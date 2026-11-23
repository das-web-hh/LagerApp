package ru.storage.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/*
 * «Далее» в приёме товаров: сайт Coglas проходит все шаги сам (скрипт assets/coglas/auto.js):
 * заказ → выбор позиций → «Добавить накладную … (поз.)» → данные накладной → «Добавить товарную накладную» →
 * выбор позиций → «Ручное оприходование (поз.)» → срок, количество, тип, номер упаковки (разделение строк) → «Обработать».
 * На странице «Приход товара» работа останавливается: кнопку «Провести» (Buchen) нажимает только человек.
 */
object CoglasAuto {
    var visible by mutableStateOf(false)
    var running by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var status by mutableStateOf("")
    val warnings = mutableStateListOf<String>()
    private val log = StringBuilder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var curPhase = ""

    private fun stamp(): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())

    private fun note(line: String) {
        log.append(stamp()).append(' ').append(line).append('\n')
        if (log.length > 20000) log.delete(0, log.length - 15000)
    }

    /** Какой вид «типа упаковки» искать в списке «Тип» на сайте. */
    private fun typeRx(pack: String): String = when (pack) {
        "Паллет" -> "euro|палл|palette|europal"
        "Картон", "Коробка" -> "коробк|karton|картон|box|schachtel"
        "Мешок" -> "мешок|sack|säck|beutel|tüte"
        "Ящик" -> "ящик|kiste|crate"
        "Гитербокс" -> "гитер|gitter"
        "Бигбэг" -> "big.?bag|биг"
        "Бочка" -> "fass|бочк|drum"
        "Ведро" -> "eimer|ведр|bucket"
        "Другое", "" -> ""
        // свой тип: ищем в списке «Тип» на сайте по самому названию (спецсимволы экранируются)
        else -> buildString { for (c in pack.trim().lowercase(Locale.ROOT)) { if (c in ".*+?^\$(){}|[]\\") append('\\'); append(c) } }
    }

    /** Задание для скрипта: только посчитанные строки (факт больше нуля), сгруппированные по артикулу. */
    private fun jobJson(j: RecvJob): String {
        val groups = LinkedHashMap<String, MutableList<ItemRow>>()
        j.rows.toList().filter { parseQ(it.fact) > 0 }.forEach { r ->
            val key = r.article.trim().ifEmpty { r.name.trim().lowercase(Locale.ROOT) }
            groups.getOrPut(key) { mutableListOf() }.add(r)
        }
        val items = JSONArray()
        groups.forEach { (_, rs) ->
            val first = rs.first()
            val rows = JSONArray()
            rs.forEach { r ->
                rows.put(
                    JSONObject()
                        .put("part", r.part)
                        .put("pack", r.pack)
                        .put("packName", j.packNames[packKey(r)].orEmpty().trim())
                        .put("typeRx", typeRx(r.pack))
                        .put("qty", fmtQ(parseQ(r.fact)))
                        .put("expiry", r.expiry.trim())
                )
            }
            items.put(
                JSONObject()
                    .put("article", first.article.trim())
                    .put("name", first.name.trim())
                    .put("rows", rows)
            )
        }
        return JSONObject()
            .put("order", j.orderNumber.trim())
            .put("waybill", j.waybill.trim())
            .put("truck", j.truck.trim())
            .put("date", j.date.trim())
            .put("items", items)
            .toString()
    }

    private suspend fun js(code: String): String = withTimeoutOrNull(6000L) {
        suspendCancellableCoroutine<String> { cont ->
            CoglasWeb.runJs(code) { if (cont.isActive) cont.resume(it) }
        }
    } ?: ""

    fun start(ctx: Context, j: RecvJob) {
        stop()
        val app = ctx.applicationContext
        val payload = jobJson(j)
        if (JSONObject(payload).getJSONArray("items").length() == 0) {
            Toast.makeText(app, "Нет посчитанных позиций", Toast.LENGTH_SHORT).show()
            return
        }
        log.setLength(0)
        warnings.clear()
        failed = false
        running = true
        visible = true
        status = "Запускаю…"
        note("старт: заказ ${j.orderNumber}")
        job = scope.launch {
            var st = "{\"phase\":\"order\",\"s\":{}}"
            var lastPhase = "order"
            var lastProgress = System.currentTimeMillis()
            var menuTries = 0
            var menuWaitUntil = 0L
            var lastLine = ""
            while (isActive) {
                delay(700)
                val now = System.currentTimeMillis()
                if (now < menuWaitUntil) continue
                val call = "(window.__cgAutoTick ? window.__cgAutoTick(" + JSONObject.quote(payload) + "," +
                    JSONObject.quote(st) + ") : 'nohook')"
                val raw = js(call)
                if (raw == "nohook") {
                    js(CoglasWeb.asset(app, "auto.js"))
                    continue
                }
                if (raw.isEmpty()) {
                    if (now - lastProgress > 40000) { fail(app, "Сайт не отвечает."); return@launch }
                    continue
                }
                val o = try { JSONObject(raw) } catch (e: Exception) {
                    // «Сайт ещё не открыт.» и другие не-JSON ответы
                    fail(app, raw.take(200)); return@launch
                }
                o.optJSONObject("st")?.let { st = it.toString() }
                val phase = o.optJSONObject("st")?.optString("phase").orEmpty()
                if (phase != lastPhase) { lastPhase = phase; lastProgress = now }
                curPhase = phase
                val msg = o.optString("msg")
                val kind = o.optString("kind")
                o.optJSONArray("warn")?.let { a ->
                    for (i in 0 until a.length()) {
                        val w = a.optString(i)
                        if (w.isNotBlank() && w !in warnings) { warnings.add(w); note("⚠ $w") }
                    }
                }
                val state = o.optString("status")
                val line = "$state | $kind | $phase | $msg"
                if (line != lastLine) { lastLine = line; note(line) }
                if (msg.isNotBlank()) status = msg
                when (state) {
                    "acted" -> lastProgress = now
                    "need_menu" -> {
                        if (menuTries >= 2) { fail(app, "Не удалось открыть список заказов. Откройте заказ на сайте вручную."); return@launch }
                        menuTries++
                        lastProgress = now
                        CoglasWeb.openMenu("Bestellungen", "Заказы")
                        menuWaitUntil = now + 5000
                    }
                    "done" -> {
                        status = msg.ifBlank { "Готово. Проверьте и нажмите «Провести» сами." }
                        running = false
                        note("готово")
                        // позиции отправлены на сайт: сохранённый счёт больше не нужен
                        try { ReceiveStore.remove(app, j) } catch (e: Exception) { }
                        Toast.makeText(app, "Проверьте и нажмите «Провести»", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    "error" -> { fail(app, msg); return@launch }
                }
                if (now - lastProgress > 40000) {
                    fail(app, "Нет движения 40 секунд. Сейчас сайт на шаге «$phase», страница «$kind». " + msg)
                    return@launch
                }
            }
        }
    }

    private var probeJob: Job? = null

    /**
     * Узнать в фоне дату приема, которую сайт ставит сам: открыть заказ, отметить позиции, один раз нажать
     * «Добавить накладную … (поз.)», прочитать «Дата поставки» и закрыть форму кнопкой «Отмена».
     * Результат — j.siteDate (в календаре «Прием товаров» на этой дате рамка). Накладная не создаётся.
     */
    fun probeDate(ctx: Context, j: RecvJob) {
        if (running || probeJob?.isActive == true || j.siteDate.isNotBlank()) return
        val app = ctx.applicationContext
        val items = JSONArray()
        (j.pool.toList() + j.rows.toList()).map { it.article.trim() }.filter { it.isNotEmpty() }.distinct().forEach {
            items.put(JSONObject().put("article", it))
        }
        if (items.length() == 0) return
        val payload = JSONObject().put("order", j.orderNumber.trim()).put("items", items).toString()
        probeJob = scope.launch {
            var st = "{\"phase\":\"order\",\"s\":{}}"
            var menuTries = 0
            var menuWaitUntil = 0L
            val startedAt = System.currentTimeMillis()
            while (isActive && !running && System.currentTimeMillis() - startedAt < 90000L) {
                delay(700)
                val now = System.currentTimeMillis()
                if (now < menuWaitUntil) continue
                val raw = js("(window.__cgProbeTick ? window.__cgProbeTick(" + JSONObject.quote(payload) + "," + JSONObject.quote(st) + ") : 'nohook')")
                if (raw == "nohook") { js(CoglasWeb.asset(app, "auto.js")); continue }
                if (raw.isEmpty()) continue
                val o = try { JSONObject(raw) } catch (e: Exception) { return@launch } // сайт ещё не открыт — без шума
                o.optJSONObject("st")?.let { st = it.toString() }
                when (o.optString("status")) {
                    "need_menu" -> {
                        if (menuTries >= 2) return@launch
                        menuTries++
                        CoglasWeb.openMenu("Bestellungen", "Заказы")
                        menuWaitUntil = now + 5000
                    }
                    "done" -> {
                        val d = o.optString("date").trim()
                        // сайт может показать дату как 22.11.2026 или 2026-11-22
                        val m1 = Regex("^(\\d{2})\\.(\\d{2})\\.(\\d{4})").find(d)
                        val m2 = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(d)
                        j.siteDate = when {
                            m1 != null -> m1.value
                            m2 != null -> m2.groupValues[3] + "." + m2.groupValues[2] + "." + m2.groupValues[1]
                            else -> ""
                        }
                        if (j.siteDate.isNotBlank()) ReceiveStore.save(app)
                        return@launch
                    }
                    "error" -> return@launch
                }
            }
        }
    }

    private suspend fun fail(app: Context, msg: String) {
        status = msg.ifBlank { "Остановлено." }
        failed = true
        running = false
        note("ОШИБКА: $status")
        // направляем на сайт, на то место, где остановились (страница остаётся как есть)
        CoglasState.tab = 1
        try { js("(window.__cgAutoFocus ? window.__cgAutoFocus(" + JSONObject.quote(curPhase) + ") : 'none')") } catch (e: Exception) { }
        // что видно на странице (кнопки, поля) — в отчёт, чтобы можно было поправить скрипт
        try {
            val info = js(CoglasWeb.asset(app, "pageinfo.js"))
            if (info.isNotBlank()) note("страница: " + info.take(1500))
        } catch (e: Exception) { }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (running) { status = "Остановлено вами. Продолжите на сайте вручную."; failed = true }
        running = false
    }

    fun close() {
        stop()
        visible = false
    }

    fun report(): String =
        "Автоприём Coglas\n" + status + "\n" + warnings.joinToString("\n") + "\n\n" + log.toString()
}

/** Плашка внизу вкладки «Сайт»: что делает автоматика, предупреждения, «Стоп». */
@Composable
fun CoglasAutoBanner(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val title = when {
        CoglasAuto.running -> "Автоприём: выполняется…"
        CoglasAuto.failed -> "Автоприём остановлен — продолжите вручную"
        else -> "Готово — нажмите «Провести» сами"
    }
    Card(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (CoglasAuto.status.isNotBlank()) Text(CoglasAuto.status, style = MaterialTheme.typography.bodyMedium)
            CoglasAuto.warnings.take(3).forEach {
                Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (CoglasAuto.warnings.size > 3) {
                Text("… и ещё ${CoglasAuto.warnings.size - 3}", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (CoglasAuto.running) {
                    Button(onClick = { CoglasAuto.stop() }) { Text("Стоп") }
                } else {
                    Button(onClick = { CoglasAuto.close() }) { Text("Закрыть") }
                }
                if (!CoglasAuto.running && (CoglasAuto.failed || CoglasAuto.warnings.isNotEmpty())) {
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("coglas_auto", CoglasAuto.report()))
                        Toast.makeText(ctx, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                    }) { Text("Копировать отчёт") }
                }
            }
        }
    }
}
