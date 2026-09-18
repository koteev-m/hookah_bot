#!/usr/bin/env python3
"""Offline AP-07 foundation regressions. No real secret material or network."""
import copy
from dataclasses import replace
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import traceback
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('dr_custody', ROOT / 'scripts/v126-dr-custody.py')
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)
dr = c.dr
H = lambda value: hashlib.sha256(('synthetic-nonsecret-' + value).encode()).hexdigest()
BASE = datetime(2026, 9, 17, tzinfo=timezone.utc)
T = lambda seconds: (BASE + timedelta(seconds=seconds)).strftime('%Y-%m-%dT%H:%M:%SZ')
CANARY = 'SYNTHETIC_SECRET_CANARY_MUST_NOT_APPEAR'


def clock(seconds):
    return dict(utc=T(seconds), error_seconds=0, monotonic_seconds=1000 + seconds, clock_id=H('clock'))


def domains(prefix, offline=False):
    return {name: None if offline and name in ('provider', 'account') else prefix + '-' + name for name in c.DOMAINS}


def binding(epoch='current'):
    source = H(epoch + '-source')[:40]
    tree = H(epoch + '-tree')[:40]
    return dict(policy_sha256=dr.digest(c.S.POLICY), source_sha=source, source_tree=tree,
                tools_sha256=H(epoch + '-tools'), python_version='3.13.7',
                runtime=dict(source_sha=source, source_tree=tree, image_sha256=H(epoch+'-image'),
                             pg_image_sha256=H(epoch+'-pg-image'), migration_tree=H(epoch+'-migration')[:40],
                             config_epoch=H(epoch+'-config-descriptor')),
                source_identity_sha256=H(epoch+'-source-identity'), database_semantics_sha256=H(epoch+'-database-semantics'),
                restore_recipe_sha256=H(epoch+'-recipe'), target_sha256=H('target'),
                deployment_record_sha256=H(epoch+'-sanitized-deployment'), telegram_mode='long_polling',
                recovery_profile=dict(ai=False,geodata=False,billing='fake'))


def asset(role, bind, version=1, state='current'):
    return dict(identity=dict(schema_version=1, kind='ap07-asset-version', logical_id=role, version=version,
                              created_at=T(-100), previous_version_sha256=None if version == 1 else H(role+'-previous')),
                classification=c.ROLES[role][0], preservation='exact-bytes', regeneration=c.ROLES[role][1],
                state=state, recovery_eligibility={'current':'active','retired':'historical-only','revoked':'ineligible'}[state],
                expires_at=None, public_bytes=dict(sha256=H(role+'-public-archive'), size=100) if c.ROLES[role][0] == c.PUBLIC else None,
                binding=copy.deepcopy(bind))


def descriptor():
    bind = binding()
    assets = [asset(role, bind) for role in sorted(set(c.ROLES) - {'webhook-secret'})]
    members = sorted(c.asset_version(x) for x in assets)
    return dict(schema_version=1, kind='ap07-custody-set', set_id='synthetic-custody', version=1,
                state='current', created_at=T(0), previous_set_sha256=None, binding=bind,
                runtime_domains=domains('vps'), backup_domains=domains('backup'), assets=assets,
                copies=[dict(copy_id=route, state='current', domains=domains(route.lower(), offline=route=='B'),
                             created_at=T(-10), declared_verified_at=None,
                             ciphertext=dict(sha256=H(route+'-encrypted-package'), size=1024), members=list(members)) for route in ('A','B')])


def pins(doc, retained=frozenset()):
    return c.CustodyPins(dr.digest(doc), doc['set_id'], doc['version'], dr.digest(doc['binding']),
                        tuple(sorted((x['identity']['logical_id'], c.asset_version(x)) for x in doc['assets'] if x['state']=='current')),
                        retained, clock(10))


def synchronize_members(doc):
    members = sorted(c.asset_version(x) for x in doc['assets'] if x['state'] != 'revoked')
    doc['assets'].sort(key=lambda x:(x['identity']['logical_id'],x['identity']['version']))
    for cp in doc['copies']:
        cp['members'] = list(members)


