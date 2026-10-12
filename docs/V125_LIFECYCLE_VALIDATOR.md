# V125 local lifecycle proposal validator

`HT-NEW-STAGING-03` / `Hookah_Tootah` / `V125_ISOLATED_TEST_LIFECYCLE`.
This Python standard-library module checks local proposal consistency only.
It does not extend the existing deployment, V126 authority/genesis, recovery or security
contracts. No dispatcher imports it. No operational lifecycle is implemented.

## Use and result

From the repository root, with Python 3.9 or newer:

```bash
python3 -B scripts/v125_lifecycle_validator.py --scope scope.json < proposal.json
python3 -B -m unittest discover -s scripts -p 'test_v125_lifecycle_validator.py' -v
```

The CLI reads one local comparison scope and stdin, then writes JSON to stdout.
Exit `0` means syntactic/logical consistency only; `1` means rejection. File/decode
errors also produce a rejection. CLI usage errors use argparse's exit `2`.
Both validation outcomes always contain:

```json
{"status":"PROPOSAL_ONLY","valid":true,"execution_authorized":false,"operationally_qualified":false,"errors":[]}
```

On rejection, `valid` is false and `errors` contains a fixed reason code without input
values. No input field can change the output authority flags. There is no executor,
network client, subprocess call, persistent store, credential handling or receipt writer
in the validator. The CLI reads only its explicit scope file; the additional offline
initial-install API below also reads an explicitly supplied backend archive through
the existing archive helper. It does not invoke that helper's publication/test commands.

## Closed input structure

The exact shapes are implemented in [the validator](../scripts/v125_lifecycle_validator.py).
The executable synthetic examples are `example(False)` and `example(True)` in
[the test suite](../scripts/test_v125_lifecycle_validator.py); they return `(proposal, scope)`.
All objects reject missing/extra keys. JSON rejects duplicate keys (including escaped
aliases), non-finite constants, malformed input and documents larger than 64 KiB.
Booleans/floats cannot stand in for integer format/schema versions.

| Input | Required content |
| --- | --- |
| Shared pins | `format_version=1`, exact project/lifecycle, `target`, `release`, nonzero `predecessor_inventory_sha256`; proposal must equal scope |
| Target | `target_id`, `resource_id`, `control_domain_id`, `epoch_id`, `frontend_origin`; new target ID starts `ht-v125-isolated-` |
| Release | Exact 40-character source commit, PostgreSQL migration schema V125, backend/frontend source/version/digest pins, frontend origin, migration-manifest and config digests |
| Scope only | `excluded_targets` with explicit `old_staging` and `production` descriptors; `used_operation_ids` list (at most 256, unique) |
| Proposal only | `mode=PROPOSAL_ONLY`, `execution=NOT_EXECUTED`, fresh `operation_id`, `current`, `proposed_state`, `prerequisites`, `configuration` |
| Current state | State name, database `ABSENT`/`PRESENT`, PostgreSQL migration schema version, nullable recovery-point digest, state-evidence digest |
| Configuration | Same frontend origin, `telegram=DISCONNECTED`, `user_access=CLOSED`, `control_import=NONE`, `data_import=NONE` |
| Each prerequisite | Exactly `status=EVIDENCE_REFERENCED`, target/epoch/source/config/recovery-point pins and a nonzero evidence digest |

Digests are 64 lowercase hex characters without a `sha256:` prefix; all-zero placeholders
are rejected. Source/image/config/manifest bytes are not loaded or authenticated.
Image version and source declarations must agree with the separately supplied scope.
The migration-manifest digest pins the proposed PostgreSQL V125 migration set; the tool
does not execute SQL or inspect that manifest. HTTPS origins must be canonical origins
without credentials, port, path, query or fragment. Excluded descriptors reject reuse of
any corresponding target/resource/control-domain/epoch/origin field, including when both
proposal and scope name that excluded identity.

