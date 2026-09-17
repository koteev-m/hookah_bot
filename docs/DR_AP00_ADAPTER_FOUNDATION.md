# AP-00 production adapter foundation — HT-OPS-25

Local implementation only; **not provisioned, not DR PASS and not release authority**.
No real bucket, service account, key, credentials, custody or storage resource exists
merely because these modules exist. No provider transfer or operational evidence
acquisition has been executed. Accepted Policy B / D1–D4 remain in the
[deployment runbook](DEPLOYMENT_RUNBOOK.md#policy-b-disaster-recovery--ht-ops-04--ap-01).

## Dependencies and local validation

The existing Python scripts use stdlib and `unittest`, with no Python package manager
or shared dependency manifest. `scripts/v126-dr-requirements.txt` is an optional,
DR-specific runtime set for CPython 3.13: boto3/botocore 1.43.94, cryptography 50.0.1,
and exact transitive pins. Install only into a dedicated temporary venv. No global
pip/config change, vendoring, custom signing or application dependency change is needed.
AP-01 imports and `validate-document` still run without these dependencies.
Requesting SDK/AEAD functionality without them returns fixed
`AP00_S3_DEPENDENCY_UNAVAILABLE` / `AP00_CRYPTO_DEPENDENCY_UNAVAILABLE` errors.

```bash
python3 -m venv /private/tmp/ht-ops-25-ap00/venv
/private/tmp/ht-ops-25-ap00/venv/bin/python -m pip --isolated --disable-pip-version-check --no-cache-dir install --only-binary=:all: -r scripts/v126-dr-requirements.txt
/private/tmp/ht-ops-25-ap00/venv/bin/python -m pip check
/private/tmp/ht-ops-25-ap00/venv/bin/python scripts/test-v126-dr-adapters.py
/private/tmp/ht-ops-25-ap00/venv/bin/python scripts/test-v126-dr-workers.py
python3 -S scripts/test-v126-dr-evidence.py
python3 scripts/test-v126-database-evidence.py --unit
python3 scripts/test-v126-dr-evidence.py --postgres-docker
```

The last selection requires an already installed local PG17 image and a Unix-socket
Docker context. It runs the existing synthetic whole-DB fixture with no pull, host
mounts, public ports or network and removes only its owned containers. Its synthetic
DB dump/restore is test coverage, not AP-02/AP-04 execution. SDK tests intercept the
actual serialized HTTP request before network I/O and use botocore stubs for metadata.
HT-OPS-27 also feeds synthetic malformed HTTP bytes through the real SDK send,
urllib3 and http.client header parser using an in-memory socket; real socket
connections are forbidden. No Yandex endpoint is contacted by tests.
HT-OPS-31 replaces the HT-OPS-29 in-process logging ownership design before any
publication. Run the dedicated worker suite plus the preserved SDK/crypto/provisioning
suite and stdlib-only AP-01 suite. The worker suite executes instrumented temporary
source copies with network-denying audit hooks, actual SDK serialization and in-memory
malformed-header parsing. Adversarial children exercise framing, crash, EOF, timeout,
SIGTERM/SIGKILL and canary boundaries. It checks distinct PIDs/reaping, clean parent
imports/logging/reloads, actual available CPython 3.11 refusal, simulated non-CPython
and disabled/free-threaded GIL rejection, and Policy B binding of changed worker bytes.
Reuse PG17/database/diagnostic evidence only after checking unchanged source surfaces
and historical evidence hashes; no Docker or full V126 rerun follows from this boundary.
The optional adapter suite is local; this changeset does not install dependencies in
existing CI jobs or alter V126 workflows.

## Target and authority boundaries

`scripts/v126-dr-schema.py` remains the target schema authority. AP-00 requires
canonical target bytes plus their exact SHA-256 and narrows lock mode to COMPLIANCE.
Design defaults are Yandex Object Storage, Russia / `ru-central1`, STANDARD,
versioning and Object Lock enabled, seven days (604800 seconds), no lifecycle expiry.
Endpoint, region and bucket are explicit descriptor values, not inferred from SDK
configuration. Bucket/account/folder/SA identifiers and capacity/budget values remain
future provisioning decisions; no real values or descriptor instance is supplied here.

`scripts/v126-dr-s3.py` exposes `make_client`, `Writer`, `ExactReader` and
`MetadataObserver`. `make_client` now constructs a stdlib-only parent launcher,
retaining explicit caller credentials for its lifetime; `close()` drops its references.
No secure memory erasure is claimed. Each authority gets a separate launcher. Neither
import, construction nor a parent operation imports boto3/botocore/urllib3. There is
no direct parent SDK client/request path. Parent logging configuration is untouched.

### Dedicated worker runtime and launch

Every logical operation starts a fresh interpreter, executes the fixed neighboring
`scripts/v126-dr-s3-worker.py` script, receives one result and reaps that process.
There is no worker reuse, pool, daemon, service, scheduler or persistent manager.
Production never imports the worker as an operational library. Fixed sibling helper
loading follows existing script conventions; request data cannot select a source path.

The argv is `[sys.executable, '-I', '-S', '-B', absolute_worker_path]`, with no shell,
credential, ciphertext or key argument. The same interpreter executable and returned
exact `platform.python_version()` keep the Python patch tied to existing Policy B
`python_version` validation. Worker admission requires **CPython, major/minor exactly
3.13, an enabled GIL query and a non-free-threaded build**. Missing GIL information,
3.11, other implementations and free-threaded builds fail closed. Admission precedes
site-packages/.pth acquisition and all SDK imports/requests; `-I -S` prevents startup
user/site/PYTHONPATH imports. After runtime and complete request validation the worker
initializes the dedicated venv site-packages and acquires the pinned SDK. Admission
does not qualify every 3.13 patch release; exact source/runtime qualification remains
separate Policy B evidence, and operational clean immutable source remains required.

The parent passes only LANG/LC_ALL=`C` and `AWS_EC2_METADATA_DISABLED=true`, closes
uncontrolled inherited descriptors and uses pipes for stdin/stdout, DEVNULL for stderr.
An OS runtime may add a platform bookkeeping variable (observed macOS
`__CF_USER_TEXT_ENCODING`); no caller credential/profile/proxy/Python environment is
inherited. The child SDK still disables config/credential files, profile/metadata
resolution, endpoint overrides and proxies; credentials are supplied explicitly to
both the session and client. TLS verification, SigV4 and path addressing are retained.

`make_client(..., timeout=120.0)` accepts a finite local guard in `(0, 300]` seconds.
It covers spawn, concurrent pipe I/O, provider work and normal child exit. Cleanup
sends terminate, waits at most 0.2 seconds, then kills if needed and waits at most
2 seconds to reap. OS process primitives must function; cleanup failures also close
the operation with a fixed failure/UNKNOWN. This is a local execution guard, **not a
DR RTO**. Future AP-02/AP-03 orchestration must separately bind operation duration
and concurrent memory into qualification/freshness budgets.

### Closed IPC and diagnostics

`scripts/v126-dr-s3-ipc.py` defines one length-delimited frame in each direction:

| Part | Contract |
| --- | --- |
| Header | 16 bytes: `HS3W`, unsigned big-endian uint32 control length, uint64 body length |
| Control | 1–16384 bytes of canonical ASCII JSON with one LF; protocol version 1; exact field/type sets; duplicate/unknown/noncanonical input rejected |
| Request | `version`, `kind=request`, closed `operation`, canonical target object/hash, explicit credential fields, operation-specific arguments |
| Response | `version`, `kind=response`, operation, `SUCCESS`/`REJECTED`/`UNKNOWN`, allowlisted code, closed operation-specific value, exact runtime version |
| Body | Binary bytes without base64; encrypted-envelope request only for write, exact ciphertext response only for read; maximum 67110936 bytes; all other bodies empty |
| End | Exact body length then EOF; missing/truncated/extra/multiple frames and trailing bytes rejected |

Only fixed pre-admission rejections may carry null operation/runtime fields; they
cannot be success or provider authority. Operation enum: `write`, `read`,
`observe_version`, `observe_bucket`. Read requires
exact VersionId. Metadata observation preserves exact HEAD + GetObjectRetention in
one child; bucket observation preserves its two read-only requests in one child.
Existing read retries happen inside that logical operation. A write child issues at
most one PUT; it never performs HEAD/LIST/latest reconciliation or a second PUT.
Parent output buffering is bounded by header/control and the operation's body cap;
concurrent nonblocking pipe I/O prevents pipe deadlock. Worker reads have explicit
size bounds and are covered by the parent deadline. The worker rechecks control,
credentials, canonical target/hash, operation arguments and envelope structure.
No AES key or KeyProvider result enters IPC; encryption/decryption stays in the caller.
Request/credential/body data is never automatically logged, echoed or evidence-stored.
Only the intended exact ciphertext body and validated provider VersionId/metadata may
cross back; raw HTTP responses, URLs, headers, provider messages and tracebacks are
never diagnostic authority.

The worker privately duplicates its response pipe before SDK loading, redirects fd 1
and fd 2 to DEVNULL, and disables library logging globally **inside that one child**.
Ordinary Python/C/SDK stdout/stderr cannot become the protocol response. Parent also
discards child stderr, including startup/crash tracebacks. Unexpected protocol bytes
produce fixed errors without echo. Parent custom factories, handlers, public
`logging.setLogRecordFactory`, reloads and duplicate module loads have no child logging
ownership responsibility. HT-OPS-29's factory wrapper, public-setter code patch,
ContextVar, lock and active counter have been removed, superseded before publication;
they never became operational evidence. This is one-shot process separation, not a
claim of sandboxing arbitrary hostile code or shared application logging in a child.

| Outcome | Parent classification |
| --- | --- |
| Valid runtime/protocol/dependency/client rejection before provider | Fixed `TransportError` (`AP00_WORKER_RUNTIME_UNSUPPORTED`, `AP00_WORKER_PROTOCOL_INVALID`, `AP00_S3_DEPENDENCY_UNAVAILABLE`, `AP00_S3_CLIENT_INVALID`) |
| Valid known provider rejection | Existing allowlisted S3 error, including 403/404/409/412 |
| Valid ambiguous write result | Existing `WriteUnknown`, `outcome=UNKNOWN` |
| Process could not be spawned | Fixed `AP00_WORKER_UNAVAILABLE`, no dispatch |
| Write timeout/crash/signal/nonzero exit/missing or invalid IPC/extra output/cleanup failure after start | `AP00_WRITE_UNKNOWN_WORKER`; commit cannot be disproved; no automatic retry |
| Read/metadata timeout/crash/nonzero exit/invalid output/cleanup failure | Fixed `AP00_WORKER_UNAVAILABLE`; no partial result |

A success frame is accepted only after zero exit, exact EOF, schema/runtime validation
and exact-version/retention cross-checks. Even a valid frame followed by abnormal exit
is not success. Child stderr or exit text never changes these classifications.

### Source binding

Existing `v126-dr-evidence.py:tooling` includes every regular `scripts/v126-*` file,
including parent, worker and IPC. `verify_checkout` compares their exact hashes with
the bound tool inventory, plus source HEAD/tree and Python patch. Tests change actual
worker/IPC bytes in a temporary copy and require `SOURCE_TOOL_BINDING_MISMATCH`.
No required-tool/schema/external-contract migration or new operation authority is
needed. Local synthetic bindings do not authorize provider execution.

Separate client instances alone do not prove independent principals/failure domains.
Future reviewed IAM **and bucket policy** must establish:

- Writer and independent reader are separate authorities; observer is also separate
  for this descriptor version. Writer has no delete/admin/governance-bypass authority.
- Requests writing `bundles/*` require `If-None-Match: *`, enforced by bucket policy.
  IAM role names alone do not establish least privilege. `storage.uploader` includes
  read/list as well as upload; any retained extra capabilities must be declared and reviewed.
- The reader can retrieve an exact version without writer credentials; no public access.

## S3 contract

`Writer.create_once` accepts exact target hash, `bundles/<manifest_sha256>.enc`,
serialized envelope bytes, non-secret custody-version hash and an explicit UTC
retention deadline. It structurally checks envelope/context and rejects plaintext
archives. Only the crypto codec authenticates an envelope; writer syntax validation
is not decryption/key possession proof. The local wall clock rejects retention below
seven days from invocation; it is not an independently acquired AP-06 clock.

`If-None-Match: *` is **current-key conditional create**, not all-history uniqueness.
A successful conditional PUT does not prove the object key has never existed before:
a current delete marker can allow it while older versions remain. Object Lock protects
historical versions from covered deletion/alteration; it does not prohibit creating
a new version. Delete markers, other writers and administrative operations can
invalidate a historical-uniqueness assumption. AP-00 does not establish all-history
namespace uniqueness; downstream code must not infer it from conditional PUT success.
Exact VersionId + ciphertext identity remain the evidence authority. The existing
`create_once` API name, `bundle_create_only` descriptor field and
`AP00_S3_CREATE_ONLY_PRECONDITION` diagnostic retain compatibility and mean only this
current-key condition.

If AP-03 needs one logical upload/version per manifest key, it must first have
independently reviewed evidence/controls covering the relevant authorities and
protected interval: initial namespace/history state, prohibition or detection of
delete markers, version deletion/reuse controls and other creation authorities, or
an independently authoritative race-safe reservation/ledger invariant. A one-time
HEAD/LIST is not a race-safe proof. HT-OPS-27 implements no such future mechanism.

One SDK `PutObject` sends Content-Length, Content-MD5, If-None-Match `*`, STANDARD,
COMPLIANCE and ObjectLockRetainUntilDate. Success requires HTTP 200 and the provider
response's valid exact VersionId plus the existing response/region invariants. ETag
is ignored. Unexpected post-dispatch 2xx (including 201/202/204/206), missing/reserved/
malformed VersionId or success responses, write network/TLS failure and uncertain server errors
raise `WriteUnknown` (`outcome == 'UNKNOWN'`), never success. There is no retry,
list/latest lookup, receipt write or reconciliation. A later 412 cannot recover an
original VersionId or resolve ambiguity; neither can a later 409. 403/404/409/412
are explicit failures, with fixed diagnostic codes.

Every SDK client uses `total_max_attempts=1`. A first `needs-retry.s3` handler blocks
HTTP errors before the SDK region redirector, including its implicit HeadBucket;
a before-sign guard checks the endpoint, bucket path and signing region. Endpoint/
region mismatch fails closed. HTTP 5xx/408/429 on writes are conservatively UNKNOWN.
GET/HEAD/config calls alone retry transient network/408/429/500/502/503/504 failures,
with at most three attempts and 0.1/0.2-second delays. TLS errors, access denial,
missing exact versions, redirects and malformed metadata do not retry. A failed
stream is closed before retry; partial bytes never escape to the evidence layer.

`ExactReader.read_exact(target_sha256, key, version_id)` preserves the AP-01 protocol.
It always supplies bucket/key/VersionId, requires matching response VersionId,
rejects delete markers and bounds ContentLength/body size. The existing
`independent_readback` still checks ciphertext SHA/size, archive identity and members.
`MetadataObserver.observe_version` issues exact HEAD + GetObjectRetention and rejects
wrong version, missing/short/non-COMPLIANCE retention. `observe_bucket` requires
versioning/Object Lock enabled and default COMPLIANCE retention of at least seven days.
These returned facts are not operational Trust, independent observation receipts,
live availability qualification or readiness authority.

## Authenticated envelope and key boundary

`scripts/v126-dr-crypto.py` uses `cryptography` AESGCM with exactly 32 caller-supplied
key bytes. `KeyProvider.resolve(custody_version_sha256)` is the sole key boundary;
there is no production implementation. Tests use only a synthetic in-memory map.
No real key is generated, read or stored. Python secure memory zeroization is not claimed.

Format v1: four ASCII bytes `HTDR`, four-byte unsigned big-endian header length,
canonical ASCII JSON header with one LF, then ciphertext with the full 16-byte GCM
tag appended. Header maximum: 2048 bytes. Plaintext: 1–67108864 bytes (64 MiB).
The complete envelope is at most 67110936 bytes. This is a bounded, in-memory,
single-PUT foundation. The 64 MiB maximum is a **temporary enforced fail-closed bound**,
not a measured production requirement. Current operational bundle compatibility is
**NOT_PROVEN**. No streaming/chunked design has been selected; larger bundles would
require a separate scope/design decision, not an automatic limit increase.

The closed header contains `format_version=1`, `algorithm=AES-256-GCM`,
`target_sha256`, `manifest_sha256`, `object_key`, `custody_version_sha256`,
`nonce_hex` (24 lowercase hex characters), and `ciphertext_size` (including tag).
AAD is **the entire prefix and canonical header**, binding all of these fields.
Production encryption calls `os.urandom(12)` internally each time; callers cannot
supply a nonce. Only tests patch that call for deterministic vectors. Nonce collision
risk and per-key usage limits require future AP-07 key lifecycle decisions; a 4096
nonce test is a bounded regression, not a mathematical uniqueness guarantee.

`Envelope` is constructed with the expected context independently of the input header.
The manifest/key relationship is validated. Decrypt verifies exact header/context,
version/algorithm, canonical serialization, lengths and authentication before returning
any plaintext. Unknown/duplicate/missing fields, bad tag/key/AAD, truncation or oversize
fail closed. Optional AP-01 encryption metadata is checked against the codec's source
hash and custody reference. No change to AP-01 `Encryption` or offhost schema is needed.
The SHA-256 and size of the **complete serialized envelope** identify ciphertext.

## Mandatory pre-AP-03 capacity compatibility gate

**AP-03 real transfer must not start until this gate is PASS.** The gate is a future
operational evidence requirement, not evidence acquired by AP-00 or HT-OPS-27.
Before a real bundle may be transferred, reviewed evidence must establish all of:

1. Actual **whole archive** byte size produced by the relevant AP-02/R0/Q
   source-capture path, including all members and archive overhead.
2. Exact **serialized encrypted-envelope** size for that archive and bound context,
   including prefix, canonical header and GCM tag.
3. A conservative documented growth/headroom rule, its protected operating interval
   and requalification trigger; one current measurement alone is insufficient.
4. Compatibility of measured and projected bounds with enforced plaintext
   `67108864`-byte and envelope `67110936`-byte maxima. The limit stays unchanged.
5. Bounded peak operational memory on **Linux/amd64**, including capture/codec/caller
   copies and concurrent work, with conservative headroom against actual limits.
6. Bounded encryption **and decryption** times compatible with the qualification
   budget under representative operational conditions.
7. Target storage-cap and retention-budget compatibility, including envelope growth,
   retained versions and the applicable retention interval; no unproven expiry saving.
8. Fail-closed admission behavior: absent, stale/unbounded or incompatible evidence
   yields `AP03_CAPACITY_COMPATIBILITY_NOT_PROVEN`; real transfer must not start.

Current gate status: **NOT_PROVEN / `AP03_CAPACITY_COMPATIBILITY_NOT_PROVEN`**.
Local 64 MiB synthetic boundary tests or synthetic RSS measurements are not proof
of operational capacity, actual archive size or Linux/amd64 compatibility. No staging,
source capture, backup or provider transfer is authorized to fill this gap here.
Future AP-03 admission must enforce this mandatory contract before its first real
transfer; this foundation does not implement an AP-03 executor.

## Non-secret provisioning descriptor

`scripts/v126-dr-provisioning.py` contains the executable closed v1
`ap00-provisioning` schema and parser (stdlib only), following the existing schema
convention. Canonical JSON + LF makes it content-addressable. It refers to the
existing exact target hash, capacity cap in bytes, monthly budget in minor RUB units,
exact policy byte hash/size, conditional-write enforcement, disabled public access,
lifecycle state, three distinct principal/capability descriptor hashes, credential
mechanism categories and configuration observation hash. No access-key IDs, secret
URIs, credentials, keys or business payload are fields. All unknown fields are rejected.

`parse(raw, target_raw, target_sha256, policy_bytes)` checks canonical shape and
references, positive integer caps/budget, distinct principal/capability identities,
role capability contradictions, forbidden public/write/delete/admin/bypass states and
absence of lifecycle expiry. Policy bytes are external to the descriptor and hashed
exactly, never echoed. The parser verifies byte identity, **not policy semantics**.
Hashes and enforcement flags are declarations, not proof that provisioning occurred.
Future independent review must inspect policy semantics and acquire configuration facts.
R0/Q consumers do not require this descriptor. Binding it and the optional dependency
inventory into independently trusted operational evidence is a future integration gate;
no schema migration or operational receipt generation is hidden in AP-00.

## Remaining packages

- AP-02 remains unimplemented/unauthorized: no operational PostgreSQL snapshot,
  exported snapshot or quiesced R0/Q orchestration, pg_dump/pg_dumpall wiring,
  intent/result producer sequence or cutover dispatch.
- AP-03 real encrypted transfer and independent provider read-back remain
  unexecuted/unauthorized and blocked until the mandatory capacity compatibility gate
  above is PASS; local adapter tests do not qualify a recovery point.
- AP-06 remains unimplemented/unauthorized: no independent clock, availability,
  ledger-head or credential/custody probes; no operational Trust construction,
  readiness/qualification wiring or evidence receipt writes.
- AP-07 remains unimplemented/unauthorized: no KMS/HSM/provider keys, password
  manager, key storage/retrieval service, secret/config/image/evidence custody.

Next gate: independent security review of the exact HT-OPS-31 dedicated-worker
changeset before any publication or provisioning. F2–F4 and crypto/provisioning
contracts are preserved. No deploy/staging/V126 action is implied.

## Implementation references

Concrete API checks only, without repeating provider research:
[Yandex PutObject](https://yandex.cloud/en/docs/storage/s3/api-ref/object/upload)
for conditional PUT, Content-MD5 and Object Lock headers;
[Yandex GetObjectRetention](https://yandex.cloud/en/docs/storage/s3/api-ref/object/getobjectretention)
for required exact VersionId and retention response;
[cryptography AESGCM](https://cryptography.io/en/latest/hazmat/primitives/aead/#cryptography.hazmat.primitives.ciphers.aead.AESGCM)
for the nonce, AAD and full-tag authenticated decrypt API. The pinned installed
botocore model and serialized-request tests establish the exact SDK field mapping.
