# PostgreSQL V126 Staging Cutover Contract

Status: **HT-12R tracked pre-Gate-A prerequisite-sync candidate / exact main baseline verified /
local fixture validation, independent review and exact green feature-branch Actions required / no
staging access or cutover started**.

This document is the single policy and state-machine authority for the PostgreSQL V126 staging
cutover. Tracked executable authority is divided without overlap:

- `scripts/v126-staging-prerequisite-sync.sh` is the only pre-Gate-A prerequisite-sync command;
- `scripts/test-v126-staging-prerequisite-sync.sh` is its local-only static/fixture authority;
- `scripts/v126-cutover.sh` for run initialization, authorization, one-stage execution, status and
  bounded recovery;
- `scripts/test-v126-cutover.sh` for static and fixture validation of that command authority.

Other product, QA, deployment and migration documents may summarize this contract and link to those
files. They must not reproduce a second V126 command sequence. Neither this document nor the
presence of the scripts or successful prerequisite sync authorizes staging access, backup creation, Caddy mutation, backend
stop/start, maintenance activation, image transfer, Flyway/V126, manual smoke or recovery.

## HT-12R tracked prerequisite-sync boundary

HT-12R starts from exact main `a648e75179975c97daa4b3dae03070e6476d8a9a`, tree
`14f2400434c8546f5aba7c4bcc94fd20622625d1`, parent
`15a996575dcb863d7f4fe2f8110d3d56399fa354`, and successful main CI run `33658844231` with
`12/12` successful jobs. A different fresh `origin/main` stops as
`MAIN_BASE_DIVERGED_BEFORE_HT12R`.

The tracked prerequisite command owns one ordered path: exact self/release identity, release-object
selection, read-only V125/Flyway/Caddy/runtime baseline, restricted run evidence, exact rollback
capture, proof that the admission guard was absent, four atomic file synchronizations, 40 named
independently checkpointed post-sync checks, exact post-sync `125:1:0:0` Flyway proof and canonical
success. It may synchronize only the release Compose file, maintenance guard, create-only admission
guard and the three maintenance-`OFF` keys while preserving all other `.env` bytes. It never creates
a backup or rehearsal resource, changes Caddy, creates the drain marker, recreates/restarts/starts a
container, loads an image, enables `V126_SMOKE`, executes Flyway, writes product data or enters Gate
A. Every first failure is persisted before at most one rollback; a rollback restores exact bytes and
metadata, durably unlinks the create-only admission guard with parent-directory `fsync`, verifies
the complete V125 baseline and returns the original nonzero status. Rollback begins only after the
canonical first-failure record matches the run/source/original status, and it distinguishes proven
no-write, proven-write and indeterminate evidence instead of treating evidence errors as no writes.
The controller transports every remote argv as one shell-quoted SSH command, clears the remote
environment, applies a bounded command timeout and serializes post-allocation actions with one
restricted per-run lock. On SIGINT/SIGTERM it terminates and waits for the active local process
group; rollback cannot acquire the remote lock until any prior remote mutation has quiesced.

HT-13 R1-R5 temporary orchestration is rejected historical evidence and cannot be a runtime
dependency, receipt or authorization. No R6, temporary dispatcher or external dispatcher is
permitted. Caddy `2.6.2` and `v2.6.2` are safely parsed as the same exact semantic version with only
an optional lowercase `v`; different, substring, malformed and ambiguous output fails. The exact
active Caddyfile hash and remaining Caddy/runtime checks are not weakened.
Health/version response, read-only database, poller-conflict log, TLS-1.3 and UDP/443 probe
execution failures are fatal in both the prewrite baseline and their separately checkpointed
post-sync checks, even if a failing producer emitted exact-looking output first. TLS 1.3 is proved
unavailable only by an explicit server protocol-version rejection; a missing client capability or
other probe error is not evidence of the required baseline.

Successful prerequisite sync only makes the tracked release execution surface eligible for a later
separately authorized Gate-A baseline. After HT-12R main integration, the exact release SHA and V126
image tag/ID must be selected and proved anew; no rejected HT-13 package, authorization string or
old candidate image identity may be reused.

## HT-12P baseline and current stop

The exact required repository baseline is:

| Identity | Exact value |
| --- | --- |
| Main SHA | `ecb09601975678a41d89e5c824cc7812c7876481` |
| Main tree | `8c97996e317f0182b4871d2a2537a732d4830f64` |
| Parent 1 | `9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1` |
| Parent 2 | `d9c656b1c5feb757b79558209f130c08cba81cf5` |
| Main Actions | run `33536142005`, workflow `CI`, event `push`, branch `main`, attempt `1`, exact head SHA, `completed/success`, `11/11` jobs successful |

