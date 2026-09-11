# V125/V126 operational handoff — accepted target policy, application not authorized

HT-RELEASE-REPAIR-01 records the policy accepted in AUTHORIZE_REPAIR_FEATURE_CI_ONLY without applying it. Migration stages,
recovery and final automated gates retain `restart=no` and RestartCount=0. A completed
stage20 or restored HTTP availability does not transfer image/configuration authority
or promise service recovery after reboot.

The last supplied availability observation is historical: source
`/private/tmp/ht13-v125-recovery-exec-s22m246w/REPORT.md`, observed
2026-09-09T14:15:41.464269+00:00, V125 PRODUCT/OFF/public200, PostgreSQL125/noV126,
drain absent, restart=no. It is not a fresh probe or native stage9 PASS. The canonical
`v126-cutover-20260909t113822z-f7828e09` remains PASS1–8/intent9 without PASS9; its PASS8
cannot establish unsafe_count=0 because of F01.

The accepted policy has distinct V125 and V126 completion boundaries:

| Area | Accepted target and prerequisite | Application boundary |
| --- | --- | --- |
| Migration restart | `restart=no` throughout migration/recovery gates | Preserved; HTTP200 cannot switch it |
| V126 ordinary operation | `unless-stopped` only after completed release, required runtime/manual gates, integrity verification and separately approved applied handoff | No live moment authorized |
| Restored V125 operation | Separate handoff on verified schema125, exact V125 image/config and recovery integrity; V126 manual17 is not its prerequisite | Historical restored availability alone is not applied handoff proof |
| Permanent image selection | Fixed root-owned `.env` holds the exact accepted full-SHA `BACKEND_IMAGE`; verify exact image ID/source before recreate; no fallback, pull or build during recreate | No live `.env` edit authorized |
| Configuration ownership | Root:root protected configuration remains server authority; ordinary upload must not import workstation uid/gid | Guard requires root deployment identity and fixed protected environment/Compose; explicit installation sets root ownership |
| Target binding | Preserve lock inode and immutable history; explicit retirement of a known completed run requires terminal receipt and approved/applied handoff | UNKNOWN remains blocked; no reset/adoption/expiry |
| Recovery point/data loss | Operator selects a proven consistent point and accepts its loss boundary after whole-DB/globals/auth/settings review | DECISION_REQUIRED; no RPO or loss default |

Ordinary deploy uses the same persistent target supervisor as the sequencer. A
standalone read-only handoff guard still refuses an existing operation registry; only
the deploy worker holding that registry's lock calls its actual authority consumer.
The worker revalidates complete immutable history and the exact approved next-request
binding before any upload or mutation. No rsync, remote mkdir, load or recreate runs
outside that lock. An unbound legacy target is refused under the lock; there is no
bootstrap/adoption fallback.

The protected canonical descriptor schema is `DESCRIPTOR_FIELDS` in
`scripts/v126-ordinary-deploy.py`: exact next owner/source-bundle and target; V125/V126
version; full-SHA image/tag, image ID/source/platform; fixed env hash; before/after
Compose hashes; Caddy disk/active hashes; protected DB URI file/hash and semantic target
digest; root:root, unless-stopped; approved/applied handoff identity/time; explicit file
hashes; public URL/check policy. Credentials do not enter the descriptor. The next
owner and descriptor are named by an explicit immutable transfer, not by HTTP200.

`deploy-staging.sh` additionally requires `APPROVED_DEPLOYMENT_FILE` and a fresh
`DEPLOY_STATE_DIR`. Its existing exact local build/artifact gate remains. One supervised
source-bound transport carries the reviewed files and image; the fixed `.env` is absent
from the upload list. Actual protected root ownership, effective env/Compose, semantic
DB equality, Caddy disk/admin/systemd state and current accepted image are checked before
mutation and after bounded backend-only recreate (`--no-build --pull never --no-deps`).
PostgreSQL configuration, mounts/network identity and container are not recreated.
A dedicated immutable deployment proof/result is required before local success.

This contract supports recreation/deployment of the **already accepted image**. It
requires the pre-existing backend to match that image. It does not choose or apply a
new image/.env authority, or implement an old-image to new-image selection transition.
Such selection needs its own separately approved boundary. Migration `restart=no`
is rejected by ordinary deploy until the explicit approved/applied handoff has changed
only the authorized target. V125 uses its schema125 recovery completion; V126 requires
its complete release/manual/integrity boundary.

