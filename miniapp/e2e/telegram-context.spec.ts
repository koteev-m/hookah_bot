import { expect, test, type Page } from '@playwright/test'
import { telegramLaunchInitData, telegramSdkBootstrap } from './fixtures/telegram-launch'

async function openContext(page: Page, sdk = false, suffix = '') {
  await page.route('**/miniapp/ux04-context-test*', (route) => route.fulfill({
    contentType: 'text/html',
    body: `<html><body><div id="test-root"></div>${sdk ? '<script src="https://telegram.org/js/telegram-web-app.js"></script>' : ''}</body></html>`
  }))
  await page.route('https://telegram.org/js/telegram-web-app.js', (route) => route.fulfill({
    contentType: 'application/javascript', body: telegramSdkBootstrap
  }))
  await page.goto(`ux04-context-test${suffix}`)
}

for (const source of ['webapp', 'hash-with-sdk', 'hash-without-sdk'] as const) {
  test(`UX04 launch context preserves ${source} data and still requires server authorization`, async ({ page }) => {
    const hash = `#${new URLSearchParams({ tgWebAppData: telegramLaunchInitData })}`
    await openContext(page, source === 'hash-with-sdk', source === 'webapp' ? '' : hash)
    if (source === 'webapp') await page.evaluate((initData) => {
      Object.assign(window, { Telegram: { WebApp: { initData } } })
    }, telegramLaunchInitData)
    const expectedInitData = source === 'webapp' ? telegramLaunchInitData : decodeURIComponent(telegramLaunchInitData)
    const authRequests: unknown[] = []
    await page.route('**/api/auth/telegram', (route) => {
      expect(route.request().method()).toBe('POST')
      authRequests.push(route.request().postDataJSON())
      return route.fulfill({ status: 401, json: { error: { code: 'UNAUTHORIZED' } } })
    })
    const context = await page.evaluate(async () => {
      const path = '/miniapp/src/shared/telegram.ts'
      const { getTelegramContext, getTelegramInitData } = await import(path)
      const { hasTelegramLaunchData, isTelegram, telegramUserId, initData } = getTelegramContext()
      Object.assign(window, { authReady: 0 })
      const authPath = '/miniapp/src/shared/authGate.ts'
      const { mountAuthGate } = await import(authPath)
      mountAuthGate({ root: document.querySelector('#test-root'), onReady: () => { Reflect.set(window, 'authReady', Reflect.get(window, 'authReady') + 1) } })
      return { hasTelegramLaunchData, isTelegram, telegramUserId, initData, raw: getTelegramInitData() }
    })
    expect(context).toEqual({ hasTelegramLaunchData: true, isTelegram: source !== 'hash-without-sdk',
      telegramUserId: 7001, initData: expectedInitData, raw: expectedInitData })
    await expect.poll(() => authRequests.length).toBe(1)
    await expect(page.locator('.error-actions button')).toBeVisible()
    expect(authRequests).toEqual([{ initData: expectedInitData }])
    expect(await page.evaluate(() => Reflect.get(window, 'authReady'))).toBe(0)
  })
}

