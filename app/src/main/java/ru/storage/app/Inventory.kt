package ru.storage.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * «Инвентаризация»: пересчёт товара на складе. Работы (в работе) хранятся на телефоне, пока их не сохранят;
 * каждая — адрес/склад и список товаров с количеством, браком, сроком годности, штрих-кодом и фото.
 */
class InvJob(val id: String, address: String, val created: Long) {
    var address by mutableStateOf(address)
    val rows = mutableStateListOf<ItemRow>()
    var step by mutableIntStateOf(0)
}

object InventoryStore {
    val jobs = mutableStateListOf<InvJob>()
    /** Открытая сейчас инвентаризация (переживает смену вкладки). */
    var openId by mutableStateOf<String?>(null)
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "inventory_jobs.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val j = InvJob(o.optString("id"), o.optString("addr"), o.optLong("at"))
                j.step = o.optInt("step", 0)
                val rs = o.optJSONArray("rows")
                if (rs != null) for (k in 0 until rs.length()) {
                    val r = rs.optJSONObject(k) ?: continue
                    val row = ItemRow(r.optString("n"), r.optString("q"), extra = true, productId = r.optString("pid"))
                    row.fact = r.optString("f")
                    row.defect = r.optString("d")
                    row.expiry = r.optString("e")
                    row.barcode = r.optString("b")
                    row.photo = r.optString("ph")
                    row.article = r.optString("a")
                    j.rows.add(row)
                }
                jobs.add(j)
            }
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun save(ctx: Context) {
        try {
            val a = JSONArray()
            jobs.toList().forEach { j ->
                val rs = JSONArray()
                j.rows.toList().forEach { r ->
                    rs.put(
                        JSONObject().put("n", r.name).put("q", r.qty).put("f", r.fact).put("d", r.defect)
                            .put("e", r.expiry).put("b", r.barcode).put("pid", r.productId).put("ph", r.photo).put("a", r.article)
                    )
                }
                a.put(JSONObject().put("id", j.id).put("addr", j.address).put("at", j.created).put("step", j.step).put("rows", rs))
            }
            file(ctx).writeText(a.toString())
        } catch (e: Exception) {
        }
    }

    fun add(ctx: Context): InvJob {
        val j = InvJob(UUID.randomUUID().toString(), "", System.currentTimeMillis())
        jobs.add(0, j)
        save(ctx)
        return j
    }

    fun remove(ctx: Context, j: InvJob) {
        jobs.remove(j)
        if (openId == j.id) openId = null
        save(ctx)
    }
}

private fun invTotal(j: InvJob) = j.rows.sumOf { parseQ(it.fact) }

/** Артикулы строк из каталога (по id товара, названию или штрих-коду); индексы совпадают с j.rows. Вызывать не в главном потоке. */
private fun invArticles(ctx: Context, rows: List<ItemRow>): List<String> {
    val db = ProductDb.get(ctx)
    return rows.map { r ->
        if (r.article.isNotBlank()) return@map r.article.trim()
        try {
            ((if (r.productId.isNotEmpty()) db.getProduct(r.productId) else null)
                ?: db.findByName(r.name.trim())
                ?: (if (r.barcode.isNotBlank()) db.findByBarcode(r.barcode.trim()) else null))?.article.orEmpty()
        } catch (e: Exception) { "" }
    }
}

private const val INV_CSS = """
@page{size:A4;margin:12mm 10mm}
*{box-sizing:border-box}
body{font-family:'DejaVu Sans',Arial,Helvetica,sans-serif;color:#000;margin:0;font-size:9pt}
.ttl{display:flex;align-items:flex-end;margin:6mm 0 5mm 4mm}
.ttl .h{font-size:22pt;font-weight:bold}
.ttl .ln{display:inline-block;width:64mm;border-bottom:1.6px solid #000;margin-left:6mm;height:1.1em}
.meta{margin:0 0 3mm 4mm;font-size:9pt;color:#333}
table.inv{width:100%;border-collapse:collapse;table-layout:fixed}
table.inv th,table.inv td{border:1px solid #444;text-align:center;vertical-align:middle;padding:2px 3px}
table.inv th{background:#f1f1f1;font-size:7.5pt;font-weight:bold;height:11mm}
table.inv thead{display:table-header-group}
table.inv tr{page-break-inside:avoid}
table.inv td{height:17.5mm}
td.n{font-size:12pt;font-weight:bold;word-break:break-all;line-height:1.05}
td.a{font-size:9pt;font-weight:bold;word-break:break-all}
td.t{text-align:left;font-size:11pt;font-weight:600;line-height:1.25;padding-left:5px}
td.q{font-size:14pt;font-weight:bold}
td.q .d{font-size:7.5pt;font-weight:normal;color:#b00}
td.e{font-size:10pt;font-weight:bold}
svg.bc{width:38mm;height:11.5mm}
.bt{font-size:10pt;letter-spacing:.5px}
tr.tot td{height:9mm;font-weight:bold;font-size:10pt;background:#f7f7f7}
.sg{margin-top:6mm}
"""

