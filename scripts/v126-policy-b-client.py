#!/usr/bin/env python3
"""Attended Policy B client and create-only local INIT metadata.

Operational entrypoints read independently enrolled authority; no test switches,
producer actions or authority bootstrap are available here.
"""
import argparse
import base64
from datetime import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
import tempfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'scripts/v126-cutover.sh'
STATE_DIRECTORIES = ('artifacts', 'authorizations', 'intents', 'receipts', 'recovery', 'tmp')
COMPLETION_FILE = 'init-completion.json'
COPY_TOKEN = 'AUTHORIZE_V126_INIT_COMPLETION_COPY'
MAX_METADATA = 64 * 1024


def module(name, filename):
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


def fail(code):
    raise ValueError(code)


def canonical(doc):
    return (json.dumps(doc, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def strict(raw):
    def pairs(items):
        out = {}
        for key, value in items:
            if key in out:
                fail('DUPLICATE_FIELD')
            out[key] = value
        return out
    if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_METADATA:
        fail('METADATA_BOUND')
    value = json.loads(raw, object_pairs_hook=pairs,
                       parse_constant=lambda _: fail('NONFINITE_NUMBER'))
    if canonical(value) != raw:
        fail('NONCANONICAL_METADATA')
    return value


def read_protected(path, mode=0o400, limit=MAX_METADATA):
    path = Path(path)
    if not path.is_absolute() or path.resolve(strict=True) != path:
        fail('METADATA_PATH')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
                or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != mode
                or not 0 < info.st_size <= limit):
            fail('METADATA_PROTECTION')
        raw = os.read(fd, limit + 1)
        after = os.fstat(fd)
        stable = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        if len(raw) != info.st_size or stable(info) != stable(after):
            fail('METADATA_CHANGED')
        return raw
    finally:
        os.close(fd)


def sync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def create_file(directory_fd, name, raw):
    fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                 0o400, dir_fd=directory_fd)
    try:
        view = memoryview(raw)
        while view:
            count = os.write(fd, view)
            if count <= 0:
                fail('INIT_SHORT_WRITE')
            view = view[count:]
        os.fsync(fd)
        os.fchmod(fd, 0o400)
    finally:
        os.close(fd)


