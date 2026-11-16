import { openAccountingSale } from './accounting-navigation'
import { expect, test, type Page } from '@playwright/test'
import httpContract from './fixtures/accounting-http.json' with { type: 'json' }
import draftCommands from './fixtures/accounting-first-draft-commands.json' with { type: 'json' }
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
async function fixture(page: Page, role = 'STAFF', initialSales: AccountingSale[] = [], useHttpContract = false, noShift = false) {
  // This capture is checked against real Ktor HTTP responses by AccountingHttpContractTest.
  const reports = new Map([[1, useHttpContract ? httpContract.created as AccountingReport : report(1, initialSales)], [2, report(2)]])
  const commands: AccountingCommand[] = []
  const accepted = new Set<string>()
  const unopened = new Set(noShift ? [1, 2] : [])
  let actor = 103
  let loseResponse = false
  let timeoutResponse = false
  let conflict = false
  let failBeforeCommit = false
  let responseBarrier: Promise<void> | undefined
  let deny = false
  let identifyCreate = false
  const createdResults = new Map<string, string>()
  await page.addInitScript(() => {
    const user = Number(sessionStorage.getItem('accounting-test-actor') ?? 103)
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: user } }, ready() {}, expand() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:${user}`, JSON.stringify({ token: 'synthetic-session', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 }))
  })
  await page.route('**/miniapp/accounting-test*', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"></head><body><div id="app"></div><script type="module">import {mountVenueApp} from '/miniapp/src/screens/venueApp.ts'; mountVenueApp({root:document.querySelector('#app'),backendUrl:location.origin});</script></body></html>` }))
  await page.route('**/api/**', async route => {
    const req = route.request(); const url = new URL(req.url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    if (url.pathname === '/api/venue/me') return route.fulfill({ json: { userId: actor, venues: [1, 2].map(id => ({ venueId: id, venueName: `Заведение ${id}`, venueStatus: 'PUBLISHED', role, permissions: ['ORDER_QUEUE_VIEW', 'MENU_VIEW', 'TABLE_VIEW'] })) } })
    if (url.pathname === '/api/venue/tables') return route.fulfill({ json: { tables: [{ tableId: Number(url.searchParams.get('venueId')) * 10 + 1, tableNumber: 1, tableLabel: 'Стол 1', isActive: true }] } })
    if (url.pathname === '/api/venue/menu') return route.fulfill({ json: { venueId: 1, categories: [{ id: 1, name: 'Меню', items: [{ id: 21, name: 'Чай из меню', priceMinor: 10000, currency: 'RUB', isAvailable: true, options: [] }] }] } })
    const match = /^\/api\/venue\/(\d+)\/accounting(?:\/(commands|\d+))?$/.exec(url.pathname)
    if (!match) return route.fulfill({ json: { unreadCount: 0, calls: [], items: [], linked: false } })
    const venue = Number(match[1]); let current = reports.get(venue)!
    if (deny) return route.fulfill({ status: 403, json: { error: { code: 'FORBIDDEN', message: 'Forbidden' } } })
    if (req.method() !== 'POST') return route.fulfill({ json: match[2] ? current
      : useHttpContract ? { ...httpContract.current, current } : { current: unopened.has(venue) || current.status === 'CLOSED' ? null : current, history: current.status === 'CLOSED' ? [{ id: current.shiftId, status: current.status, timezone: current.timezone, openedAt: current.openedAt, closedAt: current.closedAt }] : [] } })
    const body = req.postDataJSON() as AccountingCommand
    expect(req.headers().authorization).toBe('Bearer synthetic-session')
    commands.push(body)
    if (conflict) { conflict = false; return route.fulfill({ status: 409, json: { error: { code: 'ACCOUNTING_CONFLICT', message: 'Данные изменились. Обновите отчёт.' } } }) }
    if (failBeforeCommit) { failBeforeCommit = false; return route.fulfill({ status: 503, json: { error: { code: 'UNAVAILABLE', message: 'Try again' } } }) }
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
        if (unopened.has(venue) || current.status === 'CLOSED') {
          expect(body.shiftId).toBeUndefined()
          unopened.delete(venue)
          const nextId = current.status === 'CLOSED' ? current.shiftId + 10 : current.shiftId
          current = { ...report(venue), shiftId: nextId }
        }
        const added = sale()
        added.id = `manual:${body.requestId}`
        createdResults.set(body.requestId, added.id)
        added.lines = body.lines!.map((line, index) => ({ ...added.lines[0], ...line, id: `new-${index}`, menuItemId: line.menuItemId ?? null,
          name: line.menuItemId ? 'Чай из меню' : line.name!, unitPriceMinor: line.unitPriceMinor!, discountMinor: line.discountMinor ?? 0, giftReason: line.giftReason ?? null }))
        current = { ...report(venue, [...current.sales, added]), shiftId: current.shiftId }; reports.set(venue, current)
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
    if (timeoutResponse) { timeoutResponse = false; return route.fulfill({ status: 408, json: { error: { code: 'REQUEST_TIMEOUT', message: 'Request timed out' } } }) }
    if (responseBarrier) { const barrier = responseBarrier; responseBarrier = undefined; await barrier }
    return route.fulfill({ json: body.action === 'CREATE' && identifyCreate ? { ...current, createdSaleId: createdResults.get(body.requestId) } : current })
  })
  await page.goto('accounting-test?venueId=1#/accounting?av=1')
  await expect(page.getByRole('heading', { name: 'Учёт смены' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  return { commands, reports, identifyCreate() { identifyCreate = true }, loseNextResponse() { loseResponse = true }, deny() { deny = true },
    timeoutNextResponse() { timeoutResponse = true }, conflictNextSave() { conflict = true },
    failNextBeforeCommit() { failBeforeCommit = true },
    holdNextResponse() { let release!: () => void; responseBarrier = new Promise<void>(resolve => { release = resolve }); return () => release() },
    guestOpensShift(venue = 1) { unopened.delete(venue) },
    async switchAccount(user: number) { actor = user; await page.evaluate(id => sessionStorage.setItem('accounting-test-actor', String(id)), user); await page.reload() } }
}

test('mobile staff preserves draft through Telegram Back reload and venue switch then retries exact lost response', async ({ page }, testInfo) => {
  const api = await fixture(page)
  await expect(page.getByRole('button', { name: 'Сверить и закрыть смену' })).toHaveCount(0)
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
  await page.screenshot({ path: testInfo.outputPath('mobile-accounting.png'), fullPage: true })
})

test('gift requires reason and menu amount is sent in integer minor units', async ({ page }) => {
  const api = await fixture(page)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Категория меню', { exact: true }).selectOption('1')
  await page.getByLabel('Товар', { exact: true }).selectOption('21')
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
  await openAccountingSale(page.locator('.accounting-sales [data-sale-id]').filter({ hasText: /Стол 1 · Вручную/ }))
  await page.getByLabel('Частичная оплата', { exact: true }).check()
  await page.getByLabel('Получено, ₽').fill('40.00')
  await page.getByLabel('Подтверждаю частичную оплату:', { exact: false }).check()
  await page.getByRole('button', { name: /^Записать .* (наличными|картой|переводом)$/ }).click()
  await expect(page.getByLabel('Получено, ₽')).toHaveValue('60.00')
  await page.getByLabel('Способ оплаты').selectOption('TRANSFER')
  await page.getByRole('button', { name: /^Записать .* (наличными|картой|переводом)$/ }).click()
  await expect(page.getByText('Оплачено полностью', { exact: true })).toBeVisible()
  await expect(page.locator('.accounting-payment-form')).toHaveCount(0)
  expect(api.commands.map(c => [c.action, c.paymentMethod, c.amountMinor])).toEqual([['PAY', 'CASH', 4000], ['PAY', 'TRANSFER', 6000]])
  await page.evaluate(() => window.dispatchEvent(new Event('hookah:e2e-telegram-back')))
  await page.getByRole('button', { name: 'Сверить и закрыть смену' }).click()
  await expect(page.getByText(/дальнейшее исправление и запись оплат/)).toBeVisible()
  await page.getByLabel('Проверил неоплаченные', { exact: false }).check()
  await page.getByRole('button', { name: 'Подтвердить закрытие смены' }).click()
  await expect(page.getByText(/Сохранённый итог/)).toBeVisible()
  expect(api.commands.at(-1)).toMatchObject({ action: 'CLOSE', expectedReportHash: 'hash-1-10000-1', acknowledgeUnpaid: true })
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toHaveCount(0)
})

test('unfinished digital order explains prerequisite and cannot record an assumed payment', async ({ page }) => {
  await fixture(page, 'STAFF', [{ ...sale(), id: 'order:31', orderId: 31, displayNumber: 7, source: 'MINIAPP', orderClosed: false, originalPayableMinor: 10000 }])
  await openAccountingSale(page.locator('.accounting-sales [data-sale-id]').filter({ hasText: /Стол 1 · Mini App/ }))
  await expect(page.getByText(/Завершение заказа само по себе не подтверждает/)).toBeVisible()
  await expect(page.getByRole('button', { name: /^Записать .* (наличными|картой|переводом)$/ })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Открыть заказ №7' })).toHaveAttribute('href', '#/order/31')
})

test('revoked access reports denial and does not claim successful payment', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [sale()])
  await openAccountingSale(page.locator('.accounting-sales [data-sale-id]').filter({ hasText: /Стол 1 · Вручную/ }))
  await expect(page.getByRole('button', { name: /^Записать .* (наличными|картой|переводом)$/ })).toBeVisible()
  api.deny()
  await page.getByRole('button', { name: /^Записать .* (наличными|картой|переводом)$/ }).click()
  await expect(page.getByRole('alert')).toContainText('Доступ отозван')
  expect(api.commands).toHaveLength(0)
})

test('stale persisted editor cannot silently overwrite another operator correction', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [sale()])
  await openAccountingSale(page.locator('.accounting-sales [data-sale-id]').filter({ hasText: /Стол 1 · Вручную/ }))
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

test('real serialized HTTP contract displays 100 rubles and records partial and final receipts', async ({ page }, info) => {
  await page.emulateMedia({ colorScheme: 'dark' })
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const api = await fixture(page, 'STAFF', [], true)
  const saleButton = page.locator('.accounting-sales [data-sale-id]').filter({ hasText: /Стол 1 · Вручную/ })
  await expect(saleButton).toContainText('100,00')
  await page.screenshot({ path: info.outputPath('sales-list.png'), fullPage: true })
  await openAccountingSale(saleButton)
  await page.screenshot({ path: info.outputPath('payment.png'), fullPage: true })
  await expect(page.getByLabel('Получено, ₽')).toHaveValue('100.00')
  await page.getByLabel('Частичная оплата', { exact: true }).check()
  await page.getByLabel('Получено, ₽').fill('40.00')
  await page.getByLabel('Подтверждаю частичную оплату:', { exact: false }).check()
  await page.getByRole('button', { name: /^Записать .* (наличными|картой|переводом)$/ }).click()
  await expect(page.getByLabel('Получено, ₽')).toHaveValue('60.00')
  await page.getByLabel('Способ оплаты').selectOption('CARD')
  await page.getByRole('button', { name: /^Записать .* (наличными|картой|переводом)$/ }).click()
  await expect(page.getByText('Оплачено полностью', { exact: true })).toBeVisible()
  await expect(page.locator('.accounting-payment-form')).toHaveCount(0)
  await page.reload()
  await expect(page.getByText('Оплачено полностью', { exact: true })).toBeVisible()
  await expect(page.locator('.accounting-payment-form')).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('paid-sale.png'), fullPage: true })
  expect(api.commands).toHaveLength(2)
  expect(api.reports.get(1)?.totals).toMatchObject({ accruedMinor: 10000, paidMinor: 10000,
    unpaidMinor: 0, cashMinor: 4000, cardMinor: 6000, discountMinor: 0, giftMinor: 0 })
  expect(errors).toEqual([])
})

