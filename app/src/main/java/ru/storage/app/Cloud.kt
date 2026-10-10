package ru.storage.app

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

suspend fun <T> Task<T>.awaitIt(): T = suspendCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
}

class SyncState {
    var text by mutableStateOf("")
    var busy by mutableStateOf(false)
    var version by mutableIntStateOf(0)
}

/** Найденная ранее приёмка: by — по какому признаку совпало. */
class Dup(val by: String, val receipt: Receipt)

/*
 * Firestore:
 *  products/{id}: name, article, barcode, weight, unit, pack, manufacturer, photos[], updatedAt, deleted
 *  receipts/{id}: sender, orderNo, receivedAt, receiver, items[{productId,name,qty}], photos[], invoices[], updatedAt, deleted
 * Файлы фото/накладных: Firebase Storage, путь media/<имя файла>
 */
object Cloud {
    private val fs get() = FirebaseFirestore.getInstance()
    private const val PAGE = 10L
    private const val FULL_PAGE = 500L
    private const val PRODUCTS_INTERVAL = 10 * 60 * 1000L
    private const val RECEIPTS_INTERVAL = 2 * 60 * 1000L
    @Volatile private var lastProducts = 0L
    @Volatile private var lastReceipts = 0L

    // ---------- запись (локально сразу, в Firestore — в очередь, работает и без сети) ----------

    private fun productMap(p: Product): HashMap<String, Any> = hashMapOf(
        "name" to p.name,
        "article" to p.article,
        "barcode" to p.barcode,
        "weight" to p.weight,
        "unit" to p.unit,
        "pack" to p.pack,
        "manufacturer" to p.manufacturer,
        "photos" to p.photos,
        "updatedAt" to p.updatedAt,
        "deleted" to false
    )

    fun saveProduct(db: ProductDb, p: Product): Product {
        val np = p.copy(updatedAt = System.currentTimeMillis())
        db.upsertProducts(listOf(np))
        fs.collection("products").document(np.id).set(productMap(np))
        return np
    }

    fun pushProducts(list: List<Product>) {
        list.chunked(400).forEach { chunk ->
            val b = fs.batch()
            chunk.forEach { b.set(fs.collection("products").document(it.id), productMap(it)) }
            b.commit()
        }
    }

    fun deleteProduct(db: ProductDb, id: String) {
        db.deleteProduct(id)
        fs.collection("products").document(id).set(
            hashMapOf<String, Any>("deleted" to true, "updatedAt" to System.currentTimeMillis()),
            SetOptions.merge()
        )
    }

    private fun receiptMap(r: Receipt): HashMap<String, Any> = hashMapOf(
        "sender" to r.sender,
        "orderNo" to r.orderNo,
        "receivedAt" to r.receivedAt,
        "receiver" to r.receiver,
        "items" to r.items.map {
            hashMapOf("productId" to it.productId, "name" to it.name, "qty" to it.qty)
        },
        "photos" to r.photos,
        "invoices" to r.invoices,
        "fileName" to r.fileName,
        "stamp" to r.stamp,
        "updatedAt" to r.updatedAt,
        "deleted" to false
    )

    fun saveReceipt(db: ProductDb, r: Receipt): Receipt {
        val nr = r.copy(updatedAt = System.currentTimeMillis())
        db.upsertReceipts(listOf(nr))
        fs.collection("receipts").document(nr.id).set(receiptMap(nr))
        return nr
    }

    fun deleteReceipt(db: ProductDb, id: String) {
        db.deleteReceipt(id)
        fs.collection("receipts").document(id).set(
            hashMapOf<String, Any>("deleted" to true, "updatedAt" to System.currentTimeMillis()),
            SetOptions.merge()
        )
    }

    // ---------- чтение ----------

    private fun strList(v: Any?): List<String> = (v as? List<*>)?.map { it.toString() } ?: emptyList()

    private fun DocumentSnapshot.toProduct(): Product? {
        val name = getString("name") ?: return null
        return Product(
            id = id,
            name = name,
            article = get("article")?.toString() ?: "",
            barcode = get("barcode")?.toString() ?: "",
            weight = get("weight")?.toString() ?: "",
            unit = get("unit")?.toString() ?: "",
            pack = get("pack")?.toString() ?: "",
            manufacturer = get("manufacturer")?.toString() ?: "",
            photos = strList(get("photos")),
            updatedAt = getLong("updatedAt") ?: 0L
        )
    }

    private fun DocumentSnapshot.toReceipt(): Receipt? {
        if (!exists()) return null
        val items = (get("items") as? List<*>)?.mapNotNull { raw ->
            (raw as? Map<*, *>)?.let { m ->
                ReceiptItem(
                    m["productId"]?.toString() ?: "",
                    m["name"]?.toString() ?: "",
                    m["qty"]?.toString() ?: ""
                )
            }
        } ?: emptyList()
        return Receipt(
            id = id,
            sender = get("sender")?.toString() ?: "",
            orderNo = get("orderNo")?.toString() ?: "",
            receivedAt = getLong("receivedAt") ?: 0L,
            receiver = get("receiver")?.toString() ?: "",
            items = items,
            photos = strList(get("photos")),
            invoices = strList(get("invoices")),
            updatedAt = getLong("updatedAt") ?: 0L,
            fileName = get("fileName")?.toString() ?: "",
            stamp = get("stamp")?.toString() ?: ""
        )
    }

    private fun applyProducts(db: ProductDb, docs: List<DocumentSnapshot>) {
        val upserts = ArrayList<Product>()
        for (d in docs) {
            if (d.getBoolean("deleted") == true) db.deleteProduct(d.id)
            else d.toProduct()?.let { upserts.add(it) }
        }
        db.upsertProducts(upserts)
    }

