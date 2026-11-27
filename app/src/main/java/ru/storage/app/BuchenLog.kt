package ru.storage.app

import android.content.Context
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * «Провести» (Buchen) на сайте Coglas: сколько позиций и штук было в партии в момент нажатия.
 * При нажатии кнопки приложение запоминает цифры (снимок), а когда сайт показывает зелёное уведомление об успехе
 * (по-немецки «gebucht» или по-русски «проведено») — записывает их в журнал приложения и в файл buchen_log.json.
 * Если Buchen не удался (красное уведомление) — ничего не пишется.
 */
object BuchenLog {
    private const val MAX = 300
    private const val SNAP_TTL = 10L * 60L * 1000L

    private class Snap(val at: Long, val positions: Int, val pieces: Double, val ref: String)

    @Volatile private var snap: Snap? = null
    private var lastPostedAt = 0L

    private fun file(c: Context) = File(c.applicationContext.filesDir, "buchen_log.json")

    private fun measure(pageRows: Int): Snap {
        var pos = 0
        var pcs = 0.0
        var ref = ""
        val job = CoglasAuto.lastJob
        if (job != null) {
            val counted = job.rows.filter { parseQ(it.fact) > 0 }
            pos = counted.size
            pcs = counted.sumOf { parseQ(it.fact) }
            ref = job.orderNumber.trim()
            if (job.waybill.isNotBlank()) ref = (if (ref.isEmpty()) "" else "$ref / ") + job.waybill.trim()
        }
        if (pos == 0 && ScanSession.items.isNotEmpty()) {
            pos = ScanSession.items.size
            pcs = ScanSession.totalScans().toDouble()
        }
        if (pos == 0 && pageRows > 0) pos = pageRows
        return Snap(System.currentTimeMillis(), pos, pcs, ref)
    }

    /** Нажата кнопка «Провести» / Buchen: запоминаем, сколько товаров в партии. Главный поток. */
    fun snapshot(pageRows: Int) {
        snap = try { measure(pageRows) } catch (e: Exception) { null }
    }

    /** Сайт показал зелёное «накладная проведена»: записываем. Главный поток. */
    fun posted(ctx: Context, pageText: String) {
        val now = System.currentTimeMillis()
        if (now - lastPostedAt < 8000L) return
        lastPostedAt = now
        val s = snap?.takeIf { now - it.at < SNAP_TTL } ?: try { measure(0) } catch (e: Exception) { null }
        snap = null
        CoglasAuto.lastJob = null
        val app = ctx.applicationContext
        val pos = s?.positions ?: 0
        val pcs = s?.pieces ?: 0.0
        val ref = s?.ref.orEmpty()
        val text = "Проведено (Buchen): " + pos + " поз." + (if (pcs > 0) ", " + fmtQ(pcs) + " шт" else "") +
            (if (ref.isNotEmpty()) " · $ref" else "")
        try { Journal.log("Приём", text) } catch (e: Exception) { }
        Thread {
            synchronized(this) {
                try {
                    val f = file(app)
                    val a = try { if (f.exists()) JSONArray(f.readText(Charsets.UTF_8)) else JSONArray() } catch (e: Exception) { JSONArray() }
                    a.put(JSONObject().put("at", now).put("positions", pos).put("pieces", pcs).put("ref", ref).put("site", pageText.take(120)))
                    val out = JSONArray()
                    for (i in maxOf(0, a.length() - MAX) until a.length()) out.put(a.get(i))
                    f.writeText(out.toString(), Charsets.UTF_8)
                } catch (e: Exception) {
                }
            }
        }.start()
        try { Toast.makeText(app, "Записано: $pos поз." + (if (pcs > 0) ", " + fmtQ(pcs) + " шт" else ""), Toast.LENGTH_SHORT).show() } catch (e: Exception) { }
    }
}
