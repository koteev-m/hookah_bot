#!/usr/bin/env python3
"""Real Caddy integration on a disposable GitHub Ubuntu runner.

The production active-config consumer, bounded-command helper, Caddy binary,
validate/adapt/run/reload operations and HTTP/admin responses are real. A narrow
unprivileged sudo alias allows only Caddy adapt of owned fixture files. A curl
alias translates only the production localhost admin URL to the owned test port.
Fault injection can return nonzero AFTER real adapt/admin output. No systemctl,
privilege simulation, live endpoint, reboot or recovery executor is used.

--self-test runs only adapter refusal tests and makes no runtime claim.
--require-linux-caddy requires a hosted Linux CI context and runs real integration.
"""
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request

REPO = Path(__file__).resolve().parents[1]
SOURCE = REPO / 'scripts/v126-cutover.sh'
SELF_TEST = '--self-test' in sys.argv
REQUIRE = '--require-linux-caddy' in sys.argv
if SELF_TEST:
    sys.argv.remove('--self-test')
if REQUIRE:
    sys.argv.remove('--require-linux-caddy')
if SELF_TEST == REQUIRE:
    raise SystemExit('Choose --self-test or --require-linux-caddy explicitly')
if REQUIRE and not (sys.platform == 'linux' and os.environ.get('GITHUB_ACTIONS') == 'true'
                    and os.environ.get('RUNNER_OS') == 'Linux'):
    raise SystemExit('Real Caddy integration requires the disposable GitHub Ubuntu job')

ADAPTER = r'''#!/usr/bin/env python3
import os, subprocess, sys
from pathlib import Path
args = sys.argv[1:]
root = Path(os.environ['V126_CADDY_FIXTURE']).resolve()
kind = Path(sys.argv[0]).name
if kind == 'sudo':
    if (len(args) != 6 or args[:3] != ['caddy', 'adapt', '--config'] or
            args[4:] != ['--adapter', 'caddyfile']):
        raise SystemExit('Caddy test refuses unexpected privileged command')
    path = Path(args[3])
    if (path.parent != root or path.is_symlink() or not path.is_file() or
            path.name not in ('original.Caddyfile', 'candidate.Caddyfile', 'active.Caddyfile',
                              'invalid.Caddyfile', 'provisioning-invalid.Caddyfile')):
        raise SystemExit('Caddy test refuses config outside its own fixture')
    command = [os.environ['V126_REAL_CADDY'], *args[1:]]
    fault = 'adapt-output-nonzero'
elif kind == 'curl':
    production_url = 'http://127.0.0.1:2019/config/'
    expected = ['--disable', '--noproxy', '*', '--proto', '=http',
                '--connect-timeout', '3', '--max-time', '10', '-fsS', production_url]
    if args != expected:
        raise SystemExit('Caddy test refuses unexpected HTTP request')
    port = int(os.environ['V126_CADDY_ADMIN_PORT'])
    if not 1024 < port < 65536:
        raise SystemExit('Caddy test refuses non-private admin port')
    command = [os.environ['V126_REAL_CURL'], *args[:-1],
               'http://127.0.0.1:' + str(port) + '/config/']
    fault = 'admin-output-nonzero'
else:
    raise SystemExit('Unknown Caddy test adapter')
result = subprocess.run(command, check=False)
if result.returncode == 0 and os.environ.get('V126_CADDY_FAULT') == fault:
    raise SystemExit(42)
raise SystemExit(result.returncode)
'''
DRIVER = r'''
set -Eeuo pipefail
source "$1"
case "$3" in
  capture) result="$(remote_assert_caddy_config_active "$2")" || exit "$?" ;;
  conditional) if remote_assert_caddy_config_active "$2"; then :; else exit "$?"; fi ;;
  *) exit 98 ;;
esac
printf 'CADDY_RUNTIME_PROVEN\n'
'''


def run(argv, *, env, cwd, timeout=20):
    return subprocess.run(argv, env=env, cwd=cwd, capture_output=True, text=True, timeout=timeout)


