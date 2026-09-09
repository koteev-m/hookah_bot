#!/usr/bin/env python3
"""Actual runtime response/guard consumers in Bash capture and conditional contexts.

Curl, Docker/Compose and SQL inventories are explicit local process/state fixtures.
The three response parsers, V125 runtime caller and both guard scripts are real.
No network, daemon, Telegram or privileged operation is performed.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]
SOURCE = Path(os.environ.get('V126_TEST_SOURCE', REPO / 'scripts/v126-cutover.sh'))
BEFORE = os.environ.get('V126_RUNTIME_EXPECT_BEFORE') == '1'
R2_BEFORE = os.environ.get('V126_RUNTIME_R2_EXPECT_BEFORE') == '1'
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
TELEGRAM_BOT_TOKEN=123456:synthetic_only
'''
DRIVER = r'''
set -Eeuo pipefail
source "$1"
cd "$FIXTURE_ROOT"
export FIXTURE_IMAGE="$V125_IMAGE_ID" FIXTURE_RELEASE="$V125_SOURCE_SHA"
remote_capture_compose_ids() { REMOTE_CAPTURED_CONTAINER_IDS=(aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa); }
remote_require_unique_global_image_container() { return 0; }
remote_require_global_image_count() { return 0; }
remote_flyway_state() { printf '125:0:0:0\n'; [[ "$FIXTURE_FAILURE" != flyway ]] || return 42; }
remote_compose() { cat >/dev/null; printf '0:0\n'; [[ "$FIXTURE_FAILURE" != queues ]] || return 42; }
remote_assert_public_drain() { return 0; }
probe() {
  case "$FIXTURE_PROBE" in
    health) remote_assert_health_json http://127.0.0.1:8080/health ;;
    version) remote_assert_version "$V125_SOURCE_SHA" ;;
    telegram) remote_assert_telegram_idle .env ;;
    runtime) remote_assert_v125_runtime "$FIXTURE_ROOT" "fixture:$V125_SOURCE_SHA" ;;
    root-file) remote_sudo_require_root_file "$FIXTURE_ROOT/checksum" 600 ;;
    root-checksum) remote_sudo_read_sha256_checksum "$FIXTURE_ROOT/checksum" ;;
    *) exit 98 ;;
  esac
}
case "$FIXTURE_CONTEXT" in
  capture) result="$(probe)" || exit "$?" ;;
  conditional) if probe; then :; else exit "$?"; fi ;;
esac
printf 'CONSUMER_COMPLETE\n'
'''
CURL = r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
if not any('http://127.0.0.1:8080/' in a for a in args) and '--config' not in args:
    raise SystemExit(98)
if '--config' in args:
    expected={'ok':True,'result':{'url':'','pending_update_count':0}}
    invalid={'ok':True,'result':{'url':'','pending_update_count':1}}
elif any(a.endswith('/version') for a in args):
    expected={'service':'backend','env':'staging','version':os.environ['FIXTURE_RELEASE']}
    invalid={'service':'other','env':'staging','version':os.environ['FIXTURE_RELEASE']}
else:
    expected={'status':'ok'}
    invalid={'status':'wrong'}
failure=os.environ['FIXTURE_FAILURE']
response=invalid if failure == 'wrong' else expected
raw=b'{malformed' if failure == 'malformed' else json.dumps(response).encode()
if '--output' in args:
    Path(args[args.index('--output')+1]).write_bytes(raw)
else:
    sys.stdout.buffer.write(raw)
raise SystemExit(42 if failure == 'transport' else 0)
'''
DOCKER = r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[1:]
if args[0]=='compose' and args[-3:]==['config','--format','json']:
    env=dict(line.split('=',1) for line in Path('.env').read_text().splitlines() if '=' in line)
    print(json.dumps({'services':{'backend':{'environment':env}}},indent=2))
elif args[0]=='exec':
    pass
elif args[:2]==['image','inspect'] or args[:3]==['inspect','--format','{{.Image}}']:
    print(os.environ['FIXTURE_IMAGE'])
    if os.environ['FIXTURE_FAILURE'] == ('loaded-image' if args[0]=='image' else 'running-image'):
        raise SystemExit(42)
elif args[:2]==['inspect','--format'] and 'RestartPolicy' in args[2]:
    print('no:0')
    if os.environ['FIXTURE_FAILURE']=='restart-inspect':
        raise SystemExit(42)
else:
    raise SystemExit(98)
'''
SUDO = r'''#!/usr/bin/env python3
import os,sys
from pathlib import Path
args=sys.argv[1:]
path=Path(args[-1])
if path.parent != Path(os.environ['FIXTURE_ROOT']) or path.name != 'checksum':
    raise SystemExit(98)
failure=os.environ['FIXTURE_FAILURE']
if args[:2]==['test','-f']:
    raise SystemExit(42 if failure=='file-test' else (0 if path.is_file() else 1))
elif args[:3]==['test','!','-L']:
    raise SystemExit(42 if failure=='link-test' else (1 if path.is_symlink() else 0))
elif args[:3]==['stat','-c','%a:%U:%G']:
    print('600:root:root')
    raise SystemExit(42 if failure=='stat' else 0)
elif args[0]=='cat':
    sys.stdout.buffer.write(path.read_bytes())
    raise SystemExit(42 if failure=='checksum-cat' else 0)
elif args[:2]==['wc','-l']:
    print(str(path.read_bytes().count(b'\n'))+' '+str(path))
    raise SystemExit(42 if failure=='checksum-wc' else 0)
raise SystemExit(98)
'''


class RuntimeConsumers(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='v126-runtime-consumers-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.tmp = self.root / 'tmp'
        self.tmp.mkdir(mode=0o700)
        scripts = self.root / 'scripts'
        scripts.mkdir()
        for guard in ('check-staging-maintenance-config.sh', 'validate-staging-admission.sh'):
            shutil.copy(REPO / 'scripts' / guard, scripts / guard)
            (scripts / guard).chmod(0o700)
        shutil.copy(REPO / 'docker-compose.yml', self.root / 'docker-compose.yml')
        (self.root / '.env').write_text(BASE)
        (self.root / 'checksum').write_text('a'*64+'\n')
        (self.root / 'checksum').chmod(0o600)
        for name, body in [('curl', CURL), ('docker', DOCKER), ('sudo', SUDO)]:
            (self.bin / name).write_text(body)
            (self.bin / name).chmod(0o700)
        self.env = os.environ | {'PATH':str(self.bin)+os.pathsep+os.environ['PATH'],
                                'TMPDIR':str(self.tmp), 'FIXTURE_ROOT':str(self.root)}
        self.env.pop('STAGING_MAINTENANCE_V126_SMOKE_AUTHORIZED', None)

    def execute(self, probe, context, failure):
        env = self.env | {'FIXTURE_PROBE':probe,'FIXTURE_CONTEXT':context,'FIXTURE_FAILURE':failure}
        return subprocess.run(['/bin/bash','-c',DRIVER,'fixture',str(SOURCE)],cwd=self.root,env=env,
                              capture_output=True,text=True,timeout=25)

    def assert_outcome(self, result, success):
        if success:
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('CONSUMER_COMPLETE',result.stdout)
        else:
            self.assertNotEqual(result.returncode,0,'invalid runtime consumer was accepted')
            self.assertNotIn('CONSUMER_COMPLETE',result.stdout)
        self.assertNotIn('123456:synthetic_only',result.stdout+result.stderr)
        self.assertFalse(list(self.tmp.iterdir()),'runtime consumer leaked restricted response files')

    def test_response_consumers_preserve_json_and_transport_failures(self):
        for probe in ('health','version','telegram'):
            for context in ('capture','conditional'):
                for failure in ('none','wrong','malformed','transport'):
                    with self.subTest(probe=probe,context=context,failure=failure):
                        self.assert_outcome(self.execute(probe,context,failure),
                            failure=='none' or (BEFORE and failure in ('wrong','malformed')))

    def test_v125_runtime_actual_guard_failure_cannot_complete(self):
        for guard in ('maintenance','admission'):
            for context in ('capture','conditional'):
                with self.subTest(guard=guard,context=context):
                    content = BASE.replace('STAGING_MAINTENANCE_MODE=OFF','STAGING_MAINTENANCE_MODE=INVALID') if guard=='maintenance' else BASE.replace('fixture-safe-random-pepper-2026','')
                    (self.root / '.env').write_text(content)
                    guard_file = self.root/'scripts'/('check-staging-maintenance-config.sh' if guard=='maintenance' else 'validate-staging-admission.sh')
                    args = [str(guard_file),'.env'] if guard=='maintenance' else [str(guard_file),'--env-file','.env','--compose-file','docker-compose.yml']
                    direct = subprocess.run(args,cwd=self.root,env=self.env,capture_output=True,text=True,timeout=15)
                    self.assertNotEqual(direct.returncode,0,'test input did not fail the actual guard')
                    result = self.execute('runtime',context,'none')
                    self.assert_outcome(result,BEFORE)
                    self.assertIn('configuration rejected' if guard=='maintenance' else 'admission guard failed', result.stderr)

    def test_v125_runtime_response_failure_cannot_complete(self):
        for context in ('capture','conditional'):
            for failure in ('wrong','malformed','transport'):
                with self.subTest(context=context,failure=failure):
                    self.assert_outcome(self.execute('runtime',context,failure),
                                        BEFORE and failure in ('wrong','malformed'))

    def test_v125_runtime_producer_stdout_nonzero_cannot_complete(self):
        for context in ('capture','conditional'):
            for failure in ('running-image','loaded-image','restart-inspect','flyway','queues'):
                with self.subTest(context=context,failure=failure):
                    self.assert_outcome(self.execute('runtime',context,failure),R2_BEFORE)

    def test_root_file_and_checksum_consumers_preserve_producer_status(self):
        for probe in ('root-file','root-checksum'):
            failures = ('none','file-test','link-test','symlink','stat')
            if probe=='root-checksum':
                failures += ('checksum-cat','checksum-wc')
            for context in ('capture','conditional'):
                for failure in failures:
                    with self.subTest(probe=probe,context=context,failure=failure):
                        checksum=self.root/'checksum'
                        if failure=='symlink':
                            checksum.rename(self.root/'checksum-target')
                            checksum.symlink_to('checksum-target')
                        try:
                            self.assert_outcome(self.execute(probe,context,failure),
                                                failure=='none' or R2_BEFORE)
                        finally:
                            if checksum.is_symlink():
                                checksum.unlink()
                                (self.root/'checksum-target').rename(checksum)

    @unittest.skipIf(BEFORE,'after correction positive runtime verification')
    def test_v125_runtime_real_guards_positive(self):
        for context in ('capture','conditional'):
            self.assert_outcome(self.execute('runtime',context,'none'),True)


if __name__=='__main__':
    unittest.main(verbosity=2)
