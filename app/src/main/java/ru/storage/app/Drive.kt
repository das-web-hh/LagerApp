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
import java.io.RandomAccessFile
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
    private val OURS = Regex("^(PR|RC|IN|BW)-.+")

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

    /** Размер куска при отправке в Google: кратен 256 КБ, как требует Drive. */
    private const val CHUNK = 4 * 1024 * 1024

    /** Файлы до этого размера уходят одним запросом (как раньше), большие — кусками. */
    private const val SMALL = 3L * 1024 * 1024

    fun upload(ctx: Context, name: String, f: File) {
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        if (f.length() <= SMALL) {
            uploadBytes(ctx, name, mime, f.readBytes())
            return
        }
        // большой файл: скрипт открывает сессию загрузки, данные идут в Google кусками по 4 МБ с докачкой
        val init = http(
            DriveCfg.url(ctx),
            JSONObject()
                .put("action", "init")
                .put("folderId", DriveCfg.folder(ctx))
                .put("token", DriveCfg.token(ctx))
                .put("fileName", name)
                .put("mimeType", mime)
                .put("size", f.length())
                .put("skipIfExists", true)
                .toString()
        )
        if (!init.optBoolean("ok")) {
            val err = init.optString("error", "ошибка Диска")
            if (err.startsWith("Неизвестное действие")) {
                // скрипт Диска старый: новый Code.gs ещё не выпущен
                if (f.length() > 20L * 1024 * 1024) {
                    throw RuntimeException("Скрипт Диска устарел: обновите Code.gs (файл ${f.length() / 1048576} МБ)")
                }
                uploadBytes(ctx, name, mime, f.readBytes())
                return
            }
            throw RuntimeException(err)
        }
        if (init.optBoolean("exists")) return
        sendResumable(init.getString("sessionUrl"), f)
    }

    private class Put(val code: Int, val range: Long?)

    /** PUT одного куска (или запрос состояния, если data == null). Возвращает код ответа и сколько байт Google уже принял. */
    private fun put(session: String, data: ByteArray?, n: Int, contentRange: String): Put {
        val c = URL(session).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 120000
            c.instanceFollowRedirects = false
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setRequestProperty("Content-Range", contentRange)
            c.setFixedLengthStreamingMode(n)
            if (data != null && n > 0) c.outputStream.use { it.write(data, 0, n) }
            val code = c.responseCode
            val rng = c.getHeaderField("Range")?.substringAfter('-', "")?.toLongOrNull()?.let { it + 1 }
            if (code >= 400) {
                val t = c.errorStream?.bufferedReader()?.use { it.readText() }?.take(150) ?: ""
                throw RuntimeException("Диск: HTTP $code $t")
            }
            return Put(code, rng)
        } finally {
            c.disconnect()
        }
    }

    private fun sendResumable(session: String, f: File) {
        val total = f.length()
        val buf = ByteArray(CHUNK)
        var offset = 0L
        var fails = 0
        RandomAccessFile(f, "r").use { raf ->
            while (offset < total) {
                val n = minOf(CHUNK.toLong(), total - offset).toInt()
                raf.seek(offset)
                raf.readFully(buf, 0, n)
                try {
                    val r = put(session, buf, n, "bytes $offset-${offset + n - 1}/$total")
                    when (r.code) {
                        200, 201 -> return
                        308 -> offset = r.range ?: 0L
                        else -> throw RuntimeException("Диск: HTTP ${r.code}")
                    }
                    fails = 0
                } catch (e: Exception) {
                    if (++fails > 4) throw e
                    Thread.sleep(2000L * fails)
                    // после обрыва спрашиваем у Google, сколько он уже принял, и докачиваем с этого места
                    try {
                        val st = put(session, null, 0, "bytes */$total")
                        if (st.code == 200 || st.code == 201) return
                        offset = st.range ?: 0L
                    } catch (e2: Exception) {
                    }
                }
            }
        }
    }

    /** Размер куска при скачивании с Диска (скрипт отдаёт не больше 2 МБ за запрос). */
    private const val DL_CHUNK = 1024 * 1024

    /**
     * Скачивание файла по имени сразу на диск телефона кусками — в памяти только один кусок.
     * false — на Диске такого файла нет. Со старым скриптом (без кусков) качает целиком, как раньше.
     */
    fun downloadTo(ctx: Context, name: String, dest: File): Boolean {
        val tmp = File(dest.path + ".part")
        var offset = 0L
        var total = -1L
        try {
            tmp.outputStream().use { out ->
                while (total < 0 || offset < total) {
                    val r = http(query(ctx, "getchunk", "name" to name, "offset" to offset.toString(), "length" to DL_CHUNK.toString()), null)
                    if (!r.optBoolean("ok")) {
                        val err = r.optString("error", "ошибка Диска")
                        if (err == "not_found") return false
                        if (err.startsWith("Неизвестное действие") && offset == 0L) {
                            val all = download(ctx, name) ?: return false
                            out.write(all)
                            total = all.size.toLong()
                            offset = total
                            break
                        }
                        throw RuntimeException(err)
                    }
                    total = r.getLong("size")
                    val part = Base64.decode(r.getString("contentBase64"), Base64.DEFAULT)
                    if (part.isEmpty()) break
                    out.write(part)
                    offset += part.size
                }
            }
            if (total >= 0 && offset < total) throw RuntimeException("Файл скачан не полностью")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw RuntimeException("Не удалось сохранить файл")
            return true
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Загрузка готового массива байт (используется для частей APK). Файл с таким именем повторно не создаётся. */
    fun uploadBytes(ctx: Context, name: String, mime: String, data: ByteArray) {
        val o = JSONObject()
            .put("action", "upload")
            .put("folderId", DriveCfg.folder(ctx))
            .put("token", DriveCfg.token(ctx))
            .put("fileName", name)
            .put("mimeType", mime)
            .put("skipIfExists", true)
            .put("makePublic", false)
            .put("contentBase64", Base64.encodeToString(data, Base64.NO_WRAP))
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

    /** Имена файлов, в названии которых есть key (поиск на стороне скрипта Диска; действие search). */
    fun searchNames(ctx: Context, key: String): List<String> {
        val r = check(http(query(ctx, "search", "key" to key), null))
        val arr = r.optJSONArray("files") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i).optString("name")
            if (n.isNotEmpty()) out.add(n)
        }
        return out
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
