package ru.storage.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Состояние окна Coglas живёт вне экрана: пока человек сканирует камерой или выходит в другое окно,
 * загруженные заказы и результат поиска не пропадают, а запрос в пути доходит до конца.
 */
object CoglasState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var tab by mutableIntStateOf(1) // 0 App, 1 Сайт, 2 Настройки (только админ); первый раз открывается сайт — нужно войти
    /** Что открыто внутри вкладки App: "" — меню, "orders" — заказы, "info" — Infoscan. */
    var appPage by mutableStateOf("")
    /** Окно Coglas открыто: боковой свайп вкладок главного экрана на это время выключен (мешал прокрутке сайта). */
    var active by mutableStateOf(false)

    var orders by mutableStateOf<CgOrdersPage?>(null)
    var ordersBusy by mutableStateOf(false)
    var ordersErr by mutableStateOf<CgError?>(null)

    var openOrder by mutableStateOf<CgOrder?>(null)
    var detail by mutableStateOf<CgOrderDetail?>(null)
    var detailBusy by mutableStateOf(false)
    var detailErr by mutableStateOf<CgError?>(null)

    var query by mutableStateOf("")
    var scanned by mutableStateOf("")
    var scan by mutableStateOf<CgScan?>(null)
    var scanBusy by mutableStateOf(false)
    var scanErr by mutableStateOf<CgError?>(null)

    var tool by mutableStateOf("")

    fun loadOrders() {
        if (ordersBusy) return
        ordersBusy = true
        ordersErr = null
        scope.launch {
            try {
                orders = CoglasData.orders()
            } catch (e: CgError) {
                ordersErr = e
            } catch (e: Exception) {
                ordersErr = CgError(e.message ?: "Ошибка")
            } finally {
                ordersBusy = false
            }
        }
    }

    fun open(o: CgOrder) {
        openOrder = o
        detail = null
        detailErr = null
        detailBusy = true
        scope.launch {
            try {
                val d = CoglasData.order(o.id, o.number)
                if (openOrder?.id == o.id) detail = d
            } catch (e: CgError) {
                if (openOrder?.id == o.id) detailErr = e
            } catch (e: Exception) {
                if (openOrder?.id == o.id) detailErr = CgError(e.message ?: "Ошибка")
            } finally {
                detailBusy = false
            }
        }
    }

    fun closeOrder() {
        openOrder = null
        detail = null
        detailErr = null
    }

    fun search(code: String) {
        val c = code.trim()
        if (c.isEmpty() || scanBusy) return
        scanBusy = true
        scanErr = null
        scanned = c
        scan = null
        scope.launch {
            try {
                scan = CoglasData.infoscan(c)
            } catch (e: CgError) {
                scanErr = e
            } catch (e: Exception) {
                scanErr = CgError(e.message ?: "Ошибка")
            } finally {
                scanBusy = false
            }
        }
    }
}

