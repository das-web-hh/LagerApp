package ru.storage.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.security.MessageDigest
import java.util.UUID

/*
 * Firestore:
 *  users/{login}: name, login, position, role (admin|worker), status (pending|active), passHash, createdAt
 *  tasks/{id}: title, text, type (app|text|approve), action (receive|paket|wagen), assignee, assigneeName, assigneePos,
 *              createdBy, createdByName, createdAt, status (open|done), doneAt, doneAuto, doneByName, target
 *  chat/{id}: chatId, participants[2], from, fromName, to, text, ts
 */

data class AppUser(
    val login: String,
    val name: String,
    val position: String,
    val role: String,
    val status: String
) {
    val isAdmin: Boolean get() = role == "admin"
    val label: String get() = if (position.isBlank()) name else "$name · $position"
}

sealed class AuthResult {
    class Ok(val user: AppUser) : AuthResult()
    class Fail(val text: String) : AuthResult()
}

object Users {
    /** Логин администратора (в нижнем регистре). Пароль в коде не хранится — только его хэш. */
    const val ADMIN_LOGIN = "washingto1a#"
    private const val ADMIN_HASH = "9f90a344be39061ce0370469fef75c1644f7271fe4622062a69f94df3035ec58"
    const val ADMIN_NAME = "Администратор"

    private val fs get() = FirebaseFirestore.getInstance()

    fun key(login: String): String = login.trim().lowercase().replace('/', '_')

