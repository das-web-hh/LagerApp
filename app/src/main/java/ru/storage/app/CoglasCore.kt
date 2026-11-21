package ru.storage.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/*
 * Coglas (WMS stroeh.coglas.com) внутри Warehouse: бывший WmsHelper 0.6.0.
 * Сайт открывается в одном общем WebView (сессия входа общая), поверх него — родные экраны приложения:
 * Заказы, Infoscan и инструменты. Сайт переводится на русский (словарь + ИИ с памятью переводов).
 */

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

/** Ошибка запроса к Coglas. needSite = true: нет сессии, нужно войти на сайте. */
class CgError(msg: String, val needSite: Boolean = false) : Exception(msg)

/** Сюда JS-страница присылает журнал запросов, ответы API и тексты на перевод. */
class CgBridge {
    private val items = ArrayList<String>()
    private val main = Handler(Looper.getMainLooper())
    var onResult: ((Int, Int, String) -> Unit)? = null
    var onTranslate: ((String) -> Unit)? = null
    @Volatile var translateOn = true

    @JavascriptInterface
    fun log(line: String) {
        synchronized(items) {
            items.add(line)
            if (items.size > 200) items.removeAt(0)
        }
    }

    @JavascriptInterface
    fun result(id: Int, status: Int, body: String) {
        main.post { onResult?.invoke(id, status, body) }
    }

    @JavascriptInterface
    fun translate(json: String) {
        onTranslate?.invoke(json)
    }

    @JavascriptInterface
    fun trOn(): Boolean = translateOn

    fun dump(): String = synchronized(items) { items.joinToString("\n") }
    fun size(): Int = synchronized(items) { items.size }
    fun clear() = synchronized(items) { items.clear() }
}

object CoglasWeb {
    const val START_URL = "https://stroeh.coglas.com/"
    private const val HOST = "coglas.com"

    val bridge = CgBridge()
    private var web: WebView? = null
    private var appCtx: Context? = null
    private var script = ""
    private var docStart = false
    private val main = Handler(Looper.getMainLooper())

    var progress by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var loadError by mutableStateOf("")
    var trOn by mutableStateOf(true)
    var trNote by mutableStateOf("")
    var lastRaw = ""

    // камера для сканера на сайте: сначала разрешение Android, потом разрешение странице
    var camLauncher: (() -> Unit)? = null
    private var pendingPermission: PermissionRequest? = null

    fun camResult(granted: Boolean) {
        val req = pendingPermission
        pendingPermission = null
        if (req != null) {
            if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else req.deny()
        }
    }

    private fun asset(ctx: Context, name: String): String =
        ctx.assets.open("coglas/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun hostOk(url: String?): Boolean =
        try { android.net.Uri.parse(url).host?.endsWith(HOST) == true } catch (e: Exception) { false }

    @SuppressLint("SetJavaScriptEnabled")
    fun view(ctx: Context): WebView {
        appCtx = ctx.applicationContext
        web?.let { w ->
            (w.context as? MutableContextWrapper)?.baseContext = ctx
            (w.parent as? ViewGroup)?.removeView(w)
            return w
        }
        val app = ctx.applicationContext
        trOn = CoglasTr.enabled(app)
        bridge.translateOn = trOn

        val dict = CoglasTr.dictJson(app).replace("\\", "\\\\").replace("'", "\\'")
        script = asset(app, "hook.js") + "\n" + asset(app, "app.js").replace("__DICT__", dict)

        val w = WebView(MutableContextWrapper(ctx))
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.textZoom = 100
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.setBackgroundColor(Color.WHITE)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }

        bridge.onResult = { id, status, body -> pending.remove(id)?.invoke(status, body) }
        bridge.onTranslate = { json -> translate(json) }
        w.addJavascriptInterface(bridge, "WmsBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, script, setOf("https://stroeh.coglas.com"))
            docStart = true
        }

        w.setDownloadListener { url, _, _, _, _ ->
            try {
                app.startActivity(
                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
            }
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host ?: return false
                if (host.endsWith(HOST)) return false
                try {
                    app.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadError = ""
                // запасной вариант, если DOCUMENT_START_SCRIPT не поддерживается
                if (!docStart && hostOk(url)) view.evaluateJavascript(script, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (hostOk(url)) view.evaluateJavascript(script, null)
                canGoBack = view.canGoBack()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                canGoBack = view.canGoBack()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) loadError = "Нет соединения с сайтом Coglas. Проверьте интернет."
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress = newProgress
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                main.post {
                    val okHost = request.origin.host?.endsWith(HOST) == true
                    if (!okHost || PermissionRequest.RESOURCE_VIDEO_CAPTURE !in request.resources) {
                        request.deny()
                        return@post
                    }
                    if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        val launch = camLauncher
                        if (launch == null) {
                            request.deny()
                        } else {
                            pendingPermission = request
                            launch()
                        }
                    }
                }
            }
        }

        w.loadUrl(START_URL)
        web = w
        return w
    }

