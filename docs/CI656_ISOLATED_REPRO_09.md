# HT-CI656-REPRO-TOOLS-FIX-09

PREP_READY / LOCAL_ONLY. This document grants no commit, push, remote branch,
Actions invocation/rerun, merge or deployment permission. CI #656 remains UNKNOWN.

## Identity and evidence

Project Hookah_Tootah, repository github.com/koteev-m/hookah_bot.
New worktree: `/private/tmp/HT-CI656-REPRO-TOOLS-FIX-09/worktree`.
New local branch: `codex/ht-ci656-isolated-github-repro-09`.
Starting HEAD and required single parent: `eab6a22025b5b532e852a92414d2d9bbcd451f57`.
Branch 07 remains at `943f4351acfbd9a3fcaac978a5a43560b92f365f` and is unchanged.
PR #237, main, the primary checkout and the original reviewed worktree are untouched.

[Run 41231905060](https://github.com/koteev-m/hookah_bot/actions/runs/41231905060)
finished REPRO_BLOCKED / TOOLS_REQUIRED with zero lifecycle invocations and null
cleanup_quiescent. Its safe result does not identify a missing executable.
No specific package is proven absent. At least one of the following names was not
resolvable as an executable in the root driver's PATH:

`sudo`, `systemctl`, `caddy`, `curl`, `ssh`, `bash`, `git`, `docker`, `java`,
`javac`, `keytool`, `openssl`, `postgres`, `psql`, `pg_dump`, `pg_restore`.

The workflow omitted compose-runtime's Java setup and PostgreSQL/libpq/Caddy
provisioning. Caddy and PG17 are plausible missing dependencies, not demonstrated
historical absences. A separate confirmed PATH defect would discard the extracted
Caddy directory even if compose-runtime's GITHUB_PATH setup were copied verbatim.
The new local PATH regression demonstrates installed-but-unreachable Caddy without
claiming that Caddy was installed in the old run. Ubuntu 26.04.1 was observed;
Docker/Compose and the individual tool versions were not reached in that run.

## Prepared environment correction

One job, runner ubuntu-26.04, contents: read, checkout at event SHA with
persist-credentials: false. No deployment environment, OIDC, project secrets,
registry login, private artifact upload, workflow dispatch or retry.
Order is strict; default step success dependency is retained:

1. Checkout; validate exact event/source/hash/host identity and inherited authority.
2. Use compose-runtime's pinned actions/setup-java v4, Temurin 21, no cache,
   overwrite-settings: false. Select its JAVA_HOME in the root child.
3. Use the same PGDG setup script and apt package choices as compose-runtime:
   postgresql-common, ca-certificates, curl; postgresql-17, postgresql-client-17,
   and `libpq5=18.*`. The unused PG18 CLI and SSH server from the larger CI job
   are omitted; the existing ssh client must still pass executable admission.
4. Download the distro Caddy package and extract with dpkg-deb into the job's
   private temporary directory. Do not install/start a global Caddy service.
5. Run the complete root preflight, then the four existing loopback tests and
   at most one existing guarded ordinary-lifecycle invocation.

The root PATH contains only JAVA_HOME/bin, the explicitly selected extracted
Caddy bin directory, /usr/lib/postgresql/17/bin and the previous fixed system
paths. Arbitrary inherited PATH and JAVA_HOME_21_X64 are not forwarded. The new
preflight also checks the installed libpq5 package major 18 with dpkg-query.
No Docker or Compose package is installed, upgraded or downgraded. No apt upgrade,
daemon prune, alternate admission, lifecycle retry or foreign cleanup is added.

Dependency commands run once in an errexit child shell, with raw output captured
privately (0600) and never uploaded/printed. First command failure stops setup
and emits only a fixed DEPENDENCY_SETUP / DEPENDENCY_SETUP_FAILED summary with
zero lifecycle invocations and unproven cleanup. Setup has a 12-minute step bound;
the existing total job bound remains 60 minutes. A killed setup/job may produce
no final summary; absence of evidence is never success. Package installation may
start a distro PG service on the disposable runner, as in the existing CI;
no external credentials or staging/production configuration are supplied.

Official [PGDG documentation](https://www.postgresql.org/download/linux/ubuntu/)
lists Ubuntu 26.04 support and the same postgresql-common repository setup script.
Actual package resolution, downloads and dynamic linking on the next hosted image
are NOT_RUN locally. Installation failure remains REPRO_BLOCKED; no fallback
version or environment substitution is allowed.

## Admission and public output

All prior source/parent/clean-checkout/reviewed-hash checks remain mandatory.
Before any fixture, require Ubuntu 26.04.1 LTS, Docker Client and Server 29.4.2,
Compose 5.1.3, Java/Javac 21, PG17 tools, libpq18, Caddy, Linux/root/systemd,
unified cgroups and pidfd; local root-owned Docker socket; empty containers,
images, volumes, build cache and only unused standard networks. No inherited
product/deployment credentials or Docker endpoint/config/context overrides.
An occupied or incompatible environment is refused; it is never cleaned to pass.

TOOLS_REQUIRED now includes only tool_diagnostics records with a fixed stage
PREFLIGHT_TOOLS, an allowlisted executable name, and NOT_ON_PATH or
EXPECTED_LOCATION_UNAVAILABLE. NOT_ON_PATH does not claim the package is absent
from the host. The original sixteen names plus dpkg-query form the allowlist.
No resolved paths, environment values, raw stdout/stderr or exception text appear
in these records. Successful PATH resolution alone does not bypass version checks.

After full admission, all four existing loopback regressions must pass within
120 seconds, before the invocation marker is created. The original guarded
ordinary-lifecycle command, supervisor, deadlines and cleanup policy are unchanged.
A marker prevents a second invocation. Lost result or post-invocation exception
remains UNKNOWN/75; unverified cleanup is never a successful completion. The
unchanged endpoint guard and fixture resource ownership are retained. The guard
is not an OS egress sandbox; public image/build-dependency downloads remain possible.
Only reviewed safe summary/diagnostic records are published, never private captures.

## Reviewed file preservation and local checks

The five reviewed files were copied after SHA-256 verification from preparation
07 and remain byte-identical: PROJECT_STATUS.md, docs/TESTING_QA_SMOKE_STRATEGY.md,
scripts/test-v126-linux-runtime.py, scripts/test-v126-ordinary-deploy.py,
scripts/v126-ordinary-deploy.py. Their digests remain pinned in the runner.
PROJECT_STATUS is preserved as reviewed evidence; this document is the new checkpoint.
Only four preparation files differ from 07: the new workflow and this document,
scripts/ci656-isolated-repro.py, scripts/test-ci656-isolated-repro.py.
The final nine-file SHA-256 manifest and validation evidence are recorded outside
the checkout in `/private/tmp/HT-CI656-REPRO-TOOLS-FIX-09/preparation-validation.json`.

`python3 -B scripts/test-ci656-isolated-repro.py`: 20 tests PASS. Includes YAML
parsing with Ruby/Psych, trigger/permission/setup-order checks, bash -n for every
shell step, six separately injected installation failures and one stubbed success,
PATH resolution, tool diagnostics, version rejection, source binding, marker,
UNKNOWN and public-summary tests. Stubbed shell tests perform no apt/sudo/network
operation and do not establish real installation success. Python syntax is compiled
in memory; git diff --check and exact nine-path/hash verification are required.
No Linux lifecycle, local Docker daemon, root provisioning or loopback listeners
are run on Mac. These checks are NOT_RUN and require the authorized hosted attempt.

## Conditions for a later single publication and execution

Branch 07 already exists. A push to it would not produce another create event.
The new workflow is `.github/workflows/ci656-isolated-repro-09.yml`, guarded by
exact repository, event=create, ref_type=branch, both branch/full ref 09 and
run_attempt=1. The workflow SHA, event SHA and checked-out HEAD must agree.
GitHub's [create contract](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#create)
uses the newly created ref and its commit. The existing successful create-trigger
of run 41231905060 is additional evidence of this repository's routing.

A later user authorization must explicitly cover staging exactly the nine manifest
paths, one direct-child commit with `[skip ci]`, one ordinary non-force push of
that exact SHA to refs/heads/codex/ht-ci656-isolated-github-repro-09, and its one
automatic hosted execution. Revalidate identity, all nine hashes, clean index,
parent/tree/path set and remote ref absence immediately before publication. If
that remote branch exists, STOP. Do not overwrite, delete/recreate or reuse it.
No tags, other branches, PR creation, second push, rerun or fallback trigger.

Suggested commit subject after separate authorization:
`chore(ci): prepare CI656 repro tools on isolated branch 09 [skip ci]`.
Record the exact commit before requesting/consuming push authorization. Then use
an explicit single refspec with no force, mirror or follow-tags options; verify
remote SHA equality afterward and stop. Results collection/monitoring requires
the scope of the later task to permit it. This document executes none of those actions.

[Skip instructions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs)
apply to push/pull_request, not create. The marker suppresses ordinary ci.yml for
this disposable diagnostic commit. PR #237's required checks are not changed;
never open a PR from or merge this diagnostic branch. Any version/resource/setup
failure stops without another attempt. Even PREP_READY is not runtime success,
proof of cleanup, or a diagnosis of CI #656.
