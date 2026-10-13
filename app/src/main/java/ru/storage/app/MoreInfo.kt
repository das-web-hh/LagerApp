package ru.storage.app

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/* Окна раздела «Еще»: «Лицензия», «О приложении», «О системе». */

private class InfoGroup(val title: String, val rows: List<Pair<String, String>>)

private val FEATURES = listOf(
    "Приём товара: ручной, по наименованию и автоприём накладных с распознаванием",
    "Итог приёма и сверка товаров по партиям",
    "Приём B-Ware и инвентарь повреждённого товара",
    "Инвентаризация и подсчёт: барабан цифр, голос, вибрация",
    "Отправка товара",
    "Каталог товаров, сканер штрихкодов и QR-кодов",
    "История приёмок и шаблоны накладных",
    "Печать документов на немецком, русском и английском, автоподпись",
    "Google Диск для фото и накладных, облачные настройки аккаунта"
)

private val NEWS = listOf(
    "Документы: импорт PDF, Excel и текстовых файлов, их просмотр и печать",
    "Текстовый лист в редакторе виден целиком на экране; рамка листа печатается на каждой странице",
    "Работа → Выгрузка: счёт палет (евро и Einweg: большие, обычные, маленькие), номер машины и прицепа",
    "Рабочее время: радиус подхода (км) с частой проверкой, полные и короткие дни, период по датам",
    "Настройки рабочего времени перенесены в Настройки (меняет только администратор)",
    "Автоприём: выбор нескольких папок (в т. ч. Google Диск), PDF, картинки и Excel, пометки статуса, отправка файла в «По наименованию»"
)

private val OPEN_SOURCE = listOf(
    "Jetpack Compose, AndroidX, CameraX" to "Apache License 2.0",
    "Kotlin и kotlinx.coroutines" to "Apache License 2.0",
    "Firebase Firestore" to "Apache License 2.0, условия Firebase",
    "Google ML Kit (штрихкоды, текст, сканер документов)" to "Условия использования ML Kit",
    "Guava" to "Apache License 2.0"
)

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Warehouse", text))
    Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
}

private fun groupsText(groups: List<InfoGroup>): String =
    groups.joinToString("\n\n") { g -> g.title + "\n" + g.rows.joinToString("\n") { "${it.first}: ${it.second}" } }

private fun packageInfo(ctx: Context): PackageInfo? = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0)
} catch (e: Exception) {
    null
}

@Suppress("DEPRECATION")
private fun codeOf(pi: PackageInfo): Long =
    if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

private fun fmtBytes(b: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    val l = Locale.getDefault()
    return when {
        b >= gb -> String.format(l, "%.1f ГБ", b / gb)
        b >= mb -> String.format(l, "%.1f МБ", b / mb)
        b >= kb -> String.format(l, "%.0f КБ", b / kb)
        else -> "$b Б"
    }
}

private fun dirSize(f: File?): Long = when {
    f == null || !f.exists() -> 0L
    f.isFile -> f.length()
    else -> f.listFiles()?.sumOf { dirSize(it) } ?: 0L
}

