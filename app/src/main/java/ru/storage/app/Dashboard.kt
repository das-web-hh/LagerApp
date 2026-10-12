package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private class Stats(
    val receipts: Int,
    val lines: Int,
    val units: Double,
    val defects: Double,
    val mismatches: Int
)

private fun dayStart(ms: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

private fun addDays(ms: Long, d: Int): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = ms
    c.add(Calendar.DAY_OF_YEAR, d)
    return c.timeInMillis
}

private fun num(s: String): Double = s.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

private fun fmtNum(d: Double): String =
    if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.getDefault(), "%.1f", d)

private fun receiptUnits(r: Receipt): Double {
    var s = 0.0
    r.items.forEach { s += num(it.qty) }
    return s
}

private fun statsOf(list: List<Receipt>): Stats {
    var lines = 0
    var units = 0.0
    var defects = 0.0
    var mismatches = 0
    list.forEach { r ->
        r.items.forEach { i ->
            lines++
            units += num(i.qty)
            defects += num(i.defect)
            if (i.plan.isNotBlank() && num(i.plan) != num(i.qty)) mismatches++
        }
    }
    return Stats(list.size, lines, units, defects, mismatches)
}

/**
 * Главное окно — дашборд: строка поиска сверху, под ней карточки-метрики по последним операциям
 * (приёмки, позиции, брак, расхождения), график за 7 дней, топ товаров и поставщиков, последние операции.
 * Когда в поиск что-то введено — вместо дашборда показываются результаты поиска.
 */
@Composable
fun HomeScreen(prefs: Prefs, userName: String, onOpenTab: (Int) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val sync = remember { SyncState() }
    var query by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<Product>()) }
    var openProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var openReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var all by remember { mutableStateOf(emptyList<Receipt>()) }
    var productCount by remember { mutableIntStateOf(0) }
    val enough = query.trim().length >= MIN_SEARCH_CHARS

    LaunchedEffect(Unit) {
        InvoiceProfiles.load(ctx)
        try { withContext(Dispatchers.IO) { InvoiceProfiles.refresh(ctx) } } catch (e: Exception) { }
    }
    LaunchedEffect(Unit) {
        try {
            Cloud.runReceipts(db, sync, false)
            Cloud.runProducts(db, sync, false)
        } catch (e: Exception) { }
    }
    LaunchedEffect(sync.version, Media.version) {
        withContext(Dispatchers.IO) {
            all = db.allReceipts()
            productCount = db.countProducts()
        }
    }
    LaunchedEffect(query, sync.version) {
        results = if (query.trim().length < MIN_SEARCH_CHARS) emptyList()
        else withContext(Dispatchers.IO) { db.search(query, 300, newestFirst = true) }
    }

    // код с Bluetooth-сканера попадает в строку поиска
    LaunchedEffect(BtScan.version) {
        val c = BtScan.pending
        if (c != null) {
            BtScan.pending = null
            query = c
        }
    }
    if (scanning) {
        ScannerScreen(prefs = prefs, onResult = { query = it; scanning = false }, onClose = { scanning = false })
        return
    }
    val pid = openProductId
    if (pid != null) {
        // в поиске — только история приёма товара, без правки
        ProductHistoryScreen(pid) {
            openProductId = null
            sync.version++
        }
        return
    }
    val rid = openReceiptId
    if (rid != null) {
        ReceiptCardScreen(rid) {
            openReceiptId = null
            sync.version++
        }
        return
    }
    // нажатие на уведомление открывает чат или задачи
    LaunchedEffect(Nav.target) {
        when (Nav.target) {
            "chat" -> section = "Чат"
            "tasks" -> section = "Задачи"
        }
        if (Nav.target != null) Nav.target = null
    }
    val sec = section
    if (sec == "Чат") {
        ChatScreen { section = null }
        return
    }
    if (sec == "Задачи") {
        TasksScreen(onBack = { section = null }, onOpenTab = onOpenTab)
        return
    }
    if (sec != null) {
        SectionScreen(sec) { section = null }
        return
    }

    // «Назад» при введённом запросе очищает поиск и возвращает дашборд
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    // сверху экрана мягкий цвет, плавно уходящий в фон: ничего не перекрывает
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), Color.Transparent),
                endY = 900f
            )
        )
    ) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        SearchBar(
            query = query,
            onChange = { query = it },
            onScan = { scanning = true },
            placeholder = "Поиск товара"
        )
        Spacer(Modifier.height(8.dp))
        if (query.isEmpty()) {
            val me = Session.user
            val rv = Live.readVersion
            val unread = if (me != null) remember(Live.msgs, rv) { Live.unreadTotal(ctx, me) } else 0
            val openTasks = if (me != null) Live.openTasksCount(me) else 0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { section = "Чат" }, modifier = Modifier.weight(1f)) {
                    BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) { Text("Чат") }
                }
                FilledTonalButton(onClick = { section = "Задачи" }, modifier = Modifier.weight(1f)) {
                    BadgedBox(badge = { if (openTasks > 0) Badge { Text(openTasks.toString()) } }) { Text(if (Session.isAdmin) "Управление" else "Задачи") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (query.isNotEmpty()) {
            SearchResultsList(
                enough = enough,
                busy = sync.busy,
                results = results,
                onOpen = { openProductId = it }
            )
        } else {
            Dashboard(
                userName = userName,
                all = all,
                productCount = productCount,
                onOpenReceipt = { openReceiptId = it },
                onOpenTab = onOpenTab,
                onSection = { section = it }
            )
        }
    }
    }
}

