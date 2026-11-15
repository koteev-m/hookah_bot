import { test, expect, type Page } from '@playwright/test'
import { statsResponse } from './fixtures/venue-stats'
import { salePaid, salePayable, type AccountingCommand, type AccountingReport, type AccountingSale } from '../src/shared/api/accounting'

const now = '2026-11-15T12:30:00Z'
const splitId = 'split-operation-00000001'
const sale = (id: string, createdAt: string): AccountingSale => ({ id, createdAt, tableId: 11, tableLabel: 'Стол 1', source: 'MANUAL', orderId: null, displayNumber: null, orderClosed: true, currency: 'RUB', version: 1, cancelReason: null, disputeReason: null, originalPayableMinor: null, issue: null,
  lines: [{ id: `line-${id}`, name: 'Чай', qty: 1, unitPriceMinor: 99000, menuItemId: null, source: 'MANUAL', tabId: null, baseDiscountMinor: 0, baseGiftMinor: 0, discountMinor: 0, giftReason: null }], payments: [] })
function salesFixture() {
  const old = sale('manual:z-old', '2026-11-13T08:00:00Z'), middle = sale('manual:c-middle', '2026-11-14T08:00:00Z')
  middle.payments = [{ id: 'ordinary-payment-00000001', method: 'CASH', amountMinor: 99000, recordedAt: '2026-11-14T09:00:00Z', voidReason: null },
    ...['CASH', 'CARD'].map(method => ({ id: `legacy-${method}`, method, amountMinor: 10000, recordedAt: '2026-11-14T08:30:00Z', voidReason: 'Исправлена старая запись' }))]
  const partial = sale('manual:b-new', '2026-11-15T12:00:00Z'); partial.payments = [{ id: 'ordinary-payment-00000002', method: 'CASH', amountMinor: 40000, recordedAt: '2026-11-15T12:10:00Z', voidReason: null }]
  const combined = sale('manual:a-tie', '2026-11-15T12:00:00Z'); combined.payments = [{ id: `${splitId}:CASH`, method: 'CASH', amountMinor: 40000, recordedAt: '2026-11-15T12:05:00Z', voidReason: null }, { id: `${splitId}:CARD`, method: 'CARD', amountMinor: 59000, recordedAt: '2026-11-15T12:05:00Z', voidReason: null }]
  const canceled = sale('manual:cancel', '2026-11-15T11:00:00Z'); canceled.cancelReason = 'Повторная запись'
  const unknown = sale('order:unknown', '2026-11-14T13:00:00Z'); Object.assign(unknown, { source: 'CHAT', orderId: 17, displayNumber: 17, issue: 'MISSING_SNAPSHOT', disputeReason: 'Требуется сверка', lines: [] })
  return [old, middle, partial, combined, canceled, unknown]
}
function report(sales: AccountingSale[], timezone = 'Europe/Moscow'): AccountingReport {
  const active = sales.filter(s => s.cancelReason === null), paid = sales.flatMap(s => s.payments).filter(p => !p.voidReason)
  const accrued = active.reduce((n, s) => n + salePayable(s), 0), received = paid.reduce((n, p) => n + p.amountMinor, 0)
  return { shiftId: 1, venueId: 1, status: 'OPEN', timezone, openedAt: '2026-11-13T01:00:00Z', closedAt: null, currency: 'RUB', sales,
    totals: { salesCount: active.length, ordersCount: 1, grossMinor: accrued, discountMinor: 0, giftMinor: 0, accruedMinor: accrued, paidMinor: received, unpaidMinor: accrued - received, cashMinor: paid.filter(p => p.method === 'CASH').reduce((n, p) => n + p.amountMinor, 0), cardMinor: paid.filter(p => p.method === 'CARD').reduce((n, p) => n + p.amountMinor, 0), transferMinor: 0 },
    byTable: [], byItem: [], bySource: [], unpaidSaleIds: active.filter(s => salePaid(s) < salePayable(s)).map(s => s.id), disputedSaleIds: active.filter(s => s.issue || s.disputeReason).map(s => s.id), reportHash: 'unchanged-authoritative-report-hash' }
}
async function setup(page: Page, options: { sales?: AccountingSale[]; timezone?: string; role?: string; now?: string } = {}) {
  await page.clock.setFixedTime(new Date(options.now ?? now))
  let current = report(options.sales ?? salesFixture(), options.timezone)
  const commands: AccountingCommand[] = []
  await page.addInitScript(() => {
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 101 } }, ready() {}, expand() {}, onEvent() {}, offEvent() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:101`, JSON.stringify({ token: 'local-shift-clarity-fixture', expiresAtEpochSeconds: 4102444800 }))
  })
  await page.route('**/miniapp/shift-clarity-test*', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"></head><body><div id="app"></div><script type="module">
    import {renderVenueAccountingScreen} from '/miniapp/src/screens/venueAccounting.ts';import {renderVenueStatsScreen} from '/miniapp/src/screens/venueStats.ts';import {bindAppTheme} from '/miniapp/src/shared/ui/appTheme.ts';bindAppTheme(document.documentElement);
    const access={venueId:1,venueName:'Тестовая площадка',venueStatus:'PUBLISHED',role:${JSON.stringify(options.role ?? 'OWNER')},permissions:['ORDER_QUEUE_VIEW','MENU_VIEW','TABLE_VIEW']};let dispose;const mount=()=>{dispose?.();dispose=renderVenueAccountingScreen({root:document.querySelector('#app'),backendUrl:location.origin,isDebug:false,venueId:1,access,userId:101})};window.addEventListener('hashchange',mount);mount();window.showStats=()=>{dispose?.();dispose=renderVenueStatsScreen({root:document.querySelector('#app'),backendUrl:location.origin,isDebug:false,venueId:1,access})};</script></body></html>` }))
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    if (url.pathname === '/api/venue/tables') return route.fulfill({ json: { tables: [{ tableId: 11, tableLabel: 'Стол 1', tableNumber: 1, isActive: true }] } })
    if (url.pathname === '/api/venue/menu') return route.fulfill({ json: { venueId: 1, categories: [] } })
    if (url.pathname.includes('/stats/')) {
      const body = statsResponse(url, { timezone: current.timezone || 'Europe/Moscow', asOf: options.now ?? now })
      body.closedShiftFinances = []; body.openShiftOpenedAt = current.openedAt
      if (body.metrics['finance.closedReports']) body.metrics['finance.closedReports'] = { ...body.metrics['finance.closedReports'], value: '0', availability: 'AVAILABLE' }
      if (body.metrics['finance.openShifts']) body.metrics['finance.openShifts'] = { ...body.metrics['finance.openShifts'], value: '1', availability: 'AVAILABLE' }
      return route.fulfill({ json: body })
    }
    if (req.method() === 'POST') {
      const body = req.postDataJSON() as AccountingCommand; commands.push(body)
      if (body.action === 'VOID_PAYMENT') {
        const target = current.sales.find(s => s.id === body.saleId)!; target.payments.find(p => p.id === body.paymentId)!.voidReason = body.reason!; target.version++
        current = report(current.sales, current.timezone)
      }
      return route.fulfill({ json: current })
    }
    if (url.pathname.includes('/accounting')) return route.fulfill({ json: /\/accounting\/\d+$/.test(url.pathname) ? current : { current, history: [] } })
    return route.fulfill({ json: {} })
  })
  await page.goto('shift-clarity-test#/accounting?av=1')
  await expect(page.locator('.accounting-sales > button')).toHaveCount(current.sales.length)
  return { commands, state: () => current }
}
const rows = (page: Page) => page.locator('.accounting-sales > button')
const shot = (page: Page, info: { outputPath: (s: string) => string }, name: string) => page.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true })

