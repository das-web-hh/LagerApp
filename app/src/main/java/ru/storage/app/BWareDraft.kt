package ru.storage.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Черновик «Приём B-Ware» (kind = "recv") и «Инвентарь B-Ware» (kind = "inv").
 * Пишется при каждом изменении: на телефон (переживает выход и перезагрузку) и в Firebase
 * (переживает удаление и переустановку приложения; без сети уходит в очередь). Стирается только кнопкой «Очистить / Закончить».
 */
object BWareDraft {
    /** Устанавливается в LagerApp.onCreate. */
    var app: Context? = null

    private fun file(ctx: Context, kind: String) = File(ctx.filesDir, "bware_draft_$kind.json")

    private fun docRef(kind: String) = FirebaseFirestore.getInstance().collection("bware_drafts")
        .document(Session.login.lowercase().ifBlank { "admin" }.replace('/', '_') + "_" + kind)

    private fun rowsJson(rows: List<ItemRow>): JSONArray {
        val a = JSONArray()
        rows.forEach { r ->
            a.put(
                JSONObject().put("n", r.name).put("q", r.qty).put("f", r.fact).put("e", r.expiry)
                    .put("b", r.barcode).put("nt", r.note).put("pid", r.productId).put("x", r.extra)
                    .put("ph", r.photo).put("rs", JSONArray(r.reasons.toList()))
            )
        }
        return a
    }

