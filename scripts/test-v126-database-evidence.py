#!/usr/bin/env python3
"""F01/F07/F08 and G01: exact consumers, isolated PG17, clients17/18.

Default execution requires PG17 and psql/pg_restore17+18; no Docker daemon is used.
PG17_BIN/PG18_BIN select installed binary directories, never a database endpoint.
--unit runs only pure evidence decisions and does not imply PostgreSQL coverage.
The full preflight uses a minimal real relational fixture, not substituted SQL.
Preflight/DR fixtures mock remote authority/target-binding/transport/Caddy/zero-writer
and GNU-stat boundaries. Separate live-target tests execute real native psql and
libpq queries with synthetic Docker/Compose/DNS/network replies.
"""
import argparse
import contextlib
import io
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
from urllib.parse import quote

ROOT = Path(__file__).resolve().parent.parent
BASE = 'f7828e09863d391e1f714cc65c9c866f814cf6bf'
helper_path = ROOT / 'scripts/v126-database-evidence.py'
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('database_evidence', helper_path)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
dr_spec = importlib.util.spec_from_file_location('v126_dr_evidence', ROOT / 'scripts/v126-dr-evidence.py')
dr = importlib.util.module_from_spec(dr_spec)
sys.modules[dr_spec.name] = dr
dr_spec.loader.exec_module(dr)
EVENTS = []


def run(argv, *, env=None, input=None, check=True, timeout=90):
    result = subprocess.run(argv, input=input, env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout)
    EVENTS.append({'argv': [str(x) for x in argv], 'exit': result.returncode})
    if check and result.returncode:
        raise AssertionError('synthetic test command failed: ' + str(argv[0]) + ' exit ' + str(result.returncode)
                             + '\n' + result.stderr.decode(errors='replace')[:2000])
    return result


def extract(runbook, sequencer=None):
    # Git supplies canonical document bytes synthetically; the production extractor,
    # marker/one-client/source/hash/file-creation decisions execute unchanged.
    with tempfile.TemporaryDirectory(prefix='v126-extract-',dir='/tmp') as directory:
        root=Path(directory)
        source=root/'runbook.md';source.write_text(runbook)
        target=root/'preflight.sh'
        code=r'''source "$1"
fixture_book="$2"
release_git() { cat "$fixture_book"; }
RELEASE_SHA=ffffffffffffffffffffffffffffffffffffffff
RELEASE_WORKTREE="$4"
extract_booking_preflight "$3"
'''
        result=run(['bash','-c',code,'v126-extraction-fixture',str(sequencer or ROOT/'scripts/v126-cutover.sh'),
                    str(source),str(target),str(ROOT)])
        script=target.read_bytes()
        assert result.stdout.decode().strip()==hashlib.sha256(script).hexdigest()
        return script


SCHEMA = '''
CREATE TABLE bookings(id bigint PRIMARY KEY, venue_id bigint, user_id bigint);
CREATE TABLE support_threads(id bigint PRIMARY KEY, thread_type text, booking_id bigint,
 venue_id bigint, guest_user_id bigint, status text, created_at timestamp DEFAULT now());
CREATE TABLE support_messages(id bigint PRIMARY KEY, thread_id bigint REFERENCES support_threads(id) ON DELETE CASCADE);
CREATE TABLE support_thread_reads(thread_id bigint REFERENCES support_threads(id) ON DELETE CASCADE,
 user_id bigint, last_read_at timestamp, PRIMARY KEY(thread_id,user_id));
CREATE TABLE audit_log(id bigint PRIMARY KEY, entity_type text, action text, entity_id bigint, payload_json text);
CREATE TABLE analytics_events(payload_json text);
CREATE TABLE billing_invoices(provider_raw_payload text);
CREATE TABLE billing_notifications(payload_json text);
CREATE TABLE billing_payments(raw_payload text);
CREATE TABLE guest_batch_idempotency(response_snapshot jsonb);
CREATE TABLE menu_items(options jsonb);
CREATE TABLE order_batches(items_snapshot jsonb);
CREATE TABLE order_promotion_applications(schedule_snapshot_json text,target_snapshot_json text);
CREATE TABLE telegram_dialog_state(payload jsonb);
CREATE TABLE telegram_inbound_updates(payload_json text,status text);
CREATE TABLE telegram_outbox(payload_json text,status text);
CREATE TABLE venues(features jsonb,ui_layout jsonb);
CREATE TABLE visit_feedback(tags_json text);
CREATE TABLE flyway_schema_history(version text, success boolean, checksum integer);
INSERT INTO flyway_schema_history VALUES('125',true,0);
INSERT INTO bookings VALUES(1,1,1);
INSERT INTO support_threads(id,thread_type,booking_id,venue_id,guest_user_id,status) VALUES(1,'BOOKING_THREAD',1,1,1,'OPEN');
'''


