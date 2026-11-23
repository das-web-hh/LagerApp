package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val sync = remember { SyncState() }
    var list by remember { mutableStateOf(emptyList<Receipt>()) }
    var pending by remember { mutableStateOf(emptySet<String>()) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<Receipt?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Media.uploadPending(ctx, db) }
        DriveSync.start(ctx, false)
        Cloud.runReceipts(db, sync, false)
    }
    LaunchedEffect(sync.version, Media.version) {
        withContext(Dispatchers.IO) {
            list = db.allReceipts()
            pending = db.pendingUploads().toSet()
        }
    }

    val id = openId
    if (id != null) {
        ReceiptCardScreen(id) {
            openId = null
            sync.version++
        }
        return
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("История приёмов", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sync.busy) "Проверка…" else sync.text,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    Media.uploadPending(ctx, db)
                    DriveSync.start(ctx, true)
                    scope.launch { Cloud.runReceipts(db, sync, true) }
                },
                enabled = !sync.busy
            ) { Text("Обновить") }
        }
        if (DriveSync.status.isNotBlank()) {
            Text(DriveSync.status, style = MaterialTheme.typography.bodySmall)
        }
        if (Media.lastError.isNotBlank()) {
            Text(Media.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        if (list.isEmpty()) Text("Приёмов пока нет")
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { openId = r.id }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fmtTime(r.receivedAt), fontWeight = FontWeight.Medium)
                            val who = listOf(
                                if (r.sender.isNotBlank()) r.sender else "",
                                if (r.orderNo.isNotBlank()) "заказ № ${r.orderNo}" else ""
                            ).filter { it.isNotEmpty() }.joinToString(" · ")
                            if (who.isNotEmpty()) Text(who, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                r.items.joinToString(", ") { "${it.name} × ${it.qty}" + if (it.barcode.isNotBlank()) " (ШК ${it.barcode})" else "" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            val waiting = r.allMedia().any { it in pending }
                            Text(
                                "Позиций: ${r.items.size}" + if (waiting) " · ⏳ файлы отправляются" else " · ✓ сохранено",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        if (Session.isAdmin) TextButton(onClick = { toDelete = r }) { Text("Удалить") }
                    }
                }
            }
        }
    }

    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить партию?") },
            text = { Text(fmtTime(r.receivedAt) + if (r.sender.isNotBlank()) " · ${r.sender}" else "") },
            confirmButton = {
                Button(onClick = {
                    toDelete = null
                    scope.launch(Dispatchers.IO) {
                        Cloud.deleteReceipt(db, r.id)
                        Media.discard(ctx, db, r.allMedia())
                        sync.version++
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } }
        )
    }
}

