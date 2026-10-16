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

private fun ownerOf(ctx: android.content.Context): LifecycleOwner? {
    var c: android.content.Context? = ctx
    while (c is android.content.ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun ScannerScreen(prefs: Prefs, onResult: (String) -> Unit, onClose: () -> Unit, onlyQr: Boolean = false) {
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
                                        val value = result.firstOrNull()?.rawValue
                                        if (value != null && alive.get() && done.compareAndSet(false, true)) {
                                            onResult(value)
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
        Button(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) { Text("Закрыть") }
    }
}
