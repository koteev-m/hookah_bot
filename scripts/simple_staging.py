#!/usr/bin/env python3
"""Operator-only staging install/update. No HDC, SSH, teardown, restore or daemon admin.

The public CLI uses hookah-bot and the system Docker socket. Test subclasses can
exercise the same operations in uniquely named disposable Compose projects.
"""
import argparse
import contextlib
import fcntl
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import re
import secrets
import socket
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile
import zlib

SOURCE = 'https://github.com/koteev-m/hookah_bot'
REPO = 'koteev-m/hookah_bot'
IMAGE_RE = r'ghcr\.io/koteev-m/hookah_bot/backend@sha256:[0-9a-f]{64}'
LOCK = Path('/run/lock/shared-staging-docker.lock')
RUNTIME_SECRETS = {'API_SESSION_JWT_SECRET', 'TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER', 'VENUE_STAFF_INVITE_SECRET_PEPPER'}
BASE_RUNTIME_KEYS = RUNTIME_SECRETS | {'TELEGRAM_BOT_ENABLED', 'TELEGRAM_BOT_TOKEN', 'TELEGRAM_BOT_USERNAME',
                                       'PLATFORM_OWNER_TELEGRAM_ID'}
R2_CONNECTION_KEYS = {'STAFF_PHOTOS_R2_' + suffix for suffix in
                      ('ENDPOINT', 'BUCKET', 'ACCESS_KEY', 'SECRET_KEY', 'REGION', 'JURISDICTION')}
PHOTO_RUNTIME_KEYS = R2_CONNECTION_KEYS | {'STAFF_PHOTOS_R2_ENABLED', 'STAFF_PHOTOS_MAX_INPUT_BYTES'}
RUNTIME_KEYS = BASE_RUNTIME_KEYS | PHOTO_RUNTIME_KEYS
REQUIRED_JOBS = {'backend-ktlint', 'backend-compile', 'backend-archive-reproducibility',
                 'backend-release-critical-routes', 'backend-venue-booking-rbac',
                 'backend-telegram-lightweight', 'backend-migration-sanity', 'backend',
                 'compose', 'compose-cutover', 'compose-runtime', 'compose-prerequisite',
                 'miniapp', 'miniapp-e2e-smoke', 'docker'}


class Refused(Exception):
    pass


