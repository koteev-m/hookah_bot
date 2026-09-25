"""SYNTHETIC TEST ONLY. No keys, signatures, controller or operational authority.

This fixture substitutes independently acquired public reader bytes at the test
I/O boundary. Hashes/public-fingerprint-shaped strings are arbitrary identifiers;
passing the real consumer proves consistency checks, not real domain isolation.
"""
import copy
from datetime import datetime, timezone
import hashlib
import importlib.util
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location('v126_test_epoch', ROOT / 'scripts/v126-authority-epoch.py')
E = importlib.util.module_from_spec(_spec); sys.modules[_spec.name] = E; _spec.loader.exec_module(E)


def h(label):
    return hashlib.sha256(('SYNTHETIC_TEST_ONLY:' + label).encode()).hexdigest()


def timestamp(offset=0):
    return datetime.fromtimestamp(1790000000 + offset, timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


class Fixture:
    def __init__(self, root=None, target=None, source=None, tools=None, changes=None, extra_documents=()):
        self.root = Path(root or '/test-only-independent-v')
        self.changes = changes or {}
        self.documents = {}
        for doc in extra_documents: self.add(doc)
        self.source = source or dict(commit='a' * 40, tree='b' * 40, tooling_sha256='')
        self.source = copy.deepcopy(self.source)
        self.tools = tools or [dict(path='scripts/v126-authority-epoch.py', version='2', sha256=h('tool'))]
        self.source['tooling_sha256'] = self.add(self.tools)
        self.now = self.clock(0)
        self.observed = self.clock(-60)
        self.target = target or dict(path='/test-only/new-target', device=5, inode=1000, uid=0, host_fingerprint='SHA256:' + 'N' * 43)
        self.target = copy.deepcopy(self.target)
        self.build()

    def clock(self, delta=0):
        return dict(utc=timestamp(delta), error_seconds=1, monotonic_seconds=10000 + delta, clock_id=h('clock'))

    def add(self, doc):
        raw = E.canonical(doc); ref = E.digest(raw); self.documents[ref] = raw; return ref

    def fact(self, label):
        return self.add(dict(test_fixture='SYNTHETIC_TEST_ONLY', reference=label))

    def rec(self, kind, **fields):
        return dict(schema_version=2, kind=kind, **fields)

    def change(self, name, doc):
        if name in self.changes: self.changes[name](doc)
        return doc

    def retain(self, objects):
        manifest = self.add(self.rec('htqr-control-retention-manifest', epoch_id=self.epoch_id, objects=sorted(set(objects))))
        reads = []
        for route in self.policy['retention_routes']:
            reads.append(self.add(self.rec('htqr-control-retention-readback', epoch_id=self.epoch_id,
                retention_manifest_sha256=manifest, observed=self.observed,
                object_versions=[dict(sha256=ref, version='test-version-1') for ref in sorted(set(objects))], **route)))
        return manifest, reads

    def event(self, typ, **body):
        payload = self.add(self.rec('htqr-control-' + typ.lower().replace('_', '-'), **body))
        event = self.rec('htqr-control-event', epoch_id=self.epoch_id, sequence=len(self.events) + 1,
            previous_event_sha256=self.events[-1] if self.events else None, event_type=typ, payload_sha256=payload,
            actor_identity_sha256=self.policy['writer_identity_sha256'], method_sha256=self.policy['writer_method_sha256'],
            observed=self.observed, decision_provenance_sha256=self.decision)
        self.change('event_' + typ, event)
        ref = self.add(event); self.events.append(ref)
        manifest, reads = self.retain([ref, payload])
        transaction_id = 'test-transaction-' + str(len(self.events))
        publication = self.add(self.rec('htqr-control-publication', epoch_id=self.epoch_id, event_sha256=ref,
            transaction_id=transaction_id, durability='FILE_AND_DIRECTORY_FSYNC_READBACK',
            observer_identity_sha256=self.policy['retention_routes'][0]['observer_identity_sha256'], retention_manifest_sha256=manifest))
        self.transactions.append(dict(transaction_id=transaction_id, state='COMMITTED', event_sha256=ref,
            publication_sha256=publication, retention_readback_sha256=reads[0]))

    def build(self):
        self.decision = self.fact('owner-existing-decision')
        self.policy = self.change('policy', self.rec('authority-control-policy', writer_identity_sha256=h('c-writer'),
            writer_method_sha256=self.fact('c-method'), producer_identity_sha256=h('p-reader'),
            producer_method_sha256=self.fact('p-method'), controller_method_sha256=self.fact('controller-method'),
            controller_identities=[h('controller-' + x) for x in E.FACETS], verifier_identity_sha256=h('v'),
            custodian_identity_sha256=h('u'), retention_routes=[dict(route=x, controller_identity_sha256=h('retention-controller-' + x),
                observer_identity_sha256=h('retention-observer-' + x), failure_domain_sha256=h('failure-domain-' + x),
                method_sha256=self.fact('readback-method-' + x)) for x in ('A', 'B')]))
        self.policy_ref = self.add(self.policy)
        self.predecessor = self.change('predecessor', self.rec('authority-predecessor-index',
            legacy_domain_identity_sha256=h('old-domain'),
            legacy_target=dict(path='/opt/hookah-bot', device=64770, inode=553778, uid=0, host_fingerprint='SHA256:' + 'L' * 43),
            legacy_control_roots=[h('old-control')], legacy_resource_roots=[h('old-resource')],
            runs=[dict(run_id=E.F724, historical_outcome='UNKNOWN', retained_references=[self.fact('retained-724')], missing=['primary-result'], intent_sha256=None),
                dict(run_id=E.F782, historical_outcome=E.F782_STATE, retained_references=[self.fact('retained-f782')], missing=['primary-result'], intent_sha256=None)]))
        self.predecessor_ref = self.add(self.predecessor)
        self.domain = self.change('domain', self.rec('isolated-effect-domain', target=self.target, host_instance_sha256=h('new-host'),
            kernel_boot_sha256=h('new-boot'), resources={name: dict(identity_sha256=h('resource-' + name),
                controller_identity_sha256=h('controller-' + name), control_root_sha256=h('control-' + name)) for name in E.FACETS},
            external_policy='DISCONNECTED', predecessor_index_sha256=self.predecessor_ref))
        self.domain_ref = self.add(self.domain)
        self.epoch = self.change('epoch', self.rec('authority-epoch-descriptor', nonce=h('test-nonce-not-real'), created=self.observed,
            bootstrap_source=self.source, domain_identity_sha256=self.domain_ref, predecessor_index_sha256=self.predecessor_ref,
            owner_scope_decision_sha256=self.decision, control_policy_sha256=self.policy_ref))
        self.epoch_id = self.add(self.epoch)
        runtimes = {}
        for role in ('V', 'S'):
            runtimes[role] = self.add(self.change('runtime_' + role.lower(), self.rec('ap06-python-runtime', python_version='3.12.3',
                executable_path='/test-only/python3.12', executable_sha256=h('runtime-binary-' + role), role=role,
                closure_sha256=self.fact('runtime-closure-' + role), origin_sha256=self.fact('runtime-origin-' + role))))
        self.enrollment = self.change('enrollment', self.rec('ap06-prospective-enrollment-facts', epoch_id=self.epoch_id,
            domain_identity_sha256=self.domain_ref, principal_fingerprint='SHA256:' + 'P' * 43,
            principal_creation_record_sha256=self.fact('new-key-created-IN-TEST-ONLY'), possession_record_sha256=self.fact('possession-TEST-ONLY'),
            excluded_principal_fingerprints=['SHA256:' + 'A' * 43], host_fingerprint=self.target['host_fingerprint'],
            host_association_sha256=self.fact('host-association'), verifier_identity_sha256=h('v'), verifier_control_sha256=self.fact('v-control'),
            endpoint=dict(host='192.0.2.10', port=2226, user='root', peer='192.0.2.20'), source=self.source, python_version='3.12.3',
            verifier_runtime_sha256=runtimes['V'], server_runtime_sha256=runtimes['S'],
            enrollment_spec_sha256=self.fact('enrollment-spec'), observer_identity_sha256=h('p-reader'), observed=self.observed))
        self.enrollment_ref = self.add(self.enrollment)
        self.selection = self.change('selection', self.rec('prospective-protected-selection-policy', epoch_id=self.epoch_id,
            domain_identity_sha256=self.domain_ref, custodian_identity_sha256=h('u'), evaluator_identity_sha256=h('protected-evaluator'),
            method_sha256=self.fact('protected-method'), source_approval_sha256=self.fact('protected-origin-approval'),
            protected_version='test-protected-version', subset_version='test-subset-version',
            predicates=['SEMANTIC_DATABASE', 'BACKEND_CONSUMER', 'GUEST_ADMISSION', 'OWNER_MEMBERSHIP', 'MIX_VENUE', 'STAFF_LINKS', 'EXCLUSIONS']))
        self.selection_ref = self.add(self.selection)
        self.binding = self.change('binding', self.rec('ap06-bootstrap-binding', epoch_id=self.epoch_id, domain_identity_sha256=self.domain_ref,
            source_sha=self.source['commit'], source_tree=self.source['tree'], tooling_sha256=self.source['tooling_sha256'], python_version='3.12.3',
            enrollment_facts_sha256=self.enrollment_ref, protected_selection_policy_sha256=self.selection_ref, control_policy_sha256=self.policy_ref,
            producer_method_sha256=self.policy['producer_method_sha256'], isolation_method_sha256=self.fact('isolation-method'), clock_method_sha256=self.fact('clock-method')))
        self.binding_ref = self.add(self.binding)
        self.events = []; self.transactions = []
        self.event('EPOCH_ESTABLISHED', epoch_descriptor_sha256=self.epoch_id, domain_identity_sha256=self.domain_ref,
            predecessor_index_sha256=self.predecessor_ref, creation_fact_sha256=self.fact('real-controller-creation-IN-TEST-ONLY'), decision_provenance_sha256=self.decision)
        self.event('ENROLLMENT_FACTS_ACCEPTED', enrollment_facts_sha256=self.enrollment_ref)
        self.event('PRODUCER_FACTS_ACCEPTED', bootstrap_binding_sha256=self.binding_ref, protected_selection_policy_sha256=self.selection_ref,
            producer_control_sha256=self.fact('p-control'))
        self.anchor = self.change('anchor', self.rec('ap06-prospective-epoch-anchor', decision_id='test-decision', decision_provenance_sha256=self.decision,
            epoch_id=self.epoch_id, epoch_descriptor_sha256=self.epoch_id, domain_identity_sha256=self.domain_ref, predecessor_index_sha256=self.predecessor_ref,
            generation=1, revocation_generation=0, valid_from=timestamp(-120), valid_until=timestamp(3600), custodian_identity=h('u'), verifier_identity=h('v'),
            principal_fingerprint='SHA256:' + 'P' * 43, host_fingerprint=self.target['host_fingerprint'], deployment_target=self.target['path'],
            source_sha=self.source['commit'], source_tree=self.source['tree'], tooling_sha256=self.source['tooling_sha256'], python_version='3.12.3',
            bootstrap_binding_sha256=self.binding_ref, enrollment_spec_sha256=self.enrollment['enrollment_spec_sha256'],
            isolation_method_sha256=self.binding['isolation_method_sha256'], protected_selection_policy_sha256=self.selection_ref,
            sources={name: dict(identity=h({'catalogue':'c-reader','highwater':'highwater-reader','producer':'p-reader','clock':'clock-reader'}[name]),
                owner_uid=__import__('os').geteuid(), path=str(self.root / 'readers' / (name + '.json'))) for name in ('catalogue','highwater','producer','clock')},
            evidence_root=str(self.root / 'evidence'), scopes=list(E.SCOPES), initial_checkpoint=dict(control_sequence=3,
                control_head_sha256=self.events[-1], catalogue_generation=1, revocation_generation=0), clock_id=h('clock'), clock_method_sha256=self.binding['clock_method_sha256']))
        self.anchor_ref = self.add(self.anchor)
        retained, readbacks = self.retain(list(self.documents))
        approval = self.change('approval', self.rec('htqr-anchor-approval', epoch_id=self.epoch_id, anchor_sha256=self.anchor_ref,
            approver_identity_sha256=h('u'), scope=list(E.SCOPES), observed=self.observed, retention_manifest_sha256=retained, readbacks=readbacks))
        self.event('ANCHOR_APPROVED', anchor_sha256=self.anchor_ref, generation=1, approval_record_sha256=self.add(approval))
        activation = self.add(self.rec('ap06-prospective-activation', epoch_id=self.epoch_id, domain_identity_sha256=self.domain_ref,
            anchor_sha256=self.anchor_ref, enrollment_spec_sha256=self.enrollment['enrollment_spec_sha256'], enrollment_facts_sha256=self.enrollment_ref,
            configuration_sha256=self.fact('activated-exact-config'), observer_identity_sha256=h('p-reader'), observed=self.observed))
        self.event('ENROLLMENT_ACTIVATED', anchor_sha256=self.anchor_ref, enrollment_spec_sha256=self.enrollment['enrollment_spec_sha256'], activation_record_sha256=activation)
        protected = self.change('protected', self.rec('prospective-protected-observation', epoch_id=self.epoch_id, domain_identity_sha256=self.domain_ref,
            selection_policy_sha256=self.selection_ref, source=self.source, method_sha256=self.selection['method_sha256'],
            observer_identity_sha256=self.selection['evaluator_identity_sha256'], protected_version=self.selection['protected_version'], subset_version=self.selection['subset_version'],
            observed=self.observed, expires_at=timestamp(3600), results={x:dict(result='MATCH',evidence_sha256=self.fact('protected-'+x)) for x in
                ('semantic_database','backend_consumer','guest_admission','owner_membership','mix_venue','staff_links','exclusions')}))
        self.protected_ref = self.add(protected)
        self.proof = self.rec('target-effect-domain-isolation-proof', epoch_id=self.epoch_id, domain_identity_sha256=self.domain_ref,
            target_identity=self.target, source=self.source, method_sha256=self.binding['isolation_method_sha256'], controller_provenance_sha256=self.policy_ref,
            observer_identity_sha256=h('p-reader'), observed=self.observed, expires_at=timestamp(3600), facets={},
            accepted_base_state_sha256='', predecessor_index_sha256=self.predecessor_ref)
        for facet, classes in E.FACETS.items():
            resource = self.domain['resources'][facet]
            caps = [dict(edge_id=edge.lower().replace('_','-'),edge_class=edge,resource_sha256=resource['identity_sha256'],
                controller_identity_sha256=resource['controller_identity_sha256'],control_root_sha256=resource['control_root_sha256'],
                subject_domain_sha256=self.domain_ref, write_policy='DISCONNECTED' if facet=='external_effects' else 'EXCLUSIVE_NEW_DOMAIN',
                policy_sha256=self.fact('policy-'+edge)) for edge in classes]
            controller = self.change('controller_'+facet,self.rec('domain-controller-observation',epoch_id=self.epoch_id,domain_identity_sha256=self.domain_ref,
                source=self.source,method_sha256=self.policy['controller_method_sha256'],controller_identity_sha256=resource['controller_identity_sha256'],
                facet=facet,observed=self.observed,expires_at=timestamp(3600),enumeration='COMPLETE_INCOMING_CONTROL_CLOSURE',capabilities=caps,evidence_sha256=self.fact('controller-'+facet)))
            controller_ref = self.add(controller)
            probe = self.change('probe_'+facet,self.rec('domain-isolation-probe',epoch_id=self.epoch_id,domain_identity_sha256=self.domain_ref,
                source=self.source,method_sha256=self.binding['isolation_method_sha256'],observer_identity_sha256=h('p-reader'),facet=facet,
                observed=self.observed,expires_at=timestamp(3600),controller_record_sha256=controller_ref,
                probes=[dict(edge_id=x['edge_id'],result='OLD_DOMAIN_WRITE_DENIED',transcript_sha256=self.fact('probe-'+x['edge_id'])) for x in caps]))
            self.proof['facets'][facet]=dict(controller_record_sha256=controller_ref,observer_proof_sha256=self.add(probe))
        base = self.change('base',self.rec('prospective-base-state',epoch_id=self.epoch_id,domain_identity_sha256=self.domain_ref,target=self.target,
            source=self.source,data_origin_policy_sha256=self.fact('prospective-base-acceptance-NOT-DATA-TRANSFER'),control_import='NONE',business_egress='DISCONNECTED',
            observer_identity_sha256=h('p-reader'),observed=self.observed,expires_at=timestamp(3600)))
        self.proof['accepted_base_state_sha256']=self.add(base)
        self.change('proof',self.proof)
        acceptance=self.change('acceptance',self.rec('domain-isolation-acceptance',epoch_id=self.epoch_id,domain_identity_sha256=self.domain_ref,
            proof_payload_sha256=E.digest(self.proof),accepted_base_state_sha256=self.proof['accepted_base_state_sha256'],approver_identity_sha256=h('u'),
            decision_provenance_sha256=self.decision,observed=self.observed,expires_at=timestamp(3600)))
        self.proof['acceptance_record_sha256']=self.add(acceptance); self.proof_ref=self.add(self.proof)
        self.request=self.change('request',self.rec('prospective-isolated-genesis-request',run_id='test-prospective-genesis',source_sha=self.source['commit'],
            source_tree=self.source['tree'],script_sha256=h('script'),tooling_sha256=self.source['tooling_sha256'],python_version='3.12.3',target=self.target,
            epoch_id=self.epoch_id,domain_identity_sha256=self.domain_ref,predecessor_index_sha256=self.predecessor_ref,isolation_proof_sha256=self.proof_ref,
            next_init_manifest_sha256=h('next-exact-init'),created_at=timestamp(-120),expires_at=timestamp(3600),nonce=h('test-request-nonce')))
        self.request_ref=self.add(self.request)
        action=self.change('action_approval',self.rec('htqr-prospective-action-approval',epoch_id=self.epoch_id,domain_identity_sha256=self.domain_ref,
            request_sha256=self.request_ref,anchor_sha256=self.anchor_ref,scope=E.SCOPES[0],approver_identity_sha256=h('u'),valid_from=timestamp(-120),valid_until=timestamp(3600),observed=self.observed))
        self.event('ACTION_AUTHORIZED',request_sha256=self.request_ref,scope=E.SCOPES[0],approval_record_sha256=self.add(action))
        inventory=self.change('inventory',self.rec('prospective-operation-inventory',epoch_id=self.epoch_id,domain_identity_sha256=self.domain_ref,target=self.target,
            source=self.source,observed=self.observed,expires_at=timestamp(3600),observer_identity_sha256=h('p-reader'),root_state='ABSENT',completion_sha256=None,
            operation_ids=[],unresolved=[]))
        self.inventory_ref=self.add(inventory)
        self.finish()

    def finish(self, generation=1, revocation=0, revoked=None):
        revoked = revoked or []
        ledger=self.change('ledger',self.rec('htqr-control-ledger',epoch_id=self.epoch_id,epoch_descriptor_sha256=self.epoch_id,events=self.events,
            head_sequence=len(self.events),head_sha256=self.events[-1],transactions=self.transactions))
        self.ledger_ref=self.add(ledger)
        transactions=self.change('transactions',self.rec('htqr-control-transaction-inventory',epoch_id=self.epoch_id,
            observer_identity_sha256=h('p-reader'),observed=self.observed,expires_at=timestamp(3600),
            enumeration='COMPLETE_PERSISTENT_TRANSACTION_NAMESPACE',transactions=self.transactions))
        self.transactions_ref=self.add(transactions)
        retained,reads=self.retain(list(self.documents))
        checkpoint=self.change('checkpoint',self.rec('htqr-control-checkpoint',epoch_id=self.epoch_id,control_sequence=len(self.events),control_head_sha256=self.events[-1],
            catalogue_generation=generation,anchor_generation=self.anchor['generation'],revocation_generation=revocation,retention_manifest_sha256=retained,
            observer_identity_sha256=self.policy['retention_routes'][0]['observer_identity_sha256'],method_sha256=self.policy['retention_routes'][0]['method_sha256'],
            observed=self.observed,readbacks=reads))
        self.checkpoint_ref=self.add(checkpoint)
        self.catalogue=self.change('catalogue',self.rec('ap06-prospective-bootstrap-catalogue',epoch_id=self.epoch_id,source_identity=self.anchor['sources']['catalogue']['identity'],
            generation=generation,revocation_generation=revocation,observed=self.observed,expires_at=timestamp(3600),control_ledger_sha256=self.ledger_ref,control_sequence=len(self.events),
            control_head_sha256=self.events[-1],checkpoint_sha256=self.checkpoint_ref,bootstrap_binding_sha256=self.binding_ref,domain_identity_sha256=self.domain_ref,
            predecessor_index_sha256=self.predecessor_ref,documents=sorted(self.documents),revoked=revoked,
            actions=[dict(identity=E.request_identity(self.request),scope=E.SCOPES[0],valid_from=timestamp(-120),valid_until=timestamp(3600))],completion_sha256=None))
        self.highwater=self.change('highwater',self.rec('ap06-prospective-high-water',epoch_id=self.epoch_id,source_identity=self.anchor['sources']['highwater']['identity'],
            catalogue_generation=generation,anchor_generation=self.anchor['generation'],revocation_generation=revocation,control_sequence=len(self.events),control_head_sha256=self.events[-1],checkpoint_sha256=self.checkpoint_ref,
            observed=self.observed,expires_at=timestamp(3600)))
        self.observations=self.change('observations',self.rec('ap06-prospective-observations',epoch_id=self.epoch_id,source_identity=self.anchor['sources']['producer']['identity'],
            bootstrap_binding_sha256=self.binding_ref,source_sha=self.source['commit'],source_tree=self.source['tree'],tooling_sha256=self.source['tooling_sha256'],python_version='3.12.3',
            domain_identity_sha256=self.domain_ref,observed=self.observed,expires_at=timestamp(3600),observed_documents=sorted(self.documents),
            isolation_proof_sha256=self.proof_ref,protected_observation_sha256=self.protected_ref,enrollment_facts_sha256=self.enrollment_ref,new_epoch_inventory_sha256=self.inventory_ref,control_transactions_sha256=self.transactions_ref))
        self.clock_record=dict(schema_version=1,kind='ap06-clock-observation',source_identity=self.anchor['sources']['clock']['identity'],clock_id=h('clock'),
            measured=self.observed,expires_at=timestamp(3600),method_sha256=self.anchor['clock_method_sha256'])

    def validate(self, **kwargs):
        return E.validate_basis(self.request,self.anchor,self.catalogue,self.highwater,self.observations,self.documents,self.now,**kwargs)

    def promote_full(self, full_anchor, dr_binding, dr_documents, identity, scope='DISPATCH', completion_sha256=None):
        """Append explicitly synthetic full-U facts; does not validate DR for callers."""
        self.bootstrap_anchor = copy.deepcopy(self.anchor)
        self.documents.update(dr_documents)
        self.add(dr_binding)
        self.anchor = copy.deepcopy(full_anchor); self.anchor_ref = self.add(self.anchor)
        ledgers = [(ref, E.strict(raw)) for ref, raw in dr_documents.items() if isinstance(E.strict(raw), dict) and E.strict(raw).get('kind') == 'ledger']
        ledgers = [(ref, doc) for ref, doc in ledgers if doc['binding_sha256'] == E.digest(dr_binding)]
        if len(ledgers) != 1: raise ValueError('test fixture requires one exact DR ledger')
        dr_ref, ledger = ledgers[0]
        self.event('DR_BINDING_ATTACHED', binding_sha256=E.digest(dr_binding), source_identity_sha256=dr_binding['source_identity_sha256'],
            dr_ledger_sha256=dr_ref, dr_head_sha256=ledger['events'][-1])
        floor = self.anchor['revocation_generation']; revoked = []
        if floor:
            revoked = [E.digest(self.bootstrap_anchor)]
            self.event('REVOCATION_ADVANCED', revocation_generation=floor, revoked=revoked)
        retained, readbacks = self.retain(list(self.documents))
        approval = self.add(self.rec('htqr-anchor-approval', epoch_id=self.epoch_id, anchor_sha256=self.anchor_ref,
            approver_identity_sha256=h('u'), scope=self.anchor['scopes'], observed=self.observed, retention_manifest_sha256=retained, readbacks=readbacks))
        self.event('ANCHOR_APPROVED', anchor_sha256=self.anchor_ref, generation=self.anchor['generation'], approval_record_sha256=approval)
        activation = self.add(self.rec('ap06-prospective-activation', epoch_id=self.epoch_id, domain_identity_sha256=self.domain_ref,
            anchor_sha256=self.anchor_ref, enrollment_spec_sha256=self.enrollment['enrollment_spec_sha256'], enrollment_facts_sha256=self.enrollment_ref,
            configuration_sha256=self.fact('activated-exact-full-config'), observer_identity_sha256=h('p-reader'), observed=self.observed))
        self.event('ENROLLMENT_ACTIVATED', anchor_sha256=self.anchor_ref, enrollment_spec_sha256=self.enrollment['enrollment_spec_sha256'], activation_record_sha256=activation)
        self.action_identity = copy.deepcopy(identity); request_ref = self.add(identity)
        action = self.add(self.rec('htqr-prospective-action-approval', epoch_id=self.epoch_id, domain_identity_sha256=self.domain_ref,
            request_sha256=request_ref, anchor_sha256=self.anchor_ref, scope=scope, approver_identity_sha256=h('u'),
            valid_from=timestamp(-120), valid_until=timestamp(3600), observed=self.observed))
        self.event('ACTION_AUTHORIZED', request_sha256=request_ref, scope=scope, approval_record_sha256=action)
        completion_sha256 = completion_sha256 or h('durable-completion-SYNTHETIC')
        inventory = E.strict(self.documents[self.inventory_ref]); inventory.update(root_state='DURABLE_COMPLETION',
            completion_sha256=completion_sha256, operation_ids=[self.request['run_id']])
        self.inventory_ref = self.add(inventory)
        self.finish(generation=self.anchor['generation'],revocation=floor,revoked=revoked)
        self.catalogue['completion_sha256'] = completion_sha256
        self.catalogue['actions'] = [dict(identity=identity,scope=scope,valid_from=timestamp(-120),valid_until=timestamp(3600))]
        return self
