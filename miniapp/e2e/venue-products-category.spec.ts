import { test, expect, type Page } from '@playwright/test'
import { readFileSync, existsSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import captured from './fixtures/venue-products-category-http.json' with { type: 'json' }
import { midnight, statsResponse } from './fixtures/venue-stats'
import type { VenueKpiSnapshot } from '../src/shared/api/venueStatsApi'

const reports = captured as unknown as Record<string, VenueKpiSnapshot>
const key = reports.root.products!.categoryTotals![0].category.key
const otherKey = reports.root.products!.categoryTotals![1].category.key
const zeroKey = reports.root.products!.categoryTotals![2].category.key
const category = (page: Page, id = key) => page.locator(`[data-category-key="${id}"] > .product-category-toggle`)
async function setup(page: Page, live?: { base: string; token: string; venueId: number }) {
  const requests: URL[] = []; let failure = 0, partial = false, empty = false, currencyUnknown = false, corrupt: ((b: VenueKpiSnapshot) => void) | null = null, hold: Promise<void> | null = null
  await page.addInitScript(token => {
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 7 } }, ready() {}, expand() {}, onEvent() {}, offEvent() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:7`, JSON.stringify({ token, expiresAtEpochSeconds: 4102444800 }))
    const Original = window.AbortController
    ;(window as any).__nativeAbortController = Original
    window.AbortController = class extends Original { abort() {} }
  }, live?.token ?? 'synthetic-products')
  await page.route('**/miniapp/products-category-test*', route => route.fulfill({ contentType: 'text/html', body: `
    <!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"><div id="app"></div><script type="module">
    import {renderVenueStatsScreen} from '/miniapp/src/screens/venueStats.ts';
    import {bindAppTheme} from '/miniapp/src/shared/ui/appTheme.ts';bindAppTheme(document.documentElement);
    let dispose;window.mountProducts=(venueId=Number(new URLSearchParams(location.search).get('venueId')||1),role='OWNER')=>{dispose?.();dispose=renderVenueStatsScreen({root:document.querySelector('#app'),backendUrl:location.origin,isDebug:false,venueId,access:{venueId,venueName:'Категории',role,permissions:[]}})};window.mountProducts();</script></html>` }))
  await page.route('**/api/**', async route => {
    const u = new URL(route.request().url()); if (!u.pathname.startsWith('/api/')) return route.fallback()
    expect(route.request().method()).toBe('GET'); requests.push(u)
    if (live) { const response = await route.fetch({ url: live.base + u.pathname + u.search }); return route.fulfill({ response }) }
    let body: VenueKpiSnapshot
    if (u.searchParams.get('section') !== 'products') body = statsResponse(u, { asOf: reports.root.asOf })
    else {
      const expanded = u.searchParams.get('expandedCategoryKey')
      const selected = currencyUnknown ? u.searchParams.get('search') === 'absent' ? 'unknownEmpty' : u.searchParams.get('categoryKey') === reports.unknownClean?.products?.categoryTotals?.[0]?.category.key ? 'unknownClean' : ({ RUB: 'unknownRub', USD: 'unknownUsd', UNKNOWN: 'unknownOnly' }[u.searchParams.get('currency') ?? ''] ?? 'unknownRoot') : u.searchParams.get('search') === 'Товар 001' ? expanded ? 'mergedCategory' : 'mergedSearch' : partial ? expanded ? 'partialCategory' : 'partial' : empty || u.searchParams.get('search') === 'absent' ? 'empty'
        : u.searchParams.get('search') === '065' ? 'search' : expanded === otherKey ? 'other' : expanded === zeroKey ? 'zero' : expanded ? u.searchParams.get('page') === '2' ? 'pageTwo' : 'pageOne' : 'root'
      body = structuredClone(reports[selected]); body.venueId = Number(u.pathname.split('/')[3]); body.period = { ...body.period, from: midnight(u.searchParams.get('from')!, 'UTC'), to: midnight(u.searchParams.get('to')!, 'UTC') }
    }
    corrupt?.(body)
    const code = failure; if (hold) await hold
    await route.fulfill(code ? { status: code, json: { error: { code: code === 403 ? 'FORBIDDEN' : 'INTERNAL_ERROR' } } } : { json: body }).catch(() => {})
  })
  await page.goto(`products-category-test?venueId=${live?.venueId ?? 1}`)
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('products')
  await expect(page.locator('.product-summary')).toBeVisible()
  return { requests, unknownCurrency() { currencyUnknown = true }, corrupt(f: (b: VenueKpiSnapshot) => void) { corrupt = f }, fail(n: number) { failure = n }, partial() { partial = true }, empty() { partial = false; empty = true }, hold(p: Promise<void> | null) { hold = p } }
}
async function shot(page: Page, path: string) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.locator('.stats-products').evaluate(node => node.scrollIntoView({ block: 'start' }))
  await page.screenshot({ path, fullPage: false })
}
for (const width of [360, 390, 1440]) for (const theme of ['light', 'dark'] as const) {
  test(`product categories compact hierarchy ${width} ${theme}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 844 }); await page.emulateMedia({ colorScheme: theme })
    const api = await setup(page)
    await expect(page.locator('.product-category-toggle[aria-expanded="true"]')).toHaveCount(0)
    await expect(page.locator('.product-item')).toHaveCount(0)
    await expect(page.locator('.product-summary')).toContainText('133')
    await expect(page.locator('.stats-products')).not.toContainText('Неразнесённые единицы: 0')
    await shot(page, info.outputPath(`initial-${width}-${theme}.png`))
    await category(page).focus(); await page.keyboard.press('Enter')
    await expect(category(page)).toHaveAttribute('aria-expanded', 'true')
    await expect(page.locator('.product-item')).toHaveCount(50)
    await expect(category(page)).toContainText('123')
    await expect(category(page)).toBeFocused()
    await shot(page, info.outputPath(`category-${width}-${theme}.png`))
    const first = page.locator('.product-item-toggle').first(); await first.click()
    await expect(first).toHaveAttribute('aria-expanded', 'true')
    await expect(page.locator('.product-detail')).toContainText('Ручные продажи')
    await expect(page.locator('.product-detail')).toContainText('Выданные заказы')
    await expect(page.locator('.product-detail')).toContainText('подарочных: 3')
    await shot(page, info.outputPath(`item-${width}-${theme}.png`))
    await first.click(); await expect(page.locator('.product-detail')).toHaveCount(0)
    const total = await page.locator('.product-summary').innerText()
    await page.getByRole('button', { name: 'Следующие товары', exact: true }).click()
    await expect(page.locator('.product-item')).toHaveCount(12)
    expect(await page.locator('.product-summary').innerText()).toBe(total)
    await expect(page.locator('.product-item').filter({ hasText: 'Пока не заказывали' })).toHaveCount(2)
    await category(page, otherKey).click()
    await expect(page.locator('.product-category-toggle[aria-expanded="true"]')).toHaveCount(1)
    await expect(page.locator('.product-item')).toHaveCount(5)
    expect(await page.locator('.product-summary').innerText()).toBe(total)
    await page.reload(); await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('products')
    await expect(page.locator('.product-item')).toHaveCount(0)
    api.partial(); await page.getByRole('button', { name: 'Обновить статистику', exact: true }).click()
    await expect(page.locator('.product-report-warning')).toBeVisible()
    await expect(page.locator('.product-summary')).toContainText('неполно')
    await expect(page.locator('.product-category-toggle[aria-expanded="true"]')).toHaveCount(0)
    await shot(page, info.outputPath(`partial-${width}-${theme}.png`))
    api.empty(); await page.getByRole('button', { name: 'Обновить статистику', exact: true }).click()
    await expect(page.locator('.product-category')).toHaveCount(0)
    await shot(page, info.outputPath(`empty-${width}-${theme}.png`))
  })
}

