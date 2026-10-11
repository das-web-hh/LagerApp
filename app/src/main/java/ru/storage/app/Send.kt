package ru.storage.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private val RU = Locale("ru")
private fun fmtDay(ms: Long): String = SimpleDateFormat("d MMMM yyyy, EEEE", RU).format(Date(ms))
private fun fmtHm(ms: Long): String = SimpleDateFormat("HH:mm", RU).format(Date(ms))

private fun sendActivity(ctx: Context): Activity? {
    var c: Context? = ctx
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

// ======================================================================
//  Окно «Отправка»: три кнопки
// ======================================================================

@Composable
fun SendHubScreen(onBack: () -> Unit) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    when (section) {
        "Post paket" -> PostPaketScreen { section = null }
        "DHL Postwagen" -> PostwagenScreen { section = null }
        "Spedition" -> SectionScreen("Spedition") { section = null }
        else -> {
            BackHandler(onBack = onBack)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Отправка", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
                ) {
                    MoreRow(Icons.Default.Email, "Post paket", "Этикетки посылок DHL, сканер, список") { section = "Post paket" }
                    HorizontalDivider(Modifier.padding(start = 84.dp, end = 16.dp))
                    MoreRow(Icons.Default.ShoppingCart, "DHL Postwagen", "Отправлено и получено, история") { section = "DHL Postwagen" }
                    HorizontalDivider(Modifier.padding(start = 84.dp, end = 16.dp))
                    MoreRow(Icons.Default.LocationOn, "Spedition", "Отправка грузов экспедицией") { section = "Spedition" }
                }
            }
        }
    }
}

// ======================================================================
//  Post paket
// ======================================================================

private fun statusText(s: String): String = when (s) {
    "new", "proc" -> "анализ этикетки…"
    "check" -> "номер не найден — проверьте"
    "error" -> "не удалось распознать"
    else -> ""
}

private fun importScanPage(app: Context, uri: Uri, mdb: ProductDb): String? {
    return try {
        val tmp = File(app.cacheDir, "scan_" + System.nanoTime() + ".jpg")
        val ins = app.contentResolver.openInputStream(uri) ?: return null
        ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val name = Media.importFile(app, tmp, mdb, "DH")
        tmp.delete()
        name
    } catch (e: Exception) {
        null
    }
}

/** Снимки с этикеткой → новая посылка → анализ в фоне → база, Firebase, Google Диск. */
private fun createPaketFromScans(ctx: Context, uris: List<Uri>) {
    val app = ctx.applicationContext
    val mdb = ProductDb.get(app)
    val sdb = SendDb.get(app)
    val names = uris.mapNotNull { importScanPage(app, it, mdb) }
    if (names.isEmpty()) return
    val now = System.currentTimeMillis()
    val p = Paket(
        id = newSendId(), createdAt = now, photos = names, status = "new",
        author = Session.displayName, updatedAt = now
    )
    SendCloud.savePaket(sdb, p)
    LabelProcessor.enqueue(app, p.id)
    Media.uploadPending(app, mdb)
}

