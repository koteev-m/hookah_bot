import { expect, test, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { platformHttp, platformResponse, type PlatformVariant } from './fixtures/platform-analytics'
import { platformGetAnalyticsV1, type PlatformAnalyticsQuery } from '../src/shared/api/platformAnalyticsApi'

test.use({ timezoneId: 'America/Los_Angeles' })

type Options = { variant?: PlatformVariant; role?: string; ignoreAbort?: boolean; live?: boolean; entry?: boolean }
async function fixture(page: Page, options: Options = {}) {
  let variant = options.variant ?? 'complete', role = options.role ?? 'PLATFORM_OWNER', failure = 0
  let hold: Promise<void> | null = null, corrupt: ((body: ReturnType<typeof platformResponse>) => void) | null = null
  const requests: URL[] = []; const methods: string[] = []
  const live = options.live && process.env.PLATFORM_ANALYTICS_LIVE_DIR
    ? JSON.parse(readFileSync(`${process.env.PLATFORM_ANALYTICS_LIVE_DIR}/ready.json`, 'utf8')) as { base: string; token: string } : null
  await page.addInitScript(({ ignoreAbort, token }) => {
    const handlers = new Map<string, Set<() => void>>()
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 99 } }, ready() {}, expand() {},
      onEvent(name: string, callback: () => void) { if (!handlers.has(name)) handlers.set(name, new Set()); handlers.get(name)!.add(callback) },
      offEvent(name: string, callback: () => void) { handlers.get(name)?.delete(callback) } } },
    emitAnalyticsEvent: (name: string) => handlers.get(name)?.forEach(callback => callback()) })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:99`, JSON.stringify({ token, expiresAtEpochSeconds: 4102444800 }))
    if (ignoreAbort) { const Original = window.AbortController; window.AbortController = class extends Original { abort() {} } }
  }, { ignoreAbort: options.ignoreAbort ?? false, token: live?.token ?? 'test-platform-analytics' })
  await page.route('**/miniapp/platform-analytics-test*', route => route.fulfill({ contentType: 'text/html', body:
    `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"></head><body><div id="app"></div><script type="module">
    import {mountPlatformApp} from '/miniapp/src/screens/platformApp.ts';
    import {clearSession} from '/miniapp/src/shared/api/auth.ts';
    import {bindAppTheme} from '/miniapp/src/shared/ui/appTheme.ts'; bindAppTheme(document.documentElement);
    window.logoutAnalytics=clearSession;
    mountPlatformApp({root:document.querySelector('#app'),backendUrl:location.origin});
    </script></body></html>` }))
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    methods.push(route.request().method())
    if (live && (url.pathname === '/api/platform/me' || url.pathname === '/api/platform/analytics/v1')) {
      if (url.pathname.endsWith('/analytics/v1')) requests.push(url)
      // Forward to real loopback Ktor. The JSON is neither mocked nor transformed.
      const response = await route.fetch({ url: `${live.base}${url.pathname}${url.search}` })
      return route.fulfill({ response })
    }
    if (url.pathname === '/api/platform/me') return route.fulfill(role === 'PLATFORM_OWNER' ? { json: { ok: true, ownerUserId: 99 } } : { status: 403, json: { error: { code: 'FORBIDDEN' } } })
    if (url.pathname === '/api/platform/analytics/v1') {
      requests.push(url); expect(route.request().headers().authorization).toMatch(/^Bearer /)
      const body = platformResponse(url, variant); corrupt?.(body); const failed = failure
      if (hold) await hold
      return route.fulfill(failed ? { status: failed, json: { error: { code: failed === 403 ? 'FORBIDDEN' : failed === 401 ? 'UNAUTHORIZED' : 'INTERNAL_ERROR', message: 'SQL_SECRET_do_not_display' } } } : { json: body }).catch(() => {})
    }
    if (url.pathname === '/api/platform/venues') return route.fulfill({ json: { venues: [] } })
    if (url.pathname === '/api/platform/support/threads') return route.fulfill({ json: { threads: [] } })
    if (url.pathname.includes('onboarding')) return route.fulfill({ json: { requests: [] } })
    return route.fulfill({ json: { items: [] } })
  })
  await page.goto(`platform-analytics-test#/${options.entry ? 'venues' : 'analytics'}`)
  return { requests, methods, live: !!live, setVariant(value: PlatformVariant) { variant = value }, fail(value: number) { failure = value },
    hold(value: Promise<void> | null) { hold = value }, role(value: string) { role = value }, corrupt(value: typeof corrupt) { corrupt = value } }
}
const ready = (page: Page) => expect(page.locator('.platform-analytics .stats-content .stats-section').first()).toBeVisible()
const metric = (page: Page, key: string, section: string) => page.locator(`#analytics-${section} [data-kpi="${key}"]`)
const refresh = (page: Page) => page.getByRole('button', { name: 'Обновить аналитику', exact: true }).click()
async function openAll(page: Page) { for (const key of ['venues', 'subscriptions', 'invoices', 'onboarding', 'support']) await page.locator(`#analytics-${key} > summary`).click() }
async function custom(page: Page, from: string, to: string) {
  await page.getByRole('button', { name: 'Произвольный период' }).click()
  await page.getByLabel('Начало периода').fill(from); await page.getByLabel('Окончание периода (включительно)').fill(to)
  await page.getByRole('button', { name: 'Показать период' }).click()
}
const number = (text: string) => text.replace(/[^\d,−-]/g, '').replace('−', '-')
async function late(page: Page, url: URL, release: () => void) {
  const response = page.waitForResponse(value => value.url() === url.href); release(); await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}

