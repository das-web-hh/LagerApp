package ru.storage.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File
import java.util.Locale

/** Дата и время из имени файла: 8 цифр — дата (ДДММГГГГ), 4 цифры — время (ЧЧММ), среди любых букв и знаков. */
object Stamp {
    private fun validDate(d: Int, m: Int, y: Int): Boolean {
        if (y !in 2000..2100 || m !in 1..12 || d < 1) return false
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val dim = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return d <= dim[m - 1]
    }

    private fun date8(s: String): Triple<Int, Int, Int>? {
        val a = s.substring(0, 2).toInt()
        val b = s.substring(2, 4).toInt()
        val c = s.substring(4, 8).toInt()
        if (validDate(a, b, c)) return Triple(a, b, c) // ДДММГГГГ
        val y = s.substring(0, 4).toInt()
        val m = s.substring(4, 6).toInt()
        val d = s.substring(6, 8).toInt()
        if (validDate(d, m, y)) return Triple(d, m, y) // ГГГГММДД
        return null
    }

    private fun time4(s: String): Pair<Int, Int>? {
        val h = s.substring(0, 2).toInt()
        val m = s.substring(2, 4).toInt()
        return if (h in 0..23 && m in 0..59) h to m else null
    }

    /** "12.08.2026 12:45", только дата, либо "none". */
    fun parse(name: String): String {
        val base = name.substringBeforeLast('.', name)
        val runs = Regex("\\d+").findAll(base).map { it.value }.toList()
        var date: Triple<Int, Int, Int>? = null
        var time: Pair<Int, Int>? = null

        // 1) цепочка из 12+ цифр: дата + время подряд
        for (r in runs) {
            if (r.length >= 12) {
                val d = date8(r.substring(0, 8))
                val t = time4(r.substring(8, 12))
                if (d != null && t != null) {
                    date = d
                    time = t
                    break
                }
            }
        }
        // 2) группа из 8 цифр — дата, после неё группа из 4 цифр — время
        if (date == null) {
            val idx = runs.indexOfFirst { it.length == 8 && date8(it) != null }
            if (idx >= 0) {
                date = date8(runs[idx])
                val rest = runs.drop(idx + 1)
                time = rest.firstOrNull { it.length == 4 && time4(it) != null }?.let { time4(it) }
                    ?: rest.joinToString("").takeIf { it.length >= 4 }?.let { time4(it.substring(0, 4)) }
            }
        }
        // 3) цифры разделены знаками (12.08.2026 12-45)
        if (date == null) {
            val all = runs.joinToString("")
            if (all.length >= 12) {
                val d = date8(all.substring(0, 8))
                if (d != null) {
                    date = d
                    time = time4(all.substring(8, 12))
                }
            }
        }

        val d = date ?: return "none"
        val ds = String.format(Locale.ROOT, "%02d.%02d.%04d", d.first, d.second, d.third)
        val t = time ?: return ds
        return ds + " " + String.format(Locale.ROOT, "%02d:%02d", t.first, t.second)
    }
}

class PageState {
    var status by mutableStateOf("ожидает")
    var ok by mutableStateOf(false)
}

/**
 * Строка списка. qty — количество по документу (в автоприёме — принятое, в приёме по имени — плановое).
 * fact и defect вводятся вручную в приёме по имени; extra — товар добавлен вручную (нет в плане).
 */
class ItemRow(
    name: String,
    qty: String,
    val extra: Boolean = false,
    val productId: String = "",
    /** только автоприём: "ok" (✓), "missing" (✕ не пришло), "changed" (другое кол-во), "nomark" (без отметки) */
    val status: String = "",
    /** печатное количество по документу (автоприём) */
    val printed: String = ""
) {
    var name by mutableStateOf(name)
    var qty by mutableStateOf(qty)
    var fact by mutableStateOf("")
    var defect by mutableStateOf("")
}

/**
 * Автоприём: PDF → листы → чёрно-белые картинки → ИИ (по одному листу, с ожиданием и повтором).
 * Живёт отдельно от экрана, чтобы поворот экрана не прерывал распознавание.
 */
class AutoJob(private val app: Context, val file: IncomingFile) {
    val pages = mutableStateListOf<PageState>()
    var running by mutableStateOf(false)
    var message by mutableStateOf("")
    var sender by mutableStateOf("none")
    var order by mutableStateOf("none")
    var stamp by mutableStateOf(Stamp.parse(file.name))
    val items = mutableStateListOf<ItemRow>()
    var saved by mutableStateOf(false)
    var saving by mutableStateOf(false)

