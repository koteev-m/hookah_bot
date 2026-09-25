#!/usr/bin/env python3
"""Production schema/control validator tests with explicit synthetic reader I/O."""
import copy
from dataclasses import FrozenInstanceError
import importlib.util
import io
import types
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('epoch_test_fixture', ROOT / 'scripts/fixtures/v126-authority-epoch-fixture.py')
fixture = importlib.util.module_from_spec(spec); spec.loader.exec_module(fixture)
Fixture, E, h, timestamp = fixture.Fixture, fixture.E, fixture.h, fixture.timestamp


class ProspectiveContracts(unittest.TestCase):
    def reject(self, changes):
        with self.assertRaises(ValueError): Fixture(changes=changes).validate()

    def test_valid_closed_prospective_domain(self):
        f = Fixture(); basis = f.validate()
        self.assertEqual(basis.epoch_id, E.digest(f.epoch))
        self.assertEqual(basis.control_sequence, 6)
        self.assertGreater(basis.headroom_seconds, 0)
        self.assertEqual(len(f.proof['facets']), 7)
        self.assertEqual(len(E.EDGES), 10)

    def test_stable_epoch_canonical_roundtrip(self):
        f = Fixture(); raw = E.canonical(f.epoch)
        self.assertEqual(E.strict(raw, E.EPOCH), f.epoch)
        self.assertEqual(E.digest(E.strict(raw)), f.epoch_id)
        self.assertTrue(raw.endswith(b'\n'))

    def test_bootstrap_u_canonicalization(self):
        f = Fixture(); self.assertEqual(E.strict(E.canonical(f.anchor), E.BOOTSTRAP_ANCHOR), f.anchor)
        self.assertEqual(E.validate_anchor(f.anchor), f.anchor)

    def test_verified_result_immutable(self):
        with self.assertRaises(FrozenInstanceError): Fixture().validate().epoch_id = h('forged')

    def test_durable_basis_roundtrip(self):
        f = Fixture(); durable = E.durable_basis(f.request, f.validate())
        self.assertEqual(E.validate_durable_basis(E.strict(E.canonical(durable)), f.request), durable)
        self.assertLess(len(E.canonical(durable)), 16 * 1024)
        self.assertEqual(durable['predecessor_index']['runs'][0]['historical_outcome'], 'UNKNOWN')
        self.assertEqual(durable['predecessor_index']['runs'][1]['historical_outcome'], E.F782_STATE)

    def test_request_identity_epoch_roundtrip(self):
        f = Fixture(); ident = E.request_identity(f.request)
        self.assertEqual(len(ident), 9); E.validate_request(f.request, identity=ident, target=f.target['path'])
        self.assertEqual(E.epoch_context(f.anchor), E.epoch_context(f.request))

    def revoke(self, fixture, value):
        fixture.event('REVOCATION_ADVANCED', revocation_generation=1, revoked=[value])
        fixture.finish(revocation=1, revoked=[value])
        return fixture

    def test_revoked_historical_predecessor_identity_remains_descriptive(self):
        f=Fixture(); legacy_identity=f.predecessor['legacy_control_roots'][0]
        self.revoke(f,legacy_identity)
        basis=f.validate()
        self.assertEqual(basis.control_sequence,7)
        self.assertEqual(basis.revocation_generation,1)
        self.assertEqual(E.strict(basis.predecessor_index_json.encode())['runs'][0]['historical_outcome'],'UNKNOWN')

    def test_coherent_unrelated_revocation_remains_valid(self):
        f=self.revoke(Fixture(),h('unrelated-expired-actor'))
        self.assertEqual(f.validate().revocation_generation,1)

    def test_same_host_new_epoch_rejected(self):
        self.reject({'domain': lambda d: d['target'].update(host_fingerprint='SHA256:' + 'L' * 43)})

    def test_same_control_root_rejected(self):
        self.reject({'domain': lambda d: d['resources']['storage'].update(control_root_sha256=h('old-control'))})

    def test_same_resource_rejected(self):
        self.reject({'domain': lambda d: d['resources']['database'].update(identity_sha256=h('old-resource'))})

    def test_old_724_cannot_authorize(self):
        self.reject({'request': lambda d: d.update(run_id=E.F724)})

    def test_old_f782_cannot_authorize(self):
        self.reject({'request': lambda d: d.update(run_id=E.F782)})

    def test_old_history_omission_rejected(self):
        self.reject({'predecessor': lambda d: d['runs'].pop()})

    def test_old_unknown_cannot_be_upgraded(self):
        self.reject({'predecessor': lambda d: d['runs'][0].update(historical_outcome='PASS')})

    def test_f782_cannot_be_native_terminal(self):
        self.reject({'predecessor': lambda d: d['runs'][1].update(historical_outcome='NATIVE_TERMINAL_PROVEN')})

    def test_source_commit_mismatch(self):
        self.reject({'request': lambda d: d.update(source_sha='c' * 40)})

    def test_source_tree_mismatch(self):
        self.reject({'observations': lambda d: d.update(source_tree='c' * 40)})

    def test_wrong_domain(self):
        self.reject({'request': lambda d: d.update(domain_identity_sha256=h('wrong-domain'))})

    def test_wrong_target(self):
        self.reject({'request': lambda d: d.update(target=dict(d['target'], inode=2000))})

    def test_wrong_runtime(self):
        self.reject({'enrollment': lambda d: d.update(python_version='3.13.2')})

    def test_wrong_principal(self):
        self.reject({'enrollment': lambda d: d.update(principal_fingerprint='SHA256:' + 'X' * 43)})

    def test_admin_key_reuse(self):
        self.reject({'enrollment': lambda d: d['excluded_principal_fingerprints'].append(d['principal_fingerprint'])})

    def test_wrong_endpoint(self):
        self.reject({'enrollment': lambda d: d['endpoint'].update(port=22)})

    def test_old_endpoint(self):
        self.reject({'enrollment': lambda d: d['endpoint'].update(host='178.20.209.5')})

    def test_missing_peer(self):
        self.reject({'enrollment': lambda d: d['endpoint'].pop('peer')})

    def test_stale_isolation(self):
        self.reject({'proof': lambda d: d.update(observed=Fixture.clock(None, -301))})

    def test_unknown_clock_error(self):
        self.reject({'proof': lambda d: d['observed'].update(error_seconds=0)})

    def test_producer_clock_over_bound(self):
        self.reject({'proof': lambda d: d.update(observed=dict(d['observed'], error_seconds=60))})

    def test_derived_clock_60_is_valid_within_window(self):
        f = Fixture(); f.now['error_seconds'] = 60; self.assertGreater(f.validate().headroom, 0)

    def test_clock_reboot_refused(self):
        f = Fixture(); f.now['clock_id'] = h('reboot')
        with self.assertRaises(ValueError): f.validate()

    def test_controller_proof_forgery(self):
        self.reject({'controller_storage': lambda d: d.update(controller_identity_sha256=h('caller'))})

    def test_cannot_self_attest_producer(self):
        self.reject({'policy': lambda d: d.update(producer_identity_sha256=d['writer_identity_sha256'])})

    def test_cannot_self_approve_u(self):
        self.reject({'approval': lambda d: d.update(approver_identity_sha256=h('p-reader'))})

    def test_synthetic_seed_not_truthful_first_event(self):
        self.reject({'event_EPOCH_ESTABLISHED': lambda d: d.update(event_type='SYNTHETIC_QUALIFIED')})

    def test_first_event_must_be_epoch_creation(self):
        self.reject({'event_EPOCH_ESTABLISHED': lambda d: d.update(event_type='ENROLLMENT_FACTS_ACCEPTED')})

    def test_mixed_ledger_epoch(self):
        self.reject({'event_ENROLLMENT_FACTS_ACCEPTED': lambda d: d.update(epoch_id=h('old-domain'))})

    def test_partial_ledger_transaction(self):
        self.reject({'ledger': lambda d: d['transactions'][0].update(state='PENDING')})

    def test_unpublished_event(self):
        self.reject({'ledger': lambda d: d['transactions'].pop()})

    def test_persistent_transaction_inventory_omission(self):
        self.reject({'transactions': lambda d: d.update(transactions=d['transactions'][:-1])})

    def test_checkpoint_fork(self):
        self.reject({'checkpoint': lambda d: d.update(control_head_sha256=h('fork'))})

    def test_checkpoint_revocation_rollback(self):
        self.reject({'highwater': lambda d: d.update(revocation_generation=1)})

    def test_checkpoint_current_anchor_mismatch(self):
        self.reject({'checkpoint': lambda d: d.update(anchor_generation=2)})

    def test_early_late_prefix_fork(self):
        f = Fixture(); checkpoint = dict(f.anchor['initial_checkpoint'], control_sequence=4, control_head_sha256=h('different-head'))
        with self.assertRaises(ValueError): f.validate(checkpoint=checkpoint)

    def test_early_late_monotone_prefix_valid(self):
        f = Fixture(); checkpoint = dict(f.anchor['initial_checkpoint'], control_sequence=4, control_head_sha256=f.events[3])
        self.assertEqual(f.validate(checkpoint=checkpoint).control_sequence, 6)

    def test_retention_route_not_independent(self):
        self.reject({'policy': lambda d: d['retention_routes'][0].update(observer_identity_sha256=d['writer_identity_sha256'])})

    def test_same_retention_failure_domain(self):
        self.reject({'policy': lambda d: d['retention_routes'][1].update(failure_domain_sha256=d['retention_routes'][0]['failure_domain_sha256'])})

    def test_bootstrap_u_cannot_be_full(self):
        f = Fixture()
        with self.assertRaises(ValueError): E.validate_full_anchor(f.anchor)
        with self.assertRaises(ValueError): E.validate_current(f.anchor,f.catalogue,f.highwater,f.observations,f.documents,f.now,E.request_identity(f.request),'DISPATCH')

    def test_bootstrap_scope_cannot_be_widened(self):
        self.reject({'anchor': lambda d: d['scopes'].append('DISPATCH')})

    def test_downgrade_schema_refused(self):
        self.reject({'request': lambda d: d.update(schema_version=1)})

    def test_forged_synthetic_isolation_marker(self):
        self.reject({'proof': lambda d: d.update(mode='synthetic')})

    def test_external_capability_attachment_refused(self):
        self.reject({'domain': lambda d: d.update(external_policy='CONNECTED')})

    def test_control_import_refused(self):
        self.reject({'base': lambda d: d.update(control_import='ADOPT_OLD')})

    def test_protected_subset_cannot_expand(self):
        self.reject({'protected': lambda d: d.update(subset_version='unapproved-new-subset')})

    def test_stale_protected_observation(self):
        self.reject({'protected': lambda d: d.update(expires_at=timestamp(800))})

    def test_unresolved_new_operation_refused(self):
        self.reject({'inventory': lambda d: d['unresolved'].append(h('unknown'))})

    def test_completed_root_cannot_repeat_genesis(self):
        self.reject({'inventory': lambda d: d.update(root_state='DURABLE_COMPLETION', completion_sha256=h('completed'))})

    def test_no_copy_without_separate_action(self):
        f=Fixture()
        with self.assertRaises(ValueError): f.validate(readback=True)

    def test_duplicate_json_key_refused(self):
        with self.assertRaises(ValueError): E.strict(b'{"schema_version":2,"schema_version":2}\n')

    def test_noncanonical_json_refused(self):
        with self.assertRaises(ValueError): E.strict(b'{ "schema_version": 2 }\n')

    def test_bool_as_integer_refused(self):
        self.reject({'request':lambda d:d['target'].update(device=True)})

    def test_invalid_plaintext_not_echoed(self):
        with self.assertRaises(ValueError) as raised: E.check('secret-credential', 'sha')
        self.assertNotIn('secret', str(raised.exception))

    def test_mutated_durable_epoch_refused(self):
        f=Fixture(); d=E.durable_basis(f.request,f.validate()); d['epoch']['epoch_id']=h('forged')
        with self.assertRaises(ValueError): E.validate_durable_basis(d,f.request)

    def test_unknown_request_field_refused(self):
        self.reject({'request':lambda d:d.update(accepted=True)})


