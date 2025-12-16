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

## Дальнейшие шаги
- На следующих шагах добавим telegram bot модуль и режимы long polling/webhook; пока только backend skeleton.
