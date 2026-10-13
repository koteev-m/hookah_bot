#!/usr/bin/env python3
"""V125 ordinary releases; live use requires a separately enrolled staging target."""

import argparse
import contextlib
import copy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit


SOURCE = "https://github.com/koteev-m/hookah_bot"
LIVE_REFUSAL = "LEGACY_TRANSITION_REQUIRED"
REGISTRATION = Path("/etc/hookah-bot/staging-release.json")
REMOTE_SCRIPT = "/opt/hookah-bot/staging_release.py"
MAX_OUTPUT = 8 * 1024 * 1024


class Refused(ValueError):
    """Only fixed reason codes are emitted; never echo input or runtime errors."""


def require(condition, reason):
    if not condition:
        raise Refused(reason)


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def image_id(value):
    return isinstance(value, str) and re.fullmatch(r"sha256:[0-9a-f]{64}", value) is not None


def validate_scope(scope):
    require(scope["project_id"] == "Hookah_Tootah", "PROJECT_IDENTITY_MISMATCH")
    require(re.fullmatch(r"[a-z0-9][a-z0-9_-]+", scope["project"]) is not None,
            "COMPOSE_IDENTITY_INVALID")
    directory = Path(scope["directory"])
    require(directory.is_absolute() and str(directory) == scope["directory"]
            and ".." not in directory.parts, "COMPOSE_PATH_INVALID")
    require(scope["compose_file"] == str(directory / "docker-compose.yml"),
            "COMPOSE_PATH_INVALID")
    for key, scheme in (("local_url", "http"), ("public_url", "https")):
        url = urlsplit(scope[key])
        require(url.scheme == scheme and url.hostname and not url.username and not url.password
                and not url.path and not url.query and not url.fragment
                and (key != "local_url" or url.hostname == "127.0.0.1"), "STAGING_ORIGIN_INVALID")
    protected = scope["protected"]
    db, prod = protected["postgres"], protected["production"]
    require(image_id("sha256:" + db["container_id"])
            and image_id("sha256:" + prod["database_container_id"]), "DB_IDENTITY_INVALID")
    require(scope["project"] != prod["project"]
            and db["container_id"] != prod["database_container_id"]
            and db["volume"] not in prod["volumes"]
            and db["network"] not in prod["networks"], "PRODUCTION_RESOURCE_OVERLAP")
    require(all(isinstance(db[key], str) and db[key]
                for key in ("system_id", "database", "user", "volume", "network")),
            "DB_IDENTITY_INVALID")
    for value in (db["fingerprint"], prod["fingerprint"], protected["shared_fingerprint"],
                  scope["compose_fingerprint"], scope["configuration_fingerprint"],
                  scope["migration_fingerprint"]):
        require(digest(value), "IDENTITY_FINGERPRINT_INVALID")


def validate_image(record, scope):
    require(isinstance(record, dict) and image_id(record.get("id")), "IMAGE_MISSING")
    require(record.get("available") is True, "IMAGE_UNAVAILABLE")
    require(record.get("source") == SOURCE and record.get("platform") == "linux/amd64"
            and isinstance(record.get("revision"), str)
            and re.fullmatch(r"[0-9a-f]{40}", record["revision"]) is not None,
            "IMAGE_PROVENANCE_MISMATCH")
    require(record.get("ci_sha") == record["revision"] and record.get("ci_status") == "success"
            and record.get("approved") is True, "IMAGE_NOT_APPROVED")
    require(type(record.get("postgresql_head")) is int and record["postgresql_head"] == 125
            and record.get("migration_fingerprint") == scope["migration_fingerprint"],
            "MIGRATION_RELEASE_REQUIRED")
    # Keep only these non-secret fields in the operation record.
    keys = ("id", "source", "platform", "revision", "ci_sha", "ci_status", "approved",
            "postgresql_head", "migration_fingerprint", "available")
    return {key: record[key] for key in keys}


def validate_snapshot(snapshot, scope, expected_image):
    require(all(snapshot.get(key) == scope[key] for key in
                ("project", "directory", "compose_file", "compose_fingerprint",
                 "configuration_fingerprint")), "STAGING_IDENTITY_MISMATCH")
    require(snapshot.get("protected") == scope["protected"], "PROTECTED_RESOURCES_CHANGED")
    backend = snapshot["backend"]
    require(backend.get("project") == scope["project"] and backend.get("service") == "backend"
            and type(backend.get("count")) is int and backend["count"] == 1
            and backend.get("image_id") == expected_image and type(backend.get("running")) is bool,
            "BACKEND_IDENTITY_MISMATCH")
    db = scope["protected"]["postgres"]
    require(backend.get("database") == {key: db[key] for key in
                                       ("container_id", "system_id", "database", "user", "network")}
            and backend.get("db_binding_verified") is True, "DATABASE_BINDING_MISMATCH")
    require(backend.get("app_env") == "staging" and backend.get("policy_verified") is True,
            "STAGING_POLICY_MISMATCH")
    require(snapshot.get("schema") == {"head": 125, "v126_count": 0, "failed_count": 0},
            "V125_SCHEMA_REQUIRED")


def replacement(scope, target_image):
    """The only permitted mutation: one backend, without builds/pulls/dependencies."""
    validate_scope(scope)
    target_image = validate_image(target_image, scope)
    return {
        "argv": ["docker", "compose", "--project-name", scope["project"],
                 "--project-directory", scope["directory"], "--env-file",
                 str(Path(scope["directory"]) / ".env"), "--file", scope["compose_file"],
                 "up", "-d", "--no-build", "--pull", "never", "--no-deps",
                 "--force-recreate", "--scale", "backend=1", "backend"],
        "environment": {"BACKEND_IMAGE": target_image["id"], "APP_VERSION": target_image["revision"]},
    }


