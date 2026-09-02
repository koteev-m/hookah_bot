# Staging Deployment

Canonical release/deploy policy is `docs/DEPLOYMENT_RUNBOOK.md`. The single PostgreSQL V126
policy/state-machine contract is `docs/V126_STAGING_CUTOVER_CONTRACT.md`; its only executable command
authority is `scripts/v126-cutover.sh`. This file remains the one-VPS staging implementation-detail
runbook and must not define another V126 sequence or command path.

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
- `TELEGRAM_TRAFFIC_POLICY=PRODUCT` for current normal staging and the V126 window
- `TELEGRAM_ALLOWED_USER_IDS` and `TELEGRAM_ALLOWED_CHAT_IDS` empty/absent in `PRODUCT`; retained
  runtime `ALLOWLIST` compatibility is not a current public-pilot or V126 deployment profile
- `TELEGRAM_WEBAPP_PUBLIC_URL=https://staging.hookahtootah.club/miniapp/`
- `TELEGRAM_BOT_USERNAME`
- `PLATFORM_OWNER_TELEGRAM_ID`
- `PLATFORM_OWNER_USER_ID=` optional legacy compatibility alias; leave empty when `users.telegram_user_id` is the platform owner identity.
- `TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER`
- `VENUE_STAFF_INVITE_SECRET_PEPPER` explicit and restricted in staging `PRODUCT`
- `STAGING_MAINTENANCE_MODE=OFF` for normal public-pilot operation
- `STAGING_MAINTENANCE_ALLOWED_USER_IDS` and `STAGING_MAINTENANCE_ALLOWED_CHAT_IDS` empty in the
  normal example; exact restricted values exist only in an authorized `V126_SMOKE` window
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
grep -E '^(APP_ENV|POSTGRES_DB|POSTGRES_USER|DB_JDBC_URL|DB_USER|TELEGRAM_TRAFFIC_POLICY|TELEGRAM_WEBAPP_PUBLIC_URL|MINIAPP_STATIC_DIR|MINIAPP_DEV_SERVER_URL|CORS_ALLOWED_HOSTS)=' .env
```

Check secret presence only with masking:

```bash
required_secret_keys=( \
  POSTGRES_PASSWORD \
  DB_PASSWORD \
  TELEGRAM_BOT_TOKEN \
  API_SESSION_JWT_SECRET \
  TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER \
)
if awk -F= '$1 == "TELEGRAM_TRAFFIC_POLICY" && toupper($2) == "PRODUCT" { found++ } END { exit !(found == 1) }' .env
then
  required_secret_keys+=(VENUE_STAFF_INVITE_SECRET_PEPPER)
