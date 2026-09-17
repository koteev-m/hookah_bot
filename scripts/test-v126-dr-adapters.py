#!/usr/bin/env python3
"""Offline AP-00 security regressions. All credentials, keys and bytes are synthetic."""
import base64
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import io
import json
import logging
from pathlib import Path
import socket
import subprocess
import sys
import traceback
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


# Internal SDK semantic tests only; production always executes this as a script.
s3 = module('worker_s3', 'v126-dr-s3-worker.py')
c = s3.crypto
p = s3.provisioning
dr = p.dr
H = lambda value: hashlib.sha256(value.encode()).hexdigest()
TARGET = dr.canonical(dict(schema_version=1, kind='target', protocol='s3-compatible',
    endpoint='https://objects.example.invalid', bucket='synthetic-ap00', region='ru-central1',
    storage_class='STANDARD', account_ref=H('synthetic-account'), failure_domain_ref=H('synthetic-domain'),
    versioning='ENABLED', object_lock='ENABLED', lock_mode='COMPLIANCE', retention_seconds=604800))
TARGET_HASH = dr.sha(TARGET)
CONTEXT = dict(target_sha256=TARGET_HASH, manifest_sha256=H('synthetic-manifest'),
               object_key='bundles/' + H('synthetic-manifest') + '.enc', custody_version_sha256=H('synthetic-custody'))
NOW = datetime(2026, 9, 15, tzinfo=timezone.utc)
UNTIL = NOW + timedelta(days=8)
CANARY = 'SYNTHETIC_SECRET_CANARY_MUST_NOT_APPEAR'
PLAINTEXT = b'synthetic archive plaintext canary'
TEST_KEY = bytes(range(32))


class SyntheticMemoryKeyProvider:
    def __init__(self, key=TEST_KEY, reference=CONTEXT['custody_version_sha256']):
        self.keys = {reference: key}

    def resolve(self, reference):
        return self.keys[reference]


def codec(context=CONTEXT, key=TEST_KEY):
    return c.Envelope(SyntheticMemoryKeyProvider(key, context['custody_version_sha256']), context)


def vector():
    # Test-only randomness boundary. The production API has no nonce argument.
    with patch.object(c.os, 'urandom', return_value=bytes(range(12))):
        return codec().encrypt(PLAINTEXT)


def rewrite(raw, mutate):
    length = int.from_bytes(raw[4:8], 'big')
    header = json.loads(raw[8:8 + length])
    mutate(header)
    encoded = dr.canonical(header)
    return c.MAGIC + len(encoded).to_bytes(4, 'big') + encoded + raw[8 + length:]


class OfflineTest(unittest.TestCase):
    def setUp(self):
        self.addCleanup(patch.stopall)
        patch('socket.create_connection', side_effect=AssertionError('NETWORK_FORBIDDEN')).start()
        patch.object(socket.socket, 'connect', side_effect=AssertionError('NETWORK_FORBIDDEN')).start()

    def safe_error(self, call, code=None, kind=ValueError):
        with self.assertRaises(kind) as caught:
            call()
        text = ''.join(traceback.format_exception(caught.exception))
        for secret in (CANARY, PLAINTEXT.decode(), repr(TEST_KEY)):
            self.assertNotIn(secret, text)
        if code:
            self.assertEqual(str(caught.exception), code)
        return caught.exception


