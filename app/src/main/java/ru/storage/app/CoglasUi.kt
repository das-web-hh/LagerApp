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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
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

    var tab by mutableIntStateOf(2) // 0 заказы, 1 Infoscan, 2 сайт, 3 инструменты; первый раз открывается сайт — нужно войти

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
    var scanning by remember { mutableStateOf(false) }

    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        CoglasWeb.camResult(it)
    }
    DisposableEffect(Unit) {
        CoglasWeb.camLauncher = { camLauncher.launch(Manifest.permission.CAMERA) }
        onDispose {
            CoglasWeb.camLauncher = null
            CoglasWeb.detach()
        }
    }

    // «Назад» на телефоне: сначала шаг внутри Coglas (заказ → список, страница сайта назад), потом выход из окна
    BackHandler {
        when {
            st.tab == 0 && st.openOrder != null -> st.closeOrder()
            st.tab == 2 && CoglasWeb.canGoBack -> CoglasWeb.goBack()
            else -> onBack()
        }
    }

    if (scanning) {
        ScannerScreen(
            prefs = prefs,
            onResult = { code ->
                scanning = false
                st.tab = 1
                st.query = code
                st.search(code)
            },
            onClose = { scanning = false }
        )
        return
    }

    val tab = st.tab
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CgTopBar(
            tab = tab,
            onBack = onBack,
            onRefresh = {
                when (tab) {
                    0 -> if (st.openOrder != null) st.open(st.openOrder!!) else st.loadOrders()
                    1 -> if (st.scanned.isNotEmpty()) st.search(st.scanned)
                    else -> CoglasWeb.reload()
                }
            },
            onHome = { st.tab = 2; CoglasWeb.home() }
        )
        if (tab == 2 && CoglasWeb.progress in 1..99) {
            LinearProgressIndicator(progress = { CoglasWeb.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // сайт всегда живёт под родными окнами: у него общая сессия входа, через неё идут запросы заказов
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { c -> CoglasWeb.view(c) })

            if (tab == 2 && CoglasWeb.loadError.isNotEmpty()) {
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

            if (tab != 2) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (tab) {
                        0 -> OrdersTab { st.tab = 2 }
                        1 -> InfoscanTab(onCamera = { scanning = true }) { st.tab = 2 }
                        else -> ToolsTab { st.tab = 2 }
                    }
                }
            }
        }

        NavigationBar {
            NavigationBarItem(
                selected = tab == 0, onClick = { st.tab = 0 },
                icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Заказы") }, label = { Text("Заказы") }
            )
            NavigationBarItem(
                selected = tab == 1, onClick = { st.tab = 1 },
                icon = { Icon(Icons.Default.Search, contentDescription = "Infoscan") }, label = { Text("Infoscan") }
            )
            NavigationBarItem(
                selected = tab == 2, onClick = { st.tab = 2 },
                icon = { Icon(Icons.Default.Home, contentDescription = "Сайт") }, label = { Text("Сайт") }
            )
            NavigationBarItem(
                selected = tab == 3, onClick = { st.tab = 3 },
                icon = { Icon(Icons.Default.Build, contentDescription = "Инструменты") }, label = { Text("Инструменты") }
            )
        }
    }
}

@Composable
private fun CgTopBar(tab: Int, onBack: () -> Unit, onRefresh: () -> Unit, onHome: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Column(Modifier.weight(1f)) {
                Text("Coglas", style = MaterialTheme.typography.titleLarge)
                val sub = when (tab) {
                    0 -> "Заказы на приход"
                    1 -> "Infoscan: штрихкод или номер LE"
                    2 -> if (CoglasWeb.trOn) "Сайт · перевод на русский" else "Сайт · оригинал (немецкий)"
                    else -> "Инструменты и перевод"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (tab != 3) {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Обновить") }
            }
            if (tab == 2) {
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

// ---------------------------------------------------------------- инструменты

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
private fun ToolsTab(goSite: () -> Unit) {
    val st = CoglasState
    val ctx = LocalContext.current
    var clickDialog by remember { mutableStateOf(false) }
    var clickText by remember { mutableStateOf("") }

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
        // ---- перевод ----
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Перевод на русский", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Сайт переводится словарём и ИИ. Названия товаров, артикулы и номера не меняются.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = CoglasWeb.trOn, onCheckedChange = { CoglasWeb.setTranslate(ctx, it); st.tab = 2 })
                }
                val note = CoglasWeb.trNote
                Spacer(Modifier.height(6.dp))
                Text(
                    if (note.isNotEmpty()) note else "В памяти переводов: ${CoglasTr.cacheSize(ctx)} фраз",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (note.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { CoglasWeb.retryTranslate(); st.tab = 2 }, modifier = Modifier.weight(1f)) { Text("Перевести заново") }
                    OutlinedButton(onClick = { CoglasTr.clearCache(ctx); CoglasWeb.trNote = "Память переводов очищена" }, modifier = Modifier.weight(1f)) { Text("Очистить память") }
                }
            }
        }

        // ---- сайт ----
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Инструменты сайта", style = MaterialTheme.typography.titleMedium)
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

        OutlinedButton(onClick = { CoglasWeb.home(); goSite() }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Открыть главную Coglas")
        }
        HorizontalDivider()
        Text(
            "Заказы и Infoscan работают через вход на вкладке «Сайт»: войдите один раз, и сессия общая для всего окна Coglas.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
