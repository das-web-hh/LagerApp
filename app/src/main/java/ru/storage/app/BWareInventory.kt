package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/*
 * «Инвентарь B-Ware» — регистрация испорченного товара, который лежит на складе.
 * Как «Приём B-Ware», но без отправителя и номера заказа: поиск с «+», колесо-список (тап +1, барабан, вибрация, голос),
 * у каждой позиции срок годности, причины порчи и тип целостности. «Далее» — предпросмотр документа и печать.
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
}

private fun invHtml(receiver: String, now: Long): String {
    val rows = BWareInvHolder.rows
    val sb = StringBuilder()
    sb.append(
        "<html><head><meta charset='utf-8'><style>" +
            "body{font-family:sans-serif;font-size:12px;color:#111}" +
            "h1{font-size:20px;margin:0 0 8px}" +
            "table{border-collapse:collapse;width:100%}" +
            "th,td{border:1px solid #888;padding:4px 5px;text-align:left;vertical-align:top}" +
            "th{background:#eee}.n{text-align:right;white-space:nowrap}" +
            ".info td{border:none;padding:2px 10px 2px 0}.info td:first-child{color:#444}" +
            ".sub{color:#444;font-size:11px;margin-top:3px}" +
            ".sign{margin-top:36px}.sign td{border:none;padding:18px 10px 0 0;width:50%}" +
            ".line{border-top:1px solid #111;padding-top:2px;font-size:11px;color:#444}" +
            "</style></head><body>"
    )
    sb.append("<h1>Опись испорченного товара на складе (B-Ware)</h1>")
    sb.append("<table class='info'>")
    sb.append("<tr><td>Составил:</td><td>${bwEsc(bwDash(receiver))}</td></tr>")
    sb.append("<tr><td>Дата и время:</td><td>${fmtTime(now)}</td></tr>")
    sb.append("</table><br>")
    sb.append("<table><tr><th>№</th><th>Товар</th><th>Кол-во</th><th>Срок годности</th><th>Причины</th><th>Тип целостности</th></tr>")
    var total = 0.0
    rows.forEachIndexed { i, r ->
        val q = bwQty(r.fact)
        total += q
        sb.append("<tr><td>${i + 1}</td><td>${bwEsc(r.name.trim())}")
        if (r.note.isNotBlank()) sb.append("<div class='sub'>${bwEsc(r.note.trim()).replace("\n", "<br>")}</div>")
        sb.append("</td><td class='n'>${bwFmt(q)}</td><td>${bwEsc(bwDash(r.expiry))}</td>")
        sb.append("<td>${if (r.reasons.isEmpty()) "—" else r.reasons.joinToString("<br>") { "• " + bwEsc(it) }}</td>")
        sb.append("<td>${if (r.damage.isEmpty()) "—" else r.damage.joinToString("<br>") { "• " + bwEsc(it) }}</td></tr>")
    }
    sb.append("<tr><th></th><th>Итого</th><th class='n'>${bwFmt(total)}</th><th></th><th></th><th></th></tr></table>")
    sb.append("<table class='sign'><tr><td><div class='line'>Составил (подпись)</div></td>")
    sb.append("<td><div class='line'>Проверил (подпись)</div></td></tr></table>")
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
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { h.step = 0 }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
                Text("Документ", style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(8.dp))
            HtmlPreview(html, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { Printer.print(ctx, "Инвентарь_BWare_" + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.ROOT).format(java.util.Date(stamp)), html) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("🖨 Печать") }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { clearOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Закончить и очистить") }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Инвентарь B-Ware", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (h.rows.isNotEmpty()) TextButton(onClick = { clearOpen = true }) { Text("Очистить") }
                Button(
                    enabled = h.rows.isNotEmpty(),
                    onClick = {
                        val bad = h.rows.firstOrNull { bwQty(it.fact) <= 0.0 }
                        if (bad != null) error = "Укажите количество: ${bad.name.trim()}"
                        else { error = ""; h.step = 1 }
                    }
                ) { Text("Далее") }
            }
            Spacer(Modifier.height(8.dp))
            CatalogWheel(
                rows = h.rows,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                errorText = error,
                emptyText = "Введите название товара и выберите из подсказок\nили нажмите «+»",
                itemDetails = true,
                onAdded = { error = "" }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Свайп влево по строке: срок годности, причины и вид, удалить.",
                style = MaterialTheme.typography.bodySmall
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
