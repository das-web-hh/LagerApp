package ru.storage.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Этикетки Ströh (артикул, название, цена, штрих-код EAN) — «Инвентаризация», кнопка «+» при пустом поиске.
 *
 * Камера идёт непрерывно: в кадре может быть сколько угодно этикеток, каждая находится по своему штрих-коду.
 * Порядок распознавания каждой этикетки:
 *  1. телефон: штрих-код (ML Kit) + текст (ML Kit OCR) → артикул и название; нужно два одинаковых чтения подряд;
 *  2. если текст не читается, а штрих-код есть в каталоге — берём товар из каталога (тоже без ИИ);
 *  3. иначе кадр с этой этикеткой уходит в ИИ (настройки ИИ — как для документов).
 */
data class ScannedLabel(
    val key: String,
    val barcode: String,
    val article: String = "",
    val name: String = "",
    /** phone — прочитал телефон, catalog — из каталога, ai — прочитал ИИ, wait — ИИ ещё читает, none — не распознано */
    val via: String = ""
)

object StrohLabel {
    private val RE_ART = Regex("^\\d{4,8}$")
    private val RE_ART_NAME = Regex("^(\\d{4,8})\\s+(\\p{L}.{2,})$")
    private val RE_PRICE = Regex("^[€\\s]*\\d+[.,]\\d{2}\\s*€?$")
    private val RE_TIER = Regex("(?i)\\bab\\s*\\d+\\s*st")

    class Parsed(val barcode: String, val article: String, val name: String, val region: Rect)

    /** Ключ товара: тот же код в записи UPC-A (12) и EAN-13 с нулём — один и тот же. */
    fun key(barcode: String): String = barcode.trim().trimStart('0')

    /**
     * Область этикетки по её штрих-коду: штрих-код у Ströh стоит в правом верхнем углу,
     * сама этикетка шире штрих-кода примерно в 3 раза и выше примерно в 3 раза.
     */
    fun region(b: Rect): Rect {
        val w = b.width()
        val h = b.height()
        return Rect(b.right - (w * 3.25f).toInt(), b.top - (h * 0.35f).toInt(), b.right + (w * 0.12f).toInt(), b.top + (h * 3.1f).toInt())
    }

    /** barcodes — все штрих-коды кадра, lines — все строки текста кадра (в координатах повёрнутого кадра). */
    fun parseFrame(barcodes: List<Pair<String, Rect>>, lines: List<Pair<String, Rect>>): List<Parsed> {
        if (barcodes.isEmpty()) return emptyList()
        val regions = barcodes.map { region(it.second) }
        // каждая строка принадлежит ближайшей сверху этикетке, в область которой она попала
        val owned = barcodes.map { ArrayList<Pair<String, Rect>>() }
        for (l in lines) {
            val cx = l.second.centerX()
            val cy = l.second.centerY()
            var best = -1
            var bestTop = Int.MIN_VALUE
            regions.forEachIndexed { i, reg ->
                if (reg.contains(cx, cy)) {
                    val bt = barcodes[i].second.top
                    if (bt <= cy && bt > bestTop) { best = i; bestTop = bt }
                }
            }
            if (best >= 0) owned[best].add(l)
        }
        return barcodes.mapIndexed { i, bc ->
            val code = bc.first
            val core = key(code)
            val bar = bc.second
            val mine = owned[i].sortedBy { it.second.top }
            // цифры под штрих-кодом (и их обрывки) — это не артикул
            fun isCodeDigits(l: Pair<String, Rect>): Boolean {
                val compact = l.first.replace(" ", "")
                if (compact.length >= 2 && compact.all { it.isDigit() } && core.contains(compact.trimStart('0'))) return true
                val r = l.second
                return r.left >= bar.left - bar.width() * 0.1f && r.top < bar.bottom + bar.height() * 0.9f
            }
            var art = ""
            var artLine: Pair<String, Rect>? = null
            var nameFirst = ""
            for (l in mine) {
                if (isCodeDigits(l)) continue
                val t = l.first.trim()
                val compact = t.replace(" ", "")
                if (RE_ART.matches(compact)) { art = compact; artLine = l; break }
                val m = RE_ART_NAME.find(t)
                if (m != null) { art = m.groupValues[1]; artLine = l; nameFirst = m.groupValues[2].trim(); break }
            }
            val parts = ArrayList<String>()
            if (nameFirst.isNotEmpty()) parts.add(nameFirst)
            val al = artLine
            if (al != null) {
                val aMid = al.second.centerY()
                for (l in mine) {
                    if (l === al || isCodeDigits(l)) continue
                    val r = l.second
                    if (r.centerY() <= aMid + r.height() * 0.3f) continue // название — строго под артикулом
                    val t = l.first.trim()
                    val low = t.lowercase()
                    // «% ab 2 Stück nur …» — конец названия
                    if (t.startsWith("%") || low.contains("stück") || low.contains("stuck") || RE_TIER.containsMatchIn(t)) break
                    if (t.contains('€') || RE_PRICE.matches(t)) continue
                    parts.add(t)
                    if (parts.size >= 3) break
                }
            }
            val name = parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
            Parsed(code, art, if (name.any { it.isLetter() }) name else "", regions[i])
        }
    }
}

private class LabelEngine(val app: Context, val items: SnapshotStateList<ScannedLabel>) {
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
    @Volatile var alive = true

