package ru.storage.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

private val homeSections = listOf(
    "Поиск",
    "История",
    "Инвентаризация",
    "Приём испорченных товаров",
    "Инвентаризация испорченных товаров",
    "Каталог товаров",
    "Задачи",
    "Шаблоны накладных",
    "Инфо",
    "Чат"
)

@Composable
fun HomeScreen(prefs: Prefs) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val current = section
    val hctx = LocalContext.current
    LaunchedEffect(Unit) {
        InvoiceProfiles.load(hctx)
        try { withContext(Dispatchers.IO) { InvoiceProfiles.refresh(hctx) } } catch (e: Exception) { }
    }

    when {
        current == "Поиск" -> SearchScreen(prefs) { section = null }
        current == "Каталог товаров" -> CatalogScreen(prefs) { section = null }
        current == "История" -> HistoryScreen { section = null }
        current == "Шаблоны накладных" -> InvoiceProfilesScreen { section = null }
        current != null -> SectionScreen(current) { section = null }
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            homeSections.forEach { name ->
                FilledTonalButton(
                    onClick = { section = name },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    val n = if (name == "Шаблоны накладных") InvoiceProfiles.newCount else 0
                    Text(if (n > 0) "$name ($n)" else name)
                }
            }
        }
    }
}

@Composable
fun SectionScreen(title: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Окно в разработке")
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ProfileScreen(profile: UserProfile, prefs: Prefs, onLogout: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(prefs) { showSettings = false }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Профиль", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        ProfileRow("Имя", profile.firstName)
        ProfileRow("Фамилия", profile.lastName)
        ProfileRow("Номер телефона", profile.phone)
        ProfileRow("Электронная почта", profile.email)
        ProfileRow("Пароль", "••••••••")
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(
            onClick = { showSettings = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Настройки") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Выйти") }
    }
}

@Composable
fun ReceiveScreen(prefs: Prefs, receiver: String) {
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    val current = mode
    val ctx = LocalContext.current
    var pickMode by remember { mutableStateOf("auto") }
    var deleteJob by remember { mutableStateOf<AutoJob?>(null) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val m = pickMode
        // один файл — сразу открываем; несколько — создаём партии, открывать их можно из списка ниже
        if (uris.isNotEmpty()) thread { uris.forEach { Incoming.accept(ctx, it, m, open = uris.size == 1) } }
    }

    if (current == "Ручной") {
        ManualReceiveScreen(prefs, receiver) { mode = null }
        return
    }
    if (current == "По наименованию") {
        NameReceiveHub(onBack = { mode = null })
        return
    }
    if (current == "Итог приёма") {
        ReceiveTotalScreen { mode = null }
        return
    }
    if (current != null) {
        SectionScreen(current) { mode = null }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Приём", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        listOf("Ручной", "По наименованию", "Автоприём", "Итог приёма").forEach { name ->
            FilledTonalButton(
                onClick = {
                    when (name) {
                        "Автоприём" -> {
                            pickMode = "auto"
                            pdfPicker.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                        }
                        "По наименованию" -> mode = name
                        else -> mode = name
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text(name) }
        }

        val jobs = AutoReceiveHolder.jobs.filter { !it.nameMode }
        if (jobs.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Партии в работе: ${jobs.size}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Каждую партию можно свернуть и вернуться к ней позже — она не закроется, пока вы её не сохраните или не удалите.",
                style = MaterialTheme.typography.bodySmall
            )
            jobs.forEach { j -> JobCard(j) { deleteJob = it } }
        }
    }

    deleteJob?.let { j ->
        AlertDialog(
            onDismissRequest = { deleteJob = null },
            title = { Text(if (j.saved) "Убрать из списка?" else "Удалить партию?") },
            text = {
                Text(if (j.saved) "Партия уже сохранена, из списка она просто исчезнет."
                else "Партия не сохранена. Введённые количества будут потеряны.")
            },
            confirmButton = {
                Button(onClick = { AutoReceiveHolder.remove(j); deleteJob = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteJob = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun JobCard(j: AutoJob, onDelete: (AutoJob) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp).clickable { Incoming.openPath = j.file.path }
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val title = j.sender.takeIf { !it.equals("none", true) && it.isNotBlank() } ?: j.file.name
                Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val order = j.order.takeIf { !it.equals("none", true) && it.isNotBlank() }
                val kind = if (j.nameMode) "По имени" else "Автоприём"
                Text(listOfNotNull(kind, order?.let { "заказ $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall)
                Text(j.statusLine, style = MaterialTheme.typography.bodySmall,
                    color = if (j.saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onDelete(j) }) { Text("✕") }
        }
    }
}

private fun findActivity(ctx: Context): Activity? {
    var c: Context? = ctx
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** «По наименованию»: сверху «Файл» и «Снимок» (сканер документа как в Google Диске), ниже партии, которые считаются. */
@Composable
fun NameReceiveHub(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var deleteJob by remember { mutableStateOf<AutoJob?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) thread { uris.forEach { Incoming.accept(ctx, it, "name", open = uris.size == 1) } }
    }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val pdf = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf?.uri
            if (pdf != null) thread { Incoming.accept(ctx, pdf, "name", open = true) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("По наименованию", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { filePicker.launch(arrayOf("application/pdf", "image/jpeg", "image/png")) },
                modifier = Modifier.weight(1f)
            ) { Text("Файл") }
            FilledTonalButton(
                onClick = {
                    val act = findActivity(ctx)
                    if (act == null) return@FilledTonalButton
                    val opts = GmsDocumentScannerOptions.Builder()
                        .setGalleryImportAllowed(false)
                        .setPageLimit(10)
                        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                        .build()
                    GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
                        .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
                        .addOnFailureListener {
                            Toast.makeText(ctx, "Сканер недоступен: " + (it.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Снимок") }
        }

        val jobs = AutoReceiveHolder.jobs.filter { it.nameMode }
        if (jobs.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Партии в работе: ${jobs.size}", style = MaterialTheme.typography.titleMedium)
            jobs.forEach { j -> JobCard(j) { deleteJob = it } }
        }
    }

    deleteJob?.let { j ->
        AlertDialog(
            onDismissRequest = { deleteJob = null },
            title = { Text(if (j.saved) "Убрать из списка?" else "Удалить партию?") },
            text = {
                Text(if (j.saved) "Партия уже сохранена, из списка она просто исчезнет."
                else "Партия не сохранена. Введённые количества будут потеряны.")
            },
            confirmButton = {
                Button(onClick = { AutoReceiveHolder.remove(j); deleteJob = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteJob = null }) { Text("Отмена") } }
        )
    }
}
