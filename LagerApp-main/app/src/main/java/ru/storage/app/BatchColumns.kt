package ru.storage.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Какие столбцы таблицы что значат (номера с нуля, -1 — нет) и с какой строки идут товары. */
class BatchPick(val dataStart: Int, val name: Int, val qty: Int, val code: Int, val article: Int)

/**
 * Столбцы Excel для партии определяются сами, по шапке и по содержимому:
 *  • 8–14 цифр подряд (в том числе 13 — EAN-13) — штрих-код;
 *  • 4–7 цифр — артикул;
 *  • 1–3 цифры (в том числе 2,5) — количество; столбец «№ п/п» (1, 2, 3 …) не считается;
 *  • текст и смесь букв, цифр и значков — название товара.
 * Если название найти не удалось, приложение показывает окно выбора столбцов.
 */
object BatchColumns {
    private const val ROLE_CODE = 1
    private const val ROLE_NAME = 2
    private const val ROLE_QTY = 3
    private const val ROLE_ART = 4
    private const val ROLE_NUM = 5

    private val NAME_KEYS = listOf("наимен", "назван", "товар", "продук", "номенклат", "описан", "name", "item", "product", "bezeichn", "beschreib", "ware")
    private val QTY_KEYS = listOf("колич", "кол-во", "кол.", "qty", "quantity", "menge", "anzahl", "stück", "stueck", "pcs", "шт", "план")
    private val CODE_KEYS = listOf("штрих", "barcode", "bar code", "ean", "gtin", "шк", "strich")
    private val ART_KEYS = listOf("артикул", "арт", "article", "artikel", "art.", "art-", "art nr", "sku")
    private val BAD_QTY = listOf("цена", "price", "preis", "сумм", "sum", "amount", "итого", "total", "стоим", "вес", "weight", "gewicht")
    private val TOTAL_WORDS = listOf("итого", "всего", "total", "summe", "gesamt")

    private val INT = Regex("\\d+")
    private val DEC = Regex("\\d+[.,]\\d+")

    private fun cell(r: List<String>, i: Int): String = if (i < 0) "" else r.getOrElse(i) { "" }.trim()
    private fun hasLetter(s: String) = s.any { it.isLetter() }
    private fun has(s: String, keys: List<String>) = keys.any { s.contains(it) }

    private fun headerRole(raw: String): Int {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return 0
        if (s == "№" || s == "n" || s == "no" || s == "#" || s.startsWith("№ п") || s == "nr" || s == "nr.") return ROLE_NUM
        return when {
            has(s, CODE_KEYS) -> ROLE_CODE
            has(s, NAME_KEYS) -> ROLE_NAME
            has(s, BAD_QTY) -> 0
            has(s, QTY_KEYS) -> ROLE_QTY
            has(s, ART_KEYS) -> ROLE_ART
            else -> 0
        }
    }

    private class Stat {
        var filled = 0; var code = 0; var art = 0; var qty = 0; var text = 0; var textLen = 0L
        val ints = ArrayList<Long>()
    }

    /** Нумерация строк (1, 2, 3 …) — не количество. */
    private fun isSequence(v: List<Long>): Boolean {
        if (v.size < 3) return false
        var ok = 0
        for (i in 1 until v.size) if (v[i] == v[i - 1] + 1) ok++
        return ok * 10 >= (v.size - 1) * 8
    }

