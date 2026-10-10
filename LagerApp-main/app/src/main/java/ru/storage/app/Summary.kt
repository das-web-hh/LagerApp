package ru.storage.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.Locale

/** Печать через системную службу печати Android (можно выбрать принтер или «Сохранить как PDF»). */
object Printer {
    private var current: WebView? = null

    fun print(ctx: Context, jobName: String, html: String) {
        var c: Context? = ctx
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val activity = c as? Activity ?: return
        val wv = WebView(activity)
        var done = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (done) return
                done = true
                val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(
                    jobName,
                    view.createPrintDocumentAdapter(jobName),
                    PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
                )
            }
        }
        current = wv
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}

private class Line(
    val name: String,
    val plan: Double,
    val fact: Double,
    val defect: Double,
    val extra: Boolean
)

/** kind: 0 — совпало, 1 — недостача, 2 — излишек, 3 — нет в плане. */
private class Status(val kind: Int, val diff: Double)

private fun num(s: String): Double = normalizeQty(s).toDoubleOrNull() ?: 0.0

private fun fmtNum(d: Double): String =
    if (d == Math.floor(d)) d.toLong().toString()
    else String.format(Locale.ROOT, "%.2f", d).trimEnd('0').trimEnd('.')

private fun lines(job: AutoJob): List<Line> = job.items.filter { it.name.trim().isNotEmpty() }.map {
    Line(it.name.trim(), if (it.extra) 0.0 else num(it.qty), num(it.fact), num(it.defect), it.extra)
}

