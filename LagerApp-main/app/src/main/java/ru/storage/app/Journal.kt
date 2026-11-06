package ru.storage.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.atomic.AtomicBoolean

/** Одна запись журнала: cat — раздел (Приём, Каталог, Отправка, Печать, Документы, Система). */
class JournalEntry(val id: String, val t: Long, val cat: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", t).put("c", cat).put("x", text)

    companion object {
        fun fromJson(o: JSONObject) = JournalEntry(o.optString("id"), o.optLong("t"), o.optString("c"), o.optString("x"))
    }
}

/**
 * Журнал операций аккаунта.
 * На телефоне: filesDir/journal/jr_<логин>.jsonl (все записи) и jr_<логин>.pending (ещё не отправленные).
 * На Google Диске: пачки JR-<логин>-<дата-время>-<код>.json (каждая пачка загружается один раз и не меняется),
 * поэтому скрипт Диска менять не нужно. С другого телефона / после переустановки журнал собирается из этих пачек.
 */
object Journal {
    var version by mutableIntStateOf(0)
    var status by mutableStateOf("")

    val CATEGORIES = listOf("Приём", "Каталог", "Отправка", "Печать", "Документы", "Система")

    @Volatile private var app: Context? = null
    private val lock = Any()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploading = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** Логин в виде, безопасном для имени файла на Диске. */
    fun loginKey(login: String = Session.login): String =
        login.lowercase().ifBlank { "admin" }.replace(Regex("[^a-z0-9а-яё._-]"), "_")

    private fun dir(c: Context): File = File(c.filesDir, "journal").also { it.mkdirs() }
    private fun allFile(c: Context, key: String) = File(dir(c), "jr_$key.jsonl")
    private fun pendFile(c: Context, key: String) = File(dir(c), "jr_$key.pending")
    private fun sp(c: Context) = c.getSharedPreferences("journal", Context.MODE_PRIVATE)

    private fun readLines(f: File): List<String> =
        if (f.exists()) f.readLines(Charsets.UTF_8).filter { it.isNotBlank() } else emptyList()

    /** Записать операцию текущего аккаунта. Можно вызывать из любого потока, не блокирует. */
    fun log(cat: String, text: String) {
        val c = app ?: return
        val key = loginKey()
        val e = JournalEntry(
            UUID.randomUUID().toString().replace("-", "").take(16),
            System.currentTimeMillis(), cat, text.trim().take(500)
        )
        bg.launch {
            val line = e.toJson().toString() + "\n"
            synchronized(lock) {
                try {
                    allFile(c, key).appendText(line, Charsets.UTF_8)
                    pendFile(c, key).appendText(line, Charsets.UTF_8)
                } catch (ex: Exception) {
                }
            }
            version++
            scheduleFlush(c, key)
        }
    }

    private fun scheduleFlush(c: Context, key: String) {
        if (!scheduled.compareAndSet(false, true)) return
        bg.launch {
            delay(10_000L)
            scheduled.set(false)
            flush(c, key)
        }
    }

    fun entries(c: Context, key: String = loginKey()): List<JournalEntry> {
        val lines = synchronized(lock) { readLines(allFile(c, key)) }
        val out = ArrayList<JournalEntry>(lines.size)
        for (l in lines) {
            try {
                out.add(JournalEntry.fromJson(JSONObject(l)))
            } catch (ex: Exception) {
            }
        }
        out.sortByDescending { it.t }
        return out
    }

    private fun markSeen(c: Context, key: String, name: String) {
        val s = HashSet<String>(sp(c).getStringSet("seen_$key", emptySet()) ?: emptySet())
        s.add(name)
        sp(c).edit().putStringSet("seen_$key", s).apply()
    }

    /** Отправить на Диск записи, которые там ещё не лежат (блокирующая, вызывать не из главного потока). */
    fun flush(c: Context, key: String = loginKey()) {
        if (!DriveCfg.ready(c)) {
            status = "Google Диск не настроен: журнал пока хранится только на телефоне"
            return
        }
        if (!uploading.compareAndSet(false, true)) return
        try {
            val lines = synchronized(lock) { readLines(pendFile(c, key)) }
            if (lines.isEmpty()) {
                status = ""
                return
            }
            val arr = JSONArray()
            val ids = HashSet<String>()
            for (l in lines) {
                try {
                    val o = JSONObject(l)
                    arr.put(o)
                    ids.add(o.optString("id"))
                } catch (ex: Exception) {
                }
            }
            val name = "JR-$key-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) +
                "-" + UUID.randomUUID().toString().take(4) + ".json"
            val tmp = File(c.cacheDir, name)
            tmp.writeText(JSONObject().put("login", key).put("entries", arr).toString(), Charsets.UTF_8)
            try {
                DriveApi.upload(c, name, tmp)
            } finally {
                tmp.delete()
            }
            synchronized(lock) {
                val f = pendFile(c, key)
                val rest = readLines(f).filter { l ->
                    try {
                        JSONObject(l).optString("id") !in ids
                    } catch (ex: Exception) {
                        false
                    }
                }
                f.writeText(if (rest.isEmpty()) "" else rest.joinToString("\n") + "\n", Charsets.UTF_8)
            }
            markSeen(c, key, name)
            status = ""
        } catch (ex: Exception) {
            status = "Журнал: не удалось отправить на Диск (" + (ex.message ?: ex.javaClass.simpleName) + ")"
        } finally {
            uploading.set(false)
        }
    }

