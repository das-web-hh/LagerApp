package ru.storage.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Куда листать файлы в окне «Файлы» (настройка): какой жест открывает СЛЕДУЮЩИЙ файл.
 * 0 — свайп влево, 1 — свайп вправо, 2 — свайп вверх, 3 — свайп вниз (противоположный жест — предыдущий файл).
 */
object FileNavCfg {
    var dir by mutableIntStateOf(0)
        private set

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("file_nav", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        dir = sp(ctx).getInt("dir", 0).coerceIn(0, 3)
    }

    fun set(ctx: Context, v: Int) {
        dir = v.coerceIn(0, 3)
        sp(ctx).edit().putInt("dir", dir).apply()
    }

    fun applyCloud(ctx: Context, v: Long?) {
        if (v != null) set(ctx, v.toInt())
    }
}

// ───────────────────────── даты файлов и разбор запроса ─────────────────────────

private val NAME_YMD = Regex("""(20\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])(?:[-_ T.]?([01]\d|2[0-3])([0-5]\d)([0-5]\d)?)?""")
private val NAME_DMY = Regex("""(0[1-9]|[12]\d|3[01])[.\-_](0[1-9]|1[0-2])[.\-_](20\d{2})""")

private fun millisOf(y: Int, m: Int, d: Int, hh: Int = 0, mm: Int = 0): Long =
    Calendar.getInstance().apply { clear(); set(y, m - 1, d, hh, mm, 0) }.timeInMillis

/** Дата и время из имени файла (например «Сканирование_20261010-1523.pdf», «IMG_20261010_152300.jpg», «Счёт 10.10.2026.pdf»). */
fun nameMillis(name: String): Long? {
    NAME_YMD.find(name)?.let { m ->
        val g = m.groupValues
        return millisOf(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].ifBlank { "0" }.toInt(), g[5].ifBlank { "0" }.toInt())
    }
    NAME_DMY.find(name)?.let { m ->
        val g = m.groupValues
        return millisOf(g[3].toInt(), g[2].toInt(), g[1].toInt())
    }
    return null
}

internal fun dayKey(ms: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}

/** Время файла для списка: дата изменения файла, если телефон её отдаёт, иначе дата из имени. */
internal fun effMillis(f: FolderFile): Long = if (f.modified > 0) f.modified else (nameMillis(f.name) ?: 0L)

/** Дни, на которые «приходится» файл: день изменения файла и день из имени (файл-копия может иметь другую дату изменения). */
private fun fileDays(f: FolderFile): Set<Int> {
    val s = HashSet<Int>(2)
    if (f.modified > 0) s.add(dayKey(f.modified))
    nameMillis(f.name)?.let { s.add(dayKey(it)) }
    return s
}

