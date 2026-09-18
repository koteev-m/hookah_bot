#!/usr/bin/env python3
"""AP-07 closed declarations and caller-observation validation; no live custody.

No key acquisition, secret serialization, provider adapter, network, mutation or
operational PASS producer. Version digests identify NON-SECRET descriptors, never
plaintext secret bytes. An independent caller must acquire every pin/observation;
constructing these Python objects from the declarations is not verification.
"""
import argparse
from dataclasses import dataclass
import importlib.util
from pathlib import Path
import sys

sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location('dr_evidence', Path(__file__).with_name('v126-dr-evidence.py'))
dr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dr)
S = dr.schema
MAX_DOCUMENT_BYTES = 1024 * 1024
MAX_ASSETS = 256
MAX_OBSERVATION_AGE = 300
DOMAINS = ('account', 'device', 'provider', 'credential', 'physical', 'wrapping_key')
DOMAIN = {name: S.optional('id') if name in ('account', 'provider') else 'id' for name in DOMAINS}
SECRET = 'SECRET'
SENSITIVE = 'NON_SECRET_SENSITIVE'
PUBLIC = 'PUBLIC_METADATA'
# (classification, future regeneration rule, bounded retrieval operation)
ROLES = {
    'backup-key': (SECRET, 'never-while-retained', 'ap00-authenticated-decrypt'),
    'db-auth': (SECRET, 'authorized-requalification', 'isolated-db-auth'),
    'telegram-token': (SECRET, 'authorized-requalification', 'isolated-miniapp-hmac'),
    'jwt-secret': (SECRET, 'authorized-requalification', 'isolated-jwt-roundtrip'),
    'staff-invite-pepper': (SECRET, 'never-while-retained', 'isolated-hmac-record-check'),
    'staff-chat-link-pepper': (SECRET, 'never-while-retained', 'isolated-hmac-record-check'),
    'webhook-secret': (SECRET, 'authorized-requalification', 'isolated-webhook-check'),
    'configuration': (SENSITIVE, 'authorized-requalification', 'protected-config-parse'),
    'backend-image': (PUBLIC, 'not-equivalent-to-rebuild', 'image-import-inspect'),
    'postgres-image': (PUBLIC, 'not-equivalent-to-rebuild', 'image-import-inspect'),
    'source-tools': (PUBLIC, 'not-equivalent-to-rebuild', 'source-tool-byte-check'),
    'deployment': (SENSITIVE, 'authorized-requalification', 'protected-deployment-parse'),
    'recovery-evidence': (SENSITIVE, 'never-while-retained', 'evidence-chain-check'),
    'trust-bootstrap': (SENSITIVE, 'never-while-retained', 'independent-head-check'),
    'provider-target': (SENSITIVE, 'authorized-requalification', 'target-descriptor-check'),
    'provider-account-recovery': (SENSITIVE, 'authorized-requalification', 'independent-account-control'),
    'recovery-instructions': (SENSITIVE, 'authorized-requalification', 'dependency-walkthrough'),
}
VERSION_IDENTITY = S.record('ap07-asset-version', {
    'logical_id': S.enum(*ROLES), 'version': 'positive', 'created_at': 'time',
    'previous_version_sha256': S.optional('sha'),
})
# This projection deliberately excludes AP-01 custody_sha256. The full AP-01
# binding can reference this set's aggregate without a set->binding->set cycle.
BINDING = {
    'policy_sha256': 'sha', 'source_sha': 'git', 'source_tree': 'git',
    'tools_sha256': 'sha', 'python_version': 'version', 'runtime': S.RUNTIME,
    'source_identity_sha256': 'sha', 'database_semantics_sha256': 'sha',
    'restore_recipe_sha256': 'sha', 'target_sha256': 'sha',
    # Hash of a reviewed sanitized record, NEVER legacy env/database URL digests.
    'deployment_record_sha256': 'sha',
    'telegram_mode': S.enum('long_polling', 'webhook'),
    'recovery_profile': {'ai': S.enum(False), 'geodata': S.enum(False), 'billing': S.enum('fake')},
}
ASSET = {
    'identity': VERSION_IDENTITY,
    'classification': S.enum(SECRET, SENSITIVE, PUBLIC),
    'preservation': S.enum('exact-bytes'),
    'regeneration': S.enum(*(set(x[1] for x in ROLES.values()))),
    'state': S.enum('current', 'retired', 'revoked'),
    'recovery_eligibility': S.enum('active', 'historical-only', 'ineligible'),
    'expires_at': S.optional('time'),
    # Only PUBLIC_METADATA bytes may have a plaintext content identity here.
    'public_bytes': S.optional(S.FILE),
    'binding': BINDING,
}
COPY = {
    'copy_id': S.enum('A', 'B'), 'state': S.enum('current', 'retired', 'revoked'),
    'domains': DOMAIN, 'created_at': 'time', 'declared_verified_at': S.optional('time'),
    'ciphertext': S.FILE, 'members': S.array('sha'),
}
SET_SCHEMA = S.record('ap07-custody-set', {
    'set_id': 'id', 'version': 'positive', 'state': S.enum('current', 'retired', 'revoked'),
    'created_at': 'time', 'previous_set_sha256': S.optional('sha'),
    'binding': BINDING, 'runtime_domains': DOMAIN, 'backup_domains': DOMAIN,
    'assets': S.array(ASSET), 'copies': S.array(COPY),
})
RETRIEVAL = {
    'copy_id': S.enum('A', 'B'), 'asset_version_sha256': 'sha',
    'operation': S.enum(*(set(x[2] for x in ROLES.values()))),
    'operation_evidence_sha256': 'sha',
}
PROOF_SCHEMA = S.record('ap07-retrieval-proof', {
    'custody_set_sha256': 'sha', 'set_id': 'id', 'set_version': 'positive',
    'binding_sha256': 'sha', 'observed': S.CLOCK, 'expires_at': 'time',
    'environment_domains': DOMAIN, 'routes': S.array(S.enum('A', 'B')),
    'retrievals': S.array(RETRIEVAL),
})


