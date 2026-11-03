package ru.storage.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Режим «Съёмка для документов»: вместо системной камеры открывается наша с живым чёрно-белым просмотром
 * и ползунками яркости и контраста — видно, каким получится лист при печати. Цветной оригинал сохраняется как
 * обычно (он уходит на Диск и открывается по QR), чёрно-белая версия — только для печати.
 */
object DocCam {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("doccam", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean("on", false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("on", v).apply()

    fun bw(ctx: Context) = sp(ctx).getBoolean("bw", true)
    fun brightness(ctx: Context) = sp(ctx).getFloat("bright", 0.05f)
    fun contrast(ctx: Context) = sp(ctx).getFloat("contrast", 1.4f)
    fun flatten(ctx: Context) = sp(ctx).getBoolean("flatten", true)

    fun saveParams(ctx: Context, bw: Boolean, bright: Float, contrast: Float, flatten: Boolean) =
        sp(ctx).edit().putBoolean("bw", bw).putFloat("bright", bright).putFloat("contrast", contrast).putBoolean("flatten", flatten).apply()

    /** Чёрно-белая версия для печати лежит вне папки медиа (на Диск не уходит, в «Фото по QR» не попадает). */
    fun printFile(ctx: Context, name: String): File =
        File(File(ctx.applicationContext.filesDir, "printbw").also { it.mkdirs() }, name)

    /** После съёмки: делает печатную версию с теми же настройками, что были на экране камеры. Фото уже должно быть сжато и повёрнуто. */
    fun makePrint(ctx: Context, name: String) {
        val pf = printFile(ctx, name)
        try {
            if (!bw(ctx)) { pf.delete(); return }
            var src = Media.thumb(Media.file(ctx, name), 1600) ?: return
            val side = max(src.width, src.height)
            if (side > 1800) { // не больше 1800 px: хватает для листа 6×8 см и бережёт память
                val k = 1800f / side
                src = Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true)
            }
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val lum = IntArray(px.size) { i ->
                val c = px[i]
                (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)).toInt()
            }
            val out = BwFilter.apply(lum, w, h, brightness(ctx), contrast(ctx), flatten(ctx))
            val bmp = BwFilter.bitmapOf(out, w, h)
            ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                pf.writeBytes(bos.toByteArray())
            }
        } catch (e: Throwable) {
            pf.delete()
        }
    }

    fun drop(ctx: Context, name: String) {
        try { printFile(ctx, name).delete() } catch (e: Exception) { }
    }
}

