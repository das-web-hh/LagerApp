package ru.storage.app

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * «Сборка»: копия «Инвентаризации», но база — список из присланной партии (план по каждой позиции).
 * Сканируем артикул / штрих-код / название, считаем количество (с бумаги или экрана); печать, PDF и отправка как в инвентаризации.
 */
object AsmStore {
    val jobs = mutableStateListOf<InvJob>()
    var openId by mutableStateOf<String?>(null)
    /** true — пришла партия по ссылке из другого приложения: главный экран откроет «Прочее → Сборка» */
    var pendingOpen by mutableStateOf(false)
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "assembly_jobs.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val j = InvJob(o.optString("id"), o.optString("addr"), o.optLong("at"))
                j.step = o.optInt("step", 0)
                val rs = o.optJSONArray("rows")
                var planNo = 0
                if (rs != null) for (k in 0 until rs.length()) {
                    val r = rs.optJSONObject(k) ?: continue
                    // строка из списка партии (extra = false) хранит план в количестве; найденное сверх списка — extra
                    val row = ItemRow(r.optString("n"), r.optString("q"), extra = r.optBoolean("x", true), productId = r.optString("pid"))
                    row.fact = r.optString("f")
                    row.defect = r.optString("d")
                    row.expiry = r.optString("e")
                    row.barcode = r.optString("b")
                    row.photo = r.optString("ph")
                    row.article = r.optString("a")
                    row.ts = r.optLong("ts", row.ts)
                    row.tc = r.optLong("tc")
                    row.tu = r.optLong("tu")
                    row.tf = r.optString("tf")
                    // sid: у старых сохранений его нет — строка плана получает «r<номер>» (как в HTML), остальные уже имеют UUID
                    val no = if (!row.extra) planNo++ else -1
                    row.sid = r.optString("sid").ifBlank { if (no >= 0) "r$no" else row.sid }
                    row.author = r.optString("by")
                    row.fromOthers = r.optBoolean("fx", false)
                    row.seenT = r.optLong("st", 0L)
                    row.orphan = r.optBoolean("orph", false)
                    r.optJSONArray("al")?.let { a -> row.alias = (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } }
                    j.rows.add(row)
                }
                jobs.add(j)
            }
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun save(ctx: Context) {
        try {
            val a = JSONArray()
            jobs.toList().forEach { j ->
                val rs = JSONArray()
                j.rows.toList().forEach { r ->
                    rs.put(
                        JSONObject().put("n", r.name).put("q", r.qty).put("x", r.extra).put("f", r.fact).put("d", r.defect)
                            .put("e", r.expiry).put("b", r.barcode).put("pid", r.productId).put("ph", r.photo).put("a", r.article)
                            .put("sid", r.sid).put("by", r.author).put("fx", r.fromOthers).put("st", r.seenT).put("orph", r.orphan)
                            .put("al", JSONArray(r.alias))
                            .put("ts", r.ts).put("tc", r.tc).put("tu", r.tu).put("tf", r.tf)
                    )
                }
                a.put(JSONObject().put("id", j.id).put("addr", j.address).put("at", j.created).put("step", j.step).put("rows", rs))
            }
            file(ctx).writeText(a.toString())
        } catch (e: Exception) {
        }
    }

    fun remove(ctx: Context, j: InvJob) {
        jobs.remove(j)
        if (openId == j.id) openId = null
        save(ctx)
    }

    // ----- офлайн-база: полученные партии лежат на телефоне, пока их не удалят -----
    class AsmBase(val code: String, val title: String, val at: Long, val json: JSONObject)

    val bases = mutableStateListOf<AsmBase>()
    private var basesLoaded = false
    private fun baseFile(ctx: Context) = File(ctx.applicationContext.filesDir, "assembly_bases.json")

    @Synchronized
    fun loadBases(ctx: Context) {
        if (basesLoaded) return
        basesLoaded = true
        try {
            val f = baseFile(ctx)
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                bases.add(AsmBase(o.optString("code"), o.optString("title"), o.optLong("at"), o))
            }
        } catch (e: Exception) {
        }
    }

    @Synchronized
    private fun saveBases(ctx: Context) {
        try {
            val a = JSONArray()
            bases.toList().forEach { a.put(it.json) }
            baseFile(ctx).writeText(a.toString())
        } catch (e: Exception) {
        }
    }

    fun removeBase(ctx: Context, b: AsmBase) {
        bases.remove(b)
        saveBases(ctx)
    }

    internal fun codeOf(j: InvJob): String? =
        if (j.id.startsWith("asm_")) j.id.removePrefix("asm_").substringBefore('_') else null

    /** Новая сборка с нуля: строки базы с планом, факт пустой. */
    private fun fresh(ctx: Context, base: AsmBase): InvJob {
        val j = InvJob("asm_${base.code}_" + UUID.randomUUID().toString().take(8), base.title, System.currentTimeMillis())
        val rs = base.json.optJSONArray("rows")
        if (rs != null) for (k in 0 until rs.length()) {
            val r = rs.optJSONObject(k) ?: continue
            val row = ItemRow(r.optString("n"), r.optString("q"), extra = false)
            row.sid = "r$k" // тот же sid, что у строки плана в HTML-странице: счёт складывается между устройствами
            row.barcode = r.optString("b")
            row.article = r.optString("a")
            j.rows.add(row)
        }
        jobs.add(0, j)
        save(ctx)
        return j
    }

    /** Начатая сборка по этой базе (продолжаем) или новая с нуля. */
    fun startFromBase(ctx: Context, base: AsmBase): InvJob {
        load(ctx)
        return jobs.firstOrNull { codeOf(it) == base.code } ?: fresh(ctx, base)
    }

    /** Пустая сборка без базы — как «Добавить» в «Инвентаризации». */
    fun addFree(ctx: Context): InvJob {
        load(ctx)
        val j = InvJob("free_" + UUID.randomUUID().toString(), "", System.currentTimeMillis())
        jobs.add(0, j)
        save(ctx)
        return j
    }

    /** Следующая партия: та же база, счёт с нуля (для сборки без базы — пустая). */
    fun nextFor(ctx: Context, job: InvJob): InvJob {
        val base = codeOf(job)?.let { c -> bases.firstOrNull { it.code == c } }
        return if (base != null) fresh(ctx, base) else addFree(ctx)
    }

    /** Партия получена (с Диска или из файла HTML): база сохраняется офлайн и открывается сборка. Главный поток. */
    fun receiveJson(ctx: Context, o: JSONObject) {
        load(ctx); loadBases(ctx)
        val code = o.optString("code")
        if (code.isBlank()) return
        val base = AsmBase(code, o.optString("title"), o.optLong("at"), o)
        val i = bases.indexOfFirst { it.code == code }
        if (i >= 0) bases[i] = base else bases.add(0, base)
        saveBases(ctx)
        val j = startFromBase(ctx, base)
        openId = j.id
        pendingOpen = true
        Toast.makeText(ctx.applicationContext, "Партия загружена: ${base.title}", Toast.LENGTH_SHORT).show()
    }

    /** Получить партию по ссылке/коду из текста. Безопасно вызывать из любого потока. */
    fun receive(ctx: Context, text: String, onError: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        val code = BatchShare.codeFrom(text)
        if (code == null) {
            onError("Это не ссылка на партию")
            return
        }
        kotlin.concurrent.thread {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            try {
                val o = BatchShare.fetch(app, code)
                main.post { receiveJson(app, o) }
            } catch (e: Exception) {
                main.post { onError(e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}

private fun asmPlan(j: InvJob) = j.rows.sumOf { if (it.extra) 0.0 else parseQ(it.qty) }

@Composable
fun AssemblyScreen(prefs: Prefs, receiver: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { AsmStore.load(ctx); AsmStore.loadBases(ctx) }
    val open = AsmStore.jobs.firstOrNull { it.id == AsmStore.openId }
    if (open != null) {
        AssemblyEditor(open, receiver) { AsmStore.openId = null }
    } else {
        AssemblyHub(prefs, onBack)
    }
}

@Composable
private fun AssemblyHub(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var scanning by remember { mutableStateOf(false) }
    var linkDlg by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var delJob by remember { mutableStateOf<InvJob?>(null) }

    fun get(text: String) {
        busy = true
        AsmStore.receive(ctx, text) { err ->
            busy = false
            Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(AsmStore.openId) { if (AsmStore.openId != null) busy = false }

    if (scanning) {
        ScannerScreen(
            prefs = prefs, onlyQr = true,
            onResult = { scanning = false; get(it) },
            onClose = { scanning = false }
        )
        return
    }
    BackHandler(onBack = onBack)
    var delBase by remember { mutableStateOf<AsmStore.AsmBase?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text("Сборка", style = MaterialTheme.typography.titleLarge)
        }
        // как в «Инвентаризации»: сверху «Добавить» — новая сборка, считаем всё подряд
        Button(
            onClick = { AsmStore.openId = AsmStore.addFree(ctx).id },
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text("Добавить", style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f).height(48.dp), onClick = { scanning = true }) {
                Text(if (busy) "Загружаю…" else "Получить (QR)")
            }
            OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f).height(48.dp), onClick = {
                link = clip.getText()?.text.orEmpty()
                linkDlg = true
            }) { Text("По ссылке") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            if (AsmStore.jobs.isNotEmpty()) {
                item { Text("В работе", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            items(AsmStore.jobs.toList(), key = { it.id }) { j ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { AsmStore.openId = j.id },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(j.address.ifBlank { "Без названия" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val plan = asmPlan(j)
                            Text(
                                fmtTime(j.created) + " · позиций: ${j.rows.size} · собрано ${fmtQ(invTotal(j))}" + if (plan > 0.0) " из ${fmtQ(plan)}" else "",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { delJob = j }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Text(
                    "База партий (хранится на телефоне, работает без интернета)",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                if (AsmStore.bases.isEmpty()) {
                    Text(
                        "Пока пусто. Отсканируйте QR партии, откройте ссылку из WhatsApp или откройте присланный HTML-файл через Warehouse.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(AsmStore.bases.toList(), key = { "b" + it.code }) { b ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { AsmStore.openId = AsmStore.startFromBase(ctx, b).id },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.title.ifBlank { "Партия" }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                fmtTime(b.at) + " · позиций: ${b.json.optJSONArray("rows")?.length() ?: 0}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { delBase = b }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить из базы", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
    delBase?.let { b ->
        AlertDialog(
            onDismissRequest = { delBase = null },
            title = { Text("Удалить партию из базы?") },
            text = { Text("«${b.title}» пропадёт с телефона. Можно получить её заново по QR или ссылке.") },
            confirmButton = { Button(onClick = { AsmStore.removeBase(ctx, b); delBase = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { delBase = null }) { Text("Отмена") } }
        )
    }
    if (linkDlg) {
        AlertDialog(
            onDismissRequest = { linkDlg = false },
            title = { Text("Ссылка на партию") },
            text = { CompactField(value = link, onChange = { link = it }, hint = "Вставьте ссылку", modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { linkDlg = false; get(link) }, enabled = link.isNotBlank()) { Text("Получить") } },
            dismissButton = { TextButton(onClick = { linkDlg = false }) { Text("Отмена") } }
        )
    }
    delJob?.let { j ->
        AlertDialog(
            onDismissRequest = { delJob = null },
            title = { Text("Удалить сборку?") },
            text = { Text("Она не сохранена: всё посчитанное будет потеряно.") },
            confirmButton = { Button(onClick = { AsmStore.remove(ctx, j); delJob = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { delJob = null }) { Text("Отмена") } }
        )
    }
}

/** Копия редактора «Инвентаризации»: список партии — база, счёт — факт; сверху «Сохранить / Печать / Отправить». */
@Composable
private fun AssemblyEditor(job: InvJob, receiver: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { if (job.step == 1) job.step = 0 else { AsmStore.save(ctx); onClose() } }
    LaunchedEffect(job) { while (true) { delay(2000); AsmStore.save(ctx) } }
    // обычная партия: свои позиции и счёт уходят на сервер, чужие приходят (опрос раз в 120 с, отправка не чаще 20 с); пустая партия — ничего
    LaunchedEffect(job) { AsmSync.run(ctx, job, receiver) }
    // личный журнал для администратора (кто, что, сколько, когда); задание без кода партии идёт в журнал инвентаризации приложения
    LaunchedEffect(job) { while (true) { delay(5000); InvLog.push(ctx, job, receiver, AsmStore.codeOf(job) ?: InvLog.APP_CODE) } }
    DisposableEffect(job) {
        onDispose {
            InvLog.push(ctx, job, receiver, AsmStore.codeOf(job) ?: InvLog.APP_CODE, force = true)
            AsmSync.pushFinal(ctx, job, receiver)
            AsmStore.save(ctx)
        }
    }

    if (job.step == 1) {
        val stamp = remember { System.currentTimeMillis() }
        val photoNames = job.rows.map { it.photo }.filter { it.isNotBlank() }
        val photosHtml by produceState("", photoNames) { value = bwPhotosHtml(ctx, photoNames) }
        // артикул и штрих-код — только из списка партии, общий каталог не читается
        val articles = job.rows.map { ArticleLookup.Info(it.article.trim(), it.barcode.trim(), "") }
        val html = invDocHtml(job, receiver, stamp, photosHtml, "", articles, asm = true)
        val fileBase = "Сборка_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date(stamp))
        var busy by remember { mutableStateOf(false) }
        var sendDlg by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { job.step = 0 }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
                Text(job.address.ifBlank { "Сборка" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // сверху: «Напечатать и дальше» — печать документа и переход к следующей партии с нуля
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    busy = true
                    val app = ctx.applicationContext
                    scope.launch {
                        try {
                            Printer.print(ctx, fileBase, html) // копия попадает в «Документы», печать — в «Журнал»
                            Media.uploadPending(app, ProductDb.get(app))
                            if (DriveCfg.ready(app)) withContext(Dispatchers.IO) {
                                try {
                                    val pdf = HtmlPdf.render(app, html)
                                    if (pdf != null) {
                                        val name = "$fileBase.pdf"
                                        try { DriveApi.delete(app, name) } catch (e: Exception) { }
                                        DriveApi.uploadBytes(app, name, "application/pdf", pdf)
                                    }
                                } catch (e: Exception) { }
                            }
                            val next = AsmStore.nextFor(app, job)
                            AsmStore.remove(app, job)
                            AsmStore.openId = next.id
                            Toast.makeText(app, "Напечатано. Следующая партия — с нуля", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(app, "Не удалось: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text(if (busy) "…" else "Напечатать документ и дальше") }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp),
                    onClick = {
                        busy = true
                        val app = ctx.applicationContext
                        scope.launch {
                            try {
                                DocStore.recordPrinted(ctx, fileBase, html)
                                Media.uploadPending(app, ProductDb.get(app))
                                if (DriveCfg.ready(app)) {
                                    val pdf = withContext(Dispatchers.IO) { HtmlPdf.render(app, html) }
                                    if (pdf != null) withContext(Dispatchers.IO) {
                                        val name = "$fileBase.pdf"
                                        try { DriveApi.delete(app, name) } catch (e: Exception) { }
                                        DriveApi.uploadBytes(app, name, "application/pdf", pdf)
                                    }
                                    Toast.makeText(app, "Сборка сохранена: «Документы» и Google Диск", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(app, "Сохранено в «Документы» (Google Диск не настроен)", Toast.LENGTH_LONG).show()
                                }
                                AsmStore.remove(app, job)
                                onClose()
                            } catch (e: Exception) {
                                Toast.makeText(app, "Не удалось сохранить: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "…" else "Завершить") }
                OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), onClick = { Printer.print(ctx, fileBase, html) }) { Text("Печать") }
                OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), onClick = { sendDlg = true }) { Text("Отправить") }
            }
            Spacer(Modifier.height(8.dp))
            HtmlPreview(html, Modifier.weight(1f).fillMaxWidth())
        }
        if (sendDlg) {
            AlertDialog(
                onDismissRequest = { sendDlg = false },
                title = { Text("Отправить") },
                text = { Text("WhatsApp, Telegram, почта: таблицей (Excel) или обычным текстом.") },
                confirmButton = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(modifier = Modifier.fillMaxWidth(), onClick = {
                            sendDlg = false
                            try {
                                val dir = File(ctx.cacheDir, "export").also { it.mkdirs() }
                                val f = File(dir, "$fileBase.csv")
                                f.writeText(invCsv(job, stamp, articles), Charsets.UTF_8)
                                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(i, "Отправить таблицу"))
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Не удалось отправить: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                            }
                        }) { Text("Таблицей (Excel)") }
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            sendDlg = false
                            val i = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, invText(job, stamp, "Сборка"))
                            }
                            ctx.startActivity(Intent.createChooser(i, "Отправить текстом"))
                        }) { Text("Текстом") }
                        TextButton(modifier = Modifier.fillMaxWidth(), onClick = { sendDlg = false }) { Text("Отмена") }
                    }
                }
            )
        }
        return
    }

    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { AsmStore.save(ctx); onClose() }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
            Text("Сборка", style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactField(
                value = job.address, onChange = { job.address = germanKeys(it) },
                hint = "Название / заказ", modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    error = ""
                    AsmSync.pushFinal(ctx, job, receiver)
                    InvLog.push(ctx, job, receiver, AsmStore.codeOf(job) ?: InvLog.APP_CODE, force = true)
                    job.step = 1
                },
                enabled = job.rows.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) { Text("Далее") }
        }
        Spacer(Modifier.height(6.dp))
        CatalogWheel(
            rows = job.rows,
            groups = emptyList(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            errorText = error,
            emptyText = if (job.id.startsWith("asm_")) "Отсканируйте штрих-код или найдите товар по названию, артикулу" else "Отсканируйте штрих-код (появится окно с названием) или введите название и «+»",
            onAdded = { error = "" },
            scanner = true,
            inventory = true,
            listBase = true,
            isolated = true,
            // сборка по партии — только позиции списка; «Добавить» без партии — свободный счёт, товар вводится вручную
            strict = job.id.startsWith("asm_")
        )
    }
}
