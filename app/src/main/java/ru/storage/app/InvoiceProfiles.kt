package ru.storage.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Шаблон накладных одного поставщика: короткие правила, как читать именно его накладные. status: "new" — ждёт проверки, "confirmed" — используется. */
data class InvoiceProfile(
    val key: String,
    val sender: String,
    val rules: String,
    val status: String,
    val updatedAt: Long,
    /** Имя файла-образца накладной (фото первого листа, из которого сделан шаблон); "" — образца нет. */
    val sample: String = ""
)

/**
 * Реестр шаблонов накладных. Хранится на телефоне и в Firebase (коллекция invoice_profiles),
 * поэтому новый или исправленный шаблон сразу работает на всех телефонах без новой сборки.
 */
object InvoiceProfiles {
    private val fs get() = FirebaseFirestore.getInstance()
    val list = mutableStateListOf<InvoiceProfile>()
    private var loaded = false
    @Volatile private var lastRefresh = 0L

    /** Сколько шаблонов ждут проверки: считается только у администратора — подтверждает их он. */
    val newCount: Int get() = if (Session.isAdmin) list.count { it.status == "new" } else 0

    private val LEGAL = Regex("\\b(gmbh|mbh|ag|kg|ug|ohg|eg|se|ek|co|ltd|inc|llc|haftungsbeschränkt)\\b")

