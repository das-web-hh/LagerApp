package ru.storage.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * «Прием товаров» в Coglas: открывается из заказа на приход. Окно такое же, как «Инвентаризация»
 * (список товаров с барабаном факта/брака, сканер, поиск, «Далее» → документ), но вместо адреса —
 * поле, по нажатию на которое в мини-окне вводятся: номер накладной, номер грузовика, дата приема.
 * Все позиции заказа берутся как плановые. «Далее» запускает автоматический приём на сайте (CoglasAuto).
 */

/** Одна приемка: привязана к заказу; хранится на телефоне, пока её не сохранят. */
class RecvJob(val orderId: Long, val orderNumber: String, val supplier: String) {
    var waybill by mutableStateOf("")
    var truck by mutableStateOf("")
    var date by mutableStateOf("")
    val rows = mutableStateListOf<ItemRow>()
    /** Плановые позиции заказа: скрыты, пока товар не отсканирован (или не выбран в поиске) — тогда переходят в rows. */
    val pool = mutableStateListOf<ItemRow>()
    /** Дата приема, которую сайт Coglas ставит сам в форме накладной (узнаётся один раз в фоне); в календаре на ней рамка. */
    var siteDate by mutableStateOf("")
    /** Имя упаковки (отсканированный код) для каждого типа упаковки: «Паллет» → «PAL-001». Новое сканирование заменяет имя. */
    val packNames = androidx.compose.runtime.mutableStateMapOf<String, String>()
}

/** Свой цвет у каждого типа упаковки. */
internal fun packColor(p: String): androidx.compose.ui.graphics.Color = when (p) {
    "Паллет" -> androidx.compose.ui.graphics.Color(0xFF42A5F5)
    "Картон" -> androidx.compose.ui.graphics.Color(0xFFFFA726)
    "Коробка" -> androidx.compose.ui.graphics.Color(0xFF66BB6A)
    "Мешок" -> androidx.compose.ui.graphics.Color(0xFFAB47BC)
    "Другое" -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
    else -> androidx.compose.ui.graphics.Color.hsv((p.hashCode().toLong().let { if (it < 0) -it else it } % 360).toFloat(), 0.6f, 0.9f)
}

/** Строки в порядке показа: по части A, B, C…, внутри — по типу упаковки. */
internal fun recvSorted(rows: List<ItemRow>): List<ItemRow> =
    rows.sortedWith(compareBy<ItemRow>({ it.part }, { it.pack.lowercase() }))

/** Ключ группы строк: часть + тип упаковки («A|Паллет»). Под ним хранится имя (код) упаковки. */
internal fun packKey(r: ItemRow): String = r.part + "|" + r.pack

/** Как тип упаковки называется в заголовке группы. «Паллет» — это тип EURO на сайте Coglas. */
internal fun packLabel(p: String): String = when (p) {
    "Паллет" -> "Euro Pallet"
    "Картон" -> "Karton"
    else -> p
}

/** «- A - Euro Pallet - 00850 -» */
internal fun packTitle(key: String, name: String): String =
    "- " + key.substringBefore('|') + " - " + packLabel(key.substringAfter('|')) + " - " + name.trim().ifEmpty { "…" } + " -"

private fun rowToJson(r: ItemRow): JSONObject =
    JSONObject().put("n", r.name).put("q", r.qty).put("x", r.extra).put("f", r.fact).put("d", r.defect)
        .put("e", r.expiry).put("b", r.barcode).put("a", r.article).put("pid", r.productId).put("ph", r.photo)
        .put("pt", r.part).put("pk", r.pack)

private fun rowFromJson(r: JSONObject): ItemRow {
    val row = ItemRow(r.optString("n"), r.optString("q"), extra = r.optBoolean("x"), productId = r.optString("pid"))
    row.fact = r.optString("f")
    row.defect = r.optString("d")
    row.expiry = r.optString("e")
    row.barcode = r.optString("b")
    row.article = r.optString("a")
    row.photo = r.optString("ph")
    row.part = r.optString("pt").ifEmpty { "A" }
    row.pack = r.optString("pk").ifEmpty { "Паллет" }
    return row
}

