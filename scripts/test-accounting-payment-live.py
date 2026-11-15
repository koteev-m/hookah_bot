#!/usr/bin/env python3
"""Exercise real accounting UI -> loopback Ktor -> disposable PostgreSQL, including lost reply/restart."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / 'build/accounting-payment-live'
out.mkdir(parents=True, exist_ok=False)
live = out / 'session'
env = {**os.environ, 'ACCOUNTING_PAYMENT_LIVE_DIR': str(live),
       'MINIAPP_E2E_PORT': os.environ.get('MINIAPP_E2E_PORT', '5179')}
backend_log = (out / 'backend.log').open('w')
backend = subprocess.Popen([
    str(root / 'gradlew'), '--no-daemon', '--max-workers=1', ':backend:app:test',
    '--tests', '*AccountingSplitPaymentPostgresHttpTest', '--console=plain',
], cwd=root, env=env, stdout=backend_log, stderr=subprocess.STDOUT)
try:
    deadline = time.monotonic() + 300
    while not (live / 'ready.json').exists():
        if backend.poll() is not None or time.monotonic() > deadline:
            raise RuntimeError('fresh PostgreSQL HTTP fixture did not become ready; inspect backend.log')
        time.sleep(.2)
    browser_env = {**env, 'MINIAPP_E2E_PORT': os.environ.get('MINIAPP_E2E_PORT', '5179'),
                   'PLAYWRIGHT_JSON_OUTPUT_FILE': str(out / 'browser.json')}
    with (out / 'browser.log').open('w') as log:
        subprocess.run(['npm', '--prefix', 'miniapp', 'run', 'e2e:smoke', '--',
                        'accounting-payments-finalize.spec.ts', '--grep', 'payment PostgreSQL UI POST',
                        '--project=chromium', '--workers=1', '--reporter=list,json',
                        f'--output={out / "screenshots"}'], cwd=root, env=browser_env,
                       stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
    backend.wait(timeout=60)
    if backend.returncode:
        raise RuntimeError('PostgreSQL postconditions failed; inspect backend.log')
    result = json.loads((out / 'browser.json').read_text())
    stats = result['stats']
    if stats['expected'] != 1 or any(stats[k] != 0 for k in ['unexpected', 'flaky', 'skipped']) or result.get('errors'):
        raise RuntimeError(f'non-passing browser result: {stats}')
    verified = json.loads((live / 'verified.json').read_text())
    if verified['status'] != 'PASS' or not verified['restartedReplay'] or verified['getBusinessWrites'] != 0:
        raise RuntimeError('missing PostgreSQL verification')
    print(json.dumps({'status': 'PASS', 'browser': stats, 'database': verified}, indent=2))
finally:
    if backend.poll() is None:
        backend.terminate()
        try:
            backend.wait(timeout=15)
        except subprocess.TimeoutExpired:
            backend.kill()
            backend.wait(timeout=10)
    backend_log.close()
