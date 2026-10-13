package ru.storage.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** kind: "text" — документ как в Word, "sheet" — таблица (график) как в Excel, "print" — напечатанный из приложения документ (HTML),
 * "pdf" — импортированный PDF (body = файл в Base64). */
data class UserDoc(
    val id: String,
    val kind: String,
    val title: String,
    val author: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** черновик: создан, но ещё пуст — в списке не показывается */
    val draft: Boolean = false,
    val hash: String = "",
    /** имя последней загруженной на Диск версии и время (updatedAt) этой версии */
    val driveName: String = "",
    val driveAt: Long = 0L
)

/**
 * Документы проекта. На телефоне: filesDir/userdocs/<id>.meta (описание) и <id>.body (содержимое:
 * HTML для текста и напечатанного, JSON для таблицы). На Диске: DC-<id>-<версия>.json
 * (при новой версии старый файл удаляется), поэтому скрипт Диска менять не нужно.
 */
object DocStore {
    var version by mutableIntStateOf(0)
    var status by mutableStateOf("")

    private val lock = Any()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploading = AtomicBoolean(false)

    private fun dir(c: Context): File = File(c.filesDir, "userdocs").also { it.mkdirs() }
    private fun metaFile(c: Context, id: String) = File(dir(c), "$id.meta")
    private fun bodyFile(c: Context, id: String) = File(dir(c), "$id.body")
    private fun sp(c: Context) = c.getSharedPreferences("userdocs", Context.MODE_PRIVATE)

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(14)

    private fun parseMeta(f: File): UserDoc? = try {
        val o = JSONObject(f.readText(Charsets.UTF_8))
        UserDoc(
            o.getString("id"), o.optString("kind", "text"), o.optString("title"), o.optString("author"),
            o.optLong("createdAt"), o.optLong("updatedAt"), o.optBoolean("draft"),
            o.optString("hash"), o.optString("driveName"), o.optLong("driveAt")
        )
    } catch (e: Exception) {
        null
    }

    private fun writeMeta(c: Context, d: UserDoc) {
        val o = JSONObject()
            .put("id", d.id).put("kind", d.kind).put("title", d.title).put("author", d.author)
            .put("createdAt", d.createdAt).put("updatedAt", d.updatedAt).put("draft", d.draft)
            .put("hash", d.hash).put("driveName", d.driveName).put("driveAt", d.driveAt)
        metaFile(c, d.id).writeText(o.toString(), Charsets.UTF_8)
    }

    private fun addToSet(c: Context, key: String, value: String) {
        val s = HashSet<String>(sp(c).getStringSet(key, emptySet()) ?: emptySet())
        s.add(value)
        sp(c).edit().putStringSet(key, s).apply()
    }

    private fun purge(c: Context, id: String) {
        synchronized(lock) {
            metaFile(c, id).delete()
            bodyFile(c, id).delete()
        }
    }

    fun get(c: Context, id: String): UserDoc? = synchronized(lock) {
        val f = metaFile(c, id)
        if (f.exists()) parseMeta(f) else null
    }

    fun readBody(c: Context, id: String): String = synchronized(lock) {
        val f = bodyFile(c, id)
        if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    fun isBlank(kind: String, body: String): Boolean {
        if (kind == "sheet") {
            val n = try {
                JSONObject(body).optJSONObject("cells")?.length() ?: 0
            } catch (e: Exception) {
                0
            }
            return n == 0
        }
        return body.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").isBlank()
    }

    /** Новый документ: пока пуст — это черновик, в списке его нет. */
    fun create(c: Context, kind: String, title: String): UserDoc {
        val now = System.currentTimeMillis()
        val d = UserDoc(newId(), kind, title, Session.login, now, now, draft = true)
        synchronized(lock) {
            bodyFile(c, d.id).writeText("", Charsets.UTF_8)
            writeMeta(c, d)
        }
        return d
    }

    // ------------------------------------------------------------------ импорт файлов

    private fun displayName(c: Context, uri: Uri): String? = try {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun readLimited(c: Context, uri: Uri, max: Int): ByteArray {
        val ins = c.contentResolver.openInputStream(uri) ?: throw RuntimeException("Файл недоступен")
        return ins.use { i ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw RuntimeException("Файл больше ${max / 1048576} МБ")
            }
            out.toByteArray()
        }
    }

    private fun htmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun textToHtml(t: String): String {
        val sb = StringBuilder()
        t.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if (line.isBlank()) sb.append("<p><br></p>")
            else sb.append("<p>").append(htmlEsc(line.trimEnd()).replace("\t", "&nbsp;&nbsp;&nbsp;&nbsp;")).append("</p>")
        }
        return sb.toString()
    }

