package ru.storage.app

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Фотофиксация испорченного товара (приём B-Ware): до 10 фото на один приём.
 * У каждого приёма есть короткий код (например 260715K7PQ2). Он печатается QR-кодом на документе
 * («LAGER-BW:КОД»), а в имя каждого фото входит тот же код: BW-КОД-….jpg.
 * Фото хранятся как все остальные: на телефоне и на Google Диске (Media + DriveApi), поэтому
 * сервер и правила Firebase менять не нужно. Отсканировав QR, видишь только фото этого приёма.
 */

const val BW_MAX_PHOTOS = 10
const val BW_QR_PREFIX = "LAGER-BW:"
private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** Новый код приёма: дата (ггммдд) + 5 случайных символов без похожих (0/O, 1/I). Без дефиса — он разделитель в имени файла. */
fun newBwCode(): String {
    val d = SimpleDateFormat("yyMMdd", Locale.ROOT).format(Date())
    val r = SecureRandom()
    val tail = (1..5).map { CODE_ALPHABET[r.nextInt(CODE_ALPHABET.length)] }.joinToString("")
    return d + tail
}

/** Код из текста QR; null, если это не QR документа B-Ware. */
fun bwCodeFromQr(raw: String): String? {
    val t = raw.trim()
    if (!t.startsWith(BW_QR_PREFIX)) return null
    val c = t.removePrefix(BW_QR_PREFIX).trim().uppercase()
    return if (Regex("[A-Z0-9]{6,24}").matches(c)) c else null
}

fun bwPhotoPrefix(code: String) = "BW-$code-"

/** Фото одного приёма: файлы на телефоне + поиск на Google Диске по коду. Только с этим кодом, не больше 10. */
suspend fun bwPhotosFor(ctx: Context, code: String): List<String> = withContext(Dispatchers.IO) {
    val app = ctx.applicationContext
    val pre = bwPhotoPrefix(code)
    val local = Media.dir(app).listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith(pre) && Media.isImage(it.name) && it.length() > 0 }
        .sortedBy { it.lastModified() }
        .map { it.name }
    val remote = if (DriveCfg.ready(app)) {
        try {
            DriveApi.searchNames(app, pre).filter { it.startsWith(pre) && Media.isImage(it) }
        } catch (e: Exception) {
            emptyList()
        }
    } else emptyList()
    (local + remote).distinct().take(BW_MAX_PHOTOS)
}

/**
 * Листы с фото для PDF на Диске (в печать они не попадают). Фото вставляются в страницу как data-URI,
 * по два в ряд; пустой список — пустая строка.
 */
suspend fun bwPhotosHtml(ctx: Context, names: List<String>): String = withContext(Dispatchers.IO) {
    if (names.isEmpty()) return@withContext ""
    val sb = StringBuilder()
    sb.append("<style>.phs{page-break-before:always}.ph{display:inline-block;width:48.5%;vertical-align:top;margin:0 1% 8px 0;page-break-inside:avoid}.ph img{width:100%;height:auto}</style>")
    sb.append("<div class='phs'><h2>Fotos / Фото (${names.size})</h2>")
    for (n in names) {
        try {
            val f = Media.load(ctx, n) ?: continue
            val bmp = Media.thumb(f, 700) ?: continue
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            val b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
            sb.append("<div class='ph'><img src='data:image/jpeg;base64,").append(b64).append("'></div>")
        } catch (e: Exception) {
        }
    }
    sb.append("</div>")
    sb.toString()
}

/** Кнопка в окне «Приём B-Ware»: открывает окно с фото. Счётчик «3/10». */
@Composable
fun BWarePhotoButton() {
    val h = BWareHolder
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            h.ensureCode()
            open = true
        },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Text("📷 Фото испорченного товара · ${h.photos.size}/$BW_MAX_PHOTOS", maxLines = 1)
    }
    if (open) BWarePhotoDialog { open = false }
}

