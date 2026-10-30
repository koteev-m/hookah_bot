#!/usr/bin/env python3
"""Mandatory Linux Docker PDF isolation proof, against one exact local image ID. No live services."""
import argparse
import concurrent.futures
from contextlib import contextmanager
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time
import uuid

import simple_staging as staging


def run(args, *, data=None, timeout=45, check=True):
    p = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if check and p.returncode:
        raise AssertionError('FIXTURE_COMMAND_FAILED ' + ' '.join(args[:3]) + ': ' + p.stderr.decode(errors='replace')[-2000:])
    return p


@contextmanager
def probe_fixture(class_name):
    with tempfile.TemporaryDirectory(prefix='ht-pdf-probe-') as temp:
        run(['javac', '--release', '17', '-d', temp,
             str(Path(__file__).parent / 'fixtures' / (class_name + '.java'))])
        # javac inherits CI's umask 077; only this generated fixture is shared with UID 10001.
        Path(temp, class_name + '.class').chmod(0o644)
        os.chmod(temp, 0o755)
        yield temp


def pdf(pages=1, content=b'BT /F1 12 Tf 10 10 Td (Venue information) Tj ET', extra=None, trailer=b'', catalog=b''):
    objects = [b'<< /Type /Catalog /Pages 2 0 R ' + catalog + b' >>', b'',
               b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               b'<< /Length ' + str(len(content)).encode() + b' >>\nstream\n' + content + b'\nendstream']
    children = []
    for _ in range(pages):
        children.append(str(len(objects) + 1).encode() + b' 0 R')
        objects.append(b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F1 3 0 R >> >> /Contents 4 0 R >>')
    objects[1] = b'<< /Type /Pages /Kids [' + b' '.join(children) + b'] /Count ' + str(pages).encode() + b' >>'
    if extra is not None: objects.append(extra)
    return from_objects(objects, trailer)


def from_objects(objects, trailer=b''):
    output = bytearray(b'%PDF-1.4\n'); offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(output)); output.extend(str(i).encode() + b' 0 obj\n' + obj + b'\nendobj\n')
    xref = len(output)
    output.extend(b'xref\n0 ' + str(len(offsets)).encode() + b'\n0000000000 65535 f \n')
    for offset in offsets[1:]: output.extend(f'{offset:010} 00000 n \n'.encode())
    output.extend(b'trailer\n<< /Root 1 0 R /Size ' + str(len(offsets)).encode() + b' ' + trailer + b' >>\nstartxref\n' + str(xref).encode() + b'\n%%EOF\n')
    return bytes(output)


def dag(depth=32):
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>']
    for i in range(2, depth + 2):
        objects.append(f'<< /Type /Pages /Kids [{i+1} 0 R {i+1} 0 R] /Count 1 >>'.encode())
    objects.append(b'<< /Type /Page /MediaBox [0 0 100 100] >>')
    return from_objects(objects)


