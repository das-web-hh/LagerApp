/**
 * Warehouse Google Drive Web App
 *
 * Deploy:
 *   1. Extensions → Apps Script.
 *   2. Paste this file into Code.gs (replace everything).
 *   3. Deploy → Manage deployments → Edit → Version: New version → Deploy.
 *      (first time: Deploy → New deployment → Web app;
 *       Execute as: Me, Who has access: Anyone)
 *   4. Put the /exec URL and the target folder ID into Warehouse Settings
 *      (website) and into the Android app (Profile → Settings).
 *
 * The folder itself is never exposed to the browser. The browser receives
 * only matching file metadata and Drive URLs.
 */

// Защита секретным токеном. Токен НЕ хранится в коде: задайте его в
// Project Settings (шестерёнка) → Script properties → Add script property:
//   имя:  TOKEN
//   знач: длинная случайная строка (32+ символа, например из генератора паролей)
// Тот же токен вставьте в приложение (Профиль → Google Диск → Токен) и в настройки сайта.
// Пока свойство TOKEN не задано, скрипт отклоняет ВСЕ запросы — так закрыто по умолчанию.

function getToken_() {
  return String(PropertiesService.getScriptProperties().getProperty('TOKEN') || '');
}

// Сравнение без раннего выхода: время ответа не выдаёт, сколько символов угадано.
function safeEqual_(a, b) {
  a = String(a || '');
  b = String(b || '');
  var diff = a.length ^ b.length;
  var n = Math.max(a.length, b.length);
  for (var i = 0; i < n; i++) {
    diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return diff === 0;
}

// Работники входят не по общему TOKEN, а по токену входа Firebase (JWT): скрипт сам проверяет профиль в Firestore.
// Нужно свойство скрипта FB_PROJECT = ID проекта Firebase. Общий TOKEN остаётся для администратора и сайта.
function checkWorker_(jwt) {
  // Каждая проверка — это вызов UrlFetch (суточный лимит скрипта). Успешный ответ кешируем на 10 минут по хешу токена.
  var cache = null, ckey = '';
  try {
    cache = CacheService.getScriptCache();
    ckey = 'w_' + Utilities.base64EncodeWebSafe(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, jwt)).slice(0, 40);
    var hit = cache.get(ckey);
    if (hit) return JSON.parse(hit);
  } catch (e) { cache = null; }
  var project = String(PropertiesService.getScriptProperties().getProperty('FB_PROJECT') || '');
  if (!project) throw new Error('На сервере не задан FB_PROJECT (Script properties).');
  if (jwt.length > 4000) throw new Error('Неверный токен.');
  var payload = JSON.parse(Utilities.newBlob(Utilities.base64DecodeWebSafe(jwt.split('.')[1])).getDataAsString());
  var email = String(payload.email || '').toLowerCase();
  if (!email || payload.email_verified !== true) throw new Error('Email не подтверждён.');
  // подпись и срок токена проверяет сам Firestore: без действующего токена вернёт 401/403
  var url = 'https://firestore.googleapis.com/v1/projects/' + project + '/databases/(default)/documents/users/' + encodeURIComponent(email);
  var r = UrlFetchApp.fetch(url, { headers: { Authorization: 'Bearer ' + jwt }, muteHttpExceptions: true });
  if (r.getResponseCode() !== 200) throw new Error('Доступ запрещён.');
  var f = JSON.parse(r.getContentText()).fields || {};
  var status = f.status && f.status.stringValue;
  var blocked = !!(f.blocked && f.blocked.booleanValue === true);
  var strikes = f.strikes ? Number(f.strikes.integerValue || 0) : 0;
  if (status !== 'active' || blocked || strikes >= 2) throw new Error('Профиль не активен.');
  try { if (cache) cache.put(ckey, JSON.stringify({role: {stringValue: (f.role && f.role.stringValue) || ''}}), 600); } catch (e) {}
  return f;
}

function checkToken_(value) {
  var v = String(value || '');
  if (v.indexOf('eyJ') === 0 && v.split('.').length === 3) { checkWorker_(v); return; }
  var token = getToken_();
  if (!token) throw new Error('На сервере не задан TOKEN (Script properties).');
  if (!safeEqual_(value, token)) throw new Error('Неверный токен.');
}

function doGet(e) {
  try {
    var params = (e && e.parameter) || {};
    // Публично, без токена: мини-приложение «Сборка» в браузере получателя забирает список партии по коду.
    // Отдаётся только файл BATCH_<код>.json; код — длинная случайная строка из ссылки/QR.
    if (String(params.action || '').toLowerCase() === 'auth') return getAuth_(params.code);
    if (String(params.action || '').toLowerCase() === 'batch') return getBatch_(params.code, params.who);
    if (String(params.action || '').toLowerCase() === 'app') return getBatchPage_(params.code);
    // Публично, по коду партии: счёт всех устройств (для общего подсчёта в браузере)
    if (String(params.action || '').toLowerCase() === 'counts') return getCounts_(params.code);
    // Публично: состояние партии для конкретного работника (run / stop / del) — окно у работника закрывается само
    if (String(params.action || '').toLowerCase() === 'status') return getStatus_(params.code, params.who);
    // Публично, по коду существующей партии: подсказки сайта stroeh.de и каталог товаров для HTML-страницы партии
    var pubAct = String(params.action || '').toLowerCase();
    if (pubAct === 'stroehs' || pubAct === 'stroeha' || pubAct === 'catalog') return publicBatchTool_(pubAct, params);
    checkToken_(params.token);
    var action = String(params.action || 'ping').toLowerCase();
    // Поиск артикула на stroeh.de (второй уровень артикула). Папка Диска не нужна.
    if (action === 'stroehsearch') return json_(stroehSearch_(params.q));
    if (action === 'stroehart') return json_(stroehArticle_(params.url));
    var folder = getFolder_(params.folderId);
    if (action === 'organize') return organize_(params);
    if (action === 'invlog') return getInvLog_(params);

    if (action === 'ping') {
      return json_({
        ok: true,
        message: 'Google Drive доступен.',
        folderId: folder.getId(),
        folderName: folder.getName()
      });
    }

    if (action === 'search' || action === 'list') {
      var key = String(params.key || '').trim();
      if (!key) {
        return json_({ok: true, files: []});
      }
      return json_({
        ok: true,
        files: findFilesByKey_(folder, key)
      });
    }

    // Android: страница списка файлов, новые сверху (pageSize, pageToken)
    if (action === 'page') {
      var size = Math.min(Math.max(parseInt(params.pageSize, 10) || 10, 1), 200);
      var parents = treeFolders_(folder).map(function (f) { return "'" + f.getId() + "' in parents"; });
      var q = '(' + parents.join(' or ') + ') and trashed=false' +
        " and mimeType != 'application/vnd.google-apps.folder'";
      var url = 'https://www.googleapis.com/drive/v3/files?q=' + encodeURIComponent(q) +
        '&orderBy=' + encodeURIComponent('createdTime desc') +
        '&pageSize=' + size +
        '&fields=' + encodeURIComponent('nextPageToken,files(id,name,mimeType,size,createdTime)');
      if (params.pageToken) url += '&pageToken=' + encodeURIComponent(params.pageToken);
      var resp = UrlFetchApp.fetch(url, {
        headers: {Authorization: 'Bearer ' + ScriptApp.getOAuthToken()},
        muteHttpExceptions: true
      });
      if (resp.getResponseCode() >= 300) {
        throw new Error('Drive API ' + resp.getResponseCode() + ': ' + resp.getContentText().slice(0, 300));
      }
      var data = JSON.parse(resp.getContentText());
      return json_({ok: true, files: data.files || [], nextPageToken: data.nextPageToken || ''});
    }

    // Android: содержимое файла по имени (base64)
    if (action === 'get') {
      var name = String(params.name || '').trim();
      if (!name) throw new Error('Не указано имя файла.');
      var f = findOne_(folder, name);
      if (!f) return json_({ok: false, error: 'not_found'});
      return json_({
        ok: true,
        name: f.getName(),
        mimeType: f.getMimeType(),
        contentBase64: Utilities.base64Encode(f.getBlob().getBytes())
      });
    }

    // Android: файл по частям (до 2 МБ за запрос) — большие накладные не упираются в память телефона и скрипта
    if (action === 'getchunk') {
      var cname = String(params.name || '').trim();
      if (!cname) throw new Error('Не указано имя файла.');
      var cfile = findOne_(folder, cname);
      if (!cfile) return json_({ok: false, error: 'not_found'});
      var csize = cfile.getSize();
      var offset = Math.max(parseInt(params.offset, 10) || 0, 0);
      var length = Math.min(Math.max(parseInt(params.length, 10) || 1048576, 1), 2097152);
      if (offset >= csize) return json_({ok: true, size: csize, contentBase64: ''});
      var end = Math.min(offset + length, csize) - 1;
      var cresp = UrlFetchApp.fetch(
        'https://www.googleapis.com/drive/v3/files/' + encodeURIComponent(cfile.getId()) + '?alt=media',
        {
          headers: {Authorization: 'Bearer ' + ScriptApp.getOAuthToken(), Range: 'bytes=' + offset + '-' + end},
          muteHttpExceptions: true
        }
      );
      var ccode = cresp.getResponseCode();
      if (ccode !== 200 && ccode !== 206) {
        throw new Error('Drive API ' + ccode + ': ' + cresp.getContentText().slice(0, 300));
      }
      var cbytes = cresp.getContent();
      if (ccode === 200) cbytes = cbytes.slice(offset, end + 1); // сервер проигнорировал Range
      return json_({ok: true, size: csize, contentBase64: Utilities.base64Encode(cbytes)});
    }

    return json_({ok: false, error: 'Неизвестное действие: ' + action});
  } catch (error) {
    return json_({ok: false, error: errorMessage_(error)});
  }
}


