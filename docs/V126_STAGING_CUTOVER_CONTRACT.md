# PostgreSQL V126 Staging Cutover Contract

Status: **canonical controlled-cutover contract / HT-12C pre-main-integration candidate**.

This is the single current ordered contract for the PostgreSQL V126 staging cutover. Other
documents may define product assertions or one-VPS implementation details, but they must not define
another V126 order. This document is not an activation record and does not authorize a backup,
Caddy reload, backend stop/start, maintenance activation, Flyway/V126, smoke mutation, restore or
cutover.

## Immutable scope and current anchors

HT-12C is documentation and bounded operational-guard work only. It must not change backend or Mini
App production source, PostgreSQL/H2 migrations, active Caddy configuration or staging data. A
runtime or migration change stops as `HT12C_RUNTIME_CHANGE_REQUIRED`.

The exact fetched HT-12C base is:

- main SHA: `9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1`;
- tree: `4071962a6850d977c4d7c319bfecc7cd4c2273d1`;
- parent: `f837b0ed01f68832b305d5a2ed61b3927583f1e9`;
- main Actions: CI run `33514472076`, push, branch `main`, attempt `1`, exact head SHA,
  completed/success with `11/11` successful jobs and no adverse job conclusion;
- HT-12M verdict: `IDENTITY_GATED_MAINTENANCE_PREREQUISITE_COMPLETE`.

That SHA is the exact base, not the final V126 release SHA. HT-12C creates another commit. The final
release identity is selected only after the reviewed feature branch is explicitly authorized for
non-force main integration and the resulting exact main SHA has its own successful main Actions
run.

The immutable migration identities at the base and every HT-12C candidate are:

| Identity | Exact value |
| --- | --- |
| Complete migration tree | `765956602de896b4498a956753272a6bc2d2971e` |
| PostgreSQL migration tree | `bb2778e26e03e03211eab9f149777313f4a6f24b` |
| H2 migration tree | `07b5ba6ccf25e79c9cc419b9095bb664f2cfae18` |
| PostgreSQL V125 blob | `6a730d1e1c24512f63d13417e10f926390cd0d27` |
| PostgreSQL V125 SHA-256 | `54f19b478294ebfb9b0b62a744fa54e22d23b38d5ab5514330fc7f5c36a3f306` |
| PostgreSQL V126 / H2 V127 blob | `6f39f7d33b1976d0f5eb7a70051bfc5351d12e56` |
| PostgreSQL V126 / H2 V127 SHA-256 | `ad11b2f95a6c73db226d3cd1ba53ac800a514c72d454b9255f379566195e08b5` |
| Flyway 11.19.0 V126 checksum | `1701638026` |
| H2 V126 blob | `f31460f9a755454619f9622ee6f001e603e6ef70` |
| H2 V126 SHA-256 | `b20f79b92148baa961b9c94f9974b6bcdb3ab114a681de14743b213cbde1dea7` |

Migration tree or blob divergence returns `RELEASE_IDENTITY_DIVERGED`. Partial restore, Flyway
repair, migration-history or cursor edits, manual migration-history checksum/version changes and
ad-hoc SQL domain-data repair are prohibited.

## Normal staging and superseded isolation

Ordinary public-pilot staging is exactly:

```ini
TELEGRAM_TRAFFIC_POLICY=PRODUCT
TELEGRAM_ALLOWED_USER_IDS=
TELEGRAM_ALLOWED_CHAT_IDS=
STAGING_MAINTENANCE_MODE=OFF
STAGING_MAINTENANCE_ALLOWED_USER_IDS=
STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=
```

Arbitrary structurally valid private identities remain supported as Guests. Valid external
OWNER/MANAGER/STAFF invitations remain supported. Venue and Platform access still require exact
server-owned membership and RBAC. Static PRODUCT lists remain empty. `OFF` must be explicit in the
deployed staging environment and both maintenance lists must be empty; the deploy guard rejects a
stale list, any stored `STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED` key or a retained true process
flag while `OFF`.

The following remain historical evidence but are superseded as current public-pilot or V126
isolation mechanisms:

- permanent staging `ALLOWLIST` as the public-pilot mode;
- stable client CIDR or source address as authorization;
- Caddy path logger, collector route, sidecar collector, packet observer/capture or patched-Caddy
  source-attribution experiments;
- TLS fingerprints and client-supplied or forwarded `X-Real-IP` / `X-Forwarded-*` headers as
  authority.

Runtime `ALLOWLIST` compatibility and its tests are not changed by HT-12C, but it is not the normal
staging profile, the V126 mechanism or a rollback target. Caddy may forward diagnostic headers in
ordinary proxying, but neither Caddy nor a header is an identity provider.

V126 migration isolation is only:

- a generic public Caddy `503` while the backend is stopped, starting or under loopback gates;
- underlying application policy `PRODUCT`;
- temporary fail-closed `V126_SMOKE` after the reviewed new backend starts;
- exact restricted Telegram users/chats stored only in mode-0600 operator configuration/evidence;
- ordinary authenticated identity, membership and RBAC rechecks; no IP or CIDR dependency.

## Fresh read-only staging baseline

The 2026-09-01 HT-12C read-only probe matched the requested baseline:

- application source `f577934691a1a7a79ba327c54e2055425142b7be`;
- backend image ID
  `sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8`;
- `PRODUCT`, maintenance `OFF`, all four static/maintenance list counts `0`;
- one backend and one long poller; one ready, healthy PostgreSQL;
- loopback/public health, DB health and Mini App healthy;
- webhook URL empty and Bot API pending update count `0`;
- actionable inbound queue (`PENDING`/`RETRY`/`PROCESSING`) `0` and actionable outbox
  (`NEW`/`SENDING`) `0`;
- nine historical terminal `FAILED` outbox rows remain preserved and are not actionable work;
- one successful Flyway V125 row, no V126 row and no failed Flyway row;
- Caddy 2.6.2 active, current Caddyfile valid, SHA-256
  `3138a01dbf9f55402d1125c599897f893bd75335492f11c9dfe2c1cce0ecedd4`, TLS 1.2 available,
  TLS 1.3 unavailable, no UDP/443 listener and no HTTP/3 Alt-Svc.

No committed earlier Caddyfile checksum exists. The current checksum and observable TLS mitigation
are the HT-12C baseline; the final predeploy probe must repeat them. Any requested staging value
divergence returns `STATE_DIVERGED_STAGING`. The terminal FAILED rows must not be deleted or
misreported merely to make a broad non-SENT metric zero.

## Final release selection after later main-integration authorization

Branch Actions do not select the release SHA. After an exact later authorization, integrate by a
reviewed non-force `--no-ff` merge from a clean isolated worktree, without squash, rebase or
force-push. If fresh `origin/main` is no longer the reviewed base, stop for reconciliation rather
than silently rebasing the evidence.