object ReceiveStore {
    private val jobs = HashMap<Long, RecvJob>()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "coglas_receive_jobs.json")

    fun today(): String = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val j = RecvJob(o.optLong("oid"), o.optString("on"), o.optString("sup"))
                o.optJSONObject("pn")?.let { pn -> pn.keys().forEach { k -> j.packNames[k] = pn.optString(k) } }
                j.waybill = o.optString("wb")
                j.truck = o.optString("tr")
                j.date = o.optString("dt")
                j.siteDate = o.optString("sd")
                val rs = o.optJSONArray("rows")
                if (rs != null) for (k in 0 until rs.length()) j.rows.add(rowFromJson(rs.optJSONObject(k) ?: continue))
                val ps = o.optJSONArray("pool")
                if (ps != null) for (k in 0 until ps.length()) j.pool.add(rowFromJson(ps.optJSONObject(k) ?: continue))
                else {
                    // старый сохранённый приём: нетронутые плановые строки уходят в скрытый план, в списке остаются только посчитанные
                    val untouched = j.rows.filter { !it.extra && it.fact.isBlank() && it.defect.isBlank() && it.expiry.isBlank() && it.photo.isBlank() }
                    j.pool.addAll(untouched)
                    j.rows.removeAll(untouched.toSet())
                }
                // старые имена упаковок хранились по типу («Паллет»): раздаём их частям, которые есть в приёме
                j.packNames.keys.toList().filter { '|' !in it }.forEach { k ->
                    val nm = j.packNames.remove(k).orEmpty()
                    val parts = (j.rows + j.pool).filter { it.pack == k }.map { it.part }.distinct().ifEmpty { listOf("A") }
                    parts.forEach { p -> j.packNames["$p|$k"] = nm }
                }
                jobs[j.orderId] = j
            }
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun save(ctx: Context) {
        try {
            val a = JSONArray()
            jobs.values.toList().forEach { j ->
                val rs = JSONArray()
                j.rows.toList().forEach { r -> rs.put(rowToJson(r)) }
                val ps = JSONArray()
                j.pool.toList().forEach { r -> ps.put(rowToJson(r)) }
                a.put(
                    JSONObject().put("oid", j.orderId).put("on", j.orderNumber).put("sup", j.supplier)
                        .put("wb", j.waybill).put("tr", j.truck).put("dt", j.date).put("sd", j.siteDate).put("rows", rs).put("pool", ps)
                        .put("pn", JSONObject().also { pn -> j.packNames.toMap().forEach { (k, v) -> pn.put(k, v) } })
                )
            }
            file(ctx).writeText(a.toString())
        } catch (e: Exception) {
        }
    }

    /** Приемка этого заказа: уже начатая (счёт сохранён) или новая, где все позиции заказа — план. */
    @Synchronized
    fun forOrder(ctx: Context, o: CgOrder, d: CgOrderDetail): RecvJob {
        load(ctx)
        // рамка в календаре — на «План прихода» заказа (а не на дату, которую сайт ставит в форме накладной)
        val plan = Regex("\\d{2}\\.\\d{2}\\.\\d{4}").find(
            d.header.firstOrNull { it.first == "План прихода" }?.second ?: o.planned
        )?.value.orEmpty()
        jobs[o.id]?.let { if (plan.isNotBlank() && it.siteDate != plan) it.siteDate = plan; return it }
        val j = RecvJob(o.id, o.number, o.supplier)
        j.date = today()
        j.siteDate = plan
        d.positions.forEach { p ->
            val name = p.name.ifBlank { p.title }
            val row = ItemRow(name, fmtQ(p.qty))
            row.barcode = p.gtin
            row.article = p.article
            j.pool.add(row) // список счёта сначала пуст: товар появляется после скана
        }
        jobs[o.id] = j
        save(ctx)
        return j
    }

    @Synchronized
    fun remove(ctx: Context, j: RecvJob) {
        jobs.remove(j.orderId)
        save(ctx)
    }
}

