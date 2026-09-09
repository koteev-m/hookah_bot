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
| Configuration ownership | Root:root protected configuration remains server authority; ordinary upload must not import workstation uid/gid | Guard requires root deployment identity and fixed protected environment/Compose; rsync disables owner/group import |
| Target binding | Preserve lock inode and immutable history; explicit retirement of a known completed run requires terminal receipt and approved/applied handoff | UNKNOWN remains blocked; no reset/adoption/expiry |
| Recovery point/data loss | Operator selects a proven consistent point and accepts its loss boundary after whole-DB/globals/auth/settings review | DECISION_REQUIRED; no RPO or loss default |

Ordinary deploy now refuses every target containing `.v126-target-operations`, including
retired history, before rsync or remote mutation. It checks fixed `.env` image equality,
root ownership and actual effective Compose image/restart policy. Uploads use root
identity with `--no-owner --no-group`; `.env` is absent from their explicit file list and
the operation registry is excluded. The remote exact image ID is checked again before
`up --no-build --pull never`. These guards do not apply a handoff.

**OPEN: ordinary deploy protocol integration.** A read-only guard cannot exclude a race
in which a new sequencer claims an unbound target after the check. Supporting ordinary
deploy on a protocol-managed target requires one target lock across the entire remote
upload/recreate lifecycle and confirmed outcomes, with preserved history. This package
conservatively blocks that path; it does not implement partial lock expiry or unlink a
registry after retirement. No turnkey operational deploy or VM reboot proof is claimed.

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
