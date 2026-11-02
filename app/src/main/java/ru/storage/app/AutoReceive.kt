package ru.storage.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONArray
import org.json.JSONObject
import android.widget.Toast
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID
import java.io.File
import java.util.Locale

/** Дата и время из имени файла: 8 цифр — дата (ДДММГГГГ), 4 цифры — время (ЧЧММ), среди любых букв и знаков. */
object Stamp {
    private fun validDate(d: Int, m: Int, y: Int): Boolean {
        if (y !in 2000..2100 || m !in 1..12 || d < 1) return false
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val dim = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return d <= dim[m - 1]
    }

    private fun date8(s: String): Triple<Int, Int, Int>? {
        val a = s.substring(0, 2).toInt()
        val b = s.substring(2, 4).toInt()
        val c = s.substring(4, 8).toInt()
        if (validDate(a, b, c)) return Triple(a, b, c) // ДДММГГГГ
        val y = s.substring(0, 4).toInt()
        val m = s.substring(4, 6).toInt()
        val d = s.substring(6, 8).toInt()
        if (validDate(d, m, y)) return Triple(d, m, y) // ГГГГММДД
        return null
    }

    private fun time4(s: String): Pair<Int, Int>? {
        val h = s.substring(0, 2).toInt()
        val m = s.substring(2, 4).toInt()
        return if (h in 0..23 && m in 0..59) h to m else null
    }

    /** "12.08.2026 12:45", только дата, либо "none". */
    fun parse(name: String): String {
        val base = name.substringBeforeLast('.', name)
        val runs = Regex("\\d+").findAll(base).map { it.value }.toList()
        var date: Triple<Int, Int, Int>? = null
        var time: Pair<Int, Int>? = null

        // 1) цепочка из 12+ цифр: дата + время подряд
        for (r in runs) {
            if (r.length >= 12) {
                val d = date8(r.substring(0, 8))
                val t = time4(r.substring(8, 12))
                if (d != null && t != null) {
                    date = d
                    time = t
                    break
                }
            }
        }
        // 2) группа из 8 цифр — дата, после неё группа из 4 цифр — время
        if (date == null) {
            val idx = runs.indexOfFirst { it.length == 8 && date8(it) != null }
            if (idx >= 0) {
                date = date8(runs[idx])
                val rest = runs.drop(idx + 1)
                time = rest.firstOrNull { it.length == 4 && time4(it) != null }?.let { time4(it) }
                    ?: rest.joinToString("").takeIf { it.length >= 4 }?.let { time4(it.substring(0, 4)) }
            }
        }
        // 3) цифры разделены знаками (12.08.2026 12-45)
        if (date == null) {
            val all = runs.joinToString("")
            if (all.length >= 12) {
                val d = date8(all.substring(0, 8))
                if (d != null) {
                    date = d
                    time = time4(all.substring(8, 12))
                }
            }
        }

        val d = date ?: return "none"
        val ds = String.format(Locale.ROOT, "%02d.%02d.%04d", d.first, d.second, d.third)
        val t = time ?: return ds
        return ds + " " + String.format(Locale.ROOT, "%02d:%02d", t.first, t.second)
    }
}

class PageState {
    var status by mutableStateOf("ожидает")
    var ok by mutableStateOf(false)
}

/**
 * Строка списка. qty — количество по документу (в автоприёме — принятое, в приёме по имени — плановое).
 * fact и defect вводятся вручную в приёме по имени; extra — товар добавлен вручную (нет в плане).
 */
class ItemRow(
    name: String,
    qty: String,
    val extra: Boolean = false,
    productId: String = "",
    /** только автоприём: "ok" (✓), "missing" (✕ не пришло), "changed" (другое кол-во), "nomark" (без отметки) */
    val status: String = "",
    /** печатное количество по документу (автоприём) */
    val printed: String = ""
) {
    var name by mutableStateOf(name)
    var qty by mutableStateOf(qty)
    var productId by mutableStateOf(productId)

    /** Штрих-код товара ("" — не привязан). Привязывается сканером; при сохранении попадает в каталог и в историю приёма. */
    var barcode by mutableStateOf("")
    var fact by mutableStateOf("")
    var defect by mutableStateOf("")

    /** Фото брака (имена файлов в медиа-папке), снимаются в окне «Брак»; печатаются на последнем листе документа. */
    val defectPhotos = mutableStateListOf<String>()

    /** Фото товара (имя файла в медиа-папке), снятое при добавлении товара вручную; печатается на последнем листе документа. */
    var photo by mutableStateOf("")

    /** Срок годности (дд.мм.гггг), только «Приём B-Ware»; "" — не указан. */
    var expiry by mutableStateOf("")

    /** B-Ware: выбранные причины и виды повреждений позиции (список вариантов зависит от окна) и примечание. */
    val reasons = mutableStateListOf<String>()
    var note by mutableStateOf("")

    /** Поля карточки (производитель, вес, тип), разобранные ИИ; только для товаров, которых нет в каталоге. */
    var parts by mutableStateOf<ProductParts?>(null)
}

/**
 * Номер машины и прицепа партии (машина, прицеп). Если их не вписали вручную, берутся из «Приём B-Ware»,
 * когда номер заказа совпадает: там они уже введены при приёме испорченного товара.
 */
fun AutoJob.vehicle(): Pair<String, String> {
    var t = truck.trim()
    var r = trailer.trim()
    if (t.isEmpty() && r.isEmpty()) {
        val o = InvoiceCheck.clean(order)
        val h = BWareHolder
        if (o.isNotEmpty() && InvoiceCheck.clean(h.order).equals(o, ignoreCase = true)) {
            t = h.truck.trim()
            r = h.trailer.trim()
        }
    }
    return t.uppercase() to r.uppercase()
}

/**
 * Автоприём: PDF → листы → чёрно-белые картинки → ИИ (по одному листу, с ожиданием и повтором).
 * Живёт отдельно от экрана, чтобы поворот экрана не прерывал распознавание.
 */
class AutoJob(private val app: Context, val file: IncomingFile, startNameMode: Boolean) {
    val appContext: Context get() = app
    val pages = mutableStateListOf<PageState>()
    var running by mutableStateOf(false)

    /** Партия пришла из очереди автоприёма: при паузе автоприёма её распознавание ждёт «Продолжить». */
    @Volatile var fromQueue = false
    var message by mutableStateOf("")
    var sender by mutableStateOf("none")
    var order by mutableStateOf("none")

    /** Номер машины и прицепа: печатаются в шапке документа. "" — не указаны (тогда берутся из «Приём B-Ware» с тем же номером заказа). */
    var truck by mutableStateOf("")
    var trailer by mutableStateOf("")
    var stamp by mutableStateOf(Stamp.parse(file.name))
    val items = mutableStateListOf<ItemRow>()
    var saved by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var newProductCount by mutableIntStateOf(0)

    /** Причина, по которой накладная не принята (заказ или документ уже приняты). Пусто — всё в порядке. */
    var blocked by mutableStateOf("")

    /** Накладная привязана к уже принятому приёму (без новых товаров): что именно сделано. */
    var attachedNote by mutableStateOf("")

    /** Решения по товарам, которые за этот день уже принимались: ключ — название без знаков, true — принять, false — отказать. */
    val dupDecision = mutableStateMapOf<String, Boolean>()

    /** Товары, ожидающие решения «принять / отказать» (автоприём). */
    var sameDay by mutableStateOf<List<SameDay>>(emptyList())

    private var idDone = false
    private var checkedOrder = ""
    private var hashCache: String? = null

    /** SHA-256 файла накладной (считается один раз). Вызывать не в главном потоке. */
    fun docHash(): String = hashCache ?: InvoiceCheck.sha256(File(file.path)).also { hashCache = it }

    /** true — приём по имени (все строки как есть, план = печатное количество); false — автоприём (по отметкам ручкой). */
    var nameMode by mutableStateOf(startNameMode)
        private set

    /** Шаг приёма по имени: 0 — ввод количества, 1 — итог. Хранится в партии, чтобы «свёрнутая» партия открывалась там же, где её оставили. */
    var step by mutableIntStateOf(0)

    /** Короткая строка для списка «Партии в работе». */
    val statusLine: String
        get() = when {
            saved -> "Сохранено"
            saving -> "Сохранение…"
            running -> message.ifBlank { "Распознавание…" }
            !started -> "Ожидает открытия"
            hasFailed -> "Не все листы распознаны"
            blocked.isNotBlank() -> "Не принято: $blocked"
            attachedNote.isNotBlank() -> "Накладная привязана к приёму"
            !nameMode && autoState == "samedup" -> "Такие товары уже принимались сегодня — откройте и решите"
            !nameMode && autoState == "unmarked" -> "Отмечены не все позиции — откройте в «Приём по имени»"
            !nameMode && autoState == "error" -> "Не сохранено: $autoError"
            else -> if (nameMode) "Идёт подсчёт · позиций: ${items.size}" else "Проверьте и сохраните · позиций: ${items.size}"
        }

    /**
     * Автоприём: "" — ещё не решали; "unmarked" — на накладной нет отметок (✓/✕), не сохранена;
     * "error" — сохранить не получилось (текст в autoError); "saved" — сохранена автоматически.
     */
    var autoState by mutableStateOf("")
    var autoError by mutableStateOf("")

