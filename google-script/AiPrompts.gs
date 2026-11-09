/**
 * Промпты ИИ. Лежат ТОЛЬКО здесь, в приложении у работников текста запросов нет:
 * приложение присылает имя задачи (task) и параметры (params), а запрос собирает этот скрипт.
 * Взломанное приложение может запустить лишь одну из задач ниже с ограниченными параметрами,
 * но не может отправить ИИ произвольный текст.
 *
 * Подключение: добавьте этот файл в проект Apps Script рядом с AiProxy.gs и в doPost замените
 * использование body.prompt на  buildPrompt_(body)  (см. google-script/README.md).
 *
 * Тексты скопированы из приложения 2.36.0 без изменений. Меняете промпт — меняйте здесь
 * (и в Ai*.kt, если им пользуется администратор со своим ключом напрямую).
 */

var AI_TEMPLATES_ = {
  recognize_base: "Page {{page}}/{{total}} of a scanned goods-receipt (delivery note or invoice; German, sometimes Czech/Dutch). May be grey, skewed, rotated: read anyway. Do not translate.\nAnswer ONLY with compact JSON, no other text: {\"i\":[{\"n\":\"name\",\"q\":\"qty\"}]}\nAdd the keys m, h, x, b to a line only when they apply, otherwise leave them out.\ni = every goods line of this page in page order, also struck-out lines and lines with quantity 0.\nNot goods (skip): freight, pallets, deposits, packaging, shipping paper, shipping bags, totals, table headers, addresses, footers.\nn = product name as written; append the wrapped name part or size line (\"20 kg\"); no article number, expiry date, batch or position number.\nq = delivered piece count as printed (Stück, Anz., Menge, Liefermenge, Množství, Aantal ...), number only. Pallets times pieces (\"9x24\") write as printed. Never weight, price, article number, ordered or open columns.\nb = EAN/GTIN (8-14 digits) of this line, only if printed.",
  recognize_pen: "Pen marks (often blue) in the left margin belong to the row they are aligned with; decide per row.\nm = \"c\" tick; \"x\" hand-drawn X or whole line struck through (goods did not arrive). Tick and X on the same row: \"x\".\nx = 1 only if the printed quantity itself is crossed out / X-ed while the rest of the line is not struck through.\nh = quantity handwritten with a pen next to the line, number only.\nIgnore other handwriting (names, dates, pallet counts like 6P, signatures).",
  identify: "This is page {{page}} of {{total}} of a scanned goods-receipt document (Lieferschein / delivery note / packing slip / invoice). It may be black-and-white or grayscale, a bit blurry, skewed, rotated or upside down: read it anyway.\nThe document is almost always in German; sometimes in Czech or Dutch. Do NOT translate anything.\nFind ONLY two things on THIS page. Do not read the goods table. Answer with JSON only (no markdown, no comments):\n{\"sender\": \"...\", \"order_number\": \"...\"}\n- sender: the company that SENT the goods (supplier / shipper / seller), usually in the letterhead, logo or the small sender line at the top (words like Absender, Lieferant, Verkäufer, Odesílatel, Dodavatel, Afzender, Leverancier). It is NOT the recipient (Empfänger, Lieferadresse, Příjemce, Odběratel, Ontvanger; here usually \"Ströh E-Commerce GmbH\"). Company name only, without street or city. NEVER output an e-mail address: if the sender is known only from an e-mail (for example info@papier.com), output just the word between \"@\" and the last dot, i.e. delete everything up to and including \"@\" and delete the last \".\" together with everything after it (info@papier.com -> papier). If it is not on this page, use \"none\".\n- order_number: a code that starts with ЕБ (Cyrillic) or EB (Latin) followed by exactly 7 digits, for example ЕБ1234567. It may be written with a space or hyphen (EB 1234567, EB-1234567), usually next to a label like Bestellnummer / Ihre Bestell-Nr. / Bestell-Nr. / Objednávka / Bestelnummer (not the Auftrag, Kunden- or Lieferschein number). It may also stand inside a text or a line of the table (for example \"BelegNr-EB2612798\"). If it is not on this page, use \"none\".",
  label: "This is a photo of a product label of the brand STRÖH (horse stable supplies). A label contains: the STRÖH logo, a standalone article number (4-8 digits) under the logo,\nthe product name (1-3 lines, may end with a volume or weight such as \"591 ml\"), the price in euro, a line \"% ab 2 Stück nur ...\" and a barcode (EAN) with its digits above the name.\nIf several labels are visible, read ONLY the label whose barcode digits are {{code}} (or the most complete label in the middle of the photo).\nAnswer with JSON only (no markdown, no comments): {\"article\":\"\",\"name\":\"\",\"barcode\":\"\"}\n- article: the article number exactly as printed, letters and digits only, no spaces.\n- name: the full product name exactly as printed, including volume / weight; WITHOUT the price, WITHOUT the \"% ab 2 Stück\" line, WITHOUT the logo text.\n- barcode: the EAN digits printed under the bars, digits only.\nNever invent values. Use \"\" when a value is not visible.",
  profile_gen: "You analyse the layout of delivery notes (Lieferschein) of ONE supplier: \"{{sender}}\". You get the first page image and what an automatic reader extracted from it (product | quantity).\nWrite layout rules for this supplier, so that the reader extracts the data correctly next time. Write in Russian, at most 8 short lines, each line is one concrete rule starting with \"- \".\nCover only what is specific for this layout: in which column the delivered quantity is; what the weight / size column means (per one unit or total); where the product size is written (in the name, on a second line, in a separate column); how pallets and multipacks are written; which lines are NOT goods; where the pen marks (ticks, crosses) stand; where the order number is.\nDo not invent anything that is not visible. No introduction, no markdown, nothing except the lines.\nExtracted:\n{{extracted}}",
  profile_refine: "You maintain layout rules for delivery notes of ONE supplier: \"{{sender}}\". The rules are used by an automatic reader, so that it extracts product names and quantities correctly.\nThe warehouse manager says the current rules give a wrong result. Rewrite the rules so that the manager's remark is fully taken into account. Look at the attached image of the delivery note and check the new rules against it.\nKeep what is still correct, fix what is wrong, add a short concrete example with real numbers from the image when the remark is about quantities. Write in Russian, at most 8 short lines, each line is one concrete rule starting with \"- \". No introduction, no markdown, nothing except the lines.\nCurrent rules:\n{{rules}}\nManager's remark:\n{{comment}}",
  parse_one: "You split ONE product line from a goods-receipt / delivery note / invoice into fields. The text may be German, English, Czech, Dutch or mixed. Do NOT translate the product name.\nAnswer with JSON only (no markdown, no comments):\n{\"manufacturer\": \"\", \"name\": \"\", \"size\": \"\", \"unit\": \"\", \"pack\": \"\"}\nRules:\n- manufacturer: the brand / producer, if it is in the text (for example \"DERBY\", \"marstall\", \"Keralit\", \"EQUIPUR\", \"AG\", \"St. Hippolyt\"). Otherwise use the hint below if it is clearly the producer. Otherwise \"\".\n- name: the product name WITHOUT manufacturer, size, unit, package word, article number, batch (Charge), expiry (MHD) and price. Keep the original language and spelling.\n- size: ONE number: weight or volume of ONE package, with a dot as the decimal separator (for example \"15\", \"2.5\", \"500\"). Multipacks like \"2x5 kg\" or \"28 x 40g\" -> put the size of one piece in size (5, 40). If there is no size, \"\".\n- unit: exactly one of: kg, g, t, l, ml, pcs (pcs only if it is counted in pieces, for example \"90 Kapseln\"). Map Lt / ltr / Liter / L to l. If there is no size, \"\".\n- pack: exactly one of: sack, bucket, bottle, can, canister, box, pack, bag, bale, tube, other, none. Hints: Sack / Säcke = sack; Eimer = bucket; Flasche / Fla = bottle; Dose / Tin = can; Kanister / Kan = canister; Karton = box; Beutel / Tüte = bag; Ballen = bale; Packung / Pack = pack. If the text does not say, use \"none\".\n- Never invent values that are not in the text. Do not use article numbers as size.\nHint (letterhead or sender of the document, may be empty): {{hint}}\nProduct line: {{text}}",
  parse_many: "You split product lines from ONE goods-receipt / delivery note into fields. The text may be German, English, Czech, Dutch or mixed. Do NOT translate product names.\nAnswer with JSON only (no markdown, no comments), one object per input line, in the same order, exactly {{count}} objects:\n{\"items\": [{\"manufacturer\": \"\", \"name\": \"\", \"size\": \"\", \"unit\": \"\", \"pack\": \"\"}]}\nRules:\n- manufacturer: the brand / producer if it is in the line (for example \"DERBY\", \"marstall\", \"Keralit\", \"EQUIPUR\", \"AG\", \"bitopEQUI\"). If the line has none but the document hint below is clearly the producer of these goods, use the hint. Otherwise \"\".\n- name: the product name WITHOUT manufacturer, size, unit, package word, article number, batch (Charge), expiry (MHD) and price. Keep the original language and spelling.\n- size: ONE number: weight or volume of ONE package, dot as decimal separator (\"15\", \"2.5\", \"500\"). Multipacks like \"2x5 kg\" or \"28 x 40g\": the size of one piece. No size in the line: \"\".\n- unit: exactly one of: kg, g, t, l, ml, pcs (pcs only if counted in pieces, e.g. \"90 Kapseln\"). Lt / ltr / Liter / L = l. No size: \"\".\n- pack: exactly one of: sack, bucket, bottle, can, canister, box, pack, bag, bale, tube, other, none. Sack = sack; Eimer = bucket; Flasche / Fla = bottle; Dose = can; Kanister / Kan = canister; Karton = box; Beutel / Tüte = bag; Ballen = bale; Packung = pack. Not stated: \"none\".\n- Never invent values that are not in the line. Never use article numbers as size.\nDocument hint (sender, may be empty): {{hint}}\nLines:\n{{numbered}}",
  columns: "Below are the first rows of a spreadsheet with a goods list (a delivery note / order). Columns are separated by TAB. The first number in every line is the row index (from 0); the columns after it are numbered from 0.\nDecide which column is which. Answer with JSON only (no markdown, no comments):\n{\"dataStartRow\": 0, \"nameCol\": 0, \"qtyCol\": 0, \"barcodeCol\": -1, \"sender\": \"\", \"order\": \"\", \"date\": \"\"}\nRules:\n- dataStartRow: index of the FIRST row that is a real product (after any header rows).\n- nameCol: the column with the product name / description (not the article number).\n- qtyCol: the PLANNED / ordered / expected quantity. Not the received (fact) quantity, not a price, not a sum, not a weight.\n- barcodeCol: the column with the barcode (EAN / GTIN, 8-14 digits), or -1.\n- sender, order, date: only if they are clearly written in the rows above the table (sender name, order number, date as dd.mm.yyyy or dd.mm.yyyy hh:mm). Otherwise \"\".\nRows:\n{{sample}}",
  catalog: "This image shows a list of products: a table, a screenshot of an ERP / warehouse program, a price list or a printed list. Extract EVERY product row.\nAnswer with JSON only (no markdown, no comments):\n{\"rows\":[{\"article\":\"\",\"name\":\"\",\"barcode\":\"\"}]}\nRules:\n- article: the item / article number exactly as printed (letters and digits, no spaces), or \"\".\n- name: the full product name / description as printed, including weight and packaging (e.g. \"DERBY Balance pelletiert 25 kg Sack\").\n- barcode: EAN / GTIN digits only (8-14 digits, no spaces), or \"\".\n- Never invent values. Use \"\" when a value is not on the image. Skip headers, footers, totals and page numbers.",
  list_photo: "This is a photo of a computer screen (an ERP program such as ALPHAPLAN) or of a paper list with products. The picture is already converted to black and white.\nExtract EVERY product row. Answer with JSON only (no markdown, no comments):\n{\"rows\":[{\"article\":\"\",\"name\":\"\",\"barcode\":\"\"}]}\nRules:\n- article: the value from the column headed \"Artikel-Nr.\" / \"Artikel\" / \"Art.-Nr.\" (digits and letters, no spaces). Do NOT take the product-group column (a short code that repeats on many rows, e.g. \"WGU 639\"), nor stock / quantity / price columns.\n  If no header is visible: the digits-only column with fewer than 13 digits whose values differ from row to row is the article.\n- barcode: the EAN / GTIN column with 13 digits (8, 12 or 14 digits are possible too), digits only, no spaces. \"\" if there is no such column.\n- name: the full text of the \"Bezeichnung\" / description column exactly as printed, including sizes, colours and units. Keep letters, digits and symbols as they are.\n- Skip the title bar, column headers, filter rows (such as \"%horseware\"), footers (Lagerplatz, Charge, Bestand, Reserviert ...) and any other column.\n- Never invent or guess values. Use \"\" when a value is not readable.",
  barcode: "На фото штрих-код (возможно, плохо читаемый, изогнутый или блестящий). Считай цифры, напечатанные под штрихами, и/или распознай сами штрихи. Если кодов несколько — верни самый крупный и чёткий. Ответь ТОЛЬКО цифрами кода без пробелов и пояснений. Если код разобрать нельзя — ответь словом NONE. Ничего не выдумывай.",
  ping: "Reply with the single word OK",
};

