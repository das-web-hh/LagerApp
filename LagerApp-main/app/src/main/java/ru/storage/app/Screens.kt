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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.List
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

/**
 * Где находится человек внутри вкладок «Работа» и «Прочее». Хранится вне экрана: когда открывается партия (она закрывает вкладку),
 * экран вкладки уничтожается, и «Назад» раньше падал сразу в корень «Работы». Теперь возврат — в то окно, откуда партию открыли.
 */
object TabNav {
    var work by mutableStateOf<String?>(null)
    var other by mutableStateOf<String?>(null)
}

/** Делегат для локальной переменной экрана, которая на самом деле лежит в TabNav. */
class NavVar(private val read: () -> String?, private val write: (String?) -> Unit) {
    operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): String? = read()
    operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: String?) = write(value)
}

@Composable
fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Окно «Прочее»: шаблоны накладных, каталог товаров, инфо. */
@Composable
fun OtherScreen(prefs: Prefs) {
    var section by NavVar({ TabNav.other }, { TabNav.other = it })
    val current = section
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { InvoiceProfiles.load(ctx) }
    SideEffect { TabRoot.set(2, section == null) }
    when {
        current == "Каталог товаров" -> CatalogScreen(prefs) { section = null }
        current == "Шаблоны накладных" -> InvoiceProfilesScreen { section = null }
        current == "История" -> HistoryScreen { section = null }
        current == "Инфо" -> InfoScreen { section = null }
        current == "Журнал" -> JournalScreen { section = null }
        current == "Документы" -> DocsScreen { section = null }
        current == "Bluetooth" -> BluetoothScreen { section = null }
        current == "Файлы" -> FileBrowserScreen(onBack = { section = null })
        current == "Рабочее время" -> WorkTimeScreen { section = null }
        current != null -> SectionScreen(current) { section = null }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Прочее", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            TileGrid(
                listOf(
                    TileSpec("Шаблоны накладных", Icons.Default.Edit, InvoiceProfiles.newCount) { section = "Шаблоны накладных" },
                    TileSpec("Каталог товаров", Icons.Default.Menu) { section = "Каталог товаров" },
                    TileSpec("История", Icons.Default.DateRange) { section = "История" },
                    TileSpec("Инфо", Icons.Default.Info) { section = "Инфо" },
                    TileSpec("Журнал", Icons.AutoMirrored.Filled.List) { section = "Журнал" },
                    TileSpec("Документы", Icons.Default.Create) { section = "Документы" },
                    TileSpec("Файлы", Icons.Default.Share) { section = "Файлы" },
                    TileSpec("Bluetooth", Icons.Default.Settings) { section = "Bluetooth" },
                    TileSpec("Рабочее время", Icons.Default.LocationOn) { section = "Рабочее время" }
                )
            )
        }
    }
}

