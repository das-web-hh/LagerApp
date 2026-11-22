package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Товар, у которого записано, кто и когда его завёл (поля createdBy / createdAt / source / batchId в Firebase). */
data class CreatedProduct(
    val id: String,
    val name: String,
    val article: String,
    val barcode: String,
    val createdBy: String,
    val createdAt: Long,
    val source: String,
    val batchId: String,
    val deleted: Boolean
)

/** Одна загрузка: один человек и один источник. Импорт и приёмы группируются по batchId, остальное — по дню. */
class CatBatch(val key: String, val who: String, val source: String, val items: List<CreatedProduct>) {
    val from: Long get() = items.minOf { it.createdAt }
    val to: Long get() = items.maxOf { it.createdAt }
    val live: List<CreatedProduct> get() = items.filter { !it.deleted }
}

object CatalogControl {
    /** Много товаров за раз — повод проверить глазами. */
    const val SUSPECT = 50

    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.ROOT)

    fun sourceLabel(s: String): String = when (s) {
        "import" -> "Импорт каталога"
        "receive" -> "Автоприём накладной"
        "receipt" -> "Приёмка"
        "inventory" -> "Инвентаризация"
        "card" -> "Карточка товара"
        else -> "Другое"
    }

    fun shortWho(email: String): String = email.substringBefore('@').ifBlank { "—" }

    /** Товары, заведённые за последние days дней (новые сверху). Читает Firebase напрямую. */
    suspend fun load(days: Int): List<CreatedProduct> {
        val since = System.currentTimeMillis() - days * 24L * 3600_000L
        val docs = FirebaseFirestore.getInstance().collection("products")
            .whereGreaterThanOrEqualTo("createdAt", since)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(3000)
            .get().awaitIt().documents
        return docs.mapNotNull { d ->
            val name = d.getString("name") ?: return@mapNotNull null
            CreatedProduct(
                d.id, name, d.get("article")?.toString() ?: "", d.get("barcode")?.toString() ?: "",
                d.getString("createdBy") ?: "", d.getLong("createdAt") ?: 0L,
                d.getString("source") ?: "other", d.getString("batchId") ?: "",
                d.getBoolean("deleted") == true
            )
        }
    }

    fun group(list: List<CreatedProduct>): List<CatBatch> {
        val m = LinkedHashMap<String, MutableList<CreatedProduct>>()
        for (p in list) {
            val k = p.createdBy + "|" + p.source + "|" +
                p.batchId.ifBlank { "d" + dayFmt.format(Date(p.createdAt)) }
            m.getOrPut(k) { ArrayList() }.add(p)
        }
        return m.map { (k, v) -> CatBatch(k, v[0].createdBy, v[0].source, v) }
            .sortedByDescending { it.to }
    }

    /** Товары, которые уже есть в приёмках: их массовое удаление не трогает (приёмка осталась бы без товара). */
    fun usedInReceipts(db: ProductDb): Set<String> {
        val s = HashSet<String>()
        db.allReceipts().forEach { r -> r.items.forEach { if (it.productId.isNotEmpty()) s.add(it.productId) } }
        return s
    }
}

