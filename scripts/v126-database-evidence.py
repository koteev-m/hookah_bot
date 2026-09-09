#!/usr/bin/env python3
"""Privacy-safe V126 database evidence decisions; no database writes or credentials.

The sequencer embeds this exact source for its remote consumers. The regression suite
checks byte equality, so source binding covers the helper as well as its caller.
"""
import hashlib
import json
import os
import subprocess
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit


IDENTITY_SQL = """BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '2s';
SELECT json_build_object(
 'version',1,
 'system_identifier',(SELECT system_identifier::text FROM pg_control_system()),
 'postmaster_epoch',extract(epoch FROM pg_postmaster_start_time())::text,
 'database',current_database(),
 'database_oid',(SELECT oid::text FROM pg_database WHERE datname=current_database()),
 'schema',current_schema(),
 'schema_oid',(SELECT oid::text FROM pg_namespace WHERE nspname=current_schema()),
 'search_path',current_schemas(false),
 'current_role',current_user,
 'session_role',session_user,
 'role_oid',(SELECT oid::text FROM pg_roles WHERE rolname=current_user),
 'read_only',current_setting('transaction_read_only'))::text;
ROLLBACK;
"""


def fail(message):
    raise ValueError(message)


def strict_json(data):
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                fail('structured database evidence contains a duplicate field')
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=object_pairs)


def preflight_outcome(data):
    prefix = b'V126_PREFLIGHT_RESULT='
    lines = data.splitlines()
    results = [line[len(prefix):] for line in lines if line.startswith(prefix)]
    if len(results) != 1:
        fail('preflight requires exactly one structured outcome')
    try:
        outcome = strict_json(results[0])
    except (ValueError, UnicodeError):
        fail('preflight structured outcome is invalid')
    if (not isinstance(outcome, dict) or set(outcome) != {'version', 'safe', 'unsafe_count'}
            or type(outcome['version']) is not int or outcome['version'] != 1
            or outcome['safe'] is not True or type(outcome['unsafe_count']) is not int
            or outcome['unsafe_count'] != 0):
        fail('preflight did not prove safe with zero unsafe rows')
    if lines.count(b'BOOKING_THREAD_PREFLIGHT_SAFE_TO_CONTINUE') != 1:
        fail('preflight positive completion marker is missing or duplicated')
    if any(b'STOP_FOR_BOOKING_THREAD_DEDUPLICATION_DECISION' in line for line in lines):
        fail('preflight contains an unsafe decision')
    return outcome


def canonical_toc(data):
    # This is the sole volatile presentation field: pg_restore formats the archive
    # creation time using the consumer's timezone. Every other byte remains bound.
    if b'\x00' in data or not data.endswith(b'\n'):
        fail('TOC inventory is not complete text')
    lines = data.splitlines(keepends=True)
    candidates = [i for i, line in enumerate(lines) if line.startswith(b'; Archive created at ')]
    if len(candidates) != 1 or candidates[0] != 1 or lines[0] != b';\n':
        fail('TOC archive creation header is missing or misplaced')
    if not re.fullmatch(rb'; Archive created at \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?: [A-Za-z0-9_+:/.-]+)?\n', lines[1]):
        fail('TOC archive creation header is malformed')
    if lines.count(b'; Selected TOC Entries:\n') != 1:
        fail('TOC selected-entry header is missing or duplicated')
    selected = lines.index(b'; Selected TOC Entries:\n')
    headers = b''.join(lines[2:selected])
    for name in [b'dbname', b'TOC Entries', b'Compression', b'Dump Version', b'Format',
                 b'Integer', b'Offset', b'Dumped from database version', b'Dumped by pg_dump version']:
        if len(re.findall(rb'^; +'+re.escape(name)+rb': [^\n]+\n', headers, re.M)) != 1:
            fail('TOC semantic header is missing or duplicated')
    entries = [line for line in lines[selected + 1:] if line != b';\n']
    if not entries or any(not re.match(rb'[0-9]+; [0-9]+ [0-9]+ ', line) for line in entries):
        fail('TOC entries are empty or malformed')
    ids = [line.split(b';', 1)[0] for line in entries]
    if len(set(ids)) != len(ids):
        fail('TOC entry IDs are duplicated')
    lines[1] = b'; Archive creation display omitted; exact dump SHA-256 verified separately\n'
    return b''.join(lines)