test('product category empty error search and retry retain honest scope', async ({ page }) => {
  const api = await setup(page)
  await page.getByText('Поиск и фильтры товаров', { exact: true }).click()
  await page.getByLabel('Поиск товара').fill('065'); await page.getByRole('button', { name: 'Найти товары' }).click()
  await expect(page.locator('.product-summary')).toContainText('2')
  await expect(page.getByText('Итоги и категории относятся к найденным товарам по применённым фильтрам.')).toBeVisible()
  await page.getByLabel('Поиск товара').fill('absent'); await page.getByRole('button', { name: 'Найти товары' }).click()
  await expect(page.locator('.product-category')).toHaveCount(0)
  await expect(page.locator('.product-summary')).toContainText('0')
  await expect(page.getByText('По выбранным условиям товаров нет.', { exact: true })).toBeVisible()
  api.fail(500); await page.getByRole('button', { name: 'Обновить статистику', exact: true }).click()
  await expect(page.getByText('Статистика не загружена', { exact: true })).toBeVisible()
  await expect(page.locator('.product-summary')).toHaveCount(0)
  api.fail(0); await page.getByRole('button', { name: 'Повторить', exact: true }).click()
  await expect(page.locator('.product-summary')).toBeVisible()
  if (!await page.getByLabel('Сортировка товаров').isVisible()) await page.getByText('Поиск и фильтры товаров', { exact: true }).click()
  await page.getByLabel('Сортировка товаров').selectOption('amount'); await page.getByRole('button', { name: 'Найти товары' }).click()
  await expect(page.getByText('Выберите одну валюту для сортировки по стоимости.', { exact: true })).toBeVisible()
})

