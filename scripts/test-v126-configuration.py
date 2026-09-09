#!/usr/bin/env python3
"""Real fixed-env guards/Compose and exact Bash Caddy recovery callers.

No daemon or live endpoint is used. Caddy/systemctl/sudo and release prerequisites
are explicit process spies. All environment bytes, guard code, Compose rendering,
Bash consumers, hash functions, install logic and cleanup are production code.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]
SOURCE = Path(os.environ.get('V126_TEST_SOURCE', REPO / 'scripts/v126-cutover.sh'))
GUARD_ROOT = Path(os.environ.get('V126_TEST_GUARD_ROOT', REPO))
BEFORE = os.environ.get('V126_CONFIG_EXPECT_BEFORE') == '1'
BASE = '''APP_ENV=staging
POSTGRES_DB=fixture
POSTGRES_USER=fixture
POSTGRES_PASSWORD=synthetic-local-password
TELEGRAM_TRAFFIC_POLICY=PRODUCT
TELEGRAM_ALLOWED_USER_IDS=
TELEGRAM_ALLOWED_CHAT_IDS=
VENUE_STAFF_INVITE_SECRET_PEPPER=fixture-safe-random-pepper-2026
STAGING_MAINTENANCE_MODE=OFF
STAGING_MAINTENANCE_ALLOWED_USER_IDS=
STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=
TELEGRAM_BOT_ENABLED=true
TELEGRAM_BOT_MODE=long_polling
BACKEND_IMAGE=fixture:stale
# preserve this exact unrelated comment
UNRELATED_SETTING=unrelated-value
'''
SMOKE = BASE.replace('STAGING_MAINTENANCE_MODE=OFF', 'STAGING_MAINTENANCE_MODE=V126_SMOKE').replace(
    'STAGING_MAINTENANCE_ALLOWED_USER_IDS=\n', 'STAGING_MAINTENANCE_ALLOWED_USER_IDS=101,202\n').replace(
    'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=\n', 'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS=101,202,-303\n')
RUNNER = r'''
set -Eeuo pipefail
source "$1"
fixture_stage="$2"; fixture_mode="$3"
cd "$fixture_stage"
fixture_sha="$(remote_hash_file .env)"
REMOTE_BOUND_ENV_SHA256="$fixture_sha"
V126_INTERNAL_REMOTE_BASELINE_ENV_SHA256="$fixture_sha"
remote_initialize_compose() { cd "$fixture_stage"; REMOTE_BOUND_ENV_SHA256="$fixture_sha"; }
remote_require_absolute_path() { [[ "$2" == "$fixture_stage/identities" ]]; }
remote_require_operator_file() { [[ "$1" == "$fixture_stage/identities" && -f "$1" && ! -L "$1" ]]; }
remote_require_run_root() { printf '%s\n' "$fixture_stage/run"; }
remote_verify_maintenance_env_binding() { REMOTE_BOUND_ENV_SHA256="$fixture_sha"; }
remote_assert_caddy_drain_marker() { :; }
remote_assert_public_drain() { :; }
remote_capture_compose_ids() { REMOTE_CAPTURED_CONTAINER_IDS=(); }
remote_verify_proof() { :; }
remote_assert_zero_writer() { :; }
# GNU cp's existing metadata flag is the only platform adapter.
cp() {
  if [[ "$1" == --preserve=mode,ownership,timestamps ]]; then
    shift; command cp -p "$@"
  else
    command cp "$@"
  fi
}
if [[ "$fixture_mode" == recovery* ]]; then
  remote_recovery_product_off "$fixture_stage" "$fixture_stage/run"
else
  remote_transform_maintenance_config "$fixture_stage" fixture-run f7828e09863d391e1f714cc65c9c866f814cf6bf \
    fixture:1111111111111111111111111111111111111111 "$fixture_stage/identities" "$fixture_mode" "$(remote_hash_file identities)"
fi
'''
CADDY = r'''
set -Eeuo pipefail
source "$1"
fixture_root="$2"; failure="$3"
V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256="$(remote_hash_file "$fixture_root/Caddyfile.original")"
V126_INTERNAL_REMOTE_CADDY_CANDIDATE_SHA256="$(remote_hash_file "$fixture_root/Caddyfile.drain")"
if [[ "${4:-receipted}" == partial ]]; then
  V126_INTERNAL_REMOTE_BASELINE_CADDY_SHA256="$V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256"
  V126_INTERNAL_REMOTE_CADDY_ORIGINAL_SHA256=NONE
fi
remote_verify_caddy_receipt_evidence() { [[ "$failure" != receipt ]] || return 42; }
remote_verify_partial_caddy_evidence() { [[ "$failure" != receipt ]] || return 42; }
remote_caddy_evidence_root() { printf '%s\n' "$fixture_root"; }
remote_sudo_require_root_file() { :; }
remote_assert_caddy_drain_marker() { test -f "$fixture_root/marker"; }
remote_assert_public_live() { [[ "$failure" != public ]] && [[ ! -e "$fixture_root/marker" ]]; }
remote_assert_public_drain() { [[ "$failure" != public ]] && [[ -f "$fixture_root/marker" ]]; }
# Shell sudo spy is exported only to the own bounded subprocess wrapper's child shell.
export fixture_root failure
# Exact production capture form; function success must survive the caller context.
if [[ "${5:-restore}" == ensure-drain ]]; then
  remote_recovery_ensure_pre_v126_drain f7828e09863d391e1f714cc65c9c866f814cf6bf fixture-run
  printf 'DRAIN_COMPLETE\n'
else
  local_caddy_sha="$(remote_recovery_restore_original_caddy f7828e09863d391e1f714cc65c9c866f814cf6bf fixture-run)" || exit "$?"
  printf 'COMPLETE:%s\n' "$local_caddy_sha"
fi
'''


class ConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='v126-config-test-')
        self.root = Path(self.directory.name)
        self.root.chmod(0o700)
        self.bin = self.root / 'bin'
        self.bin.mkdir(mode=0o700)
        self.tmp = self.root / 'tmp'
        self.tmp.mkdir(mode=0o700)
        self.env = os.environ.copy()
        self.env.update(PATH=str(self.bin) + os.pathsep + os.environ['PATH'], TMPDIR=str(self.tmp),
                        DOCKER_HOST='unix://' + str(self.root / 'absent-docker.sock'), DOCKER_CONTEXT='')

    def tearDown(self):
        self.directory.cleanup()

    def executable(self, name, content):
        file = self.bin / name
        file.write_text('#!/bin/bash\nset -eu\n' + content)
        file.chmod(0o700)

    def run_shell(self, script, args=(), env=None):
        return subprocess.run(['/bin/bash', '-c', script, 'fixture', str(SOURCE), *map(str, args)],
                              env=env or self.env, text=True, capture_output=True, timeout=60)

    def compose(self, failure=''):
        docker = shutil.which('docker')
        if not docker:
            self.fail('Real Docker Compose is required; a mock is not coverage for F02')
        self.executable('docker', '''
case "$*" in *" config --format json") ;; *) exit 98;; esac
export DOCKER_HOST='unix://''' + str(self.root / 'absent-docker.sock') + ''''
unset DOCKER_CONTEXT
''' + ('''printf 'call\n' >> "''' + str(self.root / 'compose-calls') + '''"
[[ $(wc -l < "''' + str(self.root / 'compose-calls') + '''") -lt 2 ]] || exit 42
''' if failure == 'installed' else '') + '''
''' + ('exit 42\n' if failure == 'before' else '') + '''
"''' + docker + '''" "$@"
''' + ('exit 42\n' if failure == 'after' else ''))

    def fixture(self, mode):
        stage = self.root / 'stage'
        stage.mkdir(mode=0o700)
        (stage / 'scripts').mkdir(mode=0o700)
        (stage / 'run').mkdir(mode=0o700)
        for guard in ('check-staging-maintenance-config.sh', 'validate-staging-admission.sh'):
            shutil.copy(GUARD_ROOT / 'scripts' / guard, stage / 'scripts' / guard)
            (stage / 'scripts' / guard).chmod(0o700)
        shutil.copy(REPO / 'docker-compose.yml', stage / 'docker-compose.yml')
        (stage / '.env').write_text(mode)
        (stage / '.env').chmod(0o600)
        (stage / 'identities').write_text('STAGING_MAINTENANCE_ALLOWED_USER_IDS=101,202\nSTAGING_MAINTENANCE_ALLOWED_CHAT_IDS=101,202,-303\n')
        return stage

    def test_four_transitions_real_guards_compose(self):
        self.compose()
        for mode, before, expected in [('V126_SMOKE', BASE, SMOKE), ('OFF', SMOKE, BASE),
                                       ('recovery', SMOKE, BASE), ('recovery-off', BASE, BASE)]:
            with self.subTest(mode=mode):
                stage = self.fixture(before)
                metadata = (stage / '.env').stat()
                hostile = self.env | {'APP_ENV': 'production', 'TELEGRAM_TRAFFIC_POLICY': 'UNRESTRICTED',
                                      'STAGING_MAINTENANCE_MODE': 'OFF', 'STAGING_MAINTENANCE_ALLOWED_USER_IDS': '999'}
                result = self.run_shell(RUNNER, (stage, mode), hostile)
                if BEFORE:
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('fixed .env', result.stderr)
                    self.assertEqual((stage / '.env').read_text(), before)
                else:
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual((stage / '.env').read_text(), expected)
                    current = (stage / '.env').stat()
                    self.assertEqual((current.st_uid, current.st_gid, current.st_mode),
                                     (metadata.st_uid, metadata.st_gid, metadata.st_mode))
                self.assertFalse([p for p in (stage / 'run').iterdir() if '.env' in p.name or 'env.' in p.name])
                self.assertFalse(list(self.tmp.iterdir()))
                shutil.rmtree(stage)

    @unittest.skipIf(BEFORE, 'after repair refusal coverage')
    def test_compose_success_stdout_nonzero_has_no_install_or_pass(self):
        self.compose('after')
        stage = self.fixture(BASE)
        result = self.run_shell(RUNNER, (stage, 'V126_SMOKE'))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((stage / '.env').read_text(), BASE)
        self.assertFalse(list((stage / 'run').iterdir()))
        self.assertFalse(list(self.tmp.iterdir()))
        self.assertNotIn('ARTIFACT', result.stdout)

    @unittest.skipIf(BEFORE, 'after repair post-install validation coverage')
    def test_installed_guard_failure_is_partial_without_pass(self):
        self.compose('installed')
        stage = self.fixture(BASE)
        result = self.run_shell(RUNNER, (stage, 'V126_SMOKE'))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((stage / '.env').read_text(), SMOKE)
        self.assertFalse(list((stage / 'run').iterdir()))
        self.assertFalse(list(self.tmp.iterdir()))
        self.assertNotIn('ARTIFACT', result.stdout)

    @unittest.skipIf(BEFORE, 'after repair inverse coverage')
    def test_partial_inverse_both_directions_and_unrelated_drift(self):
        self.compose()
        for initial, current, source_mode, target in [(BASE, SMOKE, 'OFF', 'V126_SMOKE'), (SMOKE, BASE, 'V126_SMOKE', 'OFF')]:
            stage = self.fixture(current)
            script = r'''set -euo pipefail
source "$1"
remote_verify_partial_environment_transition "$2" "$2/identities" "$3" "$4" "$5" "$(remote_hash_file "$2/identities")"
'''
            args = (stage, source_mode, target, hashlib.sha256(initial.encode()).hexdigest())
            self.assertEqual(self.run_shell(script, args).returncode, 0)
            with (stage / '.env').open('a') as stream:
                stream.write('UNRELATED_DRIFT=1\n')
            self.assertNotEqual(self.run_shell(script, args).returncode, 0)
            shutil.rmtree(stage)

    def caddy_fixture(self, failure):
        fixture = Path(tempfile.mkdtemp(prefix='caddy-', dir=self.root))
        (fixture / 'Caddyfile.original').write_text('original\n')
        (fixture / 'Caddyfile.drain').write_text('candidate\n')
        (fixture / 'active-disk').write_text('candidate\n')
        (fixture / 'active-runtime').write_text('candidate\n')
        (fixture / 'marker').touch()
        self.env.update(fixture_root=str(fixture), failure=failure)
        self.executable('sudo', r'''
printf '%s\n' "$*" >> "$fixture_root/commands.log"
case "$*" in
  "stat -c %a:%U:%G "*) printf '%s\n' 700:root:root;;
  "sha256sum /etc/caddy/Caddyfile") shasum -a 256 "$fixture_root/active-disk";;
  "sha256sum "*) shift; shasum -a 256 "$@"; [[ "$failure" != hash ]] || exit 42;;
  "caddy validate "*) [[ "$failure" != validate ]] || exit 42;;
  "caddy adapt "*) printf '{"apps":{"http":{"fixture":"%s"}}}\n' "$(cat "$4")"; [[ "$failure" != adapt ]] || exit 42;;
  "install -o root -g root -m 0644 "*)
    [[ "$failure" != install-before ]] || exit 42
    cp "$8" "$fixture_root/active-disk"
    [[ "$failure" != install-after ]] || exit 42;;
  "systemctl reload caddy")
    [[ "$failure" != reload-before ]] || exit 42
    if [[ "$failure" != divergence ]]; then cp "$fixture_root/active-disk" "$fixture_root/active-runtime"; fi
    [[ "$failure" != reload-after ]] || exit 42;;
  "systemctl is-active caddy") printf '%s\n' active; [[ "$failure" != active ]] || exit 42;;
  "test ! -L /etc/caddy/v126-drain.enabled") test ! -L "$fixture_root/marker";;
  "test -e /etc/caddy/v126-drain.enabled") test -e "$fixture_root/marker";;
  "rm -f -- /etc/caddy/v126-drain.enabled") rm "$fixture_root/marker";;
  *) printf '%s\n' 'unexpected sudo fixture call' >&2; exit 98;;
esac
''')
        self.executable('curl', r'''
[[ "$*" == *"http://127.0.0.1:2019/config/"* ]] || exit 98
if [[ "$failure" == duplicate-json ]]; then
  printf '%s\n' '{"apps":{},"apps":{"http":{"fixture":"original"}}}'
else
  printf '{"apps":{"http":{"fixture":"%s"}}}\n' "$(cat "$fixture_root/active-runtime")"
fi
[[ "$failure" != admin ]] || exit 42
''')
        return fixture

    def test_caddy_failures_in_real_command_substitution(self):
        cases = ['reload-before'] if BEFORE else ['validate', 'install-before', 'install-after', 'reload-before',
                     'reload-after', 'active', 'hash', 'divergence', 'adapt', 'admin', 'duplicate-json', 'receipt', 'public']
        for failure in cases:
            with self.subTest(failure=failure):
                fixture = self.caddy_fixture(failure)
                result = self.run_shell(CADDY, (fixture, failure))
                if BEFORE:
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertIn('COMPLETE:', result.stdout)
                    self.assertFalse((fixture / 'marker').exists())
                    self.assertEqual((fixture / 'active-runtime').read_text(), 'candidate\n')
                else:
                    self.assertNotEqual(result.returncode, 0, result.stdout)
                    self.assertNotIn('COMPLETE:', result.stdout)
                    if failure != 'public':
                        self.assertTrue((fixture / 'marker').exists())
                    self.assertFalse(list(self.tmp.iterdir()))
                shutil.rmtree(fixture)

    @unittest.skipIf(BEFORE, 'after repair positive coverage')
    def test_caddy_positive_complete_requires_runtime_original(self):
        fixture = self.caddy_fixture('none')
        result = self.run_shell(CADDY, (fixture, 'none'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('COMPLETE:', result.stdout)
        self.assertEqual((fixture / 'active-runtime').read_text(), 'original\n')
        self.assertFalse((fixture / 'marker').exists())
        self.assertFalse(list(self.tmp.iterdir()))

    @unittest.skipIf(BEFORE, 'after repair partial Caddy recovery coverage')
    def test_partial_caddy_recovery_both_consumers(self):
        for action in ('restore', 'ensure-drain'):
            for failure in ('none', 'hash', 'reload-before'):
                with self.subTest(action=action, failure=failure):
                    fixture = self.caddy_fixture(failure)
                    if action == 'ensure-drain':
                        (fixture / 'active-disk').write_text('original\n')
                        (fixture / 'active-runtime').write_text('original\n')
                    result = self.run_shell(CADDY, (fixture, failure, 'partial', action))
                    if failure == 'none':
                        self.assertEqual(result.returncode, 0, result.stderr)
                        expected = 'original\n' if action == 'restore' else 'candidate\n'
                        self.assertEqual((fixture / 'active-runtime').read_text(), expected)
                        self.assertEqual((fixture / 'marker').exists(), action == 'ensure-drain')
                        self.assertIn('COMPLETE', result.stdout)
                    else:
                        self.assertNotEqual(result.returncode, 0)
                        self.assertNotIn('COMPLETE', result.stdout)
                        self.assertTrue((fixture / 'marker').exists())
                    self.assertFalse(list(self.tmp.iterdir()))
                    shutil.rmtree(fixture)


if __name__ == '__main__':
    unittest.main(verbosity=2)