private fun recvSummary(j: RecvJob): String =
    listOf(
        j.waybill.trim().takeIf { it.isNotEmpty() }?.let { "№ $it" },
        j.truck.trim().takeIf { it.isNotEmpty() }?.let { "🚚 $it" },
        j.date.trim().takeIf { it.isNotEmpty() }
    ).filterNotNull().joinToString(" · ")

private const val RECV_CSS = """
@page{size:A4;margin:12mm 10mm}
*{box-sizing:border-box}
body{font-family:'DejaVu Sans',Arial,Helvetica,sans-serif;color:#000;margin:0;font-size:9pt}
h1{font-size:20pt;margin:4mm 0 2mm 2mm}
.meta{margin:0 0 4mm 2mm;font-size:10pt;line-height:1.5}
table{width:100%;border-collapse:collapse;table-layout:fixed}
th,td{border:1px solid #444;padding:3px 4px;vertical-align:middle;word-break:break-word}
th{background:#f1f1f1;font-size:8pt}
td.c{text-align:center}
tr{page-break-inside:avoid}
tr.tot td{font-weight:bold;background:#f7f7f7}
"""

internal fun recvHtml(j: RecvJob, now: Long): String {
    val sb = StringBuilder()
    sb.append("<html><head><meta charset='utf-8'><style>").append(RECV_CSS).append("</style></head><body>")
    sb.append("<h1>Прием товаров</h1><div class='meta'>")
    sb.append("Заказ: <b>").append(bwEsc(j.orderNumber)).append("</b>")
    if (j.supplier.isNotBlank()) sb.append(" · ").append(bwEsc(j.supplier))
    sb.append("<br>Накладная: <b>").append(bwEsc(j.waybill.trim().ifEmpty { "—" })).append("</b>")
    sb.append(" · Грузовик: <b>").append(bwEsc(j.truck.trim().ifEmpty { "—" })).append("</b>")
    sb.append(" · Дата приема: <b>").append(bwEsc(j.date.trim().ifEmpty { "—" })).append("</b>")
    sb.append("<br>").append(bwEsc(fmtTime(now))).append("</div>")
    sb.append("<table><colgroup><col width='6%'><col width='15%'><col width='31%'><col width='16%'><col width='8%'><col width='8%'><col width='8%'><col width='8%'></colgroup>")
    sb.append("<tr><th>№</th><th>Артикул</th><th>Название</th><th>Часть</th><th>План</th><th>Факт</th><th>Брак</th><th>MHD</th></tr>")
    var plan = 0.0
    var fact = 0.0
    var defect = 0.0
    var lastPack: String? = null
    recvSorted(j.rows.toList()).forEachIndexed { i, r ->
        val p = if (r.extra) 0.0 else parseQ(r.qty)
        val f = parseQ(r.fact)
        val d = parseQ(r.defect)
        plan += p; fact += f; defect += d
        if (packKey(r) != lastPack) {
            lastPack = packKey(r)
            sb.append("<tr><td colspan='8' style='background:#e8e8e8;font-weight:bold;border-top:2px solid #000'>${bwEsc(packTitle(packKey(r), j.packNames[packKey(r)].orEmpty()))}</td></tr>")
        }
        sb.append("<tr><td class='c'>${i + 1}</td><td>${bwEsc(r.article)}</td><td>${bwEsc(r.name)}<br><small>${bwEsc(r.barcode)}</small></td><td class='c'>-${bwEsc(r.part)}-</td>")
        sb.append("<td class='c'>${fmtQ(p)}</td><td class='c'>${fmtQ(f)}</td><td class='c'>${if (d > 0) fmtQ(d) else ""}</td><td class='c'>${bwEsc(r.expiry)}</td></tr>")
    }
    sb.append("<tr class='tot'><td colspan='4'>Итого</td><td class='c'>${fmtQ(plan)}</td><td class='c'>${fmtQ(fact)}</td><td class='c'>${fmtQ(defect)}</td><td></td></tr>")
    sb.append("</table></body></html>")
    return sb.toString()
}

