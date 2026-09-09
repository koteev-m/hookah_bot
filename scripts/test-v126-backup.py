#!/usr/bin/env python3
"""Real PG17 backup regression; only synthetic, task-labelled local Docker resources.

Sources the production function (including the immutable before revision). Authority
preconditions (including semantic database identity, tested separately) and host paths
are fixture bindings, not a cutover authorization. The
Compose boundary forwards the original sh argv to one exact source container. Docker
inventories are restricted to this test's label; positive PostgreSQL tools are real.
"""
import atexit
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parent
BASE = "724dbe931c0af969b761cc174eb289a3e46b17ca"
LABEL = "com.hookah.ht12aa.fixture"
CANARY = "HT12AA_synthetic_password_canary"
USER = "ht12aa_source"
ODD_USER = "ht12aa role ' \" $x"
DATABASE = "ht12aa db ' \" ; $(touch /tmp/ht12aa-injected) `id`"


def run(args, **kwargs):
    if kwargs.pop("group", False):
        timeout = kwargs.pop("timeout", 180)
        with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              start_new_session=True, **kwargs) as process:
            try:
                stdout, stderr = process.communicate(timeout=timeout)
                return subprocess.CompletedProcess(args, process.returncode, stdout, stderr)
            except BaseException:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.communicate(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.communicate()
                raise
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=kwargs.pop("timeout", 180), **kwargs)


def require(ok, message):
    if not ok:
        raise AssertionError(message)


def ident(value):
    return '"' + value.replace('"', '""') + '"'


def literal(value):
    return "'" + value.replace("'", "''") + "'"


DRIVER = r'''
source "$1"
fixture="$2"
phase="$3"
mode="$4"
boundary() { python3 "$HT12AA_HELPER" --boundary "$fixture/config.json" "$@"; }
remote_require_run_root() { printf '%s\n' "$fixture/run"; }
remote_verify_baseline_authority() { boundary event baseline; }
remote_assert_database_target() { REMOTE_DATABASE_TARGET_IDENTITY_SHA256="$(hash_text fixture-semantic-database-target)"; }
remote_assert_compose_backend_image() { [[ "$1" == "fixture:$V125_SOURCE_SHA" ]]; }
remote_verify_proof() { [[ "$1" == "$fixture/run/baseline-authority.proof" ]]; boundary event pre-drain-gate; }
remote_assert_zero_writer() { [[ "$1" == 125:0:0 ]]; boundary event quiesced-gate; }
remote_backup_root() { printf '%s\n' "$fixture/backups/v126/$HT12AA_RELEASE/$HT12AA_RUN"; }
remote_capture_compose_ids() {
  [[ "$*" == 'running postgres' ]]
  REMOTE_CAPTURED_CONTAINER_IDS=("$HT12AA_SOURCE")
}
remote_compose() { boundary compose "$@"; }
docker() { boundary docker "$@"; }
sudo() { boundary sudo "$@"; }
stat() { boundary stat "$@"; }
df() { boundary df "$@"; }
sha256sum() { boundary checksum "$@"; }
if [[ "$mode" == globals ]]; then
  source "$fixture/globals-command.sh"
else
  remote_backup_rehearsal "$fixture" "$HT12AA_RUN" "$HT12AA_RELEASE" "$phase" "fixture:$V125_SOURCE_SHA"
  [[ -z "$(trap -p EXIT INT TERM HUP)" ]]
  ! declare -p V126_REMOTE_REHEARSAL_CLEANUP_CONTAINER >/dev/null 2>&1
  ! declare -p V126_REMOTE_REHEARSAL_CLEANUP_VOLUME >/dev/null 2>&1
fi
'''


