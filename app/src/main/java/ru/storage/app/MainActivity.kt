package ru.storage.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var useFront: Boolean
        get() = sp.getBoolean("front", false)
        set(v) { sp.edit().putBoolean("front", v).apply() }

    var formats: Set<Int>
        get() = (sp.getStringSet("formats", emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }.toSet()
        set(v) { sp.edit().putStringSet("formats", v.map { it.toString() }.toSet()).apply() }
}

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

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var loggedIn by rememberSaveable { mutableStateOf(false) }
    if (loggedIn) MainScreen(prefs = prefs, onLogout = { loggedIn = false })
    else LoginScreen(onSuccess = { loggedIn = true })
}

@Composable
fun LoginScreen(onSuccess: () -> Unit) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Lager", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = login,
            onValueChange = { login = it },
            label = { Text("Логин") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (login.trim() == "admin" && password == "admin") onSuccess()
                else error = "Неверный логин или пароль"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Войти") }
    }
}

private data class Tab(val title: String, val icon: ImageVector)

@Composable
fun MainScreen(prefs: Prefs, onLogout: () -> Unit) {
    val tabs = listOf(
        Tab("Главная", Icons.Default.Home),
        Tab("Приём", Icons.Default.Add),
        Tab("Отправка", Icons.AutoMirrored.Filled.Send),
        Tab("Настройки", Icons.Default.Settings)
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> HomeScreen(prefs)
                1 -> Placeholder("Приём")
                2 -> Placeholder("Отправка")
                else -> SettingsScreen(prefs, onLogout)
            }
        }
    }
}

@Composable
fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
fun HomeScreen(prefs: Prefs) {
    var query by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }

    if (scanning) {
        ScannerScreen(
            prefs = prefs,
            onResult = { code ->
                query = code
                scanning = false
            },
            onClose = { scanning = false }
        )
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { scanning = true }) { Text("📷 Скан") }
            }
        }
    }
}

@Composable
fun ScannerScreen(prefs: Prefs, onResult: (String) -> Unit, onClose: () -> Unit) {
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
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (e: Exception) {
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)

                    val codes = prefs.formats.toList()
                    val options = BarcodeScannerOptions.Builder().apply {
                        if (codes.isEmpty()) setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                        else setBarcodeFormats(codes.first(), *codes.drop(1).toIntArray())
                    }.build()
                    val scanner = BarcodeScanning.getClient(options)

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        val media = proxy.image
                        if (media != null && !done.get()) {
                            val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            scanner.process(image)
                                .addOnSuccessListener { result ->
                                    val value = result.firstOrNull()?.rawValue
                                    if (value != null && done.compareAndSet(false, true)) {
                                        onResult(value)
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        } else {
                            proxy.close()
                        }
                    }

                    val selector = if (prefs.useFront) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
                    provider.unbindAll()
                    provider.bindToLifecycle(ctx as LifecycleOwner, selector, preview, analysis)
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

@Composable
fun SettingsScreen(prefs: Prefs, onLogout: () -> Unit) {
    var front by remember { mutableStateOf(prefs.useFront) }
    var formats by remember { mutableStateOf(prefs.formats) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Text("Камера для сканера", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !front, onClick = { front = false; prefs.useFront = false })
            Text("Основная")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = front, onClick = { front = true; prefs.useFront = true })
            Text("Фронтальная")
        }

        Spacer(Modifier.height(16.dp))
        Text("Типы кодов", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = formats.isEmpty(),
                onCheckedChange = { formats = emptySet(); prefs.formats = emptySet() }
            )
            Text("Все типы")
        }
        allFormats.forEach { (name, code) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = code in formats,
                    onCheckedChange = { on ->
                        val n = if (on) formats + code else formats - code
                        formats = n
                        prefs.formats = n
                    }
                )
                Text(name)
            }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onLogout) { Text("Выйти") }
    }
}
