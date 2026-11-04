package ru.storage.app

/** Штрих-код EAN-13 / EAN-8 (и UPC-A как EAN-13 с нулём) в виде SVG для печати. Другие коды — просто цифры. */
object Ean {
    private val L = arrayOf("0001101", "0011001", "0010011", "0111101", "0100011", "0110001", "0101111", "0111011", "0110111", "0001011")
    private val G = arrayOf("0100111", "0110011", "0011011", "0100001", "0011101", "0111001", "0000101", "0010001", "0001001", "0010111")
    private val R = arrayOf("1110010", "1100110", "1101100", "1000010", "1011100", "1001110", "1010000", "1000100", "1001000", "1110100")
    private val PARITY = arrayOf("LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG", "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL")

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

    /** Готовый блок для ячейки таблицы: штрих-код картинкой с цифрами под ним (или одни цифры, если код не EAN). */
    fun html(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return "&mdash;"
        val code = if (digits.length == 12) "0$digits" else digits
        return when {
            code.length == 13 && checkOk(code) -> svg13(code)
            code.length == 8 && checkOk(code) -> svg8(code)
            else -> "<div class='bt'>${DocLayout.esc(spaced(digits))}</div>"
        }
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
