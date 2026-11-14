import { test, expect, type Page } from '@playwright/test'
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { statsResponse, midnight } from './fixtures/venue-stats'
import type { AccountingCommand } from '../src/shared/api/accounting'

const capture = () => JSON.parse(readFileSync(join(process.cwd(), 'e2e/fixtures/accounting-chat-reconciliation-http.json'), 'utf8'))
async function setup(page: Page, role = 'OWNER', liveDir?: string) {
  page.on('pageerror', error => { throw error })
  const captured = capture(), commands: AccountingCommand[] = []; let state = structuredClone(captured.initial), failure = 0, held: Promise<void> | null = null
  const live = () => liveDir ? JSON.parse(readFileSync(join(liveDir, 'ready.json'), 'utf8')) : undefined
  const token = live()?.token ?? 'synthetic-chat-reconciliation'
  await page.addInitScript(token => {
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 101 } }, ready() {}, expand() {}, onEvent() {}, offEvent() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:101`, JSON.stringify({ token, expiresAtEpochSeconds: 4102444800 }))
  }, token)
  await page.route('**/miniapp/accounting-chat-test*', route => route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"><div id="app"></div><script type="module">
    import {renderVenueAccountingScreen} from '/miniapp/src/screens/venueAccounting.ts';import {renderVenueStatsScreen} from '/miniapp/src/screens/venueStats.ts';import {bindAppTheme} from '/miniapp/src/shared/ui/appTheme.ts';bindAppTheme(document.documentElement);
    let dispose,venueId=1;const access={venueId:1,venueName:'Тестовая площадка',venueStatus:'PUBLISHED',role:'${role}',permissions:['ORDER_QUEUE_VIEW','MENU_VIEW','TABLE_VIEW']};
    const mount=()=>{dispose?.();dispose=renderVenueAccountingScreen({root:document.querySelector('#app'),backendUrl:location.origin,isDebug:false,venueId,access:{...access,venueId},userId:101})};window.addEventListener('hashchange',mount);window.switchAccountingVenue=(id)=>{venueId=id;mount()};mount();
    window.showFinance=()=>{dispose?.();dispose=renderVenueStatsScreen({root:document.querySelector('#app'),backendUrl:location.origin,isDebug:false,venueId,access})};</script></html>` }))
  let abortCommitted = !!liveDir
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()); if (!url.pathname.startsWith('/api/')) return route.fallback()
    if (liveDir) {
      const response = await route.fetch({ url: live()!.base + url.pathname + url.search })
      if (req.method() === 'POST') {
        const body = req.postDataJSON() as AccountingCommand; commands.push(body)
        if (body.action === 'RECONCILE_TEXT' && abortCommitted) {
          expect(response.status()).toBe(200); const report = await response.json(); expect(report.totals.accruedMinor).toBe(75000)
          writeFileSync(join(liveDir, 'reconciled.json'), JSON.stringify({ current: report, history: [], nextHistoryBeforeId: null }))
          abortCommitted = false; await route.abort('failed'); writeFileSync(join(liveDir, 'restart'), 'committed-response-lost'); return
        }
      }
      return route.fulfill({ response })
    }
    if (url.pathname === '/api/venue/menu') return route.fulfill({ json: { venueId: 1, categories: [] } })
    if (url.pathname === '/api/venue/tables') return route.fulfill({ json: { tables: [{ tableId: 11, tableNumber: 1, tableLabel: 'Стол 1', isActive: true }] } })
    if (/\/stats\//.test(url.pathname)) {
      if (url.searchParams.get('section') !== 'finance') return route.fulfill({ json: statsResponse(url) })
      const body = structuredClone(captured.finance); body.period = { ...body.period, from: midnight(url.searchParams.get('from')!, 'UTC'), to: midnight(url.searchParams.get('to')!, 'UTC') }; return route.fulfill({ json: body })
    }
    if (/\/venue\/2\/accounting/.test(url.pathname)) return route.fulfill({ json: { current: null, history: [] } })
    if (req.method() === 'POST') {
      const body = req.postDataJSON() as AccountingCommand; commands.push(body)
      if (held) { const p = held; held = null; await p }
      if (failure) { const status = failure; failure = 0; if (status === -1) return route.abort('failed'); return route.fulfill({ status, json: { error: { code: status === 409 ? 'ACCOUNTING_CONFLICT' : 'INVALID_INPUT', message: 'Проверьте основание сверки.', details: { field: 'confirmationSource' } } } }) }
      if (body.action === 'RECONCILE_TEXT') {
        expect(body).toMatchObject({ shiftId: 1, saleId: 'order:1', expectedVersion: state.current.sales[0].version, amountMinor: 75000, confirmationSource: 'Кассовый документ №17', reason: 'Подтверждено по документу' })
        state = structuredClone(captured.reconciled)
      } else if (body.action === 'CLOSE') { expect(body.acknowledgeUnpaid).toBe(true); state = { current: structuredClone(captured.closed), history: [] } }
      else throw new Error(`Unexpected financial action: ${body.action}`)
      return route.fulfill({ json: state.current })
    }
    if (/\/accounting/.test(url.pathname)) return route.fulfill({ json: /\/accounting\/\d+$/.test(url.pathname) ? state.current : state })
    return route.fulfill({ json: {} })
  })
  await page.goto('accounting-chat-test#/accounting?av=1&shift=1&sale=order:1')
  await expect(page.getByRole('heading', { name: /Стол 1/ })).toBeVisible()
  return { commands, fail(status: number) { failure = status }, hold() { let release!: () => void; held = new Promise<void>(r => { release = r }); return release }, conflictState() { state = structuredClone(captured.reconciled) } }
}
const submit = (page: Page) => page.getByRole('button', { name: 'Записать сверку', exact: true })
async function form(page: Page) { await page.getByRole('button', { name: 'Сверить заказ', exact: true }).click(); await expect(submit(page)).toBeVisible() }
async function fill(page: Page) {
  await page.getByLabel('Подтверждённый итог, ₽').fill('750,00')
  await page.getByLabel('Источник подтверждения · обязательно').fill('Кассовый документ №17')
  await page.getByLabel('Причина сверки · обязательно').fill('Подтверждено по документу')
}
for (const width of [360, 390, 1440]) for (const theme of ['light', 'dark'] as const) {
  test(`closed text order reconciliation validation success and honest totals ${width} ${theme}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 }); await page.emulateMedia({ colorScheme: theme })
    const api = await setup(page)
    await expect(page.getByText('Текстовый заказ требует сверки.', { exact: false })).toBeVisible()
    await expect(page.getByText('Исходная расчётная сумма не подтверждена.', { exact: false })).toBeVisible()
    await form(page); await expect(page.getByLabel('Подтверждённый итог, ₽')).toHaveValue(''); await page.getByLabel('Подтверждённый итог, ₽').focus(); await page.keyboard.press('Tab'); await expect(page.getByLabel('Источник подтверждения · обязательно')).toBeFocused(); await submit(page).click()
    await expect(page.getByLabel('Подтверждённый итог, ₽')).toBeFocused(); await expect(page.getByLabel('Подтверждённый итог, ₽')).toHaveAttribute('aria-invalid', 'true')
    await expect(page.locator('.accounting-submit-error')).toBeVisible(); expect(api.commands).toHaveLength(0)
    await page.screenshot({ path: info.outputPath(`reconciliation-error-${width}-${theme}.png`), fullPage: true })
    await page.getByLabel('Подтверждённый итог, ₽').fill('750'); await submit(page).click()
    await expect(page.getByLabel('Источник подтверждения · обязательно')).toBeFocused()
    await fill(page); await page.reload(); await expect(page.getByLabel('Подтверждённый итог, ₽')).toHaveValue('750,00')
    await page.screenshot({ path: info.outputPath(`reconciliation-form-${width}-${theme}.png`), fullPage: true })
    await submit(page).click(); await expect(page.getByText('Сверка записана. Полученные деньги', { exact: false })).toBeVisible()
    await expect(page.getByText('Сверка записана: подтверждённый неразнесённый итог', { exact: false })).toContainText('750')
    expect(api.commands).toHaveLength(1); expect(api.commands[0].action).toBe('RECONCILE_TEXT')
    await expect(page.getByText(/Начислено.*Оплачено.*0.*Остаток/)).toBeVisible()
    await page.getByRole('button', { name: 'К отчёту', exact: true }).click()
    await expect(page.locator('.accounting-totals')).toContainText('Сверено без товарной расшифровки')
    await expect(page.getByText('Начисления включают подтверждённые неразнесённые итоги.', { exact: false })).toBeVisible()
    await page.getByRole('button', { name: 'Предварительный итог и закрытие' }).click()
    await page.getByLabel('Проверил неоплаченные и спорные записи; принимаю закрытие с этим остатком').check()
    await page.getByRole('button', { name: 'Подтвердить закрытие смены', exact: true }).click()
    await expect(page.getByText('Сохранённый итог.', { exact: false })).toBeVisible()
    expect(api.commands.map(c => c.action)).toEqual(['RECONCILE_TEXT', 'CLOSE'])
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: info.outputPath(`reconciliation-closed-${width}-${theme}.png`), fullPage: true })
    await page.evaluate(() => (window as any).showFinance()); await page.getByRole('combobox', { name: 'Показатели', exact: true }).selectOption('finance')
    await expect(page.getByText('Сверено без товарной расшифровки', { exact: true })).toBeVisible()
    await expect(page.getByText('Суммы текстовых заказов сверены.', { exact: false })).toHaveCount(2)
  })
}
for (const status of [400, 409, 500, 408, -1]) test(`text reconciliation error ${status} preserves draft or exact uncertain pending command`, async ({ page }) => {
  const api = await setup(page); await form(page); await fill(page); api.fail(status); await submit(page).click()
  if ([400, 409].includes(status)) {
    await expect(page.getByLabel('Источник подтверждения · обязательно')).toBeFocused()
    await expect(page.getByLabel('Подтверждённый итог, ₽')).toHaveValue('750,00'); await page.reload(); await submit(page).click()
    await expect.poll(() => api.commands.length).toBe(2); expect(api.commands[0].requestId).not.toBe(api.commands[1].requestId)
  } else {
    await expect(page.getByText('Результат предыдущей операции ещё не подтверждён.', { exact: false })).toBeVisible(); await page.reload()
    await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click(); await expect.poll(() => api.commands.length).toBe(2)
    expect(api.commands[1]).toEqual(api.commands[0])
  }
  await expect(page.getByText('Сверка записана. Полученные деньги', { exact: false })).toBeVisible()
})
test('text reconciliation refuses unknown zero malformed and over-limit amounts without a write', async ({ page }) => {
  const api = await setup(page); await form(page); await fill(page)
  for (const value of ['', '0', '-1', '1.001', 'NaN', '100000000000.01']) {
    await page.getByLabel('Подтверждённый итог, ₽').fill(value); await submit(page).click()
    await expect(page.getByLabel('Подтверждённый итог, ₽')).toHaveAttribute('aria-invalid', 'true'); await expect(page.getByLabel('Подтверждённый итог, ₽')).toBeFocused()
  }
  expect(api.commands).toHaveLength(0)
})
test('text reconciliation staff and direct form URL cannot expose a privileged editor', async ({ page }) => {
  await setup(page, 'STAFF'); await expect(page.getByRole('button', { name: 'Сверить заказ', exact: true })).toHaveCount(0)
  await page.goto('accounting-chat-test#/accounting?av=1&shift=1&sale=order:1&view=reconcile'); await expect(page.locator('.accounting-reconciliation')).toHaveCount(0)
})
test('text reconciliation revoked access closes private form and never reports success', async ({ page }) => {
  const api = await setup(page); await form(page); await fill(page); api.fail(403); await submit(page).click()
  await expect(page.getByText('Доступ изменился.', { exact: false })).toBeVisible(); await expect(page.locator('.accounting-reconciliation')).toHaveCount(0)
  expect(api.commands).toHaveLength(1)
})
test('text reconciliation late success after account switch cannot expose former account data', async ({ page }) => {
  const api = await setup(page); await form(page); await fill(page); const release = api.hold(); await submit(page).click()
  await page.evaluate(() => { (window as any).Telegram.WebApp.initDataUnsafe.user.id = 999 }); await expect(page.getByText('Доступ изменился.', { exact: false })).toBeVisible(); release()
  await expect(page.getByText('Сверка записана. Полученные деньги', { exact: false })).toHaveCount(0)
})
test('text reconciliation venue switch preserves the own draft and hides foreign order', async ({ page }) => {
  await setup(page); await form(page); await fill(page)
  await page.evaluate(() => (window as any).switchAccountingVenue(2)); await expect(page.locator('.accounting-reconciliation')).toHaveCount(0)
  await page.evaluate(() => (window as any).switchAccountingVenue(1)); await expect(page.getByLabel('Подтверждённый итог, ₽')).toHaveValue('750,00')
})
test('text reconciliation stale version keeps draft and requires explicit current-state review', async ({ page }) => {
  const api = await setup(page); await form(page); await fill(page); api.fail(409); api.conflictState(); await submit(page).click(); await expect(page.getByLabel('Источник подтверждения · обязательно')).toBeFocused(); await page.reload()
  await expect(page.getByText('Запись изменилась. Черновик сверки сохранён;', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Открыть актуальную запись' }).click(); await expect(page.getByText('Сверка записана: подтверждённый', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Исправить сверку', exact: true }).click(); await page.getByRole('button', { name: 'Начать сверку актуальной записи' }).click()
  await expect(page.getByLabel('Подтверждённый итог, ₽')).toHaveValue('750.00'); expect(api.commands).toHaveLength(1)
})
test('closed CHAT PostgreSQL Ktor adapter DOM reconciles once across lost response restart and closes the shift', async ({ page }, info) => {
  const dir = process.env.ACCOUNTING_CHAT_LIVE_DIR
  const api = await setup(page, 'OWNER', dir); await form(page); await fill(page); await submit(page).click()
  if (dir) {
    await expect(page.getByRole('button', { name: 'Проверить и повторить операцию' })).toBeVisible()
    await expect.poll(() => existsSync(join(dir, 'restarted')), { timeout: 30000 }).toBe(true)
    await page.reload(); await page.getByRole('button', { name: 'Проверить и повторить операцию' }).click()
    await expect.poll(() => api.commands.length).toBe(2); expect(api.commands[1]).toEqual(api.commands[0])
  }
  await expect(page.getByText('Сверка записана. Полученные деньги', { exact: false })).toBeVisible()
  await expect(page.getByText(/Начислено.*Оплачено.*0.*Остаток/)).toBeVisible()
  await page.screenshot({ path: info.outputPath('live-reconciled-no-payment.png'), fullPage: true })
  await page.getByRole('button', { name: 'К отчёту', exact: true }).click(); await page.getByRole('button', { name: 'Предварительный итог и закрытие' }).click()
  await page.getByLabel('Проверил неоплаченные и спорные записи; принимаю закрытие с этим остатком').check()
  await page.getByRole('button', { name: 'Подтвердить закрытие смены', exact: true }).click(); await expect(page.getByText('Сохранённый итог.', { exact: false })).toBeVisible()
  await page.screenshot({ path: info.outputPath('live-frozen-unallocated.png'), fullPage: true })
  if (dir) writeFileSync(join(dir, 'done'), 'PASS')
})
