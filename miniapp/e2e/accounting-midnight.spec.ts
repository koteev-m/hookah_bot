import { test, expect, type Page } from '@playwright/test'
import { salePaid, salePayable, type AccountingCommand, type AccountingReport, type AccountingSale } from '../src/shared/api/accounting'

const midnight = '2026-11-15T20:59:59Z'
const sale = (id: string, at = '2026-11-15T12:00:00Z'): AccountingSale => ({ id, createdAt: at, tableId: 11, tableLabel: 'Стол 1', source: 'MANUAL', orderId: null, displayNumber: null, orderClosed: true, currency: 'RUB', version: 1, cancelReason: null, disputeReason: null, originalPayableMinor: null, issue: null,
  lines: [{ id: `line-${id}`, name: 'Чай', qty: 1, unitPriceMinor: 99000, menuItemId: null, source: 'MANUAL', tabId: null, baseDiscountMinor: 0, baseGiftMinor: 0, discountMinor: 0, giftReason: null }], payments: [] })
function report(venueId: number, timezone: string, sales = [sale('manual:one'), sale('manual:two')]): AccountingReport {
  const accrued = sales.reduce((v, s) => v + salePayable(s), 0), paid = sales.reduce((v, s) => v + salePaid(s), 0)
  return { shiftId: venueId, venueId, timezone, status: 'OPEN', openedAt: sales[0]?.createdAt ?? '2026-11-13T01:00:00Z', closedAt: null, currency: 'RUB', sales,
    totals: { salesCount: sales.length, ordersCount: 0, grossMinor: accrued, discountMinor: 0, giftMinor: 0, accruedMinor: accrued, paidMinor: paid, unpaidMinor: accrued - paid, cashMinor: paid, cardMinor: 0, transferMinor: 0 },
    byTable: [], bySource: [], byItem: [], unpaidSaleIds: sales.filter(s => salePayable(s) > salePaid(s)).map(s => s.id), disputedSaleIds: sales.filter(s => s.disputeReason).map(s => s.id), reportHash: 'unchanged-midnight-report' }
}
async function fixture(page: Page, at = midnight, timezone = 'Europe/Moscow', sales?: AccountingSale[]) {
  await page.setViewportSize({ width: 390, height: 844 }); await page.emulateMedia({ colorScheme: 'dark' })
  await page.clock.install({ time: new Date(Date.parse(at) - 1000) }); await page.clock.pauseAt(new Date(at))
  const reports = new Map([[1, report(1, timezone, sales)], [2, report(2, 'America/Los_Angeles')]])
  const commands: AccountingCommand[] = [], gets: number[] = []
  let failPayment = false, barrier: Promise<void> | null = null
  await page.addInitScript(() => {
    const handlers = new Map<string, Set<() => void>>()
    const hooks = new Map<string, Set<any>>()
    const add = EventTarget.prototype.addEventListener, remove = EventTarget.prototype.removeEventListener
    const hook = (target: EventTarget, type: string) => (target === document && type === 'visibilitychange') || (target === window && ['pageshow', 'pagehide'].includes(type))
    EventTarget.prototype.addEventListener = function(type, cb, options) { if (hook(this, type)) { if (!hooks.has(type)) hooks.set(type, new Set()); hooks.get(type)!.add(cb) } return add.call(this, type, cb, options) }
    EventTarget.prototype.removeEventListener = function(type, cb, options) { if (hook(this, type)) hooks.get(type)?.delete(cb); return remove.call(this, type, cb, options) }
    Object.assign(window, { midnightHooks: () => Object.fromEntries([...hooks].map(([k, v]) => [k, v.size])), telegramHandlers: handlers,
      telegramEvent: (type: string) => handlers.get(type)?.forEach(cb => cb()),
      Telegram: { WebApp: { initDataUnsafe: { user: { id: 103 } }, ready() {}, expand() {}, onEvent(type: string, cb: () => void) { if (!handlers.has(type)) handlers.set(type, new Set()); handlers.get(type)!.add(cb) }, offEvent(type: string, cb: () => void) { handlers.get(type)?.delete(cb) } } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:103`, JSON.stringify({ token: 'isolated-midnight-fixture', expiresAtEpochSeconds: 4102444800 }))
  })
  await page.route('**/miniapp/midnight-test*', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"></head><body><div id="app"></div><script type="module">import {mountVenueApp} from '/miniapp/src/screens/venueApp.ts';import {bindAppTheme} from '/miniapp/src/shared/ui/appTheme.ts';const unbind=bindAppTheme(document.documentElement);const dispose=mountVenueApp({root:document.querySelector('#app'),backendUrl:location.origin});window.disposeMidnightApp=()=>{dispose();unbind()};</script></body></html>` }))
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    if (url.pathname === '/api/venue/me') return route.fulfill({ json: { userId: 103, venues: [1, 2].map(id => ({ venueId: id, venueName: `Заведение ${id}`, venueStatus: 'PUBLISHED', role: 'OWNER', permissions: ['MENU_VIEW', 'TABLE_VIEW'] })) } })
    if (url.pathname === '/api/venue/tables') return route.fulfill({ json: { tables: [{ tableId: 11, tableNumber: 1, tableLabel: 'Стол 1', isActive: true }] } })
    if (url.pathname === '/api/venue/menu') return route.fulfill({ json: { venueId: 1, categories: [] } })
    const match = /^\/api\/venue\/(\d+)\/accounting(?:\/(commands|\d+))?$/.exec(url.pathname)
    if (!match) return route.fulfill({ json: { unreadCount: 0, calls: [], items: [], linked: false } })
    const venue = Number(match[1]), current = structuredClone(reports.get(venue)!)
    if (req.method() === 'POST') {
      commands.push(req.postDataJSON()); if (failPayment) return route.fulfill({ status: 500, json: { error: { code: 'INTERNAL_ERROR', message: 'Неизвестный результат' } } })
      return route.fulfill({ json: current })
    }
    gets.push(venue)
    if (barrier && venue === 1) { const wait = barrier; barrier = null; await wait }
    await route.fulfill({ json: match[2] ? current : { current, history: [] } }).catch(() => { /* Aborted old-venue request. */ })
  })
  await page.goto('midnight-test?venueId=1#/accounting?av=1'); await expect(page.locator('[data-today-count]')).toHaveAttribute('data-today-count', String(reports.get(1)!.sales.length))
  return { reports, commands, gets, fail() { failPayment = true }, hold() { let release!: () => void; barrier = new Promise<void>(r => { release = r }); return release } }
}
const today = (page: Page) => page.locator('[data-today-count]')
const shift = (page: Page) => page.locator('[data-shift-count]')
const storage = (page: Page) => page.evaluate(() => Object.fromEntries(Object.entries(sessionStorage).filter(([k]) => k.includes('hookah:accounting:'))))
const visibility = (page: Page, state: 'hidden' | 'visible') => page.evaluate(state => { Object.defineProperty(document, 'visibilityState', { configurable: true, value: state }); document.dispatchEvent(new Event('visibilitychange')) }, state)
const postMidnight = (page: Page) => page.clock.runFor(2000)

