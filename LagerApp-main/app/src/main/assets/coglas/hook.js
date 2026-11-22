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
})();
