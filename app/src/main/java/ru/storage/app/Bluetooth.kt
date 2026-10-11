package ru.storage.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Bluetooth-сканеры штрихкодов.
 * 1) «Как клавиатура» (HID): сканер спарен в настройках телефона и «печатает» код с Enter. Выбранный сканер перехватывается
 *    и код уходит в поиск (Главная), где бы ни стоял курсор.
 * 2) «Последовательный порт» (SPP): приложение само подключается к спаренному сканеру и читает коды построчно.
 */
object BtScan {
    var last by mutableStateOf("")
    var version by mutableIntStateOf(0)
    val log = mutableStateListOf<String>()
    var state by mutableStateOf("Не подключён")
    var learning by mutableStateOf(false)
    var scannerName by mutableStateOf("")
        private set

    /** Код ждёт, пока его заберёт поиск на Главной. */
    @Volatile var pending: String? = null

    /** Открыто окно Bluetooth: коды показываются в нём и не уходят в поиск. */
    @Volatile var testing = false

    private var scannerKey = ""
    private var loaded = false
    private val buf = StringBuilder()
    private var socket: BluetoothSocket? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("bluetooth", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val s = sp(ctx)
        scannerKey = s.getString("hidKey", "") ?: ""
        scannerName = s.getString("hidName", "") ?: ""
    }

    fun post(code: String) {
        val c = code.trim()
        if (c.isEmpty()) return
        last = c
        log.add(0, fmt.format(Date()) + "  " + c)
        while (log.size > 30) log.removeAt(log.size - 1)
        if (!testing) pending = c
        version++
    }

    private fun keyOf(d: InputDevice) = "${d.vendorId}:${d.productId}:${d.name}"

    fun forgetScanner(ctx: Context) {
        scannerKey = ""
        scannerName = ""
        sp(ctx).edit().remove("hidKey").remove("hidName").apply()
    }