After integration, record the resulting exact main SHA, tree and parent(s) in the restricted
release record. Require a new exact CI run for that main SHA: workflow `CI`, event `push`, branch
`main`, attempt `1`, completed/success, every expected job successful and no adverse conclusion.
The release record is deliberately outside the selected Git commit: committing its own SHA into a
tracked file would change that SHA and create a false self-reference.

Only then run the following from a separate clean detached worktree. It deliberately builds twice,
uses the same build arguments, disables provenance both times, requires identical local image IDs,
records the full-SHA tag and does not upload or deploy it.

<!-- HT12C_FINAL_RELEASE_SELECTION_BEGIN -->
```bash
set -euo pipefail

: "${EXPECTED_RELEASE_SHA:?copy the exact green main Actions SHA}"
[[ "${EXPECTED_RELEASE_SHA}" =~ ^[0-9a-f]{40}$ ]]

git fetch --no-tags origin main
test "$(git rev-parse origin/main)" = "${EXPECTED_RELEASE_SHA}"

RELEASE_WORKTREE="/tmp/hookah-v126-release-${EXPECTED_RELEASE_SHA}"
test ! -e "${RELEASE_WORKTREE}"
git worktree add --detach "${RELEASE_WORKTREE}" "${EXPECTED_RELEASE_SHA}"
cd "${RELEASE_WORKTREE}"
test "$(git rev-parse HEAD)" = "${EXPECTED_RELEASE_SHA}"
test -z "$(git status --porcelain --untracked-files=all)"

test "$(git rev-parse HEAD:backend/app/src/main/resources/db/migration)" = \
  '765956602de896b4498a956753272a6bc2d2971e'
test "$(git rev-parse HEAD:backend/app/src/main/resources/db/migration/postgresql)" = \
  'bb2778e26e03e03211eab9f149777313f4a6f24b'
test "$(git rev-parse HEAD:backend/app/src/main/resources/db/migration/h2)" = \
  '07b5ba6ccf25e79c9cc419b9095bb664f2cfae18'

FINAL_IMAGE="hookah_bot_ant-backend:${EXPECTED_RELEASE_SHA}"
PROOF_IMAGE="hookah_bot_ant-backend:${EXPECTED_RELEASE_SHA}-proof-2"

docker buildx build \
  --platform linux/amd64 \
  --provenance=false \
  --load \
  --tag "${FINAL_IMAGE}" \
  --build-arg 'VITE_BACKEND_PUBLIC_URL=https://staging.hookahtootah.club' \
  --build-arg 'GRADLE_JVM_ARGS=-Xmx2048m -XX:MaxMetaspaceSize=768m' \
  -f backend/Dockerfile \
  .
FIRST_IMAGE_ID="$(docker image inspect --format '{{.Id}}' "${FINAL_IMAGE}")"

docker buildx build \
  --platform linux/amd64 \
  --provenance=false \
  --load \
  --tag "${PROOF_IMAGE}" \
  --build-arg 'VITE_BACKEND_PUBLIC_URL=https://staging.hookahtootah.club' \
  --build-arg 'GRADLE_JVM_ARGS=-Xmx2048m -XX:MaxMetaspaceSize=768m' \
  -f backend/Dockerfile \
  .
SECOND_IMAGE_ID="$(docker image inspect --format '{{.Id}}' "${PROOF_IMAGE}")"

bash scripts/check-staging-image-identity.sh "${FIRST_IMAGE_ID}" "${SECOND_IMAGE_ID}"
test "$(docker image inspect --format '{{.Id}}' "${FINAL_IMAGE}")" = "${FIRST_IMAGE_ID}"
REVISION_LABEL="$(docker image inspect \
  --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
  "${FINAL_IMAGE}")"
test -z "${REVISION_LABEL}" || test "${REVISION_LABEL}" = "${EXPECTED_RELEASE_SHA}"

printf 'FINAL_RELEASE_SHA=%s\nFINAL_IMAGE=%s\nFINAL_IMAGE_ID=%s\n' \
  "${EXPECTED_RELEASE_SHA}" "${FINAL_IMAGE}" "${FIRST_IMAGE_ID}"
```
<!-- HT12C_FINAL_RELEASE_SELECTION_END -->

Any mismatch returns `RELEASE_IDENTITY_DIVERGED`. Do not upload, SSH or deploy during this proof.
After it passes, repeat the complete sanitized staging baseline above. Those results populate G2
and G5; they still do not authorize G4 or G6-G9 execution.

## Caddy drain artifact contract

The only allowed drain change is the secret-free file-presence switch below, added inside the existing
`staging.hookahtootah.club` site block while every ordinary reverse-proxy/TLS directive remains
byte-identical:

```caddyfile
@v126_staging_drain file {
    root /
    try_files /etc/caddy/v126-drain.enabled
}
respond @v126_staging_drain "Service temporarily unavailable" 503
```

The empty root-owned marker exists only while public staging must be drained. Its presence returns
generic `503` for every request to the staging site before reverse proxying; its absence resumes the
unchanged reverse proxy. Creating or removing it requires no reload and carries no identity or
secret. At execution time, create one full candidate from the then-active Caddyfile. Only the block
above may be added inside the staging site. The other four site blocks and every unrelated byte
must remain unchanged. The restricted evidence must contain the
mode-0600 original and candidate, original SHA-256, a redacted exact diff showing only that site,
the installed Caddy version and successful validation of both files. Never add IP matchers,
allowlists, logs, collectors, sidecars, packet capture or a patched binary.

The preparation block is read/validate/copy only and performs no reload:

<!-- V126_CADDY_PREPARE_BEGIN -->
```bash
set -euo pipefail
umask 077

: "${EXPECTED_RELEASE_SHA:?}"
: "${CADDY_CANDIDATE:?path to the reviewed full candidate}"
[[ "${EXPECTED_RELEASE_SHA}" =~ ^[0-9a-f]{40}$ ]]
CADDY_ACTIVE='/etc/caddy/Caddyfile'
CADDY_EVIDENCE="/etc/caddy/v126-evidence/${EXPECTED_RELEASE_SHA}"
CADDY_ORIGINAL="${CADDY_EVIDENCE}/Caddyfile.original"
CADDY_DRAIN_SWITCH='/etc/caddy/v126-drain.enabled'
CADDY_DIFF="${CADDY_EVIDENCE}/Caddyfile.drain.diff"

sudo test -f "${CADDY_ACTIVE}"
test -f "${CADDY_CANDIDATE}"
test ! -L "${CADDY_CANDIDATE}"
sudo test ! -e "${CADDY_DRAIN_SWITCH}"
sudo test ! -L "${CADDY_DRAIN_SWITCH}"
sudo test ! -e "${CADDY_EVIDENCE}"
sudo test ! -L "${CADDY_EVIDENCE}"
sudo install -d -o root -g root -m 0700 "${CADDY_EVIDENCE}"
sudo install -o root -g root -m 0600 "${CADDY_ACTIVE}" "${CADDY_ORIGINAL}"
sudo install -o root -g root -m 0600 "${CADDY_CANDIDATE}" "${CADDY_EVIDENCE}/Caddyfile.drain"
sudo sha256sum "${CADDY_ORIGINAL}" | sudo tee "${CADDY_EVIDENCE}/Caddyfile.original.sha256" >/dev/null
sudo sha256sum "${CADDY_EVIDENCE}/Caddyfile.drain" | \
  sudo tee "${CADDY_EVIDENCE}/Caddyfile.drain.sha256" >/dev/null
sudo chmod 0600 "${CADDY_EVIDENCE}/Caddyfile.original.sha256"
sudo chmod 0600 "${CADDY_EVIDENCE}/Caddyfile.drain.sha256"
sudo install -o root -g root -m 0600 /dev/null "${CADDY_DIFF}"
set +e
sudo diff -u --label Caddyfile.original --label Caddyfile.drain \
  "${CADDY_ORIGINAL}" "${CADDY_EVIDENCE}/Caddyfile.drain" | sudo tee "${CADDY_DIFF}" >/dev/null
DIFF_STATUS="${PIPESTATUS[0]}"
set -e
test "${DIFF_STATUS}" = '1'
sudo chmod 0600 "${CADDY_DIFF}"
test "$(sudo sed -n '1p' "${CADDY_DIFF}")" = '--- Caddyfile.original'
test "$(sudo sed -n '2p' "${CADDY_DIFF}")" = '+++ Caddyfile.drain'
test "$(sudo awk 'NR > 2 && /^-/{count++} END {print count + 0}' "${CADDY_DIFF}")" = '0'
test "$(sudo awk 'NR > 2 && /^\+/{count++} END {print count + 0}' "${CADDY_DIFF}")" = '5'
ADDED_LINES="$(sudo awk 'NR > 2 && /^\+/{sub(/^\+[[:space:]]*/, ""); print}' "${CADDY_DIFF}")"
test "${ADDED_LINES}" = $'@v126_staging_drain file {\nroot /\ntry_files /etc/caddy/v126-drain.enabled\n}\nrespond @v126_staging_drain "Service temporarily unavailable" 503'
sudo caddy validate --config "${CADDY_ORIGINAL}" --adapter caddyfile
sudo caddy validate --config "${CADDY_EVIDENCE}/Caddyfile.drain" --adapter caddyfile
test "$(sudo stat -c '%a' "${CADDY_ORIGINAL}")" = '600'
test "$(sudo stat -c '%a' "${CADDY_EVIDENCE}/Caddyfile.drain")" = '600'
test "$(sudo stat -c '%a' "${CADDY_DIFF}")" = '600'
sudo test ! -e "${CADDY_DRAIN_SWITCH}"
sudo test ! -L "${CADDY_DRAIN_SWITCH}"
```
<!-- V126_CADDY_PREPARE_END -->

Activation occurs once in Phase 1, after candidate review/validation and the pre-drain backup
rehearsal. These are the only activation commands:

```bash
set -euo pipefail
: "${EXPECTED_RELEASE_SHA:?}"
[[ "${EXPECTED_RELEASE_SHA}" =~ ^[0-9a-f]{40}$ ]]
CADDY_ACTIVE='/etc/caddy/Caddyfile'
CADDY_EVIDENCE="/etc/caddy/v126-evidence/${EXPECTED_RELEASE_SHA}"
CADDY_ORIGINAL="${CADDY_EVIDENCE}/Caddyfile.original"
CADDY_CANDIDATE="${CADDY_EVIDENCE}/Caddyfile.drain"
sudo test -d "${CADDY_EVIDENCE}"
sudo test ! -L "${CADDY_EVIDENCE}"
test "$(sudo stat -c '%a:%U:%G' "${CADDY_EVIDENCE}")" = '700:root:root'
for artifact in \
  "${CADDY_ORIGINAL}" \
  "${CADDY_CANDIDATE}" \
  "${CADDY_EVIDENCE}/Caddyfile.original.sha256" \
  "${CADDY_EVIDENCE}/Caddyfile.drain.sha256"; do
  sudo test -f "${artifact}"
  sudo test ! -L "${artifact}"
done
CADDY_ORIGINAL_SHA="$(sudo awk 'NF == 2 {print $1; exit}' \
  "${CADDY_EVIDENCE}/Caddyfile.original.sha256")"
CADDY_CANDIDATE_SHA="$(sudo awk 'NF == 2 {print $1; exit}' \
  "${CADDY_EVIDENCE}/Caddyfile.drain.sha256")"
CADDY_DRAIN_SWITCH='/etc/caddy/v126-drain.enabled'
[[ "${CADDY_ORIGINAL_SHA}" =~ ^[0-9a-f]{64}$ ]]
[[ "${CADDY_CANDIDATE_SHA}" =~ ^[0-9a-f]{64}$ ]]
test "$(sudo sha256sum "${CADDY_ACTIVE}" | awk '{print $1}')" = "${CADDY_ORIGINAL_SHA}"
test "$(sudo sha256sum "${CADDY_ORIGINAL}" | awk '{print $1}')" = "${CADDY_ORIGINAL_SHA}"
test "$(sudo sha256sum "${CADDY_CANDIDATE}" | awk '{print $1}')" = "${CADDY_CANDIDATE_SHA}"
sudo test ! -e "${CADDY_DRAIN_SWITCH}"
sudo test ! -L "${CADDY_DRAIN_SWITCH}"
sudo caddy validate --config "${CADDY_CANDIDATE}" --adapter caddyfile
sudo install -o root -g root -m 0600 /dev/null "${CADDY_DRAIN_SWITCH}"
sudo install -o root -g root -m 0644 "${CADDY_CANDIDATE}" "${CADDY_ACTIVE}"
test "$(sudo sha256sum "${CADDY_ACTIVE}" | awk '{print $1}')" = "${CADDY_CANDIDATE_SHA}"
sudo systemctl reload caddy
DRAIN_PROBE_BODY="$(mktemp "${TMPDIR:-/tmp}/v126-drain-probe.XXXXXX")"
trap 'rm -f -- "${DRAIN_PROBE_BODY}"' EXIT
test "$(curl -sS -o "${DRAIN_PROBE_BODY}" -w '%{http_code}' \
  https://staging.hookahtootah.club/health)" = '503'
test "$(cat "${DRAIN_PROBE_BODY}")" = 'Service temporarily unavailable'
```

After automated V126 startup gates pass, ordinary routing is enabled for the identity-gated smoke
without a reload:

```bash
set -euo pipefail
CADDY_DRAIN_SWITCH='/etc/caddy/v126-drain.enabled'
sudo test -f "${CADDY_DRAIN_SWITCH}"
sudo test ! -L "${CADDY_DRAIN_SWITCH}"
sudo rm -f -- "${CADDY_DRAIN_SWITCH}"
sudo test ! -e "${CADDY_DRAIN_SWITCH}"
sudo test ! -L "${CADDY_DRAIN_SWITCH}"
```

