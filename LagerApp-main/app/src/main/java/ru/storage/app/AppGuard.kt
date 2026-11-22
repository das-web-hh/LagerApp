package ru.storage.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/*
 * Защита приложения:
 *  1) обязательный PIN (цифровая клавиатура) у обычных пользователей, по желанию графический ключ и биометрия / пароль телефона;
 *  2) блокировка после 30 минут без действий (и при каждом холодном запуске);
 *  3) информирование о работе с данными (две галочки) — до первого входа в главное окно;
 *  4) доступ только в рабочее время (по серверному времени) и не дальше N км от рабочей точки (задаёт администратор).
 * Администратор от пунктов 3 и 4 освобождён; PIN для него необязателен.
 */

object AppGuard {
    /** Профиль → «Защита приложения» поднимает флаг: поверх экрана открывается установка PIN / ключа. */
    var setupRequest by mutableStateOf(false)
    var resumeTick by mutableIntStateOf(0)
    /** true, пока поверх приложения закрывающий экран (блокировка или «доступ закрыт»): сканер не должен принимать ввод. */
    @Volatile var blocked = false

    fun onResume(ctx: Context) {
        val u = Session.user
        if (u != null && AppLock.hasLock(ctx, u.login) && AppLock.idleExpired()) AppLock.locked = true
        resumeTick++
    }
}

sealed class LockResult {
    object Ok : LockResult()
    class Fail(val text: String) : LockResult()
    /** Слишком много ошибок: нужен полный вход по Email и паролю. */
    object Dead : LockResult()
}

object AppLock {
    const val IDLE_MS = 30 * 60_000L
    private const val PAUSE_EVERY = 5
    private const val MAX_FAILS = 15

    /** Приложение закрыто экраном блокировки. После холодного запуска — всегда закрыто. */
    var locked by mutableStateOf(true)

    @Volatile private var lastActive = SystemClock.elapsedRealtime()

    fun touch() { lastActive = SystemClock.elapsedRealtime() }
    fun idleExpired(): Boolean = SystemClock.elapsedRealtime() - lastActive >= IDLE_MS
    fun unlockedNow() { touch(); locked = false }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    private fun k(login: String) = login.trim().lowercase()

    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)

    private fun hash(secret: String, salt: String): String {
        val spec = javax.crypto.spec.PBEKeySpec(secret.toCharArray(), salt.toByteArray(Charsets.UTF_8), 60_000, 256)
        val raw = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return raw.joinToString("") { "%02x".format(it) }
    }

    private fun newSalt(): String = b64(ByteArray(16).also { SecureRandom().nextBytes(it) })

    // ---------- что установлено ----------
    fun hasPin(ctx: Context, login: String) = sp(ctx).contains("pin_${k(login)}")
    fun hasPattern(ctx: Context, login: String) = sp(ctx).contains("pat_${k(login)}")
    fun hasLock(ctx: Context, login: String) = hasPin(ctx, login) || hasPattern(ctx, login)
    fun bioOn(ctx: Context, login: String) = sp(ctx).getBoolean("bio_${k(login)}", false)
    fun pinLen(ctx: Context, login: String) = sp(ctx).getInt("len_${k(login)}", 0)

    fun setPin(ctx: Context, login: String, pin: String) {
        val key = k(login)
        val salt = newSalt()
        sp(ctx).edit().putString("pinsalt_$key", salt).putString("pin_$key", hash(pin, salt))
            .putInt("len_$key", pin.length).putInt("fails_$key", 0).putLong("until_$key", 0L).apply()
    }

    fun setPattern(ctx: Context, login: String, pattern: String) {
        val key = k(login)
        val salt = newSalt()
        sp(ctx).edit().putString("patsalt_$key", salt).putString("pat_$key", hash(pattern, salt)).apply()
    }

    fun removePattern(ctx: Context, login: String) {
        val key = k(login)
        sp(ctx).edit().remove("patsalt_$key").remove("pat_$key").apply()
    }

    fun setBio(ctx: Context, login: String, on: Boolean) {
        sp(ctx).edit().putBoolean("bio_${k(login)}", on).apply()
    }

    fun clear(ctx: Context, login: String) {
        val key = k(login)
        sp(ctx).edit().remove("pinsalt_$key").remove("pin_$key").remove("len_$key").remove("patsalt_$key")
            .remove("pat_$key").remove("bio_$key").remove("fails_$key").remove("until_$key").apply()
    }

    // ---------- проверка ----------
    private fun verify(ctx: Context, login: String, saltKey: String, hashKey: String, secret: String): LockResult {
        val key = k(login)
        val p = sp(ctx)
        val now = System.currentTimeMillis()
        val until = p.getLong("until_$key", 0L)
        if (now < until) return LockResult.Fail("Подождите ${(until - now) / 1000 + 1} с и повторите")
        val salt = p.getString("$saltKey$key", null)
        val stored = p.getString("$hashKey$key", null)
        if (salt == null || stored == null) return LockResult.Fail("Код не установлен")
        val ok = MessageDigest.isEqual(hash(secret, salt).toByteArray(), stored.toByteArray())
        if (ok) {
            p.edit().putInt("fails_$key", 0).putLong("until_$key", 0L).apply()
            return LockResult.Ok
        }
        val fails = p.getInt("fails_$key", 0) + 1
        if (fails >= MAX_FAILS) return LockResult.Dead
        p.edit().putInt("fails_$key", fails).apply()
        if (fails % PAUSE_EVERY == 0) {
            val pause = 30_000L * (1L shl (fails / PAUSE_EVERY - 1))
            p.edit().putLong("until_$key", now + pause).apply()
            return LockResult.Fail("Слишком много ошибок. Пауза ${pause / 1000} с")
        }
        return LockResult.Fail("Неверно. Попыток до паузы: ${PAUSE_EVERY - fails % PAUSE_EVERY}")
    }

    fun checkPin(ctx: Context, login: String, pin: String) = verify(ctx, login, "pinsalt_", "pin_", pin)
    fun checkPattern(ctx: Context, login: String, pattern: String) = verify(ctx, login, "patsalt_", "pat_", pattern)

    // ---------- биометрия и пароль телефона ----------
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canSystemAuth(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    /** Отпечаток / лицо или системный пароль (PIN, ключ телефона). onFail(null) — человек сам отменил. */
    fun systemPrompt(activity: FragmentActivity, onOk: () -> Unit, onFail: (String?) -> Unit) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) onFail(null) else onFail(errString.toString())
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Разблокировать Warehouse")
            .setSubtitle("Отпечаток, лицо или пароль телефона")
            .setAllowedAuthenticators(AUTH)
            .build()
        prompt.authenticate(info)
    }
}

