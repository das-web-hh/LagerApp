package ru.storage.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/*
 * Вкладка App в минималистичном виде: простой список разделов, у каждого справа кнопка «i» с подробностями
 * (что есть на сайте: поля, кнопки, столбцы). Список раздела строится прямо из данных сайта (тот же API, что у сайта).
 */

// ---------------------------------------------------------------- данные списков

object CgList {
    class State {
        var items by mutableStateOf<List<JSONObject>>(emptyList())
        var total by mutableIntStateOf(0)
        var busy by mutableStateOf(false)
        var err by mutableStateOf("")
        /** Не 0: показан сохранённый список от этого времени, потому что сайт сейчас недоступен. */
        var offlineAt by mutableStateOf(0L)
    }

    private fun cacheFile(key: String): java.io.File? = CoglasSchema.dir?.let { java.io.File(it, "coglas_cache_$key.json") }

    private fun saveCache(key: String, total: Int, items: List<JSONObject>) {
        try {
            val f = cacheFile(key) ?: return
            val arr = JSONArray()
            items.forEach { arr.put(it) }
            f.writeText(JSONObject().put("t", System.currentTimeMillis()).put("total", total).put("items", arr).toString(), Charsets.UTF_8)
        } catch (x: Exception) {
        }
    }

    /** Сохранённый список раздела; true — удалось показать. */
    private fun loadCache(key: String, st: State): Boolean {
        try {
            val f = cacheFile(key) ?: return false
            if (!f.exists()) return false
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val arr = o.optJSONArray("items") ?: return false
            val got = ArrayList<JSONObject>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { got.add(it) }
            if (got.isEmpty()) return false
            st.items = got
            st.total = o.optInt("total", got.size)
            st.offlineAt = o.optLong("t")
            return true
        } catch (x: Exception) {
            return false
        }
    }

    private val map = HashMap<String, State>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun state(key: String): State = map.getOrPut(key) { State() }

    private val LIST_ACTION = Regex("^[A-Za-z0-9_.]+\\.LayoutItems\\.[A-Za-z0-9_]*List\\.Actions\\.Search$")

    /** Запрос поиска списка раздела (если у раздела на сайте есть обычный список). */
    fun searchAction(e: CgMenuEntry): String? = e.apis.firstOrNull { LIST_ACTION.matches(it) }

    fun load(key: String, more: Boolean = false) {
        val entry = CoglasSchema.find(key) ?: return
        val action = searchAction(entry) ?: return
        val st = state(key)
        if (st.busy) return
        st.busy = true
        st.err = ""
        val skip = if (more) st.items.size else 0
        scope.launch {
            try {
                val body = JSONObject().put(
                    "SearchRequest", JSONObject()
                        .put("SortConfigurationKey", JSONObject.NULL)
                        .put("PropertyFilters", JSONArray())
                        .put("InvertSorting", false)
                        .put("CancelSearchRequestIfAbortedByClient", true)
                        .put("Skip", skip)
                        .put("Top", 30)
                ).toString()
                val txt = CoglasData.call(action, body, retry = true)
                val pr = JSONObject(txt).optJSONObject("PageResult")
                val arr = pr?.optJSONArray("Items")
                val got = ArrayList<JSONObject>()
                if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { got.add(it) }
                st.total = pr?.optInt("Total") ?: got.size
                st.items = if (more) st.items + got else got
                st.offlineAt = 0L
                if (!more) saveCache(key, st.total, got)
            } catch (x: CgError) {
                // нет сети или вход слетел: показываем сохранённый список, а не пустой экран
                if (st.items.isNotEmpty() || loadCache(key, st)) st.offlineAt = st.offlineAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                else st.err = x.message ?: "Ошибка"
                if (st.offlineAt > 0L) st.err = x.message ?: "Ошибка"
            } catch (x: Exception) {
                st.err = "Не удалось разобрать ответ сайта"
            } finally {
                st.busy = false
            }
        }
    }

