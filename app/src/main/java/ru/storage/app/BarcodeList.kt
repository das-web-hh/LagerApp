package ru.storage.app

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Список отсканированных штрих-кодов хранится на телефоне: 200 кодов не пропадут, если приложение закроют. */
object BarcodeList {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("barcode_list", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<String> = try {
        val a = JSONArray(sp(ctx).getString("codes", "[]") ?: "[]")
        List(a.length()) { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(ctx: Context, codes: List<String>) {
        sp(ctx).edit().putString("codes", JSONArray(codes).toString()).apply()
    }

    /** Системное «Поделиться»: выбор WhatsApp, Telegram и т.д. Один код — одна строка. */
    fun share(ctx: Context, codes: List<String>) {
        if (codes.isEmpty()) return
        try {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, codes.joinToString("\n"))
            ctx.startActivity(Intent.createChooser(i, "Поделиться списком").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "Не удалось открыть «Поделиться»", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * «Создать список»: сверху кнопка «Сканировать», ниже список отсканированных кодов (новые сверху).
 * Сканер открывается в непрерывном режиме — можно сканировать 50, 100, 200 кодов подряд и нажать «Готово».
 * Внизу «Поделиться» (системное меню) и «Очистить».
 */
@Composable
fun BarcodeListScreen(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { ProductDb.get(ctx) }
    val codes = remember { mutableStateListOf<String>().also { it.addAll(BarcodeList.load(ctx)) } }
    val names = remember { mutableStateMapOf<String, String>() }
    var scanning by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun persist() = BarcodeList.save(ctx, codes.toList())

    fun lookup(code: String) {
        if (names.containsKey(code)) return
        names[code] = ""
        scope.launch {
            val n = withContext(Dispatchers.IO) { try { db.findByBarcode(code)?.name.orEmpty() } catch (e: Exception) { "" } }
            names[code] = n.trim()
        }
    }

    fun add(code: String) {
        codes.add(code)
        persist()
        Haptics.pulse(ctx, Haptics.TAP)
        ScanFeedback.beep(true)
        lookup(code)
    }

    LaunchedEffect(Unit) { codes.toList().distinct().forEach { lookup(it) } }

    if (scanning) {
        // экран не гаснет, пока идёт сканирование
        val hostView = LocalView.current
        DisposableEffect(Unit) {
            val old = hostView.keepScreenOn
            hostView.keepScreenOn = true
            onDispose { hostView.keepScreenOn = old }
        }
        ScannerScreen(
            prefs = prefs,
            continuous = true,
            status = "В списке: ${codes.size}" + (codes.lastOrNull()?.let { "\n$it" } ?: ""),
            onResult = { add(it) },
            onClose = { scanning = false }
        )
        return
    }

    BackHandler(onBack = onBack)

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить список?") },
            text = { Text("Все ${codes.size} кодов будут удалены.") },
            confirmButton = {
                TextButton(onClick = { codes.clear(); persist(); confirmClear = false }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }

    val counts = codes.groupingBy { it }.eachCount()

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Назад") }
            Spacer(Modifier.weight(1f))
            Text("Список: ${codes.size}", style = MaterialTheme.typography.titleMedium)
        }
        Button(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Сканировать", fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))

        if (codes.isEmpty()) {
            Text(
                "Нажмите «Сканировать» и сканируйте штрих-коды подряд — они появятся здесь. Потом «Поделиться» отправит весь список в WhatsApp, Telegram и т.д.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                // новые сверху; номер — порядок сканирования
                items((codes.size - 1 downTo 0).toList()) { i ->
                    val code = codes[i]
                    val name = names[code].orEmpty()
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", modifier = Modifier.width(44.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 17.sp)
                            if (name.isNotBlank()) {
                                Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if ((counts[code] ?: 0) > 1) {
                                Text("повтор (всего ${counts[code]})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        IconButton(onClick = { codes.removeAt(i); persist() }) {
                            Icon(Icons.Default.Close, contentDescription = "Удалить")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { confirmClear = true }, enabled = codes.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("Очистить") }
            Button(
                onClick = { BarcodeList.share(ctx, codes.toList()) }, enabled = codes.isNotEmpty(),
                modifier = Modifier.weight(2f)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Поделиться")
            }
        }
    }
}
