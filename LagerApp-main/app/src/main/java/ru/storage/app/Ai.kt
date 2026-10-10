package ru.storage.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Настройки ИИ — только на этом телефоне (ключ в Firebase не отправляется). */
object AiCfg {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    fun provider(ctx: Context): String = sp(ctx).getString("provider", "claude") ?: "claude"
    fun key(ctx: Context): String = (sp(ctx).getString("key", "") ?: "").trim()
    fun baseUrl(ctx: Context): String =
        (sp(ctx).getString("base", "") ?: "").trim().ifBlank { "https://api.openai.com/v1" }
    fun timeoutSec(ctx: Context): Int = sp(ctx).getInt("timeout", 60)
    fun attempts(ctx: Context): Int = sp(ctx).getInt("attempts", 3)
    fun rawModel(ctx: Context): String = (sp(ctx).getString("model", "") ?: "").trim()
    fun model(ctx: Context): String = rawModel(ctx).ifBlank { defaultModel(provider(ctx)) }
    fun ready(ctx: Context): Boolean = key(ctx).isNotEmpty()

    fun defaultModel(provider: String): String = when (provider) {
        "gemini" -> "gemini-2.5-flash"
        "openai" -> "gpt-4o-mini"
        else -> "claude-haiku-5-5"
    }

    fun save(ctx: Context, provider: String, key: String, model: String, base: String, timeout: Int, attempts: Int) {
        sp(ctx).edit().putString("provider", provider).putString("key", key.trim())
            .putString("model", model.trim()).putString("base", base.trim())
            .putInt("timeout", timeout).putInt("attempts", attempts).apply()
    }
}

/**
 * qty — печатное количество; mark — отметка ручкой слева: "check" (галочка) / "cross" (крестик или вычеркнуто) / "none";
 * qtyCrossed — печатное количество перечёркнуто; handQty — количество, дописанное ручкой.
 */
data class AiItem(
    val name: String,
    val qty: String,
    val mark: String = "none",
    val handQty: String = "",
    val qtyCrossed: Boolean = false
)
data class PageResult(val sender: String, val order: String, val items: List<AiItem>)

object Ai {
    private val ORDER = Regex("[ЕеEe][БбBb6]\\s*[-–]?\\s*(\\d{7})")

    /** «ЕБ» + 7 цифр или "none". */
    fun normalizeOrder(s: String): String {
        val m = ORDER.find(s) ?: return "none"
        return "ЕБ" + m.groupValues[1]
    }