A fresh fetch must reproduce all values before HT-12P implementation proceeds. A mismatch stops as
`MAIN_BASE_DIVERGED_BEFORE_HT12P`.

HT-13 stopped before run-namespace allocation and before sealed-input creation with
`PREDEPLOY_CONTRACT_NOT_PROVABLE`. No HT-13 package was created and no staging mutation occurred.
That rejected attempt is not a reusable run, receipt, authorization or release artifact.

HT-12P may change only deployment-contract scripts, their bounded tests and the smallest canonical
documentation surface. It must not change backend production code, Mini App source, Compose runtime
semantics or PostgreSQL/H2 migrations. A genuinely required runtime or migration change stops as
`HT12P_RUNTIME_CHANGE_REQUIRED`.

## Immutable release boundaries

The migration identities must remain unchanged at the base, every HT-12P candidate and the later
integrated main release:

| Identity | Exact value |
| --- | --- |
| Complete migration tree | `765956602de896b4498a956753272a6bc2d2971e` |
| PostgreSQL migration tree | `bb2778e26e03e03211eab9f149777313f4a6f24b` |
| H2 migration tree | `07b5ba6ccf25e79c9cc419b9095bb664f2cfae18` |
| PostgreSQL V126 / H2 V127 blob | `6f39f7d33b1976d0f5eb7a70051bfc5351d12e56` |
| PostgreSQL V126 / H2 V127 SHA-256 | `ad11b2f95a6c73db226d3cd1ba53ac800a514c72d454b9255f379566195e08b5` |
| Flyway 11.19.0 V126 checksum | `1701638026` |

Migration-tree, blob or checksum divergence is a release stop. Partial restore, Flyway repair,
migration-history edits, cursor/read-marker edits, schema downgrade and ad-hoc domain-data repair are
prohibited.

Normal public-pilot staging remains:

```ini
TELEGRAM_TRAFFIC_POLICY=PRODUCT
TELEGRAM_ALLOWED_USER_IDS=
TELEGRAM_ALLOWED_CHAT_IDS=
STAGING_MAINTENANCE_MODE=OFF
STAGING_MAINTENANCE_ALLOWED_USER_IDS=
STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=
```

Permanent staging `ALLOWLIST`, stable CIDR/source-address authorization, Caddy access logging,
sidecar collectors, packet capture and patched Caddy remain rejected historical approaches. Caddy
is a transport/drain component, never an identity authority. Runtime `ALLOWLIST` compatibility and
its tests remain unchanged but are not a normal-staging, V126 or rollback profile.

The exact reviewed pre-V126 rollback identity remains:

- source `f577934691a1a7a79ba327c54e2055425142b7be`;
- image ID `sha256:6a8aed7c85374efd89aa2db2e3dbcbed6d84f63087a757ad077856b78bce24a8`;
- `PRODUCT`, maintenance `OFF`, and empty PRODUCT and maintenance lists.

## Trusted-operator and secret boundary

The sequencer is a fail-closed operator tool, not a defense against a malicious root operator or a
compromised local host, SSH endpoint, Docker daemon, Caddy process or PostgreSQL server. The trusted
operator must supply the reviewed release identity, exact image identities, SSH alias, staging path,
restricted remote database-target file and restricted maintenance-identity file.

Within that trust boundary the sequencer treats mutable files, shell environment, remote state,
receipts, checksums, image identities and evidence as untrusted until validated. It rejects symlinks,
unexpected schemas, identity mismatches, missing predecessors, stale or forged receipts, duplicate
execution, ambiguous interrupted stages and missing authorization. An authorization permits only the
bounded stages assigned to that gate; it is not permission to improvise commands.

Raw database URLs or credentials, Telegram identities, identity lists, bot tokens, JWTs, initData,
provider payloads and message bodies must not enter Git, ordinary command output, receipts, operation
logs, Actions logs or task comments. Restricted input files remain outside Git with operator-owned
permissions. Receipts contain hashes and bounded result metadata, not their sensitive contents.

## Immutable run-state contract

Every future cutover starts with `scripts/v126-cutover.sh init` and a new absolute state directory
that does not already exist. Initialization creates a mode-0700 directory with separate
`artifacts`, `authorizations`, `intents`, `receipts`, `recovery` and temporary namespaces. The
canonical mode-0400 `run.json` and its checksum bind at least:

- run ID;
- exact release SHA, tree and ordered parents;
- exact successful main Actions run;
- exact clean release worktree;
- sequencer SHA-256;
- staging SSH alias and project path;
- restricted database-target and maintenance-identity file paths;
- exact full-SHA V126 image tag and image ID;
- exact full-SHA V125 rollback tag.

