package ru.storage.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * Какие версии приложения могут работать с базой — решает администратор (окно «Обновление»).
 *  1) Firestore config/app: {minVersion, minBuild, blockedVersions[], blockedBuilds[], latestVersion, enforce}.
 *     Версия разрешена, если она не ниже minVersion и не в списке blocked. Первый раз minVersion = версия админа.
 *  2) Каждое приложение при запуске записывает свою версию в users/{логин}: appVer, appBuild
 *     (по ним правила Firestore закрывают данные для старых версий — см. firebase/firestore.rules).
 *  3) Если версия на телефоне не разрешена и enforce = true — поверх приложения экран «Обновите приложение».
 */

class VerCfg(
    /** Самая старая разрешённая версия. */
    val min: String,
    val enforce: Boolean,
    val exists: Boolean = true,
    /** Закрытые версии (выше min, но администратор их запретил). */
    val blocked: Set<String> = emptySet(),
    /** Самая новая версия, которую запускал администратор. */
    val latest: String = ""
)

/** Версия приложения у одного работника (из users/{логин}.appVer; пусто — приложение старое и версию не пишет). */
class UserVer(val login: String, val name: String, val ver: String, val seen: Long)

private suspend fun <T> Task<T>.awaitV(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { if (c.isActive) c.resume(it) }
    addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
}

object VerGuard {
    /** true, пока закрыто экраном «Обновите приложение» (сканер не принимает ввод). */
    @Volatile var isBlocked = false

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("ver_gate", Context.MODE_PRIVATE)
    private val fs get() = FirebaseFirestore.getInstance()

    /** 2.29.0 → 22900: число для правил Firestore (у versionCode другой смысл — это минуты сборки). */
    fun build(v: String): Long {
        val p = v.split('.').map { it.toIntOrNull() ?: 0 }
        return p.getOrElse(0) { 0 } * 10000L + p.getOrElse(1) { 0 } * 100L + p.getOrElse(2) { 0 }
    }

