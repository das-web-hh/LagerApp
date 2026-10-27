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
    checkToken_(params.token);
    var action = String(params.action || 'ping').toLowerCase();
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

function doPost(e) {
  try {
    var payload = readPayload_(e);
    checkToken_(payload.token);
    var action = String(payload.action || 'upload').toLowerCase();
    if (action === 'delete') return deleteByName_(payload);
    if (action === 'init') return initResumable_(payload);
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
