#!/usr/bin/env python3
"""Closed legacy admission evidence. This module never fences, executes or writes.

The inventory is selected by enrolled catalogue and producer readers, not by the
request. A recovered report remains an attributed claim even when its hash is
known. Native and separately observed cessation proofs are distinct admission
bases; neither converts an old missing result to SUCCESS.
"""
from dataclasses import dataclass
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import sys

sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location('v126_genesis_schema', Path(__file__).with_name('v126-dr-schema.py'))
S = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(S)
MAX_INVENTORY = 48 * 1024
MAX_SNAPSHOT = 16 * 1024 * 1024
F782 = 'v126-cutover-20260909t113822z-f7828e09'
HISTORICAL_F782 = 'FAILED_PRE_ACTIVE_CONFIG_MUTATION_EXTERNALLY_FENCED'
TARGET = {'path': 'path', 'device': 'uint', 'inode': 'positive', 'uid': 'uint', 'host_fingerprint': 'fingerprint'}
REQUEST = S.record('legacy-target-genesis-request', {
    'run_id': 'id', 'source_sha': 'git', 'source_tree': 'git', 'script_sha256': 'sha',
    'tooling_sha256': 'sha', 'python_version': 'version', 'target': TARGET,
    'next_init_manifest_sha256': 'sha', 'inventory_sha256': 'sha',
    'created_at': 'time', 'expires_at': 'time', 'nonce': 'sha'})
EFFECT = {'effect_id': 'id', 'kind': S.enum('OPERATOR_SESSION', 'DESCENDANT', 'DOCKER_REQUEST', 'FILESYSTEM_WRITER', 'RECOVERY_DISPATCH'), 'identity_sha256': 'sha'}
RUN = {'run_id': 'id', 'source_sha': 'git', 'source_tree': 'git', 'script_sha256': 'sha',
    'historical_outcome': S.enum('UNKNOWN', 'NATIVE_TERMINAL_PROVEN', HISTORICAL_F782),
    'primary': S.array({'name': 'id', 'sha256': 'sha', 'bytes': 'positive'}, 0),
    'projections': S.array({'name': 'id', 'source_sha256': 'sha', 'sha256': 'sha', 'limitations': S.array('id')}, 0),
    'reports': S.array({'name': 'id', 'sha256': 'sha', 'claimed_disposition': S.enum('UNKNOWN', HISTORICAL_F782)}, 0),
    'missing': S.array('id', 0), 'effects': S.array(EFFECT),
    'disposition': {'kind': S.enum('NATIVE_TERMINAL', 'LEGACY_UNKNOWN_FENCED'), 'proof_sha256': 'sha'}}
INVENTORY = S.record('legacy-target-inventory', {
    'source_identity': 'sha', 'discovery_method_sha256': 'sha', 'target': TARGET,
    'observed': S.CLOCK, 'expires_at': 'time', 'writer_exclusion_sha256': 'sha',
    'current_poststate_sha256': 'sha', 'runs': S.array(RUN)})
NATIVE = S.record('legacy-native-terminal-proof', {
    'run_id': 'id', 'source_sha': 'git', 'source_tree': 'git', 'script_sha256': 'sha',
    'target': TARGET, 'terminal_kind': S.enum('NATIVE_V126', 'NATIVE_PRE_V126'),
    'terminal_sha256': 'sha', 'handoff_sha256': 'sha', 'remote_history_sha256': 'sha',
    'files': S.array({'path': 'relative-path', 'sha256': 'sha', 'bytes': 'positive'})})
FENCED = S.record('legacy-fenced-disposition', {
    'run_id': 'id', 'source_sha': 'git', 'source_tree': 'git', 'script_sha256': 'sha',
    'target': TARGET, 'effect_scope_sha256': 'sha', 'fencing_method_sha256': 'sha',
    'fence_authorization_sha256': 'sha', 'acceptance_authorization_sha256': 'sha',
    'accepted_poststate_sha256': 'sha', 'observed': S.CLOCK, 'expires_at': 'time',
    'cessations': S.array({'effect_id': 'id', 'identity_sha256': 'sha', 'evidence_sha256': 'sha'})})