    private fun decodeText(b: ByteArray): String {
        val bytes = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b.copyOfRange(3, b.size) else b
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            String(bytes, java.nio.charset.Charset.forName("windows-1252"))
        }
    }

    private fun rowsToSheetJson(rows: List<List<String>>): String {
        val maxRows = 1000
        val maxCols = 60
        val list = rows.take(maxRows)
        val cols = maxOf(12, minOf(maxCols, list.maxOfOrNull { it.size } ?: 0))
        val cells = JSONObject()
        list.forEachIndexed { r, row ->
            row.take(maxCols).forEachIndexed { cI, v ->
                if (v.isNotEmpty()) cells.put("$r,$cI", JSONObject().put("v", v))
            }
        }
        val w = org.json.JSONArray()
        repeat(cols) { w.put(96) }
        return JSONObject().put("rows", maxOf(60, list.size + 20).coerceAtMost(1000)).put("cols", cols)
            .put("w", w).put("cells", cells).toString()
    }

    /**
     * Импорт файла в «Документы»: .txt/.csv → текстовый документ, .xlsx → таблица, .pdf → PDF (просмотр и печать).
     * Вызывать не в главном потоке. Ошибка — исключение с понятным текстом.
     */
    fun importFile(c: Context, uri: Uri): UserDoc {
        val app = c.applicationContext
        val name = displayName(app, uri) ?: "Файл"
        val low = name.lowercase()
        val title = name.substringBeforeLast('.', name).replace('_', ' ').trim().ifBlank { "Документ" }.take(80)
        val kind: String
        val body: String
        when {
            low.endsWith(".pdf") -> {
                val bytes = readLimited(app, uri, 20 * 1024 * 1024)
                if (bytes.size < 5 || String(bytes, 0, 5, Charsets.ISO_8859_1) != "%PDF-") throw RuntimeException("«$name» — это не PDF")
                kind = "pdf"
                body = Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            low.endsWith(".xlsx") || low.endsWith(".xlsm") -> {
                val rows = Xlsx.read(app, uri)
                if (rows.isEmpty()) throw RuntimeException("В «$name» нет данных")
                kind = "sheet"
                body = rowsToSheetJson(rows)
            }
            low.endsWith(".xls") -> throw RuntimeException("Старый формат .xls не читается: сохраните файл как .xlsx")
            low.endsWith(".txt") || low.endsWith(".csv") || low.endsWith(".md") || low.endsWith(".log") || low.endsWith(".tsv") -> {
                val t = decodeText(readLimited(app, uri, 2 * 1024 * 1024))
                if (t.isBlank()) throw RuntimeException("«$name» пустой")
                kind = "text"
                body = textToHtml(t)
            }
            else -> throw RuntimeException("«$name»: поддерживаются PDF, Excel (.xlsx) и текстовые файлы (.txt, .csv)")
        }
        val now = System.currentTimeMillis()
        val d = UserDoc(newId(), kind, title, Session.login, now, now, false)
        synchronized(lock) {
            bodyFile(app, d.id).writeText(body, Charsets.UTF_8)
            writeMeta(app, d)
        }
        version++
        push(app)
        return d
    }

    fun list(c: Context): List<UserDoc> {
        val files = dir(c).listFiles()?.filter { it.name.endsWith(".meta") } ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<UserDoc>()
        for (f in files) {
            val d = synchronized(lock) { parseMeta(f) } ?: continue
            if (d.draft) {
                if (now - d.updatedAt > 24L * 3600L * 1000L) purge(c, d.id)
                continue
            }
            out.add(d)
        }
        out.sortByDescending { it.updatedAt }
        return out
    }

    /** Автосохранение содержимого из редактора (вызывается и из фонового потока). */
    fun saveBody(c: Context, id: String, body: String) {
        synchronized(lock) {
            val d = parseMeta(metaFile(c, id)) ?: return
            bodyFile(c, id).writeText(body, Charsets.UTF_8)
            val blank = isBlank(d.kind, body)
            writeMeta(c, d.copy(updatedAt = System.currentTimeMillis(), draft = if (blank) d.draft else false))
        }
        version++
    }

    fun saveTitle(c: Context, id: String, title: String) {
        synchronized(lock) {
            val d = parseMeta(metaFile(c, id)) ?: return
            if (d.title == title) return
            writeMeta(c, d.copy(title = title, updatedAt = System.currentTimeMillis()))
        }
        version++
    }

    fun delete(c: Context, id: String) {
        val d = get(c, id)
        purge(c, id)
        addToSet(c, "gone", id)
        if (d != null && d.driveName.isNotEmpty()) addToSet(c, "del", d.driveName)
        version++
        push(c)
    }

    private fun hash(s: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(24)
    }

    /** Любая печать в приложении сохраняет копию документа (повторная печать того же — только обновляет дату). */
    fun recordPrinted(c: Context, jobName: String, html: String) {
        val app = c.applicationContext
        val author = Session.login
        bg.launch {
            try {
                val title = jobName.replace('_', ' ').trim().ifBlank { "Документ" }
                val h = hash(html)
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    val existing = dir(app).listFiles()
                        ?.filter { it.name.endsWith(".meta") }
                        ?.mapNotNull { parseMeta(it) }
                        ?.firstOrNull { it.kind == "print" && it.hash == h }
                    if (existing != null) {
                        writeMeta(app, existing.copy(updatedAt = now))
                    } else {
                        val id = newId()
                        bodyFile(app, id).writeText(html, Charsets.UTF_8)
                        writeMeta(app, UserDoc(id, "print", title, author, now, now, false, h))
                    }
                }
                version++
                flush(app)
            } catch (e: Exception) {
            }
        }
    }

    fun push(c: Context) {
        val app = c.applicationContext
        bg.launch {
            try {
                flush(app)
            } catch (e: Exception) {
            }
        }
    }

    /** Отправить на Диск новые версии документов и удалить с Диска удалённые (блокирующая). */
    fun flush(c: Context) {
        if (!DriveCfg.ready(c)) return
        if (!uploading.compareAndSet(false, true)) return
        try {
            val del = HashSet<String>(sp(c).getStringSet("del", emptySet()) ?: emptySet())
            for (n in del.toList()) {
                DriveApi.delete(c, n)
                del.remove(n)
                sp(c).edit().putStringSet("del", HashSet<String>(del)).apply()
            }
            val files = dir(c).listFiles()?.filter { it.name.endsWith(".meta") } ?: emptyList()
            for (f in files) {
                val d = synchronized(lock) { parseMeta(f) } ?: continue
                if (d.draft || d.updatedAt <= d.driveAt) continue
                val name = "DC-${d.id}-${d.updatedAt}.json"
                val json = JSONObject()
                    .put("id", d.id).put("kind", d.kind).put("title", d.title).put("author", d.author)
                    .put("createdAt", d.createdAt).put("updatedAt", d.updatedAt).put("hash", d.hash)
                    .put("body", readBody(c, d.id))
                val tmp = File(c.cacheDir, name)
                tmp.writeText(json.toString(), Charsets.UTF_8)
                try {
                    DriveApi.upload(c, name, tmp)
                } finally {
                    tmp.delete()
                }
                if (d.driveName.isNotEmpty() && d.driveName != name) {
                    try {
                        DriveApi.delete(c, d.driveName)
                    } catch (e: Exception) {
                    }
                }
                synchronized(lock) {
                    val cur = parseMeta(metaFile(c, d.id))
                    if (cur != null) writeMeta(c, cur.copy(driveName = name, driveAt = d.updatedAt))
                }
            }
            status = ""
        } catch (e: Exception) {
            status = "Документы: не удалось отправить на Диск (" + (e.message ?: e.javaClass.simpleName) + ")"
        } finally {
            uploading.set(false)
        }
    }

    /** Подтянуть с Диска документы, которых нет на телефоне (или там версия новее). */
    fun restore(c: Context): Int {
        if (!DriveCfg.ready(c)) return 0
        val names = DriveApi.searchNames(c, "DC-")
        val rx = Regex("^DC-([A-Za-z0-9]+)-(\\d+)\\.json$")
        val best = HashMap<String, Pair<Long, String>>()
        for (n in names) {
            val m = rx.find(n) ?: continue
            val id = m.groupValues[1]
            val ts = m.groupValues[2].toLongOrNull() ?: continue
            val cur = best[id]
            if (cur == null || ts > cur.first) best[id] = Pair(ts, n)
        }
        val gone = sp(c).getStringSet("gone", emptySet()) ?: emptySet()
        var added = 0
        for ((id, p) in best) {
            if (id in gone) continue
            val local = get(c, id)
            if (local != null && local.updatedAt >= p.first) continue
            val bytes = DriveApi.download(c, p.second) ?: continue
            val o = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                continue
            }
            val upd = o.optLong("updatedAt", p.first)
            val doc = UserDoc(
                id, o.optString("kind", "text"), o.optString("title"), o.optString("author"),
                o.optLong("createdAt", upd), upd, false, o.optString("hash"), p.second, upd
            )
            synchronized(lock) {
                bodyFile(c, id).writeText(o.optString("body"), Charsets.UTF_8)
                writeMeta(c, doc)
            }
            added++
        }
        sp(c).edit().putBoolean("first_done", true).apply()
        if (added > 0) version++
        return added
    }

    fun sync(c: Context, forceRestore: Boolean) {
        try {
            flush(c)
            if (DriveCfg.ready(c) && (forceRestore || !sp(c).getBoolean("first_done", false))) {
                restore(c)
            }
        } catch (e: Exception) {
            status = "Документы: ошибка Диска (" + (e.message ?: e.javaClass.simpleName) + ")"
        }
    }
}

