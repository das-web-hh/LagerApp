package ru.storage.app

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val RU = Locale("ru")

private fun capital(s: String) = s.replaceFirstChar { it.uppercase() }

/** Окно «Рабочее время»: часы по дням, месяцам и произвольному периоду. Настройки — в «Настройки → Рабочее время». */
@Composable
fun WorkTimeScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            tick++
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
        }
        Text(
            "Рабочее время", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        TabRow(selectedTabIndex = tab) {
            listOf("Месяц", "Год", "Период").forEachIndexed { i, t ->
                androidx.compose.material3.Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> MonthTab(tick)
            1 -> YearTab(tick)
            else -> PeriodTab(tick)
        }
    }
}

/** Окно «Настройки → Рабочее время»: учёт на этом телефоне, права доступа; точку, радиус, зону подхода, расписание и короткие дни меняет администратор. */
@Composable
fun WorkSettingsScreen(onBack: () -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (WorkCloud.pull(ctx)) tick++
    }
    if (picking) {
        BackHandler { picking = false }
        val c = remember { WorkStore.cfg(ctx) }
        MapPicker(
            hasInit = c.hasPoint, initLat = c.lat, initLon = c.lon, radius = c.radius,
            onPick = { lat, lon ->
                val n = WorkStore.cfg(ctx).copy(hasPoint = true, lat = lat, lon = lon)
                WorkStore.saveCfg(ctx, n)
                WorkCloud.push(ctx, n)
                picking = false
                tick++
            },
            onClose = { picking = false }
        )
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
        }
        Text(
            "Рабочее время", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        WorkSettingsTab(tick, onPickPoint = { picking = true }, onChanged = { tick++ })
    }
}

/** Период «с … по …»: сколько часов отработано за выбранные дни и по каждому дню. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodTab(tick: Int) {
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    val today = WorkStore.dayStart(now)
    var from by rememberSaveable { mutableLongStateOf(Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis) }
    var to by rememberSaveable { mutableLongStateOf(today) }
    var pick by remember { mutableStateOf(false) }
    val sessions = remember(tick) { WorkStore.sessions(ctx) }
    val dayFmt = remember { SimpleDateFormat("EEE, d MMM", RU) }
    val fullFmt = remember { SimpleDateFormat("d MMM yyyy", RU) }

    class Day(val ms: Long, val dur: Long, val spans: String)

    val days = remember(tick, from, to) {
        val out = ArrayList<Day>()
        var d = from
        var n = 0
        while (d <= to && n < 400) {
            val de = WorkStore.plusDays(d, 1)
            val spans = sessions.filter { s ->
                val e = if (s.end == 0L) now else s.end
                e > d && s.start < de
            }.joinToString(", ") { s ->
                val a = WorkStore.hhmm(maxOf(s.start, d))
                val b = if (s.end == 0L) "…" else if (s.end >= de) "24:00" else WorkStore.hhmm(s.end)
                "$a–$b"
            }
            out.add(Day(d, WorkStore.overlap(sessions, d, de, now), spans))
            d = de
            n++
        }
        out
    }
    val total = days.sumOf { it.dur }
    val worked = days.count { it.dur >= 60_000L }

    fun preset(count: Int) {
        to = today
        from = WorkStore.plusDays(today, -(count - 1))
    }

    if (pick) {
        val st = rememberDateRangePickerState(
            initialSelectedStartDateMillis = utcMillisOfLocalDay(from),
            initialSelectedEndDateMillis = utcMillisOfLocalDay(to)
        )
        DatePickerDialog(
            onDismissRequest = { pick = false },
            confirmButton = {
                TextButton(
                    enabled = st.selectedStartDateMillis != null,
                    onClick = {
                        val a = st.selectedStartDateMillis
                        val b = st.selectedEndDateMillis ?: a
                        if (a != null && b != null) {
                            from = localDayOfUtcMillis(minOf(a, b))
                            to = localDayOfUtcMillis(maxOf(a, b))
                        }
                        pick = false
                    }
                ) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { pick = false }) { Text("Отмена") } }
        ) {
            DateRangePicker(state = st, modifier = Modifier.height(520.dp))
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { StatusCard(tick) }
        item {
            OutlinedButton(
                onClick = { pick = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) { Text("С ${fullFmt.format(Date(from))} по ${fullFmt.format(Date(to))}") }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(onClick = { preset(7) }, label = { Text("7 дней") })
                AssistChip(onClick = { preset(14) }, label = { Text("14 дней") })
                AssistChip(onClick = { preset(30) }, label = { Text("30 дней") })
            }
            Text(
                "Всего за период: ${WorkStore.fmt(total).let { if (it == "—") "0 мин" else it }}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                "Дней: ${days.size}, рабочих: $worked",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        items(days.reversed()) { d ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(dayFmt.format(Date(d.ms)), style = MaterialTheme.typography.bodyLarge)
                    if (d.spans.isNotEmpty()) Text(d.spans, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    WorkStore.fmt(d.dur),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (d.dur > 0L) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                )
            }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Выбор в календаре отдаёт полночь по UTC; приводим к началу этого же числа по местному времени и обратно. */
