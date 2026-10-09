package ru.storage.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Текущий пользователь (логин) — по нему настройки хранятся в Firebase. */
object Session {
    @Volatile var login: String = "admin"
}

/**
 * Настройки аккаунта в Firestore: коллекция settings, документ = логин.
 * Поля: driveUrl, driveFolder, driveToken, useFront, formats[], updatedAt.
 * Побеждает более новая версия (по updatedAt).
 */
object CloudSettings {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    private fun docRef() = FirebaseFirestore.getInstance().collection("settings")
        .document(Session.login.lowercase().ifBlank { "admin" }.replace('/', '_'))

    /** Вызывать после любого изменения настроек: ставит время и отправляет в Firebase (в очередь, работает и без сети). */
    fun save(ctx: Context, prefs: Prefs) {
        val now = System.currentTimeMillis()
        sp(ctx).edit().putLong("updated", now).apply()
        push(ctx, prefs, now)
    }

    private fun push(ctx: Context, prefs: Prefs, ts: Long) {
        docRef().set(
            hashMapOf<String, Any>(
                "driveUrl" to DriveCfg.url(ctx),
                "driveFolder" to DriveCfg.folder(ctx),
                "driveToken" to DriveCfg.token(ctx),
                "useFront" to prefs.useFront,
                "formats" to prefs.formats.toList(),
                "updatedAt" to ts
            ),
            SetOptions.merge()
        )
    }

    /** Сверка с Firebase (1 чтение). Возвращает true, если настройки на телефоне обновились. */
    suspend fun pull(ctx: Context, prefs: Prefs): Boolean {
        val snap = docRef().get().awaitIt()
        val local = sp(ctx).getLong("updated", 0L)
        if (!snap.exists()) {
            if (local > 0L) push(ctx, prefs, local)
            return false
        }
        val remote = snap.getLong("updatedAt") ?: 0L
        if (remote > local) {
            DriveCfg.save(
                ctx,
                snap.getString("driveUrl") ?: "",
                snap.getString("driveFolder") ?: "",
                snap.getString("driveToken") ?: ""
            )
            prefs.useFront = snap.getBoolean("useFront") ?: false
            prefs.formats = (snap.get("formats") as? List<*>)
                ?.mapNotNull { (it as? Number)?.toInt() }?.toSet() ?: emptySet()
            sp(ctx).edit().putLong("updated", remote).apply()
            return true
        }
        if (remote < local) push(ctx, prefs, local)
        return false
    }
}

@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    when (section) {
        "Сканер" -> ScannerSettings(prefs) { section = null }
        "Google Диск" -> DriveSettings(prefs) { section = null }
        "Распознавание документов" -> AiSettings { section = null }
        else -> SettingsHome(onBack) { section = it }
    }
}

@Composable
private fun SettingsHome(onBack: () -> Unit, onOpen: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val sections = listOf(
        "Сканер" to "Камера, типы штрих-кодов",
        "Google Диск" to "Фото и накладные: адрес скрипта, папка, токен",
        "Распознавание документов" to "ИИ для автоприёма: провайдер, ключ, модель, время ожидания"
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        sections.forEach { (title, sub) ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(title) }.padding(vertical = 14.dp),
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
    }
}

@Composable
private fun ScannerSettings(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var front by remember { mutableStateOf(prefs.useFront) }
    var formats by remember { mutableStateOf(prefs.formats) }
    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        try {
            if (CloudSettings.pull(ctx, prefs)) {
                front = prefs.useFront
                formats = prefs.formats
            }
        } catch (e: Exception) {
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Сканер", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Text("Камера для сканера", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !front, onClick = {
                front = false; prefs.useFront = false; CloudSettings.save(ctx, prefs)
            })
            Text("Основная")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = front, onClick = {
                front = true; prefs.useFront = true; CloudSettings.save(ctx, prefs)
            })
            Text("Фронтальная")
        }

        Spacer(Modifier.height(16.dp))
        Text("Типы кодов", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = formats.isEmpty(),
                onCheckedChange = {
                    formats = emptySet(); prefs.formats = emptySet(); CloudSettings.save(ctx, prefs)
                }
            )
            Text("Все типы")
        }
        allFormats.forEach { (name, code) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = code in formats,
                    onCheckedChange = { on ->
                        val n = if (on) formats + code else formats - code
                        formats = n
                        prefs.formats = n
                        CloudSettings.save(ctx, prefs)
                    }
                )
                Text(name)
            }
        }
    }
}

