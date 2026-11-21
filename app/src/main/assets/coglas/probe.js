(function(){
  function vis(e){ var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; }
  var out = ['URL: ' + location.href, 'TITLE: ' + document.title, ''];
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
