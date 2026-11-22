(function(){
  if (window.__cgBar) return;
  window.__cgBar = true;

  // Шапка самого сайта (header.topbar > nav.navbar: домик, окна-вкладки с закрытием, гамбургер):
  //  - всегда сверху экрана и не уезжает при прокрутке и масштабировании (ширина как у сайта, размер на экране не меняется);
  //  - высота и иконки в Z раз крупнее, чем на сайте;
  //  - кнопка сканера слева от гамбургера, с промежутком.
  // Ширину шапки НЕ меняем: сайт сам считает по ней, сколько вкладок помещается.
  var Z = 1.5;       // во сколько раз крупнее высота шапки и иконки
  var BACK_Z = 1.5;  // и кнопка «Назад» самого сайта
  var HAM_W = 52 * Z; // ширина кнопки-гамбургера после увеличения
  var GAP = 22;      // промежуток между кнопкой сканера и гамбургером
  var SCAN_W = 66;
  var bar = null, btn = null, busy = false, H = 60, lastK = 0;

  function vis(e){ try { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (x) { return false; } }
  function report(v){ try { window.WmsBridge.barFound(!!v); } catch (x) {} }

  function addStyle(){
    var old = document.getElementById('__cgBarCss');
    if (old) old.remove();
    var s = document.createElement('style');
    s.id = '__cgBarCss';
    var Zs = String(Z);
    s.textContent =
      // сама шапка: прижата к видимой части экрана (позицию задаёт скрипт), без анимации
      '.topbar [data-cgbar]{position:fixed!important;top:0!important;left:0!important;right:0!important;margin:0!important;' +
      'height:' + H + 'px!important;min-height:' + H + 'px!important;z-index:2147483000!important;' +
      'transform-origin:0 0!important;transition:none!important;box-sizing:border-box!important;}' +
      // вкладки (домик, окна, «ещё N») и гамбургер — крупнее
      '.topbar [data-cgbar] .navbar-tab-item{zoom:' + Zs + '!important;}' +
      '.topbar [data-cgbar] .topbar-menu-link{zoom:' + Zs + '!important;}' +
      '.topbar [data-cgbar] .navbar-tabs-container{height:' + H + 'px!important;}' +
      // место справа под гамбургер и кнопку сканера
      '.md-skin .topbar [data-cgbar] .navbar-tabs-wrapper{width:calc(100% - ' + Math.round(HAM_W + GAP + SCAN_W) + 'px)!important;}' +
      // меню гамбургера раскрывается под шапкой на весь видимый экран (шапка с transform — fixed внутри неё не годится)
      '.topbar [data-cgbar] .topbar-menu-item .profile{position:absolute!important;top:100%!important;right:0!important;left:auto!important;' +
      'width:var(--cgW,100%)!important;height:var(--cgVH,80vh)!important;overflow:auto!important;}' +
      '#__cgScanBtn{position:absolute;top:50%;margin-top:-' + Math.round(H * 0.4) + 'px;width:' + SCAN_W + 'px;height:' + Math.round(H * 0.8) + 'px;border:0;border-radius:8px;' +
      'background:rgba(255,255,255,.25);color:#fff;display:flex;align-items:center;justify-content:center;padding:0;cursor:pointer;z-index:5;}' +
      '#__cgScanBtn svg{width:34px;height:34px;fill:none;stroke:#fff;stroke-width:2;stroke-linecap:round;}' +
      '.btn-back{zoom:' + BACK_Z + '!important;}';
    (document.head || document.documentElement).appendChild(s);
  }

  // ---- поиск шапки на странице
  function findBar(){
    var n = document.querySelector('header.topbar nav.navbar, .topbar nav.navbar, nav.navbar-fixed-top');
    if (n && vis(n)) return n;
    var W = window.innerWidth || document.documentElement.clientWidth;
    var seeds = [].slice.call(document.querySelectorAll('a.navbar-tab-link,a.profile-link,#dropdownProfile,.topbar-menu'));
    for (var i = 0; i < seeds.length; i++) {
      var p = seeds[i], best = null;
      if (!vis(p)) continue;
      while (p && p !== document.body && p !== document.documentElement) {
        var r = p.getBoundingClientRect();
        if (r.height > 170) break;
        if (r.width >= W * 0.85 && r.height >= 24 && r.top + (window.scrollY || 0) < 200) best = p;
        p = p.parentElement;
      }
      if (best) return best;
    }
    return null;
  }

  function adopt(b){
    var h0 = b.getBoundingClientRect().height || 40;
    H = Math.round(h0 * Z);
    // страница сдвигается вниз ровно на прибавку высоты шапки
    var pw = document.getElementById('page-wrapper');
    var pad = pw ? parseFloat(getComputedStyle(pw).paddingTop) || 0 : 0;
    addStyle();
    var ex = document.getElementById('__cgBarPad');
    if (ex) ex.remove();
    if (pw) {
      var s = document.createElement('style');
      s.id = '__cgBarPad';
      s.textContent = '#page-wrapper{padding-top:' + Math.round(pad + (H - h0)) + 'px!important;}';
      (document.head || document.documentElement).appendChild(s);
    }
    bar = b;
    b.setAttribute('data-cgbar', '1');
    btn = null;
    lastK = 0;
    place();
    report(true);
  }

  // шапка прижата к видимой части экрана: позиция и размер пересчитываются под текущий масштаб страницы
  function place(){
    if (!bar || !bar.isConnected) return;
    var vv = window.visualViewport;
    var lw = document.documentElement.clientWidth || window.innerWidth;
    var vw = vv ? vv.width : lw, vh = vv ? vv.height : window.innerHeight;
    var ox = vv ? vv.offsetLeft : 0, oy = vv ? vv.offsetTop : 0;
    var k = vw / lw;
    bar.style.setProperty('transform', 'translate(' + ox + 'px,' + oy + 'px) scale(' + k + ')', 'important');
    bar.style.setProperty('--cgW', lw + 'px');
    bar.style.setProperty('--cgVH', Math.max(120, vh / k - H) + 'px');
    if (Math.abs(lastK - k) > 0.001) { lastK = k; layoutBtn(k); }
  }

  var raf = 0;
  function sched(){ if (raf) return; raf = requestAnimationFrame(function(){ raf = 0; place(); }); }
  try {
    window.addEventListener('scroll', sched, true);
    window.addEventListener('resize', sched);
    if (window.visualViewport) {
      window.visualViewport.addEventListener('scroll', sched);
      window.visualViewport.addEventListener('resize', sched);
    }
  } catch (x) {}

  // ---- кнопка сканера слева от гамбургера
  function hamburger(){
    if (!bar) return null;
    var h = bar.querySelector('#dropdownProfile,.topbar-menu-link');
    if (h && vis(h)) return h;
    var br = bar.getBoundingClientRect(), best = null, bx = -1e9;
    [].slice.call(bar.querySelectorAll('a,button,[role=button]')).forEach(function(e){
      if (e.id === '__cgScanBtn') return;
      var r = e.getBoundingClientRect();
      if (r.width < 8 || r.height < 8 || r.width > br.width * 0.4) return;
      if (r.right > bx + 1) { bx = r.right; best = e; }
    });
    return best;
  }

  function layoutBtn(k){
    if (!btn || !bar) return;
    var br = bar.getBoundingClientRect();
    var h = hamburger();
    var right = HAM_W + GAP;
    if (h) {
      var hr = h.getBoundingClientRect();
      if (hr.width > 4) right = Math.max(HAM_W, (br.right - hr.left) / (k || 1)) + GAP;
    }
    btn.style.right = Math.round(right) + 'px';
  }

  function ensureBtn(){
    if (!bar) return;
    if (btn && btn.parentNode === bar) return;
    btn = document.createElement('button');
    btn.id = '__cgScanBtn';
    btn.type = 'button';
    btn.setAttribute('aria-label', 'Скан');
    btn.innerHTML = '<svg viewBox="0 0 24 24"><path d="M3 8V5a2 2 0 0 1 2-2h3M16 3h3a2 2 0 0 1 2 2v3M21 16v3a2 2 0 0 1-2 2h-3M8 21H5a2 2 0 0 1-2-2v-3M7 8v8M11 8v8M15 8v8M18 8v8"/></svg>';
    var go = function(e){ try { e.preventDefault(); e.stopPropagation(); } catch (x) {} try { window.WmsBridge.scan(); } catch (x) {} };
    btn.addEventListener('click', go, true);
    btn.addEventListener('touchend', function(e){ go(e); }, true);
    bar.appendChild(btn);
    lastK = 0;
    place();
  }

  // ---- «Назад» сайта — крупнее (основное правило в CSS: .btn-back; здесь — запасной вариант по тексту)
  function bigBack(){
    try {
      [].slice.call(document.querySelectorAll('a,button')).forEach(function(e){
        if (e.__cgBig || e.classList.contains('btn-back')) return;
        if (bar && bar.contains(e)) return;
        var t = (e.textContent || '').replace(/\s+/g, ' ').trim();
        if (/^(назад|zurück|zurueck|back)$/i.test(t) && vis(e)) {
          e.__cgBig = 1;
          e.style.setProperty('zoom', String(BACK_Z), 'important');
        }
      });
    } catch (x) {}
  }

  function tick(){
    if (busy) return;
    busy = true;
    try {
      if (!document.body) return;
      if (bar && !bar.isConnected) { bar = null; btn = null; report(false); }
      if (!bar) {
        var b = findBar();
        if (b) adopt(b);
      }
      if (bar) {
        ensureBtn();
        layoutBtn(lastK || 1);
      }
      bigBack();
    } catch (x) {
    } finally {
      busy = false;
    }
  }

  setInterval(tick, 700);
  document.addEventListener('DOMContentLoaded', tick);
  window.addEventListener('load', function(){ tick(); setTimeout(tick, 400); });
})();
