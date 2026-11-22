(function(){
  if (window.__cgBar) return;
  window.__cgBar = true;

  // Шапка самого сайта (домик, названия открытых окон с закрытием, гамбургер):
  //  - всегда сверху экрана и не уезжает при прокрутке и масштабировании;
  //  - крупнее, чем на сайте (K раз) и на всю ширину экрана при любом масштабе страницы;
  //  - рядом с гамбургером — кнопка сканера.
  // Кнопка «Назад» самого сайта делается крупнее.
  var K = 1.35;
  var BACK_K = 1.45;
  var bar = null, hold = null, btn = null, busy = false, lastW = 0;

  function vis(e){ try { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (x) { return false; } }
  function report(v){ try { window.WmsBridge.barFound(!!v); } catch (x) {} }

  function addStyle(){
    if (document.getElementById('__cgBarCss')) return;
    var s = document.createElement('style');
    s.id = '__cgBarCss';
    s.textContent =
      '[data-cgbar]{position:fixed!important;top:0!important;left:0!important;right:auto!important;margin:0!important;' +
      'z-index:2147483000!important;transform-origin:0 0!important;box-sizing:border-box!important;}' +
      '#__cgScanBtn{position:absolute;top:50%;margin-top:-20px;width:52px;height:40px;border:0;border-radius:8px;' +
      'background:rgba(255,255,255,.25);color:#fff;display:flex;align-items:center;justify-content:center;padding:0;cursor:pointer;z-index:5;}' +
      '#__cgScanBtn svg{width:28px;height:28px;fill:none;stroke:#fff;stroke-width:2;stroke-linecap:round;}';
    (document.head || document.documentElement).appendChild(s);
  }

  // ---- поиск шапки на странице
  function findBar(){
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
    // запасной путь: узкий полноширинный блок в самом верху страницы
    var c = [].slice.call(document.querySelectorAll('header,nav,.navbar,.topbar,[class*=navbar],[class*=topbar]'));
    var pick = null;
    c.forEach(function(e){
      var r = e.getBoundingClientRect();
      if (r.width >= W * 0.85 && r.height >= 24 && r.height <= 150 && r.top + (window.scrollY || 0) < 120) {
        if (!pick || r.height > pick.getBoundingClientRect().height) pick = e;
      }
    });
    return pick;
  }

  function adopt(b){
    addStyle();
    var r = b.getBoundingClientRect();
    bar = b;
    if (hold && hold.parentNode) hold.parentNode.removeChild(hold);
    hold = document.createElement('div');
    hold.id = '__cgBarHold';
    hold.style.cssText = 'height:' + Math.round(r.height * K) + 'px;width:100%;flex:none;pointer-events:none';
    b.parentNode.insertBefore(hold, b);
    b.setAttribute('data-cgbar', '1');
    btn = null;
    lastW = 0;
    place();
    report(true);
  }

  // шапка прижата к видимой части экрана: позиция и размер пересчитываются под текущий масштаб страницы
  function place(){
    if (!bar || !bar.isConnected) return;
    var vv = window.visualViewport;
    var lw = document.documentElement.clientWidth || window.innerWidth;
    var vw = vv ? vv.width : lw;
    var ox = vv ? vv.offsetLeft : 0, oy = vv ? vv.offsetTop : 0;
    var k = K * vw / lw;
    bar.style.setProperty('width', (lw / K) + 'px', 'important');
    bar.style.setProperty('transform', 'translate(' + ox + 'px,' + oy + 'px) scale(' + k + ')', 'important');
    if (Math.abs(lastW - lw) > 1) { lastW = lw; layoutBtn(k); }
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

  // ---- кнопка сканера рядом с гамбургером
  function hamburger(){
    if (!bar) return null;
    var br = bar.getBoundingClientRect(), best = null, bx = -1e9;
    [].slice.call(bar.querySelectorAll('a,button,[role=button],i,span.navbar-toggler-icon')).forEach(function(e){
      if (e.id === '__cgScanBtn' || (btn && btn.contains(e))) return;
      var r = e.getBoundingClientRect();
      if (r.width < 8 || r.height < 8 || r.width > br.width * 0.4) return;
      if (r.right > bx + 1) { bx = r.right; best = e; }
    });
    return best;
  }

  function layoutBtn(k){
    if (!btn) return;
    var br = bar.getBoundingClientRect();
    var h = hamburger();
    var right = 12;
    if (h) {
      var hr = h.getBoundingClientRect();
      right = Math.max(8, (br.right - hr.left) / (k || 1) + 8);
    }
    btn.style.right = right + 'px';
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
    lastW = 0;
    place();
  }

  // ---- «Назад» сайта — крупнее
  function bigBack(){
    try {
      [].slice.call(document.querySelectorAll('a,button')).forEach(function(e){
        if (e.__cgBig) return;
        if (bar && bar.contains(e)) return;
        var t = (e.textContent || '').replace(/\s+/g, ' ').trim();
        if (/^(назад|zurück|zurueck|back)$/i.test(t) && vis(e)) {
          e.__cgBig = 1;
          e.style.setProperty('zoom', String(BACK_K), 'important');
        }
      });
    } catch (x) {}
  }

  function tick(){
    if (busy) return;
    busy = true;
    try {
      if (!document.body) return;
      if (bar && !bar.isConnected) {
        if (hold && hold.parentNode) hold.parentNode.removeChild(hold);
        bar = null; btn = null; report(false);
      }
      if (!bar) {
        var b = findBar();
        if (b) adopt(b);
      }
      if (bar) {
        ensureBtn();
        // если шапку «сносит» из-за родителя с transform — переносим её в body
        var vv = window.visualViewport, oy = vv ? vv.offsetTop : 0;
        var top = bar.getBoundingClientRect().top;
        if (Math.abs(top - oy) > 4 && !bar.__cgMoved) {
          bar.__cgMoved = true;
          try { document.body.appendChild(bar); btn = null; ensureBtn(); } catch (x) {}
        }
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
