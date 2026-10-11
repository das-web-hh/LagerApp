# Lager — складское приложение (Android, Kotlin + Jetpack Compose)

Приложение для приёма товара на складе: каталог, приём вручную со сканером штрих-кодов,
история приёмов, фото и накладные. Данные — в Firebase (Firestore), файлы — на Google Диске.
Сборка APK — автоматически на GitHub Actions.

## Возможности

- **Каталог товаров** — список по названию; карточка: название, вес/объём (кг, г, т, л, мл, шт),
  тип (бутылка, коробка, мешок…), производитель, артикул, штрих-код, несколько фото; сохранить / удалить.
  Импорт из Excel (.xlsx) с выбором столбцов (название, штрих-код, артикул).
- **Приём → Ручной** — поиск как в Google (название, артикул или штрих-код, сканер справа в строке),
  подсказки из каталога, количество по каждой позиции, окно «Сохранить приём»:
  отправитель, номер заказа, фото, накладные. Время ставится автоматически.
- **История** — партии приёмов (новые сверху); карточка партии: все поля, время, позиции,
  фото и накладные — изменить, сохранить, удалить.
- **Настройки** — отдельные разделы (Сканер, Google Диск). Хранятся в Firebase **по аккаунту**:
  зайдя с другого телефона под тем же логином, вы получите те же настройки.
- Системная кнопка «Назад»: с главной — выход только двойным нажатием.

## Приём вручную из ответа ИИ

Накладную (PDF или фото) отдают внешнему ИИ с промтом ниже, а готовый ответ вставляют в приложение —
оно само сохраняет приём в историю (Firebase + локальная база). Окно «Приём → Ручной» при этом остаётся прежним.

**Как вставить**
- Кнопка **«Вставить из буфера»** в строке названия окна «Приём вручную» берёт текст из системного буфера обмена, или
- текст вставляют прямо в строку поиска.

**Защита от случайной вставки.** Текст принимается только если **первая строка — метка `#LAGER`**.
Любой другой вставленный текст игнорируется (в поле поиска он остаётся как обычный запрос, кнопка пишет подсказку).

**Формат ответа ИИ** (столбцы разделены табуляцией):

```
#LAGER
Отправитель/EB2612673/16.07.2026 16:48
EQUIPUR - digest plus "P" 25 kg<TAB>1<TAB>
EquiPower - Leinöl 5000 ml<TAB>2<TAB>
Monopräparat MSM 800 g<TAB>3<TAB>штрих-код (необязательно)
```

- Строка 2: отправитель / номер заказа (`EB` + 7 цифр) / дата и время из имени файла `Сканирование_ГГГГММДД-ЧЧММ`.
  Нет отправителя или номера заказа — `no_data` (также понимаются `no_name`, `EB_no_data`, `no_date`);
  время приёма тогда ставится текущее.
- Товар ищется в каталоге по названию (точно, без учёта регистра), затем по штрих-коду; не найден — сохраняется по названию.
- Количество 0 — строка пропускается. Нечитаемая строка — приём не сохраняется, приложение показывает эту строку.
- Заказ с уже принятым номером повторно не сохраняется.
- Код: `app/src/main/java/ru/storage/app/ManualImport.kt` (разбор и сохранение), `Receive.kt` (кнопка и поле).

### Промт для ИИ

Целиком копировать в чат с ИИ, затем прикрепить файл накладной (имя файла должно быть вида `Сканирование_20260716-1648.pdf`).

````text
# ROLE AND CONTEXT
You are an expert AI Document Processing, OCR, and Data Structuring specialist handling diverse invoice/delivery layouts. Your objective is 100% data extraction accuracy based on page markings, keyword filtering, and strict layout logic.

# TASK
Extract document data according to filtering marks and keyword exclusions, or extract the entire document if no marks exist. Format the output EXCLUSIVELY as a single code block. Each product item must occupy EXACTLY one line. Line breaks inside items are strictly forbidden.

# ABSOLUTE FILTERING LOGIC
1. Critical Blank Document Condition (No Marks):
   - Scan the entire document for any handwritten or visual marks (checkmarks "✓", crosses "X", arrows).
   - IF ABSOLUTELY NO MARKS EXIST: Switch to "Accept All" mode. Extract EVERY printed product row. Do not skip anything.
   - IF MARKS EXIST: Proceed to Exclusion and Inclusion rules below.

2. Exclusion Rule (When document has marks):
   - If a row starts with or contains a cross ("X") before the item number, name, or quantity -> COMPLETELY EXCLUDE this row.

