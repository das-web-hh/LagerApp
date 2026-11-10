package ru.storage.app

/** Штрих-код EAN-13 / EAN-8 (и UPC-A как EAN-13 с нулём) в виде SVG для печати. Все остальные коды — Code 128 (читается любым сканером). */
object Ean {
    private val L = arrayOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
    private val G = arrayOf("0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111")
    private val R = arrayOf("1110010", "1100110", "1101100", "1000010", "1011100", "1001110", "1010000", "1000100", "1001000", "1110100")
    private val PARITY = arrayOf("LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL")

    private val C128 = arrayOf(
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312",
        "132212", "221213", "221312", "231212", "112232", "122132", "122231", "113222",
        "123122", "123221", "223211", "221132", "221231", "213212", "223112", "312131",
        "311222", "321122", "321221", "312212", "322112", "322211", "212123", "212321",
        "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121",
        "313121", "211331", "231131", "213113", "213311", "213131", "311123", "311321",
        "331121", "312113", "312311", "332111", "314111", "221411", "431111", "111224",
        "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
        "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112",
        "421211", "212141", "214121", "412121", "111143", "111341", "131141", "114113",
        "114311", "411113", "411311", "113141", "114131", "311141", "411131", "211412",
        "211214", "211232", "2331112"
    )

    private fun checkOk(code: String): Boolean {
        val d = code.map { it - '0' }
        val n = d.size
        var sum = 0
        for (i in 0 until n - 1) {
            // справа налево после контрольной цифры: нечётные позиции ×3
            val fromRight = n - 1 - i
            sum += d[i] * (if (fromRight % 2 == 1) 3 else 1)
        }
        return (10 - sum % 10) % 10 == d[n - 1]
    }

    /** Цифры с группами как на упаковке: «4 026921 072113». */
    fun spaced(code: String): String = when (code.length) {
        13 -> code.substring(0, 1) + " " + code.substring(1, 7) + " " + code.substring(7)
        8 -> code.substring(0, 4) + " " + code.substring(4)
        else -> code
    }

    /**
     * Рисунок штрих-кода для экрана: строка модулей (1 — полоса, 0 — пробел), позиции охранных полос и сам код из 13 или 8 цифр
     * (UPC-A дополняется нулём до EAN-13). null — это не EAN-13/EAN-8 или контрольная цифра не сходится: такой код нарисовать нельзя.
     */
    fun pattern(raw: String): Triple<String, Set<Int>, String>? {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val code = if (digits.length == 12) "0$digits" else digits
        if (code.length == 13 && checkOk(code)) {
            val par = PARITY[code[0] - '0']
            val m = StringBuilder("101")
            for (i in 0 until 6) {
                val d = code[1 + i] - '0'
                m.append(if (par[i] == 'L') L[d] else G[d])
            }
            m.append("01010")
            for (i in 0 until 6) m.append(R[code[7 + i] - '0'])
            m.append("101")
            return Triple(m.toString(), setOf(0, 2, 45, 47, 92, 94), code)
        }
        if (code.length == 8 && checkOk(code)) {
            val m = StringBuilder("101")
            for (i in 0 until 4) m.append(L[code[i] - '0'])
            m.append("01010")
            for (i in 0 until 4) m.append(R[code[4 + i] - '0'])
            m.append("101")
            return Triple(m.toString(), setOf(0, 2, 32, 34, 64, 66), code)
        }
        return null
    }

    private fun bars(modules: String, x0: Int, guardIdx: Set<Int>, sb: StringBuilder) {
        var i = 0
        while (i < modules.length) {
            if (modules[i] == '1') {
                var j = i
                while (j < modules.length && modules[j] == '1') j++
                val h = if (guardIdx.contains(i)) 40 else 36
                sb.append("<rect x='${x0 + i}' y='0' width='${j - i}' height='$h'/>")
                i = j
            } else i++
        }
    }

    /** Готовый блок для ячейки таблицы: штрих-код картинкой с цифрами под ним. EAN-13/EAN-8/UPC-A с верной контрольной цифрой — как EAN, любой другой код — Code 128. */
    fun html(raw: String): String {
        val clean = raw.trim().filter { it.code in 32..126 }
        if (clean.isEmpty()) return "&mdash;"
        val digits = clean.filter { it.isDigit() }
        if (digits.length == clean.length) {
            val code = if (digits.length == 12) "0$digits" else digits
            if (code.length == 13 && checkOk(code)) return svg13(code)
            if (code.length == 8 && checkOk(code)) return svg8(code)
        }
        return svg128(clean)
    }