    /** true — приём по имени (все строки как есть, план = печатное количество); false — автоприём (по отметкам ручкой). */
    var nameMode = false
        private set

    fun setMode(name: Boolean) {
        if (nameMode == name) return
        nameMode = name
        if (results.isNotEmpty()) rebuild()
    }

    private val results = HashMap<Int, PageResult>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var started = false
        private set

    val hasFailed: Boolean get() = pages.isNotEmpty() && pages.any { !it.ok }

    fun startOnce() {
        if (started) return
        started = true
        start(false)
    }

    fun start(onlyFailed: Boolean) {
        if (running) return
        running = true
        message = "Подготовка…"
        scope.launch {
            var doc: PageSource? = null
            try {
                doc = openPageSource(File(file.path))
                val n = doc.count
                if (pages.size != n) {
                    pages.clear()
                    repeat(n) { pages.add(PageState()) }
                    results.clear()
                }
                val attempts = AiCfg.attempts(app).coerceIn(1, 8)
                for (i in 0 until n) {
                    val st = pages[i]
                    if (onlyFailed && st.ok) continue
                    st.ok = false
                    message = "Лист ${i + 1} из $n"
                    st.status = "подготовка…"
                    var png = try {
                        doc.renderBw(i)
                    } catch (e: Exception) {
                        st.status = "ошибка листа: ${e.message}"
                        continue
                    }
                    for (a in 1..attempts) {
                        st.status = if (a == 1) "отправка…" else "повтор $a из $attempts…"
                        try {
                            // со 2-й попытки — серая картинка покрупнее вместо чёрно-белой
                            if (a == 2) png = try { doc.renderBw(i, 2200, false) } catch (e: Exception) { png }
                            val r = Ai.recognize(app, png, i + 1, n)
                            // пустой лист — повтор; на первом листе без товаров тоже (там таблица почти всегда есть)
                            val blank = r.items.isEmpty() && r.sender == "none" && r.order == "none"
                            if ((blank || (i == 0 && r.items.isEmpty())) && a < attempts) {
                                throw RuntimeException("не нашёл товары, пробую в другом виде")
                            }
                            results[i] = r
                            st.ok = true
                            st.status = "готово"
                            break
                        } catch (e: Exception) {
                            st.status = "ошибка: " + (e.message ?: e.javaClass.simpleName)
                            if (a < attempts) delay(3000L * a)
                        }
                    }
                    rebuild()
                    if (i < n - 1) delay(2000)
                }
                val failed = pages.count { !it.ok }
                message = if (failed == 0) "Готово: распознано листов — $n"
                else "Не распознано листов: $failed из $n"
            } catch (e: Exception) {
                message = "Ошибка: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                doc?.close()
                running = false
            }
        }
    }

    private fun rowFor(it: AiItem): ItemRow {
        val printed = cleanQty(it.qty)
        if (nameMode) return ItemRow(it.name, printed)
        val hand = cleanQty(it.handQty)
        return when {
            hand.isNotEmpty() -> ItemRow(it.name, hand, status = "changed", printed = printed)
            it.qtyCrossed -> ItemRow(it.name, "", status = "changed", printed = printed)
            it.mark == "cross" -> ItemRow(it.name, "0", status = "missing", printed = printed)
            it.mark == "check" -> ItemRow(it.name, printed, status = "ok", printed = printed)
            else -> ItemRow(it.name, printed, status = "nomark", printed = printed)
        }
    }

    private fun rebuild() {
        var s = "none"
        var o = "none"
        val list = ArrayList<ItemRow>()
        for (i in 0 until pages.size) {
            val r = results[i] ?: continue
            if (s == "none" && r.sender != "none") s = r.sender
            if (o == "none" && r.order != "none") o = r.order
            r.items.forEach { list.add(rowFor(it)) }
        }
        sender = s
        order = o
        val extras = items.filter { it.extra }
        items.clear()
        items.addAll(list)
        items.addAll(extras)
    }
}

object AutoReceiveHolder {
    private var job: AutoJob? = null

    fun get(ctx: Context, f: IncomingFile): AutoJob {
        val j = job
        if (j != null && j.file.path == f.path) return j
        return AutoJob(ctx.applicationContext, f).also { job = it }
    }
}

