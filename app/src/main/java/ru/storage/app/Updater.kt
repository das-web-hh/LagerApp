package ru.storage.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Обновление приложения через Google Диск.
 * Каждая версия лежит в настроенной папке Диска: части APK «WH-APP-<версия>-p001…» и описание «WH-APP-<версия>.json»
 * (описание грузится последним — по нему видно, что версия загружена целиком).
 */

class AppVersion(
    val version: String,
    val time: Long,
    val size: Long,
    val sha: String,
    val parts: List<String>
)

object Updater {
    private const val PREFIX = "WH-APP-"
    private const val CHUNK = 2 * 1024 * 1024
    private const val APK_MIME = "application/vnd.android.package-archive"

    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    val versions = mutableStateListOf<AppVersion>()
    var latestNewer by mutableStateOf<AppVersion?>(null)
    var checkedOnce by mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun current(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** Сравнение версий вида 2.22.1: >0, если a новее b. */
    fun cmp(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    private fun manifestName(v: String) = "$PREFIX$v.json"
    private fun partName(v: String, i: Int) = String.format(Locale.ROOT, "%s%s-p%03d", PREFIX, v, i + 1)

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unmetered(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val metered = cm.isActiveNetworkMetered
        !metered
    } catch (e: Exception) {
        true
    }

    /** Список версий на Диске и поиск более новой. Не в главном потоке. */
    fun checkNow(ctx: Context) {
        val app = ctx.applicationContext
        val names = DriveApi.searchNames(app, PREFIX).filter { it.endsWith(".json") }.distinct()
            .sortedWith { a, b -> cmp(b.removePrefix(PREFIX).removeSuffix(".json"), a.removePrefix(PREFIX).removeSuffix(".json")) }
            .take(15)
        val list = ArrayList<AppVersion>()
        for (n in names) {
            try {
                val bytes = DriveApi.download(app, n) ?: continue
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                val arr = o.getJSONArray("parts")
                val parts = (0 until arr.length()).map { arr.getString(it) }
                list.add(AppVersion(o.getString("version"), o.optLong("time"), o.optLong("size"), o.optString("sha"), parts))
            } catch (e: Exception) {
            }
        }
        val cur = current(app)
        val newer = list.filter { cmp(it.version, cur) > 0 }.maxWithOrNull { a, b -> cmp(a.version, b.version) }
        versions.clear()
        versions.addAll(list)
        latestNewer = newer
        checkedOnce = true
        app.getSharedPreferences("updater", Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply()
    }

    /** Кладёт на Диск APK этой версии (если её там ещё нет). Не в главном потоке. */
    fun publishSelf(ctx: Context) {
        val app = ctx.applicationContext
        val ver = current(app)
        if (versions.any { it.version == ver }) {
            status = "Версия $ver уже сохранена на Диске"
            return
        }
        val src = File(app.applicationInfo.sourceDir)
        val total = src.length()
        status = "Сохраняю версию $ver на Диск…"
        progress = 0f
        val sha = sha256(src)
        val parts = ArrayList<String>()
        FileInputStream(src).use { ins ->
            var idx = 0
            var done = 0L
            val buf = ByteArray(CHUNK)
            while (true) {
                var n = 0
                while (n < CHUNK) {
                    val r = ins.read(buf, n, CHUNK - n)
                    if (r < 0) break
                    n += r
                }
                if (n <= 0) break
                val name = partName(ver, idx)
                DriveApi.uploadBytes(app, name, "application/octet-stream", if (n == CHUNK) buf else buf.copyOf(n))
                parts.add(name)
                idx++
                done += n
                progress = done.toFloat() / total
                status = "Сохраняю версию $ver на Диск: ${(progress * 100).toInt()}%"
                if (n < CHUNK) break
            }
        }
        val arr = JSONArray()
        parts.forEach { arr.put(it) }
        val man = JSONObject().put("version", ver).put("time", System.currentTimeMillis())
            .put("size", total).put("sha", sha).put("parts", arr)
        DriveApi.uploadBytes(app, manifestName(ver), "application/json", man.toString().toByteArray(Charsets.UTF_8))
        status = "Версия $ver сохранена на Диске"
        progress = 1f
        checkNow(app)
    }

    /** Скачивает версию с Диска и открывает установщик. Не в главном потоке. */
    fun downloadAndInstall(ctx: Context, v: AppVersion) {
        val app = ctx.applicationContext
        val dir = File(app.cacheDir, "export/upd").also { it.mkdirs() }
        val target = File(dir, "$PREFIX${v.version}.apk")
        dir.listFiles()?.forEach { if (it != target) it.delete() }
        progress = 0f
        val ready = target.exists() && target.length() == v.size && (v.sha.isEmpty() || sha256(target) == v.sha)
        if (!ready) {
            target.delete()
            var done = 0L
            target.outputStream().use { out ->
                v.parts.forEachIndexed { i, name ->
                    status = "Скачиваю версию ${v.version}: часть ${i + 1} из ${v.parts.size}"
                    val b = DriveApi.download(app, name) ?: throw RuntimeException("На Диске нет части $name")
                    out.write(b)
                    done += b.size
                    progress = if (v.size > 0) (done.toFloat() / v.size).coerceAtMost(1f) else 0f
                }
            }
            if (v.sha.isNotEmpty() && sha256(target) != v.sha) {
                target.delete()
                throw RuntimeException("Файл скачан с ошибкой, попробуйте ещё раз")
            }
        }
        progress = 1f
        install(app, target)
    }

    private fun install(app: Context, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            status = "Файл скачан. Разрешите установку из этого приложения и нажмите «Установить» ещё раз"
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
        app.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        status = "Открыт установщик"
    }

    private fun run(block: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                status = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun check(ctx: Context) = run {
        if (!DriveCfg.ready(ctx)) {
            status = "Google Диск не настроен (Еще → Настройки)"
            return@run
        }
        status = "Проверяю обновления…"
        checkNow(ctx)
        status = latestNewer?.let { "Доступна версия ${it.version}" } ?: "У вас последняя версия"
    }

    fun publish(ctx: Context) = run {
        if (!DriveCfg.ready(ctx)) {
            status = "Google Диск не настроен (Еще → Настройки)"
            return@run
        }
        checkNow(ctx)
        publishSelf(ctx)
    }

    fun install(ctx: Context, v: AppVersion) = run { downloadAndInstall(ctx, v) }

    /** При запуске: раз в 6 часов проверяем Диск и, если этой версии там нет, сохраняем её (только не по мобильному интернету). */
    fun auto(ctx: Context) {
        val app = ctx.applicationContext
        if (!DriveCfg.ready(app) || busy) return
        val last = app.getSharedPreferences("updater", Context.MODE_PRIVATE).getLong("last", 0L)
        if (System.currentTimeMillis() - last < 6 * 3600_000L) return
        busy = true
        try {
            checkNow(app)
            // сохранять версию на Диск может только администратор
            if (Session.isAdmin && versions.none { it.version == current(app) } && unmetered(app)) publishSelf(app)
        } catch (e: Exception) {
        } finally {
            busy = false
        }
    }
}

private val verDate = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

@Composable
fun UpdateScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val cur = remember { Updater.current(ctx) }
    LaunchedEffect(Unit) { if (!Updater.checkedOnce && !Updater.busy) Updater.check(ctx) }
    val newer = Updater.latestNewer
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
        }
        Text("Обновление", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Установлена версия $cur", style = MaterialTheme.typography.titleMedium)
                Text(
                    newer?.let { "Доступна новая версия ${it.version}" } ?: if (Updater.checkedOnce) "Это последняя версия" else "Проверка…",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (Updater.status.isNotEmpty()) Text(Updater.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (Updater.busy && Updater.progress > 0f) {
                    LinearProgressIndicator(progress = { Updater.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val admin = Session.isAdmin
        if (newer != null && admin) {
            Button(
                onClick = { Updater.install(ctx, newer) },
                enabled = !Updater.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Скачать и установить ${newer.version}") }
            Spacer(Modifier.height(8.dp))
        } else if (newer != null) {
            Text(
                "Установить новую версию может только администратор.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = { Updater.check(ctx) }, enabled = !Updater.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Проверить обновления")
        }
        if (!admin) {
            Spacer(Modifier.height(24.dp))
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { Updater.publish(ctx) }, enabled = !Updater.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Сохранить эту версию на Диск")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Версии хранятся в папке Google Диска, настроенной в «Настройки → Google Диск». Приложение само сохраняет туда свою версию (по Wi-Fi) и проверяет, нет ли новее.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        Text("Версии на Диске", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (Updater.versions.isEmpty()) {
            Text("Пока нет сохранённых версий", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        Updater.versions.forEach { v ->
            val mark = when {
                v.version == cur -> "установлена"
                Updater.cmp(v.version, cur) > 0 -> "новее"
                else -> "старее"
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Версия ${v.version} · $mark", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            verDate.format(Date(v.time)).takeIf { v.time > 0 },
                            String.format(Locale.getDefault(), "%.1f МБ", v.size / 1048576.0)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            HorizontalDivider()
        }
        Spacer(Modifier.height(24.dp))
    }
}
