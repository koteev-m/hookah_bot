import { test, expect, type Page } from '@playwright/test'
import { salePaid, salePayable, type AccountingCommand, type AccountingReport, type AccountingSale, type AccountingShiftHeader } from '../src/shared/api/accounting'

const at = '2026-11-15T12:30:00Z'
function sales(count: number): AccountingSale[] {
  return Array.from({ length: count }, (_, i) => ({ id: `manual:${String(i).padStart(4, '0')}`, tableId: 11, tableLabel: i === 0 ? 'Терраса 42' : 'Стол 1', source: 'MANUAL', orderId: null, displayNumber: null, orderClosed: true, createdAt: new Date(Date.parse(at) - i * 60000).toISOString(), currency: 'RUB', version: 1, cancelReason: null, disputeReason: null, originalPayableMinor: null, issue: null,
    lines: [{ id: `line-${i}`, name: 'Чай', qty: 1, unitPriceMinor: 99000, menuItemId: null, source: 'MANUAL', tabId: null, baseDiscountMinor: 0, baseGiftMinor: 0, discountMinor: 0, giftReason: null }], payments: [] }))
}
function report(items: AccountingSale[]): AccountingReport {
  const paid = items.reduce((n, s) => n + salePaid(s), 0), accrued = items.reduce((n, s) => n + salePayable(s), 0)
  return { shiftId: 1, venueId: 1, status: 'OPEN', timezone: 'Europe/Moscow', openedAt: '2026-11-13T00:00:00Z', closedAt: null, currency: 'RUB', sales: items,
    totals: { salesCount: items.filter(s => !s.cancelReason).length, ordersCount: 0, grossMinor: accrued, discountMinor: 0, giftMinor: 0, accruedMinor: accrued, paidMinor: paid, unpaidMinor: accrued - paid, cashMinor: paid, cardMinor: 0, transferMinor: 0 },
    byTable: [{ label: 'Стол 1', qty: items.length, accruedMinor: accrued }], byItem: [{ label: 'Чай', qty: items.length, accruedMinor: accrued }], bySource: [{ label: 'MANUAL', qty: items.length, accruedMinor: accrued }], unpaidSaleIds: items.filter(s => salePaid(s) < salePayable(s)).map(s => s.id), disputedSaleIds: items.filter(s => s.issue || s.disputeReason).map(s => s.id), reportHash: 'sections-authoritative-hash' }
}
async function fixture(page: Page, options: { count?: number; items?: AccountingSale[]; role?: string; empty?: boolean; history?: number; width?: number } = {}) {
  await page.setViewportSize({ width: options.width ?? 390, height: 844 }); await page.emulateMedia({ colorScheme: 'dark' }); await page.clock.setFixedTime(new Date(at))
  let current: AccountingReport | null = options.empty ? null : report(options.items ?? sales(options.count ?? 21))
  const closed: AccountingShiftHeader[] = Array.from({ length: options.history ?? 0 }, (_, i) => ({ id: 900 - i, status: 'CLOSED', timezone: 'Europe/Moscow', openedAt: '2026-11-10T00:00:00Z', closedAt: '2026-11-10T10:00:00Z' }))
  const commands: AccountingCommand[] = [], gets: string[] = []; let failure = 0
  await page.addInitScript(() => {
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 105 } }, ready() {}, expand() {}, onEvent() {}, offEvent() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:105`, JSON.stringify({ token: 'isolated-sections-fixture', expiresAtEpochSeconds: 4102444800 }))
  })
  await page.route('**/miniapp/sections-test*', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"></head><body><div id="app"></div><script type="module">import {mountVenueApp} from '/miniapp/src/screens/venueApp.ts';import {bindAppTheme} from '/miniapp/src/shared/ui/appTheme.ts';bindAppTheme(document.documentElement);window.disposeSections=mountVenueApp({root:document.querySelector('#app'),backendUrl:location.origin});</script></body></html>` }))
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    if (url.pathname === '/api/venue/me') return route.fulfill({ json: { userId: 105, venues: [1, 2].map(venueId => ({ venueId, venueName: `Заведение ${venueId}`, venueStatus: 'PUBLISHED', role: options.role ?? 'OWNER', permissions: ['MENU_VIEW', 'TABLE_VIEW'] })) } })
    if (url.pathname === '/api/venue/tables') return route.fulfill({ json: { tables: [{ tableId: 11, tableNumber: 1, tableLabel: 'Стол 1', isActive: true }] } })
    if (url.pathname === '/api/venue/menu') return route.fulfill({ json: { venueId: 1, categories: [] } })
    if (!url.pathname.includes('/accounting')) return route.fulfill({ json: { unreadCount: 0, calls: [], items: [], linked: false } })
    if (req.method() === 'POST') { commands.push(req.postDataJSON()); return route.fulfill({ status: 500, json: { error: { code: 'INTERNAL_ERROR', message: 'Результат неизвестен' } } }) }
    gets.push(url.pathname + url.search)
    if (failure) return route.fulfill({ status: failure, json: { error: { code: failure === 403 ? 'FORBIDDEN' : 'INTERNAL_ERROR' } } })
    const id = /accounting\/(\d+)$/.exec(url.pathname)?.[1]
    if (id) return route.fulfill({ json: { ...report(sales(1)), shiftId: Number(id), status: 'CLOSED', closedAt: closed[0]?.closedAt } })
    const before = Number(url.searchParams.get('beforeShiftId')) || 901, history = options.role === 'STAFF' ? [] : closed.filter(s => s.id < before).slice(0, 20)
    return route.fulfill({ json: { current: url.pathname.includes('/2/') ? report(sales(1)) : current, history, nextHistoryBeforeId: closed.some(s => s.id < (history.at(-1)?.id ?? 0)) ? history.at(-1)!.id : null } })
  })
  await page.goto('sections-test?venueId=1#/accounting?av=1'); await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toBeVisible()
  return { commands, gets, state: () => current, replace(items: AccountingSale[]) { current = report(items) }, fail(status: number) { failure = status } }
}
const rows = (page: Page) => page.locator('.accounting-sales [data-sale-id]')
const search = (page: Page) => page.getByLabel('Поиск по столу или номеру записи', { exact: true })

