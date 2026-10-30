"""Portable publication contract tests; no Docker, registry, GitHub or deployment calls."""
import copy
from email.message import Message
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.response
import zipfile

import yaml
import simple_staging as staging

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = yaml.load((ROOT / '.github/workflows/ci.yml').read_text(), Loader=yaml.BaseLoader)
JOB = WORKFLOW['jobs']['publish-ghcr']
STEPS = {step['id']: step for step in JOB['steps'] if 'id' in step}
SHA = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64
IMAGE_ID = 'sha256:' + 'c' * 64
IMAGE = 'ghcr.io/koteev-m/hookah_bot/backend'
SOURCE = 'https://github.com/koteev-m/hookah_bot'


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / 'candidate'
        self.needs = {name: {'result': 'success', 'outputs': {'verified': 'true'}}
                      for name in staging.REQUIRED_JOBS}
        self.env = dict(GITHUB_EVENT_NAME='push', GITHUB_REF='refs/heads/main',
                        GITHUB_REPOSITORY=staging.REPO, GITHUB_SHA=SHA,
                        GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1',
                        IMAGE_NAME=IMAGE, BUILD_DIGEST=DIGEST, CANDIDATE_DIR=str(self.output))
        self.manifest = {'digest': DIGEST, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
                         'config': {'digest': IMAGE_ID}}
        self.config = {'os': 'linux', 'architecture': 'amd64', 'config': {'Labels': {
            'org.opencontainers.image.source': SOURCE, 'org.opencontainers.image.revision': SHA},
            'Env': ['DO_NOT_EXPORT=fixture']}}

    def execute(self, step_id, runner):
        with patch.dict(os.environ, dict(self.env, NEEDS_JSON=json.dumps(self.needs))), \
                patch('subprocess.check_output', side_effect=runner):
            exec(compile(STEPS[step_id]['run'], f'ci.yml:{step_id}', 'exec'), {})

    def gate(self, head=SHA, dirty=b''):
        def runner(argv, **kwargs):
            self.assertEqual(argv[:2], ['git', '--no-optional-locks'])
            return head + '\n' if argv[2] == 'rev-parse' else dirty
        self.execute('gate', runner)

    def registry(self, argv, **kwargs):
        self.assertEqual(argv[:4], ['docker', 'buildx', 'imagetools', 'inspect'])
        if argv[-1] == '{{json .Manifest}}':
            self.assertEqual(argv[4], IMAGE + ':' + SHA)
            return json.dumps(self.manifest)
        self.assertEqual(argv[-1], '{{json .Image}}')
        self.assertEqual(argv[4], IMAGE + '@' + DIGEST)
        return json.dumps(self.config)

    def test_trigger_and_every_mandatory_dependency(self):
        self.assertEqual(set(WORKFLOW['on']), {'push', 'pull_request'})
        self.assertEqual(set(JOB['needs']), staging.REQUIRED_JOBS)
        self.assertEqual(set(WORKFLOW['jobs']), staging.REQUIRED_JOBS | {'publish-ghcr'})
        self.assertNotIn('continue-on-error', JOB)
        self.assertTrue(all('continue-on-error' not in step and 'if' not in step for step in JOB['steps']))
        expression = JOB['if'].removeprefix('${{').removesuffix('}}').replace('&&', 'and').strip()
        for event, ref, repo, success in [
            ('push', 'refs/heads/main', staging.REPO, True),
            ('pull_request', 'refs/heads/main', staging.REPO, True),
            ('push', 'refs/heads/feature', staging.REPO, True),
            ('push', 'refs/tags/main', staging.REPO, True),
            ('push', 'refs/heads/main', 'other/repo', True),
            ('push', 'refs/heads/main', staging.REPO, False),
        ]:
            expected = event == 'push' and ref == 'refs/heads/main' and repo == staging.REPO and success
            github = SimpleNamespace(event_name=event, ref=ref, repository=repo)
            self.assertEqual(eval(expression, {'__builtins__': {}},
                                  {'github': github, 'success': lambda: success}), expected)
        self.gate()

    def test_gate_denies_each_failed_skipped_cancelled_or_missing_mandatory_job(self):
        for name in staging.REQUIRED_JOBS:
            for result in ('failure', 'skipped', 'cancelled', None):
                with self.subTest(job=name, result=result):
                    self.needs[name]['result'] = result
                    with self.assertRaisesRegex(SystemExit, 'must succeed'):
                        self.gate()
            self.needs[name]['result'] = 'success'
        self.needs.pop('docker')
        with self.assertRaisesRegex(SystemExit, 'inventory'):
            self.gate()

    def test_gate_denies_unverified_compose_and_untrusted_event(self):
        self.needs['compose-runtime']['outputs'] = {}
        with self.assertRaisesRegex(SystemExit, 'Compose verification'):
            self.gate()
        self.needs['compose-runtime']['outputs'] = {'verified': 'true'}
        for key, value in [('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REF', 'refs/heads/feature'),
                           ('GITHUB_REPOSITORY', 'other/repo')]:
            with self.subTest(key=key), patch.dict(self.env, {key: value}):
                with self.assertRaisesRegex(SystemExit, 'restricted'):
                    self.gate()

    def test_exact_clean_checkout(self):
        checkout = JOB['steps'][0]
        self.assertEqual(checkout['with'], {'ref': '${{ github.sha }}', 'persist-credentials': 'false'})
        with self.assertRaisesRegex(SystemExit, 'SHA mismatch'):
            self.gate(head='d' * 40)
        with self.assertRaisesRegex(SystemExit, 'clean'):
            self.gate(dirty=b' M backend/Dockerfile\n')

    def test_job_permissions_credentials_and_no_deploy(self):
        self.assertEqual(WORKFLOW['permissions'], {'contents': 'read'})
        self.assertEqual(JOB['permissions'], {'contents': 'read', 'packages': 'write'})
        for name, job in WORKFLOW['jobs'].items():
            if name != 'publish-ghcr':
                self.assertNotIn('packages', job.get('permissions', {}))
        text = json.dumps(JOB)
        self.assertEqual(re.findall(r'secrets\.([A-Z_]+)', text), ['GITHUB_TOKEN'])
        self.assertNotIn('environment', JOB)
        self.assertEqual(JOB['runs-on'], 'ubuntu-latest')
        self.assertNotRegex(text.lower(), r'\b(ssh|scp|aeza|deploy-staging|self-hosted)\b')
        login = next(step for step in JOB['steps'] if step.get('uses', '').startswith('docker/login-action@'))
        self.assertEqual(login['with'], {'registry': 'ghcr.io', 'username': '${{ github.actor }}',
                                        'password': '${{ secrets.GITHUB_TOKEN }}'})
        for step in JOB['steps']:
            if 'uses' in step:
                self.assertRegex(step['uses'], r'^(actions|docker)/[\w-]+@[0-9a-f]{40}$')

    def test_build_inputs_and_artifact_are_bounded(self):
        self.assertEqual(STEPS['build']['with'], {
            'context': '.', 'file': 'backend/Dockerfile', 'platforms': 'linux/amd64',
            'push': 'true', 'provenance': 'false', 'sbom': 'false',
            'tags': '${{ env.IMAGE_NAME }}:${{ github.sha }}',
            'build-args': 'VITE_BACKEND_PUBLIC_URL=https://staging.hookahtootah.club',
            'labels': 'org.opencontainers.image.source=' + SOURCE + '\n'
                      'org.opencontainers.image.revision=${{ github.sha }}\n'})
        self.assertEqual(JOB['env']['DOCKER_BUILD_RECORD_UPLOAD'], 'false')
        self.assertEqual(STEPS['candidate']['env']['BUILD_DIGEST'], '${{ steps.build.outputs.digest }}')
        upload = JOB['steps'][-1]['with']
        self.assertEqual(upload['name'], 'ghcr-candidate-${{ github.sha }}-${{ github.run_attempt }}')
        self.assertEqual(upload['path'].splitlines(), [
            '${{ runner.temp }}/ghcr-candidate/candidate.json',
            '${{ runner.temp }}/ghcr-candidate/build-metadata.json'])
        self.assertEqual(upload['if-no-files-found'], 'error')

    def test_registry_digest_not_image_id_and_safe_metadata(self):
        self.execute('candidate', self.registry)
        candidate = json.loads((self.output / 'candidate.json').read_text())
        self.assertEqual(candidate, {'image': IMAGE + '@' + DIGEST, 'revision': SHA, 'ci_run': 123})
        self.assertNotEqual(candidate['image'], IMAGE + '@' + IMAGE_ID)
        metadata = (self.output / 'build-metadata.json').read_text()
        self.assertNotIn('DO_NOT_EXPORT', metadata)
        self.assertEqual(set(json.loads(metadata)), {
            'image', 'revision', 'ci_run', 'platform', 'source', 'registry_manifest_digest',
            'media_type', 'run_attempt'})
        self.assertEqual((self.output / 'candidate.json').stat().st_mode & 0o777, 0o600)

    def test_bad_digest_missing_digest_or_index_never_creates_candidate(self):
        for field, value in [('digest', IMAGE_ID), ('digest', None),
                             ('mediaType', 'application/vnd.oci.image.index.v1+json')]:
            with self.subTest(field=field, value=value), patch.dict(self.manifest, {field: value}):
                with self.assertRaises(SystemExit):
                    self.execute('candidate', self.registry)
                self.assertFalse(self.output.exists())

    def test_wrong_platform_source_revision_or_volumes_never_creates_candidate(self):
        original = copy.deepcopy(self.config)
        for kind in ('os', 'architecture', 'source', 'revision', 'Volumes'):
            self.config = copy.deepcopy(original)
            if kind in ('os', 'architecture'):
                self.config[kind] = 'wrong'
            elif kind == 'Volumes':
                self.config['config'][kind] = {'/data': {}}
            else:
                self.config['config']['Labels']['org.opencontainers.image.' + kind] = 'wrong'
            with self.subTest(kind=kind), self.assertRaises(SystemExit):
                self.execute('candidate', self.registry)
            self.assertFalse(self.output.exists())

    def test_invalid_inputs_and_registry_error_never_create_candidate(self):
        for key, value in [('GITHUB_SHA', 'main'), ('BUILD_DIGEST', ''),
                           ('GITHUB_RUN_ID', '0'), ('IMAGE_NAME', 'other/backend')]:
            with self.subTest(key=key), patch.dict(self.env, {key: value}):
                with self.assertRaises(SystemExit):
                    self.execute('candidate', self.registry)
                self.assertFalse(self.output.exists())
        with self.assertRaises(subprocess.CalledProcessError):
            self.execute('candidate', lambda *a, **kw: (_ for _ in ()).throw(
                subprocess.CalledProcessError(1, 'docker')))
        self.assertFalse(self.output.exists())

    def test_existing_installer_accepts_candidate_only_after_full_success(self):
        self.execute('candidate', self.registry)
        candidate = staging.private_json(self.output / 'candidate.json')
        run = {'id': 123, 'run_attempt': 1, 'head_sha': SHA, 'event': 'push',
               'path': '.github/workflows/ci.yml',
               'repository': {'full_name': staging.REPO}, 'head_repository': {'full_name': staging.REPO},
               'status': 'completed', 'conclusion': 'success', 'html_url': SOURCE + '/actions/runs/123'}
        jobs = [{'id': number, 'run_id': 123, 'head_sha': SHA, 'name': name,
                 'status': 'completed', 'conclusion': 'success'}
                for number, name in enumerate(sorted(staging.REQUIRED_JOBS | {'publish-ghcr'}), 1)]
        base = f'https://api.github.com/repos/{staging.REPO}/actions/runs/123'
        jobs_url = base + '/jobs?filter=latest&per_page=100&page=1'
        requests = []

        def http(request):
            requests.append(request.full_url)
            self.assertIn(request.full_url, (base, jobs_url))
            value = run if request.full_url == base else {'total_count': len(jobs), 'jobs': jobs}
            body = json.dumps(value).encode()
            headers = Message()
            headers['Content-Length'] = str(len(body))
            response = urllib.response.addinfourl(io.BytesIO(body), headers, request.full_url, 200)
            response.msg = 'fixture'
            return response

        jar = io.BytesIO()
        with zipfile.ZipFile(jar, 'w') as archive:
            for n in range(1, 127):
                archive.writestr(f'db/migration/postgresql/V{n}__fixture.sql', 'SELECT 1;\n')
        tar = io.BytesIO()
        with tarfile.open(fileobj=tar, mode='w') as archive:
            info = tarfile.TarInfo('app-0.1.0.jar')
            info.size = len(jar.getvalue())
            archive.addfile(info, io.BytesIO(jar.getvalue()))

        def runner(argv, **kwargs):
            if argv[:2] == ['docker', 'create']:
                return b'fixture-container'
            if argv[:2] == ['docker', 'cp']:
                return tar.getvalue()
            self.assertEqual(argv, ['docker', 'rm', 'fixture-container'])
            return b''

        runtime = staging.Staging.__new__(staging.Staging)
        runtime.config = {'runtime': {}}  # Historical two-service candidate, PDF opt-in absent.
        runtime.run, runtime.project = runner, 'fixture'
        inspected = {'Id': IMAGE_ID, 'Config': self.config['config']}
        with patch.object(staging.urllib.request.HTTPSHandler, 'https_open', side_effect=http), \
                patch.object(runtime, 'inspect_image', return_value=inspected):
            result = runtime.candidate(candidate)
            self.assertEqual(result['image'], IMAGE + '@' + DIGEST)
            self.assertEqual(result['id'], IMAGE_ID)
            self.assertEqual(len(result['manifest']), 126)
            self.assertEqual(requests, [base, jobs_url, base])
            for status, conclusion in [('in_progress', None), ('completed', 'failure')]:
                run.update(status=status, conclusion=conclusion)
                requests.clear()
                with self.assertRaisesRegex(staging.Refused, 'EXACT_CI_NOT_GREEN'):
                    runtime.candidate(candidate)
                self.assertEqual(requests, [base])
            run.update(status='completed', conclusion='success')
            jobs[-1]['conclusion'] = 'failure'
            requests.clear()
            with self.assertRaisesRegex(staging.Refused, 'REQUIRED_CI_JOB_NOT_GREEN'):
                runtime.candidate(candidate)
            self.assertEqual(requests, [base, jobs_url])


if __name__ == '__main__':
    unittest.main()
