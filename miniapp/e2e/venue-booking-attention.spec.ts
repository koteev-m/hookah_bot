import { expect, test, type Page } from '@playwright/test'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { DashboardSnapshot } from '../src/shared/api/venueDashboardApi'
import type { SupportThreadDto } from '../src/shared/api/supportDtos'

const permissions = ['BOOKING_VIEW', 'BOOKING_MANAGE', 'SUPPORT_MANAGE', 'ORDER_QUEUE_VIEW', 'TABLE_VIEW', 'MENU_VIEW']
const at = '2026-11-12T12:00:00Z'
const target: SupportThreadDto = { threadId: 51, venueId: 1, threadType: 'BOOKING_THREAD', assigneeScope: 'VENUE', category: 'BOOKING',
  status: 'IN_PROGRESS', title: 'Бронь №12', contextLabel: 'Бронь №12', bookingId: 12, unreadCount: 1, createdAt: at, updatedAt: at,
  booking: { bookingId: 12, status: 'SEATED', scheduledAt: at } }
const pending = { id: 'booking:13', kind: 'BOOKING_PENDING' as const, bookingId: 13, orderId: null, callId: null, tableNumber: 0, createdAt: at }
const message = { id: 'bookingMessage:91', kind: 'BOOKING_MESSAGE' as const, threadId: 51, bookingId: 12, orderId: null, callId: null, tableNumber: 0, createdAt: at }
const snapshot: DashboardSnapshot = { venueId: 1, userId: 7, role: 'OWNER', permissions, monitoringVersion: 3, eventCapacity: 256,
  attentionComplete: true, attentionTotalAtLeast: 2, attention: [message, pending], activeOrders: 0, callsInWork: 0, bookingsPending: 1,
  occupiedTables: 0, activeTables: 0, unavailableItems: 0, unavailableOptions: 0, accountingOpenedAt: null, staffChatLinked: false,
  venueStatus: 'PUBLISHED', subscriptionStatus: 'TRIAL', refreshedAt: at }