    /** Экран закрыт: сайт остаётся жить (сессия и страница сохраняются), но отцепляется от экрана. */
    fun detach() {
        val w = web ?: return
        (w.parent as? ViewGroup)?.removeView(w)
        appCtx?.let { (w.context as? MutableContextWrapper)?.baseContext = it }
        CookieManager.getInstance().flush()
    }

    fun goBack(): Boolean {
        val w = web ?: return false
        return if (w.canGoBack()) { w.goBack(); true } else false
    }

    fun reload() { web?.reload() }
    fun home() { web?.loadUrl(START_URL) }

    fun setTranslate(ctx: Context, on: Boolean) {
        CoglasTr.setEnabled(ctx, on)
        trOn = on
        bridge.translateOn = on
        // выключили — страница перезагружается и снова показывается по-немецки; включили — переводится
        web?.reload()
    }

    fun retryTranslate() {
        trNote = ""
        web?.evaluateJavascript("window.__cgRetry && window.__cgRetry()", null)
    }

    // ---------------- выполнить JS на странице ----------------

    fun runJs(js: String, onResult: (String) -> Unit) {
        val w = web
        if (w == null) { onResult("Сайт ещё не открыт."); return }
        w.evaluateJavascript(js) { raw ->
            val text = try {
                (JSONTokener(raw ?: "null").nextValue() as? String) ?: (raw ?: "")
            } catch (e: Exception) {
                raw ?: ""
            }
            onResult(text)
        }
    }

    fun probe(ctx: Context, cb: (String) -> Unit) = runJs(asset(ctx.applicationContext, "probe.js"), cb)
    fun pageText(cb: (String) -> Unit) = runJs("document.body.innerText.slice(0, 20000)", cb)
    fun click(ctx: Context, text: String, cb: (String) -> Unit) =
        runJs(asset(ctx.applicationContext, "click.js").replace("__T__", JSONObject.quote(text)), cb)

    // ---------------- запросы к API сайта (через страницу, с её сессией) ----------------

    private val pending = HashMap<Int, (Int, String) -> Unit>()
    private var nextId = 1

    suspend fun api(method: String, path: String, body: String?): Pair<Int, String> {
        val w = web ?: return -1 to "hook"
        return withContext(Dispatchers.Main) {
            val id = nextId++
            val r = withTimeoutOrNull(15000L) {
                suspendCancellableCoroutine<Pair<Int, String>> { cont ->
                    pending[id] = { st, txt -> if (cont.isActive) cont.resume(st to txt) }
                    cont.invokeOnCancellation { pending.remove(id) }
                    val js = "(window.__wmsCall ? window.__wmsCall(" + id + "," + JSONObject.quote(method) + "," +
                        JSONObject.quote(path) + "," + (if (body == null) "null" else JSONObject.quote(body)) + ")" +
                        " : (window.WmsBridge && window.WmsBridge.result(" + id + ",-1,'hook')))"
                    w.evaluateJavascript(js, null)
                }
            }
            pending.remove(id)
            r ?: (-2 to "")
        }
    }

    // ---------------- перевод ----------------

    private fun translate(json: String) {
        val app = appCtx ?: return
        thread {
            try {
                val arr = JSONArray(json)
                val lines = (0 until arr.length()).map { arr.getString(it) }
                val res = CoglasTr.translate(app, lines)
                main.post { trNote = "" }
                if (res.isNotEmpty()) {
                    val js = "window.__cgApply && window.__cgApply(" + JSONObject.quote(JSONObject(res).toString()) + ")"
                    main.post { web?.evaluateJavascript(js, null) }
                }
            } catch (e: Exception) {
                val m = e.message ?: "Ошибка перевода"
                main.post { trNote = m }
            }
        }
    }
}