After the full smoke passes, re-enable the generic drain before stopping the backend, again without
a reload:

```bash
set -euo pipefail
CADDY_DRAIN_SWITCH='/etc/caddy/v126-drain.enabled'
sudo test ! -e "${CADDY_DRAIN_SWITCH}"
sudo test ! -L "${CADDY_DRAIN_SWITCH}"
sudo install -o root -g root -m 0600 /dev/null "${CADDY_DRAIN_SWITCH}"
test "$(sudo stat -c '%a' "${CADDY_DRAIN_SWITCH}")" = '600'
DRAIN_PROBE_BODY="$(mktemp "${TMPDIR:-/tmp}/v126-drain-probe.XXXXXX")"
trap 'rm -f -- "${DRAIN_PROBE_BODY}"' EXIT
test "$(curl -sS -o "${DRAIN_PROBE_BODY}" -w '%{http_code}' \
  https://staging.hookahtootah.club/health)" = '503'
test "$(cat "${DRAIN_PROBE_BODY}")" = 'Service temporarily unavailable'
```

Restoration occurs once in Phase 5, after the OFF backend passes loopback gates while the switch
still keeps traffic drained. These are the only restoration commands:

```bash
set -euo pipefail
: "${EXPECTED_RELEASE_SHA:?}"
[[ "${EXPECTED_RELEASE_SHA}" =~ ^[0-9a-f]{40}$ ]]
CADDY_ACTIVE='/etc/caddy/Caddyfile'
CADDY_EVIDENCE="/etc/caddy/v126-evidence/${EXPECTED_RELEASE_SHA}"
CADDY_ORIGINAL="${CADDY_EVIDENCE}/Caddyfile.original"
CADDY_CANDIDATE="${CADDY_EVIDENCE}/Caddyfile.drain"
sudo test -d "${CADDY_EVIDENCE}"
sudo test ! -L "${CADDY_EVIDENCE}"
test "$(sudo stat -c '%a:%U:%G' "${CADDY_EVIDENCE}")" = '700:root:root'
for artifact in \
  "${CADDY_ORIGINAL}" \
  "${CADDY_CANDIDATE}" \
  "${CADDY_EVIDENCE}/Caddyfile.original.sha256" \
  "${CADDY_EVIDENCE}/Caddyfile.drain.sha256"; do
  sudo test -f "${artifact}"
  sudo test ! -L "${artifact}"
done
CADDY_ORIGINAL_SHA="$(sudo awk 'NF == 2 {print $1; exit}' \
  "${CADDY_EVIDENCE}/Caddyfile.original.sha256")"
CADDY_CANDIDATE_SHA="$(sudo awk 'NF == 2 {print $1; exit}' \
  "${CADDY_EVIDENCE}/Caddyfile.drain.sha256")"
CADDY_DRAIN_SWITCH='/etc/caddy/v126-drain.enabled'
[[ "${CADDY_ORIGINAL_SHA}" =~ ^[0-9a-f]{64}$ ]]
[[ "${CADDY_CANDIDATE_SHA}" =~ ^[0-9a-f]{64}$ ]]
sudo test -f "${CADDY_DRAIN_SWITCH}"
sudo test ! -L "${CADDY_DRAIN_SWITCH}"
test "$(sudo sha256sum "${CADDY_ORIGINAL}" | awk '{print $1}')" = "${CADDY_ORIGINAL_SHA}"
test "$(sudo sha256sum "${CADDY_CANDIDATE}" | awk '{print $1}')" = "${CADDY_CANDIDATE_SHA}"
test "$(sudo sha256sum "${CADDY_ACTIVE}" | awk '{print $1}')" = "${CADDY_CANDIDATE_SHA}"
sudo caddy validate --config "${CADDY_ORIGINAL}" --adapter caddyfile
sudo install -o root -g root -m 0644 "${CADDY_ORIGINAL}" "${CADDY_ACTIVE}"
test "$(sudo sha256sum "${CADDY_ACTIVE}" | awk '{print $1}')" = "${CADDY_ORIGINAL_SHA}"
sudo systemctl reload caddy
sudo rm -f -- "${CADDY_DRAIN_SWITCH}"
sudo test ! -e "${CADDY_DRAIN_SWITCH}"
sudo test ! -L "${CADDY_DRAIN_SWITCH}"
```

Exactly one activation reload and one restoration reload are allowed. The restored active
Caddyfile must equal the captured original byte-for-byte. HT-12C itself runs none of these commands.

## Backup and isolated restore artifact

Both full backups use custom format and remain preserved. The first is taken before the drain. The
second is taken only after the backend, application-writer and unidentified-session counts are all
zero. Each receives mode 0600, SHA-256, a successful `pg_restore --list`, a restricted inventory
and its own same-version isolated rehearsal. One separate restricted globals artifact is created
with `pg_dumpall --globals-only --no-role-passwords`; it is inventoried but never restored
automatically.

Run the marker-bounded block once with `BACKUP_PHASE=pre-drain` in Phase 0 and again with
`BACKUP_PHASE=quiesced` only after the Phase 1 zero-writer gate. It uses the exact running
PostgreSQL image, one disposable Docker volume, `--network none`, no published ports, no Compose
network and no staging/production pgdata mount. It never uses `tmpfs size=512m`. Readiness is
bounded to 60 one-second attempts. The required free-space threshold is
`max(2 GiB, 4 × source DB size + 2 × dump size)`.

