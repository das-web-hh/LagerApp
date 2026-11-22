package ru.storage.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/*
 * Сканер данных сайта Coglas. Открывает пункты меню System один за другим и снимает с каждой страницы:
 * заголовок, кнопки, вкладки, столбцы таблиц, названия полей, число строк, форму и запросы API.
 * Ничего на сайте не нажимает (кроме самих пунктов меню) и не вводит; значения полей и строк таблиц не читает.
 * Результат хранится на телефоне (coglas_schema.json) и из него строится меню во вкладке App.
 */

/** Один пункт меню сайта и всё, что о нём собрано. */
class CgMenuEntry(
    val key: String,
    var de: String,
    var ru: String,
    var group: String,
    var href: String = "",
    var scannedAt: Long = 0L,
    var title: String = "",
    var buttons: List<String> = emptyList(),
    var tabs: List<String> = emptyList(),
    var columns: List<String> = emptyList(),
    var fields: List<String> = emptyList(),
    var rows: Int = 0,
    var tables: Int = 0,
    var form: String = "",
    var apis: List<String> = emptyList(),
    var note: String = ""
)

object CoglasSchema {
    private val list = ArrayList<CgMenuEntry>()
    private var file: File? = null
    /** Меняется при каждом изменении данных: экраны читают его, чтобы перерисоваться. */
    var version by mutableIntStateOf(0)

    fun keyOf(s: String): String = Regex("[^\\p{L}\\p{N}]+").replace(s.lowercase(), "-").trim('-')

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "coglas_schema.json")
        try {
            val f = file!!
            if (f.exists()) {
                val arr = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    fun strs(k: String): List<String> {
                        val a = o.optJSONArray(k) ?: return emptyList()
                        return (0 until a.length()).map { a.optString(it) }
                    }
                    list.add(
                        CgMenuEntry(
                            o.getString("key"), o.optString("de"), o.optString("ru"), o.optString("group"),
                            o.optString("href"), o.optLong("scannedAt"), o.optString("title"),
                            strs("buttons"), strs("tabs"), strs("columns"), strs("fields"),
                            o.optInt("rows"), o.optInt("tables"), o.optString("form"), strs("apis"), o.optString("note")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            list.clear()
        }
        // данных ещё нет — пока показываем известное меню System (без собранных страниц)
        if (list.isEmpty()) {
            for (g in SYSTEM_MENU) for (m in g.items) list.add(CgMenuEntry(keyOf(m.de), m.de, m.ru, g.title))
        }
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(
                    JSONObject().put("key", e.key).put("de", e.de).put("ru", e.ru).put("group", e.group).put("href", e.href)
                        .put("scannedAt", e.scannedAt).put("title", e.title)
                        .put("buttons", JSONArray(e.buttons)).put("tabs", JSONArray(e.tabs))
                        .put("columns", JSONArray(e.columns)).put("fields", JSONArray(e.fields))
                        .put("rows", e.rows).put("tables", e.tables).put("form", e.form)
                        .put("apis", JSONArray(e.apis)).put("note", e.note)
                )
            }
            f.writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
        }
    }

    fun changed() { save(); version++ }

    @Synchronized
    fun all(): List<CgMenuEntry> = ArrayList(list)

    @Synchronized
    fun find(key: String): CgMenuEntry? = list.firstOrNull { it.key == key }

    /** Всё собранное одним текстом: чтобы отправить разработчику и по нему строить экраны App. */
    fun report(): String {
        val sb = StringBuilder()
        val all = all()
        sb.append("Данные сайта Coglas для App\nПунктов меню: ${all.size}, собрано страниц: ${scannedCount()}\n")
        for (e in all) {
            sb.append("\n== ").append(e.ru)
            if (!e.de.equals(e.ru, ignoreCase = true)) sb.append(" / ").append(e.de)
            sb.append("\nгруппа: ").append(e.group).append("\n")
            if (e.scannedAt == 0L) { sb.append("не собрано\n"); continue }
            if (e.note.isNotEmpty()) sb.append("ОШИБКА: ").append(e.note).append("\n")
            if (e.title.isNotEmpty()) sb.append("заголовок: ").append(e.title).append("\n")
            if (e.form.isNotEmpty()) sb.append("форма: ").append(e.form).append("\n")
            if (e.tabs.isNotEmpty()) sb.append("вкладки: ").append(e.tabs.joinToString(" | ")).append("\n")
            if (e.fields.isNotEmpty()) sb.append("поля: ").append(e.fields.joinToString(" | ")).append("\n")
            if (e.buttons.isNotEmpty()) sb.append("кнопки: ").append(e.buttons.joinToString(" | ")).append("\n")
            if (e.columns.isNotEmpty()) sb.append("столбцы: ").append(e.columns.joinToString(" | ")).append("\n")
            sb.append("таблиц: ${e.tables} · строк: ${e.rows}\n")
            if (e.apis.isNotEmpty()) sb.append("API:\n").append(e.apis.joinToString("\n") { "  $it" }).append("\n")
        }
        return sb.toString()
    }

    fun scannedCount(): Int = all().count { it.scannedAt > 0L && it.note.isEmpty() }

    /** Группы меню в порядке появления. */
    fun groups(): List<Pair<String, List<CgMenuEntry>>> {
        val out = LinkedHashMap<String, MutableList<CgMenuEntry>>()
        for (e in all()) out.getOrPut(e.group.ifEmpty { "Прочее" }) { ArrayList() }.add(e)
        return out.map { it.key to it.value }
    }

    /** Список пунктов с сайта: известные получают русские названия и группы, ранее собранные данные сохраняются. */
    @Synchronized
    fun merge(links: List<Triple<String, String, String>>) { // (текст, href, группа с сайта)
        fun nm(x: String) = x.lowercase().replace(Regex("[\\s\\-,.&\u00AD\u200B]+"), "")
        val known = HashMap<String, Pair<MenuItem, String>>()
        for (g in SYSTEM_MENU) for (m in g.items) {
            known[nm(m.de)] = m to g.title
            known[nm(m.ru)] = m to g.title
        }
        val old = list.associateBy { it.key }
        val next = ArrayList<CgMenuEntry>()
        for ((text, href, siteGroup) in links) {
            val k = known[nm(text)]
            val de = k?.first?.de ?: text
            val ru = k?.first?.ru ?: text
            val group = k?.second ?: siteGroup.ifEmpty { "Новые пункты" }
            val key = keyOf(de)
            val e = old[key] ?: CgMenuEntry(key, de, ru, group)
            e.de = de; e.ru = ru; e.group = group; e.href = href
            next.add(e)
        }
        list.clear()
        list.addAll(next)
    }
}

