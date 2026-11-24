"""Ordinary Hookah release. The Mac owns custody and Chromium. The VPS owns the existing operator lock."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import select
import shlex
import socket
import stat
import subprocess
import sys
import tempfile
import time
import zipfile
from types import SimpleNamespace

import production_profile as profile
import production_traffic as traffic
from simple_staging import Refused, atomic, private_json, require

ROOT = profile.ROOT
MAC_ROOT = Path("/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT")
OPERATOR = Path("/var/lib/hookah-production/operator")
BACKUP_ENV = Path("/etc/hookah-production/backup.env")
SERVICE = "hookah-production-backup.service"
TIMER = "hookah-production-backup.timer"
SHA = r"[a-f0-9]{40}"
sys.path.insert(0, str(profile.PROFILE))
import backup_points as points
import media_backup


def output(*args):
    return subprocess.check_output(args, text=True).strip()


def project_identity(task):
    import tomllib  # Mac operator only; the production and restore helpers also support Python 3.9.
    def git(*args):
        return output("git", "--no-optional-locks", *args)
    require(re.fullmatch(r"HT-[A-Z0-9][A-Z0-9-]*", task) and Path.cwd().resolve() == ROOT == MAC_ROOT,
            "STOP_PROJECT_CONTEXT_CONTAMINATION: operator cwd/task")
    gd = ROOT / ".git"
    for args, expected in [(("--show-toplevel",), ROOT), (("--absolute-git-dir",), gd),
                           (("--git-common-dir",), gd), (("--git-path", "index"), gd / "index")]:
        require(Path(git("rev-parse", "--path-format=absolute", *args)).resolve() == expected,
                "STOP_PROJECT_CONTEXT_CONTAMINATION: Git paths")
    head = git("rev-parse", "--verify", "HEAD^{commit}")
    registered = git("worktree", "list", "--porcelain").split("\n\n")
    require(any(r.splitlines()[:2] == ["worktree " + str(ROOT), "HEAD " + head] for r in registered),
            "STOP_PROJECT_CONTEXT_CONTAMINATION: checkout")
    for flags in ([], ["--push"]):
        urls = git("remote", "get-url", *flags, "--all", "origin").splitlines()
        require(urls and all(re.fullmatch(r"(?:https://|ssh://git@|git@)github\.com[/:]koteev-m/hookah_bot(?:\.git)?", u)
                             for u in urls), "STOP_PROJECT_CONTEXT_CONTAMINATION: origin")
    keys = git("config", "--includes", "--name-only", "--list").lower().splitlines()
    require(not any(re.match(r"^(include|url\.|core\.(worktree|hookspath|alternaterefscommand))", k) for k in keys)
            and not any(os.path.lexists(gd / n) for n in ("commondir", "objects/info/alternates", "objects/info/http-alternates"))
            and all((gd / n).resolve() == gd / n for n in ("HEAD", "index", "refs", "config", "objects", "config.worktree")),
            "STOP_PROJECT_CONTEXT_CONTAMINATION: Git redirection")
    require(not any(k.startswith("GIT_") and k not in ("GIT_OPTIONAL_LOCKS", "GIT_PAGER", "GIT_CONFIG_COUNT")
                    and not re.fullmatch(r"GIT_CONFIG_(KEY|VALUE)_\d+", k) for k in os.environ),
            "STOP_PROJECT_CONTEXT_CONTAMINATION: Git environment")
    for i in range(int(os.getenv("GIT_CONFIG_COUNT", "0"))):
        require(re.fullmatch(r"(credential|http)\.https://chatgpt(?:-staging)?\.com/backend-api/codex/sites/git/"
                             r"\.(helper|useHttpPath|proactiveAuth|followRedirects)", os.getenv(f"GIT_CONFIG_KEY_{i}", "")),
                "STOP_PROJECT_CONTEXT_CONTAMINATION: injected Git setting")
    require((ROOT / "AGENTS.md").read_bytes() == subprocess.check_output(
        ["git", "--no-optional-locks", "show", "HEAD:AGENTS.md"]), "STOP_PROJECT_CONTEXT_CONTAMINATION: instructions")
    codex = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))
    for config in (codex / "config.toml", ROOT / ".codex/config.toml"):
        if config.is_file():
            doc = tomllib.loads(config.read_text())
            require(not any(doc.get(k) for k in ("project_doc_fallback_filenames", "model_instructions_file",
                                                "developer_instructions", "instructions")),
                    "STOP_PROJECT_CONTEXT_CONTAMINATION: verify custom instruction origins")
    for directory in (codex, *ROOT.parents, ROOT, ROOT / "scripts", ROOT / "deploy", profile.PROFILE):
        for name in ("AGENTS.override.md", "AGENTS.md"):
            path = directory / name
            require(not os.path.lexists(path) or (path.resolve() == path and
                    (path == ROOT / "AGENTS.md" or not path.read_bytes().strip())),
                    "STOP_PROJECT_CONTEXT_CONTAMINATION: additional instruction authority")
    return head


def compatible_diff(old, new):
    # The operator reviews data semantics for this exact pair. Route infrastructure changes to the strict procedure.
    subprocess.run(["git", "--no-optional-locks", "merge-base", "--is-ancestor", old, new], cwd=ROOT, check=True)
    paths = subprocess.check_output(["git", "--no-optional-locks", "diff", "--no-ext-diff", "--no-renames",
                                     "--name-only", "-z", old, new], cwd=ROOT).decode().split("\0")
    allowed = ("miniapp/src/", "miniapp/e2e/", "backend/app/src/main/kotlin/", "backend/app/src/test/",
               "docs/", "miniapp/public/")
    require(all(p.startswith(allowed) or p in ("", "PROJECT_STATUS.md") for p in paths),
            "strict procedure required: changes outside ordinary application scope")


def operator_digest():
    checksum = hashlib.sha256(media_backup.recovery_tools(ROOT).encode())
    for path in (Path(__file__), *(profile.PROFILE / name for name in
                 ("compose.yaml", "init-app.sql", "traffic-gate.sh", "smoke.py", "browser-smoke.cjs", SERVICE, TIMER))):
        checksum.update(hashlib.sha256(path.read_bytes()).digest())
    return checksum.hexdigest()


def flags(args):
    require(re.fullmatch(SHA, args.approved_sha) and re.fullmatch(SHA, args.compatible_from)
            and args.approved_sha != args.compatible_from and re.fullmatch(r"sha256:[a-f0-9]{64}", args.digest)
            and args.run > 0 and args.attempt > 0, "exact approved staging candidate and compatible predecessor required")
    return dict(revision=args.approved_sha, image="ghcr.io/koteev-m/hookah_bot/backend@" + args.digest, ci_run=args.run)


def unit_properties(unit, *keys):
    lines = output("systemctl", "show", "--all", unit, *["--property=" + k for k in keys]).splitlines()
    return dict(line.split("=", 1) for line in lines)


def schedule(expect_active, *, allow_catchup=False):
    for unit in (TIMER, SERVICE):
        props = unit_properties(unit, "FragmentPath", "DropInPaths", "NeedDaemonReload")
        path = Path("/etc/systemd/system") / unit
        require(props["FragmentPath"] == str(path) and not props["DropInPaths"]
                and path.read_bytes() == (profile.PROFILE / unit).read_bytes(), "backup unit drift; resolve before deployment")
        if props["NeedDaemonReload"] == "yes":
            print("Backup unit reports NeedDaemonReload=yes; verifying loaded settings:", unit, file=sys.stderr)
    timer = unit_properties(TIMER, "UnitFileState", "Unit", "TimersMonotonic", "TimersCalendar", "Persistent",
                            "ActiveState", "NextElapseUSecRealtime")
    hooks = ("ExecCondition", "ExecStartPre", "ExecStartPost", "ExecStop", "ExecStopPost")
    service = unit_properties(SERVICE, "EnvironmentFiles", "Environment", "ExecStart", "Type", *hooks,
                              "ActiveState", "Result", "User")
    command = "/usr/bin/python3 -B ${BACKUP_TOOL} create ${ADMITTED_PLAN} ${AGE_RECIPIENT_FILE} ${BACKUP_SPOOL} ${QUALIFIED_POINTS}"
    require(service["User"] in ("", "root") and service["EnvironmentFiles"] == str(BACKUP_ENV) + " (ignore_errors=no)"
            and service["ExecStart"].count("argv[]=") == 1 and "argv[]=" + command + " ;" in service["ExecStart"]
            and "path=/usr/bin/python3 ;" in service["ExecStart"] and service["Type"] == "oneshot"
            and not any(service[k] for k in hooks)
            and service["Environment"] == "DOCKER_HOST=unix:///var/run/docker.sock"
            and timer["Unit"] == SERVICE and not timer["TimersMonotonic"]
            and timer["UnitFileState"] == "enabled" and timer["Persistent"] == "yes"
            and timer["TimersCalendar"].count("OnCalendar=") == 1
            and "OnCalendar=*-*-* 00,06,12,18:00:00 UTC ;" in timer["TimersCalendar"],
            "loaded backup schedule/service differs; inspect NeedDaemonReload before release")
    require(service["ActiveState"] in (("inactive", "activating", "active") if allow_catchup else ("inactive",))
            and service["Result"] == "success", "backup busy or unresolved backup error")
    if expect_active:
        catching_up = allow_catchup and service["ActiveState"] in ("activating", "active")
        require(timer["ActiveState"] == "active" and (timer["NextElapseUSecRealtime"] not in ("", "n/a") or catching_up),
                "backup timer not scheduled")


def settings():
    points.private_file(BACKUP_ENV)
    rows = [s.split("=", 1) for s in BACKUP_ENV.read_text().splitlines() if s and not s.startswith("#")]
    data = dict(rows)
    require(len(data) == len(rows) and set(data) == {"ADMITTED_PLAN", "BACKUP_TOOL", "BACKUP_SPOOL", "AGE_RECIPIENT_FILE",
                                                     "QUALIFIED_POINTS"}, "ambiguous backup environment")
    paths = {k: Path(v) for k, v in data.items()}
    require(all(p.is_absolute() and p == p.resolve() for p in paths.values())
            and paths["ADMITTED_PLAN"].parent == OPERATOR and paths["AGE_RECIPIENT_FILE"] == OPERATOR / "recipient.txt"
            and paths["BACKUP_SPOOL"] == OPERATOR.parent / "scheduled"
            and paths["QUALIFIED_POINTS"].parent == OPERATOR
            and paths["BACKUP_TOOL"].name == "backup_points.py", "unexpected production backup settings")
    return paths


def switch_backup(old, final):
    current = settings()["ADMITTED_PLAN"]
    require(current in (old, final), "backup plan changed independently")
    if current == final:
        return
    raw = BACKUP_ENV.read_bytes()
    before = b"ADMITTED_PLAN=" + os.fsencode(old)
    rows = raw.splitlines(keepends=True)
    require(sum(line.rstrip(b"\r\n") == before for line in rows) == 1, "backup plan compare-and-swap failed")
    st = BACKUP_ENV.stat()
    with tempfile.NamedTemporaryFile(dir=BACKUP_ENV.parent, delete=False) as f:
        tmp = Path(f.name)
        try:
            f.write(b"".join(line.replace(before, b"ADMITTED_PLAN=" + os.fsencode(final), 1)
                             if line.rstrip(b"\r\n") == before else line for line in rows))
            f.flush()
            os.fchown(f.fileno(), st.st_uid, st.st_gid)
            os.fchmod(f.fileno(), stat.S_IMODE(st.st_mode))
            os.fsync(f.fileno())
            require(BACKUP_ENV.read_bytes() == raw, "backup configuration changed")
            os.replace(tmp, BACKUP_ENV)
            profile.sync_directory(BACKUP_ENV.parent)
        finally:
            tmp.unlink(missing_ok=True)


def ask(kind, **fields):
    # stdout is also used by the existing operators; prefix only our structured replies.
    print("HT-UPDATE " + json.dumps(dict(kind=kind, **fields)), flush=True)
    require(select.select([sys.stdin], [], [], 7200 if kind == "backup" else 180)[0], "Mac operator response timed out")
    line = sys.stdin.buffer.readline(6 * 1024**2)
    require(line.endswith(b"\n"), "Mac operator disconnected")
    response = json.loads(line)
    if isinstance(response, dict) and "refused" in response:
        raise Refused("Mac operator: " + str(response["refused"]))
    return response


def staging(release):
    record = private_json(Path("/opt/hookah-bot/result.json"))
    require(record["status"] == "SUCCESS" and all(record["target"].get(k) == v for k, v in release.items()),
            "approved candidate differs from successful Hookah staging")
    ids = output("docker", "ps", "-q", "--filter", "label=com.docker.compose.project=hookah-bot",
                 "--filter", "label=com.docker.compose.service=backend").splitlines()
    require(len(ids) == 1 and output("docker", "inspect", "--format", "{{.Config.Image}}", ids[0]) == release["image"],
            "running staging image differs")
    version = json.loads(output("curl", "-fsS", "--max-time", "8", "--noproxy", "*", "http://127.0.0.1:8080/version"))
    require(version["env"] == "staging" and version["version"] == release["revision"], "staging version mismatch")


def native(action, plan, previous=None):
    traffic.dispatch_locked(SimpleNamespace(action=action, plan=plan, previous=previous or plan))


def existing_binds(plan):
    # A newer operator checkout must preserve the existing PostgreSQL and traffic-gate mounts.
    services = json.loads(plan.read_bytes())["services"]
    parents = set()
    for svc, name, dest in (("postgres", "init-app.sql", "/docker-entrypoint-initdb.d/10-hookah.sql"),
                            ("backend", "traffic-gate.sh", "/opt/hookah/traffic-gate.sh")):
        binds = [m for m in services[svc]["volumes"] if m["type"] == "bind" and m["target"] == dest]
        require(len(binds) == 1 and binds[0].get("read_only") is True, "unexpected production mount")
        path = Path(binds[0]["source"])
        require(path == path.resolve() and path.is_absolute() and path.name == name
                and path.read_bytes() == (profile.PROFILE / name).read_bytes(), "production mount changed: strict procedure")
        parents.add(path.parent)
    require(len(parents) == 1, "inconsistent production mount roots")
    return parents.pop()


def check_open(plan):
    profile.verify_running(plan)
    sha = traffic.backend(plan)["environment"]["APP_VERSION"]
    tokens = profile.GATE.read_text().split()
    require(len(tokens) == 2 and tokens[0] == sha and profile.GATE.with_name("activate").read_text().split() == tokens,
            "live traffic decision does not match plan")
    polling = "running" if traffic.backend(plan)["environment"]["TELEGRAM_BOT_ENABLED"] == "true" else "disabled"
    traffic.check_edge_open(sha, tokens[1], polling)


def closed_smoke(plan):
    traffic.check_edge_closed()
    profile.verify_running(plan)
    sha = traffic.backend(plan)["environment"]["APP_VERSION"]
    instance = traffic.await_readiness(sha, traffic.image_assets(plan))
    require(ask("browser", sha=sha, instance=instance) is True, "Chromium smoke failed")
    traffic.await_readiness(sha, traffic.image_assets(plan), instance)
    traffic.check_edge_closed()


class Update:
    def __init__(self, args, release):
        self.args = args
        self.release = release
        self.config = settings()
        self.journal = OPERATOR / "ordinary-update.json"
        self.new = OPERATOR / ("plan-" + release["revision"] + ".json")
        self.key = dict(candidate=release, attempt=args.attempt, predecessor=args.compatible_from)
        self.record = private_json(self.journal) if self.journal.exists() else {}
        # Only restart_backups grants a catch-up snapshot. Never carry the grant into a later phase.
        self.record.pop("backup_plan", None)
        if self.record.get("key") != self.key:
            require(not self.record or self.record["phase"] in ("done", "rolled-back"), "another update needs reconciliation")
            self.record = {}
        self.old = Path(self.record["old"]) if self.record else self.config["ADMITTED_PLAN"]
        require(self.old.parent == OPERATOR and self.old.resolve() == self.old and self.old != self.new,
                "invalid predecessor plan")
        profile.verify_plan_pin(self.old)
        require(traffic.backend(self.old)["environment"]["APP_VERSION"] == args.compatible_from, "wrong predecessor")

    def phase(self, phase):
        atomic(self.journal, dict(self.record, phase=phase))
        self.record["phase"] = phase

    def prepare(self):
        require(self.config["ADMITTED_PLAN"] == self.old, "unexpected backup target at admission")
        check_open(self.old)
        schedule(True)
        staging(self.release)
        archive = base64.b64decode(ask("artifact"), validate=True)
        if not self.new.exists():
            with tempfile.NamedTemporaryFile(mode="w", dir=OPERATOR) as f:
                json.dump(self.release, f)
                f.flush()
                profile.execute(SimpleNamespace(action="admit", runtime=OPERATOR / "runtime.env", candidate=Path(f.name),
                    output=self.new, previous=self.old, bind_directory=existing_binds(self.old),
                    archive=archive, attempt=self.args.attempt, image_sizes=None,
                    evidence=None, evidence_sha256=None))
        else:
            profile.verify_published(self.release, archive=archive, expected_attempt=self.args.attempt)
        self.plans()
        verify_backup(self.old, self.config)
        self.record = dict(key=self.key, old=str(self.old), old_pin=profile.admission_pin(self.old).read_text(),
                           new_pin=profile.admission_pin(self.new).read_text())
        self.phase("closing")

    def plans(self):
        profile.verify_plan_pin(self.new)
        doc = json.loads(self.new.read_bytes())
        profile.confirm_transition(self.old, self.new.read_bytes())
        require(profile.plan_schema(json.loads(self.old.read_bytes())) == profile.plan_schema(doc)
                and doc["x-hookah-release-evidence"]["candidate"] == self.release
                and doc["x-hookah-release-evidence"]["attempt"] == self.args.attempt,
                "strict update required: release or schema differs")
        if self.record:
            require(profile.admission_pin(self.old).read_text() == self.record["old_pin"]
                    and profile.admission_pin(self.new).read_text() == self.record["new_pin"], "admitted plans changed")

    def restart_backups(self, plan):
        require(settings()["ADMITTED_PLAN"] == plan, "backup target changed before restarting timer")
        atomic(self.journal, dict(self.record, backup_plan=str(plan)))
        subprocess.run(["systemctl", "start", TIMER], check=True)
        schedule(True, allow_catchup=True)

    def finish(self, plan, outcome):
        check_open(plan)
        media_backup.backup_target(plan, self.config["AGE_RECIPIENT_FILE"].read_text().strip(), online=True)
        switch_backup(self.old if plan == self.new else self.new, plan)
        self.restart_backups(plan)
        self.phase(outcome)

    def rollback(self):
        self.phase("rollback")
        which = traffic.state(self.old, self.new)
        require(which in ("NEW running", "NEW stopped", "OLD running", "OLD stopped"),
                "unknown deployment state: inspect before recovery")
        if which == "OLD running" and profile.GATE.exists():
            self.finish(self.old, "rolled-back")
            return
        require(not profile.GATE.exists() and not profile.GATE.with_name("activate").exists(),
                "public writes or polling possible: inspect before rollback")
        if which.startswith("NEW"):
            native("close", self.new)
        elif which == "OLD running":
            native("close", self.old)
        native("rollback", self.new, self.old)
        closed_smoke(self.old)
        native("open", self.old)
        self.finish(self.old, "rolled-back")

    def run(self):
        fresh = not self.record
        if fresh:
            self.prepare()
        self.plans()
        phase = self.record["phase"]
        resuming_stage = phase == "staging"
        if phase in ("done", "rolled-back"):
            plan = self.new if phase == "done" else self.old
            check_open(plan)
            require(settings()["ADMITTED_PLAN"] == plan, "completed update no longer current")
            schedule(True, allow_catchup=True)
            return phase
        if phase == "rollback":
            self.rollback()
            return "rolled-back"
        if phase == "opening" and profile.GATE.exists():
            self.finish(self.new, "done")
            return "done"
        if phase == "closing":
            try:
                if profile.GATE.exists():
                    check_open(self.old)
                if not fresh:
                    staging(self.release)
                    verify_backup(self.old, self.config)
                    require(settings()["ADMITTED_PLAN"] == self.old, "old backup binding changed")
                    self.phase("closing")
                schedule(False)
                subprocess.run(["systemctl", "stop", TIMER], check=True)
                schedule(False)
                native("close", self.old)
            except (Refused, OSError, ValueError, subprocess.SubprocessError):
                if profile.GATE.exists():
                    check_open(self.old)
                    self.restart_backups(self.old)
                    # No release was applied. The next attempt must repeat admission with new evidence.
                    self.journal.unlink()
                    profile.sync_directory(self.journal.parent)
                elif not profile.GATE.with_name("activate").exists() and traffic.state(self.old, self.new).startswith("OLD "):
                    self.rollback()
                    return "rolled-back"
                raise
            self.phase("staging")
            phase = "staging"
        if phase == "staging":
            try:
                if resuming_stage:
                    verify_backup(self.old, self.config)
                traffic.check_edge_closed()
                which = traffic.state(self.old, self.new)
                if which.startswith("OLD "):
                    if resuming_stage:
                        staging(self.release)
                    native("stage", self.new, self.old)
                elif which == "NEW stopped":
                    native("resume", self.new, self.old)
                native("inspect", self.new, self.old)
                require(traffic.state(self.old, self.new) == "NEW running", "could not identify running replacement")
                closed_smoke(self.new)
            except (Refused, ValueError, OSError, subprocess.SubprocessError):
                self.rollback()
                return "rolled-back"
            self.phase("opening")
        require(self.record["phase"] == "opening", "unrecognized release journal")
        if phase == "opening":
            verify_backup(self.old, self.config)
            closed_smoke(self.new)  # A restart can replace the previously browser-tested JVM.
        native("open", self.new)
        self.finish(self.new, "done")
        return "done"


def verify_backup(plan, config):
    points.status(config["BACKUP_SPOOL"], points.datetime.now(points.timezone.utc))
    version = points.producer(config["AGE_RECIPIENT_FILE"], config["BACKUP_TOOL"].parents[2])
    marker = private_json(config["QUALIFIED_POINTS"].parent / "scheduled-producer.json")
    require(marker["version"] == version and points.NAME.fullmatch(marker["first"]),
            "a new point from the enrolled backup implementation is required")
    evidence = ask("backup", tools=version["tools"])
    fresh = evidence["fresh"]
    # A newer local capture can still be in transit. Compare the named off-site point with its source.
    name = fresh["point"]
    require(points.NAME.fullmatch(name), "invalid external point name")
    pin = points.verified(config["BACKUP_SPOOL"] / name)
    require(points.NAME.fullmatch(evidence["tested"]) and marker["first"] <= evidence["tested"] <= name,
            "rehearse a point from the current backup producer")
    cid = profile.verified_database(plan, config["AGE_RECIPIENT_FILE"].read_text().strip())
    source = profile.pg_query(cid, "postgres", "SELECT system_identifier, oid, timeline_id FROM pg_control_system(), "
                              "pg_database, pg_control_checkpoint() WHERE datname=current_database()")
    require(fresh == dict(point=name, pin=pin, source=source, schema=str(profile.plan_schema(json.loads(plan.read_bytes()))),
                          postgres=json.loads(plan.read_bytes())["services"]["postgres"]["image"])
            and 0 <= time.time() - points.timestamp(name) <= 8 * 3600, "external point differs from current production")


def server(args):
    require(os.geteuid() == 0 and re.fullmatch(r"HT-[A-Z0-9][A-Z0-9-]*", args.task_id)
            and Path.cwd().resolve() == ROOT and ROOT.parent == Path("/opt/hookah-production/releases")
            and re.fullmatch(SHA, ROOT.name) and hashlib.sha256(Path("/etc/machine-id").read_bytes()).hexdigest()
            == args.machine and not any(k.startswith(("DOCKER_", "COMPOSE_")) for k in os.environ if k != "DOCKER_HOST")
            and os.environ.get("DOCKER_HOST") == "unix:///var/run/docker.sock", "wrong production operator target")
    require(args.operator_digest == operator_digest(), "STOP_PROJECT_CONTEXT_CONTAMINATION: operator checkout differs")
    with profile.host.operator_lock():
        require(not any(os.path.lexists(profile.GATE.with_name(n)) for n in ("recovery-hold", ".install-intent")),
                "recovery or installation in progress")
        result = Update(args, flags(args)).run()
    print("HT-RESULT " + result, flush=True)
    return 0 if result == "done" else 2


def mac_backup(config, requested_tools):
    state = Path(config["receipts"])
    # Protect against unmount/remount and concurrent transfers throughout the read-back.
    disk = points.external_disk(config["toshiba_uuid"])
    directory = points.VOLUME / "Hookah-Production-Scheduled"
    launch = output("launchctl", "print", f"gui/{os.getuid()}/com.hookahtootah.production-db-backup")
    require(re.search(r"(?m)^\s*last exit code = 0\s*$", launch), "Mac backup transfer is unverified or failed")
    with points.exclusive(state), points.exclusive(directory):
        result = points.release_backup(directory, state, time.time(), requested_tools)
        require(directory.stat().st_dev == os.stat(disk["DeviceNode"]).st_rdev
                and points.external_disk(config["toshiba_uuid"])["DeviceNode"] == disk["DeviceNode"], "external disk changed")
        return result


def handle_request(message, config, args, port, release):
    if message["kind"] == "artifact":
        subprocess.run(["node", str(profile.PROFILE / "browser-smoke.cjs"), "--preflight"], cwd=ROOT, check=True, timeout=45)
        artifact = profile.candidate_artifact(release, args.attempt)
        raw = subprocess.check_output(["gh", "api", "--hostname", "github.com",
                                       f"repos/{profile.REPO}/actions/artifacts/{artifact['id']}/zip"],
                                      cwd=ROOT, timeout=60, stderr=subprocess.DEVNULL)
        profile.check_archive(raw, artifact, release)
        return base64.b64encode(raw).decode()
    if message["kind"] == "backup":
        return mac_backup(config, message["tools"])
    require(message["kind"] == "browser" and message["sha"] in (args.approved_sha, args.compatible_from),
            "unexpected production operator message")
    try:
        smoke = subprocess.run(["node", str(profile.PROFILE / "browser-smoke.cjs"), str(port), message["sha"],
                                message["instance"]], cwd=ROOT, timeout=90)
        return smoke.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def mac(args, release):
    project_identity(args.task_id)
    compatible_diff(args.compatible_from, args.approved_sha)
    points.private_file(args.config)
    config = private_json(args.config)
    checkout = config["checkout"]
    require(re.fullmatch(r"/opt/hookah-production/releases/[0-9a-f]{40}", checkout)
            and re.fullmatch(r"[0-9a-f]{64}", config["machine_sha256"])
            and re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9.-]*", config["host"]), "invalid enrolled production target")
    known = Path(config["known_hosts"])
    identity = Path(config["ssh_identity"])
    require(all(p.is_absolute() and p.resolve() == p and p.is_file() for p in (known, identity))
            and not known.stat().st_mode & 0o022, "pinned operator SSH files required")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    ssh = ["ssh", "-F", "/dev/null", "-T", "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
           "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no", "-o", "HostKeyAlgorithms=ssh-ed25519",
           "-o", "ForwardAgent=no", "-o", "ControlMaster=no", "-o", "ControlPath=none", "-o", "ExitOnForwardFailure=yes",
           "-o", "ConnectTimeout=15", "-o", "GlobalKnownHostsFile=/dev/null", "-o", f"UserKnownHostsFile={known}",
           "-i", str(identity), "-L", f"127.0.0.1:{port}:127.0.0.1:18080", "root@" + config["host"]]
    remote = ["env", "-i", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "HOME=/root",
              "DOCKER_HOST=unix:///var/run/docker.sock", "/usr/bin/python3", "-EBs", "scripts/production_update.py",
              "--server", "--machine", config["machine_sha256"], "--operator-digest", operator_digest(),
              "--approved-sha", args.approved_sha,
              "--compatible-from", args.compatible_from, "--digest", args.digest, "--run", str(args.run),
              "--attempt", str(args.attempt), "--task-id", args.task_id]
    # No GH_TOKEN, ssh-agent forwarding, remote installation or shell-interpolated secrets.
    project_identity(args.task_id)
    proc = subprocess.Popen(ssh + ["cd " + shlex.quote(checkout) + " && exec " + shlex.join(remote)], cwd=ROOT,
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    result = None
    try:
        for line in proc.stdout:
            if line.startswith("HT-UPDATE "):
                try:
                    answer = handle_request(json.loads(line[len("HT-UPDATE "):]), config, args, port, release)
                except (Refused, OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as exc:
                    # Keep the operator channel available for a closed predecessor's rollback smoke.
                    answer = {"refused": str(exc) if isinstance(exc, Refused) else type(exc).__name__}
                proc.stdin.write(json.dumps(answer) + "\n")
                proc.stdin.flush()
            elif line.startswith("HT-RESULT "):
                result = line.strip().split()[-1]
            else:
                print(line.rstrip(), flush=True)
    finally:
        try:
            proc.stdin.close()
        except BrokenPipeError:
            pass
        proc.stdout.close()
        rc = proc.wait()
    if (rc, result) in ((0, "done"), (2, "rolled-back")):
        expected = args.approved_sha if result == "done" else args.compatible_from
        public_smoke(expected)
    require(rc == 0 and result == "done", "production update did not complete: " + str(result or "reconcile by repeating same command"))
    print("Production open on", args.approved_sha, "and backup schedule verified")
    return 0


def public_smoke(expected):
    base = ["curl", "--disable", "--silent", "--show-error", "--max-time", "10", "--noproxy", "*",
            "--write-out", "\\n%{http_code}"]
    raw, _, status = output(*base, "https://hookahtootah.club/version").rpartition("\n")
    version = json.loads(raw)
    require(status == "200" and version.get("version") == expected and version.get("env") == "prod",
            "external HTTPS version mismatch; inspect the current open release")
    page, _, status = output(*base, "https://hookahtootah.club/miniapp/").rpartition("\n")
    require(status == "200" and re.search(r"""id=["']app["']""", page), "external HTTPS Mini App unavailable")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--task-id", required=True)
    parser.add_argument("--approved-sha", required=True, help="exact owner-authorized release tested on staging")
    parser.add_argument("--compatible-from", required=True, help="reviewed predecessor with bidirectionally compatible data")
    parser.add_argument("--run", type=int, required=True)
    parser.add_argument("--attempt", type=int, required=True)
    parser.add_argument("--digest", required=True)
    parser.add_argument("--config", type=Path, default=Path.home()/".config/hookah-tootah/private/production/update.json")
    parser.add_argument("--server", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--machine", help=argparse.SUPPRESS)
    parser.add_argument("--operator-digest", help=argparse.SUPPRESS)
    args = parser.parse_args()
    os.umask(0o077)
    if args.server:
        return server(args)
    return mac(args, flags(args))


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (Refused, OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as exc:
        # Subprocess exceptions can contain private arguments. Only controlled refusals are printable.
        sys.exit("Production update stopped: " + (str(exc) if isinstance(exc, Refused) else type(exc).__name__))
