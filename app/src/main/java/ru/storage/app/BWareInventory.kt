package ru.storage.app

import androidx.activity.compose.BackHandler
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
    /** 0 — список, 1 — предпросмотр и печать */
    var step by mutableIntStateOf(0)

    fun clear() {
        rows.clear()
        step = 0
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
    val groups: List<Pair<String, List<String>>> = listOf(
        "Причина порчи" to reasons,
        "Тип целостности" to damage
    )
}

private fun bullets(list: List<String>) =
    if (list.isEmpty()) "—" else list.joinToString("<br>") { "• " + bwEsc(trOpt(it)) }

private fun invHtml(receiver: String, now: Long): String {
    val sb = StringBuilder()
    sb.append("<html><head><meta charset='utf-8'><style>$BW_CSS.sub{color:#444;font-size:11px;margin-top:3px}</style></head><body>")
    sb.append("<h1>${trHead("inv_title")}</h1>")
    sb.append("<table class='info'>")
    sb.append("<tr><td>${tr("compiled_by")}:</td><td>${bwEsc(bwDash(receiver))}</td></tr>")
    sb.append("<tr><td>${tr("date_time")}:</td><td>${fmtTime(now)}</td></tr>")
    sb.append("</table><br>")
    sb.append("<table><tr><th>${trb("th_no")}</th><th>${trb("th_item")}</th><th>${trb("th_qty")}</th><th>${trb("th_expiry")}</th><th>${trb("th_reasons")}</th><th>${trb("th_integrity")}</th></tr>")
    var total = 0.0
    BWareInvHolder.rows.forEachIndexed { i, r ->
        val q = bwQty(r.fact)
        total += q
        sb.append("<tr><td>${i + 1}</td><td>${bwEsc(r.name.trim())}")
        if (r.note.isNotBlank()) sb.append("<div class='sub'>${bwEsc(r.note.trim()).replace("\n", "<br>")}</div>")
        sb.append("</td><td class='n'>${bwFmt(q)}</td><td>${bwEsc(bwDash(r.expiry))}</td>")
        sb.append("<td>${bullets(InvOptions.reasons.filter { it in r.reasons })}</td>")
        sb.append("<td>${bullets(InvOptions.damage.filter { it in r.reasons })}</td></tr>")
    }
    sb.append("<tr><th></th><th>${trb("total")}</th><th class='n'>${bwFmt(total)}</th><th></th><th></th><th></th></tr></table>")
    sb.append("<table class='sign'><tr><td>${Signature.cell("sig_compiled", receiver, now)}</td>")
    sb.append("<td>${Signature.cell("sig_checked", "", now, auto = false)}</td></tr></table>")
    sb.append("</body></html>")
    return sb.toString()
}

@Composable
fun BWareInventoryScreen(receiver: String, onBack: () -> Unit) {
    val h = BWareInvHolder
    BackHandler { if (h.step == 1) h.step = 0 else onBack() }
    val ctx = LocalContext.current
    var clearOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    if (h.step == 1) {
        val stamp = remember { System.currentTimeMillis() }
        val html = invHtml(receiver, stamp)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { h.step = 0 }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
                Text("Документ", style = MaterialTheme.typography.titleLarge)
            }
            HtmlPreview(html, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val d = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date(stamp))
                    Printer.print(ctx, "Инвентарь_BWare_$d", html)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("🖨 Печать") }
            TextButton(onClick = { clearOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Закончить и очистить") }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            BWareHeader(
                title = "Инвентарь B-Ware",
                canClear = h.rows.isNotEmpty(),
                canNext = h.rows.isNotEmpty(),
                onClear = { clearOpen = true },
                onNext = {
                    val bad = h.rows.firstOrNull { bwQty(it.fact) <= 0.0 }
                    if (bad != null) error = "Укажите количество: ${bad.name.trim()}"
                    else { error = ""; h.step = 1 }
                }
            )
            Spacer(Modifier.height(4.dp))
            CatalogWheel(
                rows = h.rows,
                groups = InvOptions.groups,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                errorText = error,
                onAdded = { error = "" }
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
