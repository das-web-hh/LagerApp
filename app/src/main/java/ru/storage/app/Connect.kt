package ru.storage.app

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.json.JSONArray
import org.json.JSONObject

/** Подключение к Firebase (то, что обычно лежит в google-services.json). */
data class FbCfg(
    val projectId: String,
    val appId: String,
    val apiKey: String,
    val bucket: String = "",
    val senderId: String = ""
) {
    val valid: Boolean get() = projectId.isNotBlank() && appId.isNotBlank() && apiKey.isNotBlank()
}

/** Ключи EmailJS: подтверждение email кодом и письма сотрудникам. */
data class MailCfg(
    val publicKey: String,
    val serviceId: String,
    val templateId: String,
    val messageTemplateId: String,
    val privateKey: String
)

/**
 * Подключения приложения: Firebase и EmailJS. По умолчанию используются встроенные значения;
 * администратор может заменить их своими (или загрузить из файла настроек) и вернуться к встроенным.
 * Новое подключение к Firebase вступает в силу после перезапуска приложения.
 */
object ConnCfg {
    private const val FILE_TYPE = "lager-settings"

    @Volatile var appCtx: Context? = null

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("conn", Context.MODE_PRIVATE)

    /** Вызывается из Application: запоминает контекст и подключает Firebase, если админ задал свой. */
    fun init(app: Application) {
        appCtx = app.applicationContext
        val o = firebaseOverride(app) ?: return
        try {
            val cur = try { FirebaseApp.getInstance() } catch (e: Exception) { null }
            if (cur != null && cur.options.projectId == o.projectId && cur.options.applicationId == o.appId &&
                cur.options.apiKey == o.apiKey) return
            cur?.delete()
            FirebaseApp.initializeApp(app, options(o))
        } catch (e: Exception) {
            // не вышло — остаётся встроенное подключение
        }
    }

    private fun options(c: FbCfg): FirebaseOptions {
        val b = FirebaseOptions.Builder()
            .setProjectId(c.projectId.trim()).setApplicationId(c.appId.trim()).setApiKey(c.apiKey.trim())
        if (c.bucket.isNotBlank()) b.setStorageBucket(c.bucket.trim())
        if (c.senderId.isNotBlank()) b.setGcmSenderId(c.senderId.trim())
        return b.build()
    }

    // ---------- Firebase ----------

    fun firebaseOverride(ctx: Context): FbCfg? {
        val p = sp(ctx)
        val c = FbCfg(
            p.getString("fbProject", "") ?: "", p.getString("fbApp", "") ?: "", p.getString("fbKey", "") ?: "",
            p.getString("fbBucket", "") ?: "", p.getString("fbSender", "") ?: ""
        )
        return if (c.valid) c else null
    }

    /** Подключение, с которым приложение работает сейчас (после последнего запуска). */
    fun firebaseActive(): FbCfg? = try {
        val o = FirebaseApp.getInstance().options
        FbCfg(o.projectId ?: "", o.applicationId, o.apiKey, o.storageBucket ?: "", o.gcmSenderId ?: "")
    } catch (e: Exception) {
        null
    }

    fun saveFirebase(ctx: Context, c: FbCfg?) {
        val e = sp(ctx).edit()
        if (c == null || !c.valid) {
            e.remove("fbProject").remove("fbApp").remove("fbKey").remove("fbBucket").remove("fbSender")
        } else {
            e.putString("fbProject", c.projectId.trim()).putString("fbApp", c.appId.trim())
                .putString("fbKey", c.apiKey.trim()).putString("fbBucket", c.bucket.trim())
                .putString("fbSender", c.senderId.trim())
        }
        e.apply()
    }

    /** Разбор содержимого google-services.json. null — не похоже на этот файл. */
    fun parseGoogleServices(text: String): FbCfg? = try {
        val o = JSONObject(text.trim())
        val info = o.getJSONObject("project_info")
        val client = o.getJSONArray("client").getJSONObject(0)
        val c = FbCfg(
            projectId = info.optString("project_id"),
            appId = client.getJSONObject("client_info").optString("mobilesdk_app_id"),
            apiKey = client.getJSONArray("api_key").getJSONObject(0).optString("current_key"),
            bucket = info.optString("storage_bucket"),
            senderId = info.optString("project_number")
        )
        if (c.valid) c else null
    } catch (e: Exception) {
        null
    }

    // ---------- EmailJS ----------

    val DEFAULT_MAIL = MailCfg("eVykpcx5pKB6QOkY_", "service_gqzvbbf", "template_43migsz", "template_REPLACE_ME", "")

    fun mailOverride(ctx: Context): MailCfg? {
        val p = sp(ctx)
        val pk = p.getString("mPublic", "") ?: ""
        val sv = p.getString("mService", "") ?: ""
        val tp = p.getString("mTemplate", "") ?: ""
        if (pk.isBlank() || sv.isBlank() || tp.isBlank()) return null
        return MailCfg(pk, sv, tp, p.getString("mMessage", "") ?: "", p.getString("mPrivate", "") ?: "")
    }

