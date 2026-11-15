import { test, expect, type Page } from '@playwright/test'
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { statsResponse } from './fixtures/venue-stats'
import { salePaid, salePayable, type AccountingCommand, type AccountingReport, type AccountingSale } from '../src/shared/api/accounting'

const categories = Array.from({ length: 3 }, (_, c) => ({ id: c + 1, name: ['Напитки', 'Еда', 'Кальяны'][c], isActive: true, items: Array.from({ length: 50 }, (_, i) => ({ id: 1000 + c * 50 + i, categoryId: c + 1, name: i === 0 ? 'Одинаковый товар' : `Товар ${c * 50 + i}`, priceMinor: 150000, currency: 'RUB', isAvailable: true, options: [] })) }))
const makeSale = (id: string, amount = 99000): AccountingSale => ({ id, tableId: 11, tableLabel: 'Стол 1', source: 'MANUAL', orderId: null, displayNumber: null, orderClosed: true, createdAt: new Date().toISOString(), currency: 'RUB', version: 1, cancelReason: null, disputeReason: null, originalPayableMinor: null, issue: null, payments: [], lines: [{ id: `line-${id}`, name: 'Чай', qty: 1, unitPriceMinor: amount, menuItemId: null, source: 'MANUAL', tabId: null, baseDiscountMinor: 0, baseGiftMinor: 0, discountMinor: 0, giftReason: null }] })
function report(sales: AccountingSale[] = []): AccountingReport {
  const active = sales.filter(s => !s.cancelReason), paid = sales.flatMap(s => s.payments).filter(p => !p.voidReason)
  const accrued = active.reduce((v, s) => v + salePayable(s), 0), received = paid.reduce((v, p) => v + p.amountMinor, 0)
  return { shiftId: 1, venueId: 1, status: 'OPEN', openedAt: new Date().toISOString(), timezone: 'Europe/Moscow', closedAt: null, currency: 'RUB', sales, totals: { salesCount: active.length, ordersCount: 0, grossMinor: accrued, discountMinor: 0, giftMinor: 0, accruedMinor: accrued, paidMinor: received, unpaidMinor: accrued - received, cashMinor: paid.filter(p => p.method === 'CASH').reduce((v, p) => v + p.amountMinor, 0), cardMinor: paid.filter(p => p.method === 'CARD').reduce((v, p) => v + p.amountMinor, 0), transferMinor: paid.filter(p => p.method === 'TRANSFER').reduce((v, p) => v + p.amountMinor, 0) }, byTable: [], byItem: [], bySource: [], unpaidSaleIds: active.filter(s => salePaid(s) < salePayable(s)).map(s => s.id), disputedSaleIds: sales.filter(s => s.issue || s.disputeReason).map(s => s.id), reportHash: 'synthetic-finalize' }
}
async function setup(page: Page, sales: AccountingSale[] = [], liveDir?: string) {
  const live = () => liveDir ? JSON.parse(readFileSync(join(liveDir, 'ready.json'), 'utf8')) : undefined
  if (liveDir && test.info().project.name === 'chromium') await page.context().grantPermissions(['local-network-access'], { origin: new URL(test.info().project.use.baseURL!).origin })
  let state = report(sales), failure = 0, lost = false, concurrentCreate = false, loseCreate = false, legacyResult = false, held: Promise<void> | null = null
  const commands: AccountingCommand[] = [], accepted = new Set<string>(), results = new Map<string, string>()
  if (liveDir) page.on('requestfailed', req => { if (new URL(req.url()).pathname.startsWith('/api/')) appendFileSync(join(liveDir, 'browser-network.jsonl'), JSON.stringify({ method: req.method(), url: req.url(), failure: req.failure()?.errorText }) + '\n') })
  if (liveDir) page.on('response', async response => {
    const req = response.request(), url = new URL(response.url())
    if (!url.pathname.startsWith('/api/')) return
    const headers = req.headers(), body = req.method() === 'POST' ? req.postDataJSON() : undefined
    const entry = { method: req.method(), url: response.url(), origin: headers.origin, authorization: headers.authorization?.startsWith('Bearer ') ? 'Bearer present' : 'absent', contentType: headers['content-type'], action: body?.action, requestId: body?.requestId, status: response.status(), allowOrigin: response.headers()['access-control-allow-origin'] }
    appendFileSync(join(liveDir, 'browser-network.jsonl'), JSON.stringify(entry) + '\n')
  })
  await page.addInitScript(token => {
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 101 } }, ready() {}, expand() {}, onEvent() {}, offEvent() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:101`, JSON.stringify({ token, expiresAtEpochSeconds: 4102444800 }))
  }, live()?.token ?? 'synthetic-payment-finalize')
  await page.route('**/miniapp/payment-finalize-test*', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"></head><body><div id="app"></div><script type="module">
    import {renderVenueAccountingScreen} from '/miniapp/src/screens/venueAccounting.ts';import {renderVenueStatsScreen} from '/miniapp/src/screens/venueStats.ts';import {bindAppTheme} from '/miniapp/src/shared/ui/appTheme.ts';bindAppTheme(document.documentElement);
    let dispose;const access={venueId:1,venueName:'Тестовая площадка',venueStatus:'PUBLISHED',role:'OWNER',permissions:['ORDER_QUEUE_VIEW','MENU_VIEW','TABLE_VIEW']};
    const mount=()=>{dispose?.();dispose=renderVenueAccountingScreen({root:document.querySelector('#app'),backendUrl:${JSON.stringify(live()?.base)}??location.origin,isDebug:false,venueId:1,access,userId:101})};window.addEventListener('hashchange',mount);mount();window.showStats=()=>{dispose?.();dispose=renderVenueStatsScreen({root:document.querySelector('#app'),backendUrl:${JSON.stringify(live()?.base)}??location.origin,isDebug:false,venueId:1,access})};</script></body></html>` }))
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    if (liveDir) {
      if (req.method() === 'POST') {
        const body = req.postDataJSON() as AccountingCommand; commands.push(body)
        if (liveDir === process.env.ACCOUNTING_CREATE_LIVE_DIR && body.action === 'CREATE' && !lost) {
          const response = await route.fetch(); expect(response.status()).toBe(200)
          const committed = await response.json(); expect(committed.createdSaleId).toBeTruthy()
          writeFileSync(join(liveDir, 'committed-create.json'), JSON.stringify(committed)); lost = true; await route.abort('failed'); return
        }
        if (body.action === 'PAY_SPLIT' && !lost) {
          const response = await route.fetch()
          expect(response.status()).toBe(200)
          const committed = await response.json()
          expect(committed.totals).toMatchObject({ accruedMinor: 289500, paidMinor: 99000, cashMinor: 40000, cardMinor: 59000, salesCount: 2 })
          writeFileSync(join(liveDir, 'committed-response.json'), JSON.stringify(committed))
          lost = true; await route.abort('failed'); writeFileSync(join(liveDir, 'restart'), 'committed'); return
        }
      }
      return route.continue()
    }
    if (url.pathname === '/api/venue/menu') return route.fulfill({ json: { venueId: 1, categories } })
    if (url.pathname === '/api/venue/tables') return route.fulfill({ json: { tables: [{ tableId: 11, tableLabel: 'Стол 1', tableNumber: 1, isActive: true }] } })
    if (/\/stats\//.test(url.pathname)) {
      const body = statsResponse(url)
      if (!['all', 'finance'].includes(url.searchParams.get('section')!)) return route.fulfill({ json: body })
      const group = structuredClone(body.openShiftFinances[0] ?? body.closedShiftFinances[0])
      for (const [key, value] of Object.entries({ accrued: state.totals.accruedMinor, operatorConfirmed: state.totals.paidMinor, unpaid: state.totals.unpaidMinor, sales: state.totals.salesCount, averageAccruedSale: state.totals.salesCount ? Math.round(state.totals.accruedMinor / state.totals.salesCount) : null })) {
        group.metrics[key] = { ...group.metrics.accrued, value: value == null ? null : String(value), unit: key === 'sales' ? 'COUNT' : 'MINOR_UNITS', availability: value == null ? 'UNAVAILABLE' : 'AVAILABLE' }
      }
      body.openShiftFinances = state.status === 'OPEN' ? [group] : []; body.closedShiftFinances = state.status === 'CLOSED' ? [group] : []
      body.openShiftOpenedAt = state.status === 'OPEN' ? state.openedAt : null
      if (body.metrics['finance.openShifts']) body.metrics['finance.openShifts'].value = state.status === 'OPEN' ? '1' : '0'
      if (body.metrics['finance.closedReports']) body.metrics['finance.closedReports'].value = state.status === 'CLOSED' ? '1' : '0'
      return route.fulfill({ json: body })
    }
    if (req.method() === 'POST') {
      const body = req.postDataJSON() as AccountingCommand; commands.push(body)
      if (held) { const wait = held; held = null; await wait }
      if (failure) { const code = failure; failure = 0; if (code === -1) return route.abort('failed'); return route.fulfill({ status: code, json: { error: { code: code === 409 ? 'ACCOUNTING_CONFLICT' : 'INVALID_INPUT', message: 'Продажа изменилась. Проверьте остаток.' } } }) }
      if (!accepted.has(body.requestId)) {
        accepted.add(body.requestId)
        if (body.action === 'CREATE') {
          if (concurrentCreate) { state.sales.push(makeSale('manual:concurrent')); concurrentCreate = false }
          const sale = makeSale(`manual:${body.requestId}`); sale.lines = body.lines!.map((l, i) => ({ ...sale.lines[0], ...l, id: `line-${i}`, name: l.name!, unitPriceMinor: l.unitPriceMinor!, menuItemId: l.menuItemId ?? null, discountMinor: l.discountPercent ? Math.round(l.qty * l.unitPriceMinor! * Number(l.discountPercent) / 100) : l.discountMinor ?? 0, giftReason: l.giftReason ?? null })); state = { ...report([...state.sales, sale]), openedAt: state.openedAt }; results.set(body.requestId, sale.id)
        } else if (body.action === 'PAY' || body.action === 'PAY_SPLIT') {
          const sale = state.sales.find(s => s.id === body.saleId)!; sale.version++
          const parts = body.action === 'PAY_SPLIT' ? body.paymentComponents! : [{ method: body.paymentMethod!, amountMinor: body.amountMinor! }]
          sale.payments.push(...parts.map(p => ({ ...p, id: body.action === 'PAY_SPLIT' ? `${body.requestId}:${p.method}` : body.requestId, recordedAt: new Date().toISOString(), voidReason: null }))); state = { ...report(state.sales), openedAt: state.openedAt }
        } else if (body.action === 'CLOSE') { state.status = 'CLOSED'; state.closedAt = new Date().toISOString() }
      }
      if (body.action === 'CREATE' && loseCreate) { loseCreate = false; return route.abort('failed') }
      return route.fulfill({ json: { ...state, ...(!legacyResult && results.has(body.requestId) ? { createdSaleId: results.get(body.requestId) } : {}) } })
    }
    if (/\/accounting/.test(url.pathname)) return route.fulfill({ json: /\/accounting\/\d+$/.test(url.pathname) ? state : { current: state.status === 'OPEN' ? state : null, history: state.status === 'CLOSED' ? [{ id: state.shiftId, ...state }] : [] } })
    return route.fulfill({ json: {} })
  })
  await page.goto('payment-finalize-test#/accounting?av=1')
  await expect(page.getByRole('heading', { name: 'Учёт смены' })).toBeVisible()
  return { commands, legacyResult() { legacyResult = true }, loseCreate() { loseCreate = true }, addSale(sale: AccountingSale) { state = report([sale, ...state.sales]) }, concurrentCreate() { concurrentCreate = true }, fail(value: number) { failure = value }, state: () => state, hold() { let release!: () => void; held = new Promise<void>(r => { release = r }); return release } }
}
const pay = (page: Page) => page.locator('.accounting-payment-form button[type=submit]')
const open = async (page: Page) => page.locator('.accounting-sales > button').first().click()
const screenshot = (page: Page, info: { outputPath: (name: string) => string }, name: string) => page.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true })

