package ru.storage.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.text.HtmlCompat
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Ненайденные штрих-коды → документ-сообщение для офиса (на немецком).
 *
 * Правило: код не найден при первом скане — в список не идёт (запоминается как «подозрительный»).
 * Тот же код подряд второй раз и снова не найден — попадает в список сессии и в документ.
 * Любой другой код между этими сканами сбрасывает счётчик первого.
 *
 * Документ — обычный текстовый документ из окна «Документы» (kind = "text"): его можно править, печатать и отправлять.
 * Одна сессия (одна накладная / один заказ) = один документ: новые коды дописываются в него.
 * Таких документов хранится не больше MAX_DOCS: при создании 11-го самый старый удаляется.
 */
object MissingBarcodes {
    private const val PREFS = "missing_barcodes"
    private const val MAX_DOCS = 10
    private const val SESSION_TTL = 6L * 3600L * 1000L

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pendingCode = ""

    /** Экран, где идёт скан, сообщает: (номер накладной, номер EB). Ставится через MissingBarcodesRef. */
    @Volatile var screenRef: (() -> Pair<String, String>)? = null

    /** Любой скан (даже ещё без результата): если это другой код, чем «подозрительный», счётчик первого сбрасывается. */
    fun touch(code: String) {
        synchronized(lock) {
            val c = code.trim()
            if (pendingCode.isNotEmpty() && pendingCode != c) pendingCode = ""
        }
    }

    /**
     * Результат поиска кода. found = true — товар нашёлся; false — не найден.
     * siteRef — строка со страницы сайта Coglas вида «#EB1234567|номер накладной» (может быть пустой).
     * Можно вызывать из любого потока.
     */
    fun result(ctx: Context, raw: String, found: Boolean, siteRef: String = "") {
        val code = raw.trim()
        if (code.isEmpty()) return
        var confirmed = false
        synchronized(lock) {
            if (found) {
                pendingCode = ""
            } else if (pendingCode == code) {
                pendingCode = ""
                confirmed = true
            } else {
                pendingCode = code
            }
        }
        if (!confirmed) return
        val app = ctx.applicationContext
        Thread {
            try {
                record(app, code, siteRef)
            } catch (e: Exception) {
            }
        }.start()
    }

    /** Накладная проведена / партия закрыта: следующий ненайденный код начнёт новый документ. */
    fun endSession(ctx: Context) {
        synchronized(lock) {
            pendingCode = ""
            sp(ctx).edit().remove("ref").remove("codes").remove("docId").remove("at").remove("started").apply()
        }
    }

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun fmtDate(t: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(t))

    /** «Накладная / EB»: если есть накладная — её номер, если нет — номер EB; если есть оба — оба. */
    private fun refText(screen: Pair<String, String>?, siteRef: String): String {
        var wb = screen?.first?.trim().orEmpty()
        var eb = screen?.second?.trim().orEmpty()
        if (wb.isEmpty() && eb.isEmpty() && siteRef.startsWith("#")) {
            val parts = siteRef.substring(1).split("|", limit = 2)
            eb = parts.getOrElse(0) { "" }.trim()
            wb = parts.getOrElse(1) { "" }.trim()
        }
        return when {
            wb.isNotEmpty() && eb.isNotEmpty() && !wb.equals(eb, true) -> "$wb / $eb"
            wb.isNotEmpty() -> wb
            eb.isNotEmpty() -> eb
            else -> "–"
        }
    }

    private fun buildHtml(ref: String, started: Long, codes: List<String>): String {
        val sb = StringBuilder()
        sb.append("<h2>Mitteilung an das Büro – Nicht gefundene Barcodes</h2>")
        sb.append("<p><b>Referenz / Lieferschein / EBI-Nr.:</b> ").append(esc(ref)).append("</p>")
        sb.append("<p><b>Datum:</b> ").append(fmtDate(started)).append("</p>")
        sb.append("<p>Sehr geehrtes Büro-Team,</p>")
        sb.append("<p>beim Scannen wurden folgende Barcodes im System nicht gefunden (jeweils 2x erfolglos gescannt):</p>")
        codes.forEachIndexed { i, c -> sb.append("<p>").append(i + 1).append(". ").append(esc(c)).append("</p>") }
        sb.append("<p>Bitte um Prüfung und Rückmeldung.</p>")
        return sb.toString()
    }

