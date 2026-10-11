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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

data class IncomingFile(val name: String, val path: String)

/** Файлы, пришедшие через «Поделиться» / «Открыть через приложение» / выбор из «Приёма». */
object Incoming {
    /** Файлы, которым ещё не выбран способ приёма (спрашиваем по очереди). */
    val pending = mutableStateListOf<IncomingFile>()

    /** Партия, открытая на экране сейчас. null — обычный интерфейс; все партии при этом продолжают жить в AutoReceiveHolder. */
    var openPath by mutableStateOf<String?>(null)

    /** Создать партию (или вернуться к уже существующей) и, если open, открыть её. */
    fun start(ctx: Context, f: IncomingFile, mode: String, open: Boolean = true) {
        pending.remove(f)
        AutoReceiveHolder.create(ctx.applicationContext, f, mode == "name")
        if (open) openPath = f.path
    }

    fun discardPending(f: IncomingFile) {
        pending.remove(f)
        try {
            File(f.path).delete()
        } catch (e: Exception) {
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')

    /**
     * Копия файла во внутреннем кэше (доступ по content:// может пропасть). Вызывать не в главном потоке.
     * У каждого файла своя папка — два файла с одинаковым именем не затирают друг друга, партии не путаются.
     * startMode == null — спросить способ приёма; "auto"/"name" — сразу создать партию (open — открыть её на экране).
     */
    fun accept(ctx: Context, uri: Uri, startMode: String? = null, open: Boolean = true) {
        try {
            val app = ctx.applicationContext
            val name = displayName(app, uri) ?: "file.pdf"
            val dir = File(app.cacheDir, "incoming/" + UUID.randomUUID().toString()).also { it.mkdirs() }
            val target = File(dir, name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            val ins = app.contentResolver.openInputStream(uri) ?: return
            ins.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            val f = IncomingFile(name, target.path)
            if (startMode == null) pending.add(f) else start(app, f, startMode, open)
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamList(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()

    fun handleIntent(ctx: Context, intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamList(intent)
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) thread { uris.forEach { accept(ctx, it) } }
    }
}

@Composable
fun IncomingChoiceDialog(f: IncomingFile) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { Incoming.discardPending(f) },
        title = { Text("Что сделать с файлом?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(f.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Button(onClick = { Incoming.start(ctx, f, "auto") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Автоприём")
                }
                OutlinedButton(onClick = { Incoming.start(ctx, f, "name") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Приём по имени")
                }
            }
        },
        confirmButton = { TextButton(onClick = { Incoming.discardPending(f) }) { Text("Отмена") } }
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
