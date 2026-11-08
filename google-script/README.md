# ИИ-скрипт для работников (AiProxy.gs)

1. script.google.com → Новый проект → вставить код из `AiProxy.gs`.
2. Project Settings → Script properties: `AI_PROVIDER`, `AI_KEY`, `FB_PROJECT` (по желанию `AI_MODEL`, `AI_BASE_URL`, `RATE_PER_HOUR`).
3. Deploy → New deployment → Web app → Execute as: Me → Who has access: Anyone → скопировать адрес `…/exec`.
4. В приложении (аккаунт админа): Настройки → ИИ → «ИИ для работников» → вставить адрес → «Сохранить адрес для работников».
5. После изменения кода скрипта: Deploy → Manage deployments → Edit → New version (адрес не меняется).
6. У ключа ИИ у провайдера поставить лимит расходов.
