package ru.storage.app

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ln
import kotlin.math.pow

/**
 * Подготовка фото для печати на чёрно-белом принтере.
 * Цветное фото принтер сам переводит в серый с растром, из-за чего тёмные места «забиваются» чёрным.
 * Поэтому фото переводится в серый заранее: растягивается диапазон яркости, выравнивается средняя яркость
 * (тёмные фото осветляются) и тени слегка приподнимаются, чтобы не расплывались в чёрное пятно.
 */
object PrintPhoto {
    private const val MAX_SIDE = 1100

    fun prepare(src: Bitmap): Bitmap {
        var bmp = src
        val side = maxOf(bmp.width, bmp.height)
        if (side > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / side
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        }
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // яркость каждого пикселя и гистограмма
        val lum = IntArray(px.size)
        val hist = IntArray(256)
        for (i in px.indices) {
            val c = px[i]
            val y = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt().coerceIn(0, 255)
            lum[i] = y
            hist[y]++
        }
        // обрезаем 1% самых тёмных и 1% самых светлых — растягиваем остальное на весь диапазон
        val cut = (px.size * 0.01).toInt()
        var lo = 0
        var acc = 0
        while (lo < 255 && acc + hist[lo] <= cut) { acc += hist[lo]; lo++ }
        var hi = 255
        acc = 0
        while (hi > lo + 1 && acc + hist[hi] <= cut) { acc += hist[hi]; hi-- }
        val span = (hi - lo).coerceAtLeast(1).toDouble()

        // средняя яркость после растяжки → гамма, которая тянет её к середине (тёмные фото светлеют)
        var sum = 0.0
        for (i in lum.indices) sum += ((lum[i] - lo) / span).coerceIn(0.0, 1.0)
        val mean = (sum / lum.size).coerceIn(0.05, 0.95)
        val gamma = (ln(0.5) / ln(mean)).coerceIn(0.45, 1.0)

        // таблица яркости: растяжка + гамма + небольшой подъём чёрного (чтобы тени не сливались в сплошную заливку)
        val lut = IntArray(256) { v ->
            val n = ((v - lo) / span).coerceIn(0.0, 1.0).pow(gamma)
            (255 * (0.07 + 0.93 * n)).toInt().coerceIn(0, 255)
        }
        for (i in px.indices) {
            val g = lut[lum[i]]
            px[i] = Color.rgb(g, g, g)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** Файл фото → серый JPEG в base64 для вставки в HTML документа; null — файла нет или он не читается. */
    fun base64(f: File): String? {
        val bmp = Media.thumb(f, 600) ?: return null
        val gray = prepare(bmp)
        val bos = ByteArrayOutputStream()
        gray.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }
}
