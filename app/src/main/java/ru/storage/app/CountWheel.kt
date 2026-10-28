@file:OptIn(ExperimentalFoundationApi::class, kotlinx.coroutines.FlowPreview::class)

package ru.storage.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import android.content.Context
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import kotlin.math.abs
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Пересчёт товаров «колесом» (барабаном):
 *  - список бесконечный: после последней строки — одна пустая, затем снова первая;
 *  - середина крупная (x1), верх и низ в два раза меньше (x0.5) и наклонены, как у вращающегося колеса;
 *  - поиск сверху ищет по этому списку с первого символа и показывает подсказки под полем;
 *    тап по подсказке прокручивает колесо так, что товар оказывается посередине; «+» в поле — добавить товар;
 *  - тап по строке = +1 к факту; тап по числу справа — барабан цифр 0…9999 (как в будильнике),
 *    (одно колесо 0…9999) через 3 секунды после остановки значение применяется само;
 *  - после тапа и после выбора барабаном крупная цифра плавно появляется/гаснет, затем голос называет число (Настройки → Голос и подсчёт);
 *  - свайп справа налево открывает две кнопки: «Брак» (барабан цифр) и «Удалить»; свайп вправо закрывает;
 *  - долгое нажатие на строку — правка названия и плана.
 */

private val ROW_H = 76.dp
/** Приём по имени: компактная строка в одну линию (было 114 dp). Единственное число — меняется здесь. */
private val ROW_H_BIG = 81.dp  // строка посередине; остальные в 1,5 раза ниже (54 dp)

private val DIGIT_H = 46.dp
private const val CYCLE_BASE = 1_000_000

/** Наклон строк у краёв «колеса», градусы. Если наклон выглядит «наоборот» — поменять знак. */
private const val WHEEL_TILT = 12f

internal fun parseQ(s: String): Double = normalizeQty(s).toDoubleOrNull() ?: 0.0

internal fun fmtQ(d: Double): String =
    if (d == Math.floor(d) && d < 1e9) d.toLong().toString() else d.toString()

/** Строки, подходящие под запрос (все слова запроса есть в названии); сначала те, что начинаются с запроса. */
private fun findRows(rows: List<ItemRow>, q: String): List<ItemRow> {
    val words = foldDe(q.trim()).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val w0 = words[0]
    return rows
        .filter { r ->
            val n = foldDe(r.name)
            val bc = r.barcode.lowercase()
            // слово запроса есть в названии или в штрих-коде строки
            words.all { n.contains(it) || (bc.isNotEmpty() && bc.contains(it)) }
        }
        .sortedBy { r ->
            val n = foldDe(r.name)
            when {
                r.barcode.isNotEmpty() && r.barcode.lowercase().startsWith(w0) -> 0
                n.startsWith(w0) -> 0
                n.split(' ', ',', '-', '/', '(', ')').any { it.startsWith(w0) } -> 1
                else -> 2
            }
        }
}

/** Строка, которая сейчас ближе всего к середине колеса (пустая строка цикла — null). */
private fun centerRow(state: LazyListState, rows: List<ItemRow>, cycle: Int): ItemRow? {
    val li = state.layoutInfo
    if (li.viewportSize.height == 0) return null
    val c = (li.viewportStartOffset + li.viewportEndOffset) / 2
    val best = li.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - c) } ?: return null
    return rows.getOrNull(best.index % cycle)
}

/** Прокрутить колесо так, чтобы строка k оказалась посередине (по кратчайшему пути по циклу). */
private suspend fun scrollToRow(state: LazyListState, k: Int, cycle: Int, rowPx: Int, animate: Boolean) {
    var tries = 0
    while (state.layoutInfo.viewportSize.height == 0 && tries < 20) {
        withFrameNanos { }
        tries++
    }
    val li = state.layoutInfo
    val h = li.viewportSize.height
    val c = (li.viewportStartOffset + li.viewportEndOffset) / 2
    val cur = li.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - c) }?.index
        ?: state.firstVisibleItemIndex
    val base = cur - Math.floorMod(cur - k, cycle)
    val t = if (cur - base <= base + cycle - cur) base else base + cycle
    val off = -(h - rowPx) / 2
    if (animate) state.animateScrollToItem(t, off) else state.scrollToItem(t, off)
}

