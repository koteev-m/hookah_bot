import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test, type Page } from '@playwright/test'
import { statsHttp, statsResponse, type StatsVariant } from './fixtures/venue-stats'
import { calendarDateAt, exactMoney, formatRatio, periodValidation, presetDates, shiftCalendarDate, validCalendarDate } from '../src/shared/ui/venueStatsFormat'

type Options = { role?: string; variant?: StatsVariant; timezone?: string; asOf?: string; shell?: boolean; entry?: 'more'; ignoreAbort?: boolean; overview?: boolean; live?: { base: string; token: string } }
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
    const backHandlers = new Set<() => void>()
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 7 } }, ready() {}, expand() {}, BackButton: { show() {}, hide() {}, onClick(fn: () => void) { backHandlers.add(fn) }, offClick(fn: () => void) { backHandlers.delete(fn) } },
      onEvent(name: string, callback: () => void) { if (!handlers.has(name)) handlers.set(name, new Set()); handlers.get(name)!.add(callback) },
      offEvent(name: string, callback: () => void) { handlers.get(name)?.delete(callback) } } },
    emitStatsEvent: (name: string) => handlers.get(name)?.forEach(callback => callback()), emitStatsBack: () => backHandlers.forEach(callback => callback()) })
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
      if (options.live) {
        requests.push(url)
        const result = await route.fetch({ url: options.live.base + url.pathname + url.search, headers: { Authorization: `Bearer ${options.live.token}` } })
        return route.fulfill({ response: result })
      }
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
  await page.goto(`stats-test?venueId=1${options.shell ? options.entry === 'more' ? '#/more' : '#/stats' : ''}`)
  if (!options.overview && !options.shell && ['OWNER', 'MANAGER'].includes(role)) {
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('service')
    await page.getByRole('button', { name: 'Сегодня', exact: true }).click()
    await expect(page.locator('.stats-service-details').first()).toBeAttached()
    await page.locator('.stats-service-details').evaluateAll(nodes => nodes.forEach(node => { (node as HTMLDetailsElement).open = true }))
  }
  return { requests, mutations: () => mutations, setVariant(value: StatsVariant) { variant = value }, fail(value: number) { failure = value },
    hold(value: Promise<void> | null) { hold = value }, setRole(value: string) { role = value }, failTables() { tableFailure = true },
    corrupt(value: typeof corrupt) { corrupt = value } }
}
const metric = (page: Page, key: string) => page.locator(`[data-kpi="${key}"]`)
const ready = async (page: Page) => { await expect(page.locator('.stats-content .stats-section').first()).toBeAttached(); if (await page.getByRole('combobox', { name: 'Показатели', exact: true }).inputValue() === 'service') await page.locator('.stats-service-details').evaluateAll(nodes => nodes.forEach(node => { (node as HTMLDetailsElement).open = true })); await expect(page.locator('.stats-content .stats-section').first()).toBeVisible() }
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
  await fixture(page); await ready(page); await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check(); await ready(page)
  const closed = page.locator('#stats-finance > div > .stats-currency')
  await expect(closed).toHaveCount(2)
  await expect(closed.filter({ hasText: 'Валюта: RUB' }).locator('[data-kpi="accrued"] .stats-value')).toHaveText(exactMoney('900719925474099312', 'RUB'))
  await expect(closed.filter({ hasText: 'Валюта: USD' }).locator('[data-kpi="accrued"] .stats-value')).toHaveText(exactMoney('12345', 'USD'))
  await expect(page.locator('#stats-finance')).toContainText('Текущая смена сюда не входит.')
  await page.getByRole('radio', { name: 'Текущая смена', exact: true }).check()
  await expect(page.locator('#stats-finance [data-kpi="accrued"] .stats-value')).toHaveText(exactMoney('0', 'RUB'))
  await expect(page.getByRole('button', { name: 'Последние 7 дней' })).toBeHidden()
  await expect(page.locator('#stats-finance')).toContainText('Календарный период их не ограничивает.')
  await expect(page.getByLabel('Текущий стол')).toBeHidden()
})

test('stats complete empty counters differ from unavailable attendance and mean without inventing currency totals', async ({ page }) => {
  await fixture(page, { variant: 'empty' }); await ready(page)
  await expect(metric(page, 'orders.created').locator('.stats-value')).toHaveText('0')
  await expect(metric(page, 'orders.created')).toContainText('За выбранный период событий нет.')
  await expect(metric(page, 'bookings.attendance').locator('.stats-value')).toHaveText('Недостаточно данных')
  await expect(metric(page, 'calls.firstResponse').locator('.stats-value')).toHaveText('Недостаточно данных')
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check(); await ready(page)
  await expect(page.getByText('Нет смен, закрытых в выбранный период.', { exact: false })).toBeVisible()
  await expect(page.locator('.stats-currency')).toHaveCount(0)
})

