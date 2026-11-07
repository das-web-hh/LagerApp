package ru.storage.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val UNITS = listOf("кг", "г", "т", "л", "мл", "шт")
private val PACKS = listOf("Бутылка", "Коробка", "Мешок", "Банка", "Пачка", "Ведро", "Канистра", "Пакет", "Тюк")

@Composable
fun DropdownField(value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(value) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

@Composable
fun CatalogScreen(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val sync = remember { SyncState() }
    var list by remember { mutableStateOf(emptyList<Product>()) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<List<String>>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var camList by remember { mutableStateOf(false) } // камера «Скан списка»: этикетки и списки в каталог
    var found by remember { mutableStateOf<List<Product>?>(null) }

    LaunchedEffect(query, sync.version) {
        found = if (query.trim().length < MIN_SEARCH_CHARS) null
        else withContext(Dispatchers.IO) { db.search(query, 2000) }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Media.uploadPending(ctx, db) }
        DriveSync.start(ctx, false)
        Cloud.runProducts(db, sync, false)
    }
    LaunchedEffect(sync.version) { list = withContext(Dispatchers.IO) { db.allProducts() } }

    var initCols by remember { mutableStateOf<IntArray?>(null) }
    var initHeader by remember { mutableStateOf<Boolean?>(null) }

    // картинка, скриншот, PDF или снимок камеры: распознаёт телефон, а если не вышло, то ИИ; дальше выбор столбцов
    fun startOcr(uri: Uri) {
        scope.launch {
            sync.busy = true
            sync.text = "Распознаю список…"
            try {
                val t = CatalogOcr.read(ctx, uri) { sync.text = it }
                initCols = t.cols
                initHeader = t.header
                rows = t.rows
                sync.text = (if (t.viaAi) "Распознано через ИИ: " else "Распознано на телефоне: ") + "строк ${t.rows.size}. Проверьте столбцы."
            } catch (e: Exception) {
                sync.text = e.message ?: "Не удалось распознать список"
            } finally {
                sync.busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val type = ctx.contentResolver.getType(uri).orEmpty()
            if (type.startsWith("image/") || type == "application/pdf") {
                initCols = null; initHeader = null
                startOcr(uri)
            } else scope.launch {
                val data = withContext(Dispatchers.IO) {
                    try { Xlsx.read(ctx, uri) } catch (e: Exception) { emptyList() }
                }
                initCols = null; initHeader = null
                if (data.isEmpty()) sync.text = "Не удалось прочитать файл (Excel .xlsx, картинка или PDF)" else rows = data
            }
        }
    }
    val camScan = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf?.uri?.let { startOcr(it) }
        }
    }

    if (camList) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { camList = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            Surface(Modifier.fillMaxSize()) {
                ListPhotoScreen(
                    catalogMode = true,
                    onCancel = { camList = false },
                    onDone = { list ->
                        camList = false
                        // найденное идёт в обычное окно импорта: там видно столбцы, совпадающие товары обновляются, а не дублируются
                        val data = ArrayList<List<String>>()
                        data.add(listOf("Артикул", "Название", "Штрих-код"))
                        for (l in list.asReversed()) if (l.name.isNotBlank()) data.add(listOf(l.article, l.name, l.barcode))
                        if (data.size < 2) {
                            Toast.makeText(ctx, "Нет товаров с названием: ничего не импортируется", Toast.LENGTH_LONG).show()
                        } else {
                            initCols = intArrayOf(1, 2, 0)
                            initHeader = true
                            rows = data
                        }
                    }
                )
            }
        }
    }
    if (scanning) {
        ScannerScreen(prefs = prefs, onResult = { query = it; scanning = false }, onClose = { scanning = false })
        return
    }
    if (openId != null || creating) {
        ProductCardScreen(openId, prefs) {
            openId = null
            creating = false
            sync.version++
        }
        return
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Каталог товаров", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        SearchBar(
            query = query,
            onChange = { query = it },
            onScan = { scanning = true },
            placeholder = "Поиск в каталоге"
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Session.isAdmin) {
                Button(onClick = { creating = true }) { Text("+ Товар") }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📥 Импорт") }
                OutlinedButton(onClick = { camList = true }) { Text("📷 Скан списка") }
                OutlinedButton(
                    enabled = !sync.busy,
                    onClick = {
                        scope.launch {
                            sync.busy = true
                            val n = withContext(Dispatchers.IO) { ProductGroups.link(db) }
                            sync.busy = false
                            sync.text = if (n > 0) "Объединено одинаковых товаров, изменено карточек: $n" else "Новых одинаковых товаров не найдено"
                            sync.version++
                        }
                    }
                ) { Text("🔗 Объединить одинаковые") }
            }
            OutlinedButton(
                onClick = { scope.launch { Cloud.runProducts(db, sync, true) } },
                enabled = !sync.busy
            ) { Text("Обновить") }
        }
        if (DriveSync.status.isNotBlank()) {
            Text(DriveSync.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        if (sync.text.isNotBlank() || sync.busy) {
            Text(
                if (sync.busy && sync.text.isBlank()) "Проверка…" else sync.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(found ?: list, key = { it.id }) { p ->
                Text(
                    (if (p.groupId.isNotBlank()) "🔗 " else "") + p.name,
                    modifier = Modifier.fillMaxWidth().clickable { openId = p.id }.padding(vertical = 7.dp)
                )
                HorizontalDivider()
            }
        }
    }

    rows?.let { data ->
        ImportDialog(
            rows = data,
            initial = initCols,
            initialHeader = initHeader,
            onCancel = { rows = null },
            onImport = { nc, bc, ac, header ->
                rows = null
                scope.launch {
                    sync.busy = true
                    val n = withContext(Dispatchers.IO) { importRows(db, data, nc, bc, ac, header) }
                    sync.busy = false
                    sync.text = "Импортировано: $n"
                    sync.version++
                }
            }
        )
    }
}

private fun importRows(db: ProductDb, rows: List<List<String>>, nc: Int, bc: Int, ac: Int, header: Boolean): Int {
    val out = LinkedHashMap<String, Product>()
    val byBarcode = HashMap<String, Product>()
    val byArticle = HashMap<String, Product>()
    val now = System.currentTimeMillis()
    for ((i, r) in rows.withIndex()) {
        if (header && i == 0) continue
        val name = r.getOrNull(nc)?.trim().orEmpty()
        if (name.isEmpty()) continue
        val barcode = if (bc >= 0) r.getOrNull(bc)?.trim().orEmpty() else ""
        val article = if (ac >= 0) r.getOrNull(ac)?.trim().orEmpty() else ""
        val existing = (if (barcode.isNotEmpty()) byBarcode[barcode] ?: db.findByBarcode(barcode) else null)
            ?: (if (article.isNotEmpty()) byArticle[article] ?: db.findByArticle(article) else null)
        val local = if (existing == null) ProductParse.parseLocal(listOf(name)).firstOrNull() else null
        val base = existing ?: Product(
            UUID.randomUUID().toString(), name, "", "", local?.size ?: "", local?.unit ?: "", local?.pack ?: "",
            local?.manufacturer ?: "", emptyList(), 0L
        )
        val p = base.copy(
            name = name,
            article = if (article.isNotEmpty()) article else base.article,
            barcode = if (barcode.isNotEmpty()) barcode else base.barcode,
            updatedAt = now
        )
        out[p.id] = p
        if (p.barcode.isNotEmpty()) byBarcode[p.barcode] = p
        if (p.article.isNotEmpty()) byArticle[p.article] = p
    }
    val all = out.values.toList()
    db.upsertProducts(all)
    Cloud.pushProducts(all)
    return all.size
}

private fun colName(i: Int): String {
    var n = i
    val sb = StringBuilder()
    do {
        sb.insert(0, 'A' + n % 26)
        n = n / 26 - 1
    } while (n >= 0)
    return sb.toString()
}

@Composable
private fun ColumnPicker(
    label: String,
    width: Int,
    selected: Int,
    allowNone: Boolean,
    head: List<String>,
    onSelect: (Int) -> Unit
) {
    fun lab(i: Int) = if (i < 0) "— не брать —" else "${colName(i)}: ${head.getOrElse(i) { "" }.take(16)}"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        var open by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { open = true }) { Text(lab(selected)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                if (allowNone) DropdownMenuItem(text = { Text(lab(-1)) }, onClick = { onSelect(-1); open = false })
                for (i in 0 until width) {
                    DropdownMenuItem(text = { Text(lab(i)) }, onClick = { onSelect(i); open = false })
                }
            }
        }
    }
}