// ───────────── порядок на Диске: каждому виду файлов — своя папка ─────────────
// Файл попадает в подпапку рабочей папки по началу имени. Имена приложение не меняет, поэтому поиск, скачивание и удаление
// по имени работают как раньше: сначала в корне, затем во всех подпапках. Старый Code.gs без этого блока тоже работает (плоская папка).
var FOLDER_RULES_ = [
  {prefix: 'PR-S', path: ['Товары', 'Фото с сайта']},
  {prefix: 'PR-', path: ['Товары', 'Фото']},
  {prefix: 'RC-', path: ['Приёмки']},
  {prefix: 'IN-', path: ['Накладные']},
  {prefix: 'BW-', path: ['B-Ware']},
  {prefix: 'BATCH', path: ['Партии']},
  {prefix: 'CATALOG', path: ['Партии']}
];

function folderPathFor_(fileName) {
  var n = String(fileName || '');
  for (var i = 0; i < FOLDER_RULES_.length; i++) {
    if (n.indexOf(FOLDER_RULES_[i].prefix) === 0) return FOLDER_RULES_[i].path;
  }
  return null; // прочее остаётся в корне
}

function childFolder_(parent, name) {
  var it = parent.getFoldersByName(name);
  while (it.hasNext()) {
    var f = it.next();
    if (!f.isTrashed()) return f;
  }
  return parent.createFolder(name);
}

// Папка, куда кладётся файл с таким именем (создаётся при первой загрузке).
function targetFolder_(root, fileName) {
  var path = folderPathFor_(fileName);
  if (!path) return root;
  var f = root;
  for (var i = 0; i < path.length; i++) f = childFolder_(f, path[i]);
  return f;
}

// Все папки, где ищем файлы: корень и подпапки из правил (до 2 уровней), без служебных QR-папок.
function treeFolders_(root) {
  var out = [root];
  var seen = {};
  seen[root.getId()] = true;
  var it = root.getFolders();
  while (it.hasNext()) {
    var f = it.next();
    if (f.isTrashed() || f.getName().indexOf('QR-') === 0) continue;
    if (seen[f.getId()]) continue;
    seen[f.getId()] = true;
    out.push(f);
    var it2 = f.getFolders();
    while (it2.hasNext()) {
      var g = it2.next();
      if (g.isTrashed() || seen[g.getId()]) continue;
      seen[g.getId()] = true;
      out.push(g);
    }
  }
  return out;
}

// Файл по имени: сначала корень, затем подпапки. null — нет.
function findOne_(root, name) {
  var folders = treeFolders_(root);
  for (var i = 0; i < folders.length; i++) {
    var it = folders[i].getFilesByName(name);
    while (it.hasNext()) {
      var f = it.next();
      if (!f.isTrashed()) return f;
    }
  }
  return null;
}

// Android: разложить файлы корня по подпапкам и убрать дубли с одинаковым именем (в корзину).
// За вызов — не больше 120 файлов; приложение вызывает, пока left > 0.
function organize_(payload) {
  var root = getFolder_(payload.folderId);
  var moved = 0, dups = 0, left = 0, budget = 120;
  var made = {};
  var it = root.getFiles();
  var rootNames = {};
  var pending = [];
  while (it.hasNext()) {
    var f = it.next();
    if (f.isTrashed()) continue;
    pending.push(f);
  }
  for (var i = 0; i < pending.length; i++) {
    var file = pending[i];
    var name = file.getName();
    var path = folderPathFor_(name);
    if (!path) continue;
    if (budget <= 0) { left++; continue; }
    var target = targetFolder_(root, name);
    made[target.getId()] = true;
    // такой файл уже лежит в своей папке — это дубль
    var same = target.getFilesByName(name);
    var dupFound = false;
    while (same.hasNext()) {
      var other = same.next();
      if (!other.isTrashed() && other.getId() !== file.getId()) { dupFound = true; break; }
    }
    if (dupFound) { file.setTrashed(true); dups++; budget--; continue; }
    file.moveTo(target);
    moved++; budget--;
  }
  var folders = 0;
  for (var k in made) folders++;
  return json_({ok: true, moved: moved, dups: dups, left: left, folders: folders});
}

function readBatch_(code) {
  code = String(code || '');
  if (!/^[A-Za-z0-9]{6,40}$/.test(code)) return {err: 'Некорректный код партии.'};
  var best = newestFile_('BATCH_' + code + '.json', true);
  if (!best) return {err: 'Партия не найдена.'};
  return {batch: JSON.parse(best.getBlob().getDataAsString('UTF-8'))};
}

