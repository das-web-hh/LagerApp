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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Edit
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

/** Ещё один документ (накладная) к той же разгрузке: водитель может привезти несколько. */
data class UnloadDoc(
    val eb: String = "",
    val lieferant: String = "",
    val docNo: String = "",
    val files: List<String> = emptyList()
)

/** Страны номеров машин: код, название, знак на номере. Германия — первая и по умолчанию. */
data class UlgCountry(val code: String, val name: String)

val ULG_COUNTRIES = listOf(
    UlgCountry("DE", "Германия"), UlgCountry("PL", "Польша"), UlgCountry("NL", "Нидерланды"), UlgCountry("CZ", "Чехия"),
    UlgCountry("LT", "Литва"), UlgCountry("LV", "Латвия"), UlgCountry("EE", "Эстония"), UlgCountry("RO", "Румыния"),
    UlgCountry("BG", "Болгария"), UlgCountry("HU", "Венгрия"), UlgCountry("SK", "Словакия"), UlgCountry("SI", "Словения"),
    UlgCountry("HR", "Хорватия"), UlgCountry("AT", "Австрия"), UlgCountry("CH", "Швейцария"), UlgCountry("DK", "Дания"),
    UlgCountry("BE", "Бельгия"), UlgCountry("FR", "Франция"), UlgCountry("IT", "Италия"), UlgCountry("ES", "Испания"),
    UlgCountry("PT", "Португалия"), UlgCountry("SE", "Швеция"), UlgCountry("NO", "Норвегия"), UlgCountry("FI", "Финляндия"),
    UlgCountry("LU", "Люксембург"), UlgCountry("GB", "Великобритания"), UlgCountry("IE", "Ирландия"), UlgCountry("GR", "Греция"),
    UlgCountry("RS", "Сербия"), UlgCountry("TR", "Турция"), UlgCountry("UA", "Украина"), UlgCountry("BY", "Беларусь"),
    UlgCountry("MD", "Молдова"), UlgCountry("GE", "Грузия"), UlgCountry("KZ", "Казахстан"), UlgCountry("RU", "Россия")
)

fun ulgCountry(code: String): UlgCountry = ULG_COUNTRIES.firstOrNull { it.code.equals(code, true) } ?: ULG_COUNTRIES[0]

/** Флаг-эмодзи из двухбуквенного кода страны. */
fun ulgFlag(code: String): String {
    val c = code.uppercase()
    if (c.length != 2) return ""
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
}

data class UnloadRec(
    val id: String,
    /** Имя записи: кто привёз (UPS, DHL, перевозчик) или что привезли (Bigbag, картоны, товары) */
    val name: String = "",
    val car: String = "",
    /** Страна номера машины (ISO), по умолчанию Германия */
    val carCountry: String = "DE",
    val pallets: Int = 0,
    val ew: Int = 0,
    val cartons: Int = 0,
    val arrivedAt: Long,
    val eb: String = "",
    val lieferant: String = "",
    val docNo: String = "",
    val docFiles: List<String> = emptyList(),
    /** Второй, третий… документы (первый лежит в eb / lieferant / docNo / docFiles) */
    val extraDocs: List<UnloadDoc> = emptyList(),
    /** id приёмов, которые привязаны к этой разгрузке */
    val receipts: List<String> = emptyList(),
    /** false — черновик (автосохранение), true — нажали «Сохранить» */
    val saved: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
) {
    /** Все документы с данными: первый и дополнительные. */
    fun allDocs(): List<UnloadDoc> {
        val first = UnloadDoc(eb, lieferant, docNo, docFiles)
        val list = ArrayList<UnloadDoc>()
        if (eb.isNotBlank() || lieferant.isNotBlank() || docNo.isNotBlank()) list.add(first)
        list.addAll(extraDocs)
        return list
    }
}

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
                val extras = ArrayList<UnloadDoc>()
                o.optJSONArray("extraDocs")?.let { a ->
                    for (k in 0 until a.length()) {
                        val d = a.getJSONObject(k)
                        val fa = d.optJSONArray("files")
                        extras.add(
                            UnloadDoc(
                                eb = d.optString("eb"), lieferant = d.optString("lieferant"), docNo = d.optString("docNo"),
                                files = if (fa == null) emptyList() else (0 until fa.length()).map { fa.getString(it) }
                            )
                        )
                    }
                }
                list.add(
                    UnloadRec(
                        id = o.getString("id"), name = o.optString("name"), car = o.optString("car"),
                        carCountry = o.optString("carCountry").ifBlank { "DE" }, extraDocs = extras,
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
                        .put("id", r.id).put("name", r.name).put("car", r.car).put("pallets", r.pallets).put("ew", r.ew).put("cartons", r.cartons)
                        .put("arrivedAt", r.arrivedAt).put("eb", r.eb).put("lieferant", r.lieferant).put("docNo", r.docNo)
                        .put("carCountry", r.carCountry)
                        .put(
                            "extraDocs",
                            JSONArray().also { ea ->
                                r.extraDocs.forEach { d ->
                                    ea.put(JSONObject().put("eb", d.eb).put("lieferant", d.lieferant).put("docNo", d.docNo).put("files", JSONArray(d.files)))
                                }
                            }
                        )
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
        try { CoglasWeb.pushUnload() } catch (e: Exception) { }
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
        try { CoglasWeb.pushUnload() } catch (e: Exception) { }
    }

    /** Привязка приёма к разгрузке (в «Посчитать товары»). */
    fun linkReceipt(ctx: Context, unloadId: String, receiptId: String) {
        update(ctx, unloadId) { if (receiptId in it.receipts) it else it.copy(receipts = it.receipts + receiptId, updatedAt = System.currentTimeMillis()) }
    }

    fun byReceipt(receiptId: String): UnloadRec? = items.firstOrNull { receiptId in it.receipts }
}

