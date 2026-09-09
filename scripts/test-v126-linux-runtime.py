#!/usr/bin/env python3
"""Actual Linux/Docker/JVM/PG17 integration on a disposable GitHub-hosted runner.

Builds a local test image from backend/Dockerfile; never saves/pushes a release image.
All containers/networks/volumes have an unguessable ownership label. An internal network
and private synthetic Telegram TLS endpoint prevent real Telegram requests. Production
readiness, database equality, schema and extracted preflight consumers run unchanged.
Fixture migration preparation, delayed entrypoint and invalid JVM/port/version settings
are explicit test inputs. This is a connected integration selection, not full cutover,
operational DR, real Telegram smoke, VM reboot or an authorization to release.
"""
import argparse
import contextlib
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import uuid
from urllib.parse import quote, quote_plus
import zipfile

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / 'scripts/fixtures/v126-linux-runtime'
SOURCE = ROOT / 'scripts/v126-cutover.sh'
LABEL = 'com.hookah.v126.runtime-fixture'
TRUSTSTORE_PASSWORD = 'synthetic-fixture'
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('database_evidence', ROOT / 'scripts/v126-database-evidence.py')
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class FixtureAssertionError(AssertionError):
    """A reason authored by this fixture, safe to redact and show without traceback."""


def require(condition, message):
    if not condition:
        raise FixtureAssertionError(message)


def hosted_only():
    require(sys.platform == 'linux' and os.environ.get('GITHUB_ACTIONS') == 'true'
            and os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and os.environ.get('RUNNER_OS') == 'Linux',
            'requires an explicitly authorized disposable GitHub-hosted Linux runner')
    require(os.environ.get('DOCKER_HOST', 'unix:///var/run/docker.sock') == 'unix:///var/run/docker.sock'
            and not os.environ.get('DOCKER_CONTEXT'), 'nonlocal Docker target refused')
    for key in ('DATABASE_URL', 'TELEGRAM_BOT_TOKEN', 'DB_PASSWORD'):
        require(not os.environ.get(key), 'inherited product/credential input refused')
    for executable in ('docker', 'python3', 'curl', 'openssl', 'javac', 'keytool', 'psql', 'pg_dump'):
        require(shutil.which(executable), 'required integration tool unavailable: ' + executable)
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 8080))