After success, explicit deployment retirement joins that proof to a protected applied
handoff and the next exact descriptor, then appends the next transfer. The next deploy
uses the same lock/inode/history. Missing, timed-out or nonzero results keep the target
blocked, including competing recovery. The separate reconciliation/retirement semantics
and commands are in [V126_REPAIR_OPERATION_PROTOCOL.md](V126_REPAIR_OPERATION_PROTOCOL.md).
Exact feature2866db99ae897817c7e9a745d19f3b487c5563b6/CI34484968850 verifies the owned
terminal/reconciliation/handoff/two-deployment lifecycle with real Docker/JVM/PG and
Caddy/systemd consumers. Earlier prerequisites and the approved handoff are synthetic;
ordinary public_checks=false and the owned direct transport are explicit fixture limits.
Separate SSH and local typed-chain regressions supply their own evidence. The private
Docker daemon interruption gate verifies five metadata/shared-supervisor cases, exact
UNKNOWN exclusion and cleanup. It starts no container; its earliest UNKNOWN retirement
guard is separate from the full handoff proof. Running-JVM daemon survival and complete
cutover E2E are unexercised workload scopes, not established infrastructure limitations.
Host reboot/power/storage remain residual operational gates. REPAIR-REPORT/COVERAGE retain
exact implementation results, first failures and final delivery acceptance binding.

An incident or reboot first requires read-only reconciliation of exact container/image,
actual database/schema, config disk/runtime, target operation records and queued/inflight
work. Unresolved remote operations block recovery as well as cutover. PID death, SSH exit
and uptime are insufficient. The operator then uses only the separately authorized
canonical recovery boundary: V125 startup is forbidden if V126 has appeared; full DR
verification never restores a database. A routine Compose recreate must not run while
its image/restart declaration disagrees with the accepted handoff.

HTTP and Telegram/write admission are separate. Starting OFF can resume polling,
inbound/outbox work and autonomous database writes before Caddy opens public HTTP.
Opening ordinary Caddy takes effect at reload, before marker cleanup. SMOKE-denied
Telegram updates consume their offset under the existing exclusion contract; this
package adds neither retention nor replay. Provider send followed by database markSent
has an acknowledgement/crash ambiguity; queue0 is not universal exactly-once delivery.
A configured unique poller and HTTP health do not prove worker progress. The next
runtime gate needs bounded successful polling/progress and conflict evidence plus the
17 real controlled Guest/Owner/MIX assertions. No concurrent getUpdates observer is
allowed as a replacement for the bot's poller.

HT-OPS-04 / AP-01 adds the source-bound Policy B evidence/readiness consumer in
`scripts/v126-dr-evidence.py`; policy and AP-00…AP-07 boundaries are canonical in
[DEPLOYMENT_RUNBOOK.md](DEPLOYMENT_RUNBOOK.md#policy-b-disaster-recovery--ht-ops-04--ap-01).
The future approved handoff must bind the same target, data/restore source-image-
migration-config epoch, independent custody versions and current ongoing readiness.
A new source/schema/image/config epoch requires its own compatible qualified point;
historical V125/Q bytes cannot be presented as actual post-V126 restore evidence.
R0 preparation and future exact-run/native-stage7 Q are separate barriers described
in [V126_STAGING_CUTOVER_CONTRACT.md](V126_STAGING_CUTOVER_CONTRACT.md).
AP-01 implements only local validation and synthetic tests: no applied handoff,
scheduler, monitor, fence, secret export, real restore or DR PASS. The earlier
feature-publication authorization paragraph below belongs to the repair history
and grants no publication authority to this AP-01 changeset.

The schema/data rehearsal remains narrower than operational DR. The new synthetic
whole-DB rehearsal checks globals/memberships, owners/ACL, database/role settings and
role authentication, but uses only synthetic secrets. Real secret custody/rotation,
pg_hba/host authentication, extensions/version inventory, tablespaces, external assets,
Docker/Caddy/systemd recreation and crash durability still require an isolated Linux
exercise and an accepted loss boundary. No automatic live restore was added.

Feature-only commit/push and ordinary Ubuntu CI are authorized for validation. Main
integration, PR, deployment and live handoff application remain unauthorized. After
feature validation, review the single diff and the remaining operation/DR/manual gates.
No staging attempt follows this document.