def compare_toc(dump, expected_dump_sha, retained, generated):
    if not re.fullmatch('[0-9a-f]{64}', expected_dump_sha):
        fail('dump hash binding is malformed')
    if hashlib.sha256(dump).hexdigest() != expected_dump_sha:
        fail('selected DR backup hash mismatch')
    if canonical_toc(retained) != canonical_toc(generated):
        fail('DR inventory does not match the selected archive')


def database_identity(data):
    try:
        value = strict_json(data)
    except (ValueError, UnicodeError):
        fail('database identity is not one JSON object')
    fields = {'version', 'system_identifier', 'postmaster_epoch', 'database', 'database_oid',
              'schema', 'schema_oid', 'search_path', 'current_role', 'session_role', 'role_oid', 'read_only'}
    if not isinstance(value, dict) or set(value) != fields:
        fail('database identity fields do not match the contract')
    if type(value['version']) is not int or value['version'] != 1 or value['read_only'] != 'on':
        fail('database identity query did not use the read-only contract')
    for name in ['system_identifier', 'database_oid', 'schema_oid', 'role_oid']:
        if not isinstance(value[name], str) or not re.fullmatch('[1-9][0-9]*', value[name]):
            fail('database identity has an invalid catalog identity')
    if not isinstance(value['postmaster_epoch'], str) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+)?', value['postmaster_epoch']):
        fail('database identity has an invalid server lifetime')
    for name in ['database', 'schema', 'current_role', 'session_role']:
        if not isinstance(value[name], str) or not value[name] or any(ord(ch) < 32 for ch in value[name]):
            fail('database identity has an invalid name')
    if value['current_role'] != value['session_role']:
        fail('database identity unexpectedly changed roles')
    if (not isinstance(value['search_path'], list) or not value['search_path']
            or value['search_path'][0] != value['schema']
            or any(not isinstance(x, str) or not x for x in value['search_path'])
            or len(set(value['search_path'])) != len(value['search_path'])):
        fail('database identity has an invalid effective schema search path')
    return value


def equal_identities(values):
    if len(values) < 2:
        fail('database equality requires independent consumers')
    identities = [database_identity(value) for value in values]
    if any(value != identities[0] for value in identities[1:]):
        fail('database server/database/schema/intended-role equality failed')
    encoded = json.dumps(identities[0], sort_keys=True, separators=(',', ':')).encode()
    return hashlib.sha256(encoded).hexdigest()


def backend_contract(compose, source, backend, resolved, planned_network=None):
    def environment(container):
        values = {}
        for entry in container['Config']['Env']:
            key, separator, value = entry.partition('=')
            if not separator or key in values:
                fail('container environment is malformed or duplicated')
            values[key] = value
        return values

    producer = environment(source)
    if any(key.startswith('PG') and key not in {'PG_MAJOR', 'PG_VERSION', 'PG_SHA256', 'PGDATA'}
           and value for key, value in producer.items()):
        fail('source container has unsupported libpq environment overrides')
    actual = environment(backend)
    effective = compose['services']['backend']['environment']
    database = producer.get('POSTGRES_DB', '')
    role = producer.get('POSTGRES_USER', '')
    password = producer.get('POSTGRES_PASSWORD', '')
    if (not database or any(ch in database for ch in '/?:#@%')
            or not role or not password or any(ord(ch) < 32 for ch in database + role)):
        fail('source target is not an exact supported JDBC target')
    required = {'DB_JDBC_URL': 'jdbc:postgresql://postgres:5432/' + database,
                'DB_USER': role, 'DB_PASSWORD': password}
    if any(effective.get(key) != value or actual.get(key) != value for key, value in required.items()):
        fail('future/actual backend target differs from the source database')
    source_networks = source['NetworkSettings']['Networks']
    backend_networks = backend['NetworkSettings']['Networks']
    running = backend.get('State', {}).get('Running', True)
    if not running:
        if planned_network is None:
            fail('stopped backend requires an exact future network proof')
        name, network_id = planned_network['Name'], planned_network['Id']
        if (backend['HostConfig']['NetworkMode'] not in {name, network_id}
                or name not in source_networks
                or source_networks[name].get('NetworkID') != network_id):
            fail('stopped backend network plan differs from the selected source')
        backend_networks = {name: {'NetworkID': network_id}}
    shared = set(source_networks) & set(backend_networks)
    expected = set()
    for name in shared:
        left, right = source_networks[name], backend_networks[name]
        if left.get('NetworkID') != right.get('NetworkID') or not left.get('NetworkID'):
            fail('backend/source network identity differs')
        if 'postgres' in (left.get('Aliases') or []):
            address = left.get('IPAddress', '')
            if not re.fullmatch(r'(?:[0-9]{1,3}\.){3}[0-9]{1,3}', address):
                fail('source network endpoint is unavailable')
            expected.add(address)
    if not expected or set(resolved) != expected:
        fail('backend postgres DNS does not resolve only to the selected source endpoint')
    return True