class Proof:
    def __init__(self, image, evidence):
        self.image = json.loads(run(['docker', 'image', 'inspect', image]).stdout)[0]['Id']
        self.root = evidence
        self.root.mkdir(parents=True, exist_ok=True)
        self.token = 'ht-pdf-' + uuid.uuid4().hex[:12]
        self.volume = self.token + '_pdf_ipc'
        self.worker = self.token + '-worker'
        self.clients = set()
        self.results = []

    def owned(self, name):
        inspect = json.loads(run(['docker', 'inspect', name]).stdout)[0]
        assert inspect['Config']['Labels'].get('ht.pdf.fixture') == self.token
        return inspect

    def start(self):
        run(['docker', 'volume', 'create', '--label', 'ht.pdf.fixture=' + self.token,
             '--label', 'com.docker.compose.project=' + self.token,
             '--label', 'com.docker.compose.volume=pdf_ipc', '--driver', 'local',
             '--opt', 'type=tmpfs', '--opt', 'device=tmpfs', '--opt', 'o=' + staging.PDF_VOLUME_OPTIONS['o'], self.volume])
        self.worker_args = ['docker', 'run', '-d', '--restart', 'unless-stopped', '--name', self.worker, '--label', 'ht.pdf.fixture=' + self.token,
            '--label', 'com.docker.compose.project=' + self.token,
            '--label', 'com.docker.compose.service=pdf-worker', '--label', 'com.docker.compose.project.working_dir=' + str(self.root),
            '--network', 'none', '--user', '10001:10001', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges:true', '--init', '--ulimit', 'core=0:0', '--memory', str(staging.PDF_MEMORY),
            '--memory-swap', str(staging.PDF_MEMORY), '--cpus', '1', '--pids-limit', '64',
            '--mount', 'type=volume,src=' + self.volume + ',dst=' + staging.PDF_IPC,
            '--health-cmd', '/app/app/bin/pdf-worker probe', '--health-interval', '10s', '--health-timeout', '3s', '--health-retries', '3', '--health-start-period', '5s',
            '--entrypoint', '/app/app/bin/pdf-worker', self.image]
        run(self.worker_args)
        self.wait_ready()

    def client(self, data=b'', mode='validate', timeout=15):
        name = self.token + '-client-' + uuid.uuid4().hex[:8]
        self.clients.add(name)
        cmd = ['docker', 'run', '--name', name, '--label', 'ht.pdf.fixture=' + self.token,
               '-i', '--network', 'none', '--user', '10001:10001', '--read-only', '--cap-drop', 'ALL',
               '--security-opt', 'no-new-privileges:true', '--memory', '128m', '--memory-swap', '128m',
               '--pids-limit', '64', '--cpus', '1', '--mount', 'type=volume,src=' + self.volume + ',dst=' + staging.PDF_IPC + ',readonly',
               '--entrypoint', '/opt/java/openjdk/bin/java', self.image,
               '-Xmx64m', '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:-UsePerfData',
               '-cp', '/app/app/lib/*', 'com.hookah.platform.backend.miniapp.venue.media.pdf.PdfClientMain', mode]
        try:
            p = run(cmd, data=data, timeout=timeout)
            output = p.stdout.decode().strip()
            assert output in ('PDF_RESULT=0', 'PDF_RESULT=1', 'PDF_RESULT=2'), output
            return int(output[-1])
        finally:
            result = run(['docker', 'inspect', name], check=False)
            if result.returncode == 0:
                self.owned(name)
                run(['docker', 'rm', '-f', name])
            self.clients.discard(name)

    def malformed(self):
        with probe_fixture('PdfIpcProbe') as temp:
            for case in ('short-header', 'bad-magic', 'unknown-op', 'oversize', 'partial-body', 'trailing-byte', 'slow-header'):
                name = self.token + '-probe-' + uuid.uuid4().hex[:8]
                self.clients.add(name)
                try:
                    result = run(['docker', 'run', '--name', name, '--label', 'ht.pdf.fixture=' + self.token,
                        '--network', 'none', '--user', '10001:10001', '--read-only', '--cap-drop', 'ALL',
                        '--security-opt', 'no-new-privileges:true', '--memory', '96m', '--memory-swap', '96m',
                        '--pids-limit', '64', '--cpus', '1',
                        '--mount', 'type=volume,src=' + self.volume + ',dst=' + staging.PDF_IPC + ',readonly',
                        '--mount', 'type=bind,src=' + str(Path(temp).resolve()) + ',dst=/probe,readonly',
                        '--entrypoint', '/opt/java/openjdk/bin/java', self.image, '-Xmx32m', '-XX:+UseSerialGC',
                        '-XX:ActiveProcessorCount=1', '-XX:-UsePerfData', '-cp', '/probe', 'PdfIpcProbe', case], timeout=8)
                    assert result.stdout.strip() == b'IPC_REJECTED'
                finally:
                    self.owned(name); run(['docker', 'rm', '-f', name]); self.clients.discard(name)
                assert self.client(mode='ping') == 0
                print('PASS malformed-IPC', case, flush=True)

    def resource_enforcement(self):
        evidence = {}
        with probe_fixture('PdfResourceProbe') as temp:
            for mode in ('memory', 'pids', 'cpu'):
                name = self.token + '-resources-' + mode
                self.clients.add(name)
                started = time.monotonic()
                result = run(['docker', 'run', '--name', name, '--label', 'ht.pdf.fixture=' + self.token,
                    '--network', 'none', '--user', '10001:10001', '--read-only', '--cap-drop', 'ALL',
                    '--security-opt', 'no-new-privileges:true', '--memory', str(staging.PDF_MEMORY),
                    '--memory-swap', str(staging.PDF_MEMORY), '--cpus', '1', '--pids-limit', '64',
                    '--mount', 'type=bind,src=' + str(Path(temp).resolve()) + ',dst=/probe,readonly',
                    '--entrypoint', '/opt/java/openjdk/bin/java', self.image, '-Xmx768m', '-Xss512k',
                    '-XX:+UseSerialGC', '-XX:ActiveProcessorCount=1', '-XX:-UsePerfData',
                    '-cp', '/probe', 'PdfResourceProbe', mode], check=False, timeout=15)
                state = self.owned(name)['State']
                output = result.stdout.decode(errors='replace')
                if mode == 'memory': assert state['OOMKilled'] and result.returncode == 137
                if mode == 'pids': assert result.returncode == 0 and 'PIDS_ENFORCED' in output
                if mode == 'cpu':
                    counters = dict(line.split() for line in output.splitlines())
                    assert result.returncode == 0 and int(counters['nr_throttled']) > 0
                evidence[mode] = {'exit': result.returncode, 'oom_killed': state['OOMKilled'],
                                  'elapsed_seconds': round(time.monotonic() - started, 3), 'output': output[-2000:]}
                run(['docker', 'rm', name]); self.clients.discard(name)
                print('PASS kernel-enforcement', mode, flush=True)
        self.root.joinpath('kernel-enforcement.json').write_text(json.dumps(evidence, indent=2))

    def wait_ready(self):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if self.owned(self.worker)['State']['Running']:
                p = run(['docker', 'exec', self.worker, '/app/app/bin/pdf-worker', 'probe'], check=False, timeout=12)
                if p.returncode == 0 and self.owned(self.worker)['State'].get('Health', {}).get('Status') == 'healthy': return
            time.sleep(.2)
        logs = run(['docker', 'logs', '--tail', '30', self.worker]).stderr.decode(errors='replace')
        raise AssertionError('WORKER_NOT_READY ' + logs[-2000:])

    def case(self, name, data, expected):
        started = time.monotonic()
        actual = self.client(data)
        elapsed = round(time.monotonic() - started, 3)
        if actual not in expected:
            info = self.owned(self.worker)
            self.root.joinpath('failure-state.json').write_text(json.dumps({'case': name, 'elapsed': elapsed, 'state': info['State']}, indent=2))
            logs = run(['docker', 'logs', '--tail', '60', self.worker])
            self.root.joinpath('failure-worker.log').write_bytes(logs.stdout + logs.stderr)
        assert actual in expected, (name, actual, expected, elapsed)
        counters = run(['docker', 'exec', self.worker, '/bin/sh', '-c',
            'cat /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.current /sys/fs/cgroup/pids.current']).stdout.decode().splitlines()
        self.results.append({'case': name, 'result': actual, 'elapsed_seconds': elapsed, 'input_bytes': len(data),
                             'memory_peak_bytes': int(counters[0]), 'memory_current_bytes': int(counters[1]), 'pids_current': int(counters[2])})
        print('PASS', name, actual, elapsed, flush=True)

    def parser_pid(self):
        code = '''for f in /proc/[0-9]*/cmdline; do
if grep -aq '^/opt/java/openjdk/bin/java' "$f" && grep -aq 'com.hookah.platform.backend.miniapp.venue.media.pdf.PdfParserMain' "$f"; then
p=${f#/proc/}; printf '%s\\n' "${p%/cmdline}"
fi
done'''
        return run(['docker', 'exec', self.worker, '/bin/sh', '-c', code]).stdout.decode().splitlines()

    def exercise(self):
        info = self.owned(self.worker)
        observer = object.__new__(staging.Staging)
        observer.root, observer.project = self.root, self.token
        observer.run = lambda args, **kwargs: run(args, **kwargs).stdout
        base = json.loads(run(['docker', 'image', 'inspect', self.image]).stdout)[0]
        staging.Staging.observe_pdf_worker(observer, info, {'id': self.image,
            'pdf_worker_env_hash': staging.digest(sorted(base['Config'].get('Env', [])))})
        host = info['HostConfig']
        assert host['Memory'] == staging.PDF_MEMORY and host['MemorySwap'] == staging.PDF_MEMORY
        assert host['NanoCpus'] == 1000000000 and host['PidsLimit'] == 64
        assert host['ReadonlyRootfs'] and not host['Privileged'] and host['NetworkMode'] == 'none'
        assert host['SecurityOpt'] == ['no-new-privileges:true'] and host['CapDrop'] == ['ALL']
        assert len(info['Mounts']) == 1 and info['Mounts'][0]['Name'] == self.volume
        assert not host['PortBindings'] and set(info['NetworkSettings']['Networks']) == {'none'}
        assert not any(k.split('=')[0].startswith(('DB_', 'STAFF_', 'TELEGRAM_', 'AWS_', 'API_SESSION')) for k in info['Config']['Env'])
        self.malformed()
        network = run(['docker', 'exec', self.worker, '/bin/bash', '-c',
            'timeout 1 bash -c "exec 3<>/dev/tcp/198.51.100.1/9"'], check=False)
        assert network.returncode != 0
        assert run(['docker', 'exec', self.worker, '/bin/touch', '/tmp/pdf-write-test'], check=False).returncode != 0
        assert run(['docker', 'exec', self.worker, '/bin/sh', '-c',
            'test ! -e /var/run/docker.sock && test ! -e /var/lib/postgresql/data']).returncode == 0
        self.case('ordinary-1-page' , pdf(), {0})
        self.case('ordinary-200-pages', pdf(200), {0})
        self.case('ordinary-large-stream', pdf(content=b'%' + b'x' * (8 * 1024 * 1024) + b'\n'), {0})
        for white in (b' ', b'\t', b'\n', b'\r', b'\x00', b'\x0c'):
            source = pdf().replace(b'xref\n0 6', b'xref\n0' + white + b'1000000000', 1)
            self.case('xref-allocation-' + white.hex(), source, {1})
        for name in (b'/Prev 9', b'/Pr#65v 9', b'/XRefStm 9', b'/Encrypt << /Filter /Standard /V 1 /R 2 /O (xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx) /U (yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy) /P -4 >>'):
            self.case('unsupported-' + name.split()[0].decode(), pdf(trailer=name), {1})
        for escaped in (False, True):
            source = pdf(); offset = int(source.split(b'startxref\n')[-1].splitlines()[0])
            name = b'/Pr#65v' if escaped else b'/Prev'
            self.case('cyclic-Prev-escaped-' + str(escaped), source.replace(b' /Size 6 ', b' /Size 6 ' + name + b' ' + str(offset).encode() + b' '), {1})
        self.case('cyclic-Kids', from_objects([b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [2 0 R] /Count 1 >>']), {1, 2})
        self.case('deep-structure', pdf(extra=b'[' * 10000 + b']' * 10000), {1})
        self.case('large-structure', pdf(extra=b'[' + b'null ' * 100001 + b']'), {1})
        self.case('truncated', pdf()[:100], {1})
        self.case('active-JavaScript', pdf(catalog=b'/OpenAction << /S /JavaScript /JS (app.alert) >>'), {1})
        self.case('active-escaped-JavaScript', pdf(catalog=b'/OpenAction << /S /Java#53cript /J#53 (app.alert) >>'), {1})
        self.case('graph-amplification', dag(), {1, 2})
        assert self.client(mode='ping') == 0 and not self.parser_pid()
        # Freeze only the untrusted parser process; supervisor must kill/reap it by its deadline.
        with concurrent.futures.ThreadPoolExecutor(2) as executor:
            work = executor.submit(self.client, dag())
            deadline = time.monotonic() + 4
            child = []
            while time.monotonic() < deadline and not child:
                child = self.parser_pid()
            assert len(child) == 1, 'SINGLE_PARSER_NOT_OBSERVED'
            frozen_at = time.monotonic()
            run(['docker', 'exec', self.worker, '/bin/kill', '-STOP', child[0]])
            second = executor.submit(self.client, pdf())
            assert self.parser_pid() == child, 'MORE_THAN_ONE_ACTIVE_PARSER'
            assert work.result(timeout=12) == 2
            deadline_elapsed = round(time.monotonic() - frozen_at, 3)
            assert deadline_elapsed < 9
            assert second.result(timeout=12) == 0
            self.root.joinpath('deadline.json').write_text(json.dumps({'frozen_child_reply_seconds': deadline_elapsed, 'result': 2, 'kill_reap': True}, indent=2))
        assert not self.parser_pid() and self.client(mode='ping') == 0
        assert run(['docker', 'exec', self.worker, '/bin/sh', '-c', "! grep -q '^State:.*Z' /proc/[0-9]*/status"]).returncode == 0
        self.case('after-timeout-recovery', pdf(), {0})
        with concurrent.futures.ThreadPoolExecutor(2) as executor:
            pending = [executor.submit(self.client, pdf(200)) for _ in range(2)]
            assert all(p.result(timeout=25) in (0, 2) for p in pending)
        assert not self.parser_pid()
        self.root.joinpath('cgroup-before-restart.txt').write_bytes(run(['docker', 'exec', self.worker, '/bin/sh', '-c',
            'for n in memory.max memory.peak memory.events cpu.max cpu.stat pids.max pids.peak; do printf "%s\\n" "$n"; cat /sys/fs/cgroup/$n; done']).stdout)
        # A real supervisor crash, rather than a Docker manual-stop, must auto-restart.
        before_restart = self.owned(self.worker)['RestartCount']
        child = run(['docker', 'exec', self.worker, '/bin/cat', '/proc/1/task/1/children']).stdout.decode().split()
        assert len(child) == 1
        cmdline = run(['docker', 'exec', self.worker, '/bin/cat', '/proc/' + child[0] + '/cmdline']).stdout
        assert b'PdfWorkerMain' in cmdline
        run(['docker', 'exec', self.worker, '/bin/kill', '-KILL', child[0]])
        self.wait_ready()
        assert self.owned(self.worker)['RestartCount'] > before_restart, 'AUTOMATIC_RESTART_NOT_OBSERVED'
        self.case('after-worker-crash-restart', pdf(), {0})
        # Admission is mandatory: the same entrypoint must refuse absent cgroup limits.
        negative = self.token + '-unbounded'
        self.clients.add(negative)
        p = run(['docker', 'run', '--name', negative, '--label', 'ht.pdf.fixture=' + self.token,
                 '--network', 'none', '--user', '10001:10001', '--read-only', '--cap-drop', 'ALL',
                 '--security-opt', 'no-new-privileges:true', '--entrypoint', '/app/app/bin/pdf-worker', self.image], check=False)
        assert p.returncode != 0, 'UNENFORCED_LIMITS_ACCEPTED'
        self.owned(negative); run(['docker', 'rm', negative]); self.clients.discard(negative)
        cgroup = run(['docker', 'exec', self.worker, '/bin/sh', '-c',
                      'for n in memory.max memory.peak memory.events cpu.max cpu.stat pids.max pids.peak; do printf "%s\\n" "$n"; cat /sys/fs/cgroup/$n; done']).stdout.decode()
        self.root.joinpath('cgroup-evidence.txt').write_text(cgroup)
        self.root.joinpath('worker-limits.json').write_text(json.dumps({k: host[k] for k in ('Memory', 'MemorySwap', 'NanoCpus', 'PidsLimit', 'ReadonlyRootfs', 'SecurityOpt', 'CapDrop', 'NetworkMode')}, indent=2))
        self.resource_enforcement()
        logs = run(['docker', 'logs', '--tail', '100', self.worker])
        self.root.joinpath('worker-safe.log').write_bytes(logs.stdout + logs.stderr)
        self.root.joinpath('results.json').write_text(json.dumps({'status': 'PASS', 'image': self.image, 'cases': self.results,
            'single_parser': True, 'deadline_kill_reap': True, 'crash_restart': True, 'limits_admission': True}, indent=2))

    def close(self):
        for name in list(self.clients) + [self.worker]:
            p = run(['docker', 'inspect', name], check=False)
            if p.returncode == 0:
                self.owned(name); run(['docker', 'rm', '-f', name])
        p = run(['docker', 'volume', 'inspect', self.volume], check=False)
        if p.returncode == 0:
            assert json.loads(p.stdout)[0]['Labels'].get('ht.pdf.fixture') == self.token
            run(['docker', 'volume', 'rm', self.volume])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', required=True)
    parser.add_argument('--evidence-dir', required=True, type=Path)
    args = parser.parse_args()
    proof = Proof(args.image, args.evidence_dir.resolve())
    try: proof.start(); proof.exercise()
    finally: proof.close()


if __name__ == '__main__': main()
