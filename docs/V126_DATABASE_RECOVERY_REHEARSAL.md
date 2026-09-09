# V126 database equality and whole-database recovery rehearsal

This is a repair design and isolated regression contract, not a live restore command or
cutover authorization. `V126_STAGING_CUTOVER_CONTRACT.md` remains the state-machine authority.
The native stages 2/7 restore schema/data with `--no-owner --no-privileges`; that evidence
never establishes operational disaster recovery. `verify-full-dr` remains prerequisite-only.
Historical receipts and the original V126 migration bytes are unchanged.

## Database target proof

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