    fun detect(rows: List<List<String>>): BatchPick {
        val width = rows.maxOfOrNull { it.size } ?: 0
        if (width == 0) return BatchPick(0, -1, -1, -1, -1)

        // 1) шапка: строка, где опознаны хотя бы два разных назначения
        var headerRow = -1
        val hRole = IntArray(width)
        for (ri in 0 until minOf(15, rows.size)) {
            val roles = IntArray(width) { headerRole(cell(rows[ri], it)) }
            if (roles.filter { it != 0 && it != ROLE_NUM }.toSet().size >= 2) {
                headerRow = ri
                for (c in 0 until width) hRole[c] = roles[c]
                break
            }
        }
        fun fromHeader(role: Int): Int = if (headerRow < 0) -1 else (0 until width).firstOrNull { hRole[it] == role } ?: -1
        var name = fromHeader(ROLE_NAME)
        var qty = fromHeader(ROLE_QTY)
        var code = fromHeader(ROLE_CODE)
        var art = fromHeader(ROLE_ART)

        // 2) содержимое столбцов (для всего, что шапка не назвала)
        val from = if (headerRow >= 0) headerRow + 1 else 0
        val data = rows.drop(from).take(500)
        val st = Array(width) { Stat() }
        for (r in data) for (c in 0 until width) {
            val raw = cell(r, c)
            if (raw.isEmpty()) continue
            val v = raw.replace(" ", "").replace("\u00A0", "")
            val s = st[c]
            s.filled++
            when {
                INT.matches(v) && v.length in 8..14 -> s.code++
                INT.matches(v) && v.length in 4..7 -> s.art++
                INT.matches(v) && v.length <= 3 -> { s.qty++; v.toLongOrNull()?.let { s.ints.add(it) } }
                DEC.matches(v) && (v.replace(',', '.').toDoubleOrNull() ?: 1e9) < 1000.0 -> s.qty++
                INT.matches(v) -> { /* очень длинное число — ни то ни другое */ }
                else -> { s.text++; s.textLen += raw.length }
            }
        }
        fun frac(n: Int, c: Int) = if (st[c].filled == 0) 0.0 else n.toDouble() / st[c].filled
        val taken = HashSet<Int>()
        listOf(name, qty, code, art).filter { it >= 0 }.forEach { taken.add(it) }
        // столбец нумерации не берём
        val numbering = (0 until width).filter { hRole[it] == ROLE_NUM || (headerRow < 0 || hRole[it] == 0) && isSequence(st[it].ints) && st[it].qty == st[it].filled }.toSet()

        if (code < 0) code = (0 until width).filter { it !in taken && frac(st[it].code, it) >= 0.6 }.maxByOrNull { st[it].code } ?: -1
        if (code >= 0) taken.add(code)
        if (art < 0) art = (0 until width).filter { it !in taken && frac(st[it].art, it) >= 0.6 }.maxByOrNull { st[it].art } ?: -1
        if (art >= 0) taken.add(art)
        if (name < 0) name = (0 until width).filter { it !in taken && frac(st[it].text, it) >= 0.5 }.maxByOrNull { st[it].textLen } ?: -1
        if (name >= 0) taken.add(name)
        if (qty < 0) {
            val cands = (0 until width).filter { it !in taken && it !in numbering && frac(st[it].qty, it) >= 0.6 }
            qty = cands.firstOrNull { name >= 0 && it > name } ?: cands.firstOrNull() ?: -1
        }

        // 3) с какой строки идут товары
        val start = if (headerRow >= 0) headerRow + 1 else {
            val i = rows.indexOfFirst { r ->
                hasLetter(cell(r, name)) && (qty < 0 || cell(r, qty).replace(',', '.').toDoubleOrNull() != null)
            }
            if (i >= 0) i else 0
        }
        return BatchPick(start, name, qty, code, art)
    }

    /** Строки партии по выбранным столбцам. Итоги и пустые строки пропускаются. */
    fun parse(rows: List<List<String>>, p: BatchPick): List<BatchRow> {
        val out = ArrayList<BatchRow>()
        for (i in p.dataStart until rows.size) {
            val r = rows[i]
            var name = cell(r, p.name).replace('\n', ' ').replace('\t', ' ').trim()
            val code = cell(r, p.code).replace(" ", "")
            val art = cell(r, p.article)
            if (name.isEmpty() && code.isEmpty() && art.isEmpty()) continue
            if (name.isEmpty()) name = "Товар " + code.ifEmpty { art }
            if (TOTAL_WORDS.any { name.lowercase().startsWith(it) }) continue
            val q = if (p.qty >= 0) cell(r, p.qty) else ""
            out.add(BatchRow(name, batchQty(q), code, art))
        }
        return out
    }

    fun letter(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }
}

/** Окно выбора столбцов — когда сами определить название не удалось (или человек хочет поправить). */
@Composable
fun BatchColumnsDialog(
    rows: List<List<String>>,
    initial: BatchPick,
    onCancel: () -> Unit,
    onConfirm: (BatchPick) -> Unit
) {
    val width = rows.maxOfOrNull { it.size } ?: 0
    var header by remember { mutableStateOf(initial.dataStart > 0) }
    var name by remember { mutableIntStateOf(initial.name) }
    var qty by remember { mutableIntStateOf(initial.qty) }
    var code by remember { mutableIntStateOf(initial.code) }
    var art by remember { mutableIntStateOf(initial.article) }
    val start = if (header) 1 else 0
    val sample = rows.drop(start).filter { r -> r.any { it.isNotBlank() } }.take(3)

    fun label(c: Int): String {
        val head = rows.firstOrNull()?.getOrElse(c) { "" }?.trim().orEmpty()
        val ex = rows.drop(start).map { it.getOrElse(c) { "" }.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return BatchColumns.letter(c) + ": " + (if (header && head.isNotEmpty()) head.take(14) + " → " else "") + ex.take(18)
    }
    val options = listOf("— не брать —") + (0 until width).map { label(it) }

    @Composable
    fun Pick(title: String, value: Int, onPick: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
            DropdownField(value = options.getOrElse(value + 1) { options[0] }, options = options) { o -> onPick(options.indexOf(o) - 1) }
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Какие столбцы взять?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Сами определить не удалось. Укажите столбцы: штрих-код — 8–14 цифр (EAN-13 и др.), артикул — 4–7 цифр, количество — 1–3 цифры, название — текст.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = header, onCheckedChange = { header = it })
                    Text("Первая строка — заголовки")
                }
                Pick("Название *", name) { name = it }
                Pick("Количество", qty) { qty = it }
                Pick("Штрих-код", code) { code = it }
                Pick("Артикул", art) { art = it }
                if (sample.isNotEmpty() && name >= 0) {
                    Text("Пример:", style = MaterialTheme.typography.labelMedium)
                    sample.forEach { r ->
                        Text(
                            listOf(name, art, code, qty).filter { it >= 0 }.joinToString(" | ") { r.getOrElse(it) { "" }.trim().take(24) },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = name >= 0, onClick = { onConfirm(BatchPick(start, name, qty, code, art)) }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } }
    )
}