/** Мост «страница редактора → приложение»: страница сама присылает содержимое после правок (с паузой 0,5 с). */
class EditorBridge(private val app: Context, private val id: String) {
    @Volatile var changed = false

    @JavascriptInterface
    fun onChange(body: String) {
        changed = true
        DocStore.saveBody(app, id, body)
    }
}

private fun jsString(s: String): String =
    JSONObject.quote(s).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

private fun decodeJs(r: String?): String? {
    if (r == null || r == "null") return null
    return try {
        JSONTokener(r).nextValue() as? String
    } catch (e: Exception) {
        null
    }
}

/** Окно редактора: «Текст» (как Word) или «График» (как Excel). Страницы лежат в assets, работают без сети. */
@Composable
fun DocEditorScreen(docId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val doc0 = remember(docId) { DocStore.get(ctx, docId) }
    if (doc0 == null) {
        LaunchedEffect(docId) { onBack() }
        return
    }
    val kind = doc0.kind
    val wasDraft = remember(docId) { doc0.draft }
    var title by remember(docId) { mutableStateOf(doc0.title) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val bridge = remember(docId) { EditorBridge(ctx.applicationContext, docId) }
    val wvRef = remember(docId) { arrayOfNulls<WebView>(1) }

    LaunchedEffect(title) {
        delay(400)
        DocStore.saveTitle(ctx, docId, title)
    }
    DisposableEffect(docId) {
        onDispose { wvRef[0] = null }
    }

    fun leave() {
        val d = DocStore.get(ctx, docId)
        if (d != null && d.draft) {
            DocStore.delete(ctx, docId)
        } else if (d != null) {
            val what = if (kind == "sheet") "график" else "текстовый документ"
            if (wasDraft) {
                Journal.log("Документы", "Создан $what «${d.title}»")
            } else if (bridge.changed || d.title != doc0.title) {
                Journal.log("Документы", "Изменён $what «${d.title}»")
            }
            DocStore.push(ctx)
        }
        onBack()
    }

    fun finish() {
        val v = wvRef[0]
        if (v == null) {
            leave()
            return
        }
        v.evaluateJavascript("getDoc()") { r ->
            val body = decodeJs(r)
            if (body != null) DocStore.saveBody(ctx, docId, body)
            leave()
        }
    }

    fun printNow() {
        val v = wvRef[0] ?: return
        v.evaluateJavascript("getDoc()") { r ->
            val body = decodeJs(r)
            if (body != null) DocStore.saveBody(ctx, docId, body)
            v.evaluateJavascript("getPrintHtml(" + jsString(title) + ")") { r2 ->
                val html = decodeJs(r2)
                if (html != null) Printer.print(ctx, title.ifBlank { "Документ" }, html, false, kind == "text")
            }
        }
    }

    BackHandler { finish() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { finish() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("←") }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(80) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { printNow() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("🖨 Печать") }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    setBackgroundColor(if (dark) 0xFF14171D.toInt() else 0xFFEEF1F7.toInt())
                    addJavascriptInterface(bridge, "Host")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            val body = DocStore.readBody(ctx, docId)
                            view.evaluateJavascript("setDoc(" + jsString(body) + "," + dark + ")", null)
                        }
                    }
                    loadUrl("file:///android_asset/" + (if (kind == "sheet") "editor_sheet.html" else "editor_text.html"))
                    wvRef[0] = this
                }
            }
        )
    }
}

