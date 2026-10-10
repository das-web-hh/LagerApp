@file:OptIn(ExperimentalFoundationApi::class)

package ru.storage.app

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Пересчёт товаров «колесом» (барабаном):
 *  - список бесконечный: после последней строки — одна пустая, затем снова первая;
 *  - середина крупная (x1), верх и низ в два раза меньше (x0.5) и наклонены, как у вращающегося колеса;
 *  - поиск сверху фильтрует именно этот список с первого символа;
 *  - тап по строке = +1 к факту; тап по числу справа — барабан цифр 0…9999 (как в будильнике),
 *    через 3 секунды после остановки значение применяется само;
 *  - свайп справа налево открывает две кнопки: «Брак» (барабан цифр) и «Удалить»; свайп вправо закрывает;
 *  - долгое нажатие на строку — правка названия и плана.
 */

private val ROW_H = 66.dp
private val DIGIT_H = 46.dp
private const val CYCLE_BASE = 1_000_000

/** Наклон строк у краёв «колеса», градусы. Если наклон выглядит «наоборот» — поменять знак. */
private const val WHEEL_TILT = 38f

private fun parseQ(s: String): Double = normalizeQty(s).toDoubleOrNull() ?: 0.0

private fun fmtQ(d: Double): String =
    if (d == Math.floor(d) && d < 1e9) d.toLong().toString() else d.toString()

private fun matches(name: String, q: String): Boolean {
    val words = q.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val n = name.lowercase()
    return words.all { n.contains(it) }
}

/** Эффект колеса: чем дальше строка от середины, тем она меньше, бледнее и сильнее наклонена. */
private fun GraphicsLayerScope.applyWheel(
    state: LazyListState,
    index: Int,
    minScale: Float,
    minAlpha: Float,
    tilt: Float,
    pull: Float
) {
    val li = state.layoutInfo
    val info = li.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val half = (li.viewportEndOffset - li.viewportStartOffset) / 2f
    if (half <= 0f) return
    val center = (li.viewportStartOffset + li.viewportEndOffset) / 2f
    val d = ((info.offset + info.size / 2f - center) / half).coerceIn(-1f, 1f)
    val a = abs(d)
    val s = 1f - (1f - minScale) * a
    scaleX = s
    scaleY = s
    alpha = 1f - (1f - minAlpha) * a
    rotationX = -d * tilt
    translationY = -d * info.size * pull
    cameraDistance = 14f * density
}

@Composable
private fun ListSearchField(query: String, onChange: (String) -> Unit) {
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
                    "Поиск по списку",
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            TextButton(onClick = { onChange("") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("✕") }
        }
    }
}

/**
 * Список-колесо для приёма по имени. rows — строки приёма (ItemRow): qty = план, fact = пересчитано, defect = брак.
 * resetSearch — при росте значения поиск очищается (например, после добавления товара).
 */
@Composable
fun CountWheelList(
    rows: SnapshotStateList<ItemRow>,
    editable: Boolean,
    modifier: Modifier = Modifier,
    resetSearch: Int = 0
) {
    var query by remember { mutableStateOf("") }
    LaunchedEffect(resetSearch) { if (resetSearch > 0) query = "" }
    var picking by remember { mutableStateOf<Pair<ItemRow, Boolean>?>(null) } // true — факт, false — брак
    var editing by remember { mutableStateOf<ItemRow?>(null) }

    // список читается прямо здесь: изменился список или название — поиск и колесо обновятся
    val shown = rows.filter { matches(it.name, query) }
    val n = shown.size
    val cycle = n + 1

    Column(modifier) {
        ListSearchField(query) { query = it }
        Spacer(Modifier.height(8.dp))
        if (n == 0) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (rows.isEmpty()) "Список пуст" else "Ничего не найдено",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            key(n) {
                // старт: первая строка близко к середине, выше неё — пустая и последняя
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = (CYCLE_BASE / cycle) * cycle - 3
                )
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(count = Int.MAX_VALUE) { index ->
                        val row = shown.getOrNull(index % cycle)
                        if (row == null) {
                            Spacer(Modifier.fillMaxWidth().height(ROW_H))
                        } else {
                            WheelRow(
                                row = row,
                                index = index,
                                listState = listState,
                                editable = editable,
                                onPickFact = { picking = row to true },
                                onPickDefect = { picking = row to false },
                                onEdit = { editing = row },
                                onDelete = { rows.remove(row) }
                            )
                        }
                    }
                }
            }
        }
    }

    picking?.let { (row, isFact) ->
        CountPickerDialog(
            title = (if (isFact) "Факт: " else "Брак: ") + row.name,
            initial = parseQ(if (isFact) row.fact else row.defect).roundToInt().coerceIn(0, 9999),
            onApply = { v ->
                if (isFact) row.fact = v.toString() else row.defect = v.toString()
                picking = null
            },
            onDismiss = { picking = null }
        )
    }
    editing?.let { row -> RowEditDialog(row) { editing = null } }
}

