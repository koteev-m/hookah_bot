import { expect, test, type Page } from '@playwright/test'
import { statsHttp, statsResponse, type StatsVariant } from './fixtures/venue-stats'
import { calendarDateAt, exactMoney, formatRatio, periodValidation, presetDates, shiftCalendarDate, validCalendarDate } from '../src/shared/ui/venueStatsFormat'

type Options = { role?: string; variant?: StatsVariant; timezone?: string; asOf?: string; shell?: boolean; ignoreAbort?: boolean }
async function fixture(page: Page, options: Options = {}) {
  let variant = options.variant ?? 'complete'
  let role = options.role ?? 'OWNER'
  let failure = 0
  let tableFailure = false
  let hold: Promise<void> | null = null
  let corrupt: ((body: ReturnType<typeof statsResponse>) => void) | null = null
  const requests: URL[] = []
  let mutations = 0
  await page.addInitScript(({ ignoreAbort }) => {
    const handlers = new Map<string, Set<() => void>>()
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 7 } }, ready() {}, expand() {},
      onEvent(name: string, callback: () => void) { if (!handlers.has(name)) handlers.set(name, new Set()); handlers.get(name)!.add(callback) },
      offEvent(name: string, callback: () => void) { handlers.get(name)?.delete(callback) } } },
    emitStatsEvent: (name: string) => handlers.get(name)?.forEach(callback => callback()) })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:7`, JSON.stringify({ token: 'test-stats', expiresAtEpochSeconds: 4102444800 }))
    if (ignoreAbort) {
      const Original = window.AbortController
      window.AbortController = class extends Original { abort() {} }
    }
  }, { ignoreAbort: options.ignoreAbort ?? false })
  await page.route('**/miniapp/stats-test*', route => route.fulfill({ contentType: 'text/html', body:
    `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"></head><body><div id="app"></div><script type="module">
    import {renderVenueStatsScreen} from '/miniapp/src/screens/venueStats.ts';
    import {mountVenueApp} from '/miniapp/src/screens/venueApp.ts';
    import {bindAppTheme} from '/miniapp/src/shared/ui/appTheme.ts'; bindAppTheme(document.documentElement);
    let dispose; window.mountStats=(venueId=1, role=${JSON.stringify(role)})=>{dispose?.(); dispose=renderVenueStatsScreen({root:document.querySelector('#app'),backendUrl:location.origin,isDebug:false,venueId,access:{venueId,venueName:'Заведение '+venueId,role,permissions:[]}})};
    ${options.shell ? "mountVenueApp({root:document.querySelector('#app'),backendUrl:location.origin})" : 'window.mountStats()'};
    </script></body></html>` }))
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    if (route.request().method() !== 'GET' && url.pathname !== '/api/auth/telegram') mutations++
    if (url.pathname === '/api/auth/telegram') return route.fulfill({ json: { token: 'new-test-stats', expiresAtEpochSeconds: 4102444800 } })
    if (url.pathname === '/api/venue/me') return route.fulfill({ json: { userId: 7, venues: [1, 2].map(venueId => ({
      venueId, venueName: `Заведение ${venueId}`, role, permissions: [], venueStatus: 'PUBLISHED'
    })) } })
    if (url.pathname === '/api/venue/tables') return route.fulfill(tableFailure ? { status: 503, json: {} } : { json: { tables: [1, 2].map(tableId => ({ tableId, tableNumber: tableId, tableLabel: `Стол ${tableId}`, isActive: true })) } })
    if (/\/stats\/v1$/.test(url.pathname)) {
      requests.push(url)
      expect(route.request().headers().authorization).toMatch(/^Bearer /)
      const body = statsResponse(url, { ...options, variant }); corrupt?.(body)
      const capturedFailure = failure
      if (hold) await hold
      await route.fulfill(capturedFailure ? { status: capturedFailure, json: { error: { code: capturedFailure === 403 ? 'FORBIDDEN' : 'INTERNAL_ERROR', message: 'SQL_SECRET_do_not_display' } } } : { json: body }).catch(() => {})
      return
    }
    return route.fulfill({ json: { items: [], unreadCount: 0 } })
  })
  await page.goto(`stats-test?venueId=1${options.shell ? '#/stats' : ''}`)
  return { requests, mutations: () => mutations, setVariant(value: StatsVariant) { variant = value }, fail(value: number) { failure = value },
    hold(value: Promise<void> | null) { hold = value }, setRole(value: string) { role = value }, failTables() { tableFailure = true },
    corrupt(value: typeof corrupt) { corrupt = value } }
}
const metric = (page: Page, key: string) => page.locator(`[data-kpi="${key}"]`)
const ready = (page: Page) => expect(page.locator('.stats-content .stats-section').first()).toBeVisible()
const moneyNumber = (value: string) => value.replace(/[^\d,−-]/g, '').replace('−', '-')
const refresh = (page: Page) => page.getByRole('button', { name: 'Обновить статистику', exact: true }).click()
async function deliverLate(page: Page, url: URL, release: () => void) {
  const response = page.waitForResponse(value => value.url() === url.href)
  release(); await (await response).finished()
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
async function custom(page: Page, from: string, to: string) {
  await page.getByRole('button', { name: 'Произвольный период' }).click()
  await page.getByLabel('Начало периода').fill(from)
  await page.getByLabel('Окончание периода (включительно)').fill(to)
  await page.getByRole('button', { name: 'Показать период' }).click()
}

test('stats calendar arithmetic validates actual dates and 370-day inclusive ceiling without 24-hour venue arithmetic', () => {
  expect(validCalendarDate('2026-02-30')).toBe(false)
  expect(validCalendarDate('2028-02-29')).toBe(true)
  expect(periodValidation('2026-11-10', '2026-11-09')).toContain('не раньше')
  expect(periodValidation('', '2026-01-01')).toContain('корректные')
  expect(periodValidation('2026-01-01', shiftCalendarDate('2026-01-01', 369))).toBeNull()
  expect(periodValidation('2026-01-01', shiftCalendarDate('2026-01-01', 370))).toContain('370')
  expect(presetDates('7d', '2026-03-29T22:30:00Z', 'Europe/Berlin')).toEqual({ from: '2026-03-24', to: '2026-03-30' })
  expect(calendarDateAt('2026-11-10T23:30:00Z', 'America/Los_Angeles')).toBe('2026-11-10')
})

test('stats money retains exact integer minor units beyond Number precision and ISO fraction digits', () => {
  const compact = (value: string) => value.replace(/[\s\u00a0\u202f]/g, '')
  expect(compact(exactMoney('900719925474099312', 'RUB'))).toBe('9007199254740993,12RUB')
  expect(compact(exactMoney('12345', 'USD'))).toBe('123,45USD')
  expect(compact(exactMoney('12345', 'JPY'))).toBe('12345JPY')
  expect(compact(exactMoney('12345', 'KWD'))).toBe('12,345KWD')
  expect(compact(exactMoney('-1', 'RUB'))).toBe('−0,01RUB')
  expect(exactMoney('NaN', 'RUB')).toBe('Недостаточно данных')
  expect(formatRatio('0.500000')).toBe('50,00 %')
})

test('stats real HTTP capture renders orders batches financial shifts bookings visits and operator versus automatic calls', async ({ page }) => {
  const api = await fixture(page); await ready(page)
  await custom(page, '2026-03-28', '2026-03-30'); await ready(page)
  for (const [key, value] of Object.entries({ 'orders.created': '1', 'batches.created': '3', 'orders.firstAccepted': '1',
    'batches.accepted': '3', 'batches.delivered': '2', 'batches.rejected': '1', 'bookings.created': '1', 'bookings.scheduled': '6',
    'bookings.unresolved': '2', 'bookings.canceled': '1', 'bookings.expired': '1', 'seatings.registeredSessions': '1',
    'calls.created': '4', 'calls.acknowledged': '2', 'calls.operatorCompleted': '1', 'calls.autoCompleted': '1', 'calls.cancelled': '1' })) {
    await expect(metric(page, key).locator('.stats-value')).toHaveText(value)
  }
  await expect(metric(page, 'batches.acceptedDelay').locator('.stats-value')).toHaveText('25 ч')
  await expect(metric(page, 'batches.deliveredDelay').locator('.stats-value')).toHaveText('26 ч')
  await expect(metric(page, 'calls.firstResponse').locator('.stats-value')).toHaveText('5 мин')
  await expect(metric(page, 'bookings.attendance').locator('.stats-value')).toHaveText('50,00 %')
  await expect(page.getByText('Чай из заказа', { exact: true })).toBeVisible()
  await expect(page.getByText('Новое имя', { exact: true })).toHaveCount(0)
  await expect(page.getByText(/Посадки — зарегистрированные физические сессии/)).toBeVisible()
  await expect(page.getByText('Автоматическое завершение вызова при закрытии заказа не считается подтверждённым обслуживанием сотрудником.')).toBeVisible()
  await expect(page.locator('.stats-metadata')).toContainText('28 марта 2026')
  expect(api.requests.at(-1)?.searchParams.get('to')).toBe('2026-03-31')
  expect(api.mutations()).toBe(0)
  expect(statsHttp.complete.period.from).toBe('2026-03-27T23:00:00Z')
  expect(statsHttp.complete.period.to).toBe('2026-03-30T22:00:00Z')
})

test('stats presets include venue today and send 7 or 30 calendar dates in its timezone', async ({ page }) => {
  const api = await fixture(page, { timezone: 'Asia/Tokyo', asOf: '2026-11-10T22:30:00Z' }); await ready(page)
  expect(api.requests.at(-1)?.searchParams.get('from')).toBe('2026-11-11')
  expect(api.requests.at(-1)?.searchParams.get('to')).toBe('2026-11-12')
  await page.getByRole('button', { name: 'Последние 7 дней', exact: true }).click(); await ready(page)
  expect(api.requests.at(-1)?.searchParams.get('from')).toBe('2026-11-05')
  await page.getByRole('button', { name: 'Последние 30 дней', exact: true }).click(); await ready(page)
  expect(api.requests.at(-1)?.searchParams.get('from')).toBe('2026-10-13')
  await expect(page.locator('.stats-metadata')).toContainText('Asia/Tokyo')
})

for (const [date, hours] of [['2026-03-29', 23], ['2026-10-25', 25]] as const) {
  test(`stats custom ${date} preserves the ${hours}-hour Berlin day`, async ({ page }) => {
    const api = await fixture(page); await ready(page); await custom(page, date, date); await ready(page)
    const response = statsResponse(api.requests.at(-1)!)
    expect((Date.parse(response.period.to) - Date.parse(response.period.from)) / 3600000).toBe(hours)
    expect(api.requests.at(-1)?.searchParams.get('from')).toBe(date)
    expect(api.requests.at(-1)?.searchParams.get('to')).toBe(shiftCalendarDate(date, 1))
  })
}

test('stats invalid custom dates do not issue API queries and clear old results', async ({ page }) => {
  const api = await fixture(page); await ready(page); const before = api.requests.length
  await custom(page, '2026-11-10', '2026-11-09')
  await expect(page.getByRole('alert').filter({ hasText: 'не раньше' })).toBeVisible()
  await expect(page.locator('.stats-content')).toBeEmpty(); expect(api.requests).toHaveLength(before)
  await page.getByLabel('Начало периода').fill('2025-01-01'); await page.getByLabel('Окончание периода (включительно)').fill('2026-11-10')
  await page.getByRole('button', { name: 'Показать период' }).click()
  await expect(page.getByText('Выберите период не длиннее 370 календарных дней.')).toBeVisible()
  await page.getByLabel('Начало периода').fill(''); await page.getByRole('button', { name: 'Показать период' }).click()
  await expect(page.getByText('Укажите корректные даты начала и окончания.')).toBeVisible()
  expect(api.requests).toHaveLength(before)
})

test('stats closed frozen finances retain exact currencies and never combine the provisional open shift', async ({ page }) => {
  await fixture(page); await ready(page); await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await ready(page)
  const closed = page.locator('#stats-finance > div > .stats-currency')
  await expect(closed).toHaveCount(2)
  await expect(closed.filter({ hasText: 'Валюта: RUB' }).locator('[data-kpi="accrued"] .stats-value')).toHaveText(exactMoney('900719925474099312', 'RUB'))
  await expect(closed.filter({ hasText: 'Валюта: USD' }).locator('[data-kpi="accrued"] .stats-value')).toHaveText(exactMoney('12345', 'USD'))
  await expect(page.locator('.stats-provisional [data-kpi="accrued"] .stats-value')).toHaveText(exactMoney('0', 'RUB'))
  await expect(page.getByText('Показатели закрытых смен относятся к датам их закрытия. Подтверждённые оплаты внесены сотрудником и не означают банковскую сверку.')).toBeVisible()
  await expect(page.locator('.stats-provisional')).toContainText('не ограничены выбранными датами')
  await expect(page.getByLabel('Текущий стол')).toBeHidden()
})

test('stats complete empty counters differ from unavailable attendance and mean without inventing currency totals', async ({ page }) => {
  await fixture(page, { variant: 'empty' }); await ready(page)
  await expect(metric(page, 'orders.created').locator('.stats-value')).toHaveText('0')
  await expect(metric(page, 'orders.created')).toContainText('За выбранный период событий нет.')
  await expect(metric(page, 'bookings.attendance').locator('.stats-value')).toHaveText('Недостаточно данных')
  await expect(metric(page, 'calls.firstResponse').locator('.stats-value')).toHaveText('Недостаточно данных')
  await expect(page.getByText('За выбранный период закрытых смен нет.')).toBeVisible()
  await expect(page.locator('.stats-currency')).toHaveCount(0)
})

test('stats partial history exposes observed subset samples and unknown without displaying raw technical codes', async ({ page }) => {
  await fixture(page, { variant: 'partial' }); await ready(page)
  await expect(metric(page, 'orders.firstAccepted').locator('.stats-value')).toHaveText('Частичные данные')
  await expect(metric(page, 'batches.accepted')).toContainText('По известным данным: 3')
  await expect(metric(page, 'batches.accepted')).toContainText('Без полной истории: 3')
  await expect(page.locator('.stats-coverage')).toContainText('Часть старых операций не содержит точного времени.')
  await expect(page.locator('#stats-finance')).toContainText('Не все отчёты смен доступны или полны.')
  await expect(page.locator('.venue-stats')).not.toContainText(/LEGACY_HISTORY_UNKNOWN|SHIFT_CLOSED_AT|BATCH_CREATED|SQL_SECRET/)
})

test('stats API error clears previously visible money and supports a safe retry', async ({ page }) => {
  const api = await fixture(page); await ready(page); api.fail(500); await refresh(page)
  await expect(page.getByText('Статистика не загружена')).toBeVisible()
  await expect(page.locator('.stats-content')).toBeEmpty()
  await expect(page.locator('body')).not.toContainText('SQL_SECRET')
  api.fail(0); await page.getByRole('button', { name: 'Повторить', exact: true }).click(); await ready(page)
})

test('stats slow response shows progress and timeout permits retry without stale numbers', async ({ page }) => {
  const api = await fixture(page); await ready(page); await page.clock.install()
  let release!: () => void; api.hold(new Promise(resolve => { release = resolve }))
  const before = api.requests.length; await refresh(page); await expect.poll(() => api.requests.length).toBe(before + 1)
  await page.clock.runFor(9000)
  await expect(page.getByText(/Загрузка занимает больше обычного/)).toBeVisible()
  await expect(page.locator('.stats-skeleton')).toBeVisible(); await expect(page.locator('.stats-content')).toBeEmpty()
  await page.clock.runFor(22000)
  await expect(page.getByText(/Сервер не успел ответить/)).toBeVisible()
  api.hold(null); release(); await page.getByRole('button', { name: 'Повторить', exact: true }).click(); await ready(page)
})

test('stats delayed old period cannot overwrite a newer result even if cancellation is ignored', async ({ page }) => {
  const api = await fixture(page, { ignoreAbort: true }); await ready(page)
  let release!: () => void; api.hold(new Promise(resolve => { release = resolve }))
  await page.getByRole('button', { name: 'Последние 7 дней' }).click()
  await expect.poll(() => api.requests.at(-1)?.searchParams.get('from')).toBe('2026-11-04')
  api.hold(null); api.setVariant('empty')
  await page.getByRole('button', { name: 'Последние 30 дней' }).click(); await ready(page)
  await expect(metric(page, 'orders.created').locator('.stats-value')).toHaveText('0')
  await deliverLate(page, api.requests.find(url => url.searchParams.get('from') === '2026-11-04')!, release)
  await expect(page.getByRole('button', { name: 'Последние 30 дней' })).toHaveAttribute('aria-pressed', 'true')
  await expect(metric(page, 'orders.created').locator('.stats-value')).toHaveText('0')
})

test('stats venue switch disposes old request and never renders foreign financial data', async ({ page }) => {
  const api = await fixture(page, { ignoreAbort: true }); await ready(page)
  const before = api.requests.length
  let release!: () => void; api.hold(new Promise(resolve => { release = resolve })); await refresh(page)
  await expect.poll(() => api.requests.length).toBe(before + 1)
  const old = api.requests.at(-1)!
  await expect(page.locator('.stats-content')).toBeEmpty()
  api.hold(null); api.setVariant('empty')
  await page.evaluate(() => (window as unknown as { mountStats: (id: number) => void }).mountStats(2)); await ready(page)
  await deliverLate(page, old, release); await expect(page.locator('.stats-venue-name')).toHaveText('Заведение 2')
  await expect(page.locator('.stats-currency')).toHaveCount(0)
  await expect(metric(page, 'orders.created').locator('.stats-value')).toHaveText('0')
})

test('stats account switch immediately clears sensitive state and rejects a late result', async ({ page }) => {
  const api = await fixture(page, { ignoreAbort: true }); await ready(page)
  const before = api.requests.length
  let release!: () => void; api.hold(new Promise(resolve => { release = resolve })); await refresh(page)
  await expect.poll(() => api.requests.length).toBe(before + 1)
  const old = api.requests.at(-1)!
  await page.evaluate(() => { (window as unknown as { Telegram: { WebApp: { initDataUnsafe: { user: { id: number } } } } }).Telegram.WebApp.initDataUnsafe.user.id = 8 })
  await expect(page.getByText('Аккаунт изменился', { exact: true })).toBeVisible()
  await deliverLate(page, old, release); await expect(page.locator('.stats-content')).toBeEmpty(); await expect(page.getByRole('button', { name: 'Обновить статистику' })).toBeDisabled()
})

for (const code of [401, 403]) test(`stats access loss ${code} clears data and disables historical controls`, async ({ page }) => {
  const api = await fixture(page); await ready(page); api.fail(code); await refresh(page)
  await expect(page.getByText(code === 401 ? 'Аккаунт изменился' : 'Доступ к статистике потерян', { exact: true })).toBeVisible()
  await expect(page.locator('.stats-content')).toBeEmpty(); await expect(page.getByRole('button', { name: 'Последние 7 дней' })).toBeDisabled()
})

for (const role of ['OWNER', 'MANAGER']) test(`stats ${role} can read management data without mutations`, async ({ page }) => {
  const api = await fixture(page, { role }); await ready(page)
  await expect(page.getByRole('heading', { name: 'Финансовые итоги закрытых смен', exact: true })).toBeVisible()
  expect(api.mutations()).toBe(0)
})

for (const role of ['STAFF', 'GUEST', 'PLATFORM_OWNER']) test(`stats ${role} has no frontend grant and issues no analytics request`, async ({ page }) => {
  const api = await fixture(page, { role })
  await expect(page.getByText('Раздел доступен владельцу или менеджеру этого заведения.')).toBeVisible()
  expect(api.requests).toHaveLength(0); await expect(page.locator('.stats-currency')).toHaveCount(0)
})

test('stats filters apply table only within an operational section and preserve finance scope', async ({ page }) => {
  const api = await fixture(page); await ready(page)
  await expect(page.getByLabel('Текущий стол')).toBeHidden()
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('orders'); await ready(page)
  await page.getByLabel('Текущий стол').selectOption('2'); await ready(page)
  expect(api.requests.at(-1)?.searchParams.get('section')).toBe('orders'); expect(api.requests.at(-1)?.searchParams.get('tableId')).toBe('2')
  await expect(page.getByText(/Прошлые перемещения не восстанавливаются/)).toBeVisible()
  await expect(page.locator('#stats-finance')).toHaveCount(0)
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await ready(page)
  expect(api.requests.at(-1)?.searchParams.has('tableId')).toBe(false)
  await expect(page.getByLabel('Текущий стол')).toBeHidden()
})

test('stats missing table capability keeps unfiltered results and explains the unavailable filter', async ({ page }) => {
  const api = await fixture(page); await ready(page); api.failTables()
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('calls'); await ready(page)
  await expect(page.getByText(/Фильтр по столу сейчас недоступен/)).toBeVisible()
  expect(api.requests.at(-1)?.searchParams.has('tableId')).toBe(false)
})

test('stats unsupported contract and foreign response fail closed without displaying data', async ({ page }) => {
  const api = await fixture(page); await ready(page); api.corrupt(body => { body.venueId = 999 })
  await refresh(page); await expect(page.getByText(/Получены неподдерживаемые данные/)).toBeVisible()
  await expect(page.locator('.stats-content')).toBeEmpty()
  api.corrupt(body => { body.definitionVersion = 'future' }); await page.getByRole('button', { name: 'Повторить', exact: true }).click()
  await expect(page.getByText(/Получены неподдерживаемые данные/)).toBeVisible()
  api.corrupt(body => { body.period.from = '2020-01-01T00:00:00Z' }); await page.getByRole('button', { name: 'Повторить', exact: true }).click()
  await expect(page.getByText(/Получены неподдерживаемые данные/)).toBeVisible()
})

test('stats unknown KPI capability stays unavailable and never becomes a fabricated zero', async ({ page }) => {
  const api = await fixture(page); await ready(page); api.corrupt(body => { delete body.metrics['calls.operatorCompleted'] })
  await refresh(page); await ready(page)
  await expect(metric(page, 'calls.operatorCompleted').locator('.stats-value')).toHaveText('Недостаточно данных')
})

test('stats keyboard opens explanations and applies custom dates using native controls', async ({ page }) => {
  await fixture(page); await ready(page)
  const explanation = metric(page, 'orders.firstAccepted').locator('summary')
  await explanation.focus(); await page.keyboard.press('Enter')
  await expect(metric(page, 'orders.firstAccepted').locator('details')).toHaveAttribute('open', '')
  await expect(metric(page, 'orders.firstAccepted')).toContainText('Дозаказы не добавляют')
  await page.getByRole('button', { name: 'Сегодня', exact: true }).focus(); await page.keyboard.press('Tab')
  await expect(page.getByRole('button', { name: 'Последние 7 дней' })).toBeFocused()
  await page.keyboard.press('Enter'); await ready(page)
})

test('stats shell retains navigation venue switch reload and closes revoked membership', async ({ page }) => {
  const api = await fixture(page, { shell: true }); await ready(page)
  await page.locator('.venue-select').selectOption('2'); await ready(page)
  await expect(page.locator('.stats-venue-name')).toHaveText('Заведение 2')
  await page.reload(); await ready(page)
  await page.evaluate(() => { location.hash = '#/more' })
  await page.getByRole('link', { name: 'Статистика', exact: true }).click(); await ready(page)
  api.setRole('STAFF'); api.fail(403); await refresh(page)
  await expect(page.locator('.stats-content')).toHaveCount(0)
  await expect(page.getByText('У вас нет доступа к этому разделу.')).toBeVisible()
})

test('stats Telegram deactivate hides private data and activated reloads a current snapshot', async ({ page }) => {
  const api = await fixture(page); await ready(page)
  await page.evaluate(() => (window as unknown as { emitStatsEvent: (name: string) => void }).emitStatsEvent('deactivated'))
  await expect(page.locator('.stats-content')).toBeEmpty(); api.setVariant('empty')
  await page.evaluate(() => (window as unknown as { emitStatsEvent: (name: string) => void }).emitStatsEvent('activated')); await ready(page)
  await expect(metric(page, 'orders.created').locator('.stats-value')).toHaveText('0')
})

for (const [currency, expected] of [['IQD', '12,345'], ['MGA', '123,45']]) {
  test(`stats money real Ktor H2 ${currency} response passes adapter and renders ISO minor units`, async ({ page }) => {
    await fixture(page, { variant: 'money' }); await ready(page)
    const response = page.waitForResponse(value => value.url().includes('from=2026-03-28') && value.url().includes('/stats/v1'))
    await custom(page, '2026-03-28', '2026-03-30'); await ready(page)
    expect(await (await response).json()).toEqual(statsHttp.money)
    const group = statsHttp.money.closedShiftFinances.find(group => group.currency === currency)!
    expect(group.metrics.accrued.value).toBe('12345')
    const value = page.locator(`#stats-finance [data-currency="${currency}"] [data-kpi="accrued"] .stats-value`)
    await expect(value).toBeVisible()
    expect(moneyNumber(await value.innerText())).toBe(expected)
    await expect(page.locator('.stats-metadata')).toContainText('28 марта 2026')
  })
}

