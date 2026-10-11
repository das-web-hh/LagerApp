package ru.storage.app

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Рукописная подпись принимающего: имя из профиля + актуальная дата, штрихами как в образце (SVG «Hazrat 27.07.26»).
 * Есть все буквы (латиница, кириллица, ä ö ü ß) и цифры; незнакомые буквы с акцентами рисуются как базовая буква.
 */
object Signature {
    private class G(val d: String, val w: Double)

    private val map: Map<Char, G> by lazy {
        val m = HashMap<Char, G>()
        for ((chars, w, d) in SIGNATURE_GLYPHS) {
            val g = G(d, w)
            for (c in chars) m[c] = g
        }
        m
    }

    private fun glyph(c: Char): G? {
        map[c]?.let { return it }
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: return null
        return map[base]
    }

    private fun hash(s: String): Int {
        var h = 7
        for (c in s) h = h * 31 + c.code
        return h and 0x7fffffff
    }

    private fun f1(d: Double) = String.format(Locale.ROOT, "%.1f", d)

    /** Рисует строку штрихами, возвращает x после последней буквы. Лёгкий наклон и «дрожание» — детерминированные, подпись не меняется между печатями. */
    private fun strokes(text: String, startX: Double, seed: Int, out: StringBuilder): Double {
        var x = startX
        text.forEachIndexed { i, c ->
            if (c == ' ') {
                x += 16.0
                return@forEachIndexed
            }
            val g = glyph(c) ?: return@forEachIndexed
            val k = hash("$seed:$i:$c")
            val rot = (k % 7) - 3
            val dy = ((k / 7) % 5) - 2
            out.append("<path d='").append(g.d).append("' transform='translate(")
                .append(f1(x)).append(' ').append(dy)
                .append(") rotate(").append(rot).append(' ').append(f1(g.w / 2)).append(" 82)'/>")
            x += g.w + 7.0
        }
        return x
    }

    /** SVG-подпись: имя и дата (dd.MM.yy). Пустое имя — пустая строка. */
    fun svg(name: String, ms: Long): String {
        val n = name.trim()
        if (n.isEmpty()) return ""
        val date = SimpleDateFormat("dd.MM.yy", Locale.ROOT).format(Date(ms))
        val sb = StringBuilder()
        var x = strokes(n, 20.0, hash(n), sb)
        x += 24.0
        x = strokes(date, x, 17, sb)
        val w = (x + 12.0).toInt()
        val h = 100
        val dispH = 54
        val dispW = w * dispH / h
        return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 14 $w $h' width='$dispW' height='$dispH' class='sigsvg'>" +
            "<g fill='none' stroke='#1A1A1A' stroke-width='2.8' stroke-linecap='round' stroke-linejoin='round'>" +
            "<g transform='skewX(-8)'>" + sb + "</g></g></svg>"
    }

    const val CSS =
        ".sigsvg{display:block;max-width:100%}" +
            ".sigbox{margin-top:26px;display:inline-block;max-width:100%}" +
            ".sigcap{font-size:11px;color:#444;padding-top:2px}" +
            ".sigspace{height:90px;display:flex;align-items:flex-end}" +
            ".h1s{font-size:13px;font-weight:normal;color:#444}"

    /** Блок подписи без линии (для актов, где линий для подписи не было). Выключена автоподпись — ничего не выводится. */
    fun box(capKey: String, name: String, ms: Long): String {
        if (!PrintCfg.sign || name.isBlank()) return ""
        return "<div class='sigbox'>" + svg(name, ms) + "<div class='sigcap'>" + tr(capKey) + "</div></div>"
    }

    /** Ячейка с подписью над линией. Автоподпись выключена — только линия для подписи от руки. */
    fun cell(capKey: String, name: String, ms: Long, auto: Boolean = true): String {
        val s = if (auto && PrintCfg.sign) svg(name, ms) else ""
        return "<div class='sigspace'>" + s + "</div><div class='line'>" + tr(capKey) + "</div>"
    }
}
