import { test, expect, type Page } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { statsResponse, midnight } from './fixtures/venue-stats'
import type { AccountingCommand, AccountingReport, AccountingSale } from '../src/shared/api/accounting'

const categories = Array.from({ length: 3 }, (_, c) => ({ id: c + 1, name: ['Напитки', 'Еда', 'Другое'][c], isActive: true, items: Array.from({ length: 50 }, (_, i) => ({ id: 1000 + c * 50 + i, categoryId: c + 1, name: i === 0 ? 'Одинаковый товар' : `Товар ${c * 50 + i}`, priceMinor: 150000, currency: 'RUB', isAvailable: true, options: [] })) }))
const empty = (): AccountingReport => ({ shiftId: 1, venueId: 1, status: 'OPEN', timezone: 'UTC', openedAt: new Date().toISOString(), closedAt: null, currency: 'RUB', sales: [], totals: { salesCount: 0, ordersCount: 0, grossMinor: 0, discountMinor: 0, giftMinor: 0, accruedMinor: 0, paidMinor: 0, unpaidMinor: 0, cashMinor: 0, cardMinor: 0, transferMinor: 0 }, byTable: [], byItem: [], bySource: [], unpaidSaleIds: [], disputedSaleIds: [], reportHash: 'synthetic-empty' })
async function setup(page: Page, live?: { base: string; token: string; venueId: number }) {
  const commands: AccountingCommand[] = []; let report = empty(), failure = 0, priceConflict = false, held: Promise<void> | null = null
  await page.addInitScript(token => {
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 101 } }, ready() {}, expand() {}, onEvent() {}, offEvent() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:101`, JSON.stringify({ token, expiresAtEpochSeconds: 4102444800 }))
  }, live?.token ?? 'synthetic-percent')
  await page.route('**/miniapp/accounting-percent-test*', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"></head><body><div id="app"></div><script type="module">
    import {renderVenueAccountingScreen} from '/miniapp/src/screens/venueAccounting.ts';
    import {renderVenueStatsScreen} from '/miniapp/src/screens/venueStats.ts';
    import {bindAppTheme} from '/miniapp/src/shared/ui/appTheme.ts'; bindAppTheme(document.documentElement);
    let dispose;const access={venueId:1,venueName:'Тестовое заведение',venueStatus:'PUBLISHED',role:'OWNER',permissions:['ORDER_QUEUE_VIEW','MENU_VIEW','TABLE_VIEW']};
    function mount(){dispose?.();dispose=renderVenueAccountingScreen({root:document.querySelector('#app'),backendUrl:location.origin,isDebug:false,venueId:1,access,userId:101})};window.addEventListener('hashchange',mount);mount();
    window.showProducts=()=>{dispose?.();dispose=renderVenueStatsScreen({root:document.querySelector('#app'),backendUrl:location.origin,isDebug:false,venueId:1,access})};
    </script></body></html>` }))
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    if (live) { expect(req.method()).toBe('GET'); return route.fulfill({ response: await route.fetch({ url: live.base + url.pathname + url.search }) }) }
    if (url.pathname === '/api/venue/menu') return route.fulfill({ json: { venueId: 1, categories: [...categories, { id: 4, name: 'Скрытая', isActive: false, items: [{ ...categories[0].items[0], id: 4000 }] }, { id: 5, name: 'Валюты', items: [{ ...categories[0].items[0], id: 5000, currency: 'EUR' }, { ...categories[0].items[0], id: 5001, isAvailable: false }] }] } })
    if (url.pathname === '/api/venue/tables') return route.fulfill({ json: { tables: [{ tableId: 11, tableLabel: 'Стол 1', tableNumber: 1, isActive: true }] } })
    if (req.method() === 'POST') {
      const body = req.postDataJSON() as AccountingCommand; commands.push(body)
      if (held) { const p = held; held = null; await p }
      if (priceConflict) { priceConflict = false; return route.fulfill({ status: 409, json: { error: { code: 'ACCOUNTING_CONFLICT', message: 'Цена меню изменилась. Проверьте новый итог перед сохранением.', details: { field: 'price', lineIndex: 0, currentUnitPriceMinor: 160000 } } } }) }
      if (failure) { const status = failure; failure = 0; if (status === -1) return route.abort('failed'); return route.fulfill({ status, json: { error: { code: status === 409 ? 'ACCOUNTING_CONFLICT' : 'INVALID_INPUT', message: 'Проверьте причину скидки.', details: { field: 'reason' } } } }) }
      report = { ...empty(), sales: body.lines!.map((line, index) => ({ id: `manual:${body.requestId}-${index}`, tableId: 11, tableLabel: 'Стол 1', source: 'MANUAL', orderId: null, displayNumber: null, orderClosed: true, createdAt: new Date().toISOString(), currency: 'RUB', version: 1, cancelReason: null, disputeReason: null, originalPayableMinor: null, issue: null, payments: [], lines: [{ ...line, id: `line-${index}`, name: line.name!, menuItemId: line.menuItemId ?? null, unitPriceMinor: line.unitPriceMinor!, source: 'MANUAL', tabId: null, baseDiscountMinor: 0, baseGiftMinor: 0, giftReason: null, discountMinor: line.discountPercent ? Math.round(line.qty * line.unitPriceMinor! * Number(line.discountPercent) / 100) : line.discountMinor ?? 0 }] })) as AccountingSale[] }
      return route.fulfill({ json: report })
    }
    if (/\/accounting/.test(url.pathname)) return route.fulfill({ json: { current: report, history: [] } })
    return route.fulfill({ json: {} })
  })
  await page.goto('accounting-percent-test#/accounting?av=1&view=new')
  await expect(page.getByRole('heading', { name: 'Добавить продажу по столу' })).toBeVisible()
  return { commands, fail(status: number) { failure = status }, reprice() { priceConflict = true }, hold() { let release!: () => void; held = new Promise<void>(resolve => { release = resolve }); return () => release() } }
}
const rows = (page: Page) => page.locator('.accounting-line')
async function choose(page: Page) {
  await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Категория меню').selectOption('1')
  await page.getByLabel('Товар', { exact: true }).selectOption('1000')
  await page.getByLabel('Количество', { exact: true }).fill('2')
}
const save = (page: Page) => page.getByRole('button', { name: 'Сохранить продажу', exact: true })