<!-- V126_BACKUP_REHEARSAL_BEGIN -->
```bash
set -euo pipefail
umask 077

: "${EXPECTED_RELEASE_SHA:?}"
: "${BACKUP_PHASE:?set pre-drain or quiesced}"
[[ "${EXPECTED_RELEASE_SHA}" =~ ^[0-9a-f]{40}$ ]]
case "${BACKUP_PHASE}" in
  pre-drain | quiesced) ;;
  *) echo 'BACKUP_PHASE must be pre-drain or quiesced' >&2; exit 2 ;;
esac

cd /opt/hookah-bot
BACKUP_ROOT="/var/backups/hookah-bot/v126/${EXPECTED_RELEASE_SHA}"
OPERATOR_USER="$(id -un)"
OPERATOR_GROUP="$(id -gn)"
if sudo test -e "${BACKUP_ROOT}"; then
  sudo test -d "${BACKUP_ROOT}"
  sudo test ! -L "${BACKUP_ROOT}"
else
  sudo test ! -L "${BACKUP_ROOT}"
  sudo install -d -o "${OPERATOR_USER}" -g "${OPERATOR_GROUP}" -m 0700 "${BACKUP_ROOT}"
fi
test "$(stat -c '%a:%U:%G' "${BACKUP_ROOT}")" = \
  "700:${OPERATOR_USER}:${OPERATOR_GROUP}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DUMP_FILE="${BACKUP_ROOT}/${BACKUP_PHASE}-${TIMESTAMP}.dump"
LIST_FILE="${DUMP_FILE}.pg_restore.list"
SHA_FILE="${DUMP_FILE}.sha256"
METADATA_FILE="${DUMP_FILE}.rehearsal.txt"
for artifact in "${DUMP_FILE}" "${LIST_FILE}" "${SHA_FILE}" "${METADATA_FILE}"; do
  test ! -e "${artifact}"
  test ! -L "${artifact}"
done
set -o noclobber

POSTGRES_CONTAINER="$(docker compose ps -q postgres)"
test -n "${POSTGRES_CONTAINER}"
SOURCE_IMAGE_ID="$(docker inspect --format '{{.Image}}' "${POSTGRES_CONTAINER}")"
[[ "${SOURCE_IMAGE_ID}" =~ ^sha256:[0-9a-f]{64}$ ]]
SOURCE_DB_USER="$(docker compose exec -T postgres sh -c 'printf %s "$POSTGRES_USER"')"
SOURCE_VERSION="$(docker compose exec -T postgres sh -c \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SHOW server_version_num"')"
SOURCE_DB_SIZE="$(docker compose exec -T postgres sh -c \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SELECT pg_database_size(current_database())"')"
test -n "${SOURCE_DB_USER}"
[[ "${SOURCE_VERSION}" =~ ^[0-9]+$ ]]
[[ "${SOURCE_DB_SIZE}" =~ ^[0-9]+$ ]]

minimum_available_bytes() {
  local backup_available
  local docker_root
  local docker_available
  backup_available="$(df --output=avail -B1 "${BACKUP_ROOT}" | awk 'NR == 2 {print $1}')"
  docker_root="$(docker info --format '{{.DockerRootDir}}')"
  test -d "${docker_root}"
  docker_available="$(df --output=avail -B1 "${docker_root}" | awk 'NR == 2 {print $1}')"
  [[ "${backup_available}" =~ ^[0-9]+$ && "${docker_available}" =~ ^[0-9]+$ ]]
  if (( backup_available < docker_available )); then
    printf '%s\n' "${backup_available}"
  else
    printf '%s\n' "${docker_available}"
  fi
}

MINIMUM_BYTES=$((2 * 1024 * 1024 * 1024))
PRELIMINARY_BYTES=$((4 * SOURCE_DB_SIZE))
if (( PRELIMINARY_BYTES < MINIMUM_BYTES )); then PRELIMINARY_BYTES="${MINIMUM_BYTES}"; fi
AVAILABLE_BYTES="$(minimum_available_bytes)"
(( AVAILABLE_BYTES >= PRELIMINARY_BYTES ))

docker compose exec -T postgres sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > "${DUMP_FILE}"
test -s "${DUMP_FILE}"
chmod 0600 "${DUMP_FILE}"
docker compose exec -T postgres sh -c 'pg_restore --list' \
  < "${DUMP_FILE}" > "${LIST_FILE}"
test -s "${LIST_FILE}"
chmod 0600 "${LIST_FILE}"
sha256sum "${DUMP_FILE}" > "${SHA_FILE}"
chmod 0600 "${SHA_FILE}"
sha256sum -c "${SHA_FILE}" >/dev/null

if [[ "${BACKUP_PHASE}" == 'pre-drain' ]]; then
  GLOBALS_FILE="${BACKUP_ROOT}/globals-${TIMESTAMP}.sql"
  test ! -e "${GLOBALS_FILE}"
  test ! -L "${GLOBALS_FILE}"
  test ! -e "${GLOBALS_FILE}.sha256"
  test ! -L "${GLOBALS_FILE}.sha256"
  docker compose exec -T postgres sh -c \
    'pg_dumpall -U "$POSTGRES_USER" --globals-only --no-role-passwords' > "${GLOBALS_FILE}"
  test -s "${GLOBALS_FILE}"
  chmod 0600 "${GLOBALS_FILE}"
  sha256sum "${GLOBALS_FILE}" > "${GLOBALS_FILE}.sha256"
  chmod 0600 "${GLOBALS_FILE}.sha256"
  sha256sum -c "${GLOBALS_FILE}.sha256" >/dev/null
  test "$(stat -c '%a' "${GLOBALS_FILE}")" = '600'
  test "$(stat -c '%a' "${GLOBALS_FILE}.sha256")" = '600'
fi

DUMP_SIZE="$(stat -c '%s' "${DUMP_FILE}")"
AVAILABLE_BYTES="$(minimum_available_bytes)"
CALCULATED_BYTES=$((4 * SOURCE_DB_SIZE + 2 * DUMP_SIZE))
REQUIRED_BYTES="${MINIMUM_BYTES}"
if (( CALCULATED_BYTES > MINIMUM_BYTES )); then REQUIRED_BYTES="${CALCULATED_BYTES}"; fi
(( AVAILABLE_BYTES >= REQUIRED_BYTES ))

SAFE_PHASE="${BACKUP_PHASE//[^a-z0-9-]/-}"
REHEARSAL_VOLUME="hookah-v126-${SAFE_PHASE}-${TIMESTAMP,,}-$$"
REHEARSAL_CONTAINER="hookah-v126-${SAFE_PHASE}-${TIMESTAMP,,}-$$"
[[ "${REHEARSAL_VOLUME}" =~ ^hookah-v126-[a-z0-9-]+$ ]]
[[ "${REHEARSAL_CONTAINER}" =~ ^hookah-v126-[a-z0-9-]+$ ]]
if docker container inspect "${REHEARSAL_CONTAINER}" >/dev/null 2>&1; then
  echo 'rehearsal container name already exists; reconcile without deleting it' >&2
  exit 1
fi
if docker volume inspect "${REHEARSAL_VOLUME}" >/dev/null 2>&1; then
  echo 'rehearsal volume name already exists; reconcile without deleting it' >&2
  exit 1
fi

cleanup_rehearsal() {
  if [[ -n "${REHEARSAL_CONTAINER:-}" &&
    "${REHEARSAL_CONTAINER}" =~ ^hookah-v126-[a-z0-9-]+$ ]]; then
    docker rm -f "${REHEARSAL_CONTAINER}" >/dev/null 2>&1 || true
  fi
  if [[ -n "${REHEARSAL_VOLUME:-}" &&
    "${REHEARSAL_VOLUME}" =~ ^hookah-v126-[a-z0-9-]+$ ]]; then
    docker volume rm "${REHEARSAL_VOLUME}" >/dev/null 2>&1 || true
  fi
}
trap cleanup_rehearsal EXIT INT TERM

docker volume create --label hookah.v126.rehearsal=true "${REHEARSAL_VOLUME}" >/dev/null
docker run --detach \
  --name "${REHEARSAL_CONTAINER}" \
  --network none \
  --mount "type=volume,source=${REHEARSAL_VOLUME},target=/var/lib/postgresql/data" \
  --env "POSTGRES_USER=${SOURCE_DB_USER}" \
  --env POSTGRES_HOST_AUTH_METHOD=trust \
  "${SOURCE_IMAGE_ID}" >/dev/null

test "$(docker inspect --format '{{.HostConfig.NetworkMode}}' "${REHEARSAL_CONTAINER}")" = 'none'
test "$(docker inspect --format '{{len .HostConfig.PortBindings}}' "${REHEARSAL_CONTAINER}")" = '0'
test "$(docker inspect --format '{{len .Mounts}}' "${REHEARSAL_CONTAINER}")" = '1'
test "$(docker inspect --format '{{(index .Mounts 0).Type}}' "${REHEARSAL_CONTAINER}")" = 'volume'
test "$(docker inspect --format '{{(index .Mounts 0).Name}}' "${REHEARSAL_CONTAINER}")" = \
  "${REHEARSAL_VOLUME}"
test "$(docker inspect --format '{{(index .Mounts 0).Destination}}' "${REHEARSAL_CONTAINER}")" = \
  '/var/lib/postgresql/data'

READY=false
for attempt in $(seq 1 60); do
  if docker exec "${REHEARSAL_CONTAINER}" \
    pg_isready -U "${SOURCE_DB_USER}" -d postgres >/dev/null 2>&1; then
    READY=true
    break
  fi
  sleep 1
done
test "${READY}" = 'true'

docker cp "${DUMP_FILE}" "${REHEARSAL_CONTAINER}:/tmp/v126-rehearsal.dump"
docker exec "${REHEARSAL_CONTAINER}" \
  createdb -U "${SOURCE_DB_USER}" --template=template0 v126_restore_rehearsal
docker exec "${REHEARSAL_CONTAINER}" \
  pg_restore -U "${SOURCE_DB_USER}" --exit-on-error --no-owner --no-privileges \
  --dbname v126_restore_rehearsal /tmp/v126-rehearsal.dump

RESTORED_VERSION="$(docker exec "${REHEARSAL_CONTAINER}" \
  psql -X -U "${SOURCE_DB_USER}" -d v126_restore_rehearsal -Atqc 'SHOW server_version_num')"
test "${RESTORED_VERSION}" = "${SOURCE_VERSION}"
RESTORED_MIGRATION_STATE="$(docker exec "${REHEARSAL_CONTAINER}" \
  psql -X -U "${SOURCE_DB_USER}" -d v126_restore_rehearsal -Atqc \
  "SELECT CONCAT(MAX(version::integer), ':', COUNT(*) FILTER (WHERE version = '126'), ':', COUNT(*) FILTER (WHERE NOT success)) FROM flyway_schema_history")"
test "${RESTORED_MIGRATION_STATE}" = '125:0:0'

COMPLETED_REHEARSAL_CONTAINER="${REHEARSAL_CONTAINER}"
COMPLETED_REHEARSAL_VOLUME="${REHEARSAL_VOLUME}"
docker rm -f "${COMPLETED_REHEARSAL_CONTAINER}" >/dev/null
docker volume rm "${COMPLETED_REHEARSAL_VOLUME}" >/dev/null
if docker container inspect "${COMPLETED_REHEARSAL_CONTAINER}" >/dev/null 2>&1; then
  echo 'rehearsal container cleanup verification failed' >&2
  exit 1
fi
if docker volume inspect "${COMPLETED_REHEARSAL_VOLUME}" >/dev/null 2>&1; then
  echo 'rehearsal volume cleanup verification failed' >&2
  exit 1
fi
REHEARSAL_CONTAINER=''
REHEARSAL_VOLUME=''
trap - EXIT INT TERM

printf 'phase=%s\nsource_image_id=%s\nsource_version=%s\nsource_db_size=%s\ndump_size=%s\nrequired_free_bytes=%s\nrehearsal=PASS\n' \
  "${BACKUP_PHASE}" "${SOURCE_IMAGE_ID}" "${SOURCE_VERSION}" "${SOURCE_DB_SIZE}" \
  "${DUMP_SIZE}" "${REQUIRED_BYTES}" > "${METADATA_FILE}"
chmod 0600 "${METADATA_FILE}"
test "$(stat -c '%a' "${DUMP_FILE}")" = '600'
test "$(stat -c '%a' "${LIST_FILE}")" = '600'
test "$(stat -c '%a' "${SHA_FILE}")" = '600'
test "$(stat -c '%a' "${METADATA_FILE}")" = '600'
```
<!-- V126_BACKUP_REHEARSAL_END -->

