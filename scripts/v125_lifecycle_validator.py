"""Local proposal consistency checks only; never operational qualification."""

import argparse
import hashlib
import importlib.util
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit


LIFECYCLE = "V125_ISOLATED_TEST_LIFECYCLE"
PROJECT = "Hookah_Tootah"
MAX_BYTES = 65536
TARGET_KEYS = "target_id resource_id control_domain_id epoch_id frontend_origin"
COMMON_KEYS = "format_version project_id lifecycle target release predecessor_inventory_sha256"
BASE_PREREQUISITES = {"isolation", "predecessor_review", "source_review", "configuration_review"}
TRANSITIONS = {
    ("EMPTY_CONFIRMED", "V125_BASELINE_INSTALLED"): {"empty_target", "installation_approval"},
    ("V125_BASELINE_INSTALLED", "TEST_READY_CONFIRMED"): {
        "baseline_installation", "policy_b", "recovery_custody", "testing_approval"
    },
}


class InvalidProposal(ValueError):
    """Fixed, non-sensitive validation reason; never include input values."""


def require(condition, reason):
    if not condition:
        raise InvalidProposal(reason)


def fields(value, keys):
    require(type(value) is dict and set(value) == set(keys.split()), "INVALID_FIELDS")


def matches(value, pattern):
    return type(value) is str and re.fullmatch(pattern, value) is not None


def digest(value):
    require(matches(value, r"[0-9a-f]{64}") and value != "0" * 64, "INVALID_DIGEST")


def identifier(value):
    require(matches(value, r"[a-z0-9][a-z0-9_-]{2,95}"), "INVALID_IDENTIFIER")


def operation_id(value):
    require(matches(value, r"ht-v125-proposal-[a-z0-9][a-z0-9-]{0,63}"), "INVALID_OPERATION_ID")


def origin(value):
    require(type(value) is str and len(value) <= 253, "INVALID_ORIGIN")
    parsed = urlsplit(value)
    require(
        parsed.scheme == "https" and parsed.netloc == parsed.hostname
        and not parsed.path and not parsed.query and not parsed.fragment
        and matches(parsed.hostname, r"[a-z0-9]+(?:[.-][a-z0-9]+)+")
        and value == "https://" + parsed.hostname,
        "INVALID_ORIGIN",
    )


def target(value):
    fields(value, TARGET_KEYS)
    for key in ("target_id", "resource_id", "control_domain_id"):
        identifier(value[key])
    digest(value["epoch_id"])
    origin(value["frontend_origin"])


def release(value, expected_origin):
    fields(value, "source_commit postgresql_schema_version backend frontend migration_manifest_sha256 config_sha256")
    require(matches(value["source_commit"], r"[0-9a-f]{40}")
            and value["source_commit"] != "0" * 40, "INVALID_SOURCE_COMMIT")
    require(type(value["postgresql_schema_version"]) is int and value["postgresql_schema_version"] == 125,
            "V125_REQUIRED")
    for role in ("backend", "frontend"):
        image = value[role]
        fields(image, "source_commit postgresql_schema_version digest" + (" origin" if role == "frontend" else ""))
        digest(image["digest"])
        require(image["source_commit"] == value["source_commit"], "IMAGE_SOURCE_MISMATCH")
        require(type(image["postgresql_schema_version"]) is int and image["postgresql_schema_version"] == 125,
                "IMAGE_VERSION_MISMATCH")
    require(value["frontend"]["origin"] == expected_origin, "FRONTEND_ORIGIN_MISMATCH")
    digest(value["migration_manifest_sha256"])
    digest(value["config_sha256"])


def common(value):
    require(type(value["format_version"]) is int and value["format_version"] == 1,
            "INVALID_FORMAT_VERSION")
    require(value["project_id"] == PROJECT and value["lifecycle"] == LIFECYCLE,
            "WRONG_PROJECT_OR_LIFECYCLE")
    target(value["target"])
    release(value["release"], value["target"]["frontend_origin"])
    digest(value["predecessor_inventory_sha256"])


