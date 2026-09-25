# Prospective isolated authority epoch

This is a local security-contract extension for `PROSPECTIVE_ISOLATED_GENESIS`, based on
approved architecture proposal SHA-256
`9690f53c465f7110258f2272bba15496d1587b90c43ec1069773bd1eef8bba9e`.
It grants no installation, enrollment, producer execution, genesis, INIT, R0/G, V126,
data migration or deploy permission. A future operational use requires separately approved
real producers and current independently retained facts. Synthetic tests establish consumer
behavior only.

## Historical boundary

The existing target is still blocked by its legacy history. Run
`v126-cutover-20260909t025024z-724dbe93` remains UNKNOWN; run
`v126-cutover-20260909t113822z-f7828e09` retains the attributed historical
`FAILED_PRE_ACTIVE_CONFIG_MUTATION_EXTERNALLY_FENCED` state. Neither becomes a native
terminal, PASS, proof of cessation, nor authority for a new domain. Missing primary records
are not reconstructed. Existing legacy genesis still requires its complete admitted
inventory/disposition. The new path does not satisfy that requirement on the old target.

A new epoch is the digest of a canonical immutable descriptor, binding a genuinely distinct
domain, predecessor index, bootstrap source/tool closure, owner decision and control policy.
The predecessor index remains durable ancestry, including all known run/intent identifiers.
A renamed generation on shared controls is refused. No old lock, owner, receipt, action,
transaction counter, data, business account or credential is inherited.

## Closed independently acquired facts

`scripts/v126-authority-epoch.py` defines the version 2 schemas and validators. Canonical
serialization is ASCII JSON with sorted keys, compact separators and one trailing newline;
SHA-256 binds exact bytes. Unknown fields, duplicates, noncanonical JSON, booleans used as
integers, missing references and invalid clocks refuse.

An isolation proof covers all seven facets and ten required incoming control-edge classes:
compute/kernel; daemon/process and Docker requests; filesystem/storage writers; database
control; network/listener, credentials/principals and recovery dispatch; external API/bot/queue;
authority readers/writers. Each facet requires a complete controller enumeration and a
separately observed negative probe over its exact edge set. Unknown, omitted, extra, shared,
old-domain or stale capabilities refuse. Absence and a successful probe alone are insufficient.
The accepted base state permits no automatic import or connected business egress.

U pins protected C/high-water/P/clock readers and their identities. Requests cannot select
these readers. The existing attended U digest ceremony and separate C confirmation for each
fresh EARLY/LATE nonce remain mandatory. Content-addressed documents prove equality; their
operational origin comes from the independently enrolled readers. There is no public API
that turns a caller's `accepted=true`, fixture or local JSON into an operational authority.

The control ledger is separate from the unchanged DR ledger. It starts with a genuine
`EPOCH_ESTABLISHED` fact, then enrollment/producer facts, independently approved U,
activation and exact action authorization. Event payload types, sequence and hash links,
current head, high-water and revocation floors are closed. Independent retention manifests
and readbacks bind the checkpoint and immutable objects. P independently observes the
complete persistent transaction inventory. Missing, ambiguous or partial transactions block;
there is no automatic reseal, adoption or counter reset. Expired/revoked historical events
remain audit records but cannot authorize a current action.

Bootstrap U admits only isolated genesis and a separately approved copy of its durable
completion. It cannot authorize INIT, STAGE, a DR producer or a full-U permission. Full U
requires the same epoch/domain/predecessor and protected-selection policy, genuine separately
validated DR/custody/AP07/G facts, a typed prospective DR source identity, independent control
ledger attachment/approval, and a new server enrollment pin. It retains the existing DR
qualification, operational ledger and R0/Q checks. Q still requires its actual native stage7;
future Q is not an input to bootstrap or INIT.

## AP06 and transport

