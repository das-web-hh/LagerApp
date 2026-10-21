package ru.storage.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/* Папки автоприёма: выбранные папки → список PDF и картинок → очередь на распознавание ИИ → сохранение. */

class FolderFile(
    val id: String,
    val name: String,
    val uri: Uri,
    val size: Long,
    val modified: Long,
    val folder: String
)

/**
 * Пауза автоприёма: пока включена, папки не проверяются на новые файлы, очередь не двигается и ИИ ничего не получает —
 * окно только показывает файлы. Хранится на телефоне и в аккаунте (Firebase), поэтому держится и после перезапуска,
 * и после удаления и повторной установки приложения (после входа в тот же аккаунт).
 */
object AutoPause {
    var paused by mutableStateOf(false)
        private set

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("auto_pause", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        paused = sp(ctx).getBoolean("paused", false)
    }

    /** Нажатие «Пауза» / «Продолжить»: запоминается сразу и уходит в облако. */
    fun set(ctx: Context, v: Boolean) {
        paused = v
        sp(ctx).edit().putBoolean("paused", v).apply()
        CloudSettings.save(ctx, Prefs(ctx))
    }

    /** Значение из аккаунта (после входа или переустановки). */
    fun applyCloud(ctx: Context, v: Boolean?) {
        if (v == null) return
        paused = v
        sp(ctx).edit().putBoolean("paused", v).apply()
    }
}

object Folders {
    val folders = mutableStateListOf<String>()
    private val processed = mutableStateMapOf<String, Boolean>()
    var auto by mutableStateOf(true)
        private set
    private var loaded = false

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("folders", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val s = sp(ctx)
        try {
            val a = JSONArray(s.getString("list", "[]"))
            for (i in 0 until a.length()) folders.add(a.getString(i))
        } catch (e: Exception) {
        }
        (s.getStringSet("processed", emptySet()) ?: emptySet()).forEach { processed[it] = true }
        auto = s.getBoolean("auto", true)
    }

    private fun saveList(ctx: Context) {
        val a = JSONArray()
        folders.forEach { a.put(it) }
        sp(ctx).edit().putString("list", a.toString()).apply()
    }

    fun setAuto(ctx: Context, v: Boolean) {
        auto = v
        sp(ctx).edit().putBoolean("auto", v).apply()
    }

    fun add(ctx: Context, uri: Uri) {
        if (uri.toString() !in folders) folders.add(uri.toString())
        saveList(ctx)
    }

