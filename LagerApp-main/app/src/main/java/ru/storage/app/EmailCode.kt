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
    private val PUBLIC_KEY: String get() = ConnCfg.mail().publicKey
    private val SERVICE_ID: String get() = ConnCfg.mail().serviceId
    private val TEMPLATE_ID: String get() = ConnCfg.mail().templateId
    /**
     * Шаблон для писем администратора. Создайте в EmailJS второй шаблон с переменными
     * {{to_email}} (поле «To Email»), {{to_name}}, {{from_name}}, {{subject}}, {{message}} и вставьте сюда его ID.
     */
    private val MESSAGE_TEMPLATE_ID: String get() = ConnCfg.mail().messageTemplateId
    /** Private Key из EmailJS (Account → General). Нужен, если включено «Use Private Key». Можно оставить пустым и выключить эту галочку в EmailJS. */
    private val PRIVATE_KEY: String get() = ConnCfg.mail().privateKey
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
                if (PRIVATE_KEY.isNotEmpty()) body.put("accessToken", PRIVATE_KEY)
                val conn = URL("https://api.emailjs.com/api/v1.0/email/send").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val errText = if (status in 200..299) "" else try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.trim()?.take(160) ?: ""
                } catch (e: Exception) { "" }
                conn.disconnect()
                if (status in 200..299) {
                    synchronized(this@EmailCode) {
                        email = to; code = c; expires = System.currentTimeMillis() + TTL_MS; tries = 0
                    }
                    null
                } else if (status == 403) {
                    "Не удалось отправить код на Email (ошибка 403). В EmailJS → Account → Security включите «Allow EmailJS API for non-browser applications»." +
                        (if (errText.isNotEmpty()) " Ответ: $errText" else "")
                } else {
                    "Не удалось отправить код на Email (ошибка $status)" + (if (errText.isNotEmpty()) ": $errText" else "")
                }
            } catch (e: Exception) {
                "Нет связи. Не удалось отправить код на Email"
            }
        }
    }

    /** Письмо сотруднику на его Email: обращение по имени, подпись — имя отправителя. null — отправлено. */
    suspend fun sendMessage(toEmail: String, toName: String, fromName: String, subject: String, message: String): String? {
        if (MESSAGE_TEMPLATE_ID.endsWith("REPLACE_ME"))
            return "Не задан шаблон письма: создайте шаблон в EmailJS и впишите его ID в EmailCode.kt"
        return withContext(Dispatchers.IO) {
            try {
                val params = JSONObject()
                    .put("to_email", toEmail).put("email", toEmail)
                    .put("to_name", toName).put("from_name", fromName)
                    .put("subject", subject).put("message", message)
                val body = JSONObject()
                    .put("service_id", SERVICE_ID).put("template_id", MESSAGE_TEMPLATE_ID)
                    .put("user_id", PUBLIC_KEY).put("template_params", params)
                if (PRIVATE_KEY.isNotEmpty()) body.put("accessToken", PRIVATE_KEY)
                val conn = URL("https://api.emailjs.com/api/v1.0/email/send").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                conn.disconnect()
                if (status in 200..299) null else "Не удалось отправить письмо (ошибка $status)"
            } catch (e: Exception) {
                "Нет связи. Письмо не отправлено"
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
