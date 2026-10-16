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

    /** Поля страницы в мм → «милы» (1/1000 дюйма). Принтер физически не печатает у самого края листа, поэтому поля 0 нельзя: обрезается верхняя строка и края. */
    private fun margins(mm: Int): PrintAttributes.Margins {
        val mils = (mm * 1000 / 25.4).toInt()
        return PrintAttributes.Margins(mils, mils, mils, mils)
    }

    /** record = true: копия напечатанного попадает в «Документы»; печать всегда пишется в «Журнал». */
    fun print(ctx: Context, jobName: String, html: String, record: Boolean = true, noMargins: Boolean = false) {
        var c: Context? = ctx
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val activity = c as? Activity ?: return
        Journal.log("Печать", "Печать: " + jobName.replace('_', ' ').trim())
        if (record) DocStore.recordPrinted(ctx, jobName, html)
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
                    PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setMinMargins(margins(if (noMargins) 6 else 12))
                        .build()
                )
            }
        }
        current = wv
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    /** Печать готового PDF-файла как есть. */
    fun printPdf(ctx: Context, jobName: String, file: java.io.File) {
        var c: Context? = ctx
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val activity = c as? Activity ?: return
        Journal.log("Печать", "Печать: " + jobName.replace('_', ' ').trim())
        val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(jobName, object : android.print.PrintDocumentAdapter() {
            override fun onLayout(
                old: PrintAttributes?, new: PrintAttributes?, cancel: android.os.CancellationSignal?,
                cb: LayoutResultCallback, extras: android.os.Bundle?
            ) {
                if (cancel?.isCanceled == true) {
                    cb.onLayoutCancelled()
                    return
                }
                cb.onLayoutFinished(
                    android.print.PrintDocumentInfo.Builder(jobName.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".pdf")
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(android.print.PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                        .build(),
                    true
                )
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?, dest: android.os.ParcelFileDescriptor,
                cancel: android.os.CancellationSignal?, cb: WriteResultCallback
            ) {
                try {
                    java.io.FileInputStream(file).use { i ->
                        java.io.FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) }
                    }
                    cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    cb.onWriteFailed(e.message)
                }
            }
        }, null)
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

