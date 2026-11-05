package ru.storage.app

import android.content.Context

/**
 * Данные строки для экрана и печати: артикул, штрих-код, вес/объём.
 * Берутся из самой строки (введены вручную), иначе из каталога — по id товара, названию или штрих-коду.
 * Если один и тот же товар записан в каталоге несколькими карточками (в одной артикул, в другой штрих-код),
 * недостающее берётся из любой карточки группы: печатается всё, что известно о товаре.
 */
object ArticleLookup {
    @Volatile
    var app: Context? = null

    class Info(val article: String, val barcode: String, val weight: String) {
        companion object { val EMPTY = Info("", "", "") }
    }

    private fun weightOf(p: Product): String =
        if (p.weight.isBlank()) "" else (p.weight.trim() + " " + p.unit.trim()).trim()

    /** Вызывать не в главном потоке, если строк много (обращения к базе). */
    fun info(db: ProductDb, r: ItemRow): Info {
        val own = r.article.trim()
        val ownCode = r.barcode.trim()
        return try {
            val p = (if (r.productId.isNotEmpty()) db.getProduct(r.productId) else null)
                ?: db.findByName(r.name.trim())
                ?: (if (ownCode.isNotEmpty()) db.findByBarcode(ownCode) else null)
            // карточка строки — первой, затем остальные названия того же товара
            val cards = if (p == null) emptyList() else listOf(p) + ProductGroups.membersOf(db, p).filter { it.id != p.id }
            Info(
                article = own.ifEmpty { cards.firstOrNull { it.article.isNotBlank() }?.article.orEmpty().trim() },
                barcode = ownCode.ifEmpty { cards.firstOrNull { it.barcode.isNotBlank() }?.barcode.orEmpty().trim() },
                weight = cards.map { weightOf(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
            )
        } catch (e: Exception) {
            Info(own, ownCode, "")
        }
    }

    private fun info(r: ItemRow): Info {
        val c = app ?: return Info(r.article.trim(), r.barcode.trim(), "")
        return info(ProductDb.get(c), r)
    }

    fun find(r: ItemRow): String {
        if (r.article.isNotBlank()) return r.article.trim()
        return info(r).article
    }

    fun barcode(r: ItemRow): String = info(r).barcode

    fun weight(r: ItemRow): String = info(r).weight

    /** Подпись под названием: артикул, штрих-код, вес/объём (что известно). */
    fun subHtml(article: String, barcode: String, weight: String): String {
        val parts = listOfNotNull(
            article.takeIf { it.isNotBlank() }?.let { "${tr("article")}: ${bwEsc(it)}" },
            barcode.takeIf { it.isNotBlank() }?.let { "EAN: ${bwEsc(it)}" },
            weight.takeIf { it.isNotBlank() }?.let { bwEsc(it) }
        )
        return if (parts.isEmpty()) "" else "<div class='sub'>${parts.joinToString(" · ")}</div>"
    }

    /** Подпись под названием товара в печатной таблице; пусто, если ничего нет. */
    fun html(r: ItemRow): String {
        val i = info(r)
        return subHtml(i.article, i.barcode, i.weight)
    }
}
