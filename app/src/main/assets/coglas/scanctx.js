/*
 * Что открыто на сайте: заказ ('order') или накладная ('note') — только там сканы считаются партией.
 * Приложение вызывает window.__cgScanCtx() при каждом скане.
 */
(function(){
  if (window.__cgScanCtx) return;
  function vis(e){ try { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; } catch (x) { return false; } }
  function txt(e){ return String((e.innerText || e.textContent || e.value || '')).replace(/[\u00AD\u200B\u00A0]/g, ' ').replace(/\s+/g, ' ').trim().toLowerCase(); }
  var RX_ORDER = /накладн\S*\s+с\s+открыт\S*\s+количеств\S*\s+заказа|lieferschein.*offen/;
  var RX_NOTE = /оприходование\s*\(\s*поз|(авт|ручное)\.?\s*оприходование.*\(\s*поз|manuelle.*\(\s*pos|automatische.*\(\s*pos/;
  // EB-номер и номер накладной, видимые на странице: '#EB1234567|номер накладной' (для документа «ненайденные штрих-коды»)
  window.__cgScanRef = function(){
    try {
      var t = String((document.body && document.body.innerText) || '').replace(/[\u00AD\u200B\u00A0]/g, ' ').slice(0, 40000);
      var eb = '', wb = '';
      var m = /(?:^|[^A-Za-z0-9])[EЕ][BВ]\s?(\d{7})(?!\d)/i.exec(t);
      if (m) eb = 'EB' + m[1];
      var n = /(?:lieferschein(?:nummer)?|накладн\S*)\s*(?:nr\.?|№|#|номер)?\s*[:：]?\s*((?=[A-Za-z0-9\-\/.]*\d)[A-Za-z0-9][A-Za-z0-9\-\/.]{3,30})/i.exec(t);
      if (n) wb = n[1];
      return '#' + eb + '|' + wb;
    } catch (e) { return ''; }
  };
  window.__cgScanCtx = function(){
    try {
      var b = [].slice.call(document.querySelectorAll('button,a.btn,[role=button],input[type=button],input[type=submit]')).filter(vis);
      var order = false, note = false;
      for (var i = 0; i < b.length; i++) {
        var t = txt(b[i]);
        if (RX_NOTE.test(t)) note = true;
        else if (RX_ORDER.test(t)) order = true;
      }
      return note ? 'note' : (order ? 'order' : '');
    } catch (e) { return ''; }
  };
})();
