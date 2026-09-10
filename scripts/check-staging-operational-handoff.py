#!/usr/bin/env python3
"""Read-only operational authority guard; never applies a handoff or retires a run.

The standalone diagnostic still refuses protocol targets. The supervised ordinary
worker calls check_authority only while the shared target supervisor owns the lock.
This metadata/configuration guard never grants target eligibility by itself.
"""
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def protected(path, directory=False, environment=False):
    info = path.lstat()
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode),
            'configuration must be regular and nonsymlink')
    require(info.st_uid == 0 and info.st_gid == 0 and not info.st_mode & 0o022,
            'approved root ownership is required')
    if not directory:
        require((not environment or stat.S_IMODE(info.st_mode) == 0o600) and info.st_nlink == 1,
                'fixed environment must be private with one link')


def validate_environment(raw, expected):
    require(re.fullmatch(r'[a-z0-9][a-z0-9._/-]*:[0-9a-f]{40}', expected),
            'full-SHA image is required')
    values = re.findall(r'^BACKEND_IMAGE=(.*)$', raw, re.M)
    require(values == [expected], 'fixed environment differs from approved image')


def check_authority(target, expected):
    require(os.geteuid() == 0, 'approved root deployment identity is required')
    require(target.is_absolute() and target.resolve(strict=True) == target, 'canonical target is required')
    protected(target, True)
    envfile = target / '.env'
    protected(envfile, environment=True)
    protected(target / 'docker-compose.yml')
    validate_environment(envfile.read_text(), expected)
    # No ambient interpolation may override the fixed environment. Capture config
    # privately and expose only a decision, never environment or credentials.
    env = {key: value for key, value in os.environ.items() if key in ('PATH', 'HOME')}
    result = subprocess.run(['docker', 'compose', '--env-file', str(envfile),
                             '-f', str(target / 'docker-compose.yml'), 'config', '--format', 'json'],
                            cwd=target, env=env, capture_output=True, timeout=20)
    require(result.returncode == 0, 'effective Compose configuration is unavailable')
    backend = json.loads(result.stdout)['services']['backend']
    require(backend.get('image') == expected and backend.get('restart') == 'unless-stopped',
            'effective operational image or restart policy differs from handoff')
    return backend


def check(target, expected):
    # This command is only a diagnostic; protocol eligibility cannot be checked
    # once here and reused later without retaining the same target lock.
    registry = target / '.v126-target-operations'
    require(not registry.exists() and not registry.is_symlink(),
            'cutover target requires protocol-aware operational deployment; ordinary deploy is blocked')
    check_authority(target, expected)
    print('OPERATIONAL_DEPLOY_PREFLIGHT=PASS migration_target=false')


if __name__ == '__main__':
    try:
        require(len(sys.argv) == 3, 'target and exact accepted image are required')
        check(Path(sys.argv[1]), sys.argv[2])
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
        print('OPERATIONAL_DEPLOY_PREFLIGHT=REFUSED review fixed image, ownership and cutover binding', file=sys.stderr)
        raise SystemExit(4)