/** «Машина 123 · привезли 26.11.2026 08:30 · EB1234567 · Lieferant 4711» — одна строка для списков и приёма. */
fun unloadLine(r: UnloadRec): String = listOf(
    r.name.takeIf { it.isNotBlank() },
    "Машина " + (if (r.carCountry.isNotBlank() && !r.carCountry.equals("DE", true)) r.carCountry.uppercase() + " " else "") + r.car.ifBlank { "—" },
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
    SlidePages(UlgPage(editId, docFor), { p -> if (p.doc != null) 2 else if (p.edit != null) 1 else 0 }) { p ->
        val d = p.doc
        val e = p.edit
        when {
            d != null -> UnloadDocScreen(prefs, d.removePrefix("+").ifEmpty { null }, addMode = d.startsWith("+") || d.isEmpty()) { docFor = null }
            e != null -> UnloadEditScreen(e, onOpenDoc = { docFor = it }, onBack = { editId = null })
            else -> UnloadList(onNew = { editId = UUID.randomUUID().toString() }, onOpen = { editId = it }, onScan = { docFor = "" }, onOpenReceive = onOpenReceive, onBack = onBack)
        }
    }
}

private data class UlgPage(val edit: String?, val doc: String?)

@Composable
private fun UnloadList(onNew: () -> Unit, onOpen: (String) -> Unit, onScan: () -> Unit, onOpenReceive: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("Разгрузка", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onNew,
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
                            onClick = { onOpen(r.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.name.ifBlank { r.car.ifBlank { "Без имени" } },
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!r.saved) Text("черновик", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                }
                                Text(fmtTime(r.arrivedAt), style = MaterialTheme.typography.bodyMedium)
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
                OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Сканировать документ", maxLines = 1) }
                OutlinedButton(onClick = onOpenReceive, modifier = Modifier.weight(1f)) { Text("Приём товаров", maxLines = 1) }
            }
        }
    }
}

// ====================================================================== создание / редактирование

/** Номер машины: только латиница ЗАГЛАВНЫМИ, цифры, пробел и дефис; русские буквы-двойники (А, В, Е, К, М, Н, О, Р, С, Т, У, Х) превращаются в латинские. */
private val CAR_MAP = mapOf(
    'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H',
    'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X'
)

private fun ulgCar(s: String): String {
    val sb = StringBuilder()
    for (ch in s.uppercase()) {
        val c = CAR_MAP[ch] ?: ch
        if (c in 'A'..'Z' || c in '0'..'9' || c == ' ' || c == '-') sb.append(c)
    }
    return sb.toString().take(15)
}

