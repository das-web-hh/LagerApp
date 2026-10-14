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
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Transaction
import java.security.MessageDigest
import java.util.UUID

/*
 * Firestore:
 *  users/{email}: name, login, email, position, role (admin|worker), status (pending|active), passHash, createdAt
 *  config/admin: login (email администратора), createdAt — есть документ = одноразовый вход admin/admin уже удалён
 *  tasks/{id}: title, text, type (app|text|approve), action (receive|paket|wagen), assignee, assigneeName, assigneePos,
 *              createdBy, createdByName, createdAt, status (open|done), doneAt, doneAuto, doneByName, target
 *  chat/{id}: chatId, participants[2], from, fromName, to, text, ts
 */

data class AppUser(
    val login: String,
    val name: String,
    val position: String,
    val role: String,
    val status: String,
    val email: String = "",
    /** Счётчик «завершений сеанса»: администратор увеличивает — все телефоны этого сотрудника выходят из аккаунта. */
    val sess: Long = 0L,
    /** Заблокирован администратором: вход закрыт, текущий сеанс завершается. */
    val blocked: Boolean = false
) {
    val isAdmin: Boolean get() = role == "admin"
    val label: String get() = if (position.isBlank()) name else "$name · $position"
}

sealed class AuthResult {
    class Ok(val user: AppUser) : AuthResult()
    class Fail(val text: String) : AuthResult()
    /** Не ошибка и не вход: нужно действие вне приложения (например, открыть ссылку из письма). */
    class Notice(val text: String) : AuthResult()
}

object Users {
    const val ADMIN_NAME = "Администратор"

    /** Старый встроенный логин: вход под ним отключён, имя оставлено только чтобы вежливо отказать. */
    private const val RETIRED_LOGIN = "admin"

    private val fs get() = FirebaseFirestore.getInstance()

    fun key(login: String): String = login.trim().lowercase().replace('/', '_')

    /** Старый формат (SHA-256 без соли и повторов) — только для проверки уже сохранённых паролей. */
    private fun legacyHash(login: String, pw: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("lager:${key(login)}:$pw".toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private const val PBKDF2_ITER = 120_000

    private fun pbkdf2(pw: String, salt: ByteArray, iter: Int): ByteArray =
        javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(pw.toCharArray(), salt, iter, 256)).encoded

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }

    /** Новый хэш пароля: PBKDF2-HMAC-SHA256, случайная соль, формат «p1$повторы$соль$хэш». */
    fun hash(login: String, pw: String): String {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return "p1$$PBKDF2_ITER$${hex(salt)}$${hex(pbkdf2(pw, salt, PBKDF2_ITER))}"
    }

    private fun verifyOne(login: String, pw: String, stored: String?): Boolean {
        if (stored.isNullOrEmpty()) return false
        if (stored.startsWith("p1$")) {
            val p = stored.split('$')
            if (p.size != 4) return false
            return try {
                MessageDigest.isEqual(pbkdf2(pw, unhex(p[2]), p[1].toInt()), unhex(p[3]))
            } catch (e: Exception) { false }
        }
        return MessageDigest.isEqual(stored.toByteArray(), legacyHash(login, pw).toByteArray())
    }

    /** Проверка пароля (с пробелами и без) по новому или старому формату хэша. */
    fun verify(login: String, pw: String, stored: String?): Boolean =
        verifyOne(login, pw, stored) || verifyOne(login, pw.trim(), stored)

    fun validEmail(e: String): Boolean = Regex("^[^@\\s/]+@[^@\\s/]+\\.[^@\\s/]{2,}$").matches(e.trim())

    private fun DocumentSnapshot.toUser(): AppUser? {
        if (!exists()) return null
        return AppUser(
            login = id,
            name = getString("name") ?: id,
            position = getString("position") ?: "",
            role = getString("role") ?: "worker",
            status = getString("status") ?: "pending",
            email = getString("email") ?: if (id.contains('@')) id else "",
            sess = getLong("sess") ?: 0L,
            blocked = getBoolean("blocked") ?: false
        )
    }

    /** Логин (email) настоящего администратора; null — администратор ещё не настроен. */
    suspend fun adminLogin(): String? {
        val snap = fs.collection("config").document("admin").get().awaitIt()
        return if (snap.exists()) snap.getString("login") else null
    }

    private val fa get() = FirebaseAuth.getInstance()

    private const val NO_NET = "Нет связи с Firebase. Проверьте интернет и повторите"

    private fun authText(e: Exception): String = when (e) {
        is com.google.firebase.FirebaseNetworkException -> NO_NET
        is com.google.firebase.FirebaseTooManyRequestsException -> "Слишком много попыток. Подождите несколько минут и повторите"
        else -> NO_NET
    }

