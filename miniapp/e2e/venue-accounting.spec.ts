import { expect, test, type Page } from '@playwright/test'
import httpContract from './fixtures/accounting-http.json' with { type: 'json' }
import type { AccountingCommand, AccountingReport, AccountingSale } from '../src/shared/api/accounting'

test.use({ viewport: { width: 390, height: 844 } })
const sale = (): AccountingSale => ({
  id: 'manual:one', tableId: 11, tableLabel: 'Стол 1', source: 'MANUAL', orderId: null, displayNumber: null,
  orderClosed: true, createdAt: '2026-11-07T20:55:00Z', currency: 'RUB', version: 1, cancelReason: null, disputeReason: null,
  originalPayableMinor: null, issue: null, payments: [],
  lines: [{ id: 'line-one', name: 'Чай', qty: 1, unitPriceMinor: 10000, menuItemId: null, source: 'MANUAL', tabId: null,
    baseDiscountMinor: 0, baseGiftMinor: 0, discountMinor: 0, giftReason: null }]
})
function report(venueId = 1, sales: AccountingSale[] = []): AccountingReport {
  const gross = sales.reduce((sum, s) => sum + s.lines.reduce((v, l) => v + l.qty * l.unitPriceMinor, 0), 0)
  const paid = sales.flatMap(s => s.payments).reduce((sum, p) => sum + (p.voidReason ? 0 : p.amountMinor), 0)
  return { shiftId: venueId, venueId, status: 'OPEN', timezone: 'Europe/Moscow', openedAt: '2026-11-07T20:55:00Z', closedAt: null, currency: 'RUB',
    sales, totals: { salesCount: sales.length, ordersCount: sales.filter(s => s.orderId).length, grossMinor: gross, discountMinor: 0, giftMinor: 0,
      accruedMinor: gross, paidMinor: paid, unpaidMinor: gross - paid, cashMinor: 0, cardMinor: 0, transferMinor: 0 }, byTable: [], byItem: [], bySource: [],
    unpaidSaleIds: sales.filter(s => !s.payments.length).map(s => s.id), disputedSaleIds: [], reportHash: `hash-${venueId}-${paid}-${sales.length}` }
}
async function fixture(page: Page, role = 'STAFF', initialSales: AccountingSale[] = [], useHttpContract = false) {
  // This capture is checked against real Ktor HTTP responses by AccountingHttpContractTest.
  const reports = new Map([[1, useHttpContract ? httpContract.created as AccountingReport : report(1, initialSales)], [2, report(2)]])
  const commands: AccountingCommand[] = []
  const accepted = new Set<string>()
  let loseResponse = false
  let deny = false
  await page.addInitScript(() => {
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 103 } }, ready() {}, expand() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:103`, JSON.stringify({ token: 'synthetic-session', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 }))
  })
  await page.route('**/miniapp/accounting-test*', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"></head><body><div id="app"></div><script type="module">import {mountVenueApp} from '/miniapp/src/screens/venueApp.ts'; mountVenueApp({root:document.querySelector('#app'),backendUrl:location.origin});</script></body></html>` }))
  await page.route('**/api/**', async route => {
    const req = route.request(); const url = new URL(req.url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    if (url.pathname === '/api/venue/me') return route.fulfill({ json: { userId: 103, venues: [1, 2].map(id => ({ venueId: id, venueName: `Заведение ${id}`, venueStatus: 'PUBLISHED', role, permissions: ['ORDER_QUEUE_VIEW', 'MENU_VIEW', 'TABLE_VIEW'] })) } })
    if (url.pathname === '/api/venue/tables') return route.fulfill({ json: { tables: [{ tableId: Number(url.searchParams.get('venueId')) * 10 + 1, tableNumber: 1, tableLabel: 'Стол 1', isActive: true }] } })
    if (url.pathname === '/api/venue/menu') return route.fulfill({ json: { venueId: 1, categories: [{ id: 1, name: 'Меню', items: [{ id: 21, name: 'Чай из меню', priceMinor: 10000, currency: 'RUB', isAvailable: true, options: [] }] }] } })
    const match = /^\/api\/venue\/(\d+)\/accounting(?:\/(commands|\d+))?$/.exec(url.pathname)
    if (!match) return route.fulfill({ json: { unreadCount: 0, calls: [], items: [], linked: false } })
    const venue = Number(match[1]); let current = reports.get(venue)!
    if (deny) return route.fulfill({ status: 403, json: { error: { code: 'FORBIDDEN', message: 'Forbidden' } } })
    if (req.method() !== 'POST') return route.fulfill({ json: match[2] ? current
      : useHttpContract ? { ...httpContract.current, current } : { current, history: [] } })
    const body = req.postDataJSON() as AccountingCommand
    expect(req.headers().authorization).toBe('Bearer synthetic-session')
    commands.push(body)
    if (useHttpContract) {
      const step = commands.length - 1
      expect(step).toBeLessThan(2)
      expect(body).toMatchObject({ action: 'PAY', shiftId: 1, saleId: 'manual:http-contract',
        expectedVersion: step + 1, amountMinor: step === 0 ? 4000 : 6000, paymentMethod: step === 0 ? 'CASH' : 'CARD' })
      current = (step === 0 ? httpContract.paid : httpContract.settled) as AccountingReport
      reports.set(venue, current)
      return route.fulfill({ json: current })
    }
    if (!accepted.has(body.requestId)) {
      accepted.add(body.requestId)
      if (body.action === 'CREATE') {
        const added = sale()
        added.lines = body.lines!.map((line, index) => ({ ...added.lines[0], ...line, id: `new-${index}`, menuItemId: line.menuItemId ?? null,
          name: line.menuItemId ? 'Чай из меню' : line.name!, unitPriceMinor: line.unitPriceMinor!, discountMinor: line.discountMinor ?? 0, giftReason: line.giftReason ?? null }))
        current = report(venue, [...current.sales, added]); reports.set(venue, current)
      }
      if (body.action === 'PAY') {
        const target = current.sales.find(s => s.id === body.saleId)!
        target.version++
        target.payments.push({ id: body.requestId, amountMinor: body.amountMinor!, method: body.paymentMethod!, recordedAt: '2026-11-08T00:01:00Z', voidReason: null })
        current = report(venue, current.sales); reports.set(venue, current)
      }
      if (body.action === 'CLOSE') { current.status = 'CLOSED'; current.closedAt = '2026-11-08T00:15:00Z' }
    }
    if (loseResponse) { loseResponse = false; return route.abort('failed') }
    return route.fulfill({ json: current })
  })
  await page.goto('accounting-test?venueId=1#/accounting?av=1')
  await expect(page.getByRole('heading', { name: 'Учёт смены' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  return { commands, reports, loseNextResponse() { loseResponse = true }, deny() { deny = true } }
}

test('mobile staff preserves draft through Telegram Back reload and venue switch then retries exact lost response', async ({ page }) => {
  const api = await fixture(page)
  await expect(page.getByRole('button', { name: 'Предварительный итог и закрытие' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Название', { exact: true }).fill('Сок вне Telegram')
  await page.getByLabel('Цена за единицу, ₽').fill('125,50')
  await page.evaluate(() => window.dispatchEvent(new Event('hookah:e2e-telegram-back')))
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Сок вне Telegram')
  await page.reload()
  await expect(page.getByLabel('Цена за единицу, ₽')).toHaveValue('125,50')
  await page.getByLabel('Выбрать заведение').selectOption('2')
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('')
  await page.getByLabel('Выбрать заведение').selectOption('1')
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Сок вне Telegram')
  api.loseNextResponse()
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Проверить и повторить операцию' })).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands).toHaveLength(2)
  expect(api.commands[0]).toEqual(api.commands[1])
  expect(api.commands[0].lines?.[0].unitPriceMinor).toBe(12550)
  expect(api.reports.get(1)?.sales).toHaveLength(1)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '/private/tmp/HT-VENUE-SHIFT-SALES-MVP-01-mobile.png', fullPage: true })
})

test('gift requires reason and menu amount is sent in integer minor units', async ({ page }) => {
  const api = await fixture(page)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Из меню или вручную').selectOption('21')
  await page.getByLabel('Подарок / бесплатно').check()
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Укажите причину подарка')
  expect(api.commands).toHaveLength(0)
  await page.getByLabel('Причина подарка').fill('Комплимент гостю')
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands[0].lines?.[0]).toMatchObject({ menuItemId: 21, unitPriceMinor: 10000, giftReason: 'Комплимент гостю' })
})

test('manager records mixed payments and confirms frozen report hash before close', async ({ page }) => {
  const api = await fixture(page, 'MANAGER', [sale()])
  await page.getByRole('button', { name: /Стол 1 · Вручную/ }).click()
  await page.getByLabel('Получено, ₽').fill('40.00')
  await page.getByRole('button', { name: 'Подтвердить внешнюю оплату' }).click()
  await expect(page.getByLabel('Получено, ₽')).toHaveValue('60.00')
  await page.getByLabel('Способ оплаты').selectOption('TRANSFER')
  await page.getByRole('button', { name: 'Подтвердить внешнюю оплату' }).click()
  await expect(page.getByLabel('Получено, ₽')).toHaveValue('0.00')
  expect(api.commands.map(c => [c.action, c.paymentMethod, c.amountMinor])).toEqual([['PAY', 'CASH', 4000], ['PAY', 'TRANSFER', 6000]])
  await page.evaluate(() => window.dispatchEvent(new Event('hookah:e2e-telegram-back')))
  await page.getByRole('button', { name: 'Предварительный итог и закрытие' }).click()
  await expect(page.getByText(/дальнейшее исправление и запись оплат/)).toBeVisible()
  await page.getByLabel('Проверил неоплаченные', { exact: false }).check()
  await page.getByRole('button', { name: 'Подтвердить закрытие смены' }).click()
  await expect(page.getByText(/Сохранённый итог/)).toBeVisible()
  expect(api.commands.at(-1)).toMatchObject({ action: 'CLOSE', expectedReportHash: 'hash-1-10000-1', acknowledgeUnpaid: true })
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toHaveCount(0)
})

test('unfinished digital order explains prerequisite and cannot record an assumed payment', async ({ page }) => {
  await fixture(page, 'STAFF', [{ ...sale(), id: 'order:31', orderId: 31, displayNumber: 7, source: 'MINIAPP', orderClosed: false, originalPayableMinor: 10000 }])
  await page.getByRole('button', { name: /Стол 1 · Mini App/ }).click()
  await expect(page.getByText(/Завершение заказа само по себе не подтверждает/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Подтвердить внешнюю оплату' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Открыть заказ №7' })).toHaveAttribute('href', '#/order/31')
})

test('revoked access reports denial and does not claim successful payment', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [sale()])
  await page.getByRole('button', { name: /Стол 1 · Вручную/ }).click()
  await expect(page.getByRole('button', { name: 'Подтвердить внешнюю оплату' })).toBeVisible()
  api.deny()
  await page.getByRole('button', { name: 'Подтвердить внешнюю оплату' }).click()
  await expect(page.getByRole('alert')).toContainText('Доступ отозван')
  expect(api.commands).toHaveLength(0)
})

