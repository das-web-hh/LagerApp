package ru.storage.app

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val RU_LOCALE = Locale("ru")

private class InfoData(
    val receipts: List<Receipt>,
    val pakets: List<Paket>,
    val wagen: List<WagenOp>,
    val prevReceipts: Int,
    val prevUnits: Double,
    /** товары, которые за период впервые появились в истории приёмов */
    val newProducts: Int = 0,
    /** записи журнала этого аккаунта за период: всё, что добавлено, изменено, удалено */
    val journal: List<JournalEntry> = emptyList()
)

private fun iNum(s: String): Double = normalizeQty(s).toDoubleOrNull() ?: 0.0

private fun iFmt(d: Double): String =
    if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.getDefault(), "%.1f", d)

private fun iDayStart(ms: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun iAddDays(ms: Long, d: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    c.add(Calendar.DAY_OF_YEAR, d)
    return c.timeInMillis
}

private fun iUnits(r: Receipt): Double = r.items.sumOf { iNum(it.qty) }
private fun iDefects(r: Receipt): Double = r.items.sumOf { iNum(it.defect) }
private fun iHm(ms: Long): String = SimpleDateFormat("HH:mm", RU_LOCALE).format(Date(ms))

private val periodLabels = listOf("День", "Неделя", "Месяц")
private val periodDays = listOf(1, 7, 30)

private fun periodTitle(period: Int, anchor: Long): String {
    if (period == 0) return SimpleDateFormat("d MMMM yyyy, EEEE", RU_LOCALE).format(Date(anchor))
    val from = iAddDays(anchor, -(periodDays[period] - 1))
    val f = SimpleDateFormat("d MMM", RU_LOCALE)
    return f.format(Date(from)) + " – " + SimpleDateFormat("d MMM yyyy", RU_LOCALE).format(Date(anchor))
}

private fun buildReport(period: Int, anchor: Long, d: InfoData): String {
    val units = d.receipts.sumOf { iUnits(it) }
    val sb = StringBuilder()
    sb.append("Итоги: ").append(periodTitle(period, anchor)).append('\n')
    sb.append("Принято: ").append(iFmt(units)).append(" шт, приёмок ").append(d.receipts.size)
        .append(", брак ").append(iFmt(d.receipts.sumOf { iDefects(it) })).append('\n')
    if (d.receipts.isNotEmpty()) {
        val first = d.receipts.minOf { it.receivedAt }
        val last = d.receipts.maxOf { it.receivedAt }
        sb.append("Первая приёмка ").append(iHm(first)).append(", последняя ").append(iHm(last)).append('\n')
    }
    sb.append("Post paket: ").append(d.pakets.size).append('\n')
    sb.append("Postwagen: отправлено ").append(d.wagen.sumOf { it.sent })
        .append(", получено ").append(d.wagen.sumOf { it.received }).append('\n')
    if (d.receipts.isNotEmpty()) {
        sb.append("\nПриёмки:\n")
        d.receipts.sortedBy { it.receivedAt }.forEach {
            sb.append(iHm(it.receivedAt)).append(' ')
                .append(it.sender.ifBlank { "без отправителя" })
            if (it.orderNo.isNotBlank()) sb.append(" · заказ ").append(it.orderNo)
            sb.append(" · ").append(iFmt(iUnits(it))).append(" шт\n")
        }
    }
    return sb.toString()
}

private fun csvCell(s: String): String = "\"" + s.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ") + "\""

/** Таблица за выбранный период (открывается в Excel): приёмки, их позиции, посылки Post paket, операции Postwagen. */
private fun buildCsv(d: InfoData): String {
    val df = SimpleDateFormat("dd.MM.yyyy", RU_LOCALE)
    val tf = SimpleDateFormat("HH:mm:ss", RU_LOCALE)
    val sb = StringBuilder("\uFEFF")
    fun row(vararg c: String) {
        sb.append(c.joinToString(";") { csvCell(it) }).append("\r\n")
    }
    row("Тип", "Дата", "Время", "Кто", "Отправитель / получатель", "Заказ / номер посылки", "Количество", "Детали")
    d.receipts.sortedBy { it.receivedAt }.forEach { r ->
        val dt = Date(r.receivedAt)
        row(
            "Приёмка", df.format(dt), tf.format(dt), r.receiver, r.sender, r.orderNo, iFmt(iUnits(r)),
            "позиций ${r.items.size}; брак ${iFmt(iDefects(r))}; накладных ${r.invoices.size}; фото ${r.photos.size}"
        )
        r.items.forEach { it2 ->
            row(
                "Позиция", df.format(dt), tf.format(dt), r.receiver, r.sender, r.orderNo, iFmt(iNum(it2.qty)),
                it2.name + (if (it2.plan.isNotBlank()) "; план " + it2.plan else "") +
                    (if (it2.defect.isNotBlank()) "; брак " + it2.defect else "")
            )
        }
    }
    d.pakets.sortedBy { it.createdAt }.forEach { p ->
        val dt = Date(p.createdAt)
        row(
            "Post paket", df.format(dt), tf.format(dt), p.author,
            listOf(p.recipient, p.street, p.zip + " " + p.city).filter { it.isNotBlank() }.joinToString(", "),
            p.tracking, "1",
            listOf(p.product, if (p.weight.isNotBlank()) p.weight + " кг" else "", p.services, p.note)
                .filter { it.isNotBlank() }.joinToString("; ")
        )
    }
    d.wagen.sortedBy { it.createdAt }.forEach { w ->
        val dt = Date(w.createdAt)
        row(
            "Postwagen", df.format(dt), tf.format(dt), w.author, "", w.id, "${w.sent}/${w.received}",
            "отправлено ${w.sent}; получено ${w.received}" + (if (w.note.isNotBlank()) "; " + w.note else "")
        )
    }
    return sb.toString()
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionHead(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun TopLine(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * «Инфо»: итоги за день / неделю / месяц — приёмки (время, отправитель, заказ, количество, брак, накладные и фото),
 * отправки Post paket и DHL Postwagen, рабочее время, график по часам, топы, сравнение с прошлым периодом.
 * Любую приёмку можно открыть; итог можно отправить текстом.
 */
@Composable
fun InfoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val sdb = remember { SendDb.get(ctx) }
    var period by rememberSaveable { mutableIntStateOf(0) }
    var anchor by rememberSaveable { mutableStateOf(iDayStart(System.currentTimeMillis())) }
    var openReceipt by rememberSaveable { mutableStateOf<String?>(null) }
    var openPaket by rememberSaveable { mutableStateOf<String?>(null) }
    var openWagen by rememberSaveable { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<InfoData?>(null) }
    val sync = remember { SyncState() }
    var tick by remember { mutableIntStateOf(0) }

    // при открытии подтягиваем новые приёмки из Firebase, а пока окно открыто — перечитываем данные
    LaunchedEffect(Unit) {
        try { SendCloud.pull(sdb) } catch (e: Exception) { }
        try { Cloud.runReceipts(db, sync, true) } catch (e: Exception) { }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(4000)
            tick++
        }
    }
    LaunchedEffect(period, anchor, SendState.version, sync.version, Media.version, tick) {
        data = withContext(Dispatchers.IO) {
            val from = iAddDays(anchor, -(periodDays[period] - 1))
            val to = iAddDays(anchor, 1)
            val prevFrom = iAddDays(from, -periodDays[period])
            val prev = db.receiptsBetween(prevFrom, from)
            // новые товары: название, которое за этот период встретилось в истории впервые (по всем приёмкам из базы)
            val firstSeen = HashMap<String, Long>()
            db.allReceipts().forEach { r ->
                r.items.forEach { i ->
                    val k = i.name.trim().lowercase()
                    if (k.isNotEmpty()) {
                        val t = firstSeen[k]
                        if (t == null || r.receivedAt < t) firstSeen[k] = r.receivedAt
                    }
                }
            }
            val journal = try { Journal.entries(ctx).filter { it.t >= from && it.t < to } } catch (e: Exception) { emptyList() }
            InfoData(
                db.receiptsBetween(from, to), sdb.paketsBetween(from, to), sdb.wagenBetween(from, to),
                prev.size, prev.sumOf { iUnits(it) },
                firstSeen.values.count { it >= from && it < to }, journal
            )
        }
    }

    val opened = openReceipt
    if (opened != null) {
        ReceiptCardScreen(opened) { openReceipt = null }
        return
    }
    val openedPaket = openPaket
    if (openedPaket != null) {
        PaketDetailScreen(openedPaket) { openPaket = null }
        return
    }
    val openedWagen = openWagen
    if (openedWagen != null) {
        WagenDetailScreen(openedWagen) { openWagen = null }
        return
    }
    BackHandler(onBack = onBack)

    val today = iDayStart(System.currentTimeMillis())
    val d = data

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Инфо", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                periodLabels.forEachIndexed { i, l ->
                    FilterChip(selected = period == i, onClick = { period = i; showAll = false }, label = { Text(l) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { anchor = iAddDays(anchor, -periodDays[period]) }) { Text("‹", fontSize = 24.sp) }
                Text(
                    periodTitle(period, anchor), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                TextButton(
                    onClick = { anchor = minOf(iAddDays(anchor, periodDays[period]), today) },
                    enabled = anchor < today
                ) { Text("›", fontSize = 24.sp) }
            }
            if (anchor != today) {
                TextButton(onClick = { anchor = today }) { Text("Сегодня") }
            }
        }

        if (d == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            return@LazyColumn
        }

        val units = d.receipts.sumOf { iUnits(it) }
        val defects = d.receipts.sumOf { iDefects(it) }
        val lines = d.receipts.sumOf { it.items.size }
        val mismatches = d.receipts.sumOf { r ->
            r.items.count { it.plan.isNotBlank() && iNum(it.plan) != iNum(it.qty) }
        }
        val docs = d.receipts.sumOf { it.invoices.size }
        val photos = d.receipts.sumOf { it.photos.size }

        item {
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Принято товара", style = MaterialTheme.typography.labelLarge)
                    Text("${iFmt(units)} шт", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    val delta = units - d.prevUnits
                    val cmp = when {
                        d.prevReceipts == 0 && d.prevUnits == 0.0 -> "в прошлом периоде приёмок не было"
                        d.prevUnits == 0.0 -> "в прошлом периоде: 0 шт"
                        else -> {
                            val pct = (delta / d.prevUnits * 100).toInt()
                            (if (delta >= 0) "+" else "") + pct + "% к прошлому периоду (" + iFmt(d.prevUnits) + " шт)"
                        }
                    }
                    Text(cmp, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Приёмок", d.receipts.size.toString(), Modifier.weight(1f))
                MetricTile("Позиций", lines.toString(), Modifier.weight(1f))
            }
            // считаются все приёмки из базы: по накладной (автоприём, по наименованию) и вручную
            val byFile = d.receipts.count { it.fileName.isNotBlank() }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("По накладной", byFile.toString(), Modifier.weight(1f))
                MetricTile("Вручную", (d.receipts.size - byFile).toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Брак, шт", iFmt(defects), Modifier.weight(1f))
                MetricTile("Расхождений с планом", mismatches.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Накладных", docs.toString(), Modifier.weight(1f))
                MetricTile("Фото", photos.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Post paket", d.pakets.size.toString(), Modifier.weight(1f))
                MetricTile("Фото этикеток", d.pakets.sumOf { it.photos.size }.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Postwagen отправлено", d.wagen.sumOf { it.sent }.toString(), Modifier.weight(1f))
                MetricTile("Postwagen получено", d.wagen.sumOf { it.received }.toString(), Modifier.weight(1f))
            }
            // всё, что появилось и изменилось: новые товары в истории и операции из других окон (каталог, приёмки, отправка…)
            fun jc(cat: String, vararg starts: String) =
                d.journal.count { e -> e.cat == cat && starts.any { e.text.startsWith(it) } }
            SectionHead("Что добавлено и изменено")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Новых товаров в истории", d.newProducts.toString(), Modifier.weight(1f))
                MetricTile("Добавлено в каталог", jc("Каталог", "Добавлен товар", "Импорт").toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Изменено товаров", jc("Каталог", "Изменён товар").toString(), Modifier.weight(1f))
                MetricTile("Изменено приёмок", jc("Приём", "Изменена партия").toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile("Удалено", (jc("Приём", "Удалена партия") + jc("Каталог", "Удалён товар")).toString(), Modifier.weight(1f))
                MetricTile("Операций в журнале", d.journal.size.toString(), Modifier.weight(1f))
            }
            if (d.journal.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                d.journal.sortedByDescending { it.t }.take(8).forEach { e ->
                    TopLine(iHm(e.t) + " · " + e.text, e.cat)
                }
                Text(
                    "Показаны операции этого аккаунта; полный список — в «Прочее → Журнал».",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val stamps = d.receipts.map { it.receivedAt } + d.pakets.map { it.createdAt } + d.wagen.map { it.createdAt }
            if (period == 0 && stamps.size > 1) {
                val a = stamps.min()
                val b = stamps.max()
                val mins = (b - a) / 60000L
                Spacer(Modifier.height(10.dp))
                Text(
                    "Активность за день: ${iHm(a)} – ${iHm(b)} (${mins / 60} ч ${mins % 60} мин)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (d.receipts.isNotEmpty()) {
                val first = d.receipts.minOf { it.receivedAt }
                val last = d.receipts.maxOf { it.receivedAt }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (period == 0) "Первая приёмка ${iHm(first)}, последняя ${iHm(last)}"
                    else "Первая: ${fmtTime(first)}\nПоследняя: ${fmtTime(last)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (period == 0 && d.receipts.isNotEmpty()) {
            item {
                SectionHead("По часам")
                val byHour = DoubleArray(24)
                d.receipts.forEach {
                    val c = Calendar.getInstance()
                    c.timeInMillis = it.receivedAt
                    byHour[c.get(Calendar.HOUR_OF_DAY)] += iUnits(it)
                }
                val mx = byHour.max().coerceAtLeast(1.0)
                Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.Bottom) {
                    for (h in 0..23) {
                        val f = (byHour[h] / mx).toFloat().coerceIn(0.03f, 1f)
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier.fillMaxWidth().fillMaxHeight(f).padding(horizontal = 1.dp)
                                    .background(
                                        if (byHour[h] > 0) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("0", "6", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }

        item {
            SectionHead("Отправка")
            val recognised = d.pakets.count { it.status == "done" || it.status == "manual" }
            val check = d.pakets.count { it.status == "check" || it.status == "error" }
            TopLine("Post paket", d.pakets.size.toString())
            if (d.pakets.isNotEmpty()) {
                Text(
                    "распознано: $recognised" + if (check > 0) ", нужна проверка: $check" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TopLine("DHL Postwagen · отправлено", d.wagen.sumOf { it.sent }.toString())
            TopLine("DHL Postwagen · получено", d.wagen.sumOf { it.received }.toString())
            Text("операций Postwagen: ${d.wagen.size}", style = MaterialTheme.typography.bodySmall)
        }

        if (d.receipts.isNotEmpty()) {
            val topSenders = d.receipts.groupBy { it.sender.ifBlank { "без отправителя" } }
                .map { it.key to it.value.sumOf { r -> iUnits(r) } }
                .sortedByDescending { it.second }.take(5)
            val topItems = HashMap<String, Double>()
            d.receipts.forEach { r -> r.items.forEach { i ->
                if (i.name.isNotBlank()) topItems[i.name] = (topItems[i.name] ?: 0.0) + iNum(i.qty)
            } }
            val topProducts = topItems.entries.sortedByDescending { it.value }.take(5)
            item {
                SectionHead("Топ отправителей")
                topSenders.forEach { TopLine(it.first, iFmt(it.second) + " шт") }
                SectionHead("Топ товаров")
                topProducts.forEach { TopLine(it.key, iFmt(it.value) + " шт") }
                val byWho = d.receipts.filter { it.receiver.isNotBlank() }.groupBy { it.receiver }
                    .map { Triple(it.key, it.value.size, it.value.sumOf { r -> iUnits(r) }) }
                    .sortedByDescending { it.third }.take(5)
                if (byWho.isNotEmpty()) {
                    SectionHead("Кто принимал")
                    byWho.forEach { TopLine(it.first + " · приёмок " + it.second, iFmt(it.third) + " шт") }
                }
                SectionHead("Приёмки")
            }
            val shown = if (showAll) d.receipts else d.receipts.take(30)
            items(shown, key = { it.id }) { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { openReceipt = r.id }.padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.sender.ifBlank { if (r.orderNo.isNotBlank()) "Заказ № ${r.orderNo}" else "Приёмка" },
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val sub = buildList {
                            add(if (period == 0) iHm(r.receivedAt) else fmtTime(r.receivedAt))
                            if (r.orderNo.isNotBlank() && r.sender.isNotBlank()) add("заказ ${r.orderNo}")
                            if (r.receiver.isNotBlank()) add(r.receiver)
                        }.joinToString(" · ")
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val extra = buildList {
                            if (r.invoices.isNotEmpty()) add("накладных ${r.invoices.size}")
                            if (r.photos.isNotEmpty()) add("фото ${r.photos.size}")
                            val df = iDefects(r)
                            if (df > 0) add("брак ${iFmt(df)}")
                        }.joinToString(" · ")
                        if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.labelSmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${iFmt(iUnits(r))} шт", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Text("поз. ${r.items.size}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                HorizontalDivider()
            }
            if (!showAll && d.receipts.size > 30) {
                item { TextButton(onClick = { showAll = true }) { Text("Показать все (${d.receipts.size})") } }
            }
        }

        if (d.pakets.isNotEmpty()) {
            item { SectionHead("Посылки Post paket") }
            items(d.pakets.take(30), key = { "p" + it.id }) { p ->
                Column(Modifier.fillMaxWidth().clickable { openPaket = p.id }.padding(vertical = 6.dp)) {
                    Text(p.title(), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(fmtTime(p.createdAt), p.recipient, listOf(p.zip, p.city).filter { it.isNotBlank() }.joinToString(" "))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (d.wagen.isNotEmpty()) {
            item { SectionHead("Операции Postwagen") }
            items(d.wagen.take(30), key = { "w" + it.id }) { w ->
                Box(Modifier.fillMaxWidth().clickable { openWagen = w.id }) {
                    TopLine(fmtTime(w.createdAt), "отпр. ${w.sent} · получ. ${w.received}")
                }
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = {
                    val i = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, buildReport(period, anchor, d))
                    }
                    ctx.startActivity(Intent.createChooser(i, "Поделиться итогом").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Поделиться итогом текстом") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    try {
                        val dir = File(ctx.cacheDir, "export").also { it.mkdirs() }
                        val name = "lager_info_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(anchor)) +
                            "_" + periodDays[period] + "d.csv"
                        val f = File(dir, name)
                        f.writeText(buildCsv(d), Charsets.UTF_8)
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                        val i = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(i, "Выгрузить таблицу").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(ctx, "Не удалось создать таблицу", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Выгрузить таблицей (CSV для Excel)") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
