"""Regression tests for the shared host, using only fixture state."""
from contextlib import ExitStack, contextmanager, nullcontext
import fcntl
import hashlib
import http.client
import json
import os
import runpy
import signal
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import production_host as host
import production_profile as profile
import production_traffic as traffic
from simple_staging import LOCK, Refused
import media_backup as media


class SharedProductionTest(unittest.TestCase):
    def test_compiled_limits_networks_and_storage(self):
        from test_production_profile import ProductionProfileTest
        fixture = ProductionProfileTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        for enabled in (False, True):
            values = fixture.values.copy()
            if enabled:
                values[profile.MEDIA_FLAG] = "true"
                values.update({k: "synthetic" for k in profile.R2_CONNECTION_KEYS})
            plan = json.loads(profile.render(values, fixture.release))
            self.assertEqual(plan["name"], "hookah_production")
            services = plan["services"]
            self.assertEqual(host.plan_images(services), {fixture.release["image"], values["POSTGRES_IMAGE"]})
            with patch.dict(services["backend"], cgroup_parent="system.slice"), self.assertRaises(Refused):
                host.plan_images(services)
            self.assertTrue(all(item["name"] == "hookah_production_" + key
                                for section in ("volumes", "networks") for key, item in plan[section].items()))
            for service, config in plan["services"].items():
                memory, cpu = host.LIMITS[service]
                self.assertEqual(config["cgroup_parent"], host.SLICE)
                self.assertEqual(config["annotations"]["org.systemd.property.MemorySwapMax"], "uint64 0")
                self.assertEqual(float(config["cpus"]) * 1e9, cpu)
                self.assertEqual(int(config["mem_limit"]), memory * 1024**2)
                self.assertEqual(config["memswap_limit"], config["mem_limit"])
            self.assertEqual(plan["services"]["backend"]["ports"][0]["host_ip"], "127.0.0.1")
            self.assertEqual(str(plan["services"]["backend"]["ports"][0]["published"]), "18080")
            self.assertNotIn("ports", plan["services"]["postgres"])
            if enabled:
                self.assertEqual(plan["services"]["pdf-worker"]["network_mode"], "none")
            subprocess.run(["docker", "compose", "-f", "-", "config", "--quiet"], input=json.dumps(plan).encode(), check=True)
        unit = (profile.PROFILE / host.SLICE).read_text()
        self.assertIn("[Slice]\nCPUAccounting=yes\nCPUQuota=125%", unit)

    def test_verify_kernel_parent_and_live_limits(self):
        with patch.object(subprocess, "check_output", return_value="2/systemd"), patch.object(Path, "read_text") as read:
            for value in ("max 100000", "100000 100000", "125000 100000"):
                read.return_value = value
                if value.startswith("125"):
                    host.verify_slice()
                else:
                    with self.assertRaises(Refused):
                        host.verify_slice()
            cid = "a"*64
            cfg = dict(CgroupParent=host.SLICE, NanoCpus=500_000_000, Memory=512*host.MIB, MemorySwap=512*host.MIB)
            data = dict(Id=cid, State={"Pid": 123}, HostConfig=cfg)
            expected = [f"0::/{host.SLICE}/docker-{cid}.scope\n", "50000 100000", str(512*host.MIB), "0"]
            read.side_effect = expected
            host.verify_container("postgres", data)
            for index, value in ((1, "max 100000"), (2, "max"), (3, "1")):
                wrong = expected.copy()
                wrong[index] = value
                read.side_effect = wrong
                with self.assertRaises(Refused):
                    host.verify_container("postgres", data)
            read.side_effect = None
            for field in cfg:
                with self.subTest(field=field), patch.dict(cfg, {field: 0}), self.assertRaises(Refused):
                    host.verify_container("postgres", data)
            read.return_value = f"0::/system.slice/docker-{cid}.scope"
            with self.assertRaises(Refused):
                host.verify_container("postgres", data)

    def test_lock_order_busy_and_release(self):
        self.assertEqual(LOCK, Path("/run/lock/shared-staging-docker.lock"))
        with tempfile.TemporaryDirectory() as td:
            gate = Path(td) / "open"
            private = gate.with_name(".operator.lock")
            events = []
            @contextmanager
            def shared():
                events.append("shared")
                try:
                    yield -1, lambda: None
                finally:
                    with private.open("ab") as fd:
                        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    events.append("released")
            with patch.dict(os.environ, {"DOCKER_HOST": "unix:///var/run/docker.sock", "DOCKER_CONTEXT": ""}), \
                    patch.object(host, "shared_lock", side_effect=Refused("busy")), self.assertRaises(Refused):
                with host.operator_lock(gate):
                    self.fail("reached operation")
            self.assertFalse(private.exists())
            with patch.dict(os.environ, {"DOCKER_HOST": "unix:///var/run/docker.sock", "DOCKER_CONTEXT": ""}), \
                    patch.object(host, "shared_lock", shared):
                with host.operator_lock(gate), private.open("ab") as competitor:
                    events.append("operation")
                    with self.assertRaises(BlockingIOError):
                        fcntl.flock(competitor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertEqual(events, ["shared", "operation", "released"])

    def test_operator_commands_refuse_before_docker_on_busy_lock(self):
        actions = {"install": ["p"], "install-db": ["p"], "recovery-start": ["p"], "stage": ["old","new"],
                   "install-successor": ["p"], "successor-target": ["p","r"], "bind-successor": ["p","r","d","pin","sig"],
                   "resume": ["old","new"], "rollback": ["old","new"], "open": ["p"], "close": ["p"],
                   "start-closed": ["p","id"], "recovery-begin": ["p","pin"], "recovery-accept": ["p","r","pin"],
                   "recovery-telegram-reviewed": ["p","r","pin"]}
        with patch.object(host, "operator_lock", side_effect=Refused("busy")), patch.object(subprocess, "check_output") as docker:
            for action, args in actions.items():
                with self.subTest(action=action), patch.object(sys, "argv", ["operator", action, *args]), self.assertRaisesRegex(Refused, "busy"):
                    traffic.main()
            for args in (["admit","runtime","ci","p"], ["bind-db","p","key"]):
                with patch.object(sys, "argv", ["profile", *args]), self.assertRaisesRegex(Refused, "busy"):
                    profile.main()
            with self.assertRaisesRegex(Refused, "busy"):
                media.backup(Path("p"), Path("unused"), "key", False)
            docker.assert_not_called()

    def test_busy_close_warns_that_open_and_activate_are_still_effective(self):
        with tempfile.TemporaryDirectory() as td:
            gate = Path(td) / "open"
            activation = gate.with_name("activate")
            for marker in (gate, activation):
                marker.write_text("admitted instance")
            with patch.object(traffic, "GATE", gate), patch.object(host, "operator_lock", side_effect=Refused("busy")), \
                    patch.object(traffic, "operate") as operate, patch.object(sys, "argv", ["traffic", "close", "plan"]), \
                    self.assertRaisesRegex(Refused, "traffic may still be open.*Retry close.*HTTPS 503"):
                traffic.main()
            operate.assert_not_called()
            self.assertEqual([m.read_text() for m in (gate, activation)], ["admitted instance"] * 2)

    def test_bootstrap_checks_daemon_mountpoint_and_refuses_unregistered_data(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td).resolve()
            docker = root / "docker"
            pool = docker / "volumes"
            pool.mkdir(parents=True)
            proc = root / "proc"
            daemon = proc / "42"
            daemon.mkdir(parents=True)
            (daemon / "comm").write_text("dockerd")
            (daemon / "root").symlink_to("/")
            name = "hookah_production_pgdata"
            data = pool / name / "_data"
            with patch.object(host, "Path", side_effect=lambda p: proc if p == "/proc" else Path(p)), \
                    patch.object(subprocess, "check_output", side_effect=lambda cmd, **kw: str(docker) if cmd[0] == "docker" else "42"):
                self.assertTrue(host.local_volume_path(name, None).samefile(pool))
                data.mkdir(parents=True)
                with self.assertRaisesRegex(Refused, "unregistered"):
                    host.local_volume_path(name, None)
                self.assertTrue(host.local_volume_path(name, ["created", str(data)]).samefile(data))
                with self.assertRaisesRegex(Refused, "mountpoint"):
                    host.local_volume_path(name, ["created", str(pool)])

    def test_pre_pull_and_pre_dump_space_including_shared_filesystem(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            sizes = root / "sizes"
            with patch.object(host, "docker_storage", return_value=("overlayfs", [root, root], [])), \
                    patch.object(host, "cached_image_size", return_value=None) as cached, \
                    patch.object(shutil, "disk_usage", return_value=Mock(free=host.RESERVE + 2201)):
                with self.assertRaisesRegex(Refused, "missing"):
                    host.image_space(["digest"])
                sizes.write_text('{"digest":100}')
                with self.assertRaisesRegex(Refused, "packed/unpacked"):
                    host.image_space(["digest"], sizes)
                sizes.write_text('{"digest":{"driver":"overlayfs","platform":"linux/amd64","packed_bytes":100,"unpacked_bytes":1000}}')
                qualified = sizes.read_text()
                sizes.write_text(qualified.replace("linux/amd64", "linux/arm64"))
                with self.assertRaisesRegex(Refused, "qualified"):
                    host.image_space(["digest"], sizes)
                sizes.write_text(qualified)
                self.assertEqual(host.image_space(["digest"], sizes), {"digest"})
                with self.assertRaisesRegex(Refused, "insufficient"):
                    host.image_space(["digest"], sizes, [(root, 2)])
                cached.return_value = 200
                self.assertFalse(host.image_space(["digest"], allocations=[(root, 2201)]))
                with patch.object(shutil, "disk_usage", return_value=Mock(free=host.RESERVE - 1)), self.assertRaisesRegex(Refused, "insufficient"):
                    host.image_space(["digest"], sizes)
            with patch.object(shutil, "disk_usage", return_value=Mock(free=host.RESERVE + 25000)):
                media.backup_space(root, (root, 1024, 1024), {})
                with self.assertRaisesRegex(Refused, "insufficient"):
                    media.backup_space(root, (root, 1024, 1024), {"object": (16384,)})

    def test_classic_pull_measures_daemon_tmp_mount_before_download(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            tempfs = root / "custom-temp"
            tempfs.mkdir()
            sizes = root / "sizes"
            sizes.write_text('{"pg":{"platform":"linux/amd64","driver":"overlay2","packed_bytes":100,"unpacked_bytes":900}}')
            with patch.object(host, "docker_storage", return_value=("overlay2", [root], [])), \
                    patch.object(host, "cached_image_size", return_value=None), patch.object(host, "classic_pull_tmp", return_value=tempfs) as tmp, \
                    patch.object(host, "check_space") as space:
                host.image_space(["pg"], sizes)
                tmp.assert_called_once_with(root)
                space.assert_called_once_with([(tempfs, 2000)])

    def test_independent_content_and_snapshot_devices_each_keep_the_reserve(self):
        content, snapshots = Mock(spec=Path), Mock(spec=Path)
        content.stat.return_value.st_dev = 4
        snapshots.stat.return_value.st_dev = 8
        with tempfile.TemporaryDirectory() as td:
            sizes = Path(td) / "bounds"
            sizes.write_text('{"ref":{"driver":"overlayfs","platform":"linux/amd64","packed_bytes":100,"unpacked_bytes":1000}}')
            with patch.object(host, "docker_storage", return_value=("overlayfs", [content, snapshots], [])), \
                    patch.object(host, "cached_image_size", return_value=None), \
                    patch.object(shutil, "disk_usage", side_effect=lambda p: Mock(free=host.RESERVE + (10_000 if p is content else 2199))):
                with self.assertRaisesRegex(Refused, "insufficient"):
                    host.image_space(["ref"], sizes)

    def test_shared_lock_rejects_wrong_mode_link_or_replaced_inode(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            path = root / "shared"
            path.touch(mode=0o600)
            with self.assertRaisesRegex(Refused, "permissions"):
                with host.shared_lock(path, 0):
                    self.fail("accepted invalid mode")
            path.chmod(0o660)
            link = root / "symlink"
            link.symlink_to(path)
            with self.assertRaises(OSError):
                with host.shared_lock(link, 0):
                    self.fail("accepted symlink")
            actual = os.fstat
            def provisioned(fd):
                fields = list(actual(fd))
                fields[4] = 0
                return os.stat_result(fields)
            with patch.object(os, "fstat", provisioned), patch.object(os, "listxattr", return_value=[], create=True):
                os.link(path, root / "hardlink")
                with self.assertRaisesRegex(Refused, "permissions"):
                    with host.shared_lock(path, 0):
                        self.fail("accepted hard link")
                (root / "hardlink").unlink()
                with self.assertRaisesRegex(Refused, "identity"):
                    with host.shared_lock(path, 0):
                        path.rename(root / "displaced")
                        path.touch(mode=0o600)

    def test_snapshotter_uses_live_plugin_roots_and_requires_unpacked_chain(self):
        with tempfile.TemporaryDirectory() as td, socket.socket(socket.AF_UNIX) as sock:
            root = Path(td)
            endpoint = root / "ctr.sock"
            sock.bind(str(endpoint))
            content = root / "store"
            snapshots = root / "overlay"
            content.mkdir()
            snapshots.mkdir()
            info = "\n".join(["overlayfs", td, '[["driver-type","io.containerd.snapshotter.v1"]]',
                              json.dumps({"Address": str(endpoint), "Namespaces": {"Containers": "moby"}})])
            with patch.object(subprocess, "check_output", side_effect=[
                    info, "Exports:\n  root  " + str(content), "Exports:\n  root  " + str(snapshots)]) as engine:
                driver, paths, ctr = host.docker_storage()
                self.assertEqual((driver, paths), ("overlayfs", [root, content, snapshots]))
                self.assertIn(str(endpoint), ctr)
                self.assertIn("type==io.containerd.snapshotter.v1,id==overlayfs", engine.call_args.args[0])
            with patch.object(subprocess, "check_output", side_effect=[info, "Exports:"]), self.assertRaisesRegex(Refused, "root"):
                host.docker_storage()
            ref = "postgres@sha256:" + "a"*64
            image = {"Os": "linux", "Architecture": "amd64", "RepoDigests": [ref],
                     "RootFS": {"Layers": ["sha256:" + "b"*64, "sha256:" + "c"*64]}, "Size": 41}
            inspect = Mock(returncode=0, stdout=json.dumps([image]))
            for code, expected in ((1, None), (0, 41)):
                with patch.object(subprocess, "run", side_effect=[inspect, Mock(returncode=code)]) as run:
                    self.assertEqual(host.cached_image_size(ref, ctr), expected)
                    self.assertIn("snapshots", run.call_args.args[0])
                    self.assertNotIn(run.call_args.args[0][-1], image["RootFS"]["Layers"])

    def test_pull_rechecks_actual_filesystems_before_another_pull_or_image_probe(self):
        with patch.object(host, "verify_slice"), patch.object(host, "lock_args", return_value={"pass_fds": (9, 10)}), \
                patch.object(host, "image_space", side_effect=[{"pg", "app"}, Refused("insufficient after pull")]) as check, \
                patch.object(subprocess, "run") as run, patch.object(subprocess, "check_output") as probe:
            with self.assertRaisesRegex(Refused, "after pull"):
                profile.verify_images({"POSTGRES_IMAGE": "pg"}, {"image": "app"})
            self.assertEqual(check.call_count, 2)
            run.assert_called_once_with(["docker", "pull", "--quiet", "--platform", "linux/amd64", "pg"],
                                        check=True, pass_fds=(9, 10))
            probe.assert_not_called()

    def test_cached_restart_requires_floor_but_not_release_growth(self):
        with tempfile.TemporaryDirectory() as td:
            plan = Path(td) / "release"
            plan.write_text('{"services":{}}')
            path = Path(td)
            for action, args, release in (("start-closed", [str(plan), "id"], False),
                                         ("resume", [str(plan), str(plan)], False),
                                         ("stage", [str(plan), str(plan)], True)):
                with patch.object(sys, "argv", ["production_traffic", action, *args]), \
                        patch.object(host, "operator_lock", return_value=nullcontext()), patch.object(host, "verify_slice"), \
                        patch.object(traffic, "verified_database", return_value="pg"), \
                        patch.object(host, "database_space", return_value=(path, 100, 50)), \
                        patch.object(host, "plan_images", return_value={"ref"}), patch.object(host, "image_space") as check, \
                        patch.object(traffic, "operate") as operate:
                    traffic.main()
                    check.assert_called_once_with({"ref"}, allocations=[(path, 150 if release else 0)], reserve_release=release)
                    operate.assert_called_once()

    def test_underestimated_dump_cannot_consume_the_reserved_disk(self):
        with tempfile.TemporaryDirectory() as td:
            output = Path(td) / "partial.age"
            child = subprocess.run([sys.executable, "-c", "import pathlib,sys; pathlib.Path(sys.argv[1]).write_bytes(bytes(8192))", output],
                                   preexec_fn=media.file_limit(1024), capture_output=True)
            self.assertNotEqual(child.returncode, 0)
            self.assertLessEqual(output.stat().st_size, 1024)

    def install_fixture(self):
        from test_production_profile import ProductionProfileTest
        fixture = ProductionProfileTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        root = fixture.folder
        values = dict(fixture.values, PRODUCTION_MEDIA_ENABLED="true", STAFF_PHOTOS_R2_REGION="auto",
                      STAFF_PHOTOS_R2_JURISDICTION="eu", STAFF_PHOTOS_R2_BUCKET=profile.PRODUCTION_BUCKET,
                      STAFF_PHOTOS_R2_ENDPOINT="https://" + "d"*32 + ".eu.r2.cloudflarestorage.com",
                      STAFF_PHOTOS_R2_ACCESS_KEY="a"*32, STAFF_PHOTOS_R2_SECRET_KEY="b"*64)
        doc = json.loads(profile.render(values, fixture.release))
        doc["x-hookah-schema"] = 143
        doc["x-hookah-release-evidence"] = {"candidate": fixture.release}
        plan = root / "plan"
        original = json.dumps(doc).encode()
        profile.publish_plan(plan, original)
        budget = root / "pgdata-budget.json"
        qualification = dict(platform="linux/amd64", postgres=values["POSTGRES_IMAGE"], backend=fixture.release["image"],
                             qualified_capacity_bytes=600)
        (root / "ingress").mkdir()
        model = root / "engine"
        model.write_text("{}")

        def operator(action, *args, fault="", success=True):
            result = subprocess.run([sys.executable, "-B", __file__, "install-fixture", str(root), action, fault, *args],
                                    capture_output=True, text=True, timeout=30)
            if success:
                self.assertEqual(result.returncode, 0, result.stderr)
            else:
                self.assertNotEqual(result.returncode, 0)
            return result

        return root, plan, doc, original, budget, qualification, model, operator

    def test_successor_install_records_pristine_identity_before_backend(self):
        root, _, _, _, budget, qualification, model, op = self.install_fixture()
        budget.write_text(json.dumps(qualification))
        self.assertIn("historical production", op("install-successor", fault="legacy-active", success=False).stderr)
        self.assertFalse((root / "mutations").exists())
        self.assertIn("unexpected data", op("install-successor", fault="dirty", success=False).stderr)
        self.assertEqual(json.loads(model.read_bytes()), {"postgres": True})
        self.assertNotIn("pristine", json.loads((root / "ingress/.install-intent").read_bytes()))
        self.assertIn("different pending", op("install", success=False).stderr)
        self.assertIn("cannot be converted", op("recovery-begin", success=False).stderr)
        op("install-successor")
        intent = json.loads((root / "ingress/.install-intent").read_bytes())
        self.assertEqual(intent["pristine"]["container"], intent["resources"]["postgres"])
        self.assertEqual(set(json.loads(model.read_bytes())), {"postgres", "backend", "pdf-worker"})
        self.assertIn("identity changed", op("install-successor", fault="other-db", success=False).stderr)

    def test_successor_preserves_inactive_predecessor_and_refuses_restartable_container(self):
        cid = "9"*64
        for state in ("exited no", "running no", "exited always", "paused unless-stopped"):
            with self.subTest(state=state), patch.object(subprocess, "check_output", side_effect=[cid, cid, state]) as cli:
                if state == "exited no":
                    traffic.check_retired_project()
                else:
                    with self.assertRaisesRegex(Refused, "historical production"):
                        traffic.check_retired_project()
                self.assertEqual([c.args[0][1] for c in cli.call_args_list], ["ps", "ps", "inspect"])

    def test_successor_binding_requires_owner_pin_custodian_signature_and_exact_live_target(self):
        root, plan, _, _, budget, qualification, _, op = self.install_fixture()
        budget.write_text(json.dumps(qualification))
        op("install-successor")
        key = root / "test-custodian"
        subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key], check=True)
        age = root / "test-age"
        subprocess.run(["age-keygen", "-o", age], stderr=subprocess.DEVNULL, check=True)
        recipient = subprocess.check_output(["age-keygen", "-y", age], text=True).strip()
        target = json.loads(op("successor-target", recipient).stdout)
        review = {"target": target, "owner": "fixture owner", "custodian": "fixture custodian",
                  "custodian_key": " ".join(key.with_suffix(".pub").read_text().split()[:2]),
                  "history": "Synthetic experimental installation was retired",
                  "retirement_evidence_sha256": hashlib.sha256(b"fixture retirement evidence").hexdigest(),
                  "predecessor": "hookah-production", "data_disposition": "RETAIN_ARCHIVES_AND_RECEIPTS_WITHOUT_RESTORE",
                  "decision": "RETIRE_AND_ENROLL_CLEAN_SUCCESSOR"}
        decision = root / "retirement.json"
        signature = root / "retirement.json.sig"
        def write_decision(document):
            decision.write_text(json.dumps(document))
            decision.chmod(0o600)
            return hashlib.sha256(decision.read_bytes()).hexdigest()

        def bind(pin, **kw):
            return op("bind-successor", recipient, decision, pin, signature, **kw)

        pin = write_decision(review)
        self.assertIn("custodian signature file required", bind(pin, success=False).stderr)
        subprocess.run(["ssh-keygen", "-Y", "sign", "-f", key, "-n", "hookah-tootah-clean-successor-v1", decision],
                       check=True, stderr=subprocess.DEVNULL)
        outsider = root / "other-signer"
        subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", outsider], check=True)
        bad_sig = root / "other.sig"
        signed = subprocess.check_output(["ssh-keygen", "-Y", "sign", "-f", outsider, "-n", "hookah-tootah-clean-successor-v1"],
                                         input=decision.read_bytes(), stderr=subprocess.DEVNULL)
        bad_sig.write_bytes(signed)
        self.assertIn("verify", op("bind-successor", recipient, decision, pin, bad_sig, success=False).stderr)
        self.assertIn("approved digest", bind("0"*64, success=False).stderr)
        duplicate = '{"owner":"another owner",' + decision.read_text()[1:]
        decision.write_text(duplicate)
        self.assertIn("duplicate field", bind(hashlib.sha256(decision.read_bytes()).hexdigest(), success=False).stderr)
        for field in ("history", "custodian", "retirement_evidence_sha256", "data_disposition"):
            with self.subTest(field=field):
                self.assertIn("incomplete retirement", bind(write_decision(dict(review, **{field: ""})), success=False).stderr)
        self.assertIn("different successor", bind(write_decision(dict(review, target=dict(target, recipient="other"))),
                                                  success=False).stderr)
        self.assertIn("verify", bind(write_decision(dict(review, history="changed after signature")), success=False).stderr)
        self.assertEqual(write_decision(review), pin)
        for marker in ("open", "activate", "recovery-hold", "recovery-accepted"):
            latch = root / "ingress" / marker
            latch.touch()
            self.assertIn("conflicting production decisions", bind(pin, success=False).stderr)
            self.assertTrue(latch.exists())
            latch.unlink()
        for fault, message in (("legacy-active", "historical production"), ("other-db", "freshly initialized"),
                               ("dirty", "no longer clean"), ("bad-edge", "edge closed")):
            self.assertIn(message, bind(pin, fault=fault, success=False).stderr)
        binding = root / "postgres-binding.json"
        self.assertFalse(binding.exists())
        self.assertEqual(bind(pin, fault="kill-before-binding", success=False).returncode, -signal.SIGKILL)
        self.assertFalse(binding.exists())
        self.assertEqual(bind(pin, fault="kill-after-binding", success=False).returncode, -signal.SIGKILL)
        published = binding.stat().st_ino
        with patch.object(profile, "GATE", root / "ingress/open"), self.assertRaisesRegex(Refused, "completed successor"):
            profile.verified_database(plan)
        bind(pin)
        self.assertEqual(binding.stat().st_ino, published)
        self.assertEqual(json.loads(binding.read_bytes()), dict(target["database"], recipient=recipient, successor_review_sha256=pin))
        self.assertEqual(json.loads((root / "ingress/.install-intent").read_bytes())["retirement_review_sha256"], pin)
        before = binding.read_bytes()
        self.assertIn("already enrolled", bind(pin, success=False).stderr)
        self.assertEqual(binding.read_bytes(), before)
        with patch.object(profile, "GATE", root / "ingress/open"), patch.object(profile, "database_identity", return_value=target["database"]):
            self.assertEqual(profile.verified_database(plan, recipient), target["database"]["container"])
            intent = root / "ingress/.install-intent"
            intent.rename(root / "custody-copy")
            with self.assertRaisesRegex(Refused, "missing successor"):
                profile.verified_database(plan)
            (root / "custody-copy").rename(intent)
            with self.assertRaisesRegex(Refused, "signed enrollment"):
                profile.enroll_database(plan, recipient, first_install=True)
            binding.write_text(json.dumps(dict(target["database"], recipient="different")))
            with self.assertRaisesRegex(Refused, "completed successor"):
                profile.verified_database(plan)
        binding.unlink()  # Loss of the fixture binding cannot be repaired by replaying approval.
        self.assertIn("already enrolled", bind(pin, success=False).stderr)
        self.assertFalse((root / "ingress/open").exists())

    def test_recovery_begin_retries_killed_hold_publications_without_using_foreign_files(self):
        root, plan, _, _, budget, qualification, _, operator = self.install_fixture()
        budget.write_text(json.dumps(qualification))
        operator("install-db")
        hold = root / "ingress/recovery-hold"
        foreign = root / "do-not-touch"
        foreign.mkdir(mode=0o700)
        scratch = hold.with_name(".recovery-write")
        scratch.symlink_to(foreign)
        self.assertIn("untrusted recovery write directory", operator("recovery-begin", success=False).stderr)
        self.assertEqual(list(foreign.iterdir()), [])
        scratch.unlink()
        self.assertEqual(operator("recovery-begin", fault="kill-first-hold", success=False).returncode, -signal.SIGKILL)
        self.assertFalse(hold.exists())
        abandoned, = scratch.iterdir()
        abandoned.write_text("foreign bytes")
        self.assertEqual(operator("recovery-begin", fault="kill-final-hold", success=False).returncode, -signal.SIGKILL)
        self.assertEqual(set(json.loads(hold.read_bytes())), {"source_manifest"})
        operator("recovery-begin")
        self.assertEqual(json.loads(hold.read_bytes())["target"], {"container": "1"*64, "cluster_and_db": "17|42"})
        self.assertEqual(abandoned.read_text(), "foreign bytes")
        unidentified = hold.with_name("tmp-unidentified")
        unidentified.touch()
        self.assertIn("conflicting production decisions", operator("recovery-start", success=False).stderr)
        unidentified.unlink()
        operator("recovery-start")
        self.assertFalse(hold.with_name("open").exists())

    def test_install_cli_retries_or_stops_its_partial_project_after_worker_failure_and_sigkill(self):
        root, plan, doc, original, budget, qualification, model, operator = self.install_fixture()
        doc["services"]["postgres"]["image"] = "postgres@sha256:" + "f"*64
        plan.write_text(json.dumps(doc))
        self.assertIn("admission receipt", operator("install", success=False).stderr)
        plan.write_bytes(original)
        self.assertIn("PostgreSQL image identity", operator("install", fault="wrong-pg", success=False).stderr)
        self.assertEqual(model.read_text(), "{}")
        (root / "ingress/open").touch()
        self.assertIn("close production ingress", operator("install", success=False).stderr)
        (root / "ingress/open").unlink()
        self.assertIn("unowned production volume", operator("install", fault="collision", success=False).stderr)

        self.assertIn("missing PostgreSQL bootstrap disk qualification", operator("install", success=False).stderr)
        budget.write_text(json.dumps(dict(qualification, postgres="wrong")))
        self.assertIn("another release", operator("install-db", success=False).stderr)
        budget.write_text(json.dumps(qualification))
        self.assertIn("insufficient space", operator("install", fault="full-volume", success=False).stderr)
        self.assertFalse((root / "mutations").exists())
        self.assertFalse((root / "ingress/.install-intent").exists())

        killed = operator("install", fault="kill-after-pg", success=False)
        self.assertEqual(killed.returncode, -signal.SIGKILL)
        self.assertEqual(json.loads(model.read_text()), {"postgres": True})
        self.assertIn("worker unhealthy", operator("install", fault="unhealthy", success=False).stderr)
        self.assertEqual(json.loads(model.read_text()), {"postgres": True, "pdf-worker": True})
        self.assertIn("partial container image", operator("close", fault="wrong-container", success=False).stderr)
        operator("close")
        stopped = json.loads(model.read_text())
        self.assertEqual(stopped, {"postgres": False, "pdf-worker": False})
        operator("close")
        before = (root / "mutations").read_bytes()
        self.assertIn("network attachments differ from plan: postgres",
                      operator("install", fault="extra-pg-network", success=False).stderr)
        self.assertEqual((root / "mutations").read_bytes(), before)
        self.assertIn("partial installation resource replaced", operator("install", fault="replaced-volume", success=False).stderr)
        self.assertEqual(json.loads(model.read_text()), stopped)
        operator("install")
        self.assertEqual(json.loads(model.read_text()), {"postgres": True, "pdf-worker": True, "backend": True})
        operator("install")
        self.assertFalse((root / "ingress/open").exists())
        (root / "postgres-binding.json").touch()
        self.assertIn("already enrolled", operator("install", success=False).stderr)

    def test_mutating_cli_keeps_both_locks_after_operator_sigkill(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            common = root / "shared.lock"
            common.touch(mode=0o660)
            common.chmod(0o660)
            # Stand-in Docker CLI waits for permission to finish a long mutating command.
            fake = root / "docker"
            fake.write_text("#!" + sys.executable + "\nimport pathlib,time,sys\nr=pathlib.Path(__file__).parent\n"
                            "assert sys.argv[-2:]==['stop','backend']\n(r/'started').touch()\n"
                            "while not (r/'finish').exists(): time.sleep(.02)\n")
            fake.chmod(0o700)
            env = dict(os.environ, PATH=td + os.pathsep + os.environ["PATH"],
                       DOCKER_HOST="unix:///var/run/docker.sock", DOCKER_CONTEXT="")
            parent = subprocess.Popen([sys.executable, "-B", __file__, "lease-fixture", td], env=env, stderr=subprocess.PIPE)
            def await_condition(predicate):
                until = time.monotonic() + 10
                while not predicate():
                    if time.monotonic() > until:
                        self.fail("timed out waiting for lock fixture")
                    time.sleep(.02)
            with parent:
                try:
                    await_condition(lambda: (root / "started").exists() or parent.poll() is not None)
                    self.assertIsNone(parent.poll())
                    parent.kill()
                    parent.wait(timeout=5)
                    for path in (common, root / ".operator.lock"):
                        with path.open("rb") as other, self.assertRaises(BlockingIOError):
                            fcntl.flock(other, fcntl.LOCK_EX | fcntl.LOCK_NB)
                finally:
                    (root / "finish").touch()
                    if parent.poll() is None:
                        parent.kill()
            def available():
                with common.open("rb") as first, (root / ".operator.lock").open("rb") as second:
                    try:
                        for fd in (first, second):
                            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                        return True
                    except BlockingIOError:
                        return False
            await_condition(available)
            with self.assertRaisesRegex(Refused, "requires"):
                traffic.compose(root / "plan", "stop", "backend")

    def test_first_launch_rehearsal_refuses_deployed_namespaces_before_build(self):
        script = Path(__file__).with_name("test-production-first-launch.py")
        with patch.object(subprocess, "check_output") as docker, tempfile.TemporaryDirectory() as td, \
                patch.dict(os.environ, DOCKER_HOST="unix:///var/run/docker.sock", DOCKER_CONTEXT=""), \
                patch.object(host, "GATE", Path(td) / "ingress/open"), \
                patch.object(sys, "argv", [str(script), "backend", "postgres", "caddy"]):
            for rows in ((b"hookah-bot", b"", b""), (b"", b"old_data", b""), (b"", b"", b"clubs-net")):
                docker.side_effect = rows
                with self.assertRaisesRegex(ValueError, "isolated"):
                    runpy.run_path(str(script), run_name="__main__")
                self.assertEqual([call.args[0][1] for call in docker.call_args_list],
                                 ["ps", "volume", "network"][:next(i for i, row in enumerate(rows, 1) if row)])
                docker.reset_mock()
            host.GATE.parent.mkdir()
            with self.assertRaisesRegex(ValueError, "decision directory"):
                runpy.run_path(str(script), run_name="__main__")
            docker.assert_not_called()

    def test_restore_refuses_a_shared_daemon_before_cleanup(self):
        import restore_test
        with tempfile.TemporaryDirectory() as td, patch.dict(os.environ, {"XDG_STATE_HOME": td}), \
                patch.object(sys, "argv", ["restore", "point", "identity", "image"]), \
                patch.object(restore_test, "cleanup") as cleanup:
            for kind, name in (("ps", "hookah-bot"), ("ps", "unlabelled"), ("ps", "clubs-bot-stage"),
                               ("volume", "orphan_pgdata"), ("network", "custom-net")):
                def inventory(*cmd):
                    if cmd[0] == "info":
                        return "daemon"
                    self.assertIn(cmd[0], ("ps", "volume", "network"))
                    return name if cmd[0] == kind else ""
                with self.subTest(name=name), patch.object(restore_test, "docker", side_effect=inventory), \
                        self.assertRaisesRegex(Refused, "isolated restore daemon"):
                    restore_test.main()
            cleanup.assert_not_called()

    def test_restore_cleanup_accepts_only_resources_of_the_journaled_attempt(self):
        import restore_test
        attempt = "b" * 32
        image = "postgres@sha256:" + "c" * 64
        cid = "e" * 64
        name = "ht-restore-" + attempt
        volume = name + "-data"
        with tempfile.TemporaryDirectory() as td:
            journal = Path(td) / "attempt.json"
            def exercise(extra_network=False, owner=attempt):
                journal.write_text(json.dumps(dict(attempt=attempt, image=image, daemon="this-daemon")))
                resources = {name, volume}
                mutations = []
                def daemon(*cmd):
                    if cmd[:2] == ("network", "ls"):
                        return "foreign" if extra_network else ""
                    if cmd == ("ps", "-a", "--format", "{{.Names}}"):
                        return name if name in resources else ""
                    if cmd[:2] == ("volume", "ls"):
                        return volume if volume in resources else ""
                    if cmd[0] == "ps":
                        return cid if name in resources else ""
                    if cmd[:2] == ("volume", "inspect"):
                        return json.dumps([{"Name": volume, "Driver": "local", "Labels": {restore_test.LABEL: owner}}])
                    if cmd == ("inspect", cid):
                        return json.dumps([{"Id": cid, "Name": "/" + name,
                            "Config": {"Image": image, "Labels": {restore_test.LABEL: owner}},
                            "HostConfig": {"NetworkMode": "none", "PortBindings": {}},
                            "Mounts": [{"Type": "volume", "Name": volume, "Destination": "/var/lib/postgresql/data"}]}])
                    if cmd == ("rm", "-f", cid):
                        resources.remove(name)
                    elif cmd == ("volume", "rm", volume):
                        resources.remove(volume)
                    else:
                        self.fail(cmd)
                    mutations.append(cmd)
                    return ""
                with patch.object(restore_test, "docker", side_effect=daemon):
                    if extra_network or owner != attempt:
                        with self.assertRaisesRegex(Refused, "isolated restore|unrecognized restore volume"):
                            restore_test.cleanup(journal, "this-daemon")
                        self.assertEqual(mutations, [])
                        self.assertTrue(journal.exists())
                    else:
                        restore_test.cleanup(journal, "this-daemon")
                        self.assertEqual(mutations, [("rm", "-f", cid), ("volume", "rm", volume)])
                        self.assertFalse(journal.exists())
            exercise(extra_network=True)
            exercise(owner="unknown")
            exercise()

    def test_recovery_readmission_example_supplies_both_disk_and_provenance_evidence(self):
        import shlex
        runbook = (profile.ROOT / "docs/PRODUCTION_PILOT.md").read_text().replace("\\\n", " ")
        command, = (line for line in runbook.splitlines() if '"$NEW_PLAN" --evidence' in line)
        args = shlex.split(command)
        for flag, value in (("--image-sizes", "$P/image-sizes.json"), ("--evidence", "$evidence"),
                            ("--evidence-sha256", "$EVIDENCE_SHA256")):
            self.assertEqual(args[args.index(flag) + 1], value)

    def test_cold_backup_publishes_only_a_complete_bound_restore_point(self):
        import restore_test as recovery
        ref = "postgres@sha256:" + "a" * 64
        binding = ("pg", "backend", "true")
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            plan = root / "plan"
            plan.write_text(json.dumps({"x-hookah-schema": 143, "services": {"backend": {"environment": {}}}}))
            def capture(cid, env, point, recipient, media_enabled, metadata_only, *, storage):
                self.assertEqual((cid, media_enabled, metadata_only, storage), ("pg", True, False, (root, 1024, 512)))
                for file in ("database.dump.age", "media.tar.age"):
                    (point / file).write_bytes(b"fixture cipher " + file.encode())
            def output(command, **kwargs):
                return (ref + "\n").encode() if command[:2] == ["docker", "inspect"] else b""
            with patch.object(host, "GATE", root / "open"), patch.object(host, "operator_lock", return_value=nullcontext()), patch.object(host, "lock_args", return_value={}), \
                    patch.object(host, "database_space", return_value=(root, 1024, 512)), \
                    patch.object(media, "backup_target", return_value=binding) as target, \
                    patch.object(media, "capture", side_effect=capture), patch.object(profile, "pg_query", return_value="10|20|1|0/20"), \
                    patch.object(subprocess, "check_output", side_effect=output):
                media.backup(plan, root/"good", "recipient", False)
                recovery.verify_archive(root/"good", ref)
                target.side_effect = [binding, binding, ("foreign", "backend", "true")]
                with self.assertRaisesRegex(Refused, "changed"):
                    media.backup(plan, root/"partial", "recipient", False)
                self.assertFalse((root/"partial/SHA256SUMS").exists())

    def test_shared_caddy_preserves_adjacent_hosts_and_closes_only_apex(self):
        # Use the real Caddy adapter and live HTTP Host routing on an ephemeral loopback port.
        with tempfile.TemporaryDirectory() as td, socket.socket() as sock:
            root = Path(td)
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
            sock.close()
            apex = "hookahtootah.club"
            neighbours = {"staging."+apex: "hookah", "stage.nightconcierge.help": "clubs", "*."+apex: "wildcard"}
            main = root / "Caddyfile"
            main.write_text("{\n admin off\n auto_https off\n}\n" +
                            "".join(f'http://{name}:{port} {{\n bind 127.0.0.1\n respond "{value}"\n}}\n' for name,value in neighbours.items()))
            site = (profile.PROFILE / "Caddyfile").read_text()
            self.assertEqual(site.splitlines()[0], apex + " {")
            self.assertIn("reverse_proxy 127.0.0.1:18080", site)
            (root / "production.caddy").write_text(site.replace(apex+" {",f"http://{apex}:{port} {{\n bind 127.0.0.1",1)
                                                   .replace("/var/lib/hookah-production/ingress", str(root)))
            with main.open("a") as add:
                add.write("import production.caddy\n")
            env = dict(os.environ, HOME=td, XDG_CONFIG_HOME=td, XDG_DATA_HOME=td)
            # HTTP fixtures need no provider credentials, DNS or CA.
            with subprocess.Popen(["caddy", "run", "--config", str(main)], env=env, cwd=root,
                                  stdout=subprocess.DEVNULL, stderr=subprocess.PIPE) as edge:
                try:
                    for _ in range(150):
                        if edge.poll() is not None:
                            self.fail(edge.stderr.read().decode())
                        try:
                            with socket.create_connection(("127.0.0.1", port), timeout=.1):
                                break
                        except OSError:
                            time.sleep(.02)
                    for hostname, body in list(neighbours.items()) + [(apex, None)]:
                        client = http.client.HTTPConnection("127.0.0.1", port, timeout=2)
                        try:
                            client.request("GET", "/miniapp/", headers={"Host": hostname.replace("*", "guest")})
                            response = client.getresponse()
                            self.assertEqual(response.status, 503 if body is None else 200)
                            if body is None:
                                self.assertEqual(response.getheader("X-Hookah-Maintenance"), "closed")
                            else:
                                self.assertEqual(response.read().decode(), body)
                        finally:
                            client.close()
                finally:
                    if edge.poll() is None:
                        edge.terminate()


def run_install_fixture(root, action, fault, *cli_args):
    """Exercise argparse → operator → inventory/stop/up against a persistent synthetic Docker Engine."""
    plan = root / "plan"
    doc = json.loads(plan.read_bytes())
    project = traffic.PRODUCTION_PROJECT
    model = root / "engine"
    services = json.loads(model.read_text())
    ids = {name: str(i + 1) * 64 for i, name in enumerate(("postgres", "pdf-worker", "backend"))}
    orig_run = subprocess.run

    def persist():
        with (root / "mutations").open("a") as trace:
            trace.write("mutated\n")
        model.write_text(json.dumps(services))

    def image(ref):
        return {"Id": ref.split("@")[1], "Os": "linux", "Architecture": "amd64", "RepoDigests": [ref]}

    def container(service):
        cfg = doc["services"][service]
        labels = {"project": project, "service": service, "container-number": "1", "oneoff": "False",
                  "project.working_dir": str(root.resolve()), "project.config_files": str(plan.resolve()), "config-hash": service + "-hash"}
        mounts = []
        for m in cfg.get("volumes", []):
            if m["type"] in ("bind", "volume"):
                mounts.append({"Type": m["type"], "Destination": m["target"], "Source": m["source"],
                               "Name": project + "_" + m["source"], "RW": not m.get("read_only", False)})
        memory, cpu = host.LIMITS[service]
        sandbox = {"PortBindings": {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "18080"}]} if service == "backend" else {},
                   "CgroupParent": host.SLICE, "Memory": memory * host.MIB, "MemorySwap": memory * host.MIB,
                   "NanoCpus": cpu, "NetworkMode": "none" if service == "pdf-worker" else project + "_database"}
        networks = {"none": {}} if service == "pdf-worker" else {doc["networks"][n]["name"]: {} for n in cfg["networks"]}
        if fault == "extra-pg-network" and service == "postgres":
            networks["foreign"] = {}
        return {"Id": ids[service], "HostConfig": sandbox, "NetworkSettings": {"Networks": networks}, "Name": f"/{project}-{service}-1", "State": {"Running": services[service]},
                "Config": {"Image": cfg["image"], "Labels": {"com.docker.compose." + k: v for k, v in labels.items()}},
                "Image": "foreign" if fault == "wrong-container" else image(cfg["image"])["Id"], "Mounts": mounts}

    def output(cmd, **options):
        if cmd[:2] == ["docker", "compose"]:
            if "--format" in cmd and cmd[-1] == "json":  # Real Compose, config-only.
                return orig_run(cmd, check=True, stdout=subprocess.PIPE, **options).stdout
            if "ps" in cmd:
                return ids["backend"] if "backend" in services else ""
            if "--hash" in cmd:
                return "\n".join(s + " " + s + "-hash" for s in doc["services"])
            assert cmd[cmd.index("-p")+1] == project and cmd[cmd.index("-f")+1] == str(plan)
            service = cmd[-1]
            assert "up" in cmd and "--no-deps" in cmd and "--no-recreate" in cmd and "--pull" in cmd
            assert service in ids
            services[service] = True
            persist()
            if fault == "kill-after-pg" and service == "postgres":
                os.kill(os.getpid(), signal.SIGKILL)
            if fault == "unhealthy" and service == "pdf-worker":
                raise Refused("worker unhealthy")
            return ""
        if cmd[:2] == ["docker", "info"]:
            return "fixture-daemon"
        if cmd[0] == "ss":
            return b""
        if cmd[:3] == ["docker", "image", "inspect"]:
            data = image(cmd[-1])
            if fault == "wrong-pg":
                data["RepoDigests"] = ["postgres@sha256:" + "f"*64]
            return json.dumps([data])
        if cmd[:2] == ["docker", "ps"]:
            if cmd[-1] in ("label=com.docker.compose.project=hookah-production", "name=^/hookah-production([-_].*|$)"):
                return "e"*64 if fault == "legacy-active" else ""
            if "--format" in cmd:
                return "\n".join([ids[s] + " " + project + "-" + s + "-1" for s in services] +
                                  ["a" * 64 + " hookah-bot-backend-1", "f" * 64 + " clubs-bot-stage-backend-1"])
            if cmd[-1].startswith("volume="):
                return ids["postgres"] if "postgres" in services and cmd[-1].endswith("_pgdata") else ""
            return "\n".join(ids[s] for s in services)
        if cmd[:2] == ["docker", "inspect"]:
            if "--format" in cmd and cmd[-1] == "e"*64:
                return "running unless-stopped"
            service, = [s for s in services if ids[s] == cmd[-1]]
            return json.dumps([container(service)])
        if cmd[1] in ("volume", "network"):
            kind = cmd[1]
            resources = {v["name"]: k for k, v in doc[kind + "s"].items()} if services else {}
            if cmd[2] == "ls":
                names = list(resources)
                if "--filter" not in cmd:
                    names.extend(["hookah-bot_pgdata", "clubs-bot-stage_pgdata"])
                    if kind == "volume" and fault == "collision":
                        names.append(project + "_pgdata")
                return "\n".join(names)
            assert cmd[2] == "inspect"
            name = cmd[-1]
            key = resources[name]
            spec = doc[kind + "s"][key]
            data = {"Name": name, "Labels": {"com.docker.compose.project": project, "com.docker.compose." + kind: key},
                    "Options": spec.get("driver_opts"), "Driver": "local" if kind == "volume" else "bridge",
                    "Id": name, "CreatedAt": "new" if fault == "replaced-volume" else "old", "Mountpoint": "/fixture/" + name,
                    "Internal": spec.get("internal", False), "Containers": {}}
            return json.dumps([data])
        raise AssertionError("unhandled fixture operation: " + str(cmd[:3]))

    def stop(cmd, **options):
        if cmd[0] in ("age", "ssh-keygen"):
            return orig_run(cmd, **options)
        assert cmd[:2] == ["docker", "stop"] and len(cmd) == 3
        service, = [s for s in services if ids[s] == cmd[-1]]
        services[service] = False
        persist()

    replace = os.replace
    def publish(src, dst):
        if Path(dst).name == "recovery-hold" and fault == ("kill-final-hold" if Path(dst).exists() else "kill-first-hold"):
            os.kill(os.getpid(), signal.SIGKILL)
        return replace(src, dst)

    images_fs, volumes_fs = Mock(spec=Path), Mock(spec=Path)
    images_fs.stat.return_value.st_dev = 1
    volumes_fs.stat.return_value.st_dev = 2

    def free(path):
        return Mock(free=host.RESERVE + (1199 if path is volumes_fs and fault == "full-volume" else 10000))

    db = {"container": ids["postgres"], "cluster_and_db": "18|42" if fault == "other-db" else "17|42"}
    def check_edge():
        if fault == "bad-edge":
            raise Refused("fixture cannot confirm edge closed")

    def window(cid):
        if fault == "dirty":
            raise Refused("successor no longer clean at binding")
        return nullcontext()

    with ExitStack() as stack:
        if fault == "kill-before-binding":
            stack.enter_context(patch.object(profile, "publish_binding", side_effect=lambda *a: os.kill(os.getpid(), signal.SIGKILL)))
        if fault == "kill-after-binding":
            write_binding = profile.publish_binding
            def write_then_kill(*args):
                write_binding(*args)
                os.kill(os.getpid(), signal.SIGKILL)
            stack.enter_context(patch.object(profile, "publish_binding", write_then_kill))
        for owner, name, value in (
                (traffic, "GATE", root / "ingress/open"), (traffic, "check_edge_closed", check_edge),
                (os, "replace", publish), (traffic, "check_gate_binary", lambda p: None),
                (traffic, "pg_query", lambda *a: "f" if fault == "dirty" else "t"),
                (traffic, "cluster_identity", lambda p: db), (profile, "database_identity", lambda p: db),
                (traffic, "successor_binding_window", window), (profile, "pg_query", lambda *a: "t"),
                (traffic, "check_target_image", lambda p: image(doc["services"]["backend"]["image"])),
                (traffic, "verify_running", lambda p, **kw: ids["postgres"]),
                (host, "verify_slice", lambda: None), (host, "verify_container", lambda *a: None),
                (host, "docker_storage", lambda: ("overlayfs", [images_fs], [])), (host, "cached_image_size", lambda *args: 100),
                (host, "local_volume_path", lambda name, existing: volumes_fs), (shutil, "disk_usage", free),
                (host, "lock_args", lambda: {}),
                (host, "operator_lock", lambda *a: nullcontext()), (subprocess, "run", stop),
                (subprocess, "check_output", output), (sys, "argv", ["production_traffic.py", action, str(plan), *cli_args] + (["e"*64] if action == "recovery-begin" else []))):
            stack.enter_context(patch.object(owner, name, value))
        traffic.main()


def run_lease_fixture(root):
    shared = root / "shared.lock"
    actual_fstat = os.fstat
    actual_lease = host.shared_lock
    def fixture_owner(fd):
        st = actual_fstat(fd)
        if os.path.samestat(st, shared.stat()):
            values = list(st)
            values[4] = 0  # Simulate root provisioning on an unprivileged test runner.
            return os.stat_result(values)
        return st
    with ExitStack() as stack:
        stack.enter_context(patch.object(os, "fstat", fixture_owner))
        if not hasattr(os, "listxattr"):
            stack.enter_context(patch.object(os, "listxattr", lambda fd: [], create=True))
        stack.enter_context(patch.object(host, "shared_lock", lambda: actual_lease(shared, wait=0)))
        with host.operator_lock(root / "open"):
            traffic.compose(root / "plan", "stop", "backend")


if __name__ == "__main__":
    if sys.argv[1:2] == ["install-fixture"]:
        run_install_fixture(Path(sys.argv[2]), *sys.argv[3:])
    elif sys.argv[1:2] == ["lease-fixture"]:
        run_lease_fixture(Path(sys.argv[2]))
    else:
        unittest.main()
