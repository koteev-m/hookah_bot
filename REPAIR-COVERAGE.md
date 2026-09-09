# HT-RELEASE-REPAIR-01 coverage and remaining gates

Feature CI phase: **HT_RELEASE_REPAIR_VALIDATION_INCOMPLETE**. The preserved matrix below
is local evidence, not a claim that pending Linux checks passed. New baseline contract
is11 named artifacts + operation-log (12); real CLI15 binding tests pass locally.
F05 UNKNOWN daemon reconciliation and ordinary-deploy lock integration remain OPEN.
F09 target policy is accepted, with a separate restored-V125 handoff on schema125;
live application is unauthorized. Real Caddy, owned SSH/supervisor, PG17/clients17–18,
Docker/JVM migration/readiness and28 previously skipped PostgreSQL cases are wired to
the existing12-job feature CI. G01 operational DR/RPO/auth custody, G02 daemon/VM reboot,
G03/G04 real manual assertions and delivery ambiguity remain open after synthetic CI.


This updates the supplied audit map; unchanged findings are not silently promoted.
R = actual production consumer/tool in an owned fixture; M = explicit external dependency
mock; S = source review; U = required runtime proof unavailable. No row is live evidence.

| Procedure boundary | Current code/coverage | Remaining integrated gate |
| --- | --- | --- |
| Local baseline read → stage1 intent | R actual controller, bounded read attempt sealing/repeat and integrity; M Git/gh/remote commands. No intent after failed read. | U authorized real source-bound Actions after publication; not queried as release approval here. |
| Every remote stage/recovery | R actual source envelope, ACK schema/identity/log consumer and failure output preservation; S Linux supervisor/records/target binding. | U Linux own SSH before/during/after effect, child/session escape, process/daemon crash, reboot and competing recovery. |
| 1 baseline/DB authority | R psql/libpq on PG17, alternate DB/schema/role; M Docker/Compose/network; exact path labels and persistent semantic digest. | U actual container/source/future backend equality and temporal target drift. |
| 2 and7 backup/rehearsal | Existing exact backup/restore/inventory gates retained; R new PG17/schema/data and globals strategy; M legacy remote prerequisite seams. | U actual Docker source dump/load/restore with production image/compression/auth and consistent globals at chosen recovery point. |
| 3,4 Caddy activation/drain | R actual Bash status/hash/adapt/admin equality decisions; M privileged Caddy/systemctl and admin payload producers. | U real disk/runtime divergence, partial install/reload/transport loss and daemon confirmation. |
| 5,6 stopped/zero writers | Existing stopped/count/session/queue/Flyway contracts retained in full local legacy harness. | U actual Linux daemon quiescence and external writers; snapshot alone is not exclusion. |
| 8 final preflight | R complete runbook extractor, complete SQL, real PG17/psql17/18 and production structured outcome consumer. Before unsafe PASS reproduced; after rejected. | Historical PASS8 unchanged and unusable as count0 proof; new actual release requires new approved source/run. |
| 9,17 env transitions | R actual guards and Compose fixed-.env rendering, installation/postvalidation, inverse and unrelated-byte checks for both ordinary transitions and both recovery states. | U privileged Linux metadata/filesystem crash durability and complete stopped/create integration. |
| 10 image transfer | Existing exact image/archive and separate prepare/load gates retained, supervised remote actions and bounded transport. | U actual owned Docker save/load/disconnect/recreate; no new HT13 archive was built. |
| 11,18 first/final start | R one checked synthetic start + real owned HTTP readiness,503→200, delayed/hung/wrong/exit and final identity fence. M Docker/JVM. | U real Linux/JVM/Flyway/DB startup and daemon timeout outcome. |
| 12 schema/runtime | R SQL identity and retained schema/Flyway checks; R focused Kotlin consumers; M external service inventory. | U migration126 on actual whole stack, queue progress, outside poller conflicts and PostgreSQL Testcontainers cases. |
| 13,14 manual smoke | Existing source-bound gate/17-assertion attestation validator retained and exercised by legacy harness. | U all17 actual Guest/Owner/MIX assertions and real controlled delivery. Synthetic attestation is not smoke. |
| 15,16 re-drain/stop | Existing one-way/one-start/zero-backend/order boundaries retained, F05/06 wrapper applied. | U actual daemon/HTTP/TG write timing under crash/disconnect. |
| 19 ordinary Caddy restore | R real partial/receipted Bash consumers; errors and actual JSON comparison semantics; M Caddy/systemd. | U real runtime activation and crash. HTTP opens at successful reload, before marker cleanup. |
| 20 final public gates | Existing exact identity/schema/runtime proof retained; source-reviewed operational handoff added. | U actual public/manual worker gates. Accepted restart/image/config policy; live application unauthorized; stage20 is not reboot readiness. |
| pre-v126 recovery | R actual OFF preparation both starting states, nested caller failure propagation, one start/readiness and Caddy inverse fixtures. | U coordinated daemon quiescence, actual startup/active Caddy outcome. UNKNOWN prior operation blocks recovery. |
| post-v126 stop | Existing no-V125-start boundary retained and legacy-tested; remote operation receipt/unknown semantics added. | U actual stop outcome/crash/children/daemon confirmation. |
| full-DR prerequisite verifier | R same PG17 dump/list across timezone17/18 with exact hash and semantic refusal cases. No restore action added. | G01 operational DR and explicit recovery point/loss decision. |
| status/handoff | R9 cases: clean, intent, receipt corruption, repeated read attempts, incomplete/corrupt attempts, malformed intent, orphan terminal checksum and orphan recovery intent. | Historical availability remains a separate source/timestamp, never canonical execution PASS. |