test('product category stale period account venue and revoked access close private details', async ({ page }) => {
  const api = await setup(page); let release = () => {}; api.hold(new Promise<void>(r => { release = r }))
  const late = page.waitForResponse(r => r.url().includes('expandedCategoryKey='))
  await category(page).click()
  await page.getByRole('button', { name: 'Последние 7 дней', exact: true }).click()
  await expect(page.locator('.product-summary')).toHaveCount(0)
  api.hold(null); await page.getByRole('button', { name: 'Последние 30 дней', exact: true }).click()
  await expect(page.locator('.product-summary')).toBeVisible(); release(); await (await late).finished()
  await expect(page.locator('.product-item')).toHaveCount(0)
  await category(page).click(); await expect(page.locator('.product-item')).toHaveCount(50)
  api.fail(403); await page.getByRole('button', { name: 'Обновить статистику', exact: true }).click()
  await expect(page.getByText('Доступ к статистике потерян', { exact: true })).toBeVisible()
  await expect(page.locator('.product-summary')).toHaveCount(0)
  api.fail(0); await page.evaluate(() => (window as any).mountProducts(2))
  await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('products'); await expect(page.locator('.product-summary')).toBeVisible()
  await page.evaluate(() => { (window as any).Telegram.WebApp.initDataUnsafe.user.id = 8 })
  await expect(page.getByText('Аккаунт изменился', { exact: true })).toBeVisible()
  await expect(page.locator('.product-detail')).toHaveCount(0)
})

test('product categories real PostgreSQL Ktor adapter click through full range', async ({ page }, info) => {
  const directory = process.env.VENUE_PRODUCTS_CATEGORY_LIVE_DIR
  if (!directory) { await setup(page); await category(page).click(); await expect(page.locator('.product-item')).toHaveCount(50); return }
  test.setTimeout(240000)
  await expect.poll(() => existsSync(join(directory, 'ready.json')), { timeout: 90000 }).toBe(true)
  const live = JSON.parse(readFileSync(join(directory, 'ready.json'), 'utf8'))
  try {
    await page.setViewportSize({ width: 390, height: 844 }); await setup(page, live)
    const first = () => category(page, live.categoryKey)
    await expect(page.locator('.product-summary')).toContainText('133')
    await first().click(); await expect(page.locator('.product-item')).toHaveCount(50)
    await page.locator('.product-item-toggle').first().click(); await expect(page.locator('.product-detail')).toContainText('подарочных: 3')
    await page.getByRole('button', { name: 'Следующие товары', exact: true }).click(); await expect(page.locator('.product-item')).toHaveCount(12)
    await expect(page.locator('.product-summary')).toContainText('133')
    await category(page, live.otherCategoryKey).click(); await expect(page.locator('.product-item')).toHaveCount(5)
    await page.getByText('Поиск и фильтры товаров', { exact: true }).click()
    await page.getByLabel('Поиск товара').fill('065'); await page.getByRole('button', { name: 'Найти товары' }).click()
    await expect(page.locator('.product-summary')).toContainText('2')
    await page.screenshot({ path: info.outputPath('real-postgres-search-390.png'), fullPage: true })
    await page.getByLabel('Поиск товара').fill('Товар 001'); await page.getByRole('button', { name: 'Найти товары' }).click()
    await expect(page.locator('.product-summary')).toContainText('5')
    await first().click(); await expect(page.locator('.product-item')).toHaveCount(1)
    await expect(page.locator('.product-item-toggle')).toContainText('5,00 RUB')
    await page.locator('.product-item-toggle').click()
    await expect(page.locator('.product-detail')).toContainText('Ручные продажи')
    await expect(page.locator('.product-detail')).toContainText('Выданные заказы')
    await page.screenshot({ path: info.outputPath('real-merged-search-390.png'), fullPage: true })
    await page.evaluate(id => (window as any).mountProducts(id), live.currencyVenueId)
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('products')
    await expect(page.locator('.product-summary')).toContainText('9')
    await page.getByText('Поиск и фильтры товаров', { exact: true }).click()
    for (const [currency, amount] of [['RUB', '3,00 RUB'], ['USD', '4,00 USD']]) {
      await page.getByLabel('Валюта товаров').selectOption(currency)
      await page.getByRole('button', { name: 'Найти товары' }).click()
      await expect(page.locator('.product-summary')).toContainText(amount)
      await expect(page.locator('.product-currency-warning')).toBeVisible()
    }
    await page.getByLabel('Поиск товара').fill('Исторический')
    await page.getByLabel('Валюта товаров').selectOption('RUB')
    await page.getByLabel('Категория товаров').selectOption(live.currencyCategoryKey)
    await page.getByRole('button', { name: 'Найти товары' }).click()
    await expect(page.locator('.product-summary')).toContainText('3,00 RUB')
    await expect(page.locator('.product-currency-warning')).toBeVisible()
    await category(page, live.currencyCategoryKey).click()
    await expect(page.locator('.product-item-toggle')).toContainText('3,00 RUB')
    await expect(page.locator('.product-item-toggle')).toContainText('неполно')
    await page.screenshot({ path: info.outputPath('real-unknown-rub-390.png'), fullPage: true })

    await page.evaluate(id => (window as any).mountProducts(id), live.partialVenueId)
    await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('products')
    await expect(page.locator('.product-report-warning')).toBeVisible()
    await expect(page.locator('.product-summary')).toContainText('неполно')
  } finally { writeFileSync(join(directory, 'done'), 'browser finished') }
})