// ============================================================ информирование (согласие)

object Consent {
    /** Поднимите номер, если текст ниже изменился: все работники увидят его заново. */
    const val VERSION = 1

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("consent", Context.MODE_PRIVATE)

    fun accepted(ctx: Context, login: String): Boolean = sp(ctx).getInt("v_${login.lowercase()}", 0) >= VERSION

    fun accept(ctx: Context, login: String) {
        sp(ctx).edit().putInt("v_${login.lowercase()}", VERSION).putLong("t_${login.lowercase()}", System.currentTimeMillis()).apply()
        // отметка у работодателя: когда и с какой версией текста ознакомился (серверное время)
        try {
            FirebaseFirestore.getInstance().collection("users").document(login)
                .update(mapOf("consentAt" to FieldValue.serverTimestamp(), "consentVer" to VERSION.toLong()))
        } catch (e: Exception) {
        }
        try { Journal.log("Система", "Работник ознакомлен с правилами работы с данными (версия $VERSION)") } catch (e: Exception) { }
    }

    /*
     * ВАЖНО: черновик. Перед использованием покажите текст юристу / специалисту по защите данных:
     * точные формулировки зависят от страны и от трудового договора (в Германии при слежении за местом работника
     * обычно нужно согласование с Betriebsrat и запись в Verarbeitungsverzeichnis).
     */
    val sections: List<Pair<String, String>> = listOf(
        "1. Назначение приложения и характер данных" to
            "Приложение предназначено для учёта товара на складе: приём, партии, накладные, фото, остатки, данные поставщиков " +
            "и рабочая переписка. Эти сведения являются служебной (коммерческой) информацией работодателя; часть из них " +
            "может содержать персональные данные работников и контрагентов.",
        "2. Конфиденциальность" to
            "Вы обязуетесь не разглашать данные из приложения: не копировать, не пересылать, не фотографировать экран, " +
            "не выгружать и не передавать их третьим лицам (в том числе родственникам, знакомым, конкурентам, в мессенджеры " +
            "и облачные сервисы) без письменного разрешения работодателя. Обязательство действует и после прекращения работы.",
        "3. Запрещённое использование" to
            "Запрещено использовать данные и доступ к приложению в личных, коммерческих или иных целях, не связанных с " +
            "вашей работой, и в целях, причиняющих вред работодателю, коллегам или третьим лицам: хищение или сокрытие товара, " +
            "подмена, искажение или удаление записей, мошенничество, передача данных конкурентам или злоумышленникам, " +
            "попытки обойти защиту приложения, подмену времени или местоположения.",
        "4. Учётная запись и PIN-код" to
            "Доступ персональный. Храните PIN-код, графический ключ и пароль в тайне, не передавайте телефон с открытым " +
            "приложением другим лицам. Все действия под вашей учётной записью считаются совершёнными вами. " +
            "При утере телефона или подозрении на чужой доступ немедленно сообщите администратору.",
        "5. Журналирование действий" to
            "В приложении записываются вход и выход, приёмы, изменения и удаления записей, время и автор действия, " +
            "а также отметка о том, что вы ознакомились с этим текстом (дата и версия). Записи используются для учёта, " +
            "контроля сохранности товара и расследования нарушений.",
        "6. Рабочее время и геолокация" to
            "Приложение работает только в рабочее время и на рабочем месте. Для этого при открытии и во время работы " +
            "проверяются: серверное время и ваше местоположение (расстояние до рабочей точки, заданной администратором; " +
            "по умолчанию — не дальше 5 км). Местоположение используется только для этой проверки. Точные координаты " +
            "не передаются и не хранятся на сервере; сохраняется лишь результат («доступ разрешён / закрыт»), если это " +
            "предусмотрено настройками. Вне рабочего времени и вне зоны доступ закрыт.",
        "7. Правовая основа и ваши права" to
            "Данные обрабатываются в рамках трудовых отношений для учёта склада и защиты имущества работодателя, " +
            "в соответствии с применимым законодательством о защите персональных данных (в т.ч. Регламентом (ЕС) 2016/679 " +
            "GDPR/DSGVO и Bundesdatenschutzgesetz — если применимо) и законодательством о коммерческой тайне. " +
            "Вы вправе запросить сведения об обработке своих данных, их исправление и ограничение, а также отозвать согласие " +
            "на проверку геолокации, обратившись к администратору; отзыв согласия на проверку места может означать " +
            "невозможность работы с приложением.",
        "8. Ответственность" to
            "Нарушение этих правил может повлечь дисциплинарную, материальную, гражданско-правовую, а при наличии признаков " +
            "преступления — административную или уголовную ответственность в соответствии с законодательством, " +
            "а также прекращение доступа к приложению."
    )
}