/** Количество без хвоста ",00": "45,00" → "45", "2,5" → "2.5"; пусто, если числа нет. */
fun cleanQty(q: String): String {
    val n = normalizeQty(q)
    val d = n.toDoubleOrNull() ?: return ""
    return if (d == Math.floor(d) && d < 1e9) d.toLong().toString() else n
}

fun normalizeQty(q: String): String {
    val m = Regex("\\d+(?:[.,]\\d+)?").find(q) ?: return ""
    return m.value.replace(',', '.')
}

fun stampMillis(stamp: String): Long? = when (stamp.length) {
    16 -> parseTime(stamp)
    10 -> parseTime("$stamp 00:00")
    else -> null
}

/** Проверка ввода в приёме по имени: факт указан у каждой позиции, брак не больше факта. */
fun checkFacts(job: AutoJob): String? {
    val rows = job.items.filter { it.name.trim().isNotEmpty() }
    if (rows.isEmpty()) return "Нет товаров"
    for (r in rows) {
        val fact = normalizeQty(r.fact).ifEmpty { "0" }
        val defect = normalizeQty(r.defect).ifEmpty { "0" }
        if (defect.toDouble() > fact.toDouble()) return "Брак больше фактического количества: ${r.name.trim()}"
    }
    return null
}

/**
 * Сохранение приёма: товары — в историю/Firebase, исходный файл (сырой, без обработки) — на Google Диск.
 * Возвращает текст ошибки или null, если всё сохранено.
 */
suspend fun saveJob(
    ctx: Context,
    db: ProductDb,
    job: AutoJob,
    f: IncomingFile,
    receiver: String,
    nameMode: Boolean
): String? {
    if (job.saved || job.saving) return null
    val rows = ArrayList<ReceiptItem>()
    for (r in job.items) {
        val name = r.name.trim()
        if (name.isEmpty()) continue
        val pid = withContext(Dispatchers.IO) { r.productId.ifEmpty { db.findByName(name)?.id ?: "" } }
        if (nameMode) {
            val fact = normalizeQty(r.fact).ifEmpty { "0" }
            val defect = normalizeQty(r.defect).ifEmpty { "0" }
            if (defect.toDouble() > fact.toDouble()) return "Брак больше фактического количества: $name"
            val plan = if (r.extra) "0" else normalizeQty(r.qty).ifEmpty { "0" }
            rows.add(ReceiptItem(pid, name, fact, plan, defect))
        } else {
            val qty = normalizeQty(r.qty)
            val printed = normalizeQty(r.printed)
            // «не пришло» (✕): товар попадает в приём с количеством 0 и планом по документу
            if (r.status == "missing" && (qty.isEmpty() || qty.toDoubleOrNull() == 0.0)) {
                rows.add(ReceiptItem(pid, name, "0", printed.ifEmpty { "0" }))
            } else {
                if (qty.isEmpty() || !qtyValid(qty)) return "Укажите количество (число больше нуля) у товара: $name"
                val plan = if (r.status == "changed" || r.status == "missing") printed else ""
                rows.add(ReceiptItem(pid, name, qty, plan))
            }
        }
    }
    if (rows.isEmpty()) return "Нет товаров для сохранения"

    val order = Ai.normalizeOrder(job.order)
    job.saving = true
    try {
        // Номер заказа — главный признак: тот же заказ второй раз не сохраняем
        if (order != "none") {
            val d2 = try {
                Cloud.findDuplicate(db, order, "", "")
            } catch (e: Exception) {
                null
            }
            if (d2 != null) {
                return "Заказ $order уже принят ${fmtTime(d2.receipt.receivedAt)} — повторно не сохраняю."
            }
        }
        val now = System.currentTimeMillis()
        val at = stampMillis(job.stamp.trim()) ?: now
        val sender = job.sender.trim().let { if (it.equals("none", true)) "" else it }
        val stamp = job.stamp.trim().let { if (it.length == 16) it else "" }
        withContext(Dispatchers.IO) {
            val inv = Media.importFile(ctx, File(f.path), db, "IN")
            val r = Receipt(
                id = UUID.randomUUID().toString(),
                sender = sender,
                orderNo = if (order == "none") "" else order,
                receivedAt = at,
                receiver = receiver,
                items = rows,
                photos = emptyList(),
                invoices = listOfNotNull(inv),
                updatedAt = now,
                fileName = f.name,
                stamp = stamp
            )
            Cloud.saveReceipt(db, r)
            Media.uploadPending(ctx, db)
        }
        job.saved = true
        return null
    } finally {
        job.saving = false
    }
}

