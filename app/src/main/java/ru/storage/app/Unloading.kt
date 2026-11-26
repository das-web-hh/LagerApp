package ru.storage.app

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.UUID

/*
 * Работа → Приём → «Разгрузка» (новый экран, не связан со старой «Выгрузкой» из «Прочее»).
 * Запись разгрузки: номер машины, когда привезли, паллеты / Einwegpalette / картоны, данные документа (EB, Lieferant, номер документа).
 * Записи лежат на телефоне (unloading.json). Из «Посчитать товары» разгрузку можно привязать к приёму.
 */

data class UnloadRec(
    val id: String,
    val car: String = "",
    val pallets: Int = 0,
    val ew: Int = 0,
    val cartons: Int = 0,
    val arrivedAt: Long,
    val eb: String = "",
    val lieferant: String = "",
    val docNo: String = "",
    val docFiles: List<String> = emptyList(),
    /** id приёмов, которые привязаны к этой разгрузке */
    val receipts: List<String> = emptyList(),
    /** false — черновик (автосохранение), true — нажали «Сохранить» */
    val saved: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)

object UnloadStore {
    val items = mutableStateListOf<UnloadRec>()
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "unloading.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            val list = ArrayList<UnloadRec>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                fun strs(k: String): List<String> {
                    val a = o.optJSONArray(k) ?: return emptyList()
                    return (0 until a.length()).map { a.getString(it) }
                }
                list.add(
                    UnloadRec(
                        id = o.getString("id"), car = o.optString("car"),
                        pallets = o.optInt("pallets"), ew = o.optInt("ew"), cartons = o.optInt("cartons"),
                        arrivedAt = o.optLong("arrivedAt"), eb = o.optString("eb"), lieferant = o.optString("lieferant"),
                        docNo = o.optString("docNo"), docFiles = strs("docFiles"), receipts = strs("receipts"),
                        saved = o.optBoolean("saved"), createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt")
                    )
                )
            }
            items.clear()
            items.addAll(list.sortedByDescending { it.arrivedAt })
        } catch (e: Exception) {
        }
    }

    private fun persist(ctx: Context) {
        try {
            val arr = JSONArray()
            items.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("id", r.id).put("car", r.car).put("pallets", r.pallets).put("ew", r.ew).put("cartons", r.cartons)
                        .put("arrivedAt", r.arrivedAt).put("eb", r.eb).put("lieferant", r.lieferant).put("docNo", r.docNo)
                        .put("docFiles", JSONArray(r.docFiles)).put("receipts", JSONArray(r.receipts))
                        .put("saved", r.saved).put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)
                )
            }
            val f = file(ctx)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(f)) { f.writeText(arr.toString(), Charsets.UTF_8); tmp.delete() }
        } catch (e: Exception) {
        }
    }

    fun get(id: String): UnloadRec? = items.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(ctx: Context, r: UnloadRec) {
        val i = items.indexOfFirst { it.id == r.id }
        if (i >= 0) items[i] = r else items.add(r)
        items.sortByDescending { it.arrivedAt }
        persist(ctx)
    }

    @Synchronized
    fun update(ctx: Context, id: String, block: (UnloadRec) -> UnloadRec): UnloadRec? {
        val cur = get(id) ?: return null
        val n = block(cur)
        upsert(ctx, n)
        return n
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        items.removeAll { it.id == id }
        persist(ctx)
    }

    /** Привязка приёма к разгрузке (в «Посчитать товары»). */
    fun linkReceipt(ctx: Context, unloadId: String, receiptId: String) {
        update(ctx, unloadId) { if (receiptId in it.receipts) it else it.copy(receipts = it.receipts + receiptId, updatedAt = System.currentTimeMillis()) }
    }

    fun byReceipt(receiptId: String): UnloadRec? = items.firstOrNull { receiptId in it.receipts }
}

/** «Машина 123 · привезли 26.11.2026 08:30 · EB1234567 · Lieferant 4711» — одна строка для списков и приёма. */
fun unloadLine(r: UnloadRec): String = listOf(
    "Машина " + r.car.ifBlank { "—" },
    "привезли " + fmtTime(r.arrivedAt),
    r.eb.takeIf { it.isNotBlank() },
    r.lieferant.takeIf { it.isNotBlank() }?.let { "Lieferant $it" }
).filterNotNull().joinToString(" · ")

