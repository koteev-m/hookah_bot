"""Policy B v1 executable, closed schemas (stdlib only).

No defaults, coercion, free text, secret values, locators with credentials, or
forward-compatible unknown fields. Hashes always refer to predecessor bytes.
Changing these bytes changes the tooling binding even without a version bump.
"""
import re
from datetime import datetime, timezone

VERSION = 1
POLICY = {
    'name': 'B', 'snapshot_seconds': 21600, 'qualification_seconds': 7200,
    'monitor_seconds': 300, 'monitor_unknown_seconds': 600,
    'retention_seconds': 604800, 'minimum_points': 2,
    'warning_seconds': 43200, 'critical_seconds': 72000,
    'fence_seconds': 79200, 'rpo_seconds': 86400,
    'clock_error_seconds': 60, 'dispatch_margin_seconds': 300,
}
PURPOSES = ('preparation', 'cutover-Q', 'product-periodic')
ARTIFACTS = (
    'application.dump', 'globals.sql', 'toc.list', 'roles.json', 'owners.json',
    'acl.json', 'settings.json', 'extensions.json', 'sequences.json',
    'flyway.json', 'queues.json', 'data-schema.json', 'safe-config.json',
    'authority-refs.json', 'tablespaces.json',
)
RESTORE_CHECKS = (
    'RESTORE_COMPLETE', 'ROLES_MEMBERSHIPS', 'OWNERSHIP_ACL',
    'SETTINGS_AUTH_CONFIG', 'DATA_SCHEMA', 'FLYWAY', 'SEQUENCES', 'DURABLE_QUEUES',
)
FUNCTIONAL_CHECKS = (
    'DB_AUTH', 'CUSTODY_REHYDRATION', 'SAFE_BACKEND', 'HEALTH',
    'MINIAPP_AUTH', 'JWT_RBAC', 'STATE_PRESERVATION', 'CLEANUP',
)
SAFE_OVERLAY = {
    'app_env': 'staging', 'traffic': 'PRODUCT', 'maintenance': 'V126_SMOKE',
    'bot': False, 'ai': False, 'geodata': False, 'billing': 'fake',
    'expiry': False, 'reminders': False, 'restart': 'no',
}
WRITERS = ('http_admission', 'telegram_polling', 'inbound_workers',
           'outbox_and_direct_senders', 'autonomous_database_writers')


def refuse():
    # Never echo an invalid field/value (it might be a credential).
    raise ValueError('DR_SCHEMA_INVALID')


def enum(*values):
    return ('enum', values)


def array(item, minimum=1):
    return ('array', item, minimum)


def optional(item):
    # Optional value, NOT optional key.
    return ('nullable', item)


def timestamp(value):
    if type(value) is not str or not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z', value):
        refuse()
    try:
        return int(datetime.strptime(value, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc).timestamp())
    except (ValueError, OverflowError):
        refuse()


def check(value, spec):
    if isinstance(spec, dict):
        if type(value) is not dict or set(value) != set(spec):
            refuse()
        for key, rule in spec.items():
            check(value[key], rule)
    elif isinstance(spec, tuple):
        if spec[0] == 'enum':
            if not any(type(value) is type(x) and value == x for x in spec[1]):
                refuse()
        elif spec[0] == 'nullable':
            if value is not None:
                check(value, spec[1])
        elif spec[0] == 'array':
            if type(value) is not list or not spec[2] <= len(value) <= 100000:
                refuse()
            for item in value:
                check(item, spec[1])
        else:
            refuse()
    elif spec == 'time':
        timestamp(value)
    elif spec in ('uint', 'positive', 'error'):
        maximum = 60 if spec == 'error' else 2**63 - 1
        minimum = 1 if spec == 'positive' else 0
        if type(value) is not int or not minimum <= value <= maximum:
            refuse()
    else:
        patterns = {
            'sha': r'[0-9a-f]{64}', 'git': r'[0-9a-f]{40}',
            'id': r'[a-z0-9][a-z0-9._-]{0,95}',
            'version': r'[A-Za-z0-9][A-Za-z0-9._+-]{0,127}',
            'object-version': r'[A-Za-z0-9][A-Za-z0-9._+/=-]{0,255}',
            'endpoint': r'https://[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?',
            'bucket': r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]',
            'key': r'bundles/[0-9a-f]{64}\.enc',
            'tool-path': r'(?:scripts|backend|docs)/[a-zA-Z0-9_./-]+',
        }
        if spec not in patterns or type(value) is not str or not re.fullmatch(patterns[spec], value):
            refuse()
        if spec in ('tool-path', 'endpoint', 'bucket') and ('..' in value or '/./' in value):
            refuse()
        if spec == 'object-version' and value.lower() in ('null', 'latest', 'none', 'unknown'):
            refuse()


