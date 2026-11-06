package ru.storage.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * «Партии»: список товаров с количеством, который собирает один человек, а другой по нему собирает заказ в окне «Сборка».
 * Партия создаётся сканером этикеток (как «Скан списка» в каталоге, но товары идут не в каталог, а в список) или из Excel.
 * Отправка: файл партии (JSON) и страница HTML кладутся на Google Диск; получатель получает ссылку / QR / файл HTML.
 */
class BatchRow(name: String, qty: String, val barcode: String = "", val article: String = "") {
    var name by mutableStateOf(name)
    var qty by mutableStateOf(qty)
}

class Batch(val id: String, title: String, val created: Long) {
    var title by mutableStateOf(title)
    val rows = mutableStateListOf<BatchRow>()
    /** ссылка последней отправки ("" — ещё не отправлялась) */
    var link by mutableStateOf("")
    /** короткий код для ссылки и имени файла на Диске */
    val code: String get() = id.replace("-", "").take(12)
}

object BatchStore {
    val batches = mutableStateListOf<Batch>()
    var openId by mutableStateOf<String?>(null)
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "batches.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val b = Batch(o.optString("id"), o.optString("t"), o.optLong("at"))
                b.link = o.optString("l")
                val rs = o.optJSONArray("rows")
                if (rs != null) for (k in 0 until rs.length()) {
                    val r = rs.optJSONObject(k) ?: continue
                    b.rows.add(BatchRow(r.optString("n"), r.optString("q"), r.optString("b"), r.optString("a")))
                }
                batches.add(b)
            }
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun save(ctx: Context) {
        try {
            val a = JSONArray()
            batches.toList().forEach { b ->
                val rs = JSONArray()
                b.rows.toList().forEach { r ->
                    rs.put(JSONObject().put("n", r.name).put("q", r.qty).put("b", r.barcode).put("a", r.article))
                }
                a.put(JSONObject().put("id", b.id).put("t", b.title).put("at", b.created).put("l", b.link).put("rows", rs))
            }
            file(ctx).writeText(a.toString())
        } catch (e: Exception) {
        }
    }

    fun create(ctx: Context, title: String, rows: List<BatchRow>): Batch {
        val now = System.currentTimeMillis()
        val b = Batch(UUID.randomUUID().toString(), title.ifBlank { "Партия " + fmtTime(now) }, now)
        b.rows.addAll(rows)
        batches.add(0, b)
        save(ctx)
        return b
    }

    fun remove(ctx: Context, b: Batch) {
        batches.remove(b)
        if (openId == b.id) openId = null
        save(ctx)
    }
}

/** Количество по строке: пусто или ноль → 1. */
internal fun batchQty(s: String): String {
    val d = parseQ(s)
    return if (d > 0.0) fmtQ(d) else "1"
}

// ───────────────────────────── файл партии, HTML, отправка ─────────────────────────────

object BatchShare {
    private const val TAG = "#lager="

    /** Адрес страницы «Сборка» на GitHub Pages (файл docs/index.html в репозитории). Там камера в браузере работает. */
    private const val WEB = "https://das-web-hh.github.io/Lager/"

    fun json(b: Batch): String {
        val rs = JSONArray()
        b.rows.toList().forEach { r ->
            rs.put(JSONObject().put("n", r.name.trim()).put("q", batchQty(r.qty)).put("b", r.barcode).put("a", r.article))
        }
        return JSONObject().put("v", 1).put("code", b.code).put("title", b.title).put("at", b.created).put("rows", rs).toString()
    }