object CoglasScan {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var status by mutableStateOf("")

    /** only = ключ одного пункта (пересканировать только его); null — все пункты меню. */
    fun start(ctx: Context, only: String? = null) {
        if (running) return
        val app = ctx.applicationContext
        running = true
        done = 0
        total = 0
        status = "Запуск…"
        job = scope.launch {
            try {
                run(app, only)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // остановлено кнопкой: сообщение уже выставлено в stop()
            } catch (e: Exception) {
                status = "Сканирование прервано: " + (e.message ?: "ошибка")
            } finally {
                running = false
                CoglasSchema.changed()
            }
        }
    }

    fun stop() {
        job?.cancel()
        status = "Остановлено. Всё, что успели собрать, сохранено."
    }

    private suspend fun js(code: String): String = suspendCancellableCoroutine { c ->
        CoglasWeb.runJs(code) { if (c.isActive) c.resume(it) }
    }

    /** Итог открытия пункта меню: "ok" или текст ошибки. */
    private suspend fun open(e: CgMenuEntry): String =
        withTimeoutOrNull(13000L) {
            suspendCancellableCoroutine<String> { c ->
                CoglasWeb.menuListener = { m -> CoglasWeb.menuListener = null; if (c.isActive) c.resume(m) }
                c.invokeOnCancellation { CoglasWeb.menuListener = null }
                CoglasWeb.openMenu(e.de, e.ru)
            }
        } ?: "нет ответа от сайта".also { CoglasWeb.menuListener = null }