/** Перевод Coglas на русский: готовый словарь немецких слов + ИИ для остального; всё, что ИИ перевёл, запоминается на телефоне. */
object CoglasTr {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("coglas_tr", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("on", true)
    fun setEnabled(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean("on", on).apply() }
    fun cacheSize(ctx: Context): Int = cache(ctx).length()
    fun clearCache(ctx: Context) { sp(ctx).edit().remove("cache").apply() }

    private fun cache(ctx: Context): JSONObject =
        try { JSONObject(sp(ctx).getString("cache", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun dictJson(ctx: Context): String {
        val o = JSONObject()
        for ((k, v) in DICT) o.put(k, v)
        val c = cache(ctx)
        for (k in c.keys()) o.put(k, c.optString(k, ""))
        return o.toString()
    }

    /** Блокирующий вызов (не в главном потоке): возвращает {немецкий текст -> русский}. */
    fun translate(ctx: Context, lines: List<String>): Map<String, String> {
        val c = cache(ctx)
        val out = LinkedHashMap<String, String>()
        val need = ArrayList<String>()
        for (l in lines.distinct()) {
            val k = l.lowercase(Locale.ROOT)
            val d = DICT[k] ?: c.optString(k, "")
            if (d.isNotEmpty()) out[l] = d else need.add(l)
        }
        if (need.isEmpty()) return out
        if (!AiCfg.ready(ctx)) throw RuntimeException("ИИ не настроен: перевод только по словарю")
        for (chunk in need.chunked(40)) {
            val ru = askAi(ctx, chunk)
            for (i in chunk.indices) {
                val t = ru.getOrNull(i)?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    out[chunk[i]] = t
                    c.put(chunk[i].lowercase(Locale.ROOT), t)
                }
            }
        }
        // память переводов ограничена, чтобы не раздувалась
        if (c.length() > 3000) {
            val keys = ArrayList<String>()
            for (k in c.keys()) keys.add(k)
            for (k in keys.take(c.length() - 3000)) c.remove(k)
        }
        sp(ctx).edit().putString("cache", c.toString()).apply()
        return out
    }

    private fun askAi(ctx: Context, lines: List<String>): List<String> {
        val params = JSONObject().put("lines", JSONArray(lines))
        val answer = Ai.task(ctx, "translate_ru", params, null, true) { prompt(lines) }
        val s = answer.trim()
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) throw RuntimeException("ИИ вернул не JSON")
        val arr = JSONObject(s.substring(a, b + 1)).getJSONArray("items")
        if (arr.length() != lines.size) throw RuntimeException("ИИ вернул ${arr.length()} строк вместо ${lines.size}")
        return (0 until arr.length()).map { arr.optString(it, "") }
    }

    private fun prompt(lines: List<String>): String {
        val numbered = lines.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")
        return "You translate UI texts of a German warehouse-management web app (Coglas WMS) into Russian. " +
            "The lines are buttons, menu items, column headers, field labels, statuses and messages.\n" +
            "Rules: short, natural wording as in a Russian business app; keep brand names, product names, article numbers, " +
            "codes and abbreviations such as EAN, MHD, LE, GTIN unchanged; keep the capitalization style of the original; " +
            "no explanations.\n" +
            "Answer with JSON only (no markdown): {\"items\":[\"...\"]} with exactly ${lines.size} strings in the same order.\n" +
            "Lines:\n" + numbered
    }

    /** Ключи — строчными буквами. */
    private val DICT: Map<String, String> = mapOf(
        "lager" to "Склад", "bestellungen" to "Заказы", "bestellung" to "Заказ", "auftrag" to "Заказ", "aufträge" to "Заказы",
        "wareneingang" to "Приход товара", "warenausgang" to "Отгрузка", "umlagerung" to "Перемещение",
        "inventur" to "Инвентаризация", "kommissionierung" to "Комплектация", "versand" to "Отправка",
        "artikel" to "Артикул", "artikelnummer" to "Номер артикула", "artikelnr." to "Номер артикула", "bezeichnung" to "Наименование",
        "beschreibung" to "Описание", "menge" to "Количество", "anzahl" to "Количество", "einheit" to "Единица",
        "lagerplatz" to "Место хранения", "lagerplätze" to "Места хранения", "lagerbestand" to "Остаток на складе", "bestand" to "Остаток",
        "palette" to "Паллета", "paletten" to "Паллеты", "karton" to "Коробка", "lieferant" to "Поставщик", "lieferanten" to "Поставщики",
        "lieferschein" to "Накладная", "lieferung" to "Поставка", "kunde" to "Клиент", "kunden" to "Клиенты",
        "position" to "Позиция", "positionen" to "Позиции", "charge" to "Партия (Charge)", "mhd" to "Срок годности (MHD)",
        "mindesthaltbarkeitsdatum" to "Срок годности", "status" to "Статус", "datum" to "Дата", "uhrzeit" to "Время",
        "nummer" to "Номер", "name" to "Название", "typ" to "Тип", "art" to "Вид", "kategorie" to "Категория",
        "bemerkung" to "Примечание", "bemerkungen" to "Примечания", "notiz" to "Заметка",
        "suchen" to "Поиск", "suche" to "Поиск", "such..." to "Поиск...", "filter" to "Фильтр", "filtern" to "Фильтр", "alle" to "Все",
        "speichern" to "Сохранить", "abbrechen" to "Отмена", "zurück" to "Назад", "weiter" to "Далее", "fertig" to "Готово",
        "bestätigen" to "Подтвердить", "übernehmen" to "Применить", "schließen" to "Закрыть", "löschen" to "Удалить",
        "bearbeiten" to "Изменить", "hinzufügen" to "Добавить", "neu" to "Новый", "öffnen" to "Открыть", "drucken" to "Печать",
        "aktualisieren" to "Обновить", "senden" to "Отправить", "absenden" to "Отправить", "zurücksetzen" to "Сбросить",
        "ja" to "Да", "nein" to "Нет", "ok" to "ОК", "scannen" to "Сканировать", "scan" to "Скан", "barcode" to "Штрихкод",
        "anmelden" to "Войти", "abmelden" to "Выйти", "login" to "Вход", "logout" to "Выход",
        "benutzername" to "Имя пользователя", "benutzer" to "Пользователь", "passwort" to "Пароль", "kennwort" to "Пароль",
        "einstellungen" to "Настройки", "übersicht" to "Обзор", "startseite" to "Главная", "hilfe" to "Помощь", "profil" to "Профиль",
        "menü" to "Меню", "start" to "Старт", "ende" to "Конец", "seite" to "Страница", "von" to "из", "bis" to "до",
        "heute" to "Сегодня", "gestern" to "Вчера", "morgen" to "Завтра", "woche" to "Неделя", "monat" to "Месяц",
        "fehler" to "Ошибка", "warnung" to "Предупреждение", "hinweis" to "Примечание", "erfolgreich" to "Успешно",
        "laden..." to "Загрузка...", "laden" to "Загрузка", "bitte warten" to "Пожалуйста, подождите",
        "keine daten" to "Нет данных", "keine ergebnisse" to "Нет результатов", "keine einträge" to "Нет записей",
        "in bearbeitung" to "В работе", "erstellt" to "Создан", "erledigt" to "Выполнено", "abgeschlossen" to "Завершено",
        "offen" to "Открыт", "geschlossen" to "Закрыт", "aktiv" to "Активен", "inaktiv" to "Неактивен", "gesperrt" to "Заблокирован",
        "geplant" to "Запланировано", "geplantes wareneingangsdatum" to "Плановая дата прихода",
        "infoscan" to "Инфоскан", "lagerort" to "Место", "gewicht" to "Вес", "volumen" to "Объём", "preis" to "Цена",
        "mehr" to "Ещё", "weniger" to "Меньше", "details" to "Детали", "detail" to "Детали", "verlauf" to "История", "historie" to "История"
    )
}

// ======================= данные Coglas (API сайта) =======================

data class CgOrder(val id: Long, val number: String, val supplier: String, val planned: String, val status: String, val positions: Int)
data class CgOrdersPage(val items: List<CgOrder>, val total: Int)
data class CgPos(val number: Int, val title: String, val details: String, val done: Boolean)
data class CgOrderDetail(
    val id: Long, val number: String, val header: List<Pair<String, String>>, val total: Int, val shown: Int,
    val done: Int, val proc: Int, val created: Int, val positions: List<CgPos>
)
data class CgCard(val title: String, val lines: List<String>)
data class CgScan(val entities: List<CgCard>, val contents: List<CgCard>, val contentTotal: Int)

object CoglasData {

    private fun apiError(status: Int, text: String): CgError = when (status) {
        -2 -> CgError("Нет ответа за 15 секунд. Проверьте интернет.")
        -1 -> CgError("Сайт Coglas ещё не готов. Откройте вкладку «Сайт», войдите и подождите несколько секунд.", true)
        0 -> CgError("Ошибка сети.")
        401, 403 -> CgError("Нет доступа ($status). Откройте вкладку «Сайт» и войдите в Coglas.", true)
        else -> CgError("Ошибка $status: " + text.take(300))
    }

    private suspend fun call(path: String, body: String): String {
        val (st, txt) = CoglasWeb.api(
            "PUT", "/server/api/Process/executeAction?actionKey=$path", body
        )
        if (st != 200) throw apiError(st, txt)
        CoglasWeb.lastRaw = txt
        return txt
    }

    private inline fun <T> parsing(block: () -> T): T =
        try { block() } catch (e: CgError) { throw e } catch (e: Exception) {
            throw CgError("Не удалось разобрать ответ: " + (e.message ?: ""))
        }

    private fun fmtDate(s: String, withTime: Boolean = false): String {
        if (s.isEmpty()) return ""
        return try {
            val clean = s.replace(Regex("\\.\\d+Z$"), "Z")
            val inp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val d = inp.parse(clean)!!
            SimpleDateFormat(if (withTime) "dd.MM.yyyy HH:mm" else "dd.MM.yyyy", Locale.GERMANY)
                .apply { timeZone = TimeZone.getTimeZone("Europe/Berlin") }.format(d)
        } catch (e: Exception) {
            s
        }
    }

    private fun num(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    private fun statusKey(calc: JSONObject?): String {
        val k = when (val raw = calc?.opt("Status")) {
            is JSONObject -> raw.str("Key")
            is String -> raw
            else -> ""
        }
        return k.substringAfterLast('.')
    }

    fun statusRu(k: String): String = when (k) {
        "Process" -> "в работе"
        "Created" -> "создан"
        "Done" -> "готово"
        "AuditRequired" -> "нужна проверка"
        "ReportedToErp" -> "передан в ERP"
        "Deactivated" -> "деактивирован"
        else -> k
    }

    private fun unitName(qu: JSONObject?): String {
        if (qu == null) return ""
        for (k in listOf("Shortcut", "ShortName", "Symbol", "Abbreviation", "Description", "Name")) {
            val v = qu.str(k)
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    // ---------------- Заказы ----------------

    suspend fun orders(): CgOrdersPage {
        val body = "{\"SearchRequest\":{\"SortConfigurationKey\":\"GoodsInOrder.SortConfigurations.Status\"," +
            "\"PropertyFilters\":[{\"Operator\":\"In\",\"Values\":[\"GoodsInOrder.Status.GoodsInOrder.Process\"," +
            "\"GoodsInOrder.Status.GoodsInOrder.Created\"],\"Key\":\"GoodsInOrder.EnumFilters.GoodsInOrder.Calculated.Status\"," +
            "\"PropertyPath\":\"Calculated.Status.Key\",\"OrPropertyPaths\":null,\"NoDbFilter\":false}]," +
            "\"InvertSorting\":false,\"CancelSearchRequestIfAbortedByClient\":true,\"Skip\":0,\"Top\":30}," +
            "\"IncludeDeactivated\":true}"
        val txt = call("GoodsInOrder.Forms.GoodsInOrderList.LayoutItems.GoodsInOrderList.Actions.Search", body)
        return parsing {
            val pr = JSONObject(txt).optJSONObject("PageResult")
            val items = pr?.optJSONArray("Items")
            val total = pr?.optInt("Total") ?: 0
            val list = ArrayList<CgOrder>()
            if (items != null) {
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val calc = o.optJSONObject("Calculated")
                    val supplier = o.optJSONObject("Supplier")?.str("Name1") ?: ""
                    val pc = if (o.has("PositionCount")) o.optInt("PositionCount", -1) else (calc?.optInt("PositionCount", -1) ?: -1)
                    list.add(
                        CgOrder(
                            o.optLong("Id"), o.str("Number"), supplier, fmtDate(o.str("PlannedGoodsInDate")),
                            statusRu(statusKey(calc)), pc
                        )
                    )
                }
            }
            CgOrdersPage(list, total)
        }
    }

    suspend fun order(id: Long, number: String): CgOrderDetail {
        val header = ArrayList<Pair<String, String>>()
        try {
            val txt = call(
                "GoodsInOrder.Forms.GoodsInOrder.LayoutItems.GoodsInOrderDetails.Actions.Read",
                JSONObject().put("Id", id).toString()
            )
            parsing {
                val r = JSONObject(txt).optJSONObject("Result")
                if (r != null) {
                    fun add(label: String, v: String) { if (v.isNotEmpty()) header.add(label to v) }
                    val sup = r.optJSONObject("Supplier")
                    val supName = listOf(sup?.str("Name1") ?: "", sup?.str("Name2") ?: "")
                        .filter { it.isNotEmpty() }.joinToString(" ")
                    val city = sup?.str("City") ?: ""
                    add("Поставщик", if (city.isNotEmpty()) "$supName ($city)" else supName)
                    add("Создан", fmtDate(r.str("CreationDate"), true))
                    add("План прихода", fmtDate(r.str("PlannedGoodsInDate")))
                    add("Статус", statusRu(statusKey(r.optJSONObject("Calculated"))))
                    add("Внешний номер", r.str("HostReferenceNumber2").ifEmpty { r.str("ExternalNumber") })
                    add("Категория", r.str("Category"))
                    add("Примечание", r.str("Remarks"))
                }
            }
        } catch (e: CgError) {
            if (e.needSite) throw e
            header.add("Шапка заказа" to (e.message ?: "ошибка"))
        }

        val positions = ArrayList<CgPos>()
        var done = 0
        var proc = 0
        var created = 0
        var total = 0
        var skip = 0
        while (true) {
            val body = JSONObject()
                .put(
                    "SearchRequest", JSONObject()
                        .put("SortConfigurationKey", "GoodsInOrder.SortConfigurations.Number")
                        .put("PropertyFilters", JSONArray())
                        .put("InvertSorting", false)
                        .put("CancelSearchRequestIfAbortedByClient", true)
                        .put("Skip", skip)
                        .put("Top", 100)
                )
                .put("ParentId", id)
                .put("AddOverdeliveryWarning", true)
                .put("AddSinglePartRequirements", true)
                .put("AddStockQualificationsRequiredFields", true)
                .toString()
            val txt = call("GoodsInOrder.Forms.GoodsInOrder.LayoutItems.Positions.Actions.Search", body)
            val got = parsing {
                val pr = JSONObject(txt).optJSONObject("PageResult")
                val items = pr?.optJSONArray("Items")
                total = pr?.optInt("Total") ?: 0
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val p = items.getJSONObject(i)
                        val calc = p.optJSONObject("Calculated")
                        val art = calc?.optJSONObject("Article")
                        val title = listOf(art?.str("Number") ?: "", art?.str("Name") ?: "")
                            .filter { it.isNotEmpty() }.joinToString(" · ")
                        val key = statusKey(calc)
                        when (key) {
                            "Done" -> done++
                            "Process" -> proc++
                            "Created" -> created++
                        }
                        val parts = ArrayList<String>()
                        if (!p.isNull("Quantity")) {
                            val unit = unitName(art?.optJSONObject("Calculated")?.optJSONObject("QuantityUnit"))
                            parts.add((num(p.optDouble("Quantity")) + " " + unit).trim())
                        }
                        val gtin = art?.str("GTIN") ?: ""
                        if (gtin.isNotEmpty()) parts.add("EAN $gtin")
                        val mhd = fmtDate(p.str("ExpirationDate"))
                        if (mhd.isNotEmpty()) parts.add("MHD $mhd")
                        val sr = statusRu(key)
                        if (sr.isNotEmpty()) parts.add(sr)
                        positions.add(CgPos(p.optInt("Number"), title, parts.joinToString(" · "), key == "Done"))
                    }
                }
                items?.length() ?: 0
            }
            skip += got
            if (got <= 0 || skip >= total || skip >= 500) break
        }
        return CgOrderDetail(id, number, header, total, skip, done, proc, created, positions)
    }

    // ---------------- Infoscan ----------------

    suspend fun infoscan(code: String): CgScan {
        val body = JSONObject()
            .put("Barcode", code)
            .put("BarcodeConfigurationName", JSONObject.NULL)
            .put("IncludeAllBarcodeInfos", false)
            .toString()
        val txt = call("Warehouse.Forms.InfoScan.LayoutItems.InfoScan.Actions.GetEntitiesFromBarcode", body)
        val cards = ArrayList<CgCard>()
        val unitIds = ArrayList<Long>()
        parsing {
            val ents = JSONObject(txt).optJSONObject("Result")?.optJSONArray("Entities")
            if (ents != null) {
                for (i in 0 until ents.length()) {
                    val e = ents.getJSONObject(i)
                    cards.add(describe(e))
                    if (e.str("EntityType").endsWith("StorageUnit") && !e.isNull("EquipmentTypeId")) unitIds.add(e.optLong("Id"))
                }
            }
        }
        val contents = ArrayList<CgCard>()
        var contentTotal = 0
        for (uid in unitIds) {
            val (list, total) = content(uid)
            contents.addAll(list)
            contentTotal += total
        }
        return CgScan(cards, contents, contentTotal)
    }

    private fun describe(e: JSONObject): CgCard {
        val type = e.str("EntityType").substringAfterLast('.')
        val calc = e.optJSONObject("Calculated")
        val bc = e.str("Barcode")
        val title = if (bc.isNotEmpty()) "$type  $bc" else type
        val lines = ArrayList<String>()
        val nm = listOf(e.str("Number"), e.str("Name"), e.str("Description")).filter { it.isNotEmpty() }.joinToString(" · ")
        if (nm.isNotEmpty()) lines.add(nm)
        val et = calc?.optJSONObject("EquipmentType")
        if (et != null) {
            val t = et.str("Description").ifEmpty { et.str("Name") }
            if (t.isNotEmpty()) lines.add("Тип: $t")
        }
        val parents = calc?.optJSONObject("Topology")?.optJSONArray("Parents")
        if (parents != null && parents.length() > 0) {
            val names = ArrayList<String>()
            for (i in 0 until parents.length()) {
                val n = parents.getJSONObject(i).str("Name")
                if (n.isNotEmpty()) names.add(n)
            }
            if (names.isNotEmpty()) lines.add("Место: " + names.joinToString(" > "))
        }
        val gi = fmtDate(calc?.str("GoodsInDate") ?: "", true)
        if (gi.isNotEmpty()) lines.add("Приход: $gi")
        return CgCard(title, lines)
    }

    private suspend fun content(unitId: Long): Pair<List<CgCard>, Int> {
        val body = JSONObject()
            .put("ConsiderAllBlockingKindsForCalculatedQuantities", true)
            .put("Id", unitId)
            .put(
                "SearchRequest", JSONObject()
                    .put("SortConfigurationKey", JSONObject.NULL)
                    .put("InvertSorting", false)
                    .put("CancelSearchRequestIfAbortedByClient", true)
                    .put("Skip", 0)
                    .put("Top", 50)
            ).toString()
        val txt = call("Equipment.Forms.EquipmentDetails.LayoutItems.EquipmentContent.Actions.Search", body)
        return parsing {
            val pr = JSONObject(txt).optJSONObject("PageResult")
            val items = pr?.optJSONArray("Items")
            val list = ArrayList<CgCard>()
            if (items != null) for (i in 0 until items.length()) list.add(quant(items.getJSONObject(i)))
            list to (pr?.optInt("Total") ?: 0)
        }
    }

    private fun quant(q: JSONObject): CgCard {
        val art = q.optJSONObject("Calculated")?.optJSONObject("Article")
        val lines = ArrayList<String>()
        val title: String
        if (art != null) {
            val t = listOf(art.str("Number"), art.str("Name")).filter { it.isNotEmpty() }.joinToString(" · ")
            title = t.ifEmpty { "артикул " + art.str("Id") }
            val desc = art.str("Description")
            if (desc.isNotEmpty()) lines.add(desc)
            val gtin = art.str("GTIN")
            if (gtin.isNotEmpty()) lines.add("EAN $gtin")
        } else {
            title = "(без артикула)"
        }
        val parts = ArrayList<String>()
        if (!q.isNull("Quantity")) {
            val unit = unitName(art?.optJSONObject("Calculated")?.optJSONObject("QuantityUnit"))
            parts.add((num(q.optDouble("Quantity")) + " " + unit).trim())
        }
        val mhd = fmtDate(q.str("ExpirationDate"))
        if (mhd.isNotEmpty()) parts.add("MHD $mhd")
        val batch = q.str("Batch")
        if (batch.isNotEmpty()) parts.add("Charge $batch")
        if (parts.isNotEmpty()) lines.add(parts.joinToString(" · "))
        return CgCard(title, lines)
    }
}