## States and prerequisites

Only these two single-step proposals are accepted:

| Current → proposed state | Required references beyond isolation/predecessor/source/configuration review |
| --- | --- |
| `EMPTY_CONFIRMED` → `V125_BASELINE_INSTALLED` | Empty-target evidence and installation-approval reference |
| `V125_BASELINE_INSTALLED` → `TEST_READY_CONFIRMED` | Baseline-installation evidence, Policy B, recovery/custody and testing-approval references |

The first requires `database=ABSENT`, null schema and null recovery point, including in
all prerequisite bindings. The second requires `database=PRESENT`, schema 125 and a
nonzero recovery-point reference bound by every prerequisite. State evidence must equal
the empty-target/baseline-installation reference; predecessor review must reference the
pinned predecessor inventory. `TEST_READY_CONFIRMED` is a proposed terminal state name,
never an output certification. Skips, reverse transitions, repetition and further
transitions from that terminal state reject. Access remains closed even for a valid
testing-readiness proposal; opening access is outside this module.

Examples: `example(False)` is a valid synthetic empty-target proposal;
`example(True)` is a valid synthetic baseline-to-testing proposal. Starting from either,
the following changes reject:

| Change | Reason |
| --- | --- |
| Copy an old staging/production resource, epoch or frontend origin into the scope target | Excluded identity reuse |
| Set release/image schema to 126, alter image digest or migration pin only in proposal | Wrong version or scope mismatch |
| Add an R0 digest to the empty current state | No database can have a recovery point |
| Omit Policy B, use `PENDING`, add `approved=true`, or reference another epoch | Missing/invalid/unbound prerequisite |
| Jump from empty to test-ready or reuse an operation ID from scope history | Invalid transition/replay |
| Add `execute=true`, set `execution=DEPLOYED`, or request output authority | Closed-schema/claim rejection |

## Operational boundary and remaining prerequisites

Both JSON documents are untrusted comparison inputs. `EVIDENCE_REFERENCED` means only
that a shaped reference was supplied; it does **not** confirm its contents, provenance,
independence, approval, freshness or existence. An author can fabricate matching pins or
omit historical identities in scope. Repeated calls are stateless: replay detection covers
only the supplied history, not a global operation registry. Synthetic fixtures deliberately
demonstrate that even made-up evidence can yield only `PROPOSAL_ONLY`. Do not connect a
`valid=true` or exit-0 result to an executor or treat it as an operational gate.

Still required outside this task: independently acquired empty-state and complete control
isolation evidence; complete predecessor/history inventory; actual immutable V125 source,
image, migration and configuration verification; genuine installation and first-database
observations; a real recovery point, Policy B/restore/custody qualification; independently
retained, current owner approvals and operation-identity accounting. Telegram and user
access need their separate independent gates. None was acquired, created or qualified here.
No old staging, production, V126 or run724 state is changed or reclassified.

## HT-NEW-STAGING-07 — offline initial-install slice

`inspect_initial_install()` extends this module; there is no second proposal validator,
new authority schema, lifecycle executor or install CLI. The original proposal API and
tests were carried from the primary checkout unchanged before this extension. Its reviewed
source SHA-256 was `cbf9a98c47d542577e890af7bc192c8720aaa68291534f52da7352c854e1f02a`.
The task base is local `origin/main` at `5d32a7198b5617bd10afd1fb810fb7605e59163e`.

The API first reuses `check()` for the single EMPTY-to-baseline proposal, including
target/epoch exclusions, predecessor references, source/config pins, local replay
history and disconnected configuration. It then compares actual supplied PostgreSQL
migration and sanitized public configuration bytes, and uses
[`docker-save-archive.py:verify_archive`](../scripts/docker-save-archive.py) to read the
backend archive without Docker. Exact backend ID, source labels, Linux/amd64 platform,
layer hashes and archive structure are checked by that existing implementation.