test('shift sections ordered disclosures and zero mutation on keyboard toggles', async ({ page }) => {
  const api = await fixture(page, { count: 1, history: 1 }), getCount = api.gets.length
  expect(await page.locator('[data-accounting-section]').evaluateAll(ns => ns.map(n => n.getAttribute('data-accounting-section')))).toEqual(['totals', 'entries', 'archive'])
  const total = page.locator('.accounting-shift-breakdown'), archive = page.locator('.accounting-archive')
  await expect(total).not.toHaveAttribute('open'); await expect(archive).not.toHaveAttribute('open')
  await expect(total.locator('summary').first()).toHaveText('Подробный итог текущей смены')
  await total.locator('summary').first().focus(); await page.keyboard.press('Enter'); await expect(total).toHaveAttribute('open', '')
  await page.keyboard.press('Space'); await expect(total).not.toHaveAttribute('open')
  await archive.locator('summary').focus(); await page.keyboard.press('Enter'); await expect(archive).toHaveAttribute('open', '')
  expect(api.gets).toHaveLength(getCount); expect(api.commands).toEqual([])
})

for (const count of [0, 1, 20, 21, 100, 500]) test(`shift sections bounded DOM and full report ${count}`, async ({ page }, info) => {
  const api = await fixture(page, { count })
  await expect(rows(page)).toHaveCount(Math.min(count, 20)); await expect(page.locator('[data-shift-count]')).toHaveAttribute('data-shift-count', String(count))
  await expect(page.locator('.accounting-list-count')).toHaveText(`Найдено: ${count} · В списке: ${Math.min(count, 20)}`)
  if (count > 20) { await page.getByRole('button', { name: 'Показать ещё', exact: true }).click(); await expect(rows(page)).toHaveCount(Math.min(count, 40)) }
  if (count === 500) {
    await page.screenshot({ path: info.outputPath('large-initial.png'), fullPage: true })
    const input = search(page), handle = await input.elementHandle(); await input.fill('Терраса 42'); await expect(rows(page)).toHaveCount(1); await expect(rows(page).first()).toHaveAttribute('data-sale-id', 'manual:0000'); await expect(input).toBeFocused(); expect(await handle!.evaluate(n => n.isConnected)).toBe(true)
    await input.fill('Запись 1'); await expect(rows(page)).toHaveCount(20); await expect(page.locator('.accounting-list-count')).toHaveText('Найдено: 111 · В списке: 20')
    expect(api.state()!.totals.accruedMinor).toBe(49500000); await expect(page.locator('.accounting-shift-summary')).toContainText('495 000,00')
  }
  expect(api.commands).toEqual([]); await page.screenshot({ path: info.outputPath(`sales-${count}.png`), fullPage: true })
})

