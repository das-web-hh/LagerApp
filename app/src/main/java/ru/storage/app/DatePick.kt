@file:OptIn(ExperimentalFoundationApi::class)

package ru.storage.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import android.content.Context
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/*
 * Окно выбора даты (срок годности и любые другие даты в приложении):
 *  - сверху годы — горизонтальный барабан (прокрутка влево-вправо, год посередине выбран);
 *  - слева 12 месяцев — вертикальный барабан;
 *  - основная часть — дни месяца по неделям, как на календаре (с понедельника).
 * Результат — строка «дд.мм.гггг»; кнопка «Очистить» возвращает пустую строку.
 */

/**
 * Настройка «Выбор срока годности»: false — обычный календарь (месяцы названиями, дни по реальным неделям);
 * true — упрощённый: год по умолчанию 2027, месяцы цифрами в два столбца (01–06 и 07–12), дни 1–31 всегда с понедельника
 * и не сдвигаются при смене месяца/года; выбрали только месяц и год — день ставится первым.
 */
object DateCfg {
    var simple by mutableStateOf(false)
    const val DEFAULT_YEAR = 2027

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("date_cfg", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        simple = sp(ctx).getBoolean("simple", false)
    }

    fun set(ctx: Context, on: Boolean) {
        simple = on
        sp(ctx).edit().putBoolean("simple", on).apply()
    }

    fun applyCloud(ctx: Context, on: Boolean?) {
        if (on != null) set(ctx, on)
    }
}

private val MONTH_NAMES = listOf(
    "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
)
private val WEEK_DAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

private val YEAR_W = 76.dp
private val MONTH_H = 44.dp
private val GRID_H = 260.dp

/** Индекс элемента, ближайшего к середине списка (работает и для строки, и для столбца). */
private fun centerIndex(state: LazyListState): Int? {
    val li = state.layoutInfo
    val c = (li.viewportStartOffset + li.viewportEndOffset) / 2
    return li.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - c) }?.index
}