/** Один барабан: вертикальный список, значение под подсветкой по центру выбрано. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UlgWheel(labels: List<String>, index: Int, modifier: Modifier = Modifier, onChange: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val itemH = 44.dp
    val boxH = 220.dp
    val st = rememberLazyListState(initialFirstVisibleItemIndex = index.coerceIn(0, labels.lastIndex))
    val cur by rememberUpdatedState(onChange)
    var last by remember { mutableIntStateOf(index) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            val li = st.layoutInfo
            val c = (li.viewportStartOffset + li.viewportEndOffset) / 2
            li.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - c) }?.index
        }.collect { i ->
            if (i != null && i != last) {
                last = i
                cur(i)
                Haptics.pulse(ctx, Haptics.PICKER)
            }
        }
    }
    LazyColumn(
        state = st,
        flingBehavior = rememberSnapFlingBehavior(st),
        contentPadding = PaddingValues(vertical = (boxH - itemH) / 2),
        modifier = modifier.height(boxH)
    ) {
        itemsIndexed(labels) { i, t ->
            val sel = i == last
            Box(
                Modifier.fillMaxWidth().height(itemH).clickable { scope.launch { st.animateScrollToItem(i) } },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    t,
                    fontSize = if (sel) 22.sp else 17.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Выбор даты и времени барабанами: день · месяц · год | часы : минуты. */
@Composable
private fun UlgDateTimeDialog(initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val cal0 = remember { Calendar.getInstance().apply { timeInMillis = initial } }
    var year by remember { mutableIntStateOf(cal0.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(cal0.get(Calendar.MONTH) + 1) }
    var day by remember { mutableIntStateOf(cal0.get(Calendar.DAY_OF_MONTH)) }
    var hour by remember { mutableIntStateOf(cal0.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableIntStateOf(cal0.get(Calendar.MINUTE)) }
    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val years = remember { ((minOf(thisYear, year) - 3)..(maxOf(thisYear, year) + 1)).toList() }
    val dim = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
    val dayShown = minOf(day, dim)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f), shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("Когда привезли", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier.align(Alignment.Center).fillMaxWidth().height(44.dp)
                            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer)
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // число дней зависит от месяца: барабан дней пересоздаётся при смене длины месяца
                        key(dim) {
                            UlgWheel((1..dim).map { "%02d".format(it) }, dayShown - 1, Modifier.weight(1f)) { day = it + 1 }
                        }
                        UlgWheel((1..12).map { "%02d".format(it) }, month - 1, Modifier.weight(1f)) { month = it + 1 }
                        UlgWheel(years.map { it.toString() }, years.indexOf(year).coerceAtLeast(0), Modifier.weight(1.4f)) { year = years[it] }
                        Spacer(Modifier.width(10.dp))
                        UlgWheel((0..23).map { "%02d".format(it) }, hour, Modifier.weight(1f)) { hour = it }
                        Text(":", fontWeight = FontWeight.Bold)
                        UlgWheel((0..59).map { "%02d".format(it) }, minute, Modifier.weight(1f)) { minute = it }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onPick(Calendar.getInstance().apply { clear(); set(year, month - 1, dayShown, hour, minute, 0) }.timeInMillis)
                    }) { Text("Готово") }
                }
            }
        }
    }
}

/** Виды груза. По умолчанию — паллеты; нажатием на вид груза его можно сменить. */
private val ULG_CARGO = listOf("Паллеты", "Einwegpalette", "Картоны / посылки")

private data class UlgCargo(val type: Int, val qty: String)

