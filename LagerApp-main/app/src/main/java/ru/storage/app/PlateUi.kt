package ru.storage.app

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val PlateBlue = Color(0xFF003399)
private val PlateText = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
    fontSize = 24.sp, letterSpacing = 2.sp, color = Color.Black
)

private fun Context.lifecycleOwnerOrNull(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/**
 * Поле номера машины/прицепа в виде автомобильного номера.
 * Слева синяя полоса: нажмите, чтобы сменить страну (по умолчанию Германия).
 * Справа кнопка 📷: навести камеру на номер – он распознается и запишется в поле.
 */
@Composable
fun PlateField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var country by remember { mutableStateOf(detectPlateCountry(value)) }
    var menu by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (tfv.text != value) tfv = TextFieldValue(value, TextRange(value.length))
    }

    val region = value.substringBefore(' ')
    val badRegion = country == PlateCountry.DE && value.contains(' ') && !GermanRegions.isKnown(region)

    Column(modifier) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier.weight(1f).height(52.dp).clip(shape).background(Color.White)
                    .border(BorderStroke(2.dp, Color.Black), shape),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.width(38.dp).fillMaxHeight().background(PlateBlue).clickable { menu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("★", color = Color(0xFFFFCC00), fontSize = 9.sp)
                        Text(country.euCode, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        PlateCountry.values().forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.flag}  ${c.title}") },
                                onClick = {
                                    country = c; menu = false
                                    onChange(formatPlate(c, value))
                                }
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(country.hint, style = PlateText.copy(color = Color(0xFFC4C4C4)), maxLines = 1)
                    BasicTextField(
                        value = tfv,
                        onValueChange = { nv ->
                            val f = formatPlate(country, nv.text)
                            tfv = if (f == nv.text) nv else TextFieldValue(f, TextRange(f.length))
                            onChange(f)
                        },
                        singleLine = true,
                        textStyle = PlateText,
                        cursorBrush = SolidColor(PlateBlue),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            FilledTonalButton(
                onClick = { scanning = true },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(52.dp)
            ) { Text("📷", fontSize = 22.sp) }
        }
        if (badRegion) {
            Text(
                "Кода региона «$region» нет в базе — проверьте",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }

    if (scanning) {
        PlateScannerDialog(
            country = country,
            onResult = { plate -> scanning = false; onChange(formatPlate(country, plate)) },
            onClose = { scanning = false }
        )
    }
}

private class PlateVote { var cand: String? = null; var hits = 0 }

@Composable
private fun PlateScannerDialog(country: PlateCountry, onResult: (String) -> Unit, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            PlateScannerContent(country, onResult, onClose)
        }
    }
}

@Composable
private fun PlateScannerContent(country: PlateCountry, onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Нужен доступ к камере", color = Color.White)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Разрешить") }
            TextButton(onClick = onClose) { Text("Закрыть") }
        }
        return
    }

    val owner = remember { context.lifecycleOwnerOrNull() }
    if (owner == null) { LaunchedEffect(Unit) { onClose() }; return }

    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val alive = remember { AtomicBoolean(true) }
    val done = remember { AtomicBoolean(false) }
    val lastRun = remember { AtomicLong(0L) }
    val vote = remember { PlateVote() }
    val providerRef = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    var live by remember { mutableStateOf("") }

    // Сначала отвязываем камеру и только потом гасим поток – иначе кадр попадает в закрытый executor и приложение падает.
    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            try { providerRef[0]?.unbindAll() } catch (_: Exception) {}
            executor.shutdown()
            try { recognizer.close() } catch (_: Exception) {}
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    try {
                        if (!alive.get()) return@addListener
                        val provider = future.get()
                        providerRef[0] = provider
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { proxy ->
                            val now = SystemClock.elapsedRealtime()
                            val media = proxy.image
                            if (!alive.get() || done.get() || media == null || now - lastRun.get() < 350) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            lastRun.set(now)
                            try {
                                val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                                recognizer.process(img)
                                    .addOnSuccessListener { r ->
                                        if (!alive.get()) return@addOnSuccessListener
                                        val plate = extractPlate(country, r.text)
                                        live = plate ?: ""
                                        if (plate == null) { vote.cand = null; vote.hits = 0 }
                                        else {
                                            if (plate == vote.cand) vote.hits++ else { vote.cand = plate; vote.hits = 1 }
                                            // один и тот же номер в двух кадрах подряд – принимаем
                                            if (vote.hits >= 2 && done.compareAndSet(false, true)) onResult(plate)
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } catch (e: Exception) {
                                proxy.close()
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } catch (e: Exception) {
                        // камера занята/недоступна – закрываем окно, а не роняем приложение
                        onClose()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(0.9f).height(120.dp)
                .border(BorderStroke(3.dp, Color.White), RoundedCornerShape(12.dp))
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (live.isEmpty()) "Наведите камеру на номер (${country.title})" else live,
                color = Color.White,
                style = if (live.isEmpty()) MaterialTheme.typography.bodyMedium else PlateText.copy(color = Color.White)
            )
            Spacer(Modifier.height(8.dp))
            Text("Номер запишется сам, когда будет распознан", color = Color(0xFFCCCCCC), fontSize = 12.sp)
        }
        Button(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("Закрыть") }
    }
}