// Лимиты на параметры: даже если приложение взломано, длинный текст в ИИ не уйдёт.
var AI_LIMITS_ = {
  str: { rules: 3000, comment: 1500, extracted: 6000, sender: 200, text: 600, hint: 300, code: 20 },
  maxLines: 40,        // parse_many: строк за один запрос
  maxLine: 300,        // parse_many: длина одной строки
  maxRows: 25,         // columns: строк таблицы
  maxCols: 40,         // columns: столбцов
  maxCell: 60,         // columns: длина ячейки
  maxPage: 200         // номер страницы
};

// Какие задачи требуют картинку / не допускают её.
var AI_TASKS_ = {
  recognize:      { image: true },
  identify:       { image: true },
  label:          { image: true },
  profile_gen:    { image: true },
  profile_refine: { image: false },   // картинка необязательна
  parse_one:      { image: false, noImage: true },
  parse_many:     { image: false, noImage: true },
  columns:        { image: false, noImage: true },
  catalog:        { image: true },
  list_photo:     { image: true },
  barcode:        { image: true },
  ping:           { image: false, noImage: true }
};

function aiFill_(tpl, vars) {
  return tpl.replace(/\{\{(\w+)\}\}/g, function (m, k) {
    return Object.prototype.hasOwnProperty.call(vars, k) ? String(vars[k]) : m;
  });
}

