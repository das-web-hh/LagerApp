package ru.storage.app

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * «Инвентарь B-Ware» — регистрация испорченного товара на складе. Как «Приём B-Ware», но без отправителя,
 * заказа и машины: поиск «Товар» с «+», обычный список (тап +1, барабан цифр, вибрация, голос),
 * свайп вправо — срок годности, удержание строки — причины порчи и тип целостности.
 * «Далее» — документ «Опись» и печать.
 */

object BWareInvHolder {
    val rows = mutableStateListOf<ItemRow>()
    /** Склад / адрес, где проводится инвентаризация (вводится слева над поиском). */
    var warehouse by mutableStateOf("")
    /** 0 — список, 1 — предпросмотр и печать */
    var step by mutableIntStateOf(0)

    fun clear() {
        rows.clear()
        warehouse = ""
        step = 0
        BWareDraft.delete("inv")
    }
}

/** Варианты для каждой позиции инвентаря. */
object InvOptions {
    val reasons = listOf(
        "Истёк срок годности",
        "Испортился при перевозке",
        "Испорчен при хранении на складе",
        "Упал при разгрузке или работе с паллетом",
        "Подмок, залит водой",
        "Заморозка или перегрев",
        "Плесень или посторонний запах",
        "Вредители",
        "Грызуны (мыши, крысы) повредили товар",
        "Личинки или черви в товаре",
        "Моль (из личинок)",
        "Насекомые (жуки, тараканы)",
        "Протечка крыши или сырость на складе",
        "Слежался или затвердел от влаги",
        "Испорчен солнцем (стоял на солнце)",
        "Намок от дождя (стоял на улице)",
        "Повреждён гвоздями или щепой из поддона",
        "Проколот или порезан погрузчиком",
        "Раздавлен при штабелировании",
        "Загрязнён маслом или химией",
        "Заводской брак",
        "Причина неизвестна"
    )
    val damage = listOf(
        "Упаковка порвана",
        "Упаковка вскрыта",
        "Товар рассыпан",
        "Содержимое осталось внутри упаковки",
        "Содержимое вытекло или высыпалось наружу",
        "Коробка помята",
        "Коробка мокрая",
        "Крышка или пробка открыта",
        "Стекло или банка разбита, треснула",
        "Раздавлен или деформирован",
        "Этикетка повреждена или отсутствует",
        "Загрязнён"
    )
    /** Галочка «утилизировано»: попадает на документ колонкой и строкой под таблицей. */
    const val DISPOSED = "Утилизировано (выброшено в мусорный бак)"
    val groups: List<Pair<String, List<String>>> = listOf(
        "Причина порчи" to reasons,
        "Тип целостности" to damage,
        "Утилизация" to listOf(DISPOSED)
    )
}

private fun bullets(list: List<String>) =
    if (list.isEmpty()) "—" else list.joinToString("<br>") { "• " + bwEsc(trOpt(it)) }

private fun invHtml(receiver: String, now: Long, photosHtml: String = "", qrHtml: String = ""): String {
    val info = DocLayout.info(
        listOf(
            tr("compiled_by") to bwDash(receiver),
            tr("date_time") to fmtTime(now)
        ) + (if (BWareInvHolder.warehouse.isNotBlank()) listOf("Lager / Склад" to BWareInvHolder.warehouse.trim()) else emptyList())
    )
    val top = DocLayout.top(info, DocLayout.title("inv_t1", "inv_t2"))
    val sb = StringBuilder()
    sb.append("<table class='dt'><tr><th>${trb("th_no")}</th><th>${trb("th_item")}</th><th class='n'>${trb("th_qty")}</th><th>${trb("th_expiry")}</th><th>${trb("th_reasons")}</th><th>${trb("th_integrity")}</th><th>${trb("th_disposal")}</th></tr>")
    var total = 0.0
    var anyDisposed = false
    BWareInvHolder.rows.forEachIndexed { i, r ->
        val q = bwQty(r.fact)
        total += q
        sb.append("<tr><td>${i + 1}.</td><td>${bwEsc(r.name.trim())}")
        if (r.note.isNotBlank()) sb.append("<div class='sub'>${bwEsc(r.note.trim()).replace("\n", "<br>")}</div>")
        sb.append("</td><td class='n'>${bwFmt(q)}</td><td>${bwEsc(bwDash(r.expiry))}</td>")
        sb.append("<td>${bullets(InvOptions.reasons.filter { it in r.reasons })}</td>")
        sb.append("<td>${bullets(InvOptions.damage.filter { it in r.reasons })}</td>")
        val disposed = InvOptions.DISPOSED in r.reasons
        if (disposed) anyDisposed = true
        sb.append("<td>${if (disposed) "✓ " + bwEsc(tr("disposed_short")) else "—"}</td></tr>")
    }
    sb.append("<tr class='tot'><th></th><th>${trb("total")}</th><th class='n'>${bwFmt(total)}</th><th></th><th></th><th></th><th></th></tr></table>")
    if (anyDisposed) sb.append("<div class='sub' style='margin-top:10px;font-size:12px'>${bwEsc(tr("disposed_note"))}</div>")
    val bottom = "<div class='sg'>${Signature.cell("sig_compiled", receiver, now)}</div>" +
        "<div class='sg'>${Signature.cell("sig_checked", "", now, auto = false)}</div>"
    return DocLayout.page(top, sb.toString(), (if (qrHtml.isNotBlank()) "<div>$qrHtml</div>" else "") + bottom, after = photosHtml)
}

