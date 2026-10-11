package ru.storage.app

import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * «Приём B-Ware» — приём испорченного товара, который привезли.
 * Окно 1: отправитель, номер заказа (справа «Далее»), поиск по каталогу с «+», ниже — колесо-список
 *         (тап +1, барабан цифр, вибрация и голос, срок годности каждой позиции), снизу «Подробности».
 * Окно 2: номер машины и прицепа, предпросмотр документа и «Печать».
 * Состояние живёт в BWareHolder, поэтому не теряется при переходе между вкладками.
 */

object BWareHolder {
    var sender by mutableStateOf("")
    var order by mutableStateOf("")
    var truck by mutableStateOf("")
    var trailer by mutableStateOf("")
    var note by mutableStateOf("")
    val rows = mutableStateListOf<ItemRow>()
    /** выбранные варианты из BWareDetails */
    val details = mutableStateListOf<String>()
    /** 0 — ввод и подсчёт, 1 — предпросмотр и печать */
    var step by mutableIntStateOf(0)

    fun clear() {
        sender = ""
        order = ""
        truck = ""
        trailer = ""
        note = ""
        rows.clear()
        details.clear()
        step = 0
    }
}

/** Варианты «подробностей»: как испортился товар и в каком виде его привезли. */
object BWareDetails {
    val groups: List<Pair<String, List<String>>> = listOf(
        "Когда испортился" to listOf(
            "Испортился во время перевозки",
            "Был испорчен уже при погрузке (таким и привезли)",
            "Причина неизвестна"
        ),
        "В каком виде привезли" to listOf(
            "Паллет упал или груз сместился",
            "Плёнка на паллете порвана",
            "Коробка помята",
            "Коробка мокрая",
            "Упаковка порвана",
            "Упаковка вскрыта",
            "Крышка или пробка открыта",
            "Упаковка грязная",
            "Этикетка повреждена или отсутствует"
        ),
        "Содержимое" to listOf(
            "Товар рассыпался",
            "Содержимое осталось внутри упаковки",
            "Содержимое вытекло или высыпалось наружу",
            "Стекло или банка разбита, треснула",
            "Товар раздавлен или деформирован",
            "Плесень или посторонний запах",
            "Срок годности истёк"
        )
    )
}

internal fun bwQty(s: String): Double = normalizeQty(s).toDoubleOrNull() ?: 0.0

internal fun bwFmt(d: Double): String =
    if (d == Math.floor(d) && d < 1e9) d.toLong().toString()
    else String.format(Locale.ROOT, "%.2f", d).trimEnd('0').trimEnd('.')

internal fun bwEsc(s: String) =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

internal fun bwDash(s: String) = s.trim().ifBlank { "—" }

/** HTML документа для предпросмотра и печати. */
private fun bwareHtml(receiver: String, now: Long): String {
    val h = BWareHolder
    val sb = StringBuilder()
    sb.append(
        "<html><head><meta charset='utf-8'><style>" +
            "body{font-family:sans-serif;font-size:13px;color:#111}" +
            "h1{font-size:20px;margin:0 0 8px}h2{font-size:15px;margin:18px 0 6px}" +
            "table{border-collapse:collapse;width:100%}" +
            "th,td{border:1px solid #888;padding:5px 6px;text-align:left;vertical-align:top}" +
            "th{background:#eee}.n{text-align:right;white-space:nowrap}" +
            ".info td{border:none;padding:2px 10px 2px 0}.info td:first-child{color:#444}" +
            ".sign{margin-top:36px}.sign td{border:none;padding:18px 10px 0 0;width:50%}" +
            ".line{border-top:1px solid #111;padding-top:2px;font-size:11px;color:#444}" +
            "</style></head><body>"
    )
    sb.append("<h1>Акт приёма B-Ware (повреждённый товар)</h1>")
    sb.append("<table class='info'>")
    sb.append("<tr><td>Отправитель:</td><td><b>${bwEsc(bwDash(h.sender))}</b></td></tr>")
    sb.append("<tr><td>Номер заказа:</td><td><b>${bwEsc(bwDash(h.order))}</b></td></tr>")
    sb.append("<tr><td>Номер машины:</td><td><b>${bwEsc(bwDash(h.truck).uppercase())}</b></td></tr>")
    sb.append("<tr><td>Номер прицепа:</td><td><b>${bwEsc(bwDash(h.trailer).uppercase())}</b></td></tr>")
    sb.append("<tr><td>Принял:</td><td>${bwEsc(bwDash(receiver))}</td></tr>")
    sb.append("<tr><td>Дата и время:</td><td>${fmtTime(now)}</td></tr>")
    sb.append("</table>")

    sb.append("<h2>Товары</h2><table><tr><th>№</th><th>Товар</th><th>Кол-во</th><th>Срок годности</th></tr>")
    var total = 0.0
    h.rows.forEachIndexed { i, r ->
        val q = bwQty(r.fact)
        total += q
        sb.append("<tr><td>${i + 1}</td><td>${bwEsc(r.name.trim())}</td>")
        sb.append("<td class='n'>${bwFmt(q)}</td><td>${bwEsc(bwDash(r.expiry))}</td></tr>")
    }
    sb.append("<tr><th></th><th>Итого</th><th class='n'>${bwFmt(total)}</th><th></th></tr></table>")

    val chosen = h.details.toSet()
    val hasDetails = chosen.isNotEmpty() || h.note.isNotBlank()
    if (hasDetails) {
        sb.append("<h2>Подробности повреждений</h2><table>")
        BWareDetails.groups.forEach { (title, opts) ->
            val sel = opts.filter { it in chosen }
            if (sel.isNotEmpty()) {
                sb.append("<tr><td style='width:32%'><b>${bwEsc(title)}</b></td><td>")
                sb.append(sel.joinToString("<br>") { "• " + bwEsc(it) })
                sb.append("</td></tr>")
            }
        }
        if (h.note.isNotBlank()) {
            sb.append("<tr><td><b>Примечание</b></td><td>${bwEsc(h.note.trim()).replace("\n", "<br>")}</td></tr>")
        }
        sb.append("</table>")
    }

    sb.append("<table class='sign'><tr>")
    sb.append("<td><div class='line'>Принял (подпись)</div></td>")
    sb.append("<td><div class='line'>Водитель (подпись)</div></td>")
    sb.append("</tr></table>")
    sb.append("</body></html>")
    return sb.toString()
}