@Composable
fun CoglasScreen(prefs: Prefs, onBack: () -> Unit) {
    val st = CoglasState
    val ctx = LocalContext.current
    val isAdmin = Session.isAdmin
    // кто просит сканер: "" никто, "info" — Infoscan в App, "site" — сайт (вместо своей камеры)
    var scanFor by remember { mutableStateOf("") }

    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        CoglasWeb.camResult(it)
    }
    DisposableEffect(Unit) {
        CoglasWeb.camLauncher = { camLauncher.launch(Manifest.permission.CAMERA) }
        CoglasWeb.onSiteScan = { scanFor = "site" }
        st.active = true
        onDispose {
            st.active = false
            CoglasWeb.onSiteScan = null
            CoglasWeb.scanClosed()
            CoglasWeb.camLauncher = null
            CoglasWeb.detach()
        }
    }

    // «Назад» на телефоне: сначала шаг внутри Coglas (заказ → список, страница сайта назад), потом выход из окна
    BackHandler {
        when {
            st.tab == 0 && st.appPage == "orders" && st.openOrder != null -> st.closeOrder()
            st.tab == 0 && st.appPage.isNotEmpty() -> st.appPage = ""
            st.tab == 1 && CoglasWeb.canGoBack -> CoglasWeb.goBack()
            else -> onBack()
        }
    }

    if (scanFor.isNotEmpty()) {
        val target = scanFor
        ScannerScreen(
            prefs = prefs,
            onResult = { code ->
                scanFor = ""
                CoglasWeb.scanClosed()
                if (target == "site") {
                    st.tab = 1
                    CoglasWeb.deliverScan(code)
                } else {
                    st.tab = 0
                    st.appPage = "info"
                    st.query = code
                    st.search(code)
                }
            },
            onClose = { scanFor = ""; CoglasWeb.scanClosed() }
        )
        return
    }

    // «Настройки» видит только админ
    val tab = if (st.tab == 2 && !isAdmin) 1 else st.tab
    // экран не гаснет, пока сайт грузится и переводится (иначе при переводе телефон «засыпает»)
    KeepScreenOn(resetKey = CoglasWeb.progress / 20 + CoglasWeb.trBusy + CoglasScan.done)
    // после возврата в приложение будим WebView, чтобы сайт не оставался пустым
    val lifecycleOwner = LocalContext.current.findFragmentActivity()
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) return@DisposableEffect onDispose { }
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) CoglasWeb.wake()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CgTopBar(
            tab = tab,
            appPage = st.appPage,
            desktop = CoglasWeb.desktop,
            onToggleView = {
                val on = !CoglasWeb.desktop
                CoglasWeb.setDesktop(ctx, on)
                Toast.makeText(ctx, if (on) "Вид: ПК (как на мониторе). Масштабируйте щипком." else "Вид: мобильный", Toast.LENGTH_SHORT).show()
            },
            onBack = onBack,
            onRefresh = {
                when (tab) {
                    0 -> when (st.appPage) {
                        "orders" -> if (st.openOrder != null) st.open(st.openOrder!!) else st.loadOrders()
                        "info" -> if (st.scanned.isNotEmpty()) st.search(st.scanned)
                        else -> { if (st.appPage.startsWith("menu:")) CgList.load(st.appPage.removePrefix("menu:")) }
                    }
                    else -> CoglasWeb.reload()
                }
            },
            onHome = { st.tab = 1; CoglasWeb.home() }
        )
        if (tab == 1 && CoglasWeb.progress in 1..99) {
            LinearProgressIndicator(progress = { CoglasWeb.progress / 100f }, modifier = Modifier.fillMaxWidth())
        } else if (tab == 1 && CoglasWeb.trOn && CoglasWeb.trBusy > 0) {
            // страница уже загружена, идёт перевод — показываем это, а не «зависший» сайт
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // сайт всегда живёт под родными окнами: у него общая сессия входа, через неё идут запросы заказов
            key(CoglasWeb.viewKey) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        CoglasWeb.view(c).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    // update вызывается при каждой перекомпоновке (а прогресс загрузки меняется десятки раз) —
                    // тяжёлых вызовов здесь быть не должно, иначе прокрутка сайта дёргается
                    update = { w ->
                        if (w.visibility != android.view.View.VISIBLE) w.visibility = android.view.View.VISIBLE
                    }
                )
            }

            if (tab == 1 && CoglasWeb.loadError.isNotEmpty()) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(CoglasWeb.loadError, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { CoglasWeb.reload() }) { Text("Повторить") }
                }
            }

            if (tab != 1) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (tab) {
                        0 -> AppTab(onCamera = { scanFor = "info" }, goSite = { st.tab = 1 })
                        else -> SettingsTab { st.tab = 1 }
                    }
                }
            }
        }

        NavigationBar {
            NavigationBarItem(
                selected = tab == 0, onClick = { st.tab = 0 },
                icon = { Icon(Icons.Default.Menu, contentDescription = "App") }, label = { Text("App") }
            )
            NavigationBarItem(
                selected = tab == 1, onClick = { st.tab = 1 },
                icon = { Icon(Icons.Default.Home, contentDescription = "Сайт") }, label = { Text("Сайт") }
            )
            if (isAdmin) {
                NavigationBarItem(
                    selected = tab == 2, onClick = { st.tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") }, label = { Text("Настройки") }
                )
            }
        }
    }
}

