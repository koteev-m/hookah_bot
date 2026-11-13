#!/usr/bin/env python3
"""Exercise the actual CI billing result gate with isolated JUnit copies (no DB writes)."""
import ast
import contextlib
from datetime import datetime, timedelta, timezone
import io
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / '.github/workflows/ci.yml'
H2 = 'com.hookah.platform.backend.analytics.PlatformBillingKpiH2Test'
PG = 'com.hookah.platform.backend.analytics.PlatformBillingKpiPostgresTest'
DENSE = 'dense PostgreSQL snapshot preserves results without a payment scan per invoice'
REPORT_PATH = 'backend/app/build/test-results/test'
MARKER_PATH = 'backend/app/build/analytics-billing-ci-run.json'
IDENTITY = ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_SHA')


def step(workflow, name):
    return workflow.split('      - name: ' + name + '\n', 1)[1].split('\n      - name:', 1)[0]


def python_step(workflow, name):
    code = re.search(r"          python3 - <<'PY'\n(.*?)\n          PY", step(workflow, name), re.S).group(1)
    return '\n'.join(line[10:] for line in code.splitlines())


def literals(code):
    return {node.targets[0].id: ast.literal_eval(node.value)
            for node in ast.parse(code).body
            if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
            and node.targets[0].id in {'expected_classes', 'required_minimums', 'required_test_names', 'billing_classes'}}


class AnalyticsBillingCiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text()
        cls.code = python_step(cls.workflow, 'Assert route and security test results')
        cls.prepare = python_step(cls.workflow, 'Prepare fresh analytics billing evidence')
        cls.inventory = literals(cls.code)
        cls.native_reports = os.environ.get('BILLING_CI_TEST_REPORTS')
        cls.native_marker = os.environ.get('BILLING_CI_TEST_MARKER')
        if bool(cls.native_reports) != bool(cls.native_marker):
            raise AssertionError('native report copies and their matching run marker must be supplied together')

    def scenario(self, mutate=None, passing=False):
        with tempfile.TemporaryDirectory(prefix='analytics-billing-ci-') as directory:
            base = Path(directory)
            reports = base / 'reports'
            reports.mkdir()
            marker = base / 'run.json'
            now = datetime.now(timezone.utc)
            metadata = json.loads(Path(self.native_marker).read_text()) if self.native_marker else {
                'GITHUB_RUN_ID': 'synthetic-parser-test', 'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_SHA': 'a' * 40,
                'startedAt': (now - timedelta(seconds=2)).isoformat(),
            }
            marker.write_text(json.dumps(metadata))
            for name in self.inventory['expected_classes']:
                report = reports / f'TEST-{name}.xml'
                if self.native_reports:
                    shutil.copy2(Path(self.native_reports) / report.name, report)
                    continue
                names = sorted(self.inventory['required_test_names'].get(name, set()))
                minimum = self.inventory['required_minimums'].get(name, 1)
                names += [f'parser fixture {index}' for index in range(max(minimum - len(names), 0))]
                suite = ET.Element('testsuite', name=name, tests=str(len(names)), skipped='0', failures='0', errors='0',
                                   timestamp=(now - timedelta(seconds=1)).isoformat(), time='0.1')
                for case in names:
                    ET.SubElement(suite, 'testcase', name=case + '()', classname=name, time='0.01')
                ET.ElementTree(suite).write(report)
            env = {key: metadata[key] for key in IDENTITY}
            if mutate:
                mutate(reports, marker, env)
            code = self.code.replace(REPORT_PATH, str(reports)).replace(MARKER_PATH, str(marker))
            output = io.StringIO()
            error = None
            with patch.dict(os.environ, env), contextlib.redirect_stdout(output):
                try:
                    exec(compile(code, 'actual CI route/security gate', 'exec'), {})
                except (Exception, SystemExit) as caught:
                    error = caught
            if passing:
                self.assertIsNone(error, f'{error}\n{output.getvalue()}')
                self.assertIn('executed=', output.getvalue())
            else:
                self.assertIsNotNone(error, 'invalid billing evidence unexpectedly passed the actual CI gate')

    @staticmethod
    def edit(name, edit):
        def mutate(reports, marker, env):
            path = reports / f'TEST-{name}.xml'
            tree = ET.parse(path)
            edit(tree.getroot())
            tree.write(path)
        return mutate

    def test_workflow_selects_exact_classes_in_existing_docker_job(self):
        body = self.workflow.split('  backend-release-critical-routes:\n', 1)[1].split('\n  backend-venue-booking-rbac:', 1)[0]
        run = step(body, 'Run guest venue platform route and security tests')
        selectors = re.findall(r'--tests "([^"]+)"', run)
        for name, minimum in [(H2, 5), (PG, 6)]:
            self.assertEqual(selectors.count(name), 1)
            self.assertEqual(self.inventory['expected_classes'].count(name), 1)
            self.assertEqual(self.inventory['required_minimums'][name], minimum)
            self.assertIn(name, self.inventory['billing_classes'])
        self.assertIn(DENSE, self.inventory['required_test_names'][PG])
        self.assertIn(':backend:app:test --rerun', run)
        self.assertIn('--no-build-cache', run)
        self.assertIn('TZ: UTC', run)
        self.assertIn('docker info', step(body, 'Verify Docker availability (required for Testcontainers)'))
        self.assertIn('scripts/test_analytics_billing_ci.py', step(body, 'Verify analytics billing CI contract'))
        self.assertLess(body.index('Prepare fresh analytics billing evidence'), body.index('Run guest venue platform route'))
        self.assertLess(body.index('Run guest venue platform route'), body.index('Assert route and security test results'))
        self.assertIn('if: ${{ !cancelled() }}', step(body, 'Assert route and security test results'))
        self.assertIn('  push:\n    branches: [main]\n  pull_request:\n    branches: [main]', self.workflow)
        source = (ROOT / 'backend/app/src/test/kotlin/com/hookah/platform/backend/analytics/PlatformBillingKpiTest.kt').read_text()
        self.assertIn('fun `' + DENSE + '`', source)
        self.assertIn('assertEquals(12012,', source)
        self.assertIn('assertEquals(10010,', source)
        self.assertIn('PostgresTestEnv.createDataSource(PostgresTestEnv.createDatabase())', source)

    def test_prepare_binds_marker_to_current_run_attempt_and_sha(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / 'marker.json'
            env = dict(zip(IDENTITY, ('test-run', '2', 'b' * 40)))
            with patch.dict(os.environ, env):
                exec(compile(self.prepare.replace(MARKER_PATH, str(marker)), 'actual CI prepare', 'exec'), {})
            data = json.loads(marker.read_text())
            self.assertEqual({key: data[key] for key in IDENTITY}, env)
            self.assertLessEqual(datetime.fromisoformat(data['startedAt']), datetime.now(timezone.utc))

    def test_positive_all_mandatory_results_pass(self):
        self.scenario(passing=True)

    def test_missing_h2_postgres_or_both_fail(self):
        for names in [(H2,), (PG,), (H2, PG)]:
            with self.subTest(names=names):
                self.scenario(lambda reports, marker, env: [(reports / f'TEST-{name}.xml').unlink() for name in names])

    def test_too_few_executed_cases_fail_even_with_forged_summary(self):
        for name in [H2, PG]:
            for forged in [False, True]:
                with self.subTest(name=name, forged=forged):
                    def edit(suite):
                        suite.remove(suite.findall('testcase')[0])
                        if not forged:
                            suite.set('tests', str(len(suite.findall('testcase'))))
                    self.scenario(self.edit(name, edit))

    def test_missing_dense_case_fails_with_six_other_successes(self):
        def edit(suite):
            for case in suite.findall('testcase'):
                if case.attrib['name'].removesuffix('()') == DENSE:
                    case.set('name', 'different successful case()')
        self.scenario(self.edit(PG, edit))

    def test_skip_failure_or_error_fails_in_summary_or_case(self):
        for name in [H2, PG]:
            for tag, counter in [('skipped', 'skipped'), ('failure', 'failures'), ('error', 'errors')]:
                for level in ['summary', 'case', 'both']:
                    with self.subTest(name=name, tag=tag, level=level):
                        def edit(suite):
                            if level != 'case':
                                suite.set(counter, '1')
                            if level != 'summary':
                                ET.SubElement(suite.find('testcase'), tag)
                        self.scenario(self.edit(name, edit))

    def test_wrong_suite_or_testcase_identity_fails(self):
        for name in [H2, PG]:
            for target in ['suite', 'case']:
                with self.subTest(name=name, target=target):
                    self.scenario(self.edit(name, lambda s: s.set('name', 'wrong.Class') if target == 'suite'
                                            else s.find('testcase').set('classname', 'wrong.Class')))

    def test_stale_suite_timestamp_and_file_mtime_fail(self):
        for name in [H2, PG]:
            for target in ['timestamp', 'mtime']:
                with self.subTest(name=name, target=target):
                    def mutate(reports, marker, env):
                        old = datetime.fromisoformat(json.loads(marker.read_text())['startedAt']) - timedelta(seconds=1)
                        path = reports / f'TEST-{name}.xml'
                        if target == 'mtime':
                            os.utime(path, (old.timestamp(), old.timestamp()))
                        else:
                            tree = ET.parse(path)
                            tree.getroot().set('timestamp', old.isoformat())
                            tree.write(path)
                    self.scenario(mutate)

    def test_wrong_run_attempt_sha_or_missing_marker_fail(self):
        for key in [*IDENTITY, 'missing']:
            with self.subTest(key=key):
                def mutate(reports, marker, env):
                    if key == 'missing':
                        marker.unlink()
                    else:
                        data = json.loads(marker.read_text())
                        data[key] = 'previous-run'
                        marker.write_text(json.dumps(data))
                self.scenario(mutate)

    def test_missing_ci_identity_fails(self):
        self.scenario(lambda reports, marker, env: env.update(GITHUB_RUN_ID=''))

    def test_truncated_malformed_empty_or_wrong_root_xml_fail(self):
        for content in ['', '<testsuite', '<testsuites/>', '<testsuite name="wrong"/>']:
            with self.subTest(content=content):
                self.scenario(lambda reports, marker, env: (reports / f'TEST-{PG}.xml').write_text(content))

    def test_missing_required_metadata_fails(self):
        for key in ['tests', 'skipped', 'failures', 'errors', 'timestamp', 'time']:
            with self.subTest(key=key):
                self.scenario(self.edit(PG, lambda s: s.attrib.pop(key)))

    def test_future_naive_or_invalid_timestamp_fails(self):
        for stamp in [(datetime.now(timezone.utc) + timedelta(days=1)).isoformat(), '2026-01-01T00:00:00', 'invalid']:
            with self.subTest(timestamp=stamp):
                self.scenario(self.edit(PG, lambda s: s.set('timestamp', stamp)))

    def test_duplicate_unnamed_nested_or_unexecuted_cases_fail(self):
        edits = [lambda s: s.findall('testcase')[1].set('name', s.findall('testcase')[0].attrib['name']),
                 lambda s: s.find('testcase').attrib.pop('name'),
                 lambda s: s.find('testcase').set('status', 'notrun')]
        def nested(suite):
            case = suite.find('testcase')
            suite.remove(case)
            ET.SubElement(suite, 'testsuite').append(case)
        for edit in [*edits, nested]:
            with self.subTest(edit=edit):
                self.scenario(self.edit(PG, edit))

    def test_invalid_case_duration_fails_but_real_zero_is_valid(self):
        for value in ['nan', '-1', 'invalid']:
            with self.subTest(value=value):
                self.scenario(self.edit(H2, lambda s: s.find('testcase').set('time', value)))
        self.scenario(self.edit(H2, lambda s: s.find('testcase').set('time', '0')), passing=True)

    def test_old_mandatory_suite_and_minimum_still_fail_closed(self):
        name = 'com.hookah.platform.backend.analytics.KpiReadModelsH2Test'
        self.scenario(lambda reports, marker, env: (reports / f'TEST-{name}.xml').unlink())
        self.scenario(self.edit(name, lambda s: s.set('tests', '15')))

    def test_operations_postgres_http_is_mandatory_and_cannot_be_skipped(self):
        for name in ('com.hookah.platform.backend.analytics.VenueProductsCategoryPostgresHttpTest',
                     'com.hookah.platform.backend.analytics.VenueOperationsPostgresHttpTest',
                     'com.hookah.platform.backend.analytics.VenueBookingAttentionPostgresHttpTest'):
            self.assertEqual(self.inventory['required_minimums'][name], 1)
            self.scenario(lambda reports, marker, env: (reports / f'TEST-{name}.xml').unlink())
            self.scenario(self.edit(name, lambda suite: suite.set('skipped', '1')))
            self.scenario(self.edit(name, lambda suite: ET.SubElement(suite.find('testcase'), 'skipped')))


    def test_venue_review_kpi_cases_are_mandatory_and_cannot_be_skipped(self):
        for suffix in ('H2', 'Postgres'):
            name = f'com.hookah.platform.backend.analytics.KpiReadModels{suffix}Test'
            cases = {case for case in self.inventory['required_test_names'][name]
                     if case.startswith(('accounting EDIT', 'ambiguous accounting', 'completed additions',
                                         'booking attention', 'product category', 'product grouped', 'product search', 'product currency'))}
            self.assertEqual(len(cases), 9)
            for case in cases:
                def replace(suite):
                    target = next(t for t in suite.findall('testcase') if t.get('name').removesuffix('()') == case)
                    target.set('name', 'unrelated replacement()')
                self.scenario(self.edit(name, replace))
                def skip(suite):
                    target = next(t for t in suite.findall('testcase') if t.get('name').removesuffix('()') == case)
                    ET.SubElement(target, 'skipped')
                    suite.set('skipped', '1')
                self.scenario(self.edit(name, skip))

    def test_venue_review_browser_cases_are_mandatory_and_cannot_be_skipped(self):
        code = python_step(self.workflow, 'Assert full Mini App browser smoke results')
        inventory = next(ast.literal_eval(node.value) for node in ast.parse(code).body
                         if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
                         and node.targets[0].id == 'required_specs')
        specs = [{'file': file, 'title': title,
                  'tests': [{'status': 'expected', 'expectedStatus': 'passed', 'results': [{'status': 'passed'}]}
                            for _ in range(count)]} for (file, title), count in inventory.items()]
        reviews = [index for index, spec in enumerate(specs) if spec['title'].startswith('review F') or spec['file'] in {'venue-booking-attention.spec.ts', 'venue-products-category.spec.ts'}]
        self.assertEqual(len(reviews), 27)
        minimum = next(ast.literal_eval(node.value) for node in ast.parse(code).body
                       if isinstance(node, ast.Assign) and isinstance(node.targets[0], ast.Name)
                       and node.targets[0].id == 'minimum_executed')
        while sum(len(spec['tests']) for spec in specs) < minimum:
            specs.append({'file': 'additional.spec.ts', 'title': f'additional {len(specs)}', 'tests': [
                {'status': 'expected', 'expectedStatus': 'passed', 'results': [{'status': 'passed'}]}]})
        with tempfile.TemporaryDirectory(prefix='venue-review-browser-ci-') as directory:
            report = Path(directory) / 'browser.json'
            def check(index=None, skipped=False):
                current = json.loads(json.dumps(specs))
                count = sum(len(spec['tests']) for spec in current)
                if index is not None:
                    if skipped:
                        current[index]['tests'][0]['status'] = 'skipped'
                    else:
                        current[index]['title'] = 'unrelated replacement'
                report.write_text(json.dumps({'suites': [{'specs': current}], 'errors': [], 'stats': {
                    'expected': count - int(skipped), 'unexpected': 0, 'flaky': 0, 'skipped': int(skipped)}}))
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    exec(compile(code.replace('Path("playwright-results.json")', f'Path({str(report)!r})'),
                                 'actual browser CI gate', 'exec'), {})
            check()
            for index in reviews:
                with self.assertRaises(SystemExit):
                    check(index)
                with self.assertRaises(SystemExit):
                    check(index, skipped=True)



if __name__ == '__main__':
    unittest.main(verbosity=2)