LIBPQ_IDENTITY_WORKER = r"""
import ctypes
import ctypes.util
import json
import re
import shutil
import subprocess
import sys

connection = None
try:
    payload = json.load(sys.stdin)
    library = ctypes.util.find_library('pq')
    if not library and sys.platform == 'darwin':
        linked = subprocess.run(['otool', '-L', shutil.which('psql')], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=3, check=True).stdout
        match = re.search(rb'\n\s+(/[^\n]+/libpq[^\s]+\.dylib) ', linked)
        library = match.group(1).decode() if match else None
    if not library:
        raise ValueError('libpq unavailable')
    pq = ctypes.CDLL(library)
    pointer = ctypes.c_void_p
    pq.PQconnectdbParams.argtypes = [ctypes.POINTER(ctypes.c_char_p), ctypes.POINTER(ctypes.c_char_p), ctypes.c_int]
    pq.PQconnectdbParams.restype = pointer
    pq.PQstatus.argtypes = [pointer]
    pq.PQstatus.restype = ctypes.c_int
    pq.PQexec.argtypes = [pointer, ctypes.c_char_p]
    pq.PQexec.restype = pointer
    pq.PQresultStatus.argtypes = [pointer]
    pq.PQresultStatus.restype = ctypes.c_int
    pq.PQntuples.argtypes = [pointer]
    pq.PQntuples.restype = ctypes.c_int
    pq.PQnfields.argtypes = [pointer]
    pq.PQnfields.restype = ctypes.c_int
    pq.PQgetvalue.argtypes = [pointer, ctypes.c_int, ctypes.c_int]
    pq.PQgetvalue.restype = ctypes.c_char_p
    pq.PQclear.argtypes = [pointer]
    pq.PQfinish.argtypes = [pointer]
    keys = (ctypes.c_char_p * 4)(b'dbname', b'connect_timeout', b'client_encoding', None)
    values = (ctypes.c_char_p * 4)(payload['uri'].encode(), b'5', b'UTF8', None)
    connection = pq.PQconnectdbParams(keys, values, 1)
    if not connection or pq.PQstatus(connection) != 0:
        raise ValueError('connection failed')
    found = []
    for statement in payload['sql'].split(';'):
        if not statement.strip():
            continue
        result = pq.PQexec(connection, statement.encode())
        if not result:
            raise ValueError('query failed')
        try:
            status = pq.PQresultStatus(result)
            if status == 2:
                if pq.PQntuples(result) != 1 or pq.PQnfields(result) != 1:
                    raise ValueError('query result shape failed')
                found.append(pq.PQgetvalue(result, 0, 0).decode())
            elif status != 1:
                raise ValueError('query status failed')
        finally:
            pq.PQclear(result)
    if len(found) != 1:
        raise ValueError('query result count failed')
    print(found[0])
except BaseException:
    raise SystemExit('read-only libpq target identity failed') from None
finally:
    if connection:
        pq.PQfinish(connection)
"""


