(function(){
  function txt(e){ return ((e.innerText || e.textContent || e.value || '') + '').replace(/\s+/g, ' ').trim(); }
  function vis(e){ try { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (x) { return false; } }
  function uniq(a, n){ var s = {}, o = []; a.forEach(function(t){ if (t && t.length <= 70 && !s[t]) { s[t] = 1; o.push(t); } }); return o.slice(0, n); }
  // верхняя панель сайта и боковое меню в данные страницы не входят
  function inChrome(e){ try { return !!e.closest('nav,header,.navbar,.navbar-mobile,.topbar-menu,.sidebar,aside'); } catch (x) { return false; } }
  function label(e){
    var t = (e.getAttribute('aria-label') || e.getAttribute('title') || e.placeholder || '') + '';
    if (!t && e.id) { try { var l = document.querySelector('label[for="' + e.id + '"]'); if (l) t = txt(l); } catch (x) {} }
    if (!t) { var p = e.closest('label,.form-group,.mb-3,.form-floating,mat-form-field'); if (p) { var l2 = p.querySelector('label,mat-label'); if (l2) t = txt(l2); } }
    if (!t) t = e.name || '';
    return t.trim();
  }
  var head = document.querySelector('.page-heading-title,.navbar-mobile-title');
  var title = head ? txt(head) : (document.title || '');
  var onMap = !!document.getElementById('searchDashboard') && document.querySelectorAll('a.dashboard-menu-link').length >= 20;

  var buttons = [];
  [].slice.call(document.querySelectorAll('button,[role=button],input[type=button],input[type=submit],a.btn')).forEach(function(e){
    if (!vis(e) || inChrome(e)) return;
    buttons.push(txt(e) || (e.getAttribute('aria-label') || e.getAttribute('title') || ''));
  });

  var tabs = [];
  [].slice.call(document.querySelectorAll('[role=tab],.nav-tabs .nav-link,.nav-pills .nav-link')).forEach(function(e){ if (vis(e) && !inChrome(e)) tabs.push(txt(e)); });

  var columns = [], tables = 0, rows = 0;
  [].slice.call(document.querySelectorAll('table,[role=grid],[role=table]')).forEach(function(t){
    if (!vis(t) || inChrome(t)) return;
    tables++;
    var ths = [].slice.call(t.querySelectorAll('th,[role=columnheader]')).filter(vis).map(txt).filter(function(x){ return x; });
    ths.forEach(function(h){ columns.push(h); });
    var rs = t.querySelectorAll('tbody tr,[role=row]');
    var n = 0;
    for (var i = 0; i < rs.length; i++) { if (vis(rs[i]) && !rs[i].querySelector('th,[role=columnheader]')) n++; }
    rows += n;
  });

  // только названия и тип полей; введённые значения не читаются
  var fields = [];
  [].slice.call(document.querySelectorAll('input,select,textarea')).forEach(function(e){
    if (e.type === 'password' || e.type === 'hidden' || !vis(e) || inChrome(e)) return;
    var l = label(e);
    if (e.type === 'checkbox' || e.type === 'radio') { if (l) fields.push(l + ' [галочка]'); return; }
    if (e.tagName === 'SELECT') { if (l) fields.push(l + ' [список]'); return; }
    if (/date/i.test(e.type || '')) { fields.push((l || 'Дата') + ' [дата]'); return; }
    if (l) fields.push(l);
  });

  var headings = [];
  [].slice.call(document.querySelectorAll('h1,h2,h3,[role=heading]')).forEach(function(e){ if (vis(e) && !inChrome(e)) headings.push(txt(e)); });

  return JSON.stringify({
    map: onMap,
    title: title,
    headings: uniq(headings, 10),
    buttons: uniq(buttons, 40),
    tabs: uniq(tabs, 20),
    columns: uniq(columns, 60),
    fields: uniq(fields, 40),
    tables: tables,
    rows: rows
  });
})();