/** Документ для печати и PDF: как образец «Коробка №» (№, артикул, название, штрих-код картинкой, штук, срок годности). Фото и QR идут ниже. */
private fun invDocHtml(j: InvJob, receiver: String, now: Long, photosHtml: String, qrHtml: String = "", articles: List<String> = emptyList()): String {
    val sb = StringBuilder()
    val L = PrintCfg.invLang
    fun t(ru: String, en: String, de: String) = when (L) { "en" -> en; "de" -> de; else -> ru }
    sb.append("<html><head><meta charset='utf-8'><style>").append(INV_CSS).append("</style></head><body>")
    sb.append("<div class='ttl'><span class='h'>${t("Коробка №", "Box No.", "Karton Nr.")}</span><span class='ln'></span></div>")
    val meta = listOfNotNull(
        j.address.trim().takeIf { it.isNotEmpty() }?.let { "${t("Склад", "Warehouse", "Lager")}: ${bwEsc(it)}" },
        fmtTime(now),
        bwEsc(bwDash(receiver)).takeIf { receiver.isNotBlank() }
    ).joinToString(" · ")
    sb.append("<div class='meta'>$meta</div>")
    sb.append("<table class='inv'><colgroup><col style='width:7%'><col style='width:13%'><col style='width:27%'><col style='width:30%'><col style='width:8%'><col style='width:15%'></colgroup>")
    sb.append("<thead><tr><th>№</th><th>${t("Артикул", "Article", "Artikel")}</th><th>${t("Название товара", "Product name", "Produktname")}</th><th>${t("Штрих-код", "Barcode", "Barcode")}</th><th>${t("Штук", "Pcs", "Stück")}</th><th>${t("Срок годн.", "Best before", "Haltbar bis")}<br>(MHD)</th></tr></thead><tbody>")
    var total = 0.0
    j.rows.forEachIndexed { i, r ->
        val q = parseQ(r.fact)
        val d = parseQ(r.defect)
        total += q
        sb.append("<tr><td class='n'>${i + 1}</td>")
        sb.append("<td class='a'>${bwEsc(articles.getOrNull(i).orEmpty())}</td>")
        sb.append("<td class='t'>${bwEsc(r.name.trim())}</td>")
        sb.append("<td>${Ean.html(r.barcode)}</td>")
        sb.append("<td class='q'>${bwFmt(q)}${if (d > 0.0) "<div class='d'>${t("брак", "defect", "defekt")} ${bwFmt(d)}</div>" else ""}</td>")
        sb.append("<td class='e'>${bwEsc(r.expiry)}</td></tr>")
    }
    sb.append("<tr class='tot'><td></td><td></td><td style='text-align:right;padding-right:6px'>${t("Итого", "Total", "Gesamt")}</td><td></td><td class='q' style='font-size:12pt'>${bwFmt(total)}</td><td></td></tr>")
    sb.append("</tbody></table>")
    if (qrHtml.isNotBlank()) sb.append("<div class='sg'>$qrHtml</div>")
    if (photosHtml.isNotBlank()) sb.append(photosHtml)
    sb.append("</body></html>")
    return sb.toString()
}

/** Таблица для Excel (CSV с «;», UTF-8 с BOM). */
private fun invCsv(j: InvJob, now: Long, articles: List<String> = emptyList()): String {
    fun q(v: String) = "\"" + v.replace("\"", "\"\"").replace('\n', ' ') + "\""
    val sb = StringBuilder("\uFEFF")
    sb.append("Склад;").append(q(j.address.trim())).append(";;;;;\n")
    sb.append("Дата;").append(q(fmtTime(now))).append(";;;;;\n")
    sb.append("№;Артикул;Товар;Штрих-код;Количество;Брак;Срок годности\n")
    j.rows.forEachIndexed { i, r ->
        sb.append(i + 1).append(';').append(q(articles.getOrNull(i).orEmpty())).append(';').append(q(r.name.trim())).append(';').append(q(r.barcode)).append(';')
            .append(bwFmt(parseQ(r.fact))).append(';').append(bwFmt(parseQ(r.defect))).append(';').append(q(r.expiry)).append('\n')
    }
    return sb.toString()
}

