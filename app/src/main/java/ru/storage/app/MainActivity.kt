package ru.storage.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    var loggedIn by rememberSaveable { mutableStateOf(false) }
    if (loggedIn) MainScreen(onLogout = { loggedIn = false })
    else LoginScreen(onSuccess = { loggedIn = true })
}

@Composable
fun LoginScreen(onSuccess: () -> Unit) {
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Lager", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = login,
            onValueChange = { login = it },
            label = { Text("Логин") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (login.trim() == "admin" && password == "admin") onSuccess()
                else error = "Неверный логин или пароль"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Войти") }
    }
}

private data class Tab(val title: String, val icon: ImageVector)

@Composable
fun MainScreen(onLogout: () -> Unit) {
    val tabs = listOf(
        Tab("Приём", Icons.Default.Add),
        Tab("Инвентарь", Icons.AutoMirrored.Filled.List),
        Tab("Отправка", Icons.AutoMirrored.Filled.Send),
        Tab("Настройки", Icons.Default.Settings)
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when (selected) {
                0 -> Text("Приём", style = MaterialTheme.typography.headlineMedium)
                1 -> Text("Инвентарь", style = MaterialTheme.typography.headlineMedium)
                2 -> Text("Отправка", style = MaterialTheme.typography.headlineMedium)
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Настройки", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onLogout) { Text("Выйти") }
                }
            }
        }
    }
}
