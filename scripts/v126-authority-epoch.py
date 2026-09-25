#!/usr/bin/env python3
"""Closed prospective authority contracts; no producer, enrollment or write API.

The caller MUST obtain C/high-water/P from the independently enrolled protected
readers and U from the existing attended retained-digest ceremony. Canonical JSON
and matching hashes establish consistency, never the origin of a controller fact.
No request document can select those readers. Provider-specific acquisition
methods remain unenrollable until their complete interfaces are independently
reviewed. Fixtures exercise this consumer; they do not establish real isolation.
"""
from dataclasses import dataclass
import hashlib
import importlib.util
import ipaddress
import json
from pathlib import Path, PurePosixPath
import re
import sys

sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location('v126_epoch_schema', Path(__file__).with_name('v126-dr-schema.py'))
S = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(S)
MAX_BYTES = 16 * 1024 * 1024
F724 = 'v126-cutover-20260909t025024z-724dbe93'
F782 = 'v126-cutover-20260909t113822z-f7828e09'
F782_STATE = 'FAILED_PRE_ACTIVE_CONFIG_MUTATION_EXTERNALLY_FENCED'
SCOPES = ('PROSPECTIVE_ISOLATED_GENESIS', 'PROSPECTIVE_ISOLATED_GENESIS_COPY')
FACETS = {
    'compute': ('KERNEL_RUNTIME',),
    'daemons': ('DAEMON_PROCESS_CONTROL', 'DOCKER_REQUEST_AUTHORITY'),
    'storage': ('FILESYSTEM_WRITER',),
    'database': ('DATABASE_CONTROL',),
    'network_management': ('NETWORK_LISTENER_ROUTE', 'CREDENTIAL_PRINCIPAL', 'RECOVERY_DISPATCH'),
    'external_effects': ('EXTERNAL_API_BOT_QUEUE',),
    'authority': ('AUTHORITY_READER_WRITER',),
}
EDGES = tuple(edge for edges in FACETS.values() for edge in edges)


def refuse():
    raise ValueError('PROSPECTIVE_AUTHORITY_EPOCH_REFUSED') from None


def record(kind, fields):
    return {'schema_version': S.enum(2), 'kind': S.enum(kind), **fields}


def check(value, spec):
    if isinstance(spec, dict):
        if type(value) is not dict or set(value) != set(spec): refuse()
        for key, rule in spec.items(): check(value[key], rule)
    elif isinstance(spec, tuple):
        if spec[0] == 'array':
            if type(value) is not list or not spec[2] <= len(value) <= 4096: refuse()
            for item in value: check(item, spec[1])
        elif spec[0] == 'nullable':
            if value is not None: check(value, spec[1])
        elif spec[0] == 'enum':
            if not any(type(value) is type(x) and value == x for x in spec[1]): refuse()
        else: refuse()
    elif spec in ('path', 'runtime-path'):
        pattern = r'/[A-Za-z0-9_./@-]+' if spec == 'runtime-path' else r'/[A-Za-z0-9_./-]+'
        if type(value) is not str or not re.fullmatch(pattern, value) or len(value) > 4096 or str(PurePosixPath(value)) != value or '..' in PurePosixPath(value).parts: refuse()
    elif spec == 'fingerprint':
        if type(value) is not str or not re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', value): refuse()
    elif spec == 'ipv4':
        try:
            if type(value) is not str or str(ipaddress.IPv4Address(value)) != value: refuse()
        except ipaddress.AddressValueError: refuse()
    else: S.check(value, spec)


def canonical(value):
    try: return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False) + '\n').encode('ascii')
    except (ValueError, TypeError, UnicodeError): refuse()


def digest(value):
    return hashlib.sha256(value if type(value) is bytes else canonical(value)).hexdigest()


def strict(raw, spec=None):
    if type(raw) is not bytes or not 0 < len(raw) <= MAX_BYTES: refuse()
    def pairs(rows):
        result = {}
        for key, value in rows:
            if key in result: refuse()
            result[key] = value
        return result
    try: value = json.loads(raw, object_pairs_hook=pairs, parse_constant=lambda _: refuse())
    except (TypeError, ValueError, UnicodeError): refuse()
    if canonical(value) != raw: refuse()
    if spec is not None: check(value, spec)
    return value


def unique(rows, key=None):
    values = [row if key is None else row[key] for row in rows]
    if len(values) != len(set(values)): refuse()


TARGET = {'path': 'path', 'device': 'uint', 'inode': 'positive', 'uid': 'uint', 'host_fingerprint': 'fingerprint'}
SOURCE = {'commit': 'git', 'tree': 'git', 'tooling_sha256': 'sha'}
TOOLS = S.array({'path': 'tool-path', 'version': 'version', 'sha256': 'sha'})
EPOCH = record('authority-epoch-descriptor', {'nonce': 'sha', 'created': S.CLOCK,
    'bootstrap_source': SOURCE, 'domain_identity_sha256': 'sha', 'predecessor_index_sha256': 'sha',
    'owner_scope_decision_sha256': 'sha', 'control_policy_sha256': 'sha'})
PREDECESSOR_RUN = {'run_id': 'id', 'historical_outcome': S.enum('UNKNOWN', F782_STATE),
    'retained_references': S.array('sha'), 'missing': S.array('id'), 'intent_sha256': S.optional('sha')}
PREDECESSORS = record('authority-predecessor-index', {'legacy_domain_identity_sha256': 'sha',
    'legacy_target': TARGET, 'legacy_control_roots': S.array('sha'), 'legacy_resource_roots': S.array('sha'),
    'runs': S.array(PREDECESSOR_RUN)})
RESOURCE = {'identity_sha256': 'sha', 'controller_identity_sha256': 'sha', 'control_root_sha256': 'sha'}
DOMAIN = record('isolated-effect-domain', {'target': TARGET, 'host_instance_sha256': 'sha',
    'kernel_boot_sha256': 'sha', 'resources': {name: RESOURCE for name in FACETS},
    'external_policy': S.enum('DISCONNECTED'), 'predecessor_index_sha256': 'sha'})
BINDING = record('ap06-bootstrap-binding', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha',
    'source_sha': 'git', 'source_tree': 'git', 'tooling_sha256': 'sha', 'python_version': S.enum('3.12.3'),
    **{key: 'sha' for key in ('enrollment_facts_sha256', 'protected_selection_policy_sha256',
        'control_policy_sha256', 'producer_method_sha256', 'isolation_method_sha256', 'clock_method_sha256')}})
READER = {'identity': 'sha', 'owner_uid': 'uint', 'path': 'path'}
INITIAL = {'control_sequence': 'positive', 'control_head_sha256': 'sha', 'catalogue_generation': 'positive', 'revocation_generation': 'uint'}
BOOTSTRAP_ANCHOR = record('ap06-prospective-epoch-anchor', {'decision_id': 'id', 'decision_provenance_sha256': 'sha',
    'epoch_id': 'sha', 'epoch_descriptor_sha256': 'sha', 'domain_identity_sha256': 'sha', 'predecessor_index_sha256': 'sha',
    'generation': 'positive', 'revocation_generation': 'uint', 'valid_from': 'time', 'valid_until': 'time',
    'custodian_identity': 'sha', 'verifier_identity': 'sha', 'principal_fingerprint': 'fingerprint', 'host_fingerprint': 'fingerprint',
    'deployment_target': 'path', 'source_sha': 'git', 'source_tree': 'git', 'tooling_sha256': 'sha', 'python_version': S.enum('3.12.3'),
    'bootstrap_binding_sha256': 'sha', 'enrollment_spec_sha256': 'sha', 'isolation_method_sha256': 'sha',
    'protected_selection_policy_sha256': 'sha', 'sources': {name: READER for name in ('catalogue', 'highwater', 'producer', 'clock')},
    'evidence_root': 'path', 'scopes': S.array(S.enum(*SCOPES)), 'initial_checkpoint': INITIAL,
    'clock_id': 'sha', 'clock_method_sha256': 'sha'})
