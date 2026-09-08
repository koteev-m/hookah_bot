#!/usr/bin/env python3
"""Exact production derivation + real libpq/psql; only disposable loopback PostgreSQL."""
import ctypes as C
import hashlib
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.parse import quote
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
BASE = "5b205a8c53dfb374eed2803b7a120fe593149ddd"
SOURCE = (ROOT / "v126-cutover.sh").read_text()
CANARY = "HT12X_PRIVATE_" + "synthetic_only"
PSQL = shutil.which("psql")


def block(source):
    function = source.split("remote_final_v125_preflight() {\n", 1)[1]
    return function.split('"${pass_file}" <<\'PY\'\n', 1)[1].split("\nPY\n", 1)[0]


PRODUCER = block(SOURCE)
# No rewritten legacy serializer: execute the immutable base's entire producer.
OLD = subprocess.run(["git", "show", f"{BASE}:scripts/v126-cutover.sh"],
                     cwd=ROOT, capture_output=True, check=True).stdout.decode()
OLD_PRODUCER = block(OLD)


def safe_run(argv, **kwargs):
    """Never print child output/arguments on failure (they can contain canaries)."""
    try:
        return subprocess.run(argv, capture_output=True, timeout=kwargs.pop("timeout", 30), **kwargs)
    except (OSError, subprocess.TimeoutExpired):
        raise AssertionError("test subprocess unavailable or timed out") from None


def clean_env(root, service=None, password=None):
    env = {"PATH": os.environ["PATH"], "HOME": str(root), "LC_ALL": "C"}
    if service is not None:
        env.update(PGSERVICE="v126_preflight", PGSERVICEFILE=str(service), PGPASSFILE=str(password))
    return env


class Option(C.Structure):
    _fields_ = [(name, C.c_char_p) for name in
                ("keyword", "envvar", "compiled", "val", "label", "dispchar")] + [("dispsize", C.c_int)]


def load_libpq():
    if not PSQL:
        raise AssertionError("psql is required; no skip")
    if sys.platform == "darwin":
        linked = safe_run(["otool", "-L", PSQL])
        match = re.search(rb"\n\s+(/[^\n]+/libpq[^\s]+\.dylib) ", linked.stdout)
    else:
        linked = safe_run(["ldd", PSQL])
        match = re.search(rb"libpq\.so[^ ]* => (/\S+)", linked.stdout)
    if linked.returncode or not match:
        raise AssertionError("cannot identify the libpq actually linked to psql")
    path = os.fsdecode(match[1])
    pq = C.CDLL(path)
    declarations = {
        "PQlibVersion": (C.c_int, []),
        "PQconndefaults": (C.POINTER(Option), []),
        "PQconninfoFree": (None, [C.POINTER(Option)]),
        "PQconnectdb": (C.c_void_p, [C.c_char_p]),
        "PQconnectStart": (C.c_void_p, [C.c_char_p]),
        "PQstatus": (C.c_int, [C.c_void_p]),
        "PQerrorMessage": (C.c_char_p, [C.c_void_p]),
        "PQpass": (C.c_char_p, [C.c_void_p]),
        "PQconnectionUsedPassword": (C.c_int, [C.c_void_p]),
        "PQfinish": (None, [C.c_void_p]),
    }
    for name, (result, args) in declarations.items():
        getattr(pq, name).restype, getattr(pq, name).argtypes = result, args
    version = pq.PQlibVersion()
    required = os.environ.get("HT12X_REQUIRE_LIBPQ_MAJOR")
    if required and version // 10000 != int(required):
        raise AssertionError("required libpq major unavailable; no skip")
    psql_version = safe_run([PSQL, "--version"])
    if psql_version.returncode:
        raise AssertionError("psql version failed")
    if required and not re.search(rb"\b17\.\d+", psql_version.stdout):
        raise AssertionError("CI requires psql 17 linked to libpq 18")
    print(f"HT12X client: {psql_version.stdout.decode().strip()}; PQlibVersion={version}; loaded={path}", flush=True)
    return pq


