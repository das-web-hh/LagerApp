package ru.storage.app

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Один сохранённый API-ключ (провайдер + ключ + при необходимости модель и адрес). */
data class AiKey(
    val id: String,
    val label: String,
    val provider: String,
    val key: String,
    val model: String = "",
    val base: String = ""
)

/** Список ключей ИИ — только на этом телефоне. Один из них активный: им идёт распознавание. */
object AiKeys {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("ai", Context.MODE_PRIVATE)

    fun newId(): String = java.util.UUID.randomUUID().toString()

    fun providerName(p: String): String = when (p) {
        "gemini" -> "Gemini (Google)"
        "openai" -> "OpenAI-совместимый"
        else -> "Claude (Anthropic)"
    }

    /** Подпись для списков: название (или провайдер) и последние 4 символа ключа. */
    fun describe(k: AiKey): String {
        val name = k.label.ifBlank { providerName(k.provider) }
        val tail = if (k.key.length >= 4) " ···" + k.key.takeLast(4) else ""
        return name + tail
    }

    fun toJson(l: List<AiKey>): String {
        val arr = JSONArray()
        l.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("label", it.label).put("provider", it.provider)
                    .put("key", it.key).put("model", it.model).put("base", it.base)
            )
        }
        return arr.toString()
    }

    fun fromJson(s: String?): List<AiKey> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("key").trim()
                if (k.isEmpty()) null
                else AiKey(
                    o.optString("id").ifBlank { newId() }, o.optString("label"),
                    o.optString("provider", "claude"), k, o.optString("model"), o.optString("base")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Старая одиночная настройка (provider/key/model/base) превращается в первый ключ списка. */
    private fun migrate(ctx: Context) {
        val p = sp(ctx)
        if (p.contains("keys")) return
        val old = (p.getString("key", "") ?: "").trim()
        if (old.isEmpty()) {
            p.edit().putString("keys", "[]").apply()
            return
        }
        val k = AiKey(
            newId(), "", p.getString("provider", "claude") ?: "claude", old,
            (p.getString("model", "") ?: "").trim(), (p.getString("base", "") ?: "").trim()
        )
        p.edit().putString("keys", toJson(listOf(k))).putString("active", k.id).apply()
    }

    fun list(ctx: Context): List<AiKey> {
        migrate(ctx)
        return fromJson(sp(ctx).getString("keys", "[]"))
    }

    fun active(ctx: Context): AiKey? {
        val l = list(ctx)
        val id = sp(ctx).getString("active", "") ?: ""
        return l.firstOrNull { it.id == id } ?: l.firstOrNull()
    }

    fun activeId(ctx: Context): String = active(ctx)?.id ?: ""

    /** Заменить весь список ключей (загрузка файла настроек). */
    fun replaceAll(ctx: Context, keys: List<AiKey>, activeId: String) {
        val act = if (keys.any { it.id == activeId }) activeId else keys.firstOrNull()?.id ?: ""
        sp(ctx).edit().putString("keys", toJson(keys)).putString("active", act).apply()
    }

    fun setActive(ctx: Context, id: String) {
        sp(ctx).edit().putString("active", id).apply()
    }

    /** Добавить ключ в настройки (по умолчанию сразу сделать активным). */
    fun add(ctx: Context, k: AiKey, makeActive: Boolean = true) {
        val l = list(ctx) + k
        val e = sp(ctx).edit().putString("keys", toJson(l))
        if (makeActive || l.size == 1) e.putString("active", k.id)
        e.apply()
    }

    fun update(ctx: Context, k: AiKey) {
        val l = list(ctx).map { if (it.id == k.id) k else it }
        sp(ctx).edit().putString("keys", toJson(l)).apply()
    }

    fun remove(ctx: Context, id: String) {
        val l = list(ctx).filter { it.id != id }
        val e = sp(ctx).edit().putString("keys", toJson(l))
        if (activeId(ctx) == id) e.putString("active", l.firstOrNull()?.id ?: "")
        e.apply()
    }
}

/** Настройки ИИ — только на этом телефоне (ключи в Firebase не отправляются). */
object AiCfg {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("ai", Context.MODE_PRIVATE)

    fun provider(ctx: Context): String = AiKeys.active(ctx)?.provider ?: "claude"
    fun key(ctx: Context): String = AiKeys.active(ctx)?.key?.trim() ?: ""
    fun baseUrl(ctx: Context): String =
        (AiKeys.active(ctx)?.base ?: "").trim().ifBlank { "https://api.openai.com/v1" }
    fun timeoutSec(ctx: Context): Int = sp(ctx).getInt("timeout", 60)
    fun attempts(ctx: Context): Int = sp(ctx).getInt("attempts", 3)
    fun rawModel(ctx: Context): String = (AiKeys.active(ctx)?.model ?: "").trim()
    fun model(ctx: Context): String = rawModel(ctx).ifBlank { defaultModel(provider(ctx)) }
    fun ready(ctx: Context): Boolean = key(ctx).isNotEmpty()

