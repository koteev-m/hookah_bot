# Staging Deployment

Canonical release/deploy policy is `docs/DEPLOYMENT_RUNBOOK.md`. This file remains the one-VPS staging implementation detail runbook.

This runbook describes the minimal staging setup for the Telegram bot + Mini App on one VPS with Docker Compose, PostgreSQL on the same host, and the Mini App production build served by the backend at `/miniapp/`.

## 1. Target Architecture

- One VPS.
- Docker Compose.
- `postgres` service for PostgreSQL data.
- `backend` service built from `backend/Dockerfile`.
- Mini App is built during Docker build and copied into the backend image.
- Backend serves API, bot runtime, health endpoints, and static Mini App on one domain.
- Current staging public URL: `https://staging.hookahtootah.club`.
- Telegram bot mode for first staging: `long_polling`.

## 2. VPS Requirements

- Ubuntu 22.04/24.04 LTS or similar.
- 2 vCPU, 2-4 GB RAM minimum for staging.
- 20-40 GB SSD minimum; more if keeping many database backups.
- Docker Engine and Docker Compose plugin.
- Reverse proxy with HTTPS certificates, for example Caddy or Nginx.
- Firewall allowing `80/tcp`, `443/tcp`, and SSH only.

## 3. DNS

Current staging domain:

- `staging.hookahtootah.club A <vps-ip>`

Use one domain with paths:

- `https://staging.hookahtootah.club/health`
- `https://staging.hookahtootah.club/db/health`
- `https://staging.hookahtootah.club/api/*`
- `https://staging.hookahtootah.club/miniapp/`
- `https://staging.hookahtootah.club/telegram/webhook` later, only after switching to webhook mode.

## 4. Environment

Use the template:

```bash
cp docs/env/staging.env.example .env
chmod 600 .env
```

Replace placeholder values on the server. Do not commit the resulting `.env`.

Required staging values:

- `APP_ENV=staging`
- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `DB_JDBC_URL=jdbc:postgresql://postgres:5432/<POSTGRES_DB>`
- `DB_USER` equal to `POSTGRES_USER`
- `DB_PASSWORD` equal to `POSTGRES_PASSWORD`
- `API_SESSION_JWT_SECRET`
- `TELEGRAM_BOT_ENABLED=true`
- `TELEGRAM_BOT_MODE=long_polling`
- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_WEBAPP_PUBLIC_URL=https://staging.hookahtootah.club/miniapp/`
- `TELEGRAM_BOT_USERNAME`
- `PLATFORM_OWNER_TELEGRAM_ID`
- `PLATFORM_OWNER_USER_ID=` optional legacy compatibility alias; leave empty when `users.telegram_user_id` is the platform owner identity.
- `TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER`
- `MINIAPP_ENTRY_ENABLED=true`
- `MINIAPP_DEV_SERVER_URL=`
- `MINIAPP_STATIC_DIR=/app/miniapp`
- `VITE_BACKEND_PUBLIC_URL=https://staging.hookahtootah.club`
- `CORS_ALLOWED_HOSTS=https://staging.hookahtootah.club`

Environment ownership matrix:

| Group | Used by | Local dev on Mac | Staging VPS | Production later |
| --- | --- | --- | --- | --- |
| Backend runtime env | Ktor application | `APP_ENV=dev`, `DB_JDBC_URL=jdbc:postgresql://localhost:5433/hookah`, `TELEGRAM_WEBAPP_PUBLIC_URL=https://staging.hookahtootah.club/miniapp/`, `MINIAPP_DEV_SERVER_URL=http://localhost:5173`, `MINIAPP_STATIC_DIR=` | `APP_ENV=staging`, `DB_JDBC_URL=jdbc:postgresql://postgres:5432/hookah`, `TELEGRAM_WEBAPP_PUBLIC_URL=https://staging.hookahtootah.club/miniapp/`, `MINIAPP_DEV_SERVER_URL=`, `MINIAPP_STATIC_DIR=/app/miniapp` | `APP_ENV=production`, production DB URL, production Web App URL, `MINIAPP_DEV_SERVER_URL=`, static Mini App directory |
| Docker Compose / Postgres env | `docker-compose.yml` and `postgres` container | `POSTGRES_PORT=5433`, local test DB credentials from local `.env` | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, optional host port, service name `postgres:5432` inside Compose | Separate production secrets, backup policy, and stricter host exposure |
| Vite build env | Mini App bundle build | `VITE_BACKEND_PUBLIC_URL=https://staging.hookahtootah.club` when opened through Telegram/staging tunnel; `http://localhost:8080` only for plain local browser smoke | `VITE_BACKEND_PUBLIC_URL=https://staging.hookahtootah.club` baked into Docker image build | Production public backend origin |