@Composable
private fun WheelRow(
    row: ItemRow,
    index: Int,
    listState: LazyListState,
    editable: Boolean,
    onPickFact: () -> Unit,
    onPickDefect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val density = LocalDensity.current
    val btnW = 76.dp
    val maxPx = with(density) { (btnW * 2).toPx() }
    val anim = remember(row) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val factD = parseQ(row.fact)
    val planD = if (row.extra) 0.0 else parseQ(row.qty)
    val defD = parseQ(row.defect)

    Box(
        Modifier.fillMaxWidth()
            .height(ROW_H)
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .graphicsLayer { applyWheel(listState, index, 0.5f, 0.35f, WHEEL_TILT, 0.22f) }
    ) {
        // кнопки справа (открываются свайпом справа налево)
        Row(
            Modifier.align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(btnW * 2)
                .clip(RoundedCornerShape(14.dp))
        ) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(scheme.tertiaryContainer)
                    .clickable(enabled = editable) {
                        scope.launch { anim.animateTo(0f) }
                        onPickDefect()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (defD > 0) "Брак\n${fmtQ(defD)}" else "Брак",
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onTertiaryContainer
                )
            }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(scheme.errorContainer)
                    .clickable(enabled = editable) { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Удалить",
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onErrorContainer
                )
            }
        }

        // сама строка: свайп влево/вправо, тап = +1, долгое нажатие = правка
        Row(
            Modifier.offset { IntOffset(anim.value.roundToInt(), 0) }
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceVariant)
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = editable,
                    state = rememberDraggableState { delta ->
                        scope.launch { anim.snapTo((anim.value + delta).coerceIn(-maxPx, 0f)) }
                    },
                    onDragStopped = { velocity ->
                        scope.launch {
                            anim.animateTo(if (anim.value < -maxPx / 2f || velocity < -1200f) -maxPx else 0f)
                        }
                    }
                )
                .combinedClickable(
                    enabled = editable,
                    onClick = {
                        if (anim.value < -1f) scope.launch { anim.animateTo(0f) }
                        else row.fact = fmtQ(factD + 1)
                    },
                    onLongClick = onEdit
                )
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.name.ifBlank { "—" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium
                )
                val sub = buildList {
                    if (row.extra) add("нет в плане") else add("план ${fmtQ(planD)}")
                    if (defD > 0) add("брак ${fmtQ(defD)}")
                }.joinToString(" · ")
                Text(sub, fontSize = 12.sp, maxLines = 1, color = scheme.onSurfaceVariant)
            }
            // количество — справа; тап по числу открывает барабан цифр
            Box(
                Modifier.defaultMinSize(minWidth = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surface)
                    .clickable(enabled = editable) { onPickFact() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    fmtQ(factD),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        row.fact.isBlank() -> scheme.onSurfaceVariant
                        !row.extra && factD == planD -> scheme.primary
                        !row.extra && factD > planD -> scheme.error
                        else -> scheme.onSurface
                    }
                )
            }
        }
    }
}

/** Цифра в середине барабана. */
private fun selectedDigit(state: LazyListState, fallback: Int): Int {
    val li = state.layoutInfo
    val c = (li.viewportStartOffset + li.viewportEndOffset) / 2
    val best = li.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - c) } ?: return fallback
    return best.index % 10
}

@Composable
private fun DigitWheel(state: LazyListState) {
    Box(Modifier.width(60.dp).height(DIGIT_H * 5)) {
        Box(
            Modifier.align(Alignment.Center)
                .fillMaxWidth()
                .height(DIGIT_H)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
        )
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            modifier = Modifier.fillMaxSize()
        ) {
            items(count = Int.MAX_VALUE) { i ->
                Box(
                    Modifier.fillMaxWidth()
                        .height(DIGIT_H)
                        .graphicsLayer { applyWheel(state, i, 0.55f, 0.25f, 30f, 0.12f) },
                    contentAlignment = Alignment.Center
                ) {
                    Text((i % 10).toString(), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Барабан цифр как в будильнике: 4 колеса (0…9999). Значение применяется само через 3 секунды
 * после того, как все колёса остановились; кнопка «Готово» — сразу.
 */
@Composable
private fun CountPickerDialog(title: String, initial: Int, onApply: (Int) -> Unit, onDismiss: () -> Unit) {
    val digits = listOf(initial / 1000 % 10, initial / 100 % 10, initial / 10 % 10, initial % 10)
    val s0 = rememberLazyListState(initialFirstVisibleItemIndex = CYCLE_BASE + digits[0] - 2)
    val s1 = rememberLazyListState(initialFirstVisibleItemIndex = CYCLE_BASE + digits[1] - 2)
    val s2 = rememberLazyListState(initialFirstVisibleItemIndex = CYCLE_BASE + digits[2] - 2)
    val s3 = rememberLazyListState(initialFirstVisibleItemIndex = CYCLE_BASE + digits[3] - 2)
    val states = listOf(s0, s1, s2, s3)

    val value by remember {
        derivedStateOf {
            var v = 0
            for (i in 0..3) v = v * 10 + selectedDigit(states[i], digits[i])
            v
        }
    }
    val scrolling = states.any { it.isScrollInProgress }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) { if (scrolling) touched = true }
    LaunchedEffect(value, scrolling, touched) {
        if (touched && !scrolling) {
            delay(3000)
            onApply(value)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    states.forEach { DigitWheel(it) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Выбрано: $value · применится само через 3 секунды",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Button(onClick = { onApply(value) }) { Text("Готово") }
                }
            }
        }
    }
}

/** Правка названия и плана (долгое нажатие на строку): ошибки распознавания можно исправить. */
@Composable
private fun RowEditDialog(row: ItemRow, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(row.name) }
    var plan by remember { mutableStateOf(row.qty) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Правка строки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
                if (!row.extra) {
                    OutlinedTextField(
                        plan, { plan = it }, label = { Text("План (по документу)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    row.name = name.trim()
                    if (!row.extra) row.qty = plan
                    onDismiss()
                }
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