test('midnight full mountVenueApp updates counters labels and old shift without requests', async ({ page }, info) => {
  const api = await fixture(page), before = JSON.stringify(api.reports.get(1)), getCount = api.gets.length
  await expect(page.locator('[data-sale-date="2026-11-15"]')).toContainText('Сегодня'); await page.screenshot({ path: info.outputPath('before-midnight.png'), fullPage: true })
  await postMidnight(page)
  await expect.soft(today(page)).toHaveAttribute('data-today-count', '0'); await expect.soft(page.locator('[data-sale-date="2026-11-15"]')).not.toContainText('Сегодня')
  await expect(shift(page)).toHaveAttribute('data-shift-count', '2'); await expect(page.locator('.accounting-old-shift')).toBeVisible()
  await page.screenshot({ path: info.outputPath('after-midnight.png'), fullPage: true }); expect(api.gets).toHaveLength(getCount); expect(api.commands).toEqual([]); expect(JSON.stringify(api.reports.get(1))).toBe(before)
})

for (const event of ['visibilitychange', 'pageshow', 'activated']) test(`midnight background return via ${event} updates without refresh`, async ({ page }) => {
  const api = await fixture(page), getCount = api.gets.length
  if (event === 'activated') await page.evaluate(() => (window as any).telegramEvent('deactivated'))
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true }))); await visibility(page, 'hidden'); await page.clock.setSystemTime(new Date('2026-11-17T21:00:01Z'))
  await expect(today(page)).toHaveAttribute('data-today-count', '2')
  if (event === 'visibilitychange') await visibility(page, 'visible')
  else { await page.evaluate(() => Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })); await page.evaluate(event => event === 'pageshow' ? window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })) : (window as any).telegramEvent('activated'), event) }
  await expect(today(page)).toHaveAttribute('data-today-count', '0'); await expect(page.locator('[data-sale-date]')).not.toContainText('Сегодня'); await expect(shift(page)).toHaveAttribute('data-shift-count', '2')
  expect(api.gets).toHaveLength(getCount); expect(api.commands).toEqual([])
})

