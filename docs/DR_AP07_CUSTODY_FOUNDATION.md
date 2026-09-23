# AP-07 recovery custody foundation — HT-OPS-41

Scope: provider-neutral local preparation under staging/pilot Policy B. This is
**not provisioned custody, retrieval execution, DR PASS or release authority**.
The [Policy B contract](DEPLOYMENT_RUNBOOK.md#policy-b-disaster-recovery--ht-ops-04--ap-01)
owns D1–D4, DR-A…G, RPO and package authority. This document owns AP-07 inventory,
custody topology, lifecycle and retrieval requirements. The executable schema is
[`v126-dr-custody.py`](../scripts/v126-dr-custody.py), following the AP-00 local
closed-schema convention; existing AP-01 schemas/Trust/consumers are unchanged.

The approved attended AP-06 reader integration is described in
[V126 cutover contract](V126_STAGING_CUTOVER_CONTRACT.md#ht-qr-01-policy-b--attended-authority-and-same-lock-dispatch).
An independently enrolled C confirms the current catalogue for each fresh nonce;
that confirmation does not establish retrieval/restore/ongoing availability. V still
validates exact AP-07 proof and separately enrolled P observations under this document's
custody threat model. This local integration neither enrolls real custody nor runs producers.

## Threat boundary

**IN_SCOPE:** complete VPS loss, missing VPS configuration, revoked or compromised
Writer/Reader/Observer credentials separately or together, loss of Yandex Object
Storage credentials while account recovery remains possible, loss of the developer
checkout/laptop, one custody location loss, accidental deletion, ransomware on an
online device, missing decryption authority despite retained backup bytes, unknown
image/config despite a surviving key, replay of old versions, and recovery of the
wrong secret/config/image. Storage-only attackers must not obtain decryption
authority. Theft of a locked custody package must not reveal its contents.

**OUT_OF_SCOPE:** simultaneous destruction of all independent packages and unlock
routes; compromised recovery operator/clean environment during secret use; malicious
custodian; mandatory provider account closure or provider loss with no remaining
backup bytes (D1 does not promise multi-provider DB storage); guaranteed external
media recovery; numeric RTO; exactly-once Telegram effects; automatic disaster
recovery or production multi-party/HSM policy. No threshold sharing is introduced.

**ACCEPTED_RESIDUAL_RISK:** one complete unlocked package exposes all secrets it
contains; an already stolen key cannot be revoked cryptographically for historical
ciphertext. A runtime compromise may expose runtime secrets or a temporarily used
backup key. Metadata labels cannot prove physical/account independence. Offline
media can fail and human custody can drift. Authorized retrieval and refresh detect
these failures; two filenames or two cloud folders do not establish independence.
Loss of one route leaves recovery possible but prevents healthy two-copy qualification.

| Failure | Surviving recovery route / boundary |
| --- | --- |
| VPS and local `.env` gone | A or B + account recovery + exact retained backup object |
| Writer revoked/compromised | Revoke separately; Reader or regenerated scoped Reader; A/B keys unaffected |
| Reader revoked/compromised | Independent account control issues replacement Reader; no Writer escalation |
| Observer revoked/compromised | Replace Observer independently; metadata cannot substitute for GET/decrypt |
| All S3 credentials lost | Account-owner recovery outside those credentials, then new scoped access; without account control availability is unproven |
| Checkout/laptop and A lost to ransomware/deletion | Disconnected offsite B, B unlock route and a clean environment |
| B location lost | A and its separately retained unlock; restore redundancy before new healthy qualification |
| Backup bytes present, no working key | Fail; ciphertext hashes are not decrypt authority |
| Key present, wrong/missing config/image | Fail exact binding; no tag/latest or rebuild assumption |
| Storage attacker only | Authenticated ciphertext remains confidential; integrity/deletion risks require D1/AP-03/AP-06 controls |
| One locked package stolen | Separate unlock protects contents; lost-media response and replacement needed |
| Old key/config/package replay | Independent current-set and retained-point pins reject substitution |

## Inventory from current code

Classes below describe the underlying material. Public metadata is reviewed before
publication; a digest does not declassify sensitive material. A secret's version is
an opaque identity assigned independently of its bytes. Never hash secret values,
credential URLs, complete `.env` files or restricted configuration into AP-07
metadata. Hashes may identify sanitized non-secret descriptors, approved public
artifact bytes and encrypted packages only. No URI/locator field exists for secrets.

| Logical asset / actual source | Class; preserve | Regeneration, readers and loss/leak consequences |
| --- | --- | --- |
| Backup AEAD key; `v126-dr-crypto.py:KeyProvider.resolve` | SECRET; exact historical 32-byte key plus immutable non-secret key-version descriptor | Created by separately authorized user ceremony; approved backup process uses in memory, recovery operator decrypts. Cannot regenerate a retained key; loss destroys its decrypt path, leakage exposes matching ciphertext |
| DB authentication; Compose, JDBC config, password-free globals recipe | SECRET; exact intended app/restore credentials for the point | User/operator creates; PG/backend read normally, isolated DR reads later. New password is possible only with explicit reconfiguration/requalification; it is not proof of the original real-auth path |
| `TELEGRAM_BOT_TOKEN`; `MiniAppAuthRoutes` | SECRET; point-bound version | Backend uses it for Mini App signatures even when bot polling is disabled. Owner/BotFather may replace prospectively; missing/replaced token invalidates current auth, leakage controls bot authority |
| `API_SESSION_JWT_SECRET`; `SessionTokenService`, gift decision scope tokens | SECRET; exact version for state-preserving proof | Backend and DR use; replacement invalidates sessions/scoped tokens. Loss requires approved invalidation; leakage permits forged tokens |
| `VENUE_STAFF_INVITE_SECRET_PEPPER`; `StaffInviteRepository` | SECRET; exact historical pepper while relevant invitations exist | Backend HMAC verification and DR; replacement breaks pending/claimed invitation token verification and requires explicit reconciliation/reissue |
| `TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER`; `StaffChatLinkCodeRepository` | SECRET; exact version | Backend and DR; replacement invalidates link codes. Leaked pepper weakens token verification; it is not itself a Telegram access token |
| `TELEGRAM_WEBHOOK_SECRET_TOKEN` | SECRET; conditional on bound webhook mode | Long-polling baseline does not require it; webhook recovery does. New secret needs coordinated provider setup after separate authorization |
| Config; template, `application.conf`, Compose, PG/HBA/ident/catalog facts | NON_SECRET_SENSITIVE; protected exact effective config plus public sanitized profile | Operator selects; normal backend/PG and DR consume. Includes owner/maintenance identities, flags, database names/topology, locale/extensions and nondefault values. Templates reconstruct defaults only. Loss blocks exact config proof; leak reveals operational/identity data |
| Backend and PostgreSQL image bytes | NON_SECRET_SENSITIVE if private; PUBLIC_METADATA only after publication/secret-free review; exact bytes + image identity/platform | Build/release process creates; deployment and DR consume. Retain both independently of registry/VPS. Mutable `postgres:17`, tag, source SHA or reproducible-build recipe alone does not prove availability |
| Source/tools/schema/recipe | NON_SECRET_SENSITIVE if private, otherwise PUBLIC_METADATA; full source and dependency/tool bytes + exact identity | Reviewed repository/build inputs; DR consumes. Include migration tree/Flyway recipe, full V126 tooling and exact interpreter/dependencies. GitHub/local checkout is not sole custody |
| Deployment record | NON_SECRET_SENSITIVE; protected record + sanitized binding | Operator owns; deploy and DR consume. Existing ordinary-deploy descriptor carries restricted hashes/paths: do not copy them into AP-07 public JSON. Refer to its sealed bytes and an explicitly sanitized non-secret deployment record |
| Evidence chain, qualification, retained-point index | NON_SECRET_SENSITIVE; exact complete bytes | AP-02…06 create, recovery verifier reads. Preserve ledger predecessors, Q holds and current qualification inputs; losing them prevents trustworthy selection even if DB bytes survive |
| Independent Trust bootstrap/current head pins | NON_SECRET_SENSITIVE; independently retained observations/instructions | User/AP-06 acquires, recovery verifier consumes; not manufactured from the package being verified. Losing freshness/authenticity prevents readiness; copied JSON is not Trust |
| Provider target/provisioning descriptors | NON_SECRET_SENSITIVE; exact approved bytes, sanitized target digest | AP-00 owner creates, Writer/Reader/Observer/DR use. No raw access keys or credential locators; changed target needs requalification |
| Account recovery authority and instructions | NON_SECRET_SENSITIVE instructions/capability references in A/B; SECRET factors in separate account-recovery custody | User owns, only recovery/account operator reads. Preserve independent control sufficient to revoke/regenerate S3 principals; S3 key copies alone do not supply it. Never package account-recovery factors with backup decryption keys |
| Recovery instructions and custody unlock bootstrap | Protected instructions, secret unlock material kept separately | User owns; DR reads without VPS/checkout/cloud login. Include offline tool access and independently pinned current-set identity. Never package the sole unlock key with its ciphertext |

The exact backend/PG image and public source classifications require inspection at
the future ceremony. If an artifact embeds secrets, quarantine it; this schema must
not be widened to publish its plaintext digest. Deployment record/config epoch are
sanitized metadata identities, not `.env` or database URI hashes.

Baseline derives from `docs/env/staging.env.example` and code, not live staging.
AI/geodata are disabled and billing is fake in that profile. Enabled integrations
require additional reviewed custody inventory before operational use; this foundation
must fail closed on unsupported profiles. Domains/certificates are not required for
isolated D/E: the public hostname is configuration; normal service reopening needs
separate DNS/registrar control and certificate issuance. Existing TLS private keys
need not be escrowed for isolated recovery. Writer/Reader/Observer keys can be
revoked/regenerated; their exact historical bytes are not a DB decryption dependency.

For all exact assets: no automatic expiry/deletion. Retain as long as any qualified
point requires them: seven days, at least the last two points even when older, and
Q points until a separate retention decision. Account credentials have provider
expiry independent of artifact retention. Record/recheck expiry during retrieval;
expiry never silently converts an exact asset into a regenerable one.

## Chosen provider-neutral topology

Prefer two **separately encrypted, versioned recovery packages** with no recurring
service dependency: A user-controlled primary storage; B disconnected removable
storage at a different physical location, with an independent unlock route. Use a
maintained encryption tool at the later ceremony; age passphrase-encrypted packages
are a practical candidate, not a newly implemented cipher or an installed service.
No purchase or paid password-manager selection is needed for the local foundation.

Each package contains the necessary protected inventory and exact image/source/tool
bytes, or its own complete independently retrievable set of byte artifacts. A mere
registry reference is insufficient. Retain an offline usable recovery tool distribution
and its authenticated identity. A/B each has a recovery-readable instruction copy
and independent current-set pin outside the locked package to avoid bootstrap cycles.
Unlock A and unlock B are distinct; their protected backups must survive their
corresponding laptop/account loss and must not reside beside the encrypted media.
Never connect writable A and B simultaneously on a potentially compromised machine.

Provider-account recovery codes, MFA seeds and administrative passwords use separate
user-controlled custody, outside packages containing DB decryption keys. A/B contain
only its protected instructions and capability/version references. The ceremony must
verify this authority is usable without consuming one-time codes or changing the
account, and losing one location/device leaves an independent route. No actual
provider recovery mechanism is assumed from public docs.

C is a runtime/VPS copy only for needed application secrets/config. The backup key
has no ordinary backend role: provide it transiently to a separately approved
capture/decrypt process when necessary. C never counts as A or B. No persistent
key-provider service, automatic export or runtime hydration is installed here.

| Failure-domain dimension | Required declaration and later independent check |
| --- | --- |
| Account | A and B have no common account login/recovery dependency; offline route may have none. Backup-account owner control is separate from S3 principals |
| Device | Different media/devices; neither sole reader nor unlock route requires the original laptop/VPS |
| Provider | B can work with no network provider; a second folder/account at the same service is not independent provider custody |
| Credential | A/B unlock credentials distinct from each other, VPS and all backup credentials; no sole shared MFA/password-manager account |
| Physical | Separate locations and separate unlock storage; one site loss cannot remove both complete routes |
| Encryption key | Distinct package wrapping/unlock keys; this is not the DB key, whose exact bytes intentionally agree across copies |

Opaque identifiers name dependency equivalence classes, not hashes of credentials.
For each dimension, shared direct or indirect dependencies must use the same
equivalence-class identity: storage login, unlock vault, recovery email and MFA
cannot hide behind separate labels. Unknown dependencies fail admission.
Null account/provider means genuinely absent dependency, never unknown. Physical
facts and transitive recovery dependencies require user inspection; assigning two
different labels proves nothing. The independently observed map must cover unlock
backups, MFA/recovery codes, media readers and tool availability. Recovery instructions
and trust pins cannot depend solely on the same locked package they select/unlock.

## Lifecycle and retention

1. **Create:** separately authorize actual generation/import; assign immutable logical
   IDs/versions; build protected packages and keep all sensitive data out of evidence.
2. **Verify:** retrieve each route on a clean environment, use each secret through a
   bounded operation, compare non-secret/artifact identities and independently pin proof.
3. **Activate:** publish a user-pinned current set only after both routes are verified.
   A syntactically valid declaration does not activate custody. AP-06 must obtain
   fresh observations before qualification/readiness.
4. **Rotate:** new immutable asset version and successor set; keep predecessor lineage.
   Verify A/B before switching new encryption/config generation. Never overwrite the
   only older key/copy. The overlap lasts until every retained point can be recovered.
5. **Retire:** stop new use; exact historical assets remain decrypt/restore-only for
   independently pinned retained requirements. Seven days alone is insufficient.
6. **Revoke:** reject new/current proof immediately. Retain forensic bytes safely;
   never destroy the only historical key. Compromise may make availability/confidentiality
   unprovable and requires an explicit incident decision, not automatic cleanup.
7. **DR retrieve:** select an exact pinned set/point and surviving route; a one-route
   result is degraded retrieval, not proof that redundancy is healthy.
8. **After recovery:** separately rotate exposed runtime/account credentials, reconcile
   sessions/invitations, requalify new points and restore two verified copies before
   claiming normal custody health. Keep historical keys/config until release of all holds.

AES-GCM envelope v1 retains its internal random 96-bit nonce and full 128-bit tag.
Nonce collision risk and per-key usage require an explicit lifecycle decision before
operational producer use. **Unapproved proposal:** rotate before 365 days or 65536
encryption invocations across all users of a key, whichever comes first; never reset
the count across copies/runners, count abandoned attempts too, and stop new encryption
if the count is unknown. These bounds and unknown-count handling are neither accepted
project policy nor implemented AP-00 admission checks. The lifecycle decision and
reviewed producer enforcement remain prerequisites. Rotation never expires decrypt-only
historical access. No automatic destructive cleanup is added.

Keep each retained point's original AP-01 custody document and five group references
in the evidence chain. Current `group_versions()` output never replaces historical
references. Derive historical requirements from that point's independently pinned
binding/ledger; AP-00 resolves its original key-version digest.
Retained requirements distinguish data dependencies from access mechanisms. Keep
historical key/config/auth/pepper/image/evidence versions required by the point;
do not require revoked historical S3/account credentials. A separately verified
current replacement account-control route may retrieve the same retained objects.
The protected `provider-account-recovery` asset contains instructions/capability
references, never historical S3 credentials or live account factors.

## Executable local contract

`parse_set(raw)` validates declaration shape/canonical bytes only. `validate_set(raw,
pins, now)` additionally requires a fresh independently observed current catalogue,
exact set/version/binding and complete active/retained asset coverage. No secret
payload or credential locator is accepted. `validate_retrieval(set_raw, proof_raw,
pins, observations, now)` checks bounded operations against caller-owned observations;
there is no JSON-to-Trust loader. `group_versions(doc)` derives AP-01's five
declaration references without granting availability.

- `ap07-asset-version` has logical ID, positive integer version, creation time and
  predecessor identity. Its canonical metadata digest remains stable when a key
  becomes historical-only or its package changes. Stored lineage rejects the wrong
  predecessor role/version/time and a current version older than listed history.
  Missing out-of-retention predecessor bytes and set succession remain independently
  authenticated catalogue references; the parser cannot prove an absent transition.
- `ap07-custody-set` has set ID/version/state/predecessor, exact binding, assets and
  A/B copies. Each asset has class, preservation/regeneration policy, lifecycle,
  eligibility, expiry and its original binding. Copies contain exact ciphertext
  identity, sorted unique membership, creation/declaration-verification times and
  the six failure domains. A missing required member/copy or shared domain fails.
- Binding covers Policy B, tool source SHA/tree, complete tooling digest, Python
  version, DB semantics, recipe, target, sanitized deployment record, Telegram mode
  and the supported disabled-AI/geodata/fake-billing profile. A separate exact
  runtime binds application source/tree, backend/PG images, migration tree and
  config epoch. Tool checkout and application release may differ; the one runtime
  projection represents AP-01's required equal data/restore runtimes. All fields
  must match independent approved pins, never values copied from the candidate.
- `CustodyPins` supplies current-set, active-version and complete retained-version
  pins, plus a fresh independently acquired catalogue clock. `RetrievalObservations`
  pins exact proof, environment, independently inspected topology, ciphertext
  packages and every completed `(copy, asset-version, operation, evidence)` tuple.
  Receipt/catalogue freshness is at most 300 seconds including clock uncertainty;
  future/rollback/ambiguous clocks, expired proof and pre-creation observations fail.
- The topology observation covers transitive account/device/provider/credential/
  physical/wrapping-key dependencies. A proof must cover every current and retained
  required asset on each selected route. Public artifact byte hashes are permitted;
  private artifacts use sealed bytes and bound identity, with no public plaintext
  digest. Secret and restricted configuration digests are prohibited fields.

Serialization reuses AP-01: sorted JSON keys, ASCII-escaped UTF-8, compact separators,
one LF; no NaN, duplicate/unknown/missing fields, coercion or alternate serialization.
Size is capped at 1 MiB and 256 assets. Errors are fixed
`AP07_DECLARATION_INVALID`, `AP07_SET_NOT_CURRENT_OR_INCOMPLETE` or
`AP07_RETRIEVAL_NOT_PROVEN`, with no input values in diagnostics.

The read-only CLI takes one explicitly selected absolute regular JSON path:

```text
python3 -S scripts/v126-dr-custody.py /absolute/path/to/nonsecret-custody-set.json
```

Success prints only `AP07_DECLARATION_VALID_ONLY_NOT_CUSTODY_PASS`. Symlink/FIFO,
multiple-link, unstable or oversized input fails through the existing safe reader.
Retrieval APIs return `AP07_RETRIEVAL_OBSERVATIONS_VALID_ONLY` for two verified
routes or `AP07_DEGRADED_RETRIEVAL_VALID_ONLY` for one usable route. Neither is
operational PASS. Declared verification timestamps are never retrieval evidence.
Fresh catalogue state may mark the other copy retired/revoked: selected revoked
copies always fail, but one current surviving route can still validate degraded
retrieval. `validate_set` continues to reject that state for two-copy health.
These helpers add no secret-writing, networking, provider, restore or cleanup CLI.

## Retrieval proof and safe operations

DECLARED means closed canonical metadata passed local validation. PROVEN retrieval
requires separately acquired, fresh observations tied to exact current set, retained
requirements, binding, route and proof bytes. Neither a JSON `PASS` flag nor a
recomputed hash establishes availability or key possession. The local API has no
operational observation loader and does not construct AP-01 Trust or DR PASS.

Future authorized ceremony, bounded per asset:

| Asset | Required use/verification without logging material |
| --- | --- |
| Package | Retrieve via named route, verify ciphertext hash/size, unlock offline; verify protected manifest membership and immutable versions |
| DB key | Decrypt an independently pinned known authenticated envelope with exact AP-00 context; full tag/member equality must pass. Merely encrypting/decrypting a self-generated challenge with an arbitrary key is insufficient |
| DB auth | Intended app role through isolated TCP/JDBC route, plus wrong-password denial and HBA/ident proof; no trust-auth substitution |
| Telegram/JWT | Offline Mini App signature check, JWT/scoped-token positive/negative/expiry checks under exact pinned version; no Telegram calls |
| Peppers | Verify point-bound fixture/retained token semantics inside protected isolated environment; replacement pepper cannot declare old values valid |
| Config/deployment | Check exact protected version, sanctioned sanitized binding and effective configuration; no env/URI/secret hashes exported |
| Images/source/tools | Retrieve bytes, validate exact image/platform/source/tool/recipe identities; do not treat a successful registry login as image availability |
| Evidence/Trust/account recovery | Retrieve complete chain/instructions; compare independent head/set/retention pins; confirm usable account recovery mechanism separately without consuming recovery codes or making provider mutations |

Authorization must cover secret hydration and use before any of these real operations.
Prove D3 isolation before hydration; fetch permitted public tools/ciphertext first,
then deny staging/provider/Telegram/public ingress and egress. Keep material in the
bounded protected process/environment; emit only fixed results and permitted metadata.
Use no shell tracing, argv secrets, raw payload logging or plaintext secret digests.
Python memory zeroization is not guaranteed. Cleanup or explicitly authorized sealed
retention is part of AP-04; cleanup failure cannot produce qualification.

## Executable disaster walkthrough: VPS lost completely

This is an execution plan for later authorization, not a live restore performed by
HT-OPS-41. The [whole-DB recipe](V126_DATABASE_RECOVERY_REHEARSAL.md) owns SQL and
functional details. Every failure below stops the corresponding recovery gate.

| Step | Required custody input / source | Verification; failure; forbidden shortcut |
| --- | --- | --- |
| 1. Bootstrap clean environment | Surviving A/B instructions, unlock route, independent current-set/ledger pins, offline source/tools and backend/PG images | Pin exact SHA/tree/tool/schema/image/platform; missing byte artifact fails. No deceased VPS, `latest`, mutable PG tag, arbitrary rebuild or unauthenticated package-selected trust root |
| 2. Recover config | Protected config/deployment version plus sanitized binding from same route | Verify point-bound epoch, PG locale/extensions/auth topology and intended safe overlay; no `.env` recreation from memory/defaults or guessed owner IDs |
| 3. Recover decrypt authority | Exact immutable key version retained for selected point | Authorized isolated authenticated decrypt verification, independent of S3 credentials; wrong/revoked/missing key fails. No plaintext archive fallback |
| 4. Obtain backup bytes | Exact target/VersionId/cipher identity and independently restored scoped Reader authority | AP-03 exact GET, retention and full read-back; credentials may be regenerated via independent account control. No Writer credential promotion, ETag/latest or producer cache |
| 5. Restore DB | Downloaded bytes only, password-free globals/bootstrap identity, PG image, roles/HBA/ident and exact auth | D3 empty Linux/amd64 target, full SQL/equality/Flyway/queues checks; no selective restore, ignored SQL errors, `--no-owner` shortcut or migration repair |
| 6. Start isolated backend | Exact image, startup secrets/config and safe disabled-worker overlay | No pending migration; real-auth and negative checks; no public ports, production volumes or provider egress |
| 7. Functional prerequisites | Current proof, independent Trust observations, test-owned actors and cleanup authority | AP-04 named checks and AP-06 qualification/freshness; missing authority/cleanup fails. No synthetic PASS relabelled operational, writer reopening or deploy implied |

The order distinguishes obtaining encrypted bytes from hydrating secrets: public
tool/ciphertext acquisition may happen before isolation; secret operations always
wait for D3 isolation. Where step 3 needs the actual retained envelope, fetch its
ciphertext in step 4 first, then perform step 3 verification before any restore.

## Package boundaries and remaining action

- AP-00 owns target/provisioning, AEAD and injected KeyProvider. This foundation
  defines what its version reference means; no KMS/HSM/KeyProvider service is created.
- AP-01 owns canonical evidence, bindings and Trust validation. AP-07 supplies
  declaration/proof validation helpers only; no automatic readiness integration.
- AP-03 still needs its own capacity/IAM/transfer authorization and exact read-back.
  HT-OPS-39's Yandex support/IAM blocker and evidence are untouched.
- AP-04 owns real secret hydration, restore, functional check execution and cleanup.
- AP-06 owns independent current observations and freshness, ledger and Trust pins.
  Custody metadata never grants AP-06 authority or implements its acquisition adapter.

**Next external decision:** the user explicitly approves or revises the proposed
key lifecycle policy, including limits, counting and unknown-count handling, before
operational producer use. A bounded A/B custody ceremony remains separately
unauthorized: supply the two actual independent storage/unlock routes, prepare the
real protected assets, then retrieve/verify both on a clean isolated environment.
No real secret creation/import/export, device write, account/service change or paid
choice is authorized by this local task. Ceremony authorization alone does not accept
the proposed lifecycle policy or waive AP-00/03/04/06 or release gates.

Local PASS establishes inventory/design/closed validation and synthetic regressions.
It does not establish secret existence, operator access, real physical independence,
correct account recovery, image availability, retained-backup decryptability, runtime
deployment, operational custody/DR/R0/G, staging smoke or production readiness.

## Official technology basis

Public documentation checked **2026-09-17**. `DOC_CONFIRMED`: [age](https://github.com/FiloSottile/age)
supports file/passphrase encryption and offline binary distribution;
[KeePassXC](https://keepassxc.org/) provides a free offline encrypted vault;
[AWS KMS rotation](https://docs.aws.amazon.com/kms/latest/developerguide/rotate-keys.html)
preserves prior material, but [deletion/account loss](https://docs.aws.amazon.com/kms/latest/developerguide/deleting-keys.html)
can remove access, and [pricing](https://aws.amazon.com/kms/pricing/) adds recurring
key/API charges. `INFERRED`: offline A/B packages fit this pilot with fewer external
dependencies than a new KMS. Actual media, account recovery and retrieval usability
are `UNKNOWN`; their independent verification is a `PROJECT_REQUIREMENT`.

[NIST SP 800-38D §8](https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38d.pdf)
and [cryptography AESGCM](https://cryptography.io/en/latest/hazmat/primitives/aead/#cryptography.hazmat.primitives.ciphers.aead.AESGCM)
support nonce-uniqueness/tag requirements; the 365-day/65536-use bounds and unknown-count
handling above are an unapproved proposal, not an accepted project choice or limits
prescribed by these sources. The complete three-option comparison, cost/lockout/
export/MFA/binary/offline/automation analysis and source registry are retained in
the task's `evidence/external-options.md`; no service was selected or purchased.

HT-OPS-41 checkpoint and exact local validation/diff evidence are in the task-owned
`/private/tmp/ht-ops-41-ap07-custody/evidence/REPORT.md`. `PROJECT_STATUS.md` remains
unchanged to avoid HT-OPS-39 concurrent ownership; after that task finishes reconcile
only AP-07 local-foundation status and preserve its provider blocker/operational limits.