@Composable
fun ImportDialog(
    rows: List<List<String>>,
    onCancel: () -> Unit,
    onImport: (Int, Int, Int, Boolean) -> Unit,
    /** столбцы, найденные при распознавании: (название, штрих-код, артикул) */
    initial: IntArray? = null,
    initialHeader: Boolean? = null
) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("import", Context.MODE_PRIVATE) }
    val width = rows.maxOf { it.size }
    var nameCol by remember { mutableIntStateOf((initial?.get(0) ?: sp.getInt("name", 0)).coerceAtMost(width - 1)) }
    var barcodeCol by remember { mutableIntStateOf((initial?.get(1) ?: sp.getInt("barcode", -1)).coerceAtMost(width - 1)) }
    var articleCol by remember { mutableIntStateOf((initial?.get(2) ?: sp.getInt("article", -1)).coerceAtMost(width - 1)) }
    var header by remember { mutableStateOf(initialHeader ?: sp.getBoolean("header", true)) }
    val head = rows.first()
    val sample = rows.getOrNull(if (header) 1 else 0)

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Настройка таблицы") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Строк в файле: ${rows.size}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = header, onCheckedChange = { header = it })
                    Text("Первая строка — заголовки")
                }
                ColumnPicker("Название *", width, nameCol, false, head) { nameCol = it }
                ColumnPicker("Штрих-код", width, barcodeCol, true, head) { barcodeCol = it }
                ColumnPicker("Артикул", width, articleCol, true, head) { articleCol = it }
                if (sample != null) {
                    Text(
                        "Пример: " + listOf(nameCol, barcodeCol, articleCol)
                            .filter { it >= 0 }.joinToString(" | ") { sample.getOrElse(it) { "" } },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                sp.edit().putInt("name", nameCol).putInt("barcode", barcodeCol)
                    .putInt("article", articleCol).putBoolean("header", header).apply()
                onImport(nameCol, barcodeCol, articleCol, header)
            }) { Text("Импортировать") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } }
    )
}