def responses_ready(responses, revision):
    """Reuse the existing health JSON, version and Mini App GET contracts.

    A future adapter must request these endpoints with bounded timeouts, without
    redirects, from the pinned local and public staging origins. Raw bodies are
    neither logged nor persisted. An unavailable response is a readiness failure.
    """
    for origin in ("local", "public"):
        checks = responses.get(origin, {})
        for endpoint in ("health", "db_health", "miniapp"):
            response = checks.get(endpoint, {})
            if response.get("status") != 200:
                return False
            body = response.get("body")
            if endpoint == "miniapp":
                if not isinstance(body, str) or len(body) > 65536 or "<html" not in body.lower():
                    return False
            elif body != {"status": "ok"}:
                return False
    version = responses.get("local", {}).get("version", {})
    body = version.get("body")
    return (version.get("status") == 200 and isinstance(body, dict)
            and body.get("service") == "backend" and body.get("env") == "staging"
            and body.get("version") == revision)


class Store:
    """One lock and one durable current state; no history/authorization ledger."""

    def __init__(self, directory):
        self.directory = Path(directory)
        self.path = self.directory / "state.json"

    @contextlib.contextmanager
    def locked(self):
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        require(not self.directory.is_symlink(), "UNSAFE_STATE_DIRECTORY")
        fd = os.open(self.directory / "deployment.lock",
                     os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise Refused("DEPLOYMENT_BUSY") from None
            yield self
        finally:
            os.close(fd)

    def read(self):
        require(self.path.is_file() and not self.path.is_symlink(), "STATE_MISSING")
        return read_json(self.path)

    def write(self, state):
        fd, temporary = tempfile.mkstemp(prefix=".state-", dir=self.directory)
        try:
            with os.fdopen(fd, "w") as stream:
                json.dump(state, stream, sort_keys=True)
                stream.write("\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            directory_fd = os.open(self.directory, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


class Controller:
    """Testable sequencing. Runtime must supply bounded, current observations.

    An exception/non-success from replace is UNKNOWN even if Docker could have
    partially succeeded. Interrupted APPLYING also blocks all subsequent actions.
    """

    def __init__(self, scope, store, runtime, *, readiness_seconds=120):
        validate_scope(scope)
        require(0 < readiness_seconds <= 120, "READINESS_BOUND_INVALID")
        self.scope, self.store, self.runtime = copy.deepcopy(scope), store, runtime
        self.seconds = readiness_seconds

    def state(self):
        state = self.store.read()
        require(state.get("scope") == fingerprint(self.scope), "STATE_TARGET_MISMATCH")
        require(state.get("status") not in ("APPLYING", "UNKNOWN"), "OPERATOR_RECONCILIATION_REQUIRED")
        require(state.get("status") in ("ACCEPTED", "READY_FOR_SMOKE", "READINESS_FAILED"),
                "STATE_INVALID")
        return state

    def observe(self, expected, timeout=5):
        snapshot = self.runtime.observe(timeout=timeout)
        validate_snapshot(snapshot, self.scope, expected)
        return snapshot

    def image(self, expected):
        verified = validate_image(self.runtime.image(expected["id"], timeout=5), self.scope)
        require(verified == expected, "IMAGE_IDENTITY_CHANGED")

    def unknown(self, state, phase, error):
        state["status"], state["reason"] = "UNKNOWN", "DOCKER_OUTCOME_UNDEFINED"
        safe_reasons = {"STAGING_IDENTITY_MISMATCH", "PROTECTED_RESOURCES_CHANGED",
                        "BACKEND_IDENTITY_MISMATCH", "DATABASE_BINDING_MISMATCH",
                        "STAGING_POLICY_MISMATCH", "V125_SCHEMA_REQUIRED", "DOCKER_NOT_COMPLETED",
                        "BACKEND_PRIVILEGES_UNSAFE", "ENVIRONMENT_INVALID", "CONTAINER_INVENTORY_INVALID",
                        "RELEASE_CONFIGURATION_MISMATCH", "RELEASE_CONFIGURATION_CHANGED",
                        "COMMAND_TIMEOUT", "COMMAND_FAILED"}
        reason = "OBSERVATION_OR_COMMAND_FAILED"
        if isinstance(error, Refused) and str(error) in safe_reasons:
            reason = str(error)
        elif isinstance(error, TimeoutError):
            reason = "COMMAND_TIMEOUT"
        elif isinstance(error, (KeyboardInterrupt, SystemExit)):
            reason = "COMMAND_INTERRUPTED"
        state["diagnostic"] = {"phase": phase, "reason": reason}
        self.store.write(state)
        raise Refused("DOCKER_OUTCOME_UNDEFINED_OPERATOR_REQUIRED")

    def apply(self, state):
        operation = state["operation"]
        state["status"], state["reason"] = "APPLYING", None
        # Durable intent precedes the only mutation. Crash cannot become a retry.
        self.store.write(state)
        phase = "RECREATE"
        try:
            require(self.runtime.replace(replacement(self.scope, operation["to"]),
                                         timeout=120) == "COMPLETED", "DOCKER_NOT_COMPLETED")
            phase = "POSTSTATE"
            self.observe(operation["to"]["id"])
        except BaseException as error:
            self.unknown(state, phase, error)
        deadline = time.monotonic() + self.seconds
        ready = False
        try:
            while time.monotonic() < deadline:
                phase = "READINESS_OBSERVATION"
                remaining = deadline - time.monotonic()
                snapshot = self.observe(operation["to"]["id"], timeout=min(5, remaining))
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                phase = "HTTP_READY"
                try:
                    ready = (snapshot["backend"]["running"] and
                             self.runtime.ready(operation["to"], timeout=min(5, remaining)) is True)
                except (TimeoutError, OSError):
                    ready = False
                    state["diagnostic"] = {"phase": phase, "reason": "HTTP_PROBE_UNAVAILABLE"}
                if ready and time.monotonic() <= deadline:
                    break
                ready = False
                time.sleep(min(0.2, max(0, deadline - time.monotonic())))
            # A readiness failure permits rollback only with a known exact poststate.
            phase = "FINAL_POSTSTATE"
            poststate = self.observe(operation["to"]["id"])
            ready = ready and poststate["backend"]["running"]
        except BaseException as error:
            self.unknown(state, phase, error)
        if ready:
            state["status"] = "READY_FOR_SMOKE"
            self.store.write(state)
            return state["status"]
        state["status"], state["reason"] = "READINESS_FAILED", "READINESS_TIMEOUT"
        self.store.write(state)
        raise Refused("READINESS_TIMEOUT_MANUAL_DECISION_REQUIRED")

    def deploy(self, candidate, *, expected_current, owner_approved=False):
        with self.store.locked():
            state = self.state()
            require(state["status"] == "ACCEPTED", "PREVIOUS_RELEASE_UNFINISHED")
            previous = validate_image(state["accepted"], self.scope)
            candidate = validate_image(candidate, self.scope)
            require(previous["id"] == expected_current, "ACCEPTED_IMAGE_CHANGED")
            require(previous["id"] != candidate["id"], "IMAGE_ALREADY_ACCEPTED")
            require(owner_approved is True, "OWNER_APPROVAL_REQUIRED")
            self.image(previous)
            self.image(candidate)
            require(self.observe(previous["id"])["backend"]["running"], "CURRENT_IMAGE_NOT_READY")
            require(self.runtime.ready(previous, timeout=5) is True, "CURRENT_IMAGE_NOT_READY")
            require(self.observe(previous["id"])["backend"]["running"], "CURRENT_IMAGE_NOT_READY")
            state["operation"] = {"kind": "deploy", "from": previous, "to": candidate}
            return self.apply(state)

    def rollback(self, *, owner_approved=False):
        with self.store.locked():
            state = self.state()
            operation = state.get("operation")
            require(isinstance(operation, dict) and operation.get("kind") == "deploy"
                    and isinstance(operation.get("from"), dict), "ROLLBACK_IMAGE_MISSING")
            require(owner_approved is True, "OWNER_APPROVAL_REQUIRED")
            previous = validate_image(operation["from"], self.scope)
            candidate = validate_image(operation["to"], self.scope)
            self.image(previous)
            self.image(candidate)
            self.observe(candidate["id"])
            state["operation"] = {"kind": "rollback", "from": candidate, "to": previous}
            return self.apply(state)

    def accept(self, *, smoke_passed=False):
        with self.store.locked():
            state = self.state()
            require(state["status"] == "READY_FOR_SMOKE", "AUTOMATIC_CHECKS_NOT_PASSED")
            require(smoke_passed is True, "MANUAL_SMOKE_REQUIRED")
            image = state["operation"]["to"]
            self.image(image)
            require(self.observe(image["id"])["backend"]["running"], "READINESS_LOST")
            require(self.runtime.ready(image, timeout=5) is True, "READINESS_LOST")
            require(self.observe(image["id"])["backend"]["running"], "READINESS_LOST")
            state["accepted"], state["status"], state["reason"] = image, "ACCEPTED", None
            self.store.write(state)
            return state["status"]


class FixtureRuntime:
    """In-memory simulation; commands are recorded and never executed."""

    def __init__(self, snapshot, images, *, readiness=True, docker_outcome="COMPLETED"):
        self.snapshot, self.images = copy.deepcopy(snapshot), copy.deepcopy(images)
        self.readiness, self.docker_outcome, self.commands = readiness, docker_outcome, []

    def observe(self, *, timeout):
        return copy.deepcopy(self.snapshot)

    def image(self, identity, *, timeout):
        return copy.deepcopy(self.images.get(identity))

    def ready(self, image, *, timeout):
        return (self.snapshot["backend"]["image_id"] == image["id"] and self.readiness
                and self.snapshot["backend"]["running"]
                and responses_ready(self.snapshot.get("responses", {}), image["revision"]))

    def replace(self, command, *, timeout):
        self.commands.append(copy.deepcopy(command))
        self.snapshot["backend"]["image_id"] = command["environment"]["BACKEND_IMAGE"]
        revision = self.images[command["environment"]["BACKEND_IMAGE"]]["revision"]
        self.snapshot["responses"]["local"]["version"]["body"]["version"] = revision
        return self.docker_outcome


def stable_container(container):
    """Same stable identity fields used by the existing ordinary deploy checks."""
    return {key: container[key] for key in ("Id", "Image", "Config", "HostConfig", "Mounts", "RestartCount")} | {
        "started_at": container["State"]["StartedAt"], "running": container["State"]["Running"],
        "networks": container["NetworkSettings"]["Networks"],
    }


class CommandRuntime:
    """Docker/Compose/psql/curl adapter with bounded, private command I/O.

    runner(argv, timeout, payload=None, environment=None) returns (exit_code,
    bounded_stdout). It must bound the entire process lifetime, discard private
    stderr and raise on transport loss. file_reader supplies protected file bytes.
    Image records come from independently verified build/artifact evidence, not
    from OCI labels alone. Their immutable IDs bind that evidence to inspection.
    """

    def __init__(self, scope, images, *, runner, file_reader, configuration):
        validate_scope(scope)
        self.scope, self.images = copy.deepcopy(scope), copy.deepcopy(images)
        self.runner, self.file_reader = runner, file_reader
        self.configuration = configuration
        self.host_config = None

    def command(self, argv, *, deadline, payload=None, environment=None):
        remaining = deadline - time.monotonic()
        require(remaining > 0, "COMMAND_TIMEOUT")
        code, output = self.runner(argv, timeout=remaining, payload=payload, environment=environment)
        require(code == 0 and isinstance(output, bytes) and len(output) <= 8 * 1024 * 1024,
                "COMMAND_FAILED")
        return output

    def compose(self):
        scope = self.scope
        return ["docker", "compose", "--project-name", scope["project"], "--project-directory",
                scope["directory"], "--env-file", str(Path(scope["directory"]) / ".env"),
                "--file", scope["compose_file"]]

    @staticmethod
    def environment(container):
        result = {}
        for item in container["Config"]["Env"]:
            key, separator, value = item.partition("=")
            require(separator and key not in result, "ENVIRONMENT_INVALID")
            result[key] = value
        return result

    def image(self, identity, *, timeout):
        record = validate_image(self.images.get(identity), self.scope)
        # Inspect the immutable ID; validate the returned platform below (Docker API 1.48 compatible).
        inspected = json.loads(self.command(["docker", "image", "inspect", identity],
                                            deadline=time.monotonic() + timeout))
        require(len(inspected) == 1, "IMAGE_MISSING")
        image = inspected[0]
        labels = image["Config"].get("Labels") or {}
        require(image["Id"] == identity and image["Os"] + "/" + image["Architecture"] == record["platform"]
                and labels.get("org.opencontainers.image.revision") == record["revision"]
                and labels.get("org.opencontainers.image.source") == SOURCE, "IMAGE_PROVENANCE_MISMATCH")
        return record

    def observe(self, *, timeout):
        deadline = time.monotonic() + timeout
        scope = self.scope
        ids = self.command(["docker", "ps", "-aq", "--no-trunc"], deadline=deadline).decode().splitlines()
        require(ids and len(ids) <= 256 and all(re.fullmatch(r"[0-9a-f]{64}", cid) for cid in ids),
                "CONTAINER_INVENTORY_INVALID")
        containers = json.loads(self.command(["docker", "inspect", *ids], deadline=deadline))
        require(len(containers) == len(ids) and {item["Id"] for item in containers} == set(ids),
                "CONTAINER_INVENTORY_INVALID")

        def owned(project, service=None):
            return [item for item in containers
                    if item["Config"]["Labels"].get("com.docker.compose.project") == project
                    and (service is None or item["Config"]["Labels"].get("com.docker.compose.service") == service)]

        backend, postgres = owned(scope["project"], "backend"), owned(scope["project"], "postgres")
        require(len(backend) == len(postgres) == 1, "BACKEND_IDENTITY_MISMATCH")
        backend, postgres = backend[0], postgres[0]
        for item in (backend, postgres):
            labels = item["Config"]["Labels"]
            require(labels.get("com.docker.compose.project.working_dir") == scope["directory"]
                    and labels.get("com.docker.compose.project.config_files") == scope["compose_file"],
                    "STAGING_IDENTITY_MISMATCH")
        require(not backend["HostConfig"].get("Privileged") and not backend["Mounts"]
                and backend["HostConfig"].get("NetworkMode") != "host"
                and not backend["HostConfig"].get("ExtraHosts")
                and not backend["HostConfig"].get("Dns")
                and not backend["HostConfig"].get("DnsSearch")
                and not backend["HostConfig"].get("DnsOptions"), "BACKEND_PRIVILEGES_UNSAFE")
        require(all(not backend["HostConfig"].get(key) for key in
                    ("CapAdd", "Devices", "DeviceRequests", "Binds", "VolumesFrom", "Links"))
                and backend["HostConfig"].get("PidMode", "") == ""
                and all(backend["HostConfig"].get(key, "") in ("", "private")
                        for key in ("IpcMode", "UsernsMode", "CgroupnsMode"))
                and all(value in ("no-new-privileges", "no-new-privileges:true")
                        for value in backend["HostConfig"].get("SecurityOpt") or []),
                "BACKEND_PRIVILEGES_UNSAFE")
        require(postgres["State"]["Running"] is True, "DATABASE_BINDING_MISMATCH")
        record = self.images.get(backend["Image"])
        require(record is not None, "BACKEND_IDENTITY_MISMATCH")
        composed = json.loads(self.command(self.compose() + ["config", "--format", "json"], deadline=deadline))
        require(composed["name"] == scope["project"], "STAGING_IDENTITY_MISMATCH")
        require(composed["services"]["backend"]["image"] == backend["Image"]
                and composed["services"]["backend"]["environment"].get("APP_VERSION") == record["revision"],
                "RELEASE_CONFIGURATION_MISMATCH")
        effective = composed["services"]["backend"]["environment"]
        actual, pg_env = self.environment(backend), self.environment(postgres)
        require(all(actual.get(key) == str(value) for key, value in effective.items()),
                "STAGING_POLICY_MISMATCH")
        normalized = normalized_configuration(composed)
        self.host_config = copy.deepcopy(backend["HostConfig"])
        sql = ("BEGIN READ ONLY; SET LOCAL statement_timeout='2s'; SET LOCAL lock_timeout='1s'; "
               "SELECT json_build_object('system_id',(SELECT system_identifier::text FROM pg_control_system()),"
               "'database',current_database(),'user',current_user,"
               "'head',max(version::integer),'v126_count',count(*) FILTER (WHERE version='126'),"
               "'failed_count',count(*) FILTER (WHERE NOT success)) FROM flyway_schema_history; ROLLBACK;")
        db = json.loads(self.command(["docker", "exec", "-i", postgres["Id"], "sh", "-c",
                                     'env -i PATH="$PATH" PGCONNECT_TIMEOUT=2 psql -XqAtw '
                                     '-U "$POSTGRES_USER" -d "$POSTGRES_DB" --set=ON_ERROR_STOP=1'],
                                    deadline=deadline, payload=sql.encode()))
        require(db["database"] == pg_env["POSTGRES_DB"] and db["user"] == pg_env["POSTGRES_USER"],
                "DATABASE_BINDING_MISMATCH")
        networks = set(postgres["NetworkSettings"]["Networks"]) & set(backend["NetworkSettings"]["Networks"])
        require(len(networks) == 1 and set(backend["NetworkSettings"]["Networks"]) == networks,
                "DATABASE_BINDING_MISMATCH")
        network = next(iter(networks))
        pg_endpoint = postgres["NetworkSettings"]["Networks"][network]
        require(pg_endpoint.get("IPAddress") and pg_endpoint.get("NetworkID")
                and backend["NetworkSettings"]["Networks"][network].get("NetworkID") == pg_endpoint["NetworkID"],
                "DATABASE_BINDING_MISMATCH")
        # Only one endpoint may own the JDBC host on this exact staging network.
        aliases = []
        for item in containers:
            endpoint = item["NetworkSettings"]["Networks"].get(network)
            if endpoint is None:
                continue
            names = (endpoint.get("Aliases") or []) + (endpoint.get("DNSNames") or [])
            names += [item.get("Name", ""), item["Config"].get("Hostname", "")]
            if "postgres" in {name.lstrip("/").lower() for name in names}:
                aliases.append(item["Id"])
        require(aliases == [postgres["Id"]] and postgres["State"]["Running"] is True,
                "DATABASE_BINDING_MISMATCH")
        require(backend["HostConfig"].get("NetworkMode") == network, "DATABASE_BINDING_MISMATCH")
        require(actual.get("DB_JDBC_URL") == "jdbc:postgresql://postgres:5432/" + db["database"]
                and actual.get("DB_USER") == db["user"]
                and actual.get("DB_PASSWORD") == pg_env.get("POSTGRES_PASSWORD")
                and bool(pg_env.get("POSTGRES_PASSWORD")), "DATABASE_BINDING_MISMATCH")
        volumes = [mount["Name"] for mount in postgres["Mounts"] if mount["Type"] == "volume"]
        require(len(volumes) == 1, "DATABASE_BINDING_MISMATCH")
        production = owned(scope["protected"]["production"]["project"])
        prod_db = owned(scope["protected"]["production"]["project"], "postgres")
        require(production and len(prod_db) == 1, "PROTECTED_RESOURCES_CHANGED")
        excluded = {backend["Id"], postgres["Id"]} | {item["Id"] for item in production}
        shared = [item for item in containers if item["Id"] not in excluded]

        def group_fingerprint(items):
            return fingerprint([stable_container(item) for item in sorted(items, key=lambda value: value["Id"])])

        protected = {
            "postgres": {"container_id": postgres["Id"], "system_id": db["system_id"],
                         "database": db["database"], "user": db["user"], "volume": volumes[0],
                         "network": network, "fingerprint": fingerprint(stable_container(postgres))},
            "production": {"project": scope["protected"]["production"]["project"],
                           "database_container_id": prod_db[0]["Id"],
                           "volumes": sorted({m["Name"] for item in production for m in item["Mounts"]
                                              if m["Type"] == "volume"}),
                           "networks": sorted({n for item in production for n in item["NetworkSettings"]["Networks"]}),
                           "fingerprint": group_fingerprint(production)},
            "shared_fingerprint": group_fingerprint(shared),
        }
        policy = actual.get("TELEGRAM_TRAFFIC_POLICY")
        policy_verified = (policy == "ALLOWLIST" and bool(actual.get("TELEGRAM_ALLOWED_USER_IDS"))
                           and bool(actual.get("TELEGRAM_ALLOWED_CHAT_IDS"))) or (
            policy == "PRODUCT" and actual.get("STAGING_MAINTENANCE_MODE", "OFF") == "OFF"
            and all(not actual.get(key) for key in ("TELEGRAM_ALLOWED_USER_IDS", "TELEGRAM_ALLOWED_CHAT_IDS",
                                                   "STAGING_MAINTENANCE_ALLOWED_USER_IDS",
                                                   "STAGING_MAINTENANCE_ALLOWED_CHAT_IDS")))
        return {
            "project": composed["name"], "directory": scope["directory"], "compose_file": scope["compose_file"],
            "compose_fingerprint": hashlib.sha256(self.file_reader(scope["compose_file"])).hexdigest(),
            "configuration_fingerprint": fingerprint({"compose": normalized,
                                                       "backend_host_config": backend["HostConfig"]}),
            "protected": protected,
            "schema": {key: db[key] for key in ("head", "v126_count", "failed_count")},
            "backend": {"project": scope["project"], "service": "backend", "count": 1,
                        "image_id": backend["Image"], "app_env": actual.get("APP_ENV"),
                        "running": backend["State"]["Running"] is True and not backend["State"].get("Restarting", False),
                        "policy_verified": bool(policy_verified), "db_binding_verified": True,
                        "database": {"container_id": postgres["Id"], "system_id": db["system_id"],
                                     "database": db["database"], "user": db["user"], "network": network}},
        }

    def ready(self, image, *, timeout):
        deadline = time.monotonic() + timeout
        responses = {}
        try:
            for origin in ("local", "public"):
                responses[origin] = {}
                for key, path in (("health", "/health"), ("db_health", "/db/health"),
                                  ("miniapp", "/miniapp/"), ("version", "/version")):
                    if origin == "public" and key == "version":
                        continue
                    remaining = deadline - time.monotonic()
                    require(remaining > 0, "COMMAND_TIMEOUT")
                    raw = self.command(["curl", "--disable", "--noproxy", "*", "--connect-timeout", "2",
                                        "--max-time", str(min(3, remaining)), "--max-filesize", "65536",
                                        "--proto", "=http" if origin == "local" else "=https",
                                        "-sS", "-w", "\n%{http_code}", self.scope[origin + "_url"] + path],
                                       deadline=deadline)
                    body, separator, code = raw.rpartition(b"\n")
                    require(separator and len(body) <= 65536, "HTTP_RESPONSE_INVALID")
                    responses[origin][key] = {"status": int(code), "body": body.decode() if key == "miniapp"
                                             else json.loads(body)}
            return responses_ready(responses, image["revision"])
        except (Refused, OSError, TimeoutError, ValueError):
            return False

    def replace(self, command, *, timeout):
        target = command["environment"]["BACKEND_IMAGE"]
        require(command == replacement(self.scope, self.images.get(target)), "UNSAFE_REPLACEMENT_COMMAND")
        deadline = time.monotonic() + timeout
        # Persist before asking Docker. Interruption at either step is UNKNOWN.
        self.configuration.select(self.images[target])
        composed = json.loads(self.command(self.compose() + ["config", "--format", "json"], deadline=deadline))
        require(composed["services"]["backend"]["image"] == target
                and composed["services"]["backend"]["environment"].get("APP_VERSION") == self.images[target]["revision"],
                "RELEASE_CONFIGURATION_MISMATCH")
        require(fingerprint({"compose": normalized_configuration(composed),
                             "backend_host_config": self.host_config}) == self.scope["configuration_fingerprint"],
                "STAGING_IDENTITY_MISMATCH")
        # No per-command image override: subsequent Compose invocations see the same .env.
        self.command(command["argv"], deadline=deadline)
        return "COMPLETED"


def normalized_configuration(composed):
    normalized = copy.deepcopy(composed)
    backend = normalized["services"]["backend"]
    backend.pop("image", None)
    for key in ("APP_VERSION", "BACKEND_IMAGE"):
        backend["environment"].pop(key, None)
    return normalized


class ProcessRunner:
    """No shell, inherited deployment variables, private stderr or unbounded pipes."""

    def __init__(self, *, docker_host="unix:///var/run/docker.sock", environment=None):
        require(docker_host.startswith("unix:///"), "LOCAL_DOCKER_REQUIRED")
        self.docker_host = docker_host
        self.environment = {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": str(Path.home()),
                            "LANG": "C.UTF-8"}
        if environment:
            self.environment.update(environment)  # Only fixture callers supply overrides.

    def __call__(self, argv, *, timeout, payload=None, environment=None):
        require(timeout > 0 and not environment, "COMMAND_ENVIRONMENT_INVALID")
        argv = list(argv)
        binary = shutil.which(argv[0], path=self.environment["PATH"])
        require(binary is not None, "COMMAND_UNAVAILABLE")
        if argv[0] == "docker":
            argv[1:1] = ["--host", self.docker_host]
        argv[0] = binary
        deadline = time.monotonic() + timeout
        process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, env=self.environment, start_new_session=True)
        output, pending = bytearray(), memoryview(payload or b"")
        try:
            with selectors.DefaultSelector() as selector:
                os.set_blocking(process.stdout.fileno(), False)
                selector.register(process.stdout, selectors.EVENT_READ)
                if pending:
                    os.set_blocking(process.stdin.fileno(), False)
                    selector.register(process.stdin, selectors.EVENT_WRITE)
                else:
                    process.stdin.close()
                while selector.get_map():
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise TimeoutError()
                    for key, _ in selector.select(remaining):
                        if key.fileobj is process.stdout:
                            chunk = os.read(process.stdout.fileno(), 65536)
                            if not chunk:
                                selector.unregister(process.stdout)
                            output.extend(chunk)
                            require(len(output) <= MAX_OUTPUT, "COMMAND_OUTPUT_LIMIT")
                        else:
                            try:
                                pending = pending[os.write(process.stdin.fileno(), pending[:65536]):]
                            except BrokenPipeError:
                                pending = memoryview(b"")
                            if not pending:
                                selector.unregister(process.stdin)
                                process.stdin.close()
                code = process.wait(timeout=max(0, deadline - time.monotonic()))
                return code, bytes(output)
        except subprocess.TimeoutExpired:
            raise TimeoutError() from None
        finally:
            # Even a child holding stdout open cannot outlive the deadline locally.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
            process.stdout.close()
            process.stdin.close()


class ProtectedFiles:
    """Registered operator-owned physical paths; never report file contents."""

    def __init__(self, owner=0):
        self.owner = owner

    def check(self, path, *, private=False, directory=False):
        path = Path(path)
        require(path.is_absolute() and path.resolve() == path, "UNSAFE_TARGET_PATH")
        for parent in path.parents:
            info = parent.stat()
            # A sticky system /tmp is allowed only for isolated test fixtures.
            require(info.st_uid in (0, self.owner) and
                    (not info.st_mode & 0o022 or (self.owner != 0 and info.st_mode & stat.S_ISVTX)),
                    "UNSAFE_TARGET_PATH")
        info = path.lstat()
        require(info.st_uid == self.owner and not info.st_mode & 0o022
                and (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode))
                and (directory or info.st_nlink == 1)
                and (not private or not info.st_mode & 0o077), "UNSAFE_TARGET_PATH")
        return path

    def read(self, path):
        path = self.check(path)
        with path.open("rb") as stream:
            data = stream.read(1024 * 1024 + 1)
        require(len(data) <= 1024 * 1024, "INPUT_TOO_LARGE")
        return data


