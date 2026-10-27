package ru.storage.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Свой сканер документов (второй режим рядом со сканером Google).
 * Края листа ищет EdgeDetect: каждая сторона — прямая, угол — пересечение двух прямых, поэтому зона у степлера не сбивает рамку.
 * Результат — тот же PDF «Сканирование_ГГГГММДД-ЧЧММСС.pdf», дальше всё как у сканера Google.
 */
object OwnScan {
    private const val PREF = "own_scan"
    const val EXTRA_PDF = "pdf_uri"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Включён ли наш сканер (по умолчанию выключен: работает сканер Google). */
    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("own", false)
    fun setEnabled(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean("own", v).apply() }

    /** Снимать само, когда рамка замерла. */
    fun auto(ctx: Context): Boolean = sp(ctx).getBoolean("auto", false)
    fun setAuto(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean("auto", v).apply() }

    /** Степлер в левом верхнем углу (по умолчанию да). */
    fun staple(ctx: Context): Boolean = sp(ctx).getBoolean("staple", true)
    fun setStaple(ctx: Context, v: Boolean) { sp(ctx).edit().putBoolean("staple", v).apply() }

    fun intent(ctx: Context): Intent = Intent(ctx, OwnScanActivity::class.java)

    fun pdfFrom(data: Intent?): Uri? = data?.getStringExtra(EXTRA_PDF)?.let { Uri.parse(it) }
}

/** Рамка поверх камеры: координаты 0..1 по ширине и высоте кадра. */
private class QuadOverlay(ctx: Context) : View(ctx) {
    private var quad: FloatArray? = null
    private var stable = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; strokeJoin = Paint.Join.ROUND }

    fun set(q: FloatArray?, isStable: Boolean) {
        quad = q
        stable = isStable
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val q = quad ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val p = Path()
        p.moveTo(q[0] * w, q[1] * h)
        p.lineTo(q[2] * w, q[3] * h)
        p.lineTo(q[4] * w, q[5] * h)
        p.lineTo(q[6] * w, q[7] * h)
        p.close()
        val col = if (stable) Color.rgb(60, 220, 90) else Color.rgb(255, 193, 46)
        fill.color = Color.argb(45, Color.red(col), Color.green(col), Color.blue(col))
        line.color = col
        c.drawPath(p, fill)
        c.drawPath(p, line)
    }
}

/** Контейнер с пропорциями кадра камеры 3:4 (портрет). */
private class AspectFrame(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = w * 4 / 3
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }
}

/** Круглая кнопка съёмки. */
private class ShutterView(ctx: Context) : View(ctx) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 8f
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx, cy, r - 14f, dot)
    }
}