/** CSV для Excel: разделитель «;», UTF-8 с BOM. */
private fun invCsv(now: Long): String {
    fun q(v: String) = "\"" + v.replace("\"", "\"\"").replace('\n', ' ') + "\""
    val sb = StringBuilder("\uFEFF")
    sb.append("Склад;").append(q(BWareInvHolder.warehouse.trim())).append(";;;;;;;\n")
    sb.append("Дата;").append(q(fmtTime(now))).append(";;;;;;;\n")
    sb.append("№;Товар;Штрих-код;Количество;Срок годности;Причины порчи;Тип целостности;Утилизировано;Примечание\n")
    BWareInvHolder.rows.forEachIndexed { i, r ->
        sb.append(i + 1).append(';')
            .append(q(r.name.trim())).append(';')
            .append(q(r.barcode)).append(';')
            .append(bwFmt(bwQty(r.fact))).append(';')
            .append(q(r.expiry)).append(';')
            .append(q(InvOptions.reasons.filter { it in r.reasons }.joinToString(", "))).append(';')
            .append(q(InvOptions.damage.filter { it in r.reasons }.joinToString(", "))).append(';')
            .append(if (InvOptions.DISPOSED in r.reasons) "да" else "нет").append(';')
            .append(q(r.note.trim())).append('\n')
    }
    return sb.toString()
}

@Composable
fun BWareInventoryScreen(receiver: String, onBack: () -> Unit) {
    val h = BWareInvHolder
    BackHandler { if (h.step == 1) h.step = 0 else onBack() }
    BWareDraftEffect("inv")   // состояние сохраняется на телефоне и в облаке: не пропадёт ни при выходе, ни при переустановке
    val ctx = LocalContext.current
    var clearOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    if (h.step == 1) {
        val stamp = remember { System.currentTimeMillis() }
        val photoNames = h.rows.map { it.photo }.filter { it.isNotBlank() }
        val photosHtml by produceState("", photoNames) { value = bwPhotosHtml(ctx, photoNames) }
        val qrNumber = remember { "INV" + SimpleDateFormat("yyMMddHHmm", Locale.ROOT).format(Date(stamp)) }
        val qrHtml by produceState("", photoNames) { value = PhotoQr.html(ctx, qrNumber, photoNames) }
        val html = invHtml(receiver, stamp, photosHtml, qrHtml)
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        val fileBase = "Инвентарь_BWare_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date(stamp))
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { h.step = 0 }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
                Text("Документ", style = MaterialTheme.typography.titleLarge)
            }
            HtmlPreview(html, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // «Сохранить»: копия в «Документы» + PDF на Google Диск (если настроен)
                Button(
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true
                        val app = ctx.applicationContext
                        DocStore.recordPrinted(ctx, fileBase, html)
                        scope.launch {
                            try {
                                if (!DriveCfg.ready(app)) {
                                    Toast.makeText(app, "Сохранено в «Документы». На Диск не отправлено: Google Диск не настроен", Toast.LENGTH_LONG).show()
                                } else {
                                    val pdf = withContext(Dispatchers.IO) { HtmlPdf.render(app, html) }
                                    if (pdf == null) throw RuntimeException("не удалось создать PDF")
                                    val name = "$fileBase.pdf"
                                    withContext(Dispatchers.IO) {
                                        try { DriveApi.delete(app, name) } catch (e: Exception) { }
                                        DriveApi.uploadBytes(app, name, "application/pdf", pdf)
                                    }
                                    Toast.makeText(app, "Сохранено: «Документы» и Google Диск", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(app, "На Диск не сохранено: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "Сохранение…" else "Сохранить") }
                // «Экспортировать»: таблица CSV (открывается в Excel) — отправить куда угодно
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        try {
                            val dir = File(ctx.cacheDir, "export").also { it.mkdirs() }
                            val f = File(dir, "$fileBase.csv")
                            f.writeText(invCsv(stamp), Charsets.UTF_8)
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                            val i = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            ctx.startActivity(Intent.createChooser(i, "Экспорт инвентаря"))
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Не удалось экспортировать: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text("Экспортировать") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { Printer.print(ctx, fileBase, html) }) { Text("🖨 Печать") }
                TextButton(onClick = { clearOpen = true }) { Text("Закончить и очистить") }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            // сверху: слева склад/адрес инвентаризации, справа «Очистить» и «Далее»; ниже — поиск
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactField(
                    value = h.warehouse,
                    onChange = { h.warehouse = germanKeys(it) },
                    hint = "Склад / адрес",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                if (h.rows.isNotEmpty() || h.warehouse.isNotBlank()) {
                    TextButton(onClick = { clearOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Очистить") }
                }
                Button(
                    onClick = {
                        val bad = h.rows.firstOrNull { bwQty(it.fact) <= 0.0 }
                        if (bad != null) error = "Укажите количество: ${bad.name.trim()}"
                        else { error = ""; h.step = 1 }
                    },
                    enabled = h.rows.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("Далее") }
            }
            Spacer(Modifier.height(6.dp))
            CatalogWheel(
                rows = h.rows,
                groups = InvOptions.groups,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                errorText = error,
                onAdded = { error = "" },
                scanner = true
            )
        }
    }

    if (clearOpen) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            title = { Text("Очистить список?") },
            text = { Text("Все товары и выбранные причины будут удалены.") },
            confirmButton = { Button(onClick = { h.clear(); clearOpen = false; error = "" }) { Text("Очистить") } },
            dismissButton = { TextButton(onClick = { clearOpen = false }) { Text("Отмена") } }
        )
    }
}
