package ru.storage.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Отметка на экране: этикетка в кадре (координаты — в повёрнутом кадре анализа). key — ключ товара. */
class LiveMark(val key: String, val rect: Rect)

class LiveMarks(val w: Int, val h: Int, val items: List<LiveMark>)

/**
 * Живое распознавание этикеток Ströh при проходе камерой (видео).
 *  • Каждая этикетка находится по своему штрих-коду; на экране она получает отметку.
 *  • Первый раз товар записывается в список один раз (запоминается, повторно в кадре не добавляется).
 *  • Нажатие на отметку добавляет ещё одну такую же строку (addAnother): у второй этикетки того же товара дата будет другая.
 * Распознавание: 1) телефон (штрих-код + текст, два одинаковых чтения подряд); 2) каталог по штрих-коду; 3) ИИ по кадру с этикеткой.
 */
class LiveLabels(
    private val app: Context,
    private val found: SnapshotStateList<ScannedLabel>,
    private val onMarks: (LiveMarks?) -> Unit
) {
    private class Track {
        var bad = 0
        val votes = HashMap<String, Int>()
        @Volatile var done = false
        var aiStarted = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val db = ProductDb.get(app)
    private val aiPool = Executors.newFixedThreadPool(2)
    private val tracks = ConcurrentHashMap<String, Track>()
    private val aiReady = AiCfg.ready(app)
    private val bs = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E).build()
    )
    private val tr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile var alive = true

    fun close() {
        alive = false
        aiPool.shutdownNow()
        try { bs.close() } catch (e: Exception) { }
        try { tr.close() } catch (e: Exception) { }
    }

    /** Нажали на отметку: ещё одна строка того же товара (в главном потоке). */
    fun addAnother(base: String) {
        val src = found.firstOrNull { it.key == base } ?: return
        var n = 2
        while (found.any { it.key == "$base#$n" }) n++
        found.add(0, src.copy(key = "$base#$n"))
        Haptics.pulse(app, Haptics.TAP)
        ScanFeedback.beep(true)
    }

    private fun put(l: ScannedLabel) {
        main.post {
            if (!alive && l.via == "wait") return@post
            val i = found.indexOfFirst { it.key == l.key }
            if (i >= 0) found[i] = l
            else {
                found.add(0, l)
                Haptics.pulse(app, Haptics.TAP)
                ScanFeedback.beep(l.via != "none")
            }
        }
    }

    private fun gtinFormat(b: Barcode): Boolean =
        b.format == Barcode.FORMAT_EAN_13 || b.format == Barcode.FORMAT_EAN_8 || b.format == Barcode.FORMAT_UPC_A || b.format == Barcode.FORMAT_UPC_E

    private fun clip(r: Rect, w: Int, h: Int) = Rect(r.left.coerceIn(0, w), r.top.coerceIn(0, h), r.right.coerceIn(0, w), r.bottom.coerceIn(0, h))

    fun analyze(proxy: ImageProxy) {
        try {
            val media = proxy.image ?: return
            if (!alive) return
            val rot = proxy.imageInfo.rotationDegrees
            val iw = if (rot == 90 || rot == 270) proxy.height else proxy.width
            val ih = if (rot == 90 || rot == 270) proxy.width else proxy.height
            val img = InputImage.fromMediaImage(media, rot)
            val codes = Tasks.await(bs.process(img))
            val bars = ArrayList<Pair<String, Rect>>()
            for (b in codes) {
                val v = b.rawValue?.trim() ?: continue
                val box = b.boundingBox ?: continue
                if (v.isEmpty()) continue
                if (gtinFormat(b) && !gtinOk(v)) continue // прочитано с ошибкой
                bars.add(v to box)
            }
            if (bars.isEmpty()) { main.post { if (alive) onMarks(null) }; return }
            // текст читаем, только если есть ещё не готовые этикетки
            if (bars.any { tracks[StrohLabel.key(it.first)]?.done != true }) {
                val txt = Tasks.await(tr.process(img))
                val lines = ArrayList<Pair<String, Rect>>()
                for (blk in txt.textBlocks) for (ln in blk.lines) {
                    val bb = ln.boundingBox ?: continue
                    lines.add(ln.text to bb)
                }
                for (p in StrohLabel.parseFrame(bars, lines)) handle(p, proxy)
            }
            // отметки только у тех штрих-кодов, что оказались этикетками Ströh
            val marks = bars.filter { tracks.containsKey(StrohLabel.key(it.first)) }
                .map { LiveMark(StrohLabel.key(it.first), clip(StrohLabel.region(it.second), iw, ih)) }
            main.post { if (alive) onMarks(LiveMarks(iw, ih, marks)) }
        } catch (e: Exception) {
        } finally {
            proxy.close()
        }
    }

    private fun handle(p: StrohLabel.Parsed, proxy: ImageProxy) {
        val k = StrohLabel.key(p.barcode)
        val complete = p.article.isNotEmpty() && p.name.length >= 4
        // чужие штрих-коды (коробки, товары) пропускаем: нужен логотип Ströh или читаемые артикул + название
        if (!p.logo && !complete && !tracks.containsKey(k)) return
        val t = tracks.getOrPut(k) { Track() }
        if (t.done) return
        if (complete) {
            val sig = p.article + "|" + p.name
            val n = (t.votes[sig] ?: 0) + 1
            t.votes[sig] = n
            if (n >= 2) { t.done = true; put(ScannedLabel(k, p.barcode, p.article, p.name, "phone")) }
            return
        }
        t.bad++
        if (t.bad == 3) {
            val prod = try { db.findByBarcode(p.barcode) } catch (e: Exception) { null }
            if (prod != null && prod.name.isNotBlank()) {
                t.done = true
                put(ScannedLabel(k, p.barcode, prod.article, prod.name, "catalog"))
                return
            }
        }
        if (t.bad >= 8 && !t.aiStarted) {
            t.aiStarted = true
            if (!aiReady) { t.done = true; put(ScannedLabel(k, p.barcode, p.article, "", "none")); return }
            val jpeg = cropJpeg(proxy, p.region)
            if (jpeg == null) { t.done = true; put(ScannedLabel(k, p.barcode, p.article, "", "none")); return }
            put(ScannedLabel(k, p.barcode, p.article, p.name, "wait"))
            try {
                aiPool.execute {
                    var res = ScannedLabel(k, p.barcode, p.article, p.name, "none")
                    try {
                        val answer = Ai.call(app, prompt(p.barcode), jpeg, true)
                        val s = answer.indexOf('{')
                        val e = answer.lastIndexOf('}')
                        if (s >= 0 && e > s) {
                            val o = JSONObject(answer.substring(s, e + 1))
                            fun v(key: String) = o.optString(key).trim().let { if (it.equals("null", true) || it.equals("none", true)) "" else it }
                            val name = v("name").ifBlank { p.name }
                            val art = v("article").filter { it.isLetterOrDigit() }.ifBlank { p.article }
                            if (name.isNotBlank()) res = ScannedLabel(k, p.barcode, art, name, "ai")
                        }
                    } catch (e: Exception) {
                    }
                    t.done = true
                    put(res)
                }
            } catch (e: Exception) {
                t.done = true
                put(ScannedLabel(k, p.barcode, p.article, "", "none"))
            }
        }
    }

    private fun prompt(code: String) = """
        This is a photo of a product label of the brand STRÖH (horse stable supplies). A label contains: the STRÖH logo, a standalone article number (4-8 digits) under the logo,
        the product name (1-3 lines, may end with a volume or weight such as "591 ml"), the price in euro, a line "% ab 2 Stück nur ..." and a barcode (EAN) with its digits above the name.
        If several labels are visible, read ONLY the label whose barcode digits are $code (or the most complete label in the middle of the photo).
        Answer with JSON only (no markdown, no comments): {"article":"","name":"","barcode":""}
        - article: the article number exactly as printed, letters and digits only, no spaces.
        - name: the full product name exactly as printed, including volume / weight; WITHOUT the price, WITHOUT the "% ab 2 Stück" line, WITHOUT the logo text.
        - barcode: the EAN digits printed under the bars, digits only.
        Never invent values. Use "" when a value is not visible.
    """.trimIndent()

    /** Кадр с этикеткой (с запасом по краям) в JPEG для ИИ. */
    private fun cropJpeg(proxy: ImageProxy, region: Rect): ByteArray? {
        return try {
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val bmp = if (rot != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else raw
            val padX = (region.width() * 0.05f).toInt()
            val padY = (region.height() * 0.05f).toInt()
            val l = (region.left - padX).coerceIn(0, bmp.width - 1)
            val tp = (region.top - padY).coerceIn(0, bmp.height - 1)
            val r = (region.right + padX).coerceIn(l + 1, bmp.width)
            val b = (region.bottom + padY).coerceIn(tp + 1, bmp.height)
            var out = if (r - l >= 80 && b - tp >= 80) Bitmap.createBitmap(bmp, l, tp, r - l, b - tp) else bmp
            val m = maxOf(out.width, out.height)
            if (m > 1400) {
                val kf = 1400f / m
                out = Bitmap.createScaledBitmap(out, (out.width * kf).toInt().coerceAtLeast(1), (out.height * kf).toInt().coerceAtLeast(1), true)
            }
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 90, bos)
            bos.toByteArray()
        } catch (e: Throwable) {
            null
        }
    }
}
