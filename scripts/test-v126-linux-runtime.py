#!/usr/bin/env python3
"""Actual Linux/Docker/JVM/PG17 integration on a disposable GitHub-hosted runner.

Builds unmodified historical and current backend/Dockerfile test images; never
saves/pushes a release image. The historical source is a test input, not release authority.
All containers/networks/volumes have an unguessable ownership label. An internal network
and private synthetic Telegram TLS endpoint prevent real Telegram requests. Production
readiness, database equality, schema and extracted preflight consumers run unchanged.
Fixture migration preparation, delayed entrypoint and invalid JVM/port/version settings
are explicit test inputs. This is a connected integration selection, not full cutover,
operational DR, real Telegram smoke, VM reboot or an authorization to release.
"""
import argparse
import ast
import contextlib
import hashlib
import http.client
import importlib.util
import io
import ipaddress
import json
import os
from pathlib import Path
import re
import select
import shutil
import signal
import socket
import socketserver
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
# Last pre-UX08 source: real compatible backend, not current code with SQL removed.
V126_RUNTIME_SOURCE = '919abbb813cbbb724d628bdabeef8e905e6a1c42'
V126_MIGRATION_TREE = '765956602de896b4498a956753272a6bc2d2971e'
MIGRATION_PATH = 'backend/app/src/main/resources/db/migration'
V128_SCRIPT = 'V128__venue_info_media_objects.sql'
V128_SHA256 = '153579d56223a9cadd5f988081b86853cdc7394275e160b968c6200cff5fdaf9'
V127_SCRIPT = 'V127__staff_profile_photos.sql'
V127_SHA256 = '2034cdaf16b62e2f0eeacfc07bfd0d1941cad327cb5e6e7785af5e50ee514846'
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('database_evidence', ROOT / 'scripts/v126-database-evidence.py')
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)


class FixtureAssertionError(AssertionError):
    """A reason authored by this fixture, safe to redact and show without traceback."""


def require(condition, message):
    if not condition:
        raise FixtureAssertionError(message)


def verify_historical_history(rows):
    require(len(rows) == 126 and [row['version'] for row in rows] == [str(v) for v in range(1, 127)]
            and all(row['success'] for row in rows), 'forward input is not exact successful V1-V126')