for (const width of [360, 390, 1440]) for (const theme of ['light', 'dark'] as const) {
  test(`manual percent accessible menu validation and compact draft ${width} ${theme}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 }); await page.emulateMedia({ colorScheme: theme })
    const api = await setup(page)
    await expect(page.getByText('Для продаж вне приложения.', { exact: false })).toBeVisible()
    await choose(page)
    const percent = page.getByLabel('Скидка, %', { exact: true }); await expect(percent).toHaveValue(''); await expect(percent).toHaveAttribute('placeholder', '0')
    await percent.pressSequentially('10'); await expect(percent).toHaveValue('10')
    await expect(page.locator('.accounting-line-preview')).toContainText('300,00')
    await expect(page.locator('.accounting-sale-total')).toContainText('2 700,00')
    await save(page).click()
    const reason = page.getByLabel('Причина скидки · обязательно'); await expect(reason).toBeFocused(); await expect(reason).toHaveAttribute('aria-invalid', 'true')
    const errorId = await reason.getAttribute('aria-describedby'); await expect(page.locator(`#${errorId}`)).toContainText('Укажите причину скидки')
    await expect(page.locator('.accounting-submit-error')).toBeVisible(); expect(api.commands).toHaveLength(0)
    const contrast = await page.locator(`#${errorId}`).evaluate(node => {
      const colors = [getComputedStyle(node).color, getComputedStyle(node.closest('.accounting-editor')!).backgroundColor]
      const values = colors.map(color => {
        const [r, g, b] = color.match(/[\d.]+/g)!.slice(0, 3).map(Number).map(value => value / 255).map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4)
        return r * .2126 + g * .7152 + b * .0722
      })
      return (Math.max(...values) + .05) / (Math.min(...values) + .05)
    })
    expect(contrast).toBeGreaterThanOrEqual(4.5)
    await page.screenshot({ path: info.outputPath(`inline-error-${width}-${theme}.png`), fullPage: true })
    await reason.fill('Постоянный гость'); await expect(reason).toHaveAttribute('aria-invalid', 'false'); await expect(page.locator('.accounting-submit-error')).toBeHidden()
    await page.getByRole('button', { name: 'Ещё позиция', exact: true }).click()
    await expect(rows(page)).toHaveCount(2); await expect(rows(page).locator('[open]')).toHaveCount(0)
    expect(await rows(page).evaluateAll(nodes => nodes.filter(node => (node as HTMLDetailsElement).open).length)).toBe(1)
    await page.getByLabel('Поиск товара по всему меню').last().fill('Товар 149')
    await expect(page.getByLabel('Товар', { exact: true }).last().locator('option')).toHaveCount(2)
    await page.getByLabel('Товар', { exact: true }).last().focus(); await page.getByLabel('Товар', { exact: true }).last().selectOption('1149')
    await expect(page.getByLabel('Товар', { exact: true }).last()).toBeFocused()
    await page.getByLabel('Категория меню').last().selectOption('2')
    await expect(page.getByLabel('Название', { exact: true }).last()).toHaveValue('Товар 149')
    await expect(page.getByLabel('Товар', { exact: true }).last()).toHaveValue('')
    await page.screenshot({ path: info.outputPath(`form-${width}-${theme}.png`), fullPage: true })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await save(page).click()
    await expect(page.getByText('Продажа сохранена. Полученные деньги запишите отдельной оплатой.')).toBeVisible()
    expect(api.commands).toHaveLength(1)
    expect(api.commands[0].lines).toMatchObject([{ menuItemId: 1000, qty: 2, unitPriceMinor: 150000, discountPercent: '10.00' }, { menuItemId: 1149, qty: 1, discountPercent: '0.00' }])
    expect(api.commands[0].lines![0].discountMinor).toBeUndefined()
  })
}