    fun hash(login: String, pw: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("lager:${key(login)}:$pw".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun DocumentSnapshot.toUser(): AppUser? {
        if (!exists()) return null
        return AppUser(
            login = id,
            name = getString("name") ?: id,
            position = getString("position") ?: "",
            role = getString("role") ?: "worker",
            status = getString("status") ?: "pending"
        )
    }

    private fun ensureAdminDoc() {
        fs.collection("users").document(ADMIN_LOGIN).set(
            hashMapOf<String, Any>(
                "name" to ADMIN_NAME, "login" to ADMIN_LOGIN, "position" to ADMIN_NAME,
                "role" to "admin", "status" to "active", "createdAt" to System.currentTimeMillis()
            ),
            com.google.firebase.firestore.SetOptions.merge()
        )
    }

    suspend fun signIn(login: String, pw: String): AuthResult {
        val k = key(login)
        if (k.isEmpty() || pw.isEmpty()) return AuthResult.Fail("Введите логин и пароль")
        if (k == ADMIN_LOGIN) {
            if (hash(k, pw) != ADMIN_HASH) return AuthResult.Fail("Неверный логин или пароль")
            try { ensureAdminDoc() } catch (e: Exception) { }
            return AuthResult.Ok(AppUser(k, ADMIN_NAME, ADMIN_NAME, "admin", "active"))
        }
        return try {
            val snap = fs.collection("users").document(k).get().awaitIt()
            if (!snap.exists() || snap.getString("passHash") != hash(k, pw)) {
                AuthResult.Fail("Неверный логин или пароль")
            } else {
                AuthResult.Ok(snap.toUser()!!)
            }
        } catch (e: Exception) {
            AuthResult.Fail("Нет связи с Firebase. Проверьте интернет и повторите")
        }
    }

    suspend fun register(name: String, login: String, pw: String): AuthResult {
        val n = name.trim().replace(Regex("\\s+"), " ")
        val k = key(login)
        if (n.length < 2) return AuthResult.Fail("Введите имя и фамилию")
        if (k.length < 3) return AuthResult.Fail("Логин — минимум 3 символа")
        if (pw.length < 4) return AuthResult.Fail("Пароль — минимум 4 символа")
        if (k == ADMIN_LOGIN) return AuthResult.Fail("Этот логин занят")
        return try {
            val ref = fs.collection("users").document(k)
            if (ref.get().awaitIt().exists()) return AuthResult.Fail("Такой логин уже зарегистрирован")
            val now = System.currentTimeMillis()
            ref.set(
                hashMapOf<String, Any>(
                    "name" to n, "login" to k, "position" to "", "role" to "worker",
                    "status" to "pending", "passHash" to hash(k, pw), "createdAt" to now
                )
            ).awaitIt()
            Tasks.createApproval(k, n, now)
            AuthResult.Ok(AppUser(k, n, "", "worker", "pending"))
        } catch (e: Exception) {
            AuthResult.Fail("Нет связи с Firebase. Проверьте интернет и повторите")
        }
    }

    /** Подписка на документ пользователя. cb(null, false) — документ удалён (регистрация отклонена). */
    fun listenUser(login: String, cb: (AppUser?, Boolean) -> Unit): ListenerRegistration =
        fs.collection("users").document(login).addSnapshotListener { snap, _ ->
            if (snap != null) cb(snap.toUser(), snap.metadata.isFromCache)
        }

    fun listenActive(cb: (List<AppUser>) -> Unit): ListenerRegistration =
        fs.collection("users").whereEqualTo("status", "active").addSnapshotListener { snap, _ ->
            if (snap != null) cb(snap.documents.mapNotNull { it.toUser() }.sortedBy { it.name.lowercase() })
        }

    fun approve(login: String, position: String, taskId: String) {
        fs.collection("users").document(login).update(mapOf("status" to "active", "position" to position))
        Tasks.complete(taskId, auto = false)
        Journal.log("Система", "Подтверждён работник: $login · $position")
    }

    fun reject(login: String, taskId: String) {
        fs.collection("users").document(login).delete()
        Tasks.complete(taskId, auto = false)
        Journal.log("Система", "Отклонена регистрация: $login")
    }

    fun setPosition(login: String, position: String) {
        fs.collection("users").document(login).update("position", position)
    }
}

data class AppTask(
    val id: String,
    val title: String,
    val text: String,
    val type: String,
    val action: String,
    val assignee: String,
    val assigneeName: String,
    val assigneePos: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Long,
    val status: String,
    val doneAt: Long,
    val doneAuto: Boolean,
    val doneByName: String,
    val target: String
) {
    val open: Boolean get() = status == "open"
}

object Tasks {
    private val fs get() = FirebaseFirestore.getInstance()

    val ACTIONS = listOf(
        "receive" to "Принять товар (сохранить приём)",
        "paket" to "Post paket (сохранить посылку)",
        "wagen" to "DHL Postwagen (посчитать и сохранить)"
    )

    fun actionLabel(a: String): String = ACTIONS.firstOrNull { it.first == a }?.second ?: a

    private fun DocumentSnapshot.toTask(): AppTask = AppTask(
        id = id,
        title = getString("title") ?: "",
        text = getString("text") ?: "",
        type = getString("type") ?: "text",
        action = getString("action") ?: "",
        assignee = getString("assignee") ?: "",
        assigneeName = getString("assigneeName") ?: "",
        assigneePos = getString("assigneePos") ?: "",
        createdBy = getString("createdBy") ?: "",
        createdByName = getString("createdByName") ?: "",
        createdAt = getLong("createdAt") ?: 0L,
        status = getString("status") ?: "open",
        doneAt = getLong("doneAt") ?: 0L,
        doneAuto = getBoolean("doneAuto") ?: false,
        doneByName = getString("doneByName") ?: "",
        target = getString("target") ?: ""
    )

    /** Админ слушает все задачи, работник — только свои. cb(все, изменившиеся). */
    fun listen(me: AppUser, cb: (List<AppTask>, List<AppTask>) -> Unit): ListenerRegistration {
        val col = fs.collection("tasks")
        val q = if (me.isAdmin) col else col.whereEqualTo("assignee", me.login)
        return q.addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val all = snap.documents.map { it.toTask() }.sortedByDescending { it.createdAt }
            val changed = snap.documentChanges.filter { it.type != DocumentChange.Type.REMOVED }.map { it.document.toTask() }
            cb(all, changed)
        }
    }

    fun create(title: String, text: String, type: String, action: String, to: AppUser, by: AppUser) {
        val id = UUID.randomUUID().toString()
        fs.collection("tasks").document(id).set(
            hashMapOf<String, Any>(
                "title" to title, "text" to text, "type" to type, "action" to action,
                "assignee" to to.login, "assigneeName" to to.name, "assigneePos" to to.position,
                "createdBy" to by.login, "createdByName" to by.name, "createdAt" to System.currentTimeMillis(),
                "status" to "open", "doneAt" to 0L, "doneAuto" to false, "doneByName" to "", "target" to ""
            )
        )
        Journal.log("Задачи", "Задача «$title» → ${to.name}")
    }

    fun createApproval(login: String, name: String, now: Long) {
        fs.collection("tasks").document("approve_$login").set(
            hashMapOf<String, Any>(
                "title" to "Новый работник: $name",
                "text" to "Назначьте должность и подтвердите регистрацию",
                "type" to "approve", "action" to "",
                "assignee" to Users.ADMIN_LOGIN, "assigneeName" to Users.ADMIN_NAME, "assigneePos" to Users.ADMIN_NAME,
                "createdBy" to "system", "createdByName" to "Система", "createdAt" to now,
                "status" to "open", "doneAt" to 0L, "doneAuto" to false, "doneByName" to "", "target" to login
            )
        )
    }

    fun complete(id: String, auto: Boolean) {
        val who = Session.user?.name ?: ""
        fs.collection("tasks").document(id).update(
            mapOf(
                "status" to "done", "doneAt" to System.currentTimeMillis(),
                "doneAuto" to auto, "doneByName" to who
            )
        )
    }

    fun delete(id: String) {
        fs.collection("tasks").document(id).delete()
    }

    /**
     * Действие в приложении выполнено (сохранён приём, посылка или операция Postwagen) —
     * открытые задачи этого вида у текущего работника закрываются сами, время фиксируется.
     */
    fun onAction(action: String) {
        val me = Session.user ?: return
        if (me.isAdmin) return
        try {
            fs.collection("tasks").whereEqualTo("assignee", me.login).whereEqualTo("status", "open").get()
                .addOnSuccessListener { snap ->
                    snap.documents.forEach { d ->
                        if (d.getString("type") == "app" && d.getString("action") == action) complete(d.id, true)
                    }
                }
        } catch (e: Exception) {
        }
    }
}

data class ChatMsg(
    val id: String,
    val chatId: String,
    val from: String,
    val fromName: String,
    val to: String,
    val text: String,
    val ts: Long
)

object Chat {
    private val fs get() = FirebaseFirestore.getInstance()

