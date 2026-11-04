package ru.storage.app

import java.util.Locale
import kotlin.math.abs

/**
 * Поиск «того же товара» под другой записью названия: совпадают вес/объём одной упаковки, вид упаковки
 * и название (без производителя, веса и слов-упаковок) — полностью или с небольшими отличиями.
 * Производитель/отправитель не учитывается: тот же товар от другой фирмы получает тот же штрих-код.
 */
object ProductMatch {
    /** Не меньше такой похожести названий (при совпавшем весе и упаковке) считаем товар тем же. */
    const val THRESHOLD = 0.7

    class Sig(val tokens: List<String>, val size: String?, val pack: String)

    private val SIZE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(kg|ml|mg|g|lt|ltr|liter|l|t|кг|мл|мг|г|л|т)(?![\\p{L}])", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
    private val PACKWORDS = setOf(
        "sack", "säcke", "sacke", "sak", "eimer", "flasche", "fla", "bottle", "dose", "tin", "kanister", "kan",
        "karton", "beutel", "tüte", "tute", "ballen", "tube", "tubus", "packung", "pack"
    )

    /** Вес/объём одной упаковки в базовых единицах (граммы, миллилитры, штуки); null — размера нет. */
    private fun canonSize(size: String, unitRu: String): String? {
        val v = size.replace(',', '.').toDoubleOrNull() ?: return null
        val (k, mul) = when (unitRu.trim().lowercase()) {
            "кг" -> "m" to 1000.0
            "г" -> "m" to 1.0
            "т" -> "m" to 1_000_000.0
            "л" -> "v" to 1000.0
            "мл" -> "v" to 1.0
            "шт" -> "p" to 1.0
            else -> return null
        }
        return k + String.format(Locale.ROOT, "%.3f", v * mul)
    }

    private fun tokens(name: String, manufacturer: String): List<String> {
        val man = manufacturer.trim().lowercase()
        return SIZE.replace(name.lowercase(), " ").split(SPLIT)
            .filter { it.length >= 2 && it !in PACKWORDS && it != man }
    }

    /** Подпись строки накладной; parts — разбор названия (если уже есть), иначе разбираем на телефоне. */
    fun sigOf(name: String, parts: ProductParts?): Sig {
        val p = parts ?: ProductParse.parseLocal(listOf(name)).firstOrNull()
        return Sig(
            tokens = tokens(name, p?.manufacturer ?: ""),
            size = if (p == null) null else canonSize(p.size, p.unit),
            pack = p?.pack ?: ""
        )
    }

    /** Подпись карточки каталога: вес/единица/упаковка из полей, а если они пустые — из названия. */
    fun sigOfProduct(p: Product): Sig {
        val local = ProductParse.parseLocal(listOf(p.name)).firstOrNull()
        val hasFields = p.weight.isNotBlank() && p.unit.isNotBlank()
        val size = if (hasFields) canonSize(p.weight, p.unit) else local?.let { canonSize(it.size, it.unit) }
        val pack = p.pack.ifBlank { local?.pack ?: "" }
        return Sig(tokens(p.name, p.manufacturer.ifBlank { local?.manufacturer ?: "" }), size, pack)
    }

    private fun lev1(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 5 || b.length < 5 || abs(a.length - b.length) > 1) return false
        // расстояние Левенштейна не больше 1
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return when {
            a.length == b.length -> a.substring(i + 1) == b.substring(i + 1)
            a.length > b.length -> a.substring(i + 1) == b.substring(i)
            else -> a.substring(i) == b.substring(i + 1)
        }
    }

    private fun nameSim(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val pool = b.toMutableList()
        var hit = 0
        for (t in a) {
            val j = pool.indexOfFirst { lev1(t, it) }
            if (j >= 0) { hit++; pool.removeAt(j) }
        }
        return hit.toDouble() / (a.size + b.size - hit)
    }

    /** 0 — не тот же товар; иначе похожесть названий (1.0 — одинаковые). */
    fun score(a: Sig, b: Sig): Double {
        if (a.size != b.size) return 0.0 // вес/объём упаковки должен совпасть (оба без размера тоже подходит)
        if (a.pack.isNotEmpty() && b.pack.isNotEmpty() && a.pack != b.pack) return 0.0
        val sim = nameSim(a.tokens, b.tokens)
        // без размера в названиях достаточно только почти одинаковых названий
        val need = if (a.size == null) 0.9 else THRESHOLD
        return if (sim >= need) sim else 0.0
    }

    fun same(a: Sig, b: Sig): Boolean = score(a, b) > 0.0

    /** Лучший товар каталога; если два разных кода почти одинаково подходят — ничего (не рискуем). */
    fun best(row: Sig, catalog: List<Pair<Product, Sig>>): Product? {
        var top: Product? = null
        var topScore = 0.0
        var rival = false
        for ((p, s) in catalog) {
            val sc = score(row, s)
            if (sc <= 0.0) continue
            if (sc > topScore + 0.05) { top = p; topScore = sc; rival = false }
            else if (sc >= topScore - 0.05 && top != null && !sameBarcode(top.barcode, p.barcode)) rival = true
        }
        return if (rival) null else top
    }
}