@Composable
private fun DriveSettings(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(DriveCfg.url(ctx)) }
    var folder by remember { mutableStateOf(DriveCfg.folder(ctx)) }
    var token by remember { mutableStateOf(DriveCfg.token(ctx)) }
    var msg by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        try {
            if (CloudSettings.pull(ctx, prefs)) {
                url = DriveCfg.url(ctx)
                folder = DriveCfg.folder(ctx)
                token = DriveCfg.token(ctx)
                msg = "Настройки загружены из аккаунта «${Session.login}»"
            }
        } catch (e: Exception) {
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Google Диск", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Фото и накладные. Настройки хранятся в аккаунте «${Session.login}» в Firebase.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(url, { url = it }, label = { Text("Адрес скрипта (…/exec)") },
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(folder, { folder = it }, label = { Text("ID папки или ссылка на папку") },
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(token, { token = it }, label = { Text("Токен (если задан в скрипте)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            DriveCfg.save(ctx, url, folder, token)
            folder = DriveCfg.folder(ctx)
            CloudSettings.save(ctx, prefs)
            msg = "Проверка…"
            scope.launch {
                msg = try {
                    val n = withContext(Dispatchers.IO) { DriveApi.ping(ctx) }
                    Media.uploadPending(ctx, ProductDb.get(ctx))
                    DriveSync.start(ctx, true)
                    "Подключено, папка: $n. Настройки сохранены в аккаунте."
                } catch (e: Exception) {
                    "Настройки сохранены, но Диск не отвечает: ${e.message}"
                }
            }
        }) { Text("Сохранить и проверить") }
        if (msg.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(msg, style = MaterialTheme.typography.bodySmall)
        }
        var queued by remember { mutableIntStateOf(0) }
        LaunchedEffect(Media.version, msg) {
            queued = withContext(Dispatchers.IO) { ProductDb.get(ctx).pendingUploads().size }
        }
        Spacer(Modifier.height(8.dp))
        Text("Файлов в очереди на отправку: $queued", style = MaterialTheme.typography.bodySmall)
        if (Media.lastError.isNotBlank()) {
            Text(Media.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (queued > 0) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { Media.uploadPending(ctx, ProductDb.get(ctx)) }) { Text("Отправить файлы сейчас") }
        }
    }
}

@Composable
private fun AiSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val labels = listOf("Claude (Anthropic)", "Gemini (Google)", "OpenAI-совместимый")
    val keys = listOf("claude", "gemini", "openai")
    var provider by remember { mutableStateOf(AiCfg.provider(ctx)) }
    var key by remember { mutableStateOf(AiCfg.key(ctx)) }
    var model by remember { mutableStateOf(AiCfg.rawModel(ctx)) }
    var base by remember { mutableStateOf(AiCfg.baseUrl(ctx)) }
    var timeout by remember { mutableStateOf(AiCfg.timeoutSec(ctx).toString()) }
    var attempts by remember { mutableStateOf(AiCfg.attempts(ctx).toString()) }
    var msg by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)

    fun save() {
        AiCfg.save(
            ctx, provider, key, model, base,
            timeout.toIntOrNull()?.coerceIn(5, 600) ?: 60,
            attempts.toIntOrNull()?.coerceIn(1, 8) ?: 3
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Распознавание документов", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Ключ хранится только на этом телефоне и в Firebase не отправляется.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        Text("Провайдер ИИ", style = MaterialTheme.typography.titleSmall)
        DropdownField(labels[keys.indexOf(provider).coerceAtLeast(0)], labels) { l ->
            provider = keys[labels.indexOf(l)]
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            key, { key = it }, label = { Text("API-ключ") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            model, { model = it }, singleLine = true,
            label = { Text("Модель (по умолчанию: ${AiCfg.defaultModel(provider)})") },
            modifier = Modifier.fillMaxWidth()
        )
        if (provider == "openai") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                base, { base = it }, singleLine = true, label = { Text("Адрес API (…/v1)") },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                timeout, { timeout = it.filter { c -> c.isDigit() } }, singleLine = true,
                label = { Text("Ожидание листа, сек") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                attempts, { attempts = it.filter { c -> c.isDigit() } }, singleLine = true,
                label = { Text("Попыток на лист") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save(); msg = "Сохранено" }) { Text("Сохранить") }
            OutlinedButton(onClick = {
                save()
                msg = "Проверка…"
                scope.launch {
                    msg = try {
                        val r = withContext(Dispatchers.IO) { Ai.call(ctx, "Reply with the single word OK", null) }
                        "ИИ отвечает: " + r.trim().take(60)
                    } catch (e: Exception) {
                        "Ошибка: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            }) { Text("Проверить") }
        }
        if (msg.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(msg, style = MaterialTheme.typography.bodySmall)
        }
    }
}