def proof(doc, pin, routes=('A','B')):
    assets = {c.asset_version(x): x for x in doc['assets']}
    needed = set(pin.required_versions) | {version for _, version in pin.current_versions}
    return dict(schema_version=1, kind='ap07-retrieval-proof', custody_set_sha256=dr.digest(doc),
                set_id=doc['set_id'], set_version=doc['version'], binding_sha256=pin.binding_sha256,
                observed=clock(10), expires_at=T(310), environment_domains=domains('clean-recovery'), routes=list(routes),
                retrievals=[dict(copy_id=route, asset_version_sha256=version,
                                 operation=c.ROLES[assets[version]['identity']['logical_id']][2],
                                 operation_evidence_sha256=H(route+'-observed-'+version)) for route in sorted(routes) for version in sorted(needed)])


def observations(doc, receipt):
    topology = {'copies':[{'copy_id':x['copy_id'],'domains':x['domains']} for x in doc['copies'] if x['copy_id'] in receipt['routes']],
                'runtime_domains':doc['runtime_domains'],'backup_domains':doc['backup_domains']}
    return c.RetrievalObservations(dr.digest(receipt), dr.digest(receipt['environment_domains']),dr.digest(topology),
                                  frozenset((x['copy_id'],x['ciphertext']['sha256'],x['ciphertext']['size']) for x in doc['copies'] if x['copy_id'] in receipt['routes']),
                                  frozenset((x['copy_id'],x['asset_version_sha256'],x['operation'],x['operation_evidence_sha256']) for x in receipt['retrievals']),
                                  copy.deepcopy(receipt['observed']))


