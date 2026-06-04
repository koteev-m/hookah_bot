# Local Telegram Mini App Smoke

This runbook is for checking the Telegram Mini App from a phone/Telegram WebView while running the backend and Vite locally.

Use this workflow instead of Cloudflare Quick Tunnel/ngrok. The permanent local-smoke public URL is:

```text
https://dev.hookahtootah.club
```

## Architecture

```text
Telegram WebView
  -> https://dev.hookahtootah.club/miniapp/
  -> Aeza VPS Caddy
  -> 127.0.0.1:18080 on VPS
  -> SSH reverse tunnel
  -> Mac localhost:8080
  -> local Ktor backend
  -> /miniapp/ proxy to local Vite localhost:5173
```

Staging stays separate:

```text
https://staging.hookahtootah.club
```

## Safety Rules

- Do not run staging backend and local backend with the same `TELEGRAM_BOT_TOKEN` at the same time.
- Stop only the staging `backend` container before local long polling. Keep staging Postgres running.
- Do not paste bot tokens, DB passwords, JWT secrets, peppers, or AI keys into terminal output.
- Keep local-only services on localhost:
  - backend: `http://localhost:8080`
  - Vite: `http://localhost:5173/miniapp/`
  - Postgres: `localhost:5433`
- `dev.hookahtootah.club` is only for local Telegram Mini App smoke, not staging deploy.

## DNS Requirement

`dev.hookahtootah.club` must point to the Aeza VPS:

```text
dev.hookahtootah.club A 178.20.209.5
```

Check from your machine:

```bash
dig +short dev.hookahtootah.club
```

Expected:

```text
178.20.209.5
```

## Caddy on VPS

Add this server block to `/etc/caddy/Caddyfile` on the VPS:

```caddy
dev.hookahtootah.club {
    reverse_proxy 127.0.0.1:18080
}
```

Validate and reload:

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

Do not change the existing `staging.hookahtootah.club` block.

## Current Config Model

| Key | Used by | Local Telegram Mini App value |
| --- | --- | --- |
| `TELEGRAM_WEBAPP_PUBLIC_URL` | Telegram bot buttons/WebApp URLs | `https://dev.hookahtootah.club/miniapp/` |
| `MINIAPP_ENTRY_ENABLED` | enables Telegram Mini App entry/buttons | `true` |
| `MINIAPP_DEV_SERVER_URL` | backend `/miniapp/` proxy | `http://localhost:5173` |
| `MINIAPP_STATIC_DIR` | backend static Mini App mode | empty |
| `VITE_BACKEND_PUBLIC_URL` | Vite bundle API base URL | `https://dev.hookahtootah.club` |
| `CORS_ALLOWED_HOSTS` | backend CORS allowlist | `https://dev.hookahtootah.club` |
| `TELEGRAM_BOT_MODE` | backend Telegram bot mode | `long_polling` |
| `DB_JDBC_URL` | backend DB connection | `jdbc:postgresql://localhost:5433/hookah` |
| `DB_USER` / `DB_PASSWORD` | backend DB auth | local DB credentials from your `.env` |

`VITE_API_BASE_URL` and `BACKEND_PUBLIC_URL` are not used by the current Mini App code.

## Workflow

### 1. Stop staging backend

```bash
ssh hookah-staging 'cd /opt/hookah-bot && docker compose stop backend'
```

Confirm only `backend` is stopped:

```bash
ssh hookah-staging 'cd /opt/hookah-bot && docker compose ps'
```

### 2. Start local Postgres

If the local test DB container already exists:

```bash
docker start hookah-bot-test-db
```

If it does not exist, create it with local dev credentials:

```bash
docker run --name hookah-bot-test-db \
  -e POSTGRES_DB=hookah \
  -e POSTGRES_USER=hookah \
  -e POSTGRES_PASSWORD=hookah \
  -p 127.0.0.1:5433:5432 \
  -d postgres:17
```

Check it:

```bash
docker ps --filter name=hookah-bot-test-db
```

### 3. Start SSH reverse tunnel

In a separate terminal:

```bash
scripts/start-dev-reverse-tunnel.sh
```

Manual equivalent:

```bash
ssh -N -T \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -R 127.0.0.1:18080:localhost:8080 \
  hookah-staging
```

Keep this process running while local smoke is active.

