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
}
