package ru.storage.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Настройка просмотра накладных и фото: листать сверху вниз или слева направо. */
object ViewerPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("viewer", Context.MODE_PRIVATE)
    fun vertical(ctx: Context): Boolean = sp(ctx).getBoolean("vertical", true)
    fun setVertical(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("vertical", v).apply()
    }
}

/** Одна страница просмотра: лист PDF, картинка или файл, который не открыть внутри приложения. */
private data class ViewPage(val name: String, val index: Int, val kind: String) // kind: image / pdf / missing / other

private val renderLock = Mutex()
private val pageCache = LruCache<String, Bitmap>(5)

private fun pdfPageCount(f: File): Int {
    var pfd: ParcelFileDescriptor? = null
    var r: PdfRenderer? = null
    return try {
        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        r = PdfRenderer(pfd)
        r.pageCount
    } catch (e: Exception) {
        0
    } finally {
        try { r?.close() } catch (e: Exception) { }
        try { pfd?.close() } catch (e: Exception) { }
    }
}

private fun renderPdfPage(f: File, index: Int, width: Int): Bitmap? {
    var pfd: ParcelFileDescriptor? = null
    var r: PdfRenderer? = null
    return try {
        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        r = PdfRenderer(pfd)
        val page = r.openPage(index)
        try {
            val scale = width.toFloat() / page.width
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        } finally {
            page.close()
        }
    } catch (e: Exception) {
        null
    } finally {
        try { r?.close() } catch (e: Exception) { }
        try { pfd?.close() } catch (e: Exception) { }
    }
}

private suspend fun loadPageBitmap(ctx: Context, p: ViewPage): Bitmap? = withContext(Dispatchers.IO) {
    val key = "${p.name}#${p.index}"
    val cached = pageCache.get(key)
    if (cached != null) return@withContext cached
    val f = Media.file(ctx, p.name)
    val bmp = when (p.kind) {
        "image" -> Media.thumb(f, 1400)
        "pdf" -> renderLock.withLock { renderPdfPage(f, p.index, 1500) }
        else -> null
    }
    if (bmp != null) pageCache.put(key, bmp)
    bmp
}

/**
 * Просмотр накладных (PDF — по листам) и фото с пролистыванием.
 * Направление — из настроек, переключается и здесь кнопкой в шапке. Двойной тап или щипок — увеличить.
 */
@Composable
fun DocViewerScreen(names: List<String>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var vertical by remember { mutableStateOf(ViewerPrefs.vertical(ctx)) }
    var zoomed by remember { mutableStateOf(false) }

    val pages by produceState<List<ViewPage>?>(null, names) {
        val out = ArrayList<ViewPage>()
        for (n in names) {
            val f = Media.load(ctx, n)
            when {
                f == null -> out.add(ViewPage(n, 0, "missing"))
                Media.isImage(n) -> out.add(ViewPage(n, 0, "image"))
                n.lowercase().endsWith(".pdf") -> {
                    val cnt = withContext(Dispatchers.IO) { pdfPageCount(f) }
                    if (cnt <= 0) out.add(ViewPage(n, 0, "missing"))
                    else for (i in 0 until cnt) out.add(ViewPage(n, i, "pdf"))
                }
                else -> out.add(ViewPage(n, 0, "other"))
            }
        }
        value = out
    }

    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val list = pages
        if (list == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        } else if (list.isEmpty()) {
            Text("Нет файлов", Modifier.align(Alignment.Center), color = Color.White)
        } else {
            val state = rememberPagerState(pageCount = { list.size })
            LaunchedEffect(state.currentPage) { zoomed = false }
            val content: @Composable PagerScope.(Int) -> Unit = { i ->
                PageContent(list[i]) { zoomed = it }
            }
            if (vertical) {
                VerticalPager(state = state, modifier = Modifier.fillMaxSize(), userScrollEnabled = !zoomed, pageContent = content)
            } else {
                HorizontalPager(state = state, modifier = Modifier.fillMaxSize(), userScrollEnabled = !zoomed, pageContent = content)
            }
            val cur = list[state.currentPage.coerceIn(0, list.size - 1)]
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clickable { onClose() }.padding(end = 16.dp))
                Text(
                    "${state.currentPage + 1} / ${list.size} · ${Media.shownName(cur.name)}",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Text(
                    if (vertical) "↕" else "↔", color = Color.White, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clickable {
                        vertical = !vertical
                        ViewerPrefs.setVertical(ctx, vertical)
                    }.padding(start = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun PageContent(p: ViewPage, onZoom: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    when (p.kind) {
        "image", "pdf" -> {
            val bmp by produceState<Bitmap?>(null, p) { value = loadPageBitmap(ctx, p) }
            val b = bmp
            if (b == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                ZoomPage(b, onZoom)
            }
        }
        "other" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Media.shownName(p.name), color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { Media.open(ctx, p.name) }) { Text("Открыть в другой программе") }
            }
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Файл недоступен на этом телефоне:\n${Media.shownName(p.name)}\nПроверьте настройки Google Диска.",
                color = Color.White, modifier = Modifier.padding(24.dp)
            )
        }
    }
}

@Composable
private fun ZoomPage(bmp: Bitmap, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                    onZoom(scale > 1f)
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        // один палец при масштабе 1 не трогаем — это пролистывание страниц
                        if (ev.changes.size > 1 || scale > 1f) {
                            val ns = (scale * ev.calculateZoom()).coerceIn(1f, 5f)
                            scale = ns
                            offset = if (ns <= 1f) Offset.Zero else clamp(offset + ev.calculatePan(), ns)
                            onZoom(ns > 1f)
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
    ) {
        Image(
            bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y
            )
        )
    }
}
