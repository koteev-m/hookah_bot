# V126 database equality and whole-database recovery rehearsal

This is a repair design and isolated regression contract, not a live restore command or
cutover authorization. `V126_STAGING_CUTOVER_CONTRACT.md` remains the state-machine authority.
The native stages 2/7 restore schema/data with `--no-owner --no-privileges`; that evidence
never establishes operational disaster recovery. `verify-full-dr` remains prerequisite-only.
Historical receipts and the original V126 migration bytes are unchanged.

## Database target proof

### Policy B v1 local contract (HT-OPS-04 / AP-01)

The accepted policy/authority map is in
[DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md#policy-b-disaster-recovery--ht-ops-04--ap-01).
The original rehearsal/repair evidence below is historical and is not upgraded.
AP-01 adds local validators and synthetic regression only; no operational D/E,
real-auth PASS or current staging recovery point exists as a result of this patch.

`v126-dr-schema.py` declares exact fields for target, custody, binding,
authorization, intent, manifest, result, offhost, restore, functional, qualification,
event, ledger, response, ongoing and readiness records. `data_runtime` and `restore_runtime`
each bind source SHA/tree, app image, PG image, migration tree and config epoch.
They must be identical in v1: implicit migration, downgrade and compatibility
overrides are unsupported. A different-source compatibility path needs a new
reviewed schema/recipe. The release-contract source is bound separately.

The bundle manifest has exactly these members, all nonempty with exact hash/size:

- `application.dump`: complete custom-format DB dump, no partial table/schema filters;
- `globals.sql`: fresh `pg_dumpall --globals-only --no-role-passwords -l <database>`
  from the same attempt; Q cannot reuse pre-drain globals;
- `toc.list`: actual complete `pg_restore --list`, with existing strict canonical TOC checks;
- `roles.json`, `owners.json`, `acl.json`, `settings.json`, `extensions.json`,
  `sequences.json`, `flyway.json`, `queues.json`, `data-schema.json`,
  `safe-config.json`, `authority-refs.json`, `tablespaces.json`.

Inventory JSON is a closed digest/count envelope: schema_version, exact category,
snapshot_token_sha256, semantic_sha256 and count. It records no arbitrary values
or per-secret hashes. Semantic digest input is the complete canonical qualified-name
inventory; DB/globals contain the actual recoverable catalog facts. Protected
config/image/secret material stays in independent custody, with exact version refs
in the binding. These envelopes alone do not prove completeness: the future
approved producer must enumerate the whole DB and retain its independently
verified snapshot-consumer proof. Partial inventories cannot be declared complete
by hand. Table payloads/catalog details used for comparison remain confidential.

For online R0/periodic capture, the future producer must observe bounded source
time before opening the exporter, hold REPEATABLE READ READ ONLY and export one
snapshot; all pg_dump/data/inventory consumers import it before exporter exit.
Freeze administrative changes while collecting fresh globals/config. For native
Q, bind its zero-writer interval and own fresh global/config captures. Lost
exporter, nonzero consumer, unapplied snapshot or global/config drift fails the
attempt. AP-01 validates these predecessor/provenance contracts; it does not start
a database exporter or replace the existing operation supervisor/backup executor.

`validate_directory` reads through directory descriptors with O_NOFOLLOW and
O_NONBLOCK, checks regular-file/single-link/size/stability and an exact allowlist.
The bounded plain USTAR contract rejects traversal, links, devices/FIFOs, duplicate
or unexpected members, GNU/PAX extensions, zero/truncated content, trailing garbage
and member hash/size drift. No archive is extracted to disk by the validator.
Large/other archive formats need a reviewed streaming adapter, not a silent fallback.
PGDMP/TOC checks are format prechecks; an actual checked pg_restore is still mandatory.

Future D/E acceptance, in order:

1. Retrieve the exact encrypted object VersionId from the independent target;
   verify cipher size/hash, decrypt with independently recovered authority and
   verify the complete archive/member inventory. Restore has no fallback to the
   producer cache. Pin the download receipt and backend tool bytes.
2. Prove a separately owned empty Linux/amd64 target and egress isolation before
   secret hydration: no staging/Telegram/provider/public connectivity, public
   ports, host Docker socket or reused product volumes. Approved internal PG/backend
   traffic is the only exception. Pin negative connectivity and empty-target proofs.
3. Match the source PG image, extension binaries, tablespaces, encoding/locale and
   config epoch. `bootstrap_globals` supports one reviewed simple unquoted role
   name, only maintenance/template databases and exactly that non-system role.
   It omits exactly one CREATE ROLE line from an ephemeral stream and records
   original/transformed/omitted-statement hashes. Original bytes stay intact.
   Complex names, any other conflict or password-bearing globals refuse this recipe.
   Unknown custom role GUCs also refuse the ordinary stream: they may contain
   secrets despite `--no-role-passwords` and need protected custody/recipe review.
4. Apply globals with psql ON_ERROR_STOP, then complete
   `pg_restore --exit-on-error --create --dbname=postgres`; no blanket no-owner,
   no-privileges, selective restore, overwrite, Flyway repair or ignored SQL error.
5. Compare all semantic vectors before any smoke writes: roles/memberships with
   grantors/ADMIN/INHERIT/SET, DB/schema/object owners/ACL/default ACL/effective grants,
   settings/auth config, complete schema/data, full Flyway history, sequence
   definitions/value/is_called/dependencies/allocation and all durable queues
   including PROCESSING/SENDING/retry/lease/idempotency facts. New cluster OIDs
   are not expected to equal source OIDs. Q sequences require stable equality;
   online sequences need archive SET consistency and safe next allocation proof.
6. Real-auth is a separate future path: independent current secret retrieval,
   actual intended app credentials over the backend TCP/JDBC route, wrong-password
   denial, parsed HBA/ident and approved address translation. No trust-auth shortcut.
7. Start only the matching app image with no pending migrations and a proved
   egress-disabled overlay: staging/PRODUCT/V126_SMOKE, bot/AI/geodata/expiry/reminders
   disabled, fake billing, restart=no and restricted synthetic test identities.
   Recheck source flags and exact derivation; network isolation remains mandatory.
8. Functional proof requires every named check: DB_AUTH, CUSTODY_REHYDRATION,
   SAFE_BACKEND, HEALTH, MINIAPP_AUTH, JWT_RBAC, STATE_PRESERVATION, CLEANUP.
   Health/db-health/version/static must match; synthetic signed Mini App login and
   JWT positive/negative/expired cases, maintenance denial and nonmember venue/
   platform denial must use current route contracts. No real Telegram/provider
   call. Preserve pre-smoke catalogs/history and permit only test-owned deltas.
9. Stop own backend/PG, remove ephemeral secret/cache material and prove cleanup
   (or separately authorized sealed retention) before qualification. A cleanup
   failure cannot emit PASS. Operational secret/custody checks may not be labelled
   synthetic. `SYNTHETIC_POINT_PASS` is explicitly ineligible for operational DR.

AP-01 local evidence: the existing whole-DB test now shares the strict bootstrap
transform and seven semantic vectors. The optional Docker regression uses an
already installed exact PG17 image, two labelled network-none/tmpfs containers,
fake versioned download, complete restore and synthetic SCRAM positive/negative
checks. It injects owner, Flyway and durable-queue corruption to test rejection.
Its PostgreSQL platform may be the developer machine's architecture; it does not
claim Linux/amd64, backend/auth API smoke, real encryption/custody or live restore.

The sequencer must compare independently queried cluster system identifier and postmaster
lifetime, database name/OID, effective schema name/OID/search path and current/session role
name/OID. The same SQL runs against the selected Compose PostgreSQL container and the
restricted explicit URI. Queries are read-only with statement/lock bounds; a required catalog
permission failure refuses the proof and does not grant access. The resulting digest is
privacy-safe evidence of equal actual results, not a hash of a connection string.

Both selected containers must bind `com.docker.compose.project.working_dir` to the current
canonical directory and `com.docker.compose.project.config_files` to exactly its sole
`docker-compose.yml`. A matching Compose project name is insufficient: two different target
directories with the same basename can select the same containers while acquiring different
path-scoped locks. Different, missing, aliased or additional config-source labels refuse the
target proof before native database consumers or subsequent mutations.

The source container's POSTGRES database/user/password must equal both the actual retained
backend environment and future rendered Compose DB_JDBC_URL/DB_USER/DB_PASSWORD. The supported
JDBC target is the existing fixed `postgres:5432` service and explicit database, with no
additional JDBC schema/options. Nonempty source libpq overrides are rejected so backup and
schema clients cannot silently use another host, role or search path. Credentials and inspect
payloads remain in memory and do not appear in argv, normal logs or proof artifacts.

For a running backend, its DNS result must resolve exclusively to the selected PostgreSQL
endpoint on the exact shared network. Before start, a stopped or newly created container may
have empty endpoint metadata: validate its HostConfig network plan against the rendered
Compose network and exact project-owned network identity, with only the selected source/backend
members. This proves the future network plan; it is not an observation of a stopped process's
DNS or connection. Repeat actual backend DNS/config proof after readiness. Unavailable DNS,
ambiguous endpoints, unsupported network topology or changed observations refuse the gate.
The isolated tests use real PostgreSQL connections but synthetic Docker/Compose/network replies;
a real isolated Linux/Docker run is still required before claiming that runtime boundary.

## Whole-database strategy to review before accepting full DR

1. Select and explicitly approve a whole-database recovery point and acceptable data-loss
   boundary. Neither a pre-drain nor a quiesced backup implicitly authorizes losing later
   writes. Record when Telegram/workers and HTTP writes resumed separately.
2. Retain exact custom dump bytes, inventory and SHA-256. Capture roles, attributes,
   memberships and settings without password hashes, plus database/schema/object owners,
   ACL/default ACL, database/per-role settings, tablespaces, extension inventory/versions
   and any external extension/filesystem dependencies. Prove consistency of global metadata
   with the selected dump while writers and administrative mutations are excluded. Existing
   pre-drain-only globals do not establish quiesced global consistency.
3. Provision a separately authorized empty, isolated cluster on the required PostgreSQL major
   with compatible extension binaries, collation and locale. Reject unexpected roles/databases
   or tablespace conflicts; no partial merge and no automatic destructive cleanup.
4. Resolve the bootstrap-role conflict explicitly. The synthetic test uses the same bootstrap
   role as the source and first proves that the empty target has only that non-system role.
   It omits exactly that one `CREATE ROLE` statement from an ephemeral copy of the globals
   stream, then executes every retained ALTER/GRANT as the source bootstrap role. Original
   globals bytes remain unchanged. Using a different superuser as grantor is not equivalent
   on PG17: role membership `GRANTED BY` restoration can fail. Production bootstrap identity
   and any existing-role conflict need a reviewed decision, not blanket error suppression.
5. Apply the complete globals stream under the chosen conflict policy, then restore the entire
   selected database with `pg_restore --exit-on-error --create`. Preserve owners and privileges;
   do not use `--no-owner`, `--no-privileges`, selective tables or Flyway-history edits for DR.
6. Supply authentication through a separately approved secret source; password-free globals are
   intentionally insufficient. The regression supplies synthetic SCRAM passwords independently.
   Production password rotation, auth policy, filesystem keys and operator ownership remain
   explicit external decisions. No credentials are stored in repository artifacts.
7. Compare database/role/schema/object ownership, role memberships, object and default ACL,
   settings, extension versions, sequences and data invariants. Authenticate as intended
   application roles, verify expected reads/writes and denied permissions, reject a wrong
   password, and run separately authorized functional recovery checks before reopening writers.
8. Reconcile permanent image/config authority, ownership and restart policy through the separate
   operational handoff. During migration gates, restart remains `no`. No restart or live restore
   action is added by this document.

## Isolated regression evidence and limits

`python3 scripts/test-v126-database-evidence.py` requires actual PostgreSQL17 server/tools and
psql/pg_restore17 and 18. `PG17_BIN` and `PG18_BIN` select binary directories, not endpoints.
It creates only own temporary Unix-socket clusters with TCP disabled and synthetic data;
cleanup stops those exact clusters and removes only their data/socket directories. `--unit`
is a narrower helper check and does not replace the mandatory full suite in CI.

The complete extracted runbook SQL and actual remote preflight wrapper reproduce unsafe exit0
and false PASS on exact base `f7828e09863d391e1f714cc65c9c866f814cf6bf`, then require nonzero/no
proof after repair for both clients. Safe fixtures require the structured safe/count0 outcome.
Actual consumer exit failure or missing output cannot be replaced with PASS. The preflight's
remote authority, target-binding, Caddy, transport, zero-writer prerequisites and GNU-stat adapter are synthetic;
its SQL, psql decision, structured result consumer and proof creation are real. Two explicit
negative psql shims test a real successful client followed by exit73, and an exit0 client
that consumes input without producing a result; neither shim is used for safe/unsafe
positive or before evidence. A test-only cleanup observer copies synthetic output before
its normal deletion so the before unsafe count and after safe contract remain inspectable.

TOC tests retain the exact dump and inventory hash checks and execute actual pg_restore17/18
under UTC and Asia/Tokyo. Only a strictly parsed archive-created display line is canonicalized;
all other inventory bytes, including producer versions and ordered TOC records, remain exact.
Modified dump bytes, entry names or meaningful headers are rejected. Production full-DR
consumer tests use real dump/list/file/hash/proof consumers with synthetic authority and
zero-writer prerequisites; they still perform no live restore.

The whole-database test proves PG17 restoration of its complete synthetic fixture with roles,
GRANTED BY memberships, database/schema/table owners, grants/default ACL, role/database settings,
sequence state, extension inventory and separate successful/failed SCRAM authentication.
The old schema/data-only restore demonstrably changes the owner and loses the reader grant.
This is an executable strategy demonstration, not proof for the current production data,
external secrets, locale, extensions, Linux/systemd, reboot, RPO or the 17 real manual assertions.
G01 remains open until those operational choices and source-bound recovery evidence are accepted.
