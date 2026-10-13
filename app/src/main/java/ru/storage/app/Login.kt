package ru.storage.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.roundToInt

/** Плавное появление элемента с задержкой (место под него занято сразу — вёрстка не прыгает). */
@Composable
private fun Appear(delayMs: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        a.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    Box(modifier.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 24.dp.toPx()
    }) { content() }
}

private enum class Mode { LOGIN, REG_EMAIL, REG_CODE, REG_DATA, PIN }

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    password: Boolean = false, show: Boolean = false, onToggle: (() -> Unit)? = null,
    keyboard: KeyboardType = KeyboardType.Text, ime: ImeAction = ImeAction.Next,
    focus: androidx.compose.ui.focus.FocusManager, onDone: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password && onToggle != null) {
            { TextButton(onClick = onToggle) { Text(if (show) "Скрыть" else "Показать") } }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (password && keyboard == KeyboardType.Text) KeyboardType.Password else keyboard, imeAction = ime),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }, onDone = { onDone() }),
        modifier = modifier
    )
}

@Composable
fun LoginScreen(onSuccess: (AppUser) -> Unit) {
    val ctx = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(if (LocalAuth.pinEnabled(ctx)) Mode.PIN else Mode.LOGIN) }
    var pin by remember { mutableStateOf("") }
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    var password2 by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val ver = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { null } ?: ""
    }

    fun fail(text: String) {
        error = text
        info = null
        scope.launch {
            repeat(3) {
                shake.animateTo(14f, tween(55))
                shake.animateTo(-14f, tween(55))
            }
            shake.animateTo(0f, tween(55))
        }
    }

    fun go(m: Mode) {
        mode = m
        error = null
        info = null
        password = ""
        password2 = ""
        pin = ""
        show = false
    }

    fun finish(u: AppUser) {
        focus.clearFocus()
        if (!u.local) ConnCfg.markConnected(ctx)
        onSuccess(u)
    }

    fun quickSignIn(login: String, pw: String) {
        busy = true
        scope.launch {
            val r = Users.signIn(login, pw)
            busy = false
            when (r) {
                is AuthResult.Ok -> finish(r.user)
                is AuthResult.Fail -> {
                    val stale = r.text.startsWith("Неверный пароль") || r.text.startsWith("Аккаунт")
                    if (stale) {
                        LocalAuth.clearAll(ctx)
                        go(Mode.LOGIN)
                        fail(r.text + ". Сохранённый вход сброшен — войдите паролем")
                    } else {
                        pin = ""
                        fail(r.text)
                    }
                }
                is AuthResult.Setup, is AuthResult.Local -> {}
            }
        }
    }

    fun tryBiometric() {
        val act = ctx.findFragmentActivity() ?: return
        LocalAuth.authenticate(
            act,
            onOk = {
                val c = LocalAuth.credentials(ctx)
                if (c == null) {
                    LocalAuth.clearAll(ctx)
                    go(Mode.LOGIN)
                    fail("Сохранённый вход недоступен — войдите паролем")
                } else quickSignIn(c.first, c.second)
            },
            onError = { if (it != null) fail(it) }
        )
    }

    fun submit() {
        if (busy) return
        error = null
        info = null
        when (mode) {
            Mode.LOGIN -> {
                busy = true
                scope.launch {
                    val r = Users.signIn(login, password)
                    busy = false
                    when (r) {
                        is AuthResult.Ok -> finish(r.user)
                        is AuthResult.Local -> finish(r.user)
                        is AuthResult.Fail -> fail(r.text)
                        is AuthResult.Setup -> fail("Режим первоначальной настройки заблокирован.")
                    }
                }
            }
            Mode.PIN -> {
                if (pin.length != 4) { fail("Введите 4 цифры"); return }
                when (val r = LocalAuth.checkPin(ctx, pin)) {
                    is PinResult.Ok -> quickSignIn(r.login, r.password)
                    is PinResult.Wrong -> { pin = ""; fail("Неверный PIN-код. Осталось попыток: ${r.left}") }
                    is PinResult.Locked -> { go(Mode.LOGIN); fail("PIN-код сброшен из-за ошибок — войдите паролем") }
                }
            }
            Mode.REG_EMAIL -> {
                busy = true
                scope.launch {
                    val e = Users.key(email)
                    val problem = Users.checkEmailFree(e) ?: EmailCode.send(e)
                    busy = false
                    if (problem != null) fail(problem)
                    else {
                        email = e
                        code = ""
                        go(Mode.REG_CODE)
                        info = "Код отправлен на $e"
                    }
                }
            }
            Mode.REG_CODE -> {
                val problem = EmailCode.check(Users.key(email), code)
                if (problem != null) fail(problem) else { go(Mode.REG_DATA); info = "Email подтверждён" }
            }
            Mode.REG_DATA -> {
                if (password != password2) { fail("Пароли не совпадают"); return }
                busy = true
                scope.launch {
                    val r = Users.register(email, firstName, lastName, password)
                    busy = false
                    when (r) {
                        is AuthResult.Ok -> finish(r.user)
                        is AuthResult.Fail -> fail(r.text)
                        is AuthResult.Setup, is AuthResult.Local -> {}
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!LocalAuth.autoPrompted && (mode == Mode.PIN || mode == Mode.LOGIN) &&
            LocalAuth.bioEnabled(ctx) && LocalAuth.canBiometric(ctx)
        ) {
            LocalAuth.autoPrompted = true
            kotlinx.coroutines.delay(400)
            tryBiometric()
        }
    }

    DisposableEffect(Unit) {
        val act = ctx.findFragmentActivity()
        val w = act?.window
        w?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose { w?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val h = maxHeight
        var fullH by remember { mutableStateOf(h) }
        if (h > fullH) fullH = h
        val keyboard = h < fullH * 0.8f
        val logoSize by animateDpAsState(if (keyboard) 84.dp else 150.dp, tween(300), label = "logo")

        Column(
            Modifier.fillMaxWidth().heightIn(min = h)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Appear(0) {
                Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(logoSize))
            }
            Spacer(Modifier.height(8.dp))
            Appear(350) {
                Text("Warehouse", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = BrandBlue)
            }
            Appear(600) {
                Text("v$ver", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(if (keyboard) 8.dp else 20.dp))

            val title = when (mode) {
                Mode.LOGIN -> ""
                Mode.PIN -> "Вход по PIN-коду"
                Mode.REG_EMAIL -> "Регистрация — шаг 1 из 3"
                Mode.REG_CODE -> "Регистрация — шаг 2 из 3"
                Mode.REG_DATA -> "Регистрация — шаг 3 из 3"
            }
            if (title.isNotEmpty()) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
            }

            Column(Modifier.fillMaxWidth().offset { IntOffset(shake.value.roundToInt(), 0) }) {
                when (mode) {
                    Mode.LOGIN -> {
                        Appear(800, Modifier.fillMaxWidth()) {
                            Field(login, { login = it; error = null }, "Email / Логин", keyboard = KeyboardType.Email, focus = focus)
                        }
                        Spacer(Modifier.height(12.dp))
                        Appear(900, Modifier.fillMaxWidth()) {
                            Field(
                                password, { password = it; error = null }, "Пароль",
                                password = true, show = show, onToggle = { show = !show },
                                ime = ImeAction.Done, focus = focus, onDone = { submit() }
                            )
                        }
                    }
                    Mode.PIN -> {
                        Text(
                            "Аккаунт: ${LocalAuth.savedLogin(ctx) ?: ""}",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Field(
                            pin, { pin = it.filter(Char::isDigit).take(4); error = null; if (pin.length == 4) submit() },
                            "PIN-код (4 цифры)", password = true, keyboard = KeyboardType.NumberPassword,
                            ime = ImeAction.Done, focus = focus, onDone = { submit() }
                        )
                    }
                    Mode.REG_EMAIL -> {
                        Field(email, { email = it; error = null }, "Email", keyboard = KeyboardType.Email, ime = ImeAction.Done, focus = focus, onDone = { submit() })
                    }
                    Mode.REG_CODE -> {
                        Field(
                            code, { code = it.filter(Char::isDigit).take(6); error = null }, "Код из Email (6 цифр)",
                            keyboard = KeyboardType.Number, ime = ImeAction.Done, focus = focus, onDone = { submit() }
                        )
                    }
                    Mode.REG_DATA -> {
                        Field(firstName, { firstName = it; error = null }, "Имя", focus = focus)
                        Spacer(Modifier.height(12.dp))
                        Field(lastName, { lastName = it; error = null }, "Фамилия", focus = focus)
                        Spacer(Modifier.height(12.dp))
                        Field(password, { password = it; error = null }, "Пароль (минимум 6 символов)", password = true, show = show, onToggle = { show = !show }, focus = focus)
                        Spacer(Modifier.height(12.dp))
                        Field(password2, { password2 = it; error = null }, "Повторите пароль", password = true, show = show, ime = ImeAction.Done, focus = focus, onDone = { submit() })
                    }
                }
            }
            if (mode == Mode.REG_DATA) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Должность вам назначит администратор после проверки.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            info?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(18.dp))
            Appear(1000, Modifier.fillMaxWidth()) {
                Button(
                    onClick = { submit() },
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(
                        when (mode) {
                            Mode.LOGIN, Mode.PIN -> "Войти"
                            Mode.REG_EMAIL -> "Получить код"
                            Mode.REG_CODE -> "Подтвердить код"
                            Mode.REG_DATA -> "Завершить регистрацию"
                        },
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Appear(1100, Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if ((mode == Mode.LOGIN || mode == Mode.PIN) && LocalAuth.bioEnabled(ctx) && LocalAuth.canBiometric(ctx)) {
                        OutlinedButton(
                            onClick = { tryBiometric() },
                            enabled = !busy,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) { Text("Войти по отпечатку / лицу") }
                    }
                    when (mode) {
                        Mode.PIN -> TextButton(onClick = { go(Mode.LOGIN) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Войти по паролю")
                        }
                        Mode.LOGIN -> {
                            if (LocalAuth.pinEnabled(ctx)) {
                                TextButton(onClick = { go(Mode.PIN) }, modifier = Modifier.fillMaxWidth()) { Text("Войти по PIN-коду") }
                            }
                            TextButton(onClick = { go(Mode.REG_EMAIL) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Нет аккаунта? Зарегистрироваться")
                            }
                        }
                        Mode.REG_CODE -> {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val p = EmailCode.send(Users.key(email))
                                        busy = false
                                        if (p != null) fail(p) else { error = null; info = "Новый код отправлен на ${Users.key(email)}" }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Отправить код ещё раз") }
                            TextButton(onClick = { go(Mode.LOGIN) }, modifier = Modifier.fillMaxWidth()) { Text("Уже есть аккаунт? Войти") }
                        }
                        else -> TextButton(onClick = { go(Mode.LOGIN) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Уже есть аккаунт? Войти")
                        }
                    }
                }
            }
        }

        if (!keyboard) {
            Appear(1200, Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)) {
                Text(
                    "© 2026" + if (year > 2026) "–$year" else "",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}
