package ru.storage.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.concurrent.thread

data class IncomingFile(val name: String, val path: String)

/** Файл, пришедший через «Поделиться» / «Открыть через приложение». */
object Incoming {
    var file by mutableStateOf<IncomingFile?>(null)
    var mode by mutableStateOf<String?>(null) // null — выбор, "auto" — автоприём, "name" — приём по имени

    fun clear() {
        file = null
        mode = null
    }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Копия файла во внутреннем кэше (доступ по content:// может пропасть). Вызывать не в главном потоке. */
    fun accept(ctx: Context, uri: Uri, startMode: String? = null) {
        try {
            val app = ctx.applicationContext
            val name = displayName(app, uri) ?: "file.pdf"
            val dir = File(app.cacheDir, "incoming").also { it.mkdirs() }
            val target = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            val ins = app.contentResolver.openInputStream(uri) ?: return
            ins.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            mode = startMode
            file = IncomingFile(name, target.path)
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    fun handleIntent(ctx: Context, intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> streamExtra(intent)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) thread { accept(ctx, uri) }
    }
}

@Composable
fun IncomingChoiceDialog(f: IncomingFile) {
    AlertDialog(
        onDismissRequest = { Incoming.clear() },
        title = { Text("Что сделать с файлом?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(f.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Button(onClick = { Incoming.mode = "auto" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Автоприём")
                }
                OutlinedButton(onClick = { Incoming.mode = "name" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Приём по имени")
                }
            }
        },
        confirmButton = { TextButton(onClick = { Incoming.clear() }) { Text("Отмена") } }
    )
}

@Composable
fun NameReceiveScreen(f: IncomingFile, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Приём по имени", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Файл:", style = MaterialTheme.typography.labelMedium)
        Text(f.name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Дата и время из имени: ${Stamp.parse(f.name)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Text("Приём по имени в разработке.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}