for (const role of ['STAFF', 'MANAGER', 'OWNER']) test(`first sale creates shift only on save for ${role}`, async ({ page }) => {
  const api = await fixture(page, role, [], false, true)
  await expect(page.getByRole('button', { name: 'Открыть учётную смену' })).toHaveCount(0)
  await expect(page.getByText('Учётная смена откроется автоматически', { exact: false })).toBeVisible()
  expect(api.commands).toHaveLength(0)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Название', { exact: true }).fill('Первая продажа')
  await page.getByLabel('Цена за единицу, ₽').fill('100')
  await page.reload()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Первая продажа')
  expect(api.commands).toHaveLength(0)
  api.loseNextResponse()
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands).toHaveLength(2)
  expect(api.commands[0]).toEqual(api.commands[1])
  expect(api.commands[0].action).toBe('CREATE')
  expect(api.commands[0].shiftId).toBeUndefined()
  expect(api.reports.get(1)!.sales).toHaveLength(1)
  if (role === 'STAFF') await expect(page.getByRole('button', { name: 'Сверить и закрыть смену' })).toHaveCount(0)
})

test('old open shift warns before entry and midnight does not send a close or open command', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-11-12T01:00:00Z'))
  const api = await fixture(page)
  await expect(page.getByText('Новые продажи продолжают попадать в неё', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await expect(page.getByText('полночь смену не закрывает.', { exact: false })).toBeVisible()
  expect(api.commands).toHaveLength(0)
})

test('confirmed close leads to first-sale entry without reopening the frozen shift', async ({ page }) => {
  const api = await fixture(page, 'MANAGER')
  await page.getByRole('button', { name: 'Сверить и закрыть смену' }).click()
  await page.getByRole('button', { name: 'Подтвердить закрытие смены', exact: true }).click()
  await page.getByRole('button', { name: 'К текущей смене' }).click()
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Название', { exact: true }).fill('Новый период')
  await page.getByLabel('Цена за единицу, ₽').fill('100')
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands.map(c => c.action)).toEqual(['CLOSE', 'CREATE'])
  expect(api.commands[1].shiftId).toBeUndefined()
  expect(api.reports.get(1)!.shiftId).toBe(11)
})