for (const width of [360, 390]) {
  test(`shift clarity dates counters and compact rows ${width}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 }); await page.emulateMedia({ colorScheme: 'dark' }); const api = await setup(page)
    await shot(page, info, 'shift-sales')
    expect(await rows(page).evaluateAll(nodes => nodes.map(n => n.getAttribute('data-sale-id')))).toEqual(['manual:a-tie', 'manual:b-new', 'manual:cancel', 'order:unknown', 'manual:c-middle', 'manual:z-old'])
    expect(await page.locator('[data-sale-date]').evaluateAll(nodes => nodes.map(n => n.getAttribute('data-sale-date')))).toEqual(['2026-11-15', '2026-11-14', '2026-11-13'])
    await expect(page.locator('[data-shift-count]')).toContainText('5'); await expect(page.locator('[data-today-count]')).toContainText('2')
    await expect(page.locator('.accounting-shift-summary')).toContainText('13.11.2026'); await expect(page.locator('.accounting-old-shift')).toContainText('Новые продажи')
    const partial = page.locator('[data-sale-id="manual:b-new"]'); await expect(partial).toContainText('Осталось 590,00'); await expect(partial).not.toContainText('Оплачено 400')
    expect(await rows(page).evaluateAll(nodes => nodes.every(n => !n.textContent?.includes('manual:') && !n.textContent?.includes('order:')))).toBe(true)
    expect(api.state().sales.map(s => s.id)).toEqual(salesFixture().map(s => s.id))
    await page.getByRole('button', { name: 'Оплачены', exact: true }).click(); await expect(page.locator('.accounting-sales [data-sale-id="order:unknown"]')).toBeVisible(); await expect(page.locator('[data-shift-count]')).toContainText('5')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.locator('.accounting-sales > button').last().focus(); await page.keyboard.press('Enter'); await expect(page.getByRole('button', { name: 'К отчёту', exact: true })).toBeVisible()
    await page.getByText('Технические сведения', { exact: true }).click(); await expect(page.locator('.accounting-sale-technical')).toContainText('manual:c-middle')
  })

  test(`shift clarity payment operations preserve components corrections and sale scope ${width}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 }); await page.emulateMedia({ colorScheme: 'dark' }); const api = await setup(page)
    await page.goto('shift-clarity-test#/accounting?av=1&sale=manual%3Aa-tie')
    await page.locator('.accounting-payment-history > summary').click(); await shot(page, info, 'sale-payments')
    await expect(page.locator('.accounting-payment-history > summary')).toContainText('Оплаты по этой продаже')
    const split = page.locator('.accounting-payment-history [data-payment-operation]'); await expect(split).toHaveCount(1); await expect(split.locator('[data-payment-component]')).toHaveCount(2)
    await split.getByRole('button', { name: 'Исправить запись оплаты', exact: true }).first().click(); await page.getByLabel('Причина', { exact: true }).fill('Неверно указан способ'); await page.getByRole('button', { name: 'Подтвердить', exact: true }).click()
    expect(api.commands).toHaveLength(1); expect(api.commands[0]).toMatchObject({ action: 'VOID_PAYMENT', saleId: 'manual:a-tie', paymentId: `${splitId}:CASH`, expectedVersion: 1 })
    await page.locator('.accounting-payment-history > summary').click(); await expect(page.locator('.accounting-payment-history')).toContainText('Исправлено: Неверно указан способ'); await expect(page.locator('.accounting-sale-balances')).toContainText('400,00')
    await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await page.getByRole('button', { name: 'Оплаты смены', exact: true }).click()
    const operations = page.locator('.accounting-shift-payments [data-payment-operation]'); await expect(operations).toHaveCount(5)
    await expect(page.locator(`[data-payment-request-id="${splitId}"]`)).toHaveCount(1); await expect(page.locator('.accounting-shift-payments')).toContainText('Исправлена старая запись'); await shot(page, info, 'shift-payments')
    await page.locator(`[data-payment-request-id="${splitId}"]`).getByRole('button', { name: /Открыть продажу/ }).click(); await expect(page).toHaveURL(/sale=manual%3Aa-tie/)
    await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await expect(page.locator('.accounting-shift-payments')).toBeVisible()
    expect(api.commands).toHaveLength(1); expect(api.state().sales.find(s => s.id === 'manual:b-new')!.payments).toHaveLength(1)
  })

  test(`shift clarity close preview and keyboard access ${width}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 }); const api = await setup(page, { role: 'MANAGER' })
    const close = page.getByRole('button', { name: 'Предварительный итог и закрытие', exact: true })
    expect(await close.evaluate(n => n.getBoundingClientRect().top)).toBeLessThan(await rows(page).first().evaluate(n => n.getBoundingClientRect().top))
    await close.focus(); await page.keyboard.press('Enter'); await expect(page.getByRole('heading', { name: 'Подтверждение закрытия смены' })).toBeFocused()
    await expect(page.getByLabel('Проверил неоплаченные и спорные записи;', { exact: false })).not.toBeChecked(); expect(api.commands).toHaveLength(0)
    await shot(page, info, 'close-preview'); await page.getByRole('button', { name: 'Продолжить смену', exact: true }).click(); await expect(close).toBeFocused(); expect(api.state().status).toBe('OPEN')
    await page.keyboard.press('Tab'); expect(await page.evaluate(() => document.activeElement instanceof HTMLButtonElement)).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })

  test(`shift clarity closed empty report explains current shift ${width}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 }); await setup(page); await page.evaluate(() => (window as any).showStats())
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByLabel('Финансовый период').selectOption('closed'); await expect(page.locator('#stats-finance')).toBeVisible(); await shot(page, info, 'closed-empty')
    await expect(page.locator('#stats-finance')).toContainText('Нет смен, закрытых в выбранный период')
    await expect(page.locator('#stats-finance')).toContainText('Текущая смена открыта')
    await page.getByRole('button', { name: 'Показать текущую смену', exact: true }).click(); await expect(page.getByLabel('Финансовый период')).toHaveValue('current'); await expect(page.locator('#stats-finance')).toContainText(/13\.11\.(?:20)?26/)
  })

  test(`shift clarity venue timezone DST and unknown dates ${width}`, async ({ browser }, info) => {
    const context = await browser.newContext({ viewport: { width, height: 844 }, timezoneId: 'America/Los_Angeles' }); const page = await context.newPage()
    try {
      const api = await setup(page, { timezone: 'Europe/Berlin', now: '2026-10-25T01:30:00Z', sales: [sale('manual:earlier', '2026-10-25T00:45:00Z'), sale('manual:later', '2026-10-25T01:15:00Z'), sale('manual:yesterday', '2026-10-24T12:00:00Z'), sale('manual:undated', 'invalid')] })
      await expect(page.locator('[data-today-count]')).toContainText('2'); expect(await rows(page).evaluateAll(nodes => nodes.map(n => n.getAttribute('data-sale-id')))).toEqual(['manual:later', 'manual:earlier', 'manual:yesterday', 'manual:undated'])
      await expect(page.locator('[data-sale-date="unknown"]')).toContainText('Дата не определена'); expect(api.state().sales[0].id).toBe('manual:earlier'); await shot(page, info, 'venue-dst')
    } finally { await context.close() }
  })
}

