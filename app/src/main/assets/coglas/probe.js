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
