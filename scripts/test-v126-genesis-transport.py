#!/usr/bin/env python3
"""Closed genesis frames and expiry, using the actual transport; no SSH/network."""
import copy
import hashlib
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('genesis_transport', ROOT/'v126-policy-b-transport.py')
t = importlib.util.module_from_spec(spec); sys.modules[spec.name] = t; spec.loader.exec_module(t)


class GenesisTransport(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='genesis-frame-')
        self.addCleanup(self.temp.cleanup)
        self.target = Path(self.temp.name).resolve(); info=self.target.stat()
        self.inventory = {'kind':'synthetic-inventory'}
        self.proposal = dict(target=dict(path=str(self.target),device=info.st_dev,inode=info.st_ino,uid=info.st_uid),
                             inventory_sha256=t.digest(self.inventory))
        self.identity = dict(run_id='genesis-synthetic',release_sha='a'*40,script_sha256=hashlib.sha256(b'source').hexdigest(),
            intent_sha256=t.digest(self.proposal),kind='TARGET_BIND',name='LEGACY_GENESIS',action='bind-legacy-target')
        self.open = dict(version=1,type='OPEN',mode='EXECUTE',session_id='b'*64,anchor_sha256='c'*64,anchor_generation=1,
            principal_fingerprint='SHA256:'+'A'*43,host_fingerprint='SHA256:'+'B'*43,identity=self.identity,
            target=str(self.target),request_sha256=t.digest(self.proposal),manifest_sha256=t.digest(self.proposal),
            manifest_size=len(t.canonical(self.proposal)),source_tree='d'*40,tooling_sha256='e'*64,runtime='3.12.3',
            worker_args=[],environment={},init_request=None,init_completion=None,payload=None,genesis_request=self.proposal)
        self.frames=[];self.transform=lambda x:x;self.history='f'*64; self.locks=[]
        outer=self
        class Channel:
            def send(self,value,**kwargs):outer.frames.append(value)
            def recv(self,**kwargs):return outer.transform(outer.passing(outer.frames[-1]))
            def require_idle_peer(self):pass
        self.channel=Channel()
        principal={k:self.open[k] for k in ('principal_fingerprint','host_fingerprint','anchor_sha256','anchor_generation')}
        principal['revocation_generation']=1
        self.session=t.Session(self.channel,self.open,b'source',principal,lambda:None)
        self.gate=t.RemoteGate(self.session,lambda:self.history,lambda target,fd:self.locks.append(fd))

    def passing(self,c):
        return dict(t.result_echo(c),decision='PASS',barrier='LEGACY_GENESIS_COPY' if c['genesis_mode']=='GENESIS_READBACK' else 'LEGACY_GENESIS',
            qualification_sha256=t.digest(self.inventory),catalogue_head_sha256='1'*64,catalogue_sequence=1,revocation_generation=1,
            pins_sha256='2'*64,native_stage7_sha256=None,native_manifest_sha256=None,
            expiry={k:1000 for k in t.GENESIS_EXPIRY_KEYS},legacy_inventory=self.inventory)

    def early(self):return self.gate.check(self.identity,self.target,None,300)
    def late(self):return self.gate.check(self.identity,self.target,7,300)
    def dispatch(self):return self.gate.before_dispatch(self.identity,self.target,7,300)

    def test_exact_early_before_create_late_and_each_write(self):
        self.early();self.assertFalse(self.locks)
        self.gate.before_create(self.identity,self.target,None,300)
        self.late();self.dispatch()
        self.gate.before_write(self.identity,self.target,7,300)
        self.assertEqual([x['phase'] for x in self.frames],['EARLY','LATE'])
        self.assertGreaterEqual(len(self.locks),4)
        with self.assertRaises(t.ProtocolError):self.dispatch()
        with self.assertRaises(t.ProtocolError):self.gate.before_create(self.identity,self.target,None,300)

    def test_absence_and_physical_target_change_deny_before_first_write(self):
        (self.target/'.v126-target-operations').mkdir()
        with self.assertRaises(t.ProtocolError):self.early()
        self.assertEqual(self.frames,[])

    def test_delayed_first_write_and_late_write_refuse(self):
        self.early();self.gate.pending['received']=time.monotonic()-6
        with self.assertRaises(t.ProtocolError):self.gate.before_create(self.identity,self.target,None,300)
        self.assertFalse((self.target/'.v126-target-operations').exists())
        self.late();self.dispatch();self.gate.pending['sent']=time.monotonic()-1001
        with self.assertRaises(t.ProtocolError):self.gate.before_write(self.identity,self.target,7,300)

    def test_genesis_frame_cannot_claim_r0_or_omit_inventory_or_clock(self):
        for mutation in (lambda x:dict(x,barrier='R0'),lambda x:{k:v for k,v in x.items() if k!='legacy_inventory'},
                         lambda x:dict(x,legacy_inventory={}),lambda x:dict(x,expiry={'authority':1000}),
                         lambda x:dict(x,native_stage7_sha256='a'*64)):
            with self.subTest(mutation=mutation):
                self.setUp();self.transform=mutation
                with self.assertRaises(t.ProtocolError):self.early()
                self.assertFalse((self.target/'.v126-target-operations').exists())

    def test_open_proposal_identity_and_cross_mode_are_closed(self):
        for mutation in (dict(genesis_request={}),dict(manifest_sha256='a'*64),dict(worker_args=['arbitrary']),
                         dict(init_completion={}),dict(mode='GENESIS_RESET')):
            with self.subTest(mutation=mutation),self.assertRaises(t.ProtocolError):t.validate_open(dict(self.open,**mutation))
        self.early();bad=copy.deepcopy(self.frames[-1]);bad['genesis_completion_sha256']='a'*64
        with self.assertRaises(t.ProtocolError):t.validate_challenge(bad)

    def test_inventory_changed_between_rounds_never_dispatches(self):
        self.early();self.transform=lambda x:dict(x,qualification_sha256='3'*64)
        with self.assertRaises(t.ProtocolError):self.late()
        with self.assertRaises(t.ProtocolError):self.dispatch()


if __name__=='__main__':unittest.main()
