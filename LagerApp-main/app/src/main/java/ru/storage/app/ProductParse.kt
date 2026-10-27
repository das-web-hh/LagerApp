package ru.storage.app

import android.content.Context
import org.json.JSONObject

/** Результат разбора названия товара на поля карточки. Пустая строка — поля нет в тексте. */
data class ProductParts(
    val manufacturer: String,
    val name: String,
    val size: String,
    val unit: String,
    val pack: String
)

/**
 * Разбор строки накладной («DERBY Faser Plus 15 kg Sack») на поля карточки товара.
 * ИИ возвращает только чистый JSON с фиксированными кодами, а перевод кодов в «кг / Мешок» делает код —
 * так результат одинаковый для немецких, английских и других накладных.
 */
object ProductParse {
    private fun prompt(text: String, context: String) = """
        You split ONE product line from a goods-receipt / delivery note / invoice into fields. The text may be German, English, Czech, Dutch or mixed. Do NOT translate the product name.
        Answer with JSON only (no markdown, no comments):
        {"manufacturer": "", "name": "", "size": "", "unit": "", "pack": ""}
        Rules:
        - manufacturer: the brand / producer, if it is in the text (for example "DERBY", "marstall", "Keralit", "EQUIPUR", "AG", "St. Hippolyt"). Otherwise use the hint below if it is clearly the producer. Otherwise "".
        - name: the product name WITHOUT manufacturer, size, unit, package word, article number, batch (Charge), expiry (MHD) and price. Keep the original language and spelling.
        - size: ONE number: weight or volume of ONE package, with a dot as the decimal separator (for example "15", "2.5", "500"). Multipacks like "2x5 kg" or "28 x 40g" -> put the size of one piece in size (5, 40). If there is no size, "".
        - unit: exactly one of: kg, g, t, l, ml, pcs (pcs only if it is counted in pieces, for example "90 Kapseln"). Map Lt / ltr / Liter / L to l. If there is no size, "".
        - pack: exactly one of: sack, bucket, bottle, can, canister, box, pack, bag, bale, tube, other, none. Hints: Sack / Säcke = sack; Eimer = bucket; Flasche / Fla = bottle; Dose / Tin = can; Kanister / Kan = canister; Karton = box; Beutel / Tüte = bag; Ballen = bale; Packung / Pack = pack. If the text does not say, use "none".
        - Never invent values that are not in the text. Do not use article numbers as size.
        Hint (letterhead or sender of the document, may be empty): $context
        Product line: $text
    """.trimIndent()

    private fun unitRu(code: String): String = when (code.trim().lowercase()) {
        "kg" -> "кг"
        "g" -> "г"
        "t" -> "т"
        "l" -> "л"
        "ml" -> "мл"
        "pcs" -> "шт"
        else -> ""
    }

    private fun packRu(code: String): String = when (code.trim().lowercase()) {
        "sack" -> "Мешок"
        "bucket" -> "Ведро"
        "bottle" -> "Бутылка"
        "can" -> "Банка"
        "canister" -> "Канистра"
        "box" -> "Коробка"
        "pack" -> "Пачка"
        "bag" -> "Пакет"
        "bale" -> "Тюк"
        "tube" -> "Тюбик"
        else -> ""
    }

    private fun clean(s: String?): String =
        (s ?: "").trim().let { if (it.equals("null", true) || it.equals("none", true)) "" else it }

    /** Бросает исключение при ошибке сети/ключа. Вызывать не в главном потоке. */
    fun parse(ctx: Context, text: String, hint: String = ""): ProductParts {
        val answer = Ai.call(ctx, prompt(text.trim(), hint.ifBlank { "-" }), null)
        val s = answer.indexOf('{')
        val e = answer.lastIndexOf('}')
        if (s < 0 || e <= s) throw RuntimeException("ИИ вернул ответ без JSON")
        val o = JSONObject(answer.substring(s, e + 1))
        val size = clean(o.opt("size")?.toString()).replace(',', '.')
            .let { if (it.toDoubleOrNull() == null) "" else it.removeSuffix(".0") }
        val unit = if (size.isEmpty()) "" else unitRu(clean(o.optString("unit")))
        return ProductParts(
            manufacturer = clean(o.optString("manufacturer")),
            name = clean(o.optString("name")),
            size = size,
            unit = unit,
            pack = packRu(clean(o.optString("pack")))
        )
    }

    private val SIZE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(kg|ml|mg|g|lt|ltr|liter|l|t)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val PACKS = listOf(
        "sack" to Regex("\\b(sack|säcke|sacke|sak)\\b", RegexOption.IGNORE_CASE),
        "bucket" to Regex("\\b(eimer)\\b", RegexOption.IGNORE_CASE),
        "bottle" to Regex("\\b(flasche|fla|bottle)\\b", RegexOption.IGNORE_CASE),
        "can" to Regex("\\b(dose|tin)\\b", RegexOption.IGNORE_CASE),
        "canister" to Regex("\\b(kanister|kan)\\b", RegexOption.IGNORE_CASE),
        "box" to Regex("\\b(karton)\\b", RegexOption.IGNORE_CASE),
        "bag" to Regex("\\b(beutel|tüte|tute)\\b", RegexOption.IGNORE_CASE),
        "bale" to Regex("\\b(ballen)\\b", RegexOption.IGNORE_CASE),
        "tube" to Regex("\\b(tube|tubus)\\b", RegexOption.IGNORE_CASE),
        "pack" to Regex("\\b(packung|pack)\\b", RegexOption.IGNORE_CASE)
    )