    /** Подтянуть с Диска пачки, которых на этом телефоне ещё нет. Возвращает число добавленных записей. */
    fun restore(c: Context, key: String = loginKey()): Int {
        if (!DriveCfg.ready(c)) return 0
        val prefix = "JR-$key-"
        val names = DriveApi.searchNames(c, prefix).filter { it.startsWith(prefix) && it.endsWith(".json") }
        val seen = HashSet<String>(sp(c).getStringSet("seen_$key", emptySet()) ?: emptySet())
        val known = HashSet<String>()
        synchronized(lock) {
            for (l in readLines(allFile(c, key))) {
                try {
                    known.add(JSONObject(l).optString("id"))
                } catch (ex: Exception) {
                }
            }
        }
        var added = 0
        for (n in names) {
            if (n in seen) continue
            val bytes = DriveApi.download(c, n) ?: continue
            val arr = try {
                JSONObject(String(bytes, Charsets.UTF_8)).optJSONArray("entries")
            } catch (ex: Exception) {
                null
            }
            val sb = StringBuilder()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isEmpty() || !known.add(id)) continue
                    sb.append(o.toString()).append('\n')
                    added++
                }
            }
            if (sb.isNotEmpty()) {
                synchronized(lock) { allFile(c, key).appendText(sb.toString(), Charsets.UTF_8) }
            }
            seen.add(n)
        }
        sp(c).edit().putStringSet("seen_$key", seen).putBoolean("first_$key", true).apply()
        if (added > 0) version++
        return added
    }

    /** Отправка накопленного + (при первом входе на этом телефоне или по кнопке) загрузка с Диска. */
    fun sync(c: Context, forceRestore: Boolean) {
        val key = loginKey()
        try {
            flush(c, key)
            if (DriveCfg.ready(c) && (forceRestore || !sp(c).getBoolean("first_$key", false))) {
                status = "Журнал: загрузка с Диска…"
                val n = restore(c, key)
                status = if (n > 0) "Журнал: загружено записей $n" else ""
            }
        } catch (ex: Exception) {
            status = "Журнал: ошибка Диска (" + (ex.message ?: ex.javaClass.simpleName) + ")"
        }
    }
}

private val JR_LOCALE = Locale("ru")
private fun jrDayLabel(ms: Long): String = SimpleDateFormat("d MMMM yyyy, EEEE", JR_LOCALE).format(Date(ms))
private fun jrDayKey(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))
private fun jrTime(ms: Long): String = SimpleDateFormat("HH:mm", JR_LOCALE).format(Date(ms))

private fun jrColor(cat: String): Color = when (cat) {
    "Приём" -> Color(0xFF2E7D32)
    "Каталог" -> Color(0xFF016AFD)
    "Отправка" -> Color(0xFFB57F00)
    "Печать" -> Color(0xFF8E24AA)
    "Документы" -> Color(0xFF00838F)
    else -> Color(0xFF607D8B)
}

@Composable
private fun JrChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(shape).clickable(onClick = onClick)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** Окно «Журнал»: все операции аккаунта, новые сверху, по дням; фильтр по разделам. */
@Composable
fun JournalScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(emptyList<JournalEntry>()) }
    var filter by rememberSaveable { mutableStateOf("Все") }
    var busy by remember { mutableStateOf(false) }
    val login = Session.login.ifBlank { "admin" }

    LaunchedEffect(Journal.version) {
        list = withContext(Dispatchers.IO) { Journal.entries(ctx) }
    }
    LaunchedEffect(Unit) {
        busy = true
        withContext(Dispatchers.IO) { Journal.sync(ctx, false) }
        busy = false
        list = withContext(Dispatchers.IO) { Journal.entries(ctx) }
    }

    val shown = remember(list, filter) { if (filter == "Все") list else list.filter { it.cat == filter } }
    val rows = remember(shown) {
        val out = ArrayList<Any>()
        var last = ""
        for (e in shown) {
            val k = jrDayKey(e.t)
            if (k != last) {
                out.add(jrDayLabel(e.t))
                last = k
            }
            out.add(e)
        }
        out
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Журнал", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        withContext(Dispatchers.IO) { Journal.sync(ctx, true) }
                        busy = false
                        list = withContext(Dispatchers.IO) { Journal.entries(ctx) }
                    }
                },
                enabled = !busy
            ) { Text("Обновить") }
        }
        Text(
            "Аккаунт: $login · записей: ${list.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val st = if (busy && Journal.status.isBlank()) "Синхронизация с Диском…" else Journal.status
        if (st.isNotBlank()) {
            Text(
                st,
                style = MaterialTheme.typography.bodySmall,
                color = if (st.contains("не удалось") || st.contains("ошибка")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JrChip("Все", filter == "Все") { filter = "Все" }
            Journal.CATEGORIES.forEach { cat -> JrChip(cat, filter == cat) { filter = cat } }
        }
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (busy) "Загрузка…" else "Операций пока нет",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows) { r ->
                    if (r is String) {
                        Text(
                            r,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                        )
                    } else if (r is JournalEntry) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                jrTime(r.t),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(48.dp)
                            )
                            Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(jrColor(r.cat)))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.text, style = MaterialTheme.typography.bodyLarge)
                                Text(r.cat, style = MaterialTheme.typography.labelSmall, color = jrColor(r.cat))
                            }
                        }
                    }
                }
            }
        }
    }
}