### 4. Start local Vite

In a separate terminal:

```bash
cd miniapp
VITE_BACKEND_PUBLIC_URL=https://dev.hookahtootah.club npm run dev
```

Vite stays local on `http://localhost:5173`; the browser inside Telegram receives `https://dev.hookahtootah.club` as the backend API origin.

### 5. Start local backend

Preferred helper:

```bash
PUBLIC_TUNNEL_URL=https://dev.hookahtootah.club scripts/run-local-telegram-miniapp.sh
```

The helper sets only runtime overrides:

- `TELEGRAM_WEBAPP_PUBLIC_URL=https://dev.hookahtootah.club/miniapp/`
- `MINIAPP_ENTRY_ENABLED=true`
- `MINIAPP_DEV_SERVER_URL=http://localhost:5173`
- `MINIAPP_STATIC_DIR=`
- `VITE_BACKEND_PUBLIC_URL=https://dev.hookahtootah.club`
- `CORS_ALLOWED_HOSTS=https://dev.hookahtootah.club`
- `TELEGRAM_BOT_MODE=long_polling`
- `DB_JDBC_URL=jdbc:postgresql://localhost:5433/hookah`

It does not edit `.env` and does not echo secrets.

Manual equivalent:

```bash
PUBLIC_TUNNEL_URL=https://dev.hookahtootah.club

APP_ENV=dev \
TELEGRAM_BOT_MODE=long_polling \
MINIAPP_ENTRY_ENABLED=true \
TELEGRAM_WEBAPP_PUBLIC_URL="${PUBLIC_TUNNEL_URL}/miniapp/" \
MINIAPP_DEV_SERVER_URL=http://localhost:5173 \
MINIAPP_STATIC_DIR= \
VITE_BACKEND_PUBLIC_URL="${PUBLIC_TUNNEL_URL}" \
CORS_ALLOWED_HOSTS="${PUBLIC_TUNNEL_URL}" \
DB_JDBC_URL=jdbc:postgresql://localhost:5433/hookah \
DB_USER=hookah \
DB_PASSWORD=hookah \
./gradlew --no-daemon :backend:app:run --console=plain
```

If your local `.env` has different DB credentials, export `DB_USER` and `DB_PASSWORD` before running the helper.

### 6. Verify

Local checks:

```bash
curl -f http://localhost:8080/health
curl -f http://localhost:8080/db/health
```

Public dev checks:

```bash
curl -f https://dev.hookahtootah.club/health
curl -f https://dev.hookahtootah.club/db/health
curl -I https://dev.hookahtootah.club/miniapp/
```

Then open the bot in Telegram and press a Mini App button. The button should point to:

```text
https://dev.hookahtootah.club/miniapp/
```

The local backend logs should show long polling activity.

## Restore Staging

Stop local backend first, stop the SSH reverse tunnel, then restore staging backend:

```bash
ssh hookah-staging 'cd /opt/hookah-bot && docker compose start backend'
```

Check staging from VPS:

```bash
ssh hookah-staging 'cd /opt/hookah-bot && docker compose ps'
ssh hookah-staging 'curl -f http://127.0.0.1:8080/health && echo'
ssh hookah-staging 'curl -f http://127.0.0.1:8080/db/health && echo'
```

Public checks:

```bash
curl -f https://staging.hookahtootah.club/health
curl -f https://staging.hookahtootah.club/db/health
curl -I https://staging.hookahtootah.club/miniapp/
```

## Troubleshooting

- Telegram button opens staging: local backend is not the active bot poller, or `TELEGRAM_WEBAPP_PUBLIC_URL` was not overridden to `https://dev.hookahtootah.club/miniapp/`.
- `https://dev.hookahtootah.club/health` fails: Caddy, DNS, or SSH reverse tunnel is not ready.
- Mini App opens but API calls hit staging: Vite was started without `VITE_BACKEND_PUBLIC_URL=https://dev.hookahtootah.club`.
- Mini App opens but API calls fail CORS: backend was started without `CORS_ALLOWED_HOSTS=https://dev.hookahtootah.club`.
- `/miniapp/` returns diagnostic text: backend was started without `MINIAPP_DEV_SERVER_URL=http://localhost:5173`, or Vite is not running.
- Bot does not respond: staging backend may still be polling with the same token, or local backend lacks the bot token in environment.
