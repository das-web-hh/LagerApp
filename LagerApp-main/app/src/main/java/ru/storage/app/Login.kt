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

@Composable
fun LoginScreen(onSuccess: (String) -> Unit) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val shake = remember { Animatable(0f) }
    val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val ver = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { null } ?: ""
    }

    fun submit() {
        if (login.trim() == "admin" && password == "admin") {
            focus.clearFocus() // клавиатура закрывается, дальше — анимация входа
            onSuccess(login.trim())
        } else {
            error = "Неверный логин или пароль"
            scope.launch {
                repeat(3) {
                    shake.animateTo(14f, tween(55))
                    shake.animateTo(-14f, tween(55))
                }
                shake.animateTo(0f, tween(55))
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Клавиатура открыта — окно стало ниже: уменьшаем логотип и прячем год внизу, чтобы поля были на виду
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
            Spacer(Modifier.height(if (keyboard) 12.dp else 28.dp))

            Column(Modifier.fillMaxWidth().offset { IntOffset(shake.value.roundToInt(), 0) }) {
                Appear(800, Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it; error = null },
                        label = { Text("Логин") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                Appear(900, Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Пароль") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { show = !show }) { Text(if (show) "Скрыть" else "Показать") }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(18.dp))
            Appear(1000, Modifier.fillMaxWidth()) {
                Button(
                    onClick = { submit() },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) { Text("Войти", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
        }

        // Год внизу окна: с какого года приложение работает; при открытой клавиатуре не показываем
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