test('stats partial history exposes observed subset samples and unknown without displaying raw technical codes', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 }); await page.emulateMedia({ colorScheme: 'dark' })
  await fixture(page, { variant: 'partial' }); await ready(page)
  await expect(metric(page, 'orders.firstAccepted').locator('.stats-value')).toHaveText('Частичные данные')
  await expect(metric(page, 'batches.accepted')).toContainText('По известным данным: 3')
  await expect(metric(page, 'batches.accepted')).toContainText('Без полной истории: 3')
  await expect(page.locator('.stats-coverage')).toContainText('Часть старых операций не содержит точного времени.')
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check(); await ready(page)
  await expect(page.locator('#stats-finance')).toContainText('Не все отчёты смен доступны или полны.')
  await expect(page.locator('.venue-stats')).not.toContainText(/LEGACY_HISTORY_UNKNOWN|SHIFT_CLOSED_AT|BATCH_CREATED|SQL_SECRET/)
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check(); await ready(page)
  await expect(page.locator('#stats-finance')).toContainText('Не все отчёты смен доступны или полны.')
  await page.screenshot({ path: info.outputPath('partial-finances-390-dark.png'), fullPage: true })
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
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check(); await ready(page)
  await expect(page.getByRole('heading', { name: 'Закрытые смены', exact: true })).toBeVisible()
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
  await page.getByRole('combobox', { name: 'Показатели обслуживания', exact: true }).selectOption('orders'); await ready(page)
  await page.getByLabel('Текущий стол').selectOption('2'); await ready(page)
  expect(api.requests.at(-1)?.searchParams.get('section')).toBe('orders'); expect(api.requests.at(-1)?.searchParams.get('tableId')).toBe('2')
  await expect(page.getByText(/Прошлые перемещения не восстанавливаются/)).toBeVisible()
  await expect(page.locator('#stats-finance')).toHaveCount(0)
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check(); await ready(page)
  expect(api.requests.at(-1)?.searchParams.has('tableId')).toBe(false)
  await expect(page.getByLabel('Текущий стол')).toBeHidden()
})

test('stats missing table capability keeps unfiltered results and explains the unavailable filter', async ({ page }) => {
  const api = await fixture(page); await ready(page); api.failTables()
  await page.getByRole('combobox', { name: 'Показатели обслуживания', exact: true }).selectOption('calls'); await ready(page)
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
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check(); await ready(page)
    const response = page.waitForResponse(value => value.url().includes('from=2026-03-28') && value.url().includes('/stats/v1'))
    await custom(page, '2026-03-28', '2026-03-30'); await ready(page)
    const actualResponse = await response
    expect(await actualResponse.json()).toEqual(statsResponse(new URL(actualResponse.url()), { variant: 'money' }))
    const group = statsHttp.money.closedShiftFinances.find(group => group.currency === currency)!
    expect(group.metrics.accrued.value).toBe('12345')
    const value = page.locator(`#stats-finance [data-currency="${currency}"] [data-kpi="accrued"] .stats-value`)
    await expect(value).toBeVisible()
    expect(moneyNumber(await value.innerText())).toBe(expected)
    await expect(page.locator('.stats-metadata')).toContainText('28 марта 2026')
  })
}

for (const [width, theme] of [[1440, 'light'], [360, 'dark'], [390, 'dark']] as const) {
  test(`stats money mixed ISO currencies and Long maximum fit ${width}px ${theme}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 }); await page.emulateMedia({ colorScheme: theme })
    await fixture(page, { variant: 'money' }); await ready(page)
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check(); await ready(page)
    const expected: Record<string, string> = { IQD: '12,345', MGA: '123,45', RUB: '9007199254740993,12',
      EUR: '92233720368547758,07', USD: '0,01', JPY: '12345', KRW: '0', KWD: '12,345', BHD: '12,345', CLF: '1,2345' }
    for (const [currency, amount] of Object.entries(expected)) {
      const card = page.locator(`#stats-finance [data-currency="${currency}"] [data-kpi="accrued"]`)
      expect(moneyNumber(await card.locator('.stats-value').innerText()), currency).toBe(amount)
      await expect(page.locator('#stats-finance > .stats-explanation > summary')).toBeVisible()
    }
    await expect(page.locator('#stats-finance [data-currency="XXX"] [data-kpi="accrued"] .stats-value')).toHaveText('Недостаточно данных')
    await page.getByRole('radio', { name: 'Текущая смена', exact: true }).check(); await expect(page.locator('#stats-finance')).toContainText('Сейчас открытой смены нет.'); await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check()
    await page.locator('#stats-finance [data-currency="EUR"]').evaluate(node => node.scrollIntoView({ block: 'start' }))
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const screenshot = info.outputPath(`venue-money-${width}-${theme}.png`)
    await page.screenshot({ path: screenshot }); await info.attach('exact currencies', { path: screenshot, contentType: 'image/png' })
  })
}

for (const [width, theme] of [[1440, 'light'], [360, 'dark'], [390, 'dark']] as const) {
  test(`stats ${width}px ${theme} layout is readable without horizontal overflow`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 }); await page.emulateMedia({ colorScheme: theme }); await fixture(page); await ready(page)
    await expect(page.locator('html')).toHaveAttribute('data-color-scheme', theme)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const overview = info.outputPath(`venue-stats-${width}-${theme}.png`)
    await page.screenshot({ path: overview }); await info.attach('overview', { path: overview, contentType: 'image/png' })
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check(); await ready(page)
    await page.locator('#stats-finance').evaluate(node => node.scrollIntoView({ block: 'start' }))
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const unit = page.locator('#stats-finance [data-kpi="accrued"] .stats-currency-unit').first()
    await expect(unit).toHaveText('RUB')
    expect(await unit.evaluate(node => getComputedStyle(node).whiteSpace)).toBe('nowrap')
    const finance = info.outputPath(`venue-finance-${width}-${theme}.png`)
    await page.screenshot({ path: finance }); await info.attach('finance', { path: finance, contentType: 'image/png' })
  })
}

