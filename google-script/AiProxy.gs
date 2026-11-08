/**
 * Прокси ИИ для работников LagerApp (Google Apps Script, Web App).
 *
 * Свойства скрипта (Project Settings → Script properties):
 *   AI_PROVIDER   claude | gemini | openai
 *   AI_KEY        ключ провайдера ИИ (хранится только здесь)
 *   AI_MODEL      необязательно; по умолчанию claude-haiku-5-5 / gemini-2.5-flash / gpt-4o-mini
 *   AI_BASE_URL   только для openai-совместимых, например https://api.openai.com/v1
 *   FB_PROJECT    ID проекта Firebase (Project settings → Project ID)
 *   RATE_PER_HOUR необязательно; запросов в час на одного работника (по умолчанию 120)
 *
 * Развёртывание: Deploy → New deployment → Web app; Execute as: Me; Who has access: Anyone.
 * Адрес вида https://script.google.com/macros/s/.../exec вставляет админ в приложении (Настройки ИИ).
 * Доступ защищён не адресом, а Firebase-токеном работника: проверяется токен и профиль в Firestore.
 */

function doGet() { return out_({ ok: true, service: 'lager-ai-proxy' }); }

function doPost(e) {
  try {
    var req = JSON.parse(e.postData.contents);
    var user = checkUser_(req.token);
    limit_(user.email);
    var prompt = String(req.prompt || '');
    if (!prompt || prompt.length > 30000) throw new Error('неверный запрос');
    var image = req.image ? String(req.image) : '';
    if (image.length > 12000000) throw new Error('фото слишком большое');
    var mime = (req.mime === 'image/jpeg' || req.mime === 'image/png') ? req.mime : 'image/png';
    return out_({ ok: true, text: callAi_(prompt, image, mime, req.json === true) });
  } catch (err) {
    return out_({ ok: false, error: String((err && err.message) || err) });
  }
}

function out_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function prop_(name, def) {
  var v = PropertiesService.getScriptProperties().getProperty(name);
  return (v === null || v === '') ? def : v;
}

/** Токен проверяет сам Firestore (подпись и срок) по правилам базы: читаем профиль работника с его токеном. */
function checkUser_(token) {
  if (!token || String(token).length > 4000) throw new Error('нет токена входа');
  var parts = String(token).split('.');
  if (parts.length !== 3) throw new Error('неверный токен');
  var payload = JSON.parse(Utilities.newBlob(Utilities.base64DecodeWebSafe(parts[1])).getDataAsString());
  var email = String(payload.email || '').toLowerCase();
  if (!email || payload.email_verified !== true) throw new Error('Email не подтверждён');
  var url = 'https://firestore.googleapis.com/v1/projects/' + prop_('FB_PROJECT', '') +
    '/databases/(default)/documents/users/' + encodeURIComponent(email);
  var r = UrlFetchApp.fetch(url, { headers: { Authorization: 'Bearer ' + token }, muteHttpExceptions: true });
  if (r.getResponseCode() !== 200) throw new Error('доступ запрещён');
  var f = JSON.parse(r.getContentText()).fields || {};
  var status = f.status && f.status.stringValue;
  var blocked = !!(f.blocked && f.blocked.booleanValue === true);
  var strikes = f.strikes ? Number(f.strikes.integerValue || 0) : 0;
  if (status !== 'active' || blocked || strikes >= 2) throw new Error('профиль не активен');
  return { email: email };
}

/** Ограничение числа запросов в час на человека. */
function limit_(email) {
  var max = Number(prop_('RATE_PER_HOUR', '120'));
  var lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    var cache = CacheService.getScriptCache();
    var key = 'rl_' + Utilities.base64EncodeWebSafe(Utilities.computeDigest(Utilities.DigestAlgorithm.MD5, email)) +
      '_' + Utilities.formatDate(new Date(), 'UTC', 'yyyyMMddHH');
    var n = Number(cache.get(key) || 0);
    if (n >= max) throw new Error('лимит запросов исчерпан, повторите позже');
    cache.put(key, String(n + 1), 3700);
  } finally {
    lock.releaseLock();
  }
}

function fetchJson_(url, headers, body) {
  var r = UrlFetchApp.fetch(url, {
    method: 'post', contentType: 'application/json', headers: headers,
    payload: JSON.stringify(body), muteHttpExceptions: true
  });
  var code = r.getResponseCode();
  var text = r.getContentText();
  if (code < 200 || code > 299) throw new Error('ИИ: HTTP ' + code + ' ' + text.replace(/\s+/g, ' ').slice(0, 200));
  return JSON.parse(text);
}

/** Провайдер, ключ и модель берутся только из свойств скрипта: приложение выбрать их не может. */
function callAi_(prompt, image, mime, wantJson) {
  var provider = prop_('AI_PROVIDER', 'claude');
  var key = prop_('AI_KEY', '');
  if (!key) throw new Error('ключ ИИ не задан в скрипте');
  var r;
  if (provider === 'gemini') {
    var model = prop_('AI_MODEL', 'gemini-2.5-flash');
    var parts = [{ text: prompt }];
    if (image) parts.push({ inline_data: { mime_type: mime, data: image } });
    var cfg = { temperature: 0 };
    if (wantJson) cfg.responseMimeType = 'application/json';
    r = fetchJson_('https://generativelanguage.googleapis.com/v1beta/models/' + model + ':generateContent',
      { 'x-goog-api-key': key }, { contents: [{ parts: parts }], generationConfig: cfg });
    return r.candidates[0].content.parts[0].text;
  }
  if (provider === 'openai') {
    var m = prop_('AI_MODEL', 'gpt-4o-mini');
    var content = [{ type: 'text', text: prompt }];
    if (image) content.push({ type: 'image_url', image_url: { url: 'data:' + mime + ';base64,' + image } });
    var base = prop_('AI_BASE_URL', 'https://api.openai.com/v1').replace(/\/+$/, '');
    r = fetchJson_(base + '/chat/completions', { Authorization: 'Bearer ' + key },
      { model: m, messages: [{ role: 'user', content: content }] });
    return r.choices[0].message.content;
  }
  var cm = prop_('AI_MODEL', 'claude-haiku-5-5');
  var cc = [];
  if (image) cc.push({ type: 'image', source: { type: 'base64', media_type: mime, data: image } });
  cc.push({ type: 'text', text: prompt });
  r = fetchJson_('https://api.anthropic.com/v1/messages',
    { 'x-api-key': key, 'anthropic-version': '2023-06-01' },
    { model: cm, max_tokens: 3000, messages: [{ role: 'user', content: cc }] });
  var out = '';
  for (var i = 0; i < r.content.length; i++) if (r.content[i].type === 'text') out += r.content[i].text;
  return out;
}