# Shared by the existing Unix-socket rehearsal and the optional local Docker
# Policy B regression. These are synthetic values, never source DB payloads.
WHOLE_DR_SETUP_SQL = """CREATE ROLE repair_reader;
CREATE ROLE repair_login LOGIN;
GRANT repair_reader TO repair_login;
CREATE TABLE dr_payload(id integer PRIMARY KEY, value text);
INSERT INTO dr_payload VALUES(1,'synthetic');
ALTER TABLE dr_payload OWNER TO other_role;
GRANT SELECT ON dr_payload TO repair_reader;
CREATE SCHEMA dr_owned AUTHORIZATION other_role;
GRANT USAGE ON SCHEMA dr_owned TO repair_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE other_role IN SCHEMA dr_owned GRANT SELECT ON TABLES TO repair_reader;
CREATE SEQUENCE dr_sequence START 7;
ALTER SEQUENCE dr_sequence OWNER TO other_role;
SELECT nextval('dr_sequence');
ALTER DATABASE source OWNER TO other_role;
ALTER DATABASE source SET timezone='Asia/Tokyo';
ALTER ROLE repair_login SET statement_timeout='7s';
ALTER ROLE repair_login IN DATABASE source SET lock_timeout='3s';
INSERT INTO telegram_inbound_updates VALUES ('synthetic-inbound', 'PROCESSING');
INSERT INTO telegram_outbox VALUES ('synthetic-outbox', 'SENDING');
INSERT INTO guest_batch_idempotency VALUES ('{"synthetic":true}');
"""

WHOLE_DR_FACTS_SQL = {
    'ROLES_MEMBERSHIPS': """SELECT rolname,rolsuper,rolinherit,rolcreaterole,rolcreatedb,rolcanlogin,
rolreplication,rolconnlimit,rolvaliduntil,rolbypassrls FROM pg_roles WHERE rolname NOT LIKE 'pg_%' ORDER BY rolname;
SELECT pg_get_userbyid(roleid),pg_get_userbyid(member),pg_get_userbyid(grantor),admin_option,inherit_option,set_option
FROM pg_auth_members ORDER BY 1,2,3;""",
    'OWNERSHIP_ACL': """SELECT datname,pg_get_userbyid(datdba),datacl FROM pg_database WHERE datname='source';
SELECT nspname,pg_get_userbyid(nspowner),nspacl FROM pg_namespace WHERE nspname IN ('public','dr_owned','other_schema') ORDER BY 1;
SELECT n.nspname,c.relname,c.relkind,pg_get_userbyid(c.relowner),c.relacl FROM pg_class c
JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname IN ('public','dr_owned','other_schema') ORDER BY 1,2;
SELECT pg_get_userbyid(defaclrole),n.nspname,defaclobjtype,defaclacl FROM pg_default_acl d
JOIN pg_namespace n ON n.oid=d.defaclnamespace ORDER BY 1,2,3;
SELECT has_table_privilege('repair_reader','dr_payload','SELECT'),has_table_privilege('repair_reader','dr_payload','INSERT');""",
    'SETTINGS_AUTH_CONFIG': """SELECT rolname,rolconfig FROM pg_roles WHERE rolname NOT LIKE 'pg_%' ORDER BY 1;
SELECT coalesce(d.datname,''),coalesce(r.rolname,''),s.setconfig FROM pg_db_role_setting s
LEFT JOIN pg_database d ON d.oid=s.setdatabase LEFT JOIN pg_roles r ON r.oid=s.setrole ORDER BY 1,2;
SHOW timezone;""",
    'DATA_SCHEMA': """SELECT count(*),min(value),max(id) FROM dr_payload;
SELECT n.nspname,c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname IN ('public','dr_owned','other_schema') ORDER BY 1,2;
SELECT n.nspname,c.relname,a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull
FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND a.attnum>0 AND NOT a.attisdropped ORDER BY 1,2,a.attnum;
SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace='public'::regnamespace ORDER BY 1;
SELECT extname,extversion,extnamespace::regnamespace,pg_get_userbyid(extowner) FROM pg_extension ORDER BY 1;""",
    'FLYWAY': 'SELECT version,success,checksum FROM flyway_schema_history ORDER BY version;',
    'SEQUENCES': """SELECT last_value,is_called FROM dr_sequence;
SELECT s.seqrelid::regclass,s.seqtypid::regtype,s.seqstart,s.seqincrement,s.seqmax,s.seqmin,s.seqcache,s.seqcycle
FROM pg_sequence s ORDER BY 1;""",
    'DURABLE_QUEUES': """SELECT payload_json,status FROM telegram_inbound_updates ORDER BY 1,2;
SELECT payload_json,status FROM telegram_outbox ORDER BY 1,2;
SELECT response_snapshot FROM guest_batch_idempotency ORDER BY response_snapshot::text;""",
}


