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
        result = root / (op + '.result.json')
        log = root / (op + '.log')
        if not result.exists():
            unknown = True
            continue
        outcome = binding_read(result)
        if (set(outcome) != {'identity', 'operation_id', 'exit', 'outcome', 'children', 'log_sha256', 'completed_at'} or
                outcome['identity'] != identity or outcome['operation_id'] != op or
                type(outcome['exit']) is not int or not 0 <= outcome['exit'] <= 255 or
                outcome['outcome'] != ('SUCCEEDED' if outcome['exit'] == 0 else 'UNKNOWN') or
                outcome['children'] != 'REAPED'):
            raise BindingError('result_binding')
        binding_protected(log, 0o400)
        if binding_hash(log) != outcome['log_sha256']:
            raise BindingError('log_binding')
        files[result.name] = binding_hash(result)
        files[log.name] = outcome['log_sha256']
        unknown = unknown or outcome['exit'] != 0
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
        if (set(transfer) != {'format_version', 'previous_owner', 'next_owner', 'inventory', 'handoff'} or
                transfer['format_version'] != 1 or transfer['previous_owner'] != owner):
            raise BindingError('transfer_schema')
        next_owner = binding_owner(transfer['next_owner'])
        if any(previous['run_id'] == next_owner['run_id'] for previous in owners):
            raise BindingError('run_id_reuse')
        inventory, unknown, identities = binding_inventory(root, owner)
        if unknown or not inventory or transfer['inventory'] != inventory:
            raise BindingError('retired_outcome_not_proven')
        handoff = transfer['handoff']
        binding_handoff(handoff, owner, next_owner, handoff['terminal_receipt_sha256'], target)
        required = ('STAGE', 'FINAL_PUBLIC_GATES_PASSED') if handoff['operational_version'] == 'V126' else ('RECOVERY', 'pre-v126')
        if not any((identity['kind'], identity['name']) == required for identity in identities):
            raise BindingError('terminal_remote_operation_missing')
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
            allowed.update(files)
            # A missing result may have a private active/partial log: inspection is
            # conservative; it never adopts or removes such evidence.
            for name in tuple(files):
                if name.endswith('.start.json'):
                    log = name.removesuffix('.start.json') + '.log'
                    if (root / log).exists(): allowed.add(log)
        if set(p.name for p in root.iterdir()) != allowed:
            raise BindingError('unexpected_target_records')
        if mode == 'inspect':
            print(json.dumps(dict(owner=current, history_count=len(owners),
                  outcome='UNKNOWN' if unknown else 'COMMAND_RESULTS_VERIFIED',
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
        recordfd = os.open(directory / (binding_owner_id(owner) + '.json'),
                           os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o400)
        with os.fdopen(recordfd, 'wb') as handle:
            handle.write(binding_canonical(transfer)); handle.flush(); os.fsync(handle.fileno())
        for path in (directory, root):
            syncfd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(syncfd)
            finally: os.close(syncfd)
        print('TARGET_BINDING_RETIRED history_preserved=true next_baseline_only=true')
    finally:
        os.close(fd)