PQ = load_libpq()


def uri(host="127.0.0.1", port="5432", user="operator", database="exact", password=CANARY,
        options=None):
    encoded_host = "[" + host + "]" if host == "::1" else quote(host, safe="")
    query = "&".join(quote(k, safe="") + "=" + quote(v, safe="") for k, v in (options or {}).items())
    return (f"postgresql://{quote(user, safe='')}:{quote(password, safe='')}@"
            f"{encoded_host}:{port}/{quote(database, safe='')}" + ("?" + query if query else ""))


class DerivationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ht12x-libpq-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.input = self.root / "input.uri"
        self.service = self.root / "service.conf"
        self.password = self.root / "pass ' \\: % # file"

    def derive(self, value=None, source=PRODUCER, expected_sha=None, fault=""):
        raw = (value or uri()).encode() if not isinstance(value, bytes) else value
        self.input.write_bytes(raw)
        self.input.chmod(0o600)
        argv = [sys.executable, "-", str(self.input), expected_sha or hashlib.sha256(raw).hexdigest(),
                str(self.service), str(self.password)]
        result = safe_run(argv, input=(fault + source).encode(), env=clean_env(self.root))
        self.assertTrue(CANARY.encode() not in result.stdout + result.stderr, "producer leaked canary")
        self.assertTrue(raw not in result.stdout + result.stderr, "producer leaked URI")
        return result

    def fresh(self):
        for path in (self.service, self.password):
            if path.is_file() or path.is_symlink():
                path.unlink()

    def values(self):
        # PQconndefaults reads PGSERVICEFILE without network/DNS or a rewritten parser.
        with patch.dict(os.environ, clean_env(self.root, self.service, self.password), clear=True):
            options = PQ.PQconndefaults()
            self.assertTrue(bool(options), "real libpq rejected service file")
            try:
                values = {}
                index = 0
                while options[index].keyword:
                    option = options[index]
                    values[option.keyword.decode()] = option.val.decode() if option.val is not None else None
                    index += 1
                return values
            finally:
                PQ.PQconninfoFree(options)

    def connection(self, wait=False, conninfo=b"service=v126_preflight"):
        with patch.dict(os.environ, clean_env(self.root, self.service, self.password), clear=True):
            conn = (PQ.PQconnectdb if wait else PQ.PQconnectStart)(conninfo)
            self.assertTrue(bool(conn), "libpq allocation failed")
            try:
                return (PQ.PQstatus(conn), PQ.PQerrorMessage(conn), PQ.PQpass(conn),
                        PQ.PQconnectionUsedPassword(conn))
            finally:
                PQ.PQfinish(conn)

    def assert_values(self, expected):
        actual = self.values()
        for key, value in expected.items():
            self.assertTrue(actual.get(key) == value, f"libpq value mismatch for {key}")
        self.assertTrue(actual.get("password") is None, "password must not be a service option")
        for path in (self.service, self.password):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_before_after_real_quoted_port(self):
        self.assertEqual(self.derive(source=OLD_PRODUCER).returncode, 0)
        status, error, _, _ = self.connection()
        self.assertEqual(status, 1)
        self.assertTrue(b"port" in error and b"integer" in error, "legacy quoted-port refusal not reproduced")
        old_psql = safe_run([PSQL, "-XwqAt", "--dbname=service=v126_preflight"],
                            input=b"SELECT 1;\n", env=clean_env(self.root, self.service, self.password))
        self.assertEqual(old_psql.returncode, 2, "legacy psql exit2 not reproduced")
        self.assertTrue(b"port" in old_psql.stderr and b"integer" in old_psql.stderr)
        self.fresh()
        self.assertEqual(self.derive().returncode, 0)
        self.assert_values({"host": "127.0.0.1", "port": "5432", "dbname": "exact", "user": "operator",
                            "passfile": str(self.password)})
        print("HT12X before=LIBPQ_REJECTED_QUOTED_PORT; after=EXACT_LIBPQ_VALUES", flush=True)

    def test_literal_values_and_every_allowed_option(self):
        literal = " leading 'quote' \"double\" \\ slash : = # [section] % + кириллица"
        options = {"application_name": literal, "channel_binding": "prefer", "connect_timeout": "3",
                   "gssencmode": "disable", "keepalives": "1", "keepalives_count": "2",
                   "keepalives_idle": "3", "keepalives_interval": "4", "options": "-c search_path=a,b",
                   "sslcert": literal, "sslcrl": literal, "sslkey": literal, "sslmode": "require",
                   "sslrootcert": literal, "target_session_attrs": "read-write"}
        self.assertEqual(self.derive(uri(host="HoST" + literal, port="05432", user=literal,
                                         database=literal, options=options)).returncode, 0)
        self.assert_values({"host": "HoST" + literal, "port": "05432", "user": literal,
                            "dbname": literal, "passfile": str(self.password), **options})
        self.fresh()
        self.assertEqual(self.derive(uri(host="::1", port="%35%34%33%32")).returncode, 0)
        self.assert_values({"host": "::1", "port": "5432"})
        self.fresh()
        self.assertEqual(self.derive(uri() + "?application_name=a+b%2520%20c").returncode, 0)
        self.assert_values({"application_name": "a+b%20 c"})

    def test_pgpass_literal_comment_wildcards_and_escaping(self):
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            secret = CANARY + " : \\ ' * # % "
            self.assertEqual(self.derive(uri(host="#literal", port=str(reserved.getsockname()[1]),
                                             user="*", database="*", password=secret)).returncode, 0)
            # Test-only hostaddr avoids DNS; the reserved non-listening port cannot
            # reach another database. Authenticated proof below uses only the alias.
            alias = b"service=v126_preflight hostaddr=127.0.0.1"
            _, _, password, _ = self.connection(conninfo=alias)
            self.assertTrue(password == secret.encode(), "pgpass literal escape mismatch")
            for override in (b" user=unmatched", b" dbname=unmatched", b" host=unmatched"):
                _, _, password, _ = self.connection(conninfo=alias + override)
                self.assertTrue(not password, "literal pgpass target became a wildcard")

    def test_service_line_byte_boundary(self):
        # application_name= is 17 bytes, LF is one: exactly 1022 accepted.
        for value, accepted in (("x" * 1004, True), ("x" * 1005, False), ("я" * 503, False)):
            self.fresh()
            result = self.derive(uri(options={"application_name": value}))
            self.assertEqual(result.returncode == 0, accepted)
            if accepted:
                self.assert_values({"application_name": value})
            else:
                self.assertFalse(self.service.exists() or self.password.exists())

    def test_unrepresentable_and_control_values(self):
        for field in ("host", "user", "database"):
            for suffix in (" ", "\r", "\n[evil]\nhost=other", "\x00", "\t", "\x7f"):
                self.fresh()
                self.assertNotEqual(self.derive(uri(**{field: "value" + suffix})).returncode, 0, field)
                self.assertFalse(self.service.exists() or self.password.exists())
        for field in ("application_name", "options", "sslkey"):
            self.assertNotEqual(self.derive(uri(options={field: "value "})).returncode, 0)
        for suffix in (" ", "\n", "\r"):
            self.password = self.root / ("pass" + suffix)
            self.assertNotEqual(self.derive().returncode, 0)
        self.password = self.root / "pass"
        self.assertNotEqual(self.derive(fault="import sys\nsys.argv[4] += chr(0)\n").returncode, 0)
        for control in ("\n", "\r", "\x00", "\t", "\x7f"):
            self.password = self.root / "pass"
            self.assertNotEqual(self.derive(uri(password=CANARY + control)).returncode, 0)

    def test_malformed_duplicate_unknown_and_hash_privacy(self):
        bad = ["garbage" + CANARY, "postgresql://[" + CANARY, uri(port="bad"), uri(port="0"),
               uri(port="65536"), uri(host="a,b"), uri() + "#", uri() + "#" + CANARY,
               uri() + "?password=" + CANARY, uri() + "?application_name=" + CANARY + "&application_name=x",
               uri() + "?application_name=x&%61pplication_name=y", uri() + "?" + CANARY,
               uri() + "?application_name=%FF", uri() + "?application_name=%ZZ",
               uri(password="%").replace("%25", "%"), uri() + "?application_name=",
               " " + uri(), uri().replace("postgresql", "postgre\tsql"), uri() + "\r\n",
               uri() + "\n\n", uri() + "?application_name=" + CANARY + "\rhost=other"]
        for value in bad:
            self.assertNotEqual(self.derive(value).returncode, 0, "malformed URI accepted")
            self.assertFalse(self.service.exists() or self.password.exists())
        self.assertNotEqual(self.derive(expected_sha="0" * 64).returncode, 0)
        self.assertFalse(self.service.exists() or self.password.exists())
        self.assertEqual(self.derive(uri().encode() + b"\n").returncode, 0)

    def test_create_only_and_io_failure_cleanup(self):
        for path in (self.service, self.password):
            path.write_text(CANARY)
            self.assertNotEqual(self.derive().returncode, 0)
            self.assertTrue(path.read_text() == CANARY, "existing file changed")
            path.unlink()
        self.service.symlink_to(self.input)
        self.assertNotEqual(self.derive().returncode, 0)
        self.assertTrue(self.service.is_symlink())
        self.service.unlink()
        original = self.password
        self.password = self.service
        self.assertNotEqual(self.derive().returncode, 0)
        self.password = original
        self.service = self.root / "absent" / "service"
        self.assertNotEqual(self.derive().returncode, 0)
        self.assertFalse(self.password.exists(), "own partial pgpass was not cleaned")
        # Execute the exact producer with only its OS read fault-injected.
        self.assertNotEqual(self.derive(fault="from pathlib import Path\n"
                                       "def fail_read(self): raise OSError('" + CANARY + "')\n"
                                       "Path.read_bytes = fail_read\n").returncode, 0)


