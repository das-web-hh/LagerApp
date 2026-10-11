package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Приёмка, в которой был товар, и строки этого товара в ней. */
private class HistEntry(val receipt: Receipt, val lines: List<ReceiptItem>)

private fun entriesFor(db: ProductDb, p: Product): List<HistEntry> {
    val nameLc = p.name.trim().lowercase()
    return db.allReceipts().mapNotNull { r ->
        val lines = r.items.filter {
            it.productId == p.id || (it.productId.isEmpty() && it.name.trim().lowercase() == nameLc)
        }
        if (lines.isEmpty()) null else HistEntry(r, lines)
    }
}

/**
 * Карточка «История товара»: данные товара текстом и все приёмы этого товара (новые сверху):
 * отправитель, номер заказа, время приёма, кто принял, количество, фото и накладные с пролистыванием.
 * Здесь ничего не редактируется — править товар можно только в «Каталоге товаров».
 */
@Composable
fun ProductHistoryScreen(id: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    var product by remember { mutableStateOf<Product?>(null) }
    var entries by remember { mutableStateOf<List<HistEntry>?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(id) {
        withContext(Dispatchers.IO) {
            val p = db.getProduct(id)
            product = p
            entries = if (p != null) entriesFor(db, p) else emptyList()
        }
        loaded = true
    }

    val v = viewer
    if (v != null) {
        DocViewerScreen(v) { viewer = null }
        return
    }

    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("История приёма товара", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        val p = product
        if (p == null) {
            Text(if (loaded) "Товар не найден" else "Загрузка…")
        } else {
            Text(p.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            val fields = listOf(
                "Производитель" to p.manufacturer,
                "Вес / объём" to listOf(p.weight, p.unit).filter { it.isNotBlank() }.joinToString(" "),
                "Тип" to p.pack,
                "Артикул" to p.article,
                "Штрих-код" to p.barcode
            ).filter { it.second.isNotBlank() }
            fields.forEach { (k, value) -> Text("$k: $value", style = MaterialTheme.typography.bodyMedium) }
            if (p.photos.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { viewer = p.photos }) { Text("Фото товара (${p.photos.size})") }
            }
            Spacer(Modifier.height(16.dp))
            val list = entries
            Text(
                if (list == null) "Загрузка приёмов…" else "Приёмов: ${list.size}",
                style = MaterialTheme.typography.titleSmall
            )
            list?.forEach { e -> EntryCard(e) { viewer = it } }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}

@Composable
private fun EntryCard(e: HistEntry, onView: (List<String>) -> Unit) {
    val r = e.receipt
    fun v(s: String) = s.ifBlank { "—" }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Время приёма: ${fmtTime(r.receivedAt)}", fontWeight = FontWeight.Medium)
            Text("Отправитель: ${v(r.sender)}")
            Text("Номер заказа: ${v(r.orderNo)}")
            Text("Принял: ${v(r.receiver)}")
            e.lines.forEach { l ->
                val extra = listOfNotNull(
                    l.plan.takeIf { it.isNotBlank() }?.let { "по документу $it" },
                    l.defect.takeIf { it.isNotBlank() && it != "0" }?.let { "брак $it" }
                ).joinToString(" · ")
                Text(
                    "Количество: ${v(l.qty)}" + if (extra.isNotEmpty()) " ($extra)" else "",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            if (r.photos.isNotEmpty() || r.invoices.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.photos.isNotEmpty()) {
                        OutlinedButton(onClick = { onView(r.photos) }) { Text("Фото (${r.photos.size})") }
                    }
                    if (r.invoices.isNotEmpty()) {
                        Button(onClick = { onView(r.invoices) }) { Text("Накладная (${r.invoices.size})") }
                    }
                }
            }
        }
    }
}