    private fun applyReceipts(db: ProductDb, docs: List<DocumentSnapshot>) {
        val upserts = ArrayList<Receipt>()
        for (d in docs) {
            if (d.getBoolean("deleted") == true) db.deleteReceipt(d.id)
            else d.toReceipt()?.let { upserts.add(it) }
        }
        db.upsertReceipts(upserts)
    }

    /** Последние 10 по updatedAt; если среди них есть новые — ещё 10, и так далее. */
    private suspend fun incremental(
        col: CollectionReference,
        localMax: Long,
        maxPages: Int,
        apply: (List<DocumentSnapshot>) -> Unit
    ): Int {
        var total = 0
        var pages = 0
        var last: DocumentSnapshot? = null
        while (true) {
            var q: Query = col.orderBy("updatedAt", Query.Direction.DESCENDING).limit(PAGE)
            if (last != null) q = q.startAfter(last)
            val docs = q.get().awaitIt().documents
            val fresh = docs.filter { (it.getLong("updatedAt") ?: 0L) > localMax }
            withContext(Dispatchers.IO) { apply(fresh) }
            total += fresh.size
            pages++
            if (fresh.size < docs.size || docs.size < PAGE || pages >= maxPages) break
            last = docs.last()
        }
        return total
    }

    private suspend fun full(col: CollectionReference, st: SyncState, apply: (List<DocumentSnapshot>) -> Unit): Int {
        var total = 0
        var last: DocumentSnapshot? = null
        while (true) {
            var q: Query = col.orderBy(FieldPath.documentId()).limit(FULL_PAGE)
            if (last != null) q = q.startAfter(last)
            val docs = q.get().awaitIt().documents
            if (docs.isEmpty()) break
            withContext(Dispatchers.IO) { apply(docs) }
            total += docs.size
            st.text = "Загрузка каталога… $total"
            last = docs.last()
            if (docs.size < FULL_PAGE) break
        }
        return total
    }

    private fun errorText(e: Exception): String =
        if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
            "Нет доступа к Firebase (проверьте правила Firestore)"
        else if (e is FirebaseFirestoreException) "Нет связи с Firebase: ${e.code}"
        else "Ошибка синхронизации: ${e.message}"

    suspend fun runProducts(db: ProductDb, st: SyncState, force: Boolean) {
        if (st.busy) return
        val empty = withContext(Dispatchers.IO) { db.countProducts() } == 0
        if (!force && !empty && System.currentTimeMillis() - lastProducts < PRODUCTS_INTERVAL) return
        st.busy = true
        try {
            val col = fs.collection("products")
            val added = if (empty) full(col, st) { applyProducts(db, it) }
            else incremental(col, withContext(Dispatchers.IO) { db.maxProductUpdated() }, Int.MAX_VALUE) { applyProducts(db, it) }
            lastProducts = System.currentTimeMillis()
            val total = withContext(Dispatchers.IO) { db.countProducts() }
            st.text = if (empty) "Загружено товаров: $total"
            else if (added > 0) "Обновлено: $added, всего $total" else "Каталог актуален ($total)"
            st.version++
        } catch (e: Exception) {
            st.text = errorText(e)
        } finally {
            st.busy = false
        }
    }

    suspend fun runReceipts(db: ProductDb, st: SyncState, force: Boolean) {
        if (st.busy) return
        if (!force && System.currentTimeMillis() - lastReceipts < RECEIPTS_INTERVAL) return
        st.busy = true
        try {
            val localMax = withContext(Dispatchers.IO) { db.maxReceiptUpdated() }
            // при пустой истории берём только последние 50 приёмок (5 страниц по 10)
            val pages = if (localMax == 0L) 5 else Int.MAX_VALUE
            val added = incremental(fs.collection("receipts"), localMax, pages) { applyReceipts(db, it) }
            lastReceipts = System.currentTimeMillis()
            st.text = if (added > 0) "Обновлено: $added" else ""
            st.version++
        } catch (e: Exception) {
            st.text = errorText(e)
        } finally {
            st.busy = false
        }
    }

    /**
     * Проверка на повторную обработку. Приоритет признаков: 1) номер заказа, 2) имя файла, 3) дата и время.
     * Пустые значения пропускаются. Сначала локальная история, потом Firebase (1 чтение на признак).
     */
    suspend fun findDuplicate(db: ProductDb, order: String, fileName: String, stamp: String): Dup? {
        val checks = ArrayList<Triple<String, String, String>>() // подпись, поле Firestore, значение
        if (order.isNotBlank() && order != "none") checks.add(Triple("номер заказа", "orderNo", order))
        if (fileName.isNotBlank()) checks.add(Triple("имя файла", "fileName", fileName))
        if (stamp.length == 16) checks.add(Triple("дата и время", "stamp", stamp))
        for ((label, field, value) in checks) {
            val column = when (field) {
                "orderNo" -> "order_no"
                "fileName" -> "file_name"
                else -> "stamp"
            }
            val local = withContext(Dispatchers.IO) { db.findReceiptBy(column, value) }
            if (local != null) return Dup(label, local)
            try {
                val docs = fs.collection("receipts").whereEqualTo(field, value).limit(5).get().awaitIt().documents
                val r = docs.firstOrNull { it.getBoolean("deleted") != true }?.toReceipt()
                if (r != null) {
                    withContext(Dispatchers.IO) { db.upsertReceipts(listOf(r)) }
                    return Dup(label, r)
                }
            } catch (e: Exception) {
                // нет связи — остаёмся с проверкой по локальной истории
            }
        }
        return null
    }
}
