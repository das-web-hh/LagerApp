package ru.storage.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Шаблон накладных одного поставщика: короткие правила, как читать именно его накладные. status: "new" — ждёт проверки, "confirmed" — используется. */
data class InvoiceProfile(
    val key: String,
    val sender: String,
    val rules: String,
    val status: String,
    val updatedAt: Long
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

    val newCount: Int get() = list.count { it.status == "new" }

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
                InvoiceProfile(o.optString("key"), o.optString("sender"), o.optString("rules"), o.optString("status", "new"), o.optLong("updatedAt"))
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
                .put("status", it.status).put("updatedAt", it.updatedAt))
        }
        sp(ctx).edit().putString("list", arr.toString()).apply()
    }

    private fun map(p: InvoiceProfile) = hashMapOf<String, Any>(
        "sender" to p.sender, "rules" to p.rules, "status" to p.status, "updatedAt" to p.updatedAt
    )

    fun find(ctx: Context, sender: String): InvoiceProfile? {
        load(ctx)
        val k = key(sender)
        return if (k.isEmpty()) null else list.firstOrNull { it.key == k }
    }

    /** Правила для ИИ — только подтверждённые. */
    fun confirmedRules(ctx: Context, sender: String): String? =
        find(ctx, sender)?.takeIf { it.status == "confirmed" && it.rules.isNotBlank() }?.rules

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
                it.getString("status") ?: "new", it.getLong("updatedAt") ?: 0L)
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
            "Шаблон создаётся сам, когда приходит накладная от нового поставщика. Проверьте его и подтвердите — после этого ИИ читает накладные этого поставщика точнее.",
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
                    if (p.status == "new") Text("Новый — проверьте и подтвердите", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
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
    var rules by remember(p.key) { mutableStateOf(p.rules) }
    var confirmDelete by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(p.sender, style = MaterialTheme.typography.headlineSmall)
        Text(
            if (p.status == "new") "Новый шаблон: пока не используется. Исправьте правила и подтвердите."
            else "Подтверждён: добавляется в инструкцию ИИ для накладных этого поставщика.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(rules, { rules = it }, label = { Text("Правила чтения накладной") }, minLines = 8, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = rules.isNotBlank(),
            onClick = { InvoiceProfiles.save(ctx, p.copy(rules = rules.trim(), status = "confirmed")); onBack() },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (p.status == "new") "Подтвердить" else "Сохранить") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить") }
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