def _refuse(code='AP07_DECLARATION_INVALID'):
    raise ValueError(code) from None


def _unique_sorted(values):
    if values != sorted(set(values)):
        _refuse()


def _domains(value):
    S.check(value, DOMAIN)
    # A provider-free offline mechanism cannot depend on an online account.
    if value['provider'] is None and value['account'] is not None:
        _refuse()


def _independent(left, right, dimensions=DOMAINS):
    for field in dimensions:
        if left[field] is not None and left[field] == right[field]:
            _refuse()
    # Unlock material cannot be the backup credential under a different label.
    if {left['credential'], left['wrapping_key']} & {right['credential'], right['wrapping_key']}:
        _refuse()


def _identity(doc):
    S.check(doc, VERSION_IDENTITY)
    if (doc['version'] == 1) != (doc['previous_version_sha256'] is None):
        _refuse()
    return dr.digest(doc)


def asset_version(asset):
    """Digest ONLY an immutable non-secret logical version descriptor."""
    try:
        return _identity(asset['identity'])
    except Exception:
        _refuse()


def _binding(binding):
    S.check(binding, BINDING)
    # Reviewed recovery tooling and the restored application may have distinct
    # source identities. runtime projects equal AP-01 data/restore runtimes.
    if binding['policy_sha256'] != dr.digest(S.POLICY):
        _refuse()