private fun statusOf(l: Line): Status = when {
    l.extra && l.fact > 0 -> Status(3, l.fact)
    l.extra -> Status(0, 0.0)
    l.fact < l.plan -> Status(1, l.plan - l.fact)
    l.fact > l.plan -> Status(2, l.fact - l.plan)
    else -> Status(0, 0.0)
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** full = общий акт (все позиции); иначе только расхождения. null — расхождений нет. */
private fun buildReport(job: AutoJob, f: IncomingFile, receiver: String, full: Boolean): String? {
    val ls = lines(job)
    val short = ls.filter { statusOf(it).kind == 1 }
    val excess = ls.filter { statusOf(it).kind == 2 }
    val extra = ls.filter { statusOf(it).kind == 3 }
    val defects = ls.filter { it.defect > 0 }
    if (!full && short.isEmpty() && excess.isEmpty() && extra.isEmpty() && defects.isEmpty()) return null

    val sb = StringBuilder()
    sb.append(
        "<html><head><meta charset='utf-8'><style>" +
            "body{font-family:sans-serif;font-size:13px;color:#111}" +
            "h1{font-size:20px;margin:0 0 8px}h2{font-size:15px;margin:18px 0 6px}" +
            "table{border-collapse:collapse;width:100%}" +
            "th,td{border:1px solid #888;padding:5px 6px;text-align:left;vertical-align:top}" +
            "th{background:#eee}.n{text-align:right;white-space:nowrap}" +
            ".bad{color:#b00020;font-weight:bold}.warn{color:#9a6700;font-weight:bold}.ok{color:#1b6e2d}" +
            ".info td{border:none;padding:2px 6px 2px 0}.sign{margin-top:36px}" +
            "</style></head><body>"
    )
    sb.append("<h1>").append(if (full) "Приёмка товара — общий акт" else "Акт расхождений при приёмке").append("</h1>")
    sb.append("<table class='info'>")
    sb.append("<tr><td>Отправитель:</td><td>${esc(job.sender)}</td></tr>")
    sb.append("<tr><td>Номер заказа:</td><td>${esc(job.order)}</td></tr>")
    sb.append("<tr><td>Дата и время (по документу):</td><td>${esc(job.stamp)}</td></tr>")
    sb.append("<tr><td>Файл:</td><td>${esc(f.name)}</td></tr>")
    sb.append("<tr><td>Принял:</td><td>${esc(receiver)}</td></tr>")
    sb.append("<tr><td>Распечатано:</td><td>${fmtTime(System.currentTimeMillis())}</td></tr>")
    sb.append("</table>")

    if (full) {
        sb.append("<h2>Состояние приёма</h2><table><tr><th>№</th><th>Товар</th><th>План</th><th>Факт</th>")
        sb.append("<th>Брак</th><th>Годный</th><th>Расхождение</th><th>Статус</th></tr>")
        var tp = 0.0
        var tf = 0.0
        var td = 0.0
        ls.forEachIndexed { i, l ->
            val st = statusOf(l)
            val diff = l.fact - l.plan
            val (cls, text) = when (st.kind) {
                1 -> "bad" to "Недостача"
                2 -> "warn" to "Излишек"
                3 -> "bad" to "Нет в плане"
                else -> "ok" to "Совпало"
            }
            tp += l.plan
            tf += l.fact
            td += l.defect
            sb.append("<tr><td>${i + 1}</td><td>${esc(l.name)}</td><td class='n'>${fmtNum(l.plan)}</td>")
            sb.append("<td class='n'>${fmtNum(l.fact)}</td><td class='n'>${fmtNum(l.defect)}</td>")
            sb.append("<td class='n'>${fmtNum(l.fact - l.defect)}</td>")
            sb.append("<td class='n'>${if (diff > 0) "+" else ""}${fmtNum(diff)}</td>")
            sb.append("<td class='$cls'>$text${if (l.defect > 0) ", брак" else ""}</td></tr>")
        }
        sb.append("<tr><th></th><th>Итого</th><th class='n'>${fmtNum(tp)}</th><th class='n'>${fmtNum(tf)}</th>")
        sb.append("<th class='n'>${fmtNum(td)}</th><th class='n'>${fmtNum(tf - td)}</th><th></th><th></th></tr>")
        sb.append("</table>")
    } else {
        if (short.isNotEmpty()) {
            sb.append("<h2>Недостача (привезли меньше плана)</h2><table><tr><th>Товар</th><th>План</th><th>Факт</th><th>Не хватает</th></tr>")
            short.forEach {
                sb.append("<tr><td>${esc(it.name)}</td><td class='n'>${fmtNum(it.plan)}</td><td class='n'>${fmtNum(it.fact)}</td>")
                sb.append("<td class='n bad'>${fmtNum(it.plan - it.fact)}</td></tr>")
            }
            sb.append("</table>")
        }
        if (excess.isNotEmpty()) {
            sb.append("<h2>Излишек (привезли больше плана)</h2><table><tr><th>Товар</th><th>План</th><th>Факт</th><th>Лишних</th></tr>")
            excess.forEach {
                sb.append("<tr><td>${esc(it.name)}</td><td class='n'>${fmtNum(it.plan)}</td><td class='n'>${fmtNum(it.fact)}</td>")
                sb.append("<td class='n warn'>${fmtNum(it.fact - it.plan)}</td></tr>")
            }
            sb.append("</table>")
        }
        if (extra.isNotEmpty()) {
            sb.append("<h2>Товары, которых нет в плане</h2><table><tr><th>Товар</th><th>Привезли</th></tr>")
            extra.forEach { sb.append("<tr><td>${esc(it.name)}</td><td class='n bad'>${fmtNum(it.fact)}</td></tr>") }
            sb.append("</table>")
        }
        if (defects.isNotEmpty()) {
            sb.append("<h2>Бракованный товар</h2><table><tr><th>Товар</th><th>Принято всего</th><th>Брак</th></tr>")
            defects.forEach {
                sb.append("<tr><td>${esc(it.name)}</td><td class='n'>${fmtNum(it.fact)}</td><td class='n bad'>${fmtNum(it.defect)}</td></tr>")
            }
            sb.append("</table>")
        }
    }
    sb.append("<div class='sign'>Принял: ______________________ &nbsp;&nbsp; Представитель отправителя: ______________________</div>")
    sb.append("</body></html>")
    return sb.toString()
}

/** Итог приёма по имени: печать (общая / только расхождения), сохранение и список «совпало / не совпало». */
@Composable
fun NameSummaryScreen(job: AutoJob, f: IncomingFile, receiver: String, onBack: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var printOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val ls = lines(job)
    val withDiff = ls.count { statusOf(it).kind != 0 || it.defect > 0 }
    val jobName = "Приёмка_" + f.name.substringBeforeLast('.')

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Итог приёма", style = MaterialTheme.typography.headlineSmall)
        Text(f.name, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { printOpen = !printOpen }, modifier = Modifier.weight(1f)) {
                Text("🖨 Печать")
            }
            Button(
                enabled = !job.saving && !job.saved,
                modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch { error = saveJob(ctx, db, job, f, receiver, true) ?: "" }
                }
            ) {
                Text(if (job.saved) "Сохранено ✓" else if (job.saving) "Сохранение…" else "Сохранить")
            }
        }
        if (printOpen) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    buildReport(job, f, receiver, true)?.let { Printer.print(ctx, jobName, it) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Печать общая (состояние приёма)") }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    val html = buildReport(job, f, receiver, false)
                    if (html == null) {
                        Toast.makeText(ctx, "Расхождений нет — печатать нечего", Toast.LENGTH_SHORT).show()
                    } else {
                        Printer.print(ctx, jobName + "_расхождения", html)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Печать расхождений") }
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        if (job.saved) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Приём сохранён в историю. Исходный файл отправляется на Google Диск.",
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Позиций: ${ls.size} · с расхождениями: $withDiff",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(4.dp))
        ls.forEach { l ->
            val st = statusOf(l)
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(l.name, fontWeight = FontWeight.Medium)
                    Text(
                        "План ${fmtNum(l.plan)} · Факт ${fmtNum(l.fact)} · Брак ${fmtNum(l.defect)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    when (st.kind) {
                        0 -> Text("✓ Совпало с планом", color = MaterialTheme.colorScheme.primary)
                        1 -> Text("✗ Недостача: −${fmtNum(st.diff)}", color = MaterialTheme.colorScheme.error)
                        2 -> Text("▲ Излишек: +${fmtNum(st.diff)}", color = MaterialTheme.colorScheme.tertiary)
                        else -> Text("＋ Нет в плане: ${fmtNum(st.diff)}", color = MaterialTheme.colorScheme.error)
                    }
                    if (l.defect > 0) {
                        Text("⚠ Брак: ${fmtNum(l.defect)}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("← К вводу количества") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}
