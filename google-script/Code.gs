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

function checkToken_(value) {
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
      var q = "'" + folder.getId() + "' in parents and trashed=false" +
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
      var found = folder.getFilesByName(name);
      if (!found.hasNext()) return json_({ok: false, error: 'not_found'});
      var f = found.next();
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
      var cfound = folder.getFilesByName(cname);
      if (!cfound.hasNext()) return json_({ok: false, error: 'not_found'});
      var cfile = cfound.next();
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

function getCounts_(code) {
  code = String(code || '');
  if (!/^[A-Za-z0-9]{6,40}$/.test(code)) return json_({ok: false, error: 'Некорректный код партии.'});
  var f = countsFile_(code, false);
  if (!f) return json_({ok: true, devs: []});
  return json_({ok: true, devs: devList_(JSON.parse(f.getBlob().getDataAsString('UTF-8')))});
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
  var lock = LockService.getScriptLock();
  lock.waitLock(15000);
  try {
    var f = countsFile_(code, true);
    var data = JSON.parse(f.getBlob().getDataAsString('UTF-8'));
    data.devs = data.devs || {};
    if (!data.devs[dev] && Object.keys(data.devs).length >= 30) return json_({ok: false, error: 'Слишком много устройств.'});
    data.devs[dev] = {name: String(p.name || '').slice(0, 40), t: Date.now(), c: clean};
    f.setContent(JSON.stringify(data));
    return json_({ok: true, devs: devList_(data)});
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
    checkToken_(payload.token);
    var action = String(payload.action || 'upload').toLowerCase();
    if (action === 'delete') return deleteByName_(payload);
    if (action === 'init') return initResumable_(payload);
    if (action === 'mkshare') return makeShare_(payload);
    if (action !== 'upload') {
      return json_({ok: false, error: 'Неизвестное действие: ' + action});
    }

    var folder = getFolder_(payload.folderId);
    var fileName = safeFileName_(payload.fileName);
    var mimeType = String(payload.mimeType || 'application/octet-stream');
    var base64 = String(payload.contentBase64 || '');
    if (!base64) throw new Error('Пустое содержимое файла.');

    // Повторная отправка того же файла (например, после обрыва связи) не создаёт дубль
    if (payload.skipIfExists === true || String(payload.skipIfExists).toLowerCase() === 'true') {
      var existing = folder.getFilesByName(fileName);
      if (existing.hasNext()) {
        return json_({ok: true, file: fileInfo_(existing.next()), message: 'Файл уже есть.'});
      }
    }

    var bytes = Utilities.base64Decode(base64);
    var blob = Utilities.newBlob(bytes, mimeType, fileName);
    var file = folder.createFile(blob);

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
  var folder = getFolder_(payload.folderId);
  var fileName = safeFileName_(payload.fileName);
  var mimeType = String(payload.mimeType || 'application/octet-stream');
  var size = parseInt(payload.size, 10) || 0;

  if (payload.skipIfExists === true || String(payload.skipIfExists).toLowerCase() === 'true') {
    var existing = folder.getFilesByName(fileName);
    if (existing.hasNext()) {
      return json_({ok: true, exists: true, file: fileInfo_(existing.next()), message: 'Файл уже есть.'});
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
  var folder = getFolder_(payload.folderId);
  var name = safeFileName_(payload.fileName);
  var it = folder.getFilesByName(name);
  var n = 0;
  while (it.hasNext()) {
    it.next().setTrashed(true);
    n++;
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
  var iterator = folder.getFiles();
  while (iterator.hasNext()) {
    var file = iterator.next();
    if (file.getName().indexOf(key) !== -1) {
      files.push(fileInfo_(file));
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
  var key = cacheKey_('sts_', q.toLowerCase());
  var hit = cache.get(key);
  if (hit) return {ok: true, items: JSON.parse(hit)};

  var html = stroehGet_(STROEH_HOST_ + '/shop/suche/' + encodeURIComponent(q));
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
    var text = stripTags_(m[2]);
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

function stroehArticle_(url) {
  url = String(url || '').trim();
  if (!/^https:\/\/www\.stroeh\.de\/shop\/[^\s]+$/.test(url)) throw new Error('Допустимы только адреса stroeh.de');
  var cache = CacheService.getScriptCache();
  var key = cacheKey_('sta_', url);
  var hit = cache.get(key);
  if (hit) return JSON.parse(hit);

  var html = stroehGet_(url);
  var h1 = html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i);
  var name = h1 ? stripTags_(h1[1]) : '';
  // Первое «Artikelnr.» на странице — основной товар; реклама («Hier geht's oft günstiger…») стоит ниже и не берётся.
  var text = stripTags_(html);
  var m = text.match(/Artikelnr\.?\s*:?\s*([A-Za-z0-9][A-Za-z0-9\-\.\/]{2,19})/);
  if (!m) throw new Error('Артикул на странице не найден');
  var res = {ok: true, name: name, article: m[1].replace(/[\.\/\-]+$/, ''), url: url};
  cache.put(key, JSON.stringify(res), 6 * 3600);
  return res;
}

// Проверка из редактора Apps Script: Run → testStroeh_ → View → Logs. Покажет, находит ли разбор товары и артикул.
function testStroeh_() {
  var r = stroehSearch_('Agrobs Kraftpaket');
  Logger.log('Найдено: ' + r.items.length + ' ' + JSON.stringify(r.items.slice(0, 3)));
  if (r.items.length) Logger.log(JSON.stringify(stroehArticle_(r.items[0].url)));
}