/** Поле поиска в стиле остальных строк: лупа, текст, «+» справа. */
@Composable
private fun BWareSearchField(
    query: String,
    onChange: (String) -> Unit,
    onAdd: () -> Unit
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
                    "Название товара (от $MIN_SEARCH_CHARS букв)",
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            TextButton(onClick = { onChange("") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("✕") }
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = "Добавить товар", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Поиск по каталогу («+» добавляет как написано) и колесо-список под ним — общий блок для «Приём B-Ware»
 * и «Инвентарь B-Ware». itemDetails = true — у позиции кнопка свайпа «Причины и вид».
 */
@Composable
fun CatalogWheel(
    rows: SnapshotStateList<ItemRow>,
    modifier: Modifier = Modifier,
    errorText: String = "",
    emptyText: String,
    itemDetails: Boolean = false,
    onAdded: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val sync = remember { SyncState() }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf(emptyList<Product>()) }
    var addTick by remember { mutableIntStateOf(0) }
    var focusTarget by remember { mutableStateOf<ItemRow?>(null) }
    var detailsRow by remember { mutableStateOf<ItemRow?>(null) }

    LaunchedEffect(Unit) { Cloud.runProducts(db, sync, false) }
    LaunchedEffect(query, sync.version) {
        suggestions = if (query.trim().length < MIN_SEARCH_CHARS) emptyList()
        else withContext(Dispatchers.IO) { db.search(query, 8) }
    }

    fun addRow(name: String, productId: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val existing = rows.firstOrNull {
            (productId.isNotEmpty() && it.productId == productId) || it.name.trim().equals(n, true)
        }
        if (existing != null) {
            focusTarget = existing
            Toast.makeText(ctx, "Этот товар уже в списке", Toast.LENGTH_SHORT).show()
        } else {
            rows.add(ItemRow(n, "", extra = true, productId = productId))
            focusTarget = null
        }
        addTick++
        query = ""
        onAdded()
    }

    Column(modifier) {
        BWareSearchField(
            query = query,
            onChange = { query = it },
            onAdd = {
                val typed = query.trim()
                val pid = suggestions.firstOrNull { it.name.trim().equals(typed, true) }?.id.orEmpty()
                addRow(typed, pid)
            }
        )
        if (errorText.isNotBlank()) {
            Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            CountWheelList(
                rows = rows,
                editable = true,
                modifier = Modifier.fillMaxSize(),
                addTick = addTick,
                emptyText = emptyText,
                showSearch = false,
                bware = true,
                focusTarget = focusTarget,
                onDetails = if (itemDetails) ({ r: ItemRow -> detailsRow = r }) else null
            )
            val typed = query.trim()
            if (typed.length >= MIN_SEARCH_CHARS) {
                Card(Modifier.fillMaxWidth().heightIn(max = 300.dp).align(Alignment.TopCenter)) {
                    if (suggestions.isEmpty()) {
                        Text(
                            if (sync.busy) "Загрузка каталога…" else "В каталоге не найдено. Нажмите «+», чтобы добавить как написано.",
                            Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            suggestions.forEachIndexed { i, p ->
                                Column(
                                    Modifier.fillMaxWidth().clickable { addRow(p.name, p.id) }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(p.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                }
            }
        }
    }

    detailsRow?.let { r -> BWareItemDialog(r) { detailsRow = null } }
}

/** Предпросмотр HTML-документа (как лист бумаги, можно увеличивать щипком). */
@Composable
fun HtmlPreview(html: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = androidx.compose.ui.graphics.Color.White
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.WHITE)
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                }
            },
            update = { v ->
                if (v.tag != html) {
                    v.tag = html
                    v.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            }
        )
    }
}

/** Причины порчи и тип целостности одной позиции (инвентарь B-Ware). Попадает в документ. */
@Composable
private fun BWareItemDialog(row: ItemRow, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(row.name.ifBlank { "Товар" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable
                fun group(title: String, opts: List<String>, sel: SnapshotStateList<String>) {
                    Text(title, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    opts.forEach { o ->
                        val on = o in sel
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) sel.remove(o) else sel.add(o) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = on, onCheckedChange = { if (it) sel.add(o) else sel.remove(o) })
                            Text(o, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                group("Причина порчи", InvOptions.reasons, row.reasons)
                group("Тип целостности", InvOptions.damage, row.damage)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    row.note, { row.note = it },
                    label = { Text("Примечание") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("Готово") } },
        dismissButton = {
            TextButton(onClick = { row.reasons.clear(); row.damage.clear(); row.note = "" }) { Text("Сбросить") }
        }
    )
}

@Composable
fun BWareReceiveScreen(receiver: String, onBack: () -> Unit) {
    val h = BWareHolder
    BackHandler { if (h.step == 1) h.step = 0 else onBack() }
    if (h.step == 1) {
        BWarePreview(receiver)
        return
    }

    var detailsOpen by remember { mutableStateOf(false) }
    var clearOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Приём B-Ware", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (h.rows.isNotEmpty() || h.sender.isNotBlank() || h.order.isNotBlank()) {
                TextButton(onClick = { clearOpen = true }) { Text("Очистить") }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 1-я строка: отправитель
        OutlinedTextField(
            h.sender, { h.sender = it },
            label = { Text("Имя отправителя") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        // 2-я строка: номер заказа и «Далее» справа
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                h.order, { h.order = it },
                label = { Text("Номер заказа") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = h.rows.isNotEmpty(),
                onClick = {
                    val bad = h.rows.firstOrNull { bwQty(it.fact) <= 0.0 }
                    if (bad != null) {
                        error = "Укажите количество: ${bad.name.trim()}"
                    } else {
                        error = ""
                        h.step = 1
                    }
                }
            ) { Text("Далее") }
        }
        Spacer(Modifier.height(8.dp))
        // 3-я строка: поиск по каталогу и колесо-список
        CatalogWheel(
            rows = h.rows,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            errorText = error,
            emptyText = "Введите название товара и выберите из подсказок\nили нажмите «+»",
            onAdded = { error = "" }
        )
        Spacer(Modifier.height(8.dp))
        val n = h.details.size
        OutlinedButton(onClick = { detailsOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (n > 0 || h.note.isNotBlank()) "Подробности повреждений (выбрано: $n)" else "Подробности повреждений")
        }
    }

    if (detailsOpen) BWareDetailsDialog { detailsOpen = false }

    if (clearOpen) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("Очистить приём?") },
            text = { Text("Список товаров, отправитель, номера и подробности будут удалены.") },
            confirmButton = { Button(onClick = { h.clear(); clearOpen = false; error = "" }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("Отмена") } }
        )
    }
}

/** Выбор подробностей: как испортился, в каком виде привезли, что с содержимым. Попадает в документ. */
@Composable
private fun BWareDetailsDialog(onClose: () -> Unit) {
    val h = BWareHolder
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Подробности повреждений") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BWareDetails.groups.forEach { (title, opts) ->
                    Text(title, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    opts.forEach { o ->
                        val on = o in h.details
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { if (on) h.details.remove(o) else h.details.add(o) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = on, onCheckedChange = { if (it) h.details.add(o) else h.details.remove(o) })
                            Text(o, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    h.note, { h.note = it },
                    label = { Text("Другое / примечание") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("Готово") } },
        dismissButton = {
            TextButton(onClick = { h.details.clear(); h.note = "" }) { Text("Сбросить") }
        }
    )
}

/** Окно «Далее»: номер машины и прицепа, предпросмотр документа, печать. */
@Composable
private fun BWarePreview(receiver: String) {
    val h = BWareHolder
    val ctx = LocalContext.current
    val stamp = remember { System.currentTimeMillis() }
    val html = bwareHtml(receiver, stamp) // перечитывается при изменении полей и списка
    var clearOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { h.step = 0 }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text("Документ", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                h.truck, { h.truck = it },
                label = { Text("Номер машины") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                h.trailer, { h.trailer = it },
                label = { Text("Номер прицепа") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        HtmlPreview(html, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val name = "BWare_" + (h.order.ifBlank { h.sender }.ifBlank { "приём" })
                    .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
                Printer.print(ctx, name, html)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("🖨 Печать") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { clearOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Закончить и очистить")
        }
    }

    if (clearOpen) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("Закончить приём?") },
            text = { Text("Всё введённое будет удалено. Убедитесь, что документ уже напечатан или сохранён в PDF.") },
            confirmButton = { Button(onClick = { h.clear(); clearOpen = false }) { Text("Закончить") } },
            dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("Отмена") } }
        )
    }
}
