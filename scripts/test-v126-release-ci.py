#!/usr/bin/env python3
"""Local-only regressions of the sourced sequencer CI validator and receipt consumer."""
import copy
import ast
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parent
SCRIPT = ROOT / 'v126-cutover.sh'
HARNESS = ROOT / 'test-v126-cutover.sh'
# Projection of confirmed main CI 34146214650, attempt 1 (steps are not job gates).
GOOD = {'attempt': 1,
 'conclusion': 'success',
 'databaseId': 34146214650,
 'event': 'push',
 'headBranch': 'main',
 'headSha': '8436ee7b219ca6074aceecc47d6bf5f24803621d',
 'jobs': [{'conclusion': 'success', 'name': 'miniapp (20)', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'backend-telegram-lightweight (21)', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'miniapp-e2e-smoke (20)', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'backend-ktlint (21)', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'backend-migration-sanity (21)', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'backend-release-critical-routes (21)', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'backend-compile (21)', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'docker (backend)', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'backend-venue-booking-rbac (21)', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'backend-archive-reproducibility (21)', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'compose', 'status': 'completed'},
          {'conclusion': 'success', 'name': 'backend', 'status': 'completed'}],
 'status': 'completed',
 'workflowDatabaseId': 230370033,
 'workflowName': 'CI'}


def shell(body, *args):
    return subprocess.run(['bash', '-c', 'set -Eeuo pipefail\n' + body, 'fixture', *map(str, args)],
                          capture_output=True, text=True, timeout=30)


def write_json(path, doc, mode=0o600):
    path.write_text(json.dumps(doc, sort_keys=True, separators=(',', ':')) + '\n')
    path.chmod(mode)


def seed_ci(state):
    """Synthetic chain fixture: use the real proof writer, never a copied verifier."""
    manifest = json.loads((state / 'run.json').read_text())
    doc = copy.deepcopy(GOOD)
    doc.update(headSha=manifest['release_sha'], databaseId=manifest['main_actions_run_id'])
    actions = state / 'artifacts/main-actions.json'
    write_json(actions, doc, 0o400)
    proof = state / 'tmp/fixture-local-baseline.proof'
    result = shell('source "$1"; STATE_DIR="$2"; write_local_baseline_proof "$3" "$4"',
                   SCRIPT, state, proof, actions)
    if result.returncode:
        raise RuntimeError(result.stderr)
    hashes = {'main-actions': hashlib.sha256(actions.read_bytes()).hexdigest(),
              'local-baseline': hashlib.sha256(proof.read_bytes()).hexdigest()}
    proof.unlink()
    return hashes


def closure_invocation(name, runner):
    """Interpret only the literal one-command fixture steps; never execute sudo."""
    workflow = (ROOT.parent / '.github/workflows/ci.yml').read_text()
    body = workflow.split('      - name: ' + name + '\n', 1)[1]
    body = re.split(r'^      - ', body, maxsplit=1, flags=re.M)[0]
    run = body.split('        run: |\n', 1)[1]
    lines = run.splitlines()
    if not all(line.startswith('          ') for line in lines):
        raise AssertionError('fixture invocation has unsupported YAML structure')
    command = '\n'.join(line[10:] for line in lines).replace('\\\n', ' ')
    argv = shlex.split(command)
    environment = dict(runner)
    uid = 1001
    if argv[:3] == ['sudo', '-n', 'env']:
        uid, environment, argv = 0, {}, argv[3:]
        while argv and re.fullmatch('[A-Z_]+=.*', argv[0]):
            key, value = argv.pop(0).split('=', 1)
            if value.startswith('$'):
                value = runner[value[1:]]
            environment[key] = value
    if argv[:2] != ['python3', 'scripts/v126-test-endpoint-guard.py'] or argv[2] != '--run' or argv[4] != '--':
        raise AssertionError('native fixture must retain actual endpoint guard invocation')
    return uid, environment, argv[5:]


def fixture_module(name):
    path = ROOT / name
    specification = importlib.util.spec_from_file_location(name.removesuffix('.py').replace('-', '_'), path)
    module = importlib.util.module_from_spec(specification)
    sys.modules[specification.name] = module
    specification.loader.exec_module(module)
    return module


class ReleaseCiTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ht12w-ci-')
        self.root = Path(self.temp.name)
        self.actions = self.root / 'actions.json'

    def tearDown(self):
        self.temp.cleanup()

    def validate(self, doc=None, raw=None):
        if raw is None:
            write_json(self.actions, GOOD if doc is None else doc)
        else:
            self.actions.write_bytes(raw)
            self.actions.chmod(0o600)
        return shell('source "$1"; RELEASE_SHA="$3"; MAIN_ACTIONS_RUN_ID="$4"; '
                     'validate_main_actions_run "$2"; echo PASS',
                     SCRIPT, self.actions, GOOD['headSha'], GOOD['databaseId'])

    def refused(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('PASS', result.stdout)

    def test_confirmed_set_and_order_independence(self):
        self.assertEqual(self.validate().returncode, 0)
        doc = copy.deepcopy(GOOD)
        doc['jobs'].reverse()
        self.assertEqual(self.validate(doc).returncode, 0)

    def test_workflow_and_tracked_contract_agree(self):
        code = shell('source "$1"; release_ci_python', SCRIPT)
        self.assertEqual(code.returncode, 0, code.stderr)
        namespace = {}
        exec(compile(code.stdout, str(SCRIPT), 'exec'), namespace)
        workflow = (ROOT.parent / '.github/workflows/ci.yml').read_text()
        self.assertTrue(workflow.startswith('name: CI\n'))
        # Deliberately bounded to this workflow's literal, single-axis matrices.
        # A new syntax/name/matrix needs explicit contract review, never silent expansion.
        sections = re.split(r'^  ([a-z][a-z0-9-]+):\n', workflow.split('\njobs:\n', 1)[1], flags=re.M)
        names = []
        for job, body in zip(sections[1::2], sections[2::2]):
            header = body.split('    steps:\n', 1)[0]
            self.assertNotRegex(header, r'^    (?:name|uses):', msg=job)
            if '      matrix:\n' in header:
                matrix = header.split('      matrix:\n', 1)[1].strip()
                match = re.fullmatch(r'(?:java|node|target): \[([a-z0-9]+)\]', matrix)
                self.assertIsNotNone(match, f'unsupported CI matrix: {job}')
                job += f' ({match.group(1)})'
            else:
                self.assertNotIn('matrix:', header)
            names.append(job)
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(sorted(names), list(namespace['CI_REQUIRED_JOBS']))
        self.assertEqual(sorted(names), sorted(job['name'] for job in GOOD['jobs']))
        self.assertEqual(len(names), 12)

    def test_incomplete_duplicate_substituted_and_unexpected_sets(self):
        variants = []
        for index in range(12):
            doc = copy.deepcopy(GOOD); del doc['jobs'][index]; variants.append(doc)
        doc = copy.deepcopy(GOOD); doc['jobs'][-1] = doc['jobs'][0]; variants.append(doc)
        doc = copy.deepcopy(GOOD); doc['jobs'][-1]['name'] = 'substituted'; variants.append(doc)
        doc = copy.deepcopy(GOOD); doc['jobs'].append({'name':'unexpected','status':'completed','conclusion':'success'}); variants.append(doc)
        doc = copy.deepcopy(GOOD)
        for index, job in enumerate(doc['jobs']): job['name'] = f'arbitrary-{index}'
        variants.append(doc)
        for index, doc in enumerate(variants):
            with self.subTest(index=index): self.refused(self.validate(doc))

    def test_closure_consumers_are_mandatory_hosted_compose_steps(self):
        workflow = (ROOT.parent / '.github/workflows/ci.yml').read_text()
        compose = workflow.split('\n  compose:\n', 1)[1].split('\n  miniapp:\n', 1)[0]
        self.assertIn('    runs-on: ubuntu-latest\n', compose)
        self.assertNotIn('continue-on-error:', compose)
        self.assertIn('scripts/test-v126-cutover.sh', compose)
        self.assertIn('--require-linux-ssh --before-supervisor-source', compose)
        harness = HARNESS.read_text().split('\nmain() {\n', 1)[1]
        self.assertIn('show 09e19461cf54376714ae51f2d4c9e480f8365d8e:scripts/v126-cutover.sh', harness)
        self.assertIn('--before-supervisor-source "${TEST_ROOT}/v126-supervisor-before.sh"', harness)
        ordered = ('scripts/test-v126-linux-runtime.py --require-hosted-ci',
                   'scripts/test-v126-systemd-linux.py --require-hosted-systemd',
                   'scripts/test-v126-ordinary-deploy.py --require-linux-integration',
                   'scripts/test-v126-docker-daemon-linux.py --require-hosted-daemon')
        positions = [compose.index(item) for item in ordered]
        self.assertEqual(positions, sorted(positions))
        self.assertEqual(compose.count('RUNNER_ENVIRONMENT=github-hosted'), 2)
        self.assertIn('    timeout-minutes: 60\n', compose)
        self.assertIn('"${SCRIPT_DIR}/test-v126-docker-daemon-linux.py" --self-test', harness)
        self.assertIn('contents: read', workflow.split('\njobs:\n', 1)[0])
        self.assertNotRegex(workflow, r'(?m)^\s*(?:environment|workflow_run|secrets|continue-on-error):')

    def test_standalone_workflow_invocation_passes_actual_hosted_coordinator_guard(self):
        runner = dict(PATH=os.environ['PATH'], RUNNER_TEMP=str(self.root), GITHUB_ACTIONS='true',
                      RUNNER_ENVIRONMENT='github-hosted', RUNNER_OS='Linux')
        uid, environment, argv = closure_invocation('Exercise owned real systemd Caddy interruption and active configuration', runner)
        self.assertEqual(argv[:3], ['python3', 'scripts/test-v126-systemd-linux.py', '--require-hosted-systemd'])
        systemd = fixture_module('test-v126-systemd-linux.py')
        # Virtual Linux platform files only; this calls the real unmodified guard.
        read_text, is_dir, is_file = Path.read_text, Path.is_dir, Path.is_file
        with patch.object(systemd.sys, 'platform', 'linux'), patch.object(systemd.os, 'geteuid', return_value=uid), \
             patch.dict(os.environ, environment, clear=True), \
             patch.object(Path, 'read_text', lambda path, *a, **kw: 'systemd\n' if str(path) == '/proc/1/comm' else read_text(path, *a, **kw)), \
             patch.object(Path, 'is_dir', lambda path: True if str(path) == '/run/systemd/system' else is_dir(path)), \
             patch.object(Path, 'is_file', lambda path: True if str(path) == '/sys/fs/cgroup/cgroup.controllers' else is_file(path)):
            systemd.hosted_only()

    def test_ordinary_workflow_invocation_passes_actual_guard_and_fixture_temp_binding(self):
        runner = dict(PATH=os.environ['PATH'], RUNNER_TEMP=str(self.root), GITHUB_ACTIONS='true',
                      RUNNER_ENVIRONMENT='github-hosted', RUNNER_OS='Linux')
        uid, environment, argv = closure_invocation('Exercise reconciled terminal and ordinary deploy shared target lifecycle', runner)
        self.assertEqual(argv[:3], ['python3', 'scripts/test-v126-ordinary-deploy.py', '--require-linux-integration'])
        ordinary = fixture_module('test-v126-ordinary-deploy.py')
        systemd = fixture_module('test-v126-systemd-linux.py')
        tree = ast.parse((ROOT / 'test-v126-ordinary-deploy.py').read_text())
        function = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'linux_integration')
        guard = next(node for node in function.body if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call))
        # Execute the actual leading root/hosted guard, then the actual constructor.
        # Tool lookup and unused port allocation are explicit no-daemon leaf mocks.
        with patch.object(sys, 'platform', 'linux'), patch.object(os, 'geteuid', return_value=uid), \
             patch.dict(os.environ, environment, clear=True), \
             patch.object(systemd.shutil, 'which', side_effect=lambda name: '/owned-unused-tools/' + name), \
             patch.object(systemd.Fixture, 'port', side_effect=[24419, 24480]):
            exec(compile(ast.Module(body=[guard], type_ignores=[]), str(ROOT / 'test-v126-ordinary-deploy.py'), 'exec'), vars(ordinary))
            fixture = systemd.Fixture(self.root / 'ordinary-coordinator-evidence')
            self.assertEqual(fixture.root.parent, self.root.resolve())
            self.assertFalse(fixture.unit_installed)

    def test_daemon_workflow_invocation_passes_actual_hosted_root_guard(self):
        runner = dict(PATH=os.environ['PATH'], RUNNER_TEMP=str(self.root), GITHUB_ACTIONS='true',
                      RUNNER_ENVIRONMENT='github-hosted', RUNNER_OS='Linux')
        uid, environment, argv = closure_invocation('Exercise isolated Docker daemon interruption and blocked replay', runner)
        self.assertEqual(argv[:3], ['python3', 'scripts/test-v126-docker-daemon-linux.py', '--require-hosted-daemon'])
        self.assertEqual(uid, 0)
        self.assertEqual(environment['RUNNER_TEMP'], str(self.root))
        daemon = fixture_module('test-v126-docker-daemon-linux.py')
        # Only platform files are synthetic; the actual guard and workflow argv run
        # together without launching Docker, systemd, sockets or privileged commands.
        read_text, is_dir, is_file = Path.read_text, Path.is_dir, Path.is_file
        with patch.object(daemon.sys, 'platform', 'linux'), patch.object(daemon.os, 'geteuid', return_value=uid), \
             patch.dict(os.environ, environment, clear=True), \
             patch.object(Path, 'read_text', lambda path, *a, **kw: 'systemd\n' if str(path) == '/proc/1/comm' else read_text(path, *a, **kw)), \
             patch.object(Path, 'is_dir', lambda path: True if str(path) == '/run/systemd/system' else is_dir(path)), \
             patch.object(Path, 'is_file', lambda path: True if str(path) == '/sys/fs/cgroup/cgroup.controllers' else is_file(path)):
            daemon.hosted_only()

    def test_each_job_must_complete_successfully(self):
        for index in range(12):
            for field, value in [('conclusion','failure'),('conclusion','skipped'),('status','in_progress')]:
                with self.subTest(index=index, field=field, value=value):
                    doc = copy.deepcopy(GOOD); doc['jobs'][index][field] = value
                    self.refused(self.validate(doc))

    def test_exact_run_fields_and_missing_values(self):
        wrong = dict(databaseId=GOOD['databaseId']+1, workflowName='Other', workflowDatabaseId=1,
                     event='pull_request', headBranch='feature', headSha='f'*40, attempt=2,
                     status='in_progress', conclusion='failure')
        for field, value in wrong.items():
            for missing in (False, True):
                with self.subTest(field=field, missing=missing):
                    doc = copy.deepcopy(GOOD)
                    if missing: del doc[field]
                    else: doc[field] = value
                    self.refused(self.validate(doc))
        for value in (True, '1', 1.0):
            doc = copy.deepcopy(GOOD); doc['attempt'] = value; self.refused(self.validate(doc))

    def test_malformed_and_incomplete_json(self):
        for raw in (b'', b'{', b'null', b'[]', b'{}', b'\xff', b'{"jobs":[],"jobs":[]}'):
            with self.subTest(raw=raw): self.refused(self.validate(raw=raw))
        for jobs in (None, {}, [None], [1], ['name'], [{'name':[]}], [{'name':None}]):
            doc = copy.deepcopy(GOOD); doc['jobs'] = jobs; self.refused(self.validate(doc))
        for field in ('name','status','conclusion'):
            doc = copy.deepcopy(GOOD); del doc['jobs'][0][field]; self.refused(self.validate(doc))

    def test_command_failure_cannot_emit_baseline_pass(self):
        write_json(self.actions, GOOD)
        (self.root / 'tmp').mkdir()
        # Bounded consumers execute subprocesses: use actual executable fixtures,
        # never a shell function that could fall through to the user's real gh.
        fakebin = self.root / 'bin'
        fakebin.mkdir()
        gh = fakebin / 'gh'
        gh.write_text('#!' + sys.executable + '\n' + "import os,pathlib,sys\nexpected=['run','view',os.environ['FIXTURE_RUN_ID'],'--repo','koteev-m/hookah_bot','--json','databaseId,workflowName,workflowDatabaseId,event,headBranch,headSha,attempt,status,conclusion,jobs']\nif sys.argv[1:] != expected: raise SystemExit(98)\nsys.stdout.buffer.write(pathlib.Path(os.environ['FIXTURE_ACTIONS']).read_bytes())\nraise SystemExit(23)\n")
        gh.chmod(0o700)
        docker = fakebin / 'docker'
        docker.write_text('#!/bin/sh\necho UNEXPECTED_IMAGE_CALL\nexit 99\n')
        docker.chmod(0o700)
        body = r'''
source "$1"
STATE_DIR="$2"; RELEASE_WORKTREE="$2"; RELEASE_SHA="$4"; MAIN_ACTIONS_RUN_ID="$5"
RELEASE_TREE=tree; RELEASE_PARENTS=parents; SCRIPT_SHA256=script
require_cmd() { :; }
release_git() {
  case "$*" in
    *'status --porcelain'*) : ;;
    *'^{tree}'*) echo tree ;;
    *'show -s'*) echo parents ;;
    *'rev-parse'*) echo "$RELEASE_SHA" ;;
    *'fetch --no-tags'*) : ;;
    *) return 97 ;;
  esac
}
git_object_sha256() { echo script; }
export PATH="$2/bin:$PATH" FIXTURE_ACTIONS="$3" FIXTURE_RUN_ID="$5"
if verify_release_baseline_local; then echo PASS; else exit 1; fi
'''
        result = shell(body, SCRIPT, self.root, self.actions, GOOD['headSha'], GOOD['databaseId'])
        self.refused(result)
        self.assertIn('main Actions evidence command failed', result.stderr)
        self.assertNotIn('ARTIFACT', result.stdout)
        self.assertNotIn('UNEXPECTED_IMAGE_CALL', result.stdout)

    def test_downstream_receipt_rechecks_ci_and_new_proof(self):
        (self.root / 'release-worktree').mkdir()
        result = shell('source "$1"; TEST_ROOT="$2"; new_state "$CUTOVER_SCRIPT" ci-proof; '
                       'seed_chain "$NEW_STATE" 1', HARNESS, self.root)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        state = self.root / 'state-ci-proof'
        receipt = state / 'receipts/01-BASELINE_VERIFIED.receipt.json'
        operation = state / 'artifacts/1-BASELINE_VERIFIED.operation.log'
        actions = state / 'artifacts/main-actions.json'
        original = {p:p.read_bytes() for p in (receipt, Path(str(receipt)+'.sha256'), operation, actions)}

        def verify():
            return shell('source "$1"; load_state "$2"; verify_receipt BASELINE_VERIFIED; echo PASS', SCRIPT, state)

        self.assertEqual(verify().returncode, 0)
        manifest = json.loads((state / 'run.json').read_text())
        proof = state / 'tmp/proof'
        self.assertEqual(shell('source "$1"; STATE_DIR="$2"; write_local_baseline_proof "$3" "$4"',
                               SCRIPT, state, proof, actions).returncode, 0)
        good_proof = proof.read_bytes()
        self.assertIn(b'main_actions_jobs=12/12\n', good_proof)
        self.assertIn(b'main_actions_contract_sha256=', good_proof)
        for mutation in ('old-proof','forged-proof','forged-ci','missing-ci','unsealed-ci','symlink-ci'):
            with self.subTest(mutation=mutation):
                doc = json.loads(original[receipt])
                if mutation == 'old-proof':
                    forged = good_proof.replace(b'main_actions_jobs=12/12', b'main_actions_jobs=11/11')
                elif mutation == 'forged-proof':
                    forged = good_proof.replace(b'main_actions_contract_sha256=', b'forged_contract_sha256=')
                else: forged = good_proof
                for item in doc['artifacts']:
                    if item['name'] == 'local-baseline': item['sha256'] = hashlib.sha256(forged).hexdigest()
                if mutation == 'forged-ci':
                    ci = json.loads(original[actions]); ci['jobs'][-1]['name'] = 'forged'
                    actions.chmod(0o600); write_json(actions, ci, 0o400)
                    digest = hashlib.sha256(actions.read_bytes()).hexdigest()
                    for item in doc['artifacts']:
                        if item['name'] == 'main-actions': item['sha256'] = digest
                if mutation == 'missing-ci': actions.unlink()
                if mutation == 'unsealed-ci': actions.chmod(0o600)
                if mutation == 'symlink-ci':
                    actions.unlink(); actions.symlink_to(self.actions)
                # Rehash BOTH the log and receipt: schema/hash-only verification would accept these.
                operation.chmod(0o600)
                operation.write_text(''.join(f"ARTIFACT\t{i['name']}\t{i['sha256']}\n" for i in doc['artifacts'] if i['name']!='operation-log'))
                operation.chmod(0o400)
                for item in doc['artifacts']:
                    if item['name']=='operation-log': item['sha256']=hashlib.sha256(operation.read_bytes()).hexdigest()
                receipt.chmod(0o600); write_json(receipt, doc, 0o400)
                checksum = Path(str(receipt)+'.sha256'); checksum.chmod(0o600)
                checksum.write_text(hashlib.sha256(receipt.read_bytes()).hexdigest()+'\n'); checksum.chmod(0o400)
                self.refused(verify())
                for path, raw in original.items():
                    if path.is_symlink(): path.unlink()
                    if path.exists(): path.chmod(0o600)
                    path.write_bytes(raw); path.chmod(0o400)
        self.assertEqual(verify().returncode, 0)

    def test_real_prerequisite_preserves_metadata_that_root_cutover_rejects(self):
        spec = importlib.util.spec_from_file_location('prerequisite', ROOT / 'v126-staging-prerequisite-sync-helper.py')
        helper = importlib.util.module_from_spec(spec)
        with patch.object(sys, 'dont_write_bytecode', True):
            spec.loader.exec_module(helper)
        source = self.root / 'release-source'
        source.write_bytes(b'synthetic release bytes\n')
        original_lstat = Path.lstat
        for mode in ('0644', '0755'):
            target = self.root / ('installed-' + mode)
            target.write_bytes(b'previous source\n')

            def observed_lstat(path, *args, **kwargs):
                info = original_lstat(path, *args, **kwargs)
                if path == target:
                    return SimpleNamespace(st_mode=info.st_mode, st_uid=501, st_gid=0)
                return info

            # Only OS ownership observations/writes are synthetic; production atomic_install
            # still performs and verifies its real local byte/mode replacement and fsync.
            with patch.object(Path, 'lstat', observed_lstat), patch.object(helper.os, 'fchown') as chown:
                with contextlib.redirect_stdout(io.StringIO()):
                    helper.atomic_install(['atomic-install', str(source), str(target), mode, '501', '0', 'replace'])
                chown.assert_called_once()
                self.assertEqual(chown.call_args.args[1:], (501, 0))
            self.assertEqual(target.read_bytes(), source.read_bytes())
            result = shell('source "$1"; stat() { printf "%s:501:root\\n" "$fixture_mode"; }; '
                           'id() { echo root; }; fixture_mode="$3"; '
                           'remote_require_operator_file "$2" "$fixture_mode"; echo PASS',
                           SCRIPT, target, mode.lstrip('0'))
            self.refused(result)
            self.assertIn('operator file ownership or mode mismatch', result.stderr)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--seed-state':
        print(json.dumps(seed_ci(Path(sys.argv[2])), sort_keys=True))
    elif len(sys.argv) == 3 and sys.argv[1] == '--emit-state':
        for name, digest in sorted(seed_ci(Path(sys.argv[2])).items()):
            print(f'ARTIFACT\t{name}\t{digest}')
    else:
        unittest.main()
