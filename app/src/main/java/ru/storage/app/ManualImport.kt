package ru.storage.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ImportItem(val name: String, val qty: String, val barcode: String)

class ImportResult(
    val sender: String,
    val order: String,
    val receivedAt: Long?,
    val stamp: String,
    val items: List<ImportItem>,
    /** строки с количеством 0 — не принимаются */
    val zeroLines: List<String>,
    /** строки, которые не удалось разобрать */
    val badLines: List<String>
)

/**
 * Приём «из ИИ»: в строку поиска окна «Приём вручную» вставляют текст (или жмут кнопку «Вставить из буфера»),
 * который выдал ИИ по накладной. Текст принимается ТОЛЬКО если первая строка — метка #LAGER;
 * любой другой случайно вставленный текст игнорируется:
 *
 *   #LAGER
 *   Отправитель/EB1234567/16.07.2026 16:48
 *   Название товара<TAB>количество<TAB>штрих-код (необязательно)
 *
 * Нет отправителя или номера заказа — no_data (также понимаются no_name, EB_no_data, no_date).
 */
object AiImport {
    private val EMPTY = setOf("", "no_data", "no_name", "no_date", "eb_no_data", "none")
    private val NUM = Regex("\\d+([.,]\\d+)?")
    private val BARCODE = Regex("\\d{8,14}")

    const val MARKER = "#LAGER"

    private fun lines(text: String): List<String> =
        text.replace("\r", "\n").split('\n')
            .map { it.replace('\u00A0', ' ').trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }

    /** Строки после метки #LAGER (первая — заголовок отправитель/заказ/время); null — метки нет, текст чужой. */
    private fun body(text: String): List<String>? {
        val l = lines(text)
        val first = l.firstOrNull() ?: return null
        if (!first.startsWith(MARKER, ignoreCase = true)) return null
        val rest = first.substring(MARKER.length).trim()
        return (if (rest.isNotEmpty()) listOf(rest) else emptyList()) + l.drop(1)
    }

    /** Текст — ответ ИИ: есть метка #LAGER, заголовок с двумя «/» и хотя бы одна строка товара. */
    fun looksLike(text: String): Boolean {
        val b = body(text) ?: return false
        return b.size >= 2 && b.first().count { it == '/' } >= 2
    }

    private fun clean(s: String): String = s.trim().let { if (it.lowercase() in EMPTY) "" else it }

    fun parse(text: String): ImportResult? {
        val l = body(text) ?: return null
        if (l.size < 2) return null
        val head = l.first().split('/')
        if (head.size < 3) return null
        val date = head.last().trim()
        val order = clean(head[head.size - 2])
        val sender = cleanSenderName(clean(head.dropLast(2).joinToString("/")))

        val m = Regex("(\\d{2}\\.\\d{2}\\.\\d{4})\\s+(\\d{2}:\\d{2})").find(date)
        val at = m?.let { parseTime("${it.groupValues[1]} ${it.groupValues[2]}") }
        val stamp = if (m != null && at != null) "${m.groupValues[1]} ${m.groupValues[2]}" else ""

        val items = ArrayList<ImportItem>()
        val zero = ArrayList<String>()
        val bad = ArrayList<String>()
        for (line in l.drop(1)) {
            var name: String
            var qtyRaw: String
            var barcode = ""
            if (line.contains('\t')) {
                val p = line.split('\t')
                name = p[0].trim()
                qtyRaw = p.getOrNull(1)?.trim().orEmpty()
                barcode = p.getOrNull(2)?.trim().orEmpty()
            } else {
                // вставка без табуляции (пробелы): название, затем количество, затем необязательный штрих-код
                val t = line.split(Regex("\\s+"))
                var end = t.size
                if (end >= 3 && BARCODE.matches(t[end - 1]) && NUM.matches(t[end - 2])) {
                    barcode = t[end - 1]
                    end--
                }
                if (end >= 2 && NUM.matches(t[end - 1])) {
                    qtyRaw = t[end - 1]
                    name = t.subList(0, end - 1).joinToString(" ")
                } else {
                    name = line
                    qtyRaw = ""
                }
            }
            val qty = cleanQty(qtyRaw)
            if (name.isBlank() || qty.isEmpty()) {
                bad.add(line)
                continue
            }
            if (qty.toDoubleOrNull() == 0.0) {
                zero.add(name)
                continue
            }
            items.add(ImportItem(name, qty, barcode))
        }
        return ImportResult(sender, order, at, stamp, items, zero, bad)
    }