class OwnScanActivity : ComponentActivity() {
    companion object {
        private const val CAPTURE_MAX = 3200     // длинная сторона снимка при обработке
        private const val OUT_LONG = 2200        // длинная сторона страницы в PDF (меньше — не растягиваем)
        private const val DETECT_LIVE = 480      // кадр для живой рамки
        private const val DETECT_SHOT = 640      // кадр для рамки на готовом снимке
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: QuadOverlay
    private lateinit var status: TextView
    private lateinit var btnDone: Button
    private lateinit var btnUndo: Button

    private val analysisExec = Executors.newSingleThreadExecutor()
    private val workExec = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null

    private val pages = ArrayList<File>()
    private var pagesDir: File? = null
    @Volatile private var busy = false
    @Volatile private var finishing = false
    @Volatile private var staple = true
    @Volatile private var autoMode = false

    // состояние живой рамки и автосъёмки (трогается только из потока анализа)
    private var lastAnalysis = 0L
    private var lastQuad: FloatArray? = null
    private var stableCount = 0
    private var armed = true
    private var lastShot = 0L

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, "Нужен доступ к камере", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        staple = OwnScan.staple(this)
        autoMode = OwnScan.auto(this)
        pagesDir = File(cacheDir, "ownscan_" + System.nanoTime()).also { it.mkdirs() }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        val frame = AspectFrame(this)
        previewView = PreviewView(this)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        overlay = QuadOverlay(this)
        frame.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.VERTICAL
        controls.gravity = Gravity.CENTER_HORIZONTAL
        controls.setPadding(dp(12), dp(6), dp(12), dp(6))
        root.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 16f
        status.gravity = Gravity.CENTER
        controls.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        controls.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val btnCancel = Button(this)
        btnCancel.text = "Отмена"
        btnCancel.setOnClickListener { cancel() }
        row.addView(btnCancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val shutter = ShutterView(this)
        shutter.setOnClickListener {
            shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            capture()
        }
        row.addView(shutter, LinearLayout.LayoutParams(dp(84), dp(84)).also { it.setMargins(dp(8), 0, dp(8), 0) })

        btnDone = Button(this)
        btnDone.text = "Готово"
        btnDone.setOnClickListener { finishScan() }
        row.addView(btnDone, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.gravity = Gravity.CENTER_VERTICAL
        controls.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val swAuto = Switch(this)
        swAuto.text = "Авто"
        swAuto.setTextColor(Color.WHITE)
        swAuto.isChecked = autoMode
        swAuto.setOnCheckedChangeListener { _, on -> autoMode = on; OwnScan.setAuto(this, on) }
        row2.addView(swAuto, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val swStaple = Switch(this)
        swStaple.text = "Степлер сверху слева"
        swStaple.setTextColor(Color.WHITE)
        swStaple.isChecked = staple
        swStaple.setOnCheckedChangeListener { _, on -> staple = on; OwnScan.setStaple(this, on) }
        row2.addView(swStaple, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))

        btnUndo = Button(this)
        btnUndo.text = "Удалить последний"
        btnUndo.setOnClickListener { undoLast() }
        controls.addView(btnUndo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        refreshStatus("Наведите на лист")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancel() }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun refreshStatus(hint: String) {
        status.text = "Листов: " + pages.size + "  ·  " + hint
        btnDone.isEnabled = pages.isNotEmpty() && !finishing
        btnUndo.isEnabled = pages.isNotEmpty() && !finishing
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExec) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                imageCapture = capture
            } catch (e: Exception) {
                Toast.makeText(this, "Камера недоступна", Toast.LENGTH_LONG).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Живая рамка (около 4–5 раз в секунду) и автосъёмка, когда рамка замерла. */
    private fun analyze(proxy: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysis < 220 || busy || finishing) return
            lastAnalysis = now
            val raw = proxy.toBitmap()
            val small = fitRotate(raw, DETECT_LIVE, proxy.imageInfo.rotationDegrees)
            if (small !== raw) raw.recycle()
            val w = small.width
            val h = small.height
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            small.recycle()
            val q = EdgeDetect.findQuad(px, w, h, staple)
            var norm: FloatArray? = null
            if (q != null) {
                norm = FloatArray(8)
                for (i in 0 until 4) {
                    norm[2 * i] = q[2 * i] / w
                    norm[2 * i + 1] = q[2 * i + 1] / h
                }
            }
            val prev = lastQuad
            var diff = 1f
            if (norm != null && prev != null) {
                diff = 0f
                for (i in 0 until 8) diff = max(diff, abs(norm[i] - prev[i]))
            }
            lastQuad = norm
            if (norm == null || diff > 0.04f) armed = true          // лист убрали или перевернули: можно снимать следующий
            stableCount = if (norm != null && diff < 0.012f) stableCount + 1 else 0
            val isStable = norm != null && stableCount >= 3
            runOnUiThread { overlay.set(norm, isStable) }
            if (autoMode && norm != null && stableCount >= 5 && armed && now - lastShot > 2500) {
                armed = false
                lastShot = now
                runOnUiThread { capture() }
            }
        } catch (e: Throwable) {
            // кадр пропускаем
        } finally {
            proxy.close()
        }
    }

    private fun fitRotate(src: Bitmap, longSide: Int, rot: Int): Bitmap {
        val m = Matrix()
        val s = longSide.toFloat() / max(src.width, src.height)
        if (s < 1f) m.postScale(s, s)
        if (rot != 0) m.postRotate(rot.toFloat())
        if (s >= 1f && rot == 0) return src
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun capture() {
        val ic = imageCapture ?: return
        if (busy || finishing) return
        busy = true
        refreshStatus("снимаю…")
        ic.takePicture(workExec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                var bytes: ByteArray? = null
                var rot = 0
                try {
                    rot = image.imageInfo.rotationDegrees
                    val buf = image.planes[0].buffer
                    val b = ByteArray(buf.remaining())
                    buf.get(b)
                    bytes = b
                } catch (e: Throwable) {
                } finally {
                    image.close()
                }
                try {
                    if (bytes != null) processShot(bytes, rot)
                } catch (e: Throwable) {
                    runOnUiThread { Toast.makeText(this@OwnScanActivity, "Не удалось обработать снимок", Toast.LENGTH_SHORT).show() }
                } finally {
                    busy = false
                    runOnUiThread { refreshStatus("следующий лист") }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                busy = false
                runOnUiThread {
                    Toast.makeText(this@OwnScanActivity, "Ошибка съёмки", Toast.LENGTH_SHORT).show()
                    refreshStatus("попробуйте ещё раз")
                }
            }
        })
    }

    /** Снимок → рамка листа → выпрямленная страница → JPEG во временной папке. */
    private fun processShot(bytes: ByteArray, rot: Int) {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > CAPTURE_MAX) sample *= 2
        val o = BitmapFactory.Options()
        o.inSampleSize = sample
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return
        if (rot != 0) {
            val m = Matrix()
            m.postRotate(rot.toFloat())
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }

        // рамка листа ищем на уменьшенной копии
        val k = DETECT_SHOT.toFloat() / max(bmp.width, bmp.height)
        val dw = (bmp.width * k).roundToInt().coerceAtLeast(32)
        val dh = (bmp.height * k).roundToInt().coerceAtLeast(32)
        val det = Bitmap.createScaledBitmap(bmp, dw, dh, true)
        val px = IntArray(dw * dh)
        det.getPixels(px, 0, dw, 0, 0, dw, dh)
        if (det !== bmp) det.recycle()
        val q = EdgeDetect.findQuad(px, dw, dh, staple)

        val page: Bitmap
        if (q != null) {
            val sx = bmp.width.toFloat() / dw
            val sy = bmp.height.toFloat() / dh
            val full = FloatArray(8)
            for (i in 0 until 4) {
                full[2 * i] = q[2 * i] * sx
                full[2 * i + 1] = q[2 * i + 1] * sy
            }
            page = warp(bmp, full)
            bmp.recycle()
        } else {
            // лист не найден: страница остаётся целым кадром (скан не теряется)
            runOnUiThread { Toast.makeText(this, "Край листа не найден — страница без обрезки", Toast.LENGTH_SHORT).show() }
            page = capLong(bmp, OUT_LONG)
        }

        val dir = pagesDir ?: return
        val f = File(dir, "p" + (System.nanoTime()) + ".jpg")
        FileOutputStream(f).use { page.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        page.recycle()
        synchronized(pages) { pages.add(f) }
    }

    private fun capLong(src: Bitmap, longSide: Int): Bitmap {
        val s = longSide.toFloat() / max(src.width, src.height)
        if (s >= 1f) return src
        val out = Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true)
        if (out !== src) src.recycle()
        return out
    }