    /**
     * Файл партии — мини-приложение «Сборка» одним HTML-файлом (шаблон assets/batch_app.html): список партии внутри,
     * работает в любом браузере без интернета и без установки: сканер (в том числе Bluetooth), камера, поиск по названию
     * и артикулу, счёт, печать, переход к следующей партии с нуля. Счёт хранится в самом браузере.
     * Те же данные внутри файла читает и приложение Warehouse (открыть файл через Warehouse).
     */
    fun html(ctx: Context, b: Batch, link: String = ""): String {
        val tpl = ctx.applicationContext.assets.open("batch_app.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        // «<» в данных экранируется, чтобы текст товара не мог закрыть тег script
        val data = json(b).replace("<", "\\u003c")
        return tpl.replace("__TITLE__", bwEsc(b.title)).replace("__LINK__", bwEsc(link)).replace("__DATA__", data)
    }

    /**
     * Создаёт страницу-мини-приложение (HTML со списком внутри) и кладёт её на Google Диск, как и список (BATCH_код.json) для приложения.
     * Ссылка и QR ведут на скрипт Диска (действие app), он отдаёт эту страницу в браузер — iPhone и Android, без установки.
     * Повторная отправка заменяет файлы. Нужен обновлённый скрипт Диска (google-script/Code.gs). Вызывать не в главном потоке.
     */
    fun publish(app: Context, b: Batch): String {
        if (!DriveCfg.ready(app)) throw RuntimeException("Google Диск не настроен (Еще → Настройки → Google Диск)")
        val code = b.code
        val base = DriveCfg.url(app)
        // «#lager=код» в конце — по нему приложение Lager узнаёт партию при сканировании QR
        // Страница лежит на GitHub Pages (камера работает), данные партии она берёт у скрипта Диска по коду (action=batch)
        val link = WEB + "?c=" + code + "&s=" + java.net.URLEncoder.encode(base, "UTF-8") + TAG + code
        val data = json(b).toByteArray(Charsets.UTF_8)
        val page = html(app, b, link).toByteArray(Charsets.UTF_8)
        for (n in listOf("BATCH_$code.json", "BATCHPAGE_$code.html")) try { DriveApi.delete(app, n) } catch (e: Exception) { }
        DriveApi.uploadBytes(app, "BATCH_$code.json", "application/json", data)
        DriveApi.uploadBytes(app, "BATCHPAGE_$code.html", "text/html", page)
        b.link = link
        return link
    }

    /** Код партии из ссылки / текста QR; null — это не ссылка на партию. */
    fun codeFrom(text: String): String? {
        val i = text.indexOf(TAG)
        if (i < 0) return null
        val c = text.substring(i + TAG.length).takeWhile { it.isLetterOrDigit() }
        return c.takeIf { it.length in 6..40 }
    }

    /** Скачивает партию с Диска по коду. Вызывать не в главном потоке. */
    fun fetch(app: Context, code: String): JSONObject {
        if (!DriveCfg.ready(app)) throw RuntimeException("Google Диск не настроен (Еще → Настройки → Google Диск)")
        val bytes = DriveApi.download(app, "BATCH_$code.json") ?: throw RuntimeException("Партия не найдена на Диске")
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun qrBitmap(text: String, size: Int = 800): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun text(b: Batch, link: String): String =
        if (link.isBlank()) b.title else b.title + "\n\nОткрыть список (сканировать, считать по коробкам, печатать):\n" + link

    /**
     * «Поделиться»: уходит ссылка на мини-приложение (получатель открывает её в браузере, ничего не устанавливая).
     * Если на Диск загрузить не удалось (ссылки нет) — уходит сам HTML-файл. whatsapp = true — сразу в WhatsApp (если установлен).
     */
    fun share(ctx: Context, b: Batch, link: String, whatsapp: Boolean) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, text(b, link))
                putExtra(Intent.EXTRA_SUBJECT, b.title)
            }
            if (link.isBlank()) {
                val dir = File(ctx.cacheDir, "export").also { it.mkdirs() }
                val safe = b.title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').ifBlank { "batch" }.take(40)
                val f = File(dir, "Партия_$safe.html")
                f.writeText(html(ctx, b, ""), Charsets.UTF_8)
                i.type = "text/html"
                i.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else i.type = "text/plain"
            if (whatsapp) {
                for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                    try {
                        ctx.startActivity(Intent(i).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return
                    } catch (e: ActivityNotFoundException) {
                    }
                }
                Toast.makeText(ctx, "WhatsApp не найден — выберите другое приложение", Toast.LENGTH_SHORT).show()
            }
            ctx.startActivity(Intent.createChooser(i, "Отправить партию").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Не удалось отправить: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}

// ───────────────────────────── экраны ─────────────────────────────

private fun displayName(ctx: Context, uri: Uri): String = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }?.substringBeforeLast('.').orEmpty()
} catch (e: Exception) {
    ""
}

