# HT-CI656-CADDY-FORMAT-FIX-11

This is the source-bound bundle for one user-authorized diagnostic commit,
one ordinary non-force push and one automatic GitHub-hosted create run.
No merge, deployment, other branch change, second push, rerun or further diagnostic branch.
CI #656's root cause remains UNKNOWN until direct ordinary-runtime evidence.

## Exact identity

Project Hookah_Tootah; github.com/koteev-m/hookah_bot.
Worktree: `/private/tmp/HT-CI656-CADDY-FORMAT-FIX-11/worktree`.
Branch: `codex/ht-ci656-caddy-format-fix-11`.
The commit must have exactly one parent, the feature commit
`eab6a22025b5b532e852a92414d2d9bbcd451f57`, and include [skip ci].
Do not put it on top of diagnostic commit 54b25f4723773ee8f20c1b79ffcb4a0136ab5269.
The helper requires clean checkout, exact nine paths, five reviewed hashes,
HEAD = event SHA = workflow SHA, exact repository/workflow/ref and attempt1.

Five reviewed files stay byte-identical: PROJECT_STATUS.md,
docs/TESTING_QA_SMOKE_STRATEGY.md, scripts/test-v126-linux-runtime.py,
scripts/test-v126-ordinary-deploy.py, scripts/v126-ordinary-deploy.py.
The four diagnostic files are this document, scripts/ci656-isolated-repro.py,
scripts/test-ci656-isolated-repro.py, and
.github/workflows/ci656-isolated-repro-11.yml. Workflows07/09/10 are not included.
The fresh nine-file SHA256 manifest and local results are retained outside Git at
`/private/tmp/HT-CI656-CADDY-FORMAT-FIX-11/preparation-validation.json`.
The old reviewed PROJECT_STATUS checkpoint is retained to preserve its hash;
this document records the publication task and its authorized boundary.

## Confirmed diagnostic defect and bounded correction

