(function(){
  if (window.__wmsHook) return;
  window.__wmsHook = true;

  // Клавиатура только по касанию: сайт не может сам поставить курсор в поле (autofocus, el.focus() без нажатия пользователя).
  (function(){
    var last = 0;
    function mark(){ last = Date.now(); }
    ['touchstart', 'touchend', 'pointerdown', 'mousedown', 'click', 'keydown'].forEach(function(t){
      document.addEventListener(t, mark, true);
    });
    function isText(e){
      return !!e && (e.tagName === 'TEXTAREA' || e.isContentEditable ||
        (e.tagName === 'INPUT' && /^(text|search|number|tel|email|url|password|)$/i.test(e.type || '')));
    }
    function userRecent(){ return window.__wmsAllowFocus === true || Date.now() - last < 800; }
    try {
      var origFocus = HTMLElement.prototype.focus;
      HTMLElement.prototype.focus = function(){
        if (isText(this) && !userRecent()) return;
        return origFocus.apply(this, arguments);
      };
    } catch (x) {}
    document.addEventListener('focusin', function(e){
      try {
        var t = e.target;
        if (!isText(t) || userRecent()) return;
        if (e.relatedTarget && isText(e.relatedTarget)) return; // переход между полями кнопкой «Далее» на клавиатуре
        t.blur();
      } catch (x) {}
    }, true);
    // ---- Клавиатура: одно касание поля её НЕ открывает (код может приходить от сканера/«клавиатуры»).
    // Открывается двойным, тройным и более нажатием на поле или долгим удержанием.
    var kbEl = null, tapEl = null, tapAt = 0, tapN = 0, holdT = null, holdX = 0, holdY = 0;
    function setNone(el){
      try {
        if (!el || el === kbEl) return;
        if (el.__kbSaved === undefined) el.__kbSaved = el.hasAttribute('inputmode') ? el.getAttribute('inputmode') : null;
        el.setAttribute('inputmode', 'none');
      } catch (x) {}
    }
    function unNone(el){
      try {
        if (!el || el.__kbSaved === undefined) return;
        if (el.__kbSaved === null) el.removeAttribute('inputmode'); else el.setAttribute('inputmode', el.__kbSaved);
        delete el.__kbSaved;
      } catch (x) {}
    }
    function armAll(root){
      try {
        var l = (root && root.querySelectorAll ? root : document).querySelectorAll('input,textarea');
        for (var i = 0; i < l.length; i++) if (isText(l[i])) setNone(l[i]);
        if (root && root.nodeType === 1 && isText(root)) setNone(root);
      } catch (x) {}
    }
    function allowKb(el){
      if (!el || !isText(el)) return;
      kbEl = el;
      unNone(el);
      window.__wmsAllowFocus = true;
      try { el.blur(); } catch (x) {}
      setTimeout(function(){
        try {
          el.focus();
          if (window.WmsBridge && WmsBridge.showKb) WmsBridge.showKb();
        } catch (x) {}
        setTimeout(function(){ window.__wmsAllowFocus = false; }, 600);
      }, 40);
    }
    function textTarget(e){
      var t = e.target;
      if (t && t.nodeType === 3) t = t.parentElement;
      return isText(t) ? t : null;
    }
    document.addEventListener('touchstart', function(e){
      var t = textTarget(e);
      if (!t) return;
      if (t !== kbEl) setNone(t);
      var p = e.touches && e.touches[0];
      holdX = p ? p.clientX : 0; holdY = p ? p.clientY : 0;
      clearTimeout(holdT);
      holdT = setTimeout(function(){ allowKb(t); }, 550); // удержание
    }, true);
    document.addEventListener('touchmove', function(e){
      var p = e.touches && e.touches[0];
      if (p && (Math.abs(p.clientX - holdX) > 12 || Math.abs(p.clientY - holdY) > 12)) clearTimeout(holdT);
    }, true);
    ['touchend', 'touchcancel'].forEach(function(n){
      document.addEventListener(n, function(e){
        clearTimeout(holdT);
        if (n !== 'touchend') return;
        var t = textTarget(e);
        if (!t) return;
        var now = Date.now();
        if (t === tapEl && now - tapAt < 500) tapN++; else tapN = 1;
        tapEl = t; tapAt = now;
        if (tapN >= 2) allowKb(t); // двойное, тройное и больше
      }, true);
    });
    document.addEventListener('mousedown', function(e){ var t = textTarget(e); if (t && t !== kbEl) setNone(t); }, true);
    document.addEventListener('dblclick', function(e){ var t = textTarget(e); if (t) allowKb(t); }, true);
    document.addEventListener('contextmenu', function(e){ var t = textTarget(e); if (t) allowKb(t); }, true);
    // ввод с клавиатуры-сканера идёт в поле и без экранной клавиатуры; снова «замыкаем» поле, когда курсор ушёл
    document.addEventListener('focusin', function(e){
      var t = e.target;
      if (!isText(t)) return;
      if (t !== kbEl) setNone(t);
    }, true);
    // Клавиатура не должна закрывать результаты: пока она открыта (по двойному нажатию), снизу временно добавляется пустое место,
    // и поле прокручивается к верху видимой области. После выхода из поля всё возвращается как было.
    var padEl = null;
    function addPad(){
      if (padEl && padEl.isConnected) return;
      padEl = document.createElement('div');
      padEl.id = '__wmsPad';
      padEl.style.cssText = 'height:60vh;width:1px;pointer-events:none';
      (document.body || document.documentElement).appendChild(padEl);
    }
    function dropPad(){ if (padEl) { try { padEl.remove(); } catch (x) {} padEl = null; } }
    document.addEventListener('focusin', function(e){
      var t = e.target;
      if (!isText(t) || document.activeElement !== t) return;
      window.__wmsLast = t;
      window.__wmsLastAt = Date.now();
      if (t !== kbEl) return; // без клавиатуры ничего не двигаем
      addPad();
      setTimeout(function(){
        try {
          if (document.activeElement !== t) return;
          t.style.scrollMarginTop = '80px';
          t.scrollIntoView({ block: 'start', behavior: 'smooth' });
        } catch (x) {}
      }, 350);
    }, true);
    document.addEventListener('focusout', function(e){
      var t = e.target;
      setTimeout(function(){
        if (document.activeElement !== t && kbEl === t) { kbEl = null; setNone(t); }
        if (!isText(document.activeElement)) dropPad();
      }, 400);
    }, true);
    try {
      var st = document.createElement('style');
      st.textContent = 'input,textarea{touch-action:manipulation}';
      (document.head || document.documentElement).appendChild(st);
      document.addEventListener('DOMContentLoaded', function(){ armAll(document); });
      new MutationObserver(function(ms){
        for (var i = 0; i < ms.length; i++) {
          var n = ms[i].addedNodes;
          for (var j = 0; j < n.length; j++) if (n[j].nodeType === 1) armAll(n[j]);
        }
      }).observe(document.documentElement, { childList: true, subtree: true });
    } catch (x) {}
    function strip(){
      try {
        [].forEach.call(document.querySelectorAll('[autofocus]'), function(e){ e.removeAttribute('autofocus'); });
        var a = document.activeElement;
        if (isText(a) && !userRecent()) a.blur();
      } catch (x) {}
    }
    try {
      document.addEventListener('DOMContentLoaded', strip);
      window.addEventListener('load', function(){ strip(); setTimeout(strip, 500); setTimeout(strip, 1500); });
      new MutationObserver(function(ms){
        for (var i = 0; i < ms.length; i++) {
          var n = ms[i].addedNodes;
          for (var j = 0; j < n.length; j++) {
            var el = n[j];
            if (el && el.nodeType === 1) {
              if (el.hasAttribute && el.hasAttribute('autofocus')) el.removeAttribute('autofocus');
              if (el.querySelectorAll) [].forEach.call(el.querySelectorAll('[autofocus]'), function(x){ x.removeAttribute('autofocus'); });
            }
          }
        }
      }).observe(document.documentElement, { childList: true, subtree: true });
    } catch (x) {}
  })();
  var cap = window.__wmsHdr = {};
  function capture(k, v){ try { if (/^(x-csrf|x-requested-with)/i.test(k)) cap[k] = v; } catch (e) {} }
  function hc(h){
    try {
      if (!h) return;
      if (Array.isArray(h)) { h.forEach(function(p){ capture(p[0], p[1]); }); }
      else if (typeof h.forEach === 'function') { h.forEach(function(v, k){ capture(k, v); }); }
      else { Object.keys(h).forEach(function(k){ capture(k, h[k]); }); }
    } catch (e) {}
  }
  window.__wmsCall = function(id, method, url, body){
    try {
      var x = new XMLHttpRequest();
      x.open(method, url, true);
      x.setRequestHeader('Content-Type', 'application/json');
      x.setRequestHeader('Accept', 'application/json, text/plain, */*');
      for (var k in cap) { x.setRequestHeader(k, cap[k]); }
      x.onload = function(){ window.WmsBridge.result(id, x.status, x.responseText || ''); };
      x.onerror = function(){ window.WmsBridge.result(id, 0, ''); };
      x.send(body);
    } catch (e) { window.WmsBridge.result(id, -1, String(e)); }
  };
  var sens = /login|logon|auth|token|password|signin|session/i;
  var noise = /GetStatisticsUpdate|GetDashboardMessage|GetSystemMessage|\.(js|css|png|jpg|gif|svg|ico|woff2?|ttf|map)(\?|$)|coglas-cloud\.com/i;
  function clip(s, n){ if (s == null) return ''; s = String(s).replace(/\s+/g,' '); return s.length > n ? s.slice(0, n) + '...' : s; }
  function mask(u){ return String(u).replace(/(access_token|token|password|secret|api_key)=[^&]*/ig, function(m, a){ return a + '=***'; }); }
  function hn(h){
    try {
      if (!h) return '';
      if (Array.isArray(h)) return h.map(function(p){ return p[0]; }).join(',');
      if (typeof h.forEach === 'function') { var a = []; h.forEach(function(v, k){ a.push(k); }); return a.join(','); }
      return Object.keys(h).join(',');
    } catch (e) { return ''; }
  }
  function send(method, url, status, req, res, hdrs){
    try {
      if (noise.test(url)) return;
      var hide = sens.test(url);
      var line = new Date().toTimeString().slice(0,8) + ' ' + method + ' ' + status + ' ' + mask(url)
        + (hdrs ? ' | HDR: ' + hdrs : '')
        + (req && !hide ? ' | REQ: ' + clip(req, 1500) : '')
        + (res && !hide ? ' | RES: ' + clip(res, 1500) : '');
      if (window.WmsBridge) window.WmsBridge.log(line);
    } catch (e) {}
  }
  var of = window.fetch;
  if (of) {
    window.fetch = function(input, init){
      var url = (typeof input === 'string') ? input : ((input && input.url) || '');
      var method = (init && init.method) || (input && input.method) || 'GET';
      var body = (init && typeof init.body === 'string') ? init.body : '';
      var hd = hn(init && init.headers);
      hc(init && init.headers);
      return of.apply(this, arguments).then(function(r){
        try {
          r.clone().text().then(function(t){ send(method, url, r.status, body, t, hd); })
            .catch(function(){ send(method, url, r.status, body, '', hd); });
        } catch (e) {}
        return r;
      });
    };
  }
  var xo = XMLHttpRequest.prototype.open, xs = XMLHttpRequest.prototype.send, xh = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open = function(m, u){ this.__m = m; this.__u = u; return xo.apply(this, arguments); };
  XMLHttpRequest.prototype.setRequestHeader = function(k, v){ (this.__h = this.__h || []).push(k); capture(k, v); return xh.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(b){
    var x = this; var body = (typeof b === 'string') ? b : '';
    x.addEventListener('loadend', function(){
      var t = '';
      try { if (x.responseType === '' || x.responseType === 'text') t = x.responseText; } catch (e) {}
      send(x.__m || 'GET', x.__u || '', x.status, body, t, (x.__h || []).join(','));
    });
    return xs.apply(this, arguments);
  };

  // ---------- сканер: камера сайта заменяется сканером приложения ----------
  // Сайт просит камеру (getUserMedia) -> открывается сканер приложения (ML Kit + ИИ), а сайту отдаётся «пустой» поток.
  // Прочитанный код приложение подставляет в поле страницы через __cgScanResult.
  try {
    var md = navigator.mediaDevices;
    if (md && md.getUserMedia && !md.__cgPatched) {
      md.__cgPatched = true;
      var ogum = md.getUserMedia.bind(md);
      var blank = function(){
        var c = document.createElement('canvas');
        c.width = 640; c.height = 480;
        var g = c.getContext('2d');
        g.fillStyle = '#000'; g.fillRect(0, 0, 640, 480);
        return c.captureStream ? c.captureStream(1) : null;
      };
      md.getUserMedia = function(cons){
        var useNative = false;
        try { useNative = !!(cons && cons.video && window.WmsBridge && window.WmsBridge.scanOn()); } catch (e) {}
        if (!useNative) return ogum(cons);
        var st = blank();
        if (!st) return ogum(cons);
        // открываем сканер только по нажатию человека, а не при фоновой проверке камеры
        var act = true;
        try { if (navigator.userActivation) act = navigator.userActivation.isActive; } catch (e) {}
        if (act) { try { window.WmsBridge.scan(); } catch (e) {} }
        return Promise.resolve(st);
      };
    }
  } catch (e) {}

  window.__cgScanResult = function(code){
    try {
      function okInput(e){
        if (!e || e.disabled || e.readOnly) return false;
        if (e.tagName === 'TEXTAREA') return true;
        return e.tagName === 'INPUT' && /^(text|search|number|tel|)$/i.test(e.type || '');
      }
      function visible(e){ try { return e && e.isConnected && e.getClientRects().length > 0; } catch (x) { return false; } }
      // подпись поля: текст ближайших родителей, пока он короткий (строка «Артикул: GTIN | содержит | [поле]»)
      function labelOf(e){
        try {
          var p = e, t = '';
          for (var i = 0; i < 4; i++) {
            p = p.parentElement;
            if (!p || p === document.body) break;
            var s = (p.innerText || '').replace(/\s+/g, ' ').trim();
            if (s.length > 100) break;
            t = s;
          }
          return t;
        } catch (x) { return ''; }
      }
      function attrs(e){ return (e.name || '') + ' ' + (e.id || '') + ' ' + (e.placeholder || '') + ' ' + (e.getAttribute('aria-label') || ''); }
      // строка поиска ВНУТРИ выпадающего списка (select2: «Артикул GTIN ▲» → «Совпадений не найдено») — не поле для кода
      function inDropdown(e){
        try { return !!e.closest('.select2-container,.select2-dropdown,.select2-search,[class*=select2],[class*=chosen],[role=listbox],.dropdown-menu'); } catch (x) { return false; }
      }
      function good(e){ return e && okInput(e) && visible(e) && !inDropdown(e); }
      var all = [].slice.call(document.querySelectorAll('input,textarea')).filter(good);
      var el = null;
      // 0) открыто окно «Infoscan» сайта: код сразу встаёт в строку этого окна (нажимать на поле не нужно)
      function isInfoscan(){
        try {
          var t = [].slice.call(document.querySelectorAll('.navbar-tab-item.active,h1,h2,h3,.page-header,.page-title,.panel-title,.navbar-mobile-title'))
            .filter(visible).map(function(e){ return e.innerText || ''; }).join(' ');
          return /infoscan|инфоскан/i.test(t);
        } catch (x) { return false; }
      }
      if (isInfoscan()) {
        var inPage = all.filter(function(e){ try { return !e.closest('header,.topbar,nav,.navbar'); } catch (x) { return true; } });
        var act0 = document.activeElement;
        el = (inPage.indexOf(act0) >= 0 ? act0 : null) || inPage.filter(function(e){ return !e.value; })[0] || inPage[0] || null;
      }
      // 1) поле, в котором стоит курсор (нажали на строку), или куда коснулись последним — код ставится туда
      var cur = document.activeElement;
      if (!el) {
        if (good(cur)) el = cur;
        else {
          var last = window.__wmsLast;
          if (good(last) && Date.now() - (window.__wmsLastAt || 0) < 90000) el = last;
        }
      }
      // 2) иначе поле в строке «Артикул: GTIN | содержит | [поле]» (пустое — раньше, иначе любое)
      if (!el) {
        var art = all.filter(function(e){ return /артикул|gtin|artikel|article/i.test(labelOf(e)) || /gtin|artikel|article/i.test(attrs(e)); });
        var row = art.filter(function(e){ return /gtin/i.test(labelOf(e)); });
        if (row.length) art = row;
        el = art.filter(function(e){ return !e.value; })[0] || art[0] || null;
      }
      // 3) нет такой строки в окне — строка поиска
      if (!el) {
        var srch = all.filter(function(e){ return e.type === 'search' || /поиск|search|suche/i.test(attrs(e)) || /^\s*(поиск|search|suche)\s*$/i.test(labelOf(e)); });
        el = srch[0] || null;
      }
      if (!el) el = all[0];
      if (!el) return 'noinput';
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, String(code));
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      ['keydown', 'keypress', 'keyup'].forEach(function(t){
        el.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
      });
      return 'ok';
    } catch (e) { return 'err:' + e; }
  };

  // ---------- тёмная тема сайта ----------
  // Вся страница переворачивается по яркости (белое -> тёмное, чёрный текст -> светлый) с сохранением оттенков (hue-rotate 180),
  // затем сразу же «возвращается» всё, что переворачивать нельзя: картинки, фото, видео, штрихкоды (canvas), фоны-картинки.
  // Синяя шапка сайта тоже возвращается как была (иначе белые значки в ней стали бы чёрными), а её выпадающие меню остаются тёмными.
  (function(){
    var ID = '__cgDarkCss';
    var F = 'invert(1) hue-rotate(180deg)';
    var CSS =
      'html{filter:' + F + ' contrast(.88) brightness(.95)!important;background:#fff!important;color-scheme:light!important;}' +
      'img,video,canvas,picture,iframe,embed,object,svg image,[style*="background-image"],[style*="url("],.profile-avatar,' +
      '.dashboard-logo-right-background,.wgt-welcome-img,.file-image img{filter:' + F + '!important;}' +
      // не переворачиваем дважды: картинка внутри уже возвращённого блока
      'img img,picture img,[style*="background-image"] img,[style*="url("] img,[style*="background-image"] [style*="background-image"]{filter:none!important;}' +
      // шапка сайта: как есть; её раскрывающиеся списки — снова тёмные
      '.topbar nav.navbar,.topbar .navbar{filter:' + F + '!important;}' +
      '.topbar .profile,.topbar .navbar-tab-unfit-list,.topbar .dropdown-menu{filter:' + F + '!important;}' +
      '::selection{background:#3b6fd6!important;color:#fff!important;}';
    function apply(on){
      try {
        var old = document.getElementById(ID);
        if (!on) { if (old) old.remove(); return; }
        if (old) return;
        var root = document.head || document.documentElement;
        if (!root) return;
        var st = document.createElement('style');
        st.id = ID;
        st.textContent = CSS;
        root.appendChild(st);
      } catch (e) {}
    }
    window.__cgSetDark = function(on){ window.__cgDark = !!on; apply(!!on); };
    var on = false;
    try { on = !!(window.WmsBridge && window.WmsBridge.darkOn && window.WmsBridge.darkOn()); } catch (e) {}
    window.__cgDark = on;
    apply(on);
    // сайт может пересобрать <head> — стиль возвращается
    var keep = function(){ if (window.__cgDark && !document.getElementById(ID)) apply(true); };
    try { new MutationObserver(keep).observe(document, { childList: true, subtree: true }); } catch (e) {}
    document.addEventListener('DOMContentLoaded', keep);
    window.addEventListener('load', keep);
  })();

  // ---------- вид ПК: сайт рисуется как в Chrome «Версия для ПК» ----------
  // Ширина 980 — как у Chrome в режиме «версия для ПК» (она же даёт на сайте тот же вид, что на скриншоте из Chrome).
  // Важно: у сайта своя meta viewport (width=device-width) приходит позже нашей и перебивает её, поэтому
  // правятся ВСЕ meta viewport, и по мере появления новых — тоже.
  try {
    var desk = false;
    try { desk = !!(window.WmsBridge && window.WmsBridge.desktopOn && window.WmsBridge.desktopOn()); } catch (e) {}
    if (desk) {
      var VW = 980, VC = 'width=' + VW;
      // сайт не должен считать устройство телефоном
      try {
        Object.defineProperty(navigator, 'platform', { get: function(){ return 'Linux x86_64'; } });
        Object.defineProperty(navigator, 'userAgentData', {
          get: function(){ return { mobile: false, platform: 'Linux', brands: [{ brand: 'Chromium', version: '120' }], getHighEntropyValues: function(){ return Promise.resolve({ mobile: false, platform: 'Linux' }); } }; }
        });
      } catch (e) {}
      var setVp = function(){
        try {
          var root = document.head || document.documentElement;
          if (!root) return;
          var all = document.querySelectorAll('meta[name=viewport]');
          if (!all.length) {
            var nm = document.createElement('meta');
            nm.setAttribute('name', 'viewport');
            nm.setAttribute('content', VC);
            root.appendChild(nm);
            return;
          }
          for (var i = 0; i < all.length; i++) {
            if (all[i].getAttribute('content') !== VC) all[i].setAttribute('content', VC);
          }
        } catch (e) {}
      };
      setVp();
      try {
        new MutationObserver(setVp).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
      } catch (e) {}
      document.addEventListener('DOMContentLoaded', setVp);
      window.addEventListener('load', setVp);
    }
  } catch (e) {}

  // ---------- меню System: открыть нужное окно сайта по названию ----------
  // de — немецкое название пункта, ru — русское. Сайт мог уже перевести пункт, поэтому ищем по всем вариантам.
  window.__cgOpenMenu = function(de, ru){
    var T0 = Date.now(), lastTry = 0, cleared = false;
    function nm(x){ return String(x || '').replace(/[\u00AD\u200B]/g, '').toLowerCase().replace(/[\s\-,.&]+/g, ''); }
    var want = {};
    [de, ru, (window.__cgMap || {})[String(de).toLowerCase()]].forEach(function(x){ if (x) want[nm(x)] = 1; });
    function links(){ return [].slice.call(document.querySelectorAll('a.dashboard-menu-link')); }
    function textOf(a){ var t = a.querySelector('.dashboard-menu-title,h5'); return (t || a).textContent; }
    function onMap(){ return !!document.getElementById('searchDashboard') && links().length >= 20; }
    function find(){ var l = links(); for (var i = 0; i < l.length; i++) { if (want[nm(textOf(l[i]))]) return l[i]; } return null; }
    function report(m){ try { window.WmsBridge.menuResult(m); } catch (e) {} }
    function openSystem(){
      var c = [].slice.call(document.querySelectorAll('a.profile-link,a.navbar-tab-link,.topbar-menu a,nav a'));
      for (var i = 0; i < c.length; i++) {
        var t = nm(c[i].textContent);
        if (t === 'system' || t === 'система') { c[i].click(); return; }
      }
      var dd = document.getElementById('dropdownProfile');
      if (dd) dd.click();
    }
    (function step(){
      if (Date.now() - T0 > 9000) { report('Не удалось открыть «' + ru + '». Откройте System на сайте вручную.'); return; }
      if (onMap()) {
        var a = find();
        if (a) { a.click(); report('ok'); return; }
        var f = document.getElementById('searchDashboard');
        if (f && f.value && !cleared) {
          cleared = true;
          Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(f, '');
          f.dispatchEvent(new Event('input', { bubbles: true }));
          setTimeout(step, 500);
          return;
        }
        report('Пункта «' + ru + '» нет в меню System (нет прав или другое название).');
        return;
      }
      if (Date.now() - lastTry > 1500) { lastTry = Date.now(); openSystem(); }
      setTimeout(step, 300);
    })();
    return 'started';
  };

  // ---------- меню System: прочитать все пункты (для сканера «данные сайта → меню App») ----------
  // Результат уходит в WmsBridge.menuList(json): {items:[{text,href,group}]} или {err:"..."}
  window.__cgListMenu = function(){
    var T0 = Date.now(), lastTry = 0;
    function nm(x){ return String(x || '').replace(/[\u00AD\u200B]/g, '').toLowerCase().replace(/[\s\-,.&]+/g, ''); }
    function links(){ return [].slice.call(document.querySelectorAll('a.dashboard-menu-link')); }
    function textOf(a){ var t = a.querySelector('.dashboard-menu-title,h5'); return ((t || a).textContent || '').replace(/\s+/g, ' ').trim(); }
    function onMap(){ return !!document.getElementById('searchDashboard') && links().length >= 20; }
    function send(o){ try { window.WmsBridge.menuList(JSON.stringify(o)); } catch (e) {} }
    function openSystem(){
      var c = [].slice.call(document.querySelectorAll('a.profile-link,a.navbar-tab-link,.topbar-menu a,nav a'));
      for (var i = 0; i < c.length; i++) {
        var t = nm(c[i].textContent);
        if (t === 'system' || t === 'система') { c[i].click(); return; }
      }
      var dd = document.getElementById('dropdownProfile');
      if (dd) dd.click();
    }
    // заголовок группы: ближайший предыдущий заголовок рядом со ссылкой (если на сайте группы подписаны)
    function groupOf(a){
      var p = a, up = 0;
      while (p && p !== document.body && up < 5) {
        var sib = p.previousElementSibling, hops = 0;
        while (sib && hops < 40) {
          if (!sib.querySelector('a.dashboard-menu-link') && !(sib.matches && sib.matches('a.dashboard-menu-link'))) {
            var tg = (sib.tagName || '');
            var cl = String(sib.className || '');
            if (/^H[1-6]$/.test(tg) || /group|section|categor|header|heading/i.test(cl)) {
              var t = (sib.textContent || '').replace(/\s+/g, ' ').trim();
              if (t && t.length <= 60) return t;
            }
          }
          sib = sib.previousElementSibling; hops++;
        }
        p = p.parentElement; up++;
      }
      return '';
    }
    (function step(){
      if (Date.now() - T0 > 9000) { send({ err: 'Меню System не открылось. Войдите на сайте и откройте System вручную.' }); return; }
      if (onMap()) {
        var seen = {}, out = [];
        links().forEach(function(a){
          var t = textOf(a);
          if (!t || seen[nm(t)]) return;
          seen[nm(t)] = 1;
          out.push({ text: t, href: a.getAttribute('href') || '', group: groupOf(a) });
        });
        send({ items: out });
        return;
      }
      if (Date.now() - lastTry > 1500) { lastTry = Date.now(); openSystem(); }
      setTimeout(step, 300);
    })();
    return 'started';
  };

  // ---------- смена страницы сайта (адрес не меняется, меняется только заголовок) ----------
  try {
    var lastHead = null;
    setInterval(function(){
      try {
        var h = document.querySelector('.page-heading-title,.navbar-mobile-title');
        var t = h ? (h.textContent || '').trim() : '';
        if (t !== lastHead) { lastHead = t; window.WmsBridge.pageChanged(t); }
      } catch (e) {}
    }, 1200);
  } catch (e) {}
})();