test('first draft follows a guest-opened shift through reload app reentry Back and venue or account switches', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [], false, true)
  const key = (user: number, venue: number, shift: string | number) => `hookah:accounting:${new URL(page.url()).host}:${user}:${venue}:draft:${shift}:new`
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Название', { exact: true }).fill('Продажа до заказа гостя')
  await page.getByLabel('Количество', { exact: true }).fill('3')
  await page.getByLabel('Цена за единицу, ₽').fill('123,45')
  await page.getByRole('button', { name: 'Ещё позиция', exact: true }).click()
  await page.getByLabel('Название', { exact: true }).nth(1).fill('Вторая позиция')
  await page.getByLabel('Количество', { exact: true }).nth(1).fill('2')
  await page.getByLabel('Цена за единицу, ₽').nth(1).fill('67,89')
  const first = key(103, 1, 'first')
  const bound = key(103, 1, 1)
  api.guestOpensShift()
  await page.reload()
  await expect(page.getByLabel('Название', { exact: true }).nth(0)).toHaveValue('Продажа до заказа гостя')
  await expect(page.getByLabel('Название', { exact: true }).nth(1)).toHaveValue('Вторая позиция')
  await expect(page.getByLabel('Количество', { exact: true }).nth(0)).toHaveValue('3')
  await expect(page.getByLabel('Количество', { exact: true }).nth(1)).toHaveValue('2')
  await expect(page.getByLabel('Цена за единицу, ₽').nth(0)).toHaveValue('123,45')
  await expect(page.getByLabel('Цена за единицу, ₽').nth(1)).toHaveValue('67,89')
  expect(await page.evaluate(k => sessionStorage.getItem(k), first)).toBeNull()
  expect(await page.evaluate(k => JSON.parse(sessionStorage.getItem(k)!).lines.length, bound)).toBe(2)
  const entry = page.url()
  await page.goto('about:blank')
  await page.goto(entry)
  await expect(page.getByLabel('Цена за единицу, ₽').nth(1)).toHaveValue('67,89')
  await page.evaluate(() => window.dispatchEvent(new Event('hookah:e2e-telegram-back')))
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  await page.getByLabel('Выбрать заведение').selectOption('2')
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('')
  await page.getByLabel('Название', { exact: true }).fill('Черновик другого заведения')
  await page.getByLabel('Выбрать заведение').selectOption('1')
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await expect(page.getByLabel('Название', { exact: true }).nth(0)).toHaveValue('Продажа до заказа гостя')
  await api.switchAccount(204)
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('')
  await page.getByLabel('Название', { exact: true }).fill('Черновик другого аккаунта')
  await api.switchAccount(103)
  await expect(page.getByLabel('Название', { exact: true }).nth(0)).toHaveValue('Продажа до заказа гостя')
  expect(api.commands).toHaveLength(0)
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands).toHaveLength(1)
  expect(api.commands[0]).toMatchObject({ action: 'CREATE', shiftId: 1, tableId: 11,
    lines: [{ name: 'Продажа до заказа гостя', qty: 3, unitPriceMinor: 12345 }, { name: 'Вторая позиция', qty: 2, unitPriceMinor: 6789 }] })
  expect(await page.evaluate(k => sessionStorage.getItem(k), bound)).toBeNull()
})