A prospective launcher configuration is version 2 and contains the exact `epoch` context,
`authority_scope` (`BOOTSTRAP_ONLY` or `FULL_DR`), independently approved public
`enrollment_facts`, and the bound server runtime descriptor. The context is exactly
`epoch_id`, `domain_identity_sha256`, `predecessor_index_sha256`.

The new distinct ed25519 principal must be excluded from prior/admin principal identities.
The dedicated root-owned sshd remains restricted to one principal, exact listener on port
2226 and the approved V peer, with no arbitrary command, forwarding, environment, PTY or
agent. The server proves the actual sshd/master/config/session lineage. Root-owned source,
module inventory, key file, sshd configuration and anchor pin are rechecked. Honest kernel,
sshd and approved controller ownership remain assumptions; this is not hostile-root attestation.

Both V and S require Python **3.12.3**. Public runtime descriptors bind role, resolved
executable path/hash, version, closure and provenance; each consumer compares its actual
interpreter. A merely matching version string or caller-supplied host association is insufficient.
No runtime provisioning or key generation is provided by these consumers.

Protocol v2 commits the epoch context in OPEN/CHALLENGE/RESULT. The full operation identity
adds `epoch_id` and `domain_identity_sha256`; its digest binds all auxiliary frames, remote
results and acknowledgements. Enrolled principal context must match. Protocol v1 stays closed;
legacy/prospective mixing, omission or downgrade refuses.

## Preparation, creation and consumers

`prepare-init` accepts the three explicit epoch hashes and emits native manifest version 2.
Approval binds that already prepared exact manifest. The public client commands are
`prepare-isolated-target-binding`, `bind-isolated-target` and
`copy-isolated-target-binding-completion`. Preparation adds an independently retained isolation
proof digest; it does not acquire authority, create a registry or approve its own proposal.
Nonce, timestamps and next INIT manifest identity are not regenerated after approval.

The authenticated launcher invokes the existing create-once writer. EARLY precedes server
writes and spool allocation. Linux namespace observation precedes mkdir; replacement including
away-and-back refuses before lock/owner/result. LATE uses the same permanent lock. The v3 owner
envelope, distinct lock marker, canonical request, intent, `prospective-basis.json` and result
preserve ancestry. A full pending result remains mode 0600 until confirmed file/directory
barriers and readback; partial roots are permanent UNKNOWN. Power-loss persistence of the final
publication mode is not claimed; rollback to 0600 remains conservative UNKNOWN without retry.

History, snapshot, INIT completion, copy, admission, recovery, reconciliation, retirement and
ordinary transfer validate the same ancestry. Removing completion or replacing the owner with a
legacy envelope cannot downgrade the lock marker. Old intents/run IDs cannot cross the boundary.
Native manifests, intents, Gate A/B/C authorizations, stage/recovery receipts and terminal records
carry the exact epoch; reconciliation and handoff retain it. Recovery uses the existing native
recovery authority and exact epoch owner; it does not inherit a legacy owner's execution rights.

Copy requires a fresh exact independent copy action, current revocations and an independently
pinned completion digest. It neither reruns genesis nor changes server history. INIT still
requires full U and its own R0; the first STAGE is BASELINE_VERIFIED. The 20 stages, UNKNOWN
handling, Gate A/B/C and existing recovery semantics remain mandatory. No application database
migration is introduced.

## Validation and operational limits

New core, authority, transport, history, native and owned-Linux suites are connected to the
existing CI entrypoints. They use explicitly synthetic public facts and disposable fixtures.
Old legacy/genesis/Policy B/DR/history/reconciliation suites and their assertions remain.
Standalone history and poststate copies embedded in cutover must be byte-identical.

Local qualification and independent review are tied to an exact uncommitted candidate manifest.
Publication, its exact feature/main CI, actual domain provision, independently controlled
producers/retention/clock, AP06 enrollment and prospective operational ceremonies remain separate
authorization gates. Prior integrated-green baseline CI does not qualify changed candidate bytes.
