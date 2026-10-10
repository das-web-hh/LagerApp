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
class ItemRow(name: String, qty: String, val extra: Boolean = false, val productId: String = "") {
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
                            if (r.items.isEmpty() && r.sender == "none" && r.order == "none" && a < attempts) {
                                throw RuntimeException("лист пустой, пробую в другом виде")
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

    private fun rebuild() {
        var s = "none"
        var o = "none"
        val list = ArrayList<ItemRow>()
        for (i in 0 until pages.size) {
            val r = results[i] ?: continue
            if (s == "none" && r.sender != "none") s = r.sender
            if (o == "none" && r.order != "none") o = r.order
            r.items.forEach { list.add(ItemRow(it.name, it.qty)) }
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
        val fact = normalizeQty(r.fact)
        if (fact.isEmpty()) return "Укажите фактическое количество: ${r.name.trim()} (0 — если не привезли)"
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
            val fact = normalizeQty(r.fact)
            if (fact.isEmpty()) return "Укажите фактическое количество: $name (0 — если не привезли)"
            val defect = normalizeQty(r.defect).ifEmpty { "0" }
            if (defect.toDouble() > fact.toDouble()) return "Брак больше фактического количества: $name"
            val plan = if (r.extra) "0" else normalizeQty(r.qty).ifEmpty { "0" }
            rows.add(ReceiptItem(pid, name, fact, plan, defect))
        } else {
            val qty = normalizeQty(r.qty)
            if (qty.isEmpty() || !qtyValid(qty)) return "Укажите количество (число больше нуля) у каждого товара"
            rows.add(ReceiptItem(pid, name, qty))
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
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            row.name, { row.name = it }, enabled = editable,
            label = { Text("Название") }, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            row.qty, { row.qty = it }, enabled = editable, singleLine = true,
            label = { Text("Кол-во") }, modifier = Modifier.width(88.dp)
        )
        TextButton(onClick = onRemove, enabled = editable) { Text("✕") }
    }
}

/** Строка приёма по имени: название, план (из документа), факт и брак — вручную. */
@Composable
private fun NameRowEditor(row: ItemRow, editable: Boolean, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                row.name, { row.name = it }, enabled = editable,
                label = { Text(if (row.extra) "Название (нет в плане)" else "Название") },
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRemove, enabled = editable) { Text("✕") }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                row.qty, { row.qty = it }, enabled = editable && !row.extra, singleLine = true,
                label = { Text("План") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                row.fact, { row.fact = it }, enabled = editable, singleLine = true,
                label = { Text("Факт") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
            OutlinedTextField(
                row.defect, { row.defect = it }, enabled = editable, singleLine = true,
                label = { Text("Брак") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }
    }
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(if (nameMode) "Приём по имени" else "Автоприём", style = MaterialTheme.typography.headlineSmall)
        Text(f.name, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

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

        Spacer(Modifier.height(16.dp))
        Text("Товары: ${job.items.size}", style = MaterialTheme.typography.titleSmall)
        if (nameMode) {
            Text(
                "План — по документу. Факт (сколько привезли) и брак введите вручную.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        job.items.toList().forEach { row ->
            key(row) {
                if (nameMode) NameRowEditor(row, editable) { job.items.remove(row) }
                else AutoRowEditor(row, editable) { job.items.remove(row) }
            }
        }
        if (nameMode && editable) {
            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
                Text("+ Добавить товар")
            }
        }

        Spacer(Modifier.height(16.dp))
        if (job.saved) {
            Text(
                "Приём сохранён в историю. Файл отправляется на Google Диск.",
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
        } else if (!job.running && job.pages.isNotEmpty() && (job.items.isNotEmpty() || nameMode)) {
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            if (nameMode) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val e = checkFacts(job)
                        if (e != null) error = e else {
                            error = ""
                            step = 1
                        }
                    }
                ) { Text("Далее") }
            } else {
                Button(
                    enabled = !job.saving,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch { error = saveJob(ctx, db, job, f, receiver, false) ?: "" }
                    }
                ) { Text(if (job.saving) "Сохранение…" else "Сохранить приём") }
            }
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
                showAdd = false
            }
        )
    }
}