Mode differences:

| Mode | Backend | Mini App | Database | Mini App serving |
| --- | --- | --- | --- | --- |
| Local dev | Gradle app on `localhost:8080` | Vite dev server on `localhost:5173` | PostgreSQL on `localhost:5433` | Backend proxies `/miniapp/` to `MINIAPP_DEV_SERVER_URL=http://localhost:5173`; `MINIAPP_STATIC_DIR` is empty |
| Staging VPS | Backend container | Production build inside backend image | Compose service `postgres:5432` | Backend serves static files from `MINIAPP_STATIC_DIR=/app/miniapp`; `MINIAPP_DEV_SERVER_URL` is empty |
| Production later | Backend container or equivalent service | Production build | Separate production DB/secrets/backups | Static Mini App, production domain, likely webhook after staging is stable |

Before deploy, verify the non-secret part of the server env:

```bash
cd /opt/hookah-bot
grep -E '^(POSTGRES_DB|POSTGRES_USER|DB_JDBC_URL|DB_USER|TELEGRAM_WEBAPP_PUBLIC_URL|MINIAPP_STATIC_DIR|MINIAPP_DEV_SERVER_URL|CORS_ALLOWED_HOSTS)=' .env
```

Check secret presence only with masking:

```bash
grep -E '^(POSTGRES_PASSWORD|DB_PASSWORD|TELEGRAM_BOT_TOKEN|API_SESSION_JWT_SECRET|TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER|AI_API_KEY)=' .env | sed 's/=.*/=***SET***/'
```

Do not print raw `POSTGRES_PASSWORD`, `DB_PASSWORD`, Telegram tokens, JWT secrets, peppers, or API keys.

For temporary tunnel-based local dev, keep using local env files and set public URL variables to the exact current public origin. This path is legacy and should not be hardcoded in Kotlin, TypeScript, templates, or committed docs.

## 5. Build And Deploy

Standard local one-command deploy:

```bash
./scripts/deploy-staging.sh <ssh-alias>
```

Current staging alias example:

```bash
./scripts/deploy-staging.sh hookah-staging
```

The script:

1. validates `docker compose config`;
2. checks that required server `.env` keys are present without printing secrets;
3. builds the backend Docker image locally for `linux/amd64`, including the Mini App production build;
4. uploads `docker-compose.yml`, `scripts/seed-staging.sh`, safe docs/templates, and the Docker image to the VPS;
5. runs `docker compose up -d --no-build postgres backend` on the VPS;
6. waits and retries local VPS health endpoints and the public staging URL.

The health wait handles short backend startup windows and transient reverse-proxy connection resets. If the script still fails after all attempts, do not redeploy blindly; inspect container status and backend logs first.

On Mac Apple Silicon the script uses `docker buildx build --platform linux/amd64 --load` so the uploaded image matches a typical x86_64/amd64 VPS.

The backend Dockerfile uses BuildKit cache mounts for Gradle wrapper and dependency caches. If Docker build fails while downloading the Gradle distribution or Maven dependencies with a transient `SocketTimeoutException`, rerun the same deploy command after confirming it is a network timeout, not a Kotlin compile/test failure. The wrapper network timeout is intentionally higher than the default, and a successful retry should reuse the warmed Docker/Gradle cache.

### Opt-In Persistent SSH Deploy

Normal deployment remains supported and unchanged. Use the ControlMaster helper only when repeated new SSH connection establishment is unreliable during deploy.

Resilient command:

```bash
./scripts/deploy-staging-controlmaster.sh <ssh-alias>
```

Current staging alias example with optional overrides:

```bash
STAGING_PATH=/opt/hookah-bot \
STAGING_DOMAIN=staging.example.com \
DOCKER_PLATFORM=linux/amd64 \
BACKEND_IMAGE=example-backend:staging \
./scripts/deploy-staging-controlmaster.sh staging-alias
```

The helper:

- opens one persistent authenticated SSH ControlMaster connection with the operator's existing SSH config and host alias, using bounded initial connection retries;
- creates a temporary SSH wrapper and makes child `ssh` and `rsync` calls reuse the helper-owned control socket;
- calls the existing `./scripts/deploy-staging.sh` for compose validation, env validation, Docker build/upload, service restart and health checks;
- closes the master with `ssh -O exit` and removes its temporary socket/wrapper directory on success, failure, `SIGINT` or `SIGTERM`.

The helper does not:

- change SSH server configuration, firewall, fail2ban, ports, users, keys or sudo policy;
- store credentials or write to `~/.ssh`;
- disable host-key verification;
- prove the root cause of intermittent new-connection drops.

During the M8b-Free staging deploy, direct deployment repeatedly failed while opening fresh SSH/SCP/rsync connections. Both `scp` and `rsync` saw intermittent key-exchange or connection-closed failures. At the same time, server resources, disk, Docker, backend and PostgreSQL health were normal, and server logs showed substantial unauthenticated SSH scanning.

Do not treat this as a proven root cause analysis. The observed incident only proves that repeated new SSH connection establishment was unreliable, while an already-authenticated persistent SSH connection worked. M9a validated the persistent connection as an operational workaround and release-reliability improvement; it did not prove that Docker, server resources, SSH scanning, `MaxStartups` or any other single factor caused the fresh-connection drops.

M9a verified staging evidence:

- initial master connection attempt hit an SSH banner timeout;
- the helper's bounded retry opened the master successfully;
- rsync upload succeeded through the persistent connection;
- Docker image build completed;
- image upload to the VPS completed;
- backend container was recreated successfully;
- PostgreSQL remained healthy;
- local backend `/health` returned ok;
- local `/db/health` returned ok;
- local Mini App static check passed;
- public `/health` returned ok;
- public `/db/health` returned ok;
- public `/miniapp/` returned the application HTML;
- a separate retry-based public check also passed for all three public endpoints.

Expected success indicators for future deploys:

- the helper reports that the ControlMaster is ready;
- upload steps complete without opening new authenticated SSH sessions;
- image build/upload completes;
- backend restarts/recreates successfully;
- the existing deploy script prints `==> Staging deploy finished`;
- local `/health`, `/db/health`, and `/miniapp/` checks pass;
- public `/health`, `/db/health`, and `/miniapp/` checks pass.

Troubleshooting:

- Initial master connection failure: verify Docker Desktop is running, then check the SSH alias manually with normal SSH. The helper uses `BatchMode=yes`, bounded connect timeout and bounded retries; it does not fall back to the normal deploy automatically.
- Stale socket cleanup: the helper uses a fresh `mktemp` directory and removes only the exact helper-owned directories it created. If you manually inspect a leftover socket, remove only the exact confirmed temporary directory, never a wildcard under `~/.ssh`.
- Checking a known socket manually: `ssh -O check -S /tmp/hcm.xxxxxx/cm.sock staging-alias`.
- Closing a known socket manually: `ssh -O exit -S /tmp/hcm.xxxxxx/cm.sock staging-alias`.
- Docker Desktop not running: the helper fails before opening the master connection. Start Docker and rerun the same helper command.
- Health endpoint retry behavior: endpoint waits and retries still come from `deploy-staging.sh`. If retries are exhausted, inspect container status and backend logs before redeploying.

Manual regression smoke for future ControlMaster deploys:

1. Verify Docker Desktop is running.
2. Run the ControlMaster helper with the staging SSH alias.
3. Observe initial master connection confirmation.
4. Verify upload succeeds through the master.
5. Verify the existing deploy script completes.
6. Verify `/health`, `/db/health`, and `/miniapp/`.
7. Verify safety flags remain unchanged where applicable.
8. Confirm the helper closes and removes the control socket.
9. Run `ssh -O check` after cleanup for the known socket and confirm no helper-owned master remains.
10. Record fresh-connection SSH failures separately; do not treat this helper as proof of root cause.

Permanent server-network/SSH hardening remains a separate ops follow-up:

- determine the exact SSH drop cause;
- consider firewall/VPN/private management networking;
- review SSH daemon hardening;
- monitor rejected pre-auth connections;
- design deployment rollback/blue-green work separately from the ControlMaster helper.

Optional overrides:

```bash
STAGING_PATH=/opt/hookah-bot \
STAGING_DOMAIN=staging.hookahtootah.club \
BACKEND_IMAGE=hookah_bot_ant-backend:staging \
DOCKER_PLATFORM=linux/amd64 \
HEALTHCHECK_ATTEMPTS=20 \
HEALTHCHECK_SLEEP_SECONDS=3 \
./scripts/deploy-staging.sh hookah-staging
```

Before first deploy, create `.env` on the VPS. This file is mandatory before the deploy script can start Compose:

```bash
ssh hookah-staging
sudo mkdir -p /opt/hookah-bot
sudo chown "$USER":"$USER" /opt/hookah-bot
cd /opt/hookah-bot
```

After the first script run uploads the template:

```bash
cp docs/env/staging.env.example .env
chmod 600 .env
```

