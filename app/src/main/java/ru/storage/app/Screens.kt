package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

private val homeSections = listOf(
    "История",
    "Инвентаризация",
    "Приём испорченных товаров",
    "Инвентаризация испорченных товаров",
    "Каталог товаров",
    "Задачи",
    "Инфо",
    "Чат"
)

@Composable
fun HomeScreen(prefs: Prefs) {
    var query by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val current = section

    when {
        scanning -> ScannerScreen(
            prefs = prefs,
            onResult = { code ->
                query = code
                scanning = false
            },
            onClose = { scanning = false }
        )
        current == "Каталог товаров" -> CatalogScreen(prefs) { section = null }
        current == "История" -> HistoryScreen { section = null }
        current != null -> SectionScreen(current) { section = null }
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { scanning = true }) { Text("📷 Скан") }
            }
            Spacer(Modifier.height(16.dp))
            homeSections.forEach { name ->
                FilledTonalButton(
                    onClick = { section = name },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) { Text(name) }
            }
        }
    }
}

@Composable
fun SectionScreen(title: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Назад") }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Окно в разработке")
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ProfileScreen(profile: UserProfile, prefs: Prefs, onLogout: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(prefs) { showSettings = false }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Профиль", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        ProfileRow("Имя", profile.firstName)
        ProfileRow("Фамилия", profile.lastName)
        ProfileRow("Номер телефона", profile.phone)
        ProfileRow("Электронная почта", profile.email)
        ProfileRow("Пароль", "••••••••")
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(
            onClick = { showSettings = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Настройки") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Выйти") }
    }
}

@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit) {
    var front by remember { mutableStateOf(prefs.useFront) }
    var formats by remember { mutableStateOf(prefs.formats) }
    BackHandler(onBack = onBack)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("← Назад") }
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Text("Камера для сканера", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !front, onClick = { front = false; prefs.useFront = false })
            Text("Основная")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = front, onClick = { front = true; prefs.useFront = true })
            Text("Фронтальная")
        }

        Spacer(Modifier.height(16.dp))
        Text("Типы кодов", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = formats.isEmpty(),
                onCheckedChange = { formats = emptySet(); prefs.formats = emptySet() }
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
                    }
                )
                Text(name)
            }
        }
    }
}

@Composable
fun ReceiveScreen(prefs: Prefs, receiver: String) {
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    val current = mode

    if (current == "Ручной") {
        ManualReceiveScreen(prefs, receiver) { mode = null }
        return
    }
    if (current != null) {
        SectionScreen(current) { mode = null }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Приём", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        listOf("Ручной", "По наименованию", "Автоприём").forEach { name ->
            FilledTonalButton(
                onClick = { mode = name },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text(name) }
        }
    }
}