    /** Четырёхугольник [q] (лв. верх, пр. верх, пр. низ, лв. низ) → прямоугольная страница. */
    private fun warp(src: Bitmap, q: FloatArray): Bitmap {
        val top = hypot(q[0] - q[2], q[1] - q[3])
        val bottom = hypot(q[6] - q[4], q[7] - q[5])
        val left = hypot(q[0] - q[6], q[1] - q[7])
        val right = hypot(q[2] - q[4], q[3] - q[5])
        var w = (top + bottom) / 2f
        var h = (left + right) / 2f
        val k = OUT_LONG / max(w, h)
        if (k < 1f) {
            w *= k
            h *= k
        }
        val ow = w.roundToInt().coerceAtLeast(16)
        val oh = h.roundToInt().coerceAtLeast(16)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val dst = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix()
        m.setPolyToPoly(q, 0, dst, 0, 4)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    private fun undoLast() {
        if (busy || finishing) return
        synchronized(pages) {
            if (pages.isNotEmpty()) {
                val f = pages.removeAt(pages.size - 1)
                try { f.delete() } catch (e: Exception) { }
            }
        }
        refreshStatus("последний лист удалён")
    }

    private fun cancel() {
        if (finishing) return
        if (pages.isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Выйти? Снятые листы (" + pages.size + ") не сохранятся.")
            .setPositiveButton("Выйти") { _, _ ->
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            .setNegativeButton("Остаться", null)
            .show()
    }

    private fun finishScan() {
        if (busy || finishing) return
        val list = synchronized(pages) { ArrayList(pages) }
        if (list.isEmpty()) {
            Toast.makeText(this, "Снимите хотя бы один лист", Toast.LENGTH_SHORT).show()
            return
        }
        finishing = true
        refreshStatus("собираю PDF…")
        workExec.execute {
            val uri = try { buildPdf(list) } catch (e: Throwable) { null }
            runOnUiThread {
                if (uri != null) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(OwnScan.EXTRA_PDF, uri.toString()))
                    finish()
                } else {
                    finishing = false
                    Toast.makeText(this, "Не удалось собрать PDF", Toast.LENGTH_LONG).show()
                    refreshStatus("попробуйте ещё раз")
                }
            }
        }
    }

    /**
     * Простой PDF: на каждой странице готовый JPEG кладётся как есть (без перекодирования), поэтому файл небольшой
     * и память не растёт с числом листов.
     */
    private fun buildPdf(files: List<File>): Uri {
        val dir = File(cacheDir, "export").also { it.mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val out = File(dir, "Сканирование_$stamp.pdf")
        val n = files.size
        val offsets = LongArray(3 + 3 * n)
        var pos = 0L
        BufferedOutputStream(FileOutputStream(out)).use { os ->
            fun wb(b: ByteArray) { os.write(b); pos += b.size }
            fun ws(s: String) = wb(s.toByteArray(Charsets.ISO_8859_1))

            ws("%PDF-1.4\n")
            offsets[1] = pos
            ws("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            val kids = StringBuilder()
            for (i in 0 until n) kids.append(3 + 3 * i).append(" 0 R ")
            offsets[2] = pos
            ws("2 0 obj\n<< /Type /Pages /Kids [ " + kids + "] /Count " + n + " >>\nendobj\n")

            for (i in 0 until n) {
                val jpg = files[i].readBytes()
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o)
                val iw = o.outWidth.coerceAtLeast(1)
                val ih = o.outHeight.coerceAtLeast(1)
                val sc = 842f / max(iw, ih)
                val pw = (iw * sc).roundToInt().coerceAtLeast(1)
                val ph = (ih * sc).roundToInt().coerceAtLeast(1)
                val pid = 3 + 3 * i
                val cid = pid + 1
                val xid = pid + 2
                val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q\n"
                offsets[pid] = pos
                ws("$pid 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] " +
                        "/Resources << /XObject << /Im0 $xid 0 R >> >> /Contents $cid 0 R >>\nendobj\n")
                offsets[cid] = pos
                ws("$cid 0 obj\n<< /Length " + content.length + " >>\nstream\n" + content + "endstream\nendobj\n")
                offsets[xid] = pos
                ws("$xid 0 obj\n<< /Type /XObject /Subtype /Image /Width $iw /Height $ih /ColorSpace /DeviceRGB " +
                        "/BitsPerComponent 8 /Filter /DCTDecode /Length " + jpg.size + " >>\nstream\n")
                wb(jpg)
                ws("\nendstream\nendobj\n")
            }

            val total = 3 + 3 * n
            val xrefPos = pos
            val sb = StringBuilder()
            sb.append("xref\n0 ").append(total).append("\n")
            sb.append("0000000000 65535 f \n")
            for (id in 1 until total) sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[id]))
            sb.append("trailer\n<< /Size ").append(total).append(" /Root 1 0 R >>\nstartxref\n").append(xrefPos).append("\n%%EOF\n")
            ws(sb.toString())
        }
        return FileProvider.getUriForFile(applicationContext, "$packageName.files", out)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (e: Exception) { }
        analysisExec.shutdown()
        workExec.execute {
            try { pagesDir?.deleteRecursively() } catch (e: Exception) { }
        }
        workExec.shutdown()
    }
}