Fill real secrets in `.env`, then rerun the deploy script.

On the VPS:

```bash
git clone <repo-url> hookah_bot_ANT
cd hookah_bot_ANT
cp docs/env/staging.env.example .env
chmod 600 .env
```

Edit `.env`, then build and start:

```bash
docker compose build
docker compose up -d
docker compose ps
```

Follow logs:

```bash
docker compose logs -f backend
```

Restart after env changes:

```bash
docker compose up -d --build backend
```

## 6. Reverse Proxy

Backend listens on `127.0.0.1:8080` from the host. Terminate HTTPS at the reverse proxy.

Caddy example:

```caddy
staging.hookahtootah.club {
  reverse_proxy 127.0.0.1:8080 {
    header_up Host {host}
    header_up X-Real-IP {remote_host}
    header_up X-Forwarded-For {remote_host}
    header_up X-Forwarded-Proto {scheme}
  }
}
```

Nginx example:

```nginx
server {
  listen 443 ssl http2;
  server_name staging.hookahtootah.club;

  ssl_certificate     /etc/letsencrypt/live/staging.hookahtootah.club/fullchain.pem;
  ssl_certificate_key /etc/letsencrypt/live/staging.hookahtootah.club/privkey.pem;

  location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
  }
}
```

## 7. Health Checks

Post-deploy public sanity checks:

```bash
curl -f https://staging.hookahtootah.club/health
curl -f https://staging.hookahtootah.club/db/health
curl -I https://staging.hookahtootah.club/miniapp/
```

Server inspection commands:

```bash
ssh hookah-staging
cd /opt/hookah-bot
docker compose ps
docker compose logs --tail=120 backend
```

Local on the VPS:

```bash
curl -f http://127.0.0.1:8080/health
curl -f http://127.0.0.1:8080/db/health
curl -I http://127.0.0.1:8080/miniapp/
```

Public:

```bash
curl -f https://staging.hookahtootah.club/health
curl -f https://staging.hookahtootah.club/db/health
curl -I https://staging.hookahtootah.club/miniapp/
```

Expected:

- `/health` returns `{"status":"ok"}`.
- `/db/health` returns `{"status":"ok"}` after PostgreSQL is ready and migrations have passed.
- `/miniapp/` returns the Mini App HTML from the backend container.

If public health checks fail but local VPS checks pass, inspect the reverse proxy and TLS config before restarting backend. If both local and public checks fail, inspect backend logs and PostgreSQL health before redeploying.

## 8. Seed Staging Data

Fresh staging databases are empty. Run the seed only when you explicitly need smoke data; it is not a Flyway migration and does not run automatically.

The seed creates or updates one idempotent smoke venue:

- published venue visible in Guest catalog;
- owner membership, plus optional manager/staff memberships;
- active subscription;
- venue settings and booking hold settings;
- booking hours for every weekday;
- menu categories and available menu items;
- two active tables for QR/table order smoke.

It does not delete existing data and does not insert real secrets.

Required before running:

- backend and postgres containers are up;
- `.env` exists on the VPS;
- `PLATFORM_OWNER_TELEGRAM_ID` is set, or pass `STAGING_SEED_OWNER_TELEGRAM_ID`.

Run on the VPS:

```bash
cd /opt/hookah-bot
./scripts/seed-staging.sh
```

Optional role mapping for smoke with separate Telegram users:

```bash
STAGING_SEED_OWNER_TELEGRAM_ID=<owner-telegram-id> \
STAGING_SEED_MANAGER_TELEGRAM_ID=<manager-telegram-id> \
STAGING_SEED_STAFF_TELEGRAM_ID=<staff-telegram-id> \
./scripts/seed-staging.sh
```

Optional venue labels:

```bash
STAGING_SEED_VENUE_NAME="MIX Staging Smoke" \
STAGING_SEED_VENUE_CITY="Москва" \
STAGING_SEED_VENUE_ADDRESS="Staging smoke address" \
./scripts/seed-staging.sh
```

Verify after seed:

```bash
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "
SELECT id, name, status FROM venues ORDER BY id;
SELECT v.name, vt.table_number, tt.token
FROM venues v
JOIN venue_tables vt ON vt.venue_id = v.id
JOIN table_tokens tt ON tt.table_id = vt.id AND tt.is_active
WHERE v.name LIKE '\''%Staging Smoke%'\''
ORDER BY vt.table_number;
"'
```

Then open:

- Guest Mini App catalog: `https://staging.hookahtootah.club/miniapp/?mode=guest`
- Venue Mini App through the Telegram bot WebApp button for the seeded owner/manager/staff.

