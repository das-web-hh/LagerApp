package ru.storage.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * «Списки с iPhone»: iPhone работает через браузер и при сбое печати отправляет список файлом LGRP-<устройство>-<дата>-<время>-<код>.html
 * (кнопка «Отправить» рядом с «Печать»). Файл приходит любым способом (WhatsApp, почта, Telegram…) и сохраняется на телефон.
 * Здесь такие файлы ищутся по имени во всей памяти телефона: можно открыть (только просмотр), напечатать и удалить с телефона.
 */
object IosLists {
    const val PREFIX = "LGRP-"
    private val okExt = setOf("html", "htm", "txt")

    class Item(val file: File, val title: String, val user: String, val sentAt: Long, val rows: Int, val qty: Double, val kind: String)

    /** Открытый для просмотра документ (из списка, по уведомлению или «Открыть через…» из файлового менеджера). */
    class Doc(val name: String, val title: String, val user: String, val sentAt: Long, val kind: String, val html: String)

    /** Нажатие на уведомление или открытие файла: главный экран переключается на «Прочее → Списки с iPhone». */
    var wantOpen by mutableStateOf(false)
    /** Документ, который надо сразу показать (файл открыт из файлового менеджера, почты и т.п.). */
    var pendingDoc by mutableStateOf<Doc?>(null)

    fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("ios_open", false) == true) wantOpen = true
    }

    /**
     * Файл LGRP-… открыт через приложение («Открыть через…», «Поделиться»). true — это наш файл и он принят
     * (дальше его как партию, PDF или фото обрабатывать не надо). Вызывать не в главном потоке.
     */
    fun takeUri(ctx: Context, uri: Uri): Boolean {
        val app = ctx.applicationContext
        val name = (try {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }) ?: uri.lastPathSegment?.substringAfterLast('/') ?: return false
        if (!name.startsWith(PREFIX, ignoreCase = true)) return false
        val text = try { app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (e: Exception) { null }
        Handler(Looper.getMainLooper()).post {
            if (text.isNullOrBlank()) Toast.makeText(app, "Не удалось прочитать файл", Toast.LENGTH_LONG).show()
            else {
                pendingDoc = docOf(name, text, System.currentTimeMillis())
                wantOpen = true
            }
        }
        return true
    }

    /** Есть ли доступ к памяти: Android 11+ — «Доступ ко всем файлам», раньше — обычное разрешение на память. */
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun request(ctx: Context, settings: ActivityResultLauncher<Intent>, perm: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                settings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            } catch (e: Exception) {
                try { settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) { }
            }
        } else perm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    /** true — разрешение ещё не спрашивали в этой версии приложения (после установки и после каждого обновления спрашиваем один раз). */
    fun markAsked(ctx: Context): Boolean {
        val p = ctx.applicationContext.getSharedPreferences("ioslists", Context.MODE_PRIVATE)
        val v = InvLog.versionName(ctx)
        if (p.getString("asked", "") == v) return false
        p.edit().putString("asked", v).apply()
        return true
    }

    /** Поиск файлов по имени во всей памяти (кроме Android/data и скрытых папок). Не в главном потоке. */
    fun scan(): List<Item> {
        val found = ArrayList<File>()
        val stack = ArrayList<Pair<File, Int>>()
        stack.add(Pair(Environment.getExternalStorageDirectory(), 0))
        var visited = 0
        val deadline = System.currentTimeMillis() + 20_000L
        while (stack.isNotEmpty() && visited < 80_000 && System.currentTimeMillis() < deadline) {
            val cur = stack.removeAt(stack.size - 1)
            val list = (try { cur.first.listFiles() } catch (e: Exception) { null }) ?: continue
            for (f in list) {
                visited++
                val n = f.name
                if (f.isDirectory) {
                    if (cur.second >= 8 || n.startsWith(".")) continue
                    val path = f.absolutePath
                    if (path.endsWith("/Android/data") || path.endsWith("/Android/obb")) continue
                    stack.add(Pair(f, cur.second + 1))
                } else if (n.startsWith(PREFIX, ignoreCase = true) && n.substringAfterLast('.', "").lowercase() in okExt) {
                    found.add(f)
                }
            }
        }
        return found.mapNotNull { parse(it) }.sortedByDescending { it.sentAt }
    }

    private fun meta(text: String): JSONObject? {
        val m = Regex("id=\"lagerp\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(text) ?: return null
        return try { JSONObject(m.groupValues[1]) } catch (e: Exception) { null }
    }

    private fun docOf(name: String, text: String, modified: Long): Doc {
        val o = meta(text)
        val base = name.substringBeforeLast('.')
        return Doc(
            name, o?.optString("title")?.ifBlank { base } ?: base, o?.optString("user").orEmpty(),
            o?.optLong("at")?.takeIf { it > 0L } ?: modified, o?.optString("kind").orEmpty(), text
        )
    }

    private fun parse(f: File): Item? {
        if (f.length() > 30_000_000L) return null
        val text = try { f.readText() } catch (e: Exception) { return null }
        val o = meta(text) ?: return Item(f, f.nameWithoutExtension, "", f.lastModified(), 0, 0.0, "")
        val rows = o.optJSONArray("rows")
        var qty = 0.0
        if (rows != null) for (i in 0 until rows.length()) qty += rows.optJSONObject(i)?.optDouble("q", 0.0) ?: 0.0
        return Item(
            f, o.optString("title").ifBlank { f.nameWithoutExtension }, o.optString("user"),
            o.optLong("at").takeIf { it > 0L } ?: f.lastModified(), rows?.length() ?: 0, qty, o.optString("kind")
        )
    }

    fun docOf(i: Item): Doc? {
        val h = htmlOf(i)
        return if (h.isBlank()) null else Doc(i.file.name, i.title, i.user, i.sentAt, i.kind, h)
    }

    fun htmlOf(i: Item): String = try { i.file.readText() } catch (e: Exception) { "" }

    // ---------- запись файла в журнал на Диске (только администратор) ----------
    private val idRe = Regex("^[A-Za-z0-9]{6,40}$")

    /** Что будет записано: готовые запросы action=log (без токена), автор файла, время снимка и итоги для окна подтверждения. */
    class ImpPlan(
        val bodies: List<JSONObject>, val user: String, val at: Long, val code: String, val title: String,
        val kind: String, val rows: Int, val qty: Double, val parts: Int, val v2: Boolean
    )

    /** Результат последней записи файла (хранится на телефоне). */
    class ImpState(val at: Long, val ok: Boolean, val msg: String)

    fun kindName(k: String) = when (k) { "bware" -> "B-Ware"; "expired" -> "Abgelaufene Ware"; else -> "Inventur" }

    /**
     * Разбор файла для записи в базу. v:2 — берётся готовый "log"; v:1 (нет "log") — части собираются из rows:
     * k = imp_<хеш имени файла>, t0 = t1 = время файла, box = Inventur. Для кода APPINV строки делятся по дням (код APPINV+yyyyMMdd, Берлин).
     * Не в главном потоке. Бросает IllegalStateException с понятной причиной.
     */
    fun planOf(name: String, text: String, modified: Long): ImpPlan {
        val o = meta(text) ?: throw IllegalStateException("в файле нет данных списка (блок lagerp)")
        val at = o.optLong("at").takeIf { it > 0L } ?: modified
        val user = o.optString("user")
        val title = o.optString("title").ifBlank { name.substringBeforeLast('.') }
        val kind = o.optString("kind")
        // "logs" — файл «Отправить всё» (несколько заданий), "log" — одно задание/партия, иначе файл v:1
        val bases = ArrayList<JSONObject>()
        val lgs = o.optJSONArray("logs")
        val lg = o.optJSONObject("log")
        if (lgs != null && lgs.length() > 0) {
            for (i in 0 until lgs.length()) lgs.optJSONObject(i)?.let { bases.add(JSONObject(it.toString())) }
        } else if (lg != null) {
            bases.add(JSONObject(lg.toString()))
        } else {
            val src = o.optJSONArray("rows")
            val rows = JSONArray()
            if (src != null) for (i in 0 until src.length()) {
                val r = src.optJSONObject(i) ?: continue
                val q = r.optDouble("q", 0.0)
                if (q <= 0.0 || r.optString("n").isBlank()) continue
                rows.put(
                    JSONObject().put("n", r.optString("n").trim().take(200)).put("a", r.optString("a")).put("b", r.optString("b"))
                        .put("e", r.optString("e")).put("q", q).put("t0", at).put("t1", at).put("ad", 0L).put("x", 0)
                )
            }
            if (rows.length() == 0) throw IllegalStateException("в файле нет строк")
            val dev = o.optString("dev").takeIf { idRe.matches(it) } ?: BatchShare.sha(BatchShare.normName(user.ifBlank { name })).take(32)
            bases.add(
                JSONObject().put("action", "log").put("code", o.optString("code")).put("dev", dev).put("name", user.take(40))
                    .put("src", "h").put("ver", "")
                    .put("parts", JSONArray().put(JSONObject().put("k", "imp_" + BatchShare.sha(name).take(16)).put("box", "Inventur").put("rows", rows)))
            )
        }
        if (bases.isEmpty()) throw IllegalStateException("в файле нет журнала")
        val bodies = ArrayList<JSONObject>()
        val codes = ArrayList<String>()
        for (base in bases) {
            val code = base.optString("code")
            if (!idRe.matches(code)) throw IllegalStateException("в файле нет кода партии")
            if (!idRe.matches(base.optString("dev"))) throw IllegalStateException("в файле нет кода устройства")
            val parts = base.optJSONArray("parts") ?: throw IllegalStateException("в файле нет частей журнала")
            if (code !in codes) codes.add(code)
            if (code.startsWith(InvLog.APP_CODE)) {
                // инвентаризация в приложении: файл дня = день первого ввода строки (как в InvLog)
                val byDay = LinkedHashMap<String, JSONArray>()
                for (pi in 0 until parts.length()) {
                    val p = parts.optJSONObject(pi) ?: continue
                    val rs = p.optJSONArray("rows") ?: continue
                    val perDay = LinkedHashMap<String, JSONArray>()
                    for (ri in 0 until rs.length()) {
                        val r = rs.optJSONObject(ri) ?: continue
                        val t0 = r.optLong("t0").takeIf { it > 0L } ?: r.optLong("ad").takeIf { it > 0L } ?: at
                        perDay.getOrPut(InvLog.dayOf(t0)) { JSONArray() }.put(r)
                    }
                    for ((d, arr) in perDay) byDay.getOrPut(d) { JSONArray() }.put(JSONObject().put("k", p.optString("k")).put("box", p.optString("box")).put("rows", arr))
                }
                for ((d, ps) in byDay) bodies.add(JSONObject(base.toString()).put("code", InvLog.APP_CODE + d).put("parts", ps))
            } else bodies.add(base)
        }
        var rowsN = 0
        var qty = 0.0
        var partsN = 0
        for (b in bodies) {
            val ps = b.optJSONArray("parts") ?: continue
            partsN += ps.length()
            for (i in 0 until ps.length()) {
                val rs = ps.optJSONObject(i)?.optJSONArray("rows") ?: continue
                rowsN += rs.length()
                for (j in 0 until rs.length()) qty += rs.optJSONObject(j)?.optDouble("q", 0.0) ?: 0.0
            }
        }
        if (rowsN == 0) throw IllegalStateException("в файле нет строк")
        val who = user.ifBlank { bases[0].optString("name") }
        return ImpPlan(bodies, who, at, codes.joinToString(", "), title, kind, rowsN, qty, partsN, lg != null || lgs != null)
    }

    private fun impPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("ioslists_imp", Context.MODE_PRIVATE)

    /** Записывался ли файл в базу с этого телефона (по имени файла); null — нет. */
    fun impState(ctx: Context, name: String): ImpState? {
        val s = impPrefs(ctx).getString("f_$name", null) ?: return null
        return try { val o = JSONObject(s); ImpState(o.optLong("at"), o.optBoolean("ok"), o.optString("msg")) } catch (e: Exception) { null }
    }

    private fun impErr(r: JSONObject): String = when (r.optString("error")) {
        "blocked" -> if (r.optString("state") == "del") "администратор удалил партию или доступ" else "партия или работник остановлены"
        "limit", "stale", "foreign" -> r.optString("message").ifBlank { r.optString("error") }
        else -> r.optString("error").ifBlank { "ошибка Диска" }
    }

    private fun impNetErr(e: Exception): String =
        if (e is java.io.IOException) "нет сети или сервер недоступен (${e.message ?: e.javaClass.simpleName})" else (e.message ?: e.javaClass.simpleName)

    /**
     * Запись в журнал на Диске от имени работника (action=log, токен администратора, imp=1). Ничего не глотает:
     * итог (в том числе ошибка) возвращается и запоминается. Файл не удаляется. Повторная запись заменяет ту же часть.
     */
    suspend fun doImport(ctx: Context, name: String, plan: ImpPlan, onProgress: (Int, Int) -> Unit = { _, _ -> }): ImpState {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        var written = 0
        var fatal = ""
        val failed = ArrayList<String>()   // «код (причина)» по заданиям, которые сервер не принял
        var failedRows = 0
        if (!DriveCfg.ready(app)) fatal = "Google Диск не настроен"
        else for ((idx, b) in plan.bodies.withIndex()) {
            onProgress(idx, plan.bodies.size)
            var n = 0
            val ps = b.optJSONArray("parts")
            if (ps != null) for (i in 0 until ps.length()) n += ps.optJSONObject(i)?.optJSONArray("rows")?.length() ?: 0
            try {
                b.put("token", DriveCfg.token(app)).put("imp", 1).put("impBy", Session.displayName).put("impAt", now).put("at", plan.at)
                val r = withContext(Dispatchers.IO) { DriveApi.batchLog(app, b) }
                if (r.optBoolean("ok")) written += n
                else {
                    // ошибка одного задания (партия удалена, свежие данные и т.п.) не мешает остальным
                    failedRows += n
                    val why = impErr(r)
                    val label = b.optString("code") + " (" + why + ")"
                    if (label !in failed) failed.add(label)
                }
            } catch (e: Exception) {
                fatal = impNetErr(e)   // нет сети: дальше пробовать бессмысленно
                break
            }
        }
        val rest = if (fatal.isNotEmpty()) fatal else failed.take(4).joinToString("; ") + (if (failed.size > 4) "; и ещё ${failed.size - 4}" else "")
        val ok = fatal.isEmpty() && failed.isEmpty()
        val msg = when {
            ok -> "Записано в базу: $written строк"
            written > 0 -> "Записано: $written из ${plan.rows} строк. Не записано: $rest"
            else -> "Не записано: $rest"
        }
        val st = ImpState(now, ok, msg)
        try { impPrefs(app).edit().putString("f_$name", JSONObject().put("at", now).put("ok", ok).put("msg", msg).toString()).apply() } catch (e: Exception) { }
        return st
    }

    // ---------- уведомление о новом файле ----------
    private const val JOB_ID = 7311
    private const val CH = "ioslists"

    /** Файлы LGRP-… по индексу медиатеки (быстро, без обхода папок). Нужен доступ ко всем файлам. */
    private fun indexed(ctx: Context): List<File> {
        val out = ArrayList<File>()
        try {
            ctx.contentResolver.query(
                MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns.DATA),
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", arrayOf("$PREFIX%"), MediaStore.MediaColumns.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    val f = File(path)
                    if (f.isFile && f.name.substringAfterLast('.', "").lowercase() in okExt) out.add(f)
                }
            }
        } catch (e: Exception) { }
        return out
    }

    private fun keyOf(f: File) = "ios_" + f.absolutePath + "_" + f.length()

    /** Всё, что уже лежит на телефоне, считается «старым»: уведомления только о файлах, которые придут после этого. */
    private fun seed(ctx: Context) {
        for (f in indexed(ctx)) Notifier.once(ctx, keyOf(f))
    }

    /** Показывает уведомление о каждом новом полностью загруженном файле (по нажатию открывается «Списки с iPhone»). */
    fun notifyNew(ctx: Context) {
        if (!hasAccess(ctx)) return
        for (f in indexed(ctx)) {
            if (f.length() > 30_000_000L) continue
            val text = try { f.readText() } catch (e: Exception) { continue }
            // файл ещё докачивается: ждём следующего изменения, пока не появится конец документа
            if (!text.contains("</html>", ignoreCase = true) && !text.contains("id=\"lagerp\"")) continue
            if (!Notifier.once(ctx, keyOf(f))) continue
            val d = docOf(f.name, text, f.lastModified())
            val who = (iosKind(d.kind) + (if (d.user.isNotBlank()) d.user + " · " else "")).trim()
            show(ctx, keyOf(f), "Новый список с iPhone", (d.title + if (who.isNotBlank()) "\n$who" else "").trim())
        }
    }

    private fun show(ctx: Context, key: String, title: String, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val sys = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && sys.getNotificationChannel(CH) == null) {
            val ch = NotificationChannel(CH, "Списки с iPhone", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Пришёл файл со списком для печати"
            sys.createNotificationChannel(ch)
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            putExtra("ios_open", true)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return
        val pi = PendingIntent.getActivity(ctx, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        try { nm.notify(key.hashCode(), n) } catch (e: SecurityException) { }
    }

    /**
     * Следит за появлением новых файлов в памяти (работает и когда приложение закрыто). Задание одноразовое:
     * после срабатывания ставится заново. Вызывать при запуске приложения, после выдачи доступа и после перезагрузки телефона.
     */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        try {
            if (!hasAccess(app)) return
            val p = app.getSharedPreferences("ioslists", Context.MODE_PRIVATE)
            if (!p.getBoolean("seeded", false)) {
                p.edit().putBoolean("seeded", true).apply()
                Thread { try { seed(app) } catch (e: Exception) { } }.start()
            }
            val js = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val info = JobInfo.Builder(JOB_ID, ComponentName(app, IosListsJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Files.getContentUri("external"), JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(3000)
                .setTriggerContentMaxDelay(15000)
                .build()
            js.schedule(info)
        } catch (e: Exception) { }
    }

    /** Удаляет файл с телефона. true — файла больше нет. */
    fun delete(ctx: Context, i: Item): Boolean {
        try { i.file.delete() } catch (e: Exception) { }
        val gone = !i.file.exists()
        if (gone) try { MediaScannerConnection.scanFile(ctx.applicationContext, arrayOf(i.file.absolutePath), null, null) } catch (e: Exception) { }
        return gone
    }
}

/** Срабатывает, когда в памяти телефона что-то изменилось: если пришёл новый файл LGRP-…, показывает уведомление. */
class IosListsJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try { IosLists.notifyNew(app) } catch (e: Exception) { }
            jobFinished(params, false)
            IosLists.schedule(app)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

private fun iosTime(t: Long) = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).apply { timeZone = TimeZone.getTimeZone("Europe/Berlin") }.format(Date(t))
private fun iosQty(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
private fun iosShort(t: Long) = SimpleDateFormat("dd.MM HH:mm", Locale.GERMANY).apply { timeZone = TimeZone.getTimeZone("Europe/Berlin") }.format(Date(t))
private fun iosKind(k: String) = when (k) { "bware" -> "B-Ware · "; "expired" -> "Abgelaufene Ware · "; else -> "" }

/** «Прочее → Списки с iPhone»: найденные файлы. Только просмотр, печать и удаление — менять нельзя. */
@Composable
fun IosListsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(IosLists.hasAccess(ctx)) }
    var list by remember { mutableStateOf<List<IosLists.Item>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<IosLists.Doc?>(null) }
    var delItem by remember { mutableStateOf<IosLists.Item?>(null) }
    var impAsk by remember { mutableStateOf<Pair<String, IosLists.ImpPlan>?>(null) }
    var impBusy by remember { mutableStateOf(false) }
    var impMsg by remember { mutableStateOf("") }
    var impTick by remember { mutableStateOf(0) }
    var impProg by remember { mutableStateOf("") }
    val admin = Session.isAdmin
    BackHandler { if (viewing != null) viewing = null else onBack() }

    /** «Записать в базу»: разбор файла (не в главном потоке) и окно подтверждения; ошибка разбора показывается текстом. */
    fun askImport(name: String, modified: Long, html: () -> String) {
        impMsg = ""
        scope.launch {
            try {
                impAsk = name to withContext(Dispatchers.IO) { IosLists.planOf(name, html(), modified) }
            } catch (e: Exception) {
                impMsg = "Не записано: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun reload() {
        if (busy) return
        busy = true
        msg = ""
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { IosLists.scan() }
                list = r
                msg = if (r.isEmpty()) "Файлов LGRP-… в памяти телефона не найдено." else "Найдено файлов: ${r.size}"
            } catch (e: Exception) {
                msg = "Не удалось просмотреть память: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access = IosLists.hasAccess(ctx)
        if (access) reload()
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        access = IosLists.hasAccess(ctx)
        if (access) reload()
    }
    // файл открыт из файлового менеджера / почты — сразу показываем его
    LaunchedEffect(IosLists.pendingDoc) {
        IosLists.pendingDoc?.let { viewing = it; IosLists.pendingDoc = null }
    }
    LaunchedEffect(access) { if (access) IosLists.schedule(ctx) }
    LaunchedEffect(Unit) {
        if (access) reload() else {
            IosLists.markAsked(ctx)
            IosLists.request(ctx, settings, perm)
        }
    }

    val v = viewing
    if (v != null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewing = null }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
                Text(v.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
            Text(iosKind(v.kind) + (if (v.user.isNotBlank()) v.user + " · " else "") + iosTime(v.sentAt), style = MaterialTheme.typography.bodySmall)
            Text(v.name, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = v.html.isNotBlank(),
                onClick = { Printer.print(ctx, v.name.substringBeforeLast('.'), v.html, record = false) }
            ) { Text("Печать") }
            if (admin) {
                val st = remember(impTick, v.name) { IosLists.impState(ctx, v.name) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(), enabled = v.html.isNotBlank() && !impBusy,
                    onClick = { askImport(v.name, v.sentAt) { v.html } }
                ) { Text("Записать в базу") }
                if (st != null && st.ok) Text("Записано " + iosShort(st.at), style = MaterialTheme.typography.bodySmall)
                if (impMsg.isNotBlank()) Text(impMsg, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            HtmlPreview(v.html, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
                Text("Списки с iPhone", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Файлы LGRP-…, которые прислали с iPhone (кнопка «Отправить» рядом с «Печать»). Их можно открыть, напечатать или удалить; изменить нельзя.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            if (!access) {
                Text("Нужен доступ к памяти телефона, чтобы найти присланные файлы.")
                Spacer(Modifier.height(6.dp))
                Button(modifier = Modifier.fillMaxWidth(), onClick = { IosLists.request(ctx, settings, perm) }) { Text("Разрешить доступ к памяти") }
            } else {
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { reload() }) { Text(if (busy) "Ищу…" else "Обновить список") }
                if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                if (impMsg.isNotBlank()) Text(impMsg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(list, key = { it.file.absolutePath }) { i ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(i.title, fontWeight = FontWeight.Bold, maxLines = 2)
                                Text(iosKind(i.kind) + (if (i.user.isNotBlank()) i.user + " · " else "") + iosTime(i.sentAt), style = MaterialTheme.typography.bodySmall)
                                if (i.rows > 0) Text("Позиций: ${i.rows} · Stück: ${iosQty(i.qty)}", style = MaterialTheme.typography.bodySmall)
                                Text(i.file.name, style = MaterialTheme.typography.bodySmall)
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(contentPadding = PaddingValues(horizontal = 10.dp), onClick = {
                                        scope.launch {
                                            val d = withContext(Dispatchers.IO) { IosLists.docOf(i) }
                                            if (d == null) { msg = "Не удалось прочитать файл." } else { viewing = d }
                                        }
                                    }) { Text("Открыть") }
                                    OutlinedButton(contentPadding = PaddingValues(horizontal = 10.dp), onClick = {
                                        scope.launch {
                                            val d = withContext(Dispatchers.IO) { IosLists.docOf(i) }
                                            if (d == null) { msg = "Не удалось прочитать файл." } else Printer.print(ctx, i.file.nameWithoutExtension, d.html, record = false)
                                        }
                                    }) { Text("Печать") }
                                    TextButton(contentPadding = PaddingValues(horizontal = 10.dp), onClick = { delItem = i }) { Text("Удалить") }
                                }
                                if (admin) {
                                    val st = remember(impTick, i.file.name) { IosLists.impState(ctx, i.file.name) }
                                    if (st != null) Text(
                                        if (st.ok) "Записано " + iosShort(st.at) else st.msg,
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold
                                    )
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp), enabled = !impBusy,
                                        onClick = { askImport(i.file.name, i.sentAt) { IosLists.htmlOf(i) } }
                                    ) { Text("Записать в базу") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    impAsk?.let { (nm, p) ->
        val prev = IosLists.impState(ctx, nm)
        AlertDialog(
            onDismissRequest = { if (!impBusy) impAsk = null },
            title = { Text("Записать в базу?") },
            text = {
                Text(
                    "Прислал: " + p.user.ifBlank { "—" } + "\nКогда: " + iosTime(p.at) +
                        "\nПартия: " + p.title + " (" + p.code + ")" + "\nТип: " + IosLists.kindName(p.kind) +
                        "\nСтрок: " + p.rows + " · Stück: " + iosQty(p.qty) + "\nПаллет/частей: " + p.parts +
                        (if (prev != null && prev.ok) "\n\nВнимание: уже записано " + iosShort(prev.at) + ". Повторная запись заменит ту же часть, дублей не будет." else "") +
                        "\n\nДанные попадут в журнал как отправленные самим работником. Когда у него появится интернет, его отправка заменит эту запись."
                )
            },
            confirmButton = {
                Button(enabled = !impBusy, onClick = {
                    impBusy = true
                    impProg = ""
                    scope.launch {
                        try {
                            val st = IosLists.doImport(ctx, nm, p) { i, n -> impProg = " " + (i + 1) + "/" + n }
                            impMsg = st.msg
                            impTick++
                        } finally {
                            impBusy = false
                            impAsk = null
                        }
                    }
                }) { Text(if (impBusy) "Пишу…" + impProg else "Записать в базу") }
            },
            dismissButton = { TextButton(enabled = !impBusy, onClick = { impAsk = null }) { Text("Отмена") } }
        )
    }

    delItem?.let { d ->
        AlertDialog(
            onDismissRequest = { delItem = null },
            title = { Text("Удалить файл?") },
            text = { Text(d.file.name + "\nФайл будет удалён с телефона.") },
            confirmButton = {
                Button(onClick = {
                    delItem = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { IosLists.delete(ctx, d) }
                        if (ok) {
                            list = list.filter { it.file.absolutePath != d.file.absolutePath }
                            msg = "Файл удалён."
                        } else msg = "Не удалось удалить файл (нет доступа)."
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { delItem = null }) { Text("Отмена") } }
        )
    }
}