test('manual percent global search preserves stable IDs and excludes hidden foreign currency unavailable items', async ({ page }) => {
  await setup(page)
  const item = page.getByLabel('Товар', { exact: true })
  await expect(item.locator('option')).toHaveCount(1)
  await page.getByLabel('Категория меню').selectOption('1'); await expect(item.locator('option')).toHaveCount(51)
  await page.getByLabel('Поиск товара по всему меню').fill('Одинаковый товар')
  await expect(item.locator('option')).toHaveCount(4)
  await expect(item).toContainText('Напитки'); await expect(item).toContainText('Еда'); await expect(item).toContainText('Другое')
  await item.selectOption('1050'); await page.getByLabel('Категория меню').selectOption('3')
  await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Одинаковый товар')
  await expect(page.getByLabel('Цена за единицу, ₽')).toHaveValue('1500.00')
  await page.reload(); await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Одинаковый товар')
  const draft = await page.evaluate(() => Object.keys(sessionStorage).filter(k => k.endsWith(':new')).map(k => JSON.parse(sessionStorage.getItem(k)!)))
  expect(draft[0].lines[0].menuItemId).toBe(1050)
  await page.getByRole('button', { name: 'Позиция не из меню', exact: true }).click()
  await expect(page.getByLabel('Название', { exact: true })).toBeEnabled()
})

test('manual percent exact line rounding comma bounds gift and explicit mode reset preserve input', async ({ page }) => {
  const api = await setup(page); await page.getByLabel('Стол', { exact: true }).selectOption('11')
  await page.getByLabel('Название', { exact: true }).fill('Копейки'); await page.getByLabel('Цена за единицу, ₽').fill('0,01'); await page.getByLabel('Количество', { exact: true }).fill('3')
  const percent = page.getByLabel('Скидка, %', { exact: true })
  await percent.fill('50'); await expect(page.locator('.accounting-line-preview')).toContainText('0,02'); await expect(page.locator('.accounting-sale-total')).toContainText('0,01')
  for (const invalid of ['-1', '100.01', '1.234', 'NaN']) {
    await percent.fill(invalid); await save(page).click(); await expect(percent).toHaveAttribute('aria-invalid', 'true'); await expect(percent).toBeFocused()
  }
  await percent.fill('12,5'); await expect(page.getByLabel('Причина скидки · обязательно')).toBeVisible()
  await page.getByLabel('Режим скидки').focus(); await page.getByLabel('Режим скидки').selectOption('amount'); await expect(page.getByLabel('Скидка на всю позицию, ₽')).toHaveValue('')
  await expect(page.getByText('Режим изменён.', { exact: false })).toBeVisible(); await expect(page.getByLabel('Режим скидки')).toBeFocused()
  await page.getByLabel('Скидка на всю позицию, ₽').fill('0.01'); await page.getByLabel('Режим скидки').selectOption('percent')
  await percent.fill('100'); await page.getByLabel('Подарок / бесплатно').check(); await page.getByLabel('Причина подарка').fill('Отдельная причина')
  await save(page).click(); await expect(percent).toHaveAttribute('aria-invalid', 'true'); expect(api.commands).toHaveLength(0)
  await page.getByLabel('Подарок / бесплатно').uncheck(); await page.getByLabel('Причина скидки · обязательно').fill('Полная скидка')
  await save(page).click(); await expect.poll(() => api.commands.length).toBe(1)
  expect(api.commands[0].lines![0]).toMatchObject({ discountPercent: '100.00', giftReason: null })
})