## 9. Telegram Mini App Setup

In BotFather or bot settings:

- Set Web App URL to `https://staging.hookahtootah.club/miniapp/`.
- Make sure backend env has `TELEGRAM_WEBAPP_PUBLIC_URL=https://staging.hookahtootah.club/miniapp/`.
- Keep Mini App entry enabled with `MINIAPP_ENTRY_ENABLED=true`.

Telegram `initData` must be produced by Telegram runtime through `web_app` buttons. Do not generate or pass `initData` manually.

## 10. Polling First, Webhook Later

Use `TELEGRAM_BOT_MODE=long_polling` for the first staging because:

- It avoids webhook registration and public webhook troubleshooting during initial smoke.
- It works behind a reverse proxy without Telegram webhook setup.
- It keeps deploy/rollback simpler while stabilizing Mini App + bot parity.

Switch to webhook later when staging is stable:

1. Set `TELEGRAM_BOT_MODE=webhook`.
2. Set `TELEGRAM_WEBHOOK_SECRET_TOKEN`.
3. Keep `TELEGRAM_WEBHOOK_PATH=/telegram/webhook`.
4. Restart backend.
5. Register webhook with Telegram:

```bash
curl -X POST "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  -d "url=https://staging.hookahtootah.club/telegram/webhook" \
  -d "secret_token=${TELEGRAM_WEBHOOK_SECRET_TOKEN}"
```

Then check:

```bash
curl -f https://staging.hookahtootah.club/telegram/queue/health
```

## 11. Backups

Create a timestamped PostgreSQL backup:

```bash
mkdir -p backups
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
  > "backups/hookah_staging_$(date +%Y%m%d_%H%M%S).dump"
```

Restore into a fresh database only after stopping backend writes:

```bash
docker compose stop backend
docker compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' \
  < backups/<backup-file>.dump
docker compose up -d backend
```

## 12. Rollback And Restart

### Restart

Use this for a controlled backend restart without changing image or database state:

```bash
ssh hookah-staging
cd /opt/hookah-bot
docker compose restart backend
docker compose ps
docker compose logs --tail=120 backend
```

Then verify:

```bash
curl -f https://staging.hookahtootah.club/health
curl -f https://staging.hookahtootah.club/db/health
curl -I https://staging.hookahtootah.club/miniapp/
```

### Rollback

The deploy script uploads a Docker image selected by `BACKEND_IMAGE`. For rollback-friendly releases, deploy with an immutable image tag, for example:

```bash
BACKEND_IMAGE=hookah_bot_ant-backend:85f1b1e ./scripts/deploy-staging.sh hookah-staging
```

To roll back to an image that is already loaded on the VPS:

```bash
ssh hookah-staging
cd /opt/hookah-bot
docker images 'hookah_bot_ant-backend'
BACKEND_IMAGE=hookah_bot_ant-backend:<known-good-tag> docker compose up -d --no-build backend
docker compose ps
docker compose logs --tail=120 backend
```

Then run the public sanity checks:

```bash
curl -f https://staging.hookahtootah.club/health
curl -f https://staging.hookahtootah.club/db/health
curl -I https://staging.hookahtootah.club/miniapp/
```

If the previous image is not available on the VPS, rebuild and redeploy that known-good commit from the developer machine using the same `BACKEND_IMAGE=<known-good-tag>` value.

Database caution: if the failed release applied migrations, code rollback may not be enough. Restore a PostgreSQL backup only after stopping backend writes, and only when the operator accepts data loss/restore implications.

## 13. Local Development Remains Separate

Local backend:

```bash
set -a
source .env
set +a
./gradlew --no-daemon :backend:app:run --console=plain
```

Local Mini App dev server:

```bash
cd miniapp
npm run dev
```

For local backend proxy to Vite:

```bash
export MINIAPP_DEV_SERVER_URL=http://localhost:5173
export MINIAPP_STATIC_DIR=
```

For staging, keep:

```bash
MINIAPP_DEV_SERVER_URL=
MINIAPP_STATIC_DIR=/app/miniapp
```

## 14. Pre-Purchase / Pre-Launch Risks

- Final domain and DNS TTL must be chosen before setting Telegram Web App URL.
- Real Telegram bot token, session JWT secret, staff invite pepper, and database password must be generated outside the repository.
- PostgreSQL backup storage and retention are still operational decisions.
- HTTPS reverse proxy config must be tested before Telegram runtime smoke.
- Billing provider remains `fake` in the template; production billing needs a separate provider rollout.
- Webhook mode is intentionally deferred until long polling staging smoke is stable.