for (const width of [360, 390, 1440]) for (const theme of ['light', 'dark']) {
  test(`operations stats focused overview and product read model ${width} ${theme}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 680 })
    await page.emulateMedia({ colorScheme: theme as 'light' | 'dark' })
    const api = await fixture(page, { overview: true })
    await ready(page)
    await expect(page.locator('html')).toHaveAttribute('data-color-scheme', theme)
    await expect(page.getByRole('combobox', { name: 'Показатели', exact: true })).toHaveValue('overview')
    await chooseFinance(page, 'current'); await ready(page)
    await expect(page.locator('#stats-finance [data-kpi]')).toHaveCount(8)
    await expect(page.locator('#stats-finance')).toContainText('Текущая смена')
    await expect(page.locator('.stats-service-details [data-kpi]')).toHaveCount(3)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath(`overview-${width}-${theme}.png`), fullPage: true })
    await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check()
    await page.getByRole('button', { name: 'Последние 7 дней' }).click(); await ready(page)
    const from = api.requests.at(-1)!.searchParams.get('from')
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('products')
    await expect(page.locator('.product-category-toggle')).toHaveCount(statsHttp.products.products!.categoryTotals!.length)
    await expect(page.locator('.product-item')).toHaveCount(0)
    expect(api.requests.at(-1)!.searchParams.get('from')).toBe(from)
    await page.locator('.product-category-toggle').first().click()
    await expect(page.locator('.product-item')).toHaveCount(statsHttp.productsCategory.products!.categoryPage!.rows.length)
    await page.locator('.product-item-toggle').first().click()
    await expect(page.locator('.product-detail')).toContainText('Единиц: 4')
    await expect(page.locator('.product-detail')).toContainText('Чай из заказа')
    await expect(page.locator('.product-detail')).toContainText('Стоимость до скидок и подарков:')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath(`products-${width}-${theme}.png`), fullPage: true })
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('additions')
    await expect(page.getByText('Доля счетов с дозаказом', { exact: true })).toBeVisible()
    await expect(page.getByText(/Только завершённые счета в заказах, закрытых в период/)).toBeVisible()
    expect(api.mutations()).toBe(0)
  })
}

test('review F1 unknown gift allocation retains units and money without inventing ordinary units', async ({ page }) => {
  const api = await fixture(page, { overview: true }); await ready(page)
  api.corrupt(body => {
    if (!body.products) return
    const report = body.products
    const source = report.categoryPage?.rows[0].sources[0]
    if (source) { source.giftUnits = null; source.nonGiftUnits = null }
    if (report.summary) report.summary.giftUnits = { ...report.summary.giftUnits, value: null, observedValue: '0', availability: 'PARTIAL', coverage: 'PARTIAL', unknown: 1 }
    report.categoryTotals?.forEach(group => { group.totals.giftUnits = { ...group.totals.giftUnits, value: null, observedValue: '0', availability: 'PARTIAL', coverage: 'PARTIAL', unknown: 1 } })
  })
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('products')
  await expect(page.locator('.product-summary')).toContainText('4')
  await expect(page.locator('.product-report-warning')).toBeVisible()
  await page.locator('.product-category-toggle').first().click()
  await page.locator('.product-item-toggle').first().click()
  await expect(page.locator('.product-detail')).toContainText('подарочных: недостаточно данных')
  await expect(page.locator('.product-detail')).not.toContainText('без подарков: 4')
  expect(api.mutations()).toBe(0)
})

test('operations PostgreSQL HTTP response passes the real adapter and merchandise DOM', async ({ page }) => {
  const directory = process.env.VENUE_OPERATIONS_LIVE_DIR
  if (!directory) {
    await fixture(page, { overview: true }); await ready(page)
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('products')
    await expect(page.locator('.product-category-toggle')).toHaveCount(statsHttp.products.products!.categoryTotals!.length)
    await expect(page.locator('.product-item')).toHaveCount(0)
    return
  }
  test.setTimeout(180000)
  await expect.poll(() => { try { return JSON.parse(readFileSync(join(directory, 'ready.json'), 'utf8')) } catch { return null } }, { timeout: 90000 }).not.toBeNull()
  const live = JSON.parse(readFileSync(join(directory, 'ready.json'), 'utf8'))
  try {
    await fixture(page, { overview: true, live }); await ready(page)
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('products')
    await page.locator('.product-category-toggle').first().click()
    await page.locator('.product-item').filter({ hasText: 'Исторический чай' }).locator('.product-item-toggle').click()
    await expect(page.locator('.product-detail')).toContainText('Единиц: 4')
    await expect(page.locator('.product-item').filter({ hasText: 'Исторический чай' })).toContainText('49,380')
    await page.locator('.product-item').filter({ hasText: 'Подарок из учёта' }).locator('.product-item-toggle').click()
    await expect(page.locator('.product-detail')).toContainText('подарочных: 3')
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('additions')
    await expect(metric(page, 'additions.completedTabs').locator('.stats-value')).toHaveText('1')
    await expect(metric(page, 'additions.openTabs').locator('.stats-value')).toHaveText('1')
    await page.route('**/api/venue/1/dashboard*', async route => {
      const url = new URL(route.request().url())
      const response = await route.fetch({ url: live.base + url.pathname + url.search, headers: { Authorization: `Bearer ${live.token}` } })
      await route.fulfill({ response })
    })
    await page.evaluate(async () => {
      // Vite loads the production monitor and its normal HTTP adapter, not a test implementation.
      const url = '/miniapp/src/shared/venueEventMonitor.ts'
      const { mountVenueEventMonitor } = await import(url)
      const root = document.createElement('div'); document.body.prepend(root)
      mountVenueEventMonitor({ root, backendUrl: location.origin, isDebug: false, access: { venueId: 1, role: 'OWNER', permissions: ['BOOKING_MANAGE'] }, userId: 7, onAccessChanged() {} })
    })
    await page.getByRole('button', { name: 'Требуют внимания: 1', exact: true }).click()
    await expect(page.locator('.venue-alert-list a')).toHaveCount(1)
    await expect(page.locator('.venue-alert-list a')).toHaveAttribute('href', '#/messages?threadId=1')
    await expect(page.locator('.venue-alert-list')).not.toContainText('Private guest text')
  } finally { writeFileSync(join(directory, 'done'), 'browser completed') }
})


test('review F2 current shift loads and refreshes independently of invalid closed draft and preserves it on return', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 }); await page.emulateMedia({ colorScheme: 'dark' })
  const api = await fixture(page, { overview: true }); await ready(page)
  const mode = { selectOption: (value: 'current' | 'closed') => chooseFinance(page, value) }
  await mode.selectOption('closed'); await page.getByRole('button', { name: 'Произвольный период' }).click()
  await page.getByLabel('Начало периода').fill('2026-11-14'); await page.getByLabel('Окончание периода (включительно)').fill('2026-11-01')
  await page.getByRole('button', { name: 'Показать период' }).click(); await expect(page.locator('.stats-validation')).toContainText('не раньше')
  const before = api.requests.length; await mode.selectOption('current'); await refresh(page)
  await expect.poll(() => api.requests.length).toBeGreaterThan(before); await ready(page)
  await expect(page.locator('.stats-validation')).toBeHidden(); await expect(page.getByLabel('Начало периода')).toBeHidden()
  await page.screenshot({ path: info.outputPath('review-F2-current-invalid-draft.png'), fullPage: true })
  await mode.selectOption('closed'); await expect(page.getByLabel('Начало периода')).toHaveValue('2026-11-14'); await expect(page.getByLabel('Окончание периода (включительно)')).toHaveValue('2026-11-01')
  await expect(page.locator('.stats-validation')).toContainText('не раньше')
})

test('review F2 empty invalid and oversized drafts never gate current refresh and apply only after correction', async ({ page }) => {
  const api = await fixture(page, { overview: true, timezone: 'Europe/Berlin', asOf: '2026-03-29T10:00:00Z' }); await ready(page)
  const mode = { selectOption: (value: 'current' | 'closed') => chooseFinance(page, value) }, start = page.getByLabel('Начало периода'), end = page.getByLabel('Окончание периода (включительно)')
  for (const bad of ['', '2026-02-30', '2024-01-01']) {
    await mode.selectOption('closed'); await page.getByRole('button', { name: 'Произвольный период' }).click()
    await start.evaluate((node, value) => { (node as HTMLInputElement).value = value; node.dispatchEvent(new Event('input', { bubbles: true })) }, bad)
    await end.fill('2026-03-29'); const draft = await start.inputValue()
    await page.getByRole('button', { name: 'Показать период' }).click(); await expect(page.locator('.stats-validation')).not.toBeEmpty()
    api.corrupt(body => { const group = body.openShiftFinances[0]; if (group) group.metrics.accrued.value = '123400' })
    const count = api.requests.length; await mode.selectOption('current'); await ready(page); await refresh(page); await ready(page)
    expect(api.requests.length).toBeGreaterThan(count); await expect(page.locator('#stats-finance [data-kpi=accrued] .stats-value')).toContainText('1 234,00')
    await expect(page.locator('.stats-validation')).toBeHidden(); await mode.selectOption('closed'); await expect(start).toHaveValue(draft); await expect(page.locator('.stats-validation')).not.toBeEmpty()
    await start.fill('2026-03-29'); await page.getByRole('button', { name: 'Показать период' }).click(); await ready(page)
    expect(api.requests.at(-1)?.searchParams.get('from')).toBe('2026-03-29'); expect(api.requests.at(-1)?.searchParams.get('to')).toBe('2026-03-30')
    await expect(page.getByText('Даты не применены.', { exact: false })).toBeHidden()
  }
})

test('review F2 late closed response cannot replace current refresh and failed refresh exposes no old KPI', async ({ page }) => {
  const api = await fixture(page, { overview: true, ignoreAbort: true }); await ready(page); await chooseFinance(page, 'current'); await ready(page)
  let release!: () => void; api.hold(new Promise<void>(resolve => { release = resolve }))
  const count = api.requests.length; await page.getByRole('radio', { name: 'Закрытые смены', exact: true }).check(); await expect.poll(() => api.requests.length).toBeGreaterThan(count)
  const old = api.requests.at(-1)!; api.hold(null)
  api.corrupt(body => { if (body.openShiftFinances[0]) body.openShiftFinances[0].metrics.accrued.value = '543200' })
  await page.getByRole('radio', { name: 'Текущая смена', exact: true }).check(); await ready(page)
  await expect(page.locator('#stats-finance [data-kpi=accrued]')).toContainText('5 432,00'); await deliverLate(page, old, release)
  await expect(page.locator('#stats-finance [data-kpi=accrued]')).toContainText('5 432,00')
  api.fail(500); await refresh(page); await expect(page.getByRole('heading', { name: 'Статистика не загружена' })).toBeVisible(); await expect(page.locator('.stats-content')).toBeEmpty()
  api.fail(0); await page.getByRole('button', { name: 'Повторить', exact: true }).click(); await ready(page); await expect(page.locator('#stats-finance [data-kpi=accrued]')).toContainText('5 432,00')
})

function financialExample(body: ReturnType<typeof statsResponse>) {
  body.openShiftOpenedAt = '2026-11-13T10:00:00Z'
  for (const [kind, groups] of [['current', body.openShiftFinances], ['closed', body.closedShiftFinances]] as const) {
    const values: Record<string, string> = kind === 'current'
      ? { accrued: '99000', operatorConfirmed: '40000', cash: '40000', card: '0', transfer: '0', unpaid: '59000', sales: '1', averageAccruedSale: '99000' }
      : { accrued: '289500', operatorConfirmed: '289500', cash: '40000', card: '190500', transfer: '59000', unpaid: '0', sales: '2', averageAccruedSale: '144750' }
    for (const group of groups) for (const [key, value] of Object.entries(values)) {
      if (!group.metrics[key]) continue
      if (key === 'averageAccruedSale' && group.metrics[key].reason === 'NO_MEASURABLE_SAMPLE') {
        Object.assign(group.metrics[key], { availability: 'AVAILABLE', coverage: 'COMPLETE_RECORDED_SOURCE', reason: null, samples: Number(values.sales) })
      }
      if (key === 'averageAccruedSale' && group.metrics[key].availability === 'PARTIAL' && group.metrics[key].observedValue == null) continue
      group.metrics[key].observedValue = value
      if (group.metrics[key].availability === 'AVAILABLE') group.metrics[key].value = value
    }
  }
}
const chooseFinance = (page: Page, mode: 'closed' | 'current') => page.getByRole('radio', { name: mode === 'current' ? 'Текущая смена' : 'Закрытые смены', exact: true }).check()

// Full rendered screens from the production renderer; fixtures are synthetic read-only API envelopes.
for (const [width, theme] of [[360, 'dark'], [390, 'dark'], [1440, 'light']] as const) {
  test(`stats financial redesign full screens and context ${width} ${theme}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: width === 1440 ? 960 : 844 }); await page.emulateMedia({ colorScheme: theme })
    const api = await fixture(page, { overview: true, timezone: 'Europe/Moscow', asOf: '2026-11-16T12:00:00Z' }); await ready(page)
    api.corrupt(body => { financialExample(body); body.closedShiftFinances = body.closedShiftFinances.slice(0, 1) })
    for (const view of ['overview', 'finance']) for (const mode of ['current', 'closed'] as const) {
      await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption(view); await chooseFinance(page, mode); await refresh(page); await ready(page)
      await expect(page.locator('#stats-finance')).toContainText(mode === 'current' ? 'Текущая смена' : 'Закрытые смены')
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      if (width === 390) {
        await page.evaluate(() => window.scrollTo(0, 0))
        const last = await page.locator('#stats-finance [data-kpi=averageAccruedSale] .stats-value').boundingBox()
        expect(last!.y + last!.height).toBeLessThanOrEqual(844)
      }
      if (width !== 360) await page.screenshot({ path: info.outputPath(`${view}-${mode}-${width}-${theme}.png`), fullPage: true })
    }
    api.setVariant('empty'); await refresh(page); await ready(page)
    await expect(page.locator('#stats-finance')).toContainText('Нет смен, закрытых в выбранный период.')
    if (width !== 360) await page.screenshot({ path: info.outputPath(`empty-${width}-${theme}.png`), fullPage: true })
    api.setVariant('partial'); await refresh(page); await ready(page)
    await expect(page.locator('.stats-coverage')).toContainText('Частичные данные')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    if (width !== 360) await page.screenshot({ path: info.outputPath(`partial-${width}-${theme}.png`), fullPage: true })
    expect(api.mutations()).toBe(0)
  })
}