class ReleaseConfiguration:
    """Change exactly two non-secret .env assignments, preserving all other bytes."""

    def __init__(self, directory, files):
        self.path, self.files = Path(directory) / ".env", files

    def select(self, image):
        self.files.check(self.path, private=True)
        original = self.files.read(self.path)
        require(b"\r" not in original and b"\x00" not in original, "ENVIRONMENT_INVALID")
        lines = original.splitlines(keepends=True)
        values = {b"BACKEND_IMAGE": image["id"].encode(), b"APP_VERSION": image["revision"].encode()}
        seen, updated = set(), []
        for line in lines:
            assignment = re.match(rb"\s*(?:export\s+)?[A-Za-z_][A-Za-z0-9_]*\s*=\s*(.*)", line.rstrip(b"\n"))
            if assignment:
                value = assignment[1]
                if value.startswith(b"'"):
                    require(re.fullmatch(rb"'[^']*'\s*(?:#.*)?", value), "ENVIRONMENT_INVALID")
                elif value.startswith(b'"'):
                    require(re.fullmatch(rb'"(?:[^"\\]|\\.)*"\s*(?:#.*)?', value), "ENVIRONMENT_INVALID")
            else:
                require(not line.strip() or line.lstrip().startswith(b"#"), "ENVIRONMENT_INVALID")
            match = re.match(rb"\s*(?:export\s+)?(BACKEND_IMAGE|APP_VERSION)\s*=", line)
            if match:
                key = match[1]
                require(key not in seen, "ENVIRONMENT_INVALID")
                seen.add(key)
                updated.append(key + b"=" + values[key] + b"\n")
            else:
                updated.append(line)
        if updated and not updated[-1].endswith(b"\n"):
            updated[-1] += b"\n"
        for key in values.keys() - seen:
            updated.append(key + b"=" + values[key] + b"\n")
        fd, temporary = tempfile.mkstemp(prefix=".release-env-", dir=self.path.parent)
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(b"".join(updated))
                stream.flush()
                os.fsync(stream.fileno())
            require(self.files.read(self.path) == original, "RELEASE_CONFIGURATION_CHANGED")
            os.replace(temporary, self.path)
            directory_fd = os.open(self.path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def decode_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    require(len(raw) <= 1024 * 1024, "INPUT_TOO_LARGE")
    return json.loads(raw, object_pairs_hook=pairs)


def read_json(path):
    with Path(path).open("rb") as stream:
        return decode_json(stream.read(1024 * 1024 + 1))


def registration(target, *, path=REGISTRATION, files=None):
    files = files or ProtectedFiles()
    require(Path(path).exists(), LIVE_REFUSAL)
    files.check(path, private=True)
    registered = decode_json(files.read(path))
    require(registered.get("transition_approved") is True, LIVE_REFUSAL)
    require(registered.get("target") == target, "REGISTERED_TARGET_MISMATCH")
    validate_scope(registered["scope"])
    files.check(registered["scope"]["directory"], directory=True)
    files.check(registered["scope"]["compose_file"])
    files.check(Path(registered["scope"]["directory"]) / ".env", private=True)
    files.check(Path(registered["scope"]["directory"]) / ".staging-release", private=True, directory=True)
    return registered


def registered_action(request, registered, *, runner=None, files=None, readiness_seconds=120):
    """Runs on the target host: all clients share the same state and flock."""
    files = files or ProtectedFiles()
    scope = registered["scope"]
    require(registered.get("transition_approved") is True, LIVE_REFUSAL)
    require(request.get("target") == registered["target"], "REGISTERED_TARGET_MISMATCH")
    action = request.get("action")
    require(action in ("initialize", "deploy", "rollback", "accept", "status"), "ACTION_INVALID")
    store = Store(Path(scope["directory"]) / ".staging-release")
    try:
        state = None
        if action != "initialize":
            files.check(store.path, private=True)
            state = store.read()
            require(state.get("scope") == fingerprint(scope), "STATE_TARGET_MISMATCH")
        if action == "status":
            with store.locked():
                state = store.read()
                status = state["status"]
                require(status in ("ACCEPTED", "READY_FOR_SMOKE", "READINESS_FAILED", "APPLYING", "UNKNOWN"),
                        "STATE_INVALID")
                accepted = validate_image(state["accepted"], scope)
                intended = validate_image((state.get("operation") or {}).get("to") or accepted, scope)
                if status in ("APPLYING", "UNKNOWN"):
                    return {"status": "UNKNOWN", "state": status, "accepted_image": accepted["id"],
                            "intended_image": intended["id"]}
                runtime = CommandRuntime(scope, {intended["id"]: intended}, runner=runner or ProcessRunner(),
                            file_reader=files.read, configuration=ReleaseConfiguration(scope["directory"], files))
                validate_snapshot(runtime.observe(timeout=5), scope, intended["id"])
                return {"status": "FAILED" if status == "READINESS_FAILED" else "SUCCESS", "state": status,
                        "accepted_image": accepted["id"], "current_image": intended["id"]}
        images = {}
        if state:
            for record in [state["accepted"], *(state.get("operation") or {}).values()]:
                if isinstance(record, dict):
                    verified = validate_image(record, scope)
                    images[verified["id"]] = verified
        if action in ("deploy", "initialize"):
            verified = validate_image(request.get("candidate"), scope)
            images[verified["id"]] = verified
        runtime = CommandRuntime(scope, images, runner=runner or ProcessRunner(), file_reader=files.read,
                                 configuration=ReleaseConfiguration(scope["directory"], files))
        controller = Controller(scope, store, runtime, readiness_seconds=readiness_seconds)
        if action == "initialize":
            require(request.get("owner_approved") is True, "OWNER_APPROVAL_REQUIRED")
            with store.locked():
                require(not store.path.exists(), "TARGET_ALREADY_INITIALIZED")
                controller.image(verified)
                require(controller.observe(verified["id"])["backend"]["running"]
                        and runtime.ready(verified, timeout=5), "CURRENT_IMAGE_NOT_READY")
                require(controller.observe(verified["id"])["backend"]["running"], "CURRENT_IMAGE_NOT_READY")
                store.write({"scope": fingerprint(scope), "status": "ACCEPTED", "accepted": verified,
                             "operation": None, "reason": None})
            phase = "ACCEPTED"
        elif action == "deploy":
            phase = controller.deploy(request.get("candidate"), expected_current=request.get("expected_current"),
                                      owner_approved=request.get("owner_approved") is True)
        elif action == "rollback":
            phase = controller.rollback(owner_approved=request.get("owner_approved") is True)
        else:
            phase = controller.accept(smoke_passed=request.get("smoke_passed") is True)
        return {"status": "SUCCESS", "state": phase}
    except Refused as error:
        reason = str(error)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RecursionError):
        reason = "TARGET_INPUT_OR_STATE_INVALID"
    if reason == "DEPLOYMENT_BUSY":
        return {"status": "FAILED", "reason": reason}
    # An unfinished durable intent always wins over an error classification.
    try:
        state = store.read()
    except (OSError, ValueError):
        state = None
    if state and state.get("status") in ("APPLYING", "UNKNOWN"):
        return {"status": "UNKNOWN", "state": state["status"], "reason": "OPERATOR_RECONCILIATION_REQUIRED"}
    return {"status": "FAILED", "reason": reason}