class AuthenticatedTest(DerivationTest):
    # Inherit fixture helpers, not the derivation test methods (suite selects test_auth_*).
    @classmethod
    def setUpClass(cls):
        cls.cluster = tempfile.TemporaryDirectory(prefix="ht12x-pg17-")
        cls.addClassCleanup(cls.cluster.cleanup)
        root = Path(cls.cluster.name)
        secret = root / "bootstrap-password"
        secret.write_text(CANARY + "_bootstrap")
        secret.chmod(0o600)
        cls.name = "ht12x-libpq-" + os.urandom(8).hex()
        cls.addClassCleanup(cls.stop_cluster)
        started = safe_run(["docker", "run", "--detach", "--name", cls.name,
                            "--label", "com.hookah.ht12x.fixture=" + cls.name,
                            "--publish", "127.0.0.1::5432", "--tmpfs", "/var/lib/postgresql/data",
                            "--mount", f"type=bind,src={secret},dst=/run/bootstrap-password,readonly",
                            "--env", "POSTGRES_PASSWORD_FILE=/run/bootstrap-password",
                            "--env", "POSTGRES_HOST_AUTH_METHOD=scram-sha-256", "postgres:17"], timeout=180)
        if started.returncode:
            raise AssertionError("disposable PostgreSQL17 launch failed")
        for _ in range(60):
            ready = safe_run(["docker", "exec", cls.name, "pg_isready", "-h", "127.0.0.1",
                              "-U", "postgres", "-d", "postgres"])
            if ready.returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise AssertionError("disposable PostgreSQL17 readiness failed")
        port = safe_run(["docker", "port", cls.name, "5432/tcp"])
        if port.returncode or not re.fullmatch(rb"127\.0\.0\.1:[0-9]+\n", port.stdout):
            raise AssertionError("disposable database must bind loopback only")
        cls.port = port.stdout.decode().strip().split(":")[1]
        cls.user = " role ' \\ : % + #"
        cls.database = " db ' \\ : % + #"
        cls.secret = CANARY + " ' \\\\ : % + # * "
        sql = ('SET standard_conforming_strings=on;\nCREATE ROLE ' + cls.identifier(cls.user) +
               ' LOGIN PASSWORD ' + cls.literal(cls.secret) + ';\nCREATE DATABASE ' +
               cls.identifier(cls.database) + ' OWNER ' + cls.identifier(cls.user) + ';\n')
        setup = safe_run(["docker", "exec", "-i", cls.name, "psql", "-Xw", "-U", "postgres", "-d", "postgres",
                          "-v", "ON_ERROR_STOP=1"], input=sql.encode())
        if setup.returncode:
            raise AssertionError("synthetic role/database provisioning failed")

    @classmethod
    def stop_cluster(cls):
        inventory = safe_run(["docker", "ps", "--all", "--quiet", "--no-trunc", "--filter",
                              "label=com.hookah.ht12x.fixture=" + cls.name])
        if inventory.returncode:
            raise AssertionError("owned disposable PostgreSQL cleanup inventory failed")
        ids = inventory.stdout.splitlines()
        if not ids:
            return
        if len(ids) != 1 or not re.fullmatch(rb"[0-9a-f]{64}", ids[0]):
            raise AssertionError("owned disposable PostgreSQL cleanup inventory ambiguous")
        if safe_run(["docker", "rm", "--force", "--volumes", ids[0].decode()]).returncode:
            raise AssertionError("owned disposable PostgreSQL cleanup failed")

    @staticmethod
    def identifier(value):
        return '"' + value.replace('"', '""') + '"'

    @staticmethod
    def literal(value):
        return "'" + value.replace("'", "''") + "'"

    def auth_uri(self, **changes):
        values = dict(port=self.port, user=self.user, database=self.database, password=self.secret,
                      options={"connect_timeout": "2", "sslmode": "disable", "gssencmode": "disable",
                               "options": "-c statement_timeout=3000"})
        values.update(changes)
        return uri(**values)

    def select(self):
        sql = ("BEGIN READ ONLY;\nSELECT current_database()=" + self.literal(self.database) +
               " AND current_user=" + self.literal(self.user) +
               " AND current_setting('transaction_read_only')='on'"
               " AND current_setting('server_version_num')::int BETWEEN 170000 AND 179999;\nROLLBACK;\n")
        return safe_run([PSQL, "-XwqAt", "-v", "ON_ERROR_STOP=1", "--dbname=service=v126_preflight"],
                        input=sql.encode(), env=clean_env(self.root, self.service, self.password), timeout=10)

    def test_auth_exact_binding_readonly_pgpass(self):
        self.assertEqual(self.derive(self.auth_uri()).returncode, 0)
        self.assert_values({"dbname": self.database, "user": self.user, "port": self.port,
                            "passfile": str(self.password)})
        status, _, password, used = self.connection(wait=True)
        self.assertEqual(status, 0, "real libpq authentication failed")
        self.assertEqual(used, 1, "trust/peer must not replace password authentication")
        self.assertTrue(password == self.secret.encode(), "libpq did not read exact pgpass password")
        result = self.select()
        self.assertEqual(result.returncode, 0, "psql SELECT failed")
        self.assertTrue(result.stdout == b"t\n", "database/role/readonly/server binding failed")
        self.assertTrue(CANARY.encode() not in result.stdout + result.stderr, "psql leaked canary")
        print("HT12X authenticated=PASS; server=17; pgpass-password-used=YES; exact-role/database; read-only=on", flush=True)

    def test_auth_wrong_password_missing_passfile_and_connection_failure(self):
        self.assertEqual(self.derive(self.auth_uri(password=CANARY + "_wrong")).returncode, 0)
        result = self.select()
        self.assertNotEqual(result.returncode, 0, "wrong password passed")
        self.assertTrue(CANARY.encode() not in result.stdout + result.stderr)
        self.fresh()
        self.assertEqual(self.derive(self.auth_uri()).returncode, 0)
        self.password.unlink()
        self.assertNotEqual(self.select().returncode, 0, "missing pgpass passed")
        self.fresh()
        # Reserve a non-listening loopback port, so connection refusal cannot hit another DB.
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            self.assertEqual(self.derive(self.auth_uri(port=str(reserved.getsockname()[1]))).returncode, 0)
            self.assertNotEqual(self.select().returncode, 0, "connection failure passed")