EXCLUSION = S.record('legacy-writer-exclusion', {'target': TARGET,
    'run_ids': S.array('id'), 'effect_scope_sha256': 'sha', 'method_sha256': 'sha',
    'authorization_sha256': 'sha', 'observed': S.CLOCK, 'expires_at': 'time',
    'evidence': S.array('sha')})
POSTSTATE = S.record('legacy-accepted-poststate', {'target': TARGET,
    'runtime_sha': 'git', 'image_id': 'image-id', 'database_version': S.enum(125, 126),
    'admission_sha256': 'sha', 'compose_sha256': 'sha', 'maintenance_sha256': 'sha',
    'acceptance_method_sha256': 'sha', 'authorization_sha256': 'sha',
    'observed': S.CLOCK, 'expires_at': 'time'})
OBSERVATIONS = S.record('ap06-legacy-observations', {
    'source_identity': 'sha', 'binding_sha256': 'sha', 'source_sha': 'git', 'source_tree': 'git',
    'tools_sha256': 'sha', 'python_version': 'version', 'target_sha256': 'sha',
    'observed': S.CLOCK, 'expires_at': 'time', 'inventory_sha256': 'sha',
    'observed_documents': S.array('sha'), 'writer_exclusion_sha256': 'sha',
    'current_poststate_sha256': 'sha', 'cessations': S.array({'run_id': 'id', 'effect_id': 'id',
        'identity_sha256': 'sha', 'evidence_sha256': 'sha', 'observed': S.CLOCK}, 0),
    'accepted_poststates': S.array('sha')})
AUTHORIZATION = S.record('legacy-disposition-authorization', {'authority_identity': 'sha',
    'target': TARGET, 'run_ids': S.array('id'), 'effect_scope_sha256': 'sha',
    'scope': S.enum('FENCE', 'ACCEPT_POSTSTATE', 'EXCLUDE_LEGACY_WRITERS'),
    'source_sha': 'git', 'valid_from': 'time', 'valid_until': 'time'})
PINS = {'discovery_method_sha256': 'sha', 'fencing_method_sha256': 'sha',
    'acceptance_method_sha256': 'sha', 'writer_exclusion_method_sha256': 'sha',
    'disposition_authority_identity': 'sha'}
EXPIRY_KEYS = frozenset(('anchor', 'clock', 'catalogue', 'authority', 'inventory', 'disposition'))


def refuse():
    raise ValueError('LEGACY_GENESIS_EVIDENCE_REFUSED') from None


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n').encode()


def digest(value):
    return hashlib.sha256(value if type(value) is bytes else canonical(value)).hexdigest()


def check(value, spec):
    if isinstance(spec, dict):
        if type(value) is not dict or set(value) != set(spec): refuse()
        for key, rule in spec.items(): check(value[key], rule)
    elif isinstance(spec, tuple) and spec[0] == 'array':
        if type(value) is not list or not spec[2] <= len(value) <= 256: refuse()
        for row in value: check(row, spec[1])
    elif spec == 'path':
        if type(value) is not str or not value.startswith('/') or len(value) > 4096 or not re.fullmatch(r'/[A-Za-z0-9_./-]+', value) or str(PurePosixPath(value)) != value or '..' in PurePosixPath(value).parts: refuse()
    elif spec == 'relative-path':
        if type(value) is not str or len(value) > 512 or not re.fullmatch(r'[A-Za-z0-9_./-]+', value) or PurePosixPath(value).is_absolute() or str(PurePosixPath(value)) != value or '..' in PurePosixPath(value).parts: refuse()
    elif spec == 'fingerprint':
        if type(value) is not str or not re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', value): refuse()
    elif spec == 'image-id':
        if type(value) is not str or not re.fullmatch(r'sha256:[0-9a-f]{64}', value): refuse()
    else:
        S.check(value, spec)


