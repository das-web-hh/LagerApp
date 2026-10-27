package ru.storage.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false, digits: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                digits -> KeyboardType.NumberPassword
                secret -> KeyboardType.Password
                else -> KeyboardType.Text
            }
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Профиль: имя и фамилия, смена пароля, быстрый вход (отпечаток / лицо и PIN из 4 цифр). */
@Composable
fun ProfileScreen(profile: UserProfile, prefs: Prefs, onLogout: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val me = Session.user
    var dialog by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) } // перечитать флаги быстрого входа
    val mine = me != null && LocalAuth.savedLogin(ctx) == me.login
    val bioOn = remember(tick) { mine && LocalAuth.bioEnabled(ctx) }
    val pinOn = remember(tick) { mine && LocalAuth.pinEnabled(ctx) }
    val canBio = remember { LocalAuth.canBiometric(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Профиль", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        InfoRow("Имя", profile.firstName)
        InfoRow("Фамилия", profile.lastName)
        InfoRow("Должность", profile.position)
        InfoRow("Логин", me?.login ?: "")
        Spacer(Modifier.height(8.dp))
        if (me != null && me.isAdmin) {
            OutlinedButton(onClick = { dialog = "name" }, modifier = Modifier.fillMaxWidth()) { Text("Изменить имя и фамилию") }
            Spacer(Modifier.height(8.dp))
        } else if (me != null) {
            Text(
                "Имя и фамилию меняет администратор. Пароль вы можете изменить сами.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        if (me != null) {
            OutlinedButton(onClick = { dialog = "pass" }, modifier = Modifier.fillMaxWidth()) { Text("Сменить пароль") }
        }

        Spacer(Modifier.height(20.dp))
        Text("Быстрый вход", style = MaterialTheme.typography.titleMedium)
        Text(
            "Пароль хранится на этом телефоне в защищённом хранилище Android.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Отпечаток / лицо")
                if (!canBio) Text(
                    "На телефоне не настроена биометрия",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = bioOn,
                enabled = canBio && me != null,
                onCheckedChange = {
                    if (it) dialog = "bio" else { LocalAuth.setBio(ctx, false); tick++ }
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        if (pinOn) {
            Text("PIN-код включён")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialog = "pin" }, modifier = Modifier.weight(1f)) { Text("Изменить PIN") }
                OutlinedButton(
                    onClick = { LocalAuth.removePin(ctx); tick++ },
                    modifier = Modifier.weight(1f)
                ) { Text("Удалить PIN") }
            }
        } else {
            OutlinedButton(onClick = { dialog = "pin" }, enabled = me != null, modifier = Modifier.fillMaxWidth()) {
                Text("Установить PIN-код (4 цифры)")
            }
        }

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Выйти") }
    }

    if (me != null) {
        when (dialog) {
            "name" -> NameDialog(me, onClose = { dialog = null })
            "pass" -> PassDialog(me, onClose = { dialog = null })
            "bio" -> QuickDialog(me, withPin = false, onClose = { dialog = null }, onDone = { tick++ })
            "pin" -> QuickDialog(me, withPin = true, onClose = { dialog = null }, onDone = { tick++ })
        }
    }
}

@Composable
private fun NameDialog(me: AppUser, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf(me.name.substringBefore(' ')) }
    var last by remember { mutableStateOf(me.name.substringAfter(' ', "")) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Имя и фамилия") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DField(first, { first = it; err = null }, "Имя")
                DField(last, { last = it; err = null }, "Фамилия")
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val e = Users.updateName(me.login, first, last)
                    busy = false
                    if (e == null) {
                        if (me.isAdmin) {
                            val full = listOf(first, last).joinToString(" ") { it.trim().replace(Regex("\\s+"), " ") }
                            Session.onUserUpdated?.invoke(me.copy(name = full))
                        }
                        Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show()
                        onClose()
                    } else err = e
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Отмена") } }
    )
}

@Composable
private fun PassDialog(me: AppUser, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Смена пароля") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DField(old, { old = it; err = null }, "Текущий пароль", secret = true)
                DField(new1, { new1 = it; err = null }, "Новый пароль (минимум 6 символов)", secret = true)
                DField(new2, { new2 = it; err = null }, "Повторите новый пароль", secret = true)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && old.isNotEmpty() && new1.isNotEmpty(), onClick = {
                if (new1 != new2) { err = "Пароли не совпадают"; return@Button }
                busy = true
                scope.launch {
                    val e = Users.changePassword(me.login, old, new1)
                    busy = false
                    if (e == null) {
                        LocalAuth.refreshPassword(ctx, me.login, new1.trim())
                        Toast.makeText(ctx, "Пароль изменён", Toast.LENGTH_SHORT).show()
                        onClose()
                    } else err = e
                }
            }) { Text("Сменить") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Отмена") } }
    )
}

/** Включение отпечатка (withPin = false) или установка PIN (withPin = true): сначала проверяем пароль в Firebase. */
@Composable
private fun QuickDialog(me: AppUser, withPin: Boolean, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun finish(msg: String) {
        onDone()
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        onClose()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (withPin) "PIN-код" else "Вход по отпечатку / лицу") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Введите пароль, чтобы телефон запомнил его.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DField(pw, { pw = it; err = null }, "Пароль", secret = true)
                if (withPin) {
                    DField(pin1, { pin1 = it.filter(Char::isDigit).take(4); err = null }, "PIN-код (4 цифры)", secret = true, digits = true)
                    DField(pin2, { pin2 = it.filter(Char::isDigit).take(4); err = null }, "Повторите PIN-код", secret = true, digits = true)
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && pw.isNotEmpty(), onClick = {
                if (withPin) {
                    if (pin1.length != 4) { err = "PIN — ровно 4 цифры"; return@Button }
                    if (pin1 != pin2) { err = "PIN-коды не совпадают"; return@Button }
                }
                busy = true
                scope.launch {
                    val r = Users.signIn(me.login, pw)
                    if (r !is AuthResult.Ok) {
                        busy = false
                        err = if (r is AuthResult.Fail) r.text else "Не удалось проверить пароль"
                        return@launch
                    }
                    if (withPin) {
                        busy = false
                        if (LocalAuth.saveCredentials(ctx, me.login, pw.trim())) {
                            LocalAuth.setPin(ctx, pin1)
                            finish("PIN-код сохранён")
                        } else err = "Не удалось сохранить пароль в защищённом хранилище"
                    } else {
                        val act = ctx.findFragmentActivity()
                        if (act == null) { busy = false; err = "Биометрия недоступна"; return@launch }
                        LocalAuth.authenticate(
                            act,
                            onOk = {
                                busy = false
                                if (LocalAuth.saveCredentials(ctx, me.login, pw.trim())) {
                                    LocalAuth.setBio(ctx, true)
                                    finish("Вход по отпечатку / лицу включён")
                                } else err = "Не удалось сохранить пароль в защищённом хранилище"
                            },
                            onError = { busy = false; if (it != null) err = it }
                        )
                    }
                }
            }) { Text(if (withPin) "Сохранить" else "Продолжить") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Отмена") } }
    )
}
