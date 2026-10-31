# HT-CI656-ISOLATED-GITHUB-REPRO-PREP-07

Local preparation only. No commit, push, remote branch, PR or Actions run is
authorized by this document. CI #656 attempt 2 root cause remains UNKNOWN.
PR #237 and `.github/workflows/ci.yml` are outside this change.

## Local identity and reviewed inputs

- Project: Hookah_Tootah; remote identity: github.com/koteev-m/hookah_bot.
- Base: `eab6a22025b5b532e852a92414d2d9bbcd451f57`.
- New worktree: `/private/tmp/HT-CI656-ISOLATED-GITHUB-REPRO-PREP-07/worktree`.
- New branch: `codex/ht-ci656-isolated-github-repro-07`.
- Source: `/private/tmp/HT-CI656-FIRST-FAILURE-REPRO-01/worktree`, unchanged.
- Review: HT-CI656-BOUNDED-SCAN-INDEPENDENT-REVIEW-06, PASS / no findings.
- Source evidence: `/private/tmp/HT-CI656-BOUNDED-METADATA-SCAN-05/validation.json`.

The five files remain byte-identical to the independent review. The earlier
PROJECT_STATUS checkpoint is deliberately preserved to retain its reviewed hash;
this document records the preparation task without rewriting reviewed evidence.

| File | SHA-256 |
| --- | --- |
| PROJECT_STATUS.md | `0f3854edf1837b67928505b90399f2464aedce8b09784da674e3a105a7542c81` |
| docs/TESTING_QA_SMOKE_STRATEGY.md | `c4f0d1ec98c77dd8c9936fc6ae834443372b57a3ce05a36a729322c6d0c8043f` |
| scripts/test-v126-linux-runtime.py | `16625d0a050a5728fb31714611dafddcbe28678b43302300ee74632638190226` |
| scripts/test-v126-ordinary-deploy.py | `88499900722a1c251cbf95f936cb9e9ff221449d30a3dc2d54c3132eb7066bef` |
| scripts/v126-ordinary-deploy.py | `a13890ae38f6a77c47e512d84495b92b95161f2b1831cf18f046da5a8dbc03ee` |

Only four preparation files are added: this document, the create-only workflow,
`scripts/ci656-isolated-repro.py` and its preparation tests. The runtime source
gate admits exactly these nine changed paths against the base, one direct parent,
a clean checkout, the five hashes, the `[skip ci]` marker, and the pinned historical
fixture commit already present locally.

## Why create is applicable

GitHub associates `create` with the new branch/tag and its last commit. The event
does not have the default-branch-only restriction listed for other events.
GitHub discovers workflow files at the commit/ref associated with the event.
Thus the new workflow is included in the first commit pushed to the new branch;
it does not need to be added to main first.

Sources checked on 2026-10-31:

- [Create event and its SHA/ref](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#create).
- [Workflow discovery at the event SHA/ref](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflows#workflow-triggers).
- [Skip instructions apply only to push/pull_request](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs).
- [Events generated using GITHUB_TOKEN do not normally trigger another workflow](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow#triggering-a-workflow-from-a-workflow).

Use the user's separately authorized normal Git push credential, not a workflow's
GITHUB_TOKEN. The commit message must contain `[skip ci]`. The repository has only
one existing workflow, `CI` (`ci.yml`), with push/pull_request triggers. Its push
run for this disposable branch is suppressed. No PR is created; required checks
on PR #237 are untouched. Skip instructions do not suppress this create event.
The diagnostic branch must never be merged, reused, deleted/recreated or used as
a PR branch: skipped required checks would remain pending on such a PR.

The job also checks the exact repository, branch name, ref_type=branch, full ref,
and run_attempt=1. A tag, another branch, workflow_dispatch, or manual rerun cannot
execute the fixture. No retry mechanism or alternative trigger is provided.
This limits the authorized first creation; it is not a durable cross-run lock
against an operator deleting and recreating the branch.

## Admission and stop conditions

The final commit SHA does not exist during this no-commit preparation. After a
separately authorized local commit, record that exact OID before authorizing its
publication. Runtime requires checkout HEAD = create event SHA = workflow SHA,
the exact workflow ref, and a single parent equal to the feature base. It does
not substitute the base SHA or historical fixture SHA for the published SHA.

Before fixture execution, fail with REPRO_BLOCKED on any unverifiable condition:

1. Source/event/hash/branch/attempt checks above, or an inherited product secret,
   staging/production variable, SSH agent, Docker endpoint/context/config override.
2. Actual Linux GitHub-hosted runner; Ubuntu **26.04.1 LTS**, VERSION_ID=26.04.
3. Docker **client and server 29.4.2**, server OS Linux; Compose **5.1.3**.
4. Noninteractive root access, actual PID 1 systemd, unified cgroup hierarchy,
   Python pidfd APIs, real systemctl/Caddy and available loopback port 8080.
5. Java/Javac 21, PostgreSQL 17 host postgres/psql/pg_dump/pg_restore, and required
   Python/Git/Bash/curl/ssh/OpenSSL/keytool/Docker executables. JAVA_HOME_21_X64 and
   `/usr/lib/postgresql/17/bin` may select preinstalled tools; nothing is installed.
   The existing runtime fixture independently checks its actual Docker PG17
   endpoint and identity after creating its owned containers.
6. Root-owned local `/run/docker.sock`; no containers, images, volumes or build
   cache; only empty standard bridge/host/none networks. An occupied daemon is
   refused, never pruned or reset to satisfy admission.

The ubuntu-26.04 label does not guarantee the exact point release, Docker versions,
Caddy/PG17 availability or empty daemon. Missing tools, preloaded images/cache or
version drift intentionally produce REPRO_BLOCKED. There is no package install,
Docker/Compose downgrade, automatic remediation or second attempt. A blocked
admission is evidence of environment mismatch, not a reproduced CI defect.

The root child receives `env -i` with only a fixed tool PATH, a private fresh HOME,
private temporary root, verified event identifiers and hosted-runner flags. No
project/GitHub secrets, registry credentials, SSH agent or proxy configuration are
forwarded. Checkout uses read-only contents permission and does not persist its
credential. There is no deployment environment, OIDC, login, cache or upload step.

The unchanged endpoint guard is an accidental-escape guard, not an OS network
sandbox. It constrains fixture gh/ssh/curl calls; source-bound fixture adapters
map Caddy to owned units/files and explicit loopback endpoints. Docker runtime
uses its existing labelled internal network and synthetic provider/credentials.
No staging/production endpoint or authorization is supplied. Public image and
build-dependency downloads remain possible during Docker builds; this plan does
not claim general egress isolation or alter the established fixture contract.

## One admitted execution

After the full preflight, run precisely these existing TransportTest methods in
one bounded 120-second subprocess, with output captured privately:

- test_real_tcp_bytes_and_per_connection_resolution
- test_real_tcp_connection_cap_and_public_target_refusal
- test_real_tcp_idle_deadline_and_active_cleanup_are_bounded
- test_actual_readiness_consumer_503_to_200_through_real_tcp_relay

All four must pass before the ordinary invocation marker is created. Then run
exactly once, as root, the existing command shape:

```text
python3 -B scripts/v126-test-endpoint-guard.py --run <private-root>/endpoints --
  python3 -B scripts/test-v126-ordinary-deploy.py --require-linux-integration
  --evidence <private-root>/ordinary
```

The invocation is bounded by the 60-minute job timeout, matching compose-runtime's
existing outer bound. Native consumer/supervisor/deadline/cleanup rules remain
unchanged. No wrapper timeout kills the lifecycle early and no wrapper removes
resources. Cancellation or absent/unverified completion is REPRO_UNKNOWN, never
success, REAPED or proven cleanup. An existing invocation marker blocks relaunch.

The fixture alone decides cleanup vs retained UNKNOWN resources. Everything runs
on a fresh disposable hosted VM; no foreign cleanup is attempted. Retained private
evidence disappears with runner disposal and is not uploaded. This intentionally
limits later private forensics to a separately authorized future task.

Only one `HT_CI656_REPRO_RESULT` JSON line is published. It contains a closed
status/reason, event SHA when available, loopback/invocation counts, checked
endpoint status, fixture cleanup result, validated closed first-failure metadata,
and optionally the reviewed safe build stream records. Original stream SHA-256,
byte counts, normalized reason codes and 4096-byte safe excerpt limits remain.
Arbitrary events, exception text, raw stdout/stderr/private capture are omitted.
No result at all after runner termination also means UNKNOWN.

## Local validation

Run from the new worktree:

```bash
python3 -B scripts/test-ci656-isolated-repro.py
git --no-optional-locks diff --check
git --no-optional-locks status --short
```

Tests parse YAML with Ruby/Psych and verify the trigger/permission contract,
source/hash refusals, exact versions, no Mac subprocess dispatch, the four test
selectors, guarded argv, summary projection and one-invocation marker. They do
not use a Docker daemon, sudo, network listeners or Linux lifecycle execution.
Compile Python source in memory with `compile(..., 'exec')`; do not add pycache.
Confirm `ci.yml` and operational dependencies still equal the base, the five
reviewed hashes still match, and the original reviewed worktree is unchanged.
No hosted execution or local loopback success is claimed by these checks.

## Later publication: separate permission required

First obtain permission to stage the explicit nine files and create **one** local
commit with this exact subject:

```text
chore(ci): isolate CI656 attempt-2 repro [skip ci]
```

Only after that permission, run these explicit commands in the new worktree:

```bash
git add -- PROJECT_STATUS.md docs/TESTING_QA_SMOKE_STRATEGY.md \
  scripts/test-v126-linux-runtime.py scripts/test-v126-ordinary-deploy.py \
  scripts/v126-ordinary-deploy.py .github/workflows/ci656-isolated-repro-07.yml \
  scripts/ci656-isolated-repro.py scripts/test-ci656-isolated-repro.py \
  docs/CI656_ISOLATED_REPRO_07.md
git diff --cached --check
git commit -m 'chore(ci): isolate CI656 attempt-2 repro [skip ci]'
```

Record `REPRO_COMMIT=$(git rev-parse --verify HEAD)`, its single parent, exact
message, nine-file diff and hashes. Check a clean worktree/index. Then request
separate authorization for **one create-only push of that exact commit** and the
**one automatic GitHub-hosted repro** that this push will cause. The permission
must cover up to 60 minutes of runner time, public dependency/image downloads,
root-owned disposable Docker/systemd/Caddy fixture resources and only the safe
summary in GitHub logs. It does not cover a rerun, branch recreation or fallback
trigger if admission fails.

Before that external action, repeat the project identity gate with the actual
published-commit constraint, revalidate local source/hashes and inspect remote
branch absence using `git ls-remote`. If it exists, STOP without pushing. Use a
create-only lease so a race cannot update an existing branch:

```bash
git push --porcelain \
  --force-with-lease=refs/heads/codex/ht-ci656-isolated-github-repro-07: \
  origin "$REPRO_COMMIT:refs/heads/codex/ht-ci656-isolated-github-repro-07"
git ls-remote --exit-code origin refs/heads/codex/ht-ci656-isolated-github-repro-07
```

The empty expected lease means the remote ref must not exist; it does not
authorize overwriting a branch. Compare the returned SHA exactly to REPRO_COMMIT,
then STOP. Do not monitor/wait for Actions. The user/ChatGPT consumes the result;
any red/blocked run gets a separate bounded task. No main/PR #237 update, merge,
workflow rerun/dispatch, remote deletion or staging/production action is included.