class CryptoTests(OfflineTest):
    def test_roundtrip_full_tag_and_bound_metadata(self):
        raw = vector()
        header, aad, encrypted = c.inspect_envelope(raw, CONTEXT)
        self.assertEqual(len(encrypted), len(PLAINTEXT) + 16)
        self.assertEqual(header['nonce_hex'], bytes(range(12)).hex())
        self.assertEqual(codec().decrypt(raw), PLAINTEXT)
        meta = dict(backend='aes-256-gcm', backend_sha256=dr.sha(Path(c.__file__).read_bytes()),
                    format_version='1', custody_version_sha256=CONTEXT['custody_version_sha256'], mode='client-side')
        self.assertEqual(codec().decrypt(raw, meta), PLAINTEXT)
        for field, value in [('backend','fake'), ('backend_sha256',H('wrong')), ('mode','synthetic'),
                             ('format_version','2'), ('custody_version_sha256',H('wrong'))]:
            with self.subTest(field=field):
                self.safe_error(lambda: codec().decrypt(raw, {**meta, field: value}), 'AP00_DECRYPTION_FAILED')
        self.assertNotIn(PLAINTEXT, raw)
        self.assertNotIn(TEST_KEY, raw)

    def test_deterministic_serialized_identity(self):
        raw = vector()
        self.assertEqual(raw, vector())
        self.assertEqual(dr.file_identity(raw), {'sha256': '4455813c623cb21b25ee3d841a25bb6ae6716ea03daea451d61217095f64e17d', 'size': 517})

    def test_tampered_ciphertext_nonce_and_tag(self):
        raw = vector()
        length = int.from_bytes(raw[4:8], 'big')
        for index in (8 + length, len(raw)-1, len(raw)-16):
            with self.subTest(index=index):
                changed = raw[:index] + bytes([raw[index] ^ 1]) + raw[index+1:]
                self.safe_error(lambda: codec().decrypt(changed), 'AP00_DECRYPTION_FAILED')
        changed = rewrite(raw, lambda h: h.update(nonce_hex='ff'*12))
        self.safe_error(lambda: codec().decrypt(changed), 'AP00_DECRYPTION_FAILED')

    def test_each_aad_binding_rejects_recontextualization(self):
        raw = vector()
        for field in CONTEXT:
            altered = dict(CONTEXT)
            if field in ('manifest_sha256', 'object_key'):
                altered.update(manifest_sha256=H('other'), object_key='bundles/'+H('other')+'.enc')
            else:
                altered[field] = H('other')
            with self.subTest(field=field):
                self.safe_error(lambda: codec(altered).decrypt(raw), 'AP00_DECRYPTION_FAILED')
                # Even a rewritten header matching the new expected context fails AEAD.
                forged = rewrite(raw, lambda h: h.update(altered))
                self.safe_error(lambda: codec(altered).decrypt(forged), 'AP00_DECRYPTION_FAILED')

    def test_wrong_key_key_lengths_and_provider_diagnostics(self):
        raw = vector()
        for key in (b'x'*32, b'x'*16, b'x'*24, b'x'*31, 'x'*32, None):
            with self.subTest(key_type=type(key).__name__):
                self.safe_error(lambda: codec(key=key).decrypt(raw), 'AP00_DECRYPTION_FAILED')
        provider = SyntheticMemoryKeyProvider()
        provider.resolve = lambda ref: (_ for _ in ()).throw(ValueError(CANARY))
        self.safe_error(lambda: c.Envelope(provider, CONTEXT).decrypt(raw), 'AP00_DECRYPTION_FAILED')
        self.safe_error(lambda: c.Envelope(provider, CONTEXT).encrypt(PLAINTEXT), 'AP00_ENCRYPTION_FAILED')

    def test_unsupported_unknown_missing_and_wrong_type_header(self):
        for field, value in [('algorithm','AES-CBC'), ('format_version',2), ('format_version',True),
                             ('nonce_hex','gg'*12), ('nonce_hex','00'*11), ('nonce_hex','AA'*12),
                             ('ciphertext_size',True), ('ciphertext_size',17), ('credential',CANARY)]:
            with self.subTest(field=field, value_type=type(value).__name__):
                self.safe_error(lambda: codec().decrypt(rewrite(vector(), lambda h: h.update({field:value}))),
                                'AP00_DECRYPTION_FAILED')
        for field in json.loads(vector()[8:8+int.from_bytes(vector()[4:8],'big')]):
            self.safe_error(lambda: codec().decrypt(rewrite(vector(),lambda h: h.pop(field))), 'AP00_DECRYPTION_FAILED')

    def test_truncation_noncanonical_duplicate_and_oversize(self):
        raw = vector()
        for size in range(len(raw)):
            self.safe_error(lambda: codec().decrypt(raw[:size]), 'AP00_DECRYPTION_FAILED')
        for bad in (raw+b'x', b'XXXX'+raw[4:], c.MAGIC+(2049).to_bytes(4,'big')+raw[8:],
                    'not bytes', b'', c.MAGIC+b'\x00'*4):
            self.safe_error(lambda: codec().decrypt(bad), 'AP00_DECRYPTION_FAILED')
        length = int.from_bytes(raw[4:8], 'big')
        for header in (raw[8:8+length]+b' ', raw[8:8+length].replace(b'{',b'{"format_version":1,',1)):
            bad = c.MAGIC+len(header).to_bytes(4,'big')+header+raw[8+length:]
            self.safe_error(lambda: codec().decrypt(bad), 'AP00_DECRYPTION_FAILED')
        with patch.object(c,'MAX_ENVELOPE_BYTES',len(raw)-1):
            self.safe_error(lambda: codec().decrypt(raw), 'AP00_DECRYPTION_FAILED')
        with patch.object(c,'MAX_PLAINTEXT_BYTES',len(PLAINTEXT)-1):
            self.safe_error(lambda: codec().encrypt(PLAINTEXT), 'AP00_ENCRYPTION_FAILED')
        self.safe_error(lambda: codec().encrypt(b''), 'AP00_ENCRYPTION_FAILED')

    def test_production_nonce_uniqueness_4096_encryptions(self):
        seen = set()
        encryption = codec()
        for _ in range(4096):
            raw = encryption.encrypt(PLAINTEXT)
            header, _, _ = c.inspect_envelope(raw, CONTEXT)
            seen.add(header['nonce_hex'])
        self.assertEqual(len(seen), 4096)