REQUEST = record('prospective-isolated-genesis-request', {'run_id': 'id', 'source_sha': 'git', 'source_tree': 'git',
    'script_sha256': 'sha', 'tooling_sha256': 'sha', 'python_version': S.enum('3.12.3'), 'target': TARGET,
    'epoch_id': 'sha', 'domain_identity_sha256': 'sha', 'predecessor_index_sha256': 'sha', 'isolation_proof_sha256': 'sha',
    'next_init_manifest_sha256': 'sha', 'created_at': 'time', 'expires_at': 'time', 'nonce': 'sha'})
IDENTITY = {'run_id': 'id', 'release_sha': 'git', 'script_sha256': 'sha', 'intent_sha256': 'sha',
    'kind': S.enum('INIT', 'STAGE', 'COPY_ONLY', 'TARGET_BIND'), 'name': 'version', 'action': 'id', 'epoch_id': 'sha', 'domain_identity_sha256': 'sha'}
OBSERVATIONS = record('ap06-prospective-observations', {'epoch_id': 'sha', 'source_identity': 'sha',
    'bootstrap_binding_sha256': 'sha', 'source_sha': 'git', 'source_tree': 'git', 'tooling_sha256': 'sha',
    'python_version': S.enum('3.12.3'), 'domain_identity_sha256': 'sha', 'observed': S.CLOCK, 'expires_at': 'time',
    'observed_documents': S.array('sha'), 'isolation_proof_sha256': 'sha', 'protected_observation_sha256': 'sha',
    'enrollment_facts_sha256': 'sha', 'new_epoch_inventory_sha256': 'sha', 'control_transactions_sha256': 'sha'})
CATALOGUE = record('ap06-prospective-bootstrap-catalogue', {'epoch_id': 'sha', 'source_identity': 'sha',
    'generation': 'positive', 'revocation_generation': 'uint', 'observed': S.CLOCK, 'expires_at': 'time',
    'control_ledger_sha256': 'sha', 'control_sequence': 'positive', 'control_head_sha256': 'sha',
    'checkpoint_sha256': 'sha', 'bootstrap_binding_sha256': 'sha', 'domain_identity_sha256': 'sha',
    'predecessor_index_sha256': 'sha', 'documents': S.array('sha'), 'revoked': S.array('sha', 0),
    'actions': S.array({'identity': IDENTITY, 'scope': S.enum(*SCOPES, 'DISPATCH', 'COPY_ONLY'), 'valid_from': 'time', 'valid_until': 'time'}),
    'completion_sha256': S.optional('sha')})
HIGHWATER = record('ap06-prospective-high-water', {'epoch_id': 'sha', 'source_identity': 'sha',
    'catalogue_generation': 'positive', 'anchor_generation': 'positive', 'revocation_generation': 'uint',
    'control_sequence': 'positive', 'control_head_sha256': 'sha', 'checkpoint_sha256': 'sha',
    'observed': S.CLOCK, 'expires_at': 'time'})
CONTROL_POLICY = record('authority-control-policy', {'writer_identity_sha256': 'sha', 'writer_method_sha256': 'sha',
    'producer_identity_sha256': 'sha', 'producer_method_sha256': 'sha', 'controller_method_sha256': 'sha',
    'controller_identities': S.array('sha'), 'verifier_identity_sha256': 'sha', 'custodian_identity_sha256': 'sha',
    'retention_routes': S.array({'route': S.enum('A', 'B'), 'controller_identity_sha256': 'sha',
        'observer_identity_sha256': 'sha', 'failure_domain_sha256': 'sha', 'method_sha256': 'sha'})})
CAPABILITY = {'edge_id': 'id', 'edge_class': S.enum(*EDGES), 'resource_sha256': 'sha',
    'controller_identity_sha256': 'sha', 'control_root_sha256': 'sha', 'subject_domain_sha256': 'sha',
    'write_policy': S.enum('EXCLUSIVE_NEW_DOMAIN', 'DISCONNECTED'), 'policy_sha256': 'sha'}
CONTROLLER = record('domain-controller-observation', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha',
    'source': SOURCE, 'method_sha256': 'sha', 'controller_identity_sha256': 'sha', 'facet': S.enum(*FACETS),
    'observed': S.CLOCK, 'expires_at': 'time', 'enumeration': S.enum('COMPLETE_INCOMING_CONTROL_CLOSURE'),
    'capabilities': S.array(CAPABILITY), 'evidence_sha256': 'sha'})
PROBE = record('domain-isolation-probe', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha',
    'source': SOURCE, 'method_sha256': 'sha', 'observer_identity_sha256': 'sha', 'facet': S.enum(*FACETS),
    'observed': S.CLOCK, 'expires_at': 'time', 'controller_record_sha256': 'sha',
    'probes': S.array({'edge_id': 'id', 'result': S.enum('OLD_DOMAIN_WRITE_DENIED'), 'transcript_sha256': 'sha'})})
PROOF = record('target-effect-domain-isolation-proof', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha',
    'target_identity': TARGET, 'source': SOURCE, 'method_sha256': 'sha', 'controller_provenance_sha256': 'sha',
    'observer_identity_sha256': 'sha', 'observed': S.CLOCK, 'expires_at': 'time',
    'facets': {name: {'controller_record_sha256': 'sha', 'observer_proof_sha256': 'sha'} for name in FACETS},
    'accepted_base_state_sha256': 'sha', 'predecessor_index_sha256': 'sha', 'acceptance_record_sha256': 'sha'})
ACCEPTANCE = record('domain-isolation-acceptance', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha',
    'proof_payload_sha256': 'sha', 'accepted_base_state_sha256': 'sha', 'approver_identity_sha256': 'sha',
    'decision_provenance_sha256': 'sha', 'observed': S.CLOCK, 'expires_at': 'time'})
BASE_STATE = record('prospective-base-state', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha',
    'target': TARGET, 'source': SOURCE, 'data_origin_policy_sha256': 'sha',
    'control_import': S.enum('NONE'), 'business_egress': S.enum('DISCONNECTED'),
    'observer_identity_sha256': 'sha', 'observed': S.CLOCK, 'expires_at': 'time'})
RUNTIME = record('ap06-python-runtime', {'python_version': S.enum('3.12.3'), 'executable_path': 'runtime-path',
    'executable_sha256': 'sha', 'role': S.enum('V', 'S'), 'closure_sha256': 'sha', 'origin_sha256': 'sha'})
ENROLLMENT = record('ap06-prospective-enrollment-facts', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha',
    'principal_fingerprint': 'fingerprint', 'principal_creation_record_sha256': 'sha', 'possession_record_sha256': 'sha',
    'excluded_principal_fingerprints': S.array('fingerprint'), 'host_fingerprint': 'fingerprint',
    'host_association_sha256': 'sha', 'verifier_identity_sha256': 'sha', 'verifier_control_sha256': 'sha',
    'endpoint': {'host': 'ipv4', 'port': S.enum(2226), 'user': S.enum('root'), 'peer': 'ipv4'},
    'source': SOURCE, 'python_version': S.enum('3.12.3'), 'verifier_runtime_sha256': 'sha', 'server_runtime_sha256': 'sha',
    'enrollment_spec_sha256': 'sha', 'observer_identity_sha256': 'sha', 'observed': S.CLOCK})
PROTECTED_POLICY = record('prospective-protected-selection-policy', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha',
    'custodian_identity_sha256': 'sha', 'evaluator_identity_sha256': 'sha', 'method_sha256': 'sha',
    'source_approval_sha256': 'sha', 'protected_version': 'object-version', 'subset_version': 'object-version',
    'predicates': S.array(S.enum('SEMANTIC_DATABASE', 'BACKEND_CONSUMER', 'GUEST_ADMISSION', 'OWNER_MEMBERSHIP', 'MIX_VENUE', 'STAFF_LINKS', 'EXCLUSIONS'))})