for (const width of [360, 390]) test(`shift sections date groups full search filter and Back ${width}`, async ({ page }, info) => {
  const items = sales(100)
  items[80].createdAt = '2026-11-14T12:00:00Z'; items[81].createdAt = '2026-11-13T12:00:00Z'
  items[80].disputeReason = 'Проверить источник'; items[81].tableLabel = 'Зал 73'
  items[80].payments = [{ id: 'voided-record-000001', method: 'CASH', amountMinor: 40000, recordedAt: items[80].createdAt, voidReason: 'Неверно' }]
  const api = await fixture(page, { items, width }), gets = api.gets.length
  await expect(page.locator('.accounting-attention')).toContainText('Спорных: 1')
  await search(page).fill('Стол 1'); await page.getByRole('button', { name: 'Не оплачены', exact: true }).click()
  await expect(page.locator('.accounting-list-count')).toContainText('Найдено: 98')
  await search(page).fill('Зал 73'); await expect(rows(page)).toHaveCount(1); await expect(rows(page)).toBeVisible()
  await expect(page.locator('.accounting-day-group')).toHaveAttribute('open', ''); await expect(page.locator('[data-sale-date]')).toContainText('13.11.2026')
  await rows(page).click(); await expect(page.locator('.accounting-sale-technical')).toContainText('manual:0081')
  await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await expect(search(page)).toHaveValue('Зал 73'); await expect(rows(page)).toHaveCount(1); await expect(rows(page)).toBeFocused()
  await search(page).fill(''); await page.getByRole('button', { name: 'Все', exact: true }).click()
  await search(page).fill('Запись 2'); await expect(page.locator('.accounting-list-count')).toContainText('Найдено: 11')
  expect(api.commands).toEqual([]); expect(api.gets.length).toBe(gets + 2)
  await page.screenshot({ path: info.outputPath('search-and-dates.png'), fullPage: true })
})

test('shift sections old dates collapsed but review filter exposes records', async ({ page }) => {
  const items = sales(3); items[1].createdAt = '2026-11-14T12:00:00Z'; items[2].createdAt = '2026-11-13T12:00:00Z'; items[2].issue = 'MISSING_SNAPSHOT'
  await fixture(page, { items })
  await expect(page.locator('.accounting-day-group').nth(0)).toHaveAttribute('open', '')
  await expect(page.locator('.accounting-day-group').nth(1)).not.toHaveAttribute('open')
  await expect(page.locator('.accounting-day-group').nth(2)).not.toHaveAttribute('open')
  await expect(page.locator('.accounting-day-group').nth(2).locator('summary')).toContainText('Сверка: 1')
  await page.getByRole('button', { name: 'Оплачены', exact: true }).click(); await expect(rows(page)).toHaveCount(1); await expect(rows(page)).toBeVisible(); await expect(rows(page)).toContainText('Требует сверки')
})

test('shift sections split operations bounded independent of sales and preserved corrections', async ({ page }, info) => {
  const items = sales(500)
  items.forEach((s, i) => { s.payments = ['CASH', 'CARD'].map((method, j) => ({ id: `split-fixture-${String(i).padStart(8, '0')}:${method}`, method, amountMinor: j ? 59000 : 40000, recordedAt: s.createdAt, voidReason: i === 0 && j === 0 ? 'Исправлено кассиром' : null })) })
  const api = await fixture(page, { items }), gets = api.gets.length
  await page.getByRole('button', { name: 'Оплаты смены', exact: true }).click()
  await expect(rows(page)).toHaveCount(0)
  const operations = page.locator('.accounting-shift-payments [data-payment-operation]')
  await expect(operations).toHaveCount(20); await expect(operations.locator('[data-payment-component]')).toHaveCount(40)
  await expect(operations.first()).toContainText('Исправлено кассиром'); await expect(page.locator('.accounting-shift-payments')).toContainText('Операций: 500 · Компонентов: 1000')
  await page.getByRole('button', { name: 'Показать ещё', exact: true }).click(); await expect(operations).toHaveCount(40)
  expect(await operations.evaluateAll(ns => new Set(ns.map(n => n.getAttribute('data-sale-id'))).size)).toBe(40)
  await operations.first().getByRole('button', { name: /Открыть продажу/ }).click(); await expect(page).toHaveURL(/sale=manual%3A0000/)
  await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await expect(operations).toHaveCount(40); await expect(operations.first().getByRole('button')).toBeFocused()
  await page.getByRole('button', { name: 'Продажи', exact: true }).click(); await expect(rows(page)).toHaveCount(20); await expect(operations).toHaveCount(0)
  expect(api.commands).toEqual([]); expect(api.gets).toHaveLength(gets + 2)
  await page.getByRole('button', { name: 'Оплаты смены', exact: true }).click(); await page.screenshot({ path: info.outputPath('payments-large.png'), fullPage: true })
})