/** Ждёт, пока высота списка перестанет меняться (закрылась/открылась клавиатура), иначе прокрутка собьётся. */
private suspend fun awaitStableViewport(state: LazyListState) {
    snapshotFlow { state.layoutInfo.viewportSize.height }.debounce(180).first()
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

/**
 * Колесо списка товаров: средняя строка в полном размере, остальные — вдвое меньше (середина в 2 раза крупнее и выше),
 * дальше от середины — бледнее и сильнее наклонены.
 */
private fun GraphicsLayerScope.applyBigCenter(state: LazyListState, index: Int, tilt: Float) {
    val li = state.layoutInfo
    val info = li.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val half = (li.viewportEndOffset - li.viewportStartOffset) / 2f
    if (half <= 0f) return
    val center = (li.viewportStartOffset + li.viewportEndOffset) / 2f
    val dPx = info.offset + info.size / 2f - center
    val ak = abs(dPx / info.size)
    // ширина у всех строк одинаковая (без масштаба); размер выбранной строки задаёт её высота (в 1,5 раза больше)
    alpha = if (ak <= 1f) 1f - 0.3f * ak else (0.7f - 0.2f * (ak - 1f)).coerceAtLeast(0.3f)
    rotationX = -(dPx / half).coerceIn(-1f, 1f) * tilt
    cameraDistance = 14f * density
}

/** Линия середины списка проходит через эту строку: она «выбрана» и становится крупной. */
private fun containsCenter(state: LazyListState, index: Int): Boolean {
    val li = state.layoutInfo
    val info = li.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    val c = (li.viewportStartOffset + li.viewportEndOffset) / 2
    return c >= info.offset && c < info.offset + info.size
}

/** 1 — строка ровно посередине, 0 — на расстоянии строки и дальше. */
private fun centerness(state: LazyListState, index: Int): Float {
    val li = state.layoutInfo
    val info = li.visibleItemsInfo.firstOrNull { it.index == index } ?: return 0f
    val c = (li.viewportStartOffset + li.viewportEndOffset) / 2f
    val k = abs(info.offset + info.size / 2f - c) / info.size
    return (1f - k).coerceIn(0f, 1f)
}

@Composable
private fun ListSearchField(
    query: String,
    onChange: (String) -> Unit,
    onAdd: (() -> Unit)?,
    onScan: (() -> Unit)?,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit
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
                    "Название или штрих-код",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { onChange(germanKeys(it)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { onFocusChange(it.isFocused) }
            )
        }
        if (query.isNotEmpty()) {
            TextButton(onClick = { onChange("") }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("✕") }
        }
        if (onScan != null) {
            IconButton(onClick = onScan) { BarcodeScannerIcon(color = MaterialTheme.colorScheme.primary) }
        }
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Добавить товар", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Мини-окно с подходящими товарами под полем поиска. */
@Composable
private fun SuggestionsCard(hits: List<ItemRow>, onPick: (ItemRow) -> Unit, modifier: Modifier = Modifier, maxH: Dp = 280.dp, compact: Boolean = false) {
    // свой цвет и рамка — чтобы окно подсказок отличалось от основного списка
    Card(
        modifier.fillMaxWidth().heightIn(max = maxH),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary)
    ) {
        if (hits.isEmpty()) {
            Text("Ничего не найдено", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                hits.forEachIndexed { i, r ->
                    if (compact) {
                        // компактно: название и плановое количество в одной строке — в окне помещается больше подсказок
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onPick(r) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                r.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 16.sp,
                                lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            val info = buildList {
                                if (r.extra) add("нет в плане") else add(fmtQ(parseQ(r.qty)))
                                if (r.fact.isNotBlank()) add("факт ${fmtQ(parseQ(r.fact))}")
                            }.joinToString(" · ")
                            Text(info, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    } else Column(
                        Modifier.fillMaxWidth().clickable { onPick(r) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(r.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val info = buildList {
                            if (r.extra) add("нет в плане") else add("план ${fmtQ(parseQ(r.qty))}")
                            if (r.fact.isNotBlank()) add("факт ${fmtQ(parseQ(r.fact))}")
                        }.joinToString(" · ")
                        Text(info, style = MaterialTheme.typography.bodySmall)
                    }
                    if (i < hits.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f))
                }
            }
        }
    }
}

/**
 * Список-колесо для приёма по имени. rows — строки приёма (ItemRow): qty = план, fact = пересчитано, defect = брак.
 * Колесо всегда показывает весь список; поле поиска даёт подсказки, тап по подсказке ставит товар на середину.
 * addTick — при росте значения колесо прокручивается к последней (только что добавленной) строке.
 * onAdd — «+» в поле поиска.
 */
@Composable
fun CountWheelList(
    rows: SnapshotStateList<ItemRow>,
    editable: Boolean,
    modifier: Modifier = Modifier,
    addTick: Int = 0,
    onAdd: (() -> Unit)? = null,
    emptyText: String = "Список пуст",
    /** false — своего поля поиска нет (его рисует экран, например «Приём B-Ware») */
    showSearch: Boolean = true,
    /** true — режим B-Ware: кнопки свайпа «Срок» и «Удалить», под названием — срок годности */
    bware: Boolean = false,
    /** при addTick колесо встаёт на эту строку; null — на последнюю добавленную */
    focusTarget: ItemRow? = null,
    /** не null — у строки третья кнопка свайпа «Причины» (инвентарь B-Ware); вызывается с этой строкой */
    onDetails: ((ItemRow) -> Unit)? = null,
    /** true — обычный список (без колеса); используется в B-Ware и в «Приём вручную» */
    plain: Boolean = false,
    /** подпись под названием у строки, добавленной вручную (нет в плане); пусто — без подписи */
    extraLabel: String = "нет в плане",
    /**
     * «Приём по имени»: строки в 1,5 раза выше; количество, срок, брак и удаление меняются только у строки посередине
     * (до другой строки доходят прокруткой или поиском); при наборе в поиске выбранная строка стоит посередине между полем и клавиатурой.
     */
    nameMode: Boolean = false,
    /** кнопка сканера рядом с «+»: найти товар по штрих-коду */
    onScan: (() -> Unit)? = null,
    /** кнопка «EAN» в свайпе строки влево: отсканировать штрих-код и привязать его к этой строке */
    onScanBind: ((ItemRow) -> Unit)? = null,
    /** true — круглая плавающая кнопка сканера; работает так же, как сканер в поле поиска (onScan) */
    scanFab: Boolean = false,
    /** действие круглой кнопки; null — то же, что onScan */
    onScanFab: (() -> Unit)? = null,
    /** поставить эту строку на середину при росте jumpTick */
    jumpTo: ItemRow? = null,
    jumpTick: Int = 0,
    /** вставить текст в строку поиска при росте searchTick */
    searchText: String = "",
    searchTick: Int = 0,
    /** true — через 10 секунд без касаний фокус сам встаёт в поле поиска и открывается клавиатура */
    idleKeyboard: Boolean = false,
    /** true — пока открыто другое окно (сканер, диалог), клавиатуру не вызываем */
    idleBlock: Boolean = false
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    val lastTouch = remember { longArrayOf(System.currentTimeMillis()) }
    var picking by remember { mutableStateOf<Pair<ItemRow, Boolean>?>(null) } // true — факт, false — брак
    var editing by remember { mutableStateOf<ItemRow?>(null) }
    var expiryRow by remember { mutableStateOf<ItemRow?>(null) }
    var focusRow by remember { mutableStateOf<ItemRow?>(null) }
    var qtyRow by remember { mutableStateOf<ItemRow?>(null) }     // «Кол-во»: ввод итогового количества с клавиатуры
    var photoRow by remember { mutableStateOf<ItemRow?>(null) }   // «Фото»: строка, для которой снимаем товар
    var drumSession by remember { mutableStateOf<DrumSession?>(null) } // барабан «Приёма по имени»: прибавляет к количеству, 30 с
    val photoCtx = LocalContext.current
    val photoDb = remember { ProductDb.get(photoCtx) }
    val takePhoto = rememberPhotoCapture("RC") { n ->
        val r = photoRow
        photoRow = null
        if (r == null) {
            Media.discard(photoCtx, photoDb, listOf(n))
        } else {
            if (r.photo.isNotBlank()) Media.discard(photoCtx, photoDb, listOf(r.photo)) // старое фото заменяется
            r.photo = n
            Media.uploadPending(photoCtx, photoDb)
        }
    }
    var blinkRow by remember { mutableStateOf<ItemRow?>(null) }
    var selectedRow by remember { mutableStateOf<ItemRow?>(null) } // выбранная в поиске строка — выделена постоянно
    var focusBlink by remember { mutableStateOf(true) }      // мигнуть два раза, когда строка встала на середину
    var focusAfterIme by remember { mutableStateOf(false) }  // сначала дождаться, пока закроется клавиатура
    val keyboard = LocalSoftwareKeyboardController.current
    var blinkTick by remember { mutableIntStateOf(0) }
    val memo = remember { arrayOfNulls<ItemRow>(1) } // строка, стоявшая посередине (чтобы не терять место)
    val focusManager = LocalFocusManager.current
    val appCtx = LocalContext.current
    // пока считаем товар, экран не выключается (время — в Настройки → Экран; каждое нажатие/ввод начинает отсчёт заново)
    KeepScreenOn(listOf(rows.map { it.fact + "|" + it.defect }, query, picking, editing))
    val density = LocalDensity.current
    // «Приём по имени»: когда строк больше 6, они заполняют экран — колесо отключается, показывается обычный список одинаковых строк
    // «Приём по имени» — всегда барабан с бесконечной прокруткой, сколько бы ни было строк
    val usePlain = plain
    val bigRows = nameMode && !usePlain
    val rowH = if (bigRows) ROW_H_BIG else ROW_H
    val rowPx = with(density) { rowH.roundToPx() }

    LaunchedEffect(addTick) {
        if (addTick > 0) {
            query = ""
            focusBlink = true; focusAfterIme = false
            focusRow = focusTarget ?: rows.lastOrNull()
        }
    }

    LaunchedEffect(jumpTick) {
        if (jumpTick > 0 && jumpTo != null) { focusBlink = true; focusAfterIme = false; focusRow = jumpTo }
    }
    LaunchedEffect(searchTick) {
        if (searchTick > 0) query = searchText
    }
    // пока набираем текст, первая найденная строка сама встаёт на середину
    if (bigRows) {
        LaunchedEffect(query) {
            // пока печатаем — встаёт на середину без мигания; мигает после выбора в окне подсказок
            if (query.isNotBlank()) findRows(rows, query).firstOrNull()?.let { focusBlink = false; focusAfterIme = false; focusRow = it }
        }
    }

    val n = rows.size
    val cycle = n + 1
    val hits = if (query.isBlank()) emptyList() else findRows(rows, query)

    fun pick(row: ItemRow) {
        focusBlink = true
        focusAfterIme = true
        focusRow = row
        query = ""
        focusManager.clearFocus()
        keyboard?.hide()
    }

    // 10 секунд без касаний: экран готов к набору названия товара (фокус в поле поиска, клавиатура открыта)
    LaunchedEffect(idleKeyboard, showSearch) {
        if (!idleKeyboard || !showSearch) return@LaunchedEffect
        while (true) {
            delay(1000)
            val now = System.currentTimeMillis()
            val busy = idleBlock || picking != null || qtyRow != null || editing != null || expiryRow != null || !editable
            if (busy) { lastTouch[0] = now; continue }
            if (!searchFocused && now - lastTouch[0] >= 10_000L) {
                try {
                    searchFocus.requestFocus()
                    keyboard?.show()
                } catch (e: Exception) {
                }
                lastTouch[0] = now
            }
        }
    }

    Column(
        modifier.pointerInput(Unit) {
            // любое касание экрана сбрасывает 10-секундный отсчёт (событие не потребляем)
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch[0] = System.currentTimeMillis()
                }
            }
        }
    ) {
        if (showSearch) {
            ListSearchField(query, { query = it }, onAdd, onScan, searchFocus, { searchFocused = it }) { hits.firstOrNull()?.let { pick(it) } }
            Spacer(Modifier.height(8.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // «Приём по имени»: если все товары помещаются на экране — обычный список без повторов; барабан — только когда не помещаются
            // (отключено: в «Приёме по имени» строку выбирают только поиском, прокруткой вверх-вниз или сканером,
            // поэтому барабан нужен всегда — в обычном списке без барабана выбрать строку было бы нечем)
            val fitsAll = false
            val plainNow = usePlain || fitsAll
            if (n == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(emptyText, style = MaterialTheme.typography.bodyMedium)
                }
            } else if (plainNow) {
                val plainState = rememberLazyListState()
                val plainItemPx = with(density) { 68.dp.roundToPx() }
                LaunchedEffect(focusRow, n) {
                    val fr = focusRow
                    if (fr != null) {
                        val k = rows.indexOf(fr)
                        if (k >= 0) {
                            if (focusAfterIme) awaitStableViewport(plainState)
                            // строка — на середину списка (у самого начала и конца списка — насколько хватает места)
                            val h = plainState.layoutInfo.viewportSize.height
                            val shift = ((h - plainItemPx) / 2).coerceIn(0, k * plainItemPx)
                            plainState.animateScrollToItem(k, -shift)
                            if (focusBlink) {
                                selectedRow = fr
                                blinkRow = fr
                                blinkTick++
                            }
                        }
                        focusRow = null
                    }
                }
                LazyColumn(state = plainState, modifier = Modifier.fillMaxSize()) {
                    items(rows.toList(), key = { System.identityHashCode(it) }) { row ->
                        WheelRow(
                            row = row,
                            blinkKey = if (row === blinkRow) blinkTick else 0,
                            selected = row === selectedRow,
                            index = 0,
                            listState = plainState,
                            editable = editable,
                            plain = true,
                            bware = bware,
                            extraLabel = extraLabel,
                            rowH = rowH, centerOnly = false, bigText = false, planBeside = nameMode, onSelect = { focusRow = row }, onTap = { if (nameMode && query.isNotEmpty()) query = "" },
                            onPickExpiry = { expiryRow = row },
                            onDetails = onDetails?.let { cb -> { cb(row) } },
                            onPickFact = { picking = row to true },
                            onPickDefect = { picking = row to false },
                            onEdit = { editing = row },
                            onDelete = { rows.remove(row) },
                            onEan = onScanBind?.let { cb -> { cb(row) } },
                            onPickQty = if (nameMode && !bware) ({ qtyRow = row }) else null,
                            onPhoto = if (nameMode && !bware) ({ photoRow = row; takePhoto() }) else null
                        )
                    }
                }
            } else {
                key(n) {
                    // старт: первая строка близко к середине, выше неё — пустая и последняя
                    val listState = rememberLazyListState(
                        initialFirstVisibleItemIndex = (CYCLE_BASE / cycle) * cycle - 3
                    )
                    val restored = remember { booleanArrayOf(false) }
                    LaunchedEffect(focusRow) {
                        if (!restored[0]) {
                            restored[0] = true
                            val k = memo[0]?.let { rows.indexOf(it) } ?: -1
                            if (k >= 0) scrollToRow(listState, k, cycle, rowPx, false)
                        }
                        val fr = focusRow
                        if (fr != null) {
                            val k = rows.indexOf(fr)
                            if (k >= 0) {
                                // выбрали в подсказках: клавиатура закрывается и меняет высоту списка — ждём, потом прокручиваем
                                if (focusAfterIme) awaitStableViewport(listState)
                                scrollToRow(listState, k, cycle, rowPx, true)
                                memo[0] = fr
                                // строка встала на середину — мигнуть два раза
                                if (focusBlink) {
                                    selectedRow = fr
                                    blinkRow = fr
                                    blinkTick++
                                }
                            }
                            focusRow = null
                        }
                    }
                    val lastH = remember { intArrayOf(0) }
                    // клавиатура открылась или закрылась — высота списка изменилась: выбранная строка остаётся посередине
                    LaunchedEffect(Unit) {
                        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { h ->
                            if (h > 0) {
                                if (lastH[0] != 0 && lastH[0] != h) {
                                    val k = memo[0]?.let { rows.indexOf(it) } ?: -1
                                    // пока ждёт выбранная строка (focusRow), старое место не возвращаем — иначе она «откатывается» назад
                                    if (k >= 0 && focusRow == null) scrollToRow(listState, k, cycle, rowPx, false)
                                }
                                lastH[0] = h
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        snapshotFlow { centerRow(listState, rows, cycle) }.collect {
                            val hNow = listState.layoutInfo.viewportSize.height
                            if (lastH[0] != 0 && hNow != lastH[0]) return@collect // высота меняется — середину вернёт эффект выше
                            if (it != null) {
                                // импульс, когда на середину пришла другая строка (первое значение — просто запоминаем)
                                if (memo[0] != null && memo[0] !== it) Haptics.pulse(appCtx, Haptics.LIST)
                                memo[0] = it
                            }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        flingBehavior = rememberSnapFlingBehavior(listState),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(count = Int.MAX_VALUE) { index ->
                            val row = rows.getOrNull(index % cycle)
                            if (row == null) {
                                // граница круга: после последней строки — линия, затем снова первая
                                Box(Modifier.fillMaxWidth().height(if (bigRows) rowH / 1.5f else rowH), contentAlignment = Alignment.Center) {
                                    HorizontalDivider(Modifier.padding(horizontal = 24.dp), thickness = 2.dp, color = MaterialTheme.colorScheme.outline)
                                }
                            } else {
                                WheelRow(
                                    row = row,
                                    blinkKey = if (row === blinkRow) blinkTick else 0,
                                    selected = row === selectedRow,
                                    index = index,
                                    listState = listState,
                                    editable = editable,
                                    plain = false,
                                    bware = bware,
                                    extraLabel = extraLabel,
                                    rowH = rowH, centerOnly = bigRows, bigText = bigRows, planBeside = nameMode, onSelect = { focusRow = row }, onTap = { if (nameMode && query.isNotEmpty()) query = "" },
                                    onPickExpiry = { expiryRow = row },
                                    onDetails = onDetails?.let { cb -> { cb(row) } },
                                    onPickFact = { picking = row to true },
                                    onPickDefect = { picking = row to false },
                                    onEdit = { editing = row },
                                    onDelete = { rows.remove(row) },
                                    onEan = onScanBind?.let { cb -> { cb(row) } },
                                    onPickQty = if (nameMode && !bware) ({ qtyRow = row }) else null,
                                    onPhoto = if (nameMode && !bware) ({ photoRow = row; takePhoto() }) else null
                                )
                            }
                        }
                    }
                }
            }
            // «Приём по имени»: подсказки появляются с третьей буквы и остаются, пока есть похожие названия
            // или пока не нажали на название (в подсказках или в списке); без совпадений окно скрывается
            val showSuggestions = if (nameMode) query.trim().length >= 3 && hits.isNotEmpty() else query.isNotBlank()
            if (showSuggestions) {
                SuggestionsCard(
                    hits, onPick = { pick(it) }, modifier = Modifier.align(Alignment.TopCenter),
                    maxH = if (nameMode) 336.dp else 280.dp, compact = nameMode
                )
            }
            if (scanFab && (onScanFab ?: onScan) != null && editable && n > 0 && (!plainNow || nameMode)) {
                ScanFab { (onScanFab ?: onScan)?.invoke() }
            }
            CountOverlay()
        }
    }

    picking?.let { (row, isFact) ->
        // «Приём по имени», факт: барабан прибавляет к количеству. Выбранное на барабане можно менять 30 секунд,
        // потом барабан снова на нуле, а количество остаётся в строке (ещё 10 на барабане = +10).
        val drumAdd = nameMode && !bware && isFact
        val drumInfo = remember(row) {
            val ds = drumSession
            val live = drumAdd && ds != null && ds.row === row &&
                System.currentTimeMillis() - ds.at < DRUM_RESET_MS && parseQ(row.fact) == ds.last
            val base = if (!drumAdd) 0.0 else if (live) ds!!.base else parseQ(row.fact)
            base to (if (live) (parseQ(row.fact) - base).roundToInt().coerceIn(0, 9999) else 0)
        }
        val drumBase = drumInfo.first
        CountPickerDialog(
            title = (if (isFact) (if (bware) "Количество: " else if (drumAdd && drumBase > 0) "Факт ${fmtQ(drumBase)} + " else "Факт: ") else "Брак: ") + row.name,
            initial = if (drumAdd) drumInfo.second else parseQ(if (isFact) row.fact else row.defect).roundToInt().coerceIn(0, 9999),
            onApply = { v ->
                if (drumAdd) {
                    val total = drumBase + v
                    row.fact = fmtQ(total)
                    drumSession = DrumSession(row, drumBase, total, System.currentTimeMillis())
                    CountAnnouncer.picked(appCtx, fmtQ(total), fmtQ(total))
                } else {
                    if (isFact) row.fact = v.toString() else row.defect = v.toString()
                    if (isFact) CountAnnouncer.picked(appCtx, v.toString(), v.toString())
                    else CountAnnouncer.picked(appCtx, "Брак $v", "брак $v")
                }
                picking = null
            },
            onDismiss = { picking = null },
            photos = if (!isFact && !bware) row.defectPhotos else null
        )
    }
    editing?.let { row -> RowEditDialog(row) { editing = null } }
    qtyRow?.let { row ->
        QtyInputDialog(
            row = row,
            onApply = { v ->
                row.fact = fmtQ(v)
                drumSession = null // итоговое количество задано вручную: барабан начинается с нуля
                CountAnnouncer.picked(appCtx, fmtQ(v), fmtQ(v))
                qtyRow = null
            },
            onDismiss = { qtyRow = null }
        )
    }
    expiryRow?.let { row ->
        ExpiryPickerDialog(
            initial = row.expiry,
            onPick = { row.expiry = it; expiryRow = null },
            onDismiss = { expiryRow = null }
        )
    }
}

@Composable
private fun WheelRow(
    row: ItemRow,
    blinkKey: Int,
    selected: Boolean = false,
    index: Int,
    listState: LazyListState,
    editable: Boolean,
    plain: Boolean,
    bware: Boolean,
    extraLabel: String,
    rowH: Dp,
    centerOnly: Boolean,
    bigText: Boolean,
    /** «Приём по имени»: «план N» стоит рядом с фактом; в обычном списке тап по строке = +1, тап по числу открывает окно прокрутки количества */
    planBeside: Boolean = false,
    onSelect: () -> Unit,
    /** любое нажатие на строку списка (закрывает окно подсказок) */
    onTap: () -> Unit = {},
    onPickExpiry: () -> Unit,
    onDetails: (() -> Unit)?,
    onPickFact: () -> Unit,
    onPickDefect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEan: (() -> Unit)? = null,
    /** «Приём по имени»: кнопка «Кол-во» — ввод итогового количества с клавиатуры */
    onPickQty: (() -> Unit)? = null,
    /** «Приём по имени»: кнопка «Фото» — снимок товара, печатается в документе и уходит на Google Диск */
    onPhoto: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val btnCount = 2 + (if (onEan != null) 1 else 0) + (if (onPickQty != null) 1 else 0) + (if (onPhoto != null) 1 else 0)
    val btnW = if (btnCount >= 5) 62.dp else 76.dp
    val maxPx = with(density) { (btnW * btnCount).toPx() }
    val rightPx = with(density) { 96.dp.toPx() }
    val anim = remember(row) { Animatable(0f) }
    val blink = remember(row) { Animatable(0f) }
    LaunchedEffect(blinkKey) {
        if (blinkKey > 0) {
            repeat(2) {
                blink.animateTo(1f, tween(170))
                blink.animateTo(0f, tween(170))
            }
        }
    }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    // кнопки свайпа сами закрываются через 5 секунд, если их не трогали
    var dragging by remember { mutableStateOf(false) }
    var dragStartX by remember { mutableFloatStateOf(0f) }
    val swipeOpen by remember { derivedStateOf { anim.value < -1f } }
    LaunchedEffect(swipeOpen, dragging) {
        if (swipeOpen && !dragging) {
            delay(5000)
            anim.animateTo(0f)
        }
    }
    val factD = parseQ(row.fact)
    val planD = if (row.extra) 0.0 else parseQ(row.qty)
    val defD = parseQ(row.defect)
    val hasDetails = bware && onDetails != null
    // в «Приёме по имени» менять можно только строку посередине; остальные — только выбрать (тап или прокрутка)
    val active by remember(listState, index, centerOnly, plain) {
        derivedStateOf { !centerOnly || plain || centerness(listState, index) > 0.5f }
    }
    val canEdit = editable && active
    // барабан «Приёма по имени»: выбранная (на середине) строка в 1,5 раза выше остальных, ширина одинаковая
    val isCenterRow by remember(listState, index, centerOnly, plain) {
        derivedStateOf { centerOnly && !plain && containsCenter(listState, index) }
    }
    val animH by animateDpAsState(if (isCenterRow) rowH else rowH / 1.5f, tween(120), label = "rowH")
    val emph = !bigText || isCenterRow  // крупные шрифты только у выбранной строки

    Box(
        Modifier.fillMaxWidth()
            .height(if (plain) 68.dp else if (centerOnly) animH else rowH)
            .padding(horizontal = if (plain) 0.dp else 12.dp, vertical = 3.dp)
            .then(if (plain) Modifier else Modifier.graphicsLayer { applyBigCenter(listState, index, WHEEL_TILT) })
    ) {
        // слева (открывается свайпом вправо): срок годности
        Box(
            Modifier.align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(106.dp)
                .graphicsLayer { alpha = if (anim.value > 1f) 1f else 0f }
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.tertiaryContainer)
                .clickable(enabled = canEdit) {
                    scope.launch { anim.animateTo(0f) }
                    onPickExpiry()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Срок\nгодности",
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onTertiaryContainer
            )
        }

        // справа (открываются свайпом справа налево)
        Row(
            Modifier.align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(btnW * btnCount)
                .clip(RoundedCornerShape(14.dp))
        ) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(scheme.secondaryContainer)
                    .clickable(enabled = canEdit) {
                        scope.launch { anim.animateTo(0f) }
                        if (hasDetails) onDetails?.invoke() else onPickDefect()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (hasDetails) (if (row.reasons.isEmpty()) "Причины" else "Причины\n${row.reasons.size}")
                    else if (defD > 0) "Брак\n${fmtQ(defD)}" else "Брак",
                    textAlign = TextAlign.Center,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSecondaryContainer
                )
            }
            if (onEan != null) {
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(scheme.tertiaryContainer)
                        .clickable(enabled = canEdit) {
                            scope.launch { anim.animateTo(0f) }
                            onEan()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (row.barcode.isBlank()) "EAN" else "EAN\n✓",
                        textAlign = TextAlign.Center,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onTertiaryContainer
                    )
                }
            }
            if (onPickQty != null) {
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(scheme.primaryContainer)
                        .clickable(enabled = canEdit) {
                            scope.launch { anim.animateTo(0f) }
                            onPickQty()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Кол-во",
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onPrimaryContainer
                    )
                }
            }
            if (onPhoto != null) {
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(scheme.secondaryContainer)
                        .clickable(enabled = canEdit) {
                            scope.launch { anim.animateTo(0f) }
                            onPhoto()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (row.photo.isBlank()) "Фото" else "Фото\n✓",
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSecondaryContainer
                    )
                }
            }
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(scheme.errorContainer)
                    .clickable(enabled = canEdit) { onDelete() },
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

        // сама строка: свайп вправо — срок годности, влево — кнопки; тап = +1; долгое нажатие — причины (B-Ware) или правка
        // «Приём по имени»: порог движения пальца выше обычного — лёгкое смещение при тапе не считается свайпом
        val baseVc = LocalViewConfiguration.current
        val vc = remember(baseVc, centerOnly) { if (centerOnly && !plain) WideSlopConfig(baseVc, baseVc.touchSlop * SWIPE_SLOP_MULT) else baseVc }
        CompositionLocalProvider(LocalViewConfiguration provides vc) {
        Row(
            Modifier.offset { IntOffset(anim.value.roundToInt(), 0) }
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceVariant)
                // средняя строка колеса другого цвета: плавно «заливается» по мере подхода к середине
                .drawBehind {
                    if (selected) {
                        // выбранная строка: свой цвет, всегда
                        drawRect(scheme.tertiaryContainer)
                    } else if (!plain) {
                        val t = centerness(listState, index)
                        if (t > 0f) drawRect(scheme.primaryContainer.copy(alpha = t))
                    }
                }
                .background(scheme.primary.copy(alpha = 0.55f * blink.value))
                .then(if (selected) Modifier.border(2.5.dp, scheme.tertiary, RoundedCornerShape(14.dp)) else Modifier)
                // выбранная строка (середина барабана) вверх-вниз не листается: список двигают только за невыбранные строки
                .then(
                    if (centerOnly && !plain && active) Modifier.pointerInput(Unit) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var dx = 0f
                            var dy = 0f
                            var down = true
                            while (down) {
                                val ev = awaitPointerEvent()
                                ev.changes.forEach { c ->
                                    val d = c.positionChange()
                                    dx += d.x; dy += d.y
                                    if (abs(dy) > slop && abs(dy) > abs(dx)) c.consume()
                                }
                                down = ev.changes.any { it.pressed }
                            }
                        }
                    } else Modifier
                )
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = canEdit,
                    state = rememberDraggableState { delta ->
                        scope.launch { anim.snapTo((anim.value + delta).coerceIn(-maxPx, rightPx)) }
                    },
                    onDragStarted = { dragging = true; dragStartX = anim.value },
                    onDragStopped = { velocity ->
                        dragging = false
                        scope.launch {
                            if (anim.value > rightPx * 0.6f) {
                                // протянули вправо — открыть выбор срока годности
                                onPickExpiry()
                                anim.animateTo(0f)
                            } else {
                                // решает не «половина всех кнопок», а то, куда и насколько двинули палец (кнопок до 5 — полпути было слишком далеко)
                                val moved = anim.value - dragStartX
                                val need = with(density) { SWIPE_OPEN_DP.dp.toPx() }
                                val wasOpen = dragStartX < -1f
                                val open = if (wasOpen) !(moved > need || velocity > 600f) else (moved < -need || velocity < -500f)
                                anim.animateTo(if (open) -maxPx else 0f)
                            }
                        }
                    }
                )
                // тап и долгое нажатие работают только у выбранной строки (середина барабана);
                // на остальных строках они ничего не делают — выбрать строку можно только поиском, прокруткой или сканером
                .combinedClickable(
                    enabled = editable && active,
                    onClick = {
                        onTap()
                        if (anim.value < -1f) scope.launch { anim.animateTo(0f) }
                        else {
                            row.fact = fmtQ(parseQ(row.fact) + 1)
                            Haptics.pulse(ctx, Haptics.TAP)
                            CountAnnouncer.tap(ctx, row)
                        }
                    },
                    onLongClick = { if (hasDetails) onDetails?.invoke() else onEdit() }
                )
                .padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.name.ifBlank { "—" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = if (bigText) (if (emph) 18.sp else 13.sp) else 15.sp,
                    lineHeight = if (bigText) (if (emph) 21.sp else 16.sp) else 18.sp,
                    fontWeight = FontWeight.Medium
                )
                if (bware) {
                    val tail = if (!hasDetails) "" else if (row.reasons.isEmpty()) "" else " · причин ${row.reasons.size}"
                    Text(
                        (if (row.expiry.isBlank()) "срок: не указан" else "срок: ${row.expiry}") + tail,
                        fontSize = 12.sp,
                        maxLines = 1,
                        color = if (row.expiry.isBlank()) scheme.error else scheme.onSurfaceVariant
                    )
                } else {
                    val sub = buildList {
                        // в компактной строке план стоит рядом с фактом, а не под названием
                        if (row.extra) { if (extraLabel.isNotEmpty()) add(extraLabel) } else if (!(bigText || planBeside)) add("план ${fmtQ(planD)}")
                        if (defD > 0) add("брак ${fmtQ(defD)}")
                        if (row.expiry.isNotBlank()) add("срок ${row.expiry}")
                    }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, fontSize = if (bigText) 11.sp else 12.sp, lineHeight = if (bigText) 13.sp else 14.sp, maxLines = 1, color = scheme.onSurfaceVariant)
                }
            }
            // план — рядом с фактическим количеством
            if ((bigText || planBeside) && !bware && !row.extra) {
                Text(
                    fmtQ(planD),
                    fontSize = 13.sp, maxLines = 1, color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            // «Приём по имени»: есть ли у товара штрих-код — значок, нет кода — перечёркнутый значок
            if (onEan != null && !bware) {
                BarcodeStatusIcon(has = row.barcode.isNotBlank(), size = if (bigText && !emph) 16.dp else 20.dp)
                Spacer(Modifier.width(8.dp))
            }
            // количество — справа; тап по числу открывает барабан цифр
            Box(
                Modifier.defaultMinSize(minWidth = if (bigText) 44.dp else 56.dp)
                    .clip(RoundedCornerShape(if (bigText) 8.dp else 12.dp))
                    .background(scheme.surface)
                    .clickable(enabled = canEdit) { onPickFact() }
                    .padding(horizontal = if (bigText) 8.dp else 10.dp, vertical = if (bigText) (if (emph) 6.dp else 2.dp) else 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    fmtQ(factD),
                    fontSize = if (bigText) (if (emph) 28.sp else 19.sp) else 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        row.fact.isBlank() -> scheme.onSurfaceVariant
                        !bware && !row.extra && factD == planD -> scheme.primary
                        !bware && !row.extra && factD > planD -> scheme.error
                        else -> scheme.onSurface
                    }
                )
            }
        }
        }
    }
}