PROTECTED = record('prospective-protected-observation', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha',
    'selection_policy_sha256': 'sha', 'source': SOURCE, 'method_sha256': 'sha', 'observer_identity_sha256': 'sha',
    'protected_version': 'object-version', 'subset_version': 'object-version', 'observed': S.CLOCK, 'expires_at': 'time',
    'results': {key: {'result': S.enum('MATCH'), 'evidence_sha256': 'sha'} for key in
        ('semantic_database', 'backend_consumer', 'guest_admission', 'owner_membership', 'mix_venue', 'staff_links', 'exclusions')}})
INVENTORY = record('prospective-operation-inventory', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha', 'target': TARGET,
    'source': SOURCE, 'observed': S.CLOCK, 'expires_at': 'time', 'observer_identity_sha256': 'sha',
    'root_state': S.enum('ABSENT', 'DURABLE_COMPLETION'), 'completion_sha256': S.optional('sha'),
    'operation_ids': S.array('id', 0), 'unresolved': S.array('sha', 0)})
EVENT_PAYLOADS = {
    'EPOCH_ESTABLISHED': {'epoch_descriptor_sha256': 'sha', 'domain_identity_sha256': 'sha', 'predecessor_index_sha256': 'sha', 'creation_fact_sha256': 'sha', 'decision_provenance_sha256': 'sha'},
    'ENROLLMENT_FACTS_ACCEPTED': {'enrollment_facts_sha256': 'sha'},
    'PRODUCER_FACTS_ACCEPTED': {'bootstrap_binding_sha256': 'sha', 'protected_selection_policy_sha256': 'sha', 'producer_control_sha256': 'sha'},
    'ANCHOR_APPROVED': {'anchor_sha256': 'sha', 'generation': 'positive', 'approval_record_sha256': 'sha'},
    'ENROLLMENT_ACTIVATED': {'anchor_sha256': 'sha', 'enrollment_spec_sha256': 'sha', 'activation_record_sha256': 'sha'},
    'ACTION_AUTHORIZED': {'request_sha256': 'sha', 'scope': S.enum(*SCOPES, 'DISPATCH', 'COPY_ONLY'), 'approval_record_sha256': 'sha'},
    'REVOCATION_ADVANCED': {'revocation_generation': 'positive', 'revoked': S.array('sha')},
    'DR_BINDING_ATTACHED': {'binding_sha256': 'sha', 'source_identity_sha256': 'sha', 'dr_ledger_sha256': 'sha', 'dr_head_sha256': 'sha'},
}
EVENT = record('htqr-control-event', {'epoch_id': 'sha', 'sequence': 'positive', 'previous_event_sha256': S.optional('sha'),
    'event_type': S.enum(*EVENT_PAYLOADS), 'payload_sha256': 'sha', 'actor_identity_sha256': 'sha',
    'method_sha256': 'sha', 'observed': S.CLOCK, 'decision_provenance_sha256': 'sha'})
TRANSACTION = {'transaction_id': 'id', 'state': S.enum('COMMITTED'), 'event_sha256': 'sha',
    'publication_sha256': 'sha', 'retention_readback_sha256': 'sha'}
TRANSACTION_INVENTORY = record('htqr-control-transaction-inventory', {'epoch_id': 'sha',
    'observer_identity_sha256': 'sha', 'observed': S.CLOCK, 'expires_at': 'time',
    'enumeration': S.enum('COMPLETE_PERSISTENT_TRANSACTION_NAMESPACE'), 'transactions': S.array(TRANSACTION)})
LEDGER = record('htqr-control-ledger', {'epoch_id': 'sha', 'epoch_descriptor_sha256': 'sha', 'events': S.array('sha'),
    'head_sequence': 'positive', 'head_sha256': 'sha', 'transactions': S.array(TRANSACTION)})
CHECKPOINT = record('htqr-control-checkpoint', {'epoch_id': 'sha', 'control_sequence': 'positive', 'control_head_sha256': 'sha',
    'catalogue_generation': 'positive', 'anchor_generation': 'uint', 'revocation_generation': 'uint',
    'retention_manifest_sha256': 'sha', 'observer_identity_sha256': 'sha', 'method_sha256': 'sha', 'observed': S.CLOCK,
    'readbacks': S.array('sha')})
RETENTION = record('htqr-control-retention-manifest', {'epoch_id': 'sha', 'objects': S.array('sha')})
READBACK = record('htqr-control-retention-readback', {'epoch_id': 'sha', 'route': S.enum('A', 'B'),
    'retention_manifest_sha256': 'sha', 'controller_identity_sha256': 'sha', 'observer_identity_sha256': 'sha',
    'failure_domain_sha256': 'sha', 'method_sha256': 'sha', 'object_versions': S.array({'sha256': 'sha', 'version': 'object-version'}),
    'observed': S.CLOCK})
APPROVAL = record('htqr-anchor-approval', {'epoch_id': 'sha', 'anchor_sha256': 'sha', 'approver_identity_sha256': 'sha',
    'scope': S.array(S.enum(*SCOPES, 'DISPATCH', 'COPY_ONLY')), 'observed': S.CLOCK, 'retention_manifest_sha256': 'sha', 'readbacks': S.array('sha')})
PUBLICATION = record('htqr-control-publication', {'epoch_id': 'sha', 'event_sha256': 'sha',
    'transaction_id': 'id', 'durability': S.enum('FILE_AND_DIRECTORY_FSYNC_READBACK'), 'observer_identity_sha256': 'sha',
    'retention_manifest_sha256': 'sha'})
ACTIVATION = record('ap06-prospective-activation', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha', 'anchor_sha256': 'sha',
    'enrollment_spec_sha256': 'sha', 'enrollment_facts_sha256': 'sha', 'configuration_sha256': 'sha',
    'observer_identity_sha256': 'sha', 'observed': S.CLOCK})
ACTION_APPROVAL = record('htqr-prospective-action-approval', {'epoch_id': 'sha', 'domain_identity_sha256': 'sha',
    'request_sha256': 'sha', 'anchor_sha256': 'sha', 'scope': S.enum(*SCOPES, 'DISPATCH', 'COPY_ONLY'), 'approver_identity_sha256': 'sha',
    'valid_from': 'time', 'valid_until': 'time', 'observed': S.CLOCK})


def validate_request(request, *, identity=None, target=None):
    check(request, REQUEST)
    if len(canonical(request)) > 16 * 1024 or S.timestamp(request['created_at']) >= S.timestamp(request['expires_at']): refuse()
    if request['run_id'] in (F724, F782): refuse()
    if identity is not None and request_identity(request) != identity: refuse()
    if target is not None and request['target']['path'] != str(target): refuse()
    return request


def request_identity(request):
    return dict(run_id=request['run_id'], release_sha=request['source_sha'], script_sha256=request['script_sha256'],
        intent_sha256=digest(request), kind='TARGET_BIND', name='PROSPECTIVE_ISOLATED_GENESIS', action='bind-isolated-target',
        epoch_id=request['epoch_id'], domain_identity_sha256=request['domain_identity_sha256'])


identity = request_identity


def validate_anchor(doc):
    check(doc, BOOTSTRAP_ANCHOR)
    if doc['epoch_id'] != doc['epoch_descriptor_sha256'] or S.timestamp(doc['valid_from']) >= S.timestamp(doc['valid_until']): refuse()
    unique(doc['scopes']); unique(list(doc['sources'].values()), 'identity'); unique(list(doc['sources'].values()), 'path')
    root = PurePosixPath(doc['evidence_root'])
    for reader in doc['sources'].values():
        path = PurePosixPath(reader['path'])
        if path == root or root in path.parents: refuse()
    if doc['initial_checkpoint']['revocation_generation'] > doc['revocation_generation']: refuse()
    return doc


def validate_binding(doc):
    check(doc, BINDING)
    return doc