    /** Собеседник, чей чат открыт сейчас (для него уведомления не показываем). */
    @Volatile var activePeer: String? = null

    fun chatId(a: String, b: String): String = listOf(a, b).sorted().joinToString("|")

    fun send(me: AppUser, peer: AppUser, text: String) {
        val id = UUID.randomUUID().toString()
        fs.collection("chat").document(id).set(
            hashMapOf<String, Any>(
                "chatId" to chatId(me.login, peer.login),
                "participants" to listOf(me.login, peer.login),
                "from" to me.login, "fromName" to me.name, "to" to peer.login,
                "text" to text, "ts" to System.currentTimeMillis()
            )
        )
    }

    private fun DocumentSnapshot.toMsg(): ChatMsg = ChatMsg(
        id, getString("chatId") ?: "", getString("from") ?: "", getString("fromName") ?: "",
        getString("to") ?: "", getString("text") ?: "", getLong("ts") ?: 0L
    )

    fun listenMine(me: AppUser, cb: (List<ChatMsg>, List<ChatMsg>) -> Unit): ListenerRegistration =
        fs.collection("chat").whereArrayContains("participants", me.login).addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val all = snap.documents.map { it.toMsg() }
            val added = snap.documentChanges.filter { it.type == DocumentChange.Type.ADDED }.map { it.document.toMsg() }
            cb(all, added)
        }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("chat_read", Context.MODE_PRIVATE)

    fun lastRead(ctx: Context, peer: String): Long =
        sp(ctx).getLong("${Session.user?.login}_$peer", 0L)

    fun markRead(ctx: Context, peer: String) {
        sp(ctx).edit().putLong("${Session.user?.login}_$peer", System.currentTimeMillis() + 1000L).apply()
        Live.readVersion++
    }
}

