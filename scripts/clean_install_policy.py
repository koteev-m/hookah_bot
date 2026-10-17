"""CR-1 policy for staging_release.Controller; no CLI or live authority adapter.

An Authority implementation is trusted code enrolled independently of requests.
Implemented adapters in this tree are deliberately disposable local witnesses.
Do not substitute operator JSON, OCI labels or a local file for that boundary.
"""

from abc import ABC, abstractmethod
import re
import time


ORIGIN = "CLEAN_REINSTALL_V126"
SCOPED_ORIGIN = "SHARED_HOST_HOOKAH_SCOPED_REPLACEMENT"
FACETS = {"provider", "storage", "executors", "database", "telegram", "ingress", "custody"}
TOOLS = {"source", "tree", "closure", "runtime"}


class Authority(ABC):
    """Independent, bounded, authenticated witness + durable CAS journal.

    head returns {state: hash-or-None, pending: token-or-None}. authorize must
    acquire current evidence from enrolled sources, bind the entire request and
    exact owner/CI/security approvals; it must not accept facts from that request.
    begin atomically consumes that authorization and compares expected head,
    reserving the physical target/incarnation AND DB/volume identity across all
    installation IDs, even if the executor dies before its local write. Caller
    UUIDs must never create parallel authority for one physical target.
    finish independently acquires/compares the poststate, appends the full
    sanitized terminal record, retains all intents/evidence off-target and
    advances the head to its hash. Lost acknowledgments are never retried.
    All calls must enforce timeout, revocations, freshness and reader identity.
    """

    @abstractmethod
    def head(self, installation, *, timeout):
        raise NotImplementedError

    @abstractmethod
    def authorize(self, request, *, timeout):
        raise NotImplementedError

    @abstractmethod
    def begin(self, request, evidence, expected_head, *, timeout):
        raise NotImplementedError

    @abstractmethod
    def finish(self, token, terminal_state, *, timeout):
        raise NotImplementedError


def scope(value, require, digest):
    require(set(value) == ({"project_id", "origin", "installation", "incarnation", "ancestry", "tooling"}
                            | ({"shared_host"} if value.get("origin") == SCOPED_ORIGIN else set())),
            "CLEAN_SCOPE_INVALID")
    require(value["project_id"] == "Hookah_Tootah" and value["origin"] in (ORIGIN, SCOPED_ORIGIN),
            "CLEAN_ORIGIN_REQUIRED")
    require(isinstance(value["installation"], str)
            and re.fullmatch(r"[0-9a-f]{32}", value["installation"]), "INSTALLATION_ID_INVALID")
    require(all(digest(value[key]) for key in ("incarnation", "ancestry")), "CLEAN_BINDING_INVALID")
    tool = value["tooling"]
    require(isinstance(tool, dict) and set(tool) == TOOLS
            and all(isinstance(tool[k], str) and re.fullmatch(r"[0-9a-f]{40}", tool[k])
                    for k in ("source", "tree"))
            and all(digest(tool[k]) for k in ("closure", "runtime")), "TOOLING_IDENTITY_INVALID")

    if value["origin"] == SCOPED_ORIGIN:
        from shared_host_scope import validate_binding
        validate_binding(value["shared_host"])


def manifest(items, require, digest):
    require(isinstance(items, list) and 126 <= len(items) <= 10000, "MIGRATION_MANIFEST_INVALID")
    for number, item in enumerate(items, 1):
        require(isinstance(item, dict) and set(item) == {"version", "script", "sha256", "checksum"}
                and type(item["version"]) is int and item["version"] == number
                and isinstance(item["script"], str)
                and re.fullmatch(r"V" + str(number) + r"__[A-Za-z0-9_]+\.sql", item["script"])
                and digest(item["sha256"]) and type(item["checksum"]) is int
                and -(2 ** 31) <= item["checksum"] < 2 ** 31, "MIGRATION_MANIFEST_INVALID")
    return items


def image(record, require, digest, image_id):
    require(isinstance(record, dict) and set(record) == {
        "id", "source", "revision", "platform", "archive", "manifest", "schema"}, "CLEAN_IMAGE_INVALID")
    require(image_id(record["id"]) and record["source"] == "https://github.com/koteev-m/hookah_bot"
            and isinstance(record["revision"], str) and re.fullmatch(r"[0-9a-f]{40}", record["revision"])
            and record["platform"] == "linux/amd64" and digest(record["archive"])
            and digest(record["schema"]), "CLEAN_IMAGE_INVALID")
    manifest(record["manifest"], require, digest)
    return record


def history(items):
    return [{"rank": n, "version": m["version"], "script": m["script"],
             "checksum": m["checksum"], "success": True} for n, m in enumerate(items, 1)]


def snapshot(value, target, require, digest):
    require(isinstance(value, dict) and set(value) == {
        "installation", "incarnation", "database", "configuration", "traffic", "image",
        "history", "user_objects", "unidentified_writers", "backend_count", "schema"}
        | ({"resource_guard"} if target["origin"] == SCOPED_ORIGIN else set()), "CLEAN_OBSERVATION_INVALID")
    if target["origin"] == SCOPED_ORIGIN:
        require(value["resource_guard"] == target["shared_host"]["runtime_guard"], "RESOURCE_GUARD_CHANGED")
    require(value["installation"] == target["installation"]
            and value["incarnation"] == target["incarnation"], "INSTALLATION_CHANGED")
    db = value["database"]
    require(isinstance(db, dict) and set(db) == {"system_id", "database", "role", "volume", "network"}
            and all(isinstance(v, str) and 0 < len(v) <= 128 for v in db.values()), "DATABASE_BINDING_INVALID")
    require(digest(value["configuration"]) and digest(value["schema"])
            and value["traffic"] in ("CLOSED", "SMOKE", "PUBLIC"),
            "CLEAN_CONFIGURATION_INVALID")
    require(value["unidentified_writers"] == [], "UNEXCLUDED_WRITER")
    require(type(value["backend_count"]) is int and value["backend_count"] in (0, 1), "BACKEND_COUNT_INVALID")