`migration_files` is an in-memory mapping of SQL basenames to bytes: exactly one each
of V1 through V125, with no V126, duplicate version, path or non-SQL member. Its local
comparison digest is SHA-256 of the filename-to-content-SHA256 map serialized with
sorted keys, compact JSON separators and one LF. It must match the proposal/scope
`migration_manifest_sha256`. `configuration_bytes` must serialize the existing
proposal `configuration` object exactly in meaning, including disconnected Telegram
and closed access, and match `config_sha256`. Duplicate/extra keys and contradictory
content refuse even when rehashed. Never pass `.env`, credentials or their hashes.
The backend `digest` is an image config/manifest ID for this API, as required by the
archive helper. It is not silently interpreted as a registry digest.

PostgreSQL inputs must specify `17.10`, `linux/amd64` and matching exact
`docker.io/library/postgres@sha256:...` references. No reference image from an earlier
task is a default approval. These PostgreSQL fields are **declaration comparisons**:
the backend archive verifier does not support stock PostgreSQL, so no PostgreSQL image
bytes, running version or host architecture are authenticated here.

The keyword arguments are local diagnostic claims, not independently acquired evidence:

| Evidence kind / DB / schema / attempt outcome | Candidate state |
| --- | --- |
| `EMPTY_TARGET` / `ABSENT` / null / `NOT_STARTED` | `EMPTY` |
| `DATABASE` / `PRESENT` / 0 / `PREPARED` | `PREPARED_EMPTY_POSTGRESQL` |
| `DATABASE` / `PRESENT` / 125 / `INSTALLED` | `INSTALLED_CLOSED_V125` |
| Missing, partial, failed, timed out, UNKNOWN or contradictory claims/materials | `UNKNOWN` |

Prepared/installed claims describe a possible poststate of the original proposal;
they grant no resume, retry or adoption. Existing/imported DBs do not satisfy EMPTY.
Credential import is refused. All successful and rejected assessments retain
`status=PROPOSAL_ONLY`, `verified_state=UNKNOWN`, `execution_authorized=false`,
`operationally_qualified=false` and
`execution_blockers=[INDEPENDENT_INITIAL_INSTALL_AUTHORITY_UNAVAILABLE]`.
`valid=true` only means these untrusted local inputs are consistent. There is no
argument, JSON approval field or adapter injection that opens execution.

Run the offline API examples and regression tests with Python 3.10 or newer:

```bash
python3 -B -m unittest discover -s scripts -p 'test_v125*.py' -v
```

The [initial-install tests](../scripts/test_v125_initial_install.py) generate synthetic
archives and public descriptors, exercise real local SQL inventory bytes, and prohibit
process/network use during assessment. They do not install or migrate any database.
The existing archive helper reopens paths between hashing and parsing, and source labels
do not prove that the supplied SQL inventory is embedded in the image. These limits make
the result unsuitable for dispatch or immutable target acquisition. No TOCTOU, credential
custody, complete predecessor ancestry or real operation-history proof is claimed.

Independent review of the base contracts found no EMPTY installation acquisition/enrollment
or action transport. `v126-authority-epoch.py` admits genesis/copy; its `BASE_STATE` has no
database-absence proof. INIT requires existing owner/history and real R0. The larger local
source-preparation/activation candidate in the `ht-qr-01-contract-correction` worktree was
read only as design evidence; it was not ported or treated as operational authority.

Real use still needs independently acquired isolation/EMPTY and full predecessor ancestry;
fresh epoch, U/C/P/clock/control binding and independently retained exact action approval;
source, PostgreSQL/backend image, config and migration acquisition; protected enrollment
and bounded transport/execution with durable UNKNOWN handling and independent poststate.
Actual recovery, Policy B/AP07 qualification (including its unresolved key-use policy) and
separate access approval precede any Telegram opening. No fictitious R0 closes this gap.
The existing production/staging/V126 contracts are unchanged.
