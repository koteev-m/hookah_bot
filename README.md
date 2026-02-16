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
- `GET /metrics` → Prometheus-метрики приложения

### Эксплуатационные метрики
`/metrics` экспортирует ключевые метрики для webhook/очередей:
- `inbound_queue_depth` — глубина очереди входящих Telegram webhook updates.
- `outbound_queue_depth` — глубина очереди Telegram outbox.
- `outbound_send_success_total` — количество успешных отправок outbox.
- `outbound_send_failed_total` — количество неуспешных попыток отправки outbox.
- `outbound_429_total` — количество ответов Telegram API с кодом `429` (rate limit).
- `webhook_processing_lag_seconds` — lag (гистограмма) между получением webhook и началом обработки worker-ом.

Быстрая проверка локально:
```bash
curl -s http://localhost:8080/metrics | rg "inbound_queue_depth|outbound_queue_depth|outbound_send|outbound_429|webhook_processing_lag_seconds"
```


## Runbook и migration policy
- Минимальный эксплуатационный runbook: `docs/OPERATIONS.md`.
- Политика миграций Flyway (CI validate + production rollout): `docs/MIGRATION_POLICY.md`.

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

#### Обработка недоступных заведений в guest-эндпойнтах
Гостевые эндпойнты всегда скрывают недоступные/неопубликованные заведения: для browse-запросов ответ `NOT_FOUND` (404) вместо раскрытия причины. Исключение — бизнес-ограничения, где нужен явный 423.

Точное поведение по типам запросов:
- `GET /api/guest/table/resolve` возвращает 200 только для найденного `tableToken`, привязанного к опубликованному заведению. Если `tableToken` не найден **или** найден, но заведение не опубликовано, — `NOT_FOUND` (404). При 200 доступность отражается полями `available` и `unavailableReason` (например, `SUBSCRIPTION_BLOCKED`).
- `POST /api/guest/order/add-batch`, `GET /api/guest/order/active`, `POST /api/guest/staff-call` возвращают 423 только для `SUBSCRIPTION_BLOCKED` (UX требование); случаи `venue not available` и `token not found` остаются `NOT_FOUND` (404).
- `GET /api/guest/venue/{id}` и `GET /api/guest/venue/{id}/menu`: для недоступных/неопубликованных заведений всегда `NOT_FOUND` (404).
- `GET /api/guest/catalog` возвращает только опубликованные заведения; недоступные статусы в список не попадают (нет 423/404 на этом эндпойнте).

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

#### tableToken format
Валидация `tableToken` (query или body) одинаковая для всех гостевых маршрутов:
- `trim()` перед проверками
- максимальная длина — 128 символов
- допустимы только ASCII символы `0x21..0x7E` (без пробелов)
- при нарушении правила — 400 `INVALID_INPUT`

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
- `SUBSCRIPTION_BLOCKED` (423) — подписка past_due/suspended/suspended_by_platform.
- `DATABASE_UNAVAILABLE` (503) — база данных недоступна.

### Security note
**Не логируйте и не шарьте `initData` и session token**, не кладите их в багрепорты или тикеты. Все примеры в README содержат только placeholder-значения.

## Billing (MVP)
Минимальная схема биллинга:
- Таблицы: `billing_invoices`, `billing_payments`, `billing_notifications`.
- Статусы инвойсов: `DRAFT`, `OPEN`, `PAID`, `PAST_DUE`, `VOID`. Статусы платежей: `SUCCEEDED`, `FAILED`, `REFUNDED`.
- `BillingProvider` определяет создание инвойса и обработку вебхуков; для dev доступен `FakeBillingProvider`, для карт можно включить `GenericHmacBillingProvider` через `BILLING_PROVIDER=generic_hmac`.
- `BillingService` хранит инвойс/платёж и применяет события оплаты через идемпотентные provider event id.
- `SubscriptionBillingEngine` + `SubscriptionBillingJob` периодически создают инвойсы, рассылают напоминания, переводят просроченные счета в `PAST_DUE` и замораживают подписку. При оплате через `BillingHooks` подписка возвращается в `ACTIVE`.

## Telegram payments: Stars vs external billing
Telegram поддерживает оплату Stars и внешние платежи, это разные потоки:
- Stars API: https://core.telegram.org/api/stars
- Payments for bots: https://core.telegram.org/bots/payments

## Database (Postgres + Flyway)
- Скопировать переменные окружения: `cp .env.example .env`
- Для локальной разработки PostgreSQL: `docker compose up -d postgres`
- Загрузить переменные окружения в shell (bash): `set -a; source .env; set +a`
- Запустить backend: `./gradlew :backend:app:run`
- Проверить: `curl -f http://localhost:8080/db/health`
- В Docker backend подключается к `postgres:5432`, снаружи Docker — к `localhost:5432`

