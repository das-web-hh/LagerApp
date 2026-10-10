package ru.storage.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Product(
    val id: String,
    val name: String,
    val article: String,
    val barcode: String,
    val weight: String,
    val unit: String,
    val pack: String,
    val manufacturer: String,
    val photos: List<String>,
    val updatedAt: Long
)

data class ReceiptItem(val productId: String, val name: String, val qty: String)

data class Receipt(
    val id: String,
    val sender: String,
    val orderNo: String,
    val receivedAt: Long,
    val receiver: String,
    val items: List<ReceiptItem>,
    val photos: List<String>,
    val invoices: List<String>,
    val updatedAt: Long,
    val fileName: String = "",
    val stamp: String = ""
) {
    fun allMedia(): List<String> = photos + invoices
}

fun fmtTime(ms: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(ms))

fun parseTime(s: String): Long? = try {
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).apply { isLenient = false }
        .parse(s.trim())?.time
} catch (e: Exception) {
    null
}

fun listToJson(l: List<String>): String = JSONArray(l).toString()

fun jsonToList(s: String?): List<String> {
    if (s.isNullOrBlank()) return emptyList()
    return try {
        val a = JSONArray(s)
        List(a.length()) { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
}

fun itemsToJson(l: List<ReceiptItem>): String {
    val a = JSONArray()
    l.forEach { a.put(JSONObject().put("id", it.productId).put("name", it.name).put("qty", it.qty)) }
    return a.toString()
}

fun jsonToItems(s: String?): List<ReceiptItem> {
    if (s.isNullOrBlank()) return emptyList()
    return try {
        val a = JSONArray(s)
        List(a.length()) {
            val o = a.getJSONObject(it)
            ReceiptItem(o.optString("id"), o.optString("name"), o.optString("qty"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}

class ProductDb private constructor(context: Context) :
    SQLiteOpenHelper(context, "catalog.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE products (" +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL, article TEXT NOT NULL, " +
                "barcode TEXT NOT NULL, name_lc TEXT NOT NULL, search_lc TEXT NOT NULL, " +
                "weight TEXT NOT NULL DEFAULT '', unit TEXT NOT NULL DEFAULT '', " +
                "pack TEXT NOT NULL DEFAULT '', manufacturer TEXT NOT NULL DEFAULT '', " +
                "photos TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_products_name ON products(name_lc)")
        createExtra(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            listOf("weight", "unit", "pack", "manufacturer", "photos").forEach {
                db.execSQL("ALTER TABLE products ADD COLUMN $it TEXT NOT NULL DEFAULT ''")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_products_name ON products(name_lc)")
            createExtra(db)
        } else if (oldVersion == 2) {
            db.execSQL("ALTER TABLE receipts ADD COLUMN file_name TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE receipts ADD COLUMN stamp TEXT NOT NULL DEFAULT ''")
        }
    }

    private fun createExtra(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_products_barcode ON products(barcode)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_products_article ON products(article)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS receipts (" +
                "id TEXT PRIMARY KEY, sender TEXT NOT NULL, order_no TEXT NOT NULL, " +
                "received_at INTEGER NOT NULL, receiver TEXT NOT NULL, items TEXT NOT NULL, " +
                "photos TEXT NOT NULL, invoices TEXT NOT NULL, updated_at INTEGER NOT NULL, " +
                "file_name TEXT NOT NULL DEFAULT '', stamp TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS uploads (name TEXT PRIMARY KEY)")
    }

    // ---------- товары ----------

    private val pcols = "id, name, article, barcode, weight, unit, pack, manufacturer, photos, updated_at"

    private fun readProducts(sql: String, args: Array<String>): List<Product> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Product>()
            while (c.moveToNext()) {
                out.add(
                    Product(
                        c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getString(6), c.getString(7),
                        jsonToList(c.getString(8)), c.getLong(9)
                    )
                )
            }
            out
        }

    fun countProducts(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM products", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun maxProductUpdated(): Long =
        readableDatabase.rawQuery("SELECT MAX(updated_at) FROM products", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }

    fun upsertProducts(list: List<Product>) {
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
                    put("weight", p.weight)
                    put("unit", p.unit)
                    put("pack", p.pack)
                    put("manufacturer", p.manufacturer)
                    put("photos", listToJson(p.photos))
                    put("updated_at", p.updatedAt)
                }
                db.insertWithOnConflict("products", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteProduct(id: String) {
        writableDatabase.delete("products", "id = ?", arrayOf(id))
    }

    fun getProduct(id: String): Product? =
        readProducts("SELECT $pcols FROM products WHERE id = ?", arrayOf(id)).firstOrNull()

    fun findByBarcode(code: String): Product? =
        readProducts("SELECT $pcols FROM products WHERE barcode = ? LIMIT 1", arrayOf(code)).firstOrNull()

    fun findByArticle(article: String): Product? =
        readProducts("SELECT $pcols FROM products WHERE article = ? LIMIT 1", arrayOf(article)).firstOrNull()

    fun allProducts(): List<Product> =
        readProducts("SELECT $pcols FROM products ORDER BY name_lc", emptyArray())

    /** Поиск по названию, артикулу и штрих-коду; каждое слово запроса должно встретиться. */
    fun search(query: String, limit: Int, newestFirst: Boolean = false): List<Product> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        fun esc(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val where = words.joinToString(" AND ") { "search_lc LIKE ? ESCAPE '\\'" }
        val args = ArrayList<String>()
        words.forEach { args.add("%" + esc(it) + "%") }
        val order = if (newestFirst) {
            "updated_at DESC, name_lc"
        } else {
            args.add(esc(words.first()) + "%")
            "(name_lc LIKE ? ESCAPE '\\') DESC, name_lc"
        }
        return readProducts(
            "SELECT $pcols FROM products WHERE $where ORDER BY $order LIMIT $limit",
            args.toTypedArray()
        )
    }

    // ---------- приёмки ----------

    private val rcols = "id, sender, order_no, received_at, receiver, items, photos, invoices, updated_at, file_name, stamp"

    private fun readReceipts(sql: String, args: Array<String>): List<Receipt> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Receipt>()
            while (c.moveToNext()) {
                out.add(
                    Receipt(
                        c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4),
                        jsonToItems(c.getString(5)), jsonToList(c.getString(6)),
                        jsonToList(c.getString(7)), c.getLong(8), c.getString(9), c.getString(10)
                    )
                )
            }
            out
        }

    fun countReceipts(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM receipts", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun maxReceiptUpdated(): Long =
        readableDatabase.rawQuery("SELECT MAX(updated_at) FROM receipts", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }

    fun upsertReceipts(list: List<Receipt>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in list) {
                val v = ContentValues().apply {
                    put("id", r.id)
                    put("sender", r.sender)
                    put("order_no", r.orderNo)
                    put("received_at", r.receivedAt)
                    put("receiver", r.receiver)
                    put("items", itemsToJson(r.items))
                    put("photos", listToJson(r.photos))
                    put("invoices", listToJson(r.invoices))
                    put("updated_at", r.updatedAt)
                    put("file_name", r.fileName)
                    put("stamp", r.stamp)
                }
                db.insertWithOnConflict("receipts", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteReceipt(id: String) {
        writableDatabase.delete("receipts", "id = ?", arrayOf(id))
    }

    fun getReceipt(id: String): Receipt? =
        readReceipts("SELECT $rcols FROM receipts WHERE id = ?", arrayOf(id)).firstOrNull()

    /** column: order_no, file_name или stamp — внутренние имена столбцов. */
    fun findReceiptBy(column: String, value: String): Receipt? =
        readReceipts("SELECT $rcols FROM receipts WHERE $column = ? ORDER BY received_at DESC LIMIT 1", arrayOf(value))
            .firstOrNull()

    fun findByName(name: String): Product? =
        readProducts("SELECT $pcols FROM products WHERE name_lc = ? LIMIT 1", arrayOf(name.trim().lowercase()))
            .firstOrNull()

    fun allReceipts(): List<Receipt> =
        readReceipts("SELECT $rcols FROM receipts ORDER BY received_at DESC", emptyArray())

    // ---------- файлы, ожидающие отправки в Firebase Storage ----------

    fun addUpload(name: String) {
        val v = ContentValues().apply { put("name", name) }
        writableDatabase.insertWithOnConflict("uploads", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeUpload(name: String) {
        writableDatabase.delete("uploads", "name = ?", arrayOf(name))
    }

    fun pendingUploads(): List<String> =
        readableDatabase.rawQuery("SELECT name FROM uploads", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }

    companion object {
        @Volatile private var instance: ProductDb? = null
        fun get(context: Context): ProductDb = instance ?: synchronized(this) {
            instance ?: ProductDb(context.applicationContext).also { instance = it }
        }
    }
}