def evidence(value, request, target, require, digest, fingerprint):
    require(isinstance(value, dict) and value.get("request") == fingerprint(request), "AUTHORITY_REQUEST_MISMATCH")
    require(type(value.get("expires")) in (int, float) and time.time() < value["expires"] <= time.time() + 300,
            "AUTHORITY_EXPIRED")
    require(value.get("tooling") == target["tooling"] and value.get("ancestry") == target["ancestry"]
            and value.get("historical_run724") == "UNKNOWN", "AUTHORITY_IDENTITY_MISMATCH")
    require(value.get("pending_effects") == [] and value.get("external_writers") == [], "LEGACY_EFFECTS_UNRESOLVED")
    facets = value.get("facets", {})
    require(set(facets) == FACETS and all(isinstance(v, dict) and v.get("verdict") ==
            ("RETAINED_DEPENDENCY" if k == "provider" and target["origin"] == SCOPED_ORIGIN else "EXCLUDED")
            and digest(v.get("receipt")) for k, v in facets.items()), "CESSATION_EVIDENCE_INCOMPLETE")
    require(digest(value.get("owner_approval")), "OWNER_APPROVAL_REQUIRED")
    if target["origin"] == SCOPED_ORIGIN:
        from shared_host_scope import retirement_evidence
        retirement_evidence(value.get("scoped"), target, request)
    plan = request["plan"]
    if plan["kind"] == "BOOTSTRAP":
        require((target["origin"] == SCOPED_ORIGIN or digest(value.get("reset"))) and digest(value.get("empty"))
                and digest(value.get("loss_scope")), "RESET_EMPTY_AUTHORITY_REQUIRED")
    else:
        # A receipt must attest current policy readiness, not merely backup existence.
        require(digest(value.get("recovery")), "RECOVERY_READINESS_REQUIRED")
    if plan.get("image"):
        require(value.get("ci_revision") == plan["image"]["revision"]
                and digest(value.get("ci_receipt")), "EXACT_IMAGE_CI_REQUIRED")
    if plan["kind"] in ("FORWARD", "ROLLBACK"):
        expected = "COMPATIBLE_FORWARD" if plan["kind"] == "FORWARD" else "CODE_ONLY_SAME_SCHEMA"
        require(digest(value.get("compatibility")) and value.get("compatibility_verdict") == expected,
                "REVIEWED_COMPATIBILITY_REQUIRED")
    if plan["kind"] == "ACCEPT":
        require(digest(value.get("smoke")), "MANUAL_SMOKE_REQUIRED")
    if plan["kind"] == "PUBLIC":
        require(digest(value.get("public_handoff")), "PUBLIC_HANDOFF_REQUIRED")


def transition(state, before, plan, require):
    kind, candidate = plan["kind"], plan.get("image")
    if kind == "BOOTSTRAP":
        require(state is None, "INSTALLATION_ALREADY_EXISTS")
        require(len(candidate["manifest"]) == 126, "BOOTSTRAP_V126_REQUIRED")
        require(before["image"] is None and before["backend_count"] == 0
                and before["history"] == [] and before["user_objects"] == []
                and before["traffic"] == "CLOSED", "EMPTY_DATABASE_REQUIRED")
        return "CLOSED_V126", "CLOSED"
    require(state is not None, "INSTALLATION_NOT_ACCEPTED")
    require(before == state["observed"], "ACCEPTED_STATE_DRIFT")
    phase = state["status"]
    if kind == "TEST":
        require(phase == "CLOSED_V126", "TEST_ADMISSION_POSITION_INVALID")
        return "TEST_ADMITTED", "SMOKE"
    if kind == "ACCEPT":
        require(phase in ("TEST_ADMITTED", "READY_FOR_SMOKE"), "SMOKE_POSITION_INVALID")
        return ("AWAITING_PUBLIC_HANDOFF" if phase == "TEST_ADMITTED" else "ACCEPTED"), before["traffic"]
    if kind == "PUBLIC":
        require(phase == "AWAITING_PUBLIC_HANDOFF", "PUBLIC_HANDOFF_POSITION_INVALID")
        return "ACCEPTED", "PUBLIC"
    require(phase == "ACCEPTED" and before["traffic"] == "PUBLIC", "INSTALLATION_NOT_ACCEPTED")
    current = state["image"]
    require(candidate["id"] != current["id"], "IMAGE_ALREADY_ACCEPTED")
    old, new = current["manifest"], candidate["manifest"]
    if kind in ("APP", "ROLLBACK"):
        require(new == old and candidate["schema"] == current["schema"], "SCHEMA_INCOMPATIBLE_USE_FORWARD_FIX")
    else:
        require(kind == "FORWARD" and len(new) > len(old) and new[:len(old)] == old,
                "APPEND_ONLY_MIGRATIONS_REQUIRED")
    return "READY_FOR_SMOKE", "PUBLIC"