    // ---- превращение записи сайта в читаемые строки «поле → значение»

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}T.*")

    private fun date(s: String): String = try {
        val dateOnly = s.contains("T00:00:00")
        val clean = s.replace(Regex("\\.\\d+"), "").removeSuffix("Z").substringBefore('+')
        val d = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(clean)!!
        SimpleDateFormat(if (dateOnly) "dd.MM.yyyy" else "dd.MM.yyyy HH:mm", Locale.GERMANY)
            .apply { timeZone = TimeZone.getTimeZone("Europe/Berlin") }.format(d)
    } catch (x: Exception) {
        s
    }

    private fun enumValue(s: String): String? =
        if (!s.contains(' ') && s.count { it == '.' } >= 2 && s[0].isUpperCase()) s.substringAfterLast('.') else null

    private fun walk(o: JSONObject, prefix: String, depth: Int, out: MutableList<Pair<String, String>>) {
        for (k in o.keys()) {
            if (k == "Version" || k == "RowVersion" || k.endsWith("Id") || k.endsWith("Guid")) continue
            val v = o.opt(k)
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            when (v) {
                null, JSONObject.NULL -> { }
                is JSONObject -> {
                    val key = v.optString("Key", "")
                    if (key.isNotEmpty()) out.add(path to key.substringAfterLast('.'))
                    else if (depth < 3) walk(v, path, depth + 1, out)
                }
                is JSONArray -> { }
                is Boolean -> if (v) out.add(path to "да")
                is Number -> out.add(path to v.toString().removeSuffix(".0"))
                else -> {
                    val s = v.toString().trim()
                    if (s.isEmpty() || s.length > 120) continue
                    out.add(path to if (DATE.matches(s)) date(s) else (enumValue(s) ?: s))
                }
            }
        }
    }

    fun flat(o: JSONObject): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        walk(o, "", 0, out)
        // статусы — по-русски
        return out.map { (k, v) -> if (k.substringAfterLast('.') == "Status") k to CoglasData.statusRu(v) else k to v }
    }

    private val TITLE_KEYS = listOf("Number", "Name", "Name1", "Barcode", "Description", "Title")

    fun titleOf(p: List<Pair<String, String>>): String {
        for (t in TITLE_KEYS) p.firstOrNull { it.first.substringAfterLast('.') == t }?.let { return it.second }
        return p.firstOrNull()?.second ?: "—"
    }

    fun subOf(p: List<Pair<String, String>>): String {
        val title = titleOf(p)
        return p.filter { it.second != title && it.second.length <= 40 }.take(3).joinToString(" · ") { it.second }
    }
}

// ---------------------------------------------------------------- главное окно App

private val MAIN_FORMS = listOf(
    "GoodsInOrder.Forms.GoodsInOrderList", "DeliveryNote.Forms.DeliveryNoteList", "GoodsInReceipt.Forms.GoodsInReceiptList",
    "CustomerOrder.Forms.CustomerOrderList", "Tour.Forms.TourList", "GoodsOutReceipt.Forms.GoodsOutReceiptList",
    "PickingOrder.Forms.SelectPickingCategory", "Packing.Forms.CustomerOrdersForPacking"
)
private val GROUPS = listOf("Основное", "Склад", "Справочники", "Управление", "Настройки", "Прочее")

private fun groupFor(e: CgMenuEntry): String {
    val f = e.form
    return when {
        f in MAIN_FORMS -> "Основное"
        f.startsWith("Topology") || f.startsWith("Warehouse.") || f.startsWith("TransferReceipt") ||
            f.startsWith("TransferOrder.Forms.TransferCategory") || f.startsWith("Stock.") || f.startsWith("History") ||
            f.startsWith("Inventory") || f.startsWith("StockCounting") || f.startsWith("Hauling") ||
            f.startsWith("Packing.Forms.ScanLabel") -> "Склад"
        f.startsWith("Article") || f.startsWith("BusinessPartner") || f.startsWith("Equipment") ||
            f.startsWith("File.") || f.startsWith("Checklist") -> "Справочники"
        f.contains("ControlCenter") -> "Управление"
        f.startsWith("Printing") || f.startsWith("UserManagement") -> "Настройки"
        f.startsWith("Administration") -> "Прочее"
        else -> when (e.group) {
            "Приход", "Отгрузка", "Комплектация" -> "Основное"
            "Справочники", "Мастер-данные" -> "Справочники"
            "Склад", "Остаток", "Перемещение и транспорт" -> "Склад"
            "Транспорт" -> "Управление"
            "Рабочее место", "Управление пользователями" -> "Настройки"
            else -> "Прочее"
        }
    }
}