fi
for key in "${required_secret_keys[@]}"
do
  if awk -v expected="$key" '
    index($0, "=") > 0 && substr($0, 1, index($0, "=") - 1) == expected {
      seen++
      value = substr($0, index($0, "=") + 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      probe = value
      gsub(/[[:space:]]/, "", probe)
      if (probe != "" && probe != "\"\"" && probe != sprintf("%c%c", 39, 39)) {
        nonempty++
      }
    }
    END { exit !(seen == 1 && nonempty == 1) }
  ' .env
  then
    printf '%s=%s\n' "$key" '***SET***'
  else
    printf '%s=%s\n' "$key" '***MISSING_OR_EMPTY_OR_DUPLICATE***' >&2
    exit 1
  fi
done
```

This check includes `VENUE_STAFF_INVITE_SECRET_PEPPER` only in `PRODUCT`, exits non-zero for a
missing, blank, explicitly empty-quoted or duplicate required key and never prints its value.
`AI_API_KEY` is optional; if the AI provider is enabled, validate it with the same masked
single-value check. Do not print raw `POSTGRES_PASSWORD`, `DB_PASSWORD`, Telegram tokens, JWT
secrets, peppers, API keys or allowlist IDs.

### Mandatory Staging Telegram Traffic Mode

`UNRESTRICTED` Telegram traffic is forbidden when `APP_ENV=staging`. Backend startup must fail
before database initialization unless the explicit configuration matches one of these modes.

Historical compatibility only (no current staging or V126 authorization):

```dotenv
TELEGRAM_TRAFFIC_POLICY=ALLOWLIST
TELEGRAM_ALLOWED_USER_IDS=<positive-user-id>,<positive-user-id>
TELEGRAM_ALLOWED_CHAT_IDS=<positive-private-chat-id>,<positive-private-chat-id>,<negative-staff-group-id>
```

The lists are comma-separated canonical decimal signed 64-bit values. Surrounding whitespace is
trimmed; empty elements, zero, overflow, non-decimal values and duplicates are rejected. User IDs
must be positive. Chat IDs may be positive private chats or negative groups/supergroups. For every
allowed Mini App/private-bot user, place the positive ID in both lists. Never commit real IDs.

If a separate future task explicitly authorizes this compatibility path, store runtime values only
in the mode-0600 server `.env` and maintain a second restricted operator
manifest at `/etc/hookah-bot/staging/telegram-allowlist.manifest`. The directory is `root:root` mode
0700 and the file is `root:root` mode 0600. The manifest maps non-sensitive tester aliases to the
exact user/private-chat/staff-chat IDs, purpose, approval and review date; it contains no bot token
and is never uploaded from or copied into the repository. Familiar people and previous staging
testers lose bot and Mini App access unless their exact IDs are present. That is intended fail-closed
behavior.

Public pilot:

```dotenv
TELEGRAM_TRAFFIC_POLICY=PRODUCT
TELEGRAM_ALLOWED_USER_IDS=
TELEGRAM_ALLOWED_CHAT_IDS=
VENUE_STAFF_INVITE_SECRET_PEPPER=<explicit-restricted-secret>
```

`PRODUCT` never requires a per-user or per-chat manifest entry. Positive matching private
actor/chat and valid signed Mini App identities enter as Guest; active memberships and server-side
RBAC remain required for Venue/Platform access. Product groups and outbound targets are accepted
only through the exact current server-owned staff-chat/product workflow rules. The normal deploy
profile requires this exact mode and rejects nonempty static lists or a missing, blank or normalized
known-placeholder invite pepper before build/upload/restart.

### Temporary identity-gated V126 maintenance overlay

The V126 migration window does not use `TELEGRAM_TRAFFIC_POLICY=ALLOWLIST` and does not use client
IP/CIDR attribution. Underlying policy remains `PRODUCT`; the separate overlay defaults to `OFF` and
has no authorization effect in normal public-pilot operation:

```dotenv
TELEGRAM_TRAFFIC_POLICY=PRODUCT
STAGING_MAINTENANCE_MODE=OFF
STAGING_MAINTENANCE_ALLOWED_USER_IDS=
STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=
```

Application runtime defaults to OFF, but the staging deploy guard requires all three maintenance
keys explicitly. OFF deploys require both lists empty, reject any stored authorization key and
require the one-shot process flag to be absent or false, preventing a silent stale maintenance
configuration.

Only a separately reviewed drain/cutover may put exact identities in the mode-0600 server `.env`:

```dotenv
TELEGRAM_TRAFFIC_POLICY=PRODUCT
STAGING_MAINTENANCE_MODE=V126_SMOKE
STAGING_MAINTENANCE_ALLOWED_USER_IDS=<restricted-positive-test-identities>
STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=<same-positive-private-chats-and-reviewed-negative-staff-chat>
```

Real values are restricted operational evidence and must never appear in Git, terminal capture,
deploy output, logs or a task/PR comment. The positive chat set exactly equals the user set; negative
entries are exact test staff chats. Active startup fails closed on a missing, malformed, duplicate,
zero, overflow or inconsistent value. Editing `.env` does not hot-reload the process; activation and
deactivation require the single controlled sequence in
`docs/V126_STAGING_CUTOVER_CONTRACT.md`.

`scripts/check-staging-maintenance-config.sh` remains the underlying configuration guard, but the
ordinary deploy script is not a V126 command. Only `scripts/v126-cutover.sh` may prepare active
maintenance, transfer the already-built image, start it later in a separate state, authorize manual
smoke, return maintenance to OFF and restore ordinary routing. The one-shot
`STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true` value contains no identities, is never stored in
`.env` and may be supplied only inside the exact sequencer stage that validates the active
configuration.

Gate A ends after automated schema/runtime verification while public Caddy drain remains active.
Gate B separately authorizes the exact manual-client window and cannot disable maintenance. Gate C
is unavailable until complete manual-smoke evidence exists; it reactivates the public drain before
stopping the backend, verifies explicit OFF with empty maintenance lists, starts the same exact V126
image and restores the original Caddyfile only after loopback gates pass. A retained active mode or
nonempty maintenance list is a release failure.

After the Gate-B state removes the already-loaded drain marker, explicitly require unauthenticated
protected traffic to remain generic `503`, then repeat with one valid excluded Telegram identity and
compare the restricted SSH/loopback state snapshots before running allowed smoke:

The sequencer owns the unauthenticated probe; do not put initData or an identity in a command or its
output. The excluded signed-initData check runs only through the canonical client, with its value
redacted; zero-state evidence stays in restricted operator artifacts. Before the first
migration-window start, the baseline receipt records the current backend's exact image
reference plus Docker image ID (and digest when present) from the VPS. Authorization is invalid
without that immutable predeploy rollback evidence; an application/source SHA alone is not an image
identity.

Preserve an already explicit invite pepper byte-for-byte during this transition. If the old
`ALLOWLIST` process used the built-in development fallback instead, capture only that fact (never the
value), install a new restricted explicit pepper, and reconcile/reissue every still-pending
staff/owner invite before relying on it; existing links cannot validate after the pepper changes.

The retained ALLOWLIST compatibility policy is immutable for the lifetime of a backend process and
has no current staging authorization. A separately scoped future use would require:

1. review and update the restricted manifest;
2. update only the staging `.env`;
3. perform a controlled backend restart with exactly one backend/poller;
4. repeat auth, queue, provenance and log gates.

Do not test startup failure against the live staging database. The automated
`TelegramTrafficPolicyStartupTest` starts the application locally with invalid staging settings and
proves fail-closed startup. A controlled deploy must also treat the expected policy/config exception
as a startup failure, never fall back to unrestricted mode.

If a separate future task authorizes ALLOWLIST, its denial proof must snapshot the relevant
`users`, `telegram_processed_updates`, `telegram_inbound_updates` and `telegram_outbox` facts, submit
one update and one signed Mini App auth attempt from a deliberately excluded test identity, and
compare the snapshots. The denied webhook response may be HTTP 200 to acknowledge intentional
discard, but it must enqueue nothing. Logs may contain only bounded denial reason/counts, never IDs,
token, initData, update payload or message text.

#### Historical ALLOWLIST staff-group bootstrap — no current authorization

This procedure is retained as historical compatibility evidence and is not part of normal staging,
V126, rollback or current operations. A separately authorized future ALLOWLIST task would have to
review it again before placing a staff group ID in a restricted manifest:

1. Create a dedicated, private, non-topic Telegram supergroup with a pre-reviewed exact title and
   post one disposable non-topic message from the operator's account. Do not reuse a real venue group.
2. In Telegram Desktop copy that message's private link. Accept only the exact
   `https://t.me/c/<positive-internal-id>/<positive-message-id>` shape and derive the candidate Bot
   API ID as `-100<positive-internal-id>`. Any public, topic, malformed, ambiguous, non-positive or
   overflowing link is a STOP.
3. During a separately authorized isolated-ALLOWLIST window, drain and stop only the current staging
   poller before adding the unchanged staging bot to this group. Never run a second poller or webhook,
   and restore the reviewed public-pilot `PRODUCT` configuration after the isolated smoke closes.
4. Read the unchanged bot token from its restricted secret store into a temporary mode-0600 curl
   config outside Git. The token must not appear in shell history, process arguments or command
   output. Call the read-only Bot API `getChat` for the candidate.
5. A local verifier must emit only PASS/FAIL and require `ok=true`, exact returned `result.id`, exact
   `result.type=supergroup` and the exact pre-reviewed `result.title`. Do not print or retain the raw
   JSON, title, token or ID in general logs.
6. Only after exact match, write the ID to the restricted manifest and staging `.env`. A mismatch,
   unavailable `getChat`, uncertain bot membership or inability to keep the token private is a STOP.

Real venue/user data is not disposable smoke data. The allowlist does not authorize using venue 2 or
any other existing real object as a fixture.

For temporary tunnel-based local dev, keep using local env files and set public URL variables to the exact current public origin. This path is legacy and should not be hardcoded in Kotlin, TypeScript, templates, or committed docs.

## 5. Build And Deploy

Standard local one-command deploy for ordinary public-pilot releases only:

This command intentionally builds, uploads and starts in one path. Therefore it is prohibited for
every V126 state and recovery branch; V126 uses the no-build transfer and separately gated startup
implemented only by `scripts/v126-cutover.sh`.

```bash
BACKEND_IMAGE='hookah_bot_ant-backend:<candidate-sha>' \
EXPECTED_BACKEND_IMAGE_ID='sha256:<reviewed-image-id>' \
./scripts/deploy-staging.sh '<ssh-alias>'
```

Current staging alias example:

```bash
BACKEND_IMAGE='hookah_bot_ant-backend:<candidate-sha>' \
EXPECTED_BACKEND_IMAGE_ID='sha256:<reviewed-image-id>' \
./scripts/deploy-staging.sh hookah-staging
```

The script:

1. runs the secret-free admission and maintenance guard fixtures locally;
2. requires a reviewed canonical `EXPECTED_BACKEND_IMAGE_ID`, then builds the backend image locally
   for `linux/amd64` with BuildKit provenance disabled,
   including the Mini App production build;
3. compares the built canonical `sha256` image ID to the reviewed value and stops before opening
   SSH, uploading files or making any remote change on a missing, malformed or different value;
4. uploads Compose/control files, then validates the fixed `.env` through the scrubbed effective
   Compose admission guard and the separate maintenance authorization guard without printing
   secrets or identities;
5. uploads the already verified Docker image;
6. runs `docker compose --env-file .env up -d --no-build postgres backend` through the same scrubbed
   admission and maintenance environment so shell values cannot replace reviewed configuration;
7. waits and retries loopback health/DB/static checks and, when enabled, the public staging URL.

CI and local release validation use the same secret-free checks:

```bash
bash -n scripts/validate-staging-admission.sh
bash -n scripts/deploy-staging.sh
bash scripts/validate-staging-admission.sh --self-test docker-compose.yml
```

The ordinary command always means public-pilot `PRODUCT`. The retained compatibility invocation
below is historical and has no current staging or V126 authorization:

```bash
STAGING_ADMISSION_PROFILE=isolated-allowlist \
BACKEND_IMAGE='hookah_bot_ant-backend:<candidate-sha>' \
EXPECTED_BACKEND_IMAGE_ID='sha256:<reviewed-image-id>' \
./scripts/deploy-staging.sh '<ssh-alias>'
```

That compatibility opt-in is not a public-pilot, V126 or rollback path. A future task needs separate
explicit authorization before use.

The health wait handles short backend startup windows and transient reverse-proxy connection resets. If the script still fails after all attempts, do not redeploy blindly; inspect container status and backend logs first.

For an ordinary digest-authorized release, record the reviewed ID returned by
`docker image inspect` and pass that exact value to the deploy. The V126 final-release selection
instead requires a separate two-build proof after HT-12P integration; the resulting already-built
full-SHA tag and exact image ID become immutable sequencer inputs. The ordinary deploy uses
`--provenance=false` because BuildKit provenance attestations can change the top-level manifest-list
digest between otherwise identical rebuilds. This local Docker-save deployment does not publish an
attestation; source SHA, green Actions and the independently reviewed diff remain the provenance
evidence. The expected-ID comparison runs after build but before any image upload or service restart:

```bash
BACKEND_IMAGE='hookah_bot_ant-backend:<candidate-sha>' \
EXPECTED_BACKEND_IMAGE_ID='sha256:<reviewed-image-id>' \
./scripts/deploy-staging-controlmaster.sh '<ssh-alias>'
```

`EXPECTED_BACKEND_IMAGE_ID` is mandatory for every deploy. Omitting it, supplying a malformed ID or
building a different image fails locally before SSH, upload or service mutation.

The V126 sequencer additionally requires the existing remote `docker-compose.yml`,
`scripts/check-staging-maintenance-config.sh` and `scripts/validate-staging-admission.sh` to be
operator-owned, non-symlink files whose bytes equal their exact `RELEASE_SHA` Git objects. It seals
those hashes together with the restricted database-target and maintenance-identity content hashes,
the complete staging `.env` bytes and ordinary Caddyfile bytes at baseline. Every dependent remote
action revalidates the exact applicable baseline or completed maintenance/Caddy receipt. Release
objects are read only through sanitized Git plumbing with inherited `GIT_*` controls removed and
replacement objects disabled. Only a
deterministic, inverse-reconstructable partial transition at its exact recovery predecessor can be
recognized without the missing stage receipt. Preparing the exact release execution surface is a
separate HT-13 prerequisite; `scripts/v126-cutover.sh` neither uploads nor repairs it, and it rejects
a mutable, V125 or locally edited copy.

V126 image transfer and startup are separate states. The transfer gate validates the rendered
Compose JSON for the `backend` service specifically. Docker-save bytes are captured in an unlinked
mode-0400 descriptor, and that exact descriptor is parsed, hashed and transferred using
Bash-3.2/openrsync-compatible syntax. The remote loader re-parses and hashes its own unlinked
descriptor and feeds the same bytes to `docker load`; it never reopens the retained archive path for
execution. Each V126 startup uses create-only
`--no-build`, forces restart policy `no`, requires `RestartCount=0`, issues exactly one explicit
start, and then proves one total/running Compose backend with the exact V126 image and long-polling
configuration, zero global live V125 containers and zero old-image backend in the staging project.

On Mac Apple Silicon the script uses
`docker buildx build --platform linux/amd64 --provenance=false --load` so the uploaded image matches
a typical x86_64/amd64 VPS and has a stable preupload identity for the expected-ID gate.

The backend Dockerfile uses BuildKit cache mounts for Gradle wrapper and dependency caches. If Docker build fails while downloading the Gradle distribution or Maven dependencies with a transient `SocketTimeoutException`, rerun the same deploy command after confirming it is a network timeout, not a Kotlin compile/test failure. The wrapper network timeout is intentionally higher than the default, and a successful retry should reuse the warmed Docker/Gradle cache.

### Opt-In Persistent SSH Deploy

Direct deployment remains supported. Use the ControlMaster helper when repeated new SSH connection establishment is unreliable during deploy; both paths require the same exact image identity.

Resilient command:

```bash
BACKEND_IMAGE='hookah_bot_ant-backend:<candidate-sha>' \
EXPECTED_BACKEND_IMAGE_ID='sha256:<reviewed-image-id>' \
./scripts/deploy-staging-controlmaster.sh '<ssh-alias>'
```

Current staging alias example with optional overrides:

```bash
STAGING_PATH=/opt/hookah-bot \
STAGING_DOMAIN=staging.example.com \
DOCKER_PLATFORM=linux/amd64 \
BACKEND_IMAGE='example-backend:<candidate-sha>' \
EXPECTED_BACKEND_IMAGE_ID='sha256:<reviewed-image-id>' \
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
BACKEND_IMAGE=hookah_bot_ant-backend:<candidate-sha> \
DOCKER_PLATFORM=linux/amd64 \
HEALTHCHECK_ATTEMPTS=20 \
HEALTHCHECK_SLEEP_SECONDS=3 \
EXPECTED_BACKEND_IMAGE_ID=sha256:<reviewed-image-id> \
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

For ordinary initial setup only, edit `.env`, then build and start:

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

These generic `up`/restart commands are prohibited as a substitute for any V126 phase. They do not
enforce the two backups, Caddy drain, zero-session gates, final preflight, identity overlay or
controlled OFF transition.

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

Forwarded or client-supplied IP headers are diagnostics only. They are never identity, RBAC or V126
authorization, even when the proxy overwrites them. Caddy is not an identity provider.

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
2. Set a nonempty `TELEGRAM_WEBHOOK_SECRET_TOKEN`; staging startup fails closed without it, so an
   allowlisted actor/chat cannot be forged through an unauthenticated public webhook request.
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

The earlier single-dump/in-place-restore example is superseded and must not be used for V126. The
single current staging V126 contract requires a pre-drain and a quiesced full custom-format backup,
a separate restricted globals artifact, mode 0600, SHA-256, successful `pg_restore --list`, the
exact free-space gate and an isolated same-version rehearsal for each full backup. Use only the
receipt-gated backup states in `scripts/v126-cutover.sh`; policy and artifact requirements remain in
`docs/V126_STAGING_CUTOVER_CONTRACT.md`.

Never restore over the live staging database as an ordinary release rollback. A full consistent
restore is separately authorized recovered DR; partial restore and automatic globals restore are
prohibited.

## 12. Rollback And Restart

### Restart

Use this for an ordinary controlled backend restart without changing image or database state. It is
not permitted while a V126 run exists; the sequencer owns both V126 startup states:

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

The examples in this subsection are ordinary OFF-mode rollback references only. They are not V126
commands and must not be composed with a V126 run. V126 recovery uses only
`scripts/v126-cutover.sh recover ...` under the exact policy in
`docs/V126_STAGING_CUTOVER_CONTRACT.md`.

The deploy script uploads a Docker image selected by `BACKEND_IMAGE`. For rollback-friendly releases, deploy with an immutable image tag, for example:

```bash
BACKEND_IMAGE='hookah_bot_ant-backend:<known-good-full-sha>' \
EXPECTED_BACKEND_IMAGE_ID='sha256:<reviewed-known-good-image-id>' \
./scripts/deploy-staging.sh hookah-staging
```

For V126 specifically, pre-V126 recovery first requires live Flyway V125/V126-absent proof before
mutation, then establishes and proves public drain, and may select only source
`f577934691a1a7a79ba327c54e2055425142b7be`, image ID
`sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8`, PRODUCT, maintenance OFF
and empty lists. It classifies Flyway before any Caddy/backend mutation and can finish the drain from
a partial activation only after validating the root-owned original/candidate bytes and checksums.
The bounded command refuses after V126 is present. Post-V126 recovery likewise classifies exact
successful V126 before Caddy/backend mutation and keeps or restores drain. It stops only the scoped
Compose backend and succeeds only after proving zero global V125 and run-bound V126 containers; an
outside-project match is left untouched and fails closed for operator resolution. Its terminal
scoped-image result is one of `EXACT_V126_STOPPED`,
`NO_BACKEND_ALREADY_STOPPED`, `V125_REFUSED_AND_STOPPED` or
`UNKNOWN_REFUSED_AND_STOPPED`; none permits a V125 or forward-fix start. The full-DR verifier checks
the backup and zero-writer/session boundary and stops before restore authorization.
An exact successful post-V126-stop recovery receipt may anchor that separately authorized full-DR
prerequisite verifier, but neither command can resume the normal cutover chain or perform a restore.

For an ordinary non-V126 rollback to an image already loaded on the VPS:

```bash
ssh hookah-staging
cd /opt/hookah-bot
docker images 'hookah_bot_ant-backend'
ROLLBACK_BACKEND_IMAGE='hookah_bot_ant-backend:<known-good-full-sha>'
EXPECTED_ROLLBACK_IMAGE_ID='sha256:<reviewed-known-good-image-id>'
actual_rollback_image_id="$(docker image inspect --format '{{.Id}}' "${ROLLBACK_BACKEND_IMAGE}")"
bash scripts/check-staging-image-identity.sh \
  "${actual_rollback_image_id}" \
  "${EXPECTED_ROLLBACK_IMAGE_ID}"
BACKEND_IMAGE="${ROLLBACK_BACKEND_IMAGE}" docker compose up -d --no-build backend
running_backend_image_id="$(docker inspect --format '{{.Image}}' "$(docker compose ps -q backend)")"
test "${running_backend_image_id}" = "${EXPECTED_ROLLBACK_IMAGE_ID}"
docker compose ps
docker compose logs --tail=120 backend
```

For an ordinary OFF-mode rollback, pass loopback config/health/DB/schema/queue gates before restoring
routing, then run the public sanity checks:

```bash
curl -f https://staging.hookahtootah.club/health
curl -f https://staging.hookahtootah.club/db/health
curl -I https://staging.hookahtootah.club/miniapp/
```

For an ordinary non-V126 rollback, if the approved compatible image is not available on the VPS,
rebuild and redeploy only the exact reviewed commit with its full-SHA tag and expected image ID. This
fallback is categorically unavailable to the V126 sequencer: V126 accepts only its already-built,
verified image inputs and separates transfer from startup.

Database caution: if the failed release applied migrations, code rollback may not be enough. In
particular, after V126 the old-writer rollback path is forbidden. The HT-12P full-DR command only
verifies prerequisites and records the accepted recovery point/data-loss boundary; it does not
perform a restore. Any later full restore requires a separate explicit authorization.

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