def assert_compose_source_labels(source_labels, backend_labels):
    canonical_cwd = str(Path.cwd().resolve(strict=True))
    required = {
        'com.docker.compose.project.working_dir': canonical_cwd,
        'com.docker.compose.project.config_files': str(Path(canonical_cwd) / 'docker-compose.yml'),
    }
    for labels in (source_labels, backend_labels):
        if not isinstance(labels, dict) or any(labels.get(key) != value for key, value in required.items()):
            fail('database containers belong to another Compose working directory or config source')


def assert_live_target(uri_path, backend_image, expected_uri_sha):
    # All credential-bearing configuration and inspect output stays in this process.
    raw_uri = Path(uri_path).read_bytes()
    if (not re.fullmatch('[0-9a-f]{64}', expected_uri_sha)
            or hashlib.sha256(raw_uri).hexdigest() != expected_uri_sha):
        fail('database URI bytes differ from immutable authority')
    clean = {key: os.environ[key] for key in ('PATH', 'HOME')}
    compose_env = dict(clean, BACKEND_IMAGE=backend_image)
    compose = ['docker', 'compose', '--env-file', '.env', '--file', 'docker-compose.yml']

    def command(argv, *, payload=None, environment=None):
        result = subprocess.run(argv, input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env=environment or clean, timeout=12, check=False)
        if result.returncode:
            fail('database target consumer failed')
        return result.stdout

    def ids(service, running):
        arguments = ['ps', '--status', 'running', '-q', '--no-trunc', service] if running else ['ps', '-aq', '--no-trunc', service]
        values = command(compose + arguments, environment=compose_env).decode().splitlines()
        if len(values) != 1 or not re.fullmatch('[0-9a-f]{64}', values[0]):
            fail('database target requires one canonical source/backend container')
        return values[0]

    def inspected(cid):
        values = json.loads(command(['docker', 'inspect', cid]))
        if not isinstance(values, list) or len(values) != 1 or values[0].get('Id') != cid:
            fail('database target container identity changed')
        return values[0]

    source_id, backend_id = ids('postgres', True), ids('backend', False)
    source, backend = inspected(source_id), inspected(backend_id)
    config = json.loads(command(compose + ['config', '--format', 'json'], environment=compose_env))
    source_labels, backend_labels = source['Config']['Labels'], backend['Config']['Labels']
    assert_compose_source_labels(source_labels, backend_labels)
    if (source_labels.get('com.docker.compose.service') != 'postgres'
            or backend_labels.get('com.docker.compose.service') != 'backend'
            or not source_labels.get('com.docker.compose.project')
            or source_labels.get('com.docker.compose.project') != backend_labels.get('com.docker.compose.project')):
        fail('database target project/service identity differs')
    # The tracked Compose puts both services on the same single default network.
    def networks(service):
        declared = config['services'][service].get('networks', {'default': None})
        if not isinstance(declared, dict) or len(declared) != 1:
            fail('database target has unsupported Compose networks')
        return set(declared)
    logical = networks('postgres')
    if networks('backend') != logical:
        fail('future backend/source Compose networks differ')
    name = config['networks'][next(iter(logical))]['name']
    if name not in source['NetworkSettings']['Networks']:
        fail('source does not use the future Compose network')
    networks_found = json.loads(command(['docker', 'network', 'inspect', name]))
    if not isinstance(networks_found, list) or len(networks_found) != 1:
        fail('future database network is not unique')
    planned_network = networks_found[0]
    project = source_labels['com.docker.compose.project']
    if (planned_network.get('Name') != name
            or planned_network.get('Id') != source['NetworkSettings']['Networks'][name]['NetworkID']
            or planned_network.get('Labels', {}).get('com.docker.compose.project') != project
            or not set(planned_network.get('Containers', {})).issubset({source_id, backend_id})
            or source_id not in planned_network.get('Containers', {})):
        fail('future database network identity or endpoint inventory differs')
    # A stopped backend cannot run a DNS consumer. Its exact retained network and
    # future Compose plan must agree; recheck from the created/running backend later.
    resolver = backend_id if backend['State']['Running'] else source_id
    dns = command(['docker', 'exec', resolver, 'getent', 'ahostsv4', 'postgres']).decode().splitlines()
    addresses = []
    for line in dns:
        parts = line.split()
        if len(parts) not in (2, 3) or not re.fullmatch(r'(?:[0-9]{1,3}\.){3}[0-9]{1,3}', parts[0]):
            fail('database DNS consumer returned malformed output')
        addresses.append(parts[0])
    backend_contract(config, source, backend, addresses, planned_network)
    native = command(['docker', 'exec', '-i', source_id, 'sh', '-c',
                      ': "${POSTGRES_USER:?}" "${POSTGRES_DB:?}"; '
                      'env -i PATH="$PATH" PGCONNECT_TIMEOUT=5 psql -XqAtw -U "$POSTGRES_USER" -d "$POSTGRES_DB" --set=ON_ERROR_STOP=1'],
                     payload=IDENTITY_SQL.encode())
    raw = raw_uri.removesuffix(b'\n')
    uri = raw.decode('utf-8')
    if (not uri.startswith(('postgresql://', 'postgres://')) or not uri
            or any(ord(ch) < 32 or ord(ch) == 127 for ch in uri)):
        fail('database target URI is malformed')
    parsed = urlsplit(uri)
    if (not parsed.hostname or not parsed.username or not parsed.password or parsed.fragment
            or not parsed.path.startswith('/') or parsed.path.count('/') != 1 or len(parsed.path) < 2
            or ',' in parsed.netloc):
        fail('database target URI lacks explicit connection authority')
    host = command(['python3', '-c', LIBPQ_IDENTITY_WORKER],
                   payload=json.dumps({'uri': uri, 'sql': IDENTITY_SQL}).encode())
    digest = equal_identities([native, host])
    # Inspect again to reject container replacement/config drift during the reads.
    def stable(container):
        return {key: container[key] for key in ('Id', 'Config', 'RestartCount', 'HostConfig')} | {
            'networks': container['NetworkSettings']['Networks'],
            'started_at': container['State']['StartedAt'], 'running': container['State']['Running']}
    if (json.loads(command(['docker', 'network', 'inspect', name])) != [planned_network]
            or stable(inspected(source_id)) != stable(source) or stable(inspected(backend_id)) != stable(backend)
            or ids('postgres', True) != source_id or ids('backend', False) != backend_id):
        fail('database target container changed during verification')
    print('DATABASE_TARGET_EQUALITY=' + digest)


