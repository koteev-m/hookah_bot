#!/usr/bin/env python3
"""Source-bound target binding history; no SSH or daemon mutation.

The sequencer embeds these same bytes. Retirement only appends a protected transfer
record after the caller verifies its real terminal receipt and approved handoff.
Unknown daemon outcomes cannot be retired by this protocol.
"""
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys


class BindingError(ValueError):
    pass


def binding_action_sequence(kind, name):
    stages = {
        'BASELINE_VERIFIED': ['baseline'],
        'PRE_DRAIN_BACKUP_REHEARSED': ['backup-rehearsal'],
        'CADDY_CANDIDATE_INSTALLED_AND_RELOADED': ['caddy-activate'],
        'PUBLIC_DRAIN_ACTIVE': ['public-drain-on'],
        'V125_BACKEND_STOPPED': ['stop-backend'],
        'ZERO_WRITER_GATE_PASSED': ['zero-writer'],
        'QUIESCED_BACKUP_REHEARSED': ['backup-rehearsal'],
        'FINAL_V125_PREFLIGHT_PASSED': ['preflight-upload', 'final-v125-preflight'],
        'V126_MAINTENANCE_CONFIG_PREPARED': ['transform-maintenance'],
        'V126_IMAGE_TRANSFERRED_AND_VERIFIED': ['image-prepare', 'image-upload', 'image-load'],
        'V126_BACKEND_STARTED': ['start-v126'],
        'V126_SCHEMA_RUNTIME_GATE_PASSED': ['schema-runtime-gate'],
        'MANUAL_SMOKE_AUTHORIZED': ['open-manual-smoke'],
        'MANUAL_SMOKE_PASSED': ['record-manual-smoke'],
        'PUBLIC_DRAIN_REACTIVATED': ['public-drain-on'],
        'V126_BACKEND_STOPPED_FOR_OFF_TRANSITION': ['stop-backend'],
        'MAINTENANCE_OFF_CONFIG_VERIFIED': ['transform-maintenance'],
        'FINAL_V126_BACKEND_STARTED': ['start-v126'],
        'ORDINARY_CADDY_RESTORED': ['restore-caddy'],
        'FINAL_PUBLIC_GATES_PASSED': ['final-public-gates'],
    }
    recovery = {'pre-v126': ['recover-pre-v126'], 'post-v126-stop': ['recover-post-v126-stop'],
                'verify-full-dr': ['verify-full-dr']}
    if kind == 'INIT' and name == 'RUN_INITIALIZED':
        return ['initialize-run']
    selected = stages if kind == 'STAGE' else recovery if kind == 'RECOVERY' else {}
    if name not in selected:
        raise BindingError('reconciliation_action_class')
    return selected[name]


def binding_canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def binding_protected(path, mode, directory=False):
    info = path.lstat()
    if (not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)) or
            info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != mode or
            (not directory and info.st_nlink != 1)):
        raise BindingError('record_metadata')


def binding_read(path):
    binding_protected(path, 0o400)
    raw = path.read_bytes()
    value = json.loads(raw)
    if raw != binding_canonical(value):
        raise BindingError('record_not_canonical')
    return value


def binding_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def binding_owner(value):
    if (not isinstance(value, dict) or set(value) != {'run_id', 'release_sha', 'script_sha256'} or
            not re.fullmatch(r'[a-z0-9][a-z0-9._-]{5,63}', value['run_id']) or
            not re.fullmatch(r'[0-9a-f]{40}', value['release_sha']) or
            not re.fullmatch(r'[0-9a-f]{64}', value['script_sha256'])):
        raise BindingError('owner_schema')
    return value


def binding_owner_id(owner):
    return hashlib.sha256(binding_canonical(binding_owner(owner))).hexdigest()


def binding_inventory(root, owner):
    files = {}
    unknown = False
    identities = []
    for start in sorted(root.glob('*.start.json')):
        doc = binding_read(start)
        identity = doc.get('identity', {})
        if {key: identity.get(key) for key in owner} != owner:
            continue
        op = start.name.removesuffix('.start.json')
        if (set(doc) != {'identity', 'operation_id', 'started_at', 'boot_id'} or
                set(identity) != {'run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action'} or
                doc['operation_id'] != op or hashlib.sha256(binding_canonical(identity)).hexdigest() != op):
            raise BindingError('start_binding')
        identities.append(identity)
        files[start.name] = binding_hash(start)
        request = root / (op + '.request.json')
        if request.exists() or request.is_symlink():
            binding_request(request, identity, root.parent)
            files[request.name] = binding_hash(request)
        result = root / (op + '.result.json')
        log = root / (op + '.log')
        if not result.exists():
            unknown = True
            continue
        outcome = binding_result(result, identity, root.parent)
        files[result.name] = binding_hash(result)
        if identity['kind'] != 'INIT':
            files[log.name] = outcome['log_sha256']
        if identity['kind'] == 'DEPLOY' and outcome['exit'] == 0:
            proof = root / (op + '.deploy-proof.json')
            binding_deploy_proof(proof, identity, root.parent)
            files[proof.name] = binding_hash(proof)
        unknown = unknown or outcome['exit'] != 0
    for name, digest in binding_reconciliation_inventory(root, root.parent).items():
        identity = binding_read(root / name)['identity']
        if {key: identity.get(key) for key in owner} == owner:
            files[name] = digest
    return files, unknown, identities