    private fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000
            c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { c.setRequestProperty(it.key, it.value) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw RuntimeException("ИИ: HTTP $code " + text.replace(Regex("\\s+"), " ").take(200))
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    /** Один запрос к ИИ: текст + (необязательно) картинка. Возвращает текст ответа. */
    fun call(ctx: Context, prompt: String, png: ByteArray?): String {
        val provider = AiCfg.provider(ctx)
        val key = AiCfg.key(ctx)
        val model = AiCfg.model(ctx)
        val timeout = AiCfg.timeoutSec(ctx).coerceIn(5, 600) * 1000
        val b64 = png?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        val mime = if (png != null && png.size > 2 && png[0] == 0xFF.toByte()) "image/jpeg" else "image/png"
        return when (provider) {
            "gemini" -> {
                val parts = JSONArray().put(JSONObject().put("text", prompt))
                if (b64 != null) {
                    parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64)))
                }
                val cfg = JSONObject().put("temperature", 0)
                if (png != null) cfg.put("responseMimeType", "application/json")
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    .put("generationConfig", cfg)
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=" +
                    URLEncoder.encode(key, "UTF-8")
                val r = post(url, emptyMap(), body.toString(), timeout)
                r.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).getString("text")
            }
            "openai" -> {
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
                if (b64 != null) {
                    content.put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:$mime;base64,$b64"))
                    )
                }
                val body = JSONObject().put("model", model).put(
                    "messages", JSONArray().put(JSONObject().put("role", "user").put("content", content))
                )
                val url = AiCfg.baseUrl(ctx).trimEnd('/') + "/chat/completions"
                val r = post(url, mapOf("Authorization" to "Bearer $key"), body.toString(), timeout)
                r.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            }
            else -> {
                val content = JSONArray()
                if (b64 != null) {
                    content.put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject().put("type", "base64").put("media_type", mime).put("data", b64)
                        )
                    )
                }
                content.put(JSONObject().put("type", "text").put("text", prompt))
                val body = JSONObject().put("model", model).put("max_tokens", 4096).put(
                    "messages", JSONArray().put(JSONObject().put("role", "user").put("content", content))
                )
                val r = post(
                    "https://api.anthropic.com/v1/messages",
                    mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
                    body.toString(), timeout
                )
                val arr = r.getJSONArray("content")
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    if (b.optString("type") == "text") sb.append(b.optString("text"))
                }
                sb.toString()
            }
        }
    }

    private fun prompt(page: Int, total: Int) = """
        This is page $page of $total of a scanned goods-receipt document (Lieferschein / delivery note / packing slip). It may be black-and-white or grayscale, a bit blurry, skewed, rotated or upside down: read it anyway.
        The document is almost always in German; sometimes in Czech or Dutch. Do NOT translate anything.
        A warehouse worker has checked the printed lines with a pen (ticks, crosses, strike-throughs, handwritten numbers). Read these pen marks carefully: pen marks are often in blue (or other colored) ink, the printed text is black.
        A mark belongs to the table row it is vertically aligned with (usually drawn in the left margin, left of the position number). Marks of neighbouring rows can touch or overlap: decide for each row separately by where the center of the mark lies, and never give one mark to two rows.
        Extract data from THIS page only and answer with JSON only (no markdown, no comments):
        {"sender": "...", "order_number": "...", "items": [{"name": "...", "qty": "...", "mark": "check", "qty_crossed": false, "hand_qty": ""}]}
        Rules:
        - sender: the company that SENT the goods (supplier / shipper / seller), usually in the letterhead, logo or the small sender line at the top (words like Absender, Lieferant, Verkäufer, Odesílatel, Dodavatel, Afzender, Leverancier). It is NOT the recipient (Empfänger, Lieferadresse, Příjemce, Odběratel, Ontvanger; here usually "Ströh E-Commerce GmbH"). Company name only, without street or city. If it is not on this page, use "none".
        - order_number: a code that starts with ЕБ (Cyrillic) or EB (Latin) followed by exactly 7 digits, for example ЕБ1234567. It may be written with a space or hyphen (EB 1234567, EB-1234567), usually next to a label like Bestellnummer / Ihre Bestell-Nr. / Bestell-Nr. / Objednávka / Bestelnummer (not the Auftrag, Kunden- or Lieferschein number). It may also stand inside a text or a line of the table (for example "BelegNr-EB2612798"); then use it as order_number and do NOT list that line as an item. If it is not on this page, use "none".
        - The document may also be an invoice (Rechnung) or an order confirmation instead of a delivery note: treat its goods table the same way. Prices (Einzelpreis, Gesamtpreis, Preis, EUR) are never a quantity; the position number (Pos) is not part of the name.
        - items: every GOODS line of this page, in the order of the page. Lines that are crossed out, struck through or have quantity 0 must be included too — never skip a goods line.
          Skip lines that are not goods: freight (Frachtkosten, Fracht), pallets and pallet deposits (Euro-Palette, EW Pallets, Leihgebühr, Gutschrift), packaging, totals, weight summaries, table headers, addresses, footers.
        - name: the product name exactly as written (keep the original language). If the name continues on the next line (for example "Nachfüller") or a size line follows (for example "20 kg", "500 ml", "2,5 Lt"), append it to the name. Do not include article number, expiry date (Verfallsdatum) or batch (Charge) in the name.
        - qty: the PRINTED number of delivered units of this line (columns like Stück, Anz., Anzahl, Menge (if a small extra column with 1 or a unit code follows Menge, the quantity is the Menge number) with unit ST / PCE / Sack / Eimer / Dose / Kan / Fla, aktuelle Liefermenge / Liefermenge, Množství, Počet, ks, Aantal, Stuks). Number only. Do not use the weight per unit, total weight (Gesamt in kg), price, article number, batch number, "bestellt" (ordered) or "offen" (open) columns when a delivered piece count exists.
        - mark: the pen mark that belongs to this line (usually at the left of the line): "check" = a tick (✓, √ or a slash-like tick); "cross" = a hand-drawn X, or the whole line struck through with a pen (goods did not arrive); "none" = no pen mark.
        - qty_crossed: true only if the printed quantity itself is crossed out or marked with an X / correction while the rest of the line is not struck through; otherwise false.
        - hand_qty: if a quantity is handwritten with a pen next to this line or its quantity (for example "9 stk", "15"), give that number only; otherwise "".
        - Ignore handwritten notes that do not belong to a line: names, dates, pallet counts like "6P" or "7P", signatures, scribbles, arrows.
        If the page has no goods lines, use an empty items array.
    """.trimIndent()

    /** Достаём JSON из ответа; если ответ оборвался на длинной таблице — закрываем скобки. */
    private fun parseJson(text: String): JSONObject {
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) throw RuntimeException("ИИ вернул ответ без JSON")
        val cut = text.substring(s, e + 1)
        for (tail in listOf("", "]}", "}")) {
            try {
                return JSONObject(cut + tail)
            } catch (ex: Exception) {
            }
        }
        throw RuntimeException("ИИ вернул неполный JSON")
    }

    /** Распознать один лист. Бросает исключение при ошибке/таймауте — вызывающий повторит. */
    fun recognize(ctx: Context, png: ByteArray, page: Int, total: Int): PageResult {
        val text = call(ctx, prompt(page, total), png)
        val o = parseJson(text)
        val items = ArrayList<AiItem>()
        val arr = o.optJSONArray("items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val name = it.optString("name").trim()
                if (name.isEmpty()) continue
                val q = (it.opt("qty")?.toString() ?: "").trim()
                val hand = (it.opt("hand_qty")?.toString() ?: "").trim().let { h -> if (h.equals("null", true)) "" else h }
                val mark = when (it.optString("mark").trim().lowercase()) {
                    "check", "tick", "ok" -> "check"
                    "cross", "x" -> "cross"
                    else -> "none"
                }
                items.add(AiItem(name, q, mark, hand, it.optBoolean("qty_crossed", false)))
            }
        }
        val sender = o.optString("sender", "none").trim().ifBlank { "none" }
        return PageResult(sender, normalizeOrder(o.optString("order_number", "none")), items)
    }
}