`--no-owner --no-privileges` is limited to the isolated data/schema rehearsal because globals are
captured separately and must not be applied automatically. It does not weaken the required full
custom-format backup. Preserve both dumps, both inventories/hashes/rehearsal records and the one
globals artifact. A rehearsal failure blocks cutover; never compensate with a partial restore or
manual repair.

## Exact ordered state machine

### Phase 0 — ordinary public pilot

```text
Caddy ordinary routing
backend V125 running
PRODUCT
maintenance OFF
```

Before any downtime:

1. Close G0-G3 for the exact final release record. The restricted manual Guest/Owner clients and
   identities must be ready, but values must not enter Git, logs or general task output.
2. Complete the deterministic two-build proof and final read-only staging probe.
3. Create, hash, inventory and rehearse the fresh `pre-drain` full backup. Create the separate
   globals artifact. Preserve both.
4. Capture the active Caddyfile, checksum it, create the full generic-503 switch candidate, prove the other
   four sites unchanged and validate original/candidate. HT-12C provides no reload authorization.

### Phase 1 — generic public drain and V125 stop

1. Install the validated generic `503` candidate only for `staging.hookahtootah.club` and perform
   the one activation reload. Leave the other four sites unchanged.
2. Verify public staging returns the same generic `503`; no client CIDR exception exists.
3. Stop the V125 backend. PostgreSQL must stay running and ready.
4. Run the exact zero-writer gate below. Every returned count must be zero. A stricter zero of all
   non-gate application-database client sessions supplies both application-writer `0` and
   unidentified-session `0`; the idle-in-transaction count is cluster-wide.