def write_adapters(root):
    directory = root / 'bin'
    directory.mkdir(mode=0o700)
    for name in ('sudo', 'curl'):
        path = directory / name
        path.write_text(ADAPTER)
        path.chmod(0o700)
    return directory


class AdapterRefusals(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='v126-caddy-adapter-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.bin = write_adapters(self.root)
        forbidden = self.root / 'must-not-run'
        forbidden.write_text('#!/bin/sh\nprintf escaped > "$V126_CADDY_FIXTURE/escaped"\nexit 98\n')
        forbidden.chmod(0o700)
        self.env = os.environ | {'V126_CADDY_FIXTURE':str(self.root), 'V126_REAL_CADDY':str(forbidden),
                                 'V126_REAL_CURL':str(forbidden), 'V126_CADDY_ADMIN_PORT':'23456'}

    def test_rejects_privileged_operations_and_external_paths(self):
        for args in (['systemctl', 'reload', 'caddy'], ['caddy', 'run'],
                     ['caddy', 'adapt', '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile']):
            with self.subTest(args=args):
                result = run([str(self.bin/'sudo'), *args], env=self.env, cwd=self.root)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.root/'escaped').exists())

    def test_rejects_non_fixture_http_and_symlink_config(self):
        for endpoint in ('https://staging.hookahtootah.club/health', 'https://api.telegram.org/',
                         'http://127.0.0.1:9999/config/'):
            result = run([str(self.bin/'curl'), '--disable', '--noproxy', '*', '--proto', '=http', '--connect-timeout', '3',
                          '--max-time', '10', '-fsS', endpoint], env=self.env, cwd=self.root)
            self.assertNotEqual(result.returncode, 0)
        (self.root/'original.Caddyfile').symlink_to('/dev/null')
        result = run([str(self.bin/'sudo'), 'caddy', 'adapt', '--config',
                      str(self.root/'original.Caddyfile'), '--adapter', 'caddyfile'],
                     env=self.env, cwd=self.root)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root/'escaped').exists())

    def test_exact_readonly_requests_delegate_with_owned_address(self):
        recorder = self.root/'recorder'
        recorder.write_text('#!/usr/bin/env python3\nimport json,os,sys\nfrom pathlib import Path\n'
                            'Path(os.environ["V126_CADDY_FIXTURE"],"argv.json").write_text(json.dumps(sys.argv[1:]))\n')
        recorder.chmod(0o700)
        env = self.env | {'V126_REAL_CADDY':str(recorder), 'V126_REAL_CURL':str(recorder)}
        flags = ['--disable','--noproxy','*','--proto','=http','--connect-timeout','3','--max-time','10','-fsS']
        result = run([str(self.bin/'curl'),*flags,'http://127.0.0.1:2019/config/'],env=env,cwd=self.root)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(json.loads((self.root/'argv.json').read_text()),
                         [*flags,'http://127.0.0.1:23456/config/'])
        config = self.root/'original.Caddyfile'
        config.write_text('synthetic adapter contract only')
        args = ['adapt','--config',str(config),'--adapter','caddyfile']
        result = run([str(self.bin/'sudo'),'caddy',*args],env=env,cwd=self.root)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(json.loads((self.root/'argv.json').read_text()),args)


