package ru.storage.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.concurrent.thread
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

private val homeSections = listOf(
    "Поиск",
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
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val current = section

    when {
        current == "Поиск" -> SearchScreen(prefs) { section = null }
        current == "Каталог товаров" -> CatalogScreen(prefs) { section = null }
        current == "История" -> HistoryScreen { section = null }
        current != null -> SectionScreen(current) { section = null }
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
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
fun ReceiveScreen(prefs: Prefs, receiver: String) {
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    val current = mode
    val ctx = LocalContext.current
    var pickMode by remember { mutableStateOf("auto") }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val m = pickMode
        if (uri != null) thread { Incoming.accept(ctx, uri, m) }
    }

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
                onClick = {
                    when (name) {
                        "Автоприём" -> {
                            pickMode = "auto"
                            pdfPicker.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                        }
                        "По наименованию" -> {
                            pickMode = "name"
                            pdfPicker.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                        }
                        else -> mode = name
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) { Text(name) }
        }
    }
}