/** Окно «Еще»: кнопка «Профиль» и четыре строки; профиль со всеми его строками — в отдельном окне. */
@Composable
fun MoreScreen(profile: UserProfile, prefs: Prefs, onLogout: () -> Unit) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val current = section
    SideEffect { TabRoot.set(3, section == null) }
    when {
        current == "Профиль" -> ProfileScreen(profile, prefs, onLogout) { section = null }
        current == "Настройки" -> SettingsScreen(prefs) { section = null }
        current == "Обновление" -> UpdateScreen { section = null }
        current == "Лицензия" -> LicenseScreen(profile) { section = null }
        current == "О приложении" -> AboutAppScreen { section = null }
        current == "О системе" -> AboutSystemScreen { section = null }
        current == "Отправить приложение" -> ShareAppScreen { section = null }
        current != null -> SectionScreen(current) { section = null }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Еще", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            ProfileCard(profile.firstName.ifBlank { "Профиль" }) { section = "Профиль" }
            Spacer(Modifier.height(16.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                MoreRow(Icons.Default.Settings, "Настройки", "Сканер, печать, тема, Google Диск и др.") { section = "Настройки" }
                HorizontalDivider(Modifier.padding(start = 68.dp, end = 13.dp))
                MoreRow(
                    Icons.Default.Refresh, "Обновление",
                    Updater.latestNewer?.let { "Доступна версия ${it.version}" } ?: "Проверка и установка новой версии"
                ) { section = "Обновление" }
                HorizontalDivider(Modifier.padding(start = 68.dp, end = 13.dp))
                MoreRow(Icons.Default.Lock, "Лицензия", "Условия использования, компоненты") { section = "Лицензия" }
                HorizontalDivider(Modifier.padding(start = 68.dp, end = 13.dp))
                MoreRow(Icons.Default.Info, "О приложении", "Версия, возможности, что нового") { section = "О приложении" }
                HorizontalDivider(Modifier.padding(start = 68.dp, end = 13.dp))
                MoreRow(Icons.Default.Build, "О системе", "Телефон, память, сеть, батарея") { section = "О системе" }
            }
            Spacer(Modifier.height(12.dp))
            WideOutlinedButton(Icons.Default.Share, "Отправить приложение") { section = "Отправить приложение" }
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
fun ReceiveScreen(prefs: Prefs, receiver: String) {
    var mode by NavVar({ TabNav.work }, { TabNav.work = it })
    var viewReceipt by rememberSaveable { mutableStateOf<String?>(null) }
    val admin = Session.isAdmin
    val current = mode
    val ctx = LocalContext.current
    SideEffect { TabRoot.set(1, mode == null && viewReceipt == null) }
    var pickMode by remember { mutableStateOf("auto") }
    var deleteJob by remember { mutableStateOf<AutoJob?>(null) }
    var showFiles by rememberSaveable { mutableStateOf(false) }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        val m = pickMode
        // один файл — сразу открываем; несколько — создаём партии, открывать их можно из списка ниже
        if (uris.isNotEmpty()) thread { uris.forEach { Incoming.accept(ctx, it, m, open = uris.size == 1) } }
    }

    // «Снимок» в Автоприёме: сканер документа (до 30 листов) → PDF → Google Диск (очередь) → распознавание → автосохранение
    val autoScanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val pdf0 = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf?.uri
            if (pdf0 != null) thread {
                // кривой скан (сшитые листы, угол под степлером) выравнивается; ровный остаётся как есть
                val pdf = ScanFix.fix(ctx, pdf0)
                val db = ProductDb.get(ctx)
                val inv = Media.importUri(ctx, pdf, db, "IN")
                Media.uploadPending(ctx, db)
                Incoming.accept(ctx, pdf, "auto", open = true, driveName = inv ?: "")
            }
        }
    }
    val ownAutoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val pdf = if (res.resultCode == Activity.RESULT_OK) OwnScan.pdfFrom(res.data) else null
        if (pdf != null) thread {
            val db = ProductDb.get(ctx)
            val inv = Media.importUri(ctx, pdf, db, "IN")
            Media.uploadPending(ctx, db)
            Incoming.accept(ctx, pdf, "auto", open = true, driveName = inv ?: "")
        }
    }
    val startAutoScan: () -> Unit = {
        val act = findActivity(ctx)
        if (act != null && OwnScan.enabled(ctx)) {
            ownAutoLauncher.launch(OwnScan.intent(act))
        } else if (act != null) {
            val opts = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(30)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
                .addOnSuccessListener { sender -> autoScanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener {
                    Toast.makeText(ctx, "Сканер недоступен: " + (it.message ?: ""), Toast.LENGTH_LONG).show()
                }
        }
    }

    val rid = viewReceipt
    if (rid != null) {
        ReceiptCardScreen(rid) { viewReceipt = null }
        return
    }
    if (current == "Ручной") {
        ManualReceiveScreen(prefs, receiver) { mode = null }
        return
    }
    if (current == "По наименованию") {
        NameReceiveHub(onBack = { mode = null })
        return
    }
    if (current == "Приём B-Ware") {
        BWareReceiveScreen(receiver) { mode = null }
        return
    }
    if (current == "Инвентарь B-Ware") {
        BWareInventoryScreen(receiver) { mode = null }
        return
    }
    if (current == "Фото по QR") {
        BWarePhotoLookupScreen(prefs) { mode = null }
        return
    }
    if (current == "Итог приёма") {
        ReceiveTotalScreen { mode = null }
        return
    }
    if (current == "Отправка") {
        SendHubScreen { mode = null }
        return
    }
    if (current == "Выгрузка") {
        UnloadScreen { mode = null }
        return
    }
    if (current == "Автоприём" && showFiles) {
        // отдельное окно «Файлы / накладные»: все файлы, проблемные сверху
        FilesScreen(onBack = { showFiles = false }, part = "list")
        return
    }
    if (current == "Автоприём") {
        FilesScreen(
            onBack = { mode = null },
            title = "Автоприём",
            part = "main",
            onOpenFiles = { showFiles = true },
            onPickFiles = {
                pickMode = "auto"
                pdfPicker.launch(
                    arrayOf(
                        "application/pdf", "image/jpeg", "image/png",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    )
                )
            },
            onScan = startAutoScan
        )
        return
    }
    if (current != null) {
        SectionScreen(current) { mode = null }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Работа", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        val autoCount = AutoReceiveHolder.jobs.count { !it.nameMode && !it.saved }
        val nameCount = AutoReceiveHolder.jobs.count { it.nameMode && !it.saved }
        TileGrid(
            listOf(
                TileSpec("Ручной", Icons.Default.Edit) { mode = "Ручной" },
                TileSpec("По наименованию", Icons.Default.Search, nameCount) { mode = "По наименованию" },
                TileSpec("Автоприём", Icons.Default.Refresh, autoCount) { mode = "Автоприём" },
                TileSpec("Итог приёма", Icons.Default.Check) { mode = "Итог приёма" },
                TileSpec("Приём B-Ware", Icons.Default.Add) { mode = "Приём B-Ware" },
                TileSpec("Инвентаризация", Icons.Default.Done) { mode = "Инвентаризация" },
                TileSpec("Инвентарь B-Ware", Icons.Default.Warning) { mode = "Инвентарь B-Ware" },
                TileSpec("Фото по QR", Icons.Default.Info) { mode = "Фото по QR" },
                TileSpec("Отправка", Icons.AutoMirrored.Filled.Send) { mode = "Отправка" },
                TileSpec("Выгрузка", Icons.Default.ShoppingCart) { mode = "Выгрузка" }
            )
        )

        if (!admin) {
            ReceivedGoodsList { viewReceipt = it }
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

/** Для сотрудников: принятый товар обычным списком, только просмотр. */
@Composable
private fun ReceivedGoodsList(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val sync = remember { SyncState() }
    var list by remember { mutableStateOf(emptyList<Receipt>()) }
    LaunchedEffect(Unit) {
        try { Cloud.runReceipts(db, sync, false) } catch (e: Exception) { }
    }
    LaunchedEffect(sync.version, Media.version) {
        list = withContext(Dispatchers.IO) { db.allReceipts() }
    }
    Spacer(Modifier.height(8.dp))
    Text("Принятый товар", style = MaterialTheme.typography.titleMedium)
    Text(
        "Приём товара доступен всем. Когда приёмка записана в базу, менять её может только администратор.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    if (list.isEmpty()) Text("Приёмов пока нет", style = MaterialTheme.typography.bodyMedium)
    list.take(60).forEach { r ->
        Column(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 8.dp)) {
            val who = listOf(
                r.sender.takeIf { it.isNotBlank() },
                r.orderNo.takeIf { it.isNotBlank() }?.let { "заказ № $it" }
            ).filterNotNull().joinToString(" · ")
            Text(fmtTime(r.receivedAt) + if (who.isNotEmpty()) " · $who" else "", fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Text(
                r.items.joinToString(", ") { "${it.name} × ${it.qty}" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        HorizontalDivider()
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
    val excelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) thread { uris.forEach { Incoming.accept(ctx, it, "name", open = uris.size == 1) } }
    }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val pdf0 = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf?.uri
            if (pdf0 != null) thread {
                // кривой скан (сшитые листы, угол под степлером) выравнивается; ровный остаётся как есть
                val pdf = ScanFix.fix(ctx, pdf0)
                // скан сразу ставится в очередь на Google Диск (нет сети — уйдёт, как только она появится)
                val db = ProductDb.get(ctx)
                val inv = Media.importUri(ctx, pdf, db, "IN")
                Media.uploadPending(ctx, db)
                Incoming.accept(ctx, pdf, "name", open = true, driveName = inv ?: "")
            }
        }
    }

    val ownScanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val pdf = if (res.resultCode == Activity.RESULT_OK) OwnScan.pdfFrom(res.data) else null
        if (pdf != null) thread {
            val db = ProductDb.get(ctx)
            val inv = Media.importUri(ctx, pdf, db, "IN")
            Media.uploadPending(ctx, db)
            Incoming.accept(ctx, pdf, "name", open = true, driveName = inv ?: "")
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
                    excelPicker.launch(arrayOf(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel.sheet.macroEnabled.12"
                    ))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Excel") }
            FilledTonalButton(
                onClick = {
                    val act = findActivity(ctx)
                    if (act == null) return@FilledTonalButton
                    if (OwnScan.enabled(ctx)) {
                        ownScanLauncher.launch(OwnScan.intent(act))
                        return@FilledTonalButton
                    }
                    val opts = GmsDocumentScannerOptions.Builder()
                        .setGalleryImportAllowed(false)
                        .setPageLimit(30)
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
