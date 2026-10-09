package ru.storage.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/** PDF → отдельные листы → чёрно-белые PNG (меньше токенов у ИИ, чем у цветных). */
class PdfDoc(file: File) : AutoCloseable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    val count: Int get() = renderer.pageCount

    /** Лист [index] (с нуля) → чёрно-белый PNG; длинная сторона ≈ [longSide] px. */
    fun renderBw(index: Int, longSide: Int = 1600): ByteArray {
        val page = renderer.openPage(index)
        try {
            val scale = longSide.toFloat() / maxOf(page.width, page.height)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            toBlackWhite(bmp)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            return out.toByteArray()
        } finally {
            page.close()
        }
    }

    override fun close() {
        try { renderer.close() } catch (e: Exception) { }
        try { pfd.close() } catch (e: Exception) { }
    }

    /** Бинаризация по порогу Оцу: всё тёмное — чёрное, остальное — белое. */
    private fun toBlackWhite(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val gray = IntArray(px.size)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val g = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            gray[i] = g
            hist[g]++
        }
        val total = px.size
        var sum = 0L
        for (t in 0..255) sum += t.toLong() * hist[t]
        var sumB = 0L
        var wB = 0
        var best = 0.0
        var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                thr = t
            }
        }
        val limit = minOf(thr + 10, 215)
        for (i in px.indices) px[i] = if (gray[i] <= limit) Color.BLACK else Color.WHITE
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }
}