@Composable
private fun InfoPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GroupBlock(g: InfoGroup) {
    Text(
        g.title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    g.rows.forEach { (k, v) ->
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(k, style = MaterialTheme.typography.labelMedium)
            Text(v.ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun BulletBlock(title: String, items: List<String>) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
    items.forEach {
        Text("•  $it", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 3.dp))
    }
}

// ---------------------------------------------------------------- О приложении

private fun appGroups(ctx: Context): List<InfoGroup> {
    val pi = packageInfo(ctx)
    val rows = mutableListOf<Pair<String, String>>()
    rows.add("Название" to "Warehouse")
    rows.add("Версия" to (pi?.versionName ?: "—"))
    // versionCode в сборке = минуты с 1970 года, поэтому из него получается время сборки
    if (pi != null) rows.add("Дата сборки" to fmtTime(codeOf(pi) * 60000L))
    if (pi != null) rows.add("Установлено" to fmtTime(pi.firstInstallTime))
    if (pi != null) rows.add("Последнее обновление" to fmtTime(pi.lastUpdateTime))
    rows.add("Пакет" to ctx.packageName)
    val data = mutableListOf<Pair<String, String>>()
    try {
        val db = ProductDb.get(ctx)
        data.add("Товаров в каталоге" to db.countProducts().toString())
        data.add("Приёмок в истории" to db.countReceipts().toString())
    } catch (e: Exception) {
        data.add("Каталог и история" to "недоступны")
    }
    return listOf(InfoGroup("Приложение", rows), InfoGroup("Данные", data))
}

@Composable
fun AboutAppScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var groups by remember { mutableStateOf<List<InfoGroup>>(emptyList()) }
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { appGroups(ctx) } }
    val version = packageInfo(ctx)?.versionName ?: ""

    InfoPage("О приложении", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(72.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Warehouse", style = MaterialTheme.typography.headlineSmall)
                Text("Склад: приём, инвентаризация, отправка", style = MaterialTheme.typography.bodyMedium)
            }
        }
        groups.forEach { GroupBlock(it) }
        BulletBlock("Возможности", FEATURES)
        BulletBlock("Что нового в $version", NEWS)
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { copyText(ctx, "Warehouse $version\n\n" + groupsText(groups)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Скопировать данные о версии") }
    }
}

// ---------------------------------------------------------------- О системе

private fun systemGroups(ctx: Context): List<InfoGroup> {
    val device = listOf(
        "Устройство" to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Обновление безопасности" to (Build.VERSION.SECURITY_PATCH ?: "—"),
        "Язык системы" to Locale.getDefault().displayName,
        "Часовой пояс" to TimeZone.getDefault().id
    )

    val dm = ctx.resources.displayMetrics
    val hw = mutableListOf<Pair<String, String>>()
    hw.add("Экран" to "${dm.widthPixels} × ${dm.heightPixels}, ${dm.densityDpi} dpi")
    try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        hw.add("Оперативная память" to "свободно ${fmtBytes(mi.availMem)} из ${fmtBytes(mi.totalMem)}")
    } catch (e: Exception) {
    }
    try {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.blockCountLong * st.blockSizeLong
        val free = st.availableBlocksLong * st.blockSizeLong
        hw.add("Память телефона" to "свободно ${fmtBytes(free)} из ${fmtBytes(total)}")
    } catch (e: Exception) {
    }
    try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level >= 0 && scale > 0) {
            hw.add("Батарея" to "${level * 100 / scale}%" + if (charging) ", заряжается" else "")
        }
    } catch (e: Exception) {
    }

    val net = mutableListOf<Pair<String, String>>()
    try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val kind = when {
            caps == null -> "нет подключения"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "подключено"
        }
        val web = when {
            caps == null -> ""
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> ", интернет есть"
            else -> ", без доступа в интернет"
        }
        net.add("Сеть" to kind + web)
    } catch (e: Exception) {
        net.add("Сеть" to "не удалось определить")
    }
    try {
        net.add("Облако" to "Firebase, проект " + com.google.firebase.FirebaseApp.getInstance().options.projectId)
    } catch (e: Exception) {
    }
    net.add("Аккаунт (логин)" to Session.login)

    val cam = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    val dataSize = dirSize(ctx.getDatabasePath("catalog.db").parentFile) + dirSize(ctx.filesDir)
    val app = listOf(
        "Камера" to if (cam) "разрешена" else "не разрешена",
        "Данные приложения" to fmtBytes(dataSize),
        "Кэш" to fmtBytes(dirSize(ctx.cacheDir))
    )
    return listOf(
        InfoGroup("Устройство", device),
        InfoGroup("Железо", hw),
        InfoGroup("Сеть и аккаунт", net),
        InfoGroup("Приложение на телефоне", app)
    )
}

@Composable
fun AboutSystemScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var groups by remember { mutableStateOf<List<InfoGroup>>(emptyList()) }
    LaunchedEffect(tick) { groups = withContext(Dispatchers.IO) { systemGroups(ctx) } }
    val version = packageInfo(ctx)?.versionName ?: ""

    InfoPage("О системе", onBack) {
        Text("Данные телефона, на котором работает приложение. Пригодятся, если нужно описать проблему.", style = MaterialTheme.typography.bodySmall)
        groups.forEach { GroupBlock(it) }
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = { tick++ }, modifier = Modifier.fillMaxWidth()) { Text("Обновить") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { copyText(ctx, "Warehouse $version\n\n" + groupsText(groups)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Скопировать для поддержки") }
    }
}

// ---------------------------------------------------------------- Лицензия

private fun installId(ctx: Context): String {
    val sp = ctx.getSharedPreferences("license", Context.MODE_PRIVATE)
    val existing = sp.getString("install_id", null)
    if (existing != null) return existing
    val id = UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
    sp.edit().putString("install_id", id).apply()
    return id
}

@Composable
fun LicenseScreen(profile: UserProfile, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val pi = remember { packageInfo(ctx) }
    val id = remember { installId(ctx) }
    val licensee = "${profile.firstName} ${profile.lastName}".trim().ifBlank { Session.login }
    val group = InfoGroup(
        "Лицензия",
        listOf(
            "Продукт" to "Warehouse ${pi?.versionName ?: ""}".trim(),
            "Тип лицензии" to "Для внутреннего использования",
            "Лицензиат" to licensee,
            "Аккаунт (логин)" to Session.login,
            "Идентификатор установки" to id,
            "Первая установка" to (pi?.let { fmtTime(it.firstInstallTime) } ?: "—"),
            "Срок действия" to "Бессрочно"
        )
    )

    InfoPage("Лицензия", onBack) {
        GroupBlock(group)
        BulletBlock(
            "Условия использования",
            listOf(
                "Приложение предназначено для учёта товара на складе владельца и его сотрудников.",
                "Копирование и передача приложения или доступа к нему третьим лицам без согласия владельца не допускается.",
                "Данные склада (товары, приёмки, фото, накладные) принадлежат владельцу и хранятся на устройстве и в облаке Firebase.",
                "Приложение предоставляется «как есть»: перед печатью и отправкой документов проверяйте данные."
            )
        )
        Text(
            "Сторонние компоненты",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
        )
        OPEN_SOURCE.forEach { (name, lic) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(lic, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = { copyText(ctx, id) }, modifier = Modifier.fillMaxWidth()) {
            Text("Скопировать идентификатор установки")
        }
    }
}