    private fun fillRows(target: SnapshotStateList<ItemRow>, a: JSONArray?) {
        target.clear()
        if (a == null) return
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val r = ItemRow(o.optString("n"), o.optString("q"), extra = o.optBoolean("x"), productId = o.optString("pid"))
            r.fact = o.optString("f")
            r.expiry = o.optString("e")
            r.barcode = o.optString("b")
            r.note = o.optString("nt")
            r.photo = o.optString("ph")
            o.optJSONArray("rs")?.let { rs -> for (k in 0 until rs.length()) r.reasons.add(rs.optString(k)) }
            target.add(r)
        }
    }

    private fun isEmpty(kind: String): Boolean =
        if (kind == "inv") BWareInvHolder.rows.isEmpty()
        else BWareHolder.let {
            it.rows.isEmpty() && it.sender.isBlank() && it.order.isBlank() && it.truck.isBlank() &&
                it.trailer.isBlank() && it.photos.isEmpty()
        }

    /** Текущее состояние окна в виде JSON (читает все поля — по нему экран узнаёт, что нужно сохранять). */
    fun snapshot(kind: String): String {
        val j = JSONObject().put("v", 1).put("kind", kind)
        if (kind == "inv") {
            j.put("rows", rowsJson(BWareInvHolder.rows)).put("step", BWareInvHolder.step)
        } else {
            val h = BWareHolder
            j.put("rows", rowsJson(h.rows)).put("step", h.step)
                .put("sender", h.sender).put("order", h.order).put("truck", h.truck).put("trailer", h.trailer)
                .put("driverSigned", h.driverSigned).put("code", h.code).put("photos", JSONArray(h.photos.toList()))
        }
        return j.toString()
    }

    private fun apply(kind: String, json: String) {
        val j = JSONObject(json)
        if (kind == "inv") {
            fillRows(BWareInvHolder.rows, j.optJSONArray("rows"))
            BWareInvHolder.step = j.optInt("step", 0)
        } else {
            val h = BWareHolder
            fillRows(h.rows, j.optJSONArray("rows"))
            h.step = j.optInt("step", 0)
            h.sender = j.optString("sender")
            h.order = j.optString("order")
            h.truck = j.optString("truck")
            h.trailer = j.optString("trailer")
            h.driverSigned = j.optBoolean("driverSigned")
            h.code = j.optString("code")
            h.photos.clear()
            j.optJSONArray("photos")?.let { a -> for (i in 0 until a.length()) h.photos.add(a.optString(i)) }
        }
    }

    @Synchronized
    private fun saveLocal(ctx: Context, kind: String, json: String) {
        val f = file(ctx, kind)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(f)) { f.writeText(json); tmp.delete() }
    }

    private fun saveCloud(kind: String, json: String) {
        try {
            docRef(kind).set(hashMapOf<String, Any>("json" to json, "updatedAt" to System.currentTimeMillis()))
        } catch (e: Exception) {
        }
    }

    /** Стереть черновик (после «Очистить» / «Закончить»). */
    fun delete(kind: String) {
        val c = app ?: return
        try { file(c, kind).delete() } catch (e: Exception) { }
        try { docRef(kind).delete() } catch (e: Exception) { }
    }

    /** При выходе из приложения: немедленно записать оба окна, если они не пустые. */
    fun flushAll() {
        val c = app ?: return
        for (k in listOf("recv", "inv")) {
            try {
                if (!isEmpty(k)) {
                    val s = snapshot(k)
                    saveLocal(c, k, s)
                    saveCloud(k, s)
                }
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Если окно пустое — вернуть сохранённое: с телефона, а если там нет (переустановка) — из облака; берётся более свежее.
     * Возвращает false, только если облако не ответило (нет сети), чтобы позже повторить.
     */
    suspend fun restoreIfEmpty(ctx: Context, kind: String): Boolean {
        if (!isEmpty(kind)) return true
        var local: String? = null
        var localT = 0L
        withContext(Dispatchers.IO) {
            try {
                val f = file(ctx, kind)
                if (f.exists()) { local = f.readText(); localT = f.lastModified() }
            } catch (e: Exception) {
            }
        }
        var cloud: String? = null
        var cloudT = 0L
        var cloudOk = true
        try {
            val snap = docRef(kind).get().awaitIt()
            if (snap.exists()) {
                cloud = snap.getString("json")
                cloudT = snap.getLong("updatedAt") ?: 0L
            }
        } catch (e: Exception) {
            cloudOk = false
        }
        val pick = if (cloud != null && cloudT > localT) cloud else local ?: cloud
        if (pick != null && isEmpty(kind)) {
            try { apply(kind, pick) } catch (e: Exception) { }
        }
        return cloudOk
    }
}

/**
 * Подключается в окне B-Ware: возвращает сохранённую операцию при входе и сохраняет каждое изменение
 * (на телефон через 0,6 с, в облако через 3 с без изменений).
 */
@Composable
fun BWareDraftEffect(kind: String) {
    val ctx = LocalContext.current.applicationContext
    LaunchedEffect(kind) {
        BWareDraft.app = ctx
        var restored = BWareDraft.restoreIfEmpty(ctx, kind)
        // нет сети после переустановки — пробуем ещё, пока окно пустое
        var tries = 0
        while (!restored && tries < 8) {
            delay(15_000)
            tries++
            restored = BWareDraft.restoreIfEmpty(ctx, kind)
        }
        var wasNotEmpty = false
        snapshotFlow { BWareDraft.snapshot(kind) }.collectLatest { json ->
            val empty = JSONObject(json).let {
                it.optJSONArray("rows")?.length() == 0 && it.optString("sender").isBlank() && it.optString("order").isBlank() &&
                    it.optString("truck").isBlank() && it.optString("trailer").isBlank() &&
                    (it.optJSONArray("photos")?.length() ?: 0) == 0
            }
            if (empty) {
                // пользователь сам убрал всё — черновик больше не нужен; но не стираем облако, пока ничего не работало в этом запуске
                if (wasNotEmpty) { wasNotEmpty = false; BWareDraft.delete(kind) }
                return@collectLatest
            }
            wasNotEmpty = true
            delay(600)
            withContext(Dispatchers.IO) {
                try {
                    val f = File(ctx.filesDir, "bware_draft_$kind.json")
                    val tmp = File(ctx.filesDir, "bware_draft_$kind.json.tmp")
                    tmp.writeText(json)
                    if (!tmp.renameTo(f)) { f.writeText(json); tmp.delete() }
                } catch (e: Exception) {
                }
            }
            delay(2400)
            withContext(Dispatchers.IO) {
                try {
                    FirebaseFirestore.getInstance().collection("bware_drafts")
                        .document(Session.login.lowercase().ifBlank { "admin" }.replace('/', '_') + "_" + kind)
                        .set(hashMapOf<String, Any>("json" to json, "updatedAt" to System.currentTimeMillis()))
                } catch (e: Exception) {
                }
            }
        }
    }
}