def parse_set(raw):
    """Validate DECLARED topology only. Does not establish accessibility or health."""
    try:
        doc = _parse(raw, SET_SCHEMA)
        _binding(doc['binding'])
        if len(doc['assets']) > MAX_ASSETS or len(doc['copies']) != 2:
            _refuse()
        if (doc['version'] == 1) != (doc['previous_set_sha256'] is None):
            _refuse()
        created = S.timestamp(doc['created_at'])
        for key in ('runtime_domains', 'backup_domains'):
            _domains(doc[key])
        identifiers, versions, current = [], [], set()
        for asset in doc['assets']:
            version = asset_version(asset)
            identity = asset['identity']
            role = identity['logical_id']
            identifiers.append((role, identity['version']))
            versions.append(version)
            _binding(asset['binding'])
            classes = (PUBLIC, SENSITIVE) if role in ('backend-image', 'postgres-image', 'source-tools') else (ROLES[role][0],)
            if (asset['classification'] not in classes
                    or asset['regeneration'] != ROLES[role][1]
                    or S.timestamp(identity['created_at']) > created):
                _refuse()
            if asset['classification'] == PUBLIC:
                if asset['public_bytes'] is None:
                    _refuse()
            elif asset['public_bytes'] is not None:
                _refuse()
            if asset['expires_at'] is not None and S.timestamp(asset['expires_at']) <= S.timestamp(identity['created_at']):
                _refuse()
            eligibility = {'current': 'active', 'retired': 'historical-only', 'revoked': 'ineligible'}
            if asset['recovery_eligibility'] != eligibility[asset['state']]:
                _refuse()
            if asset['state'] == 'current':
                if role in current or asset['binding'] != doc['binding']:
                    _refuse()
                current.add(role)
        _unique_sorted(identifiers)
        if len(set(versions)) != len(versions):
            _refuse()
        by_version = {asset_version(x): x for x in doc['assets']}
        for asset in doc['assets']:
            identity = asset['identity']
            predecessor = by_version.get(identity['previous_version_sha256'])
            if predecessor is not None:
                previous = predecessor['identity']
                if (previous['logical_id'] != identity['logical_id']
                        or previous['version'] + 1 != identity['version']
                        or S.timestamp(previous['created_at']) > S.timestamp(identity['created_at'])):
                    _refuse()
            elif identity['version'] > 1 and any(
                    x['identity']['logical_id'] == identity['logical_id']
                    and x['identity']['version'] == identity['version'] - 1 for x in doc['assets']):
                _refuse()
            if asset['state'] == 'current' and any(
                    x['identity']['logical_id'] == identity['logical_id']
                    and x['identity']['version'] > identity['version'] for x in doc['assets']):
                _refuse()
        required = set(ROLES) - {'webhook-secret'}
        if doc['binding']['telegram_mode'] == 'webhook':
            required.add('webhook-secret')
        if current != required:
            _refuse()
        if [x['copy_id'] for x in doc['copies']] != ['A', 'B']:
            _refuse()
        required_members = {asset_version(x) for x in doc['assets'] if x['state'] != 'revoked'}
        for copy in doc['copies']:
            _domains(copy['domains'])
            if not max(S.timestamp(x['identity']['created_at']) for x in doc['assets'] if x['state'] != 'revoked') <= S.timestamp(copy['created_at']) <= created:
                _refuse()
            if copy['declared_verified_at'] is not None and not S.timestamp(copy['created_at']) <= S.timestamp(copy['declared_verified_at']) <= created:
                _refuse()
            _unique_sorted(copy['members'])
            if set(copy['members']) != required_members:
                _refuse()
            for key in ('runtime_domains', 'backup_domains'):
                _independent(copy['domains'], doc[key])
        if any(doc['copies'][1]['domains'][key] is not None for key in ('provider', 'account')):
            _refuse()
        _independent(doc['copies'][0]['domains'], doc['copies'][1]['domains'])
        # Independently encrypted packages have distinct ciphertext identities.
        if doc['copies'][0]['ciphertext']['sha256'] == doc['copies'][1]['ciphertext']['sha256']:
            _refuse()
        return doc
    except Exception:
        _refuse()


def _parse(raw, spec):
    if type(raw) is not bytes or not 0 < len(raw) <= MAX_DOCUMENT_BYTES:
        _refuse()
    doc = dr.database.strict_json(raw)
    S.check(doc, spec)
    if raw != dr.canonical(doc):
        _refuse()
    return doc


@dataclass(frozen=True)
class CustodyPins:
    """Independent current catalogue/head and retained requirements, never JSON.

    required_versions includes every asset required by retained qualified points:
    seven days OR last two OR Q hold (and any other explicit retention hold).
    AP-06 acquires that complete set independently; no local time-based deletion.
    observed is the fresh independent catalogue/revocation/retention observation,
    not the time an old pin was loaded. It must be at most 300 seconds old.
    binding_sha256 and current_versions must come from approved source/runtime,
    not from the set under validation. All tuples/sets are caller-owned snapshots.
    """
    set_sha256: str
    set_id: str
    set_version: int
    binding_sha256: str
    current_versions: tuple
    required_versions: frozenset
    observed: dict


