package ru.storage.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Порядок данных»: раскладывает данные и файлы по своим местам, чтобы ничего не перемешивалось.
 *
 * Телефон и Firebase: пробелы в карточках, привязка одинаковых товаров по артикулу, очередь отправки без пропавших файлов,
 * лишние локальные фото (на Диске они остаются). Google Диск: файлы по папкам — Товары / Фото, Товары / Фото с сайта,
 * Приёмки, Накладные, B-Ware, Партии — и без дублей с одинаковым именем.
 * Ничего не удаляется из базы и с Диска безвозвратно: дубли на Диске уходят в корзину, лишние локальные фото скачаются снова.
 */
object Organizer {
    /** Префиксы файлов, которые приложение раскладывает по папкам (то же — в Code.gs). */
    val FOLDERS = listOf(
        "PR-S" to "Товары / Фото с сайта",
        "PR-" to "Товары / Фото",
        "RC-" to "Приёмки",
        "IN-" to "Накладные",
        "BW-" to "B-Ware",
        "BATCH" to "Партии"
    )

    private const val KEEP_DAYS = 14L

    suspend fun run(ctx: Context, onStep: (String) -> Unit): List<String> = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val db = ProductDb.get(app)
        val out = ArrayList<String>()

        // 1. карточки товаров: лишние пробелы в названии, артикулах и штрих-коде
        onStep("Проверяю карточки товаров…")
        var fixed = 0
        for (p in db.allProducts()) {
            val c = p.copy(
                name = p.name.trim().replace(Regex("\\s+"), " "),
                article = p.article.trim(), webArticle = p.webArticle.trim(), barcode = p.barcode.trim().replace(" ", ""),
                manufacturer = p.manufacturer.trim()
            )
            if (c != p) { Cloud.saveProduct(db, c, log = false); fixed++ }
        }
        out.add(if (fixed > 0) "Исправлено карточек (пробелы): $fixed" else "Карточки товаров в порядке")

        // 2. один артикул — один товар: названия привязываются друг к другу
        onStep("Привязываю одинаковые товары по артикулу…")
        val linked = ProductGroups.linkByArticle(db)
        out.add(if (linked > 0) "Привязано по артикулу карточек: $linked" else "Новых совпадений артикулов нет")

        // 3. очередь отправки: записи о файлах, которых уже нет на телефоне
        onStep("Проверяю очередь отправки на Диск…")
        var gone = 0
        for (n in db.pendingUploads()) {
            val f = Media.file(app, n)
            if (!f.exists() || f.length() == 0L) { db.removeUpload(n); gone++ }
        }
        if (gone > 0) out.add("Из очереди убрано записей без файла: $gone")

        // 4. лишние локальные фото: ни в одном товаре и приёме, уже отправлены, старше двух недель
        onStep("Ищу лишние файлы на телефоне…")
        val used = HashSet<String>()
        db.allProducts().forEach { used.addAll(it.photos) }
        db.allReceipts().forEach { used.addAll(it.allMedia()) }
        val pending = db.pendingUploads().toSet()
        val limit = System.currentTimeMillis() - KEEP_DAYS * 24 * 3600 * 1000
        var freed = 0
        var bytes = 0L
        Media.dir(app).listFiles()?.forEach { f ->
            val n = f.name
            val ours = (n.startsWith("PR-") || n.startsWith("RC-")) && !n.startsWith("PR-S") && Media.isImage(n)
            if (ours && n !in used && n !in pending && f.lastModified() < limit) {
                bytes += f.length()
                if (f.delete()) freed++
            }
        }
        if (freed > 0) out.add("Удалено лишних фото на телефоне: $freed (${bytes / 1024} КБ); на Диске они сохранены")

        // 5. Google Диск: файлы по папкам, без дублей
        if (Session.isAdmin && DriveCfg.ready(app)) {
            onStep("Раскладываю файлы на Google Диске…")
            try {
                var moved = 0
                var dups = 0
                var folders = 0
                var rounds = 0
                while (rounds++ < 60) {
                    val r = DriveApi.organize(app)
                    moved += r.moved; dups += r.dups; folders = maxOf(folders, r.folders)
                    onStep("Google Диск: разложено файлов $moved, осталось ${r.left}…")
                    if (r.left <= 0 || (r.moved == 0 && r.dups == 0)) break
                }
                out.add("Диск: разложено по папкам файлов $moved, дублей в корзину $dups")
            } catch (e: Exception) {
                out.add("Диск: ${e.message ?: "ошибка"}")
            }
        } else if (!Session.isAdmin) {
            out.add("Диск раскладывает администратор")
        } else {
            out.add("Google Диск не настроен")
        }
        Journal.log("Система", "Наведён порядок данных: " + out.joinToString("; "))
        out
    }
}