/** Конфигурация касаний с увеличенным порогом движения (touchSlop). */
private class WideSlopConfig(private val base: ViewConfiguration, private val slop: Float) : ViewConfiguration by base {
    override val touchSlop: Float get() = slop
}

/** Во сколько раз порог «это уже свайп» больше стандартного (тап с лёгким смещением остаётся тапом). */
private const val SWIPE_SLOP_MULT = 1.5f
/** Сколько dp надо протянуть строку, чтобы кнопки открылись (или закрылись). */
private const val SWIPE_OPEN_DP = 44f

private const val MAX_COUNT = 9999
private const val PAD_ITEMS = 2 // пустые строки сверху и снизу, чтобы 0 и 9999 тоже вставали в середину

/** Число в середине колеса (индекс списка минус пустые строки сверху). */
private fun selectedCount(state: LazyListState, fallback: Int): Int {
    val li = state.layoutInfo
    val c = (li.viewportStartOffset + li.viewportEndOffset) / 2
    val best = li.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - c) } ?: return fallback
    return (best.index - PAD_ITEMS).coerceIn(0, MAX_COUNT)
}

/** Одно колесо: все числа 0…9999 подряд, начинается с 0. */
@Composable
private fun CountWheel(state: LazyListState) {
    Box(Modifier.width(180.dp).height(DIGIT_H * 5)) {
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
            items(count = MAX_COUNT + 1 + PAD_ITEMS * 2) { i ->
                val v = i - PAD_ITEMS
                Box(
                    Modifier.fillMaxWidth()
                        .height(DIGIT_H)
                        .graphicsLayer { applyWheel(state, i, 0.55f, 0.25f, 30f, 0.12f) },
                    contentAlignment = Alignment.Center
                ) {
                    if (v in 0..MAX_COUNT) Text(v.toString(), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Барабан количества как в будильнике, но одно колесо 0…9999. Значение применяется само через 3 секунды
 * после остановки колеса; кнопка «Готово» — сразу.
 */
@Composable
fun CountPickerDialog(
    title: String,
    initial: Int,
    onApply: (Int) -> Unit,
    onDismiss: () -> Unit,
    /** не null — в окне можно снять фото (брак); автоприменение отключается, число применяет «Готово» */
    photos: SnapshotStateList<String>? = null
) {
    val start = initial.coerceIn(0, MAX_COUNT)
    // первый видимый индекс = число; число стоит третьим сверху, то есть посередине
    val state = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val value by remember { derivedStateOf { selectedCount(state, start) } }
    val scrolling = state.isScrollInProgress
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) { if (scrolling) touched = true }
    val vibCtx = LocalContext.current
    LaunchedEffect(Unit) {
        // импульс на каждое новое число в середине (первое значение пропускаем)
        var first = true
        snapshotFlow { value }.collect {
            if (first) first = false else Haptics.pulse(vibCtx, Haptics.PICKER)
        }
    }
    LaunchedEffect(value, scrolling, touched) {
        if (touched && !scrolling && photos == null) {
            delay(3000)
            onApply(value)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                CountWheel(state)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (photos == null) "Выбрано: $value · применится само через 3 секунды" else "Выбрано: $value",
                    style = MaterialTheme.typography.bodySmall
                )
                if (photos != null) {
                    val pctx = LocalContext.current
                    val pdb = remember { ProductDb.get(pctx) }
                    Spacer(Modifier.height(8.dp))
                    Text("Фото брака (печатается на последнем листе документа)", style = MaterialTheme.typography.bodySmall)
                    if (photos.isNotEmpty()) {
                        PhotoGrid(photos.toList()) { n ->
                            Media.discard(pctx, pdb, listOf(n))
                            photos.remove(n)
                        }
                    }
                    MediaButtons(kind = "RC", allowFile = false) { n ->
                        photos.add(n)
                        Media.uploadPending(pctx, pdb)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Button(onClick = { onApply(value) }) { Text("Готово") }
                }
            }
        }
    }
}

/** Барабан «Приёма по имени»: количество до первого выбора на барабане (base), итог (last) и время последнего выбора. */
private class DrumSession(val row: ItemRow, val base: Double, val last: Double, val at: Long)

private const val DRUM_RESET_MS = 30_000L

/** Мини-окно «Кол-во»: итоговое фактическое количество набирается на цифровой клавиатуре (например 7000). */
@Composable
private fun QtyInputDialog(row: ItemRow, onApply: (Double) -> Unit, onDismiss: () -> Unit) {
    val start = fmtQ(parseQ(row.fact))
    var tf by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(start, androidx.compose.ui.text.TextRange(0, start.length))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val ok = tf.text.isNotBlank() && normalizeQty(tf.text).toDoubleOrNull() != null
    fun apply() { if (ok) onApply(parseQ(tf.text)) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Количество", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = tf,
                    onValueChange = { v -> tf = v.copy(text = v.text.filter { it.isDigit() || it == '.' || it == ',' }.take(9)) },
                    label = { Text("Фактическое количество (итог)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { apply() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            }
        },
        confirmButton = { Button(enabled = ok, onClick = { apply() }) { Text("ОК") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
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
                OutlinedTextField(name, { name = germanKeys(it) }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
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


/**
 * Круглая плавающая кнопка сканера штрих-кода. Её можно перетащить в любое место экрана — там она и остаётся
 * (положение хранится на телефоне). Тап — сканировать и привязать код к выбранной (средней) строке.
 */
@Composable
private fun BoxScope.ScanFab(onClick: () -> Unit) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("scan_fab", Context.MODE_PRIVATE) }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val d = LocalDensity.current
        val sizePx = with(d) { 56.dp.toPx() }
        val gap = with(d) { 12.dp.toPx() }
        val w = with(d) { maxWidth.toPx() }
        val h = with(d) { maxHeight.toPx() }
        val maxX = (w - sizePx).coerceAtLeast(0f)
        val maxY = (h - sizePx).coerceAtLeast(0f)
        // -1 — положение ещё не задано: правый нижний угол
        var px by remember { mutableFloatStateOf(sp.getFloat("x", -1f)) }
        var py by remember { mutableFloatStateOf(sp.getFloat("y", -1f)) }
        val x = (if (px < 0f) maxX - gap else px).coerceIn(0f, maxX)
        val y = (if (py < 0f) maxY - gap else py).coerceIn(0f, maxY)
        Box(
            Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(56.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragEnd = { sp.edit().putFloat("x", px).putFloat("y", py).apply() }
                    ) { change, drag ->
                        change.consume()
                        px = ((if (px < 0f) maxX - gap else px) + drag.x).coerceIn(0f, maxX)
                        py = ((if (py < 0f) maxY - gap else py) + drag.y).coerceIn(0f, maxY)
                    }
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            BarcodeScannerIcon(28.dp, MaterialTheme.colorScheme.onPrimary)
        }
    }
}