test('product category exact large amounts unknown gifts and long names stay compact', async ({ page }, info) => {
  await page.setViewportSize({ width: 360, height: 844 }); const api = await setup(page)
  api.corrupt(body => {
    const p = body.products; if (!p?.summary) return
    const delta = 18446744073709551614n - 200n
    const adjust = (m: { value: string | null; observedValue: string | null }) => {
      m.observedValue = (BigInt(m.observedValue!) + delta).toString(); m.value = m.observedValue
    }
    p.summary.grossAmounts.filter(a => a.currency === 'RUB').forEach(a => adjust(a.gross))
    p.categoryTotals?.filter(g => g.category.key === key).forEach(g => g.totals.grossAmounts.forEach(a => adjust(a.gross)))
    if (p.categoryPage?.key === key) {
      const row = p.categoryPage.rows[0]; adjust(row.gross)
      row.sources[0].snapshotGrossMinor = '18446744073709551614'
      row.sources[0].giftUnits = null; row.sources[0].nonGiftUnits = null
    }
    p.summary.giftUnits = { ...p.summary.giftUnits, value: null, observedValue: '3', availability: 'PARTIAL', coverage: 'PARTIAL', unknown: 1 }
    p.categoryTotals?.filter(g => g.category.key === key).forEach(g => { g.totals.giftUnits = { ...p.summary!.giftUnits } })
  })
  await page.getByRole('button', { name: 'Обновить статистику', exact: true }).click()
  await expect(page.locator('.product-summary')).toContainText('неполно')
  await category(page).click(); await expect(page.locator('.product-item')).toHaveCount(50)
  expect((await page.locator('.product-item-toggle').first().innerText()).replace(/[^0-9,]/g, '')).toContain('184467440737095519,14')
  await page.locator('.product-item-toggle').first().click()
  await expect(page.locator('.product-detail')).toContainText('подарочных: недостаточно данных')
  await shot(page, info.outputPath('large-exact-360.png'))
  await category(page, zeroKey).click(); await expect(page.locator('.product-item')).toHaveCount(1)
  await page.locator('.product-item-toggle').click()
  await expect(page.locator('.product-detail')).toContainText('Очень длинное название услуги')
  await shot(page, info.outputPath('long-name-360.png'))
})

