package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Позиция приёмки: товар + количество (используется и при приёме, и в карточке партии). */
class EditItem(val productId: String, val name: String, initial: String) {
    var qty by mutableStateOf(initial)
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
    val lines = remember { mutableStateListOf<EditItem>() }
    var showDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

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
        Text("Приём вручную", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        SearchBar(
            query = query,
            onChange = { query = it },
            onScan = { scanning = true }
        )
        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        } else if (sync.text.isNotBlank() && (sync.busy || suggestions.isEmpty())) {
            Text(sync.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        if (suggestions.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column {
                    suggestions.forEachIndexed { i, p ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    if (lines.none { it.productId == p.id }) lines.add(0, EditItem(p.id, p.name, "1"))
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
        if (lines.isNotEmpty()) Text("Принято позиций: ${lines.size}", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f)) {
            items(lines.toList(), key = { it.productId }) { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.name, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = l.qty, onValueChange = { l.qty = it },
                        singleLine = true, label = { Text("Кол-во") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(100.dp)
                    )
                    TextButton(onClick = { lines.remove(l) }) { Text("✕") }
                }
                HorizontalDivider()
            }
        }
        if (lines.isNotEmpty()) {
            val ok = lines.all { qtyValid(it.qty) }
            if (!ok) Text("Укажите количество больше нуля", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { showDialog = true }, enabled = ok, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить приём")
            }
        }
    }

    if (showDialog) {
        SaveReceiptDialog(
            onDismiss = { showDialog = false },
            onSave = { sender, order, photos, invoices ->
                val now = System.currentTimeMillis()
                val r = Receipt(
                    id = UUID.randomUUID().toString(),
                    sender = sender.trim(), orderNo = order.trim(), receivedAt = now, receiver = receiver,
                    items = lines.map { ReceiptItem(it.productId, it.name, it.qty.trim().replace(',', '.')) },
                    photos = photos, invoices = invoices, updatedAt = now
                )
                scope.launch(Dispatchers.IO) {
                    Cloud.saveReceipt(db, r)
                    Media.uploadPending(ctx, db)
                }
                lines.clear()
                showDialog = false
                message = "Приём сохранён: ${fmtTime(now)}"
            }
        )
    }
}

@Composable
private fun SaveReceiptDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>, List<String>) -> Unit
) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var sender by remember { mutableStateOf("") }
    var order by remember { mutableStateOf("") }
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
fun SearchBar(query: String, onChange: (String) -> Unit, onScan: () -> Unit) {
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
                    "Артикул, название или штрих-код",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            TextButton(onClick = { onChange("") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("✕") }
        }
        TextButton(onClick = onScan, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("📷") }
    }
}