/** Текстом для WhatsApp / Telegram. */
private fun invText(j: InvJob, now: Long): String {
    val sb = StringBuilder("Инвентаризация")
    if (j.address.isNotBlank()) sb.append(" — ").append(j.address.trim())
    sb.append("\n").append(fmtTime(now)).append("\n\n")
    j.rows.forEachIndexed { i, r ->
        sb.append(i + 1).append(". ").append(r.name.trim()).append(" — ").append(bwFmt(parseQ(r.fact)))
        if (parseQ(r.defect) > 0.0) sb.append(" (брак ").append(bwFmt(parseQ(r.defect))).append(")")
        if (r.expiry.isNotBlank()) sb.append(", срок ").append(r.expiry)
        sb.append("\n")
    }
    sb.append("\nИтого: ").append(bwFmt(invTotal(j)))
    return sb.toString()
}

/**
 * Штрихкоды и артикулы из инвентаризации попадают в каталог — и в телефон (SQLite), и в облако (Firebase):
 * Cloud.saveProduct пишет в обе базы. Новые товары заводятся с весом/упаковкой из названия;
 * у уже имеющегося товара дописываются только пустые поля (штрих-код, артикул), чужие данные не затираются.
 */
private fun invSaveBarcodes(ctx: Context, j: InvJob) {
    val db = ProductDb.get(ctx)
    j.rows.toList().forEach { r ->
        val code = r.barcode.trim()
        val art = r.article.trim()
        val name = r.name.trim()
        if (code.isEmpty() && art.isEmpty()) return@forEach
        try {
            val p = (if (r.productId.isNotEmpty()) db.getProduct(r.productId) else null)
                ?: (if (name.isNotEmpty()) db.findByName(name) else null)
                ?: (if (code.isNotEmpty()) db.findByBarcode(code) else null)
            if (p == null) {
                if (name.isEmpty()) return@forEach
                val local = ProductParse.parseLocal(listOf(name)).firstOrNull()
                Cloud.saveProduct(
                    db,
                    Product(
                        id = UUID.randomUUID().toString(), name = name, article = art, barcode = code,
                        weight = local?.size ?: "", unit = local?.unit ?: "", pack = local?.pack ?: "",
                        manufacturer = local?.manufacturer ?: "", photos = emptyList(), updatedAt = 0L
                    )
                )
            } else {
                val newCode = if (p.barcode.isBlank() && code.isNotEmpty() && db.findByBarcode(code) == null) code else p.barcode
                val newArt = if (p.article.isBlank() && art.isNotEmpty()) art else p.article
                if (newCode != p.barcode || newArt != p.article) Cloud.saveProduct(db, p.copy(barcode = newCode, article = newArt))
            }
        } catch (e: Exception) {
        }
    }
}

@Composable
fun InventoryScreen(receiver: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { InventoryStore.load(ctx) }
    val open = InventoryStore.jobs.firstOrNull { it.id == InventoryStore.openId }
    if (open != null) {
        InventoryEditor(open, receiver) { InventoryStore.openId = null }
    } else {
        BackHandler { onBack() }
        InventoryHub(onBack)
    }
}

