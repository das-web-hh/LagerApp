package ru.storage.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Личный журнал инвентаризации устройства: по каждому заданию (паллете) — какие товары, сколько, артикул, штрих-код и когда.
 * Уходит на Диск в INVLOG_<код>.json (скрипт, действие log). Назад на телефоны он не возвращается, со счётом и списками партии
 * не смешивается; читает его только администратор (экран «Отчёт инвентаризации»).
 * Код партии берётся у задания. Обычная инвентаризация (без партии) идёт по дням: код APPINV + yyyyMMdd (Europe/Berlin),
 * запись попадает в файл того дня, когда строку впервые ввели.
 */
object InvLog {
    const val APP_CODE = "APPINV"
    private const val MIN_MS = 20_000L
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSig = HashMap<String, String>()
    private val lastAt = HashMap<String, Long>()
    private val errs = LinkedHashMap<String, String>()

    /** Последняя ошибка отправки журнала («Журнал не отправлен: причина»); пусто — всё отправлено. Показывается в окнах заданий. */
    var status by mutableStateOf("")
        private set

    /** Записать/снять сообщение об ошибке; key — задание (или служебный ключ), msg — полный текст для показа. */
    @Synchronized
    fun note(key: String, msg: String?) {
        if (msg == null) errs.remove(key) else errs[key] = msg
        status = errs.values.lastOrNull() ?: ""
    }

    private fun setErr(jobId: String, msg: String?) = note(jobId, msg?.let { "Журнал не отправлен: $it" })

