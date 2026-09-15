"""AP-00 closed, non-secret provisioning declarations; never operational proof."""
import importlib.util
from pathlib import Path

_spec = importlib.util.spec_from_file_location('dr_evidence', Path(__file__).with_name('v126-dr-evidence.py'))
dr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dr)
S = dr.schema


def authority(write, read, observe):
    return {
        'principal_sha256': 'sha', 'capability_descriptor_sha256': 'sha',
        'credential_mechanism': S.enum('static-access-key', 'temporary-session'),
        'bundle_create_only': S.enum(write), 'exact_version_read': S.enum(read),
        'metadata_read': S.enum(observe), 'delete': S.enum(False),
        'admin': S.enum(False), 'governance_bypass': S.enum(False),
    }


SCHEMA = S.record('ap00-provisioning', {
    'target_sha256': 'sha', 'capacity_cap_bytes': 'positive',
    'monthly_budget_minor': 'positive', 'budget_currency': S.enum('RUB'),
    'bucket_policy': S.FILE, 'conditional_write_enforced': S.enum(True),
    'public_access_disabled': S.enum(True),
    'lifecycle_expiration': S.enum('ABSENT', 'PRESENT'),
    'expiration_after_seconds': S.optional('positive'),
    'writer': {**authority(True, False, False),
               # Uploader roles may also read/list. These facts must be explicit.
               'exact_version_read': S.enum(False, True), 'list': S.enum(False, True)},
    'independent_reader': {**authority(False, True, False), 'list': S.enum(False)},
    'metadata_observer': {**authority(False, False, True), 'list': S.enum(False)},
    'configuration_observation_sha256': 'sha',
})
MAX_DESCRIPTOR_BYTES = 16384
MAX_POLICY_BYTES = 65536


def target(raw, expected_sha256):
    """Current target schema remains authoritative; AP-00 narrows lock to compliance."""
    try:
        S.check(expected_sha256, 'sha')
        doc = dr.parse(raw, 'target')
        if dr.sha(raw) != expected_sha256 or doc['lock_mode'] != 'COMPLIANCE':
            raise ValueError
        return doc
    except Exception:
        raise ValueError('AP00_TARGET_INVALID') from None


def parse(raw, target_raw, target_sha256, policy_bytes):
    """Check declarations and exact byte references, not actual IAM enforcement.

    Policy bytes stay outside this descriptor. A future independent review must
    establish their semantics and acquire the referenced configuration observation.
    """
    try:
        destination = target(target_raw, target_sha256)
        if type(raw) is not bytes or not 0 < len(raw) <= MAX_DESCRIPTOR_BYTES:
            raise ValueError
        doc = dr.database.strict_json(raw)
        S.check(doc, SCHEMA)
        if raw != dr.canonical(doc) or doc['target_sha256'] != target_sha256:
            raise ValueError
        if type(policy_bytes) is not bytes or not 0 < len(policy_bytes) <= MAX_POLICY_BYTES:
            raise ValueError
        if doc['bucket_policy'] != dr.file_identity(policy_bytes):
            raise ValueError
        # Initial AP-00 allows no lifecycle expiry, including noncurrent versions.
        if doc['lifecycle_expiration'] != 'ABSENT' or doc['expiration_after_seconds'] is not None:
            raise ValueError
        if destination['retention_seconds'] != 604800:
            raise ValueError
        roles = [doc[x] for x in ('writer', 'independent_reader', 'metadata_observer')]
        for field in ('principal_sha256', 'capability_descriptor_sha256'):
            if len({role[field] for role in roles}) != 3:
                raise ValueError
        return doc
    except Exception:
        raise ValueError('AP00_PROVISIONING_INVALID') from None