    fun close() {
        alive = false
        aiPool.shutdownNow()
    }

    private fun put(l: ScannedLabel) {
        main.post {
            if (!alive && l.via == "wait") return@post
            val i = items.indexOfFirst { it.key == l.key }
            if (i >= 0) items[i] = l
            else {
                items.add(0, l)
                Haptics.pulse(app, Haptics.TAP)
                ScanFeedback.beep(l.via != "none")
            }
        }
    }

    private fun gtinFormat(b: Barcode): Boolean =
        b.format == Barcode.FORMAT_EAN_13 || b.format == Barcode.FORMAT_EAN_8 || b.format == Barcode.FORMAT_UPC_A || b.format == Barcode.FORMAT_UPC_E

    fun analyze(proxy: ImageProxy, bs: BarcodeScanner, tr: TextRecognizer) {
        try {
            val media = proxy.image ?: return
            if (!alive) return
            val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            val codes = Tasks.await(bs.process(img))
            val found = ArrayList<Pair<String, Rect>>()
            for (b in codes) {
                val v = b.rawValue?.trim() ?: continue
                val box = b.boundingBox ?: continue
                if (v.isEmpty()) continue
                if (gtinFormat(b) && !gtinOk(v)) continue // прочитано с ошибкой
                found.add(v to box)
            }
            if (found.isEmpty()) return
            // текст читаем, только если в кадре есть ещё не готовые этикетки
            if (found.all { tracks[StrohLabel.key(it.first)]?.done == true }) return
            val txt = Tasks.await(tr.process(img))
            val lines = ArrayList<Pair<String, Rect>>()
            for (blk in txt.textBlocks) for (ln in blk.lines) {
                val bb = ln.boundingBox ?: continue
                lines.add(ln.text to bb)
            }
            for (p in StrohLabel.parseFrame(found, lines)) handle(p, proxy)
        } catch (e: Exception) {
        } finally {
            proxy.close()
        }
    }

    private fun handle(p: StrohLabel.Parsed, proxy: ImageProxy) {
        val k = StrohLabel.key(p.barcode)
        val t = tracks.getOrPut(k) { Track() }
        if (t.done) return
        if (p.article.isNotEmpty() && p.name.length >= 4) {
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
            if (!aiReady) {
                t.done = true
                put(ScannedLabel(k, p.barcode, p.article, "", "none"))
                return
            }
            val jpeg = cropJpeg(proxy, p.region)
            if (jpeg == null) {
                t.done = true
                put(ScannedLabel(k, p.barcode, p.article, "", "none"))
                return
            }
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

private fun lifecycleOwnerOf(ctx: Context): LifecycleOwner? {
    var c: Context? = ctx
    while (c is android.content.ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/**
 * Окно камеры «Этикетки»: снизу растёт список найденных товаров. «Готово» — вернуть список (без тех, что ИИ ещё читает).
 */
@Composable
fun LabelScanScreen(onDone: (List<ScannedLabel>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }
    val found = remember { mutableStateListOf<ScannedLabel>() }
    val waiting = found.count { it.via == "wait" }
    fun finish() = onDone(found.filter { it.via != "wait" }.toList())
    BackHandler { finish() }

    if (!hasPermission) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Нужен доступ к камере")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Разрешить") }
            TextButton(onClick = onCancel) { Text("Закрыть") }
        }
        return
    }

    val app = context.applicationContext
    val engine = remember { LabelEngine(app, found) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128
                ).build()
        )
    }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    // сначала отвязать камеру, потом гасить поток — иначе кадр уходит в закрытый executor
    DisposableEffect(Unit) {
        onDispose {
            engine.close()
            try { ProcessCameraProvider.getInstance(context).get().unbindAll() } catch (e: Exception) { }
            executor.shutdown()
            try { barcodeScanner.close() } catch (e: Exception) { }
            try { recognizer.close() } catch (e: Exception) { }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    try {
                        if (!engine.alive) return@addListener
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        // для текста нужна чёткая картинка: Full HD вместо стандартных 640x480
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                                    ).build()
                            )
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { proxy ->
                            if (engine.alive) engine.analyze(proxy, barcodeScanner, recognizer) else proxy.close()
                        }
                        val owner = lifecycleOwnerOf(ctx)
                        if (owner == null) { onCancel(); return@addListener }
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } catch (e: Exception) {
                        onCancel()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 20.dp, end = 130.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.6f)
        ) {
            Text(
                "Наведите камеру на этикетки: можно сразу на несколько",
                color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 3
            )
        }
        Button(
            onClick = { finish() },
            enabled = waiting == 0,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) { Text(if (waiting == 0) "Готово (${found.size})" else "ИИ… $waiting") }

        if (found.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 300.dp),
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                LazyColumn(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    items(found.toList(), key = { it.key }) { l ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        l.name.isNotBlank() -> l.name
                                        l.via == "wait" -> "ИИ читает этикетку…"
                                        else -> "Не распознано — название введёте после"
                                    },
                                    color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                val sub = listOf(
                                    if (l.article.isNotBlank()) "арт. ${l.article}" else "",
                                    "ШК ${l.barcode}",
                                    when (l.via) { "phone" -> "телефон"; "catalog" -> "каталог"; "ai" -> "ИИ"; else -> "" }
                                ).filter { it.isNotEmpty() }.joinToString(" · ")
                                Text(sub, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { found.removeAll { it.key == l.key } }) { Text("✕", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}
