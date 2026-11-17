"""Require the built-release origin regressions in both browser engines."""
import json
from pathlib import Path
import sys


def required_titles():
    titles = set()
    for environment in ('staging', 'production'):
        titles.update(f'release {environment} {mode} uses its own API origin'
                      for mode in ('guest', 'venue', 'platform'))
        titles.add(f'release {environment} media and PDF stay scoped to their origin')
        titles.update(f'release {environment} {auth} authentication never falls back'
                      for auth in ('denied', 'offline', 'redirect'))
        titles.add(f'release {environment} missing initData sends no authentication')
        titles.add(f'release {environment} API and binary redirects never cross origins')
    titles.update({
        'release sessions refresh and remain isolated by origin and account',
        'release assets contain no configured backend fallback',
        'dev supports distinct frontend and backend localhost ports',
        'dev without override keeps localhost backend',
        'dev accepts explicit HTTPS tunnel origin',
    })
    titles.update(f'dev rejects invalid backend origin {label}'
                  for label in ('remoteHttp', 'credentials', 'path', 'malformed'))
    return titles


def validate(report):
    expected = {(project, title) for project in ('chromium', 'webkit') for title in required_titles()}
    stats = report.get('stats', {})
    if (not {'expected', 'unexpected', 'flaky', 'skipped'} <= set(stats) or
            any(type(stats.get(key)) is not int for key in ('expected', 'unexpected', 'flaky', 'skipped')) or
            stats['expected'] != len(expected) or any(stats[key] for key in ('unexpected', 'flaky', 'skipped')) or
            report.get('errors') != []):
        raise ValueError('Portable browser stats are missing, incomplete or non-passing')
    observed = set()

    def visit(suite, inherited_file=None):
        filename = suite.get('file', inherited_file)
        for spec in suite.get('specs', []):
            if Path(spec.get('file', filename or '')).name != 'portable-release.spec.ts':
                raise ValueError('Unexpected portable browser spec')
            for case in spec.get('tests', []):
                identity = (case.get('projectName'), spec.get('title'))
                results = case.get('results', [])
                if (identity not in expected or identity in observed or case.get('status') != 'expected' or
                        case.get('expectedStatus') != 'passed' or len(results) != 1 or
                        results[0].get('status') != 'passed' or results[0].get('retry') != 0 or
                        results[0].get('errors') != []):
                    raise ValueError(f'Missing, duplicate or non-passing portable browser case: {identity}')
                observed.add(identity)
        for child in suite.get('suites', []):
            visit(child, filename)

    for suite in report['suites']:
        visit(suite)
    if observed != expected:
        raise ValueError(f'Incomplete portable browser coverage: {len(observed)}/{len(expected)}')
    return len(observed)


if __name__ == '__main__':
    try:
        count = validate(json.loads(Path(sys.argv[1]).read_text()))
    except (IndexError, OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        raise SystemExit(f'Portable release browser gate failed: {error}') from error
    print(f'Portable release browser gate PASS: {count} cases, Chromium and WebKit, no skips/retries')
