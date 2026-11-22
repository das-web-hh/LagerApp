(function(){
  if (window.__cgApp) return;
  window.__cgApp = true;
  var TR = true;
  try { TR = window.WmsBridge ? !!window.WmsBridge.trOn() : true; } catch (e) { TR = true; }
  window.__cgTr = TR;
  // перевод выключен: страница не меняется вообще (ни стиль, ни перевод)
  if (!TR) return;

  // ---------- вид приложения, а не сайта ----------
  var CSS = [
    'html{-webkit-tap-highlight-color:transparent;-webkit-text-size-adjust:100%;}',
    '*{-webkit-tap-highlight-color:transparent;}',
    '::-webkit-scrollbar{display:none;width:0;height:0;}',
    'a,button,[role=button],[role=tab],[role=menuitem],[role=option],input[type=button],input[type=submit],select,label{touch-action:manipulation;}'
  ].join('\n');

  function addStyle(){
    try {
      if (document.getElementById('__cgStyle')) return;
      var s = document.createElement('style');
      s.id = '__cgStyle';
      s.textContent = CSS;
      (document.head || document.documentElement).appendChild(s);
    } catch (e) {}
  }
  function fixViewport(){
    try {
      var m = document.querySelector('meta[name=viewport]');
      if (m) m.setAttribute('content', 'width=device-width, initial-scale=1, viewport-fit=cover');
    } catch (e) {}
  }

  // ---------- перевод на русский ----------
  // словарь (немецкий -> русский) подставляет приложение; остальное переводит ИИ и запоминает
  var MAP = window.__cgMap = (function(){ try { return JSON.parse('__DICT__'); } catch (e) { return {}; } })();
 
  function log(s){ try { window.WmsBridge.log('CG ' + s); } catch (e) {} }
  window.__cgStat = 'wait';

  var orig = new WeakMap();
  var cnt = new WeakMap();
  var ops = 0, opsT = Date.now();
  function guard(){
    // защита от «пинг-понга» с сайтом: если правок слишком много в секунду — перевод отключается
    var now = Date.now();
    if (now - opsT > 1000) { ops = 0; opsT = now; }
    if (++ops > 600) {
      window.__cgTr = false;
      window.__cgStat = 'off:loop';
      try { mo.disconnect(); } catch (e) {}
      log('loop guard: translation stopped');
      return false;
    }
    return true;
  }
  var pending = {};
  var asked = {};
  var timer = null;

  function norm(t){ return String(t).replace(/\s+/g, ' ').trim(); }
  function has(k){ return Object.prototype.hasOwnProperty.call(MAP, k); }

  // FULL — «перевести полностью»: в перевод идут и длинные строки, и строки с цифрами, и слова ЗАГЛАВНЫМИ
  var FULL = false;
  function skip(t){
    if (t.length < 2 || t.length > (FULL ? 400 : 120)) return true;
    if (!/[A-Za-zÄÖÜäöüß]/.test(t)) return true;
    if (/[@\/\\]|https?:/i.test(t)) return true;
    if (/[а-яА-Я]/.test(t)) return true;
    if (FULL) {
      if (!/[A-Za-zÄÖÜäöüß]{3,}/.test(t)) return true;
      if (t.indexOf(' ') < 0 && /\d/.test(t)) return true;
      return false;
    }
    if (/\d/.test(t)) return true;
    if (t.indexOf(' ') < 0 && t === t.toUpperCase() && t.length > 3) return true;
    return false;
  }

  function schedule(){
    if (timer) return;
    timer = setTimeout(flush, 300);
  }
  function flush(){
    timer = null;
    var keys = Object.keys(pending);
    if (!keys.length) return;
    var list = keys.slice(0, 40);
    list.forEach(function(t){ delete pending[t]; asked[t.toLowerCase()] = true; });
    try { window.WmsBridge.translate(JSON.stringify(list)); } catch (e) {}
    if (Object.keys(pending).length) schedule();
  }

  function convert(src){
    var t = norm(src);
    if (!t) return null;
    var k = t.toLowerCase();
    if (has(k)) {
      var lead = src.match(/^\s*/)[0];
      var trail = src.match(/\s*$/)[0];
      return lead + MAP[k] + trail;
    }
    if (skip(t)) return null;
    if (!asked[k]) { pending[t] = true; schedule(); }
    return null;
  }

  function handleText(n){
    if (!window.__cgTr) return;
    var p = n.parentNode;
    if (!p) return;
    var tag = p.nodeName;
    if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT' || tag === 'TEXTAREA' || tag === 'CODE' || tag === 'PRE') return;
    var cur = n.nodeValue;
    if (!cur) return;
    var o = orig.get(n);
    if (o && o === cur) return;
    var c = cnt.get(n) || 0;
    if (c > 6) return;
    var out = convert(cur);
    if (out !== null && out !== cur) {
      if (!guard()) return;
      cnt.set(n, c + 1);
      orig.set(n, out);
      n.nodeValue = out;
    }
  }

  var ATTRS = ['placeholder', 'title', 'aria-label'];
  function handleAttrs(el){
    if (!window.__cgTr || !el || el.nodeType !== 1) return;
    for (var i = 0; i < ATTRS.length; i++) {
      var a = ATTRS[i];
      var v = el.getAttribute(a);
      if (!v) continue;
      var mark = el.__cgA || (el.__cgA = {});
      if (mark[a] === v) continue;
      var out = convert(v);
      if (out !== null && out !== v) {
        mark[a] = out;
        el.setAttribute(a, out);
      }
    }
    if (el.tagName === 'INPUT' && (el.type === 'button' || el.type === 'submit') && el.value) {
      var mk = el.__cgA || (el.__cgA = {});
      if (mk.value !== el.value) {
        var o2 = convert(el.value);
        if (o2 !== null && o2 !== el.value) { mk.value = o2; el.value = o2; }
      }
    }
  }

  function walk(root){
    if (!root) return;
    if (root.nodeType === 3) { handleText(root); return; }
    if (root.nodeType !== 1) return;
    try {
      var w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
      var n;
      while ((n = w.nextNode())) handleText(n);
      handleAttrs(root);
      var els = root.querySelectorAll('[placeholder],[title],[aria-label],input[type=button],input[type=submit]');
      for (var i = 0; i < els.length; i++) handleAttrs(els[i]);
    } catch (e) {}
  }

  // ответ приложения: {"немецкий текст": "русский"}
  window.__cgApply = function(s){
    try {
      var m = (typeof s === 'string') ? JSON.parse(s) : s;
      for (var k in m) MAP[String(k).toLowerCase()] = m[k];
      walk(document.body || document.documentElement);
    } catch (e) {}
  };
  // «Перевести заново»: забыть, что уже спрашивали, и пройти страницу ещё раз
  window.__cgRetry = function(full){
    FULL = !!full;
    asked = {};
    cnt = new WeakMap();
    walk(document.body || document.documentElement);
  };

  var mo = new MutationObserver(function(muts){
    if (!window.__cgTr) return;
    for (var i = 0; i < muts.length; i++) {
      var m = muts[i];
      if (m.type === 'characterData') handleText(m.target);
      else if (m.type === 'attributes') handleAttrs(m.target);
      else if (m.type === 'childList') {
        for (var j = 0; j < m.addedNodes.length; j++) walk(m.addedNodes[j]);
      }
    }
  });

  function start(){
    addStyle();
    try {
      mo.observe(document.documentElement, {
        childList: true, subtree: true, characterData: true,
        attributes: true, attributeFilter: ATTRS
      });
    } catch (e) {}
    walk(document.body || document.documentElement);
  }

  // Сайту не мешаем, пока он грузится: ждём, когда заставка «loading» исчезнет и страница затихнет
  function boot(){
    var t0 = Date.now(), last = Date.now(), idle = null;
    try {
      idle = new MutationObserver(function(){ last = Date.now(); });
      idle.observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
    (function wait(){
      var txt = '';
      try { txt = document.body ? (document.body.innerText || '').trim() : ''; } catch (e) {}
      var splash = txt.length < 40 || (/loading/i.test(txt) && txt.length < 80);
      var quiet = Date.now() - last > 800;
      var waited = Date.now() - t0;
      // пока на экране форма входа — не переводим и ничего не трогаем: ждём, пока пользователь войдёт
      var loginUp = !!document.querySelector('input[type=password]');
      if (loginUp) { t0 = Date.now(); setTimeout(wait, 500); return; }
      if (!splash && quiet) {
        try { if (idle) idle.disconnect(); } catch (e) {}
        window.__cgStat = 'on';
        log('translation started after ' + waited + ' ms');
        try { start(); } catch (e) { log('start error ' + e); }
        return;
      }
      if (waited > 40000) {
        try { if (idle) idle.disconnect(); } catch (e) {}
        window.__cgStat = 'nostart';
        log('site stayed on loading screen; translation never started');
        return;
      }
      setTimeout(wait, 300);
    })();
  }
  if (document.readyState === 'complete') boot();
  else window.addEventListener('load', boot);
})();
