# Что исправлено / что сделать вручную

Исправлено в коде:
1. firestore.rules: удалено публичное чтение users/{id} (`allow get: if true`).
2. build.gradle.kts: пароли и путь к ключу больше не зашиты, только GitHub Secrets.
3. Удалены app/lager.jks, new-lager.jks, release.jks, key_base64.txt; в .gitignore добавлены *.jks, *.keystore, *.p12, *.b64, key_base64.txt (прежняя запись с неверным путём не работала).
4. AndroidManifest: allowBackup=false, cleartext запрещён, WorkBootReceiver не exported.
5. Updater: APK ставится, только если подписан тем же сертификатом, что и приложение.
6. Ai.kt: ключ Gemini передаётся в заголовке, а не в URL.
7. Редактор документов: санитайзер HTML + отключён файловый доступ WebView.

ВРУЧНУЮ (код этого не сделает):
- Опубликуйте firebase/firestore.rules в Firebase Console (Firestore → Rules).
- Удалите поле passHash у всех документов users в Firestore.
- Все ключи, что лежали в репозитории/архиве (lager, new-lager, release), считать скомпрометированными и больше не использовать: создайте новый keystore, внесите его в GitHub Secrets
  (LAGER_KEYSTORE_BASE64, LAGER_STORE_PASSWORD, LAGER_KEY_ALIAS, LAGER_KEY_PASSWORD).
  ВНИМАНИЕ: приложение с новой подписью не обновится поверх старого — нужна переустановка.
- Вычистите ключи из истории git (git filter-repo / BFG) или создайте репозиторий заново.
- Смените токен Apps Script (TOKEN), ключи EmailJS и AI-ключи.
- Включите Firebase App Check и ограничьте API-ключ по пакету/SHA-1.