test('stats financial redesign keeps receipt methods visible in overview and closed finance', async ({ page }) => {
  const api = await fixture(page, { overview: true }); await ready(page); api.corrupt(financialExample)
  for (const view of ['overview', 'finance']) for (const mode of ['current', 'closed'] as const) {
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption(view); await chooseFinance(page, mode); await refresh(page); await ready(page)
    const rub = page.locator('.stats-currency[data-currency="RUB"]')
    for (const key of ['operatorConfirmed', 'cash', 'card', 'transfer', 'accrued', 'unpaid', 'sales', 'averageAccruedSale']) {
      await expect(rub.locator(`[data-kpi="${key}"]`)).toBeVisible()
      expect(await rub.locator(`[data-kpi="${key}"]`).evaluate(n => n.closest('details:not([open])') === null)).toBe(true)
    }
    await expect(rub.locator('[data-kpi="sales"] .stats-value')).toHaveText(mode === 'closed' ? '2' : '1')
    await expect(rub.locator('[data-kpi="averageAccruedSale"] .stats-value')).toHaveText(exactMoney(mode === 'closed' ? '144750' : '99000', 'RUB'))
    await expect(rub.locator('[data-kpi="cash"] .stats-value')).toHaveText(exactMoney('40000', 'RUB'))
    await expect(rub.locator('[data-kpi="card"] .stats-value')).toHaveText(exactMoney(mode === 'closed' ? '190500' : '0', 'RUB'))
    await expect(rub.locator('[data-kpi="transfer"] .stats-value')).toHaveText(exactMoney(mode === 'closed' ? '59000' : '0', 'RUB'))
    await expect(page.locator('.stats-content')).not.toContainText('Комбинированная')
  }
  expect(api.mutations()).toBe(0)
})

