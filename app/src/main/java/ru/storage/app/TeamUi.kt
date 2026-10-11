package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val RU = Locale("ru")

private fun hm(ms: Long): String = SimpleDateFormat("HH:mm", RU).format(Date(ms))

private fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(ms: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ms, now) -> "Сегодня"
        sameDay(ms, now - 24L * 3600_000L) -> "Вчера"
        else -> SimpleDateFormat("d MMMM yyyy", RU).format(Date(ms))
    }
}

@Composable
private fun Avatar(name: String, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.4f).sp
        )
    }
}

// ---------------------------------------------------------------- ожидание подтверждения

/** Новый работник зарегистрировался: ждёт, пока админ назначит должность и подтвердит. */
@Composable
fun PendingScreen(user: AppUser, onApproved: (AppUser) -> Unit, onLogout: () -> Unit) {
    var rejected by remember { mutableStateOf(false) }
    DisposableEffect(user.login) {
        val reg = Users.listenUser(user.login) { u, fromCache ->
            if (u == null) {
                if (!fromCache) rejected = true
            } else if (u.status == "active") {
                onApproved(u)
            }
        }
        onDispose { reg.remove() }
    }
    BackHandler { }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(110.dp))
        Spacer(Modifier.height(24.dp))
        if (rejected) {
            Text("Регистрация отклонена", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Администратор не подтвердил этот аккаунт. Обратитесь к нему или зарегистрируйтесь заново.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
        } else {
            Text("Ждём подтверждения", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "${user.name}, администратор получил уведомление: он назначит вам должность и подтвердит вход. " +
                    "Это окно откроется само, как только вас подтвердят.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onLogout) { Text(if (rejected) "К окну входа" else "Выйти") }
    }
}

// ---------------------------------------------------------------- чат

@Composable
fun ChatScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val me = Session.user ?: return
    var peerLogin by rememberSaveable { mutableStateOf<String?>(null) }
    val peer = Live.users.firstOrNull { it.login == peerLogin }
    if (peer != null) {
        Conversation(me, peer) { peerLogin = null }
        return
    }
    BackHandler(onBack = onBack)

    val rv = Live.readVersion
    val msgs = Live.msgs
    val others = Live.users.filter { it.login != me.login }
    class Row2(val u: AppUser, val last: ChatMsg?, val unread: Int)
    val rows = remember(others, msgs, rv) {
        others.map { u ->
            val cid = Chat.chatId(me.login, u.login)
            Row2(u, msgs.filter { it.chatId == cid }.maxByOrNull { it.ts }, Live.unreadFrom(ctx, me, u.login))
        }.sortedWith(compareByDescending<Row2> { it.last?.ts ?: 0L }.thenBy { it.u.name.lowercase() })
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", fontSize = 26.sp) }
            Text("Чат", style = MaterialTheme.typography.headlineSmall)
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Пока нет других сотрудников. Они появятся здесь после регистрации и подтверждения.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.u.login }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { peerLogin = r.u.login }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(r.u.name, 48)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.u.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                r.u.position.ifBlank { "без должности" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                            if (r.last != null) {
                                Text(
                                    (if (r.last.from == me.login) "Вы: " else "") + r.last.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (r.last != null) {
                                Text(hm(r.last.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (r.unread > 0) {
                                Spacer(Modifier.height(4.dp))
                                Badge { Text(r.unread.toString()) }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 76.dp))
                }
            }
        }
    }
}

@Composable
private fun Conversation(me: AppUser, peer: AppUser, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val cid = Chat.chatId(me.login, peer.login)
    val list = remember(Live.msgs) { Live.msgs.filter { it.chatId == cid }.sortedBy { it.ts } }
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    DisposableEffect(peer.login) {
        Chat.activePeer = peer.login
        onDispose { Chat.activePeer = null }
    }
    LaunchedEffect(list.size) {
        Chat.markRead(ctx, peer.login)
        if (list.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹", fontSize = 26.sp) }
            Avatar(peer.name, 40)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(peer.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(peer.position.ifBlank { "без должности" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        HorizontalDivider()
        val rev = list.asReversed()
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (rev.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("Напишите первое сообщение", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            itemsIndexed(rev, key = { _, m -> m.id }) { i, m ->
                val older = rev.getOrNull(i + 1)
                val mine = m.from == me.login
                Column(Modifier.fillMaxWidth()) {
                    if (older == null || !sameDay(older.ts, m.ts)) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                dayLabel(m.ts), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Surface(
                            shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp),
                            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.widthIn(max = 290.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                                Text(
                                    m.text,
                                    color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    hm(m.ts), fontSize = 11.sp, modifier = Modifier.align(Alignment.End),
                                    color = (if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Сообщение") },
                shape = RoundedCornerShape(24.dp), maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        Chat.send(me, peer, t)
                        text = ""
                    }
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.size(52.dp)
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить") }
        }
    }
}

// ---------------------------------------------------------------- задачи

@Composable
private fun TaskHead(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
    )
}

@Composable
fun TasksScreen(onBack: () -> Unit, onOpenTab: (Int) -> Unit) {
    val me = Session.user ?: return
    var creating by rememberSaveable { mutableStateOf(false) }
    var posFor by remember { mutableStateOf<AppUser?>(null) }
    if (creating) {
        NewTaskScreen(me) { creating = false }
        return
    }
    BackHandler(onBack = onBack)
    val all = Live.tasks

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹", fontSize = 26.sp) }
                Text("Задачи", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            }
        }
        if (me.isAdmin) {
            item {
                Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("Новая задача") }
            }
            val approvals = all.filter { it.type == "approve" && it.open }
            if (approvals.isNotEmpty()) {
                item { TaskHead("Подтверждение работников") }
                items(approvals, key = { it.id }) { t ->
                    ApproveCard(t)
                    Spacer(Modifier.height(8.dp))
                }
            }
            val issued = all.filter { it.type != "approve" }
            item { TaskHead("Выданные задачи") }
            if (issued.isEmpty()) {
                item { Text("Пока нет задач", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(issued, key = { it.id }) { t ->
                TaskCard(t, admin = true, onOpenTab = onOpenTab, onBack = onBack)
                Spacer(Modifier.height(8.dp))
            }
            val staff = Live.users.filter { !it.isAdmin }
            if (staff.isNotEmpty()) {
                item { TaskHead("Сотрудники") }
                items(staff, key = { "u" + it.login }) { u ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(u.name, 38)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.name, fontWeight = FontWeight.Medium)
                            Text(u.position.ifBlank { "без должности" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { posFor = u }) { Text("Должность") }
                    }
                }
            }
        } else {
            val open = all.filter { it.open }
            val done = all.filter { !it.open }.take(20)
            item { TaskHead("В работе") }
            if (open.isEmpty()) {
                item { Text("Новых задач нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(open, key = { it.id }) { t ->
                TaskCard(t, admin = false, onOpenTab = onOpenTab, onBack = onBack)
                Spacer(Modifier.height(8.dp))
            }
            if (done.isNotEmpty()) {
                item { TaskHead("Выполнено") }
                items(done, key = { it.id }) { t ->
                    TaskCard(t, admin = false, onOpenTab = onOpenTab, onBack = onBack)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    posFor?.let { u ->
        var v by remember(u.login) { mutableStateOf(u.position) }
        AlertDialog(
            onDismissRequest = { posFor = null },
            title = { Text(u.name) },
            text = { OutlinedTextField(v, { v = it }, label = { Text("Должность") }, singleLine = true) },
            confirmButton = {
                Button(enabled = v.isNotBlank(), onClick = { Users.setPosition(u.login, v.trim()); posFor = null }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { posFor = null }) { Text("Отмена") } }
        )
    }
}

private val POSITIONS = listOf("Кладовщик", "Приёмщик", "Комплектовщик", "Грузчик", "Менеджер")

@Composable
private fun ApproveCard(t: AppTask) {
    var pos by remember(t.id) { mutableStateOf("") }
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Новый работник", style = MaterialTheme.typography.labelMedium)
            Text(t.title.removePrefix("Новый работник: "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Логин: ${t.target} · ${fmtTime(t.createdAt)}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                pos, { pos = it }, label = { Text("Должность") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                POSITIONS.forEach { p -> AssistChip(onClick = { pos = p }, label = { Text(p) }) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = pos.isNotBlank(), onClick = { Users.approve(t.target, pos.trim(), t.id) }) { Text("Подтвердить") }
                OutlinedButton(onClick = { Users.reject(t.target, t.id) }) { Text("Отклонить") }
            }
        }
    }
}

@Composable
private fun TaskCard(t: AppTask, admin: Boolean, onOpenTab: (Int) -> Unit, onBack: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!t.open) Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (t.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(t.text, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            val kind = if (t.type == "app") "В приложении: " + Tasks.actionLabel(t.action) else "Текстовая задача"
            Text(kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (admin) {
                Text(
                    "Кому: " + t.assigneeName + if (t.assigneePos.isNotBlank()) " · ${t.assigneePos}" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("От: ${t.createdByName} · ${fmtTime(t.createdAt)}", style = MaterialTheme.typography.bodySmall)
            }
            if (!t.open) {
                Text(
                    "Выполнено " + fmtTime(t.doneAt) + if (t.doneAuto) " (зафиксировано приложением)" else " (отмечено вручную)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (admin) {
                Text("В работе", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (t.open && !admin) {
                Spacer(Modifier.height(8.dp))
                if (t.type == "app") {
                    Text(
                        "Отметится сама, когда вы выполните это в приложении.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { onOpenTab(1); onBack() }) { Text("Открыть «Работа»") }
                } else {
                    Button(onClick = { Tasks.complete(t.id, auto = false) }) { Text("Выполнено") }
                }
            }
            if (admin) {
                TextButton(onClick = { Tasks.delete(t.id) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun NewTaskScreen(me: AppUser, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var kind by rememberSaveable { mutableStateOf("app") }
    var action by rememberSaveable { mutableStateOf("receive") }
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf("") }
    val workers = Live.users.filter { !it.isAdmin }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹", fontSize = 26.sp) }
            Text("Новая задача", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == "app", onClick = { kind = "app" }, label = { Text("В приложении") })
            FilterChip(selected = kind == "text", onClick = { kind = "text" }, label = { Text("Текстом") })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (kind == "app") "Приложение само отметит выполнение и время, когда работник сохранит нужное действие."
            else "Работа без приложения: работник сам нажмёт «Выполнено».",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (kind == "app") {
            Spacer(Modifier.height(10.dp))
            Tasks.ACTIONS.forEach { (code, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { action = code },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = action == code, onClick = { action = code })
                    Text(label)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (kind == "app") "Название (необязательно)" else "Название") }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            text, { text = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (kind == "app") "Пояснение (необязательно)" else "Что нужно сделать") }
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Кому", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (chosen.size == workers.size) chosen.clear() else { chosen.clear(); chosen.addAll(workers.map { it.login }) }
            }) { Text(if (chosen.size == workers.size && workers.isNotEmpty()) "Снять всех" else "Выбрать всех") }
        }
        if (workers.isEmpty()) {
            Text("Нет подтверждённых работников", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        workers.forEach { u ->
            Row(
                Modifier.fillMaxWidth().clickable { if (u.login in chosen) chosen.remove(u.login) else chosen.add(u.login) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = u.login in chosen,
                    onCheckedChange = { if (it) chosen.add(u.login) else chosen.remove(u.login) }
                )
                Column {
                    Text(u.name)
                    Text(u.position.ifBlank { "без должности" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val ttl = title.trim().ifEmpty { if (kind == "app") Tasks.actionLabel(action).substringBefore(" (") else "" }
                when {
                    kind == "text" && ttl.isEmpty() -> error = "Введите название задачи"
                    kind == "text" && text.isBlank() -> error = "Опишите, что нужно сделать"
                    chosen.isEmpty() -> error = "Выберите хотя бы одного работника"
                    else -> {
                        workers.filter { it.login in chosen }.forEach { u ->
                            Tasks.create(ttl, text.trim(), kind, if (kind == "app") action else "", u, me)
                        }
                        onClose()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Отправить") }
    }
}
