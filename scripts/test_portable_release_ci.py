"""Negative evidence tests for the actual portable-browser CI result gate."""
import copy
from pathlib import Path
import unittest

from check_portable_release_results import required_titles, validate


class PortableResultGateTest(unittest.TestCase):
    def setUp(self):
        self.report = {'stats': {'expected': 2 * len(required_titles()), 'unexpected': 0, 'flaky': 0, 'skipped': 0},
                       'errors': [], 'suites': [{'file': 'portable-release.spec.ts', 'specs': [
                           {'title': title, 'tests': [{'projectName': project, 'status': 'expected',
                            'expectedStatus': 'passed', 'results': [{'status': 'passed', 'retry': 0, 'errors': []}]}]}
                           for project in ('chromium', 'webkit') for title in sorted(required_titles())]}]}

    def test_complete_report_passes(self):
        self.assertEqual(validate(self.report), 54)

    def test_existing_browser_job_requires_the_build_suite_and_result_gate(self):
        root = Path(__file__).resolve().parent.parent
        workflow = (root / '.github/workflows/ci.yml').read_text()
        job = workflow.split('  miniapp-e2e-smoke:\n', 1)[1].split('\n  docker:', 1)[0]
        self.assertIn('VITE_BACKEND_PUBLIC_URL: https://unexpected-api.invalid', job)
        self.assertIn('NODE_ENV: production', job)
        self.assertIn('run: npm run e2e:portable -- --reporter=list,json', job)
        self.assertIn('PLAYWRIGHT_JSON_OUTPUT_FILE: playwright-portable-results.json', job)
        self.assertIn('if: ${{ !cancelled() }}\n        run: python3 -B scripts/check_portable_release_results.py miniapp/playwright-portable-results.json', job)
        self.assertLess(job.index('Build portable Mini App release'), job.index('Run portable release on Chromium'))
        self.assertLess(job.index('Run portable release on Chromium'), job.index('Assert portable release browser results'))
        self.assertLess(job.index('Assert portable release browser results'), job.index('Run full Mini App browser smoke'))
        self.assertNotIn('continue-on-error', job)

    def test_each_case_and_each_engine_are_mandatory(self):
        for index in range(54):
            report = copy.deepcopy(self.report)
            del report['suites'][0]['specs'][index]
            with self.subTest(index=index), self.assertRaises(ValueError):
                validate(report)

    def test_duplicate_case_cannot_replace_missing_case(self):
        self.report['suites'][0]['specs'][0] = copy.deepcopy(self.report['suites'][0]['specs'][1])
        with self.assertRaises(ValueError):
            validate(self.report)

    def test_skips_retries_failures_missing_results_and_wrong_expected_status_fail(self):
        for mutation in ({'status': 'skipped'}, {'expectedStatus': 'failed'}, {'results': []},
                         {'results': [{'status': 'failed', 'retry': 0, 'errors': []}]},
                         {'results': [{'status': 'passed', 'retry': 1, 'errors': []}]}):
            report = copy.deepcopy(self.report)
            report['suites'][0]['specs'][0]['tests'][0].update(mutation)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate(report)

    def test_nonpassing_stats_and_runner_errors_fail(self):
        for field in ('expected', 'unexpected', 'flaky', 'skipped'):
            report = copy.deepcopy(self.report)
            report['stats'][field] += 1
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate(report)
        self.report['errors'] = [{'message': 'Runner failed'}]
        with self.assertRaises(ValueError):
            validate(self.report)


if __name__ == '__main__':
    unittest.main()