def main(argv):
    if argv == ['identity-sql']:
        print(IDENTITY_SQL, end='')
    elif len(argv) == 4 and argv[0] == 'live-target':
        assert_live_target(argv[1], argv[2], argv[3])
    elif len(argv) == 2 and argv[0] == 'preflight':
        preflight_outcome(Path(argv[1]).read_bytes())
    elif len(argv) == 5 and argv[0] == 'toc':
        if not re.fullmatch('[0-9a-f]{64}', argv[2]):
            fail('dump hash binding is malformed')
        digest = hashlib.sha256()
        with Path(argv[1]).open('rb') as dump:
            for block in iter(lambda: dump.read(1024 * 1024), b''):
                digest.update(block)
        if digest.hexdigest() != argv[2]:
            fail('selected DR backup hash mismatch')
        if canonical_toc(Path(argv[3]).read_bytes()) != canonical_toc(Path(argv[4]).read_bytes()):
            fail('DR inventory does not match the selected archive')
    elif len(argv) >= 3 and argv[0] == 'identity':
        print(equal_identities([Path(path).read_bytes() for path in argv[1:]]))
    else:
        fail('invalid database evidence operation')


if __name__ == '__main__':
    try:
        main(sys.argv[1:])
    except (ValueError, OSError, UnicodeError, KeyError, TypeError, subprocess.SubprocessError):
        raise SystemExit('database evidence refused; no credentials or target details are logged') from None
