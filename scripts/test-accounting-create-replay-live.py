#!/usr/bin/env python3
"""Real browser CREATE -> PostgreSQL -> new JVM -> exact replay -> PAY original sale -> fresh KPI DOM."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1]).resolve()
project = sys.argv[2] if len(sys.argv) > 2 else 'chromium'
assert project in ('chromium', 'webkit')
out.mkdir(parents=True, exist_ok=False)
live = out / 'session'
env = {**os.environ, 'ACCOUNTING_CREATE_LIVE_DIR': str(live), 'MINIAPP_E2E_PORT': '5183',
       'MINIAPP_E2E_WEBKIT': 'true', 'JAVA_TOOL_OPTIONS': '-Dapi.version=1.44'}
with (out / 'backend.log').open('w') as log:
    backend = subprocess.Popen([str(root / 'gradlew'), '--offline', '--no-daemon', '--max-workers=1',
                                ':backend:app:test', '--rerun', '--tests', '*AccountingCreateReplayPostgresHttpTest',
                                '--console=plain'], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 300
        while not (live / 'ready.json').exists():
            if backend.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('Native PostgreSQL JVM fixture failed to start; inspect backend.log')
            time.sleep(.2)
        with (out / 'browser.log').open('w') as browser_log:
            subprocess.run(['npm', '--prefix', 'miniapp', 'run', 'e2e:smoke', '--',
                            'accounting-payments-finalize.spec.ts', '--grep', 'review F1 CREATE replay',
                            f'--project={project}', '--workers=1', '--reporter=list,json',
                            f'--output={out / "browser"}'], cwd=root,
                           env={**env, 'PLAYWRIGHT_JSON_OUTPUT_FILE': str(out / 'browser.json')},
                           stdout=browser_log, stderr=subprocess.STDOUT, check=True, timeout=180)
        backend.wait(timeout=60)
        if backend.returncode:
            raise RuntimeError('Native database postconditions failed')
        result = json.loads((out / 'browser.json').read_text())
        assert result['stats']['expected'] == 1 and all(result['stats'][k] == 0 for k in ('unexpected', 'skipped', 'flaky')) and not result.get('errors')
        db = json.loads((live / 'verified.json').read_text())
        ui = json.loads((live / 'browser-proof.json').read_text())
        assert db['status'] == 'PASS' and db['actualJvmRestart'] and db['getBusinessWrites'] == 0
        assert ui['exactReplay'] and ui['paidSaleId'] == db['originalSaleId'] == db['paidSaleId']
        assert db['otherPaidMinor'] == 0
        print(json.dumps({'status': 'PASS', 'project': project, 'database': db, 'browser': ui}, indent=2))
    finally:
        if backend.poll() is None:
            backend.terminate()
            try:
                backend.wait(timeout=20)
            except subprocess.TimeoutExpired:
                backend.kill()
                backend.wait(timeout=10)