@Composable
fun ExpiryPickerDialog(initial: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember { Calendar.getInstance() }
    val parsed = remember(initial) { Regex("""^(\d{2})\.(\d{2})\.(\d{4})$""").find(initial.trim()) }
    val simple = remember { DateCfg.simple }
    val startYear = parsed?.groupValues?.get(3)?.toInt() ?: if (simple) DateCfg.DEFAULT_YEAR else today.get(Calendar.YEAR)
    val startMonth = (parsed?.groupValues?.get(2)?.toInt() ?: (today.get(Calendar.MONTH) + 1)).coerceIn(1, 12)
    // упрощённый вид: если день не выбирали, дата — первое число месяца
    val startDay = parsed?.groupValues?.get(1)?.toInt() ?: if (simple) 1 else 0

    var year by remember { mutableIntStateOf(startYear) }
    var month by remember { mutableIntStateOf(startMonth) }
    var day by remember { mutableIntStateOf(startDay) }

    val years = remember {
        val y0 = minOf(today.get(Calendar.YEAR) - 1, startYear)
        val y1 = maxOf(today.get(Calendar.YEAR) + 15, startYear)
        (y0..y1).toList()
    }

    // дни выбранного месяца
    val first = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
    val offset = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7 // неделя с понедельника
    val dim = first.getActualMaximum(Calendar.DAY_OF_MONTH)
    val selDay = if (day > dim) dim else day

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(vertical = 16.dp, horizontal = 12.dp)) {
                Text("Выберите дату", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(8.dp))

                // годы — барабан слева направо
                BoxWithConstraints(Modifier.fillMaxWidth().height(52.dp)) {
                    val pad = (maxWidth - YEAR_W) / 2
                    val st = rememberLazyListState(initialFirstVisibleItemIndex = years.indexOf(year).coerceAtLeast(0))
                    LaunchedEffect(Unit) {
                        snapshotFlow { centerIndex(st) }.collect { i ->
                            val y = i?.let { years.getOrNull(it) }
                            if (y != null && y != year) {
                                year = y
                                Haptics.pulse(ctx, Haptics.PICKER)
                            }
                        }
                    }
                    Box(
                        Modifier.align(Alignment.Center).width(YEAR_W).fillMaxHeight().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    )
                    LazyRow(
                        state = st,
                        flingBehavior = rememberSnapFlingBehavior(st),
                        contentPadding = PaddingValues(horizontal = pad),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(years) { i, y ->
                            Box(
                                Modifier.width(YEAR_W).fillMaxHeight()
                                    .clickable { scope.launch { st.animateScrollToItem(i) } },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    y.toString(),
                                    fontSize = if (y == year) 22.sp else 16.sp,
                                    fontWeight = if (y == year) FontWeight.Bold else FontWeight.Normal,
                                    color = if (y == year) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth()) {
                  if (simple) {
                    // месяцы цифрами в два столбца: 01–06 и 07–12
                    Row(Modifier.width(112.dp).height(GRID_H), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1..6, 7..12).forEach { col ->
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                col.forEach { m ->
                                    val sel = m == month
                                    Box(
                                        Modifier.fillMaxWidth().weight(1f).padding(vertical = 2.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .then(if (sel) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                                            .clickable {
                                                month = m
                                                Haptics.pulse(ctx, Haptics.PICKER)
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "%02d".format(m),
                                            fontSize = if (sel) 20.sp else 17.sp,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                  } else {
                    // месяцы — столбик-барабан слева
                    Box(Modifier.width(108.dp).height(GRID_H)) {
                        val st = rememberLazyListState(initialFirstVisibleItemIndex = month - 1)
                        LaunchedEffect(Unit) {
                            snapshotFlow { centerIndex(st) }.collect { i ->
                                if (i != null && i in 0..11 && i + 1 != month) {
                                    month = i + 1
                                    Haptics.pulse(ctx, Haptics.PICKER)
                                }
                            }
                        }
                        Box(
                            Modifier.align(Alignment.Center).fillMaxWidth().height(MONTH_H)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        )
                        LazyColumn(
                            state = st,
                            flingBehavior = rememberSnapFlingBehavior(st),
                            contentPadding = PaddingValues(vertical = (GRID_H - MONTH_H) / 2),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(MONTH_NAMES) { i, name ->
                                val sel = i + 1 == month
                                Box(
                                    Modifier.fillMaxWidth().height(MONTH_H)
                                        .clickable { scope.launch { st.animateScrollToItem(i) } },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "%02d %s".format(i + 1, name),
                                        fontSize = if (sel) 16.sp else 13.sp,
                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                  }
                    Spacer(Modifier.width(8.dp))
                    // дни по неделям
                    Column(Modifier.weight(1f).height(GRID_H)) {
                        Row(Modifier.fillMaxWidth().height(20.dp)) {
                            WEEK_DAYS.forEach {
                                Text(
                                    it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        repeat(if (simple) 5 else 6) { w ->
                            Row(Modifier.fillMaxWidth().height(40.dp)) {
                                repeat(7) { c ->
                                    // упрощённый вид: 1–31 всегда с понедельника, сетка не зависит от месяца и года
                                    val dn = if (simple) w * 7 + c + 1 else w * 7 + c - offset + 1
                                    Box(Modifier.weight(1f).fillMaxHeight().padding(1.dp), contentAlignment = Alignment.Center) {
                                        if (dn in 1..(if (simple) 31 else dim)) {
                                            val inMonth = dn <= dim
                                            val sel = dn == selDay
                                            val isToday = dn == today.get(Calendar.DAY_OF_MONTH) &&
                                                month == today.get(Calendar.MONTH) + 1 &&
                                                year == today.get(Calendar.YEAR)
                                            Box(
                                                Modifier.fillMaxSize().clip(CircleShape)
                                                    .then(
                                                        if (sel) Modifier.background(MaterialTheme.colorScheme.primary)
                                                        else if (isToday) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                                        else Modifier
                                                    )
                                                    .clickable(enabled = inMonth) {
                                                        day = dn
                                                        Haptics.pulse(ctx, Haptics.TAP)
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    dn.toString(),
                                                    fontSize = 14.sp,
                                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (sel) MaterialTheme.colorScheme.onPrimary
                                                    else if (!inMonth) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                                    else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    if (selDay > 0) "Выбрано: " + String.format(Locale.ROOT, "%02d.%02d.%04d", selDay, month, year)
                    else "Выберите день",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onPick("") }) { Text("Очистить") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Button(
                        enabled = selDay > 0,
                        onClick = { onPick(String.format(Locale.ROOT, "%02d.%02d.%04d", selDay, month, year)) }
                    ) { Text("Готово") }
                }
            }
        }
    }
}
