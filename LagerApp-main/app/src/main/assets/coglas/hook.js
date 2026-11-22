(function(){
  if (window.__wmsHook) return;
  window.__wmsHook = true;
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
      var el = document.activeElement;
      if (!okInput(el)) {
        var all = [].slice.call(document.querySelectorAll('input,textarea')).filter(function(e){ return okInput(e) && e.offsetParent !== null; });
        var pri = all.filter(function(e){
          var h = (e.name || '') + ' ' + (e.id || '') + ' ' + (e.placeholder || '') + ' ' + (e.getAttribute('aria-label') || '');
          return /barcode|scan|ean|gtin|code|штрих|скан|код|lager|le\b/i.test(h);
        });
        el = pri[0] || all[0];
      }
      if (!el) return 'noinput';
      el.focus();
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
