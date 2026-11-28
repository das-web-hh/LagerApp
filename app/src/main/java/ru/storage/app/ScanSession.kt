package ru.storage.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * Сканы на сайте Coglas, пока открыт заказ или накладная.
 * Пока идёт партия (до «Провести» / Buchen) приложение запоминает каждый отсканированный штрих-код: сколько раз он отсканирован,
 * артикул и название. В окне подсчёта количество у такого товара ставится равным числу сканов (один код × 3 скана = 3).
 * Партия закрывается сама, когда сайт сообщает, что накладная проведена.
 */
data class ScanEntry(val code: String, val article: String, val name: String, val count: Int, val lastAt: Long)

object ScanSession {
    val items = mutableStateListOf<ScanEntry>()
    /** Растёт при каждом изменении — по нему окно подсчёта понимает, что надо подтянуть новые количества. */
    var version by mutableIntStateOf(0)
    private var loaded = false
    private var lastCode = ""
    private var lastAt = 0L
    private val main = Handler(Looper.getMainLooper())

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "scan_session.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val a = JSONArray(f.readText(Charsets.UTF_8))
            items.clear()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                items.add(ScanEntry(o.getString("code"), o.optString("article"), o.optString("name"), o.optInt("count", 1), o.optLong("lastAt")))
            }
            version++
        } catch (e: Exception) {
        }
    }

    private fun save(ctx: Context) {
        try {
            val a = JSONArray()
            items.forEach { e ->
                a.put(JSONObject().put("code", e.code).put("article", e.article).put("name", e.name).put("count", e.count).put("lastAt", e.lastAt))
            }
            file(ctx).writeText(a.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
        }
    }

    /** Один скан кода на странице заказа / накладной. Вызывать в главном потоке. */
    fun record(ctx: Context, code: String) {
        val c = code.trim()
        if (c.isEmpty()) return
        load(ctx)
        val i = items.indexOfFirst { it.code == c }
        val now = System.currentTimeMillis()
        if (i >= 0) items[i] = items[i].copy(count = items[i].count + 1, lastAt = now)
        else items.add(ScanEntry(c, "", "", 1, now))
        lastCode = c
        lastAt = now
        version++
        save(ctx)
        // артикул и название берём из каталога приложения (в фоне)
        if (i < 0 || items[i].name.isBlank()) {
            val app = ctx.applicationContext
            Thread {
                try {
                    val p = ProductDb.get(app).findByBarcode(c)
                    if (p != null) main.post { refine(app, c, p.shownArticle, p.name) }
                } catch (e: Exception) {
                }
            }.start()
        }
    }

    /** Сайт нашёл товар: «артикул, название» — уточняем последний отсканированный код (если скан был недавно). */
    fun refineLast(ctx: Context, text: String) {
        if (lastCode.isEmpty() || System.currentTimeMillis() - lastAt > 20000) return
        val m = Regex("^([A-Za-z0-9._\\-]+)\\s*,\\s*(.*)$").find(text.trim()) ?: return
        refine(ctx, lastCode, m.groupValues[1], m.groupValues[2])
    }

    private fun refine(ctx: Context, code: String, article: String, name: String) {
        val i = items.indexOfFirst { it.code == code }
        if (i < 0) return
        val e = items[i]
        val n = e.copy(article = article.ifBlank { e.article }, name = name.ifBlank { e.name })
        if (n != e) {
            items[i] = n
            version++
            save(ctx)
        }
    }

    /** Коды, уже отправленные на сайт в этой партии и принятые им: повторный скан такого кода на сайт не уходит, только считается. */
    private val sentToSite = HashSet<String>()
    /** Номер партии: растёт при закрытии, по нему окно подсчёта отличает новую партию от старой. */
    var batch = 0
        private set

    fun wasSent(code: String): Boolean = sentToSite.contains(code.trim())
    fun markSent(code: String) { sentToSite.add(code.trim()) }
    fun unmarkSent(code: String) { sentToSite.remove(code.trim()) }

    /** Накладная проведена (Buchen) или нажали «Очистить»: партия закрыта. */
    fun clear(ctx: Context) {
        items.clear()
        sentToSite.clear()
        batch++
        lastCode = ""
        version++
        save(ctx)
    }

    fun totalScans(): Int = items.sumOf { it.count }
}