for (const [firstTime, currentTime, expected] of [[100, 200, 'Свежее в смене'], [300, 200, 'Свежий первый'], [200, 200, 'Свежее в смене'], [undefined, undefined, 'Свежее в смене']] as const) {
  test(`draft adoption keeps freshest data and consumes first key (${firstTime}, ${currentTime})`, async ({ page }) => {
    const api = await fixture(page, 'STAFF', [], false, true)
    await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
    await page.evaluate(({ firstTime, currentTime }) => {
      const prefix = `hookah:accounting:${location.host}:103:1:draft:`
      const draft = (name: string, updatedAt?: number) => ({ tableId: '11', reason: '', updatedAt,
        lines: [{ name, qty: '2', price: '100', discount: '0', gift: false, giftReason: '' }] })
      sessionStorage.setItem(`${prefix}first:new`, JSON.stringify(draft('Свежий первый', firstTime)))
      sessionStorage.setItem(`${prefix}1:new`, JSON.stringify(draft('Свежее в смене', currentTime)))
    }, { firstTime, currentTime })
    api.guestOpensShift()
    await page.reload()
    await expect(page.getByLabel('Название', { exact: true })).toHaveValue(expected)
    expect(await page.evaluate(() => sessionStorage.getItem(`hookah:accounting:${location.host}:103:1:draft:first:new`))).toBeNull()
    await page.reload()
    await expect(page.getByLabel('Название', { exact: true })).toHaveValue(expected)
  })
}

test('first draft remains intact if binding storage fails and can be recovered on reload', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [], false, true)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Название', { exact: true }).fill('Не удалять при ошибке storage')
  await page.addInitScript(() => {
    const write = Storage.prototype.setItem
    Storage.prototype.setItem = function (key, value) {
      if (key.endsWith(':draft:1:new')) throw new DOMException('Quota exceeded', 'QuotaExceededError')
      write.call(this, key, value)
    }
  })
  api.guestOpensShift()
  await page.reload()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Не удалять при ошибке storage')
  expect(await page.evaluate(() => sessionStorage.getItem(`hookah:accounting:${location.host}:103:1:draft:first:new`))).not.toBeNull()
})

test('uncertain first sale replays original payload without migrating its pending draft', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [], false, true)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Название', { exact: true }).fill('Неопределённая отправка')
  await page.getByLabel('Цена за единицу, ₽').fill('100')
  api.loseNextResponse()
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Проверить и повторить операцию' })).toBeVisible()
  await page.reload()
  expect(await page.evaluate(() => sessionStorage.getItem(`hookah:accounting:${location.host}:103:1:draft:first:new`))).not.toBeNull()
  expect(await page.evaluate(() => sessionStorage.getItem(`hookah:accounting:${location.host}:103:1:draft:1:new`))).toBeNull()
  await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands).toHaveLength(2)
  expect(api.commands[0]).toEqual(api.commands[1])
  expect(api.commands[1].shiftId).toBeUndefined()
  expect(api.reports.get(1)!.sales).toHaveLength(1)
  expect(await page.evaluate(() => Object.keys(sessionStorage).filter(k => k.startsWith(`hookah:accounting:${location.host}:103:1:draft:`)))).toEqual([])
})

test('first sale is not sent when its replay key cannot be persisted', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [], false, true)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Название', { exact: true }).fill('Сохранить первую продажу')
  await page.getByLabel('Цена за единицу, ₽').fill('123,45')
  await page.evaluate(() => {
    const write = Storage.prototype.setItem
    Storage.prototype.setItem = function (key, value) {
      if (key.endsWith(':pending')) throw new DOMException('Quota exceeded', 'QuotaExceededError')
      write.call(this, key, value)
    }
  })
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Операция не отправлена')
  expect(api.commands).toHaveLength(0)
  expect(api.reports.get(1)!.sales).toHaveLength(0)
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Сохранить первую продажу')
  await expect(page.getByRole('button', { name: 'Сохранить продажу', exact: true })).toBeEnabled()
  await page.reload()
  await expect(page.getByLabel('Цена за единицу, ₽')).toHaveValue('123,45')
  api.loseNextResponse()
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands).toHaveLength(2)
  expect(api.commands[0]).toEqual(api.commands[1])
  expect(api.reports.get(1)!.sales).toHaveLength(1)
})

