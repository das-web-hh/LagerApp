package ru.storage.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Calendar

/** Позиция документа для сравнения с уже принятым товаром. */
class DocItem(val name: String, val qty: String)

/** Товар, который за этот же день уже принимался с тем же количеством. */
class SameDay(val name: String, val qty: String, val receipt: Receipt)

/** Найденная приёмка без накладной, к которой можно привязать документ. by — по какому признаку совпало. */
class Found(val receipt: Receipt, val by: String)

/**
 * Проверка накладной при приёмке:
 *  1) заказ уже принят, а накладной у него нет → документ привязывается к этому заказу;
 *  2) отправитель и товары совпали, а номера заказа в базе нет → привязать документ и дописать номер заказа;
 *  3) дата и товары совпали, а отправителя и номера заказа в базе нет → привязать документ и дописать их;
 *  4) за один день один и тот же товар с тем же количеством уже принимался → спрашиваем «принять / отказать».
 * Точные повторы (номер заказа, содержимое файла, имя файла) не принимаются вообще — см. Cloud.findDuplicate.
 */
object InvoiceCheck {
    /** Сколько дней вокруг даты документа искать приёмку без накладной по отправителю и товарам (случаи 2 и 3). */
    const val GOODS_WINDOW_DAYS = 7

    /** Насколько похожи названия, чтобы считать товар тем же (0…1). */
    private const val NAME_SIM = 0.6

    fun isNone(s: String): Boolean = s.isBlank() || s.equals("none", ignoreCase = true)

    fun clean(s: String): String = if (isNone(s)) "" else s.trim()

    fun sha256(f: File): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (e: Exception) {
        ""
    }

    /** Название без регистра и знаков — для точного сравнения. */
    fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.toSet()

    /** Похожесть названий. Если числа в названиях (вес, объём) разные — это разные товары. */
    fun nameSim(a: String, b: String): Double {
        val na = norm(a)
        val nb = norm(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val da = ta.filter { t -> t.all { it.isDigit() } }.toSet()
        val db = tb.filter { t -> t.all { it.isDigit() } }.toSet()
        if (da.isNotEmpty() && db.isNotEmpty() && da != db) return 0.0
        if (na.length >= 8 && nb.length >= 8 && (na.contains(nb) || nb.contains(na))) return 0.9
        val inter = ta.intersect(tb).size
        return inter.toDouble() / ta.union(tb).size
    }

    /** Один и тот же отправитель (названия записывают по-разному: «Innopha», «Innopha GmbH»). */
    fun sameSender(a: String, b: String): Boolean {
        val ka = InvoiceProfiles.key(a)
        val kb = InvoiceProfiles.key(b)
        if (ka.length < 3 || kb.length < 3) return false
        return ka == kb || ka.contains(kb) || kb.contains(ka)
    }

    /** Начало дня (плюс plusDays дней) в часовом поясе телефона. */
    fun dayStart(ms: Long, plusDays: Int = 0): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (plusDays != 0) c.add(Calendar.DAY_OF_YEAR, plusDays)
        return c.timeInMillis
    }

    /** Товары документа и приёмки совпадают (по названиям, у одиночной позиции — ещё и по количеству). */
    fun goodsMatch(doc: List<DocItem>, r: Receipt): Boolean {
        val d = doc.filter { it.name.isNotBlank() }
        val x = r.items.filter { it.name.isNotBlank() }
        if (d.isEmpty() || x.isEmpty()) return false
        val smaller = minOf(d.size, x.size)
        if (smaller == 1) {
            return d.any { di ->
                val dq = cleanQty(di.qty)
                dq.isNotEmpty() && x.any { xi ->
                    nameSim(di.name, xi.name) >= NAME_SIM && (cleanQty(xi.qty) == dq || cleanQty(xi.plan) == dq)
                }
            }
        }
        val matched = d.count { di -> x.any { xi -> nameSim(di.name, xi.name) >= NAME_SIM } }
        val need = Math.ceil(0.6 * smaller).toInt().coerceAtLeast(1)
        return matched >= need
    }

