# Hookah Bot & Mini App Monorepo

Монохранилище для Telegram-бота и Mini App агрегатора кальянных. Этот шаг содержит минимальный каркас backend (Ktor) и dev-frontend (Vite + TypeScript).

## Требования
- JDK 21
- Node.js LTS (для miniapp)
- Docker + Docker Compose

## Backend локально
```bash
./gradlew :backend:app:run
```

### Эндпойнты
- `GET /health` → `{ "status": "ok" }`
- `GET /version` → сведения о версии/окружении
- `GET /db/health` → проверка соединения с Postgres (возвращает `disabled`, если БД не настроена)

## Mini App API

### Auth: `POST /api/auth/telegram`
`initData` — это строка, которую Telegram Mini App передаёт клиентскому приложению. Её нельзя «пересобирать» или менять — отправляйте на сервер ровно как есть. На сервере подпись `initData` проверяется с использованием `TELEGRAM_BOT_TOKEN` (даже если бот/поллинг не используются), плюс применяются ограничения по времени: `TELEGRAM_MINIAPP_INITDATA_MAX_AGE_SECONDS` и `TELEGRAM_MINIAPP_INITDATA_MAX_FUTURE_SKEW_SECONDS`.

Пример запроса:
```json
{
  "initData": "<telegram-initData-from-miniapp>"
}
```

Пример ответа:
```json
{
  "token": "<session-token>",
  "expiresAtEpochSeconds": 1712345678,
  "user": {
    "telegramUserId": 123456789,
    "username": "hookah_user",
    "firstName": "Alex",
    "lastName": "Ivanov"
  }
}
```

#### Session token TTL
TTL session token задаётся переменной `API_SESSION_TTL_SECONDS`. Значение `expiresAtEpochSeconds` в ответе `POST /api/auth/telegram` соответствует времени истечения токена в epoch seconds. После истечения TTL нужно снова получить токен через `POST /api/auth/telegram`.

#### Suspended mode (API_GUEST_SUSPENDED_MODE)
Режим `explain` возвращает 423 (`SERVICE_SUSPENDED` / `SUBSCRIPTION_BLOCKED`) для недоступных заведений. Режим `hide` скрывает причины недоступности: для некоторых гостевых запросов ответ будет как `NOT_FOUND` (404) вместо 423 (например, `GET /api/guest/venue/{id}` и `GET /api/guest/venue/{id}/menu` для suspended заведений).

### Использование Bearer token для `/api/guest/*`
Все гостевые эндпойнты защищены `Authorization: Bearer <token>`, где `<token>` — результат `POST /api/auth/telegram`.

Основные маршруты:
- `GET /api/guest/catalog`
- `GET /api/guest/venue/{id}`
- `GET /api/guest/venue/{id}/menu`
- `GET /api/guest/table/resolve?tableToken=...`
- `GET /api/guest/order/active?tableToken=...`
- `POST /api/guest/order/add-batch`
- `POST /api/guest/staff-call`
- `GET /api/guest/_ping`

### Ошибки и envelope
Ответы об ошибках возвращаются в формате `ApiErrorEnvelope`:
```json
{
  "error": {
    "code": "INVALID_INPUT",
    "message": "Invalid input"
  },
  "requestId": "..."
}
```

Ожидаемые коды ошибок и статусы:
- `UNAUTHORIZED` (401) — отсутствует или невалидный Bearer token.
- `INITDATA_INVALID` (401) — `initData` не прошла проверку подписи/срока.
- `INVALID_INPUT` (400) — некорректные параметры (например, `tableToken`, `reason`, `comment`).
- `NOT_FOUND` (404) — неизвестный `tableToken` и т.п.
- `SERVICE_SUSPENDED` (423) — venue suspended.
- `SUBSCRIPTION_BLOCKED` (423) — подписка past_due/suspended.
- `DATABASE_UNAVAILABLE` (503) — база данных недоступна.

### Security note
**Не логируйте и не шарьте `initData` и session token**, не кладите их в багрепорты или тикеты. Все примеры в README содержат только placeholder-значения.

## Database (Postgres + Flyway)
- Скопировать переменные окружения: `cp .env.example .env`
- Для локальной разработки PostgreSQL: `docker compose up -d postgres`
- Загрузить переменные окружения в shell (bash): `set -a; source .env; set +a`
- Запустить backend: `./gradlew :backend:app:run`
- Проверить: `curl -f http://localhost:8080/db/health`
- В Docker backend подключается к `postgres:5432`, снаружи Docker — к `localhost:5432`

## Mini App локально
```bash
cd miniapp
npm ci
npm run dev
```

Переменная `VITE_BACKEND_PUBLIC_URL` (по умолчанию `http://localhost:8080`) управляет URL backend для запросов из miniapp.

## Прокси Mini App через backend
```bash
export MINIAPP_DEV_SERVER_URL=http://localhost:5173
./gradlew :backend:app:run
# открыть http://localhost:8080/miniapp/
```

## Сборка miniapp
```bash
cd miniapp
npm ci
npm run build
```

## Запуск через Docker
```bash
cp .env.example .env
docker compose up --build
curl http://localhost:8080/health
curl http://localhost:8080/version
```

Для Docker `MINIAPP_DEV_SERVER_URL` не сможет достучаться до localhost хоста; по умолчанию при открытии `http://localhost:8080/miniapp/` будет выведена подсказка (это ожидаемо на этом шаге).

## Mini App статика
- Если нет dev-сервера, можно собрать `npm run build` и указать `MINIAPP_STATIC_DIR=miniapp/dist`, после чего открыть `http://localhost:8080/miniapp/`.

## Telegram Bot (long polling / webhook)
- Скопировать `.env.example` → `.env` и заполнить переменные `TELEGRAM_*` (токен не коммитить).
- Для long polling: `set -a; source .env; set +a; ./gradlew :backend:app:run`.
- Для webhook: нужен публичный HTTPS. Запустить backend, затем вручную вызвать setWebhook:
  ```bash
  curl -X POST "https://api.telegram.org/bot<TELEGRAM_BOT_TOKEN>/setWebhook" \
    -d "url=https://your-domain/telegram/webhook" \
    -d "secret_token=<TELEGRAM_WEBHOOK_SECRET_TOKEN>"
  ```
- Проверка: в Telegram отправить `/start` и `/start <table_token>` — должно показать меню или контекст стола.
- Для локальной проверки table_token (при поднятой БД `docker compose up -d postgres`):
  ```sql
  INSERT INTO venues(name, status, staff_chat_id) VALUES ('Demo Hookah', 'active_published', NULL) RETURNING id;
  -- возьмите id из предыдущего шага
  INSERT INTO venue_tables(venue_id, table_number) VALUES (<venue_id>, 5) RETURNING id;
  INSERT INTO table_tokens(token, table_id) VALUES ('demo-token', <table_id>);
  ```
