package ru.storage.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
        if (savedInstanceState == null) Incoming.handleIntent(this, intent)
        Ui.load(applicationContext)
        setContent {
            AppTheme { AppRoot() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Incoming.handleIntent(this, intent)
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
    // вход запоминается до выхода из аккаунта (кнопка в Профиле)
    val sessionSp = remember { context.getSharedPreferences("session", Context.MODE_PRIVATE) }
    var userLogin by rememberSaveable { mutableStateOf(sessionSp.getString("login", "") ?: "") }
    var loggedIn by rememberSaveable { mutableStateOf(userLogin.isNotEmpty()) }
    Session.login = userLogin.ifBlank { "admin" }
    // после входа сверяем настройки аккаунта с Firebase
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            try {
                CloudSettings.pull(context, prefs)
            } catch (e: Exception) {
            }
        }
    }
    if (loggedIn) {
        val receiver = "${profile.firstName} ${profile.lastName}".trim()
        val openJob = Incoming.openPath?.let { AutoReceiveHolder.find(it) }
        if (openJob != null) {
            // свёрнутые партии остаются в AutoReceiveHolder и продолжают работать
            AutoReceiveScreen(
                openJob.file, receiver, if (openJob.nameMode) "name" else "auto"
            ) { Incoming.openPath = null }
        } else {
            MainScreen(prefs = prefs, profile = profile, onLogout = {
                sessionSp.edit().remove("login").apply()
                loggedIn = false
            })
            Incoming.pending.firstOrNull()?.let { IncomingChoiceDialog(it) }
        }
    } else {
        LoginScreen(onSuccess = {
            val l = it.lowercase()
            sessionSp.edit().putString("login", l).apply()
            userLogin = l
            loggedIn = true
        })
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
    val ctx = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }
    // Системная «Назад»: с других вкладок — на главную; с главной — выход только двойным нажатием
    BackHandler {
        if (selected != 0) {
            selected = 0
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBack < 2000) {
                var c: Context? = ctx
                while (c is ContextWrapper && c !is Activity) c = c.baseContext
                (c as? Activity)?.finish()
            } else {
                lastBack = now
                Toast.makeText(ctx, "Нажмите ещё раз для выхода", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = {
                            if (i == 1) {
                                val n = AutoReceiveHolder.jobs.count { !it.saved }
                                BadgedBox(badge = { if (n > 0) Badge { Text(n.toString()) } }) {
                                    Icon(tab.icon, contentDescription = tab.title)
                                }
                            } else Icon(tab.icon, contentDescription = tab.title)
                        },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selected) {
                0 -> HomeScreen(prefs)
                1 -> ReceiveScreen(prefs, "${profile.firstName} ${profile.lastName}".trim())
                2 -> Placeholder("Отправка")
                else -> ProfileScreen(profile, prefs, onLogout)
            }
        }
    }
}