The script identity may not change after initialization. A changed script requires a new run; it
must never reinterpret old receipts.

All release-baseline and release-object Git reads run through one sanitized Git boundary. It removes
every inherited `GIT_*` variable, disables replacement objects, fsmonitor, the untracked cache and
hooks, and uses `cat-file blob` for exact blob bytes. An inherited index/worktree/object directory,
alternate object store or replace ref therefore cannot redefine the claimed release, cleanliness,
migration identity, execution sources or marker-bounded preflight.

State 1 also establishes the release execution surface. The remote operator-owned
`docker-compose.yml`, `scripts/check-staging-maintenance-config.sh` and
`scripts/validate-staging-admission.sh` must byte-match the SHA-256 values computed from those exact
objects at `RELEASE_SHA`, with their required non-symlink modes and ownership. A V125 copy, mutable
checkout copy or locally edited helper is not acceptable. The restricted database-target and
maintenance-identity files are bound by both their manifest paths and their content SHA-256 values.
State 1 seals those five file-content bindings, plus the complete staging `.env` bytes and ordinary
Caddyfile bytes, in a strict remote baseline-authority proof and receipt. Every dependent remote
action revalidates the proof, current source/input files, modes, owners and the applicable exact
baseline, `V126_SMOKE` or maintenance-`OFF` environment hash before Compose or a database client may
run. The unique running baseline V125 container must also match all eight PRODUCT/OFF, empty-list
and long-polling values in the exact baseline `.env`; a stale container is a STOP. Preparing that
exact release execution surface is the separate tracked HT-12R prerequisite outside the sequencer.
The sequencer still fails closed if it is absent and never uploads, repairs or silently selects
another copy; successful prerequisite sync does not authorize Gate A.

Before a stage performs any remote or exposure-changing action, the sequencer writes a canonical
mode-0400 intent bound to the run, release, script, stage, exact predecessor receipt hash and exact
authorization receipt hash. An existing intent without a PASS receipt means the result is ambiguous:
automatic retry is forbidden and explicit reconciliation or a bounded recovery path is required.

Each successful stage writes a canonical mode-0400 receipt and checksum containing:

- format version, run ID, release SHA and sequencer SHA-256;
- exact stage and result category;
- predecessor stage and predecessor receipt SHA-256;
- authorization gate and authorization receipt SHA-256;
- intent SHA-256;
- completion timestamp;
- the exact stage-specific artifact names and SHA-256 values defined below, with no missing,
  duplicate or additional name.

File presence alone never proves success. The verifier replays the complete chain from the immutable
run manifest, checks canonical schemas and modes, verifies every checksum and binding, and rejects a
forged, stale, duplicated, reordered or mismatched receipt. Each stage's exact artifact set includes
one `operation-log` at its canonical path. The verifier requires that real file to be a
non-symlink, current-user-owned mode-0400 file, hashes its bytes and replays every strictly formed
`ARTIFACT` line. Those lines must contain exactly the other artifact names and hashes in the stage
contract; recomputing a receipt checksum after substituting, dropping or adding an artifact cannot
make it valid. Exactly one stage may execute per `stage` invocation. There is no multi-stage, retry,
fallback, build, deploy, restore or automatic authorization command.

Remote execution is not a second public executor. There is no public remote-helper subcommand.
For a receipt-gated stage or recovery only, the local sequencer streams a mode-0600 envelope and its
exact script body over `ssh ... bash -s` standard input. The envelope binds the run, release,
staging path, full sequencer SHA-256, operation kind/name, predecessor, authorization and intent;
the remote loader hashes the streamed body before sourcing it. The envelope also carries the exact
run-bound V126 image ID, baseline-authority hashes, any completed Caddy-stage artifact hashes and any
completed maintenance-transform proof hashes. Its internal dispatcher accepts only the exact
operation/action tuple assigned to the current stage or recovery. Arguments, environment flags or
direct invocation cannot select an unbound remote action.

## Exact 20-state machine

The following order is complete and immutable:

Every row requires exactly the listed artifact names plus exactly one `operation-log`; the receipt
verifier rejects any different set.

