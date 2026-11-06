package ru.storage.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Черновики партий «Приём по имени» / «Автоприём» на телефоне (filesDir/drafts).
 * Для каждой партии: <id>.json — список, количества, брак, штрих-коды, шаг; <id>/<имя файла> — копия исходной накладной
 * (кэш приложения система может очистить, а накладная нужна при сохранении).
 */
object AutoDraft {
    private const val MAX_COPY = 60L * 1024 * 1024

    private fun dir(ctx: Context) = File(ctx.filesDir, "drafts").also { it.mkdirs() }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    @Synchronized
    fun write(ctx: Context, id: String, file: IncomingFile, json: String) {
        val d = dir(ctx)
        val tmp = File(d, "$id.json.tmp")
        tmp.writeText(json)
        val target = File(d, "$id.json")
        if (!tmp.renameTo(target)) {
            target.writeText(json)
            tmp.delete()
        }
        val copy = File(File(d, id), safe(file.name))
        val src = File(file.path)
        if (!copy.exists() && src.exists() && src.length() <= MAX_COPY && src.absolutePath != copy.absolutePath) {
            try {
                copy.parentFile?.mkdirs()
                src.copyTo(copy, overwrite = true)
            } catch (e: Exception) {
            }
        }
    }

    @Synchronized
    fun delete(ctx: Context, id: String) {
        val d = dir(ctx)
        File(d, "$id.json").delete()
        File(d, "$id.json.tmp").delete()
        File(d, id).deleteRecursively()
    }

    /** Путь к копии накладной из черновика (null — копии нет). */
    fun copyPath(ctx: Context, id: String, name: String): String? =
        File(File(dir(ctx), id), safe(name)).takeIf { it.exists() }?.path

    @Synchronized
    fun readAll(ctx: Context): List<JSONObject> =
        (dir(ctx).listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".json") }
            .mapNotNull {
                try {
                    JSONObject(it.readText())
                } catch (e: Exception) {
                    null
                }
            }
}