@dataclass(frozen=True)
class ProspectiveBasis:
    epoch_id: str
    domain_identity_sha256: str
    predecessor_index_sha256: str
    isolation_proof_sha256: str
    bootstrap_binding_sha256: str
    control_ledger_sha256: str
    control_sequence: int
    control_head_sha256: str
    checkpoint_sha256: str
    catalogue_generation: int
    revocation_generation: int
    enrollment_facts_sha256: str
    protected_observation_sha256: str
    request_sha256: str
    headroom_seconds: int
    event_payloads: tuple
    epoch_descriptor_json: str
    predecessor_index_json: str

    @property
    def headroom(self):
        return self.headroom_seconds


def _clock(value):
    check(value, S.CLOCK)
    if not 1 <= value['error_seconds'] <= 60: refuse()


def _chronology(before, after):
    _clock(before); _clock(after)
    if S.timestamp(before['utc']) > S.timestamp(after['utc']): refuse()
    if before['clock_id'] == after['clock_id']:
        wall = S.timestamp(after['utc']) - S.timestamp(before['utc'])
        mono = after['monotonic_seconds'] - before['monotonic_seconds']
        if mono < 0 or abs(wall - mono) > before['error_seconds'] + after['error_seconds']: refuse()


def _current(doc, now, reserve=0):
    _clock(now); stamp = doc['observed']; _clock(stamp); _chronology(stamp, now)
    if stamp['error_seconds'] > 59: refuse()
    if stamp['clock_id'] != now['clock_id']: refuse()
    age = S.timestamp(now['utc']) + now['error_seconds'] - S.timestamp(stamp['utc']) + stamp['error_seconds']
    remaining = S.timestamp(doc['expires_at']) - S.timestamp(now['utc']) - now['error_seconds'] - reserve
    if age > 300 or remaining <= 0: refuse()
    return min(300 - age, remaining)


def _window(start, end, now, reserve=0):
    low = S.timestamp(now['utc']) - now['error_seconds']
    high = S.timestamp(now['utc']) + now['error_seconds'] + reserve
    if S.timestamp(start) > low or S.timestamp(end) <= high: refuse()
    return S.timestamp(end) - high


def _same_epoch(doc, anchor):
    if doc.get('epoch_id') != anchor['epoch_id']: refuse()
    if 'domain_identity_sha256' in doc and doc['domain_identity_sha256'] != anchor['domain_identity_sha256']: refuse()


def _revocation_pins(value, spec):
    """Collect only closed-schema SHA pins, including inline actor/method IDs.

    Reading a content-addressed record is insufficient: an active controller or
    method may be named inside it without being a document object of its own.
    Historical records are deliberately excluded by the caller.
    """
    if spec == 'sha': return {value}
    if isinstance(spec, dict):
        return set().union(*(_revocation_pins(value[key], rule) for key, rule in spec.items()))
    if isinstance(spec, tuple):
        if spec[0] == 'array': return set().union(*(_revocation_pins(item, spec[1]) for item in value))
        if spec[0] == 'nullable' and value is not None: return _revocation_pins(value, spec[1])
    return set()


