package ru.storage.app

import android.content.Context
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/*
 * ИИ для работников через Google Apps Script (google-script/AiProxy.gs).
 * Ключ ИИ и модель лежат только в свойствах скрипта и на телефон работника не попадают.
 * Приложение шлёт в скрипт фото, ИМЯ ЗАДАЧИ с параметрами (не текст запроса!) и свой Firebase-токен входа.
 * Тексты запросов (промпты) лежат только в скрипте (google-script/AiPrompts.gs): взломанное приложение не может
 * отправить ИИ произвольный текст, а может лишь запустить одну из известных задач.
 * Скрипт проверяет токен и профиль (active, не заблокирован, нарушений < 2), ограничивает число запросов
 * и только потом собирает запрос и обращается к ИИ.
 * Адрес скрипта (не секрет) лежит в Firestore config/aiProxy: пишет администратор, читают активные работники.
 */
object AiProxy {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("aiproxy", Context.MODE_PRIVATE)

    fun url(ctx: Context): String = sp(ctx).getString("url", "")!!.trim()
    fun ready(ctx: Context): Boolean = url(ctx).isNotEmpty()

    /** Работник забирает адрес скрипта из Firebase. При ошибке сети прежний адрес остаётся. */
    suspend fun pull(ctx: Context) {
        try {
            val d = FirebaseFirestore.getInstance().collection("config").document("aiProxy").get().awaitIt()
            sp(ctx).edit().putString("url", d.getString("url") ?: "").apply()
        } catch (e: Exception) {
        }
    }

    /** Администратор: сохранить адрес скрипта у себя и в Firebase (для работников). */
    fun publish(ctx: Context, url: String) {
        val u = url.trim()
        sp(ctx).edit().putString("url", u).apply()
        FirebaseFirestore.getInstance().collection("config").document("aiProxy")
            .set(hashMapOf<String, Any>("url" to u, "updatedAt" to System.currentTimeMillis()))
    }

    /** Одна задача для ИИ через скрипт: task — имя задачи, params — её параметры. Только не в главном потоке. */
    fun call(ctx: Context, task: String, params: JSONObject, png: ByteArray?, json: Boolean): String {
        val url = url(ctx)
        if (url.isEmpty()) throw RuntimeException("ИИ не настроен: администратор не указал адрес ИИ-скрипта")
        val user = FirebaseAuth.getInstance().currentUser ?: throw RuntimeException("Войдите в аккаунт")
        val token = Tasks.await(user.getIdToken(false), 20, TimeUnit.SECONDS).token
            ?: throw RuntimeException("Не удалось получить токен входа")
        val body = JSONObject().put("token", token).put("task", task).put("params", params).put("json", json)
        if (png != null) {
            val mime = if (png.size > 2 && png[0] == 0xFF.toByte()) "image/jpeg" else "image/png"
            body.put("image", Base64.encodeToString(png, Base64.NO_WRAP)).put("mime", mime)
        }
        val r = post(url, body.toString(), AiCfg.timeoutSec(ctx).coerceIn(5, 600) * 1000)
        if (!r.optBoolean("ok")) throw RuntimeException("ИИ: " + r.optString("error", "ошибка скрипта"))
        return r.getString("text")
    }

    /** POST с ручным переходом по перенаправлениям (Apps Script отвечает на POST кодом 302). */
    private fun post(url: String, body: String, timeoutMs: Int): JSONObject {
        var target = url
        var method = "POST"
        var payload: String? = body
        repeat(6) {
            val c = URL(target).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 20000
                c.readTimeout = timeoutMs
                c.instanceFollowRedirects = false
                c.requestMethod = method
                if (payload != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    c.outputStream.use { it.write(payload!!.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val loc = c.getHeaderField("Location") ?: throw RuntimeException("Перенаправление без адреса (HTTP $code)")
                    target = URL(URL(target), loc).toString()
                    if (code != 307 && code != 308) {
                        method = "GET"
                        payload = null
                    }
                    return@repeat
                }
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                try {
                    return JSONObject(text)
                } catch (e: Exception) {
                    throw RuntimeException("ИИ-скрипт вернул не JSON (HTTP $code): " + text.replace(Regex("\\s+"), " ").take(120))
                }
            } finally {
                c.disconnect()
            }
        }
        throw RuntimeException("Слишком много перенаправлений")
    }
}