## Gaps

| Gap | Disposition | Evidence and exact remaining need |
| --- | --- | --- |
| G01 | OPEN; synthetic strategy verified | Actual own PG17 whole-DB restore with globals/memberships/grantor/owners/ACL/default ACL/settings/sequence/extension/SCRAM synthetic authentication passes. Original no-owner/no-privileges path loses owner/grant as reproduced. Not a whole operational DR: secret custody/rotation, pg_hba/host auth, extensions/tablespaces/assets, crash/recreate and accepted recovery point/loss still need decisions and runtime exercise. |
| G02 | OPEN | Real Compose config rendering and own PG17 are available. Ordinary Ubuntu CI now authorized; actual Linux tests are pending. macOS diagnostic skips and required-entrypoint refusal remain preserved. Real systemd/daemon and VM crash/reboot proof remains separate even after owned-container/Caddy/SSH CI. |
| G03 | OPEN for integrated runtime |109 Kotlin tests execute/pass;28 PostgreSQL Testcontainers cases skip. Source and existing synthetic tests preserve admission semantics: OFF can resume Telegram/autonomous writes before public HTTP; denied Telegram offset advances; send→markSent ambiguity remains. No delivery/loss/replay policy changes. Need actual queue and write-boundary proof on isolated stack. |
| G04 | OPEN | Existing attestation validators remain required; configuration count1 and health do not establish progress/no409 conflict. Need bounded worker progress observation plus all17 real controlled manual assertions under separately authorized Gate B/C. Do not observe getUpdates concurrently or call synthetic Telegram live smoke. |

## Mocks and non-substitutions

- DB tests run actual full extracted SQL and psql/libpq/pg_restore. Docker/Compose/DNS,
  source authority, GNU-stat, Caddy and zero-writer prerequisites are explicit fixtures.
- Configuration tests run actual guards and actual Compose. Privileged install/Caddy/
  systemctl/HTTP-admin producers are explicit process spies with independent disk/runtime
  state. No tested consumer is replaced by success.
- Readiness uses real Bash/Python/curl/owned HTTP; Docker inventory/start and URL port mapping
  are fixtures. Only deadlines are shortened; production decisions are extracted unchanged.
- Local controller tests use executable Git/gh/SSH/Docker fixtures; no release publication.
  Stdin tests simulate the OS supervisor but run real envelope/dispatcher/ACK validator.
- Legacy scheduling adapters invoke existing command mocks and preserve their status;
  they do not claim real subreaper/daemon behavior. New Linux tests use actual supervisor
  and owned sshd when the required isolated platform exists.
- Kotlin tests are H2/in-process/synthetic provider where stated; skipped PostgreSQL tests
  remain missing coverage. Only an internal nullable test-transport override was added; product defaults and migration bytes are preserved.

Use [REPAIR-REPORT.md](REPAIR-REPORT.md) for command/exits, one review and next decisions.
`LIVE_ACTIONS=NONE`; `PUBLICATION=FEATURE_VALIDATION_ONLY`;
`STAGING_RELEASE_READINESS=NOT_ESTABLISHED`.