    fun cachedCfg(ctx: Context): VerCfg? {
        val sp = sp(ctx)
        if (!sp.contains("min")) return null
        return VerCfg(
            sp.getString("min", "") ?: "", sp.getBoolean("enforce", false), true,
            (sp.getString("blocked", "") ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            sp.getString("latest", "") ?: ""
        )
    }

    private fun cache(ctx: Context, c: VerCfg) {
        sp(ctx).edit().putString("min", c.min).putBoolean("enforce", c.enforce)
            .putString("blocked", c.blocked.joinToString(",")).putString("latest", c.latest).apply()
    }

    /** Может ли версия v работать с базой по настройкам администратора. */
    fun allowed(c: VerCfg, v: String): Boolean =
        (c.min.isBlank() || Updater.cmp(v, c.min) >= 0) && v !in c.blocked

    fun isOutdated(ctx: Context, c: VerCfg?): Boolean =
        c != null && c.enforce && !allowed(c, Updater.current(ctx))

    fun cachedBlock(ctx: Context): VerCfg? = cachedCfg(ctx)?.takeIf { isOutdated(ctx, it) }

    fun reported(ctx: Context, login: String): Boolean =
        sp(ctx).getString("rep_${login.lowercase()}", "") == Updater.current(ctx)

    /** Записывает версию этого телефона в профиль (только когда она изменилась). */
    suspend fun report(ctx: Context, login: String): Boolean {
        if (reported(ctx, login)) return true
        val cur = Updater.current(ctx)
        return try {
            val ok = withTimeoutOrNull(8_000L) {
                fs.collection("users").document(login)
                    .update(mapOf("appVer" to cur, "appBuild" to build(cur))).awaitV()
                true
            } ?: false
            if (ok) sp(ctx).edit().putString("rep_${login.lowercase()}", cur).apply()
            ok
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchCfg(ctx: Context): VerCfg? = try {
        val snap = withTimeoutOrNull(8_000L) {
            fs.collection("config").document("app").get(Source.SERVER).awaitV()
        }
        if (snap == null) null else {
            val c = if (snap.exists()) VerCfg(
                snap.getString("minVersion") ?: snap.getString("requiredVersion") ?: "",
                snap.getBoolean("enforce") ?: true, true,
                (snap.get("blockedVersions") as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                snap.getString("latestVersion") ?: ""
            ) else VerCfg("", false, false)
            cache(ctx, c)
            c
        }
    } catch (e: Exception) {
        null
    }

    /** Возвращает настройки, если эта версия закрыта; null — работать можно. */
    suspend fun check(ctx: Context, user: AppUser): VerCfg? {
        val app = ctx.applicationContext
        report(app, user.login)
        var cfg = fetchCfg(app)
        if (cfg != null && user.isAdmin) {
            val cur = Updater.current(app)
            val first = !cfg.exists || cfg.min.isBlank()
            val newLatest = cfg.latest.isBlank() || Updater.cmp(cur, cfg.latest) > 0
            if (first || newLatest) {
                val enforce = if (!cfg.exists) true else cfg.enforce
                val data = mutableMapOf<String, Any>(
                    "enforce" to enforce, "updatedAt" to System.currentTimeMillis(), "updatedBy" to user.login
                )
                if (newLatest) data["latestVersion"] = cur
                if (first) { data["minVersion"] = cur; data["minBuild"] = build(cur) }
                try {
                    withTimeoutOrNull(8_000L) {
                        fs.collection("config").document("app").set(data, SetOptions.merge()).awaitV()
                    }
                    cfg = VerCfg(if (first) cur else cfg.min, enforce, true, cfg.blocked, if (newLatest) cur else cfg.latest)
                    cache(app, cfg)
                    if (first) try { Journal.log("Система", "Разрешённые версии приложения: с $cur") } catch (e: Exception) { }
                } catch (e: Exception) {
                }
            }
        }
        val c = cfg ?: cachedCfg(app)
        return if (isOutdated(app, c)) c else null
    }

    suspend fun setEnforce(ctx: Context, on: Boolean) {
        withTimeoutOrNull(8_000L) {
            fs.collection("config").document("app").set(
                mapOf("enforce" to on, "updatedAt" to System.currentTimeMillis()), SetOptions.merge()
            ).awaitV()
        }
        val old = cachedCfg(ctx)
        cache(ctx, VerCfg(old?.min ?: "", on, true, old?.blocked ?: emptySet(), old?.latest ?: ""))
        try { Journal.log("Система", if (on) "Старые версии приложения блокируются" else "Блокировка старых версий выключена") } catch (e: Exception) { }
    }

    /** Какие версии стоят у работников (по одному чтению на человека). null — нет связи. */
    suspend fun loadUserVersions(): List<UserVer>? = try {
        val snap = withTimeoutOrNull(10_000L) { fs.collection("users").get(Source.SERVER).awaitV() }
        snap?.documents
            ?.filter { (it.getString("status") ?: "") == "active" && it.getBoolean("blocked") != true }
            ?.map {
                UserVer(
                    it.id, it.getString("name") ?: it.id, it.getString("appVer") ?: "",
                    it.getTimestamp("seenAt")?.toDate()?.time ?: 0L
                )
            }
    } catch (e: Exception) {
        null
    }

    /** Из выбора администратора (какие из известных версий разрешены) получает min и список закрытых. */
    fun policyFrom(known: List<String>, allowedSet: Set<String>, c: VerCfg): VerCfg {
        val min = allowedSet.minWithOrNull { x, y -> Updater.cmp(x, y) } ?: c.min
        val blocked = HashSet<String>()
        known.forEach { if (Updater.cmp(it, min) > 0 && it !in allowedSet) blocked.add(it) }
        c.blocked.forEach { if (it !in known && Updater.cmp(it, min) > 0 && it !in allowedSet) blocked.add(it) }
        return VerCfg(min, c.enforce, true, blocked, c.latest)
    }

    suspend fun savePolicy(ctx: Context, c: VerCfg) {
        withTimeoutOrNull(8_000L) {
            fs.collection("config").document("app").set(
                mapOf(
                    "minVersion" to c.min, "minBuild" to build(c.min),
                    "blockedVersions" to c.blocked.toList(), "blockedBuilds" to c.blocked.map { build(it) },
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).awaitV()
        }
        cache(ctx, c)
        try { Journal.log("Система", "Разрешённые версии: с ${c.min}" + if (c.blocked.isEmpty()) "" else ", закрыты: ${c.blocked.joinToString(", ")}") } catch (e: Exception) { }
    }
}

/** Оборачивает главный экран: старая версия закрывается экраном «Обновите приложение». */
@Composable
fun VersionGate(user: AppUser, onLogout: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var blocked by remember(user.login) { mutableStateOf(VerGuard.cachedBlock(ctx)) }
    var ready by remember(user.login) { mutableStateOf(VerGuard.reported(ctx, user.login)) }
    var recheck by remember { mutableIntStateOf(0) }

    LaunchedEffect(user.login, recheck, AppGuard.resumeTick) {
        while (true) {
            blocked = try { VerGuard.check(ctx, user) } catch (e: Exception) { VerGuard.cachedBlock(ctx) }
            ready = true
            delay(10 * 60_000L)
        }
    }
    SideEffect { VerGuard.isBlocked = blocked != null || !ready }
    DisposableEffect(Unit) { onDispose { VerGuard.isBlocked = false } }

    Box(Modifier.fillMaxSize()) {
        if (ready) content()
        val b = blocked
        if (!ready) {
            Surface(Modifier.fillMaxSize().pointerInput(Unit) { }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        } else if (b != null) {
            Surface(Modifier.fillMaxSize().pointerInput(Unit) { }) {
                OutdatedScreen(b, onRetry = { recheck++ }, onLogout = onLogout)
            }
        }
    }
}

@Composable
fun OutdatedScreen(cfg: VerCfg, onRetry: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val cur = remember { Updater.current(ctx) }
    LaunchedEffect(Unit) { if (!Updater.busy) Updater.check(ctx) }
    val target = Updater.versions.filter { Updater.cmp(it.version, cur) > 0 && VerGuard.allowed(cfg, it.version) }
        .maxWithOrNull { a, b -> Updater.cmp(a.version, b.version) }
    BackHandler { }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("⬆️", fontSize = 72.sp)
        Spacer(Modifier.height(16.dp))
        Text("Нужно обновить приложение", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Версия $cur закрыта администратором (работать можно с ${cfg.min.ifBlank { "последней" }} и новее). " +
                "Пока версия не обновлена, работа с данными закрыта, чтобы не испортить базу.",
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        if (target != null) {
            Button(onClick = { Updater.install(ctx, target) }, enabled = !Updater.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Скачать и установить ${target.version}")
            }
        } else {
            Text(
                "Новая версия пока не найдена на Google Диске. Попросите администратора открыть приложение по Wi-Fi.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
        }
        if (Updater.status.isNotEmpty()) {
            Text(Updater.status, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
        if (Updater.busy && Updater.progress > 0f) {
            LinearProgressIndicator(progress = { Updater.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { Updater.check(ctx); onRetry() },
            enabled = !Updater.busy, modifier = Modifier.fillMaxWidth()
        ) { Text("Проверить снова") }
        TextButton(onClick = onLogout) { Text("Выйти из аккаунта") }
    }
}

private val verDateFmt = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())

/** Только для администратора (окно «Обновление»): какие версии работают с базой и какие лежат на Диске. */
@Composable
fun VersionManager() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cur = remember { Updater.current(ctx) }
    var cfg by remember { mutableStateOf(VerGuard.cachedCfg(ctx) ?: VerCfg("", false, false)) }
    var askDelete by remember { mutableStateOf<AppVersion?>(null) }
    LaunchedEffect(Unit) { VerGuard.fetchCfg(ctx)?.let { cfg = it } }
    var users by remember { mutableStateOf<List<UserVer>?>(null) }
    var usersBusy by remember { mutableStateOf(false) }
    fun loadUsers() {
        if (usersBusy) return
        usersBusy = true
        scope.launch {
            val r = VerGuard.loadUserVersions()
            if (r != null) users = r
            usersBusy = false
        }
    }
    LaunchedEffect(Unit) { loadUsers() }

    val known = (Updater.versions.map { it.version } + cur + (users?.map { it.ver }?.filter { it.isNotBlank() } ?: emptyList())).distinct().sortedWith { a, b -> Updater.cmp(b, a) }
    fun toggle(v: String, on: Boolean) {
        val a = known.filter { VerGuard.allowed(cfg, it) }.toMutableSet()
        if (on) a.add(v) else a.remove(v)
        a.add(cur)
        val n = VerGuard.policyFrom(known, a, cfg)
        cfg = n
        scope.launch(Dispatchers.IO) { try { VerGuard.savePolicy(ctx, n) } catch (e: Exception) { } }
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Закрывать неразрешённые версии", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Включено: с базой работают только разрешённые версии, остальным показывается «Обновите приложение». " +
                    "Выключено: ограничений нет.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = cfg.enforce,
            onCheckedChange = { on ->
                cfg = VerCfg(cfg.min, on, cfg.exists, cfg.blocked, cfg.latest)
                scope.launch(Dispatchers.IO) { try { VerGuard.setEnforce(ctx, on) } catch (e: Exception) { } }
            }
        )
    }
    Spacer(Modifier.height(8.dp))
    Text("Версии", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Text(
        "Переключатель: можно ли этой версии работать с базой. Версии ниже самой старой разрешённой закрыты сами. " +
            "Кнопка «Удалить» убирает версию с Google Диска (из приложения).",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    known.forEach { v ->
        val onDrive = Updater.versions.firstOrNull { it.version == v }
        val ok = VerGuard.allowed(cfg, v)
        val mark = when {
            v == cur -> "установлена"
            Updater.cmp(v, cur) > 0 -> "новее"
            else -> "старее"
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Версия $v · $mark", style = MaterialTheme.typography.bodyLarge)
                val cnt = users?.count { it.ver == v } ?: 0
                Text(
                    (if (ok) "Можно работать" else "Закрыта") + (if (cnt > 0) " · у пользователей: $cnt" else "") + " · " +
                        (onDrive?.let {
                            listOfNotNull(
                                verDateFmt.format(java.util.Date(it.time)).takeIf { _ -> it.time > 0 },
                                String.format(java.util.Locale.getDefault(), "%.1f МБ", it.size / 1048576.0)
                            ).joinToString(" · ")
                        } ?: "нет на Диске"),
                    style = MaterialTheme.typography.bodySmall
                )
                if (onDrive != null) {
                    TextButton(
                        onClick = { askDelete = onDrive }, enabled = !Updater.busy,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                    ) { Text("Удалить с Диска") }
                }
            }
            Switch(checked = ok, enabled = v != cur, onCheckedChange = { toggle(v, it) })
        }
        HorizontalDivider()
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Версии у пользователей", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { loadUsers() }, enabled = !usersBusy) { Text(if (usersBusy) "Загрузка…" else "Обновить") }
    }
    val ul = users
    if (ul == null) {
        Text(if (usersBusy) "Загружаю…" else "Не удалось загрузить список", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            "Версию приложение записывает при запуске. «Неизвестна» — старое приложение (до 2.30.0), оно версию не передаёт.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ul.sortedWith(compareBy<UserVer>({ it.ver.isNotBlank() && VerGuard.allowed(cfg, it.ver) }, { it.name.lowercase() })).forEach { u ->
            val ok = u.ver.isNotBlank() && VerGuard.allowed(cfg, u.ver)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(u.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull(
                            "версия " + u.ver.ifBlank { "неизвестна" },
                            if (u.seen > 0) "был в сети " + verDateFmt.format(java.util.Date(u.seen)) else null
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    if (ok) "можно" else "закрыта",
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            HorizontalDivider()
        }
    }
    askDelete?.let { v ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text("Удалить версию ${v.version}?") },
            text = {
                Text(
                    "Файлы версии будут удалены с Google Диска. Работники больше не смогут скачать эту версию" +
                        (if (VerGuard.allowed(cfg, v.version)) ", а она сейчас разрешена." else ".") +
                        (if (v.version == cur) " Это установленная у вас версия: приложение само сохранит её на Диск при следующем запуске по Wi-Fi." else "")
                )
            },
            confirmButton = {
                TextButton(onClick = { Updater.deleteVersion(ctx, v); askDelete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("Отмена") } }
        )
    }
}