@dataclass(frozen=True)
class RetrievalObservations:
    """Independent caller observations, not receipt claims or a JSON trust loader.

    Each completed_retrieval is (copy_id, asset-version digest, operation,
    non-secret operation-evidence digest). Each package identity and environment
    is independently observed. topology_sha256 pins independently checked transitive
    route/runtime/backup dependencies (including unlock/MFA/account recovery), not
    labels copied from the set. A caller/adapter must perform the bounded operation
    before supplying a tuple; a self-reported PASS/hash is insufficient.
    No operational acquisition implementation is provided by this module.
    """
    proof_sha256: str
    environment_sha256: str
    topology_sha256: str
    packages: frozenset
    completed_retrievals: frozenset
    observed: dict


def _validate_set(raw, pins, now, required_routes):
    """Shared pin/lifecycle validation; route health is selected by the public API."""
    try:
        if type(pins) is not CustodyPins:
            _refuse()
        S.check(now, S.CLOCK)
        S.check(pins.observed, S.CLOCK)
        if dr.age(pins.observed, now) > MAX_OBSERVATION_AGE:
            _refuse()
        S.check(pins.set_sha256, 'sha')
        S.check(pins.binding_sha256, 'sha')
        S.check(pins.set_id, 'id')
        S.check(pins.set_version, 'positive')
        if type(pins.current_versions) is not tuple or type(pins.required_versions) is not frozenset:
            _refuse()
        doc = parse_set(raw)
        if (dr.sha(raw) != pins.set_sha256 or doc['set_id'] != pins.set_id
                or doc['version'] != pins.set_version or dr.digest(doc['binding']) != pins.binding_sha256
                or doc['state'] != 'current'
                or S.timestamp(doc['created_at']) > S.timestamp(now['utc']) - now['error_seconds']
                or S.timestamp(doc['created_at']) > S.timestamp(pins.observed['utc']) - pins.observed['error_seconds']):
            _refuse()
        active = tuple(sorted((x['identity']['logical_id'], asset_version(x)) for x in doc['assets'] if x['state'] == 'current'))
        if pins.current_versions != active:
            _refuse()
        by_version = {asset_version(x): x for x in doc['assets']}
        needed = set(pins.required_versions) | {version for _, version in active}
        for version in needed:
            S.check(version, 'sha')
            asset = by_version.get(version)
            if asset is None or asset['state'] == 'revoked':
                _refuse()
            if asset['expires_at'] is not None and S.timestamp(asset['expires_at']) <= S.timestamp(now['utc']) + now['error_seconds']:
                _refuse()
        for copy in doc['copies']:
            if copy['copy_id'] in required_routes and (copy['state'] != 'current' or not needed <= set(copy['members'])):
                _refuse()
        return doc
    except Exception:
        _refuse('AP07_SET_NOT_CURRENT_OR_INCOMPLETE')


def validate_set(raw, pins, now):
    """Require both current routes; valid declarations alone are not retrieval proof."""
    return _validate_set(raw, pins, now, frozenset(('A', 'B')))


def group_versions(doc):
    """AP-01 aggregate identities are declarations, not Trust.custody_versions.

    Decryption is the immutable key descriptor expected by AP-00 KeyProvider;
    other groups digest non-secret version-descriptor references. Call ONLY after
    parse_set/validate_set; this helper cannot establish operational availability.
    """
    try:
        doc = parse_set(dr.canonical(doc))
        refs = {x['identity']['logical_id']: asset_version(x) for x in doc['assets'] if x['state'] == 'current'}
        return {
            'decryption': refs['backup-key'],
            'secrets': dr.digest({k: v for k, v in refs.items() if ROLES[k][0] == SECRET and k != 'backup-key'}),
            'config': dr.digest({k: refs[k] for k in ('configuration', 'deployment', 'provider-target', 'provider-account-recovery', 'recovery-instructions')}),
            'images': dr.digest({k: refs[k] for k in ('backend-image', 'postgres-image', 'source-tools')}),
            'evidence': dr.digest({k: refs[k] for k in ('recovery-evidence', 'trust-bootstrap')}),
        }
    except Exception:
        _refuse()


