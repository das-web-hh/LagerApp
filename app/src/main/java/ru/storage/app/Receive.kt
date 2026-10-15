package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Позиция приёмки: товар + количество (используется и при приёме, и в карточке партии). */
class EditItem(
    val productId: String,
    val name: String,
    initial: String,
    val plan: String = "",
    val defect: String = "",
    val barcode: String = ""
) {
    var qty by mutableStateOf(initial)
    /** срок годности дд.мм.гггг, "" — не указан */
    var expiry by mutableStateOf("")
}

fun qtyValid(q: String): Boolean = (q.trim().replace(',', '.').toDoubleOrNull() ?: 0.0) > 0.0

@Composable
fun ManualReceiveScreen(prefs: Prefs, receiver: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val sync = remember { SyncState() }
    var query by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<Product>()) }
    // строки списка: qty — план (из вставки), fact — посчитано, defect — испорченные, expiry — срок годности
    val rows = remember { mutableStateListOf<ItemRow>() }
    var addTick by remember { mutableIntStateOf(0) }
    var focusTarget by remember { mutableStateOf<ItemRow?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    // данные шапки из вставки (отправитель / заказ / время)
    var metaSender by remember { mutableStateOf("") }
    var metaOrder by remember { mutableStateOf("") }
    var metaStamp by remember { mutableStateOf("") }
    var metaAt by remember { mutableStateOf<Long?>(null) }
    val clipboard = LocalClipboardManager.current

    fun addRow(p: Product) {
        val existing = rows.firstOrNull { it.productId == p.id }
        if (existing != null) {
            focusTarget = existing
        } else {
            rows.add(ItemRow(p.name, "", extra = true, productId = p.id).also { r -> r.barcode = p.barcode })
            focusTarget = null
        }
        addTick++
    }

    // вставка из ИИ / Excel: текст с меткой #LAGER — товары попадают в список, дальше их считают тапами
    fun importAi(text: String) {
        val res = AiImport.parse(text)
        if (res == null) {
            message = "Не удалось разобрать текст"
            return
        }
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                res.items.map { it to (db.findByName(it.name) ?: if (it.barcode.isNotBlank()) db.findByBarcode(it.barcode) else null) }
            }
            var added = 0
            for ((it, p) in found) {
                val same = rows.firstOrNull { r ->
                    (p != null && r.productId == p.id) || r.name.trim().equals(it.name.trim(), true)
                }
                if (same != null) {
                    val sum = (normalizeQty(same.qty).toDoubleOrNull() ?: 0.0) + (it.qty.toDoubleOrNull() ?: 0.0)
                    same.qty = cleanQty(sum.toString())
                } else {
                    rows.add(ItemRow(it.name, it.qty, productId = p?.id ?: "").also { r -> r.barcode = it.barcode.ifBlank { p?.barcode ?: "" } })
                    added++
                }
            }
            if (res.sender.isNotBlank()) metaSender = res.sender
            if (res.order.isNotBlank()) metaOrder = res.order
            if (res.stamp.isNotBlank()) { metaStamp = res.stamp; metaAt = res.receivedAt }
            focusTarget = null
            addTick++
            message = buildString {
                append("В список добавлено позиций: $added")
                if (res.zeroLines.isNotEmpty()) append(" · пропущено с количеством 0: ${res.zeroLines.size}")
                if (res.badLines.isNotEmpty()) append(" · не разобрано строк: ${res.badLines.size}")
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Media.uploadPending(ctx, db) }
        Cloud.runProducts(db, sync, false)
    }
    LaunchedEffect(query, sync.version) {
        suggestions = if (query.isBlank()) emptyList()
        else withContext(Dispatchers.IO) { db.search(query, 8) }
    }

    if (scanning) {
        ScannerScreen(prefs = prefs, onResult = { query = it; scanning = false }, onClose = { scanning = false })
        return
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Приём вручную", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            FilledTonalButton(
                onClick = {
                    val t = clipboard.getText()?.text.orEmpty()
                    if (AiImport.looksLike(t)) importAi(t)
                    else message = "В буфере нет данных: текст должен начинаться с метки ${AiImport.MARKER}"
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) { Text("Вставить", style = MaterialTheme.typography.labelMedium) }
        }
        Spacer(Modifier.height(12.dp))
        SearchBar(
            query = query,
            onChange = { v ->
                if (AiImport.looksLike(v)) {
                    // вставлен текст с меткой #LAGER — сразу кладём товары в список
                    query = ""
                    importAi(v)
                } else {
                    query = v
                }
            },
            onScan = { scanning = true },
            multilinePaste = true
        )
        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        } else if (sync.text.isNotBlank() && (sync.busy || suggestions.isEmpty())) {
            Text(sync.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        val head = listOf(metaSender, metaOrder, metaStamp).filter { it.isNotBlank() }.joinToString(" · ")
        if (head.isNotEmpty()) Text(head, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))

        if (suggestions.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column {
                    suggestions.forEachIndexed { i, p ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    addRow(p)
                                    query = ""
                                    message = ""
                                }
                                .padding(12.dp)
                        ) {
                            Text(p.name, fontWeight = FontWeight.Medium)
                            val sub = listOf(
                                if (p.article.isNotBlank()) "арт. ${p.article}" else "",
                                if (p.barcode.isNotBlank()) "ШК ${p.barcode}" else ""
                            ).filter { it.isNotEmpty() }.joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                        }
                        if (i < suggestions.lastIndex) HorizontalDivider()
                    }
                }
            }
        } else if (query.isNotBlank() && !sync.busy) {
            Text("Ничего не найдено", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(12.dp))
        if (rows.isNotEmpty()) {
            Text(
                "Позиций: ${rows.size}. Тап по строке — +1, по числу — барабан; свайп вправо — срок годности, влево — брак и удаление.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
        }
        // обычный список (без барабана): тап +1, тап по числу — барабан с вибрацией, голосом и числом на экране
        CountWheelList(
            rows = rows,
            editable = true,
            modifier = Modifier.weight(1f),
            addTick = addTick,
            emptyText = "Список пуст: найдите товар или нажмите «Вставить»",
            showSearch = false,
            plain = true,
            focusTarget = focusTarget,
            extraLabel = ""
        )
        if (rows.isNotEmpty()) {
            fun d(x: String) = normalizeQty(x).toDoubleOrNull() ?: 0.0
            val counted = rows.count { d(it.fact) > 0.0 }
            val badDefect = rows.firstOrNull { d(it.defect) > d(it.fact) }
            val err = when {
                counted == 0 -> "Посчитайте хотя бы один товар"
                badDefect != null -> "Брак больше количества: ${badDefect.name.trim()}"
                else -> ""
            }
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (rows.any { d(it.fact) == 0.0 && it.extra }) {
                Text("Товары без количества в приём не попадут", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { showDialog = true }, enabled = err.isEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить приём")
            }
        }
    }

    if (showDialog) {
        SaveReceiptDialog(
            initialSender = metaSender,
            initialOrder = metaOrder,
            onDismiss = { showDialog = false },
            onSave = { sender, order, photos, invoices ->
                val now = System.currentTimeMillis()
                fun q(x: String) = normalizeQty(x).ifEmpty { "0" }
                val items = rows
                    .filter { !(it.extra && (normalizeQty(it.fact).toDoubleOrNull() ?: 0.0) <= 0.0) }
                    .map {
                        ReceiptItem(
                            it.productId, it.name.trim(), q(it.fact),
                            if (it.extra) "" else q(it.qty),
                            normalizeQty(it.defect).let { dft -> if ((dft.toDoubleOrNull() ?: 0.0) > 0.0) dft else "" },
                            it.expiry.trim(), it.barcode.trim()
                        )
                    }
                val r = Receipt(
                    id = UUID.randomUUID().toString(),
                    sender = sender.trim(), orderNo = order.trim(), receivedAt = metaAt ?: now, receiver = receiver,
                    items = items,
                    photos = photos, invoices = invoices, updatedAt = now, stamp = metaStamp
                )
                scope.launch(Dispatchers.IO) {
                    Cloud.saveReceipt(db, r)
                    Media.uploadPending(ctx, db)
                }
                rows.clear()
                metaSender = ""; metaOrder = ""; metaStamp = ""; metaAt = null
                showDialog = false
                message = "Приём сохранён: ${fmtTime(now)}"
            }
        )
    }
}

@Composable
private fun SaveReceiptDialog(
    initialSender: String = "",
    initialOrder: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>, List<String>) -> Unit
) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var sender by remember { mutableStateOf(initialSender) }
    var order by remember { mutableStateOf(initialOrder) }
    val photos = remember { mutableStateListOf<String>() }
    val invoices = remember { mutableStateListOf<String>() }

    fun cancel() {
        val all = photos.toList() + invoices.toList()
        scope.launch(Dispatchers.IO) { Media.discard(ctx, db, all) }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { cancel() },
        title = { Text("Данные приёма") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sender, { sender = it }, label = { Text("Имя отправителя") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(order, { order = it }, label = { Text("Номер заказа") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Фото товара", style = MaterialTheme.typography.titleSmall)
                MediaStrip(photos) { photos.remove(it) }
                MediaButtons(kind = "RC", allowFile = false) { photos.add(it) }
                Text("Накладные (фото или файл)", style = MaterialTheme.typography.titleSmall)
                MediaStrip(invoices) { invoices.remove(it) }
                MediaButtons(kind = "IN", allowFile = true) { invoices.add(it) }
                Text("Время приёма сохранится автоматически", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { onSave(sender, order, photos.toList(), invoices.toList()) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = { cancel() }) { Text("Отмена") } }
    )
}

/** Поисковая строка в стиле Google: скруглённая, невысокая, сканер справа внутри. */
@Composable
fun SearchBar(
    query: String,
    onChange: (String) -> Unit,
    onScan: () -> Unit,
    placeholder: String = "Артикул, название или штрих-код",
    /** true — поле принимает вставку из нескольких строк (приём из ИИ); выглядит так же, Enter не делает перенос */
    multilinePaste: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    placeholder,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = !multilinePaste,
                maxLines = if (multilinePaste) 1 else Int.MAX_VALUE,
                keyboardOptions = if (multilinePaste) KeyboardOptions(imeAction = ImeAction.Search) else KeyboardOptions.Default,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            TextButton(onClick = { onChange("") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("✕") }
        }
        IconButton(onClick = onScan) { BarcodeScannerIcon() }
    }
}