for (const status of [400, 409, 500, 408, -1]) test(`manual percent failed submit ${status} keeps draft focus and exact pending payload`, async ({ page }) => {
  const api = await setup(page); await choose(page); await page.getByLabel('Скидка, %', { exact: true }).fill('10'); await page.getByLabel('Причина скидки · обязательно').fill('Причина')
  api.fail(status); await save(page).click()
  if ([400,409].includes(status)) {
    await expect(page.getByLabel('Причина скидки · обязательно')).toBeFocused(); await expect(page.getByLabel('Количество', { exact: true })).toHaveValue('2')
    await expect(page.locator('.accounting-submit-error')).toBeVisible(); await save(page).click(); await expect.poll(() => api.commands.length).toBe(2)
    expect(api.commands[0].requestId).not.toBe(api.commands[1].requestId)
  } else {
    await expect(page.getByText('Продажа могла сохраниться.', { exact: false })).toBeVisible()
    await page.reload(); await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click(); await expect.poll(() => api.commands.length).toBe(2)
    expect(api.commands[1]).toEqual(api.commands[0])
  }
  await expect(page.getByText('Продажа сохранена.', { exact: false })).toBeVisible()
})

test('manual percent price conflict requires explicit recalculation and double click creates one command', async ({ page }) => {
  const api = await setup(page); await choose(page); await page.getByLabel('Скидка, %', { exact: true }).fill('10'); await page.getByLabel('Причина скидки · обязательно').fill('Причина')
  api.reprice(); await save(page).click(); await expect(page.getByLabel('Цена за единицу, ₽')).toHaveValue('1500.00')
  const priceError = await page.getByLabel('Цена за единицу, ₽').getAttribute('aria-describedby'); await expect(page.locator(`#${priceError}`)).toBeFocused(); await expect(page.locator(`#${priceError}`)).toBeInViewport()
  await page.getByRole('button', { name: /Применить цену/ }).click(); await expect(page.getByLabel('Цена за единицу, ₽')).toHaveValue('1600.00'); await expect(page.locator('.accounting-sale-total')).toContainText('2 880,00')
  const release = api.hold(); await save(page).click(); await expect(save(page)).toBeDisabled(); await expect(page.getByText('Сохраняем продажу…')).toBeVisible()
  await page.locator('form').evaluate(form => { form.dispatchEvent(new Event('submit', { cancelable: true })); form.dispatchEvent(new Event('submit', { cancelable: true })) })
  expect(api.commands).toHaveLength(2); release(); await expect(page.getByText('Продажа сохранена.', { exact: false })).toBeVisible()
})

test('manual percent account switch closes private form without late success', async ({ page }) => {
  const api = await setup(page); await choose(page); const release = api.hold(); await save(page).click()
  await page.evaluate(() => { (window as any).Telegram.WebApp.initDataUnsafe.user.id = 999 })
  await expect(page.getByText('Доступ изменился.', { exact: false })).toBeVisible(); release()
  await expect(page.locator('.accounting-editor')).toHaveCount(0); await expect(page.getByText('Продажа сохранена.', { exact: false })).toHaveCount(0)
  expect(api.commands).toHaveLength(1)
})

for (const status of [401, 403]) test(`manual percent revoked access ${status} closes private form and preserves pending safety`, async ({ page }) => {
  const api = await setup(page); await choose(page); api.fail(status); await save(page).click()
  await expect(page.getByText('Доступ изменился.', { exact: false })).toBeVisible()
  await expect(page.locator('.accounting-editor')).toHaveCount(0); expect(api.commands).toHaveLength(1)
})

test('manual percent required table name quantity and price errors focus the first field without losing draft', async ({ page }) => {
  const api = await setup(page); await save(page).click()
  await expect(page.getByLabel('Стол', { exact: true })).toBeFocused()
  await expect(page.getByLabel('Название', { exact: true })).toHaveAttribute('aria-invalid', 'true')
  await expect(page.getByLabel('Цена за единицу, ₽')).toHaveAttribute('aria-invalid', 'true')
  await page.getByLabel('Стол', { exact: true }).selectOption('11'); await page.getByLabel('Название', { exact: true }).fill('Своя позиция')
  await page.getByLabel('Количество', { exact: true }).fill(''); await save(page).click()
  await expect(page.getByLabel('Количество', { exact: true })).toBeFocused(); await expect(page.getByLabel('Название', { exact: true })).toHaveValue('Своя позиция')
  await page.getByLabel('Количество', { exact: true }).fill('1'); await page.getByLabel('Цена за единицу, ₽').fill('0,5'); await save(page).click()
  await expect.poll(() => api.commands.length).toBe(1); expect(api.commands[0].lines![0]).toMatchObject({ unitPriceMinor: 50, discountPercent: '0.00' })
})