async function identity(page: Page, token = 'synthetic') {
  await page.addInitScript(token => {
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 7 } }, ready() {}, expand() {}, onEvent() {}, offEvent() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:7`, JSON.stringify({ token, expiresAtEpochSeconds: 4102444800 }))
  }, token)
}
async function shell(page: Page) {
  await page.route('**/miniapp/booking-attention-test*', route => route.fulfill({ contentType: 'text/html', body: `
    <meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css">
    <div id="app"></div><script type="module">import {mountVenueApp} from '/miniapp/src/screens/venueApp.ts';
    mountVenueApp({root:document.querySelector('#app'),backendUrl:location.origin});</script>` }))
}
async function tasks(page: Page) {
  if (!await page.locator('.venue-alert-dialog').isVisible()) await page.getByRole('button', { name: /^Требуют внимания:/ }).click()
}
async function mock(page: Page) {
  await identity(page); await shell(page)
  let failure = 0, wrong = false, missing = false
  const detailIds: number[] = [], methods: string[] = []
  await page.route('**/api/**', async route => {
    const u = new URL(route.request().url())
    if (!u.pathname.startsWith('/api/')) return route.fallback()
    methods.push(route.request().method())
    if (u.pathname === '/api/venue/me') return route.fulfill({ json: { userId: 7, venues: [{ venueId: 1, venueName: 'Тестовое заведение', venueStatus: 'PUBLISHED', role: 'OWNER', permissions }] } })
    if (u.pathname.endsWith('/dashboard')) {
      const body = structuredClone(snapshot)
      if (missing) body.attention[0].threadId = null
      return route.fulfill({ json: body })
    }
    if (u.pathname.endsWith('/support/threads')) return route.fulfill(failure === 403 ? { status: 403, json: { error: { code: 'FORBIDDEN' } } } : { json: { items: [{ ...target, threadId: 52, bookingId: 14, title: 'Соседняя переписка', booking: { ...target.booking!, bookingId: 14 } }] } })
    if (/\/support\/threads\/\d+$/.test(u.pathname)) {
      const id = Number(u.pathname.split('/').pop()); detailIds.push(id)
      if (failure) return route.fulfill({ status: failure, json: { error: { code: failure === 403 ? 'FORBIDDEN' : 'NOT_FOUND' } } })
      return route.fulfill({ json: { thread: wrong ? { ...target, threadId: 52 } : target, messages: [{ messageId: 91, threadId: 51, authorRole: 'GUEST', source: 'GUEST_BOT', text: 'Точный текст SEATED', createdAt: at }] } })
    }
    if (u.pathname === '/api/venue/bookings') return route.fulfill({ json: { items: [{ bookingId: 13, venueId: 1, status: 'pending', source: 'GUEST', scheduledAt: at, partySize: 2 }] } })
    return route.fulfill({ json: { items: [], unreadCount: 0 } })
  })
  return { detailIds, methods, fail(value: number) { failure = value }, wrong(value: boolean) { wrong = value }, missing() { missing = true } }
}

for (const width of [360, 390, 1440]) test(`booking attention click selects exact conversation outside inventory ${width}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 800 }); const api = await mock(page)
  await page.goto('booking-attention-test?venueId=1#/dashboard')
  await tasks(page)
  const link = page.locator('.venue-alert-list a[href="#/messages?threadId=51"]')
  await expect(link).toBeVisible(); await expect(page.locator('.venue-alert-list')).not.toContainText('Точный текст')
  if (width === 390) { await link.focus(); await page.keyboard.press('Enter') } else await link.click()
  await expect(page).toHaveURL(/#\/messages\?threadId=51$/)
  await expect(page.locator('.venue-messages-detail')).toContainText('Точный текст SEATED')
  await expect(page.locator('.venue-messages-detail')).not.toContainText('Соседняя переписка')
  expect(api.detailIds).toEqual([51])
  await page.reload(); await expect(page.locator('.venue-messages-detail')).toContainText('Точный текст SEATED')
  await page.goBack(); await tasks(page)
  await page.locator('.venue-alert-list a[href="#/bookings?bookingId=13"]').click()
  await expect(page.locator('[data-booking-id="13"]')).toHaveClass(/order-send-highlight/)
  await expect(page.locator('[data-booking-id="12"]')).toHaveCount(0)
  expect(api.methods.every(method => method === 'GET')).toBe(true)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('booking attention exact failures and revoke never substitute or retain another conversation', async ({ page }) => {
  const api = await mock(page); api.fail(404)
  await page.goto('booking-attention-test?venueId=1#/messages?threadId=51')
  await expect(page.getByText(/Не удалось загрузить сообщения/)).toBeVisible()
  await expect(page.locator('.venue-messages-detail')).not.toContainText('Точный текст')
  api.fail(0); api.wrong(true); await page.reload()
  await expect(page.getByText(/Состав переписки изменился/)).toBeVisible()
  await expect(page.locator('.venue-messages-detail')).not.toContainText('Точный текст')
  api.wrong(false); await page.reload()
  await expect(page.locator('.venue-messages-detail')).toContainText('Точный текст SEATED')
  api.fail(403); await page.getByRole('button', { name: '🔄 Обновить', exact: true }).click()
  await expect(page.locator('.venue-messages-detail')).toBeEmpty()
  await expect(page.getByText(/Доступ к перепискам потерян/)).toBeVisible()
  api.fail(0); api.missing(); await page.goto('booking-attention-test?venueId=1#/dashboard'); await page.reload()
  await expect(page.locator('.venue-alert-status')).toContainText('Полнота списка не подтверждена')
  await expect(page.locator('.venue-alert-list a[href*="threadId"]')).toHaveCount(0)
})

test('PostgreSQL booking writer dashboard attention click opens exact Messages and preserves unread boundary', async ({ page }, testInfo) => {
  const directory = process.env.VENUE_BOOKING_ATTENTION_LIVE_DIR
  if (!directory) {
    const api = await mock(page); await page.goto('booking-attention-test?venueId=1#/dashboard'); await tasks(page)
    await page.locator('.venue-alert-list a[href="#/messages?threadId=51"]').click()
    await expect(page.locator('.venue-messages-detail')).toContainText('Точный текст SEATED'); expect(api.detailIds).toEqual([51]); return
  }
  test.setTimeout(180000)
  await expect.poll(() => existsSync(join(directory, 'ready.json')), { timeout: 90000 }).toBe(true)
  const live = JSON.parse(readFileSync(join(directory, 'ready.json'), 'utf8'))
  const methods: string[] = []
  await identity(page, live.token); await shell(page)
  await page.route('**/api/**', async route => {
    const u = new URL(route.request().url())
    if (!u.pathname.startsWith('/api/')) return route.fallback()
    methods.push(route.request().method())
    const response = await route.fetch({ url: live.base + u.pathname + u.search })
    await route.fulfill({ response })
  })
  const detail = page.locator('.venue-messages-detail')
  const direct = (status: string) => `#/messages?threadId=${live.targets[status].threadId}`
  try {
    await page.goto(`booking-attention-test?venueId=${live.venueId}#/dashboard`)
    await tasks(page)
    await page.locator(`.venue-alert-list a[href="${direct('SEATED')}"]`).click()
    await expect(page).toHaveURL(new RegExp(`threadId=${live.targets.SEATED.threadId}$`))
    await expect(detail).toContainText(live.targets.SEATED.text)
    await expect(detail.locator('h3').first()).toBeFocused()
    await expect(detail.getByText(live.targets.SEATED.text)).toBeInViewport()
    await expect(detail).not.toContainText('Соседний разговор')
    writeFileSync(join(directory, 'append'), 'new guest message after open snapshot')
    await expect.poll(() => existsSync(join(directory, 'appended'))).toBe(true)
    await expect(detail).not.toContainText('Новое после снимка')
    const unread = await page.request.get(`${live.base}/api/venue/${live.venueId}/dashboard?monitoringVersion=3`, { headers: { Authorization: `Bearer ${live.token}` } })
    expect((await unread.json()).attention.some((event: any) => event.threadId === live.targets.SEATED.threadId)).toBe(true)
    for (const width of [360, 390, 1440]) {
      await page.setViewportSize({ width, height: 800 }); await page.reload()
      await expect(detail).toContainText(live.targets.SEATED.text)
      await expect(detail).toContainText('Новое после снимка')
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      await page.screenshot({ path: testInfo.outputPath(`booking-thread-${width}.png`), fullPage: false })
    }
    for (const status of ['CONFIRMED', 'CHANGED']) {
      await page.evaluate(() => { location.hash = '#/dashboard' }); await tasks(page)
      await page.locator(`.venue-alert-list a[href="${direct(status)}"]`).click()
      await expect(detail).toContainText(live.targets[status].text)
    }
    await page.evaluate(() => { location.hash = '#/dashboard' }); await tasks(page)
    await page.locator(`.venue-alert-list a[href="#/bookings?bookingId=${live.pendingBookingId}"]`).click()
    await expect(page.locator(`[data-booking-id="${live.pendingBookingId}"]`)).toHaveClass(/order-send-highlight/)
    await expect(page.locator(`[data-booking-id="${live.targets.SEATED.bookingId}"]`)).toHaveCount(0)
    expect(methods.every(method => method === 'GET')).toBe(true)
  } finally { writeFileSync(join(directory, 'done'), 'browser finished') }
})