    fun key(sender: String): String =
        sender.lowercase().replace(LEGAL, " ").replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("invoice_profiles", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val arr = JSONArray(sp(ctx).getString("list", "[]"))
            val l = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                InvoiceProfile(o.optString("key"), o.optString("sender"), o.optString("rules"), o.optString("status", "new"), o.optLong("updatedAt"), o.optString("sample"))
            }.filter { it.key.isNotEmpty() }
            list.clear()
            list.addAll(l)
        } catch (e: Exception) {
        }
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("key", it.key).put("sender", it.sender).put("rules", it.rules)
                .put("status", it.status).put("updatedAt", it.updatedAt).put("sample", it.sample))
        }
        sp(ctx).edit().putString("list", arr.toString()).apply()
    }

    private fun map(p: InvoiceProfile) = hashMapOf<String, Any>(
        "sender" to p.sender, "rules" to p.rules, "status" to p.status, "updatedAt" to p.updatedAt, "sample" to p.sample
    )

    fun find(ctx: Context, sender: String): InvoiceProfile? {
        load(ctx)
        val k = key(sender)
        return if (k.isEmpty()) null else list.firstOrNull { it.key == k }
    }

    /** Правила для ИИ — только подтверждённые. */
    fun confirmedRules(ctx: Context, sender: String): String? =
        find(ctx, sender)?.takeIf { it.status == "confirmed" && it.rules.isNotBlank() }?.rules

    /** Сохранить фото первого листа накладной как образец шаблона (уходит на Google Диск, как остальные накладные). Возвращает имя файла или "". */
    fun saveSample(ctx: Context, png: ByteArray): String {
        return try {
            val app = ctx.applicationContext
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return ""
            val name = "IN-" + java.util.UUID.randomUUID().toString().replace("-", "").take(14) + ".jpg"
            Media.file(app, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            val db = ProductDb.get(app)
            db.addUpload(name)
            Media.uploadPending(app, db)
            name
        } catch (e: Exception) {
            ""
        }
    }

    /** Образец накладной: свой, а если его нет (старый шаблон) — картинка накладной из последней приёмки этого поставщика. */
    fun sampleFor(ctx: Context, p: InvoiceProfile): String? {
        if (p.sample.isNotBlank()) return p.sample
        return try {
            ProductDb.get(ctx).allReceipts()
                .filter { key(it.sender) == p.key }
                .flatMap { it.invoices }
                .firstOrNull { Media.isImage(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: Context, p: InvoiceProfile) {
        load(ctx)
        val np = p.copy(updatedAt = System.currentTimeMillis())
        val i = list.indexOfFirst { it.key == np.key }
        if (i >= 0) list[i] = np else list.add(np)
        persist(ctx)
        Journal.log("Приём", (if (i >= 0) "Изменён шаблон накладной: " else "Добавлен шаблон накладной: ") + np.key)
        try {
            fs.collection("invoice_profiles").document(np.key).set(map(np))
        } catch (e: Exception) {
        }
    }

    fun delete(ctx: Context, key: String) {
        load(ctx)
        list.removeAll { it.key == key }
        persist(ctx)
        Journal.log("Приём", "Удалён шаблон накладной: $key")
        try {
            fs.collection("invoice_profiles").document(key).delete()
        } catch (e: Exception) {
        }
    }

    /** Подтянуть шаблоны из Firebase (не чаще раза в 5 минут). Более новая версия побеждает. */
    suspend fun refresh(ctx: Context, force: Boolean = false) {
        load(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 5 * 60 * 1000L) return
        val snap = fs.collection("invoice_profiles").get().awaitIt()
        lastRefresh = now
        val cloud = snap.documents.map {
            InvoiceProfile(it.id, it.getString("sender") ?: "", it.getString("rules") ?: "",
                it.getString("status") ?: "new", it.getLong("updatedAt") ?: 0L, it.getString("sample") ?: "")
        }
        for (c in cloud) {
            val i = list.indexOfFirst { it.key == c.key }
            if (i < 0) list.add(c) else if (c.updatedAt > list[i].updatedAt) list[i] = c
        }
        // созданные без сети — отправляем
        list.filter { l -> cloud.none { it.key == l.key } }.forEach {
            try { fs.collection("invoice_profiles").document(it.key).set(map(it)) } catch (e: Exception) { }
        }
        persist(ctx)
    }

    private fun prompt(sender: String, extracted: String) = """
        You analyse the layout of delivery notes (Lieferschein) of ONE supplier: "$sender". You get the first page image and what an automatic reader extracted from it (product | quantity).
        Write layout rules for this supplier, so that the reader extracts the data correctly next time. Write in Russian, at most 8 short lines, each line is one concrete rule starting with "- ".
        Cover only what is specific for this layout: in which column the delivered quantity is; what the weight / size column means (per one unit or total); where the product size is written (in the name, on a second line, in a separate column); how pallets and multipacks are written; which lines are NOT goods; where the pen marks (ticks, crosses) stand; where the order number is.
        Do not invent anything that is not visible. No introduction, no markdown, nothing except the lines.
        Extracted:
        $extracted
    """.trimIndent()

    private fun refinePrompt(sender: String, rules: String, comment: String) = """
        You maintain layout rules for delivery notes of ONE supplier: "$sender". The rules are used by an automatic reader, so that it extracts product names and quantities correctly.
        The warehouse manager says the current rules give a wrong result. Rewrite the rules so that the manager's remark is fully taken into account. Look at the attached image of the delivery note and check the new rules against it.
        Keep what is still correct, fix what is wrong, add a short concrete example with real numbers from the image when the remark is about quantities. Write in Russian, at most 8 short lines, each line is one concrete rule starting with "- ". No introduction, no markdown, nothing except the lines.
        Current rules:
        $rules
        Manager's remark:
        $comment
    """.trimIndent()

    /** Переписать правила по замечанию человека (с картинкой накладной, если она есть). Вызывать не в главном потоке. */
    fun refine(ctx: Context, sender: String, rules: String, comment: String, png: ByteArray?): String =
        Ai.call(ctx, refinePrompt(sender, rules, comment), png, false).trim().take(1500)

    /** Фото образца накладной, уменьшенное, для отправки ИИ. */
    suspend fun samplePng(ctx: Context, p: InvoiceProfile): ByteArray? {
        val n = sampleFor(ctx, p) ?: return null
        val f = Media.load(ctx, n) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, b)
                var sc = 1
                while (b.outWidth / sc > 2000) sc *= 2
                val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sc })
                if (bmp == null) null
                else java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Один запрос к ИИ (с картинкой первого листа) — текст правил. Вызывать не в главном потоке. */
    fun generate(ctx: Context, sender: String, png: ByteArray, extracted: String): String =
        Ai.call(ctx, prompt(sender, extracted), png, false).trim().take(1500)
}

@Composable
fun InvoiceProfilesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        InvoiceProfiles.load(ctx)
        try {
            withContext(Dispatchers.IO) { InvoiceProfiles.refresh(ctx, true) }
        } catch (e: Exception) {
            note = "Нет связи с Firebase — показаны шаблоны с телефона"
        }
    }

    val cur = open?.let { k -> InvoiceProfiles.list.firstOrNull { it.key == k } }
    if (cur != null) {
        ProfileEditor(cur, onBack = { open = null })
        return
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Шаблоны накладных", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (Session.isAdmin) "Шаблон создаётся сам, когда приходит накладная от нового поставщика. Проверьте его и подтвердите — после этого ИИ читает накладные этого поставщика точнее."
            else "Шаблоны накладных поставщиков. Сотрудники только смотрят: проверять и подтверждать шаблоны может администратор.",
            style = MaterialTheme.typography.bodySmall
        )
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        val items = InvoiceProfiles.list.sortedWith(compareBy({ it.status != "new" }, { it.sender.lowercase() }))
        if (items.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Шаблонов пока нет.")
        }
        items.forEach { p ->
            Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { open = p.key }) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.sender, fontWeight = FontWeight.Medium)
                    if (p.status == "new") Text(if (Session.isAdmin) "Новый — проверьте и подтвердите" else "Новый — ждёт проверки администратора", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    else Text("Подтверждён", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    Text(p.rules.lineSequence().firstOrNull { it.isNotBlank() } ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun ProfileEditor(p: InvoiceProfile, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val canEdit = Session.isAdmin
    var rules by remember(p.key) { mutableStateOf(p.rules) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var comment by remember(p.key) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(p.sender, style = MaterialTheme.typography.headlineSmall)
        Text(
            when {
                !canEdit -> "Только просмотр. Проверять и подтверждать шаблоны может администратор."
                p.status == "new" -> "Новый шаблон: пока не используется. Сравните правила с накладной внизу, исправьте и подтвердите."
                else -> "Подтверждён: добавляется в инструкцию ИИ для накладных этого поставщика."
            },
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            rules, { rules = it }, readOnly = !canEdit,
            label = { Text("Правила чтения накладной") }, minLines = 8, modifier = Modifier.fillMaxWidth()
        )
        if (canEdit) {
            Text("Текст выше можно править вручную. Или напишите (можно надиктовать), что неправильно, — ИИ перепишет правила сам.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                comment, { comment = it },
                label = { Text("Что неправильно?") },
                placeholder = { Text("Например: количество — это штуки, а не паллеты: 9pl × 28pcs = 252") },
                minLines = 2, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = !busy && comment.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true; msg = ""
                        try {
                            val t = withContext(Dispatchers.IO) {
                                val png = InvoiceProfiles.samplePng(ctx, p)
                                AutoReceiveHolder.aiLock.withLock { InvoiceProfiles.refine(ctx, p.sender, rules, comment.trim(), png) }
                            }
                            if (t.isNotBlank()) {
                                rules = t; comment = ""; testResult = ""
                                msg = "Правила обновлены. Нажмите «Проверить на этой накладной», затем подтвердите."
                            } else msg = "ИИ вернул пустой ответ"
                        } catch (e: Exception) {
                            msg = "Ошибка ИИ: " + (e.message ?: "")
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Исправить правила с ИИ") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = !busy && rules.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true; msg = ""; testResult = ""
                        try {
                            val res = withContext(Dispatchers.IO) {
                                val png = InvoiceProfiles.samplePng(ctx, p) ?: throw RuntimeException("нет фото накладной")
                                AutoReceiveHolder.aiLock.withLock { Ai.recognize(ctx, png, 1, 1, rules.trim()) }
                            }
                            testResult = if (res.items.isEmpty()) "Товаров не найдено."
                            else res.items.joinToString("\n") { "• ${it.name} — ${it.qty}" }
                        } catch (e: Exception) {
                            msg = "Ошибка проверки: " + (e.message ?: "")
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Проверить на этой накладной") }
            if (busy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            if (testResult.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Так ИИ прочитает эту накладную с текущими правилами (название — количество):", style = MaterialTheme.typography.bodySmall)
                Text(testResult)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (canEdit) {
            Button(
                enabled = rules.isNotBlank(),
                onClick = { InvoiceProfiles.save(ctx, p.copy(rules = rules.trim(), status = "confirmed")); onBack() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (p.status == "new") "Подтвердить" else "Сохранить") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить") }
            Spacer(Modifier.height(16.dp))
        }

        // фото накладной: по нему видно, что и как написано, и можно проверить правила
        Text("Накладная этого поставщика", style = MaterialTheme.typography.titleMedium)
        val sampleName by produceState<String?>(null, p.key, p.sample) {
            value = withContext(Dispatchers.IO) { InvoiceProfiles.sampleFor(ctx, p) }
        }
        val bmp by produceState<Bitmap?>(null, sampleName) {
            val n = sampleName
            value = if (n == null) null else withContext(Dispatchers.IO) {
                Media.load(ctx, n)?.let { f ->
                    try {
                        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(f.path, b)
                        var sc = 1
                        while (b.outWidth / sc > 1800) sc *= 2
                        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sc })
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val img = bmp
        if (img != null) {
            Image(
                img.asImageBitmap(), contentDescription = "Накладная",
                modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth
            )
        } else {
            Text(
                if (sampleName == null) "Фото накладной для этого шаблона не сохранено. У новых шаблонов оно появляется само."
                else "Загрузка фото накладной…",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить шаблон?") },
            text = { Text(p.sender) },
            confirmButton = { Button(onClick = { InvoiceProfiles.delete(ctx, p.key); confirmDelete = false; onBack() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}
