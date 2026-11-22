(function(){
  function vis(e){ var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  function d(e){
    var cs = getComputedStyle(e), r = e.getBoundingClientRect();
    return e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (typeof e.className === 'string' && e.className ? '.' + e.className.trim().split(/\s+/).slice(0, 3).join('.') : '') +
      ' pos=' + cs.position + ' z=' + cs.zIndex + ' op=' + cs.opacity + ' disp=' + cs.display + ' vis=' + cs.visibility +
      ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + '@' + Math.round(r.left) + ',' + Math.round(r.top);
  }
  var vv = window.visualViewport;
  var beat = 'raf=' + (window.__cgRaf || 0) + ' timers=' + (window.__cgTim || 0) + ' (прошлый замер raf=' + (window.__cgPrevRaf || 0) + ')';
  window.__cgPrevRaf = window.__cgRaf || 0;
  var se = document.scrollingElement || document.documentElement;
  var geo = 'scroll=' + Math.round(window.scrollX) + ',' + Math.round(window.scrollY) +
    ' scrollH=' + se.scrollHeight + ' scrollW=' + se.scrollWidth +
    (vv ? ' vv.scale=' + vv.scale + ' vv.off=' + Math.round(vv.offsetLeft) + ',' + Math.round(vv.offsetTop) + ' vv=' + Math.round(vv.width) + 'x' + Math.round(vv.height) : '');
  var fitInfo = 'fit=' + (window.__cgFitN || 0) + ' html.inline.h=' + document.documentElement.style.height + ' computed=' + getComputedStyle(document.documentElement).height;
  var out = ['URL: ' + location.href, 'TITLE: ' + document.title,
    'state=' + document.readyState + ' visibility=' + document.visibilityState + ' hidden=' + document.hidden +
    ' win=' + innerWidth + 'x' + innerHeight + ' dpr=' + devicePixelRatio + ' stat=' + (window.__cgStat || '-'), beat, geo, fitInfo, ''];
  try {
    out.push('В центре экрана (сверху вниз):');
    var st = document.elementsFromPoint(innerWidth / 2, innerHeight / 2);
    for (var k = 0; k < st.length && k < 6; k++) out.push('  ' + d(st[k]));
    out.push('');
  } catch (e) {}
  try {
    var u = document.querySelector('input[name=Username],input[type=password]');
    out.push('Цепочка от поля входа вверх:');
    for (var q = u, m = 0; q && m < 12; q = q.parentElement, m++) {
      var c2 = getComputedStyle(q);
      out.push('  ' + d(q) + ' ovf=' + c2.overflow + ' tr=' + (c2.transform === 'none' ? '-' : 'Y') + ' anim=' + c2.animationName + ' ptr=' + c2.pointerEvents);
    }
    out.push('');
  } catch (e) {}
  try {
    out.push('CSS-правила про размеры (html, body, #applicationHost, #wrapper, .login-container):');
    var targets = [document.documentElement, document.body, document.getElementById('applicationHost'), document.getElementById('wrapper'), document.querySelector('.login-container')];
    var props = /^(height|min-height|max-height|position|display|contain|zoom|transform|writing-mode)$/;
    var lines = 0;
    for (var si = 0; si < document.styleSheets.length && lines < 40; si++) {
      var rules;
      try { rules = document.styleSheets[si].cssRules; } catch (e) { out.push('  (лист ' + si + ' закрыт)'); continue; }
      for (var ri = 0; ri < rules.length && lines < 40; ri++) {
        var r = rules[ri];
        if (!r.selectorText || !r.style) continue;
        var hit = false;
        for (var ti = 0; ti < targets.length; ti++) { try { if (targets[ti] && targets[ti].matches(r.selectorText)) { hit = true; break; } } catch (e) {} }
        if (!hit) continue;
        var decl = [];
        for (var pi = 0; pi < r.style.length; pi++) { var nm = r.style[pi]; if (props.test(nm)) decl.push(nm + ':' + r.style.getPropertyValue(nm) + (r.style.getPropertyPriority(nm) ? '!' : '')); }
        if (decl.length) { out.push('  ' + r.selectorText.slice(0, 60) + ' { ' + decl.join('; ') + ' }'); lines++; }
      }
    }
    var hs = getComputedStyle(document.documentElement);
    out.push('html: max-h=' + hs.maxHeight + ' min-h=' + hs.minHeight + ' contain=' + hs.contain + ' zoom=' + hs.zoom + ' writing=' + hs.writingMode + ' compat=' + document.compatMode);
    out.push('');
  } catch (e) { out.push('css-diag error: ' + e); }
  try {
    out.push('Структура страницы (тег#id.класс высота×ширина @верх позиция/display/overflow):');
    var cnt = 0;
    var walkTree = function(el, depth){
      if (cnt >= 70 || depth > 7) return;
      var kids = [];
      for (var i = 0; i < el.children.length; i++) {
        var k = el.children[i], tg = k.tagName;
        if (tg === 'SCRIPT' || tg === 'STYLE' || tg === 'LINK' || tg === 'META' || tg === 'svg' || tg === 'I' || tg === 'SPAN') continue;
        kids.push(k);
      }
      for (var j = 0; j < kids.length && j < 6; j++) {
        if (cnt >= 70) return;
        var k2 = kids[j], cs2 = getComputedStyle(k2), r2 = k2.getBoundingClientRect();
        var c3 = (typeof k2.className === 'string' && k2.className) ? '.' + k2.className.trim().split(/\s+/).slice(0, 2).join('.') : '';
        out.push(new Array(depth + 1).join(' ') + k2.tagName.toLowerCase() + (k2.id ? '#' + k2.id : '') + c3 +
          ' ' + Math.round(r2.height) + 'x' + Math.round(r2.width) + ' @' + Math.round(r2.top) +
          ' ' + cs2.position + '/' + cs2.display + '/' + cs2.overflowY + (cs2.opacity !== '1' ? ' op=' + cs2.opacity : '') + (cs2.visibility !== 'visible' ? ' vis=' + cs2.visibility : '') +
          ' H=' + (k2.style.height || cs2.height));
        cnt++;
        walkTree(k2, depth + 1);
      }
      if (kids.length > 6) { out.push(new Array(depth + 1).join(' ') + '… ещё ' + (kids.length - 6)); }
    };
    walkTree(document.getElementById('applicationHost') || document.body, 1);
    out.push('');
  } catch (e) { out.push('tree error: ' + e); }
  var els = document.querySelectorAll('a,button,input,select,textarea,[role=button],[onclick],[ng-click]');
  var n = 0;
  for (var i = 0; i < els.length && n < 150; i++) {
    var e = els[i];
    if (!vis(e)) continue;
    n++;
    var t = ((e.type === 'password') ? '' : (e.innerText || e.value || '')).trim().replace(/\s+/g,' ').slice(0, 40);
    var c = (typeof e.className === 'string') ? e.className : '';
    var cls = c.split(' ').filter(Boolean).slice(0, 3).join('.');
    var href = e.getAttribute('href');
    var extra = [e.type && 'type=' + e.type, e.name && 'name=' + e.name, e.placeholder && 'ph=' + e.placeholder, href && 'href=' + href.slice(0, 60)].filter(Boolean).join(' ');
    out.push(e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (cls ? '.' + cls : '') + ' "' + t + '" ' + extra);
  }
  out.push('', 'Показано элементов: ' + n);
  return out.join('\n');
})()