test('shift clarity STAFF cannot close or read closed history', async ({ page }) => {
  await setup(page, { role: 'STAFF' }); await expect(page.getByRole('button', { name: 'Предварительный итог и закрытие' })).toHaveCount(0)
  await expect(page.getByText('История закрытых смен', { exact: true })).toHaveCount(0); await page.getByRole('button', { name: 'Оплаты смены', exact: true }).click(); await expect(page.locator('.accounting-shift-payments [data-payment-operation]')).toHaveCount(5)
})

test('shift clarity presentation never mutates reports or guesses legacy payment groups', async ({ page }) => {
  await setup(page)
  const result = await page.evaluate(async () => {
    const { paymentOperations, orderedSales, venueDay } = await import('/miniapp/src/screens/accountingPresentation.ts')
    const p = (id: string, method: string) => ({ id, method, amountMinor: 100, recordedAt: '2026-11-15T12:00:00Z', voidReason: null })
    const prefix = 'same-request-00000001'
    const a = { id: 'a', createdAt: '2026-11-15T00:00:00Z', payments: [p(`${prefix}:CASH`, 'CASH'), p(`${prefix}:CARD`, 'CARD')] }
    const b = { id: 'b', createdAt: '2026-11-14T00:00:00Z', payments: [p(`${prefix}:CASH`, 'CASH'), p(`${prefix}:CARD`, 'CARD')] }
    const input = [b, a]; const before = JSON.stringify(input); orderedSales(input as any)
    const groups = input.flatMap(s => paymentOperations(s as any)); const legacy = { id: 'legacy', payments: [p('legacy-CASH', 'CASH'), p('legacy-CARD', 'CARD'), p('other-request-00000001:CASH', 'CARD')] }
    return { originalUnchanged: before === JSON.stringify(input), groups: groups.length, components: groups.map(g => g.payments.length), legacy: paymentOperations(legacy as any).length, invalidZone: venueDay('2026-11-15T00:00:00Z', ''), invalidDate: venueDay('bad', 'Europe/Moscow'), naiveDate: venueDay('2026-11-15T00:00:00', 'Europe/Moscow'),
      preciseOrder: orderedSales([{ ...a, id: 'earlier', createdAt: '2026-11-15T00:00:00.000000001Z' }, { ...b, id: 'later', createdAt: '2026-11-15T03:00:00.000000002+03:00' }] as any).map(s => s.id),
      incomplete: paymentOperations({ payments: [p(`${prefix}:CASH`, 'CASH')] } as any).map(g => g.requestId),
      inconsistent: paymentOperations({ payments: [p(`${prefix}:CASH`, 'CASH'), { ...p(`${prefix}:CARD`, 'CARD'), recordedAt: '2026-11-15T12:00:01Z' }] } as any).map(g => g.requestId) }
  })
  expect(result).toEqual({ originalUnchanged: true, groups: 2, components: [2, 2], legacy: 3, invalidZone: null, invalidDate: null, naiveDate: null, preciseOrder: ['later', 'earlier'], incomplete: [null], inconsistent: [null, null] })
})
