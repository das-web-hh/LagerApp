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
        if (window.__cgSimScan) act = true; // нажатие имитировало приложение по кнопке сканера
        if (act) { try { window.WmsBridge.scan(); } catch (e) {} }
        return Promise.resolve(st);
      };
    }
  } catch (e) {}

  // ---------- сканер в заказе: код -> фильтр «Артикул GTIN» -> галочка у найденной позиции ----------
  (function(){
    var q = [], busy = false;
    function sleep(ms){ return new Promise(function(r){ setTimeout(r, ms); }); }
    function vis(e){ try { return !!e && e.isConnected && e.getClientRects().length > 0; } catch (x) { return false; } }
    function txt(e){ return ((e && (e.innerText || e.textContent)) || '').replace(/\s+/g, ' ').trim(); }
    function toast(m){ try { window.WmsBridge.toast(m); } catch (x) {} }
    function waitFor(fn, ms, step){
      var t0 = Date.now();
      return new Promise(function(res){
        (function tick(){
          var v = null;
          try { v = fn(); } catch (x) {}
          if (v) return res(v);
          if (Date.now() - t0 >= ms) return res(null);
          setTimeout(tick, step || 100);
        })();
      });
    }
    function fire(el, types){
      types.forEach(function(t){
        try {
          var ev = /^pointer/.test(t)
            ? new PointerEvent(t, { bubbles: true, cancelable: true, pointerType: 'touch', isPrimary: true, view: window })
            : new MouseEvent(t, { bubbles: true, cancelable: true, view: window, button: 0 });
          el.dispatchEvent(ev);
        } catch (x) {}
      });
    }
    function tap(el){ fire(el, ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click']); }
    function inDrop(e){ try { return !!e.closest('.select2-container,.select2-dropdown,.select2-search,[class*=select2],[class*=chosen],[role=listbox],.dropdown-menu,.mat-select-panel,.cdk-overlay-pane'); } catch (x) { return false; } }
    function labelOf(e){
      var p = e, t = '';
      for (var i = 0; i < 4; i++) {
        p = p.parentElement;
        if (!p || p === document.body) break;
        var s = txt(p);
        if (s.length > 100) break;
        t = s;
      }
      return t;
    }
    function setVal(el, v){
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      try { el.dispatchEvent(new KeyboardEvent('keyup', { key: v ? v.slice(-1) : 'Backspace', bubbles: true })); } catch (x) {}
    }
    function pressEnter(el){
      ['keydown', 'keypress', 'keyup'].forEach(function(t){
        try { el.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true })); } catch (x) {}
      });
    }

    // окно заказа с позициями: есть расширенный поиск, «Выбрать все на текущей странице» и заголовок заказа
    window.__cgIsOrder = function(){
      // на странице уже есть строка «Артикул: GTIN» — код идёт именно в неё
      try { if (gtinInput()) return true; } catch (x) {}
      var t = (document.body && document.body.innerText) || '';
      return /Расширенный поиск|Erweiterte Suche|Advanced search/i.test(t) &&
        /(Выбрать все|Снять выбор со всех)[^\n]{0,40}страниц|Alle auf der aktuellen|Auswahl[^\n]{0,30}aufheben|Select all|Deselect all/i.test(t) &&
        /Заголовок|Kopf|Header/i.test(t);
    };

    function gtinInput(){
      function textInput(e){
        try { return !e.disabled && vis(e) && !inDrop(e) && /^(text|search|tel|number|)$/i.test(e.type || ''); } catch (x) { return false; }
      }
      var inputs = [].slice.call(document.querySelectorAll('input,textarea')).filter(textInput);
      if (!inputs.length) return null;
      // 1) по подписи строки: надпись «Артикул: GTIN» (короткий текст), поле — в той же строке справа от неё
      var found = [];
      try {
        var tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null), tn;
        while ((tn = tw.nextNode())) {
          var tv = (tn.nodeValue || '').replace(/\s+/g, ' ').trim();
          if (!tv || tv.length > 40 || !/gtin/i.test(tv)) continue;
          var pe = tn.parentElement;
          if (!pe || !vis(pe) || inDrop(pe) || pe.closest('select,option,button')) continue;
          var lr = pe.getBoundingClientRect();
          var lc = lr.top + lr.height / 2;
          var best = null, bd = 1e9;
          inputs.forEach(function(e){
            var r = e.getBoundingClientRect();
            var ec = r.top + r.height / 2;
            if (Math.abs(ec - lc) > Math.max(r.height, lr.height) * 0.9) return;
            if (r.left < lr.right - 6) return;
            var d = r.left - lr.right;
            if (d < bd) { bd = d; best = e; }
          });
          if (best) found.push(best);
        }
      } catch (x) {}
      if (found.length) return found.filter(function(e){ return !e.readOnly; })[0] || found[0];
      // 2) запасной путь: подпись в ближайших родителях
      var list = inputs.filter(function(e){ return /gtin/i.test(labelOf(e)); });
      return list.filter(function(e){ return !e.readOnly; })[0] || list[0] || null;
    }
    function total(){
      var t = (document.body && document.body.innerText) || '';
      var m = t.match(/(\d+)\s*(?:von|из|of)\s*(\d+)\s*(?:Ergebnis|результат|result)/i);
      if (m) return parseInt(m[2], 10);
      if (/(Ergebnisse|результат\S*|results?)\s*:?\s*0\b|нет\s+результат|keine\s+ergebnis/i.test(t)) return 0;
      return null;
    }

    // открыть фильтр «Артикул GTIN» (выпадающий список рядом с кнопками +, карандаш, корзина) как человек
    async function openFilter(){
      var boxes = [].slice.call(document.querySelectorAll('.select2-container,[class*=select2-container],mat-select,.mat-select'))
        .filter(function(c){ return vis(c); });
      var cand = boxes.filter(function(c){ return /gtin/i.test(txt(c)); })[0] || boxes[0];
      if (!cand) return false;
      var trg = cand.querySelector('.select2-selection,.select2-choice,.select2-selection--single,.mat-select-trigger') || cand;
      var OPT = '.select2-results__option,.select2-result,.select2-result-label,[role=option],mat-option,.dropdown-menu li';
      function gtinOpt(){
        return [].slice.call(document.querySelectorAll(OPT)).filter(function(e){ return vis(e) && txt(e).length < 60 && /gtin/i.test(txt(e)); })[0] || null;
      }
      function anyList(){
        return [].slice.call(document.querySelectorAll('.select2-results,.select2-drop,.select2-dropdown,[role=listbox],.mat-select-panel')).some(vis);
      }
      tap(trg);
      // список открылся, а пункта GTIN в нём нет (например, в «Накладных»): закрываем и сдаёмся быстро
      var opt = await waitFor(function(){ var o = gtinOpt(); if (o) return o; return null; }, 1200, 100);
      if (!opt) {
        try {
          var act = document.activeElement || document;
          ['keydown', 'keyup'].forEach(function(t){ try { act.dispatchEvent(new KeyboardEvent(t, { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true })); } catch (x) {} });
          if (anyList()) fire(document.body, ['mousedown', 'mouseup']);
        } catch (x) {}
        await sleep(150);
        return false;
      }
      tap(opt);
      try { opt.click(); } catch (x) {}
      return true;
    }

    // строка поиска окна (если фильтра «Артикул GTIN» на странице нет)
    function searchInput(){
      var list = [].slice.call(document.querySelectorAll('input,textarea')).filter(function(e){
        try {
          if (e.disabled || !vis(e) || inDrop(e)) return false;
          if (!/^(text|search|)$/i.test(e.type || '')) return false;
          return /^(поиск|suche|search)/i.test((e.placeholder || '').trim()) || /^(поиск|suche|search)/i.test((e.getAttribute('aria-label') || '').trim());
        } catch (x) { return false; }
      });
      return list[0] || null;
    }

    function rowChecks(){
      var anchorY = 0;
      var els = [].slice.call(document.querySelectorAll('button,[role=button],a,div,span'));
      for (var i = 0; i < els.length; i++) {
        var e = els[i];
        if (!vis(e)) continue;
        var s = txt(e);
        if (s.length < 70 && /(выбрать все|снять выбор со всех|alle\s+(ab|aus)wählen|auswahl.*aufheben|select all|deselect all)/i.test(s) && e.tagName !== 'LABEL') {
          anchorY = Math.max(anchorY, e.getBoundingClientRect().bottom + window.scrollY);
        }
      }
      var cbs = [].slice.call(document.querySelectorAll('input[type=checkbox],[role=checkbox],mat-checkbox'));
      var ok = cbs.filter(function(c){
        var host = vis(c) ? c : (c.closest('label') || c.parentElement);
        if (!vis(host)) return false;
        if (/всегда выбирать|immer.*(auswählen|wählen)|always select|деактивирован|deaktiviert|требуется проверка|prüfung/i.test(labelOf(c) + ' ' + txt(host))) return false;
        return host.getBoundingClientRect().top + window.scrollY > anchorY;
      });
      ok.sort(function(a, b){
        var ha = vis(a) ? a : (a.closest('label') || a.parentElement), hb = vis(b) ? b : (b.closest('label') || b.parentElement);
        return ha.getBoundingClientRect().top - hb.getBoundingClientRect().top;
      });
      return ok;
    }
    function rowCheck(){ return rowChecks()[0] || null; }
    function isChecked(c){
      if (c.tagName === 'MAT-CHECKBOX') { var i = c.querySelector('input'); return !!(i && i.checked); }
      return c.checked === true || c.getAttribute('aria-checked') === 'true';
    }

    // одно нажатие на строку, как пальцем: сайт делает поле активным (без этого в него нельзя писать)
    async function tapField(el){
      window.__wmsAllowFocus = true;
      try { el.scrollIntoView({ block: 'nearest' }); } catch (x) {}
      fire(el, ['pointerdown', 'mousedown']);
      try { el.focus(); } catch (x) {}
      fire(el, ['pointerup', 'mouseup', 'click']);
      await sleep(200);
    }
    // кнопка «очистить» (ластик) в строке поля: появляется, только когда в поле что-то написано
    function clearBtn(el){
      try {
        var er = el.getBoundingClientRect(), cy = er.top + er.height / 2;
        var p = el.parentElement;
        for (var i = 0; i < 5 && p && p !== document.body; i++, p = p.parentElement) {
          var bs = [].slice.call(p.querySelectorAll('button,[role=button],a,.btn')).filter(function(b){
            if (!vis(b) || b === el || b.contains(el)) return false;
            var r = b.getBoundingClientRect();
            return r.left >= er.right - 4 && Math.abs((r.top + r.height / 2) - cy) < Math.max(er.height, 40);
          });
          if (!bs.length) continue;
          var named = bs.filter(function(b){
            var sg = (b.className && b.className.baseVal !== undefined ? b.className.baseVal : (b.className || '')) + ' ' + (b.innerHTML || '').slice(0, 400) + ' ' + (b.getAttribute('title') || '') + ' ' + (b.getAttribute('aria-label') || '');
            return /eraser|clear|reset|trash-?can|löschen|loeschen|очист|сброс/i.test(sg);
          });
          if (named.length) return named[named.length - 1];
          if (bs.length >= 2) { bs.sort(function(a, b){ return b.getBoundingClientRect().left - a.getBoundingClientRect().left; }); return bs[0]; }
        }
      } catch (x) {}
      return null;
    }
    // вставка кода одним действием, как вставка из буфера: сайт ищет один раз по полному коду, а не по каждой цифре
    async function typeInto(el, code){
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var set = Object.getOwnPropertyDescriptor(proto, 'value').set;
      set.call(el, '');
      set.call(el, code);
      try { el.dispatchEvent(new InputEvent('input', { bubbles: true, data: code, inputType: 'insertFromPaste' })); } catch (x) { el.dispatchEvent(new Event('input', { bubbles: true })); }
      el.dispatchEvent(new Event('change', { bubbles: true }));
    }

    async function run(code){
      window.__wmsAllowFocus = false;
      var inp = gtinInput();
      if (!inp) {
        var opened = await openFilter();
        if (opened) inp = await waitFor(gtinInput, 3000, 100);
      }
      // фильтра «Артикул GTIN» на странице нет (например, окно «Накладные»): работаем через строку поиска
      if (!inp) inp = searchInput();
      if (!inp) { toast('Не нашёл ни фильтр «Артикул GTIN», ни строку поиска.'); return false; }

      // очистить прошлый код: нажимаем кнопку «очистить» рядом со строкой (она есть, только когда в поле что-то написано);
      // сайт покажет весь список (с отмеченными и неотмеченными)
      if (inp.value) {
        var before = total();
        var cb0 = clearBtn(inp);
        if (cb0) { try { cb0.click(); } catch (x) { tap(cb0); } await sleep(250); }
        if (inp.value) { setVal(inp, ''); pressEnter(inp); }
        await waitFor(function(){ var t = total(); return t !== null && t !== before; }, 2500, 100);
        await sleep(300);
        inp = gtinInput() || searchInput() || inp;
      }
      var base = total();
      // одно нажатие на строку, потом набор кода
      await tapField(inp);
      await typeInto(inp, String(code));
      pressEnter(inp);
      try { inp.blur(); } catch (x) {}
      window.__wmsAllowFocus = false;
      // сайт ищет сам. Галочку ставим только когда список СТАЛ из одной позиции и так стоит без изменений (не раньше, чем сайт нашёл товар)
      var t0 = Date.now(), last = '', since = Date.now(), cb = null, verdict = '';
      while (Date.now() - t0 < 10000) {
        await sleep(120);
        var n = total();
        var cbs = rowChecks();
        var sig = n + '|' + cbs.length;
        if (sig !== last) { last = sig; since = Date.now(); continue; }
        if (Date.now() - since < 900) continue;       // список ещё меняется
        if (n === null) continue;
        if (n === base && base !== 1) continue;        // список так и не отфильтровался
        if (n === 1 && cbs.length >= 1) { cb = cbs[0]; verdict = 'one'; break; }
        if (n === 0) { verdict = 'zero'; break; }
        if (n > 1) { verdict = 'many'; break; }
      }
      if (verdict === 'zero') { toast('Товар с кодом ' + code + ' не найден'); return false; }
      if (verdict === 'many') { toast('По коду ' + code + ' найдено позиций: ' + total() + '. Галочка не поставлена.'); return false; }
      if (!cb) { toast('Сайт не успел найти товар по коду ' + code + '. Галочка не поставлена.'); return false; }
      try { (vis(cb) ? cb : (cb.closest('label') || cb)).scrollIntoView({ block: 'nearest' }); } catch (x) {}
      if (!isChecked(cb)) {
        var host = vis(cb) ? cb : (cb.closest('label') || cb.parentElement);
        try { host.click(); } catch (x) { tap(host); }
        await sleep(350);
        if (!isChecked(cb)) { tap(host); await sleep(350); }
      }
      // товар найден и отмечен: приложение вибрирует и показывает большую галочку
      if (isChecked(cb)) {
        var ft = '';
        try {
          var anc = cb;
          for (var q = 0; q < 8 && anc && anc !== document.body; q++, anc = anc.parentElement) {
            var mm = /№:?\s*([A-Za-z0-9._\-]+)\s*,?\s*([^\n]*)/.exec(anc.innerText || '');
            if (mm) { ft = mm[1] + (mm[2] ? ', ' + mm[2].trim().slice(0, 60) : ''); break; }
          }
        } catch (x) {}
        try { if (window.WmsBridge.foundText) window.WmsBridge.foundText(ft || String(code)); else window.WmsBridge.found(); } catch (x) {}
      }
      else toast('Не удалось поставить галочку у найденного товара (код ' + code + ').');
      return true;
    }

    // ---- поиск по просьбе приложения (вкладка App): текст вводится в строку поиска сайта, ответ сайта возвращается приложению
    var siteSearching = false;
    window.__cgOnPage = function(de, ru){
      try {
        function low(x){ return (x || '').replace(/\s+/g, ' ').trim().toLowerCase(); }
        var names = [];
        [].slice.call(document.querySelectorAll('.navbar-tab-item.active .navbar-tab-link-text,.navbar-tab-item.active,.page-heading-title,.navbar-mobile-title')).forEach(function(e){ names.push(low(e.textContent)); });
        de = low(de); ru = low(ru);
        var hit = names.some(function(n){ return n && ((de && n.indexOf(de) >= 0) || (ru && n.indexOf(ru) >= 0)); });
        return hit && !!searchInput() ? 'yes' : 'no';
      } catch (x) { return 'no'; }
    };
    window.__cgSiteSearch = function(id, text){
      if (siteSearching) return 'busy';
      siteSearching = true;
      (async function(){
        function done(ok, resp, body, err){
          siteSearching = false; window.__cgCap = null; autoBusy(false);
          try { window.WmsBridge.searchDone(id, !!ok, resp || '', body || '', err || ''); } catch (x) {}
        }
        autoBusy(true);
        try {
          var inp = searchInput();
          if (!inp) return done(false, '', '', 'На странице сайта нет строки поиска');
          text = String(text || '');
          if (!text) window.__cgCap = { since: Date.now(), res: null };
          if (inp.value) {
            var cb0 = clearBtn(inp);
            if (cb0) { try { cb0.click(); } catch (x) { tap(cb0); } await sleep(300); }
            if (inp.value) { setVal(inp, ''); pressEnter(inp); }
            await sleep(text ? 900 : 200);
          }
          if (text) {
            window.__cgCap = { since: Date.now(), res: null };
            await tapField(inp);
            await typeInto(inp, text);
            pressEnter(inp);
            try { inp.blur(); } catch (x) {}
            window.__wmsAllowFocus = false;
          }
          // ждём ответ сайта на поиск; берём последний, когда новых ответов больше нет
          var t0 = Date.now(), got = null;
          while (Date.now() - t0 < 14000) {
            await sleep(150);
            var c = window.__cgCap && window.__cgCap.res;
            if (c && Date.now() - c.t > 600) { got = c; break; }
          }
          if (!got) return done(false, '', '', 'Сайт не прислал результат поиска');
          return done(true, got.resp, got.body, '');
        } catch (e) { return done(false, '', '', 'Ошибка поиска: ' + e); }
      })();
      return 'started';
    };

    function autoBusy(on){ try { window.WmsBridge.busy('auto', on); } catch (x) {} }
    async function pump(){
      if (busy) return;
      busy = true;
      autoBusy(true);
      try {
        while (q.length) {
          var c = q.shift();
          try { await run(c); } catch (e) { toast('Ошибка сканера GTIN: ' + e); }
        }
      } finally { busy = false; autoBusy(false); }
    }
    window.__cgGtinFlow = function(code){ q.push(code); pump(); };
  })();


  // ---------- кнопка сканера приложения: нажать значок сканера на странице сайта, как человек ----------
  // Возвращает 'clicked', если на странице нашёлся значок сканера (рядом с надписью «Готово к сканированию» и т.п.) и он нажат.
  window.__cgClickSiteScan = function(){
    try {
      function vis(e){ try { return !!e && e.isConnected && e.getClientRects().length > 0; } catch (x) { return false; } }
      function txt(e){ return ((e && (e.innerText || e.textContent)) || '').replace(/\s+/g, ' ').trim(); }
      var RX = /готов\S*\s+к\s+сканир|bereit\s+(zum|zur|für|fuer)\s+scan|ready\s+(to|for)\s+scan|отсканируйте\s+штрих|штрих-?код\S*\s+отсканир|barcode\s+(bitte\s+)?scann|bitte\S*\s.{0,25}scann|please\s+scan|можете\s+сканир|sie\s+können\s+scann|you\s+can\s+scan/i;
      var RX_KB = /keyboard|tastatur|клавиатур|keypad/i;
      var RX_SC = /scan|barcode|camera|kamera|qr|штрих|скан|камер/i;
      var CLICKABLE = 'button,[role=button],a,mat-icon,i,svg,img,span[class*=icon],div[class*=icon],[class*=btn],[class*=button]';
      function sig(e){
        try { return (e.id || '') + ' ' + (typeof e.className === 'string' ? e.className : (e.className && e.className.baseVal) || '') + ' ' + (e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('title') || '') + ' ' + (e.getAttribute('alt') || '') + ' ' + (e.getAttribute('data-icon') || '') + ' ' + (e.tagName === 'MAT-ICON' ? txt(e) : ''); } catch (x) { return ''; }
      }
      function inBar(e){ try { return !!e.closest('#__cgScanBtn,header,nav,.navbar,.topbar,.navbar-mobile'); } catch (x) { return false; } }
      function target(e){
        var t = e.closest ? (e.closest('button,[role=button],a') || e) : e;
        return t;
      }
      // 1) контейнер рядом с надписью о сканировании
      var anchors = [];
      [].slice.call(document.querySelectorAll('input,textarea')).forEach(function(e){
        try { if (vis(e) && RX.test((e.placeholder || '') + ' ' + (e.getAttribute('aria-label') || '') + ' ' + (e.value || ''))) anchors.push(e); } catch (x) {}
      });
      var tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null), tn;
      while ((tn = tw.nextNode())) {
        var tv = tn.nodeValue || '';
        if (tv.length < 120 && RX.test(tv) && tn.parentElement && vis(tn.parentElement)) anchors.push(tn.parentElement);
      }
      var pick = null;
      for (var ai = 0; ai < anchors.length && !pick; ai++) {
        var p = anchors[ai];
        for (var i = 0; i < 5 && p && p !== document.body && !pick; i++) {
          var c = [].slice.call(p.querySelectorAll(CLICKABLE)).filter(function(e){ return vis(e) && !inBar(e) && !RX_KB.test(sig(e)); });
          if (c.length) {
            // значок сканера: по имени, иначе самый правый (клавиатура слева, сканер справа)
            var named = c.filter(function(e){ return RX_SC.test(sig(e)); });
            if (named.length) pick = named[named.length - 1];
            else {
              c.sort(function(a, b){ return b.getBoundingClientRect().left - a.getBoundingClientRect().left; });
              pick = c[0];
            }
          }
          p = p.parentElement;
        }
      }
      // 2) без надписи: значок/кнопка сканера в самой странице (не в верхней панели)
      if (!pick) {
        var all = [].slice.call(document.querySelectorAll(CLICKABLE)).filter(function(e){ return vis(e) && !inBar(e) && RX_SC.test(sig(e)) && !RX_KB.test(sig(e)); });
        if (all.length === 1) pick = all[0];
      }
      if (!pick) return 'none';
      var el = target(pick);
      window.__cgSimScan = true;
      setTimeout(function(){ window.__cgSimScan = false; }, 2500);
      ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function(t){
        try {
          el.dispatchEvent(/^pointer/.test(t)
            ? new PointerEvent(t, { bubbles: true, cancelable: true, pointerType: 'touch', isPrimary: true, view: window })
            : new MouseEvent(t, { bubbles: true, cancelable: true, view: window, button: 0 }));
        } catch (x) {}
      });
      try { el.click(); } catch (x) { el.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window })); }
      return 'clicked';
    } catch (e) { return 'err:' + e; }
  };

  window.__cgScanResult = function(code){
    try {
      if (window.WmsBridge && window.WmsBridge.gtinOn && window.WmsBridge.gtinOn() && window.__cgIsOrder && window.__cgIsOrder()) {
        window.__cgGtinFlow(code);
        return 'ok';
      }
    } catch (e) {}
    try {
      function okInput(e){
        if (!e || e.disabled || e.readOnly) return false;
        if (e.tagName === 'TEXTAREA') return true;
        return e.tagName === 'INPUT' && /^(text|search|number|tel|)$/i.test(e.type || '');
      }
      function visible(e){ try { return e && e.isConnected && e.getClientRects().length > 0; } catch (x) { return false; } }
      function txt(e){ return ((e && (e.innerText || e.textContent)) || '').replace(/\s+/g, ' ').trim(); }
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
      var scanField = false;
      // 00) поле для сканера на сайте («Готово к сканированию» / «Bereit zum Scannen»): код сразу встаёт в него.
      // Такое поле часто только для чтения или с выключенной клавиатурой, поэтому ищем его отдельно от обычных полей.
      var RX_READY = /готов\S*\s+к\s+сканир|bereit\s+(zum|zur|für|fuer)\s+scan|ready\s+(to|for)\s+scan|отсканируйте\s+штрих|штрих-?код\S*\s+отсканир|barcode\s+(bitte\s+)?scann|bitte\S*\s.{0,25}scann|please\s+scan|можете\s+сканир|sie\s+können\s+scann|you\s+can\s+scan/i;
      var wedge = false;
      function textOk(e){ try { return !!e && RX_READY.test(txt0(e)); } catch (x) { return false; } }
      function txt0(e){ return ((e.placeholder || '') + ' ' + (e.getAttribute && (e.getAttribute('aria-label') || e.getAttribute('title') || '')) + ' ' + (e.value || '')); }
      function fieldOk(e){
        try {
          if (!e || e.disabled || inDropdown(e)) return false;
          if (e.isContentEditable && e.tagName !== 'INPUT') return true;
          return (e.tagName === 'TEXTAREA') || (e.tagName === 'INPUT' && /^(text|search|tel|number|)$/i.test(e.type || ''));
        } catch (x) { return false; }
      }
      // поле ищем рядом с надписью: поднимаемся вверх по родителям; поле может быть скрыто или «только чтение» — это нормально для сканера-«клавиатуры»
      function fieldNear(node){
        var p = node;
        for (var i = 0; i < 6 && p && p !== document.body; i++) {
          var c = [].slice.call(p.querySelectorAll('input,textarea,[contenteditable=""],[contenteditable=true]')).filter(fieldOk);
          if (c.length) return c.filter(visible)[0] || c[0];
          p = p.parentElement;
        }
        return null;
      }
      var anchors = [];
      try {
        [].slice.call(document.querySelectorAll('input,textarea')).forEach(function(e){ if (visible(e) && textOk(e)) anchors.push(e); });
        var tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
        var tn;
        while ((tn = tw.nextNode())) {
          var tv = tn.nodeValue || '';
          if (tv.length < 120 && RX_READY.test(tv) && tn.parentElement && visible(tn.parentElement)) anchors.push(tn.parentElement);
        }
      } catch (x) {}
      for (var ai = 0; ai < anchors.length && !el; ai++) {
        var an = anchors[ai];
        var f = fieldOk(an) ? an : fieldNear(an);
        if (f) { el = f; scanField = true; }
      }
      // надпись про сканирование есть, а поля нет: отдаём код «нажатиями клавиш» активному элементу страницы
      if (!el && anchors.length) wedge = true;
      // 0) открыто окно «Infoscan» сайта: код сразу встаёт в строку этого окна (нажимать на поле не нужно)
      function isInfoscan(){
        try {
          var t = [].slice.call(document.querySelectorAll('.navbar-tab-item.active,h1,h2,h3,.page-header,.page-title,.page-heading-title,.panel-title,.navbar-mobile-title'))
            .filter(visible).map(function(e){ return e.innerText || ''; }).join(' ');
          return /infoscan|инфоскан/i.test(t);
        } catch (x) { return false; }
      }
      if (!el && isInfoscan()) {
        // в окне Infoscan подходит и поле только для чтения
        var inPage = [].slice.call(document.querySelectorAll('input,textarea')).filter(function(e){
          try { return !e.disabled && visible(e) && !inDropdown(e) && /^(text|search|tel|number|)$/i.test(e.type || '') && !e.closest('header,.topbar,nav,.navbar'); } catch (x) { return false; }
        });
        var act0 = document.activeElement;
        el = (inPage.indexOf(act0) >= 0 ? act0 : null) || inPage.filter(function(e){ return !e.value; })[0] || inPage[0] || null;
        if (el) scanField = true;
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
      if (!el && wedge) {
        var tgt = document.activeElement && document.activeElement !== document.body ? document.activeElement : document.body;
        window.__wmsAllowFocus = true;
        String(code).split('').concat(['Enter']).forEach(function(ch){
          var en = ch === 'Enter';
          var kc = en ? 13 : ch.charCodeAt(0);
          [tgt, document].forEach(function(t){
            ['keydown', 'keypress', 'keyup'].forEach(function(ty){
              try { t.dispatchEvent(new KeyboardEvent(ty, { key: ch, code: en ? 'Enter' : '', keyCode: kc, which: kc, bubbles: true, cancelable: true })); } catch (x) {}
            });
          });
        });
        setTimeout(function(){ window.__wmsAllowFocus = false; }, 700);
        return 'ok';
      }
      if (!el) el = all[0];
      if (!el) return 'noinput';
      var isEd = el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA';
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var setter = isEd ? function(v){ this.textContent = v; } : Object.getOwnPropertyDescriptor(proto, 'value').set;
      var codeS = String(code);
      if (scanField) {
        // поле сканера: сначала как будто «набрали» код клавишами сканера, потом значение и Enter —
        // так принимает и поле, которое читает нажатия клавиш, и поле, которое читает значение
        window.__wmsAllowFocus = true;
        try { el.focus(); } catch (x) {}
        codeS.split('').forEach(function(ch){
          ['keydown', 'keypress', 'keyup'].forEach(function(t){
            try { el.dispatchEvent(new KeyboardEvent(t, { key: ch, keyCode: ch.charCodeAt(0), which: ch.charCodeAt(0), bubbles: true, cancelable: true })); } catch (x) {}
          });
        });
        setTimeout(function(){ window.__wmsAllowFocus = false; }, 700);
      }
      setter.call(el, codeS);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      ['keydown', 'keypress', 'keyup'].forEach(function(t){
        el.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
      });
      return 'ok';
    } catch (e) { return 'err:' + e; }
  };

  // ---------- календарь приложения вместо календаря сайта ----------
  // Нажали на поле даты (или на значок календаря рядом) — сайт свой календарь не показывает, открывается календарь приложения.
  // Выбранная дата сама подставляется в это поле (в фоне), с событиями input/change, чтобы сайт её принял.
  try {
    var RX_DL = /дата|срок годности|datum|mhd|haltbar|ablauf|date/i;
    var dtLast = 0, dtx = 0, dty = 0, dtMoved = false;
    var ctxText = function(e){
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
    };
    var dateInputOf = function(t){
      try {
        if (!t || !t.closest) return null;
        var inp = null;
        if (t.tagName === 'INPUT') inp = t;
        else {
          var g = t.closest('.input-group,.date,[class*=datepicker],[class*=datetimepicker],[class*=date-picker],[class*=calendar]');
          if (g) { var c = g.querySelectorAll('input'); if (c.length === 1) inp = c[0]; }
        }
        if (!inp || inp.disabled || !/^(text|date|datetime-local|)$/i.test(inp.type || '')) return null;
        if (inp.type === 'date' || inp.type === 'datetime-local') return inp;
        var ig = inp.closest('.input-group');
        var cls = (inp.className || '') + ' ' + ((inp.parentElement && inp.parentElement.className) || '') + ' ' + (inp.id || '') + ' ' + (inp.name || '');
        if (/date|datum|picker|calendar/i.test(cls)) return inp;
        if (ig && ig.querySelector('.fa-calendar,.fa-calendar-alt,.fa-calendar-days,.glyphicon-calendar,[class*=calendar]')) return inp;
        if (/^\d{2}\.\d{2}\.\d{4}/.test(inp.value || '')) return inp;
        return RX_DL.test(ctxText(inp)) ? inp : null;
      } catch (x) { return null; }
    };
    var dateTap = function(e){
      try {
        if (!e.isTrusted) return;           // нажатия самого скрипта (автоприём) не трогаем
        var inp = dateInputOf(e.target);
        if (!inp) return;
        var now = Date.now();
        var m = /\d{2}\.\d{2}\.\d{4}/.exec(inp.value || '');
        var ok = false;
        if (now - dtLast > 400) {
          try { ok = !!window.WmsBridge.pickDate(m ? m[0] : '', ctxText(inp).slice(0, 40)); } catch (x) {}
        } else ok = true;
        dtLast = now;
        if (!ok) return;                    // приложение не может показать календарь — работает календарь сайта
        window.__cgDateEl = inp;
        try { e.preventDefault(); e.stopImmediatePropagation(); } catch (x) {}
        try { inp.blur(); } catch (x) {}
      } catch (x) {}
    };
    document.addEventListener('touchstart', function(e){ var t = e.touches && e.touches[0]; if (t) { dtx = t.clientX; dty = t.clientY; dtMoved = false; } }, true);
    document.addEventListener('touchmove', function(e){ var t = e.touches && e.touches[0]; if (t && (Math.abs(t.clientX - dtx) > 10 || Math.abs(t.clientY - dty) > 10)) dtMoved = true; }, true);
    document.addEventListener('touchend', function(e){ if (!dtMoved) dateTap(e); }, true);
    document.addEventListener('mousedown', dateTap, true);
    document.addEventListener('click', dateTap, true);
  } catch (e) {}

  // выбранная в приложении дата → в поле, на которое нажали.
  // Главный способ: приложение само «нажимает» в календаре сайта, как человек: открывает его, нажимает на заголовок
  // (дни → месяцы → годы), выбирает год, потом месяц (после месяца сайт сам открывает дни), потом день.
  // Тогда календарь сайта сам записывает дату в поле и в свою память (событие changeDate), и «Сохранить» её принимает.
  // Если нажатия не получились (другой вид календаря, день недоступен) — запасной путь: setDate календаря + значение «дд.мм.гггг» с событиями.
  window.__cgDateResult = function(v){
    try {
      var el = window.__cgDateEl;
      if (!el || !el.isConnected) return 'noinput';
      var s = String(v == null ? '' : v).trim();
      var m = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(s);
      var D = m ? +m[1] : 0, MO = m ? +m[2] : 0, Y = m ? +m[3] : 0;
      var $ = window.jQuery;
      var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      var fire = function(){
        try { el.dispatchEvent(new Event('input', { bubbles: true })); } catch (x) {}
        try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (x) {}
        try { if ($) $(el).trigger('change'); } catch (x) {}
      };
      var done = function(res){ try { window.WmsBridge && WmsBridge.log && WmsBridge.log('date:' + res + ':' + s + '=>' + el.value); } catch (x) {} };
      var allow = function(on){ window.__wmsAllowFocus = !!on; };
      var vis = function(e){
        try { var r = e.getBoundingClientRect(); if (r.width <= 0 || r.height <= 0) return false;
          var cs = getComputedStyle(e); return cs.display !== 'none' && cs.visibility !== 'hidden'; } catch (x) { return false; }
      };
      var tap = function(e){
        try { e.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window })); return true; } catch (x) { return false; }
      };
      var hideCal = function(){
        try { var h = ($(el).data('datepicker') ? $(el) : $(el).closest('.date,.input-group,[data-provide=datepicker]')); h.datepicker('hide'); } catch (x) {}
        try { el.blur(); } catch (x) {}
      };

      // пустая строка («Очистить»)
      if (!m) {
        setter.call(el, '');
        try { if ($ && $.fn.datepicker) $(el).datepicker('clearDates'); } catch (x) {}
        fire();
        try { el.dispatchEvent(new Event('blur', { bubbles: true })); } catch (x) {}
        done('clear');
        return 'ok';
      }

      // значение в том виде, как его хранит поле: дата + прежнее время (если оно было), у type=date — гггг-мм-дд
      var tm = /\s\d{1,2}:\d{2}.*$/.exec(el.value || '');
      var strict = s + (tm ? tm[0] : '');
      if (el.type === 'date') strict = Y + '-' + s.slice(3, 5) + '-' + s.slice(0, 2);
      var has = function(){
        var n = ((el.value || '').match(/\d+/g) || []).map(Number);
        return n.indexOf(D) >= 0 && n.indexOf(MO) >= 0 && n.indexOf(Y) >= 0;
      };

      // запасной путь
      var fallback = function(){
        try {
          if ($ && $.fn.datepicker) {
            var h = ($(el).data('datepicker') ? $(el) : $(el).closest('.date,.input-group,[data-provide=datepicker]'));
            try { h.datepicker('setDate', new Date(Y, MO - 1, D)); } catch (x) {}
          }
        } catch (x) {}
        if (!has() || (el.type !== 'date' && !tm && el.value !== s)) setter.call(el, strict);
        fire();
        try { if ($ && $.fn.datepicker) { var h2 = ($(el).data('datepicker') ? $(el) : $(el).closest('.date,.input-group,[data-provide=datepicker]')); h2.datepicker('update', strict); } } catch (x) {}
        fire();
        if (el.value !== strict) { setter.call(el, strict); fire(); }
        hideCal();
        try { el.dispatchEvent(new Event('blur', { bubbles: true })); } catch (x) {}
        allow(false);
        done('fallback');
      };

      // нажатия в календаре сайта работают только для календаря bootstrap-datepicker (обычное поле даты, без времени)
      if (!$ || !$.fn || !$.fn.datepicker || el.type === 'date' || tm) { fallback(); return 'ok'; }

      var findCal = function(){
        var c = [].slice.call(document.querySelectorAll('.datepicker-dropdown,.datepicker.datepicker-inline,div.datepicker')).filter(vis);
        return c.length ? c[c.length - 1] : null;
      };
      var viewOf = function(dp){
        var n = ['days', 'months', 'years', 'decades', 'centuries'];
        for (var i = 0; i < n.length; i++) { var t = dp.querySelector('.datepicker-' + n[i]); if (t && vis(t)) return n[i]; }
        return '';
      };
      var open = function(){
        try {
          var h = ($(el).data('datepicker') ? $(el) : $(el).closest('.date,.input-group,[data-provide=datepicker]'));
          h.datepicker('show');
        } catch (x) {}
      };

      allow(true);
      var stage = 'up', tries = 0, guard = 0, lastYearFirst = null;
      var tick = function(){
        tries++;
        if (tries > 60) { fallback(); return; }
        var dp = findCal();
        if (!dp) { if (tries === 1 || tries % 8 === 0) open(); setTimeout(tick, 80); return; }
        var vw = viewOf(dp);
        if (stage === 'up') {
          if (vw === 'years') stage = 'year';
          else if (vw === 'days' || vw === 'months') { var sw = dp.querySelector('.datepicker-' + vw + ' .datepicker-switch'); if (sw) tap(sw); else { fallback(); return; } setTimeout(tick, 90); return; }
          else { fallback(); return; }
        }
        if (stage === 'year') {
          if (vw !== 'years') { setTimeout(tick, 80); return; }
          var ys = [].slice.call(dp.querySelectorAll('.datepicker-years span.year'));
          var hit = ys.filter(function(e){ return (e.textContent || '').trim() === String(Y); })[0];
          if (hit) {
            if (/disabled/.test(hit.className)) { fallback(); return; }
            tap(hit); stage = 'month'; setTimeout(tick, 90); return;
          }
          var first = parseInt((ys[0] && ys[0].textContent) || '0', 10);
          if (!first || ++guard > 14 || first === lastYearFirst) { fallback(); return; }
          lastYearFirst = first;
          var nav = dp.querySelector('.datepicker-years ' + (Y < first ? '.prev' : '.next'));
          if (!nav) { fallback(); return; }
          tap(nav); setTimeout(tick, 90); return;
        }
        if (stage === 'month') {
          if (vw !== 'months') { setTimeout(tick, 80); return; }
          var ms = dp.querySelectorAll('.datepicker-months span.month');
          var mm = ms[MO - 1];
          if (!mm || /disabled/.test(mm.className)) { fallback(); return; }
          tap(mm); stage = 'day'; setTimeout(tick, 90); return;
        }
        if (stage === 'day') {
          if (vw !== 'days') { setTimeout(tick, 80); return; }
          var ds = [].slice.call(dp.querySelectorAll('.datepicker-days td.day')).filter(function(e){
            return !/\b(old|new)\b/.test(e.className) && (e.textContent || '').trim() === String(D);
          });
          var dd = ds[0];
          if (!dd || /disabled/.test(dd.className)) { fallback(); return; }
          tap(dd); stage = 'check'; setTimeout(tick, 150); return;
        }
        if (stage === 'check') {
          if (has()) {
            // строго дд.мм.гггг с точками: если сайт записал в другом виде — переписываем
            if (el.value !== strict) { setter.call(el, strict); fire(); }
            hideCal();
            try { el.dispatchEvent(new Event('blur', { bubbles: true })); } catch (x) {}
            allow(false);
            done('clicks');
          } else fallback();
        }
      };
      tick();
      return 'ok';
    } catch (e) { return 'err:' + e; }
  };

  // ---------- вид списка позиций (Настройки → «Вид списков») ----------
  // 0 — как на сайте; 1 — компактная карточка (чекбокс, стрелка, номер и корзина в одну строку, маленькое фото, счётчики в строку);
  // 2 — одна строка на позицию (чекбокс + номер и название), остальное раскрывается нажатием на строку.
  // Строки списка ищутся по признакам, а не по названиям классов сайта: рядом одинаковые блоки, внутри чекбокс и картинка/ссылка/«Stck».
  (function(){
    var ID = '__cgListCss';
    var CSS =
      'html[data-cglv] [data-cgrow]{display:flex!important;flex-wrap:wrap!important;align-items:center!important;gap:2px 6px!important;padding:4px 6px!important;min-height:0!important;height:auto!important;}' +
      'html[data-cglv] [data-cgrow]>*{width:auto!important;max-width:100%!important;flex:0 1 auto!important;min-height:0!important;height:auto!important;margin:0!important;padding:0 2px!important;float:none!important;position:static!important;}' +
      'html[data-cglv] [data-cgrow] input[type=checkbox]{margin:0!important;}' +
      'html[data-cglv] [data-cgrow] img{max-height:44px!important;max-width:60px!important;width:auto!important;height:auto!important;}' +
      'html[data-cglv] [data-cgrow] [data-cgname]{flex:1 1 55%!important;}' +
      'html[data-cglv] [data-cgrow] [data-cgcnt],html[data-cglv] [data-cgrow] [data-cgcnt] *{display:inline!important;font-size:12px!important;line-height:1.3!important;margin:0 6px 0 0!important;padding:0!important;}' +
      'html[data-cglv] [data-cgrow] br{display:none!important;}' +
      'html[data-cglv="2"] [data-cgrow]:not([data-cgopen]) *:not([data-cgk]){display:none!important;}' +
      'html[data-cglv="2"] [data-cgrow]:not([data-cgopen]) [data-cgmain],html[data-cglv="2"] [data-cgrow]:not([data-cgopen]) [data-cgmain] *{font-size:15px!important;line-height:1.25!important;}' +
      'html[data-cglv="2"] [data-cgrow]{border-bottom:1px solid rgba(128,128,128,.35)!important;}';
    var mode = 0, timer = 0, obs = null;
    function css(on){
      var old = document.getElementById(ID);
      if (!on) { if (old) old.remove(); return; }
      if (old) return;
      var st = document.createElement('style'); st.id = ID; st.textContent = CSS;
      (document.head || document.documentElement).appendChild(st);
    }
    function mark(){
      try {
        var cbs = document.querySelectorAll('input[type=checkbox]');
        for (var i = 0; i < cbs.length; i++) {
          var cb = cbs[i];
          if (cb.closest('[data-cgrow]') || cb.closest('.navbar,.topbar,.modal,.datepicker')) continue;
          var p = cb.parentElement;
          for (var k = 0; k < 8 && p && p !== document.body; k++) {
            var par = p.parentElement; if (!par) break;
            var sibs = [].filter.call(par.children, function(c){ return c.tagName === p.tagName && c.className === p.className; });
            if (sibs.length >= 2 && /Stck|№|MHD/.test(p.textContent || '') && p.querySelector('img,a,button')) {
              sibs.forEach(prep);
              break;
            }
            p = par;
          }
        }
      } catch (x) {}
    }
    function chain(e, row, set){ while (e && e !== row) { e.setAttribute('data-cgk', '1'); e = e.parentElement; } }
    function prep(row){
      if (row.getAttribute('data-cgrow') === '2') return;
      row.setAttribute('data-cgrow', '1');
      try {
        var cb = row.querySelector('input[type=checkbox]');
        if (cb) { cb.setAttribute('data-cgk', '1'); chain(cb.parentElement, row); }
        // главная ссылка: номер + название товара (самая длинная ссылка со словами)
        var main = null, best = 0;
        [].forEach.call(row.querySelectorAll('a'), function(a){
          var t = (a.textContent || '').trim();
          if (t.length > best && /[A-Za-zА-Яа-я]{3}/.test(t)) { best = t.length; main = a; }
        });
        if (main) {
          main.setAttribute('data-cgmain', '1'); main.setAttribute('data-cgk', '1');
          [].forEach.call(main.querySelectorAll('*'), function(d){ d.setAttribute('data-cgk', '1'); });
          chain(main.parentElement, row);
          var cell = main; while (cell.parentElement && cell.parentElement !== row) cell = cell.parentElement;
          cell.setAttribute('data-cgname', '1');
        }
        // ячейка со счётчиками «N Stck в заказе…»
        [].forEach.call(row.children, function(c){ if (/Stck\s+в|Stck\s+in|в заказе/i.test(c.textContent || '') && !c.querySelector('a,input')) c.setAttribute('data-cgcnt', '1'); });
        row.setAttribute('data-cgrow', '2');
        row.addEventListener('click', function(e){
          if (mode !== 2) return;
          if (e.target.closest('input,a,button,label,select')) return;
          if (row.hasAttribute('data-cgopen')) row.removeAttribute('data-cgopen'); else row.setAttribute('data-cgopen', '1');
        });
      } catch (x) {}
    }
    function dump(){
      try {
        var row = document.querySelector('[data-cgrow]');
        if (!row) { WmsBridge.log('rowdump: строка списка не найдена'); return; }
        var out = [];
        (function walk(e, d){
          if (d > 5 || out.length > 60) return;
          var t = ''; for (var i = 0; i < e.childNodes.length; i++) if (e.childNodes[i].nodeType === 3) t += e.childNodes[i].nodeValue;
          out.push(new Array(d + 1).join('  ') + e.tagName.toLowerCase() + (e.className && typeof e.className === 'string' ? '.' + e.className.trim().split(/\s+/).join('.') : '') + (t.trim() ? ' "' + t.trim().slice(0, 24) + '"' : ''));
          for (var j = 0; j < e.children.length; j++) walk(e.children[j], d + 1);
        })(row, 0);
        WmsBridge.log('rowdump:\n' + out.join('\n'));
      } catch (x) {}
    }
    function schedule(){ clearTimeout(timer); timer = setTimeout(function(){ if (mode) mark(); }, 250); }
    window.__cgSetListView = function(m){
      mode = +m || 0;
      window.__cgListMode = mode;
      if (!mode) { document.documentElement.removeAttribute('data-cglv'); css(false); if (obs) { try { obs.disconnect(); } catch (x) {} obs = null; } return; }
      css(true);
      document.documentElement.setAttribute('data-cglv', String(mode));
      mark();
      if (!obs) { try { obs = new MutationObserver(schedule); obs.observe(document.body || document.documentElement, { childList: true, subtree: true }); } catch (x) {} }
      setTimeout(dump, 600);
    };
    var m0 = 0;
    try { m0 = +(window.WmsBridge && WmsBridge.listViewMode && WmsBridge.listViewMode()) || 0; } catch (e) {}
    if (m0) { if (document.body) window.__cgSetListView(m0); else document.addEventListener('DOMContentLoaded', function(){ window.__cgSetListView(m0); }); }
  })();

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

