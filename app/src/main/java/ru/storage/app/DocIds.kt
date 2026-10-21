package ru.storage.app

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer

/**
 * Номер заказа и имя отправителя определяет само приложение (ML Kit OCR + правила), без ИИ.
 *
 * Номер заказа: EB + 7 цифр (EB1234567; допускаются пробел/дефис и кириллические Е/Б, которые путает OCR).
 *
 * Имя отправителя:
 *  1) e-mail на листе: this@emailtest.com → emailtest;
 *  2) нет e-mail → адреса с «.de» (www.webtest.de → webtest);
 *  3) нет .de → другие сайты с www./http (this.webtest.com → webtest).
 * Наш адрес (Ströh) пропускается. Сначала ищем на 1-м листе; если там имени нет — на 2-м и далее.
 * Не нашли — пусто (\"none\").
 */
object DocIds {
    data class Ids(val sender: String, val order: String)

    private val ORDER = Regex("(?<![A-Za-z0-9])[EeЕе][BbБб6]\\s*[-–]?\\s*(\\d{7})(?!\\d)", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@([A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)+)")
    private val HOST = Regex("(?<![@A-Za-z0-9.\\-])((?:[A-Za-z0-9\\-]+\\.)+[A-Za-z]{2,})(?![A-Za-z0-9\\-])")

    /** Бесплатные почтовые сервисы: имя отправителя из них не берём. */
    private val FREE_MAIL = setOf(
        "gmail", "googlemail", "gmx", "web", "t-online", "outlook", "hotmail", "yahoo", "icloud",
        "freenet", "mail", "aol", "live", "posteo"
    )

    /** Приводим к виду без умлаутов и регистра: Ströh → stroh, STROEH → stroeh. */
    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("ß", "ss")

    /** Наше имя в любом написании: Stroeh, STROEH, STRÖH, Ströh, ströh и т.п. */
    fun isOwn(name: String): Boolean {
        val f = fold(name)
        return f.contains("stroh") || f.contains("stroeh")
    }

    private fun label(host: String): String {
        val parts = host.lowercase().trim('.').split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return ""
        // второй уровень: this.webtest.com → webtest; shop.example.co.uk → example
        val second = setOf("co", "com", "org", "net", "gov", "ac")
        val i = if (parts.size >= 3 && parts[parts.size - 2] in second && parts.last().length == 2) parts.size - 3 else parts.size - 2
        return parts[i.coerceAtLeast(0)]
    }

    private fun accept(name: String): Boolean =
        name.length >= 2 && !isOwn(name) && name !in FREE_MAIL && name.any { it.isLetter() }

    /** Имя отправителя из текста одного листа или \"\". */
    fun senderFromText(text: String): String {
        // 1) электронные почты
        for (m in EMAIL.findAll(text)) {
            val n = label(m.groupValues[1])
            if (accept(n)) return n
        }
        // 2) адреса .de (сначала сайты, чтобы не путать с обрывками текста)
        val hosts = HOST.findAll(text).map { it.groupValues[1] }.toList()
        for (h in hosts) {
            if (h.lowercase().endsWith(".de")) {
                val n = label(h)
                if (accept(n)) return n
            }
        }
        // 3) другие сайты (www.… / http…), например this.webtest.com
        for (h in hosts) {
            val low = h.lowercase()
            if (low.endsWith(".de")) continue
            val marked = text.contains("www.$low", true) || low.startsWith("www.") ||
                text.contains("http://$low", true) || text.contains("https://$low", true)
            if (!marked) continue
            val n = label(h)
            if (accept(n)) return n
        }
        return ""
    }

    fun orderFromText(text: String): String {
        val m = ORDER.find(text) ?: return "none"
        return Ai.normalizeOrder("EB" + m.groupValues[1])
    }

    /** Читает листы по очереди (OCR на телефоне). Заказ ищется на всех листах, отправитель — с первого, при неудаче с последующих. */
    suspend fun find(doc: PageSource, onPage: (Int) -> Unit = {}): Ids {
        var sender = ""
        var order = "none"
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            for (i in 0 until doc.count) {
                onPage(i)
                val jpg = try { doc.renderBw(i, 2000, false) } catch (e: Exception) { continue }
                val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: continue
                var text = ""
                try {
                    text = rec.process(InputImage.fromBitmap(bmp, 0)).awaitIt().text
                } catch (e: Exception) {
                } finally {
                    bmp.recycle()
                }
                if (text.isBlank()) continue
                if (sender.isEmpty()) sender = senderFromText(text)
                if (order == "none") order = orderFromText(text)
                if (sender.isNotEmpty() && order != "none") break
            }
        } finally {
            rec.close()
        }
        return Ids(sender.ifEmpty { "none" }, order)
    }
}

/**
 * Разбор «9x24»: 9 паллет по 24 штуки = 216 штук. Применяется к ответу ИИ.
 * «2x5 kg» / «28 x 40g» (после второго числа сразу единица измерения) — это фасовка, не паллеты, такие записи не трогаем.
 */
object QtyExpand {
    private val UNIT = "(?:kg|g|mg|t|l|lt|ltr|ml|cl|cm|mm|m)"
    private val PAIR = Regex("(\\d{1,4})\\s*[xX×]\\s*(\\d{1,4})(?![\\d.,])(?!\\s*$UNIT\\b)")
    private val ONLY_PAIR = Regex("^\\s*(\\d{1,4})\\s*[xX×]\\s*(\\d{1,4})\\s*$")

    data class Fixed(val name: String, val qty: String)

    fun apply(name: String, qty: String): Fixed {
        // количество записано как «9x24»
        ONLY_PAIR.find(qty)?.let { return Fixed(name, (it.groupValues[1].toLong() * it.groupValues[2].toLong()).toString()) }
        // «produkt 9x24 15kg»: шаблон в названии
        val m = PAIR.find(name) ?: return Fixed(name, qty)
        val a = m.groupValues[1].toLong()
        val b = m.groupValues[2].toLong()
        val q = normalizeQty(qty).toDoubleOrNull()
        val total = a * b
        val plausible = qty.isBlank() || q == null || q == a.toDouble() || q == b.toDouble() || q == total.toDouble()
        if (!plausible) return Fixed(name, qty)
        val cleaned = name.replaceRange(m.range, " ").replace(Regex("\\s{2,}"), " ").trim()
        return Fixed(cleaned.ifBlank { name }, total.toString())
    }
}

/**
 * Строки, которые в накладной не товар: европаллеты, фрахт, упаковка, «Versandpapier», «Versandbeutel» (сколько пакетиков отправлено) и т.п.
 * Такие строки отбрасываются сразу после ответа ИИ, в приём они не попадают.
 */
object NonGoods {
    private val RE = Regex(
        "(europal|euro[- ]?pal|ew[- ]?pal|einweg[- ]?pal|epal|chep|pallet|palett|\\bpalette\\b|" +
            "fracht|versand|verpackung|leihgeb|pfand|gutschrift|porto|transportkosten|frachtkosten)",
        RegexOption.IGNORE_CASE
    )

    fun isNonGoods(name: String): Boolean = RE.containsMatchIn(name)
}
