import { expect, test, type Page } from '@playwright/test'

async function mountTables(page: Page, permissions = ['TABLE_VIEW', 'TABLE_MANAGE', 'TABLE_QR_EXPORT']) {
  await page.route('**/miniapp/ux03-tables-test', (route) => route.fulfill({
    contentType: 'text/html', body: '<html><head><link rel="stylesheet" href="/miniapp/src/style.css"></head><body><div id="test-root"></div></body></html>'
  }))
  await page.route('**/api/venue/tables?*', (route) => route.fulfill({ json: {
    tables: [{ tableId: 10, tableNumber: 2, tableLabel: 'Стол №2', isActive: true, activeTokenIssuedAt: '2026-10-26T10:00:00Z' }]
  } }))
  await page.goto('ux03-tables-test')
  await page.evaluate(async (permissions) => {
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:unknown`, JSON.stringify({
      token: 'synthetic-test-session', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600
    }))
    const path = '/miniapp/src/screens/venueTables.ts'
    const { renderVenueTablesScreen } = await import(path)
    renderVenueTablesScreen({ root: document.querySelector('#test-root'), backendUrl: location.origin, isDebug: false,
      venueId: 1, access: { venueId: 1, role: 'OWNER', permissions } })
  }, permissions)
  await expect(page.getByText('Стол №2', { exact: true })).toBeVisible()
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

test('QR browser PDF PNG ZIP downloads preserve object URL and offer manual fallback', async ({ page }) => {
  const formats: string[] = []
  await page.route('**/api/venue/tables/qr-package?*', (route) => {
    const url = new URL(route.request().url())
    const format = url.searchParams.get('format')!
    formats.push(format)
    expect(route.request().headers().authorization).toBe('Bearer synthetic-test-session')
    expect(url.searchParams.has('token')).toBe(false)
    if (format === 'png') expect(url.searchParams.get('tableId')).toBe('10')
    return route.fulfill({ body: `synthetic-${format}`, contentType: format === 'png' ? 'image/png' : `application/${format}`,
      headers: { 'Content-Disposition': `attachment; filename="qr.${format}"` } })
  })
  await mountTables(page)
  await page.clock.install()
  for (const [label, format] of [['Все QR в PDF', 'pdf'], ['QR этого стола', 'png'], ['ZIP для компьютера', 'zip']]) {
    const pending = page.waitForEvent('download')
    await page.getByRole('button', { name: label, exact: true }).click()
    expect((await pending).suggestedFilename()).toBe(`qr.${format}`)
    const link = page.getByRole('link', { name: 'Открыть подготовленный файл' })
    await expect(link).toBeVisible()
    expect(await link.evaluate(async (node) => (await fetch((node as HTMLAnchorElement).href)).text())).toBe(`synthetic-${format}`)
    await expect(page.locator('.status')).toContainText('сохранение запрошено')
    await expect(page.getByText('QR пакет скачан', { exact: true })).toHaveCount(0)
  }
  expect(formats).toEqual(['pdf', 'png', 'zip'])
  await page.clock.fastForward(5 * 60 * 1000 + 10)
  await expect(page.getByRole('link', { name: 'Открыть подготовленный файл' })).toBeHidden()
})

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
  await mountTables(page)
  expect(requests).toHaveLength(0)
  const send = page.getByRole('button', { name: 'Отправить в Telegram', exact: true })
  await send.click()
  await expect(send).toBeDisabled()
  await send.evaluate((node) => (node as HTMLButtonElement).click())
  await expect.poll(() => requests.length).toBe(1)
  release()
  await expect(page.locator('.status')).toContainText('Документ отправлен')
  fail = true
  await page.getByRole('button', { name: 'Отправить PNG в Telegram', exact: true }).click()
  await expect(page.getByText('Личный чат недоступен. Откройте бота и нажмите Старт.')).toBeVisible()
  await expect(send).toBeEnabled()
  expect(requests).toEqual([{ format: 'pdf' }, { format: 'png', tableId: 10 }])
})

test('table view without manage or export rights has no creation download or delivery controls', async ({ page }) => {
  await mountTables(page, ['TABLE_VIEW'])
  await expect(page.getByLabel('Количество столов')).toHaveCount(0)
  await expect(page.getByRole('button')).toHaveCount(0)
})

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
    await mountTables(page)
    await page.getByRole('button', { name: outcome === 'lost-http-response' ? 'Отправить PNG в Telegram' : 'Отправить в Telegram', exact: true }).click()
    await expect(page.locator('.error-card')).toContainText(
      'Файл мог быть отправлен. Проверьте личный чат с ботом перед повторной отправкой.')
    await expect(page.locator('.error-card button')).toHaveCount(0)
    await expect(page.locator('.status')).not.toContainText('Документ отправлен')
    await expect(page.getByRole('button', { name: 'Отправить в Telegram', exact: true })).toBeEnabled()
    // Advancing timers also checks that no delayed retry sends a second document.
    await page.clock.install()
    await page.clock.fastForward(60_000)
    expect(acceptedDocuments).toBe(1)
  })
}

for (const rejection of [{ status: 403, code: 'FORBIDDEN' }, { status: 400, code: 'INVALID_INPUT' }]) {
  test(`UX03 P2 definite ${rejection.code} remains an ordinary denial`, async ({ page }) => {
    let calls = 0
    await page.route('**/api/venue/tables/qr-delivery?*', (route) => {
      calls++
      return route.fulfill({ status: rejection.status, json: { error: { code: rejection.code, message: 'Проверьте параметры файла.' } } })
    })
    await mountTables(page)
    await page.getByRole('button', { name: 'Отправить в Telegram', exact: true }).click()
    await expect(page.locator('.error-card')).toBeVisible()
    await expect(page.locator('.error-card')).not.toContainText('Файл мог быть отправлен')
    if (rejection.code === 'INVALID_INPUT') await expect(page.locator('.error-card')).toContainText('Проверьте параметры файла.')
    else await expect(page.locator('.error-card')).toContainText('У вас нет доступа к режиму заведения. Обратитесь к администратору.')
    expect(calls).toBe(1)
  })
}