// ---------- «Провести» (Buchen): уведомление сайта показываем на 5 секунд — большая галочка и текст внизу ----------
(function(){
  if (window.__cgNotice) return;
  window.__cgNotice = true;
  var buchenAt = 0, lastText = '', lastAt = 0;
  var WINDOW_MS = 30000;
  var RX_OK = /Wareneingangsschein\s+gebucht|(накладн|приход|поступлен|ордер|документ|товар|Wareneingang)[^.]{0,70}(проведен|оприходован|забронирован|учтен|учтён|занесен|занесён|gebucht|posted|booked)|(проведен|оприходован|забронирован)[а-я]*\s+(накладн|приход|поступлен)/i;
  var SEL = '.toast,.toastr,.toast-message,.snackbar,.snack-bar,.notification,.notify,.noty,.noty_body,.growl,.alert,[role=alert],[role=status],[aria-live=polite],[aria-live=assertive],' +
    '.mat-snack-bar-container,.mat-mdc-snack-bar-container,.mat-mdc-snack-bar-label,.p-toast-message,.ui-growl-item,.swal2-toast,.ajs-message,.k-notification,.ui-notificationbar,[class*=toast],[class*=Toast],[class*=notification],[class*=snackbar],[class*=Snackbar]';
  function vis(e){ try { return !!e && e.isConnected && e.getClientRects().length > 0; } catch (x) { return false; } }
  function txt(e){ return ((e && (e.innerText || e.textContent)) || '').replace(/\s+/g, ' ').trim(); }

  document.addEventListener('click', function(ev){
    try {
      var b = ev.target && ev.target.closest ? ev.target.closest('button,a,[role=button],.btn,input[type=button],input[type=submit]') : null;
      if (!b) return;
      var t = (b.innerText || b.textContent || b.value || '').replace(/\s+/g, ' ').trim();
      buchenAt = 0;
    } catch (x) {}
  }, true);

  function rgbOf(el){
    try {
      var c = getComputedStyle(el).backgroundColor || '';
      var m = c.match(/rgba?\(\s*(\d+)[ ,]+(\d+)[ ,]+(\d+)(?:[ ,\/]+([\d.]+))?/);
      if (!m) return null;
      if (m[4] !== undefined && parseFloat(m[4]) < 0.3) return null;
      return [+m[1], +m[2], +m[3]];
    } catch (x) { return null; }
  }
  // цвет уведомления: зелёный — успех, красный — ошибка; остальные (синие, жёлтые) приложение не трогает
  function kindOf(e){
    var p = e;
    for (var i = 0; i < 4 && p && p.nodeType === 1; i++, p = p.parentElement) {
      var c = rgbOf(p);
      if (c) {
        if (c[1] > c[0] + 20 && c[1] > c[2] + 10) return 'ok';
        if (c[0] > 150 && c[1] < 110 && c[2] < 110) return 'err';
        return '';
      }
    }
    var cl = '';
    try { cl = (typeof e.className === 'string' ? e.className : '') + ' ' + ((e.parentElement && typeof e.parentElement.className === 'string') ? e.parentElement.className : ''); } catch (x) {}
    if (/success|green/i.test(cl)) return 'ok';
    if (/danger|error|red/i.test(cl)) return 'err';
    return '';
  }
  function report(e){
    var tries = 0;
    (function attempt(){
      setTimeout(function(){
        try {
          var t = txt(e).replace(/^[\s×✕✖]+/, '').replace(/[\s×✕✖]+$/, '').trim();
          var k = kindOf(e);
          // вспомогательные уведомления приложения выключены — сайт показывает свои сообщения как есть
          try { if (window.WmsBridge.noticesOn && !window.WmsBridge.noticesOn()) return; } catch (x) {}
          if (k === 'err') return;   // красные уведомления приложение не трогает и не показывает
          if (t && t.length <= 200 && k) {
            if (t === lastText && Date.now() - lastAt < 3000) return;
            lastText = t; lastAt = Date.now();
            // вид «накладная проведена» — отдельный сигнал (звук)
            var kind = (k === 'ok' && RX_OK.test(t)) ? 'post' : k;
            window.WmsBridge.notice(kind, t);
            // уведомление сайта показывает приложение в центре экрана, свою полосу сайта прячем
            try { e.style.setProperty('visibility', 'hidden', 'important'); } catch (x) {}
            if (k === 'err') {
              // красное исчезает так же, как у сайта: следим, когда оно пропадёт
              var iv = setInterval(function(){
                var gone = !e.isConnected || e.getClientRects().length === 0;
                if (gone) { clearInterval(iv); try { window.WmsBridge.noticeEnd(); } catch (x) {} }
              }, 300);
              setTimeout(function(){ clearInterval(iv); }, 20000);
            }
            return;
          }
        } catch (x) {}
        // текст мог быть ещё не переведён на русский: пробуем ещё раз, пока уведомление на экране
        if (++tries < 5 && e.isConnected) attempt();
      }, tries === 0 ? 500 : 900);
    })();
  }
  function check(n){
    try {
      if (n.nodeType !== 1) return;
      if (n.id === '__cgBar' || n.id === '__cgWin' || n.id === '__cgScanBtn') return;
      var hit = null;
      if (n.matches && n.matches(SEL)) hit = n;
      else if (n.querySelector) hit = n.querySelector(SEL);
      if (!hit) return;
      if (!vis(hit) && !vis(n)) return;
      // уведомление — короткое сообщение, а не целая страница или диалог
      if (txt(hit).length > 300 || (hit.closest && hit.closest('.modal,[role=dialog]') && /modal|dialog/i.test(hit.className || ''))) return;
      report(hit);
    } catch (x) {}
  }
  function start(){
    if (!document.body) { setTimeout(start, 200); return; }
    new MutationObserver(function(ms){
      ms.forEach(function(m){ [].slice.call(m.addedNodes || []).forEach(check); });
    }).observe(document.body, { childList: true, subtree: true });
  }
  start();
})();

// ---------- ожидание: считаем запросы сайта; если сайт что-то грузит дольше 0,6 с — приложение показывает значок ожидания ----------
(function(){
  if (window.__cgNet) return;
  window.__cgNet = true;
  var pend = 0, netOn = false, onT = null, offT = null, maxT = null;
  var SKIP = /poll|heartbeat|ping|keepalive|signalr|websocket|notification\/count|session\/touch/i;
  function send(on){ try { window.WmsBridge.busy('net', on); } catch (x) {} }
  function upd(){
    if (pend > 0) {
      if (offT) { clearTimeout(offT); offT = null; }
      if (!netOn && !onT) {
        onT = setTimeout(function(){
          onT = null;
          if (pend > 0) {
            netOn = true; send(true);
            // защита: если запрос завис, значок не висит вечно
            maxT = setTimeout(function(){ if (netOn) { netOn = false; send(false); } }, 15000);
          }
        }, 600);
      }
    } else {
      if (onT) { clearTimeout(onT); onT = null; }
      if (netOn && !offT) {
        offT = setTimeout(function(){
          offT = null;
          if (pend === 0 && netOn) { netOn = false; if (maxT) clearTimeout(maxT); send(false); }
        }, 250);
      }
    }
  }
  try {
    var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u){ try { this.__cgSkip = SKIP.test(String(u)); } catch (x) {} return XO.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function(){
      if (!this.__cgSkip) {
        pend++; upd();
        var done = false, self = this;
        function fin(){ if (done) return; done = true; pend = Math.max(0, pend - 1); upd(); }
        this.addEventListener('loadend', fin);
      }
      return XS.apply(this, arguments);
    };
  } catch (x) {}
  try {
    var F = window.fetch;
    if (F) window.fetch = function(inp){
      var skip = false;
      try { skip = SKIP.test(String(inp && inp.url ? inp.url : inp)); } catch (x) {}
      if (skip) return F.apply(this, arguments);
      pend++; upd();
      var p = F.apply(this, arguments);
      var fin = function(){ pend = Math.max(0, pend - 1); upd(); };
      p.then(fin, fin);
      return p;
    };
  } catch (x) {}
})();

// ---------- захват ответов поиска сайта (только пока приложение само ищет через строку поиска сайта) ----------
(function(){
  if (window.__cgCapHook) return;
  window.__cgCapHook = true;
  var RX = /\.Actions\.Search\b/;
  try {
    var XO = XMLHttpRequest.prototype.open, XS = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(m, u){ try { this.__cgU = String(u); } catch (x) {} return XO.apply(this, arguments); };
    XMLHttpRequest.prototype.send = function(b){
      try {
        if (window.__cgCap && this.__cgU && RX.test(this.__cgU)) {
          var self = this, body = typeof b === 'string' ? b : '';
          this.addEventListener('loadend', function(){
            try {
              if (!window.__cgCap) return;
              var r = (self.responseType === '' || self.responseType === 'text') ? self.responseText : JSON.stringify(self.response);
              window.__cgCap.res = { url: self.__cgU, body: body, resp: r, t: Date.now() };
            } catch (x) {}
          });
        }
      } catch (x) {}
      return XS.apply(this, arguments);
    };
  } catch (x) {}
  try {
    var F = window.fetch;
    if (F) window.fetch = function(inp, init){
      var p = F.apply(this, arguments);
      try {
        var u = String(inp && inp.url ? inp.url : inp);
        if (window.__cgCap && RX.test(u)) {
          var body = (init && typeof init.body === 'string') ? init.body : '';
          p.then(function(r){ try { r.clone().text().then(function(t){ if (window.__cgCap) window.__cgCap.res = { url: u, body: body, resp: t, t: Date.now() }; }); } catch (x) {} });
        }
      } catch (x) {}
      return p;
    };
  } catch (x) {}
})();
