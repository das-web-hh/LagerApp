package ru.storage.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Память камеры «+» на телефоне:
 *  • черновик — этикетки, которые камера уже записала, но ещё не добавлены в список (не пропадают при выходе/вылете);
 *  • удалённые — этикетки, которые вы убрали долгим нажатием: камера их больше не отмечает и не записывает.
 * И то и другое действует DRAFT_HOURS часов (рабочий день), потом забывается само: тот же товар на следующий день снова считается.
 */
object LabelDraft {
    private const val DRAFT_HOURS = 12L
    private const val MAX_AGE = DRAFT_HOURS * 3600_000L

    private fun sp(ctx: Context) = ctx.getSharedPreferences("label_draft", Context.MODE_PRIVATE)

    private fun mode(catalog: Boolean, tag: String = "") = if (tag.isNotEmpty()) tag else if (catalog) "cat" else "inv"

    fun save(ctx: Context, catalog: Boolean, list: List<ScannedLabel>, tag: String = "") {
        try {
            val a = JSONArray()
            // «ИИ…» (ещё читается) не сохраняем: после возврата этикетка снова будет прочитана
            list.filter { it.via != "wait" }.forEach {
                a.put(JSONObject().put("k", it.key).put("b", it.barcode).put("a", it.article).put("n", it.name).put("v", it.via))
            }
            sp(ctx).edit().putString("found_" + mode(catalog, tag), a.toString())
                .putLong("found_at_" + mode(catalog, tag), System.currentTimeMillis()).apply()
        } catch (e: Exception) { }
    }

    fun load(ctx: Context, catalog: Boolean, tag: String = ""): List<ScannedLabel> {
        return try {
            val p = sp(ctx)
            val at = p.getLong("found_at_" + mode(catalog, tag), 0L)
            if (System.currentTimeMillis() - at > MAX_AGE) return emptyList()
            val a = JSONArray(p.getString("found_" + mode(catalog, tag), "[]"))
            List(a.length()) {
                val o = a.getJSONObject(it)
                ScannedLabel(o.optString("k"), o.optString("b"), o.optString("a"), o.optString("n"), o.optString("v"))
            }.filter { it.key.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(ctx: Context, catalog: Boolean, tag: String = "") {
        sp(ctx).edit().remove("found_" + mode(catalog, tag)).remove("found_at_" + mode(catalog, tag)).apply()
    }

    // ---------- удалённые этикетки ----------

    /** ключ товара (StrohLabel.key) → когда удалили */
    fun ignored(ctx: Context): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        try {
            val o = JSONObject(sp(ctx).getString("ignored", "{}"))
            val now = System.currentTimeMillis()
            for (k in o.keys()) {
                val t = o.optLong(k)
                if (now - t <= MAX_AGE) out[k] = t
            }
        } catch (e: Exception) { }
        return out
    }

    fun saveIgnored(ctx: Context, map: Map<String, Long>) {
        try {
            val o = JSONObject()
            map.forEach { (k, t) -> o.put(k, t) }
            sp(ctx).edit().putString("ignored", o.toString()).apply()
        } catch (e: Exception) { }
    }
}