| # | State | Gate | Exact artifacts besides `operation-log` | Required outcome |
| --- | --- | --- | --- | --- |
| 1 | `BASELINE_VERIFIED` | None | `baseline-caddy`, `baseline-env`, `database-url-binding`, `local-baseline`, `main-actions`, `maintenance-identities`, `remote-admission-source`, `remote-compose-source`, `remote-maintenance-check-source`, `staging-baseline` | Exact final release/worktree/Actions/migrations, immutable V126 and V125 images, one global V125 long-polling backend and no V126 backend, ordinary public/loopback runtime, queues, Flyway V125, complete `.env` bytes, Caddy baseline, restricted-input content hashes and exact release execution surface are validated without mutation. |
| 2 | `PRE_DRAIN_BACKUP_REHEARSED` | A | `pre-drain-backup-dump`, `pre-drain-backup-inventory`, `pre-drain-backup-proof`, `pre-drain-backup-rehearsal`, `pre-drain-globals` | Fresh full custom-format backup, inventory, SHA-256, separate globals artifact and isolated same-version restore rehearsal pass. |
| 3 | `CADDY_CANDIDATE_INSTALLED_AND_RELOADED` | A | `caddy-activation`, `caddy-candidate`, `caddy-diff`, `caddy-original` | Complete candidate is derived from the active file, adds only the generic marker switch in the exact staging site, validates, installs, reloads once and is proved active while the marker is still absent. |
| 4 | `PUBLIC_DRAIN_ACTIVE` | A | `public-drain-active` | Marker creation is allowed only from state 3; public staging returns the exact generic `503`. |
| 5 | `V125_BACKEND_STOPPED` | A | `v125-backend-stopped` | Exact V125 backend is stopped while PostgreSQL remains ready and the public drain remains active. |
| 6 | `ZERO_WRITER_GATE_PASSED` | A | `zero-writer-v125` | Backend, client/unidentified/idle-in-transaction sessions, prepared transactions, replication slots, actionable queues and unexpected Flyway state are all zero/expected. |
| 7 | `QUIESCED_BACKUP_REHEARSED` | A | `quiesced-backup-dump`, `quiesced-backup-inventory`, `quiesced-backup-proof`, `quiesced-backup-rehearsal` | A second distinct full backup, inventory, hash and isolated restore rehearsal pass under the quiesced gate. |
| 8 | `FINAL_V125_PREFLIGHT_PASSED` | A | `final-v125-preflight`, `final-v125-preflight-source` | The marker-bounded booking-integrity preflight is extracted only from the immutable `RELEASE_SHA` Git object, runs unedited against the baseline-bound database target and returns safe-to-continue. |
| 9 | `V126_MAINTENANCE_CONFIG_PREPARED` | A | `maintenance-v126_smoke` | Only reviewed maintenance values change; underlying PRODUCT and unrelated environment bytes remain unchanged; active configuration validates without exposing identities. |
| 10 | `V126_IMAGE_TRANSFERRED_AND_VERIFIED` | A | `local-v126-image-archive`, `v126-image-archive`, `v126-image-transfer-ready`, `v126-image-transferred` | The already-built full-SHA image is locally verified, exported, checksummed, transferred, remotely loaded and matched by exact image ID; no backend starts. |
| 11 | `V126_BACKEND_STARTED` | A | `v126-backend-first-started` | Exactly one backend starts once from the verified image under `PRODUCT` plus `V126_SMOKE`, with public routing still drained. |
| 12 | `V126_SCHEMA_RUNTIME_GATE_PASSED` | A | `v126-schema-runtime` | Exact V126/checksum/schema, same-image backend, one poller, loopback health, queues, webhook and pending-update gates pass. Gate A stops here. |
| 13 | `MANUAL_SMOKE_AUTHORIZED` | B | `manual-smoke-handoff`, `manual-smoke-window` | A Gate-B authorization anchored to state 12 creates the sanitized handoff, removes only the already-loaded drain marker without a Caddyfile change or reload, and proves the public V126_SMOKE window plus protected unauthenticated generic-`503` boundary. |
| 14 | `MANUAL_SMOKE_PASSED` | B | `manual-smoke-evidence`, `manual-smoke-passed` | The exact 17-assertion canonical identity-gated evidence is supplied and hashed; the protected unauthenticated generic-`503` boundary is re-proved. Gate B stops here. |
| 15 | `PUBLIC_DRAIN_REACTIVATED` | C | `public-drain-reactivated` | Marker recreation requires state 14 and Gate C; the exact generic public `503` is re-proved. |
| 16 | `V126_BACKEND_STOPPED_FOR_OFF_TRANSITION` | C | `v126-off-transition-backend-stopped` | The V126 backend stops only after the second drain is active. |
| 17 | `MAINTENANCE_OFF_CONFIG_VERIFIED` | C | `maintenance-off` | Maintenance becomes explicit `OFF`, both maintenance lists are empty, the one-shot flag is absent, PRODUCT remains unchanged and guards pass. |
| 18 | `FINAL_V126_BACKEND_STARTED` | C | `v126-backend-final-started` | The same exact V126 image starts once; loopback runtime/schema/queue gates pass while public traffic remains drained. |
| 19 | `ORDINARY_CADDY_RESTORED` | C | `ordinary-caddy-restored` | Captured ordinary Caddyfile is restored byte-for-byte, validated and reloaded once; restoration requires states 17 and 18. |
| 20 | `FINAL_PUBLIC_GATES_PASSED` | C | `final-public-gates` | Automated public `/health`, `/db/health` and Mini App checks plus loopback runtime, schema, exact image/backend/poller, webhook, queue and Telegram-idle gates pass under PRODUCT/OFF. |