    /** Заказ с таким номером уже принят? (локальная история, затем Firebase) */
    suspend fun byOrder(db: ProductDb, order: String): Receipt? {
        val o = clean(order)
        if (o.isEmpty()) return null
        return try {
            Cloud.findDuplicate(db, o, "", "")?.receipt
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ищет приёмку БЕЗ накладной, к которой относится документ: отправитель и товары совпали (случай 2),
     * либо у приёмки нет ни отправителя, ни номера заказа, а дата и товары совпали (случай 3).
     * Приёмка с другим номером заказа не подходит. Из нескольких подходящих берётся ближайшая по времени.
     */
    suspend fun byGoods(db: ProductDb, sender: String, order: String, docAt: Long?, items: List<DocItem>): Found? {
        val s = clean(sender)
        val o = clean(order)
        if (items.isEmpty()) return null
        val base = docAt ?: System.currentTimeMillis()
        val list = withContext(Dispatchers.IO) {
            db.receiptsBetween(dayStart(base, -GOODS_WINDOW_DAYS), dayStart(base, GOODS_WINDOW_DAYS + 1))
        }
        var best: Found? = null
        var bestDist = Long.MAX_VALUE
        for (r in list) {
            if (r.invoices.isNotEmpty()) continue
            if (o.isNotEmpty() && r.orderNo.isNotBlank() && Ai.latinOrder(r.orderNo) != Ai.latinOrder(o)) continue
            var by = ""
            if (s.isNotEmpty() && r.sender.isNotBlank() && sameSender(s, r.sender)) {
                by = "отправитель и товары"
            } else if (r.sender.isBlank() && r.orderNo.isBlank() && (s.isNotEmpty() || o.isNotEmpty()) &&
                docAt != null && dayStart(r.receivedAt) == dayStart(docAt)
            ) {
                by = "дата и товары"
            }
            if (by.isEmpty()) continue
            if (!goodsMatch(items, r)) continue
            val dist = Math.abs(r.receivedAt - base)
            if (dist < bestDist) {
                best = Found(r, by)
                bestDist = dist
            }
        }
        return best
    }

    /** Привязывает файл накладной к приёмке и дописывает то, чего в ней не хватало (отправитель, номер заказа). Возвращает текст ошибки или null. */
    suspend fun attach(
        ctx: Context,
        db: ProductDb,
        r: Receipt,
        f: IncomingFile,
        sender: String,
        order: String,
        stamp: String,
        hash: String
    ): String? = withContext(Dispatchers.IO) {
        if (!Session.isAdmin) {
            return@withContext "Приёмка уже записана в базу: дописать к ней накладную, отправителя или номер заказа может только администратор."
        }
        try {
            val inv = Media.importFile(ctx, File(f.path), db, "IN")
            if (inv == null) {
                "Не удалось сохранить файл накладной"
            } else {
                val s = clean(sender)
                val o = clean(order)
                val nr = r.copy(
                    sender = if (r.sender.isBlank() && s.isNotEmpty()) s else r.sender,
                    orderNo = if (r.orderNo.isBlank() && o.isNotEmpty()) o else r.orderNo,
                    invoices = r.invoices + inv,
                    fileName = r.fileName.ifBlank { f.name },
                    stamp = r.stamp.ifBlank { stamp },
                    docHash = hash.ifBlank { r.docHash }
                )
                Cloud.saveReceipt(db, nr)
                Media.uploadPending(ctx, db)
                null
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Текст для экрана: к какой приёмке привязана накладная и что в неё дописано. */
    fun attachNote(r: Receipt, how: String, sender: String, order: String): String {
        val s = clean(sender)
        val o = clean(order)
        val added = ArrayList<String>()
        if (r.sender.isBlank() && s.isNotEmpty()) added.add("отправитель $s")
        if (r.orderNo.isBlank() && o.isNotEmpty()) added.add("номер заказа $o")
        val head = listOf(r.sender.ifBlank { s }, r.orderNo.ifBlank { o }, fmtTime(r.receivedAt))
            .filter { it.isNotBlank() }.joinToString(" · ")
        return "Накладная привязана к уже принятому приёму ($how): $head. Товары повторно не добавлялись." +
            (if (added.isNotEmpty()) " Дописано: " + added.joinToString(", ") + "." else "")
    }

    /** Товары, которые в день приёма уже принимались с тем же названием и количеством. */
    suspend fun sameDayItems(db: ProductDb, rows: List<DocItem>, at: Long): List<SameDay> {
        val list = withContext(Dispatchers.IO) { db.receiptsBetween(dayStart(at), dayStart(at, 1)) }
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<SameDay>()
        for (row in rows) {
            val q = cleanQty(row.qty)
            if (q.isEmpty() || (q.toDoubleOrNull() ?: 0.0) <= 0.0) continue
            val key = norm(row.name)
            if (key.isEmpty()) continue
            for (r in list) {
                if (r.items.any { norm(it.name) == key && cleanQty(it.qty) == q }) {
                    out.add(SameDay(row.name, q, r))
                    break
                }
            }
        }
        return out
    }
}