## Deployment (MVP)
Ниже — минимальный набор шагов и параметров для безопасного деплоя. Все значения — placeholder, реальные секреты не храните в репозитории.

### Обязательные переменные окружения
База данных:
- `DB_JDBC_URL=jdbc:postgresql://<db-host>:5432/<db-name>`
- `DB_USER=<db-user>`
- `DB_PASSWORD=<db-password>`

JWT/сессии Mini App:
- `API_SESSION_JWT_SECRET=<session-jwt-secret>`
- `API_SESSION_TTL_SECONDS=3600`

Billing webhook:
- `BILLING_WEBHOOK_SECRET=<billing-webhook-secret>`
- `BILLING_WEBHOOK_IP_ALLOWLIST=<cidr-or-ip-list>` (опционально, через запятую)
- `BILLING_WEBHOOK_IP_ALLOWLIST_USE_X_FORWARDED_FOR=true|false` (включать только за доверенным прокси)

Card checkout (Generic HMAC provider):
- `BILLING_PROVIDER=generic_hmac`
- `BILLING_GENERIC_CHECKOUT_BASE_URL=https://payments.example.com/checkout`
- `BILLING_GENERIC_MERCHANT_ID=<merchant-id>` (опционально)
- `BILLING_GENERIC_CHECKOUT_RETURN_URL=https://miniapp.example.com/billing/return` (опционально)
- `BILLING_GENERIC_SIGNING_SECRET=<provider-webhook-signing-secret>`
- `BILLING_GENERIC_SIGNATURE_HEADER=X-Billing-Signature` (опционально, по умолчанию это значение)

Webhook `POST /api/billing/webhook/generic_hmac` должен содержать подпись HMAC SHA-256 в заголовке `BILLING_GENERIC_SIGNATURE_HEADER` и JSON с полями `event_id`, `payment_status`, `invoice_id`, `amount_minor`, `currency`.

Telegram webhook (если используете webhook-режим бота):
- `TELEGRAM_BOT_ENABLED=true`
- `TELEGRAM_BOT_TOKEN=<telegram-bot-token>`
- `TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER=<staff-chat-link-secret-pepper>`
- `TELEGRAM_WEBHOOK_SECRET_TOKEN=<telegram-webhook-secret>`
- `TELEGRAM_WEBHOOK_PATH=/telegram/webhook`

### HTTPS termination через reverse proxy
Backend слушает HTTP, TLS завершается на edge. Пример для Nginx:
```nginx
server {
  listen 443 ssl http2;
  server_name example.com;

  ssl_certificate     /etc/ssl/certs/fullchain.pem;
  ssl_certificate_key /etc/ssl/private/privkey.pem;

  location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
  }
}
```

Пример для Caddy:
```caddy
example.com {
  reverse_proxy 127.0.0.1:8080 {
    header_up Host {host}
    header_up X-Real-IP {remote_host}
    header_up X-Forwarded-For {remote_host}
    header_up X-Forwarded-Proto {scheme}
  }
}
```

### X-Forwarded-For и IP allowlist для billing webhook
Если вы используете `BILLING_WEBHOOK_IP_ALLOWLIST`, то сервис может проверять IP запроса. За proxy/ingress реальный IP приходит в `X-Forwarded-For`, поэтому включайте `BILLING_WEBHOOK_IP_ALLOWLIST_USE_X_FORWARDED_FOR=true` **только** если edge гарантированно очищает/перезаписывает этот заголовок. Иначе злоумышленник сможет подставить IP в `X-Forwarded-For`. Не используйте `$proxy_add_x_forwarded_for` для allowlist, если входной `X-Forwarded-For` не очищается: он сохраняет первый IP из клиентского заголовка.

### Минимальный security checklist
- **Не логировать** `initData` и session token (см. секцию Auth: `POST /api/auth/telegram`).
- **Не коммитить** секреты и реальные токены (`.env`, `BILLING_WEBHOOK_SECRET`, `TELEGRAM_BOT_TOKEN`, `API_SESSION_JWT_SECRET` и т.д.).
- **Ограничить доступ** к `POST /api/billing/webhook` на уровне сети (firewall, security groups, allowlist).
- **Включить rate limits** на edge (Nginx/Caddy/ingress), если возможно.

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
  INSERT INTO venues(name, status, staff_chat_id) VALUES ('Demo Hookah', 'PUBLISHED', NULL) RETURNING id;
  -- возьмите id из предыдущего шага
  INSERT INTO venue_tables(venue_id, table_number) VALUES (<venue_id>, 5) RETURNING id;
  INSERT INTO table_tokens(token, table_id) VALUES ('demo-token', <table_id>);
  ```
