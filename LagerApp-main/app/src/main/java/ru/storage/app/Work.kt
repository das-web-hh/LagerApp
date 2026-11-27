package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Вкладка «Работа»: разделы склада, внутри каждого — кнопки действий.
 * Прежнее содержимое «Работы» (Ручной, Автоприём, Coglas…) теперь в «Прочее».
 *
 * live — какое готовое окно приложения открывает кнопка; null — окна пока нет: откроется заглушка «Окно в разработке»,
 * чтобы позже подключить действие, заменив null на ключ из workLive().
 */

private class WorkAction(val title: String, val live: String? = null)
private class WorkSection(val title: String, val icon: ImageVector, val actions: List<WorkAction>)

private val WORK_SECTIONS = listOf(
    WorkSection(
        "Приём", Icons.Default.Add, listOf(
            WorkAction("Разгрузка", "unload"),
            WorkAction("Посчитать товары", "coglas"),
            WorkAction("Проверка брака и расхождений"),
            WorkAction("Маркировка и печать этикеток"),
            WorkAction("Размещение на склад"),
            WorkAction("Завершить приёмку")
        )
    ),
    WorkSection(
        "Перемещение", Icons.Default.Refresh, listOf(
            WorkAction("Пополнение зоны отбора"),
            WorkAction("Перемещение между ячейками"),
            WorkAction("Перемещение в зону брака / возврата")
        )
    ),
    WorkSection(
        "Сборка", Icons.Default.ShoppingCart, listOf(
            WorkAction("Взять заказ в работу", "assembly"),
            WorkAction("Собрать позиции", "assembly"),
            WorkAction("Экспедиция (передать в зону отгрузки)"),
            WorkAction("Завершить сборку")
        )
    ),
    WorkSection(
        "Упаковка", Icons.Default.Build, listOf(
            WorkAction("Взвесить и проверить"),
            WorkAction("Упаковать заказ"),
            WorkAction("Печать накладной / этикетки")
        )
    ),
    WorkSection(
        "Отправка", Icons.AutoMirrored.Filled.Send, listOf(
            WorkAction("Погрузка в транспорт"),
            WorkAction("Передать курьеру / ТК", "send"),
            WorkAction("Закрыть отправку")
        )
    ),
    WorkSection(
        "Обхолер (самовывоз)", Icons.Default.Place, listOf(
            WorkAction("Подготовить заказ к выдаче"),
            WorkAction("Выдать клиенту (подпись / документы)")
        )
    ),
    WorkSection(
        "Инвентаризация", Icons.Default.Done, listOf(
            WorkAction("Пересчёт ячейки / товара", "inventory"),
            WorkAction("Зафиксировать расхождения")
        )
    ),
    WorkSection(
        "Возвраты", Icons.Default.Warning, listOf(
            WorkAction("Принять возврат"),
            WorkAction("Проверка состояния (на полку / в брак)")
        )
    )
)

/** Окно вкладки «Работа» — вся навигация внутри вкладки описана двумя значениями: раздел и открытое в нём действие. */
private data class WorkPage(val section: String?, val live: String?)

private fun workDepth(p: WorkPage) = if (p.live != null) 2 else if (p.section != null) 1 else 0

/**
 * Окна с анимацией: открытие — новое окно выезжает справа налево и закрывает прежнее, закрытие — наоборот, окно уезжает вправо.
 * depth — «глубина» окна: больше — открылось, меньше — закрылось. Содержимое рисуется по значению page, а не по текущему состоянию,
 * поэтому уходящее окно во время анимации остаётся таким, каким было.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun <T> SlidePages(page: T, depth: (T) -> Int, content: @Composable (T) -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = depth(targetState) > depth(initialState)
            val move = tween<IntOffset>(320, easing = FastOutSlowInEasing)
            val fade = tween<Float>(320)
            val t = if (forward) {
                (slideInHorizontally(move) { w -> w }) togetherWith (slideOutHorizontally(move) { w -> -w / 4 } + fadeOut(fade))
            } else {
                (slideInHorizontally(move) { w -> -w / 4 } + fadeIn(fade)) togetherWith (slideOutHorizontally(move) { w -> w })
            }
            // открывающееся окно поверх прежнего; при закрытии наверху остаётся уходящее
            t.apply { targetContentZIndex = if (forward) 1f else -1f }
        },
        label = "slide"
    ) { p ->
        // пока идёт анимация, окно непрозрачное — иначе под ним просвечивало бы прежнее
        val animating = transition.currentState != transition.targetState
        Box(Modifier.fillMaxSize().then(if (animating) Modifier.background(bg) else Modifier)) { content(p) }
    }
}

@Composable
fun WorkScreen(prefs: Prefs, receiver: String) {
    var section by NavVar({ TabNav.job }, { TabNav.job = it })
    var live by NavVar({ TabNav.jobAction }, { TabNav.jobAction = it })
    // нижнее меню видно только на корне вкладки
    SideEffect { TabRoot.set(1, section == null && live == null) }

    SlidePages(WorkPage(section, live), ::workDepth) { p ->
        val l = p.live
        val sec = WORK_SECTIONS.firstOrNull { it.title == p.section }
        when {
            l != null -> when {
                l == "search" -> ProductSearchScreen(prefs) { live = null }
                l == "unload" -> UnloadingScreen(prefs, onOpenReceive = { live = "manual" }) { live = null }
                l == "manual" -> ManualReceiveScreen(prefs, receiver) { live = null }
                l == "coglas" -> CoglasScreen(prefs) { live = null }
                l == "assembly" -> AssemblyScreen(prefs, receiver) { live = null }
                l == "send" -> SendHubScreen { live = null }
                l == "inventory" -> InventoryScreen(receiver) { live = null }
                l.startsWith("stub:") -> SectionScreen(l.removePrefix("stub:")) { live = null }
                else -> Box(Modifier.fillMaxSize())
            }
            sec != null -> {
                BackHandler { section = null }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    TextButton(onClick = { section = null }) { Text("‹ Назад") }
                    Text(sec.title, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    sec.actions.forEach { a ->
                        FilledTonalButton(
                            onClick = { live = a.live ?: "stub:${a.title}" },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
                        ) { Text(a.title, style = MaterialTheme.typography.titleMedium) }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Работа", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { live = "search" },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Поиск товара / ячейки", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(16.dp))
                TileGrid(WORK_SECTIONS.map { s -> TileSpec(s.title, s.icon) { section = s.title } })
            }
        }
    }
}

/**
 * «Поиск товара / ячейки»: штрихкод со сканера или текст. Товар ищется в базе приложения (название, артикул, штрихкод),
 * остатки и место хранения берутся из Coglas Infoscan — он понимает и штрихкод товара, и код ячейки / палеты.
 */
