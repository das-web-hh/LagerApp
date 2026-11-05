package ru.storage.app

import android.graphics.Rect

/**
 * Результат распознавания снимка в «Инвентаризации» (кнопка «+» при пустом поиске): одна позиция списка.
 * Этикетки Ströh (артикул, название, цена, штрих-код EAN) разбирает StrohLabel, списки с монитора и бумаги — ListPhoto.
 */
data class ScannedLabel(
    val key: String,
    val barcode: String,
    val article: String = "",
    val name: String = "",
    /** phone — прочитал телефон, ai — прочитал ИИ */
    val via: String = ""
)

object StrohLabel {
    private val RE_ART = Regex("^\\d{4,8}$")
    private val RE_ART_NAME = Regex("^(\\d{4,8})\\s+(\\p{L}.{2,})$")
    private val RE_PRICE = Regex("^[€\\s]*\\d+[.,]\\d{2}\\s*€?$")
    private val RE_TIER = Regex("(?i)\\bab\\s*\\d+\\s*st")

    class Parsed(val barcode: String, val article: String, val name: String, val region: Rect)

    /** Ключ товара: тот же код в записи UPC-A (12) и EAN-13 с нулём — один и тот же. */
    fun key(barcode: String): String = barcode.trim().trimStart('0')

    /**
     * Область этикетки по её штрих-коду: штрих-код у Ströh стоит в правом верхнем углу,
     * сама этикетка шире штрих-кода примерно в 3 раза и выше примерно в 3 раза.
     */
    fun region(b: Rect): Rect {
        val w = b.width()
        val h = b.height()
        return Rect(b.right - (w * 3.25f).toInt(), b.top - (h * 0.35f).toInt(), b.right + (w * 0.12f).toInt(), b.top + (h * 3.1f).toInt())
    }

    /** barcodes — все штрих-коды кадра, lines — все строки текста кадра (в координатах повёрнутого кадра). */
    fun parseFrame(barcodes: List<Pair<String, Rect>>, lines: List<Pair<String, Rect>>): List<Parsed> {
        if (barcodes.isEmpty()) return emptyList()
        val regions = barcodes.map { region(it.second) }
        // каждая строка принадлежит ближайшей сверху этикетке, в область которой она попала
        val owned = barcodes.map { ArrayList<Pair<String, Rect>>() }
        for (l in lines) {
            val cx = l.second.centerX()
            val cy = l.second.centerY()
            var best = -1
            var bestTop = Int.MIN_VALUE
            regions.forEachIndexed { i, reg ->
                if (reg.contains(cx, cy)) {
                    val bt = barcodes[i].second.top
                    if (bt <= cy && bt > bestTop) { best = i; bestTop = bt }
                }
            }
            if (best >= 0) owned[best].add(l)
        }
        return barcodes.mapIndexed { i, bc ->
            val code = bc.first
            val core = key(code)
            val bar = bc.second
            val mine = owned[i].sortedBy { it.second.top }
            // цифры под штрих-кодом (и их обрывки) — это не артикул
            fun isCodeDigits(l: Pair<String, Rect>): Boolean {
                val compact = l.first.replace(" ", "")
                if (compact.length >= 2 && compact.all { it.isDigit() } && core.contains(compact.trimStart('0'))) return true
                val r = l.second
                return r.left >= bar.left - bar.width() * 0.1f && r.top < bar.bottom + bar.height() * 0.9f
            }
            var art = ""
            var artLine: Pair<String, Rect>? = null
            var nameFirst = ""
            for (l in mine) {
                if (isCodeDigits(l)) continue
                val t = l.first.trim()
                val compact = t.replace(" ", "")
                if (RE_ART.matches(compact)) { art = compact; artLine = l; break }
                val m = RE_ART_NAME.find(t)
                if (m != null) { art = m.groupValues[1]; artLine = l; nameFirst = m.groupValues[2].trim(); break }
            }
            val parts = ArrayList<String>()
            if (nameFirst.isNotEmpty()) parts.add(nameFirst)
            val al = artLine
            if (al != null) {
                val aMid = al.second.centerY()
                for (l in mine) {
                    if (l === al || isCodeDigits(l)) continue
                    val r = l.second
                    if (r.centerY() <= aMid + r.height() * 0.3f) continue // название — строго под артикулом
                    val t = l.first.trim()
                    val low = t.lowercase()
                    // «% ab 2 Stück nur …» — конец названия
                    if (t.startsWith("%") || low.contains("stück") || low.contains("stuck") || RE_TIER.containsMatchIn(t)) break
                    if (t.contains('€') || RE_PRICE.matches(t)) continue
                    parts.add(t)
                    if (parts.size >= 3) break
                }
            }
            val name = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            Parsed(code, art, if (name.any { it.isLetter() }) name else "", regions[i])
        }
    }
}
