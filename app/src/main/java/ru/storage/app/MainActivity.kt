package ru.storage.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var useFront: Boolean
        get() = sp.getBoolean("front", false)
        set(v) { sp.edit().putBoolean("front", v).apply() }

    var formats: Set<Int>
        get() = (sp.getStringSet("formats", emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }.toSet()
        set(v) { sp.edit().putStringSet("formats", v.map { it.toString() }.toSet()).apply() }
}

data class UserProfile(
    val firstName: String,
    val lastName: String,
    val phone: String,
    val email: String
)

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val profile = remember { UserProfile("Admin", "", "", "") }
    var loggedIn by rememberSaveable { mutableStateOf(false) }
    if (loggedIn) MainScreen(prefs = prefs, profile = profile, onLogout = { loggedIn = false })
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
fun MainScreen(prefs: Prefs, profile: UserProfile, onLogout: () -> Unit) {
    val tabs = listOf(
        Tab("Главная", Icons.Default.Home),
        Tab("Приём", Icons.Default.Add),
        Tab("Отправка", Icons.AutoMirrored.Filled.Send),
        Tab("Профиль", Icons.Default.Person)
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> HomeScreen(prefs)
                1 -> ReceiveScreen(prefs)
                2 -> Placeholder("Отправка")
                else -> ProfileScreen(profile, prefs, onLogout)
            }
        }
    }
}
