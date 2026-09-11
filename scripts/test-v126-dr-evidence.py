#!/usr/bin/env python3
"""Offline Policy B regression. Optional owned, network-none local PostgreSQL17.

No real secrets, remote endpoints, provider clients, V126 init/stages or fence.
"""
import argparse
import copy
from dataclasses import replace
from datetime import datetime, timezone
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('dr_evidence', ROOT / 'scripts/v126-dr-evidence.py')
dr = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = dr
spec.loader.exec_module(dr)
S = dr.schema
H = lambda text: hashlib.sha256(text.encode()).hexdigest()
UTC = lambda seconds: datetime.fromtimestamp(seconds, timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
DAY = S.timestamp('2026-09-11T00:00:00Z')
NOW = DAY + 28740


def clock(seconds, error=0, identity='source-clock', monotonic=None):
    return {'utc': UTC(seconds), 'error_seconds': error, 'clock_id': H(identity),
            'monotonic_seconds': seconds if monotonic is None else monotonic}


def record(kind, **fields):
    return {'schema_version': 1, 'kind': kind, **fields}


def archive_bytes(members):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, raw in sorted(members.items()):
            info = tarfile.TarInfo(name)
            info.size = len(raw)
            archive.addfile(info, io.BytesIO(raw))
    return output.getvalue()


def synthetic_members(snapshot):
    members = {name: dr.canonical({'schema_version': 1, 'category': name[:-5],
                'snapshot_token_sha256': snapshot, 'semantic_sha256': H(name), 'count': 1})
               for name in S.ARTIFACTS if name.endswith('.json')}
    members['application.dump'] = b'PGDMPsynthetic-format-only-not-a-real-dump'
    members['globals.sql'] = b'-- synthetic globals\nCREATE ROLE bootstrap;\nALTER ROLE bootstrap WITH SUPERUSER;\n'
    headers = ('dbname', 'TOC Entries', 'Compression', 'Dump Version', 'Format', 'Integer', 'Offset',
               'Dumped from database version', 'Dumped by pg_dump version')
    members['toc.list'] = (b';\n; Archive created at 2026-09-11 00:00:00 UTC\n'
                           + ''.join(';     '+x+': fixture\n' for x in headers).encode()
                           + b'; Selected TOC Entries:\n1; 0 0 TABLE public synthetic bootstrap\n')
    return members


class Fixture:
    def __init__(self, purpose='preparation', second_outcome='QUALIFIED', mutate=None, now=None):
        self.documents = {}
        self.events = []
        self.points = []
        self.auths = set()
        self.auth_by_purpose = {}
        self.observed = set()
        self.previous_result = None
        self.now = clock(now or NOW, identity='verifier-clock')
        self.mutate = mutate or (lambda kind, doc: None)
        target = record('target', protocol='s3-compatible', endpoint='https://objects.example.invalid',
                        bucket='synthetic-only', region='test-region', storage_class='STANDARD',
                        account_ref=H('account'), failure_domain_ref=H('storage-domain'),
                        versioning='ENABLED', object_lock='ENABLED', lock_mode='COMPLIANCE', retention_seconds=604800)
        target_ref = self.put(target)
        custody = record('custody', owner='user', independent_of_source=True, independent_of_backup_credentials=True,
                         versions={k: H('custody-'+k) for k in ('decryption', 'secrets', 'config', 'images', 'evidence')})
        self.custody_ref = self.put(custody)
        self.custody_versions = frozenset(custody['versions'].values())
        source = subprocess.check_output(['git', '--no-replace-objects', '-C', str(ROOT), 'rev-parse', 'HEAD', 'HEAD^{tree}']).decode().splitlines()
        runtime = dict(source_sha=source[0], source_tree=source[1], image_sha256=H('app-image'),
                       pg_image_sha256=H('pg-image'), migration_tree='a'*40, config_epoch=H('config-epoch'))
        self.binding = record('binding', policy_sha256=dr.digest(S.POLICY), source_sha=source[0], source_tree=source[1],
            tools=dr.tooling(ROOT), python_version=dr.platform.python_version(), target_sha256=target_ref,
            custody_sha256=self.custody_ref, source_identity_sha256=H('source-identity'),
            database_semantics_sha256=H('database'), data_runtime=runtime, restore_runtime=runtime,
            restore_recipe_sha256=dr.sha((ROOT/'docs/V126_DATABASE_RECOVERY_REHEARSAL.md').read_bytes()))
        self.binding_ref = self.put(self.binding)
        self.cutover = None
        self.native = None
        if purpose == 'cutover-Q':
            native_members=synthetic_members(H('snapshot-2'))
            native_artifacts={'operation-log':H('log'),'quiesced-backup-dump':dr.sha(native_members['application.dump']),
                              'quiesced-backup-inventory':dr.sha(native_members['toc.list']),
                              'quiesced-backup-proof':H('native-proof'),'quiesced-backup-rehearsal':H('native-rehearsal')}
            self.native = dr.canonical(dict(format_version=1, artifacts=[{'name':k,'sha256':v} for k,v in sorted(native_artifacts.items())],
                authorization_gate='A', authorization_receipt_sha256=H('native-auth'), completed_at=UTC(DAY+21630),
                intent_sha256=H('native-intent'), predecessor_receipt_sha256=H('stage6'),
                predecessor_stage='ZERO_WRITER_GATE_PASSED', release_sha=source[0], result_category='PASS',
                run_id='future-synthetic-run', script_sha256=next(x['sha256'] for x in self.binding['tools'] if x['path']=='scripts/v126-cutover.sh'),
                stage='QUIESCED_BACKUP_REHEARSED'))
            self.cutover = {'run_id': 'future-synthetic-run', 'stage7_sha256': dr.sha(self.native), 'native_manifest_sha256': H('native-manifest')}
            self.observed.update((self.cutover['stage7_sha256'], self.cutover['native_manifest_sha256']))
        self.add_point(1, DAY, 'product-periodic', 'QUALIFIED')
        self.add_point(2, DAY + 21600, purpose, second_outcome)
        self.ledger_ref = self.put(record('ledger', binding_sha256=self.binding_ref, events=self.events, head_sequence=len(self.events)))
        self.response = self.put(record('response',binding_sha256=self.binding_ref,
            authorization_sha256=self.auth_by_purpose['product-periodic'],owner='user',mode='synthetic',
            executor_sha256=dr.sha((ROOT/'scripts/v126-dr-evidence.py').read_bytes()),
            mechanism_evidence_sha256=H('writer-mechanism'),writers={k:True for k in S.WRITERS},
            trigger_age_seconds=79200,response_budget_seconds=300,auto_restore=False,auto_rollback=False,auto_reopen=False))
        self.observed.add(H('writer-mechanism'))
        self.ongoing_ref = self.put(record('ongoing', binding_sha256=self.binding_ref, ledger_sha256=self.ledger_ref,
            authorization_sha256=self.auth_by_purpose['product-periodic'],
            observed=self.now, last_monitor=self.now,
            last_snapshot_due=UTC((S.timestamp(self.now['utc'])//21600)*21600),
            response_authority_sha256=self.response, mechanism_sha256=H('mechanism'),
            completed_periodic_qualification_sha256=self.points[0]['qref'], failure_detection_test_sha256=H('failure-test'),
            post_v126_recipe_sha256=H('post-v126-recipe')))
        self.observed.update((H('mechanism'), H('failure-test'), H('post-v126-recipe')))
        self.trust = dr.Trust(self.binding_ref, self.ledger_ref, frozenset(self.auths), self.custody_versions,
                             frozenset(p['qref'] for p in self.points), frozenset(self.observed), frozenset({self.response}), self.now,
                             ongoing_sha256=self.ongoing_ref)
        self.evidence = dr.Evidence(self.documents)
        self.readiness_ref = None
        if second_outcome == 'QUALIFIED':
            p = self.points[-1]
            self.readiness_ref = self.put(record('readiness', binding_sha256=self.binding_ref, ledger_sha256=self.ledger_ref,
                authorization_sha256=self.auth_by_purpose[purpose],
                ongoing_sha256=self.ongoing_ref, qualification_sha256=p['qref'], purpose=purpose, checked=self.now,
                age_seconds=dr.age(p['manifest']['point'], self.now), deadline=UTC(DAY+21600+86400),
                cutover=self.cutover, result='SYNTHETIC_POINT_PASS'))
            self.evidence = dr.Evidence(self.documents)

    def put(self, doc):
        self.mutate(doc['kind'], doc)
        raw = dr.canonical(doc)
        reference = dr.sha(raw)
        self.documents[reference] = raw
        return reference

    def event(self, kind, docref):
        event = record('event', sequence=len(self.events)+1, previous_sha256=self.events[-1] if self.events else None,
                       document_kind=kind, document_sha256=docref)
        self.events.append(self.put(event))

    def add_point(self, sequence, point_time, purpose, outcome):
        auth = record('authorization', binding_sha256=self.binding_ref, actor='user', authorization_id='synthetic-ap01-'+str(sequence),
                      purpose=purpose, valid_from=UTC(DAY-10000), valid_until=UTC(DAY+172800), packages=['AP-01'], mode='synthetic')
        authref = self.put(auth)
        self.auths.add(authref)
        self.auth_by_purpose[purpose] = authref
        intent = record('intent', binding_sha256=self.binding_ref, authorization_sha256=authref, attempt_id='attempt-'+str(sequence),
                        sequence=sequence, purpose=purpose, started=clock(point_time-1), previous_result_sha256=self.previous_result,
                        cutover=self.cutover if purpose=='cutover-Q' else None)
        iref = self.put(intent)
        self.event('intent', iref)
        if outcome == 'PENDING':
            return
        if outcome in ('FAILED', 'UNKNOWN'):
            result = record('result', intent_sha256=iref, outcome=outcome, exit_code=73 if outcome=='FAILED' else None,
                completed=clock(point_time+60), manifest_sha256=None, archive=None, consumer_results_sha256=H('consumer'))
            self.previous_result = self.put(result)
            self.event('result', self.previous_result)
            return
        snapshot = H('snapshot-'+str(sequence))
        members = synthetic_members(snapshot)
        manifest = record('manifest', binding_sha256=self.binding_ref, intent_sha256=iref, point=clock(point_time),
            snapshot_token_sha256=snapshot, consistency='quiesced' if purpose=='cutover-Q' else 'exported-snapshot',
            admin_freeze_sha256=H('admin-freeze'), globals_capture_intent_sha256=iref,
            snapshot_consumers_sha256=H('snapshot-consumers'), artifacts={k: dr.file_identity(v) for k,v in members.items()})
        mref = self.put(manifest)
        archive = archive_bytes(members)
        result = record('result', intent_sha256=iref, outcome='SUCCESS', exit_code=0, completed=clock(point_time+60),
                        manifest_sha256=mref, archive=dr.file_identity(archive), consumer_results_sha256=H('consumer'))
        rref = self.put(result)
        self.previous_result = rref
        self.event('result', rref)
        cipher = ('opaque-fixture-token-'+str(sequence)).encode()
        off = record('offhost', result_sha256=rref, manifest_sha256=mref, target_sha256=self.binding['target_sha256'],
            transport_sha256=dr.sha((ROOT/'scripts/v126-dr-evidence.py').read_bytes()),
            key='bundles/'+mref+'.enc', version_id='fixture-version-'+str(sequence), ciphertext=dr.file_identity(cipher),
            encryption={'backend':'fixture-token-map', 'backend_sha256':dr.sha((ROOT/'scripts/v126-dr-evidence.py').read_bytes()), 'format_version':'1',
                        'custody_version_sha256':H('custody-decryption'), 'mode':'synthetic'},
            uploaded_at=UTC(point_time+180), read_at=UTC(point_time+300), retained_until=UTC(point_time+604860),
            writer_identity_sha256=H('writer'), reader_identity_sha256=H('reader'), reader_failure_domain_sha256=H('reader-domain'),
            readback=dr.file_identity(cipher), downloaded_archive=dr.file_identity(archive),
            decrypted_members_sha256=dr.digest(manifest['artifacts']), custody_sha256=self.custody_ref, exit_code=0)
        oref = self.put(off)
        restore = record('restore', offhost_sha256=oref, downloaded_archive=dr.file_identity(archive),
            target_identity_sha256=H('isolated-target'), empty_target_sha256=H('empty'), egress_proof_sha256=H('egress'),
            isolated_at=UTC(point_time+420), hydrated_at=UTC(point_time+540), started_at=UTC(point_time+660), completed_at=UTC(point_time+780),
            platform='linux/amd64', runtime=self.binding['restore_runtime'], recipe_sha256=self.binding['restore_recipe_sha256'],
            bootstrap_transform_sha256=H('bootstrap'), auth_scope='synthetic',
            checks={k:{'outcome':'PASS', 'evidence_sha256':H(k)} for k in S.RESTORE_CHECKS})
        sref = self.put(restore)
        functional = record('functional', restore_sha256=sref, completed_at=UTC(point_time+900), auth_scope='synthetic',
                            overlay=dict(S.SAFE_OVERLAY), checks={k:{'outcome':'PASS', 'evidence_sha256':H(k)} for k in S.FUNCTIONAL_CHECKS})
        fref = self.put(functional)
        qualification = record('qualification', binding_sha256=self.binding_ref, intent_sha256=iref, result_sha256=rref,
            manifest_sha256=mref, offhost_sha256=oref, restore_sha256=sref, functional_sha256=fref, qualified_at=UTC(point_time+1020),
            status='AVAILABLE_AND_FUNCTIONALLY_VERIFIED')
        qref = self.put(qualification)
        self.event('qualification', qref)
        self.observed.update([oref, H('admin-freeze'), H('snapshot-consumers'), H('consumer'), H('empty'), H('egress'), H('bootstrap'),
                              *[H(k) for k in S.RESTORE_CHECKS + S.FUNCTIONAL_CHECKS]])
        self.points.append(dict(qref=qref, manifest=manifest, members=members, archive=archive, off=off, cipher=cipher, intent=intent))

    def barrier(self, **kwargs):
        return dr.consume_barrier(self.evidence, self.trust, self.readiness_ref, self.ongoing_ref, self.now,
            purpose=self.points[-1]['intent']['purpose'], requested_attempt='attempt-2', **kwargs)


class PolicyTests(unittest.TestCase):
    def test_complete_synthetic_chain_r0(self):
        f = Fixture()
        self.assertEqual(f.barrier()['barrier'], 'R0')
        self.assertFalse(f.barrier()['operational'])
        self.assertTrue(dr.evaluate_ongoing(f.evidence, f.trust, f.ongoing_ref, f.now)['ready'])

    def test_closed_schemas_all_documents_and_nested_keys(self):
        f = Fixture()
        for raw in f.documents.values():
            doc = dr.parse(raw)
            for bad in [b'{"schema_version":1,' + raw[1:], dr.canonical(dict(doc, unknown=True)),
                        dr.canonical({k:v for k,v in doc.items() if k!='schema_version'}), raw[:-1],
                        raw.replace(b'"schema_version":1', b'"schema_version":true')]:
                with self.subTest(kind=doc['kind']), self.assertRaises(ValueError):
                    dr.parse(bad)
        manifest = dict(f.points[0]['manifest'])
        manifest['point'] = dict(manifest['point'], token='unapproved')
        with self.assertRaises(ValueError):
            dr.parse(dr.canonical(manifest))

    def test_f3_timestamp_rejects_unicode_digits_in_every_numeric_position(self):
        f = Fixture()
        ascii_time = f.now['utc']
        cases = []
        for name, glyphs in (('fullwidth', '０１２３４５６７８９'), ('arabic-indic', '٠١٢٣٤٥٦٧٨٩')):
            mapping = str.maketrans('0123456789', glyphs)
            cases.append((name+'-year', ascii_time[:4].translate(mapping)+ascii_time[4:]))
            for position, digit in enumerate(ascii_time):
                if '0' <= digit <= '9':
                    cases.append((name+'-mixed-position-'+str(position),
                                  ascii_time[:position]+digit.translate(mapping)+ascii_time[position+1:]))
        cases.extend((('fullwidth-hyphen', ascii_time.replace('-', '－', 1)),
                      ('fullwidth-colon', ascii_time.replace(':', '：', 1))))
        for name, value in cases:
            with self.subTest(case=name):
                doc = f.evidence.get(f.ongoing_ref, 'ongoing')
                doc['observed']['utc'] = value
                with self.assertRaisesRegex(ValueError, '^DR_SCHEMA_INVALID$'):
                    S.timestamp(value)
                with self.assertRaisesRegex(ValueError, '^DR_SCHEMA_INVALID$'):
                    S.validate(doc, 'ongoing')
                with self.assertRaisesRegex(ValueError, '^DR_DOCUMENT_INVALID$'):
                    dr.parse(dr.canonical(doc), 'ongoing')

    def test_f3_ascii_timestamp_grammar_and_canonical_round_trip_unchanged(self):
        f = Fixture()
        self.assertEqual(S.VERSION, 1)
        for value, epoch in (('2026-09-11T07:59:00Z', 1789113540),
                             ('0001-01-01T00:00:00Z', -62135596800),
                             ('9999-12-31T23:59:59Z', 253402300799),
                             ('1969-12-31T23:59:59Z', -1),
                             ('1970-01-01T00:00:00Z', 0),
                             ('2000-02-29T00:00:00Z', 951782400)):
            with self.subTest(value=value):
                self.assertEqual(S.timestamp(value), epoch)
                doc = f.evidence.get(f.ongoing_ref, 'ongoing')
                doc['observed']['utc'] = value
                raw = dr.canonical(doc)
                parsed = dr.parse(raw, 'ongoing')
                self.assertEqual(parsed, doc)
                self.assertEqual(dr.canonical(parsed), raw)
                self.assertEqual(dr.digest(parsed), dr.sha(raw))
        # The existing grammar is whole seconds with uppercase T/Z, no offsets
        # or fractional component; calendar validation remains datetime's job.
        for value in ('0000-01-01T00:00:00Z', '10000-01-01T00:00:00Z', '1900-02-29T00:00:00Z',
                      '2026-02-30T00:00:00Z', '2026-09-11T24:00:00Z', '2026-09-11T23:59:60Z',
                      '2026-9-11T07:59:00Z', '2026-09-11t07:59:00z', '2026-09-11T07:59:00+00:00',
                      '2026-09-11T07:59:00.0Z', '2026-09-11T07:59:00.٠Z',
                      '2026/09/11T07:59:00Z', '2026-09-11T07:59:00Z\x00'):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, '^DR_SCHEMA_INVALID$'):
                S.timestamp(value)

    def test_f3_rehashed_unicode_timestamp_rejected_by_parser_cli_and_consumers(self):
        with tempfile.TemporaryDirectory(prefix='ht-ops-04-f3-cli-') as directory:
            path = Path(directory).resolve()/'ongoing.json'
            for purpose in ('preparation', 'cutover-Q'):
                f = Fixture(purpose=purpose)
                kwargs = {'cutover': f.cutover, 'native_stage7': f.native} if purpose == 'cutover-Q' else {}
                self.assertTrue(dr.evaluate_ongoing(f.evidence, f.trust, f.ongoing_ref, f.now)['ready'])
                self.assertFalse(f.barrier(**kwargs)['operational'])
                path.write_bytes(f.evidence.raw(f.ongoing_ref))
                result = subprocess.run([sys.executable, str(ROOT/'scripts/v126-dr-evidence.py'),
                                         'validate-document', str(path)], capture_output=True)
                self.assertEqual(result.returncode, 0)
                self.assertEqual(result.stdout, b'SCHEMA_VALID_ONLY_NOT_DR_PASS\n')
                original_trust = copy.deepcopy(f.trust)
                predecessors = dict(f.evidence.documents)
                for year in ('２０２６', '٢٠٢٦', '2٠26'):
                    with self.subTest(purpose=purpose, year=year):
                        observed = dict(f.now, utc=year+f.now['utc'][4:])
                        evidence, ongoing_ref, readiness_ref = self.rewrite_ongoing(f, observed=observed)
                        trust = replace(f.trust, ongoing_sha256=ongoing_ref)
                        self.assertEqual(replace(trust, ongoing_sha256=f.ongoing_ref), original_trust)
                        self.assertEqual(f.trust, original_trust)
                        self.assertTrue(all(evidence.documents[k] == v for k,v in predecessors.items()))
                        raw = evidence.raw(ongoing_ref)
                        with self.assertRaisesRegex(ValueError, '^DR_DOCUMENT_INVALID$'):
                            dr.parse(raw, 'ongoing')
                        path.write_bytes(raw)
                        result = subprocess.run([sys.executable, str(ROOT/'scripts/v126-dr-evidence.py'),
                                                 'validate-document', str(path)], capture_output=True)
                        self.assertNotEqual(result.returncode, 0)
                        self.assertEqual(result.stdout, b'')
                        self.assertEqual(result.stderr, b'DR document refused; values are not logged\n')
                        evaluated = dr.evaluate_ongoing(evidence, trust, ongoing_ref, f.now)
                        self.assertEqual(evaluated['state'], 'RPO_NOT_PROVABLE')
                        self.assertFalse(evaluated['ready'])
                        with self.assertRaisesRegex(ValueError, '^ONGOING_NOT_READY$'):
                            dr.consume_barrier(evidence, trust, readiness_ref, ongoing_ref, f.now,
                                               purpose=purpose, requested_attempt='attempt-2', **kwargs)

    def test_exact_24h_and_dispatch_budget(self):
        p = clock(DAY)
        self.assertEqual(dr.fresh(p, clock(DAY+86400)), 86400)
        with self.assertRaisesRegex(ValueError, 'RPO_VIOLATION'):
            dr.fresh(p, clock(DAY+86401))
        with self.assertRaisesRegex(ValueError, 'BUDGET'):
            dr.fresh(p, clock(DAY+86400), 0)
        self.assertEqual(dr.fresh(p, clock(DAY+86000), 100), 86000)

    def test_conservative_uncertainty(self):
        self.assertEqual(dr.fresh(clock(DAY,60), clock(DAY+86280,60)), 86400)
        with self.assertRaises(ValueError):
            dr.fresh(clock(DAY,60), clock(DAY+86281,60))
        with self.assertRaises(ValueError):
            dr.fresh(clock(DAY,61), clock(DAY+1000))

    def test_rollback_future_and_ambiguous_clock(self):
        cases = [(clock(DAY), clock(DAY-1)), (clock(DAY), clock(DAY+1, monotonic=DAY-1)),
                 (clock(DAY), clock(DAY+100, monotonic=DAY+1)),
                 (clock(DAY,60), clock(DAY+100,60,identity='other'))]
        for p,n in cases:
            with self.subTest(p=p,n=n), self.assertRaises(ValueError):
                dr.fresh(p,n)

    def test_new_failed_pending_unknown_do_not_fallback(self):
        for outcome in ('FAILED','PENDING','UNKNOWN'):
            f = Fixture(second_outcome=outcome)
            with self.subTest(outcome=outcome), self.assertRaisesRegex(ValueError, 'LATEST_ATTEMPT_'+outcome):
                dr.select_point(f.evidence, f.trust, f.now)
            self.assertEqual(dr.evaluate_ongoing(f.evidence,f.trust,f.ongoing_ref,f.now)['state'], 'RPO_NOT_PROVABLE')

    def test_incomplete_ledger_and_forged_head(self):
        f = Fixture()
        for ref in (f.events[0], f.events[-1], f.ledger_ref):
            docs = dict(f.documents)
            del docs[ref]
            with self.assertRaises(ValueError):
                dr.select_point(dr.Evidence(docs),f.trust,f.now)
        bad = replace(f.trust,ledger_sha256=f.events[-1])
        with self.assertRaises(ValueError):
            dr.select_point(f.evidence,bad,f.now)

    def test_replayed_duplicate_and_reordered_ledger(self):
        for change in (lambda d:d['events'].reverse(), lambda d:d['events'].append(d['events'][0]),
                       lambda d:d.update(head_sequence=999)):
            def mutate(kind,doc):
                if kind=='ledger': change(doc)
            f=Fixture(mutate=mutate)
            with self.assertRaises(ValueError): dr.select_point(f.evidence,f.trust,f.now)

    def test_changed_source_tool_schema_or_runtime_binding(self):
        mutations = [lambda d:d.update(source_sha='f'*40), lambda d:d.update(source_tree='f'*40),
                     lambda d:d['tools'][0].update(sha256=H('changed')),
                     lambda d:d.update(tools=d['tools'][:-1]), lambda d:d.update(python_version='0.0.0'),
                     lambda d:d.update(restore_runtime=dict(d['restore_runtime'],image_sha256=H('other')))]
        for change in mutations:
            def mutate(kind,doc):
                if kind=='binding': change(doc)
            f=Fixture(mutate=mutate)
            with self.assertRaises(ValueError): dr.select_point(f.evidence,f.trust,f.now)
        f=Fixture()
        with mock.patch.object(dr, 'tooling', return_value=[]), self.assertRaises(ValueError):
            dr.select_point(f.evidence,f.trust,f.now)

    def test_wrong_predecessors_consistency_fresh_globals(self):
        changes = [('intent','binding_sha256',H('other')), ('manifest','globals_capture_intent_sha256',H('old-globals')),
                   ('manifest','consistency','quiesced'), ('offhost','result_sha256',H('other')),
                   ('restore','offhost_sha256',H('other')), ('functional','restore_sha256',H('other')),
                   ('qualification','manifest_sha256',H('other')), ('result','exit_code',73)]
        for kind,key,value in changes:
            f=Fixture(mutate=lambda k,d: d.update({key:value}) if k==kind else None)
            with self.subTest(kind=kind,key=key), self.assertRaises(ValueError):
                dr.select_point(f.evidence,f.trust,f.now)

    def test_unavailable_custody_bytes_or_independent_observation(self):
        f=Fixture()
        for trust in (replace(f.trust,custody_versions=frozenset()),
                      replace(f.trust,available_qualifications=frozenset({f.points[0]['qref']})),
                      replace(f.trust,observed_evidence=frozenset()),replace(f.trust,authorization_sha256=frozenset())):
            with self.assertRaises(ValueError): dr.select_point(f.evidence,trust,f.now)
        with self.assertRaisesRegex(ValueError,'CHECKPOINT_STALE'):
            dr.select_point(f.evidence,replace(f.trust,observed=clock(NOW-301,identity='verifier-clock')),f.now)

    def test_credentials_and_tokens_rejected_in_target(self):
        f=Fixture()
        target=f.evidence.get(f.binding['target_sha256'],'target')
        for bad in ('https://user:canary@example.invalid', 'https://example.invalid?token=canary',
                    'https://example.invalid/#canary','s3://bucket', 'https://example.invalid/%63anary'):
            with self.assertRaises(ValueError): dr.parse(dr.canonical(dict(target,endpoint=bad)))
        for field in ('credentials','query','access_key','secret_key'):
            with self.assertRaises(ValueError): dr.parse(dr.canonical(dict(target,**{field:'canary'})))

    def test_independent_exact_version_readback_and_fake_crypto(self):
        f=Fixture(); p=f.points[-1]; off=p['off']; transport=dr.FakeS3()
        transport.put_fixture(off['target_sha256'],off['key'],off['version_id'],p['cipher'])
        crypto=dr.FakeEncryption(p['cipher'],p['archive'])
        actual=dr.independent_readback(transport,crypto,off,p['manifest'],1024*1024)
        self.assertEqual(actual,p['members'])
        for changed in (dict(off,version_id='wrong-generation'), dict(off,readback={'sha256':H('wrong'),'size':1}),
                        dict(off,ciphertext={'sha256':H('wrong'),'size':1}),dict(off,downloaded_archive={'sha256':H('wrong'),'size':1})):
            with self.assertRaises(ValueError): dr.independent_readback(transport,crypto,changed,p['manifest'],1024*1024)
        with self.assertRaises(ValueError):
            transport.put_fixture(off['target_sha256'],off['key'],off['version_id'],p['cipher'])
        with self.assertRaises(ValueError): crypto.decrypt(p['cipher'],dict(off['encryption'],mode='client-side'))

    def test_receipt_cannot_assert_independence_or_extend_age(self):
        for kind, key, value in [('offhost','reader_identity_sha256',H('writer')),
                                  ('offhost','reader_failure_domain_sha256',H('source-identity')),
                                  ('offhost','read_at',UTC(DAY-1)), ('offhost','retained_until',UTC(DAY+86400)),
                                  ('restore','target_identity_sha256',H('source-identity')),
                                  ('restore','hydrated_at',UTC(DAY)),('functional','auth_scope','real-custody')]:
            f=Fixture(mutate=lambda k,d: d.update({key:value}) if k==kind else None)
            with self.subTest(kind=kind,key=key), self.assertRaises(ValueError): dr.select_point(f.evidence,f.trust,f.now)

    def test_qualification_budget_and_future_proof(self):
        f=Fixture(mutate=lambda k,d: d.update(qualified_at=UTC(DAY+21600+7300)) if k=='qualification' else None)
        with self.assertRaises(ValueError): dr.select_point(f.evidence,f.trust,f.now)

    def test_qualification_exact_budget_with_uncertainty(self):
        for delta,passed in ((6120,True),(6121,False)):
            def mutate(kind,doc):
                if kind=='qualification':
                    doc['qualified_at']=UTC(S.timestamp(doc['qualified_at'])+delta)
            f=Fixture(mutate=mutate,now=DAY+30000)
            if passed:
                self.assertEqual(dr.select_point(f.evidence,f.trust,f.now)['sequence'],2)
            else:
                with self.assertRaisesRegex(ValueError,'QUALIFICATION_BUDGET'):
                    dr.select_point(f.evidence,f.trust,f.now)

    def test_new_qualified_attempt_after_known_failure_preserves_failure(self):
        f=Fixture(second_outcome='FAILED')
        failed=f.previous_result
        original=f.documents[failed]
        f.add_point(3,DAY+25000,'preparation','QUALIFIED')
        ref=f.put(record('ledger',binding_sha256=f.binding_ref,events=f.events,head_sequence=len(f.events)))
        trust=replace(f.trust,ledger_sha256=ref,authorization_sha256=frozenset(f.auths),
                      observed_evidence=frozenset(f.observed),available_qualifications=frozenset(p['qref'] for p in f.points))
        selected=dr.select_point(dr.Evidence(f.documents),trust,f.now,'preparation','attempt-3')
        self.assertEqual(selected['sequence'],3)
        self.assertEqual(f.documents[failed],original)
        self.assertEqual(dr.parse(original)['outcome'],'FAILED')

    def test_latest_producer_success_without_qualification(self):
        def mutate(kind,doc):
            if kind=='ledger':
                doc['events']=doc['events'][:-1]
                doc['head_sequence']-=1
        f=Fixture(mutate=mutate)
        with self.assertRaisesRegex(ValueError,'LATEST_ATTEMPT_SUCCESS'):
            dr.select_point(f.evidence,f.trust,f.now)

    def test_real_retention_minimum_two_and_consumer_authority(self):
        def mutate(kind,doc):
            if kind=='ledger':
                doc['events']=doc['events'][:3]
                doc['head_sequence']=3
        f=Fixture(mutate=mutate)
        result=dr.evaluate_ongoing(f.evidence,f.trust,f.ongoing_ref,f.now)
        self.assertIn('RETENTION_OR_PERIODIC_PROOF_MISSING',result['issues'])
        self.assertFalse(result['ready'])
        for kind in ('readiness','ongoing'):
            f=Fixture(mutate=lambda k,d: d.update(authorization_sha256=H('unapproved')) if k==kind else None)
            with self.assertRaises(ValueError): f.barrier()

    def test_s3_reserved_version_and_tool_backend_mismatch(self):
        for version in ('null','latest','unknown','NONE'):
            f=Fixture(mutate=lambda k,d: d.update(version_id=version) if k=='offhost' else None)
            with self.assertRaises(ValueError): dr.select_point(f.evidence,f.trust,f.now)
        f=Fixture(mutate=lambda k,d: d.update(transport_sha256=H('unreviewed')) if k=='offhost' else None)
        with self.assertRaises(ValueError): dr.select_point(f.evidence,f.trust,f.now)

    def test_q_stage7_must_bind_the_dump_and_inventory_bytes(self):
        f=Fixture(purpose='cutover-Q')
        native=json.loads(f.native)
        native['artifacts'][1]['sha256']=H('another-dump')
        raw=dr.canonical(native)
        altered=dict(f.cutover,stage7_sha256=dr.sha(raw))
        # Rebind every DR cutover reference plus independently pin the alternate
        # native record: byte-to-manifest comparison must still detect the swap.
        def mutate(kind,doc):
            if kind in ('intent','readiness') and doc['cutover'] is not None:
                doc['cutover']=altered
        f=Fixture(purpose='cutover-Q',mutate=mutate)
        f.trust=replace(f.trust,observed_evidence=f.trust.observed_evidence|frozenset({dr.sha(raw)}))
        with self.assertRaisesRegex(ValueError,'Q_NATIVE_BACKUP_BYTES'):
            f.barrier(cutover=altered,native_stage7=raw)

    def test_synthetic_never_operational_pass(self):
        f=Fixture()
        with self.assertRaises(ValueError): dr.select_point(f.evidence,replace(f.trust,operational=True),f.now)
        proof=f.evidence.get(f.readiness_ref,'readiness')
        forged=dr.canonical(dict(proof,result='DR_POINT_PASS'))
        f.evidence.documents[dr.sha(forged)]=forged
        with self.assertRaises(ValueError):
            dr.consume_barrier(f.evidence,f.trust,dr.sha(forged),f.ongoing_ref,f.now,purpose='preparation',requested_attempt='attempt-2')

    def test_artifact_missing_corrupt_zero_truncated_size_hash(self):
        f=Fixture(); p=f.points[-1]
        for name in S.ARTIFACTS:
            for replacement in (None,b'',p['members'][name][:-1],p['members'][name]+b'!'):
                members=dict(p['members'])
                if replacement is None: del members[name]
                else: members[name]=replacement
                with self.subTest(name=name), self.assertRaises(ValueError): dr.validate_bundle(members,p['manifest'])
        docs=dict(f.documents); docs[f.points[-1]['qref']]=b'{}\n'
        with self.assertRaises(ValueError): dr.select_point(dr.Evidence(docs),f.trust,f.now)

    def test_bundle_symlink_parent_hardlink_fifo_and_extra(self):
        f=Fixture(); p=f.points[-1]
        with tempfile.TemporaryDirectory(prefix='ht-ops-04-') as directory:
            root=Path(directory).resolve()
            for name,raw in p['members'].items(): (root/name).write_bytes(raw)
            dr.validate_directory(root,p['manifest'],1024*1024)
            target=root/'application.dump'; original=target.read_bytes(); target.unlink()
            for kind in ('symlink','fifo','hardlink'):
                backing=root/'backing'; backing.write_bytes(original)
                if kind=='symlink': target.symlink_to(backing)
                elif kind=='fifo': os.mkfifo(target)
                else: os.link(backing,target)
                with self.assertRaises((ValueError,OSError)): dr.read_regular(target,1024*1024)
                target.unlink(); backing.unlink()
            target.write_bytes(original)
            alias=root/'alias'; alias.symlink_to(root,target_is_directory=True)
            with self.assertRaises((ValueError,OSError)): dr.read_regular(alias/'application.dump',1024*1024)
            with self.assertRaises(ValueError): dr.validate_directory(root,p['manifest'],1024*1024)

    def test_archive_traversal_links_special_duplicates_and_truncation(self):
        f=Fixture(); p=f.points[-1]
        for name,kind in (('../escape',tarfile.REGTYPE),('/absolute',tarfile.REGTYPE),
                          ('application.dump',tarfile.SYMTYPE),('application.dump',tarfile.LNKTYPE),
                          ('application.dump',tarfile.FIFOTYPE),('application.dump',tarfile.CHRTYPE),
                          ('unexpected',tarfile.REGTYPE),('application.dump',tarfile.REGTYPE)):
            stream=io.BytesIO()
            with tarfile.open(fileobj=stream,mode='w') as ar:
                for n,b in p['members'].items():
                    info=tarfile.TarInfo(n); info.size=len(b); ar.addfile(info,io.BytesIO(b))
                extra=tarfile.TarInfo(name); extra.type=kind; extra.linkname='application.dump' if kind in (tarfile.SYMTYPE,tarfile.LNKTYPE) else ''
                ar.addfile(extra,io.BytesIO())
            with self.subTest(name=name,kind=kind), self.assertRaises(ValueError):
                dr.validate_archive(stream.getvalue(),p['manifest'],1024*1024)
        for bad in (p['archive'][:100],p['archive'][:2048],p['archive'][:-1],p['archive']+b'!'*512):
            with self.assertRaises(ValueError): dr.validate_archive(bad,p['manifest'],1024*1024)

    def test_bootstrap_exact_exception_and_original_immutable(self):
        raw=b'CREATE ROLE bootstrap;\nALTER ROLE bootstrap WITH SUPERUSER;\nCREATE ROLE reader;\n'
        before=dr.sha(raw)
        modified,proof=dr.bootstrap_globals(raw,'bootstrap',['bootstrap'],['postgres','template0','template1'])
        self.assertEqual(modified,b'ALTER ROLE bootstrap WITH SUPERUSER;\nCREATE ROLE reader;\n')
        self.assertEqual(dr.sha(raw),before)
        self.assertEqual(proof['original_sha256'],before)
        for roles,dbs in [(['bootstrap','other'],['postgres','template0','template1']),(['bootstrap'],['postgres','source'])]:
            with self.assertRaises(ValueError): dr.bootstrap_globals(raw,'bootstrap',roles,dbs)
        for bad in (raw+raw, raw+b"ALTER ROLE reader PASSWORD 'synthetic-canary';\n",
                    raw+b"ALTER ROLE reader SET app.access_key TO 'synthetic-canary';\n",raw[:-1]):
            with self.assertRaises(ValueError): dr.bootstrap_globals(bad,'bootstrap',['bootstrap'],['postgres','template0','template1'])

    def test_catalog_vectors_must_all_match(self):
        original={k:(k+'\n').encode() for k in S.RESTORE_CHECKS if k!='RESTORE_COMPLETE'}
        dr.compare_catalogs(original,dict(original))
        for name in original:
            with self.assertRaises(ValueError): dr.compare_catalogs(original,dict(original,**{name:b'changed'}))
            with self.assertRaises(ValueError): dr.compare_catalogs(original,{k:v for k,v in original.items() if k!=name})

    def test_r0_is_not_q_and_q_wrong_run_or_stage7(self):
        r0=Fixture(); q=Fixture(purpose='cutover-Q')
        with self.assertRaises(ValueError): r0.barrier(cutover=q.cutover,native_stage7=q.native)
        self.assertEqual(q.barrier(cutover=q.cutover,native_stage7=q.native)['barrier'],'Q')
        for cutover,native in [(None,None),(dict(q.cutover,run_id='other-run'),q.native),
                               (q.cutover,q.native+b' '),(dict(q.cutover,stage7_sha256=H('old-stage7')),q.native)]:
            with self.assertRaises(ValueError): q.barrier(cutover=cutover,native_stage7=native)
        with self.assertRaises(ValueError):
            dr.consume_barrier(q.evidence,q.trust,q.readiness_ref,q.ongoing_ref,q.now,purpose='preparation',requested_attempt='attempt-2')

    def test_old_schema_receipt_is_not_dr_proof_or_native_reconciliation(self):
        f=Fixture(purpose='cutover-Q'); before=dr.sha(f.native)
        with self.assertRaises(ValueError): dr.parse(f.native,'readiness')
        native=json.loads(f.native); native['format_version']=2
        with self.assertRaises(ValueError): dr.validate_native_stage7(dr.canonical(native),f.cutover,f.binding_ref,f.evidence)
        self.assertEqual(dr.sha(f.native),before)

    def test_readiness_forged_age_stale_or_wrong_attempt(self):
        for key,value in [('age_seconds',0),('deadline',UTC(DAY+999999))]:
            f=Fixture(mutate=lambda k,d: d.update({key:value}) if k=='readiness' else None)
            with self.assertRaises(ValueError): f.barrier()
        f=Fixture()
        with self.assertRaises(ValueError): dr.select_point(f.evidence,f.trust,clock(DAY+21600+86401,identity='verifier-clock'))
        with self.assertRaises(ValueError): dr.select_point(f.evidence,f.trust,f.now,'preparation','attempt-1')

    def test_thresholds_including_exact24h(self):
        for seconds,state in [(43199,'HEALTHY'),(43200,'WARNING'),(71999,'WARNING'),(72000,'CRITICAL'),
                              (79199,'CRITICAL'),(79200,'FENCE_REQUIRED'),(86400,'FENCE_REQUIRED'),(86401,'RPO_VIOLATION')]:
            f=Fixture(now=DAY+21600+seconds)
            result=dr.evaluate_ongoing(f.evidence,f.trust,f.ongoing_ref,f.now)
            # A missed cadence makes a young point non-ready even before warning.
            self.assertEqual(result['age_state'], state)
            self.assertEqual(result['state'], 'RPO_NOT_PROVABLE' if seconds==43199 else state)
            self.assertFalse(result['ready'])

    def test_cadence_monitor_authority_retention(self):
        f=Fixture()
        result=dr.evaluate_ongoing(f.evidence,replace(f.trust,response_authorities=frozenset()),f.ongoing_ref,f.now)
        self.assertEqual(result['state'],'RESPONSE_AUTHORITY_MISSING')
        self.assertFalse(result['ready'])
        for last_age in (301,601):
            f=Fixture(mutate=lambda k,d: d.update(last_monitor=clock(NOW-last_age,identity='verifier-clock')) if k=='ongoing' else None)
            self.assertFalse(dr.evaluate_ongoing(f.evidence,f.trust,f.ongoing_ref,f.now)['ready'])
        f=Fixture()
        # A retained ledger includes every qualified point; no removal of the first
        # can be hidden behind a newer success and an arbitrary "complete" flag.
        self.assertFalse(dr.evaluate_ongoing(f.evidence,replace(f.trust,available_qualifications=frozenset({f.points[-1]['qref']})),f.ongoing_ref,f.now)['ready'])
        _,_,points=dr.replay_ledger(f.evidence,f.trust,f.now)
        self.assertEqual(dr.retention_required(points,clock(DAY+10*86400,identity='verifier-clock')),set(p['qref'] for p in f.points))

    @staticmethod
    def rewrite_ongoing(f, **updates):
        # Add only rewritten ongoing/readiness bytes; preserve all predecessors.
        ongoing = f.evidence.get(f.ongoing_ref, 'ongoing')
        ongoing.update(copy.deepcopy(updates))
        ongoing_raw = dr.canonical(ongoing)
        ongoing_ref = dr.sha(ongoing_raw)
        readiness = f.evidence.get(f.readiness_ref, 'readiness')
        readiness['ongoing_sha256'] = ongoing_ref
        readiness_raw = dr.canonical(readiness)
        readiness_ref = dr.sha(readiness_raw)
        documents = dict(f.evidence.documents)
        documents.update({ongoing_ref: ongoing_raw, readiness_ref: readiness_raw})
        return dr.Evidence(documents), ongoing_ref, readiness_ref

    def assert_ongoing_not_ready(self, f, evidence, trust, ongoing_ref, readiness_ref):
        result = dr.evaluate_ongoing(evidence, trust, ongoing_ref, f.now)
        self.assertNotEqual(result['state'], 'HEALTHY')
        self.assertFalse(result['ready'])
        with self.assertRaisesRegex(ValueError, 'ONGOING_NOT_READY'):
            dr.consume_barrier(evidence, trust, readiness_ref, ongoing_ref, f.now,
                               purpose='preparation', requested_attempt='attempt-2')

    def test_ongoing_rehash_cannot_refresh_monitor_with_unchanged_trust(self):
        # HT-OPS-05 F1: stale observation + fresh ledger, custody and Trust.
        stale = clock(NOW-601, identity='verifier-clock')
        f = Fixture(mutate=lambda k,d: d.update(observed=stale, last_monitor=stale) if k=='ongoing' else None)
        trusted = f.trust
        trusted_contents = copy.deepcopy(trusted)
        original_documents = dict(f.evidence.documents)
        self.assertEqual(dr.evaluate_ongoing(f.evidence, trusted, f.ongoing_ref, f.now)['state'], 'RPO_NOT_PROVABLE')
        self.assert_ongoing_not_ready(f, f.evidence, trusted, f.ongoing_ref, f.readiness_ref)
        for fields in (('observed',), ('last_monitor',), ('observed', 'last_monitor')):
            with self.subTest(fields=fields):
                evidence, ongoing_ref, readiness_ref = self.rewrite_ongoing(f, **{k: f.now for k in fields})
                self.assertIs(trusted, f.trust)
                self.assertEqual(trusted, trusted_contents)
                self.assertEqual(f.evidence.documents, original_documents)
                self.assertTrue(all(evidence.documents[k] == v for k,v in original_documents.items()))
                self.assertNotIn(ongoing_ref, trusted.observed_evidence)
                self.assertNotIn(readiness_ref, trusted.observed_evidence)
                self.assert_ongoing_not_ready(f, evidence, trusted, ongoing_ref, readiness_ref)

    def test_ongoing_fresh_independent_observation_can_restore_readiness(self):
        stale = clock(NOW-601, identity='verifier-clock')
        f = Fixture(mutate=lambda k,d: d.update(observed=stale, last_monitor=stale) if k=='ongoing' else None)
        evidence, ongoing_ref, readiness_ref = self.rewrite_ongoing(f, observed=f.now, last_monitor=f.now)
        self.assert_ongoing_not_ready(f, evidence, f.trust, ongoing_ref, readiness_ref)
        # Synthetic caller acquisition: a new independent observation, not a
        # value derived by the evaluator from the submitted readiness receipt.
        fresh_trust = replace(f.trust, ongoing_sha256=ongoing_ref)
        self.assertIsNot(fresh_trust, f.trust)
        self.assertEqual(replace(fresh_trust, ongoing_sha256=f.trust.ongoing_sha256), f.trust)
        result = dr.evaluate_ongoing(evidence, fresh_trust, ongoing_ref, f.now)
        self.assertEqual(result['state'], 'HEALTHY')
        self.assertTrue(result['ready'])
        barrier = dr.consume_barrier(evidence, fresh_trust, readiness_ref, ongoing_ref, f.now,
                                     purpose='preparation', requested_attempt='attempt-2')
        self.assertEqual(barrier['barrier'], 'R0')
        self.assertFalse(barrier['operational'])

    def test_ongoing_requires_exact_independent_observation(self):
        f = Fixture()
        # Generic historical observations are not the current ongoing pin.
        for pin in (None, '', True, H('another-ongoing-observation')):
            with self.subTest(pin=pin):
                trust = replace(f.trust, ongoing_sha256=pin,
                                observed_evidence=f.trust.observed_evidence | {f.ongoing_ref})
                self.assert_ongoing_not_ready(f, f.evidence, trust, f.ongoing_ref, f.readiness_ref)
        # Even an independently known mechanism cannot be substituted into the
        # current observation without a new caller-owned pin of the whole record.
        for field, value in (('mechanism_sha256', H('failure-test')),
                             ('ledger_sha256', H('another-ledger')),
                             ('authorization_sha256', f.auth_by_purpose['preparation'])):
            with self.subTest(field=field):
                evidence, ongoing_ref, readiness_ref = self.rewrite_ongoing(f, **{field: value})
                self.assert_ongoing_not_ready(f, evidence, f.trust, ongoing_ref, readiness_ref)

    def test_ongoing_pin_does_not_renew_independent_checkpoint(self):
        stale = clock(NOW-601, identity='verifier-clock')
        f = Fixture(mutate=lambda k,d: d.update(observed=stale, last_monitor=stale) if k=='ongoing' else None)
        # A current independent checkpoint cannot freshen its stale ongoing pin.
        self.assertEqual(f.trust.observed, f.now)
        self.assertEqual(f.trust.ongoing_sha256, f.ongoing_ref)
        self.assert_ongoing_not_ready(f, f.evidence, f.trust, f.ongoing_ref, f.readiness_ref)
        evidence, ongoing_ref, readiness_ref = self.rewrite_ongoing(f, observed=f.now, last_monitor=f.now)
        for checkpoint_age in (300, 301):
            with self.subTest(checkpoint_age=checkpoint_age):
                trust = replace(f.trust, ongoing_sha256=ongoing_ref,
                                observed=clock(NOW-checkpoint_age, identity='verifier-clock'))
                result = dr.evaluate_ongoing(evidence, trust, ongoing_ref, f.now)
                if checkpoint_age == 300:
                    self.assertEqual(result['state'], 'HEALTHY')
                    self.assertTrue(result['ready'])
                else:
                    self.assertEqual(result['state'], 'RPO_NOT_PROVABLE')
                    self.assertFalse(result['ready'])
                    with self.assertRaisesRegex(ValueError, 'INDEPENDENT_CHECKPOINT_STALE'):
                        dr.consume_barrier(evidence, trust, readiness_ref, ongoing_ref, f.now,
                                           purpose='preparation', requested_attempt='attempt-2')

    def test_f2_monitor_boundaries_preserve_independent_facts(self):
        for point_age, severity in ((7140, 'HEALTHY'), (43200, 'WARNING'), (72000, 'CRITICAL'),
                                    (79200, 'FENCE_REQUIRED'), (86400, 'FENCE_REQUIRED'), (86401, 'RPO_VIOLATION')):
            f = Fixture(now=DAY+21600+point_age)
            binding, attempts, points = dr.replay_ledger(f.evidence, f.trust, f.now)
            self.assertEqual(dr.age(points[-1]['manifest']['point'], f.now), point_age)
            self.assertTrue(dr.validate_response(f.evidence, f.trust, binding, f.response, f.now))
            trusted_contents = copy.deepcopy(f.trust)
            predecessors = dict(f.evidence.documents)
            for silence in (300, 301, 600, 601):
                with self.subTest(point_age=point_age, silence=silence):
                    evidence, ongoing_ref, readiness_ref = self.rewrite_ongoing(
                        f, last_monitor=clock(DAY+21600+point_age-silence, identity='verifier-clock'))
                    trust = replace(f.trust, ongoing_sha256=ongoing_ref)
                    self.assertEqual(replace(trust, ongoing_sha256=f.ongoing_ref), trusted_contents)
                    self.assertEqual(f.trust, trusted_contents)
                    self.assertTrue(all(evidence.documents[k] == v for k,v in predecessors.items()))
                    result = dr.evaluate_ongoing(evidence, trust, ongoing_ref, f.now)
                    self.assertEqual(result['age_state'], severity)
                    self.assertEqual(result['age_seconds'], point_age)
                    self.assertEqual(result['response_state'], 'AUTHORIZED')
                    self.assertEqual(result['last_attempt_status'], attempts[-1]['outcome'])
                    expected = 'RPO_NOT_PROVABLE' if severity == 'HEALTHY' and silence > 300 else severity
                    self.assertEqual(result['state'], expected)
                    monitor_issues = [x for x in result['issues'] if x.startswith('MONITOR_')]
                    self.assertEqual(monitor_issues, ['MONITOR_UNAVAILABLE'] if silence > 600 else
                                     ['MONITOR_INTERVAL_MISSED'] if silence > 300 else [])
                    if severity == 'HEALTHY' and silence == 300:
                        self.assertTrue(result['ready'])
                    else:
                        self.assertFalse(result['ready'])
                        with self.assertRaises(ValueError):
                            dr.consume_barrier(evidence, trust, readiness_ref, ongoing_ref, f.now,
                                               purpose='preparation', requested_attempt='attempt-2')

    def test_f2_monitor_unknown_preserves_missing_response_authority(self):
        f = Fixture(now=DAY+21600+79200)
        for silence in (600, 601):
            with self.subTest(silence=silence):
                evidence, ongoing_ref, readiness_ref = self.rewrite_ongoing(
                    f, last_monitor=clock(DAY+21600+79200-silence, identity='verifier-clock'))
                trust = replace(f.trust, ongoing_sha256=ongoing_ref, response_authorities=frozenset())
                result = dr.evaluate_ongoing(evidence, trust, ongoing_ref, f.now)
                self.assertEqual(result['state'], 'FENCE_REQUIRED')
                self.assertEqual(result['age_state'], 'FENCE_REQUIRED')
                self.assertEqual(result['age_seconds'], 79200)
                self.assertEqual(result['response_state'], 'RESPONSE_AUTHORITY_MISSING')
                self.assertIn('RESPONSE_AUTHORITY_MISSING', result['issues'])
                self.assertIn('MONITOR_UNAVAILABLE' if silence > 600 else 'MONITOR_INTERVAL_MISSED', result['issues'])
                self.assert_ongoing_not_ready(f, evidence, trust, ongoing_ref, readiness_ref)

    def test_f2_monitor_unknown_rejects_r0_and_q_consumers(self):
        for purpose in ('preparation', 'cutover-Q'):
            f = Fixture(purpose=purpose)
            kwargs = {'cutover': f.cutover, 'native_stage7': f.native} if purpose == 'cutover-Q' else {}
            self.assertIn(f.barrier(**kwargs)['barrier'], ('R0', 'Q'))
            for silence in (600, 601):
                with self.subTest(purpose=purpose, silence=silence):
                    evidence, ongoing_ref, readiness_ref = self.rewrite_ongoing(
                        f, last_monitor=clock(NOW-silence, identity='verifier-clock'))
                    trust = replace(f.trust, ongoing_sha256=ongoing_ref)
                    result = dr.evaluate_ongoing(evidence, trust, ongoing_ref, f.now)
                    self.assertEqual(result['state'], 'RPO_NOT_PROVABLE')
                    self.assertFalse(result['ready'])
                    with self.assertRaisesRegex(ValueError, 'ONGOING_NOT_READY'):
                        dr.consume_barrier(evidence, trust, readiness_ref, ongoing_ref, f.now,
                                           purpose=purpose, requested_attempt='attempt-2', **kwargs)

    def test_f2_simultaneous_errors_remain_non_ready(self):
        cases = (
            ('stale-point-monitor-stale', 86401, 301, {}, False, False, 'RPO_VIOLATION'),
            ('critical-response-missing', 72000, 0, {'response_authorities': frozenset()}, False, False, 'CRITICAL'),
            ('fence-target-unavailable', 79200, 0, {'available_qualifications': frozenset()}, False, False, 'RPO_NOT_PROVABLE'),
            ('violation-monitor-unknown', 86401, 601, {}, False, False, 'RPO_VIOLATION'),
            ('fresh-custody-unavailable', 7140, 0, {'custody_versions': frozenset()}, False, False, 'RPO_NOT_PROVABLE'),
            ('fresh-latest-failed', 7140, 0, {}, True, False, 'RPO_NOT_PROVABLE'),
            ('valid-point-min-copies', 7140, 0, {}, False, True, 'RPO_NOT_PROVABLE'),
            ('latest-failed-monitor-unknown', 7140, 601, {}, True, False, 'RPO_NOT_PROVABLE'),
        )
        for name, point_age, silence, trust_updates, failed, min_copies, expected in cases:
            with self.subTest(case=name):
                def mutate(kind, doc):
                    if kind == 'ongoing':
                        doc['last_monitor'] = clock(DAY+21600+point_age-silence, identity='verifier-clock')
                    if kind == 'ledger' and min_copies:
                        doc.update(events=doc['events'][:3], head_sequence=3)
                f = Fixture(now=DAY+21600+point_age, second_outcome='FAILED' if failed else 'QUALIFIED', mutate=mutate)
                trust = replace(f.trust, **trust_updates)
                result = dr.evaluate_ongoing(f.evidence, trust, f.ongoing_ref, f.now)
                self.assertEqual(result['state'], expected)
                self.assertFalse(result['ready'])
                if silence:
                    self.assertIn('MONITOR_UNAVAILABLE' if silence > 600 else 'MONITOR_INTERVAL_MISSED', result['issues'])
                if failed:
                    self.assertEqual(result['last_attempt_status'], 'FAILED')
                    self.assertEqual(result['response_state'], 'AUTHORIZED')
                    self.assertIn('LATEST_ATTEMPT_FAILED', result['issues'])
                    with self.assertRaisesRegex(ValueError, 'LATEST_ATTEMPT_FAILED'):
                        dr.select_point(f.evidence, trust, f.now)
                if min_copies:
                    self.assertIn('RETENTION_OR_PERIODIC_PROOF_MISSING', result['issues'])
                with self.assertRaises(ValueError):
                    dr.consume_barrier(f.evidence, trust, f.readiness_ref, f.ongoing_ref, f.now,
                                       purpose='preparation', requested_attempt='attempt-2')

    def test_f2_invalid_monitor_clock_preserves_other_dimensions(self):
        f = Fixture(now=DAY+21600+79200)
        now = S.timestamp(f.now['utc'])
        for updates in ({'observed': clock(now-301, identity='verifier-clock')},
                        {'last_monitor': clock(now+1, identity='verifier-clock')},
                        {'last_monitor': clock(now-601, monotonic=now+1, identity='verifier-clock')}):
            with self.subTest(updates=updates):
                evidence, ongoing_ref, readiness_ref = self.rewrite_ongoing(f, **updates)
                trust = replace(f.trust, ongoing_sha256=ongoing_ref)
                result = dr.evaluate_ongoing(evidence, trust, ongoing_ref, f.now)
                self.assertEqual(result['state'], 'FENCE_REQUIRED')
                self.assertEqual(result['age_state'], 'FENCE_REQUIRED')
                self.assertEqual(result['age_seconds'], 79200)
                self.assertEqual(result['response_state'], 'AUTHORIZED')
                self.assertEqual(result['last_attempt_status'], 'QUALIFIED')
                self.assertIn('MONITOR_UNAVAILABLE', result['issues'])
                self.assert_ongoing_not_ready(f, evidence, trust, ongoing_ref, readiness_ref)

    def test_all_writer_response_and_no_automatic_recovery(self):
        for writer in S.WRITERS:
            f=Fixture(mutate=lambda k,d: d['writers'].update({writer:False}) if k=='response' else None)
            result=dr.evaluate_ongoing(f.evidence,f.trust,f.ongoing_ref,f.now)
            self.assertEqual(result['state'],'RESPONSE_AUTHORITY_MISSING')
            self.assertFalse(result['ready'])
        for action in ('auto_restore','auto_rollback','auto_reopen'):
            f=Fixture(mutate=lambda k,d: d.update({action:True}) if k=='response' else None)
            self.assertFalse(dr.evaluate_ongoing(f.evidence,f.trust,f.ongoing_ref,f.now)['ready'])
        f=Fixture(now=DAY+21600+86401)
        result=dr.evaluate_ongoing(f.evidence,replace(f.trust,response_authorities=frozenset()),f.ongoing_ref,f.now)
        self.assertEqual(result['state'],'RPO_VIOLATION')
        self.assertEqual(result['response_state'],'RESPONSE_AUTHORITY_MISSING')

    def test_exact_qualification_deadline_cannot_hide_missing_cycle(self):
        f=Fixture(now=DAY+28800)
        result=dr.evaluate_ongoing(f.evidence,f.trust,f.ongoing_ref,f.now)
        self.assertIn('CADENCE_OR_QUALIFICATION_MISSED',result['issues'])
        self.assertFalse(result['ready'])

    def test_new_slot_grace_cannot_hide_previous_missed_periodic_cycle(self):
        f=Fixture(now=DAY+43260)
        result=dr.evaluate_ongoing(f.evidence,f.trust,f.ongoing_ref,f.now)
        self.assertEqual(result['age_seconds'],21660)
        self.assertIn('CADENCE_OR_QUALIFICATION_MISSED',result['issues'])
        self.assertFalse(result['ready'])

    def test_evidence_contains_no_plaintext_secrets_and_errors_do_not_echo(self):
        f=Fixture()
        raw=b''.join(f.documents.values())
        for canary in (b'synthetic-canary',b'postgresql://',b'BEGIN PRIVATE KEY',b'SCRAM-SHA-256$',b'PGPASSWORD'):
            self.assertNotIn(canary,raw)
        with tempfile.TemporaryDirectory(prefix='ht-ops-04-cli-') as directory:
            path=Path(directory).resolve()/'input.json'
            path.write_bytes(b'{"secret":"synthetic-canary"}')
            result=subprocess.run([sys.executable,str(ROOT/'scripts/v126-dr-evidence.py'),'validate-document',str(path)],capture_output=True)
            self.assertNotEqual(result.returncode,0)
            self.assertNotIn(b'synthetic-canary',result.stdout+result.stderr)
            path.write_bytes(f.documents[f.ledger_ref])
            result=subprocess.run([sys.executable,str(ROOT/'scripts/v126-dr-evidence.py'),'validate-document',str(path)],capture_output=True)
            self.assertEqual(result.stdout,b'SCHEMA_VALID_ONLY_NOT_DR_PASS\n')
            self.assertEqual(result.returncode,0)


class PostgreSQLDockerTests(unittest.TestCase):
    def test_downloaded_whole_db_preserves_catalogs_queues_flyway_and_auth(self):
        existing = dr.module('existing_dr_rehearsal', ROOT/'scripts/test-v126-database-evidence.py')
        env = {k:v for k,v in os.environ.items() if k in ('PATH','HOME','TMPDIR')}
        def command(args, payload=None, check=True):
            result = subprocess.run(['docker',*args], input=payload, env=env, capture_output=True, timeout=90)
            if check and result.returncode:
                self.fail('owned synthetic Docker consumer failed; output suppressed, exit='+str(result.returncode))
            return result
        context = json.loads(command(['context','inspect']).stdout)
        self.assertEqual(len(context),1)
        self.assertTrue(context[0]['Endpoints']['docker']['Host'].startswith('unix://'))
        inspected = json.loads(command(['image','inspect','postgres:17']).stdout)
        self.assertEqual(len(inspected),1)
        image = inspected[0]['Id']
        self.assertRegex(image,r'^sha256:[0-9a-f]{64}$')
        containers=[]
        def execute(cid,args,payload=None,check=True):
            return command(['exec','-i','--user','postgres',cid,*args],payload,check)
        def sql(cid,statement,db='postgres',check=True):
            return execute(cid,['psql','-XqAtw','-U','repair_owner','-d',db,'--set=ON_ERROR_STOP=1'],statement.encode(),check)
        def start():
            cid=command(['run','--detach','--pull=never','--network','none','--memory','512m','--pids-limit','128',
                '--label','codex.task=HT-OPS-04','--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=384m',
                '-e','POSTGRES_USER=repair_owner','-e','POSTGRES_DB=postgres','-e','POSTGRES_HOST_AUTH_METHOD=trust',image]).stdout.decode().strip()
            self.assertRegex(cid,r'^[0-9a-f]{64}$')
            containers.append(cid)
            for _ in range(100):
                result=execute(cid,['sh','-c','test "$(cat /proc/1/comm)" = postgres && pg_isready -U repair_owner -d postgres'],check=False)
                if result.returncode==0: break
                time.sleep(0.1)
            else: self.fail('owned PG17 did not become ready')
            config=json.loads(command(['inspect',cid]).stdout)[0]
            self.assertEqual(config['Id'],cid)
            self.assertEqual(config['Image'],image)
            self.assertEqual(config['HostConfig']['NetworkMode'],'none')
            self.assertFalse(config['HostConfig']['PortBindings'])
            self.assertEqual(config['Config']['Labels']['codex.task'],'HT-OPS-04')
            self.assertTrue(all(m['Type']=='tmpfs' for m in config['Mounts']))
            return cid
        try:
            source=start()
            version=execute(source,['pg_dump','--version']).stdout
            self.assertIn(b'PostgreSQL) 17.',version)
            sql(source,'CREATE DATABASE source; CREATE ROLE other_role;')
            sql(source,existing.SCHEMA+'CREATE SCHEMA other_schema;'+existing.WHOLE_DR_SETUP_SQL,'source')
            original={k:sql(source,v,'source').stdout for k,v in existing.WHOLE_DR_FACTS_SQL.items()}
            # The fixture has no concurrent writers. Exact pg_dump bytes and fresh
            # password-free globals are downloaded through the fake S3 interface.
            dump=execute(source,['pg_dump','-U','repair_owner','--format=custom','--dbname=source']).stdout
            globals_sql=execute(source,['pg_dumpall','-U','repair_owner','-l','source','--globals-only','--no-role-passwords']).stdout
            listing=execute(source,['pg_restore','--list'],dump).stdout
            fixture=Fixture(); p=fixture.points[-1]
            members=dict(p['members'], **{'application.dump':dump,'globals.sql':globals_sql,'toc.list':listing})
            for name,vector in original.items():
                member={'ROLES_MEMBERSHIPS':'roles.json','OWNERSHIP_ACL':'acl.json','SETTINGS_AUTH_CONFIG':'settings.json',
                        'DATA_SCHEMA':'data-schema.json','FLYWAY':'flyway.json','SEQUENCES':'sequences.json','DURABLE_QUEUES':'queues.json'}[name]
                data=json.loads(members[member]); data['semantic_sha256']=dr.sha(vector); members[member]=dr.canonical(data)
            manifest=dict(p['manifest'],artifacts={k:dr.file_identity(v) for k,v in members.items()})
            archive=archive_bytes(members)
            off=dict(p['off'], manifest_sha256=dr.digest(manifest),key='bundles/'+dr.digest(manifest)+'.enc',
                     downloaded_archive=dr.file_identity(archive),decrypted_members_sha256=dr.digest(manifest['artifacts']))
            transport=dr.FakeS3()
            transport.put_fixture(off['target_sha256'],off['key'],off['version_id'],p['cipher'])
            downloaded=dr.independent_readback(transport,dr.FakeEncryption(p['cipher'],archive),off,manifest,16*1024*1024)
            # Deliberately discard the producer cache; restore only downloaded members.
            del members, archive, dump, globals_sql
            target=start()
            roles=sql(target,"SELECT rolname FROM pg_roles WHERE rolname NOT LIKE 'pg_%' ORDER BY rolname;").stdout.decode().splitlines()
            dbs=sql(target,'SELECT datname FROM pg_database ORDER BY datname;').stdout.decode().splitlines()
            transformed,proof=dr.bootstrap_globals(downloaded['globals.sql'],'repair_owner',roles,dbs)
            self.assertEqual(proof['original_sha256'],manifest['artifacts']['globals.sql']['sha256'])
            execute(target,['psql','-XqAtw','-U','repair_owner','-d','postgres','--set=ON_ERROR_STOP=1'],transformed)
            execute(target,['pg_restore','-U','repair_owner','--exit-on-error','--create','--dbname=postgres'],downloaded['application.dump'])
            restored={k:sql(target,v,'source').stdout for k,v in existing.WHOLE_DR_FACTS_SQL.items()}
            self.assertEqual(len(dr.compare_catalogs(original,restored)),7)
            sql(target,"ALTER TABLE dr_payload OWNER TO repair_owner;",'source')
            changed=dict(restored,OWNERSHIP_ACL=sql(target,existing.WHOLE_DR_FACTS_SQL['OWNERSHIP_ACL'],'source').stdout)
            with self.assertRaises(ValueError): dr.compare_catalogs(original,changed)
            sql(target,"ALTER TABLE dr_payload OWNER TO other_role;",'source')
            sql(target,"UPDATE telegram_outbox SET status='SENT';",'source')
            changed=dict(restored,DURABLE_QUEUES=sql(target,existing.WHOLE_DR_FACTS_SQL['DURABLE_QUEUES'],'source').stdout)
            with self.assertRaises(ValueError): dr.compare_catalogs(original,changed)
            sql(target,"UPDATE telegram_outbox SET status='SENDING';",'source')
            sql(target,'UPDATE flyway_schema_history SET checksum=1;','source')
            changed=dict(restored,FLYWAY=sql(target,existing.WHOLE_DR_FACTS_SQL['FLYWAY'],'source').stdout)
            with self.assertRaises(ValueError): dr.compare_catalogs(original,changed)
            sql(target,'UPDATE flyway_schema_history SET checksum=0;','source')
            self.assertEqual(sql(target,"SELECT nextval('dr_sequence');",'source').stdout,b'8\n')
            # Synthetic SCRAM secret is generated in memory and passed through
            # stdin only, never argv, files, receipts, fixture source or diagnostics.
            password=os.urandom(24).hex()
            sql(target,"ALTER ROLE repair_login PASSWORD '"+password+"';")
            execute(target,['sh','-c',
                '(printf "local all repair_login scram-sha-256\\n"; cat "$PGDATA/pg_hba.conf") > "$PGDATA/hba.new" && '
                'mv "$PGDATA/hba.new" "$PGDATA/pg_hba.conf" && pg_ctl -D "$PGDATA" reload'])
            def login(secret,query):
                return execute(target,['sh','-c',
                    'IFS= read -r PGPASSWORD; export PGPASSWORD; exec psql -XqAtw -U repair_login -d source --set=ON_ERROR_STOP=1'],
                    (secret+'\n'+query).encode(),check=False)
            success=login(password,'SELECT current_user; SELECT count(*) FROM dr_payload; SHOW statement_timeout; SHOW lock_timeout; SHOW timezone;')
            self.assertEqual(success.returncode,0)
            self.assertEqual(success.stdout,b'repair_login\n1\n7s\n3s\nAsia/Tokyo\n')
            self.assertNotEqual(login('invalid-synthetic','SELECT 1;').returncode,0)
            self.assertNotEqual(login(password,"INSERT INTO dr_payload VALUES(2,'denied');").returncode,0)
            self.assertNotIn(password.encode(),b''.join(downloaded.values()))
            self.assertEqual(sql(target,existing.WHOLE_DR_FACTS_SQL['FLYWAY'],'source').stdout,original['FLYWAY'])
            self.assertEqual(sql(target,existing.WHOLE_DR_FACTS_SQL['DURABLE_QUEUES'],'source').stdout,original['DURABLE_QUEUES'])
            print('SYNTHETIC_PG17_WHOLE_DB_ONLY; image='+image+'; architecture='+inspected[0]['Architecture'])
        finally:
            for cid in reversed(containers):
                owned=json.loads(command(['inspect',cid]).stdout)[0]
                self.assertEqual(owned['Config']['Labels']['codex.task'],'HT-OPS-04')
                self.assertEqual(owned['Image'],image)
                command(['rm','--force',cid])
                self.assertNotEqual(command(['inspect',cid],check=False).returncode,0)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--postgres-docker',action='store_true',help='also run own network-none PG17 using an already installed local image')
    args=parser.parse_args()
    suite=unittest.TestLoader().loadTestsFromTestCase(PolicyTests)
    if args.postgres_docker:
        suite.addTests(unittest.TestLoader().loadTestsFromTestCase(PostgreSQLDockerTests))
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
