(function(){
  if (window.__wmsHook) return;
  window.__wmsHook = true;
  var cap = window.__wmsHdr = {};
  function capture(k, v){ try { if (/^(x-csrf|x-requested-with)/i.test(k)) cap[k] = v; } catch (e) {} }
  function hc(h){
    try {
      if (!h) return;
      if (Array.isArray(h)) { h.forEach(function(p){ capture(p[0], p[1]); }); }
      else if (typeof h.forEach === 'function') { h.forEach(function(v, k){ capture(k, v); }); }
      else { Object.keys(h).forEach(function(k){ capture(k, h[k]); }); }
    } catch (e) {}
  }
  window.__wmsCall = function(id, method, url, body){
    try {
      var x = new XMLHttpRequest();
      x.open(method, url, true);
      x.setRequestHeader('Content-Type', 'application/json');
      x.setRequestHeader('Accept', 'application/json, text/plain, */*');
      for (var k in cap) { x.setRequestHeader(k, cap[k]); }
      x.onload = function(){ window.WmsBridge.result(id, x.status, x.responseText || ''); };
      x.onerror = function(){ window.WmsBridge.result(id, 0, ''); };
      x.send(body);
    } catch (e) { window.WmsBridge.result(id, -1, String(e)); }
  };
  var sens = /login|logon|auth|token|password|signin|session/i;
  var noise = /GetStatisticsUpdate|GetDashboardMessage|GetSystemMessage|\.(js|css|png|jpg|gif|svg|ico|woff2?|ttf|map)(\?|$)|coglas-cloud\.com/i;
  function clip(s, n){ if (s == null) return ''; s = String(s).replace(/\s+/g,' '); return s.length > n ? s.slice(0, n) + '...' : s; }
  function mask(u){ return String(u).replace(/(access_token|token|password|secret|api_key)=[^&]*/ig, function(m, a){ return a + '=***'; }); }
  function hn(h){
    try {
      if (!h) return '';
      if (Array.isArray(h)) return h.map(function(p){ return p[0]; }).join(',');
      if (typeof h.forEach === 'function') { var a = []; h.forEach(function(v, k){ a.push(k); }); return a.join(','); }
      return Object.keys(h).join(',');
    } catch (e) { return ''; }
  }
  function send(method, url, status, req, res, hdrs){
    try {
      if (noise.test(url)) return;
      var hide = sens.test(url);
      var line = new Date().toTimeString().slice(0,8) + ' ' + method + ' ' + status + ' ' + mask(url)
        + (hdrs ? ' | HDR: ' + hdrs : '')
        + (req && !hide ? ' | REQ: ' + clip(req, 1500) : '')
        + (res && !hide ? ' | RES: ' + clip(res, 1500) : '');
      if (window.WmsBridge) window.WmsBridge.log(line);
    } catch (e) {}
  }
  var of = window.fetch;
  if (of) {
    window.fetch = function(input, init){
      var url = (typeof input === 'string') ? input : ((input && input.url) || '');
      var method = (init && init.method) || (input && input.method) || 'GET';
      var body = (init && typeof init.body === 'string') ? init.body : '';
      var hd = hn(init && init.headers);
      hc(init && init.headers);
      return of.apply(this, arguments).then(function(r){
        try {
          r.clone().text().then(function(t){ send(method, url, r.status, body, t, hd); })
            .catch(function(){ send(method, url, r.status, body, '', hd); });
        } catch (e) {}
        return r;
      });
    };
  }
  var xo = XMLHttpRequest.prototype.open, xs = XMLHttpRequest.prototype.send, xh = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open = function(m, u){ this.__m = m; this.__u = u; return xo.apply(this, arguments); };
  XMLHttpRequest.prototype.setRequestHeader = function(k, v){ (this.__h = this.__h || []).push(k); capture(k, v); return xh.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(b){
    var x = this; var body = (typeof b === 'string') ? b : '';
    x.addEventListener('loadend', function(){
      var t = '';
      try { if (x.responseType === '' || x.responseType === 'text') t = x.responseText; } catch (e) {}
      send(x.__m || 'GET', x.__u || '', x.status, body, t, (x.__h || []).join(','));
    });
    return xs.apply(this, arguments);
  };
})();