def boundary(config, kind, args):
    cfg = json.loads(Path(config).read_text())
    root = Path(config).parent

    def event(value):
        with (root / "events").open("a") as log:
            log.write(value + "\n")

    def forward(command, payload=None):
        result = run(command, input=payload)
        # Capture raw diagnostics in the private harness process, never ordinary logs.
        if CANARY.encode() in result.stdout + result.stderr or b"SCRAM-SHA-256$" in result.stdout:
            raise AssertionError("credential appeared in client output")
        sys.stdout.buffer.write(result.stdout)
        if result.returncode:
            if b'missing "=" after' in result.stderr:
                sys.stderr.write("LIBPQ_CONNINFO_MISSING_EQUALS\n")
            else:
                sys.stderr.write("synthetic client command failed\n")
        return result.returncode

    def docker(args, payload=None):
        return forward([cfg["docker"], *args], payload)

    def own(name, volume=False):
        require(name in (cfg["source"], cfg["sentinel"]) or re.fullmatch(
            "hookah-v126-" + cfg["run"] + r"-(pre-drain|quiesced)-[0-9]+", name),
            "Docker target escaped the test namespace")
        result = run([cfg["docker"], *( ["volume"] if volume else ["container"] ),
                      "inspect", "--format", '{{ index .Labels "' + LABEL + '" }}'
                      if volume else '{{ index .Config.Labels "' + LABEL + '" }}', name])
        require(result.returncode == 0 and result.stdout.decode().strip() == cfg["token"],
                "Docker boundary refused a resource outside this test")

    def mapped(value):
        if value.startswith("/var/backups/hookah-bot"):
            value = str(root / "backups") + value[len("/var/backups/hookah-bot"):]
        require(Path(value).is_relative_to(root), "host path escaped fixture")
        return Path(value)

    fault = cfg.get("fault", "")
    if kind == "event":
        event(args[0])
        return 0
    if kind == "compose":
        require(args[:5] == ["exec", "-T", "postgres", "sh", "-c"] and len(args) == 6,
                "unexpected Compose argv")
        command = args[-1]
        utility = next((name for name in ("pg_dumpall", "pg_dump", "pg_restore", "psql")
                        if re.search(r"\b" + name + r"\b", command)), "user")
        event(utility)
        payload = sys.stdin.buffer.read() if utility == "pg_restore" else None
        if fault == utility + "-empty":
            return 0
        if fault == utility + "-partial":
            sys.stdout.write("PGDMP truncated" if utility == "pg_dump" else "-- partial globals\n")
            return 0 if utility == "pg_dump" else 73
        if fault == utility + "-fail":
            return 71
        env = ["--env", "POSTGRES_USER=" + cfg["user"]]
        if cfg["database"] is not None:
            env += ["--env", "POSTGRES_DB=" + cfg["database"]]
        unset = ["env", "-u", "POSTGRES_DB"] if cfg["database"] is None else []
        status = docker(["exec", "-i", *env, cfg["source"], *unset, *args[3:5], command], payload)
        return 72 if fault == utility + "-nonzero" and status == 0 else status
    if kind == "sudo":
        if args[0] == "test":
            negate = args[1] == "!"
            option, value = args[2:] if negate else args[1:]
            path = mapped(value)
            result = {"-e": path.exists, "-L": path.is_symlink, "-d": path.is_dir}[option]()
            return int(result if negate else not result)
        if args[0] == "install":
            require(args[1:2] == ["-d"] and args[-2] == "0700", "unexpected install")
            mapped(args[-1]).mkdir(mode=0o700, parents=True)
            return 0
        if args[0] == "stat":
            args = args[1:]
            kind = "stat"
        else:
            raise AssertionError("unexpected sudo action")
    if kind == "stat":
        require(args[0] == "-c", "unexpected stat")
        info = mapped(args[2]).stat()
        formats = {"%a": f"{stat.S_IMODE(info.st_mode):o}", "%s": str(info.st_size),
                   "%a:%U:%G": f"{stat.S_IMODE(info.st_mode):o}:" +
                   subprocess.check_output(["id", "-un"]).decode().strip() + ":" +
                   subprocess.check_output(["id", "-gn"]).decode().strip()}
        print(formats[args[1]])
        return 0
    if kind == "df":
        require(args[:2] == ["--output=avail", "-B1"], "unexpected df")
        print("Avail\n" + str(shutil.disk_usage(mapped(args[2])).free))
        return 0
    if kind == "checksum":
        check = args[0] == "-c"
        path = mapped(args[-1])
        event("checksum-check" if check else "checksum-write")
        if check and fault == ("globals-checksum" if "globals" in path.name else "dump-checksum"):
            target = Path(path.read_text().split("  ", 1)[1].strip())
            with mapped(str(target)).open("ab") as stream:
                stream.write(b"corruption")
        return forward([cfg["sha256sum"], *args])
    require(kind == "docker", "unexpected boundary")
    if args == ["info", "--format", "{{.DockerRootDir}}"]:
        print(root)
        return 0
    if args == ["ps", "--all", "--format", "{{.Names}}"]:
        return docker([*args, "--filter", "label=" + LABEL + "=" + cfg["token"]])
    if args == ["volume", "ls", "--format", "{{.Name}}"]:
        return docker([*args, "--filter", "label=" + LABEL + "=" + cfg["token"]])
    if args[0] in ("run",) or args[:2] == ["volume", "create"]:
        name = args[args.index("--name") + 1] if args[0] == "run" else args[-1]
        require(re.fullmatch("hookah-v126-" + cfg["run"] + r"-(pre-drain|quiesced)-[0-9]+", name),
                "unexpected rehearsal name")
        event("container-create" if args[0] == "run" else "volume-create")
        if fault == "wrong-owner-volume" and args[0] == "volume":
            args[args.index("--label") + 1] = "hookah.v126.rehearsal-owner=foreign-fixture"
        index = 1 if args[0] == "run" else 2
        return docker([*args[:index], "--label", LABEL + "=" + cfg["token"], *args[index:]])
    if args[0] == "cp":
        own(args[2].split(":")[0])
        mapped(args[1])
        event("copy-dump")
    elif args[0] == "exec":
        own(args[1])
        event("rehearsal-" + args[2])
        if fault == "restore-fail" and args[2] == "pg_restore":
            return 74
    elif args[0] == "rm":
        own(args[-1])
        require(args[-1] != cfg["source"], "production attempted source cleanup")
        if not fault:
            # Additional test oracle before production deletes its restored database.
            query = ("SELECT (SELECT count(*) FROM ht12aa_payload)=2 AND "
                     "(SELECT sum(value) FROM ht12aa_payload)=42 AND "
                     "(SELECT last_value FROM ht12aa_seq)=9 AND "
                     "NOT EXISTS (SELECT FROM pg_roles WHERE rolname='ht12aa_global');")
            result = run([cfg["docker"], "exec", args[-1], "psql", "-X", "-U", cfg["user"],
                          "-d", "v126_restore_rehearsal", "-Atqc", query])
            require(result.returncode == 0 and result.stdout == b"t\n", "restored data/global separation failed")
            event("data-verified-globals-not-applied")
        event("container-remove")
    elif args[:2] == ["volume", "rm"]:
        own(args[-1], True)
        require(args[-1] != cfg["sentinel"], "production attempted foreign cleanup")
        event("volume-remove")
    elif args[:2] == ["volume", "inspect"]:
        own(args[-1], True)
    elif args[0] == "inspect" or args[:2] == ["container", "inspect"]:
        own(args[-1])
    else:
        raise AssertionError("unexpected Docker action")
    return docker(args)


class BackupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="ht12aa-backup-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.token = uuid.uuid4().hex
        cls.cleaned = False
        cls.name = "ht12aa-source-" + cls.token
        cls.sentinel = "ht12aa-sentinel-" + cls.token
        cls.docker = shutil.which("docker")
        cls.sha256sum = shutil.which("sha256sum")
        require(cls.docker and cls.sha256sum, "Docker and sha256sum are required; no skip")
        cls.addClassCleanup(cls.cleanup_resources)
        atexit.register(cls.cleanup_resources)  # unittest skips class cleanups on interruption.
        result = run([cls.docker, "run", "--detach", "--name", cls.name, "--network", "none",
                      "--label", LABEL + "=" + cls.token, "--tmpfs", "/var/lib/postgresql/data",
                      "--env", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:17",
                      "-c", "log_connections=on", "-c", "log_line_prefix=%a|%u|%d|"], timeout=240)
        require(result.returncode == 0, "owned PostgreSQL17 launch failed")
        cls.source = result.stdout.decode().strip()
        for _ in range(120):
            ready = run([cls.docker, "exec", cls.source, "pg_isready", "-U", "postgres"])
            if ready.returncode == 0:
                # Require final server, not the image entrypoint's temporary init server.
                state = run([cls.docker, "exec", cls.source, "sh", "-c", "cat /proc/1/comm"])
                if state.stdout == b"postgres\n":
                    break
            time.sleep(0.5)
        else:
            raise AssertionError("owned PostgreSQL17 readiness failed")
        cls.sql("CREATE ROLE " + ident(USER) + " LOGIN SUPERUSER; CREATE ROLE ht12aa_global LOGIN PASSWORD " +
                literal(CANARY) + "; COMMENT ON ROLE ht12aa_global IS 'HT12AA known global'; " +
                "GRANT ht12aa_global TO " + ident(USER) + "; CREATE DATABASE ht12aa_plain OWNER " + ident(USER) + ";")
        cls.sql("CREATE DATABASE " + ident(DATABASE) + " OWNER " + ident(USER) + ";")
        cls.sql("CREATE ROLE " + ident(ODD_USER) + " LOGIN SUPERUSER;")
        require(cls.sql("SELECT rolpassword LIKE 'SCRAM-SHA-256$%' FROM pg_authid "
                        "WHERE rolname='ht12aa_global'") == b"t\n", "synthetic password verifier missing")
        for db in ("ht12aa_plain", DATABASE):
            cls.sql("CREATE TABLE flyway_schema_history(version text, success boolean); "
                    "INSERT INTO flyway_schema_history VALUES ('125', true); "
                    "CREATE TABLE ht12aa_payload(id integer PRIMARY KEY, value integer); "
                    "INSERT INTO ht12aa_payload VALUES (1,19),(2,23); "
                    "CREATE SEQUENCE ht12aa_seq; SELECT setval('ht12aa_seq',9);", db, USER)
        cls.sql("ALTER DATABASE postgres ALLOW_CONNECTIONS false; ALTER DATABASE template1 ALLOW_CONNECTIONS false;",
                "ht12aa_plain", USER)
        require(run([cls.docker, "volume", "create", "--label", LABEL + "=" + cls.token,
                     "--label", "hookah.v126.rehearsal-owner=foreign-fixture", cls.sentinel]).returncode == 0,
                "sentinel provisioning failed")
        image = run([cls.docker, "inspect", "--format", "{{.Image}}", cls.source])
        require(image.returncode == 0, "source image identity unavailable")
        cls.image = image.stdout.decode().strip()
        identity = run([cls.docker, "image", "inspect", "--format",
                        "{{.Id}} {{json .RepoDigests}} {{.Os}}/{{.Architecture}}", cls.image])
        require(identity.returncode == 0, "image provenance unavailable")
        print("HT12AA image: " + identity.stdout.decode().strip(), flush=True)
        for command in ("postgres", "pg_dump", "pg_dumpall", "pg_restore"):
            version = run([cls.docker, "exec", cls.source, command, "--version"])
            require(version.returncode == 0 and re.search(rb"PostgreSQL\) 17\.", version.stdout), "PG17 required")
            print("HT12AA " + version.stdout.decode().strip(), flush=True)
        require(cls.sql("SHOW server_version_num", "ht12aa_plain", USER).startswith(b"17"), "server must be PG17")
        before = run(["git", "show", BASE + ":scripts/v126-cutover.sh"], cwd=ROOT)
        require(before.returncode == 0, "immutable before source unavailable")
        cls.before = cls.root / "before.sh"
        cls.before.write_bytes(before.stdout)
        cls.driver = cls.root / "driver.sh"
        cls.driver.write_text(DRIVER)

    @classmethod
    def sql(cls, sql, db="postgres", user="postgres"):
        result = run([cls.docker, "exec", "-i", cls.source, "psql", "-XqAt", "-v", "ON_ERROR_STOP=1",
                      "-U", user, "-d", db], input=sql.encode())
        require(result.returncode == 0, "synthetic SQL failed")
        return result.stdout

    @classmethod
    def cleanup_resources(cls):
        if cls.cleaned:
            return
        # Only this random task label, including resources left by intentional failure.
        for volume in (False, True):
            listing = ["volume", "ls", "-q"] if volume else ["ps", "-aq", "--no-trunc"]
            result = run([cls.docker, *listing, "--filter", "label=" + LABEL + "=" + cls.token])
            require(result.returncode == 0, "test-owned cleanup inventory failed")
            for name in result.stdout.decode().splitlines():
                command = ["volume", "rm", name] if volume else ["rm", "-fv", name]
                require(run([cls.docker, *command]).returncode == 0, "test-owned cleanup failed")
            verify = run([cls.docker, *listing, "--filter", "label=" + LABEL + "=" + cls.token])
            require(verify.returncode == 0 and not verify.stdout.strip(), "test-owned resources remain")
        cls.cleaned = True
        print("HT12AA own-resource cleanup: PASS", flush=True)

    def invoke(self, source=None, phase="pre-drain", database="ht12aa_plain", user=USER,
               fault="", mode="backup", root=None):
        source = source or ROOT / "v126-cutover.sh"
        if root is None:
            root = self.root / uuid.uuid4().hex
            root.mkdir(mode=0o700)
            (root / "run").mkdir(mode=0o700)
            (root / "backups").mkdir(mode=0o700)
        run_id = "ht12aa-" + self.token
        cfg = dict(docker=self.docker, sha256sum=self.sha256sum, token=self.token, source=self.source,
                   run=run_id, sentinel=self.sentinel, user=user, database=database, fault=fault)
        (root / "config.json").write_text(json.dumps(cfg))
        if mode == "globals":
            # Extract the production call verbatim; no duplicate command in the test oracle.
            match = re.findall(r"    remote_compose exec -T postgres sh -c \\\n      '[^\n]*pg_dumpall[^\n]*' \\\n      > \"\$\{globals_file\}\"", source.read_text())
            require(len(match) == 1, "production globals call extraction drift")
            (root / "globals-command.sh").write_text('globals_file="$fixture/globals.sql"\n' + match[0] + "\n")
        env = {key: value for key, value in os.environ.items()
               if key in ("PATH", "HOME", "TMPDIR", "DOCKER_HOST", "DOCKER_CONFIG", "DOCKER_CONTEXT")}
        env.update(HT12AA_HELPER=str(Path(__file__).resolve()), HT12AA_RUN=run_id,
                   HT12AA_RELEASE=BASE, HT12AA_SOURCE=self.source)
        result = run(["bash", str(self.driver), str(source), str(root), phase, mode], env=env, group=True)
        require(CANARY.encode() not in result.stdout + result.stderr, "credential in diagnostics")
        require(b"SCRAM-SHA-256$" not in result.stdout + result.stderr, "verifier in diagnostics")
        return result, root, root / "backups" / "v126" / BASE / run_id

    def rejected(self, result, root, backup, phase="pre-drain"):
        self.assertNotEqual(result.returncode, 0, "unexpected backup success")
        self.assertNotIn(b"ARTIFACT\t", result.stdout, "failure emitted successful evidence")
        self.assertFalse((backup / (phase + ".dump.rehearsal.txt")).exists())
        self.assertFalse((root / "run" / (phase + "-backup-rehearsed.proof")).exists())

    def test_01_immutable_before(self):
        result, root, backup = self.invoke(source=self.before)
        self.rejected(result, root, backup)
        self.assertIn(b"LIBPQ_CONNINFO_MISSING_EQUALS", result.stderr)
        self.assertGreater((backup / "pre-drain.dump.pg_restore.list").stat().st_size, 0)
        self.assertEqual((backup / "globals.sql").stat().st_size, 0)
        self.assertFalse((backup / "globals.sql.sha256").exists())
        self.assertNotIn("volume-create", (root / "events").read_text())

    def test_02_full_production_both_phases(self):
        result, root, backup = self.invoke(database=DATABASE)
        self.assertEqual(result.returncode, 0, "real pre-drain backup/rehearsal failed")
        globals_bytes = (backup / "globals.sql").read_bytes()
        for expected in (b"CREATE ROLE ht12aa_global;", b"HT12AA known global", b"GRANT ht12aa_global TO",
                         ("CREATE ROLE " + USER + ";").encode()):
            require(expected in globals_bytes, "known global object missing")
        for forbidden in (CANARY.encode(), b"SCRAM-SHA-256$", b"md5", b" PASSWORD ", b"CREATE DATABASE"):
            require(forbidden not in globals_bytes, "globals privacy/scope failure")
        before = {p.name: p.read_bytes() for p in backup.iterdir()}
        result, _, _ = self.invoke(phase="quiesced", database=DATABASE, root=root)
        self.assertEqual(result.returncode, 0, "real quiesced backup/rehearsal failed")
        for name, contents in before.items():
            self.assertEqual((backup / name).read_bytes(), contents, "pre-drain artifact changed")
        self.assertEqual(stat.S_IMODE(backup.stat().st_mode), 0o700)
        for path in backup.iterdir():
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            self.assertEqual(path.stat().st_nlink, 1)
            self.assertFalse(path.is_symlink())
        events = (root / "events").read_text().splitlines()
        self.assertEqual(events.count("pg_dumpall"), 1)
        self.assertEqual(events.count("data-verified-globals-not-applied"), 2)
        self.assertEqual(events.count("container-remove"), 2)
        self.assertEqual(events.count("volume-remove"), 2)
        for gate in ("pre-drain-gate", "quiesced-gate"):
            self.assertIn(gate, events)
        expected = ["pg_dump", "pg_restore", "checksum-write", "checksum-check", "pg_dumpall",
                    "checksum-write", "checksum-check", "volume-create", "container-create", "copy-dump",
                    "rehearsal-createdb", "rehearsal-pg_restore", "rehearsal-psql", "rehearsal-psql",
                    "data-verified-globals-not-applied", "container-remove", "volume-remove"]
        cursor = 0
        for event in events:
            if cursor < len(expected) and event == expected[cursor]:
                cursor += 1
        self.assertEqual(cursor, len(expected), "backup operation order changed")
        logs = run([self.docker, "logs", self.source])
        self.assertEqual(logs.returncode, 0)
        require(("connection authorized: user=" + USER + " database=" + DATABASE +
                 " application_name=pg_dumpall").encode() in logs.stderr + logs.stdout,
                "actual initial database/role not observed")
        require(CANARY.encode() not in logs.stdout + logs.stderr, "credential in server diagnostics")
        marker = run([self.docker, "exec", self.source, "test", "!", "-e", "/tmp/ht12aa-injected"])
        self.assertEqual(marker.returncode, 0, "database name was shell interpreted")
        self.assertEqual(run([self.docker, "volume", "inspect", self.sentinel]).returncode, 0)

    def test_03_initial_database_and_role_fail_closed(self):
        for database, user in (("ht12aa_plain", USER), (DATABASE, USER), (DATABASE, ODD_USER), ("missing_ht12aa", USER),
                               ("", USER), (None, USER), ("postgres", USER), ("template1", USER),
                               ("ht12aa_plain", "missing_role")):
            with self.subTest(case="valid" if database in ("ht12aa_plain", DATABASE) and user in (USER, ODD_USER) else "invalid"):
                result, root, _ = self.invoke(database=database, user=user, mode="globals")
                expected = database in ("ht12aa_plain", DATABASE) and user in (USER, ODD_USER)
                self.assertEqual(result.returncode == 0, expected, "explicit connection selection mismatch")
                if expected:
                    self.assertIn(b"CREATE ROLE ht12aa_global;", (root / "globals.sql").read_bytes())

    def test_04_first_failure_stops_later_actions(self):
        faults = ["pg_dump-fail", "pg_dump-empty", "pg_dump-partial", "pg_dump-nonzero",
                  "pg_restore-fail", "pg_restore-empty", "pg_restore-nonzero", "dump-checksum",
                  "pg_dumpall-fail", "pg_dumpall-empty", "pg_dumpall-partial", "pg_dumpall-nonzero",
                  "globals-checksum", "restore-fail", "wrong-owner-volume"]
        for phase in ("pre-drain", "quiesced"):
            for fault in faults:
                if phase == "quiesced" and (fault.startswith("pg_dumpall") or fault == "globals-checksum"):
                    continue
                with self.subTest(phase=phase, fault=fault):
                    root = None
                    if phase == "quiesced":
                        # Bind the pre-existing namespace only; no fabricated backup PASS.
                        root = self.root / uuid.uuid4().hex
                        (root / "run").mkdir(parents=True, mode=0o700)
                        (root / "backups" / "v126" / BASE / ("ht12aa-" + self.token)).mkdir(parents=True, mode=0o700)
                        for parent in (root / "backups").rglob("*"):
                            parent.chmod(0o700)
                    result, root, backup = self.invoke(phase=phase, fault=fault, root=root)
                    self.rejected(result, root, backup, phase)
                    events = (root / "events").read_text().splitlines()
                    if fault not in ("restore-fail", "wrong-owner-volume"):
                        prefix = ["baseline", phase + "-gate", "user", "psql", "psql", "pg_dump"]
                        if not fault.startswith("pg_dump-") or fault == "pg_dump-partial":
                            prefix.append("pg_restore")
                        if fault == "dump-checksum" or fault.startswith("pg_dumpall-") or fault == "globals-checksum":
                            prefix += ["checksum-write", "checksum-check"]
                        if fault.startswith("pg_dumpall-") or fault == "globals-checksum":
                            prefix.append("pg_dumpall")
                        if fault == "globals-checksum":
                            prefix += ["checksum-write", "checksum-check"]
                        self.assertEqual(events, prefix, "work continued after first failure")
                    if fault.startswith("pg_dump-") and fault != "pg_dump-partial":
                        self.assertNotIn("pg_restore", events)
                    if fault.startswith("pg_restore-") or fault == "dump-checksum":
                        self.assertNotIn("pg_dumpall", events)
                    if fault.startswith("pg_dumpall-"):
                        self.assertFalse((backup / "globals.sql.sha256").exists())
                    if fault == "restore-fail":
                        self.assertNotIn("rehearsal-psql", events)
                        self.assertIn("container-remove", events)
                        self.assertIn("volume-remove", events)
                    if fault == "wrong-owner-volume":
                        self.assertNotIn("container-create", events)
                        self.assertNotIn("volume-remove", events)

    def test_05_create_only(self):
        result, root, backup = self.invoke()
        self.assertEqual(result.returncode, 0)
        snapshot = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in backup.iterdir()}
        for phase in ("pre-drain", "quiesced"):
            if phase == "quiesced":
                target = backup / "quiesced.dump"
                target.symlink_to(backup / "pre-drain.dump")
            result, _, _ = self.invoke(root=root, phase=phase)
            if phase == "quiesced":
                self.rejected(result, root, backup, phase)
            else:
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(b"ARTIFACT\t", result.stdout)
            for path, digest in snapshot.items():
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), digest, "existing artifact overwritten")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--boundary":
        try:
            sys.exit(boundary(sys.argv[2], sys.argv[3], sys.argv[4:]))
        except Exception:
            sys.stderr.write("HT12AA isolated boundary failed\n")
            sys.exit(97)
    else:
        os.umask(0o077)
        unittest.main(verbosity=2)
