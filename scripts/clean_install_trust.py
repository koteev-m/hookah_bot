"""Role-separated signed evidence and durable CAS for DISPOSABLE_LOCAL only.

The filesystem journal exercises custody mechanics across processes. It is NOT
an off-VPS service, independent UID boundary, WORM store or provider adapter.
Real enrollment is deliberately unavailable. No private signing key is consumed.
"""
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

from clean_install_policy import Authority, SCOPED_ORIGIN
from staging_release import ProtectedFiles, Store, decode_json, fingerprint, require


ROLES = {"owner", "provider", "verifier", "custodian"}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


class LocalSignedAuthority(Authority):
    def __init__(self, enrollment, *, observe):
        require(enrollment.get("profile") == "DISPOSABLE_LOCAL", "BLOCKED_EXTERNAL_ENROLLMENT")
        self.enrollment, self.observe = copy.deepcopy(enrollment), observe
        self.scope = enrollment["scope"]
        self.files = ProtectedFiles(owner=os.geteuid())
        self.root = Path(enrollment["custody"])
        target = Path(enrollment["directory"])
        require(self.root != target and self.root not in target.parents and target not in self.root.parents,
                "CUSTODY_MUST_BE_OUTSIDE_TARGET")
        self.files.check(self.root, directory=True, private=True)
        self.store = Store(self.root)
        require(self.store.path.exists(), "CUSTODY_HISTORY_MISSING")
        require(set(enrollment["keys"]) == ROLES, "INDEPENDENT_ROLES_REQUIRED")
        keys = []
        for role in sorted(ROLES):
            key = enrollment["keys"][role]
            raw = self.files.read(key["path"])
            require(hashlib.sha256(raw).hexdigest() == key["sha256"], "ENROLLED_KEY_CHANGED")
            canonical_key = subprocess.run(['/usr/bin/openssl', 'pkey', '-pubin', '-outform', 'DER'], input=raw,
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
            require(canonical_key.returncode == 0, 'ENROLLED_KEY_INVALID')
            keys.append(canonical_key.stdout)
        require(len(set(keys)) == len(ROLES), "INDEPENDENT_ROLES_REQUIRED")
        self.request = self.proof = None

    def read_journal(self):
        self.files.check(self.store.path, private=True)
        data = self.store.read()
        require(data["scope"] == fingerprint(self.scope) and data["target"] == self.enrollment["target_binding"],
                "CUSTODY_TARGET_MISMATCH")
        require(isinstance(data["events"], list), "CUSTODY_HISTORY_INVALID")
        require({p.name for p in self.root.glob('object-*.json')} ==
                {'object-' + identity + '.json' for identity in data['events']}, 'CUSTODY_ORPHAN_OBJECT')
        previous = None
        state, pending = None, None
        revoked = False
        for identity in data["events"]:
            require(isinstance(identity, str) and len(identity) == 64
                    and all(c in "0123456789abcdef" for c in identity), "CUSTODY_HISTORY_INVALID")
            event = decode_json(self.files.read(self.root / ("object-" + identity + ".json")))
            require(fingerprint(event) == identity, "CUSTODY_HISTORY_INVALID")
            require(event["previous_event"] == previous, "CUSTODY_HISTORY_INVALID")
            if event['kind'] == 'INTENT':
                require(pending is None and event['request']['previous'] == state, 'CUSTODY_HISTORY_INVALID')
                pending = event['token']
            elif event['kind'] == 'REVOCATION':
                revoked = True
            else:
                require(event['kind'] == 'TERMINAL' and pending == event['token'], 'CUSTODY_HISTORY_INVALID')
                state, pending = fingerprint(event['terminal']), None
            previous = identity
        require(data["tail"] == previous and data['state'] == state and data['pending'] == pending, "CUSTODY_HISTORY_INVALID")
        data['revoked'] = revoked
        return data

    def head(self, installation, *, timeout):
        require(installation == self.scope["installation"], "INSTALLATION_NOT_ENROLLED")
        with self.store.locked():
            value = self.read_journal()
            return {"state": value["state"], "pending": value["pending"]}

    def signed(self, envelope, role):
        require(isinstance(envelope, dict) and set(envelope) == {"payload", "signature"}, "SIGNED_EVIDENCE_REQUIRED")
        key = self.enrollment["keys"][role]
        raw_key = self.files.read(key["path"])
        require(hashlib.sha256(raw_key).hexdigest() == key["sha256"], "ENROLLED_KEY_CHANGED")
        payload = envelope["payload"]
        require(payload.get("role") == role and payload.get("audience") == "CR1_DISPOSABLE_LOCAL"
                and payload.get("scope") == fingerprint(self.scope)
                and payload.get("target") == self.enrollment["target_binding"], "EVIDENCE_SCOPE_MISMATCH")
        require(type(payload.get("expires")) in (int, float)
                and time.time() < payload["expires"] <= time.time() + 300, "AUTHORITY_EXPIRED")
        require(payload.get("epoch") == self.enrollment["epoch"], "AUTHORITY_REVOKED")
        try:
            signature = base64.b64decode(envelope["signature"], validate=True)
        except (ValueError, TypeError):
            require(False, "EVIDENCE_SIGNATURE_INVALID")
        require(len(signature) <= 8192, "EVIDENCE_SIGNATURE_INVALID")
        with tempfile.TemporaryDirectory(prefix="ht-cr1-signature-") as temp:
            root = Path(temp)
            (root / "key").write_bytes(raw_key)
            (root / "signature").write_bytes(signature)
            result = subprocess.run(["/usr/bin/openssl", "dgst", "-sha256", "-verify", str(root / "key"),
                                     "-signature", str(root / "signature")], input=canonical(payload),
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        require(result.returncode == 0, "EVIDENCE_SIGNATURE_INVALID")
        return payload

    def authorize(self, request, *, timeout):
        require(request["scope"] == self.scope, "INSTALLATION_NOT_ENROLLED")
        with self.store.locked():
            require(not self.read_journal()['revoked'], 'AUTHORITY_REVOKED')
        bundle = decode_json(self.files.read(self.enrollment["evidence"]))
        require(set(bundle) == ROLES, "INDEPENDENT_ROLES_REQUIRED")
        payloads = {role: self.signed(bundle[role], role) for role in sorted(ROLES)}
        require(all(p["request"] == fingerprint(request) for p in payloads.values()), "AUTHORITY_REQUEST_MISMATCH")
        revocations = self.signed(decode_json(self.files.read(self.enrollment["revocations"])), "custodian")
        require(revocations['request'] == 'REVOCATIONS', 'REVOCATION_SOURCE_INVALID')
        if revocations['active'] is not True or revocations['revoked'] != []:
            with self.store.locked():
                data = self.read_journal()
                data['revoked'] = True
                self.write_event(data, {'kind': 'REVOCATION', 'evidence': revocations})
            require(False, 'AUTHORITY_REVOKED')
        require(payloads["owner"]["approved"] is True, "OWNER_APPROVAL_REQUIRED")
        provider = payloads["provider"]
        if self.scope['origin'] == SCOPED_ORIGIN:
            binding = self.scope['shared_host']
            require(provider.get('mode') == 'RETAINED_SHARED_HOST'
                    and provider.get('host') == binding['host']
                    and provider.get('admins') == fingerprint(binding['retained_admins'])
                    and provider.get('pending_actions') == []
                    and provider.get('incarnation') == self.scope['incarnation'], 'RETAINED_HOST_NOT_PROVEN')
            require(payloads['owner'].get('shared_host_trust') == fingerprint(binding), 'HOST_ADMIN_TRUST_UNPROVEN')
        else:
            require(provider["reset_complete"] is True and provider["pending_actions"] == []
                    and provider["incarnation"] == self.scope["incarnation"], "PROVIDER_RESET_NOT_PROVEN")
        require(payloads["custodian"]["custody"] == fingerprint({"path": str(self.root), "target": self.enrollment["target_binding"]}),
                "CUSTODY_SOURCE_MISMATCH")
        proof = copy.deepcopy(payloads["verifier"]["facts"])
        require(proof["owner_approval"] == fingerprint(payloads["owner"])
                and proof["facets"]["provider"]["receipt"] == fingerprint(provider)
                and proof["facets"]["custody"]["receipt"] == fingerprint(payloads["custodian"]),
                "EVIDENCE_CHAIN_MISMATCH")
        if self.scope['origin'] == SCOPED_ORIGIN:
            require(proof.get('scoped', {}).get('admin_trust_approval') == fingerprint(payloads['owner'])
                    and proof.get('scoped', {}).get('custody_receipt') == fingerprint(payloads['custodian']),
                    'EVIDENCE_CHAIN_MISMATCH')
        proof["signed_bundle"] = bundle
        self.request, self.proof = copy.deepcopy(request), copy.deepcopy(proof)
        return proof

    def write_event(self, data, event):
        event["previous_event"] = data["tail"]
        identity = fingerprint(event)
        path = self.root / ("object-" + identity + ".json")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(canonical(event)); stream.flush(); os.fsync(stream.fileno())
        data["events"].append(identity)
        data["tail"] = identity
        self.store.write(data)
        require(self.read_journal() == data, "CUSTODY_READBACK_FAILED")

    def begin(self, request, evidence, expected_head, *, timeout):
        # Re-read signatures/revocations immediately before reserving effects.
        require(self.request == request and self.proof == evidence, "UNAUTHORIZED_REQUEST")
        require(self.authorize(request, timeout=timeout) == evidence, "EVIDENCE_CHANGED")
        with self.store.locked():
            data = self.read_journal()
            require(not data['revoked'], 'AUTHORITY_REVOKED')
            require(data["pending"] is None and data["state"] == expected_head, "CUSTODY_CAS_CONFLICT")
            token = fingerprint({"request": request, "tail": data["tail"]})
            data["pending"] = token
            self.write_event(data, {"kind": "INTENT", "token": token, "request": request, "evidence": evidence})
            return token

    def finish(self, token, terminal_state, *, timeout):
        proof = copy.deepcopy(self.proof)
        require(self.request is not None and self.authorize(self.request, timeout=timeout) == proof, 'EVIDENCE_CHANGED')
        require(self.observe(timeout=timeout) == terminal_state["observed"], "INDEPENDENT_POSTSTATE_MISMATCH")
        with self.store.locked():
            data = self.read_journal()
            require(not data['revoked'], 'AUTHORITY_REVOKED')
            require(data["pending"] == token, "CUSTODY_CAS_CONFLICT")
            data.update(pending=None, state=fingerprint(terminal_state))
            self.write_event(data, {"kind": "TERMINAL", "token": token, "terminal": terminal_state})
        return "COMPLETED"