private val RU_MONTHS = listOf("янв", "фев", "мар", "апр", "мая", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")
private val RU_MONTH_NUM = listOf(1, 2, 3, 4, 5, 5, 6, 7, 8, 9, 10, 11, 12)

/** Дата из строки поиска в любом привычном виде: 10.10.2026, 10/10/26, 2026-10-10, 20261010, 101026, «10 окт», «10.10». Год может быть не указан (null). */
internal class QDate(val y: Int?, val m: Int, val d: Int)

internal fun parseQueryDate(raw: String): QDate? {
    val q = raw.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return null
    fun ok(y: Int?, m: Int, d: Int): QDate? {
        if (m !in 1..12 || d !in 1..31) return null
        val yy = y?.let { if (it < 100) 2000 + it else it }
        if (yy != null && yy !in 2000..2099) return null
        return QDate(yy, m, d)
    }
    // «10 окт», «10 октября», «10 окт 2026»
    Regex("""^(\d{1,2})\s*([а-я]{3,})\.?\s*(\d{2,4})?$""").find(q)?.let { r ->
        val idx = RU_MONTHS.indexOfFirst { r.groupValues[2].startsWith(it) }
        if (idx >= 0) return ok(r.groupValues[3].toIntOrNull(), RU_MONTH_NUM[idx], r.groupValues[1].toInt())
    }
    // только цифры
    if (q.all { it.isDigit() }) {
        return when (q.length) {
            8 -> if (q.startsWith("20") || q.startsWith("19")) ok(q.substring(0, 4).toInt(), q.substring(4, 6).toInt(), q.substring(6, 8).toInt())
            else ok(q.substring(4, 8).toInt(), q.substring(2, 4).toInt(), q.substring(0, 2).toInt())
            6 -> ok(q.substring(4, 6).toInt(), q.substring(2, 4).toInt(), q.substring(0, 2).toInt())
            4 -> ok(null, q.substring(2, 4).toInt(), q.substring(0, 2).toInt())
            else -> null
        }
    }
    // с разделителями . / - пробел
    val parts = q.split(Regex("""[./\-\s,]+""")).filter { it.isNotEmpty() }
    if (parts.any { p -> p.any { !it.isDigit() } }) return null
    return when (parts.size) {
        3 -> if (parts[0].length == 4) ok(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        else ok(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
        2 -> ok(null, parts[1].toInt(), parts[0].toInt())
        else -> null
    }
}

internal fun matchesDate(f: FolderFile, q: QDate): Boolean = fileDays(f).any { k ->
    val y = k / 10000
    val m = (k / 100) % 100
    val d = k % 100
    m == q.m && d == q.d && (q.y == null || y == q.y)
}

private val STAMP = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT)

// ───────────────────────── окно «Файлы» ─────────────────────────

@Composable
fun FileBrowserScreen(onBack: () -> Unit, initialQuery: String = "") {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Folders.load(ctx) }

    var files by remember { mutableStateOf(emptyList<FolderFile>()) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var calendar by remember { mutableStateOf(false) }
    var folderDlg by remember { mutableStateOf(false) }
    var viewIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(reload, Folders.folders.size) {
        loading = true
        files = withContext(Dispatchers.IO) { Folders.scanAll(ctx, false).sortedByDescending { effMillis(it) } }
        loading = false
    }

    val qDate = remember(query) { parseQueryDate(query) }
    val results = remember(files, query) {
        when {
            query.isBlank() -> files
            qDate != null -> files.filter { matchesDate(it, qDate) }.sortedBy { effMillis(it) }
            else -> files.filter { it.name.contains(query.trim(), ignoreCase = true) }
        }
    }

    val viewing = viewIndex
    if (viewing != null && results.isNotEmpty()) {
        FileViewer(results, viewing.coerceIn(0, results.lastIndex)) { viewIndex = null }
        return
    }

    BackHandler(onBack = onBack)
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            withContext(Dispatchers.IO) {
                try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
                Folders.add(ctx, uri)
            }
            reload++
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Файлы", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { folderDlg = true }) { Text("Папки (${Folders.folders.size})") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Поиск: название или дата") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить") }
                    IconButton(onClick = { calendar = true }) { Icon(Icons.Default.DateRange, "Календарь") }
                }
            }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                Folders.folders.isEmpty() -> "Подключите папку: кнопка «Папки»"
                loading -> "Читаю папки…"
                query.isBlank() -> "Файлов: ${results.size}"
                qDate != null -> "За эту дату файлов: ${results.size}"
                else -> "Найдено: ${results.size}"
            },
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(results.size) { i ->
                val f = results[i]
                Row(
                    Modifier.fillMaxWidth().clickable { viewIndex = i }.padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (f.name.lowercase().endsWith(".pdf")) "📄" else if (isImageName(f.name)) "🖼" else "📊", fontSize = 24.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val t = effMillis(f)
                        Text(
                            (if (t > 0) STAMP.format(Date(t)) else "дата неизвестна") + " · " + f.folder,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (calendar) {
        val today = remember { SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(Date()) }
        ExpiryPickerDialog(
            initial = qDate?.takeIf { it.y != null }?.let { String.format(Locale.ROOT, "%02d.%02d.%04d", it.d, it.m, it.y) } ?: today,
            onPick = { picked ->
                calendar = false
                query = picked
            },
            onDismiss = { calendar = false }
        )
    }

    if (folderDlg) {
        AlertDialog(
            onDismissRequest = { folderDlg = false },
            title = { Text("Папки с файлами") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (Folders.folders.isEmpty()) Text("Папок нет. Добавьте одну или несколько.")
                    Folders.folders.toList().forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Folders.label(s), Modifier.weight(1f).padding(vertical = 6.dp))
                            IconButton(onClick = { Folders.remove(ctx, s); reload++ }) { Icon(Icons.Default.Delete, "Убрать") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { addFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить папку")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { folderDlg = false }) { Text("Готово") } }
        )
    }
}

// ───────────────────────── просмотр файла ─────────────────────────

private class PdfHolder(ctx: Context, uri: Uri) : AutoCloseable {
    private val pfd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r")!!
    private val renderer = PdfRenderer(pfd)
    private val mutex = Mutex()

    @Volatile
    private var closed = false
    val count: Int = renderer.pageCount

    suspend fun render(i: Int, width: Int): Bitmap? = mutex.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            val page = renderer.openPage(i)
            try {
                val k = width.toFloat() / page.width
                val bmp = Bitmap.createBitmap(width, (page.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        }
    }

    override fun close() {
        closed = true
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                try { renderer.close() } catch (e: Exception) { }
                try { pfd.close() } catch (e: Exception) { }
            }
        }
    }
}

@Composable
private fun FileViewer(list: List<FolderFile>, start: Int, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var idx by remember { mutableIntStateOf(start) }
    val f = list[idx]
    val dir = FileNavCfg.dir
    var scale by remember(idx) { mutableFloatStateOf(1f) }
    var off by remember(idx) { mutableStateOf(Offset.Zero) }

    fun go(d: Int) {
        val n = idx + d
        if (n in list.indices) idx = n
    }

    // вертикальное листание: тянем дальше конца страницы — открывается соседний файл
    val conn = remember(dir, list.size) {
        object : NestedScrollConnection {
            var acc = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (dir >= 2) acc += available.y
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val a = acc
                acc = 0f
                if (dir >= 2 && abs(a) > 120f) {
                    val next = if (dir == 2) a < 0 else a > 0
                    go(if (next) 1 else -1)
                }
                return Velocity.Zero
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть") }
            Column(Modifier.weight(1f)) {
                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                val t = effMillis(f)
                Text(
                    (if (t > 0) STAMP.format(Date(t)) else "") + "  ·  ${idx + 1} / ${list.size}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (scale > 1.01f) TextButton(onClick = { scale = 1f; off = Offset.Zero }) { Text("1×") }
            OutlinedButton(onClick = { go(-1) }, enabled = idx > 0, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("‹ Назад") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { go(1) }, enabled = idx < list.lastIndex, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Вперёд ›") }
        }
        HorizontalDivider()
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .nestedScroll(conn)
                // горизонтальное листание файлов (если выбрано в настройках)
                .pointerInput(idx, dir, scale > 1.01f) {
                    if (dir <= 1 && scale <= 1.01f) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (abs(total) > 150f) {
                                    val next = if (dir == 0) total < 0 else total > 0
                                    go(if (next) 1 else -1)
                                }
                            },
                            onHorizontalDrag = { _, d -> total += d }
                        )
                    }
                }
                // два пальца — увеличение и сдвиг
                .pointerInput(idx) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size >= 2) {
                                scale = (scale * ev.calculateZoom()).coerceIn(1f, 5f)
                                off = if (scale > 1f) off + ev.calculatePan() else Offset.Zero
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y)) {
                key(f.id) {
                    val name = f.name.lowercase()
                    when {
                        name.endsWith(".pdf") -> PdfBody(f)
                        isImageName(name) -> ImageBody(f)
                        else -> ExternalBody(f)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfBody(f: FolderFile) {
    val ctx = LocalContext.current
    val holder = remember(f.id) { try { PdfHolder(ctx, f.uri) } catch (e: Exception) { null } }
    DisposableEffect(f.id) { onDispose { holder?.close() } }
    if (holder == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Не удалось открыть PDF") }
        return
    }
    val dm = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = with(density) { (dm.screenWidthDp.dp.toPx() * 1.5f).toInt() }
    LazyColumn(Modifier.fillMaxSize()) {
        items(holder.count) { i ->
            val bmp by produceState<Bitmap?>(null, holder, i, widthPx) { value = holder.render(i, widthPx) }
            val b = bmp
            if (b == null) {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                androidx.compose.foundation.Image(
                    bitmap = b.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ImageBody(f: FolderFile) {
    val ctx = LocalContext.current
    val dm = LocalConfiguration.current
    val density = LocalDensity.current
    val targetPx = with(density) { (dm.screenWidthDp.dp.toPx() * 2f).toInt() }
    val bmp by produceState<Bitmap?>(null, f.id) {
        value = withContext(Dispatchers.IO) {
            try {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, b) }
                var s = 1
                while (b.outWidth / (s * 2) >= targetPx) s *= 2
                ctx.contentResolver.openInputStream(f.uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    val b = bmp
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        if (b == null) CircularProgressIndicator(Modifier.padding(40.dp))
        else androidx.compose.foundation.Image(
            bitmap = b.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth
        )
    }
}

@Composable
private fun ExternalBody(f: FolderFile) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Этот файл здесь не показывается (не PDF и не картинка).")
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW).setData(f.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, "Нет приложения для этого файла", android.widget.Toast.LENGTH_SHORT).show()
            }
        }) { Text("Открыть в другом приложении") }
    }
}
