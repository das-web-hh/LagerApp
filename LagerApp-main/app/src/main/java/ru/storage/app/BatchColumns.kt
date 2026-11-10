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
/** done — столбец «Посчитано» (число, написанное ручкой), mark — столбец с галочкой (✓, x, +, да); ask — спросить столбцы у человека. */
class BatchPick(
    val dataStart: Int, val name: Int, val qty: Int, val code: Int, val article: Int,
    val done: Int = -1, val mark: Int = -1, val ask: Boolean = false
)

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
    private const val ROLE_DONE = 6
    private const val ROLE_MARK = 7

    private val NAME_KEYS = listOf("наимен", "назван", "товар", "продук", "номенклат", "описан", "name", "item", "product", "bezeichn", "beschreib", "ware")
    private val QTY_KEYS = listOf("колич", "кол-во", "кол.", "qty", "quantity", "menge", "anzahl", "stück", "stueck", "pcs", "шт", "план")
    private val CODE_KEYS = listOf("штрих", "barcode", "bar code", "ean", "gtin", "шк", "strich")
    private val ART_KEYS = listOf("артикул", "арт", "article", "artikel", "art.", "art-", "art nr", "sku")
    private val DONE_KEYS = listOf("посчит", "найден", "факт", "сверен", "fakt", "counted", "gezählt", "gezaehlt", "tatsächlich", "tatsaechlich")
    private val MARK_KEYS = listOf("галоч", "отметк", "haken", "✓", "✔", "check")
    private val MARK_WORDS = setOf("да", "ja", "yes", "ok", "ок")
    private val BAD_QTY = listOf("цена", "price", "preis", "сумм", "sum", "amount", "итого", "total", "стоим", "вес", "weight", "gewicht")
    private val TOTAL_WORDS = listOf("итого", "всего", "total", "summe", "gesamt")

    private val INT = Regex("\\d+")
    private val DEC = Regex("\\d+[.,]\\d+")

    private fun cell(r: List<String>, i: Int): String = if (i < 0) "" else r.getOrElse(i) { "" }.trim()
    private fun hasLetter(s: String) = s.any { it.isLetter() }

    /** Галочка ручкой: ✓ ✔ ☑ ✅ x х v + да — позиция уже найдена. */
    private fun isMark(raw: String): Boolean {
        val v = raw.replace("\uFE0F", "").trim().lowercase()
        if (v.isEmpty()) return false
        if (v in MARK_WORDS) return true
        return v.length <= 3 && v.all { it in "✓✔☑✅xхv×+√" }
    }
    private fun has(s: String, keys: List<String>) = keys.any { s.contains(it) }

    private fun headerRole(raw: String): Int {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return 0
        if (s == "№" || s == "n" || s == "no" || s == "#" || s.startsWith("№ п") || s == "nr" || s == "nr.") return ROLE_NUM
        return when {
            has(s, CODE_KEYS) -> ROLE_CODE
            has(s, DONE_KEYS) -> ROLE_DONE
            has(s, MARK_KEYS) -> ROLE_MARK
            has(s, NAME_KEYS) -> ROLE_NAME
            has(s, BAD_QTY) -> 0
            has(s, QTY_KEYS) -> ROLE_QTY
            has(s, ART_KEYS) -> ROLE_ART
            else -> 0
        }
    }

    private class Stat {
        var filled = 0; var code = 0; var art = 0; var qty = 0; var text = 0; var textLen = 0L; var marks = 0
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
        var done = fromHeader(ROLE_DONE)
        var mark = fromHeader(ROLE_MARK)

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
            if (isMark(raw)) s.marks++
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
        listOf(name, qty, code, art, done, mark).filter { it >= 0 }.forEach { taken.add(it) }
        // столбец нумерации не берём
        val numbering = (0 until width).filter { hRole[it] == ROLE_NUM || (headerRow < 0 || hRole[it] == 0) && isSequence(st[it].ints) && st[it].qty == st[it].filled }.toSet()

        if (code < 0) code = (0 until width).filter { it !in taken && frac(st[it].code, it) >= 0.6 }.maxByOrNull { st[it].code } ?: -1
        if (code >= 0) taken.add(code)
        if (art < 0) art = (0 until width).filter { it !in taken && frac(st[it].art, it) >= 0.6 }.maxByOrNull { st[it].art } ?: -1
        if (art >= 0) taken.add(art)
        // столбец только с галочками (✓, x, +, да)
        if (mark < 0) mark = (0 until width).firstOrNull { it !in taken && st[it].filled > 0 && st[it].marks == st[it].filled } ?: -1
        if (mark >= 0) taken.add(mark)
        if (name < 0) name = (0 until width).filter { it !in taken && frac(st[it].text, it) >= 0.5 }.maxByOrNull { st[it].textLen } ?: -1
        if (name >= 0) taken.add(name)
        if (qty < 0) {
            val cands = (0 until width).filter { it !in taken && it !in numbering && frac(st[it].qty, it) >= 0.6 }
            qty = cands.firstOrNull { name >= 0 && it > name } ?: cands.firstOrNull() ?: -1
        }
        if (qty >= 0) taken.add(qty)
        // ещё один числовой столбец, который назвать не удалось (количество по плану? посчитано ручкой?) — лучше спросить
        val bad = (0 until width).filter { headerRow >= 0 && has(cell(rows[headerRow], it).lowercase(), BAD_QTY) }.toSet()
        val spare = (0 until width).filter { it !in taken && it !in numbering && it !in bad && frac(st[it].qty, it) >= 0.6 }
        val ask = name < 0 || spare.isNotEmpty()

        // 3) с какой строки идут товары
        val start = if (headerRow >= 0) headerRow + 1 else {
            val i = rows.indexOfFirst { r ->
                hasLetter(cell(r, name)) && (qty < 0 || cell(r, qty).replace(',', '.').toDoubleOrNull() != null)
            }
            if (i >= 0) i else 0
        }
        return BatchPick(start, name, qty, code, art, done, mark, ask)
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
            val plan = batchQty(q)
            // уже найдено: галочка ручкой — вся позиция, либо число из столбца «Посчитано»
            var done = ""
            if (p.mark >= 0 && isMark(cell(r, p.mark))) done = plan
            else if (p.done >= 0) {
                val d = parseQ(cell(r, p.done))
                if (d > 0.0) done = fmtQ(d)
            }
            out.add(BatchRow(name, plan, code, art, done))
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
    var done by remember { mutableIntStateOf(initial.done) }
    var mark by remember { mutableIntStateOf(initial.mark) }
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
                    "Проверьте столбцы (если что-то определилось неверно — поправьте). Штрих-код — 8–14 цифр, артикул — 4–7 цифр, количество — по плану, напечатанное компьютером. «Посчитано» — число, написанное ручкой; «Галочка» — столбец с ✓ / x / +: такие позиции считаются уже найденными.",
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
                Pick("Посчитано", done) { done = it }
                Pick("Галочка", mark) { mark = it }
                if (sample.isNotEmpty() && name >= 0) {
                    Text("Пример:", style = MaterialTheme.typography.labelMedium)
                    sample.forEach { r ->
                        Text(
                            listOf(name, art, code, qty, done, mark).filter { it >= 0 }.joinToString(" | ") { r.getOrElse(it) { "" }.trim().take(24) },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = name >= 0, onClick = { onConfirm(BatchPick(start, name, qty, code, art, done, mark)) }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Отмена") } }
    )
}