private fun openEntry(e: CgMenuEntry) {
    CoglasState.appPage = when {
        e.form == "GoodsInOrder.Forms.GoodsInOrderList" -> "orders"
        e.form == "Warehouse.Forms.InfoScan" -> "info"
        else -> "menu:" + e.key
    }
}

@Composable
fun AppHome() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { CoglasSchema.init(ctx) }
    @Suppress("UNUSED_VARIABLE") val ver = CoglasSchema.version
    var q by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    var admin by remember { mutableStateOf(false) }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val needle = q.trim().lowercase()
    val entries = CoglasSchema.all().filter { !it.ru.startsWith("Масштабирование") }
    val grouped = entries.groupBy { groupFor(it) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text("Поиск") }, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        )
        Spacer(Modifier.height(8.dp))
        var shown = 0
        for (g in GROUPS) {
            var list = grouped[g] ?: continue
            if (g == "Основное") list = list.sortedBy { val i = MAIN_FORMS.indexOf(it.form); if (i < 0) 99 else i }
            val items = list.filter { needle.isEmpty() || it.ru.lowercase().contains(needle) || it.de.lowercase().contains(needle) }
            if (items.isEmpty()) continue
            shown += items.size
            val expanded = needle.isNotEmpty() || (open[g] ?: (g == "Основное"))
            Row(
                Modifier.fillMaxWidth().clickable { open[g] = !expanded }.padding(top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    g.uppercase(), modifier = Modifier.weight(1f), fontSize = 12.sp, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (expanded) "−" else "${items.size}", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                items.forEach { e ->
                    Row(Modifier.fillMaxWidth().clickable { openEntry(e) }, verticalAlignment = Alignment.CenterVertically) {
                        Text(e.ru, modifier = Modifier.weight(1f).padding(vertical = 14.dp), fontSize = 16.sp)
                        IconButton(onClick = { info = e.key }) {
                            Icon(
                                Icons.Default.Info, contentDescription = "Подробности", modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        if (shown == 0) Text("Ничего не найдено", modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (Session.isAdmin) {
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = { admin = !admin }) { Text(if (admin) "Скрыть данные сайта" else "Данные сайта (админ)") }
            if (admin) ScanCard()
        }
        Spacer(Modifier.height(24.dp))
    }
    info?.let { InfoDialog(it) { info = null } }
}

// ---------------------------------------------------------------- «i»: подробности раздела

@Composable
private fun InfoDialog(key: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val e = CoglasSchema.find(key)
    if (e == null) { onClose(); return }
    @Suppress("UNUSED_VARIABLE") val ver = CoglasSchema.version
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
        dismissButton = {
            if (Session.isAdmin) {
                TextButton(onClick = { onClose(); CoglasState.tab = 1; CoglasWeb.openMenu(e.de, e.ru) }) { Text("На сайте") }
            }
        },
        title = { Text(e.ru) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!e.de.equals(e.ru, ignoreCase = true)) {
                    Text(e.de, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (e.scannedAt == 0L) {
                    Text("Данные этого раздела ещё не собраны.")
                } else {
                    if (e.note.isNotEmpty()) Text(e.note, color = MaterialTheme.colorScheme.error)
                    InfoLines("На сайте есть", e.tabs)
                    InfoLines("Поля и фильтры", e.fields)
                    InfoLines("Кнопки", e.buttons.filter { it !in listOf("10", "20", "50", "«", "‹", "…", "›", "»") && !it.all { c -> c.isDigit() } })
                    InfoLines("Столбцы таблицы", e.columns)
                    Text("Строк при сборе: ${e.rows}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (Session.isAdmin && (e.form.isNotEmpty() || e.apis.isNotEmpty())) {
                        val dev = buildString {
                            if (e.form.isNotEmpty()) append(e.form).append("\n")
                            append(e.apis.joinToString("\n") { it.substringAfterLast("LayoutItems.") })
                        }
                        SelectionContainer { Text(dev, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        OutlinedButton(onClick = { CoglasScan.start(ctx, e.key); onClose() }, enabled = !CoglasScan.running) { Text("Пересканировать") }
                    }
                }
            }
        }
    )
}

@Composable
private fun InfoLines(title: String, v: List<String>) {
    if (v.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Text(v.joinToString(" · "), fontSize = 14.sp)
    }
}

// ---------------------------------------------------------------- окно раздела

@Composable
fun MenuWorkScreen(key: String) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { CoglasSchema.init(ctx) }
    @Suppress("UNUSED_VARIABLE") val ver = CoglasSchema.version
    val e = CoglasSchema.find(key)
    if (e == null) {
        Text("Раздел не найден.", modifier = Modifier.padding(16.dp))
        return
    }
    var info by remember { mutableStateOf(false) }
    val action = CgList.searchAction(e)
    Column(Modifier.fillMaxSize()) {
        // название раздела теперь в шапке окна, здесь только кнопка «i»
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { info = true }) {
                Icon(Icons.Default.Info, contentDescription = "Подробности", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Для этого раздела пока нет родного экрана.\nЧто на сайте — кнопка «i». Раздел работает на сайте целиком.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { CoglasState.tab = 1; CoglasWeb.openMenu(e.de, e.ru) }) { Text("Открыть раздел") }
                }
            }
        } else {
            CgListScreen(e)
        }
    }
    if (info) InfoDialog(key) { info = false }
}

@Composable
private fun CgListScreen(e: CgMenuEntry) {
    val st = CgList.state(e.key)
    var q by remember(e.key) { mutableStateOf("") }
    var sel by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var rec by remember(e.key) { mutableStateOf<Pair<String, Long>?>(null) }
    val recCfg = RECORD_CFG[e.form]
    LaunchedEffect(e.key) { if (st.items.isEmpty() && !st.busy) CgList.load(e.key) }
    val needle = q.trim().lowercase()
    val rows = st.items.map { CgList.flat(it) to it.optLong("Id") }
        .filter { needle.isEmpty() || it.first.joinToString(" ") { p -> p.second }.lowercase().contains(needle) }

    rec?.let { (title, id) ->
        if (recCfg != null) {
            RecordView(title, recCfg, id) { rec = null }
            androidx.activity.compose.BackHandler { rec = null }
            return
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text("Поиск в загруженных") }, modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        )
        if (st.offlineAt > 0L) {
            val at = SimpleDateFormat("dd.MM HH:mm", Locale.GERMANY).format(java.util.Date(st.offlineAt))
            Text(
                "Нет связи с сайтом. Показан сохранённый список от $at.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)
            )
            OutlinedButton(onClick = { CgList.load(e.key) }, enabled = !st.busy) { Text("Обновить") }
        } else if (st.err.isNotEmpty()) {
            Text(st.err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            OutlinedButton(onClick = { CgList.load(e.key) }) { Text("Повторить") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows) { (r, rid) ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { if (recCfg != null && rid > 0L) rec = CgList.titleOf(r) to rid else sel = r }
                        .padding(vertical = 12.dp)
                ) {
                    Text(CgList.titleOf(r), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    val sub = CgList.subOf(r)
                    if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    when {
                        st.busy -> CircularProgressIndicator(Modifier.size(28.dp))
                        st.items.isNotEmpty() && st.items.size < st.total ->
                            OutlinedButton(onClick = { CgList.load(e.key, more = true) }) { Text("Показать ещё (${st.items.size} из ${st.total})") }
                        st.items.isNotEmpty() -> Text("Всего: ${st.total}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        st.err.isEmpty() -> Text("Список пуст", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    sel?.let { r ->
        AlertDialog(
            onDismissRequest = { sel = null },
            confirmButton = { TextButton(onClick = { sel = null }) { Text("Закрыть") } },
            title = { Text(CgList.titleOf(r)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.forEach { (k, v) ->
                        Column {
                            Text(k.split('.').takeLast(2).joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(v, fontSize = 15.sp)
                        }
                    }
                }
            }
        )
    }
}