@Composable
fun CatalogControlScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT) }
    BackHandler(onBack = onBack)

    var days by remember { mutableIntStateOf(7) }
    var person by remember { mutableStateOf("") }          // "" — все
    var all by remember { mutableStateOf<List<CreatedProduct>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<String?>(null) }
    var askBatch by remember { mutableStateOf<CatBatch?>(null) }
    var askOne by remember { mutableStateOf<CreatedProduct?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(days, reload) {
        loading = true
        msg = ""
        try {
            all = CatalogControl.load(days)
        } catch (e: Exception) {
            all = emptyList()
            msg = "Не удалось загрузить: " + (e.message ?: e.javaClass.simpleName)
        }
        loading = false
    }

    val people = all.groupBy { it.createdBy }.map { it.key to it.value.count { p -> !p.deleted } }
        .sortedByDescending { it.second }
    val shown = if (person.isEmpty()) all else all.filter { it.createdBy == person }
    val batches = CatalogControl.group(shown)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Контроль каталога", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Кто и сколько товаров завёл в базу. Партию, в которой много лишнего, можно удалить целиком или по одному. " +
                "Товары без записи об авторе (заведённые до этой версии) здесь не видны.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Сутки", 7 to "7 дней", 30 to "30 дней").forEach { (d, l) ->
                FilterChip(selected = days == d, onClick = { days = d; opened = null }, label = { Text(l) })
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Кто заводил (товаров за период):", style = MaterialTheme.typography.labelMedium)
        Column {
            FilterChip(
                selected = person.isEmpty(), onClick = { person = "" },
                label = { Text("Все · ${all.count { !it.deleted }}") }
            )
            people.forEach { (who, n) ->
                FilterChip(
                    selected = person == who, onClick = { person = who; opened = null },
                    label = { Text((if (who.isBlank()) "без автора" else CatalogControl.shortWho(who)) + " · $n") },
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        if (msg.isNotBlank()) Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (loading) Text("Загрузка…")
        else if (batches.isEmpty()) Text("За этот период новых товаров нет.")

        batches.forEach { b ->
            val live = b.live.size
            val big = live >= CatalogControl.SUSPECT
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().clickable { opened = if (opened == b.key) null else b.key },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            CatalogControl.shortWho(b.who) + " · " + CatalogControl.sourceLabel(b.source),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            fmt.format(Date(b.to)) + " · товаров: $live" +
                                (if (b.items.size != live) " (удалено ${b.items.size - live})" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (big) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        if (big) Text("Много за раз — проверьте", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Text(if (opened == b.key) "˄" else "˅", style = MaterialTheme.typography.titleLarge)
                }
                if (opened == b.key) {
                    b.live.take(200).forEach { p ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                                Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                val sub = listOf(p.article, p.barcode).filter { it.isNotBlank() }.joinToString(" · ")
                                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { askOne = p }) { Text("Удалить") }
                        }
                    }
                    if (b.live.size > 200) Text("…и ещё ${b.live.size - 200}. Удаление партии уберёт все.",
                        style = MaterialTheme.typography.bodySmall)
                    if (live > 0) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { askBatch = b }, modifier = Modifier.fillMaxWidth()) {
                            Text("Удалить всю партию ($live)")
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }

    askBatch?.let { b ->
        AlertDialog(
            onDismissRequest = { askBatch = null },
            title = { Text("Удалить партию?") },
            text = {
                Text(
                    CatalogControl.shortWho(b.who) + " · " + CatalogControl.sourceLabel(b.source) + "\n" +
                        "Товаров: ${b.live.size}. Те, что уже есть в приёмках, останутся — приёмка без товара ломается."
                )
            },
            confirmButton = {
                Button(onClick = {
                    askBatch = null
                    scope.launch {
                        msg = "Удаление…"
                        msg = try {
                            val res = withContext(Dispatchers.IO) {
                                val db = ProductDb.get(ctx)
                                val used = CatalogControl.usedInReceipts(db)
                                val ids = b.live.map { it.id }.filter { it !in used }
                                Cloud.deleteProducts(
                                    db, ids,
                                    "партия: " + CatalogControl.shortWho(b.who) + ", " + CatalogControl.sourceLabel(b.source)
                                )
                                ids.size to (b.live.size - ids.size)
                            }
                            reload++
                            "Удалено: ${res.first}" + if (res.second > 0) ", оставлено (есть в приёмках): ${res.second}" else ""
                        } catch (e: Exception) {
                            "Ошибка: " + (e.message ?: e.javaClass.simpleName)
                        }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { askBatch = null }) { Text("Отмена") } }
        )
    }

    askOne?.let { p ->
        AlertDialog(
            onDismissRequest = { askOne = null },
            title = { Text("Удалить товар?") },
            text = { Text(p.name) },
            confirmButton = {
                Button(onClick = {
                    askOne = null
                    scope.launch {
                        msg = try {
                            val kept = withContext(Dispatchers.IO) {
                                val db = ProductDb.get(ctx)
                                if (p.id in CatalogControl.usedInReceipts(db)) true
                                else { Cloud.deleteProducts(db, listOf(p.id), "по одному"); false }
                            }
                            reload++
                            if (kept) "Не удалён: товар есть в приёмках" else "Удалён"
                        } catch (e: Exception) {
                            "Ошибка: " + (e.message ?: e.javaClass.simpleName)
                        }
                    }
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { askOne = null }) { Text("Отмена") } }
        )
    }
}