@Composable
fun ReceiptEditScreen(id: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    // менять приёмку (отправителя, номер заказа, количества, файлы) может только администратор
    val canEdit = Session.isAdmin
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Receipt?>(null) }
    var sender by remember { mutableStateOf("") }
    var order by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<EditItem>() }
    val photos = remember { mutableStateListOf<String>() }
    val invoices = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var histId by remember { mutableStateOf<String?>(null) }
    var notInCatalog by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val r = withContext(Dispatchers.IO) { db.getReceipt(id) }
        if (r != null) {
            original = r
            sender = r.sender; order = r.orderNo; receiver = r.receiver
            time = fmtTime(r.receivedAt)
            items.clear(); items.addAll(r.items.map { EditItem(it.productId, it.name, it.qty, it.plan, it.defect, it.barcode).also { e -> e.expiry = it.expiry } })
            photos.clear(); photos.addAll(r.photos)
            invoices.clear(); invoices.addAll(r.invoices)
        }
    }

    // нажатие на название товара — история приёма этого товара (без правки)
    val hid = histId
    if (hid != null) {
        ProductHistoryScreen(hid) { histId = null }
        return
    }

    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹", fontSize = 26.sp) }
            Text(if (canEdit) "Изменить приём" else "Приём (просмотр)", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))
        if (!canEdit) {
            Text(
                "Менять приёмку может только администратор.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MinField("Отправитель", sender, readOnly = !canEdit) { sender = it }
        MinField("Номер заказа", order, readOnly = !canEdit) { order = it }
        MinField("Время приёма (дд.мм.гггг чч:мм)", time, readOnly = !canEdit) { time = it }
        MinField("Кто принял", receiver, readOnly = !canEdit) { receiver = it }

        Spacer(Modifier.height(20.dp))
        Text("Товары", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.toList().forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable {
                    scope.launch {
                        val pid = l.productId.ifEmpty { withContext(Dispatchers.IO) { db.findByName(l.name)?.id ?: "" } }
                        if (pid.isEmpty()) notInCatalog = true else histId = pid
                    }
                }) {
                    Text(l.name, style = MaterialTheme.typography.bodyLarge)
                    if (l.plan.isNotBlank()) {
                        Text(
                            "план ${l.plan}" + if (l.defect.isNotBlank() && l.defect != "0") " · брак ${l.defect}" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextField(
                    value = l.qty, onValueChange = { l.qty = it }, singleLine = true, readOnly = !canEdit,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End),
                    colors = minFieldColors(),
                    modifier = Modifier.width(84.dp)
                )
                if (canEdit) TextButton(onClick = { items.remove(l) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        Spacer(Modifier.height(20.dp))
        Text("Фото", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        MediaStrip(photos, if (canEdit) fun(n: String) { photos.remove(n) } else null)
        if (canEdit) {
            Spacer(Modifier.height(4.dp))
            MediaButtons(kind = "RC", allowFile = false) { photos.add(it) }
        }

        Spacer(Modifier.height(16.dp))
        Text("Накладные", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        MediaStrip(invoices, if (canEdit) fun(n: String) { invoices.remove(n) } else null)
        if (canEdit) {
            Spacer(Modifier.height(4.dp))
            MediaButtons(kind = "IN", allowFile = true) { invoices.add(it) }
        }

        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        if (canEdit) Button(
            onClick = {
                val old = original
                val t = parseTime(time)
                if (old == null) {
                    error = "Приём не найден"
                } else if (t == null) {
                    error = "Время в формате дд.мм.гггг чч:мм"
                } else if (items.isEmpty()) {
                    error = "В партии нет товаров — удалите партию целиком"
                } else if (items.any { !qtyValid(it.qty) && !(it.plan.isNotBlank() && it.qty.trim().replace(',', '.').toDoubleOrNull() == 0.0) }) {
                    error = "Количество должно быть больше нуля"
                } else {
                    val ph = photos.toList()
                    val inv = invoices.toList()
                    val r = old.copy(
                        sender = sender.trim(), orderNo = order.trim(), receiver = receiver.trim(),
                        receivedAt = t,
                        items = items.map { ReceiptItem(it.productId, it.name, it.qty.trim().replace(',', '.'), it.plan, it.defect, it.expiry, it.barcode) },
                        photos = ph, invoices = inv
                    )
                    scope.launch(Dispatchers.IO) {
                        Cloud.saveReceipt(db, r)
                        Media.discard(ctx, db, old.allMedia().filter { it !in ph && it !in inv })
                        Media.uploadPending(ctx, db)
                        withContext(Dispatchers.Main) { onClose() }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Сохранить") }
        if (canEdit) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Удалить партию", color = MaterialTheme.colorScheme.error)
        }
    }

    if (notInCatalog) {
        AlertDialog(
            onDismissRequest = { notInCatalog = false },
            title = { Text("Товар не найден") },
            text = { Text("Этого товара нет в каталоге, истории по нему нет.") },
            confirmButton = { Button(onClick = { notInCatalog = false }) { Text("Понятно") } }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить партию?") },
            text = { Text(time) },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    val old = original
                    scope.launch(Dispatchers.IO) {
                        Cloud.deleteReceipt(db, id)
                        Media.discard(ctx, db, old?.allMedia() ?: emptyList())
                        withContext(Dispatchers.Main) { onClose() }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}


private fun cardNum(s: String): Double = s.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

private fun cardFmt(d: Double): String =
    if (d == Math.rint(d)) d.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", d).trimEnd('0').trimEnd('.')

/**
 * Окно партии: сверху главные данные (когда, кто принял, номер заказа, отправитель), ниже простой список «товар — количество»
 * (серым — плановое), под чертой — только товары с расхождениями. Кнопка «Изменить» — только у администратора.
 */
@Composable
fun ReceiptCardScreen(id: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    var rc by remember { mutableStateOf<Receipt?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var histId by remember { mutableStateOf<String?>(null) }
    var notInCatalog by remember { mutableStateOf(false) }

    LaunchedEffect(id, rev) {
        rc = withContext(Dispatchers.IO) { db.getReceipt(id) }
        loaded = true
    }
    if (editing) {
        // после правки или удаления возвращаемся в окно партии (удалённую партию окно закроет само)
        ReceiptEditScreen(id) { editing = false; rev++ }
        return
    }
    val hid = histId
    if (hid != null) {
        ProductHistoryScreen(hid) { histId = null }
        return
    }
    BackHandler(onBack = onClose)
    val r = rc
    if (r == null) {
        if (loaded) LaunchedEffect(Unit) { onClose() }
        Box(Modifier.fillMaxSize())
        return
    }

    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val diffs = r.items.filter {
        val d = cardNum(it.defect)
        (it.plan.isNotBlank() && Math.abs(cardNum(it.qty) - cardNum(it.plan)) > 1e-9) || d > 0.0
    }
    val total = r.items.sumOf { cardNum(it.qty) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹", fontSize = 26.sp) }
            Text("Партия", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))

        // главные данные
        @Composable
        fun info(label: String, value: String) {
            if (value.isBlank() || value.equals("none", true)) return
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = onSurfaceVar, modifier = Modifier.width(120.dp))
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
        }
        info("Принято", fmtTime(r.receivedAt))
        info("Кто принял", r.receiver)
        info("Номер заказа", Ai.latinOrder(r.orderNo))
        info("Отправитель", r.sender)
        info("Позиций", "${r.items.size} · всего ${cardFmt(total)} шт")

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // простой список: слева товар, справа количество, серым — план
        r.items.forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable {
                    scope.launch {
                        val pid = l.productId.ifEmpty { withContext(Dispatchers.IO) { db.findByName(l.name)?.id ?: "" } }
                        if (pid.isEmpty()) notInCatalog = true else histId = pid
                    }
                }) {
                    Text(l.name, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                if (l.plan.isNotBlank()) {
                    Text(l.plan, style = MaterialTheme.typography.bodyMedium, color = onSurfaceVar.copy(alpha = 0.7f))
                    Spacer(Modifier.width(10.dp))
                }
                Text(l.qty, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // черта и расхождения
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        if (diffs.isEmpty()) {
            Text("Расхождений нет", style = MaterialTheme.typography.bodyMedium, color = onSurfaceVar)
        } else {
            Text("Расхождения: ${diffs.size}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            diffs.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val d = cardNum(l.defect)
                        if (d > 0.0) Text("брак ${cardFmt(d)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (l.plan.isNotBlank()) {
                        val diff = cardNum(l.qty) - cardNum(l.plan)
                        Text(l.plan, style = MaterialTheme.typography.bodyMedium, color = onSurfaceVar.copy(alpha = 0.7f))
                        Text(" → ", style = MaterialTheme.typography.bodyMedium, color = onSurfaceVar)
                        Text(l.qty, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (Math.abs(diff) > 1e-9) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                (if (diff > 0) "+" else "") + cardFmt(diff),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        Text(l.qty, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // накладные и фото — только если они есть
        if (r.invoices.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            Text("Исходный документ", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            r.invoices.forEach { n ->
                InlineDoc(n)
                Spacer(Modifier.height(16.dp))
            }
        }
        if (r.photos.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Фото", style = MaterialTheme.typography.labelLarge, color = onSurfaceVar)
            Spacer(Modifier.height(4.dp))
            MediaStrip(r.photos, null)
        }

        Spacer(Modifier.height(24.dp))
        if (Session.isAdmin) {
            Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Изменить") }
        }
    }

    if (notInCatalog) {
        AlertDialog(
            onDismissRequest = { notInCatalog = false },
            title = { Text("Товар не найден") },
            text = { Text("Этого товара нет в каталоге, истории по нему нет.") },
            confirmButton = { Button(onClick = { notInCatalog = false }) { Text("Понятно") } }
        )
    }
}

@Composable
private fun minFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant
)

/** Минималистичное поле: маленькая подпись над строкой с тонкой линией, без рамок и заливки. */
@Composable
private fun MinField(label: String, value: String, readOnly: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextField(
            value = value, onValueChange = onChange, singleLine = true, readOnly = readOnly,
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = minFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
