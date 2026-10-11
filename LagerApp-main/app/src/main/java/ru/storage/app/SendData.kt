package ru.storage.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.UUID

/** Счётчик изменений: экраны «Отправка» и «Инфо» перечитывают базу, когда он растёт. */
object SendState {
    var version by mutableIntStateOf(0)
}

fun newSendId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

/**
 * Посылка DHL Paket. status: "new"/"proc" — идёт распознавание этикетки, "done" — номер найден,
 * "check" — номер не найден, проверьте вручную, "error" — распознать не удалось, "manual" — добавлена вручную.
 */
data class Paket(
    val id: String,
    val createdAt: Long,
    val tracking: String = "",
    val product: String = "",
    val recipient: String = "",
    val street: String = "",
    val zip: String = "",
    val city: String = "",
    val country: String = "",
    val sender: String = "",
    val weight: String = "",
    val routing: String = "",
    val note: String = "",
    val photos: List<String> = emptyList(),
    val rawText: String = "",
    val status: String = "new",
    val author: String = "",
    val updatedAt: Long = 0L,
    /** Номер расчёта (Abrechnungsnr / Billing No). */
    val billing: String = "",
    /** Референс отправителя (Referenznr / Sendungsreferenz / Warenkorb-ID). */
    val reference: String = "",
    /** Отметки и услуги с этикетки: Nachnahme, Schweres Paket, Filial-Routing, GoGreen… */
    val services: String = ""
) {
    fun title(): String = when {
        tracking.isNotBlank() -> tracking
        recipient.isNotBlank() -> recipient
        note.isNotBlank() -> note
        else -> "Посылка"
    }
}

/** Операция DHL Postwagen: сколько отправлено и сколько получено. */
data class WagenOp(
    val id: String,
    val createdAt: Long,
    val sent: Int,
    val received: Int,
    val note: String = "",
    val author: String = "",
    val updatedAt: Long = 0L
)

