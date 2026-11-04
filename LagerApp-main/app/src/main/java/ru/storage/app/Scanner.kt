package ru.storage.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

val allFormats = listOf(
    "QR-код" to Barcode.FORMAT_QR_CODE,
    "EAN-13" to Barcode.FORMAT_EAN_13,
    "EAN-8" to Barcode.FORMAT_EAN_8,
    "UPC-A" to Barcode.FORMAT_UPC_A,
    "UPC-E" to Barcode.FORMAT_UPC_E,
    "Code 128" to Barcode.FORMAT_CODE_128,
    "Code 39" to Barcode.FORMAT_CODE_39,
    "Code 93" to Barcode.FORMAT_CODE_93,
    "ITF" to Barcode.FORMAT_ITF,
    "Codabar" to Barcode.FORMAT_CODABAR,
    "Data Matrix" to Barcode.FORMAT_DATA_MATRIX,
    "PDF417" to Barcode.FORMAT_PDF417,
    "Aztec" to Barcode.FORMAT_AZTEC
)

/** Контрольная цифра GTIN (EAN-8, UPC-A, EAN-13, GTIN-14). false — код прочитан с ошибкой. */
fun gtinOk(s: String): Boolean {
    if (s.length !in intArrayOf(8, 12, 13, 14) || !s.all { it in '0'..'9' }) return false
    val d = s.reversed()
    var sum = 0
    for (i in 1 until d.length) sum += (d[i] - '0') * (if (i % 2 == 1) 3 else 1)
    return (10 - sum % 10) % 10 == d[0] - '0'
}

/** Один и тот же товарный код в разной записи: UPC-A (12) и EAN-13 с нулём впереди — это один код. */
fun barcodeVariants(code: String): List<String> {
    val c = code.trim()
    if (c.isEmpty() || !c.all { it in '0'..'9' }) return listOf(c)
    val core = c.trimStart('0')
    if (core.isEmpty()) return listOf(c)
    val out = linkedSetOf(c, core)
    for (len in intArrayOf(8, 12, 13, 14)) if (len >= core.length) out.add(core.padStart(len, '0'))
    return out.toList()
}

fun sameBarcode(a: String, b: String): Boolean =
    a.isNotBlank() && b.isNotBlank() && (a.trim() == b.trim() || b.trim() in barcodeVariants(a))

/** Сколько раз подряд (с паузой между кадрами) код должен прочитаться одинаково, чтобы считаться верным. */
private const val CONFIRM_GAP_MS = 400L
private const val CONFIRM_WINDOW_MS = 3000L
/** Непрерывный режим: тот же код считается снова, только если он пропал из кадра на это время. */
private const val REARM_MS = 600L

/** Из результатов кадра берём только код, у которого сходится контрольная цифра (EAN/UPC), — ошибочные чтения отбрасываются. */
private fun validValue(list: List<Barcode>): String? = list.firstOrNull { b ->
    val v = b.rawValue?.trim()
    v != null && v.isNotEmpty() && (
        (b.format != Barcode.FORMAT_EAN_13 && b.format != Barcode.FORMAT_EAN_8 && b.format != Barcode.FORMAT_UPC_A) || gtinOk(v)
        )
}?.rawValue?.trim()

private fun ownerOf(ctx: android.content.Context): LifecycleOwner? {
    var c: android.content.Context? = ctx
    while (c is android.content.ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun ScannerScreen(
    prefs: Prefs,
    onResult: (String) -> Unit,
    onClose: () -> Unit,
    onlyQr: Boolean = false,
    /** true — сканер не закрывается после кода: каждый код уходит в onResult, пока не нажмут «Закрыть» */
    continuous: Boolean = false,
    /** подпись сверху (для непрерывного режима: что добавилось) */
    status: String = ""
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }
    BackHandler(onBack = onClose)

    if (!hasPermission) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Нужен доступ к камере")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Разрешить") }
            TextButton(onClick = onClose) { Text("Закрыть") }
        }
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }
    val alive = remember { AtomicBoolean(true) }
    // двойная проверка: код принимается, только когда два чтения подряд (с паузой) совпали
    val cand = remember { arrayOf<String?>(null) }
    val candAt = remember { longArrayOf(0L) }
    var hint by remember { mutableStateOf("") }
    val lastOk = remember { arrayOf<String?>(null) }
    val lastSeen = remember { longArrayOf(0L) }
    val scannerRef = remember { arrayOfNulls<com.google.mlkit.vision.barcode.BarcodeScanner>(1) }
    // Порядок важен: сначала отвязать камеру, потом гасить поток, иначе кадр уходит в закрытый executor и приложение падает.
    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (e: Exception) {
            }
            executor.shutdown()
            try { scannerRef[0]?.close() } catch (e: Exception) {}
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
                    if (!alive.get()) return@addListener
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)

                    val codes = if (onlyQr) listOf(Barcode.FORMAT_QR_CODE) else prefs.formats.toList()
                    val options = BarcodeScannerOptions.Builder().apply {
                        if (codes.isEmpty()) setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                        else setBarcodeFormats(codes.first(), *codes.drop(1).toIntArray())
                    }.build()
                    val scanner = BarcodeScanning.getClient(options)
                    scannerRef[0] = scanner

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        val media = proxy.image
                        if (media != null && !done.get() && alive.get()) {
                            try {
                                val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                                scanner.process(image)
                                    .addOnSuccessListener { result ->
                                        val value = validValue(result)
                                        if (alive.get() && !done.get()) {
                                            val now = SystemClock.elapsedRealtime()
                                            if (value == null) {
                                                // код пропал из кадра — следующий такой же код снова считается
                                                if (continuous && now - lastSeen[0] > REARM_MS) lastOk[0] = null
                                            } else {
                                                lastSeen[0] = now
                                                if (continuous && value == lastOk[0]) {
                                                    // тот же код всё ещё в кадре — второй раз не считаем
                                                } else if (cand[0] != value || now - candAt[0] > CONFIRM_WINDOW_MS) {
                                                    // первое чтение (или другое значение) — запоминаем и ждём подтверждения
                                                    cand[0] = value
                                                    candAt[0] = now
                                                    hint = "Проверяю код…"
                                                } else if (now - candAt[0] >= CONFIRM_GAP_MS) {
                                                    if (continuous) {
                                                        cand[0] = null
                                                        lastOk[0] = value
                                                        hint = ""
                                                        onResult(value)
                                                    } else if (done.compareAndSet(false, true)) {
                                                        onResult(value)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } catch (e: Exception) {
                                proxy.close()
                            }
                        } else {
                            proxy.close()
                        }
                    }

                    val owner = ownerOf(ctx)
                    if (owner == null) { onClose(); return@addListener }
                    val selector = if (prefs.useFront && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, selector, preview, analysis)
                  } catch (e: Exception) {
                    // камера занята или недоступна – закрываем сканер, а не роняем приложение
                    onClose()
                  }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )
        if (hint.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(hint, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        if (status.isNotEmpty()) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 20.dp, end = 120.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(status, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), maxLines = 3)
            }
        }
        Button(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) { Text(if (continuous) "Готово" else "Закрыть") }
    }
}