class Diagnostics:
    def __init__(self, evidence, source_hash, values):
        self.evidence, self.source_hash, self.values = evidence, source_hash, values
        self.harness_hash = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        self.records, self.failures = [], []
        self.lock = threading.Lock()

    def redact(self, text):
        secrets = {TRUSTSTORE_PASSWORD}
        secrets.update(value for key, value in self.values().items()
                       if re.search('PASSWORD|TOKEN|SECRET|PEPPER', key, re.I) and value)
        variants = set(secrets)
        for _ in range(2):
            for value in tuple(variants):
                variants.update((json.dumps(value, ensure_ascii=True)[1:-1],
                                 json.dumps(value, ensure_ascii=False)[1:-1],
                                 value.replace('/', '\\/'), quote(value, safe=''), quote_plus(value, safe='')))
            variants.update(re.sub(r'%[0-9A-F]{2}', lambda match: match[0].lower(), value) for value in tuple(variants))
        for value in sorted(variants, key=len, reverse=True):
            text = text.replace(value, '[synthetic-secret-redacted]')
        return text

    def stream(self, payload, include_text):
        result = {'sha256': hashlib.sha256(payload).hexdigest(), 'bytes': len(payload)}
        if include_text:
            # Redact the entire stream first: a secret straddling the excerpt edge
            # must not become a surviving partial credential. Each stream gets4KiB.
            text = self.redact(payload.decode('utf-8', errors='replace'))
            budget = 4096
            truncated = len(json.dumps(text, ensure_ascii=True).encode()) > budget
            if truncated:
                marker = '\n[... sanitized output truncated ...]\n'
                lower, upper = 0, min(len(text), budget)
                while lower < upper:
                    count = (lower + upper + 1) // 2
                    candidate = text[:count // 2] + marker + text[-(count - count // 2):]
                    if len(json.dumps(candidate, ensure_ascii=True).encode()) <= budget:
                        lower = count
                    else:
                        upper = count - 1
                text = text[:lower // 2] + marker + text[-(lower - lower // 2):]
            result.update(excerpt=text, truncated=truncated)
        return result

    def command(self, category, consumer, status, elapsed, deadline, stdout, stderr, *, timed_out=False):
        require(category in ('build', 'readiness', 'metadata'), 'invalid diagnostic category')
        record = {'category': category, 'consumer': consumer, 'exit': status, 'seconds': round(elapsed, 3),
                  'deadline_seconds': deadline, 'timed_out': timed_out, 'source_sha256': self.source_hash,
                  'harness_sha256': self.harness_hash,
                  'stdout': self.stream(stdout, category in ('build', 'readiness')),
                  'stderr': self.stream(stderr, category in ('build', 'readiness'))}
        return self.retain(record)

    def failure(self, phase, error):
        record = {'category': 'failure', 'phase': phase, 'type': type(error).__name__,
                  'message': self.stream(str(error).encode(), isinstance(error, FixtureAssertionError)),
                  'source_sha256': self.source_hash, 'harness_sha256': self.harness_hash}
        with self.lock:
            self.failures.append(record)
        self.retain(record)

    def retain(self, record):
        with self.lock:
            name = 'diagnostic-' + str(len(self.records) + 1).zfill(4) + '.json'
            payload = (json.dumps(record, sort_keys=True, ensure_ascii=True) + '\n').encode()
            fd = os.open(self.evidence / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o400)
            with os.fdopen(fd, 'wb') as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            self.records.append(record)
        return name

    def emit_failure(self):
        if self.failures:
            for record in self.records:
                # One JSON-prefixed line: control bytes and GitHub workflow-command
                # lookalikes never become a new runner command line.
                payload = json.dumps(record, sort_keys=True, ensure_ascii=True).replace('::', r'\u003a\u003a')
                print('V126_RUNTIME_DIAGNOSTIC ' + payload)


class Integration:
    def __init__(self, evidence):
        self.evidence = evidence
        self.evidence.mkdir(parents=True, exist_ok=False)
        self.evidence.chmod(0o700)
        self.temp = tempfile.TemporaryDirectory(prefix='ht-v126-runtime-')
        self.root = Path(self.temp.name)
        self.fixture = self.root / 'fixture'
        self.fixture.mkdir(mode=0o755)
        self.token = uuid.uuid4().hex
        self.project = 'ht-v126-runtime-' + self.token
        self.image = 'ht-v126-runtime-fixture:' + self.token
        self.image_id = None
        self.copy_container = None
        self.events = []
        self.starts = {}
        self.source_hash = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
        self.diagnostics = Diagnostics(self.evidence, self.source_hash, lambda: getattr(self, 'values', {}))
        self.release = self.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT).stdout.decode().strip()
        require(re.fullmatch('[0-9a-f]{40}', self.release), 'invalid source commit')
        self.password = 'synthetic_' + self.token
        self.telegram_token = '81001:synthetic_' + self.token
        self.values = {
            'POSTGRES_USER': 'runtime_fixture', 'POSTGRES_PASSWORD': self.password,
            'POSTGRES_DB': 'runtime_fixture', 'DB_JDBC_URL': 'jdbc:postgresql://postgres:5432/runtime_fixture',
            'DB_USER': 'runtime_fixture', 'DB_PASSWORD': self.password,
            'DB_CONNECTION_TIMEOUT_MS': '1500', 'DB_MAX_POOL_SIZE': '3',
            'APP_ENV': 'staging', 'APP_VERSION': self.release, 'APP_HTTP_PORT': '8080',
            'API_SESSION_JWT_SECRET': 'synthetic_jwt_' + self.token,
            'TELEGRAM_BOT_ENABLED': 'true', 'TELEGRAM_BOT_MODE': 'long_polling',
            'TELEGRAM_BOT_TOKEN': self.telegram_token, 'TELEGRAM_BOT_USERNAME': 'runtime_fixture_bot',
            'TELEGRAM_LONG_POLLING_TIMEOUT_SECONDS': '1', 'TELEGRAM_TRAFFIC_POLICY': 'PRODUCT',
            'TELEGRAM_ALLOWED_USER_IDS': '', 'TELEGRAM_ALLOWED_CHAT_IDS': '',
            'STAGING_MAINTENANCE_MODE': 'V126_SMOKE',
            'STAGING_MAINTENANCE_ALLOWED_USER_IDS': '81001001',
            'STAGING_MAINTENANCE_ALLOWED_CHAT_IDS': '81001001',
            'TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER': 'synthetic_pepper_' + self.token,
            'VENUE_STAFF_INVITE_SECRET_PEPPER': 'synthetic_invite_' + self.token,
            'AI_ASSISTANT_ENABLED': 'false',
            'JAVA_TOOL_OPTIONS': '-Djavax.net.ssl.trustStore=/fixture/truststore.p12 '
                                 '-Djavax.net.ssl.trustStorePassword=' + TRUSTSTORE_PASSWORD,
        }
        self.compose_doc = {
            'name': self.project,
            'services': {
                'postgres': {'image': 'postgres:17', 'restart': 'no', 'labels': {LABEL: self.token},
                             'environment': {k: self.values[k] for k in ('POSTGRES_USER', 'POSTGRES_PASSWORD', 'POSTGRES_DB')},
                             'ports': ['127.0.0.1::5432', '127.0.0.1::443'],
                             'networks': {'default': {'aliases': ['api.telegram.org']}},
                             'volumes': ['pgdata:/var/lib/postgresql/data']},
                'provider': {'image': 'python:3.13-slim', 'restart': 'no', 'labels': {LABEL: self.token},
                             'environment': {'FIXTURE_TELEGRAM_TOKEN': self.telegram_token},
                             'volumes': [str(self.fixture) + ':/fixture:ro'],
                             'command': ['python3', '/fixture/telegram-provider.py'],
                             # Share the source namespace: the production exact-endpoint guard
                             # must still see only source+backend on the database network.
                             'network_mode': 'service:postgres'},
                'backend': {'image': '${BACKEND_IMAGE}', 'restart': 'no', 'labels': {LABEL: self.token},
                            'env_file': ['.env'], 'ports': ['127.0.0.1:8080:8080'],
                            'volumes': [str(self.fixture) + ':/fixture:ro'],
                            'command': ['/bin/sh', '-c', 'sleep 3; exec /app/app/bin/app']},
            },
            'networks': {'default': {'internal': True, 'labels': {LABEL: self.token}}},
            'volumes': {'pgdata': {'labels': {LABEL: self.token}}},
        }
        self.write_inputs()
        self.uri = None

    def run(self, argv, *, input=None, check=True, timeout=90, cwd=None, env=None, diagnostic_category=None):
        require(diagnostic_category is None
                or (diagnostic_category == 'readiness' and [str(x) for x in argv[:2]] == ['bash', '-c']
                    and 'remote_wait_backend_ready' in str(argv[2]))
                or (diagnostic_category == 'build' and [str(x) for x in argv[:2]] == ['docker', 'build']),
                'text diagnostics require an explicit build/readiness consumer')
        before = time.monotonic()
        clean = {key: os.environ[key] for key in ('PATH', 'HOME')}
        if env:
            clean.update(env)
        # One process group per bounded test command; no global PID scans or killall.
        process = subprocess.Popen([str(x) for x in argv], stdin=subprocess.PIPE if input is not None else subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True,
                                   cwd=cwd or self.root, env=clean)
        try:
            out, err = process.communicate(input, timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            out, err = process.communicate()
            elapsed = time.monotonic() - before
            diagnostic = self.diagnostics.command(diagnostic_category or 'metadata', Path(str(argv[0])).name,
                                                  124, elapsed, timeout, out, err, timed_out=True)
            self.events.append({'consumer': Path(str(argv[0])).name, 'exit': 124, 'seconds': round(elapsed, 3),
                                'diagnostic': diagnostic})
            raise FixtureAssertionError('test command deadline; remote mutation outcome is not inferred') from None
        result = subprocess.CompletedProcess(argv, process.returncode, out, err)
        elapsed = time.monotonic() - before
        event = {'consumer': Path(str(argv[0])).name, 'exit': result.returncode, 'seconds': round(elapsed, 3)}
        if diagnostic_category or result.returncode:
            event['diagnostic'] = self.diagnostics.command(diagnostic_category or 'metadata', event['consumer'],
                                                          result.returncode, elapsed, timeout, out, err)
        self.events.append(event)
        if check and result.returncode:
            raise FixtureAssertionError('integration consumer failed: ' + Path(str(argv[0])).name + ' exit ' + str(result.returncode))
        return result

    def compose(self, *args, **kwargs):
        return self.run(['docker', 'compose', '--env-file', '.env', '--file', 'docker-compose.yml', *args],
                        env={'BACKEND_IMAGE': self.image}, **kwargs)

    def write_inputs(self):
        (self.root / '.env').write_text(''.join(k + '=' + v + '\n' for k, v in self.values.items()))
        (self.root / '.env').chmod(0o600)
        (self.root / 'docker-compose.yml').write_text(json.dumps(self.compose_doc, indent=2) + '\n')
        (self.root / 'docker-compose.yml').chmod(0o600)
        self.env_sha = hashlib.sha256((self.root / '.env').read_bytes()).hexdigest()

    def inspect(self, cid):
        rows = json.loads(self.run(['docker', 'inspect', cid]).stdout)
        require(len(rows) == 1 and rows[0]['Id'] == cid and rows[0]['Config']['Labels'].get(LABEL) == self.token,
                'owned container identity mismatch')
        return rows[0]

    def cid(self, service):
        ids = self.compose('ps', '-aq', '--no-trunc', service).stdout.decode().splitlines()
        require(len(ids) == 1 and re.fullmatch('[0-9a-f]{64}', ids[0]), 'one canonical owned container required')
        self.inspect(ids[0])
        return ids[0]

    def sql(self, query, *, database='runtime_fixture'):
        return self.run(['docker', 'exec', '-i', self.postgres, 'psql', '-XqAtw', '-U', 'runtime_fixture',
                         '-d', database, '--set=ON_ERROR_STOP=1'], input=query.encode()).stdout

    def shell(self, code, args=(), **kwargs):
        script = 'set -Eeuo pipefail\nsource "$1"\nshift\n' + code
        return self.run(['bash', '-c', script, 'v126-runtime-fixture', SOURCE, *args], **kwargs)

    def start_once(self, cid):
        require(cid not in self.starts, 'test attempted a second backend start')
        self.starts[cid] = 1
        self.run(['docker', 'start', cid])

    def observe(self, cid, *, expected_release=None):
        before = time.monotonic()
        result = self.shell('if output="$(remote_wait_backend_ready "$@")"; then printf "OBSERVER_PASS\\n"; else exit $?; fi\n',
                            [cid, self.image_id, expected_release or self.release, 'first', self.env_sha],
                            check=False, timeout=135, diagnostic_category='readiness')
        output = result.stdout + result.stderr
        require(self.password.encode() not in output and self.telegram_token.encode() not in output,
                'readiness leaked synthetic credentials')
        return result, output, time.monotonic() - before

    def check_starts(self, cid):
        row = self.inspect(cid)
        require(self.starts.get(cid) == 1 and row['RestartCount'] == 0
                and row['HostConfig']['RestartPolicy']['Name'] == 'no', 'single-start restart policy violated')
        # Actual Docker events are scoped to this unique container and count start actions.
        events = self.run(['docker', 'events', '--since', self.started_at, '--until', str(int(time.time()) + 1),
                           '--filter', 'container=' + cid, '--filter', 'event=start', '--format', '{{json .}}']).stdout.splitlines()
        require(len(events) == 1, 'Docker event history does not prove exactly one backend start')

    def stats(self):
        context = ssl.create_default_context(cafile=str(self.fixture / 'provider.crt'))
        connection = http.client.HTTPSConnection('127.0.0.1', self.provider_port, timeout=3, context=context)
        try:
            connection.request('GET', '/__fixture__/stats')
            response = connection.getresponse()
            require(response.status == 200, 'synthetic provider statistics unavailable')
            return json.loads(response.read(16384))
        finally:
            connection.close()

    def prepare(self):
        self.started_at = str(int(time.time()) - 1)
        for command in (['docker', 'version', '--format', '{{.Server.Version}}'], ['docker', 'compose', 'version'],
                        ['java', '-version'], ['psql', '--version'], ['pg_dump', '--version']):
            version = self.run(command)
            self.events.append({'version': (version.stdout + version.stderr).decode().strip()})
        require(b' 17.' in self.run(['pg_dump', '--version']).stdout, 'host pg_dump17 is required')
        self.run(['docker', 'build', '--label', LABEL + '=' + self.token, '--tag', self.image,
                  '--file', 'backend/Dockerfile', '.'], cwd=ROOT, timeout=900, diagnostic_category='build')
        self.image_id = self.run(['docker', 'image', 'inspect', '--format', '{{.Id}}', self.image]).stdout.decode().strip()
        require(re.fullmatch('sha256:[0-9a-f]{64}', self.image_id), 'test image identity unavailable')
        self.copy_container = self.run(['docker', 'create', '--label', LABEL + '=' + self.token,
                                        self.image, '/bin/true']).stdout.decode().strip()
        self.run(['docker', 'cp', self.copy_container + ':/app/app/lib', str(self.root / 'lib')])
        migrations = {str(path.relative_to(ROOT / 'backend/app/src/main/resources')): path.read_bytes()
                      for path in (ROOT / 'backend/app/src/main/resources/db/migration/postgresql').glob('*.sql')}
        embedded = {}
        for jar in (self.root / 'lib').glob('*.jar'):
            with zipfile.ZipFile(jar) as archive:
                for name in archive.namelist():
                    if name.startswith('db/migration/postgresql/') and name.endswith('.sql'):
                        require(name not in embedded, 'duplicate migration resource in actual runtime classpath')
                        embedded[name] = archive.read(name)
        require(embedded == migrations, 'actual runtime image migration bytes differ from unchanged source')
        self.events.append({'checkpoint': 'IMAGE_MIGRATION_BYTES_EXACT', 'files': len(migrations),
                            'v126_sha256': hashlib.sha256(next(value for key, value in embedded.items()
                                                             if '/V126__' in key)).hexdigest()})
        self.run(['docker', 'rm', self.copy_container])
        self.copy_container = None
        for name in ('V126RuntimeFixture.java', 'telegram-provider.py'):
            shutil.copyfile(FIXTURES / name, self.fixture / name)
            (self.fixture / name).chmod(0o644)
        self.run(['javac', '--release', '21', '-cp', str(self.root / 'lib') + '/*',
                  '-d', self.fixture, self.fixture / 'V126RuntimeFixture.java'])
        (self.fixture / 'V126RuntimeFixture.class').chmod(0o644)
        (self.fixture / 'identity.sql').write_text(HELPER.IDENTITY_SQL)
        (self.fixture / 'identity.sql').chmod(0o644)
        self.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                  '-keyout', self.fixture / 'provider.key', '-out', self.fixture / 'provider.crt',
                  '-subj', '/CN=api.telegram.org', '-addext', 'subjectAltName=DNS:api.telegram.org,DNS:localhost,IP:127.0.0.1'])
        (self.fixture / 'provider.key').chmod(0o600)
        (self.fixture / 'provider.crt').chmod(0o644)
        self.run(['keytool', '-importcert', '-noprompt', '-alias', 'synthetic-provider', '-file', self.fixture / 'provider.crt',
                  '-keystore', self.fixture / 'truststore.p12', '-storetype', 'PKCS12', '-storepass', TRUSTSTORE_PASSWORD])
        (self.fixture / 'truststore.p12').chmod(0o644)
        self.compose('up', '-d', '--no-build', 'postgres', 'provider', timeout=180)
        self.postgres, self.provider = self.cid('postgres'), self.cid('provider')
        network = json.loads(self.run(['docker', 'network', 'inspect', self.project + '_default']).stdout)[0]
        require(network['Internal'] is True and network['Labels'].get(LABEL) == self.token, 'external network egress was not disabled')
        self.network = network['Name']
        self.provider_port = int(self.inspect(self.postgres)['NetworkSettings']['Ports']['443/tcp'][0]['HostPort'])
        pgport = int(self.inspect(self.postgres)['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort'])
        self.uri = 'postgresql://runtime_fixture:' + self.password + '@127.0.0.1:' + str(pgport) + '/runtime_fixture'
        deadline = time.monotonic() + 60
        while True:
            check = self.run(['docker', 'exec', self.postgres, 'pg_isready', '-h', '127.0.0.1', '-U', 'runtime_fixture', '-d', 'runtime_fixture'], check=False)
            if check.returncode == 0:
                break
            require(time.monotonic() < deadline, 'owned PG17 did not become ready')
            time.sleep(0.25)
        require(self.sql('SHOW server_version_num;').startswith(b'17'), 'owned server must be PG17')
        self.run(['docker', 'run', '--rm', '--label', LABEL + '=' + self.token, '--network', self.network,
                  '--env-file', str(self.root / '.env'), '-v', str(self.fixture) + ':/fixture:ro', self.image,
                  'java', '-cp', '/app/app/lib/*:/fixture', 'V126RuntimeFixture', 'migrate125'], timeout=180)
        require(self.sql("SELECT max(version::int)||':'||count(*) FILTER (WHERE version='126') FROM flyway_schema_history;") == b'125:0\n',
                'actual migration preparation did not stop at V125')
        self.events.append({'checkpoint': 'ACTUAL_FLYWAY_V125_NO_V126'})

    def target(self, *, uri=None, success=True):
        path = self.root / 'database-uri'
        path.write_text((uri or self.uri) + '\n')
        path.chmod(0o600)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        result = self.run(['python3', ROOT / 'scripts/v126-database-evidence.py', 'live-target', path, self.image, digest], check=False)
        require((result.returncode == 0) == success, 'actual database target equality result mismatch')
        require(self.password.encode() not in result.stdout + result.stderr, 'database equality leaked synthetic credential')
        if success:
            require(re.fullmatch(rb'DATABASE_TARGET_EQUALITY=[0-9a-f]{64}\n', result.stdout), 'structured equality result missing')
        return result

    def positive_sequence(self):
        self.compose('create', '--no-build', '--no-deps', 'backend')
        cid = self.cid('backend')
        self.target()  # Actual source/host plus stopped future-backend network proof.
        dump = self.run(['pg_dump', '-Fc', '--no-owner', '--no-acl', '-d', self.uri], timeout=60).stdout
        require(dump.startswith(b'PGDMP'), 'actual PG17 V125 backup was not produced')
        self.events.append({'checkpoint': 'ACTUAL_V125_BACKUP', 'sha256': hashlib.sha256(dump).hexdigest()})
        self.shell('RELEASE_WORKTREE="$1"; RELEASE_SHA="$2"; extract_booking_preflight "$3"\n',
                   [ROOT, self.release, self.root / 'preflight.sh'])
        preflight = self.run(['bash', self.root / 'preflight.sh'], env={'DATABASE_URL': self.uri}, timeout=60)
        HELPER.preflight_outcome(preflight.stdout)
        self.events.append({'checkpoint': 'ACTUAL_EXTRACTED_V125_PREFLIGHT_SAFE_COUNT0'})
        self.start_once(cid)
        result, output, elapsed = self.observe(cid)
        require(result.returncode == 0 and b'READINESS=STARTING' in output and b'READINESS=READY' in output,
                'actual delayed JVM startup did not become ready')
        self.check_starts(cid)
        self.target()
        # Same production SQL through source psql and actual backend image/JDBC.
        native = self.sql(HELPER.IDENTITY_SQL)
        jdbc = self.run(['docker', 'exec', cid, 'java', '-cp', '/app/app/lib/*:/fixture',
                         'V126RuntimeFixture', 'identity', '/fixture/identity.sql']).stdout
        HELPER.equal_identities([native, jdbc])
        self.shell('remote_compose() { command docker compose --env-file .env --file docker-compose.yml "$@"; }\nremote_assert_schema_v126\n',
                   env={'BACKEND_IMAGE': self.image})
        self.events.append({'checkpoint': 'ACTUAL_JVM_V125_TO_V126_READY_IDENTITY_SCHEMA', 'seconds': round(elapsed, 3)})
        self.sql('CREATE DATABASE another_plausible_target;')
        self.target(uri=self.uri.rsplit('/', 1)[0] + '/another_plausible_target', success=False)
        self.sql('CREATE SCHEMA another_plausible_schema;')
        self.target(uri=self.uri + '?options=-csearch_path%3Danother_plausible_schema', success=False)
        before = self.stats()['getUpdates']
        (self.fixture / 'deny-update').touch(mode=0o644)
        deadline = time.monotonic() + 20
        while True:
            stats = self.stats()
            if stats['getUpdates'] >= before + 2 and stats['last_offset'] == 81002:
                break
            require(time.monotonic() < deadline, 'actual synthetic-provider poller did not make progress')
            time.sleep(0.2)
        require(stats['unexpected'] == 0 and stats['outbound'] == 0, 'unexpected external/provider operation attempted')
        require(self.sql('SELECT count(*) FROM users WHERE telegram_user_id=81001999;') == b'0\n',
                'maintenance-denied Telegram update crossed the user write boundary')
        self.events.append({'checkpoint': 'ACTUAL_POLLER_PROGRESS_DENIED_WRITE_BOUNDARY',
                            'polls_after_ready': stats['getUpdates'] - before, 'denied_offset_advanced': True,
                            'denied_user_writes': 0, 'outbound_calls': 0})
        # Real application /db/health returns503 while only our database is stopped.
        self.run(['docker', 'stop', '--time', '3', self.postgres])
        failed_health = self.run(['curl', '--disable', '--noproxy', '*', '--connect-timeout', '2', '--max-time', '5',
                                  '-sS', '-o', '/dev/null', '-w', '%{http_code}', 'http://127.0.0.1:8080/db/health'])
        require(failed_health.stdout == b'503', 'actual JVM DB health did not expose unavailable PG as503')
        errors = []
        def restore_own_database():
            try:
                time.sleep(3)
                self.run(['docker', 'start', self.postgres])
                # Rejoin the restarted source network namespace; never restart the backend.
                self.run(['docker', 'restart', '--time', '1', self.provider])
                self.provider_port = int(self.inspect(self.postgres)['NetworkSettings']['Ports']['443/tcp'][0]['HostPort'])
            except BaseException as error:
                errors.append(error)
        restoring = threading.Thread(target=restore_own_database)
        restoring.start()
        try:
            result, output, _ = self.observe(cid)
        finally:
            restoring.join(timeout=95)
        require(not restoring.is_alive() and not errors, 'own database recovery did not finish')
        require(result.returncode == 0 and b'READINESS=STARTING' in output and b'READINESS=READY' in output,
                'actual JVM503-to200 did not recover without a backend restart')
        self.check_starts(cid)
        deadline = time.monotonic() + 15
        while True:
            try:
                self.stats()
                break
            except (OSError, http.client.HTTPException):
                require(time.monotonic() < deadline, 'owned provider did not rejoin restarted source network')
                time.sleep(0.2)
        self.events.append({'checkpoint': 'ACTUAL_JVM_503_TO_200_SAME_BACKEND_ONE_START'})
        self.compose('rm', '--stop', '--force', 'backend')

    def negative_sequence(self):
        cases = [('wrong-version', {'APP_VERSION': 'f' * 40}, b'READINESS=FAILED', 4),
                 ('jvm-exit', {'JAVA_TOOL_OPTIONS': '-XX:UnrecognizedSyntheticRuntimeOption=1'}, b'READINESS=FAILED', 4),
                 ('readiness-timeout', {'APP_HTTP_PORT': '8081'}, b'READINESS=UNKNOWN', 75)]
        original = dict(self.values)
        self.compose_doc['services']['backend']['command'] = ['/app/app/bin/app']
        for name, changes, expected, code in cases:
            self.values = dict(original, **changes)
            self.write_inputs()
            self.compose('create', '--no-build', '--no-deps', 'backend')
            cid = self.cid('backend')
            self.start_once(cid)
            result, output, elapsed = self.observe(cid)
            require(result.returncode == code and expected in output and b'OBSERVER_PASS' not in output,
                    'actual JVM negative readiness did not fail closed: ' + name)
            require(b'retry_allowed=false' in output, 'failed readiness allows retry')
            if name == 'readiness-timeout':
                require(119 <= elapsed < 135 and self.inspect(cid)['State']['Running'],
                        'unknown deadline must retain honest running JVM outcome')
            self.check_starts(cid)
            self.events.append({'checkpoint': name, 'exit': code, 'seconds': round(elapsed, 3), 'backend_start_count': 1})
            self.compose('rm', '--stop', '--force', 'backend')
        require(self.stats()['unexpected'] == 0, 'synthetic provider observed unexpected operation')

    def cleanup(self):
        failed = []
        if self.copy_container:
            if self.run(['docker', 'rm', '--force', self.copy_container], check=False).returncode:
                failed.append('image-inspection fixture container')
        if (self.root / 'docker-compose.yml').exists():
            if self.compose('down', '--volumes', '--remove-orphans', '--timeout', '5', check=False, timeout=90).returncode:
                failed.append('owned Compose resources')
        image = self.run(['docker', 'image', 'inspect', self.image], check=False)
        if image.returncode == 0:
            rows = json.loads(image.stdout)
            require(len(rows) == 1 and rows[0]['Config']['Labels'].get(LABEL) == self.token,
                    'refuse cleanup of an image with different ownership')
            if self.run(['docker', 'image', 'rm', '--no-prune', self.image], check=False).returncode:
                failed.append('owned test image')
        for kind, args in [('container', ['ps', '-aq']), ('volume', ['volume', 'ls', '-q']), ('network', ['network', 'ls', '-q'])]:
            result = self.run(['docker', *args, '--filter', 'label=' + LABEL + '=' + self.token], check=False)
            if result.returncode or result.stdout.strip():
                failed.append('remaining owned ' + kind)
        self.temp.cleanup()
        require(not failed, 'owned runtime cleanup incomplete: ' + ', '.join(failed))