def binding_handoff(doc, owner, next_owner, receipt_sha, target):
    keys = {'format_version', 'owner', 'next_owner', 'terminal_receipt_sha256', 'target_sha256',
            'operational_version', 'backend_image', 'image_id', 'environment_sha256',
            'compose_sha256', 'caddy_runtime_sha256', 'config_owner', 'restart_policy',
            'handoff_approved_and_applied', 'approval_id', 'observed_at'}
    if (not isinstance(doc, dict) or set(doc) != keys or type(doc['format_version']) is not int or
            doc['format_version'] != 1 or doc['owner'] != owner or doc['next_owner'] != next_owner or
            doc['terminal_receipt_sha256'] != receipt_sha or
            doc['target_sha256'] != hashlib.sha256(str(target).encode()).hexdigest() or
            doc['operational_version'] not in ('V125', 'V126') or
            doc['config_owner'] != 'root:root' or doc['restart_policy'] != 'unless-stopped' or
            doc['handoff_approved_and_applied'] is not True or
            not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{5,127}', doc['approval_id'])):
        raise BindingError('handoff_contract')
    for key in ('terminal_receipt_sha256', 'environment_sha256', 'compose_sha256', 'caddy_runtime_sha256'):
        if not re.fullmatch(r'[0-9a-f]{64}', doc[key]):
            raise BindingError('handoff_digest')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', doc['image_id']):
        raise BindingError('handoff_image')
    release = owner['release_sha'] if doc['operational_version'] == 'V126' else 'f577934691a1a7a79ba327c54e2055425142b7be'
    if not re.fullmatch(r'[a-z0-9][a-z0-9._/-]*:' + release, doc['backend_image']):
        raise BindingError('handoff_image_source')
    datetime.datetime.strptime(doc['observed_at'], '%Y-%m-%dT%H:%M:%SZ')


def binding_chain(root, target):
    owner = binding_owner(binding_read(root / 'run.json'))
    owners = [owner]
    directory = root / 'transfers'
    if not directory.exists() and not directory.is_symlink():
        return owner, owners
    binding_protected(directory, 0o700, True)
    names = set(path.name for path in directory.iterdir())
    consumed = set()
    while binding_owner_id(owner) + '.json' in names:
        name = binding_owner_id(owner) + '.json'
        if name in consumed:
            raise BindingError('binding_cycle')
        transfer = binding_read(directory / name)
        version = transfer.get('format_version')
        keys = {'format_version', 'previous_owner', 'next_owner', 'inventory', 'handoff'}
        if version == 2:
            keys |= {'next_kind', 'request_sha256', 'terminal_kind'}
        if (type(version) is not int or version not in (1, 2) or set(transfer) != keys or
                transfer['previous_owner'] != owner):
            raise BindingError('transfer_schema')
        if version == 2 and (transfer['next_kind'] not in ('ORDINARY_DEPLOY', 'CUTOVER') or
                (transfer['next_kind'] == 'ORDINARY_DEPLOY' and not re.fullmatch('[0-9a-f]{64}', transfer['request_sha256'])) or
                (transfer['next_kind'] == 'CUTOVER' and transfer['request_sha256'] is not None) or
                transfer['terminal_kind'] not in ('NATIVE_RECEIPT', 'RECONCILED_EFFECT', 'ORDINARY_DEPLOY_PROOF')):
            raise BindingError('transfer_policy')
        next_owner = binding_owner(transfer['next_owner'])
        if any(previous['run_id'] == next_owner['run_id'] for previous in owners):
            raise BindingError('run_id_reuse')
        inventory, unknown, identities = binding_inventory(root, owner)
        if unknown or not inventory or transfer['inventory'] != inventory:
            raise BindingError('retired_outcome_not_proven')
        handoff = transfer['handoff']
        binding_handoff(handoff, owner, next_owner, handoff['terminal_receipt_sha256'], target)
        ordinary = version == 2 and transfer['terminal_kind'] == 'ORDINARY_DEPLOY_PROOF'
        required = ('DEPLOY', 'ORDINARY_DEPLOY') if ordinary else (('STAGE', 'FINAL_PUBLIC_GATES_PASSED') if handoff['operational_version'] == 'V126' else ('RECOVERY', 'pre-v126'))
        if not any((identity['kind'], identity['name']) == required for identity in identities):
            raise BindingError('terminal_remote_operation_missing')
        if ordinary:
            matches = [identity for identity in identities if identity['kind'] == 'DEPLOY']
            if len(matches) != 1:
                raise BindingError('ordinary_terminal_operation_count')
            op = hashlib.sha256(binding_canonical(matches[0])).hexdigest()
            proof_path = root / (op + '.deploy-proof.json')
            proof = binding_deploy_proof(proof_path, matches[0], target)
            binding_deploy_handoff(proof, binding_hash(proof_path), handoff)
        consumed.add(name)
        owner = next_owner
        owners.append(owner)
    if consumed != names:
        raise BindingError('unlinked_transfer_record')
    return owner, owners


def binding_entry(mode):
    # Called only from the sequencer CLI after its source/receipt checks.
    target = Path(sys.argv[1])
    if not target.is_absolute() or target.resolve(strict=True) != target:
        raise BindingError('target_not_canonical')
    info = target.stat()
    if info.st_uid != os.geteuid() or info.st_mode & 0o022:
        raise BindingError('target_ownership')
    root = target / '.v126-target-operations'
    binding_protected(root, 0o700, True)
    binding_protected(root / 'lock', 0o600)
    fd = os.open(root / 'lock', os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        current, owners = binding_chain(root, target)
        inventory, unknown, identities = binding_inventory(root, current)
        allowed = {'lock', 'run.json'} | ({'transfers'} if (root / 'transfers').exists() else set())
        for owner in owners:
            files, old_unknown, _ = binding_inventory(root, owner)
            allowed.update(name.split('/')[0] for name in files)
            # A missing result may have a private active/partial log: inspection is
            # conservative; it never adopts or removes such evidence.
            for name in tuple(files):
                if name.endswith('.start.json'):
                    log = name.removesuffix('.start.json') + '.log'
                    if (root / log).exists(): allowed.add(log)
        if set(p.name for p in root.iterdir()) != allowed:
            raise BindingError('unexpected_target_records')
        if mode == 'inspect':
            reconciled = sum(name.startswith('reconciliations/') for name in inventory)
            print(json.dumps(dict(owner=current, history_count=len(owners),
                  outcome='UNKNOWN' if unknown else 'COMMAND_RESULTS_VERIFIED',
                  reconciled_completions=reconciled,
                  next_action='EXTERNAL_DAEMON_FENCING_DECISION_REQUIRED' if unknown else 'VERIFY_TERMINAL_RECEIPT_AND_APPROVED_HANDOFF',
                  retry_allowed=False), sort_keys=True))
            return
        owner = dict(zip(('run_id', 'release_sha', 'script_sha256'), sys.argv[2:5]))
        receipt_sha, handoff_path = sys.argv[5:7]
        next_owner = dict(zip(('run_id', 'release_sha', 'script_sha256'), sys.argv[7:10]))
        binding_owner(next_owner)
        if current != owner or unknown or not inventory:
            raise BindingError('retirement_requires_known_completed_current_run')
        handoff = binding_read(Path(handoff_path))
        binding_handoff(handoff, owner, next_owner, receipt_sha, target)
        if handoff['image_id'] != sys.argv[11]:
            raise BindingError('terminal_handoff_image_mismatch')
        if handoff['operational_version'] != sys.argv[10]:
            raise BindingError('terminal_handoff_version_mismatch')
        required = ('STAGE', 'FINAL_PUBLIC_GATES_PASSED') if handoff['operational_version'] == 'V126' else ('RECOVERY', 'pre-v126')
        if not any((identity['kind'], identity['name']) == required for identity in identities):
            raise BindingError('terminal_remote_operation_missing')
        if any(previous['run_id'] == next_owner['run_id'] for previous in owners):
            raise BindingError('run_id_reuse')
        directory = root / 'transfers'
        if not directory.exists():
            directory.mkdir(mode=0o700)
        binding_protected(directory, 0o700, True)
        transfer = dict(format_version=1, previous_owner=owner, next_owner=next_owner,
                        inventory=inventory, handoff=handoff)
        if len(sys.argv) > 13:
            next_kind, request_path, terminal_kind = sys.argv[12:15]
            if next_kind not in ('ORDINARY_DEPLOY', 'CUTOVER') or terminal_kind not in ('NATIVE_RECEIPT', 'RECONCILED_EFFECT'):
                raise BindingError('next_binding_policy')
            request_sha = None
            if next_kind == 'ORDINARY_DEPLOY':
                request = binding_read(Path(request_path))
                binding_next_request(request, next_owner, handoff, target)
                request_sha = binding_hash(Path(request_path))
            elif request_path != 'NONE':
                raise BindingError('cutover_transfer_has_no_deploy_request')
            transfer.update(format_version=2, next_kind=next_kind,
                            request_sha256=request_sha, terminal_kind=terminal_kind)
        recordfd = os.open(directory / (binding_owner_id(owner) + '.json'),
                           os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o400)
        with os.fdopen(recordfd, 'wb') as handle:
            handle.write(binding_canonical(transfer)); handle.flush(); os.fsync(handle.fileno())
        for path in (directory, root):
            syncfd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(syncfd)
            finally: os.close(syncfd)
        print('TARGET_BINDING_RETIRED history_preserved=true ' +
              ('next_request_only=true' if transfer.get('next_kind') == 'ORDINARY_DEPLOY' else 'next_baseline_only=true'))
    finally:
        os.close(fd)


def binding_refuse(reason):
    raise BindingError(reason)


def binding_sync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def binding_create(path, value):
    binding_create_raw(path, binding_canonical(value))


def binding_create_raw(path, raw):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o400)
    with os.fdopen(fd, 'wb') as handle:
        handle.write(raw); handle.flush(); os.fsync(handle.fileno())
    binding_sync_dir(path.parent)


def binding_active_policy(root, target):
    _, owners = binding_chain(root, target)
    if len(owners) == 1:
        return dict(next_kind='CUTOVER', request_sha256=None)
    transfer = binding_read(root / 'transfers' / (binding_owner_id(owners[-2]) + '.json'))
    return dict(next_kind=transfer.get('next_kind', 'CUTOVER'), request_sha256=transfer.get('request_sha256'))


def binding_deploy_proof(path, identity, target):
    doc = binding_read(path)
    keys = {'format_version', 'identity', 'target_sha256', 'request_sha256',
            'environment_sha256', 'compose_sha256', 'image_id', 'backend_container_id',
            'database_identity_sha256', 'caddy_runtime_sha256', 'result', 'completed_at'}
    if (set(doc) != keys or type(doc['format_version']) is not int or doc['format_version'] != 1 or doc['identity'] != identity or
            doc['target_sha256'] != hashlib.sha256(str(target).encode()).hexdigest() or
            doc['request_sha256'] != identity['intent_sha256'] or
            doc['result'] != 'ORDINARY_DEPLOY_COMPLETED'):
        raise BindingError('ordinary_deploy_proof')
    for key in ('request_sha256', 'environment_sha256', 'compose_sha256', 'backend_container_id',
                'database_identity_sha256', 'caddy_runtime_sha256'):
        if not re.fullmatch('[0-9a-f]{64}', doc[key]):
            raise BindingError('ordinary_deploy_proof_digest')
    if not re.fullmatch('sha256:[0-9a-f]{64}', doc['image_id']):
        raise BindingError('ordinary_deploy_proof_image')
    datetime.datetime.strptime(doc['completed_at'], '%Y-%m-%dT%H:%M:%SZ')
    return doc


def binding_next_request(request, next_owner, handoff, target):
    # Full descriptor/source/file validation belongs to the single deploy consumer.
    # A transfer binds its exact bytes; these joins preserve the applied authority.
    if (request.get('owner') != next_owner or
            request.get('target_sha256') != hashlib.sha256(str(target).encode()).hexdigest()):
        raise BindingError('next_request_binding')
    for key in ('operational_version', 'backend_image', 'image_id', 'environment_sha256',
                'caddy_runtime_sha256', 'config_owner', 'restart_policy',
                'handoff_approved_and_applied'):
        if request.get(key) != handoff[key]:
            raise BindingError('next_request_handoff_' + key)
    if request.get('compose_before_sha256') != handoff['compose_sha256']:
        raise BindingError('next_request_previous_compose')


def binding_deploy_handoff(proof, digest, handoff):
    if digest != handoff['terminal_receipt_sha256']:
        raise BindingError('ordinary_terminal_proof_binding')
    for key in ('image_id', 'environment_sha256', 'compose_sha256', 'caddy_runtime_sha256'):
        if proof[key] != handoff[key]:
            raise BindingError('ordinary_terminal_handoff_' + key)


def binding_request(path, identity, target):
    doc = binding_read(path)
    if identity['kind'] == 'INIT':
        return binding_init_request(doc, identity, target)
    if (set(doc) != {'format_version', 'identity', 'target_sha256', 'args', 'environment'} or
            type(doc['format_version']) is not int or doc['format_version'] != 1 or
            doc['identity'] != identity or
            doc['target_sha256'] != hashlib.sha256(str(target).encode()).hexdigest() or
            not isinstance(doc['args'], list) or not 3 <= len(doc['args']) <= 32 or
            any(not isinstance(value, str) or '\x00' in value or '\n' in value for value in doc['args']) or
            doc['args'][:3] != [str(target), identity['run_id'], identity['release_sha']] or
            not isinstance(doc['environment'], dict) or
            any(not key.startswith('V126_INTERNAL_REMOTE_') or not isinstance(value, str)
                for key, value in doc['environment'].items())):
        raise BindingError('original_request_contract')
    return doc


def binding_operation_evidence(root, operation_id, target):
    if not re.fullmatch('[0-9a-f]{64}', operation_id):
        raise BindingError('operation_id_schema')
    start_path = root / (operation_id + '.start.json')
    start = binding_read(start_path)
    identity = start['identity']
    if (set(start) != {'identity', 'operation_id', 'started_at', 'boot_id'} or
            start['operation_id'] != operation_id or
            hashlib.sha256(binding_canonical(identity)).hexdigest() != operation_id):
        raise BindingError('original_start_binding')
    request_path = root / (operation_id + '.request.json')
    request = binding_request(request_path, identity, target)
    result_path = root / (operation_id + '.result.json')
    result = binding_read(result_path)
    if (set(result) != {'identity', 'operation_id', 'exit', 'outcome', 'children', 'log_sha256', 'completed_at'} or
            result['identity'] != identity or result['operation_id'] != operation_id or
            type(result['exit']) is not int or result['exit'] != 0 or result['outcome'] != 'SUCCEEDED' or
            result['children'] != 'REAPED'):
        raise BindingError('UNKNOWN_external_daemon_fencing_required')
    log = root / (operation_id + '.log')
    binding_protected(log, 0o400)
    if binding_hash(log) != result['log_sha256']:
        raise BindingError('original_log_binding')
    files = {path.name: binding_hash(path) for path in (start_path, request_path, result_path, log)}
    return identity, files, request


def binding_reconciliation_inventory(root, target):
    directory = root / 'reconciliations'
    if not directory.exists() and not directory.is_symlink():
        return {}
    binding_protected(directory, 0o700, True)
    inventory = {}
    for path in sorted(directory.iterdir()):
        doc = binding_read(path)
        keys = {'format_version', 'kind', 'identity', 'operation_id', 'target_sha256',
                'operations', 'checker_sha256', 'poststate', 'observed_at', 'retry_allowed'}
        if (set(doc) != keys or type(doc['format_version']) is not int or doc['format_version'] != 1 or
                doc['kind'] != 'RECONCILED_EFFECT' or doc['retry_allowed'] is not False or
                doc['target_sha256'] != hashlib.sha256(str(target).encode()).hexdigest() or
                path.name != doc['operation_id'] + '.json' or
                not re.fullmatch('[0-9a-f]{64}', doc['checker_sha256']) or
                not isinstance(doc['poststate'], dict) or doc['poststate'].get('outcome') != 'EXACT_COMPLETED_EFFECT' or
                not isinstance(doc['operations'], list) or not doc['operations']):
            raise BindingError('reconciliation_schema')
        datetime.datetime.strptime(doc['observed_at'], '%Y-%m-%dT%H:%M:%SZ')
        ids = []
        for operation in doc['operations']:
            if set(operation) != {'operation_id', 'files'}:
                raise BindingError('reconciliation_operation_schema')
            identity, files, _ = binding_operation_evidence(root, operation['operation_id'], target)
            if (operation['files'] != files or any(identity[key] != doc['identity'][key]
                    for key in ('run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name'))):
                raise BindingError('reconciliation_original_binding')
            ids.append(operation['operation_id'])
        if len(ids) != len(set(ids)) or ids[-1] != doc['operation_id'] or identity != doc['identity']:
            raise BindingError('reconciliation_operation_order')
        inventory['reconciliations/' + path.name] = binding_hash(path)
    return inventory


def binding_reconcile(target, owner, kind, name, intent_sha, source_sha, checker_sha, actions, observe):
    """Append exact-effect evidence after lost ACK. Never dispatch an action again.

    observe is the source-bound read-only action checker supplied by the sequencer,
    not an operator attestation. Nonzero/missing durable results stay UNKNOWN even
    if current state resembles the desired state.
    """
    target = Path(target)
    if not target.is_absolute() or target.resolve(strict=True) != target:
        raise BindingError('target_not_canonical')
    if source_sha != owner['script_sha256'] or not re.fullmatch('[0-9a-f]{64}', checker_sha):
        raise BindingError('reconciliation_source_binding')
    root = target / '.v126-target-operations'
    binding_protected(root, 0o700, True)
    binding_protected(root / 'lock', 0o600)
    fd = os.open(root / 'lock', os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        current, owners = binding_chain(root, target)
        _, unknown, identities = binding_inventory(root, current)
        if current != owner or unknown:
            raise BindingError('UNKNOWN_external_daemon_fencing_required')
        original = binding_reconciliation_inventory(root, target)
        allowed = {'run.json', 'lock'} | ({'transfers'} if (root / 'transfers').exists() else set())
        for previous in owners:
            allowed.update(path.split('/')[0] for path in binding_inventory(root, previous)[0])
        if set(path.name for path in root.iterdir()) != allowed:
            raise BindingError('unexpected_target_records')
        selected = [identity for identity in identities if
                    (identity['kind'], identity['name'], identity['intent_sha256']) == (kind, name, intent_sha)]
        by_action = {identity['action']: identity for identity in selected}
        if len(by_action) != len(selected) or set(by_action) != set(actions) or not actions:
            raise BindingError('original_action_sequence_incomplete')
        operations = []
        requests = []
        for action in actions:
            identity = by_action[action]
            op = hashlib.sha256(binding_canonical(identity)).hexdigest()
            _, files, request = binding_operation_evidence(root, op, target)
            operations.append(dict(operation_id=op, files=files))
            requests.append(request)
        directory = root / 'reconciliations'
        path = directory / (op + '.json')
        if path.exists() or path.is_symlink():
            # Explicit readback of a prior immutable reconciliation is not replay.
            if 'reconciliations/' + path.name not in original:
                raise BindingError('reconciliation_invalid')
            return binding_read(path)
        poststate = observe(identity, requests[-1], operations)
        if not isinstance(poststate, dict) or poststate.get('outcome') != 'EXACT_COMPLETED_EFFECT':
            raise BindingError('UNKNOWN_postconditions_insufficient')
        record = dict(format_version=1, kind='RECONCILED_EFFECT', identity=identity,
                      operation_id=op, target_sha256=hashlib.sha256(str(target).encode()).hexdigest(),
                      operations=operations, checker_sha256=checker_sha, poststate=poststate,
                      observed_at=datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                      retry_allowed=False)
        if not directory.exists():
            directory.mkdir(mode=0o700)
            binding_sync_dir(root)
        binding_protected(directory, 0o700, True)
        binding_create(path, record)
        binding_reconciliation_inventory(root, target)
        return record
    finally:
        os.close(fd)


def binding_export_reconciliation(root, record):
    import base64
    names = {name for operation in record['operations'] for name in operation['files']}
    names.add('reconciliations/' + record['operation_id'] + '.json')
    return dict(format_version=1, files={name: base64.b64encode((root / name).read_bytes()).decode('ascii')
                                       for name in sorted(names)})


def binding_reconciliation_log(original, remote_logs):
    artifacts = {}
    for raw in [original, *remote_logs]:
        seen = set()
        for line in raw.splitlines():
            if not line.startswith(b'ARTIFACT'):
                continue
            match = re.fullmatch(rb'ARTIFACT\t([a-z0-9][a-z0-9._-]{0,63})\t([0-9a-f]{64})', line)
            if not match:
                raise BindingError('original_artifact_encoding')
            name, digest = (value.decode('ascii') for value in match.groups())
            if name == 'operation-log' or name in seen or (name in artifacts and artifacts[name] != digest):
                raise BindingError('original_artifact_conflict')
            seen.add(name); artifacts[name] = digest
    return b'RECONCILIATION_ARTIFACT_INVENTORY_FROM_ORIGINAL_RECORDS\n' + b''.join(
        ('ARTIFACT\t' + name + '\t' + digest + '\n').encode('ascii') for name, digest in sorted(artifacts.items()))


def binding_original_stage_log(directory, index, stage):
    paths = [Path(directory) / f'{index}-{stage}.{suffix}.log' for suffix in ('operation', 'failed')]
    present = [path for path in paths if path.exists() or path.is_symlink()]
    if len(present) != 1:
        raise BindingError('original_local_operation_log_ambiguous_or_missing')
    binding_protected(present[0], 0o400)
    return present[0]


def binding_validate_dr_boundary(raw, run_id, release_sha, phase):
    doc = json.loads(raw)
    keys = {'accepted_data_loss_boundary', 'accepted_recovery_point_utc', 'backup_phase',
            'format_version', 'release_sha', 'result_category', 'run_id'}
    if (set(doc) != keys or type(doc['format_version']) is not int or doc['format_version'] != 1 or
            raw != binding_canonical(doc) or phase not in ('pre-drain', 'quiesced') or
            doc['run_id'] != run_id or doc['release_sha'] != release_sha or
            doc['backup_phase'] != phase or doc['result_category'] != 'DR_PREREQUISITES_ACCEPTED'):
        raise BindingError('DR boundary evidence schema or identity mismatch')
    expected = ('ALL_WRITES_AFTER_PRE_DRAIN_BACKUP_MAY_BE_LOST' if phase == 'pre-drain'
                else 'ALL_WRITES_AFTER_QUIESCED_BACKUP_MAY_BE_LOST')
    if doc['accepted_data_loss_boundary'] != expected:
        raise BindingError('DR data-loss boundary does not match selected backup')
    datetime.datetime.strptime(doc['accepted_recovery_point_utc'], '%Y-%m-%dT%H:%M:%SZ')
    return doc


def binding_retained_local_artifacts(state, manifest, artifacts, bundle_path=None):
    for name in ('manual-smoke-handoff', 'manual-smoke-evidence'):
        if name not in artifacts:
            continue
        path = Path(state) / 'artifacts' / (name + '.json')
        doc = binding_read(path)
        if (binding_hash(path) != artifacts[name] or
                doc.get('run_id') != manifest['run_id'] or doc.get('release_sha') != manifest['release_sha']):
            raise BindingError('original_local_manual_evidence_missing_or_changed')
    if 'dr-boundary' in artifacts:
        import base64
        path = Path(state) / 'recovery/dr-boundary.json'
        binding_protected(path, 0o400)
        if binding_hash(path) != artifacts['dr-boundary'] or bundle_path is None:
            raise BindingError('original_local_DR_boundary_missing_or_changed')
        bundle = binding_read(Path(bundle_path))
        requests = [json.loads(base64.b64decode(raw, validate=True)) for name, raw in bundle['files'].items()
                    if name.endswith('.request.json')]
        if len(requests) != 1 or requests[0]['identity']['action'] != 'verify-full-dr':
            raise BindingError('original_DR_request_missing')
        args = requests[0]['args']
        if (len(args) != 9 or args[5:8] != [artifacts['dr-selected-backup'], artifacts['dr-selected-inventory'], artifacts['dr-boundary']]):
            raise BindingError('original_DR_backup_boundary_binding')
        binding_validate_dr_boundary(path.read_bytes(), manifest['run_id'], manifest['release_sha'], args[4])
    # Baseline's local proof is source-derived and main-actions is separately
    # validated by the canonical CI consumer. Image/source uploads retain their
    # identical bytes remotely; the action observer verifies those sealed files.


def binding_embedded_source(source, name):
    delimiter = 'V126_RECONCILIATION_PY' if name == 'remote_reconciliation_poststate_python' else 'PY'
    marker = (name + "() {\n  cat <<'" + delimiter + "'\n").encode('ascii')
    if source.count(marker) != 1:
        raise BindingError('embedded_source_marker')
    body = source.split(marker, 1)[1].split(('\n' + delimiter + '\n}').encode('ascii'), 1)[0] + b'\n'
    return body


def binding_verify_reconciliation_bundle(path, target, source_sha, kind, name, intent_sha, checker_sha):
    """Validate transferred immutable evidence using the same record validators.

    Temporary files are exact private copies for validation, never target records.
    A bundle cannot authorize a mutation replay or synthesize a native receipt.
    """
    import base64
    import tempfile
    bundle = binding_read(Path(path))
    if (set(bundle) != {'format_version', 'files'} or type(bundle['format_version']) is not int or
            bundle['format_version'] != 1 or not isinstance(bundle['files'], dict) or
            not 5 <= len(bundle['files']) <= 13):
        raise BindingError('reconciliation_bundle_schema')
    with tempfile.TemporaryDirectory(prefix='v126-evidence-check-') as directory:
        root = Path(directory)
        (root / 'reconciliations').mkdir(mode=0o700)
        total = 0
        for filename, encoded in bundle['files'].items():
            if not re.fullmatch(r'(?:[0-9a-f]{64}\.(?:start|request|result)\.json|[0-9a-f]{64}\.log|reconciliations/[0-9a-f]{64}\.json)', filename):
                raise BindingError('reconciliation_bundle_filename')
            raw = base64.b64decode(encoded, validate=True)
            total += len(raw)
            if total > 16 * 1024**2:
                raise BindingError('reconciliation_bundle_size')
            file = root / filename
            file.write_bytes(raw); file.chmod(0o400)
        inventory = binding_reconciliation_inventory(root, Path(target))
        if len(inventory) != 1:
            raise BindingError('reconciliation_bundle_record_count')
        record_name = next(iter(inventory))
        record = binding_read(root / record_name)
        identity = record['identity']
        if (identity['script_sha256'] != source_sha or identity['kind'] != kind or
                identity['name'] != name or identity['intent_sha256'] != intent_sha or
                record['checker_sha256'] != checker_sha):
            raise BindingError('reconciliation_bundle_identity')
        expected = {filename for operation in record['operations'] for filename in operation['files']}
        if set(bundle['files']) != expected | {record_name}:
            raise BindingError('reconciliation_bundle_inventory')
        actual_actions = [binding_read(root / (operation['operation_id'] + '.start.json'))['identity']['action']
                          for operation in record['operations']]
        if actual_actions != binding_action_sequence(kind, name):
            raise BindingError('reconciliation_action_sequence')
        logs = [(root / (operation['operation_id'] + '.log')).read_bytes() for operation in record['operations']]
        return record, logs


def binding_write_completion(state, kind, name, index, bundle_path, source_path, expected_spec):
    state = Path(state)
    source = Path(source_path).read_bytes()
    source_sha = hashlib.sha256(source).hexdigest()
    manifest = binding_read(state / 'run.json')
    if manifest['script_sha256'] != source_sha:
        raise BindingError('local_completion_source_identity')
    stage = kind == 'STAGE'
    if kind not in ('STAGE', 'RECOVERY'):
        raise BindingError('local_completion_kind')
    prefix = f'{index}-{name}' if stage else 'recovery-' + name
    base = state / ('receipts' if stage else 'recovery') / (f'{index:02d}-{name}' if stage else name)
    intent_path = state / ('intents' if stage else 'recovery') / ((f'{index:02d}-{name}' if stage else name) + '.intent.json')
    intent = binding_read(intent_path)
    intent_sha = binding_hash(intent_path)
    checksum = Path(str(intent_path) + '.sha256')
    binding_protected(checksum, 0o400)
    if checksum.read_bytes() != (intent_sha + '\n').encode():
        raise BindingError('original_intent_checksum')
    record, logs = binding_verify_reconciliation_bundle(bundle_path, manifest['staging_path'], source_sha, kind, name,
        intent_sha, hashlib.sha256(binding_embedded_source(source, 'remote_reconciliation_poststate_python')).hexdigest())
    if any(record['identity'][key] != manifest[key] for key in ('run_id', 'release_sha', 'script_sha256')):
        raise BindingError('reconciliation_run_binding')
    original = binding_original_stage_log(state / 'artifacts', index, name) if stage else state / 'recovery' / (name + '.operation.log')
    binding_protected(original, 0o400)
    derived = binding_reconciliation_log(original.read_bytes(), logs)
    artifacts = [dict(name=parts[1].decode(), sha256=parts[2].decode())
                 for parts in (line.split(b'\t') for line in derived.splitlines() if line.startswith(b'ARTIFACT\t'))]
    if {item['name'] for item in artifacts} != set(expected_spec.split(',')):
        raise BindingError('original_complete_artifact_set_required')
    binding_retained_local_artifacts(state, manifest, {item['name']: item['sha256'] for item in artifacts}, bundle_path)
    artifacts.append(dict(name='operation-log', sha256=hashlib.sha256(derived).hexdigest()))
    fields = ('run_id', 'release_sha', 'script_sha256', 'predecessor_stage', 'predecessor_receipt_sha256')
    fields += ('stage', 'authorization_gate', 'authorization_receipt_sha256') if stage else ('mode', 'authorization_token_sha256')
    doc = {key: intent[key] for key in fields}
    doc.update(format_version=2, result_category='RECONCILED_EFFECT' if stage else 'RECONCILED_TERMINAL_RECOVERY',
               completed_at=record['observed_at'], intent_sha256=intent_sha,
               artifacts=sorted(artifacts, key=lambda item: item['name']),
               remote_evidence_sha256=binding_hash(Path(bundle_path)), original_operation_log_sha256=binding_hash(original))
    target = Path(str(base) + '.reconciliation.json')
    bundle_target = state / 'artifacts' / (prefix + '.remote-reconciliation.json')
    log_target = state / 'artifacts' / (prefix + '.reconciliation.log')
    for path in (Path(str(base)+'.receipt.json'), Path(str(base)+'.receipt.json.sha256'),
                 target, Path(str(target)+'.sha256'), bundle_target, log_target):
        if path.exists() or path.is_symlink():
            raise BindingError('completion_evidence_already_exists')
    binding_create_raw(bundle_target, Path(bundle_path).read_bytes())
    binding_create_raw(log_target, derived)
    binding_create(target, doc)
    binding_create_raw(Path(str(target)+'.sha256'), (binding_hash(target)+'\n').encode('ascii'))
    return target


def binding_retire_deploy(target, owner, proof_sha, handoff_path, next_request_path):
    """Explicit retirement of one completed deploy; never reset or unlock history."""
    target = Path(target)
    if not target.is_absolute() or target.resolve(strict=True) != target:
        raise BindingError('target_not_canonical')
    root = target / '.v126-target-operations'
    binding_protected(root, 0o700, True)
    binding_protected(root / 'lock', 0o600)
    fd = os.open(root / 'lock', os.O_RDONLY | os.O_NOFOLLOW)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        current, owners = binding_chain(root, target)
        policy = binding_active_policy(root, target)
        files, unknown, identities = binding_inventory(root, current)
        if (current != binding_owner(owner) or policy['next_kind'] != 'ORDINARY_DEPLOY' or
                unknown or len(identities) != 1 or identities[0]['kind'] != 'DEPLOY'):
            raise BindingError('ordinary_retirement_requires_known_completion')
        allowed = {'run.json', 'lock', 'transfers'}
        for previous in owners:
            allowed.update(name.split('/')[0] for name in binding_inventory(root, previous)[0])
        if set(path.name for path in root.iterdir()) != allowed:
            raise BindingError('unexpected_target_records')
        identity = identities[0]
        if identity['intent_sha256'] != policy['request_sha256']:
            raise BindingError('ordinary_terminal_request_binding')
        op = hashlib.sha256(binding_canonical(identity)).hexdigest()
        path = root / (op + '.deploy-proof.json')
        proof = binding_deploy_proof(path, identity, target)
        if binding_hash(path) != proof_sha:
            raise BindingError('ordinary_terminal_proof_digest')
        handoff = binding_read(Path(handoff_path))
        request = binding_read(Path(next_request_path))
        next_owner = binding_owner(request['owner'])
        binding_handoff(handoff, owner, next_owner, proof_sha, target)
        binding_deploy_handoff(proof, proof_sha, handoff)
        binding_next_request(request, next_owner, handoff, target)
        if any(previous['run_id'] == next_owner['run_id'] for previous in owners):
            raise BindingError('run_id_reuse')
        transfer = dict(format_version=2, previous_owner=owner, next_owner=next_owner,
                        inventory=files, handoff=handoff, terminal_kind='ORDINARY_DEPLOY_PROOF',
                        next_kind='ORDINARY_DEPLOY', request_sha256=binding_hash(Path(next_request_path)))
        binding_create(root / 'transfers' / (binding_owner_id(owner) + '.json'), transfer)
        print('TARGET_BINDING_RETIRED history_preserved=true next_request_only=true')
    finally:
        os.close(fd)


def binding_init_request(doc, identity, target):
    keys = {'format_version', 'identity', 'target_sha256', 'manifest_sha256', 'manifest_size',
            'local_state_sha256', 'metadata'}
    if (not isinstance(doc, dict) or set(doc) != keys or type(doc['format_version']) is not int or
            doc['format_version'] != 1 or doc['identity'] != identity or
            (identity['kind'], identity['name'], identity['action']) != ('INIT', 'RUN_INITIALIZED', 'initialize-run') or
            doc['target_sha256'] != hashlib.sha256(str(target).encode()).hexdigest() or
            not isinstance(doc['manifest_sha256'], str) or not re.fullmatch('[0-9a-f]{64}', doc['manifest_sha256']) or
            identity['intent_sha256'] != doc['manifest_sha256'] or
            not isinstance(doc['local_state_sha256'], str) or not re.fullmatch('[0-9a-f]{64}', doc['local_state_sha256']) or
            type(doc['manifest_size']) is not int or not 1 <= doc['manifest_size'] <= 65536):
        binding_refuse('init_request_contract')
    metadata = dict(directories=['artifacts', 'authorizations', 'intents', 'receipts', 'recovery', 'tmp'], files=[
        dict(path='run.json', sha256=doc['manifest_sha256'], size=doc['manifest_size'], mode=0o400),
        dict(path='run.json.sha256', sha256=hashlib.sha256((doc['manifest_sha256'] + '\n').encode()).hexdigest(),
             size=65, mode=0o400)])
    if doc['metadata'] != metadata or len(binding_canonical(doc)) > 65536:
        binding_refuse('init_metadata_scope')
    return doc


def binding_init_attestation(doc, identity, request):
    expected = dict(format_version=1, operation_id=hashlib.sha256(binding_canonical(identity)).hexdigest(),
                    request_sha256=hashlib.sha256(binding_canonical(request)).hexdigest(),
                    manifest_sha256=request['manifest_sha256'],
                    metadata_sha256=hashlib.sha256(binding_canonical(request['metadata'])).hexdigest(),
                    writer='ATTENDED_VERIFIER', durable=True)
    if doc != expected or type(doc.get('format_version')) is not int or doc.get('durable') is not True:
        binding_refuse('init_local_write_attestation')
    return doc


def binding_validate_init_completion(identity, request, result, target):
    """Pure closed-schema verification; caller separately pins the remote history."""
    binding_init_request(request, identity, Path(target))
    operation_id = hashlib.sha256(binding_canonical(identity)).hexdigest()
    keys = {'format_version', 'identity', 'operation_id', 'exit', 'outcome', 'completion',
            'request_sha256', 'attestation', 'completed_at'}
    if (not isinstance(result, dict) or set(result) != keys or type(result['format_version']) is not int or
            result['format_version'] != 1 or result['identity'] != identity or result['operation_id'] != operation_id or
            type(result['exit']) is not int or result['exit'] != 0 or result['outcome'] != 'SUCCEEDED' or
            result['completion'] != 'LOCAL_METADATA_ATTESTED' or
            result['request_sha256'] != hashlib.sha256(binding_canonical(request)).hexdigest()):
        binding_refuse('init_result_binding')
    binding_init_attestation(result['attestation'], identity, request)
    try:
        completed = datetime.datetime.fromisoformat(result['completed_at'])
        if completed.tzinfo is None or completed.utcoffset() != datetime.timedelta(0):
            binding_refuse('init_completion_time')
    except (TypeError, ValueError):
        binding_refuse('init_completion_time')
    return result


def binding_require_init_completion(root, target, current, completion):
    initialized = [item for item in current if item['kind'] == 'INIT']
    if len(initialized) != 1 or not isinstance(completion, dict) or set(completion) != {
            'request', 'result', 'request_sha256', 'result_sha256'}:
        binding_refuse('local_init_completion_required')
    identity = initialized[0]
    binding_validate_init_completion(identity, completion['request'], completion['result'], target)
    operation_id = hashlib.sha256(binding_canonical(identity)).hexdigest()
    for key in ('request', 'result'):
        path = root / (operation_id + '.' + key + '.json')
        if (completion[key] != binding_read(path) or completion[key + '_sha256'] != binding_hash(path)):
            binding_refuse('local_remote_init_completion_mismatch')


def binding_result(path, identity, target):
    outcome = binding_read(path)
    operation_id = hashlib.sha256(binding_canonical(identity)).hexdigest()
    if identity['kind'] == 'INIT':
        request = binding_request(path.parent / (operation_id + '.request.json'), identity, target)
        binding_validate_init_completion(identity, request, outcome, target)
    else:
        if (set(outcome) != {'identity', 'operation_id', 'exit', 'outcome', 'children', 'log_sha256', 'completed_at'} or
                outcome['identity'] != identity or outcome['operation_id'] != operation_id or
                type(outcome['exit']) is not int or not 0 <= outcome['exit'] <= 255 or
                outcome['outcome'] != ('SUCCEEDED' if outcome['exit'] == 0 else 'UNKNOWN') or
                outcome['children'] != 'REAPED'):
            binding_refuse('result_binding')
        log = path.parent / (operation_id + '.log')
        binding_protected(log, 0o400)
        if binding_hash(log) != outcome['log_sha256']:
            binding_refuse('log_binding')
    return outcome


def binding_existing_lock(target):
    target = Path(target)
    if not target.is_absolute() or str(target.resolve(strict=True)) != str(target):
        binding_refuse('target_not_canonical')
    info = target.stat()
    if info.st_uid != os.geteuid() or info.st_mode & 0o022:
        binding_refuse('target_ownership')
    root = target / '.v126-target-operations'
    binding_protected(root, 0o700, True)
    binding_protected(root / 'lock', 0o600)
    fd = os.open(root / 'lock', os.O_RDWR | os.O_NOFOLLOW)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        binding_lock_held(root, fd)
        return root, fd
    except BaseException:
        os.close(fd)
        raise


def binding_lock_held(root, fd):
    binding_protected(root, 0o700, True)
    binding_protected(root / 'lock', 0o600)
    actual, current = os.fstat(fd), (root / 'lock').stat()
    if (actual.st_dev, actual.st_ino) != (current.st_dev, current.st_ino):
        binding_refuse('target_lock_replaced')
    if sys.platform == 'linux':
        # Verify this exact open file description, not merely contention from
        # another process which might have acquired the lock after ours was lost.
        with open('/proc/self/fdinfo/' + str(fd), encoding='ascii') as handle:
            raw = handle.read(16385)
        device_inode = f'{os.major(actual.st_dev):02x}:{os.minor(actual.st_dev):02x}:{actual.st_ino}'
        expected = re.compile(r'^lock:\s+\d+: FLOCK\s+ADVISORY\s+WRITE\s+' + str(os.getpid()) +
                              r'\s+' + re.escape(device_inode) + r'\s+0 EOF$', re.M)
        if len(raw) > 16384 or not expected.search(raw):
            binding_refuse('target_lock_not_held')
        return
    probe = os.open(root / 'lock', os.O_RDWR | os.O_NOFOLLOW)
    try:
        try:
            fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        binding_refuse('target_lock_not_held')
    finally:
        os.close(probe)


def binding_history(root, target):
    owner, owners = binding_chain(root, target)
    expected = {'lock', 'run.json'}
    if (root / 'transfers').exists():
        expected.add('transfers')
    current = []
    recovery = False
    for prior_owner in owners:
        inventory, unknown, identities = binding_inventory(root, prior_owner)
        if unknown:
            for item in identities:
                result_path = root / (hashlib.sha256(binding_canonical(item)).hexdigest() + '.result.json')
                if result_path.exists() and binding_read(result_path).get('exit') != 0:
                    binding_refuse('prior_daemon_outcome_unknown')
            binding_refuse('prior_outcome_unknown')
        expected.update(name.split('/')[0] for name in inventory)
        if prior_owner == owner:
            current = identities
            recovery = any(item['kind'] == 'RECOVERY' for item in current)
    if set(path.name for path in root.iterdir()) != expected:
        binding_refuse('unexpected_target_records')
    return owner, owners, current, recovery


def binding_admit_operation(root, target, identity, current, request_sha256=None):
    policy = binding_active_policy(root, target)
    if policy['next_kind'] == 'ORDINARY_DEPLOY':
        if ((identity['kind'], identity['name'], identity['action']) != ('DEPLOY', 'ORDINARY_DEPLOY', 'ordinary-deploy') or
                request_sha256 != policy['request_sha256'] or identity['intent_sha256'] != request_sha256 or current):
            binding_refuse('ordinary_deploy_requires_exact_next_request')
        return
    if identity['kind'] == 'DEPLOY':
        binding_refuse('ordinary_deploy_not_authorized_by_transfer')
    if identity['kind'] == 'INIT':
        if current:
            binding_refuse('init_requires_empty_owner_history')
        return
    if identity['kind'] == 'RECOVERY':
        if not any(item['kind'] != 'INIT' for item in current):
            binding_refuse('next_binding_requires_fresh_baseline')
        return  # Existing, separately authorized historical recovery remains inspectable/usable.
    initialized = [item for item in current if item['kind'] == 'INIT']
    if len(initialized) != 1:
        binding_refuse('cutover_requires_completed_init')
    baseline = [item for item in current if item['kind'] == 'STAGE' and item['name'] == 'BASELINE_VERIFIED']
    if identity['name'] != 'BASELINE_VERIFIED' and not baseline:
        binding_refuse('next_binding_requires_fresh_baseline')
    if identity['name'] == 'BASELINE_VERIFIED' and baseline:
        binding_refuse('baseline_already_dispatched')


def binding_history_snapshot(root):
    """Exact bounded read of the existing registry, including this invocation's intent."""
    import time
    deadline = time.monotonic() + 30
    result = {}
    size = 0
    pending = [root]
    while pending:
        directory = pending.pop()
        binding_protected(directory, 0o700, True)
        info = directory.stat()
        result[str(directory.relative_to(root))] = [info.st_dev, info.st_ino, 0o700, 'directory']
        for path in sorted(directory.iterdir()):
            if len(result) > 4096 or time.monotonic() >= deadline:
                binding_refuse('history_read_bound')
            info = path.lstat()
            if stat.S_ISDIR(info.st_mode):
                pending.append(path)
                continue
            mode = 0o600 if path == root / 'lock' or (path.suffix == '.log' and stat.S_IMODE(info.st_mode) == 0o600) else 0o400
            binding_protected(path, mode)
            size += info.st_size
            if size > 16 * 1024 * 1024:
                binding_refuse('history_size_bound')
            result[str(path.relative_to(root))] = [info.st_dev, info.st_ino, mode, binding_hash(path)]
    return result


def binding_history_unchanged(root, lockfd, expected):
    binding_lock_held(root, lockfd)
    if binding_history_snapshot(root) != expected:
        binding_refuse('history_changed_during_invocation')


def binding_live_history(root, lockfd, before, created):
    binding_lock_held(root, lockfd)
    after = binding_history_snapshot(root)
    if set(after) != set(before) | set(created) or any(after[name] != value for name, value in before.items()):
        binding_refuse('unexpected_inflight_history_delta')
    for name, digest in created.items():
        if after[name][3] != digest:
            binding_refuse('own_inflight_record_changed')
    return after


def binding_policy_b_dispatch(gate, identity, target, lockfd, timeout):
    # The attended transport consumes its one-use LATE nonce at this final boundary.
    # In-process Gate remains available only to source-bound isolated validation.
    callback = getattr(gate, 'before_dispatch', None)
    if callback is not None:
        callback(identity, target, lockfd, timeout)


def binding_initialize(target, identity, init_request, *, policy_b_gate, write_init, ack_init=None, timeout=300):
    """S-side INIT: existing target lock, R0, durable intent, attended metadata write."""
    import signal
    import time
    if sys.platform != 'linux':
        binding_refuse('linux_target_lock_required')
    binding_owner({key: identity.get(key) for key in ('run_id', 'release_sha', 'script_sha256')})
    if (set(identity) != {'run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action'} or
            (identity['kind'], identity['name'], identity['action']) != ('INIT', 'RUN_INITIALIZED', 'initialize-run') or
            timeout != 300 or policy_b_gate is None or not callable(write_init)):
        binding_refuse('init_identity_or_authority')
    target = Path(target)
    binding_init_request(init_request, identity, target)
    root, lockfd = binding_existing_lock(target)
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)}
    cancelled = False
    def cancel(signum, frame):
        nonlocal cancelled
        cancelled = True
    try:
        for sig in previous:
            signal.signal(sig, cancel)
        owner, _, current, _ = binding_history(root, target)
        if owner != {key: identity[key] for key in owner}:
            binding_refuse('target_bound_to_another_run')
        binding_admit_operation(root, target, identity, current)
        before = binding_history_snapshot(root)
        deadline = time.monotonic() + 930
        binding_policy_b_check(policy_b_gate, identity, target, lockfd, timeout)
        binding_history_unchanged(root, lockfd, before)
        if cancelled or time.monotonic() >= deadline:
            binding_refuse('init_cancelled_before_intent')
        operation_id = hashlib.sha256(binding_canonical(identity)).hexdigest()
        now = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()
        start = dict(identity=identity, operation_id=operation_id, started_at=now(),
                     boot_id=Path('/proc/sys/kernel/random/boot_id').read_text().strip())
        binding_create(root / (operation_id + '.start.json'), start)
        binding_create(root / (operation_id + '.request.json'), init_request)
        created = {operation_id + '.start.json': hashlib.sha256(binding_canonical(start)).hexdigest(),
                   operation_id + '.request.json': hashlib.sha256(binding_canonical(init_request)).hexdigest()}
        live = binding_live_history(root, lockfd, before, created)
        late = binding_policy_b_check(policy_b_gate, identity, target, lockfd, timeout)
        binding_history_unchanged(root, lockfd, live)
        if cancelled or time.monotonic() >= deadline:
            binding_refuse('init_cancelled_before_dispatch')
        action_started = time.monotonic()
        binding_policy_b_dispatch(policy_b_gate, identity, target, lockfd, timeout)
        if cancelled:
            binding_refuse('init_cancelled_before_dispatch')
        attestation = write_init(identity, init_request, late)
        if cancelled or time.monotonic() - action_started >= 300 or time.monotonic() >= deadline:
            binding_refuse('init_local_completion_expired')
        binding_init_attestation(attestation, identity, init_request)
        binding_history_unchanged(root, lockfd, live)
        if cancelled or time.monotonic() >= deadline:
            binding_refuse('init_completion_deadline')
        result = dict(format_version=1, identity=identity, operation_id=operation_id, exit=0, outcome='SUCCEEDED',
                      completion='LOCAL_METADATA_ATTESTED', request_sha256=created[operation_id + '.request.json'],
                      attestation=attestation, completed_at=now())
        binding_create(root / (operation_id + '.result.json'), result)
        if ack_init is not None:
            ack_started = time.monotonic()
            ack_init(result)
            if time.monotonic() - ack_started >= 30 or time.monotonic() >= deadline:
                binding_refuse('init_ack_deadline')
        return result
    finally:
        os.close(lockfd)
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def binding_init_readback(target, identity, request_sha256):
    """Read existing exact completion only; never rerun a writer or append a result."""
    target = Path(target)
    root, lockfd = binding_existing_lock(target)
    try:
        binding_history(root, target)
        if (identity.get('kind'), identity.get('name'), identity.get('action')) != ('INIT', 'RUN_INITIALIZED', 'initialize-run'):
            binding_refuse('init_readback_identity')
        operation_id = hashlib.sha256(binding_canonical(identity)).hexdigest()
        request_path = root / (operation_id + '.request.json')
        request = binding_request(request_path, identity, target)
        if binding_hash(request_path) != request_sha256:
            binding_refuse('init_readback_request')
        result_path = root / (operation_id + '.result.json')
        result = binding_result(result_path, identity, target)
        return dict(request=request, result=result, request_sha256=request_sha256,
                    result_sha256=binding_hash(result_path))
    finally:
        os.close(lockfd)


