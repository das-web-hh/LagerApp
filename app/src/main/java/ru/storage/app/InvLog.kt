package ru.storage.app

import android.content.Context
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

/**
 * Личный журнал инвентаризации устройства: по каждому заданию (паллете) — какие товары, сколько, артикул, штрих-код и когда.
 * Уходит на Диск в INVLOG_<код>.json (скрипт, действие log). Назад на телефоны он не возвращается, со счётом и списками партии
 * не смешивается; читает его только администратор (экран «Отчёт инвентаризации»).
 * Код партии берётся у задания; без кода (обычная инвентаризация) — APPINV.
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

    @Synchronized
    private fun setErr(jobId: String, msg: String?) {
        if (msg == null) errs.remove(jobId) else errs[jobId] = msg
        val m = errs.values.lastOrNull()
        status = if (m == null) "" else "Журнал не отправлен: $m"
    }

    private fun errText(r: JSONObject): String = when (r.optString("error")) {
        "blocked" -> if (r.optString("state") == "del") "администратор удалил партию или ваш доступ" else "администратор остановил вас или партию"
        "limit" -> r.optString("message").ifBlank { "журнал заполнен (лимит 300 частей на файл)" }
        else -> r.optString("error").ifBlank { "ошибка Диска" }
    }

    private fun isAppCode(code: String) = code.startsWith(APP_CODE)

    fun versionName(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("invlog", Context.MODE_PRIVATE)

    /** Собирает тело запроса из текущих строк задания и отмечает время изменений. Вызывать в главном потоке. */
    private fun body(ctx: Context, job: InvJob, receiver: String, code: String): JSONObject {
        val now = System.currentTimeMillis()
        val rows = JSONArray()
        for (r in job.rows.toList()) {
            val q = parseQ(r.fact)
            if (q <= 0.0 || r.name.isBlank()) continue
            if (r.tf != r.fact) {
                r.tf = r.fact
                if (r.tc == 0L) r.tc = now
                r.tu = now
            }
            // у старых строк времени нет — берём время создания задания
            val t0 = if (r.tc > 0L) r.tc else job.created
            val t1 = if (r.tu > 0L) r.tu else t0
            val art = r.article.trim().ifBlank { try { ArticleLookup.find(r) } catch (e: Exception) { "" } }
            rows.put(
                JSONObject().put("n", r.name.trim().take(200)).put("a", art).put("b", r.barcode.trim()).put("e", r.expiry.trim()).put("q", q)
                    .put("t0", t0).put("t1", t1).put("ad", if (r.extra) r.ts else 0L).put("x", if (r.extra) 1 else 0)
            )
        }
        val dev = devOf(receiver)
        val part = JSONObject()
            .put("k", dev.take(12) + "_" + job.id.replace(Regex("[^A-Za-z0-9_-]"), "").take(40))
            .put("box", job.address.trim().ifBlank { "Inventur" })
            .put("rows", rows)
        return JSONObject().put("action", "log").put("code", code).put("dev", dev).put("name", receiver.take(40))
            .put("src", "a").put("ver", versionName(ctx)).put("parts", JSONArray().put(part))
    }

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
        val p = try { body(app, job, receiver, code) } catch (e: Exception) { setErr(job.id, "не удалось собрать данные: ${e.message ?: e.javaClass.simpleName}"); return }
        try { prefs(app).edit().putString("r_" + job.id, receiver).putString("c_" + job.id, code).apply() } catch (e: Exception) { }
        val sig = p.optJSONArray("parts").toString()
        if (sig == lastSig[job.id]) return
        lastAt[job.id] = now
        bg.launch {
            if (send(app, job.id, p)) lastSig[job.id] = sig
        }
    }

    /** Одна отправка; true — сервер принял. Любая ошибка попадает в status, данные остаются, повтор — со следующим push. */
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
        val list = ArrayList<Pair<String, JSONObject>>()
        val all = InventoryStore.jobs.toList().map { it to APP_CODE } + AsmStore.jobs.toList().map { it to (AsmStore.codeOf(it) ?: APP_CODE) }
        for ((job, code0) in all) {
            val code = pr.getString("c_" + job.id, null) ?: code0
            if (!isAppCode(code) && AsmStore.bases.none { it.code == code }) continue
            val recv = pr.getString("r_" + job.id, null) ?: Session.user?.name.orEmpty()
            try {
                val p = body(app, job, recv, code)
                if ((p.optJSONArray("parts")?.optJSONObject(0)?.optJSONArray("rows")?.length() ?: 0) > 0) list.add(job.id to p)
            } catch (e: Exception) {
                setErr(job.id, "не удалось собрать данные: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        bg.launch {
            var allOk = true
            for ((id, p) in list) if (!send(app, id, p)) allOk = false
            if (allOk) pr.edit().putString("synced_ver", ver).apply()
        }
    }
}
