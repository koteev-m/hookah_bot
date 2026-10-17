"""Opt-in CR-1 controller + real PostgreSQL/Flyway integration, synthetic authority.

HT_CLEAN_PG_TEST=1 python3 -B -m unittest discover -s scripts -p test_clean_install_postgres.py -v
Uses only already loaded immutable images, an internal network, random credentials,
and new labelled disposable resources. No ports, bot, SSH, downloads or real traffic.
The application image supplies its actual Flyway/JDBC libraries. App updates and
traffic/CI/authority are synthetic model inputs: this is NOT a deployment rehearsal.
"""

import copy
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import unittest
import uuid
import zipfile
import zlib

import clean_install_policy as clean
import staging_release as release
from test_clean_install_release import SyntheticAuthority, SyntheticRuntime, fixture


BACKEND = "sha256:5716515f536a7cb7733808819e70094081f95fb92ff8def3f5697e7b4a0972e3"
POSTGRES = "sha256:1eada89ec2521ae0f61125f6bdaf83bbbf88a43fecda48d112a83bb92df75c36"
REVISION = "7fc3ed0b7de556363bf2b72569b0c2dd98cfb81e"
ARCHIVE = "5320517520f8fa51bcc358c13c65192f4cb3102e6019219f8a6d0fece8363ea7"
JAVA = '''public class CleanFlywayFixture {
 public static void main(String[] args) throws Exception {
  Class<?> f=Class.forName("org.flywaydb.core.Flyway");
  Object c=f.getMethod("configure").invoke(null);
  c.getClass().getMethod("dataSource",String.class,String.class,String.class)
    .invoke(c,"jdbc:postgresql://postgres:5432/"+System.getenv("FIXTURE_DATABASE"),"fixture",System.getenv("FIXTURE_PASSWORD"));
  c.getClass().getMethod("locations",String[].class)
    .invoke(c,(Object)new String[]{"filesystem:/fixture/migrations"});
  Object instance=c.getClass().getMethod("load").invoke(c);
  f.getMethod("migrate").invoke(instance);
  f.getMethod("validate").invoke(instance);
 }
}
'''