<!-- V126_ZERO_WRITER_GATE_BEGIN -->
```bash
set -euo pipefail
cd /opt/hookah-bot

test "$(docker compose ps --status running -q backend | wc -l | tr -d ' ')" = '0'
test "$(docker compose ps --status running -q postgres | wc -l | tr -d ' ')" = '1'
docker compose exec -T postgres sh -c \
  'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null

SESSION_GATE="$(docker compose exec -T postgres sh -c \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  COUNT(*) FILTER (
    WHERE backend_type = 'client backend'
      AND datname = current_database()
      AND pid <> pg_backend_pid()
  ), ':',
  COUNT(*) FILTER (
    WHERE backend_type = 'client backend'
      AND pid <> pg_backend_pid()
      AND state LIKE 'idle in transaction%'
  ), ':',
  (SELECT COUNT(*) FROM pg_prepared_xacts), ':',
  (SELECT COUNT(*) FROM pg_replication_slots)
)
FROM pg_stat_activity;
SQL
)"
test "${SESSION_GATE}" = '0:0:0:0'

QUEUE_GATE="$(docker compose exec -T postgres sh -c \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  (SELECT COUNT(*) FROM telegram_inbound_updates WHERE status IN ('PENDING', 'RETRY', 'PROCESSING')),
  ':',
  (SELECT COUNT(*) FROM telegram_outbox WHERE status IN ('NEW', 'SENDING'))
);
SQL
)"
test "${QUEUE_GATE}" = '0:0'

FLYWAY_GATE="$(docker compose exec -T postgres sh -c \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At --set=ON_ERROR_STOP=1' <<'SQL'
SELECT CONCAT(
  MAX(version::integer), ':',
  COUNT(*) FILTER (WHERE version = '126'), ':',
  COUNT(*) FILTER (WHERE NOT success)
)
FROM flyway_schema_history;
SQL
)"
test "${FLYWAY_GATE}" = '125:0:0'

printf '%s\n' \
  'hookah_backend_container_count=0' \
  'hookah_application_writer_session_count=0' \
  'unidentified_candidate_session_count=0' \
  'idle_in_transaction_session_count=0' \
  'prepared_transaction_count=0' \
  'replication_slot_count=0' \
  'actionable_inbound_queue_count=0' \
  'actionable_outbox_count=0'
```
<!-- V126_ZERO_WRITER_GATE_END -->

5. With all zero gates still true, create and fully rehearse the second `quiesced` custom-format
   backup. Give it its own mode-0600 dump, SHA-256, `pg_restore --list`, inventory and rehearsal
   record. Preserve both backups.
6. Only after the quiesced rehearsal passes, extract the marker-bounded final booking-integrity
   preflight from the exact final release worktree using the canonical extractor in
   `docs/DEPLOYMENT_RUNBOOK.md`. Record its SHA-256 and run the exact unedited Bash artifact. Any
   unsafe, ambiguous or incomplete result stops. Do not run a cached/clipboard copy.

### Phase 2 — reviewed V126 startup while public traffic remains drained

Before starting the candidate, change only the reviewed maintenance values in the mode-0600
staging environment:

```ini
TELEGRAM_TRAFFIC_POLICY=PRODUCT
TELEGRAM_ALLOWED_USER_IDS=
TELEGRAM_ALLOWED_CHAT_IDS=
STAGING_MAINTENANCE_MODE=V126_SMOKE
STAGING_MAINTENANCE_ALLOWED_USER_IDS=<restricted>
STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=<restricted>
```

Supply `STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED=true` only as the one-shot reviewed deploy-process
value. It is not a stored identity or `.env` setting. Preserve every unrelated environment byte.
Then:

1. Start exactly one reviewed V126 backend from the recorded full-SHA image and image ID. This is
   the candidate-start substep of the reviewed deploy path, not permission to skip Phases 0-1.
   Keep `RUN_PUBLIC_CHECKS=false` because Caddy remains drained.
2. Normal startup applies PostgreSQL V126.
3. Verify exactly one successful V126 row, checksum `1701638026`, no failed row, exact repository
   head/no pending migration, and the schema invariants with the block below.

<!-- V126_SCHEMA_GATE_BEGIN -->
```bash
set -euo pipefail
cd /opt/hookah-bot
docker compose exec -T postgres sh -c \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" --set=ON_ERROR_STOP=1' <<'SQL'
DO $contract$
BEGIN
  IF (SELECT COUNT(*) FROM flyway_schema_history WHERE version = '126') <> 1 THEN
    RAISE EXCEPTION 'V126 Flyway row count mismatch';
  END IF;
  IF (SELECT COUNT(*) FROM flyway_schema_history
      WHERE version = '126' AND success AND checksum = 1701638026) <> 1 THEN
    RAISE EXCEPTION 'V126 Flyway identity mismatch';
  END IF;
  IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN
    RAISE EXCEPTION 'failed Flyway history row exists';
  END IF;
  IF (SELECT MAX(version::integer) FROM flyway_schema_history) <> 126 THEN
    RAISE EXCEPTION 'Flyway head is not V126';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND table_name = 'support_thread_reads'
      AND column_name = 'last_read_message_id'
      AND data_type = 'bigint'
      AND is_nullable = 'YES'
      AND column_default IS NULL
      AND is_identity = 'NO'
      AND is_generated = 'NEVER'
  ) THEN
    RAISE EXCEPTION 'last_read_message_id invariant mismatch';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint c
    JOIN pg_class t ON t.oid = c.conrelid
    JOIN pg_namespace n ON n.oid = t.relnamespace
    WHERE n.nspname = current_schema()
      AND t.relname = 'support_thread_reads'
      AND c.contype = 'p'
      AND pg_get_constraintdef(c.oid, false) = 'PRIMARY KEY (thread_id, user_id)'
  ) THEN
    RAISE EXCEPTION 'support_thread_reads primary key mismatch';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM pg_index i
    JOIN pg_class idx ON idx.oid = i.indexrelid
    JOIN pg_class tbl ON tbl.oid = i.indrelid
    JOIN pg_namespace n ON n.oid = tbl.relnamespace
    WHERE n.nspname = current_schema()
      AND tbl.relname = 'support_messages'
      AND idx.relname = 'idx_support_messages_thread_id'
      AND NOT i.indisunique
      AND i.indisvalid
      AND i.indisready
      AND pg_get_indexdef(i.indexrelid) LIKE '%(thread_id, id)'
  ) THEN
    RAISE EXCEPTION 'support message unread index mismatch';
  END IF;
END
$contract$;
SQL
```
<!-- V126_SCHEMA_GATE_END -->

4. Verify exactly one backend/poller, the recorded new image ID on every running backend, old image
   running count `0`, loopback health/DB health/Mini App, PostgreSQL health, webhook URL empty, Bot
   API pending updates `0` and actionable inbound/outbox queues `0`. Historical terminal rows remain
   preserved.
5. Keep generic Caddy `503` until every automated migration/startup/schema/runtime gate passes.

### Phase 3 — identity-gated live smoke

1. Remove the empty drain-switch marker only after every Phase 2 gate passes. The already-loaded
   candidate resumes ordinary staging reverse-proxy routing without a reload.
2. Keep `V126_SMOKE` active. Only the exact permitted Guest/Owner identities may use Telegram and
   Mini App. Every other protected identity receives the same generic `503` before mutation.
3. Recheck JWTs issued before activation. Compare denied snapshots and require excluded inbound,
   user/session/idempotency/domain/notification/outbox rows unchanged.
4. Keep subscription billing, table cleanup, booking expiry/reminders and every other autonomous
   writer disabled under the HT-12M contract.
5. Run the complete Guest/Owner/Telegram/Mini App smoke, including tenant/RBAC negatives, linked
   staff-chat delivery, NULL-author unread behavior, correct/wrong surface marker isolation,
   Support/Conversations separation and same-display-number/different-service-date label collision.