@Composable
private fun CgTopBar(tab: Int, appPage: String, desktop: Boolean, onToggleView: () -> Unit, onBack: () -> Unit, onRefresh: () -> Unit, onHome: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Column(Modifier.weight(1f)) {
                Text("Coglas", style = MaterialTheme.typography.titleLarge)
                val sub = when (tab) {
                    0 -> when (appPage) {
                        "orders" -> "App · заказы на приход"
                        "info" -> "App · Infoscan: штрихкод или номер LE"
                        else -> if (appPage.startsWith("menu:")) "App · " + (CoglasSchema.find(appPage.removePrefix("menu:"))?.ru ?: "") else "App"
                    }
                    1 -> (if (CoglasWeb.trOn && !CoglasWeb.trSafe) "Сайт · перевод на русский" else if (CoglasWeb.trOn) "Сайт · без перевода (авария)" else "Сайт · оригинал (немецкий)") +
                        (if (CoglasWeb.desktop) " · ПК" else "")
                    else -> "Настройки (админ)"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tab == 1) {
                TextButton(onClick = onToggleView) { Text(if (desktop) "ПК" else "Моб.") }
            }
            if (tab == 1 || (tab == 0 && appPage.isNotEmpty())) {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Обновить") }
            }
            if (tab == 1) {
                IconButton(onClick = onHome) { Icon(Icons.Default.Home, contentDescription = "Главная Coglas") }
            }
        }
    }
}

// ---------------------------------------------------------------- общие кусочки

@Composable
private fun CgLoading(text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CgErrorBox(err: CgError, onRetry: () -> Unit, goSite: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(err.message ?: "Ошибка", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        if (err.needSite) {
            Button(onClick = goSite, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Открыть сайт и войти") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Повторить") }
        } else {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Повторить") }
        }
    }
}