/** Просмотр напечатанного документа: как на бумаге, можно напечатать ещё раз. */
@Composable
fun PrintedDocScreen(docId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val doc = remember(docId) { DocStore.get(ctx, docId) }
    val html = remember(docId) { DocStore.readBody(ctx, docId) }
    val title = doc?.title ?: "Документ"
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        HtmlPreview(html, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { Printer.print(ctx, title, html, false) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("🖨 Печать") }
    }
}

/** Просмотр импортированного PDF: листы по порядку, кнопка печати. */
@Composable
fun PdfDocScreen(docId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val doc = remember(docId) { DocStore.get(ctx, docId) }
    val title = doc?.title ?: "PDF"
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(docId) {
        withContext(Dispatchers.IO) {
            try {
                val f = File(ctx.cacheDir, "docpdf_$docId.pdf")
                f.writeBytes(Base64.decode(DocStore.readBody(ctx, docId), Base64.DEFAULT))
                file = f
            } catch (e: Exception) {
                error = "Не удалось открыть PDF"
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val f = file
        when {
            error.isNotEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(error) }
            f == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> PdfPages(f, Modifier.weight(1f).fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { file?.let { Printer.printPdf(ctx, title, it) } },
            enabled = file != null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("🖨 Печать") }
    }
}

@Composable
private fun PdfPages(file: File, modifier: Modifier) {
    val widthPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp.toPx() * 1.6f).toInt() }.coerceIn(600, 1800)
    val lock = remember(file) { Mutex() }
    val holder = remember(file) {
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            Pair(pfd, PdfRenderer(pfd))
        } catch (e: Exception) {
            null
        }
    }
    DisposableEffect(holder) {
        onDispose {
            try { holder?.second?.close() } catch (e: Exception) { }
            try { holder?.first?.close() } catch (e: Exception) { }
        }
    }
    if (holder == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Не удалось открыть PDF") }
        return
    }
    val renderer = holder.second
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(renderer.pageCount) { i ->
            val bmp by produceState<Bitmap?>(null, i) {
                value = withContext(Dispatchers.IO) {
                    lock.withLock {
                        try {
                            val page = renderer.openPage(i)
                            try {
                                val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                                val b = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                                b.eraseColor(AColor.WHITE)
                                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                b
                            } finally {
                                page.close()
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }
            val b = bmp
            if (b == null) {
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Surface(shadowElevation = 2.dp, color = androidx.compose.ui.graphics.Color.White) {
                    Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun docKindLabel(kind: String): String = when (kind) {
    "sheet" -> "График"
    "print" -> "Напечатано"
    "pdf" -> "PDF"
    else -> "Текст"
}

private fun docEmoji(kind: String): String = when (kind) {
    "sheet" -> "📊"
    "print" -> "🖨"
    "pdf" -> "📄"
    else -> "📝"
}

@Composable
private fun DocChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(shape).clickable(onClick = onClick)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** Окно «Документы»: над списком кнопка «Создать документ» → мини-окно «Текст» / «График». */
@Composable
fun DocsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf(emptyList<UserDoc>()) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<UserDoc?>(null) }
    var filter by rememberSaveable { mutableStateOf("Все") }
    var busy by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val errors = ArrayList<String>()
            val done = ArrayList<UserDoc>()
            withContext(Dispatchers.IO) {
                uris.forEach { u ->
                    try {
                        done.add(DocStore.importFile(ctx, u))
                    } catch (e: Exception) {
                        errors.add(e.message ?: "Не удалось импортировать файл")
                    }
                }
            }
            done.forEach { Journal.log("Документы", "Импортирован файл «${it.title}» (${docKindLabel(it.kind)})") }
            busy = false
            docs = withContext(Dispatchers.IO) { DocStore.list(ctx) }
            if (errors.isNotEmpty()) Toast.makeText(ctx, errors.joinToString("\n"), Toast.LENGTH_LONG).show()
            if (done.size == 1 && errors.isEmpty()) openId = done[0].id
        }
    }

    LaunchedEffect(DocStore.version) {
        docs = withContext(Dispatchers.IO) { DocStore.list(ctx) }
    }
    LaunchedEffect(Unit) {
        busy = true
        withContext(Dispatchers.IO) { DocStore.sync(ctx, false) }
        busy = false
        docs = withContext(Dispatchers.IO) { DocStore.list(ctx) }
    }

    val id = openId
    if (id != null) {
        val kind = remember(id) { DocStore.get(ctx, id)?.kind ?: "text" }
        if (kind == "print") {
            PrintedDocScreen(id) { openId = null }
        } else if (kind == "pdf") {
            PdfDocScreen(id) { openId = null }
        } else {
            DocEditorScreen(id) {
                openId = null
                scope.launch { docs = withContext(Dispatchers.IO) { DocStore.list(ctx) } }
            }
        }
        return
    }

    val shown = remember(docs, filter) {
        when (filter) {
            "Текст" -> docs.filter { it.kind == "text" }
            "График" -> docs.filter { it.kind == "sheet" }
            "Напечатано" -> docs.filter { it.kind == "print" }
            "PDF" -> docs.filter { it.kind == "pdf" }
            else -> docs
        }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Документы", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        withContext(Dispatchers.IO) { DocStore.sync(ctx, true) }
                        busy = false
                        docs = withContext(Dispatchers.IO) { DocStore.list(ctx) }
                    }
                },
                enabled = !busy
            ) { Text("Обновить") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pick = true }, modifier = Modifier.weight(1f).height(52.dp)) {
                Text("＋ Создать")
            }
            OutlinedButton(
                onClick = {
                    importer.launch(
                        arrayOf(
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "text/plain", "text/csv", "text/comma-separated-values", "application/octet-stream"
                        )
                    )
                },
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("⇩ Импорт") }
        }
        Text(
            "Импорт: PDF, Excel (.xlsx), текст (.txt, .csv). Можно печатать.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
        val st = DocStore.status
        if (st.isNotBlank()) {
            Text(st, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else if (busy) {
            Text("Синхронизация с Диском…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Все", "Текст", "График", "PDF", "Напечатано").forEach { f -> DocChip(f, filter == f) { filter = f } }
        }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Документов пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable { openId = d.id }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(docEmoji(d.kind), style = MaterialTheme.typography.titleLarge) }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                docKindLabel(d.kind) + " · " + fmtTime(d.updatedAt) +
                                    (if (d.author.isNotBlank()) " · " + d.author else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (Session.isAdmin) TextButton(onClick = { toDelete = d }) { Text("✕") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (pick) {
        AlertDialog(
            onDismissRequest = { pick = false },
            title = { Text("Создать документ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            pick = false
                            val stamp = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())
                            openId = DocStore.create(ctx, "text", "Текст $stamp").id
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("📝  Текст") }
                    Button(
                        onClick = {
                            pick = false
                            val stamp = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())
                            openId = DocStore.create(ctx, "sheet", "График $stamp").id
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text("📊  График") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pick = false }) { Text("Отмена") } }
        )
    }

    toDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить документ?") },
            text = { Text("«${d.title}» будет удалён с телефона и с Google Диска.") },
            confirmButton = {
                Button(onClick = {
                    DocStore.delete(ctx, d.id)
                    Journal.log("Документы", "Удалён документ «${d.title}»")
                    toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } }
        )
    }
}
