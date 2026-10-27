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
    var results by remember { mutableStateOf(emptyList<HistoryHit>()) }   // строки истории для товаров, которых нет в каталоге
    var prodHits by remember { mutableStateOf(emptyList<ProdHit>()) }      // подсказки из каталога
    var openProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var openReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val startDocScan = rememberDocScanner()
    var browseQuery by rememberSaveable { mutableStateOf<String?>(null) }   // «Файлы» с подставленным запросом из главного поиска
    var receiptHits by remember { mutableStateOf(emptyList<ReceiptHit>()) } // приёмки: по номеру заказа, дате, файлу, отправителю
    var fileHits by remember { mutableStateOf(emptyList<FolderFile>()) }    // файлы из папок: по дате и названию
    var all by remember { mutableStateOf(emptyList<Receipt>()) }
    var productCount by remember { mutableIntStateOf(0) }
    val enough = query.trim().length >= MIN_SEARCH_CHARS
    SideEffect { TabRoot.set(0, !scanning && openProductId == null && openReceiptId == null && section == null && browseQuery == null && query.isEmpty()) }

    LaunchedEffect(Unit) {
        InvoiceProfiles.load(ctx)
        try { withContext(Dispatchers.IO) { InvoiceProfiles.refresh(ctx) } } catch (e: Exception) { }
    }
    LaunchedEffect(Unit) {
        try {
            Cloud.runReceipts(db, sync, false)
            Cloud.runProducts(db, sync, false)
            if (Cloud.migrateOrdersToLatin(db) > 0) sync.version++
        } catch (e: Exception) { }
    }
    LaunchedEffect(sync.version, Media.version) {
        withContext(Dispatchers.IO) {
            all = db.allReceipts()
            productCount = db.countProducts()
        }
    }
    // подсказки — из каталога (один товар = одна строка, сверху тот, что принимали последним);
    // товары, принятые без карточки в каталоге, показываются отдельным списком ниже
    LaunchedEffect(query, sync.version, all) {
        if (query.trim().length < MIN_SEARCH_CHARS) {
            prodHits = emptyList(); results = emptyList(); receiptHits = emptyList(); fileHits = emptyList()
        } else withContext(Dispatchers.IO) {
            prodHits = searchProducts(db, all, query)
            val ids = db.allProducts().map { it.id }.toSet()
            results = searchHistory(db, all, query).filter { it.item.productId !in ids }
            receiptHits = searchReceipts(all, query)
        }
    }
    // файлы из папок — по дате и названию (как в окне «Файлы»); список папок читаем один раз на запрос
    LaunchedEffect(query) {
        if (query.trim().length < MIN_SEARCH_CHARS) fileHits = emptyList()
        else withContext(Dispatchers.IO) {
            val q = query.trim()
            val qd = parseQueryDate(q)
            fileHits = try {
                Folders.load(ctx)
                Folders.scanAll(ctx, false)
                    .filter { if (qd != null) matchesDate(it, qd) else it.name.contains(q, ignoreCase = true) }
                    .sortedByDescending { effMillis(it) }
                    .take(30)
            } catch (e: Exception) { emptyList() }
        }
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
            "scan" -> { ScanOrigin.fromWidget = true; startDocScan() }   // кнопка виджета: сразу открыть сканер документа
        }
        if (Nav.target != null) Nav.target = null
    }
    val bq = browseQuery
    if (bq != null) {
        FileBrowserScreen(onBack = { browseQuery = null }, initialQuery = bq)
        return
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
            placeholder = "Товар, заказ EB…, дата"
        )
        Spacer(Modifier.height(8.dp))
        if (query.isEmpty()) {
            val me = Session.user
            val rv = Live.readVersion
            val unread = if (me != null) remember(Live.msgs, rv) { Live.unreadTotal(ctx, me) } else 0
            val openTasks = if (me != null) Live.openTasksCount(me) else 0
            // фон кнопок — белая поверхность с тенью: отделяется от цветного фона окна
            val btnColors = ButtonDefaults.elevatedButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ElevatedButton(onClick = startDocScan, colors = btnColors, modifier = Modifier.weight(1f)) { Text("Скан") }
                ElevatedButton(onClick = { section = "Чат" }, colors = btnColors, modifier = Modifier.weight(1f)) {
                    BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) { Text("Чат") }
                }
            }
            Spacer(Modifier.height(8.dp))
            ElevatedButton(onClick = { section = "Задачи" }, colors = btnColors, modifier = Modifier.fillMaxWidth()) {
                BadgedBox(badge = { if (openTasks > 0) Badge { Text(openTasks.toString()) } }) { Text(if (Session.isAdmin) "Управление" else "Задачи") }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (query.isNotEmpty()) {
            SearchResultsList(
                enough = enough,
                busy = sync.busy,
                prodHits = prodHits,
                results = results,
                receiptHits = receiptHits,
                fileHits = fileHits,
                onOpenProduct = { openProductId = it },
                onOpen = { openReceiptId = it },
                onOpenFiles = { browseQuery = query.trim() }
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

/** Строка истории: товар в конкретной приёмке. */
private class HistoryHit(val receipt: Receipt, val item: ReceiptItem, val key: String)

private fun searchHistory(db: ProductDb, all: List<Receipt>, query: String): List<HistoryHit> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val products = db.allProducts().associateBy { it.id }
    val out = ArrayList<HistoryHit>()
    for (r in all) {
        r.items.forEachIndexed { i, item ->
            val p = products[item.productId]
            val hay = listOf(item.name, p?.name ?: "", p?.article ?: "", p?.barcode ?: "", r.sender, r.orderNo)
                .joinToString(" ").lowercase()
            if (words.all { w -> hay.contains(w) }) out.add(HistoryHit(r, item, r.id + "#" + i))
        }
    }
    out.sortByDescending { it.receipt.receivedAt }
    return out.take(300)
}

/** Приёмка, найденная по номеру заказа, дате, названию файла или отправителю. */
private class ReceiptHit(val receipt: Receipt, val why: String)

/** «ЕБ1234567», «еб-1234567», «eb 1234567» → «eb1234567»: русские и латинские буквы номера заказа сравниваются одинаково. */
private fun orderKey(s: String): String =
    Ai.latinOrder(s).lowercase().replace(Regex("[\\s\\-–]+"), "").replace(Regex("^[её][бb6]"), "eb")

private fun searchReceipts(all: List<Receipt>, query: String): List<ReceiptHit> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val qd = parseQueryDate(q)
    val qo = orderKey(q)
    val ql = q.lowercase()
    val out = ArrayList<ReceiptHit>()
    for (r in all) {
        val ok = r.orderNo.isNotBlank() && r.orderNo != "none" && qo.length >= MIN_SEARCH_CHARS && orderKey(r.orderNo).contains(qo)
        var why = ""
        if (ok) why = "заказ"
        else if (qd != null) {
            val days = HashSet<Int>()
            days.add(dayKey(r.receivedAt))
            parseTime(r.stamp)?.let { days.add(dayKey(it)) }
            nameMillis(r.fileName)?.let { days.add(dayKey(it)) }
            if (days.any { k -> (k / 100) % 100 == qd.m && k % 100 == qd.d && (qd.y == null || k / 10000 == qd.y) }) why = "дата"
        } else if (r.fileName.isNotBlank() && r.fileName.lowercase().contains(ql)) why = "файл"
        else if (r.sender.isNotBlank() && r.sender.lowercase().contains(ql)) why = "отправитель"
        if (why.isNotEmpty()) out.add(ReceiptHit(r, why))
    }
    out.sortByDescending { it.receipt.receivedAt }
    return out.take(100)
}

/** Подсказка из каталога: товар, когда его принимали в последний раз и сколько раз. */
private class ProdHit(val product: Product, val last: Receipt?, val count: Int)

private fun searchProducts(db: ProductDb, all: List<Receipt>, query: String): List<ProdHit> {
    val found = db.search(query, 100)
    if (found.isEmpty()) return emptyList()
    val out = found.map { p ->
        val nameLc = p.name.trim().lowercase()
        val rs = all.filter { r ->
            r.items.any { it.productId == p.id || (it.productId.isEmpty() && it.name.trim().lowercase() == nameLc) }
        }
        ProdHit(p, rs.maxByOrNull { it.receivedAt }, rs.size)
    }
    // сначала те, что принимали недавно; не принимавшиеся — в конце, по алфавиту
    return out.sortedWith(compareByDescending<ProdHit> { it.last?.receivedAt ?: Long.MIN_VALUE }.thenBy { it.product.name.lowercase() })
}

@Composable
private fun SearchResultsList(
    enough: Boolean,
    busy: Boolean,
    prodHits: List<ProdHit>,
    results: List<HistoryHit>,
    receiptHits: List<ReceiptHit>,
    fileHits: List<FolderFile>,
    onOpenProduct: (String) -> Unit,
    onOpen: (String) -> Unit,
    onOpenFiles: () -> Unit
) {
    when {
        !enough -> Text(
            "Введите минимум $MIN_SEARCH_CHARS символа: название, артикул, штрих-код, номер заказа (EB…) или дату",
            style = MaterialTheme.typography.bodySmall
        )
        prodHits.isEmpty() && results.isEmpty() && receiptHits.isEmpty() && fileHits.isEmpty() -> Text(
            if (busy) "Загрузка…" else "Ничего не найдено",
            style = MaterialTheme.typography.bodySmall
        )
        else -> Text(
            "Товаров: ${prodHits.size} · приёмок: ${receiptHits.size} · файлов: ${fileHits.size}",
            style = MaterialTheme.typography.bodySmall
        )
    }
    Spacer(Modifier.height(4.dp))
    LazyColumn(Modifier.fillMaxSize()) {
        if (receiptHits.isNotEmpty()) {
            item(key = "rhdr") {
                Text("Приёмки (заказ, дата, файл, отправитель)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            }
            items(receiptHits, key = { "r" + it.receipt.id }) { h ->
                val r = h.receipt
                Column(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 6.dp)) {
                    Text(
                        if (r.orderNo.isNotBlank() && r.orderNo != "none") "Заказ № ${Ai.latinOrder(r.orderNo)}" else r.sender.ifBlank { "Приёмка" },
                        fontWeight = FontWeight.Medium
                    )
                    val sub = listOf(
                        fmtTime(r.receivedAt),
                        if (r.orderNo.isNotBlank() && r.orderNo != "none") r.sender else "",
                        r.fileName,
                        "позиций ${r.items.size}"
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        if (fileHits.isNotEmpty()) {
            item(key = "fhdr") {
                Text("Файлы", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(fileHits, key = { "f" + it.id }) { f ->
                Column(Modifier.fillMaxWidth().clickable { onOpenFiles() }.padding(vertical = 6.dp)) {
                    Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(if (f.modified > 0) fmtTime(f.modified) else "", f.folder).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider()
            }
        }
        items(prodHits, key = { "p" + it.product.id }) { h ->
            Column(Modifier.fillMaxWidth().clickable { onOpenProduct(h.product.id) }.padding(vertical = 6.dp)) {
                Text(h.product.name, fontWeight = FontWeight.Medium)
                val sub = listOf(h.product.article, h.product.barcode).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                val l = h.last
                Text(
                    if (l == null) "Ещё не принимали"
                    else "Последний приём: " + fmtTime(l.receivedAt) + (if (l.receiver.isNotBlank()) " · " + l.receiver else "") +
                        (if (h.count > 1) " · приёмов: ${h.count}" else ""),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            HorizontalDivider()
        }
        if (results.isNotEmpty()) {
            item(key = "hdr") {
                Text("Принято без карточки в каталоге", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            items(results, key = { it.key }) { h ->
                val r = h.receipt
                Column(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 6.dp)) {
                    Text(h.item.name, fontWeight = FontWeight.Medium)
                    Text(fmtTime(r.receivedAt) + " · количество ${h.item.qty}", style = MaterialTheme.typography.bodySmall)
                    val sub = listOf(
                        r.sender,
                        if (r.orderNo.isNotBlank() && r.orderNo != "none") "заказ ${r.orderNo}" else ""
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
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
        val openJobs = AutoReceiveHolder.jobs.filter { !it.saved }
        if (openJobs.isNotEmpty()) {
            SectionTitle("В работе: ${openJobs.size}")
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column {
                    openJobs.forEachIndexed { i, j ->
                        Row(
                            Modifier.fillMaxWidth().clickable { Incoming.openPath = j.file.path }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                val title = j.sender.takeIf { !it.equals("none", true) && it.isNotBlank() } ?: j.file.name
                                Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (if (j.nameMode) "По имени · " else "Автоприём · ") + j.statusLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        if (i < openJobs.size - 1) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
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
                MaterialTheme.colorScheme.primary, onClick = {
                    val open = AutoReceiveHolder.jobs.filter { !it.saved }
                    if (open.size == 1) Incoming.openPath = open[0].file.path else onOpenTab(1)
                }
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

/** Виджет «Принято»: три фиксированных счётчика — за сегодня, за месяц и за год. Цифры не крутятся и сами не переключаются. */
@Composable
private fun PeriodWidget(all: List<Receipt>, now: Long) {
    val spans = remember(now / 60000L) { spansOf(now) }
    val names = listOf("Сегодня", "Месяц", "Год")
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(BrandBlueDark, BrandBlue)))
            .padding(20.dp)
    ) {
        Column {
            Text("Принято, шт", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.8f))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spans.forEachIndexed { k, sp ->
                    val cur = all.filter { it.receivedAt >= sp.from && it.receivedAt < sp.to }
                    val units = cur.sumOf { receiptUnits(it) }
                    Column(Modifier.weight(1f)) {
                        Text(names[k], style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                        Text(
                            fmtNum(units),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = Color.White, maxLines = 1, softWrap = false
                        )
                        Text("приёмок: ${cur.size}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                    }
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