test('HTTP timeout after first sale commit retains the original replay key through reload', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [], false, true)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Название', { exact: true }).fill('Таймаут после сохранения')
  await page.getByLabel('Цена за единицу, ₽').fill('100')
  api.timeoutNextResponse()
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Проверить и повторить операцию' })).toBeVisible()
  await page.reload()
  await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands).toHaveLength(2)
  expect(api.commands[0]).toEqual(api.commands[1])
  expect(api.commands[1].shiftId).toBeUndefined()
  expect(api.reports.get(1)!.sales).toHaveLength(1)
})

test('first sale conflict preserves all draft lines through guest opening and reload', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [], false, true)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Название', { exact: true }).fill('Первая строка')
  await page.getByLabel('Цена за единицу, ₽').fill('123,45')
  await page.getByRole('button', { name: 'Ещё позиция', exact: true }).click()
  await page.getByLabel('Название', { exact: true }).nth(1).fill('Вторая строка')
  await page.getByLabel('Количество', { exact: true }).nth(1).fill('2')
  await page.getByLabel('Цена за единицу, ₽').nth(1).fill('67,89')
  api.conflictNextSave()
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Данные изменились')
  expect(api.reports.get(1)!.sales).toHaveLength(0)
  await expect(page.getByLabel('Название', { exact: true }).nth(1)).toHaveValue('Вторая строка')
  api.guestOpensShift()
  await page.reload()
  await expect(page.getByLabel('Название', { exact: true }).nth(0)).toHaveValue('Первая строка')
  await expect(page.getByLabel('Название', { exact: true }).nth(1)).toHaveValue('Вторая строка')
  await expect(page.getByLabel('Количество', { exact: true }).nth(1)).toHaveValue('2')
  await expect(page.getByLabel('Цена за единицу, ₽').nth(1)).toHaveValue('67,89')
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands).toHaveLength(2)
  expect(api.commands[1].lines).toEqual(api.commands[0].lines)
  expect(api.commands[1].shiftId).toBe(1)
  expect(api.reports.get(1)!.sales).toHaveLength(1)
})

const accountingScope = (page: Page, user = 103, venue = 1) => `hookah:accounting:${new URL(page.url()).host}:${user}:${venue}`
const stored = (page: Page, key: string) => page.evaluate(k => sessionStorage.getItem(k), key)
async function enterFirstDraft(page: Page) {
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Режим скидки', { exact: true }).selectOption('amount')
  await page.getByLabel('Название', { exact: true }).fill(draftCommands[0].lines[0].name)
  await page.getByLabel('Цена за единицу, ₽').fill('123,45')
}
async function exhaustSessionStorage(page: Page) {
  const quota = await page.evaluate(() => {
    let low = 0, high = 6 * 1024 * 1024, errors = 0
    while (low < high) {
      const middle = Math.ceil((low + high) / 2)
      try { sessionStorage.setItem('accounting-quota-filler', 'x'.repeat(middle)); low = middle }
      catch (error) { if (!(error instanceof DOMException) || error.name !== 'QuotaExceededError') throw error; errors++; high = middle - 1 }
    }
    sessionStorage.setItem('accounting-quota-filler', 'x'.repeat(low))
    return { chars: low, errors }
  })
  expect(quota.chars).toBeGreaterThan(1_000_000)
  expect(quota.errors).toBeGreaterThan(0)
  return quota
}
async function quotaFallback(page: Page) {
  const api = await fixture(page, 'STAFF', [], false, true)
  await enterFirstDraft(page)
  const first = `${accountingScope(page)}:draft:first:new`
  const bound = `${accountingScope(page)}:draft:1:new`
  const original = await stored(page, first)
  const quota = await exhaustSessionStorage(page)
  api.guestOpensShift()
  await page.reload()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue(draftCommands[0].lines[0].name)
  expect(await stored(page, first)).toBe(original)
  expect(await stored(page, bound)).toBeNull()
  return { api, first, bound, original, quota, pending: `${accountingScope(page)}:pending` }
}
function expectDraftCommand(command: AccountingCommand, index = 0) {
  expect(command.requestId).toMatch(/^[0-9a-f-]{36}$/)
  expect({ ...command, requestId: draftCommands[index].requestId }).toEqual(draftCommands[index])
}

test('real quota fallback cleans the actual first draft and permits only a genuinely new sale', async ({ page }, testInfo) => {
  const { api, first, bound, pending, quota } = await quotaFallback(page)
  // The same full browser storage must also prevent sending an unpersisted request.
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Операция не отправлена')
  expect(api.commands).toHaveLength(0)
  expect(await stored(page, pending)).toBeNull()
  expect(await stored(page, first)).not.toBeNull()
  await page.evaluate(() => sessionStorage.removeItem('accounting-quota-filler'))
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands).toHaveLength(1)
  expectDraftCommand(api.commands[0])
  expect(api.reports.get(1)!.sales).toHaveLength(1)
  for (const key of [first, bound, pending]) expect(await stored(page, key)).toBeNull()
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('')
  await expect(page.getByLabel('Цена за единицу, ₽')).toHaveValue('')
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  expect(api.commands).toHaveLength(1)
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Режим скидки', { exact: true }).selectOption('amount')
  await page.getByLabel('Название', { exact: true }).fill(draftCommands[1].lines[0].name)
  await page.getByLabel('Цена за единицу, ₽').fill('67,89')
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands).toHaveLength(2)
  expectDraftCommand(api.commands[1], 1)
  expect(api.commands[1].requestId).not.toBe(api.commands[0].requestId)
  expect(api.reports.get(1)!.totals.accruedMinor).toBe(19134)
  await testInfo.attach('actual-browser-commands.json', { body: JSON.stringify({ quota, commands: api.commands }), contentType: 'application/json' })
})