def require(ok, reason):
    if not ok:
        raise Refused(reason)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def atomic(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as out:
        json.dump(value, out, indent=2, sort_keys=True)
        out.write('\n')
        out.flush()
        os.fsync(out.fileno())
    os.replace(temporary, path)
    fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def private_json(path):
    path = Path(path)
    require(not path.is_symlink() and path.is_file(), 'PRIVATE_FILE_REQUIRED')
    st = path.stat()
    require(st.st_uid == os.geteuid() and not st.st_mode & 0o077, 'PRIVATE_FILE_MODE_OR_OWNER')
    return json.loads(path.read_text())


def run(argv, *, timeout=60, payload=None):
    env = {k: v for k, v in os.environ.items() if not k.startswith(('COMPOSE_', 'DOCKER_'))}
    # Do not let .env or ambient Compose settings select another project/daemon.
    env['DOCKER_HOST'] = 'unix:///var/run/docker.sock'
    try:
        p = subprocess.run(argv, input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=timeout, env=env)
    except subprocess.TimeoutExpired as exc:
        raise Refused('COMMAND_TIMEOUT_OUTCOME_UNKNOWN') from exc
    require(p.returncode == 0, 'COMMAND_FAILED_' + Path(argv[0]).name)
    return p.stdout


class NoCiRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        fp.close()
        raise Refused('CI_API_REDIRECT_REFUSED')


def public_ci_json(opener, url, deadline):
    request = urllib.request.Request(url, headers={
        'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2026-03-10',
        'User-Agent': 'Hookah-Tootah-staging-ci'})
    remaining = deadline - time.monotonic()
    require(remaining > 0, 'CI_API_TIMEOUT')
    limit = 4 * 1024 * 1024
    try:
        with opener.open(request, timeout=min(10, remaining)) as response:
            require(response.status == 200, 'CI_API_RESPONSE_INVALID')
            length = response.headers.get('Content-Length')
            if length is not None:
                require(length.isascii() and length.isdigit() and int(length) <= limit,
                        'CI_API_RESPONSE_INVALID')
            body = bytearray()
            while True:
                require(time.monotonic() < deadline, 'CI_API_TIMEOUT')
                chunk = response.read1(min(65536, limit + 1 - len(body)))
                if not chunk:
                    break
                body.extend(chunk)
                require(len(body) <= limit, 'CI_API_RESPONSE_TOO_LARGE')
            require(time.monotonic() < deadline, 'CI_API_TIMEOUT')
            require(length is None or len(body) == int(length), 'CI_API_RESPONSE_INCOMPLETE')
        value = json.loads(body)
    except urllib.error.HTTPError as exc:
        exc.close()
        raise Refused(f'CI_API_HTTP_{exc.code}') from exc
    except (OSError, urllib.error.URLError, http.client.HTTPException) as exc:
        raise Refused('CI_API_UNAVAILABLE') from exc
    except (ValueError, RecursionError) as exc:
        raise Refused('CI_API_RESPONSE_INVALID') from exc
    require(isinstance(value, dict), 'CI_API_RESPONSE_INVALID')
    return value


def verify_ci(revision, run_id):
    require(isinstance(revision, str) and re.fullmatch(r'[0-9a-f]{40}', revision)
            and re.fullmatch(r'[1-9][0-9]{0,19}', str(run_id)), 'CI_INPUT_INVALID')
    run_id = int(run_id)
    base = f'https://api.github.com/repos/{REPO}/actions/runs/{run_id}'
    html_url = f'{SOURCE}/actions/runs/{run_id}'
    # Fresh anonymous transport: no ambient proxy credentials, gh auth or redirects.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoCiRedirect())
    deadline = time.monotonic() + 60

    def read_run():
        record = public_ci_json(opener, base, deadline)
        require(type(record.get('id')) is int and record['id'] == run_id
                and record.get('head_sha') == revision and record.get('event') == 'push'
                and record.get('path') == '.github/workflows/ci.yml'
                and all(isinstance(record.get(k), dict) and record[k].get('full_name') == REPO
                        for k in ('repository', 'head_repository'))
                and record.get('status') == 'completed' and record.get('conclusion') == 'success'
                and record.get('html_url') == html_url
                and type(record.get('run_attempt')) is int and record['run_attempt'] > 0,
                'EXACT_CI_NOT_GREEN')
        return record

    record = read_run()
    total = None
    ids, names = set(), set()
    # Request numbered pages from the fixed endpoint, never URLs supplied in Link.
    for page in range(1, 11):
        data = public_ci_json(opener, base + f'/jobs?filter=latest&per_page=100&page={page}', deadline)
        count, jobs = data.get('total_count'), data.get('jobs')
        require(type(count) is int and 0 < count <= 1000 and isinstance(jobs, list), 'CI_JOBS_RESPONSE_INVALID')
        if total is None:
            total = count
        require(count == total and len(jobs) == min(100, total - len(ids)), 'CI_JOBS_RESPONSE_INCOMPLETE')
        for job in jobs:
            require(isinstance(job, dict) and type(job.get('id')) is int and job['id'] > 0
                    and job['id'] not in ids and type(job.get('run_id')) is int and job['run_id'] == run_id
                    and job.get('head_sha') == revision and isinstance(job.get('name'), str) and job['name'],
                    'CI_JOB_IDENTITY_INVALID')
            require(job.get('status') == 'completed' and job.get('conclusion') == 'success', 'REQUIRED_CI_JOB_NOT_GREEN')
            ids.add(job['id'])
            names.add(job['name'].split(' (')[0])
        if len(ids) == total:
            break
    require(len(ids) == total and REQUIRED_JOBS <= names, 'REQUIRED_CI_JOB_NOT_GREEN')
    # A rerun during pagination must not combine evidence from different executions.
    require(read_run()['run_attempt'] == record['run_attempt'], 'CI_RUN_CHANGED')
    return {'id': run_id, 'sha': revision, 'url': html_url}


def manifest(jar_bytes):
    rows = []
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as jar:
        names = [n for n in jar.namelist() if n.startswith('db/migration/postgresql/') and n.endswith('.sql')]
        require(len(names) == len(set(names)), 'DUPLICATE_SQL')
        for name in names:
            script = name.split('/')[-1]
            require(re.fullmatch(r'V[1-9][0-9]*__[^/]+\.sql', script), 'SQL_NAME_INVALID')
            raw = jar.read(name)
            crc = zlib.crc32(''.join(raw.decode('utf-8-sig').splitlines()).encode())
            rows.append({'version': int(script[1:].split('__')[0]), 'script': script,
                         'sha256': hashlib.sha256(raw).hexdigest(), 'checksum': crc if crc < 2**31 else crc - 2**32})
    rows.sort(key=lambda x: x['version'])
    require([r['version'] for r in rows] == list(range(1, len(rows) + 1)) and len(rows) >= 126,
            'CONTIGUOUS_V1_TO_V126_REQUIRED')
    return rows


def history_for(rows):
    return [{k: r[k] for k in ('version', 'script', 'checksum')} for r in rows]


def compatibility(old, new, review=None):
    require(len(new) >= len(old) and new[:len(old)] == old, 'MIGRATIONS_NOT_APPEND_ONLY')
    if new != old:
        require(review is not None and review.get('from_manifest') == digest(old)
                and review.get('to_manifest') == digest(new)
                and review.get('backward_compatible') is True
                and isinstance(review.get('reason'), str) and len(review['reason'].strip()) >= 20,
                'EXACT_COMPATIBILITY_REVIEW_REQUIRED')


@contextlib.contextmanager
def shared_lock(path=LOCK, wait=30):
    require(0 <= wait <= 60, 'LOCK_WAIT_INVALID')
    initial = os.lstat(path)
    fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        def check():
            st, named = os.fstat(fd), os.lstat(path)
            require(stat.S_ISREG(st.st_mode) and st.st_uid == 0 and stat.S_IMODE(st.st_mode) == 0o660,
                    'LOCK_OWNER_OR_MODE_INVALID')
            require(stat.S_ISREG(named.st_mode)
                    and (initial.st_dev, initial.st_ino) == (st.st_dev, st.st_ino) == (named.st_dev, named.st_ino),
                    'LOCK_INODE_CHANGED')
            require(st.st_nlink == 1, 'LOCK_LINK_COUNT_INVALID')
            listxattr = getattr(os, 'listxattr', None)
            require(listxattr is not None, 'LOCK_XATTR_CHECK_UNAVAILABLE')
            require(not listxattr(fd), 'LOCK_XATTR_PRESENT')

        deadline = time.monotonic() + wait
        while True:
            check()
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                require(time.monotonic() < deadline, 'SHARED_DOCKER_LOCK_BUSY')
                time.sleep(0.1)
        check()
        yield
        check()
    finally:
        os.close(fd)


def validate_config(c):
    require(set(c) == {'host', 'architecture', 'postgres_image', 'postgres_password', 'runtime'}, 'CONFIG_FIELDS_INVALID')
    require(isinstance(c['host'], str) and c['host'] and c['architecture'] in ('amd64', 'arm64'), 'HOST_CONFIG_INVALID')
    require(re.fullmatch(r'(docker\.io/library/)?postgres@sha256:[0-9a-f]{64}', c['postgres_image']), 'POSTGRES_DIGEST_REQUIRED')
    require(re.fullmatch(r'[A-Za-z0-9_-]{32,128}', c['postgres_password']), 'DB_PASSWORD_INVALID')
    e = c['runtime']
    require(isinstance(e, dict) and BASE_RUNTIME_KEYS <= set(e) <= RUNTIME_KEYS, 'RUNTIME_FIELDS_INVALID')
    require(all(isinstance(v, str) and '\n' not in v and '\r' not in v and '$' not in v for v in e.values()), 'RUNTIME_VALUES_INVALID')
    require(all(re.fullmatch(r'[A-Za-z0-9_-]{32,128}', e[k]) for k in RUNTIME_SECRETS), 'RUNTIME_SECRET_INVALID')
    require(e['TELEGRAM_BOT_ENABLED'] in ('true', 'false'), 'TELEGRAM_FLAG_INVALID')
    if e['TELEGRAM_BOT_ENABLED'] == 'true':
        require(re.fullmatch(r'[0-9]+:[A-Za-z0-9_-]+', e['TELEGRAM_BOT_TOKEN'])
                and re.fullmatch(r'[1-9][0-9]*', e['PLATFORM_OWNER_TELEGRAM_ID'])
                and re.fullmatch(r'[A-Za-z0-9_]+', e['TELEGRAM_BOT_USERNAME']), 'TELEGRAM_CONFIG_INVALID')
    else:
        require(e['TELEGRAM_BOT_TOKEN'] == '', 'DISABLED_TELEGRAM_TOKEN_MUST_BE_EMPTY')
    # Do not insert defaults: old runtime/Compose hashes must remain unchanged.
    require(e.get('STAFF_PHOTOS_R2_ENABLED', 'false') in ('true', 'false'), 'R2_FLAG_INVALID')
    if e.get('STAFF_PHOTOS_R2_ENABLED') == 'true' or R2_CONNECTION_KEYS & set(e):
        require(R2_CONNECTION_KEYS | {'STAFF_PHOTOS_R2_ENABLED'} <= set(e), 'R2_CONFIG_INCOMPLETE')
        require(e['STAFF_PHOTOS_R2_BUCKET'] == 'hookah-tootah-media-staging-eu', 'R2_BUCKET_INVALID')
        require(e['STAFF_PHOTOS_R2_REGION'] == 'auto', 'R2_REGION_INVALID')
        jurisdiction = e['STAFF_PHOTOS_R2_JURISDICTION']
        require(jurisdiction in ('default', 'eu'), 'R2_JURISDICTION_INVALID')
        suffix = r'eu\.' if jurisdiction == 'eu' else ''
        require(re.fullmatch(r'https://[a-f0-9]{32}\.' + suffix + r'r2\.cloudflarestorage\.com',
                             e['STAFF_PHOTOS_R2_ENDPOINT']), 'R2_ENDPOINT_INVALID')
        require(re.fullmatch(r'[A-Za-z0-9_-]{16,128}', e['STAFF_PHOTOS_R2_ACCESS_KEY'])
                and re.fullmatch(r'[A-Za-z0-9/+=_-]{32,256}', e['STAFF_PHOTOS_R2_SECRET_KEY']),
                'R2_CREDENTIALS_INVALID')
    if 'STAFF_PHOTOS_MAX_INPUT_BYTES' in e:
        limit = e['STAFF_PHOTOS_MAX_INPUT_BYTES']
        require(re.fullmatch(r'[1-9][0-9]{0,6}', limit) and 1024 <= int(limit) <= 5 * 1024 * 1024,
                'R2_INPUT_LIMIT_INVALID')


class Staging:
    project = 'hookah-bot'
    port = 8080
    readiness_seconds = 120

    def __init__(self, directory, runner=run):
        self.root = Path(directory)
        require(self.root.is_absolute() and self.root.resolve() == self.root and self.root.is_dir(), 'ABSOLUTE_PRIVATE_DIRECTORY_REQUIRED')
        st = self.root.stat()
        require(st.st_uid == os.geteuid() and not st.st_mode & 0o077, 'DIRECTORY_MODE_OR_OWNER')
        self.run = runner
        self.config = private_json(self.root / 'config.json')
        validate_config(self.config)
        self.state_file = self.root / 'result.json'
        self.compose_file = self.root / 'compose.json'

    def host_check(self):
        require(sys.platform == 'linux' and os.geteuid() == 0, 'LINUX_TRUSTED_SYSTEM_OPERATOR_REQUIRED')
        require(socket.gethostname() == self.config['host'], 'WRONG_HOST')
        require(not any(k.startswith(('DOCKER_', 'COMPOSE_')) for k in os.environ), 'DOCKER_ENV_OVERRIDE_REFUSED')
        self.run(['docker', 'compose', 'version'], timeout=10)
        info = json.loads(self.run(['docker', 'info', '--format', '{{json .}}'], timeout=10))
        arch = {'x86_64': 'amd64', 'aarch64': 'arm64'}.get(info['Architecture'], info['Architecture'])
        require(info['OSType'] == 'linux' and arch == self.config['architecture'], 'DAEMON_ARCHITECTURE_MISMATCH')

    def compose(self, *args, payload=None, timeout=120):
        return self.run(['docker', 'compose', '--project-name', self.project, '--project-directory', str(self.root),
                         '--env-file', '/dev/null', '-f', str(self.compose_file), *args], payload=payload, timeout=timeout)

    def document(self, image):
        c = self.config
        env = dict(c['runtime'], APP_ENV='staging', APP_VERSION=image['revision'], APP_HTTP_PORT='8080',
                   DB_JDBC_URL='jdbc:postgresql://postgres:5432/hookah', DB_USER='hookah', DB_PASSWORD=c['postgres_password'],
                   TELEGRAM_TRAFFIC_POLICY='PRODUCT', TELEGRAM_ALLOWED_USER_IDS='', TELEGRAM_ALLOWED_CHAT_IDS='',
                   STAGING_MAINTENANCE_MODE='OFF', STAGING_MAINTENANCE_ALLOWED_USER_IDS='', STAGING_MAINTENANCE_ALLOWED_CHAT_IDS='',
                   TELEGRAM_BOT_MODE='long_polling', TELEGRAM_WEBAPP_PUBLIC_URL='https://staging.hookahtootah.club/miniapp/',
                   MINIAPP_STATIC_DIR='/app/miniapp', MINIAPP_DEV_SERVER_URL='', MINIAPP_ENTRY_ENABLED='true',
                   CORS_ALLOWED_HOSTS='https://staging.hookahtootah.club', AI_ASSISTANT_ENABLED='false', BILLING_PROVIDER='fake')
        return {'services': {
            'postgres': {'image': c['postgres_image'], 'platform': 'linux/' + c['architecture'], 'restart': 'unless-stopped',
                         'environment': {'POSTGRES_DB': 'hookah', 'POSTGRES_USER': 'hookah', 'POSTGRES_PASSWORD': c['postgres_password']},
                         'volumes': ['pgdata:/var/lib/postgresql/data'],
                         'healthcheck': {'test': ['CMD-SHELL', 'pg_isready -U hookah -d hookah'], 'interval': '2s', 'timeout': '3s', 'retries': 30}},
            'backend': {'image': image['image'], 'platform': 'linux/' + c['architecture'], 'restart': 'unless-stopped',
                        'environment': env, 'ports': [f'127.0.0.1:{self.port}:8080'],
                        'depends_on': {'postgres': {'condition': 'service_healthy'}}}},
            'volumes': {'pgdata': {}}, 'networks': {'default': {}}}

    def inspect_image(self, ref, *, pull=True):
        if pull:
            self.run(['docker', 'pull', '--platform', 'linux/' + self.config['architecture'], ref], timeout=600)
        data = json.loads(self.run(['docker', 'image', 'inspect', '--platform', 'linux/' + self.config['architecture'], ref]))[0]
        require(data.get('Os') == 'linux' and data.get('Architecture') == self.config['architecture'], 'IMAGE_ARCHITECTURE_MISMATCH')
        require(ref in data.get('RepoDigests', []), 'IMAGE_DIGEST_MISMATCH')
        return data

    def candidate(self, record):
        require(set(record) == {'image', 'revision', 'ci_run'}, 'CANDIDATE_FIELDS_INVALID')
        require(re.fullmatch(IMAGE_RE, record['image']), 'HOOKAH_IMMUTABLE_IMAGE_REQUIRED')
        ci = verify_ci(record['revision'], record['ci_run'])
        data = self.inspect_image(record['image'])
        labels = data.get('Config', {}).get('Labels') or {}
        require(labels.get('org.opencontainers.image.source') == SOURCE
                and labels.get('org.opencontainers.image.revision') == record['revision'], 'IMAGE_SOURCE_REVISION_MISMATCH')
        require(not data['Config'].get('Volumes'), 'BACKEND_IMAGE_VOLUMES_FORBIDDEN')
        # Copy only the packaged application JAR from a never-started, uniquely named container.
        name = self.project + '-inspect-' + uuid.uuid4().hex
        cid = self.run(['docker', 'create', '--name', name, '--network', 'none', '--label', 'ht.simple.inspect=' + name,
                        '--entrypoint', '/bin/true', record['image']]).decode().strip()
        try:
            raw = self.run(['docker', 'cp', cid + ':/app/app/lib/app-0.1.0.jar', '-'])
            with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
                files = [m for m in archive if m.isfile()]
                require(len(files) == 1 and files[0].size < 64 * 1024 * 1024, 'PACKAGED_JAR_INVALID')
                rows = manifest(archive.extractfile(files[0]).read())
        finally:
            self.run(['docker', 'rm', cid])
        return dict(record, id=data['Id'], manifest=rows, ci=ci)

    def containers(self):
        ids = self.run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + self.project]).decode().split()
        return json.loads(self.run(['docker', 'inspect', *ids])) if ids else []

    def db(self, sql):
        return self.compose('exec', '-T', 'postgres', 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'hookah', '-d', 'hookah', '-At', '-c', sql).decode().strip()

    def history(self):
        if self.db("SELECT to_regclass('public.flyway_schema_history') IS NULL") == 't':
            require(self.db("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'") == '0', 'NONEMPTY_UNMANAGED_DATABASE')
            return []
        rows = json.loads(self.db("SELECT coalesce(json_agg(t ORDER BY installed_rank),'[]'::json) FROM (SELECT installed_rank,version,script,checksum,success FROM public.flyway_schema_history) t"))
        require(all(r['success'] and str(r['version']).isdigit() for r in rows), 'FLYWAY_FAILED_OR_UNKNOWN_HISTORY')
        return [{'version': int(r['version']), 'script': r['script'], 'checksum': r['checksum']} for r in rows]

    def binding(self):
        items = self.containers()
        require(all(i['Config']['Labels'].get('com.docker.compose.service') in ('backend', 'postgres') for i in items), 'UNEXPECTED_PROJECT_SERVICE')
        pgs = [i for i in items if i['Config']['Labels'].get('com.docker.compose.service') == 'postgres']
        require(len(pgs) == 1, 'POSTGRES_IDENTITY_UNKNOWN')
        pg = pgs[0]
        require(pg['State']['Running'] and not pg['HostConfig'].get('PortBindings'), 'POSTGRES_NOT_PRIVATE_OR_RUNNING')
        require(len(pg['Mounts']) == 1 and pg['Mounts'][0].get('Name') == self.project + '_pgdata'
                and pg['Mounts'][0]['Destination'] == '/var/lib/postgresql/data', 'POSTGRES_VOLUME_MISMATCH')
        require(set(pg['NetworkSettings']['Networks']) == {self.project + '_default'}, 'POSTGRES_NETWORK_MISMATCH')
        require(pg['Config']['Labels'].get('com.docker.compose.project.working_dir') == str(self.root), 'COMPOSE_DIRECTORY_MISMATCH')
        return {'container': pg['Id'], 'image': pg['Image'], 'mounts': pg['Mounts'],
                'config': digest(pg['Config']), 'host_config': digest(pg['HostConfig']),
                'network': pg['NetworkSettings']['Networks'][self.project + '_default']['NetworkID'],
                'system_id': self.db('SELECT system_identifier FROM pg_control_system()')}

    def observe(self, image, binding):
        require(self.binding() == binding, 'POSTGRES_BINDING_CHANGED')
        require(self.history() == history_for(image['manifest']), 'DATABASE_HISTORY_MISMATCH')
        apps = [i for i in self.containers() if i['Config']['Labels'].get('com.docker.compose.service') == 'backend']
        require(len(apps) == 1, 'BACKEND_IDENTITY_UNKNOWN')
        app = apps[0]
        require(app['Image'] == image['id'] and app['State']['Running'], 'BACKEND_IMAGE_NOT_RUNNING')
        require(app['HostConfig']['PortBindings'] == {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(self.port)}]}
                and not app['Mounts'] and set(app['NetworkSettings']['Networks']) == {self.project + '_default'}, 'BACKEND_ISOLATION_MISMATCH')
        actual = dict(x.split('=', 1) for x in app['Config']['Env'])
        require(all(actual.get(k) == v for k, v in self.document(image)['services']['backend']['environment'].items()), 'BACKEND_RUNTIME_CONFIG_MISMATCH')
        require({k: v for k, v in actual.items() if k in PHOTO_RUNTIME_KEYS}
                == {k: v for k, v in self.config['runtime'].items() if k in PHOTO_RUNTIME_KEYS},
                'BACKEND_RUNTIME_CONFIG_MISMATCH')

    def ready(self, image):
        deadline = time.monotonic() + self.readiness_seconds
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        while time.monotonic() < deadline:
            try:
                for path in ('/health', '/db/health', '/version', '/miniapp/', '/api/guest/catalog'):
                    try:
                        response = opener.open(f'http://127.0.0.1:{self.port}' + path, timeout=3)
                    except urllib.error.HTTPError as exc:
                        response = exc
                    with response:
                        body = response.read(1024 * 1024)
                        require(response.status == (401 if path.startswith('/api/') else 200), 'HTTP_SMOKE_FAILED')
                    if path in ('/health', '/db/health'):
                        require(json.loads(body).get('status') == 'ok', 'HEALTH_BODY_INVALID')
                    elif path == '/version':
                        value = json.loads(body)
                        require(value.get('version') == image['revision'] and value.get('env') == 'staging'
                                and value.get('service') == 'backend', 'VERSION_MISMATCH')
                    elif path == '/miniapp/':
                        require(b'<html' in body.lower() and b'<script' in body.lower(), 'MINIAPP_BUNDLE_MISSING')
                        assets = re.findall(rb'(?:src|href)="(/miniapp/assets/[^"]+)"', body)
                        require(assets, 'MINIAPP_ASSETS_MISSING')
                        for asset in assets:
                            with opener.open(f'http://127.0.0.1:{self.port}' + asset.decode(), timeout=3) as resource:
                                require(resource.status == 200 and len(resource.read(1024 * 1024)) > 32, 'MINIAPP_ASSET_FAILED')
                return
            except (OSError, ValueError, Refused):
                time.sleep(1)
        raise Refused('READINESS_FAILED')

    def backup(self, binding):
        require(self.binding() == binding, 'POSTGRES_BINDING_CHANGED')
        path = self.root / ('backup-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + uuid.uuid4().hex[:8] + '.dump')
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, 'wb') as out:
                raw = self.compose('exec', '-T', 'postgres', 'pg_dump', '-U', 'hookah', '-d', 'hookah', '-Fc', timeout=300)
                out.write(raw); out.flush(); os.fsync(out.fileno())
            self.compose('exec', '-T', 'postgres', 'pg_restore', '--list', payload=raw)
            record = {'path': str(path), 'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw), 'binding': binding}
            atomic(path.with_suffix('.json'), record)
            return record
        except BaseException:
            # Keep failed artifacts for diagnosis; they have no successful sidecar.
            raise

    def state(self):
        return private_json(self.state_file) if self.state_file.exists() else None

    def save(self, state):
        atomic(self.state_file, state)
        atomic(self.root / ('operation-' + state['operation'] + '.json'), state)

    def config_binding(self):
        return digest({k: v for k, v in self.config.items() if k != 'runtime'})

    def apply(self, action, record=None, review=None, apply_config=False):
        self.host_check()
        state = self.state()
        if action == 'status':
            return {'status': state['status'] if state else 'NOT_INSTALLED',
                    'operation': state['operation'] if state else None,
                    'intended_image': state['target']['image'] if state else None,
                    'containers': [{'id': i['Id'], 'service': i['Config']['Labels'].get('com.docker.compose.service'),
                                    'running': i['State']['Running']} for i in self.containers()]}
        require(action in ('install', 'deploy', 'reconcile', 'rollback', 'backup'), 'ACTION_INVALID')
        if state:
            require(state['config_binding'] == self.config_binding(), 'DATABASE_OR_HOST_CONFIG_CHANGED')
            require(state['runtime_hash'] == digest(self.config['runtime']) or (action == 'deploy' and apply_config), 'RUNTIME_CONFIG_CHANGED_USE_APPLY_CONFIG')
            require(self.compose_file.exists() and digest(private_json(self.compose_file)) == state['compose_hash'], 'COMPOSE_CHANGED')
        if action in ('backup', 'reconcile', 'rollback'):
            require(state and state.get('binding'), 'DATABASE_BINDING_NOT_ESTABLISHED_MANUAL_INSPECTION_REQUIRED')
        if action == 'backup':
            return {'status': 'BACKUP_VERIFIED', **self.backup(state['binding'])}
        if action == 'reconcile':
            # No replay: accept only the exact intended running result after fresh checks.
            self.observe(state['target'], state['binding']); self.ready(state['target'])
            state['status'] = 'SUCCESS'; state.pop('error', None); self.save(state)
            return {'status': 'SUCCESS', 'operation': state['operation']}
        if action == 'rollback':
            require(state.get('previous') and state['previous']['manifest'] == state['target']['manifest'], 'SCHEMA_ROLLBACK_FORBIDDEN')
            require(state['runtime_hash'] == state.get('previous_runtime_hash'), 'CONFIG_ROLLBACK_REQUIRES_MANUAL_REVIEW')
            require(self.binding() == state['binding'] and self.history() == history_for(state['previous']['manifest']), 'ROLLBACK_DATABASE_NOT_PROVEN')
            target = self.candidate({k: state['previous'][k] for k in ('image', 'revision', 'ci_run')})
            require(target == state['previous'], 'ROLLBACK_IMAGE_CHANGED')
        else:
            require(not state or state['status'] == 'SUCCESS', 'UNFINISHED_OPERATION_RECONCILE_REQUIRED')
            require(action != 'deploy' or state, 'INSTALL_REQUIRED')
            target = self.candidate(record)
            if state:
                self.observe(state['target'], state['binding']) if not apply_config else self.check_previous(state)
                if action == 'install':
                    require(target == state['target'] and state['runtime_hash'] == digest(self.config['runtime']), 'ALREADY_INSTALLED_USE_DEPLOY')
                    self.ready(target)
                    return {'status': 'SUCCESS', 'operation': state['operation'], 'unchanged': True}
                compatibility(state['target']['manifest'], target['manifest'], review)
            else:
                require(len(target['manifest']) == 126, 'INITIAL_INSTALL_REQUIRES_EXACT_V126')
                require(self.config['runtime']['TELEGRAM_BOT_ENABLED'] == 'false', 'INITIAL_TELEGRAM_MUST_BE_OFF')
                require(not self.containers(), 'INITIAL_PROJECT_EXISTS')
                for kind, name in [('volume', self.project + '_pgdata'), ('network', self.project + '_default')]:
                    existing = self.run(['docker', kind, 'ls', '--format', '{{.Name}}']).decode().splitlines()
                    require(name not in existing, 'INITIAL_RESOURCE_EXISTS')
                pg = self.inspect_image(self.config['postgres_image'])
                require('PG_MAJOR=17' in pg['Config']['Env'], 'POSTGRES_17_REQUIRED')
        doc = self.document(target)
        operation = {'status': 'APPLYING', 'operation': uuid.uuid4().hex, 'action': action, 'target': target,
                     'previous': state['target'] if state else None,
                     'previous_runtime_hash': state['runtime_hash'] if state else None,
                     'config_binding': self.config_binding(), 'runtime_hash': digest(self.config['runtime']),
                     'compose_hash': digest(doc), 'binding': state['binding'] if state else None}
        # Durable intent before first app/DB write. A crash leaves APPLYING, which blocks deploy.
        self.save(operation)
        try:
            atomic(self.compose_file, doc)
            if not state:
                self.compose('up', '-d', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '90', 'postgres')
                operation['binding'] = self.binding(); self.save(operation)
                require(self.history() == [], 'INITIAL_DATABASE_NOT_EMPTY')
            if state and target['manifest'] != state['target']['manifest']:
                # Quiesce this app before the consistent snapshot. Other projects
                # keep running; no down/volume/database operation is involved.
                self.compose('stop', '--timeout', '30', 'backend')
                operation['backup'] = self.backup(operation['binding']); self.save(operation)
            self.compose('up', '-d', '--no-build', '--pull', 'never', '--no-deps', '--force-recreate', 'backend')
            self.ready(target)
            self.observe(target, operation['binding'])
            operation['status'] = 'SUCCESS'; self.save(operation)
            return {'status': 'SUCCESS', 'operation': operation['operation'], 'schema': len(target['manifest']),
                    'image': target['image'], 'telegram': self.config['runtime']['TELEGRAM_BOT_ENABLED']}
        except BaseException as exc:
            operation['status'] = 'UNKNOWN'
            operation['error'] = str(exc) if isinstance(exc, Refused) else type(exc).__name__
            self.save(operation)
            raise

    def check_previous(self, state):
        # Config changes are explicit; verify the old running environment before replacing it.
        desired = self.config
        old_doc = private_json(self.compose_file)
        require(digest(old_doc) == state['compose_hash'], 'COMPOSE_CHANGED')
        old_env = old_doc['services']['backend']['environment']
        old_runtime = {k: v for k, v in old_env.items() if k in RUNTIME_KEYS}
        require(digest(old_runtime) == state['runtime_hash'], 'PREVIOUS_RUNTIME_HASH_MISMATCH')
        previous = dict(desired, runtime=old_runtime)
        validate_config(previous)
        self.config = previous
        try:
            self.observe(state['target'], state['binding'])
        finally:
            self.config = desired


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['init-config', 'inspect', 'verify-ci', 'install', 'deploy', 'status', 'backup', 'reconcile', 'rollback'])
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--candidate', type=Path)
    parser.add_argument('--compatibility-review', type=Path)
    parser.add_argument('--apply-config', action='store_true')
    parser.add_argument('--revision')
    parser.add_argument('--ci-run')
    parser.add_argument('--host')
    parser.add_argument('--postgres-image')
    parser.add_argument('--architecture', choices=['amd64', 'arm64'], default='amd64')
    parser.add_argument('--lock-wait', type=int, default=30)
    args = parser.parse_args()
    try:
        if args.action == 'verify-ci':
            result = verify_ci(args.revision or '', args.ci_run or '')
        else:
            require(args.directory is not None, 'DIRECTORY_REQUIRED')
            require(sys.platform == 'linux' and os.geteuid() == 0, 'LINUX_TRUSTED_SYSTEM_OPERATOR_REQUIRED')
            with shared_lock(wait=args.lock_wait):
                if args.action == 'init-config':
                    require(args.directory.is_dir() and args.directory.resolve() == args.directory
                            and args.directory.stat().st_uid == 0
                            and not args.directory.stat().st_mode & 0o077, 'PRIVATE_DIRECTORY_REQUIRED')
                    require(args.host == socket.gethostname(), 'WRONG_HOST')
                    require(not (args.directory / 'config.json').exists(), 'CONFIG_ALREADY_EXISTS')
                    c = {'host': args.host, 'architecture': args.architecture, 'postgres_image': args.postgres_image,
                         'postgres_password': secrets.token_hex(32), 'runtime': {
                             key: secrets.token_hex(32) for key in (
                                 'API_SESSION_JWT_SECRET', 'TELEGRAM_STAFF_CHAT_LINK_SECRET_PEPPER',
                                 'VENUE_STAFF_INVITE_SECRET_PEPPER')}}
                    c['runtime'].update(TELEGRAM_BOT_ENABLED='false', TELEGRAM_BOT_TOKEN='',
                                        TELEGRAM_BOT_USERNAME='', PLATFORM_OWNER_TELEGRAM_ID='')
                    validate_config(c)
                    atomic(args.directory / 'config.json', c)
                    print(json.dumps({'status': 'CONFIG_CREATED', 'telegram': 'false'}))
                    return 0
                runtime = Staging(args.directory)
                record = private_json(args.candidate) if args.candidate else None
                review = private_json(args.compatibility_review) if args.compatibility_review else None
                require(args.action not in ('install', 'deploy') or record is not None, 'CANDIDATE_REQUIRED')
                if args.action == 'inspect':
                    runtime.host_check()
                    require(record is not None, 'CANDIDATE_REQUIRED')
                    result = runtime.candidate(record)
                else:
                    result = runtime.apply(args.action, record, review, args.apply_config)
        print(json.dumps(result, sort_keys=True))
    except (Refused, OSError, ValueError, KeyError, TypeError) as exc:
        # No subprocess stderr, configuration, token or HTTP response is emitted.
        print(json.dumps({'status': 'STOP', 'reason': str(exc) if isinstance(exc, Refused) else type(exc).__name__,
                          'next': 'Read result.json and status; reconcile only a verified running result. Do not delete data or retry UNKNOWN.'}))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