private fun localDayOfUtcMillis(utc: Long): Long {
    val u = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
    return Calendar.getInstance().apply {
        clear(); set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

private fun utcMillisOfLocalDay(local: Long): Long {
    val l = Calendar.getInstance().apply { timeInMillis = local }
    return Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        clear(); set(l.get(Calendar.YEAR), l.get(Calendar.MONTH), l.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
    }.timeInMillis
}

@Composable
private fun StatusCard(tick: Int) {
    val ctx = LocalContext.current
    val c = remember(tick) { WorkStore.cfg(ctx) }
    val open = remember(tick) { WorkStore.open(ctx) }
    val now = System.currentTimeMillis()
    val title = when {
        !c.enabled -> "Учёт выключен (Настройки → Рабочее время)"
        open != null -> "В рабочей зоне • идёт запись"
        else -> WorkStore.statusMsg(ctx).ifBlank { "Ожидание первой проверки" }
    }
    val today = WorkStore.overlap(WorkStore.sessions(ctx), WorkStore.dayStart(now), WorkStore.plusDays(WorkStore.dayStart(now), 1), now)
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (open != null) Text("С ${WorkStore.hhmm(open.start)}", style = MaterialTheme.typography.bodyMedium)
            Text("Сегодня: ${WorkStore.fmt(today)}", style = MaterialTheme.typography.bodyLarge)
            val st = WorkStore.statusTime(ctx)
            if (c.enabled && st > 0L) {
                val d = WorkStore.statusDist(ctx)
                val dist = if (d >= 0f) ", до точки ${d.toInt()} м" else ""
                Text("Проверка в ${WorkStore.hhmm(st)}$dist", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MonthTab(tick: Int) {
    val ctx = LocalContext.current
    var offset by rememberSaveable { mutableIntStateOf(0) } // месяцев от текущего
    val now = System.currentTimeMillis()
    val sessions = remember(tick) { WorkStore.sessions(ctx) }
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, offset)
    }
    val monthStart = WorkStore.dayStart(cal.timeInMillis)
    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val monthTitle = capital(SimpleDateFormat("LLLL yyyy", RU).format(Date(monthStart)))
    val dayFmt = remember { SimpleDateFormat("EEE, d MMM", RU) }

    class Day(val ms: Long, val dur: Long, val spans: String)

    val days = remember(tick, offset) {
        (0 until daysInMonth).map { i ->
            val c2 = Calendar.getInstance().apply { timeInMillis = monthStart; add(Calendar.DAY_OF_MONTH, i) }
            val ds = c2.timeInMillis
            val de = WorkStore.plusDays(ds, 1)
            val spans = sessions.filter { s ->
                val e = if (s.end == 0L) now else s.end
                e > ds && s.start < de
            }.joinToString(", ") { s ->
                val a = WorkStore.hhmm(maxOf(s.start, ds))
                val b = if (s.end == 0L) "…" else if (s.end >= de) "24:00" else WorkStore.hhmm(s.end)
                "$a–$b"
            }
            Day(ds, WorkStore.overlap(sessions, ds, de, now), spans)
        }
    }
    val total = days.sumOf { it.dur }
    val worked = days.count { it.dur >= 60_000L }

    LazyColumn(Modifier.fillMaxSize()) {
        item { StatusCard(tick) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset-- }) { Text("←") }
                Text(monthTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { offset++ }, enabled = offset < 0) { Text("→") }
            }
            Text(
                "Всего за месяц: ${WorkStore.fmt(total).let { if (it == "—") "0 мин" else it }}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                "Рабочих дней: $worked",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        items(days) { d ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(dayFmt.format(Date(d.ms)), style = MaterialTheme.typography.bodyLarge)
                    if (d.spans.isNotEmpty()) Text(d.spans, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    WorkStore.fmt(d.dur),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (d.dur > 0L) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                )
            }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun YearTab(tick: Int) {
    val ctx = LocalContext.current
    var offset by rememberSaveable { mutableIntStateOf(0) } // лет от текущего
    val now = System.currentTimeMillis()
    val sessions = remember(tick) { WorkStore.sessions(ctx) }
    val year = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR) + offset

    class Mon(val title: String, val dur: Long, val days: Int)

    val months = remember(tick, offset) {
        (0 until 12).map { m ->
            val a = Calendar.getInstance().apply {
                clear(); set(year, m, 1, 0, 0, 0)
            }
            val b = Calendar.getInstance().apply { timeInMillis = a.timeInMillis; add(Calendar.MONTH, 1) }
            var daysWorked = 0
            var d = a.timeInMillis
            while (d < b.timeInMillis) {
                val n = WorkStore.plusDays(d, 1)
                if (WorkStore.overlap(sessions, d, n, now) >= 60_000L) daysWorked++
                d = n
            }
            Mon(
                capital(SimpleDateFormat("LLLL", RU).format(Date(a.timeInMillis))),
                WorkStore.overlap(sessions, a.timeInMillis, b.timeInMillis, now),
                daysWorked
            )
        }
    }
    val total = months.sumOf { it.dur }
    LazyColumn(Modifier.fillMaxSize()) {
        item { StatusCard(tick) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { offset-- }) { Text("←") }
                Text("$year год", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { offset++ }, enabled = offset < 0) { Text("→") }
            }
            Text(
                "Всего за год: ${WorkStore.fmt(total).let { if (it == "—") "0 мин" else it }}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        items(months) { m ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.title, style = MaterialTheme.typography.bodyLarge)
                    if (m.days > 0) Text("Рабочих дней: ${m.days}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    WorkStore.fmt(m.dur),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (m.dur > 0L) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                )
            }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun WorkSettingsTab(tick: Int, onPickPoint: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val admin = Session.isAdmin
    var cfg by remember(tick) { mutableStateOf(WorkStore.cfg(ctx)) }
    var radiusText by remember { mutableStateOf(cfg.radius.toString()) }
    var intervalText by remember { mutableStateOf(cfg.intervalMin.toString()) }
    var farText by remember { mutableStateOf(cfg.farIntervalMin.toString()) }
    var approachText by remember { mutableStateOf(cfg.approachKm.toString()) }
    var showBgDialog by remember { mutableStateOf(false) }
    var permTick by remember { mutableIntStateOf(0) }

    /** shared = общие настройки (их меняет администратор и они уходят на все телефоны); иначе только этот телефон. */
    fun upd(n: WorkCfg, shared: Boolean = true) {
        cfg = n
        WorkStore.saveCfg(ctx, n)
        if (shared && admin) WorkCloud.push(ctx, n)
        onChanged()
    }

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permTick++
        WorkTracker.ensureService(ctx)
    }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
        if (WorkTracker.hasFine(ctx)) {
            WorkTracker.ensureService(ctx)
            if (!WorkTracker.hasBackground(ctx)) showBgDialog = true
        } else {
            Toast.makeText(ctx, "Без доступа к геолокации учёт не работает", Toast.LENGTH_LONG).show()
        }
    }

    fun requestLocation() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        locLauncher.launch(perms.toTypedArray())
    }

    fun pickTime(current: Int, onSet: (Int) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> onSet(h * 60 + m) }, current / 60, current % 60, true).show()
    }

    if (showBgDialog) {
        AlertDialog(
            onDismissRequest = { showBgDialog = false },
            title = { Text("Доступ «Всегда»") },
            text = {
                Text("Чтобы время считалось, когда приложение закрыто, в настройках выберите для геолокации «Разрешать всегда».")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBgDialog = false
                    if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("Продолжить") }
            },
            dismissButton = { TextButton(onClick = { showBgDialog = false }) { Text("Позже") } }
        )
    }

    val dayList = listOf(
        Calendar.MONDAY to "Пн", Calendar.TUESDAY to "Вт", Calendar.WEDNESDAY to "Ср",
        Calendar.THURSDAY to "Чт", Calendar.FRIDAY to "Пт", Calendar.SATURDAY to "Сб", Calendar.SUNDAY to "Вс"
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Учёт моего рабочего времени", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Время идёт, пока телефон находится в рабочей зоне",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = cfg.enabled, onCheckedChange = { on ->
                if (on) {
                    if (!cfg.hasPoint) {
                        Toast.makeText(ctx, "Администратор ещё не выбрал рабочую точку", Toast.LENGTH_LONG).show()
                    } else {
                        upd(cfg.copy(enabled = true), shared = false)
                        if (!WorkTracker.hasFine(ctx)) requestLocation()
                        else {
                            WorkTracker.ensureService(ctx)
                            if (!WorkTracker.hasBackground(ctx)) showBgDialog = true
                        }
                    }
                } else {
                    upd(cfg.copy(enabled = false), shared = false)
                    WorkStore.closeOpen(ctx, System.currentTimeMillis())
                    WorkTracker.stopService(ctx)
                }
            })
        }

        Spacer(Modifier.height(16.dp))
        Text("Рабочая точка", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            if (cfg.hasPoint) String.format(Locale.US, "%.5f, %.5f", cfg.lat, cfg.lon) else "Точка не выбрана",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        if (admin) {
            Button(onClick = onPickPoint) { Text(if (cfg.hasPoint) "Изменить точку на карте" else "Выбрать на карте") }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = radiusText,
                onValueChange = { t ->
                    val s = t.filter { it.isDigit() }.take(5)
                    radiusText = s
                    val v = s.toIntOrNull()
                    if (v != null && v >= 10) upd(cfg.copy(radius = v))
                },
                label = { Text("Радиус рабочей зоны, метров (от 10)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Text("Зона подхода", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Пока до рабочей точки не больше N км, телефон проверяется часто — так приход на работу не потеряется между редкими проверками. " +
                    "Дальше зоны подхода проверка редкая (интервал сам сокращается при приближении). 0 км — не использовать.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = approachText,
                onValueChange = { t ->
                    val s = t.filter { it.isDigit() }.take(3)
                    approachText = s
                    val v = s.toIntOrNull()
                    if (v != null) upd(cfg.copy(approachKm = v))
                },
                label = { Text("Зона подхода, км (например 5)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = intervalText,
                onValueChange = { t ->
                    val s = t.filter { it.isDigit() }.take(4)
                    intervalText = s
                    val v = s.toIntOrNull()
                    if (v != null && v >= 1) upd(cfg.copy(intervalMin = v))
                },
                label = { Text("В зоне подхода проверять каждые, минут") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = farText,
                onValueChange = { t ->
                    val s = t.filter { it.isDigit() }.take(4)
                    farText = s
                    val v = s.toIntOrNull()
                    if (v != null && v >= 1) upd(cfg.copy(farIntervalMin = v))
                },
                label = { Text("Вне зоны подхода проверять каждые, минут") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Text("Полный день", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { pickTime(cfg.fromMin) { upd(cfg.copy(fromMin = it)) } }) {
                    Text("С ${WorkStore.minToText(cfg.fromMin)}")
                }
                OutlinedButton(onClick = { pickTime(cfg.toMin) { upd(cfg.copy(toMin = it)) } }) {
                    Text("До ${WorkStore.minToText(cfg.toMin)}")
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Дни проверки", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            FlowRowCompat {
                dayList.forEach { (d, name) ->
                    FilterChip(
                        selected = d in cfg.days,
                        onClick = {
                            val nd = if (d in cfg.days) cfg.days - d else cfg.days + d
                            upd(cfg.copy(days = nd, shortDays = cfg.shortDays.intersect(nd)))
                        },
                        label = { Text(name) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Короткие дни", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                "Отметьте дни, когда работают не полный день (например, суббота). В эти дни проверка и учёт заканчиваются в выбранное время.",
                style = MaterialTheme.typography.bodySmall
            )
            FlowRowCompat {
                dayList.filter { it.first in cfg.days }.forEach { (d, name) ->
                    FilterChip(
                        selected = d in cfg.shortDays,
                        onClick = { upd(cfg.copy(shortDays = if (d in cfg.shortDays) cfg.shortDays - d else cfg.shortDays + d)) },
                        label = { Text(name) }
                    )
                }
            }
            if (cfg.days.isEmpty()) Text("Сначала выберите дни проверки", style = MaterialTheme.typography.bodySmall)
            if (cfg.shortDays.isNotEmpty()) {
                OutlinedButton(
                    onClick = { pickTime(cfg.shortToMin) { upd(cfg.copy(shortToMin = it)) } },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("Короткий день до ${WorkStore.minToText(cfg.shortToMin)}") }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Настройки задал администратор", style = MaterialTheme.typography.titleSmall)
                    Text("Рабочая зона: радиус ${cfg.radius} м", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Полный день: ${WorkStore.minToText(cfg.fromMin)}–${WorkStore.minToText(cfg.toMin)}, дни: " +
                            (dayList.filter { it.first in cfg.days }.joinToString(", ") { it.second }.ifEmpty { "—" }),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (cfg.shortDays.isNotEmpty()) {
                        Text(
                            "Короткие дни: " + dayList.filter { it.first in cfg.shortDays }.joinToString(", ") { it.second } +
                                " — до ${WorkStore.minToText(cfg.shortToMin)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (cfg.approachKm > 0) {
                        Text(
                            "Зона подхода ${cfg.approachKm} км: проверка каждые ${cfg.intervalMin} мин",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text("Изменить может только администратор.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        val fine = remember(permTick, tick) { WorkTracker.hasFine(ctx) }
        val bg = remember(permTick, tick) { WorkTracker.hasBackground(ctx) }
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Разрешения", style = MaterialTheme.typography.titleSmall)
                Text("Геолокация: ${if (fine) "выдана" else "нет"}", style = MaterialTheme.typography.bodyMedium)
                Text("Работа в фоне («Всегда»): ${if (bg) "выдана" else "нет"}", style = MaterialTheme.typography.bodyMedium)
                if (!fine || !bg) {
                    TextButton(onClick = { if (!fine) requestLocation() else showBgDialog = true }) { Text("Выдать доступ") }
                }
                Text(
                    "Если телефон (Samsung, Xiaomi и др.) закрывает приложение в фоне, в настройках батареи выберите для него «Без ограничений».",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Простой перенос чипов по строкам: по 4 в ряд. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) { content() }
}

/** Выбор рабочей точки на карте OpenStreetMap: тап по карте ставит точку, круг показывает радиус. */
@Composable
private fun MapPicker(
    hasInit: Boolean, initLat: Double, initLon: Double, radius: Int,
    onPick: (Double, Double) -> Unit, onClose: () -> Unit
) {
    val ctx = LocalContext.current
    remember {
        Configuration.getInstance().apply {
            load(ctx, ctx.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.filesDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmtiles")
        }
        true
    }
    var picked by remember { mutableStateOf(if (hasInit) GeoPoint(initLat, initLon) else null) }
    var map by remember { mutableStateOf<MapView?>(null) }

    DisposableEffect(map) {
        map?.onResume()
        onDispose { map?.onPause() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Отмена") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                val l = WorkTracker.lastKnown(ctx)
                if (l == null) {
                    Toast.makeText(ctx, "Местоположение пока неизвестно. Включите геолокацию или нажмите на карту", Toast.LENGTH_LONG).show()
                } else {
                    val p = GeoPoint(l.latitude, l.longitude)
                    picked = p
                    map?.controller?.setZoom(17.0)
                    map?.controller?.animateTo(p)
                }
            }) { Text("Я здесь") }
        }
        Text(
            if (picked == null) "Нажмите на карту, чтобы поставить рабочую точку" else "Точка выбрана. Нажмите в другом месте, чтобы переставить",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { c ->
                MapView(c).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    val start = picked
                    if (start != null) {
                        controller.setZoom(17.0)
                        controller.setCenter(start)
                    } else {
                        val l = WorkTracker.lastKnown(c)
                        if (l != null) {
                            controller.setZoom(16.0)
                            controller.setCenter(GeoPoint(l.latitude, l.longitude))
                        } else {
                            controller.setZoom(5.0)
                            controller.setCenter(GeoPoint(51.0, 10.0))
                        }
                    }
                    overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            if (p != null) picked = GeoPoint(p.latitude, p.longitude)
                            return true
                        }
                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    }))
                    map = this
                }
            },
            update = { mv ->
                // оставляем только слой событий, остальное перерисовываем
                while (mv.overlays.size > 1) mv.overlays.removeAt(mv.overlays.size - 1)
                val p = picked
                if (p != null) {
                    val circle = Polygon(mv)
                    circle.points = Polygon.pointsAsCircle(p, radius.toDouble())
                    circle.fillPaint.color = 0x332196F3
                    circle.outlinePaint.color = 0xFF2196F3.toInt()
                    circle.outlinePaint.strokeWidth = 4f
                    mv.overlays.add(circle)
                    val marker = Marker(mv)
                    marker.position = p
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    mv.overlays.add(marker)
                }
                mv.invalidate()
            }
        )
        Button(
            onClick = { picked?.let { onPick(it.latitude, it.longitude) } },
            enabled = picked != null,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Сохранить точку") }
    }
}
