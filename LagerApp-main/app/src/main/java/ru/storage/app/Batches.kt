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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
/** done — сколько штук по этой позиции уже найдено заранее (галочка ручкой на бумажном списке или столбец «Посчитано»); "" — ничего. */
class BatchRow(name: String, qty: String, val barcode: String = "", val article: String = "", val done: String = "") {
    var name by mutableStateOf(name)
    var qty by mutableStateOf(qty)
}

class Batch(val id: String, title: String, val created: Long) {
    var title by mutableStateOf(title)
    /** имена получателей через запятую: по ним HTML пускает внутрь (внутри файла только хеши) */
    var names by mutableStateOf("")
    /** пустая партия: список изначально пуст, получатель сам добавляет и печатает товары, каждая его партия тоже пустая */
    var empty = false
    val rows = mutableStateListOf<BatchRow>()
    /** ссылка последней отправки ("" — ещё не отправлялась) */
    var link by mutableStateOf("")
    /** хеш того, что лежит на Диске по этой ссылке: без изменений в партии повторная отправка ничего не создаёт */
    var pubHash by mutableStateOf("")
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
                b.pubHash = o.optString("ph")
                b.names = o.optString("nm")
                b.empty = o.optBoolean("em", false)
                val rs = o.optJSONArray("rows")
                if (rs != null) for (k in 0 until rs.length()) {
                    val r = rs.optJSONObject(k) ?: continue
                    b.rows.add(BatchRow(r.optString("n"), r.optString("q"), r.optString("b"), r.optString("a"), r.optString("d")))
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
                    rs.put(JSONObject().put("n", r.name).put("q", r.qty).put("b", r.barcode).put("a", r.article).put("d", r.done))
                }
                a.put(JSONObject().put("id", b.id).put("t", b.title).put("at", b.created).put("l", b.link).put("ph", b.pubHash).put("nm", b.names).put("em", b.empty).put("rows", rs))
            }
            file(ctx).writeText(a.toString())
        } catch (e: Exception) {
        }
    }

    fun create(ctx: Context, title: String, rows: List<BatchRow>, empty: Boolean = false): Batch {
        val now = System.currentTimeMillis()
        val b = Batch(UUID.randomUUID().toString(), title.ifBlank { (if (empty) "Пустая партия " else "Партия ") + fmtTime(now) }, now)
        b.empty = empty
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
    private const val WEB = "https://das-web-hh.github.io/LagerApp/"

    internal fun sha(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Имя в одном виде у отправителя и в HTML: маленькие буквы, ё = е, без лишних пробелов. */
    internal fun normName(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

    /**
     * Доступ к партии: хеши имён (кто может открыть), хеш администратора (ему рабочее время не мешает)
     * и рабочее время из настроек «Доступ по месту и времени» (config/access).
     */
    fun auth(b: Batch, cfg: AccessCfg?): JSONObject {
        val names = JSONArray()
        val seen = HashSet<String>()
        b.names.split(',', ';', '\n').map { normName(it) }.filter { it.isNotEmpty() }.forEach { if (seen.add(sha(it))) names.put(sha(it)) }
        val adm = JSONArray()
        val u = Session.user
        if (u != null && u.isAdmin) {
            val h = sha(normName(u.login))
            adm.put(h)
            if (seen.add(h)) names.put(h)
        }
        val hours = JSONObject().put("on", cfg?.enabled == true)
        if (cfg != null) hours.put("from", cfg.fromMin).put("to", cfg.toMin).put("tz", cfg.tz).put("days", JSONArray(cfg.days.toList()))
        return JSONObject().put("names", names).put("adm", adm).put("h", hours)
    }

    fun json(b: Batch, cfg: AccessCfg? = null, withAuth: Boolean = false): String {
        val rs = JSONArray()
        b.rows.toList().forEach { r ->
            val ro = JSONObject().put("n", r.name.trim()).put("q", batchQty(r.qty)).put("b", r.barcode).put("a", r.article)
            if (r.done.isNotBlank()) ro.put("d", r.done)
            rs.put(ro)
        }
        val o = JSONObject().put("v", 1).put("code", b.code).put("title", b.title).put("at", b.created).put("rows", rs)
        if (b.empty) o.put("empty", true)
        if (withAuth) o.put("auth", auth(b, cfg))
        return o.toString()
    }

    /**
     * Файл партии — мини-приложение «Сборка» одним HTML-файлом (шаблон assets/batch_app.html): список партии внутри,
     * работает в любом браузере без интернета и без установки: сканер (в том числе Bluetooth), камера, поиск по названию
     * и артикулу, счёт, печать, переход к следующей партии с нуля. Счёт хранится в самом браузере.
     * Те же данные внутри файла читает и приложение Warehouse (открыть файл через Warehouse).
     */
    fun html(ctx: Context, b: Batch, link: String = "", cfg: AccessCfg? = AccessStore.cached(ctx)): String {
        val tpl = ctx.applicationContext.assets.open("batch_app.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        // «<» в данных экранируется, чтобы текст товара не мог закрыть тег script
        // в файл идут только код, название и доступ: список товаров браузер получает с сервера после входа по имени
        val data = JSONObject(json(b, cfg, true)).also { it.remove("rows") }.toString().replace("<", "\\u003c")
        return tpl.replace("__TITLE__", bwEsc(b.title)).replace("__LINK__", bwEsc(link)).replace("__DATA__", data)
    }

    /**
     * Создаёт страницу-мини-приложение (HTML со списком внутри) и кладёт её на Google Диск, как и список (BATCH_код.json) для приложения.
     * Ссылка и QR ведут на скрипт Диска (действие app), он отдаёт эту страницу в браузер — iPhone и Android, без установки.
     * Повторная отправка заменяет файлы. Нужен обновлённый скрипт Диска (google-script/Code.gs). Вызывать не в главном потоке.
     */
    private val pubLock = Any()

    /**
     * Одна партия — одна ссылка: адрес строится из кода партии (он не меняется), поэтому QR и «Поделиться» всегда дают одну и ту же ссылку.
     * Если список, имена и доступ не менялись, на Диск ничего не отправляется (дублей не бывает). force = true — отправить заново.
     * Если партия удалена у всех — отправка закрыта, пока администратор не нажмёт «Пуск» для всех.
     */
    fun publish(app: Context, b: Batch, force: Boolean = false): String = synchronized(pubLock) {
        if (!DriveCfg.ready(app)) throw RuntimeException("Google Диск не настроен (Еще → Настройки → Google Диск)")
        val code = b.code
        val base = DriveCfg.url(app)
        // «#lager=код» в конце — по нему приложение Lager узнаёт партию при сканировании QR
        // Страница лежит на GitHub Pages (камера работает), данные партии она берёт у скрипта Диска по коду (action=batch)
        val link = WEB + "?c=" + code + "&s=" + java.net.URLEncoder.encode(base, "UTF-8") + TAG + code
        if (BatchCtl.state(BatchCtl.load(app, code), null) == "del")
            throw RuntimeException("Партия удалена у всех получателей. Верните её кнопкой «Пуск» в «Управление партией»")
        // рабочее время берём с сервера (не из кеша), чтобы HTML получил актуальные часы
        val cfg = try { kotlinx.coroutines.runBlocking { AccessStore.load(app) } } catch (e: Exception) { AccessStore.cached(app) }
        pushCatalog(app)
        val dataStr = json(b, cfg, true)
        val h = sha(dataStr + link)
        if (!force && b.link == link && b.pubHash == h) return@synchronized link
        val data = dataStr.toByteArray(Charsets.UTF_8)
        val page = html(app, b, link, cfg).toByteArray(Charsets.UTF_8)
        for (n in listOf("BATCH_$code.json", "BATCHPAGE_$code.html")) try { DriveApi.delete(app, n) } catch (e: Exception) { }
        DriveApi.uploadBytes(app, "BATCH_$code.json", "application/json", data)
        DriveApi.uploadBytes(app, "BATCHPAGE_$code.html", "text/html", page)
        b.link = link
        b.pubHash = h
        BatchCtl.reinstate(app, b)
        link
    }

    /**
     * Каталог товаров (он синхронизирован с Firebase) кладётся на Диск файлом CATALOG.json: [[название, штрих-код, артикул], …].
     * HTML-страница партии берёт его у скрипта (action=catalog) для подсказок «из базы». Отправляется, только если каталог изменился.
     */
    private fun pushCatalog(app: Context) {
        try {
            val list = ProductDb.get(app).allProducts().filter { it.name.isNotBlank() }
            if (list.isEmpty()) return
            val arr = org.json.JSONArray()
            list.forEach { p -> arr.put(org.json.JSONArray().put(p.name.trim()).put(p.barcode.trim()).put(p.article.trim())) }
            val txt = arr.toString().replace("<", "\\u003c")
            val h = sha(txt)
            val sp = app.getSharedPreferences("batch_catalog", Context.MODE_PRIVATE)
            if (sp.getString("hash", "") == h) return
            try { DriveApi.delete(app, "CATALOG.json") } catch (e: Exception) { }
            DriveApi.uploadBytes(app, "CATALOG.json", "application/json", txt.toByteArray(Charsets.UTF_8))
            sp.edit().putString("hash", h).apply()
        } catch (e: Exception) {
            // каталог — только подсказки; партия публикуется и без него
        }
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
                Toast.makeText(ctx, "Нужна ссылка: загрузите партию на Google Диск (Настройки → Google Диск) и отправьте снова", Toast.LENGTH_LONG).show()
                return
            }
            if (false) {
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

// ───────────────────────────── управление партией (администратор) ─────────────────────────────

/**
 * Управляющий файл партии на Диске: BATCHCTL_код.json = {"all":"run|stop|del","w":{"хеш имени":"stop|del"},"adm":[хеш админа]}.
 * Сервер (Code.gs) проверяет его при каждом запросе работника: stop — окно закрывается, счёт сохраняется;
 * del — данные на устройстве стираются. Нет файла — всё работает. Вызывать не в главном потоке.
 */
object BatchCtl {
    private fun fname(code: String) = "BATCHCTL_$code.json"

    fun load(app: Context, code: String): JSONObject {
        val bytes = DriveApi.download(app, fname(code)) ?: return JSONObject()
        return try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { JSONObject() }
    }

    /** Состояние: hash = null — партия в целом (для всех), иначе — конкретный получатель. "run" / "stop" / "del". */
    fun state(c: JSONObject, hash: String?): String {
        val all = c.optString("all")
        if (hash == null) return if (all == "stop" || all == "del") all else "run"
        if (all == "del") return "del"
        val own = c.optJSONObject("w")?.optString(hash).orEmpty()
        return if (own == "del") "del" else if (all == "stop" || own == "stop") "stop" else "run"
    }

    private fun write(app: Context, code: String, c: JSONObject) {
        c.put("t", System.currentTimeMillis())
        try { DriveApi.delete(app, fname(code)) } catch (e: Exception) { }
        DriveApi.uploadBytes(app, fname(code), "application/json", c.toString().toByteArray(Charsets.UTF_8))
    }

    /** op: "stop" | "run" | "del"; hash = null — для всех получателей. Возвращает новое состояние. */
    fun set(app: Context, b: Batch, hash: String?, op: String): JSONObject {
        val c = load(app, b.code)
        val adm = JSONArray()
        Session.user?.takeIf { it.isAdmin }?.let { adm.put(BatchShare.sha(BatchShare.normName(it.login))) }
        c.put("adm", adm)
        val w = c.optJSONObject("w") ?: JSONObject().also { c.put("w", it) }
        if (hash == null) {
            c.put("all", op)
            if (op == "run") w.keys().asSequence().toList().forEach { if (w.optString(it) == "stop") w.remove(it) }
        } else if (op == "run") w.remove(hash) else w.put(hash, op)
        write(app, b.code, c)
        return c
    }

    /** Имя снова добавлено в партию (после удаления) — снимаем с него «удалён». */
    fun reinstate(app: Context, b: Batch) {
        try {
            val c = load(app, b.code)
            val w = c.optJSONObject("w") ?: return
            var changed = false
            b.names.split(',', ';', '\n').map { BatchShare.normName(it) }.filter { it.isNotEmpty() }.forEach {
                val h = BatchShare.sha(it)
                if (w.optString(h) == "del") { w.remove(h); changed = true }
            }
            if (changed) write(app, b.code, c)
        } catch (e: Exception) {
        }
    }
}

private fun stText(s: String) = when (s) { "stop" -> "остановлено"; "del" -> "удалено"; else -> "работает" }

private class CtlAsk(val who: String?)

// ───────────────────────────── экраны ─────────────────────────────

private fun displayName(ctx: Context, uri: Uri): String = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }?.substringBeforeLast('.').orEmpty()
} catch (e: Exception) {
    ""
}

/** Окно «Партии» только для администратора: у остальных пользователей приложения оно не открывается. */
@Composable
fun BatchesScreen(prefs: Prefs, onBack: () -> Unit) {
    if (Session.user?.isAdmin == true) {
        BatchesScreenImpl(prefs, onBack)
    } else {
        BackHandler(onBack = onBack)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            }
            Text("Партии доступны только администратору.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(8.dp))
        }
    }
}

/** Окно «Партии»: сверху «Добавить», ниже созданные партии. */
@Composable
private fun BatchesScreenImpl(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { BatchStore.load(ctx) }
    var addDlg by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    /** «Сканирование с ИИ»: что сейчас читается ('' — ничего) и задача, чтобы её можно было отменить */
    var aiStatus by remember { mutableStateOf("") }
    var aiJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var delBatch by remember { mutableStateOf<Batch?>(null) }
    /** окно названия для пустой партии (null — закрыто) */
    var emptyName by remember { mutableStateOf<String?>(null) }
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
                    if (auto.isNotEmpty() && !pick.ask) {
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

    val aiScan = rememberBatchScanner(
        onPdf = { pdf ->
            aiStatus = "ИИ читает документ…"
            aiJob = scope.launch {
                try {
                    val list = BatchAiScan.read(ctx, pdf) { s -> aiStatus = s }
                    accept(list, "")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    target = null
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(ctx, "ИИ: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                    target = null
                } finally {
                    aiStatus = ""
                    aiJob = null
                }
            }
        },
        onCancel = { target = null }
    )

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
                    "Партий пока нет. «Добавить» → сканер этикеток, файл Excel или пустая партия.",
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
                                    fmtTime(b.created) + (if (b.empty) " · пустая" else " · позиций: ${b.rows.size}") + if (b.link.isNotBlank()) " · отправлена" else "",
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
            text = { Text(if (target != null) "Товары добавятся в эту партию." else "Как создать список товаров? «Пустая партия» — без списка: получатель сам добавляет и печатает товары.") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { addDlg = false; scanning = true }) { Text("Сканировать") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { addDlg = false; picker.launch(arrayOf("*/*")) }) { Text("Из файла (Excel)") }
                    if (target == null) OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        addDlg = false
                        emptyName = "Пустая партия " + fmtTime(System.currentTimeMillis())
                    }) { Text("Пустая партия") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        addDlg = false
                        if (!AiCfg.ready(ctx)) {
                            Toast.makeText(ctx, "ИИ не настроен: укажите ключ ИИ в настройках", Toast.LENGTH_LONG).show()
                            target = null
                        } else aiScan()
                    }) { Text("Сканирование с ИИ (отметки ручкой)") }
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { addDlg = false; target = null }) { Text("Отмена") }
                }
            }
        )
    }
    emptyName?.let { nm ->
        AlertDialog(
            onDismissRequest = { emptyName = null },
            title = { Text("Пустая партия") },
            text = {
                Column {
                    Text("Список пуст: получатель сам добавляет товары (поиск, сканер, «＋») и печатает. Переключатель партий в HTML тоже создаёт пустые партии.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    CompactField(value = nm, onChange = { emptyName = germanKeys(it) }, hint = "Название партии", modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    BatchStore.openId = BatchStore.create(ctx, nm.trim(), emptyList(), empty = true).id
                    emptyName = null
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { emptyName = null }) { Text("Отмена") } }
        )
    }
    if (aiStatus.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Сканирование с ИИ") },
            text = { Text(aiStatus) },
            confirmButton = { TextButton(onClick = { aiJob?.cancel() }) { Text("Отмена") } }
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

    // управление партией: стоп / пуск / удаление — для всех получателей или для одного
    var ctl by remember(b.id) { mutableStateOf<JSONObject?>(null) }
    var ctlOpen by remember(b.id) { mutableStateOf(false) }
    var ctlBusy by remember(b.id) { mutableStateOf(false) }
    var ctlAsk by remember(b.id) { mutableStateOf<CtlAsk?>(null) }
    val workers = b.names.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { BatchShare.normName(it) }

    fun ctlLoad() {
        if (b.link.isBlank()) return
        val app = ctx.applicationContext
        scope.launch {
            try { ctl = withContext(Dispatchers.IO) { BatchCtl.load(app, b.code) } }
            catch (e: Exception) { Toast.makeText(app, "Не удалось получить состояние: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
        }
    }
    fun ctlDo(who: String?, op: String) {
        if (ctlBusy) return
        ctlBusy = true
        val app = ctx.applicationContext
        scope.launch {
            try {
                val hash = who?.let { BatchShare.sha(BatchShare.normName(it)) }
                ctl = withContext(Dispatchers.IO) { BatchCtl.set(app, b, hash, op) }
                if (who != null && op == "del") {
                    b.names = workers.filter { it != who }.joinToString(", ")
                    BatchStore.save(app)
                }
                Toast.makeText(app, when (op) { "stop" -> "Остановлено"; "run" -> "Возобновлено"; else -> "Удалено" } +
                    ": " + (who ?: "все получатели"), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(app, "Не получилось: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            }
            ctlBusy = false
        }
    }
    LaunchedEffect(b.link) { if (b.link.isNotBlank()) ctlLoad() }

    /** Загружает партию на Диск (если не удалось — партию всё равно можно отправить файлом без ссылки), затем action(ссылка). */
    fun publish(force: Boolean = false, action: (String) -> Unit) {
        if (busy) return
        busy = true
        BatchStore.save(ctx)
        val app = ctx.applicationContext
        scope.launch {
            val link = try {
                withContext(Dispatchers.IO) { BatchShare.publish(app, b, force) }
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
                enabled = !busy && (b.rows.isNotEmpty() || b.empty) && b.names.isNotBlank(),
                modifier = Modifier.weight(1f).height(48.dp),
                onClick = { sendDlg = true }
            ) { Text(if (busy) "Загружаю…" else "Отправить") }
            OutlinedButton(
                enabled = !busy && (b.rows.isNotEmpty() || b.empty) && b.names.isNotBlank(),
                modifier = Modifier.weight(1f).height(48.dp),
                onClick = { publish { link -> if (link.isNotBlank()) qrLink = link } }
            ) { Text("QR-код") }
        }
        Spacer(Modifier.height(8.dp))
        CompactField(value = b.title, onChange = { b.title = germanKeys(it) }, hint = "Название партии", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        CompactField(value = b.names, onChange = { b.names = it }, hint = "Имена получателей (через запятую)", modifier = Modifier.fillMaxWidth())
        Text(
            "HTML откроется только с этими именами и только в рабочее время (часы задаёт администратор в Настройки → Доступ по месту и времени).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
        if (b.link.isNotBlank()) {
            OutlinedButton(
                enabled = !busy && b.names.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                onClick = { publish(true) { l -> Toast.makeText(ctx, if (l.isNotBlank()) "Доступ обновлён: новые имена сразу работают по прежней ссылке" else "Не удалось обновить", Toast.LENGTH_LONG).show() } }
            ) { Text("Обновить имена и доступ") }
        }
        if (b.link.isNotBlank()) {
            val c = ctl ?: JSONObject()
            val allSt = BatchCtl.state(c, null)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                onClick = { ctlOpen = !ctlOpen; if (ctlOpen) ctlLoad() }
            ) { Text("Управление партией · " + stText(allSt) + if (ctlOpen) " ▴" else " ▾") }
            if (ctlOpen) {
                Card(Modifier.fillMaxWidth().padding(top = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        Text("Все получатели: " + stText(allSt), fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (allSt == "run") OutlinedButton(enabled = !ctlBusy, modifier = Modifier.weight(1f), onClick = { ctlDo(null, "stop") }) { Text("Стоп") }
                            else OutlinedButton(enabled = !ctlBusy, modifier = Modifier.weight(1f), onClick = { ctlDo(null, "run") }) { Text("Пуск") }
                            OutlinedButton(enabled = !ctlBusy && allSt != "del", modifier = Modifier.weight(1f), onClick = { ctlAsk = CtlAsk(null) }) {
                                Text("Удалить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (workers.isNotEmpty()) Text("По получателям", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                        workers.forEach { n ->
                            val st = BatchCtl.state(c, BatchShare.sha(BatchShare.normName(n)))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(n, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stText(st) + if (allSt != "run" && st == "run") " (партия в целом: " + stText(allSt) + ")" else "", style = MaterialTheme.typography.bodySmall)
                                }
                                if (st == "stop") TextButton(enabled = !ctlBusy, onClick = { ctlDo(n, "run") }) { Text("Пуск") }
                                else TextButton(enabled = !ctlBusy && st == "run", onClick = { ctlDo(n, "stop") }) { Text("Стоп") }
                                TextButton(enabled = !ctlBusy && st != "del", onClick = { ctlAsk = CtlAsk(n) }) { Text("✕", color = MaterialTheme.colorScheme.error) }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        if (b.empty) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    "Пустая партия. Списка нет: получатель открывает HTML (после ввода имени), сам добавляет товары — поиском по каталогу, сканером или «＋» — считает и печатает. Кнопка «Список партии» в HTML создаёт новые партии, и каждая тоже пустая.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp)
                )
            }
            Spacer(Modifier.weight(1f))
        } else {
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
                                if (r.barcode.isNotBlank()) "ШК ${r.barcode}" else "",
                                if (r.done.isNotBlank()) "✓ найдено ${r.done}" else ""
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
    }

    ctlAsk?.let { a ->
        AlertDialog(
            onDismissRequest = { ctlAsk = null },
            title = { Text(if (a.who == null) "Удалить партию у всех?" else "Удалить партию у «${a.who}»?") },
            text = {
                Text(
                    if (a.who == null) "У всех получателей окно закроется, а данные на их устройствах сотрутся при следующем подключении. Ссылка и QR перестанут открывать партию. Вернуть доступ можно кнопкой «Пуск» для всех."
                    else "У этого получателя окно закроется, данные на его устройстве сотрутся при следующем подключении. Имя уберётся из списка получателей; чтобы вернуть его, добавьте имя снова и обновите доступ."
                )
            },
            confirmButton = { Button(onClick = { val w = a.who; ctlAsk = null; ctlDo(w, "del") }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { ctlAsk = null }) { Text("Отмена") } }
        )
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