def ssh_action(target, request, *, runner=None):
    require(isinstance(target, str) and re.fullmatch(r"[a-z][a-z0-9_-]{1,63}", target), "TARGET_INVALID")
    # Target is also checked against the root-owned registration on the remote host.
    argv = ["ssh", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
            "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=5", "-o", "ServerAliveCountMax=2",
            "-o", "ClearAllForwardings=yes", "-o", "ForwardAgent=no", "-o", "ControlMaster=no",
            "-o", "ControlPath=none", "--", target,
            "/usr/bin/python3 -B " + REMOTE_SCRIPT + " worker --target " + target]
    try:
        code, raw = (runner or ProcessRunner())(argv, timeout=360, payload=json.dumps(request).encode())
        result = decode_json(raw)
        require(code in (0, 2, 3) and isinstance(result, dict)
                and result.get("status") in ("SUCCESS", "FAILED", "UNKNOWN")
                and code == {"SUCCESS": 0, "FAILED": 2, "UNKNOWN": 3}[result["status"]]
                and set(result) <= {"status", "state", "reason", "accepted_image", "current_image", "intended_image"},
                "TRANSPORT_RESULT_INVALID")
        # Never forward arbitrary remote diagnostics or terminal output.
        require(all(isinstance(value, str) and re.fullmatch(r"[A-Z][A-Z0-9_]{0,95}|sha256:[0-9a-f]{64}", value)
                    for value in result.values()), "TRANSPORT_RESULT_INVALID")
        return result
    except (Refused, OSError, ValueError, TimeoutError, KeyboardInterrupt):
        return {"status": "UNKNOWN", "reason": "SSH_OUTCOME_UNDEFINED_OPERATOR_REQUIRED"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("plan", "simulate", "initialize", "deploy", "rollback", "accept", "status", "worker"))
    parser.add_argument("--fixture", help="Offline observations and reviewed image records")
    parser.add_argument("--state-dir", help="Local simulation state directory")
    parser.add_argument("--target", help="Explicit SSH alias, matching the enrolled staging target")
    parser.add_argument("--candidate", help="Independently verified image record (JSON)")
    parser.add_argument("--expected-current", help="Exact accepted image ID")
    parser.add_argument("--owner-approved", action="store_true", help="Owner approved this operation and exact image")
    parser.add_argument("--smoke-passed", action="store_true", help="Manual smoke completed successfully")
    args = parser.parse_args(argv)
    try:
        if args.action not in ("plan", "simulate"):
            require(args.target is not None and not args.fixture and not args.state_dir, LIVE_REFUSAL)
            require(re.fullmatch(r"[a-z][a-z0-9_-]{1,63}", args.target), "TARGET_INVALID")
            if args.action == "worker":
                require(os.geteuid() == 0, LIVE_REFUSAL)
                registered = registration(args.target)
                request = decode_json(sys.stdin.buffer.read(1024 * 1024 + 1))
                result = registered_action(request, registered)
            else:
                if args.action in ("deploy", "rollback", "initialize"):
                    require(args.owner_approved, "OWNER_APPROVAL_REQUIRED")
                if args.action in ("deploy", "initialize"):
                    require(args.candidate is not None, "IMAGE_MISSING")
                if args.action == "deploy":
                    require(image_id(args.expected_current), "ACCEPTED_IMAGE_REQUIRED")
                if args.action == "accept":
                    require(args.smoke_passed, "MANUAL_SMOKE_REQUIRED")
                request = {"target": args.target, "action": args.action,
                           "owner_approved": args.owner_approved, "smoke_passed": args.smoke_passed,
                           "expected_current": args.expected_current,
                           "candidate": read_json(args.candidate) if args.candidate else None}
                result = ssh_action(args.target, request)
            print(json.dumps(result, sort_keys=True))
            return {"SUCCESS": 0, "FAILED": 2, "UNKNOWN": 3}[result["status"]]
        require(args.fixture is not None, "FIXTURE_REQUIRED")
        fixture = read_json(args.fixture)
        scope = fixture["scope"]
        validate_scope(scope)
        current = validate_image(fixture["current"], scope)
        candidate = validate_image(fixture["candidate"], scope)
        require(current["id"] != candidate["id"], "IMAGE_ALREADY_ACCEPTED")
        validate_snapshot(fixture["snapshot"], scope, current["id"])
        if args.action == "plan":
            result = {"status": "OFFLINE_PLAN", "live_execution": False,
                      "command": replacement(scope, candidate)}
        else:
            require(args.state_dir is not None, "SIMULATION_STATE_REQUIRED")
            store = Store(args.state_dir)
            with store.locked():
                require(not store.path.exists(), "SIMULATION_ALREADY_EXISTS")
                store.write({"scope": fingerprint(scope), "status": "ACCEPTED", "accepted": current,
                             "operation": None, "reason": None})
            runtime = FixtureRuntime(fixture["snapshot"], {r["id"]: r for r in (current, candidate)})
            controller = Controller(scope, store, runtime)
            controller.deploy(candidate, expected_current=current["id"], owner_approved=True)
            controller.accept(smoke_passed=True)
            result = {"status": "SIMULATED_ACCEPTED", "live_execution": False}
        print(json.dumps(result, sort_keys=True))
        return 0
    except Refused as error:
        reason = str(error)
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RecursionError):
        reason = "LOCAL_INPUT_OR_STATE_INVALID"
    if args.action == "worker":
        print(json.dumps({"status": "FAILED", "reason": reason}))
    else:
        print(json.dumps({"status": "REFUSED", "reason": reason, "live_execution": False}), file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