def verify_forward_history(before, after):
    verify_historical_history(before)
    require(len(after) == 128 and after[:126] == before, 'forward migration changed historical Flyway rows')
    for row, version, script in zip(after[126:], ('127', '128'), (V127_SCRIPT, V128_SCRIPT)):
        require(row['version'] == version and row['script'] == script
                and row['success'] and row['type'] == 'SQL', 'unexpected forward migration')


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
        return self.redact_json(text)

    def redact_json(self, text, depth=0):
        # Parse complete bounded documents, never a scalar prefix of an invalid value.
        # Unparsed input contributes only closed indicators, not "mostly clean" text.
        if depth >= 8:
            return '[nested-json-redacted]'
        stripped = text.strip()
        if (len(text) <= 65536 and len(text.encode('utf-8', errors='replace')) <= 65536
                and stripped.startswith(('{', '[', '"', "'"))):
            try:
                value = json.loads(stripped)
            except (ValueError, RecursionError):
                try:
                    value = ast.literal_eval(stripped)
                except (ValueError, SyntaxError, RecursionError, TypeError):
                    return self.redact_plain(text)
            return json.dumps(self.public_value(value, depth + 1), ensure_ascii=True)
        if '\n' in text:
            return '\n'.join(self.redact_json(line, depth) if line else '' for line in text.split('\n'))
        return self.redact_plain(text)

    def public_value(self, value, depth):
        if depth >= 8:
            return '[nested-json-redacted]'
        if isinstance(value, dict):
            result, details = {}, []
            for key, child in list(value.items())[:256]:
                normalized = re.sub('[^a-z0-9]', '', str(key).casefold())
                if re.search('password|token|secret|pepper|authorization|apikey', normalized):
                    result['credential'] = '[credential-redacted]'
                elif key == 'status' and type(child) is str and child in {'FAILED', 'ERROR', 'UNKNOWN', 'PASSED'}:
                    result['status'] = child
                elif key == 'exit' and type(child) is int and -255 <= child <= 255:
                    result['exit'] = child
                else:
                    # Arbitrary keys/strings may themselves be private data.
                    details.append(self.public_value(child, depth + 1))
            if len(value) > 256:
                details.append('[nested-json-redacted]')
            if details:
                result['diagnostics'] = details
            return result
        if isinstance(value, (list, tuple)):
            result = [self.public_value(child, depth + 1) for child in value[:256]]
            return result + (['[nested-json-redacted]'] if len(value) > 256 else [])
        if isinstance(value, str):
            return self.redact_json(value, depth)
        return '[diagnostic-text-withheld]'

    @staticmethod
    def metadata_positions(line):
        # Two bounded-token scans preserve greedy suffix selection without
        # rescanning the remaining line for every unsuccessful parse prefix.
        last_end = 0
        for match in re.finditer(r'POM|metadata', line, re.I):
            last_end = match.end()
        if not last_end:
            return
        for match in re.finditer(r'\b(?:could not|failed to) parse|(?P<invalid>\binvalid (?:POM|metadata))', line, re.I):
            if match.group('invalid') is not None:
                yield match.start()
            elif match.end() < last_end:
                yield match.start()
                return  # The old greedy match consumed through the last suffix.

    def redact_plain(self, text):
        # No path/credential boundary guessing: all variable text is withheld.
        # These are observed text indicators, never an inferred runtime root cause.
        indicators = (
            (r'\* What went wrong:', '* What went wrong:'),
            (r'FAILURE: Build failed', 'FAILURE: Build failed'),
            (r'\* Try:', '* Try:'),
            (r'\bBuildKit START\b', 'BuildKit START'),
            (r'\bBuildKit progress\b', 'BuildKit progress'),
            (r'\bnpm ERR! (?:actual )?build failure\b', 'npm ERR! build failure'),
            (r'\bCould not resolve\b', 'Could not resolve'),
            (r'\bCould not find\b', 'Could not find'),
            (r'\bCould not GET\b', 'Could not GET'),
            (r'\bCould not HEAD\b', 'Could not HEAD'),
            (r'\bUnknownHost\b', 'UnknownHost'),
            (r'\btimed? out\b', 'timed out'),
            (r'\bConnection reset\b', 'Connection reset'),
            (r'\bConnection refused\b', 'Connection refused'),
            (r'\bPKIX\b', 'PKIX'),
            (r'\bSSLHandshake\b', 'SSLHandshake'),
            (r'\bUnresolved reference\b', 'Unresolved reference'),
            (r'\bENOSPC\b', 'ENOSPC'),
            (r'\[synthetic-secret-redacted\]', '[synthetic-secret-redacted]'),
        )
        output = []
        for line in text.split('\n'):
            if not line:
                output.append('')
                continue
            observed = [(match.start(), label) for pattern, label in indicators
                        for match in re.finditer(pattern, line, re.I)]
            observed.extend((position, 'Could not parse POM metadata') for position in self.metadata_positions(line))
            for match in re.finditer(r'\bstatus code (429|5[0-9]{2})\b', line, re.I):
                observed.append((match.start(), 'status code ' + match[1]))
            for match in re.finditer(r'\bnpm ERR! code (E401|E403|E404|E429|ENOSPC|EACCES|ENOENT|ECONNRESET|ETIMEDOUT)\b', line):
                observed.append((match.start(), 'npm ERR! code ' + match[1]))
            output.append(' '.join(label for _, label in sorted(observed)) or '[diagnostic-text-withheld]')
        return '\n'.join(output)

    def stream(self, payload, include_text, *, build_failure=False):
        result = {'sha256': hashlib.sha256(payload).hexdigest(), 'bytes': len(payload)}
        if include_text:
            # Redact the entire stream first: a secret straddling the excerpt edge
            # must not become a surviving partial credential. Each stream gets4KiB.
            text = self.redact(payload.decode('utf-8', errors='replace'))
            original = text
            # Fixed vocabulary survives excerpt selection without publishing raw stderr.
            reason_codes = [code for label, code in [('Unresolved reference', 'UNRESOLVED_REFERENCE'),
                            ('ENOSPC', 'ENOSPC'), ('Connection reset', 'CONNECTION_RESET')] if label in text]
            withheld = '[diagnostic-text-withheld]' in text or '[nested-json-redacted]' in text
            selected_block = False
            if build_failure:
                # BuildKit progress can bury the cause between the old head/tail excerpts.
                # Select only after whole-stream redaction, retaining the original hash/size.
                match = re.search(r'(?m)^.*\* What went wrong:.*$', text)
                if match is None:
                    match = re.search(r'(?m)^.*FAILURE: Build failed.*$', text)
                if match:
                    selected_block = True
                    text = text[match.start():]
                    end = re.search(r'(?m)^.*\* Try:.*$', text)
                    if end:
                        text = text[:end.start()]
            budget = 4096
            truncated = len(json.dumps(text, ensure_ascii=True).encode()) > budget
            if truncated:
                marker = '\n[... sanitized output truncated ...]\n'
                lower, upper = 0, min(len(text), budget)
                while lower < upper:
                    count = (lower + upper + 1) // 2
                    candidate = (text[:count] if selected_block else
                                 text[:count // 2] + marker + text[-(count - count // 2):])
                    if len(json.dumps(candidate, ensure_ascii=True).encode()) <= budget:
                        lower = count
                    else:
                        upper = count - 1
                text = text[:lower] if selected_block else text[:lower // 2] + marker + text[-(lower - lower // 2):]
            result.update(excerpt=text, truncated=truncated or text != original or withheld,
                          excerpt_format='safe-diagnostic-summary', reason_codes=reason_codes)
        return result

    def build_reason(self, payload, timed_out):
        text = self.redact(payload.decode('utf-8', errors='replace'))
        if timed_out:
            return 'TIMEOUT'
        if re.search(r'(?i)Could not (?:GET|HEAD)|UnknownHost|timed? out|connection (?:reset|refused)|'
                     r'PKIX|SSLHandshake|status code (?:429|5\d\d)', text):
            return 'NETWORK_INDICATOR'
        if re.search(r'(?i)(?:could not|failed to) parse.*(?:POM|metadata)|invalid (?:POM|metadata)', text):
            return 'METADATA_INDICATOR'
        if re.search(r'Could not (?:resolve|find)', text):
            return 'DEPENDENCY_RESOLUTION'
        return 'UNKNOWN'

    def first_build_failure(self):
        return next((record for record in self.records
                     if record.get('category') == 'build' and record.get('exit') != 0), None)

    def command(self, category, consumer, status, elapsed, deadline, stdout, stderr, *, timed_out=False):
        require(category in ('build', 'readiness', 'metadata'), 'invalid diagnostic category')
        record = {'category': category, 'consumer': consumer, 'exit': status, 'seconds': round(elapsed, 3),
                  'deadline_seconds': deadline, 'timed_out': timed_out, 'source_sha256': self.source_hash,
                  'harness_sha256': self.harness_hash,
                  'stdout': self.stream(stdout, category in ('build', 'readiness')),
                  'stderr': self.stream(stderr, category in ('build', 'readiness'), build_failure=category == 'build' and status != 0)}
        if category == 'build' and status != 0:
            record['reason_category'] = self.build_reason(stderr, timed_out)
        return self.retain(record)

    def failure(self, phase, error):
        locations = []
        traceback = error.__traceback__
        while traceback:
            frame = traceback.tb_frame
            if Path(frame.f_code.co_filename).resolve() == Path(__file__).resolve():
                locations.append({'file': 'scripts/test-v126-linux-runtime.py',
                                  'function': frame.f_code.co_name, 'line': traceback.tb_lineno})
            traceback = traceback.tb_next
        record = {'category': 'failure', 'phase': phase, 'type': type(error).__name__,
                  'message': self.stream(str(error).encode(), isinstance(error, FixtureAssertionError)),
                  'locations': locations[-8:], 'source_sha256': self.source_hash, 'harness_sha256': self.harness_hash}
        if hasattr(error, 'runtime_failure'):
            record['consumer_failure'] = error.runtime_failure
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


class LoopbackRelay:
    """Fixture-only byte transport; every connection resolves an owned endpoint anew."""
    def __init__(self, resolve_target, port=0, *, idle_seconds=20, total_seconds=60, max_connections=16):
        self.resolve_target = resolve_target
        self.idle_seconds, self.total_seconds = idle_seconds, total_seconds
        self.max_connections = max_connections
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.sockets, self.workers = set(), set()
        self.connections, self.refusals = 0, 0
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', port))
        self.listener.listen(16)
        self.listener.settimeout(.1)
        self.port = self.listener.getsockname()[1]
        self.thread = threading.Thread(target=self.accept, name='v126-owned-loopback-relay', daemon=True)
        self.thread.start()

    def accept(self):
        while not self.stop.is_set():
            try:
                client, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            with self.lock:
                if self.stop.is_set():
                    client.close()
                    continue
                if len(self.workers) >= self.max_connections:
                    self.refusals += 1
                    client.close()
                    continue
                worker = threading.Thread(target=self.forward, args=(client,), daemon=True)
                self.sockets.add(client)
                self.workers.add(worker)
                self.connections += 1
            worker.start()

    def forward(self, client):
        upstream = None
        try:
            host, port = self.resolve_target()
            address = ipaddress.IPv4Address(host)
            require(address.is_private and not address.is_unspecified and not address.is_multicast
                    and type(port) is int and 0 < port < 65536, 'relay endpoint is not an explicit private fixture address')
            if self.stop.is_set():
                return
            upstream = socket.create_connection((str(address), port), timeout=3)
            with self.lock:
                self.sockets.add(upstream)
            client.settimeout(1)
            upstream.settimeout(1)
            started = last_data = time.monotonic()
            while not self.stop.is_set():
                now = time.monotonic()
                remaining = min(self.total_seconds - (now - started), self.idle_seconds - (now - last_data))
                if remaining <= 0:
                    break
                readable, _, _ = select.select([client, upstream], [], [], min(.1, remaining))
                for origin in readable:
                    payload = origin.recv(65536)
                    if not payload:
                        return
                    (upstream if origin is client else client).sendall(payload)
                    last_data = time.monotonic()
        except (OSError, ValueError, FixtureAssertionError, KeyError, TypeError):
            with self.lock:
                self.refusals += 1
        finally:
            with self.lock:
                for connection in (client, upstream):
                    if connection is not None:
                        self.sockets.discard(connection)
                        connection.close()
                self.workers.discard(threading.current_thread())

    def close(self):
        self.stop.set()
        self.listener.close()
        self.thread.join(timeout=1)
        with self.lock:
            for connection in self.sockets:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            workers = list(self.workers)
        deadline = time.monotonic() + 15
        for worker in workers:
            worker.join(timeout=max(0, deadline - time.monotonic()))
        require(not self.thread.is_alive() and not any(worker.is_alive() for worker in workers),
                'owned loopback relay did not stop within its cleanup bound')


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
        self.images = []
        self.copy_container = None
        self.events = []
        self.starts = {}
        self.relays = []
        self.backend_relay_cid = None
        self.source_hash = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
        self.diagnostics = Diagnostics(self.evidence, self.source_hash, lambda: getattr(self, 'values', {}))
        self.current_release = self.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT).stdout.decode().strip()
        require(re.fullmatch('[0-9a-f]{40}', self.current_release), 'invalid source commit')
        self.release = V126_RUNTIME_SOURCE
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
                            'env_file': ['.env'],
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
            error = FixtureAssertionError('test command deadline; remote mutation outcome is not inferred')
            error.runtime_failure = self.failure_context(diagnostic_category, 124, True)
            raise error from None
        result = subprocess.CompletedProcess(argv, process.returncode, out, err)
        elapsed = time.monotonic() - before
        event = {'consumer': Path(str(argv[0])).name, 'exit': result.returncode, 'seconds': round(elapsed, 3)}
        if diagnostic_category or result.returncode:
            event['diagnostic'] = self.diagnostics.command(diagnostic_category or 'metadata', event['consumer'],
                                                          result.returncode, elapsed, timeout, out, err)
        self.events.append(event)
        if check and result.returncode:
            error = FixtureAssertionError('integration consumer failed: ' + Path(str(argv[0])).name + ' exit ' + str(result.returncode))
            error.runtime_failure = self.failure_context(diagnostic_category, result.returncode, False)
            raise error
        return result

    def failure_context(self, category, status, timed_out):
        return dict(stage='BUILD' if category == 'build' else 'UNKNOWN',
                    consumer=self.diagnostics.records[-1]['consumer'] if category == 'build' else 'UNKNOWN',
                    reason_category=self.diagnostics.records[-1].get('reason_category', 'UNKNOWN'),
                    exit_code=status, timed_out=timed_out, children='UNKNOWN', supervisor_exit_code=None)

    def compose(self, *args, **kwargs):
        return self.run(['docker', 'compose', '--env-file', '.env', '--file', 'docker-compose.yml', *args],
                        env={'BACKEND_IMAGE': self.image}, **kwargs)

    def write_inputs(self):
        (self.root / '.env').write_text(''.join(k + '=' + v + '\n' for k, v in self.values.items()))
        (self.root / '.env').chmod(0o600)
        (self.root / 'docker-compose.yml').write_text(json.dumps(self.compose_doc, indent=2) + '\n')
        (self.root / 'docker-compose.yml').chmod(0o600)
        self.env_sha = hashlib.sha256((self.root / '.env').read_bytes()).hexdigest()

    def inspect(self, cid, *, timeout=90):
        rows = json.loads(self.run(['docker', 'inspect', cid], timeout=timeout).stdout)
        require(len(rows) == 1 and rows[0]['Id'] == cid and rows[0]['Config']['Labels'].get(LABEL) == self.token,
                'owned container identity mismatch')
        return rows[0]

    def owned_endpoint(self, cid, port):
        require(re.fullmatch('[0-9a-f]{64}', cid or ''), 'relay requires one exact owned container ID')
        service = 'postgres' if cid == self.postgres and port in (5432, 443) else 'backend'
        require(service == 'postgres' or (cid == self.backend_relay_cid and port == 8080),
                'relay target is outside the selected source/backend services')
        row = self.inspect(cid, timeout=5)
        labels = row['Config']['Labels']
        require(labels.get('com.docker.compose.project') == self.project
                and labels.get('com.docker.compose.service') == service
                and row['State'].get('Running') is True and row['State'].get('Status') == 'running'
                and not row['State'].get('OOMKilled'), 'owned relay container is not running in the exact fixture')
        endpoints = row['NetworkSettings']['Networks']
        require(set(endpoints) == {self.network}, 'owned relay requires exactly the internal fixture network')
        endpoint = endpoints[self.network]
        network_rows = json.loads(self.run(['docker', 'network', 'inspect', self.network], timeout=5).stdout)
        require(len(network_rows) == 1, 'owned relay network is not unique')
        network = network_rows[0]
        require(network.get('Internal') is True and network.get('Name') == self.network
                and network.get('Labels', {}).get(LABEL) == self.token
                and network.get('Id') == endpoint.get('NetworkID')
                and network.get('Containers', {}).get(cid, {}).get('EndpointID') == endpoint.get('EndpointID')
                and endpoint.get('EndpointID'), 'owned relay network identity or endpoint differs')
        address = ipaddress.IPv4Address(endpoint.get('IPAddress', ''))
        require(address.is_private and not address.is_loopback and not address.is_unspecified
                and not address.is_link_local and not address.is_multicast
                and ipaddress.ip_interface(network['Containers'][cid]['IPv4Address']).ip == address,
                'owned relay bridge address differs from the exact container endpoint')
        return str(address), port

    def relay(self, resolver, port=0):
        relay = LoopbackRelay(resolver, port)
        self.relays.append(relay)
        return relay

    def wait_for_source_ready(self):
        deadline = time.monotonic() + 60
        previous = None
        while True:
            source = self.inspect(self.postgres, timeout=5)
            fact = {'checkpoint': 'OWNED_PG17_STATE', 'running': source['State'].get('Running'),
                    'state': source['State'].get('Status'), 'exit_code': source['State'].get('ExitCode'),
                    'oom_killed': source['State'].get('OOMKilled'),
                    'docker_published_ports': sorted(source['NetworkSettings'].get('Ports', {}))}
            if fact != previous:
                self.events.append(fact)
                previous = fact
            require(source['State'].get('Running') is True and source['State'].get('Status') == 'running'
                    and not source['State'].get('OOMKilled'), 'owned PostgreSQL exited before readiness')
            check = self.run(['docker', 'exec', self.postgres, 'pg_isready', '-h', '127.0.0.1', '-U', 'runtime_fixture',
                              '-d', 'runtime_fixture'], check=False, timeout=5)
            if check.returncode == 0:
                break
            require(time.monotonic() < deadline, 'owned PG17 did not become ready')
            time.sleep(0.25)
        require(self.sql('SHOW server_version_num;').startswith(b'17'), 'owned server must be PG17')
        self.events.append({'checkpoint': 'OWNED_PG17_READY_BEFORE_RELAY', 'running': source['State']['Running'],
                            'state': source['State']['Status'], 'exit_code': source['State'].get('ExitCode'),
                            'docker_published_ports': sorted(source['NetworkSettings'].get('Ports', {}))})

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
        # Container creation already bound this ID/label. Resolve and revalidate its
        # current bridge address only when a real HTTP consumer opens a connection;
        # an immediately exited JVM must still reach the actual readiness observer.
        self.backend_relay_cid = cid

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

    def historical_source(self):
        self.run(['git', 'merge-base', '--is-ancestor', V126_RUNTIME_SOURCE, self.current_release], cwd=ROOT)
        tree = self.run(['git', 'rev-parse', V126_RUNTIME_SOURCE + ':' + MIGRATION_PATH], cwd=ROOT).stdout.decode().strip()
        require(tree == V126_MIGRATION_TREE, 'historical runtime migration identity changed')
        source = self.root / 'historical-source'
        source.mkdir()
        archive = self.run(['git', 'archive', '--format=tar', V126_RUNTIME_SOURCE], cwd=ROOT).stdout
        self.run(['tar', '-xf', '-', '-C', source], input=archive)
        return source

    def build_image(self, source, library):
        self.images.append(self.image)  # Retain ownership even if build/inspection fails.
        self.run(['docker', 'build', '--label', LABEL + '=' + self.token, '--tag', self.image,
                  '--file', 'backend/Dockerfile', '.'], cwd=source, timeout=900, diagnostic_category='build')
        self.image_id = self.run(['docker', 'image', 'inspect', '--format', '{{.Id}}', self.image]).stdout.decode().strip()
        require(re.fullmatch('sha256:[0-9a-f]{64}', self.image_id), 'test image identity unavailable')
        self.copy_container = self.run(['docker', 'create', '--label', LABEL + '=' + self.token,
                                        self.image, '/bin/true']).stdout.decode().strip()
        self.run(['docker', 'cp', self.copy_container + ':/app/app/lib', str(library)])
        migrations = {str(path.relative_to(source / 'backend/app/src/main/resources')): path.read_bytes()
                      for path in (source / MIGRATION_PATH / 'postgresql').glob('*.sql')}
        embedded = {}
        for jar in library.glob('*.jar'):
            with zipfile.ZipFile(jar) as archive:
                for name in archive.namelist():
                    if name.startswith('db/migration/postgresql/') and name.endswith('.sql'):
                        require(name not in embedded, 'duplicate migration resource in actual runtime classpath')
                        embedded[name] = archive.read(name)
        require(embedded == migrations, 'actual runtime image migration bytes differ from unchanged source')
        self.events.append({'checkpoint': 'IMAGE_MIGRATION_BYTES_EXACT', 'source_commit': self.release,
                            'image_id': self.image_id, 'files': len(migrations),
                            'v126_sha256': hashlib.sha256(next(value for key, value in embedded.items()
                                                             if '/V126__' in key)).hexdigest()})
        self.run(['docker', 'rm', self.copy_container])
        self.copy_container = None

    def prepare(self):
        self.started_at = str(int(time.time()) - 1)
        for command in (['docker', 'version', '--format', '{{.Server.Version}}'], ['docker', 'compose', 'version'],
                        ['java', '-version'], ['psql', '--version'], ['pg_dump', '--version']):
            version = self.run(command)
            self.events.append({'version': (version.stdout + version.stderr).decode().strip()})
        require(b' 17.' in self.run(['pg_dump', '--version']).stdout, 'host pg_dump17 is required')
        self.build_image(self.historical_source(), self.root / 'lib')
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
        self.wait_for_source_ready()
        self.owned_endpoint(self.postgres, 443)
        self.owned_endpoint(self.postgres, 5432)
        self.provider_port = self.relay(lambda: self.owned_endpoint(self.postgres, 443)).port
        pgport = self.relay(lambda: self.owned_endpoint(self.postgres, 5432)).port
        self.relay(lambda: self.owned_endpoint(self.backend_relay_cid, 8080), port=8080)
        self.uri = 'postgresql://runtime_fixture:' + self.password + '@127.0.0.1:' + str(pgport) + '/runtime_fixture'
        self.events.append({'checkpoint': 'OWNED_LOOPBACK_RELAY_TRANSPORT', 'internal_network': True,
                            'source_ports': [443, 5432], 'backend_port': 8080,
                            'scope': 'test-only byte relay; real libpq/curl/JVM consumers unchanged'})
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
        self.compose('create', '--no-build', 'backend')
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
                self.owned_endpoint(self.postgres, 443)
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
            self.compose('create', '--no-build', 'backend')
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
        self.values = original
        self.write_inputs()

    def history(self):
        # Flyway metadata only; no credentials or domain rows are emitted.
        return [json.loads(row) for row in self.sql(
            'SELECT row_to_json(h) FROM flyway_schema_history h ORDER BY installed_rank;').splitlines()]

    def forward_sequence(self):
        before = self.history()
        verify_historical_history(before)
        photo = ROOT / MIGRATION_PATH / 'postgresql' / V127_SCRIPT
        require(hashlib.sha256(photo.read_bytes()).hexdigest() == V127_SHA256, 'unreviewed current migration bytes')
        media = ROOT / MIGRATION_PATH / 'postgresql' / V128_SCRIPT
        require(hashlib.sha256(media.read_bytes()).hexdigest() == V128_SHA256, 'unreviewed media migration bytes')
        self.release = self.current_release
        self.image = 'ht-v127-runtime-fixture:' + self.token
        self.build_image(ROOT, self.root / 'current-lib')
        self.values.update(APP_VERSION=self.release, BACKEND_IMAGE=self.image)
        self.write_inputs()
        self.compose('create', '--no-build', 'backend')
        cid = self.cid('backend')
        self.start_once(cid)
        result, output, _ = self.observe(cid)
        require(result.returncode == 0 and b'READINESS=READY' in output, 'current JVM did not become ready after V128')
        self.check_starts(cid)
        after = self.history()
        verify_forward_history(before, after)
        # The current image's real Flyway validates every checksum and absence of pending SQL.
        self.run(['docker', 'exec', cid, 'java', '-cp', '/app/app/lib/*:/fixture',
                  'V126RuntimeFixture', 'validate128'], timeout=60)
        require(self.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                         "AND table_name IN ('staff_profile_photos','staff_profile_photo_state');") == b'2\n',
                'photo schema missing after current JVM migration')
        rejected = self.shell('remote_compose() { command docker compose --env-file .env --file docker-compose.yml "$@"; }\n'
                              'remote_assert_schema_v126\n', env={'BACKEND_IMAGE': self.image}, check=False)
        require(rejected.returncode == 3, 'historical V126 schema guard accepted forward schema')
        self.events.append({'checkpoint': 'ACTUAL_CURRENT_JVM_V126_TO_V128', 'source_commit': self.release,
                            'image_id': self.image_id, 'historical_rows_unchanged': 126,
                            'history_sha256': hashlib.sha256(json.dumps(after, sort_keys=True).encode()).hexdigest(),
                            'forward_version': after[-1]['version'], 'forward_checksum': after[-1]['checksum'],
                            'historical_schema_guard_exit': rejected.returncode,
                            'rollback_safety': 'NOT_CLAIMED'})
        self.compose('rm', '--stop', '--force', 'backend')

    def cleanup(self):
        try:
            self.cleanup_containers()
        finally:
            failures = []
            for relay in self.relays:
                try:
                    relay.close()
                except FixtureAssertionError:
                    failures.append(relay)
            self.events.append({'checkpoint': 'OWNED_LOOPBACK_RELAYS_CLOSED', 'count': len(self.relays),
                                'connection_count': sum(relay.connections for relay in self.relays),
                                'refused_connections': sum(relay.refusals for relay in self.relays)})
            require(not failures, 'owned loopback relay cleanup incomplete')

    def cleanup_containers(self):
        failed = []
        if self.copy_container:
            if self.run(['docker', 'rm', '--force', self.copy_container], check=False).returncode:
                failed.append('image-inspection fixture container')
        if (self.root / 'docker-compose.yml').exists():
            if self.compose('down', '--volumes', '--remove-orphans', '--timeout', '5', check=False, timeout=90).returncode:
                failed.append('owned Compose resources')
        for name in self.images:
            image = self.run(['docker', 'image', 'inspect', name], check=False)
            if image.returncode == 0:
                rows = json.loads(image.stdout)
                require(len(rows) == 1 and rows[0]['Config']['Labels'].get(LABEL) == self.token,
                        'refuse cleanup of an image with different ownership')
                if self.run(['docker', 'image', 'rm', '--no-prune', name], check=False).returncode:
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
        fixture.forward_sequence()
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

    def test_metadata_scan_scaling_in_bounded_subprocess(self):
        probe = '''
import importlib.util, json, pathlib, sys, time
spec = importlib.util.spec_from_file_location('metadata_scaling_probe', sys.argv[1])
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
diagnostics = runtime.Diagnostics(pathlib.Path(sys.argv[2]), 'a' * 64, lambda: {})
diagnostics.stream(b'could not parse ', True, build_failure=True)
measurements = []
for kib in (16, 32, 64, 128, 256):
    payload = b'could not parse ' * (kib * 1024 // 16)
    samples = []
    for _ in range(3):
        start = time.process_time()
        result = diagnostics.stream(payload, True, build_failure=True)
        samples.append(time.process_time() - start)
    seconds = sorted(samples)[1]
    measurements.append({'kib': kib, 'cpu_seconds': seconds})
    # Stop at a demonstrated bad slope rather than amplify a known regression.
    if seconds > measurements[0]['cpu_seconds'] * (kib / 16) * 3:
        break
else:
    diagnostics.command('build', 'docker', 1, .01, 1, b'', payload)
print(json.dumps(measurements))
'''
        try:
            result = subprocess.run([sys.executable, '-B', '-c', probe, str(Path(__file__).resolve()), str(self.evidence)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
        except subprocess.TimeoutExpired:
            self.fail('metadata scan exceeded the protective subprocess deadline')
        self.assertEqual(result.returncode, 0, 'metadata scaling subprocess failed')
        measurements = json.loads(result.stdout)
        for row in measurements:
            self.assertLessEqual(row['cpu_seconds'] / measurements[0]['cpu_seconds'], row['kib'] / 16 * 3,
                                 'superlinear metadata scan: ' + json.dumps(measurements))
        self.assertEqual([row['kib'] for row in measurements], [16, 32, 64, 128, 256])

    def test_metadata_scan_long_lines_keep_only_closed_public_indicators(self):
        repeated = 'could not parse ' * 16384
        cases = [('no suffix', repeated, 'UNKNOWN', True),
                 ('suffix before prefixes', 'POM metadata ' + repeated, 'UNKNOWN', True),
                 ('early POM', 'Could not parse POM ' + repeated, 'METADATA_INDICATOR', False),
                 ('early metadata', 'invalid metadata ' + repeated, 'METADATA_INDICATOR', False),
                 ('late error', 'x ' * 131072 + 'failed to parse POM', 'METADATA_INDICATOR', False),
                 ('single suffix', repeated + 'metadata', 'METADATA_INDICATOR', False),
                 ('separate lines', 'could not parse\nPOM metadata', 'UNKNOWN', True),
                 ('many lines', 'could not parse\n' * 1024 + 'invalid POM', 'METADATA_INDICATOR', True),
                 ('unknown', 'unclassified error', 'UNKNOWN', True),
                 ('Gradle POM', '* What went wrong:\nCould not resolve private:module:1\n'
                  'Could not parse POM\n* Try:\n', 'METADATA_INDICATOR', True)]
        canary = 'opaque-private-' + uuid.uuid4().hex
        private = ' ' + canary + ' https://user:' + canary + '@private.invalid/a /private/' + canary
        private += r' C:\private\config.xml \\host\share\config.xml file:///private/config.xml'
        for label, text, reason, truncated in cases:
            with self.subTest(case=label):
                result = self.assert_isolated_public_clean((text + private).encode(),
                            [canary, 'private.invalid', 'private:module:1', 'config.xml'])
                self.assertEqual(result['reason_category'], reason)
                self.assertEqual(result['stderr']['excerpt_format'], 'safe-diagnostic-summary')
                self.assertEqual(result['stderr']['truncated'], truncated)
                self.assertEqual(result['stderr']['reason_codes'], [])
                if reason == 'METADATA_INDICATOR':
                    self.assertEqual(result['stderr']['excerpt'].count('Could not parse POM metadata'), 1)

    def assert_public_diagnostic_clean(self, payload, forbidden):
        name = self.diagnostics.command('build', 'docker', 1, 1, 900, b'', payload)
        self.diagnostics.failure('integration', FixtureAssertionError('synthetic build failed'))
        public = io.StringIO()
        with contextlib.redirect_stdout(public):
            self.diagnostics.emit_failure()
        stored = (self.evidence / name).read_text()
        records = [json.loads(line.split(' ', 1)[1]) for line in public.getvalue().splitlines()]
        build = next(record for record in reversed(records) if record.get('category') == 'build')
        self.assertEqual(json.loads(stored), build)
        self.assertEqual(build['stderr']['sha256'], hashlib.sha256(payload).hexdigest())
        self.assertEqual(build['stderr']['bytes'], len(payload))
        self.assertLessEqual(len(json.dumps(build['stderr']['excerpt'], ensure_ascii=True).encode()), 4096)
        for value in forbidden:
            variants = {value}
            for _ in range(3):
                variants.update(json.dumps(item, ensure_ascii=True)[1:-1] for item in tuple(variants))
            for item in variants:
                self.assertNotIn(item, self.diagnostics.redact(payload.decode('utf-8')))
                self.assertNotIn(item, stored)
                self.assertNotIn(item, public.getvalue())
        return build

    def test_json_credentials_are_redacted_through_stored_and_emitted_diagnostics(self):
        for key in ['API_TOKEN', 'password', 'SeCrEt', 'api_key', 'apiKey', 'AUTHORIZATION',
                    'client_secret', 'refresh_token', 'DB_PASSWORD', 'VENUE_STAFF_INVITE_SECRET_PEPPER']:
            for spacing in [(',', ':'), (', ', ': ')]:
                value = 'unknown-canary-quote"-slash\\-line\n-界'
                document = json.dumps({key: value, 'status': 'FAILED', 'exit': 1}, separators=spacing)
                for depth in range(3):
                    with self.subTest(key=key, spacing=spacing, encoding_depth=depth):
                        payload = ('* What went wrong:\nCould not resolve fixture:module:1\n' + document + '\n* Try:\n').encode()
                        result = self.assert_public_diagnostic_clean(payload, [value, 'unknown-canary'])
                        self.assertIn('FAILED', result['stderr']['excerpt'])
                        self.assertIn('exit', result['stderr']['excerpt'])
                    document = json.dumps(document)

    def assert_isolated_public_clean(self, payload, forbidden):
        previous_evidence, previous_diagnostics = self.evidence, self.diagnostics
        with tempfile.TemporaryDirectory(dir=self.evidence) as directory:
            self.evidence = Path(directory)
            self.diagnostics = Diagnostics(self.evidence, previous_diagnostics.source_hash, lambda: self.values)
            try:
                return self.assert_public_diagnostic_clean(payload, forbidden)
            finally:
                self.evidence, self.diagnostics = previous_evidence, previous_diagnostics

    def test_hardening_partial_scalar_credentials_fail_closed_at_public_boundary(self):
        for prefix in ('true', 'false', 'null', '123', 'NaN'):
            canary = 'opaque' + uuid.uuid4().hex
            document = '{"password":' + prefix + canary + '}'
            for padding in (0, 1900, 2050, 4090):
                with self.subTest(prefix=prefix, padding=padding):
                    payload = ('x' * padding + document + '\n' + 'BuildKit progress\n' * 400).encode()
                    self.assert_isolated_public_clean(payload, [canary])

    def test_hardening_single_quotes_and_damaged_serialization_fail_closed(self):
        canary = 'opaque' + uuid.uuid4().hex
        documents = ["{'password': '" + canary + "'}",
                     "{'API_TOKEN': {'nested': ['" + canary + "']}}",
                     "{'\\u0070assword': '" + canary + "'}",
                     json.dumps('{"password" : "' + canary + '"}').replace(' ', r'\u0020')[:-1],
                     '{"pass\x1b[0mword":"' + canary + '"}',
                     'unclosed "\n{"token":"' + canary + '"}']
        for index, document in enumerate(documents):
            for depth in range(3):
                with self.subTest(index=index, depth=depth):
                    self.assert_isolated_public_clean(document.encode(), [canary])
                document = json.dumps(document)

    def test_hardening_path_punctuation_and_escaped_messages_do_not_leak(self):
        canary = 'opaque' + uuid.uuid4().hex
        for path in ['/@' + canary + '/config.xml', '/+' + canary + '/config.xml',
                     "/private/parent's/" + canary + '/config.xml',
                     'C:\\folder with spaces\\' + canary + '\\config.xml',
                     '\\\\host\\share\\' + canary + '\\config.xml',
                     'file:///private/' + canary + '/config.xml']:
            message = 'error:' + path
            document = json.dumps({'message': 'open failed at ' + message})
            for index, text in enumerate([message, document, json.dumps(document),
                                          document.replace('/', r'\/'), document.replace('/', r'\u002f')]):
                with self.subTest(path_kind=path[:2], encoding=index):
                    self.assert_isolated_public_clean(text.encode(), [canary, 'config.xml'])

    def test_hardening_error_indicators_survive_ambiguous_paths(self):
        canary = 'opaque' + uuid.uuid4().hex
        for reason in ('Unresolved reference', 'ENOSPC', 'Connection reset'):
            for message in ['error:/private/' + canary + '/file: ' + reason,
                            'e: /@' + canary + '/Source.kt:12:13: ' + reason]:
                for text in (message, json.dumps({'message': message})):
                    with self.subTest(reason=reason, structured=text.startswith('{')):
                        result = self.assert_isolated_public_clean(text.encode(), [canary])
                        self.assertIn(reason, result['stderr']['excerpt'])
                        if reason == 'Connection reset':
                            self.assertEqual(result['reason_category'], 'NETWORK_INDICATOR')

    def test_hardening_unclassified_text_is_not_published(self):
        canary = uuid.uuid4().hex
        for document in [canary, json.dumps({'message': canary}),
                         json.dumps({'unexpected': [canary]}), '{broken ' + canary]:
            with self.subTest(structured=document.startswith('{')):
                result = self.assert_isolated_public_clean(document.encode(), [canary])
                self.assertEqual(result['reason_category'], 'UNKNOWN')

    def test_hardening_malformed_and_bounded_structures_cannot_break_capture(self):
        canary = uuid.uuid4().hex
        documents = ['{[]: "' + canary + '"}', '{"token": true' + canary + ',}',
                     '[' * 2000 + json.dumps(canary) + ']' * 2000,
                     json.dumps({'token': canary, 'extra': 'x' * 70000}),
                     json.dumps({'token': canary, 'extra': '界' * 40000}, ensure_ascii=False),
                     json.dumps({canary: canary}), json.dumps([canary] * 300)]
        for index, document in enumerate(documents):
            with self.subTest(index=index):
                result = self.assert_isolated_public_clean(document.encode(), [canary])
                self.assertEqual(result['stderr']['excerpt_format'], 'safe-diagnostic-summary')

    def test_hardening_reason_codes_survive_excerpt_selection(self):
        for reason, code in [('Unresolved reference', 'UNRESOLVED_REFERENCE'),
                             ('ENOSPC', 'ENOSPC'), ('Connection reset', 'CONNECTION_RESET')]:
            payload = ('* What went wrong:\n' + 'BuildKit progress\n' * 1000
                       + reason + '\n* Try:\n').encode()
            result = self.assert_isolated_public_clean(payload, [])
            self.assertTrue(result['stderr']['truncated'])
            self.assertNotIn(reason, result['stderr']['excerpt'])
            self.assertIn(code, result['stderr']['reason_codes'])

    def test_hardening_structured_credential_values_are_not_reason_evidence(self):
        result = self.assert_isolated_public_clean(json.dumps({'token': 'Connection reset ENOSPC'}).encode(),
                                                   ['Connection reset', 'ENOSPC'])
        self.assertEqual(result['reason_category'], 'UNKNOWN')
        self.assertEqual(result['stderr']['reason_codes'], [])

    def test_sensitive_json_values_are_removed_conservatively_at_truncation_boundary(self):
        secret = 'boundary-canary-' + 's' * 6000 + '"\\end'
        documents = [json.dumps({'api_key': secret, 'status': 'FAILED'}),
                     '{"\\u0070assword":' + json.dumps(secret) + ',"status":"FAILED"}',
                     json.dumps({'token': {'nested': [secret]}, 'status': 'FAILED'}),
                     '{"secret":' + json.dumps(secret)[:-1]]
        for document in documents:
            with self.subTest(document_kind=documents.index(document)):
                payload = ('prelude\n* What went wrong:\nCould not resolve fixture:module:1\n' + 'safe ' * 380
                           + document + '\n' + 'tail ' * 2000).encode()
                result = self.assert_public_diagnostic_clean(payload, [secret, 'boundary-canary', 's' * 30])
                self.assertTrue(result['stderr']['truncated'])

    def test_absolute_paths_and_uris_after_colon_are_removed_from_public_diagnostics(self):
        paths = ['/private/path-canary/config.xml', '/home/path-canary/config.xml',
                 '/private/folder with path-canary/config.xml', r'C:\folder with path-canary\config.xml',
                 r'C:\private\path-canary\config.xml', r'\\host\share\path-canary\config.xml',
                 'file:/private/path-canary/config.xml', 'file:///private/path-canary/config.xml',
                 'file://host/share/path-canary/config.xml']
        for path in paths:
            for text in ['error:' + path, json.dumps({'path': 'error:' + path}),
                         json.dumps({'path': 'error:' + path}).replace('/', r'\/'),
                         '{"path":"error:\\u002fprivate\\u002fpath-canary\\u002fconfig.xml"}']:
                with self.subTest(path_kind=paths.index(path)):
                    payload = ('* What went wrong:\nCould not parse POM metadata\n' + text + '\n* Try:\n').encode()
                    result = self.assert_public_diagnostic_clean(payload, ['path-canary', 'config.xml'])
                    self.assertIn('Could not parse POM metadata', result['stderr']['excerpt'])
                    self.assertEqual(result['reason_category'], 'METADATA_INDICATOR')

    def test_build_without_gradle_marker_preserves_clean_head_and_error_tail(self):
        payload = ('BuildKit START\n' + 'BuildKit progress\n' * 3000
                   + '{"API_TOKEN":"fallback-canary"}\nerror:/private/path-canary/config.xml\n'
                   + 'npm ERR! code E404\nnpm ERR! actual build failure\n').encode()
        result = self.assert_public_diagnostic_clean(payload, ['fallback-canary', 'path-canary', 'config.xml'])
        excerpt = result['stderr']['excerpt']
        self.assertIn('BuildKit START', excerpt)
        self.assertIn('npm ERR! code E404', excerpt)
        self.assertIn('npm ERR! build failure', excerpt)
        self.assertNotIn('npm ERR! actual build failure', excerpt)
        self.assertEqual(result['stderr']['excerpt_format'], 'safe-diagnostic-summary')
        self.assertTrue(result['stderr']['truncated'])

    def test_short_build_fallback_is_not_marked_truncated(self):
        result = self.assert_public_diagnostic_clean(b'npm ERR! code E404\n', [])
        self.assertEqual(result['stderr']['excerpt'], 'npm ERR! code E404\n')
        self.assertFalse(result['stderr']['truncated'])

    def test_ambiguous_and_deeply_encoded_credentials_are_removed_conservatively(self):
        partial = json.dumps(json.dumps({'API_TOKEN': 'partial-canary'}))[:-1]
        unicode_key = json.dumps('{\n"\\u0070assword"\n : "unicode-canary"\n}')[:-1]
        deep = json.dumps({'api_key': 'deep-canary'})
        for _ in range(7):
            deep = json.dumps(deep)
        for text, canary in [(partial, 'partial-canary'), (unicode_key, 'unicode-canary'), (deep, 'deep-canary'),
                             ('API_KEY=' + json.dumps('plain-canary-"\\'), 'plain-canary')]:
            with self.subTest(kind=canary):
                self.assert_public_diagnostic_clean(('Could not resolve fixture:module:1\n' + text).encode(), [canary])

    def test_build_middle_cause_survives_progress_and_redacts_before_selection(self):
        payload = ('progress\n' * 2000 + '\n#27 * What went wrong:\n'
                   '#27 Could not resolve example:library:1.0.\n'
                   '#27 Could not GET https://unknown:credential@private.invalid/repository?token=private\n'
                   '#27 Connection reset at /home/private-user/project/config.xml\n'
                   '#27 DB_PASSWORD=' + self.values['DB_PASSWORD'] + '\n#27 * Try:\n' + 'tail\n' * 2000).encode()
        name = self.diagnostics.command('build', 'docker', 1, 40, 900, b'', payload)
        record = json.loads((self.evidence / name).read_bytes())
        excerpt = record['stderr']['excerpt']
        self.assertIn('Could not resolve', excerpt)
        self.assertNotIn('example:library:1.0', excerpt)
        self.assertEqual(record['stderr']['excerpt_format'], 'safe-diagnostic-summary')
        self.assertIn('Connection reset', excerpt)
        self.assertEqual(record['reason_category'], 'NETWORK_INDICATOR')
        self.assertEqual(record['stderr']['sha256'], hashlib.sha256(payload).hexdigest())
        self.assertEqual(record['stderr']['bytes'], len(payload))
        self.assertTrue(record['stderr']['truncated'])
        for secret in ['unknown:credential', 'private.invalid', 'private-user', 'config.xml', self.values['DB_PASSWORD']]:
            self.assertNotIn(secret, excerpt)
        self.assertLessEqual(len(json.dumps(excerpt, ensure_ascii=True).encode()), 4096)

    def test_build_categories_are_indicators_and_unknown_is_preserved(self):
        for text, expected in [('Could not resolve example:missing:1', 'DEPENDENCY_RESOLUTION'),
                               ('Could not parse POM metadata', 'METADATA_INDICATOR'),
                               ('UnknownHost repository', 'NETWORK_INDICATOR'),
                               ('unclassified failure', 'UNKNOWN'),
                               ('metadataSources { ... }', 'UNKNOWN')]:
            with self.subTest(expected=expected):
                self.assertEqual(self.diagnostics.build_reason(text.encode(), False), expected)
        self.assertEqual(self.diagnostics.build_reason(b'Could not resolve example', True), 'TIMEOUT')

    def test_unknown_url_credentials_paths_and_multibyte_excerpt_are_bounded(self):
        for url in ['https://other:credential@private.invalid/a', 'https:\\/\\/other:credential@private.invalid/a',
                    quote('https://other:credential@private.invalid/a', safe='')]:
            text = url + ' "/private/path with spaces/key" C:\\private\\key API_TOKEN=unknown-secret Authorization: Bearer opaque-credential'
            sanitized = self.diagnostics.redact(text)
            for secret in ['other:credential', 'other%3Acredential', 'private.invalid', 'spaces', 'unknown-secret',
                           'C:\\private', 'opaque-credential']:
                self.assertNotIn(secret, sanitized)
        payload = ('noise\n' * 5000 + '* What went wrong:\nCould not resolve ' + '界' * 10000).encode()
        result = self.diagnostics.stream(payload, True, build_failure=True)
        self.assertIn('Could not resolve', result['excerpt'])
        self.assertLessEqual(len(json.dumps(result['excerpt'], ensure_ascii=True).encode()), 4096)

    def test_build_failure_context_survives_later_cleanup(self):
        fixture = Integration.__new__(Integration)
        fixture.root, fixture.events, fixture.diagnostics = self.evidence, [], self.diagnostics
        with patch.object(subprocess, 'Popen') as popen:
            popen.return_value.communicate.return_value = (b'', b'* What went wrong:\nCould not resolve example:missing:1\n')
            popen.return_value.returncode = 1
            try:
                fixture.run(['docker', 'build', '.'], diagnostic_category='build', timeout=900)
            except FixtureAssertionError as error:
                primary = error
            else:
                self.fail('failed build accepted')
        self.assertEqual(primary.runtime_failure['stage'], 'BUILD')
        self.assertEqual(primary.runtime_failure['exit_code'], 1)
        with contextlib.redirect_stdout(io.StringIO()):
            execute_fixture(self.fixture(primary_error=primary, cleanup_error=RuntimeError('private cleanup')))
        result = json.loads((self.evidence / 'result.json').read_text())
        self.assertEqual(result['first_failure']['consumer_failure'], primary.runtime_failure)
        self.assertEqual(result['first_failure']['consumer_failure']['children'], 'UNKNOWN')

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
        self.assertNotIn('tail', result['excerpt'])
        self.assertEqual(result['excerpt_format'], 'safe-diagnostic-summary')
        record = {'excerpt': result['excerpt'], 'fixture_control_probe': '::error::\n::stop-commands::fixture'}
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

            def forward_sequence(inner):
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
        self.assertEqual(result['first_failure']['message']['excerpt'], '[synthetic-secret-redacted]')
        self.assertEqual(result['first_failure']['message']['excerpt_format'], 'safe-diagnostic-summary')
        self.assertEqual([item['phase'] for item in self.diagnostics.failures], ['integration', 'cleanup'])
        self.assertNotIn('excerpt', self.diagnostics.failures[1]['message'])
        self.assertNotIn(self.secret, output.getvalue())
        self.assertIn('[synthetic-secret-redacted]', output.getvalue())
        self.assertNotIn('first failure ', output.getvalue())
        self.assertTrue(all(line.startswith('V126_RUNTIME_DIAGNOSTIC ') for line in output.getvalue().splitlines()))

    def test_success_does_not_emit_expected_negative_observer_diagnostics(self):
        self.diagnostics.command('readiness', 'bash', 4, .5, 135, b'', b'READINESS=FAILED')
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = execute_fixture(self.fixture())
        self.assertEqual(status, 0)
        self.assertEqual(output.getvalue(), '')
        self.assertEqual(json.loads((self.evidence / 'result.json').read_text())['status'], 'PASSED')

    def test_unknown_failure_keeps_only_safe_own_traceback_locations(self):
        try:
            raise KeyError('SYNTHETIC_PRIVATE_EXCEPTION_VALUE')
        except KeyError as error:
            self.diagnostics.failure('integration', error)
        record = self.diagnostics.failures[0]
        self.assertNotIn('excerpt', record['message'])
        self.assertNotIn('SYNTHETIC_PRIVATE_EXCEPTION_VALUE', json.dumps(record))
        self.assertEqual(record['locations'][-1]['function'], 'test_unknown_failure_keeps_only_safe_own_traceback_locations')
        self.assertEqual(record['locations'][-1]['file'], 'scripts/test-v126-linux-runtime.py')
        self.assertGreater(record['locations'][-1]['line'], 0)


class MigrationRuntimeTest(unittest.TestCase):
    def test_historical_context_is_complete_pinned_source_with_original_migration_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = Integration(Path(directory) / 'evidence')
            try:
                source = fixture.historical_source()
                self.assertEqual(fixture.release, V126_RUNTIME_SOURCE)
                self.assertEqual(fixture.values['APP_VERSION'], V126_RUNTIME_SOURCE)
                for name in ('backend/Dockerfile', 'backend/app/src/main/kotlin/com/hookah/platform/backend/Application.kt'):
                    expected = fixture.run(['git', 'show', V126_RUNTIME_SOURCE + ':' + name], cwd=ROOT).stdout
                    self.assertEqual((source / name).read_bytes(), expected)
                self.assertEqual(len(list((source / MIGRATION_PATH / 'postgresql').glob('*.sql'))), 126)
                self.assertFalse((source / MIGRATION_PATH / 'postgresql' / V127_SCRIPT).exists())
                # Missing/wrong pinned identity must fail before extracting/building.
                with patch(__name__ + '.V126_MIGRATION_TREE', '0' * 40), self.assertRaises(FixtureAssertionError):
                    fixture.historical_source()
            finally:
                fixture.temp.cleanup()

    def test_forward_requires_exact_history_and_only_reviewed_tail(self):
        before = [dict(version=str(v), script='V' + str(v) + '__fixture.sql', checksum=v,
                       success=True, type='SQL', installed_rank=v) for v in range(1, 127)]
        tail = dict(version='127', script=V127_SCRIPT, checksum=127, success=True, type='SQL', installed_rank=127)
        media_tail = dict(version='128', script=V128_SCRIPT, checksum=128, success=True, type='SQL', installed_rank=128)
        after = before + [tail, media_tail]
        verify_forward_history(before, after)
        for key, value in (('checksum', -1), ('script', 'changed.sql'), ('success', False), ('installed_rank', 999)):
            changed = [dict(row) for row in after]
            changed[0][key] = value
            with self.subTest(historical=key), self.assertRaises(FixtureAssertionError):
                verify_forward_history(before, changed)
        for key, value in (('version', '128'), ('script', 'unknown.sql'), ('success', False), ('type', 'BASELINE')):
            with self.subTest(tail=key), self.assertRaises(FixtureAssertionError):
                verify_forward_history(before, before + [dict(tail, **{key: value}), media_tail])
        for key, value in (('version', '129'), ('script', 'unknown.sql'), ('success', False), ('type', 'BASELINE')):
            with self.subTest(media_tail=key), self.assertRaises(FixtureAssertionError):
                verify_forward_history(before, before + [tail, dict(media_tail, **{key: value})])
        for changed in (before, before + [tail], after + [tail], after[1:]):
            with self.assertRaises(FixtureAssertionError):
                verify_forward_history(before, changed)
        with self.assertRaises(FixtureAssertionError):
            verify_historical_history(before[:-1])


class TransportTest(unittest.TestCase):
    def setUp(self):
        self.relays, self.servers = [], []

    def tearDown(self):
        for relay in self.relays:
            relay.close()
        for server, thread, release in self.servers:
            release.set()
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)
            self.assertFalse(thread.is_alive())

    def server(self, idle=False):
        release = threading.Event()
        class Handler(socketserver.BaseRequestHandler):
            def handle(inner):
                if idle:
                    release.wait(3)
                    return
                inner.request.settimeout(2)
                try:
                    while payload := inner.request.recv(65536):
                        inner.request.sendall(payload)
                except OSError:
                    pass
        server = socketserver.ThreadingTCPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': .02})
        thread.start()
        self.servers.append((server, thread, release))
        return server.server_address

    def relay(self, resolver, **kwargs):
        relay = LoopbackRelay(resolver, **kwargs)
        self.relays.append(relay)
        return relay

    def fixture(self):
        fixture = Integration.__new__(Integration)
        fixture.postgres, fixture.backend_relay_cid = 'a' * 64, 'b' * 64
        fixture.project, fixture.network, fixture.token = 'own-fixture', 'own-network', 'own-token'
        fixture.events, fixture.starts = [], {}
        row = {'Id': fixture.postgres, 'Config': {'Labels': {LABEL: fixture.token,
               'com.docker.compose.project': fixture.project, 'com.docker.compose.service': 'postgres'}},
               'State': {'Running': True, 'Status': 'running', 'OOMKilled': False, 'ExitCode': 0},
               'NetworkSettings': {'Ports': {}, 'Networks': {fixture.network: {
                   'NetworkID': 'network-id', 'EndpointID': 'endpoint-id', 'IPAddress': '172.29.0.2'}}}}
        network = {'Id': 'network-id', 'Name': fixture.network, 'Internal': True, 'Labels': {LABEL: fixture.token},
                   'Containers': {fixture.postgres: {'EndpointID': 'endpoint-id', 'IPv4Address': '172.29.0.2/16'}}}
        calls = []
        def run(argv, **kwargs):
            calls.append(argv)
            if argv == ['docker', 'inspect', fixture.postgres]:
                payload = json.dumps([row]).encode()
            elif argv == ['docker', 'network', 'inspect', fixture.network]:
                payload = json.dumps([network]).encode()
            elif argv[:4] == ['docker', 'exec', fixture.postgres, 'pg_isready']:
                payload = b''
            elif argv[:3] == ['docker', 'exec', '-i'] and kwargs.get('input') == b'SHOW server_version_num;':
                payload = b'170011\n'
            else:
                raise AssertionError('unexpected synthetic Docker dependency call')
            return subprocess.CompletedProcess(argv, 0, payload, b'')
        fixture.run = run
        return fixture, row, network, calls

    def test_absent_published_ports_use_exact_owned_endpoint_after_native_pg_ready(self):
        fixture, row, _, calls = self.fixture()
        fixture.wait_for_source_ready()
        self.assertEqual(fixture.events[-1]['docker_published_ports'], [])
        self.assertEqual(fixture.owned_endpoint(fixture.postgres, 443), ('172.29.0.2', 443))
        self.assertEqual(fixture.owned_endpoint(fixture.postgres, 5432), ('172.29.0.2', 5432))
        self.assertTrue(any('pg_isready' in call for call in calls))
        self.assertEqual(row['NetworkSettings']['Ports'], {})

    def test_exited_pg_and_changed_container_network_address_fail_closed(self):
        fixture, row, _, calls = self.fixture()
        row['State'].update(Running=False, Status='exited', ExitCode=1)
        with self.assertRaisesRegex(FixtureAssertionError, 'PostgreSQL exited before readiness'):
            fixture.wait_for_source_ready()
        self.assertFalse(any('pg_isready' in call for call in calls))
        self.assertEqual((fixture.events[-1]['state'], fixture.events[-1]['exit_code']), ('exited', 1))
        mutations = [lambda row, net: row.update(Id='c' * 64),
                     lambda row, net: row['Config']['Labels'].update({LABEL: 'another-owner'}),
                     lambda row, net: net.update(Internal=False),
                     lambda row, net: net['Containers']['a' * 64].update(IPv4Address='172.29.0.3/16')]
        for mutate in mutations:
            fixture, row, network, _ = self.fixture()
            mutate(row, network)
            with self.assertRaises(FixtureAssertionError):
                fixture.owned_endpoint(fixture.postgres, 443)

    def test_real_tcp_bytes_and_per_connection_resolution(self):
        target = self.server()
        resolved = []
        relay = self.relay(lambda: (resolved.append('owned-endpoint') or target))
        for _ in range(2):
            with socket.create_connection(('127.0.0.1', relay.port), timeout=2) as client:
                payload = bytes(range(256)) * 256
                client.sendall(payload)
                actual = bytearray()
                while len(actual) < len(payload):
                    actual.extend(client.recv(65536))
                self.assertEqual(bytes(actual), payload)
        self.assertEqual(resolved, ['owned-endpoint', 'owned-endpoint'])
        relay.close()
        self.assertFalse(relay.workers or relay.sockets)

    def test_actual_readiness_consumer_503_to_200_through_real_tcp_relay(self):
        specification = importlib.util.spec_from_file_location('owned_readiness_fixture', ROOT / 'scripts/test-v126-readiness.py')
        readiness = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(readiness)
        class RelayedReadiness(readiness.ReadinessTest):
            def setUp(inner):
                super().setUp()
                inner.relay = LoopbackRelay(lambda: ('127.0.0.1', inner.server.server_address[1]))
                inner.env['FIXTURE_PORT'] = str(inner.relay.port)

            def tearDown(inner):
                inner.relay.close()
                super().tearDown()
        result = unittest.TestResult()
        RelayedReadiness('test_503_then_200_same_container_single_start').run(result)
        self.assertEqual((len(result.errors), len(result.failures)), (0, 0),
                         'actual source readiness/curl/HTTP through relay failed')

    def test_real_tcp_idle_deadline_and_active_cleanup_are_bounded(self):
        target = self.server(idle=True)
        relay = self.relay(lambda: target, idle_seconds=.2, total_seconds=.5)
        with socket.create_connection(('127.0.0.1', relay.port), timeout=2) as client:
            before = time.monotonic()
            self.assertEqual(client.recv(1), b'')
            self.assertLess(time.monotonic() - before, 1.5)
        active = self.relay(lambda: target)
        with socket.create_connection(('127.0.0.1', active.port), timeout=2) as client:
            client.sendall(b'synthetic')
            deadline = time.monotonic() + 1
            while len(active.sockets) < 2 and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertEqual(len(active.sockets), 2)
            before = time.monotonic()
            active.close()
            self.assertLess(time.monotonic() - before, 1.5)
        self.assertFalse(active.workers or active.sockets)

    def test_real_tcp_connection_cap_and_public_target_refusal(self):
        target = self.server(idle=True)
        opened = threading.Event()
        relay = self.relay(lambda: (opened.set() or target), max_connections=1)
        with socket.create_connection(('127.0.0.1', relay.port), timeout=2):
            self.assertTrue(opened.wait(1))
            with socket.create_connection(('127.0.0.1', relay.port), timeout=2) as second:
                self.assertEqual(second.recv(1), b'')
        denied = self.relay(lambda: ('8.8.8.8', 443))
        client = socket.socket()
        client.settimeout(2)
        with patch.object(socket, 'create_connection') as connect:
            try:
                client.connect(('127.0.0.1', denied.port))
                self.assertEqual(client.recv(1), b'')
            finally:
                client.close()
            connect.assert_not_called()


class ComposeParserTest(unittest.TestCase):
    def test_actual_positive_and_negative_create_argv_use_supported_compose_flags(self):
        docker = shutil.which('docker')
        self.assertIsNotNone(docker, 'read-only Docker Compose parser is required')
        module = ast.parse(Path(__file__).read_text())
        integration = next(node for node in module.body if isinstance(node, ast.ClassDef) and node.name == 'Integration')
        with tempfile.TemporaryDirectory(prefix='v126-compose-parser-') as temporary:
            directory = Path(temporary)
            environment = {key: os.environ[key] for key in ('PATH', 'HOME')}
            environment['DOCKER_HOST'] = 'unix://' + str(directory / 'nonexistent-daemon.sock')
            for method in ('positive_sequence', 'negative_sequence'):
                body = next(node for node in integration.body if isinstance(node, ast.FunctionDef) and node.name == method)
                calls = [node for node in ast.walk(body) if isinstance(node, ast.Call)
                         and isinstance(node.func, ast.Attribute) and node.func.attr == 'compose'
                         and node.args and isinstance(node.args[0], ast.Constant) and node.args[0].value == 'create']
                self.assertEqual(len(calls), 1, method)
                arguments = [ast.literal_eval(value) for value in calls[0].args]
                result = subprocess.run([docker, 'compose', *arguments, '--help'], cwd=directory,
                                        env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
                self.assertEqual(result.returncode, 0, method + ': real Compose parser rejected source argv')
                self.assertIn(b'Creates containers for a service', result.stdout)
                self.assertEqual(arguments[-1], 'backend')
            # The actual constructor emits no backend depends_on/links; selecting
            # this service for create cannot request postgres/provider dependencies.
            fixture = Integration(directory / 'evidence')
            try:
                backend = fixture.compose_doc['services']['backend']
                self.assertFalse(set(backend) & {'depends_on', 'links', 'external_links'})
                self.assertEqual(fixture.starts, {})
            finally:
                fixture.temp.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--require-hosted-ci', action='store_true')
    mode.add_argument('--diagnostics-self-test', action='store_true')
    parser.add_argument('--evidence-dir', type=Path)
    args = parser.parse_args()
    if args.diagnostics_self_test:
        suite = unittest.TestSuite(unittest.defaultTestLoader.loadTestsFromTestCase(case)
                                  for case in (DiagnosticsTest, MigrationRuntimeTest, TransportTest, ComposeParserTest))
        result = unittest.TextTestRunner(verbosity=2).run(suite)
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