    /** Ключи EmailJS: свои, если заданы, иначе встроенные. */
    fun mail(): MailCfg {
        val c = appCtx ?: return DEFAULT_MAIL
        val o = mailOverride(c) ?: return DEFAULT_MAIL
        return o.copy(messageTemplateId = o.messageTemplateId.ifBlank { DEFAULT_MAIL.messageTemplateId })
    }

    fun saveMail(ctx: Context, c: MailCfg?) {
        val e = sp(ctx).edit()
        if (c == null) {
            e.remove("mPublic").remove("mService").remove("mTemplate").remove("mMessage").remove("mPrivate")
        } else {
            e.putString("mPublic", c.publicKey.trim()).putString("mService", c.serviceId.trim())
                .putString("mTemplate", c.templateId.trim()).putString("mMessage", c.messageTemplateId.trim())
                .putString("mPrivate", c.privateKey.trim())
        }
        e.apply()
    }

    // ---------- признак настоящего входа на этом телефоне ----------

    /** Хотя бы один раз на этом телефоне входили по-настоящему. */
    fun markConnected(ctx: Context) = sp(ctx).edit().putBoolean("ok", true).apply()

    fun everConnected(ctx: Context): Boolean = sp(ctx).getBoolean("ok", false)

    /** Логин администратора из загруженного файла — подсказка для входа после загрузки. */
    fun savedAdminLogin(ctx: Context): String = sp(ctx).getString("adminLogin", "") ?: ""

    // ---------- файл настроек ----------

    /** Все настройки подключения одним JSON-файлом. Внутри секреты (ключи, токен): хранить в надёжном месте. */
    fun exportJson(ctx: Context): String {
        val o = JSONObject().put("type", FILE_TYPE).put("version", 1).put("savedAt", System.currentTimeMillis())
        val adminLogin = Session.user?.takeIf { it.isAdmin }?.login ?: savedAdminLogin(ctx)
        if (adminLogin.isNotBlank()) o.put("adminLogin", adminLogin)
        (firebaseOverride(ctx) ?: firebaseActive())?.let {
            o.put(
                "firebase",
                JSONObject().put("projectId", it.projectId).put("appId", it.appId).put("apiKey", it.apiKey)
                    .put("bucket", it.bucket).put("senderId", it.senderId)
            )
        }
        val m = mailOverride(ctx) ?: DEFAULT_MAIL
        o.put(
            "emailjs",
            JSONObject().put("publicKey", m.publicKey).put("serviceId", m.serviceId).put("templateId", m.templateId)
                .put("messageTemplateId", m.messageTemplateId).put("privateKey", m.privateKey)
        )
        o.put(
            "drive",
            JSONObject().put("url", DriveCfg.url(ctx)).put("folder", DriveCfg.folder(ctx)).put("token", DriveCfg.token(ctx))
        )
        val ai = JSONObject().put("keys", JSONArray(AiKeys.toJson(AiKeys.list(ctx))))
            .put("active", AiKeys.activeId(ctx))
            .put("timeout", AiCfg.timeoutSec(ctx)).put("attempts", AiCfg.attempts(ctx))
        o.put("ai", ai)
        return o.toString(2)
    }

    class Loaded(val json: JSONObject) {
        val adminLogin: String get() = json.optString("adminLogin")
        val hasFirebase: Boolean get() = json.optJSONObject("firebase") != null
    }

    /** Разбор файла настроек. Возвращает ошибку текстом либо Loaded. */
    fun parse(text: String): Pair<Loaded?, String?> {
        return try {
            val o = JSONObject(text.trim())
            if (o.optString("type") != FILE_TYPE) Pair(null, "Это не файл настроек приложения")
            else Pair(Loaded(o), null)
        } catch (e: Exception) {
            Pair(null, "Файл повреждён или это не файл настроек")
        }
    }

    /** Применяет загруженные настройки. Подключение к Firebase вступит в силу после перезапуска. */
    fun apply(ctx: Context, l: Loaded) {
        val o = l.json
        o.optJSONObject("firebase")?.let {
            val c = FbCfg(
                it.optString("projectId"), it.optString("appId"), it.optString("apiKey"),
                it.optString("bucket"), it.optString("senderId")
            )
            if (c.valid) saveFirebase(ctx, c)
        }
        o.optJSONObject("emailjs")?.let {
            val c = MailCfg(
                it.optString("publicKey"), it.optString("serviceId"), it.optString("templateId"),
                it.optString("messageTemplateId"), it.optString("privateKey")
            )
            if (c.publicKey.isNotBlank() && c.serviceId.isNotBlank() && c.templateId.isNotBlank()) saveMail(ctx, c)
        }
        o.optJSONObject("drive")?.let { DriveCfg.save(ctx, it.optString("url"), it.optString("folder"), it.optString("token")) }
        o.optJSONObject("ai")?.let { ai ->
            val keys = AiKeys.fromJson(ai.optJSONArray("keys")?.toString())
            if (keys.isNotEmpty()) AiKeys.replaceAll(ctx, keys, ai.optString("active"))
            AiCfg.saveCommon(ctx, ai.optInt("timeout", AiCfg.timeoutSec(ctx)), ai.optInt("attempts", AiCfg.attempts(ctx)))
        }
        sp(ctx).edit().putString("adminLogin", l.adminLogin).apply()
    }
}
