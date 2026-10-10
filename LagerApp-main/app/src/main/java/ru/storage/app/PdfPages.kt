package ru.storage.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/** Источник листов для распознавания: PDF (много листов) или одна картинка. */
interface PageSource : AutoCloseable {
    val count: Int
    /** Лист [index] (с нуля) → чёрно-белый PNG. */
    fun renderBw(index: Int, longSide: Int = 1800, bw: Boolean = true): ByteArray
}

fun isImageName(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
}

fun openPageSource(file: File): PageSource =
    if (isImageName(file.name)) ImageDoc(file) else PdfDoc(file)

/** PDF → отдельные листы → чёрно-белые PNG (меньше токенов у ИИ, чем у цветных). */
class PdfDoc(file: File) : PageSource {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)

    override val count: Int get() = renderer.pageCount

    override fun renderBw(index: Int, longSide: Int, bw: Boolean): ByteArray {
        val page = renderer.openPage(index)
        try {
            val scale = longSide.toFloat() / maxOf(page.width, page.height)
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return if (bw) bitmapToBwPng(bmp) else bitmapToGrayJpeg(bmp)
        } finally {
            page.close()
        }
    }

    override fun close() {
        try { renderer.close() } catch (e: Exception) { }
        try { pfd.close() } catch (e: Exception) { }
    }
}

/** Одна картинка (jpg/png/webp) = один лист. */
class ImageDoc(private val file: File) : PageSource {
    override val count: Int = 1

    override fun renderBw(index: Int, longSide: Int, bw: Boolean): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= longSide) s *= 2
        var bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = s })
            ?: throw RuntimeException("Не удалось открыть картинку")
        val rot = try {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (rot != 0f) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
        }
        // приводим длинную сторону к longSide
        val long = maxOf(bmp.width, bmp.height)
        if (long != longSide) {
            val k = longSide.toFloat() / long
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        }
        // прозрачные пиксели png → белые, иначе станут чёрными
        val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        flat.eraseColor(Color.WHITE)
        android.graphics.Canvas(flat).drawBitmap(bmp, 0f, 0f, null)
        return if (bw) bitmapToBwPng(flat) else bitmapToGrayJpeg(flat)
    }

    override fun close() {}
}

/** Бинаризация по порогу Оцу: всё тёмное — чёрное, остальное — белое → PNG. */
fun bitmapToBwPng(bmp: Bitmap): ByteArray {
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
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
}

/** Серый JPEG без жёсткой бинаризации: запасной вариант для тусклых и неровно освещённых сканов. */
fun bitmapToGrayJpeg(bmp: Bitmap): ByteArray {
    val gray = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
    val cm = android.graphics.ColorMatrix().apply { setSaturation(0f) }
    val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) }
    val c = android.graphics.Canvas(gray)
    c.drawColor(Color.WHITE)
    c.drawBitmap(bmp, 0f, 0f, paint)
    val out = ByteArrayOutputStream()
    gray.compress(Bitmap.CompressFormat.JPEG, 88, out)
    gray.recycle()
    bmp.recycle()
    return out.toByteArray()
}