def validate_retrieval(set_raw, proof_raw, pins, observations, now):
    """Validate independently acquired observations, never produce operational PASS.

    Both routes: AP07_RETRIEVAL_OBSERVATIONS_VALID_ONLY.
    One route: AP07_DEGRADED_RETRIEVAL_VALID_ONLY (recovery, not healthy custody).
    A missing copy cannot create a new healthy set; the historical two-copy set
    remains pinned. Real secret use requires separate AP-07/AP-04 authorization.
    """
    try:
        proof = _parse(proof_raw, PROOF_SCHEMA)
        _unique_sorted(proof['routes'])
        if not 1 <= len(proof['routes']) <= 2:
            _refuse()
        doc = _validate_set(set_raw, pins, now, frozenset(proof['routes']))
        if type(observations) is not RetrievalObservations:
            _refuse()
        S.check(observations.proof_sha256, 'sha')
        S.check(observations.environment_sha256, 'sha')
        S.check(observations.topology_sha256, 'sha')
        S.check(observations.observed, S.CLOCK)
        if type(observations.packages) is not frozenset or type(observations.completed_retrievals) is not frozenset:
            _refuse()
        if (dr.sha(proof_raw) != observations.proof_sha256
                or proof['custody_set_sha256'] != pins.set_sha256
                or proof['set_id'] != pins.set_id or proof['set_version'] != pins.set_version
                or proof['binding_sha256'] != pins.binding_sha256
                or proof['observed'] != observations.observed
                or dr.digest(proof['environment_domains']) != observations.environment_sha256):
            _refuse()
        if S.timestamp(proof['observed']['utc']) - proof['observed']['error_seconds'] < S.timestamp(doc['created_at']):
            _refuse()
        age = dr.age(observations.observed, now)
        expiry = S.timestamp(proof['expires_at'])
        if (age > MAX_OBSERVATION_AGE
                or expiry <= S.timestamp(now['utc']) + now['error_seconds']
                or expiry > S.timestamp(proof['observed']['utc']) + MAX_OBSERVATION_AGE):
            _refuse()
        _domains(proof['environment_domains'])
        for domains in (doc['runtime_domains'], doc['backup_domains']):
            _independent(proof['environment_domains'], domains)
        _unique_sorted(proof['routes'])
        if not 1 <= len(proof['routes']) <= 2:
            _refuse()
        topology = {'copies': [{'copy_id': x['copy_id'], 'domains': x['domains']}
                               for x in doc['copies'] if x['copy_id'] in proof['routes']],
                    'runtime_domains': doc['runtime_domains'], 'backup_domains': doc['backup_domains']}
        if dr.digest(topology) != observations.topology_sha256:
            _refuse()
        by_version = {asset_version(x): x for x in doc['assets']}
        needed = set(pins.required_versions) | {version for _, version in pins.current_versions}
        expected = {(route, version) for route in proof['routes'] for version in needed}
        actual = [(x['copy_id'], x['asset_version_sha256']) for x in proof['retrievals']]
        _unique_sorted(actual)
        if set(actual) != expected:
            _refuse()
        completed = frozenset((x['copy_id'], x['asset_version_sha256'], x['operation'], x['operation_evidence_sha256']) for x in proof['retrievals'])
        if completed != observations.completed_retrievals:
            _refuse()
        for item in proof['retrievals']:
            role = by_version[item['asset_version_sha256']]['identity']['logical_id']
            if item['operation'] != ROLES[role][2]:
                _refuse()
        packages = frozenset((x['copy_id'], x['ciphertext']['sha256'], x['ciphertext']['size']) for x in doc['copies'] if x['copy_id'] in proof['routes'])
        if observations.packages != packages:
            _refuse()
        if len(proof['routes']) == 1:
            return 'AP07_DEGRADED_RETRIEVAL_VALID_ONLY'
        return 'AP07_RETRIEVAL_OBSERVATIONS_VALID_ONLY'
    except Exception:
        _refuse('AP07_RETRIEVAL_NOT_PROVEN')


def main():
    parser = argparse.ArgumentParser(description='AP-07 DECLARATION validation only; no secret/provider access')
    parser.add_argument('path', help='explicit absolute local custody-set JSON path')
    args = parser.parse_args()
    try:
        parse_set(dr.read_regular(args.path, MAX_DOCUMENT_BYTES))
        print('AP07_DECLARATION_VALID_ONLY_NOT_CUSTODY_PASS')
        return 0
    except Exception:
        print('AP07_DECLARATION_INVALID', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