No state may infer success from the environment or skip an absent receipt. A later state always
requires the exact immediately preceding receipt, even when an operator believes an equivalent
manual action occurred.

## Authorization boundaries

The exact sequencer tokens are deliberately separate:

| Gate | Exact token | Receipt anchor | Permitted states |
| --- | --- | --- | --- |
| A | `AUTHORIZE_V126_CUTOVER_GATE_A` | `BASELINE_VERIFIED` | 2-12 only |
| B | `AUTHORIZE_V126_MANUAL_SMOKE_GATE_B` | `V126_SCHEMA_RUNTIME_GATE_PASSED` | 13-14 only |
| C | `AUTHORIZE_V126_OFF_TRANSITION_GATE_C` | `MANUAL_SMOKE_PASSED` | 15-20 only |

Gate A may perform the pre-drain backup through automated V126 verification. It must not open a
manual client window automatically. Gate B may authorize and record only the exact live smoke. It
cannot disable maintenance, recreate the drain or restore Caddy. Gate C cannot exist until the full
manual-smoke receipt verifies. Authorizations are hash-bound to the exact run, release, script and
anchor receipt and are not reusable across runs.

## Database target binding

Every host-side `psql`, `pg_dump`, `pg_restore`, `createdb` or `dropdb` invocation must bind an exact
target and fail before client launch if it is absent. The booking-integrity preflight therefore
requires `DATABASE_URL` with `${DATABASE_URL:?DATABASE_URL must bind the exact target}` before
`psql`. The sequencer parses the baseline-bound restricted URI into mode-0600 libpq service/pass
files and supplies only the nonsecret `service=v126_preflight` alias to the extracted wrapper; the
same read that derives those files must hash-match the baseline receipt. The raw URI is not placed
in a process argument or process environment. Commands inside the staging or
isolated PostgreSQL container must require explicit user and database variables or an equally
unambiguous maintenance database. Local socket, OS-user, default-database and inherited
`PGHOST`/`PGDATABASE` fallback are forbidden.

## Caddy activation and restoration

The only allowed candidate change is the secret-free file-presence switch for
`/etc/caddy/v126-drain.enabled` inside the exact `staging.hookahtootah.club` site. The sequencer must
perform the activation in this order:

1. capture the active ordinary file and its SHA-256;
2. require the captured bytes to hash-match the baseline receipt, then derive and validate the
   complete candidate, proving no unrelated byte changed;
3. install the complete candidate;
4. perform exactly one activation reload;
5. prove Caddy is active with the candidate while the marker remains absent;
6. write the state-3 receipt;
7. only then create the marker in state 4 and verify the generic public `503`.

Marker removal for manual smoke requires state 12 and state-13 Gate-B authorization. Marker
recreation requires state 14 and Gate C. Ordinary byte-preserving restoration requires maintenance
OFF and the final V126 loopback receipt. The complete cutover budget is one activation reload and one
restoration reload. No IP matcher, logger, collector, sidecar, packet capture or patched binary is
permitted.

## Immutable no-build image path

The cutover sequencer never invokes `docker build`, `docker buildx build`, Compose build or the
ordinary `scripts/deploy-staging.sh` build/upload/start path. Deterministic two-build proof is a
separate final-release selection prerequisite after later HT-12P main integration; it produces the
already-built full-SHA tag and expected image ID consumed by run initialization.

Every sequencer Compose invocation specifies the one receipt-bound `docker-compose.yml` explicitly;
default discovery and automatic override merging are not part of the execution surface.

