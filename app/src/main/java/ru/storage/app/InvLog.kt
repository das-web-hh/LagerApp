package ru.storage.app

import android.content.Context
import android.os.SystemClock
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

    private fun devOf(receiver: String) = BatchShare.sha(BatchShare.normName(receiver.ifBlank { Session.login })).take(32)

    /** Собирает тело запроса из текущих строк задания и отмечает время изменений. Вызывать в главном потоке. */
    private fun body(job: InvJob, receiver: String, code: String): JSONObject {
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
            val art = r.article.trim().ifBlank { try { ArticleLookup.find(r) } catch (e: Exception) { "" } }
            rows.put(
                JSONObject().put("n", r.name.trim().take(200)).put("a", art).put("b", r.barcode.trim()).put("e", r.expiry.trim()).put("q", q)
                    .put("t0", r.tc).put("t1", r.tu).put("ad", if (r.extra) r.ts else 0L).put("x", if (r.extra) 1 else 0)
            )
        }
        val dev = devOf(receiver)
        val part = JSONObject()
            .put("k", dev.take(12) + "_" + job.id.replace(Regex("[^A-Za-z0-9_-]"), "").take(40))
            .put("box", job.address.trim().ifBlank { "Inventur" })
            .put("rows", rows)
        return JSONObject().put("action", "log").put("code", code).put("dev", dev).put("name", receiver.take(40))
            .put("src", "a").put("parts", JSONArray().put(part))
    }

    /** Отправка журнала задания: не чаще раза в 20 с и только если что-то изменилось; force — сразу. */
    fun push(ctx: Context, job: InvJob, receiver: String, code: String, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!DriveCfg.ready(app)) return
        if (code != APP_CODE) {
            AsmStore.loadBases(app)
            if (AsmStore.bases.none { it.code == code }) return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - (lastAt[job.id] ?: 0L) < MIN_MS) return
        val p = try { body(job, receiver, code) } catch (e: Exception) { return }
        val sig = p.optJSONArray("parts").toString()
        if (sig == lastSig[job.id]) return
        lastAt[job.id] = now
        bg.launch {
            try {
                p.put("token", DriveCfg.token(app))
                val r = KeepAlive.run(app, "Журнал инвентаризации") { DriveApi.batchLog(app, p) }
                if (r.optBoolean("ok")) lastSig[job.id] = sig
            } catch (e: Exception) {
            }
        }
    }
}