def check(proposal, scope):
    # Scope is a separate comparison input, not an authority store or registry.
    fields(scope, COMMON_KEYS + " excluded_targets used_operation_ids")
    common(scope)
    require(matches(scope["target"]["target_id"], r"ht-v125-isolated-[a-z0-9][a-z0-9-]{0,63}"),
            "ISOLATED_TARGET_REQUIRED")
    fields(scope["excluded_targets"], "old_staging production")
    for excluded in scope["excluded_targets"].values():
        target(excluded)
        require(all(scope["target"][key] != excluded[key] for key in TARGET_KEYS.split()),
                "EXCLUDED_TARGET_REUSE")
    used = scope["used_operation_ids"]
    require(type(used) is list and len(used) <= 256, "INVALID_OPERATION_HISTORY")
    for item in used:
        operation_id(item)
    require(len(set(used)) == len(used), "DUPLICATE_OPERATION_HISTORY")

    fields(proposal, COMMON_KEYS + " mode execution operation_id current proposed_state prerequisites configuration")
    common(proposal)
    for key in COMMON_KEYS.split():
        require(proposal[key] == scope[key], "SCOPE_BINDING_MISMATCH")
    require(proposal["mode"] == "PROPOSAL_ONLY", "PROPOSAL_ONLY_REQUIRED")
    require(proposal["execution"] == "NOT_EXECUTED", "EXECUTION_CLAIM_FORBIDDEN")
    operation_id(proposal["operation_id"])
    require(proposal["operation_id"] not in used, "OPERATION_ID_REUSED")

    config = proposal["configuration"]
    fields(config, "frontend_origin telegram user_access control_import data_import")
    require(config == {
        "frontend_origin": scope["target"]["frontend_origin"],
        "telegram": "DISCONNECTED", "user_access": "CLOSED",
        "control_import": "NONE", "data_import": "NONE",
    }, "DISCONNECTED_CONFIGURATION_REQUIRED")

    current = proposal["current"]
    fields(current, "state database postgresql_schema_version recovery_point_sha256 state_evidence_sha256")
    digest(current["state_evidence_sha256"])
    require(type(current["state"]) is str and type(proposal["proposed_state"]) is str,
            "INVALID_STATE")
    transition = (current["state"], proposal["proposed_state"])
    require(transition in TRANSITIONS, "INVALID_TRANSITION")
    if current["state"] == "EMPTY_CONFIRMED":
        require(current["database"] == "ABSENT" and current["postgresql_schema_version"] is None
                and current["recovery_point_sha256"] is None, "EMPTY_DATABASE_CONFLICT")
    else:
        require(current["database"] == "PRESENT" and type(current["postgresql_schema_version"]) is int
                and current["postgresql_schema_version"] == 125, "BASELINE_DATABASE_CONFLICT")
        digest(current["recovery_point_sha256"])

    prerequisites = proposal["prerequisites"]
    fields(prerequisites, " ".join(BASE_PREREQUISITES | TRANSITIONS[transition]))
    for reference in prerequisites.values():
        fields(reference, "status target_id epoch_id source_commit config_sha256 recovery_point_sha256 evidence_sha256")
        require(reference["status"] == "EVIDENCE_REFERENCED", "UNCONFIRMED_PREREQUISITE")
        require(reference["target_id"] == scope["target"]["target_id"]
                and reference["epoch_id"] == scope["target"]["epoch_id"]
                and reference["source_commit"] == scope["release"]["source_commit"]
                and reference["config_sha256"] == scope["release"]["config_sha256"]
                and reference["recovery_point_sha256"] == current["recovery_point_sha256"],
                "PREREQUISITE_BINDING_MISMATCH")
        digest(reference["evidence_sha256"])
    state_key = "empty_target" if current["state"] == "EMPTY_CONFIRMED" else "baseline_installation"
    require(current["state_evidence_sha256"] == prerequisites[state_key]["evidence_sha256"],
            "STATE_EVIDENCE_MISMATCH")
    require(proposal["predecessor_inventory_sha256"] == prerequisites["predecessor_review"]["evidence_sha256"],
            "PREDECESSOR_EVIDENCE_MISMATCH")


def parse_json(raw):
    require(type(raw) is str and len(raw.encode("utf-8")) <= MAX_BYTES, "INVALID_JSON_SIZE")

    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    def constant(_value):
        raise InvalidProposal("NONFINITE_JSON_NUMBER")

    return json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)


def result(error=None):
    return {
        "status": "PROPOSAL_ONLY", "valid": error is None,
        "execution_authorized": False, "operationally_qualified": False,
        "errors": [] if error is None else [error],
    }


def validate_json(proposal_json, scope_json):
    """Validate two untrusted JSON documents without I/O or persistent replay state."""
    try:
        check(parse_json(proposal_json), parse_json(scope_json))
    except InvalidProposal as error:
        return result(str(error))
    except (ValueError, TypeError, RecursionError, UnicodeError):
        return result("MALFORMED_JSON")
    return result()


def initial_install_state(*, evidence_kind, database, schema_version, outcome):
    """Classify local claims, never authenticate EMPTY evidence or a DB observation.

    PREPARED/INSTALLED describe one attempt's diagnostic poststate, not permission
    to resume it. Even an unchanged local claim cannot authorize another attempt.
    """
    if schema_version is not None and type(schema_version) is not int:
        return "UNKNOWN"
    claims = (evidence_kind, database, schema_version, outcome)
    if claims == ("EMPTY_TARGET", "ABSENT", None, "NOT_STARTED"):
        return "EMPTY"
    if claims == ("DATABASE", "PRESENT", 0, "PREPARED"):
        return "PREPARED_EMPTY_POSTGRESQL"
    if claims == ("DATABASE", "PRESENT", 125, "INSTALLED"):
        return "INSTALLED_CLOSED_V125"
    return "UNKNOWN"


