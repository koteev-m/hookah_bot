#!/usr/bin/env python3
"""Connected reconciliation hook for the owned ordinary-deploy Linux fixture.

The predecessor baseline/maintenance/start/Caddy receipts below are explicitly
synthetic prerequisites. The final-public action, supervisor, lost output pipe,
fresh observer, immutable reconciliation and subsequent transfer are real code.
No additional image build, container start, daemon configuration or SSH is used.
"""
import argparse
import difflib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SELF = Path(__file__).resolve()
ROOT = SELF.parent.parent


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def require(condition, reason):
    if not condition:
        raise ValueError('OWNED_RECONCILIATION:' + reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()


def create(path, raw, mode=0o400):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(descriptor, 'wb') as output:
        output.write(raw)
        output.flush()
        os.fsync(output.fileno())


def proof(root, name, fields):
    raw = ''.join(key + '=' + str(value) + '\n' for key, value in fields.items()).encode()
    create(root / (name + '.proof'), raw, 0o600)
    create(root / (name + '.proof.sha256'), (sha(raw) + '\n').encode(), 0o600)
    return sha(raw)


def activation_program(source):
    section = source.decode().split('remote_verify_caddy_receipt_evidence() {\n', 1)[1].split('\nremote_verify_partial_caddy_evidence()', 1)[0]
    chunks = re.findall(r"<<'PY'\n(.*?)\nPY\n", section, re.S)
    require(len(chunks) == 1 and 'Caddy activation proof schema mismatch' in chunks[0], 'source activation consumer unavailable')
    return (chunks[0] + '\n').encode()


def adapter_plan(spec, kind, args, payload=None):
    if kind == 'sudo' and args[:2] == ['python3', '-'] and len(args) == 8:
        require(payload is not None and sha(payload) == spec['activation_program_sha256'], 'privileged reader source differs')
        require(args[2:] == spec['activation_arguments'], 'privileged activation arguments differ')
        return [spec['real_sudo'], '-n', sys.executable, '-', spec['activation_path'], *args[3:]], payload
    return [spec['previous_' + kind], *args], payload


def owned_telegram_plan(spec, args):
    base = ['--disable', '--connect-timeout', '3', '--max-time', '10', '--config']
    require(len(args) == 9 and args[:6] == base and args[7] == '--output', 'Telegram reader arguments differ')
    config, output = Path(args[6]), Path(args[8])
    metadata = config.lstat()
    require(stat.S_ISREG(metadata.st_mode) and metadata.st_uid == os.geteuid() and
            stat.S_IMODE(metadata.st_mode) == 0o600 and not output.is_symlink() and
            config.parent == output.parent and config.parent.name.startswith('v126-telegram-idle.'), 'Telegram private files differ')
    expected = ('silent\nshow-error\nfail\nurl = "https://api.telegram.org/bot' + spec['telegram_token'] + '/getWebhookInfo"\n').encode()
    require(config.read_bytes() == expected, 'only own getWebhookInfo is allowed')
    return [spec['guarded_curl'], '--disable', '--noproxy', '*', '--proto', '=https', '--connect-timeout', '3',
            '--max-time', '10', '--cacert', spec['provider_ca'], '--fail', '--silent', '--show-error',
            '--url', 'https://127.0.0.1:' + str(spec['provider_port']) + '/bot' + spec['telegram_token'] + '/getWebhookInfo',
            '--output', str(output)]


def adapter(spec_path, kind, args):
    path = Path(spec_path)
    metadata = path.lstat()
    require(stat.S_ISREG(metadata.st_mode) and metadata.st_uid == os.geteuid() and
            stat.S_IMODE(metadata.st_mode) == 0o600, 'owned adapter specification metadata')
    spec = json.loads(path.read_bytes())
    require(os.geteuid() == 0 and path.parent == Path(spec['root']), 'owned adapter requires root private namespace')
    if kind == 'curl' and '--config' in args:
        command, payload = owned_telegram_plan(spec, args), None
    else:
        payload = sys.stdin.buffer.read(32769) if kind == 'sudo' and args[:2] == ['python3', '-'] else None
        command, payload = adapter_plan(spec, kind, args, payload)
    if payload is None:
        os.execv(command[0], command)
    return subprocess.run(command, input=payload).returncode


def synthetic_operation(bindings, target, owner, stage, action, args, environment, artifacts):
    identity = dict(owner, intent_sha256=sha(('SYNTHETIC_PREREQUISITE:' + stage).encode()), kind='STAGE', name=stage, action=action)
    operation = sha(canonical(identity))
    root = target / '.v126-target-operations'
    bindings.binding_create(root / (operation + '.start.json'), dict(identity=identity, operation_id=operation,
                            started_at='2026-09-10T00:00:00Z', boot_id='synthetic-prerequisite'))
    request = dict(format_version=1, identity=identity, target_sha256=sha(str(target).encode()), args=args, environment=environment)
    bindings.binding_create(root / (operation + '.request.json'), request)
    log = b'SYNTHETIC_PREREQUISITE_NOT_A_REAL_STAGE_RECEIPT\n' + b''.join(
        ('ARTIFACT\t' + name + '\t' + value + '\n').encode() for name, value in artifacts.items())
    create(root / (operation + '.log'), log)
    bindings.binding_create(root / (operation + '.result.json'), dict(identity=identity, operation_id=operation,
                            exit=0, outcome='SUCCEEDED', children='REAPED', log_sha256=sha(log), completed_at='2026-09-10T00:00:00Z'))


def secondary_failure(primary, phase, secondary):
    note = 'Owned reconciliation ' + phase + ' failed (' + type(secondary).__name__ + '); first failure preserved; fixture retained if lifetime is unknown'
    try:
        if primary is not None: primary.add_note(note)
        print('OWNED_RECONCILIATION_SECONDARY ' + json.dumps(dict(phase=phase, type=type(secondary).__name__)), file=sys.stderr)
    except BaseException:
        # A broken diagnostic destination must never replace the first failure.
        pass


def run_connected(fixture, caddy, evidence_dir):
    require(sys.platform == 'linux' and os.geteuid() == 0 and os.environ.get('GITHUB_ACTIONS') == 'true' and
            os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted', 'disposable hosted root Linux required')
    bindings = load(ROOT / 'scripts/v126-operation-bindings.py', 'reconciliation_linux_bindings')
    collector = load(ROOT / 'scripts/v126-reconcile-poststate.py', 'reconciliation_linux_collector')
    evidence_dir = Path(evidence_dir)
    evidence_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    target = fixture.root.resolve()
    source = (ROOT / 'scripts/v126-cutover.sh').read_bytes()
    source_path = target / 'reconciliation-source.sh'
    create(source_path, source)
    owner = dict(run_id='owned-reconciled-' + fixture.token[:16], release_sha=fixture.release, script_sha256=sha(source))
    run = owner['run_id']
    registry = target / '.v126-target-operations'
    registry.mkdir(mode=0o700)
    create(registry / 'lock', b'', 0o600)
    bindings.binding_create(registry / 'run.json', owner)
    inode = (registry / 'lock').stat().st_ino
    namespace = target / '.v126-runs'
    namespace.mkdir(mode=0o700)
    run_root = namespace / run
    run_root.mkdir(mode=0o700)
    scripts = target / 'scripts'
    scripts.mkdir(mode=0o755, exist_ok=True)
    for name in ('check-staging-maintenance-config.sh', 'validate-staging-admission.sh'):
        path = scripts / name
        require(not path.exists(), 'own fixture guard destination already exists')
        create(path, (ROOT / 'scripts' / name).read_bytes(), 0o755)
    uri = target / 'reconciliation-database-uri'
    identities = target / 'reconciliation-identities'
    create(uri, (fixture.uri + '\n').encode(), 0o600)
    create(identities, b'STAGING_MAINTENANCE_ALLOWED_USER_IDS=81001001\nSTAGING_MAINTENANCE_ALLOWED_CHAT_IDS=81001001\n', 0o600)
    cid = fixture.cid('backend')
    actual = fixture.inspect(cid)
    require(actual['HostConfig']['RestartPolicy']['Name'] == 'no' and actual['RestartCount'] == 0 and
            actual['Image'] == fixture.image_id, 'terminal fixture requires exact restart=no backend')
    require(fixture.values['STAGING_MAINTENANCE_MODE'] == 'OFF', 'terminal fixture must be OFF')
    env_sha, compose_sha = sha((target / '.env').read_bytes()), sha((target / 'docker-compose.yml').read_bytes())
    maintenance_sha, admission_sha = (sha((scripts / name).read_bytes()) for name in
                                    ('check-staging-maintenance-config.sh', 'validate-staging-admission.sh'))
    db_result = fixture.run(['python3', ROOT / 'scripts/v126-database-evidence.py', 'live-target', uri,
                            fixture.image, sha(uri.read_bytes())], timeout=180).stdout.decode().strip()
    require(re.fullmatch('DATABASE_TARGET_EQUALITY=[0-9a-f]{64}', db_result), 'real database identity unavailable')
    db_sha = db_result.split('=', 1)[1]
    caddy_root = caddy.caddy_root / 'v126-evidence' / fixture.release / run
    caddy_root.mkdir(mode=0o700, parents=True)
    for parent in (caddy.caddy_root / 'v126-evidence', caddy.caddy_root / 'v126-evidence' / fixture.release):
        parent.chmod(0o700)
    original, candidate = caddy.original.encode(), caddy.candidate.encode()
    diff = ''.join(difflib.unified_diff(caddy.original.splitlines(True), caddy.candidate.splitlines(True),
                                      fromfile='Caddyfile.original', tofile='Caddyfile.drain')).encode()
    for name, raw in [('Caddyfile.original', original), ('Caddyfile.drain', candidate), ('Caddyfile.drain.diff', diff)]:
        create(caddy_root / name, raw, 0o600)
        if name != 'Caddyfile.drain.diff': create(caddy_root / (name + '.sha256'), (sha(raw) + '\n').encode(), 0o600)
    current_config = fixture.run([caddy.spec['curl'], '--disable', '--noproxy', '*', '--proto', '=http',
                                 '--connect-timeout', '3', '--max-time', '10', '-fsS',
                                 'http://127.0.0.1:' + str(caddy.admin_port) + '/config/']).stdout
    activation = proof(caddy_root, 'activation', dict(run_id=run, release_sha=fixture.release,
                       original_sha256=sha(original), candidate_sha256=sha(candidate), diff_sha256=sha(diff),
                       active_admin_config_sha256=sha(current_config), marker_present='false', activation_reload='PASS'))
    baseline = dict(run_id=run, release_sha=fixture.release, database_url_path=str(uri), database_url_sha256=sha(uri.read_bytes()),
                    maintenance_identities_path=str(identities), maintenance_identities_sha256=sha(identities.read_bytes()),
                    compose_path=str(target / 'docker-compose.yml'), compose_sha256=compose_sha,
                    maintenance_check_path=str(scripts / 'check-staging-maintenance-config.sh'), maintenance_check_sha256=maintenance_sha,
                    admission_path=str(scripts / 'validate-staging-admission.sh'), admission_sha256=admission_sha,
                    caddy_sha256=sha(original), environment_path=str(target / '.env'), environment_sha256=env_sha, result='PASS')
    proof(run_root, 'baseline-authority', baseline)
    off_sha = proof(run_root, 'maintenance-off', dict(run_id=run, release_sha=fixture.release, mode='OFF', before_sha256='b' * 64,
                    after_sha256=env_sha, identities='BOUND_REDACTED', unrelated_bytes='PRESERVED', result='PASS'))
    start_sha = proof(run_root, 'v126-backend-final-started', dict(run_id=run, release_sha=fixture.release, phase='final',
                      image_tag=fixture.image, image_id=fixture.image_id, backend_container_id=cid, compose_build='false',
                      start_command_count='1', restart_policy='no', restart_count='0', backend_count='1', poller_count='1',
                      live_v125_count='0', live_old_image_count='0', public_drain='PASS', result='PASS'))
    restore_sha = proof(run_root, 'ordinary-caddy-restored', dict(run_id=run, release_sha=fixture.release,
                        original_sha256=sha(original), active_admin_config_sha256=sha(current_config), maintenance='OFF', public='LIVE',
                        byte_preserving='true', result='PASS'))
    environment = {
        'V126_INTERNAL_REMOTE_MODE': 'true', 'V126_INTERNAL_REMOTE_ENVELOPE_VALIDATED': 'V126_INTERNAL_REMOTE_ENVELOPE_V1',
        'V126_INTERNAL_REMOTE_V126_IMAGE_ID': fixture.image_id, 'V126_INTERNAL_REMOTE_DATABASE_TARGET_IDENTITY_SHA256': db_sha,
        'V126_INTERNAL_REMOTE_MAINTENANCE_OFF_SHA256': off_sha, 'V126_INTERNAL_REMOTE_MAINTENANCE_SMOKE_SHA256': 'NONE',
        'V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256': sha(original), 'V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256': sha(candidate),
        'V126_INTERNAL_REMOTE_CADDY_DIFF_SHA256': sha(diff), 'V126_INTERNAL_REMOTE_CADDY_ACTIVATION_SHA256': activation,
        'V126_INTERNAL_REMOTE_BASELINE_DATABASE_URL_SHA256': sha(uri.read_bytes()),
        'V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_IDENTITIES_SHA256': sha(identities.read_bytes()),
        'V126_INTERNAL_REMOTE_BASELINE_COMPOSE_SOURCE_SHA256': compose_sha,
        'V126_INTERNAL_REMOTE_BASELINE_MAINTENANCE_CHECK_SOURCE_SHA256': maintenance_sha,
        'V126_INTERNAL_REMOTE_BASELINE_ADMISSION_SOURCE_SHA256': admission_sha,
        'V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256': sha(original), 'V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256': env_sha,
    }
    common = [str(target), run, fixture.release, fixture.image, fixture.image_id]
    synthetic_operation(bindings, target, owner, 'BASELINE_VERIFIED', 'baseline', common[:4] +
                        [str(uri), str(identities), compose_sha, maintenance_sha, admission_sha], environment, {})
    synthetic_operation(bindings, target, owner, 'MAINTENANCE_OFF_CONFIG_VERIFIED', 'transform-maintenance',
                        common[:4] + [str(identities), 'OFF', sha(identities.read_bytes())], environment, {'maintenance-off': off_sha})
    synthetic_operation(bindings, target, owner, 'FINAL_V126_BACKEND_STARTED', 'start-v126', common + ['final'],
                        environment, {'v126-backend-final-started': start_sha})
    synthetic_operation(bindings, target, owner, 'ORDINARY_CADDY_RESTORED', 'restore-caddy', common, environment,
                        {'ordinary-caddy-restored': restore_sha})
    adapter_root = target / 'reconciliation-adapters'
    adapter_root.mkdir(mode=0o700)
    import shutil
    spec = dict(root=str(adapter_root), previous_curl=shutil.which('curl'), previous_sudo=shutil.which('sudo'),
                real_sudo=caddy.tools['sudo'], guarded_curl=caddy.spec['curl'], provider_port=fixture.provider_port,
                provider_ca=str(fixture.fixture / 'provider.crt'), telegram_token=fixture.telegram_token,
                activation_program_sha256=sha(activation_program(source)), activation_path=str(caddy_root / 'activation.proof'),
                activation_arguments=['/etc/caddy/v126-evidence/' + fixture.release + '/' + run + '/activation.proof',
                                      run, fixture.release, sha(original), sha(candidate), sha(diff)])
    spec_path = adapter_root / 'spec.json'
    create(spec_path, canonical(spec), 0o600)
    import shlex
    for kind in ('curl', 'sudo'):
        create(adapter_root / kind, ('#!/bin/sh\nexec ' + ' '.join(shlex.quote(value) for value in
               [sys.executable, str(SELF), '--adapter', str(spec_path), kind]) + ' "$@"\n').encode(), 0o700)
    saved_path = os.environ['PATH']
    os.environ['PATH'] = str(adapter_root) + os.pathsep + saved_path
    identity = dict(owner, intent_sha256=sha(b'OWNED_REAL_TERMINAL_ACTION'), kind='STAGE', name='FINAL_PUBLIC_GATES_PASSED', action='final-public-gates')
    op = sha(canonical(identity))
    request = dict(format_version=1, identity=identity, target_sha256=sha(str(target).encode()), args=common, environment=environment)
    launch = target / 'reconciliation-launch.json'
    create(launch, canonical(dict(identity=identity, args=common, environment=environment, source=str(source_path), target=str(target))))
    events = []
    active = None
    primary = None
    try:
        # Close the real reader before execution. Supervisor result/log durability
        # is independent of this lost stdout/ACK pipe; no SSH is simulated.
        with (evidence_dir / 'closed-pipe.stderr').open('xb') as error:
            active = subprocess.Popen([sys.executable, str(SELF), '--supervise', str(launch)],
                                      stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=error,
                                      env={key: os.environ[key] for key in ('PATH', 'HOME')}, start_new_session=True)
            active.stdout.close()
            local_exit = active.wait(timeout=750)
            active = None
        outcome = bindings.binding_read(registry / (op + '.result.json'))
        require(outcome['exit'] == 0 and outcome['outcome'] == 'SUCCEEDED' and outcome['children'] == 'REAPED', 'real terminal action did not finish')
        before = {str(path.relative_to(registry)): path.read_bytes() for path in registry.rglob('*') if path.is_file()}
        _, files, retained_request = bindings.binding_operation_evidence(registry, op, target)
        require(retained_request == request, 'actual protected request differs')
        operations = [dict(operation_id=op, files=files)]
        # Actual fixed metadata guard must refuse a lookalike with wrong ownership
        # mode before reconciliation is recorded. Restore only the owned mode.
        (target / '.env').chmod(0o644)
        try:
            try:
                bindings.binding_reconcile(target, owner, identity['kind'], identity['name'], identity['intent_sha256'],
                    owner['script_sha256'], sha((ROOT / 'scripts/v126-reconcile-poststate.py').read_bytes()), ['final-public-gates'],
                    lambda current, original, group: collector.collect(target, current, source, original, group))
            except (ValueError, bindings.BindingError):
                events.append({'checkpoint': 'ACTUAL_WRONG_ENV_METADATA_REFUSED'})
            else:
                raise ValueError('OWNED_RECONCILIATION:wrong metadata accepted')
        finally:
            (target / '.env').chmod(0o600)
        require(before == {str(path.relative_to(registry)): path.read_bytes() for path in registry.rglob('*') if path.is_file()},
                'refused observation altered operation records')
        original_evidence = collector.Evidence(target, identity, request, operations)
        calls, _ = collector.observer_plan(original_evidence, request)
        wrong_calls = [(name, collector.quoted_call('reconcile_exact_backend', '0' * 64, fixture.image_id)
                        if name == 'exact_backend' else command) for name, command in calls]
        try:
            collector.run_observer(source, request, original_evidence, wrong_calls, '0' * 64)
        except ValueError as error:
            require('current_postconditions_exit_4_' in str(error), 'wrong resource did not fail actual equality consumer')
            events.append({'checkpoint': 'ACTUAL_WRONG_BACKEND_ID_REFUSED',
                           'scope': 'actual observer consumer with synthetic wrong expected ID; original records unchanged'})
        else:
            raise ValueError('OWNED_RECONCILIATION:wrong backend identity accepted')
        record = bindings.binding_reconcile(target, owner, identity['kind'], identity['name'], identity['intent_sha256'],
            owner['script_sha256'], sha((ROOT / 'scripts/v126-reconcile-poststate.py').read_bytes()), ['final-public-gates'],
            lambda current, original, group: collector.collect(target, current, source, original, group))
        require(record['kind'] == 'RECONCILED_EFFECT' and record['poststate']['observation']['backend_container_id'] == cid and
                record['retry_allowed'] is False and (registry / 'lock').stat().st_ino == inode and
                all((registry / name).read_bytes() == raw for name, raw in before.items()), 'reconciliation identity/history differs')
        require(bindings.binding_reconciliation_inventory(registry, target), 'actual reconciliation inventory unavailable')
        record_path = registry / 'reconciliations' / (op + '.json')
        events.append({'checkpoint': 'REAL_TERMINAL_LOST_PIPE_RECONCILED', 'operation_id': op, 'local_exit': local_exit,
                       'original_result': outcome['outcome'], 'children': outcome['children'], 'backend_container_id': cid,
                       'postgres_container_id': record['poststate']['observation']['postgres_container_id'],
                       'reconciliation_sha256': sha(record_path.read_bytes()), 'lock_inode_preserved': True,
                       'mutation_replayed': False, 'source_sha256': sha(source)})
        result = dict(previous_owner=owner, terminal_receipt_sha256=sha(record_path.read_bytes()),
                      terminal_kind='RECONCILED_EFFECT', registry=str(registry), events=events,
                      prerequisites='SYNTHETIC_BASELINE_MAINTENANCE_START_CADDY_NOT_FULL_E2E',
                      native_terminal_action='REAL', ssh='NOT_USED', host_reboot='NOT_EXECUTED')
        create(evidence_dir / 'result.json', canonical(result))
        return result
    except BaseException as error:
        primary = error
        try:
            stderr = evidence_dir / 'closed-pipe.stderr'
            metadata = dict(status='FAILED', type=type(error).__name__,
                            reason=str(error) if isinstance(error, ValueError) else 'owned integration consumer failed',
                            source_sha256=sha(source), operation_id=op,
                            stderr_sha256=sha(stderr.read_bytes()) if stderr.exists() else None,
                            stderr_bytes=stderr.stat().st_size if stderr.exists() else None,
                            events=events, original_records_retained=True)
            create(evidence_dir / 'failure.json', canonical(metadata))
        except BaseException as diagnostics:
            secondary_failure(primary, 'diagnostics', diagnostics)
        raise
    finally:
        os.environ['PATH'] = saved_path
        if active is not None:
            try:
                active.terminate()
                active.wait(timeout=20)
            except BaseException as cleanup:
                # The caller's registry audit refuses cleanup without REAPED.
                fixture.reconciliation_cleanup_unproven = True
                secondary_failure(primary, 'supervisor cleanup', cleanup)
                if primary is None:
                    raise ValueError('OWNED_RECONCILIATION:supervisor lifetime unproven; retain fixture') from cleanup


def supervise(path):
    doc = json.loads(Path(path).read_bytes())
    bindings = load(ROOT / 'scripts/v126-operation-bindings.py', 'reconciliation_worker_bindings')
    env = {key: os.environ[key] for key in ('PATH', 'HOME')}
    env.update(doc['environment'])
    worker = ['bash', '-c', 'set -Eeuo pipefail; source "$1"; shift; remote_final_public_gates "$@"',
              'owned-real-final-public', doc['source'], *doc['args']]
    return bindings.binding_supervise(doc['target'], doc['identity'], worker, env=env, timeout=600,
                                      request_context=dict(args=doc['args'], environment=doc['environment']))


class PortableTests(unittest.TestCase):
    def test_actual_failure_handlers_preserve_primary_when_diagnostics_and_cleanup_fail(self):
        import ast
        import contextlib
        import io
        from types import SimpleNamespace
        tree = ast.parse(SELF.read_text())
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'run_connected')
        handler = next(node for node in function.body if isinstance(node, ast.Try) and node.finalbody)
        # Execute the actual handler/finally AST. Only the native fixture body is
        # replaced by a raised first error; no daemon or subprocess is started.
        handler.body = [ast.Raise(exc=ast.Name(id='first', ctx=ast.Load()), cause=None)]
        probe = ast.FunctionDef(name='probe', args=ast.arguments(posonlyargs=[], args=[], kwonlyargs=[],
                                kw_defaults=[], defaults=[]), body=[handler], decorator_list=[])
        module = ast.fix_missing_locations(ast.Module(body=[probe], type_ignores=[]))
        class HungSupervisor:
            def terminate(self): pass
            def wait(self, timeout): raise subprocess.TimeoutExpired('owned-fixture', timeout)
        for failure in ('write', 'read'):
            with self.subTest(diagnostic_failure=failure), tempfile.TemporaryDirectory(prefix='v126-hook-failure-') as temp:
                evidence = Path(temp)
                if failure == 'read': (evidence / 'closed-pipe.stderr').write_bytes(b'private fixture stderr')
                first = ValueError('OWNED_RECONCILIATION:first owned failure')
                fixture = SimpleNamespace()
                namespace = dict(globals(), first=first, primary=None, source=b'own source', op='a' * 64,
                                 events=[], evidence_dir=evidence, active=HungSupervisor(), fixture=fixture,
                                 saved_path=os.environ['PATH'])
                exec(compile(module, str(SELF), 'exec'), namespace)
                target = patch.object(Path, 'read_bytes', side_effect=OSError('synthetic unreadable')) if failure == 'read' else \
                         contextlib.nullcontext()
                # The extracted handler resolves globals from its own namespace.
                if failure == 'write': namespace['create'] = lambda *args: (_ for _ in ()).throw(OSError('synthetic unwritable'))
                with target, contextlib.redirect_stderr(io.StringIO()), self.assertRaises(ValueError) as caught:
                    namespace['probe']()
                self.assertIs(caught.exception, first)
                self.assertTrue(fixture.reconciliation_cleanup_unproven)
                self.assertEqual(len(first.__notes__), 2)
        # A cleanup failure without a prior error still refuses completion.
        handler.body = [ast.Pass()]
        probe.body = [ast.Assign(targets=[ast.Name(id='primary', ctx=ast.Store())], value=ast.Constant(None)), handler]
        module = ast.fix_missing_locations(ast.Module(body=[probe], type_ignores=[]))
        fixture = SimpleNamespace()
        namespace = dict(globals(), active=HungSupervisor(), fixture=fixture, saved_path=os.environ['PATH'])
        exec(compile(module, str(SELF), 'exec'), namespace)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaisesRegex(ValueError, 'supervisor lifetime unproven'):
            namespace['probe']()
        self.assertTrue(fixture.reconciliation_cleanup_unproven)

    def test_activation_reader_is_exact_source_bound(self):
        source = (ROOT / 'scripts/v126-cutover.sh').read_bytes()
        payload = activation_program(source)
        spec = dict(activation_program_sha256=sha(payload), activation_arguments=['/own/activation', 'run', 'a', 'b', 'c', 'd'],
                    activation_path='/own/mapped/activation', real_sudo='/own/sudo')
        args = ['python3', '-', *spec['activation_arguments']]
        command, actual = adapter_plan(spec, 'sudo', args, payload)
        self.assertEqual(command[4], '/own/mapped/activation')
        self.assertEqual(actual, payload)
        with self.assertRaises(ValueError): adapter_plan(spec, 'sudo', args, payload + b'\n')

    def test_telegram_maps_only_exact_synthetic_method_to_loopback(self):
        with tempfile.TemporaryDirectory(prefix='v126-telegram-idle.') as temp:
            root = Path(temp)
            config = root / 'curl.conf'
            config.write_text('silent\nshow-error\nfail\nurl = "https://api.telegram.org/bot81001:synthetic/getWebhookInfo"\n')
            config.chmod(0o600)
            spec = dict(telegram_token='81001:synthetic', guarded_curl='/own/guard', provider_ca='/own/ca', provider_port=24443)
            args = ['--disable', '--connect-timeout', '3', '--max-time', '10', '--config', str(config), '--output', str(root / 'output')]
            mapped = owned_telegram_plan(spec, args)
            self.assertIn('https://127.0.0.1:24443/bot81001:synthetic/getWebhookInfo', mapped)
            self.assertNotIn('--insecure', mapped)
            config.write_text(config.read_text().replace('getWebhookInfo', 'sendMessage'))
            with self.assertRaises(ValueError): owned_telegram_plan(spec, args)

    def test_provider_keeps_exact_token_host_and_read_only_loopback_boundary(self):
        import ast
        path = ROOT / 'scripts/fixtures/v126-linux-runtime/telegram-provider.py'
        tree = ast.parse(path.read_text())
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'allowed_api_target')
        namespace = {'TOKEN': '81001:synthetic'}
        exec(compile(ast.Module(body=[function], type_ignores=[]), str(path), 'exec'), namespace)
        allowed = namespace['allowed_api_target']
        for host, method, token, expected in [('127.0.0.1', 'getWebhookInfo', '81001:synthetic', True),
                                             ('127.0.0.1', 'getUpdates', '81001:synthetic', False),
                                             ('127.0.0.1', 'sendMessage', '81001:synthetic', False),
                                             ('127.0.0.1', 'getWebhookInfo', 'wrong', False),
                                             ('external.invalid', 'getWebhookInfo', '81001:synthetic', False),
                                             ('api.telegram.org', 'getUpdates', '81001:synthetic', True)]:
            with self.subTest(host=host, method=method, expected=expected):
                self.assertEqual(allowed(host, '/bot' + token + '/' + method), expected)


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == '--adapter':
        raise SystemExit(adapter(sys.argv[2], sys.argv[3], sys.argv[4:]))
    if len(sys.argv) > 1 and sys.argv[1] == '--supervise':
        raise SystemExit(supervise(sys.argv[2]))
    parser = argparse.ArgumentParser()
    parser.add_argument('--self-test', action='store_true', required=True)
    parser.parse_args()
    raise SystemExit(0 if unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PortableTests)).wasSuccessful() else 1)
