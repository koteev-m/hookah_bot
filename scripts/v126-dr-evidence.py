#!/usr/bin/env python3
"""Source-bound Policy B decisions, not a DR executor.

The only CLI operation validates a local document. No provider, credentials,
process launch, scheduler, fence, V126 state write, or operational PASS producer.
Future approved consumers must supply independently acquired Trust, never load it
from the evidence being checked. Digests prove equality, not authenticity.
"""
import argparse
from dataclasses import dataclass
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import platform
import re
import stat
import subprocess
import sys
import tarfile
from typing import Protocol

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


schema = module('dr_schema', ROOT / 'scripts/v126-dr-schema.py')
database = module('database_evidence', ROOT / 'scripts/v126-database-evidence.py')


def fail(code):
    raise ValueError(code)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True,
                       allow_nan=False) + '\n').encode('ascii')


def digest(value):
    return sha(canonical(value))


def file_identity(data):
    if not data:
        fail('EMPTY_ARTIFACT')
    return {'sha256': sha(data), 'size': len(data)}


def parse(raw, kind=None):
    try:
        if type(raw) is not bytes or len(raw) > 16 * 1024 * 1024:
            fail('DR_DOCUMENT_SIZE')
        doc = database.strict_json(raw)
        schema.validate(doc, kind)
        if raw != canonical(doc):
            fail('DR_NONCANONICAL_DOCUMENT')
        return doc
    except (ValueError, TypeError, UnicodeError, RecursionError, OverflowError):
        fail('DR_DOCUMENT_INVALID')