FILE = {'sha256': 'sha', 'size': 'positive'}
CLOCK = {'utc': 'time', 'error_seconds': 'error', 'monotonic_seconds': 'uint', 'clock_id': 'sha'}
RUNTIME = {'source_sha': 'git', 'source_tree': 'git', 'image_sha256': 'sha',
           'pg_image_sha256': 'sha', 'migration_tree': 'git', 'config_epoch': 'sha'}
CUTOVER = {'run_id': 'id', 'stage7_sha256': 'sha', 'native_manifest_sha256': 'sha'}
CHECK = {'outcome': enum('PASS'), 'evidence_sha256': 'sha'}


def record(kind, fields):
    return {'schema_version': enum(VERSION), 'kind': enum(kind), **fields}


SCHEMAS = {
    'target': record('target', {
        'protocol': enum('s3-compatible'), 'endpoint': 'endpoint', 'bucket': 'bucket',
        'region': 'id', 'storage_class': enum('STANDARD'), 'account_ref': 'sha',
        'failure_domain_ref': 'sha', 'versioning': enum('ENABLED'),
        'object_lock': enum('ENABLED'), 'lock_mode': enum('GOVERNANCE', 'COMPLIANCE'),
        'retention_seconds': enum(604800),
    }),
    'custody': record('custody', {
        'owner': enum('user'), 'independent_of_source': enum(True),
        'independent_of_backup_credentials': enum(True),
        'versions': {k: 'sha' for k in ('decryption', 'secrets', 'config', 'images', 'evidence')},
    }),
    'binding': record('binding', {
        'policy_sha256': 'sha', 'source_sha': 'git', 'source_tree': 'git',
        'tools': array({'path': 'tool-path', 'version': 'version', 'sha256': 'sha'}),
        'python_version': 'version', 'target_sha256': 'sha', 'custody_sha256': 'sha',
        'source_identity_sha256': 'sha', 'database_semantics_sha256': 'sha',
        'data_runtime': RUNTIME, 'restore_runtime': RUNTIME, 'restore_recipe_sha256': 'sha',
    }),
    'authorization': record('authorization', {
        'binding_sha256': 'sha', 'actor': enum('user'), 'authorization_id': 'id',
        'purpose': enum(*PURPOSES), 'valid_from': 'time', 'valid_until': 'time',
        'packages': array(enum('AP-01', 'AP-02', 'AP-03', 'AP-04', 'AP-05', 'AP-06', 'AP-07')),
        'mode': enum('synthetic', 'operational'),
    }),
    'intent': record('intent', {
        'binding_sha256': 'sha', 'authorization_sha256': 'sha', 'attempt_id': 'id',
        'sequence': 'positive', 'purpose': enum(*PURPOSES), 'started': CLOCK,
        'previous_result_sha256': optional('sha'), 'cutover': optional(CUTOVER),
    }),
    'manifest': record('manifest', {
        'binding_sha256': 'sha', 'intent_sha256': 'sha', 'point': CLOCK,
        'snapshot_token_sha256': 'sha', 'consistency': enum('exported-snapshot', 'quiesced'),
        'admin_freeze_sha256': 'sha', 'globals_capture_intent_sha256': 'sha',
        'snapshot_consumers_sha256': 'sha',
        'artifacts': {name: FILE for name in ARTIFACTS},
    }),
    'result': record('result', {
        'intent_sha256': 'sha', 'outcome': enum('SUCCESS', 'FAILED', 'UNKNOWN'),
        'exit_code': optional('uint'), 'completed': CLOCK,
        'manifest_sha256': optional('sha'), 'archive': optional(FILE),
        'consumer_results_sha256': 'sha',
    }),
    'offhost': record('offhost', {
        'result_sha256': 'sha', 'manifest_sha256': 'sha', 'target_sha256': 'sha',
        'transport_sha256': 'sha',
        'key': 'key', 'version_id': 'object-version', 'ciphertext': FILE,
        'encryption': {'backend': 'id', 'backend_sha256': 'sha', 'format_version': 'version',
                       'custody_version_sha256': 'sha', 'mode': enum('synthetic', 'client-side')},
        'uploaded_at': 'time', 'read_at': 'time', 'retained_until': 'time',
        'writer_identity_sha256': 'sha', 'reader_identity_sha256': 'sha',
        'reader_failure_domain_sha256': 'sha', 'readback': FILE,
        'downloaded_archive': FILE, 'decrypted_members_sha256': 'sha',
        'custody_sha256': 'sha', 'exit_code': enum(0),
    }),
    'restore': record('restore', {
        'offhost_sha256': 'sha', 'downloaded_archive': FILE,
        'target_identity_sha256': 'sha', 'empty_target_sha256': 'sha',
        'egress_proof_sha256': 'sha', 'isolated_at': 'time', 'hydrated_at': 'time',
        'started_at': 'time', 'completed_at': 'time', 'platform': enum('linux/amd64'),
        'runtime': RUNTIME, 'recipe_sha256': 'sha', 'bootstrap_transform_sha256': 'sha',
        'auth_scope': enum('synthetic', 'real-custody'),
        'checks': {name: CHECK for name in RESTORE_CHECKS},
    }),
    'functional': record('functional', {
        'restore_sha256': 'sha', 'completed_at': 'time', 'auth_scope': enum('synthetic', 'real-custody'),
        'overlay': {k: enum(v) for k, v in SAFE_OVERLAY.items()},
        'checks': {name: CHECK for name in FUNCTIONAL_CHECKS},
    }),
    'qualification': record('qualification', {
        'binding_sha256': 'sha', 'intent_sha256': 'sha', 'result_sha256': 'sha',
        'manifest_sha256': 'sha', 'offhost_sha256': 'sha', 'restore_sha256': 'sha',
        'functional_sha256': 'sha', 'qualified_at': 'time',
        'status': enum('AVAILABLE_AND_FUNCTIONALLY_VERIFIED'),
    }),
    'event': record('event', {
        'sequence': 'positive', 'previous_sha256': optional('sha'),
        'document_kind': enum('intent', 'result', 'qualification'), 'document_sha256': 'sha',
    }),
    'ledger': record('ledger', {
        'binding_sha256': 'sha', 'events': array('sha'), 'head_sequence': 'positive',
    }),
    'response': record('response', {
        'binding_sha256': 'sha', 'authorization_sha256': 'sha', 'owner': enum('user'),
        'mode': enum('synthetic', 'operational'), 'executor_sha256': 'sha',
        'mechanism_evidence_sha256': 'sha', 'writers': {k: enum(True) for k in WRITERS},
        'trigger_age_seconds': enum(79200), 'response_budget_seconds': enum(300),
        'auto_restore': enum(False), 'auto_rollback': enum(False), 'auto_reopen': enum(False),
    }),
    'ongoing': record('ongoing', {
        'binding_sha256': 'sha', 'ledger_sha256': 'sha', 'authorization_sha256': 'sha', 'observed': CLOCK,
        'last_monitor': CLOCK, 'last_snapshot_due': 'time', 'response_authority_sha256': optional('sha'),
        'mechanism_sha256': 'sha', 'completed_periodic_qualification_sha256': 'sha',
        'failure_detection_test_sha256': 'sha', 'post_v126_recipe_sha256': 'sha',
    }),
    'readiness': record('readiness', {
        'binding_sha256': 'sha', 'ledger_sha256': 'sha', 'ongoing_sha256': 'sha',
        'authorization_sha256': 'sha',
        'qualification_sha256': 'sha', 'purpose': enum(*PURPOSES), 'checked': CLOCK,
        'age_seconds': 'uint', 'deadline': 'time', 'cutover': optional(CUTOVER),
        'result': enum('SYNTHETIC_POINT_PASS', 'DR_POINT_PASS'),
    }),
}


def validate(document, kind=None):
    if type(document) is not dict or document.get('kind') not in SCHEMAS:
        refuse()
    if kind is not None and document['kind'] != kind:
        refuse()
    check(document, SCHEMAS[document['kind']])
    return document