def unique(rows, key=None):
    items = [row if key is None else row[key] for row in rows]
    if len(items) != len(set(items)): refuse()


def validate_request(request, *, identity=None, target=None):
    check(request, REQUEST)
    if len(canonical(request)) > 16 * 1024 or S.timestamp(request['created_at']) >= S.timestamp(request['expires_at']): refuse()
    if identity is not None and request_identity(request) != identity: refuse()
    if target is not None and request['target']['path'] != str(target): refuse()
    return request


def request_identity(request):
    return dict(run_id=request['run_id'], release_sha=request['source_sha'], script_sha256=request['script_sha256'],
        intent_sha256=digest(request), kind='TARGET_BIND', name='LEGACY_GENESIS', action='bind-legacy-target')


def validate_inventory(inventory):
    check(inventory, INVENTORY)
    if len(canonical(inventory)) > MAX_INVENTORY: refuse()
    unique(inventory['runs'], 'run_id')
    for run in inventory['runs']:
        for key in ('primary', 'projections', 'reports'): unique(run[key], 'name')
        unique(run['missing']); unique(run['effects'], 'effect_id')
        names = [x['name'] for key in ('primary', 'projections', 'reports') for x in run[key]] + run['missing']
        if len(names) != len(set(names)): refuse()
        if run['historical_outcome'] == HISTORICAL_F782:
            if run['run_id'] != F782 or not any(x['claimed_disposition'] == HISTORICAL_F782 for x in run['reports']): refuse()
        if any(x['claimed_disposition'] == HISTORICAL_F782 for x in run['reports']) and run['run_id'] != F782: refuse()
        if run['historical_outcome'] == 'NATIVE_TERMINAL_PROVEN' and run['disposition']['kind'] != 'NATIVE_TERMINAL': refuse()
        if run['disposition']['kind'] == 'NATIVE_TERMINAL' and (run['missing'] or run['historical_outcome'] != 'NATIVE_TERMINAL_PROVEN'): refuse()
    return inventory


@dataclass(frozen=True)
class NativeTerminalProof:
    run_id: str
    source_sha: str
    target: str
    terminal_sha256: str
    handoff_sha256: str