@Composable
fun PostPaketScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sdb = remember { SendDb.get(ctx) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var text by rememberSaveable { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Paket>>(emptyList()) }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { SendCloud.pull(sdb) }
    LaunchedEffect(SendState.version, Media.version) {
        withContext(Dispatchers.IO) {
            list = sdb.allPakets()
            pending = ProductDb.get(ctx).pendingUploads().toSet()
        }
    }

    val opened = openId
    if (opened != null) {
        PaketDetailScreen(opened) { openId = null }
        return
    }
    BackHandler(onBack = onBack)

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages
                ?.mapNotNull { it.imageUri } ?: emptyList()
            if (uris.isNotEmpty()) thread { createPaketFromScans(ctx, uris) }
        }
    }

    fun startScan() {
        val act = sendActivity(ctx) ?: return
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(10)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
            .addOnSuccessListener { sender -> scanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                Toast.makeText(ctx, "Сканер недоступен: " + (it.message ?: ""), Toast.LENGTH_LONG).show()
            }
    }

    fun addManual() {
        val t = text.trim()
        if (t.isEmpty()) return
        val compactT = t.replace(" ", "")
        val looksTracking = compactT.length >= 10 && compactT.all { it.isLetterOrDigit() }
        val now = System.currentTimeMillis()
        val p = Paket(
            id = newSendId(), createdAt = now,
            tracking = if (looksTracking) compactT.uppercase() else "",
            note = if (looksTracking) "" else t,
            status = "manual", author = Session.displayName, updatedAt = now
        )
        text = ""
        scope.launch(Dispatchers.IO) { SendCloud.savePaket(sdb, p) }
    }

    val q = text.trim().lowercase()
    val shown = if (q.isEmpty()) list else list.filter { p ->
        listOf(p.tracking, p.recipient, p.street, p.zip, p.city, p.sender, p.note, p.product)
            .any { it.lowercase().contains(q) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("Post paket", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Номер посылки или заметка") },
                trailingIcon = {
                    IconButton(onClick = { addManual() }, enabled = text.isNotBlank()) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить")
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (list.isEmpty()) "Посылок пока нет.\nНажмите жёлтую кнопку и снимите этикетку."
                        else "Ничего не найдено",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.id }) { p ->
                        PaketRow(p, p.photos.any { it in pending }) { openId = p.id }
                    }
                }
            }
        }
        ScanFab(onClick = { startScan() })
    }
}

