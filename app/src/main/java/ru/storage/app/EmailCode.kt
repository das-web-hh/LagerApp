package ru.storage.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Подтверждение email 6-значным кодом через EmailJS (те же ключи и шаблон, что в index.html).
 * Важно: в кабинете EmailJS → Account → Security должна быть включена галочка
 * «Allow EmailJS API for non-browser applications», иначе письма из приложения не уходят.
 */
object EmailCode {
    private const val PUBLIC_KEY = "eVykpcx5pKB6QOkY_"
    private const val SERVICE_ID = "service_gqzvbbf"
    private const val TEMPLATE_ID = "template_43migsz"
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_TRIES = 5

    private val rnd = SecureRandom()

    private var email = ""
    private var code = ""
    private var expires = 0L
    private var tries = 0

    fun newCode(): String = (100000 + rnd.nextInt(900000)).toString()

    /** Отправляет код на почту. null — отправлено, иначе текст ошибки. */
    suspend fun send(to: String): String? {
        val c = newCode()
        return withContext(Dispatchers.IO) {
            try {
                val params = JSONObject()
                    .put("to_email", to).put("email", to)
                    .put("passcode", c).put("code", c)
                val body = JSONObject()
                    .put("service_id", SERVICE_ID)
                    .put("template_id", TEMPLATE_ID)
                    .put("user_id", PUBLIC_KEY)
                    .put("template_params", params)
                val conn = URL("https://api.emailjs.com/api/v1.0/email/send").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                conn.disconnect()
                if (status in 200..299) {
                    synchronized(this@EmailCode) {
                        email = to; code = c; expires = System.currentTimeMillis() + TTL_MS; tries = 0
                    }
                    null
                } else {
                    "Не удалось отправить код на Email (ошибка $status)"
                }
            } catch (e: Exception) {
                "Нет связи. Не удалось отправить код на Email"
            }
        }
    }

    /** null — код верный (и сразу сгорает), иначе текст ошибки. */
    @Synchronized
    fun check(to: String, input: String): String? {
        if (code.isEmpty() || email != to) return "Сначала получите код"
        if (System.currentTimeMillis() > expires) { code = ""; return "Код устарел — запросите новый" }
        if (tries >= MAX_TRIES) { code = ""; return "Слишком много попыток — запросите новый код" }
        if (input.trim() != code) { tries++; return "Неверный код из Email" }
        code = ""
        return null
    }
}
