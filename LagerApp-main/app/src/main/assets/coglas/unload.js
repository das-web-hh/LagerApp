/*
 * Автозаполнение формы «Добавить накладную» на сайте Coglas данными из «Работа → Разгрузка».
 * Приложение кладёт в window.__cgUnload список разгрузок: [{id, eb, car, date:'дд.мм.гггг', doc}].
 * Когда сайт сам подставил в поле «Номер» номер вида EB2613834-02, берётся только EB и ровно 7 цифр (всё после тире отбрасывается);
 * по этому EB находится разгрузка и заполняются «Госномер грузовика» (если поле пустое), «Внеш. номер» — номер накладной / Lieferschein (если поле пустое) и «Дата поставки» (если дата другая).
 * Кнопку «Добавить товарную накладную» скрипт НЕ нажимает.
 */
(function(){
  if (window.__cgUnloadInit) return;
  window.__cgUnloadInit = true;

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
  var INPUT = 'input:not([type=checkbox]):not([type=radio]):not([type=hidden]):not([type=button]):not([type=submit]),textarea';
  function labelEl(rx){
    var c = all('label,div,span,td,th,dt,p,b,strong').filter(function(e){
      if (!vis(e)) return false;
      var t = txt(e);
      if (!t || t.length > 60 || !rx.test(t)) return false;
      for (var i = 0; i < e.children.length; i++) if (rx.test(txt(e.children[i]))) return false;
      return true;
    });
    return c.length ? c[0] : null;
  }
  function inputByLabel(rx){
    var lab = labelEl(rx);
    if (!lab) return null;
    if (lab.control) return lab.control;
    var l = all(INPUT);
    for (var i = 0; i < l.length; i++) {
      var e = l[i];
      if (e !== lab && (lab.compareDocumentPosition(e) & 4)) return e;
    }
    return null;
  }
  function fire(el, types){
    types.forEach(function(t){ try { el.dispatchEvent(new Event(t, { bubbles: true })); } catch (x) {} });
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

  var RX_NUM = /^номер$|^nummer$/;
  var RX_TRUCK = /^госномер грузовика$|^lkw.?kennzeichen$|^kennzeichen/;
  var RX_EXT = /^внеш\.?\s*номер$|^extern\S*\s*nummer$/;
  var RX_DATE = /^дата поставки$|^lieferdatum$/;
  var RX_SUBMIT = /^добавить товарн\S*\s+накладн\S*$|^lieferschein (hinzufügen|anlegen|erstellen)$/;
  var RX_CANCEL = /^отмена$|^abbrechen$/;

  function hasBtn(rx){
    var b = all('button,a.btn,[role=button],input[type=button],input[type=submit]');
    for (var i = 0; i < b.length; i++) if (vis(b[i]) && rx.test(txt(b[i]))) return true;
    return false;
  }
  // номер EB: строго 7 цифр; «EB2613834-02» → EB2613834
  function ebOf(v){
    var m = /(?:^|[^A-Za-zА-Яа-я])[EЕ][BВБ]\s*[-–]?\s*(\d{7})(?!\d)/i.exec(String(v == null ? '' : v));
    return m ? 'EB' + m[1] : '';
  }
  function notice(text){
    try {
      var d = document.createElement('div');
      d.textContent = text;
      d.style.cssText = 'position:fixed;left:50%;bottom:90px;transform:translateX(-50%);z-index:2147483647;background:#1b5e20;color:#fff;' +
        'padding:10px 16px;border-radius:12px;font:14px sans-serif;max-width:90%;text-align:center;box-shadow:0 2px 10px rgba(0,0,0,.3)';
      document.body.appendChild(d);
      setTimeout(function(){ try { d.remove(); } catch (x) {} }, 3500);
    } catch (x) {}
  }

  var lastEb = '';
  setInterval(function(){
    try {
      var data = window.__cgUnload;
      if (!data || !data.length) return;
      // только на форме «Добавить товарную накладную»; ушли с неё — при следующем открытии заполним заново
      if (!(hasBtn(RX_SUBMIT) && hasBtn(RX_CANCEL))) { lastEb = ''; return; }
      var numEl = inputByLabel(RX_NUM);
      if (!numEl) return;
      var eb = ebOf(numEl.value);
      if (!eb || eb === lastEb) return;
      var rec = null;
      for (var i = 0; i < data.length; i++) if (data[i].eb === eb) { rec = data[i]; break; }   // список уже отсортирован: сверху новые
      lastEb = eb;
      if (!rec) return;
      var did = [];
      var truck = inputByLabel(RX_TRUCK);
      if (truck && rec.car && !String(truck.value || '').trim()) { setValue(truck, rec.car); did.push('машина ' + rec.car); }
      var ext = inputByLabel(RX_EXT);
      if (ext && rec.doc && !String(ext.value || '').trim()) { setValue(ext, rec.doc); did.push('накладная ' + rec.doc); }
      var date = inputByLabel(RX_DATE);
      if (date && rec.date && norm(date.value) !== norm(rec.date)) { setValue(date, rec.date); did.push('дата ' + rec.date); }
      if (did.length) notice('Из разгрузки: ' + did.join(', '));
    } catch (x) {}
  }, 700);
})();