test('midnight sale draft focus selection scroll and Back survive rollover', async ({ page }) => {
  const api = await fixture(page); await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11'); const name = page.getByLabel('Название', { exact: true }); await name.fill('Черновик после полуночи')
  await page.getByLabel('Цена за единицу, ₽').fill('1500'); await name.focus(); await name.press('ArrowLeft')
  const node = await name.elementHandle(), saved = await storage(page), url = page.url(), selection = await name.evaluate(n => (n as HTMLInputElement).selectionStart), scroll = await page.evaluate(() => ({ x: scrollX, y: scrollY }))
  await postMidnight(page); await expect(name).toBeFocused(); expect(await node!.evaluate(n => n.isConnected)).toBe(true); await expect(name).toHaveValue('Черновик после полуночи'); expect(await name.evaluate(n => (n as HTMLInputElement).selectionStart)).toBe(selection); expect(await storage(page)).toEqual(saved); expect(page.url()).toBe(url); expect(await page.evaluate(() => ({ x: scrollX, y: scrollY }))).toEqual(scroll)
  await expect(today(page)).toHaveAttribute('data-today-count', '0'); await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await expect(today(page)).toHaveAttribute('data-today-count', '0'); await expect(page.locator('[data-sale-date]')).not.toContainText('Сегодня'); expect(api.commands).toEqual([])
})

test('midnight payment draft focus and uncertain pending request remain exact', async ({ page }) => {
  const api = await fixture(page); await page.locator('.accounting-sales [data-sale-id="manual:one"]').click()
  await page.getByLabel('Частичная оплата', { exact: true }).check(); const amount = page.getByLabel('Получено, ₽'); await amount.fill('400'); await page.getByLabel('Подтверждаю частичную оплату:', { exact: false }).check(); await amount.focus()
  const handle = await amount.elementHandle(), saved = await storage(page), url = page.url(), scroll = await page.evaluate(() => scrollY)
  await postMidnight(page); await expect(amount).toBeFocused(); expect(await handle!.evaluate(n => n.isConnected)).toBe(true); await expect(amount).toHaveValue('400'); expect(await storage(page)).toEqual(saved); expect(page.url()).toBe(url); expect(await page.evaluate(() => scrollY)).toBe(scroll); expect(api.commands).toEqual([])
  api.fail(); await page.locator('.accounting-payment-form button[type=submit]').click(); await expect(page.locator('.accounting-pending')).toBeVisible(); const pending = await storage(page), pendingNode = await page.locator('.accounting-pending').elementHandle()
  await visibility(page, 'hidden'); await page.clock.setSystemTime(new Date('2026-11-17T21:00:01Z')); await visibility(page, 'visible'); await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })))
  expect(await pendingNode!.evaluate(n => n.isConnected)).toBe(true); expect(await storage(page)).toEqual(pending); expect(api.commands).toHaveLength(1); expect(api.commands[0]).toMatchObject({ action: 'PAY', saleId: 'manual:one', amountMinor: 40000 }); expect(page.url()).toBe(url)
})

for (const [label, before, next] of [['spring23h', '2026-03-28T22:59:59Z', '2026-03-29T21:59:59Z'], ['autumn25h', '2026-10-24T21:59:59Z', '2026-10-25T22:59:59Z']] as const) test(`midnight venue timezone DST ${label} differs from device`, async ({ browser }) => {
  const context = await browser.newContext({ timezoneId: 'America/Los_Angeles' }), page = await context.newPage()
  try {
    const api = await fixture(page, before, 'Europe/Berlin', [sale('manual:one', before), sale('manual:two', before)])
    await postMidnight(page); await expect(today(page)).toHaveAttribute('data-today-count', '0'); await expect(page.locator('[data-sale-date]')).not.toContainText('Сегодня')
    const currentDate = await page.evaluate(() => new Date().toISOString()); api.reports.set(1, report(1, 'Europe/Berlin', [sale('manual:one', currentDate), sale('manual:two', currentDate)]))
    await page.getByRole('button', { name: 'Обновить отчёт', exact: true }).click(); await expect(today(page)).toHaveAttribute('data-today-count', '2'); await page.clock.fastForward(Date.parse(next) - Date.parse(currentDate)); await expect(today(page)).toHaveAttribute('data-today-count', '2')
    await postMidnight(page); await expect(today(page)).toHaveAttribute('data-today-count', '0'); await expect(page.locator('[data-sale-date]')).not.toContainText('Сегодня'); await expect(shift(page)).toHaveAttribute('data-shift-count', '2'); expect(api.commands).toEqual([])
  } finally { await context.close() }
})

