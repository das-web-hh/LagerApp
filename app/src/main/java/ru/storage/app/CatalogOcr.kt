package ru.storage.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Список товаров с фото, скриншота или PDF → таблица для импорта в каталог.
 * Сначала распознаёт сам телефон (ML Kit: строки и столбцы по положению текста); если таблица не получилась,
 * картинка уходит ИИ, он возвращает артикул, название и штрих-код, и они приходят обратно на телефон.
 */
object CatalogOcr {
    /** rows — ячейки таблицы; cols — (название, штрих-код, артикул), -1 — нет; header — первая строка заголовки. */
    class Table(val rows: List<List<String>>, val header: Boolean, val cols: IntArray, val viaAi: Boolean)

    private class Raw(val text: String, val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float)
    private class Cell(val text: String, val x: Float, val w: Float, val y: Float, val h: Float)

    suspend fun read(ctx: Context, uri: Uri, onStatus: (String) -> Unit): Table {
        val pages = withContext(Dispatchers.IO) { loadPages(ctx, uri) }
        if (pages.isEmpty()) throw RuntimeException("Не удалось открыть файл (нужна картинка или PDF)")
        val phone = try { fromPhone(pages) } catch (e: Exception) { null }
        if (phone != null && isGood(phone)) {
            val head = guessHeader(phone)
            return Table(phone, head, guessCols(phone, head), false)
        }
        if (!AiCfg.ready(ctx)) {
            throw RuntimeException("Телефон не смог распознать таблицу. Чтобы распознать через ИИ, добавьте ключ ИИ в настройках.")
        }
        onStatus("Телефон не распознал таблицу, отправляю в ИИ…")
        val rows = withContext(Dispatchers.IO) { fromAi(ctx, pages) }
        if (rows.size < 2) throw RuntimeException("ИИ не нашёл товаров на картинке")
        return Table(rows, true, intArrayOf(1, 2, 0), true)
    }

    // ---------- страницы ----------

    private fun loadPages(ctx: Context, uri: Uri): List<Bitmap> {
        val type = ctx.contentResolver.getType(uri).orEmpty()
        return if (type == "application/pdf" || uri.toString().lowercase().endsWith(".pdf")) pdfPages(ctx, uri) else listOfNotNull(decodeImage(ctx, uri))
    }