    /** Вызывается из Activity для каждого нажатия клавиши. true — нажатие забрал сканер. */
    fun onKey(ctx: Context, e: KeyEvent): Boolean {
        val d = e.device ?: return false
        if (d.isVirtual || (d.sources and InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD) return false
        if (d.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        if (learning) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                scannerKey = keyOf(d)
                scannerName = d.name
                learning = false
                sp(ctx).edit().putString("hidKey", scannerKey).putString("hidName", scannerName).apply()
                buf.setLength(0)
            }
            return true
        }
        if (scannerKey.isEmpty() || keyOf(d) != scannerKey) return false
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> {
                    if (buf.isNotEmpty()) post(buf.toString())
                    buf.setLength(0)
                }
                else -> {
                    val ch = e.unicodeChar
                    if (ch != 0 && !Character.isISOControl(ch)) buf.append(ch.toChar())
                }
            }
        }
        return true
    }

    private fun adapter(ctx: Context): BluetoothAdapter? =
        (ctx.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun paired(ctx: Context): List<BluetoothDevice> = try {
        if (!hasPermission(ctx)) emptyList() else adapter(ctx)?.bondedDevices?.toList() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun isEnabled(ctx: Context): Boolean = try { adapter(ctx)?.isEnabled == true } catch (e: Exception) { false }

    @SuppressLint("MissingPermission")
    fun connect(ctx: Context, dev: BluetoothDevice) {
        val app = ctx.applicationContext
        disconnect()
        val name = try { dev.name ?: dev.address } catch (e: Exception) { dev.address }
        state = "Подключение к $name…"
        sp(app).edit().putString("sppAddress", dev.address).apply()
        thread {
            var s: BluetoothSocket? = null
            try {
                adapter(app)?.cancelDiscovery()
                s = dev.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                s.connect()
                socket = s
                state = "Подключён: $name"
                val ins = s.inputStream
                val line = StringBuilder()
                while (true) {
                    val b = ins.read()
                    if (b < 0) break
                    if (b == 13 || b == 10) {
                        if (line.isNotEmpty()) post(line.toString())
                        line.setLength(0)
                    } else if (b >= 32) line.append(b.toChar())
                }
                state = "Отключён"
            } catch (e: Exception) {
                state = if (socket == null) "Не удалось подключиться: ${e.message ?: "ошибка"}" else "Отключён"
            } finally {
                try { s?.close() } catch (e: Exception) { }
                if (socket === s) socket = null
            }
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (e: Exception) { }
        socket = null
        state = "Не подключён"
    }

    fun connected(): Boolean = socket != null

    /** При запуске приложения подключаемся к последнему сканеру SPP. */
    fun autoConnect(ctx: Context) {
        load(ctx)
        val addr = sp(ctx).getString("sppAddress", null) ?: return
        if (socket != null || !hasPermission(ctx) || !isEnabled(ctx)) return
        paired(ctx).firstOrNull { it.address == addr }?.let { connect(ctx, it) }
    }

    fun forgetSpp(ctx: Context) {
        sp(ctx).edit().remove("sppAddress").apply()
        disconnect()
    }

    fun savedSpp(ctx: Context): String? = sp(ctx).getString("sppAddress", null)
}

@SuppressLint("MissingPermission")
@Composable
fun BluetoothScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var permTick by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { BtScan.load(ctx) }
    DisposableEffect(Unit) {
        BtScan.testing = true
        onDispose { BtScan.testing = false; BtScan.learning = false }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val hasPerm = remember(permTick) { BtScan.hasPermission(ctx) }
    val enabled = remember(permTick, refresh) { BtScan.isEnabled(ctx) }
    val devices = remember(permTick, refresh) { BtScan.paired(ctx) }
    val saved = remember(refresh, BtScan.state) { BtScan.savedSpp(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
        }
        Text("Bluetooth", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Bluetooth-сканеры штрихкодов. Сначала спарьте сканер в настройках телефона, затем выберите способ подключения ниже.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            try { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } catch (e: Exception) {
                Toast.makeText(ctx, "Не удалось открыть настройки", Toast.LENGTH_SHORT).show()
            }
        }) { Text("Настройки Bluetooth телефона") }
        if (!enabled) {
            Text("Bluetooth на телефоне выключен", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }

        // ---------- как клавиатура
        Spacer(Modifier.height(20.dp))
        Text("Сканер как клавиатура", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Самый частый режим: сканер «печатает» код и жмёт Enter. Нажмите «Определить сканер» и отсканируйте любой штрихкод — после этого коды с него будут попадать в поиск на Главной, где бы ни стоял курсор.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)
        )
        Text(
            if (BtScan.scannerName.isNotEmpty()) "Сканер: ${BtScan.scannerName}" else "Сканер не выбран",
            style = MaterialTheme.typography.bodyLarge
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { BtScan.learning = !BtScan.learning }) {
                Text(if (BtScan.learning) "Отсканируйте код…" else "Определить сканер")
            }
            if (BtScan.scannerName.isNotEmpty()) {
                OutlinedButton(onClick = { BtScan.forgetScanner(ctx); refresh++ }) { Text("Забыть") }
            }
        }

        // ---------- SPP
        Spacer(Modifier.height(20.dp))
        Text("Сканер по Bluetooth-порту (SPP)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Для сканеров, у которых включён режим «SPP / Serial». Приложение само подключится к выбранному сканеру и будет принимать коды.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)
        )
        Text("Состояние: ${BtScan.state}", style = MaterialTheme.typography.bodyLarge)
        if (!hasPerm) {
            Button(onClick = { if (Build.VERSION.SDK_INT >= 31) permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                modifier = Modifier.padding(top = 4.dp)) { Text("Разрешить доступ к Bluetooth") }
        } else if (devices.isEmpty()) {
            Text("Спаренных устройств нет", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        } else {
            devices.forEach { d ->
                val name = try { d.name ?: d.address } catch (e: Exception) { d.address }
                Card(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(d.address + if (d.address == saved) " · запоминается" else "", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { BtScan.connect(ctx, d); refresh++ }) { Text("Подключить") }
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { BtScan.disconnect(); refresh++ }, enabled = BtScan.connected()) { Text("Отключить") }
                if (saved != null) OutlinedButton(onClick = { BtScan.forgetSpp(ctx); refresh++ }) { Text("Забыть") }
            }
        }

        // ---------- проверка
        Spacer(Modifier.height(20.dp))
        Text("Проверка", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "Отсканируйте код — он появится здесь. Пока это окно открыто, коды в поиск не отправляются.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)
        )
        Text(
            if (BtScan.last.isEmpty()) "—" else BtScan.last,
            style = MaterialTheme.typography.headlineSmall
        )
        BtScan.log.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(24.dp))
    }
}