test('midnight venue switch late response and disposal remove scoped lifecycle handlers', async ({ page }) => {
  const api = await fixture(page), old = await today(page).elementHandle(); const release = api.hold()
  await page.getByRole('button', { name: 'Обновить отчёт', exact: true }).click(); await expect.poll(() => api.gets.length).toBe(2)
  await page.getByLabel('Выбрать заведение').selectOption('2'); await expect(page.locator('.accounting-shift-summary')).toContainText('America/Los_Angeles'); release()
  await postMidnight(page); await expect(today(page)).toHaveAttribute('data-today-count', '2'); expect(await old!.evaluate(n => n.textContent)).toContain('2')
  await page.getByLabel('Выбрать заведение').selectOption('1'); await expect(today(page)).toHaveAttribute('data-today-count', '0')
  expect(await page.evaluate(() => (window as any).midnightHooks())).toEqual({ visibilitychange: 1, pageshow: 1, pagehide: 1 })
  expect(await page.evaluate(() => (window as any).telegramHandlers.get('activated').size)).toBe(1)
  await page.evaluate(() => (window as any).disposeMidnightApp()); expect(await page.evaluate(() => (window as any).midnightHooks())).toEqual({ visibilitychange: 0, pageshow: 0, pagehide: 0 })
  expect(await page.evaluate(() => (window as any).telegramHandlers.get('activated').size)).toBe(0); expect(await page.evaluate(() => (window as any).telegramHandlers.get('deactivated').size)).toBe(0)
  const disposed = await page.locator('#app').innerHTML(), gets = api.gets.length
  await page.clock.runFor(120000); await page.evaluate(() => { window.dispatchEvent(new Event('pageshow')); document.dispatchEvent(new Event('visibilitychange')); (window as any).telegramEvent('activated') }); expect(await page.locator('#app').innerHTML()).toBe(disposed); expect(api.gets).toHaveLength(gets); expect(api.commands).toEqual([])
})

test('midnight filters corrections and explicit report refresh retain financial scope', async ({ page }) => {
  const one = sale('manual:one'), two = sale('manual:two'); two.disputeReason = 'Проверить источник'; two.payments = [{ id: 'voided-payment-00000001', method: 'CASH', amountMinor: 20000, recordedAt: midnight, voidReason: 'Неверный способ' }, { id: 'active-payment-00000001', method: 'CASH', amountMinor: 40000, recordedAt: midnight, voidReason: null }]
  const api = await fixture(page, midnight, 'Europe/Moscow', [one, two]); await page.getByRole('button', { name: 'Частично', exact: true }).click(); const url = page.url(), totals = await page.locator('.accounting-shift-totals').textContent()
  await postMidnight(page); await expect(today(page)).toHaveAttribute('data-today-count', '0'); expect(await page.locator('.accounting-shift-totals').textContent()).toBe(totals); expect(page.url()).toBe(url); await expect(page.locator('.accounting-sales button[data-sale-id]')).toHaveCount(1); await expect(page.locator('[data-sale-id="manual:two"]')).toContainText('Спорная')
  await page.getByRole('button', { name: 'Оплаты смены', exact: true }).click(); await expect(page.locator('.accounting-shift-payments')).toContainText('Неверный способ'); await expect(page.locator('[data-payment-component]')).toHaveCount(2)
  api.reports.set(1, report(1, 'Europe/Moscow', [one, two, sale('manual:new-day', '2026-11-15T21:00:00Z')]))
  await page.getByRole('button', { name: 'Обновить отчёт', exact: true }).click(); await expect(today(page)).toHaveAttribute('data-today-count', '1'); await expect(shift(page)).toHaveAttribute('data-shift-count', '3'); await expect(page.locator('.accounting-shift-totals')).toContainText('2 970,00')
  await page.getByRole('button', { name: 'Продажи', exact: true }).click(); await expect(page.getByRole('button', { name: 'Частично', exact: true })).toHaveAttribute('aria-pressed', 'true'); await page.getByRole('button', { name: 'Все', exact: true }).click(); await expect(page.locator('[data-sale-date="2026-11-16"]')).toContainText('Сегодня'); await expect(page.locator('.accounting-sales button[data-sale-id]')).toHaveCount(3); expect(api.commands).toEqual([])
})