// Самый свежий файл с таким именем. trashOlder: старые дубликаты уходят в корзину — у партии всегда один файл.
function newestFile_(name, trashOlder) {
  var it = DriveApp.getFilesByName(name), best = null;
  while (it.hasNext()) {
    var f = it.next();
    if (f.isTrashed()) continue;
    if (!best) { best = f; continue; }
    var older = f.getLastUpdated() > best.getLastUpdated() ? best : f;
    if (older === best) best = f;
    if (trashOlder) { try { older.setTrashed(true); } catch (e) {} }
  }
  return best;
}

// ───────── управление партией: стоп / пуск / удаление (для всех или для одного работника) ─────────
// Файл BATCHCTL_<код>.json пишет приложение администратора: {all:'run|stop|del', w:{<хеш имени>:'stop|del'}, adm:[<хеш админа>]}.
// Нет файла — всё работает. Админ (adm) не блокируется никогда.
function readCtl_(code) {
  var f = newestFile_('BATCHCTL_' + code + '.json', true);
  if (!f) return {};
  try { return JSON.parse(f.getBlob().getDataAsString('UTF-8')) || {}; } catch (e) { return {}; }
}

// who — полный хеш имени (64 символа) или его первые 32 символа (так устройство подписывает свой счёт)
function keyMatch_(key, who) {
  key = String(key || '');
  return who.length >= 64 ? key === who : (who.length >= 32 && key.indexOf(who) === 0);
}

function ctlState_(code, who) {
  who = String(who || '');
  if (!/^[A-Za-z0-9]{6,40}$/.test(String(code || ''))) return 'run';
  var c = readCtl_(code), i, k;
  if (who) {
    var adm = c.adm || [];
    for (i = 0; i < adm.length; i++) if (keyMatch_(adm[i], who)) return 'run';
  }
  if (c.all === 'del') return 'del';
  var w = c.w || {}, own = '';
  if (who) for (k in w) if (keyMatch_(k, who)) { own = w[k]; break; }
  if (own === 'del') return 'del';
  if (c.all === 'stop' || own === 'stop') return 'stop';
  return 'run';
}

function getStatus_(code, who) {
  code = String(code || '');
  if (!/^[A-Za-z0-9]{6,40}$/.test(code)) return json_({ok: false, error: 'Некорректный код партии.'});
  return json_({ok: true, state: ctlState_(code, who), now: Date.now()});
}

// Рабочее время по серверному времени Google (часы телефона не влияют). h = {on, from, to, tz, days[1=вс..7=сб]}
function inWindow_(h) {
  if (!h || !h.on) return true;
  var tz = h.tz || 'Europe/Moscow', now = new Date();
  var u = Number(Utilities.formatDate(now, tz, 'u'));      // 1=пн … 7=вс
  var dow = u === 7 ? 1 : u + 1;                           // 1=вс … 7=сб
  var m = Number(Utilities.formatDate(now, tz, 'H')) * 60 + Number(Utilities.formatDate(now, tz, 'm'));
  var days = h.days || [];
  if (h.from <= h.to) return days.indexOf(dow) >= 0 && m >= h.from && m <= h.to;
  if (m >= h.from) return days.indexOf(dow) >= 0;
  if (m <= h.to) return days.indexOf(dow === 1 ? 7 : dow - 1) >= 0;
  return false;
}

// Только вход: имена (хеши), рабочее время и серверное время. Список товаров не отдаётся.
function getAuth_(code) {
  var r = readBatch_(code);
  if (r.err) return json_({ok: false, error: r.err});
  return json_({ok: true, title: r.batch.title || '', auth: r.batch.auth || {}, now: Date.now()});
}

// Список товаров: только если имя (хеш) есть в списке партии и сейчас рабочее время.
function getBatch_(code, who) {
  who = String(who || '');
  if (who) {
    var st = ctlState_(code, who);
    if (st === 'del') return json_({ok: false, error: 'deleted'});
    if (st === 'stop') return json_({ok: false, error: 'stopped'});
  }
  var r = readBatch_(code);
  if (r.err) return json_({ok: false, error: r.err});
  var a = r.batch.auth;
  if (a && a.names && a.names.length) {
    who = String(who || '');
    if (!who || a.names.indexOf(who) < 0) return json_({ok: false, error: 'name'});
    var isAdm = a.adm && a.adm.indexOf(who) >= 0;
    if (!isAdm && !inWindow_(a.h)) return json_({ok: false, error: 'hours'});
  }
  return json_({ok: true, batch: r.batch, now: Date.now()});
}

// ───────── общий счёт партии с нескольких устройств ─────────
function countsFile_(code, create) {
  var it = DriveApp.getFilesByName('BATCHCOUNT_' + code + '.json');
  while (it.hasNext()) { var f = it.next(); if (!f.isTrashed()) return f; }
  if (!create) return null;
  return DriveApp.createFile('BATCHCOUNT_' + code + '.json', '{"devs":{}}', 'application/json');
}

function devList_(data) {
  var out = [];
  var devs = (data && data.devs) || {};
  for (var d in devs) out.push({dev: d, name: devs[d].name || '', t: devs[d].t || 0, c: devs[d].c || {}});
  return out;
}

// ───────── общие позиции обычной партии (items) ─────────
// Позиции, добавленные получателем сверх плана, лежат в том же файле счёта: devs[<dev>].items = [{sid, name, article, barcode, by, t}].
// Список своих позиций устройство присылает целиком (замена), в ответе counts отдаётся объединённый список всех устройств.
// ПУСТАЯ партия ("empty": true в BATCH_<код>.json): items не сохраняются и не отдаются никогда — у каждого работника свой отдельный список.

function isArr_(v) { return Object.prototype.toString.call(v) === '[object Array]'; }

// Нормализованное название (как nmz в HTML): регистр, ё = е, пробелы
function nmz_(s) { return String(s || '').toLowerCase().replace(/ё/g, 'е').replace(/\s+/g, ' ').trim(); }

// true — партия пустая ИЛИ определить не удалось (на всякий случай обмен позициями закрыт). Ответ кешируется на 5 минут.
function isEmptyBatch_(code) {
  var cache = null, key = 'em_' + code;
  try {
    cache = CacheService.getScriptCache();
    var c = cache.get(key);
    if (c === '1') return true;
    if (c === '0') return false;
  } catch (e) { cache = null; }
  var r;
  try { r = readBatch_(code); } catch (e) { return true; }
  if (r.err || !r.batch) return true;
  var em = r.batch.empty;
  var isEm = em === true || em === 1 || String(em).toLowerCase() === 'true';
  try { if (cache) cache.put(key, isEm ? '1' : '0', 300); } catch (e) {}
  return isEm;
}

