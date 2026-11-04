package ru.storage.app

import android.content.Context

/**
 * Артикул строки: берётся из самой строки (введён вручную), иначе из каталога — по id товара, названию или штрих-коду.
 * Нужен и экрану (подпись у строки), и печати документов.
 */
object ArticleLookup {
    @Volatile
    var app: Context? = null

    fun find(r: ItemRow): String {
        if (r.article.isNotBlank()) return r.article.trim()
        val c = app ?: return ""
        return try {
            val db = ProductDb.get(c)
            ((if (r.productId.isNotEmpty()) db.getProduct(r.productId) else null)
                ?: db.findByName(r.name.trim())
                ?: (if (r.barcode.isNotBlank()) db.findByBarcode(r.barcode.trim()) else null))?.article.orEmpty().trim()
        } catch (e: Exception) {
            ""
        }
    }

    /** Подпись под названием товара в печатной таблице; пусто, если артикула нет. */
    fun html(r: ItemRow): String {
        val a = find(r)
        return if (a.isEmpty()) "" else "<div class='sub'>${tr("article")}: ${bwEsc(a)}</div>"
    }
}
