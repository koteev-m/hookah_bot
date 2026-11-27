"""Contracts for the shared Compose runner package installation."""

import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "install_compose_ci_postgres", Path(__file__).with_name("install-compose-ci-postgres.py")
)
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)

ARCH = "amd64"
TARGET = "18.7-1.pgdg26.04+5"
NEWER = "19.0-2.pgdg26.04+1"
PGDG = "https://apt.postgresql.org/pub/repos/apt"


class InstallerTests(unittest.TestCase):
    def test_selects_libpq18_from_the_native_versioned_pgdg_component(self):
        listing = f"""+libpq5 | {NEWER} | {PGDG} resolute-pgdg/main {ARCH} Packages
libpq5 | 18.9-1 | https://example.com/apt resolute-pgdg/18 {ARCH} Packages
libpq5 | 18.8-1 | {PGDG} resolute-pgdg/18 arm64 Packages
libpq5 | 18.7-1.pgdg26.04+2 | {PGDG} resolute-pgdg/18 {ARCH} Packages
libpq5 | {TARGET} | {PGDG} resolute-pgdg/18 {ARCH} Packages
"""
        with patch.object(installer, "run", return_value=listing), patch.object(
            installer, "newer", side_effect=lambda left, right: left > right
        ):
            self.assertEqual(installer.libpq18_version("resolute", ARCH), TARGET)
            with patch.object(installer, "run", return_value=listing.splitlines()[0]), self.assertRaisesRegex(
                ValueError, "no libpq 18 package"
            ):
                installer.libpq18_version("resolute", ARCH)

    def test_only_exact_native_libpq_downgrades_are_allowed(self):
        targets = {"libpq5": TARGET, "libpq-dev": TARGET}
        lines = [f"Inst {package}:{ARCH} [{NEWER}] ({TARGET} PGDG [{ARCH}])" for package in targets]
        with patch.object(installer, "newer", return_value=True):
            self.assertEqual(installer.checked_downgrades("\n".join(lines), ARCH, targets), lines)
            for rejected in (lines[0].replace("libpq5", "postgresql-17"),
                             lines[0].replace(TARGET, "18.6-1"), lines[0].replace(ARCH, "arm64")):
                with self.subTest(line=rejected), self.assertRaisesRegex(ValueError, "downgrades an unrelated"):
                    installer.checked_downgrades("\n".join([*lines, rejected]), ARCH, targets)
            with self.assertRaisesRegex(ValueError, "removes a package"):
                installer.checked_downgrades("Remv libpq-dev", ARCH, targets)

    def test_install_flag_depends_on_the_checked_plan(self):
        env = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_OS": "Linux"}
        for installed_version in (TARGET, NEWER):
            is_downgrade = installed_version == NEWER
            initial = {"libpq5": installed_version, "libpq-dev": installed_version}
            expected = {"libpq5": TARGET, "libpq-dev": TARGET}
            plan = "\n".join(f"Inst {p} [{NEWER}] ({TARGET} PGDG [{ARCH}])" for p in expected) if is_downgrade else ""
            with self.subTest(installed=installed_version), patch.dict(os.environ, env), patch.object(
                installer.sys, "platform", "linux"
            ), patch.object(installer.Path, "read_text", return_value="VERSION_CODENAME=resolute\n"), patch.object(
                installer, "libpq18_version", return_value=TARGET
            ), patch.object(installer, "installed_libpq", side_effect=[initial, expected]), patch.object(
                installer, "newer", return_value=is_downgrade
            ), patch.object(installer, "run", side_effect=[ARCH, None, None, None, plan, None]) as run:
                installer.install_tools(with_age=True)
            simulate, install = (args.args for args in run.call_args_list[-2:])
            self.assertIn("-s", simulate)
            self.assertNotIn("--allow-downgrades", simulate)
            self.assertIn("--no-remove", install)
            self.assertEqual("--allow-downgrades" in install, is_downgrade)
            self.assertEqual(simulate[simulate.index("install"):], install[install.index("install"):])
            for package in ("age", "postgresql-17", "postgresql-client-17", "postgresql-client-18",
                            f"libpq5={TARGET}", f"libpq-dev={TARGET}"):
                self.assertIn(package, install)


if __name__ == "__main__":
    unittest.main()
