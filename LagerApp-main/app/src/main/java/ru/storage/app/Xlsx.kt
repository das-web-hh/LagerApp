package ru.storage.app

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream

/** Минимальное чтение первого листа .xlsx (без сторонних библиотек). */
object Xlsx {
    private val SHEET = Regex("xl/worksheets/sheet(\\d+)\\.xml")

    fun read(ctx: Context, uri: Uri): List<List<String>> {
        var shared: ByteArray? = null
        val sheets = HashMap<Int, ByteArray>()
        val ins = ctx.contentResolver.openInputStream(uri) ?: return emptyList()
        ins.use { raw ->
            ZipInputStream(raw).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val n = e.name
                    if (n == "xl/sharedStrings.xml") {
                        shared = zip.readBytes()
                    } else {
                        val m = SHEET.matchEntire(n)
                        if (m != null) sheets[m.groupValues[1].toInt()] = zip.readBytes()
                    }
                    e = zip.nextEntry
                }
            }
        }
        val sheet = sheets.entries.minByOrNull { it.key }?.value ?: return emptyList()
        val strings = shared?.let { parseShared(it) } ?: emptyList()
        return parseSheet(sheet, strings)
    }

    private fun parser(b: ByteArray): XmlPullParser {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(b), null)
        return p
    }

    private fun parseShared(b: ByteArray): List<String> {
        val p = parser(b)
        val out = ArrayList<String>()
        var sb: StringBuilder? = null
        var inRph = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> inRph = true
                    "t" -> {
                        val s = p.nextText()
                        if (!inRph) sb?.append(s)
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "si" -> {
                        out.add(sb?.toString() ?: "")
                        sb = null
                    }
                    "rPh" -> inRph = false
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return n - 1
    }

    private fun number(v: String): String = try {
        val bd = BigDecimal(v).stripTrailingZeros()
        if (bd.scale() <= 0) bd.toBigInteger().toString() else bd.toPlainString()
    } catch (e: Exception) {
        v
    }

    private fun cell(type: String, v: String, strings: List<String>): String = when (type) {
        "s" -> strings.getOrElse(v.toIntOrNull() ?: -1) { "" }
        "str", "e", "inlineStr" -> v
        "b" -> if (v == "1") "TRUE" else "FALSE"
        else -> number(v)
    }

    private fun parseSheet(b: ByteArray, strings: List<String>): List<List<String>> {
        val p = parser(b)
        val rows = ArrayList<Map<Int, String>>()
        var cur: HashMap<Int, String>? = null
        var col = 0
        var nextCol = 0
        var type = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "row" -> {
                        cur = HashMap()
                        nextCol = 0
                    }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref != null) colIndex(ref) else nextCol
                        nextCol = col + 1
                        type = p.getAttributeValue(null, "t") ?: ""
                    }
                    "v" -> {
                        val v = p.nextText()
                        cur?.put(col, cell(type, v, strings))
                    }
                    "t" -> {
                        val c = cur
                        if (c != null && type == "inlineStr") c[col] = (c[col] ?: "") + p.nextText()
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "row") {
                cur?.let { rows.add(it) }
                cur = null
            }
            ev = p.next()
        }
        val width = rows.maxOfOrNull { r -> (r.keys.maxOrNull() ?: -1) + 1 } ?: 0
        return rows.map { r -> List(width) { i -> (r[i] ?: "").trim() } }
    }
}