def command(argv, *, payload=None, timeout=120):
    result = subprocess.run(argv, input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode:
        # Never emit arbitrary daemon/Java/env diagnostics into test output.
        raise RuntimeError("DISPOSABLE_COMMAND_FAILED: " + argv[0])
    return result.stdout


def sql_manifest(directory):
    rows = []
    for path in directory.glob("V*__*.sql"):
        raw = path.read_bytes()
        checksum = zlib.crc32("".join(raw.decode("utf-8-sig").splitlines()).encode())
        rows.append({"version": int(path.name.split("__")[0][1:]), "script": path.name,
                     "sha256": hashlib.sha256(raw).hexdigest(),
                     "checksum": checksum if checksum < 2 ** 31 else checksum - 2 ** 32})
    return sorted(rows, key=lambda row: row["version"])


class PostgreSQLRuntime(SyntheticRuntime):
    """Real SQL/history adapter, synthetic image-selection/traffic model ONLY."""

    def __init__(self, scope, image, root, name):
        super().__init__(scope, image)
        self.root, self.name = root, name
        self.snapshot["database"].update(system_id=self.sql("select system_identifier from pg_control_system()"),
                                         volume=name, network=name)

    def sql(self, sql):
        return command(["docker", "exec", "-i", self.name, "psql", "-XqAtw", "-U", "fixture", "-d", "fixture",
                        "--set=ON_ERROR_STOP=1"], payload=sql.encode()).decode().strip()

    def schema(self, database="fixture"):
        # Deterministic fixture DDL identity, excluding Flyway's own ledger.
        raw = command(["docker", "exec", self.name, "pg_dump", "-U", "fixture", "-d", database,
                       "--schema-only", "--no-owner", "--no-privileges", "--no-comments", "--no-tablespaces",
                       "--exclude-table=public.flyway_schema_history"]).decode()
        normalized = "\n".join(line for line in raw.splitlines()
                                if not line.startswith(("\\restrict ", "\\unrestrict ")))
        return hashlib.sha256(normalized.encode()).hexdigest()

    def clean_observe(self, *, timeout):
        value = super().clean_observe(timeout=timeout)
        value["schema"] = self.schema()
        exists = self.sql("select to_regclass('public.flyway_schema_history') is not null") == "t"
        value["history"] = json.loads(self.sql("select coalesce(json_agg(t order by rank),'[]') from "
            "(select installed_rank as rank,version::integer,script,checksum,success from flyway_schema_history) t")) if exists else []
        value["user_objects"] = json.loads(self.sql("select coalesce(json_agg(nspname||'.'||relname order by nspname,relname),'[]') "
            "from pg_class join pg_namespace on pg_namespace.oid=relnamespace "
            "where nspname not in ('pg_catalog','information_schema') and nspname not like 'pg_toast%'"))
        value["unidentified_writers"] = json.loads(self.sql("select coalesce(json_agg(pid),'[]') from pg_stat_activity "
            "where datname=current_database() and pid<>pg_backend_pid() and backend_type='client backend'"))
        return value

    def migrate(self, database, timeout=300):
        command(["docker", "run", "--rm", "--pull", "never", "--platform", "linux/amd64",
                     "--network", self.name, "--label", "ht.cr1.fixture=" + self.name,
                     "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--read-only",
                     "--env-file", str(self.root / "fixture.env"), "-e", "FIXTURE_DATABASE=" + database,
                     "--mount", "type=bind,src=" + str(self.root) + ",dst=/fixture,readonly",
                     "--entrypoint", "java", BACKEND, "-cp", "/fixture:/app/app/lib/*", "CleanFlywayFixture"],
                    timeout=timeout)

    def clean_execute(self, plan, before, *, timeout):
        if "image" in plan:
            if sql_manifest(self.root / "migrations") != plan["image"]["manifest"]:
                raise RuntimeError("SQL_BYTES_CHANGED")
            # No backend is running in this DB fixture; the actual application
            # startup/health/API smoke remains retained HT31 evidence.
            self.migrate("fixture", timeout)
        return super().clean_execute(plan, before, timeout=timeout)


@unittest.skipUnless(os.environ.get("HT_CLEAN_PG_TEST") == "1", "explicit disposable PostgreSQL opt-in required")
class DockerFlywayTests(unittest.TestCase):
    def test_bootstrap_noop_forward_preserves_data_and_blocks_replay(self):
        name = "ht-cr1-33-" + uuid.uuid4().hex[:12]
        resources = []
        root_parent = "/private/tmp" if Path("/private/tmp").exists() else None
        with tempfile.TemporaryDirectory(prefix=name, dir=root_parent) as temporary:
            root = Path(temporary).resolve()
            # Java UID in the image must be able to read only this synthetic fixture.
            root.chmod(0o755)
            try:
                for identity in (BACKEND, POSTGRES):
                    item = json.loads(command(["docker", "image", "inspect", identity]))[0]
                    self.assertEqual(item["Id"], identity)
                labels = json.loads(command(["docker", "image", "inspect", BACKEND]))[0]["Config"]["Labels"]
                self.assertEqual(labels["org.opencontainers.image.revision"], REVISION)
                self.assertEqual(labels["org.opencontainers.image.source"], release.SOURCE)
                # Copy immutable application JAR from a never-started container;
                # compare every embedded migration byte to this checkout.
                cid = command(["docker", "create", "--pull", "never", "--platform", "linux/amd64", "--network", "none",
                               "--label", "ht.cr1.fixture=" + name, BACKEND]).decode().strip()
                resources.append(("container", cid))
                command(["docker", "cp", cid + ":/app/app/lib/app-0.1.0.jar", str(root / "app.jar")])
                migrations = root / "migrations"
                migrations.mkdir()
                source = Path(__file__).resolve().parent.parent / "backend/app/src/main/resources/db/migration/postgresql"
                with zipfile.ZipFile(root / "app.jar") as jar:
                    entries = {entry.removeprefix("db/migration/postgresql/") for entry in jar.namelist()
                               if entry.startswith("db/migration/postgresql/") and entry.endswith(".sql")}
                    self.assertEqual(entries, {p.name for p in source.glob("*.sql")})
                    for path in source.glob("*.sql"):
                        self.assertEqual(jar.read("db/migration/postgresql/" + path.name), path.read_bytes())
                        shutil.copyfile(path, migrations / path.name)
                (root / "CleanFlywayFixture.java").write_text(JAVA)
                command(["javac", "--release", "17", str(root / "CleanFlywayFixture.java")])
                password = secrets.token_hex(24)
                environment = root / "fixture.env"
                environment.write_text("POSTGRES_DB=fixture\nPOSTGRES_USER=fixture\nPOSTGRES_PASSWORD=" + password
                                       + "\nFIXTURE_PASSWORD=" + password + "\n")
                environment.chmod(0o600)
                command(["docker", "network", "create", "--internal", "--label", "ht.cr1.fixture=" + name, name])
                resources.append(("network", name))
                command(["docker", "volume", "create", "--label", "ht.cr1.fixture=" + name, name])
                resources.append(("volume", name))
                command(["docker", "run", "-d", "--pull", "never", "--name", name, "--network", name,
                         "--network-alias", "postgres", "--restart", "no", "--label", "ht.cr1.fixture=" + name,
                         "--env-file", str(environment), "--mount", "type=volume,src=" + name + ",dst=/var/lib/postgresql/data", POSTGRES])
                resources.append(("container", name))
                for attempt in range(40):
                    try:
                        # initdb's temporary Unix-only postmaster is not ready.
                        command(["docker", "exec", name, "pg_isready", "-h", "127.0.0.1", "-U", "fixture", "-d", "fixture"])
                        break
                    except RuntimeError:
                        time.sleep(0.25)
                else:
                    self.fail("Disposable PostgreSQL did not start")
                scope, image = fixture()
                image.update(id=BACKEND, revision=REVISION, archive=ARCHIVE, manifest=sql_manifest(migrations))
                runtime = PostgreSQLRuntime(scope, image, root, name)
                # Simulate CI schema qualification on a separate disposable DB.
                # It is an expected result acquired BEFORE dispatch, not a hash
                # accepted opportunistically from the target after migration.
                runtime.sql("create database qualified")
                runtime.migrate("qualified")
                image["schema"] = runtime.schema("qualified")
                runtime.images[image["id"]] = copy.deepcopy(image)
                authority = SyntheticAuthority(scope)
                store = release.Store(root / "state", migration_target=root)
                controller = release.Controller.clean_installation(scope, store, runtime, authority)
                self.assertEqual(controller.clean_action("BOOTSTRAP", candidate=image), "CLOSED_V126")
                original = runtime.clean_observe(timeout=5)["history"]
                self.assertEqual(len(original), 126)
                with self.assertRaisesRegex(release.Refused, "INSTALLATION_ALREADY_EXISTS"):
                    controller.clean_action("BOOTSTRAP", candidate=image)
                controller.clean_action("TEST", configuration="8" * 64)
                controller.accept(smoke_passed=True)
                controller.clean_action("PUBLIC", configuration="9" * 64)
                # An application-owned row is inserted ONLY in the disposable DB.
                runtime.sql("insert into users(telegram_user_id, first_name) values(330000001,'CR1 retained row')")
                data_before = runtime.sql("select row_to_json(u) from users u where telegram_user_id=330000001")
                candidate = copy.deepcopy(image)
                candidate.update(id="sha256:" + "a" * 64, revision="b" * 40)
                runtime.images[candidate["id"]] = candidate
                self.assertEqual(controller.deploy(candidate, expected_current=image["id"], owner_approved=True), "READY_FOR_SMOKE")
                self.assertEqual(runtime.clean_observe(timeout=5)["history"], original)
                controller.accept(smoke_passed=True)
                (migrations / "V127__cr1_fixture.sql").write_text("ALTER TABLE users ADD COLUMN cr1_fixture_note text;\n")
                forward = copy.deepcopy(candidate)
                forward.update(id="sha256:" + "c" * 64, revision="d" * 40, manifest=sql_manifest(migrations))
                runtime.migrate("qualified")
                forward["schema"] = runtime.schema("qualified")
                runtime.images[forward["id"]] = forward
                self.assertEqual(controller.deploy(forward, expected_current=candidate["id"], owner_approved=True), "READY_FOR_SMOKE")
                controller.accept(smoke_passed=True)
                after = runtime.clean_observe(timeout=5)["history"]
                self.assertEqual(after[:126], original)
                self.assertEqual(len(after), 127)
                data_after = runtime.sql("select row_to_json(u)::jsonb - 'cr1_fixture_note' from users u where telegram_user_id=330000001")
                self.assertEqual(json.loads(data_before), json.loads(data_after))
                with self.assertRaisesRegex(release.Refused, "SCHEMA_INCOMPATIBLE_USE_FORWARD_FIX"):
                    controller.rollback(owner_approved=True)
                # Failure after Flyway commits is UNKNOWN, and cannot migrate again.
                uncertain = copy.deepcopy(forward)
                uncertain.update(id="sha256:" + "e" * 64, revision="f" * 40)
                runtime.images[uncertain["id"]] = uncertain
                runtime.fault = "lost_ack"
                with self.assertRaisesRegex(release.Refused, "CLEAN_OUTCOME_UNDEFINED_OPERATOR_REQUIRED"):
                    controller.deploy(uncertain, expected_current=forward["id"], owner_approved=True)
                with self.assertRaisesRegex(release.Refused, "OPERATOR_RECONCILIATION_REQUIRED"):
                    controller.deploy(uncertain, expected_current=forward["id"], owner_approved=True)
                self.assertEqual(runtime.clean_observe(timeout=5)["history"], after)
            finally:
                # Clean only resources created above, verifying ownership before deletion.
                # Include a --rm helper left running after a lost Docker CLI ACK.
                remaining = command(["docker", "ps", "-aq", "--filter", "label=ht.cr1.fixture=" + name]).decode().split()
                resources = [(kind, identity) for kind, identity in resources if kind != "container"]
                resources += [("container", identity) for identity in remaining]
                for kind, identity in sorted(resources, key=lambda item: {"container": 0, "volume": 1, "network": 2}[item[0]]):
                    inspected = json.loads(command(["docker", kind, "inspect", identity]))[0]
                    owned = inspected["Config"]["Labels"] if kind == "container" else inspected["Labels"]
                    self.assertEqual(owned.get("ht.cr1.fixture"), name)
                    command(["docker", kind, "rm", *( ["--force"] if kind == "container" else []), identity])


if __name__ == "__main__":
    unittest.main()