DRIVER = r'''
source "$1"
fixture="$2"
remote_initialize_compose() { :; }
remote_assert_database_target() { :; }
remote_require_run_root() { printf '%s\n' "$fixture/run"; }
remote_verify_proof() { :; }
remote_assert_public_drain() { :; }
remote_assert_zero_writer() { :; }
stat() { python3 "$fixture/statshim.py" "$@"; }
rm() {
  local item
  for item in "$@"; do
    if [[ "$item" == "$fixture/run/final-v125-preflight.output" && -f "$item" ]]; then
      cp "$item" "$fixture/preflight-output.captured"
    fi
  done
  command rm "$@"
}
remote_final_v125_preflight "$fixture" synthetic-only ffffffffffffffffffffffffffffffffffffffff fixture:ffffffffffffffffffffffffffffffffffffffff "$fixture/target.uri" "$fixture/run/final-v125-preflight.sh.partial" "$3" "$4"
'''


class EvidenceUnitTests(unittest.TestCase):
    def test_preflight_outcome_requires_count0_and_unique_safe(self):
        good = b'V126_PREFLIGHT_RESULT={"version":1,"safe":true,"unsafe_count":0}\nBOOKING_THREAD_PREFLIGHT_SAFE_TO_CONTINUE\n'
        self.assertEqual(helper.preflight_outcome(good)['unsafe_count'], 0)
        for bad in [b'', good + good, good.replace(b'count":0', b'count":1'),
                    good.replace(b'count":0', b'count":false'), good.replace(b'true', b'false'), good.replace(b'"safe":true', b'"safe":false,"safe":true'),
                    good + b'STOP_FOR_BOOKING_THREAD_DEDUPLICATION_DECISION\n']:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                helper.preflight_outcome(bad)

    def test_backend_contract_rejects_target_and_network_divergence(self):
        network = {'NetworkID': 'n1', 'IPAddress': '172.29.0.2', 'Aliases': ['postgres']}
        source = {'Config': {'Env': ['POSTGRES_DB=source', 'POSTGRES_USER=owner', 'POSTGRES_PASSWORD=synthetic']},
                  'NetworkSettings': {'Networks': {'own_default': network}}}
        required = {'DB_JDBC_URL': 'jdbc:postgresql://postgres:5432/source', 'DB_USER': 'owner', 'DB_PASSWORD': 'synthetic'}
        backend = {'Config': {'Env': [key+'='+value for key,value in required.items()]},
                   'NetworkSettings': {'Networks': {'own_default': dict(network, IPAddress='172.29.0.3')}}}
        compose = {'services': {'backend': {'environment': required}}}
        self.assertTrue(helper.backend_contract(compose, source, backend, ['172.29.0.2']))
        for key, value in [('DB_JDBC_URL','jdbc:postgresql://postgres:5432/other'),
                           ('DB_JDBC_URL','jdbc:postgresql://postgres:5432/source?currentSchema=other'),
                           ('DB_USER','other'),('DB_PASSWORD','other')]:
            wrong = json.loads(json.dumps(compose))
            wrong['services']['backend']['environment'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                helper.backend_contract(wrong,source,backend,['172.29.0.2'])
        for addresses in [[],['172.29.0.3'],['172.29.0.2','172.29.0.4']]:
            with self.assertRaises(ValueError):
                helper.backend_contract(compose,source,backend,addresses)
        backend['NetworkSettings']['Networks']['own_default']['NetworkID']='other'
        with self.assertRaises(ValueError):
            helper.backend_contract(compose,source,backend,['172.29.0.2'])

    def test_compose_source_labels_bind_canonical_target(self):
        cwd=str(Path.cwd().resolve())
        labels={'com.docker.compose.project':'staging',
                'com.docker.compose.project.working_dir':cwd,
                'com.docker.compose.project.config_files':str(Path(cwd)/'docker-compose.yml')}
        helper.assert_compose_source_labels(labels,labels)
        other=str(Path(cwd).parent/'another-parent'/Path(cwd).name)
        for key,value in [('com.docker.compose.project.working_dir',other),
                          ('com.docker.compose.project.config_files',str(Path(other)/'docker-compose.yml')),
                          ('com.docker.compose.project.config_files',labels['com.docker.compose.project.config_files']+',/tmp/override.yml')]:
            wrong=dict(labels);wrong[key]=value
            for source,backend in [(wrong,labels),(labels,wrong),(wrong,wrong)]:
                with self.subTest(key=key,value=value),self.assertRaises(ValueError):
                    helper.assert_compose_source_labels(source,backend)
        with self.assertRaises(ValueError):
            helper.assert_compose_source_labels({},labels)

    def test_embedded_helper_is_exact(self):
        script = (ROOT / 'scripts/v126-cutover.sh').read_text()
        marker = "remote_database_evidence_python() {\n  cat <<'PY'\n"
        self.assertEqual(script.count(marker), 1, 'root integration must embed the authoritative helper')
        embedded = script.split(marker, 1)[1].split('\nPY\n}', 1)[0] + '\n'
        self.assertEqual(embedded, helper_path.read_text())


class PostgreSQLTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.umask(0o077)
        cls.bin17 = Path(os.environ.get('PG17_BIN', '/usr/lib/postgresql/17/bin'))
        cls.bin18 = Path(os.environ.get('PG18_BIN', '/usr/lib/postgresql/18/bin'))
        for directory, major in [(cls.bin17, '17'), (cls.bin18, '18')]:
            for tool in ['psql', 'pg_restore']:
                version = run([directory/tool, '--version']).stdout.decode().strip()
                if '(PostgreSQL) ' + major + '.' not in version:
                    raise AssertionError('wrong mandatory PostgreSQL client major')
                print(version)
        cls.root = Path(tempfile.mkdtemp(prefix='v126-db-evidence-', dir='/tmp'))
        cls.socket = Path(tempfile.mkdtemp(prefix='v126pg-', dir='/tmp'))
        cls.env = {k: v for k, v in os.environ.items() if k in ('PATH', 'HOME', 'TMPDIR')}
        cls.env.update(PATH=str(cls.bin17)+os.pathsep+cls.env['PATH'], PGHOST=str(cls.socket),
                       PGPORT='55489', PGUSER='repair_owner', PGDATABASE='postgres', LC_ALL='C', TZ='UTC')
        cls.data = cls.root/'data'
        cls.started = False
        try:
            run([cls.bin17/'initdb','-D',cls.data,'-U','repair_owner','-A','trust','--encoding=UTF8','--locale=C'], env=cls.env)
            run([cls.bin17/'pg_ctl','-D',cls.data,'-l',cls.root/'postgres.log',
                 '-o',"-h '' -k " + str(cls.socket) + ' -p 55489','-w','start'], env=cls.env)
            cls.started = True
            cls.sql('CREATE DATABASE source; CREATE DATABASE other;')
            cls.sql(SCHEMA, 'source')
            cls.sql(SCHEMA, 'other')
            cls.sql('CREATE SCHEMA other_schema; CREATE ROLE other_role LOGIN; GRANT EXECUTE ON FUNCTION pg_control_system() TO other_role;', 'source')
            cls.old_script = cls.root/'before.sh'
            cls.old_script.write_bytes(run(['git','-C',str(ROOT),'show',BASE+':scripts/v126-cutover.sh']).stdout)
            old_book = run(['git','-C',str(ROOT),'show',BASE+':docs/DEPLOYMENT_RUNBOOK.md']).stdout.decode()
            cls.before = extract(old_book, cls.old_script)
            cls.after = extract((ROOT/'docs/DEPLOYMENT_RUNBOOK.md').read_text())
        except BaseException:
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        if getattr(cls, 'started', False):
            run([cls.bin17/'pg_ctl','-D',cls.data,'-m','immediate','-w','stop'], env=cls.env)
        if hasattr(cls, 'data') and not (cls.data/'postmaster.pid').exists():
            shutil.rmtree(cls.data, ignore_errors=True)
            shutil.rmtree(cls.socket, ignore_errors=True)
        destination = os.environ.get('V126_DB_EVIDENCE_DIR')
        if destination and hasattr(cls, 'root'):
            target = Path(destination)
            target.mkdir(parents=True, exist_ok=True)
            (target/(cls.root.name+'-commands.json')).write_text(json.dumps(EVENTS, indent=2))
            (target/'fixture-location.txt').write_text(str(cls.root)+'\nOwned PostgreSQL stopped; data/socket removed. No daemon/live target.\n')
        elif hasattr(cls, 'root'):
            shutil.rmtree(cls.root, ignore_errors=True)

    @classmethod
    def sql(cls, statement, db='postgres', **kw):
        env = dict(cls.env, PGDATABASE=db)
        env.update(kw.pop('extra', {}))
        return run([cls.bin17/'psql','-XqAtw','--set=ON_ERROR_STOP=1'], env=env,
                   input=statement.encode(), **kw).stdout

    def wrapper(self, script, client, before=False, fault=None):
        case = Path(tempfile.mkdtemp(prefix='case-', dir=self.root))
        os.chown(case, -1, os.getgid())
        (case/'run').mkdir(mode=0o700)
        partial = case/'run/final-v125-preflight.sh.partial'
        partial.write_bytes(script)
        partial.chmod(0o600)
        uri = case/'target.uri'
        uri.write_text('postgresql://repair_owner:synthetic@'+quote(str(self.socket), safe='')+':55489/source\n')
        (case/'statshim.py').write_text("import os,pwd,grp,stat,sys\n_,fmt,path=sys.argv[1:]\ns=os.stat(path);mode=format(stat.S_IMODE(s.st_mode),'o');print({'%a':mode,'%a:%U:%G':mode+':'+pwd.getpwuid(s.st_uid).pw_name+':'+grp.getgrgid(s.st_gid).gr_name}[fmt])\n")
        (case/'driver.sh').write_text(DRIVER)
        path = str(client)+os.pathsep+self.env['PATH']
        if fault:
            shim = case/'bin'; shim.mkdir()
            psql = shim/'psql'
            if fault == 'success-nonzero':
                psql.write_text('#!/bin/bash\n"'+str(client/'psql')+'" "$@"\nexit 73\n')
            elif fault == 'exit0-without-result':
                psql.write_text('#!/bin/bash\ncat >/dev/null\nexit 0\n')
            psql.chmod(0o700)
            path = str(shim)+os.pathsep+path
        env = dict(self.env, PATH=path)
        selected = self.old_script if before else ROOT/'scripts/v126-cutover.sh'
        result = run(['bash',case/'driver.sh',selected,case,hashlib.sha256(script).hexdigest(),
                      hashlib.sha256(uri.read_bytes()).hexdigest()],env=env,check=False)
        return result, case

    def test_full_extracted_preflight_before_after_all_clients(self):
        self.sql("INSERT INTO support_threads(id,thread_type,booking_id,venue_id,guest_user_id,status) VALUES(2,'BOOKING_THREAD',NULL,1,1,'OPEN');",'source')
        try:
            for client in [self.bin17, self.bin18]:
                before, before_case = self.wrapper(self.before, client, before=True)
                self.assertEqual(before.returncode, 0, before.stderr.decode())
                self.assertTrue((before_case/'run/final-v125-preflight.proof').exists())
                before_output=(before_case/'preflight-output.captured').read_bytes()
                self.assertIn(b'unsafe_row_count= 1',before_output)
                self.assertIn(b'STOP_FOR_BOOKING_THREAD_DEDUPLICATION_DECISION',before_output)
                self.assertNotIn(b'BOOKING_THREAD_PREFLIGHT_SAFE_TO_CONTINUE',before_output)
                after, after_case = self.wrapper(self.after, client)
                self.assertNotEqual(after.returncode, 0)
                self.assertFalse((after_case/'run/final-v125-preflight.proof').exists())
                self.assertIn(b'STOP_FOR_BOOKING_THREAD_DEDUPLICATION_DECISION', (after_case/'run/final-v125-preflight.output').read_bytes())
                self.assertFalse((after_case/'run/final-v125-preflight.pgpass').exists())
        finally:
            self.sql('DELETE FROM support_threads WHERE id=2;', 'source')
        for client in [self.bin17, self.bin18]:
            success, case = self.wrapper(self.after, client)
            self.assertEqual(success.returncode, 0, success.stderr.decode())
            self.assertTrue((case/'run/final-v125-preflight.proof').exists())
            helper.preflight_outcome((case/'preflight-output.captured').read_bytes())
            self.assertFalse((case/'run/final-v125-preflight.output').exists())
            for fault in ['success-nonzero', 'exit0-without-result']:
                failure, bad = self.wrapper(self.after, client, fault=fault)
                self.assertNotEqual(failure.returncode, 0)
                self.assertFalse((bad/'run/final-v125-preflight.proof').exists())
        self.assertEqual(self.sql('SELECT count(*) FROM support_threads;', 'source').strip(), b'1')

    def test_actual_database_schema_and_role_equality(self):
        identity = self.sql(helper.IDENTITY_SQL, 'source')
        self.assertEqual(len(helper.equal_identities([identity, identity])), 64)
        alternatives = [self.sql(helper.IDENTITY_SQL, 'other'),
                        self.sql('SET search_path=other_schema;'+helper.IDENTITY_SQL, 'source'),
                        self.sql('SET SESSION AUTHORIZATION other_role;'+helper.IDENTITY_SQL, 'source')]
        for other in alternatives:
            helper.database_identity(other)
            with self.assertRaises(ValueError):
                helper.equal_identities([identity, other])

    def test_live_target_pipeline_uses_real_psql_and_distinguishes_targets(self):
        source_id, backend_id = '1'*64, '2'*64
        network = {'NetworkID':'own-network','IPAddress':'172.29.0.2','Aliases':['postgres']}
        state = {'Running':True,'StartedAt':'synthetic-start'}
        source = {'Id':source_id,'RestartCount':0,'State':state,'HostConfig':{'NetworkMode':'own_default'},
                  'Config':{'Env':['POSTGRES_DB=source','POSTGRES_USER=repair_owner','POSTGRES_PASSWORD=synthetic'],
                            'Labels':{'com.docker.compose.service':'postgres','com.docker.compose.project':'own'}},
                  'NetworkSettings':{'Networks':{'own_default':network}}}
        source_origin={'com.docker.compose.project.working_dir':str(Path.cwd().resolve()),
                       'com.docker.compose.project.config_files':str(Path.cwd().resolve()/'docker-compose.yml')}
        source['Config']['Labels'].update(source_origin)
        required = {'DB_JDBC_URL':'jdbc:postgresql://postgres:5432/source','DB_USER':'repair_owner','DB_PASSWORD':'synthetic'}
        backend = {'Id':backend_id,'RestartCount':0,'State':dict(state),'HostConfig':{'NetworkMode':'own_default'},
                   'Config':{'Env':[key+'='+value for key,value in required.items()],
                             'Labels':{'com.docker.compose.service':'backend','com.docker.compose.project':'own'}},
                   'NetworkSettings':{'Networks':{'own_default':dict(network,IPAddress='172.29.0.3',Aliases=['backend'])}}}
        backend['Config']['Labels'].update(source_origin)
        compose = {'services':{'backend':{'environment':required},'postgres':{}},
                   'networks':{'default':{'name':'own_default'}}}
        planned = {'Name':'own_default','Id':'own-network','Labels':{'com.docker.compose.project':'own'},
                   'Containers':{source_id:{'IPv4Address':'172.29.0.2/24'}}}
        real_run = subprocess.run
        calls = []
        def dispatch(argv, **kw):
            if argv[0] != 'docker':
                return real_run(argv,**kw)
            calls.append(argv)
            if argv[1]=='compose':
                result = json.dumps(compose).encode() if 'config' in argv else ((source_id if argv[-1]=='postgres' else backend_id)+'\n').encode()
            elif argv[1:3]==['network','inspect']:
                result=json.dumps([planned]).encode()
            elif argv[1]=='inspect':
                result=json.dumps([source if argv[-1]==source_id else backend]).encode()
            elif argv[1:3]==['exec','-i']:
                forwarded=dict(kw,env=dict(self.env,PGDATABASE='source'))
                return real_run([str(self.bin17/'psql'),'-XqAtw','--set=ON_ERROR_STOP=1'],**forwarded)
            elif 'getent' in argv:
                result=b'172.29.0.2 STREAM postgres\n172.29.0.2 DGRAM\n'
            else:
                raise AssertionError('unexpected synthetic Docker boundary')
            return subprocess.CompletedProcess(argv,0,result,b'')
        uri=self.root/'binding.uri'
        uri_prefix='postgresql://repair_owner:synthetic@'+quote(str(self.socket),safe='')+':55489/'
        with mock.patch.dict(os.environ,{'PATH':str(self.bin17)+os.pathsep+self.env['PATH']}), mock.patch.object(subprocess,'run',dispatch):
            for running in [True,False]:
                backend['State']['Running']=running
                backend['NetworkSettings']['Networks'] = {'own_default':dict(network,IPAddress='172.29.0.3',Aliases=['backend'])} if running else {}
                uri.write_text(uri_prefix+'source')
                with contextlib.redirect_stdout(io.StringIO()) as output:
                    helper.assert_live_target(uri,'fixture:exact',hashlib.sha256(uri.read_bytes()).hexdigest())
                self.assertRegex(output.getvalue(),r'^DATABASE_TARGET_EQUALITY=[0-9a-f]{64}\n$')
            for target in ['other','source?options=-csearch_path%3Dother_schema']:
                uri.write_text(uri_prefix+target)
                with self.assertRaises(ValueError):
                    helper.assert_live_target(uri,'fixture:exact',hashlib.sha256(uri.read_bytes()).hexdigest())
            uri.write_text(uri_prefix+'source')
            called_before=len(calls)
            with self.assertRaises(ValueError):
                helper.assert_live_target(uri,'fixture:exact','0'*64)
            self.assertEqual(len(calls),called_before)
            for container in [source,backend]:
                for key in source_origin:
                    container['Config']['Labels'][key]='/tmp/another-parent/'+Path.cwd().name+('/docker-compose.yml' if key.endswith('config_files') else '')
                    before_calls=len(calls)
                    with self.assertRaises(ValueError):
                        helper.assert_live_target(uri,'fixture:exact',hashlib.sha256(uri.read_bytes()).hexdigest())
                    self.assertFalse(any(command[1:3]==['exec','-i'] for command in calls[before_calls:]))
                    container['Config']['Labels'][key]=source_origin[key]
            planned['Containers']['3'*64]={}
            with self.assertRaises(ValueError):
                helper.assert_live_target(uri,'fixture:exact',hashlib.sha256(uri.read_bytes()).hexdigest())
        self.assertTrue(any(command[1:3]==['exec','-i'] for command in calls))

    def test_full_dr_consumer_before_after_timezone(self):
        dump = run([self.bin17/'pg_dump','--format=custom','--dbname=source'],env=self.env).stdout
        inventory = run([self.bin17/'pg_restore','--list'],env=self.env,input=dump).stdout
        driver = r'''source "$1"
fixture="$2"
remote_initialize_compose() { :; }
remote_assert_database_target() { :; }
remote_require_run_root() { printf '%s\n' "$fixture/run"; }
remote_assert_caddy_drain_marker() { :; }
remote_assert_public_drain() { :; }
remote_assert_zero_writer() { :; }
remote_flyway_state() { printf '125:1:0:0\n'; }
remote_backup_root() { printf '%s\n' "$fixture"; }
remote_compose() { [[ "$1 $2 $3" == 'exec -T postgres' ]]; shift 3; "$@"; }
stat() { python3 "$fixture/statshim.py" "$@"; }
export POSTGRES_USER=repair_owner POSTGRES_DB=source
remote_verify_full_dr "$fixture" synthetic-only ffffffffffffffffffffffffffffffffffffffff fixture:ffffffffffffffffffffffffffffffffffffffff pre-drain "$3" "$4" bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb NONE
'''
        for before, zone, expected_success in [(True,'UTC',True),(True,'Asia/Tokyo',False),(False,'UTC',True),(False,'Asia/Tokyo',True)]:
            case=Path(tempfile.mkdtemp(prefix='toc-',dir=self.root));os.chown(case,-1,os.getgid())
            (case/'run').mkdir(mode=0o700)
            (case/'pre-drain.dump').write_bytes(dump)
            (case/'pre-drain.dump.pg_restore.list').write_bytes(inventory)
            (case/'driver.sh').write_text(driver)
            (case/'statshim.py').write_text("import os,pwd,grp,stat,sys\n_,fmt,path=sys.argv[1:]\ns=os.stat(path);mode=format(stat.S_IMODE(s.st_mode),'o');print({'%a':mode,'%a:%U:%G':mode+':'+pwd.getpwuid(s.st_uid).pw_name+':'+grp.getgrgid(s.st_gid).gr_name}[fmt])\n")
            selected=self.old_script if before else ROOT/'scripts/v126-cutover.sh'
            result=run(['bash',case/'driver.sh',selected,case,hashlib.sha256(dump).hexdigest(),hashlib.sha256(inventory).hexdigest()],env=dict(self.env,TZ=zone),check=False)
            self.assertEqual(result.returncode==0,expected_success,result.stderr.decode())
            self.assertEqual((case/'run/recovery-full-dr-prerequisites.proof').exists(),expected_success)

    def test_real_toc_timezone_clients_and_changes(self):
        dump = run([self.bin17/'pg_dump','--format=custom','--dbname=source'],env=self.env).stdout
        sha = hashlib.sha256(dump).hexdigest()
        retained = run([self.bin17/'pg_restore','--list'],env=self.env,input=dump).stdout
        for client in [self.bin17, self.bin18]:
            for zone in ['UTC','Asia/Tokyo']:
                listing = run([client/'pg_restore','--list'], env=dict(self.env,TZ=zone),input=dump).stdout
                helper.compare_toc(dump, sha, retained, listing)
        self.assertNotEqual(retained, listing)
        with self.assertRaises(ValueError):
            helper.compare_toc(dump+b'changed', sha, retained, retained)
        changed = retained.replace(b'TABLE public bookings ', b'TABLE public other_bookings ')
        self.assertNotEqual(changed, retained)
        with self.assertRaises(ValueError):
            helper.compare_toc(dump, sha, retained, changed)
        with self.assertRaises(ValueError):
            helper.compare_toc(dump, sha, retained, retained.replace(b';     Format: CUSTOM', b';     Format: OTHER'))


    def test_whole_db_roles_acl_settings_and_synthetic_auth(self):
        self.sql(WHOLE_DR_SETUP_SQL, 'source')
        self.sql("ALTER ROLE repair_login PASSWORD 'synthetic_DR_auth_only';", 'source')
        source_hba = self.data/'pg_hba.conf'
        source_hba.write_text('local all repair_login scram-sha-256\n' + source_hba.read_text())
        run([self.bin17/'pg_ctl','-D',self.data,'reload'],env=self.env)
        dump = run([self.bin17/'pg_dump','--format=custom','--dbname=source'],env=self.env).stdout
        globals_sql = run([self.bin17/'pg_dumpall','-l','source','--globals-only','--no-role-passwords'],env=self.env).stdout
        self.assertNotIn(b'SCRAM-SHA-256$', globals_sql)
        self.assertNotIn(b'synthetic_DR_auth_only', globals_sql)
        self.sql('CREATE DATABASE schema_only;')
        run([self.bin17/'pg_restore','--exit-on-error','--no-owner','--no-privileges','--dbname=schema_only'],env=self.env,input=dump)
        loss_query = "SELECT tableowner FROM pg_tables WHERE tablename='dr_payload'; SELECT has_table_privilege('repair_reader','dr_payload','SELECT');"
        self.assertEqual(self.sql(loss_query,'source'),b'other_role\nt\n')
        self.assertEqual(self.sql(loss_query,'schema_only'),b'repair_owner\nf\n')
        target = Path(tempfile.mkdtemp(prefix='restore-',dir=self.root))
        socket = Path(tempfile.mkdtemp(prefix='v126dr-',dir='/tmp'))
        data = target/'data'
        env = dict(self.env,PGHOST=str(socket),PGUSER='repair_owner')
        started = False
        try:
            run([self.bin17/'initdb','-D',data,'-U','repair_owner','-A','trust','--encoding=UTF8','--locale=C'],env=env)
            hba = data/'pg_hba.conf'
            hba.write_text('local all repair_login scram-sha-256\n' + hba.read_text())
            run([self.bin17/'pg_ctl','-D',data,'-l',target/'postgres.log','-o',"-h '' -k "+str(socket)+' -p 55489','-w','start'],env=env)
            started = True
            # The only allowed bootstrap conflict is the exact initdb role in an empty cluster.
            self.assertEqual(globals_sql.count(b'CREATE ROLE repair_owner;\n'), 1)
            bootstrap = run([self.bin17/'psql','-XqAtw','--set=ON_ERROR_STOP=1'],env=env,
                            input=b"SELECT rolname FROM pg_roles WHERE rolname NOT LIKE 'pg_%' ORDER BY rolname;").stdout
            self.assertEqual(bootstrap,b'repair_owner\n')
            databases = run([self.bin17/'psql','-XqAtw','--set=ON_ERROR_STOP=1'],env=env,
                            input=b'SELECT datname FROM pg_database ORDER BY datname;').stdout.decode().splitlines()
            globals_restore, transformation = dr.bootstrap_globals(
                globals_sql, 'repair_owner', bootstrap.decode().splitlines(), databases)
            self.assertEqual(transformation['original_sha256'], hashlib.sha256(globals_sql).hexdigest())
            run([self.bin17/'psql','-XqAtw','--set=ON_ERROR_STOP=1'],env=env,input=globals_restore)
            run([self.bin17/'pg_restore','--exit-on-error','--create','--dbname=postgres'],env=env,input=dump)
            # Passwords are a distinct synthetic recovery input, never from globals.
            run([self.bin17/'psql','-XqAtw','--set=ON_ERROR_STOP=1'],env=env,
                input=b"ALTER ROLE repair_login PASSWORD 'synthetic_DR_auth_only';")
            facts = """SELECT tableowner FROM pg_tables WHERE schemaname='public' AND tablename='dr_payload';
SELECT has_table_privilege('repair_reader','dr_payload','SELECT');
SELECT pg_has_role('repair_login','repair_reader','MEMBER');
SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='source';
SELECT count(*)||':'||min(value) FROM dr_payload;
SELECT nspname||':'||pg_get_userbyid(nspowner)||':'||nspacl::text FROM pg_namespace WHERE nspname='dr_owned';
SELECT defaclobjtype::text||':'||defaclacl::text FROM pg_default_acl WHERE defaclnamespace=(SELECT oid FROM pg_namespace WHERE nspname='dr_owned');
SELECT last_value FROM dr_sequence;
SELECT extname||':'||extversion FROM pg_extension ORDER BY extname;
SELECT rolname||':'||coalesce(rolconfig::text,'') FROM pg_roles WHERE rolname='repair_login';
SELECT setconfig::text FROM pg_db_role_setting WHERE setdatabase=(SELECT oid FROM pg_database WHERE datname='source') ORDER BY setrole;
"""
            original = self.sql(facts,'source')
            restored = run([self.bin17/'psql','-XqAtw','--set=ON_ERROR_STOP=1'],env=dict(env,PGDATABASE='source'),input=facts.encode()).stdout
            self.assertEqual(restored, original)
            source_vectors = {name:self.sql(sql,'source') for name,sql in WHOLE_DR_FACTS_SQL.items()}
            restored_vectors = {name:run([self.bin17/'psql','-XqAtw','--set=ON_ERROR_STOP=1'],
                env=dict(env,PGDATABASE='source'),input=sql.encode()).stdout for name,sql in WHOLE_DR_FACTS_SQL.items()}
            dr.compare_catalogs(source_vectors, restored_vectors)
            auth_query = b"SELECT current_user; SELECT count(*) FROM dr_payload; SHOW statement_timeout; SHOW lock_timeout; SHOW timezone;"
            for base_env in [self.env, env]:
                login_env = dict(base_env,PGDATABASE='source',PGUSER='repair_login',PGPASSWORD='synthetic_DR_auth_only')
                authenticated = run([self.bin17/'psql','-XqAtw','--set=ON_ERROR_STOP=1'],env=login_env,input=auth_query)
                self.assertEqual(authenticated.stdout,b'repair_login\n1\n7s\n3s\nAsia/Tokyo\n')
                denied = run([self.bin17/'psql','-XqAtw','--set=ON_ERROR_STOP=1'],env=dict(login_env,PGPASSWORD='wrong_synthetic_only'),input=b'SELECT 1;',check=False)
                self.assertNotEqual(denied.returncode,0)
                write = run([self.bin17/'psql','-XqAtw','--set=ON_ERROR_STOP=1'],env=login_env,input=b"INSERT INTO dr_payload VALUES(2,'not allowed');",check=False)
                self.assertNotEqual(write.returncode,0)
        finally:
            if started:
                run([self.bin17/'pg_ctl','-D',data,'-m','immediate','-w','stop'],env=env)
            if not (data/'postmaster.pid').exists():
                shutil.rmtree(data,ignore_errors=True)
                shutil.rmtree(socket,ignore_errors=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--unit', action='store_true')
    args = parser.parse_args()
    suite = unittest.TestLoader().loadTestsFromTestCase(EvidenceUnitTests)
    if not args.unit:
        suite.addTests(unittest.TestLoader().loadTestsFromTestCase(PostgreSQLTests))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