The mandatory non-substitutable live gate is:

`HT14_MANDATORY_LIVE_GATE_GUEST_REPLY_OWNER_UNREAD_CLEAR`

It must prove:

- one exact Guest reply and one persisted Guest message;
- exactly one expected Telegram/outbox delivery;
- Owner unread creation only on the exact thread;
- exact-thread-only Owner unread clear;
- no duplicate marker and no unread resurrection;
- no other-thread, CLIENT or non-MIX mutation.

Automated evidence never replaces this live assertion.

### Phase 4 — controlled maintenance disable

Only after the entire live smoke passes:

1. Recreate the empty drain-switch marker and prove public staging returns generic `503`; only then
   stop the V126 backend. Do not reload Caddy.
2. Change only these stored values, preserving every unrelated environment byte:
   `STAGING_MAINTENANCE_MODE=OFF`, empty maintenance user list and empty maintenance chat list.
   Omit/remove the one-shot `STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED` process flag.
3. Preserve `TELEGRAM_TRAFFIC_POLICY=PRODUCT`, both empty PRODUCT static lists, the exact V126 image,
   final application SHA and every unrelated environment value.
4. Run both staging admission and maintenance guards. `OFF` plus any stale list or active flag is a
   failure. Start exactly one V126 backend.
5. While public traffic remains drained, reverify Flyway V126/checksum, schema, exact image, one
   backend/poller, health/DB/Mini App, queues, webhook and PRODUCT admission configuration.

Maintenance must never remain silently active after a successful cutover.

### Phase 5 — byte-preserving Caddy restoration and final public gates

1. Restore the captured ordinary Caddyfile byte-for-byte and prove its SHA-256 equals the original.
2. Validate it and perform the one reviewed restoration reload.
3. Repeat public health, DB health, Mini App, exact image/backend/poller, Flyway/schema, webhook,
   pending-update, actionable-queue and fresh ordinary Guest/Owner PRODUCT gates.
4. Record the redacted OFF transition and all final results. Only this completes G9.

## Caddy reload clarification

The future cutover budget is exactly one activation reload and one restoration reload. The
candidate's empty marker switch enables the Phase 3 routing and Phase 4 pre-stop drain transitions
without a reload. Its secret-free syntax was validated read-only against installed Caddy 2.6.2 on
2026-09-01; execution must still validate the full then-current candidate and prove the toggle
behavior before cutover. If it does not produce that exact behavior, G6 is `BLOCKED_INPUT`; do not
improvise. Caddy remains a transport/drain component, never an identity authority.

## Recovery and rollback boundary

Allowed pre-V126 runtime rollback is only the exact deployed V125 identity:

- source `f577934691a1a7a79ba327c54e2055425142b7be`;
- image `sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8`;
- `PRODUCT`, maintenance `OFF`, empty PRODUCT and maintenance lists.

It is allowed only before V126 has applied and before any V126 smoke write. Keep traffic drained,
stop the failed candidate, prove the database remains at V125, start only that exact V125 image,
repeat loopback/runtime gates, then restore ordinary routing. Runtime rollback never reverses
committed memberships, messages, invites, orders or any other product fact.

After V126 has applied, it is prohibited to start any V125 backend over that database. Also
prohibited: partial restore, Flyway repair, migration-history edits, manual cursor/read-marker edits,
schema downgrade and manual domain-data repair. The primary response is to keep public traffic
drained and use a reviewed V126-compatible forward fix under the same identity overlay.

A full consistent V125 restore is a separate explicit disaster-recovery authorization, not a
successful V126 deployment. It requires an exact verified full backup and `pg_restore --list`
inventory, zero backend writers, zero unidentified/client sessions, zero idle-in-transaction
sessions, zero prepared transactions and zero replication slots. Globals are reviewed separately
and never restored automatically. The operator must state the accepted recovery point/data-loss
boundary, restore the whole database into a controlled target, verify the restored Flyway state and
start only the exact compatible reviewed image. No table, row or schema subset may be merged over
V126.

## G0-G9 predeploy matrix

This matrix uses only `CLOSED_PREDEPLOY`, `READY_FOR_EXECUTION`, `BLOCKED_INPUT` and
`EXECUTION_REQUIRED`. It never marks an execution-time gate PASS during HT-12C documentation work.

| Gate | Requirement | HT-12C feature-branch classification |
| --- | --- | --- |
| G0 | Exact staging environment and operator boundary | `CLOSED_PREDEPLOY` — sanitized baseline and no-secret boundary recorded; repeat after main integration. |
| G1 | HT-12M prerequisite complete on V125 staging and main | `CLOSED_PREDEPLOY` — exact V125 image/source and main run `33514472076` recorded. |
| G2 | Final release SHA/tree/Actions/migration identity | `BLOCKED_INPUT` — requires explicit main integration and new exact green main Actions. |
| G3 | Restricted maintenance identities and manual clients ready | `BLOCKED_INPUT` — operator must attest readiness in restricted evidence without disclosing values. |
| G4 | Fresh backup, globals artifact and isolated rehearsal | `EXECUTION_REQUIRED` — both backups/rehearsals occur only during the authorized window. |
| G5 | Deterministic immutable V126 image | `BLOCKED_INPUT` — two-build proof waits for the final integrated main SHA. |
| G6 | Generic 503 drain, backend 0, sessions/writers/preflight safe | `EXECUTION_REQUIRED` — includes the reviewed two-reload Caddy mechanism. |
| G7 | V126 startup and automated schema/runtime verification | `EXECUTION_REQUIRED`. |
| G8 | Identity-gated live smoke including mandatory HT-14 assertion | `EXECUTION_REQUIRED`. |
| G9 | Maintenance OFF transition, PRODUCT restoration and final Caddy restore | `EXECUTION_REQUIRED`. |

After a later authorized integration, G2/G5 may become `CLOSED_PREDEPLOY`, G3 may become
`READY_FOR_EXECUTION`, and G4/G6-G9 remain execution-time gates. Backup, maintenance activation,
Flyway/V126 and cutover still require a separate authorization.

## HT-12C main-integration gate

Before requesting integration, the feature branch must have a complete reviewed diff, local static
PASS, exact migration equality, no secret/raw identity leakage, a normal non-force push and an exact
successful branch Actions run with every expected job successful. Then stop at
`HT12C_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`.

The exact later authorization text is:

```text
AUTHORIZE_HT12C_MAIN_INTEGRATION
base=9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1
candidate=<exact reviewed feature-branch SHA>
candidate_tree=<exact reviewed feature-branch tree>
method=non-force-no-ff-merge
```

No shorter, implicit or cutover authorization substitutes for that text. It authorizes only the
reviewed main integration and its post-integration release-identity proof; it does not authorize a
backup, Caddy reload, staging write, maintenance activation, migration or cutover.