@Composable
private fun AutoRowEditor(row: ItemRow, editable: Boolean, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                row.name, { row.name = it }, enabled = editable,
                label = { Text("Название") }, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                row.qty, { row.qty = it }, enabled = editable, singleLine = true,
                label = { Text("Кол-во") }, modifier = Modifier.width(88.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            TextButton(onClick = onRemove, enabled = editable) { Text("✕") }
        }
        val bold = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        val doc = if (row.printed.isNotEmpty()) row.printed else "—"
        when (row.status) {
            "ok" -> Text("✓ ПРИШЛО · по документу $doc", style = bold, color = MaterialTheme.colorScheme.primary)
            "missing" -> Text("✕ НЕ ПРИШЛО · по документу $doc · принято 0", style = bold, color = MaterialTheme.colorScheme.error)
            "changed" -> Text(
                if (row.qty.isBlank()) "✎ количество перечёркнуто — впишите · по документу $doc"
                else "✎ ДРУГОЕ КОЛИЧЕСТВО · по документу $doc · принято ${row.qty}",
                style = bold, color = MaterialTheme.colorScheme.tertiary
            )
            "nomark" -> Text("без отметки — проверьте · по документу $doc", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DocFields(job: AutoJob, editable: Boolean) {
    OutlinedTextField(
        job.sender, { job.sender = it }, label = { Text("Отправитель (организация)") },
        enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        job.order, { job.order = it }, label = { Text("Номер заказа (ЕБ + 7 цифр)") },
        enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        job.stamp, { job.stamp = it }, label = { Text("Дата и время (из имени файла)") },
        enabled = editable, singleLine = true, modifier = Modifier.fillMaxWidth()
    )
}

/** Верх экрана «Приём по имени»: кнопки, отправитель и заказ, ниже — колесо с поиском. Ничего лишнего. */
@Composable
private fun ColumnScope.NameModeMain(
    job: AutoJob,
    error: String,
    addTick: Int,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onAdd: () -> Unit
) {
    val editable = !job.running && !job.saved && !job.saving
    val failed = job.pages.count { !it.ok }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onNext,
            enabled = editable && job.pages.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text("Далее") }
        if (!job.running && !job.saved && failed > 0) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) {
                Text("Повторить листы ($failed)", maxLines = 1)
            }
        }
    }
    if (error.isNotBlank()) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp))
    }
    if (job.saved) {
        Text("Приём сохранён", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            job.sender.let { if (it.equals("none", true)) "" else it }, { job.sender = it },
            label = { Text("Отправитель") }, enabled = editable, singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            job.order.let { if (it.equals("none", true)) "" else it }, { job.order = it },
            label = { Text("Заказ") }, enabled = editable, singleLine = true,
            modifier = Modifier.width(150.dp)
        )
    }
    Spacer(Modifier.height(8.dp))
    CountWheelList(
        job.items, editable, Modifier.weight(1f), addTick, onAdd,
        if (job.running) job.message else "Список пуст"
    )
}