State 10 verifies the local tag and ID, exports the image and captures it into a mode-0400 unlinked
snapshot. The same read-only file descriptor is parsed without extraction, hashed and transferred;
there is no later pathname reopen. The archive must contain only safe regular/directory members, one
exact image/tag association, a config member whose name and actual SHA-256 both equal the expected
image ID, and a unique regular layer inventory. A local mismatch therefore stops before the first
remote action. The remote side captures the uploaded file into its own mode-0400 unlinked snapshot,
re-parses and hashes that exact descriptor, and feeds the same rewound descriptor to `docker load`.
The separately retained run archive is rehashed before and after load. A transfer-byte mismatch stops
before Docker mutation, while loaded image ID and Compose resolution must still match exactly. The
portable transfer uses the host's Bash-3.2-compatible fixed descriptor and supported rsync mode
syntax. Compose verification reads the rendered JSON and requires the `backend` service itself, not
merely any service or aggregate image list, to resolve to that exact tag. State 11 is a separate
authorization- and predecessor-gated startup. It uses create-only `--no-build`, forces restart
policy `no`, proves `RestartCount=0`, and issues exactly one explicit `docker start`. Before either
start, the exact bound `.env` is rehashed after create and the stopped container must expose the
eight exact PRODUCT/poller/phase-specific maintenance values; state 18 repeats the same one-start
contract for the OFF transition. Runtime gates require exactly one Compose
backend total and running, the exact V126 image ID, `TELEGRAM_BOT_ENABLED=true`,
`TELEGRAM_BOT_MODE=long_polling`, zero live V125 containers globally and zero live old-image
backend in the staging Compose project. Every later runtime gate re-identifies that live container
and rebinds the same eight values to the exact applicable `V126_SMOKE` or `OFF` `.env` before any
public-window or final proof. A runtime/configuration mismatch stops before backend startup. No
implicit build, mutable staging tag, restart policy, hidden retry or fallback is allowed.

## Manual smoke evidence

Gate B uses only restricted identities prepared outside Git. The sanitized evidence file supplied
to state 14 must contain no raw identity, JWT, initData, credential or message body. It is canonical
JSON bound to the run and release and contains exactly these 17 assertion keys, each equal to
`PASS`, with no other assertion or top-level field:

1. `MATRIX_GUEST`
2. `MATRIX_OWNER`
3. `MATRIX_MIX`
4. `MATRIX_MIX_STAFF_CHAT`
5. `TENANT_RBAC_NEGATIVES`
6. `LINKED_STAFF_CHAT_DELIVERY`
7. `NULL_AUTHOR_UNREAD_CREATE_CLEAR_RESURRECT`
8. `WRONG_SURFACE_MARKERS_UNCHANGED`
9. `EXACT_SURFACE_ONLY_CLEAR`
10. `SUPPORT_CONVERSATIONS_SEPARATION`
11. `LABEL_COLLISION`
12. `LIVE_ONE_GUEST_REPLY`
13. `LIVE_ONE_PERSISTED_GUEST_MESSAGE`
14. `LIVE_EXACTLY_ONE_TELEGRAM_OUTBOX_DELIVERY`
15. `LIVE_OWNER_EXACT_THREAD_UNREAD_CREATED_AND_CLEARED`
16. `LIVE_NO_DUPLICATE_OR_RESURRECTED_MARKER`
17. `LIVE_NO_OTHER_THREAD_CLIENT_OR_NON_MIX_MUTATION`

Assertions 12-17 are the non-substitutable
`HT14_MANDATORY_LIVE_GATE_GUEST_REPLY_OWNER_UNREAD_CLEAR`: one exact Guest reply, one persisted
Guest message, exactly one expected Telegram/outbox delivery, exact-thread-only Owner unread
creation and clear, no duplicate marker or unread resurrection, and no other-thread, CLIENT or
non-MIX mutation. Automated evidence never substitutes for this live result.

While the public health and Mini App window is open, both state 13 and state 14 also call the
protected endpoint `/api/guest/_ping` without credentials and require HTTP `503` with only the
generic application error (`SERVICE_UNAVAILABLE`, `Service unavailable`, no detail; an opaque
request ID may be present). A `2xx`, auth-specific denial, leaked detail or unexpected JSON field
closes Gate B.

## Bounded recovery and terminal stops

Recovery commands are explicit branches, not automatic fallbacks. They create their own immutable
intent/evidence and never rewrite the normal receipt chain. Before a recovery action begins, the
sequencer records its recovery intent and terminal run marker before any remote action. Any recorded recovery
intent makes that run permanently terminal, including when the remote action fails or its result is
ambiguous; normal authorization/stage execution and recovery retry are forbidden afterward. The
only one-way escalation from a completed recovery is a separately authorized full-DR prerequisite
verification anchored to an exact successful post-V126-stop recovery receipt. It still cannot
resume the normal chain and stops before restore authorization.

