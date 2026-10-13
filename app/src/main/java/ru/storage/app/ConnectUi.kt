package ru.storage.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Перезапуск приложения (нужен, чтобы новое подключение к Firebase вступило в силу). */
fun restartApp(ctx: Context) {
    val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
    if (i != null) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(i)
    }
    Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
}

/** Выход из аккаунта на этом телефоне (сохранённый вход, PIN и отпечаток сбрасываются). */
private fun signOutHere(ctx: Context) {
    ctx.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().apply()
    try { LocalAuth.clearAll(ctx) } catch (e: Exception) { }
}

private fun readText(ctx: Context, uri: Uri): String? = try {
    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
} catch (e: Exception) {
    null
}

/**
 * Загрузка и сохранение файла настроек. Загрузить можно из файла на телефоне или из облачного хранилища
 * (Google Диск, OneDrive, Dropbox и др.): они открываются в том же системном окне выбора файла.
 * Перед загрузкой — информационное окно: логин и пароль изменятся на прежние.
 */
@Composable
fun SettingsFileBlock(allowSave: Boolean) {
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf<ConnCfg.Loaded?>(null) }
    var error by remember { mutableStateOf("") }

    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = readText(ctx, uri)
            if (text == null) error = "Не удалось прочитать файл"
            else {
                val (l, e) = ConnCfg.parse(text)
                if (l == null) error = e ?: "Не удалось разобрать файл" else { error = ""; loaded = l }
            }
        }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(ConnCfg.exportJson(ctx).toByteArray(Charsets.UTF_8)) }
                Toast.makeText(ctx, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                error = "Не удалось сохранить файл"
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Загрузить настройки", style = MaterialTheme.typography.titleMedium)
        Text(
            "Файл настроек содержит подключение к Firebase, EmailJS, адрес Google Диска и ключи ИИ.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { opener.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Из файла") }
            FilledTonalButton(onClick = { opener.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("Из облака") }
        }
        Text(
            "«Из облака»: в окне выбора откройте меню слева и выберите Google Диск, OneDrive или другое хранилище.",
            style = MaterialTheme.typography.bodySmall
        )
        if (allowSave) {
            OutlinedButton(
                onClick = { saver.launch("lager-settings.json") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить настройки в файл") }
            Text(
                "В файле лежат секретные ключи. Сохраните его в надёжное место (например, в свой облачный диск) и никому не передавайте.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    loaded?.let { l ->
        val who = l.adminLogin
        AlertDialog(
            onDismissRequest = { loaded = null },
            title = { Text("Загрузить настройки?") },
            text = {
                Text(
                    "Будут заменены подключения и ключи из файла.\n\n" +
                        "Логин и пароль изменятся на те, которые у вас были раньше" +
                        (if (who.isNotBlank()) ": логин $who, пароль — прежний." else ". Логин из файла не указан — войдите своим прежним Email и паролем.") +
                        "\n\nПриложение перезапустится, и нужно будет войти заново."
                )
            },
            confirmButton = {
                Button(onClick = {
                    ConnCfg.apply(ctx, l)
                    signOutHere(ctx)
                    loaded = null
                    restartApp(ctx)
                }) { Text("Загрузить") }
            },
            dismissButton = { TextButton(onClick = { loaded = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None
    )
}

/** Настройки → «Подключения»: Firebase и EmailJS (свои вместо встроенных) + файл настроек. Только администратор. */
@Composable
fun ConnectionSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    BackHandler(onBack = onBack)
    val fbo = remember { ConnCfg.firebaseOverride(ctx) }
    val act = remember { ConnCfg.firebaseActive() }
    var project by remember { mutableStateOf(fbo?.projectId ?: "") }
    var appId by remember { mutableStateOf(fbo?.appId ?: "") }
    var apiKey by remember { mutableStateOf(fbo?.apiKey ?: "") }
    var bucket by remember { mutableStateOf(fbo?.bucket ?: "") }
    var sender by remember { mutableStateOf(fbo?.senderId ?: "") }
    val mo = remember { ConnCfg.mailOverride(ctx) }
    var mPublic by remember { mutableStateOf(mo?.publicKey ?: "") }
    var mService by remember { mutableStateOf(mo?.serviceId ?: "") }
    var mTemplate by remember { mutableStateOf(mo?.templateId ?: "") }
    var mMessage by remember { mutableStateOf(mo?.messageTemplateId ?: "") }
    var mPrivate by remember { mutableStateOf(mo?.privateKey ?: "") }
    var msg by remember { mutableStateOf("") }
    var restart by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Подключения", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Если поля пустые, работают встроенные подключения. Свои можно задать вручную или загрузить файлом. " +
                "Новое подключение к Firebase вступает в силу после перезапуска приложения.",
            style = MaterialTheme.typography.bodySmall
        )

        SettingsFileBlock(allowSave = true)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        Text("Firebase", style = MaterialTheme.typography.titleMedium)
        Text(
            "Сейчас: " + (act?.projectId?.takeIf { it.isNotBlank() } ?: "—") +
                if (fbo != null) " (своё подключение)" else " (встроенное)",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(
            onClick = {
                val c = ConnCfg.parseGoogleServices(clipboard.getText()?.text.orEmpty())
                if (c == null) msg = "В буфере нет содержимого google-services.json"
                else {
                    project = c.projectId; appId = c.appId; apiKey = c.apiKey; bucket = c.bucket; sender = c.senderId
                    msg = "Данные из google-services.json вставлены. Нажмите «Сохранить»."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Вставить google-services.json из буфера") }
        Field("Project ID", project, { project = it })
        Field("App ID (mobilesdk_app_id)", appId, { appId = it })
        Field("API key", apiKey, { apiKey = it }, secret = true)
        Field("Storage bucket (необязательно)", bucket, { bucket = it })
        Field("Sender ID (необязательно)", sender, { sender = it })

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("EmailJS", style = MaterialTheme.typography.titleMedium)
        Text(
            "Сейчас: " + if (mo != null) "свои ключи" else "встроенные ключи",
            style = MaterialTheme.typography.bodySmall
        )
        Field("Public key", mPublic, { mPublic = it })
        Field("Service ID", mService, { mService = it })
        Field("Template ID (код подтверждения)", mTemplate, { mTemplate = it })
        Field("Template ID (письма сотрудникам)", mMessage, { mMessage = it })
        Field("Private key (необязательно)", mPrivate, { mPrivate = it }, secret = true)

        if (msg.isNotBlank()) Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        Button(
            onClick = {
                val fbAny = listOf(project, appId, apiKey).any { it.isNotBlank() }
                val fb = FbCfg(project, appId, apiKey, bucket, sender)
                val mailAny = listOf(mPublic, mService, mTemplate).any { it.isNotBlank() }
                when {
                    fbAny && !fb.valid -> msg = "Firebase: заполните Project ID, App ID и API key"
                    mailAny && listOf(mPublic, mService, mTemplate).any { it.isBlank() } ->
                        msg = "EmailJS: заполните Public key, Service ID и Template ID"
                    else -> {
                        msg = ""
                        ConnCfg.saveFirebase(ctx, if (fbAny) fb else null)
                        ConnCfg.saveMail(ctx, if (mailAny) MailCfg(mPublic, mService, mTemplate, mMessage, mPrivate) else null)
                        if (Session.user?.local != true) Journal.log("Система", "Изменены подключения (Firebase / EmailJS)")
                        restart = true
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сохранить") }
        OutlinedButton(
            onClick = {
                ConnCfg.saveFirebase(ctx, null)
                ConnCfg.saveMail(ctx, null)
                project = ""; appId = ""; apiKey = ""; bucket = ""; sender = ""
                mPublic = ""; mService = ""; mTemplate = ""; mMessage = ""; mPrivate = ""
                msg = ""
                restart = true
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Вернуть встроенные") }
    }

    if (restart) {
        AlertDialog(
            onDismissRequest = { restart = false },
            title = { Text("Перезапустить приложение?") },
            text = { Text("Подключения сохранены. Чтобы новое подключение к Firebase заработало, приложение нужно перезапустить.") },
            confirmButton = { Button(onClick = { restart = false; restartApp(ctx) }) { Text("Перезапустить") } },
            dismissButton = { TextButton(onClick = { restart = false }) { Text("Позже") } }
        )
    }
}

/**
 * Окно локального входа admin/admin: пока подключение не настроено, доступна только настройка.
 * Здесь загружают файл настроек (после него логин и пароль станут прежними) или задают подключения вручную.
 */
@Composable
fun SetupProfileScreen(prefs: Prefs, onLogout: () -> Unit) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    when (section) {
        "Подключения" -> ConnectionSettings { section = null }
        "Google Диск" -> DriveSettings(prefs) { section = null }
        "Распознавание документов" -> AiSettings { section = null }
        else -> {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Настройка подключения", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Вы вошли как admin/admin: подключение к базе ещё не настроено. Загрузите файл настроек — " +
                        "после этого логин и пароль станут прежними — или задайте подключения вручную.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                SettingsFileBlock(allowSave = false)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                listOf(
                    "Подключения" to "Firebase и EmailJS вручную",
                    "Google Диск" to "Адрес скрипта, папка, токен",
                    "Распознавание документов" to "API-ключи ИИ"
                ).forEach { (title, sub) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { section = title }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(sub, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Выйти") }
            }
        }
    }
}
