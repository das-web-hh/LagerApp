package ru.storage.app

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.util.Locale

/**
 * Палеты: ИИ иногда берёт из колонки «Menge» число палет («2 Palette») вместо штук.
 * Лист читается ещё раз на телефоне (OCR, токены не тратятся) и количество сверяется с напечатанным под названием:
 *   «1 Palette = 39 Sack => Gesamt: 78 Sack»  → 78 штук
 *   «Chargennr.: … Menge: 7 Eimer», «… Menge: 13 Eimer» → 20 штук
 * Количество заменяется только если ответ ИИ — делитель напечатанного итога (2 → 78), поэтому
 * правильно прочитанные строки не трогаются, а ошибка OCR в цифре не портит верный ответ ИИ.
 */
object PalletFix {
    private class Ln(val text: String, val top: Int, val left: Int)

    private val GESAMT = Regex("Gesamt\\s*:?\\s*(\\d{1,6})\\s*[A-Za-zÄÖÜäöüß]+", RegexOption.IGNORE_CASE)
    private val MENGE = Regex("Menge\\s*:?\\s*(\\d{1,6})\\s*[A-Za-zÄÖÜäöüß]+", RegexOption.IGNORE_CASE)
    private val PER_PAL = Regex("Pal\\w*\\.?\\s*=\\s*(\\d{1,5})", RegexOption.IGNORE_CASE)

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("ß", "ss")

    private fun tokens(s: String): Set<String> =
        fold(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

    /** Насколько строка OCR похожа на название товара (число общих слов; 0 — не похожа). */
    private fun score(nameTok: Set<String>, lineTok: Set<String>): Int {
        if (nameTok.isEmpty() || lineTok.isEmpty()) return 0
        val common = nameTok.intersect(lineTok).size
        if (common < 2 && !(nameTok.size == 1 && common == 1)) return 0
        val byLine = common.toDouble() / lineTok.size
        val byName = common.toDouble() / nameTok.size
        return if (byLine >= 0.8 || byName >= 0.6) common else 0
    }

    private suspend fun ocrLines(doc: PageSource, index: Int): List<Ln> {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val jpg = doc.renderBw(index, 2400, false)
            val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: return emptyList()
            try {
                val t = rec.process(InputImage.fromBitmap(bmp, 0)).awaitIt()
                val out = ArrayList<Ln>()
                for (b in t.textBlocks) for (l in b.lines) {
                    val r = l.boundingBox
                    out.add(Ln(l.text, r?.top ?: 0, r?.left ?: 0))
                }
                return out
            } finally {
                bmp.recycle()
            }
        } catch (e: Exception) {
            return emptyList()
        } finally {
            rec.close()
        }
    }

    /** Возвращает тот же список, если исправлять нечего. */
    suspend fun apply(doc: PageSource, index: Int, items: List<AiItem>): List<AiItem> {
        if (items.isEmpty()) return items
        val lines = ocrLines(doc, index)
        return fix(lines, items)
    }

    private fun fix(lines: List<Ln>, items: List<AiItem>): List<AiItem> {
        // на листе нет ни «Gesamt», ни «Menge: N», ни «Palette = N» — проверять нечего
        if (lines.none { GESAMT.containsMatchIn(it.text) || MENGE.containsMatchIn(it.text) || PER_PAL.containsMatchIn(it.text) }) return items
        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val lineTok = sorted.map { tokens(it.text) }

        // строка листа для каждого товара: лучшее совпадение по названию
        val match = IntArray(items.size) { -1 }
        for ((k, it) in items.withIndex()) {
            val nt = tokens(it.name)
            var best = 0
            for (j in sorted.indices) {
                val s = score(nt, lineTok[j])
                if (s > best) { best = s; match[k] = j }
            }
        }
        // две позиции на одной строке листа — непонятно, где чья: такие не трогаем
        val used = match.filter { it >= 0 }.groupingBy { it }.eachCount()
        val order = items.indices.filter { match[it] >= 0 && used[match[it]] == 1 }.sortedBy { sorted[match[it]].top }

        val result = items.toMutableList()
        var changed = false
        for ((pos, k) in order.withIndex()) {
            val start = sorted[match[k]].top
            val end = order.getOrNull(pos + 1)?.let { sorted[match[it]].top } ?: Int.MAX_VALUE
            val block = sorted.indices.filter { j -> j != match[k] && sorted[j].top > start && sorted[j].top < end }.map { sorted[it].text }
            val qty = items[k].qty.trim().replace(',', '.').toDoubleOrNull() ?: continue
            if (qty <= 0.0 || qty != Math.floor(qty)) continue
            val q = qty.toInt()

            var total = 0
            block.forEach { t -> GESAMT.find(t)?.let { m -> if (total == 0) total = m.groupValues[1].toInt() } }
            if (total == 0) {
                var sum = 0
                block.forEach { t -> MENGE.find(t)?.let { m -> sum += m.groupValues[1].toInt() } }
                total = sum
            }
            val newQty = when {
                // напечатан итог: ИИ взял число палет (или его долю) — берём итог
                total > q && total % q == 0 -> total
                // итога нет, но есть «1 Palette = 39 Sack»: количество у ИИ меньше штук на палете, значит это палеты
                total == 0 -> block.firstNotNullOfOrNull { t -> PER_PAL.find(t)?.groupValues?.get(1)?.toIntOrNull() }
                    ?.takeIf { per -> per > q }?.let { per -> q * per } ?: continue
                else -> continue
            }
            result[k] = items[k].copy(qty = newQty.toString())
            changed = true
        }
        return if (changed) result else items
    }
}