private fun recvCsv(j: RecvJob): String = buildString {
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    append("Заказ;").append(q(j.orderNumber)).append("\n")
    append("Накладная;").append(q(j.waybill.trim())).append("\n")
    append("Грузовик;").append(q(j.truck.trim())).append("\n")
    append("Дата приема;").append(q(j.date.trim())).append("\n\n")
    append("Артикул;Название;Штрих-код;Упаковка;Имя упаковки;Часть;План;Факт;Брак;MHD\n")
    recvSorted(j.rows.toList()).forEach { r ->
        append(q(r.article)).append(';').append(q(r.name)).append(';').append(q(r.barcode)).append(';').append(q(r.pack)).append(';')
            .append(q(j.packNames[packKey(r)].orEmpty())).append(';').append(q(r.part)).append(';')
            .append(fmtQ(if (r.extra) 0.0 else parseQ(r.qty))).append(';').append(fmtQ(parseQ(r.fact))).append(';')
            .append(fmtQ(parseQ(r.defect))).append(';').append(q(r.expiry)).append('\n')
    }
}

private fun recvText(j: RecvJob): String = buildString {
    append("Прием товаров\nЗаказ ").append(j.orderNumber).append('\n')
    append("Накладная: ").append(j.waybill.trim().ifEmpty { "—" }).append('\n')
    append("Грузовик: ").append(j.truck.trim().ifEmpty { "—" }).append('\n')
    append("Дата приема: ").append(j.date.trim().ifEmpty { "—" }).append("\n\n")
    var lastPack: String? = null
    recvSorted(j.rows.toList()).forEachIndexed { i, r ->
        if (packKey(r) != lastPack) {
            lastPack = packKey(r)
            append("\n").append(packTitle(packKey(r), j.packNames[packKey(r)].orEmpty())).append("\n")
        }
        append(i + 1).append(". ").append(r.article.ifBlank { "—" }).append(" · ").append(r.name)
            .append(" -").append(r.part).append("-")
            .append(": план ").append(fmtQ(if (r.extra) 0.0 else parseQ(r.qty))).append(", факт ").append(fmtQ(parseQ(r.fact)))
        if (parseQ(r.defect) > 0) append(", брак ").append(fmtQ(parseQ(r.defect)))
        append('\n')
    }
}

/**
 * Окно «Прием товаров». Шаг 0 — счёт товара (как в инвентаризации), шаг 1 — документ.
 * «Назад» (стрелка в шапке и системная кнопка) обрабатывает CoglasScreen через CoglasState.receiveBack().
 */