@Composable
private fun UnloadEditScreen(id: String, onOpenDoc: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val initial = remember(id) { UnloadStore.get(id) }
    val now0 = remember(id) { System.currentTimeMillis() }
    var name by remember(id) { mutableStateOf(initial?.name ?: "") }
    var car by remember(id) { mutableStateOf(ulgCar(initial?.car ?: "")) }
    var country by remember(id) { mutableStateOf(initial?.carCountry?.ifBlank { "DE" } ?: "DE") }
    var pickCountry by remember { mutableStateOf(false) }
    // груз: строки «вид + количество»; по умолчанию одна строка «Паллеты»
    val cargo = remember(id) {
        mutableStateListOf<UlgCargo>().apply {
            if (initial != null) {
                if (initial.pallets > 0) add(UlgCargo(0, initial.pallets.toString()))
                if (initial.ew > 0) add(UlgCargo(1, initial.ew.toString()))
                if (initial.cartons > 0) add(UlgCargo(2, initial.cartons.toString()))
            }
            if (isEmpty()) add(UlgCargo(0, ""))
        }
    }
    fun cargoSum(t: Int) = cargo.filter { it.type == t }.sumOf { it.qty.toIntOrNull() ?: 0 }
    val cargoKey = cargo.joinToString(";") { it.type.toString() + ":" + it.qty }
    // время прибытия фиксируется само в момент открытия «Новой разгрузки»; его можно поправить
    var arrived by remember(id) { mutableLongStateOf(initial?.arrivedAt ?: now0) }
    // новая запись открывается сразу для ввода; готовая — только для просмотра, править её можно по карандашу
    var editing by remember(id) { mutableStateOf(initial == null) }
    var err by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickDt by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    val live = UnloadStore.items.firstOrNull { it.id == id }

    /** Пишет только поля формы: данные документа (EB, Lieferant) не трогает. */
    fun persist(markSaved: Boolean = false, force: Boolean = false) {
        if (gone || !editing && !markSaved && !force) return
        val p = cargoSum(0)
        val w = cargoSum(1)
        val c = cargoSum(2)
        val has = name.isNotBlank() || car.isNotBlank() || p > 0 || w > 0 || c > 0 || force || markSaved || UnloadStore.get(id) != null
        if (!has) return
        val base = UnloadStore.get(id) ?: UnloadRec(id = id, arrivedAt = arrived, createdAt = now0, updatedAt = now0)
        UnloadStore.upsert(
            ctx,
            base.copy(
                name = name.trim(), car = ulgCar(car).trim(), carCountry = country, pallets = p, ew = w, cartons = c, arrivedAt = arrived,
                saved = base.saved || markSaved, updatedAt = System.currentTimeMillis()
            )
        )
    }

    // автосохранение (только в режиме правки): при каждом изменении полей с короткой паузой и при выходе из окна
    LaunchedEffect(name, car, country, cargoKey, arrived, editing) {
        if (!editing) return@LaunchedEffect
        delay(400)
        persist()
    }
    DisposableEffect(id) { onDispose { persist() } }
    BackHandler { persist(); onBack() }

    val c = Calendar.getInstance().apply { timeInMillis = arrived }
    val dateTxt = "%02d.%02d.%04d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
    val timeTxt = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))

    @Composable
    fun row(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
            Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { persist(); onBack() }) { Text("‹ Назад") }
            Spacer(Modifier.weight(1f))
            // единственная кнопка изменения записи
            if (!editing) {
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, contentDescription = "Изменить") }
            }
        }
        Text(
            if (initial == null) "Новая разгрузка" else name.ifBlank { "Разгрузка" },
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(12.dp))

        if (editing) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Имя (кто привёз / что привезли)") },
                placeholder = { Text("UPS, DHL, перевозчик, Bigbag, картоны, товары…") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text("Страна номера машины", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { pickCountry = true }, modifier = Modifier.fillMaxWidth()) {
                Text(ulgFlag(country) + "  " + ulgCountry(country).name + "  (" + country + ")", modifier = Modifier.weight(1f))
                Text("▾")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = car, onValueChange = { car = ulgCar(it); err = "" },
                label = { Text("Номер машины *") },
                placeholder = { Text("HH ER 234") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                isError = err.isNotEmpty(), modifier = Modifier.fillMaxWidth()
            )
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("Груз (нажмите на вид груза, чтобы изменить)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            cargo.forEachIndexed { i, line ->
                var menu by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1.3f)) {
                        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text(ULG_CARGO[line.type], maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("▾")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            ULG_CARGO.forEachIndexed { t, label ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { cargo[i] = line.copy(type = t); menu = false })
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = line.qty, onValueChange = { cargo[i] = line.copy(qty = it.filter { ch -> ch.isDigit() }.take(5)) },
                        label = { Text("Кол-во") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                    )
                    if (cargo.size > 1) {
                        IconButton(onClick = { cargo.removeAt(i) }) { Icon(Icons.Default.Close, contentDescription = "Убрать") }
                    }
                }
            }
            TextButton(onClick = { cargo.add(UlgCargo(if (cargo.any { it.type == 0 }) 2 else 0, "")) }) { Text("+ Добавить груз") }
            Spacer(Modifier.height(14.dp))
            Text("Когда привезли", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDt = true }) { Text("$dateTxt  $timeTxt") }
                TextButton(onClick = { arrived = System.currentTimeMillis() }) { Text("Сейчас") }
            }
        } else {
            row("Имя", name)
            row("Номер машины", if (car.isBlank()) "" else ulgFlag(country) + " " + car + " (" + country + ")")
            row("Привезли", "$dateTxt $timeTxt")
            row("Паллеты", cargoSum(0).toString())
            row("Einwegpalette", cargoSum(1).toString())
            row("Картоны / посылки", cargoSum(2).toString())
        }

        Spacer(Modifier.height(16.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Документы", style = MaterialTheme.typography.titleSmall)
                val docs = live?.allDocs().orEmpty()
                val hasFirst = live != null && (live.eb.isNotBlank() || live.lieferant.isNotBlank() || live.docNo.isNotBlank())
                if (docs.isEmpty()) {
                    Text("Документ ещё не распознан", style = MaterialTheme.typography.bodyMedium)
                }
                docs.forEachIndexed { i, d ->
                    if (docs.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        Text("Документ ${i + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    if (d.eb.isNotBlank()) Text("EB: ${d.eb}", style = MaterialTheme.typography.bodyLarge)
                    if (d.lieferant.isNotBlank()) Text("Lieferant: ${d.lieferant}", style = MaterialTheme.typography.bodyLarge)
                    if (d.docNo.isNotBlank()) Text("Номер документа: ${d.docNo}", style = MaterialTheme.typography.bodyLarge)
                    val extraIdx = i - (if (hasFirst) 1 else 0)
                    if (editing && extraIdx >= 0) {
                        TextButton(onClick = {
                            UnloadStore.update(ctx, id) { cur -> cur.copy(extraDocs = cur.extraDocs.filterIndexed { j, _ -> j != extraIdx }, updatedAt = System.currentTimeMillis()) }
                        }) { Text("Убрать этот документ", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (live != null && live.receipts.isNotEmpty()) {
                    Text("Приёмов привязано: ${live.receipts.size}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                if (!hasFirst) {
                    OutlinedButton(onClick = { persist(force = true); onOpenDoc(id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Сканировать / распознать документ")
                    }
                } else {
                    OutlinedButton(onClick = { persist(force = true); onOpenDoc("+" + id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("+ Добавить документ")
                    }
                    if (editing) {
                        TextButton(onClick = { persist(force = true); onOpenDoc(id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Распознать первый документ заново")
                        }
                    }
                }
            }
        }

        if (editing) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (car.isBlank()) { err = "Укажите номер машины"; return@Button }
                    persist(markSaved = true)
                    if (initial == null) onBack() else editing = false
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
    }

    if (pickDt) {
        UlgDateTimeDialog(arrived, onDismiss = { pickDt = false }, onPick = { arrived = it; pickDt = false })
    }
    if (pickCountry) {
        AlertDialog(
            onDismissRequest = { pickCountry = false },
            title = { Text("Страна номера машины") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ULG_COUNTRIES.forEach { cn ->
                        Row(
                            Modifier.fillMaxWidth().clickable { country = cn.code; pickCountry = false }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ulgFlag(cn.code), fontSize = 22.sp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                cn.name + "  (" + cn.code + ")",
                                fontWeight = if (cn.code == country) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickCountry = false }) { Text("Закрыть") } }
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
fun UnloadDocScreen(prefs: Prefs, targetId: String?, addMode: Boolean = false, onBack: () -> Unit) {
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
        Text(if (addMode) "Добавить документ" else "Распознавание документа", style = MaterialTheme.typography.headlineSmall)
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
                        val firstEmpty = it.eb.isBlank() && it.lieferant.isBlank() && it.docNo.isBlank()
                        val nd = UnloadDoc(eb.trim(), lieferant.trim(), docNo.trim(), files)
                        when {
                            // обычное распознавание или у записи ещё нет документа: пишем в первый документ
                            !addMode || firstEmpty -> it.copy(
                                eb = nd.eb, lieferant = nd.lieferant, docNo = nd.docNo,
                                docFiles = (it.docFiles + files).distinct(), updatedAt = now
                            )
                            // такой документ уже есть — не дублируем, только добавляем файл
                            it.allDocs().any { d -> d.eb == nd.eb && d.lieferant == nd.lieferant && d.docNo == nd.docNo } ->
                                it.copy(docFiles = (it.docFiles + files).distinct(), updatedAt = now)
                            // «Добавить»: следующий документ той же разгрузки
                            else -> it.copy(extraDocs = it.extraDocs + nd, updatedAt = now)
                        }
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
