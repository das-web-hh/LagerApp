package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* Работа → Выгрузка: счёт палет, принятых при выгрузке машины (как DHL Postwagen, но принятие по типам палет). */

private val UL_RU = Locale("ru")

/** Типы палет в порядке переключения свайпом. */
private val PALLET_TYPES = listOf(
    "Евро-палеты",
    "Einweg · большие",
    "Einweg · обычные",
    "Einweg · маленькие"
)

private fun UnloadOp.countOf(i: Int): Int = when (i) {
    0 -> eur
    1 -> ewBig
    2 -> ewNorm
    else -> ewSmall
}

private fun ulDay(ms: Long): String = SimpleDateFormat("d MMMM yyyy, EEEE", UL_RU).format(Date(ms))

/** «Евро 12 · Einweg 3/2/0» — краткая строка по типам. */
private fun UnloadOp.breakdown(): String =
    "Евро $eur · Einweg бол. $ewBig / обыч. $ewNorm / мал. $ewSmall"

@Composable
fun UnloadScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val sdb = remember { SendDb.get(ctx) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<UnloadOp>>(emptyList()) }

    LaunchedEffect(Unit) { SendCloud.pull(sdb) }
    LaunchedEffect(SendState.version) { list = withContext(Dispatchers.IO) { sdb.allUnloads() } }

    val opened = openId
    when {
        adding -> { UnloadAddScreen { adding = false }; return }
        opened != null -> { UnloadDetailScreen(opened) { openId = null }; return }
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Выгрузка", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Добавить", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        val dayStart = remember(list) {
            val c = java.util.Calendar.getInstance()
            c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0)
            c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
            c.timeInMillis
        }
        val today = list.filter { it.createdAt >= dayStart }
        if (today.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Сегодня", style = MaterialTheme.typography.labelMedium)
                            Text("выгрузок: ${today.size}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Палет ${today.sumOf { it.total }}", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Евро ${today.sumOf { it.eur }} · Einweg бол. ${today.sumOf { it.ewBig }} / " +
                            "обыч. ${today.sumOf { it.ewNorm }} / мал. ${today.sumOf { it.ewSmall }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Text("История", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Выгрузок пока нет") }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { u ->
                    Card(
                        onClick = { openId = u.id },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(fmtTime(u.createdAt), fontWeight = FontWeight.Medium)
                                val veh = listOf(
                                    u.truck.takeIf { it.isNotBlank() }?.let { "машина $it" },
                                    u.trailer.takeIf { it.isNotBlank() }?.let { "прицеп $it" }
                                ).filterNotNull().joinToString(" · ")
                                if (veh.isNotEmpty()) {
                                    Text(veh, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(u.breakdown(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${u.total}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnloadAddScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val sdb = remember { SendDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var c0 by rememberSaveable { mutableIntStateOf(0) }
    var c1 by rememberSaveable { mutableIntStateOf(0) }
    var c2 by rememberSaveable { mutableIntStateOf(0) }
    var c3 by rememberSaveable { mutableIntStateOf(0) }
    fun getC(i: Int): Int = when (i) { 0 -> c0; 1 -> c1; 2 -> c2; else -> c3 }
    fun setC(i: Int, v: Int) { when (i) { 0 -> c0 = v; 1 -> c1 = v; 2 -> c2 = v; else -> c3 = v } }
    var cur by rememberSaveable { mutableIntStateOf(0) }
    var truck by rememberSaveable { mutableStateOf("") }
    var trailer by rememberSaveable { mutableStateOf("") }
    var picker by remember { mutableStateOf(false) }
    val keys = remember { List(4) { Any() } }
    // пока считаем, экран не выключается (20 минут; каждое нажатие начинает отсчёт заново)
    KeepScreenOn(20, listOf(c0, c1, c2, c3, cur, truck, trailer, picker))

    val total = c0 + c1 + c2 + c3
    var dragSum by remember { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Новая выгрузка", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            // выбор типа: кнопки или свайп справа налево по счётчику
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PALLET_TYPES.forEachIndexed { i, name ->
                    val sel = i == cur
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { cur = i }
                    ) {
                        Column(Modifier.padding(vertical = 6.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                name.replace("Einweg · ", "Ew ").replace("Евро-палеты", "Евро"),
                                fontSize = 12.sp, maxLines = 1
                            )
                            Text(getC(i).toString(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Тип палет: ${PALLET_TYPES[cur]}. Смахните счётчик справа налево — следующий тип.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragSum = 0f },
                        onDragEnd = {
                            if (dragSum < -90f) cur = (cur + 1) % PALLET_TYPES.size
                            else if (dragSum > 90f) cur = (cur + PALLET_TYPES.size - 1) % PALLET_TYPES.size
                            dragSum = 0f
                        },
                        onDragCancel = { dragSum = 0f },
                        onHorizontalDrag = { _, dx -> dragSum += dx }
                    )
                }
            ) {
                BigCounter(
                    PALLET_TYPES[cur], getC(cur), keys[cur], Modifier.fillMaxSize(),
                    { setC(cur, it) }
                ) { picker = true }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = truck, onValueChange = { truck = it.take(20) }, singleLine = true,
                    label = { Text("Номер машины") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = trailer, onValueChange = { trailer = it.take(20) }, singleLine = true,
                    label = { Text("Номер прицепа") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    val op = UnloadOp(
                        id = newSendId(), createdAt = now,
                        eur = c0, ewBig = c1, ewNorm = c2, ewSmall = c3,
                        truck = truck.trim(), trailer = trailer.trim(),
                        author = Session.displayName, updatedAt = now
                    )
                    scope.launch(Dispatchers.IO) { SendCloud.saveUnload(sdb, op) }
                    onClose()
                },
                enabled = total > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Сохранить · палет $total", style = MaterialTheme.typography.titleMedium) }
        }
        CountOverlay()
    }

    if (picker) {
        CountPickerDialog(
            title = PALLET_TYPES[cur],
            initial = getC(cur),
            onApply = { v ->
                setC(cur, v)
                CountAnnouncer.picked(ctx, v.toString(), v.toString())
                picker = false
            },
            onDismiss = { picker = false }
        )
    }
}

@Composable
fun UnloadDetailScreen(id: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    val sdb = remember { SendDb.get(ctx) }
    val scope = rememberCoroutineScope()
    var u by remember { mutableStateOf<UnloadOp?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id, SendState.version) { u = withContext(Dispatchers.IO) { sdb.getUnload(id) } }
    val cur = u
    if (cur == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Запись не найдена") }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Выгрузка", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                InfoLine("Дата", ulDay(cur.createdAt))
                InfoLine("Время", SimpleDateFormat("HH:mm:ss", UL_RU).format(Date(cur.createdAt)))
                InfoLine("Номер машины", cur.truck)
                InfoLine("Номер прицепа", cur.trailer)
                PALLET_TYPES.forEachIndexed { i, name -> InfoLine(name, cur.countOf(i).toString()) }
                InfoLine("Всего палет", cur.total.toString())
                InfoLine("Кто сохранил", cur.author)
                InfoLine("Код записи", cur.id)
            }
        }
        if (Session.isAdmin) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Удалить запись", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить запись?") },
            text = { Text("Запись исчезнет из истории на всех телефонах.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    scope.launch(Dispatchers.IO) { SendCloud.deleteUnload(sdb, cur.id) }
                    onClose()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
}