@Composable
fun ReceiveScreen(job: RecvJob) {
    val ctx = LocalContext.current
    val st = CoglasState
    // состояние пишем на телефон каждые пару секунд и при выходе
    LaunchedEffect(job) { while (true) { delay(2000); ReceiveStore.save(ctx) } }
    // дату приема, которую сайт ставит сам, узнаём один раз в фоне — на ней в календаре будет рамка
    LaunchedEffect(job) { if (job.siteDate.isBlank()) CoglasAuto.probeDate(ctx, job) }
    DisposableEffect(job) { onDispose { ReceiveStore.save(ctx) } }

    if (st.receiveStep == 1) {
        ReceivePreview(job)
        return
    }

    var splitRow by remember { mutableStateOf<ItemRow?>(null) }
    var scanPack by remember { mutableStateOf<String?>(null) }
    var editGroup by remember { mutableStateOf<String?>(null) }
    // «Далее»: без срока годности у посчитанных строк — уведомление и двойное подтверждение (шаг 1 и шаг 2)
    var expiryAsk by remember { mutableStateOf(0) }
    val counted = job.rows.filter { parseQ(it.fact) > 0 }
    val noExpiry = counted.filter { it.expiry.isBlank() }
    // чего не хватает: номер накладной, номер грузовика, срок годности — перед «Далее» двойное подтверждение
    val missing = buildList {
        if (job.waybill.isBlank()) add("номер накладной")
        if (job.truck.isBlank()) add("номер грузовика")
        if (noExpiry.isNotEmpty()) add("срок годности (строк: ${noExpiry.size})")
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // проверка кода упаковки через Инфоскан: сообщение и его вид (0 — идёт проверка, 1 — красное, 2 — оранжевое)
    var packMsg by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var dataDlg by remember { mutableStateOf(false) }
    var datePick by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // «Далее»: открывается вкладка «Сайт», где приём проходит сам; на «Провести» работа останавливается
    val startAuto: () -> Unit = {
        ReceiveStore.save(ctx)
        CoglasAuto.start(ctx, job)
        if (CoglasAuto.running) st.tab = 1
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // под шапкой: слева «адресная строка» (по нажатию — накладная, грузовик, дата), справа «Далее»
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            val summary = recvSummary(job)
            Box(
                Modifier.weight(1f).height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { dataDlg = true }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    summary.ifEmpty { "Накладная · грузовик · дата" },
                    color = if (summary.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            // без количества кнопка не нажимается и серая; когда количество есть — зелёная
            Button(
                onClick = {
                    ReceiveStore.save(ctx)
                    if (missing.isNotEmpty()) expiryAsk = 1 else startAuto()
                },
                enabled = counted.isNotEmpty(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) { Text("Далее") }
        }
        packMsg?.let { (m, kind) ->
            Text(
                m,
                color = when (kind) {
                    1 -> androidx.compose.ui.graphics.Color(0xFFD32F2F)
                    2 -> androidx.compose.ui.graphics.Color(0xFFEF6C00)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (kind == 1) androidx.compose.ui.text.font.FontWeight.Bold else null,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        CatalogWheel(
            rows = job.rows,
            groups = emptyList(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            errorText = error,
            emptyText = "Отсканируйте товар — он появится в списке",
            recvStyle = true,
            pool = job.pool,
            multiScanTick = st.receiveScanTick,
            onAdded = { error = "" },
            scanner = true,
            inventory = true,
            listBase = true,
            // поиск и сканер ищут только среди позиций выбранной накладной (заказа): каталог и сайт не используются
            isolated = true,
            strict = true,
            showAdd = false,
            showPart = true,
            onRowLongPress = { splitRow = it },
            // типы упаковки: по алфавиту, у каждого свой цвет, между типами линия; сверху — имя упаковки и сканер
            groupOf = { packKey(it) },
            rowTint = { packColor(it.pack).copy(alpha = 0.28f) },
            // над каждой частью: линия и строка «- A - Euro Pallet - 00850 -»; нажатие — изменить тип и имя упаковки
            groupHeader = { key ->
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    androidx.compose.material3.HorizontalDivider(thickness = 2.dp, color = packColor(key.substringAfter('|')))
                    Text(
                        packTitle(key, job.packNames[key].orEmpty()),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { editGroup = key }.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            }
        )
    }

    splitRow?.let { r -> SplitDialog(job, r) { splitRow = null } }
    editGroup?.let { key ->
        val part = key.substringBefore('|')
        val pack0 = key.substringAfter('|')
        var pack by remember(key) { mutableStateOf(pack0) }
        var nm by remember(key) { mutableStateOf(job.packNames[key].orEmpty()) }
        AlertDialog(
            onDismissRequest = { editGroup = null },
            title = { Text("Часть $part") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Тип упаковки", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SPLIT_PACKS.forEach { p ->
                            FilterChip(selected = pack == p, onClick = { pack = p }, label = { Text(packLabel(p)) })
                        }
                    }
                    OutlinedTextField(
                        value = nm, onValueChange = { nm = it }, singleLine = true,
                        label = { Text("Имя упаковки (код)") }, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = { editGroup = null; scanPack = key }, modifier = Modifier.fillMaxWidth()) { Text("Сканировать код") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newKey = "$part|$pack"
                    job.rows.filter { it.part == part && it.pack == pack0 }.forEach { it.pack = pack }
                    job.packNames.remove(key)
                    if (nm.isNotBlank()) job.packNames[newKey] = nm.trim()
                    ReceiveStore.save(ctx)
                    editGroup = null
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { editGroup = null }) { Text("Отмена") } }
        )
    }
    if (expiryAsk > 0) {
        val names = missing.joinToString("\n") { "• $it" } +
            (if (noExpiry.isNotEmpty()) "\n\nБез срока годности:\n" + noExpiry.take(5).joinToString("\n") { "· " + it.name.trim() + " (" + it.pack + " -" + it.part + "-)" } +
                (if (noExpiry.size > 5) "\n… и ещё ${noExpiry.size - 5}" else "") else "")
        AlertDialog(
            onDismissRequest = { expiryAsk = 0 },
            title = { Text(if (expiryAsk == 1) "Не всё заполнено" else "Точно продолжить?") },
            text = {
                Text(
                    if (expiryAsk == 1) "Не указано:\n$names\n\nНомера — в строке под шапкой, срок годности — свайп строки вправо."
                    else "Подтвердите ещё раз: приём на сайте будет создан без этих данных:\n" + missing.joinToString("\n") { "• $it" }
                )
            },
            confirmButton = {
                if (expiryAsk == 1) Button(onClick = { expiryAsk = 2 }) { Text("Продолжить без них") }
                else Button(onClick = { expiryAsk = 0; startAuto() }) { Text("Да, продолжить") }
            },
            dismissButton = { TextButton(onClick = { expiryAsk = 0 }) { Text(if (expiryAsk == 1) "Заполнить" else "Назад") } }
        )
    }
    // сканер имени упаковки: новое сканирование заменяет прежнее имя
    scanPack?.let { pack ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { scanPack = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                ScannerScreen(
                    prefs = remember { Prefs(ctx) },
                    continuous = false,
                    status = "Имя упаковки: " + packTitle(pack, ""),
                    onResult = { raw ->
                        val code = raw.trim()
                        val p = pack
                        scanPack = null
                        scope.launch {
                            packMsg = "Проверяю код $code через Инфоскан…" to 0
                            val res = try { CoglasData.infoscan(code) } catch (e: Exception) { null }
                            when {
                                res == null -> {
                                    // нет связи с сайтом: код записываем, но без проверки
                                    job.packNames[p] = code
                                    ReceiveStore.save(ctx)
                                    packMsg = "${packTitle(p, code)} записан без проверки (нет связи с сайтом)" to 2
                                }
                                res.contentTotal > 0 ->
                                    packMsg = "Код $code занят: внутри уже есть товар (${res.contentTotal}). Отсканируйте другой." to 1
                                res.entities.isNotEmpty() && res.entities.none { it.title.startsWith("StorageUnit") } ->
                                    packMsg = "Код $code не пустая упаковка: ${res.entities.first().title.trim()}. Отсканируйте другой." to 1
                                else -> {
                                    // пусто (или кода в системе ещё нет): товары прикрепляются к этому коду
                                    job.packNames[p] = code
                                    ReceiveStore.save(ctx)
                                    packMsg = null
                                    Toast.makeText(ctx, "${packTitle(p, code)} — пусто, ок", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onClose = { scanPack = null }
                )
            }
        }
    }
    if (dataDlg) {
        var wb by remember { mutableStateOf(job.waybill) }
        var tr by remember { mutableStateOf(job.truck) }
        AlertDialog(
            onDismissRequest = { job.waybill = wb; job.truck = tr; dataDlg = false },
            title = { Text("Данные приема") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = wb, onValueChange = { wb = it }, singleLine = true,
                        label = { Text("Номер накладной") }, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tr, onValueChange = { tr = it }, singleLine = true,
                        label = { Text("Номер грузовика") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions.Default
                    )
                    Text("Дата приема", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { datePick = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(job.date.ifBlank { "Выбрать дату" })
                    }
                }
            },
            confirmButton = {
                Button(onClick = { job.waybill = wb.trim(); job.truck = tr.trim(); dataDlg = false; ReceiveStore.save(ctx) }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { job.waybill = wb; job.truck = tr; dataDlg = false }) { Text("Закрыть") } }
        )
    }
    if (datePick) {
        // тот же выбор даты, что и для срока годности
        ExpiryPickerDialog(
            initial = job.date,
            mark = job.siteDate,
            onPick = { job.date = it; datePick = false; ReceiveStore.save(ctx) },
            onDismiss = { datePick = false }
        )
    }
}

@Composable
private fun ReceivePreview(job: RecvJob) {
    val ctx = LocalContext.current
    val st = CoglasState
    val stamp = remember { System.currentTimeMillis() }
    val html = recvHtml(job, stamp)
    val fileBase = "Прием_" + job.orderNumber.replace(Regex("[^A-Za-z0-9А-Яа-я_-]"), "_") + "_" +
        SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date(stamp))
    var sendDlg by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp),
                onClick = {
                    try {
                        DocStore.recordPrinted(ctx, fileBase, html)
                        Toast.makeText(ctx, "Сохранено в «Документы»", Toast.LENGTH_LONG).show()
                        ReceiveStore.remove(ctx, job)
                        st.receiveOpen = false
                        st.receiveStep = 0
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Не удалось сохранить: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                    }
                }
            ) { Text("Сохранить") }
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
                                f.writeText(recvCsv(job), Charsets.UTF_8)
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
                                putExtra(Intent.EXTRA_TEXT, recvText(job))
                            }
                            ctx.startActivity(Intent.createChooser(i, "Отправить текстом"))
                        }
                    ) { Text("Текстом") }
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { sendDlg = false }) { Text("Отмена") }
                }
            }
        )
    }
}

