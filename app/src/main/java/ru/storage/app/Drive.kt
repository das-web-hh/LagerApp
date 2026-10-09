package ru.storage.app

import android.content.Context
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** Настройки Google Диска (адрес скрипта, папка, токен) — хранятся на телефоне. */
object DriveCfg {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("drive", Context.MODE_PRIVATE)
    fun url(ctx: Context): String = sp(ctx).getString("url", "")!!.trim()
    fun folder(ctx: Context): String = sp(ctx).getString("folder", "")!!.trim()
    fun token(ctx: Context): String = sp(ctx).getString("token", "")!!.trim()
    fun ready(ctx: Context): Boolean = url(ctx).isNotEmpty() && folder(ctx).isNotEmpty()

    fun save(ctx: Context, url: String, folder: String, token: String) {
        var f = folder.trim()
        if (f.contains("/folders/")) f = f.substringAfter("/folders/").substringBefore('?').substringBefore('/')
        sp(ctx).edit().putString("url", url.trim()).putString("folder", f)
            .putString("token", token.trim()).apply()
    }
}

class DrivePage(val names: List<String>, val next: String?)

/** Запросы к Google Apps Script (Web App), который работает с папкой на Google Диске. */
object DriveApi {
    private val OURS = Regex("^(PR|RC|IN)-.+")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** GET/POST с ручным переходом по перенаправлениям (Apps Script отвечает на POST кодом 302). */
    private fun http(url: String, body: String?): JSONObject {
        var target = url
        var method = if (body != null) "POST" else "GET"
        var payload = body
        repeat(6) {
            val c = URL(target).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 20000
                c.readTimeout = 180000
                c.instanceFollowRedirects = false
                c.requestMethod = method
                if (payload != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    c.outputStream.use { it.write(payload!!.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val loc = c.getHeaderField("Location")
                        ?: throw RuntimeException("Перенаправление без адреса (HTTP $code)")
                    target = URL(URL(target), loc).toString()
                    if (code != 307 && code != 308) {
                        method = "GET"
                        payload = null
                    }
                    return@repeat
                }
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                try {
                    return JSONObject(text)
                } catch (e: Exception) {
                    val snippet = text.replace(Regex("\\s+"), " ").take(120)
                    throw RuntimeException("Скрипт Диска вернул не JSON (HTTP $code): $snippet")
                }
            } finally {
                c.disconnect()
            }
        }
        throw RuntimeException("Слишком много перенаправлений")
    }

    private fun query(ctx: Context, action: String, vararg kv: Pair<String, String>): String {
        val sb = StringBuilder(DriveCfg.url(ctx))
        sb.append("?action=").append(enc(action))
        sb.append("&folderId=").append(enc(DriveCfg.folder(ctx)))
        val t = DriveCfg.token(ctx)
        if (t.isNotEmpty()) sb.append("&token=").append(enc(t))
        kv.forEach { sb.append("&").append(it.first).append("=").append(enc(it.second)) }
        return sb.toString()
    }

    private fun check(r: JSONObject): JSONObject {
        if (!r.optBoolean("ok")) throw RuntimeException(r.optString("error", "ошибка Диска"))
        return r
    }

    fun ping(ctx: Context): String {
        val r = check(http(query(ctx, "ping"), null))
        return r.optString("folderName")
    }

    fun upload(ctx: Context, name: String, f: File) {
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val o = JSONObject()
            .put("action", "upload")
            .put("folderId", DriveCfg.folder(ctx))
            .put("token", DriveCfg.token(ctx))
            .put("fileName", name)
            .put("mimeType", mime)
            .put("skipIfExists", true)
            .put("makePublic", false)
            .put("contentBase64", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        check(http(DriveCfg.url(ctx), o.toString()))
    }

    /** Содержимое файла по имени; null, если файла на Диске нет. */
    fun download(ctx: Context, name: String): ByteArray? {
        val r = http(query(ctx, "get", "name" to name), null)
        if (!r.optBoolean("ok")) {
            if (r.optString("error") == "not_found") return null
            throw RuntimeException(r.optString("error", "ошибка Диска"))
        }
        return Base64.decode(r.getString("contentBase64"), Base64.DEFAULT)
    }

    fun delete(ctx: Context, name: String) {
        val o = JSONObject()
            .put("action", "delete")
            .put("folderId", DriveCfg.folder(ctx))
            .put("token", DriveCfg.token(ctx))
            .put("fileName", name)
        check(http(DriveCfg.url(ctx), o.toString()))
    }

    /** Страница списка файлов, новые сверху. */
    fun page(ctx: Context, token: String?, size: Int): DrivePage {
        val kv = ArrayList<Pair<String, String>>()
        kv.add("pageSize" to size.toString())
        if (!token.isNullOrEmpty()) kv.add("pageToken" to token)
        val r = check(http(query(ctx, "page", *kv.toTypedArray()), null))
        val arr = r.optJSONArray("files")
        val names = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i).optString("name")
            if (OURS.matches(n)) names.add(n)
        }
        val next = r.optString("nextPageToken", "")
        return DrivePage(names, if (next.isEmpty()) null else next)
    }
}

/** Синхронизация файлов с Диска: первый раз — всё, дальше — по 10 новых, пока есть новые. */
object DriveSync {
    var status by mutableStateOf("")
    private val running = AtomicBoolean(false)
    @Volatile private var last = 0L
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(ctx: Context, force: Boolean) {
        val app = ctx.applicationContext
        if (!DriveCfg.ready(app)) {
            status = "Google Диск не настроен (Профиль → Настройки)"
            return
        }
        if (!force && System.currentTimeMillis() - last < 5 * 60 * 1000L) return
        if (!running.compareAndSet(false, true)) return
        bg.launch {
            try {
                sync(app)
                last = System.currentTimeMillis()
            } catch (e: Exception) {
                status = "Диск: ${e.message}"
            } finally {
                running.set(false)
            }
        }
    }

    private fun sync(ctx: Context) {
        val sp = ctx.getSharedPreferences("drive", Context.MODE_PRIVATE)
        val first = !sp.getBoolean("first_done", false)
        var token: String? = null
        var loaded = 0
        status = if (first) "Диск: первая загрузка…" else "Диск: проверка…"
        while (true) {
            val page = DriveApi.page(ctx, token, if (first) 100 else 10)
            val missing = page.names.filter { !Media.file(ctx, it).exists() }
            for (n in missing) {
                status = "Диск: загрузка файлов… $loaded"
                val bytes = DriveApi.download(ctx, n)
                if (bytes != null) {
                    Media.file(ctx, n).writeBytes(bytes)
                    loaded++
                }
            }
            token = page.next
            if (token == null) break
            if (!first && missing.isEmpty()) break
        }
        if (first) sp.edit().putBoolean("first_done", true).apply()
        status = if (loaded > 0) "Диск: загружено файлов $loaded" else "Диск: всё актуально"
    }
}