    /** Code 128: B (или C для цифр) со стартом, контрольным символом и тихими зонами по 10 модулей. Высота та же, что у EAN (модуль 0,345 мм). */
    private fun svg128(text: String): String {
        val v = ArrayList<Int>()
        if (text.length >= 4 && text.all { it.isDigit() }) {
            var i = 0
            if (text.length % 2 == 1) { v.add(104); v.add(text[0] - ' '); v.add(99); i = 1 } else v.add(105)
            while (i < text.length) { v.add(text.substring(i, i + 2).toInt()); i += 2 }
        } else {
            v.add(104)
            for (ch in text) v.add(ch - ' ')
        }
        var sum = v[0]
        for (k in 1 until v.size) sum += v[k] * k
        v.add(sum % 103)
        v.add(106)
        val sb = StringBuilder()
        var x = 10
        for (n in v) {
            val w = C128[n]
            for (q in w.indices) {
                val wd = w[q] - '0'
                if (q % 2 == 0) sb.append("<rect x='$x' y='0' width='$wd' height='36'/>")
                x += wd
            }
        }
        val full = x + 10
        val mm = minOf(full * 0.345, 56.0)
        val tl = minOf(text.length * 5.4, full - 20.0)
        val label = DocLayout.esc(text)
        return "<svg class='bc' viewBox='0 0 $full 50' xmlns='http://www.w3.org/2000/svg' fill='#000' style='width:${String.format(java.util.Locale.US, "%.1f", mm)}mm'>" +
            sb +
            "<text x='${full / 2.0}' y='47' text-anchor='middle' font-family='DejaVu Sans, Arial, sans-serif' font-size='8.5' textLength='$tl' lengthAdjust='spacing'>$label</text></svg>"
    }

    private fun svg13(code: String): String {
        val first = code[0] - '0'
        val par = PARITY[first]
        val m = StringBuilder("101")
        for (i in 0 until 6) {
            val d = code[1 + i] - '0'
            m.append(if (par[i] == 'L') L[d] else G[d])
        }
        m.append("01010")
        for (i in 0 until 6) m.append(R[code[7 + i] - '0'])
        m.append("101")
        val x0 = 9
        val sb = StringBuilder("<svg class='bc' viewBox='0 0 ${x0 + 95 + 7} 50' xmlns='http://www.w3.org/2000/svg' fill='#000'>")
        // охранные полосы (начало, середина, конец) чуть длиннее
        bars(m.toString(), x0, setOf(0, 2, 45, 47, 92, 94), sb)
        sb.append("<g font-family='DejaVu Sans, Arial, sans-serif' font-size='8.5' fill='#000'>")
        sb.append("<text x='1' y='47'>${code[0]}</text>")
        sb.append("<text x='${x0 + 4}' y='47' textLength='40' lengthAdjust='spacing'>${code.substring(1, 7)}</text>")
        sb.append("<text x='${x0 + 51}' y='47' textLength='40' lengthAdjust='spacing'>${code.substring(7)}</text>")
        sb.append("</g></svg>")
        return sb.toString()
    }

    private fun svg8(code: String): String {
        val m = StringBuilder("101")
        for (i in 0 until 4) m.append(L[code[i] - '0'])
        m.append("01010")
        for (i in 0 until 4) m.append(R[code[4 + i] - '0'])
        m.append("101")
        val sb = StringBuilder("<svg class='bc' viewBox='0 0 ${67 + 14} 50' xmlns='http://www.w3.org/2000/svg' fill='#000'>")
        bars(m.toString(), 7, setOf(0, 2, 32, 34, 64, 66), sb)
        sb.append("<g font-family='DejaVu Sans, Arial, sans-serif' font-size='8.5' fill='#000'>")
        sb.append("<text x='${7 + 4}' y='47' textLength='26' lengthAdjust='spacing'>${code.substring(0, 4)}</text>")
        sb.append("<text x='${7 + 37}' y='47' textLength='26' lengthAdjust='spacing'>${code.substring(4)}</text>")
        sb.append("</g></svg>")
        return sb.toString()
    }
}