@Composable
private fun CgCardView(card: CgCard) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            card.lines.forEach {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------------------------------------------------------- заказы

@Composable
private fun OrdersTab(goSite: () -> Unit) {
    val st = CoglasState
    LaunchedEffect(Unit) {
        if (st.orders == null && !st.ordersBusy && st.ordersErr == null) st.loadOrders()
    }
    if (st.openOrder != null) {
        OrderDetailView(goSite)
        return
    }
    val page = st.orders
    val err = st.ordersErr
    when {
        page == null && err != null -> CgErrorBox(err, onRetry = { st.loadOrders() }, goSite = goSite)
        page == null -> CgLoading("Загружаю заказы…")
        else -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (page.items.isEmpty()) "Заказов нет" else "Заказов: ${page.items.size} из ${page.total}",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                    )
                    if (st.ordersBusy) CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = { st.loadOrders() }) { Text("Обновить") }
                }
            }
            if (err != null) {
                item { Text(err.message ?: "Ошибка", color = MaterialTheme.colorScheme.error) }
            }
            items(page.items) { o ->
                Card(
                    onClick = { st.open(o) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(o.number, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (o.supplier.isNotEmpty()) Text(o.supplier, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        val meta = listOfNotNull(
                            o.planned.takeIf { it.isNotEmpty() }?.let { "план $it" },
                            o.status.takeIf { it.isNotEmpty() },
                            o.positions.takeIf { it >= 0 }?.let { "поз. $it" }
                        ).joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderDetailView(goSite: () -> Unit) {
    val st = CoglasState
    val o = st.openOrder ?: return
    val d = st.detail
    val err = st.detailErr
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { st.closeOrder() }) { Text("← К списку") }
            Spacer(Modifier.weight(1f))
            Text(o.number, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 12.dp))
        }
        when {
            err != null -> CgErrorBox(err, onRetry = { st.open(o) }, goSite = goSite)
            d == null -> CgLoading("Загружаю заказ ${o.number}…")
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Заказ ${d.number}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            d.header.forEach { (k, v) ->
                                Spacer(Modifier.height(6.dp))
                                Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Позиции: ${d.total} · ✓ готово ${d.done} · в работе ${d.proc} · создано ${d.created}" +
                            (if (d.shown < d.total) "\n(показано ${d.shown} из ${d.total})" else ""),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                items(d.positions) { p ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (p.done) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${p.number}. " + (if (p.done) "✓ " else "") + p.title,
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium
                            )
                            if (p.details.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text(p.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Infoscan

@Composable
private fun InfoscanTab(onCamera: () -> Unit, goSite: () -> Unit) {
    val st = CoglasState
    val focus = LocalFocusManager.current
    val doSearch = { focus.clearFocus(); st.search(st.query) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = st.query,
            onValueChange = { st.query = it },
            label = { Text("Штрихкод / номер LE") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() })
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = doSearch, enabled = !st.scanBusy, modifier = Modifier.weight(1f).height(52.dp)) { Text("Найти") }
            OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f).height(52.dp)) { Text("Сканировать") }
        }
        Spacer(Modifier.height(8.dp))

        val scan = st.scan
        val err = st.scanErr
        when {
            st.scanBusy -> CgLoading("Ищу: ${st.scanned} …")
            err != null -> CgErrorBox(err, onRetry = { st.search(st.scanned) }, goSite = goSite)
            scan == null -> Text(
                "Введите штрихкод или номер LE (паллеты, ящика) либо отсканируйте камерой — покажу, что это, где лежит и что внутри.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            scan.entities.isEmpty() -> Text("Ничего не найдено по коду: ${st.scanned}", style = MaterialTheme.typography.bodyLarge)
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(scan.entities) { CgCardView(it) }
                if (scan.contents.isNotEmpty() || scan.contentTotal > 0) {
                    item {
                        Text("Содержимое (${scan.contentTotal})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    items(scan.contents) { CgCardView(it) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- вкладка App

@Composable
private fun AppTab(onCamera: () -> Unit, goSite: () -> Unit) {
    val st = CoglasState
    when (st.appPage) {
        "orders" -> OrdersTab(goSite)
        "info" -> InfoscanTab(onCamera, goSite)
        else -> if (st.appPage.startsWith("menu:")) MenuWorkScreen(st.appPage.removePrefix("menu:")) else AppHome()
    }
}

/** Пункт меню System сайта: de — немецкое название на сайте, ru — название в App. */
internal data class MenuItem(val de: String, val ru: String)
internal data class MenuGroup(val title: String, val items: List<MenuItem>)

/** Меню System сайта Coglas (снято с окна System). Группы названы по смыслу и порядку пунктов. */
internal val SYSTEM_MENU = listOf(
    MenuGroup("Справочники", listOf(
        MenuItem("Anhänge", "Вложения"),
        MenuItem("Artikel", "Артикулы"),
        MenuItem("Checklistenvorlagen", "Шаблоны чек-листов"),
        MenuItem("Geschäftspartner", "Партнёры"),
        MenuItem("Ladehilfsmitteltypen", "Типы паллет и тары")
    )),
    MenuGroup("Рабочее место", listOf(
        MenuItem("Aktuelle Arbeitsstation", "Текущая рабочая станция"),
        MenuItem("Arbeitsstationen", "Рабочие станции"),
        MenuItem("Bildschirmskalierung", "Масштаб экрана"),
        MenuItem("Mein Profil", "Мой профиль")
    )),
    MenuGroup("Склад", listOf(
        MenuItem("Infoscan", "Инфоскан"),
        MenuItem("Lagerübersicht", "Обзор склада"),
        MenuItem("Shopfloor", "Shopfloor"),
        MenuItem("Bestandsbewegungen", "Движение запасов"),
        MenuItem("Chargenverfolgung", "Отслеживание партий"),
        MenuItem("Inventuraufträge", "Задания инвентаризации"),
        MenuItem("Inventurlisten", "Списки инвентаризации"),
        MenuItem("Inventurzählung", "Подсчёт инвентаризации"),
        MenuItem("Vorwarnzeit Haltbarkeit", "Предупреждение о сроке годности")
    )),
    MenuGroup("Приход", listOf(
        MenuItem("Bestellungen", "Заказы"),
        MenuItem("Lieferscheine", "Накладные"),
        MenuItem("Wareneingänge", "Приходы товара")
    )),
    MenuGroup("Комплектация", listOf(
        MenuItem("Kommissionierleitstand", "Пульт комплектации"),
        MenuItem("Kommissionierung", "Комплектация")
    )),
    MenuGroup("Перемещение и транспорт", listOf(
        MenuItem("Lagereinheit umlagern", "Переместить складскую единицу"),
        MenuItem("Transport", "Транспорт"),
        MenuItem("Transportleitstand", "Пульт транспорта"),
        MenuItem("Vorholungsbedarfe", "Потребность в подвозе")
    )),
    MenuGroup("Отгрузка", listOf(
        MenuItem("Kundenaufträge", "Заказы клиентов"),
        MenuItem("Scan, Label & Ship", "Скан, этикетка и отправка"),
        MenuItem("Verladung", "Погрузка"),
        MenuItem("Verpackung", "Упаковка"),
        MenuItem("Warenausgänge", "Отгрузки")
    )),
    MenuGroup("Прочее", listOf(
        MenuItem("About", "О программе")
    ))
)

/** Карточка админа: собрать данные сайта (меню System и его страницы) для построения экранов App. */
@Composable
internal fun ScanCard() {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val ver = CoglasSchema.version
    val all = CoglasSchema.all()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Данные сайта для App", style = MaterialTheme.typography.titleSmall)
            Text(
                "Страниц собрано: ${CoglasSchema.scannedCount()} из ${all.size}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (CoglasScan.running) {
                val p = if (CoglasScan.total > 0) CoglasScan.done / CoglasScan.total.toFloat() else 0f
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Text(CoglasScan.status, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { CoglasScan.stop() }) { Text("Остановить") }
            } else {
                if (CoglasScan.status.isNotEmpty()) Text(CoglasScan.status, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { CoglasScan.start(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Собрать данные сайта") }
                if (CoglasSchema.scannedCount() > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { cgShare(ctx, CoglasSchema.report()) }, modifier = Modifier.weight(1f)) { Text("Отправить данные") }
                        OutlinedButton(onClick = { cgCopy(ctx, CoglasSchema.report()) }, modifier = Modifier.weight(1f)) { Text("Копировать") }
                    }
                }
            }
            Text(
                "Сканер только открывает пункты меню System и читает заголовки, кнопки, вкладки, столбцы и названия полей. " +
                    "Ничего не нажимает, не вводит и не сохраняет на сайте; значения из таблиц не читает. " +
                    "Войдите на вкладке «Сайт» и не закрывайте окно Coglas, пока идёт сбор.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------- настройки (только админ)

private fun cgCopy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("coglas", text))
    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
}

private fun cgShare(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(i, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun PageLines(label: String, v: List<String>) {
    if (v.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(v.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PageCard(p: CgPage) {
    var open by remember(p.key) { mutableStateOf(false) }
    val app = p.inApp()
    Card(
        onClick = { open = !open },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (app.isNotEmpty()) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(p.title.ifEmpty { p.key }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(p.key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                (if (app.isEmpty()) "В App: нет — нужно сделать" else "В App: есть ($app)") +
                    " · заходов ${p.visits} · API ${p.apis.size}",
                style = MaterialTheme.typography.bodySmall,
                color = if (app.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (open) {
                PageLines("Меню", p.menu)
                PageLines("Заголовки", p.headings)
                PageLines("Кнопки", p.buttons)
                PageLines("Поля", p.fields)
                if (p.inputs > 0 || p.tables > 0) PageLines("Элементы", listOf("полей ввода ${p.inputs}", "таблиц ${p.tables}"))
                PageLines("Запросы API", p.apis)
            }
        }
    }
}

@Composable
private fun SettingsTab(goSite: () -> Unit) {
    val st = CoglasState
    val ctx = LocalContext.current
    var clickDialog by remember { mutableStateOf(false) }
    var clickText by remember { mutableStateOf("") }
    var dev by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val ver = CoglasPages.version
    val pages = CoglasPages.list()

    val result = st.tool
    if (result.isNotEmpty()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Surface(
                Modifier.weight(1f).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
            ) {
                SelectionContainer {
                    Text(
                        result,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { cgCopy(ctx, result) }, modifier = Modifier.weight(1f)) { Text("Копировать") }
                OutlinedButton(onClick = { cgShare(ctx, result) }, modifier = Modifier.weight(1f)) { Text("Поделиться") }
                Button(onClick = { st.tool = "" }, modifier = Modifier.weight(1f)) { Text("Закрыть") }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- перевод сайта ----
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Перевод сайта на русский", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Словарь + ИИ. Артикулы, коды и номера не меняются.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = CoglasWeb.trOn, onCheckedChange = { CoglasWeb.setTranslate(ctx, it); st.tab = 1 })
                }
                val note = CoglasWeb.trNote
                Spacer(Modifier.height(6.dp))
                Text(
                    if (note.isNotEmpty()) note else "В памяти переводов: ${CoglasTr.cacheSize(ctx)} фраз",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (note.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { CoglasWeb.retryTranslate(true); st.tab = 1 },
                    enabled = CoglasWeb.trOn,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Перевести страницу полностью") }
                Text(
                    "Обычный перевод пропускает длинные строки, строки с цифрами и слова ЗАГЛАВНЫМИ. Эта кнопка переводит и их (до перезагрузки страницы).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { CoglasWeb.retryTranslate(false); st.tab = 1 }, modifier = Modifier.weight(1f)) { Text("Перевести заново") }
                    OutlinedButton(onClick = { CoglasTr.clearCache(ctx); CoglasWeb.trNote = "Память переводов очищена" }, modifier = Modifier.weight(1f)) { Text("Очистить память") }
                }
            }
        }

        // ---- вид сайта ----
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Вид сайта: ПК", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Сайт рисуется как на большом мониторе (широкая страница), масштаб меняется щипком. Тот же переключатель есть вверху вкладки «Сайт».",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = CoglasWeb.desktop, onCheckedChange = { CoglasWeb.setDesktop(ctx, it); st.tab = 1 })
            }
        }

        // ---- сканер ----
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Сканер приложения на сайте", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Когда сайт просит камеру, открывается сканер приложения (с ИИ), а код подставляется в поле страницы. Выключите, чтобы сайт использовал свою камеру.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = CoglasWeb.nativeScan, onCheckedChange = { CoglasWeb.setNativeScan(ctx, it) })
            }
        }

        // ---- страницы сайта для развития App ----
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Страницы сайта для развития App", style = MaterialTheme.typography.titleMedium)
                val todo = pages.count { it.inApp().isEmpty() }
                Text(
                    if (pages.isEmpty()) "Пока пусто. Походите по разделам во вкладке «Сайт» — страницы, кнопки, поля и запросы записываются сами."
                    else "Страниц: ${pages.size} · уже в App: ${pages.size - todo} · нужно сделать: $todo",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { CoglasWeb.snapshot { Toast.makeText(ctx, "Данные страницы сохранены", Toast.LENGTH_SHORT).show() } },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("Снять данные открытой страницы") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { st.tool = CoglasPages.report() },
                        enabled = pages.isNotEmpty(), modifier = Modifier.weight(1f)
                    ) { Text("Отчёт") }
                    OutlinedButton(
                        onClick = { showPages = !showPages },
                        enabled = pages.isNotEmpty(), modifier = Modifier.weight(1f)
                    ) { Text(if (showPages) "Скрыть список" else "Список") }
                    OutlinedButton(
                        onClick = { CoglasPages.clear() },
                        enabled = pages.isNotEmpty(), modifier = Modifier.weight(1f)
                    ) { Text("Очистить") }
                }
                if (showPages) pages.forEach { PageCard(it) }
            }
        }

        // ---- инструменты разработчика (свёрнуты) ----
        OutlinedButton(onClick = { dev = !dev }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(if (dev) "Скрыть инструменты разработчика" else "Инструменты разработчика")
        }
        if (dev) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { CoglasWeb.probe(ctx) { st.tool = it.ifEmpty { "Пусто" } } }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Разведка: кнопки и поля страницы")
                    }
                    Button(onClick = { CoglasWeb.pageText { st.tool = it.ifEmpty { "Пусто" } } }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Текст страницы")
                    }
                    Button(
                        onClick = {
                            val n = CoglasWeb.bridge.size()
                            st.tool = if (n == 0) "Лог пуст. Походите по сайту (откройте заказ, поиск и т.д.), затем снова нажмите «Лог запросов»."
                            else "Запросов в логе: $n\n(данные входа и токены скрыты)\n\n" + CoglasWeb.bridge.dump()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Лог запросов сайта") }
                    Button(onClick = { clickDialog = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Нажать кнопку по тексту") }
                    OutlinedButton(
                        onClick = { st.tool = if (CoglasWeb.lastRaw.isEmpty()) "Пусто" else CoglasWeb.lastRaw.take(60000) },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Raw: последний ответ сервера") }
                    OutlinedButton(
                        onClick = { CoglasWeb.bridge.clear(); Toast.makeText(ctx, "Лог очищен", Toast.LENGTH_SHORT).show() },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("Очистить лог") }
                }
            }
        }

        OutlinedButton(onClick = { CoglasWeb.home(); goSite() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Открыть главную Coglas")
        }
        HorizontalDivider()
    }

    if (clickDialog) {
        AlertDialog(
            onDismissRequest = { clickDialog = false },
            title = { Text("Нажать по тексту") },
            text = {
                OutlinedTextField(
                    value = clickText, onValueChange = { clickText = it }, singleLine = true,
                    label = { Text("Текст кнопки, например: Lager") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    val t = clickText.trim()
                    clickDialog = false
                    if (t.isNotEmpty()) CoglasWeb.click(ctx, t) { st.tool = it.ifEmpty { "Пусто" } }
                }) { Text("Нажать") }
            },
            dismissButton = { TextButton(onClick = { clickDialog = false }) { Text("Отмена") } }
        )
    }
}