test('stats financial redesign defaults to closed week and keeps thirty days separate from the whole open shift', async ({ page }) => {
  const api = await fixture(page, { overview: true, timezone: 'Asia/Tokyo', asOf: '2026-11-10T22:30:00Z' }); await ready(page)
  await expect(page.getByRole('radio', { name: 'Закрытые смены', exact: true })).toBeChecked()
  await expect(page.getByRole('button', { name: 'Последние 7 дней', exact: true })).toHaveAttribute('aria-pressed', 'true')
  expect(api.requests.at(-1)?.searchParams.get('from')).toBe('2026-11-05')
  api.corrupt(financialExample)
  await page.getByRole('button', { name: 'Последние 30 дней', exact: true }).click(); await ready(page)
  expect(api.requests.at(-1)?.searchParams.get('from')).toBe('2026-10-13')
  expect(api.requests.at(-1)?.searchParams.get('to')).toBe('2026-11-12')
  await expect(page.locator('.stats-metadata')).toContainText('по дате закрытия')
  await expect(page.locator('#stats-finance')).toContainText('Смен в расчёте: 2')
  await chooseFinance(page, 'current'); await ready(page)
  await expect(page.getByRole('button', { name: 'Последние 30 дней', exact: true })).toBeHidden()
  await expect(page.locator('.stats-metadata')).toContainText('Вся текущая смена с момента открытия')
  await expect(page.locator('#stats-finance')).toContainText('Предварительные суммы всей открытой смены')
  await expect(metric(page, 'operatorConfirmed').locator('.stats-value')).toHaveText(exactMoney('40000', 'RUB'))
  await expect(metric(page, 'sales').locator('.stats-value')).toHaveText('1')
  await chooseFinance(page, 'closed'); await ready(page)
  expect(api.requests.at(-1)?.searchParams.get('from')).toBe('2026-10-13')
  await expect(metric(page, 'sales').first().locator('.stats-value')).toHaveText('2')
  expect(api.mutations()).toBe(0)
})