function aiStr_(v, max, name) {
  var s = (v === undefined || v === null) ? '' : String(v);
  if (s.length > max) throw new Error('Параметр ' + name + ' слишком длинный');
  return s;
}

function aiInt_(v, min, max, name) {
  var n = parseInt(v, 10);
  if (isNaN(n) || n < min || n > max) throw new Error('Неверный параметр ' + name);
  return n;
}

/**
 * Собирает текст запроса по имени задачи. Бросает Error при неизвестной задаче или неверных параметрах:
 * doPost должен вернуть {ok:false, error:e.message}. hasImage — пришла ли картинка.
 */
function buildPrompt_(body) {
  var task = String(body.task || '');
  var def = AI_TASKS_[task];
  if (!def) throw new Error('Неизвестная задача ИИ');
  var hasImage = !!body.image;
  if (def.image && !hasImage) throw new Error('Для задачи нужна картинка');
  if (def.noImage && hasImage) throw new Error('Для этой задачи картинка не нужна');
  var p = body.params || {};
  var L = AI_LIMITS_;
  var T = AI_TEMPLATES_;

  switch (task) {
    case 'recognize': {
      var page = aiInt_(p.page, 1, L.maxPage, 'page');
      var total = aiInt_(p.total, 1, L.maxPage, 'total');
      var rules = aiStr_(p.rules, L.str.rules, 'rules');
      var vars = { page: page, total: total };
      var txt = aiFill_(T.recognize_base, vars);
      if (p.marks !== false) txt += '\n' + T.recognize_pen;
      if (rules.trim()) txt += '\nSupplier layout notes (more important than the rules above):\n' + rules.trim();
      return txt;
    }
    case 'identify':
      return aiFill_(T.identify, {
        page: aiInt_(p.page, 1, L.maxPage, 'page'),
        total: aiInt_(p.total, 1, L.maxPage, 'total')
      });
    case 'label':
      return aiFill_(T.label, { code: aiStr_(p.code, L.str.code, 'code').replace(/[^0-9A-Za-z]/g, '') });
    case 'profile_gen':
      return aiFill_(T.profile_gen, {
        sender: aiStr_(p.sender, L.str.sender, 'sender'),
        extracted: aiStr_(p.extracted, L.str.extracted, 'extracted')
      });
    case 'profile_refine':
      return aiFill_(T.profile_refine, {
        sender: aiStr_(p.sender, L.str.sender, 'sender'),
        rules: aiStr_(p.rules, L.str.rules, 'rules'),
        comment: aiStr_(p.comment, L.str.comment, 'comment')
      });
    case 'parse_one':
      return aiFill_(T.parse_one, {
        text: aiStr_(p.text, L.str.text, 'text'),
        hint: aiStr_(p.hint, L.str.hint, 'hint')
      });
    case 'parse_many': {
      var lines = p.lines;
      if (!Array.isArray(lines) || lines.length < 1 || lines.length > L.maxLines) throw new Error('Неверный параметр lines');
      var numbered = lines.map(function (l, i) { return (i + 1) + '. ' + aiStr_(l, L.maxLine, 'lines'); }).join('\n');
      return aiFill_(T.parse_many, { count: lines.length, hint: aiStr_(p.hint, L.str.hint, 'hint'), numbered: numbered });
    }
    case 'columns': {
      var rows = p.rows;
      if (!Array.isArray(rows) || rows.length < 1 || rows.length > L.maxRows) throw new Error('Неверный параметр rows');
      var sample = rows.map(function (r, i) {
        if (!Array.isArray(r) || r.length > L.maxCols) throw new Error('Неверный параметр rows');
        return i + '\t' + r.map(function (c) {
          return aiStr_(c, L.maxCell, 'rows').replace(/[\t\n]/g, ' ').trim();
        }).join('\t');
      }).join('\n');
      return aiFill_(T.columns, { sample: sample });
    }
    case 'catalog':
    case 'list_photo':
    case 'barcode':
    case 'ping':
      return T[task];
  }
  throw new Error('Неизвестная задача ИИ');
}
