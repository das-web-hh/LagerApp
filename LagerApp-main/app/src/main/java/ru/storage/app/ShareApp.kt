package ru.storage.app

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

/* Отправка самого приложения (APK) на другое устройство: Telegram, WhatsApp, почта, Bluetooth и любые другие способы. */

private const val APK_MIME = "application/vnd.android.package-archive"

private fun versionName(ctx: Context): String = try {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
} catch (e: Exception) {
    ""
}

/** Копия установленного APK во внутренней папке и ссылка на неё для других приложений. */
private fun apkFile(ctx: Context): File {
    val src = File(ctx.applicationInfo.sourceDir)
    val dir = File(ctx.cacheDir, "export").also { it.mkdirs() }
    dir.listFiles()?.filter { it.name.endsWith(".apk") }?.forEach { if (it.name != "Warehouse-${versionName(ctx)}.apk") it.delete() }
    val dst = File(dir, "Warehouse-${versionName(ctx)}.apk")
    if (!dst.exists() || dst.length() != src.length()) src.copyTo(dst, overwrite = true)
    return dst
}

private fun apkUri(ctx: Context): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apkFile(ctx))

private fun baseIntent(ctx: Context): Intent = Intent(Intent.ACTION_SEND).apply {
    type = APK_MIME
    putExtra(Intent.EXTRA_STREAM, apkUri(ctx))
    putExtra(Intent.EXTRA_SUBJECT, "Warehouse ${versionName(ctx)}")
    putExtra(Intent.EXTRA_TEXT, "Приложение Warehouse ${versionName(ctx)}. Откройте файл и установите (разрешите установку из этого источника).")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

/** Отправка через конкретное приложение: перебираем пакеты, пока какой-то не найдётся. */
private fun sendVia(ctx: Context, name: String, packages: List<String>) {
    try {
        for (p in packages) {
            val i = baseIntent(ctx).setPackage(p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(i)
                return
            } catch (e: ActivityNotFoundException) {
            }
        }
        Toast.makeText(ctx, "$name не установлен. Выберите другой способ", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Не удалось отправить: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun sendEmail(ctx: Context) {
    try {
        val i = baseIntent(ctx)
        i.selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        ctx.startActivity(Intent.createChooser(i, "Отправить по почте").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Почтовое приложение не найдено", Toast.LENGTH_LONG).show()
    }
}

private fun sendChooser(ctx: Context) {
    try {
        ctx.startActivity(Intent.createChooser(baseIntent(ctx), "Отправить приложение").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Не удалось отправить: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/** Копия APK в «Загрузки» — оттуда файл можно передать кабелем, через Nearby Share и т.д. */
private fun saveToDownloads(ctx: Context) {
    if (Build.VERSION.SDK_INT < 29) {
        Toast.makeText(ctx, "На этой версии Android используйте «Другие способы»", Toast.LENGTH_LONG).show()
        return
    }
    try {
        val src = apkFile(ctx)
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, src.name)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw RuntimeException("не удалось создать файл")
        ctx.contentResolver.openOutputStream(uri)?.use { o -> src.inputStream().use { it.copyTo(o) } }
        Toast.makeText(ctx, "Сохранено в Загрузки: ${src.name}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Не удалось сохранить: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun ShareAppScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("← Назад") }
        }
        Text("Отправить приложение", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Копия этого приложения (версия ${versionName(ctx)}) уходит на другое устройство. Установленное на нём приложение обновится поверх, если оно из этой же сборки.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        val m = Modifier.fillMaxWidth()
        Button(onClick = { sendVia(ctx, "Telegram", listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")) }, modifier = m) { Text("Telegram") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { sendVia(ctx, "WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b")) }, modifier = m) { Text("WhatsApp") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { sendEmail(ctx) }, modifier = m) { Text("Эл. почта") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { sendVia(ctx, "Bluetooth", listOf("com.android.bluetooth", "com.samsung.android.bluetooth")) }, modifier = m) { Text("Bluetooth") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { sendChooser(ctx) }, modifier = m) { Text("Другие способы…") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { saveToDownloads(ctx) }, modifier = m) { Text("Сохранить файл в «Загрузки»") }
        Spacer(Modifier.height(20.dp))
        Text("Как установить на другом устройстве", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "1. Получите файл .apk и откройте его.\n2. Если Android спросит, разрешите установку из этого источника (Telegram, Chrome, Файлы и т.д.).\n3. Нажмите «Установить».\n\nЕсли мессенджер отказывается отправлять .apk, сохраните файл в «Загрузки» и передайте любым другим способом (почта, Bluetooth, Nearby Share, кабель).",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
