(function(){
  function txt(e){ return ((e.innerText || e.textContent || e.value || '') + '').replace(/\s+/g, ' ').trim(); }
  function vis(e){ try { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (x) { return false; } }
  function uniq(a, n){ var s = {}, o = []; a.forEach(function(t){ if (t && t.length <= 60 && !s[t]) { s[t] = 1; o.push(t); } }); return o.slice(0, n); }
  function list(sel, n){
    var a = [];
    try { [].slice.call(document.querySelectorAll(sel)).forEach(function(e){ if (vis(e)) a.push(txt(e)); }); } catch (x) {}
    return uniq(a, n);
  }
  function count(sel){
    var c = 0;
    try { [].slice.call(document.querySelectorAll(sel)).forEach(function(e){ if (vis(e)) c++; }); } catch (x) {}
    return c;
  }
  // только названия полей, значения (введённые данные) не читаются
  var fields = [];
  try {
    [].slice.call(document.querySelectorAll('input,select,textarea')).forEach(function(e){
      if (e.type === 'password' || e.type === 'hidden' || !vis(e)) return;
      fields.push((e.getAttribute('aria-label') || e.placeholder || e.name || '') + '');
    });
    [].slice.call(document.querySelectorAll('label,mat-label')).forEach(function(e){ if (vis(e)) fields.push(txt(e)); });
  } catch (x) {}
  return JSON.stringify({
    url: location.href,
    title: document.title || '',
    headings: list('h1,h2,h3,[role=heading]', 10),
    buttons: list('button,[role=button],input[type=button],input[type=submit],a.btn', 30),
    menu: list('nav a,nav li,aside a,[role=menuitem],[role=tab],mat-nav-list a,.mat-mdc-list-item,.mat-list-item', 40),
    fields: uniq(fields, 30),
    inputs: count('input:not([type=hidden]),select,textarea'),
    tables: count('table,[role=grid],[role=table]')
  });
})();
