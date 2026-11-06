package ru.storage.app

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed class PinResult {
    class Ok(val login: String, val password: String) : PinResult()
    class Wrong(val left: Int) : PinResult()
    object Locked : PinResult()
}

fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Быстрый вход на этом телефоне: пароль хранится зашифрованным ключом Android Keystore (ключ не покидает телефон).
 * Открыть его можно отпечатком / лицом (BiometricPrompt) или PIN-кодом из 4 цифр (5 ошибок — PIN стирается).
 * Само приложение при этом входит обычным путём: логин + пароль проверяются в Firebase, поэтому блокировка
 * администратором и смена пароля продолжают работать.
 */
object LocalAuth {
    private const val ALIAS = "lager_local_pw"
    private const val MAX_FAILS = 5

    /** Автозапрос отпечатка показываем один раз за запуск приложения, после «Выйти» — только кнопка. */
    @Volatile var autoPrompted = false

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("local_auth", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private fun sha(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Сохраняет логин и пароль (зашифрованно). Флаги отпечатка и PIN не трогает. */
    fun saveCredentials(ctx: Context, login: String, password: String): Boolean = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(password.toByteArray(Charsets.UTF_8))
        sp(ctx).edit().putString("login", login).putString("ct", b64(ct)).putString("iv", b64(c.iv)).apply()
        true
    } catch (e: Exception) {
        false
    }

    fun savedLogin(ctx: Context): String? = sp(ctx).getString("login", null)?.takeIf { it.isNotEmpty() }

    private fun password(ctx: Context): String? = try {
        val p = sp(ctx)
        val ct = p.getString("ct", null)
        val iv = p.getString("iv", null)
        if (ct == null || iv == null) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, unb64(iv)))
            String(c.doFinal(unb64(ct)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    fun credentials(ctx: Context): Pair<String, String>? {
        val l = savedLogin(ctx) ?: return null
        val p = password(ctx) ?: return null
        return l to p
    }

    // ---------- отпечаток / лицо ----------

    fun canBiometric(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS

    fun bioEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("bio", false) && savedLogin(ctx) != null

    fun setBio(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("bio", on).apply()
        cleanupIfEmpty(ctx)
    }

    /** onError(null) — человек сам отменил, ничего показывать не нужно. */
    fun authenticate(activity: FragmentActivity, onOk: () -> Unit, onError: (String?) -> Unit) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) onError(null) else onError(errString.toString())
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Вход в Warehouse")
            .setSubtitle("Приложите палец или посмотрите в камеру")
            .setNegativeButtonText("Отмена")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()
        prompt.authenticate(info)
    }

    // ---------- PIN ----------

    fun pinEnabled(ctx: Context): Boolean = sp(ctx).contains("pinHash") && savedLogin(ctx) != null

    fun setPin(ctx: Context, pin: String) {
        val salt = b64(ByteArray(16).also { SecureRandom().nextBytes(it) })
        sp(ctx).edit().putString("pinSalt", salt).putString("pinHash", sha(salt, pin)).putInt("fails", 0).apply()
    }

    fun removePin(ctx: Context) {
        sp(ctx).edit().remove("pinSalt").remove("pinHash").remove("fails").apply()
        cleanupIfEmpty(ctx)
    }

    fun checkPin(ctx: Context, pin: String): PinResult {
        val p = sp(ctx)
        val salt = p.getString("pinSalt", null)
        val hash = p.getString("pinHash", null)
        if (salt == null || hash == null) return PinResult.Locked
        if (sha(salt, pin) == hash) {
            val creds = credentials(ctx)
            if (creds == null) { clearAll(ctx); return PinResult.Locked }
            p.edit().putInt("fails", 0).apply()
            return PinResult.Ok(creds.first, creds.second)
        }
        val fails = p.getInt("fails", 0) + 1
        if (fails >= MAX_FAILS) {
            clearAll(ctx)
            return PinResult.Locked
        }
        p.edit().putInt("fails", fails).apply()
        return PinResult.Wrong(MAX_FAILS - fails)
    }

    // ---------- общее ----------

    /** Пароль сменился — обновляем сохранённый, чтобы PIN и отпечаток продолжили работать. */
    fun refreshPassword(ctx: Context, login: String, newPassword: String) {
        if (savedLogin(ctx) == login) saveCredentials(ctx, login, newPassword)
    }

    /** Удаляет всё: пароль, PIN, флаг отпечатка. */
    fun clearAll(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }

    private fun cleanupIfEmpty(ctx: Context) {
        if (!sp(ctx).getBoolean("bio", false) && !sp(ctx).contains("pinHash")) clearAll(ctx)
    }
}