test('manual accounting mixed text source retains known totals warns and blocks settlement', async ({ page }) => {
  await setup(page)
  const captured = JSON.parse(readFileSync(join(process.cwd(), 'e2e/fixtures/accounting-percent-http.json'), 'utf8'))
  const state = structuredClone(captured.digital)
  const sale = state.current.sales[0]
  sale.sourceWarning = 'Есть текстовая часть без товарного состава и цен. Учтены известные строки; нужна сверка исходного заказа.'
  state.current.disputedSaleIds = [sale.id]
  await page.route('**/api/venue/1/accounting**', route => { expect(route.request().method()).toBe('GET'); return route.fulfill({ json: state }) })
  await page.goto('accounting-percent-test#/accounting?av=1')
  await expect(page.getByText('Итоги содержат известную часть', { exact: false })).toBeVisible()
  await expect(page.locator('.accounting-shift-totals > .accounting-totals')).toContainText('2 700')
  await page.getByRole('button', { name: /Стол.*Mini App/ }).click()
  await expect(page.getByText('Есть текстовая часть без товарного состава', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Исправить продажу', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Подтвердить внешнюю оплату', exact: true })).toHaveCount(0)
  await page.goto(`accounting-percent-test#/accounting?av=1&sale=${encodeURIComponent(sale.id)}&view=edit`)
  await expect(page.locator('.accounting-editor')).toHaveCount(0)
})

test('real Bot Mini App PostgreSQL HTTP accounting and product adapters render discounts without double counting', async ({ page }, info) => {
  const liveDir = process.env.ACCOUNTING_PERCENT_LIVE_DIR
  const live = liveDir ? JSON.parse(readFileSync(join(liveDir, 'ready.json'), 'utf8')) : undefined
  const captured = JSON.parse(readFileSync(join(process.cwd(), 'e2e/fixtures/accounting-percent-http.json'), 'utf8'))
  await setup(page, live)
  if (!live) await page.route('**/api/venue/1/**', route => {
    const url = new URL(route.request().url()); expect(route.request().method()).toBe('GET')
    if (!url.pathname.includes('/stats/')) return route.fulfill({ json: captured.manual })
    if (url.searchParams.get('section') !== 'products') return route.fulfill({ json: statsResponse(url) })
    const body = structuredClone(url.searchParams.has('expandedCategoryKey') ? captured.productCategory : captured.products)
    body.period = { ...body.period, from: midnight(url.searchParams.get('from')!, 'UTC'), to: midnight(url.searchParams.get('to')!, 'UTC') }
    return route.fulfill({ json: body })
  })
  await page.goto('accounting-percent-test#/accounting?av=1')
  await expect(page.locator('.accounting-shift-totals > .accounting-totals')).toContainText('5 400'); await page.locator('.accounting-shift-breakdown > summary').click(); await expect(page.locator('.accounting-shift-breakdown > .accounting-totals')).toContainText('600')
  await expect(page.getByText('2 записей', { exact: false })).toHaveCount(0)
  await expect(page.getByText('1 записей без достоверного снимка', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: /Стол.*Вручную/ }).click(); await page.getByRole('button', { name: 'Исправить продажу', exact: true }).click()
  await expect(page.getByLabel('Скидка, %', { exact: true })).toHaveValue('10')
  await expect(page.getByLabel('Причина исправления · обязательно')).toBeVisible()
  await page.screenshot({ path: info.outputPath('real-pg-accounting.png'), fullPage: true })
  await page.evaluate(() => (window as any).showProducts())
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('products')
  await expect(page.locator('.product-summary')).toContainText('4')
  await page.locator('.product-category-toggle').first().click(); await page.locator('.product-item-toggle').first().click()
  await expect(page.locator('.product-detail')).toContainText('Ручные продажи'); await expect(page.locator('.product-detail')).toContainText('Выданные заказы')
  await expect(page.locator('.product-summary')).toContainText('6 000')
  await page.screenshot({ path: info.outputPath('real-pg-products.png'), fullPage: true })
  if (liveDir) writeFileSync(join(liveDir, 'done'), 'PASS')
})