The exclusive state lock records the owner PID plus pending/launched child records for the stage
worker, SSH and rsync processes. Signal handlers terminate tracked children, reconcile the lock and
exit with the signal-specific status; they do not release the lock and continue. Ordinary stage
commands never remove or take over the lock. Only a recovery command may take over a dead lock, and
only after validating the exact surface, proving the owner and every tracked child dead and rejecting
any ambiguous pending launch. Concurrent recovery uses an atomic hard-link compare-and-set takeover
marker inside the existing lock directory, then rechecks owner/children and atomically replaces the
owner PID without any lock-directory disappearance window. A live, malformed or unprovable lock is
a STOP. Dead-lock takeover does not resume the normal chain; it may enter only the one explicitly
authorized recovery branch.

Secret-bearing Telegram and preflight files, environment-transform temporaries and Caddy admin
snapshots bind their concrete cleanup targets into one-shot `EXIT`/`HUP`/`INT`/`TERM` handlers
before use. Each handler clears the cleanup traps before acting, preserves the original exit status
or the exact signal status and reports a cleanup failure without masking the primary failure.
Restore-rehearsal container and volume cleanup is pre-armed with exact names and a run-specific
ownership label. The sequencer proves that label before mount/use or deletion, removes the container
before the volume and refuses to delete a wrong-owner resource.

### Pre-V126 runtime rollback

Exact token: `AUTHORIZE_V126_PRE_V126_ROLLBACK`.

Before any Caddy or backend mutation, the command classifies live Flyway as exact head V125, V126
absent and no failed history row; every other classification refuses. It can recover a partial Caddy
activation by binding the original to the baseline receipt, proving the candidate is its exact
deterministic transform and, when state 3 completed, matching every Caddy artifact to that immutable
receipt. It accepts only the sealed original or candidate as active, finishes the candidate
reload/marker drain if needed, and then stops only the scoped Compose candidate. An unreceipted
stage-9 environment move is accepted only when its exact inverse reconstructs the baseline bytes.
It selects only the exact reviewed V125 tag and image ID, refuses rather than stopping any matching
container outside the project, and deterministically restores `PRODUCT` with maintenance `OFF` and
all lists empty. The exact resulting `.env` hash is rechecked immediately before and after create;
the stopped container must itself expose those six policy/list values plus enabled long-polling
configuration before it can start. Recovery then uses create-only/no-build plus restart `no`,
`RestartCount=0` and one explicit start, and verifies compatible loopback
runtime/schema/queue/admission plus unique global V125/zero global V126 before restoring the original
Caddyfile and ordinary routing. It
refuses once V126 is present. It neither restores data nor undoes product facts.

### Post-V126 forward-fix stop

Exact token: `AUTHORIZE_V126_POST_V126_FORWARD_FIX_STOP`.

Before any Caddy or backend mutation, the command requires exact successful V126 Flyway identity.
It keeps or restores the public drain, observes every running Compose backend and then stops the
backend unconditionally. Its terminal proof classifies the observation as exactly one expected V126
(`EXACT_V126_STOPPED`), none already running (`NO_BACKEND_ALREADY_STOPPED`), an unexpected V125
(`V125_REFUSED_AND_STOPPED`) or any unknown/multiple image set
(`UNKNOWN_REFUSED_AND_STOPPED`). An unreceipted state-17 environment move is accepted only when its
exact inverse reconstructs the immutable state-9 bytes. Every classification must end with zero
scoped backend, global V125, global run-bound V126, writer/session, prepared-transaction and
replication-slot state. V125 and unknown images inside the scoped Compose backend are refused and
stopped; a matching outside-project container causes a terminal fail-closed result and is never
stopped host-wide. The command explicitly rejects any V125 or forward-fix startup and records that
only a separately reviewed V126-compatible forward fix may continue. It modifies no migration
history, cursor/read marker or domain data.

### Full-DR prerequisite verification

Exact token: `AUTHORIZE_V126_FULL_DR_PREREQUISITE_VERIFICATION`.

The verifier requires one exact preserved backup phase, validates dump hash and inventory, requires
zero backend/writers/client or unidentified sessions/idle-in-transaction sessions/prepared
transactions/replication slots, and hashes an operator-supplied record of the accepted recovery point
and data-loss boundary. It stops at a separate DR authorization requirement. It contains no restore,
`pg_restore --dbname` against a recovery target, Flyway repair or automatic fallback path. Full DR,
if later authorized, restores the whole consistent database only; partial table/row/schema merge is
never permitted.

This verifier may be selected directly from a nonterminal run only when its live public-drain and
zero-backend/writer/session gates already pass and the chosen backup receipt verifies. From a
terminal run it is allowed only as the one-way escalation anchored to an exact successful
post-V126-stop recovery receipt.

Failure to prove any of these three boundaries returns `RECOVERY_BOUNDARY_NOT_PROVABLE` for HT-12P.

## G0-G9 release view