    /** Сохраняет приём (история, Firebase, локальная база). Возвращает текст для строки состояния. */
    suspend fun save(ctx: Context, db: ProductDb, r: ImportResult, receiver: String): String {
        if (r.badLines.isNotEmpty()) {
            val more = if (r.badLines.size > 1) " (и ещё ${r.badLines.size - 1})" else ""
            return "Не сохранено. Не удалось разобрать строку: ${r.badLines.first()}$more"
        }
        if (r.items.isEmpty()) return "Не сохранено: нет товаров с количеством больше нуля"
        if (r.order.isNotEmpty()) {
            val d = try { Cloud.findDuplicate(db, r.order, "", "") } catch (e: Exception) { null }
            if (d != null) return "Заказ ${r.order} уже принят ${fmtTime(d.receipt.receivedAt)} — повторно не сохраняю."
        }
        val notFound = ArrayList<String>()
        val now = System.currentTimeMillis()
        val rec = withContext(Dispatchers.IO) {
            val rows = r.items.map {
                val p = db.findByName(it.name) ?: if (it.barcode.isNotBlank()) db.findByBarcode(it.barcode) else null
                if (p == null) notFound.add(it.name)
                ReceiptItem(p?.id ?: "", it.name, it.qty)
            }
            val receipt = Receipt(
                id = UUID.randomUUID().toString(),
                sender = r.sender, orderNo = r.order,
                receivedAt = r.receivedAt ?: now, receiver = receiver,
                items = rows, photos = emptyList(), invoices = emptyList(),
                updatedAt = now, stamp = r.stamp
            )
            Cloud.saveReceipt(db, receipt)
            receipt
        }
        val head = listOf(
            r.sender.ifEmpty { "без отправителя" },
            r.order.ifEmpty { "без номера заказа" },
            fmtTime(rec.receivedAt)
        ).joinToString(" · ")
        return buildString {
            append("Принято из ИИ: $head · позиций: ${r.items.size}")
            if (notFound.isNotEmpty()) append("\nНет в каталоге (сохранены по названию): ${notFound.size}")
            if (r.zeroLines.isNotEmpty()) append("\nПропущено с количеством 0: ${r.zeroLines.size}")
        }
    }
}

/**
 * Excel-файл для приёма по наименованию. Столбцы строго те же, что при вставке в окне «Приём вручную»:
 *
 *   строка 1:  #LAGER
 *   строка 2:  Отправитель/EB1234567/16.07.2026 16:48   (одной ячейкой или тремя: отправитель | заказ | дата и время)
 *   строка 3+: A — название товара, B — количество, C — штрих-код (необязательно)
 *
 * Файл читается тем же разбором, что и вставка из буфера (AiImport.parse), поэтому правила одинаковые.
 * Если метки #LAGER нет, таблица читается как «название | количество | штрих-код» без отправителя и заказа;
 * первая строка пропускается, если в ней вместо количества текст (заголовок столбцов).
 */
object ExcelImport {
    private val DATE = Regex("\\d{2}\\.\\d{2}\\.\\d{4}")
    private val NUM = Regex("\\d+([.,]\\d+)?")

    private fun isHeader(cells: List<String>): Boolean {
        val first = cells.firstOrNull().orEmpty()
        if (first.count { it == '/' } >= 2) return true
        val nonEmpty = cells.filter { it.isNotEmpty() }
        return nonEmpty.size >= 2 && nonEmpty.none { NUM.matches(it) } && DATE.containsMatchIn(nonEmpty.last())
    }

    private fun headerLine(cells: List<String>): String {
        val first = cells.firstOrNull().orEmpty()
        if (first.count { it == '/' } >= 2) return first
        val parts = cells.filter { it.isNotEmpty() }
        return when (parts.size) {
            0 -> "no_data/no_data/"
            1 -> "${parts[0]}/no_data/"
            2 -> "${parts[0]}/${parts[1]}/"
            else -> "${parts[0]}/${parts[1]}/${parts[2]}"
        }
    }

    private fun itemLine(cells: List<String>): String =
        listOf(cells.getOrElse(0) { "" }, cells.getOrElse(1) { "" }, cells.getOrElse(2) { "" })
            .joinToString("\t").trimEnd('\t')

    /** Строки таблицы → текст в формате вставки (#LAGER …). Пустая строка — таблица пустая. */
    fun toPasteText(rows: List<List<String>>): String {
        val clean = rows
            .map { r -> r.map { it.replace('\u00A0', ' ').trim() } }
            .filter { r -> r.any { it.isNotEmpty() } }
        if (clean.isEmpty()) return ""
        val sb = StringBuilder(AiImport.MARKER).append('\n')
        val marked = clean[0].firstOrNull().orEmpty().startsWith(AiImport.MARKER, ignoreCase = true)
        var rest = clean
        if (marked) {
            // заголовок может стоять в той же строке после метки или в следующей
            val after = clean[0].drop(1).filter { it.isNotEmpty() }
            val tail = clean[0][0].substring(AiImport.MARKER.length).trim()
            rest = clean.drop(1)
            when {
                after.isNotEmpty() || tail.isNotEmpty() ->
                    sb.append(headerLine(if (tail.isNotEmpty()) listOf(tail) + after else after)).append('\n')
                rest.isNotEmpty() && isHeader(rest[0]) -> {
                    sb.append(headerLine(rest[0])).append('\n')
                    rest = rest.drop(1)
                }
                else -> sb.append("no_data/no_data/\n")
            }
        } else {
            sb.append("no_data/no_data/\n")
            val f = rest[0]
            val q = f.getOrElse(1) { "" }
            if (q.isEmpty() || !NUM.matches(q)) rest = rest.drop(1)
        }
        rest.forEach { sb.append(itemLine(it)).append('\n') }
        return sb.toString()
    }

    fun parse(rows: List<List<String>>): ImportResult? {
        val text = toPasteText(rows)
        if (text.isEmpty()) return null
        return AiImport.parse(text)
    }
}
