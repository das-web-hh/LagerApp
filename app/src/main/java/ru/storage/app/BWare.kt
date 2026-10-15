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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * «Приём B-Ware» — приём испорченного товара, который привезли.
 * Окно 1 (минимализм): [Отправитель | Номер машины], [Номер заказа | Номер прицепа], поиск «Товар» с «+»,
 *   ниже обычный список. Тап по строке +1 (вибрация, голос), тап по числу — барабан цифр,
 *   свайп вправо — срок годности, свайп влево — «Причины» и «Удалить», удержание — причины и повреждения.
 *   «Далее» (вверху справа) — предпросмотр документа и печать.
 * Состояние живёт в BWareHolder, поэтому не теряется при переходе между вкладками.
 */

object BWareHolder {
    var sender by mutableStateOf("")
    var order by mutableStateOf("")
    var truck by mutableStateOf("")
    var trailer by mutableStateOf("")
    val rows = mutableStateListOf<ItemRow>()
    /** 0 — ввод и подсчёт, 1 — предпросмотр и печать */
    var step by mutableIntStateOf(0)

    fun clear() {
        sender = ""
        order = ""
        truck = ""
        trailer = ""
        rows.clear()
        step = 0
    }
}

/** Варианты повреждений при приёме: как испортился, в каком виде привезли, что с содержимым. */
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

/** Выбранное в позиции по группам (для документа): «Группа: пункт; пункт» построчно. */
internal fun bwDetailsHtml(row: ItemRow, groups: List<Pair<String, List<String>>>): String {
    val sb = StringBuilder()
    groups.forEach { (title, opts) ->
        val sel = opts.filter { it in row.reasons }
        if (sel.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("<br>")
            sb.append("<b>${bwEsc(trOpt(title))}:</b> ").append(sel.joinToString("; ") { bwEsc(trOpt(it)) })
        }
    }
    if (row.note.isNotBlank()) {
        if (sb.isNotEmpty()) sb.append("<br>")
        sb.append("<i>${bwEsc(row.note.trim()).replace("\n", "<br>")}</i>")
    }
    return if (sb.isEmpty()) "—" else sb.toString()
}

internal const val BW_CSS =
    "@page{margin:12mm}body{font-family:sans-serif;font-size:12px;color:#111;margin:0;padding:8mm 10mm}" +
        "h1{font-size:20px;margin:0 0 8px}h2{font-size:15px;margin:16px 0 6px}" +
        "table{border-collapse:collapse;width:100%}" +
        "th,td{border:1px solid #888;padding:4px 5px;text-align:left;vertical-align:top}" +
        "th{background:#eee}.n{text-align:right;white-space:nowrap}" +
        ".info td{border:none;padding:2px 10px 2px 0}.info td:first-child{color:#444}" +
        ".sign{margin-top:36px}.sign td{border:none;padding:18px 10px 0 0;width:50%}" +
        ".line{border-top:1px solid #111;padding-top:2px;font-size:11px;color:#444}" +
        Signature.CSS

/** Надпись в документе: водитель подписал накладную (всегда по-немецки, рукописным шрифтом). */
internal const val DRIVER_SIGNED_DE = "Der Fahrer hat den Lieferschein unterschrieben"

/** HTML документа приёма для предпросмотра и печати. Язык(и) и автоподпись — по настройкам печати. */
private fun bwareHtml(receiver: String, now: Long): String {
    val h = BWareHolder
    val sb = StringBuilder()
    sb.append("<html><head><meta charset='utf-8'><style>$BW_CSS</style></head><body>")
    sb.append("<h1>${trHead("bw_title")}</h1>")
    sb.append("<table class='info'>")
    sb.append("<tr><td>${tr("sender")}:</td><td><b>${bwEsc(bwDash(h.sender))}</b></td></tr>")
    sb.append("<tr><td>${tr("order")}:</td><td><b>${bwEsc(bwDash(h.order))}</b></td></tr>")
    sb.append("<tr><td>${tr("truck")}:</td><td><b>${bwEsc(bwDash(h.truck).uppercase())}</b></td></tr>")
    sb.append("<tr><td>${tr("trailer")}:</td><td><b>${bwEsc(bwDash(h.trailer).uppercase())}</b></td></tr>")
    sb.append("<tr><td>${tr("received_by")}:</td><td>${bwEsc(bwDash(receiver))}</td></tr>")
    sb.append("<tr><td>${tr("date_time")}:</td><td>${fmtTime(now)}</td></tr>")
    sb.append("</table>")
    sb.append("<h2>${tr("goods_h")}</h2><table><tr><th>${trb("th_no")}</th><th>${trb("th_item")}</th><th>${trb("th_qty")}</th><th>${trb("th_expiry")}</th><th>${trb("th_details")}</th></tr>")
    var total = 0.0
    h.rows.forEachIndexed { i, r ->
        val q = bwQty(r.fact)
        total += q
        sb.append("<tr><td>${i + 1}</td><td>${bwEsc(r.name.trim())}</td><td class='n'>${bwFmt(q)}</td>")
        sb.append("<td>${bwEsc(bwDash(r.expiry))}</td><td>${bwDetailsHtml(r, BWareDetails.groups)}</td></tr>")
    }
    sb.append("<tr><th></th><th>${trb("total")}</th><th class='n'>${bwFmt(total)}</th><th></th><th></th></tr></table>")
    sb.append("<table class='sign'><tr><td>${Signature.cell("sig_received", receiver, now)}</td>")
    // вместо места для подписи водителя — рукописная надпись по-немецки: водитель расписался на Lieferschein
    sb.append("<td><div style='min-height:90px;display:flex;align-items:flex-end'>${Signature.handwritten(DRIVER_SIGNED_DE)}</div><div class='line'></div></td></tr></table>")
    sb.append("</body></html>")
    return sb.toString()
}