The legacy G0-G9 view remains a release-summary projection, not a second executor:

| Gate | Receipt/state projection before cutover |
| --- | --- |
| G0 | Exact environment/operator boundary; repeated by state 1. |
| G1 | HT-12M V125 prerequisite and exact rollback identity. |
| G2 | Final post-integration release SHA/tree/parents/Actions and migration identity; state 1. |
| G3 | Restricted manual clients/identities ready outside Git; state 1 and Gate B handoff. |
| G4 | States 2 and 7 backup/rehearsal receipts. |
| G5 | Deterministic prebuilt image identity consumed by states 1 and 10. |
| G6 | States 3-8 Caddy/drain/zero-writer/preflight chain. |
| G7 | States 9-12 V126 maintenance/start/schema/runtime chain. |
| G8 | States 13-14 manual-smoke chain. |
| G9 | States 15-20 OFF transition, Caddy restoration and final public chain. |

During HT-12P, G4 and G6-G9 remain execution-required. No documentation, local fixture or branch
Actions result marks a staging execution gate passed.

## Validation and independent review

`scripts/test-v126-cutover.sh` must prove stage implementation completeness, unique extractable
markers, shell syntax, required bindings, fail-closed missing inputs, exact predecessor enforcement,
exact per-state artifact inventories and real operation-log replay, rechecksummed forged/stale
receipt rejection, Gate A/B/C separation, no public remote helper and strict internal-envelope
dispatch, atomic lock/signal/orphan-child behavior, exact release-bound Compose/guard and restricted
input content hashes, complete `.env` and ordinary Caddy baseline anchors, immutable maintenance/
Caddy receipt revalidation, and the only exact inverse-reconstructable partial transitions at their
bounded recovery predecessors. It must also prove sanitized immutable Git reads despite inherited
Git path/object/index variables and replacement refs; backend-specific image resolution; no-build
transfer; safe archive inventory; exact unlinked-FD parse/hash/upload/load identity; local archive
rejection before the first remote action; remote mismatch rejection before Docker load; actual
Bash 3.2/openrsync portability; transfer/start separation; restart-disabled single-start runtime;
one long-polling backend;
strict failure propagation from every Compose/Docker inventory, global V125 zero and staging-project
old-image zero, all 17 manual assertions, the protected unauthenticated generic `503`, Flyway-before-
Caddy recovery ordering, scoped-stop/outside-project refusal, partial activation recovery, the
stage-17 OFF/no-receipt post-stop-to-full-DR chain, safe-stop classification, DR no-restore, privacy
and immutable migration identities. Current maintenance/admission guards, PRODUCT/OFF and V126_SMOKE
Compose fixtures, affected deploy self-tests, `git diff --check` and clean-worktree verification
remain required. Existing CI floors must not be reduced.

After the final diff and local tests, one independent read-only release/security reviewer must inspect
ordering, authorizations, database binding, no-build transfer, Caddy safety, all recovery branches,
secret/identity leakage and unchanged runtime/migrations. Any P0, P1 or blocking P2 prevents
commit/push.

## HT-12P integration gate

After complete local PASS, independent review PASS, a normal non-force feature-branch push and an
exact successful branch Actions run with every expected job successful, stop at:

`HT12P_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`

The exact later authorization text is:

```text
AUTHORIZE_HT12P_MAIN_INTEGRATION
base=ecb09601975678a41d89e5c824cc7812c7876481
candidate=<exact reviewed feature-branch SHA>
candidate_tree=<exact reviewed feature-branch tree>
method=non-force-no-ff-merge
```

No shorter or implicit authorization substitutes for that text. It authorizes only reviewed main
integration and post-integration release-identity proof. It does not authorize staging or cutover.

After later authorized integration, require a new exact green main Actions run, select the resulting
final V126 release SHA, repeat deterministic two-build image proof and return
`V126_EXECUTABLE_CUTOVER_CONTRACT_CLOSED`. Exactly one next task then exists: HT-13 must restart from
fresh read-only reconciliation and must not reuse its rejected preparation attempt.

## HT-12P terminal verdicts

Return exactly one at the applicable root-task boundary:

1. `HT12P_MAIN_INTEGRATION_AUTHORIZATION_REQUIRED`
2. `V126_EXECUTABLE_CUTOVER_CONTRACT_CLOSED`
3. `HT12P_RUNTIME_CHANGE_REQUIRED`
4. `MAIN_BASE_DIVERGED_BEFORE_HT12P`
5. `EXECUTION_SEQUENCE_NOT_PROVABLE`
6. `RECOVERY_BOUNDARY_NOT_PROVABLE`
7. `SECURITY_BLOCKER`
