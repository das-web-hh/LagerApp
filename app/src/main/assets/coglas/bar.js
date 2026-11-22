(function(){
  if (window.__cgBar) return;
  window.__cgBar = true;

  // Шапка самого сайта (header.topbar > nav.navbar: домик, окна-вкладки с закрытием, гамбургер):
  //  - всегда сверху экрана и не уезжает при прокрутке и масштабировании (ширина как у сайта, размер на экране не меняется);
  //  - высота и иконки в Z раз крупнее, чем на сайте;
  //  - кнопка сканера слева от гамбургера, с промежутком.
  // Ширину шапки НЕ меняем: сайт сам считает по ней, сколько вкладок помещается.
  // На телефоне сайт рисует мобильную шапку (вкладок нет, заголовок окна отдельной строкой). Здесь она приводится к виду ПК.
  var Z = 1.5;       // во сколько раз крупнее высота шапки и иконки
  var BACK_Z = 1.5;  // и кнопка «Назад» самого сайта
  var HAM_W = 52 * Z; // ширина кнопки-гамбургера после увеличения
  var GAP = 22;      // промежуток между кнопкой сканера и гамбургером
  var SCAN_W = 66;
  var bar = null, btn = null, busy = false, H = 60, lastK = 0, lastTM = null, lastAct = null;

  function vis(e){ try { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (x) { return false; } }
  function report(v){ try { window.WmsBridge.barFound(!!v); } catch (x) {} }

  function addStyle(){
    var old = document.getElementById('__cgBarCss');
    if (old) old.remove();
    var s = document.createElement('style');
    s.id = '__cgBarCss';
    var Zs = String(Z);
    var S = '.topbar [data-cgbar]';
    var X = Math.round(HAM_W + GAP + SCAN_W);
    s.textContent =
      // сама шапка: прижата к видимой части экрана (позицию задаёт скрипт), без анимации
      S + '{position:fixed!important;top:0!important;left:0!important;right:0!important;margin:0!important;' +
      'height:' + H + 'px!important;min-height:' + H + 'px!important;z-index:2147483000!important;' +
      'transform-origin:0 0!important;transition:none!important;box-sizing:border-box!important;' +
      // одна строка без переносов: домик, вкладка окна, гамбургер (как на ПК). Мобильная вёрстка сайта переносила
      // заголовок окна на вторую строку, и гамбургер с названием уезжали за пределы шапки.
      'display:flex!important;flex-wrap:nowrap!important;align-items:stretch!important;}' +
      // вкладки (домик, окна, «ещё N») и гамбургер — крупнее
      S + ' .navbar-tab-item{zoom:' + Zs + '!important;}' +
      S + ' .topbar-menu-link{zoom:' + Zs + '!important;}' +
      // блок вкладок: в строку; ширина — свободное место (с вкладками) или по содержимому (только домик)
      S + ' .navbar-tabs-wrapper{float:none!important;display:flex!important;flex:0 0 auto!important;flex-wrap:nowrap!important;' +
      'align-items:stretch!important;min-width:0!important;height:' + H + 'px!important;}' +
      S + '[data-cgtabs="1"] .navbar-tabs-wrapper{width:calc(100% - ' + X + 'px)!important;}' +
      S + ':not([data-cgtabs="1"]) .navbar-tabs-wrapper{width:auto!important;}' +
      S + ' .navbar-tabs-home{float:none!important;flex:0 0 auto!important;}' +
      S + ' .navbar-tabs-container{float:none!important;flex:1 1 auto!important;min-width:0!important;height:' + H + 'px!important;' +
      'overflow-x:auto!important;overflow-y:hidden!important;scrollbar-width:none!important;}' +
      S + ' .navbar-tabs-container::-webkit-scrollbar{display:none!important;}' +
      S + ' .navbar-tabs-unfit{float:none!important;flex:0 0 auto!important;}' +
      S + ' .navbar-tabs-container .navbar-tabs{display:flex!important;flex-wrap:nowrap!important;white-space:nowrap!important;margin:0!important;}' +
      // вкладки окон — как на ПК (на телефоне сайт их прячет и показывает вместо них заголовок)
      S + ' .navbar-tabs-container .navbar-tab-item{display:flex!important;align-items:center!important;position:relative!important;flex:0 0 auto!important;}' +
      S + ' .navbar-tab-unfit{display:flex!important;align-items:center!important;}' +
      S + ' .navbar-tabs-container .navbar-tab-item.active{background-color:#fff!important;border-radius:4px 4px 0 0!important;}' +
      S + ' .navbar-tab-link{position:relative!important;display:block!important;margin:10px 0!important;padding:0 35px 0 40px!important;' +
      'font-size:14px!important;color:#fff!important;min-width:0!important;max-width:var(--cgTabMax,170px)!important;border-left:1px solid #fff!important;}' +
      S + ' .navbar-tab-link-work{padding-right:15px!important;}' +
      S + ' .navbar-tab-link-text{display:block!important;overflow:hidden!important;text-overflow:ellipsis!important;white-space:nowrap!important;}' +
      S + ' .navbar-tab-icon{position:absolute!important;top:50%!important;left:0!important;margin-top:-8px!important;margin-left:20px!important;' +
      'font-size:14px!important;line-height:1!important;color:#fff!important;}' +
      S + ' .navbar-tab-link-close{position:absolute!important;top:50%!important;right:10px!important;width:20px!important;margin-top:-11px!important;' +
      'padding:5px!important;font-size:10px!important;line-height:1!important;text-align:center!important;color:#fff!important;}' +
      S + ' .navbar-tab-item.active .navbar-tab-icon,' + S + ' .navbar-tab-item.active .navbar-tab-link,' +
      S + ' .navbar-tab-item.active .navbar-tab-link-close{color:rgb(0,153,208)!important;}' +
      S + ' .navbar-tab-item:first-child .navbar-tab-link,' + S + ' .navbar-tab-item.active + .navbar-tab-item .navbar-tab-link{border-color:transparent!important;}' +
      S + ' .navbar-tab-empty,' + S + ' .navbar-tabs-amount{display:none!important;}' +
      // гамбургер: справа, в той же строке
      S + ' .topbar-menu{float:none!important;flex:0 0 auto!important;margin:0 0 0 auto!important;display:block!important;}' +
      // заголовок окна: показывается, только если вкладок нет (иначе название уже на вкладке, как на ПК); не переносится на вторую строку
      S + ' .navbar-mobile-heading{display:flex!important;align-items:center!important;justify-content:center!important;flex:1 1 0!important;' +
      'min-width:0!important;width:auto!important;height:100%!important;margin:0 ' + (GAP + SCAN_W) + 'px 0 4px!important;}' +
      S + '[data-cgtabs="1"] .navbar-mobile-heading{display:none!important;}' +
      S + ' .navbar-mobile-title{margin:0!important;padding:0!important;font-size:19px!important;line-height:1.15!important;white-space:nowrap!important;' +
      'overflow:hidden!important;text-overflow:ellipsis!important;max-width:100%!important;color:#fff!important;}' +
      // меню гамбургера раскрывается под шапкой на весь видимый экран (шапка с transform — fixed внутри неё не годится)
      S + ' .topbar-menu-item .profile{position:absolute!important;top:100%!important;right:0!important;left:auto!important;' +
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
    var hr = b.getBoundingClientRect().height || 40;
    var h0 = Math.max(hr, 40); // на телефоне сайт делает шапку 30px; рисуем как на ПК (40px)
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
      s.textContent = '#page-wrapper{padding-top:' + Math.round(pad + (H - hr)) + 'px!important;}';
      (document.head || document.documentElement).appendChild(s);
    }
    bar = b;
    lastTM = null;
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
    bar.style.setProperty('--cgTabMax', Math.round(lw * 0.5 / Z) + 'px');
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

  // есть ли в шапке вкладки окон (как на ПК)? Если да — заголовок окна не нужен: название стоит на вкладке.
  // Если сайт на телефоне вкладок не рисует — показывается его заголовок окна, чтобы название не пропадало.
  function syncTabs(){
    if (!bar) return;
    var has = false;
    var q = bar.querySelectorAll('.navbar-tabs-container .navbar-tab-item');
    for (var i = 0; i < q.length; i++) {
      if ((q[i].textContent || '').replace(/\s+/g, '').length > 0) { has = true; break; }
    }
    if (has !== lastTM) {
      lastTM = has;
      if (has) bar.setAttribute('data-cgtabs', '1'); else bar.removeAttribute('data-cgtabs');
      lastK = 0;
      place();
    }
    if (has) {
      var act = bar.querySelector('.navbar-tabs-container .navbar-tab-item.active');
      if (act && act !== lastAct) {
        lastAct = act;
        try { act.scrollIntoView({ inline: 'nearest', block: 'nearest' }); } catch (x) {}
      }
    }
  }

  function tick(){
    if (busy) return;
    busy = true;
    try {
      if (!document.body) return;
      if (bar && !bar.isConnected) { bar = null; btn = null; lastTM = null; lastAct = null; report(false); }
      if (!bar) {
        var b = findBar();
        if (b) adopt(b);
      }
      if (bar) {
        syncTabs();
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