def binding_policy_b_required(identity):
    # Real stage/action identities select the barrier, never readiness claims.
    if identity['kind'] == 'INIT':
        if (identity['name'], identity['action']) != ('RUN_INITIALIZED', 'initialize-run'):
            binding_refuse('init_dispatch_identity')
        return True
    if identity['kind'] == 'DEPLOY':
        if (identity['name'], identity['action']) != ('ORDINARY_DEPLOY', 'ordinary-deploy'):
            binding_refuse('policy_b_dispatch_identity')
        return False
    if identity['action'] not in binding_action_sequence(identity['kind'], identity['name']):
        binding_refuse('policy_b_dispatch_identity')
    if identity['kind'] == 'RECOVERY':
        return False
    return identity['name'] in {
        'BASELINE_VERIFIED', 'FINAL_V125_PREFLIGHT_PASSED',
        'V126_MAINTENANCE_CONFIG_PREPARED', 'V126_BACKEND_STARTED',
        'MANUAL_SMOKE_AUTHORIZED', 'FINAL_V126_BACKEND_STARTED',
        'ORDINARY_CADDY_RESTORED',
    }


def binding_policy_b_check(gate, identity, target, lockfd, timeout):
    import time
    started = time.monotonic()
    try:
        result = gate.check(identity, target, lockfd, timeout)
        elapsed = time.monotonic() - started
        if elapsed < 0 or elapsed >= 300:
            binding_refuse('policy_b_round_expired')
        expected = 'R0' if identity['kind'] == 'INIT' or identity['name'] == 'BASELINE_VERIFIED' else 'Q'
        if (not isinstance(result, dict) or result.get('barrier') != expected or
                result.get('operational') is not True or
                not re.fullmatch('[0-9a-f]{64}', result.get('qualification_sha256', ''))):
            binding_refuse('policy_b_consumer_result')
        return result
    except Exception:
        # Do not expose provider data or turn a refusal into retry/recovery authority.
        binding_refuse('policy_b_admission_refused')


