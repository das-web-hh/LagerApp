package ru.storage.app

import android.content.Context
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
private val PACKS = listOf("Бутылка", "Коробка", "Мешок", "Банка", "Пачка")

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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Media.uploadPending(ctx, db) }
        Cloud.runProducts(db, sync, false)
    }
    LaunchedEffect(sync.version) { list = withContext(Dispatchers.IO) { db.allProducts() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val data = withContext(Dispatchers.IO) {
                try { Xlsx.read(ctx, uri) } catch (e: Exception) { emptyList() }
            }
            if (data.isEmpty()) sync.text = "Не удалось прочитать файл (нужен .xlsx)" else rows = data
        }
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
        TextButton(onClick = onBack) { Text("← Назад") }
        Text("Каталог товаров", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { creating = true }) { Text("+ Товар") }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("📥 Excel") }
            OutlinedButton(
                onClick = { scope.launch { Cloud.runProducts(db, sync, true) } },
                enabled = !sync.busy
            ) { Text("Обновить") }
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
            items(list, key = { it.id }) { p ->
                Text(
                    p.name,
                    modifier = Modifier.fillMaxWidth().clickable { openId = p.id }.padding(vertical = 12.dp)
                )
                HorizontalDivider()
            }
        }
    }

    rows?.let { data ->
        ImportDialog(
            rows = data,
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
        val base = existing ?: Product(UUID.randomUUID().toString(), name, "", "", "", "", "", "", emptyList(), 0L)
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
    onImport: (Int, Int, Int, Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("import", Context.MODE_PRIVATE) }
    val width = rows.maxOf { it.size }
    var nameCol by remember { mutableIntStateOf(sp.getInt("name", 0).coerceAtMost(width - 1)) }
    var barcodeCol by remember { mutableIntStateOf(sp.getInt("barcode", -1).coerceAtMost(width - 1)) }
    var articleCol by remember { mutableIntStateOf(sp.getInt("article", -1).coerceAtMost(width - 1)) }
    var header by remember { mutableStateOf(sp.getBoolean("header", true)) }
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
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Product?>(null) }
    var name by remember { mutableStateOf("") }
    var article by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("кг") }
    var pack by remember { mutableStateOf("") }
    var manufacturer by remember { mutableStateOf("") }
    val photos = remember { mutableStateListOf<String>() }
    var scanning by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(id) {
        if (id != null) {
            val p = withContext(Dispatchers.IO) { db.getProduct(id) }
            if (p != null) {
                original = p
                name = p.name; article = p.article; barcode = p.barcode
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

    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onClose) { Text("← Назад") }
        Text(
            if (id == null) "Новый товар" else "Карточка товара",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                weight, { weight = it }, label = { Text("Вес / объём") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            DropdownField(unit, UNITS) { unit = it }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pack, { pack = it }, label = { Text("Тип (бутылка, коробка, мешок…)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            PACKS.forEach { p -> TextButton(onClick = { pack = p }) { Text(p) } }
        }
        OutlinedTextField(manufacturer, { manufacturer = it }, label = { Text("Производитель") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(article, { article = it }, label = { Text("Артикул") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(barcode, { barcode = it }, label = { Text("Штрих-код") },
                singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { scanning = true }) { Text("📷 Скан") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Фото", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        MediaStrip(photos) { photos.remove(it) }
        Spacer(Modifier.height(8.dp))
        MediaButtons(allowFile = false) { photos.add(it) }

        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
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
                        manufacturer = manufacturer.trim(), photos = cur, updatedAt = 0L
                    )
                    scope.launch(Dispatchers.IO) {
                        Cloud.saveProduct(db, p)
                        Media.discard(ctx, db, (old?.photos ?: emptyList()).filter { it !in cur })
                        Media.uploadPending(ctx, db)
                        withContext(Dispatchers.Main) { onClose() }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить") }
        if (id != null) {
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
}