/** Окно «Партии»: сверху «Добавить», ниже созданные партии. */
@Composable
fun BatchesScreen(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { BatchStore.load(ctx) }
    var addDlg by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var delBatch by remember { mutableStateOf<Batch?>(null) }
    /** партия, в которую докидываем товары; null — создаём новую */
    var target by remember { mutableStateOf<Batch?>(null) }

    fun accept(rows: List<BatchRow>, title: String) {
        if (rows.isEmpty()) {
            Toast.makeText(ctx, "Товаров не найдено", Toast.LENGTH_LONG).show()
            return
        }
        val t = target
        if (t != null) {
            rows.forEach { n -> if (t.rows.none { it.barcode.isNotBlank() && it.barcode == n.barcode || it.name.trim().equals(n.name.trim(), true) }) t.rows.add(n) }
            BatchStore.save(ctx)
            BatchStore.openId = t.id
        } else {
            BatchStore.openId = BatchStore.create(ctx, title, rows).id
        }
        target = null
    }

    // таблица, в которой столбцы не определились сами: показываем окно выбора
    var colsData by remember { mutableStateOf<List<List<String>>?>(null) }
    var colsTitle by remember { mutableStateOf("") }
    var colsPick by remember { mutableStateOf<BatchPick?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val data = withContext(Dispatchers.IO) { try { Xlsx.read(ctx, uri) } catch (e: Exception) { emptyList() } }
                    .filter { r -> r.any { it.isNotBlank() } }
                val title = displayName(ctx, uri)
                if (data.isEmpty()) {
                    Toast.makeText(ctx, "Не удалось прочитать файл (нужен Excel .xlsx)", Toast.LENGTH_LONG).show()
                    target = null
                } else {
                    val pick = BatchColumns.detect(data)
                    val auto = if (pick.name >= 0) BatchColumns.parse(data, pick) else emptyList()
                    if (auto.isNotEmpty()) {
                        accept(auto, title)
                    } else {
                        colsData = data; colsTitle = title; colsPick = pick
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "Ошибка файла: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                target = null
            } finally {
                busy = false
            }
        } else target = null
    }

    val cd = colsData
    val cp = colsPick
    if (cd != null && cp != null) {
        BatchColumnsDialog(
            rows = cd, initial = cp,
            onCancel = { colsData = null; colsPick = null; target = null },
            onConfirm = { p ->
                val list = BatchColumns.parse(cd, p)
                colsData = null; colsPick = null
                if (list.isEmpty()) {
                    Toast.makeText(ctx, "В выбранных столбцах нет товаров", Toast.LENGTH_LONG).show()
                    target = null
                } else accept(list, colsTitle)
            }
        )
    }

    val open = BatchStore.batches.firstOrNull { it.id == BatchStore.openId }

    if (scanning) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { scanning = false; target = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            Surface(Modifier.fillMaxSize()) {
                ListPhotoScreen(
                    catalogMode = true,
                    draftTag = "batch",
                    doneLabel = "В партию",
                    onCancel = { scanning = false; target = null },
                    onDone = { list ->
                        scanning = false
                        accept(
                            list.filter { it.name.isNotBlank() || it.barcode.isNotBlank() }
                                .asReversed()
                                .map { BatchRow(it.name.ifBlank { "Товар " + it.barcode }, "1", it.barcode, it.article) },
                            ""
                        )
                    }
                )
            }
        }
    }

    if (open != null) {
        BatchEditor(open, onAddMore = { target = open; addDlg = true }) { BatchStore.save(ctx); BatchStore.openId = null }
    } else {
        BackHandler(onBack = onBack)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
                Text("Партии", style = MaterialTheme.typography.titleLarge)
            }
            Button(
                onClick = { target = null; addDlg = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) { Text(if (busy) "Читаю файл…" else "Добавить", style = MaterialTheme.typography.titleLarge) }
            Spacer(Modifier.height(8.dp))
            if (BatchStore.batches.isEmpty()) {
                Text(
                    "Партий пока нет. «Добавить» → сканер этикеток или файл Excel.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(BatchStore.batches.toList(), key = { it.id }) { b ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { BatchStore.openId = b.id },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    fmtTime(b.created) + " · позиций: ${b.rows.size}" + if (b.link.isNotBlank()) " · отправлена" else "",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = { delBatch = b }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (addDlg) {
        AlertDialog(
            onDismissRequest = { addDlg = false; target = null },
            title = { Text("Добавить") },
            text = { Text(if (target != null) "Товары добавятся в эту партию." else "Как создать список товаров?") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { addDlg = false; scanning = true }) { Text("Сканировать") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { addDlg = false; picker.launch(arrayOf("*/*")) }) { Text("Из файла (Excel)") }
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { addDlg = false; target = null }) { Text("Отмена") }
                }
            }
        )
    }
    delBatch?.let { b ->
        AlertDialog(
            onDismissRequest = { delBatch = null },
            title = { Text("Удалить партию?") },
            text = { Text("Список «${b.title}» будет удалён с телефона. Уже отправленная ссылка продолжит работать.") },
            confirmButton = { Button(onClick = { BatchStore.remove(ctx, b); delBatch = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { delBatch = null }) { Text("Отмена") } }
        )
    }
}