test('successful first draft adoption removes both operation copies after save', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [], false, true)
  await enterFirstDraft(page)
  const first = `${accountingScope(page)}:draft:first:new`, bound = `${accountingScope(page)}:draft:1:new`
  api.guestOpensShift()
  await page.reload()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue(draftCommands[0].lines[0].name)
  expect(await stored(page, first)).toBeNull()
  expect(await stored(page, bound)).not.toBeNull()
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(await stored(page, first)).toBeNull()
  expect(await stored(page, bound)).toBeNull()
  expect(api.commands).toHaveLength(1)
  expectDraftCommand(api.commands[0])
})

for (const failure of ['conflict', 'before-commit', '408', 'lost-response'] as const) {
  test(`real quota fallback preserves draft after ${failure} and safely retries`, async ({ page }) => {
    const { api, first, bound, pending, original } = await quotaFallback(page)
    await page.evaluate(() => sessionStorage.removeItem('accounting-quota-filler'))
    if (failure === 'conflict') api.conflictNextSave()
    if (failure === 'before-commit') api.failNextBeforeCommit()
    if (failure === '408') api.timeoutNextResponse()
    if (failure === 'lost-response') api.loseNextResponse()
    await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
    if (failure === 'conflict') {
      await expect(page.getByRole('alert')).toContainText('Данные изменились')
      expect(await stored(page, pending)).toBeNull()
      // Available storage permits migration on rerender; the input is still retained.
      expect((await stored(page, first)) ?? (await stored(page, bound))).toBe(original)
    } else {
      await expect(page.getByRole('button', { name: 'Проверить и повторить операцию' })).toBeVisible()
      expect(await stored(page, first)).toBe(original)
      expect(await stored(page, bound)).toBeNull()
      expect(JSON.parse((await stored(page, pending))!).body).toEqual(api.commands[0])
    }
    expect(api.reports.get(1)!.sales).toHaveLength(['408', 'lost-response'].includes(failure) ? 1 : 0)
    await page.reload()
    if (failure === 'conflict') {
      await expect(page.getByLabel('Название', { exact: true })).toHaveValue(draftCommands[0].lines[0].name)
      await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
    } else await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click()
    await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
    expect(api.commands).toHaveLength(2)
    if (failure !== 'conflict') expect(api.commands[1]).toEqual(api.commands[0])
    expectDraftCommand(api.commands[0]); expectDraftCommand(api.commands[1])
    expect(api.reports.get(1)!.sales).toHaveLength(1)
    for (const key of [first, bound, pending]) expect(await stored(page, key)).toBeNull()
  })
}

test('late success preserves newer same-key revision and foreign drafts and pending operation', async ({ page }) => {
  const api = await fixture(page)
  await enterFirstDraft(page)
  const key = `${accountingScope(page)}:draft:1:new`, pending = `${accountingScope(page)}:pending`
  const release = api.holdNextResponse()
  try {
    await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
    await expect.poll(() => api.commands.length).toBe(1)
    const protectedData = await page.evaluate(({ key, pending }) => {
      const draft = JSON.parse(sessionStorage.getItem(key)!)
      // Same payload and clock tick, but a different edit: content/timestamp alone is insufficient.
      draft.revision = crypto.randomUUID()
      const prefix = `hookah:accounting:${location.host}:`
      const entries = [[key, JSON.stringify(draft)], [`${prefix}103:1:draft:99:new`, 'another shift'],
        [`${prefix}103:2:draft:first:new`, 'another venue'], [`${prefix}204:1:draft:first:new`, 'another user'],
        [pending, JSON.stringify({ body: { action: 'CREATE', requestId: 'parallel-operation' } })]]
      entries.forEach(([k, value]) => sessionStorage.setItem(k, value))
      return entries
    }, { key, pending })
    release()
    await expect(page.getByRole('button', { name: 'Проверить и повторить операцию' })).toBeVisible()
    for (const [k, value] of protectedData) expect(await stored(page, k)).toBe(value)
    expect(api.commands).toHaveLength(1)
  } finally { release() }
})

test('double synchronous submit sends one persisted requestId', async ({ page }) => {
  const api = await fixture(page, 'STAFF', [], false, true)
  await enterFirstDraft(page)
  const release = api.holdNextResponse()
  try {
    await page.locator('form.accounting-editor').evaluate(form => {
      form.dispatchEvent(new Event('submit', { cancelable: true }))
      form.dispatchEvent(new Event('submit', { cancelable: true }))
    })
    await expect.poll(() => api.commands.length).toBe(1)
    const pending = JSON.parse((await stored(page, `${accountingScope(page)}:pending`))!)
    expect(pending.body).toEqual(api.commands[0])
    await expect(page.getByRole('button', { name: 'Сохранить продажу', exact: true })).toBeDisabled()
    release()
    await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
    expect(api.commands).toHaveLength(1)
    expect(api.reports.get(1)!.sales).toHaveLength(1)
  } finally { release() }
})

