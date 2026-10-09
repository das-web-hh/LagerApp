package ru.storage.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val MIN_SEARCH_CHARS = 3

/** Иконка сканера штрих-кодов: уголки рамки и полосы кода. */
@Composable
fun BarcodeScannerIcon(
    size: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.09f
        val m = stroke / 2f
        val c = w * 0.3f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = stroke, cap = StrokeCap.Round)
        // уголки
        line(m, c, m, m); line(m, m, c, m)
        line(w - c, m, w - m, m); line(w - m, m, w - m, c)
        line(m, h - c, m, h - m); line(m, h - m, c, h - m)
        line(w - c, h - m, w - m, h - m); line(w - m, h - m, w - m, h - c)
        // полосы штрих-кода
        val bars = listOf(0.26f to 0.05f, 0.35f to 0.03f, 0.42f to 0.07f, 0.54f to 0.03f, 0.61f to 0.05f, 0.70f to 0.04f)
        bars.forEach { (x, bw) ->
            drawRect(color, Offset(w * x, h * 0.28f), Size(w * bw, h * 0.44f))
        }
    }
}

/** Окно «Поиск»: строка поиска сверху, результаты из каталога — новые сверху, старые снизу. */
@Composable
fun SearchScreen(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val sync = remember { SyncState() }
    var query by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<Product>()) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val enough = query.trim().length >= MIN_SEARCH_CHARS

    LaunchedEffect(Unit) { Cloud.runProducts(db, sync, false) }
    LaunchedEffect(query, sync.version) {
        results = if (query.trim().length < MIN_SEARCH_CHARS) emptyList()
        else withContext(Dispatchers.IO) { db.search(query, 300, newestFirst = true) }
    }

    if (scanning) {
        ScannerScreen(prefs = prefs, onResult = { query = it; scanning = false }, onClose = { scanning = false })
        return
    }
    val id = openId
    if (id != null) {
        ProductCardScreen(id, prefs) {
            openId = null
            sync.version++
        }
        return
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SearchBar(
            query = query,
            onChange = { query = it },
            onScan = { scanning = true },
            placeholder = "Поиск товара"
        )
        Spacer(Modifier.height(8.dp))
        when {
            !enough -> Text(
                "Введите минимум $MIN_SEARCH_CHARS символа: название, артикул или штрих-код",
                style = MaterialTheme.typography.bodySmall
            )
            results.isEmpty() -> Text(
                if (sync.busy) "Загрузка каталога…" else "Ничего не найдено",
                style = MaterialTheme.typography.bodySmall
            )
            else -> Text("Найдено: ${results.size}", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { p ->
                Column(Modifier.fillMaxWidth().clickable { openId = p.id }.padding(vertical = 10.dp)) {
                    Text(p.name, fontWeight = FontWeight.Medium)
                    val sub = listOf(
                        if (p.article.isNotBlank()) "арт. ${p.article}" else "",
                        if (p.barcode.isNotBlank()) "ШК ${p.barcode}" else ""
                    ).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