POLICY = b'{"synthetic-policy-only":true}\n'


def descriptor():
    def role(name, write, read, observe):
        return dict(principal_sha256=H(name), capability_descriptor_sha256=H(name+'-capability'),
                    credential_mechanism='static-access-key', bundle_create_only=write,
                    exact_version_read=read, metadata_read=observe, delete=False, admin=False,
                    governance_bypass=False, list=False)
    return dict(schema_version=1, kind='ap00-provisioning', target_sha256=TARGET_HASH,
                capacity_cap_bytes=1024**3, monthly_budget_minor=10000, budget_currency='RUB',
                bucket_policy=dr.file_identity(POLICY), conditional_write_enforced=True,
                public_access_disabled=True, lifecycle_expiration='ABSENT', expiration_after_seconds=None,
                writer=role('writer',True,False,False), independent_reader=role('reader',False,True,False),
                metadata_observer=role('observer',False,False,True), configuration_observation_sha256=H('observation'))


class ProvisioningTests(OfflineTest):
    def parse(self, doc):
        return p.parse(dr.canonical(doc), TARGET, TARGET_HASH, POLICY)

    def test_canonical_exact_target_policy_and_content_address(self):
        doc = descriptor()
        raw = dr.canonical(doc)
        self.assertEqual(self.parse(doc), doc)
        self.assertEqual(dr.digest(dict(reversed(list(doc.items())))), dr.sha(raw))
        self.safe_error(lambda:p.parse(raw+b' ',TARGET,TARGET_HASH,POLICY),'AP00_PROVISIONING_INVALID')
        self.safe_error(lambda:p.parse(raw,TARGET,H('other'),POLICY),'AP00_PROVISIONING_INVALID')
        self.safe_error(lambda:p.parse(raw,TARGET,TARGET_HASH,POLICY+b' '),'AP00_PROVISIONING_INVALID')
        duplicate = raw.replace(b'{', b'{"schema_version":1,',1)
        self.safe_error(lambda:p.parse(duplicate,TARGET,TARGET_HASH,POLICY),'AP00_PROVISIONING_INVALID')

    def test_all_missing_unknown_and_secret_fields(self):
        for field in descriptor():
            doc = descriptor(); del doc[field]
            self.safe_error(lambda:self.parse(doc),'AP00_PROVISIONING_INVALID')
        for field in ('secret','access_key_id','secret_uri','credentials','key_material','plaintext'):
            for nested in (None,'writer','independent_reader','metadata_observer','bucket_policy'):
                doc = descriptor(); (doc if nested is None else doc[nested])[field] = CANARY
                self.safe_error(lambda:self.parse(doc),'AP00_PROVISIONING_INVALID')

    def test_types_hashes_capacity_and_unsafe_configuration(self):
        for field, value in [('target_sha256','z'*64), ('configuration_observation_sha256','0'*63),
                             ('capacity_cap_bytes',True), ('capacity_cap_bytes',0), ('monthly_budget_minor',-1),
                             ('monthly_budget_minor','100'), ('public_access_disabled',False),
                             ('conditional_write_enforced',False), ('public_access_disabled',1),
                             ('budget_currency',CANARY)]:
            doc = descriptor(); doc[field] = value
            self.safe_error(lambda:self.parse(doc),'AP00_PROVISIONING_INVALID')
        for field, value in [('sha256',H('wrong')),('size',True),('size',len(POLICY)+1)]:
            doc = descriptor(); doc['bucket_policy'][field] = value
            self.safe_error(lambda:self.parse(doc),'AP00_PROVISIONING_INVALID')

    def test_no_lifecycle_expiry_even_after_retention(self):
        for state, seconds in [('PRESENT',1),('PRESENT',604800),('PRESENT',999999),('ABSENT',604800),('PRESENT',None)]:
            doc = descriptor(); doc.update(lifecycle_expiration=state,expiration_after_seconds=seconds)
            self.safe_error(lambda:self.parse(doc),'AP00_PROVISIONING_INVALID')

    def test_principal_separation_and_capability_contradictions(self):
        for field in ('principal_sha256','capability_descriptor_sha256'):
            for a,b in [('writer','independent_reader'),('writer','metadata_observer'),('independent_reader','metadata_observer')]:
                doc=descriptor(); doc[a][field]=doc[b][field]
                self.safe_error(lambda:self.parse(doc),'AP00_PROVISIONING_INVALID')
        for role in ('writer','independent_reader','metadata_observer'):
            for field in ('delete','admin','governance_bypass'):
                doc=descriptor(); doc[role][field]=True
                self.safe_error(lambda:self.parse(doc),'AP00_PROVISIONING_INVALID')
        for role, field in [('writer','bundle_create_only'),('independent_reader','bundle_create_only'),
                            ('metadata_observer','exact_version_read'),('metadata_observer','metadata_read')]:
            doc=descriptor(); doc[role][field]=not doc[role][field]
            self.safe_error(lambda:self.parse(doc),'AP00_PROVISIONING_INVALID')
        doc=descriptor(); doc['writer'].update(exact_version_read=True,list=True)
        self.assertEqual(self.parse(doc),doc)

    def test_target_unsafe_or_conflicting(self):
        for field,value in [('endpoint','http://invalid.example'),('endpoint','https://user:secret@example.invalid'),
                            ('endpoint','https://example.invalid/?token='+CANARY),('lock_mode','GOVERNANCE'),
                            ('versioning','DISABLED'),('object_lock','DISABLED'),('storage_class','COLD')]:
            doc=json.loads(TARGET); doc[field]=value; raw=dr.canonical(doc)
            self.safe_error(lambda:p.target(raw,dr.sha(raw)),'AP00_TARGET_INVALID')


