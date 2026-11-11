package ru.storage.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Одна запись журнала: позиция одного работника на одной паллете (списке). */
class InvRow(
    val code: String, val party: String, val worker: String, val src: String, val box: String,
    val name: String, val article: String, val barcode: String, val qty: Double,
    val tAdd: Long, val tFirst: Long, val tLast: Long, val extra: Boolean
)

/**
 * Отчёт инвентаризации для администратора (на немецком, для склада Alfaplan): кто, что, сколько, какой артикул и штрих-код,
 * когда добавил. Excel (4 листа), просмотр и печать. Данные берутся из личных журналов всех устройств (HTML и приложение).
 */
object InvReport {
    private fun fmt(t: Long) = if (t > 0L) SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(t)) else ""
    private fun srcName(s: String) = if (s == "a") "App" else "Browser (Handy)"
    private fun num(d: Double): String = BigDecimal(d).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    private fun key(r: InvRow) = r.barcode.trim().ifBlank { r.article.trim() }.ifBlank { r.name.trim().lowercase() }

    /** Журналы выбранных партий (код → название). Не в главном потоке; при ошибке бросает исключение. */
    fun fetch(ctx: Context, codes: List<String>): List<InvRow> {
        val out = ArrayList<InvRow>()
        for (code in codes) {
            val j = DriveApi.invLog(ctx, code)
            if (!j.optBoolean("ok")) throw RuntimeException(j.optString("error", "ошибка Диска"))
            val party = j.optString("title").ifBlank { code }
            val parts = j.optJSONArray("parts") ?: continue
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val rows = p.optJSONArray("rows") ?: continue
                for (k in 0 until rows.length()) {
                    val r = rows.optJSONObject(k) ?: continue
                    val first = r.optLong("t0").takeIf { it > 0L } ?: r.optLong("ad")
                    val last = r.optLong("t1").takeIf { it > 0L } ?: first
                    out.add(
                        InvRow(
                            code, party, p.optString("name").ifBlank { "?" }, p.optString("src"), p.optString("box"),
                            r.optString("n"), r.optString("a"), r.optString("b"), r.optDouble("q", 0.0),
                            r.optLong("ad"), first, last, r.optInt("x") == 1
                        )
                    )
                }
            }
        }
        return out.sortedByDescending { it.tLast }
    }

    private class Wk(val name: String, val items: Int, val lines: Int, val pcs: Double, val first: Long, val last: Long, val src: String, val boxes: Int)

    private fun workers(rows: List<InvRow>): List<Wk> = rows.groupBy { it.worker }.map { (n, l) ->
        Wk(
            n, l.map { key(it) }.distinct().size, l.size, l.sumOf { it.qty },
            l.map { it.tFirst }.filter { it > 0L }.minOrNull() ?: 0L, l.maxOf { it.tLast },
            l.map { srcName(it.src) }.distinct().joinToString(", "), l.map { it.party + "|" + it.box }.distinct().size
        )
    }.sortedBy { it.name.lowercase() }

    // ---------- Excel ----------
    class B(val v: Any?)           // жирная ячейка
    class H(val v: Any?)           // ячейка заголовка таблицы (жирная, серая)
    class Sheet(val name: String, val rows: List<List<Any?>>, val widths: List<Int>, val freeze: Boolean)

    fun sheets(rows: List<InvRow>): List<Sheet> {
        val now = System.currentTimeMillis()
        val wk = workers(rows)
        val parties = rows.map { it.party }.distinct().joinToString(", ")
        val sum = listOf<List<Any?>>(
            listOf(B("Inventurbericht – Alfaplan")),
            listOf(),
            listOf(B("Erstellt am"), fmt(now)),
            listOf(B("Partie / Liste"), parties),
            listOf(B("Mitarbeiter"), wk.size.toLong()),
            listOf(B("Artikel (Positionen)"), rows.map { key(it) }.distinct().size.toLong()),
            listOf(B("Erfassungszeilen"), rows.size.toLong()),
            listOf(B("Stück gesamt"), rows.sumOf { it.qty }),
            listOf(B("Erste Erfassung"), fmt(rows.map { it.tFirst }.filter { it > 0L }.minOrNull() ?: 0L)),
            listOf(B("Letzte Erfassung"), fmt(rows.maxOfOrNull { it.tLast } ?: 0L)),
            listOf(),
            listOf("Quelle: Browser (Handy) = Seite der Partie, App = Fenster Inventur / Sammlung. Details siehe Blätter Mitarbeiter, Artikel, Protokoll.")
        )
        val mit = listOf<List<Any?>>(
            listOf(H("Mitarbeiter"), H("Positionen"), H("Erfassungszeilen"), H("Stück gesamt"), H("Erste Erfassung"), H("Letzte Erfassung"), H("Paletten / Listen"), H("Quelle"))
        ) + wk.map { listOf<Any?>(it.name, it.items.toLong(), it.lines.toLong(), it.pcs, fmt(it.first), fmt(it.last), it.boxes.toLong(), it.src) }
        val art = listOf<List<Any?>>(
            listOf(H("Artikel-Nr."), H("Artikelbezeichnung"), H("EAN"), H("Gesamtmenge"), H("Mitarbeiter"), H("Erfassungen"))
        ) + rows.groupBy { key(it) }.values.map { l ->
            listOf<Any?>(
                l.map { it.article }.firstOrNull { it.isNotBlank() } ?: "", l.first().name,
                l.map { it.barcode }.firstOrNull { it.isNotBlank() } ?: "",
                l.sumOf { it.qty }, l.map { it.worker }.distinct().size.toLong(), l.size.toLong()
            )
        }.sortedBy { (it[1] as String).lowercase() }
        val prot = listOf<List<Any?>>(
            listOf(
                H("Letzte Änderung"), H("Erste Erfassung"), H("Hinzugefügt"), H("Mitarbeiter"), H("Partie"), H("Palette / Liste"),
                H("Artikel-Nr."), H("Artikelbezeichnung"), H("EAN"), H("Menge"), H("Quelle"), H("Zusatzartikel")
            )
        ) + rows.map {
            listOf<Any?>(
                fmt(it.tLast), fmt(it.tFirst), fmt(it.tAdd), it.worker, it.party, it.box, it.article, it.name, it.barcode, it.qty,
                srcName(it.src), if (it.extra) "Ja" else "Nein"
            )
        }
        return listOf(
            Sheet("Zusammenfassung", sum, listOf(26, 60), false),
            Sheet("Mitarbeiter", mit, listOf(24, 12, 16, 14, 18, 18, 16, 20), true),
            Sheet("Artikel", art, listOf(16, 52, 18, 14, 12, 12), true),
            Sheet("Protokoll", prot, listOf(18, 18, 18, 22, 24, 20, 14, 52, 18, 10, 16, 13), true)
        )
    }

    private fun xml(s: String): String {
        val b = StringBuilder()
        for (c in s) {
            when {
                c == '&' -> b.append("&amp;")
                c == '<' -> b.append("&lt;")
                c == '>' -> b.append("&gt;")
                c == '"' -> b.append("&quot;")
                c.code < 32 && c != '\n' -> b.append(' ')
                else -> b.append(c)
            }
        }
        return b.toString()
    }

    private fun colName(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do { sb.insert(0, ('A' + n % 26)); n = n / 26 - 1 } while (n >= 0)
        return sb.toString()
    }

    private fun sheetXml(s: Sheet): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\">")
        if (s.freeze) sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        sb.append("</sheetView></sheetViews><cols>")
        s.widths.forEachIndexed { i, w -> sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
        sb.append("</cols><sheetData>")
        s.rows.forEachIndexed { ri, row ->
            sb.append("<row r=\"${ri + 1}\">")
            row.forEachIndexed { ci, raw ->
                val style = if (raw is H) 2 else if (raw is B) 1 else 0
                val v: Any? = if (raw is H) raw.v else if (raw is B) raw.v else raw
                val ref = colName(ci) + (ri + 1)
                when (v) {
                    null -> {}
                    is Number -> sb.append("<c r=\"$ref\" s=\"$style\"><v>${num(v.toDouble())}</v></c>")
                    else -> sb.append("<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(v.toString())}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    /** Excel-файл (.xlsx) во временной папке export — оттуда его можно отправить через «Поделиться». Не в главном потоке. */
    fun writeXlsx(ctx: Context, rows: List<InvRow>): File {
        val dir = File(ctx.cacheDir, "export").also { it.mkdirs() }
        val f = File(dir, "Inventurbericht_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date()) + ".xlsx")
        val sh = sheets(rows)
        val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        ZipOutputStream(FileOutputStream(f)).use { z ->
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            put(
                "[Content_Types].xml",
                head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                    sh.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
                    "</Types>"
            )
            put(
                "_rels/.rels",
                head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
            )
            put(
                "xl/workbook.xml",
                head + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
                    sh.mapIndexed { i, s -> "<sheet name=\"${xml(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
                    "</sheets></workbook>"
            )
            put(
                "xl/_rels/workbook.xml.rels",
                head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    sh.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
                    "<Relationship Id=\"rId${sh.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
            )
            put(
                "xl/styles.xml",
                head + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                    "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
                    "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
                    "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE6E6E6\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
                    "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                    "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                    "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                    "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
                    "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/></cellXfs></styleSheet>"
            )
            sh.forEachIndexed { i, s -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(s)) }
        }
        return f
    }

    // ---------- просмотр и печать (немецкий) ----------
    private fun he(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun html(rows: List<InvRow>): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset=\"utf-8\"><style>@page{size:A4 landscape;margin:10mm}body{font:11px/1.3 Arial,sans-serif;color:#000}h1{font-size:18px;margin:0 0 6px}h2{font-size:14px;margin:14px 0 4px}")
        sb.append("table{border-collapse:collapse;width:100%}th,td{border:1px solid #444;padding:2px 4px;text-align:left;vertical-align:top}th{background:#e6e6e6}td.n{text-align:right}tr{page-break-inside:avoid}</style></head><body>")
        sb.append("<h1>Inventurbericht – Alfaplan</h1><div>Erstellt am ${fmt(System.currentTimeMillis())} · Partie / Liste: ${he(rows.map { it.party }.distinct().joinToString(", "))}</div>")
        val wk = workers(rows)
        sb.append("<div>Mitarbeiter: ${wk.size} · Artikel: ${rows.map { key(it) }.distinct().size} · Erfassungszeilen: ${rows.size} · <b>Stück gesamt: ${num(rows.sumOf { it.qty })}</b></div>")
        sb.append("<h2>Mitarbeiter</h2><table><tr><th>Mitarbeiter</th><th>Positionen</th><th>Stück gesamt</th><th>Erste Erfassung</th><th>Letzte Erfassung</th><th>Quelle</th></tr>")
        wk.forEach { sb.append("<tr><td>${he(it.name)}</td><td class=n>${it.items}</td><td class=n>${num(it.pcs)}</td><td>${fmt(it.first)}</td><td>${fmt(it.last)}</td><td>${he(it.src)}</td></tr>") }
        sb.append("</table><h2>Protokoll</h2><table><tr><th>Zeit</th><th>Mitarbeiter</th><th>Palette / Liste</th><th>Artikel-Nr.</th><th>Artikelbezeichnung</th><th>EAN</th><th>Menge</th><th>Hinzugefügt</th></tr>")
        rows.forEach {
            sb.append("<tr><td>${fmt(it.tLast)}</td><td>${he(it.worker)}</td><td>${he(it.box)}</td><td>${he(it.article)}</td><td>${he(it.name)}</td><td>${he(it.barcode)}</td><td class=n>${num(it.qty)}</td><td>${fmt(it.tAdd)}</td></tr>")
        }
        sb.append("</table></body></html>")
        return sb.toString()
    }

    /** Отправка Excel через системное «Поделиться» (WhatsApp, почта и т.д.). */
    fun share(ctx: Context, f: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Inventurbericht")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Inventurbericht senden"))
    }
}

/** «Прочее → Отчёт инвентаризации»: только администратор. Загрузка журналов, просмотр, печать, отправка Excel, очистка. */
@Composable
fun InvReportScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { BatchStore.load(ctx) }
    val sel = remember { mutableStateMapOf<String, Boolean>() }
    var rows by remember { mutableStateOf<List<InvRow>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var clearDlg by remember { mutableStateOf(false) }
    val list = listOf(InvLog.APP_CODE to "Инвентаризация в приложении") +
        BatchStore.batches.toList().filter { it.link.isNotBlank() }.map { it.code to (it.title + if (it.empty) " (пустая)" else "") }
    fun chosen() = list.map { it.first }.filter { sel[it] != false }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text("Отчёт инвентаризации", style = MaterialTheme.typography.titleLarge)
        }
        if (!Session.isAdmin) {
            Text("Отчёт доступен только администратору.")
            return@Column
        }
        Text(
            "Кто, что, сколько, артикул, штрих-код и время — по всем работникам (браузер и приложение). Отчёт на немецком.",
            style = MaterialTheme.typography.bodySmall
        )
        Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
            list.forEach { (code, title) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sel[code] != false, onCheckedChange = { sel[code] = it })
                    Text(title, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && chosen().isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    busy = true
                    msg = ""
                    val app = ctx.applicationContext
                    val codes = chosen()
                    scope.launch {
                        try {
                            val r = KeepAlive.run(app, "Отчёт инвентаризации") { withContext(Dispatchers.IO) { InvReport.fetch(app, codes) } }
                            rows = r
                            msg = if (r.isEmpty()) "Записей пока нет." else "Записей: ${r.size}, работников: ${r.map { it.worker }.distinct().size}"
                        } catch (e: Exception) {
                            msg = "Не удалось загрузить: ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text(if (busy) "Загружаю…" else "Загрузить отчёт") }
        }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        val r = rows
        if (r != null && r.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp),
                    onClick = {
                        busy = true
                        val app = ctx.applicationContext
                        scope.launch {
                            try {
                                val f = withContext(Dispatchers.IO) { InvReport.writeXlsx(app, r) }
                                InvReport.share(ctx, f)
                            } catch (e: Exception) {
                                Toast.makeText(app, "Не удалось создать Excel: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Excel · отправить") }
                OutlinedButton(
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp),
                    onClick = { Printer.print(ctx, "Inventurbericht", InvReport.html(r)) }
                ) { Text("Печать") }
                OutlinedButton(
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp),
                    onClick = { clearDlg = true }
                ) { Text("Очистить") }
            }
            Spacer(Modifier.height(6.dp))
            val html = remember(r) { InvReport.html(r) }
            HtmlPreview(html, Modifier.weight(1f).fillMaxWidth())
        }
    }
    if (clearDlg) {
        AlertDialog(
            onDismissRequest = { clearDlg = false },
            title = { Text("Очистить журнал?") },
            text = { Text("Журналы выбранных партий на Диске будут стёрты (счёт партий не затрагивается). Сначала отправьте Excel.") },
            confirmButton = {
                Button(onClick = {
                    clearDlg = false
                    val app = ctx.applicationContext
                    val codes = chosen()
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                for (c in codes) {
                                    val j = DriveApi.invLogClear(app, c)
                                    if (!j.optBoolean("ok")) throw RuntimeException(j.optString("error", "ошибка Диска"))
                                }
                            }
                            rows = null
                            msg = "Журнал очищен."
                        } catch (e: Exception) {
                            msg = "Не удалось очистить: ${e.message ?: e.javaClass.simpleName}"
                        }
                    }
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { clearDlg = false }) { Text("Отмена") } }
        )
    }
}
