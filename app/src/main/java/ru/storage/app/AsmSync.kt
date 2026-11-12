package ru.storage.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Сборка»: обмен позициями и счётом между получателями ОБЫЧНОЙ партии через скрипт Диска (действия count / counts,
 * как у HTML-страницы партии). Firebase не используется.
 *
 * ПУСТАЯ партия ("empty": true в файле партии) — у каждого работника свой отдельный список: ничего не отправляется и не
 * принимается. Если тип партии определить нельзя (нет базы), обмен тоже закрыт. Задание без кода партии — ничего не шлёт.
 */
object AsmSync {
    private const val PUSH_MIN_MS = 20_000L
    private const val PULL_MS = 120_000L
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** sid своей позиции → (подпись названия/артикула/кода, время правки): t меняется только при правке */
    private val tMap = HashMap<String, Pair<String, Long>>()

    private fun who(receiver: String) = BatchShare.sha(BatchShare.normName(receiver.ifBlank { Session.login }))

    private fun nmz(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

    /** Обмен разрешён: скрипт Диска настроен, партия получена и она обычная (не пустая). */
    private fun enabled(ctx: Context, code: String?): Boolean {
        if (code == null || !DriveCfg.ready(ctx)) return false
        AsmStore.loadBases(ctx)
        val base = AsmStore.bases.firstOrNull { it.code == code } ?: return false
        return !base.json.optBoolean("empty", false)
    }

    /** Тело запроса count: мой счёт по строкам (включая добавленные) и мои добавленные позиции. Главный поток. */
    private fun payload(job: InvJob, receiver: String, code: String): JSONObject {
        val me = who(receiver)
        val counts = JSONObject()
        AsmStore.jobs.toList().filter { AsmStore.codeOf(it) == code }.forEach { j ->
            j.rows.toList().forEach { r ->
                val v = parseQ(r.fact)
                if (v > 0.0) counts.put(r.sid, counts.optDouble(r.sid, 0.0) + v)
            }
        }
        val items = JSONArray()
        val now = System.currentTimeMillis()
        for (r in job.rows.toList()) {
            if (!r.extra || r.orphan || r.name.isBlank()) continue
            if (!r.fromOthers && r.author.isBlank()) r.author = me // строка добавлена на этом устройстве — я автор
            if (r.author != me) continue
            if (items.length() >= 3000) break
            val sig = r.name.trim() + "|" + r.article + "|" + r.barcode
            val old = tMap[r.sid]
            val t = if (old == null || old.first != sig) now.also { tMap[r.sid] = sig to it } else old.second
            items.put(
                JSONObject().put("sid", r.sid).put("name", r.name.trim().take(200)).put("article", r.article)
                    .put("barcode", r.barcode).put("by", me).put("t", t)
            )
        }
        return JSONObject().put("action", "count").put("code", code).put("dev", me.take(32))
            .put("name", receiver.take(40)).put("counts", counts).put("items", items)
    }

    /** Админ остановил работника или партию (stopped / deleted на сервере): показываем сообщение, а не молчим. Ответ ok снимает его. */
    private fun stopNote(job: InvJob, r: JSONObject) {
        val key = "sync_" + job.id
        if (r.optBoolean("ok")) { InvLog.note(key, null); return }
        when (r.optString("error")) {
            "stopped" -> InvLog.note(key, "Администратор остановил вас или партию. Ваша работа не отправляется.")
            "deleted" -> InvLog.note(key, "Администратор удалил партию или ваш доступ. Ваша работа не отправляется.")
        }
    }

    private fun sigOf(p: JSONObject) = p.optJSONObject("counts").toString() + p.optJSONArray("items").toString()

    private suspend fun <T> net(app: Context, block: () -> T): T =
        KeepAlive.run(app, "Синхронизация партии") { withContext(Dispatchers.IO) { block() } }

    /**
     * Пока окно открыто: опрос раз в 120 с (сразу при открытии), отправка изменений не чаще раза в 20 с, и только
     * после первого удачного опроса. Вызывать из LaunchedEffect (главный поток).
     */
    suspend fun run(ctx: Context, job: InvJob, receiver: String) {
        val app = ctx.applicationContext
        val code = AsmStore.codeOf(job)
        if (code == null || !enabled(app, code)) return
        var synced = false
        var nextPull = 0L
        var lastPush = 0L
        var lastSig = sigOf(payload(job, receiver, code)) // то, что уже есть на сервере, заново не шлём
        while (true) {
            val now = SystemClock.elapsedRealtime()
            try {
                if (now >= nextPull) {
                    val r = net(app) { DriveApi.batchCounts(app, code) }
                    stopNote(job, r)
                    synced = apply(job, receiver, r) || synced
                    nextPull = now + (if (synced) PULL_MS else 15_000L)
                } else if (synced && now - lastPush >= PUSH_MIN_MS) {
                    val p = payload(job, receiver, code)
                    val sig = sigOf(p)
                    if (sig != lastSig) {
                        val r = net(app) { DriveApi.batchCount(app, p) }
                        stopNote(job, r)
                        if (r.optBoolean("ok")) {
                            lastSig = sig
                            lastPush = now
                            apply(job, receiver, r)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                nextPull = maxOf(nextPull, now + 15_000L)
            }
            delay(2000)
        }
    }

    /** Немедленная отправка («Далее», закрытие окна). Задание, которое уже напечатано и удалено, не отправляется. */
    fun pushFinal(ctx: Context, job: InvJob, receiver: String) {
        val app = ctx.applicationContext
        val code = AsmStore.codeOf(job) ?: return
        if (!enabled(app, code) || AsmStore.jobs.none { it === job }) return
        val p = try { payload(job, receiver, code) } catch (e: Exception) { return }
        bg.launch {
            try { KeepAlive.run(app, "Синхронизация партии") { DriveApi.batchCount(app, p) } } catch (e: Exception) { }
        }
    }

    /** Ответ скрипта → чужой счёт и чужие позиции в задании. Главный поток. */
    private fun apply(job: InvJob, receiver: String, r: JSONObject): Boolean {
        if (!r.optBoolean("ok")) return false
        val me = who(receiver)
        val myDev = me.take(32)
        val rm = HashMap<String, Double>()
        val devs = r.optJSONArray("devs")
        if (devs != null) for (i in 0 until devs.length()) {
            val d = devs.optJSONObject(i) ?: continue
            if (d.optString("dev") == myDev) continue
            val c = d.optJSONObject("c") ?: continue
            c.keys().forEach { k -> rm[k] = (rm[k] ?: 0.0) + c.optDouble(k, 0.0) }
        }
        // нет items — скрипт старый (или партия пустая): позиции не трогаем
        r.optJSONArray("items")?.let { merge(job, me, it) }
        job.rows.toList().forEach { row ->
            row.others = (rm[row.sid] ?: 0.0) + row.alias.sumOf { a -> rm[a] ?: 0.0 }
        }
        return true
    }

    /** Чужие позиции без дублей: sid / штрих-код / название совпали — чужой sid привязывается к строке; иначе новая строка (план 0). */
    private fun merge(job: InvJob, me: String, items: JSONArray) {
        val rows = job.rows
        val seen = HashSet<String>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val sid = o.optString("sid")
            if (name.isEmpty() || sid.isEmpty()) continue
            val ids = ArrayList<String>()
            ids.add(sid)
            o.optJSONArray("al")?.let { a -> for (k in 0 until a.length()) a.optString(k).takeIf { s -> s.isNotEmpty() }?.let { s -> ids.add(s) } }
            seen.addAll(ids)
            val by = o.optString("by")
            if (by == me) continue // свои позиции (в том числе удалённые мной) обратно не принимаем
            val bc = o.optString("barcode").trim()
            val ar = o.optString("article").trim()
            val t = o.optLong("t")
            val set = ids.toSet()
            val key = nmz(name)
            val x = rows.firstOrNull { r -> r.sid in set || r.alias.any { a -> a in set } }
                ?: (if (bc.isNotEmpty()) rows.firstOrNull { r -> r.barcode.trim() == bc } else null)
                ?: rows.firstOrNull { r -> nmz(r.name) == key }
            if (x == null) {
                val row = ItemRow(name, "", extra = true)
                row.sid = ids[0]
                row.alias = ids.drop(1)
                row.author = by
                row.barcode = bc
                row.article = ar
                row.fromOthers = true
                row.seenT = t
                rows.add(row)
            } else {
                val add = ids.filter { s -> s != x.sid && s !in x.alias }
                if (add.isNotEmpty()) x.alias = x.alias + add
                if (x.fromOthers && x.author != me) {
                    if (t > x.seenT) { // автор поправил название, артикул или штрих-код
                        if (x.name != name) x.name = name
                        if (ar.isNotEmpty()) x.article = ar
                        if (bc.isNotEmpty() && x.barcode != bc && rows.none { r -> r !== x && r.barcode == bc }) x.barcode = bc
                        x.seenT = t
                    }
                } else {
                    if (x.barcode.isBlank() && bc.isNotEmpty() && rows.none { r -> r !== x && r.barcode == bc }) x.barcode = bc
                    if (x.article.isBlank() && ar.isNotEmpty()) x.article = ar
                }
            }
        }
        // автор удалил позицию: у остальных строка исчезает, если по ней нет подсчёта; иначе остаётся (удалить можно вручную)
        rows.toList().forEach { r ->
            if (r.fromOthers && !r.orphan && r.author != me && (listOf(r.sid) + r.alias).none { it in seen }) {
                if (parseQ(r.fact) > 0.0 || parseQ(r.defect) > 0.0) r.orphan = true else rows.remove(r)
            }
        }
    }
}
