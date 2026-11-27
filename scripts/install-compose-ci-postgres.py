#!/usr/bin/env python3
"""Install the PostgreSQL tools shared by the hosted Compose jobs."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


LIBPQ_PACKAGES = ("libpq5", "libpq-dev")
APT_GET = ("sudo", "-n", "env", "LC_ALL=C", "DEBIAN_FRONTEND=noninteractive", "apt-get")
APT_INSTALL = (*APT_GET, "--no-install-recommends", "--no-remove")
INSTALL_LINE = re.compile(r"^Inst (\S+)(?: \[([^\]]+)\])? \((\S+)")


def run(*args, capture=False):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE if capture else None).stdout


def newer(left, right):
    returncode = subprocess.run(("dpkg", "--compare-versions", left, "gt", right), check=False).returncode
    if returncode not in (0, 1):
        raise ValueError(f"cannot compare Debian package versions {left!r} and {right!r}")
    return returncode == 0


def libpq18_version(codename, arch):
    source = f"https://apt.postgresql.org/pub/repos/apt {codename}-pgdg/18 {arch} Packages"
    version = None
    for line in run("apt-cache", "madison", "libpq5", capture=True).splitlines():
        _, candidate, index = (part.strip() for part in line.split("|", 2))
        if (index == source and re.fullmatch(r"18\.[0-9]+-\S+", candidate)
                and (version is None or newer(candidate, version))):
            version = candidate
    if version is None:
        raise ValueError(f"no libpq 18 package in the signed PGDG component: {source}")
    return version


def installed_libpq(arch):
    listing = run("dpkg-query", "-W", "-f=${Package}\t${Architecture}\t${db:Status-Status}\t${Version}\n",
                  capture=True)
    return {package: version for package, package_arch, status, version in
            (line.split("\t") for line in listing.splitlines())
            if package in LIBPQ_PACKAGES and package_arch == arch and status == "installed"}


def checked_downgrades(plan, arch, requested):
    downgrades = []
    for line in plan.splitlines():
        if line.startswith(("Remv ", "Purg ")):
            raise ValueError(f"APT plan removes a package: {line}")
        if not line.startswith("Inst "):
            continue
        match = INSTALL_LINE.match(line)
        if match is None:
            raise ValueError(f"unexpected APT plan line: {line}")
        package, old, target = match.groups()
        if old is None or not newer(old, target):
            continue
        if requested.get(package.removesuffix(f":{arch}")) != target:
            raise ValueError(f"APT plan downgrades an unrelated package or version: {line}")
        downgrades.append(line)
    return downgrades


def install_tools(with_age):
    if (os.environ.get("GITHUB_ACTIONS"), os.environ.get("RUNNER_ENVIRONMENT"), os.environ.get("RUNNER_OS")) != (
        "true", "github-hosted", "Linux"
    ) or not sys.platform.startswith("linux"):
        raise ValueError("requires an isolated GitHub-hosted Linux runner")
    os.environ["LC_ALL"] = "C"
    codename = re.search(r"(?m)^VERSION_CODENAME=([a-z]+)$", Path("/etc/os-release").read_text()).group(1)
    arch = run("dpkg", "--print-architecture", capture=True).strip()
    run(*APT_GET, "-o", "APT::Update::Error-Mode=any", "update")
    run(*APT_INSTALL, "-y", "install", "postgresql-common", "ca-certificates", "curl")
    run("sudo", "-n", "/usr/share/postgresql-common/pgdg/apt.postgresql.org.sh", "-y", "-c", "18")
    version = libpq18_version(codename, arch)
    installed = installed_libpq(arch)
    requested = {"libpq5": version}
    if "libpq-dev" in installed:
        # libpq-dev depends on exactly the same libpq5 version.
        requested["libpq-dev"] = version
    packages = ["postgresql-17", "postgresql-client-17", "postgresql-client-18", "openssh-server"]
    if with_age:
        packages.append("age")
    packages.extend(f"{package}={target}" for package, target in requested.items())
    print(f"Installed libpq packages: {installed}; requested: {requested}", flush=True)
    # Hosted images set Assume-Yes globally, which rejects even a simulated downgrade.
    plan = run(*APT_INSTALL, "-s", "-o", "APT::Get::Assume-Yes=false", "install", *packages, capture=True)
    downgrades = checked_downgrades(plan, arch, requested)
    if downgrades:
        print("Approved exact libpq downgrade plan:\n" + "\n".join(downgrades), flush=True)
    options = ["--allow-downgrades"] if downgrades else []
    run(*APT_INSTALL, "-y", *options, "install", *packages)
    actual = installed_libpq(arch)
    if any(actual.get(package) != target for package, target in requested.items()):
        raise ValueError(f"unexpected installed libpq packages: {actual}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--with-age", action="store_true")
    try:
        install_tools(parser.parse_args().with_age)
    except (ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