@Composable
private fun SearchResultsList(
    enough: Boolean,
    busy: Boolean,
    results: List<Product>,
    onOpen: (String) -> Unit
) {
    when {
        !enough -> Text(
            "Введите минимум $MIN_SEARCH_CHARS символа: название, артикул или штрих-код",
            style = MaterialTheme.typography.bodySmall
        )
        results.isEmpty() -> Text(
            if (busy) "Загрузка каталога…" else "Ничего не найдено",
            style = MaterialTheme.typography.bodySmall
        )
        else -> Text("Найдено: ${results.size}", style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(4.dp))
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.id }) { p ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(p.id) }.padding(vertical = 6.dp)) {
                Text(p.name, fontWeight = FontWeight.Medium)
                val sub = listOf(
                    if (p.article.isNotBlank()) "арт. ${p.article}" else "",
                    if (p.barcode.isNotBlank()) "ШК ${p.barcode}" else ""
                ).filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Dashboard(
    userName: String,
    all: List<Receipt>,
    productCount: Int,
    onOpenReceipt: (String) -> Unit,
    onOpenTab: (Int) -> Unit,
    onSection: (String) -> Unit
) {
    val now = System.currentTimeMillis()
    val today = dayStart(now)
    val cur = all.filter { it.receivedAt >= today && it.receivedAt < addDays(today, 1) }
    val st = statsOf(cur)
    val monthStart = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val month = all.filter { it.receivedAt >= monthStart }

    val topProducts = month.flatMap { it.items }
        .groupBy { it.name }
        .map { e -> e.key to e.value.sumOf { num(it.qty) } }
        .sortedByDescending { it.second }
        .take(3)
    val topSenders = month.filter { it.sender.isNotBlank() }
        .groupBy { it.sender }
        .map { e -> e.key to e.value.size }
        .sortedByDescending { it.second }
        .take(3)
    val bars = (6 downTo 0).map { k ->
        val s = addDays(today, -k)
        val e = addDays(s, 1)
        val v = all.filter { it.receivedAt >= s && it.receivedAt < e }.sumOf { receiptUnits(it) }
        Pair(s, v)
    }
    val recent = all.take(5)
    val jobsInWork = AutoReceiveHolder.jobs.count { !it.saved }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        val dateText = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now))
            .replaceFirstChar { it.uppercase() }
        Text(dateText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        PeriodWidget(all, now)
        SectionTitle("Сегодня")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(Icons.Default.Check, st.receipts.toString(), "Приёмок", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            MetricCard(Icons.Default.Menu, st.lines.toString(), "Позиций", Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                Icons.Default.Warning, fmtNum(st.defects), "Брак, шт", Modifier.weight(1f),
                if (st.defects > 0.0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
            )
            MetricCard(
                Icons.Default.Info, st.mismatches.toString(), "Расхождений с планом", Modifier.weight(1f),
                if (st.mismatches > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
            )
        }

        SectionTitle("Принято за 7 дней, шт")
        BarsCard(bars, today)

        SectionTitle("Сейчас")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                Icons.Default.Refresh, jobsInWork.toString(), "Партий в работе", Modifier.weight(1f),
                MaterialTheme.colorScheme.primary, onClick = { onOpenTab(1) }
            )
            MetricCard(
                Icons.Default.Edit, InvoiceProfiles.newCount.toString(), "Новых шаблонов", Modifier.weight(1f),
                MaterialTheme.colorScheme.tertiary, onClick = { onOpenTab(2) }
            )
            MetricCard(
                Icons.Default.Menu, productCount.toString(), "Товаров в каталоге", Modifier.weight(1f),
                MaterialTheme.colorScheme.secondary, onClick = { onOpenTab(2) }
            )
        }

        if (topProducts.isNotEmpty() || topSenders.isNotEmpty()) {
            SectionTitle("Топ за месяц")
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (topProducts.isNotEmpty()) {
                        Text("Товары", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        topProducts.forEach { p ->
                            TopRow(p.first, fmtNum(p.second) + " шт")
                        }
                    }
                    if (topProducts.isNotEmpty() && topSenders.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                    }
                    if (topSenders.isNotEmpty()) {
                        Text("Поставщики", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        topSenders.forEach { s ->
                            TopRow(s.first, "приёмок: ${s.second}")
                        }
                    }
                }
            }
        }

        SectionTitle("Последние операции")
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            if (recent.isEmpty()) {
                Text("Пока нет операций", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            } else {
                Column {
                    recent.forEachIndexed { i, r ->
                        RecentRow(r) { onOpenReceipt(r.id) }
                        if (i < recent.size - 1) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }

    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
}

private class Span(val title: String, val from: Long, val to: Long, val prevFrom: Long, val prevTo: Long, val prevName: String)

private fun calMs(ms: Long, f: Calendar.() -> Unit): Long =
    Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        f()
    }.timeInMillis

private fun spansOf(now: Long): List<Span> {
    val d0 = dayStart(now)
    val m0 = calMs(now) { set(Calendar.DAY_OF_MONTH, 1) }
    val y0 = calMs(now) { set(Calendar.DAY_OF_YEAR, 1) }
    return listOf(
        Span("за сегодня", d0, addDays(d0, 1), addDays(d0, -1), d0, "вчерашнему дню"),
        Span("за месяц", m0, calMs(m0) { add(Calendar.MONTH, 1) }, calMs(m0) { add(Calendar.MONTH, -1) }, m0, "прошлому месяцу"),
        Span("за год", y0, calMs(y0) { add(Calendar.YEAR, 1) }, calMs(y0) { add(Calendar.YEAR, -1) }, y0, "прошлому году")
    )
}

/** Виджет «Принято»: сам переходит с дня на месяц и на год, цифра плавно набирается. Нажатие — следующий счётчик. */
@Composable
private fun PeriodWidget(all: List<Receipt>, now: Long) {
    val spans = remember(now / 60000L) { spansOf(now) }
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(idx) {
        delay(5000)
        idx = (idx + 1) % spans.size
    }
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(BrandBlueDark, BrandBlue)))
            .clickable { idx = (idx + 1) % spans.size }
            .padding(20.dp)
    ) {
        Column {
            Crossfade(targetState = idx, animationSpec = tween(600), label = "period") { i ->
                val sp = spans[i]
                val cur = all.filter { it.receivedAt >= sp.from && it.receivedAt < sp.to }
                val prev = all.filter { it.receivedAt >= sp.prevFrom && it.receivedAt < sp.prevTo }
                val units = cur.sumOf { receiptUnits(it) }
                val prevUnits = prev.sumOf { receiptUnits(it) }
                var target by remember { mutableStateOf(0f) }
                LaunchedEffect(units) { target = units.toFloat() }
                val shown by animateFloatAsState(target, tween(900), label = "count")
                val delta = when {
                    prevUnits <= 0.0 && units <= 0.0 -> "Операций пока нет"
                    prevUnits <= 0.0 -> "Раньше приёмок не было"
                    else -> {
                        val pct = ((units - prevUnits) / prevUnits * 100.0).roundToInt()
                        when {
                            pct > 0 -> "▲ $pct% к ${sp.prevName}"
                            pct < 0 -> "▼ ${abs(pct)}% к ${sp.prevName}"
                            else -> "Как и раньше"
                        }
                    }
                }
                Column {
                    Text("Принято ${sp.title}", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            fmtNum(shown.toDouble()),
                            style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "шт", style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Приёмок: ${cur.size}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    Text(delta, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                spans.indices.forEach { k ->
                    Box(
                        Modifier.height(6.dp).width(if (k == idx) 22.dp else 6.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = if (k == idx) 1f else 0.4f))
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(st: Stats, prevUnits: Double, periodTitle: String) {
    val shape = RoundedCornerShape(28.dp)
    val delta = when {
        prevUnits <= 0.0 && st.units <= 0.0 -> "Операций пока нет"
        prevUnits <= 0.0 -> "В прошлом периоде приёмок не было"
        else -> {
            val pct = ((st.units - prevUnits) / prevUnits * 100.0).roundToInt()
            when {
                pct > 0 -> "▲ $pct% к прошлому периоду"
                pct < 0 -> "▼ ${abs(pct)}% к прошлому периоду"
                else -> "Как в прошлом периоде"
            }
        }
    }
    Box(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(BrandBlueDark, BrandBlue)))
            .padding(20.dp)
    ) {
        Column {
            Text("Принято $periodTitle", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    fmtNum(st.units),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "шт",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(delta, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}

@Composable
private fun MetricCard(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier,
    accent: Color,
    onClick: (() -> Unit)? = null
) {
    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BarsCard(bars: List<Pair<Long, Double>>, today: Long) {
    val maxV = max(1.0, bars.maxOf { it.second })
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            bars.forEach { b ->
                val isToday = b.first == today
                val color = if (isToday) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                val h = (6.0 + b.second / maxV * 70.0).toFloat()
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        if (b.second > 0.0) fmtNum(b.second) else "",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier.fillMaxWidth().height(h.dp)
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                            .background(color)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        SimpleDateFormat("EE", Locale.getDefault()).format(Date(b.first)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun TopRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RecentRow(r: Receipt, onClick: () -> Unit) {
    val title = when {
        r.sender.isNotBlank() -> r.sender
        r.orderNo.isNotBlank() -> "Заказ № ${r.orderNo}"
        else -> "Приёмка"
    }
    val sub = listOf(
        fmtTime(r.receivedAt),
        if (r.sender.isNotBlank() && r.orderNo.isNotBlank()) "заказ № ${r.orderNo}" else ""
    ).filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("+${fmtNum(receiptUnits(r))} шт", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text("поз.: ${r.items.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
