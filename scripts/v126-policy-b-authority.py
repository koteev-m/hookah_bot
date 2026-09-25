#!/usr/bin/env python3
"""Attended AP-06 acquisition from previously enrolled independent sources.

There is no enrollment, producer, SSH, plugin or credentials operation here. The
root is an independently retained U decision entered at the foreground console;
C confirms currentness, whereas independently enrolled P records supply machine
observations. File permissions protect those already enrolled sources; they do
not establish their provenance. Missing sources never fall back to readiness.
"""
import copy
from dataclasses import dataclass
from datetime import datetime, timezone
import importlib.util
import math
import os
from pathlib import Path
import re
import secrets
import select
import stat
import sys
import time

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result

adapter = sys.modules.get('v126_policy_b_dispatch') or load('v126_policy_b_dispatch', 'v126-policy-b-dispatch.py')
dr, S = adapter.dr, adapter.dr.schema
custody = load('v126_authority_custody', 'v126-dr-custody.py')
genesis = load('v126_legacy_genesis', 'v126-legacy-genesis.py')
MAX_BYTES = 16 * 1024 * 1024
EXPIRY_KEYS = frozenset(('checkpoint', 'ongoing_observed', 'monitor', 'authorization',
    'response_authority', 'cadence', 'age_state', 'custody_retrieval', 'rpo', 'anchor', 'clock', 'source_observations'))
IDENTITY = {'run_id': 'id', 'release_sha': 'git', 'script_sha256': 'sha', 'intent_sha256': 'sha',
            'kind': S.enum('INIT', 'STAGE', 'COPY_ONLY', 'TARGET_BIND'), 'name': 'version', 'action': 'id'}
ANCHOR_KEYS = frozenset(('schema_version', 'kind', 'decision_id', 'decision_provenance_sha256',
    'generation', 'valid_from', 'valid_until', 'revocation_generation', 'custodian_identity',
    'verifier_identity', 'principal_fingerprint', 'host_fingerprint', 'deployment_target',
    'source_sha', 'source_tree', 'tooling_sha256', 'python_version', 'binding_sha256',
    'target_sha256', 'source_identity_sha256', 'database_semantics_sha256', 'data_runtime',
    'restore_runtime', 'post_v126_recipe_sha256', 'sources', 'evidence_root', 'scopes',
    'initial_checkpoint', 'clock_id', 'clock_method_sha256'))
CATALOGUE_SPEC = S.record('ap06-current-catalogue', {
    'source_identity': 'sha', 'generation': 'positive', 'revocation_generation': 'uint',
    'observed': S.CLOCK, 'expires_at': 'time', 'ledger_sha256': 'sha',
    'ledger_sequence': 'positive', 'ledger_head_sha256': 'sha',
    'latest_attempt_outcome': S.enum('PENDING','UNKNOWN','FAILED','QUALIFIED'),
    'authorizations': S.array('sha'), 'responses': S.array('sha'),
    'revoked': S.array('sha', 0), 'ongoing_sha256': 'sha', 'readiness_sha256': 'sha',
    'custody_set_sha256': 'sha', 'custody_set_id': 'id', 'custody_set_version': 'positive',
    'custody_binding_sha256': 'sha', 'custody_current_versions': S.array({'logical_id':'id', 'sha256':'sha'}),
    'custody_required_versions': S.array('sha'), 'documents': S.array('sha'),
    'actions': S.array({'identity': IDENTITY, 'scope': S.enum('DISPATCH', 'COPY_ONLY', 'LEGACY_GENESIS', 'LEGACY_GENESIS_COPY'),
                      'valid_from': 'time', 'valid_until': 'time'})})
GENESIS_CATALOGUE_SPEC = dict(CATALOGUE_SPEC, kind=S.enum('ap06-legacy-genesis-catalogue'),
    legacy_inventory_sha256='sha', legacy_completion_sha256=S.optional('sha'))
HIGHWATER_SPEC = S.record('ap06-high-water', {'source_identity':'sha', 'catalogue_generation':'positive',
    'revocation_generation':'uint', 'ledger_sequence':'positive', 'ledger_head_sha256':'sha',
    'observed':S.CLOCK, 'expires_at':'time'})
CLOCK_SPEC = S.record('ap06-clock-observation', {'source_identity':'sha', 'clock_id':'sha',
    'measured':S.CLOCK, 'expires_at':'time', 'method_sha256':'sha'})
P_SPEC = S.record('ap06-producer-observations', {
    'source_identity':'sha', 'binding_sha256':'sha', 'source_sha':'git', 'source_tree':'git',
    'tools_sha256':'sha', 'python_version':'version', 'target_sha256':'sha',
    'data_runtime':S.RUNTIME, 'restore_runtime':S.RUNTIME, 'observed':S.CLOCK, 'expires_at':'time',
    'observed_evidence':S.array('sha'), 'ongoing_sha256':'sha',
    'availability':S.array({'qualification_sha256':'sha', 'offhost_sha256':'sha',
        'version_id':'object-version', 'ciphertext':S.FILE, 'readback':S.FILE, 'downloaded_archive':S.FILE,
        'asset_versions':S.array('sha')}),
    'retrieval':{'proof_sha256':'sha','environment_sha256':'sha','topology_sha256':'sha',
        'packages':S.array({'copy_id':S.enum('A','B'),'sha256':'sha','size':'positive'}),
        'completed':S.array(custody.RETRIEVAL), 'observed':S.CLOCK}})