for (const newer of [false, true]) test(`legacy pending replay preserves its request and protects newer draft: ${newer}`, async ({ page }) => {
  const { api, first, bound, pending } = await quotaFallback(page)
  await page.evaluate(() => sessionStorage.removeItem('accounting-quota-filler'))
  api.loseNextResponse()
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Проверить и повторить операцию' })).toBeVisible()
  await page.evaluate(({ first, bound, pending, newer }) => {
    const operation = JSON.parse(sessionStorage.getItem(pending)!)
    // Pre-fix pending records have only the computed shift key, even when the source was first:new.
    sessionStorage.setItem(pending, JSON.stringify({ body: operation.body, draftKey: bound }))
    const draft = JSON.parse(sessionStorage.getItem(first)!)
    if (newer) draft.revision = crypto.randomUUID()
    else delete draft.revision
    sessionStorage.setItem(first, JSON.stringify(draft))
  }, { first, bound, pending, newer })
  const preserved = await stored(page, first)
  await page.reload()
  await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click()
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  expect(api.commands).toHaveLength(2)
  expect(api.commands[1]).toEqual(api.commands[0])
  expect(api.reports.get(1)!.sales).toHaveLength(1)
  expect(await stored(page, pending)).toBeNull()
  expect(await stored(page, first)).toBeNull()
  expect(await stored(page, bound)).toBe(newer ? preserved : null)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue(newer ? draftCommands[0].lines[0].name : '')
})

const legacySearch = (page: Page) => page.getByLabel('Поиск по столу или номеру записи', { exact: true })
const legacyMode = (page: Page, name: string) => page.getByRole('button', { name, exact: true })
async function legacyCreate(page: Page) {
  await legacyMode(page, 'Добавить продажу').click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Название', { exact: true }).fill('Чай')
  await page.getByLabel('Цена за единицу, ₽').fill('100')
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
}
async function legacyFixture(page: Page) {
  await page.clock.setFixedTime(new Date('2026-11-07T20:59:00Z'))
  const paid = sale(); paid.payments = [{ id: 'paid-operation-00001', method: 'CASH', amountMinor: 10000, recordedAt: paid.createdAt, voidReason: null }]
  return fixture(page, 'OWNER', [paid])
}
async function expectExplicitSales(page: Page) {
  await expect(page).toHaveURL(/list=sales/)
  await expect(legacyMode(page, 'Продажи')).toHaveAttribute('aria-pressed', 'true')
  await expect(legacyMode(page, 'Оплаты смены')).toHaveAttribute('aria-pressed', 'false')
  await expect(page.locator('.accounting-sales')).toBeVisible()
  await expect(page.locator('.accounting-shift-payments')).toHaveCount(0)
  await expect(legacySearch(page)).toHaveValue('')
  await expect(legacyMode(page, 'Все')).toHaveAttribute('aria-pressed', 'true')
}

test('legacy CREATE explicit sales choice overrides restored payments and hidden filters', async ({ page }, info) => {
  const api = await legacyFixture(page)
  await legacyMode(page, 'Оплачены').click(); await legacySearch(page).fill('Нет совпадений'); await legacyMode(page, 'Оплаты смены').click()
  await legacyCreate(page); await expect(legacyMode(page, 'Выбрать продажу для оплаты')).toBeVisible()
  const created = `manual:${api.commands[0].requestId}`
  await expect(legacyMode(page, 'Оплаты смены')).toHaveAttribute('aria-pressed', 'true')
  await page.screenshot({ path: info.outputPath('legacy-before-choice.png'), fullPage: true })
  await legacyMode(page, 'Выбрать продажу для оплаты').click(); await expectExplicitSales(page)
  await expect(page).toHaveURL(/filter=all/)
  await expect(page.locator('.accounting-sales [data-sale-id]')).toHaveCount(2)
  const row = page.locator(`.accounting-sales [data-sale-id="${created}"]`); await expect(row).toBeVisible()
  await expect(page.locator('.accounting-payment-form')).toHaveCount(0)
  await page.screenshot({ path: info.outputPath('legacy-after-choice.png'), fullPage: true })
  await openAccountingSale(row); await expect(page).toHaveURL(new RegExp(`sale=${encodeURIComponent(created)}`))
  await expect(page.locator('.accounting-sale-technical')).toContainText(created)
  expect(api.commands.map(c => c.action)).toEqual(['CREATE']); expect(api.reports.get(1)!.sales).toHaveLength(2)
  expect(api.reports.get(1)!.sales.every(s => s.payments.length === (s.id === 'manual:one' ? 1 : 0))).toBe(true)
})

test('legacy sales choice survives history Telegram Back reload and resume', async ({ page }) => {
  const api = await legacyFixture(page); await legacyMode(page, 'Оплаты смены').click(); await legacyCreate(page)
  await legacyMode(page, 'Выбрать продажу для оплаты').click(); await expectExplicitSales(page)
  await page.goBack(); await expect(page).toHaveURL(/list=payments/); await expect(legacyMode(page, 'Оплаты смены')).toHaveAttribute('aria-pressed', 'true')
  await page.goForward(); await expectExplicitSales(page); await page.reload(); await expectExplicitSales(page)
  await page.evaluate(() => {
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' }); document.dispatchEvent(new Event('visibilitychange'))
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' }); document.dispatchEvent(new Event('visibilitychange'))
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
  })
  await expectExplicitSales(page)
  const created = `manual:${api.commands[0].requestId}`
  await openAccountingSale(page.locator(`.accounting-sales [data-sale-id="${created}"]`))
  await page.evaluate(() => window.dispatchEvent(new Event('hookah:e2e-telegram-back'))); await expectExplicitSales(page)
  await page.reload(); await expectExplicitSales(page); expect(api.commands.map(c => c.action)).toEqual(['CREATE'])
})

