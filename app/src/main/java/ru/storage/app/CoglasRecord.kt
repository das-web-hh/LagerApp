package ru.storage.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/*
 * Карточки только для чтения: накладная и приход товара (шапка + позиции) во вкладке App,
 * плюс инструменты заказа: поиск по позициям, фильтр по статусу, копирование/отправка, «Распечатать обзор».
 * Ничего не меняет на сайте, кроме явной печати обзора заказа (с подтверждением).
 */

private fun cgToast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

private fun cgClip(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Coglas", text))
    cgToast(ctx, "Скопировано")
}

private fun cgSend(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------------------------------------------------------- инструменты заказа

/** 0 — все позиции, 1 — не готовые, 2 — готовые. */
fun visiblePositions(d: CgOrderDetail): List<CgPos> {
    val q = CoglasState.posQuery.trim().lowercase()
    return d.positions.filter { p ->
        (when (CoglasState.posFilter) { 1 -> !p.done; 2 -> p.done; else -> true }) &&
            (q.isEmpty() || p.title.lowercase().contains(q) || p.details.lowercase().contains(q))
    }
}

private fun orderText(d: CgOrderDetail): String = buildString {
    append("Заказ ").append(d.number).append('\n')
    d.header.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
    append("Позиции: ${d.total} · готово ${d.done} · в работе ${d.proc} · создано ${d.created}\n")
    d.positions.forEach { p -> append(p.number).append(". ").append(if (p.done) "✓ " else "").append(p.title).append(" — ").append(p.details).append('\n') }
}

@Composable
fun OrderTools(o: CgOrder, d: CgOrderDetail) {
    val ctx = LocalContext.current
    val st = CoglasState
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = st.posQuery, onValueChange = { st.posQuery = it }, singleLine = true,
            placeholder = { Text("Поиск по позициям (артикул, название, EAN)") },
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Все" to 0, "Не готовы" to 1, "Готовы" to 2).forEach { (t, v) ->
                FilterChip(selected = st.posFilter == v, onClick = { st.posFilter = v }, label = { Text(t) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { cgClip(ctx, orderText(d)) }, modifier = Modifier.weight(1f)) { Text("Копировать", maxLines = 1) }
            OutlinedButton(onClick = { cgSend(ctx, orderText(d)) }, modifier = Modifier.weight(1f)) { Text("Поделиться", maxLines = 1) }
            OutlinedButton(onClick = { st.open(o) }, modifier = Modifier.weight(1f)) { Text("Обновить", maxLines = 1) }
        }
        OutlinedButton(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Распечатать обзор заказа")
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Распечатать обзор?") },
            text = { Text("Заказ ${o.number}. Сайт отправит обзор на печать на выбранном рабочем месте (вкладка «Настройки» сайта → Текущее рабочее место).") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    scope.launch {
                        try {
                            CoglasData.call(
                                "GoodsInOrder.Forms.GoodsInOrder.LayoutItems.GoodsInOrderDetails.Actions.PrintGoodsInOrderSummary",
                                JSONObject().put("Id", o.id).toString()
                            )
                            cgToast(ctx, "Обзор отправлен на печать")
                        } catch (e: CgError) {
                            cgToast(ctx, e.message ?: "Не удалось отправить на печать")
                        } catch (e: Exception) {
                            cgToast(ctx, "Не удалось отправить на печать")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Печатать") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } }
        )
    }
}

// ---------------------------------------------------------------- накладная / приход: шапка + позиции

class CgRecordCfg(val read: String, val positions: String, val posLabel: String = "Позиции")

/** Для каких списков есть карточка «шапка + позиции» (названия запросов взяты из отчёта по сайту). */
val RECORD_CFG = mapOf(
    "DeliveryNote.Forms.DeliveryNoteList" to CgRecordCfg(
        "DeliveryNote.Forms.DeliveryNote.LayoutItems.DeliveryNoteDetails.Actions.Read",
        "DeliveryNote.Forms.DeliveryNote.LayoutItems.Positions.Actions.Search"
    ),
    "BusinessPartner.Forms.BusinessPartnerList" to CgRecordCfg(
        "BusinessPartner.Forms.BusinessPartner.LayoutItems.BusinessPartnerDetails.Actions.Read",
        "BusinessPartner.Forms.BusinessPartner.LayoutItems.ShippingTariffList.Actions.Search",
        "Тарифы доставки"
    ),
    "GoodsInReceipt.Forms.GoodsInReceiptList" to CgRecordCfg(
        "GoodsInReceipt.Forms.GoodsInReceipt.LayoutItems.GoodsInReceiptDetails.Actions.Read",
        "GoodsInReceipt.Forms.GoodsInReceipt.LayoutItems.Positions.Actions.Search"
    )
)

class CgRecord(val header: List<Pair<String, String>>, val positions: List<List<Pair<String, String>>>, val total: Int, val posErr: String = "")

object CgRecordApi {
    suspend fun load(cfg: CgRecordCfg, id: Long): CgRecord {
        val head = CoglasData.call(cfg.read, JSONObject().put("Id", id).toString(), retry = true)
        val r = JSONObject(head).optJSONObject("Result") ?: JSONObject()
        val header = CgList.flat(r)
        val rows = ArrayList<List<Pair<String, String>>>()
        var total = 0
        var skip = 0
        var posErr = ""
        try {
            while (skip < 500) {
                val body = JSONObject()
                    .put(
                        "SearchRequest", JSONObject()
                            .put("SortConfigurationKey", JSONObject.NULL)
                            .put("PropertyFilters", JSONArray())
                            .put("InvertSorting", false)
                            .put("CancelSearchRequestIfAbortedByClient", true)
                            .put("Skip", skip)
                            .put("Top", 100)
                    )
                    .put("ParentId", id)
                    .toString()
                val txt = CoglasData.call(cfg.positions, body, retry = true)
                val pr = JSONObject(txt).optJSONObject("PageResult")
                val arr = pr?.optJSONArray("Items")
                total = pr?.optInt("Total") ?: 0
                val n = arr?.length() ?: 0
                for (i in 0 until n) arr?.optJSONObject(i)?.let { rows.add(CgList.flat(it)) }
                skip += n
                if (n <= 0 || skip >= total) break
            }
        } catch (e: CgError) {
            // шапка важнее: если список позиций не открылся, показываем хотя бы шапку
            if (e.needSite) throw e
            posErr = e.message ?: "список не загрузился"
        } catch (e: Exception) {
            posErr = "список не удалось разобрать"
        }
        return CgRecord(header, rows, total, posErr)
    }
}

private fun recText(title: String, r: CgRecord): String = buildString {
    append(title).append('\n')
    r.header.forEach { (k, v) -> append(k.substringAfterLast('.')).append(": ").append(v).append('\n') }
    append("Позиций: ${r.total}\n")
    r.positions.forEachIndexed { i, p -> append(i + 1).append(". ").append(CgList.titleOf(p)).append(" — ").append(CgList.subOf(p)).append('\n') }
}

@Composable
fun RecordView(title: String, cfg: CgRecordCfg, id: Long, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var rec by remember(id) { mutableStateOf<CgRecord?>(null) }
    var err by remember(id) { mutableStateOf("") }
    var reload by remember(id) { mutableStateOf(0) }
    var q by remember(id) { mutableStateOf("") }
    LaunchedEffect(id, reload) {
        err = ""
        try {
            rec = CgRecordApi.load(cfg, id)
        } catch (e: CgError) {
            err = e.message ?: "Ошибка"
        } catch (e: Exception) {
            err = "Не удалось разобрать ответ сайта"
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← К списку") }
            Spacer(Modifier.weight(1f))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 12.dp), maxLines = 1)
        }
        val r = rec
        when {
            r == null && err.isNotEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(err, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { reload++ }) { Text("Повторить") }
            }
            r == null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
            else -> {
                val needle = q.trim().lowercase()
                val rows = r.positions.filter { needle.isEmpty() || it.joinToString(" ") { p -> p.second }.lowercase().contains(needle) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                r.header.forEach { (k, v) ->
                                    Spacer(Modifier.height(6.dp))
                                    Text(k.substringAfterLast('.'), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(v, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { cgClip(ctx, recText(title, r)) }, modifier = Modifier.weight(1f)) { Text("Копировать", maxLines = 1) }
                            OutlinedButton(onClick = { cgSend(ctx, recText(title, r)) }, modifier = Modifier.weight(1f)) { Text("Поделиться", maxLines = 1) }
                            OutlinedButton(onClick = { rec = null; reload++ }, modifier = Modifier.weight(1f)) { Text("Обновить", maxLines = 1) }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = q, onValueChange = { q = it }, singleLine = true,
                            placeholder = { Text("Поиск по позициям") }, modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large
                        )
                    }
                    item {
                        Text(
                            cfg.posLabel + ": ${r.total}" + (if (rows.size != r.positions.size) " · найдено ${rows.size}" else "") +
                                (if (r.posErr.isNotEmpty()) "\nНе загрузились: ${r.posErr}" else ""),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    items(rows) { p ->
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(CgList.titleOf(p), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                val sub = CgList.subOf(p)
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
