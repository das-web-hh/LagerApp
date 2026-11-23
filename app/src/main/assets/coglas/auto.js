/*
 * Автоматический приём на сайте Coglas.
 * Kotlin вызывает window.__cgAutoTick(jobJson, stJson) раз в ~0,7 с.
 * За один вызов делается не больше одного действия; страница определяется по её содержимому,
 * поэтому если сайт перезагрузился, работа продолжается с того же места.
 * Возвращает JSON: {st:{...}, status:'wait'|'acted'|'need_menu'|'done'|'error', msg:'...', warn:['...']}
 * Кнопку «Провести» (Buchen) скрипт НИКОГДА не нажимает.
 */
(function(){
  if (window.__cgAutoTick) return;

  // ---------- общие помощники ----------
  function norm(s){ return String(s == null ? '' : s).replace(/[\u00AD\u200B\u00A0]/g, ' ').replace(/\s+/g, ' ').trim().toLowerCase(); }
  function txt(e){ try { return norm(e.innerText || e.textContent || e.value || ''); } catch (x) { return ''; } }
  function vis(e){
    try {
      var r = e.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return false;
      var cs = getComputedStyle(e);
      return cs.visibility !== 'hidden' && cs.display !== 'none';
    } catch (x) { return false; }
  }
  function all(sel, root){ try { return [].slice.call((root || document).querySelectorAll(sel)); } catch (x) { return []; } }
  function buttons(root){
    return all('button,a.btn,[role=button],input[type=button],input[type=submit]', root).filter(function(b){
      return vis(b) && !b.disabled && !(b.classList && b.classList.contains('disabled'));
    });
  }
  function findBtn(rx, root){
    var b = buttons(root);
    for (var i = 0; i < b.length; i++) if (rx.test(txt(b[i]))) return b[i];
    return null;
  }
  function click(e){
    try { e.scrollIntoView({ block: 'center' }); } catch (x) {}
    e.click();
  }
  function fire(el, types){
    types.forEach(function(t){
      try { el.dispatchEvent(new Event(t, { bubbles: true })); } catch (x) {}
    });
  }
  function setValue(el, v){
    window.__wmsAllowFocus = true;
    try { el.focus(); } catch (x) {}
    try {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
    } catch (x) { el.value = v; }
    fire(el, ['input', 'change', 'keyup']);
    try { el.blur(); } catch (x) {}
    fire(el, ['blur']);
    setTimeout(function(){ window.__wmsAllowFocus = false; }, 500);
  }
  function title(){
    var h = document.querySelector('.page-heading-title,.navbar-mobile-title');
    if (h && vis(h)) return txt(h);
    var hs = all('h1,h2,[role=heading]').filter(vis);
    return hs.length ? txt(hs[0]) : '';
  }
  function bodyText(){ return norm(document.body ? document.body.innerText : ''); }
  function alertText(){
    var out = [];
    all('.alert-danger,.alert-warning,.toast,[role=alert],.modal.show,.modal.in,[role=dialog],.swal2-popup,.bootbox').forEach(function(e){
      if (vis(e)) { var t = (e.innerText || '').replace(/\s+/g, ' ').trim(); if (t) out.push(t.slice(0, 160)); }
    });
    return out.slice(0, 3).join(' | ');
  }
  // первый элемент по селектору, который идёт ПОСЛЕ подписи lab в документе (или внутри неё) и лежит внутри root
  function nextAfter(lab, selector, root){
    var l = all(selector, root);
    for (var i = 0; i < l.length; i++) {
      var e = l[i];
      if (e !== lab && (lab.compareDocumentPosition(e) & 4)) return e;
    }
    return null;
  }
  // подпись (самый глубокий элемент с нужным текстом) внутри root
  function labelEl(rx, root){
    var c = all('label,div,span,td,th,dt,p,b,strong,h5,h6', root).filter(function(e){
      if (!vis(e)) return false;
      var t = txt(e);
      if (!t || t.length > 60 || !rx.test(t)) return false;
      for (var i = 0; i < e.children.length; i++) if (rx.test(txt(e.children[i]))) return false;
      return true;
    });
    return c.length ? c[0] : null;
  }
  var INPUT = 'input:not([type=checkbox]):not([type=radio]):not([type=hidden]):not([type=button]):not([type=submit]),textarea';
  function inputByLabel(rx, root){
    var lab = labelEl(rx, root);
    if (!lab) return null;
    if (lab.control) return lab.control;
    return nextAfter(lab, INPUT, root);
  }
  function isChecked(cb){
    if (cb.checked !== undefined && cb.tagName === 'INPUT') return !!cb.checked;
    return cb.getAttribute('aria-checked') === 'true';
  }
  function same(a, b){ return norm(a) === norm(b); }
  function bump(st, key){ st.s[key] = (st.s[key] || 0) + 1; return st.s[key]; }

  // ---------- определение страницы ----------
  var RX_ADD_NOTE_POS = /накладн\S*\s+с\s+открыт\S*\s+количеств\S*\s+заказа\s*\(\s*поз|lieferschein.*offen.*\(\s*pos/;
  var RX_SUBMIT_NOTE = /^добавить товарн\S*\s+накладн\S*$|^lieferschein (hinzufügen|anlegen|erstellen)$/;
  var RX_MANUAL_POS = /ручное оприходование\s*\(\s*поз|manuelle.*\(\s*pos/;
  var RX_PROCESS = /^обработать$|^verarbeit/;
  var RX_RESET = /^сбросить$|^zurücksetzen$/;
  var RX_BOOK = /^провести$|^buchen$/;

  function kind(){
    if (findBtn(RX_PROCESS) && findBtn(RX_RESET)) return 'collect';
    if (findBtn(RX_BOOK)) return 'goodsin';
    if (findBtn(RX_SUBMIT_NOTE) && findBtn(/^отмена$|^abbrechen$/)) return 'addnote';
    if (findBtn(RX_MANUAL_POS)) return 'note';
    if (findBtn(RX_ADD_NOTE_POS)) return 'order';
    var t = title();
    if (/^(заказы|bestellungen)$/.test(t) && searchInput()) return 'orders';
    return 'other';
  }
  var ORDER = ['order', 'addnote', 'note', 'collect', 'goodsin'];

  // ---------- позиции в списках (заказ, накладная) ----------
  var RX_ROW_EXCLUDE = /деактивирован|требуется проверка|всегда выбирать|deaktiviert|prüfung erforderlich|immer alle/;
  function posRows(){
    var out = [];
    all('input[type=checkbox],[role=checkbox]').forEach(function(cb){
      var a = cb, found = null;
      for (var i = 0; i < 8 && a; i++, a = a.parentElement) {
        var raw = a.innerText || '';
        if (/№:?\s*[A-Za-z0-9]/.test(raw)) { found = a; break; }
      }
      if (!found) return;
      var t = txt(found);
      if (t.length > 700 || RX_ROW_EXCLUDE.test(t)) return;
      if (!/stck|mhd|шт\b/.test(t)) return;
      var boxes = all('input[type=checkbox],[role=checkbox]', found);
      if (boxes.length !== 1 || boxes[0] !== cb) return;
      var m = /№:?\s*([A-Za-z0-9._\-]+)\s*,/.exec(found.innerText || '');
      out.push({
        cb: cb, el: found, article: m ? m[1] : '',
        done: /выполнено|erledigt|abgeschlossen/.test(t)
      });
    });
    return out;
  }
  function wantedArticle(job, art){
    for (var i = 0; i < job.items.length; i++) if (job.items[i].article && same(job.items[i].article, art)) return true;
    return false;
  }
  function selectedCount(){
    var m = /(\d+)\s*(выбрано|ausgewählt)/.exec(bodyText());
    return m ? parseInt(m[1], 10) : null;
  }
  // ввести текст в поле «Поиск» так же, как это делает человек
  function doSearch(si, text){
    setValue(si, text);
    fire(si, ['keydown', 'keypress', 'keyup']);
    try { si.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, which: 13, bubbles: true })); } catch (x) {}
    try { si.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', keyCode: 13, which: 13, bubbles: true })); } catch (x) {}
  }
  // короткое слово из названия для запасного поиска: «… (Apfel)» → «Apfel», иначе первые слова
  function shortName(name){
    var m = /\(([^)]{3,30})\)\s*$/.exec(name || '');
    if (m) return m[1].trim();
    return String(name || '').split(/\s+/).slice(0, 3).join(' ');
  }
  // Запасной вариант без поиска (нет поля «Поиск»): отметить нужные строки в текущем списке.
  // Строки со статусом «Выполнено» ТОЖЕ берём: на сайте их выбирают так же, как «В работе».
  function selectPositionsPlain(job, st){
    var rows = posRows();
    if (!rows.length) {
      if (bump(st, 'noRows') > 25) return { state: 'error', msg: 'Не вижу список позиций на странице.' };
      return { state: 'wait', msg: 'Жду список позиций…' };
    }
    var wanted = rows.filter(function(r){ return wantedArticle(job, r.article); });
    var needArts = job.items.filter(function(i){ return i.article; }).length;
    if (wanted.length < needArts && !st.s.big) {
      var b50 = all('button,a,li,span').filter(vis).filter(function(e){ return txt(e) === '50' && e.children.length === 0; })[0];
      if (b50) { st.s.big = 1; click(b50); return { state: 'acted', msg: 'Показываю 50 позиций на странице…' }; }
    }
    if (!wanted.length) return { state: 'error', msg: 'Принимаемых позиций нет в этом списке (проверьте артикулы).' };
    var changed = 0;
    rows.forEach(function(r){
      var want = wanted.indexOf(r) >= 0;
      if (isChecked(r.cb) !== want) { click(r.cb); changed++; }
    });
    if (changed) return { state: 'acted', msg: 'Выбираю позиции (' + wanted.length + ')…' };
    return { state: 'ready', msg: '' };
  }
  // Основной вариант, как вручную: сбросить выбор → для каждого товара набрать артикул в «Поиск»
  // (если не нашёл — слово из названия) → отметить строку → в конце очистить поиск и проверить «N выбрано».
  // Возвращает {state:'wait'|'acted'|'ready'|'error', msg, warn?}
  function selectPositions(job, st){
    var si = searchInput();
    if (!si) return selectPositionsPlain(job, st);
    var now = Date.now();
    var S = st.s.sel;
    if (!S) S = st.s.sel = { i: 0, tryNo: 0, t: 0, found: 0, miss: [], stage: 'reset', at: now };
    var items = job.items;

    // 0) снять старый выбор со всех страниц
    if (S.stage === 'reset') {
      var cnt = selectedCount();
      if (cnt && !S.resetClicked) {
        var rb = findBtn(/снять выбор со всех страниц|auswahl.*aller seiten/);
        S.resetClicked = now;
        if (rb) { click(rb); return { state: 'acted', msg: 'Снимаю старый выбор…' }; }
      }
      if (S.resetClicked && now - S.resetClicked < 800) return { state: 'wait', msg: '' };
      S.stage = 'search'; S.at = now;
    }

    // 1) поиск и отметка по одному товару
    if (S.stage === 'search') {
      while (S.i < items.length && !items[S.i].article) S.i++;
      if (S.i < items.length) {
        var it = items[S.i];
        if (!S.t) {
          var text = S.tryNo === 0 ? it.article : shortName(it.name);
          doSearch(si, text);
          S.t = now;
          return { state: 'acted', msg: 'Ищу «' + text + '» (' + (S.i + 1) + '/' + items.length + ')…' };
        }
        var elapsed = now - S.t;
        var vr = posRows();
        var mine = vr.filter(function(r){ return same(r.article, it.article); });
        var onlyMine = vr.length > 0 && vr.length === mine.length;
        if (mine.length && (onlyMine || elapsed > 2500) && elapsed > 600) {
          // если артикул встретился несколько раз — берём незавершённые, иначе все
          var pick = mine.filter(function(r){ return !r.done; });
          if (!pick.length) pick = mine;
          var w = [];
          if (mine.length > 1) w.push('Артикул ' + it.article + ' встречается в заказе ' + mine.length + ' раз(а) — проверьте выбор.');
          pick.forEach(function(r){ if (!isChecked(r.cb)) click(r.cb); });
          S.found += pick.length;
          S.i++; S.t = 0; S.tryNo = 0;
          return { state: 'acted', msg: 'Отметил ' + it.article + ' (' + S.found + ')', warn: w };
        }
        if (elapsed > 6500) {
          if (S.tryNo === 0 && shortName(it.name)) { S.tryNo = 1; S.t = 0; return { state: 'acted', msg: '' }; }
          S.miss.push(it.article);
          S.i++; S.t = 0; S.tryNo = 0;
          return { state: 'acted', msg: '', warn: ['Артикул ' + it.article + ' («' + it.name + '») не найден в списке — отметьте вручную.'] };
        }
        return { state: 'wait', msg: '' };
      }
      S.stage = 'clear'; S.at = 0;
    }

    // 2) очистить поиск, чтобы выбор был виден целиком
    if (S.stage === 'clear') {
      if (!S.at) {
        doSearch(si, '');
        S.at = now;
        return { state: 'acted', msg: 'Очищаю поиск…' };
      }
      if (now - S.at < 1800) return { state: 'wait', msg: '' };
      S.stage = 'check';
    }

    // 3) проверить «N выбрано»
    if (S.stage === 'check') {
      if (!S.found) return { state: 'error', msg: 'Принимаемых позиций нет в этом списке (проверьте артикулы).' };
      var c = selectedCount();
      if (c !== null && c !== S.found) {
        if (bump(st, 'chk') > 8) return { state: 'error', msg: 'Выбрано на сайте: ' + c + ', нужно: ' + S.found + '. Проверьте список.' };
        return { state: 'wait', msg: 'Проверяю выбор…' };
      }
      return { state: 'ready', msg: '', warn: S.miss.length ? ['Не найдены артикулы: ' + S.miss.join(', ')] : [] };
    }
    return { state: 'wait', msg: '' };
  }

  // ---------- шаг 0: заказ ----------
  function searchInput(){
    var l = all('input[type=search],input[type=text],input:not([type])').filter(vis);
    for (var i = 0; i < l.length; i++) {
      var p = norm(l[i].placeholder || l[i].getAttribute('aria-label') || '');
      if (/поиск|such/.test(p)) return l[i];
    }
    return null;
  }
  function orderLink(num){
    var l = all('a,td,span,div,b').filter(vis).filter(function(e){ return same(txt(e), num) && e.children.length === 0; });
    return l.length ? l[0] : null;
  }
  function stepOrder(job, st, k){
    if (k === 'order') {
      if (bodyText().indexOf(norm(job.order)) < 0) {
        // открыт другой заказ
        if (bump(st, 'wrongOrder') > 2) return { status: 'need_menu', msg: 'Открыт другой заказ, перехожу к списку заказов…' };
        return { status: 'wait', msg: 'Проверяю номер заказа…' };
      }
      var r = selectPositions(job, st);
      if (r.state === 'error') return { status: 'error', msg: r.msg };
      if (r.state === 'wait') return { status: 'wait', msg: r.msg };
      if (r.state === 'acted') return { status: 'acted', msg: r.msg, warn: r.warn };
      if (st.s.clicked && Date.now() - st.s.clicked < 9000) return { status: 'wait', msg: 'Жду форму накладной…' };
      if (st.s.clicked) return { status: 'error', msg: 'Форма накладной не открылась. ' + alertText() };
      st.s.clicked = Date.now();
      click(findBtn(RX_ADD_NOTE_POS));
      return { status: 'acted', msg: 'Нажимаю «Добавить накладную … (поз.)»', warn: r.warn };
    }
    if (k === 'orders') {
      var link = orderLink(job.order);
      if (link) { click(link); st.s.opened = Date.now(); return { status: 'acted', msg: 'Открываю заказ ' + job.order }; }
      var si = searchInput();
      if (si && !st.s.searched) {
        st.s.searched = Date.now();
        setValue(si, job.order);
        fire(si, ['keydown', 'keypress', 'keyup']);
        try { si.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, which: 13, bubbles: true })); } catch (x) {}
        try { si.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', keyCode: 13, which: 13, bubbles: true })); } catch (x) {}
        return { status: 'acted', msg: 'Ищу заказ ' + job.order + '…' };
      }
      if (st.s.searched && Date.now() - st.s.searched > 12000) return { status: 'error', msg: 'Заказ ' + job.order + ' не найден в списке.' };
      return { status: 'wait', msg: 'Жду результат поиска…' };
    }
    if (st.s.opened && Date.now() - st.s.opened < 10000) return { status: 'wait', msg: 'Жду страницу заказа…' };
    return { status: 'need_menu', msg: 'Открываю список заказов…' };
  }

  // ---------- шаг 1: форма «Добавить товарную накладную» ----------
  // что видно на форме накладной: значения нужных полей и тексты ошибок (для отчёта)
  function formDiag(f){
    var out = [];
    try {
      f.forEach(function(x){
        var el = inputByLabel(x.rx);
        out.push(x.n + '=' + (el ? '«' + el.value + '»' : 'поле не найдено') + (x.v ? ' (нужно «' + x.v + '»)' : ' (пусто в задании)'));
      });
      var errs = all('.invalid-feedback,.text-danger,.has-error,.help-block,[class*=error],[class*=invalid],.field-validation-error').filter(vis)
        .map(function(e){ return (e.innerText || '').replace(/\s+/g, ' ').trim(); }).filter(Boolean);
      if (errs.length) out.push('ошибки на форме: ' + errs.slice(0, 5).join(' | '));
      var empt = all('input,select,textarea').filter(vis).filter(function(e){
        return (e.required || e.getAttribute('aria-required') === 'true') && !String(e.value || '').trim();
      }).map(function(e){ return e.placeholder || e.name || e.id || '?'; });
      if (empt.length) out.push('пустые обязательные: ' + empt.slice(0, 6).join(', '));
    } catch (x) {}
    return out.join('; ');
  }
  function stepAddNote(job, st){
    var f = [
      { rx: /^внеш\.?\s*номер$|^extern\S*\s*nummer$/, v: job.waybill, n: 'номер накладной' },
      { rx: /^госномер грузовика$|^lkw.?kennzeichen$|^kennzeichen/, v: job.truck, n: 'номер грузовика' },
      { rx: /^дата поставки$|^lieferdatum$/, v: job.date, n: 'дата приема' }
    ];
    var warn = [];
    if (!st.s.filled) {
      f.forEach(function(x){
        if (!x.v) return;
        var el = inputByLabel(x.rx);
        if (!el) { warn.push('Поле «' + x.n + '» не найдено — заполните вручную.'); return; }
        setValue(el, x.v);
      });
      st.s.filled = Date.now();
      return { status: 'acted', msg: 'Заполняю данные накладной…', warn: warn };
    }
    if (Date.now() - st.s.filled < 500) return { status: 'wait', msg: '' };
    if (!st.s.clicked) {
      var w2 = [], refilled = false;
      f.forEach(function(x){
        if (!x.v) return;
        var el = inputByLabel(x.rx);
        if (el && norm(el.value) !== norm(x.v)) {
          if ((st.s.refill || 0) < 2) { setValue(el, x.v); refilled = true; }
          else w2.push('«' + x.n + '»: на сайте «' + el.value + '», а нужно «' + x.v + '» — проверьте.');
        }
      });
      if (refilled) {
        st.s.refill = (st.s.refill || 0) + 1;
        st.s.filled = Date.now();
        return { status: 'acted', msg: 'Проверяю поля…' };
      }
      var sb = findBtn(RX_SUBMIT_NOTE);
      if (!sb) return { status: 'wait', msg: 'Жду кнопку «Добавить товарную накладную»…' };
      st.s.clicked = Date.now();
      click(sb);
      return { status: 'acted', msg: 'Нажимаю «Добавить товарную накладную»', warn: w2 };
    }
    if (Date.now() - st.s.clicked > 12000) return { status: 'error', msg: 'Накладная не создалась. ' + alertText() + ' ' + formDiag(f) };
    return { status: 'wait', msg: 'Жду страницу накладной…' };
  }

  // ---------- шаг 2: страница накладной ----------
  function stepNote(job, st){
    var r = selectPositions(job, st);
    if (r.state === 'error') return { status: 'error', msg: r.msg };
    if (r.state === 'wait') return { status: 'wait', msg: r.msg };
    if (r.state === 'acted') return { status: 'acted', msg: r.msg, warn: r.warn };
    if (st.s.clicked && Date.now() - st.s.clicked < 9000) return { status: 'wait', msg: 'Жду окно оприходования…' };
    if (st.s.clicked) return { status: 'error', msg: 'Окно оприходования не открылось. ' + alertText() };
    st.s.clicked = Date.now();
    click(findBtn(RX_MANUAL_POS));
    return { status: 'acted', msg: 'Нажимаю «Ручное оприходование (поз.)»', warn: r.warn };
  }

  // ---------- шаг 3: окно «Оприходование» ----------
  var RX_ART = /^(артикул|artikel)$/;
  var RX_QTY = /^(количество|menge)$/;
  var RX_MHD = /срок годности|mhd|mindesthaltbarkeit/;
  var RX_UNIT = /^(грузовая единица|ladeeinheit|lagereinheit|ladungsträger)$/;
  var RX_TYPE = /^(тип|typ)$/;
  var RX_SPLIT = /^(разделить|teilen|aufteilen)$/;
  var RX_ENTER = /^(ввод|eingabe)$/;

  function rowOfLabel(lab){
    var a = lab, last = lab;
    while (a.parentElement && a.parentElement !== document.body) {
      var n = 0;
      all('*', a.parentElement).forEach(function(e){ if (RX_ART.test(txt(e)) && e.children.length === 0) n++; });
      if (n > 1) break;
      a = a.parentElement; last = a;
    }
    return last;
  }
  function collectRows(){
    var labs = all('label,div,span,td,th,b,strong,p').filter(function(e){ return vis(e) && e.children.length === 0 && RX_ART.test(txt(e)); });
    var rows = [];
    labs.forEach(function(l){
      var el = rowOfLabel(l);
      var raw = el.innerText || '';
      var m = /(?:артикул|artikel)\s*:?\s*([A-Za-z0-9._\-]+)/i.exec(raw);
      var unitLab = labelEl(RX_UNIT, el), typeLab = labelEl(RX_TYPE, el), qtyLab = labelEl(RX_QTY, el);
      var mhdLab = labelEl(RX_MHD, el), splitLab = labelEl(RX_SPLIT, el), enterLab = labelEl(RX_ENTER, el);
      rows.push({
        el: el, article: m ? m[1] : '',
        qty: qtyLab ? nextAfter(qtyLab, INPUT, el) : null,
        date: mhdLab ? nextAfter(mhdLab, INPUT, el) : null,
        unit: unitLab ? nextAfter(unitLab, 'select', el) : null,
        type: typeLab ? nextAfter(typeLab, 'select', el) : null,
        split: splitLab ? nextAfter(splitLab, 'button,a.btn,[role=button]', el) : null,
        enter: enterLab ? nextAfter(enterLab, 'button,a.btn,[role=button]', el) : null
      });
    });
    return rows;
  }
  function pickOption(sel, rxSrc, letter){
    var opts = [].slice.call(sel.options || []);
    for (var i = 0; i < opts.length; i++) {
      var t = norm(opts[i].text);
      if (!t) continue;
      if (letter) { if (new RegExp('^' + letter.toLowerCase() + '(\\b|$)').test(t)) return opts[i]; }
      else if (rxSrc && new RegExp(rxSrc, 'i').test(t)) return opts[i];
    }
    return null;
  }
  function setSelect(sel, opt){
    window.__wmsAllowFocus = true;
    sel.value = opt.value;
    fire(sel, ['input', 'change']);
    setTimeout(function(){ window.__wmsAllowFocus = false; }, 500);
  }
  // строки задания в порядке форм на сайте: часть A, B, C… (у части A обычно паллет), внутри — по упаковке
  function sortedJobRows(item){
    return item.rows.slice().sort(function(a, b){
      var p = a.part.localeCompare(b.part);
      return p !== 0 ? p : a.pack.toLowerCase().localeCompare(b.pack.toLowerCase());
    });
  }
  function modalInput(){
    var l = all('.modal.show input,.modal.in input,[role=dialog] input,.swal2-popup input,.bootbox input').filter(function(e){
      return vis(e) && /^(text|search|number|tel|)$/i.test(e.type || '');
    });
    return l.length ? l[0] : null;
  }
  function modalRoot(){
    var l = all('.modal.show,.modal.in,[role=dialog],.swal2-popup,.bootbox').filter(vis);
    return l.length ? l[0] : null;
  }

  function stepCollect(job, st){
    var rows = collectRows();
    if (!rows.length) {
      if (bump(st, 'noRows') > 25) return { status: 'error', msg: 'Не вижу строки в окне оприходования.' };
      return { status: 'wait', msg: 'Жду окно оприходования…' };
    }
    var warn = [];
    // 1) разделить строки: на каждый артикул нужно столько строк, сколько видов упаковки/частей
    if (!st.s.splitDone) {
      for (var i = 0; i < job.items.length; i++) {
        var it = job.items[i];
        var ex = rows.filter(function(r){ return same(r.article, it.article); });
        if (!ex.length) continue;
        if (ex.length < it.rows.length) {
          if (st.s.lastSplit && Date.now() - st.s.lastSplit < 1500) return { status: 'wait', msg: '' };
          var tries = bump(st, 'sp_' + i);
          if (tries > it.rows.length + 4) return { status: 'error', msg: 'Не удалось разделить строку «' + it.name + '».' };
          if (!ex[0].split) return { status: 'error', msg: 'Не нашёл кнопку «Разделить» у «' + it.name + '».' };
          st.s.lastSplit = Date.now();
          click(ex[0].split);
          return { status: 'acted', msg: 'Разделяю «' + it.name + '» (' + (ex.length + 1) + '/' + it.rows.length + ')…' };
        }
      }
      st.s.splitDone = 1;
    }
    // 2) заполнить строки
    if (!st.s.filled) {
      job.items.forEach(function(it){
        var ex = rows.filter(function(r){ return same(r.article, it.article); });
        if (!ex.length) { warn.push('Артикул ' + it.article + ' не найден в окне оприходования.'); return; }
        var jr = sortedJobRows(it);
        for (var k = 0; k < jr.length && k < ex.length; k++) {
          var r = ex[k], j = jr[k];
          if (r.qty) setValue(r.qty, j.qty); else warn.push('«' + it.name + '»: нет поля «Количество».');
          if (j.expiry) { if (r.date) setValue(r.date, j.expiry); else warn.push('«' + it.name + '»: нет поля срока годности.'); }
          if (r.unit && j.part) { var uo = pickOption(r.unit, null, j.part); if (uo) setSelect(r.unit, uo); }
          if (r.type && j.typeRx) {
            var to = pickOption(r.type, j.typeRx, null);
            if (to) setSelect(r.type, to);
            else {
              var have = [].slice.call(r.type.options || []).map(function(o){ return (o.text || '').replace(/\s+/g, ' ').trim(); }).filter(function(t){ return t && !/^выберите/i.test(t); });
              warn.push('«' + it.name + '»: тип «' + j.pack + '» не найден в списке. На сайте есть: ' + (have.slice(0, 20).join(', ') || 'пусто') + '.');
            }
          }
        }
        if (ex.length > jr.length) warn.push('«' + it.name + '»: на сайте лишних строк: ' + (ex.length - jr.length) + ' — проверьте.');
      });
      st.s.filled = Date.now();
      return { status: 'acted', msg: 'Заполняю срок, количество и тип…', warn: warn };
    }
    if (Date.now() - st.s.filled < 700) return { status: 'wait', msg: '' };
    // 3) проверить, что значения на месте (сайт мог их сбросить)
    if (!st.s.verified) {
      var bad = 0;
      job.items.forEach(function(it){
        var ex = rows.filter(function(r){ return same(r.article, it.article); });
        var jr = sortedJobRows(it);
        for (var k = 0; k < jr.length && k < ex.length; k++) {
          var r = ex[k], j = jr[k];
          if (r.qty && norm(r.qty.value).replace(',', '.') !== norm(j.qty).replace(',', '.')) {
            if ((st.s.reQty || 0) < 2) { setValue(r.qty, j.qty); bad++; }
            else warn.push('«' + it.name + '»: количество на сайте «' + r.qty.value + '», нужно «' + j.qty + '».');
          }
          if (j.expiry && r.date && norm(r.date.value) !== norm(j.expiry)) {
            if ((st.s.reQty || 0) < 2) { setValue(r.date, j.expiry); bad++; }
            else warn.push('«' + it.name + '»: срок годности на сайте «' + r.date.value + '», нужно «' + j.expiry + '».');
          }
        }
      });
      if (bad) { st.s.reQty = (st.s.reQty || 0) + 1; st.s.filled = Date.now(); return { status: 'acted', msg: 'Перепроверяю значения…', warn: warn }; }
      st.s.verified = 1;
      return { status: 'acted', msg: 'Значения проверены.', warn: warn };
    }
    // 4) номера упаковок (имя, отсканированное в приложении)
    var todo = [];
    job.items.forEach(function(it){
      var ex = rows.filter(function(r){ return same(r.article, it.article); });
      var jr = sortedJobRows(it);
      for (var k = 0; k < jr.length && k < ex.length; k++) if (jr[k].packName) todo.push({ row: ex[k], name: jr[k].packName, id: it.article + '#' + k });
    });
    var pn = st.s.pn || 0;
    if (pn < todo.length) {
      var t = todo[pn];
      if (!st.s.pnStage) {
        if (!t.row.enter) { st.s.pn = pn + 1; return { status: 'acted', msg: '', warn: ['Нет кнопки «Ввод» для номера упаковки ' + t.name + ' — введите вручную.'] }; }
        st.s.pnInputs = all('input', t.row.el).length;
        st.s.pnStage = 1; st.s.pnAt = Date.now();
        click(t.row.enter);
        return { status: 'acted', msg: 'Ввожу номер упаковки ' + t.name + '…' };
      }
      if (st.s.pnStage === 1) {
        var mi = modalInput();
        var inl = null;
        if (!mi) { var ins = all('input', t.row.el).filter(function(e){ return vis(e) && !e.disabled; }); if (ins.length > st.s.pnInputs) inl = ins[ins.length - 1]; }
        var target = mi || inl;
        if (target) {
          setValue(target, t.name);
          try { target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, which: 13, bubbles: true })); } catch (x) {}
          try { target.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', keyCode: 13, which: 13, bubbles: true })); } catch (x) {}
          var mr = modalRoot();
          if (mr) {
            var ok = findBtn(/^(ok|ввод|применить|готово|да|übernehmen|bestätigen|speichern|ja)$/, mr);
            if (ok) click(ok);
          }
          st.s.pnStage = 2; st.s.pnAt = Date.now();
          return { status: 'acted', msg: 'Подтверждаю номер упаковки…' };
        }
        if (Date.now() - st.s.pnAt > 4000) {
          st.s.pn = pn + 1; st.s.pnStage = 0;
          return { status: 'acted', msg: '', warn: ['Поле для номера упаковки ' + t.name + ' не открылось — введите вручную.'] };
        }
        return { status: 'wait', msg: '' };
      }
      if (st.s.pnStage === 2) {
        if (Date.now() - st.s.pnAt < 900) return { status: 'wait', msg: '' };
        var w3 = [];
        if (modalRoot()) {
          var ok2 = findBtn(/^(ok|закрыть|готово|schließen)$/, modalRoot());
          if (ok2) click(ok2);
        }
        if (txt(t.row.el).indexOf(norm(t.name)) < 0) w3.push('Номер упаковки ' + t.name + ' не появился в строке — проверьте.');
        st.s.pn = pn + 1; st.s.pnStage = 0;
        return { status: 'acted', msg: '', warn: w3 };
      }
    }
    // 5) обработать
    if (modalRoot()) return { status: 'wait', msg: 'Жду закрытия окна…' };
    if (!st.s.clicked) {
      var pb = findBtn(RX_PROCESS);
      if (!pb) return { status: 'wait', msg: 'Жду кнопку «Обработать»…' };
      st.s.clicked = Date.now();
      click(pb);
      return { status: 'acted', msg: 'Нажимаю «Обработать»' };
    }
    if (Date.now() - st.s.clicked > 15000) return { status: 'error', msg: 'После «Обработать» страница прихода не открылась. ' + alertText() };
    return { status: 'wait', msg: 'Жду страницу прихода товара…' };
  }

  // ---------- шаг 4: «Приход товара», кнопка «Провести» ----------
  function stepGoodsIn(){
    var b = findBtn(RX_BOOK);
    if (b) { try { b.scrollIntoView({ block: 'center' }); } catch (x) {} }
    return { status: 'done', msg: 'Готово. Проверьте и нажмите «Провести» сами.' };
  }


  // при ошибке: показать место, где остановились (окно с сообщением или кнопку текущего шага), с красной рамкой
  window.__cgAutoFocus = function(phase){
    try {
      var el = null;
      var al = all('.alert-danger,.alert-warning,.toast,[role=alert],.modal.show,.modal.in,[role=dialog],.swal2-popup,.bootbox,.invalid-feedback,.text-danger').filter(vis);
      if (al.length) el = al[0];
      if (!el) {
        var rx = { order: RX_ADD_NOTE_POS, addnote: RX_SUBMIT_NOTE, note: RX_MANUAL_POS, collect: RX_PROCESS, goodsin: RX_BOOK }[phase];
        if (rx) el = findBtn(rx);
      }
      if (!el) return 'none';
      el.scrollIntoView({ block: 'center' });
      el.style.outline = '3px solid #d32f2f';
      el.style.outlineOffset = '2px';
      setTimeout(function(){ try { el.style.outline = ''; el.style.outlineOffset = ''; } catch (x) {} }, 15000);
      return 'ok';
    } catch (x) { return 'err'; }
  };

  // ---------- зонд даты: один раз открыть форму накладной, прочитать «Дата поставки» и закрыть форму ----------
  // Накладная НЕ создаётся: кнопку «Добавить товарную накладную» зонд не нажимает, форма закрывается кнопкой «Отмена».
  window.__cgProbeTick = function(jobJson, stJson){
    var res;
    var st = { phase: 'order', s: {} };
    try { st = JSON.parse(stJson) || st; if (!st.s) st.s = {}; } catch (x) {}
    try {
      var job = JSON.parse(jobJson);
      var k = kind();
      if (k === 'addnote') {
        var el = inputByLabel(/^дата поставки$|^lieferdatum$/);
        var v = el ? String(el.value || '').trim() : '';
        if (!v) {
          if (bump(st, 'noDate') > 10) res = { status: 'error', msg: 'Поле даты на форме накладной не найдено.' };
          else res = { status: 'wait', msg: '' };
        } else {
          var cancel = findBtn(/^отмена$|^abbrechen$/);
          if (cancel) click(cancel);
          res = { status: 'done', date: v, msg: 'Дата на сайте: ' + v };
        }
      } else if (k === 'order' || k === 'orders' || k === 'other') {
        res = stepOrder(job, st, k);
      } else {
        res = { status: 'error', msg: 'Неожиданная страница: ' + k };
      }
    } catch (e) {
      res = { status: 'error', msg: 'Сбой скрипта: ' + (e && e.message ? e.message : e) };
    }
    res.st = st;
    return JSON.stringify(res);
  };

  // ---------- вход ----------
  window.__cgAutoTick = function(jobJson, stJson){
    var res;
    var st = { phase: 'order', s: {} };
    try { st = JSON.parse(stJson) || st; if (!st.s) st.s = {}; } catch (x) {}
    try {
      var job = JSON.parse(jobJson);
      var k = kind();
      var ki = ORDER.indexOf(k), pi = ORDER.indexOf(st.phase);
      if (ki > pi) { st.phase = k; st.s = {}; pi = ki; }
      if (st.phase === 'order') res = stepOrder(job, st, k);
      else if (k !== st.phase) res = { status: 'wait', msg: 'Жду страницу…' };
      else if (st.phase === 'addnote') res = stepAddNote(job, st);
      else if (st.phase === 'note') res = stepNote(job, st);
      else if (st.phase === 'collect') res = stepCollect(job, st);
      else res = stepGoodsIn();
    } catch (e) {
      res = { status: 'error', msg: 'Сбой скрипта: ' + (e && e.message ? e.message : e) };
    }
    res.st = st;
    res.kind = (function(){ try { return kind(); } catch (x) { return '?'; } })();
    return JSON.stringify(res);
  };
})();