def validate_init_manifest(manifest):
    doc = strict(manifest)
    expected = {'created_at', 'database_url_file', 'format_version', 'main_actions_run_id',
                'maintenance_identities_file', 'release_parents', 'release_sha', 'release_tree',
                'release_worktree', 'remote', 'run_id', 'script_sha256', 'staging_path',
                'v125_image_tag', 'v126_image_id', 'v126_image_tag'}
    if type(doc) is not dict or set(doc) != expected or type(doc['format_version']) is not int or doc['format_version'] != 1:
        fail('INIT_MANIFEST_SCHEMA')
    def match(name, pattern):
        if type(doc[name]) is not str or re.fullmatch(pattern, doc[name]) is None:
            fail('INIT_MANIFEST_FIELD')
    match('created_at', r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z')
    try:
        datetime.strptime(doc['created_at'], '%Y-%m-%dT%H:%M:%SZ')
    except ValueError:
        fail('INIT_MANIFEST_TIMESTAMP')
    match('run_id', r'[a-z0-9][a-z0-9._-]{5,63}')
    match('remote', r'[A-Za-z0-9][A-Za-z0-9._@-]*')
    for key in ('release_sha', 'release_tree'):
        match(key, r'[0-9a-f]{40}')
    match('script_sha256', r'[0-9a-f]{64}')
    if (type(doc['release_parents']) is not list or not 1 <= len(doc['release_parents']) <= 2
            or any(type(value) is not str or not re.fullmatch(r'[0-9a-f]{40}', value) for value in doc['release_parents'])
            or type(doc['main_actions_run_id']) is not int or doc['main_actions_run_id'] <= 0):
        fail('INIT_MANIFEST_FIELD')
    for key in ('database_url_file', 'maintenance_identities_file', 'release_worktree', 'staging_path'):
        match(key, r'/[A-Za-z0-9._/+:-]+')
        if any(part in ('.', '..') for part in doc[key].split('/')):
            fail('INIT_MANIFEST_PATH')
    match('v126_image_id', r'sha256:[0-9a-f]{64}')
    match('v126_image_tag', r'[a-z0-9][a-z0-9._/-]*:' + doc['release_sha'])
    match('v125_image_tag', r'[a-z0-9][a-z0-9._/-]*:f577934691a1a7a79ba327c54e2055425142b7be')
    return doc


def proposal_path(state, path, manifest):
    path, state = Path(path), Path(state)
    release = Path(validate_init_manifest(manifest)['release_worktree'])
    if (not path.is_absolute() or path.parent.resolve(strict=True) != path.parent
            or path == state or state in path.parents or path == release or release in path.parents):
        fail('INIT_PROPOSAL_PATH')
    return path


def prepare_init_proposal(state, path, manifest):
    """Persist a proposal for independent approval; never create canonical state."""
    request = init_request(state, manifest)
    if Path(state).exists() or Path(state).is_symlink():
        fail('INIT_STATE_EXISTS')
    verify_source(manifest)
    path = proposal_path(state, path, manifest)
    raw = canonical(dict(format_version=1, state_dir=str(state), manifest=strict(manifest)))
    strict(raw)
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if info.st_uid != os.geteuid() or info.st_mode & 0o022:
            fail('INIT_PROPOSAL_PARENT')
        create_file(fd, path.name, raw)
        os.fsync(fd)
        if read_protected(path) != raw:
            fail('INIT_PROPOSAL_READBACK')
    finally:
        os.close(fd)
    return dict(proposal_sha256=digest(raw), manifest_sha256=digest(manifest),
                request_sha256=digest(canonical(request)), identity=request['identity'])


def read_init_proposal(state, path, expected_sha256):
    if type(expected_sha256) is not str or not re.fullmatch(r'[0-9a-f]{64}', expected_sha256):
        fail('INIT_PROPOSAL_DIGEST')
    raw = read_protected(path)
    if digest(raw) != expected_sha256:
        fail('INIT_PROPOSAL_CHANGED')
    proposal = strict(raw)
    if (type(proposal) is not dict or set(proposal) != {'format_version', 'state_dir', 'manifest'}
            or type(proposal['format_version']) is not int or proposal['format_version'] != 1
            or proposal['state_dir'] != str(state)):
        fail('INIT_PROPOSAL_BINDING')
    manifest = canonical(proposal['manifest'])
    proposal_path(state, path, manifest)
    init_request(state, manifest)
    return manifest


def init_request(state, manifest):
    doc = validate_init_manifest(manifest)
    state = Path(state)
    release = Path(doc['release_worktree'])
    if (not state.is_absolute() or str(state) == '/' or state.parent.resolve(strict=True) != state.parent
            or state == release or release in state.parents):
        fail('INIT_STATE_PATH')
    identity = dict(run_id=doc['run_id'], release_sha=doc['release_sha'],
                    script_sha256=doc['script_sha256'], intent_sha256=digest(manifest),
                    kind='INIT', name='RUN_INITIALIZED', action='initialize-run')
    checksum = (digest(manifest) + '\n').encode()
    metadata = dict(directories=list(STATE_DIRECTORIES), files=[
        dict(path='run.json', sha256=digest(manifest), size=len(manifest), mode=0o400),
        dict(path='run.json.sha256', sha256=digest(checksum), size=len(checksum), mode=0o400)])
    return dict(format_version=1, identity=identity,
                target_sha256=digest(doc['staging_path'].encode()),
                manifest_sha256=digest(manifest), manifest_size=len(manifest),
                local_state_sha256=digest(str(state).encode()), metadata=metadata)


def write_metadata(state, manifest, request):
    """Called once only by the live transport after LATE admission.

    A partial directory is deliberately retained. It cannot authorize a stage.
    """
    started = time.monotonic()
    state = Path(state)
    expected = init_request(state, manifest)
    if request != expected:
        fail('INIT_REQUEST_MISMATCH')
    parent_fd = os.open(state.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    root_fd = None
    try:
        parent_stat = os.fstat(parent_fd)
        if parent_stat.st_uid != os.geteuid() or parent_stat.st_mode & 0o022:
            fail('INIT_PARENT_PROTECTION')
        os.mkdir(state.name, 0o700, dir_fd=parent_fd)
        os.fsync(parent_fd)
        root_fd = os.open(state.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                          dir_fd=parent_fd)
        for name in STATE_DIRECTORIES:
            os.mkdir(name, 0o700, dir_fd=root_fd)
        os.fsync(root_fd)
        create_file(root_fd, 'run.json', manifest)
        create_file(root_fd, 'run.json.sha256', (digest(manifest) + '\n').encode())
        os.fsync(root_fd)
        if (read_protected(state / 'run.json') != manifest
                or read_protected(state / 'run.json.sha256') != (digest(manifest) + '\n').encode()):
            fail('INIT_READBACK')
        current = os.stat(state.name, dir_fd=parent_fd, follow_symlinks=False)
        held = os.fstat(root_fd)
        if (current.st_dev, current.st_ino) != (held.st_dev, held.st_ino):
            fail('INIT_ROOT_CHANGED')
        if time.monotonic() - started > 300:
            fail('INIT_WRITER_TIMEOUT')
        return dict(format_version=1, operation_id=digest(canonical(request['identity'])),
                    request_sha256=digest(canonical(request)),
                    manifest_sha256=digest(manifest),
                    metadata_sha256=digest(canonical(request['metadata'])),
                    writer='ATTENDED_VERIFIER', durable=True)
    finally:
        if root_fd is not None:
            os.close(root_fd)
        os.close(parent_fd)


def validate_local_metadata(state):
    """Verify the immutable INIT metadata surface, retaining future native contents."""
    state = Path(state)
    if not state.is_absolute() or state.resolve(strict=True) != state:
        fail('INIT_STATE_PATH')
    root_fd = os.open(state, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    held = os.fstat(root_fd)
    directories = []
    try:
        if held.st_uid != os.geteuid() or stat.S_IMODE(held.st_mode) != 0o700:
            fail('INIT_STATE_PROTECTION')
        for name in STATE_DIRECTORIES:
            fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=root_fd)
            directories.append((name, fd))
            info = os.fstat(fd)
            if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
                fail('INIT_DIRECTORY_PROTECTION')
        manifest = read_protected(state / 'run.json')
        checksum = read_protected(state / 'run.json.sha256')
        if checksum != (digest(manifest) + '\n').encode():
            fail('INIT_MANIFEST_CHECKSUM')
        current = state.lstat()
        if (current.st_dev, current.st_ino) != (held.st_dev, held.st_ino):
            fail('INIT_ROOT_CHANGED')
        for name, fd in directories:
            current = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
            info = os.fstat(fd)
            if (not stat.S_ISDIR(current.st_mode) or
                    (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino)):
                fail('INIT_DIRECTORY_CHANGED')
        return manifest
    finally:
        for _, fd in directories:
            os.close(fd)
        os.close(root_fd)



def validate_completion(state, proof):
    manifest = validate_local_metadata(state)
    request = init_request(state, manifest)
    if not isinstance(proof, dict) or set(proof) != {'request', 'result', 'request_sha256', 'result_sha256'} or proof['request'] != request:
        fail('INIT_COMPLETION_BINDING')
    bindings = module('v126_client_bindings', 'v126-operation-bindings.py')
    bindings.binding_validate_init_completion(request['identity'], request, proof['result'], strict(manifest)['staging_path'])
    if (proof['request_sha256'] != digest(canonical(request))
            or proof['result_sha256'] != digest(canonical(proof['result']))):
        fail('INIT_COMPLETION_DIGEST')
    return proof


def save_completion(state, proof):
    validate_completion(state, proof)
    root_fd = os.open(state, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        create_file(root_fd, COMPLETION_FILE, canonical(proof))
        os.fsync(root_fd)
    finally:
        os.close(root_fd)
    if strict(read_protected(Path(state) / COMPLETION_FILE)) != proof:
        fail('INIT_COMPLETION_READBACK')


def require_completion(state):
    return validate_completion(state, strict(read_protected(Path(state) / COMPLETION_FILE)))


def require_local_state_lock(state):
    """Reuse the sequencer lock; only its live owning invocation may call V."""
    answer = subprocess.check_output(['bash', '-c',
        'source "$1"; validate_state_lock_surface "$2"', 'v126-local-lock',
        str(SOURCE), str(Path(state)/'.exclusive-lock')], stderr=subprocess.DEVNULL, timeout=30)
    if not re.fullmatch(rb'[1-9][0-9]*\n', answer):
        fail('LOCAL_STATE_LOCK')
    owner = int(answer)
    current = os.getpid()
    deadline = time.monotonic() + 30
    for _ in range(32):
        if current == owner:
            return
        if current <= 1 or time.monotonic() >= deadline:
            break
        parent = subprocess.check_output(['ps', '-o', 'ppid=', '-p', str(current)],
            stderr=subprocess.DEVNULL, timeout=min(5, max(.01, deadline-time.monotonic())))
        current = int(parent.strip())
    fail('LOCAL_STATE_LOCK_NOT_OWNED')


def verify_source(manifest):
    doc = strict(manifest)
    checkout = Path(doc['release_worktree'])
    if checkout.resolve(strict=True) != checkout or checkout != ROOT:
        fail('SOURCE_CHECKOUT')
    def git(*args):
        return subprocess.check_output(['git', '--no-optional-locks', '-C', str(checkout),
                                        *args], stderr=subprocess.DEVNULL, timeout=30)
    if (git('rev-parse', 'HEAD').decode().strip() != doc['release_sha']
            or git('rev-parse', 'HEAD^{tree}').decode().strip() != doc['release_tree']
            or git('status', '--porcelain', '--untracked-files=all')
            or digest(SOURCE.read_bytes()) != doc['script_sha256']
            or git('show', doc['release_sha'] + ':scripts/v126-cutover.sh') != SOURCE.read_bytes()):
        fail('EXACT_CLEAN_SOURCE_REQUIRED')


def verify_native_stage7(state, context, native_history):
    """Use the complete existing native verifier, never a replacement parser."""
    state = Path(state)
    manifest = read_protected(state / 'run.json')
    doc = strict(manifest)
    if (doc['run_id'] != context.run_id or doc['release_sha'] != context.release_sha
            or doc['staging_path'] != context.target):
        fail('NATIVE_STATE_BINDING')
    script = 'source "$1"; load_state "$2"; verify_receipt QUIESCED_BACKUP_REHEARSED >/dev/null'
    subprocess.run(['bash', '-c', script, 'v126-native-verifier', str(SOURCE), str(state)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=300)
    raw = read_protected(state / 'receipts/07-QUIESCED_BACKUP_REHEARSED.receipt.json',
                         limit=16 * 1024 * 1024)
    compare_native_history(state, context, native_history)
    adapter = module('v126_policy_b_dispatch', 'v126-policy-b-dispatch.py')
    return adapter.NativeStage7(raw, digest(manifest))


# Original envelope remains the source of native predecessor/Gate A/B/C bindings.
ENVELOPE_NAMES = (
    'ACTION', 'RUN_ID', 'RELEASE_SHA', 'STAGING_PATH', 'SCRIPT_SHA256', 'V126_IMAGE_ID',
    'OPERATION_KIND', 'OPERATION_NAME', 'PREDECESSOR_STAGE', 'PREDECESSOR_HASH',
    'AUTHORIZATION_GATE', 'AUTHORIZATION_HASH', 'INTENT_HASH',
    'BASELINE_DATABASE_URL_SHA256', 'DATABASE_TARGET_IDENTITY_SHA256',
    'BASELINE_MAINTENANCE_IDENTITIES_SHA256', 'BASELINE_COMPOSE_SOURCE_SHA256',
    'BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256', 'BASELINE_ADMISSION_SOURCE_SHA256',
    'BASELINE_CADDY_SHA256', 'BASELINE_ENV_SHA256', 'CADDY_ORIGINAL_SHA256',
    'CADDY_CANDIDATE_SHA256', 'CADDY_DIFF_SHA256', 'CADDY_ACTIVATION_SHA256',
    'MAINTENANCE_SMOKE_SHA256', 'MAINTENANCE_OFF_SHA256',
)


def read_local_stream(path, source):
    """Decode generated data only; the old client-provided shell loader is never executed."""
    with open(path, 'rb') as handle:
        prefix = handle.read(2 * 1024 * 1024)
        magic = b'\nV126_INTERNAL_REMOTE_ENVELOPE_V1\n'
        if prefix.count(magic) != 1:
            fail('LOCAL_ENVELOPE_MAGIC')
        start = prefix.index(magic) + len(magic)
        handle.seek(start)
        values = []
        for _ in range(len(ENVELOPE_NAMES) + 1):
            raw = handle.readline(4097)
            if len(raw) > 4096 or not raw.endswith(b'\n') or b'\x00' in raw or b'\r' in raw:
                fail('LOCAL_ENVELOPE_FIELD')
            values.append(raw[:-1].decode('ascii'))
        count = values.pop()
        if not re.fullmatch(r'[1-9][0-9]?', count) or not 1 <= int(count) <= 32:
            fail('LOCAL_ENVELOPE_ARGUMENTS')
        args = []
        for _ in range(int(count)):
            raw = handle.readline(4097)
            if not raw.endswith(b'\n') or len(raw) > 4096 or any(x in raw[:-1] for x in (b'\r', b'\t', b'\x00')):
                fail('LOCAL_ENVELOPE_ARGUMENT')
            args.append(raw[:-1].decode('ascii'))
        if handle.read(len(source)) != source:
            fail('LOCAL_ENVELOPE_SOURCE')
        environment = {'V126_INTERNAL_REMOTE_' + k: v for k, v in zip(ENVELOPE_NAMES, values)}
        environment['V126_INTERNAL_REMOTE_MODE'] = 'true'
        environment['V126_INTERNAL_REMOTE_ENVELOPE_VALIDATED'] = 'V126_INTERNAL_REMOTE_ENVELOPE_V1'
        action = environment['V126_INTERNAL_REMOTE_ACTION']
        payload_offset = None
        if action in ('preflight-upload', 'image-upload'):
            if handle.read(1) != b'\0':
                fail('LOCAL_UPLOAD_DELIMITER')
            payload_offset = handle.tell()
            size = os.fstat(handle.fileno()).st_size - payload_offset
            if not 0 < size <= 8 * 1024**3:
                fail('LOCAL_UPLOAD_SIZE')
        elif handle.read(1):
            fail('LOCAL_ENVELOPE_TRAILING_BYTES')
        identity = dict(run_id=values[1], release_sha=values[2], script_sha256=values[4],
                        intent_sha256=values[12], kind=values[6], name=values[7], action=values[0])
        return identity, args, environment, payload_offset


def native_history_snapshot(target, owner):
    """Sanitized exact native operation metadata under the supervisor's target lock."""
    bindings = module('v126_client_bindings', 'v126-operation-bindings.py')
    root = Path(target) / '.v126-target-operations'
    stages = ('BASELINE_VERIFIED', 'PRE_DRAIN_BACKUP_REHEARSED',
              'CADDY_CANDIDATE_INSTALLED_AND_RELOADED', 'PUBLIC_DRAIN_ACTIVE',
              'V125_BACKEND_STOPPED', 'ZERO_WRITER_GATE_PASSED', 'QUIESCED_BACKUP_REHEARSED')
    result = []
    for stage in stages:
        matches = []
        for start_path in root.glob('*.start.json'):
            start = bindings.binding_read(start_path)
            identity = start['identity']
            if (identity.get('kind') == 'STAGE' and identity.get('name') == stage
                    and all(identity.get(k) == owner[k] for k in ('run_id', 'release_sha', 'script_sha256'))):
                matches.append((start_path, identity))
        if len(matches) != 1:
            fail('NATIVE_REMOTE_HISTORY_INCOMPLETE')
        start_path, identity = matches[0]
        op = digest(canonical(identity))
        request_path = root / (op + '.request.json')
        bindings.binding_request(request_path, identity, Path(target))
        outcome = bindings.binding_result(root / (op + '.result.json'), identity, Path(target))
        if outcome['exit'] != 0:
            fail('NATIVE_REMOTE_OUTCOME')
        raw = read_protected(root / (op + '.log'), limit=16*1024*1024)
        artifacts = []
        seen = set()
        for line in raw.splitlines():
            if line.startswith(b'ARTIFACT\t'):
                fields = line.split(b'\t')
                if (len(fields) != 3 or not re.fullmatch(rb'[a-z0-9][a-z0-9._-]{0,63}', fields[1])
                        or not re.fullmatch(rb'[0-9a-f]{64}', fields[2]) or fields[1] in seen):
                    fail('NATIVE_REMOTE_ARTIFACTS')
                seen.add(fields[1])
                artifacts.append(dict(name=fields[1].decode(), sha256=fields[2].decode()))
        result.append(dict(identity=identity, request_sha256=digest(request_path.read_bytes()),
                           result_sha256=digest((root / (op+'.result.json')).read_bytes()),
                           log_sha256=outcome['log_sha256'], completed_at=outcome['completed_at'],
                           artifacts=artifacts))
    if len(canonical(result)) > MAX_METADATA // 2:
        fail('NATIVE_HISTORY_BOUND')
    return result


def compare_native_history(state, context, history):
    from datetime import datetime, timedelta
    stages = ('BASELINE_VERIFIED', 'PRE_DRAIN_BACKUP_REHEARSED',
              'CADDY_CANDIDATE_INSTALLED_AND_RELOADED', 'PUBLIC_DRAIN_ACTIVE',
              'V125_BACKEND_STOPPED', 'ZERO_WRITER_GATE_PASSED', 'QUIESCED_BACKUP_REHEARSED')
    actions = ('baseline', 'backup-rehearsal', 'caddy-activate', 'public-drain-on',
               'stop-backend', 'zero-writer', 'backup-rehearsal')
    if not isinstance(history, list) or len(history) != len(stages):
        fail('NATIVE_REMOTE_HISTORY_REQUIRED')
    for index, (stage, action, observed) in enumerate(zip(stages, actions, history), 1):
        expected_keys = {'identity', 'request_sha256', 'result_sha256', 'log_sha256', 'completed_at', 'artifacts'}
        if not isinstance(observed, dict) or set(observed) != expected_keys:
            fail('NATIVE_REMOTE_HISTORY_SCHEMA')
        raw = read_protected(Path(state)/'receipts'/f'{index:02d}-{stage}.receipt.json')
        native = strict(raw)
        identity = observed['identity']
        if (identity.get('run_id') != context.run_id or identity.get('release_sha') != context.release_sha
                or identity.get('script_sha256') != context.script_sha256
                or identity.get('kind') != 'STAGE' or identity.get('name') != stage
                or identity.get('action') != action or identity.get('intent_sha256') != native['intent_sha256']):
            fail('NATIVE_REMOTE_IDENTITY')
        for key in ('request_sha256', 'result_sha256', 'log_sha256'):
            if not isinstance(observed[key], str) or not re.fullmatch('[0-9a-f]{64}', observed[key]):
                fail('NATIVE_REMOTE_PROOF_IDENTITY')
        completed = datetime.fromisoformat(observed['completed_at'])
        local_completed = datetime.fromisoformat(native['completed_at'])
        if completed.tzinfo is None or local_completed.tzinfo is None or completed >= local_completed + timedelta(seconds=1):
            fail('NATIVE_REMOTE_CHRONOLOGY')
        expected = {item['name']: item['sha256'] for item in native['artifacts']}
        seen = set()
        if not isinstance(observed['artifacts'], list) or not observed['artifacts']:
            fail('NATIVE_REMOTE_ARTIFACTS_MISSING')
        for item in observed['artifacts']:
            if (not isinstance(item, dict) or set(item) != {'name', 'sha256'}
                    or item['name'] in seen or expected.get(item['name']) != item['sha256']):
                fail('NATIVE_REMOTE_ARTIFACT_MISMATCH')
            seen.add(item['name'])
        required = set(expected) - {'operation-log'}
        if stage == 'BASELINE_VERIFIED':
            required -= {'local-baseline', 'main-actions'}
        if seen != required:
            fail('NATIVE_REMOTE_ARTIFACT_SET')
        # Native logs contain exact remote bytes. Baseline alone prepends two
        # independently validated local ARTIFACT lines; remove only those bytes.
        local_log = read_protected(Path(state)/'artifacts'/f'{index}-{stage}.operation.log', limit=16*1024*1024)
        if stage == 'BASELINE_VERIFIED':
            prefix = b''.join(('ARTIFACT\t' + name + '\t' + expected[name] + '\n').encode()
                              for name in ('local-baseline', 'main-actions'))
            if not local_log.startswith(prefix):
                fail('NATIVE_BASELINE_LOG_PREFIX')
            local_log = local_log[len(prefix):]
        if digest(local_log) != observed['log_sha256']:
            fail('NATIVE_REMOTE_LOG_BINDING')


def load_transport_config(path, manifest, enrollment):
    config = strict(read_protected(Path(path), mode=0o600))
    if not isinstance(config, dict) or set(config) != {'remote_alias', 'ssh'}:
        fail('TRANSPORT_LOCATOR_SCHEMA')
    if config['remote_alias'] != strict(manifest)['remote']:
        fail('TRANSPORT_ALIAS_BINDING')
    options = config['ssh']
    if options.get('host_fingerprint') != enrollment.document['host_fingerprint']:
        fail('TRANSPORT_HOST_BINDING')
    transport = module('v126_policy_b_transport', 'v126-policy-b-transport.py')
    with transport.ssh_command(options):
        pass
    return options


def make_open(enrollment, manifest, identity, request, *, arguments=(), environment=None,
              completion=None, payload=None, mode='EXECUTE'):
    doc = enrollment.document
    initializing = identity['kind'] == 'INIT'
    return dict(version=1, type='OPEN', mode=mode, session_id=os.urandom(32).hex(),
                anchor_sha256=enrollment.sha256, anchor_generation=doc['generation'],
                principal_fingerprint=doc['principal_fingerprint'],
                host_fingerprint=doc['host_fingerprint'], identity=identity,
                target=strict(manifest)['staging_path'], request_sha256=digest(canonical(request)),
                manifest_sha256=digest(manifest) if initializing else None,
                manifest_size=len(manifest) if initializing else 0,
                source_tree=doc['source_tree'], tooling_sha256=doc['tooling_sha256'],
                runtime=doc['python_version'], worker_args=list(arguments),
                environment=environment or {}, init_request=request if initializing else None,
                init_completion=completion, payload=payload)


def complete_proof(request, result):
    return dict(request=request, result=result, request_sha256=digest(canonical(request)),
                result_sha256=digest(canonical(result)))


def genesis_module():
    return module('v126_legacy_genesis', 'v126-legacy-genesis.py')


def verify_genesis_source(request):
    """The proposal never grants authority to substitute source/tool identities."""
    g = genesis_module()
    g.validate_request(request)
    def git(*args):
        return subprocess.check_output(['git', '--no-optional-locks', '-C', str(ROOT), *args],
                                       stderr=subprocess.DEVNULL, timeout=30)
    authority = module('v126_policy_b_authority', 'v126-policy-b-authority.py')
    if (git('rev-parse', 'HEAD').decode().strip() != request['source_sha']
            or git('rev-parse', 'HEAD^{tree}').decode().strip() != request['source_tree']
            or git('status', '--porcelain', '--untracked-files=all')
            or digest(SOURCE.read_bytes()) != request['script_sha256']
            or git('show', request['source_sha'] + ':scripts/v126-cutover.sh') != SOURCE.read_bytes()
            or authority.dr.digest(authority.dr.tooling(ROOT)) != request['tooling_sha256']
            or '.'.join(map(str, sys.version_info[:3])) != request['python_version']):
        fail('EXACT_CLEAN_GENESIS_SOURCE_REQUIRED')


def new_protected_metadata(path, raw):
    path = Path(path)
    if (not path.is_absolute() or path.parent.resolve(strict=True) != path.parent
            or path == ROOT or ROOT in path.parents):
        fail('GENESIS_LOCAL_OUTPUT_PATH')
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if info.st_uid != os.geteuid() or info.st_mode & 0o022:
            fail('GENESIS_LOCAL_OUTPUT_PROTECTION')
        create_file(fd, path.name, raw)
        os.fsync(fd)
        if read_protected(path) != raw:
            fail('GENESIS_LOCAL_OUTPUT_READBACK')
    finally:
        os.close(fd)


def prepare_target_binding(args):
    """Output exact proposal bytes before independent catalogue approval.

    No server access, target registry or canonical INIT state is created here.
    Observed target fields are assertions, rechecked independently on V and S.
    """
    if Path(args.state_dir).exists() or Path(args.state_dir).is_symlink():
        fail('INIT_STATE_EXISTS')
    manifest = read_init_proposal(args.state_dir, args.init_proposal_file,
                                  args.init_proposal_sha256)
    verify_source(manifest)
    doc = strict(manifest)
    authority = module('v126_policy_b_authority', 'v126-policy-b-authority.py')
    from datetime import timezone
    request = dict(schema_version=1, kind='legacy-target-genesis-request',
        run_id=doc['run_id'], source_sha=doc['release_sha'], source_tree=doc['release_tree'],
        script_sha256=doc['script_sha256'], tooling_sha256=authority.dr.digest(authority.dr.tooling(ROOT)),
        python_version='.'.join(map(str, sys.version_info[:3])),
        target=dict(path=doc['staging_path'], device=args.target_device, inode=args.target_inode,
                    uid=args.target_uid, host_fingerprint=args.host_fingerprint),
        next_init_manifest_sha256=digest(manifest), inventory_sha256=args.inventory_sha256,
        created_at=datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        expires_at=args.expires_at, nonce=os.urandom(32).hex())
    genesis_module().validate_request(request)
    raw = canonical(request)
    new_protected_metadata(args.proposal_file, raw)
    return dict(proposal_sha256=digest(raw), identity=genesis_identity(request),
                next_init_manifest_sha256=request['next_init_manifest_sha256'])


def genesis_identity(request):
    return dict(run_id=request['run_id'], release_sha=request['source_sha'],
                script_sha256=request['script_sha256'], intent_sha256=digest(canonical(request)),
                kind='TARGET_BIND', name='LEGACY_GENESIS', action='bind-legacy-target')


def run_genesis_client(args):
    if args.command == 'prepare-target-binding':
        sys.stdout.buffer.write(canonical(prepare_target_binding(args)))
        return 0
    completion_path = Path(args.completion_file)
    if (not completion_path.is_absolute() or completion_path.parent.resolve(strict=True) != completion_path.parent
            or completion_path.exists() or completion_path.is_symlink()
            or completion_path == ROOT or ROOT in completion_path.parents):
        fail('GENESIS_COMPLETION_OUTPUT_EXISTS_OR_INVALID')
    parent_info = completion_path.parent.stat()
    if parent_info.st_uid != os.geteuid() or parent_info.st_mode & 0o022:
        fail('GENESIS_COMPLETION_OUTPUT_PROTECTION')
    raw = read_protected(args.proposal_file)
    if digest(raw) != args.proposal_sha256:
        fail('GENESIS_PROPOSAL_CHANGED')
    request = strict(raw)
    verify_genesis_source(request)
    authority = module('v126_policy_b_authority', 'v126-policy-b-authority.py')
    transport = module('v126_policy_b_transport', 'v126-policy-b-transport.py')
    enrollment = authority.load_enrolled_authority(args.policy_b_anchor)
    locator = strict(read_protected(args.policy_b_transport, mode=0o600))
    if (type(locator) is not dict or set(locator) != {'remote_alias', 'ssh'}
            or locator['remote_alias'] != args.remote
            or locator['ssh'].get('host_fingerprint') != enrollment.document['host_fingerprint']):
        fail('GENESIS_TRANSPORT_BINDING')
    identity = genesis_identity(request)
    verifier = authority.VVerifier(enrollment, None)
    verifier.set_genesis_request(raw)
    mode = 'GENESIS_READBACK' if args.command == 'copy-target-binding-completion' else 'EXECUTE'
    if mode == 'GENESIS_READBACK' and args.authorization != 'AUTHORIZE_V126_GENESIS_COMPLETION_COPY':
        fail('GENESIS_COPY_AUTHORITY_REQUIRED')
    # U's independently approved anchor and exact catalogue action are acquired
    # by the real verifier on each fresh EARLY/LATE server challenge.
    doc = enrollment.document
    opened = dict(version=1, type='OPEN', mode=mode, session_id=os.urandom(32).hex(),
        anchor_sha256=enrollment.sha256, anchor_generation=doc['generation'],
        principal_fingerprint=doc['principal_fingerprint'], host_fingerprint=doc['host_fingerprint'],
        identity=identity, target=request['target']['path'], request_sha256=digest(raw),
        manifest_sha256=digest(raw), manifest_size=len(raw), source_tree=request['source_tree'],
        tooling_sha256=request['tooling_sha256'], runtime=request['python_version'],
        worker_args=[], environment={}, init_request=None, init_completion=None, payload=None,
        genesis_request=request)
    reply = transport.invoke_operation(locator['ssh'], opened, SOURCE.read_bytes(), verifier)
    if reply['status'] != 0:
        fail('GENESIS_OUTCOME_UNCONFIRMED')
    # Only copy completion returned by the same authenticated session. A lost
    # response never authorizes a second genesis and never removes local evidence.
    proof = reply['result']
    expected = {'request', 'result', 'request_sha256', 'result_sha256', 'legacy_inventory', 'legacy_inventory_sha256'}
    if (type(proof) is not dict or set(proof) != expected or proof['request'] != request
            or proof['request_sha256'] != digest(raw)
            or proof['result_sha256'] != digest(canonical(proof['result']))
            or proof['legacy_inventory_sha256'] != request['inventory_sha256']
            or proof['legacy_inventory_sha256'] != digest(canonical(proof['legacy_inventory']))):
        fail('GENESIS_COMPLETION_SCHEMA')
    bindings = module('v126_client_bindings', 'v126-operation-bindings.py')
    bindings.binding_validate_genesis_completion(request, proof['result'], proof['legacy_inventory'],
                                                request['target']['path'])
    new_protected_metadata(args.completion_file, canonical(proof))
    sys.stdout.buffer.write(canonical(dict(completion_sha256=digest(canonical(proof)),
                                          request_sha256=digest(raw))))
    return 0


def run_client(args):
    if args.command in ('prepare-target-binding', 'bind-legacy-target', 'copy-target-binding-completion'):
        return run_genesis_client(args)
    if args.command == 'verify-completion':
        require_completion(args.state_dir)
        return 0
    state = Path(args.state_dir)
    if args.command == 'prepare-init':
        manifest = sys.stdin.buffer.read(MAX_METADATA + 1)
        summary = prepare_init_proposal(state, args.proposal_file, manifest)
        sys.stdout.buffer.write(canonical(summary))
        return 0
    if args.command != 'init':
        require_local_state_lock(state)
    if args.command == 'init':
        proposal_file = getattr(args, 'proposal_file', None)
        proposal_sha256 = getattr(args, 'proposal_sha256', None)
        if bool(proposal_file) != bool(proposal_sha256):
            fail('INIT_PROPOSAL_REQUIRED')
        # The outer CLI always supplies a prepared proposal. Existing internal
        # callers may supply exact preapproved bytes directly, without regeneration.
        manifest = (read_init_proposal(state, proposal_file, proposal_sha256) if proposal_file
                    else sys.stdin.buffer.read(MAX_METADATA + 1))
        validate_init_manifest(manifest)
    else:
        manifest = read_protected(state/'run.json')
    doc = strict(manifest)
    verify_source(manifest)
    authority = module('v126_policy_b_authority', 'v126-policy-b-authority.py')
    transport = module('v126_policy_b_transport', 'v126-policy-b-transport.py')
    enrollment = authority.load_enrolled_authority(args.policy_b_anchor)
    ssh_options = load_transport_config(args.policy_b_transport, manifest, enrollment)
    verifier = authority.VVerifier(enrollment, lambda context, history: verify_native_stage7(state, context, history))
    source = SOURCE.read_bytes()
    if args.command in ('init', 'complete-init-copy'):
        request = init_request(state, manifest)
        identity = request['identity']
        if args.command == 'complete-init-copy':
            if args.authorization != COPY_TOKEN:
                fail('INIT_COPY_AUTHORITY_REQUIRED')
            authorization = verifier.authorize_action(identity, doc['staging_path'], scope='COPY_ONLY')
            authorized_at = time.monotonic()
            opened = make_open(enrollment, manifest, identity, request, mode='INIT_READBACK')
            reply = transport.invoke_operation(ssh_options, opened, source, verifier)
            if reply['status'] != 0:
                fail('INIT_READBACK_UNCONFIRMED')
            verifier.recheck_sources()
            verifier.now()
            elapsed = time.monotonic() - authorized_at
            if elapsed < 0 or elapsed >= authorization['expiry']:
                fail('INIT_COPY_AUTHORITY_EXPIRED')
            save_completion(state, reply['result'])
            return 0
        if state.exists() or state.is_symlink():
            fail('INIT_STATE_EXISTS')
        verifier.authorize_action(identity, doc['staging_path'], scope='DISPATCH')
        opened = make_open(enrollment, manifest, identity, request)
        written = []
        acknowledged = []
        def writer(message):
            if message['request'] != request:
                fail('INIT_LIVE_REQUEST')
            answer = write_metadata(state, manifest, request)
            written.append(answer)
            return answer
        def completion(message):
            proof = complete_proof(request, message['result'])
            save_completion(state, proof)
            acknowledged.append(proof)
        reply = transport.invoke_operation(ssh_options, opened, source, verifier,
                                           init_writer=writer, completion_writer=completion)
        if reply['status'] != 0 or len(written) != 1 or len(acknowledged) != 1:
            fail('INIT_COMPLETION_UNCONFIRMED')
        require_completion(state)
        return 0
    completion = require_completion(state)
    identity, arguments, environment, offset = read_local_stream(args.stream_file, source)
    if (identity['kind'] != 'STAGE' or identity['run_id'] != doc['run_id']
            or identity['release_sha'] != doc['release_sha']
            or identity['script_sha256'] != doc['script_sha256']):
        fail('LOCAL_OPERATION_BINDING')
    request = dict(args=arguments, environment=environment)
    payload = None
    payload_metadata = None
    if offset is not None:
        # The transport receives an already opened source and bounded slice, not a command/path on S.
        payload = open(args.stream_file, 'rb')
        payload.seek(offset)
        size = os.fstat(payload.fileno()).st_size - offset
        hasher = hashlib.sha256()
        for chunk in iter(lambda: payload.read(1024*1024), b''):
            hasher.update(chunk)
        payload.seek(offset)
        payload_metadata = dict(size=size, sha256=hasher.hexdigest())
    try:
        opened = make_open(enrollment, manifest, identity, request, arguments=arguments,
                           environment=environment, completion=completion, payload=payload_metadata)
        with tempfile.TemporaryFile() as private_output:
            reply = transport.invoke_operation(ssh_options, opened, source, verifier,
                payload=None if payload is None else payload.fileno(), output_sink=private_output.write)
            # Preserve the native ACK/log identity in its existing restricted capture.
            if reply['status'] == 0:
                private_output.seek(0)
                for block in iter(lambda: private_output.read(65536), b''):
                    sys.stdout.buffer.write(block)
        return reply['status']
    finally:
        if payload is not None:
            payload.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('prepare-init', 'init', 'operation', 'complete-init-copy', 'verify-completion'):
        entry = sub.add_parser(command)
        entry.add_argument('--state-dir', required=True)
        if command not in ('prepare-init', 'verify-completion'):
            entry.add_argument('--policy-b-anchor', required=True)
            entry.add_argument('--policy-b-transport', required=True)
        if command in ('prepare-init', 'init'):
            entry.add_argument('--proposal-file', required=command == 'prepare-init')
        if command == 'init':
            entry.add_argument('--proposal-sha256')
        if command == 'operation':
            entry.add_argument('--stream-file', required=True)
        if command == 'complete-init-copy':
            entry.add_argument('--authorization', required=True)
    for command in ('prepare-target-binding', 'bind-legacy-target', 'copy-target-binding-completion'):
        entry = sub.add_parser(command)
        entry.add_argument('--proposal-file', required=True)
        if command == 'prepare-target-binding':
            entry.add_argument('--state-dir', required=True)
            entry.add_argument('--init-proposal-file', required=True)
            entry.add_argument('--init-proposal-sha256', required=True)
            entry.add_argument('--inventory-sha256', required=True)
            entry.add_argument('--target-device', type=int, required=True)
            entry.add_argument('--target-inode', type=int, required=True)
            entry.add_argument('--target-uid', type=int, required=True)
            entry.add_argument('--host-fingerprint', required=True)
            entry.add_argument('--expires-at', required=True)
        else:
            entry.add_argument('--proposal-sha256', required=True)
            entry.add_argument('--policy-b-anchor', required=True)
            entry.add_argument('--policy-b-transport', required=True)
            entry.add_argument('--remote', required=True)
            entry.add_argument('--completion-file', required=True)
        if command == 'copy-target-binding-completion':
            entry.add_argument('--authorization', required=True)
    try:
        return run_client(parser.parse_args())
    except (Exception, KeyboardInterrupt):
        print('POLICY_B_CLIENT_REFUSED outcome_unconfirmed=true retry_allowed=false', file=sys.stderr)
        return 75


if __name__ == '__main__':
    raise SystemExit(main())