test('stats financial redesign keeps incomplete methods and hidden detail warnings visible without false zero', async ({ page }) => {
  const api = await fixture(page, { overview: true, variant: 'partial' }); await ready(page)
  const rub = page.locator('.stats-currency[data-currency="RUB"]')
  await expect(page.locator('.stats-coverage')).toContainText('Частичные данные')
  await expect(rub.locator('[data-kpi="cash"]')).toBeVisible()
  await expect(rub.locator('[data-kpi="cash"]')).toContainText('По известным данным:')
  await expect(rub.locator('.stats-warning').filter({ hasText: 'Скидки:' })).toBeVisible()
  await expect(page.locator('.stats-currency')).toHaveCount(2)
  await chooseFinance(page, 'current'); await ready(page)
  await expect(page.locator('.stats-coverage')).toBeHidden() // Closed/operational partial history cannot taint the current finance source.
  api.corrupt(body => { const group = body.openShiftFinances[0]; if (group) delete group.metrics.cash })
  await refresh(page); await ready(page)
  await expect(rub.locator('[data-kpi="cash"] .stats-value')).toHaveText('Недостаточно данных')
  await expect(rub.locator('[data-kpi="card"] .stats-value')).toHaveText(exactMoney('0', 'RUB'))
  await expect(rub.locator('[data-kpi="averageAccruedSale"] .stats-value')).toHaveText('Недостаточно данных')
  expect(api.mutations()).toBe(0)
})

test('stats financial redesign explains empty closed period with open shift and disclosures make no requests', async ({ page }) => {
  const api = await fixture(page, { overview: true }); await ready(page)
  api.corrupt(body => {
    body.closedShiftFinances = []; Object.assign(body.metrics['finance.closedReports'], { value: '0', observedValue: '0', samples: 0 })
    body.openShiftOpenedAt = '2026-11-13T10:00:00Z'
  })
  await refresh(page); await ready(page)
  await expect(page.locator('#stats-finance')).toContainText('Нет смен, закрытых в выбранный период.')
  await expect(page.locator('#stats-finance')).toContainText('Она не входит в отчёт закрытых смен')
  await expect(page.locator('.stats-currency')).toHaveCount(0)
  await page.getByRole('button', { name: 'Показать текущую смену', exact: true }).click(); await ready(page)
  await expect(page.getByRole('radio', { name: 'Текущая смена', exact: true })).toBeChecked()
  await expect(page.locator('#stats-finance')).toContainText(/13\.11\.(?:20)?26/)
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await ready(page)
  const before = api.requests.length
  await page.locator('.stats-finance-details > summary').click()
  await expect(metric(page, 'discounts')).toBeVisible()
  await page.locator('#stats-finance > .stats-explanation > summary').click()
  await expect(page.locator('#stats-finance > .stats-explanation')).toContainText('компоненты комбинированных оплат')
  await page.locator('.stats-finance-details > summary').click()
  expect(api.requests.length).toBe(before); expect(api.mutations()).toBe(0)
})

test('stats financial redesign native mode keyboard short viewport and focus stay usable', async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 420 })
  const api = await fixture(page, { overview: true }); await ready(page)
  const current = page.getByRole('radio', { name: 'Текущая смена', exact: true })
  await current.focus(); await current.press('Space'); await ready(page)
  await expect(current).toBeChecked(); await expect(current).toBeFocused()
  await current.press('ArrowRight'); await ready(page)
  await expect(page.getByRole('radio', { name: 'Закрытые смены', exact: true })).toBeChecked()
  await expect(page.getByRole('radio', { name: 'Закрытые смены', exact: true })).toBeFocused()
  await page.getByRole('button', { name: 'Произвольный период', exact: true }).click()
  const start = page.getByLabel('Начало периода'), end = page.getByLabel('Окончание периода (включительно)')
  await start.fill('2026-03-29'); await end.fill('2026-03-29'); await end.press('Enter'); await ready(page)
  await expect(end).toBeFocused(); expect(api.requests.at(-1)?.searchParams.get('to')).toBe('2026-03-30')
  await page.locator('#stats-finance > .stats-explanation > summary').focus()
  await page.keyboard.press('Enter'); await expect(page.locator('#stats-finance > .stats-explanation')).toHaveAttribute('open', '')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  expect(api.mutations()).toBe(0)
})

