"""Exercise the cutover against real systemd in a disposable offline Linux VM.

Install the repository's two backup units under /etc/systemd/system, enable the timer,
create /etc/hookah-production and boot with ht.disposable_timing_test=1. The backup
producer is a recorder. systemd starts it via the actual timer and EnvironmentFile.
"""
import json
import os
from pathlib import Path
import subprocess
import time
import unittest

import test_production_backup_handoff as handoff


release = handoff.release


class SystemdHandoff(handoff.BackupHandoff):
    def environment_file(self):
        return Path("/etc/hookah-production/backup.env")

    def install_timer(self):
        self.dropin = Path("/run/systemd/system") / (release.TIMER + ".d")
        subprocess.run(["systemctl", "stop", release.TIMER, release.SERVICE], check=True)
        subprocess.run(["systemctl", "reset-failed"], check=True)
        self.override = self.dropin / "cutover-rehearsal.conf"
        self.addCleanup(self.environment_file().unlink, missing_ok=True)
        self.addCleanup(subprocess.run, ["systemctl", "daemon-reload"], check=True)
        self.addCleanup(self.override.unlink, missing_ok=True)
        self.addCleanup(subprocess.run, ["systemctl", "stop", release.TIMER, release.SERVICE], check=True)
        # A missed slot makes Persistent=true fire on start, using the worker's real unit.
        stamp = Path("/var/lib/systemd/timers") / ("stamp-" + release.TIMER)
        stamp.parent.mkdir(exist_ok=True, parents=True)
        stamp.touch()
        os.utime(stamp, (time.time() - 86400,) * 2)
        (self.root / "backup_points.py").write_text(
            "import sys,json\nfrom pathlib import Path\n"
            "assert sys.argv[1] == 'create'\nplan = Path(sys.argv[2])\n"
            "receipt = Path(__file__).with_name('timer-receipt')\n"
            "receipt.with_suffix('.tmp').write_text(json.dumps([str(plan),json.loads(plan.read_text())['x-hookah-schema']]))\n"
            "receipt.with_suffix('.tmp').replace(receipt)\n")

    def start_timer(self, real_run, cmd, kwargs):
        if self.start_failure:
            self.dropin.mkdir(exist_ok=True)
            self.override.write_text("[Unit]\nRefuseManualStart=yes\n")
            real_run(["systemctl", "daemon-reload"], check=True)
        return real_run(cmd, **kwargs)

    def timer_active(self):
        return subprocess.run(["systemctl", "is-active", "--quiet", release.TIMER]).returncode == 0

    def change_loaded_unit(self):
        self.dropin.mkdir(exist_ok=True)
        self.override.write_text("[Timer]\nAccuracySec=2min\n")
        subprocess.run(["systemctl", "daemon-reload"], check=True)

    def assert_protected(self):
        super().assert_protected()
        receipt = self.root / "timer-receipt"
        deadline = time.monotonic() + 115
        while not receipt.exists() and time.monotonic() < deadline:
            time.sleep(.25)
        self.assertEqual(json.loads(receipt.read_text()), [str(self.new), 147])
        state = subprocess.check_output(["systemctl", "show", release.TIMER, "-p", "ActiveState", "-p", "SubState",
                                        "-p", "NextElapseUSecRealtime", "-p", "Triggers"], text=True)
        print("Scheduled unit received the V147 plan.", state.strip(), flush=True)


if __name__ == "__main__":
    if not (Path("/proc/1/comm").read_text().strip() == "systemd" and
            "ht.disposable_timing_test=1" in Path("/proc/cmdline").read_text().split() and
            all(p.name == "lo" or (not (p / "device").exists() and
                                   (p / "operstate").read_text().strip() == "down")
                for p in Path("/sys/class/net").iterdir() if p.is_dir()) and
            not any(os.path.lexists(p) for p in (release.BACKUP_ENV, release.OPERATOR.parent, "/var/run/docker.sock"))):
        raise SystemExit("Requires an empty offline rehearsal VM with its opt-in boot flag.")
    for unit in (release.SERVICE, release.TIMER):
        if (Path("/etc/systemd/system") / unit).read_bytes() != (release.profile.PROFILE / unit).read_bytes():
            raise SystemExit("Install the exact repository units in the disposable VM.")
    unittest.main(verbosity=2)
