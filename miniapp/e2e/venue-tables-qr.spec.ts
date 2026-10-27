import { expect, test, type Page } from '@playwright/test'
import { telegramLaunchInitData, telegramSdkBootstrap } from './fixtures/telegram-launch'

type LaunchFixture = boolean | 'sdk-browser' | 'platform-only' | 'query-only' | 'hash-with-sdk' | 'hash-without-sdk'

async function mountTables(page: Page, permissions = ['TABLE_VIEW', 'TABLE_MANAGE', 'TABLE_QR_EXPORT'], launch: LaunchFixture = false) {
  const telegram = launch === true || launch === 'hash-with-sdk' || launch === 'hash-without-sdk'
  const sdk = launch === 'sdk-browser' || launch === 'hash-with-sdk'
  await page.route('**/miniapp/ux03-tables-test', (route) => route.fulfill({
    contentType: 'text/html', body: `<html><head><link rel="stylesheet" href="/miniapp/src/style.css"></head><body><div id="test-root"></div>${sdk ? '<script src="https://telegram.org/js/telegram-web-app.js"></script>' : ''}</body></html>`
  }))
  await page.route('https://telegram.org/js/telegram-web-app.js', (route) => route.fulfill({
    contentType: 'application/javascript', body: telegramSdkBootstrap
  }))
  await page.route('**/api/venue/tables?*', (route) => route.fulfill({ json: {
    tables: [{ tableId: Number(new URL(route.request().url()).searchParams.get('venueId')) * 10,
      tableNumber: 2, tableLabel: 'Стол №2', isActive: true, activeTokenIssuedAt: '2026-10-26T10:00:00Z' }]
  } }))
  await page.goto('ux03-tables-test')
  await page.evaluate(async ({ permissions, telegram, launch, initData }) => {
    if (launch === 'hash-with-sdk' || launch === 'hash-without-sdk') {
      history.replaceState(null, '', `#${new URLSearchParams({ tgWebAppData: initData })}`)
    } else if (launch === 'query-only') {
      history.replaceState(null, '', '?screen=menu&tableToken=synthetic_table&startapp=other&tgWebAppPlatform=ios')
    } else if (launch === 'platform-only') {
      Object.assign(window, { Telegram: { WebApp: { platform: 'ios', ready() {}, expand() {} } } })
    } else if (launch === true) {
      Object.assign(window, { Telegram: { WebApp: { initData, initDataUnsafe: { user: { id: 7001 } } } } })
    }
    if (telegram) {
      const activity = { blobUrls: 0, anchorClicks: 0 }
      Object.assign(window, { qrActivity: activity })
      const createObjectURL = URL.createObjectURL.bind(URL)
      URL.createObjectURL = (blob) => {
        activity.blobUrls++
        return createObjectURL(blob)
      }
      document.addEventListener('click', (event) => {
        if (event.target instanceof HTMLAnchorElement) activity.anchorClicks++
      }, true)
    }
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:${telegram ? '7001' : 'unknown'}`, JSON.stringify({
      token: 'synthetic-test-session', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600
    }))
    const path = '/miniapp/src/screens/venueTables.ts'
    const { renderVenueTablesScreen } = await import(path)
    let dispose: (() => void) | undefined
    const render = (venueId: number) => {
      dispose?.()
      dispose = renderVenueTablesScreen({ root: document.querySelector('#test-root'), backendUrl: location.origin, isDebug: false,
        venueId, access: { venueId, role: 'OWNER', permissions } })
    }
    Object.assign(window, { qrRender: render, qrInitialUrl: location.href })
    render(1)
  }, { permissions, telegram, launch, initData: telegramLaunchInitData })
  await expect(page.getByText('Стол №2', { exact: true })).toBeVisible()
}

async function expectTelegramScreen(page: Page) {
  await expect(page.getByRole('heading', { name: 'Столы и QR', exact: true })).toBeVisible()
  await expect(page).toHaveURL(await page.evaluate(() => Reflect.get(window, 'qrInitialUrl')))
  await expect(page.locator('.venue-tables a')).toHaveCount(0)
  expect(await page.evaluate(() => (window as Window & { qrActivity: { blobUrls: number; anchorClicks: number } }).qrActivity))
    .toEqual({ blobUrls: 0, anchorClicks: 0 })
  expect(await page.evaluate(() => performance.getEntriesByType('resource')
    .filter((entry) => entry.name.includes('/api/venue/tables/qr-package')))).toEqual([])
}

for (const userAgent of ['iPhone', 'desktop', 'hash-with-sdk', 'hash-without-sdk'] as const) {
  test.describe(`UX04 Telegram ${userAgent}`, () => {
    if (userAgent === 'iPhone') test.use({ userAgent: 'Mozilla/5.0 (iPhone)', viewport: { width: 390, height: 844 } })
    test('has one PDF and one table PNG action without downloads or navigation', async ({ page }) => {
      const deliveries: unknown[] = []
      const unexpected: string[] = []
      page.on('download', () => unexpected.push('download'))
      page.on('popup', () => unexpected.push('popup'))
      page.on('request', (request) => {
        if (new URL(request.url()).pathname.startsWith('/api/') && !request.url().includes('/api/venue/tables?') &&
            !request.url().includes('/api/venue/tables/qr-delivery?')) unexpected.push(request.url())
      })
      await page.route('**/api/venue/tables/qr-delivery?*', (route) => {
        expect(route.request().method()).toBe('POST')
        expect(route.request().headers().authorization).toBe('Bearer synthetic-test-session')
        expect(new URL(route.request().url()).searchParams.get('venueId')).toBe('1')
        deliveries.push(route.request().postDataJSON())
        return route.fulfill({ json: { sent: true } })
      })
      await mountTables(page, undefined, userAgent.startsWith('hash-') ? userAgent as LaunchFixture : true)
      await expect(page.locator('.venue-form-row button')).toHaveCount(1)
      await expect(page.locator('.venue-table-actions button')).toHaveCount(1)
      await expect(page.getByRole('button', { name: /Все QR в PDF|QR этого стола|ZIP|Отправить PNG/ })).toHaveCount(0)
      expect(deliveries).toEqual([])
      for (const label of ['Отправить все QR в Telegram', 'Отправить QR в Telegram']) {
        const send = page.getByRole('button', { name: label, exact: true })
        await send.click()
        await expect(page.locator('.status')).toContainText('Документ отправлен в ваш личный чат с ботом.')
        await expect(send).toBeEnabled()
        await expectTelegramScreen(page)
      }
      expect(deliveries).toEqual([{ format: 'pdf' }, { format: 'png', tableId: 10 }])
      expect(unexpected).toEqual([])
    })
  })
}

test('table batch previews sequential and individual numbers validates and keeps conflict draft', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const requests: unknown[] = []
  let conflict = true
  await page.route('**/api/venue/tables/batch-create?*', async (route) => {
    requests.push(route.request().postDataJSON())
    await route.fulfill(conflict ? { status: 400, json: { error: { code: 'INVALID_INPUT', message: 'Номер уже занят. Столы не созданы.' } } } :
      { json: { count: 6, tables: [] } })
  })
  await mountTables(page)
  const create = page.getByRole('button', { name: 'Создать столы', exact: true })
  await page.getByLabel('Количество столов').fill('6')
  const numbers = page.locator('.venue-table-numbers input')
  await expect(numbers).toHaveCount(6)
  expect(await numbers.evaluateAll((nodes) => nodes.map((node) => (node as HTMLInputElement).value))).toEqual(['3', '4', '5', '6', '7', '8'])
  for (const [index, value] of [1, 3, 5, 8, 12, 20].entries()) await numbers.nth(index).fill(String(value))
  await expect(page.locator('.venue-table-validation')).toContainText('1, 3, 5, 8, 12, 20')
  for (const invalid of ['1', '2', '0', '-1', '1.5', 'abc', '2147483648']) {
    await numbers.nth(5).fill(invalid)
    await expect(create).toBeDisabled()
  }
  await numbers.nth(5).fill('20')
  await create.click()
  await expect(page.getByText('Номер уже занят. Столы не созданы.')).toBeVisible()
  await expect(numbers.nth(5)).toHaveValue('20')
  expect(requests).toEqual([{ count: 6, tableNumbers: [1, 3, 5, 8, 12, 20] }])
  conflict = false
  await numbers.nth(5).fill('21')
  await create.click()
  await expect(page.getByLabel('Количество столов')).toHaveValue('')
  expect(requests).toHaveLength(2)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})

for (const browser of ['desktop', 'iPhone', 'sdk-browser', 'platform-only', 'query-only'] as const) {
  test.describe(`UX04 ordinary browser ${browser}`, () => {
    if (browser === 'iPhone') test.use({ userAgent: 'Mozilla/5.0 (iPhone)', viewport: { width: 390, height: 844 } })
    test('QR browser PDF PNG ZIP downloads preserve object URL and offer manual fallback', async ({ page }) => {
      const formats: string[] = []
      const deliveries: string[] = []
      page.on('request', (request) => {
        if (new URL(request.url()).pathname === '/api/venue/tables/qr-delivery') deliveries.push(request.url())
      })
      await page.route('**/api/venue/tables/qr-package?*', (route) => {
        const url = new URL(route.request().url())
        const format = url.searchParams.get('format')!
        formats.push(format)
        expect(route.request().headers().authorization).toBe('Bearer synthetic-test-session')
        expect(url.searchParams.has('token')).toBe(false)
        expect(url.searchParams.get('venueId')).toBe('1')
        if (format === 'png') expect(url.searchParams.get('tableId')).toBe('10')
        return route.fulfill({ body: `synthetic-${format}`, contentType: format === 'png' ? 'image/png' : `application/${format}`,
          headers: { 'Content-Disposition': `attachment; filename="qr.${format}"` } })
      })
      await mountTables(page, undefined, browser === 'desktop' || browser === 'iPhone' ? false : browser)
      if (browser === 'sdk-browser') expect(await page.evaluate(() => {
        const sdk = Reflect.get(window, 'Telegram').WebApp
        return { initData: sdk.initData, platform: sdk.platform }
      })).toEqual({ initData: '', platform: 'unknown' })
      await page.clock.install()
      for (const [label, format] of [['Все QR в PDF', 'pdf'], ['QR этого стола', 'png'], ['ZIP для компьютера', 'zip']]) {
        const pending = page.waitForEvent('download')
        await page.getByRole('button', { name: label, exact: true }).click()
        expect((await pending).suggestedFilename()).toBe(`qr.${format}`)
        const link = page.getByRole('link', { name: 'Открыть подготовленный файл' })
        await expect(link).toBeVisible()
        expect(await link.evaluate(async (node) => (await fetch((node as HTMLAnchorElement).href)).text())).toBe(`synthetic-${format}`)
        expect(await link.evaluate(async (node) => (await fetch((node as HTMLAnchorElement).href)).headers.get('content-type')))
          .toBe(format === 'png' ? 'image/png' : `application/${format}`)
        await expect(page.locator('.status')).toContainText('сохранение запрошено')
        await expect(page.getByText('QR пакет скачан', { exact: true })).toHaveCount(0)
      }
      expect(formats).toEqual(['pdf', 'png', 'zip'])
      expect(deliveries).toEqual([])
      await page.clock.fastForward(5 * 60 * 1000 + 10)
      await expect(page.getByRole('link', { name: 'Открыть подготовленный файл' })).toBeHidden()
    })
  })
}

for (const launch of ['sdk-browser', true, 'hash-without-sdk'] as const) {
  test(`UX04 launch P1 reopens Tables and changes venue after error and success (${launch})`, async ({ page }) => {
    const telegram = launch !== 'sdk-browser'
    const requests: unknown[] = []
    const unexpected: string[] = []
    let fail = true
    await page.route('**/api/venue/tables/qr-*?*', (route) => {
      const request = route.request()
      const url = new URL(request.url())
      const send = url.pathname.endsWith('/qr-delivery')
      if (send !== telegram) unexpected.push(url.pathname)
      expect(request.headers().authorization).toBe('Bearer synthetic-test-session')
      expect(request.method()).toBe(telegram ? 'POST' : 'GET')
      requests.push({ venueId: url.searchParams.get('venueId'),
        payload: send ? request.postDataJSON() : { format: url.searchParams.get('format'), tableId: url.searchParams.get('tableId') } })
      if (fail) return route.fulfill({ status: 400, json: { error: { code: 'INVALID_INPUT', message: 'Синтетическая ошибка QR.' } } })
      return route.fulfill(send ? { json: { sent: true } } : {
        contentType: 'image/png', body: 'synthetic-png', headers: { 'Content-Disposition': 'attachment; filename="venue-2.png"' }
      })
    })
    await mountTables(page, undefined, launch)
    const originalUrl = page.url()
    await page.getByRole('button', { name: telegram ? 'Отправить все QR в Telegram' : 'Все QR в PDF', exact: true }).click()
    await expect(page.locator('.error-card')).toContainText('Синтетическая ошибка QR.')
    await expect(page).toHaveURL(originalUrl)
    await expect(page.getByRole('heading', { name: 'Столы и QR', exact: true })).toBeVisible()
    await page.clock.install()
    await page.clock.fastForward(60_000)
    expect(requests).toHaveLength(1)
    fail = false
    for (const venueId of [1, 2]) {
      await page.evaluate((venueId) => Reflect.get(window, 'qrRender')(venueId), venueId)
      await expect(page.getByText('Стол №2', { exact: true })).toBeVisible()
      const download = telegram ? null : page.waitForEvent('download')
      await page.getByRole('button', { name: telegram ? 'Отправить QR в Telegram' : 'QR этого стола', exact: true }).click()
      if (download) expect((await download).suggestedFilename()).toBe('venue-2.png')
      await expect(page.locator('.status')).toContainText(telegram ? 'Документ отправлен в ваш личный чат с ботом.' : 'сохранение запрошено')
      await expect(page).toHaveURL(originalUrl)
      await expect(page.getByRole('heading', { name: 'Столы и QR', exact: true })).toBeVisible()
      if (telegram) await expectTelegramScreen(page)
    }
    expect(requests).toEqual(telegram ? [
      { venueId: '1', payload: { format: 'pdf' } },
      { venueId: '1', payload: { format: 'png', tableId: 10 } },
      { venueId: '2', payload: { format: 'png', tableId: 20 } }
    ] : [
      { venueId: '1', payload: { format: 'pdf', tableId: null } },
      { venueId: '1', payload: { format: 'png', tableId: '10' } },
      { venueId: '2', payload: { format: 'png', tableId: '20' } }
    ])
    expect(unexpected).toEqual([])
  })
}

test('Telegram QR delivery is explicit pending click is deduplicated and failure permits manual retry', async ({ page }) => {
  let release!: () => void
  const gate = new Promise<void>((resolve) => { release = resolve })
  const requests: unknown[] = []
  let fail = false
  await page.route('**/api/venue/tables/qr-delivery?*', async (route) => {
    requests.push(route.request().postDataJSON())
    await gate
    await route.fulfill(fail ? { status: 400, json: { error: { code: 'INVALID_INPUT', message: 'Личный чат недоступен. Откройте бота и нажмите Старт.' } } } : { json: { sent: true } })
  })
  await mountTables(page, undefined, true)
  await expectTelegramScreen(page)
  expect(requests).toHaveLength(0)
  const send = page.getByRole('button', { name: 'Отправить все QR в Telegram', exact: true })
  await send.click()
  await expect(send).toBeDisabled()
  await send.evaluate((node) => (node as HTMLButtonElement).click())
  await expect.poll(() => requests.length).toBe(1)
  release()
  await expect(page.locator('.status')).toContainText('Документ отправлен')
  fail = true
  await page.getByRole('button', { name: 'Отправить QR в Telegram', exact: true }).click()
  await expect(page.getByText('Личный чат недоступен. Откройте бота и нажмите Старт.')).toBeVisible()
  await expect(send).toBeEnabled()
  await expectTelegramScreen(page)
  expect(requests).toEqual([{ format: 'pdf' }, { format: 'png', tableId: 10 }])
})

for (const telegram of [false, true]) {
  for (const format of ['pdf', 'png']) {
    test(`UX04 unavailable ${format} delivery preserves screen without download fallback (Telegram=${telegram})`, async ({ page }) => {
      let calls = 0
      const downloads: string[] = []
      page.on('request', (request) => {
        if (new URL(request.url()).pathname === '/api/venue/tables/qr-package') downloads.push(request.url())
      })
      page.on('download', () => downloads.push('download'))
      await page.route('**/api/venue/tables/qr-delivery?*', (route) => {
        calls++
        expect(route.request().postDataJSON()).toEqual(format === 'pdf' ? { format } : { format, tableId: 10 })
        return route.fulfill({ status: 400, json: { error: { code: 'INVALID_INPUT',
          message: 'Личный чат недоступен. Откройте бота и нажмите Старт.' } } })
      })
      await mountTables(page, undefined, telegram)
      const label = format === 'pdf'
        ? (telegram ? 'Отправить все QR в Telegram' : 'Отправить в Telegram')
        : (telegram ? 'Отправить QR в Telegram' : 'Отправить PNG в Telegram')
      const send = page.getByRole('button', { name: label, exact: true })
      await send.click()
      await expect(page.locator('.error-card')).toContainText('Личный чат недоступен. Откройте бота и нажмите Старт.')
      await expect(send).toBeEnabled()
      if (telegram) await expectTelegramScreen(page)
      else await expect(page.getByRole('heading', { name: 'Столы и QR', exact: true })).toBeVisible()
      await page.clock.install()
      await page.clock.fastForward(60_000)
      expect(calls).toBe(1)
      expect(downloads).toEqual([])
    })
  }
}

for (const telegram of [false, true]) {
  test(`table view without manage or export rights has no creation download or delivery controls (Telegram=${telegram})`, async ({ page }) => {
    await mountTables(page, ['TABLE_VIEW'], telegram)
    await expect(page.getByLabel('Количество столов')).toHaveCount(0)
    await expect(page.getByRole('button')).toHaveCount(0)
  })
}

for (const outcome of ['audit-failure', 'lost-http-response', 'server-error', 'unconfirmed-success'] as const) {
  test(`UX03 P2 Telegram ${outcome} warns of possible delivery without retry`, async ({ page }) => {
    let acceptedDocuments = 0
    await page.route('**/api/venue/tables/qr-delivery?*', async (route) => {
      acceptedDocuments++
      if (outcome === 'lost-http-response') return route.abort('connectionreset')
      if (outcome === 'unconfirmed-success') return route.fulfill({ json: { sent: false } })
      await route.fulfill(outcome === 'audit-failure'
        ? { status: 400, json: { error: { code: 'INVALID_INPUT', details: { deliveryOutcome: 'unknown' } } } }
        : { status: 502, body: 'upstream connection lost' })
    })
    await mountTables(page, undefined, true)
    await page.getByRole('button', { name: outcome === 'lost-http-response' ? 'Отправить QR в Telegram' : 'Отправить все QR в Telegram', exact: true }).click()
    await expect(page.locator('.error-card')).toContainText(
      'Файл мог быть отправлен. Проверьте личный чат с ботом перед повторной отправкой.')
    await expect(page.locator('.error-card button')).toHaveCount(0)
    await expect(page.locator('.status')).not.toContainText('Документ отправлен')
    await expect(page.getByRole('button', { name: 'Отправить все QR в Telegram', exact: true })).toBeEnabled()
    // Advancing timers also checks that no delayed retry sends a second document.
    await page.clock.install()
    await page.clock.fastForward(60_000)
    expect(acceptedDocuments).toBe(1)
    await expectTelegramScreen(page)
  })
}

for (const rejection of [{ status: 403, code: 'FORBIDDEN' }, { status: 400, code: 'INVALID_INPUT' }]) {
  test(`UX03 P2 definite ${rejection.code} remains an ordinary denial`, async ({ page }) => {
    let calls = 0
    await page.route('**/api/venue/tables/qr-delivery?*', (route) => {
      calls++
      return route.fulfill({ status: rejection.status, json: { error: { code: rejection.code, message: 'Проверьте параметры файла.' } } })
    })
    await mountTables(page, undefined, true)
    await page.getByRole('button', { name: 'Отправить все QR в Telegram', exact: true }).click()
    await expect(page.locator('.error-card')).toBeVisible()
    await expect(page.locator('.error-card')).not.toContainText('Файл мог быть отправлен')
    if (rejection.code === 'INVALID_INPUT') await expect(page.locator('.error-card')).toContainText('Проверьте параметры файла.')
    else await expect(page.locator('.error-card')).toContainText('У вас нет доступа к режиму заведения. Обратитесь к администратору.')
    expect(calls).toBe(1)
    await expectTelegramScreen(page)
  })
}