    fun defaultModel(provider: String): String = when (provider) {
        "gemini" -> "gemini-2.5-flash"
        "openai" -> "gpt-4o-mini"
        else -> "claude-haiku-5-5"
    }

    /**
     * false — обычная проверка: каждый лист уходит в ИИ один раз (только название и количество), токены экономятся;
     * true — проверка с шаблоном: после чтения ИИ сверяет лист по шаблону поставщика и создаёт новый шаблон (больше токенов).
     */
    fun templateMode(ctx: Context): Boolean = sp(ctx).getBoolean("template", false)
    fun setTemplateMode(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("template", on).apply()
    }

    fun saveCommon(ctx: Context, timeout: Int, attempts: Int) {
        sp(ctx).edit().putInt("timeout", timeout).putInt("attempts", attempts).apply()
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
    val qtyCrossed: Boolean = false,
    /** Штрих-код (EAN/GTIN) строки, если он напечатан в документе; "" — не найден. */
    val barcode: String = ""
)
/**
 * Электронная почта в названии отправителя не нужна: оставляем только слово между «@» и последней точкой.
 * info@papier.com → papier (всё до @ и последняя точка с доменом удаляются). Остальной текст не меняется.
 */
fun cleanSenderName(raw: String): String {
    val re = Regex("""[A-Za-z0-9._%+\-]+@([A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*)""")
    if (!re.containsMatchIn(raw)) return raw.trim()
    return re.replace(raw) { m ->
        val host = m.groupValues[1]
        val i = host.lastIndexOf('.')
        if (i > 0) host.substring(0, i) else host
    }.trim().trimEnd('.', ',', ';').trim()
}

data class PageResult(val sender: String, val order: String, val items: List<AiItem>)

/** Лёгкий первый запрос: только отправитель и номер заказа (без товаров). */
data class IdResult(val sender: String, val order: String)

object Ai {
    private val ORDER = Regex("[ЕеEe][БбBb6]\\s*[-–]?\\s*(\\d{7})")

    /** «EB» (латиница) + 7 цифр или "none". */
    fun normalizeOrder(s: String): String {
        val m = ORDER.find(s) ?: return "none"
        return "EB" + m.groupValues[1]   // латиница
    }