@Composable
private fun InventoryHub(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var delJob by remember { mutableStateOf<InvJob?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text("Инвентаризация", style = MaterialTheme.typography.titleLarge)
        }
        // работы в процессе; пока работ нет, список пустой
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(InventoryStore.jobs.toList(), key = { it.id }) { j ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { InventoryStore.openId = j.id },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(j.address.ifBlank { "Без адреса" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                fmtTime(j.created) + " · позиций: ${j.rows.size} · всего: ${fmtQ(invTotal(j))}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { delJob = j }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Button(
            onClick = {
                val j = InventoryStore.add(ctx)
                InventoryStore.openId = j.id
            },
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text("Добавить", style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.height(8.dp))
    }
    delJob?.let { j ->
        AlertDialog(
            onDismissRequest = { delJob = null },
            title = { Text("Удалить инвентаризацию?") },
            text = { Text("Она не сохранена: все посчитанные товары будут потеряны.") },
            confirmButton = { Button(onClick = { InventoryStore.remove(ctx, j); delJob = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { delJob = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun InventoryEditor(job: InvJob, receiver: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { if (job.step == 1) job.step = 0 else { InventoryStore.save(ctx); onClose() } }
    // состояние пишем на телефон каждые пару секунд и при выходе: не пропадёт при закрытии приложения
    LaunchedEffect(job) { while (true) { delay(2000); InventoryStore.save(ctx) } }
    DisposableEffect(job) { onDispose { InventoryStore.save(ctx) } }

    if (job.step == 1) {
        val stamp = remember { System.currentTimeMillis() }
        val photoNames = job.rows.map { it.photo }.filter { it.isNotBlank() }
        val photosHtml by produceState("", photoNames) { value = bwPhotosHtml(ctx, photoNames) }
        val qrHtml by produceState("", photoNames) { value = PhotoQr.html(ctx, "INV" + job.id.replace("-", "").take(8).uppercase(), photoNames) }
        val articles by produceState(emptyList<String>(), job.rows.size) { value = withContext(Dispatchers.IO) { invArticles(ctx, job.rows.toList()) } }
        val html = invDocHtml(job, receiver, stamp, photosHtml, qrHtml, articles)
        val fileBase = "Инвентаризация_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date(stamp))
        var busy by remember { mutableStateOf(false) }
        var sendDlg by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { job.step = 0 }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
                Text(job.address.ifBlank { "Инвентаризация" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // сверху: «Сохранить», «Распечатать», «Отправить»
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    onClick = {
                        busy = true
                        val app = ctx.applicationContext
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { invSaveBarcodes(app, job) }
                                DocStore.recordPrinted(ctx, fileBase, html)
                                Media.uploadPending(app, ProductDb.get(app))
                                if (DriveCfg.ready(app)) {
                                    val pdf = withContext(Dispatchers.IO) { HtmlPdf.render(app, html) }
                                    if (pdf != null) withContext(Dispatchers.IO) {
                                        val name = "$fileBase.pdf"
                                        try { DriveApi.delete(app, name) } catch (e: Exception) { }
                                        DriveApi.uploadBytes(app, name, "application/pdf", pdf)
                                    }
                                    Toast.makeText(app, "Инвентаризация сохранена: «Документы» и Google Диск", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(app, "Сохранено в «Документы» (Google Диск не настроен)", Toast.LENGTH_LONG).show()
                                }
                                InventoryStore.remove(app, job)
                                onClose()
                            } catch (e: Exception) {
                                Toast.makeText(app, "Не удалось сохранить: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "…" else "Сохранить") }
                OutlinedButton(
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp),
                    onClick = { Printer.print(ctx, fileBase, html) }
                ) { Text("Печать") }
                OutlinedButton(
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp),
                    onClick = { sendDlg = true }
                ) { Text("Отправить") }
            }
            Spacer(Modifier.height(8.dp))
            HtmlPreview(html, Modifier.weight(1f).fillMaxWidth())
        }
        if (sendDlg) {
            AlertDialog(
                onDismissRequest = { sendDlg = false },
                title = { Text("Отправить") },
                text = { Text("WhatsApp, Telegram, почта и другие приложения: таблицей (Excel) или обычным текстом.") },
                confirmButton = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                sendDlg = false
                                try {
                                    val dir = File(ctx.cacheDir, "export").also { it.mkdirs() }
                                    val f = File(dir, "$fileBase.csv")
                                    f.writeText(invCsv(job, stamp, articles), Charsets.UTF_8)
                                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                                    val i = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/csv"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(Intent.createChooser(i, "Отправить таблицу"))
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Не удалось отправить: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                                }
                            }
                        ) { Text("Таблицей (Excel)") }
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                sendDlg = false
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, invText(job, stamp))
                                }
                                ctx.startActivity(Intent.createChooser(i, "Отправить текстом"))
                            }
                        ) { Text("Текстом") }
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = { sendDlg = false }) { Text("Отмена") }
                    }
                }
            )
        }
        return
    }

    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { InventoryStore.save(ctx); onClose() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text("Инвентаризация", style = MaterialTheme.typography.titleLarge)
        }
        // под шапкой: слева адрес (просто поле), справа «Далее»
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactField(
                value = job.address,
                onChange = { job.address = germanKeys(it) },
                hint = "Адрес / склад",
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { error = ""; job.step = 1 },
                enabled = job.rows.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) { Text("Далее") }
        }
        Spacer(Modifier.height(6.dp))
        CatalogWheel(
            rows = job.rows,
            groups = emptyList(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            errorText = error,
            emptyText = "Отсканируйте штрих-код, найдите товар по названию или нажмите «+» (пустой поиск) — камера прочитает этикетки",
            onAdded = { error = "" },
            scanner = true,
            inventory = true
        )
    }
}