@Composable
fun ProductSearchScreen(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var coglasOpen by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Product>>(emptyList()) }
    var picked by remember { mutableStateOf<Product?>(null) }
    var code by remember { mutableStateOf("") }
    var scan by remember { mutableStateOf<CgScan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        results = if (query.trim().length < MIN_SEARCH_CHARS) emptyList()
        else withContext(Dispatchers.IO) { db.search(query, 50) }
    }

    fun lookup(c: String) {
        val cc = c.trim()
        if (cc.isEmpty() || busy) return
        code = cc
        busy = true
        err = null
        scan = null
        scope.launch {
            try {
                scan = CoglasData.infoscan(cc)
            } catch (e: CgError) {
                err = e.message ?: "Ошибка"
            } catch (e: Exception) {
                err = e.message ?: "Ошибка"
            } finally {
                busy = false
            }
        }
    }

    if (coglasOpen) {
        CoglasScreen(prefs) { coglasOpen = false }
        return
    }
    if (scanning) {
        ScannerScreen(
            prefs = prefs,
            onResult = { scanned ->
                scanning = false
                query = ""
                note = ""
                scope.launch {
                    val p = withContext(Dispatchers.IO) { db.findByBarcode(scanned) }
                    picked = p
                    if (p == null) note = "В базе приложения нет товара с кодом $scanned — проверяю в Coglas"
                    lookup(scanned)
                }
            },
            onClose = { scanning = false }
        )
        return
    }

    BackHandler(onBack = onBack)
    LazyColumnScreen(
        header = {
            TextButton(onClick = onBack) { Text("‹ Назад") }
            Text("Поиск товара / ячейки", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            SearchBar(
                query = query,
                onChange = { query = it; note = "" },
                onScan = { scanning = true },
                placeholder = "Штрихкод, название, артикул или код ячейки"
            )
            if (query.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { focus.clearFocus(); picked = null; note = ""; lookup(query) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Остатки в Coglas по коду «${query.trim()}»", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (note.isNotBlank()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    ) {
        // найденные товары (пока ничего не выбрано)
        if (picked == null && query.trim().length >= MIN_SEARCH_CHARS) {
            if (results.isEmpty()) Text("В базе приложения ничего не найдено", style = MaterialTheme.typography.bodySmall)
            results.forEach { p ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        focus.clearFocus()
                        picked = p
                        val c = p.barcode.ifBlank { ProductGroups.membersOf(db, p).firstOrNull { it.barcode.isNotBlank() }?.barcode.orEmpty() }
                        if (c.isNotBlank()) lookup(c) else { code = ""; scan = null; err = null; note = "У товара нет штрихкода в базе — остатки по нему не найти" }
                    }.padding(vertical = 9.dp)
                ) {
                    Text(p.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            p.shownArticle.takeIf { it.isNotBlank() }?.let { "арт. $it" } ?: "",
                            p.barcode.takeIf { it.isNotBlank() } ?: "нет штрихкода"
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HorizontalDivider()
            }
        }

        picked?.let { p ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    listOf(
                        "Артикул" to p.shownArticle,
                        "Штрихкод" to p.barcode,
                        "Упаковка" to p.pack,
                        "Производитель" to p.manufacturer
                    ).filter { it.second.isNotBlank() }.forEach { (k, v) ->
                        Text("$k: $v", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (busy) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Ищу в Coglas: $code …")
            }
        }
        err?.let { e ->
            Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { lookup(code) }) { Text("Повторить") }
                OutlinedButton(onClick = { coglasOpen = true }) { Text("Открыть Coglas") }
            }
        }
        scan?.let { sc ->
            if (sc.entities.isEmpty()) {
                Text("В Coglas ничего не найдено по коду: $code", style = MaterialTheme.typography.bodyLarge)
            } else {
                sc.entities.forEach { CgCardView(it); Spacer(Modifier.height(8.dp)) }
                if (sc.contents.isNotEmpty() || sc.contentTotal > 0) {
                    Text("Содержимое (${sc.contentTotal})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp))
                    sc.contents.forEach { CgCardView(it); Spacer(Modifier.height(8.dp)) }
                }
            }
        }
        if (picked == null && scan == null && !busy && err == null && query.isBlank()) {
            Text(
                "Отсканируйте штрихкод товара или ячейки (значок справа) либо введите название, артикул или код — покажу, что это, где лежит и сколько.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Прокручиваемый экран: фиксированная шапка сверху (поиск) и список под ней. */
@Composable
private fun LazyColumnScreen(header: @Composable ColumnScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column { header() }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { body() }
    }
}
