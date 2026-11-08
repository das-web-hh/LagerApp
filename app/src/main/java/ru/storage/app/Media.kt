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
 * Имя файла — код: PR- (фото товара), RC- (фото приёма), IN- (накладная), BW-КОД- (фото испорченного товара B-Ware) + случайный номер.
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

    /** Фото по https-адресу (страница товара сайта) → файл в приложении, в очередь на Диск. Вызывать не в главном потоке. null — не вышло. */
    fun importUrl(ctx: Context, url: String, db: ProductDb, kind: String): String? =
        importUrlAs(ctx, url, db, newPhotoName(kind))

    /**
     * То же, но с заданным именем файла: одно и то же фото с сайта всегда получает одно имя —
     * на телефоне и на Диске оно хранится один раз. Файл уже есть — повторно не скачивается и не отправляется.
     */
    /** Причина последней неудачи importUrlAs (для подсказки на экране). */
    @Volatile var importError: String = ""

    fun importUrlAs(ctx: Context, url: String, db: ProductDb, name: String): String? {
        importError = ""
        if (!url.startsWith("https://")) { importError = "адрес фото не https"; return null }
        val target = file(ctx, name)
        if (target.exists() && target.length() > 0) return name
        return try {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            c.setRequestProperty("Referer", "https://www.stroeh.de/")
            if (c.responseCode !in 200..299) { importError = "сайт ответил HTTP ${c.responseCode}"; c.disconnect(); return null }
            val mime = c.contentType.orEmpty()
            // тип не проверяем строго: сайт может отдавать .webp как octet-stream; главное — что файл раскодируется как картинка
            if (mime.startsWith("text/")) { importError = "вместо фото пришла страница"; c.disconnect(); return null }
            var total = 0L
            c.inputStream.use { i -> target.outputStream().use { o ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    total += n
                    if (total > 10_000_000L) throw RuntimeException("слишком большой файл")
                    o.write(buf, 0, n)
                }
            } }
            c.disconnect()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            if (bounds.outWidth <= 0) { importError = "файл не распознан как картинка"; target.delete(); return null }
            shrink(target)
            db.addUpload(name)
            name
        } catch (e: Exception) {
            importError = "ошибка загрузки: ${e.javaClass.simpleName}"
            target.delete()
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
        // режим «Съёмка для документов»: чёрно-белая версия для печати (цветной оригинал остаётся как есть и уходит на Диск)
        if (DocCam.enabled(ctx)) DocCam.makePrint(ctx, name)
    }

    /** Отправка ожидающих файлов на Google Диск в фоне; при ошибке повторится при следующем вызове. */
    private val tooBig = java.util.Collections.synchronizedSet(HashSet<String>())

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
                    var progress = false
                    for (name in list) {
                        // файл, на котором уже не хватило памяти, в этом запуске пропускаем: остальные файлы всё равно отправляются
                        if (name in tooBig) continue
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
                            progress = true
                        } catch (e: OutOfMemoryError) {
                            // большой многолистовой файл: раньше это закрывало приложение при каждом включении интернета
                            tooBig.add(name)
                            lastError = "Файл слишком большой для отправки на Диск (${f.length() / 1048576} МБ): $name"
                            version++
                        } catch (e: Exception) {
                            lastError = "Ошибка отправки на Диск: " + (e.message ?: e.javaClass.simpleName)
                            version++
                            failed = true
                            break
                        }
                    }
                    if (!progress && !failed) break
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
            // фото с сайта (PR-S…) — общий кэш на Диске: одно на товар, при закрытии карточки не удаляется
            if (n.startsWith("PR-S")) continue
            file(app, n).delete()
            DocCam.drop(app, n)
            db.removeUpload(n)
            if (n !in pending && DriveCfg.ready(app) && Session.isAdmin) {
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
            if (DriveApi.downloadTo(ctx, name, f)) f else null
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
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
    var liveName by remember { mutableStateOf<String?>(null) }
    fun startCamera() {
        val (name, uri) = Media.cameraTarget(ctx, kind)
        pendingCam = name
        if (DocCam.enabled(ctx)) liveName = name else camLauncher.launch(uri)
    }
    liveName?.let { n ->
        LiveCameraDialog(Media.file(ctx, n)) { ok ->
            liveName = null
            val name = pendingCam
            pendingCam = null
            if (name != null) {
                if (ok) scope.launch {
                    withContext(Dispatchers.IO) { Media.finishCamera(ctx, name, db) }
                    cb(name)
                } else Media.file(ctx, name).delete()
            }
        }
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

/** Камера без кнопки: возвращает функцию «снять». Снимок сохраняется как фото вида kind, имя файла приходит в onAdded. */
@Composable
fun rememberPhotoCapture(kind: String, onAdded: (String) -> Unit): () -> Unit {
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
    var liveName by remember { mutableStateOf<String?>(null) }
    fun startCamera() {
        val (name, uri) = Media.cameraTarget(ctx, kind)
        pendingCam = name
        if (DocCam.enabled(ctx)) liveName = name else camLauncher.launch(uri)
    }
    liveName?.let { n ->
        LiveCameraDialog(Media.file(ctx, n)) { ok ->
            liveName = null
            val name = pendingCam
            pendingCam = null
            if (name != null) {
                if (ok) scope.launch {
                    withContext(Dispatchers.IO) { Media.finishCamera(ctx, name, db) }
                    cb(name)
                } else Media.file(ctx, name).delete()
            }
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
    }
    return {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }
}
