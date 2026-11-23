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
import android.view.MotionEvent
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
    var onScan: (() -> Unit)? = null
    var onMenuResult: ((String) -> Unit)? = null
    /** Сканер меню: сюда приходит список пунктов System (JSON). */
    var onMenuList: ((String) -> Unit)? = null
    var onPageChanged: (() -> Unit)? = null
    /** Страница просит показать клавиатуру (двойное/тройное нажатие или долгое удержание на поле). */
    var onShowKb: (() -> Unit)? = null
    @Volatile var translateOn = true
    @Volatile var scanNative = true
    @Volatile var desktopMode = false
    @Volatile var darkMode = false

    @JavascriptInterface
    fun log(line: String) {
        synchronized(items) {
            items.add(line)
            if (items.size > 200) items.removeAt(0)
        }
        CoglasPages.noteLog(line)
    }

    /** Сайт открывает свой сканер (getUserMedia) — вместо камеры сайта открываем сканер приложения. */
    @JavascriptInterface
    fun scanOn(): Boolean = scanNative

    /** Приложение показывает свой календарь вместо календаря сайта (обработчик задаёт экран Coglas). */
    var onPickDate: ((String, String) -> Unit)? = null

    /** Нажали на поле даты на сайте: true — календарь покажет приложение, календарь сайта подавляется. */
    @JavascriptInterface
    fun pickDate(initial: String, label: String): Boolean {
        val cb = onPickDate ?: return false
        main.post { cb(initial, label) }
        return true
    }

    /** Телефон в ландшафте: шапка сайта не прижата к верху, а прокручивается вместе со страницей. */
    @JavascriptInterface
    fun isLand(): Boolean = CoglasWeb.landscape

    @JavascriptInterface
    fun scan() {
        main.post { onScan?.invoke() }
    }

    /** Включён вид «ПК»: страница читает это при загрузке и ставит ширину окна как у монитора. */
    @JavascriptInterface
    fun desktopOn(): Boolean = desktopMode

    /** Включена тёмная тема сайта: страница читает это при загрузке и сразу красится тёмной. */
    @JavascriptInterface
    fun darkOn(): Boolean = darkMode

    /** Итог открытия пункта меню System: "ok" или текст ошибки. */
    @JavascriptInterface
    fun menuResult(msg: String) {
        main.post { onMenuResult?.invoke(msg) }
    }

    @JavascriptInterface
    fun menuList(json: String) {
        main.post { onMenuList?.invoke(json) }
    }

    @JavascriptInterface
    fun showKb() {
        main.post { onShowKb?.invoke() }
    }

    /** Шапка сайта найдена (true) или на странице её нет, например экран входа (false). */
    @JavascriptInterface
    fun barFound(found: Boolean) {
        main.post { CoglasWeb.siteBar = found }
    }

    /** На сайте сменилась страница (заголовок). */
    @JavascriptInterface
    fun pageChanged(title: String) {
        main.post { onPageChanged?.invoke() }
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
    /** Сколько пакетов текста сейчас переводится (для индикатора на экране). */
    var trBusy by mutableIntStateOf(0)
    /** Перевод временно отключён сторожем: страница с ним не отрисовалась. */
    var trSafe by mutableStateOf(false)
    /** Шапка самого сайта (домик, окна, гамбургер, кнопка сканера) найдена на странице. */
    var siteBar by mutableStateOf(false)
    private var watchToken = 0
    /** Меняется, когда WebView пришлось создать заново (система убила его процесс). */
    var viewKey by mutableIntStateOf(0)
    var lastRaw = ""

    /** Вид сайта: true — как на ПК (широкая страница, десктопный User-Agent), false — мобильный. */
    var desktop by mutableStateOf(false)
    private var mobileUa = ""
    private var desktopUa = ""

    /** Тёмная тема сайта (переключатель во вкладке «Настройки»). Применяется сразу, без перезагрузки страницы. */
    var dark by mutableStateOf(false)

    private fun darkBg(on: Boolean): Int = if (on) Color.rgb(14, 14, 14) else Color.WHITE

    fun setDark(ctx: Context, on: Boolean) {
        CoglasTr.setDark(ctx, on)
        dark = on
        bridge.darkMode = on
        val w = web ?: return
        w.setBackgroundColor(darkBg(on))
        w.evaluateJavascript("window.__cgSetDark && window.__cgSetDark(" + on + ")", null)
    }

    // ---- масштаб: на каком масштабе оставили окно, на таком открываются и остальные
    private var zoomSaved = 0f
    private var curScale = 0f
    private var zoomUntil = 0L
    private var zoomApplying = false
    private var lastUpAt = 0L

    private fun noteTouch(e: MotionEvent) {
        val now = System.currentTimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> zoomUntil = now + 2500L // щипок двумя пальцами
            MotionEvent.ACTION_DOWN -> if (now - lastUpAt < 350L) zoomUntil = now + 2500L // двойное касание
            MotionEvent.ACTION_UP -> lastUpAt = now
        }
    }

    private fun onScale(newScale: Float) {
        curScale = newScale
        if (zoomApplying) return
        val ctx = appCtx ?: return
        if (System.currentTimeMillis() < zoomUntil) {
            // масштаб поменял сам человек: запоминаем
            zoomSaved = newScale
            CoglasTr.setZoom(ctx, desktop, newScale)
        } else if (zoomSaved > 0f && Math.abs(newScale / zoomSaved - 1f) > 0.03f) {
            // страница сама сбросила масштаб (новое окно, перерисовка) — возвращаем сохранённый
            applyZoom(500L)
        }
    }

    /** Вернуть сохранённый масштаб (через delay мс: страница к этому времени успевает разложиться). */
    @Suppress("DEPRECATION")
    fun applyZoom(delay: Long) {
        main.postDelayed({
            val w = web ?: return@postDelayed
            if (zoomSaved <= 0f || zoomApplying) return@postDelayed
            val cur = if (curScale > 0f) curScale else w.scale
            if (cur <= 0f || Math.abs(cur / zoomSaved - 1f) <= 0.03f) return@postDelayed
            zoomApplying = true
            try { w.zoomBy((zoomSaved / cur).coerceIn(0.05f, 20f)) } catch (e: Exception) { }
            main.postDelayed({ zoomApplying = false }, 900L)
        }, delay)
    }

    private fun applyUa(w: WebView) {
        w.settings.userAgentString = if (desktop) desktopUa else mobileUa
    }

    fun setDesktop(ctx: Context, on: Boolean) {
        CoglasTr.setDesktop(ctx, on)
        desktop = on
        zoomSaved = CoglasTr.zoom(ctx, on)
        curScale = 0f
        bridge.desktopMode = on
        val w = web ?: return
        applyUa(w)
        w.reload()
    }

    /** Открыть окно сайта из меню System по названию (de — немецкое, ru — русское). */
    fun openMenu(de: String, ru: String) {
        main.post {
            runJs("window.__cgOpenMenu ? window.__cgOpenMenu(" + JSONObject.quote(de) + "," + JSONObject.quote(ru) +
                ") : 'nohook'") { r ->
                if (r == "nohook") menuToast("Сайт ещё не загрузился. Подождите и нажмите снова.")
            }
        }
    }

    /** Пока идёт сканер, итог открытия пункта меню получает он (без всплывающих сообщений). */
    var menuListener: ((String) -> Unit)? = null

    private fun menuToast(msg: String) {
        val c = appCtx ?: return
        android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Сканер штрихкодов приложения (с ИИ) вместо камеры сайта. */
    var nativeScan by mutableStateOf(true)
    var onSiteScan: (() -> Unit)? = null
    private var scanOpen = false
    private var snapToken = 0

    fun requestScan() {
        main.post {
            if (scanOpen) return@post
            val f = onSiteScan ?: return@post
            scanOpen = true
            f()
        }
    }

    fun scanClosed() { scanOpen = false }

    fun setNativeScan(ctx: Context, on: Boolean) {
        CoglasTr.setScan(ctx, on)
        nativeScan = on
        bridge.scanNative = on
    }

    /** Код со сканера приложения подставляется в поле страницы и подтверждается Enter, как от сканера-«клавиатуры». */
    fun deliverScan(code: String) {
        main.post {
            runJs("window.__cgScanResult ? window.__cgScanResult(" + JSONObject.quote(code) + ") : 'nohook'") { r ->
                if (r != "ok") {
                    val ctx = appCtx
                    if (ctx != null) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("barcode", code))
                        android.widget.Toast.makeText(ctx, "Нет поля для кода на странице. Код скопирован: $code", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

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

    fun asset(ctx: Context, name: String): String =
        ctx.assets.open("coglas/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun hostOk(url: String?): Boolean =
        try { android.net.Uri.parse(url).host?.endsWith(HOST) == true } catch (e: Exception) { false }

    @SuppressLint("SetJavaScriptEnabled")
    fun view(ctx: Context): WebView {
        appCtx = ctx.applicationContext
        web?.let { w ->
            (w.context as? MutableContextWrapper)?.baseContext = ctx
            (w.parent as? ViewGroup)?.removeView(w)
            w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.onResume()
            w.resumeTimers()
            return w
        }
        val app = ctx.applicationContext
        trOn = CoglasTr.enabled(app)
        bridge.translateOn = trOn
        nativeScan = CoglasTr.scan(app)
        bridge.scanNative = nativeScan
        desktop = CoglasTr.desktop(app)
        bridge.desktopMode = desktop
        dark = CoglasTr.dark(app)
        bridge.darkMode = dark
        CoglasPages.init(app)

        val dict = CoglasTr.dictJson(app).replace("\\", "\\\\").replace("'", "\\'")
        zoomSaved = CoglasTr.zoom(app, desktop)
        script = asset(app, "hook.js") + "\n" + asset(app, "bar.js") + "\n" + asset(app, "app.js").replace("__DICT__", dict)

        val w = WebView(MutableContextWrapper(ctx))
        // ВАЖНО: без match_parent WebView внутри Compose считает свою высоту «по содержимому» (0),
        // и у сайта 100% / 100vh превращаются в 0 — страница рисуется только шапкой.
        w.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
        mobileUa = s.userAgentString
        // десктопный User-Agent: без "Android", "Mobile" и "wv"
        desktopUa = mobileUa
            .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
            .replace(" Mobile", "")
            .replace("; wv", "")
        if (desktop) s.userAgentString = desktopUa
        w.overScrollMode = View.OVER_SCROLL_NEVER
        // Свайп вверх-вниз по сайту: не отдавать жест родителям (боковой свайп вкладок, прокрутки Compose),
        // иначе сайт получает ACTION_CANCEL посреди движения и «залипает».
        w.setOnTouchListener { v, e ->
            noteTouch(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.setBackgroundColor(darkBg(dark))

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }

        bridge.onResult = { id, status, body -> pending.remove(id)?.invoke(status, body) }
        bridge.onTranslate = { json -> translate(json) }
        bridge.onScan = { requestScan() }
        bridge.onShowKb = {
            val v = web
            if (v != null) {
                v.requestFocus()
                val imm = app.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        bridge.onMenuResult = { m ->
            val l = menuListener
            if (l != null) l(m) else if (m != "ok") menuToast(m)
        }
        bridge.onPageChanged = { if (Session.isAdmin) scheduleSnapshot(900L) }
        CoglasPages.onNewForm = {
            if (Session.isAdmin) {
                scheduleSnapshot(1800L)
                // второй снимок позже: первый мог застать ещё старую страницу (заголовок и кнопки соседнего окна)
                val k = CoglasPages.currentKey()
                main.postDelayed({ if (web != null && CoglasPages.currentKey() == k) snapshot() }, 4500L)
            }
        }
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

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                onScale(newScale)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (hostOk(url)) {
                    view.evaluateJavascript(script, null); watch(view); nudge(view, 0)
                    applyZoom(400L); applyZoom(1500L); applyZoom(3500L)
                }
                canGoBack = view.canGoBack()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                canGoBack = view.canGoBack()
                if (Session.isAdmin && hostOk(url)) scheduleSnapshot(2500L)
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // система убила процесс WebView (нехватка памяти / сон): страница стала бы пустой — создаём заново
                main.post { recreateView() }
                return true
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
                    if (nativeScan) {
                        // запасной путь: страница всё же попросила камеру — отдаём сканеру приложения
                        request.deny()
                        requestScan()
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

    /**
     * Сторож: через 25 с после загрузки смотрим, что на странице. Если перевод уже запущен, а страница пустая —
     * перезагружаем сайт без перевода (сайт важнее перевода). Если перевод даже не стартовал — виноват не он.
     */
    private fun watch(view: WebView) {
        val token = ++watchToken
        main.postDelayed({
            if (token != watchToken || web !== view || !trOn) return@postDelayed
            if (bridge.translateOn.not()) return@postDelayed
            view.evaluateJavascript("(document.body?document.body.innerText.trim().length:0)+'|'+(window.__cgStat||'')") { raw ->
                val v = (raw ?: "").trim('"')
                val len = v.substringBefore('|').toIntOrNull() ?: return@evaluateJavascript
                val stat = v.substringAfter('|', "")
                if (len >= 40) return@evaluateJavascript
                when (stat) {
                    "on", "off:loop" -> {
                        trSafe = true
                        bridge.translateOn = false
                        trNote = "Сайт не отрисовался с переводом — открыт без перевода."
                        view.reload()
                    }
                    "nostart" -> trNote = "Сайт не вышел с экрана загрузки (перевод ещё не запускался). Проверьте интернет и вход."
                    else -> { }
                }
            }
        }, 25000L)
    }

    /** Раз в полсекунды заставляем WebView перерисоваться: без этого он мог «замирать» с пустым экраном. */
    private fun nudge(view: WebView, n: Int) {
        if (n > 12) return
        main.postDelayed({
            if (web === view) {
                view.invalidate()
                if (n % 4 == 0) { view.onResume(); view.resumeTimers() }
                nudge(view, n + 1)
            }
        }, 500L)
    }

    private fun recreateView() {
        val old = web ?: return
        (old.parent as? ViewGroup)?.removeView(old)
        try { old.destroy() } catch (e: Exception) { }
        web = null
        pending.clear()
        trBusy = 0
        viewKey++
    }

    /** Экран снова виден: будим WebView (после сна/фона он мог остановить таймеры и отрисовку). */
    fun wake() {
        val w = web ?: return
        w.onResume()
        w.resumeTimers()
        w.postInvalidate()
    }

    fun goBack(): Boolean {
        val w = web ?: return false
        return if (w.canGoBack()) { w.goBack(); true } else false
    }

    /**
     * Системная «Назад» на вкладке «Сайт»: сайт — одностраничный (история WebView не меняется), поэтому нажимаем
     * кнопку «Назад» самой страницы (.btn-back или кнопка с текстом Назад/Zurück). onResult(true) — нажата.
     */
    fun siteBack(onResult: (Boolean) -> Unit) {
        main.post {
            runJs(SITE_BACK_JS) { r -> onResult(r == "clicked") }
        }
    }

    private const val SITE_BACK_JS = "(function(){" +
        "function vis(e){try{var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;" +
        "var cs=getComputedStyle(e);return cs.visibility!=='hidden'&&cs.display!=='none';}catch(x){return false;}}" +
        "var rx=/^(назад|zurück|zurueck|back)$/i;" +
        "var c=[].slice.call(document.querySelectorAll('.btn-back,a,button,[role=button]')).filter(function(e){" +
        "if(!vis(e)||e.disabled||e.id==='__cgScanBtn')return false;" +
        "if(e.classList&&e.classList.contains('btn-back'))return true;" +
        "return rx.test((e.textContent||'').replace(/\\s+/g,' ').trim());});" +
        "if(!c.length)return 'none';" +
        "var b=c.filter(function(e){return e.classList&&e.classList.contains('btn-back');})[0]||c[0];" +
        "try{b.click();}catch(x){return 'none';}return 'clicked';})()"

    /** Ландшафт: сайт читает это через мост (isLand) и перестаёт прижимать свою шапку к верху экрана. */
    @Volatile var landscape = false
    fun applyLandscape(on: Boolean) {
        landscape = on
        main.post { web?.evaluateJavascript("try{window.__cgBarPlace&&window.__cgBarPlace()}catch(e){}", null) }
    }

    /** Дата из календаря приложения → в поле даты, на которое нажали на сайте (в фоне). */
    fun deliverDate(value: String) {
        main.post { runJs("window.__cgDateResult ? window.__cgDateResult(" + JSONObject.quote(value) + ") : 'nohook'") { } }
    }

    fun reload() { web?.reload() }
    fun home() { web?.loadUrl(START_URL) }

    fun setTranslate(ctx: Context, on: Boolean) {
        CoglasTr.setEnabled(ctx, on)
        trOn = on
        trSafe = false
        bridge.translateOn = on
        // выключили — страница перезагружается и снова показывается по-немецки; включили — переводится
        web?.reload()
    }

    /** full = true: «перевести полностью» — в перевод идут и длинные строки, и строки с цифрами, и слова ЗАГЛАВНЫМИ. */
    fun retryTranslate(full: Boolean = false) {
        trNote = ""
        web?.evaluateJavascript("window.__cgRetry && window.__cgRetry(" + full + ")", null)
    }

    /** Снять данные страницы через delay мс; частые вызовы склеиваются в один. */
    fun scheduleSnapshot(delay: Long) {
        val t = ++snapToken
        main.postDelayed({ if (t == snapToken && web != null) snapshot() }, delay)
    }

    /** Снять данные текущей страницы сайта (заголовки, кнопки, меню, поля) для списка «что делать в App». */
    fun snapshot(done: (() -> Unit)? = null) {
        val ctx = appCtx ?: return
        runJs(asset(ctx, "pageinfo.js")) { txt ->
            CoglasPages.update(txt)
            done?.invoke()
        }
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

    private val trLock = Any()

    private fun translate(json: String) {
        val app = appCtx ?: return
        main.post { trBusy++ }
        thread {
            try {
                val arr = JSONArray(json)
                val lines = (0 until arr.length()).map { arr.getString(it) }
                // пакеты переводятся строго по очереди: общая память переводов не портится
                val res = synchronized(trLock) { CoglasTr.translate(app, lines) }
                main.post { trNote = "" }
                if (res.isNotEmpty()) {
                    val js = "window.__cgApply && window.__cgApply(" + JSONObject.quote(JSONObject(res).toString()) + ")"
                    main.post { web?.evaluateJavascript(js, null) }
                }
            } catch (e: Throwable) {
                val m = e.message ?: "Ошибка перевода"
                main.post { trNote = m }
            } finally {
                main.post { if (trBusy > 0) trBusy-- }
            }
        }
    }
}

/** Перевод Coglas на русский: готовый словарь немецких слов + ИИ для остального; всё, что ИИ перевёл, запоминается на телефоне. */
object CoglasTr {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("coglas_tr", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("on", true)
    fun setEnabled(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean("on", on).apply() }
    fun desktop(ctx: Context): Boolean = sp(ctx).getBoolean("desktop", false)
    fun setDesktop(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean("desktop", on).apply() }
    fun dark(ctx: Context): Boolean = sp(ctx).getBoolean("dark", false)
    fun setDark(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean("dark", on).apply() }
    fun scan(ctx: Context): Boolean = sp(ctx).getBoolean("scan", true)
    fun setScan(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean("scan", on).apply() }
    /** Масштаб сайта, на котором человек оставил окно (отдельно для вида ПК и мобильного). */
    fun zoom(ctx: Context, desktop: Boolean): Float = sp(ctx).getFloat(if (desktop) "zoom_d" else "zoom_m", 0f)
    fun setZoom(ctx: Context, desktop: Boolean, v: Float) { sp(ctx).edit().putFloat(if (desktop) "zoom_d" else "zoom_m", v).apply() }
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


// ======================= страницы сайта: что уже есть в App, что ещё нужно сделать =======================

class CgPage(
    val key: String,
    var title: String = "",
    var url: String = "",
    var visits: Int = 0,
    var last: Long = 0L,
    var headings: List<String> = emptyList(),
    var buttons: List<String> = emptyList(),
    var menu: List<String> = emptyList(),
    var fields: List<String> = emptyList(),
    var inputs: Int = 0,
    var tables: Int = 0,
    var apis: List<String> = emptyList()
) {
    /** Какая часть этой страницы уже сделана в App родным экраном ("" — нет). */
    fun inApp(): String = when {
        apis.any { it.contains("GoodsInOrder.Forms.GoodsInOrder") } -> "Заказы на приход"
        apis.any { it.contains("InfoScan", ignoreCase = true) } -> "Infoscan"
        else -> ""
    }
}

/** Журнал страниц сайта, по которым ходили в окне «Сайт»: заголовки, кнопки, поля и запросы API. Хранится на телефоне. */
object CoglasPages {
    private val map = LinkedHashMap<String, CgPage>()
    private var ctx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current = ""
    var version by mutableIntStateOf(0)

    private val saver = Runnable { save() }

    private fun sp() = ctx?.getSharedPreferences("coglas_pages", Context.MODE_PRIVATE)

    @Synchronized
    fun init(c: Context) {
        if (ctx != null) return
        ctx = c.applicationContext
        try {
            val arr = JSONArray(sp()?.getString("pages", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                fun strs(k: String): List<String> {
                    val a = o.optJSONArray(k) ?: return emptyList()
                    return (0 until a.length()).map { a.optString(it) }
                }
                if (!o.getString("key").startsWith("form:")) continue // старые записи по адресу (он у сайта один) не нужны
                val p = CgPage(
                    o.getString("key"), o.optString("title"), o.optString("url"), o.optInt("visits"), o.optLong("last"),
                    strs("headings"), strs("buttons"), strs("menu"), strs("fields"), o.optInt("inputs"), o.optInt("tables"), strs("apis")
                )
                map[p.key] = p
            }
        } catch (e: Exception) {
        }
        main.postDelayed({ exportFile() }, 3000L)
    }

    @Synchronized
    private fun save() {
        try {
            val arr = JSONArray()
            for (p in map.values) {
                arr.put(
                    JSONObject().put("key", p.key).put("title", p.title).put("url", p.url).put("visits", p.visits).put("last", p.last)
                        .put("headings", JSONArray(p.headings)).put("buttons", JSONArray(p.buttons)).put("menu", JSONArray(p.menu))
                        .put("fields", JSONArray(p.fields)).put("inputs", p.inputs).put("tables", p.tables).put("apis", JSONArray(p.apis))
                )
            }
            sp()?.edit()?.putString("pages", arr.toString())?.apply()
        } catch (e: Exception) {
        }
        exportFile()
    }

    /**
     * Весь отчёт по страницам сайта сам обновляется одним файлом «Загрузки/coglas_report.txt» на телефоне
     * (после каждого нового окна/запроса, с паузой ~1,5 с). Его можно прикрепить из памяти телефона.
     */
    fun exportFile(done: ((Boolean) -> Unit)? = null) {
        val c = ctx ?: run { done?.invoke(false); return }
        if (map.isEmpty()) { done?.invoke(false); return }
        val text = report()
        thread {
            var ok = false
            try {
                val name = "coglas_report.txt"
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val cr = c.contentResolver
                    val rel = android.os.Environment.DIRECTORY_DOWNLOADS + "/"
                    val coll = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    var uri: android.net.Uri? = null
                    cr.query(
                        coll, arrayOf(android.provider.MediaStore.MediaColumns._ID),
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " +
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                        arrayOf(name, rel), null
                    )?.use { if (it.moveToFirst()) uri = android.content.ContentUris.withAppendedId(coll, it.getLong(0)) }
                    if (uri == null) {
                        val v = android.content.ContentValues()
                        v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                        v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel)
                        uri = cr.insert(coll, v)
                    }
                    uri?.let { u -> cr.openOutputStream(u, "wt")?.use { o -> o.write(text.toByteArray(Charsets.UTF_8)); ok = true } }
                } else {
                    val d = c.getExternalFilesDir(null)
                    if (d != null) { java.io.File(d, name).writeText(text, Charsets.UTF_8); ok = true }
                }
            } catch (e: Exception) {
            }
            if (done != null) main.post { done(ok) }
        }
    }

    private fun changed() {
        main.removeCallbacks(saver)
        main.postDelayed(saver, 1500L)
        main.post { version++ }
    }

    private fun page(key: String): CgPage = synchronized(this) {
        map.getOrPut(key) {
            if (map.size >= 150) map.keys.firstOrNull()?.let { map.remove(it) }
            CgPage(key)
        }
    }

    /** Форма сайта из запроса: formKey=... или начало actionKey до ".LayoutItems". */
    private fun formOf(line: String): String? {
        Regex("formKey=([A-Za-z0-9_.]+)").find(line)?.let { return it.groupValues[1] }
        val a = Regex("actionKey=([A-Za-z0-9_.]+)").find(line)?.groupValues?.get(1) ?: return null
        val i = a.indexOf(".LayoutItems")
        return if (i > 0) a.substring(0, i) else null
    }

    /** Запросы сайта: адрес в браузере всегда "#", поэтому страницы различаем по форме (formKey) в запросах API. */
    @Synchronized
    fun noteLog(line: String) {
        if (!Session.isAdmin) return
        val form = formOf(line)
        if (form != null && "form:$form" != current) {
            current = "form:$form"
            val p = page(current)
            p.visits++
            p.last = System.currentTimeMillis()
            if (p.title.isBlank()) p.title = form.substringAfterLast('.')
            main.post { onNewForm?.invoke() }
        }
        val key = current
        if (key.isEmpty()) return
        val api = Regex("actionKey=([A-Za-z0-9_.]+)").find(line)?.groupValues?.get(1)
            ?: Regex("^\\S+ (?:GET|POST|PUT|DELETE|PATCH) \\d+ (\\S+)").find(line)?.groupValues?.get(1)
                ?.substringBefore('?')?.takeIf { it.contains("/api/", ignoreCase = true) }
            ?: return
        val p = page(key)
        if (api in p.apis || p.apis.size >= 40) return
        p.apis = p.apis + api
        changed()
    }

    var onNewForm: (() -> Unit)? = null

    /** Ответ pageinfo.js: данные открытой страницы записываются на последнюю открытую форму. */
    @Synchronized
    fun update(json: String) {
        try {
            val o = JSONObject(json)
            val key = current
            if (!key.startsWith("form:")) return
            val p = page(key)
            fun strs(k: String): List<String> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).map { a.optString(it) }
            }
            val h = strs("headings")
            // снимок стартовой страницы («Добро пожаловать») не относится к другим окнам: не затираем ими данные
            if (h.any { it.startsWith("Добро пожаловать") } && !key.endsWith("Administration.Forms.About")) return
            // пустой снимок (страница ещё не нарисована) не стирает уже собранные кнопки и поля
            if (strs("buttons").isEmpty() && strs("fields").isEmpty() && (p.buttons.isNotEmpty() || p.fields.isNotEmpty())) return
            p.url = o.optString("url").take(300)
            val t = h.firstOrNull() ?: o.optString("title")
            if (t.isNotBlank()) p.title = t.take(80)
            p.headings = h; p.buttons = strs("buttons"); p.menu = strs("menu"); p.fields = strs("fields")
            p.inputs = o.optInt("inputs"); p.tables = o.optInt("tables")
            p.last = System.currentTimeMillis()
            changed()
        } catch (e: Exception) {
        }
    }

    /** Страницы: сначала те, которых ещё нет в App, потом по числу заходов. */
    @Synchronized
    fun list(): List<CgPage> = map.values.sortedWith(compareBy<CgPage> { it.inApp().isNotEmpty() }.thenByDescending { it.visits })

    @Synchronized
    fun clear() { map.clear(); current = ""; changed() }

    /** Для сканера меню: последняя открытая форма сайта и её запросы API. */
    @Synchronized
    fun currentKey(): String = current

    @Synchronized
    fun apisOf(key: String): List<String> = map[key]?.apis ?: emptyList()

    @Synchronized
    fun report(): String {
        val all = list()
        val todo = all.count { it.inApp().isEmpty() }
        val sb = StringBuilder()
        sb.append("Страницы сайта Coglas для App\n")
        sb.append("Всего: ${all.size} · уже в App: ${all.size - todo} · нужно сделать: $todo\n")
        for (p in all) {
            sb.append("\n== ").append(p.title.ifEmpty { "(без названия)" }).append("\n")
            sb.append("путь: ").append(p.key).append(" · заходов: ").append(p.visits).append("\n")
            val a = p.inApp()
            sb.append("в App: ").append(if (a.isEmpty()) "НЕТ" else "есть ($a)").append("\n")
            if (p.menu.isNotEmpty()) sb.append("меню: ").append(p.menu.joinToString(" | ")).append("\n")
            if (p.headings.isNotEmpty()) sb.append("заголовки: ").append(p.headings.joinToString(" | ")).append("\n")
            if (p.buttons.isNotEmpty()) sb.append("кнопки: ").append(p.buttons.joinToString(" | ")).append("\n")
            if (p.fields.isNotEmpty()) sb.append("поля: ").append(p.fields.joinToString(" | ")).append("\n")
            if (p.inputs > 0 || p.tables > 0) sb.append("полей ввода: ${p.inputs} · таблиц: ${p.tables}\n")
            if (p.apis.isNotEmpty()) sb.append("запросы API:\n").append(p.apis.joinToString("\n") { "  $it" }).append("\n")
        }
        return sb.toString()
    }
}

// ======================= данные Coglas (API сайта) =======================

data class CgOrder(val id: Long, val number: String, val supplier: String, val planned: String, val status: String, val positions: Int)
data class CgOrdersPage(val items: List<CgOrder>, val total: Int)
data class CgPos(
    val number: Int, val title: String, val details: String, val done: Boolean,
    /** для «Приема товаров»: артикул, название, штрихкод (GTIN), плановое количество и единица */
    val article: String = "", val name: String = "", val gtin: String = "", val qty: Double = 0.0, val unit: String = ""
)
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

    /** retry = true только для чтения (поиск списков): при обрыве сети запрос повторяется до 3 раз. Запись не повторяем: риск дубля. */
    suspend fun call(path: String, body: String, retry: Boolean = false): String {
        var last: CgError? = null
        for (attempt in 0..2) {
            val (st, txt) = CoglasWeb.api(
                "PUT", "/server/api/Process/executeAction?actionKey=$path", body
            )
            if (st == 200) {
                CoglasWeb.lastRaw = txt
                return txt
            }
            last = apiError(st, txt)
            val transient = st == 0 || st == -2 || st in 502..504
            if (!retry || !transient || attempt == 2) throw last
            kotlinx.coroutines.delay(1000L * (attempt + 1))
        }
        throw last ?: CgError("Ошибка сети.")
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
                        var planQty = 0.0
                        var planUnit = ""
                        if (!p.isNull("Quantity")) {
                            val unit = unitName(art?.optJSONObject("Calculated")?.optJSONObject("QuantityUnit"))
                            planQty = p.optDouble("Quantity")
                            planUnit = unit
                            parts.add((num(p.optDouble("Quantity")) + " " + unit).trim())
                        }
                        val gtin = art?.str("GTIN") ?: ""
                        if (gtin.isNotEmpty()) parts.add("EAN $gtin")
                        val mhd = fmtDate(p.str("ExpirationDate"))
                        if (mhd.isNotEmpty()) parts.add("MHD $mhd")
                        val sr = statusRu(key)
                        if (sr.isNotEmpty()) parts.add(sr)
                        positions.add(CgPos(p.optInt("Number"), title, parts.joinToString(" · "), key == "Done", art?.str("Number") ?: "", art?.str("Name") ?: "", gtin, planQty, planUnit))
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