    fun setMode(name: Boolean) {
        if (nameMode == name) return
        nameMode = name
        autoState = ""
        if (results.isNotEmpty()) rebuild()
    }

    /** «Открыть в приёме по имени»: партия переключается в ручной режим, экран открывается тем же файлом. */
    fun openInNameMode() {
        step = 0
        setMode(true)
    }

    /** Все позиции с отметкой: ✓, ✕ или вписанное ручкой количество. Без отметки хотя бы у одной — автоприём не сохраняет. */
    fun markedComplete(): Boolean = items.isNotEmpty() && items.all {
        it.status == "ok" || it.status == "missing" || (it.status == "changed" && it.qty.isNotBlank())
    }

    /** Автоприём: если все позиции отмечены — сохраняем сразу (история, Firebase, локальная база, накладная); иначе не сохраняем. */
    suspend fun autoFinish() {
        if (nameMode || saved || saving || items.isEmpty()) return
        // на накладной нет ни одной галочки и ни одного крестика — сохраняем всё как есть (количество по документу, ноль тоже ноль)
        val noMarksAtAll = items.all { it.status == "nomark" }
        if (!noMarksAtAll && !markedComplete()) {
            autoState = "unmarked"
            return
        }
        val dups = pendingSameDay()
        if (dups.isNotEmpty()) {
            sameDay = dups
            autoState = "samedup"
            return
        }
        autoError = ""
        message = "Сохраняю приём…"
        val err = try {
            saveJob(app, ProductDb.get(app), this, file, AutoReceiveHolder.receiver, false)
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        if (err == null) {
            autoState = "saved"
            message = "Приём сохранён автоматически"
        } else {
            autoState = "error"
            autoError = err
        }
    }

    /**
     * Только админ, кнопка «Принят»: распознанное принимается автоматически, даже без галочек и крестиков
     * (количество — по документу). Если такая партия уже есть в базе — она перезаписывается.
     * Возвращает текст ошибки или null.
     */
    suspend fun adminAccept(): String? {
        if (!Session.isAdmin) return "Принимать так может только администратор"
        if (saved || saving || running) return "Партия ещё обрабатывается"
        if (items.isEmpty()) return "Нет распознанных товаров"
        if (hasFailed) return "Не все листы распознаны — сначала повторите распознавание, иначе часть товаров потеряется"
        if (nameMode) {
            if (items.any { it.fact.isNotBlank() }) return "В «По наименованию» уже введено количество — завершите приём там"
            setMode(false)
        }
        blocked = ""
        attachedNote = ""
        autoError = ""
        message = "Принимаю…"
        val err = try {
            saveJob(app, ProductDb.get(app), this, file, AutoReceiveHolder.receiver, false, forceAccept = true, overwrite = true)
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        if (err == null) {
            autoState = "saved"
            message = "Принято администратором"
        } else {
            autoState = "error"
            autoError = err
        }
        return err
    }

    /** Товары, которые за день приёма уже принимались с тем же количеством и по которым ещё нет решения. */
    suspend fun pendingSameDay(): List<SameDay> {
        val at = stampMillis(stamp.trim()) ?: System.currentTimeMillis()
        val rows = items.filter { it.name.isNotBlank() }
            .map { DocItem(it.name.trim(), if (nameMode) it.fact else it.qty) }
        return InvoiceCheck.sameDayItems(ProductDb.get(app), rows, at)
            .filter { dupDecision[InvoiceCheck.norm(it.name)] == null }
            .distinctBy { InvoiceCheck.norm(it.name) }
    }

    /** Накладная привязывается к уже принятому приёму: товары не дублируются, документ и недостающие данные дописываются. */
    private suspend fun attachTo(r: Receipt, how: String): Boolean {
        saving = true
        message = "Привязываю накладную к приёму…"
        val st = stamp.trim().let { if (it.length == 16) it else "" }
        val err = try {
            InvoiceCheck.attach(app, ProductDb.get(app), r, file, sender, order, st, withContext(Dispatchers.IO) { docHash() })
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        saving = false
        if (err != null) {
            blocked = "Не удалось привязать накладную: $err"
            return true
        }
        val shown = ArrayList<ItemRow>()
        r.items.forEach {
            val row = ItemRow(it.name, it.qty, status = "ok", printed = it.plan.ifEmpty { it.qty })
            row.fact = it.qty
            shown.add(row)
        }
        items.clear()
        items.addAll(shown)
        attachedNote = InvoiceCheck.attachNote(r, how, sender, order)
        saved = true
        autoState = "saved"
        message = attachedNote
        return true
    }

    /** Этап 1 уже знает номер заказа: заказ принят с накладной — стоп; принят без накладной — привязываем документ. true — партия закончена. */
    private suspend fun checkOrderEarly(): Boolean {
        val o = InvoiceCheck.clean(order)
        if (o.isEmpty()) return false
        checkedOrder = o
        val r = InvoiceCheck.byOrder(ProductDb.get(app), o) ?: return false
        if (r.invoices.isEmpty()) return attachTo(r, "по номеру заказа")
        blocked = "заказ $o уже принят ${fmtTime(r.receivedAt)} вместе с накладной — повторно не принимаю. Остальное с документа не запрашивалось."
        return true
    }

    /** После распознавания всех листов: заказ, отправитель + товары, дата + товары. true — документ привязан к существующему приёму или отклонён. */
    private suspend fun resolveExisting(): Boolean {
        val db = ProductDb.get(app)
        val o = InvoiceCheck.clean(order)
        if (o.isNotEmpty() && o != checkedOrder) {
            checkedOrder = o
            val r = InvoiceCheck.byOrder(db, o)
            if (r != null) {
                if (r.invoices.isEmpty()) return attachTo(r, "по номеру заказа")
                blocked = "заказ $o уже принят ${fmtTime(r.receivedAt)} вместе с накладной — повторно не принимаю."
                return true
            }
        }
        val docItems = items.filter { !it.extra && it.name.isNotBlank() }
            .map { DocItem(it.name.trim(), it.printed.ifEmpty { it.qty }) }
        val found = InvoiceCheck.byGoods(db, sender, order, stampMillis(stamp.trim()), docItems) ?: return false
        return attachTo(found.receipt, found.by)
    }

    /** Лёгкий запрос по первым листам: только отправитель и номер заказа. Бросает исключение, если ИИ не ответил ни на один лист. */
    private suspend fun identify(doc: PageSource, n: Int, attempts: Int) {
        var s = "none"
        var o = "none"
        var answered = false
        var lastErr = ""
        val limit = minOf(n, 4)
        for (i in 0 until limit) {
            message = "Проверка накладной: лист ${i + 1}"
            val png = try {
                doc.renderBw(i)
            } catch (e: Exception) {
                continue
            }
            AutoReceiveHolder.aiLock.withLock {
                for (a in 1..attempts) {
                    try {
                        val r = Ai.identify(app, png, i + 1, n)
                        if (s == "none" && r.sender != "none") s = r.sender
                        if (o == "none" && r.order != "none") o = r.order
                        answered = true
                        break
                    } catch (e: Exception) {
                        lastErr = e.message ?: e.javaClass.simpleName
                        if (a < attempts) delay(3000L * a)
                    }
                }
            }
            if (s != "none" && o != "none") break
            if (i < limit - 1) delay(1000)
        }
        if (!answered) throw RuntimeException(lastErr.ifBlank { "ИИ не ответил" })
        if (s != "none") sender = s
        if (o != "none") order = o
    }

    private val results = HashMap<Int, PageResult>()
    private val partsCache = HashMap<String, ProductParts>()
    private var seenSender: String? = null
    private var genSender: String? = null
    private var genPng: ByteArray? = null

    /** Поставщика ещё нет в реестре: ИИ описывает раскладку его накладной, шаблон попадает в «Шаблоны накладных» со статусом «Новый». */
    private suspend fun createProfileIfNeeded() {
        val sName = genSender ?: return
        val png = genPng ?: return
        genSender = null
        genPng = null
        if (items.isEmpty() || InvoiceProfiles.find(app, sName) != null) return
        message = "Создаю шаблон накладной…"
        val extracted = items.filter { !it.extra }.take(25).joinToString("\n") { "${it.name} | ${it.printed.ifEmpty { it.qty }}" }
        val text = AutoReceiveHolder.aiLock.withLock {
            withContext(Dispatchers.IO) { InvoiceProfiles.generate(app, sName, png, extracted) }
        }
        if (text.isNotBlank()) {
            val sample = withContext(Dispatchers.IO) { InvoiceProfiles.saveSample(app, png) }
            InvoiceProfiles.save(app, InvoiceProfile(InvoiceProfiles.key(sName), sName, text, "new", 0L, sample))
            newProfileName = sName
        }
    }
    var newProfileName by mutableStateOf("")
        private set
    var parsing by mutableStateOf(false)
        private set

    /** Автоприём: для товаров, которых нет в каталоге, ИИ разбирает название на поля карточки (один запрос на ~40 строк). */
    private suspend fun parseNewProducts() {
        if (nameMode) return
        val db = ProductDb.get(app)
        val todo = items.filter { !it.extra && it.parts == null && it.name.isNotBlank() }
            .filter { partsCache[it.name.trim()] == null }
            .filter { r -> withContext(Dispatchers.IO) { r.productId.isEmpty() && db.findByName(r.name.trim()) == null } }
            .map { it.name.trim() }.distinct()
        if (todo.isNotEmpty()) {
            parsing = true
            message = "Разбор товаров…"
            try {
                // разбор названий (вес, единица, упаковка) делает телефон, без запроса к ИИ
                val res = ProductParse.parseLocal(todo)
                todo.forEachIndexed { i, n -> res.getOrNull(i)?.let { partsCache[n] = it } }
            } finally {
                parsing = false
            }
        }
        items.forEach { r -> if (r.parts == null) partsCache[r.name.trim()]?.let { r.parts = it } }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var started = false
        private set

    val hasFailed: Boolean
        get() = blocked.isBlank() && attachedNote.isBlank() && pages.isNotEmpty() && pages.any { !it.ok }

    /** Распознавание закончилось с ошибкой (не все листы или сбой до начала) — вместо «Далее» показываем «Повторить». */
    val failedState: Boolean
        get() = !running && !saved && started && (hasFailed || message.startsWith("Ошибка"))

    /** Текст ошибки для показа под строкой заказа. */
    val errorText: String
        get() = pages.firstOrNull { !it.ok && it.status.startsWith("ошибка") }?.status
            ?: if (message.startsWith("Ошибка")) message else ""

    /** Что делается прямо сейчас (для мигающей строки): состояние текущего листа. */
    val liveStatus: String
        get() = pages.firstOrNull { !it.ok && it.status != "ожидает" }?.status ?: ""

    /** Файл — таблица Excel (.xlsx): товары берутся из столбцов как при вставке в «Приём вручную», без ИИ. */
    val isExcel: Boolean
        get() = file.name.lowercase().let { it.endsWith(".xlsx") || it.endsWith(".xlsm") }

    private fun loadExcel() {
        if (running) return
        running = true
        message = "Чтение Excel…"
        scope.launch {
            try {
                val rows = Xlsx.read(app, android.net.Uri.fromFile(File(file.path)))
                val (res, how) = readExcelRows(app, rows)
                if (res == null || res.items.isEmpty()) {
                    message = "Ошибка: " + (how.ifBlank { "в файле нет товаров или не найдены столбцы названия и количества" })
                    return@launch
                }
                val db = ProductDb.get(app)
                val list = res.items.map {
                    val p = db.findByName(it.name) ?: if (it.barcode.isNotBlank()) db.findByBarcode(it.barcode) else null
                    ItemRow(it.name, it.qty, productId = p?.id ?: "").also { r ->
                        r.barcode = it.barcode.ifBlank { p?.barcode ?: "" }
                    }
                }
                if (res.sender.isNotBlank()) sender = res.sender
                if (res.order.isNotBlank()) order = res.order
                if (res.stamp.isNotBlank()) stamp = res.stamp
                items.clear()
                items.addAll(list)
                pages.clear()
                pages.add(PageState().also { it.ok = true; it.status = "готово" })
                val skipped = res.badLines.size + res.zeroLines.size
                message = "Готово: позиций — ${list.size}" + (if (skipped > 0) ", пропущено строк: $skipped" else "") +
                    (if (how.isNotBlank()) ". $how" else "")
            } catch (e: Exception) {
                message = "Ошибка: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                running = false
            }
        }
    }

    fun startOnce() {
        if (started) return
        started = true
        if (isExcel) loadExcel() else start(false)
    }

    fun start(onlyFailed: Boolean) {
        if (isExcel) { loadExcel(); return }
        if (running) return
        running = true
        if (!saved) autoState = ""
        message = "Подготовка…"
        scope.launch {
            var doc: PageSource? = null
            try {
                doc = openPageSource(File(file.path))
                val n = doc.count
                if (pages.size != n) {
                    pages.clear()
                    repeat(n) { pages.add(PageState()) }
                    results.clear()
                }
                val attempts = AiCfg.attempts(app).coerceIn(1, 8)
                // true — «проверка с шаблоном»; false — обычная: каждый лист в ИИ ровно один раз, без шаблонов
                val tpl = AiCfg.templateMode(app)
                // номер заказа и отправитель приложение определяет само (OCR на телефоне), ИИ их не читает
                if (!onlyFailed && !idDone) {
                    message = "Поиск заказа и отправителя…"
                    try {
                        val ids = DocIds.find(doc) { message = "Поиск заказа и отправителя: лист ${it + 1} из $n" }
                        if (InvoiceCheck.isNone(sender) && ids.sender != "none") sender = ids.sender
                        if (InvoiceCheck.isNone(order) && ids.order != "none") order = ids.order
                    } catch (e: Exception) {
                    }
                    idDone = true
                }
                // шаблоны накладных поставщиков (из Firebase, не чаще раза в 5 минут) — только в режиме с шаблоном
                if (tpl) try { InvoiceProfiles.refresh(app) } catch (e: Exception) { }
                // Единственная защита от повторов — тот же самый файл (по содержимому): его второй раз в ИИ не отправляем.
                // Другой файл с тем же номером заказа (продолжение, повторный снимок) распознаётся и принимается как обычно.
                if (!onlyFailed && !saved) {
                    val h = withContext(Dispatchers.IO) { docHash() }
                    val dup = try { Cloud.findDuplicate(ProductDb.get(app), "", "", "", h) } catch (e: Exception) { null }
                    if (dup != null) {
                        blocked = "этот файл уже принят ${fmtTime(dup.receipt.receivedAt)} — повторно не отправляю на распознавание"
                        message = "Не принято: $blocked"
                        return@launch
                    }
                }
                var rules = ""
                var rulesChecked = !tpl
                var needGen = false
                val knownSender = InvoiceCheck.clean(sender)
                if (tpl && knownSender.isNotEmpty() && idDone) {
                    // отправитель известен с этапа 1: подтверждённый шаблон используем сразу для всех листов
                    seenSender = knownSender
                    rulesChecked = true
                    InvoiceProfiles.confirmedRules(app, knownSender)?.let { rules = it }
                    needGen = InvoiceProfiles.find(app, knownSender) == null
                }
                for (i in 0 until n) {
                    val st = pages[i]
                    if (onlyFailed && st.ok) continue
                    while (fromQueue && AutoPause.paused) {
                        st.status = "пауза"
                        message = "Автоприём на паузе"
                        delay(1000)
                    }
                    st.ok = false
                    message = "Лист ${i + 1} из $n"
                    st.status = "подготовка…"
                    var png = try {
                        doc.renderBw(i)
                    } catch (e: Exception) {
                        st.status = "ошибка листа: ${e.message}"
                        continue
                    }
                    // пустой лист (кроме первого) в ИИ не отправляем: экономия токенов
                    if (i > 0 && isBlankPage(png)) {
                        results[i] = PageResult("none", "none", emptyList())
                        st.ok = true
                        st.status = "пустой лист — пропущен"
                        rebuild()
                        continue
                    }
                    // одновременно в ИИ идёт один лист (из любой партии) — чтобы не упереться в лимиты
                    AutoReceiveHolder.aiLock.withLock {
                        for (a in 1..attempts) {
                            st.status = if (a == 1) "отправка…" else "повтор $a из $attempts…"
                            try {
                                // со 2-й попытки — серая картинка покрупнее вместо чёрно-белой
                                if (a == 2) png = try { doc.renderBw(i, 1700, false) } catch (e: Exception) { png }
                                val r = Ai.recognize(app, png, i + 1, n, rules, !nameMode)
                                // повтор (двойной расход токенов) — только на первом листе без товаров: там таблица почти всегда есть
                                if (i == 0 && r.items.isEmpty() && a < attempts) {
                                    throw RuntimeException("не нашёл товары, пробую в другом виде")
                                }
                                results[i] = r
                                st.ok = true
                                st.status = "готово"
                                if (tpl && needGen && genPng == null && r.items.isNotEmpty()) {
                                    genSender = knownSender
                                    genPng = png
                                }
                                if (!rulesChecked && r.sender != "none") {
                                    rulesChecked = true
                                    seenSender = r.sender
                                    val rl = InvoiceProfiles.confirmedRules(app, r.sender)
                                    if (rl != null) {
                                        // у поставщика есть подтверждённый шаблон: этот лист читаем ещё раз с правилами, остальные — сразу с ними
                                        rules = rl
                                        st.status = "уточнение по шаблону…"
                                        try { results[i] = Ai.recognize(app, png, i + 1, n, rl, !nameMode) } catch (e: Exception) { }
                                        st.status = "готово"
                                    } else if (InvoiceProfiles.find(app, r.sender) == null && r.items.isNotEmpty()) {
                                        genSender = r.sender
                                        genPng = png
                                    }
                                }
                                break
                            } catch (e: Exception) {
                                st.status = "ошибка: " + (e.message ?: e.javaClass.simpleName)
                                if (a < attempts) delay(3000L * a)
                            }
                        }
                    }
                    // палеты: ИИ иногда берёт число палет вместо штук; сверяем с текстом листа (OCR на телефоне, без токенов)
                    results[i]?.let { r0 ->
                        if (st.ok && r0.items.isNotEmpty()) {
                            try {
                                val fixed = PalletFix.apply(doc, i, r0.items)
                                if (fixed !== r0.items) results[i] = r0.copy(items = fixed)
                            } catch (e: Exception) {
                            }
                        }
                    }
                    rebuild()
                    if (i < n - 1) delay(2000)
                }
                while (fromQueue && AutoPause.paused) {
                    message = "Автоприём на паузе"
                    delay(1000)
                }
                val failed = pages.count { !it.ok }
                message = if (failed == 0) "Готово: распознано листов — $n"
                else "Не распознано листов: $failed из $n"
                // Проверка накладной: не относится ли документ к уже принятому приёму (заказ / отправитель + товары / дата + товары)
                // Привязка к «такому же» заказу или по составу товаров отключена: продолжение заказа принимается как новый файл
                val attached = false
                if (!attached) {
                    try {
                        parseNewProducts()
                        if (failed == 0) message = "Готово: распознано листов — $n"
                    } catch (e: Exception) {
                    }
                }
                var profileNote: String
                if (!tpl) {
                    profileNote = "Обычная проверка (без шаблона)"
                } else if (failed != 0) {
                    profileNote = "Шаблон не создан: не все листы распознаны"
                } else {
                    profileNote = try {
                        createProfileIfNeeded()
                        val sn = seenSender
                        when {
                            newProfileName.isNotBlank() -> "Новый шаблон накладной: $newProfileName (Главная → Шаблоны накладных)"
                            sn == null -> "Шаблон не создан: поставщик на листах не определён"
                            else -> InvoiceProfiles.find(app, sn)?.let {
                                if (it.status == "new") "Шаблон поставщика «$sn» уже создан и ждёт подтверждения"
                                else "Использован подтверждённый шаблон поставщика «$sn»"
                            } ?: "Шаблон не создан"
                        }
                    } catch (e: Exception) {
                        "Шаблон не создан: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
                message = "Готово: распознано листов — $n. $profileNote"
                if (attached) message = if (blocked.isNotBlank()) "Не принято: $blocked" else attachedNote
                if (failed == 0 && !nameMode && !attached) autoFinish()
            } catch (e: Exception) {
                message = "Ошибка: " + (e.message ?: e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) {
                message = "Ошибка: не хватило памяти на этом файле — повторите нераспознанные листы"
            } finally {
                doc?.close()
                running = false
            }
        }
    }

    /** Строка списка из ответа ИИ; штрих-код с документа (если он там напечатан) сразу привязывается к строке. */
    private fun rowFor(it: AiItem): ItemRow = rowFor0(it).also { r -> if (it.barcode.isNotBlank()) r.barcode = it.barcode }

    private fun rowFor0(it: AiItem): ItemRow {
        val printed = cleanQty(it.qty)
        if (nameMode) return ItemRow(it.name, printed)
        val hand = cleanQty(it.handQty)
        return when {
            hand.isNotEmpty() -> ItemRow(it.name, hand, status = "changed", printed = printed)
            it.qtyCrossed -> ItemRow(it.name, "", status = "changed", printed = printed)
            it.mark == "cross" -> ItemRow(it.name, "0", status = "missing", printed = printed)
            it.mark == "check" -> ItemRow(it.name, printed, status = "ok", printed = printed)
            else -> ItemRow(it.name, printed, status = "nomark", printed = printed)
        }
    }

    private fun rebuild() {
        if (attachedNote.isNotBlank()) return
        var s = if (InvoiceCheck.isNone(sender)) "none" else sender
        var o = if (InvoiceCheck.isNone(order)) "none" else order
        val list = ArrayList<ItemRow>()
        for (i in 0 until pages.size) {
            val r = results[i] ?: continue
            if (s == "none" && r.sender != "none") s = r.sender
            if (o == "none" && r.order != "none") o = r.order
            r.items.forEach { list.add(rowFor(it)) }
        }
        sender = s
        order = o
        if (restoredMode) {
            // после перезапуска приложения результатов старых листов в памяти нет: уже посчитанные строки не трогаем, добавляем только новые
            fun key(r: ItemRow) = InvoiceCheck.norm(r.name) + "|" + r.printed.ifEmpty { r.qty }
            val have = items.map { key(it) }.toHashSet()
            list.filter { have.add(key(it)) }.forEach { r ->
                partsCache[r.name.trim()]?.let { r.parts = it }
                items.add(r)
            }
            return
        }
        val extras = items.filter { it.extra }
        list.forEach { r -> partsCache[r.name.trim()]?.let { r.parts = it } }
        items.clear()
        items.addAll(list)
        items.addAll(extras)
    }

    // ---------- черновик на телефоне ----------
    // Каждое действие сначала записывается на телефон (файл в памяти приложения), и только при «Сохранить» уходит в Firebase.
    // Если систему выгрузит приложение из памяти, список и введённые количества вернутся при следующем запуске.

    /** Имя файла черновика; при восстановлении берётся из самого черновика. */
    var draftId: String = UUID.nameUUIDFromBytes(file.path.toByteArray()).toString()

    /** Партию убрали (сохранена и закрыта или удалена вручную) — черновик больше не пишется. */
    @Volatile var removed = false
    private var restoredMode = false

    /** Черновик нужен, пока приём не сохранён и есть что терять. */
    private fun draftNeeded(): Boolean = !saved && blocked.isBlank() && attachedNote.isBlank() && items.isNotEmpty()

    /** Подсчёт ушёл в историю и Firebase, партия закрыта или не принята — черновик удаляется. */
    private fun draftObsolete(): Boolean = saved || blocked.isNotBlank() || attachedNote.isNotBlank()

    /** Снимок партии. Читает состояния Compose, поэтому годится для snapshotFlow. */
    fun draftJson(): String {
        val arr = JSONArray()
        for (r in items.toList()) {
            arr.put(
                JSONObject().put("n", r.name).put("q", r.qty).put("x", r.extra).put("p", r.productId)
                    .put("s", r.status).put("pr", r.printed).put("f", r.fact).put("d", r.defect)
                    .put("e", r.expiry).put("b", r.barcode).put("nt", r.note).put("ph", r.photo)
                    .put("dph", JSONArray(r.defectPhotos.toList()))
                    .put("rs", JSONArray(r.reasons.toList()))
            )
        }
        val pg = JSONArray()
        pages.toList().forEach { pg.put(it.ok) }
        return JSONObject().put("v", 1).put("id", draftId).put("name", file.name).put("path", file.path).put("dn", file.driveName)
            .put("nameMode", nameMode).put("step", step).put("sender", sender).put("order", order)
            .put("truck", truck).put("trailer", trailer)
            .put("stamp", stamp).put("pagesOk", pg).put("items", arr).toString()
    }

    /** Записать черновик сразу (при сворачивании приложения). */
    fun flushDraft() {
        if (removed) return
        try {
            if (draftNeeded()) AutoDraft.write(app, draftId, file, draftJson())
        } catch (e: Exception) {
        }
    }

    /** Вернуть партию из черновика: список, количества, брак, штрих-коды и шаг. Распознавание заново не запускается. */
    fun applyDraft(o: JSONObject) {
        draftId = o.optString("id", draftId)
        sender = o.optString("sender", "none")
        order = o.optString("order", "none")
        truck = o.optString("truck")
        trailer = o.optString("trailer")
        stamp = o.optString("stamp", stamp)
        step = o.optInt("step", 0)
        val arr = o.optJSONArray("items") ?: JSONArray()
        val list = ArrayList<ItemRow>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val r = ItemRow(
                j.optString("n"), j.optString("q"), j.optBoolean("x"), j.optString("p"),
                j.optString("s"), j.optString("pr")
            )
            r.fact = j.optString("f")
            r.defect = j.optString("d")
            r.expiry = j.optString("e")
            r.barcode = j.optString("b")
            r.note = j.optString("nt")
            r.photo = j.optString("ph")
            j.optJSONArray("dph")?.let { a -> for (k in 0 until a.length()) r.defectPhotos.add(a.getString(k)) }
            j.optJSONArray("rs")?.let { a -> for (k in 0 until a.length()) r.reasons.add(a.getString(k)) }
            list.add(r)
        }
        items.clear()
        items.addAll(list)
        pages.clear()
        val pg = o.optJSONArray("pagesOk")
        if (pg != null) {
            for (i in 0 until pg.length()) pages.add(PageState().also { it.ok = pg.optBoolean(i); it.status = if (it.ok) "готово" else "ожидает" })
        }
        started = true
        restoredMode = true
    }

    /** Подсказка из каталога: у строк без штрих-кода берём код найденного по названию (или id) товара. Не в главном потоке. */
    fun fillBarcodesFromCatalog() {
        val db = ProductDb.get(app)
        for (r in items.toList()) {
            if (r.barcode.isNotBlank() || r.name.isBlank()) continue
            val p = (if (r.productId.isNotEmpty()) db.getProduct(r.productId) else null) ?: db.findByName(r.name.trim())
            if (p != null && p.barcode.isNotBlank()) r.barcode = p.barcode
        }
    }

    init {
        // пишем черновик через 0,25 с после любого изменения списка, количеств, отправителя, заказа и шага
        scope.launch {
            snapshotFlow { if (draftNeeded()) draftJson() else if (draftObsolete()) "-" else "" }
                .collectLatest { js ->
                    delay(250)
                    if (removed) return@collectLatest
                    try {
                        when (js) {
                            "" -> {}
                            "-" -> AutoDraft.delete(app, draftId)
                            else -> AutoDraft.write(app, draftId, file, js)
                        }
                    } catch (e: Exception) {
                    }
                }
        }
    }
}

/** Все партии, которые сейчас в работе. Партия живёт, пока её не сохранят и не закроют или не удалят вручную. */
object AutoReceiveHolder {
    val jobs = mutableStateListOf<AutoJob>()
    val aiLock = Mutex()

    /** Кто принимает (имя из профиля); задаётся при входе, нужен автоприёму, который сохраняет без участия экрана. */
    @Volatile var receiver: String = ""

    fun find(path: String): AutoJob? = jobs.firstOrNull { it.file.path == path }

    fun create(ctx: Context, f: IncomingFile, nameMode: Boolean): AutoJob {
        find(f.path)?.let { return it }
        return AutoJob(ctx.applicationContext, f, nameMode).also { jobs.add(it) }
    }

    /** Вернуть партии из черновиков на телефоне (после того как систему выгрузила приложение из памяти). Вызывать один раз при запуске. */
    @Synchronized
    fun restore(ctx: Context) {
        val app = ctx.applicationContext
        for (o in AutoDraft.readAll(app)) {
            try {
                val path = o.optString("path")
                if (path.isEmpty() || find(path) != null) continue
                // исходный файл лежит в кэше, систему могла его очистить — тогда берём копию из черновика
                val use = if (File(path).exists()) path else AutoDraft.copyPath(app, o.optString("id"), o.optString("name")) ?: continue
                val job = AutoJob(app, IncomingFile(o.optString("name"), use, o.optString("dn")), o.optBoolean("nameMode"))
                job.applyDraft(o)
                jobs.add(job)
            } catch (e: Exception) {
            }
        }
    }

    /** Записать все черновики сейчас (приложение уходит в фон). */
    fun flushDrafts() {
        jobs.toList().forEach { it.flushDraft() }
    }

    fun remove(job: AutoJob) {
        job.removed = true
        AutoDraft.delete(job.appContext, job.draftId)
        jobs.remove(job)
        if (Incoming.openPath == job.file.path) Incoming.openPath = null
    }

    /** Партии, которые ещё не сохранены. */
    fun unfinishedCount(): Int = jobs.count { !it.saved }
}

/** Количество без хвоста ",00": "45,00" → "45", "2,5" → "2.5"; пусто, если числа нет. */
fun cleanQty(q: String): String {
    val n = normalizeQty(q)
    val d = n.toDoubleOrNull() ?: return ""
    return if (d == Math.floor(d) && d < 1e9) d.toLong().toString() else n
}

fun normalizeQty(q: String): String {
    val m = Regex("\\d+(?:[.,]\\d+)?").find(q) ?: return ""
    return m.value.replace(',', '.')
}

fun stampMillis(stamp: String): Long? = when (stamp.length) {
    16 -> parseTime(stamp)
    10 -> parseTime("$stamp 00:00")
    else -> null
}

/** Проверка ввода в приёме по имени: факт указан у каждой позиции, брак не больше факта. */
fun checkFacts(job: AutoJob): String? {
    val rows = job.items.filter { it.name.trim().isNotEmpty() }
    if (rows.isEmpty()) return "Нет товаров"
    for (r in rows) {
        val fact = normalizeQty(r.fact).ifEmpty { "0" }
        val defect = normalizeQty(r.defect).ifEmpty { "0" }
        if (defect.toDouble() > fact.toDouble()) return "Брак больше фактического количества: ${r.name.trim()}"
    }
    return null
}

/**
 * Сохранение приёма: товары — в историю/Firebase, исходный файл (сырой, без обработки) — на Google Диск.
 * Возвращает текст ошибки или null, если всё сохранено.
 */
suspend fun saveJob(
    ctx: Context,
    db: ProductDb,
    job: AutoJob,
    f: IncomingFile,
    receiver: String,
    nameMode: Boolean,
    /** админ жмёт «Принят»: строки без отметок принимаются с количеством по документу */
    forceAccept: Boolean = false,
    /** админ: если такая партия (по номеру заказа или файлу) уже есть в базе — она перезаписывается */
    overwrite: Boolean = false
): String? {
    if (job.saved || job.saving) return null
    val rows = ArrayList<ReceiptItem>()
    val newProducts = LinkedHashMap<String, Product>()
    val bind = ArrayList<Pair<String, String>>() // товар → штрих-код, привязанный сканером
    for (r in job.items) {
        val name = r.name.trim()
        if (name.isEmpty()) continue
        // автоприём: строка с крестиком слева (не пришла) отбрасывается, в приём не попадает
        if (!nameMode && r.status == "missing") continue
        // товар, который за этот день уже принимали с тем же количеством и по которому выбрали «Отказать»
        if (job.dupDecision[InvoiceCheck.norm(name)] == false) continue
        var pid = withContext(Dispatchers.IO) { r.productId.ifEmpty { db.findByName(name)?.id ?: "" } }
        val bcRaw = r.barcode.trim()
        if (pid.isEmpty() && bcRaw.isNotEmpty() && (nameMode || r.extra)) {
            // товара нет в каталоге, но у строки есть штрих-код: заводим карточку (имя и код), строка остаётся в списке
            pid = newProducts.getOrPut(name.lowercase()) {
                Product(
                    id = UUID.randomUUID().toString(), name = name, article = "", barcode = bcRaw,
                    weight = "", unit = "", pack = "", manufacturer = "", photos = emptyList(), updatedAt = 0L
                )
            }.id
        } else if (pid.isNotEmpty() && bcRaw.isNotEmpty()) {
            bind.add(pid to bcRaw)
        }
        var bc = bcRaw
        if (bc.isEmpty() && pid.isNotEmpty()) bc = withContext(Dispatchers.IO) { db.getProduct(pid)?.barcode ?: "" }
        val pt = r.parts
        if (pid.isEmpty() && !nameMode && !r.extra && pt != null) {
            // нового товара в каталоге нет — заводим карточку по разобранным полям (имя оставляем как в накладной, чтобы в следующий раз найти его по имени)
            pid = newProducts.getOrPut(name.lowercase()) {
                Product(
                    id = UUID.randomUUID().toString(), name = name, article = "", barcode = bcRaw,
                    weight = pt.size, unit = pt.unit, pack = pt.pack, manufacturer = pt.manufacturer,
                    photos = emptyList(), updatedAt = 0L
                )
            }.id
        }
        if (nameMode) {
            val fact = normalizeQty(r.fact).ifEmpty { "0" }
            val defect = normalizeQty(r.defect).ifEmpty { "0" }
            if (defect.toDouble() > fact.toDouble()) return "Брак больше фактического количества: $name"
            val plan = if (r.extra) "0" else normalizeQty(r.qty).ifEmpty { "0" }
            rows.add(ReceiptItem(pid, name, fact, plan, defect, r.expiry.trim(), bc))
        } else {
            var qty = normalizeQty(r.qty)
            val printed = normalizeQty(r.printed)
            // количество 0 допустимо (принимается как ноль); недопустимо только пустое или не число
            fun numOk(q: String) = q.isNotEmpty() && (q.toDoubleOrNull() ?: -1.0) >= 0.0
            if (forceAccept && r.status != "missing" && !numOk(qty) && numOk(printed)) qty = printed
            // «не пришло» (✕): товар попадает в приём с количеством 0 и планом по документу
            if (r.status == "missing" && (qty.isEmpty() || qty.toDoubleOrNull() == 0.0)) {
                rows.add(ReceiptItem(pid, name, "0", printed.ifEmpty { "0" }, barcode = bc))
            } else {
                if (!numOk(qty)) return "Укажите количество у товара: $name"
                val plan = if (r.status == "changed" || r.status == "missing") printed else ""
                rows.add(ReceiptItem(pid, name, qty, plan, barcode = bc))
            }
        }
    }
    if (rows.isEmpty()) {
        return if (job.dupDecision.values.any { !it }) "Все товары отклонены — приём не сохранён" else "Нет товаров для сохранения"
    }

    val order = Ai.normalizeOrder(job.order)
    job.saving = true
    try {
        val hash = withContext(Dispatchers.IO) { job.docHash() }
        // Точные признаки — номер заказа, содержимое и имя файла: тот же заказ или документ второй раз не принимаем
        val d2 = try {
            Cloud.findDuplicate(db, if (overwrite) order else "", "", "", hash)
        } catch (e: Exception) {
            null
        }
        val replaced: Receipt? = if (overwrite && Session.isAdmin) d2?.receipt else null
        if (d2 != null && replaced == null) {
            if (d2.kind == "order" && d2.receipt.invoices.isEmpty()) {
                // заказ уже принят, но без накладной — документ привязываем к нему, товары не дублируем
                val st = job.stamp.trim().let { if (it.length == 16) it else "" }
                val err = InvoiceCheck.attach(ctx, db, d2.receipt, f, job.sender, order, st, hash)
                if (err != null) return err
                job.attachedNote = InvoiceCheck.attachNote(d2.receipt, "по номеру заказа", job.sender, order)
                job.saved = true
                return null
            }
            return if (d2.kind == "order") "Заказ $order уже принят ${fmtTime(d2.receipt.receivedAt)} — повторно не сохраняю."
            else "Этот документ уже принят (${d2.by}) ${fmtTime(d2.receipt.receivedAt)} — повторно не сохраняю."
        }
        val now = System.currentTimeMillis()
        val at = stampMillis(job.stamp.trim()) ?: now
        val sender = job.sender.trim().let { if (it.equals("none", true)) "" else it }
        val stamp = job.stamp.trim().let { if (it.length == 16) it else "" }
        withContext(Dispatchers.IO) {
            newProducts.values.forEach { Cloud.saveProduct(db, it) }
            // штрих-коды, привязанные сканером к товарам каталога; если код числился за другим товаром — снимаем его с того
            bind.distinctBy { it.first }.forEach { (pid, code) ->
                if (newProducts.values.any { it.id == pid }) return@forEach
                val p = db.getProduct(pid) ?: return@forEach
                if (p.barcode == code) return@forEach
                db.findByBarcode(code)?.takeIf { it.id != pid }?.let { Cloud.saveProduct(db, it.copy(barcode = "")) }
                Cloud.saveProduct(db, p.copy(barcode = code))
            }
            val inv = f.driveName.ifBlank { null } ?: Media.importFile(ctx, File(f.path), db, "IN")
            val r = Receipt(
                id = replaced?.id ?: UUID.randomUUID().toString(),
                sender = sender,
                orderNo = if (order == "none") "" else order,
                receivedAt = at,
                receiver = receiver,
                items = rows,
                photos = (job.items.flatMap { listOf(it.photo) + it.defectPhotos } + (replaced?.photos ?: emptyList())).filter { it.isNotBlank() }.distinct(),
                invoices = ((replaced?.invoices ?: emptyList()) + listOfNotNull(inv)).distinct(),
                updatedAt = now,
                fileName = f.name,
                stamp = stamp,
                docHash = hash
            )
            Cloud.saveReceipt(db, r)
            Media.uploadPending(ctx, db)
        }
        job.newProductCount = newProducts.size
        job.saved = true
        return null
    } finally {
        job.saving = false
    }
}

/**
 * Мини-окно: такой товар (то же название и количество) за этот день уже принимался.
 * По каждому товару нужно выбрать «Принять» или «Отказать»; отказанный в приём не попадает.
 */
@Composable
fun SameDayDialog(list: List<SameDay>, onConfirm: (Map<String, Boolean>) -> Unit, onDismiss: () -> Unit) {
    val choice = remember { mutableStateMapOf<String, Boolean>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Такой товар уже принят сегодня") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                list.forEach { d ->
                    val k = InvoiceCheck.norm(d.name)
                    Column {
                        Text(d.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                        val where = listOf(d.receipt.sender, d.receipt.orderNo, fmtTime(d.receipt.receivedAt))
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        Text("Количество ${d.qty} · уже принято: $where", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (choice[k] == true) Button(onClick = { choice[k] = true }) { Text("Принять") }
                            else OutlinedButton(onClick = { choice[k] = true }) { Text("Принять") }
                            if (choice[k] == false) Button(onClick = { choice[k] = false }) { Text("Отказать") }
                            else OutlinedButton(onClick = { choice[k] = false }) { Text("Отказать") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = list.all { choice[InvoiceCheck.norm(it.name)] != null },
                onClick = { onConfirm(choice.toMap()) }
            ) { Text("Продолжить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AutoRowView(row: ItemRow) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        row.parts?.let { p ->
            val line = listOf(p.manufacturer, p.name, listOf(p.size, p.unit).filter { it.isNotBlank() }.joinToString(" "), p.pack)
                .filter { it.isNotBlank() }.joinToString(" · ")
            if (line.isNotBlank()) Text("➜ $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(row.name, style = MaterialTheme.typography.bodyLarge)
        val bold = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        val doc = if (row.printed.isNotEmpty()) row.printed else "—"
        when (row.status) {
            "ok" -> Text("✓ ПРИШЛО · ${row.qty} · по документу $doc", style = bold, color = MaterialTheme.colorScheme.primary)
            "missing" -> Text("✕ НЕ ПРИШЛО · по документу $doc · в приём не попадёт", style = bold, color = MaterialTheme.colorScheme.error)
            "changed" -> Text(
                if (row.qty.isBlank()) "✎ количество перечёркнуто, число не вписано · по документу $doc"
                else "✎ ДРУГОЕ КОЛИЧЕСТВО · по документу $doc · принято ${row.qty}",
                style = bold, color = MaterialTheme.colorScheme.tertiary
            )
            else -> Text("без отметки · по документу $doc", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

/** Данные накладной в автоприёме — только текст: править нельзя, всё берётся с проверенной бумаги. */
@Composable
private fun DocInfo(job: AutoJob) {
    fun v(s: String) = if (s.equals("none", true) || s.isBlank()) "—" else s
    Text("Отправитель: ${v(job.sender)}", style = MaterialTheme.typography.bodyLarge)
    Text("Номер заказа: ${v(job.order)}", style = MaterialTheme.typography.bodyLarge)
    Text("Дата и время: ${v(job.stamp)}", style = MaterialTheme.typography.bodyLarge)
}

/** Мигающая строка: показывает, что идёт распознавание. */
@Composable
private fun BlinkText(text: String) {
    val tr = rememberInfiniteTransition(label = "blink")
    val a by tr.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alpha"
    )
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = a }.padding(top = 2.dp)
    )
}

/**
 * Верх экрана «Приём по имени»: 1-я строка — отправитель, 2-я — заказ слева и «Далее» справа
 * (при ошибке распознавания вместо «Далее» — «Повторить»), ниже — колесо с поиском.
 */
@Composable
private fun ColumnScope.NameModeMain(
    job: AutoJob,
    error: String,
    addTick: Int,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onAddPrefilled: (name: String, productId: String, barcode: String) -> Unit
) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    // сканер переживает сворачивание и пересоздание экрана: состояние хранится в rememberSaveable
    var scanFor by rememberSaveable { mutableStateOf<String?>(null) } // "search" — найти товар по коду, "bind" — привязать код к строке
    var bindIdx by rememberSaveable { mutableIntStateOf(-1) }
    var jumpTo by remember { mutableStateOf<ItemRow?>(null) }
    var jumpTick by remember { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }
    var searchTick by remember { mutableIntStateOf(0) }
    var conflict by remember { mutableStateOf<Triple<ItemRow, String, String>?>(null) } // строка, код, кому принадлежит сейчас

    // штрих-коды подтягиваются из каталога по названию: после распознавания, из файла, после возврата из фона
    LaunchedEffect(job.items.size, job.running) {
        if (!job.running) withContext(Dispatchers.IO) { job.fillBarcodesFromCatalog() }
    }

    fun setSearch(t: String) { searchText = t; searchTick++ }
    fun jump(r: ItemRow) { jumpTo = r; jumpTick++ }

    // Сканер у «+»: код → строка списка с этим кодом; нет — товар каталога, его название идёт в поиск и находится в списке;
    // нет в списке — окно «Добавить товар»; нет и в каталоге — окно «Новый товар» с этим кодом.
    fun findByCode(code: String) {
        scope.launch {
            val inList = job.items.firstOrNull { sameBarcode(it.barcode, code) }
            if (inList != null) {
                setSearch("")
                jump(inList)
                return@launch
            }
            val p = withContext(Dispatchers.IO) { db.findByBarcode(code) }
            if (p == null) {
                onAddPrefilled("", "", code)
                return@launch
            }
            setSearch(p.name)
            val row = job.items.firstOrNull { it.productId.isNotEmpty() && it.productId == p.id }
                ?: job.items.firstOrNull { it.name.trim().equals(p.name.trim(), true) }
            if (row != null) {
                if (row.barcode.isBlank()) row.barcode = code
                jump(row)
            } else {
                onAddPrefilled(p.name, p.id, code)
            }
        }
    }

    // Круглая кнопка: код привязывается к выбранной строке
    fun bindCode(row: ItemRow, code: String) {
        scope.launch {
            val owner = withContext(Dispatchers.IO) { db.findByBarcode(code) }
            val ownerIsRow = owner != null && (owner.id == row.productId || owner.name.trim().equals(row.name.trim(), true))
            val otherRow = job.items.firstOrNull { it !== row && sameBarcode(it.barcode, code) }
            if ((owner != null && !ownerIsRow) || otherRow != null) {
                conflict = Triple(row, code, otherRow?.name ?: owner?.name ?: "")
            } else {
                row.barcode = code
                Haptics.pulse(ctx, Haptics.TAP)
                Toast.makeText(ctx, "Штрих-код привязан: ${row.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val editable = !job.running && !job.saved && !job.saving
    var scanStatus by remember { mutableStateOf("") }

    // Круглая кнопка и внешний сканер: код → строка с этим штрих-кодом, количество +1 (как нажатие на строку)
    fun countByCode(raw: String) {
        val code = raw.trim()
        if (code.isEmpty()) return
        if (!editable) {
            scanStatus = "Сейчас считать нельзя"
            return
        }
        scope.launch {
            var row = job.items.firstOrNull { sameBarcode(it.barcode, code) }
            if (row == null) {
                val p = withContext(Dispatchers.IO) { db.findByBarcode(code) }
                if (p != null) {
                    row = job.items.firstOrNull { it.productId.isNotEmpty() && it.productId == p.id }
                        ?: job.items.firstOrNull { it.name.trim().equals(p.name.trim(), true) }
                    if (row != null && row.barcode.isBlank()) row.barcode = code
                }
            }
            if (row == null) {
                scanStatus = "Нет в списке: $code"
                return@launch
            }
            row.fact = fmtQ(parseQ(row.fact) + 1)
            Haptics.pulse(ctx, Haptics.TAP)
            CountAnnouncer.tap(ctx, row)
            scanStatus = "${row.name}: ${row.fact}"
            jump(row)
        }
    }
    val countRef = rememberUpdatedState<(String) -> Unit> { c -> countByCode(c) }
    val scanForRef = rememberUpdatedState(scanFor)
    DisposableEffect(Unit) {
        val h: (String) -> Unit = { c ->
            val m = scanForRef.value
            if (m == null || m == "count") countRef.value(c)
        }
        BtScan.handler = h
        onDispose { if (BtScan.handler === h) BtScan.handler = null }
    }
    var editDoc by remember { mutableStateOf(false) }
    var retryDlg by remember { mutableStateOf(false) }
    val senderText = job.sender.let { if (it.equals("none", true)) "" else it }.ifBlank { "—" }
    val orderText = job.order.let { if (it.equals("none", true)) "" else it }.ifBlank { "—" }

    // линия прогресса на самом верху окна: сколько процентов плана уже посчитано (половина — 50%, всё — 100%)
    val planRows = job.items.filter { !it.extra }
    val planned = planRows.sumOf { normalizeQty(it.qty).toDoubleOrNull() ?: 0.0 }
    val counted = planRows.sumOf { minOf(normalizeQty(it.fact).toDoubleOrNull() ?: 0.0, normalizeQty(it.qty).toDoubleOrNull() ?: 0.0) }
    val pct = if (planned > 0) Math.round(counted / planned * 100).toInt().coerceIn(0, 100) else 0
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = pct / 100f,
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text("$pct%", fontWeight = FontWeight.Bold)
    }
    // 1-я строка: отправитель слева, номер заказа справа (над «Далее»); 2-я строка: «Далее» справа. Тап по тексту открывает правку
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            senderText, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(enabled = editable) { editDoc = true }.padding(vertical = 4.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            orderText, style = MaterialTheme.typography.titleMedium, maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.clickable(enabled = editable) { editDoc = true }.padding(vertical = 4.dp)
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (job.failedState) {
            Button(onClick = { retryDlg = true }) { Text("Повторить") }
        } else {
            Button(onClick = onNext, enabled = editable && job.pages.isNotEmpty()) { Text("Далее") }
        }
    }

    if (job.running) {
        BlinkText(listOf(job.message, job.liveStatus).filter { it.isNotBlank() }.joinToString(" · "))
    }
    val err = if (job.failedState) job.errorText else ""
    if (err.isNotBlank()) {
        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3,
            modifier = Modifier.padding(top = 2.dp))
    }
    if (error.isNotBlank()) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp))
    }
    if (job.saved) {
        Text(job.attachedNote.ifBlank { "Приём сохранён" }, color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
    Spacer(Modifier.height(8.dp))
    CountWheelList(
        job.items, editable, Modifier.weight(1f), addTick, onAdd,
        if (job.running) "" else "Список пуст",
        nameMode = true,
        onScan = { scanFor = "search" },
        scanFab = true,
        onScanFab = { scanStatus = ""; scanFor = "count" },
        onScanBind = { r -> bindIdx = job.items.indexOf(r); scanFor = "bind" },
        jumpTo = jumpTo, jumpTick = jumpTick,
        searchText = searchText, searchTick = searchTick,
        idleKeyboard = true,
        idleBlock = scanFor != null || conflict != null || editDoc || retryDlg
    )

    scanFor?.let { mode ->
        Dialog(
            onDismissRequest = { scanFor = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        ) {
            Surface(Modifier.fillMaxSize()) {
                ScannerScreen(
                    prefs = remember { Prefs(ctx) },
                    continuous = mode == "count",
                    status = if (mode == "count") scanStatus else "",
                    onResult = { raw ->
                        val code = raw.trim()
                        if (mode == "count") {
                            // круглая кнопка: сканер остаётся открытым, каждый код = +1 к строке
                            countRef.value(code)
                        } else {
                            scanFor = null
                            if (code.isNotEmpty()) {
                                if (mode == "bind") job.items.getOrNull(bindIdx)?.let { bindCode(it, code) } else findByCode(code)
                            }
                        }
                    },
                    onClose = { scanFor = null }
                )
            }
        }
    }
    conflict?.let { (row, code, who) ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("Штрих-код уже привязан") },
            text = { Text("Код $code уже числится за «$who». Привязать его к «${row.name}»? У «$who» он будет снят при сохранении приёма.") },
            confirmButton = {
                Button(onClick = {
                    job.items.filter { it !== row && it.barcode == code }.forEach { it.barcode = "" }
                    row.barcode = code
                    conflict = null
                }) { Text("Привязать") }
            },
            dismissButton = { TextButton(onClick = { conflict = null }) { Text("Отмена") } }
        )
    }

    if (editDoc) EditDocDialog(job, onDismiss = { editDoc = false })
    if (retryDlg) {
        RetryKeyDialog(onDismiss = { retryDlg = false }, onRetry = { retryDlg = false; onRetry() })
    }
}

@Composable
private fun EditDocDialog(job: AutoJob, onDismiss: () -> Unit) {
    var sender by remember { mutableStateOf(job.sender.let { if (it.equals("none", true)) "" else it }) }
    var order by remember { mutableStateOf(job.order.let { if (it.equals("none", true)) "" else it }) }
    // если машина не вписана — подставляем из «Приём B-Ware» с тем же номером заказа
    val known = remember { job.vehicle() }
    var truck by remember { mutableStateOf(known.first) }
    var trailer by remember { mutableStateOf(known.second) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отправитель, заказ, машина") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sender, { sender = it }, label = { Text("Отправитель") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(order, { order = it }, label = { Text("Номер заказа") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                PlateField(truck, { truck = it.take(20) }, "Номер машины", Modifier.fillMaxWidth())
                PlateField(trailer, { trailer = it.take(20) }, "Номер прицепа", Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                job.sender = sender.trim().ifEmpty { "none" }
                job.order = order.trim().ifEmpty { "none" }
                job.truck = truck.trim()
                job.trailer = trailer.trim()
                onDismiss()
            }) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AddItemDialog(
    initialName: String = "",
    initialPid: String = "",
    barcode: String = "",
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    var photo by remember { mutableStateOf("") }
    var added by remember { mutableStateOf(false) }
    // закрыли окно без добавления — снятое фото не нужно
    DisposableEffect(Unit) { onDispose { if (!added && photo.isNotEmpty()) Media.discard(ctx, db, listOf(photo)) } }
    var name by remember { mutableStateOf(initialName) }
    var pid by remember { mutableStateOf(initialPid) }
    var sugg by remember { mutableStateOf(emptyList<Product>()) }

    LaunchedEffect(name) {
        sugg = if (name.trim().length < MIN_SEARCH_CHARS || pid.isNotEmpty()) emptyList()
        else withContext(Dispatchers.IO) { db.search(name, 6) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (barcode.isNotBlank() && initialPid.isEmpty()) "Новый товар" else "Добавить товар") },
        text = {
            Column {
                if (barcode.isNotBlank()) {
                    Text(
                        if (initialPid.isEmpty()) "Штрих-код $barcode не найден в каталоге. Назовите товар — он добавится в каталог и в список."
                        else "Штрих-код $barcode · товар есть в каталоге, но не в этом списке.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                OutlinedTextField(
                    name, { name = it; pid = "" }, label = { Text("Название товара") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Введите от $MIN_SEARCH_CHARS символов, чтобы найти товар в каталоге, или впишите новое название.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                sugg.forEach { p ->
                    Text(
                        p.name,
                        Modifier.fillMaxWidth().clickable {
                            name = p.name
                            pid = p.id
                            sugg = emptyList()
                        }.padding(vertical = 10.dp)
                    )
                    HorizontalDivider()
                }
                Spacer(Modifier.height(10.dp))
                Text("Фото товара (печатается на последнем листе документа)", style = MaterialTheme.typography.bodySmall)
                if (photo.isEmpty()) {
                    MediaButtons(kind = "RC", allowFile = false) { n ->
                        photo = n
                        Media.uploadPending(ctx, db)
                    }
                } else {
                    PhotoGrid(listOf(photo)) { n ->
                        Media.discard(ctx, db, listOf(n))
                        photo = ""
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { added = true; onAdd(name.trim(), pid, photo) }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

/** Открыта ли экранная клавиатура (в «Приёме по имени»): по ней нижнее меню прячется под клавиатуру и не поднимается над ней. */
object KeyboardState {
    var open by mutableStateOf(false)
}

/**
 * mode = "auto" — автоприём (сохранение сразу после распознавания);
 * mode = "name" — приём по имени: распознанное количество = план, факт и брак вводятся вручную,
 * кнопка «Далее» ведёт на итоговое окно (печать и сохранение).
 */
@Composable
fun AutoReceiveScreen(f: IncomingFile, receiver: String, mode: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val nameMode = mode == "name"
    val job = remember(f.path) { AutoReceiveHolder.create(ctx, f, nameMode) }

    // «Приём по имени»: при наборе в поиске окно уменьшается над клавиатурой, а не уезжает под неё
    if (nameMode) {
        val rootView = LocalView.current
        DisposableEffect(Unit) {
            val w = ctx.findFragmentActivity()?.window
            w?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            // клавиатура открыта, если видимая часть окна заметно ниже полной высоты
            val rect = android.graphics.Rect()
            val l = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                rootView.getWindowVisibleDisplayFrame(rect)
                val full = rootView.rootView.height
                KeyboardState.open = full > 0 && (full - rect.bottom) > full * 0.15f
            }
            rootView.viewTreeObserver.addOnGlobalLayoutListener(l)
            onDispose {
                rootView.viewTreeObserver.removeOnGlobalLayoutListener(l)
                KeyboardState.open = false
                w?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
            }
        }
    }

    // onClose — «свернуть»: партия остаётся в работе. Убрать партию совсем можно после сохранения или кнопкой ✕ в списке.
    val finish = { if (job.saved) AutoReceiveHolder.remove(job) else onClose() }
    if (nameMode && job.step == 1) {
        NameSummaryScreen(job, f, receiver, onBack = { job.step = 0 }, onClose = finish)
        return
    }

    BackHandler(onBack = onClose)
    val ready = AiCfg.ready(ctx) || job.isExcel
    var checked by remember(f.path) { mutableStateOf(false) }
    var dup by remember(f.path) { mutableStateOf<Dup?>(null) }
    var forced by remember(f.path) { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var addPrefill by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var retryDlg by remember { mutableStateOf(false) }
    var delJobDlg by remember { mutableStateOf(false) }
    var addTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(f.path, nameMode) { job.setMode(nameMode) }

    // До распознавания: не обрабатывался ли этот файл раньше (имя файла, затем дата и время)
    LaunchedEffect(f.path) {
        if (ready && !job.started) {
            val d = try {
                // сначала содержимое файла и имя (точные признаки), затем дата и время
                val h = withContext(Dispatchers.IO) { job.docHash() }
                Cloud.findDuplicate(db, "", f.name, job.stamp, h)
            } catch (e: Exception) {
                null
            }
            dup = d
            if (d == null) job.startOnce()
        }
        checked = true
    }

    val showMain = ready && checked && (dup == null || forced)
    Column(
        Modifier.fillMaxSize()
            .then(if (nameMode) Modifier else Modifier.verticalScroll(rememberScrollState()))
            .padding(if (nameMode && showMain) 10.dp else 16.dp)
    ) {
        if (!nameMode || !showMain) {
            Text(if (nameMode) "Приём по имени" else "Автоприём", style = MaterialTheme.typography.headlineSmall)
            Text(f.name, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }

        if (!ready) {
            Text(
                "Не задан ключ ИИ. Откройте Профиль → Настройки → Распознавание документов.",
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { AutoReceiveHolder.remove(job); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
            return@Column
        }
        if (!checked) {
            Text("Проверка: не обрабатывался ли файл раньше…")
            return@Column
        }
        val d = dup
        if (d != null && !forced) {
            Text(if (d.hard) "Этот документ уже принят" else "Похожий документ уже принят",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text("Совпало: ${d.by}. На распознавание не отправлен.")
            Spacer(Modifier.height(8.dp))
            Text("Приём: ${fmtTime(d.receipt.receivedAt)}")
            if (d.receipt.sender.isNotBlank()) Text("Отправитель: ${d.receipt.sender}")
            if (d.receipt.orderNo.isNotBlank()) Text("Номер заказа: ${d.receipt.orderNo}")
            Text("Товаров: ${d.receipt.items.size}")
            Spacer(Modifier.height(16.dp))
            if (!d.hard) {
                // совпали только дата и время — это могут быть разные документы, даём выбрать
                Button(onClick = { forced = true; job.startOnce() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Обработать всё равно")
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = { AutoReceiveHolder.remove(job); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
            return@Column
        }

        if (job.blocked.isNotBlank() && !job.running) {
            Text("Накладная не принята", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            Text(job.blocked)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { AutoReceiveHolder.remove(job); onClose() }, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
            return@Column
        }

        if (nameMode) {
            NameModeMain(
                job = job,
                error = error,
                addTick = addTick,
                onNext = {
                    val e = checkFacts(job)
                    if (e != null) error = e else {
                        error = ""
                        job.step = 1
                    }
                },
                onRetry = { job.start(true) },
                onAdd = { addPrefill = null; showAdd = true },
                onAddPrefilled = { n, pid, code -> addPrefill = Triple(n, pid, code); showAdd = true }
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (job.running) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(job.message, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        job.pages.forEachIndexed { i, p ->
            Text(
                "Лист ${i + 1} из ${job.pages.size} — ${p.status}",
                style = MaterialTheme.typography.bodySmall,
                color = if (p.status.startsWith("ошибка")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(16.dp))
        DocInfo(job)

        Spacer(Modifier.height(16.dp))
        Text("Товары: ${job.items.size}", style = MaterialTheme.typography.titleSmall)
        Text(
            "По отметкам ручкой: ✓ — пришло, ✕ — не пришло (сохранится с количеством 0), ✎ — другое количество, дописанное ручкой. " +
                "Количество здесь не меняется: оно берётся с проверенной накладной.",
            style = MaterialTheme.typography.bodySmall
        )
        job.items.toList().forEach { row ->
            key(row) { AutoRowView(row) }
        }

        Spacer(Modifier.height(16.dp))
        when {
            job.saved -> {
                Text(
                    if (job.attachedNote.isNotBlank()) job.attachedNote
                    else "Приём сохранён автоматически: история, Firebase и локальная база. Накладная отправляется на Google Диск." +
                        if (job.newProductCount > 0) " Новых товаров в каталоге: ${job.newProductCount}." else "",
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            }
            job.saving -> {
                Text("Сохранение…", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            job.autoState == "error" -> {
                Text(job.autoError, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { job.autoFinish() } }
                ) { Text("Повторить сохранение") }
                Spacer(Modifier.height(8.dp))
            }
            !job.running && job.pages.isNotEmpty() && !job.hasFailed && job.items.isEmpty() -> {
                Text("Товары на накладной не найдены.", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { job.openInNameMode() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Открыть в «Приём по имени»")
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (!job.running && !job.saved && job.hasFailed) {
            Button(onClick = { retryDlg = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Повторить нераспознанные листы")
            }
            Spacer(Modifier.height(8.dp))
        }
        // Любую несохранённую партию можно довести до конца прямо отсюда (в том числе партии, вернувшиеся из черновика после обновления)
        if (!job.running && !job.saving && !job.saved) {
            if (job.items.isNotEmpty() && !job.hasFailed) {
                if (job.autoState != "error") {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { scope.launch { job.autoFinish() } }
                    ) { Text("Сохранить") }
                    Spacer(Modifier.height(8.dp))
                }
                if (Session.isAdmin) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                val err = job.adminAccept()
                                Toast.makeText(ctx, err ?: "Принято", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) { Text("Сохранить как есть (по накладной)") }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { job.openInNameMode() }) {
                    Text("Открыть в «Приём по имени»")
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = { delJobDlg = true }) {
                Text("Удалить партию", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(4.dp))
        }
        OutlinedButton(onClick = finish, modifier = Modifier.fillMaxWidth()) {
            Text(if (job.saved) "Закрыть" else "Свернуть (партия останется в работе)")
        }
    }

    if (retryDlg) {
        RetryKeyDialog(onDismiss = { retryDlg = false }, onRetry = { retryDlg = false; job.start(true) })
    }
    if (delJobDlg) {
        AlertDialog(
            onDismissRequest = { delJobDlg = false },
            title = { Text("Удалить партию?") },
            text = { Text("Партия не сохранена: распознанные товары и введённые количества будут потеряны.") },
            confirmButton = {
                Button(onClick = { delJobDlg = false; AutoReceiveHolder.remove(job); onClose() }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { delJobDlg = false }) { Text("Отмена") } }
        )
    }

    if (!nameMode && job.autoState == "samedup") {
        SameDayDialog(
            list = job.sameDay,
            onConfirm = { m ->
                m.forEach { (k, v) -> job.dupDecision[k] = v }
                job.autoState = ""
                scope.launch { job.autoFinish() }
            },
            onDismiss = onClose
        )
    }

    if (!nameMode && job.autoState == "unmarked") {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Накладная без отметок") },
            text = {
                Text(
                    "На этой накладной отмечены не все позиции (есть и отмеченные, и без галочки/крестика), " +
                        "поэтому автоприём её не сохранил. Откройте её в «Приём по имени» и посчитайте вручную" +
                        if (Session.isAdmin) " или сохраните как есть: количество возьмётся с накладной, без пересчёта." else "."
                )
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(modifier = Modifier.fillMaxWidth(), onClick = { job.openInNameMode() }) { Text("Открыть в «Приём по имени»") }
                    if (Session.isAdmin) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { scope.launch { job.adminAccept() } }
                        ) { Text("Сохранить как есть (по накладной)") }
                    }
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = onClose) { Text("Закрыть") }
                }
            }
        )
    }

    if (showAdd) {
        val pf = addPrefill
        AddItemDialog(
            initialName = pf?.first ?: "",
            initialPid = pf?.second ?: "",
            barcode = pf?.third ?: "",
            onDismiss = { showAdd = false; addPrefill = null },
            onAdd = { name, pid, photo ->
                job.items.add(ItemRow(name, "0", extra = true, productId = pid).also { it.barcode = pf?.third ?: ""; it.photo = photo })
                addTick++
                showAdd = false
                addPrefill = null
            }
        )
    }
}