/** full = общий акт (все позиции); иначе только расхождения. null — расхождений нет. Язык(и) — по настройкам печати. */
private fun buildReport(ctx: Context, job: AutoJob, f: IncomingFile, receiver: String, full: Boolean): String? {
    val ls = lines(job)
    val short = ls.filter { statusOf(it).kind == 1 }
    val excess = ls.filter { statusOf(it).kind == 2 }
    val extra = ls.filter { statusOf(it).kind == 3 }
    val defects = ls.filter { it.defect > 0 }
    if (!full && short.isEmpty() && excess.isEmpty() && extra.isEmpty() && defects.isEmpty()) return null

    val now = System.currentTimeMillis()
    val info = DocLayout.info(
        listOf(
            tr("sender") to job.sender,
            tr("order") to job.order,
            tr("doc_time") to job.stamp,
            tr("received_by") to receiver,
            tr("printed") to fmtTime(now)
        )
    )
    val top = DocLayout.top(info, DocLayout.title(if (full) "title_full" else "title_diff"))
    val sb = StringBuilder()

    if (full) {
        sb.append("<h2>${tr("state_h")}</h2><table class='dt'><tr><th>${trb("th_no")}</th><th>${trb("th_item")}</th><th>${trb("th_plan")}</th><th>${trb("th_fact")}</th>")
        sb.append("<th>${trb("th_defect")}</th><th>${trb("th_good")}</th><th>${trb("th_diff")}</th><th>${trb("th_status")}</th></tr>")
        var tp = 0.0
        var tf = 0.0
        var td = 0.0
        ls.forEachIndexed { i, l ->
            val st = statusOf(l)
            val diff = l.fact - l.plan
            val (cls, text) = when (st.kind) {
                1 -> "bad" to tr("st_short")
                2 -> "warn" to tr("st_excess")
                3 -> "bad" to tr("st_extra")
                else -> "ok" to tr("st_ok")
            }
            tp += l.plan
            tf += l.fact
            td += l.defect
            sb.append("<tr><td>${i + 1}</td><td>${esc(l.name)}</td><td class='n'>${fmtNum(l.plan)}</td>")
            sb.append("<td class='n'>${fmtNum(l.fact)}</td><td class='n'>${fmtNum(l.defect)}</td>")
            sb.append("<td class='n'>${fmtNum(l.fact - l.defect)}</td>")
            sb.append("<td class='n'>${if (diff > 0) "+" else ""}${fmtNum(diff)}</td>")
            sb.append("<td class='$cls'>$text${if (l.defect > 0) ", " + tr("th_defect") else ""}</td></tr>")
        }
        sb.append("<tr class='tot'><th></th><th>${trb("total")}</th><th class='n'>${fmtNum(tp)}</th><th class='n'>${fmtNum(tf)}</th>")
        sb.append("<th class='n'>${fmtNum(td)}</th><th class='n'>${fmtNum(tf - td)}</th><th></th><th></th></tr>")
        sb.append("</table>")
    } else {
        if (short.isNotEmpty()) {
            sb.append("<h2>${tr("h_short")}</h2><table class='dt'><tr><th>${trb("th_item")}</th><th>${trb("th_plan")}</th><th>${trb("th_fact")}</th><th>${trb("th_missing")}</th></tr>")
            short.forEach {
                sb.append("<tr><td>${esc(it.name)}</td><td class='n'>${fmtNum(it.plan)}</td><td class='n'>${fmtNum(it.fact)}</td>")
                sb.append("<td class='n bad'>${fmtNum(it.plan - it.fact)}</td></tr>")
            }
            sb.append("</table>")
        }
        if (excess.isNotEmpty()) {
            sb.append("<h2>${tr("h_excess")}</h2><table class='dt'><tr><th>${trb("th_item")}</th><th>${trb("th_plan")}</th><th>${trb("th_fact")}</th><th>${trb("th_surplus")}</th></tr>")
            excess.forEach {
                sb.append("<tr><td>${esc(it.name)}</td><td class='n'>${fmtNum(it.plan)}</td><td class='n'>${fmtNum(it.fact)}</td>")
                sb.append("<td class='n warn'>${fmtNum(it.fact - it.plan)}</td></tr>")
            }
            sb.append("</table>")
        }
        if (extra.isNotEmpty()) {
            sb.append("<h2>${tr("h_extra")}</h2><table class='dt'><tr><th>${trb("th_item")}</th><th>${trb("th_arrived")}</th></tr>")
            extra.forEach { sb.append("<tr><td>${esc(it.name)}</td><td class='n bad'>${fmtNum(it.fact)}</td></tr>") }
            sb.append("</table>")
        }
        if (defects.isNotEmpty()) {
            sb.append("<h2>${tr("h_defects")}</h2><table class='dt'><tr><th>${trb("th_item")}</th><th>${trb("th_recv_total")}</th><th>${trb("th_defect")}</th></tr>")
            defects.forEach {
                sb.append("<tr><td>${esc(it.name)}</td><td class='n'>${fmtNum(it.fact)}</td><td class='n bad'>${fmtNum(it.defect)}</td></tr>")
            }
            sb.append("</table>")
        }
    }
    return DocLayout.page(top, sb.toString(), "<div></div>" + DocLayout.signBlock(receiver, now), after = itemPhotosHtml(ctx, job))
}

/** Фото товаров, добавленных вручную: последний лист документа, под каждым фото — название товара (6×8 см). */
private fun itemPhotosHtml(ctx: Context, job: AutoJob): String {
    val list = job.items.filter { it.photo.isNotBlank() && it.name.isNotBlank() }
    if (list.isEmpty()) return ""
    val sb = StringBuilder()
    sb.append("<style>.ip{display:inline-block;width:60mm;vertical-align:top;margin:0 4mm 4mm 0;page-break-inside:avoid}.ip .im{width:60mm;height:80mm;overflow:hidden}.ip img{width:100%;height:100%;object-fit:cover;display:block}.ip .cp{font-size:10px;margin-top:2px}</style>")
    sb.append("<div><h2>").append(esc(trList("photos").joinToString(" / "))).append("</h2>")
    for (r in list) {
        try {
            val f = Media.file(ctx, r.photo)
            if (!f.exists() || f.length() == 0L) continue
            val bmp = Media.thumb(f, 600) ?: continue
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos)
            val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            sb.append("<div class='ip'><div class='im'><img src='data:image/jpeg;base64,").append(b64).append("'></div>")
            sb.append("<div class='cp'>").append(esc(r.name.trim())).append("</div></div>")
        } catch (e: Exception) {
        }
    }
    sb.append("</div>")
    return sb.toString()
}