test('native item controls retain identity without focus restoration through selection discount gift and second row', async ({ page }) => {
  await setup(page); await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Категория меню').selectOption('1')
  const item = page.getByLabel('Товар', { exact: true }), handle = await item.elementHandle()
  await item.focus(); await page.evaluate(() => { (window as any).pickerFocusCalls = 0; const original = HTMLElement.prototype.focus; HTMLElement.prototype.focus = function(...args) { if (this.tagName === 'SELECT') (window as any).pickerFocusCalls++; return original.apply(this, args) } })
  await item.selectOption('1000'); expect(await handle!.evaluate(node => node.isConnected)).toBe(true)
  await expect(page.getByLabel('Цена за единицу, ₽')).toHaveValue('1500.00'); await page.keyboard.press('Tab'); await expect(page.getByRole('button', { name: 'Позиция не из меню', exact: true })).toBeFocused()
  await page.getByLabel('Поиск товара по всему меню').fill('Одинаковый товар'); await expect(item.locator('option')).toHaveCount(4); await item.selectOption('1050')
  await page.getByLabel('Режим скидки').selectOption('amount'); await page.getByLabel('Подарок / бесплатно').check(); await expect(page.getByLabel('Причина подарка')).toBeVisible()
  expect(await handle!.evaluate(node => node.isConnected)).toBe(true)
  await page.getByRole('button', { name: 'Ещё позиция', exact: true }).click()
  const second = page.getByLabel('Товар', { exact: true }).last(), secondHandle = await second.elementHandle()
  await page.getByLabel('Категория меню').last().selectOption('2'); await second.selectOption('1050'); await second.selectOption('1051'); expect(await secondHandle!.evaluate(node => node.isConnected)).toBe(true)
  await expect(page.locator('.accounting-line')).toHaveCount(2)
  expect(await page.evaluate(() => (window as any).pickerFocusCalls)).toBe(0)
  await page.getByRole('button', { name: 'Убрать позицию', exact: true }).last().click(); await expect(page.locator('.accounting-line')).toHaveCount(1)
  await page.reload(); await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Одинаковый товар')
})
for (const [width, theme] of [[390, 'dark'], [360, 'dark'], [1440, 'light']] as const) {
  test(`ordinary partial split payment explicit states and accessible result ${width} ${theme}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 }); await page.emulateMedia({ colorScheme: theme })
    const api = await setup(page, [makeSale('manual:ordinary'), makeSale('manual:split'), makeSale('manual:partial')])
    await page.locator('.accounting-sales > [data-sale-id="manual:ordinary"]').click(); await expect(pay(page)).toContainText('Записать 990,00 ₽ наличными'); await screenshot(page, info, 'ordinary-payment')
    const release = api.hold(); await pay(page).click(); await expect(pay(page)).toBeDisabled(); await page.locator('.accounting-payment-form').evaluate(f => f.dispatchEvent(new Event('submit', { cancelable: true }))); release()
    await expect(page.getByText('Оплачено полностью', { exact: true })).toBeVisible(); await expect(page.locator('.accounting-payment-form')).toHaveCount(0); expect(api.commands).toHaveLength(1); await screenshot(page, info, 'fully-paid')
    await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await page.locator('.accounting-sales > [data-sale-id="manual:split"]').click()
    await page.getByLabel('Способ оплаты').selectOption('SPLIT'); await page.getByLabel('Наличные, ₽').fill('400'); await page.getByLabel('Карта, ₽').fill('590'); await screenshot(page, info, 'combined-payment'); await pay(page).click()
    await expect(page.getByText('Оплачено полностью', { exact: true })).toBeVisible(); expect(api.commands[1]).toMatchObject({ action: 'PAY_SPLIT', paymentComponents: [{ method: 'CASH', amountMinor: 40000 }, { method: 'CARD', amountMinor: 59000 }] })
    await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await page.locator('.accounting-sales > [data-sale-id="manual:partial"]').click()
    await page.getByLabel('Частичная оплата', { exact: true }).check(); await page.getByLabel('Получено, ₽').fill('400'); await pay(page).click(); await expect(page.getByRole('alert')).toContainText('подтвердите')
    await page.getByLabel('Подтверждаю частичную оплату:', { exact: false }).check(); await screenshot(page, info, 'partial-payment'); await pay(page).click()
    await expect(page.locator('.accounting-sale-balances')).toContainText('590,00'); await expect(pay(page)).toContainText('590,00'); await expect(page.locator('.accounting-success')).toContainText('400,00'); await screenshot(page, info, 'partial-result')
    await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await expect(page.locator('.accounting-sales > button')).toHaveCount(3); await screenshot(page, info, 'sales-states')
    await page.getByRole('button', { name: 'Частично', exact: true }).click(); await expect(page.locator('.accounting-sales > button')).toHaveCount(1)
    await page.locator('.accounting-sales > button').click(); await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await expect(page.getByRole('button', { name: 'Частично', exact: true })).toHaveAttribute('aria-pressed', 'true')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}
for (const code of [400, 409, 408, 500, -1]) test(`payment refusal or uncertain outcome ${code} preserves draft and original request`, async ({ page }) => {
  const api = await setup(page, [makeSale('manual:failure')]); await open(page)
  await page.getByLabel('Способ оплаты').selectOption('SPLIT'); await page.getByLabel('Наличные, ₽').fill('400'); await page.getByLabel('Карта, ₽').fill('590'); api.fail(code); await pay(page).click()
  if ([400, 409].includes(code)) { await expect(page.locator('.accounting-submit-error')).toBeVisible(); await expect(page.getByLabel('Наличные, ₽')).toHaveValue('400'); await page.reload(); await expect(page.getByLabel('Карта, ₽')).toHaveValue('590'); await pay(page).click() }
  else { await expect(page.locator('.accounting-pending')).toBeVisible(); await page.reload(); await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click(); expect(api.commands[1]).toEqual(api.commands[0]) }
  await expect(page.getByText('Оплачено полностью', { exact: true })).toBeVisible()
})
test('free canceled unknown and reconciliation remain honest in every sales filter', async ({ page }) => {
  const free = makeSale('manual:free', 0); free.lines[0].giftReason = 'Подарок'
  const unknown = makeSale('manual:unknown', 0); unknown.issue = 'UNKNOWN'
  const canceled = makeSale('manual:canceled'); canceled.cancelReason = 'Отмена'
  const api = await setup(page, [free, unknown, canceled, makeSale('manual:same-a'), makeSale('manual:same-b')]); await page.locator('.accounting-sales > [data-sale-id="manual:free"]').click()
  await expect(page.getByText('Оплата не требуется', { exact: true })).toBeVisible(); await expect(pay(page)).toHaveCount(0)
  await page.getByRole('button', { name: 'К отчёту', exact: true }).click()
  for (const filter of ['Не оплачены', 'Частично', 'Оплачены', 'Все']) { await page.getByRole('button', { name: filter, exact: true }).click(); await expect(page.locator('.accounting-sales > button').filter({ hasText: 'Сумма неизвестна' })).toHaveCount(1) }
  await expect(page.locator('.accounting-sales > button')).toHaveCount(5)
  api.legacyResult(); api.concurrentCreate(); await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11'); await page.getByLabel('Название', { exact: true }).fill('Чай'); await page.getByLabel('Цена за единицу, ₽').fill('990')
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Выбрать продажу для оплаты', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Перейти к записи оплаты', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Выбрать продажу для оплаты', exact: true }).click()
  await expect(page.locator('.accounting-sales > button')).toHaveCount(7)
  await expect(page.locator('.accounting-payment-form')).toHaveCount(0)
})
test('payment PostgreSQL UI POST restart exact replay accounting list current and closed finance DOM', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 }); await page.emulateMedia({ colorScheme: 'dark' })
  const dir = process.env.ACCOUNTING_PAYMENT_LIVE_DIR, api = await setup(page, [], dir)
  for (const price of ['1905', '990']) {
    await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click(); await page.getByLabel('Стол', { exact: true }).selectOption('11')
    await page.getByLabel('Название', { exact: true }).fill('Чай'); await page.getByLabel('Цена за единицу, ₽').fill(price); await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
    await expect(page.getByText('Продажа сохранена.', { exact: false })).toBeVisible()
  }
  await page.getByRole('button', { name: 'Перейти к записи оплаты', exact: true }).click()
  await page.getByLabel('Способ оплаты').selectOption('SPLIT'); await page.getByLabel('Наличные, ₽').fill('400'); await page.getByLabel('Карта, ₽').fill('590'); await pay(page).click()
  if (dir) {
    await expect(page.locator('.accounting-pending')).toBeVisible(); await expect.poll(() => existsSync(join(dir, 'restarted')), { timeout: 30000 }).toBe(true)
    await page.reload(); await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click(); expect(api.commands[3]).toEqual(api.commands[2])
  }
  await expect(page.getByText('Оплачено полностью', { exact: true })).toBeVisible(); await expect(pay(page)).toHaveCount(0); await screenshot(page, info, 'live-paid')
  await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await expect(page.locator('.accounting-sales > button')).toHaveCount(2); await screenshot(page, info, 'live-sales')
  await page.evaluate(() => (window as any).showStats()); await expect(page.locator('#stats-finance [data-kpi=accrued]')).toContainText('2 895,00')
  await expect(page.locator('#stats-finance [data-kpi=sales] .stats-value')).toHaveText('2'); await expect(page.locator('#stats-finance [data-kpi=averageAccruedSale]')).toContainText('1 447,50'); await screenshot(page, info, 'current-overview')
  await page.goto('payment-finalize-test#/accounting?av=1'); await page.getByRole('button', { name: 'Предварительный итог и закрытие' }).click(); await page.getByLabel('Проверил неоплаченные', { exact: false }).check(); await page.getByRole('button', { name: 'Подтвердить закрытие смены', exact: true }).click(); await expect(page.getByText('Сохранённый итог.', { exact: false })).toBeVisible()
  await page.evaluate(() => (window as any).showStats()); await expect(page.locator('#stats-finance')).toContainText('Сейчас открытой смены нет.'); await screenshot(page, info, 'empty-current-shift')
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance'); await page.getByLabel('Финансовый период').selectOption('closed'); await page.getByRole('button', { name: 'Последние 7 дней', exact: true }).click()
  await expect(page.locator('#stats-finance [data-kpi=averageAccruedSale]')).toContainText('1 447,50'); await screenshot(page, info, 'closed-finances')
  if (dir) writeFileSync(join(dir, 'done'), 'PASS')
})


test('review F1 CREATE replay identifies its original sale among identical concurrent sales and payment targets it', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 }); await page.emulateMedia({ colorScheme: 'dark' })
  const dir = process.env.ACCOUNTING_CREATE_LIVE_DIR, api = await setup(page, [], dir)
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click()
  await page.getByLabel('Стол', { exact: true }).selectOption('11'); await page.getByLabel('Название', { exact: true }).fill('Чай'); await page.getByLabel('Цена за единицу, ₽').fill('990')
  api.loseCreate(); await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click()
  await expect(page.locator('.accounting-pending')).toBeVisible()
  const original = dir ? JSON.parse(readFileSync(join(dir, 'committed-create.json'), 'utf8')).createdSaleId : api.state().sales[0].id
  await page.reload(); await expect(page.locator('.accounting-pending')).toBeVisible()
  if (dir) {
    writeFileSync(join(dir, 'reloaded'), 'GET contains original A')
    await expect.poll(() => existsSync(join(dir, 'restarted')), { timeout: 45000 }).toBe(true)
  } else api.addSale(makeSale('manual:other-identical'))
  await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click()
  expect(api.commands[1]).toEqual(api.commands[0])
  await page.getByRole('button', { name: 'Перейти к записи оплаты', exact: true }).click()
  await expect.poll(() => new URLSearchParams(page.url().split('?')[1]).get('sale')).toBe(original)
  await expect(pay(page)).toBeVisible(); await screenshot(page, info, 'review-F1-original-sale')
  await pay(page).click(); await expect(page.getByText('Оплачено полностью', { exact: true })).toBeVisible()
  expect(api.commands.at(-1)).toMatchObject({ action: 'PAY', saleId: original, amountMinor: 99000 })
  if (!dir) expect(salePaid(api.state().sales.find(s => s.id === 'manual:other-identical')!)).toBe(0)
  if (dir) {
    const session = JSON.parse(readFileSync(join(dir, 'ready.json'), 'utf8'))
    const other = JSON.parse(readFileSync(join(dir, 'restarted'), 'utf8')).otherSaleId
    const state = await page.request.get(`${session.base}/api/venue/1/accounting`, { headers: { Authorization: `Bearer ${session.token}` } }); expect(state.status()).toBe(200)
    expect(salePaid((await state.json()).current.sales.find((s: AccountingSale) => s.id === other))).toBe(0)
    await page.evaluate(() => (window as any).showStats()); await expect(page.locator('#stats-finance [data-kpi=operatorConfirmed]')).toContainText('990,00')
    await page.getByLabel('Финансовый период').selectOption('closed'); await page.getByRole('button', { name: 'Произвольный период' }).click()
    await page.getByLabel('Начало периода').fill('2026-11-14'); await page.getByLabel('Окончание периода (включительно)').fill('2026-11-01'); await page.getByRole('button', { name: 'Показать период' }).click()
    await page.getByLabel('Финансовый период').selectOption('current'); await expect(page.locator('#stats-finance [data-kpi=accrued]')).toContainText('1 980,00')
    const extra = { requestId: crypto.randomUUID(), action: 'CREATE', tableId: 11, lines: [{ name: 'Tea', qty: 1, unitPriceMinor: 190500 }] }
    const response = await page.request.post(`${session.base}/api/venue/1/accounting/commands`, { headers: { Authorization: `Bearer ${session.token}` }, data: extra }); expect(response.status()).toBe(200)
    await page.getByRole('button', { name: 'Обновить статистику' }).click(); await expect(page.locator('#stats-finance [data-kpi=accrued]')).toContainText('3 885,00')
    await expect(page.locator('#stats-finance [data-kpi=sales] .stats-value')).toHaveText('3'); await expect(page.locator('#stats-finance [data-kpi=averageAccruedSale]')).toContainText('1 295,00')
    await expect(page.locator('.stats-validation')).toBeHidden(); await screenshot(page, info, 'review-F2-live-current-refresh')
    writeFileSync(join(dir, 'browser-proof.json'), JSON.stringify({ paidSaleId: api.commands.at(-1)?.saleId, originalSaleId: original, otherSaleId: other, exactReplay: JSON.stringify(api.commands[0]) === JSON.stringify(api.commands[1]), currentRefreshAccruedMinor: 388500, source: 'real HTTP and DOM' }))
    writeFileSync(join(dir, 'done'), 'verified UI POST replay PAY and fresh KPI DOM')
  }
})

test('review F3 partial acknowledgment owns and clears only its validation error without submitting', async ({ page }, info) => {
  await page.setViewportSize({ width: 390, height: 844 }); await page.emulateMedia({ colorScheme: 'dark' })
  const api = await setup(page, [makeSale('manual:ack')]); await open(page)
  const partial = page.getByLabel('Частичная оплата', { exact: true }), ack = page.getByLabel('Подтверждаю частичную оплату:', { exact: false })
  await partial.check(); await page.getByLabel('Получено, ₽').fill('400'); await pay(page).click()
  await screenshot(page, info, 'review-F3-before-ack')
  await expect(ack).toHaveAttribute('aria-invalid', 'true'); await expect(ack).toBeFocused(); await expect(partial).toHaveAttribute('aria-invalid', 'false')
  await ack.press('Space'); await expect(ack).toBeChecked(); await expect(ack).toHaveAttribute('aria-invalid', 'false'); await expect(page.locator('.accounting-submit-error')).toBeHidden()
  await expect(ack).toBeFocused(); expect(api.commands).toHaveLength(0); await screenshot(page, info, 'review-F3-after-ack')
  await pay(page).click(); await expect(page.locator('.accounting-sale-balances')).toContainText('590,00'); expect(api.commands).toHaveLength(1)
})

test('review F3 acknowledgment preserves amount and server errors through drafts and payment modes', async ({ page }) => {
  const api = await setup(page, [makeSale('manual:ack-modes')]); await open(page)
  const ack = page.getByLabel('Подтверждаю частичную оплату:', { exact: false }), amount = page.getByLabel('Получено, ₽')
  await page.getByLabel('Частичная оплата', { exact: true }).check(); await amount.fill('1500'); await pay(page).click(); await expect(amount).toHaveAttribute('aria-invalid', 'true')
  if (test.info().project.name === 'webkit') await ack.tap(); else await ack.check()
  await expect(ack).toBeChecked(); await expect(amount).toHaveAttribute('aria-invalid', 'true'); await expect(page.locator('.accounting-submit-error')).toContainText('не превышать'); expect(api.commands).toHaveLength(0)
  await amount.fill('400'); await expect(ack).not.toBeChecked(); await pay(page).click(); await expect(ack).toHaveAttribute('aria-invalid', 'true')
  await ack.check(); await expect(page.locator('.accounting-submit-error')).toBeHidden(); await ack.uncheck(); await pay(page).click(); await expect(ack).toBeFocused(); await ack.press('Space')
  await page.reload(); await expect(ack).toBeChecked(); await expect(amount).toHaveValue('400'); expect(api.commands).toHaveLength(0)
  api.fail(400); await pay(page).click(); await expect(page.locator('.accounting-submit-error')).toContainText('Продажа изменилась')
  await ack.uncheck(); await ack.check(); await expect(page.locator('.accounting-submit-error')).toContainText('Продажа изменилась'); expect(api.commands).toHaveLength(1)
  await page.getByLabel('Способ оплаты').selectOption('SPLIT'); await page.getByLabel('Наличные, ₽').fill('200'); await page.getByLabel('Карта, ₽').fill('200'); await pay(page).click(); await expect(ack).toHaveAttribute('aria-invalid', 'true')
  await ack.check(); await expect(ack).toHaveAttribute('aria-invalid', 'false'); await expect(page.locator('.accounting-submit-error')).toContainText('Продажа изменилась')
  await pay(page).press('Enter'); await expect(page.locator('.accounting-sale-balances')).toContainText('590,00'); expect(api.commands).toHaveLength(2)
  await page.getByLabel('Способ оплаты').selectOption('CARD'); await expect(pay(page)).toContainText('590,00'); await expect(ack).toBeHidden()
})

test('review F1 legacy and missing limited result never substitute a different sale', async ({ page }) => {
  const api = await setup(page, [makeSale('manual:identical')]); api.legacyResult()
  await page.getByRole('button', { name: 'Добавить продажу', exact: true }).click(); await page.getByLabel('Стол', { exact: true }).selectOption('11'); await page.getByLabel('Название', { exact: true }).fill('Чай'); await page.getByLabel('Цена за единицу, ₽').fill('990')
  await page.getByRole('button', { name: 'Сохранить продажу', exact: true }).click(); await expect(page.getByRole('button', { name: 'Выбрать продажу для оплаты', exact: true })).toBeVisible(); await expect(pay(page)).toHaveCount(0)
  await page.evaluate(() => { sessionStorage.setItem(`hookah:accounting:${location.host}:101:1:saved-sale`, JSON.stringify('manual:outside-page')) }); await page.reload()
  await page.getByRole('button', { name: 'Перейти к записи оплаты', exact: true }).click(); await expect(page.getByText('Продажа недоступна в этом отчёте.', { exact: false })).toBeVisible(); await expect(pay(page)).toHaveCount(0)
  expect(api.commands).toHaveLength(1)
  await page.getByRole('button', { name: 'К списку продаж', exact: true }).click(); await expect(page.locator('.accounting-sales > button')).toHaveCount(2)
})

for (const width of [390, 1440]) {
  test(`payment total validation follows every component and submits once ${width}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 }); await page.emulateMedia({ colorScheme: width === 390 ? 'dark' : 'light' })
    const api = await setup(page, [makeSale('manual:total')]); await open(page)
    const cash = page.getByLabel('Наличные, ₽'), card = page.getByLabel('Карта, ₽'), transfer = page.getByLabel('Перевод, ₽')
    const footer = page.locator('.accounting-submit-error'), preview = page.locator('.accounting-payment-preview')
    const partial = page.getByLabel('Частичная оплата', { exact: true }), ack = page.getByLabel('Подтверждаю частичную оплату:', { exact: false })
    await page.getByLabel('Способ оплаты').selectOption('SPLIT'); await cash.fill('400'); await card.fill('900'); await pay(page).click()
    await expect(footer).toContainText('не превышать')
    if (width === 390 && info.project.name === 'chromium') await screenshot(page, info, 'total-overpayment')
    const handle = await card.elementHandle(), form = await page.locator('.accounting-payment-form').elementHandle()
    await card.scrollIntoViewIfNeeded(); await card.focus(); const before = await page.evaluate(() => scrollY)
    await card.fill('590')
    await expect(cash).toHaveAttribute('aria-invalid', 'false'); await expect(footer).toBeHidden()
    await expect(preview).toContainText('990,00'); await expect(pay(page)).toBeEnabled(); await expect(card).toBeFocused()
    expect(await handle!.evaluate(node => node.isConnected)).toBe(true); expect(await form!.evaluate(node => node.isConnected)).toBe(true)
    const position = await page.evaluate(() => ({ y: scrollY, max: Math.max(0, document.documentElement.scrollHeight - innerHeight) }))
    expect(Math.abs(position.y - Math.min(before, position.max))).toBeLessThanOrEqual(2); expect(api.commands).toHaveLength(0)
    if (width === 390 && info.project.name === 'chromium') await screenshot(page, info, 'total-corrected-card')
    const total = page.getByRole('group', { name: 'Итог оплаты', exact: true })
    await expect(total).toHaveAttribute('aria-invalid', 'false')
    // F/C: an already corrected total becomes excessive again and remains excessive at1000.
    await card.fill('900'); await expect(total).toHaveAttribute('aria-invalid', 'true'); await expect(footer).toContainText('не превышать')
    const description = await total.getAttribute('aria-describedby'); expect(description).toBeTruthy()
    for (const control of [cash, card, transfer]) await expect(control).toHaveAttribute('aria-describedby', new RegExp(description!))
    await card.fill('600'); await expect(preview).toContainText('1 000,00'); await expect(pay(page)).toBeDisabled(); await expect(footer).toContainText('не превышать')
    // B: the other component can resolve the same total constraint.
    await card.fill('900'); await cash.fill('90'); await expect(total).toHaveAttribute('aria-invalid', 'false'); await expect(footer).toBeHidden()
    // D: removing overpayment reveals the independent partial-mode/acknowledgment requirement.
    await cash.fill('400'); await card.fill('580'); await expect(footer).not.toContainText('не превышать'); await expect(partial).toHaveAttribute('aria-invalid', 'true'); await pay(page).click(); expect(api.commands).toHaveLength(0)
    await partial.check(); await expect(ack).toHaveAttribute('aria-invalid', 'true'); await ack.press('Space'); await expect(footer).toBeHidden(); await expect(pay(page)).toBeEnabled()
    // G: empty components are omitted; TRANSFER participates in the same calculation.
    await card.fill(''); await transfer.fill('900'); await expect(total).toHaveAttribute('aria-invalid', 'true'); await ack.check(); await expect(total).toHaveAttribute('aria-invalid', 'true')
    await transfer.fill('590'); await expect(footer).toBeHidden(); await expect(pay(page)).toBeEnabled(); expect(api.commands).toHaveLength(0)
    const release = api.hold(); await pay(page).press('Enter'); await expect(pay(page)).toBeDisabled()
    await page.locator('.accounting-payment-form').evaluate(node => node.dispatchEvent(new Event('submit', { cancelable: true }))); release()
    await expect(page.getByText('Оплачено полностью', { exact: true })).toBeVisible()
    expect(api.commands).toHaveLength(1); expect(api.commands[0]).toMatchObject({ action: 'PAY_SPLIT', paymentComponents: [{ method: 'CASH', amountMinor: 40000 }, { method: 'TRANSFER', amountMinor: 59000 }] })
    expect(api.commands[0].acknowledgePartial).toBeUndefined()
  })

  test(`payment total validation preserves independent errors draft and pending ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 })
    const api = await setup(page, [makeSale('manual:total-errors')]); await open(page)
    const method = page.getByLabel('Способ оплаты'), partial = page.getByLabel('Частичная оплата', { exact: true })
    const cash = page.getByLabel('Наличные, ₽'), card = page.getByLabel('Карта, ₽'), transfer = page.getByLabel('Перевод, ₽')
    const ack = page.getByLabel('Подтверждаю частичную оплату:', { exact: false }), footer = page.locator('.accounting-submit-error')
    await method.selectOption('SPLIT'); await partial.check(); await cash.fill('400'); await card.fill('500'); await pay(page).click(); await expect(ack).toHaveAttribute('aria-invalid', 'true')
    await cash.fill('400.123'); await expect(cash).toHaveAttribute('aria-invalid', 'true'); await expect(ack).toHaveAttribute('aria-invalid', 'true')
    await expect(page.locator('.accounting-payment-preview')).toHaveText('Проверьте суммы оплаты.'); await expect(pay(page)).toBeDisabled()
    await ack.check(); await expect(cash).toHaveAttribute('aria-invalid', 'true'); await expect(ack).toHaveAttribute('aria-invalid', 'false'); await expect(footer).toContainText('допустимую сумму')
    await card.fill('590'); await expect(cash).toHaveAttribute('aria-invalid', 'true'); await cash.fill('400'); await expect(footer).toBeHidden()
    await page.reload(); await expect(cash).toHaveValue('400'); await expect(card).toHaveValue('590'); await expect(partial).toBeChecked()
    api.fail(400); await pay(page).click(); await expect(footer).toContainText('Продажа изменилась')
    await transfer.fill('10'); await expect(footer).toContainText('не превышать'); await ack.check(); await expect(footer).toContainText('не превышать'); await expect(footer).toContainText('Продажа изменилась')
    await transfer.fill(''); await expect(footer).not.toContainText('не превышать'); await expect(footer).toContainText('Продажа изменилась'); await expect(pay(page)).toBeEnabled()
    await method.selectOption('CARD'); await expect(footer).toContainText('Продажа изменилась'); await expect(pay(page)).toBeEnabled()
    await partial.uncheck(); await method.selectOption('SPLIT'); await card.fill(''); await expect(footer).toContainText('минимум два'); await expect(pay(page)).toBeDisabled()
    await card.fill('590'); await expect(footer).not.toContainText('минимум два'); await expect(footer).toContainText('Продажа изменилась')
    api.fail(500); await pay(page).click(); await expect(page.locator('.accounting-pending')).toBeVisible(); await expect(page.locator('.accounting-payment-form')).toHaveCount(0)
    expect(api.commands).toHaveLength(2); const pending = api.commands[1]
    await page.reload(); await expect(page.locator('.accounting-pending')).toBeVisible(); await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click()
    await expect(page.getByText('Оплачено полностью', { exact: true })).toBeVisible(); expect(api.commands).toHaveLength(3); expect(api.commands[2]).toEqual(pending)
  })
}