def inspect_initial_install(
    proposal_json, scope_json, *, evidence_kind, database, schema_version, outcome,
    credential_import, postgresql_version, postgresql_image, expected_postgresql_image,
    postgresql_platform, migration_files, configuration_bytes, backend_archive, backend_tag,
):
    """Offline initial-install material assessment; NO operational entrypoint.

    JSON and keyword arguments are untrusted comparison inputs, not a new evidence
    schema or an acquisition interface. migration_files maps PostgreSQL SQL names
    to actual bytes. The archive is read by the existing Docker-save verifier.
    Neither a JSON flag nor this return value can supply independent authority.
    """
    assessment = {
        **result(), "candidate_state": "UNKNOWN", "verified_state": "UNKNOWN",
        "execution_blockers": ["INDEPENDENT_INITIAL_INSTALL_AUTHORITY_UNAVAILABLE"],
    }
    try:
        proposal, scope = parse_json(proposal_json), parse_json(scope_json)
        check(proposal, scope)
        require(proposal["current"]["state"] == "EMPTY_CONFIRMED",
                "INITIAL_INSTALL_PROPOSAL_REQUIRED")
        require(credential_import == "NONE", "CREDENTIAL_IMPORT_FORBIDDEN")
        require(postgresql_version == "17.10", "POSTGRESQL_17_10_REQUIRED")
        require(postgresql_platform == "linux/amd64", "POSTGRESQL_PLATFORM_MISMATCH")
        # Registry digest, not a Docker config/image ID. No historical default pin.
        pattern = r"docker\.io/library/postgres@sha256:[0-9a-f]{64}"
        require(matches(expected_postgresql_image, pattern), "EXACT_POSTGRESQL_IMAGE_REQUIRED")
        digest(expected_postgresql_image.rsplit(":", 1)[1])
        require(postgresql_image == expected_postgresql_image, "POSTGRESQL_IMAGE_MISMATCH")
        # Public sanitized configuration descriptor only, never .env/secret bytes.
        require(type(configuration_bytes) is bytes and 0 < len(configuration_bytes) <= MAX_BYTES,
                "CONFIGURATION_BYTES_REQUIRED")
        require(hashlib.sha256(configuration_bytes).hexdigest() == scope["release"]["config_sha256"],
                "CONFIGURATION_BYTES_MISMATCH")
        require(parse_json(configuration_bytes.decode("utf-8")) == proposal["configuration"],
                "CONFIGURATION_CONTENT_MISMATCH")

        state = initial_install_state(evidence_kind=evidence_kind, database=database,
                                      schema_version=schema_version, outcome=outcome)
        require(state != "UNKNOWN", "UNKNOWN_OR_CONTRADICTORY_INSTALLATION")

        # Hash the supplied bytes, not caller-declared file hashes. This inventory
        # encoding is local material comparison, never an operational manifest.
        require(type(migration_files) is dict and len(migration_files) == 125,
                "V125_MIGRATION_INVENTORY_REQUIRED")
        inventory, versions = {}, []
        for name, raw in migration_files.items():
            require(matches(name, r"V[1-9][0-9]*__[A-Za-z0-9_]+\.sql"),
                    "INVALID_MIGRATION_NAME")
            require(type(raw) is bytes and bool(raw), "MIGRATION_BYTES_REQUIRED")
            versions.append(int(name.split("__", 1)[0][1:]))
            inventory[name] = hashlib.sha256(raw).hexdigest()
        require(sorted(versions) == list(range(1, 126)), "V125_MIGRATION_INVENTORY_REQUIRED")
        inventory_raw = (json.dumps(inventory, sort_keys=True, separators=(",", ":")) + "\n").encode("ascii")
        require(hashlib.sha256(inventory_raw).hexdigest() == scope["release"]["migration_manifest_sha256"],
                "MIGRATION_BYTES_MISMATCH")
        require(matches(backend_tag, r"[a-z0-9][a-z0-9._/-]*:[0-9a-f]{40}"),
                "INVALID_BACKEND_TAG")

        spec = importlib.util.spec_from_file_location(
            "v125_local_archive", Path(__file__).with_name("docker-save-archive.py"))
        archive = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(archive)
        try:
            archive.verify_archive(
                Path(backend_archive), expected_tag=backend_tag,
                expected_image_id="sha256:" + scope["release"]["backend"]["digest"],
                expected_revision=scope["release"]["source_commit"],
                expected_source=archive.EXPECTED_SOURCE,
            )
        except (archive.ArchiveError, OSError, ValueError, TypeError, KeyError):
            raise InvalidProposal("BACKEND_ARCHIVE_MISMATCH") from None
        assessment["candidate_state"] = state
    except InvalidProposal as error:
        assessment.update(result(str(error)))
    except (OSError, ValueError, TypeError, RecursionError, UnicodeError):
        assessment.update(result("LOCAL_INPUT_INVALID"))
    return assessment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", required=True, help="Local comparison JSON; not operational authority")
    args = parser.parse_args()
    try:
        with Path(args.scope).open("rb") as stream:
            scope = stream.read(MAX_BYTES + 1).decode("utf-8")
        proposal = sys.stdin.buffer.read(MAX_BYTES + 1).decode("utf-8")
        outcome = validate_json(proposal, scope)
    except (OSError, UnicodeError):
        outcome = result("LOCAL_INPUT_UNREADABLE")
    print(json.dumps(outcome, sort_keys=True))
    return 0 if outcome["valid"] else 1


if __name__ == "__main__":
    sys.exit(main())