    /** Старые записи в базе хранят номер с кириллическим «ЕБ»: для сравнения и поиска приводим к одному виду. */
    fun latinOrder(s: String): String = ORDER.find(s)?.let { "EB" + it.groupValues[1] } ?: s.trim()
    fun legacyOrder(s: String): String = ORDER.find(s)?.let { "ЕБ" + it.groupValues[1] } ?: s.trim()

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
    fun call(ctx: Context, prompt: String, png: ByteArray?, json: Boolean = png != null): String {
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
                if (json) cfg.put("responseMimeType", "application/json")
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    .put("generationConfig", cfg)
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
                val r = post(url, mapOf("x-goog-api-key" to key), body.toString(), timeout)
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
                val body = JSONObject().put("model", model).put("max_tokens", 3000).put(
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

    /** Короткий запрос: лишнее в ИИ не отправляем. marks=false (приём по имени) — про пометки ручкой не спрашиваем вообще. */
    private fun prompt(page: Int, total: Int, rules: String = "", marks: Boolean = true): String {
        val base = """
        Page $page/$total of a scanned goods-receipt (delivery note or invoice; German, sometimes Czech/Dutch). May be grey, skewed, rotated: read anyway. Do not translate.
        Answer ONLY with compact JSON, no other text: {"i":[{"n":"name","q":"qty"}]}
        Add the keys m, h, x, b to a line only when they apply, otherwise leave them out.
        i = every goods line of this page in page order, also struck-out lines and lines with quantity 0.
        Not goods (skip): freight, pallets, deposits, packaging, shipping paper, shipping bags, totals, table headers, addresses, footers.
        n = product name as written; append the wrapped name part or size line ("20 kg"); no article number, expiry date, batch or position number.
        q = delivered piece count as printed (Stück, Anz., Menge, Liefermenge, Množství, Aantal ...), number only. Pallets times pieces ("9x24") write as printed. Never weight, price, article number, ordered or open columns.
        b = EAN/GTIN (8-14 digits) of this line, only if printed.
        """.trimIndent()
        val pen = """
        Pen marks (often blue) in the left margin belong to the row they are aligned with; decide per row.
        m = "c" tick; "x" hand-drawn X or whole line struck through (goods did not arrive). Tick and X on the same row: "x".
        x = 1 only if the printed quantity itself is crossed out / X-ed while the rest of the line is not struck through.
        h = quantity handwritten with a pen next to the line, number only.
        Ignore other handwriting (names, dates, pallet counts like 6P, signatures).
        """.trimIndent()
        return base + (if (marks) "\n" + pen else "") +
            (if (rules.isBlank()) "" else "\nSupplier layout notes (more important than the rules above):\n" + rules.trim())
    }

    private fun idPrompt(page: Int, total: Int) = """
        This is page $page of $total of a scanned goods-receipt document (Lieferschein / delivery note / packing slip / invoice). It may be black-and-white or grayscale, a bit blurry, skewed, rotated or upside down: read it anyway.
        The document is almost always in German; sometimes in Czech or Dutch. Do NOT translate anything.
        Find ONLY two things on THIS page. Do not read the goods table. Answer with JSON only (no markdown, no comments):
        {"sender": "...", "order_number": "..."}
        - sender: the company that SENT the goods (supplier / shipper / seller), usually in the letterhead, logo or the small sender line at the top (words like Absender, Lieferant, Verkäufer, Odesílatel, Dodavatel, Afzender, Leverancier). It is NOT the recipient (Empfänger, Lieferadresse, Příjemce, Odběratel, Ontvanger; here usually "Ströh E-Commerce GmbH"). Company name only, without street or city. NEVER output an e-mail address: if the sender is known only from an e-mail (for example info@papier.com), output just the word between "@" and the last dot, i.e. delete everything up to and including "@" and delete the last "." together with everything after it (info@papier.com -> papier). If it is not on this page, use "none".
        - order_number: a code that starts with ЕБ (Cyrillic) or EB (Latin) followed by exactly 7 digits, for example ЕБ1234567. It may be written with a space or hyphen (EB 1234567, EB-1234567), usually next to a label like Bestellnummer / Ihre Bestell-Nr. / Bestell-Nr. / Objednávka / Bestelnummer (not the Auftrag, Kunden- or Lieferschein number). It may also stand inside a text or a line of the table (for example "BelegNr-EB2612798"). If it is not on this page, use "none".
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

    /** Быстрая проверка листа: только отправитель и номер заказа. Бросает исключение при ошибке/таймауте. */
    fun identify(ctx: Context, png: ByteArray, page: Int, total: Int): IdResult {
        val o = parseJson(call(ctx, idPrompt(page, total), png))
        val sender = cleanSenderName(o.optString("sender", "none").trim()).ifBlank { "none" }
        return IdResult(sender, normalizeOrder(o.optString("order_number", "none")))
    }

    /** Распознать один лист. Бросает исключение при ошибке/таймауте — вызывающий повторит. */
    fun recognize(ctx: Context, png: ByteArray, page: Int, total: Int, rules: String = "", marks: Boolean = true): PageResult {
        val text = call(ctx, prompt(page, total, rules, marks), png)
        val o = parseJson(text)
        val items = ArrayList<AiItem>()
        val arr = o.optJSONArray("i") ?: o.optJSONArray("items")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                fun str(vararg keys: String): String {
                    for (k in keys) {
                        val v = it.opt(k)?.toString()?.trim() ?: continue
                        if (v.isNotEmpty() && !v.equals("null", true)) return v
                    }
                    return ""
                }
                val name = str("n", "name")
                if (name.isEmpty() || NonGoods.isNonGoods(name)) continue
                val fx = QtyExpand.apply(name, str("q", "qty"))
                if (!marks) {
                    val bc0 = str("b", "barcode").filter { ch -> ch.isDigit() }.let { d -> if (d.length in 8..14) d else "" }
                    items.add(AiItem(fx.name, fx.qty, "none", "", false, bc0))
                    continue
                }
                val mark = when (str("m", "mark").lowercase()) {
                    "c", "check", "tick", "ok" -> "check"
                    "x", "cross" -> "cross"
                    else -> "none"
                }
                val crossed = str("x", "qty_crossed").lowercase().let { v -> v == "1" || v == "true" }
                val bc = str("b", "barcode").filter { ch -> ch.isDigit() }.let { d -> if (d.length in 8..14) d else "" }
                items.add(AiItem(fx.name, fx.qty, mark, str("h", "hand_qty"), crossed, bc))
            }
        }
        // отправитель и номер заказа ИИ не читает: их находит приложение (DocIds)
        return PageResult("none", "none", items)
    }
}