def add_facet_tests():
    for name in E.FACETS:
        def omitted(self, facet=name): self.reject({'proof':lambda d:d['facets'].pop(facet)})
        def unknown(self, facet=name): self.reject({'controller_'+facet:lambda d:d['capabilities'][0].update(write_policy='UNKNOWN')})
        def shared(self, facet=name): self.reject({'controller_'+facet:lambda d:d['capabilities'][0].update(subject_domain_sha256=h('old-domain'))})
        def absence(self, facet=name): self.reject({'controller_'+facet:lambda d:d.update(capabilities=[])})
        def probe_omission(self, facet=name): self.reject({'probe_'+facet:lambda d:d['probes'].pop()})
        setattr(ProspectiveContracts,'test_omitted_facet_'+name,omitted)
        setattr(ProspectiveContracts,'test_unknown_capability_'+name,unknown)
        setattr(ProspectiveContracts,'test_shared_capability_'+name,shared)
        setattr(ProspectiveContracts,'test_absence_is_not_proof_'+name,absence)
        setattr(ProspectiveContracts,'test_incomplete_probe_'+name,probe_omission)

def add_revocation_tests():
    policy_paths = [(key,) for key in ('writer_identity_sha256','writer_method_sha256','producer_identity_sha256',
        'producer_method_sha256','controller_method_sha256','verifier_identity_sha256','custodian_identity_sha256')]
    policy_paths += [('controller_identities',i) for i in range(len(E.FACETS))]
    policy_paths += [('retention_routes',i,key) for i in range(2) for key in
        ('controller_identity_sha256','observer_identity_sha256','failure_domain_sha256','method_sha256')]
    for path in policy_paths:
        def test(self,path=path):
            f=Fixture();value=f.policy
            for key in path:value=value[key]
            self.revoke(f,value)
            with self.assertRaises(ValueError):f.validate()
        setattr(ProspectiveContracts,'test_revoked_active_policy_'+'_'.join(map(str,path)),test)
    # A coherent new ledger/head/checkpoint isolates revocation semantics from
    # malformed catalogue rejection. Every selected dependency is independently
    # revoked, including event publication and retention acknowledgements.
    for index,event_type,field in ((3,'u','approval_record_sha256'),(4,'activation','activation_record_sha256'),
                                  (5,'action','approval_record_sha256')):
        for dependency in ('event','payload',field,'publication','readback','retention'):
            def test(self,index=index,field=field,dependency=dependency):
                f=Fixture();event_ref=f.events[index];event=E.strict(f.documents[event_ref]);body=E.strict(f.documents[event['payload_sha256']])
                transaction=next(row for row in f.transactions if row['event_sha256']==event_ref)
                value={'event':event_ref,'payload':event['payload_sha256'],field:body[field],
                    'publication':transaction['publication_sha256'],'readback':transaction['retention_readback_sha256'],
                    'retention':E.strict(f.documents[transaction['publication_sha256']])['retention_manifest_sha256']}[dependency]
                self.revoke(f,value)
                with self.assertRaises(ValueError):f.validate()
            setattr(ProspectiveContracts,'test_revoked_current_'+event_type+'_'+dependency,test)


