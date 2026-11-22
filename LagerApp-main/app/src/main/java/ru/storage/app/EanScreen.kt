package ru.storage.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor

/** Недавно выбранные товары окна «EAN» (хранятся на телефоне): пока в поиске пусто, они показываются под ним. */
private object EanRecent {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("ean_recent", Context.MODE_PRIVATE)
    private const val MAX = 15

    fun ids(ctx: Context): List<String> =
        sp(ctx).getString("ids", "").orEmpty().split(',').filter { it.isNotBlank() }

    fun push(ctx: Context, id: String) {
        val list = (listOf(id) + ids(ctx).filter { it != id }).take(MAX)
        sp(ctx).edit().putString("ids", list.joinToString(",")).apply()
    }
}

private fun eanActivity(ctx: Context): Activity? {
    var c: Context? = ctx
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Штрих-код EAN-13 / EAN-8 крупно: чёрные полосы на белом, с «тихими зонами» по краям, целые пиксели на модуль (чтобы сканер читал с экрана). */
@Composable
fun EanBarcodeView(code: String, modifier: Modifier = Modifier) {
    val pat = remember(code) { Ean.pattern(code) }
    Canvas(modifier) {
        drawRect(Color.White)
        if (pat == null) return@Canvas
        val mods = pat.first
        val quiet = 12
        val total = mods.length + quiet * 2
        val mw = floor(size.width / total).coerceAtLeast(1f)
        val left = (size.width - mw * total) / 2f + quiet * mw
        var i = 0
        while (i < mods.length) {
            if (mods[i] == '1') {
                var j = i
                while (j < mods.length && mods[j] == '1') j++
                drawRect(Color.Black, Offset(left + i * mw, 0f), Size((j - i) * mw, size.height))
                i = j
            } else i++
        }
    }
}

/** Верхняя часть окна: штрих-код выбранного товара (или подсказка, или причина, почему код не нарисован). */
@Composable
private fun EanTop(p: Product?, code: String, height: Dp) {
    Surface(color = Color.White, modifier = Modifier.fillMaxWidth().height(height)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (p == null) {
                Text(
                    "Выберите товар ниже — здесь появится его штрих-код",
                    color = Color(0xFF666666), textAlign = TextAlign.Center
                )
                return@Column
            }
            val pat = Ean.pattern(code)
            when {
                code.isBlank() -> Text("У этого товара нет штрих-кода в базе", color = Color(0xFFB00020), fontWeight = FontWeight.Medium)
                pat == null -> {
                    Text(Ean.spaced(code), color = Color.Black, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(
                        "Это не EAN-13/EAN-8 или контрольная цифра не сходится — штрих-код нарисовать нельзя, проверьте код в карточке",
                        color = Color(0xFFB00020), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                    )
                }
                else -> {
                    EanBarcodeView(code, Modifier.fillMaxWidth().weight(1f))
                    Text(
                        Ean.spaced(pat.third), color = Color.Black, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp
                    )
                }
            }
            Text(
                p.name, color = Color(0xFF444444), style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * «Прочее → EAN»: сверху (около 20% экрана) штрих-код выбранного товара — его читает другой сканер прямо с экрана телефона;
 * ниже поиск по названию, артикулу и штрих-коду со сканером (камера, а плохой код читает ИИ) и список товаров.
 * Штрих-код берётся из базы (каталога).
 */
@Composable
fun EanScreen(prefs: Prefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ProductDb.get(ctx) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Product>>(emptyList()) }
    var recent by remember { mutableStateOf<List<Product>>(emptyList()) }
    var selected by remember { mutableStateOf<Product?>(null) }
    var code by remember { mutableStateOf("") }

    // экран ярче и не гаснет, пока на нём штрих-код (иначе сканер с экрана читает хуже)
    DisposableEffect(Unit) {
        val act = eanActivity(ctx)
        val w = act?.window
        if (w != null) {
            val lp = w.attributes
            lp.screenBrightness = 1f
            w.attributes = lp
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (w != null) {
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    LaunchedEffect(selectedId) {
        val id = selectedId
        if (id == null) {
            selected = null; code = ""
        } else withContext(Dispatchers.IO) {
            val p = db.getProduct(id)
            // у одного товара под разными названиями штрих-код может быть записан в другой карточке группы
            val c = p?.let { x ->
                x.barcode.ifBlank { ProductGroups.membersOf(db, x).firstOrNull { it.barcode.isNotBlank() }?.barcode.orEmpty() }
            }.orEmpty().trim()
            selected = p
            code = c
        }
    }

    LaunchedEffect(query) {
        results = if (query.trim().length < MIN_SEARCH_CHARS) emptyList()
        else withContext(Dispatchers.IO) { db.search(query, 200) }
    }
    LaunchedEffect(selectedId) {
        recent = withContext(Dispatchers.IO) { EanRecent.ids(ctx).mapNotNull { db.getProduct(it) } }
    }

    fun select(p: Product) {
        selectedId = p.id
        message = ""
        focus.clearFocus()
        EanRecent.push(ctx, p.id)
    }

    if (scanning) {
        ScannerScreen(
            prefs = prefs,
            onResult = { scanned ->
                scanning = false
                scope.launch {
                    val p = withContext(Dispatchers.IO) { db.findByBarcode(scanned) }
                    if (p != null) {
                        query = ""
                        select(p)
                    } else {
                        query = scanned
                        message = "В базе нет товара с кодом $scanned"
                    }
                }
            },
            onClose = { scanning = false }
        )
        return
    }

    BackHandler(onBack = onBack)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = maxHeight * 0.2f
        Column(Modifier.fillMaxSize()) {
            EanTop(selected, code, top)
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(10.dp))
                SearchBar(
                    query = query,
                    onChange = { query = it; message = "" },
                    onScan = { scanning = true },
                    placeholder = "Название, артикул или штрих-код"
                )
                if (message.isNotBlank()) {
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(6.dp))
                val q = query.trim()
                val list = if (q.length >= MIN_SEARCH_CHARS) results else recent
                when {
                    q.isNotEmpty() && q.length < MIN_SEARCH_CHARS ->
                        Text("Введите хотя бы $MIN_SEARCH_CHARS символа", style = MaterialTheme.typography.bodySmall)
                    list.isEmpty() && q.isEmpty() ->
                        Text("Введите название или артикул, либо нажмите на значок сканера справа.", style = MaterialTheme.typography.bodySmall)
                    list.isEmpty() ->
                        Text("Ничего не найдено", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        if (q.isEmpty()) Text("Недавно выбранные", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 2.dp))
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(list, key = { it.id }) { p ->
                                val sel = p.id == selectedId
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                                        .clickable { select(p) }
                                        .padding(horizontal = 6.dp, vertical = 9.dp)
                                ) {
                                    Text(p.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        BarcodeStatusIcon(p.barcode.isNotBlank(), size = 16.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            listOf(
                                                p.shownArticle.takeIf { it.isNotBlank() }?.let { "арт. $it" } ?: "",
                                                p.barcode.takeIf { it.isNotBlank() } ?: "нет штрих-кода"
                                            ).filter { it.isNotEmpty() }.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