class SendDb private constructor(context: Context) : SQLiteOpenHelper(context, "send.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE pakets (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, tracking TEXT NOT NULL, " +
                "product TEXT NOT NULL, recipient TEXT NOT NULL, street TEXT NOT NULL, zip TEXT NOT NULL, " +
                "city TEXT NOT NULL, country TEXT NOT NULL, sender TEXT NOT NULL, weight TEXT NOT NULL, " +
                "routing TEXT NOT NULL, note TEXT NOT NULL, photos TEXT NOT NULL, raw_text TEXT NOT NULL, " +
                "status TEXT NOT NULL, author TEXT NOT NULL, updated_at INTEGER NOT NULL, " +
                "billing TEXT NOT NULL DEFAULT '', reference TEXT NOT NULL DEFAULT '', services TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX idx_pakets_created ON pakets(created_at)")
        db.execSQL(
            "CREATE TABLE wagen (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, sent INTEGER NOT NULL, " +
                "received INTEGER NOT NULL, note TEXT NOT NULL, author TEXT NOT NULL, updated_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_wagen_created ON wagen(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE pakets ADD COLUMN billing TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE pakets ADD COLUMN reference TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE pakets ADD COLUMN services TEXT NOT NULL DEFAULT ''")
        }
    }

    private val pcols = "id, created_at, tracking, product, recipient, street, zip, city, country, sender, " +
        "weight, routing, note, photos, raw_text, status, author, updated_at, billing, reference, services"

    private fun readPakets(sql: String, args: Array<String>): List<Paket> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<Paket>()
            while (c.moveToNext()) {
                out.add(
                    Paket(
                        id = c.getString(0), createdAt = c.getLong(1), tracking = c.getString(2),
                        product = c.getString(3), recipient = c.getString(4), street = c.getString(5),
                        zip = c.getString(6), city = c.getString(7), country = c.getString(8),
                        sender = c.getString(9), weight = c.getString(10), routing = c.getString(11),
                        note = c.getString(12), photos = jsonToList(c.getString(13)), rawText = c.getString(14),
                        status = c.getString(15), author = c.getString(16), updatedAt = c.getLong(17),
                        billing = c.getString(18), reference = c.getString(19), services = c.getString(20)
                    )
                )
            }
            out
        }

    fun upsertPaket(p: Paket) {
        val v = ContentValues().apply {
            put("id", p.id)
            put("created_at", p.createdAt)
            put("tracking", p.tracking)
            put("product", p.product)
            put("recipient", p.recipient)
            put("street", p.street)
            put("zip", p.zip)
            put("city", p.city)
            put("country", p.country)
            put("sender", p.sender)
            put("weight", p.weight)
            put("routing", p.routing)
            put("note", p.note)
            put("photos", listToJson(p.photos))
            put("raw_text", p.rawText)
            put("status", p.status)
            put("author", p.author)
            put("updated_at", p.updatedAt)
            put("billing", p.billing)
            put("reference", p.reference)
            put("services", p.services)
        }
        writableDatabase.insertWithOnConflict("pakets", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getPaket(id: String): Paket? =
        readPakets("SELECT $pcols FROM pakets WHERE id = ?", arrayOf(id)).firstOrNull()

    fun allPakets(): List<Paket> =
        readPakets("SELECT $pcols FROM pakets ORDER BY created_at DESC", emptyArray())

    fun paketsBetween(from: Long, to: Long): List<Paket> = readPakets(
        "SELECT $pcols FROM pakets WHERE created_at >= ? AND created_at < ? ORDER BY created_at DESC",
        arrayOf(from.toString(), to.toString())
    )

    fun deletePaket(id: String) {
        writableDatabase.delete("pakets", "id = ?", arrayOf(id))
    }

    fun maxPaketUpdated(): Long =
        readableDatabase.rawQuery("SELECT MAX(updated_at) FROM pakets", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }

    // ---------- Postwagen ----------

    private fun readWagen(sql: String, args: Array<String>): List<WagenOp> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val out = ArrayList<WagenOp>()
            while (c.moveToNext()) {
                out.add(
                    WagenOp(
                        c.getString(0), c.getLong(1), c.getInt(2), c.getInt(3),
                        c.getString(4), c.getString(5), c.getLong(6)
                    )
                )
            }
            out
        }

    private val wcols = "id, created_at, sent, received, note, author, updated_at"

    fun upsertWagen(w: WagenOp) {
        val v = ContentValues().apply {
            put("id", w.id)
            put("created_at", w.createdAt)
            put("sent", w.sent)
            put("received", w.received)
            put("note", w.note)
            put("author", w.author)
            put("updated_at", w.updatedAt)
        }
        writableDatabase.insertWithOnConflict("wagen", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getWagen(id: String): WagenOp? =
        readWagen("SELECT $wcols FROM wagen WHERE id = ?", arrayOf(id)).firstOrNull()

    fun allWagen(): List<WagenOp> =
        readWagen("SELECT $wcols FROM wagen ORDER BY created_at DESC", emptyArray())

    fun wagenBetween(from: Long, to: Long): List<WagenOp> = readWagen(
        "SELECT $wcols FROM wagen WHERE created_at >= ? AND created_at < ? ORDER BY created_at DESC",
        arrayOf(from.toString(), to.toString())
    )

    fun deleteWagen(id: String) {
        writableDatabase.delete("wagen", "id = ?", arrayOf(id))
    }

    fun maxWagenUpdated(): Long =
        readableDatabase.rawQuery("SELECT MAX(updated_at) FROM wagen", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }

    companion object {
        @Volatile private var instance: SendDb? = null
        fun get(context: Context): SendDb = instance ?: synchronized(this) {
            instance ?: SendDb(context.applicationContext).also { instance = it }
        }
    }
}

/*
 * Firestore:
 *  shipments/{id}: createdAt, tracking, product, recipient, street, zip, city, country, sender, weight,
 *                  routing, note, photos[], rawText, status, author, updatedAt, billing, reference, services, deleted
 *  wagen_ops/{id}: createdAt, sent, received, note, author, updatedAt, deleted
 * Снимки этикеток: Google Диск (имена DH-…), связь — список photos.
 */
object SendCloud {
    private val fs get() = FirebaseFirestore.getInstance()
    @Volatile private var lastPull = 0L

    private fun paketMap(p: Paket): HashMap<String, Any> = hashMapOf(
        "createdAt" to p.createdAt,
        "tracking" to p.tracking,
        "product" to p.product,
        "recipient" to p.recipient,
        "street" to p.street,
        "zip" to p.zip,
        "city" to p.city,
        "country" to p.country,
        "sender" to p.sender,
        "weight" to p.weight,
        "routing" to p.routing,
        "note" to p.note,
        "photos" to p.photos,
        "rawText" to p.rawText,
        "status" to p.status,
        "author" to p.author,
        "updatedAt" to p.updatedAt,
        "billing" to p.billing,
        "reference" to p.reference,
        "services" to p.services,
        "deleted" to false
    )

    /** Локально сразу, в Firestore — в очередь (работает и без сети). */
    fun savePaket(db: SendDb, p: Paket): Paket {
        val np = p.copy(updatedAt = System.currentTimeMillis())
        db.upsertPaket(np)
        fs.collection("shipments").document(np.id).set(paketMap(np))
        SendState.version++
        return np
    }

    /** Промежуточное состояние (идёт распознавание) — только на этом телефоне. */
    fun savePaketLocal(db: SendDb, p: Paket): Paket {
        val np = p.copy(updatedAt = System.currentTimeMillis())
        db.upsertPaket(np)
        SendState.version++
        return np
    }

    fun deletePaket(db: SendDb, id: String) {
        db.deletePaket(id)
        fs.collection("shipments").document(id).set(
            hashMapOf<String, Any>("deleted" to true, "updatedAt" to System.currentTimeMillis()),
            SetOptions.merge()
        )
        SendState.version++
    }

    fun saveWagen(db: SendDb, w: WagenOp): WagenOp {
        val nw = w.copy(updatedAt = System.currentTimeMillis())
        db.upsertWagen(nw)
        fs.collection("wagen_ops").document(nw.id).set(
            hashMapOf<String, Any>(
                "createdAt" to nw.createdAt,
                "sent" to nw.sent,
                "received" to nw.received,
                "note" to nw.note,
                "author" to nw.author,
                "updatedAt" to nw.updatedAt,
                "deleted" to false
            )
        )
        SendState.version++
        return nw
    }

    fun deleteWagen(db: SendDb, id: String) {
        db.deleteWagen(id)
        fs.collection("wagen_ops").document(id).set(
            hashMapOf<String, Any>("deleted" to true, "updatedAt" to System.currentTimeMillis()),
            SetOptions.merge()
        )
        SendState.version++
    }

    /** Подтянуть новые записи с других телефонов (не чаще раза в минуту; при ошибке сети молча пропускаем). */
    suspend fun pull(db: SendDb) {
        if (System.currentTimeMillis() - lastPull < 60_000L) return
        lastPull = System.currentTimeMillis()
        try {
            var changed = false
            val sp = fs.collection("shipments").whereGreaterThan("updatedAt", db.maxPaketUpdated()).get().awaitIt()
            for (d in sp.documents) {
                changed = true
                if (d.getBoolean("deleted") == true) {
                    db.deletePaket(d.id)
                    continue
                }
                db.upsertPaket(
                    Paket(
                        id = d.id,
                        createdAt = d.getLong("createdAt") ?: 0L,
                        tracking = d.getString("tracking") ?: "",
                        product = d.getString("product") ?: "",
                        recipient = d.getString("recipient") ?: "",
                        street = d.getString("street") ?: "",
                        zip = d.getString("zip") ?: "",
                        city = d.getString("city") ?: "",
                        country = d.getString("country") ?: "",
                        sender = d.getString("sender") ?: "",
                        weight = d.getString("weight") ?: "",
                        routing = d.getString("routing") ?: "",
                        note = d.getString("note") ?: "",
                        photos = (d.get("photos") as? List<*>)?.map { it.toString() } ?: emptyList(),
                        rawText = d.getString("rawText") ?: "",
                        status = d.getString("status") ?: "done",
                        author = d.getString("author") ?: "",
                        updatedAt = d.getLong("updatedAt") ?: 0L,
                        billing = d.getString("billing") ?: "",
                        reference = d.getString("reference") ?: "",
                        services = d.getString("services") ?: ""
                    )
                )
            }
            val wp = fs.collection("wagen_ops").whereGreaterThan("updatedAt", db.maxWagenUpdated()).get().awaitIt()
            for (d in wp.documents) {
                changed = true
                if (d.getBoolean("deleted") == true) {
                    db.deleteWagen(d.id)
                    continue
                }
                db.upsertWagen(
                    WagenOp(
                        id = d.id,
                        createdAt = d.getLong("createdAt") ?: 0L,
                        sent = (d.getLong("sent") ?: 0L).toInt(),
                        received = (d.getLong("received") ?: 0L).toInt(),
                        note = d.getString("note") ?: "",
                        author = d.getString("author") ?: "",
                        updatedAt = d.getLong("updatedAt") ?: 0L
                    )
                )
            }
            if (changed) SendState.version++
        } catch (e: Exception) {
        }
    }
}
