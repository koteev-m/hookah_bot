"""AP-00 AES-256-GCM envelope. No key acquisition, custody or execution CLI."""
import importlib.util
import os
from pathlib import Path
from typing import Protocol

_spec = importlib.util.spec_from_file_location('dr_evidence', Path(__file__).with_name('v126-dr-evidence.py'))
dr = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dr)
S = dr.schema
MAGIC = b'HTDR'
MAX_HEADER_BYTES = 2048
MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024
MAX_ENVELOPE_BYTES = 8 + MAX_HEADER_BYTES + MAX_PLAINTEXT_BYTES + 16
CONTEXT = {'target_sha256': 'sha', 'manifest_sha256': 'sha', 'object_key': 'key',
           'custody_version_sha256': 'sha'}
HEADER = {**CONTEXT, 'format_version': S.enum(1), 'algorithm': S.enum('AES-256-GCM'),
          'ciphertext_size': 'positive'}
# Nonce syntax is checked separately; all other fields use the AP-01 closed rules.


class KeyProvider(Protocol):
    """Caller supplies exactly 32 key bytes by non-secret custody-version hash.

    No implementation or secure memory zeroization guarantee is provided here.
    """
    def resolve(self, custody_version_sha256: str) -> bytes: ...


def aesgcm():
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        return AESGCM
    except ImportError:
        raise ValueError('AP00_CRYPTO_DEPENDENCY_UNAVAILABLE') from None


def context(value):
    S.check(value, CONTEXT)
    if value['object_key'] != 'bundles/' + value['manifest_sha256'] + '.enc':
        raise ValueError('AP00_ENVELOPE_INVALID')
    return dict(value)


def inspect_envelope(raw, expected_context):
    """Strict structural check only; NEVER authenticates or yields plaintext."""
    try:
        expected = context(expected_context)
        if type(raw) is not bytes or not 8 < len(raw) <= MAX_ENVELOPE_BYTES or raw[:4] != MAGIC:
            raise ValueError
        length = int.from_bytes(raw[4:8], 'big')
        if not 0 < length <= MAX_HEADER_BYTES or len(raw) < 8 + length + 17:
            raise ValueError
        header_raw = raw[8:8 + length]
        header = dr.database.strict_json(header_raw)
        if type(header) is not dict or set(header) != set(HEADER) | {'nonce_hex'}:
            raise ValueError
        S.check({k: v for k, v in header.items() if k != 'nonce_hex'}, HEADER)
        nonce = header['nonce_hex']
        if type(nonce) is not str or not dr.re.fullmatch('[0-9a-f]{24}', nonce):
            raise ValueError
        if (header_raw != dr.canonical(header)
                or {k: header[k] for k in CONTEXT} != expected
                or not 17 <= header['ciphertext_size'] <= MAX_PLAINTEXT_BYTES + 16
                or len(raw) != 8 + length + header['ciphertext_size']):
            raise ValueError
        return header, raw[:8 + length], raw[8 + length:]
    except Exception:
        raise ValueError('AP00_ENVELOPE_INVALID') from None


class Envelope:
    """Bind expected context independently of the received envelope.

    Production randomness is internal. Deterministic vectors patch os.urandom only
    inside tests; there is no caller-controlled nonce/randomness argument.
    """
    def __init__(self, key_provider: KeyProvider, expected_context):
        try:
            self._context = context(expected_context)
        except Exception:
            raise ValueError('AP00_ENVELOPE_INVALID') from None
        self._provider = key_provider

    def _key(self):
        try:
            key = self._provider.resolve(self._context['custody_version_sha256'])
            if type(key) is not bytes or len(key) != 32:
                raise ValueError
            return key
        except Exception:
            raise ValueError('AP00_KEY_UNAVAILABLE') from None

    def encrypt(self, plaintext):
        primitive = aesgcm()
        try:
            if type(plaintext) is not bytes or not 0 < len(plaintext) <= MAX_PLAINTEXT_BYTES:
                raise ValueError
            header = {**self._context, 'format_version': 1, 'algorithm': 'AES-256-GCM',
                      'nonce_hex': os.urandom(12).hex(), 'ciphertext_size': len(plaintext) + 16}
            header_raw = dr.canonical(header)
            aad = MAGIC + len(header_raw).to_bytes(4, 'big') + header_raw
            return aad + primitive(self._key()).encrypt(bytes.fromhex(header['nonce_hex']), plaintext, aad)
        except Exception:
            raise ValueError('AP00_ENCRYPTION_FAILED') from None

    def decrypt(self, ciphertext, metadata=None):
        primitive = aesgcm()
        try:
            # Backward-compatible AP-01 Encryption protocol. Context is constructor-bound,
            # because the old receipt's encryption subobject alone is insufficient AAD.
            if metadata is not None:
                S.check(metadata, S.SCHEMAS['offhost']['encryption'])
                if (metadata['backend'] != 'aes-256-gcm' or metadata['format_version'] != '1'
                        or metadata['mode'] != 'client-side'
                        or metadata['backend_sha256'] != dr.sha(Path(__file__).read_bytes())
                        or metadata['custody_version_sha256'] != self._context['custody_version_sha256']):
                    raise ValueError
            header, aad, payload = inspect_envelope(ciphertext, self._context)
            return primitive(self._key()).decrypt(bytes.fromhex(header['nonce_hex']), payload, aad)
        except Exception:
            # AESGCM.decrypt returns bytes only after verifying the full 128-bit tag.
            raise ValueError('AP00_DECRYPTION_FAILED') from None
