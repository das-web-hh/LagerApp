package ru.storage.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Файлы (фото, накладные): копия на телефоне + Google Диск (через Apps Script).
 * Имя файла — код: PR- (фото товара), RC- (фото приёма), IN- (накладная) + случайный номер.
 * Связь с товаром/партией хранится в Firebase (списки имён photos / invoices).
 */
object Media {
    /** Последняя ошибка отправки на Диск (пусто, если всё хорошо) и счётчик изменений очереди. */
    var lastError by mutableStateOf("")
    var version by mutableIntStateOf(0)

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploading = java.util.concurrent.atomic.AtomicBoolean(false)

    fun dir(ctx: Context): File = File(ctx.filesDir, "media").also { it.mkdirs() }
    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    fun isImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")
    }

    fun shownName(name: String): String = if (isImage(name)) name else name.substringAfter('_', name)

    private fun newPhotoName(kind: String) = kind + "-" + UUID.randomUUID().toString().replace("-", "").take(14) + ".jpg"

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9А-Яа-яЁё._-]"), "_").takeLast(60)

    /** Сжать фото до 2000 px и сохранить как JPEG (с учётом поворота). */
    private fun shrink(f: File) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var s = 1
            while (bounds.outWidth / s > 2000 || bounds.outHeight / s > 2000) s *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
                ?: return
            val rot = try {
                when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                0f
            }
            val out = if (rot != 0f)
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
            else bmp
            f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (e: Exception) {
        }
    }

    /** Выбранный из галереи/памяти файл → копия в приложении. Возвращает имя или null. */
    fun importUri(ctx: Context, uri: Uri, db: ProductDb, kind: String): String? {
        return try {
            val mime = ctx.contentResolver.getType(uri) ?: ""
            val isImg = mime.startsWith("image/")
            val name = if (isImg) newPhotoName(kind)
            else kind + "-" + UUID.randomUUID().toString().take(8) + "_" + safe(displayName(ctx, uri) ?: "file")
            val target = file(ctx, name)
            val ins = ctx.contentResolver.openInputStream(uri) ?: return null
            ins.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            if (isImg) shrink(target)
            db.addUpload(name)
            name
        } catch (e: Exception) {
            null
        }
    }

    /** Файл из кэша приложения (присланный скан) → в медиа-папку «как есть», без сжатия; в очередь на Диск. */
    fun importFile(ctx: Context, src: File, db: ProductDb, kind: String): String? {
        return try {
            val name = if (isImage(src.name)) {
                kind + "-" + UUID.randomUUID().toString().replace("-", "").take(14) + "." +
                    src.extension.lowercase().ifBlank { "jpg" }
            } else {
                kind + "-" + UUID.randomUUID().toString().take(8) + "_" + safe(src.name)
            }
            src.copyTo(file(ctx, name), overwrite = true)
            db.addUpload(name)
            name
        } catch (e: Exception) {
            null
        }
    }

    fun cameraTarget(ctx: Context, kind: String): Pair<String, Uri> {
        val name = newPhotoName(kind)
        val f = file(ctx, name)
        f.createNewFile()
        return name to FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    }

    fun finishCamera(ctx: Context, name: String, db: ProductDb) {
        shrink(file(ctx, name))
        db.addUpload(name)
    }

    /** Отправка ожидающих файлов на Google Диск в фоне; при ошибке повторится при следующем вызове. */
    fun uploadPending(ctx: Context, db: ProductDb) {
        val app = ctx.applicationContext
        if (!DriveCfg.ready(app)) return
        if (!uploading.compareAndSet(false, true)) return
        bg.launch {
            try {
                while (true) {
                    val list = db.pendingUploads()
                    if (list.isEmpty()) break
                    var failed = false
                    for (name in list) {
                        val f = file(app, name)
                        if (!f.exists() || f.length() == 0L) {
                            db.removeUpload(name)
                            continue
                        }
                        try {
                            DriveApi.upload(app, name, f)
                            db.removeUpload(name)
                            lastError = ""
                            version++
                        } catch (e: Exception) {
                            lastError = "Ошибка отправки на Диск: " + (e.message ?: e.javaClass.simpleName)
                            version++
                            failed = true
                            break
                        }
                    }
                    if (failed) break
                }
            } finally {
                uploading.set(false)
            }
        }
    }

    fun discard(ctx: Context, db: ProductDb, names: Collection<String>) {
        val app = ctx.applicationContext
        val pending = db.pendingUploads().toSet()
        for (n in names) {
            file(app, n).delete()
            db.removeUpload(n)
            if (n !in pending && DriveCfg.ready(app)) {
                bg.launch {
                    try {
                        DriveApi.delete(app, n)
                    } catch (e: Exception) {
                    }
                }
            }
        }
    }

    /** Локальный файл; если его нет на этом телефоне — скачивается с Google Диска. */
    suspend fun load(ctx: Context, name: String): File? = withContext(Dispatchers.IO) {
        val f = file(ctx, name)
        if (f.exists() && f.length() > 0) return@withContext f
        if (!DriveCfg.ready(ctx)) return@withContext null
        try {
            val bytes = DriveApi.download(ctx, name) ?: return@withContext null
            f.writeBytes(bytes)
            f
        } catch (e: Exception) {
            null
        }
    }

    fun thumb(f: File, target: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / s > target * 2 || o.outHeight / s > target * 2) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    fun open(ctx: Context, name: String) {
        val f = file(ctx, name)
        if (!f.exists()) return
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
        }
    }
}