def refuse():
    raise ValueError('POLICY_B_AUTHORITY_REFUSED') from None


def strict(raw, spec=None):
    if type(raw) is not bytes or not 0 < len(raw) <= MAX_BYTES:
        refuse()
    doc = dr.database.strict_json(raw)
    if dr.canonical(doc) != raw:
        refuse()
    if spec is not None:
        S.check(doc, spec)
    return doc


def canonical_path(value):
    if type(value) is not str or not value.startswith('/') or len(value) > 4096 or '\x00' in value:
        refuse()
    path = Path(value)
    if str(path.resolve(strict=True)) != value:
        refuse()
    return path


def protected_read(path, owner, limit=MAX_BYTES):
    path = canonical_path(str(path))
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != owner or info.st_mode & 0o022:
        refuse()
    # A source enrolled on a writable untrusted parent is not the enrolled object.
    for parent in (path.parent, *path.parents):
        mode = parent.stat().st_mode
        if mode & 0o022 and not mode & stat.S_ISVTX:
            refuse()
    return dr.read_regular(path, limit)


class ForegroundConsole:
    """Independent /dev/tty only; stdin, environment and supplied files are inert."""
    def ask(self, prompt, deadline):
        fd = os.open('/dev/tty', os.O_RDWR | os.O_NOCTTY)
        try:
            if not os.isatty(fd) or os.tcgetpgrp(fd) != os.getpgrp():
                refuse()
            os.write(fd, prompt.encode('ascii'))
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([fd], [], [], remaining)[0]:
                refuse()
            answer = os.read(fd, 257)
            if len(answer) > 256 or not answer.endswith(b'\n'):
                refuse()
            return answer.decode('ascii').strip()
        finally:
            os.close(fd)


@dataclass(frozen=True)
class PreviouslyApprovedAnchor:
    document: dict
    sha256: str
    raw: bytes
    path: str
    owner_uid: int


def validate_anchor(doc):
    if type(doc) is not dict or set(doc) not in (ANCHOR_KEYS, ANCHOR_KEYS | {'legacy_genesis'}) or type(doc['schema_version']) is not int or doc['schema_version'] != 1 or doc['kind'] != 'ap06-previously-approved-anchor':
        refuse()
    for field in ('decision_id',): S.check(doc[field], 'id')
    for field in ('decision_provenance_sha256', 'custodian_identity', 'verifier_identity', 'tooling_sha256',
                  'binding_sha256','target_sha256','source_identity_sha256','database_semantics_sha256',
                  'post_v126_recipe_sha256','clock_id','clock_method_sha256'): S.check(doc[field], 'sha')
    for field in ('source_sha','source_tree'): S.check(doc[field], 'git')
    S.check(doc['generation'], 'positive'); S.check(doc['revocation_generation'], 'uint')
    S.check(doc['python_version'], 'version'); S.check(doc['data_runtime'], S.RUNTIME); S.check(doc['restore_runtime'], S.RUNTIME)
    for field in ('valid_from','valid_until'): S.check(doc[field], 'time')
    for field in ('principal_fingerprint','host_fingerprint'):
        if type(doc[field]) is not str or not re.fullmatch(r'SHA256:[A-Za-z0-9+/]{43}', doc[field]): refuse()
    target = doc['deployment_target']
    if type(target) is not str or not target.startswith('/') or str(Path(target)) != target or '..' in Path(target).parts or len(target)>4096: refuse()
    canonical_path(doc['evidence_root'])
    if type(doc['sources']) is not dict or set(doc['sources']) != {'catalogue','highwater','producer','clock'}: refuse()
    paths = []
    for value in doc['sources'].values():
        if type(value) is not dict or set(value) != {'identity','owner_uid','path'}: refuse()
        S.check(value['identity'],'sha'); S.check(value['owner_uid'],'uint')
        paths.append(str(canonical_path(value['path'])))
    if len({x['identity'] for x in doc['sources'].values()}) != len(paths): refuse()
    if len(paths) != len(set(paths)) or any(Path(x).is_relative_to(Path(doc['evidence_root'])) for x in paths): refuse()
    S.check(doc['scopes'], S.array(S.enum('DISPATCH','COPY_ONLY','LEGACY_GENESIS','LEGACY_GENESIS_COPY')))
    if len(set(doc['scopes'])) != len(doc['scopes']): refuse()
    if any(x in doc['scopes'] for x in ('LEGACY_GENESIS','LEGACY_GENESIS_COPY')):
        if 'legacy_genesis' not in doc: refuse()
        genesis.check(doc['legacy_genesis'], genesis.PINS)
    elif 'legacy_genesis' in doc: refuse()
    S.check(doc['initial_checkpoint'], {'catalogue_generation':'positive','ledger_sequence':'positive','ledger_head_sha256':'sha','revocation_generation':'uint'})


