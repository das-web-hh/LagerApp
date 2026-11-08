package ru.storage.app

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Настройка «Приоритетный артикул»: из системы (свой: ввод, скан, накладная, Excel) или с сайта stroeh.de.
 * Хранится на телефоне и в аккаунте (Firebase), как остальные настройки.
 */
object SiteCfg {
    /** true — главнее артикул с сайта; false — главнее артикул из системы. */
    var siteFirst by mutableStateOf(false)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("site_cfg", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        siteFirst = sp(ctx).getString("priority", "system") == "site"
    }

    fun set(ctx: Context, site: Boolean) {
        siteFirst = site
        sp(ctx).edit().putString("priority", if (site) "site" else "system").apply()
    }

    val code: String get() = if (siteFirst) "site" else "system"

    fun applyCloud(ctx: Context, v: String?) {
        if (v == "site" || v == "system") set(ctx, v == "site")
    }

    /** Из двух артикулов выбирает главный по настройке; если главного нет — второй. */
    fun pick(own: String, web: String): String {
        val o = own.trim()
        val w = web.trim()
        return if (siteFirst) w.ifEmpty { o } else o.ifEmpty { w }
    }
}

/** Одна подсказка в мини-окне: товар каталога, товар сайта или то и другое сразу (связаны по артикулу). */
class Suggest(
    val product: Product?,
    val members: List<Product>,
    val hit: DriveApi.StrohHit?,
    val info: DriveApi.StrohInfo?
) {
    val linked: Boolean get() = product != null && hit != null

    /** Одно имя на подсказку: у связанных — по настройке приоритета. */
    val name: String
        get() {
            val siteName = (info?.name?.takeIf { it.isNotBlank() } ?: hit?.name).orEmpty()
            return when {
                product == null -> siteName
                hit == null -> product.name
                SiteCfg.siteFirst && siteName.isNotBlank() -> siteName
                else -> product.name
            }
        }

    val article: String
        get() {
            val own = members.firstOrNull { it.article.isNotBlank() }?.article.orEmpty()
            val web = info?.article.orEmpty().ifBlank { members.firstOrNull { it.webArticle.isNotBlank() }?.webArticle.orEmpty() }
            return SiteCfg.pick(own, web)
        }

    val barcode: String get() = members.firstOrNull { it.barcode.isNotBlank() }?.barcode.orEmpty().trim()

    val key: String get() = if (product != null) "p" + product.id else "s" + hit?.url
}