    /** Разбор названий на телефоне: вес/объём, единица и упаковка — по словам и числам в самой строке. Производитель — первое слово, если оно написано заглавными (DERBY, EQUIPUR). */
    fun parseLocal(names: List<String>): List<ProductParts?> = names.map { raw ->
        val t = raw.trim()
        if (t.isEmpty()) return@map null
        val m = SIZE.find(t)
        val size = m?.groupValues?.get(1)?.replace(',', '.')?.removeSuffix(".0") ?: ""
        val unitCode = when (m?.groupValues?.get(2)?.lowercase()) {
            "kg" -> "kg"
            "g" -> "g"
            "t" -> "t"
            "ml" -> "ml"
            "l", "lt", "ltr", "liter" -> "l"
            else -> ""
        }
        val pack = PACKS.firstOrNull { it.second.containsMatchIn(t) }?.first ?: "none"
        val first = t.split(Regex("\\s+")).firstOrNull() ?: ""
        val manufacturer = if (first.length >= 3 && first.all { it.isUpperCase() || it.isDigit() || it == '-' } && first.any { it.isLetter() }) first else ""
        ProductParts(
            manufacturer = manufacturer,
            name = t,
            size = size,
            unit = if (size.isEmpty()) "" else unitRu(unitCode),
            pack = packRu(pack)
        )
    }

    private fun promptMany(lines: List<String>, hint: String) = """
        You split product lines from ONE goods-receipt / delivery note into fields. The text may be German, English, Czech, Dutch or mixed. Do NOT translate product names.
        Answer with JSON only (no markdown, no comments), one object per input line, in the same order, exactly ${lines.size} objects:
        {"items": [{"manufacturer": "", "name": "", "size": "", "unit": "", "pack": ""}]}
        Rules:
        - manufacturer: the brand / producer if it is in the line (for example "DERBY", "marstall", "Keralit", "EQUIPUR", "AG", "bitopEQUI"). If the line has none but the document hint below is clearly the producer of these goods, use the hint. Otherwise "".
        - name: the product name WITHOUT manufacturer, size, unit, package word, article number, batch (Charge), expiry (MHD) and price. Keep the original language and spelling.
        - size: ONE number: weight or volume of ONE package, dot as decimal separator ("15", "2.5", "500"). Multipacks like "2x5 kg" or "28 x 40g": the size of one piece. No size in the line: "".
        - unit: exactly one of: kg, g, t, l, ml, pcs (pcs only if counted in pieces, e.g. "90 Kapseln"). Lt / ltr / Liter / L = l. No size: "".
        - pack: exactly one of: sack, bucket, bottle, can, canister, box, pack, bag, bale, tube, other, none. Sack = sack; Eimer = bucket; Flasche / Fla = bottle; Dose = can; Kanister / Kan = canister; Karton = box; Beutel / Tüte = bag; Ballen = bale; Packung = pack. Not stated: "none".
        - Never invent values that are not in the line. Never use article numbers as size.
        Document hint (sender, may be empty): $hint
        Lines:
        ${lines.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")}
    """.trimIndent()

    private fun fromJson(o: JSONObject): ProductParts {
        val size = clean(o.opt("size")?.toString()).replace(',', '.')
            .let { if (it.toDoubleOrNull() == null) "" else it.removeSuffix(".0") }
        return ProductParts(
            manufacturer = clean(o.optString("manufacturer")),
            name = clean(o.optString("name")),
            size = size,
            unit = if (size.isEmpty()) "" else unitRu(clean(o.optString("unit"))),
            pack = packRu(clean(o.optString("pack")))
        )
    }

    /** Разбор сразу многих строк одним запросом (по 40 штук). Результат — в том же порядке; null — строку не удалось разобрать. */
    fun parseMany(ctx: Context, names: List<String>, hint: String = ""): List<ProductParts?> {
        val out = ArrayList<ProductParts?>()
        for (chunk in names.chunked(40)) {
            val res: List<ProductParts?> = try {
                val answer = Ai.call(ctx, promptMany(chunk.map { it.trim() }, hint.ifBlank { "-" }), null)
                val s = answer.indexOf('{')
                val e = answer.lastIndexOf('}')
                val arr = JSONObject(answer.substring(s, e + 1)).optJSONArray("items")
                if (arr == null || arr.length() != chunk.size) List(chunk.size) { null }
                else List(chunk.size) { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
            } catch (ex: Exception) {
                List(chunk.size) { null }
            }
            out.addAll(res)
        }
        return out
    }
}
