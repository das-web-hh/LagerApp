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
        Cloud.runReceipts(db, sync, false)
    }
    LaunchedEffect(sync.version) {
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
                onClick = { scope.launch { Cloud.runReceipts(db, sync, true) } },
                enabled = !sync.busy
            ) { Text("Обновить") }
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
                                r.items.joinToString(", ") { "${it.name} × ${it.qty}" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            val waiting = r.allMedia().any { it in pending }
                            Text(
                                "Позиций: ${r.items.size}" + if (waiting) " · ⏳ файлы отправляются" else " · ✓ сохранено",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(onClick = { toDelete = r }) { Text("Удалить") }
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
fun ReceiptCardScreen(id: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
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

    LaunchedEffect(id) {
        val r = withContext(Dispatchers.IO) { db.getReceipt(id) }
        if (r != null) {
            original = r
            sender = r.sender; order = r.orderNo; receiver = r.receiver
            time = fmtTime(r.receivedAt)
            items.clear(); items.addAll(r.items.map { EditItem(it.productId, it.name, it.qty) })
            photos.clear(); photos.addAll(r.photos)
            invoices.clear(); invoices.addAll(r.invoices)
        }
    }

    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Карточка приёма", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(sender, { sender = it }, label = { Text("Имя отправителя") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(order, { order = it }, label = { Text("Номер заказа") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(time, { time = it }, label = { Text("Время приёма (дд.мм.гггг чч:мм)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(receiver, { receiver = it }, label = { Text("Кто принял") },
            singleLine = true, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(16.dp))
        Text("Товары", style = MaterialTheme.typography.titleSmall)
        items.toList().forEach { l ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l.name, modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = l.qty, onValueChange = { l.qty = it }, singleLine = true,
                    label = { Text("Кол-во") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(100.dp)
                )
                TextButton(onClick = { items.remove(l) }) { Text("✕") }
            }
            HorizontalDivider()
        }

        Spacer(Modifier.height(16.dp))
        Text("Фото", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        MediaStrip(photos) { photos.remove(it) }
        Spacer(Modifier.height(8.dp))
        MediaButtons(allowFile = false) { photos.add(it) }

        Spacer(Modifier.height(16.dp))
        Text("Накладные", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        MediaStrip(invoices) { invoices.remove(it) }
        Spacer(Modifier.height(8.dp))
        MediaButtons(allowFile = true) { invoices.add(it) }

        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val old = original
                val t = parseTime(time)
                if (old == null) {
                    error = "Приём не найден"
                } else if (t == null) {
                    error = "Время в формате дд.мм.гггг чч:мм"
                } else if (items.isEmpty()) {
                    error = "В партии нет товаров — удалите партию целиком"
                } else if (items.any { !qtyValid(it.qty) }) {
                    error = "Количество должно быть больше нуля"
                } else {
                    val ph = photos.toList()
                    val inv = invoices.toList()
                    val r = old.copy(
                        sender = sender.trim(), orderNo = order.trim(), receiver = receiver.trim(),
                        receivedAt = t,
                        items = items.map { ReceiptItem(it.productId, it.name, it.qty.trim().replace(',', '.')) },
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
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Удалить партию")
        }
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