/** Невысокое поле ввода без рамки: подсказка внутри, заглавные буквы для номеров. */
@Composable
fun CompactField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    caps: Boolean = false
) {
    Box(
        modifier.height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.None
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Поиск товара: лупа, надпись «Товар», «+» справа. */
@Composable
private fun BWareSearchField(query: String, onChange: (String) -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Товар", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1)
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
 * Поиск по каталогу («+» добавляет как написано) и обычный список под ним — общий блок для «Приём B-Ware»
 * и «Инвентарь B-Ware». groups — варианты для окна «причины и повреждения» (удержание строки).
 */
@Composable
fun CatalogWheel(
    rows: SnapshotStateList<ItemRow>,
    groups: List<Pair<String, List<String>>>,
    modifier: Modifier = Modifier,
    errorText: String = "",
    emptyText: String = "Введите товар и нажмите «+»",
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
                plain = true,
                focusTarget = focusTarget,
                onDetails = { r -> detailsRow = r }
            )
            val typed = query.trim()
            if (typed.length >= MIN_SEARCH_CHARS) {
                Card(Modifier.fillMaxWidth().heightIn(max = 300.dp).align(Alignment.TopCenter)) {
                    if (suggestions.isEmpty()) {
                        Text(
                            if (sync.busy) "Загрузка каталога…" else "Нет в каталоге — нажмите «+», чтобы добавить как написано.",
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

    detailsRow?.let { r -> BWareItemDialog(r, groups) { detailsRow = null } }
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

/** Причины и повреждения одной позиции (удержание строки). Попадает в документ. */
@Composable
private fun BWareItemDialog(row: ItemRow, groups: List<Pair<String, List<String>>>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(row.name.ifBlank { "Товар" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.forEach { (title, opts) ->
                    Text(title, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    opts.forEach { o ->
                        val on = o in row.reasons
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) row.reasons.remove(o) else row.reasons.add(o) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = on, onCheckedChange = { if (it) row.reasons.add(o) else row.reasons.remove(o) })
                            Text(o, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
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
            TextButton(onClick = { row.reasons.clear(); row.note = "" }) { Text("Сбросить") }
        }
    )
}

/** Верхняя строка окна: заголовок, «Очистить», «Далее». */
@Composable
internal fun BWareHeader(title: String, canClear: Boolean, canNext: Boolean, onClear: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
        if (canClear) TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Очистить") }
        Button(
            onClick = onNext,
            enabled = canNext,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
            modifier = Modifier.height(36.dp)
        ) { Text("Далее") }
    }
}

@Composable
fun BWareReceiveScreen(receiver: String, onBack: () -> Unit) {
    val h = BWareHolder
    BackHandler { if (h.step == 1) h.step = 0 else onBack() }
    if (h.step == 1) {
        BWarePreview(receiver)
        return
    }

    var clearOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        BWareHeader(
            title = "Приём B-Ware",
            canClear = h.rows.isNotEmpty() || h.sender.isNotBlank() || h.order.isNotBlank() ||
                h.truck.isNotBlank() || h.trailer.isNotBlank(),
            canNext = h.rows.isNotEmpty(),
            onClear = { clearOpen = true },
            onNext = {
                val bad = h.rows.firstOrNull { bwQty(it.fact) <= 0.0 }
                if (bad != null) error = "Укажите количество: ${bad.name.trim()}"
                else { error = ""; h.step = 1 }
            }
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactField(h.sender, { h.sender = it }, "Отправитель", Modifier.weight(1f))
            CompactField(h.truck, { h.truck = it }, "Номер машины", Modifier.weight(1f), caps = true)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactField(h.order, { h.order = it }, "Номер заказа", Modifier.weight(1f), caps = true)
            CompactField(h.trailer, { h.trailer = it }, "Номер прицепа", Modifier.weight(1f), caps = true)
        }
        Spacer(Modifier.height(6.dp))
        CatalogWheel(
            rows = h.rows,
            groups = BWareDetails.groups,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            errorText = error,
            onAdded = { error = "" }
        )
    }

    if (clearOpen) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("Очистить приём?") },
            text = { Text("Список товаров, отправитель, заказ и номера машины будут удалены.") },
            confirmButton = { Button(onClick = { h.clear(); clearOpen = false; error = "" }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("Отмена") } }
        )
    }
}

/** Окно «Далее»: предпросмотр документа и печать. */
@Composable
private fun BWarePreview(receiver: String) {
    val h = BWareHolder
    val ctx = LocalContext.current
    val stamp = remember { System.currentTimeMillis() }
    val html = bwareHtml(receiver, stamp)
    var clearOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { h.step = 0 }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text("Документ", style = MaterialTheme.typography.titleLarge)
        }
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