// Проверка и чистка присланного списка: не больше 3000 позиций, название до 200 символов
function cleanItems_(src) {
  var out = [], seen = {}, now = Date.now();
  if (!isArr_(src)) return out;
  for (var i = 0; i < src.length; i++) {
    var it = src[i];
    if (!it || typeof it !== 'object') continue;
    var sid = String(it.sid || '');
    if (!/^[A-Za-z0-9_-]{1,40}$/.test(sid) || seen[sid]) continue;
    var name = String(it.name || '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, 200);
    if (!name) continue;
    var t = Number(it.t);
    if (!isFinite(t) || t <= 0 || t > now) t = now;
    if (out.length >= 3000) break;
    seen[sid] = 1;
    out.push({
      sid: sid,
      name: name,
      article: String(it.article || '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, 40),
      barcode: String(it.barcode || '').replace(/\s/g, '').slice(0, 40),
      by: String(it.by || '').replace(/[^A-Za-z0-9]/g, '').slice(0, 64),
      t: t
    });
  }
  return out;
}

// Объединённый список позиций всех устройств. Одна позиция — это одинаковый sid ИЛИ одинаковый штрих-код ИЛИ одинаковое
// нормализованное название. В группе побеждает более новое t (поля берутся у него, пустые артикул и штрих-код — у остальных);
// sid группы — наименьший (не меняется от правок), остальные sid уходят в al: клиенты складывают счёт по всем sid группы.
function mergeItems_(data) {
  var list = [], devs = (data && data.devs) || {}, d, i;
  for (d in devs) {
    var its = devs[d] && devs[d].items;
    if (isArr_(its)) for (i = 0; i < its.length; i++) if (its[i] && its[i].sid) list.push(its[i]);
  }
  var par = [];
  for (i = 0; i < list.length; i++) par[i] = i;
  var find = function (x) { while (par[x] !== x) { par[x] = par[par[x]]; x = par[x]; } return x; };
  var first = {};
  for (i = 0; i < list.length; i++) {
    var it = list[i];
    var keys = ['s:' + it.sid, 'n:' + nmz_(it.name)];
    if (it.barcode) keys.push('b:' + it.barcode);
    for (var k = 0; k < keys.length; k++) {
      if (first.hasOwnProperty(keys[k])) { var ra = find(i), rb = find(first[keys[k]]); if (ra !== rb) par[ra] = rb; }
      else first[keys[k]] = i;
    }
  }
  var groups = {};
  for (i = 0; i < list.length; i++) { var r = find(i); (groups[r] = groups[r] || []).push(list[i]); }
  var out = [];
  for (var g in groups) {
    var m = groups[g];
    m.sort(function (x, y) { return (y.t || 0) - (x.t || 0) || (x.sid < y.sid ? -1 : 1); });
    var win = m[0], sids = {}, art = '', bc = '';
    for (i = 0; i < m.length; i++) {
      sids[m[i].sid] = 1;
      if (!art && m[i].article) art = m[i].article;
      if (!bc && m[i].barcode) bc = m[i].barcode;
    }
    var all = Object.keys(sids).sort(), main = all[0];
    out.push({
      sid: main, name: win.name, article: win.article || art, barcode: win.barcode || bc,
      by: win.by || '', t: win.t || 0, al: all.slice(1)
    });
  }
  out.sort(function (x, y) { return x.sid < y.sid ? -1 : x.sid > y.sid ? 1 : 0; });
  return out.slice(0, 3000);
}

function getCounts_(code) {
  code = String(code || '');
  if (!/^[A-Za-z0-9]{6,40}$/.test(code)) return json_({ok: false, error: 'Некорректный код партии.'});
  var f = countsFile_(code, false);
  var data = f ? JSON.parse(f.getBlob().getDataAsString('UTF-8')) : {devs: {}};
  var out = {ok: true, devs: devList_(data)};
  // items есть в ответе только у обычной партии; у пустой (и если тип партии не удалось определить) их нет совсем
  if (!isEmptyBatch_(code)) out.items = mergeItems_(data);
  return json_(out);
}

function saveCounts_(p) {
  var code = String(p.code || ''), dev = String(p.dev || '');
  if (!/^[A-Za-z0-9]{6,40}$/.test(code) || !/^[A-Za-z0-9]{6,40}$/.test(dev)) return json_({ok: false, error: 'Некорректный код.'});
  if (!DriveApp.getFilesByName('BATCH_' + code + '.json').hasNext()) return json_({ok: false, error: 'Партия не найдена.'});
  if (ctlState_(code, dev) !== 'run') return json_({ok: false, error: 'blocked'});
  var src = p.counts || {}, clean = {}, n = 0;
  for (var k in src) {
    var v = Number(src[k]);
    if (!isFinite(v) || v <= 0 || v > 1000000 || String(k).length > 80) continue;
    if (++n > 3000) break;
    clean[k] = Math.round(v * 1000) / 1000;
  }
  // items: необязательное поле. Нет поля — прежний список устройства не трогаем (старые страницы). Партия пустая — items не принимаем.
  var hasItems = isArr_(p.items), batchEmpty = isEmptyBatch_(code), cleanIt = [];
  if (hasItems && !batchEmpty) cleanIt = cleanItems_(p.items);
  var lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    var f = countsFile_(code, true);
    var data = JSON.parse(f.getBlob().getDataAsString('UTF-8'));
    data.devs = data.devs || {};
    if (!data.devs[dev] && Object.keys(data.devs).length >= 30) return json_({ok: false, error: 'Слишком много устройств.'});
    var rec = {name: String(p.name || '').slice(0, 40), t: Date.now(), c: clean};
    if (batchEmpty) {
      // пустая партия: ничего лишнего в файле не остаётся
    } else if (hasItems) rec.items = cleanIt;
    else if (data.devs[dev] && data.devs[dev].items) rec.items = data.devs[dev].items;
    data.devs[dev] = rec;
    f.setContent(JSON.stringify(data));
    var out = {ok: true, devs: devList_(data)};
    if (!batchEmpty) out.items = mergeItems_(data);
    return json_(out);
  } finally {
    lock.releaseLock();
  }
}

// ───────── журнал инвентаризации (кто, что, сколько, когда) ─────────
// Файл INVLOG_<код>.json: parts[<ключ>] = {dev, name, src: 'h' браузер | 'a' приложение, ver, box, t, rows: [{n,a,b,q,t0,t1,ad,x}]}.
// Каждое устройство присылает свой личный снимок по каждой своей коробке/паллете (замена). Другим работникам журнал НЕ отдаётся
// никогда и в счёт/списки партии не попадает — читает его только администратор (action=invlog). Код APPINV — инвентаризация в приложении.
function invLogFile_(code, create) {
  var it = DriveApp.getFilesByName('INVLOG_' + code + '.json');
  while (it.hasNext()) { var f = it.next(); if (!f.isTrashed()) return f; }
  if (!create) return null;
  return DriveApp.createFile('INVLOG_' + code + '.json', '{"parts":{}}', 'application/json');
}

function checkAdmin_(value) {
  var v = String(value || '');
  if (v.indexOf('eyJ') === 0 && v.split('.').length === 3) {
    var f = checkWorker_(v);
    if (!(f && f.role && f.role.stringValue === 'admin')) throw new Error('Только для администратора.');
    return;
  }
  var token = getToken_();
  if (!token) throw new Error('На сервере не задан TOKEN (Script properties).');
  if (!safeEqual_(v, token)) throw new Error('Неверный токен.');
}

function cleanLogRows_(src) {
  var out = [], now = Date.now();
  if (!isArr_(src)) return out;
  var ts = function (v) { v = Number(v); return isFinite(v) && v > 0 && v <= now + 60000 ? Math.round(v) : 0; };
  for (var i = 0; i < src.length && out.length < 3000; i++) {
    var r = src[i];
    if (!r || typeof r !== 'object') continue;
    var q = Number(r.q), n = String(r.n || '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, 200);
    if (!n || !isFinite(q) || q <= 0 || q > 1000000) continue;
    out.push({
      n: n, q: Math.round(q * 1000) / 1000,
      a: String(r.a || '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, 40),
      b: String(r.b || '').replace(/\s/g, '').slice(0, 40),
      e: String(r.e || '').replace(/[^0-9A-Za-z.\/ -]/g, '').trim().slice(0, 12),
      t0: ts(r.t0), t1: ts(r.t1), ad: ts(r.ad), x: r.x ? 1 : 0
    });
  }
  return out;
}

function saveLog_(p) {
  var code = String(p.code || ''), dev = String(p.dev || '');
  if (!/^[A-Za-z0-9]{6,40}$/.test(code) || !/^[A-Za-z0-9]{6,40}$/.test(dev)) return json_({ok: false, error: 'Некорректный код.'});
  var viaApp = false;
  if (p.token) { checkToken_(p.token); viaApp = true; }
  var isApp = /^APPINV(\d{8})?$/.test(code);
  var hasBatch = !isApp && DriveApp.getFilesByName('BATCH_' + code + '.json').hasNext();
  if (!hasBatch && !(viaApp && isApp)) return json_({ok: false, error: 'Партия не найдена.'});
  if (hasBatch) {
    var st = ctlState_(code, dev);
    if (st === 'del') return json_({ok: false, error: 'blocked', state: 'del'});
    if (st === 'stop') return json_({ok: false, error: 'blocked', state: 'stop'});
  }
  var ver = String(p.ver || '').replace(/[^0-9A-Za-z._-]/g, '').slice(0, 20);
  var name = String(p.name || '').slice(0, 40), src = p.src === 'a' ? 'a' : 'h', clean = [], skipped = 0;
  var parts = isArr_(p.parts) ? p.parts : [];
  if (parts.length > 50) return json_({ok: false, error: 'limit', message: 'Слишком много частей в одном запросе (максимум 50).'});
  for (var i = 0; i < parts.length && clean.length < 50; i++) {
    var pt = parts[i], key = String((pt && pt.k) || '');
    if (pt && isArr_(pt.rows) && pt.rows.length > 3000) return json_({ok: false, error: 'limit', message: 'В части больше 3000 строк (максимум 3000), журнал не сохранён.'});
    if (!/^[A-Za-z0-9_-]{1,60}$/.test(key)) continue;
    clean.push({k: key, box: String(pt.box || '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, 60), rows: cleanLogRows_(pt.rows)});
  }
  var lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    var f = invLogFile_(code, true);
    var data = JSON.parse(f.getBlob().getDataAsString('UTF-8'));
    data.parts = data.parts || {};
    for (var j = 0; j < clean.length; j++) {
      var c = clean[j], old = data.parts[c.k];
      if (old && old.dev !== dev) continue;                                   // чужую запись не трогаем
      if (!old && Object.keys(data.parts).length >= 300) { skipped++; continue; }
      data.parts[c.k] = {dev: dev, name: name, src: src, ver: ver, box: c.box, t: Date.now(), rows: c.rows};
    }
    f.setContent(JSON.stringify(data));
    if (skipped > 0) return json_({ok: false, error: 'limit', message: 'Журнал заполнен: максимум 300 частей на файл, ' + skipped + ' не сохранено.'});
    return json_({ok: true});
  } finally {
    lock.releaseLock();
  }
}

// Список дней yyyyMMdd от from до to включительно (не больше 62)
function dayList_(from, to) {
  var out = [];
  if (!/^\d{8}$/.test(from) || !/^\d{8}$/.test(to) || from > to) return out;
  var t = Date.UTC(+from.substr(0, 4), +from.substr(4, 2) - 1, +from.substr(6, 2));
  for (var i = 0; i < 62; i++) {
    var d = new Date(t + i * 86400000);
    var s = d.getUTCFullYear() + ('0' + (d.getUTCMonth() + 1)).slice(-2) + ('0' + d.getUTCDate()).slice(-2);
    if (s > to) break;
    out.push(s);
  }
  return out;
}

// Только администратор (токен входа с ролью admin или общий TOKEN)
function getInvLog_(params) {
  checkAdmin_(params.token);
  var code = String(params.code || '');
  if (!/^[A-Za-z0-9]{6,40}$/.test(code)) return json_({ok: false, error: 'Некорректный код партии.'});
  // инвентаризация в приложении за период: файлы APPINV<день> (+ старый общий APPINV для частей, которых нет в дневных файлах)
  if (code === 'APPINV' && /^\d{8}$/.test(String(params.from || '')) && /^\d{8}$/.test(String(params.to || ''))) {
    var days = dayList_(String(params.from), String(params.to)), plist = [], seen = {}, di, pk;
    for (di = 0; di < days.length; di++) {
      var df = invLogFile_('APPINV' + days[di], false);
      if (!df) continue;
      var dp = (JSON.parse(df.getBlob().getDataAsString('UTF-8')).parts) || {};
      for (pk in dp) { seen[pk] = 1; plist.push({key: pk, day: days[di], dev: dp[pk].dev, name: dp[pk].name, src: dp[pk].src, ver: dp[pk].ver || '', box: dp[pk].box, t: dp[pk].t, rows: dp[pk].rows || []}); }
    }
    var lf = invLogFile_('APPINV', false);
    if (lf) {
      var lp = (JSON.parse(lf.getBlob().getDataAsString('UTF-8')).parts) || {};
      for (pk in lp) if (!seen[pk]) plist.push({key: pk, day: '', dev: lp[pk].dev, name: lp[pk].name, src: lp[pk].src, ver: lp[pk].ver || '', box: lp[pk].box, t: lp[pk].t, rows: lp[pk].rows || []});
    }
    return json_({ok: true, code: code, title: 'Inventur (App)', empty: false, parts: plist});
  }
  var f = invLogFile_(code, false), list = [];
  if (f) {
    var parts = (JSON.parse(f.getBlob().getDataAsString('UTF-8')).parts) || {};
    for (var k in parts) list.push({key: k, dev: parts[k].dev, name: parts[k].name, src: parts[k].src, ver: parts[k].ver || '', box: parts[k].box, t: parts[k].t, rows: parts[k].rows || []});
  }
  var title = /^APPINV(\d{8})?$/.test(code) ? 'Inventur (App)' + (code.length === 14 ? ' ' + code.substr(12, 2) + '.' + code.substr(10, 2) + '.' + code.substr(6, 4) : '') : '', empty = false, kind = '';
  if (!title) { var r = readBatch_(code); if (r.batch) { title = String(r.batch.title || ''); empty = r.batch.empty === true; kind = /^(bware|expired)$/.test(String(r.batch.kind || '')) ? String(r.batch.kind) : ''; } }
  return json_({ok: true, code: code, title: title, empty: empty, kind: kind, parts: list});
}

function clearInvLog_(p) {
  checkAdmin_(p.token);
  var code = String(p.code || '');
  if (!/^[A-Za-z0-9]{6,40}$/.test(code)) return json_({ok: false, error: 'Некорректный код партии.'});
  var lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    if (code === 'APPINV' && /^\d{8}$/.test(String(p.from || '')) && /^\d{8}$/.test(String(p.to || ''))) {
      var ds = dayList_(String(p.from), String(p.to));
      for (var di = 0; di < ds.length; di++) { var df = invLogFile_('APPINV' + ds[di], false); if (df) df.setContent('{"parts":{}}'); }
      return json_({ok: true});
    }
    var f = invLogFile_(code, false);
    if (f) f.setContent('{"parts":{}}');
    return json_({ok: true});
  } finally {
    lock.releaseLock();
  }
}


// Страница партии: HTML-файл с Диска (BATCHPAGE_<код>.html) открывается в браузере получателя по ссылке/QR.
function getBatchPage_(code) {
  code = String(code || '');
  var msg = function(t) { return HtmlService.createHtmlOutput('<p style="font:18px sans-serif;padding:24px">' + t + '</p>').addMetaTag('viewport', 'width=device-width,initial-scale=1'); };
  if (!/^[A-Za-z0-9]{6,40}$/.test(code)) return msg('Некорректная ссылка.');
  var best = newestFile_('BATCHPAGE_' + code + '.html', true);
  if (!best) return msg('Партия не найдена. Попросите отправить её ещё раз.');
  return HtmlService.createHtmlOutput(best.getBlob().getDataAsString('UTF-8'))
    .setTitle('Сборка')
    .addMetaTag('viewport', 'width=device-width,initial-scale=1,maximum-scale=1')
    .setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}

function doPost(e) {
  try {
    var payload = readPayload_(e);
    // Публично, по коду партии: устройство отправляет свой счёт (запись только в BATCHCOUNT_<код>.json)
    if (String(payload.action || '').toLowerCase() === 'count') return saveCounts_(payload);
    // Публично, по коду партии: журнал инвентаризации (только запись; читает его один администратор)
    if (String(payload.action || '').toLowerCase() === 'log') return saveLog_(payload);
    checkToken_(payload.token);
    var action = String(payload.action || 'upload').toLowerCase();
    if (action === 'delete') return deleteByName_(payload);
    if (action === 'init') return initResumable_(payload);
    if (action === 'mkshare') return makeShare_(payload);
    if (action === 'organize') return organize_(payload);
    if (action === 'invlogclear') return clearInvLog_(payload);
    if (action !== 'upload') {
      return json_({ok: false, error: 'Неизвестное действие: ' + action});
    }

    var root = getFolder_(payload.folderId);
    var fileName = safeFileName_(payload.fileName);
    var mimeType = String(payload.mimeType || 'application/octet-stream');
    var base64 = String(payload.contentBase64 || '');
    if (!base64) throw new Error('Пустое содержимое файла.');

    // Повторная отправка того же файла (например, после обрыва связи) не создаёт дубль — ни в корне, ни в подпапках
    if (payload.skipIfExists === true || String(payload.skipIfExists).toLowerCase() === 'true') {
      var existing = findOne_(root, fileName);
      if (existing) {
        return json_({ok: true, file: fileInfo_(existing), message: 'Файл уже есть.'});
      }
    }

    var bytes = Utilities.base64Decode(base64);
    var blob = Utilities.newBlob(bytes, mimeType, fileName);
    var file = targetFolder_(root, fileName).createFile(blob); // своя папка по виду файла

    if (payload.makePublic === true || String(payload.makePublic).toLowerCase() === 'true') {
      try {
        file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
      } catch (sharingError) {
        // Workspace administrators can prohibit public links. The upload
        // remains successful and the authenticated owner can still open it.
      }
    }

    return json_({
      ok: true,
      file: fileInfo_(file),
      message: 'Файл загружен.'
    });
  } catch (error) {
    return json_({ok: false, error: errorMessage_(error)});
  }
}

// QR на документе: отдельная папка «QR-<имя>» внутри рабочей папки, открытая по ссылке только на просмотр.
// Ссылка ведёт только в эту папку (в ней лежат цветные фото одного документа), остальное содержимое Диска не открывается.
function makeShare_(payload) {
  var root = getFolder_(payload.folderId);
  var name = String(payload.name || '').trim();
  if (!/^[A-Za-z0-9_-]{4,40}$/.test(name)) throw new Error('Некорректное имя папки.');
  var folderName = 'QR-' + name;
  var it = root.getFoldersByName(folderName);
  var folder = it.hasNext() ? it.next() : root.createFolder(folderName);
  folder.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
  return json_({ok: true, folderId: folder.getId(), url: folder.getUrl(), message: 'Папка открыта по ссылке.'});
}

// Android: большой файл. Скрипт только открывает «возобновляемую» сессию загрузки в Drive и отдаёт её адрес;
// сами данные телефон отправляет в Google напрямую кусками по 4 МБ (с докачкой при обрыве) —
// ни тело запроса, ни память скрипта, ни память телефона размером файла не ограничены.
function initResumable_(payload) {
  var root = getFolder_(payload.folderId);
  var fileName = safeFileName_(payload.fileName);
  var folder = targetFolder_(root, fileName); // своя папка по виду файла
  var mimeType = String(payload.mimeType || 'application/octet-stream');
  var size = parseInt(payload.size, 10) || 0;

  if (payload.skipIfExists === true || String(payload.skipIfExists).toLowerCase() === 'true') {
    var existing = findOne_(root, fileName);
    if (existing) {
      return json_({ok: true, exists: true, file: fileInfo_(existing), message: 'Файл уже есть.'});
    }
  }

  var headers = {
    Authorization: 'Bearer ' + ScriptApp.getOAuthToken(),
    'X-Upload-Content-Type': mimeType
  };
  if (size > 0) headers['X-Upload-Content-Length'] = String(size);

  var resp = UrlFetchApp.fetch(
    'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name,size',
    {
      method: 'post',
      contentType: 'application/json; charset=UTF-8',
      headers: headers,
      payload: JSON.stringify({name: fileName, parents: [folder.getId()], mimeType: mimeType}),
      muteHttpExceptions: true
    }
  );
  var code = resp.getResponseCode();
  if (code < 200 || code >= 300) {
    throw new Error('Drive API ' + code + ': ' + resp.getContentText().slice(0, 300));
  }
  var all = resp.getAllHeaders();
  var location = all['Location'] || all['location'];
  if (!location) throw new Error('Drive не вернул адрес загрузки.');
  return json_({ok: true, sessionUrl: String(location)});
}

// Android: удалить (в корзину) файл по имени из настроенной папки
function deleteByName_(payload) {
  var root = getFolder_(payload.folderId);
  var name = safeFileName_(payload.fileName);
  var n = 0;
  var folders = treeFolders_(root);
  for (var i = 0; i < folders.length; i++) {
    var it = folders[i].getFilesByName(name);
    while (it.hasNext()) {
      it.next().setTrashed(true);
      n++;
    }
  }
  return json_({ok: true, deleted: n});
}

function readPayload_(e) {
  var parameter = (e && e.parameter) || {};
  var raw = parameter.payload || '';
  if (raw) {
    try {
      return JSON.parse(raw);
    } catch (error) {
      throw new Error('Поле payload содержит некорректный JSON.');
    }
  }

  var body = e && e.postData && e.postData.contents;
  if (body) {
    try {
      return JSON.parse(body);
    } catch (error) {
      throw new Error('Тело POST-запроса содержит некорректный JSON.');
    }
  }
  return parameter;
}

function getFolder_(folderId) {
  var id = String(folderId || '').trim();
  if (!id) throw new Error('Не указан ID папки Google Диска.');
  return DriveApp.getFolderById(id);
}

function findFilesByKey_(folder, key) {
  var files = [];
  var folders = treeFolders_(folder);
  for (var i = 0; i < folders.length; i++) {
    var iterator = folders[i].getFiles();
    while (iterator.hasNext()) {
      var file = iterator.next();
      if (!file.isTrashed() && file.getName().indexOf(key) !== -1) {
        files.push(fileInfo_(file));
      }
    }
  }
  files.sort(function(a, b) {
    return String(b.createdTime).localeCompare(String(a.createdTime));
  });
  return files;
}

function fileInfo_(file) {
  var id = file.getId();
  var mimeType = file.getMimeType();
  return {
    id: id,
    name: file.getName(),
    mimeType: mimeType,
    size: file.getSize(),
    createdTime: file.getDateCreated().toISOString(),
    viewUrl: 'https://drive.google.com/uc?export=view&id=' + encodeURIComponent(id),
    previewUrl: 'https://drive.google.com/file/d/' + encodeURIComponent(id) + '/preview',
    downloadUrl: 'https://drive.google.com/uc?export=download&id=' + encodeURIComponent(id),
    webViewLink: file.getUrl()
  };
}

function safeFileName_(value) {
  var name = String(value || 'warehouse-file').trim()
    .replace(/[\\\/:*?"<>|#%{}[\]$!'@`=]/g, '_');
  return name.slice(0, 180) || 'warehouse-file';
}

function errorMessage_(error) {
  return error && error.message ? String(error.message) : String(error);
}

function json_(value) {
  return ContentService
    .createTextOutput(JSON.stringify(value))
    .setMimeType(ContentService.MimeType.JSON);
}


// Запустите один раз вручную (▶ Выполнить), чтобы выдать разрешения на
// Drive и внешние запросы, затем выпустите новую версию развёртывания.
function authorize_() {
  DriveApp.getRootFolder();
  UrlFetchApp.fetch('https://www.googleapis.com/discovery/v1/apis/drive/v3/rest');
  return 'ok';
}


// ───────────── stroeh.de: поиск и артикул ─────────────
// Запросы только по нажатию кнопки в приложении (один поиск + одна страница товара), результат кэшируется на 6 часов.

var STROEH_HOST_ = 'https://www.stroeh.de';

function stroehGet_(url) {
  var r = UrlFetchApp.fetch(url, {
    muteHttpExceptions: true,
    followRedirects: true,
    headers: {
      'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
      'Accept-Language': 'de-DE,de;q=0.9'
    }
  });
  var code = r.getResponseCode();
  if (code !== 200) throw new Error('stroeh.de ответил HTTP ' + code);
  return r.getContentText('UTF-8');
}

function decodeEntities_(s) {
  var named = {amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', auml: 'ä', ouml: 'ö', uuml: 'ü',
    Auml: 'Ä', Ouml: 'Ö', Uuml: 'Ü', szlig: 'ß', euro: '€', ndash: '–', mdash: '—'};
  return String(s || '')
    .replace(/&#x([0-9a-f]+);/gi, function (m, h) { return String.fromCharCode(parseInt(h, 16)); })
    .replace(/&#(\d+);/g, function (m, d) { return String.fromCharCode(parseInt(d, 10)); })
    .replace(/&([a-zA-Z]+);/g, function (m, n) { return named.hasOwnProperty(n) ? named[n] : m; });
}

function stripTags_(s) {
  var t = String(s || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ');
  return decodeEntities_(t).replace(/\s+/g, ' ').trim();
}

function cacheKey_(prefix, value) {
  var d = Utilities.computeDigest(Utilities.DigestAlgorithm.MD5, String(value));
  return prefix + Utilities.base64EncodeWebSafe(d);
}

function stroehSearch_(q) {
  q = String(q || '').trim().slice(0, 80);
  if (q.length < 2) throw new Error('Слишком короткий запрос');
  var cache = CacheService.getScriptCache();
  var key = cacheKey_('sts2_', q.toLowerCase());
  var hit = cache.get(key);
  if (hit) return {ok: true, items: JSON.parse(hit)};

  var searchUrl = STROEH_HOST_ + '/shop/suche/' + encodeURIComponent(q);
  // Поиск по точному артикулу магазин может сразу перенаправить на страницу товара: тогда это единственный результат.
  try {
    var first = UrlFetchApp.fetch(searchUrl, {
      muteHttpExceptions: true,
      followRedirects: false,
      headers: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
        'Accept-Language': 'de-DE,de;q=0.9'
      }
    });
    var fc = first.getResponseCode();
    if (fc >= 300 && fc < 400) {
      var hdr = first.getHeaders();
      var loc = String(hdr['Location'] || hdr['location'] || '');
      if (loc.indexOf('/') === 0) loc = STROEH_HOST_ + loc;
      if (/^https:\/\/www\.stroeh\.de\/shop\/[^?#\s]+[^\/?#\s]$/.test(loc)) {
        var one = stroehArticle_(loc);
        var direct = [{name: one.name || q, url: loc}];
        cache.put(key, JSON.stringify(direct), 6 * 3600);
        return {ok: true, items: direct};
      }
    }
  } catch (ignore) { /* обычный поиск ниже */ }

  var html = stroehGet_(searchUrl);
  var items = [], seen = {};
  var re = /<a\b[^>]*?href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi, m;
  while ((m = re.exec(html)) && items.length < 40) {
    var href = decodeEntities_(m[1]);
    if (href.indexOf('/') === 0) href = STROEH_HOST_ + href;
    var mm = href.match(/^https:\/\/www\.stroeh\.de\/shop\/([^?#]+)$/);
    if (!mm) continue;
    var path = mm[1];
    if (/\/$/.test(path)) continue;          // разделы каталога кончаются «/», товары — нет
    if (path.split('/').length < 3) continue; // служебные страницы (agb.php, Lieferkosten…)
    var text = cleanHitName_(stripTags_(m[2]));
    if (!text) continue;
    var e = seen[href];
    if (!e) { e = {name: text, url: href}; seen[href] = e; items.push(e); }
    else if (text.length < e.name.length) e.name = text; // название короче описания
  }
  if (items.length) cache.put(key, JSON.stringify(items), 6 * 3600);
  return {ok: true, items: items};
}

// Публичные запросы HTML-страницы партии. Работают только с кодом существующей партии (не открытый прокси).
// catalog — файл CATALOG.json (название, штрих-код, артикул), который приложение кладёт на Диск вместе с партией.
function publicBatchTool_(act, p) {
  var r = readBatch_(p.code);
  if (r.err) return json_({ok: false, error: r.err});
  if (act === 'stroehs') return json_(stroehSearch_(p.q));
  if (act === 'stroeha') return json_(stroehArticle_(p.url));
  var f = newestFile_('CATALOG.json', false);
  if (!f) return json_({ok: true, items: []});
  return json_({ok: true, items: JSON.parse(f.getBlob().getDataAsString('UTF-8'))});
}

// Адрес главного фото со страницы товара.
function stroehImage_(html) {
  var BAD = /\/ps\d+\/|logo|icon|sprite|payment|paypal|badge|flag|banner|placeholder|blank|loader|pixel|spacer|riders|feedscanner|siegel|trusted|ekomi|\.svg|\.gif|data:image/i;
  var THUMB = /thumb|[\/_-](xs|small|mini|tiny)[\/_.-]|_s\.|\/\d{2,3}x\d{2,3}\//i;
  function abs(u) {
    u = decodeEntities_(String(u || '')).trim();
    if (!u) return '';
    if (u.indexOf('//') === 0) u = 'https:' + u;
    else if (u.charAt(0) === '/') u = STROEH_HOST_ + u;
    else if (!/^https?:\/\//i.test(u)) u = STROEH_HOST_ + '/' + u;
    u = u.replace(/^http:\/\//i, 'https://');
    return /^https:\/\/[^\s"'<>]+$/.test(u) ? u : '';
  }
  function okUrl(u) { return u && !BAD.test(u) && /\.(jpe?g|png|webp)(\?|$)/i.test(u); }
  function fromSrcset(v) {
    var parts = String(v || '').split(',');
    var last = parts[parts.length - 1].trim().split(/\s+/)[0];
    return last;
  }

  // Порядок: 1) JSON-LD Product / itemprop=image, 2) og:image, 3) галерея перед «Artikelnr.»
  // 1) JSON-LD (schema.org Product → image)
  var ld = /<script[^>]+type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi, lm;
  while ((lm = ld.exec(html))) {
    try {
      var j = JSON.parse(lm[1]);
      var list = Array.isArray(j) ? j : (j['@graph'] || [j]);
      for (var i = 0; i < list.length; i++) {
        var o = list[i];
        if (!o || String(o['@type']).indexOf('Product') < 0 || !o.image) continue;
        var im0 = Array.isArray(o.image) ? o.image[0] : o.image;
        if (im0 && typeof im0 === 'object') im0 = im0.url || im0.contentUrl;
        var u0 = abs(im0);
        if (okUrl(u0)) return u0;
      }
    } catch (ignore) { }
  }

  // 1б) разметка schema.org прямо в странице: <link itemprop="image" href="…"> (так у stroeh.de)
  var ip = html.match(/<(?:link|meta)[^>]+itemprop=["']image["'][^>]+(?:href|content)=["']([^"']+)["']/i) ||
    html.match(/<(?:link|meta)[^>]+(?:href|content)=["']([^"']+)["'][^>]+itemprop=["']image["']/i);
  var u1 = ip ? abs(ip[1]) : '';
  if (okUrl(u1)) return u1;

  // 2) og:image / image_src — главное фото, которое магазин сам указывает для страницы
  var im = html.match(/<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i) ||
    html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']/i) ||
    html.match(/<link[^>]+rel=["']image_src["'][^>]+href=["']([^"']+)["']/i);
  var u3 = im ? abs(im[1]) : '';
  if (okUrl(u3)) return u3;
  // 3) Галерея: область страницы от «хлебных крошек» до «Artikelnr.» (блок «Ähnliches» ниже и не попадает)
  var art = html.search(/Artikelnr/i);
  var h1 = html.search(/<h1/i);
  var endIdx = Math.max(art, h1);
  if (endIdx > 0) {
    var startIdx = Math.max(0, Math.min(art < 0 ? h1 : art, h1 < 0 ? art : h1) - 25000);
    var bc = html.lastIndexOf('readcrumb', endIdx);
    if (bc > startIdx) startIdx = bc;
    var region = html.slice(startIdx, endIdx + 400);
    var cands = [];
    var tag = /<img\b[^>]*>/gi, tm;
    while ((tm = tag.exec(region))) {
      var t = tm[0];
      var big = t.match(/\b(?:data-zoom-image|data-large-image|data-large|data-full|data-big|data-image-large)=["']([^"']+)["']/i);
      var src = t.match(/\b(?:src|data-src|data-lazy-src|data-original)=["']([^"']+)["']/i);
      var ss = t.match(/\b(?:data-srcset|srcset)=["']([^"']+)["']/i);
      var u = big ? big[1] : (ss ? fromSrcset(ss[1]) : (src ? src[1] : ''));
      u = abs(u);
      if (okUrl(u)) cands.push({u: u, big: !!big});
    }
    for (var c = 0; c < cands.length; c++) if (cands[c].big && !THUMB.test(cands[c].u)) return cands[c].u;
    for (var d = 0; d < cands.length; d++) if (!THUMB.test(cands[d].u)) return cands[d].u;
    if (cands.length) return cands[0].u;
  }
  return '';
}

// Название из ссылки поиска без бейджей скидок («SCHNÄPPCHEN», «RABATT 17%», «(0)», цена).
function cleanHitName_(t) {
  var c = String(t || '').replace(/\s+(SCHNÄPPCHEN|RABATT|VERSANDKOSTENFREI|SALE|NEU)\b.*$/i, '').replace(/\s*\(\d+\)\s*.*$/, '').replace(/\s*€.*$/, '').trim();
  return c || t;
}

function stroehArticle_(url) {
  url = String(url || '').trim();
  if (!/^https:\/\/www\.stroeh\.de\/shop\/[^\s]+$/.test(url)) throw new Error('Допустимы только адреса stroeh.de');
  var cache = CacheService.getScriptCache();
  var key = cacheKey_('sta3_', url);
  var hit = cache.get(key);
  if (hit) return JSON.parse(hit);

  var html = stroehGet_(url);
  var h1 = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i);
  var name = h1 ? stripTags_(h1[1]) : '';
  // Первое «Artikelnr.» на странице — основной товар; реклама («Hier geht's oft günstiger…») стоит ниже и не берётся.
  var text = stripTags_(html);
  var m = text.match(/Artikelnr\.?\s*:?\s*([A-Za-z0-9][A-Za-z0-9\-\.\/]{2,19})/);
  if (!m) throw new Error('Артикул на странице не найден');
  // Фото товара: большое фото из галереи слева от названия (до «Artikelnr.»), без логотипов, значков и блока «Ähnliches»
  var img = stroehImage_(html);
  var res = {ok: true, name: name, article: m[1].replace(/[\.\/\-]+$/, ''), url: url, image: img};
  cache.put(key, JSON.stringify(res), 6 * 3600);
  return res;
}

// Проверка из редактора Apps Script: Run → testStroeh_ → View → Logs. Покажет, находит ли разбор товары и артикул.
function testStroeh_() {
  var r = stroehSearch_('Agrobs Kraftpaket');
  Logger.log('Найдено: ' + r.items.length + ' ' + JSON.stringify(r.items.slice(0, 3)));
  if (r.items.length) Logger.log(JSON.stringify(stroehArticle_(r.items[0].url)));
}