private fun ulgActivity(ctx: Context): Activity? {
    var c: Context? = ctx
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun ulgCounts(r: UnloadRec) = "Паллеты ${r.pallets} · Einweg ${r.ew} · Картоны ${r.cartons}"

// ====================================================================== список

@Composable
fun UnloadingScreen(prefs: Prefs, onOpenReceive: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { UnloadStore.load(ctx) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    // "" — распознавание без выбранной записи; иначе id записи
    var docFor by rememberSaveable { mutableStateOf<String?>(null) }
    val d = docFor
    val e = editId
    if (d != null) {
        UnloadDocScreen(prefs, d.ifEmpty { null }) { docFor = null }
        return
    }
    if (e != null) {
        UnloadEditScreen(e, onOpenDoc = { docFor = it }, onBack = { editId = null })
        return
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("Разгрузка", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { editId = UUID.randomUUID().toString() },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Новая разгрузка", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            val list = UnloadStore.items.toList()
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Записей разгрузки пока нет") }
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { r ->
                        Card(
                            onClick = { editId = r.id },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.car.ifBlank { "Без номера машины" },
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!r.saved) Text("черновик", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                }
                                Text("Привезли: " + fmtTime(r.arrivedAt), style = MaterialTheme.typography.bodyMedium)
                                Text(ulgCounts(r), style = MaterialTheme.typography.bodyMedium)
                                val doc = listOf(
                                    r.eb.takeIf { it.isNotBlank() },
                                    r.lieferant.takeIf { it.isNotBlank() }?.let { "Lieferant $it" },
                                    r.docNo.takeIf { it.isNotBlank() }?.let { "док. $it" }
                                ).filterNotNull().joinToString(" · ")
                                if (doc.isNotEmpty()) Text(doc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                if (r.receipts.isNotEmpty()) Text("Приёмов привязано: ${r.receipts.size}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        // нижняя панель быстрого доступа
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { docFor = "" }, modifier = Modifier.weight(1f)) { Text("Сканировать документ", maxLines = 1) }
                OutlinedButton(onClick = onOpenReceive, modifier = Modifier.weight(1f)) { Text("Приём товаров", maxLines = 1) }
            }
        }
    }
}

// ====================================================================== создание / редактирование

private fun ulgPickDate(ctx: Context, ms: Long, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    DatePickerDialog(ctx, { _, y, m, d ->
        onPick(Calendar.getInstance().apply { timeInMillis = ms; set(y, m, d) }.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

private fun ulgPickTime(ctx: Context, ms: Long, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    TimePickerDialog(ctx, { _, h, mi ->
        onPick(Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, mi); set(Calendar.SECOND, 0) }.timeInMillis)
    }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
}

@Composable
private fun UnloadEditScreen(id: String, onOpenDoc: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val initial = remember(id) { UnloadStore.get(id) }
    val now0 = remember(id) { System.currentTimeMillis() }
    var car by remember(id) { mutableStateOf(initial?.car ?: "") }
    var pallets by remember(id) { mutableStateOf(initial?.pallets?.takeIf { it > 0 }?.toString() ?: "") }
    var ew by remember(id) { mutableStateOf(initial?.ew?.takeIf { it > 0 }?.toString() ?: "") }
    var cartons by remember(id) { mutableStateOf(initial?.cartons?.takeIf { it > 0 }?.toString() ?: "") }
    // время прибытия фиксируется само в момент открытия «Новой разгрузки»; его можно поправить
    var arrived by remember(id) { mutableLongStateOf(initial?.arrivedAt ?: now0) }
    var err by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    val live = UnloadStore.items.firstOrNull { it.id == id }

    /** Пишет только поля формы: данные документа (EB, Lieferant) не трогает. */
    fun persist(markSaved: Boolean = false, force: Boolean = false) {
        if (gone) return
        val p = pallets.toIntOrNull() ?: 0
        val w = ew.toIntOrNull() ?: 0
        val c = cartons.toIntOrNull() ?: 0
        val has = car.isNotBlank() || p > 0 || w > 0 || c > 0 || force || markSaved || UnloadStore.get(id) != null
        if (!has) return
        val base = UnloadStore.get(id) ?: UnloadRec(id = id, arrivedAt = arrived, createdAt = now0, updatedAt = now0)
        UnloadStore.upsert(
            ctx,
            base.copy(
                car = car.trim(), pallets = p, ew = w, cartons = c, arrivedAt = arrived,
                saved = base.saved || markSaved, updatedAt = System.currentTimeMillis()
            )
        )
    }

    // автосохранение: при каждом изменении полей (с короткой паузой) и при выходе из окна
    LaunchedEffect(car, pallets, ew, cartons, arrived) {
        delay(400)
        persist()
    }
    DisposableEffect(id) { onDispose { persist() } }
    BackHandler { persist(); onBack() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = { persist(); onBack() }) { Text("‹ Назад") }
        Text(if (initial == null) "Новая разгрузка" else "Разгрузка", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = car, onValueChange = { car = it; err = "" },
            label = { Text("Номер машины *") }, singleLine = true,
            isError = err.isNotEmpty(), modifier = Modifier.fillMaxWidth()
        )
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pallets, onValueChange = { pallets = it.filter { ch -> ch.isDigit() }.take(5) },
            label = { Text("Паллеты") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ew, onValueChange = { ew = it.filter { ch -> ch.isDigit() }.take(5) },
            label = { Text("Одноразовые паллеты (Einwegpalette)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = cartons, onValueChange = { cartons = it.filter { ch -> ch.isDigit() }.take(5) },
            label = { Text("Картоны / посылки") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))
        Text("Когда привезли", style = MaterialTheme.typography.titleSmall)
        val c = Calendar.getInstance().apply { timeInMillis = arrived }
        val dateTxt = "%02d.%02d.%04d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
        val timeTxt = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { ulgPickDate(ctx, arrived) { arrived = it } }) { Text(dateTxt) }
            OutlinedButton(onClick = { ulgPickTime(ctx, arrived) { arrived = it } }) { Text(timeTxt) }
            TextButton(onClick = { arrived = System.currentTimeMillis() }) { Text("Сейчас") }
        }

        Spacer(Modifier.height(16.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Документ", style = MaterialTheme.typography.titleSmall)
                val hasDoc = live != null && (live.eb.isNotBlank() || live.lieferant.isNotBlank() || live.docNo.isNotBlank())
                if (hasDoc && live != null) {
                    if (live.eb.isNotBlank()) Text("EB: ${live.eb}", style = MaterialTheme.typography.bodyLarge)
                    if (live.lieferant.isNotBlank()) Text("Lieferant: ${live.lieferant}", style = MaterialTheme.typography.bodyLarge)
                    if (live.docNo.isNotBlank()) Text("Номер документа: ${live.docNo}", style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text("Документ ещё не распознан", style = MaterialTheme.typography.bodyMedium)
                }
                if (live != null && live.receipts.isNotEmpty()) {
                    Text("Приёмов привязано: ${live.receipts.size}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { persist(force = true); onOpenDoc(id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (hasDoc) "Распознать документ заново" else "Сканировать / распознать документ")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (car.isBlank()) { err = "Укажите номер машины"; return@Button }
                persist(markSaved = true)
                onBack()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Сохранить") }
        if (initial != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Удалить запись", color = MaterialTheme.colorScheme.error)
            }
        }
        Text(
            "Все изменения сохраняются сами — в черновик, даже если не нажать «Сохранить».",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить запись?") },
            text = { Text("Запись разгрузки будет удалена с этого телефона.") },
            confirmButton = {
                Button(onClick = { gone = true; UnloadStore.delete(ctx, id); confirmDelete = false; onBack() }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}

// ====================================================================== распознавание документа

private class UlgResult(val eb: String, val lieferant: String, val docNo: String, val file: String?, val note: String)

private fun ulgPrompt(page: Int, total: Int) = """
    Page $page of $total of a scanned goods delivery document (Lieferschein / delivery note / invoice / packing slip), German, sometimes Czech or Dutch. It may be grey, skewed, rotated or blurry: read it anyway. Do NOT translate.
    Find ONLY these three values on THIS page. Answer with JSON only, no markdown, no comments:
    {"eb":"...","lieferant":"...","doc_no":"..."}
    - eb: a code that starts with EB (or Cyrillic ЕБ) followed by exactly 7 digits, for example EB1234567. It may be written EB 1234567 or EB-1234567, or stand inside a longer reference (BelegNr-EB2612798). Usually next to Bestellnummer / Ihre Bestell-Nr. Output it as EB plus 7 digits.
    - lieferant: the supplier NUMBER (not the company name): the number under which the recipient knows the sender, next to words like Lieferant, Lieferanten-Nr., Lieferantennummer, Lief.-Nr., Kreditorennummer, Vendor no., Dodavatel c. Digits (letters only if they are printed as part of that number). It is NOT a phone or fax number, VAT ID (USt-IdNr., DE...), IBAN, customer number (Kundennummer), order number or delivery note number.
    - doc_no: the delivery note / document number (Lieferschein-Nr., Beleg-Nr.), only if printed.
    Use "" for anything that is not on this page. Never guess.
""".trimIndent()

private fun ulgJson(text: String): JSONObject? {
    val s = text.indexOf('{')
    val e = text.lastIndexOf('}')
    if (s < 0 || e <= s) return null
    return try { JSONObject(text.substring(s, e + 1)) } catch (ex: Exception) { null }
}

private suspend fun ulgRecognize(ctx: Context, uri: Uri): UlgResult {
    // копия во временный файл (PDF или картинка) + копия в архив приложения (уйдёт на Google Диск)
    val mime = ctx.contentResolver.getType(uri).orEmpty()
    val ext = if (mime.startsWith("image/")) "jpg" else "pdf"
    val tmp = File(ctx.cacheDir, "ulg-" + UUID.randomUUID().toString().take(8) + "." + ext)
    withContext(Dispatchers.IO) {
        ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
    }
    val saved = withContext(Dispatchers.IO) {
        try { Media.importUri(ctx, uri, ProductDb.get(ctx), "IN") } catch (e: Exception) { null }
    }
    var eb = ""
    var lief = ""
    var docNo = ""
    var note = ""
    val doc = withContext(Dispatchers.IO) { openPageSource(tmp) }
    try {
        val pages = minOf(doc.count, 3)
        if (!AiCfg.ready(ctx)) {
            note = "ИИ не настроен — Lieferant впишите вручную."
        } else {
            for (i in 0 until pages) {
                if (eb.isNotEmpty() && lief.isNotEmpty()) break
                try {
                    val o = withContext(Dispatchers.IO) {
                        val jpg = doc.renderBw(i, 2000, false)
                        val params = JSONObject().put("page", i + 1).put("total", pages)
                        ulgJson(Ai.task(ctx, "unload_doc", params, jpg, true) { ulgPrompt(i + 1, pages) })
                    } ?: continue
                    if (eb.isEmpty()) eb = Ai.normalizeOrder(o.optString("eb", "")).let { if (it == "none") "" else it }
                    if (lief.isEmpty()) lief = o.optString("lieferant", "").trim().take(30).takeIf { !it.equals("none", true) && !it.equals("null", true) }.orEmpty()
                    if (docNo.isEmpty()) docNo = o.optString("doc_no", "").trim().take(40).takeIf { !it.equals("none", true) && !it.equals("null", true) }.orEmpty()
                } catch (e: Exception) {
                    note = "ИИ: " + (e.message ?: "ошибка").take(120)
                }
            }
        }
        // ИИ не нашёл EB (или не ответил): запасной путь — чтение текста на телефоне
        if (eb.isEmpty()) {
            try {
                val ids = DocIds.find(doc)
                if (ids.order != "none") eb = ids.order
            } catch (e: Exception) {
            }
        }
    } finally {
        try { doc.close() } catch (e: Exception) { }
        tmp.delete()
    }
    return UlgResult(eb, lief, docNo, saved, note)
}

@Composable
fun UnloadDocScreen(prefs: Prefs, targetId: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { UnloadStore.load(ctx) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var eb by rememberSaveable { mutableStateOf("") }
    var lieferant by rememberSaveable { mutableStateOf("") }
    var docNo by rememberSaveable { mutableStateOf("") }
    var docFile by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(targetId ?: UnloadStore.items.firstOrNull()?.id) }

    fun handle(uri: Uri?) {
        if (uri == null) return
        busy = true
        status = "Распознаю документ…"
        scope.launch {
            try {
                val r = ulgRecognize(ctx, uri)
                if (r.eb.isNotEmpty()) eb = r.eb
                if (r.lieferant.isNotEmpty()) lieferant = r.lieferant
                if (r.docNo.isNotEmpty()) docNo = r.docNo
                if (r.file != null) docFile = r.file
                status = buildString {
                    append(if (r.eb.isNotEmpty() || r.lieferant.isNotEmpty()) "Готово — проверьте значения." else "Ничего не найдено — впишите значения вручную.")
                    if (r.note.isNotBlank()) append(" ").append(r.note)
                }
            } catch (e: Exception) {
                status = "Ошибка: " + (e.message ?: "не удалось прочитать документ")
            } finally {
                busy = false
            }
        }
    }

    val ownLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) handle(OwnScan.pdfFrom(res.data))
    }
    val gmsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) handle(GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf?.uri)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> handle(uri) }

    fun startScan() {
        val act = ulgActivity(ctx) ?: return
        if (OwnScan.enabled(ctx)) {
            ownLauncher.launch(OwnScan.intent(act))
        } else {
            val opts = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(false)
                .setPageLimit(3)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
                .addOnSuccessListener { sender -> gmsLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener { Toast.makeText(ctx, "Сканер недоступен: " + (it.message ?: ""), Toast.LENGTH_LONG).show() }
        }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("‹ Назад") }
        Text("Распознавание документа", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Из документа берутся только номер EB, номер Lieferant и номер документа.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { startScan() }, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)) { Text("Сканировать") }
            OutlinedButton(
                onClick = { picker.launch(arrayOf("image/*", "application/pdf")) },
                enabled = !busy, modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("Фото / файл") }
        }
        if (busy) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(status)
            }
        } else if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(eb, { eb = it }, label = { Text("Номер EB") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(lieferant, { lieferant = it }, label = { Text("Номер Lieferant") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(docNo, { docNo = it }, label = { Text("Номер документа (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(14.dp))
        Text("Привязать к разгрузке", style = MaterialTheme.typography.titleSmall)
        if (targetId != null) {
            val t = UnloadStore.get(targetId)
            Text(if (t != null) unloadLine(t) else "Текущая запись", style = MaterialTheme.typography.bodyMedium)
        } else {
            UnloadStore.items.take(6).forEach { u ->
                Row(
                    Modifier.fillMaxWidth().clickable { picked = u.id }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = picked == u.id, onClick = { picked = u.id })
                    Text(unloadLine(u), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { picked = null }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = picked == null, onClick = { picked = null })
                Text("Новая разгрузка (черновик)", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                val now = System.currentTimeMillis()
                val files = if (docFile.isNotBlank()) listOf(docFile) else emptyList()
                val target = picked
                if (target != null && UnloadStore.get(target) != null) {
                    UnloadStore.update(ctx, target) {
                        it.copy(
                            eb = eb.trim(), lieferant = lieferant.trim(), docNo = docNo.trim(),
                            docFiles = (it.docFiles + files).distinct(), updatedAt = now
                        )
                    }
                } else {
                    UnloadStore.upsert(
                        ctx,
                        UnloadRec(
                            id = UUID.randomUUID().toString(), arrivedAt = now, createdAt = now, updatedAt = now,
                            eb = eb.trim(), lieferant = lieferant.trim(), docNo = docNo.trim(), docFiles = files
                        )
                    )
                }
                onBack()
            },
            enabled = !busy && (eb.isNotBlank() || lieferant.isNotBlank() || docNo.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Сохранить данные документа") }
    }
}

// ====================================================================== выбор разгрузки (из «Посчитать товары»)

@Composable
fun UnloadPickDialog(onDismiss: () -> Unit, onClear: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    remember { UnloadStore.load(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Привязать разгрузку") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (UnloadStore.items.isEmpty()) Text("Записей разгрузки пока нет — создайте в «Приём → Разгрузка».")
                UnloadStore.items.take(20).forEach { u ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(u.id) }.padding(vertical = 8.dp)) {
                        Text(u.car.ifBlank { "Без номера машины" }, fontWeight = FontWeight.Medium)
                        Text(unloadLine(u), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onClear) { Text("Без разгрузки") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