def add_provenance_tests():
    locations = ('anchor', 'event_EPOCH_ESTABLISHED', 'event_ENROLLMENT_FACTS_ACCEPTED',
        'event_PRODUCER_FACTS_ACCEPTED', 'event_ANCHOR_APPROVED', 'event_ENROLLMENT_ACTIVATED', 'event_ACTION_AUTHORIZED')
    for location in locations:
        for mode in ('missing','unselected','unobserved','forged','unretained'):
            def test(self,location=location,mode=mode):
                provenance={'test_fixture':'SYNTHETIC_TEST_ONLY','public_decision_reference':'independent-'+location}
                ref=E.digest(provenance)
                f=Fixture(changes={location:lambda d:d.update(decision_provenance_sha256=ref)},
                    extra_documents=() if mode in ('missing','unretained') else (provenance,))
                if mode=='unselected': f.catalogue['documents'].remove(ref)
                elif mode=='unobserved': f.observations['observed_documents'].remove(ref)
                elif mode=='forged': f.documents[ref]=E.canonical({'test_fixture':'forged replacement'})
                elif mode=='unretained':
                    f.documents[ref]=E.canonical(provenance)
                    f.catalogue['documents'].append(ref);f.observations['observed_documents'].append(ref)
                with self.assertRaises(ValueError): f.validate()
            setattr(ProspectiveContracts,'test_'+mode+'_decision_provenance_'+location,test)
        def positive(self,location=location):
            provenance={'test_fixture':'SYNTHETIC_TEST_ONLY','public_decision_reference':'independent-'+location}
            ref=E.digest(provenance)
            f=Fixture(changes={location:lambda d:d.update(decision_provenance_sha256=ref)},extra_documents=(provenance,))
            self.assertEqual(f.validate().control_sequence,6)
        setattr(ProspectiveContracts,'test_selected_observed_retained_decision_provenance_'+location,positive)