for (const scenario of ['no-sdk', 'sdk-bootstrap', 'empty-webapp', 'platform-and-functions', 'query-only', 'unsafe-start-only', 'whitespace'] as const) {
  test(`UX04 launch context rejects ${scenario} as launch evidence without changing SDK or start context`, async ({ page }) => {
    const query = scenario === 'query-only' ? '?screen=menu&tableToken=synthetic_table&startapp=other&tgWebAppPlatform=ios&tableSessionId=70' : ''
    await openContext(page, scenario === 'sdk-bootstrap', query)
    const result = await page.evaluate(async (scenario) => {
      if (['empty-webapp', 'platform-and-functions', 'unsafe-start-only', 'whitespace'].includes(scenario)) {
        const webApp = scenario === 'platform-and-functions' ? { platform: 'ios', ready() {}, expand() {}, showScanQrPopup() {} }
          : scenario === 'unsafe-start-only' ? { initDataUnsafe: { start_param: 'synthetic_table', user: { id: 7001 } } }
          : scenario === 'whitespace' ? { initData: '  ' } : {}
        Object.assign(window, { Telegram: { WebApp: webApp } })
      }
      const path = '/miniapp/src/shared/telegram.ts'
      const { getTelegramContext } = await import(path)
      const ctx = getTelegramContext()
      return { hasTelegramLaunchData: ctx.hasTelegramLaunchData, isTelegram: ctx.isTelegram,
        initData: ctx.initData, startParam: ctx.startParam, tableToken: ctx.tableToken,
        tableTokenAutoResolve: ctx.tableTokenAutoResolve, tableSessionId: ctx.tableSessionId,
        platform: Reflect.get(window, 'Telegram')?.WebApp?.platform ?? null }
    }, scenario)
    expect(result.hasTelegramLaunchData).toBe(false)
    expect(result.isTelegram).toBe(!['no-sdk', 'query-only'].includes(scenario))
    if (scenario === 'sdk-bootstrap') {
      expect(result.platform).toBe('unknown')
      expect(result.initData).toBeNull()
      expect(await page.evaluate(() => Reflect.get(window, 'Telegram').WebApp.initData)).toBe('')
    }
    if (scenario === 'query-only' || scenario === 'unsafe-start-only') {
      expect(result.startParam).toBe('synthetic_table')
      expect(result.tableToken).toBe('synthetic_table')
      expect(result.tableTokenAutoResolve).toBe(true)
    }
    if (scenario === 'query-only') expect(result.tableSessionId).toBe(70)
  })
}

test('UX04 SDK without launch data cannot pass the auth gate', async ({ page }) => {
  await openContext(page, true)
  const requests: string[] = []
  page.on('request', (request) => { if (new URL(request.url()).pathname.startsWith('/api/')) requests.push(request.url()) })
  await page.evaluate(async () => {
    const path = '/miniapp/src/shared/authGate.ts'
    const { mountAuthGate } = await import(path)
    mountAuthGate({ root: document.querySelector('#test-root'), onReady: () => { throw new Error('SDK presence must not authorize') } })
  })
  await expect(page.getByText('Нет Telegram-авторизации', { exact: true })).toBeVisible()
  expect(requests).toEqual([])
})

for (const initData of ['', telegramLaunchInitData]) {
  test(`UX04 SDK BackButton scanner and chat capabilities survive empty or populated launch data (${Boolean(initData)})`, async ({ page }) => {
    await openContext(page)
    const result = await page.evaluate(async (initData) => {
      let back = 0, shown = 0, hidden = 0, unbound = 0, scans = 0, sent = 0
      let callback = () => {}
      const webApp = { initData, initDataUnsafe: {}, BackButton: {
        show: () => { shown++ }, hide: () => { hidden++ },
        onClick: (cb: () => void) => { callback = cb }, offClick: () => { unbound++ }
      }, showScanQrPopup() { if (this === webApp) scans++ }, sendData: () => { sent++ } }
      Object.assign(window, { Telegram: { WebApp: webApp } })
      const path = '/miniapp/src/shared/telegram.ts'
      const { getTelegramContext, getTelegramQrScanner } = await import(path)
      const ctx = getTelegramContext()
      const backPath = '/miniapp/src/shared/telegramBackButton.ts'
      const { bindTelegramBackButton } = await import(backPath)
      const dispose = bindTelegramBackButton({ getRouteName: () => 'menu', canGoBack: () => true,
        back: () => { back++ }, navigate: () => { throw new Error('Unexpected fallback') } })
      callback()
      getTelegramQrScanner(ctx.webApp)?.({ text: 'QR' })
      const actionsPath = '/miniapp/src/shared/telegramActions.ts'
      const { sendChatOrder } = await import(actionsPath)
      const chat = sendChatOrder(ctx, { cmd: 'synthetic' })
      dispose()
      return { sameSdk: ctx.webApp === webApp, isTelegram: ctx.isTelegram, launch: ctx.hasTelegramLaunchData,
        back, shown, hidden, unbound, scans, sent, chat }
    }, initData)
    expect(result).toEqual({ sameSdk: true, isTelegram: true, launch: Boolean(initData),
      back: 1, shown: 1, hidden: 1, unbound: 1, scans: 1, sent: 1, chat: { ok: true } })
  })
}