test('stats financial redesign selected coverage and unavailable methods remain visible without false zeros', async ({ page }) => {
  const api = await fixture(page, { overview: true, variant: 'partial' }); await ready(page)
  const rub = page.locator('.stats-currency[data-currency="RUB"]')
  await expect(page.locator('.stats-coverage')).toContainText('Частичные данные')
  await expect(rub.locator('[data-kpi=cash]')).toBeVisible()
  await expect(rub.locator('[data-kpi=cash] .stats-value')).toHaveText('Частичные данные')
  await expect(rub.locator('[data-kpi=cash] .stats-observed')).toContainText(exactMoney('200', 'RUB'))
  await expect(rub.locator('[data-kpi=averageAccruedSale] .stats-observed')).toHaveCount(0)
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await ready(page)
  await expect(page.locator('.stats-finance-details').first()).not.toHaveAttribute('open', '')
  await expect(rub.locator('.stats-warning').filter({ hasText: 'Скидки:' })).toBeVisible()
  await expect(page.locator('.stats-currency')).toHaveCount(2)
  await chooseFinance(page, 'current'); await ready(page)
  await expect(page.locator('.stats-coverage')).toBeHidden()
  await expect(page.locator('#stats-finance')).not.toContainText('Частичные данные')
  api.corrupt(body => {
    const group = body.openShiftFinances[0]; delete group.metrics.cash
    Object.assign(group.metrics.discounts, { value: null, observedValue: null, availability: 'UNAVAILABLE', reason: 'UNKNOWN_CAPABILITY' })
  })
  await refresh(page); await ready(page)
  await expect(rub.locator('[data-kpi=cash] .stats-value')).toHaveText('Недостаточно данных')
  await expect(rub.locator('.stats-warning').filter({ hasText: 'Скидки:' })).toBeVisible()
  await expect(page.locator('#stats-finance')).not.toContainText('UNKNOWN_CAPABILITY')
  expect(api.mutations()).toBe(0)
})

function viewportFinancials(body: ReturnType<typeof statsResponse>) {
  financialExample(body)
  body.closedShiftFinances = body.closedShiftFinances.slice(0, 1)
  const values: Record<string, string> = { operatorConfirmed: '350000', cash: '100000', card: '200000', transfer: '50000', accrued: '400000', unpaid: '50000', sales: '2', averageAccruedSale: '200000' }
  for (const group of body.closedShiftFinances) for (const [key, value] of Object.entries(values)) {
    const kpi = group.metrics[key]; kpi.observedValue = value
    if (kpi.availability === 'AVAILABLE') kpi.value = value
  }
}
async function receiptViewport(page: Page, safeBottom = 0) {
  return page.locator('#stats-finance [data-kpi]').evaluateAll((nodes, bottomInset) => {
    const viewport = window.visualViewport
    const frame = { top: viewport?.offsetTop ?? 0, bottom: (viewport?.offsetTop ?? 0) + (viewport?.height ?? innerHeight) - bottomInset, left: viewport?.offsetLeft ?? 0, right: (viewport?.offsetLeft ?? 0) + (viewport?.width ?? innerWidth) }
    const keys = ['operatorConfirmed', 'cash', 'card', 'transfer']
    return { scrollY, frame, cards: nodes.filter(n => keys.includes((n as HTMLElement).dataset.kpi!)).map(node => {
      const r = node.getBoundingClientRect()
      let top = frame.top, bottom = frame.bottom, left = frame.left, right = frame.right
      for (let parent = node.parentElement; parent; parent = parent.parentElement) {
        const style = getComputedStyle(parent), box = parent.getBoundingClientRect()
        if (/(auto|scroll|hidden|clip)/.test(style.overflowY)) { top = Math.max(top, box.top + parent.clientTop); bottom = Math.min(bottom, box.top + parent.clientTop + parent.clientHeight) }
        if (/(auto|scroll|hidden|clip)/.test(style.overflowX)) { left = Math.max(left, box.left + parent.clientLeft); right = Math.min(right, box.left + parent.clientLeft + parent.clientWidth) }
      }
      const inside = r.top >= top && r.bottom <= bottom && r.left >= left && r.right <= right
      const unobscured = inside && [[r.left + 2, r.top + 2], [r.right - 2, r.bottom - 2], [(r.left + r.right) / 2, (r.top + r.bottom) / 2]].every(([x, y]) => {
        const hit = document.elementFromPoint(x, y); return hit != null && (node === hit || node.contains(hit))
      })
      return { key: (node as HTMLElement).dataset.kpi!, top: r.top, bottom: r.bottom, left: r.left, right: r.right, inside, unobscured }
    }) }
  }, safeBottom)
}