test('legacy sales choice clears hidden search when URL already targets sales', async ({ page }) => {
  const api = await legacyFixture(page); await legacySearch(page).fill('Нет совпадений'); await legacyCreate(page)
  await expect(legacyMode(page, 'Выбрать продажу для оплаты')).toBeVisible()
  await expect(page.locator('.accounting-editor')).toHaveCount(0)
  await expect(page).toHaveURL(/list=sales/); const url = page.url()
  await legacyMode(page, 'Выбрать продажу для оплаты').click(); await expectExplicitSales(page)
  expect(page.url()).toBe(url); await expect(page.locator('.accounting-sales [data-sale-id]')).toHaveCount(2)
  expect(api.commands.map(c => c.action)).toEqual(['CREATE'])
})

test('ordinary CREATE authoritative identity bypasses restored list without guessing', async ({ page }) => {
  const api = await legacyFixture(page); api.identifyCreate()
  await legacySearch(page).fill('Нет совпадений'); await legacyMode(page, 'Оплаты смены').click(); await legacyCreate(page)
  await expect(legacyMode(page, 'Перейти к записи оплаты')).toBeVisible()
  const created = `manual:${api.commands[0].requestId}`
  // Same table, amount and name; only the server result identity identifies this sale.
  await legacyMode(page, 'Перейти к записи оплаты').click()
  await expect(page).toHaveURL(new RegExp(`sale=${encodeURIComponent(created)}`)); await expect(page.locator('.accounting-sale-technical')).toContainText(created)
  await expect(page.locator('.accounting-sale-technical')).not.toContainText('manual:one')
  await expect(page.locator('.accounting-payment-form')).toBeVisible(); expect(api.commands.map(c => c.action)).toEqual(['CREATE'])
  await legacyMode(page, 'К отчёту').click(); await expect(legacyMode(page, 'Оплаты смены')).toHaveAttribute('aria-pressed', 'true'); await expect(legacySearch(page)).toHaveValue('Нет совпадений')
})

test('legacy choice scope and ordinary tab restoration remain isolated', async ({ page }) => {
  const api = await legacyFixture(page)
  await legacyMode(page, 'Частично').click(); await legacySearch(page).fill('Приватный поиск'); await legacyMode(page, 'Оплаты смены').click()
  await page.evaluate(() => {
    const key = Object.keys(sessionStorage).find(k => k.includes(':103:1:view:'))!
    const old = JSON.parse(sessionStorage.getItem(key)!); delete old.route; sessionStorage.setItem(key, JSON.stringify(old))
  })
  await page.reload(); await expect(legacyMode(page, 'Оплаты смены')).toHaveAttribute('aria-pressed', 'true'); await expect(legacySearch(page)).toHaveValue('Приватный поиск')
  await legacyCreate(page); await legacyMode(page, 'Выбрать продажу для оплаты').click(); await expectExplicitSales(page)
  await legacySearch(page).fill('Только 103'); await legacyMode(page, 'Оплаты смены').click()
  const saved = await page.evaluate(() => Object.fromEntries(Object.entries(sessionStorage).filter(([k]) => k.includes(':103:1:view:'))))
  await page.getByLabel('Выбрать заведение').selectOption('2'); await expect(legacySearch(page)).toHaveValue(''); await expect(legacyMode(page, 'Продажи')).toHaveAttribute('aria-pressed', 'true')
  await api.switchAccount(104); await expect(legacySearch(page)).toHaveValue(''); await expect(legacyMode(page, 'Продажи')).toHaveAttribute('aria-pressed', 'true')
  expect(await page.evaluate(() => Object.fromEntries(Object.entries(sessionStorage).filter(([k]) => k.includes(':103:1:view:'))))).toEqual(saved)
  expect(api.commands.map(c => c.action)).toEqual(['CREATE'])
})

test('legacy choice after uncertain CREATE preserves exact pending replay and no payment', async ({ page }) => {
  const api = await legacyFixture(page); await legacyMode(page, 'Оплаты смены').click(); api.loseNextResponse(); await legacyCreate(page)
  await expect(page.locator('.accounting-pending')).toBeVisible()
  const pending = await page.evaluate(() => Object.fromEntries(Object.entries(sessionStorage).filter(([k]) => k.includes('hookah:accounting:') && /:(pending|draft:.*)$/.test(k))))
  await page.reload(); await expect(page.locator('.accounting-pending')).toBeVisible()
  expect(await page.evaluate(() => Object.fromEntries(Object.entries(sessionStorage).filter(([k]) => k.includes('hookah:accounting:') && /:(pending|draft:.*)$/.test(k))))).toEqual(pending)
  expect(api.commands).toHaveLength(1)
  await legacyMode(page, 'Проверить и повторить операцию').click(); await legacyMode(page, 'Выбрать продажу для оплаты').click(); await expectExplicitSales(page)
  expect(api.commands).toHaveLength(2); expect(api.commands[1]).toEqual(api.commands[0]); expect(api.commands.every(c => c.action === 'CREATE')).toBe(true)
  expect(api.reports.get(1)!.sales).toHaveLength(2)
})
