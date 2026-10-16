package ru.storage.app

import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /** Код приёма: он в QR на документе и в имени каждого фото (BW-КОД-….jpg). Пусто, пока не нужен. */
    var code by mutableStateOf("")
    /** Имена файлов фото испорченного товара этого приёма (до BW_MAX_PHOTOS). */
    val photos = mutableStateListOf<String>()

    /** PDF этого приёма на Google Диске: id файла и ссылка из QR-кода (создаются при первой печати). */
    var pdfId by mutableStateOf("")
    var pdfUrl by mutableStateOf("")

    fun ensureCode(): String {
        if (code.isBlank()) code = newBwCode()
        return code
    }

    /** Очистка не удаляет файлы фото: QR на уже напечатанном документе должен продолжать открывать их. */
    fun clear() {
        sender = ""
        order = ""
        truck = ""
        trailer = ""
        rows.clear()
        photos.clear()
        pdfId = ""
        pdfUrl = ""
        code = ""
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
private fun bwareHtml(receiver: String, now: Long, qrPayload: String, photosHtml: String = ""): String {
    val h = BWareHolder
    // слева — только значения, как в образце; пустое значение — «–»
    val info = DocLayout.info(
        listOf(
            "" to bwDash(h.sender),
            "" to bwDash(h.order),
            "" to bwDash(h.truck).uppercase(),
            "" to bwDash(h.trailer).uppercase(),
            "" to bwDash(receiver),
            "" to fmtTime(now)
        )
    )
    val top = DocLayout.top(info, DocLayout.title("bw_t1", "bw_t2"))
    val sb = StringBuilder()
    sb.append("<table class='dt'><tr><th></th><th>${trb("th_item")}</th><th class='n'>${trb("th_qty")}</th>")
    sb.append("<th>${trb("th_expiry")}</th><th>${trb("th_details")}</th></tr>")
    var total = 0.0
    h.rows.forEachIndexed { i, r ->
        val q = bwQty(r.fact)
        total += q
        sb.append("<tr><td>${i + 1}.</td><td>${bwEsc(r.name.trim())}</td><td class='n'>${bwFmt(q)}</td>")
        sb.append("<td>${bwEsc(bwDash(r.expiry))}</td><td>${bwDetailsHtml(r, BWareDetails.groups)}</td></tr>")
    }
    sb.append("<tr class='tot'><th></th><th>${trb("total")}</th><th class='n'>${bwFmt(total)}</th><th></th><th></th></tr></table>")
    val qr = if (qrPayload.isBlank()) "" else DocLayout.qrBlock(qrPayload)
    val bottom = (if (qr.isEmpty()) "<div></div>" else qr) + DocLayout.signBlock(receiver, now)
    return DocLayout.page(top, sb.toString(), bottom, after = photosHtml)
}

private val bwBg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun bwToast(ctx: Context, text: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show()
    }
}

/**
 * Кнопка «Печать»: 1) на Диске резервируется PDF со ссылкой «по ссылке», она идёт в QR-код;
 * 2) на печать уходит документ БЕЗ фото; 3) в фоне тот же документ + фото одним PDF заменяет заготовку на Диске.
 * Если Диск не настроен или недоступен — документ всё равно печатается (QR по старой схеме, внутри приложения).
 */
private suspend fun bwPrintAndSave(ctx: Context, jobName: String, receiver: String, stamp: Long) {
    val h = BWareHolder
    val app = ctx.applicationContext
    val code = h.ensureCode()
    var driveError = ""
    if (!DriveCfg.ready(app)) {
        driveError = "Google Диск не настроен (Профиль → Настройки)"
    } else if (h.pdfId.isBlank()) {
        try {
            val slot = withContext(Dispatchers.IO) { DriveApi.reservePdf(app, "BWDOC-$code.pdf") }
            h.pdfId = slot.id
            h.pdfUrl = slot.downloadUrl
        } catch (e: Exception) {
            driveError = e.message ?: e.javaClass.simpleName
        }
    }
    val driveId = h.pdfId
    val qr = h.pdfUrl.ifBlank { BW_QR_PREFIX + code }
    // оба документа собираются сейчас, пока приём не очищен: печать — без фото, PDF для Диска — с фото
    val printHtml = bwareHtml(receiver, stamp, qr)
    val driveHtml = if (driveId.isNotBlank()) bwareHtml(receiver, stamp, qr, bwPhotosHtml(app, h.photos.toList())) else ""

    Printer.print(ctx, jobName, printHtml)

    if (driveId.isBlank()) {
        bwToast(app, "На Диск не сохранено: $driveError")
        return
    }
    bwBg.launch {
        try {
            val pdf = HtmlPdf.render(app, driveHtml) ?: throw RuntimeException("не удалось создать PDF")
            withContext(Dispatchers.IO) { DriveApi.finalizePdf(app, driveId, pdf) }
            bwToast(app, "PDF с фото сохранён на Google Диск")
        } catch (e: Exception) {
            bwToast(app, "На Диск не сохранено: ${e.message ?: e.javaClass.simpleName}")
        }
    }
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
                h.truck.isNotBlank() || h.trailer.isNotBlank() || h.photos.isNotEmpty(),
            canNext = h.rows.isNotEmpty(),
            onClear = { clearOpen = true },
            onNext = {
                val bad = h.rows.firstOrNull { bwQty(it.fact) <= 0.0 }
                if (bad != null) error = "Укажите количество: ${bad.name.trim()}"
                else { error = ""; h.ensureCode(); h.step = 1 }
            }
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactField(h.sender, { h.sender = it }, "Отправитель", Modifier.weight(1f))
            CompactField(h.order, { h.order = it }, "Номер заказа", Modifier.weight(1f), caps = true)
        }
        Spacer(Modifier.height(4.dp))
        PlateField(h.truck, { h.truck = it }, "Номер машины", Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        PlateField(h.trailer, { h.trailer = it }, "Номер прицепа", Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        BWarePhotoButton()
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
            text = { Text("Список товаров, отправитель, заказ, номера машины и фото будут убраны из окна. Уже сохранённые фото останутся доступны по QR-коду напечатанного документа.") },
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
    // в предпросмотре QR того же размера; настоящая ссылка на PDF создаётся при нажатии «Печать»
    val html = bwareHtml(receiver, stamp, h.pdfUrl.ifBlank { BW_QR_PREFIX + h.ensureCode() })
    var clearOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { h.step = 0 }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text("Документ", style = MaterialTheme.typography.titleLarge)
        }
        HtmlPreview(html, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy,
            onClick = {
                val name = "BWare_" + (h.order.ifBlank { h.sender }.ifBlank { "приём" })
                    .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
                busy = true
                scope.launch {
                    try {
                        bwPrintAndSave(ctx, name, receiver, stamp)
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Подготовка…" else "🖨 Печать и сохранить на Диск") }
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
