(function(t){
  t = t.toLowerCase().trim();
  var all = document.querySelectorAll('a,button,[role=button],li,span,div,td,label');
  var best = null, bl = 1e9;
  for (var i = 0; i < all.length; i++) {
    var e = all[i], r = e.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) continue;
    var x = (e.innerText || '').trim().toLowerCase();
    if (x.indexOf(t) < 0) continue;
    if (x.length < bl) { bl = x.length; best = e; }
  }
  if (!best) return 'Не найдено: ' + t;
  best.scrollIntoView({block: 'center'});
  best.click();
  return 'Нажато: <' + best.tagName.toLowerCase() + '> "' + (best.innerText || '').trim().slice(0, 60) + '"';
})(__T__)
