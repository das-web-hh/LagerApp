package ru.storage.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/*
 * Firestore: коллекция "products", документ = один товар:
 *   name (string), article (string), barcode (string), updatedAt (number, миллисекунды)
 */

data class Product(
    val id: String,
    val name: String,
    val article: String,
    val barcode: String,
    val updatedAt: Long
)

class ProductDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "catalog.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE products (" +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL, article TEXT NOT NULL, " +
                "barcode TEXT NOT NULL, name_lc TEXT NOT NULL, search_lc TEXT NOT NULL, " +
                "updated_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_products_name ON products(name_lc)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM products", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun maxUpdated(): Long = readableDatabase.rawQuery("SELECT MAX(updated_at) FROM products", null).use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
    }

    fun upsert(list: List<Product>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in list) {
                val v = ContentValues().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("article", p.article)
                    put("barcode", p.barcode)
                    put("name_lc", p.name.lowercase())
                    put("search_lc", "${p.name} ${p.article} ${p.barcode}".lowercase())
                    put("updated_at", p.updatedAt)
                }
                db.insertWithOnConflict("products", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun read(sql: String, args: Array<String>): List<Product> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Product>()
            while (c.moveToNext()) {
                out.add(Product(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
            }
            out
        }

    private val cols = "id, name, article, barcode, updated_at"

    fun all(): List<Product> = read("SELECT $cols FROM products ORDER BY name_lc", emptyArray())

    /** Поиск по названию, артикулу и штрих-коду; каждое слово запроса должно встретиться. */
    fun search(query: String, limit: Int): List<Product> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        fun esc(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val where = words.joinToString(" AND ") { "search_lc LIKE ? ESCAPE '\\'" }
        val args = ArrayList<String>()
        words.forEach { args.add("%" + esc(it) + "%") }
        args.add(esc(words.first()) + "%")
        return read(
            "SELECT $cols FROM products WHERE $where " +
                "ORDER BY (name_lc LIKE ? ESCAPE '\\') DESC, name_lc LIMIT $limit",
            args.toTypedArray()
        )
    }

    companion object {
        @Volatile private var instance: ProductDb? = null
        fun get(context: Context): ProductDb = instance ?: synchronized(this) {
            instance ?: ProductDb(context.applicationContext).also { instance = it }
        }
    }
}

class SyncState {
    var text by mutableStateOf("")
    var busy by mutableStateOf(false)
    var version by mutableIntStateOf(0)
}

object CatalogSync {
    private const val PAGE = 10
    private const val FULL_PAGE = 500
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L
    @Volatile private var lastSync = 0L

    private suspend fun <T> Task<T>.result(): T = suspendCoroutine { c ->
        addOnSuccessListener { c.resume(it) }
        addOnFailureListener { c.resumeWithException(it) }
    }

    private fun DocumentSnapshot.toProduct(): Product? {
        val name = getString("name") ?: return null
        return Product(
            id = id,
            name = name,
            article = get("article")?.toString() ?: "",
            barcode = get("barcode")?.toString() ?: "",
            updatedAt = getLong("updatedAt") ?: 0L
        )
    }

    suspend fun run(db: ProductDb, st: SyncState, force: Boolean) {
        if (st.busy) return
        val empty = withContext(Dispatchers.IO) { db.count() } == 0
        if (!force && !empty && System.currentTimeMillis() - lastSync < MIN_INTERVAL_MS) return
        st.busy = true
        try {
            val col = FirebaseFirestore.getInstance().collection("products")
            val added = if (empty) fullSync(db, col, st) else incrementalSync(db, col)
            lastSync = System.currentTimeMillis()
            val total = withContext(Dispatchers.IO) { db.count() }
            st.text = if (empty) "Загружено товаров: $total"
            else if (added > 0) "Обновлено: $added, всего $total" else "Каталог актуален ($total)"
            st.version++
        } catch (e: FirebaseFirestoreException) {
            st.text = if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                "Нет доступа к Firebase (проверьте правила Firestore)"
            else "Нет связи с Firebase: ${e.code}"
        } catch (e: Exception) {
            st.text = "Ошибка синхронизации: ${e.message}"
        } finally {
            st.busy = false
        }
    }

    /** Первый запуск: вся коллекция страницами по 500. */
    private suspend fun fullSync(db: ProductDb, col: com.google.firebase.firestore.CollectionReference, st: SyncState): Int {
        var total = 0
        var last: DocumentSnapshot? = null
        while (true) {
            var q: Query = col.orderBy(FieldPath.documentId()).limit(FULL_PAGE.toLong())
            if (last != null) q = q.startAfter(last)
            val docs = q.get().result().documents
            if (docs.isEmpty()) break
            val items = docs.mapNotNull { it.toProduct() }
            withContext(Dispatchers.IO) { db.upsert(items) }
            total += items.size
            st.text = "Загрузка каталога… $total"
            last = docs.last()
            if (docs.size < FULL_PAGE) break
        }
        return total
    }

    /** Дальше: последние 10 по updatedAt; если среди них есть новые — ещё 10, и так далее. */
    private suspend fun incrementalSync(db: ProductDb, col: com.google.firebase.firestore.CollectionReference): Int {
        val localMax = withContext(Dispatchers.IO) { db.maxUpdated() }
        var total = 0
        var last: DocumentSnapshot? = null
        while (true) {
            var q: Query = col.orderBy("updatedAt", Query.Direction.DESCENDING).limit(PAGE.toLong())
            if (last != null) q = q.startAfter(last)
            val docs = q.get().result().documents
            val fresh = docs.mapNotNull { it.toProduct() }.filter { it.updatedAt > localMax }
            withContext(Dispatchers.IO) { db.upsert(fresh) }
            total += fresh.size
            if (fresh.size < docs.size || docs.size < PAGE) break
            last = docs.last()
        }
        return total
    }
}

@Composable
private fun rememberSync(db: ProductDb): SyncState {
    val st = remember { SyncState() }
    LaunchedEffect(Unit) { CatalogSync.run(db, st, false) }
    return st
}

@Composable
fun CatalogScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val db = remember { ProductDb.get(context) }
    val sync = rememberSync(db)
    var items by remember { mutableStateOf(emptyList<Product>()) }
    LaunchedEffect(sync.version) { items = withContext(Dispatchers.IO) { db.all() } }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
        }
        Text("Каталог товаров", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (sync.busy && sync.text.isBlank()) "Проверка…" else sync.text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            val scope = rememberCoroutineScope()
            OutlinedButton(
                onClick = { scope.launch { CatalogSync.run(db, sync, true) } },
                enabled = !sync.busy
            ) { Text("Обновить") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { p ->
                Text(p.name, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun ManualReceiveScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { ProductDb.get(context) }
    val sync = rememberSync(db)
    var query by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<Product>()) }
    val received = remember { mutableStateListOf<Product>() }

    LaunchedEffect(query, sync.version) {
        suggestions = if (query.isBlank()) emptyList()
        else withContext(Dispatchers.IO) { db.search(query, 8) }
    }

    if (scanning) {
        ScannerScreen(
            prefs = prefs,
            onResult = { code ->
                query = code
                scanning = false
            },
            onClose = { scanning = false }
        )
        return
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Назад") }
        Text("Приём вручную", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Артикул, название или штрих-код") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { scanning = true }) { Text("📷 Скан") }
        }
        if (sync.text.isNotBlank() && (sync.busy || suggestions.isEmpty())) {
            Text(sync.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        if (suggestions.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column {
                    suggestions.forEachIndexed { i, p ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    if (received.none { it.id == p.id }) received.add(0, p)
                                    query = ""
                                }
                                .padding(12.dp)
                        ) {
                            Text(p.name, fontWeight = FontWeight.Medium)
                            val sub = listOf(
                                if (p.article.isNotBlank()) "арт. ${p.article}" else "",
                                if (p.barcode.isNotBlank()) "ШК ${p.barcode}" else ""
                            ).filter { it.isNotEmpty() }.joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                        }
                        if (i < suggestions.lastIndex) HorizontalDivider()
                    }
                }
            }
        } else if (query.isNotBlank() && !sync.busy) {
            Text("Ничего не найдено", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(16.dp))
        if (received.isNotEmpty()) {
            Text("Принято: ${received.size}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(received, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { received.remove(p) }) { Text("✕") }
                }
                HorizontalDivider()
            }
        }
    }
}