for (const [width, height] of [[390, 844], [360, 780]]) for (const theme of ['light', 'dark'] as const) {
  test(`stats shell first viewport receipts ${width}x${height} ${theme}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height }); await page.emulateMedia({ colorScheme: theme })
    const api = await fixture(page, { shell: true, entry: 'more', overview: true, timezone: 'Europe/Moscow', asOf: '2026-11-16T12:00:00Z' })
    api.corrupt(viewportFinancials)
    await page.getByRole('link', { name: 'Статистика', exact: true }).click(); await ready(page)
    const measurements = []
    for (const [view, mode] of [['finance', 'closed'], ['overview', 'closed'], ['overview', 'current'], ['finance', 'current']] as const) {
      await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption(view); await chooseFinance(page, mode); await ready(page)
      await expect(page.locator('.venue-shell .venue-header')).toBeVisible()
      await expect(page.getByRole('combobox', { name: 'Выбрать заведение', exact: true })).toBeVisible()
      await expect(page.getByRole('navigation', { name: 'Основная навигация' })).toBeVisible()
      await expect(page.locator('.stats-header h2')).toHaveText('Статистика')
      const context = await page.locator('.stats-header h2, .stats-finance-mode label:has(:checked), .stats-period-caption:not([hidden])').evaluateAll(nodes => nodes.map(n => {
        const r = n.getBoundingClientRect(); return { text: n.textContent, top: r.top, bottom: r.bottom }
      }))
      const measurement = { view, mode, context, ...await receiptViewport(page) }; measurements.push(measurement)
      if (view === 'finance' && mode === 'closed' || width === 390 && theme === 'dark') await page.screenshot({ path: info.outputPath(`shell-${view}-${mode}-${width}-${theme}.png`), fullPage: true })
    }
    await info.attach('viewport-coordinates', { body: JSON.stringify(measurements, null, 2), contentType: 'application/json' })
    for (const m of measurements) {
      expect(m.scrollY).toBe(0)
      expect(m.cards).toHaveLength(4)
      for (const item of m.context) { expect(item.top).toBeGreaterThanOrEqual(m.frame.top); expect(item.bottom).toBeLessThanOrEqual(m.frame.bottom) }
      expect(m.context).toHaveLength(m.mode === 'closed' ? 3 : 2)
      for (const card of m.cards) {
        expect(card.top, `${m.view}/${m.mode}/${card.key} top`).toBeGreaterThanOrEqual(m.frame.top)
        expect(card.bottom, `${m.view}/${m.mode}/${card.key} bottom`).toBeLessThanOrEqual(m.frame.bottom)
        expect(card.inside, `${m.view}/${m.mode}/${card.key} ancestor clipping`).toBe(true)
        expect(card.unobscured, `${m.view}/${m.mode}/${card.key} hit test`).toBe(true)
      }
    }
    expect(api.mutations()).toBe(0)
  })
}

for (const theme of ['light', 'dark'] as const) {
  test(`stats shell short viewport scroll warnings currencies and navigation ${theme}`, async ({ page }, info) => {
    await page.setViewportSize({ width: 390, height: 650 }); await page.emulateMedia({ colorScheme: theme })
    const api = await fixture(page, { shell: true, entry: 'more', overview: true })
    await page.getByRole('link', { name: 'Статистика', exact: true }).click(); await ready(page)
    await page.evaluate(() => {
      document.documentElement.style.setProperty('--tg-safe-area-inset-bottom', '20px')
      document.documentElement.style.setProperty('--tg-content-safe-area-inset-bottom', '12px')
    })
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await ready(page)
    await expect(page.locator('.stats-currency')).toHaveCount(2)
    const rub = page.locator('.stats-currency[data-currency="RUB"]'), usd = page.locator('.stats-currency[data-currency="USD"]')
    await expect(rub.locator('[data-kpi=accrued] .stats-value')).toHaveText(exactMoney('900719925474099312', 'RUB'))
    await expect(usd.locator('[data-kpi=accrued] .stats-value')).toHaveText(exactMoney('12345', 'USD'))
    for (const currency of ['RUB', 'USD']) {
      await page.locator(`.stats-currency[data-currency="${currency}"] .stats-received`).evaluate(n => n.scrollIntoView({ block: 'center' }))
      const m = await receiptViewport(page, 32)
      for (const card of m.cards.filter(card => card.inside)) expect(card.unobscured).toBe(true)
      const bounds = await page.locator(`.stats-currency[data-currency="${currency}"] .stats-received`).boundingBox()
      expect(bounds!.y).toBeGreaterThanOrEqual(0); expect(bounds!.y + bounds!.height).toBeLessThanOrEqual(618)
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const details = page.locator('.stats-period-details'), disclosure = details.locator('summary')
    const before = api.requests.length
    await disclosure.focus(); await page.keyboard.press('Enter')
    await expect(details).toHaveAttribute('open', ''); await expect(disclosure).toBeFocused()
    await expect(details).toContainText('Часовой пояс: Europe/Berlin')
    await page.keyboard.press('Enter'); await expect(details).not.toHaveAttribute('open', '')
    expect(api.requests).toHaveLength(before)
    api.setVariant('partial'); await refresh(page); await ready(page)
    await expect(page.locator('.stats-coverage')).toContainText('Частичные данные')
    await expect(rub.locator('[data-kpi=cash] .stats-value')).toHaveText('Частичные данные')
    expect(await rub.locator('[data-kpi=cash]').evaluate(n => n.closest('details:not([open])') === null)).toBe(true)
    await page.screenshot({ path: info.outputPath(`shell-partial-390x650-${theme}.png`), fullPage: true })
    api.corrupt(body => { const g = body.closedShiftFinances[0]; if (g) { g.currency = 'UNKNOWN'; delete g.metrics.cash } })
    await refresh(page); await ready(page)
    const unknown = page.locator('.stats-currency[data-currency="UNKNOWN"]')
    await expect(unknown).toContainText('Валюта: не определена')
    await expect(unknown.locator('[data-kpi=cash] .stats-value')).toHaveText('Недостаточно данных')
    api.corrupt(null); api.setVariant('empty'); await refresh(page); await ready(page)
    await expect(page.locator('#stats-finance')).toContainText('Нет смен, закрытых в выбранный период.')
    await page.getByRole('button', { name: 'Последние 30 дней', exact: true }).click(); await ready(page)
    await expect(page.getByRole('button', { name: 'Последние 30 дней', exact: true })).toHaveAttribute('aria-pressed', 'true')
    await expect(page.locator('.stats-currency')).toHaveCount(0)
    await page.screenshot({ path: info.outputPath(`shell-empty-390x650-${theme}.png`), fullPage: true })
    api.setVariant('complete'); await page.getByRole('button', { name: 'Произвольный период', exact: true }).click()
    const start = page.getByLabel('Начало периода'), end = page.getByLabel('Окончание периода (включительно)')
    await start.fill('2026-03-29'); await end.fill('2026-03-29'); await end.press('Enter'); await ready(page)
    await expect(end).toBeFocused(); expect(api.requests.at(-1)?.searchParams.get('to')).toBe('2026-03-30')
    await page.evaluate(() => (window as unknown as { emitStatsBack: () => void }).emitStatsBack())
    await expect(page).toHaveURL(/#\/more$/)
    await page.getByRole('link', { name: 'Статистика', exact: true }).click(); await ready(page)
    await expect(page.locator('.stats-header h2')).toBeVisible()
    expect(api.mutations()).toBe(0)
  })
}