for (const [width, theme] of [[1440, 'light'], [360, 'dark']] as const) {
  test(`stats money mixed ISO currencies and Long maximum fit ${width}px ${theme}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 }); await page.emulateMedia({ colorScheme: theme })
    await fixture(page, { variant: 'money' }); await ready(page)
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await ready(page)
    const expected: Record<string, string> = { IQD: '12,345', MGA: '123,45', RUB: '9007199254740993,12',
      EUR: '92233720368547758,07', USD: '0,01', JPY: '12345', KRW: '0', KWD: '12,345', BHD: '12,345', CLF: '1,2345' }
    for (const [currency, amount] of Object.entries(expected)) {
      const card = page.locator(`#stats-finance [data-currency="${currency}"] [data-kpi="accrued"]`)
      expect(moneyNumber(await card.locator('.stats-value').innerText()), currency).toBe(amount)
      await expect(card.getByText('Как считается', { exact: true })).toBeVisible()
    }
    await expect(page.locator('#stats-finance [data-currency="XXX"] [data-kpi="accrued"] .stats-value')).toHaveText('Недостаточно данных')
    await expect(page.locator('.stats-provisional')).toContainText('Сейчас открытой смены нет.')
    await page.locator('#stats-finance [data-currency="EUR"]').evaluate(node => node.scrollIntoView({ block: 'start' }))
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const screenshot = info.outputPath(`venue-money-${width}-${theme}.png`)
    await page.screenshot({ path: screenshot }); await info.attach('exact currencies', { path: screenshot, contentType: 'image/png' })
  })
}

for (const [width, theme] of [[1440, 'light'], [360, 'dark']] as const) {
  test(`stats ${width}px ${theme} layout is readable without horizontal overflow`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 }); await page.emulateMedia({ colorScheme: theme }); await fixture(page); await ready(page)
    await expect(page.locator('html')).toHaveAttribute('data-color-scheme', theme)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const overview = info.outputPath(`venue-stats-${width}-${theme}.png`)
    await page.screenshot({ path: overview }); await info.attach('overview', { path: overview, contentType: 'image/png' })
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await ready(page)
    await page.locator('#stats-finance').evaluate(node => node.scrollIntoView({ block: 'start' }))
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const unit = page.locator('#stats-finance [data-kpi="accrued"] .stats-currency-unit').first()
    await expect(unit).toHaveText('RUB')
    expect(await unit.evaluate(node => getComputedStyle(node).whiteSpace)).toBe('nowrap')
    const finance = info.outputPath(`venue-finance-${width}-${theme}.png`)
    await page.screenshot({ path: finance }); await info.attach('finance', { path: finance, contentType: 'image/png' })
  })
}