class CustodyTests(unittest.TestCase):
    def setUp(self):
        self.doc = descriptor()
        self.pin = pins(self.doc)
        self.now = clock(20)
        self.receipt = proof(self.doc, self.pin)
        # Test doubles model independent inputs. No operational acquisition exists.
        self.observation = observations(self.doc, self.receipt)
        self.addCleanup(patch.stopall)
        patch('socket.create_connection', side_effect=AssertionError('NETWORK_FORBIDDEN')).start()
        patch.object(socket.socket, 'connect', side_effect=AssertionError('NETWORK_FORBIDDEN')).start()

    def invalid(self, call, code):
        with self.assertRaises(ValueError) as caught:
            call()
        self.assertEqual(str(caught.exception), code)
        self.assertNotIn(CANARY, ''.join(traceback.format_exception(caught.exception)))

    def parse(self, doc=None):
        return c.parse_set(dr.canonical(self.doc if doc is None else doc))

    def validate(self, doc=None, pin=None):
        return c.validate_set(dr.canonical(self.doc if doc is None else doc), self.pin if pin is None else pin, self.now)

    def retrieve(self, receipt=None, obs=None, now=None, doc=None, pin=None):
        return c.validate_retrieval(dr.canonical(self.doc if doc is None else doc),
                                    dr.canonical(self.receipt if receipt is None else receipt),
                                    self.pin if pin is None else pin, self.observation if obs is None else obs,
                                    self.now if now is None else now)

    def test_valid_minimal_two_copy_set_and_separate_observations(self):
        self.assertEqual(self.parse(), self.doc)
        self.assertEqual(self.validate(), self.doc)
        self.assertEqual(self.retrieve(), 'AP07_RETRIEVAL_OBSERVATIONS_VALID_ONLY')
        self.invalid(lambda: self.retrieve(obs={}), 'AP07_RETRIEVAL_NOT_PROVEN')

    def test_missing_each_required_asset_and_conditional_webhook(self):
        for i in range(len(self.doc['assets'])):
            altered = copy.deepcopy(self.doc)
            altered['assets'].pop(i)
            synchronize_members(altered)
            self.invalid(lambda: self.parse(altered), 'AP07_DECLARATION_INVALID')
        self.doc['binding']['telegram_mode'] = 'webhook'
        for item in self.doc['assets']:
            item['binding']['telegram_mode'] = 'webhook'
        self.invalid(self.parse, 'AP07_DECLARATION_INVALID')
        self.doc['assets'].append(asset('webhook-secret', self.doc['binding']))
        synchronize_members(self.doc)
        self.assertEqual(self.parse(), self.doc)

    def test_missing_copy_member_or_same_ciphertext(self):
        for mutate in (lambda d:d['copies'].pop(), lambda d:d['copies'][1]['members'].pop(),
                       lambda d:d['copies'][1].update(ciphertext=d['copies'][0]['ciphertext'])):
            doc = copy.deepcopy(self.doc)
            mutate(doc)
            self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')

    def test_offline_copies_reject_shared_failure_domain(self):
        for dimension in ('device', 'physical', 'credential', 'wrapping_key'):
            with self.subTest(dimension=dimension):
                doc = copy.deepcopy(self.doc)
                # Keep B offline so only the A/B comparison can reject this alias.
                self.assertIsNone(doc['copies'][1]['domains']['account'])
                self.assertIsNone(doc['copies'][1]['domains']['provider'])
                doc['copies'][1]['domains'][dimension] = doc['copies'][0]['domains'][dimension]
                self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')

    def test_offline_copies_reject_cross_labeled_authority(self):
        for left, right in (('credential', 'wrapping_key'), ('wrapping_key', 'credential')):
            with self.subTest(a=left, b=right):
                doc = copy.deepcopy(self.doc)
                self.assertIsNone(doc['copies'][1]['domains']['account'])
                self.assertIsNone(doc['copies'][1]['domains']['provider'])
                doc['copies'][1]['domains'][right] = doc['copies'][0]['domains'][left]
                self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')

    def test_copies_independent_of_runtime_and_backup(self):
        for surface in ('runtime_domains','backup_domains'):
            for dimension in c.DOMAINS:
                with self.subTest(surface=surface, dimension=dimension):
                    doc = copy.deepcopy(self.doc)
                    doc['copies'][0]['domains'][dimension] = doc[surface][dimension]
                    self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')
            doc = copy.deepcopy(self.doc)
            doc['copies'][0]['domains']['wrapping_key'] = doc[surface]['credential']
            self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')

    def test_offline_b_rejects_account_and_provider_dependencies(self):
        self.assertIsNone(self.doc['copies'][1]['domains']['account'])
        self.assertIsNone(self.doc['copies'][1]['domains']['provider'])
        self.assertEqual(self.parse(), self.doc)
        for dimensions in (('account',), ('provider',), ('account', 'provider')):
            with self.subTest(dimensions=dimensions):
                doc = copy.deepcopy(self.doc)
                for dimension in dimensions:
                    doc['copies'][1]['domains'][dimension] = 'b-online-' + dimension
                self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')

    def test_stale_version_and_current_catalogue_cannot_be_self_renewed(self):
        old = copy.deepcopy(self.doc)
        self.doc.update(version=2, previous_set_sha256=dr.digest(old))
        self.invalid(self.validate, 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')
        for field, value in [('set_version',2), ('set_id','wrong-id'), ('set_sha256',H('wrong')),
                             ('binding_sha256',H('wrong')), ('current_versions',()), ('current_versions',{})]:
            self.invalid(lambda:self.validate(doc=old,pin=replace(self.pin,**{field:value})), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')

    def test_current_revoked_or_retired_set_copy_or_asset_rejected(self):
        for state in ('revoked','retired'):
            for level in ('set','copy','asset'):
                doc = copy.deepcopy(self.doc)
                if level == 'set':
                    doc['state'] = state
                elif level == 'copy':
                    doc['copies'][0]['state'] = state
                else:
                    doc['assets'][0].update(state=state,recovery_eligibility='ineligible' if state=='revoked' else 'historical-only')
                    synchronize_members(doc)
                self.invalid(lambda:self.validate(doc,pins(doc)), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')

    def test_unknown_secret_fields_and_plaintext_secret_hashes_never_echoed(self):
        for field in ('plaintext','secret','key_bytes','secret_sha256','plaintext_sha256','credential_uri'):
            for level in ('set','asset','copy','identity'):
                doc = copy.deepcopy(self.doc)
                target = {'set':doc,'asset':doc['assets'][0],'copy':doc['copies'][0],'identity':doc['assets'][0]['identity']}[level]
                target[field] = CANARY
                self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')
        for item in self.doc['assets']:
            if item['classification'] != c.PUBLIC:
                altered = copy.deepcopy(self.doc)
                next(x for x in altered['assets'] if x['identity']==item['identity'])['public_bytes'] = dict(sha256=H('forbidden'),size=1)
                self.invalid(lambda:self.parse(altered), 'AP07_DECLARATION_INVALID')

    def test_duplicate_identity_current_role_copy_member_and_proof_entries(self):
        doc = copy.deepcopy(self.doc)
        doc['assets'].insert(0,copy.deepcopy(doc['assets'][0]))
        self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')
        doc = copy.deepcopy(self.doc)
        other = copy.deepcopy(doc['assets'][0])
        other['identity'].update(version=2,previous_version_sha256=c.asset_version(other))
        doc['assets'].append(other)
        synchronize_members(doc)
        self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')
        doc = copy.deepcopy(self.doc)
        doc['copies'][1]['copy_id'] = 'A'
        self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')
        doc = copy.deepcopy(self.doc)
        doc['copies'][0]['members'].append(doc['copies'][0]['members'][-1])
        self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')
        self.receipt['retrievals'].append(self.receipt['retrievals'][-1])
        self.invalid(lambda:self.retrieve(obs=observations(self.doc,self.receipt)), 'AP07_RETRIEVAL_NOT_PROVEN')

    def test_malformed_timestamps_versions_expiry_and_future_created(self):
        for value in (True,0,-1,'1',1.0,2**64):
            doc = copy.deepcopy(self.doc)
            doc['assets'][0]['identity']['version'] = value
            self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')
        for value in ('2026-02-30T00:00:00Z','2026-09-17','2026-09-17T00:00:00+00:00',False):
            doc = copy.deepcopy(self.doc)
            doc['created_at'] = value
            self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')
        self.doc['created_at'] = T(21)
        self.invalid(lambda:self.validate(pin=pins(self.doc)), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')
        self.doc = descriptor()
        self.doc['assets'][0]['expires_at'] = T(20)
        self.invalid(lambda:self.validate(pin=pins(self.doc)), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')

    def test_wrong_source_tool_runtime_and_deployment_binding(self):
        for field in ('tools_sha256','source_sha','source_tree','deployment_record_sha256','target_sha256'):
            doc = copy.deepcopy(self.doc)
            doc['binding'][field] = H('wrong')[:40] if field in ('source_sha','source_tree') else H('wrong')
            self.invalid(lambda:self.validate(doc), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')
        doc = copy.deepcopy(self.doc)
        doc['assets'][0]['binding'] = binding('wrong')
        self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')

    def test_noncanonical_duplicate_json_and_wrong_types_rejected(self):
        raw = dr.canonical(self.doc)
        for bad in (raw+b' ',raw.rstrip(b'\n'),json.dumps(self.doc,indent=2).encode(),
                    raw.replace(b'{',b'{"schema_version":1,',1),b'[]',b'null',b'NaN',b'', 'not bytes'):
            self.invalid(lambda:c.parse_set(bad), 'AP07_DECLARATION_INVALID')
        doc = copy.deepcopy(self.doc)
        doc['assets'].reverse()
        self.invalid(lambda:self.parse(doc), 'AP07_DECLARATION_INVALID')
        with patch.object(c,'MAX_DOCUMENT_BYTES',10):
            self.invalid(self.parse, 'AP07_DECLARATION_INVALID')

    def test_retrieval_wrong_version_set_binding_and_unpinned_refresh(self):
        for field,value in (('set_version',2),('set_id','wrong'),('custody_set_sha256',H('wrong')),('binding_sha256',H('wrong'))):
            receipt = copy.deepcopy(self.receipt)
            receipt[field] = value
            self.invalid(lambda:self.retrieve(receipt,observations(self.doc,receipt)), 'AP07_RETRIEVAL_NOT_PROVEN')
        self.receipt['expires_at'] = T(200)
        self.invalid(self.retrieve, 'AP07_RETRIEVAL_NOT_PROVEN')
        self.invalid(lambda:self.retrieve(obs={}), 'AP07_RETRIEVAL_NOT_PROVEN')

    def test_retrieval_environment_independence(self):
        for surface in ('runtime_domains','backup_domains'):
            for dimension in c.DOMAINS:
                receipt = copy.deepcopy(self.receipt)
                receipt['environment_domains'][dimension] = self.doc[surface][dimension]
                self.invalid(lambda:self.retrieve(receipt,observations(self.doc,receipt)), 'AP07_RETRIEVAL_NOT_PROVEN')

    def test_expired_stale_future_clock_and_long_proof_lifetime(self):
        for now in (clock(311),clock(9)):
            self.invalid(lambda:self.retrieve(now=now), 'AP07_RETRIEVAL_NOT_PROVEN')
        receipt = copy.deepcopy(self.receipt)
        for value in (T(20),T(311)):
            receipt['expires_at'] = value
            self.invalid(lambda:self.retrieve(receipt,observations(self.doc,receipt)), 'AP07_RETRIEVAL_NOT_PROVEN')
        receipt = copy.deepcopy(self.receipt)
        receipt['observed']['monotonic_seconds'] = 2000
        self.invalid(lambda:self.retrieve(receipt,observations(self.doc,receipt)), 'AP07_RETRIEVAL_NOT_PROVEN')

    def test_receipt_alone_missing_operations_packages_or_wrong_operation_fails(self):
        for change in (dict(completed_retrievals=frozenset()),dict(packages=frozenset()),
                       dict(environment_sha256=H('wrong')),dict(topology_sha256=H('wrong')),dict(proof_sha256=H('wrong'))):
            self.invalid(lambda:self.retrieve(obs=replace(self.observation,**change)), 'AP07_RETRIEVAL_NOT_PROVEN')
        receipt = copy.deepcopy(self.receipt)
        receipt['retrievals'][0]['operation'] = 'dependency-walkthrough'
        self.invalid(lambda:self.retrieve(receipt,observations(self.doc,receipt)), 'AP07_RETRIEVAL_NOT_PROVEN')
        receipt = copy.deepcopy(self.receipt)
        receipt['retrievals'].pop()
        self.invalid(lambda:self.retrieve(receipt,observations(self.doc,receipt)), 'AP07_RETRIEVAL_NOT_PROVEN')

    def test_single_surviving_route_is_degraded_not_healthy_custody(self):
        for route in ('A','B'):
            receipt = proof(self.doc,self.pin,(route,))
            self.assertEqual(self.retrieve(receipt,observations(self.doc,receipt)), 'AP07_DEGRADED_RETRIEVAL_VALID_ONLY')
        self.doc['copies'].pop()
        self.invalid(lambda:self.validate(pin=pins(self.doc)), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')

    def rotated(self):
        old_key = next(x for x in self.doc['assets'] if x['identity']['logical_id']=='backup-key')
        old_version = c.asset_version(old_key)
        old_key.update(state='retired',recovery_eligibility='historical-only')
        new_key = asset('backup-key',self.doc['binding'],2)
        new_key['identity']['previous_version_sha256'] = old_version
        self.doc['assets'].append(new_key)
        synchronize_members(self.doc)
        return old_version

    def test_key_rotation_keeps_historical_decrypt_and_current_epoch_separate(self):
        old_version = self.rotated()
        self.pin = pins(self.doc,frozenset({old_version}))
        self.assertEqual(self.validate(),self.doc)
        receipt = proof(self.doc,self.pin)
        self.assertEqual(self.retrieve(receipt,observations(self.doc,receipt)), 'AP07_RETRIEVAL_OBSERVATIONS_VALID_ONLY')
        self.assertNotEqual(c.group_versions(self.doc)['decryption'],old_version)
        old = next(x for x in self.doc['assets'] if c.asset_version(x)==old_version)
        old.update(state='revoked',recovery_eligibility='ineligible')
        synchronize_members(self.doc)
        self.invalid(lambda:self.validate(pin=pins(self.doc,frozenset({old_version}))), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')

    def test_retained_requirement_survives_seven_days_last_two_and_q_hold(self):
        old_version = self.rotated()
        self.pin = pins(self.doc,frozenset({old_version}))
        self.assertEqual(c.validate_set(dr.canonical(self.doc),replace(self.pin,observed=clock(30*86400-10)),clock(30*86400)),self.doc)
        self.doc['assets'] = [x for x in self.doc['assets'] if c.asset_version(x)!=old_version]
        synchronize_members(self.doc)
        self.invalid(lambda:self.validate(pin=pins(self.doc,frozenset({old_version}))), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')

    def test_retained_config_or_secret_not_only_key_must_be_preserved(self):
        for role in ('configuration','jwt-secret','backend-image','staff-invite-pepper'):
            self.invalid(lambda:self.validate(pin=replace(self.pin,required_versions=frozenset({H(role+'-retained')}))), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')

    def test_regenerable_is_not_substitute_for_exact_historical_secret(self):
        db = next(x for x in self.doc['assets'] if x['identity']['logical_id']=='db-auth')
        self.assertEqual(db['regeneration'],'authorized-requalification')
        db['preservation'] = 'regenerate'
        self.invalid(self.parse,'AP07_DECLARATION_INVALID')
        self.doc = descriptor()
        key = next(x for x in self.doc['assets'] if x['identity']['logical_id']=='backup-key')
        key['regeneration'] = 'authorized-requalification'
        self.invalid(self.parse,'AP07_DECLARATION_INVALID')

    def test_config_image_secret_classes_and_version_identity_are_distinct(self):
        for role in ('backend-image','configuration','jwt-secret'):
            doc = copy.deepcopy(self.doc)
            item = next(x for x in doc['assets'] if x['identity']['logical_id']==role)
            item['classification'] = c.SECRET if item['classification']!=c.SECRET else c.PUBLIC
            self.invalid(lambda:self.parse(doc),'AP07_DECLARATION_INVALID')
        versions = c.group_versions(self.doc)
        self.assertEqual(set(versions),{'decryption','secrets','config','images','evidence'})
        self.assertEqual(len(set(versions.values())),5)
        key = next(x for x in self.doc['assets'] if x['identity']['logical_id']=='backup-key')
        before = c.asset_version(key)
        key.update(state='retired',recovery_eligibility='historical-only')
        self.assertEqual(c.asset_version(key),before)
        self.assertEqual(dr.sha(dr.canonical(self.parse(descriptor()))),dr.digest(descriptor()))

    def test_catalogue_pins_must_be_fresh_even_with_fresh_retrieval(self):
        self.invalid(lambda:c.validate_set(dr.canonical(self.doc),self.pin,clock(311)), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')
        self.receipt.update(observed=clock(311),expires_at=T(611))
        self.invalid(lambda:self.retrieve(obs=observations(self.doc,self.receipt),now=clock(321)), 'AP07_RETRIEVAL_NOT_PROVEN')
        self.invalid(lambda:self.validate(pin=replace(self.pin,observed=clock(21))), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')

    def test_unsupported_live_provider_profile_fails_closed(self):
        for field,value in (('ai',True),('geodata',True),('billing','live')):
            self.doc = descriptor()
            self.doc['binding']['recovery_profile'][field] = value
            for item in self.doc['assets']:
                item['binding']['recovery_profile'][field] = value
            self.invalid(self.parse,'AP07_DECLARATION_INVALID')

    def test_private_image_cannot_publish_plain_digest(self):
        for item in self.doc['assets']:
            if item['identity']['logical_id'] in ('backend-image','postgres-image','source-tools'):
                item.update(classification=c.SENSITIVE,public_bytes=None)
        self.assertEqual(self.parse(),self.doc)
        self.doc['assets'][0]['public_bytes'] = dict(sha256=H('private-image'),size=1)
        self.invalid(self.parse,'AP07_DECLARATION_INVALID')

    def test_rotation_lineage_and_older_current_version_rejected(self):
        old_version = self.rotated()
        newer = next(x for x in self.doc['assets'] if x['identity']['logical_id']=='backup-key' and x['identity']['version']==2)
        for previous in (H('wrong-predecessor'),c.asset_version(next(x for x in self.doc['assets'] if x['identity']['logical_id']=='telegram-token'))):
            newer['identity']['previous_version_sha256'] = previous
            synchronize_members(self.doc)
            self.invalid(self.parse,'AP07_DECLARATION_INVALID')
        newer['identity']['previous_version_sha256'] = old_version
        newer.update(state='retired',recovery_eligibility='historical-only')
        old = next(x for x in self.doc['assets'] if c.asset_version(x)==old_version)
        old.update(state='current',recovery_eligibility='active')
        synchronize_members(self.doc)
        self.invalid(self.parse,'AP07_DECLARATION_INVALID')

    def test_catalogue_proof_and_packages_cannot_predate_their_dependencies(self):
        self.invalid(lambda:self.validate(pin=replace(self.pin,observed=clock(-1))), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')
        self.receipt.update(observed=clock(-1),expires_at=T(299))
        self.invalid(lambda:self.retrieve(obs=observations(self.doc,self.receipt)), 'AP07_RETRIEVAL_NOT_PROVEN')
        self.doc['copies'][0]['created_at'] = T(-101)
        self.invalid(self.parse,'AP07_DECLARATION_INVALID')

    def test_account_recovery_version_is_in_ap01_aggregate(self):
        before = c.group_versions(self.doc)
        item = next(x for x in self.doc['assets'] if x['identity']['logical_id']=='provider-account-recovery')
        previous = c.asset_version(item)
        item['identity'].update(version=2,previous_version_sha256=previous)
        synchronize_members(self.doc)
        after = c.group_versions(self.doc)
        self.assertNotEqual(before['config'],after['config'])
        self.assertEqual(before['decryption'],after['decryption'])

    def test_degraded_retrieval_survives_other_route_revocation(self):
        for surviving,lost in (('A','B'),('B','A')):
            doc = descriptor()
            next(x for x in doc['copies'] if x['copy_id']==lost)['state'] = 'revoked'
            pin = pins(doc)
            receipt = proof(doc,pin,(surviving,))
            self.assertEqual(self.retrieve(receipt,observations(doc,receipt),doc=doc,pin=pin), 'AP07_DEGRADED_RETRIEVAL_VALID_ONLY')
            self.invalid(lambda:self.validate(doc,pin), 'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')
            receipt = proof(doc,pin,(lost,))
            self.invalid(lambda:self.retrieve(receipt,observations(doc,receipt),doc=doc,pin=pin), 'AP07_RETRIEVAL_NOT_PROVEN')
        for cp in self.doc['copies']:
            cp['state'] = 'revoked'
        self.pin = pins(self.doc)
        receipt = proof(self.doc,self.pin,('A',))
        self.invalid(lambda:self.retrieve(receipt,observations(self.doc,receipt)), 'AP07_RETRIEVAL_NOT_PROVEN')

    def test_recovery_tool_source_and_restored_runtime_source_are_distinct(self):
        self.doc['binding']['runtime']['source_sha'] = H('historical-app-source')[:40]
        self.doc['binding']['runtime']['source_tree'] = H('historical-app-tree')[:40]
        for item in self.doc['assets']:
            item['binding'] = copy.deepcopy(self.doc['binding'])
        self.assertEqual(self.validate(pin=pins(self.doc)),self.doc)
        self.invalid(self.validate,'AP07_SET_NOT_CURRENT_OR_INCOMPLETE')

    def test_safe_error_suppresses_upstream_exception_context(self):
        class PoisonedAsset(dict):
            def __getitem__(self, key):
                raise RuntimeError(CANARY)
        self.invalid(lambda:c.asset_version(PoisonedAsset()), 'AP07_DECLARATION_INVALID')
        with patch.object(c.dr,'canonical',side_effect=RuntimeError(CANARY)):
            self.invalid(lambda:c.group_versions(self.doc), 'AP07_DECLARATION_INVALID')

    def test_declaration_cli_never_emits_operational_pass_or_invalid_input(self):
        with tempfile.TemporaryDirectory(prefix='ap07-synthetic-') as tmp:
            path = Path(tmp).resolve()/'declaration.json'
            path.write_bytes(dr.canonical(self.doc))
            result = subprocess.run([sys.executable,str(ROOT/'scripts/v126-dr-custody.py'),str(path)],capture_output=True,check=False)
            self.assertEqual(result.returncode,0)
            self.assertEqual(result.stdout,b'AP07_DECLARATION_VALID_ONLY_NOT_CUSTODY_PASS\n')
            path.write_text(CANARY)
            result = subprocess.run([sys.executable,str(ROOT/'scripts/v126-dr-custody.py'),str(path)],capture_output=True,check=False)
            self.assertEqual(result.returncode,1)
            self.assertEqual(result.stderr,b'AP07_DECLARATION_INVALID\n')
            self.assertNotIn(CANARY.encode(),result.stdout+result.stderr)


if __name__=='__main__':
    unittest.main()