@Composable
fun MediaStrip(names: List<String>, onRemove: ((String) -> Unit)?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    if (names.isEmpty()) {
        Text("—", style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { n ->
            Box(
                Modifier.size(96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { scope.launch { if (Media.load(ctx, n) != null) Media.open(ctx, n) } }
            ) {
                if (Media.isImage(n)) {
                    val bmp by produceState<Bitmap?>(null, n) {
                        value = Media.load(ctx, n)?.let { withContext(Dispatchers.IO) { Media.thumb(it, 200) } }
                    }
                    bmp?.let {
                        Image(
                            it.asImageBitmap(), null,
                            Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )
                    }
                } else {
                    Text(
                        "📄 " + Media.shownName(n),
                        Modifier.align(Alignment.Center).padding(6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onRemove != null) {
                    Text(
                        "✕",
                        Modifier.align(Alignment.TopEnd)
                            .background(Color(0x99000000))
                            .clickable { onRemove(n) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White
                    )
                }
            }
        }
    }
}

/** Кнопки «Снять», «Фото из галереи» и (если нужно) «Файл». Добавленное имя файла приходит в onAdded. */
@Composable
fun MediaButtons(kind: String, allowFile: Boolean, onAdded: (String) -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val cb by rememberUpdatedState(onAdded)
    var pendingCam by remember { mutableStateOf<String?>(null) }

    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val name = pendingCam
        pendingCam = null
        if (name != null) {
            if (ok) scope.launch {
                withContext(Dispatchers.IO) { Media.finishCamera(ctx, name, db) }
                cb(name)
            } else Media.file(ctx, name).delete()
        }
    }
    fun startCamera() {
        val (name, uri) = Media.cameraTarget(ctx, kind)
        pendingCam = name
        camLauncher.launch(uri)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        scope.launch {
            uris.forEach { u ->
                val n = withContext(Dispatchers.IO) { Media.importUri(ctx, u, db, kind) }
                if (n != null) cb(n)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) startCamera() else permLauncher.launch(Manifest.permission.CAMERA)
        }) { Text("📷 Снять") }
        OutlinedButton(onClick = { pickLauncher.launch("image/*") }) { Text("🖼 Галерея") }
        if (allowFile) OutlinedButton(onClick = { pickLauncher.launch("*/*") }) { Text("📄 Файл") }
    }
}