def validate_basis(inventory, observations, documents, pins, now, native_verifier):
    """Machine observations come only from the independently enrolled reader.

    No booleans or claims in request/report substitute for this source. The
    native callback must execute the existing complete supported native verifier;
    unknown historical producers are not silently checked with today's parser.
    """
    validate_inventory(inventory); check(observations, OBSERVATIONS); check(pins, PINS)
    if observations['inventory_sha256'] != digest(inventory) or observations['source_identity'] != inventory['source_identity']: refuse()
    if inventory['discovery_method_sha256'] != pins['discovery_method_sha256']: refuse()
    unique(observations['observed_documents']); unique(observations['accepted_poststates'])
    observed = set(observations['observed_documents'])
    if not observed <= set(documents): refuse()
    if sum(map(len, documents.values())) > MAX_SNAPSHOT: refuse()
    def get(ref, spec=None):
        if ref not in observed or ref not in documents or digest(documents[ref]) != ref: refuse()
        try: value = json.loads(documents[ref])
        except (ValueError, TypeError): refuse()
        if canonical(value) != documents[ref]: refuse()
        if spec is not None: check(value, spec)
        return value
    def current(doc, protect_dispatch=False):
        # Use the same independent clock/chronology rules as the AP06 consumer.
        reserve = 900 if protect_dispatch else 0
        low = S.timestamp(now['utc']) - now['error_seconds']
        upper = S.timestamp(now['utc']) + now['error_seconds']
        stamp = doc['observed']
        if (stamp['clock_id'] != now['clock_id'] or stamp['monotonic_seconds'] > now['monotonic_seconds']
                or S.timestamp(stamp['utc']) > S.timestamp(now['utc'])
                or abs((S.timestamp(now['utc'])-S.timestamp(stamp['utc']))-(now['monotonic_seconds']-stamp['monotonic_seconds'])) > stamp['error_seconds']+now['error_seconds']): refuse()
        if upper - S.timestamp(stamp['utc']) + stamp['error_seconds'] > 300 or upper+reserve >= S.timestamp(doc['expires_at']): refuse()
        return min(300 - (upper - S.timestamp(stamp['utc']) + stamp['error_seconds']), S.timestamp(doc['expires_at']) - upper - reserve)
    def authorization(ref, scope, runs, effects):
        doc = get(ref, AUTHORIZATION)
        if (doc['authority_identity'] != pins['disposition_authority_identity'] or doc['scope'] != scope or doc['source_sha'] != observations['source_sha']
                or doc['target'] != inventory['target'] or set(doc['run_ids']) != set(runs)
                or doc['effect_scope_sha256'] != digest(effects)):
            refuse()
        unique(doc['run_ids'])
        if S.timestamp(doc['valid_from']) > S.timestamp(now['utc'])-now['error_seconds'] or S.timestamp(doc['valid_until']) <= S.timestamp(now['utc'])+now['error_seconds']+900: refuse()
        return S.timestamp(doc['valid_until'])-S.timestamp(now['utc'])-now['error_seconds']-900
    headrooms = [current(inventory, True), current(observations)]
    target = inventory['target']
    run_ids = [x['run_id'] for x in inventory['runs']]
    effects = [dict(run_id=run['run_id'], **effect) for run in inventory['runs'] for effect in run['effects']]
    exclusion_ref = inventory['writer_exclusion_sha256']
    if exclusion_ref != observations['writer_exclusion_sha256']: refuse()
    exclusion = get(exclusion_ref, EXCLUSION)
    if (exclusion['target'] != target or exclusion['method_sha256'] != pins['writer_exclusion_method_sha256']
            or exclusion['run_ids'] != run_ids or exclusion['effect_scope_sha256'] != digest(effects)
            or not set(exclusion['evidence']) <= observed): refuse()
    headrooms += [current(exclusion, True), authorization(exclusion['authorization_sha256'], 'EXCLUDE_LEGACY_WRITERS', run_ids, effects)]
    postref = inventory['current_poststate_sha256']
    if postref != observations['current_poststate_sha256'] or postref not in observations['accepted_poststates']: refuse()
    poststate = get(postref, POSTSTATE)
    if poststate['target'] != target or poststate['acceptance_method_sha256'] != pins['acceptance_method_sha256']: refuse()
    headrooms += [current(poststate, True), authorization(poststate['authorization_sha256'], 'ACCEPT_POSTSTATE', run_ids, effects)]
    seen_observations = set()
    for observation in observations['cessations']:
        key = (observation['run_id'], observation['effect_id'])
        if key in seen_observations: refuse()
        seen_observations.add(key)
    for run in inventory['runs']:
        for item in run['primary']:
            if item['sha256'] not in observed or item['sha256'] not in documents or len(documents[item['sha256']]) != item['bytes'] or digest(documents[item['sha256']]) != item['sha256']: refuse()
        # Projections and reports are retained as claims, never promoted to raw.
        for item in run['projections'] + run['reports']:
            if item['sha256'] not in documents or digest(documents[item['sha256']]) != item['sha256']: refuse()
        proof = get(run['disposition']['proof_sha256'], NATIVE if run['disposition']['kind'] == 'NATIVE_TERMINAL' else FENCED)
        if any(proof[k] != run[k] for k in ('run_id','source_sha','source_tree','script_sha256')) or proof['target'] != target: refuse()
        if run['disposition']['kind'] == 'NATIVE_TERMINAL':
            unique(proof['files'], 'path')
            for entry in proof['files']:
                if entry['sha256'] not in observed or entry['sha256'] not in documents or len(documents[entry['sha256']]) != entry['bytes'] or digest(documents[entry['sha256']]) != entry['sha256']: refuse()
            for key in ('terminal_sha256','handoff_sha256','remote_history_sha256'):
                if proof[key] not in observed: refuse()
            if not callable(native_verifier): refuse()
            result = native_verifier(run, proof, documents)
            if type(result) is not NativeTerminalProof or result != NativeTerminalProof(run['run_id'], run['source_sha'], target['path'], proof['terminal_sha256'], proof['handoff_sha256']): refuse()
        else:
            if (proof['fencing_method_sha256'] != pins['fencing_method_sha256'] or proof['effect_scope_sha256'] != digest(run['effects'])
                    or proof['accepted_poststate_sha256'] != postref): refuse()
            headrooms += [current(proof, True), authorization(proof['fence_authorization_sha256'], 'FENCE', [run['run_id']], run['effects'])]
            if proof['acceptance_authorization_sha256'] != poststate['authorization_sha256']: refuse()
            unique(proof['cessations'], 'effect_id')
            if {x['effect_id'] for x in proof['cessations']} != {x['effect_id'] for x in run['effects']}: refuse()
            for effect in run['effects']:
                rows = [x for x in observations['cessations'] if x['run_id'] == run['run_id'] and x['effect_id'] == effect['effect_id']]
                claims = [x for x in proof['cessations'] if x['effect_id'] == effect['effect_id']]
                if len(rows) != 1 or len(claims) != 1: refuse()
                row, claim = rows[0], claims[0]
                if row['identity_sha256'] != effect['identity_sha256'] or any(row[k] != claim[k] for k in claim) or row['evidence_sha256'] not in observed: refuse()
                headrooms.append(current(dict(observed=row['observed'], expires_at=observations['expires_at'])))
    return {'inventory_sha256': digest(inventory), 'poststate_sha256': postref,
            'disposition_sha256': digest([x['disposition'] for x in inventory['runs']]),
            'headroom': min(headrooms)}