@Composable
private fun PaketRow(p: Paket, notUploaded: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val who = listOf(p.recipient, listOf(p.zip, p.city).filter { it.isNotBlank() }.joinToString(" "))
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (who.isNotEmpty()) {
                    Text(who, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    fmtTime(p.createdAt) + (if (p.product.isNotBlank()) " · " + p.product else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (p.services.isNotBlank()) {
                    Text(
                        p.services, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.tertiary
                    )
                }
                val st = statusText(p.status)
                if (st.isNotEmpty()) {
                    Text(
                        st, style = MaterialTheme.typography.bodySmall,
                        color = if (p.status == "error" || p.status == "check") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (p.photos.isNotEmpty()) Text("📷 ${p.photos.size}", style = MaterialTheme.typography.bodySmall)
                if (notUploaded) Text("⏳ Диск", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Круглая жёлтая кнопка сканера: её можно перетащить в любое место, она всегда плавает над списком. */
@Composable
private fun BoxScope.ScanFab(onClick: () -> Unit) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("send_ui", Context.MODE_PRIVATE) }
    val density = LocalDensity.current
    val size = 64.dp
    BoxWithConstraints(Modifier.matchParentSize()) {
        val maxX = with(density) { (maxWidth - size).toPx() }
        val maxY = with(density) { (maxHeight - size).toPx() }
        val pad = with(density) { 20.dp.toPx() }
        var pos by remember {
            mutableStateOf(
                if (sp.contains("fab_x")) Offset(sp.getFloat("fab_x", 0f), sp.getFloat("fab_y", 0f)) else null
            )
        }
        val base = pos ?: Offset(maxX - pad, maxY - pad)
        val cur = Offset(base.x.coerceIn(0f, maxX), base.y.coerceIn(0f, maxY))
        Box(
            Modifier
                .offset { IntOffset(cur.x.roundToInt(), cur.y.roundToInt()) }
                .size(size)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(Color(0xFFFFC107))
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = {
                            pos?.let { sp.edit().putFloat("fab_x", it.x).putFloat("fab_y", it.y).apply() }
                        }
                    ) { change, drag ->
                        change.consume()
                        val b = pos ?: Offset(maxX - pad, maxY - pad)
                        pos = Offset((b.x + drag.x).coerceIn(0f, maxX), (b.y + drag.y).coerceIn(0f, maxY))
                    }
                }
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            ScannerGlyph()
        }
    }
}

/** Иконка «Сканер» как в списке операций: два ряда чёрных квадратиков и оранжевая линия луча между ними. */
@Composable
private fun ScannerGlyph() {
    Canvas(Modifier.size(32.dp)) {
        val sq = 8.dp.toPx()
        val gap = 4.dp.toPx()
        val x0 = (size.width - (2 * sq + gap)) / 2f
        val topY = 3.dp.toPx()
        val botY = size.height - 3.dp.toPx() - sq
        val dark = Color(0xFF212121)
        fun box(x: Float, y: Float) = drawRect(dark, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(sq, sq))
        box(x0, topY); box(x0 + sq + gap, topY)
        box(x0, botY); box(x0 + sq + gap, botY)
        val sw = 3.dp.toPx()
        drawLine(
            Color(0xFFE65100), Offset(1.dp.toPx(), size.height / 2f), Offset(size.width - 1.dp.toPx(), size.height / 2f),
            strokeWidth = sw, cap = StrokeCap.Round
        )
    }
}

// ---------- карточка посылки ----------

@Composable
private fun InfoLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PhotoPage(name: String, onRemove: (() -> Unit)?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var done by remember(name) { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(null, name, Media.version) {
        value = if (Media.isImage(name)) {
            Media.load(ctx, name)?.let { withContext(Dispatchers.IO) { Media.thumb(it, 900) } }
        } else null
        done = true
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), null,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { scope.launch { if (Media.load(ctx, name) != null) Media.open(ctx, name) } },
                contentScale = ContentScale.FillWidth
            )
        } else if (!done) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Text(
                if (Media.isImage(name)) "Файл недоступен: на телефоне его уже нет, а Google Диск не настроен или нет сети."
                else "📄 " + Media.shownName(name),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (onRemove != null) TextButton(onClick = onRemove) { Text("Убрать этот снимок") }
    }
}

@Composable
fun PaketDetailScreen(id: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val sdb = remember { SendDb.get(ctx) }
    val mdb = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf<Paket?>(null) }
    var edit by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id, SendState.version) { p = withContext(Dispatchers.IO) { sdb.getPaket(id) } }
    val cur = p
    if (cur == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Посылка не найдена") }
        return
    }

    fun update(n: Paket) {
        scope.launch(Dispatchers.IO) { SendCloud.savePaket(sdb, n) }
    }

    val fields = listOf(
        Triple("tracking", "Номер посылки", cur.tracking),
        Triple("product", "Продукт", cur.product),
        Triple("recipient", "Получатель", cur.recipient),
        Triple("street", "Улица", cur.street),
        Triple("zip", "Индекс", cur.zip),
        Triple("city", "Город", cur.city),
        Triple("country", "Страна", cur.country),
        Triple("sender", "Отправитель", cur.sender),
        Triple("weight", "Вес, кг", cur.weight),
        Triple("routing", "Leitcode / Routingcode", cur.routing),
        Triple("billing", "Номер расчёта (Abrechnungsnr)", cur.billing),
        Triple("reference", "Референс отправителя", cur.reference),
        Triple("services", "Отметки и услуги", cur.services),
        Triple("note", "Заметка", cur.note)
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(cur.title(), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val st = statusText(cur.status)
        if (st.isNotEmpty()) Text(st, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                InfoLine("Дата и время", fmtDay(cur.createdAt) + ", " + fmtHm(cur.createdAt))
                InfoLine("Кто добавил", cur.author)
                fields.forEach { (key, label, value) -> InfoLine(label, value) { edit = key } }
                if (cur.rawText.isNotBlank()) {
                    TextButton(onClick = { showRaw = !showRaw }) {
                        Text(if (showRaw) "Скрыть распознанный текст" else "Показать распознанный текст")
                    }
                    if (showRaw) Text(cur.rawText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Нажмите на поле, чтобы исправить его.", style = MaterialTheme.typography.bodySmall)
        if (cur.tracking.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        try {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("tracking", cur.tracking))
                            Toast.makeText(ctx, "Номер скопирован", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Копировать номер") }
                FilledTonalButton(
                    onClick = {
                        try {
                            val url = "https://www.dhl.de/de/privatkunden/pakete-empfangen/verfolgen.html?piececode=" +
                                Uri.encode(cur.tracking)
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Отследить") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Снимки этикетки", style = MaterialTheme.typography.titleMedium)
        Text(
            "На телефоне снимки лежат двое суток, потом остаются на Google Диске и открываются оттуда.",
            style = MaterialTheme.typography.bodySmall
        )
        cur.photos.forEach { n ->
            PhotoPage(n) {
                scope.launch(Dispatchers.IO) {
                    Media.discard(ctx, mdb, listOf(n))
                    SendCloud.savePaket(sdb, cur.copy(photos = cur.photos - n))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        MediaButtons("DH", false) { n ->
            scope.launch(Dispatchers.IO) {
                val fresh = sdb.getPaket(id) ?: return@launch
                SendCloud.savePaket(sdb, fresh.copy(photos = fresh.photos + n))
                Media.uploadPending(ctx, mdb)
            }
        }

        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    SendCloud.savePaketLocal(sdb, cur.copy(status = "new", rawText = ""))
                    LabelProcessor.enqueue(ctx, cur.id)
                }
            },
            enabled = cur.photos.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Распознать этикетку заново") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Удалить посылку", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }

    edit?.let { key ->
        val label = fields.first { it.first == key }.second
        var v by remember(key) { mutableStateOf(fields.first { it.first == key }.third) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(label) },
            text = { OutlinedTextField(value = v, onValueChange = { v = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                Button(onClick = {
                    val t = v.trim()
                    update(
                        when (key) {
                            "tracking" -> cur.copy(tracking = t.replace(" ", "").uppercase())
                            "product" -> cur.copy(product = t)
                            "recipient" -> cur.copy(recipient = t)
                            "street" -> cur.copy(street = t)
                            "zip" -> cur.copy(zip = t)
                            "city" -> cur.copy(city = t)
                            "country" -> cur.copy(country = t)
                            "sender" -> cur.copy(sender = t)
                            "weight" -> cur.copy(weight = t.replace(',', '.'))
                            "routing" -> cur.copy(routing = t)
                            "billing" -> cur.copy(billing = t)
                            "reference" -> cur.copy(reference = t)
                            "services" -> cur.copy(services = t)
                            else -> cur.copy(note = t)
                        }.let { n ->
                            if ((n.status == "check" || n.status == "error") && n.tracking.isNotBlank()) n.copy(status = "done") else n
                        }
                    )
                    edit = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Отмена") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить посылку?") },
            text = { Text("Запись и её снимки (в том числе на Google Диске) будут удалены.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch(Dispatchers.IO) {
                        Media.discard(ctx, mdb, cur.photos)
                        SendCloud.deletePaket(sdb, cur.id)
                    }
                    onClose()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}

// ======================================================================
//  DHL Postwagen
// ======================================================================

@Composable
fun PostwagenScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sdb = remember { SendDb.get(ctx) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<WagenOp>>(emptyList()) }

    LaunchedEffect(Unit) { SendCloud.pull(sdb) }
    LaunchedEffect(SendState.version) { list = withContext(Dispatchers.IO) { sdb.allWagen() } }

    val opened = openId
    when {
        adding -> { WagenAddScreen { adding = false }; return }
        opened != null -> { WagenDetailScreen(opened) { openId = null }; return }
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("DHL Postwagen", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Добавить", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        val dayStart = remember(list) {
            val c = java.util.Calendar.getInstance()
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            c.timeInMillis
        }
        val todayOps = list.filter { it.createdAt >= dayStart }
        if (todayOps.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Сегодня", style = MaterialTheme.typography.labelMedium)
                        Text("операций: ${todayOps.size}", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Отправлено ${todayOps.sumOf { it.sent }}", fontWeight = FontWeight.SemiBold)
                        Text("Получено ${todayOps.sumOf { it.received }}", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Text("История", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Операций пока нет") }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { w ->
                    Card(
                        onClick = { openId = w.id },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(fmtTime(w.createdAt), fontWeight = FontWeight.Medium)
                                if (w.note.isNotBlank()) {
                                    Text(w.note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Отправлено ${w.sent}", style = MaterialTheme.typography.bodyMedium)
                                Text("Получено ${w.received}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Большая строка-счётчик: тап = +1 (вибрация и голос), число справа — барабан 0…9999, «−» убавляет на 1. */
@Composable
private fun BigCounter(
    title: String,
    value: Int,
    key: Any,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
    onPick: () -> Unit
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = {
            onChange((value + 1).coerceAtMost(9999))
            Haptics.pulse(ctx, Haptics.TAP)
            CountAnnouncer.tap(ctx, key)
        },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = scheme.onPrimaryContainer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        if (value > 0) {
                            onChange(value - 1)
                            Haptics.pulse(ctx, Haptics.TAP)
                            CountAnnouncer.picked(ctx, (value - 1).toString(), (value - 1).toString())
                        }
                    },
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(48.dp)
                ) { Text("−", fontSize = 24.sp) }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp))
                        .background(scheme.surface)
                        .clickable { onPick() }
                        .defaultMinSize(minWidth = 120.dp)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(value.toString(), fontSize = 52.sp, fontWeight = FontWeight.Bold, color = scheme.primary)
                }
            }
        }
    }
}

@Composable
fun WagenAddScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val sdb = remember { SendDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var sent by rememberSaveable { mutableIntStateOf(0) }
    var received by rememberSaveable { mutableIntStateOf(0) }
    var note by rememberSaveable { mutableStateOf("") }
    var picker by remember { mutableStateOf<String?>(null) }
    val keySent = remember { Any() }
    val keyRecv = remember { Any() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Новая операция", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            BigCounter("Отправлено", sent, keySent, Modifier.weight(1f), { sent = it }) { picker = "sent" }
            Spacer(Modifier.height(12.dp))
            BigCounter("Получено", received, keyRecv, Modifier.weight(1f), { received = it }) { picker = "recv" }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note, onValueChange = { note = it }, singleLine = true,
                placeholder = { Text("Заметка (необязательно)") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    val op = WagenOp(
                        id = newSendId(), createdAt = now, sent = sent, received = received,
                        note = note.trim(), author = Session.displayName, updatedAt = now
                    )
                    scope.launch(Dispatchers.IO) { SendCloud.saveWagen(sdb, op) }
                    onClose()
                },
                enabled = sent > 0 || received > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Сохранить", style = MaterialTheme.typography.titleMedium) }
        }
        CountOverlay()
    }

    picker?.let { which ->
        val isSent = which == "sent"
        CountPickerDialog(
            title = if (isSent) "Отправлено" else "Получено",
            initial = if (isSent) sent else received,
            onApply = { v ->
                if (isSent) sent = v else received = v
                CountAnnouncer.picked(ctx, v.toString(), v.toString())
                picker = null
            },
            onDismiss = { picker = null }
        )
    }
}

@Composable
fun WagenDetailScreen(id: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val sdb = remember { SendDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var w by remember { mutableStateOf<WagenOp?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id, SendState.version) { w = withContext(Dispatchers.IO) { sdb.getWagen(id) } }
    val cur = w
    if (cur == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Операция не найдена") }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Операция Postwagen", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                InfoLine("Дата", fmtDay(cur.createdAt))
                InfoLine("Время", SimpleDateFormat("HH:mm:ss", RU).format(Date(cur.createdAt)))
                InfoLine("Отправлено", cur.sent.toString())
                InfoLine("Получено", cur.received.toString())
                val diff = cur.received - cur.sent
                InfoLine("Разница (получено − отправлено)", (if (diff > 0) "+" else "") + diff)
                InfoLine("Кто сохранил", cur.author)
                InfoLine("Заметка", cur.note)
                InfoLine("Код операции", cur.id)
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Удалить операцию", color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить операцию?") },
            text = { Text("Запись исчезнет из истории на всех телефонах.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch(Dispatchers.IO) { SendCloud.deleteWagen(sdb, cur.id) }
                    onClose()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}