@Composable
private fun AddItemDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    var name by remember { mutableStateOf("") }
    var pid by remember { mutableStateOf("") }
    var sugg by remember { mutableStateOf(emptyList<Product>()) }

    LaunchedEffect(name) {
        sugg = if (name.trim().length < MIN_SEARCH_CHARS || pid.isNotEmpty()) emptyList()
        else withContext(Dispatchers.IO) { db.search(name, 6) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить товар") },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it; pid = "" }, label = { Text("Название товара") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Введите от $MIN_SEARCH_CHARS символов, чтобы найти товар в каталоге, или впишите новое название.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                sugg.forEach { p ->
                    Text(
                        p.name,
                        Modifier.fillMaxWidth().clickable {
                            name = p.name
                            pid = p.id
                            sugg = emptyList()
                        }.padding(vertical = 10.dp)
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { onAdd(name.trim(), pid) }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/**
 * mode = "auto" — автоприём (сохранение сразу после распознавания);
 * mode = "name" — приём по имени: распознанное количество = план, факт и брак вводятся вручную,
 * кнопка «Далее» ведёт на итоговое окно (печать и сохранение).
 */
@Composable
fun AutoReceiveScreen(f: IncomingFile, receiver: String, mode: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val nameMode = mode == "name"
    val job = remember(f.path) { AutoReceiveHolder.get(ctx, f) }
    var step by remember(f.path) { mutableIntStateOf(0) }

    if (nameMode && step == 1) {
        NameSummaryScreen(job, f, receiver, onBack = { step = 0 }, onClose = onClose)
        return
    }

    BackHandler(onBack = onClose)
    val ready = AiCfg.ready(ctx)
    var checked by remember(f.path) { mutableStateOf(false) }
    var dup by remember(f.path) { mutableStateOf<Dup?>(null) }
    var forced by remember(f.path) { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var addTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(f.path, nameMode) { job.setMode(nameMode) }

    // До распознавания: не обрабатывался ли этот файл раньше (имя файла, затем дата и время)
    LaunchedEffect(f.path) {
        if (ready && !job.started) {
            val d = try {
                Cloud.findDuplicate(db, "", f.name, job.stamp)
            } catch (e: Exception) {
                null
            }
            dup = d
            if (d == null) job.startOnce()
        }
        checked = true
    }

    val showMain = ready && checked && (dup == null || forced)
    Column(
        Modifier.fillMaxSize()
            .then(if (nameMode) Modifier.imePadding() else Modifier.verticalScroll(rememberScrollState()))
            .padding(if (nameMode && showMain) 10.dp else 16.dp)
    ) {
        if (!nameMode || !showMain) {
            Text(if (nameMode) "Приём по имени" else "Автоприём", style = MaterialTheme.typography.headlineSmall)
            Text(f.name, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }

        if (!ready) {
            Text(
                "Не задан ключ ИИ. Откройте Профиль → Настройки → Распознавание документов.",
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
            return@Column
        }
        if (!checked) {
            Text("Проверка: не обрабатывался ли файл раньше…")
            return@Column
        }
        val d = dup
        if (d != null && !forced) {
            Text("Этот файл уже обработан", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error)
            Text("Совпало: ${d.by}. На распознавание не отправлен.")
            Spacer(Modifier.height(8.dp))
            Text("Приём: ${fmtTime(d.receipt.receivedAt)}")
            if (d.receipt.sender.isNotBlank()) Text("Отправитель: ${d.receipt.sender}")
            if (d.receipt.orderNo.isNotBlank()) Text("Номер заказа: ${d.receipt.orderNo}")
            Text("Товаров: ${d.receipt.items.size}")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { forced = true; job.startOnce() }, modifier = Modifier.fillMaxWidth()) {
                Text("Обработать всё равно")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
            return@Column
        }

        if (nameMode) {
            NameModeMain(
                job = job,
                error = error,
                addTick = addTick,
                onNext = {
                    val e = checkFacts(job)
                    if (e != null) error = e else {
                        error = ""
                        step = 1
                    }
                },
                onRetry = { job.start(true) },
                onAdd = { showAdd = true }
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (job.running) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(job.message, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        job.pages.forEachIndexed { i, p ->
            Text(
                "Лист ${i + 1} из ${job.pages.size} — ${p.status}",
                style = MaterialTheme.typography.bodySmall,
                color = if (p.status.startsWith("ошибка")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }

        val editable = !job.running && !job.saved && !job.saving
        Spacer(Modifier.height(16.dp))
        DocFields(job, editable)

        Spacer(Modifier.height(16.dp))
        Text("Товары: ${job.items.size}", style = MaterialTheme.typography.titleSmall)
        Text(
            "По отметкам ручкой: ✓ — пришло, ✕ — не пришло (сохранится с количеством 0), ✎ — другое количество, дописанное ручкой.",
            style = MaterialTheme.typography.bodySmall
        )
        job.items.toList().forEach { row ->
            key(row) { AutoRowEditor(row, editable) { job.items.remove(row) } }
        }

        Spacer(Modifier.height(16.dp))
        if (job.saved) {
            Text(
                "Приём сохранён в историю. Файл отправляется на Google Диск.",
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        } else if (!job.running && job.pages.isNotEmpty() && job.items.isNotEmpty()) {
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                enabled = !job.saving,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch { error = saveJob(ctx, db, job, f, receiver, false) ?: "" }
                }
            ) { Text(if (job.saving) "Сохранение…" else "Сохранить приём") }
            Spacer(Modifier.height(8.dp))
        }

        if (!job.running && !job.saved && job.hasFailed) {
            Button(onClick = { job.start(true) }, modifier = Modifier.fillMaxWidth()) {
                Text("Повторить нераспознанные листы")
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
    }

    if (showAdd) {
        AddItemDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, pid ->
                job.items.add(ItemRow(name, "0", extra = true, productId = pid))
                addTick++
                showAdd = false
            }
        )
    }
}