    private fun pdfPages(ctx: Context, uri: Uri): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        var pfd: ParcelFileDescriptor? = null
        var r: PdfRenderer? = null
        try {
            pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return out
            r = PdfRenderer(pfd)
            for (i in 0 until minOf(r.pageCount, 8)) {
                val page = r.openPage(i)
                try {
                    val w = 1800
                    val h = (page.height * (w.toFloat() / page.width)).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.add(bmp)
                } finally {
                    page.close()
                }
            }
        } catch (e: Exception) {
        } finally {
            try { r?.close() } catch (e: Exception) { }
            try { pfd?.close() } catch (e: Exception) { }
        }
        return out
    }

    private fun decodeImage(ctx: Context, uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var s = 1
            while (max(bounds.outWidth, bounds.outHeight) / s > 2600) s *= 2
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
            } ?: return null
            val rot = try {
                ctx.contentResolver.openInputStream(uri)?.use {
                    when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            } catch (e: Exception) { 0 }
            if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        } catch (e: Throwable) {
            null
        }
    }

    // ---------- распознавание на телефоне ----------

    private suspend fun fromPhone(pages: List<Bitmap>): List<List<String>> {
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val all = ArrayList<List<String>>()
        var width = 0
        val perPage = ArrayList<List<List<String>>>()
        try {
            for (bmp in pages) {
                val t = rec.process(InputImage.fromBitmap(bmp, 0)).awaitIt()
                val raws = ArrayList<Raw>()
                for (b in t.textBlocks) for (l in b.lines) {
                    val r = l.boundingBox ?: continue
                    if (l.text.isBlank()) continue
                    raws.add(Raw(l.text.trim(), r.exactCenterX(), r.exactCenterY(), r.width().toFloat(), r.height().toFloat(), l.angle))
                }
                if (raws.size < 6) continue
                val table = tableFrom(raws)
                width = max(width, table.maxOfOrNull { it.size } ?: 0)
                perPage.add(table)
            }
        } finally {
            try { rec.close() } catch (e: Exception) { }
        }
        for (p in perPage) for (r in p) all.add(r + List(max(0, width - r.size)) { "" })
        return all
    }

    private fun clusters(cells: List<Cell>): List<List<Cell>> {
        if (cells.isEmpty()) return emptyList()
        val medH = cells.map { it.h }.sorted()[cells.size / 2].coerceAtLeast(6f)
        val sorted = cells.sortedBy { it.y }
        val rows = ArrayList<MutableList<Cell>>()
        var meanY = 0f
        for (c in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(c.y - meanY) <= medH * 0.6f) {
                last.add(c)
                meanY = (meanY * (last.size - 1) + c.y) / last.size
            } else {
                rows.add(mutableListOf(c))
                meanY = c.y
            }
        }
        return rows
    }

    private fun rotate(raws: List<Raw>, deg: Float): List<Cell> {
        val a = Math.toRadians(deg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        return raws.map { r ->
            val x = r.cx * ca + r.cy * sa
            val y = -r.cx * sa + r.cy * ca
            Cell(r.text, x - r.w / 2, r.w, y, r.h)
        }
    }

    /** Строки и столбцы по положению текста (с поправкой на наклон снимка). */
    private fun tableFrom(raws: List<Raw>): List<List<String>> {
        val tilt = raws.map { it.angle }.sorted()[raws.size / 2]
        // знак наклона заранее неясен: берём тот поворот, при котором строк получается меньше (текст лучше выровнен)
        val best = listOf(0f, tilt, -tilt).map { d -> d to clusters(rotate(raws, d)) }.minByOrNull { it.second.size }!!
        val rowsCells = best.second
        val medH = raws.map { it.h }.sorted()[raws.size / 2].coerceAtLeast(6f)

        // ячейки: близкие по горизонтали куски одной строки склеиваются
        val merged = rowsCells.map { row ->
            val s = row.sortedBy { it.x }
            val out = ArrayList<Cell>()
            for (c in s) {
                val p = out.lastOrNull()
                if (p != null && c.x - (p.x + p.w) < medH * 1.0f) {
                    out[out.size - 1] = Cell(p.text + " " + c.text, p.x, c.x + c.w - p.x, p.y, p.h)
                } else out.add(c)
            }
            out
        }
        // столбцы: левые края ячеек, которые повторяются в большинстве строк
        val lefts = merged.flatMap { r -> r.map { it.x } }.sorted()
        val groups = ArrayList<MutableList<Float>>()
        for (x in lefts) {
            val g = groups.lastOrNull()
            if (g != null && x - g.last() <= medH * 2.2f) g.add(x) else groups.add(mutableListOf(x))
        }
        val minSupport = max(2, (merged.size * 0.3).toInt())
        val anchors = groups.filter { it.size >= minSupport }.map { it.average().toFloat() }
        if (anchors.size < 2) return merged.map { r -> r.map { it.text } }
        val table = merged.map { row ->
            val cols = Array(anchors.size) { StringBuilder() }
            for (c in row) {
                var idx = 0
                for (i in anchors.indices) if (anchors[i] <= c.x + medH * 1.5f) idx = i
                if (cols[idx].isNotEmpty()) cols[idx].append(' ')
                cols[idx].append(c.text)
            }
            cols.map { normCell(it.toString().trim()) }
        }
        return table.filter { r -> r.count { it.isNotBlank() } >= 1 }
    }

    /** Штрих-код с пробелами («4 026921 072113») → цифры подряд. */
    private fun normCell(s: String): String {
        if (s.isNotEmpty() && s.all { it.isDigit() || it == ' ' }) {
            val d = s.filter { it.isDigit() }
            if (d.length in 8..14) return d
        }
        return s
    }

    private fun isGood(rows: List<List<String>>): Boolean {
        if (rows.size < 3) return false
        val width = rows.maxOf { it.size }
        if (width < 2) return false
        val filled = rows.count { r -> r.count { it.isNotBlank() } >= 2 }
        return filled >= rows.size * 0.6
    }

    private fun isNum(s: String) = s.isNotBlank() && s.replace(" ", "").all { it.isDigit() }

    private fun guessHeader(rows: List<List<String>>): Boolean {
        val first = rows.firstOrNull() ?: return false
        return first.none { isNum(it) && it.replace(" ", "").length >= 4 } && first.count { it.isNotBlank() } >= 2
    }

    /** Названия столбцов по заголовкам, а если их нет, по содержимому: длинный текст — название, 8–14 цифр — штрих-код, короткие номера — артикул. */
    private fun guessCols(rows: List<List<String>>, header: Boolean): IntArray {
        val width = rows.maxOf { it.size }
        val data = if (header) rows.drop(1) else rows
        var name = -1
        var barcode = -1
        var article = -1
        if (header) {
            val h = rows[0].map { it.lowercase() }
            for ((i, t) in h.withIndex()) {
                when {
                    name < 0 && listOf("bezeichnung", "название", "наимен", "name", "beschreibung", "artikelname").any { t.contains(it) } -> name = i
                    barcode < 0 && listOf("штрих", "ean", "barcode", "gtin").any { t.contains(it) } -> barcode = i
                    article < 0 && listOf("artikel-nr", "artikelnr", "artikel", "артикул", "article", "art.", "sku").any { t.contains(it) } -> article = i
                }
            }
        }
        for (c in 0 until width) {
            val vals = data.mapNotNull { it.getOrNull(c)?.trim() }.filter { it.isNotEmpty() }
            if (vals.isEmpty()) continue
            val num = vals.count { isNum(it) }.toDouble() / vals.size
            val avg = vals.map { it.replace(" ", "").length }.average()
            val distinct = vals.toSet().size.toDouble() / vals.size
            if (barcode < 0 && num >= 0.6 && avg in 8.0..14.5 && c != article) barcode = c
            else if (article < 0 && num >= 0.6 && avg in 3.0..9.0 && distinct >= 0.6 && c != barcode) article = c
        }
        if (name < 0) {
            var bestLen = 0.0
            for (c in 0 until width) {
                if (c == barcode || c == article) continue
                val vals = data.mapNotNull { it.getOrNull(c)?.trim() }.filter { it.isNotEmpty() }
                if (vals.isEmpty()) continue
                val letters = vals.count { v -> v.any { it.isLetter() } }.toDouble() / vals.size
                val len = vals.map { it.length }.average() * letters
                if (len > bestLen) { bestLen = len; name = c }
            }
        }
        return intArrayOf(name.coerceAtLeast(0), barcode, article)
    }

    // ---------- ИИ ----------

    private fun scaled(b: Bitmap, side: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= side) return b
        val k = side.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    private fun fromAi(ctx: Context, pages: List<Bitmap>): List<List<String>> {
        val prompt = """
            This image shows a list of products: a table, a screenshot of an ERP / warehouse program, a price list or a printed list. Extract EVERY product row.
            Answer with JSON only (no markdown, no comments):
            {"rows":[{"article":"","name":"","barcode":""}]}
            Rules:
            - article: the item / article number exactly as printed (letters and digits, no spaces), or "".
            - name: the full product name / description as printed, including weight and packaging (e.g. "DERBY Balance pelletiert 25 kg Sack").
            - barcode: EAN / GTIN digits only (8-14 digits, no spaces), or "".
            - Never invent values. Use "" when a value is not on the image. Skip headers, footers, totals and page numbers.
        """.trimIndent()
        val out = ArrayList<List<String>>()
        out.add(listOf("Артикул", "Название", "Штрих-код"))
        for (p in pages) {
            val bos = ByteArrayOutputStream()
            scaled(p, 1800).compress(Bitmap.CompressFormat.PNG, 100, bos)
            val answer = Ai.task(ctx, "catalog", JSONObject(), bos.toByteArray(), true) { prompt }
            val s = answer.indexOf('{')
            val e = answer.lastIndexOf('}')
            if (s < 0 || e <= s) continue
            val arr = JSONObject(answer.substring(s, e + 1)).optJSONArray("rows") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                fun v(k: String) = o.optString(k).trim().let { if (it.equals("null", true) || it.equals("none", true)) "" else it }
                val name = v("name")
                if (name.isEmpty()) continue
                out.add(listOf(v("article").replace(" ", ""), name, v("barcode").filter { it.isDigit() }))
            }
        }
        return out
    }
}