    fun remove(ctx: Context, s: String) {
        folders.remove(s)
        saveList(ctx)
        try {
            ctx.contentResolver.releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun markProcessed(ctx: Context, ids: Collection<String>) {
        ids.forEach { processed[it] = true }
        sp(ctx).edit().putStringSet("processed", processed.keys.toSet()).apply()
    }

    fun isProcessed(id: String) = processed[id] == true

    fun label(s: String): String = try {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(s))
        val path = id.substringAfter(':', "")
        if (path.isBlank()) "Память телефона" else path
    } catch (e: Exception) {
        s
    }

    /** Берём только PDF, картинки и Excel (.xlsx). */
    private fun isDoc(name: String, mime: String): Boolean =
        mime == "application/pdf" || mime == "image/jpeg" || mime == "image/png" || mime == "image/webp" ||
            mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
            name.lowercase().endsWith(".pdf") || isImageName(name) ||
            name.lowercase().endsWith(".xlsx") || name.lowercase().endsWith(".xlsm")

    private fun scan(ctx: Context, tree: Uri, docId: String, depth: Int, label: String, out: MutableList<FolderFile>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        try {
            ctx.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val mod = c.getLong(3)
                    val size = c.getLong(4)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 3) scan(ctx, tree, id, depth + 1, "$label/$name", out)
                    } else if (isDoc(name, mime)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        out.add(FolderFile("$uri|$size|$mod", name, uri, size, mod, label))
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    /** Файлы одной папки (и вложенных до 3 уровней). Не в главном потоке. */
    fun listTree(ctx: Context, tree: Uri): List<FolderFile> {
        val out = ArrayList<FolderFile>()
        try {
            scan(ctx, tree, DocumentsContract.getTreeDocumentId(tree), 0, label(tree.toString()), out)
        } catch (e: Exception) {
        }
        return out
    }

    /** Файлы всех выбранных папок, новые сверху. Если enqueue и автоприём включён — новые файлы ставятся в очередь. */
    fun scanAll(ctx: Context, enqueue: Boolean): List<FolderFile> {
        load(ctx)
        val all = folders.toList().flatMap { listTree(ctx, Uri.parse(it)) }.sortedByDescending { it.modified }
        if (enqueue && auto && !AutoPause.paused) {
            all.filter { !isProcessed(it.id) }.sortedBy { it.modified }.forEach { FolderQueue.enqueue(ctx, it) }
        }
        return all
    }

    /** Пометка в списке: ✓ принят, ⏳ в работе, ⚠ не получилось, ● новый. */
    fun mark(f: FolderFile): String {
        if (f.id in FolderQueue.queued) return "⏳"
        val path = FolderQueue.tracked[f.id]
        if (path != null) {
            val j = AutoReceiveHolder.find(path) ?: return "✓"
            return when {
                j.saved -> "✓"
                j.running || j.saving -> "⏳"
                j.failedState || j.blocked.isNotBlank() || j.autoState == "error" || j.autoState == "unmarked" || j.autoState == "samedup" -> "⚠"
                else -> "✎"
            }
        }
        return if (isProcessed(f.id)) "✓" else "●"
    }

    /** Подпись состояния файла в списке. */
    fun status(f: FolderFile): String {
        if (f.id in FolderQueue.queued) return if (AutoPause.paused) "В очереди — автоприём на паузе" else "В очереди на распознавание"
        val path = FolderQueue.tracked[f.id]
        if (path != null) {
            val j = AutoReceiveHolder.find(path)
            return if (j != null) j.statusLine else "Обработан"
        }
        return if (isProcessed(f.id)) "Обработан" else "Новый"
    }
}

/** Очередь: файлы из папок уходят в автоприём строго по одному, следующий — когда предыдущий дочитан ИИ. */
object FolderQueue {
    val queued = mutableStateListOf<String>()
    val tracked = mutableStateMapOf<String, String>() // id файла → путь копии, по которой живёт партия
    private val channel = Channel<FolderFile>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    @Synchronized
    fun enqueue(ctx: Context, f: FolderFile) {
        if (AutoPause.paused) return
        if (f.id in queued || tracked.containsKey(f.id)) return
        val app = ctx.applicationContext
        if (!started) {
            started = true
            scope.launch { for (item in channel) work(app, item) }
        }
        queued.add(f.id)
        channel.trySend(f)
    }

    private suspend fun work(app: Context, f: FolderFile) {
        try {
            // пауза: файл ждёт в очереди, пока автоприём не продолжат
            while (AutoPause.paused) delay(1000)
            val dir = File(app.cacheDir, "incoming/" + UUID.randomUUID().toString()).also { it.mkdirs() }
            val target = File(dir, f.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            val ins = app.contentResolver.openInputStream(f.uri) ?: throw RuntimeException("Файл недоступен")
            ins.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            val job = AutoReceiveHolder.create(app, IncomingFile(f.name, target.path), false)
            tracked[f.id] = target.path
            job.fromQueue = true
            job.startOnce()
            queued.remove(f.id)
            var t0 = System.currentTimeMillis()
            delay(500)
            // ждём, пока ИИ дочитает все листы этого файла (не дольше 20 минут; время паузы не считается)
            while (job.running && System.currentTimeMillis() - t0 < 20 * 60_000L) {
                if (AutoPause.paused) t0 += 1000
                delay(1000)
            }
        } catch (e: Exception) {
        } finally {
            queued.remove(f.id)
            Folders.markProcessed(app, listOf(f.id))
        }
    }
}

/** Файл из папки → партия «По наименованию» (копия во внутреннем кэше), экран открывается сам. Не в главном потоке. */
fun openFolderFileByName(ctx: Context, f: FolderFile) {
    val app = ctx.applicationContext
    val known = FolderQueue.tracked[f.id]?.let { AutoReceiveHolder.find(it) }
    if (known != null) {
        known.openInNameMode()
        Incoming.openPath = known.file.path
        return
    }
    try {
        val dir = File(app.cacheDir, "incoming/" + UUID.randomUUID().toString()).also { it.mkdirs() }
        val target = File(dir, f.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        val ins = app.contentResolver.openInputStream(f.uri) ?: throw RuntimeException("Файл недоступен")
        ins.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        val job = AutoReceiveHolder.create(app, IncomingFile(f.name, target.path), true)
        FolderQueue.tracked[f.id] = target.path
        Folders.markProcessed(app, listOf(f.id))
        job.startOnce()
        Incoming.openPath = target.path
    } catch (e: Exception) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(app, "Не удалось открыть файл: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}

private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

private fun sizeText(b: Long): String = when {
    b >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f МБ", b / 1048576.0)
    b >= 1024 -> "${b / 1024} КБ"
    b > 0 -> "$b Б"
    else -> ""
}

/**
 * Окно «Автоприём» (и «Файлы» в «Прочем»): папки (одна или несколько, в том числе Google Диск через системный выбор),
 * список PDF, картинок и Excel из них, пометки статуса; нажатие на файл открывает его в «По наименованию».
 * onPickFiles — кнопка «Выбрать файлы» (разовый выбор файлов без папки).
 */
@Composable
fun FilesScreen(onBack: () -> Unit, title: String = "Файлы", onPickFiles: (() -> Unit)? = null, onScan: (() -> Unit)? = null) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Folders.load(ctx) }
    var files by remember { mutableStateOf(emptyList<FolderFile>()) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var baseline by remember { mutableStateOf<Pair<Uri, List<FolderFile>>?>(null) }

    LaunchedEffect(reload, Folders.folders.size, Folders.auto, AutoPause.paused) {
        loading = true
        files = withContext(Dispatchers.IO) { Folders.scanAll(ctx, true) }
        loading = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                }
                Folders.listTree(ctx, uri)
            }
            if (uri.toString() in Folders.folders) {
                Toast.makeText(ctx, "Эта папка уже добавлена", Toast.LENGTH_SHORT).show()
            } else if (list.isEmpty()) {
                Folders.add(ctx, uri)
            } else {
                baseline = uri to list
            }
        }
    }

    baseline?.let { (uri, list) ->
        AlertDialog(
            onDismissRequest = {
                try { ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
                baseline = null
            },
            title = { Text("В папке файлов: ${list.size}") },
            text = { Text("Отправить на распознавание все имеющиеся файлы или принимать только те, что появятся после этого?") },
            confirmButton = {
                TextButton(onClick = {
                    Folders.add(ctx, uri)
                    baseline = null
                }) { Text("Принять все") }
            },
            dismissButton = {
                TextButton(onClick = {
                    Folders.markProcessed(ctx, list.map { it.id })
                    Folders.add(ctx, uri)
                    baseline = null
                }) { Text("Только новые") }
            }
        )
    }

    val newCount = files.count { f -> !Folders.isProcessed(f.id) && f.id !in FolderQueue.queued && !FolderQueue.tracked.containsKey(f.id) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Назад") }
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Text(
                "PDF, картинки и Excel из выбранных папок (можно несколько; папка может быть и на Google Диске). Новые файлы по очереди уходят в автоприём: ИИ читает накладную, приём сохраняется в базу, а файл — на Google Диск. Нажмите на файл — он откроется в «По наименованию».",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            // пауза / продолжить автоприём (хранится в аккаунте: держится после перезапуска и переустановки)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (AutoPause.paused) "⏸ Автоприём на паузе" else "▶ Автоприём работает", style = MaterialTheme.typography.titleMedium,
                        color = if (AutoPause.paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text(
                        if (AutoPause.paused) "Новые файлы не проверяются и не отправляются на распознавание, окно только показывает файлы. Пауза сохраняется, пока вы не нажмёте «Продолжить»."
                        else "Новые файлы из папок идут на распознавание по очереди.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (AutoPause.paused) Button(onClick = { AutoPause.set(ctx, false) }) { Text("▶ Продолжить") }
                else OutlinedButton(onClick = { AutoPause.set(ctx, true) }) { Text("⏸ Пауза") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Принимать новые файлы автоматически", style = MaterialTheme.typography.bodyLarge)
                    Text("Проверка папок раз в 2 минуты, пока приложение запущено", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = Folders.auto, onCheckedChange = { Folders.setAuto(ctx, it) })
            }
            Text("Папки", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (Folders.folders.isEmpty()) {
            item { Text("Папки не выбраны", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp)) }
        }
        items(Folders.folders.toList()) { s ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Folders.label(s), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { Folders.remove(ctx, s) }) { Text("✕") }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(null) }) { Text("Добавить папку") }
                if (onPickFiles != null) OutlinedButton(onClick = onPickFiles) { Text("Выбрать файлы") }
                if (onScan != null) OutlinedButton(onClick = onScan) { Text("Снимок") }
                OutlinedButton(onClick = { reload++ }, enabled = !loading && Folders.folders.isNotEmpty()) {
                    Text(if (loading) "Проверка…" else "Проверить")
                }
            }
            if (newCount > 0 && !Folders.auto) {
                OutlinedButton(
                    enabled = !AutoPause.paused,
                    onClick = {
                        files.filter { !Folders.isProcessed(it.id) }.sortedBy { it.modified }.forEach { FolderQueue.enqueue(ctx, it) }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text("Отправить новые на распознавание ($newCount)") }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            val marks = files.map { Folders.mark(it) }
            Text("Файлы: ${files.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            Text(
                "✓ принято ${marks.count { it == "✓" }} · ✎ проверить ${marks.count { it == "✎" }} · ⏳ в работе ${marks.count { it == "⏳" }} · " +
                    "⚠ не получилось ${marks.count { it == "⚠" }} · ● новых ${marks.count { it == "●" }}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        }
        items(files, key = { it.id }) { f ->
            val st = Folders.status(f)
            val mk = Folders.mark(f)
            Row(
                Modifier.fillMaxWidth()
                    .clickable {
                        if (f.id in FolderQueue.queued) {
                            Toast.makeText(ctx, "Файл в очереди на распознавание", Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch(Dispatchers.IO) { openFolderFileByName(ctx, f) }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    mk,
                    style = MaterialTheme.typography.titleLarge,
                    color = when (mk) {
                        "✓" -> androidx.compose.ui.graphics.Color(0xFF1B8A4B)
                        "⚠", "●" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.width(32.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(dateFmt.format(Date(f.modified)).takeIf { f.modified > 0 }, sizeText(f.size).ifBlank { null }, f.folder)
                            .filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        st, style = MaterialTheme.typography.bodySmall,
                        color = if (st == "Новый" || mk == "⚠") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