3. Strict Keyword Exclusion for Unmarked Rows:
   - If a row DOES NOT have a checkmark ("✓"), check its text entirely (name, description, notes).
   - COMPLETELY EXCLUDE the row if it contains ANY of these words/codes (case-insensitive, as a whole word or substring):
     ew, europallet, pallet, versand, EB (followed by numbers), palletenfrachtkostenpauseschale, versandkosten, landehilfsmittel, post.

4. Inclusion Rule (When document has marks):
   - Include a row ONLY if it has a clear handwritten checkmark ("✓") or a clear handwritten confirmation. Do not omit checked items.

5. Multi-Page Collision Resolution:
   - If a product row appears across multiple conflicting pages, locate the absolute "First Page".
   - Look for a handwritten arrow (optionally with text like "ströh"). Extract data ONLY from the position indicated by the arrow.

# METADATA EXTRACTION (LINES 1 AND 2)
Line 1 is ALWAYS the fixed marker, exactly as written: #LAGER
Line 2 contains metadata separated by slashes (/):
- Sender Name: Priority: Email domains (e.g., info@innopha.de -> Innopha), logos/headers, website URLs. Exclude creditor@stroeh.de, stroeh, ströh. Fallback: no_data.
- Document Number: Number starting with "EB" followed by 7 digits (e.g., EB2613026). Fallback: no_data.
- Date and Time (FROM FILENAME): Extract strictly from the source file name matching the pattern Сканирование_YYYYMMDD-HHMM (e.g., from Сканирование_20260817-1254.jpg extract date and time and format as DD.MM.YYYY HH:MM, resulting in 17.08.2026 12:54). Fallback: no_data.
- Format for Line 2: [Sender Name]/[Document Number]/[Date and Time]

# QUANTITY & BARCODE RESOLUTION
1. Standard Printed Quantity: If accepted and no handwritten corrections exist, use the printed delivery quantity column ("aktuelle Liefermenge", e.g., "5,00" -> 5).
2. Handwritten STK/Unit Priority: If text contains "stk" or "штук" next to a number (e.g., "51 stk"), ignore printed numbers and use this exact handwritten number.
3. Handwritten Crosses/Zeros: If a printed quantity is crossed out or has an "X"/"0" over/next to it, force Quantity = 0.
4. Strict Quantity Formatting: Must be a clean integer. NEVER include periods or commas (truncate decimals, e.g., "5,00" -> 5). If invalid/missing, set Quantity = 0.
5. Barcode Rule: Extract physical printed/written barcode only. Leave column completely empty if missing. NO INTERNET LOOKUP.

# OUTPUT FORMAT
- Start immediately with the code block. NO introductory or concluding text outside the block.
- Columns within product rows must be separated by a single horizontal tab character.

Format structure:
#LAGER
[Sender Name]/[EB Number]/[Date and Time]
[Product Name]	[Quantity]	[Barcode]
[Product Name]	[Quantity]	[Barcode]

# CONSTRAINTS
- Output MUST contain exactly one output code block and nothing else.
- The first line of the block MUST be exactly #LAGER.
- Remove all internal newline characters (\n, \r) from product names and replace with a single space.
- Strip leading/trailing whitespace from all extracted values.
````

## Как хранятся данные

| Что | Где |
|---|---|
| Товары | Firestore, коллекция `products` + локальная база SQLite на телефоне |
| Приёмки | Firestore, коллекция `receipts` + SQLite |
| Настройки аккаунта | Firestore, коллекция `settings`, документ = логин |
| Фото и накладные | Google Диск (папка) + копия в памяти телефона |

**Экономия лимитов Firebase.** Если локальной базы нет — загружается всё. Дальше проверяются
только последние 10 записей (по `updatedAt`); если среди них есть новые — ещё 10, и так далее.
Каталог не чаще раза в 10 минут, история — раз в 2 минуты (кнопка «Обновить» — сразу).
Удаление — «мягкое» (`deleted: true`), чтобы оно дошло до других устройств.

**Файлы.** Имя файла на Диске — код: `PR-…` (фото товара), `RC-…` (фото приёма),
`IN-…` (накладная). Связь с товаром или партией хранится в Firestore (списки `photos` и `invoices`).
Синхронизация с Диском: первый раз — всё, потом по 10 новых, пока есть новые.

### Структура документов Firestore

```
products/{id}   name, article, barcode, weight, unit, pack, manufacturer, photos[], updatedAt, deleted
receipts/{id}   sender, orderNo, receivedAt, receiver, items[{productId,name,qty}], photos[], invoices[], updatedAt, deleted
settings/{login} driveUrl, driveFolder, driveToken, useFront, formats[], updatedAt
```