def verify_native_terminal(run, proof, documents, *, source_path=None, next_owner):
    """Replay the existing complete native verifier on bounded protected copies.

    Only the installed, source-bound producer version is executable. A different
    historical producer is unsupported, never evaluated from supplied evidence.
    All document paths are relative state/ or remote/ snapshot members; manifest
    paths are data and are not followed for reading credentials or backups.
    """
    import os
    import subprocess
    import tempfile
    from datetime import datetime, timedelta
    source_path = Path(source_path or Path(__file__).with_name('v126-cutover.sh')).resolve(strict=True)
    check(proof, NATIVE)
    if digest(source_path.read_bytes()) != proof['script_sha256']:
        raise ValueError('LEGACY_NATIVE_PRODUCER_UNSUPPORTED')
    if proof['script_sha256'] != run['script_sha256']: refuse()
    if not documents or sum(map(len, documents.values())) > MAX_SNAPSHOT: refuse()
    files = proof['files']; unique(files, 'path')
    filemap = {row['path']: row for row in files}
    for row in files:
        check(row['path'], 'relative-path')
        parts = PurePosixPath(row['path']).parts
        if len(parts) < 2 or parts[0] not in ('state','remote') or row['path'].endswith('/lock'): refuse()
        if row['sha256'] not in documents or len(documents[row['sha256']]) != row['bytes'] or digest(documents[row['sha256']]) != row['sha256']: refuse()
    def document(ref):
        if ref not in documents or digest(documents[ref]) != ref: refuse()
        value=json.loads(documents[ref])
        if canonical(value) != documents[ref]: refuse()
        return value
    remote_descriptor=document(proof['remote_history_sha256'])
    if type(remote_descriptor) is not dict or set(remote_descriptor) != {'format_version','inventory'} or remote_descriptor['format_version'] != 1: refuse()
    expected_remote={name.removeprefix('remote/'):row['sha256'] for name,row in filemap.items() if name.startswith('remote/')}
    if remote_descriptor['inventory'] != expected_remote or 'run.json' not in expected_remote: refuse()
    if 'state/run.json' not in filemap: refuse()
    manifest=document(filemap['state/run.json']['sha256'])
    if (any(manifest.get(k) != run[k] for k in ('run_id','script_sha256')) or manifest.get('release_sha') != run['source_sha']
            or manifest.get('release_tree') != run['source_tree'] or manifest.get('staging_path') != proof['target']['path']): refuse()
    module_spec=importlib.util.spec_from_file_location('v126_genesis_native_bindings',source_path.with_name('v126-operation-bindings.py'))
    bindings=importlib.util.module_from_spec(module_spec);module_spec.loader.exec_module(bindings)
    owner=dict(run_id=run['run_id'],release_sha=run['source_sha'],script_sha256=run['script_sha256'])
    handoff=document(proof['handoff_sha256'])
    bindings.binding_handoff(handoff,owner,next_owner,proof['terminal_sha256'],proof['target']['path'])
    if handoff['operational_version'] != ('V126' if proof['terminal_kind']=='NATIVE_V126' else 'V125'): refuse()
    with tempfile.TemporaryDirectory(prefix='v126-native-genesis-') as directory:
        root=Path(directory);root.chmod(0o700)
        for row in files:
            path=root/row['path']
            path.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
            for parent in path.parents:
                if parent==root: break
                parent.chmod(0o700)
            path.write_bytes(documents[row['sha256']]);path.chmod(0o400)
        state=root/'state';remote=root/'remote'
        for name in ('artifacts','authorizations','intents','receipts','recovery','tmp'):
            (state/name).mkdir(mode=0o700,exist_ok=True)
        (remote/'lock').write_bytes(b'');(remote/'lock').chmod(0o600)
        current,owners,identities,has_recovery=bindings.binding_history(remote,Path(proof['target']['path']))
        if current != owner or owners != [owner]: refuse()
        initializations=[x for x in identities if x['kind']=='INIT']
        if len(initializations)!=1: refuse()
        init_identity=initializations[0];init_op=digest(init_identity)
        init_request=bindings.binding_request(remote/(init_op+'.request.json'),init_identity,Path(proof['target']['path']))
        init_result=bindings.binding_result(remote/(init_op+'.result.json'),init_identity,Path(proof['target']['path']))
        manifest_raw=(state/'run.json').read_bytes()
        if init_request['manifest_sha256']!=digest(manifest_raw) or init_request['manifest_size']!=len(manifest_raw): refuse()
        completion_path=state/'init-completion.json'
        if not completion_path.is_file(): refuse()
        completion=json.loads(completion_path.read_bytes())
        if (set(completion)!={'request','result','request_sha256','result_sha256'}
                or completion['request']!=init_request or completion['result']!=init_result
                or completion['request_sha256']!=digest(init_request) or completion['result_sha256']!=digest(init_result)):
            refuse()
        bindings.binding_validate_init_completion(init_identity,init_request,init_result,Path(proof['target']['path']))

        terminal_path = ('receipts/20-FINAL_PUBLIC_GATES_PASSED.receipt.json' if proof['terminal_kind']=='NATIVE_V126' else 'recovery/pre-v126.receipt.json')
        if 'state/'+terminal_path not in filemap or filemap['state/'+terminal_path]['sha256'] != proof['terminal_sha256']: refuse()
        terminal=document(proof['terminal_sha256'])
        if datetime.fromisoformat(handoff['observed_at'].replace('Z','+00:00')) < datetime.fromisoformat(terminal['completed_at'].replace('Z','+00:00')): refuse()
        # Fixed script and fixed functions only. No command/producer supplied by
        # a record is executed, no external action function is called.
        if proof['terminal_kind']=='NATIVE_V126':
            code='source "$1"; load_state "$2"; verify_receipt FINAL_PUBLIC_GATES_PASSED >/dev/null'
            required=('STAGE','FINAL_PUBLIC_GATES_PASSED')
        else:
            predecessor=terminal.get('predecessor_stage')
            if type(predecessor) is not str or not re.fullmatch('[A-Z][A-Z0-9_]+',predecessor): refuse()
            code='source "$1"; load_state "$2"; verify_receipt "$3" >/dev/null; verify_recovery_receipt pre-v126 >/dev/null; verify_reconciliation_recovery_intent pre-v126 >/dev/null; [[ "$(classify_status_record "${STATE_DIR}/run-terminal.json" terminal)" == RECONCILIATION_REQUIRED ]]'
            required=('RECOVERY','pre-v126')
        command=['bash','-ceu',code,'v126-native-genesis-verifier',str(source_path),str(state)]
        if proof['terminal_kind']=='NATIVE_PRE_V126': command.append(predecessor)
        checks='; execution_status="$(status_command status --state-dir "$2")"; [[ "$execution_status" != *INVALID_EVIDENCE* ]]; attempt_state="$(read_attempt_state)"; case "$attempt_state" in NOT_STARTED|NOT_DISPATCHED) ;; *) exit 75 ;; esac; '
        checks += ('[[ "$execution_status" == *canonical_execution=COMPLETE* ]]' if proof['terminal_kind']=='NATIVE_V126' else
            '[[ "$execution_status" == *terminal=true* && "$execution_status" == *canonical_execution=TERMINAL_RECOVERY_COMPLETE* ]]')
        command[2] += checks
        completed=subprocess.run(command,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=300,
            env={'PATH':os.environ.get('PATH','/usr/bin:/bin'),'HOME':str(root),'PYTHONDONTWRITEBYTECODE':'1'})
        if completed.returncode != 0: refuse()
        stages = ('BASELINE_VERIFIED','PRE_DRAIN_BACKUP_REHEARSED','CADDY_CANDIDATE_INSTALLED_AND_RELOADED',
            'PUBLIC_DRAIN_ACTIVE','V125_BACKEND_STOPPED','ZERO_WRITER_GATE_PASSED','QUIESCED_BACKUP_REHEARSED',
            'FINAL_V125_PREFLIGHT_PASSED','V126_MAINTENANCE_CONFIG_PREPARED','V126_IMAGE_TRANSFERRED_AND_VERIFIED',
            'V126_BACKEND_STARTED','V126_SCHEMA_RUNTIME_GATE_PASSED','MANUAL_SMOKE_AUTHORIZED','MANUAL_SMOKE_PASSED',
            'PUBLIC_DRAIN_REACTIVATED','V126_BACKEND_STOPPED_FOR_OFF_TRANSITION','MAINTENANCE_OFF_CONFIG_VERIFIED',
            'FINAL_V126_BACKEND_STARTED','ORDINARY_CADDY_RESTORED','FINAL_PUBLIC_GATES_PASSED')
        actions = (('baseline',),('backup-rehearsal',),('caddy-activate',),('public-drain-on',),('stop-backend',),
            ('zero-writer',),('backup-rehearsal',),('preflight-upload','final-v125-preflight'),('transform-maintenance',),
            ('image-prepare','image-upload','image-load'),('start-v126',),('schema-runtime-gate',),('open-manual-smoke',),
            ('record-manual-smoke',),('public-drain-on',),('stop-backend',),('transform-maintenance',),('start-v126',),
            ('restore-caddy',),('final-public-gates',))
        final_stage = 'FINAL_PUBLIC_GATES_PASSED' if proof['terminal_kind']=='NATIVE_V126' else predecessor
        if final_stage not in stages: refuse()
        allowed_stages=set(stages[:stages.index(final_stage)+1])
        expected_operations={('INIT','RUN_INITIALIZED','initialize-run')}
        expected_operations.update(('STAGE',stage,action) for stage,stage_actions in zip(stages,actions)
            if stage in allowed_stages for action in stage_actions)
        if proof['terminal_kind']=='NATIVE_PRE_V126':
            expected_operations.add(('RECOVERY','pre-v126','recover-pre-v126'))
        actual_operations=[(x['kind'],x['name'],x['action']) for x in identities]
        if len(actual_operations)!=len(expected_operations) or set(actual_operations)!=expected_operations: refuse()
        def utc(value):
            try:
                parsed=datetime.fromisoformat(value.replace('Z','+00:00'))
                if parsed.tzinfo is None or parsed.utcoffset()!=timedelta(0): refuse()
                return parsed
            except (AttributeError,TypeError,ValueError):
                refuse()
        previous_completed=utc(init_result['completed_at'])
        if utc(bindings.binding_read(remote/(init_op+'.start.json'))['started_at'])>previous_completed: refuse()
        previous_native=previous_completed.replace(microsecond=0)
        for index,(stage,stage_actions) in enumerate(zip(stages,actions),1):
            if stage not in allowed_stages: break
            local_receipt=state/'receipts'/f'{index:02d}-{stage}.receipt.json'
            if not local_receipt.is_file(): refuse()
            native=json.loads(local_receipt.read_bytes())
            rows=[x for x in identities if x['kind']=='STAGE' and x['name']==stage]
            if len(rows)!=len(stage_actions) or {x['action'] for x in rows}!=set(stage_actions): refuse()
            remote_log=b''
            native_completed=utc(native['completed_at'])
            if native_completed<previous_native: refuse()
            for action in stage_actions:
                row=next(x for x in rows if x['action']==action)
                if row['intent_sha256']!=native['intent_sha256']: refuse()
                operation=digest(row)
                result=bindings.binding_result(remote/(operation+'.result.json'),row,Path(proof['target']['path']))
                if result['exit']!=0 or result['outcome']!='SUCCEEDED': refuse()
                start=bindings.binding_read(remote/(operation+'.start.json'))
                started=utc(start['started_at']);completed_remote=utc(result['completed_at'])
                # Native receipts have second precision; remote records retain
                # fractions. Actions and their predecessor must still be ordered.
                if (started<max(previous_completed,previous_native) or started>completed_remote
                        or completed_remote>=native_completed+timedelta(seconds=1)): refuse()
                previous_completed=completed_remote
                remote_log+=(remote/(operation+'.log')).read_bytes()
            previous_native=native_completed
            local_log=(state/'artifacts'/f'{index}-{stage}.operation.log').read_bytes()
            if index==1:
                artifact_map={x['name']:x['sha256'] for x in native['artifacts']}
                prefix=b''.join(('ARTIFACT\t'+name+'\t'+artifact_map[name]+'\n').encode() for name in ('local-baseline','main-actions'))
                if not local_log.startswith(prefix): refuse()
                local_log=local_log[len(prefix):]
            if local_log!=remote_log: refuse()
        matches=[x for x in identities if (x['kind'],x['name'])==required]
        if len(matches)!=1 or matches[0]['intent_sha256'] != terminal['intent_sha256']: refuse()
        op=digest(matches[0]);result=bindings.binding_result(remote/(op+'.result.json'),matches[0],Path(proof['target']['path']))
        if result['exit']!=0 or result['outcome']!='SUCCEEDED': refuse()
        native_log_ref=next((x['sha256'] for x in terminal['artifacts'] if x['name']=='operation-log'),None)
        if native_log_ref != result['log_sha256']: refuse()
        started=utc(bindings.binding_read(remote/(op+'.start.json'))['started_at'])
        completed_remote=utc(result['completed_at']);terminal_completed=utc(terminal['completed_at'])
        if (started>completed_remote or completed_remote>=terminal_completed+timedelta(seconds=1)
                or terminal_completed>utc(handoff['observed_at'])): refuse()
        if proof['terminal_kind']=='NATIVE_PRE_V126' and (started<max(previous_completed,previous_native)
                or terminal_completed<previous_native): refuse()
    return NativeTerminalProof(run['run_id'],run['source_sha'],proof['target']['path'],proof['terminal_sha256'],proof['handoff_sha256'])