/** Куда открыть приложение по нажатию на уведомление: "tasks" или "chat". */
object Nav {
    var target by mutableStateOf<String?>(null)
}

/** Общие живые данные: сотрудники, задачи, сообщения. Подписки работают, пока пользователь вошёл. */
object Live {
    var users by mutableStateOf(emptyList<AppUser>())
    var tasks by mutableStateOf(emptyList<AppTask>())
    var msgs by mutableStateOf(emptyList<ChatMsg>())
    var readVersion by mutableIntStateOf(0)

    private val regs = ArrayList<ListenerRegistration>()

    fun unreadFrom(ctx: Context, me: AppUser, peer: String): Int =
        msgs.count { it.from == peer && it.to == me.login && it.ts > Chat.lastRead(ctx, peer) }

    fun unreadTotal(ctx: Context, me: AppUser): Int =
        msgs.count { it.to == me.login && it.ts > Chat.lastRead(ctx, it.from) }

    fun openTasksCount(me: AppUser): Int = tasks.count { it.open && it.assignee == me.login }

    fun start(ctx: Context, me: AppUser) {
        stop()
        val app = ctx.applicationContext
        Notifier.ensureChannel(app)
        val since = Notifier.since(app, me.login)

        regs += Users.listenActive { users = it }
        regs += Tasks.listen(me) { all, changed ->
            tasks = all
            changed.forEach { t ->
                if (t.assignee == me.login && t.open && t.createdAt > since && Notifier.once(app, "t:${t.id}")) {
                    Notifier.show(
                        app, "t:${t.id}",
                        if (t.type == "approve") "Новый работник" else "Новая задача",
                        if (t.type == "approve") t.title.removePrefix("Новый работник: ") + " — назначьте должность и подтвердите" else t.title,
                        "tasks"
                    )
                }
                if (me.isAdmin && t.type != "approve" && !t.open && t.doneAt > since && Notifier.once(app, "d:${t.id}")) {
                    Notifier.show(
                        app, "d:${t.id}", "Задача выполнена",
                        "${t.assigneeName}: ${t.title} · " + fmtTime(t.doneAt), "tasks"
                    )
                }
            }
        }
        regs += Chat.listenMine(me) { all, added ->
            msgs = all
            added.forEach { m ->
                if (m.to == me.login && m.ts > since && Chat.activePeer != m.from && Notifier.once(app, "m:${m.id}")) {
                    Notifier.show(app, "m:${m.id}", m.fromName, m.text, "chat")
                }
            }
        }
    }

    fun stop() {
        regs.forEach { try { it.remove() } catch (e: Exception) { } }
        regs.clear()
        users = emptyList()
        tasks = emptyList()
        msgs = emptyList()
    }
}

/** Системные уведомления (шторка). */
object Notifier {
    private const val CHANNEL = "team"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Задачи и чат", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Новые задачи, выполнение задач, сообщения"
            nm.createNotificationChannel(ch)
        }
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("notif", Context.MODE_PRIVATE)

    /** Время первого запуска уведомлений для этого пользователя: всё, что новее, считается новым. */
    fun since(ctx: Context, login: String): Long {
        val k = "since_$login"
        val v = sp(ctx).getLong(k, 0L)
        if (v > 0L) return v
        val now = System.currentTimeMillis()
        sp(ctx).edit().putLong(k, now).apply()
        return now
    }

    /** true — об этом событии ещё не уведомляли (и запоминает, что уведомили). */
    @Synchronized
    fun once(ctx: Context, key: String): Boolean {
        val s = (sp(ctx).getStringSet("seen", emptySet()) ?: emptySet()).toMutableSet()
        if (key in s) return false
        s.add(key)
        if (s.size > 600) {
            val iter = s.iterator()
            repeat(200) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        sp(ctx).edit().putStringSet("seen", s).apply()
        return true
    }

    fun show(ctx: Context, key: String, title: String, text: String, open: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            putExtra("open", open)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: return
        val pi = PendingIntent.getActivity(
            ctx, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        try {
            nm.notify(key.hashCode(), n)
        } catch (e: SecurityException) {
        }
    }
}