@Composable
fun ProductCardScreen(id: String?, prefs: Prefs, onClose: () -> Unit) {
    val canEdit = Session.isAdmin
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Product?>(null) }
    var name by remember { mutableStateOf("") }
    var article by remember { mutableStateOf("") }
    var webArticle by remember { mutableStateOf("") }
    var strohDialog by remember { mutableStateOf(false) }
    var barcode by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("кг") }
    var pack by remember { mutableStateOf("") }
    var manufacturer by remember { mutableStateOf("") }
    val photos = remember { mutableStateListOf<String>() }
    var scanning by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var groupMembers by remember { mutableStateOf<List<Product>>(emptyList()) }
    var groupTick by remember { mutableStateOf(0) }
    var mergeDialog by remember { mutableStateOf(false) }
    var mergeQuery by remember { mutableStateOf("") }
    var splitAsk by remember { mutableStateOf<Product?>(null) }

    LaunchedEffect(id, groupTick) {
        groupMembers = if (id == null) emptyList() else withContext(Dispatchers.IO) {
            val cur = db.getProduct(id)
            if (cur == null || cur.groupId.isBlank()) emptyList() else db.byGroup(cur.groupId)
        }
    }

    LaunchedEffect(id) {
        if (id != null) {
            val p = withContext(Dispatchers.IO) { db.getProduct(id) }
            if (p != null) {
                original = p
                name = p.name; article = p.article; webArticle = p.webArticle; barcode = p.barcode
                weight = p.weight; unit = p.unit.ifBlank { "кг" }
                pack = p.pack; manufacturer = p.manufacturer
                photos.clear(); photos.addAll(p.photos)
            }
        }
    }

    if (scanning) {
        ScannerScreen(prefs = prefs, onResult = { barcode = it; scanning = false }, onClose = { scanning = false })
        return
    }
    if (showHistory && id != null) {
        ProductHistoryScreen(id) { showHistory = false }
        return
    }

    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            if (id == null) "Новый товар" else "Карточка товара",
            style = MaterialTheme.typography.headlineSmall
        )
        if (id != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showHistory = true }, modifier = Modifier.fillMaxWidth()) {
                Text("История приёма товара")
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(name, { name = germanKeys(it) }, label = { Text("Название") }, readOnly = !canEdit, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        if (canEdit) OutlinedButton(
            enabled = !parsing && name.isNotBlank(),
            onClick = {
                if (!AiCfg.ready(ctx)) {
                    error = "Не задан ключ ИИ. Откройте Профиль → Настройки → Распознавание документов."
                    return@OutlinedButton
                }
                error = ""
                parsing = true
                val src = name
                scope.launch {
                    try {
                        val r = withContext(Dispatchers.IO) { ProductParse.parse(ctx, src, manufacturer) }
                        if (r.name.isNotBlank()) name = r.name
                        if (r.manufacturer.isNotBlank()) manufacturer = r.manufacturer
                        if (r.size.isNotBlank()) weight = r.size
                        if (r.unit.isNotBlank()) unit = r.unit
                        if (r.pack.isNotBlank()) pack = r.pack
                    } catch (e: Exception) {
                        error = e.message ?: "Ошибка разбора"
                    }
                    parsing = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (parsing) "Разбираю…" else "Разобрать") }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                weight, { weight = it }, label = { Text("Вес / объём") }, singleLine = true, readOnly = !canEdit,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            if (canEdit) DropdownField(unit, UNITS) { unit = it } else Text(unit)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pack, { pack = it }, label = { Text("Тип (бутылка, коробка, мешок…)") }, readOnly = !canEdit,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (canEdit) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            PACKS.forEach { p -> TextButton(onClick = { pack = p }) { Text(p) } }
        }
        OutlinedTextField(manufacturer, { manufacturer = germanKeys(it) }, label = { Text("Производитель") }, readOnly = !canEdit,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(article, { article = it }, label = { Text("Артикул") }, readOnly = !canEdit,
            singleLine = true, modifier = Modifier.fillMaxWidth())
        // второй уровень: артикул с сайта stroeh.de. Свой артикул (поле выше: ввод, скан, накладная) всегда главнее.
        if (webArticle.isNotBlank()) {
            Text(
                "Артикул с сайта stroeh.de: $webArticle — " +
                    if (article.isBlank()) "используется, пока нет своего артикула" else "запасной, главнее свой артикул",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (canEdit) Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { strohDialog = true }, modifier = Modifier.weight(1f)) {
                Text(if (webArticle.isBlank()) "🔎 Найти артикул на stroeh.de" else "🔎 Найти другой на stroeh.de")
            }
            if (webArticle.isNotBlank()) TextButton(onClick = { webArticle = "" }) { Text("Убрать") }
        }
        if (strohDialog) {
            StrohSearchDialog(
                initialQuery = name,
                onPick = { webArticle = it; strohDialog = false },
                onDismiss = { strohDialog = false }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(barcode, { barcode = it }, label = { Text("Штрих-код") }, readOnly = !canEdit,
                singleLine = true, modifier = Modifier.weight(1f))
            if (canEdit) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { scanning = true }) { Text("📷 Скан") }
            }
        }
        if (id != null) {
            Spacer(Modifier.height(16.dp))
            Text("Одинаковый товар под другими названиями", style = MaterialTheme.typography.titleSmall)
            if (groupMembers.size < 2) {
                Text("Не объединён с другими карточками", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Эти карточки показываются как один товар, приёмы собраны вместе.", style = MaterialTheme.typography.bodySmall)
                groupMembers.forEach { m ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.name + if (m.id == id) "  (эта карточка)" else "",
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                        )
                        if (canEdit) TextButton(onClick = { splitAsk = m }) { Text("Отделить") }
                    }
                }
            }
            if (canEdit) OutlinedButton(onClick = { mergeQuery = ""; mergeDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text("🔗 Объединить с другой карточкой…")
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Фото", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        MediaStrip(photos, if (canEdit) fun(n: String) { photos.remove(n) } else null)
        if (canEdit) {
            Spacer(Modifier.height(8.dp))
            MediaButtons(kind = "PR", allowFile = false) { photos.add(it) }
        }

        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        if (canEdit) Button(
            onClick = {
                if (name.isBlank()) {
                    error = "Введите название"
                } else {
                    val cur = photos.toList()
                    val old = original
                    val p = Product(
                        id = old?.id ?: UUID.randomUUID().toString(),
                        name = name.trim(), article = article.trim(), barcode = barcode.trim(),
                        weight = weight.trim().replace(',', '.'), unit = unit, pack = pack.trim(),
                        manufacturer = manufacturer.trim(), photos = cur, updatedAt = 0L,
                        webArticle = webArticle.trim()
                    )
                    scope.launch(Dispatchers.IO) {
                        // группа могла измениться кнопками «Объединить»/«Отделить» уже после открытия карточки — берём свежую
                        val fresh = old?.let { db.getProduct(it.id) }
                        Cloud.saveProduct(db, p.copy(groupId = fresh?.groupId ?: "", apart = fresh?.apart ?: emptyList()))
                        Media.discard(ctx, db, (old?.photos ?: emptyList()).filter { it !in cur })
                        Media.uploadPending(ctx, db)
                        withContext(Dispatchers.Main) { onClose() }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить") }
        if (id != null && canEdit) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Удалить товар")
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить товар?") },
            text = { Text(name) },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    val old = original
                    scope.launch(Dispatchers.IO) {
                        if (id != null) Cloud.deleteProduct(db, id)
                        Media.discard(ctx, db, old?.photos ?: emptyList())
                        withContext(Dispatchers.Main) { onClose() }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }

    splitAsk?.let { m ->
        AlertDialog(
            onDismissRequest = { splitAsk = null },
            title = { Text("Отделить от группы?") },
            text = { Text(m.name + "\n\nОна станет отдельным товаром и автоматически больше не будет объединяться с остальными названиями этой группы.") },
            confirmButton = {
                Button(onClick = {
                    splitAsk = null
                    scope.launch {
                        withContext(Dispatchers.IO) { ProductGroups.split(db, m.id) }
                        groupTick++
                    }
                }) { Text("Отделить") }
            },
            dismissButton = { TextButton(onClick = { splitAsk = null }) { Text("Отмена") } }
        )
    }

    if (mergeDialog && id != null) {
        var cands by remember { mutableStateOf<List<Product>>(emptyList()) }
        LaunchedEffect(mergeQuery, groupTick) {
            cands = if (mergeQuery.trim().length < MIN_SEARCH_CHARS) emptyList()
            else withContext(Dispatchers.IO) {
                val own = db.getProduct(id)
                val skip = (own?.let { ProductGroups.membersOf(db, it) } ?: emptyList()).map { it.id }.toSet() + id
                db.search(mergeQuery, 40).filter { it.id !in skip }
            }
        }
        AlertDialog(
            onDismissRequest = { mergeDialog = false },
            title = { Text("Тот же товар — какая карточка?") },
            text = {
                Column {
                    OutlinedTextField(
                        mergeQuery, { mergeQuery = germanKeys(it) }, label = { Text("Название, артикул или штрих-код") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(cands, key = { it.id }) { c ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    mergeDialog = false
                                    scope.launch {
                                        withContext(Dispatchers.IO) { ProductGroups.merge(db, listOf(id, c.id)) }
                                        groupTick++
                                    }
                                }.padding(vertical = 6.dp)
                            ) {
                                Text(c.name)
                                val sub = listOf(c.shownArticle, c.barcode).filter { it.isNotBlank() }.joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergeDialog = false }) { Text("Отмена") } }
        )
    }
}