/** Сверка товаров одной партии: печать (мини-окно), сохранение и список «совпало / не совпало». */
@Composable
fun NameSummaryScreen(job: AutoJob, f: IncomingFile, receiver: String, onBack: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var printOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var dupList by remember { mutableStateOf<List<SameDay>>(emptyList()) }
    val ls = lines(job)
    val withDiff = ls.count { statusOf(it).kind != 0 || it.defect > 0 }
    val jobName = "Приёмка_" + f.name.substringBeforeLast('.')

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Сверка товаров", style = MaterialTheme.typography.headlineSmall)
        Text(batchTitle(job, f), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { printOpen = !printOpen }, modifier = Modifier.weight(1f)) {
                Text("🖨 Печать")
            }
            Button(
                enabled = !job.saving && !job.saved,
                modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        // сначала — не принимался ли такой же товар с тем же количеством за этот день
                        val pend = job.pendingSameDay()
                        if (pend.isNotEmpty()) dupList = pend
                        else error = saveJob(ctx, db, job, f, receiver, true) ?: ""
                    }
                }
            ) {
                Text(if (job.saved) "Сохранено ✓" else if (job.saving) "Сохранение…" else "Сохранить")
            }
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        if (job.saved) {
            Spacer(Modifier.height(8.dp))
            Text(
                job.attachedNote.ifBlank { "Приём сохранён в историю. Исходный файл отправляется на Google Диск." },
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
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(if (job.saved) "Закрыть" else "Свернуть (партия останется в работе)")
        }
    }

    if (dupList.isNotEmpty()) {
        SameDayDialog(
            list = dupList,
            onConfirm = { m ->
                m.forEach { (k, v) -> job.dupDecision[k] = v }
                dupList = emptyList()
                scope.launch { error = saveJob(ctx, db, job, f, receiver, true) ?: "" }
            },
            onDismiss = { dupList = emptyList() }
        )
    }

    if (printOpen) {
        AlertDialog(
            onDismissRequest = { printOpen = false },
            confirmButton = {},
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            printOpen = false
                            val html = buildReport(ctx, job, f, receiver, false)
                            if (html == null) {
                                Toast.makeText(ctx, "Расхождений нет", Toast.LENGTH_SHORT).show()
                            } else {
                                Printer.print(ctx, jobName + "_расхождения", html)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Расхождения") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            printOpen = false
                            buildReport(ctx, job, f, receiver, true)?.let { Printer.print(ctx, jobName, it) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Всё") }
                }
            }
        )
    }
}

private fun batchTitle(job: AutoJob, f: IncomingFile): String {
    val sender = job.sender.takeIf { !it.equals("none", true) && it.isNotBlank() }
    val order = job.order.takeIf { !it.equals("none", true) && it.isNotBlank() }
    return listOfNotNull(sender, order?.let { "заказ $it" }).joinToString(" · ").ifBlank { "Партия" }
}

/** «Итог приёма»: все партии в работе одним списком; нажатие на партию открывает её «Сверку товаров». */
@Composable
fun ReceiveTotalScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val jobs = AutoReceiveHolder.jobs.toList()
    var positions = 0
    var diffs = 0
    jobs.forEach { j ->
        val ls = lines(j)
        positions += ls.size
        diffs += ls.count { statusOf(it).kind != 0 || it.defect > 0 }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Итог приёма", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (jobs.isEmpty()) {
            Text("Партий нет")
        } else {
            Text(
                "Партий: ${jobs.size} · позиций: $positions · с расхождениями: $diffs",
                style = MaterialTheme.typography.titleSmall
            )
            jobs.forEach { j ->
                val ls = lines(j)
                val d = ls.count { statusOf(it).kind != 0 || it.defect > 0 }
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(batchTitle(j, j.file), fontWeight = FontWeight.Medium)
                        Text(
                            "Позиций: ${ls.size} · расхождений: $d · ${j.statusLine}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { if (j.nameMode) j.step = 1; Incoming.openPath = j.file.path }) { Text("Сверка товаров") }
                    }
                }
            }
        }
    }
}