test('shift sections archive pages preserve current UI and open exact frozen report', async ({ page }, info) => {
  const api = await fixture(page, { count: 1, history: 45 }), gets = api.gets.length
  const archive = page.locator('.accounting-archive'); await archive.locator('summary').click(); await expect(archive.locator('.accounting-archive-list > button')).toHaveCount(21)
  const searchNode = await search(page).elementHandle()
  await page.getByRole('button', { name: 'Ещё закрытые смены' }).click(); await expect(archive.locator('.accounting-archive-list > button')).toHaveCount(41)
  expect(await searchNode!.evaluate(n => n.isConnected)).toBe(true); expect(api.gets).toHaveLength(gets + 1)
  await page.screenshot({ path: info.outputPath('archive.png'), fullPage: true })
  await archive.locator('.accounting-archive-list > button').nth(25).click(); await expect(page).toHaveURL(/shift=875/)
  await expect(page.locator('.accounting-shift-breakdown > summary')).toHaveText('Итог закрытой смены')
  await expect(page.getByRole('button', { name: 'Добавить продажу', exact: true })).toHaveCount(0); await expect(page.getByRole('button', { name: 'Сверить и закрыть смену', exact: true })).toHaveCount(0)
  expect(api.gets).toContain('/api/venue/1/accounting/875'); expect(api.commands).toEqual([])
})

test('shift sections scope revoke and private filters remain isolated', async ({ page }) => {
  const api = await fixture(page)
  await search(page).fill('Терраса'); await page.getByLabel('Выбрать заведение').selectOption('2'); await expect(search(page)).toHaveValue(''); await expect(rows(page)).toHaveCount(1)
  api.fail(403); await page.getByRole('button', { name: 'Обновить отчёт', exact: true }).click(); await expect(search(page)).toHaveCount(0); await expect(page.locator('.venue-accounting')).toContainText('Доступ')
  expect(api.commands).toEqual([])
})

test('shift sections failed refresh discloses stale data without false success', async ({ page }) => {
  const api = await fixture(page, { count: 1 }); api.fail(500)
  await page.getByRole('button', { name: 'Обновить отчёт', exact: true }).click()
  await expect(page.locator('.venue-accounting > .error-card')).toBeVisible(); await expect(page.locator('.venue-accounting > .error-card')).toContainText('обновить')
  await expect(page.locator('[data-shift-count]')).toHaveAttribute('data-shift-count', '1'); expect(api.commands).toEqual([])
})

test('shift sections STAFF has current entries without archive or close', async ({ page }) => {
  await fixture(page, { role: 'STAFF', count: 1, history: 45 })
  await expect(page.locator('.accounting-archive')).toHaveCount(0); await expect(page.getByRole('button', { name: 'Сверить и закрыть смену' })).toHaveCount(0)
  await expect(page.locator('.accounting-old-shift')).toContainText('владелец или менеджер')
  await page.getByRole('button', { name: 'Оплаты смены', exact: true }).click(); await expect(page.locator('.accounting-shift-payments')).toBeVisible()
})

test('shift sections desktop light layout and empty shift guidance', async ({ page }, info) => {
  await fixture(page, { empty: true, width: 1440 }); await page.emulateMedia({ colorScheme: 'light' })
  await expect(page.locator('.venue-accounting')).toContainText('открывается автоматически при первой сохранённой продаже или заказе')
  await expect(page.locator('.accounting-archive')).not.toHaveAttribute('open'); await page.locator('.accounting-archive > summary').click(); await expect(page.locator('.accounting-archive')).toContainText('Закрытых смен пока нет')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: info.outputPath('empty-desktop-light.png'), fullPage: true })
})

test('shift sections search caret disclosures and scroll survive non destructive actions', async ({ page }, info) => {
  const api = await fixture(page, { count: 100, width: 1440 }), gets = api.gets.length
  await page.emulateMedia({ colorScheme: 'light' })
  const field = search(page), handle = await field.elementHandle()
  await field.fill('Стол 1'); await field.press('ArrowLeft')
  const caret = await field.evaluate(n => (n as HTMLInputElement).selectionStart)
  await field.press('Enter'); await expect(field).toBeFocused(); expect(await handle!.evaluate(n => n.isConnected)).toBe(true); expect(await field.evaluate(n => (n as HTMLInputElement).selectionStart)).toBe(caret)
  await page.locator('.accounting-shift-breakdown > summary').click(); await page.locator('.accounting-shift-breakdown > summary').click()
  await page.getByRole('button', { name: 'Показать ещё', exact: true }).focus(); const scroll = await page.evaluate(() => scrollY)
  await page.keyboard.press('Enter'); await expect(rows(page)).toHaveCount(40); await expect(page.getByRole('button', { name: 'Показать ещё', exact: true })).toBeFocused(); expect(await page.evaluate(() => scrollY)).toBe(scroll)
  expect(api.gets).toHaveLength(gets); expect(api.commands).toEqual([])
  await page.screenshot({ path: info.outputPath('desktop-light.png'), fullPage: true })
})