class FixtureFailureTest(unittest.TestCase):
    def test_created_container_cleanup_after_launch_nonzero_or_timeout(self):
        for timeout in (False, True):
            class FailedFixture(AuthenticatedTest):
                pass

            calls = []
            def docker_result(argv, **kwargs):
                calls.append(argv)
                if argv[:2] == ["docker", "run"]:
                    if timeout:
                        raise AssertionError("test subprocess unavailable or timed out")
                    return subprocess.CompletedProcess(argv, 1, b"", b"")
                if argv[:2] == ["docker", "ps"]:
                    return subprocess.CompletedProcess(argv, 0, b"a" * 64 + b"\n", b"")
                if argv == ["docker", "rm", "--force", "--volumes", "a" * 64]:
                    return subprocess.CompletedProcess(argv, 0, b"", b"")
                raise AssertionError("unexpected fixture failure command")

            with patch(__name__ + ".safe_run", side_effect=docker_result):
                try:
                    with self.assertRaises(AssertionError):
                        FailedFixture.setUpClass()
                finally:
                    FailedFixture.doClassCleanups()
            self.assertFalse(FailedFixture.tearDown_exceptions, "fixture cleanup failed")
            self.assertTrue([call[:2] for call in calls] ==
                            [["docker", "run"], ["docker", "ps"], ["docker", "rm"]],
                            "created container was not cleaned after launch failure")
            self.assertTrue("com.hookah.ht12x.fixture=" + FailedFixture.name in calls[0] and
                            "label=com.hookah.ht12x.fixture=" + FailedFixture.name in calls[1],
                            "cleanup did not bind the owned fixture label")

    def test_failure_assertions_do_not_print_sensitive_values(self):
        case = DerivationTest()
        case.service = case.password = Path("unused")
        with patch.object(case, "values", return_value={"password": CANARY}):
            with self.assertRaises(AssertionError) as failure:
                case.assert_values({})
        self.assertTrue(CANARY not in str(failure.exception), "failure assertion leaked canary")
        auth_case = AuthenticatedTest()
        auth_case.port, auth_case.user, auth_case.database, auth_case.secret = "5432", "role", "db", CANARY
        auth_case.password = Path("unused")
        with patch.object(auth_case, "derive", return_value=subprocess.CompletedProcess([], 0)), \
                patch.object(auth_case, "assert_values"), \
                patch.object(auth_case, "connection", return_value=(0, b"", CANARY.encode(), 1)), \
                patch.object(auth_case, "select", return_value=subprocess.CompletedProcess([], 0, CANARY.encode(), b"")):
            with self.assertRaises(AssertionError) as failure:
                auth_case.test_auth_exact_binding_readonly_pgpass()
        self.assertTrue(CANARY not in str(failure.exception), "SELECT assertion leaked canary")


if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(DerivationTest)
    suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(FixtureFailureTest))
    if "--parse-only" not in sys.argv:
        suite.addTests(AuthenticatedTest(name) for name in unittest.defaultTestLoader.getTestCaseNames(AuthenticatedTest)
                       if name.startswith("test_auth_"))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    sys.exit(not result.wasSuccessful())