/** Поиск по stroeh.de (через скрипт Google), кэш в памяти, связывание с каталогом по артикулу. */
object SiteSearch {
    private val searches = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, List<DriveApi.StrohHit>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<DriveApi.StrohHit>>?) = size > 40
        }
    )
    private val details = java.util.concurrent.ConcurrentHashMap<String, DriveApi.StrohInfo>()

    /** Артикул для сравнения: без пробелов, дефисов и регистра. */
    fun norm(a: String): String = a.uppercase().filter { it.isLetterOrDigit() }

    private fun nameKey(n: String): String = n.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Можно ли искать это на сайте: название (от 4 символов) или артикул (одно слово с цифрой, от 3 символов).
     * Штрих-коды (длинные цифры), даты и номера заказов EB… на сайт не уходят.
     */
    fun eligible(q: String): Boolean {
        val t = q.trim()
        if (t.length < 3) return false
        if (t.all { it.isDigit() }) return t.length in 4..7
        if (t.any { it.isDigit() } && t.all { it.isDigit() || it in ".,-/ " }) return false
        if (t.startsWith("EB", true) && t.drop(2).all { it.isDigit() } && t.length >= 6) return false
        return t.length >= 4 || (!t.contains(' ') && t.any { it.isDigit() })
    }

    suspend fun search(ctx: Context, q: String, limit: Int = 8): List<DriveApi.StrohHit> {
        val t = q.trim()
        val key = t.lowercase()
        searches[key]?.let { return it.take(limit) }
        val r = withContext(Dispatchers.IO) { DriveApi.strohSearch(ctx, t) }
        if (r.isNotEmpty()) searches[key] = r
        return r.take(limit)
    }

    private suspend fun detail(ctx: Context, h: DriveApi.StrohHit): DriveApi.StrohInfo? {
        details[h.url]?.let { return it }
        val d = try {
            withContext(Dispatchers.IO) { DriveApi.strohDetail(ctx, h.url) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        details[h.url] = d
        return d
    }

    private fun link(db: ProductDb, products: List<Product>, hits: List<DriveApi.StrohHit>, infos: Map<String, DriveApi.StrohInfo?>): List<Suggest> {
        val used = HashSet<String>()
        val out = ArrayList<Suggest>()
        for (p in products) {
            val mem = try { ProductGroups.membersOf(db, p) } catch (e: Exception) { listOf(p) }
            val arts = mem.flatMap { listOf(norm(it.article), norm(it.webArticle)) }.filter { it.isNotEmpty() }.toSet()
            val names = mem.map { nameKey(it.name) }.toSet()
            val m = hits.firstOrNull { h ->
                if (h.url in used) return@firstOrNull false
                val i = infos[h.url]
                val a = if (i == null) "" else norm(i.article)
                (a.isNotEmpty() && a in arts) || nameKey(i?.name?.ifBlank { h.name } ?: h.name) in names
            }
            if (m != null) {
                used.add(m.url)
                out.add(Suggest(p, mem, m, infos[m.url]))
            } else out.add(Suggest(p, mem, null, null))
        }
        for (h in hits) if (h.url !in used) out.add(Suggest(null, emptyList(), h, infos[h.url]))
        return out
    }

    /** Быстро: только то, что уже загружено (без запросов на сайт). */
    fun combineQuick(ctx: Context, products: List<Product>, hits: List<DriveApi.StrohHit>): List<Suggest> =
        link(ProductDb.get(ctx), products, hits, hits.associate { it.url to details[it.url] })

    /** Полностью: у первых сайтовых товаров читается артикул со страницы товара, одинаковые артикулы склеиваются в одну подсказку. */
    suspend fun combine(ctx: Context, products: List<Product>, hits: List<DriveApi.StrohHit>, resolve: Int = 6): List<Suggest> {
        val infos = HashMap<String, DriveApi.StrohInfo?>()
        coroutineScope {
            hits.take(resolve).map { h -> async { h.url to detail(ctx, h) } }.awaitAll().forEach { infos[it.first] = it.second }
        }
        return withContext(Dispatchers.IO) { link(ProductDb.get(ctx), products, hits, infos) }
    }

    /** Связанная подсказка запоминается в карточке: артикул с сайта записывается в webArticle. Вызывать не в главном потоке. */
    fun bind(ctx: Context, s: Suggest) {
        val p = s.product ?: return
        val art = s.info?.article.orEmpty().trim()
        if (art.isEmpty()) return
        try {
            val db = ProductDb.get(ctx)
            val cur = db.getProduct(p.id) ?: return
            if (cur.webArticle.isBlank()) Cloud.saveProduct(db, cur.copy(webArticle = art), log = false)
        } catch (e: Exception) {
        }
    }

    /** Название и артикул товара с сайта для подсказки (из кэша или со страницы). */
    suspend fun infoOf(ctx: Context, s: Suggest): DriveApi.StrohInfo? = s.info ?: s.hit?.let { detail(ctx, it) }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(14)

    /**
     * Фото товара с сайта → файл «PR-S<артикул>.jpg» на телефоне и в очереди на Google Диск (папка «Товары / Фото с сайта»).
     * Имя одинаковое для одного товара, поэтому фото не дублируется. Вызывать не в главном потоке. null — фото нет.
     */
    fun photoName(ctx: Context, info: DriveApi.StrohInfo): String? {
        if (info.image.isBlank()) return null
        val base = norm(info.article).ifEmpty { sha(info.image) }.take(40)
        val name = "PR-S$base.jpg"
        val db = ProductDb.get(ctx)
        val got = Media.importUrlAs(ctx, info.image, db, name) ?: return null
        Media.uploadPending(ctx, db)
        return got
    }

    /** Картинка для большой панели: у связанных и сайтовых — с сайта (и сохраняется на Диск), иначе первое фото карточки. */
    suspend fun bigPhoto(ctx: Context, s: Suggest): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (s.hit != null) {
                val i = infoOf(ctx, s)
                if (i != null) {
                    val n = photoName(ctx, i)
                    if (n != null) Media.thumb(Media.file(ctx, n), 1600)?.let { return@withContext it }
                }
            }
            val local = s.members.flatMap { it.photos }.firstOrNull()
            if (local != null) Media.load(ctx, local)?.let { return@withContext Media.thumb(it, 1600) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

class SiteSuggestState {
    var items by mutableStateOf(emptyList<Suggest>())
    var busy by mutableStateOf(false)
}

/**
 * Подсказки для поля поиска: товары каталога (products) и товары сайта — по названию и по артикулу.
 * Совпал артикул — это одна подсказка с одним именем (связаны). Каталог показывается сразу, сайт — после паузы.
 */
@Composable
fun rememberSiteSuggest(query: String, products: List<Product>, enabled: Boolean = true): SiteSuggestState {
    val ctx = LocalContext.current.applicationContext
    val st = remember { SiteSuggestState() }
    var hits by remember { mutableStateOf(emptyList<DriveApi.StrohHit>()) }
    LaunchedEffect(query, enabled) {
        val t = query.trim()
        hits = emptyList()
        if (!enabled || !SiteSearch.eligible(t) || !DriveCfg.ready(ctx)) { st.busy = false; return@LaunchedEffect }
        st.busy = true
        delay(600)
        hits = try {
            SiteSearch.search(ctx, t)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        st.busy = false
    }
    LaunchedEffect(products, hits, SiteCfg.siteFirst) {
        st.items = try {
            SiteSearch.combineQuick(ctx, products, hits)
        } catch (e: Exception) {
            products.map { Suggest(it, listOf(it), null, null) }
        }
        if (hits.isNotEmpty()) {
            st.items = try {
                SiteSearch.combine(ctx, products, hits)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                st.items
            }
        }
    }
    return st
}

/**
 * Обёртка строки подсказки: свайп слева направо (даже чуть-чуть) открывает фото товара большой панелью слева;
 * свайп справа налево (тоже чуть-чуть) или тап по свободным 20% справа — панель уходит обратно.
 */
@Composable
fun PhotoSwipeBox(s: Suggest?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val can = s != null && (s.hit != null || s.members.any { it.photos.isNotEmpty() })
    Box(
        modifier.pointerInput(can) {
            if (!can) return@pointerInput
            var acc = 0f
            var fired = false
            val trigger = 6.dp.toPx()
            detectHorizontalDragGestures(
                onDragStart = { acc = 0f; fired = false },
                onDragEnd = { acc = 0f },
                onDragCancel = { acc = 0f }
            ) { change, dx ->
                acc += dx
                if (!fired && acc > trigger) {
                    fired = true
                    change.consume()
                    open = true
                }
            }
        }
    ) { content() }
    if (open && s != null) SitePhotoPanel(s) { open = false }
}

/** Фото слева на 80% ширины экрана поверх всего окна; справа остаётся 20% свободного места. */
@Composable
fun SitePhotoPanel(s: Suggest, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(s.key) {
        bmp = SiteSearch.bigPhoto(ctx.applicationContext, s)
        loading = false
    }
    val close: () -> Unit = { scope.launch { shown = false; delay(200); onClose() } }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } }
        ) {
            AnimatedVisibility(
                visible = shown,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = slideInHorizontally(tween(160)) { -it } + fadeIn(tween(100)),
                exit = slideOutHorizontally(tween(160)) { -it } + fadeOut(tween(100))
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(0.8f).clipToBounds()
                        .background(Color(0xF2101010))
                        .pointerInput(Unit) { detectTapGestures { } }
                        .pointerInput(Unit) {
                            var acc = 0f
                            var fired = false
                            val trigger = 6.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragStart = { acc = 0f; fired = false },
                                onDragEnd = { acc = 0f },
                                onDragCancel = { acc = 0f }
                            ) { change, dx ->
                                acc += dx
                                if (!fired && acc < -trigger) {
                                    fired = true
                                    change.consume()
                                    close()
                                }
                            }
                        }
                ) {
                    val b = bmp
                    when {
                        b != null -> Image(
                            b.asImageBitmap(), contentDescription = s.name,
                            modifier = Modifier.fillMaxSize().padding(bottom = 64.dp), contentScale = ContentScale.Fit
                        )
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> Text("Фото не найдено", Modifier.align(Alignment.Center), color = Color.White)
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(s.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val art = s.article
                        if (art.isNotBlank()) Text("арт. $art", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Строка подсказки: одно имя, артикул, пометка «связан с сайтом» / «сайт stroeh.de»; свайп вправо — фото. */
@Composable
fun SuggestRow(s: Suggest, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PhotoSwipeBox(if (s.hit != null || s.members.any { it.photos.isNotEmpty() }) s else null, modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(s.name, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOf(
                s.article.takeIf { it.isNotBlank() }?.let { "арт. $it" } ?: "",
                s.barcode.takeIf { it.isNotBlank() }?.let { "ШК $it" } ?: "",
                when {
                    s.linked -> "🔗 связан с сайтом"
                    s.product == null -> "сайт stroeh.de"
                    else -> ""
                }
            ).filter { it.isNotEmpty() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Строка «Ищу на stroeh.de…» под полем поиска, пока идёт запрос. */
@Composable
fun SiteBusyLine(busy: Boolean) {
    if (busy) Text(
        "Ищу на stroeh.de…", Modifier.padding(start = 14.dp, top = 6.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
    )
}
