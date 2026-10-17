package ru.storage.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    override fun onStop() {
        // приложение уходит в фон (звонок, другое приложение, клавиатура другого окна): черновики сразу на диск
        Incoming.persistOpen(applicationContext)
        kotlin.concurrent.thread { BWareDraft.flushAll() }
        kotlin.concurrent.thread { AutoReceiveHolder.flushDrafts() }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // списки «Приём по имени» / «Автоприём», которые не успели сохранить, возвращаются с телефона
        try { AutoReceiveHolder.restore(applicationContext) } catch (e: Exception) { }
        // открытая партия возвращается на экран, даже если систему выгрузила приложение из памяти
        try { Incoming.restoreOpen(applicationContext) } catch (e: Exception) { }
        if (savedInstanceState == null) Incoming.handleIntent(this, intent)
        Nav.target = intent?.getStringExtra("open")
        Journal.init(applicationContext)
        Ui.load(applicationContext)
        PrintCfg.load(applicationContext)
        ScreenCfg.load(applicationContext)
        DateCfg.load(applicationContext)
        Speaker.init(applicationContext)
        setContent {
            AppTheme { AppRoot() }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (BtScan.onKey(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Incoming.handleIntent(this, intent)
        intent.getStringExtra("open")?.let { Nav.target = it }
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
    val email: String,
    val position: String = ""
)

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    // вход запоминается до выхода из аккаунта (кнопка в Профиле)
    val sessionSp = remember { context.getSharedPreferences("session", Context.MODE_PRIVATE) }
    fun loadUser(): AppUser? {
        val l = sessionSp.getString("login", "") ?: ""
        if (l.isEmpty() || !sessionSp.contains("status")) return null
        // сессия без аккаунта Firebase Auth (вход старой версией) больше не действует — нужен новый вход
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) return null
        // сессия администратора со старой схемой входа (зашитый логин) больше не действует — нужен вход по Email
        if (sessionSp.getString("role", "") == "admin" && sessionSp.getInt("v", 0) < 2) return null
        return AppUser(
            l, sessionSp.getString("name", "") ?: "", sessionSp.getString("position", "") ?: "",
            sessionSp.getString("role", "worker") ?: "worker", sessionSp.getString("status", "pending") ?: "pending",
            sess = sessionSp.getLong("sess", 0L)
        )
    }
    var user by remember { mutableStateOf(loadUser()) }
    fun applyUser(u: AppUser?) {
        user = u
        Session.user = u
        Session.login = when {
            u == null -> "admin"
            u.isAdmin -> "admin" // прежний ключ: настройки и журнал администратора сохраняются
            else -> u.login
        }
    }
    fun saveUser(u: AppUser?) {
        val e = sessionSp.edit()
        if (u == null) {
            e.clear()
            try { com.google.firebase.auth.FirebaseAuth.getInstance().signOut() } catch (ex: Exception) { }
        } else {
            e.putString("login", u.login).putString("name", u.name).putString("position", u.position)
                .putString("role", u.role).putString("status", u.status).putInt("v", 2).putLong("sess", u.sess)
        }
        e.apply()
        applyUser(u)
    }
    remember {
        Session.user = user
        Session.login = user?.let { if (it.isAdmin) "admin" else it.login } ?: "admin"
        0
    }

    // администратор поменял своё имя в профиле — обновляем сохранённого пользователя
    SideEffect { Session.onUserUpdated = { u -> saveUser(u) } }

    val current = user
    val loggedIn = current != null && current.status == "active"
    val profile = remember(current) {
        val n = current?.name ?: ""
        UserProfile(n.substringBefore(' '), n.substringAfter(' ', ""), "", "", current?.position ?: "")
    }

    // после входа сверяем настройки аккаунта с Firebase
    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            try {
                CloudSettings.pull(context, prefs)
            } catch (e: Exception) {
            }
        }
    }

    // чат, задачи, уведомления: подписки работают, пока пользователь вошёл
    DisposableEffect(current?.login, loggedIn) {
        if (loggedIn && current != null) Live.start(context, current)
        onDispose { if (loggedIn) Live.stop() }
    }
    // изменения в профиле (должность) и удаление аккаунта подхватываем из Firebase
    DisposableEffect(current?.login, loggedIn) {
        var reg: com.google.firebase.firestore.ListenerRegistration? = null
        if (loggedIn && current != null && !current.isAdmin) {
            reg = Users.listenUser(current.login) { u, fromCache ->
                if (u == null) {
                    if (!fromCache) saveUser(null)
                } else if (!fromCache && (u.blocked || u.sess != current.sess)) {
                    // администратор заблокировал аккаунт или завершил сеанс
                    Toast.makeText(
                        context,
                        if (u.blocked) "Доступ заблокирован администратором" else "Администратор завершил ваш сеанс",
                        Toast.LENGTH_LONG
                    ).show()
                    saveUser(null)
                } else if (u.status == "active" && (u.name != current.name || u.position != current.position)) {
                    saveUser(u)
                }
            }
        }
        onDispose { reg?.remove() }
    }
    // разрешение на уведомления (Android 13+)
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(loggedIn) {
        if (loggedIn && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // после верного пароля: сначала анимация закрывает экран входа, и только потом открывается главный экран
    var splash by remember { mutableStateOf(false) }
    var pendingUser by remember { mutableStateOf<AppUser?>(null) }
    Box(Modifier.fillMaxSize()) {
    if (loggedIn) {
        val receiver = "${profile.firstName} ${profile.lastName}".trim()
        AutoReceiveHolder.receiver = receiver
        MainScreen(prefs = prefs, profile = profile, onLogout = {
            Journal.log("Система", "Выход из аккаунта")
            saveUser(null)
        })
        Incoming.pending.firstOrNull()?.let { IncomingChoiceDialog(it) }
    } else if (current != null) {
        // зарегистрировался, но админ ещё не назначил должность и не подтвердил
        PendingScreen(
            current,
            onApproved = { u -> pendingUser = u; splash = true },
            onLogout = { saveUser(null) }
        )
    } else {
        LoginScreen(onSuccess = { u ->
            if (u.status == "active") {
                pendingUser = u
                splash = true
            } else {
                saveUser(u)
            }
        })
    }
    if (splash) {
        WelcomeSplash(
            onCovered = {
                pendingUser?.let {
                    saveUser(it)
                    Journal.log("Система", "Вход в аккаунт")
                }
            },
            onFinished = { splash = false }
        )
    }
    }
}

private data class Tab(val title: String, val icon: ImageVector)

/** Какие из четырёх главных вкладок сейчас на своём первом экране. Свайп между вкладками работает только там. */
object TabRoot {
    val flags = mutableStateListOf(true, true, true, true)
    fun set(i: Int, root: Boolean) { if (flags[i] != root) flags[i] = root }
}

@Composable
fun MainScreen(prefs: Prefs, profile: UserProfile, onLogout: () -> Unit) {
    val tabs = listOf(
        Tab("Главная", Icons.Default.Home),
        Tab("Работа", Icons.Default.Add),
        Tab("Прочее", Icons.Default.Menu),
        Tab("Еще", Icons.Default.Settings)
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    // у каждой вкладки свой цвет фона (у «Главной» — собственный синий); счётчик сбрасывает вкладку к корню
    val accents = listOf<Color?>(null, Color(0xFF1FA971), Color(0xFFF08A24), Color(0xFF8E5BD6))
    val resets = remember { mutableStateListOf(0, 0, 0, 0) }
    /** Открыть вкладку из любого места: свёрнутая партия закрывается (остаётся в работе), вкладка открывается с начала. */
    fun go(i: Int) {
        Incoming.openPath = null
        resets[i] = resets[i] + 1
        selected = i
    }
    val swipePx = with(LocalDensity.current) { 90.dp.toPx() }
    LaunchedEffect(Nav.target) { if (Nav.target != null) selected = 0 }
    LaunchedEffect(Unit) { WorkTracker.ensureService(ctx) }
    // общие настройки рабочего времени от администратора подтягиваются на этот телефон
    LaunchedEffect(Unit) {
        while (true) {
            try { WorkCloud.pull(ctx) } catch (e: Exception) { }
            delay(10 * 60_000L)
        }
    }
    LaunchedEffect(Unit) {
        delay(20_000)
        withContext(Dispatchers.IO) { try { Updater.auto(ctx) } catch (e: Exception) { } }
    }
    LaunchedEffect(Unit) {
        BtScan.load(ctx)
        withContext(Dispatchers.IO) { try { BtScan.autoConnect(ctx) } catch (e: Exception) { } }
    }
    // папки автоприёма: новые PDF и картинки сами уходят в очередь на распознавание
    LaunchedEffect(Unit) {
        delay(5000)
        while (true) {
            withContext(Dispatchers.IO) {
                try {
                    Folders.load(ctx)
                    if (Folders.auto && Folders.folders.isNotEmpty()) Folders.scanAll(ctx, true)
                } catch (e: Exception) {
                }
            }
            delay(120_000L)
        }
    }
    // снимки этикеток Post paket живут на телефоне двое суток; незаконченный анализ этикеток добирается
    // журнал и документы: отправляем на Диск то, что накопилось без связи
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                Journal.flush(ctx)
                DocStore.flush(ctx)
            } catch (e: Exception) {
            }
        }
    }
    LaunchedEffect(Unit) {
        LabelProcessor.resume(ctx)
        while (true) {
            withContext(Dispatchers.IO) { try { SendRetention.sweep(ctx) } catch (e: Exception) { } }
            delay(60 * 60 * 1000L)
        }
    }
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

    // «Приём по имени»: при наборе меню остаётся на месте — его закрывает клавиатура, а не поднимает над ней
    val hideBar = KeyboardState.open && Incoming.openPath != null
    Scaffold(
        bottomBar = {
            if (!hideBar) NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { go(i) },
                        icon = {
                            if (i == 1) {
                                val n = AutoReceiveHolder.jobs.count { !it.saved }
                                BadgedBox(badge = { if (n > 0) Badge { Text(n.toString()) } }) {
                                    Icon(tab.icon, contentDescription = tab.title)
                                }
                            } else if (i == 2) {
                                val n = InvoiceProfiles.newCount
                                BadgedBox(badge = { if (n > 0) Badge { Text(n.toString()) } }) {
                                    Icon(tab.icon, contentDescription = tab.title)
                                }
                            } else if (i == 3 && Updater.latestNewer != null) {
                                BadgedBox(badge = { Badge() }) {
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
        Box(
            modifier = Modifier.fillMaxSize().padding(padding)
                // свайп справа налево — следующее окно, слева направо — предыдущее; строки со своим свайпом его не запускают
                .pointerInput(selected) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragCancel = { total = 0f },
                        onDragEnd = {
                            // только на первом экране каждой из четырёх вкладок
                            val canSwipe = TabRoot.flags[selected] && Incoming.openPath == null
                            if (canSwipe && total <= -swipePx && selected < tabs.lastIndex) go(selected + 1)
                            else if (canSwipe && total >= swipePx && selected > 0) go(selected - 1)
                            total = 0f
                        },
                        onHorizontalDrag = { _, dx -> total += dx }
                    )
                }
        ) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(220)) { w -> w * dir / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(220)) { w -> -w * dir / 3 } + fadeOut(tween(120)))
                },
                label = "tabs"
            ) { tab ->
                val accent = accents[tab]
                Box(
                    Modifier.fillMaxSize().then(
                        if (accent == null) Modifier else Modifier.background(
                            Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), Color.Transparent), endY = 900f)
                        )
                    )
                ) {
                    key(resets[tab]) {
                        val openJob = Incoming.openPath?.let { AutoReceiveHolder.find(it) }
                        if (openJob != null && tab == selected) {
                            // свёрнутые партии остаются в AutoReceiveHolder и продолжают работать
                            AutoReceiveScreen(
                                openJob.file, "${profile.firstName} ${profile.lastName}".trim(),
                                if (openJob.nameMode) "name" else "auto"
                            ) { Incoming.openPath = null }
                        } else when (tab) {
                            0 -> HomeScreen(prefs, profile.firstName) { go(it) }
                            1 -> ReceiveScreen(prefs, "${profile.firstName} ${profile.lastName}".trim())
                            2 -> OtherScreen(prefs)
                            else -> MoreScreen(profile, prefs, onLogout)
                        }
                    }
                }
            }
        }
    }
}