class Raw(io.BytesIO):
    def stream(self, amt=None, decode_content=False):
        yield self.read()


class S3Tests(OfflineTest):
    def setUp(self):
        super().setUp()
        from botocore.httpsession import URLLib3Session
        patch.object(URLLib3Session,'send',side_effect=AssertionError('NETWORK_FORBIDDEN')).start()
        self.client=s3.make_client(TARGET,TARGET_HASH,access_key='SYNTHETIC_ACCESS',secret_key=CANARY)
        self.addCleanup(self.client.close)
        self.writer=s3.Writer(self.client,TARGET,TARGET_HASH)
        self.reader=s3.ExactReader(self.client,TARGET,TARGET_HASH)
        self.observer=s3.MetadataObserver(self.client,TARGET,TARGET_HASH)
        self.raw=vector()
        patch.object(s3,'_utc_now',return_value=NOW).start()
        self.sleep=patch.object(s3.time,'sleep').start()

    def wire(self, outcomes):
        from botocore.awsrequest import AWSResponse
        def send(request):
            outcome=outcomes.pop(0)
            if isinstance(outcome,Exception): raise outcome
            status,headers,data=outcome
            return AWSResponse(request.url,status,headers,Raw(data))
        return patch.object(self.client._endpoint.http_session,'send',side_effect=send)

    def upload(self):
        return self.writer.create_once(TARGET_HASH,CONTEXT['object_key'],self.raw,
                   custody_version_sha256=CONTEXT['custody_version_sha256'],retain_until=UNTIL)

    def get(self, version='version-001'):
        return self.reader.read_exact(TARGET_HASH,CONTEXT['object_key'],version)

    def test_actual_serialized_signed_put_contract_and_response_version(self):
        with self.wire([(200,{'x-amz-version-id':'version-001','etag':'not-evidence'},b'')]) as send:
            self.assertEqual(self.upload(),'version-001')
        self.assertEqual(send.call_count,1)
        request=send.call_args.args[0]
        self.assertEqual(request.method,'PUT')
        self.assertEqual(request.url,'https://objects.example.invalid/synthetic-ap00/'+CONTEXT['object_key'])
        headers={k.lower():v.decode() if isinstance(v,bytes) else v for k,v in request.headers.items()}
        for name, value in {'if-none-match':'*','content-md5':base64.b64encode(hashlib.md5(self.raw).digest()).decode(),
                            'x-amz-storage-class':'STANDARD','x-amz-object-lock-mode':'COMPLIANCE',
                            'x-amz-object-lock-retain-until-date':'2026-09-23T00:00:00Z',
                            'content-length':str(len(self.raw))}.items():
            self.assertEqual(headers[name],value)
            if name!='content-length': self.assertIn(name,headers['authorization'])
        self.assertIn('/ru-central1/s3/aws4_request',headers['authorization'])
        self.assertEqual(request.body.read(),self.raw)
        self.assertTrue(self.client._endpoint.http_session._verify)
        self.assertEqual(self.client.meta.config.retries['total_max_attempts'],1)

    def test_missing_empty_reserved_or_malformed_version_is_unknown(self):
        for version in (None,'','null','latest','unknown',CANARY+'\n'):
            headers={} if version is None else {'x-amz-version-id':version}
            with self.subTest(version_type=type(version).__name__), self.wire([(200,headers,b'')]) as send:
                result=self.safe_error(self.upload,'AP00_WRITE_UNKNOWN_VERSION_ID',s3.WriteUnknown)
                self.assertEqual(result.outcome,'UNKNOWN'); self.assertEqual(send.call_count,1)

    def test_write_status_matrix_has_one_put_and_no_auxiliary_request(self):
        cases = [(200, 'version-001', None),
                 *[(200, v, 'AP00_WRITE_UNKNOWN_VERSION_ID') for v in (None, '', 'null', 'latest', 'unknown', CANARY+'\n')],
                 *[(status, 'version-001', 'AP00_WRITE_UNKNOWN_HTTP') for status in (201, 202, 204, 206)],
                 (403, None, 'AP00_S3_FORBIDDEN'), (404, None, 'AP00_S3_EXACT_VERSION_UNAVAILABLE'),
                 (409, None, 'AP00_S3_CONDITIONAL_RACE'), (412, None, 'AP00_S3_CREATE_ONLY_PRECONDITION')]
        for status, version, code in cases:
            headers = {} if version is None else {'x-amz-version-id': version}
            with self.subTest(status=status, version=repr(version)), self.wire([(status, headers, b'')]) as send:
                if code is None:
                    self.assertEqual(self.upload(), 'version-001')
                else:
                    error = self.safe_error(self.upload, code, s3.TransportError)
                    self.assertEqual(isinstance(error, s3.WriteUnknown), status < 300)
                    if status < 300:
                        self.assertEqual(error.outcome, 'UNKNOWN')
                self.assertEqual([call.args[0].method for call in send.call_args_list], ['PUT'])
                self.assertEqual(send.call_args.args[0].url,
                                 'https://objects.example.invalid/synthetic-ap00/'+CONTEXT['object_key'])
        self.sleep.assert_not_called()

    def test_http_rejections_single_attempt(self):
        for status,code in [(403,'FORBIDDEN'),(404,'EXACT_VERSION_UNAVAILABLE'),(409,'CONDITIONAL_RACE'),(412,'CREATE_ONLY_PRECONDITION')]:
            body=('<Error><Code>Denied</Code><Message>'+CANARY+'</Message></Error>').encode()
            with self.subTest(status=status), self.wire([(status,{},body)]) as send:
                self.safe_error(self.upload,'AP00_S3_'+code,s3.TransportError)
                self.assertEqual(send.call_count,1)
        self.sleep.assert_not_called()

    def test_write_timeout_connection_tls_and_5xx_remain_unknown(self):
        from botocore import exceptions as e
        failures=[e.ReadTimeoutError(endpoint_url=CANARY),e.ConnectionClosedError(endpoint_url=CANARY),
                  e.EndpointConnectionError(endpoint_url=CANARY),e.ConnectTimeoutError(endpoint_url=CANARY),
                  e.SSLError(endpoint_url=CANARY,error=CANARY), (503,{},b'<Error><Code>SlowDown</Code></Error>')]
        for failure in failures:
            with self.subTest(kind=type(failure).__name__), self.wire([failure]) as send:
                self.safe_error(self.upload,kind=s3.WriteUnknown)
                self.assertEqual(send.call_count,1)
        self.sleep.assert_not_called()

    def test_redirect_region_error_never_dispatches_headbucket_or_second_put(self):
        for status,code in [(301,'PermanentRedirect'),(307,'TemporaryRedirect'),(400,'AuthorizationHeaderMalformed')]:
            body=('<Error><Code>'+code+'</Code><Region>wrong-region</Region></Error>').encode()
            with self.wire([(status,{'x-amz-bucket-region':'wrong-region','location':'https://other.invalid'},body)]) as send:
                self.safe_error(self.upload,kind=s3.TransportError)
                self.assertEqual(send.call_count,1)

    def test_success_response_region_mismatch_is_not_authority(self):
        with self.wire([(200,{'x-amz-version-id':'version-001','x-amz-bucket-region':'wrong'},b'')]) as send:
            self.safe_error(self.upload,'AP00_WRITE_UNKNOWN_REGION_MISMATCH',s3.WriteUnknown)
            self.assertEqual(send.call_count,1)
        with self.wire([(200,{'x-amz-version-id':'version-001','x-amz-bucket-region':'wrong'},b'')]):
            self.safe_error(self.get,'AP00_S3_TARGET_MISMATCH')

    def test_read_stream_failure_closes_body_and_retries_same_version(self):
        from botocore.exceptions import ResponseStreamingError
        class BrokenBody:
            closed=False
            def read(self,n): raise ResponseStreamingError(error=CANARY)
            def close(self): self.closed=True
        body=BrokenBody()
        responses=[dict(VersionId='version-001',ContentLength=1,Body=body,ResponseMetadata={'HTTPStatusCode':200}),
                   dict(VersionId='version-001',ContentLength=1,Body=io.BytesIO(b'x'),ResponseMetadata={'HTTPStatusCode':200})]
        with patch.object(self.client,'get_object',side_effect=responses) as get:
            self.assertEqual(self.get(),b'x')
            self.assertEqual(get.call_args_list[0],get.call_args_list[1])
        self.assertTrue(body.closed)

    def test_no_ambient_credential_discovery_or_config_reads(self):
        from botocore.credentials import CredentialResolver
        from botocore.configloader import load_config
        def config(path):
            self.assertEqual(path,s3.os.devnull)
            return load_config(path)
        with patch.object(CredentialResolver,'load_credentials',side_effect=AssertionError(CANARY)), \
                patch('botocore.configloader.load_config',side_effect=config), \
                patch.dict(s3.os.environ,{'AWS_PROFILE':'SYNTHETIC_FORBIDDEN_PROFILE',
                                         'AWS_CONFIG_FILE':'/forbidden-config',
                                         'AWS_SHARED_CREDENTIALS_FILE':'/forbidden-credentials',
                                         'AWS_ENDPOINT_URL':'http://forbidden.invalid'}):
            client=s3.make_client(TARGET,TARGET_HASH,access_key='SYNTHETIC_ACCESS',secret_key=CANARY)
            self.addCleanup(client.close)
            self.assertEqual(client.meta.endpoint_url,'https://objects.example.invalid')

    def test_exact_get_version_query_and_bytes_no_latest(self):
        version='version/+=='
        with self.wire([(200,{'x-amz-version-id':version,'content-length':str(len(self.raw))},self.raw)]) as send:
            self.assertEqual(self.get(version),self.raw)
        self.assertEqual(send.call_args.args[0].url,
                         'https://objects.example.invalid/synthetic-ap00/'+CONTEXT['object_key']+'?versionId=version%2F%2B%3D%3D')
        self.assertEqual(send.call_args.args[0].method,'GET')
        for version in (None,'','latest','null','unknown'):
            self.safe_error(lambda:self.get(version),'AP00_S3_ADDRESS_INVALID')

    def test_exact_read_403_404_do_not_retry(self):
        for status,code in [(403,'FORBIDDEN'),(404,'EXACT_VERSION_UNAVAILABLE')]:
            with self.wire([(status,{},b'<Error><Code>NoSuchVersion</Code></Error>')]) as send:
                self.safe_error(self.get,'AP00_S3_'+code)
                self.assertEqual(send.call_count,1)
        self.sleep.assert_not_called()

    def test_bounded_read_retries_preserve_exact_request(self):
        from botocore.exceptions import ReadTimeoutError
        with self.wire([ReadTimeoutError(endpoint_url=CANARY),(503,{},b'<Error/>'),
                        (200,{'x-amz-version-id':'version-001','content-length':str(len(self.raw))},self.raw)]) as send:
            self.assertEqual(self.get(),self.raw)
            self.assertEqual(send.call_count,3)
            self.assertEqual(len({call.args[0].url for call in send.call_args_list}),1)
        self.assertEqual([call.args[0] for call in self.sleep.call_args_list],[0.1,0.2])
        with self.wire([ReadTimeoutError(endpoint_url=CANARY) for _ in range(3)]) as send:
            self.safe_error(self.get,'AP00_S3_READ_UNAVAILABLE'); self.assertEqual(send.call_count,3)

    def test_read_tls_and_malformed_response_fail_closed(self):
        from botocore.exceptions import SSLError
        with self.wire([SSLError(endpoint_url=CANARY,error=CANARY)]) as send:
            self.safe_error(self.get,'AP00_S3_TLS_FAILURE'); self.assertEqual(send.call_count,1)
        for headers in ({}, {'x-amz-version-id':'wrong','content-length':'1'},
                        {'x-amz-version-id':'version-001','content-length':'0'},
                        {'x-amz-version-id':'version-001','content-length':str(c.MAX_ENVELOPE_BYTES+1)},
                        {'x-amz-version-id':'version-001','content-length':'1','x-amz-delete-marker':'true'}):
            with self.wire([(200,headers,b'x')]):
                self.safe_error(self.get,'AP00_S3_EXACT_RESPONSE_INVALID')
        with patch.object(self.client,'get_object',return_value={'ResponseMetadata':{}}):
            self.safe_error(self.get,'AP00_S3_RESPONSE_INVALID')

    def test_target_endpoint_region_bucket_and_retry_mismatch_before_send(self):
        for field,value in [('endpoint','https://different.invalid'),('region','another'),('bucket','different')]:
            target=json.loads(TARGET); target[field]=value; raw=dr.canonical(target)
            self.safe_error(lambda:s3.Writer(self.client,raw,dr.sha(raw)),'AP00_S3_TARGET_OR_CLIENT_MISMATCH')
        self.safe_error(lambda:self.writer.create_once(H('wrong'),CONTEXT['object_key'],self.raw,
                        custody_version_sha256=CONTEXT['custody_version_sha256'],retain_until=UNTIL),'AP00_S3_ADDRESS_INVALID')
        self.client.meta.config.retries['total_max_attempts']=2
        self.safe_error(self.upload,'AP00_S3_ADDRESS_INVALID')

    def test_retention_and_plaintext_rejected_before_put(self):
        for until in (NOW+timedelta(days=6),NOW,UNTIL.replace(tzinfo=None),UNTIL.replace(microsecond=1),CANARY):
            self.safe_error(lambda:self.writer.create_once(TARGET_HASH,CONTEXT['object_key'],self.raw,
                            custody_version_sha256=CONTEXT['custody_version_sha256'],retain_until=until))
        self.safe_error(lambda:self.writer.create_once(TARGET_HASH,CONTEXT['object_key'],PLAINTEXT,
                        custody_version_sha256=CONTEXT['custody_version_sha256'],retain_until=UNTIL),'AP00_ENVELOPE_INVALID')

    def observe(self):
        return self.observer.observe_version(TARGET_HASH,CONTEXT['object_key'],'version-001',required_until=UNTIL)

    def stub(self):
        from botocore.stub import Stubber
        return Stubber(self.client)

    def test_exact_head_and_retention_observation(self):
        params=dict(Bucket='synthetic-ap00',Key=CONTEXT['object_key'],VersionId='version-001')
        with self.stub() as stub:
            stub.add_response('head_object',dict(VersionId='version-001',ContentLength=12,ResponseMetadata={'HTTPStatusCode':200}),params)
            stub.add_response('get_object_retention',dict(Retention={'Mode':'COMPLIANCE','RetainUntilDate':UNTIL},
                                                        ResponseMetadata={'HTTPStatusCode':200}),params)
            self.assertEqual(self.observe(),dict(version_id='version-001',size=12,mode='COMPLIANCE',retained_until=UNTIL))
            stub.assert_no_pending_responses()

    def test_retention_mismatch_missing_and_wrong_version(self):
        for retention in ({}, {'Mode':'GOVERNANCE','RetainUntilDate':UNTIL},
                          {'Mode':'COMPLIANCE','RetainUntilDate':UNTIL-timedelta(seconds=1)},
                          {'Mode':'COMPLIANCE','RetainUntilDate':UNTIL.replace(tzinfo=None)}):
            with self.stub() as stub:
                stub.add_response('head_object',dict(VersionId='version-001',ContentLength=12,ResponseMetadata={'HTTPStatusCode':200}))
                stub.add_response('get_object_retention',dict(Retention=retention,ResponseMetadata={'HTTPStatusCode':200}))
                self.safe_error(self.observe,'AP00_S3_RETENTION_MISMATCH')
        with self.stub() as stub:
            stub.add_response('head_object',dict(VersionId='wrong',ContentLength=12,ResponseMetadata={'HTTPStatusCode':200}))
            self.safe_error(self.observe,'AP00_S3_EXACT_RESPONSE_INVALID')

    def test_bucket_config_observation_and_mismatches(self):
        for status,enabled,mode,days,valid in [('Enabled','Enabled','COMPLIANCE',7,True),
                  ('Suspended','Enabled','COMPLIANCE',7,False),('Enabled','Enabled','GOVERNANCE',7,False),
                  ('Enabled','Enabled','COMPLIANCE',6,False),('Enabled',None,'COMPLIANCE',7,False)]:
            config={'Rule':{'DefaultRetention':{'Mode':mode,'Days':days}}}
            if enabled: config['ObjectLockEnabled']=enabled
            with self.stub() as stub:
                stub.add_response('get_bucket_versioning',dict(Status=status,ResponseMetadata={'HTTPStatusCode':200}),{'Bucket':'synthetic-ap00'})
                stub.add_response('get_object_lock_configuration',dict(ObjectLockConfiguration=config,ResponseMetadata={'HTTPStatusCode':200}),{'Bucket':'synthetic-ap00'})
                if valid: self.assertEqual(self.observer.observe_bucket()['default_retention_days'],7)
                else: self.safe_error(self.observer.observe_bucket,'AP00_S3_BUCKET_CONFIGURATION_MISMATCH')

    def test_separate_authority_clients_and_ap01_evidence_hash_layer(self):
        fixture_module=module('dr_tests','test-v126-dr-evidence.py')
        fixture=fixture_module.Fixture()
        receipt=dr.parse(next(raw for raw in fixture.documents.values() if dr.parse(raw)['kind']=='offhost'),'offhost')
        manifest=dr.parse(fixture.documents[receipt['manifest_sha256']],'manifest')
        archive=fixture_module.archive_bytes(fixture_module.synthetic_members(manifest['snapshot_token_sha256']))
        target=fixture.documents[receipt['target_sha256']]
        context=dict(target_sha256=receipt['target_sha256'],manifest_sha256=receipt['manifest_sha256'],
                     object_key=receipt['key'],custody_version_sha256=receipt['encryption']['custody_version_sha256'])
        encryption=codec(context); encrypted=encryption.encrypt(archive)
        receipt.update(ciphertext=dr.file_identity(encrypted),readback=dr.file_identity(encrypted))
        receipt['encryption'].update(backend='aes-256-gcm',backend_sha256=dr.sha(Path(c.__file__).read_bytes()),mode='client-side')
        other=s3.make_client(target,receipt['target_sha256'],access_key='SYNTHETIC_READER',secret_key=CANARY)
        self.addCleanup(other.close)
        self.assertIsNot(other,self.client)
        reader=s3.ExactReader(other,target,receipt['target_sha256'])
        from botocore.response import StreamingBody
        def response():
            return dict(VersionId=receipt['version_id'],ContentLength=len(encrypted),
                        Body=StreamingBody(io.BytesIO(encrypted),len(encrypted)),ResponseMetadata={'HTTPStatusCode':200})
        with patch.object(other,'get_object',side_effect=lambda **kwargs:response()):
            members=dr.independent_readback(reader,encryption,receipt,manifest,1024*1024)
            self.assertEqual(members['application.dump'],fixture_module.synthetic_members(manifest['snapshot_token_sha256'])['application.dump'])
            receipt['ciphertext']['sha256']=H('corrupt')
            self.safe_error(lambda:dr.independent_readback(reader,encryption,receipt,manifest,1024*1024),'READBACK_MISMATCH')


class DependencyTests(OfflineTest):
    def test_stdlib_only_imports_and_bounded_optional_dependency_errors(self):
        script = '''import importlib.util, pathlib, sys
sys.dont_write_bytecode=True
root=pathlib.Path(sys.argv[1])
for name in ('evidence','provisioning','crypto','s3'):
 spec=importlib.util.spec_from_file_location('local_'+name,root/('v126-dr-'+name+'.py'))
 m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
 if name=='crypto':
  try: m.aesgcm()
  except ValueError as e: assert str(e)=='AP00_CRYPTO_DEPENDENCY_UNAVAILABLE'
  else: raise AssertionError('dependency unexpectedly present')
 if name=='s3':
  assert not any(n.split('.')[0] in ('boto3','botocore','urllib3') for n in sys.modules)
  assert not hasattr(m, 'sdk')
print('STDLIB_ONLY_IMPORT_AND_DEPENDENCY_ERRORS_PASS')
'''
        result=subprocess.run([sys.executable,'-S','-c',script,str(ROOT/'scripts')],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('STDLIB_ONLY_IMPORT_AND_DEPENDENCY_ERRORS_PASS',result.stdout)


if __name__=='__main__':
    unittest.main(verbosity=2)
