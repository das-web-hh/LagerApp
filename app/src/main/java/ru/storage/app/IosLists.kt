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
    BackHandler { if (viewing != null) viewing = null else onBack() }

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
                            }
                        }
                    }
                }
            }
        }
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