private val SPLIT_PARTS = listOf("A", "B", "C", "D", "E", "F", "G", "H")
private val SPLIT_PACKS = listOf("Паллет", "Картон", "Коробка", "Мешок", "Другое")

/** Тот же товар, что и строка r (по штрих-коду, артикулу или названию). */
private fun sameProduct(a: ItemRow, b: ItemRow): Boolean =
    (a.barcode.isNotBlank() && a.barcode == b.barcode) ||
        (a.article.isNotBlank() && a.article.equals(b.article, true)) ||
        a.name.trim().equals(b.name.trim(), true)

/**
 * Мини-окно по долгому нажатию на строку: «Разделить» — выбрать часть (B, C, D…) и вид упаковки (паллет, картон…).
 * Новая строка того же товара встаёт под этой; сама строка по умолчанию — часть A и паллет.
 */
@Composable
private fun SplitDialog(job: RecvJob, row: ItemRow, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var pack by remember { mutableStateOf("Паллет") }
    // одинаковый товар в разных упаковках — это нормально: занятые части считаем отдельно для каждой упаковки
    fun freePart(p: String): String {
        val used = job.rows.filter { sameProduct(it, row) && it.pack == p }.map { it.part }.toSet()
        return SPLIT_PARTS.firstOrNull { it !in used } ?: "B"
    }
    var partTouched by remember { mutableStateOf(false) }
    var part by remember { mutableStateOf(freePart("Паллет")) }
    var packName by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(pack) { if (!partTouched) part = freePart(pack) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Разделить") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(row.name.trim(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Сейчас: ${row.part} · ${row.pack}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Часть", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPLIT_PARTS.forEach { p ->
                        FilterChip(selected = part == p, onClick = { part = p; partTouched = true }, label = { Text(p) })
                    }
                }
                Text("Упаковка", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPLIT_PACKS.forEach { p ->
                        FilterChip(selected = pack == p, onClick = { pack = p }, label = { Text(packLabel(p)) })
                    }
                }
                OutlinedTextField(
                    value = packName, onValueChange = { packName = it }, singleLine = true,
                    label = { Text("Имя упаковки (код)") }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (job.rows.any { sameProduct(it, row) && it.part == part && it.pack == pack }) {
                    Toast.makeText(ctx, "Часть $part · $pack уже есть", Toast.LENGTH_SHORT).show()
                } else {
                    val n = ItemRow(row.name, "", extra = true, productId = row.productId)
                    n.barcode = row.barcode
                    n.article = row.article
                    n.expiry = row.expiry
                    n.part = part
                    n.pack = pack
                    val at = job.rows.indexOf(row)
                    job.rows.add(if (at >= 0) at + 1 else job.rows.size, n)
                    if (packName.isNotBlank()) job.packNames["$part|$pack"] = packName.trim()
                    ReceiveStore.save(ctx)
                    onClose()
                }
            }) { Text("Разделить") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } }
    )
}