def validate_basis(request, anchor, catalogue, highwater, observations, documents, now, *, readback=False, checkpoint=None, _full_identity=None, _full_scope=None):
    """Validate an independently acquired complete authority snapshot, never enroll it."""
    full = _full_identity is not None
    if full:
        validate_full_anchor(anchor); check(_full_identity, IDENTITY)
        if _full_scope not in ('DISPATCH', 'COPY_ONLY') or _full_scope not in anchor['scopes']: refuse()
        if _full_identity['epoch_id'] != anchor['epoch_id'] or _full_identity['domain_identity_sha256'] != anchor['domain_identity_sha256']: refuse()
    else:
        validate_request(request); validate_anchor(anchor)
    initial = anchor['control_checkpoint'] if full else anchor['initial_checkpoint']
    if checkpoint is not None:
        check(checkpoint, INITIAL)
        if any(checkpoint[k] < initial[k] for k in ('control_sequence', 'catalogue_generation', 'revocation_generation')): refuse()
        initial = checkpoint
    check(catalogue, CATALOGUE); check(highwater, HIGHWATER); check(observations, OBSERVATIONS)
    if type(documents) is not dict or sum(len(x) for x in documents.values() if type(x) is bytes) > MAX_BYTES: refuse()
    for ref, raw in documents.items():
        check(ref, 'sha')
        if type(raw) is not bytes or digest(raw) != ref: refuse()
    unique(catalogue['documents']); unique(observations['observed_documents']); unique(catalogue['revoked'])
    selected = set(catalogue['documents']); observed = set(observations['observed_documents'])
    if not selected <= observed or not observed <= set(documents): refuse()
    consumed = set(); historical = False
    anchor_spec = FULL_ANCHOR if full else BOOTSTRAP_ANCHOR
    # The prior bootstrap U and retained prefix are ancestry, not current grants.
    current_anchor_spec = {key: rule for key, rule in anchor_spec.items()
        if key not in ('bootstrap_anchor_sha256', 'initial_checkpoint', 'control_checkpoint')}
    active_pins = _revocation_pins(anchor, current_anchor_spec)
    active_pins |= _revocation_pins(highwater, HIGHWATER)
    active_pins |= _revocation_pins(observations, {key: rule for key, rule in OBSERVATIONS.items()
        if key != 'observed_documents'})
    active_pins |= _revocation_pins(catalogue, {key: rule for key, rule in CATALOGUE.items()
        if key not in ('documents', 'revoked', 'actions')})
    def get(ref, spec=None):
        if ref not in selected or ref not in observed or (not historical and ref in catalogue['revoked']): refuse()
        consumed.add(ref)
        value = strict(documents[ref], spec)
        if not historical and spec is not None and spec is not PREDECESSORS:
            active_pins.update(_revocation_pins(value, spec))
        return value
    source = dict(commit=anchor['source_sha'], tree=anchor['source_tree'], tooling_sha256=anchor['tooling_sha256'])
    for doc in (catalogue, highwater, observations): _same_epoch(doc, anchor)
    if not full: _same_epoch(request, anchor)
    for name, doc in (('catalogue', catalogue), ('highwater', highwater), ('producer', observations)):
        if doc['source_identity'] != anchor['sources'][name]['identity']: refuse()
    for key in ('source_sha', 'source_tree', 'tooling_sha256', 'python_version'):
        if observations[key] != anchor[key] or (not full and request[key] != anchor[key]): refuse()
    for doc in ((catalogue,) if full else (request, catalogue)):
        if doc['predecessor_index_sha256'] != anchor['predecessor_index_sha256']: refuse()
    if not full and (request['target']['host_fingerprint'] != anchor['host_fingerprint'] or request['target']['path'] != anchor['deployment_target']): refuse()
    if digest(anchor) in catalogue['revoked'] or anchor['epoch_id'] in catalogue['revoked']: refuse()
    if catalogue['bootstrap_binding_sha256'] != anchor['bootstrap_binding_sha256'] or observations['bootstrap_binding_sha256'] != anchor['bootstrap_binding_sha256']: refuse()
    if not full and not readback and observations['isolation_proof_sha256'] != request['isolation_proof_sha256']: refuse()
    headrooms = [_current(x, now) for x in (catalogue, highwater, observations)]
    headrooms += [_window(anchor['valid_from'], anchor['valid_until'], now)]
    if not full and not readback: headrooms.append(_window(request['created_at'], request['expires_at'], now))
    if now['clock_id'] != anchor['clock_id']: refuse()
    for key in ('control_sequence', 'control_head_sha256', 'revocation_generation'):
        if catalogue[key] != highwater[key]: refuse()
    if (highwater['catalogue_generation'] != catalogue['generation'] or highwater['anchor_generation'] != anchor['generation']
            or highwater['checkpoint_sha256'] != catalogue['checkpoint_sha256']
            or catalogue['generation'] < initial['catalogue_generation']
            or catalogue['revocation_generation'] < anchor['revocation_generation']): refuse()
    get(anchor['decision_provenance_sha256'])
    epoch = get(anchor['epoch_id'], EPOCH)
    predecessor = get(anchor['predecessor_index_sha256'], PREDECESSORS)
    domain = get(anchor['domain_identity_sha256'], DOMAIN)
    binding = get(anchor['bootstrap_binding_sha256'], BINDING)
    policy = get(epoch['control_policy_sha256'], CONTROL_POLICY)
    tools = get(anchor['tooling_sha256'], TOOLS); unique(tools, 'path')
    if epoch['domain_identity_sha256'] != digest(domain) or epoch['predecessor_index_sha256'] != digest(predecessor) or domain['predecessor_index_sha256'] != digest(predecessor): refuse()
    if (domain['target']['path'] != anchor['deployment_target'] or domain['target']['host_fingerprint'] != anchor['host_fingerprint']
            or (not full and domain['target'] != request['target']) or domain['target']['host_fingerprint'] == predecessor['legacy_target']['host_fingerprint']): refuse()
    if digest(domain) == predecessor['legacy_domain_identity_sha256']: refuse()
    unique(predecessor['runs'], 'run_id'); unique(predecessor['legacy_control_roots']); unique(predecessor['legacy_resource_roots'])
    runs = {row['run_id']: row for row in predecessor['runs']}
    if F724 not in runs or F782 not in runs or runs[F724]['historical_outcome'] != 'UNKNOWN' or runs[F782]['historical_outcome'] != F782_STATE: refuse()
    acting_identity = _full_identity if full else request_identity(request)
    if acting_identity['run_id'] in runs or acting_identity['intent_sha256'] in {row['intent_sha256'] for row in runs.values()}: refuse()
    for row in runs.values():
        unique(row['retained_references']); unique(row['missing'])
        for ref in row['retained_references']: get(ref)
    for facet, resource in domain['resources'].items():
        if resource['control_root_sha256'] in predecessor['legacy_control_roots'] or resource['identity_sha256'] in predecessor['legacy_resource_roots']: refuse()
        if resource['controller_identity_sha256'] not in policy['controller_identities']: refuse()
    _same_epoch(binding, anchor)
    for key in ('source_sha', 'source_tree', 'tooling_sha256', 'python_version', 'protected_selection_policy_sha256', 'isolation_method_sha256', 'clock_method_sha256'):
        if binding[key] != anchor[key]: refuse()
    if binding['control_policy_sha256'] != digest(policy) or binding['producer_method_sha256'] != policy['producer_method_sha256']: refuse()
    if policy['producer_identity_sha256'] != anchor['sources']['producer']['identity'] or policy['verifier_identity_sha256'] != anchor['verifier_identity'] or policy['custodian_identity_sha256'] != anchor['custodian_identity']: refuse()
    if full:
        dr_binding = get(anchor['binding_sha256'], S.SCHEMAS['binding'])
        source_identity = get(dr_binding['source_identity_sha256'], DR_SOURCE_IDENTITY)
        validate_dr_source_identity(source_identity, dr_binding, anchor)
    distinct = [policy['writer_identity_sha256'], policy['producer_identity_sha256'], policy['verifier_identity_sha256']]
    unique(distinct); unique(policy['controller_identities'])
    if policy['custodian_identity_sha256'] in (policy['writer_identity_sha256'], policy['producer_identity_sha256']): refuse()
    routes = policy['retention_routes']; unique(routes, 'route'); unique(routes, 'observer_identity_sha256'); unique(routes, 'failure_domain_sha256')
    if {row['route'] for row in routes} != {'A', 'B'}: refuse()
    for route in routes:
        if route['controller_identity_sha256'] in distinct or route['observer_identity_sha256'] in distinct: refuse()
    def retain(manifest_ref, readback_refs, required):
        manifest = get(manifest_ref, RETENTION); _same_epoch(manifest, anchor); unique(manifest['objects'])
        if not set(required) <= set(manifest['objects']) or manifest_ref in manifest['objects']: refuse()
        for ref in manifest['objects']: get(ref)
        unique(readback_refs)
        if len(readback_refs) != 2: refuse()
        seen_routes = set()
        for ref in readback_refs:
            row = get(ref, READBACK); _same_epoch(row, anchor)
            if row['retention_manifest_sha256'] != manifest_ref: refuse()
            pin = next((x for x in routes if x['route'] == row['route']), None)
            if pin is None or any(row[key] != pin[key] for key in pin): refuse()
            seen_routes.add(row['route']); unique(row['object_versions'], 'sha256')
            if {x['sha256'] for x in row['object_versions']} != set(manifest['objects']): refuse()
            _chronology(row['observed'], now)
        if seen_routes != {'A', 'B'}: refuse()
    enrollment = get(binding['enrollment_facts_sha256'], ENROLLMENT); _same_epoch(enrollment, anchor)
    if enrollment['source'] != source or enrollment['enrollment_spec_sha256'] != anchor['enrollment_spec_sha256'] or enrollment['python_version'] != anchor['python_version']: refuse()
    for key in ('principal_fingerprint', 'host_fingerprint'):
        if enrollment[key] != anchor[key]: refuse()
    unique(enrollment['excluded_principal_fingerprints'])
    if enrollment['principal_fingerprint'] in enrollment['excluded_principal_fingerprints'] or enrollment['host_fingerprint'] == predecessor['legacy_target']['host_fingerprint']: refuse()
    if enrollment['verifier_identity_sha256'] != anchor['verifier_identity'] or enrollment['observer_identity_sha256'] != policy['producer_identity_sha256']: refuse()
    if enrollment['endpoint']['host'] == '178.20.209.5': refuse()
    for key in ('principal_creation_record_sha256', 'possession_record_sha256', 'host_association_sha256', 'verifier_control_sha256', 'verifier_runtime_sha256', 'server_runtime_sha256', 'enrollment_spec_sha256'): get(enrollment[key])
    for role, key in (('V', 'verifier_runtime_sha256'), ('S', 'server_runtime_sha256')):
        runtime = get(enrollment[key], RUNTIME)
        if runtime['role'] != role or runtime['python_version'] != enrollment['python_version']: refuse()
        get(runtime['closure_sha256']); get(runtime['origin_sha256'])
    if observations['enrollment_facts_sha256'] != digest(enrollment): refuse()
    selection = get(anchor['protected_selection_policy_sha256'], PROTECTED_POLICY); _same_epoch(selection, anchor)
    unique(selection['predicates'])
    if set(selection['predicates']) != {'SEMANTIC_DATABASE', 'BACKEND_CONSUMER', 'GUEST_ADMISSION', 'OWNER_MEMBERSHIP', 'MIX_VENUE', 'STAFF_LINKS', 'EXCLUSIONS'}: refuse()
    if selection['custodian_identity_sha256'] != anchor['custodian_identity'] or selection['evaluator_identity_sha256'] in distinct[:1]: refuse()
    get(selection['source_approval_sha256'])
    protected = get(observations['protected_observation_sha256'], PROTECTED); _same_epoch(protected, anchor)
    if (protected['selection_policy_sha256'] != digest(selection) or protected['source'] != source or protected['method_sha256'] != selection['method_sha256']
            or protected['observer_identity_sha256'] != selection['evaluator_identity_sha256']
            or any(protected[key] != selection[key] for key in ('protected_version', 'subset_version'))): refuse()
    headrooms.append(_current(protected, now, 900))
    for result in protected['results'].values(): get(result['evidence_sha256'])
    proof = get(observations['isolation_proof_sha256'], PROOF); _same_epoch(proof, anchor)
    if (proof['source'] != source or proof['target_identity'] != domain['target'] or proof['method_sha256'] != anchor['isolation_method_sha256']
            or proof['controller_provenance_sha256'] != digest(policy) or proof['observer_identity_sha256'] != policy['producer_identity_sha256']
            or proof['predecessor_index_sha256'] != digest(predecessor)): refuse()
    headrooms.append(_current(proof, now, 900))
    all_edges = set()
    for facet, refs in proof['facets'].items():
        controller = get(refs['controller_record_sha256'], CONTROLLER); _same_epoch(controller, anchor)
        probe = get(refs['observer_proof_sha256'], PROBE); _same_epoch(probe, anchor)
        resource = domain['resources'][facet]
        if (controller['source'] != source or controller['facet'] != facet or controller['method_sha256'] != policy['controller_method_sha256']
                or controller['controller_identity_sha256'] != resource['controller_identity_sha256']): refuse()
        if (probe['source'] != source or probe['facet'] != facet or probe['method_sha256'] != anchor['isolation_method_sha256']
                or probe['observer_identity_sha256'] != policy['producer_identity_sha256'] or probe['controller_record_sha256'] != digest(controller)): refuse()
        headrooms += [_current(controller, now, 900), _current(probe, now, 900)]
        unique(controller['capabilities'], 'edge_id'); unique(probe['probes'], 'edge_id')
        if {x['edge_id'] for x in probe['probes']} != {x['edge_id'] for x in controller['capabilities']}: refuse()
        if {x['edge_class'] for x in controller['capabilities']} != set(FACETS[facet]): refuse()
        for cap in controller['capabilities']:
            if cap['edge_id'] in all_edges: refuse()
            all_edges.add(cap['edge_id'])
            if (cap['resource_sha256'] != resource['identity_sha256'] or cap['controller_identity_sha256'] != resource['controller_identity_sha256']
                    or cap['control_root_sha256'] != resource['control_root_sha256'] or cap['subject_domain_sha256'] != digest(domain)): refuse()
            expected = 'DISCONNECTED' if facet == 'external_effects' else 'EXCLUSIVE_NEW_DOMAIN'
            if cap['write_policy'] != expected: refuse()
            get(cap['policy_sha256'])
        get(controller['evidence_sha256'])
        for probe_row in probe['probes']: get(probe_row['transcript_sha256'])
    base = get(proof['accepted_base_state_sha256'], BASE_STATE); _same_epoch(base, anchor)
    if base['target'] != domain['target'] or base['source'] != source or base['observer_identity_sha256'] != policy['producer_identity_sha256']: refuse()
    get(base['data_origin_policy_sha256']); headrooms.append(_current(base, now, 900))
    acceptance = get(proof['acceptance_record_sha256'], ACCEPTANCE); _same_epoch(acceptance, anchor)
    payload = dict(proof); del payload['acceptance_record_sha256']
    if (acceptance['proof_payload_sha256'] != digest(payload) or acceptance['accepted_base_state_sha256'] != digest(base)
            or acceptance['approver_identity_sha256'] != anchor['custodian_identity']): refuse()
    get(acceptance['decision_provenance_sha256']); headrooms.append(_current(acceptance, now, 900))
    inventory = get(observations['new_epoch_inventory_sha256'], INVENTORY); _same_epoch(inventory, anchor)
    if inventory['target'] != domain['target'] or inventory['source'] != source or inventory['observer_identity_sha256'] != policy['producer_identity_sha256'] or inventory['unresolved']: refuse()
    unique(inventory['operation_ids'])
    if set(inventory['operation_ids']) & set(runs): refuse()
    if full:
        if inventory['root_state'] != 'DURABLE_COMPLETION' or inventory['completion_sha256'] != catalogue['completion_sha256'] or catalogue['completion_sha256'] is None: refuse()
    elif catalogue['completion_sha256'] is None:
        if inventory['root_state'] != 'ABSENT' or inventory['completion_sha256'] is not None or inventory['operation_ids']: refuse()
    elif not readback or inventory['root_state'] != 'DURABLE_COMPLETION' or inventory['completion_sha256'] != catalogue['completion_sha256']: refuse()
    headrooms.append(_current(inventory, now))
    active_refs = set(consumed) | {digest(anchor), digest(request) if not full else digest(_full_identity)}
    historical = True
    ledger = get(catalogue['control_ledger_sha256'], LEDGER); _same_epoch(ledger, anchor)
    if (ledger['epoch_descriptor_sha256'] != anchor['epoch_id'] or ledger['head_sequence'] != len(ledger['events'])
            or ledger['head_sha256'] != ledger['events'][-1] or ledger['head_sequence'] != catalogue['control_sequence']
            or ledger['head_sha256'] != catalogue['control_head_sha256']): refuse()
    unique(ledger['events']); unique(ledger['transactions'], 'transaction_id'); unique(ledger['transactions'], 'event_sha256')
    transactions = get(observations['control_transactions_sha256'], TRANSACTION_INVENTORY); _same_epoch(transactions, anchor)
    if transactions['observer_identity_sha256'] != policy['producer_identity_sha256'] or transactions['transactions'] != ledger['transactions']: refuse()
    headrooms.append(_current(transactions, now))
    if {row['event_sha256'] for row in ledger['transactions']} != set(ledger['events']): refuse()
    if not full and readback != (catalogue['completion_sha256'] is not None): refuse()
    previous = None; previous_clock = None; seen_types = []; payloads = []; revoked = set(); revocation = 0; anchor_generation = 0
    approved_anchors = set(); approved_documents = {}; activated_anchors = set(); action_authorized = False
    current_events = set(); event_dependencies = {}; current_action = None
    active_control_refs = set()
    for index, ref in enumerate(ledger['events'], 1):
        event = get(ref, EVENT); _same_epoch(event, anchor)
        get(event['decision_provenance_sha256'])
        if event['sequence'] != index or event['previous_event_sha256'] != previous or event['actor_identity_sha256'] != policy['writer_identity_sha256'] or event['method_sha256'] != policy['writer_method_sha256']: refuse()
        _chronology(event['observed'], now)
        if previous_clock is not None: _chronology(previous_clock, event['observed'])
        typ = event['event_type']; body = get(event['payload_sha256'], record('htqr-control-' + typ.lower().replace('_', '-'), EVENT_PAYLOADS[typ]))
        for key, value in body.items():
            if key.endswith('_sha256') and key not in ('domain_identity_sha256', 'source_identity_sha256'): get(value)
        if index == 1:
            if typ != 'EPOCH_ESTABLISHED' or body['epoch_descriptor_sha256'] != anchor['epoch_id'] or body['domain_identity_sha256'] != digest(domain) or body['predecessor_index_sha256'] != digest(predecessor) or body['decision_provenance_sha256'] != epoch['owner_scope_decision_sha256']: refuse()
        elif typ == 'EPOCH_ESTABLISHED': refuse()
        if typ in ('EPOCH_ESTABLISHED', 'ENROLLMENT_FACTS_ACCEPTED', 'PRODUCER_FACTS_ACCEPTED'):
            current_events.add(ref)
            active_control_refs.update(_revocation_pins(body, record('htqr-control-' + typ.lower().replace('_', '-'), EVENT_PAYLOADS[typ])))
        if typ == 'ENROLLMENT_FACTS_ACCEPTED':
            if typ in seen_types or body['enrollment_facts_sha256'] != digest(enrollment): refuse()
        if typ == 'PRODUCER_FACTS_ACCEPTED':
            if 'ENROLLMENT_FACTS_ACCEPTED' not in seen_types or typ in seen_types or body['bootstrap_binding_sha256'] != digest(binding) or body['protected_selection_policy_sha256'] != digest(selection): refuse()
        if typ == 'ANCHOR_APPROVED':
            if 'PRODUCER_FACTS_ACCEPTED' not in seen_types or body['generation'] <= anchor_generation: refuse()
            approved = get(body['anchor_sha256'])
            if approved.get('kind') == 'ap06-prospective-epoch-anchor': validate_anchor(approved)
            else: validate_full_anchor(approved)
            if anchor_generation == 0 and body['generation'] != 1: refuse()
            approved_checkpoint = approved['control_checkpoint'] if approved['kind'] == 'ap06-prospective-full-anchor' else approved['initial_checkpoint']
            prefix_sequence = approved_checkpoint['control_sequence']
            if not 3 <= prefix_sequence < index or ledger['events'][prefix_sequence - 1] != approved_checkpoint['control_head_sha256']: refuse()
            approval = get(body['approval_record_sha256'], APPROVAL)
            _same_epoch(approved, anchor); _same_epoch(approval, anchor)
            get(approved['decision_provenance_sha256'])
            if (approved['generation'] != body['generation'] or approval['anchor_sha256'] != body['anchor_sha256']
                    or approval['approver_identity_sha256'] != anchor['custodian_identity'] or approval['scope'] != approved['scopes']): refuse()
            _chronology(approval['observed'], event['observed'])
            retain(approval['retention_manifest_sha256'], approval['readbacks'], [body['anchor_sha256']])
            approved_anchors.add(body['anchor_sha256']); approved_documents[body['anchor_sha256']] = approved; anchor_generation = body['generation']
            if body['anchor_sha256'] == digest(anchor):
                current_events.add(ref)
                active_control_refs.update({body['approval_record_sha256'], approval['retention_manifest_sha256'], *approval['readbacks']})
        if typ == 'ENROLLMENT_ACTIVATED':
            if body['anchor_sha256'] not in approved_anchors: refuse()
            activation = get(body['activation_record_sha256'], ACTIVATION); _same_epoch(activation, anchor)
            if (activation['anchor_sha256'] != body['anchor_sha256'] or activation['enrollment_spec_sha256'] != body['enrollment_spec_sha256']
                    or body['enrollment_spec_sha256'] != anchor['enrollment_spec_sha256'] or activation['enrollment_facts_sha256'] != digest(enrollment)
                    or activation['observer_identity_sha256'] != policy['producer_identity_sha256']): refuse()
            get(activation['configuration_sha256']); _chronology(activation['observed'], event['observed'])
            activated_anchors.add(body['anchor_sha256'])
            if body['anchor_sha256'] == digest(anchor):
                current_events.add(ref)
                active_control_refs.add(body['activation_record_sha256'])
                active_control_refs.update(_revocation_pins(activation, ACTIVATION))
        if typ == 'ACTION_AUTHORIZED':
            approval = get(body['approval_record_sha256'], ACTION_APPROVAL); _same_epoch(approval, anchor)
            if approval['request_sha256'] != body['request_sha256'] or approval['scope'] != body['scope'] or approval['approver_identity_sha256'] != anchor['custodian_identity']: refuse()
            if approval['anchor_sha256'] not in activated_anchors or body['scope'] not in approved_documents[approval['anchor_sha256']]['scopes']: refuse()
            _chronology(approval['observed'], event['observed'])
            proposal = get(body['request_sha256'])
            if body['scope'] in SCOPES: validate_request(proposal)
            else: check(proposal, IDENTITY)
            _same_epoch(proposal, anchor)
            if proposal['run_id'] in runs: refuse()
            if 'created_at' in proposal and S.timestamp(proposal['created_at']) > S.timestamp(approval['observed']['utc']): refuse()
            proposal_ref = digest(_full_identity) if full else digest(request)
            expected_scope = _full_scope if full else SCOPES[1] if readback else SCOPES[0]
            if body['request_sha256'] == proposal_ref and body['scope'] == expected_scope and approval['anchor_sha256'] == digest(anchor):
                if full and proposal != _full_identity: refuse()
                # The latest exact current approval supersedes prior approvals,
                # whose revocation/expiry remains descriptive ledger history.
                current_action = (ref, body['approval_record_sha256'], approval)
                action_authorized = True
        if typ == 'REVOCATION_ADVANCED':
            unique(body['revoked'])
            if body['revocation_generation'] <= revocation or not revoked < set(body['revoked']): refuse()
            revocation = body['revocation_generation']; revoked = set(body['revoked'])
        if typ == 'DR_BINDING_ATTACHED':
            historical_source_identity = get(body['source_identity_sha256'], DR_SOURCE_IDENTITY)
            _same_epoch(historical_source_identity, anchor)
            attached_binding = get(body['binding_sha256'], S.SCHEMAS['binding'])
            if attached_binding['source_identity_sha256'] != body['source_identity_sha256']: refuse()
        if full and typ == 'DR_BINDING_ATTACHED' and body['binding_sha256'] == anchor['binding_sha256']:
            current_events.add(ref)
            active_control_refs.update(_revocation_pins(body, record('htqr-control-dr-binding-attached', EVENT_PAYLOADS[typ])))
        transaction = next(row for row in ledger['transactions'] if row['event_sha256'] == ref)
        publication = get(transaction['publication_sha256'], PUBLICATION); _same_epoch(publication, anchor)
        if publication['event_sha256'] != ref or publication['transaction_id'] != transaction['transaction_id'] or publication['observer_identity_sha256'] not in {x['observer_identity_sha256'] for x in routes}: refuse()
        transaction_readback = get(transaction['retention_readback_sha256'], READBACK); _same_epoch(transaction_readback, anchor)
        if not any(all(transaction_readback[key] == pin[key] for key in pin) for pin in routes): refuse()
        _chronology(event['observed'], transaction_readback['observed'])
        if transaction_readback['retention_manifest_sha256'] != publication['retention_manifest_sha256']: refuse()
        # Each publication is independently retained; the final checkpoint proves
        # both routes for the entire closure including transaction publications.
        retention = get(publication['retention_manifest_sha256'], RETENTION)
        _same_epoch(retention, anchor); unique(retention['objects']); unique(transaction_readback['object_versions'], 'sha256')
        if ({x['sha256'] for x in transaction_readback['object_versions']} != set(retention['objects'])
                or ref not in retention['objects'] or event['payload_sha256'] not in retention['objects']): refuse()
        event_dependencies[ref] = {ref, event['payload_sha256'], event['decision_provenance_sha256'],
            event['actor_identity_sha256'], event['method_sha256'], transaction['publication_sha256'],
            transaction['retention_readback_sha256'], publication['retention_manifest_sha256']}
        payloads.append((typ, canonical(body))); seen_types.append(typ); previous = ref; previous_clock = event['observed']
    expected_scope = _full_scope if full else SCOPES[1] if readback else SCOPES[0]
    matches = [x for x in catalogue['actions'] if x['identity'] == acting_identity and x['scope'] == expected_scope]
    if len(matches) != 1 or expected_scope not in anchor['scopes']: refuse()
    headrooms.append(_window(matches[0]['valid_from'], matches[0]['valid_until'], now))
    if (digest(anchor) not in approved_anchors or digest(anchor) not in activated_anchors or not action_authorized
            or anchor_generation != anchor['generation'] or revocation != catalogue['revocation_generation'] or revoked != set(catalogue['revoked'])): refuse()
    if current_action is None: refuse()
    action_event, action_approval_ref, action_approval = current_action
    headrooms.append(_window(action_approval['valid_from'], action_approval['valid_until'], now))
    current_events.add(action_event); active_control_refs.add(action_approval_ref)
    active_control_refs.update(_revocation_pins(action_approval, ACTION_APPROVAL))
    for ref in current_events: active_control_refs.update(event_dependencies[ref])
    checkpoint = get(catalogue['checkpoint_sha256'], CHECKPOINT); _same_epoch(checkpoint, anchor)
    for key in ('control_sequence', 'control_head_sha256', 'revocation_generation'):
        if checkpoint[key] != catalogue[key]: refuse()
    if checkpoint['catalogue_generation'] != catalogue['generation'] or checkpoint['anchor_generation'] != anchor['generation']: refuse()
    if not any(checkpoint['observer_identity_sha256'] == x['observer_identity_sha256'] and checkpoint['method_sha256'] == x['method_sha256'] for x in routes): refuse()
    _chronology(previous_clock, checkpoint['observed']); _chronology(checkpoint['observed'], now)
    if initial['control_sequence'] > len(ledger['events']): refuse()
    if ledger['events'][initial['control_sequence'] - 1] != initial['control_head_sha256']: refuse()
    # A detached checkpoint cannot include itself or its own readback. All facts
    # already consumed, the ledger and transaction proofs precede this manifest.
    required = set(consumed) - {catalogue['checkpoint_sha256']}
    required.discard(checkpoint['retention_manifest_sha256']); required.difference_update(checkpoint['readbacks'])
    retain(checkpoint['retention_manifest_sha256'], checkpoint['readbacks'], required)
    active_control_refs.update({checkpoint['retention_manifest_sha256'], *checkpoint['readbacks']})
    if (active_pins | active_control_refs) & revoked: refuse()
    if (active_refs | {digest(ledger), digest(checkpoint), observations['control_transactions_sha256']}) & revoked: refuse()
    return ProspectiveBasis(anchor['epoch_id'], digest(domain), digest(predecessor), digest(proof), digest(binding),
        digest(ledger), ledger['head_sequence'], ledger['head_sha256'], digest(checkpoint), catalogue['generation'], revocation,
        digest(enrollment), digest(protected), digest(_full_identity) if full else digest(request), max(0, int(min(headrooms))), tuple(payloads),
        canonical(epoch).decode('ascii'), canonical(predecessor).decode('ascii'))