## Установка с нуля

### 1. Firebase

1. https://console.firebase.google.com → создать проект.
2. Добавить Android-приложение с именем пакета **`ru.storage.app`**, скачать `google-services.json`
   и положить в `app/google-services.json` (имя пакета в файле должно совпадать с `applicationId`).
3. Build → **Firestore Database** → Create database.
4. Вкладка Rules → вставить содержимое файла [`firebase/firestore.rules`](firebase/firestore.rules) → Publish.
5. Тариф Spark (бесплатный) подходит: Firebase Storage **не используется**.

Коллекции создаются сами при первой записи из приложения.

### 2. Google Диск (скрипт)

1. Создать на Google Диске папку для файлов, открыть её и скопировать ID из адреса (`…/folders/<ID>`).
2. https://script.google.com → новый проект → вставить содержимое
   [`google-script/Code.gs`](google-script/Code.gs) в `Code.gs`.
3. Один раз запустить функцию `authorize_` (▶ Выполнить) и выдать разрешения.
4. Deploy → New deployment → **Web app**: Execute as — *Me*, Who has access — *Anyone* → Deploy.
   Скопировать адрес, заканчивающийся на `/exec`.
5. При обновлении скрипта: Deploy → Manage deployments → Edit → Version: **New version** → Deploy
   (адрес не меняется).
6. В приложении: Профиль → Настройки → **Google Диск** → вставить адрес `/exec` и ID (или ссылку) папки →
   «Сохранить и проверить». Настройки уйдут в Firebase и появятся на других устройствах этого аккаунта.

Необязательная защита: в начале скрипта переменная `TOKEN`. Если задать строку, её же нужно ввести
в поле «Токен» в приложении (сайт склада токен не отправляет, поэтому по умолчанию пусто).

### 3. Вход

Сейчас логин `admin`, пароль `admin` (локально). Логин используется как ключ настроек в Firebase.

## Сборка APK

Каждый `git push` в ветку `main` запускает GitHub Actions (`.github/workflows/build.yml`).
Готовый APK: репозиторий → **Actions** → последний запуск → **Artifacts → LagerApp-debug**.

### Работа из Termux

```bash
cd ~/Storage
git pull --rebase
# ...изменения...
git add .
git commit -m "Описание"
git pull --rebase
git push
```

## Структура кода (`app/src/main/java/ru/storage/app`)

| Файл | Назначение |
|---|---|
| `MainActivity.kt` | вход, нижнее меню, системная «Назад», `Prefs` |
| `Screens.kt` | главное окно, профиль, раздел «Приём» |
| `Catalog.kt` | каталог, карточка товара, импорт из Excel |
| `Receive.kt` | приём вручную, поиск, окно сохранения приёма |
| `History.kt` | история приёмов, карточка партии |
| `Settings.kt` | настройки по разделам, синхронизация настроек с Firebase |
| `Data.kt` | модели и локальная база (SQLite) |
| `Cloud.kt` | Firestore: запись и экономная синхронизация |
| `Drive.kt` | обмен с Google Диском (скрипт), синхронизация файлов |
| `Media.kt` | фото/файлы: съёмка, выбор, сжатие, показ |
| `Xlsx.kt` | чтение .xlsx без сторонних библиотек |
| `Scanner.kt` | сканер штрих-кодов (CameraX + ML Kit) |

## Ограничения и безопасность

- Импорт Excel — только `.xlsx` (старый `.xls` не читается).
- Правила Firestore сейчас открыты (в приложении нет Firebase Auth). Токен и адрес скрипта
  лежат в `settings`, поэтому перед реальной эксплуатацией нужно перейти на Firebase Auth и закрыть правила.
- Бесплатные лимиты Firestore: 50 000 чтений и 20 000 записей в сутки.

## Подпись APK (чтобы приложение обновлялось поверх старого)

Без постоянного ключа каждая сборка подписывается новым случайным ключом: Android не обновляет такое
приложение, а Google Play Protect считает его новым. Поэтому APK подписывается **одним и тем же ключом**,
который хранится в секретах GitHub (в репозиторий ключ не кладётся!).

Один раз, в Termux:

```bash
pkg install -y openjdk-17 gh
cd ~
keytool -genkeypair -keystore lager.jks -alias lager -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12 -storepass 'ВАШ_ПАРОЛЬ' -keypass 'ВАШ_ПАРОЛЬ' -dname "CN=Lager, O=Lager, C=DE"
base64 -w 0 lager.jks > lager.b64

gh auth login        # GitHub.com → HTTPS → Login with a web browser
gh secret set KEYSTORE_BASE64   -R das-web-hh/LagerApp < lager.b64
gh secret set KEYSTORE_PASSWORD -R das-web-hh/LagerApp -b'ВАШ_ПАРОЛЬ'
gh secret set KEY_ALIAS         -R das-web-hh/LagerApp -b'lager'
gh secret set KEY_PASSWORD      -R das-web-hh/LagerApp -b'ВАШ_ПАРОЛЬ'
```

**Файл `lager.jks` и пароль обязательно сохраните** (например, на Google Диск в личную папку): если их
потерять, обновлять приложение поверх установленного станет невозможно.

Дальше каждый `git push` собирает подписанный `release`-APK (Actions → Artifacts → `LagerApp`);
номер версии растёт сам. Если секретов нет, собирается обычный debug-APK.


## Приём по документу: два режима

- **Приём по имени** — берутся все строки накладной подряд (галочки, крестики и вычеркнутые тоже): печатное количество = план,
  факт и брак вводятся вручную, можно добавить товар, которого нет в накладной.
- **Автоприём** — учитываются отметки ручкой: ✓ — пришло (берётся количество), ✕ или вычеркнуто — не пришло (сохраняется с количеством 0),
  печатное количество перечёркнуто и рядом дописано ручкой число — берётся рукописное. Строки без отметки помечаются «без отметки — проверьте».
- Фрахт, паллеты, залог и итоговые строки в оба режима не попадают.

## Подпись APK (важно)

Файл `app/lager.jks` — постоянный ключ подписи. Благодаря ему каждая новая сборка обновляет приложение поверх старой без удаления.
Сохраните копию этого файла в надёжном месте: если ключ потерять или заменить, приложение придётся один раз удалить и поставить заново.

## Пересчёт «колесом» (приём по имени)

Список товаров — бесконечное колесо (после последней строки пустая строка, затем снова первая): середина крупная, верх и низ вдвое меньше.
Поиск сверху фильтрует этот список с первого символа. Тап по строке — +1 к факту, тап по числу справа — барабан цифр 0…9999
(через 3 секунды после остановки значение применяется само). Свайп справа налево — кнопки «Брак» и «Удалить», свайп вправо закрывает.
Долгое нажатие на строку — правка названия и плана. Позиции, до которых не дотронулись, считаются как факт 0.

### Экран приёма по имени (компоновка)

Сверху: кнопки «Далее» и «Повторить листы (N)» (если есть нераспознанные), затем «Отправитель» и «Заказ», затем поиск по списку со значком «+»
(добавить товар). Колесо всегда показывает весь список; подсказки поиска появляются под полем, тап по подсказке ставит товар на середину колеса.

## Проверка накладной при приёме (версия 2.10)

Накладная сначала отправляется на ИИ **только за отправителем и номером заказа** (лёгкий запрос, первые до 4 листов).
Дальше:

1. **Заказ уже принят и накладной у него нет** — документ привязывается к этому приёму, товары не дублируются,
   недостающий отправитель дописывается. Остальное с документа не запрашивается.
2. **Заказ уже принят вместе с накладной** — документ не принимается, на распознавание не уходит.
3. **Заказа в базе нет** — документ читается целиком (с подтверждённым шаблоном поставщика, если он есть; нет шаблона — создаётся новый).
   После этого:
   - отправитель и товары совпали с приёмом без накладной, номера заказа у приёма нет → накладная привязывается, номер заказа дописывается;
   - у приёма нет ни отправителя, ни номера заказа, а дата и товары совпали → привязывается накладная, дописываются отправитель и номер заказа.
   Приёмки ищутся в окне ±7 дней от даты документа (`InvoiceCheck.GOODS_WINDOW_DAYS`); приём с другим номером заказа не подходит.
4. **Повторы не принимаются:** номер заказа, содержимое файла (SHA-256, хранится в приёме как `docHash`) и имя файла — жёсткая проверка.
   Совпадение только по дате и времени — предупреждение с кнопкой «Обработать всё равно».
5. **Один и тот же товар с тем же количеством за один день** — мини-окно «Принять / Отказать» по каждому такому товару.
   Отказанный товар в приём не попадает.

Код: `InvoiceCheck.kt` (правила), `AutoReceive.kt` (порядок шагов, окна), `Cloud.kt` (`findDuplicate`), `Ai.kt` (`identify`).

## Отправка и Инфо (версия 2.13)