def read_regular(path, limit):
    """Walk all components using dirfds; do not follow links or block on FIFOs."""
    path = Path(path)
    if not path.is_absolute() or any(x in ('.', '..') for x in path.parts):
        fail('UNSAFE_PATH')
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
    try:
        for component in path.parts[1:-1]:
            child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
        child = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
        try:
            before = os.fstat(child)
            if (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1
                    or not 0 < before.st_size <= limit):
                fail('UNSAFE_ARTIFACT')
            with os.fdopen(child, 'rb', closefd=False) as handle:
                data = handle.read(limit + 1)
            after = os.fstat(child)
            stable = lambda s: (s.st_dev, s.st_ino, s.st_mode, s.st_nlink, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
            if len(data) != before.st_size or stable(before) != stable(after):
                fail('ARTIFACT_CHANGED')
            return data
        finally:
            os.close(child)
    finally:
        os.close(fd)


class Evidence:
    """Exact immutable byte snapshots. No filename-based latest selection."""
    def __init__(self, documents):
        self.documents = dict(documents)

    def raw(self, reference):
        schema.check(reference, 'sha')
        raw = self.documents.get(reference)
        if type(raw) is not bytes or sha(raw) != reference:
            fail('EVIDENCE_UNAVAILABLE_OR_CORRUPT')
        return raw

    def get(self, reference, kind):
        return parse(self.raw(reference), kind)


@dataclass(frozen=True)
class Trust:
    """Caller-supplied independent observations, NOT a serializable trust document.

    AP-06 must acquire these under its own authority and revalidate availability
    at each use. AP-01 only constructs synthetic test instances. Passing hashes
    copied from an untrusted receipt is not a valid implementation of this API.
    ongoing_sha256 pins the exact independently acquired ongoing observation,
    including its clocks and mechanism/ledger/authorization bindings. Rehashing
    evidence cannot renew this observation or the independent checkpoint clock.
    """
    binding_sha256: str
    ledger_sha256: str
    authorization_sha256: frozenset
    custody_versions: frozenset
    available_qualifications: frozenset
    observed_evidence: frozenset
    response_authorities: frozenset
    observed: dict
    operational: bool = False
    ongoing_sha256: str | None = None


REQUIRED_TOOLS = (
    'scripts/v126-cutover.sh', 'scripts/v126-database-evidence.py',
    'scripts/v126-operation-bindings.py', 'scripts/v126-dr-evidence.py',
    'scripts/v126-dr-schema.py', 'docs/V126_DATABASE_RECOVERY_REHEARSAL.md',
)


def tooling(root):
    # Include the existing production V126 helper surface, not only this verifier.
    paths = set(REQUIRED_TOOLS)
    paths.update(str(p.relative_to(root)) for p in (root / 'scripts').glob('v126-*') if p.is_file())
    return [{'path': p, 'version': 'policy-b-v1', 'sha256': sha(read_regular(root / p, 16*1024*1024))}
            for p in sorted(paths)]


def verify_checkout(binding, root, require_clean=False):
    """Read-only binding check, including uncommitted executable/schema bytes.

    A future operational caller additionally requires reviewed immutable source;
    AP-01 can bind a local candidate with explicit tool hashes for synthetic tests.
    """
    schema.validate(binding, 'binding')
    root = Path(root)
    env = {k: v for k, v in os.environ.items() if k in ('PATH', 'HOME', 'TMPDIR')}
    def git(*args):
        return subprocess.run(['git', '--no-replace-objects', '-c', 'core.fsmonitor=false',
                               '-C', str(root), *args], env=env, check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
    if (git('rev-parse', 'HEAD') != binding['source_sha']
            or git('rev-parse', 'HEAD^{tree}') != binding['source_tree']
            or tooling(root) != binding['tools']
            or binding['python_version'] != platform.python_version()):
        fail('SOURCE_TOOL_BINDING_MISMATCH')
    if require_clean and git('status', '--porcelain', '--untracked-files=all'):
        fail('OPERATIONAL_SOURCE_NOT_CLEAN')


def validate_binding(evidence, trust):
    binding = evidence.get(trust.binding_sha256, 'binding')
    verify_checkout(binding, ROOT, require_clean=trust.operational)
    if binding['policy_sha256'] != digest(schema.POLICY):
        fail('POLICY_MISMATCH')
    paths = [x['path'] for x in binding['tools']]
    if paths != sorted(set(paths)) or not set(REQUIRED_TOOLS) <= set(paths):
        fail('INCOMPLETE_TOOL_BINDING')
    recipe = next(x['sha256'] for x in binding['tools'] if x['path'] == 'docs/V126_DATABASE_RECOVERY_REHEARSAL.md')
    if binding['restore_recipe_sha256'] != recipe:
        fail('RESTORE_RECIPE_NOT_TOOL_BOUND')
    if binding['data_runtime'] != binding['restore_runtime']:
        fail('RUNTIME_COMPATIBILITY_NOT_PROVEN')
    evidence.get(binding['target_sha256'], 'target')
    custody = evidence.get(binding['custody_sha256'], 'custody')
    if not set(custody['versions'].values()) <= trust.custody_versions:
        fail('CUSTODY_UNAVAILABLE')
    return binding


def chronology(earlier, later):
    """Same-clock monotonic observations; cross-clock ambiguous order is rejected."""
    schema.check(earlier, schema.CLOCK)
    schema.check(later, schema.CLOCK)
    elapsed = schema.timestamp(later['utc']) - schema.timestamp(earlier['utc'])
    if earlier['clock_id'] == later['clock_id']:
        mono = later['monotonic_seconds'] - earlier['monotonic_seconds']
        if elapsed < 0 or mono < 0 or abs(elapsed - mono) > earlier['error_seconds'] + later['error_seconds']:
            fail('CLOCK_ROLLBACK_OR_DRIFT')
    elif schema.timestamp(earlier['utc']) + earlier['error_seconds'] > schema.timestamp(later['utc']) - later['error_seconds']:
        fail('CLOCK_ORDER_AMBIGUOUS')


def age(point, now):
    chronology(point, now)
    # Reject future observations even if uncertainty could hide them.
    if schema.timestamp(point['utc']) > schema.timestamp(now['utc']):
        fail('FUTURE_POINT')
    return (schema.timestamp(now['utc']) + now['error_seconds']
            - schema.timestamp(point['utc']) + point['error_seconds'])


def fresh(point, now, action_seconds=None):
    value = age(point, now)
    if value > schema.POLICY['rpo_seconds']:
        fail('RPO_VIOLATION')
    if action_seconds is not None:
        schema.check(action_seconds, 'uint')
        if value + action_seconds + schema.POLICY['dispatch_margin_seconds'] > 86400:
            fail('FRESHNESS_BUDGET_EXHAUSTED')
    return value


def validate_bundle(members, manifest):
    schema.validate(manifest, 'manifest')
    if set(members) != set(schema.ARTIFACTS):
        fail('BUNDLE_INVENTORY_MISMATCH')
    for name in schema.ARTIFACTS:
        if file_identity(members[name]) != manifest['artifacts'][name]:
            fail('BUNDLE_ARTIFACT_MISMATCH')
    if not members['application.dump'].startswith(b'PGDMP'):
        fail('DUMP_FORMAT_INVALID')
    database.canonical_toc(members['toc.list'])
    password_free_globals(members['globals.sql'])
    # Metadata is digest/reference-only. No arbitrary config values or SQL output
    # can accidentally carry a secret into produced evidence/test fixtures.
    for name in schema.ARTIFACTS:
        if name.endswith('.json'):
            try:
                value = database.strict_json(members[name])
                spec = {'schema_version': schema.enum(1), 'category': schema.enum(name[:-5]),
                        'snapshot_token_sha256': 'sha', 'semantic_sha256': 'sha', 'count': 'uint'}
                schema.check(value, spec)
                if (members[name] != canonical(value)
                        or value['snapshot_token_sha256'] != manifest['snapshot_token_sha256']):
                    fail('INVENTORY_SNAPSHOT_MISMATCH')
            except (ValueError, TypeError, UnicodeError):
                fail('UNSAFE_INVENTORY_METADATA')
    return digest(manifest['artifacts'])


def validate_directory(root, manifest, byte_limit):
    schema.validate(manifest, 'manifest')
    schema.check(byte_limit, 'positive')
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        fail('UNSAFE_BUNDLE_DIRECTORY')
    if set(os.listdir(root)) != set(schema.ARTIFACTS):
        fail('UNEXPECTED_BUNDLE_MEMBER')
    if sum(x['size'] for x in manifest['artifacts'].values()) > byte_limit:
        fail('BUNDLE_BUDGET_EXCEEDED')
    members = {name: read_regular(root / name, manifest['artifacts'][name]['size']) for name in schema.ARTIFACTS}
    validate_bundle(members, manifest)
    return members


def validate_archive(raw, manifest, byte_limit):
    schema.validate(manifest, 'manifest')
    schema.check(byte_limit, 'positive')
    if (not 0 < len(raw) <= byte_limit or len(raw) % 512
            or sum(x['size'] for x in manifest['artifacts'].values()) > byte_limit):
        fail('ARCHIVE_TRUNCATED_OR_OVERSIZE')
    members = {}
    try:
        with tarfile.open(fileobj=io.BytesIO(raw), mode='r:') as archive:
            end = 0
            for item in archive:
                if (item.type != tarfile.REGTYPE or item.name not in manifest['artifacts']
                        or item.name in members or item.pax_headers or item.linkname
                        or item.offset != end or item.offset_data != end + 512
                        or item.size != manifest['artifacts'][item.name]['size']):
                    fail('UNEXPECTED_ARCHIVE_MEMBER')
                handle = archive.extractfile(item)
                members[item.name] = handle.read(item.size + 1)
                end = item.offset_data + ((item.size + 511) // 512) * 512
            if len(raw) - end < 1024 or any(raw[end:]):
                fail('ARCHIVE_TRAILER_INVALID')
    except (tarfile.TarError, OSError, EOFError):
        fail('ARCHIVE_INVALID')
    validate_bundle(members, manifest)
    return members


def password_free_globals(raw):
    if (type(raw) is not bytes or not raw or not raw.endswith(b'\n') or b'\0' in raw
            or re.search(rb'password|scram-sha|md5[0-9a-f]{32}|://', raw, re.I)):
        fail('GLOBALS_NOT_PASSWORD_FREE')
    safe_settings = {b'timezone', b'search_path', b'statement_timeout', b'lock_timeout',
                     b'idle_session_timeout', b'idle_in_transaction_session_timeout',
                     b'default_transaction_read_only'}
    # Custom role GUCs may carry credentials even with --no-role-passwords.
    # Unknown/complex settings belong to protected custody, not this stream.
    for statement in raw.split(b';'):
        if re.search(rb'\bALTER\s+ROLE\b', statement, re.I):
            setting = re.search(rb'\bSET\s+([^\s]+)\s+(?:TO|=)', statement, re.I)
            if setting and setting[1].strip(b'"').lower() not in safe_settings:
                fail('GLOBALS_SETTING_REQUIRES_PROTECTED_CUSTODY')


def bootstrap_globals(raw, role, target_roles, target_databases):
    """One reviewed simple-name bootstrap exception; no SQL/error suppression.

    Complex identifiers need a separately reviewed parser/recipe. The subsequent
    psql ON_ERROR_STOP consumer must still validate the complete original stream.
    """
    password_free_globals(raw)
    if (not re.fullmatch(r'[a-z][a-z0-9_]{0,62}', role) or role.startswith('pg_')
            or target_roles != [role] or sorted(target_databases) != ['postgres', 'template0', 'template1']):
        fail('RESTORE_TARGET_NOT_EMPTY')
    statement = ('CREATE ROLE ' + role + ';\n').encode()
    if raw.splitlines(keepends=True).count(statement) != 1:
        fail('BOOTSTRAP_CONFLICT_NOT_EXACT')
    transformed = b''.join(x for x in raw.splitlines(keepends=True) if x != statement)
    return transformed, {'original_sha256': sha(raw), 'transformed_sha256': sha(transformed),
                         'omitted_statement_sha256': sha(statement)}


def compare_catalogs(before, after):
    """Compare full snapshot-derived semantic vectors, never cluster-local OIDs."""
    names = set(schema.RESTORE_CHECKS) - {'RESTORE_COMPLETE'}
    if set(before) != names or set(after) != names:
        fail('CATALOG_INVENTORY_INCOMPLETE')
    for name in names:
        if type(before[name]) is not bytes or not before[name] or before[name] != after[name]:
            fail('RESTORE_CATALOG_MISMATCH')
    return {name: sha(before[name]) for name in sorted(names)}


class S3Transport(Protocol):
    """AP-00/AP-03 backend must create once, pin VersionId, verify retention, and
    perform independent versioned GET. ETag is never a digest; no mutable latest.
    No implementation/SDK/credential acquisition is supplied by AP-01.
    """
    def read_exact(self, target_sha256: str, key: str, version_id: str) -> bytes: ...


class Encryption(Protocol):
    """Reviewed crypto backend prerequisite; keys stay behind this interface."""
    def decrypt(self, ciphertext: bytes, metadata: dict) -> bytes: ...


class FakeS3:
    """Deterministic in-memory version store; no filesystem/network credentials."""
    def __init__(self):
        self.objects = {}

    def put_fixture(self, target, key, version, data):
        identity = (target, key, version)
        if identity in self.objects or not data:
            fail('FAKE_OBJECT_ALREADY_EXISTS_OR_EMPTY')
        self.objects[identity] = bytes(data)

    def read_exact(self, target_sha256, key, version_id):
        if (target_sha256, key, version_id) not in self.objects:
            fail('EXACT_OBJECT_VERSION_UNAVAILABLE')
        return self.objects[(target_sha256, key, version_id)]


class FakeEncryption:
    """Opaque token lookup, NOT encryption. Only synthetic fixtures are accepted."""
    def __init__(self, ciphertext, plaintext):
        self.ciphertext, self.plaintext = ciphertext, plaintext

    def decrypt(self, ciphertext, metadata):
        if metadata['mode'] != 'synthetic' or ciphertext != self.ciphertext:
            fail('SYNTHETIC_CRYPTO_ONLY')
        return self.plaintext


def independent_readback(transport, encryption, receipt, manifest, byte_limit):
    schema.validate(receipt, 'offhost')
    raw = transport.read_exact(receipt['target_sha256'], receipt['key'], receipt['version_id'])
    if file_identity(raw) != receipt['ciphertext'] or receipt['readback'] != receipt['ciphertext']:
        fail('READBACK_MISMATCH')
    archive = encryption.decrypt(raw, receipt['encryption'])
    if file_identity(archive) != receipt['downloaded_archive']:
        fail('DECRYPTED_ARCHIVE_MISMATCH')
    members = validate_archive(archive, manifest, byte_limit)
    if digest(manifest['artifacts']) != receipt['decrypted_members_sha256']:
        fail('DECRYPTED_MEMBERS_MISMATCH')
    return members


def require_observations(references, trust):
    if not set(references) <= trust.observed_evidence:
        fail('INDEPENDENT_OBSERVATION_MISSING')


def validate_intent(evidence, trust, binding, intent):
    if intent['binding_sha256'] != trust.binding_sha256:
        fail('INCOMPATIBLE_BINDING')
    if intent['authorization_sha256'] not in trust.authorization_sha256:
        fail('AUTHORIZATION_NOT_TRUSTED')
    auth = evidence.get(intent['authorization_sha256'], 'authorization')
    if (auth['binding_sha256'] != trust.binding_sha256 or auth['purpose'] != intent['purpose']
            or (auth['mode'] == 'operational') != trust.operational
            or len(auth['packages']) != len(set(auth['packages']))):
        fail('AUTHORIZATION_SCOPE_MISMATCH')
    required = {'AP-02', 'AP-03', 'AP-04', 'AP-06'} if trust.operational else {'AP-01'}
    if not required <= set(auth['packages']):
        fail('AUTHORIZATION_PACKAGE_MISSING')
    start = schema.timestamp(intent['started']['utc'])
    if not (schema.timestamp(auth['valid_from']) <= start - intent['started']['error_seconds']
            <= start + intent['started']['error_seconds'] <= schema.timestamp(auth['valid_until'])):
        fail('AUTHORIZATION_EXPIRED')
    if (intent['purpose'] == 'cutover-Q') != (intent['cutover'] is not None):
        fail('R0_IS_NOT_Q')
    return auth


def validate_qualification(evidence, trust, binding, qref, intent_ref, result_ref, now):
    q = evidence.get(qref, 'qualification')
    if (q['binding_sha256'] != trust.binding_sha256 or q['intent_sha256'] != intent_ref
            or q['result_sha256'] != result_ref):
        fail('QUALIFICATION_PREDECESSOR_MISMATCH')
    intent = evidence.get(intent_ref, 'intent')
    auth = validate_intent(evidence, trust, binding, intent)
    result = evidence.get(result_ref, 'result')
    if (result['outcome'] != 'SUCCESS' or result['exit_code'] != 0 or result['intent_sha256'] != intent_ref
            or result['manifest_sha256'] != q['manifest_sha256']):
        fail('PRODUCER_DID_NOT_SUCCEED')
    manifest = evidence.get(q['manifest_sha256'], 'manifest')
    if (manifest['binding_sha256'] != trust.binding_sha256 or manifest['intent_sha256'] != intent_ref
            or manifest['globals_capture_intent_sha256'] != intent_ref
            or manifest['consistency'] != ('quiesced' if intent['purpose'] == 'cutover-Q' else 'exported-snapshot')):
        fail('SNAPSHOT_OR_FRESH_GLOBALS_MISMATCH')
    chronology(intent['started'], manifest['point'])
    chronology(manifest['point'], result['completed'])
    age(result['completed'], now)
    off = evidence.get(q['offhost_sha256'], 'offhost')
    if (off['result_sha256'] != result_ref or off['manifest_sha256'] != q['manifest_sha256']
            or off['target_sha256'] != binding['target_sha256']
            or off['custody_sha256'] != binding['custody_sha256']
            or off['key'] != 'bundles/' + q['manifest_sha256'] + '.enc'
            or off['ciphertext'] != off['readback'] or off['downloaded_archive'] != result['archive']
            or off['decrypted_members_sha256'] != digest(manifest['artifacts'])
            or off['writer_identity_sha256'] == off['reader_identity_sha256']
            or off['reader_failure_domain_sha256'] == binding['source_identity_sha256']):
        fail('OFFHOST_RECEIPT_MISMATCH')
    custody = evidence.get(binding['custody_sha256'], 'custody')
    if off['encryption']['custody_version_sha256'] != custody['versions']['decryption']:
        fail('ENCRYPTION_CUSTODY_MISMATCH')
    if off['encryption']['mode'] != ('client-side' if trust.operational else 'synthetic'):
        fail('ENCRYPTION_MODE_MISMATCH')
    tools = {x['sha256'] for x in binding['tools']}
    if off['transport_sha256'] not in tools or off['encryption']['backend_sha256'] not in tools:
        fail('TRANSPORT_OR_ENCRYPTION_NOT_TOOL_BOUND')
    restore = evidence.get(q['restore_sha256'], 'restore')
    if (restore['offhost_sha256'] != q['offhost_sha256'] or restore['downloaded_archive'] != result['archive']
            or restore['target_identity_sha256'] == binding['source_identity_sha256']
            or restore['runtime'] != binding['restore_runtime']
            or restore['recipe_sha256'] != binding['restore_recipe_sha256']):
        fail('RESTORE_BINDING_MISMATCH')
    functional = evidence.get(q['functional_sha256'], 'functional')
    scope = 'real-custody' if trust.operational else 'synthetic'
    if (functional['restore_sha256'] != q['restore_sha256']
            or restore['auth_scope'] != scope or functional['auth_scope'] != scope):
        fail('SYNTHETIC_IS_NOT_REAL_AUTH')
    times = [result['completed']['utc'], off['uploaded_at'], off['read_at'], restore['isolated_at'],
             restore['hydrated_at'], restore['started_at'], restore['completed_at'],
             functional['completed_at'], q['qualified_at']]
    seconds = [schema.timestamp(x) for x in times]
    # These cross-worker UTC observations use the policy maximum uncertainty
    # (60s each). No optimistic ordering between independent clocks.
    if (any(b - a < 120 for a, b in zip(seconds, seconds[1:]))
            or seconds[-1] + 60 > schema.timestamp(now['utc']) - now['error_seconds']):
        fail('PROOF_CHRONOLOGY_INVALID')
    if seconds[-1] + 60 > schema.timestamp(auth['valid_until']):
        fail('QUALIFICATION_OUTSIDE_AUTHORIZATION')
    if seconds[-1] + 60 - schema.timestamp(manifest['point']['utc']) + manifest['point']['error_seconds'] > 7200:
        fail('QUALIFICATION_BUDGET_EXCEEDED')
    if schema.timestamp(off['retained_until']) < schema.timestamp(manifest['point']['utc']) + 604800:
        fail('OBJECT_RETENTION_TOO_SHORT')
    # Live bytes/custody/independent read-back are caller observations; receipt
    # assertions alone never prove them. Trust head pins all dependent bytes.
    require_observations([
        q['offhost_sha256'], manifest['admin_freeze_sha256'], manifest['snapshot_consumers_sha256'],
        result['consumer_results_sha256'], restore['empty_target_sha256'], restore['egress_proof_sha256'],
        restore['bootstrap_transform_sha256'],
        *[x['evidence_sha256'] for x in restore['checks'].values()],
        *[x['evidence_sha256'] for x in functional['checks'].values()],
    ], trust)
    return {'qualification': qref, 'manifest': manifest, 'intent': intent,
            'qualified_at': seconds[-1], 'sequence': intent['sequence']}


def replay_ledger(evidence, trust, now):
    if type(trust.operational) is not bool or age(trust.observed, now) > 300:
        fail('INDEPENDENT_CHECKPOINT_STALE')
    binding = validate_binding(evidence, trust)
    ledger = evidence.get(trust.ledger_sha256, 'ledger')
    if ledger['binding_sha256'] != trust.binding_sha256 or ledger['head_sequence'] != len(ledger['events']):
        fail('LEDGER_INCOMPLETE')
    previous_event = None
    previous_result = None
    attempts = []
    points = []
    seen_documents = set()
    for sequence, ref in enumerate(ledger['events'], 1):
        event = evidence.get(ref, 'event')
        if event['sequence'] != sequence or event['previous_sha256'] != previous_event:
            fail('LEDGER_CHAIN_INVALID')
        previous_event = ref
        docref = event['document_sha256']
        if docref in seen_documents:
            fail('LEDGER_DUPLICATE_DOCUMENT')
        seen_documents.add(docref)
        kind = event['document_kind']
        doc = evidence.get(docref, kind)
        if kind == 'intent':
            if attempts and attempts[-1]['outcome'] not in ('FAILED', 'QUALIFIED'):
                fail('UNRESOLVED_ATTEMPT')
            if (doc['sequence'] != len(attempts) + 1 or doc['previous_result_sha256'] != previous_result
                    or doc['attempt_id'] in {a['intent']['attempt_id'] for a in attempts}):
                fail('ATTEMPT_SEQUENCE_INVALID')
            validate_intent(evidence, trust, binding, doc)
            age(doc['started'], now)
            if attempts:
                chronology(attempts[-1]['result']['completed'], doc['started'])
                if attempts[-1]['outcome'] == 'QUALIFIED':
                    if points[-1]['qualified_at'] + 60 > schema.timestamp(doc['started']['utc']) - doc['started']['error_seconds']:
                        fail('QUALIFICATION_OVERLAPS_NEXT_ATTEMPT')
            attempts.append({'intent_ref': docref, 'intent': doc, 'outcome': 'PENDING'})
        elif kind == 'result':
            if not attempts or attempts[-1]['outcome'] != 'PENDING' or doc['intent_sha256'] != attempts[-1]['intent_ref']:
                fail('RESULT_WITHOUT_PENDING_INTENT')
            chronology(attempts[-1]['intent']['started'], doc['completed'])
            age(doc['completed'], now)
            if doc['outcome'] == 'SUCCESS':
                if doc['exit_code'] != 0 or doc['manifest_sha256'] is None or doc['archive'] is None:
                    fail('INCOMPLETE_SUCCESS')
            elif (doc['manifest_sha256'] is not None or doc['archive'] is not None
                  or (doc['outcome'] == 'FAILED' and (doc['exit_code'] is None or doc['exit_code'] == 0))
                  or (doc['outcome'] == 'UNKNOWN' and doc['exit_code'] is not None)):
                fail('INVALID_TERMINAL_OUTCOME')
            attempts[-1].update(outcome=doc['outcome'], result=doc, result_ref=docref)
            previous_result = docref
        else:
            if not attempts or attempts[-1]['outcome'] != 'SUCCESS':
                fail('QUALIFICATION_WITHOUT_SUCCESS')
            point = validate_qualification(evidence, trust, binding, docref,
                                           attempts[-1]['intent_ref'], attempts[-1]['result_ref'], now)
            if points:
                chronology(points[-1]['manifest']['point'], point['manifest']['point'])
            points.append(point)
            attempts[-1]['outcome'] = 'QUALIFIED'
    if not attempts:
        fail('LEDGER_EMPTY')
    if not retention_required(points, now) <= trust.available_qualifications:
        fail('REQUIRED_RETENTION_BYTES_UNAVAILABLE')
    return binding, attempts, points


def select_point(evidence, trust, now, purpose=None, requested_attempt=None):
    _, attempts, points = replay_ledger(evidence, trust, now)
    if attempts[-1]['outcome'] != 'QUALIFIED' or not points:
        fail('LATEST_ATTEMPT_' + attempts[-1]['outcome'])
    selected = max(points, key=lambda p: (schema.timestamp(p['manifest']['point']['utc'])
                                         - p['manifest']['point']['error_seconds'], p['sequence']))
    if selected['sequence'] != attempts[-1]['intent']['sequence']:
        fail('LATEST_ATTEMPT_NOT_AUTHORITATIVE')
    if selected['qualification'] not in trust.available_qualifications:
        fail('EXACT_BYTES_UNAVAILABLE')
    if purpose is not None and selected['intent']['purpose'] != purpose:
        fail('PURPOSE_MISMATCH')
    if requested_attempt is not None and selected['intent']['attempt_id'] != requested_attempt:
        fail('REQUESTED_ATTEMPT_MISMATCH')
    fresh(selected['manifest']['point'], now)
    return selected


def retention_required(points, now):
    """Selection only; never delete bytes or attempt records. Q always retained."""
    ordered = sorted(points, key=lambda p: (schema.timestamp(p['manifest']['point']['utc']), p['sequence']))
    return {p['qualification'] for p in ordered[-2:]} | {
        p['qualification'] for p in ordered
        if age(p['manifest']['point'], now) <= 604800 or p['intent']['purpose'] == 'cutover-Q'
    }


def evaluate_ongoing(evidence, trust, ongoing_ref, now):
    """Pure evaluation: monitor issues cannot erase proven age/response facts."""
    try:
        ongoing = evidence.get(ongoing_ref, 'ongoing')
        schema.check(trust.ongoing_sha256, 'sha')
        if digest(ongoing) != trust.ongoing_sha256:
            fail('ONGOING_OBSERVATION_NOT_TRUSTED')
        if ongoing['binding_sha256'] != trust.binding_sha256 or ongoing['ledger_sha256'] != trust.ledger_sha256:
            fail('ONGOING_BINDING_MISMATCH')
        verify_consumer_authority(evidence, trust, ongoing['authorization_sha256'], now, 'product-periodic', 'AP-05')
        binding, attempts, points = replay_ledger(evidence, trust, now)
        response_state = ('AUTHORIZED' if validate_response(evidence, trust, binding, ongoing['response_authority_sha256'], now)
                          else 'RESPONSE_AUTHORITY_MISSING')
        # Monitor clocks are a separate dimension after the independent pin,
        # checkpoint and ledger checks. Their failure only closes readiness.
        issues = []
        try:
            observed_age = age(ongoing['observed'], now)
            monitor_age = age(ongoing['last_monitor'], now)
            if observed_age > 300 or monitor_age > 600:
                issues.append('MONITOR_UNAVAILABLE')
            elif monitor_age > 300:
                issues.append('MONITOR_INTERVAL_MISSED')
        except ValueError:
            issues.append('MONITOR_UNAVAILABLE')
        if attempts[-1]['outcome'] != 'QUALIFIED' or not points:
            issues.append('LATEST_ATTEMPT_' + attempts[-1]['outcome'])
            if response_state != 'AUTHORIZED':
                issues.append(response_state)
            return {'state': 'RPO_NOT_PROVABLE', 'age_state': 'RPO_NOT_PROVABLE',
                    'response_state': response_state, 'age_seconds': None, 'issues': issues,
                    'ready': False, 'last_attempt_status': attempts[-1]['outcome']}
        latest = max(points, key=lambda p: (schema.timestamp(p['manifest']['point']['utc'])
                                            - p['manifest']['point']['error_seconds'], p['sequence']))
        value = age(latest['manifest']['point'], now)
        state = ('RPO_VIOLATION' if value > 86400 else 'FENCE_REQUIRED' if value >= 79200
                 else 'CRITICAL' if value >= 72000 else 'WARNING' if value >= 43200 else 'HEALTHY')
        periodic = [p for p in points if p['intent']['purpose'] == 'product-periodic']
        if (not periodic or ongoing['completed_periodic_qualification_sha256'] not in {p['qualification'] for p in periodic}
                or len(points) < 2 or not retention_required(points, now) <= trust.available_qualifications):
            issues.append('RETENTION_OR_PERIODIC_PROOF_MISSING')
        due = schema.timestamp(ongoing['last_snapshot_due'])
        now_lower = schema.timestamp(now['utc']) - now['error_seconds']
        expected_due = (now_lower // 21600) * 21600
        if due != expected_due:
            issues.append('CADENCE_OBSERVATION_INVALID')
        # Opening the next slot's grace period must not hide a missed previous
        # cycle. A more recent completed cycle can restore current readiness.
        required_slot = expected_due if now_lower >= expected_due + 7200 else expected_due - 21600
        on_time = [p for p in periodic if p['qualified_at'] + 60
                   <= (schema.timestamp(p['manifest']['point']['utc']) // 21600) * 21600 + 7200]
        if not any(schema.timestamp(p['manifest']['point']['utc']) >= required_slot for p in on_time):
            issues.append('CADENCE_OR_QUALIFICATION_MISSED')
        require_observations([ongoing['mechanism_sha256'], ongoing['failure_detection_test_sha256'],
                              ongoing['post_v126_recipe_sha256']], trust)
        # Preserve the age dimension and its warning/action severity. A young
        # point with a blocking issue is non-ready, without losing its known age.
        display = 'RPO_NOT_PROVABLE' if issues and state == 'HEALTHY' else state
        if response_state != 'AUTHORIZED':
            issues.append(response_state)
            if display == 'HEALTHY':
                display = response_state
        return {'state': display, 'age_state': state, 'response_state': response_state,
                'age_seconds': value, 'issues': issues, 'ready': not issues and state == 'HEALTHY',
                'last_attempt_status': attempts[-1]['outcome']}
    except (ValueError, KeyError, TypeError, OSError):
        return {'state': 'RPO_NOT_PROVABLE', 'age_state': 'RPO_NOT_PROVABLE',
                'response_state': 'RESPONSE_AUTHORITY_MISSING', 'age_seconds': None,
                'issues': ['EVIDENCE_OR_CLOCK_NOT_PROVABLE'], 'ready': False,
                'last_attempt_status': 'UNPROVABLE'}


def consume_barrier(evidence, trust, readiness_ref, ongoing_ref, now, *, purpose,
                    requested_attempt, action_seconds=None, cutover=None, native_stage7=None):
    """Versioned pure hook for FUTURE V126 callers under their existing target lock.

    No call is wired into the historical 20-state executor. AP-06 revalidates the
    native stage7 chain with the existing verifier and pins its exact bytes; a
    stage7 JSON file supplied by itself is not authority.
    """
    proof = evidence.get(readiness_ref, 'readiness')
    verify_consumer_authority(evidence, trust, proof['authorization_sha256'], now, purpose, 'AP-06')
    if (proof['binding_sha256'] != trust.binding_sha256 or proof['ledger_sha256'] != trust.ledger_sha256
            or proof['ongoing_sha256'] != ongoing_ref or proof['purpose'] != purpose
            or proof['result'] != ('DR_POINT_PASS' if trust.operational else 'SYNTHETIC_POINT_PASS')):
        fail('READINESS_BINDING_MISMATCH')
    selected = select_point(evidence, trust, now, purpose, requested_attempt)
    if proof['qualification_sha256'] != selected['qualification']:
        fail('READINESS_NOT_CURRENT')
    recorded_age = fresh(selected['manifest']['point'], proof['checked'])
    lower = schema.timestamp(selected['manifest']['point']['utc']) - selected['manifest']['point']['error_seconds']
    if proof['age_seconds'] != recorded_age or schema.timestamp(proof['deadline']) != lower + 86400:
        fail('READINESS_FORGED_AGE')
    chronology(proof['checked'], now)
    fresh(selected['manifest']['point'], now, action_seconds)
    if purpose == 'cutover-Q':
        schema.check(cutover, schema.CUTOVER)
        if (cutover != proof['cutover'] or cutover != selected['intent']['cutover']
                or type(native_stage7) is not bytes or sha(native_stage7) != cutover['stage7_sha256']):
            fail('Q_RUN_OR_STAGE7_MISMATCH')
        require_observations([cutover['stage7_sha256'], cutover['native_manifest_sha256']], trust)
        native = validate_native_stage7(native_stage7, cutover, trust.binding_sha256, evidence)
        artifacts = {x['name']: x['sha256'] for x in native['artifacts']}
        manifest = selected['manifest']
        if (artifacts['quiesced-backup-dump'] != manifest['artifacts']['application.dump']['sha256']
                or artifacts['quiesced-backup-inventory'] != manifest['artifacts']['toc.list']['sha256']
                or schema.timestamp(native['completed_at']) < schema.timestamp(manifest['point']['utc']) + manifest['point']['error_seconds']):
            fail('Q_NATIVE_BACKUP_BYTES_OR_TIME_MISMATCH')
    elif purpose != 'preparation' or cutover is not None or proof['cutover'] is not None or native_stage7 is not None:
        fail('R0_IS_NOT_Q')
    ongoing = evaluate_ongoing(evidence, trust, ongoing_ref, now)
    if not ongoing['ready']:
        fail('ONGOING_NOT_READY')
    return {'barrier': 'Q' if purpose == 'cutover-Q' else 'R0',
            'qualification_sha256': selected['qualification'], 'operational': trust.operational}


def verify_consumer_authority(evidence, trust, reference, now, purpose, package):
    if reference not in trust.authorization_sha256:
        fail('CONSUMER_AUTHORITY_NOT_TRUSTED')
    auth = evidence.get(reference, 'authorization')
    now_time = schema.timestamp(now['utc'])
    if (auth['binding_sha256'] != trust.binding_sha256 or auth['purpose'] != purpose
            or auth['mode'] != ('operational' if trust.operational else 'synthetic')
            or (package if trust.operational else 'AP-01') not in auth['packages']
            or len(auth['packages']) != len(set(auth['packages']))
            or schema.timestamp(auth['valid_from']) > now_time - now['error_seconds']
            or schema.timestamp(auth['valid_until']) < now_time + now['error_seconds']):
        fail('CONSUMER_AUTHORITY_SCOPE_OR_EXPIRY')


def validate_response(evidence, trust, binding, reference, now):
    """Validate design/authority only. No writer action is available here."""
    try:
        if reference not in trust.response_authorities:
            return False
        response = evidence.get(reference, 'response')
        if (response['binding_sha256'] != trust.binding_sha256
                or response['mode'] != ('operational' if trust.operational else 'synthetic')
                or response['executor_sha256'] not in {x['sha256'] for x in binding['tools']}):
            return False
        verify_consumer_authority(evidence, trust, response['authorization_sha256'], now, 'product-periodic', 'AP-05')
        require_observations([response['mechanism_evidence_sha256']], trust)
        return True
    except (ValueError, TypeError, KeyError):
        return False


def validate_native_stage7(raw, cutover, binding_ref, evidence):
    """Inspect native v1 shape without upgrading it to the new DR schema."""
    try:
        doc = database.strict_json(raw)
        fields = {'artifacts', 'authorization_gate', 'authorization_receipt_sha256', 'completed_at',
                  'format_version', 'intent_sha256', 'predecessor_receipt_sha256', 'predecessor_stage',
                  'release_sha', 'result_category', 'run_id', 'script_sha256', 'stage'}
        if type(doc) is not dict or set(doc) != fields or canonical(doc) != raw:
            fail('NATIVE_STAGE7_INVALID')
        binding = evidence.get(binding_ref, 'binding')
        script_hash = next(x['sha256'] for x in binding['tools'] if x['path'] == 'scripts/v126-cutover.sh')
        if (type(doc['format_version']) is not int or doc['format_version'] != 1 or doc['result_category'] != 'PASS'
                or doc['stage'] != 'QUIESCED_BACKUP_REHEARSED' or doc['predecessor_stage'] != 'ZERO_WRITER_GATE_PASSED'
                or doc['run_id'] != cutover['run_id'] or doc['release_sha'] != binding['source_sha']
                or doc['script_sha256'] != script_hash or doc['authorization_gate'] != 'A'):
            fail('NATIVE_STAGE7_BINDING_MISMATCH')
        for name in ('intent_sha256', 'predecessor_receipt_sha256', 'authorization_receipt_sha256'):
            schema.check(doc[name], 'sha')
        schema.timestamp(doc['completed_at'])
        # Full inventory and operation-log replay stay with verify_receipt.
        schema.check(doc['artifacts'], schema.array({'name': 'id', 'sha256': 'sha'}))
        names = [x['name'] for x in doc['artifacts']]
        required = {'operation-log', 'quiesced-backup-dump', 'quiesced-backup-inventory',
                    'quiesced-backup-proof', 'quiesced-backup-rehearsal'}
        if names != sorted(required):
            fail('NATIVE_STAGE7_ARTIFACTS_INVALID')
        return doc
    except (ValueError, KeyError, TypeError, StopIteration):
        fail('NATIVE_STAGE7_INVALID')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['validate-document'])
    parser.add_argument('path', type=Path)
    args = parser.parse_args()
    try:
        parse(read_regular(args.path, 16 * 1024 * 1024))
    except (ValueError, OSError, TypeError, UnicodeError):
        raise SystemExit('DR document refused; values are not logged') from None
    print('SCHEMA_VALID_ONLY_NOT_DR_PASS')


if __name__ == '__main__':
    main()