def validate_full_transition(full_anchor, bootstrap_anchor, control_basis, dr_binding):
    """Additional continuity join. Full v1 DR/P/custody validation stays mandatory."""
    validate_anchor(bootstrap_anchor)
    if type(control_basis) is not ProspectiveBasis: refuse()
    if (type(full_anchor) is not dict or full_anchor.get('schema_version') != 2 or full_anchor.get('kind') != 'ap06-prospective-full-anchor'
            or full_anchor.get('bootstrap_anchor_sha256') != digest(bootstrap_anchor)
            or full_anchor.get('generation', 0) <= bootstrap_anchor['generation']
            or full_anchor.get('revocation_generation', -1) < bootstrap_anchor['revocation_generation']): refuse()
    for key in ('epoch_id', 'epoch_descriptor_sha256', 'domain_identity_sha256', 'predecessor_index_sha256', 'bootstrap_binding_sha256'):
        if full_anchor.get(key) != bootstrap_anchor[key]: refuse()
    validate_full_anchor(full_anchor)
    if control_basis.epoch_id != full_anchor['epoch_id'] or control_basis.domain_identity_sha256 != full_anchor['domain_identity_sha256']: refuse()
    attachments = [strict(raw) for name, raw in control_basis.event_payloads if name == 'DR_BINDING_ATTACHED']
    if not any(row['binding_sha256'] == digest(dr_binding) and row['source_identity_sha256'] == dr_binding.get('source_identity_sha256') for row in attachments): refuse()
    for key in ('source_sha', 'source_tree', 'python_version'):
        if dr_binding.get(key) != full_anchor.get(key): refuse()
    return full_anchor


