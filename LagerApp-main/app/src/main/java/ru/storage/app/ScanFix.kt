package ru.storage.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Выравнивание скана: если лист был сшит (степлер) и сканер не увидел один угол, строки на странице идут косо.
 * Приложение после сканирования само находит наклон строк текста на каждом листе и поворачивает лист так, чтобы строки шли горизонтально.
 * Если все листы и так ровные (наклон меньше порога) — файл не трогается.
 */
object ScanFix {
    /** Наклон меньше этого значения (градусы) не исправляем. */
    private const val MIN_ANGLE = 0.4f

    /** Дальше этого угла не ищем: больший наклон — это уже не кривой угол, а повёрнутый лист. */
    private const val MAX_ANGLE = 10f

    /**
     * Угол наклона строк (градусы): положительный — строки идут вниз вправо. 0 — если строк не видно или уверенности нет.
     * Строки находятся по горизонтальному профилю тёмных точек: при правильном угле строки текста дают резкие пики.
     */
    fun skewAngle(src: Bitmap): Float {
        val w0 = 720
        val scale = w0.toFloat() / src.width
        val w = w0
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        // яркость и порог (Оцу)
        val gray = IntArray(w * h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val g = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
            gray[i] = g
            hist[g]++
        }
        val total = w * h
        var sumAll = 0L
        for (t in 0..255) sumAll += t.toLong() * hist[t]
        var wB = 0
        var sumB = 0L
        var best = 0.0
        var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                thr = t
            }
        }

        // тёмные точки (не больше ~60 тысяч, равномерно)
        var dark = 0
        for (g in gray) if (g < thr) dark++
        if (dark < 400) return 0f
        val step = (dark / 60000).coerceAtLeast(1)
        val xs = IntArray(dark / step + 1)
        val ys = IntArray(dark / step + 1)
        var n = 0
        var k = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (gray[y * w + x] < thr) {
                if (k % step == 0 && n < xs.size) {
                    xs[n] = x
                    ys[n] = y
                    n++
                }
                k++
            }
        }
        if (n < 400) return 0f

        val bins = IntArray(h + 2 * w + 4)
        fun score(deg: Float): Double {
            java.util.Arrays.fill(bins, 0)
            val a = deg * PI / 180.0
            val ca = cos(a)
            val sa = sin(a)
            for (i in 0 until n) {
                val v = ys[i] * ca - xs[i] * sa
                bins[(v + w).toInt()]++
            }
            var s = 0.0
            for (i in 1 until bins.size) {
                val d = (bins[i] - bins[i - 1]).toDouble()
                s += d * d
            }
            return s
        }

        val base = score(0f)
        var bestDeg = 0f
        var bestScore = base
        var a = -MAX_ANGLE
        while (a <= MAX_ANGLE + 0.001f) {
            val sc = score(a)
            if (sc > bestScore) {
                bestScore = sc
                bestDeg = a
            }
            a += 0.5f
        }
        // уточнение вокруг лучшего угла
        var fine = bestDeg - 0.5f
        val fineEnd = bestDeg + 0.5f
        while (fine <= fineEnd + 0.001f) {
            val sc = score(fine)
            if (sc > bestScore) {
                bestScore = sc
                bestDeg = fine
            }
            fine += 0.1f
        }
        // угол принимаем, только если строки при нём заметно чётче, чем без поворота
        if (abs(bestDeg) < MIN_ANGLE || bestScore < base * 1.05) return 0f
        return bestDeg
    }

    /** Лист, повёрнутый так, чтобы строки с наклоном [deg] стали горизонтальными. Размер сохраняется, края белые. */
    fun rotateLevel(src: Bitmap, deg: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.rotate(-deg, src.width / 2f, src.height / 2f)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun render(page: PdfRenderer.Page, longSide: Int): Bitmap {
        val scale = longSide.toFloat() / maxOf(page.width, page.height)
        val w = (page.width * scale).toInt().coerceAtLeast(1)
        val h = (page.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    }

    /**
     * Скан (PDF) → при необходимости выровненный PDF. Возвращает Uri нового файла или исходный [src], если выравнивать нечего
     * или что-то пошло не так (скан в любом случае не теряется). Вызывать не в главном потоке.
     */
    fun fix(ctx: Context, src: Uri): Uri {
        val app = ctx.applicationContext
        val tmp = File(app.cacheDir, "scanfix_" + System.nanoTime() + ".pdf")
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            val ins = app.contentResolver.openInputStream(src) ?: return src
            ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val n = renderer.pageCount
            if (n <= 0) return src

            // 1) наклон каждого листа по уменьшенной картинке
            val angles = FloatArray(n)
            for (i in 0 until n) {
                val page = renderer.openPage(i)
                try {
                    val bmp = render(page, 1000)
                    angles[i] = skewAngle(bmp)
                    bmp.recycle()
                } finally {
                    page.close()
                }
            }
            if (angles.all { it == 0f }) return src

            // 2) новый PDF: кривые листы повёрнуты, ровные остаются как есть
            val doc = PdfDocument()
            try {
                for (i in 0 until n) {
                    val page = renderer.openPage(i)
                    try {
                        val pw = page.width
                        val ph = page.height
                        var bmp = render(page, 1900)
                        if (angles[i] != 0f) {
                            val rotated = rotateLevel(bmp, angles[i])
                            bmp.recycle()
                            bmp = rotated
                        }
                        val info = PdfDocument.PageInfo.Builder(pw, ph, i + 1).create()
                        val p = doc.startPage(info)
                        p.canvas.drawBitmap(bmp, null, Rect(0, 0, pw, ph), Paint(Paint.FILTER_BITMAP_FLAG))
                        doc.finishPage(p)
                        bmp.recycle()
                    } finally {
                        page.close()
                    }
                }
                val dir = File(app.cacheDir, "export").also { it.mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                val out = File(dir, "Сканирование_$stamp.pdf")
                out.outputStream().use { doc.writeTo(it) }
                return FileProvider.getUriForFile(app, "${app.packageName}.files", out)
            } finally {
                doc.close()
            }
        } catch (e: Throwable) {
            return src
        } finally {
            try { renderer?.close() } catch (e: Exception) { }
            try { pfd?.close() } catch (e: Exception) { }
            try { tmp.delete() } catch (e: Exception) { }
        }
    }
}