[Run10](https://github.com/koteev-m/hookah_bot/actions/runs/41239605368) completed
REPRO_BLOCKED / TOOL_VERSION, stage PREFLIGHT_VERSION, component caddy,
expected vMAJOR.MINOR.PATCH, actual 2.6.2, lifecycle_invocations=0 and
cleanup_quiescent=null. Dependency setup and the early Docker/Compose checks
succeeded. The validator extracted the unprefixed version but required v in a
second condition. This is a diagnostic format defect, not CI656's runtime cause.

The regression for the observed 2.6.2 fails against the published validator.
The correction accepts exactly three numeric release components, with optional
lowercase v, optionally followed by one Go module h1 SHA-256 checksum. Both
2.6.2 and v2.6.2 pass; standard prefixed release/checksum output remains accepted.
There is no new minimum or exact Caddy version. Leading zeros, incomplete/four-part
versions, duplicate versions, malformed checksums, custom/prerelease suffixes and
unrecognized trailing text are refused. Outer whitespace/newline handling stays
unchanged. A full-match failure reports UNKNOWN, never a guessed version fragment.

The checksum form follows upstream [Caddy Version()](https://github.com/caddyserver/caddy/blob/v2.6.2/caddy.go#L821-L825).
No checksum, suffix, command output or build metadata is published. Source hashes
and the other11 version checks, full admission, fixture and cleanup policy remain
unchanged. Existing branches/worktrees07/09/10 are preserved.

## Version diagnostic contract

| Component | Required condition |
| --- | --- |
| java / javac | major21 / 21.* |
| postgres / psql / pg_dump / pg_restore | 17.* |
| libpq | installed libpq5 package18.* |
| caddy | [v]MAJOR.MINOR.PATCH; optional module h1 checksum, no version pin |
| docker_client | exactly29.4.2 |
| docker_server | exactly29.4.2 |
| docker_os | linux |
| docker_compose | exactly5.1.3 |

TOOL_VERSION refusal includes fixed stage PREFLIGHT_VERSION, an allowlisted
component, constant expected_version and normalized numeric actual_version or
UNKNOWN. Docker OS uses only closed linux/windows values or UNKNOWN. Version
probe failure/timeout/decoding failure uses the scheduled component and UNKNOWN;
this does not prove a physical version mismatch. A missing executable remains
TOOLS_REQUIRED. Private output, suffixes, build metadata, environment values,
paths, URLs, credentials, dependency coordinates and capture are never copied.
Only the proven Caddy format defect is corrected. libpq checks package major,
not psql linkage. Every other version requirement is unchanged.

## Single-run execution and admission

One create trigger, guarded by exact branch/ref/repository and run_attempt=1;
one job on ubuntu-26.04, contents:read, checkout event SHA, credentials not
persisted. No deployment environment, OIDC, project secrets, registry login,
private artifact upload, cache, dispatch or retry.

1. After checkout, --check-preparation validates source/host/inherited authority,
   then read-only Docker/Compose versions using an explicit root-owned local
   socket, fixed system PATH and a fresh private HOME/config. A refusal stops
   before Java/apt preparation. Full root admission is still required later.
2. The same setup-java pin as compose-runtime selects Temurin21/JAVA_HOME.
3. The same PGDG repository setup/apt method installs PG17 server/client and
   libpq5=18.*. Download and extract distro Caddy into a private temporary path;
   no global Caddy service. Unused PG18 CLI/SSH server setup is omitted.
   Installation output is private0600; only a fixed setup-failure summary can
   be emitted. Setup is bounded to12min; total job bound remains60min.
4. Root env-i uses only selected Java/Caddy/PG17 and fixed system PATH, private
   HOME/temp and verified event fields. Require Ubuntu26.04.1 LTS, exact Docker
   client/server29.4.2 and Compose5.1.3, Linux/root/systemd/cgroup/pidfd, required
   tools/versions, root-owned local socket, empty Docker resources/cache and
   unused standard networks. Inherited deployment/remote authority is refused.
   No Docker/Compose install, upgrade/downgrade, daemon prune or foreign cleanup.
5. Only after full admission run the four existing loopback regressions in one
   120-second subprocess, then create the invocation marker and run the existing
   guarded ordinary lifecycle once. Existing supervisor/deadline/ownership/
   recovery/handoff/cleanup policy is unchanged. Failure/unknown stops this task.

The fixed child PATH includes JAVA_HOME/bin, the extracted Caddy directory,
/usr/lib/postgresql/17/bin and system directories; arbitrary PATH is not forwarded.
The endpoint guard is not an OS egress sandbox. Public package/image/build
network downloads may occur, using no staging/production credentials or endpoints.
Only owned disposable fixture resources may be created or cleaned. Missing or
unverified completion is UNKNOWN, never proof of cleanup or successful lifecycle.

## Validation and publication procedure

Run python3 -B scripts/test-ci656-isolated-repro.py (33 tests). Its matrix covers
all12 components, both Caddy forms and standard checksum output, malformed and
ambiguous versions, safe output, absent/unknown versions, blocked fixture,
early-check order/isolation, old07/09/10 branch refusal, one marker and UNKNOWN.
YAML/trigger/permissions and bash-n validation are included. Compile Python in
memory; verify exact nine paths/hashes, unchanged ci.yml and contracts, and
git diff --check. Local tests do not run actual Docker/root/Linux lifecycle.

Before the authorized one commit/push: repeat identity, confirm remote branch
absence, empty initial index, PR237/main unchanged, stage exactly the nine manifest
paths, inspect staged hashes/diff, commit once with [skip ci], verify single parent,
tree/files/hashes and clean status. Then one explicit non-force branch-only push,
with no tags or submodule pushes. Confirm remote SHA equality and collect the
user-authorized terminal result read-only. No rerun, second push or further diagnostic branch.

GitHub [create](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#create)
uses the new ref and its commit. [Skip rules](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs)
apply to push/pull_request, so [skip ci] suppresses ordinary CI for this diagnostic
commit while create runs. No PR is created; PR237 required checks stay unchanged.

If an exact version mismatch is proven, stop and report component/expected/actual.
Do not substitute versions or repeat rolling-image attempts. Any incompatible-image
remedy needs a separate task, potentially a verified disposable matching image;
the hosted-only gate cannot be bypassed using forged flags on a standalone VM.
PR237 merge readiness and the original CI656 cause are not established by this run.