@Composable
private fun BWarePhotoDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val h = BWareHolder
    val code = h.code // уже создан по нажатию кнопки
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Фото повреждений (${h.photos.size}/$BW_MAX_PHOTOS)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Сфотографируйте испорченный товар, до $BW_MAX_PHOTOS фото. " +
                        "Они попадут только в PDF на Google Диске (QR-код на документе откроет его), на бумаге фото не печатаются.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                if (h.photos.size < BW_MAX_PHOTOS) {
                    MediaButtons(kind = "BW-$code", allowFile = false) { name ->
                        if (h.photos.size >= BW_MAX_PHOTOS) {
                            // из галереи можно выбрать сразу много: лишние не сохраняем
                            Media.discard(ctx, db, listOf(name))
                            Toast.makeText(ctx, "Максимум $BW_MAX_PHOTOS фото", Toast.LENGTH_SHORT).show()
                        } else {
                            h.photos.add(name)
                            Media.uploadPending(ctx, db)
                        }
                    }
                } else {
                    Text("Достигнут предел: $BW_MAX_PHOTOS фото. Удалите ненужное (✕), чтобы добавить новое.",
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(10.dp))
                PhotoGrid(h.photos) { n ->
                    h.photos.remove(n)
                    Media.discard(ctx, db, listOf(n))
                }
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("Готово") } }
    )
}

/** Фото сеткой по три в ряд; тап — открыть, ✕ (если передан onRemove) — удалить. */
@Composable
fun PhotoGrid(names: List<String>, onRemove: ((String) -> Unit)? = null) {
    if (names.isEmpty()) {
        Text("Фото пока нет", style = MaterialTheme.typography.bodySmall)
        return
    }
    names.chunked(3).forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            row.forEach { n -> PhotoCell(n, onRemove, Modifier.weight(1f).aspectRatio(1f)) }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PhotoCell(name: String, onRemove: ((String) -> Unit)?, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bmp by produceState<Bitmap?>(null, name) {
        value = Media.load(ctx, name)?.let { withContext(Dispatchers.IO) { Media.thumb(it, 300) } }
    }
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { scope.launch { if (Media.load(ctx, name) != null) Media.open(ctx, name) } }
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (onRemove != null) {
            Text(
                "✕",
                Modifier.align(Alignment.TopEnd)
                    .background(Color(0x99000000))
                    .clickable { onRemove(name) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White
            )
        }
    }
}

/** «Фото по QR»: сканер (только QR) → фото именно этого приёма B-Ware. */
@Composable
fun BWarePhotoLookupScreen(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var scanning by remember { mutableStateOf(true) }
    var code by remember { mutableStateOf<String?>(null) }
    var bad by remember { mutableStateOf("") }

    if (scanning) {
        ScannerScreen(
            prefs = prefs,
            onResult = { raw ->
                // QR на новых документах — ссылка на PDF на Google Диске: открываем её (PDF скачивается на этот телефон)
                val t = raw.trim()
                if (t.startsWith("https://drive.google.com/") || t.startsWith("https://docs.google.com/")) {
                    try {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(t))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
                    }
                    onBack()
                    return@ScannerScreen
                }
                val c = bwCodeFromQr(raw)
                code = c
                bad = if (c == null) "Это не QR-код документа B-Ware." else ""
                scanning = false
            },
            onClose = onBack,
            onlyQr = true
        )
        return
    }

    BackHandler(onBack = onBack)
    val current = code
    var names by remember(current) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(current) {
        names = null
        if (current != null) names = bwPhotosFor(ctx, current)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Фото B-Ware", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        if (current == null) {
            Text(bad.ifBlank { "QR-код не распознан." }, color = MaterialTheme.colorScheme.error)
        } else {
            Text("Приём: $current", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val list = names
            when {
                list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Загрузка фото…")
                }
                list.isEmpty() -> Text(
                    "Фото по этому QR-коду не найдены. Если их снимали на другом телефоне, " +
                        "подождите, пока они загрузятся на Google Диск, и отсканируйте снова.",
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> {
                    Text("Фото: ${list.size}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    PhotoGrid(list)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                code = null
                bad = ""
                scanning = true
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Сканировать другой QR") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
    }
}