@unittest.skipUnless(REQUIRE, 'adapter-only self-test; real Caddy integration not executed')
class RealCaddy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.caddy = shutil.which('caddy')
        cls.curl = shutil.which('curl')
        cls.bash = shutil.which('bash')
        if not all((cls.caddy, cls.curl, cls.bash)):
            raise AssertionError('caddy, curl and bash are mandatory; no runtime skip')
        if os.geteuid() == 0:
            raise AssertionError('Caddy fixture must run as the ordinary hosted-runner user')
        version = subprocess.run([cls.caddy, 'version'], capture_output=True, text=True, timeout=10)
        if version.returncode:
            raise AssertionError('real Caddy version query failed')
        print('V126 real Caddy: ' + version.stdout.strip(), flush=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='v126-caddy-linux-', dir=os.environ.get('RUNNER_TEMP'))
        self.root = Path(self.temp.name).resolve()
        self.root.chmod(0o700)
        self.addCleanup(self.temp.cleanup)
        self.bin = write_adapters(self.root)
        self.proofs = self.root/'proofs'
        self.proofs.mkdir(mode=0o700)
        self.reservations = []
        self.addCleanup(self.close_reservations)
        self.admin = self.reserve_port()
        self.http = self.reserve_port()
        self.unreachable_admin = self.reserve_port()
        self.env = {key:value for key,value in os.environ.items()
                    if key.lower() not in ('http_proxy','https_proxy','all_proxy','no_proxy')
                    and key not in ('CADDY_ADMIN','CADDY_CONFIG_FILE')}
        self.env.update(PATH=str(self.bin)+os.pathsep+os.environ['PATH'], HOME=str(self.root),
                        XDG_CONFIG_HOME=str(self.root/'config'), XDG_DATA_HOME=str(self.root/'data'),
                        TMPDIR=str(self.proofs), V126_CADDY_FIXTURE=str(self.root),
                        V126_REAL_CADDY=self.caddy, V126_REAL_CURL=self.curl,
                        V126_CADDY_ADMIN_PORT=str(self.admin), V126_CADDY_FAULT='none')
        self.original = self.root/'original.Caddyfile'
        self.candidate = self.root/'candidate.Caddyfile'
        self.active = self.root/'active.Caddyfile'
        self.original.write_text(self.config('original'))
        self.candidate.write_text(self.config('candidate'))
        self.active.write_bytes(self.original.read_bytes())
        for path in (self.original, self.candidate):
            self.assertEqual(self.command('validate', '--config', str(path), '--adapter', 'caddyfile').returncode, 0)
        self.server_log = (self.root/'caddy.log').open('wb')
        self.addCleanup(self.server_log.close)
        # Release only the selected server ports. The third port remains owned and
        # non-listening, providing a real failed-reload endpoint without a mock.
        self.reservations[0].close()
        self.reservations[1].close()
        self.server = subprocess.Popen([self.caddy, 'run', '--config', str(self.active), '--adapter', 'caddyfile'],
                                       env=self.env, cwd=self.root, stdout=self.server_log,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        self.addCleanup(self.stop_server)
        self.server_pid = self.server.pid
        deadline = time.monotonic()+10
        while time.monotonic() < deadline:
            if self.server.poll() is not None:
                self.fail('owned Caddy exited during startup')
            try:
                if self.http_body() == 'original':
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.05)
        self.fail('owned Caddy startup exceeded ten-second deadline')

    def close_reservations(self):
        for reservation in self.reservations:
            reservation.close()

    def reserve_port(self):
        reservation = socket.socket()
        reservation.bind(('127.0.0.1',0))
        self.reservations.append(reservation)
        return reservation.getsockname()[1]

    def config(self, response):
        return ('{\n  admin 127.0.0.1:'+str(self.admin)+'\n  auto_https off\n}\n'
                'http://127.0.0.1:'+str(self.http)+' {\n  bind 127.0.0.1\n'
                '  respond "'+response+'" 200\n}\n')

    def command(self, *args):
        return run([self.caddy, *args], env=self.env, cwd=self.root)

    def http_body(self, admin=False):
        port, path = (self.admin,'/config/') if admin else (self.http,'/')
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open('http://127.0.0.1:'+str(port)+path, timeout=1) as response:
            return response.read().decode()

    def stop_server(self):
        if self.server.poll() is None:
            os.killpg(self.server.pid, signal.SIGTERM)
            try:
                self.server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(self.server.pid, signal.SIGKILL)
                self.server.wait(timeout=5)

    def assert_proof(self, config, success, fault='none'):
        for context in ('capture','conditional'):
            result = run([self.bash, '-c', DRIVER, 'fixture', str(SOURCE), str(config), context],
                         env=self.env | {'V126_CADDY_FAULT':fault}, cwd=self.root)
            if success:
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('CADDY_RUNTIME_PROVEN',result.stdout)
            else:
                self.assertNotEqual(result.returncode,0,'active config mismatch was accepted')
                self.assertNotIn('CADDY_RUNTIME_PROVEN',result.stdout)
            self.assertFalse(list(self.proofs.iterdir()),'restricted config proof was not cleaned')

    def reload(self, config, address=None):
        return self.command('reload', '--config', str(config), '--adapter', 'caddyfile',
                            '--address', '127.0.0.1:'+str(address or self.admin))

    def test_real_disk_runtime_transition_and_inverse(self):
        self.assert_proof(self.original,True)
        self.active.write_bytes(self.candidate.read_bytes())
        self.assertEqual(self.active.read_bytes(),self.candidate.read_bytes())
        self.assertEqual(self.http_body(),'original')
        self.assert_proof(self.active,False)
        self.assertEqual(self.reload(self.active).returncode,0)
        self.assertEqual(self.http_body(),'candidate')
        self.assert_proof(self.active,True)
        self.assert_proof(self.original,False)
        self.active.write_bytes(self.original.read_bytes())
        self.assert_proof(self.active,False)
        self.assertEqual(self.reload(self.active).returncode,0)
        self.assertEqual(self.http_body(),'original')
        self.assert_proof(self.original,True)
        self.assertEqual(self.server.pid,self.server_pid)
        self.assertIsNone(self.server.poll())

    def test_real_validate_and_reload_errors_preserve_running_config(self):
        invalid = self.root/'invalid.Caddyfile'
        invalid.write_text('this is not a valid Caddyfile {\n')
        before = json.loads(self.http_body(admin=True))
        self.assertNotEqual(self.command('validate','--config',str(invalid),'--adapter','caddyfile').returncode,0)
        self.assertNotEqual(self.reload(invalid).returncode,0)
        self.assert_proof(invalid,False)
        self.assertEqual(json.loads(self.http_body(admin=True)),before)
        provisioning = self.root/'provisioning-invalid.Caddyfile'
        provisioning.write_text('{\n admin 127.0.0.1:'+str(self.admin)+'\n auto_https off\n}\n'
                                'https://127.0.0.1:'+str(self.http)+' {\n bind 127.0.0.1\n'
                                ' tls '+str(self.root/'missing-cert.pem')+' '+str(self.root/'missing-key.pem')+'\n'
                                ' respond "never"\n}\n')
        self.assertEqual(self.command('adapt','--config',str(provisioning),'--adapter','caddyfile').returncode,0)
        self.assertNotEqual(self.command('validate','--config',str(provisioning),'--adapter','caddyfile').returncode,0)
        self.assertNotEqual(self.reload(provisioning).returncode,0)
        self.assertEqual(json.loads(self.http_body(admin=True)),before)
        self.assertEqual(self.http_body(),'original')
        self.assert_proof(self.original,True)

    def test_real_failed_reload_leaves_disk_runtime_divergence(self):
        self.active.write_bytes(self.candidate.read_bytes())
        self.assertNotEqual(self.reload(self.active,self.unreachable_admin).returncode,0)
        self.assertEqual(self.http_body(),'original')
        self.assert_proof(self.active,False)
        self.assert_proof(self.original,True)

    def test_real_producer_output_nonzero_is_not_completion(self):
        self.assert_proof(self.original,False,'adapt-output-nonzero')
        self.assert_proof(self.original,False,'admin-output-nonzero')
        self.assert_proof(self.original,True)

    def test_stopped_owned_server_is_not_runtime_completion(self):
        self.assert_proof(self.original,True)
        self.stop_server()
        self.assertIsNotNone(self.server.poll())
        self.assertEqual(self.active.read_bytes(),self.original.read_bytes())
        self.assert_proof(self.original,False)


if __name__ == '__main__':
    unittest.main(verbosity=2)