// ============================================================ общие элементы ввода

@Composable
private fun PinField(value: String, label: String, onChange: (String) -> Unit, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = { n -> onChange(n.filter { it.isDigit() }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Графический ключ 3×3. Возвращает строку вида «0-1-4-7». Ключ короче 4 точек не принимается снаружи. */
@Composable
fun PatternPad(error: Boolean, onDone: (String) -> Unit) {
    val done by rememberUpdatedState(onDone)
    val scope = rememberCoroutineScope()
    var box by remember { mutableStateOf(IntSize.Zero) }
    val picked = remember { mutableStateListOf<Int>() }
    var cur by remember { mutableStateOf<Offset?>(null) }
    val primary = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error
    val idle = MaterialTheme.colorScheme.outline

    fun center(i: Int): Offset {
        val w = box.width / 3f
        val h = box.height / 3f
        return Offset((i % 3 + 0.5f) * w, (i / 3 + 0.5f) * h)
    }

    fun hit(p: Offset): Int {
        val r = box.width / 3f * 0.28f
        for (i in 0..8) if ((center(i) - p).getDistance() < r) return i
        return -1
    }

    fun finish() {
        cur = null
        if (picked.isNotEmpty()) {
            done(picked.joinToString("-"))
            scope.launch {
                delay(450)
                picked.clear()
            }
        }
    }

    Canvas(
        Modifier.size(260.dp).onSizeChanged { box = it }.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { o ->
                    picked.clear()
                    cur = o
                    val h = hit(o)
                    if (h >= 0) picked.add(h)
                },
                onDrag = { change, _ ->
                    change.consume()
                    cur = change.position
                    val h = hit(change.position)
                    if (h >= 0 && h !in picked) picked.add(h)
                },
                onDragEnd = { finish() },
                onDragCancel = { finish() }
            )
        }
    ) {
        val color = if (error) bad else primary
        for (j in 1 until picked.size) {
            drawLine(color, center(picked[j - 1]), center(picked[j]), strokeWidth = 6.dp.toPx())
        }
        val c = cur
        if (c != null && picked.isNotEmpty()) drawLine(color, center(picked.last()), c, strokeWidth = 6.dp.toPx())
        for (i in 0..8) {
            val on = i in picked
            drawCircle(if (on) color else idle, radius = if (on) 14.dp.toPx() else 9.dp.toPx(), center = center(i))
        }
    }
}

// ============================================================ установка защиты

@Composable
fun SetupLockScreen(user: AppUser, cancelable: Boolean, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val login = user.login
    var stage by remember { mutableIntStateOf(0) } // 0 PIN · 1 дополнительно · 2 ключ · 3 повтор ключа
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var firstPattern by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val hasPat = remember(tick) { AppLock.hasPattern(ctx, login) }
    val bio = remember(tick) { AppLock.bioOn(ctx, login) }
    val canSys = remember { AppLock.canSystemAuth(ctx) }

    fun finish() {
        AppLock.unlockedNow()
        AppGuard.setupRequest = false
        onDone()
    }

    BackHandler {
        when {
            stage >= 2 -> { stage = 1; msg = "" }
            stage == 0 && cancelable -> { AppGuard.setupRequest = false; onDone() }
            else -> { }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("🔐", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        when (stage) {
            0 -> {
                Text("Защита приложения", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Придумайте PIN-код из 4–8 цифр. Без него приложение не откроется. " +
                        "Он потребуется после 30 минут без действий и при каждом запуске.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                PinField(pin, "PIN-код", { pin = it; msg = "" })
                Spacer(Modifier.height(8.dp))
                PinField(pin2, "Повторите PIN-код", { pin2 = it; msg = "" })
                if (msg.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val weak = pin.all { it == pin[0] } || "0123456789".contains(pin) || "9876543210".contains(pin)
                        when {
                            pin.length < 4 -> msg = "Минимум 4 цифры"
                            pin != pin2 -> msg = "PIN-коды не совпадают"
                            weak -> msg = "Слишком простой PIN (1234, 0000…). Придумайте другой"
                            else -> {
                                AppLock.setPin(ctx, login, pin)
                                pin = ""; pin2 = ""; msg = ""
                                stage = 1; tick++
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Продолжить") }
                if (cancelable) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { AppGuard.setupRequest = false; onDone() }) { Text("Отмена") }
                }
            }
            1 -> {
                Text("PIN-код установлен", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Дополнительно (по желанию) можно открывать приложение графическим ключом, отпечатком или лицом. " +
                        "Пароль телефона тоже всегда подойдёт.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                if (hasPat) {
                    Text("Графический ключ установлен")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { stage = 2; msg = "" }, modifier = Modifier.weight(1f)) { Text("Изменить") }
                        OutlinedButton(
                            onClick = { AppLock.removePattern(ctx, login); tick++ },
                            modifier = Modifier.weight(1f)
                        ) { Text("Убрать") }
                    }
                } else {
                    OutlinedButton(onClick = { stage = 2; msg = "" }, modifier = Modifier.fillMaxWidth()) {
                        Text("Добавить графический ключ")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Отпечаток / лицо")
                        if (!canSys) Text(
                            "На телефоне не настроена блокировка экрана",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = bio, enabled = canSys,
                        onCheckedChange = { on ->
                            if (!on) { AppLock.setBio(ctx, login, false); tick++ }
                            else {
                                val act = ctx.findFragmentActivity()
                                if (act != null) AppLock.systemPrompt(act, {
                                    AppLock.setBio(ctx, login, true); tick++
                                }, { e -> if (e != null) msg = e })
                            }
                        }
                    )
                }
                if (msg.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
            }
            else -> {
                Text(
                    if (stage == 2) "Нарисуйте графический ключ" else "Повторите графический ключ",
                    fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text("Соедините минимум 4 точки", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                PatternPad(error = msg.isNotEmpty()) { p ->
                    val n = p.split("-").size
                    if (n < 4) {
                        msg = "Нужно минимум 4 точки"
                    } else if (stage == 2) {
                        firstPattern = p; msg = ""; stage = 3
                    } else if (p == firstPattern) {
                        AppLock.setPattern(ctx, login, p)
                        firstPattern = ""; msg = ""; stage = 1; tick++
                    } else {
                        msg = "Ключи не совпали. Начните заново"; firstPattern = ""; stage = 2
                    }
                }
                if (msg.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { stage = 1; msg = "" }) { Text("Назад") }
            }
        }
    }
}

// ============================================================ разблокировка

@Composable
fun UnlockScreen(user: AppUser, onUnlocked: () -> Unit, onForced: () -> Unit) {
    val ctx = LocalContext.current
    val login = user.login
    val hasPin = remember { AppLock.hasPin(ctx, login) }
    val hasPat = remember { AppLock.hasPattern(ctx, login) }
    val canSys = remember { AppLock.canSystemAuth(ctx) }
    var mode by remember { mutableStateOf(if (hasPin) "pin" else "pattern") }
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    fun handle(r: LockResult) {
        when (r) {
            is LockResult.Ok -> { AppLock.unlockedNow(); onUnlocked() }
            is LockResult.Fail -> { msg = r.text; bad = true; pin = "" }
            is LockResult.Dead -> {
                Toast.makeText(ctx, "Слишком много ошибок. Войдите заново по Email и паролю", Toast.LENGTH_LONG).show()
                AppLock.clear(ctx, login)
                onForced()
            }
        }
    }

    fun system() {
        val act = ctx.findFragmentActivity() ?: return
        AppLock.systemPrompt(act, { AppLock.unlockedNow(); onUnlocked() }, { e -> if (e != null) msg = e })
    }

    // отпечаток предлагается сам, если включён в защите
    LaunchedEffect(Unit) {
        if (AppLock.bioOn(ctx, login) && canSys) system()
    }

    BackHandler { }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("🔒", fontSize = 56.sp)
        Spacer(Modifier.height(8.dp))
        Text("Приложение заблокировано", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(user.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        if (mode == "pin") {
            val len = AppLock.pinLen(ctx, login)
            PinField(
                value = pin, label = "PIN-код",
                onChange = { n ->
                    pin = n; msg = ""; bad = false
                    if (len > 0 && n.length == len) handle(AppLock.checkPin(ctx, login, n))
                },
                onDone = { if (pin.isNotEmpty()) handle(AppLock.checkPin(ctx, login, pin)) }
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { if (pin.isNotEmpty()) handle(AppLock.checkPin(ctx, login, pin)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть")
            }
        } else {
            PatternPad(error = bad) { p -> handle(AppLock.checkPattern(ctx, login, p)) }
        }
        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(msg, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        if (hasPin && hasPat) {
            TextButton(onClick = { mode = if (mode == "pin") "pattern" else "pin"; msg = ""; bad = false }) {
                Text(if (mode == "pin") "Графический ключ" else "PIN-код")
            }
        }
        if (canSys) {
            OutlinedButton(onClick = { system() }, modifier = Modifier.fillMaxWidth()) { Text("Отпечаток / лицо / пароль телефона") }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { confirmForget = true }) { Text("Забыли код? Выйти из аккаунта") }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Защита будет сброшена. Войдите по Email и паролю и задайте новый PIN-код.") },
            confirmButton = { TextButton(onClick = { AppLock.clear(ctx, login); onForced() }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Отмена") } }
        )
    }
}

// ============================================================ информирование

@Composable
fun ConsentScreen(user: AppUser, onAccepted: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var a by remember { mutableStateOf(false) }
    var b by remember { mutableStateOf(false) }
    BackHandler { }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("Важная информация", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Прочитайте до конца. Без вашего подтверждения приложение не откроется.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Consent.sections.forEach { (t, body) ->
                Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = a, onCheckedChange = { a = it })
            Text(
                "Я ознакомился(ась) с правилами работы с данными, понимаю их и обязуюсь соблюдать.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = b, onCheckedChange = { b = it })
            Text(
                "Я согласен(на) на проверку времени и моего местоположения при работе с приложением и на журналирование действий.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = a && b,
            onClick = { Consent.accept(ctx, user.login); onAccepted() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Продолжить") }
        TextButton(onClick = onLogout, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Не согласен — выйти") }
    }
}

// ============================================================ рабочее время и место (задаёт администратор)

data class AccessCfg(
    val enabled: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusKm: Double = 5.0,
    val fromMin: Int = 8 * 60,
    val toMin: Int = 18 * 60,
    val days: Set<Int> = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
    /** Часовой пояс, в котором администратор задавал часы работы. */
    val tz: String = TimeZone.getDefault().id
) {
    fun describe(): String {
        val names = mapOf(2 to "пн", 3 to "вт", 4 to "ср", 5 to "чт", 6 to "пт", 7 to "сб", 1 to "вс")
        val d = listOf(2, 3, 4, 5, 6, 7, 1).filter { it in days }.joinToString(", ") { names[it] ?: "" }
        val r = if (radiusKm == radiusKm.toLong().toDouble()) radiusKm.toLong().toString() else radiusKm.toString()
        return "Рабочее время: ${WorkStore.minToText(fromMin)}–${WorkStore.minToText(toMin)} ($d).\n" +
            "Рабочее место: не дальше $r км от точки, заданной администратором."
    }
}

private suspend fun <T> Task<T>.awaitC(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { if (c.isActive) c.resume(it) }
    addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
}

object AccessStore {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("access_cfg", Context.MODE_PRIVATE)
    private val fs get() = FirebaseFirestore.getInstance()

    fun cached(ctx: Context): AccessCfg? {
        val s = sp(ctx)
        if (!s.contains("saved")) return null
        return AccessCfg(
            enabled = s.getBoolean("enabled", false),
            lat = java.lang.Double.longBitsToDouble(s.getLong("lat", 0L)),
            lon = java.lang.Double.longBitsToDouble(s.getLong("lon", 0L)),
            radiusKm = java.lang.Double.longBitsToDouble(s.getLong("radius", java.lang.Double.doubleToRawLongBits(5.0))),
            fromMin = s.getInt("from", 480),
            toMin = s.getInt("to", 1080),
            days = (s.getString("days", "2,3,4,5,6") ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.toSet(),
            tz = s.getString("tz", null) ?: TimeZone.getDefault().id
        )
    }

    private fun cache(ctx: Context, c: AccessCfg) {
        sp(ctx).edit().putBoolean("saved", true).putBoolean("enabled", c.enabled)
            .putLong("lat", java.lang.Double.doubleToRawLongBits(c.lat))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(c.lon))
            .putLong("radius", java.lang.Double.doubleToRawLongBits(c.radiusKm))
            .putInt("from", c.fromMin).putInt("to", c.toMin).putString("days", c.days.joinToString(","))
            .putString("tz", c.tz).apply()
    }

    private fun parse(d: DocumentSnapshot): AccessCfg = AccessCfg(
        enabled = d.getBoolean("enabled") ?: false,
        lat = d.getDouble("lat") ?: 0.0,
        lon = d.getDouble("lon") ?: 0.0,
        radiusKm = d.getDouble("radiusKm") ?: 5.0,
        fromMin = (d.getLong("from") ?: 480L).toInt(),
        toMin = (d.getLong("to") ?: 1080L).toInt(),
        days = (d.get("days") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.toSet()
            ?: setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
        tz = d.getString("tz") ?: TimeZone.getDefault().id
    )

    /** Настройки с сервера; нет связи — последние сохранённые на телефоне; null — не знаем вообще. */
    suspend fun load(ctx: Context): AccessCfg? {
        try {
            val snap = withTimeoutOrNull(8_000L) {
                fs.collection("config").document("access").get(Source.SERVER).awaitC()
            }
            if (snap != null) {
                val c = if (snap.exists()) parse(snap) else AccessCfg(enabled = false)
                cache(ctx, c)
                return c
            }
        } catch (e: Exception) {
        }
        return cached(ctx)
    }

    fun save(ctx: Context, c: AccessCfg) {
        fs.collection("config").document("access").set(
            mapOf(
                "enabled" to c.enabled, "lat" to c.lat, "lon" to c.lon, "radiusKm" to c.radiusKm,
                "from" to c.fromMin.toLong(), "to" to c.toMin.toLong(), "days" to c.days.map { it.toLong() },
                "tz" to c.tz, "updatedAt" to System.currentTimeMillis()
            )
        )
        cache(ctx, c)
    }
}

/** Серверное время: подкрутка часов на телефоне не помогает. Между сверками идёт по монотонному таймеру телефона. */
object ServerClock {
    @Volatile private var serverAt = 0L
    @Volatile private var elapsedAt = 0L

    fun age(): Long = if (serverAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - elapsedAt
    fun now(): Long? = if (serverAt == 0L) null else serverAt + (SystemClock.elapsedRealtime() - elapsedAt)

    suspend fun sync(login: String): Boolean {
        return try {
            val ref = FirebaseFirestore.getInstance().collection("users").document(login)
            val ok = withTimeoutOrNull(8_000L) {
                ref.update("seenAt", FieldValue.serverTimestamp()).awaitC()
                ref.get(Source.SERVER).awaitC()
            } ?: return false
            val ts = ok.getTimestamp("seenAt") ?: return false
            serverAt = ts.toDate().time
            elapsedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            false
        }
    }
}

enum class Why { CHECKING, TIME, PLACE, NO_PERM, NO_GPS, NO_NET, MOCK }

sealed class AccessResult {
    object Open : AccessResult()
    class Closed(val why: Why, val text: String, val cfg: AccessCfg?) : AccessResult()
}

object AccessCheck {
    fun inWindow(c: AccessCfg, serverNow: Long): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone(c.tz))
        cal.timeInMillis = serverNow
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (c.fromMin <= c.toMin) {
            dow in c.days && m >= c.fromMin && m <= c.toMin
        } else {
            if (m >= c.fromMin) dow in c.days
            else if (m <= c.toMin) (if (dow == Calendar.SUNDAY) Calendar.SATURDAY else dow - 1) in c.days
            else false
        }
    }

    suspend fun evaluate(ctx: Context, user: AppUser): AccessResult {
        if (user.isAdmin) return AccessResult.Open
        val cfg = AccessStore.load(ctx)
            ?: return AccessResult.Closed(Why.NO_NET, "Нет связи с сервером: не удалось проверить настройки доступа.", null)
        if (!cfg.enabled) return AccessResult.Open

        // 1) время — только серверное
        if (ServerClock.age() > 5 * 60_000L) ServerClock.sync(user.login)
        val now = (if (ServerClock.age() <= 30 * 60_000L) ServerClock.now() else null)
            ?: return AccessResult.Closed(Why.NO_NET, "Нет связи с сервером: не удалось проверить время.", cfg)
        if (!inWindow(cfg, now)) {
            return AccessResult.Closed(Why.TIME, "Сейчас нерабочее время. Приложение работает только в рабочее время.", cfg)
        }

        // 2) место
        if (!WorkTracker.hasFine(ctx)) {
            return AccessResult.Closed(Why.NO_PERM, "Нужен доступ к геолокации, чтобы проверить, что вы на рабочем месте.", cfg)
        }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val gpsOn = try {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { false }
        if (!gpsOn) return AccessResult.Closed(Why.NO_GPS, "Включите геолокацию (GPS) на телефоне.", cfg)
        val loc = WorkTracker.fix(ctx)
            ?: return AccessResult.Closed(Why.NO_GPS, "Не удалось определить местоположение. Выйдите на открытое место и повторите.", cfg)
        val mock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider
        if (mock) return AccessResult.Closed(Why.MOCK, "Обнаружена подмена местоположения. Отключите её.", cfg)
        val res = FloatArray(1)
        android.location.Location.distanceBetween(loc.latitude, loc.longitude, cfg.lat, cfg.lon, res)
        val acc = if (loc.hasAccuracy()) minOf(loc.accuracy, 200f) else 0f
        if (res[0] - acc > cfg.radiusKm * 1000.0) {
            val km = res[0] / 1000f
            return AccessResult.Closed(Why.PLACE, "Вы вне рабочей зоны (до рабочей точки около ${"%.1f".format(km)} км).", cfg)
        }
        return AccessResult.Open
    }
}

@Composable
fun AccessClosedScreen(r: AccessResult.Closed, onRetry: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onRetry() }
    val tr = rememberInfiniteTransition(label = "access")
    val scale by tr.animateFloat(
        0.88f, 1.12f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale"
    )
    val alpha by tr.animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha"
    )
    val checking = r.why == Why.CHECKING
    val icon = when (r.why) { Why.TIME -> "🕒"; Why.CHECKING -> "📡"; Why.NO_NET -> "📶"; else -> "📍" }
    val title = when (r.why) {
        Why.CHECKING -> "Проверяем время и место…"
        Why.TIME -> "Сейчас не рабочее время"
        Why.PLACE -> "Вы не на рабочем месте"
        else -> "Доступ закрыт"
    }
    BackHandler { }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(icon, fontSize = 84.sp, modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha })
        Spacer(Modifier.height(20.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        if (!checking) Text(r.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "Приложение работает только в рабочее время и на рабочем месте.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        r.cfg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.describe(), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 14.sp)
        }
        Spacer(Modifier.height(24.dp))
        if (checking) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        } else {
            if (r.why == Why.NO_PERM) {
                Button(
                    onClick = {
                        permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Разрешить геолокацию") }
                Spacer(Modifier.height(8.dp))
            }
            if (r.why == Why.NO_GPS) {
                Button(
                    onClick = { try { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) { } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Открыть настройки геолокации") }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Проверить снова") }
            Text(
                "Окно откроется само, когда условия выполнятся.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onLogout) { Text("Выйти из аккаунта") }
    }
}

// ============================================================ входной контроль

/**
 * Оборачивает главный экран: установка защиты → информирование → главный экран.
 * Поверх главного экрана (его состояние сохраняется) появляются блокировка и «доступ закрыт».
 */
@Composable
fun AppGate(user: AppUser, onLogout: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val login = user.login
    val regular = !user.isAdmin
    var lockTick by remember { mutableIntStateOf(0) }
    val hasLock = remember(lockTick, login, AppGuard.setupRequest) { AppLock.hasLock(ctx, login) }
    var consent by remember(login) { mutableStateOf(Consent.accepted(ctx, login)) }
    val forcedSetup = regular && !hasLock
    val needConsent = regular && !forcedSetup && !consent

    // 30 минут без действий — блокировка
    LaunchedEffect(hasLock) {
        while (hasLock) {
            delay(15_000L)
            if (!AppLock.locked && AppLock.idleExpired()) AppLock.locked = true
        }
    }

    val showUnlock = hasLock && AppLock.locked
    val unlocked = !forcedSetup && !needConsent && !showUnlock

    // проверка времени и места: при открытии, при возврате в приложение и периодически
    var access by remember { mutableStateOf<AccessResult?>(null) }
    var recheck by remember { mutableIntStateOf(0) }
    LaunchedEffect(login, unlocked, recheck, AppGuard.resumeTick) {
        if (!unlocked || !regular) {
            access = AccessResult.Open
            return@LaunchedEffect
        }
        while (true) {
            val r = try { AccessCheck.evaluate(ctx, user) } catch (e: Exception) {
                AccessResult.Closed(Why.NO_NET, "Не удалось выполнить проверку. Повторите.", null)
            }
            access = r
            delay(if (r is AccessResult.Open) 120_000L else 20_000L)
        }
    }
    val acc = access
    val showAccess = regular && unlocked && acc !is AccessResult.Open

    SideEffect { AppGuard.blocked = showUnlock || showAccess }
    DisposableEffect(Unit) { onDispose { AppGuard.blocked = false } }

    val forceOut: () -> Unit = { AppLock.clear(ctx, login); onLogout() }

    when {
        forcedSetup -> Surface(Modifier.fillMaxSize()) {
            SetupLockScreen(user, cancelable = false) { lockTick++ }
        }
        needConsent -> Surface(Modifier.fillMaxSize()) {
            ConsentScreen(user, onAccepted = { consent = true }, onLogout = onLogout)
        }
        else -> Box(Modifier.fillMaxSize()) {
            content()
            if (showAccess) {
                Surface(Modifier.fillMaxSize().pointerInput(Unit) { }) {
                    AccessClosedScreen(
                        acc as? AccessResult.Closed ?: AccessResult.Closed(Why.CHECKING, "", null),
                        onRetry = { recheck++ }, onLogout = onLogout
                    )
                }
            }
            if (AppGuard.setupRequest && !showUnlock) {
                Surface(Modifier.fillMaxSize().pointerInput(Unit) { }) {
                    SetupLockScreen(user, cancelable = true) { lockTick++ }
                }
            }
            if (showUnlock) {
                Surface(Modifier.fillMaxSize().pointerInput(Unit) { }) {
                    UnlockScreen(user, onUnlocked = { lockTick++ }, onForced = forceOut)
                }
            }
        }
    }
}

// ============================================================ настройки администратора

@Composable
fun AccessSettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(AccessStore.cached(ctx) ?: AccessCfg()) }
    var loaded by remember { mutableStateOf(false) }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var radText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun fill(c: AccessCfg) {
        cfg = c
        latText = if (c.lat == 0.0 && c.lon == 0.0) "" else c.lat.toString()
        lonText = if (c.lat == 0.0 && c.lon == 0.0) "" else c.lon.toString()
        radText = if (c.radiusKm == c.radiusKm.toLong().toDouble()) c.radiusKm.toLong().toString() else c.radiusKm.toString()
    }

    LaunchedEffect(Unit) {
        AccessStore.load(ctx)?.let { fill(it) } ?: fill(cfg)
        loaded = true
    }

    fun here() {
        scope.launch {
            busy = true
            val l = WorkTracker.fix(ctx)
            busy = false
            if (l == null) Toast.makeText(ctx, "Не удалось определить местоположение", Toast.LENGTH_LONG).show()
            else { latText = "%.6f".format(l.latitude).replace(',', '.'); lonText = "%.6f".format(l.longitude).replace(',', '.') }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (WorkTracker.hasFine(ctx)) here()
    }

    fun pickTime(cur: Int, set: (Int) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> set(h * 60 + m) }, cur / 60, cur % 60, true).show()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Доступ по месту и времени", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Касается обычных пользователей. Администратор работает всегда. Время берётся с сервера, а не с часов телефона.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ограничение включено", Modifier.weight(1f))
            Switch(checked = cfg.enabled, onCheckedChange = { cfg = cfg.copy(enabled = it) })
        }
        Spacer(Modifier.height(12.dp))
        Text("Рабочая точка", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            latText, { latText = it }, label = { Text("Широта (lat)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            lonText, { lonText = it }, label = { Text("Долгота (lon)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            enabled = !busy,
            onClick = {
                if (WorkTracker.hasFine(ctx)) here()
                else permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Определяем…" else "Взять моё текущее местоположение") }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            radText, { radText = it }, label = { Text("Радиус, км (по умолчанию 5)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Text("Рабочее время", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickTime(cfg.fromMin) { cfg = cfg.copy(fromMin = it) } }, modifier = Modifier.weight(1f)) {
                Text("С ${WorkStore.minToText(cfg.fromMin)}")
            }
            OutlinedButton(onClick = { pickTime(cfg.toMin) { cfg = cfg.copy(toMin = it) } }, modifier = Modifier.weight(1f)) {
                Text("До ${WorkStore.minToText(cfg.toMin)}")
            }
        }
        Spacer(Modifier.height(8.dp))
        val names = listOf(2 to "Пн", 3 to "Вт", 4 to "Ср", 5 to "Чт", 6 to "Пт", 7 to "Сб", 1 to "Вс")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            names.forEach { (d, n) ->
                val on = d in cfg.days
                val m = Modifier.weight(1f)
                val toggle = { cfg = cfg.copy(days = if (on) cfg.days - d else cfg.days + d) }
                if (on) Button(onClick = toggle, modifier = m, contentPadding = PaddingValues(0.dp)) { Text(n, fontSize = 13.sp) }
                else OutlinedButton(onClick = toggle, modifier = m, contentPadding = PaddingValues(0.dp)) { Text(n, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Часовой пояс: ${TimeZone.getDefault().id} (как на этом телефоне). Окно через полночь тоже поддерживается.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = loaded,
            onClick = {
                val lat = latText.trim().replace(',', '.').toDoubleOrNull()
                val lon = lonText.trim().replace(',', '.').toDoubleOrNull()
                val rad = radText.trim().replace(',', '.').toDoubleOrNull() ?: 5.0
                when {
                    cfg.enabled && (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) ->
                        Toast.makeText(ctx, "Укажите рабочую точку", Toast.LENGTH_LONG).show()
                    rad <= 0.0 -> Toast.makeText(ctx, "Радиус должен быть больше нуля", Toast.LENGTH_LONG).show()
                    cfg.enabled && cfg.days.isEmpty() -> Toast.makeText(ctx, "Выберите рабочие дни", Toast.LENGTH_LONG).show()
                    else -> {
                        val n = cfg.copy(lat = lat ?: 0.0, lon = lon ?: 0.0, radiusKm = rad, tz = TimeZone.getDefault().id)
                        AccessStore.save(ctx, n)
                        cfg = n
                        try { Journal.log("Система", "Изменены настройки доступа по месту и времени") } catch (e: Exception) { }
                        Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить") }
    }
}