    private fun record(app: Context, code: String, siteRef: String) {
        synchronized(lock) {
            val sp = sp(app)
            val now = System.currentTimeMillis()
            val ref = refText(try { screenRef?.invoke() } catch (e: Exception) { null }, siteRef)

            val oldRef = sp.getString("ref", null)
            val last = sp.getLong("at", 0L)
            val oldDocId = sp.getString("docId", "").orEmpty()
            val oldCodes = ArrayList<String>()
            try {
                val a = JSONArray(sp.getString("codes", "[]"))
                for (i in 0 until a.length()) oldCodes.add(a.getString(i))
            } catch (e: Exception) {
            }
            val oldDoc = if (oldDocId.isNotEmpty()) DocStore.get(app, oldDocId) else null
            // новая сессия: давно не было ненайденных, другая накладная / заказ, или документ удалён
            val refChanged = oldRef != null && oldRef != ref && oldRef != "–" && ref != "–"
            val fresh = oldRef == null || oldDoc == null || now - last > SESSION_TTL || refChanged

            val codes: ArrayList<String> = if (fresh) ArrayList<String>() else oldCodes
            val keepRef = if (fresh || oldRef == null || (oldRef == "–" && ref != "–")) ref else oldRef
            val started = if (fresh) now else sp.getLong("started", now)

            if (codes.contains(code)) {
                // дубликаты в списке исключаем
                sp.edit().putLong("at", now).apply()
                return
            }
            codes.add(code)

            val docId: String
            val title: String
            if (!fresh && oldDoc != null) {
                // дописываем строку в тот же документ (перед «Bitte um Prüfung…»; правки пользователя сохраняются)
                val body = DocStore.readBody(app, oldDoc.id)
                val line = "<p>" + codes.size + ". " + esc(code) + "</p>"
                val marker = body.indexOf("Bitte um Pr")
                val at = if (marker >= 0) body.lastIndexOf("<p", marker) else -1
                val nb = if (at >= 0) body.substring(0, at) + line + body.substring(at) else body + line
                DocStore.saveBody(app, oldDoc.id, nb)
                docId = oldDoc.id
                title = oldDoc.title
            } else {
                title = ("Nicht gefundene Barcodes – " + (if (keepRef == "–") "" else "$keepRef – ") + fmtDate(started)).take(80)
                val d = DocStore.create(app, "text", title)
                DocStore.saveBody(app, d.id, buildHtml(keepRef, started, codes))
                docId = d.id
                Journal.log("Документы", "Создан документ «$title» (ненайденные штрих-коды)")
                registerAndTrim(app, d.id)
            }

            sp.edit()
                .putString("ref", keepRef)
                .putString("codes", JSONArray(codes).toString())
                .putString("docId", docId)
                .putLong("at", now)
                .putLong("started", started)
                .apply()
            DocStore.push(app)

            main.post {
                try {
                    Toast.makeText(app, "Код $code не найден 2 раза подряд — добавлен в документ «Nicht gefundene Barcodes» (раздел «Документы»)", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                }
            }
        }
    }

    /** Помнит id автоматических документов; хранит последние MAX_DOCS, самый старый вытесняется. */
    private fun registerAndTrim(app: Context, newId: String) {
        val sp = sp(app)
        val ids = ArrayList<String>()
        try {
            val a = JSONArray(sp.getString("docs", "[]"))
            for (i in 0 until a.length()) ids.add(a.getString(i))
        } catch (e: Exception) {
        }
        ids.removeAll { DocStore.get(app, it) == null }   // удалённые вручную уже не считаем
        ids.add(newId)
        while (ids.size > MAX_DOCS) {
            val old = ids.removeAt(0)
            try { DocStore.delete(app, old) } catch (e: Exception) { }
        }
        sp.edit().putString("docs", JSONArray(ids).toString()).apply()
    }

    // ------------------------------------------------------------------ «Поделиться»

    /** HTML документа → простой текст (абзацы сохраняются). */
    fun htmlToText(html: String): String {
        val t = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+\\n"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
        return t.trim()
    }

    /** Стандартное системное меню «Поделиться»: WhatsApp, Telegram, Gmail и т.д. */
    fun share(ctx: Context, title: String, html: String) {
        val text = htmlToText(html)
        if (text.isBlank()) {
            Toast.makeText(ctx, "Документ пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            ctx.startActivity(Intent.createChooser(i, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Не удалось открыть меню отправки", Toast.LENGTH_SHORT).show()
        }
    }
}

/** Ставится на экран, где идёт скан: говорит трекеру, какой накладной / заказу принадлежат сканы. */
@Composable
fun MissingBarcodesRef(waybill: () -> String, eb: () -> String) {
    val w = rememberUpdatedState(waybill)
    val e = rememberUpdatedState(eb)
    DisposableEffect(Unit) {
        val r: () -> Pair<String, String> = { w.value() to e.value() }
        MissingBarcodes.screenRef = r
        onDispose { if (MissingBarcodes.screenRef === r) MissingBarcodes.screenRef = null }
    }
}