FULL_ANCHOR = dict(BOOTSTRAP_ANCHOR,
    kind=S.enum('ap06-prospective-full-anchor'),
    bootstrap_anchor_sha256='sha', binding_sha256='sha', target_sha256='sha',
    source_identity_sha256='sha', database_semantics_sha256='sha',
    data_runtime=S.RUNTIME, restore_runtime=S.RUNTIME, post_v126_recipe_sha256='sha',
    scopes=S.array(S.enum('DISPATCH', 'COPY_ONLY')),
    initial_checkpoint={'catalogue_generation': 'positive', 'ledger_sequence': 'positive',
        'ledger_head_sha256': 'sha', 'revocation_generation': 'uint'}, control_checkpoint=INITIAL)


def validate_full_anchor(doc):
    check(doc, FULL_ANCHOR)
    if doc['epoch_id'] != doc['epoch_descriptor_sha256'] or S.timestamp(doc['valid_from']) >= S.timestamp(doc['valid_until']): refuse()
    unique(doc['scopes']); unique(list(doc['sources'].values()), 'identity'); unique(list(doc['sources'].values()), 'path')
    root = PurePosixPath(doc['evidence_root'])
    for reader in doc['sources'].values():
        path = PurePosixPath(reader['path'])
        if path == root or root in path.parents: refuse()
    return doc


