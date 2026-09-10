#!/usr/bin/env python3
"""Fresh read-only observations for intact successful V126 operation records.

The binding coordinator holds the permanent target lock throughout collect(). This
module never dispatches an action or writes a stage proof. Its temporary source and
observer files are private; retained operation records are opened read-only.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import stat
import subprocess
import tempfile
import time


ACTIONS = frozenset({
    'baseline', 'backup-rehearsal', 'caddy-activate', 'public-drain-on',
    'stop-backend', 'zero-writer', 'final-v125-preflight', 'transform-maintenance',
    'image-prepare', 'image-load', 'start-v126', 'schema-runtime-gate',
    'open-manual-smoke', 'record-manual-smoke', 'restore-caddy', 'final-public-gates',
    'recover-pre-v126', 'recover-post-v126-stop', 'verify-full-dr',
    'preflight-upload', 'image-upload',
})
IMPLEMENTED = frozenset({
    'start-v126', 'schema-runtime-gate', 'open-manual-smoke', 'record-manual-smoke',
    'restore-caddy', 'final-public-gates', 'stop-backend', 'zero-writer',
    'recover-pre-v126', 'recover-post-v126-stop', 'transform-maintenance',
    'caddy-activate', 'public-drain-on', 'image-load',
    'baseline', 'backup-rehearsal', 'final-v125-preflight', 'verify-full-dr',
})
UNSUPPORTED_REASONS = {
    'image-prepare': 'prepare_alone_cannot_complete_ordered_transfer_group',
    'preflight-upload': 'upload_alone_cannot_complete_ordered_preflight_group',
    'image-upload': 'upload_alone_cannot_complete_ordered_transfer_group',
}
MAX_RECORD = 16 * 1024 * 1024
DEADLINE_SECONDS = 600
PROOF_FILENAMES = {'pre-drain-backup-proof': 'pre-drain-backup-rehearsed',
                   'quiesced-backup-proof': 'quiesced-backup-rehearsed'}


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def require(condition, reason):
    if not condition:
        raise ValueError('INSUFFICIENT_EVIDENCE:' + reason)


def regular(path, mode):
    path = Path(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        metadata = os.fstat(fd)
        require(stat.S_ISREG(metadata.st_mode) and metadata.st_uid == os.geteuid() and
                stat.S_IMODE(metadata.st_mode) == mode and metadata.st_nlink == 1,
                'protected_file_metadata')
        require(metadata.st_size <= MAX_RECORD, 'protected_file_bound')
        raw = bytearray()
        while len(raw) <= MAX_RECORD:
            block = os.read(fd, min(65536, MAX_RECORD + 1 - len(raw)))
            if not block:
                break
            raw.extend(block)
        require(len(raw) == metadata.st_size, 'protected_file_changed')
        after = os.fstat(fd)
        require((metadata.st_size, metadata.st_mtime_ns, metadata.st_ctime_ns) ==
                (after.st_size, after.st_mtime_ns, after.st_ctime_ns), 'protected_file_changed')
        return bytes(raw)
    finally:
        os.close(fd)


def directory(path):
    metadata = Path(path).lstat()
    require(stat.S_ISDIR(metadata.st_mode) and metadata.st_uid == os.geteuid() and
            stat.S_IMODE(metadata.st_mode) == 0o700, 'protected_directory_metadata')


def document(path):
    raw = regular(path, 0o400)
    value = json.loads(raw)
    require(raw == canonical(value), 'original_record_not_canonical')
    return value, digest(raw)


def validate_request(target, identity, request):
    require(set(request) == {'format_version', 'identity', 'target_sha256', 'args', 'environment'} and
            type(request['format_version']) is int and request['format_version'] == 1 and
            request['identity'] == identity and request['target_sha256'] == digest(str(target).encode()),
            'original_request_binding')
    require(isinstance(request['args'], list) and 3 <= len(request['args']) <= 32 and
            all(isinstance(arg, str) and not any(c in arg for c in '\x00\n\r') for arg in request['args']) and
            request['args'][:3] == [str(target), identity['run_id'], identity['release_sha']],
            'original_request_args')
    require(isinstance(request['environment'], dict) and all(
        re.fullmatch(r'V126_INTERNAL_REMOTE_[A-Z0-9_]+', key) and isinstance(value, str) and
        not any(c in value for c in '\x00\n\r') for key, value in request['environment'].items()),
        'original_request_environment')


def operation(root, operation_id, target):
    require(re.fullmatch('[0-9a-f]{64}', operation_id), 'operation_id')
    start, start_sha = document(root / (operation_id + '.start.json'))
    require(set(start) == {'identity', 'operation_id', 'started_at', 'boot_id'} and
            start['operation_id'] == operation_id and digest(canonical(start['identity'])) == operation_id,
            'original_start_binding')
    identity = start['identity']
    require(set(identity) == {'run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name', 'action'} and
            re.fullmatch('[a-z0-9][a-z0-9._-]{0,79}', identity['run_id']) and
            re.fullmatch('[0-9a-f]{40}', identity['release_sha']) and
            all(re.fullmatch('[0-9a-f]{64}', identity[key]) for key in ('script_sha256', 'intent_sha256')),
            'original_identity_schema')
    request, request_sha = document(root / (operation_id + '.request.json'))
    validate_request(target, identity, request)
    result, result_sha = document(root / (operation_id + '.result.json'))
    require(set(result) == {'identity', 'operation_id', 'exit', 'outcome', 'children', 'log_sha256', 'completed_at'} and
            result['identity'] == identity and result['operation_id'] == operation_id and
            type(result['exit']) is int and result['exit'] == 0 and result['outcome'] == 'SUCCEEDED' and
            result['children'] == 'REAPED', 'UNKNOWN_original_result_or_children')
    log = regular(root / (operation_id + '.log'), 0o400)
    require(digest(log) == result['log_sha256'], 'original_log_digest')
    files = {operation_id + suffix: value for suffix, value in (
        ('.start.json', start_sha), ('.request.json', request_sha),
        ('.result.json', result_sha), ('.log', digest(log)))}
    artifacts = {}
    for row in log.splitlines():
        if row.startswith(b'ARTIFACT'):
            match = re.fullmatch(rb'ARTIFACT\t([a-z0-9][a-z0-9._-]{0,63})\t([0-9a-f]{64})', row)
            require(match is not None, 'original_artifact_format')
            name, value = (part.decode() for part in match.groups())
            require(name not in artifacts, 'duplicate_original_artifact')
            artifacts[name] = value
    return identity, request, files, artifacts


class Evidence:
    def __init__(self, target, identity, request, operations):
        self.target, self.identity = Path(target), identity
        self.root = self.target / '.v126-target-operations'
        self.run_root = self.target / '.v126-runs' / identity['run_id']
        directory(self.root)
        directory(self.target / '.v126-runs')
        directory(self.run_root)
        require(isinstance(operations, list) and operations, 'original_operation_group_empty')
        self.artifacts, self.selected, self.proofs, self.original_requests = {}, {}, {}, []
        self.derived_environment = {}
        selected_ids = []
        for selected in operations:
            require(set(selected) == {'operation_id', 'files'}, 'selected_operation_schema')
            original, original_request, files, artifacts = operation(self.root, selected['operation_id'], self.target)
            require(all(original[key] == identity[key] for key in
                        ('run_id', 'release_sha', 'script_sha256', 'intent_sha256', 'kind', 'name')) and
                    files == selected['files'], 'selected_operation_binding')
            selected_ids.append(selected['operation_id'])
            for name, value in artifacts.items():
                require(name not in self.selected, 'duplicate_selected_artifact')
                self.selected[name] = value
        require(len(selected_ids) == len(set(selected_ids)) and original == identity and
                original_request == request, 'last_original_operation_binding')
        # Predecessor IDs/proofs must also originate in intact successful records;
        # a writable proof plus a matching writable sidecar is insufficient.
        for path in sorted(self.root.glob('*.start.json')):
            candidate, _ = document(path)
            candidate_identity = candidate.get('identity', {})
            if any(candidate_identity.get(key) != identity[key] for key in ('run_id', 'release_sha', 'script_sha256')):
                continue
            original, original_request, _, artifacts = operation(self.root, path.name.removesuffix('.start.json'), self.target)
            self.original_requests.append((original, original_request))
            for name, value in artifacts.items():
                require(name not in self.artifacts, 'ambiguous_original_artifact')
                self.artifacts[name] = value

    def derive(self, key, artifact):
        require(artifact in self.selected, 'missing_original_artifact_' + artifact)
        self.derived_environment[key] = self.selected[artifact]

    def prior_request(self, action):
        requests = [request for identity, request in self.original_requests if identity['action'] == action]
        require(len(requests) == 1, 'ambiguous_prior_request_' + action)
        return requests[0]

    def proof(self, name, *, selected=False):
        expected = (self.selected if selected else self.artifacts).get(name)
        require(expected is not None, 'missing_original_artifact_' + name)
        path = self.run_root / (PROOF_FILENAMES.get(name, name) + '.proof')
        raw = regular(path, 0o600)
        require(digest(raw) == expected and regular(Path(str(path) + '.sha256'), 0o600) ==
                (expected + '\n').encode(), 'original_proof_digest_' + name)
        fields = {}
        for row in raw.decode('utf-8').splitlines():
            require('=' in row, 'proof_row_' + name)
            key, value = row.split('=', 1)
            require(key not in fields and re.fullmatch('[a-z0-9_]+', key), 'proof_key_' + name)
            fields[key] = value
        require(fields.get('run_id') == self.identity['run_id'] and
                fields.get('release_sha') == self.identity['release_sha'], 'proof_identity_' + name)
        self.proofs[name] = expected
        return fields


def quoted_call(name, *args):
    require(re.fullmatch(r'[a-z][a-z0-9_]*', name), 'checker_function')
    return name + ' ' + ' '.join(shlex.quote(str(arg)) for arg in args)


def observer_plan(evidence, request):
    """Return only checked read-only calls; no original action implementation."""
    action = evidence.identity['action']
    require(action in IMPLEMENTED, 'unsupported_action_' + action + '_' + UNSUPPORTED_REASONS.get(action, 'unknown'))
    args = request['args']
    target, run, release = args[:3]
    expected_id = None
    require(len(args) >= (3 if action == 'caddy-activate' else 4), 'action_args_' + action)
    init_args = args[:4]
    if action == 'baseline':
        require(len(args) == 9, 'baseline_args')
        for suffix, artifact in [('DATABASE_URL', 'database-url-binding'), ('MAINTENANCE_IDENTITIES', 'maintenance-identities'),
                                 ('COMPOSE_SOURCE', 'remote-compose-source'), ('MAINTENANCE_CHECK_SOURCE', 'remote-maintenance-check-source'),
                                 ('ADMISSION_SOURCE', 'remote-admission-source'), ('CADDY', 'baseline-caddy'), ('ENV', 'baseline-env')]:
            evidence.derive('V126_INTERNAL_REMOTE_BASELINE_' + suffix + '_SHA256', artifact)
        evidence.derive('V126_INTERNAL_REMOTE_DATABASE_TARGET_IDENTITY_SHA256', 'database-target-identity')
    elif action == 'backup-rehearsal':
        require(len(args) == 5 and args[3] in ('pre-drain', 'quiesced'), 'backup_args')
        init_args = args[:3] + [args[4]]
    if action == 'caddy-activate':
        require(len(args) == 3, 'caddy_args')
        baseline = evidence.prior_request('baseline')
        require(len(baseline['args']) >= 4, 'baseline_image_argument')
        init_args = args + [baseline['args'][3]]
        for suffix, artifact in [('ORIGINAL', 'caddy-original'), ('CANDIDATE', 'caddy-candidate'),
                                 ('DIFF', 'caddy-diff'), ('ACTIVATION', 'caddy-activation')]:
            evidence.derive('V126_INTERNAL_REMOTE_CADDY_' + suffix + '_SHA256', artifact)
    calls = [('initialize', quoted_call('remote_initialize_compose', *init_args))]
    image = args[4] if len(args) >= 5 else None
    if action in {'start-v126', 'schema-runtime-gate', 'open-manual-smoke', 'record-manual-smoke',
                  'restore-caddy', 'final-public-gates'}:
        require(len(args) == (6 if action in {'start-v126', 'record-manual-smoke'} else 5) and
                re.fullmatch('sha256:[0-9a-f]{64}', image or '') and
                request['environment'].get('V126_INTERNAL_REMOTE_V126_IMAGE_ID') == image,
                'runtime_image_args')
        phase = args[5] if action == 'start-v126' else (
            'final' if action in {'restore-caddy', 'final-public-gates'} else 'first')
        require(phase in ('first', 'final'), 'start_phase')
        started = 'v126-backend-' + phase + '-started'
        original = evidence.proof(started, selected=(action == 'start-v126'))
        expected_id = original.get('backend_container_id')
        require(re.fullmatch('[0-9a-f]{64}', expected_id or '') and original.get('image_id') == image and
                original.get('image_tag') == args[3] and original.get('phase') == phase and
                original.get('start_command_count') == '1' and original.get('restart_policy') == 'no' and
                original.get('restart_count') == '0' and original.get('result') == 'PASS',
                'original_exact_start_resource')
        calls.append(('exact_backend', quoted_call('reconcile_exact_backend', expected_id, image)))
        calls.append(('readiness', quoted_call('remote_wait_backend_ready', expected_id, image, release,
                                               phase) + ' "${REMOTE_BOUND_ENV_SHA256}"'))
        mode = 'OFF' if phase == 'final' else 'V126_SMOKE'
        live = action in {'open-manual-smoke', 'record-manual-smoke', 'restore-caddy', 'final-public-gates'}
        calls.append(('runtime', quoted_call('remote_assert_runtime', target, release, image, mode, str(not live).lower())))
        if action != 'start-v126':
            own_name = {'schema-runtime-gate': 'v126-schema-runtime', 'open-manual-smoke': 'manual-smoke-window',
                        'record-manual-smoke': 'manual-smoke-passed', 'restore-caddy': 'ordinary-caddy-restored',
                        'final-public-gates': 'final-public-gates'}[action]
            own = evidence.proof(own_name, selected=True)
            require(own.get('result') == ('AUTHORIZED' if action == 'open-manual-smoke' else 'PASS'), 'original_action_result')
            if action == 'record-manual-smoke':
                require(re.fullmatch('[0-9a-f]{64}', args[5]) and own.get('evidence_sha256') == args[5], 'manual_original_binding')
        if phase == 'final' and live:
            restored = evidence.proof('ordinary-caddy-restored', selected=(action == 'restore-caddy'))
            require(restored.get('original_sha256') == request['environment'].get('V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256'),
                    'original_caddy_binding')
            calls.append(('ordinary_caddy', quoted_call('reconcile_ordinary_caddy', release, run)))
        else:
            calls.append(('candidate_caddy', quoted_call('remote_assert_caddy_candidate_active', release, run)))
        if live:
            calls.extend([('drain_absent', 'sudo test ! -e /etc/caddy/v126-drain.enabled\nsudo test ! -L /etc/caddy/v126-drain.enabled'),
                          ('public_live', 'remote_assert_public_live')])
            if phase == 'first':
                calls.append(('protected_denial', 'remote_assert_protected_unauthenticated_503'))
        else:
            calls.append(('public_drain', 'remote_assert_public_drain'))
    elif action == 'stop-backend':
        require(len(args) == 6 and args[4] in ('v125', 'v126-off-transition') and
                re.fullmatch('sha256:[0-9a-f]{64}', args[5]), 'stop_args')
        own = evidence.proof(args[4] + '-backend-stopped', selected=True)
        require(own.get('phase') == args[4] and own.get('backend_running_count') == '0' and own.get('result') == 'PASS',
                'original_stop_proof')
        calls.extend([('candidate_caddy', quoted_call('remote_assert_caddy_candidate_active', release, run)),
                      ('public_drain', 'remote_assert_public_drain'), ('stopped_backend', 'reconcile_stopped_backend')])
    elif action == 'zero-writer':
        require(len(args) == 4, 'zero_writer_args')
        own = evidence.proof('zero-writer-v125', selected=True)
        require(own.get('flyway') == '125:0:0' and own.get('result') == 'PASS', 'original_zero_writer_proof')
        calls.extend([('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', '125:0:0'))])
    elif action == 'recover-pre-v126':
        require(len(args) == 5, 'recovery_args')
        own = evidence.proof('recovery-pre-v126', selected=True)
        expected_id = own.get('backend_container_id')
        require(re.fullmatch('[0-9a-f]{64}', expected_id or '') and
                re.fullmatch('sha256:[0-9a-f]{64}', own.get('v125_image_id', '')) and
                own.get('result') == 'PRE_V126_ROLLBACK_COMPLETE' and own.get('flyway') == '125:0:0:0' and
                own.get('start_command_count') == '1' and own.get('restart_policy') == 'no' and
                own.get('restart_count') == '0', 'original_recovery_resource')
        calls = [('initialize_recovery', quoted_call('remote_initialize_reconciled_recovery',
                  *args[:4], evidence.proofs['recovery-pre-v126'])),
                 ('exact_backend', quoted_call('reconcile_exact_backend', expected_id, own['v125_image_id'])),
                 ('recovery_environment', quoted_call('remote_assert_bound_container_environment', expected_id, 'pre-v126')),
                 ('readiness', quoted_call('remote_wait_backend_ready', expected_id, own['v125_image_id'],
                  own.get('v125_source_sha', ''), 'pre-v126', own.get('env_after_sha256', ''))),
                 ('v125_runtime', quoted_call('remote_assert_v125_runtime', target, args[3], 'false')),
                 ('ordinary_caddy', quoted_call('reconcile_ordinary_caddy', release, run)),
                 ('drain_absent', 'sudo test ! -e /etc/caddy/v126-drain.enabled\nsudo test ! -L /etc/caddy/v126-drain.enabled'),
                 ('public_live', 'remote_assert_public_live')]
    elif action == 'recover-post-v126-stop':
        require(len(args) == 5, 'post_stop_args')
        evidence.proof('recovery-post-v126-stop', selected=True)
        calls.extend([('original_stop_contract', quoted_call('remote_verify_post_v126_stop_proof', evidence.run_root,
                      run, release, evidence.proofs['recovery-post-v126-stop'])),
                      ('candidate_caddy', quoted_call('remote_assert_caddy_candidate_active', release, run)),
                      ('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', '126:1:0'))])
    elif action == 'transform-maintenance':
        require(len(args) == 7 and args[5] in ('V126_SMOKE', 'OFF'), 'maintenance_args')
        mode = args[5]
        name = 'maintenance-' + mode.lower()
        own = evidence.proof(name, selected=True)
        require(own.get('mode') == mode and own.get('result') == 'PASS', 'original_maintenance_result')
        evidence.derive('V126_INTERNAL_REMOTE_MAINTENANCE_' + ('SMOKE' if mode == 'V126_SMOKE' else 'OFF') + '_SHA256', name)
        calls.extend([('maintenance_proof', quoted_call('remote_verify_maintenance_env_binding', target,
                      evidence.run_root, run, release, mode, evidence.proofs[name])),
                      ('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', '125:0:0' if mode == 'V126_SMOKE' else '126:1:0')),
                      ('temporary_cleanup', quoted_call('reconcile_maintenance_cleanup', evidence.run_root, mode))])
    elif action == 'caddy-activate':
        calls.extend([('candidate_caddy', quoted_call('remote_assert_caddy_candidate_active', release, run)),
                      ('drain_absent', 'sudo test ! -e /etc/caddy/v126-drain.enabled\nsudo test ! -L /etc/caddy/v126-drain.enabled')])
    elif action == 'public-drain-on':
        require(len(args) == 6 and args[4] in ('initial', 'reactivated') and
                re.fullmatch('sha256:[0-9a-f]{64}', args[5]), 'drain_args')
        own = evidence.proof('public-drain-' + ('active' if args[4] == 'initial' else 'reactivated'), selected=True)
        require(own.get('phase') == args[4] and own.get('result') == 'PASS', 'original_drain_result')
        calls.append(('candidate_caddy', quoted_call('remote_assert_caddy_candidate_active', release, run)))
        if args[4] == 'initial':
            calls.append(('v125_runtime', quoted_call('remote_assert_v125_runtime', target, args[3])))
        else:
            started = evidence.proof('v126-backend-first-started')
            expected_id = started.get('backend_container_id')
            require(re.fullmatch('[0-9a-f]{64}', expected_id or '') and started.get('image_id') == args[5], 'drain_backend_binding')
            evidence.proof('manual-smoke-passed')
            calls.extend([('exact_backend', quoted_call('reconcile_exact_backend', expected_id, args[5])),
                          ('runtime', quoted_call('remote_assert_runtime', target, release, args[5], 'V126_SMOKE', 'true'))])
        calls.append(('public_drain', 'remote_assert_public_drain'))
    elif action == 'image-load':
        require(len(args) == 6 and re.fullmatch('sha256:[0-9a-f]{64}', args[4]) and
                re.fullmatch('[0-9a-f]{64}', args[5]), 'image_args')
        own = evidence.proof('v126-image-transferred', selected=True)
        evidence.proof('v126-image-transfer-ready', selected=True)
        require(own.get('archive_sha256') == args[5] and own.get('remote_image_id') == args[4] and
                own.get('image_tag') == args[3] and own.get('result') == 'PASS' and
                evidence.selected.get('v126-image-archive') == args[5], 'original_image_result')
        calls.extend([('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', '125:0:0')),
                      ('sealed_image', quoted_call('reconcile_loaded_image', evidence.run_root, *args[3:6]))])
    elif action == 'baseline':
        require(all(evidence.selected.get(name) == value for name, value in zip(
                    ('remote-compose-source', 'remote-maintenance-check-source', 'remote-admission-source'), args[6:9])),
                'baseline_original_source_arguments')
        calls.extend([('baseline_paths', quoted_call('reconcile_baseline_paths', evidence.run_root, args[4], args[5])),
                      ('v125_runtime', quoted_call('remote_assert_v125_runtime', target, args[3], 'false')),
                      ('baseline_environment', 'remote_capture_compose_ids running backend\n'
                       'remote_assert_bound_container_environment "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" baseline'),
                      ('baseline_caddy', 'reconcile_baseline_caddy'),
                      ('public_live', 'remote_assert_public_live')])
    elif action == 'backup-rehearsal':
        phase = args[3]
        own = evidence.proof(phase + '-backup-proof', selected=True)
        require(own.get('phase') == phase and own.get('result') == 'PASS', 'original_backup_result')
        calls += backup_checks(evidence, release, run, phase, own, selected=True)
        if phase == 'quiesced':
            calls.extend([('public_drain', 'remote_assert_public_drain'),
                          ('zero_writer', quoted_call('remote_assert_zero_writer', '125:0:0'))])
    elif action == 'final-v125-preflight':
        require(len(args) == 8 and re.fullmatch('[0-9a-f]{64}', args[6]), 'preflight_args')
        own = evidence.proof('final-v125-preflight', selected=True)
        require(own.get('script_sha256') == args[6] and own.get('preflight_outcome') == 'SAFE' and
                own.get('unsafe_count') == '0' and own.get('credentials_cleanup') == 'COMPLETE' and
                own.get('result') == 'PASS' and own.get('flyway') == '125:0:0',
                'historical_preflight_structured_witness_missing_or_invalid')
        backup = evidence.proof('quiesced-backup-proof')
        calls += backup_checks(evidence, release, run, 'quiesced', backup)
        calls.extend([('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', '125:0:0')),
                      ('preflight_retained_source_and_cleanup', quoted_call('reconcile_preflight_evidence', evidence.run_root, args[6])),
                      ('preflight_current_safe', quoted_call('reconcile_preflight_safe', evidence.run_root, args[4], args[6], args[7]))])
    elif action == 'verify-full-dr':
        require(len(args) == 9 and args[4] in ('pre-drain', 'quiesced') and
                all(re.fullmatch('[0-9a-f]{64}', value) for value in args[5:8]), 'full_dr_args')
        own = evidence.proof('recovery-full-dr-prerequisites', selected=True)
        phase = args[4]
        backup = evidence.proof(phase + '-backup-proof')
        require(backup.get('dump_sha256') == args[5] and backup.get('inventory_sha256') == args[6], 'full_dr_original_archive')
        predecessor = request['environment'].get('V126_INTERNAL_REMOTE_PREDECESSOR_STAGE')
        receipt_sha = request['environment'].get('V126_INTERNAL_REMOTE_PREDECESSOR_HASH') if predecessor == 'RECOVERY_POST_V126_STOP' else 'NONE'
        if predecessor == 'RECOVERY_POST_V126_STOP':
            evidence.proof('recovery-post-v126-stop')
            require(evidence.proofs['recovery-post-v126-stop'] == args[8], 'full_dr_original_stop')
            calls.append(('original_stop_contract', quoted_call('remote_verify_post_v126_stop_proof', evidence.run_root, run, release, args[8])))
        else:
            require(args[8] == 'NONE', 'full_dr_unexpected_stop')
        calls += backup_checks(evidence, release, run, phase, backup)
        calls.extend([('full_dr_original_contract', quoted_call('remote_verify_full_dr_proof',
                      evidence.run_root / 'recovery-full-dr-prerequisites.proof', run, release, phase,
                      *args[5:8], receipt_sha, args[8])),
                      ('public_drain', 'remote_assert_public_drain'),
                      ('zero_writer', quoted_call('remote_assert_zero_writer', 'ANY'))])
    return calls, expected_id


def backup_checks(evidence, release, run, phase, proof, *, selected=False):
    artifacts = evidence.selected if selected else evidence.artifacts
    for field, suffix in [('dump_sha256', 'dump'), ('inventory_sha256', 'inventory'), ('rehearsal_sha256', 'rehearsal')]:
        require(re.fullmatch('[0-9a-f]{64}', proof.get(field, '')) and
                proof[field] == artifacts.get(phase + '-backup-' + suffix), 'original_backup_' + field)
    cid, volume, owner = (proof.get(key, '') for key in ('rehearsal_container', 'rehearsal_volume', 'rehearsal_owner'))
    require(re.fullmatch('[0-9a-f]{64}', cid) and re.fullmatch('hookah-v126-[a-z0-9-]+', volume) and
            re.fullmatch('v126:' + release + r':[a-z0-9-]+:' + phase + r':[0-9]+', owner) and
            proof.get('rehearsal_cleanup') == 'COMPLETE', 'historical_backup_resource_witness_missing_or_invalid')
    globals_sha = artifacts.get('pre-drain-globals', 'NONE') if phase == 'pre-drain' else 'NONE'
    require(phase != 'pre-drain' or re.fullmatch('[0-9a-f]{64}', globals_sha), 'original_globals_binding')
    return [('backup_archive_' + phase, quoted_call('reconcile_backup_archive', release, run, phase,
             proof['dump_sha256'], proof['inventory_sha256'], proof['rehearsal_sha256'], globals_sha)),
            ('backup_resources_' + phase, quoted_call('reconcile_rehearsal_absent', cid, volume, owner))]


READ_ONLY_HELPERS = r'''
reconcile_exact_backend() {
  local expected="$1" image="$2" observed
  remote_capture_compose_ids running backend || return 4
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || return 4
  [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" == "${expected}" ]] || return 4
  remote_capture_compose_ids all backend || return 4
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || return 4
  [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" == "${expected}" ]] || return 4
  observed="$(docker inspect --format '{{.Image}}:{{.HostConfig.RestartPolicy.Name}}:{{.RestartCount}}' "${expected}")" || return 4
  [[ "${observed}" == "${image}:no:0" ]] || return 4
}
reconcile_stopped_backend() {
  remote_capture_compose_ids running backend || return 4
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 0 )) || return 4
  remote_require_global_image_count "${V125_IMAGE_ID}" 0 || return 4
  remote_require_global_image_count "${V126_INTERNAL_REMOTE_V126_IMAGE_ID}" 0 || return 4
  remote_compose exec -T postgres sh -c ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; PGCONNECT_TIMEOUT=5 pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"' >/dev/null
}
reconcile_ordinary_caddy() {
  local release="$1" run="$2" root observed
  local expected="${V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256}"
  if [[ "${expected}" == NONE ]]; then
    remote_verify_partial_caddy_evidence "${release}" "${run}" || return 4
    expected="${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256}"
  else
    remote_verify_caddy_receipt_evidence "${release}" "${run}" || return 4
  fi
  root="$(remote_caddy_evidence_root "${release}" "${run}")" || return 4
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644 || return 4
  observed="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" || return 4
  [[ "${observed}" == "${expected}" ]] || return 4
  remote_assert_caddy_service_active || return 4
  remote_assert_caddy_config_active "${root}/Caddyfile.original" || return 4
}
reconcile_maintenance_cleanup() {
  local root="$1" mode="$2" path
  for path in "${root}/env.${mode}.candidate" "${root}/env.before-${mode}" "${root}/.env.next"; do
    [[ ! -e "${path}" && ! -L "${path}" ]] || return 4
  done
}
reconcile_loaded_image() (
  local root="$1" tag="$2" image="$3" archive_sha="$4" observed
  v126_reconcile_image_copy='' v126_reconcile_image_snapshot=''
  trap 'status=$?; trap - EXIT; if [[ -n "${v126_reconcile_image_copy:-}" ]]; then rm -f -- "${v126_reconcile_image_copy}" || status=4; fi; if [[ -n "${v126_reconcile_image_snapshot:-}" ]]; then rm -f -- "${v126_reconcile_image_snapshot}" || status=4; fi; exit "${status}"' EXIT
  remote_require_operator_file "${root}/v126-image.tar" 400 || exit 4
  [[ ! -e "${root}/v126-image.tar.partial" && ! -L "${root}/v126-image.tar.partial" ]] || exit 4
  [[ "$(remote_hash_file "${root}/v126-image.tar")" == "${archive_sha}" ]] || exit 4
  # The real saved-image verifier accepts only an unlinked private snapshot.
  # Preserve the retained sealed archive and use the source's actual snapshotter.
  v126_reconcile_image_copy="$(mktemp "${V126_RECONCILE_PRIVATE}/image-source.XXXXXX")" || exit 4
  v126_reconcile_image_snapshot="$(mktemp "${V126_RECONCILE_PRIVATE}/image-snapshot.XXXXXX")" || exit 4
  cat "${root}/v126-image.tar" > "${v126_reconcile_image_copy}" || exit 4
  chmod 0600 "${v126_reconcile_image_copy}" "${v126_reconcile_image_snapshot}" || exit 4
  snapshot_image_archive "${v126_reconcile_image_copy}" "${v126_reconcile_image_snapshot}" || exit 4
  exec 9<"${v126_reconcile_image_snapshot}" || exit 4
  rm -f -- "${v126_reconcile_image_snapshot}" "${v126_reconcile_image_copy}" || { exec 9<&-; exit 4; }
  observed="$(verify_saved_image_archive_fd 9 "${tag}" "${image}")" || { exec 9<&-; exit 4; }
  exec 9<&-
  [[ "${observed}" == "${archive_sha}" ]] || exit 4
  remote_require_operator_file "${root}/v126-image.tar" 400 || exit 4
  [[ "$(remote_hash_file "${root}/v126-image.tar")" == "${archive_sha}" ]] || exit 4
  observed="$(docker image inspect --format '{{.Id}}' "${tag}")" || exit 4
  [[ "${observed}" == "${image}" && "${image}" == "${V126_INTERNAL_REMOTE_V126_IMAGE_ID}" ]] || exit 4
  remote_assert_compose_backend_image "${tag}" || exit 4
)
reconcile_baseline_paths() {
  python3 - "$1/baseline-authority.proof" "$2" "$3" <<'PY'
from pathlib import Path
import sys
fields = dict(row.split('=', 1) for row in Path(sys.argv[1]).read_text().splitlines())
if fields.get('database_url_path') != sys.argv[2] or fields.get('maintenance_identities_path') != sys.argv[3]:
    raise SystemExit('original baseline path differs')
PY
}
reconcile_baseline_caddy() {
  local observed
  remote_sudo_require_root_file /etc/caddy/Caddyfile 644 || return 4
  observed="$(sudo sha256sum /etc/caddy/Caddyfile | awk '{print $1}')" || return 4
  [[ "${observed}" == "${V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256}" ]] || return 4
  sudo test ! -e /etc/caddy/v126-drain.enabled || return 4
  sudo test ! -L /etc/caddy/v126-drain.enabled || return 4
  cutover_bounded_command 15 sudo caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null || return 4
  remote_assert_caddy_service_active || return 4
  remote_assert_caddy_config_active /etc/caddy/Caddyfile || return 4
}
reconcile_rehearsal_absent() {
  local cid="$1" volume="$2" owner="$3" observed
  observed="$(docker container ls --all --no-trunc --filter "id=${cid}" --format '{{.ID}}')" || return 4
  [[ -z "${observed}" ]] || return 4
  observed="$(docker container ls --all --no-trunc --filter "label=hookah.v126.rehearsal-owner=${owner}" --format '{{.ID}}')" || return 4
  [[ -z "${observed}" ]] || return 4
  observed="$(docker volume ls --filter "name=${volume}" --format '{{.Name}}')" || return 4
  [[ -z "${observed}" ]] || return 4
  observed="$(docker volume ls --filter "label=hookah.v126.rehearsal-owner=${owner}" --format '{{.Name}}')" || return 4
  [[ -z "${observed}" ]] || return 4
}
reconcile_backup_archive() (
  local release="$1" run="$2" phase="$3" dump_sha="$4" inventory_sha="$5" metadata_sha="$6" globals_sha="$7"
  local root dump inventory metadata temporary observed code
  root="$(remote_backup_root "${release}" "${run}")" || exit 4
  [[ -d "${root}" && ! -L "${root}" ]] || exit 4
  [[ "$(stat -c '%a:%U:%G' "${root}")" == "700:$(id -un):$(id -gn)" ]] || exit 4
  dump="${root}/${phase}.dump"
  inventory="${dump}.pg_restore.list"
  metadata="${dump}.rehearsal.txt"
  remote_require_operator_file "${dump}" 600 || exit 4
  remote_require_operator_file "${inventory}" 600 || exit 4
  remote_require_operator_file "${metadata}" 600 || exit 4
  remote_require_operator_file "${dump}.sha256" 600 || exit 4
  [[ "$(remote_hash_file "${dump}")" == "${dump_sha}" ]] || exit 4
  [[ "$(remote_hash_file "${inventory}")" == "${inventory_sha}" ]] || exit 4
  [[ "$(remote_hash_file "${metadata}")" == "${metadata_sha}" ]] || exit 4
  [[ "$(cat "${dump}.sha256")" == "${dump_sha}  ${dump}" ]] || exit 4
  sha256sum -c "${dump}.sha256" >/dev/null || exit 4
  if [[ "${globals_sha}" != NONE ]]; then
    remote_require_operator_file "${root}/globals.sql" 600 || exit 4
    remote_require_operator_file "${root}/globals.sql.sha256" 600 || exit 4
    [[ "$(remote_hash_file "${root}/globals.sql")" == "${globals_sha}" ]] || exit 4
    [[ "$(cat "${root}/globals.sql.sha256")" == "${globals_sha}  ${root}/globals.sql" ]] || exit 4
    sha256sum -c "${root}/globals.sql.sha256" >/dev/null || exit 4
  fi
  remote_capture_compose_ids running postgres || exit 4
  (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || exit 4
  observed="$(docker inspect --format '{{.Image}}' "${REMOTE_CAPTURED_CONTAINER_IDS[0]}")" || exit 4
  python3 - "${metadata}" "${release}" "${run}" "${phase}" "${observed}" <<'PY'
from pathlib import Path
import re
import sys
path, release, run, phase, image = sys.argv[1:]
fields = {}
for row in Path(path).read_text().splitlines():
    key, value = row.split('=', 1)
    if key in fields: raise SystemExit('duplicate rehearsal metadata')
    fields[key] = value
for key, expected in dict(run_id=run, release_sha=release, phase=phase, source_image_id=image,
                          restored_flyway='125:0:0', rehearsal='PASS').items():
    if fields.get(key) != expected: raise SystemExit('original rehearsal metadata differs')
if not re.fullmatch(r'17[0-9]{4}', fields.get('source_version', '')):
    raise SystemExit('original rehearsal is not PostgreSQL17')
PY
  [[ "$?" == 0 ]] || exit 4
  temporary="${V126_RECONCILE_PRIVATE}/toc-${phase}"
  [[ ! -e "${temporary}" && ! -L "${temporary}" ]] || exit 4
  (set -o noclobber; umask 077; : > "${temporary}") || exit 4
  chmod 0600 "${temporary}" || exit 4
  remote_compose exec -T postgres sh -c ': "${POSTGRES_USER:?}"; pg_restore --list' < "${dump}" > "${temporary}" || exit 4
  code="$(remote_database_evidence_python)" || exit 4
  python3 -c "${code}" toc "${dump}" "${dump_sha}" "${inventory}" "${temporary}" || exit 4
  [[ "$(remote_hash_file "${inventory}")" == "${inventory_sha}" ]] || exit 4
  [[ "$(remote_hash_file "${metadata}")" == "${metadata_sha}" ]] || exit 4
)
reconcile_preflight_evidence() {
  local root="$1" expected="$2" path
  remote_require_operator_file "${root}/final-v125-preflight.sh" 500 || return 4
  [[ "$(remote_hash_file "${root}/final-v125-preflight.sh")" == "${expected}" ]] || return 4
  for path in final-v125-preflight.sh.partial final-v125-preflight.output final-v125-preflight.pg_service.conf final-v125-preflight.pgpass; do
    [[ ! -e "${root}/${path}" && ! -L "${root}/${path}" ]] || return 4
  done
}
reconcile_preflight_safe() {
  local root="$1" uri="$2" script_sha="$3" uri_sha="$4" temporary="${V126_RECONCILE_PRIVATE}"
  remote_assert_database_target "${uri}" "${uri_sha}" || return 4
  python3 "${temporary}/derive.py" "${uri}" "${uri_sha}" "${temporary}/pg_service.conf" "${temporary}/pgpass" || return 4
  python3 "${temporary}/execute.py" "${root}/final-v125-preflight.sh" "${script_sha}" "${temporary}/preflight.output" \
    "${temporary}/pg_service.conf" "${temporary}/pgpass" "${PATH}" "${HOME}" || return 4
  python3 -c "$(remote_database_evidence_python)" preflight "${temporary}/preflight.output" || return 4
}
'''


def preflight_sources(source_bytes):
    source = source_bytes.decode('utf-8')
    start = source.index('remote_final_v125_preflight() {\n')
    end = source.index('\n# Candidate bytes ', start)
    chunks = re.findall(r"<<'PY'\n(.*?)\nPY\n", source[start:end], re.S)
    derive = [chunk for chunk in chunks if chunk.startswith('# HT12X_LIBPQ_DERIVATION_BEGIN\n')]
    execute = [chunk for chunk in chunks if 'script_path, expected_sha, output_path, service_path, pass_path, path_value, home_value = sys.argv[1:]' in chunk]
    require(len(derive) == len(execute) == 1, 'source_bound_preflight_consumers_unavailable')
    return derive[0] + '\n', execute[0] + '\n'


def run_observer(source_bytes, request, evidence, calls, expected_id):
    env = {key: os.environ[key] for key in ('PATH', 'HOME', 'TMPDIR') if key in os.environ}
    env.update(request['environment'])
    env.update(evidence.derived_environment)
    env.update({'V126_INTERNAL_REMOTE_MODE': 'true',
                'V126_INTERNAL_REMOTE_ENVELOPE_VALIDATED': 'V126_INTERNAL_REMOTE_ENVELOPE_V1'})
    with tempfile.TemporaryDirectory(prefix='v126-reconcile-read-') as temp:
        root = Path(temp)
        env['V126_RECONCILE_PRIVATE'] = str(root)
        env['TMPDIR'] = str(root)
        source, script = root / 'source.sh', root / 'observe.sh'
        source.write_bytes(source_bytes)
        if evidence.identity['action'] == 'final-v125-preflight':
            derive, execute = preflight_sources(source_bytes)
            for name, code in [('derive.py', derive), ('execute.py', execute)]:
                (root / name).write_text(code)
                (root / name).chmod(0o400)
        body = 'set -Eeuo pipefail\nsource "$1"\n' + READ_ONLY_HELPERS
        for name in evidence.proofs:
            body += quoted_call('remote_verify_proof', evidence.run_root / (PROOF_FILENAMES.get(name, name) + '.proof')) + '\n'
        for name, command in calls:
            body += "printf '%s\\n' 'V126_RECONCILE_CHECK=" + name + "'\n" + command + '\n'
            body += 'v126_reconcile_status=$?\n[[ "${v126_reconcile_status}" == 0 ]] || exit 4\n'
        body += '''remote_assert_database_target || exit 4
remote_capture_compose_ids running postgres || exit 4
(( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )) || exit 4
[[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" =~ ^[0-9a-f]{64}$ ]] || exit 4
printf 'V126_RECONCILE_POSTGRES=%s\\n' "${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
printf 'V126_RECONCILE_DATABASE=%s\\n' "${REMOTE_DATABASE_TARGET_IDENTITY_SHA256}"
printf 'V126_RECONCILE_ENVIRONMENT=%s\\n' "${REMOTE_BOUND_ENV_SHA256}"
remote_capture_compose_ids running backend || exit 4
(( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} <= 1 )) || exit 4
if (( ${#REMOTE_CAPTURED_CONTAINER_IDS[@]} == 1 )); then
  [[ "${REMOTE_CAPTURED_CONTAINER_IDS[0]}" =~ ^[0-9a-f]{64}$ ]] || exit 4
  printf 'V126_RECONCILE_BACKEND=%s\\n' "${REMOTE_CAPTURED_CONTAINER_IDS[0]}"
else
  printf '%s\\n' 'V126_RECONCILE_BACKEND=NONE'
fi
printf '%s\\n' 'V126_RECONCILE_COMPLETED=true'
'''
        script.write_text(body)
        source.chmod(0o400)
        script.chmod(0o400)
        started = time.monotonic()
        # The actual source supervisor bounds and reaps the read-only consumer
        # tree. The enclosing binding supervisor continues to hold the target lock.
        result = subprocess.run(['bash', '-c',
                                 'set -Eeuo pipefail; source "$1"; cutover_bounded_command "$3" bash "$2" "$1"',
                                 'v126-reconcile-read-only', str(source), str(script), str(DEADLINE_SECONDS)],
                                cwd=evidence.target, env=env, stdin=subprocess.DEVNULL, capture_output=True)
        elapsed = time.monotonic() - started
        require(result.returncode == 0, 'current_postconditions_exit_' + str(result.returncode) +
                '_stdout_' + digest(result.stdout) + '_stderr_' + digest(result.stderr))
        rows = {}
        observed_checks = []
        for line in result.stdout.splitlines():
            if not line.startswith(b'V126_RECONCILE_'):
                continue
            key, value = line.decode('ascii').split('=', 1)
            if key == 'V126_RECONCILE_CHECK':
                observed_checks.append(value)
            else:
                require(key not in rows, 'duplicate_observation')
                rows[key] = value
        require(observed_checks == [name for name, _ in calls] and set(rows) == {
            'V126_RECONCILE_POSTGRES', 'V126_RECONCILE_DATABASE',
            'V126_RECONCILE_ENVIRONMENT', 'V126_RECONCILE_BACKEND', 'V126_RECONCILE_COMPLETED'} and
            rows['V126_RECONCILE_COMPLETED'] == 'true' and
            all(re.fullmatch('[0-9a-f]{64}', rows[key]) for key in rows if not key.endswith(('COMPLETED', 'BACKEND'))) and
            (rows['V126_RECONCILE_BACKEND'] == 'NONE' or re.fullmatch('[0-9a-f]{64}', rows['V126_RECONCILE_BACKEND'])) and
            (expected_id is None or rows['V126_RECONCILE_BACKEND'] == expected_id),
            'structured_current_postconditions')
        return dict(checks=observed_checks, backend_container_id=(None if rows['V126_RECONCILE_BACKEND'] == 'NONE' else rows['V126_RECONCILE_BACKEND']),
                    postgres_container_id=rows['V126_RECONCILE_POSTGRES'],
                    database_identity_sha256=rows['V126_RECONCILE_DATABASE'],
                    environment_sha256=rows['V126_RECONCILE_ENVIRONMENT'],
                    stdout_sha256=digest(result.stdout), stderr_sha256=digest(result.stderr),
                    observer_sha256=digest(body.encode()), exit=0, elapsed_seconds=round(elapsed, 6),
                    deadline_seconds=DEADLINE_SECONDS)


def collect(target, identity, source_bytes, request, operations):
    """Called only while binding_reconcile holds the canonical persistent lock."""
    target = Path(target)
    require(target.is_absolute() and target.resolve(strict=True) == target, 'target_not_canonical')
    require(isinstance(source_bytes, bytes) and digest(source_bytes) == identity.get('script_sha256'), 'source_binding')
    validate_request(target, identity, request)
    action = identity.get('action')
    require(action in ACTIONS, 'unknown_action')
    require(action in IMPLEMENTED, 'unsupported_action_' + action + '_' + UNSUPPORTED_REASONS.get(action, 'unknown'))
    evidence = Evidence(target, identity, request, operations)
    calls, expected_id = observer_plan(evidence, request)
    observed = run_observer(source_bytes, request, evidence, calls, expected_id)
    # Re-read the bound files after observation; no proof/record may drift while
    # the observer runs, even if its current resource checks happened to succeed.
    after = Evidence(target, identity, request, operations)
    for name, original_sha in evidence.proofs.items():
        after.proof(name)
        require(after.proofs[name] == original_sha, 'proof_changed_during_observation')
    return dict(format_version=1, action=action, outcome='EXACT_COMPLETED_EFFECT', retry_allowed=False,
                source_sha256=digest(source_bytes), request_sha256=digest(canonical(request)),
                original_artifacts=dict(sorted(evidence.proofs.items())),
                derived_original_artifact_bindings=dict(sorted(evidence.derived_environment.items())), observation=observed,
                observed_at=datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))