/** Открытая партия: сверху «Отправить» и «QR», ниже список с количеством. */
@Composable
private fun BatchEditor(b: Batch, onAddMore: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }
    DisposableEffect(b) { onDispose { BatchStore.save(ctx) } }
    var busy by remember { mutableStateOf(false) }
    var sendDlg by remember { mutableStateOf(false) }
    var qrLink by remember { mutableStateOf<String?>(null) }

    /** Загружает партию на Диск (если не удалось — партию всё равно можно отправить файлом без ссылки), затем action(ссылка). */
    fun publish(action: (String) -> Unit) {
        if (busy) return
        busy = true
        BatchStore.save(ctx)
        val app = ctx.applicationContext
        scope.launch {
            val link = try {
                withContext(Dispatchers.IO) { BatchShare.publish(app, b) }
            } catch (e: Exception) {
                Toast.makeText(app, "На Диск не загружено: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                ""
            }
            BatchStore.save(app)
            busy = false
            action(link)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text("Партия", style = MaterialTheme.typography.titleLarge)
        }
        // над списком: «Отправить» и «QR»
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && b.rows.isNotEmpty(),
                modifier = Modifier.weight(1f).height(48.dp),
                onClick = { sendDlg = true }
            ) { Text(if (busy) "Загружаю…" else "Отправить") }
            OutlinedButton(
                enabled = !busy && b.rows.isNotEmpty(),
                modifier = Modifier.weight(1f).height(48.dp),
                onClick = { publish { link -> if (link.isNotBlank()) qrLink = link } }
            ) { Text("QR-код") }
        }
        Spacer(Modifier.height(8.dp))
        CompactField(value = b.title, onChange = { b.title = germanKeys(it) }, hint = "Название партии", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(
            "Позиций: ${b.rows.size} · всего: ${fmtQ(b.rows.sumOf { parseQ(batchQty(it.qty)) })}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp)
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
            items(b.rows.toList(), key = { System.identityHashCode(it) }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = listOf(
                            if (r.article.isNotBlank()) "арт. ${r.article}" else "",
                            if (r.barcode.isNotBlank()) "ШК ${r.barcode}" else ""
                        ).filter { it.isNotEmpty() }.joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                    }
                    CompactField(
                        value = r.qty, onChange = { v -> r.qty = v.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(7) },
                        hint = "1", modifier = Modifier.width(76.dp)
                    )
                    TextButton(onClick = { b.rows.remove(r) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("✕") }
                }
                HorizontalDivider()
            }
        }
        OutlinedButton(onClick = onAddMore, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Добавить ещё") }
    }

    if (sendDlg) {
        AlertDialog(
            onDismissRequest = { sendDlg = false },
            title = { Text("Отправить партию") },
            text = { Text("Список загрузится на Google Диск. Получателю уйдёт ссылка: она открывает мини-приложение в браузере (iPhone и Android), ничего устанавливать не нужно.") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = {
                        sendDlg = false
                        publish { link -> BatchShare.share(ctx, b, link, whatsapp = true) }
                    }) { Text("WhatsApp") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        sendDlg = false
                        publish { link -> BatchShare.share(ctx, b, link, whatsapp = false) }
                    }) { Text("Поделиться…") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        sendDlg = false
                        publish { link -> if (link.isNotBlank()) qrLink = link }
                    }) { Text("Показать QR-код") }
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { sendDlg = false }) { Text("Отмена") }
                }
            }
        )
    }
    qrLink?.let { link ->
        val bmp = remember(link) { BatchShare.qrBitmap(link) }
        AlertDialog(
            onDismissRequest = { qrLink = null },
            title = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Surface(color = Color.White, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                        Image(bmp.asImageBitmap(), contentDescription = "QR-код партии", modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Получатель наводит обычную камеру телефона на код — в браузере откроется мини-приложение со списком. В приложении Lager код тоже можно сканировать (Прочее → Сборка → «Получить»).", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { qrLink = null }) { Text("Закрыть") } },
            dismissButton = { TextButton(onClick = { BatchShare.share(ctx, b, link, whatsapp = false) }) { Text("Поделиться") } }
        )
    }
}