    private suspend fun readMenu(): List<Triple<String, String, String>>? {
        val raw = withTimeoutOrNull(13000L) {
            suspendCancellableCoroutine<String> { c ->
                CoglasWeb.bridge.onMenuList = { j -> CoglasWeb.bridge.onMenuList = null; if (c.isActive) c.resume(j) }
                c.invokeOnCancellation { CoglasWeb.bridge.onMenuList = null }
                CoglasWeb.runJs("window.__cgListMenu ? window.__cgListMenu() : 'nohook'") { r ->
                    if (r == "nohook" && c.isActive) { CoglasWeb.bridge.onMenuList = null; c.resume("") }
                }
            }
        }
        CoglasWeb.bridge.onMenuList = null
        if (raw.isNullOrEmpty()) return null
        return try {
            val o = JSONObject(raw)
            if (o.has("err")) { status = o.optString("err"); return null }
            val a = o.optJSONArray("items") ?: return null
            (0 until a.length()).map {
                val x = a.getJSONObject(it)
                Triple(x.optString("text"), x.optString("href"), x.optString("group"))
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun run(ctx: Context, only: String?) {
        CoglasSchema.init(ctx)
        if (only == null) {
            status = "Читаю меню System…"
            val links = readMenu()
            if (links == null || links.size < 3) {
                if (status == "Читаю меню System…") {
                    status = "Сайт не готов. Откройте вкладку «Сайт», войдите в Coglas и повторите."
                }
                return
            }
            CoglasSchema.merge(links)
            CoglasSchema.changed()
        }
        val targets = if (only != null) listOfNotNull(CoglasSchema.find(only)) else CoglasSchema.all()
        total = targets.size
        for (e in targets) {
            currentCoroutineContext().ensureActive()
            status = "Сканирую: ${e.ru} (${done + 1} из $total)"
            scanPage(ctx, e)
            done++
            CoglasSchema.changed()
        }
        val ok = targets.count { it.note.isEmpty() }
        status = "Готово: собрано страниц $ok из $total" + if (ok < total) ". Пункты с ошибкой отмечены на их страницах." else "."
    }

    private suspend fun scanPage(ctx: Context, e: CgMenuEntry) {
        // закрыть окошко, оставшееся от прошлого пункта
        js(
            "(function(){try{document.querySelectorAll('.modal.show .btn-close,.modal.show [data-bs-dismiss],.modal.show [data-dismiss]')" +
                ".forEach(function(b){b.click();});document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',keyCode:27,bubbles:true}));}catch(x){}return 'ok';})()"
        )
        delay(300)
        e.note = ""
        val r = open(e)
        if (r != "ok") {
            e.note = r
            e.scannedAt = System.currentTimeMillis()
            return
        }
        delay(1200)
        val script = CoglasWeb.asset(ctx, "menuscan.js")
        var last = ""
        var stable = 0
        var json = ""
        val t0 = System.currentTimeMillis()
        // ждём, пока страница перестанет меняться (таблицы подгружаются не сразу)
        while (System.currentTimeMillis() - t0 < 10000L) {
            json = js(script)
            if (json.isNotEmpty() && json == last) { if (++stable >= 2) break } else { stable = 0; last = json }
            delay(800)
        }
        try {
            val o = JSONObject(json)
            if (o.optBoolean("map")) {
                e.note = "Пункт не открылся (осталось меню System)."
            } else {
                fun strs(k: String): List<String> {
                    val a = o.optJSONArray(k) ?: return emptyList()
                    return (0 until a.length()).map { a.optString(it) }
                }
                e.title = o.optString("title").take(80)
                e.buttons = strs("buttons"); e.tabs = strs("tabs"); e.columns = strs("columns"); e.fields = strs("fields")
                e.rows = o.optInt("rows"); e.tables = o.optInt("tables")
                val form = CoglasPages.currentKey()
                e.form = form.removePrefix("form:")
                e.apis = CoglasPages.apisOf(form)
            }
        } catch (x: Exception) {
            e.note = "Не удалось прочитать страницу."
        }
        e.scannedAt = System.currentTimeMillis()
    }
}