- **Отправка** — три строки в стиле списка операций: Post paket, DHL Postwagen, Spedition.
- **Post paket** — поле «номер/заметка» с «+», ниже список посылок. Круглая жёлтая кнопка сканера (иконка «два ряда квадратов и луч»)
  перетаскивается в любое место и плавает над списком. Сканер документа: один или несколько снимков → «Сохранить» →
  в фоне OCR + штрих-коды (без ИИ) → база, Firebase, снимки на Google Диск.
  Карточка посылки: сверху данные, снизу снимки. Кнопки «Копировать номер» и «Отследить» (dhl.de).
- **Разбор этикеток** (`LabelOcr.kt`, `LabelParser`): «Paket bis 5/10 kg», Common Label (PLG@DHL Business Portal),
  англоязычные (From/To, Shipment No., Billing No.), новый макет 2020+ (Nachnahme, Filial-Routing, GoGreen, 10+ kg), JJD, S10.
  Поля: номер, продукт, получатель, адрес, отправитель, вес, Leitcode, номер расчёта, референс, отметки.
  Если номер не найден, страница пробуется повёрнутой на 90/180/270°. Повтор номера помечается в заметке.
- **Снимки на телефоне хранятся 48 часов** (`SendRetention`), дальше остаются на Google Диске; не загруженные на Диск не удаляются.
- **DHL Postwagen** — «Добавить», итог за сегодня и история. В окне добавления две большие строки «Отправлено» и «Получено»
  (тап +1 с вибрацией и голосом, число справа — барабан), «Сохранить». Карточка операции: дата, время, разница, кто сохранил.
- **Инфо** — за день/неделю/месяц: принято шт, приёмки, позиции, брак, расхождения, накладные, фото, Post paket,
  Postwagen (отправлено/получено), активность за день, график по часам, топы, «Кто принимал».
  Приёмки, посылки и операции Postwagen открываются из Инфо. Кнопка «Выгрузить таблицей (CSV для Excel)».


## Приём B-Ware (версия 2.14)

Плитка «Работа → Приём B-Ware» — приём испорченного товара, который привезли. Код: `BWare.kt`.

**Окно ввода**
1. Имя отправителя.
2. Номер заказа; справа кнопка «Далее» (активна, когда в списке есть товар; у каждой позиции количество должно быть больше 0).
3. Поиск по каталогу: подсказки появляются после 3-го символа, тап по подсказке добавляет товар. Кнопка «+» (или Enter)
   добавляет текст как написан, если товара нет в каталоге. Уже добавленный товар второй раз не дублируется — колесо встаёт на него.
4. Ниже — колесо-список (как в «По наименованию»): тап по строке +1 с вибрацией и голосом, тап по числу — барабан цифр,
   свайп влево — «Срок» и «Удалить», долгое нажатие — правка названия.
   **Срок годности** — под названием (тап) или кнопка «Срок» при свайпе; открывается календарь, «Очистить» убирает дату.
5. Кнопка снизу «Подробности повреждений»: варианты «когда испортился» (в перевозке / уже при погрузке / неизвестно),
   «в каком виде привезли» (паллет упал, коробка помята/мокрая, упаковка порвана и т.д.), «содержимое» (рассыпалось,
   осталось внутри, вытекло наружу, разбито…) и поле «Другое». Выбранное попадает в документ.

**Окно «Далее»**: номер машины и номер прицепа, предпросмотр документа (акт: отправитель, заказ, машина, прицеп, принял,
дата, таблица товаров с количеством и сроком годности, подробности, подписи) и кнопка «🖨 Печать» (системная печать / PDF).
«Закончить и очистить» сбрасывает приём. Введённое сохраняется в памяти приложения при переходах между вкладками,
но не записывается в историю и Firebase.

## Инвентарь B-Ware и окно выбора даты (версия 2.15)

- **Инвентарь B-Ware** (`BWareInventory.kt`) — регистрация испорченного товара на складе. Как «Приём B-Ware», но без отправителя
  и заказа: поиск по каталогу с «+», колесо-список (тап +1, барабан, вибрация, голос), «Далее» — документ «Опись испорченного товара»
  с печатью. Свайп влево по строке: **Срок**, **Причины и вид**, **Удалить**. В «Причины и вид» — причины порчи, тип целостности
  (порван, рассыпан, содержимое осталось внутри / вытекло…) и примечание; всё попадает в документ.
- **Окно выбора даты** (`DatePick.kt`, `ExpiryPickerDialog`) — сверху годы (барабан влево-вправо), слева 12 месяцев (барабан),
  в основной части дни по неделям как в календаре. Используется для срока годности в «Приём B-Ware» и «Инвентарь B-Ware».