for (const width of [360, 390]) test(`product category invalid sort keeps DOM disclosure and first keyboard click ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 }); const api = await setup(page)
  await category(page).click(); await expect(page.locator('.product-item')).toHaveCount(50)
  await page.locator('.product-item-toggle').first().click()
  await page.getByText('Поиск и фильтры товаров', { exact: true }).click()
  await page.getByLabel('Поиск товара').fill('draft')
  await page.getByLabel('Сортировка товаров').selectOption('amount')
  const requests = api.requests.length
  await page.getByRole('button', { name: 'Найти товары' }).click()
  await expect(page.getByText('Выберите одну валюту для сортировки по стоимости.', { exact: true })).toBeVisible()
  expect(api.requests.length).toBe(requests)
  await expect(page.locator('.product-detail')).toBeVisible()
  await expect(category(page)).toHaveAttribute('aria-expanded', 'true')
  await category(page).focus(); await page.keyboard.press('Enter')
  await expect(category(page)).toHaveAttribute('aria-expanded', 'false')
  await expect(page.locator('.product-item')).toHaveCount(0)
  await expect(category(page)).toBeFocused()
  await expect(page.getByLabel('Поиск товара')).toHaveValue('draft')
  let release = () => {}; api.hold(new Promise<void>(r => { release = r }))
  const late = page.waitForResponse(r => r.url().includes('expandedCategoryKey='))
  await category(page).click(); await expect(category(page)).toHaveAttribute('aria-expanded', 'true')
  await category(page).click(); await expect(category(page)).toHaveAttribute('aria-expanded', 'false')
  api.hold(null); release(); await (await late).finished()
  await expect(category(page)).toHaveAttribute('aria-expanded', 'false')
  await expect(page.locator('.product-item')).toHaveCount(0)
})

for (const width of [360, 390]) for (const failure of [400, 500, 'timeout'] as const) test(`product category failed filter preserves last DOM and recovers ${failure} ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 844 }); const api = await setup(page)
  await category(page).click(); await expect(page.locator('.product-item')).toHaveCount(50)
  await page.locator('.product-item-toggle').first().click()
  await page.getByText('Поиск и фильтры товаров', { exact: true }).click()
  await page.getByLabel('Поиск товара').fill('draft')
  await page.getByLabel('Категория товаров').selectOption(key)
  const old = await page.locator('.product-summary').innerText()
  let release = () => {}
  if (failure === 'timeout') {
    await page.evaluate(() => { window.AbortController = (window as any).__nativeAbortController })
    await page.clock.install(); api.hold(new Promise<void>(r => { release = r }))
  } else api.fail(failure)
  await page.getByRole('button', { name: 'Найти товары' }).click()
  if (failure === 'timeout') await page.clock.fastForward(31000)
  await expect(page.getByText('Статистика не загружена', { exact: true })).toBeVisible()
  await expect(page.locator('.product-summary')).toHaveText(old, { useInnerText: true })
  await expect(page.getByText('Сохранены последние загруженные итоги. Новые фильтры не применены.', { exact: true })).toBeVisible()
  await expect(page.locator('.product-detail')).toBeVisible()
  await expect(page.getByLabel('Поиск товара')).toHaveValue('draft')
  await expect(page.getByLabel('Категория товаров')).toHaveValue(key)
  await category(page).focus(); await page.keyboard.press('Enter')
  await expect(category(page)).toHaveAttribute('aria-expanded', 'false')
  await expect(page.locator('.product-item')).toHaveCount(0)
  api.fail(0); api.hold(null); release()
  await page.getByRole('button', { name: 'Повторить', exact: true }).click()
  await expect(page.locator('.error-card')).toBeHidden()
  await expect(page.locator('.product-summary')).toBeVisible()
  await category(page).click(); await expect(page.locator('.product-item')).toHaveCount(50)
  await expect(category(page)).toHaveAttribute('aria-expanded', 'true')
})

test('product merged search and scoped unknown currencies retain honest HTTP totals', async ({ page }) => {
  const api = await setup(page)
  await page.getByText('Поиск и фильтры товаров', { exact: true }).click()
  await page.getByLabel('Поиск товара').fill('Товар 001'); await page.getByRole('button', { name: 'Найти товары' }).click()
  await expect(page.locator('.product-summary')).toContainText('5,00 RUB')
  await category(page).click(); await expect(page.locator('.product-item')).toHaveCount(1)
  await page.locator('.product-item-toggle').click()
  await expect(page.locator('.product-detail')).toContainText('Ручные продажи')
  await expect(page.locator('.product-detail')).toContainText('Выданные заказы')
  await category(page).click(); await expect(category(page)).toHaveAttribute('aria-expanded', 'false')
  api.unknownCurrency(); await page.getByLabel('Поиск товара').fill('')
  for (const [currency, amount] of [['RUB', '3,00 RUB'], ['USD', '4,00 USD'], ['UNKNOWN', '—'], ['', '3,00 RUB']]) {
    await page.getByLabel('Валюта товаров').selectOption(currency)
    await page.getByRole('button', { name: 'Найти товары' }).click()
    await expect(page.locator('.product-summary')).toContainText(amount)
    await expect(page.locator('.product-report-warning')).toBeVisible()
    if (currency === 'RUB' || currency === 'USD') await expect(page.locator('.product-currency-warning')).toHaveText('Есть операции с неизвестной валютой. Полнота суммы не подтверждена.')
  }
  await page.getByLabel('Валюта товаров').selectOption('RUB'); await page.getByLabel('Поиск товара').fill('absent')
  await page.getByRole('button', { name: 'Найти товары' }).click()
  await expect(page.locator('.product-currency-warning')).toHaveCount(0)
  await expect(page.locator('.product-summary')).toContainText('0,00 RUB')
})