test('platform analytics real Ktor H2 JSON passes the adapter and screen without write operations', async ({ page }) => {
  const api = await fixture(page, { live: true }); await ready(page)
  await custom(page, '2026-03-28', '2026-03-30'); await ready(page); await openAll(page)
  await expect(metric(page, 'invoices.manualConfirmed', 'invoices').locator('.stats-value')).toHaveText('1')
  await expect(metric(page, 'support.total', 'support').locator('.stats-value')).toHaveText(api.live ? '3' : '1')
  expect(number(await page.locator('#analytics-invoices [data-currency="IQD"] .stats-value').innerText())).toBe('12,345')
  expect(number(await page.locator('#analytics-invoices [data-currency="MGA"] .stats-value').first().innerText())).toBe('123,45')
  expect(api.methods.every(method => method === 'GET')).toBe(true)
  expect(api.requests.at(-1)?.searchParams.get('to')).toBe('2026-03-31')
  if (api.live) writeFileSync(`${process.env.PLATFORM_ANALYTICS_LIVE_DIR}/done`, 'PASS')
})

test('platform analytics existing owner navigation opens the real dashboard and returns to venues', async ({ page }) => {
  await fixture(page, { entry: true }); await page.getByRole('button', { name: 'Аналитика', exact: true }).click(); await ready(page)
  await expect(page.getByRole('heading', { name: 'Аналитика платформы', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'К заведениям', exact: true }).click(); await expect(page).toHaveURL(/#\/venues$/)
})
for (const role of ['OWNER', 'MANAGER', 'STAFF', 'GUEST']) test(`platform analytics denies ${role} before loading aggregates`, async ({ page }) => {
  const api = await fixture(page, { role }); await expect(page.getByText('Нет доступа.', { exact: true })).toBeVisible()
  expect(api.requests).toHaveLength(0); await expect(page.locator('.platform-analytics')).toHaveCount(0)
})

test('platform analytics overview is compact and all five thematic sections use real metrics', async ({ page }) => {
  await fixture(page); await ready(page)
  await expect(page.locator('.platform-analytics-overview .venue-stats-metric')).toHaveCount(4)
  await expect(page.locator('.platform-analytics-section')).toHaveCount(5)
  await openAll(page)
  for (const [section, key, value] of [['venues', 'venues.total', '8'], ['venues', 'venues.created', '8'],
    ['invoices', 'invoices.open', '6'], ['invoices', 'invoices.paid', '2'], ['onboarding', 'onboarding.created', '4'], ['onboarding', 'onboarding.linked', '1']]) {
    await expect(metric(page, key, section).locator('.stats-value')).toHaveText(value)
  }
  await expect(page.locator('.platform-analytics canvas, .platform-analytics svg')).toHaveCount(0)
})

test('platform analytics presets use server UTC dates and keep rolling WAAV independent', async ({ page }) => {
  const api = await fixture(page); await ready(page)
  for (const [label, start] of [['Сегодня', '2026-11-12'], ['Последние 7 дней', '2026-11-06'], ['Последние 30 дней', '2026-10-14']]) {
    await page.getByRole('button', { name: label, exact: true }).click(); await ready(page)
    expect(api.requests.at(-1)?.searchParams.get('from')).toBe(start); expect(api.requests.at(-1)?.searchParams.get('to')).toBe('2026-11-13')
    await expect(page.locator('.stats-metadata')).toContainText('UTC'); await expect(page.locator('.stats-metadata')).toContainText('168 часов')
    await expect(page.locator('.platform-analytics-overview [data-kpi="venues.waav"] .stats-value')).toHaveText('1')
  }
})

test('platform analytics custom dates use exclusive UTC end even on a venue DST day', async ({ page }) => {
  const api = await fixture(page); await ready(page); await custom(page, '2026-03-29', '2026-03-29'); await ready(page)
  const url = api.requests.at(-1)!; expect(url.searchParams.get('to')).toBe('2026-03-30')
  expect(platformResponse(url).period.from).toBe('2026-03-29T00:00:00Z'); await expect(page.locator('.stats-metadata')).toContainText('29 марта 2026')
})

test('platform analytics invalid ranges clear results and never query the API', async ({ page }) => {
  const api = await fixture(page); await ready(page)
  for (const [start, end, message] of [['2026-01-02', '2026-01-01', 'не раньше'], ['2020-01-01', '2026-01-01', '370'], ['', '2026-01-01', 'корректные']]) {
    const count = api.requests.length; await custom(page, start, end); await expect(page.locator('.stats-validation')).toContainText(message)
    expect(api.requests).toHaveLength(count); await expect(page.locator('.stats-content')).toBeEmpty()
  }
})

test('platform analytics section filter requests only supported aggregates and does not infer chat links', async ({ page }) => {
  const api = await fixture(page); await ready(page)
  for (const section of ['venues', 'subscriptions', 'invoices', 'onboarding', 'support']) {
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption(section); await ready(page)
    expect(api.requests.at(-1)?.searchParams.get('section')).toBe(section); await expect(page.locator('.platform-analytics-section')).toHaveCount(1)
  }
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('onboarding'); await ready(page)
  await expect(page.getByText('Привязки чатов сотрудников доступны в обзоре всех разделов.')).toBeVisible()
  expect(api.requests.every(url => [...url.searchParams.keys()].every(key => ['from', 'to', 'section'].includes(key)))).toBe(true)
})

test('platform analytics lifecycle availability and legacy activity remain distinct', async ({ page }) => {
  await fixture(page, { variant: 'partial' }); await ready(page); await page.locator('#analytics-venues > summary').click()
  await expect(metric(page, 'venues.total', 'venues').locator('.stats-value')).toHaveText('8')
  await expect(metric(page, 'venues.guestAvailable', 'venues').locator('.stats-value')).toHaveText('Частичные данные')
  await expect(metric(page, 'venues.waav', 'venues')).toContainText('По известным данным: 1')
  await expect(page.locator('.stats-coverage')).toContainText('не означает отсутствие активности')
  await expect(page.locator('#analytics-venues')).toContainText('Распределение причин недоступности гостям пока недоступно')
  await expect(page.locator('#analytics-venues .stats-distribution')).toContainText('Опубликованы')
})

test('platform analytics subscriptions distinguish missing rows paid period and courtesy selector', async ({ page }) => {
  await fixture(page); await ready(page); await page.locator('#analytics-subscriptions > summary').click()
  await expect(page.locator('#analytics-subscriptions')).toContainText('Подписка отсутствует')
  await expect(page.locator('#analytics-subscriptions')).toContainText('не являются полученными деньгами')
  await expect(metric(page, 'subscriptions.paidThroughCurrent', 'subscriptions')).toContainText('По известным данным: 1')
  await page.getByText('Распределение дат окончания срока', { exact: true }).click()
  await expect(page.locator('#analytics-subscriptions')).toContainText('1 марта 2099')
  await expect(page.locator('#analytics-subscriptions')).not.toContainText('1 февраля 2099')
})

test('platform analytics paid invoices manual confirmations warnings and currencies do not become settlement', async ({ page }) => {
  await fixture(page); await ready(page); await page.locator('#analytics-invoices > summary').click()
  for (const key of ['invoices.manualConfirmed', 'invoices.paidWithoutMatchingEvidence', 'invoices.inconsistentSuccessfulEvents', 'invoices.multipleMatchingSuccesses']) {
    await expect(metric(page, key, 'invoices').locator('.stats-value')).toHaveText('1')
  }
  await expect(metric(page, 'invoices.paid', 'invoices').locator('.stats-value')).toHaveText('2')
  expect(number(await page.locator('#analytics-invoices [data-currency="USD"] .stats-value').first().innerText())).toBe('40000000,00')
  await expect(page.locator('#analytics-invoices')).toContainText('не означают банковскую сверку')
  await expect(page.locator('#analytics-invoices')).toContainText('нельзя складывать')
})

test('platform analytics support excludes SYSTEM GUEST booking and venue conversations from human TTFR', async ({ page }) => {
  await fixture(page); await ready(page); await page.locator('#analytics-support > summary').click()
  await expect(metric(page, 'support.total', 'support').locator('.stats-value')).toHaveText('1')
  await expect(metric(page, 'support.ttfr', 'support').locator('.stats-value')).toHaveText('3 мин')
  await expect(metric(page, 'support.oldestActiveAge', 'support').locator('.stats-value')).toHaveText('Недостаточно данных')
  await expect(page.locator('#analytics-support')).toContainText('Системные и гостевые сообщения не считаются ответом')
})

test('platform analytics active support queue exposes measured age separately from resolution', async ({ page }) => {
  await fixture(page, { variant: 'active' }); await ready(page); await page.locator('#analytics-support > summary').click()
  await expect(metric(page, 'support.active', 'support').locator('.stats-value')).toHaveText('1')
  await expect(metric(page, 'support.oldestActiveAge', 'support').locator('.stats-value')).not.toHaveText('Недостаточно данных')
  await expect(page.locator('#analytics-support')).toContainText('Возраст обращения не является временем решения')
})

test('platform analytics partial human reply samples remain partial instead of an overall average', async ({ page }) => {
  await fixture(page, { variant: 'partial' }); await ready(page); await page.locator('#analytics-support > summary').click()
  await expect(metric(page, 'support.ttfr', 'support').locator('.stats-value')).toHaveText('Частичные данные')
  await expect(metric(page, 'support.ttfr', 'support')).toContainText('По известным данным: 3 мин')
  await expect(metric(page, 'support.ttfrUnknown', 'support').locator('.stats-value')).toHaveText('1')
})

test('platform analytics complete zero and unavailable mean are distinct in an empty platform', async ({ page }) => {
  await fixture(page, { variant: 'empty' }); await ready(page); await openAll(page)
  await expect(metric(page, 'venues.created', 'venues').locator('.stats-value')).toHaveText('0')
  await expect(metric(page, 'venues.created', 'venues')).toContainText('За выбранный период событий нет')
  await expect(metric(page, 'support.ttfr', 'support').locator('.stats-value')).toHaveText('Недостаточно данных')
  await expect(page.locator('[data-currency]')).toHaveCount(0)
})

for (const status of [400, 500]) test(`platform analytics ${status} error clears data and offers a safe retry`, async ({ page }) => {
  const api = await fixture(page); await ready(page); api.fail(status); await refresh(page)
  await expect(page.getByText('Аналитика не загружена', { exact: true })).toBeVisible(); await expect(page.locator('.stats-content')).toBeEmpty()
  await expect(page.locator('#app')).not.toContainText('SQL_SECRET')
  api.fail(0); await page.getByRole('button', { name: 'Повторить', exact: true }).click(); await ready(page)
})
for (const status of [401, 403]) test(`platform analytics ${status} revocation erases data and blocks stale permission reuse`, async ({ page }) => {
  const api = await fixture(page); await ready(page); api.fail(status); await refresh(page)
  await expect(page.getByRole('heading', { name: 'Доступ к аналитике закрыт' })).toBeVisible(); await expect(page.locator('.stats-content')).toBeEmpty()
  await expect(page.getByRole('button', { name: 'Обновить аналитику' })).toBeDisabled()
  await page.getByRole('button', { name: 'К заведениям', exact: true }).click(); await expect(page.getByText('Нет доступа.', { exact: true })).toBeVisible()
})

test('platform analytics ignored-abort stale period response cannot replace the latest result', async ({ page }) => {
  const api = await fixture(page, { ignoreAbort: true }); await ready(page)
  let release!: () => void; api.hold(new Promise(resolve => { release = resolve })); await page.getByRole('button', { name: 'Последние 7 дней', exact: true }).click()
  await expect.poll(() => api.requests.at(-1)?.searchParams.get('from')).toBe('2026-11-06'); const old = api.requests.at(-1)!
  api.hold(null); api.setVariant('empty'); await page.getByRole('button', { name: 'Последние 30 дней', exact: true }).click(); await ready(page)
  await late(page, old, release)
  await expect(page.locator('.platform-analytics-overview [data-kpi="venues.total"] .stats-value')).toHaveText('0')
  await expect(page.locator('.stats-metadata')).toContainText('14 октября 2026')
})

test('platform analytics account switch clears before paint and cannot restore an old response', async ({ page }) => {
  const api = await fixture(page, { ignoreAbort: true }); await ready(page)
  const before = api.requests.length
  let release!: () => void; api.hold(new Promise(resolve => { release = resolve })); await refresh(page)
  await expect.poll(() => api.requests.length).toBe(before + 1); const old = api.requests.at(-1)!
  await page.evaluate(() => { (window as any).Telegram.WebApp.initDataUnsafe.user.id = 7 })
  await expect(page.getByRole('heading', { name: 'Доступ к аналитике закрыт' })).toBeVisible(); await late(page, old, release)
  await expect(page.locator('.stats-content')).toBeEmpty()
})

test('platform analytics logout erases loaded values without waiting for another API call', async ({ page }) => {
  await fixture(page); await ready(page); await page.evaluate(() => (window as any).logoutAnalytics())
  await expect(page.getByRole('heading', { name: 'Доступ к аналитике закрыт' })).toBeVisible(); await expect(page.locator('.stats-content')).toBeEmpty()
})

test('platform analytics reload return and Telegram reactivation read fresh data', async ({ page }) => {
  const api = await fixture(page); await ready(page); api.setVariant('empty')
  await page.evaluate(() => (window as any).emitAnalyticsEvent('deactivated')); await expect(page.locator('.stats-content')).toBeEmpty()
  await page.evaluate(() => (window as any).emitAnalyticsEvent('activated')); await ready(page)
  await expect(page.locator('.platform-analytics-overview [data-kpi="venues.total"] .stats-value')).toHaveText('0')
  await page.reload(); await ready(page)
  await page.getByRole('button', { name: 'К заведениям', exact: true }).click(); await page.getByRole('button', { name: 'Аналитика', exact: true }).click(); await ready(page)
})

test('platform analytics slow request timeout remains recoverable and late success stays hidden', async ({ page }) => {
  await page.clock.install(); const api = await fixture(page, { ignoreAbort: true }); await ready(page)
  let release!: () => void; api.hold(new Promise(resolve => { release = resolve })); await refresh(page)
  await expect(page.locator('.stats-skeleton')).toBeVisible(); await page.clock.runFor(8100)
  await expect(page.locator('.stats-load-status')).toContainText('больше обычного'); const old = api.requests.at(-1)!
  await page.clock.runFor(22000); await expect(page.getByText('Аналитика не загружена')).toBeVisible()
  const response = page.waitForResponse(value => value.url() === old.href); release(); await (await response).finished(); await page.clock.runFor(100)
  await expect(page.locator('.stats-content')).toBeEmpty(); api.hold(null); await page.getByRole('button', { name: 'Повторить', exact: true }).click(); await ready(page)
})

test('platform analytics missing KPI and unknown capability do not become fake zeros', async ({ page }) => {
  const api = await fixture(page); await ready(page)
  api.corrupt(body => { delete body.metrics['venues.waav']; body.limitations.push('UNKNOWN_NEW_SOURCE'); body.breakdowns['subscriptions.states']?.push({ key: 'UNKNOWN', count: 1 }) })
  await refresh(page); await ready(page)
  await expect(page.locator('.platform-analytics-overview [data-kpi="venues.waav"] .stats-value')).toHaveText('Недостаточно данных')
  await expect(page.locator('.stats-coverage')).toContainText('дополнительные ограничения')
  await page.locator('#analytics-subscriptions > summary').click(); await expect(page.locator('#analytics-subscriptions')).toContainText('Неизвестно')
})

for (const malformed of ['null', 'foreign', 'wrongTimezone', 'wrongPeriod', 'wrongVersion', 'malformedMetric']) test(`platform analytics rejects ${malformed} response without leaking previous data`, async ({ page }) => {
  const api = await fixture(page); await ready(page)
  api.corrupt(body => {
    if (malformed === 'null') { Object.keys(body).forEach(key => delete (body as any)[key]); return }
    if (malformed === 'foreign') (body as any).venueId = 1
    if (malformed === 'wrongTimezone') body.period.timezone = 'Europe/Moscow'
    if (malformed === 'wrongPeriod') body.period.from = '2020-01-01T00:00:00Z'
    if (malformed === 'wrongVersion') body.definitionVersion = 'future'
    if (malformed === 'malformedMetric') (body.metrics['venues.total'] as any).value = 123
  })
  await refresh(page); await expect(page.getByText('Аналитика не загружена')).toBeVisible(); await expect(page.locator('.stats-content')).toBeEmpty()
})

test('platform analytics adapter rejects unsupported filters before issuing a request', async () => {
  let calls = 0
  const deps = { isDebug: false, clearSession() {}, getAccessToken: async () => { calls++; return { ok: true as const, data: { token: 'test' } } } }
  const query = { from: '2026-01-01', to: '2026-01-02', section: 'all', currency: 'RUB' } as PlatformAnalyticsQuery
  expect((await platformGetAnalyticsV1('', query, deps, new AbortController().signal)).ok).toBe(false); expect(calls).toBe(0)
})

for (const [width, theme] of [[360, 'dark'], [1440, 'light']] as const) test(`platform analytics ${width}px ${theme} is accessible and preserves exact large currency amounts`, async ({ page }, info) => {
  await page.setViewportSize({ width, height: 900 }); await page.emulateMedia({ colorScheme: theme })
  const api = await fixture(page); await ready(page)
  await expect(page.locator('html')).toHaveAttribute('data-color-scheme', theme)
  const overview = info.outputPath(`platform-analytics-${width}-${theme}.png`); await page.screenshot({ path: overview }); await info.attach('overview', { path: overview, contentType: 'image/png' })
  // Boundary value uses the same real DTO field (decimal string); billing amounts are not recalculated in the browser.
  api.corrupt(body => { for (const group of body.invoiceFinances.open ?? []) if (group.currency === 'USD') group.metrics.amount.value = group.metrics.amount.observedValue = '9223372036854775807' })
  await refresh(page); await ready(page); const summary = page.locator('#analytics-invoices > summary'); await summary.focus(); await page.keyboard.press('Enter')
  const card = page.locator('#analytics-invoices [data-currency="USD"]').first()
  expect(number(await card.locator('.stats-value').innerText())).toBe('92233720368547758,07')
  await expect(card.locator('.stats-currency-unit')).toHaveText('USD')
  expect(await card.locator('.stats-currency-unit').evaluate(node => getComputedStyle(node).whiteSpace)).toBe('nowrap')
  await card.locator('summary').focus(); await page.keyboard.press('Enter'); await expect(card.locator('details')).toHaveAttribute('open', '')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const money = info.outputPath(`platform-money-${width}-${theme}.png`); await card.scrollIntoViewIfNeeded(); await page.screenshot({ path: money }); await info.attach('money', { path: money, contentType: 'image/png' })
})

test('platform analytics DOM excludes confidential data and drilldown uses existing screens without filters', async ({ page }) => {
  await fixture(page); await ready(page); await openAll(page)
  await expect(metric(page, 'venues.staffChatLinked', 'onboarding').locator('.stats-value')).toHaveText('1')
  await expect(page.locator('#app')).not.toContainText(/PRIVATE_|RAW_PROVIDER|123456|telegram_user|SUPPORT_TICKET|LEGACY_HISTORY|SNAPSHOT_|initData|SQL_SECRET/)
  await page.getByRole('button', { name: 'Открыть центр поддержки', exact: true }).click(); await expect(page).toHaveURL(/#\/support$/)
})

const defaultSampleCounts = [
  ['venues.guestAvailable', 'venues', 1],
  ['subscriptions.paidThroughCurrent', 'subscriptions', 7],
  ['subscriptions.effectivePaidThroughCurrent', 'subscriptions', 7]
] as const

test('platform analytics samples real HTTP counts keep unknown without a default sample size', async ({ page }, info) => {
  const api = await fixture(page, { live: true, variant: 'partial' })
  try {
    await ready(page)
    const response = page.waitForResponse(response => response.url().includes('/analytics/v1?from=2026-03-28'))
    await custom(page, '2026-03-28', '2026-03-30'); await ready(page); await openAll(page)
    const body = await (await response).json()
    const evidence = []
    for (const [key, section, unknown] of defaultSampleCounts) {
      const card = metric(page, key, section)
      expect(body.metrics[key]).toMatchObject({ value: null, observedValue: '1', samples: 0, unknown, availability: 'PARTIAL' })
      evidence.push({ key, metric: body.metrics[key], text: await card.innerText() })
      await expect.soft(card).toContainText('По известным данным: 1')
      await expect.soft(card).toContainText(`Без подтверждённых данных: ${unknown}.`)
      await expect.soft(card).not.toContainText('Случаев в расчёте:')
      await expect.soft(card.locator('.stats-value')).toHaveText('Частичные данные')
    }
    const evidencePath = info.outputPath('samples-http-dom.json')
    writeFileSync(evidencePath, JSON.stringify(evidence, null, 2))
    await info.attach('samples-http-dom', { path: evidencePath, contentType: 'application/json' })
    expect(api.methods.every(method => method === 'GET')).toBe(true)
  } finally {
    if (api.live) writeFileSync(`${process.env.PLATFORM_ANALYTICS_LIVE_DIR}/done`, info.errors.length ? 'BROWSER_ASSERTION_FAILED' : 'PASS')
  }
})

test('platform analytics samples COUNT defaults stay absent across zero null period reload and reentry', async ({ page }) => {
  const api = await fixture(page, { variant: 'empty' }); await ready(page); await openAll(page)
  for (const [key, section] of defaultSampleCounts) {
    const card = metric(page, key, section)
    await expect(card.locator('.stats-value')).toHaveText('0')
    await expect(card).not.toContainText('Случаев в расчёте:')
    await expect(card).toContainText('Подходящих записей на время обновления нет.')
  }
  api.setVariant('partial')
  await page.getByRole('button', { name: 'Последние 7 дней', exact: true }).click(); await ready(page); await openAll(page)
  for (const reopen of [async () => {}, async () => { await page.reload(); await ready(page); await openAll(page) }, async () => {
    await page.getByRole('button', { name: 'К заведениям', exact: true }).click()
    await page.getByRole('button', { name: 'Аналитика', exact: true }).click(); await ready(page); await openAll(page)
  }]) {
    await reopen()
    for (const [key, section, unknown] of defaultSampleCounts) {
      const card = metric(page, key, section)
      await expect(card).toContainText('По известным данным: 1')
      await expect(card).toContainText(`Без подтверждённых данных: ${unknown}.`)
      await expect(card).not.toContainText('Случаев в расчёте:')
      await card.locator('summary').click(); await expect(card.locator('details')).toHaveAttribute('open', '')
    }
  }
  api.corrupt(body => {
    const value = body.metrics['venues.guestAvailable']
    Object.assign(value, { value: null, observedValue: null, availability: 'UNAVAILABLE', coverage: 'COMPLETE_RECORDED_SOURCE', unknown: 0, reason: 'NO_MEASURABLE_SAMPLE' })
  })
  await refresh(page); await ready(page); await openAll(page)
  const unavailable = metric(page, 'venues.guestAvailable', 'venues')
  await expect(unavailable.locator('.stats-value')).toHaveText('Недостаточно данных')
  await expect(unavailable).not.toContainText('По известным данным: 0')
  await expect(unavailable).not.toContainText('Случаев в расчёте:')
})

test('platform analytics samples measured WAAV age and TTFR preserve real positive zero and unknown counts', async ({ page }) => {
  const api = await fixture(page); await ready(page)
  for (const variant of ['complete', 'partial', 'active', 'empty'] as const) {
    api.setVariant(variant); await refresh(page); await ready(page); await openAll(page)
    for (const [key, section] of [['venues.waav', 'venues'], ['support.oldestActiveAge', 'support'], ['support.ttfr', 'support']]) {
      const value = platformHttp[variant].metrics[key], card = metric(page, key, section)
      await expect(card).toContainText(`Случаев в расчёте: ${value.samples}.`)
      await expect(card).toContainText(`Без подтверждённых данных: ${value.unknown}.`)
      if (value.availability === 'PARTIAL') await expect(card.locator('.stats-value')).toHaveText('Частичные данные')
      if (value.value === null && value.availability === 'UNAVAILABLE') await expect(card.locator('.stats-value')).toHaveText('Недостаточно данных')
    }
    await expect(metric(page, 'support.ttfrUnknown', 'support')).not.toContainText('Случаев в расчёте:')
    await expect(page.locator('.platform-analytics-section')).toHaveCount(5)
  }
  // An unknown basis must not inherit sample semantics merely from its unit or positive count.
  api.setVariant('partial'); api.corrupt(body => { body.metrics['support.ttfr'].basis = 'UNKNOWN_SAMPLE_CONTRACT' })
  await refresh(page); await ready(page); await openAll(page)
  await expect(metric(page, 'support.ttfr', 'support').locator('.stats-value')).toHaveText('Недостаточно данных')
  await expect(metric(page, 'support.ttfr', 'support')).not.toContainText('Случаев в расчёте:')
  await expect(metric(page, 'support.ttfr', 'support')).toContainText('Без подтверждённых данных: 1.')
})