def load_enrolled_authority(anchor_path, console=None):
    """Read a prior approval; do not create one or show a candidate pin to copy."""
    console = console or ForegroundConsole()
    raw = protected_read(anchor_path, os.geteuid())
    doc = strict(raw); validate_anchor(doc)
    answer = console.ask('U: enter the independently retained previously approved anchor SHA-256 (no new enrollment): ', time.monotonic()+300)
    S.check(answer, 'sha')
    if answer != dr.sha(raw): refuse()
    return PreviouslyApprovedAnchor(copy.deepcopy(doc), answer, raw, str(anchor_path), os.geteuid())


class VVerifier:
    """Production readers are fixed and read-only; fixture injection is external I/O."""
    def __init__(self, enrollment, native_verifier, console=None, native_genesis_verifier=None):
        if type(enrollment) is not PreviouslyApprovedAnchor: refuse()
        validate_anchor(enrollment.document)
        if dr.canonical(enrollment.document) != enrollment.raw or dr.sha(enrollment.raw) != enrollment.sha256: refuse()
        self.anchor = copy.deepcopy(enrollment)
        self.doc = self.anchor.document
        self.console = console or ForegroundConsole()
        self.native_verifier = native_verifier
        self.native_genesis_verifier = native_genesis_verifier
        self.genesis_request = None
        self.seen = set()
        self.sessions = {}
        self.previous_clock = None
        self.minimum_generation = self.doc['initial_checkpoint']['catalogue_generation']
        self.minimum_revocation = max(self.doc['revocation_generation'], self.doc['initial_checkpoint']['revocation_generation'])
        self.checkpoint = dict(self.doc['initial_checkpoint'])
        self.last_validation = None
        self.round_snapshot = None
        self.source_bytes = {}

    def source(self, name, spec):
        pin = self.doc['sources'][name]
        raw = protected_read(pin['path'], pin['owner_uid'])
        self.source_bytes[name] = raw
        if sum(map(len,self.source_bytes.values())) + len(self.anchor.raw) > MAX_BYTES: refuse()
        parsed = strict(raw, spec)
        if parsed['source_identity'] != pin['identity']: refuse()
        return raw, parsed

    def now(self):
        raw, clock = self.source('clock', CLOCK_SPEC)
        if clock['clock_id'] != self.doc['clock_id'] or clock['measured']['clock_id'] != self.doc['clock_id'] or clock['method_sha256'] != self.doc['clock_method_sha256']: refuse()
        wall, mono = time.time(), time.monotonic()
        measured = clock['measured']
        error = measured['error_seconds']
        # A concrete enrolled synchronization measurement binds this machine's
        # wall and monotonic clocks. Reading it does not renew its measurement.
        utc = S.timestamp(measured['utc'])
        delta = mono - measured['monotonic_seconds']
        if not 0 <= delta <= 300 or abs((wall-utc)-delta) > error or wall+error >= S.timestamp(clock['expires_at']): refuse()
        now = dict(utc=datetime.fromtimestamp(math.floor(wall),timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
                   error_seconds=error+1, monotonic_seconds=math.floor(mono), clock_id=clock['clock_id'])
        S.check(now,S.CLOCK)
        if self.previous_clock is not None: dr.chronology(self.previous_clock,now)
        self.previous_clock = now
        self.clock_record = clock
        return now

    def unexpired(self, start, expiry, now):
        if S.timestamp(start) > S.timestamp(now['utc'])-now['error_seconds'] or S.timestamp(expiry) <= S.timestamp(now['utc'])+now['error_seconds']: refuse()

    def recheck_sources(self):
        if protected_read(self.anchor.path,self.anchor.owner_uid) != self.anchor.raw: refuse()
        if self.round_snapshot:
            for name, raw in self.round_snapshot.items():
                pin=self.doc['sources'][name]
                if protected_read(pin['path'],pin['owner_uid']) != raw: refuse()

    def acquire_catalogue(self, nonce, identity, scope):
        self.source_bytes = {}
        now = self.now()
        self.unexpired(self.doc['valid_from'],self.doc['valid_until'],now)
        catraw, cat = self.source('catalogue', GENESIS_CATALOGUE_SPEC if scope in ('LEGACY_GENESIS','LEGACY_GENESIS_COPY') else CATALOGUE_SPEC)
        highraw, high = self.source('highwater',HIGHWATER_SPEC)
        self.round_snapshot={'catalogue':catraw,'highwater':highraw}
        for observed in (cat,high):
            if dr.age(observed['observed'],now) > 300 or S.timestamp(observed['expires_at']) <= S.timestamp(now['utc'])+now['error_seconds']: refuse()
        if (cat['generation'] < self.minimum_generation or cat['revocation_generation'] < self.minimum_revocation
                or high['catalogue_generation'] != cat['generation'] or high['revocation_generation'] != cat['revocation_generation']
                or high['ledger_sequence'] != cat['ledger_sequence'] or high['ledger_head_sha256'] != cat['ledger_head_sha256']): refuse()
        if set(cat['revoked']) & {self.anchor.sha256,self.doc['verifier_identity'],self.doc['custodian_identity'],self.doc['clock_method_sha256'],*[x['identity'] for x in self.doc['sources'].values()]}: refuse()
        if scope not in self.doc['scopes']: refuse()
        matching=[x for x in cat['actions'] if x['identity']==identity and x['scope']==scope]
        if len(matching)!=1: refuse()
        self.unexpired(matching[0]['valid_from'],matching[0]['valid_until'],now)
        # Display only schema-constrained public identifiers. This is currentness,
        # never a prompt to declare storage, restore or credentials available.
        prompt=('C '+self.doc['custodian_identity']+': confirm current complete catalogue including unresolved attempts/revocations; source '+cat['source_identity']+
            ' sequence '+str(cat['ledger_sequence'])+' head '+cat['ledger_head_sha256']+' latest '+cat['latest_attempt_outcome']+' revocation '+str(cat['revocation_generation'])+
            ' expires '+cat['expires_at']+'; type nonce '+nonce+': ')
        if self.console.ask(prompt,time.monotonic()+300) != nonce: refuse()
        self.recheck_sources()
        self.catalogue,self.highwater,self.action_authority=cat,high,matching[0]
        return cat,now

    def authorize_action(self, identity, target=None, scope='COPY_ONLY'):
        """COPY_ONLY has independent approval/currentness, without R0/readiness."""
        S.check(identity,IDENTITY)
        if target is not None and str(target)!=self.doc['deployment_target']: refuse()
        if identity['release_sha'] != self.doc['source_sha'] or scope not in ('COPY_ONLY','DISPATCH'): refuse()
        with adapter.bounded(300):
            nonce=secrets.token_hex(32)
            self.acquire_catalogue(nonce,identity,scope)
            binding=self.source_binding()
            if identity['script_sha256'] != next(x['sha256'] for x in binding['tools'] if x['path']=='scripts/v126-cutover.sh'): refuse()
            self.recheck_sources()
            now=self.now()
            self.unexpired(self.action_authority['valid_from'],self.action_authority['valid_until'],now)
            expiry=min(self.remaining(self.action_authority['valid_until'],now),self.remaining(self.doc['valid_until'],now),
                300-dr.age(self.catalogue['observed'],now),self.remaining(self.catalogue['expires_at'],now),
                300-dr.age(self.highwater['observed'],now),self.remaining(self.highwater['expires_at'],now),
                300-dr.age(self.clock_record['measured'],now),self.remaining(self.clock_record['expires_at'],now))
            if not math.isfinite(expiry) or expiry<=0: refuse()
            return {'anchor_sha256':self.anchor.sha256,'catalogue_head_sha256':self.catalogue['ledger_head_sha256'],
                    'revocation_generation':self.catalogue['revocation_generation'],'expiry':expiry}

    def challenge(self, value):
        expected={'version','type','session_id','nonce','sequence','phase','operation_id','identity','target','timeout','history_sha256',
                  'anchor_sha256','anchor_generation','manifest_sha256','source_tree','tooling_sha256','runtime','principal_fingerprint','host_fingerprint','native_history'}
        if type(value) is dict and type(value.get('identity')) is dict and value['identity'].get('kind')=='TARGET_BIND':
            expected |= {'genesis_mode','genesis_completion_sha256'}
        if type(value) is not dict or set(value)!=expected or value['version']!=1 or value['type']!='CHALLENGE': refuse()
        S.check(value['identity'],IDENTITY)
        for key in ('session_id','nonce','operation_id','history_sha256','anchor_sha256','tooling_sha256'): S.check(value[key],'sha')
        S.check(value['manifest_sha256'],S.optional('sha')); S.check(value['source_tree'],'git')
        if value['sequence'] not in (1,2) or type(value['sequence']) is not int or value['phase'] != ('EARLY' if value['sequence']==1 else 'LATE'): refuse()
        identity=value['identity']
        if (dr.digest(identity)!=value['operation_id'] or identity['release_sha']!=self.doc['source_sha']
                or value['target']!=self.doc['deployment_target'] or value['anchor_sha256']!=self.anchor.sha256
                or value['anchor_generation']!=self.doc['generation'] or value['source_tree']!=self.doc['source_tree']
                or value['tooling_sha256']!=self.doc['tooling_sha256'] or value['runtime']!=self.doc['python_version']
                or value['principal_fingerprint']!=self.doc['principal_fingerprint'] or value['host_fingerprint']!=self.doc['host_fingerprint']): refuse()
        native_history=value['native_history']
        if type(native_history) is not list or len(native_history)>20: refuse()
        for row in native_history:
            if type(row) is not dict or set(row)!={'identity','request_sha256','result_sha256','log_sha256','completed_at','artifacts'}: refuse()
            S.check(row['identity'],IDENTITY)
            for key in ('request_sha256','result_sha256','log_sha256'): S.check(row[key],'sha')
            # Existing operation history uses datetime.isoformat(), whereas AP01
            # documents use seconds + Z. Keep the native bytes and its UTC format.
            stamp=row['completed_at']
            if type(stamp) is not str or len(stamp)>40 or not re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?(?:Z|\+00:00)',stamp): refuse()
            parsed=datetime.fromisoformat(stamp.replace('Z','+00:00'))
            if parsed.utcoffset().total_seconds()!=0: refuse()
            S.check(row['artifacts'],S.array({'name':'id','sha256':'sha'},0))
        is_r0=identity['name'] in ('BASELINE_VERIFIED','RUN_INITIALIZED','LEGACY_GENESIS')
        if (is_r0 and native_history) or (not is_r0 and not 1<=len(native_history)<=20): refuse()
        if identity['kind']=='INIT':
            if identity['name']!='RUN_INITIALIZED' or identity['action']!='initialize-run' or value['manifest_sha256'] is None: refuse()
        elif identity['kind']=='TARGET_BIND':
            if identity['name']!='LEGACY_GENESIS' or identity['action']!='bind-legacy-target' or value['manifest_sha256']!=identity['intent_sha256']: refuse()
            if value['genesis_mode'] not in ('EXECUTE','GENESIS_READBACK'): refuse()
            S.check(value['genesis_completion_sha256'], S.optional('sha'))
            if (value['genesis_mode']=='EXECUTE') != (value['genesis_completion_sha256'] is None): refuse()
        elif identity['kind']!='STAGE': refuse()
        if value['timeout']!=adapter.ACTION_SECONDS.get(identity['action'],300) or type(value['timeout']) is not int: refuse()
        if value['nonce'] in self.seen: refuse()
        self.seen.add(value['nonce'])
        session=self.sessions.get(value['session_id'])
        binding={k:v for k,v in value.items() if k not in ('nonce','sequence','phase','history_sha256')}
        if value['sequence']==1:
            if session is not None: refuse()
            self.sessions[value['session_id']]={'binding':binding,'admitted':False}
        elif session is None or session['binding']!=binding or session['admitted'] is not True: refuse()
        else: del self.sessions[value['session_id']]
        return adapter.Context(identity['run_id'],identity['release_sha'],identity['script_sha256'],identity['name'],identity['action'],value['target'],value['timeout'])

    def source_binding(self):
        raw=dr.read_regular(Path(self.doc['evidence_root'])/self.doc['binding_sha256'],MAX_BYTES)
        if dr.sha(raw)!=self.doc['binding_sha256']: refuse()
        binding=dr.parse(raw,'binding')
        if (dr.digest(binding['tools'])!=self.doc['tooling_sha256'] or binding['python_version']!=self.doc['python_version']
                or any(binding[k]!=self.doc[k] for k in ('source_sha','source_tree','target_sha256','source_identity_sha256','database_semantics_sha256','data_runtime','restore_runtime'))): refuse()
        dr.verify_checkout(binding,ROOT,require_clean=True)
        return binding

    def acquire(self, context):
        cat=self.catalogue
        documents={}
        total=sum(map(len,self.source_bytes.values()))+len(self.anchor.raw)
        for ref in cat['documents']:
            S.check(ref,'sha')
            if ref in documents: refuse()
            raw=dr.read_regular(Path(self.doc['evidence_root'])/ref,MAX_BYTES)
            total+=len(raw)
            if total>MAX_BYTES or dr.sha(raw)!=ref: refuse()
            documents[ref]=raw
        evidence=dr.Evidence(documents)
        binding=evidence.get(self.doc['binding_sha256'],'binding')
        if (dr.digest(binding['tools'])!=self.doc['tooling_sha256'] or binding['python_version']!=self.doc['python_version']
                or any(binding[k]!=self.doc[k] for k in ('source_sha','source_tree','target_sha256','source_identity_sha256','database_semantics_sha256','data_runtime','restore_runtime'))): refuse()
        dr.verify_checkout(binding,ROOT,require_clean=True)
        ledger=evidence.get(cat['ledger_sha256'],'ledger')
        cp=self.checkpoint
        if (ledger['head_sequence']!=cat['ledger_sequence'] or ledger['events'][-1]!=cat['ledger_head_sha256']
                or cp['ledger_sequence']>len(ledger['events']) or ledger['events'][cp['ledger_sequence']-1]!=cp['ledger_head_sha256']
                or cat['revocation_generation']<cp['revocation_generation']): refuse()
        praw,producer=self.source('producer',P_SPEC)
        self.round_snapshot['producer']=praw
        now=self.now()
        if (producer['binding_sha256']!=self.doc['binding_sha256'] or producer['tools_sha256']!=self.doc['tooling_sha256']
                or any(producer[k]!=self.doc[k] for k in ('source_sha','source_tree','python_version','target_sha256','data_runtime','restore_runtime'))
                or dr.age(producer['observed'],now)>300 or self.remaining(producer['expires_at'],now)<=0
                or producer['ongoing_sha256']!=cat['ongoing_sha256']): refuse()
        revoked=set(cat['revoked'])
        auths=frozenset(cat['authorizations']); responses=frozenset(cat['responses'])
        observed=frozenset(producer['observed_evidence'])
        if any(len(xs)!=len(set(xs)) for xs in (cat['authorizations'],cat['responses'],producer['observed_evidence'])): refuse()
        if revoked & (auths|responses|observed|{cat['ongoing_sha256'],cat['custody_set_sha256'],cat['ledger_sha256']}): refuse()
        cpins=custody.CustodyPins(cat['custody_set_sha256'],cat['custody_set_id'],cat['custody_set_version'],cat['custody_binding_sha256'],
            tuple(sorted((x['logical_id'],x['sha256']) for x in cat['custody_current_versions'])),frozenset(cat['custody_required_versions']),cat['observed'])
        r=producer['retrieval']
        robs=custody.RetrievalObservations(r['proof_sha256'],r['environment_sha256'],r['topology_sha256'],
            frozenset((x['copy_id'],x['sha256'],x['size']) for x in r['packages']),
            frozenset((x['copy_id'],x['asset_version_sha256'],x['operation'],x['operation_evidence_sha256']) for x in r['completed']),r['observed'])
        setraw,proofraw=evidence.raw(cpins.set_sha256),evidence.raw(robs.proof_sha256)
        if custody.validate_retrieval(setraw,proofraw,cpins,robs,now)!='AP07_RETRIEVAL_OBSERVATIONS_VALID_ONLY': refuse()
        setdoc=custody.validate_set(setraw,cpins,now)
        groups=custody.group_versions(setdoc)
        custodydoc=evidence.get(binding['custody_sha256'],'custody')
        if custodydoc['versions']!=groups: refuse()
        required=set(cpins.required_versions)|{x[1] for x in cpins.current_versions}
        available=set()
        for item in producer['availability']:
            qref=item['qualification_sha256']
            q=evidence.get(qref,'qualification'); off=evidence.get(q['offhost_sha256'],'offhost')
            if (qref in available or qref in revoked or q['offhost_sha256']!=item['offhost_sha256']
                    or any(off[k]!=item[k] for k in ('version_id','ciphertext','readback','downloaded_archive'))
                    or off['ciphertext']!=off['readback'] or not required<=set(item['asset_versions'])
                    or not set(item['asset_versions'])<=required): refuse()
            available.add(qref)
        trust=dr.Trust(self.doc['binding_sha256'],cat['ledger_sha256'],auths,frozenset(groups.values()),
            frozenset(available),observed,responses,cat['observed'],True,cat['ongoing_sha256'])
        purpose='preparation' if context.stage in ('RUN_INITIALIZED','BASELINE_VERIFIED') else 'cutover-Q'
        selected=dr.select_point(evidence,trust,now,purpose)
        if cat['latest_attempt_outcome']!='QUALIFIED': refuse()
        pins=adapter.Pins(context.run_id,self.doc['deployment_target'],self.doc['source_sha'],self.doc['source_tree'],
            self.doc['target_sha256'],self.doc['source_identity_sha256'],self.doc['database_semantics_sha256'],
            self.doc['data_runtime'],self.doc['restore_runtime'],selected['intent']['attempt_id'],self.doc['post_v126_recipe_sha256'])
        self.producer,self.retrieval_proof,self.selected=producer,strict(proofraw,custody.PROOF_SCHEMA),selected
        self.custody_set=setdoc
        if sum(map(len,documents.values()))+sum(map(len,self.source_bytes.values()))+len(self.anchor.raw)>MAX_BYTES: refuse()
        self.recheck_sources()
        return adapter.Observations(trust,evidence,pins,cat['readiness_sha256'],cat['ongoing_sha256'])

    def set_genesis_request(self, raw):
        request = strict(raw)
        genesis.validate_request(request)
        if (request['source_sha'] != self.doc['source_sha'] or request['source_tree'] != self.doc['source_tree']
                or request['tooling_sha256'] != self.doc['tooling_sha256'] or request['python_version'] != self.doc['python_version']
                or request['target']['path'] != self.doc['deployment_target']
                or request['target']['host_fingerprint'] != self.doc['host_fingerprint']): refuse()
        self.genesis_request = copy.deepcopy(request)

    def genesis_documents(self):
        documents = {}
        total = sum(map(len,self.source_bytes.values())) + len(self.anchor.raw)
        for ref in self.catalogue['documents']:
            if ref in documents: refuse()
            raw = dr.read_regular(Path(self.doc['evidence_root'])/ref, MAX_BYTES)
            total += len(raw)
            if total > MAX_BYTES or dr.sha(raw) != ref: refuse()
            documents[ref] = raw
        return documents

    def genesis_ledger(self, documents):
        # The full independently selected hash chain and current high-water head
        # are checked. Genesis does not require a future R0/qualification point.
        evidence = dr.Evidence(documents)
        ledger = evidence.get(self.catalogue['ledger_sha256'], 'ledger')
        cat, cp = self.catalogue, self.checkpoint
        if (ledger['binding_sha256'] != self.doc['binding_sha256'] or ledger['head_sequence'] != len(ledger['events'])
                or ledger['head_sequence'] != cat['ledger_sequence'] or ledger['events'][-1] != cat['ledger_head_sha256']
                or cp['ledger_sequence'] > len(ledger['events']) or ledger['events'][cp['ledger_sequence']-1] != cp['ledger_head_sha256']): refuse()
        previous = None
        seen = set()
        for sequence, ref in enumerate(ledger['events'], 1):
            event = evidence.get(ref, 'event')
            if event['sequence'] != sequence or event['previous_sha256'] != previous or event['document_sha256'] in seen: refuse()
            evidence.get(event['document_sha256'], event['document_kind'])
            seen.add(event['document_sha256']); previous = ref

    def verify_genesis(self, challenge, context, echo, started):
        request = self.genesis_request
        if request is None: refuse()
        genesis.validate_request(request, identity=challenge['identity'], target=challenge['target'])
        readback = challenge['genesis_mode'] == 'GENESIS_READBACK'
        scope = 'LEGACY_GENESIS_COPY' if readback else 'LEGACY_GENESIS'
        with adapter.bounded(300-(time.monotonic()-started)):
            self.acquire_catalogue(challenge['nonce'], challenge['identity'], scope)
            binding = self.source_binding()
            if request['script_sha256'] != next(x['sha256'] for x in binding['tools'] if x['path']=='scripts/v126-cutover.sh'): refuse()
            documents = self.genesis_documents()
            self.genesis_ledger(documents)
            cat = self.catalogue
            if request['inventory_sha256'] != cat['legacy_inventory_sha256']: refuse()
            invraw = documents.get(cat['legacy_inventory_sha256'])
            if invraw is None: refuse()
            inventory = strict(invraw)
            genesis.validate_inventory(inventory)
            if inventory['target'] != request['target'] or request['run_id'] in {x['run_id'] for x in inventory['runs']}: refuse()
            current_action_refs = {challenge['identity']['intent_sha256'],dr.digest(challenge['identity']),dr.digest(self.action_authority)}
            if current_action_refs & set(cat['revoked']): refuse()
            now = self.now()
            if readback:
                if cat['legacy_completion_sha256'] is None or cat['legacy_completion_sha256'] != challenge['genesis_completion_sha256']: refuse()
                # Copy authority is fresh; the accepted immutable inventory is
                # historical. Do not require a new fence or requalify old effects.
                validation = dict(inventory_sha256=request['inventory_sha256'], completion_sha256=cat['legacy_completion_sha256'])
                disposition_headroom = self.remaining(self.action_authority['valid_until'], now)
            else:
                if cat['legacy_completion_sha256'] is not None: refuse()
                self.unexpired(request['created_at'], request['expires_at'], now)
                if self.remaining(request['expires_at'], now) <= context.action_seconds+600: refuse()
                raw, producer = self.source('producer', genesis.OBSERVATIONS)
                self.round_snapshot['producer'] = raw
                if (producer['binding_sha256'] != self.doc['binding_sha256'] or producer['tools_sha256'] != self.doc['tooling_sha256']
                        or any(producer[k] != self.doc[k] for k in ('source_sha','source_tree','python_version','target_sha256'))): refuse()
                refs = set(producer['observed_documents']) | {request['inventory_sha256']}
                if (refs | set(self.doc['legacy_genesis'].values())) & set(cat['revoked']): refuse()
                native_verifier = self.native_genesis_verifier
                if native_verifier is None:
                    next_owner = dict(run_id=request['run_id'],release_sha=request['source_sha'],script_sha256=request['script_sha256'])
                    native_verifier = lambda run, proof, docs: genesis.verify_native_terminal(run,proof,docs,source_path=ROOT/'scripts/v126-cutover.sh',next_owner=next_owner)
                validation = genesis.validate_basis(inventory, producer, documents, self.doc['legacy_genesis'], now, native_verifier)
                disposition_headroom = min(validation['headroom'], self.remaining(request['expires_at'], now)-context.action_seconds-600)
            self.recheck_sources()
            final = self.now(); dr.chronology(now, final)
            elapsed = S.timestamp(final['utc'])+final['error_seconds']-S.timestamp(now['utc'])+now['error_seconds']
            reserve = 0 if readback else context.action_seconds+600
            authority = self.remaining(self.action_authority['valid_until'], final)-reserve
            vector = dict(anchor=self.remaining(self.doc['valid_until'], final)-reserve,
                clock=min(300-dr.age(self.clock_record['measured'],final),self.remaining(self.clock_record['expires_at'],final)),
                catalogue=min(300-dr.age(cat['observed'],final),self.remaining(cat['expires_at'],final),
                    300-dr.age(self.highwater['observed'],final),self.remaining(self.highwater['expires_at'],final)),
                authority=authority, inventory=authority if readback else min(300-dr.age(inventory['observed'],final),self.remaining(inventory['expires_at'],final)),
                disposition=authority if readback else disposition_headroom-max(0,elapsed))
            if set(vector)!=genesis.EXPIRY_KEYS or any(type(x) not in (int,float) or not math.isfinite(x) or x<=0 for x in vector.values()): refuse()
        if not 0 <= time.monotonic()-started <= 300: refuse()
        if challenge['sequence']==1: self.sessions[challenge['session_id']]['admitted']=True
        self.minimum_generation=cat['generation']; self.minimum_revocation=cat['revocation_generation']
        self.checkpoint=dict(catalogue_generation=cat['generation'],ledger_sequence=cat['ledger_sequence'],ledger_head_sha256=cat['ledger_head_sha256'],revocation_generation=cat['revocation_generation'])
        self.last_validation = dict(validation, inventory=copy.deepcopy(inventory))
        return dict(echo,decision='PASS',barrier='LEGACY_GENESIS_COPY' if readback else 'LEGACY_GENESIS',
            qualification_sha256=request['inventory_sha256'], catalogue_head_sha256=cat['ledger_head_sha256'],
            catalogue_sequence=cat['ledger_sequence'],revocation_generation=cat['revocation_generation'],
            pins_sha256=dr.digest(validation),native_stage7_sha256=None,native_manifest_sha256=None,
            expiry=vector,legacy_inventory=inventory)

    def verify_native_stage7(self, context):
        if not callable(self.native_verifier): refuse()
        result=self.native_verifier(context,copy.deepcopy(self.current_challenge['native_history']))
        if type(result) is not adapter.NativeStage7: refuse()
        return result

    @staticmethod
    def remaining(expiry, now):
        return S.timestamp(expiry)-S.timestamp(now['utc'])-now['error_seconds']

    def expiry(self, validated):
        now=validated['clock']; evidence=validated['evidence']; observed=validated['observations']; ongoing=validated['ongoing']
        cat,producer=self.catalogue,self.producer
        authrefs=set(observed.trust.authorization_sha256)
        # Include every consumed authorization, not only readiness/AP-06.
        authority=min(self.remaining(evidence.get(ref,'authorization')['valid_until'],now) for ref in authrefs)
        lower=S.timestamp(now['utc'])-now['error_seconds']; upper=S.timestamp(now['utc'])+now['error_seconds']
        slot=math.floor(lower/21600)*21600
        cadence=min(x-upper for x in (slot+7200,slot+21600) if x>lower)
        vector=dict(checkpoint=min(300-dr.age(cat['observed'],now),self.remaining(cat['expires_at'],now),
                                   300-dr.age(self.highwater['observed'],now),self.remaining(self.highwater['expires_at'],now)),
            ongoing_observed=300-dr.age(ongoing['observed'],now), monitor=300-dr.age(ongoing['last_monitor'],now),
            authorization=min(authority,self.remaining(self.action_authority['valid_until'],now)),
            response_authority=authority,cadence=cadence,age_state=43200-dr.age(self.selected['manifest']['point'],now),
            custody_retrieval=min(300-dr.age(producer['retrieval']['observed'],now),self.remaining(self.retrieval_proof['expires_at'],now),
                *[self.remaining(x['expires_at'],now) for x in self.custody_set['assets'] if x['expires_at'] is not None]),
            rpo=86400-dr.age(self.selected['manifest']['point'],now)-validated['context'].action_seconds-300-300,
            anchor=self.remaining(self.doc['valid_until'],now),
            clock=min(300-dr.age(self.clock_record['measured'],now),self.remaining(self.clock_record['expires_at'],now)),
            source_observations=min(300-dr.age(producer['observed'],now),self.remaining(producer['expires_at'],now)))
        if set(vector)!=EXPIRY_KEYS or any(type(x) not in (int,float) or not math.isfinite(x) or x<=0 for x in vector.values()): refuse()
        return vector

    def verify(self, challenge):
        # Echo only bounded protocol identities even on refusal; diagnostics never
        # contain the source documents, credentials or unvalidated free text.
        if type(challenge) is not dict: refuse()
        echo={k:challenge.get(k) for k in ('session_id','nonce','sequence','phase')}
        S.check(echo,{'session_id':'sha','nonce':'sha','sequence':S.enum(1,2),'phase':S.enum('EARLY','LATE')})
        echo.update(version=1,type='RESULT',challenge_sha256=dr.digest(challenge))
        try:
            started=time.monotonic()
            self.current_challenge=copy.deepcopy(challenge)
            context=self.challenge(challenge)
            if challenge['identity']['kind']=='TARGET_BIND':
                return self.verify_genesis(challenge, context, echo, started)
            with adapter.bounded(300):
                self.acquire_catalogue(challenge['nonce'],challenge['identity'],'DISPATCH')
            gate=adapter.Gate(self,operational=True)
            result=gate.verify_context(context,checkpoint=self.recheck_sources,observation_seconds=300-(time.monotonic()-started))
            with adapter.bounded(300-(time.monotonic()-started)):
                self.recheck_sources()
                validation=gate.last_validation
                # No expensive validation after this current clock/expiry computation.
                now=self.now(); dr.chronology(validation['clock'],now)
                def cadence_phase(clock):
                    lower=S.timestamp(clock['utc'])-clock['error_seconds']
                    slot,offset=divmod(lower,S.POLICY['snapshot_seconds'])
                    return slot,offset>=S.POLICY['qualification_seconds']
                if cadence_phase(validation['clock'])!=cadence_phase(now): refuse()
                validation['clock']=now
                expiry=self.expiry(validation)
            if not 0 <= time.monotonic()-started <= 300: refuse()
            if challenge['sequence']==1: self.sessions[challenge['session_id']]['admitted']=True
            self.last_validation=validation
            self.minimum_generation=self.catalogue['generation']; self.minimum_revocation=self.catalogue['revocation_generation']
            self.checkpoint=dict(catalogue_generation=self.catalogue['generation'],ledger_sequence=self.catalogue['ledger_sequence'],ledger_head_sha256=self.catalogue['ledger_head_sha256'],revocation_generation=self.catalogue['revocation_generation'])
            native=validation['native']
            return dict(echo,decision='PASS',barrier=result['barrier'],qualification_sha256=result['qualification_sha256'],
                catalogue_head_sha256=self.catalogue['ledger_head_sha256'],catalogue_sequence=self.catalogue['ledger_sequence'],
                revocation_generation=self.catalogue['revocation_generation'],pins_sha256=dr.digest(vars(validation['observations'].pins)),
                native_stage7_sha256=None if native is None else dr.sha(native.receipt),
                native_manifest_sha256=None if native is None else native.manifest_sha256,expiry=expiry)
        except (Exception,adapter._ObservationTimeout):
            return dict(echo,decision='REFUSE',reason='POLICY_B_REFUSED')