test('stale persisted editor cannot silently overwrite another operator correction', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [sale()])
  await page.getByRole('button', { name: /Стол 1 · Вручную/ }).click()
  await page.getByRole('button', { name: 'Исправить продажу', exact: true }).click()
  await page.getByLabel('Название', { exact: true }).fill('Старый черновик')
  api.reports.get(1)!.sales[0].version = 2
  await page.reload()
  await expect(page.getByText(/Запись изменилась после начала исправления/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Сохранить исправление' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Загрузить актуальную запись вместо черновика' }).click()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Чай')
  expect(api.commands).toHaveLength(0)
})

test('real serialized HTTP contract displays 100 rubles and records partial and final receipts', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const api = await fixture(page, 'STAFF', [], true)
  const saleButton = page.getByRole('button', { name: /Стол 1 · Вручную/ })
  await expect(saleButton).toContainText('100,00')
  await saleButton.click()
  await expect(page.getByLabel('Получено, ₽')).toHaveValue('100.00')
  await page.getByLabel('Получено, ₽').fill('40.00')
  await page.getByRole('button', { name: 'Подтвердить внешнюю оплату' }).click()
  await expect(page.getByLabel('Получено, ₽')).toHaveValue('60.00')
  await page.getByLabel('Способ оплаты').selectOption('CARD')
  await page.getByRole('button', { name: 'Подтвердить внешнюю оплату' }).click()
  await expect(page.getByLabel('Получено, ₽')).toHaveValue('0.00')
  await page.reload()
  await expect(page.getByLabel('Получено, ₽')).toHaveValue('0.00')
  expect(api.commands).toHaveLength(2)
  expect(api.reports.get(1)?.totals).toMatchObject({ accruedMinor: 10000, paidMinor: 10000,
    unpaidMinor: 0, cashMinor: 4000, cardMinor: 6000, discountMinor: 0, giftMinor: 0 })
  expect(errors).toEqual([])
})