def execute_fixture(fixture):
    status = 'FAILED'
    try:
        fixture.prepare()
        fixture.positive_sequence()
        fixture.negative_sequence()
        require(hashlib.sha256(SOURCE.read_bytes()).hexdigest() == fixture.source_hash, 'sequencer source changed during runtime test')
        status = 'PASSED'
    except BaseException as error:
        fixture.diagnostics.failure('integration', error)
    finally:
        try:
            fixture.cleanup()
        except BaseException as error:
            status = 'FAILED_CLEANUP'
            fixture.diagnostics.failure('cleanup', error)
        finally:
            (fixture.evidence / 'result.json').write_text(json.dumps({'status': status, 'source_sha256': fixture.source_hash,
                'release_commit': fixture.release, 'image_id': fixture.image_id, 'events': fixture.events,
                'ownership': {'project': fixture.project, 'label': LABEL + '=' + fixture.token},
                'backend_starts': fixture.starts, 'live_actions': 'NONE', 'real_telegram': False,
                'first_failure': fixture.diagnostics.failures[0] if fixture.diagnostics.failures else None,
                'failure_count': len(fixture.diagnostics.failures),
                'scope': 'connected integration consumers; not complete cutover/reboot/manual smoke'}, indent=2) + '\n')
    fixture.diagnostics.emit_failure()
    return 0 if status == 'PASSED' else 1


class DiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='v126-runtime-diagnostics-')
        self.evidence = Path(self.temp.name)
        self.secret = 'SYNTHETIC+private/"\\секрет'
        self.values = {key: self.secret + suffix for key, suffix in
                       [('DB_PASSWORD', '_db'), ('TELEGRAM_BOT_TOKEN', '_tg'), ('API_SESSION_JWT_SECRET', '_jwt'),
                        ('VENUE_STAFF_INVITE_SECRET_PEPPER', '_pepper')]}
        self.diagnostics = Diagnostics(self.evidence, hashlib.sha256(SOURCE.read_bytes()).hexdigest(), lambda: self.values)

    def tearDown(self):
        self.temp.cleanup()

    def test_all_secret_keys_json_uri_variants_and_truststore_are_redacted(self):
        examples = []
        for secret in [*self.values.values(), TRUSTSTORE_PASSWORD]:
            examples.extend([secret, json.dumps(secret)[1:-1], quote(secret, safe=''), quote_plus(secret, safe=''),
                             quote(json.dumps(secret)[1:-1], safe='')])
        text = ' '.join(examples)
        actual = self.diagnostics.redact(text)
        for value in examples:
            self.assertNotIn(value, actual)
        self.assertEqual(actual.count('[synthetic-secret-redacted]'), len(examples))

    def test_redaction_precedes_bounded_excerpt_and_controls_are_json_escaped(self):
        secret = 'private-' + 's' * 5000
        self.values['OTHER_TOKEN'] = secret
        payload = ('::error::\n\x1b' + 'a' * 1800 + secret + 'b' * 9000
                   + '\n::warning::tail::stop-commands::fixture').encode()
        result = self.diagnostics.stream(payload, True)
        self.assertEqual(result['sha256'], hashlib.sha256(payload).hexdigest())
        self.assertEqual(result['bytes'], len(payload))
        self.assertTrue(result['truncated'])
        self.assertLessEqual(len(json.dumps(result['excerpt'], ensure_ascii=True).encode()), 4096)
        self.assertNotIn('private-', result['excerpt'])
        self.assertNotIn('s' * 20, result['excerpt'])
        self.assertIn('tail', result['excerpt'])
        record = {'excerpt': result['excerpt']}
        self.diagnostics.records.append(record)
        self.diagnostics.failures.append({'phase': 'test'})
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.diagnostics.emit_failure()
        lines = output.getvalue().splitlines()
        self.assertEqual(len(lines), 1)
        self.assertTrue(lines[0].startswith('V126_RUNTIME_DIAGNOSTIC '))
        self.assertNotIn('\x1b', lines[0])
        self.assertNotIn('::', lines[0])
        self.assertEqual(json.loads(lines[0].split(' ', 1)[1]), record)

    def test_generic_output_is_hash_only_and_records_are_immutable(self):
        payload = (self.secret + ' inspect/config/DB rows').encode()
        name = self.diagnostics.command('metadata', 'docker', 42, .25, 90, payload, payload)
        path = self.evidence / name
        snapshot = path.read_bytes()
        record = json.loads(snapshot)
        self.assertEqual(set(record['stdout']), {'sha256', 'bytes'})
        self.assertEqual(set(record['stderr']), {'sha256', 'bytes'})
        self.assertEqual(path.stat().st_mode & 0o777, 0o400)
        self.diagnostics.command('readiness', 'bash', 75, 135, 135, b'', b'READINESS=UNKNOWN', timed_out=True)
        self.assertEqual(path.read_bytes(), snapshot)
        self.assertEqual(len(list(self.evidence.glob('diagnostic-*.json'))), 2)

    def test_run_records_check_false_observer_and_build_but_hides_other_output(self):
        fixture = Integration.__new__(Integration)
        fixture.root, fixture.events, fixture.diagnostics = self.evidence, [], self.diagnostics
        cases = [(['bash', '-c', 'remote_wait_backend_ready'], 'readiness', 4, False),
                 (['docker', 'build', '.'], 'build', 17, True),
                 (['docker', 'inspect', 'synthetic'], None, 23, True)]
        for argv, category, status, check in cases:
            with patch.object(subprocess, 'Popen') as popen:
                process = popen.return_value
                process.communicate.return_value = (self.values['DB_PASSWORD'].encode(), b'synthetic stderr')
                process.returncode = status
                if check:
                    with self.assertRaisesRegex(AssertionError, 'integration consumer failed'):
                        fixture.run(argv, check=check, diagnostic_category=category, timeout=135)
                else:
                    self.assertEqual(fixture.run(argv, check=check, diagnostic_category=category, timeout=135).returncode, status)
            record = self.diagnostics.records[-1]
            self.assertEqual((record['exit'], record['deadline_seconds']), (status, 135))
            self.assertEqual('excerpt' in record['stdout'], category is not None)
            self.assertIn('diagnostic', fixture.events[-1])
            self.assertNotIn(self.values['DB_PASSWORD'], json.dumps(record, ensure_ascii=False))

    def test_timed_out_build_retains_deadline_and_hashes_without_claiming_no_effect(self):
        fixture = Integration.__new__(Integration)
        fixture.root, fixture.events, fixture.diagnostics = self.evidence, [], self.diagnostics
        argv = ['docker', 'build', '.']
        with patch.object(subprocess, 'Popen') as popen, patch.object(os, 'killpg') as kill:
            process = popen.return_value
            process.pid = 4242
            process.communicate.side_effect = [subprocess.TimeoutExpired(argv, 900), (b'partial build', b'failure')]
            with self.assertRaisesRegex(AssertionError, 'remote mutation outcome is not inferred'):
                fixture.run(argv, diagnostic_category='build', timeout=900)
            kill.assert_called_once_with(4242, signal.SIGKILL)
        record = self.diagnostics.records[-1]
        self.assertEqual((record['exit'], record['deadline_seconds'], record['timed_out']), (124, 900, True))
        self.assertEqual(record['stdout']['sha256'], hashlib.sha256(b'partial build').hexdigest())

    def fixture(self, *, primary_error=None, cleanup_error=None):
        class Fixture:
            def prepare(inner):
                if primary_error:
                    raise primary_error

            def positive_sequence(inner):
                pass

            def negative_sequence(inner):
                pass

            def cleanup(inner):
                if cleanup_error:
                    raise cleanup_error
        fixture = Fixture()
        fixture.diagnostics, fixture.evidence = self.diagnostics, self.evidence
        fixture.source_hash = self.diagnostics.source_hash
        fixture.release, fixture.image_id, fixture.events = 'a' * 40, None, []
        fixture.project, fixture.token, fixture.starts = 'own-synthetic', 'synthetic', {}
        return fixture

    def test_first_failure_survives_cleanup_failure_with_sanitized_owned_reason(self):
        primary = FixtureAssertionError('first failure ' + self.values['DB_PASSWORD'])
        cleanup = RuntimeError('cleanup failure ' + self.secret)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = execute_fixture(self.fixture(primary_error=primary, cleanup_error=cleanup))
        result = json.loads((self.evidence / 'result.json').read_text())
        self.assertEqual((status, result['status'], result['failure_count']), (1, 'FAILED_CLEANUP', 2))
        self.assertEqual(result['first_failure']['phase'], 'integration')
        self.assertEqual(result['first_failure']['message']['sha256'], hashlib.sha256(str(primary).encode()).hexdigest())
        self.assertEqual(result['first_failure']['message']['excerpt'], 'first failure [synthetic-secret-redacted]')
        self.assertEqual([item['phase'] for item in self.diagnostics.failures], ['integration', 'cleanup'])
        self.assertNotIn('excerpt', self.diagnostics.failures[1]['message'])
        self.assertNotIn(self.secret, output.getvalue())
        self.assertIn('first failure [synthetic-secret-redacted]', output.getvalue())
        self.assertTrue(all(line.startswith('V126_RUNTIME_DIAGNOSTIC ') for line in output.getvalue().splitlines()))

    def test_success_does_not_emit_expected_negative_observer_diagnostics(self):
        self.diagnostics.command('readiness', 'bash', 4, .5, 135, b'', b'READINESS=FAILED')
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = execute_fixture(self.fixture())
        self.assertEqual(status, 0)
        self.assertEqual(output.getvalue(), '')
        self.assertEqual(json.loads((self.evidence / 'result.json').read_text())['status'], 'PASSED')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--require-hosted-ci', action='store_true')
    mode.add_argument('--diagnostics-self-test', action='store_true')
    parser.add_argument('--evidence-dir', type=Path)
    args = parser.parse_args()
    if args.diagnostics_self_test:
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(DiagnosticsTest))
        return 0 if result.wasSuccessful() else 1
    hosted_only()
    evidence = args.evidence_dir or Path(os.environ['RUNNER_TEMP']) / ('v126-linux-runtime-evidence-' + uuid.uuid4().hex)
    fixture = Integration(evidence)
    if execute_fixture(fixture):
        return 1
    print('V126 Linux/Docker/JVM connected integration: PASS; evidence=' + str(evidence))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
