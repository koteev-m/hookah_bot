#!/usr/bin/env python3
"""Portable original-evidence/observer contract tests; no daemon or network.

Observer protocol tests mock only the external read-only observation boundary.
Resource equality tests execute the real collector Bash consumer with explicit
synthetic Docker/Compose read leaves. Native source guards run in Linux fixtures.
"""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('poststate', HERE / 'v126-reconcile-poststate.py')
poststate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(poststate)
SOURCE = (HERE / 'v126-cutover.sh').read_bytes()


class PoststateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='v126-poststate-test-')
        self.target = Path(self.temp.name).resolve()
        self.root = self.target / '.v126-target-operations'
        self.root.mkdir(mode=0o700)
        self.namespace = self.target / '.v126-runs'
        self.namespace.mkdir(mode=0o700)
        self.run = self.namespace / 'synthetic-run'
        self.run.mkdir(mode=0o700)
        self.identity = dict(run_id='synthetic-run', release_sha='a' * 40,
                             script_sha256=poststate.digest(SOURCE), intent_sha256='b' * 64,
                             kind='STAGE', name='V126_BACKEND_STARTED', action='start-v126')
        self.cid, self.image = 'c' * 64, 'sha256:' + 'd' * 64
        self.request = dict(format_version=1, identity=self.identity,
                            target_sha256=poststate.digest(str(self.target).encode()),
                            args=[str(self.target), 'synthetic-run', 'a' * 40, 'synthetic:' + 'a' * 40,
                                  self.image, 'first'],
                            environment={'V126_INTERNAL_REMOTE_V126_IMAGE_ID': self.image})
        self.proof_name = 'v126-backend-first-started'
        self.proof_fields = dict(run_id='synthetic-run', release_sha='a' * 40, phase='first',
                                 image_tag=self.request['args'][3], image_id=self.image,
                                 backend_container_id=self.cid, start_command_count='1',
                                 restart_policy='no', restart_count='0', result='PASS')
        self.proof(self.proof_name, self.proof_fields)
        self.operations = [self.record(self.identity, self.request, [self.proof_name])]

    def tearDown(self):
        self.temp.cleanup()

    def write(self, path, raw, mode):
        if path.exists():
            path.chmod(0o600)
        path.write_bytes(raw)
        path.chmod(mode)

    def proof(self, name, fields):
        raw = ''.join(key + '=' + value + '\n' for key, value in fields.items()).encode()
        self.write(self.run / (name + '.proof'), raw, 0o600)
        self.write(self.run / (name + '.proof.sha256'), (poststate.digest(raw) + '\n').encode(), 0o600)

    def record(self, identity, request, names, *, exit_code=0, children='REAPED', extra_log=b''):
        op = poststate.digest(poststate.canonical(identity))
        start = dict(identity=identity, operation_id=op, started_at='2026-09-10T00:00:00Z', boot_id='synthetic-boot')
        log = b''.join(('ARTIFACT\t' + name + '\t' +
                       poststate.digest((self.run / (name + '.proof')).read_bytes()) + '\n').encode() for name in names) + extra_log
        result = dict(identity=identity, operation_id=op, exit=exit_code,
                      outcome='SUCCEEDED' if exit_code == 0 else 'FAILED', children=children,
                      log_sha256=poststate.digest(log), completed_at='2026-09-10T00:00:01Z')
        files = {}
        for suffix, raw in [('.start.json', poststate.canonical(start)), ('.request.json', poststate.canonical(request)),
                            ('.result.json', poststate.canonical(result)), ('.log', log)]:
            path = self.root / (op + suffix)
            self.write(path, raw, 0o400)
            files[path.name] = poststate.digest(raw)
        return dict(operation_id=op, files=files)

    def collect(self):
        return poststate.collect(self.target, self.identity, SOURCE, self.request, self.operations)

    def snapshot(self):
        return {str(path.relative_to(self.target)): path.read_bytes()
                for path in self.target.rglob('*') if path.is_file()}

    def test_success_result_uses_original_exact_resource_and_preserves_every_record(self):
        before = self.snapshot()
        with patch.object(poststate, 'run_observer', return_value={'mock': 'read-only boundary'}) as observe:
            result = self.collect()
        self.assertEqual(result['outcome'], 'EXACT_COMPLETED_EFFECT')
        self.assertFalse(result['retry_allowed'])
        self.assertEqual(observe.call_args.args[-1], self.cid)
        self.assertEqual(self.snapshot(), before)
        calls = observe.call_args.args[-2]
        joined = '\n'.join(command for _, command in calls)
        self.assertIn('remote_wait_backend_ready', joined)
        self.assertIn('remote_assert_runtime', joined)
        self.assertNotIn('remote_start_v126 ', joined)
        self.assertNotIn('docker start ', joined)

    def test_original_nonzero_and_unknown_children_refuse_even_if_current_would_match(self):
        for exit_code, children in [(1, 'REAPED'), (75, 'REAPED'), (0, 'UNKNOWN')]:
            with self.subTest(exit=exit_code, children=children):
                self.operations = [self.record(self.identity, self.request, [self.proof_name],
                                               exit_code=exit_code, children=children)]
                with patch.object(poststate, 'run_observer') as observe:
                    with self.assertRaisesRegex(ValueError, 'UNKNOWN_original_result_or_children'):
                        self.collect()
                    observe.assert_not_called()

    def test_missing_new_source_container_identity_is_insufficient(self):
        del self.proof_fields['backend_container_id']
        self.proof(self.proof_name, self.proof_fields)
        self.operations = [self.record(self.identity, self.request, [self.proof_name])]
        with patch.object(poststate, 'run_observer') as observe:
            with self.assertRaisesRegex(ValueError, 'original_exact_start_resource'):
                self.collect()
            observe.assert_not_called()

    def test_changed_proof_with_matching_sidecar_cannot_replace_original_artifact(self):
        self.proof_fields['backend_container_id'] = 'e' * 64
        self.proof(self.proof_name, self.proof_fields)
        with self.assertRaisesRegex(ValueError, 'original_proof_digest'):
            self.collect()

    def test_changed_original_log_request_or_source_refuses_before_observation(self):
        original_source = SOURCE + b'\n'
        with self.assertRaisesRegex(ValueError, 'source_binding'):
            poststate.collect(self.target, self.identity, original_source, self.request, self.operations)
        changed = json.loads(json.dumps(self.request))
        changed['args'][4] = 'sha256:' + 'e' * 64
        with self.assertRaisesRegex(ValueError, 'last_original_operation_binding'):
            poststate.collect(self.target, self.identity, SOURCE, changed, self.operations)
        log = self.root / (self.operations[0]['operation_id'] + '.log')
        self.write(log, log.read_bytes() + b'new observation\n', 0o400)
        with self.assertRaisesRegex(ValueError, 'original_log_digest'):
            self.collect()

    def test_duplicate_artifact_line_and_unprotected_or_symlink_record_refuse(self):
        original = (self.root / (self.operations[0]['operation_id'] + '.log')).read_bytes()
        self.operations = [self.record(self.identity, self.request, [self.proof_name], extra_log=original)]
        with self.assertRaisesRegex(ValueError, 'duplicate_original_artifact'):
            self.collect()
        self.operations = [self.record(self.identity, self.request, [self.proof_name])]
        path = self.root / (self.operations[0]['operation_id'] + '.result.json')
        path.chmod(0o600)
        with self.assertRaisesRegex(ValueError, 'protected_file_metadata'):
            self.collect()
        path.unlink()
        path.symlink_to('/dev/null')
        with self.assertRaises(OSError):
            self.collect()

    def test_observer_refusal_and_post_observation_drift_never_return_completion(self):
        with patch.object(poststate, 'run_observer', side_effect=ValueError('INSUFFICIENT_EVIDENCE:wrong_identity')):
            with self.assertRaisesRegex(ValueError, 'wrong_identity'):
                self.collect()
        def drift(*unused):
            self.proof_fields['backend_container_id'] = 'e' * 64
            self.proof(self.proof_name, self.proof_fields)
            return {'mock': 'read-only boundary'}
        with patch.object(poststate, 'run_observer', side_effect=drift):
            with self.assertRaisesRegex(ValueError, 'original_proof_digest'):
                self.collect()

    def test_all_original_action_classes_have_explicit_implementation_or_refusal(self):
        self.assertEqual(len(poststate.ACTIONS), 21)
        self.assertEqual(poststate.ACTIONS, poststate.IMPLEMENTED | poststate.UNSUPPORTED_REASONS.keys())
        for action in poststate.UNSUPPORTED_REASONS:
            with self.subTest(action=action):
                identity = dict(self.identity, action=action)
                request = dict(self.request, identity=identity)
                with self.assertRaisesRegex(ValueError, 'unsupported_action_' + action):
                    poststate.collect(self.target, identity, SOURCE, request, self.operations)

    def test_terminal_requires_original_final_start_and_restore_artifacts(self):
        identity = dict(self.identity, name='FINAL_PUBLIC_GATES_PASSED', action='final-public-gates')
        request = dict(self.request, identity=identity, args=self.request['args'][:5])
        fields = dict(run_id=identity['run_id'], release_sha=identity['release_sha'], result='PASS')
        self.proof('final-public-gates', fields)
        operations = [self.record(identity, request, ['final-public-gates'])]
        with self.assertRaisesRegex(ValueError, 'missing_original_artifact_v126-backend-final-started'):
            poststate.collect(self.target, identity, SOURCE, request, operations)

    def test_actual_bash_resource_consumer_refuses_replacement_duplicate_image_and_restart(self):
        # Explicit dependency mocks output only own synthetic Docker inventory;
        # the tested equality consumer is the production collector's exact body.
        for label, running, all_ids, image, restart, expected in [
                ('exact', self.cid, self.cid, self.image, 'no:0', 0),
                ('replacement', 'e' * 64, 'e' * 64, self.image, 'no:0', 1),
                ('duplicate', self.cid + ' ' + 'e' * 64, self.cid, self.image, 'no:0', 1),
                ('wrong-image', self.cid, self.cid, 'sha256:' + 'e' * 64, 'no:0', 1),
                ('restarted', self.cid, self.cid, self.image, 'no:1', 1)]:
            with self.subTest(case=label):
                program = 'set -Eeuo pipefail\n' + poststate.READ_ONLY_HELPERS + r'''
remote_capture_compose_ids() {
  if [[ "$1" == running ]]; then read -ra REMOTE_CAPTURED_CONTAINER_IDS <<< "$SYNTHETIC_RUNNING";
  else read -ra REMOTE_CAPTURED_CONTAINER_IDS <<< "$SYNTHETIC_ALL"; fi
}
docker() {
  [[ "$1" == inspect && "$2" == --format && "$4" == "$SYNTHETIC_EXPECTED" ]] || exit 91
  printf '%s:%s\n' "$SYNTHETIC_IMAGE" "$SYNTHETIC_RESTART"
}
reconcile_exact_backend "$SYNTHETIC_EXPECTED" "$SYNTHETIC_WANTED_IMAGE"
'''
                result = subprocess.run(['bash', '-c', program], capture_output=True, timeout=5,
                                        env={**os.environ, 'SYNTHETIC_RUNNING': running, 'SYNTHETIC_ALL': all_ids,
                                             'SYNTHETIC_IMAGE': image, 'SYNTHETIC_RESTART': restart,
                                             'SYNTHETIC_EXPECTED': self.cid, 'SYNTHETIC_WANTED_IMAGE': self.image})
                self.assertEqual(result.returncode == 0, expected == 0, result.stderr.decode())

    def test_observer_requires_complete_structured_output_even_with_exit_zero(self):
        evidence = poststate.Evidence(self.target, self.identity, self.request, self.operations)
        calls, expected_id = poststate.observer_plan(evidence, self.request)
        with patch.object(poststate.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, b'', b'')):
            with self.assertRaisesRegex(ValueError, 'structured_current_postconditions'):
                poststate.run_observer(SOURCE, self.request, evidence, calls, expected_id)

    def test_actual_saved_image_validator_reads_private_snapshot_and_preserves_sealed_archive(self):
        # Only Docker/Compose inventory is synthetic. Archive parsing, source
        # snapshotting, metadata/hash checks and the reconciliation caller are real.
        tag = 'synthetic:' + 'a' * 40
        layer = b'owned synthetic layer'
        config = poststate.canonical(dict(architecture='amd64', os='linux', config=dict(User='appuser', Labels={
            'org.opencontainers.image.revision': 'a' * 40,
            'org.opencontainers.image.source': 'https://github.com/koteev-m/hookah_bot'}),
            rootfs=dict(type='layers', diff_ids=['sha256:' + poststate.digest(layer)])))
        image = 'sha256:' + poststate.digest(config)
        archive = self.run / 'v126-image.tar'
        manifest = poststate.canonical([dict(Config=image[7:] + '.json', RepoTags=[tag], Layers=['layer.tar'])])
        with tarfile.open(archive, 'w') as handle:
            for name, raw in [('manifest.json', manifest), (image[7:] + '.json', config), ('layer.tar', layer)]:
                member = tarfile.TarInfo(name)
                member.size, member.mode = len(raw), 0o600
                handle.addfile(member, io.BytesIO(raw))
        raw = archive.read_bytes()
        archive.chmod(0o400)
        info = archive.stat()
        before = (info.st_ino, info.st_mode, info.st_nlink, raw)
        private = self.target / 'private-snapshot'
        private.mkdir(mode=0o700)
        program = 'set -Eeuo pipefail\nsource "$1"\n' + poststate.READ_ONLY_HELPERS + r'''
stat() {
  [[ "$1" == -c && "$2" == '%a:%U:%G' && "$#" == 3 ]] || exit 91
  python3 - "$3" <<'PY'
import grp,os,pwd,stat,sys
value=os.stat(sys.argv[1],follow_symlinks=False)
print(format(stat.S_IMODE(value.st_mode),'o')+':'+pwd.getpwuid(value.st_uid).pw_name+':'+grp.getgrgid(value.st_gid).gr_name)
PY
}
docker() {
  [[ "$*" == "image inspect --format {{.Id}} ${SYNTHETIC_TAG}" ]] || exit 91
  printf '%s\n' "$SYNTHETIC_LOADED_IMAGE"
}
remote_compose() {
  [[ "$*" == 'config --format json' ]] || exit 91
  python3 -c 'import json,sys; print(json.dumps({"services":{"backend":{"image":sys.argv[1]}}}))' "$SYNTHETIC_TAG"
}
reconcile_loaded_image "$SYNTHETIC_ROOT" "$SYNTHETIC_TAG" "$SYNTHETIC_IMAGE" "$SYNTHETIC_ARCHIVE_SHA"
'''
        env = dict(os.environ, SYNTHETIC_ROOT=str(self.run), SYNTHETIC_TAG=tag, SYNTHETIC_IMAGE=image,
                   SYNTHETIC_ARCHIVE_SHA=poststate.digest(raw), SYNTHETIC_LOADED_IMAGE=image,
                   V126_INTERNAL_REMOTE_V126_IMAGE_ID=image, V126_RECONCILE_PRIVATE=str(private))
        damaged = raw.replace(layer, b'X' * len(layer), 1)
        for case, expected, overrides, content in [
                ('exact', 0, {}, raw), ('wrong-dump-hash', 4, {'SYNTHETIC_ARCHIVE_SHA': '0' * 64}, raw),
                ('wrong-loaded-image', 4, {'SYNTHETIC_LOADED_IMAGE': 'sha256:' + '0' * 64}, raw),
                ('invalid-layer-same-archive-hash', 4, {'SYNTHETIC_ARCHIVE_SHA': poststate.digest(damaged)}, damaged)]:
            with self.subTest(case=case):
                archive.chmod(0o600)
                archive.write_bytes(content)
                archive.chmod(0o400)
                info = archive.stat()
                before = (info.st_ino, info.st_mode, info.st_nlink, content)
                result = subprocess.run(['bash', '-c', program, 'saved-image-read-only', str(HERE / 'v126-cutover.sh')],
                                        capture_output=True, timeout=10, env=dict(env, **overrides))
                self.assertEqual(result.returncode, expected, result.stderr.decode())
                if case == 'invalid-layer-same-archive-hash':
                    self.assertIn(b'DiffID mismatch', result.stderr)
                info = archive.stat()
                self.assertEqual((info.st_ino, info.st_mode, info.st_nlink, archive.read_bytes()), before)
                self.assertEqual(list(private.iterdir()), [])

    def test_backup_requires_original_resource_cleanup_witness_and_original_artifact_hashes(self):
        phase = 'quiesced'
        identity = dict(self.identity, name='QUIESCED_BACKUP_REHEARSED', action='backup-rehearsal')
        request = dict(self.request, identity=identity,
                       args=self.request['args'][:3] + [phase, self.request['args'][3]])
        fields = dict(run_id='synthetic-run', release_sha='a' * 40, phase=phase,
                      dump_sha256='1' * 64, inventory_sha256='2' * 64, rehearsal_sha256='3' * 64, result='PASS')
        name = 'quiesced-backup-proof'
        extra = b''.join(('ARTIFACT\tquiesced-backup-' + suffix + '\t' + value + '\n').encode()
                         for suffix, value in [('dump', '1' * 64), ('inventory', '2' * 64), ('rehearsal', '3' * 64)])
        # Producer's artifact name and retained filename intentionally differ.
        def save():
            self.proof(name, fields)
            operation = self.record(identity, request, [name], extra_log=extra)
            for suffix in ('.proof', '.proof.sha256'):
                (self.run / (name + suffix)).rename(self.run / ('quiesced-backup-rehearsed' + suffix))
            return [operation]
        operations = save()
        with patch.object(poststate, 'run_observer') as observe:
            with self.assertRaisesRegex(ValueError, 'historical_backup_resource_witness'):
                poststate.collect(self.target, identity, SOURCE, request, operations)
            observe.assert_not_called()
        fields.update(rehearsal_container='e' * 64, rehearsal_volume='hookah-v126-synthetic-run-quiesced-123',
                      rehearsal_owner='v126:' + 'a' * 40 + ':synthetic-run:quiesced:123', rehearsal_cleanup='COMPLETE')
        operations = save()
        before = self.snapshot()
        with patch.object(poststate, 'run_observer', return_value={'mock': 'read-only boundary'}) as observe:
            result = poststate.collect(self.target, identity, SOURCE, request, operations)
        self.assertEqual(result['original_artifacts'].keys(), {name})
        self.assertIn('backup_resources_quiesced', [check for check, _ in observe.call_args.args[-2]])
        self.assertEqual(self.snapshot(), before)

    def test_actual_resource_absence_consumer_refuses_alive_label_or_inventory_failure(self):
        for label, fail, row, expected in [('empty', '', '', 0), ('resource-present', '', 'owned-id', 4),
                                          ('daemon-failure', 'yes', '', 4)]:
            with self.subTest(case=label):
                program = 'set -Eeuo pipefail\n' + poststate.READ_ONLY_HELPERS + r'''
docker() {
  [[ "$1" == container && "$2" == ls || "$1" == volume && "$2" == ls ]] || exit 91
  [[ -z "$SYNTHETIC_FAIL" ]] || return 72
  printf '%s' "$SYNTHETIC_ROW"
}
reconcile_rehearsal_absent "cccc" "hookah-v126-owned" "v126:own-label"
'''
                result = subprocess.run(['bash', '-c', program], capture_output=True, timeout=5,
                                        env={**os.environ, 'SYNTHETIC_FAIL': fail, 'SYNTHETIC_ROW': row})
                self.assertEqual(result.returncode, expected, result.stderr.decode())

    def test_exact_preflight_derivation_and_executor_keep_checked_status_and_private_files(self):
        derive, execute = poststate.preflight_sources(SOURCE)
        uri = self.target / 'synthetic-uri'
        uri.write_text('postgresql://synthetic:synthetic-pass@127.0.0.1:5432/synthetic?sslmode=disable\n')
        uri.chmod(0o600)
        service, password = self.target / 'service', self.target / 'pass'
        result = subprocess.run([sys.executable, '-c', derive, str(uri), poststate.digest(uri.read_bytes()),
                                 str(service), str(password)], capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual(service.stat().st_mode & 0o777, 0o600)
        self.assertEqual(password.stat().st_mode & 0o777, 0o600)
        self.assertNotIn(b'synthetic-pass', result.stdout + result.stderr)
        # Synthetic script is the explicit SQL dependency in this executor test.
        # Its real checked exit must survive the exact production source snippet.
        script = self.target / 'synthetic-extracted.sh'
        script.write_text('test "$DATABASE_URL" = service=v126_preflight || exit 92\nprintf "synthetic-only\\n"\nexit 23\n')
        script.chmod(0o500)
        output = self.target / 'private-output'
        result = subprocess.run([sys.executable, '-c', execute, str(script), poststate.digest(script.read_bytes()),
                                 str(output), str(service), str(password), os.environ['PATH'], str(self.target)],
                                capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 23)
        self.assertEqual(output.read_text(), 'synthetic-only\n')
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        self.assertEqual(result.stdout, b'')

    def test_generated_all_helper_source_parses_and_keeps_actual_read_only_toc_consumer(self):
        result = subprocess.run(['bash', '-n'], input=poststate.READ_ONLY_HELPERS.encode(), capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertIn('python3 -c "${code}" toc "${dump}" "${dump_sha}" "${inventory}" "${temporary}"', poststate.READ_ONLY_HELPERS)
        for mutation in ('docker start ', 'docker run ', 'docker rm ', 'docker volume rm ',
                         'systemctl reload ', 'remote_write_proof ', 'remote_dispatch_action ', 'remote_create_run_root '):
            self.assertNotIn(mutation, poststate.READ_ONLY_HELPERS)


if __name__ == '__main__':
    unittest.main(verbosity=2)