    /**
     * Вход. Пароли хранит Firebase Authentication, а не наша база.
     * Работник, который ещё не переведён со старой схемы (users.passHash), проверяется по старому хэшу
     * один раз и получает аккаунт Firebase с тем же паролем.
     */
    suspend fun signIn(login: String, pw: String): AuthResult {
        val k = key(login)
        val p = pw.trim()
        if (k.isEmpty() || p.isEmpty()) return AuthResult.Fail("Введите логин и пароль")
        if (k == RETIRED_LOGIN) return AuthResult.Fail("Вход admin/admin удалён. Войдите по своему Email")
        if (!validEmail(k)) return AuthResult.Fail("Введите Email, указанный при регистрации")
        return try {
            val signed = try {
                fa.signInWithEmailAndPassword(k, p).awaitIt().user
            } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                null
            } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
                null
            }
            if (signed != null) afterAuth(k, signed) else migrateLegacy(k, p)
        } catch (e: Exception) {
            AuthResult.Fail(authText(e))
        }
    }

    /** Firebase Auth принял пароль: проверяем подтверждение Email и профиль в Firestore. */
    private suspend fun afterAuth(k: String, user: FirebaseUser): AuthResult {
        user.reload().awaitIt()
        val u = fa.currentUser ?: return AuthResult.Fail(NO_NET)
        if (!u.isEmailVerified) {
            try { u.sendEmailVerification().awaitIt() } catch (e: Exception) { }
            fa.signOut()
            return AuthResult.Notice("Email не подтверждён. Мы отправили письмо со ссылкой на $k. Откройте её (проверьте «Спам») и войдите снова")
        }
        u.getIdToken(true).awaitIt() // в токен попадает отметка «Email подтверждён» — по ней работают правила Firestore
        val ref = fs.collection("users").document(k)
        val snap = ref.get().awaitIt()
        if (!snap.exists()) return createProfile(k, u)
        if (snap.getBoolean("blocked") == true) {
            fa.signOut()
            return AuthResult.Fail("Доступ заблокирован администратором")
        }
        // старый хэш пароля больше не нужен — удаляем из базы
        if (snap.contains("passHash")) {
            try { ref.update("passHash", FieldValue.delete()).awaitIt() } catch (e: Exception) { }
        }
        return AuthResult.Ok(snap.toUser()!!)
    }

    /** Первый вход после регистрации: профиль «worker / pending» и задача администратору подтвердить работника. */
    private suspend fun createProfile(k: String, u: FirebaseUser): AuthResult {
        val n = (u.displayName ?: "").trim().ifEmpty { k.substringBefore('@') }
        val f = n.substringBefore(' ')
        val l = n.substringAfter(' ', "")
        val now = System.currentTimeMillis()
        val admin = try { adminLogin() } catch (e: Exception) { null }
        fs.collection("users").document(k).set(
            hashMapOf<String, Any>(
                "name" to n, "firstName" to f, "lastName" to l, "login" to k, "email" to k,
                "position" to "", "role" to "worker", "status" to "pending", "createdAt" to now
            )
        ).awaitIt()
        if (admin != null) Tasks.createApproval(k, n, now, admin)
        return AuthResult.Ok(AppUser(k, n, "", "worker", "pending"))
    }

    /** Старая схема: пароль проверяется по users.passHash, затем аккаунт переносится в Firebase Auth с тем же паролем. */
    private suspend fun migrateLegacy(k: String, p: String): AuthResult {
        val snap = try {
            fs.collection("users").document(k).get().awaitIt()
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            // переходный период закончен (правила закрыты): старой схемы больше нет
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED)
                return AuthResult.Fail("Неверный пароль или Email")
            throw e
        }
        if (!snap.exists()) return AuthResult.Fail("Аккаунт «$k» не найден. Проверьте Email или зарегистрируйтесь")
        val stored = snap.getString("passHash")
        if (stored.isNullOrEmpty() || !verify(k, p, stored)) return AuthResult.Fail("Неверный пароль")
        if (snap.getBoolean("blocked") == true) return AuthResult.Fail("Доступ заблокирован администратором")
        if (p.length < 6) return AuthResult.Fail("Пароль короче 6 символов: новая система входа его не принимает. Обратитесь к администратору")
        return try {
            val created = fa.createUserWithEmailAndPassword(k, p).awaitIt().user ?: return AuthResult.Fail(NO_NET)
            try { created.sendEmailVerification().awaitIt() } catch (e: Exception) { }
            fa.signOut()
            AuthResult.Notice("Ваш аккаунт переведён на новую систему входа. Мы отправили письмо со ссылкой на $k — откройте её и войдите снова тем же паролем")
        } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
            // аккаунт Firebase уже есть, но пароль не подошёл: письмо для установки пароля заодно подтверждает Email
            try { fa.sendPasswordResetEmail(k).awaitIt() } catch (e2: Exception) { }
            AuthResult.Notice("Мы отправили письмо со ссылкой для установки пароля на $k. Откройте её и войдите с новым паролем")
        }
    }

    /** Шаг 1 регистрации: формат Email. Занят ли он, выяснится при создании аккаунта. */
    suspend fun checkEmailFree(email: String): String? =
        if (!validEmail(key(email))) "Введите корректный Email" else null

    /**
     * Шаг 3 регистрации: создаётся аккаунт Firebase и уходит письмо со ссылкой подтверждения.
     * Профиль (всегда «worker», без должности, статус pending) появляется при первом входе после подтверждения;
     * должность и роль назначает только администратор.
     */
    suspend fun register(email: String, firstName: String, lastName: String, pw: String): AuthResult {
        val k = key(email)
        val f = firstName.trim().replace(Regex("\\s+"), " ")
        val l = lastName.trim().replace(Regex("\\s+"), " ")
        val p = pw.trim()
        if (!validEmail(k)) return AuthResult.Fail("Введите корректный Email")
        if (f.isEmpty() || l.isEmpty()) return AuthResult.Fail("Введите имя и фамилию")
        if (p.length < 6) return AuthResult.Fail("Пароль — минимум 6 символов")
        return try {
            val u = fa.createUserWithEmailAndPassword(k, p).awaitIt().user ?: return AuthResult.Fail(NO_NET)
            try { u.updateProfile(UserProfileChangeRequest.Builder().setDisplayName("$f $l").build()).awaitIt() } catch (e: Exception) { }
            try { u.sendEmailVerification().awaitIt() } catch (e: Exception) { }
            fa.signOut()
            AuthResult.Notice("Почти готово. Мы отправили письмо со ссылкой на $k. Откройте её и войдите — затем администратор назначит вам должность")
        } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
            AuthResult.Fail("Пользователь с таким Email уже существует")
        } catch (e: com.google.firebase.auth.FirebaseAuthWeakPasswordException) {
            AuthResult.Fail("Пароль слишком простой. Придумайте другой")
        } catch (e: Exception) {
            AuthResult.Fail(authText(e))
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

    /** Завершить сеанс: сотрудник выйдет из аккаунта на всех телефонах, но сможет войти снова. */
    fun endSession(login: String) {
        fs.collection("users").document(login).update("sess", FieldValue.increment(1))
        Journal.log("Система", "Администратор завершил сеанс: $login")
    }

    /** Смена пароля из профиля (через Firebase Auth). null — готово, иначе текст ошибки. */
    suspend fun changePassword(login: String, old: String, new: String): String? {
        if (new.trim().length < 6) return "Новый пароль — минимум 6 символов"
        return try {
            val u = fa.currentUser ?: return "Сеанс устарел. Выйдите из аккаунта и войдите заново"
            u.reauthenticate(EmailAuthProvider.getCredential(u.email ?: key(login), old.trim())).awaitIt()
            u.updatePassword(new.trim()).awaitIt()
            Journal.log("Система", "Пароль изменён")
            null
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
            "Текущий пароль неверный"
        } catch (e: Exception) {
            NO_NET
        }
    }

    /** Смена имени и фамилии из профиля. null — готово, иначе текст ошибки. */
    suspend fun updateName(login: String, firstName: String, lastName: String): String? {
        val f = firstName.trim().replace(Regex("\\s+"), " ")
        val l = lastName.trim().replace(Regex("\\s+"), " ")
        if (f.isEmpty() || l.isEmpty()) return "Введите имя и фамилию"
        return try {
            fs.collection("users").document(key(login))
                .update(mapOf("name" to "$f $l", "firstName" to f, "lastName" to l)).awaitIt()
            null
        } catch (e: Exception) {
            "Нет связи с Firebase. Проверьте интернет и повторите"
        }
    }

    /** Удалить сотрудника (только администратор): аккаунт и его задачи исчезают, телефон сотрудника выходит из аккаунта. */
    fun deleteUser(login: String, name: String) {
        fs.collection("users").document(login).delete()
        fs.collection("tasks").whereEqualTo("assignee", login).get().addOnSuccessListener { s ->
            s.documents.forEach { it.reference.delete() }
        }
        Journal.log("Система", "Администратор удалил сотрудника: $name ($login)")
    }

    /** Заблокировать / разблокировать: заблокированный сразу выходит и не может войти. */
    fun setBlocked(login: String, blocked: Boolean) {
        fs.collection("users").document(login).update(mapOf("blocked" to blocked, "sess" to FieldValue.increment(1)))
        Journal.log("Система", (if (blocked) "Заблокирован: " else "Разблокирован: ") + login)
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
        "wagen" to "DHL Postwagen (посчитать и сохранить)",
        "unload" to "Выгрузка (посчитать палеты и сохранить)"
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

    fun createApproval(login: String, name: String, now: Long, admin: String) {
        fs.collection("tasks").document("approve_$login").set(
            hashMapOf<String, Any>(
                "title" to "Новый работник: $name",
                "text" to "Назначьте должность и подтвердите регистрацию",
                "type" to "approve", "action" to "",
                "assignee" to admin, "assigneeName" to Users.ADMIN_NAME, "assigneePos" to Users.ADMIN_NAME,
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