    /** День yyyyMMdd по Берлину: по нему выбирается файл APPINV<день>. */
    fun dayOf(t: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Europe/Berlin") }.format(Date(t))

    private fun errText(r: JSONObject): String = when (r.optString("error")) {
        "blocked" -> if (r.optString("state") == "del") "администратор удалил партию или ваш доступ" else "администратор остановил вас или партию"
        "limit" -> r.optString("message").ifBlank { "журнал заполнен (лимит 1000 частей на файл)" }
        else -> r.optString("error").ifBlank { "ошибка Диска" }
    }

    private fun isAppCode(code: String) = code.startsWith(APP_CODE)

    private fun devOf(receiver: String) = BatchShare.sha(BatchShare.normName(receiver.ifBlank { Session.login })).take(32)

    fun versionName(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("invlog", Context.MODE_PRIVATE)

    /**
     * Тела запросов из текущих строк задания (отмечает время изменений). Главный поток.
     * Партия — один запрос (код партии). Обычная инвентаризация — по запросу на каждый день (APPINV<yyyyMMdd>):
     * строка уходит в файл дня первого ввода, поэтому задание через полночь делится на два файла.
     */
    private fun bodies(ctx: Context, job: InvJob, receiver: String, code: String): List<JSONObject> {
        val now = System.currentTimeMillis()
        val daily = isAppCode(code)
        val byDay = LinkedHashMap<String, JSONArray>()
        for (r in job.rows.toList()) {
            val q = parseQ(r.fact)
            if (q <= 0.0 || r.name.isBlank()) continue
            if (r.tf != r.fact) {
                r.tf = r.fact
                if (r.tc == 0L) r.tc = now
                r.tu = now
            }
            // у старых строк времени нет — берём время создания задания
            val t0 = if (r.tc > 0L) r.tc else if (job.created > 0L) job.created else now
            val t1 = if (r.tu > 0L) r.tu else t0
            val art = r.article.trim().ifBlank { try { ArticleLookup.find(r) } catch (e: Exception) { "" } }
            val o = JSONObject().put("n", r.name.trim().take(200)).put("a", art).put("b", r.barcode.trim()).put("e", r.expiry.trim()).put("q", q)
                .put("t0", t0).put("t1", t1).put("ad", if (r.extra) r.ts else 0L).put("x", if (r.extra) 1 else 0)
            byDay.getOrPut(if (daily) dayOf(t0) else "") { JSONArray() }.put(o)
        }
        if (daily) {
            // дни, отправленные раньше, а строк в них не осталось: часть очищается пустым списком
            prefs(ctx).getString("d_" + job.id, "").orEmpty().split(',').filter { it.length == 8 }.forEach { byDay.getOrPut(it) { JSONArray() } }
        } else if (byDay.isEmpty()) byDay[""] = JSONArray()
        val dev = devOf(receiver)
        val k = dev.take(12) + "_" + job.id.replace(Regex("[^A-Za-z0-9_-]"), "").take(40)
        val box = job.address.trim().ifBlank { "Inventur" }
        return byDay.map { (day, rows) ->
            val part = JSONObject().put("k", k).put("box", box).put("rows", rows)
            JSONObject().put("action", "log").put("code", if (daily) code + day else code).put("dev", dev).put("name", receiver.take(40))
                .put("src", "a").put("ver", versionName(ctx)).put("parts", JSONArray().put(part))
        }
    }

    /**
     * Журнал задания одним объектом для файла LGRP (v:2, поле "log"): ровно то, что ушло бы действием log (без токена).
     * Несколько дней APPINV сливаются в один объект с кодом APPINV — при записи в базу администратор делит их по дням обратно.
     * null — нет строк. Главный поток.
     */
    fun fileLog(ctx: Context, job: InvJob, receiver: String, code: String): JSONObject? {
        val ps = bodies(ctx.applicationContext, job, receiver, code).filter { (it.optJSONArray("parts")?.optJSONObject(0)?.optJSONArray("rows")?.length() ?: 0) > 0 }
        if (ps.isEmpty()) return null
        if (ps.size == 1) return ps[0]
        val rows = JSONArray()
        for (p in ps) {
            val rs = p.optJSONArray("parts")?.optJSONObject(0)?.optJSONArray("rows") ?: continue
            for (i in 0 until rs.length()) rows.put(rs.get(i))
        }
        val first = ps[0].optJSONArray("parts")!!.getJSONObject(0)
        return JSONObject(ps[0].toString()).put("code", code)
            .put("parts", JSONArray().put(JSONObject().put("k", first.optString("k")).put("box", first.optString("box")).put("rows", rows)))
    }

    private fun he(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * «Отправить файлом»: создаёт LGRP-<устройство>-<дата>-<время>-<2 символа>.html (отчёт + JSON v:2 с готовым журналом) и открывает
     * системное «Поделиться». Администратор записывает такой файл в базу на экране «Списки с iPhone». Токен в файл не попадает.
     * Возвращает текст ошибки или null. Главный поток.
     */
    fun shareFile(ctx: Context, job: InvJob, receiver: String, code: String, title: String): String? {
        val log = try { fileLog(ctx, job, receiver, code) } catch (e: Exception) { return e.message ?: e.javaClass.simpleName }
            ?: return "нет посчитанных товаров"
        return exportFile(ctx, listOf(log), receiver, title)
    }

    /**
     * «Отправить всё файлом»: журналы ВСЕХ сохранённых на телефоне заданий (инвентаризация и сборка/партии) одним файлом LGRP (v:2, поле "logs").
     * Администратор записывает такой файл в базу одним нажатием (каждое задание — своим запросом). Возвращает текст ошибки или null. Главный поток.
     */
    fun shareAllFile(ctx: Context, receiver: String): String? {
        return try {
            val app = ctx.applicationContext
            val pr = prefs(app)
            InventoryStore.load(app)
            AsmStore.load(app)
            val all = InventoryStore.jobs.toList().map { it to APP_CODE } + AsmStore.jobs.toList().map { it to (AsmStore.codeOf(it) ?: APP_CODE) }
            val logs = ArrayList<JSONObject>()
            for ((job, code0) in all) {
                val code = pr.getString("c_" + job.id, null) ?: code0
                val recv = pr.getString("r_" + job.id, null) ?: receiver
                fileLog(app, job, recv, code)?.let { logs.add(it) }
            }
            if (logs.isEmpty()) "нет посчитанных товаров" else exportFile(app, logs, receiver, "Все задания (" + logs.size + ")")
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun exportFile(ctx: Context, logs: List<JSONObject>, receiver: String, title: String): String? {
        return try {
            val app = ctx.applicationContext
            val at = System.currentTimeMillis()
            val rows = JSONArray()
            val html = StringBuilder()
            val ttl = title.trim().ifBlank { "Inventur" }
            html.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>").append(he(ttl))
                .append("</title><style>body{font-family:Verdana,Arial,sans-serif;margin:8px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #000;padding:3px;font-size:11pt}th{background:#e6e6e6}td.q{text-align:center;font-weight:700}</style></head><body><h1>")
                .append(he(ttl)).append("</h1><div>").append(he(receiver)).append(" · ").append(
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).apply { timeZone = TimeZone.getTimeZone("Europe/Berlin") }.format(Date(at))
                ).append("</div><table><tr><th>Nr.</th><th>Artikel-Nr.</th><th>Artikelbezeichnung</th><th>EAN</th><th>Stück</th><th>MHD</th></tr>")
            var nr = 0
            for (log in logs) {
                val ps = log.optJSONArray("parts") ?: continue
                for (i in 0 until ps.length()) {
                    val pt = ps.optJSONObject(i) ?: continue
                    val rs = pt.optJSONArray("rows") ?: continue
                    if (logs.size > 1) html.append("<tr><th colspan=6>").append(he(pt.optString("box"))).append(" · ").append(he(log.optString("code"))).append("</th></tr>")
                    for (j in 0 until rs.length()) {
                        val r = rs.optJSONObject(j) ?: continue
                        val q = r.optDouble("q", 0.0)
                        nr++
                        rows.put(JSONObject().put("n", r.optString("n")).put("a", r.optString("a")).put("b", r.optString("b")).put("q", q).put("e", r.optString("e")))
                        html.append("<tr><td>").append(nr).append("</td><td>").append(he(r.optString("a"))).append("</td><td>").append(he(r.optString("n")))
                            .append("</td><td>").append(he(r.optString("b"))).append("</td><td class=q>").append(if (q % 1.0 == 0.0) q.toLong().toString() else q.toString())
                            .append("</td><td>").append(he(r.optString("e"))).append("</td></tr>")
                    }
                }
            }
            val meta = JSONObject().put("v", 2).put("app", "lager").put("title", ttl).put("user", receiver).put("dev", logs[0].optString("dev"))
                .put("code", if (logs.size == 1) logs[0].optString("code") else "").put("kind", "").put("at", at).put("rows", rows)
            if (logs.size == 1) meta.put("log", logs[0]) else meta.put("logs", JSONArray(logs))
            html.append("</table><script type=\"application/json\" id=\"lagerp\">").append(meta.toString().replace("<", "\\u003c")).append("</scr").append("ipt></body></html>")
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(at))
            val rnd = "abcdefghjkmnpqrstuvwxyz23456789".let { a -> (1..2).map { a.random() }.joinToString("") }
            val name = "LGRP-" + logs[0].optString("dev").take(4) + "-" + stamp + "-" + rnd + ".html"
            val dir = File(app.cacheDir, "export").also { it.mkdirs() }
            val f = File(dir, name)
            f.writeText(html.toString(), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "Отправить файлом").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun sigOf(ps: List<JSONObject>) = ps.joinToString("#") { it.optString("code") + ":" + it.optJSONArray("parts").toString() }

    /** Отправка журнала задания: не чаще раза в 20 с и только если что-то изменилось; force — сразу. Ошибки показываются в status. */
    fun push(ctx: Context, job: InvJob, receiver: String, code: String, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!DriveCfg.ready(app)) return
        if (!isAppCode(code)) {
            AsmStore.loadBases(app)
            if (AsmStore.bases.none { it.code == code }) return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - (lastAt[job.id] ?: 0L) < MIN_MS) return
        val ps = try { bodies(app, job, receiver, code) } catch (e: Exception) { setErr(job.id, "не удалось собрать данные: ${e.message ?: e.javaClass.simpleName}"); return }
        try { prefs(app).edit().putString("r_" + job.id, receiver).putString("c_" + job.id, code).apply() } catch (e: Exception) { }
        if (ps.isEmpty()) return
        val sig = sigOf(ps)
        if (sig == lastSig[job.id]) return
        lastAt[job.id] = now
        bg.launch {
            if (sendAll(app, job.id, ps)) lastSig[job.id] = sig
        }
    }

    /** Отправка всех запросов задания (по одному на день); дни со строками запоминаются. true — всё принято сервером. */
    private suspend fun sendAll(app: Context, jobId: String, ps: List<JSONObject>): Boolean {
        var ok = true
        val days = ArrayList<String>()
        for (p in ps) {
            if (!send(app, jobId, p)) ok = false
            else if (p.optString("code").length == APP_CODE.length + 8 && (p.optJSONArray("parts")?.optJSONObject(0)?.optJSONArray("rows")?.length() ?: 0) > 0)
                days.add(p.optString("code").takeLast(8))
        }
        if (ok) try { prefs(app).edit().putString("d_$jobId", days.joinToString(",")).apply() } catch (e: Exception) { }
        return ok
    }

    /** Один запрос; true — сервер принял. Любая ошибка попадает в status, данные остаются, повтор — со следующим push. */
    private suspend fun send(app: Context, jobId: String, p: JSONObject): Boolean {
        return try {
            p.put("token", DriveCfg.token(app))
            val r = KeepAlive.run(app, "Журнал инвентаризации") { DriveApi.batchLog(app, p) }
            if (r.optBoolean("ok")) { setErr(jobId, null); true } else { setErr(jobId, errText(r)); false }
        } catch (e: Exception) {
            setErr(jobId, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /**
     * Один раз после установки новой версии: отправляет все сохранённые на телефоне задания (обычные и партий).
     * Вызывать в главном потоке после входа. Флаг ставится только если всё ушло; иначе повтор при следующем запуске.
     */
    fun syncAll(ctx: Context) {
        val app = ctx.applicationContext
        if (!DriveCfg.ready(app)) return
        val ver = versionName(app)
        val pr = prefs(app)
        if (pr.getString("synced_ver", "") == ver) return
        InventoryStore.load(app)
        AsmStore.load(app)
        AsmStore.loadBases(app)
        val list = ArrayList<Pair<String, List<JSONObject>>>()
        val all = InventoryStore.jobs.toList().map { it to APP_CODE } + AsmStore.jobs.toList().map { it to (AsmStore.codeOf(it) ?: APP_CODE) }
        for ((job, code0) in all) {
            val code = pr.getString("c_" + job.id, null) ?: code0
            if (!isAppCode(code) && AsmStore.bases.none { it.code == code }) continue
            val recv = pr.getString("r_" + job.id, null) ?: Session.user?.name.orEmpty()
            try {
                val ps = bodies(app, job, recv, code).filter { (it.optJSONArray("parts")?.optJSONObject(0)?.optJSONArray("rows")?.length() ?: 0) > 0 }
                if (ps.isNotEmpty()) list.add(job.id to ps)
            } catch (e: Exception) {
                setErr(job.id, "не удалось собрать данные: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        bg.launch {
            var allOk = true
            for ((id, ps) in list) if (!sendAll(app, id, ps)) allOk = false
            if (allOk) pr.edit().putString("synced_ver", ver).apply()
        }
    }
}