add_facet_tests()
add_revocation_tests()
add_provenance_tests()
def negative_controls():
    """Mutation controls run only in memory; production files remain unchanged."""
    source = (ROOT/'scripts/v126-authority-epoch.py').read_text()
    controls = [
        ('shared-control-root', "resource['control_root_sha256'] in predecessor['legacy_control_roots'] or ", "", 'test_same_control_root_rejected'),
        ('self-approved-U', "approval['approver_identity_sha256'] != anchor['custodian_identity'] or approval['scope']", "approval['scope']", 'test_cannot_self_approve_u'),
        ('partial-transaction', "'state': S.enum('COMMITTED')", "'state': S.enum('COMMITTED', 'PENDING')", 'test_partial_ledger_transaction'),
        ('event-provenance-closure', "        get(event['decision_provenance_sha256'])", "        pass  # TEST ONLY removed event provenance read", 'test_missing_decision_provenance_event_ACTION_AUTHORIZED'),
        ('active-revocation-closure', "    if (active_pins | active_control_refs) & revoked: refuse()", "    pass  # TEST ONLY removed active transitive revocation guard", 'test_revoked_active_policy_writer_identity_sha256'),
        ('retention-failure-domain', "unique(routes, 'failure_domain_sha256')", "None", 'test_same_retention_failure_domain'),
    ]
    original = fixture.E
    for name, before, after, test in controls:
        if source.count(before) != 1: raise AssertionError('negative control source boundary changed: '+name)
        module = types.ModuleType('v126_epoch_negative_'+name.replace('-','_'))
        module.__file__ = str(ROOT/'scripts/v126-authority-epoch.py'); sys.modules[module.__name__] = module
        exec(compile(source.replace(before,after,1),module.__file__,'exec'),module.__dict__)
        fixture.E = module
        try:
            result = unittest.TextTestRunner(stream=io.StringIO()).run(unittest.TestSuite([ProspectiveContracts(test)]))
        finally: fixture.E = original
        if result.testsRun != 1 or result.errors or len(result.failures) != 1:
            raise AssertionError('negative control did not expose removed protection: '+name)
        print('NEGATIVE_CONTROL_DETECTED '+name)
    print(str(len(controls))+' negative controls PASS; source unchanged')


if __name__ == '__main__':
    if '--negative-controls' in sys.argv:
        sys.argv.remove('--negative-controls'); negative_controls()
    else: unittest.main()
