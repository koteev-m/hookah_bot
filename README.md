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