def validate_current(anchor, catalogue, highwater, observations, documents, now, identity, scope, *, checkpoint=None):
    return validate_basis(None, anchor, catalogue, highwater, observations, documents, now,
        checkpoint=checkpoint, _full_identity=identity, _full_scope=scope)


def epoch_context(doc):
    keys = ('epoch_id', 'domain_identity_sha256', 'predecessor_index_sha256')
    if type(doc) is not dict: refuse()
    result = {key: doc.get(key) for key in keys}
    check(result, {key: 'sha' for key in keys})
    return result


DURABLE_BASIS = record('prospective-genesis-basis', {'epoch': {key: 'sha' for key in
    ('epoch_id', 'domain_identity_sha256', 'predecessor_index_sha256')},
    'isolation_proof_sha256': 'sha', 'predecessor_index': PREDECESSORS, 'epoch_descriptor': EPOCH})


def validate_durable_basis(value, request):
    """Completed-history validation; retained evidence never reacquires authority."""
    validate_request(request); check(value, DURABLE_BASIS)
    epoch = value['epoch_descriptor']; predecessor = value['predecessor_index']
    if (value['epoch'] != epoch_context(request) or digest(epoch) != request['epoch_id']
            or digest(predecessor) != request['predecessor_index_sha256']
            or epoch['domain_identity_sha256'] != request['domain_identity_sha256']
            or epoch['predecessor_index_sha256'] != digest(predecessor)
            or value['isolation_proof_sha256'] != request['isolation_proof_sha256']): refuse()
    unique(predecessor['runs'], 'run_id')
    runs = {row['run_id']: row for row in predecessor['runs']}
    if (F724 not in runs or F782 not in runs or runs[F724]['historical_outcome'] != 'UNKNOWN'
            or runs[F782]['historical_outcome'] != F782_STATE or request['run_id'] in runs
            or digest(request) in {row['intent_sha256'] for row in runs.values()}
            or request['target']['host_fingerprint'] == predecessor['legacy_target']['host_fingerprint']): refuse()
    return value


def durable_basis(request, verified):
    if type(verified) is not ProspectiveBasis or verified.request_sha256 != digest(request): refuse()
    value = dict(schema_version=2, kind='prospective-genesis-basis', epoch=epoch_context(request),
        isolation_proof_sha256=request['isolation_proof_sha256'],
        predecessor_index=strict(verified.predecessor_index_json.encode('ascii'), PREDECESSORS),
        epoch_descriptor=strict(verified.epoch_descriptor_json.encode('ascii'), EPOCH))
    return validate_durable_basis(value, request)


DR_SOURCE_IDENTITY = record('prospective-dr-source-identity', {
    'epoch_id': 'sha', 'domain_identity_sha256': 'sha', 'predecessor_index_sha256': 'sha',
    'bootstrap_binding_sha256': 'sha', 'protected_selection_policy_sha256': 'sha',
    'database_semantics_sha256': 'sha', 'source_sha': 'git', 'source_tree': 'git',
    'tooling_sha256': 'sha', 'data_runtime': S.RUNTIME, 'restore_runtime': S.RUNTIME})


def validate_dr_source_identity(identity, binding, anchor):
    check(identity, DR_SOURCE_IDENTITY); S.check(binding, S.SCHEMAS['binding'])
    if digest(identity) != binding['source_identity_sha256'] or digest(binding['tools']) != identity['tooling_sha256']: refuse()
    for key in ('epoch_id', 'domain_identity_sha256', 'predecessor_index_sha256', 'bootstrap_binding_sha256', 'protected_selection_policy_sha256',
                'source_sha', 'source_tree', 'tooling_sha256', 'database_semantics_sha256', 'data_runtime', 'restore_runtime'):
        if identity[key] != anchor[key]: refuse()
    for key in ('source_sha', 'source_tree', 'database_semantics_sha256', 'data_runtime', 'restore_runtime'):
        if binding[key] != identity[key]: refuse()
    return identity