object BwFilter {
    /**
     * lum 0..255 → серое изображение для печати. flatten — выравнивание освещения (тень и блик): яркость каждого участка
     * подтягивается к общему «фону» листа; затем яркость (−0,4…0,4) и контраст (0,6…3).
     */
    fun apply(lum: IntArray, w: Int, h: Int, brightness: Float, contrast: Float, flatten: Boolean): IntArray {
        val n = w * h
        val out = IntArray(n)
        var src = lum
        if (flatten && w >= 8 && h >= 8) {
            val cs = max(8, max(w, h) / 20)
            val gw = (w + cs - 1) / cs
            val gh = (h + cs - 1) / cs
            val sum = DoubleArray(gw * gh)
            val cnt = IntArray(gw * gh)
            for (y in 0 until h) {
                val row = (y / cs) * gw
                for (x in 0 until w) {
                    sum[row + x / cs] += lum[y * w + x].toDouble()
                    cnt[row + x / cs]++
                }
            }
            val avg = FloatArray(gw * gh) { (sum[it] / max(1, cnt[it])).toFloat() }
            val flat = IntArray(n)
            for (y in 0 until h) {
                val fy = ((y + 0.5f) / cs - 0.5f).coerceIn(0f, (gh - 1).toFloat())
                val y0 = floor(fy).toInt()
                val y1 = min(gh - 1, y0 + 1)
                val ty = fy - y0
                for (x in 0 until w) {
                    val fx = ((x + 0.5f) / cs - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                    val x0 = floor(fx).toInt()
                    val x1 = min(gw - 1, x0 + 1)
                    val tx = fx - x0
                    val top = avg[y0 * gw + x0] * (1 - tx) + avg[y0 * gw + x1] * tx
                    val bot = avg[y1 * gw + x0] * (1 - tx) + avg[y1 * gw + x1] * tx
                    val bg = (top * (1 - ty) + bot * ty).coerceAtLeast(30f)
                    // фон листа → светло-серый (≈200), тёмные участки осветляются сильнее светлых
                    val k = (200f / bg).toDouble().pow(0.9).coerceIn(0.6, 3.5)
                    flat[y * w + x] = (lum[y * w + x] * k).toInt().coerceIn(0, 255)
                }
            }
            src = flat
        }
        val c = contrast.coerceIn(0.4f, 4f)
        val b = brightness.coerceIn(-0.6f, 0.6f)
        val lut = IntArray(256) { v -> (((v / 255f - 0.5f) * c + 0.5f + b) * 255f).toInt().coerceIn(0, 255) }
        for (i in 0 until n) out[i] = lut[src[i].coerceIn(0, 255)]
        return out
    }

    fun bitmapOf(l: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(IntArray(l.size) { android.graphics.Color.rgb(l[it], l[it], l[it]) }, 0, w, 0, 0, w, h)
        return bmp
    }
}

private fun ownerOf(ctx: Context): LifecycleOwner? {
    var c: Context? = ctx
    while (c != null) {
        if (c is LifecycleOwner) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}

/** Кадр камеры (яркость, Y-плоскость) → уменьшенный и повёрнутый «как на экране» массив яркости. */
private fun frameToLum(img: ImageProxy): Triple<IntArray, Int, Int> {
    val plane = img.planes[0]
    val buf = plane.buffer
    val rs = plane.rowStride
    val ps = plane.pixelStride
    val w = img.width
    val h = img.height
    val step = max(1, max(w, h) / 320)
    val sw = w / step
    val sh = h / step
    val l = IntArray(sw * sh)
    for (y in 0 until sh) {
        val base = y * step * rs
        for (x in 0 until sw) l[y * sw + x] = buf.get(base + x * step * ps).toInt() and 0xFF
    }
    return when (img.imageInfo.rotationDegrees) {
        90 -> { // ow = sh, oh = sw: out(ox, oy) = src(oy, sh-1-ox)
            val o = IntArray(l.size)
            for (oy in 0 until sw) for (ox in 0 until sh) o[oy * sh + ox] = l[(sh - 1 - ox) * sw + oy]
            Triple(o, sh, sw)
        }
        180 -> {
            val o = IntArray(l.size)
            for (i in l.indices) o[i] = l[l.size - 1 - i]
            Triple(o, sw, sh)
        }
        270 -> { // out(ox, oy) = src(sw-1-oy, ox)
            val o = IntArray(l.size)
            for (oy in 0 until sw) for (ox in 0 until sh) o[oy * sh + ox] = l[ox * sw + (sw - 1 - oy)]
            Triple(o, sh, sw)
        }
        else -> Triple(l, sw, sh)
    }
}

/**
 * Живая камера: сверху чёрно-белый просмотр «как будет напечатано», внизу ползунки.
 * onDone(true) — фото записано в photo; настройки сохранены (из них потом делается печатная версия).
 */
@Composable
fun LiveCameraDialog(photo: File, onDone: (Boolean) -> Unit) {
    Dialog(
        onDismissRequest = { onDone(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) { LiveCameraBody(photo, onDone) }
    }
}

@Composable
private fun LiveCameraBody(photo: File, onDone: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val owner = remember { ownerOf(ctx) }
    val scope = rememberCoroutineScope()
    var bw by remember { mutableStateOf(DocCam.bw(ctx)) }
    var bright by remember { mutableFloatStateOf(DocCam.brightness(ctx)) }
    var contrast by remember { mutableFloatStateOf(DocCam.contrast(ctx)) }
    var flatten by remember { mutableStateOf(DocCam.flatten(ctx)) }
    var torch by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<Bitmap?>(null) }
    var cam by remember { mutableStateOf<Camera?>(null) }
    val params = remember { AtomicReference(floatArrayOf(1f, 0.05f, 1.4f, 1f)) }
    SideEffect { params.set(floatArrayOf(if (bw) 1f else 0f, bright, contrast, if (flatten) 1f else 0f)) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(Unit) {
        val lo = owner ?: return@LaunchedEffect
        try {
            val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(ctx).get() }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { img ->
                try {
                    val p = params.get()
                    if (p[0] > 0.5f) {
                        val (l, w, h) = frameToLum(img)
                        live = BwFilter.bitmapOf(BwFilter.apply(l, w, h, p[1], p[2], p[3] > 0.5f), w, h)
                    } else live = null
                } catch (e: Throwable) {
                } finally {
                    img.close()
                }
            }
            provider.unbindAll()
            cam = provider.bindToLifecycle(lo, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Камера недоступна: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            onDone(false)
        }
    }
    LaunchedEffect(torch, cam) { try { cam?.cameraControl?.enableTorch(torch) } catch (e: Exception) { } }
    DisposableEffect(Unit) {
        onDispose {
            try { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } catch (e: Exception) { }
            executor.shutdown()
        }
    }

    Column(Modifier.fillMaxSize().background0()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            val bmp = live
            if (bw && bmp != null) {
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { torch = !torch }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(if (torch) "💡 Вкл" else "💡") }
            }
        }
        Column(Modifier.fillMaxWidth().background1().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = bw, onCheckedChange = { bw = it })
                Spacer(Modifier.width(8.dp))
                Text("Ч/Б как при печати", color = Color.White, modifier = Modifier.weight(1f))
                Text("Выровнять свет", color = Color.White)
                Spacer(Modifier.width(6.dp))
                Switch(checked = flatten, onCheckedChange = { flatten = it }, enabled = bw)
            }
            Text("Яркость", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Slider(value = bright, onValueChange = { bright = it }, valueRange = -0.4f..0.4f, enabled = bw)
            Text("Контраст", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.6f..3f, enabled = bw)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onDone(false) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Отмена") }
                Button(
                    enabled = !busy,
                    modifier = Modifier.weight(2f).height(56.dp),
                    onClick = {
                        busy = true
                        DocCam.saveParams(ctx, bw, bright, contrast, flatten)
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(photo).build(),
                            ContextCompat.getMainExecutor(ctx),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) { onDone(true) }
                                override fun onError(e: ImageCaptureException) {
                                    busy = false
                                    Toast.makeText(ctx, "Не удалось снять: ${e.message ?: ""}", Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }
                ) { Text(if (busy) "Сохранение…" else "Снять") }
            }
        }
    }
}

private fun Modifier.background0(): Modifier = this.then(Modifier.background(Color.Black))
private fun Modifier.background1(): Modifier = this.then(Modifier.background(Color(0xFF1B1B1B)))