def binding_supervise(target, identity, worker_argv, *, input_data=None, input_fd=None,
                      env=None, timeout=300, request_sha256=None, pass_fds=(), request_context=None,
                      policy_b_gate=None, init_completion=None, prepare_payload=None):
    """Hold the single target lock through admission, children and durable result.

    Callers validate source before entry; workers contain only action consumers.
    Missing/nonzero outcomes never become no-effect authority.
    """
    import ctypes
    import signal
    import subprocess
    import time
    if sys.platform != 'linux':
        binding_refuse('linux_subreaper_required')
    libc = ctypes.CDLL(None, use_errno=True)
    if libc.prctl(36, 1, 0, 0, 0) != 0:
        binding_refuse('subreaper_unavailable')
    binding_owner({key: identity.get(key) for key in ('run_id', 'release_sha', 'script_sha256')})
    if (set(identity) != {'run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action'} or
            not re.fullmatch('[0-9a-f]{64}', identity['intent_sha256']) or not worker_argv or
            not 0 < timeout <= 1800):
        binding_refuse('invalid_identity')
    policy_b_required = binding_policy_b_required(identity)
    if identity['kind'] == 'INIT':
        binding_refuse('init_requires_metadata_handshake')
    if policy_b_required and policy_b_gate is None:
        # No file/env bootstrap: operational acquisition/transport needs an
        # approved AP-06 contract. Refuse before creating target records.
        binding_refuse('policy_b_independent_observer_required')
    upload_action = identity['kind'] == 'STAGE' and identity['action'] in ('preflight-upload', 'image-upload')
    if (upload_action != callable(prepare_payload) or
            (upload_action and (input_fd is not None or pass_fds))):
        binding_refuse('upload_requires_locked_preparation')
    lockfd = None
    logfd = None
    previous_signals = {sig: signal.getsignal(sig) for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)}
    try:
        operation_id = hashlib.sha256(binding_canonical(identity)).hexdigest()
        target = Path(target)
        if not target.is_absolute() or str(target.resolve(strict=True)) != str(target):
            binding_refuse('target_not_canonical')
        # Existing sequencer source/input guards still run inside the worker.
        info = target.stat()
        if info.st_uid != os.geteuid() or info.st_mode & 0o022:
            binding_refuse('target_ownership')
        root, lockfd = binding_existing_lock(target)
        owner, prior_owners, current_operations, recovery_seen = binding_history(root, target)
        requested_owner = {key: identity[key] for key in ('run_id', 'release_sha', 'script_sha256')}
        if owner != requested_owner:
            binding_refuse('target_bound_to_another_run')
        binding_admit_operation(root, target, identity, current_operations, request_sha256)
        if identity['kind'] == 'STAGE':
            binding_require_init_completion(root, target, current_operations, init_completion)
        if recovery_seen and identity['kind'] != 'RECOVERY':
            binding_refuse('prior_failed_operation_requires_recovery')
        if (root / (operation_id + '.start.json')).exists():
            binding_refuse('operation_already_dispatched')
        history_before = binding_history_snapshot(root)
        if policy_b_required:
            binding_policy_b_check(policy_b_gate, identity, target, lockfd, timeout)
        binding_history_unchanged(root, lockfd, history_before)
        now = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()
        start = dict(identity=identity, operation_id=operation_id, started_at=now(),
                     boot_id=Path('/proc/sys/kernel/random/boot_id').read_text().strip())
        binding_create(root / (operation_id + '.start.json'), start)
        created = {operation_id + '.start.json': hashlib.sha256(binding_canonical(start)).hexdigest()}
        if request_context is not None:
            request_path = root / (operation_id + '.request.json')
            binding_create(request_path, dict(format_version=1, identity=identity,
                target_sha256=hashlib.sha256(str(target).encode()).hexdigest(), **request_context))
            binding_request(request_path, identity, target)
            created[request_path.name] = binding_hash(request_path)
        log_path = root / (operation_id + '.log')
        logfd = os.open(log_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        created[log_path.name] = hashlib.sha256(b'').hexdigest()
        live_history = binding_live_history(root, lockfd, history_before, created)
        # Mutation output never depends on the SSH stdout pipe staying open.
        signal.signal(signal.SIGHUP, signal.SIG_IGN)
        cancelled = False

        def cancel(signum, frame):
            nonlocal cancelled
            cancelled = True

        for signum in (signal.SIGTERM, signal.SIGINT):
            signal.signal(signum, cancel)
        # Binary acquisition is part of this operation: no request/spool before
        # existing history admission, applicable EARLY and durable intent.
        def payload_current():
            if cancelled:
                binding_refuse('cancelled_during_payload')
            binding_lock_held(root, lockfd)

        payload_current()
        binding_history_unchanged(root, lockfd, live_history)
        if upload_action:
            transfer_started = time.monotonic()
            try:
                prepared = prepare_payload(identity, target, lockfd, live_history, payload_current)
            except Exception:
                binding_refuse('payload_acquisition_failed')
            elapsed = time.monotonic() - transfer_started
            if elapsed < 0 or elapsed >= 900:
                binding_refuse('payload_transfer_expired')
            if (not isinstance(prepared, tuple) or len(prepared) != 2 or
                    not isinstance(prepared[0], list) or not prepared[0] or
                    not all(isinstance(arg, str) for arg in prepared[0]) or
                    not isinstance(prepared[1], tuple) or len(prepared[1]) != 1 or
                    type(prepared[1][0]) is not int or prepared[1][0] <= 2 or prepared[1][0] == lockfd or
                    not stat.S_ISREG(os.fstat(prepared[1][0]).st_mode)):
                binding_refuse('payload_worker_binding')
            worker_argv, pass_fds = prepared
            payload_current()
        # No cached PASS after durable I/O or upload. LATE observes fresh inputs.
        if policy_b_required:
            binding_policy_b_check(policy_b_gate, identity, target, lockfd, timeout)
        binding_history_unchanged(root, lockfd, live_history)
        if cancelled:
            binding_refuse('cancelled_before_dispatch')
        deadline = time.monotonic() + timeout
        if policy_b_required:
            binding_policy_b_dispatch(policy_b_gate, identity, target, lockfd, timeout)
        if cancelled:
            binding_refuse('cancelled_before_dispatch')
        child = subprocess.Popen(worker_argv, stdin=(input_fd if input_fd is not None else subprocess.PIPE),
                                 stdout=logfd, stderr=logfd, start_new_session=True,
                                 env=env, pass_fds=pass_fds)
        pending_input = memoryview(input_data or b'') if input_fd is None else memoryview(b'')
        input_error = False
        if input_fd is None:
            os.set_blocking(child.stdin.fileno(), False)
        status = None
        worker_reaped = False
        interrupted = False
        descendant_failure = False
        cleanup_deadline = None
        # waitpid(-1) plus subreaper adoption is the completion proof, not a PID scan.
        while True:
            interrupted = interrupted or cancelled or time.monotonic() >= deadline
            if input_fd is None and not child.stdin.closed:
                try:
                    if pending_input:
                        sent = os.write(child.stdin.fileno(), pending_input[:65536])
                        pending_input = pending_input[sent:]
                    if not pending_input:
                        child.stdin.close()
                except BlockingIOError:
                    pass
                except BrokenPipeError:
                    input_error = bool(pending_input)
                    child.stdin.close()
            try:
                pid, wait_status = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                break
            if pid:
                value = os.waitstatus_to_exitcode(wait_status)
                if pid == child.pid:
                    worker_reaped = True
                    status = value if value >= 0 else 128 - value
                    child.returncode = value
                elif value != 0:
                    descendant_failure = True
                continue
            if interrupted:
                interrupted = True
                if cleanup_deadline is None:
                    cleanup_deadline = time.monotonic() + 10
                    status = 124
                    if not worker_reaped:
                        # The unreaped leader reserves this PID/PGID; after reaping
                        # it, only our unreaped adopted children are safe to signal.
                        try:
                            os.killpg(child.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                # Direct children are ours and remain unreaped, so their PIDs cannot be reused.
                for pid_text in Path('/proc/self/task/' + str(os.getpid()) + '/children').read_text().split():
                    try:
                        os.kill(int(pid_text), signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                if time.monotonic() >= cleanup_deadline:
                    binding_refuse('children_completion_unknown')
            time.sleep(0.05)
        if status is None:
            binding_refuse('worker_outcome_unknown')
        if input_fd is None and not child.stdin.closed:
            child.stdin.close()
        if interrupted or cancelled or time.monotonic() >= deadline:
            status = 124
        elif (input_error or pending_input) and status == 0:
            status = 75
        if descendant_failure and status == 0:
            status = 75
        if status == 0 and identity['kind'] == 'DEPLOY':
            try:
                binding_deploy_proof(root / (operation_id + '.deploy-proof.json'), identity, target)
            except (BindingError, OSError, ValueError, KeyError, TypeError):
                status = 75
        os.fsync(logfd)
        os.fchmod(logfd, 0o400)
        os.close(logfd)
        logfd = None
        raw = log_path.read_bytes()
        outcome = dict(identity=identity, operation_id=operation_id, exit=status,
                       outcome='SUCCEEDED' if status == 0 else 'UNKNOWN', children='REAPED',
                       log_sha256=hashlib.sha256(raw).hexdigest(), completed_at=now())
        binding_create(root / (operation_id + '.result.json'), outcome)
        # Broken transport does not alter durable operation results.
        try:
            sys.stdout.buffer.write(raw)
            sys.stdout.buffer.write(b'\nREMOTE_OPERATION_ACK\t' + binding_canonical(outcome))
            sys.stdout.buffer.flush()
        except BrokenPipeError:
            pass
        return status
    finally:
        if logfd is not None:
            # A late refusal retains the durable start as UNKNOWN, never SUCCESS.
            os.fsync(logfd)
            os.fchmod(logfd, 0o400)
            os.close(logfd)
        if lockfd is not None:
            os.close(lockfd)
        for sig, handler in previous_signals.items():
            signal.signal(sig, handler)
