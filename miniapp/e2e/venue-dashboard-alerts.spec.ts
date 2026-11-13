import { expect, test, type Page } from '@playwright/test'
import type { DashboardSnapshot } from '../src/shared/api/venueDashboardApi'
import httpContract from './fixtures/venue-dashboard-http.json' with { type: 'json' }
import { writeFileSync } from 'node:fs'

const permissions = ['ORDER_QUEUE_VIEW', 'ORDER_STATUS_UPDATE', 'BOOKING_VIEW', 'MENU_VIEW', 'TABLE_VIEW', 'STAFF_CHAT_LINK']
const event = (id: string, kind: 'ORDER_CREATED' | 'BATCH_ADDED' | 'STAFF_CALL_CREATED' = 'ORDER_CREATED') => ({
  id, kind, orderId: id.startsWith('batch:') ? 10 : null, callId: id.startsWith('call:') ? Number(id.split(':')[1]) : null,
  tableNumber: 3, createdAt: '2026-11-09T08:00:00Z'
})
function empty(venueId = 1, role = 'OWNER', userId = 103): DashboardSnapshot {
  return { venueId, userId, role, permissions, monitoringVersion: 2, eventCapacity: 256, attentionComplete: true, attentionTotalAtLeast: 0, attention: [], activeOrders: 0, callsInWork: 0,
    bookingsPending: 2, occupiedTables: 1, activeTables: 4, unavailableItems: 2, unavailableOptions: 1,
    accountingOpenedAt: null, staffChatLinked: role === 'STAFF' ? null : true, venueStatus: 'PUBLISHED',
    subscriptionStatus: role === 'OWNER' ? 'PAST_DUE' : null, refreshedAt: '2026-11-09T08:00:00Z' }
}
async function fixture(page: Page, initial = empty()) {
  let snapshot = initial
  let fail = 0
  let requests = 0
  let mutations = 0
  let delay: Promise<void> | null = null
  let meDelay: Promise<void> | null = null
  let meRequests = 0
  await page.addInitScript((initialUserId) => {
    const state = window as unknown as { tones: number; blocked: boolean; holdDelivery: boolean; notices: unknown[]; permissionClicks: number; emit: (name: string) => void }
    state.tones = 0; state.blocked = false; state.notices = []; state.permissionClicks = 0
    const handlers = new Map<string, Set<() => void>>()
    const user = Number(sessionStorage.getItem('dashboard-user') ?? initialUserId)
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: user } }, ready() {}, expand() {},
      onEvent(name: string, fn: () => void) { if (!handlers.has(name)) handlers.set(name, new Set()); handlers.get(name)!.add(fn) },
      offEvent(name: string, fn: () => void) { handlers.get(name)?.delete(fn) }
    } } })
    state.emit = name => handlers.get(name)?.forEach(fn => fn())
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:${user}`, JSON.stringify({ token: 'synthetic', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 }))
    class Audio {
      state = 'suspended'; currentTime = 0; destination = {}
      async resume() { if (state.blocked) throw new Error('autoplay'); this.state = 'running' }
      async suspend() { this.state = 'suspended' }
      async close() { this.state = 'closed' }
      createGain() { return { gain: { setValueAtTime() {}, linearRampToValueAtTime() {} }, connect() {}, disconnect() {} } }
      createOscillator() { return { frequency: { value: 0, setValueAtTime() {} }, connect() {}, disconnect() {}, start() { if (state.blocked) throw new Error('blocked'); state.tones++ }, stop() { if (!state.holdDelivery) queueMicrotask(() => { this.onended?.() }) }, onended: null as (() => void) | null } }
    }
    class Notice {
      static permission = 'default'
      static async requestPermission() { state.permissionClicks++; Notice.permission = 'granted'; return 'granted' }
      constructor(title: string, options: unknown) { state.notices.push({ title, options }) }
      close() {}
    }
    Object.assign(window, { AudioContext: Audio, Notification: Notice })
  }, initial.userId)
  await page.route('**/miniapp/dashboard-test*', route => route.fulfill({ contentType: 'text/html', body:
    '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"></head><body><div id="app"></div><script type="module">import {mountVenueApp} from "/miniapp/src/screens/venueApp.ts"; import {bindAppTheme} from "/miniapp/src/shared/ui/appTheme.ts"; bindAppTheme(document.documentElement); window.mountAgain=()=>mountVenueApp({root:document.querySelector("#app"),backendUrl:location.origin}); window.mountAgain();</script></body></html>' }))
  await page.route('**/api/venue/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() !== 'GET') mutations++
    if (path === '/api/venue/me') {
      meRequests++
      const body = { userId: snapshot.userId, venues: [1, 2].map(venueId => ({ venueId, role: snapshot.role,
        permissions: snapshot.role === 'STAFF' ? permissions.filter(p => p !== 'STAFF_CHAT_LINK') : permissions, venueName: `Заведение ${venueId}`, venueStatus: 'PUBLISHED' })) }
      if (meDelay) await meDelay
      return route.fulfill({ json: body })
    }
    if (/\/dashboard$/.test(path)) {
      requests++
      const captured = structuredClone(snapshot)
      const capturedFailure = fail
      captured.attentionTotalAtLeast = captured.attentionComplete ? captured.attention.length : 257
      captured.venueId = Number(path.split('/')[3])
      if (captured.venueId !== snapshot.venueId) { captured.attention = []; captured.attentionTotalAtLeast = 0 }
      if (delay) await delay
      if (capturedFailure) return route.fulfill({ status: capturedFailure, json: { error: { code: capturedFailure === 403 ? 'FORBIDDEN' : 'UNAVAILABLE' } } })
      return route.fulfill({ json: captured })
    }
    if (path === '/api/venue/orders/queue') return route.fulfill({ json: { items: [], nextCursor: null } })
    return route.fulfill({ json: { items: [], unreadCount: 0 } })
  })
  await page.goto('dashboard-test?venueId=1#/dashboard')
  await expect(page.getByRole('button', { includeHidden: true, name: /Требуют внимания: \d/ })).toBeVisible()
  return { set(value: DashboardSnapshot) { snapshot = value }, fail(status: number) { fail = status }, requests: () => requests,
    mutations: () => mutations, hold(promise: Promise<void> | null) { delay = promise },
    holdMe(promise: Promise<void> | null) { meDelay = promise }, meRequests: () => meRequests }
}
async function openTasks(page: Page) {
  if (!await page.locator('.venue-alert-dialog').isVisible()) await page.getByRole('button', { includeHidden: true, name: /^Требуют внимания:/ }).click()
}
async function taskAction(page: Page, name: string) {
  await openTasks(page)
  await page.getByRole('button', { name, exact: true }).click()
  await page.keyboard.press('Escape')
}
const tones = (page: Page) => page.evaluate(() => (window as unknown as { tones: number }).tones)
const refresh = async (page: Page) => {
  await openTasks(page)
  await expect(page.getByRole('button', { includeHidden: true, name: 'Обновить события', exact: true })).toBeEnabled()
  await Promise.all([
    page.waitForResponse(response => /\/api\/venue\/\d+\/dashboard$/.test(new URL(response.url()).pathname)),
    taskAction(page, 'Обновить события')
  ])
  await expect(page.getByRole('button', { includeHidden: true, name: 'Обновить события', exact: true })).toBeEnabled()
  await expect(page.locator('.venue-alert-status')).toContainText('Связь есть.')
}

test('first connection is silent, first batch and append of one order plus calls coalesce and replay is silent', async ({ page }) => {
  const baseline = empty(); baseline.attention = [event('batch:1')]
  const api = await fixture(page, baseline)
  await expect.poll(() => tones(page)).toBe(0)
  await taskAction(page, 'Включить звук')
  await expect.poll(() => tones(page)).toBe(1)
  const next = { ...baseline,
    attention: [event('batch:1'), event('batch:2', 'BATCH_ADDED'), { ...event('batch:3'), orderId: 20 }, event('call:7', 'STAFF_CALL_CREATED'), event('call:8', 'STAFF_CALL_CREATED')], activeOrders: 2 }
  api.set(next); await refresh(page)
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: 5', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { includeHidden: true, name: /Первый заказ/ }).last()).toHaveAttribute('href', '#/orders/20')
  await expect.poll(() => tones(page)).toBe(2)
  await refresh(page); await expect.poll(() => tones(page)).toBe(2)
  await openTasks(page); await page.locator('.venue-alert-dialog').getByRole('link', { includeHidden: true, name: /Дозаказ/ }).first().click()
  await expect(page).toHaveURL(/#\/orders\/10$/)
  await expect(page.locator('.venue-alerts')).toHaveCount(1)
  await refresh(page); await expect.poll(() => tones(page)).toBe(2)
  expect(api.mutations()).toBe(0)
  await page.reload()
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: 5', exact: true })).toBeVisible()
  await expect.poll(() => tones(page)).toBe(0)
  await taskAction(page, 'Включить звук'); await refresh(page)
  await expect.poll(() => tones(page)).toBe(1)
})

test('offline recovery catches lower delayed IDs and already handled creations without a sound storm', async ({ page }) => {
  const api = await fixture(page)
  await taskAction(page, 'Включить звук')
  api.fail(503); await taskAction(page, 'Обновить события')
  await expect(page.locator('.venue-alert-status')).toContainText('Нет связи')
  await page.evaluate(() => (window as unknown as { emit: (s: string) => void }).emit('deactivated'))
  const before = api.requests()
  api.set({ ...empty(), attention: [event('batch:9', 'BATCH_ADDED')] })
  await page.clock.install(); await page.clock.runFor(45000)
  expect(api.requests()).toBe(before)
  api.fail(0)
  await page.evaluate(() => (window as unknown as { emit: (s: string) => void }).emit('activated'))
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: 1', exact: true })).toBeVisible()
  await expect.poll(() => tones(page)).toBe(2)
  await refresh(page); await expect.poll(() => tones(page)).toBe(2)
  await expect(page.locator('.venue-alert-status')).toContainText('Последнее успешное обновление:')
})

test('bounded repeats and local mute do not ACK, toggle and volume are device only', async ({ page }) => {
  const api = await fixture(page)
  await page.clock.install()
  await taskAction(page, 'Включить звук')
  api.set({ ...empty(), attention: [event('call:1', 'STAFF_CALL_CREATED')] })
  await refresh(page); await expect.poll(() => tones(page)).toBe(2)
  await page.clock.runFor(61000); await refresh(page); await expect.poll(() => tones(page)).toBe(3)
  await page.clock.runFor(61000); await refresh(page); await expect.poll(() => tones(page)).toBe(4)
  await page.clock.runFor(180000); await refresh(page); await expect.poll(() => tones(page)).toBe(4)
  api.set({ ...empty(), attention: [event('call:2', 'STAFF_CALL_CREATED')] })
  await refresh(page); await expect.poll(() => tones(page)).toBe(5)
  await taskAction(page, 'Приглушить на устройстве')
  await expect(page.getByRole('button', { includeHidden: true, name: 'Приглушено на устройстве', exact: true })).toBeDisabled()
  await page.clock.runFor(61000); await refresh(page); await expect.poll(() => tones(page)).toBe(5)
  await openTasks(page); await page.getByText('Настройки оповещений', { exact: true }).click()
  await page.getByLabel('Громкость сигнала').fill('25')
  await taskAction(page, 'Выключить звук')
  api.set({ ...empty(), attention: [event('call:3', 'STAFF_CALL_CREATED')] })
  await refresh(page); await expect.poll(() => tones(page)).toBe(5)
  expect(api.mutations()).toBe(0)
})

test('autoplay rejection and unavailable storage fail safely with visual alerts', async ({ page }) => {
  const api = await fixture(page)
  await page.evaluate(() => { (window as unknown as { blocked: boolean }).blocked = true })
  await taskAction(page, 'Включить звук')
  await expect(page.locator('.venue-alerts')).toContainText('нужно разрешить звук')
  api.set({ ...empty(), attention: [event('batch:1')] }); await refresh(page)
  await expect.poll(() => tones(page)).toBe(0)
  await page.evaluate(() => { (window as unknown as { blocked: boolean }).blocked = false })
  await taskAction(page, 'Включить звук'); await expect.poll(() => tones(page)).toBe(2)
  await page.evaluate(() => { Storage.prototype.setItem = () => { throw new Error('quota') } })
  api.set({ ...empty(), attention: [event('batch:2', 'BATCH_ADDED')] }); await refresh(page)
  await expect(page.locator('.venue-alerts')).toContainText('Хранилище недоступно')
  await expect(page.locator('.venue-alerts')).not.toContainText('Новых ожидающих событий после предыдущего обновления:')
  await refresh(page)
  await expect(page.locator('.venue-alerts')).not.toContainText('Новых ожидающих событий после предыдущего обновления:')
  await expect.poll(() => tones(page)).toBe(2)
})

test('venue and role switches discard late responses and denied access clears the snapshot', async ({ page }) => {
  const api = await fixture(page)
  api.set({ ...empty(), attention: [event('batch:1')] }); await refresh(page)
  let release!: () => void
  api.hold(new Promise<void>(resolve => { release = resolve }))
  await taskAction(page, 'Обновить события')
  await page.getByRole('combobox', { name: 'Выбрать заведение' }).selectOption('2')
  api.hold(null); release()
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: 0', exact: true })).toBeVisible()
  await expect(page.locator('.venue-alert-list')).not.toContainText('Новый заказ')
  api.set({ ...empty(2, 'STAFF'), attention: [] }); await refresh(page)
  await expect(page.locator('.venue-header')).toContainText('персонал')
  await expect(page.getByRole('link', { includeHidden: true, name: /Чат персонала:/ })).toHaveCount(0)
  api.fail(403); await taskAction(page, 'Обновить события')
  await expect(page.getByText('Оповещения остановлены:', { exact: false })).toBeVisible()
  await expect(page.locator('.venue-alert-list')).toHaveCount(0)
})

test('real HTTP capture renders all cockpit cards and retained first batch plus append', async ({ page }, testInfo) => {
  await fixture(page, httpContract as DashboardSnapshot)
  await expect(page.getByRole('button', { includeHidden: true, name: /^Требуют внимания:/ })).toBeInViewport()
  await expect(page.locator('.venue-dashboard-card').filter({ hasText: 'Новые заказы и дозаказы' })).toContainText('2')
  await expect(page.locator('.venue-dashboard-card').filter({ hasText: 'Активные заказы' })).toContainText('1')
  await expect(page.locator('.venue-dashboard-card').filter({ hasText: 'Чат персонала:' })).toContainText('не привязан')
  await expect(page.getByRole('link', { includeHidden: true, name: /Первый заказ · стол 1/ })).toHaveAttribute('href', '#/orders/1')
  await expect(page.getByRole('link', { includeHidden: true, name: /Дозаказ · новые позиции · стол 1/ })).toHaveAttribute('href', '#/orders/1')
  await expect(page.getByRole('link', { includeHidden: true, name: /Вызов персонала · стол 1/ })).toHaveAttribute('href', '#/calls?callId=1')
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('dashboard-mobile.png'), fullPage: true })
})

test('visibility pause, timeout, offline and account switch discard stale private state', async ({ page }) => {
  const api = await fixture(page)
  await page.clock.install()
  await taskAction(page, 'Включить звук')
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  const before = api.requests()
  await page.clock.runFor(45000); expect(api.requests()).toBe(before)
  api.set({ ...empty(), attention: [event('call:4', 'STAFF_CALL_CREATED')] })
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: 1', exact: true })).toBeVisible()
  await expect.poll(() => tones(page)).toBe(2)
  let release!: () => void
  api.hold(new Promise<void>(resolve => { release = resolve }))
  await Promise.all([
    page.waitForRequest(request => /\/api\/venue\/\d+\/dashboard$/.test(new URL(request.url()).pathname)),
    taskAction(page, 'Обновить события')
  ])
  await expect(page.getByRole('button', { includeHidden: true, name: 'Обновить события', exact: true })).toBeDisabled()
  await page.clock.runFor(10001)
  await expect(page.locator('.venue-alert-status')).toContainText('Нет связи')
  api.hold(null); release()
  await page.evaluate(() => { Object.defineProperty(navigator, 'onLine', { configurable: true, value: false }); dispatchEvent(new Event('offline')) })
  const offlineRequests = api.requests()
  await page.clock.runFor(30000); expect(api.requests()).toBe(offlineRequests)
  api.set({ ...empty(1, 'OWNER', 104), attention: [] })
  await page.evaluate(() => {
    const w = window as unknown as { Telegram: { WebApp: { initDataUnsafe: { user: { id: number } } } } }
    w.Telegram.WebApp.initDataUnsafe.user.id = 104
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:104`, JSON.stringify({ token: 'synthetic', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 }))
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true }); dispatchEvent(new Event('online'))
  })
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: 0', exact: true })).toBeVisible()
  await expect(page.locator('.venue-alert-list')).not.toContainText('стол 3')
  await expect.poll(() => tones(page)).toBe(2)
})

test('unsupported Web Locks keeps visual detection and explicitly disables automatic sound', async ({ page }) => {
  await page.addInitScript(() => Object.defineProperty(navigator, 'locks', { value: undefined }))
  const api = await fixture(page)
  await taskAction(page, 'Включить звук')
  api.set({ ...empty(), attention: [event('batch:1')] }); await refresh(page)
  await expect(page.locator('.venue-alerts')).toContainText('недоступен — только визуальные оповещения')
  await expect.poll(() => tones(page)).toBe(1)
})

test('Web Locks rejection falls back to visual updates without automatic signals', async ({ page }) => {
  await page.addInitScript(() => Object.defineProperty(navigator, 'locks', { value: { request: async () => { throw new Error('SecurityError') } } }))
  const api = await fixture(page)
  await taskAction(page, 'Включить звук')
  api.set({ ...empty(), attention: [event('batch:1')] }); await refresh(page)
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: 1', exact: true })).toBeVisible()
  await expect(page.locator('.venue-alerts')).toContainText('недоступна координация — только визуальные оповещения')
  await expect.poll(() => tones(page)).toBe(1)
})

test('401 clears facts and stops polling until explicit access recovery', async ({ page }) => {
  const api = await fixture(page)
  api.set({ ...empty(), attention: [event('call:1', 'STAFF_CALL_CREATED')] }); await refresh(page)
  api.fail(401)
  await taskAction(page, 'Обновить события')
  await expect(page.locator('.venue-alerts')).toHaveCount(0)
  await expect(page.locator('.venue-alert-list')).toHaveCount(0)
  const requests = api.requests()
  await page.clock.install(); await page.clock.runFor(45000)
  expect(api.requests()).toBe(requests)
})

test('repeat mount and competing windows reserve each new event once, notifications contain no customer data', async ({ page, context }) => {
  const api = await fixture(page)
  await openTasks(page); await page.getByText('Настройки оповещений', { exact: true }).click()
  await taskAction(page, 'Включить системные уведомления')
  expect(await page.evaluate(() => (window as unknown as { permissionClicks: number }).permissionClicks)).toBe(1)
  await taskAction(page, 'Включить звук')
  await page.evaluate(() => (window as unknown as { mountAgain: () => void }).mountAgain())
  await expect(page.locator('.venue-alerts')).toHaveCount(1)
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: 0', exact: true })).toBeVisible()
  await taskAction(page, 'Включить звук')
  await openTasks(page); await page.getByText('Настройки оповещений', { exact: true }).click()
  await taskAction(page, 'Включить системные уведомления')
  const second = await context.newPage()
  const api2 = await fixture(second)
  await taskAction(second, 'Включить звук')
  const next = { ...empty(), attention: [event('batch:1'), event('call:2', 'STAFF_CALL_CREATED')] }
  api.set(next); api2.set(next)
  await refresh(page); await refresh(second)
  expect(await tones(page) + await tones(second)).toBe(4) // three explicit enable checks + one arrival
  const notices = await page.evaluate(() => (window as unknown as { notices: Array<{ title: string; options: { body: string } }> }).notices)
  expect(notices).toEqual([{ title: 'Событие заведения', options: { body: 'Проверьте оперативный экран Mini App.', tag: 'hookah-venue-attention', silent: true } }])
})

test('403 unmounts private calls before delayed me and rejects late private 200 and stale access', async ({ page }) => {
  const api = await fixture(page)
  let holdPrivate = false
  let releasePrivate!: () => void
  const privateBarrier = new Promise<void>(resolve => { releasePrivate = resolve })
  await page.route('**/api/venue/1/staff-calls**', async route => {
    if (holdPrivate) await privateBarrier
    return route.fulfill({ json: { items: [{ id: 91, tableId: 1, tableNumber: 3, reason: 'COME', reasonLabel: 'Подойти', status: 'NEW', statusLabel: 'Новый', createdAt: '2026-11-09T08:00:00Z', guestDisplayName: 'Приватный гость', comment: 'Приватный комментарий' }] } })
  })
  await page.evaluate(() => { location.hash = '#/calls' })
  await expect(page.locator('.venue-calls')).toContainText('Приватный гость')
  await expect(page.locator('.venue-calls')).toContainText('Приватный комментарий')
  holdPrivate = true
  await Promise.all([page.waitForRequest(request => request.url().includes('/staff-calls')), page.locator('.venue-calls').getByRole('button', { includeHidden: true, name: 'Обновить', exact: true }).click()])
  let releaseMe!: () => void
  api.holdMe(new Promise<void>(resolve => { releaseMe = resolve }))
  const before = api.meRequests()
  api.fail(403)
  await taskAction(page, 'Обновить события')
  await expect.poll(api.meRequests).toBe(before + 1)
  await expect(page.locator('.venue-calls')).toHaveCount(0)
  await expect(page.locator('body')).not.toContainText('Приватный гость')
  await expect(page.locator('body')).not.toContainText('Приватный комментарий')
  await expect(page.locator('.venue-header')).not.toContainText('владелец')
  releasePrivate()
  await expect(page.locator('body')).not.toContainText('Приватный гость')
  api.fail(0); api.holdMe(null); releaseMe()
  await expect(page).not.toHaveURL(/venueId=1/)
  await expect(page.locator('body')).not.toContainText('Приватный гость')
  expect(api.mutations()).toBe(0)
})

test('late dashboard 200 cannot restore a context cleared by another venue 403', async ({ page }) => {
  const api = await fixture(page)
  let releaseDashboard!: () => void
  api.hold(new Promise<void>(resolve => { releaseDashboard = resolve }))
  await Promise.all([page.waitForRequest(request => request.url().includes('/1/dashboard')), taskAction(page, 'Обновить события')])
  api.hold(null)
  let releaseMe!: () => void
  api.holdMe(new Promise<void>(resolve => { releaseMe = resolve }))
  api.fail(403)
  await page.getByRole('combobox', { name: 'Выбрать заведение' }).selectOption('2')
  await expect.poll(api.meRequests).toBe(2)
  await expect(page.locator('.venue-alerts')).toHaveCount(0)
  releaseDashboard()
  await expect(page.locator('.venue-header')).not.toContainText('владелец')
  await expect(page.locator('.screen-root .venue-owner-home')).toHaveCount(0)
  api.fail(0); api.holdMe(null); releaseMe()
  await expect(page).not.toHaveURL(/venueId=2/)
})

test('stale me actor after account change stays invalid until matching access is obtained', async ({ page }) => {
  const api = await fixture(page)
  let release!: () => void
  api.holdMe(new Promise<void>(resolve => { release = resolve }))
  await page.evaluate(() => {
    const w = window as unknown as { Telegram: { WebApp: { initDataUnsafe: { user: { id: number } } } }; emit: (name: string) => void }
    w.Telegram.WebApp.initDataUnsafe.user.id = 104
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:104`, JSON.stringify({ token: 'synthetic-104', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 }))
    w.emit('activated')
  })
  await expect.poll(api.meRequests).toBe(2)
  await expect(page.locator('.venue-alerts')).toHaveCount(0)
  api.holdMe(null); release()
  await expect(page.locator('.venue-access-state')).toContainText('Аккаунт изменился')
  await expect(page.locator('.venue-owner-home')).toHaveCount(0)
  api.set(empty(1, 'OWNER', 104))
  await page.evaluate(() => sessionStorage.setItem(`hookah_session_token:${location.host}:user:104`, JSON.stringify({ token: 'synthetic-104', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 })))
  await page.getByRole('button', { includeHidden: true, name: 'Обновить доступ к оповещениям', exact: true }).click()
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: 0', exact: true })).toBeVisible()
})

test('confirmed role change cannot reenter the old role from stale me or start a revalidation loop', async ({ page }) => {
  const api = await fixture(page)
  await page.route('**/api/venue/*/dashboard*', route => {
    const venue = Number(new URL(route.request().url()).pathname.split('/')[3])
    return route.fulfill({ json: empty(venue, 'STAFF') })
  })
  await taskAction(page, 'Обновить события')
  await expect(page.locator('.venue-alerts')).toHaveCount(0)
  await expect(page.locator('.venue-header')).not.toContainText('владелец')
  expect(api.meRequests()).toBeLessThanOrEqual(3)
  await page.clock.install(); await page.clock.runFor(45000)
  expect(api.meRequests()).toBeLessThanOrEqual(3)
  api.set(empty(1, 'STAFF'))
  await page.getByRole('button', { includeHidden: true, name: 'Обновить доступ к оповещениям', exact: true }).click()
  await expect(page.locator('.venue-header')).toContainText('персонал')
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: 0', exact: true })).toBeVisible()
})

test('silent tab discovering first leaves audio pending for enabled tab', async ({ page, context }) => {
  const audible = await fixture(page)
  await taskAction(page, 'Включить звук')
  const quietPage = await context.newPage()
  const quiet = await fixture(quietPage)
  const next = { ...empty(), attention: [event('call:41', 'STAFF_CALL_CREATED')] }
  audible.set(next); quiet.set(next)
  await refresh(quietPage)
  const before = await quietPage.evaluate(() => JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0].receipts['call:41'])
  expect(before.sounds).toBe(0); expect(before.baseline).toBe(false)
  await refresh(page)
  await expect.poll(() => tones(page)).toBe(2)
  expect(await tones(quietPage)).toBe(0)
  await refresh(quietPage); await refresh(page)
  expect(await tones(page)).toBe(2)
})

test('two enabled tabs concurrently deliver one successful audio receipt', async ({ page, context }) => {
  const a = await fixture(page)
  const bPage = await context.newPage()
  const b = await fixture(bPage)
  await taskAction(page, 'Включить звук')
  await taskAction(bPage, 'Включить звук')
  const next = { ...empty(), attention: [event('batch:42', 'BATCH_ADDED')] }
  a.set(next); b.set(next)
  await Promise.all([refresh(page), refresh(bPage)])
  await expect.poll(async () => await tones(page) + await tones(bPage)).toBe(3)
  const receipt = await page.evaluate(() => JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0].receipts['batch:42'])
  expect(receipt.sounds).toBe(1)
  await Promise.all([refresh(page), refresh(bPage)])
  expect(await tones(page) + await tones(bPage)).toBe(3)
})

test('closed delivery owner leaves an expiring lease and another enabled tab recovers', async ({ page, context }) => {
  const a = await fixture(page)
  const bPage = await context.newPage()
  const b = await fixture(bPage)
  await taskAction(page, 'Включить звук')
  await taskAction(bPage, 'Включить звук')
  await page.evaluate(() => { (window as unknown as { holdDelivery: boolean }).holdDelivery = true })
  const next = { ...empty(), attention: [event('call:51', 'STAFF_CALL_CREATED')] }
  a.set(next); b.set(next)
  await taskAction(page, 'Обновить события')
  await expect.poll(() => tones(page)).toBe(2)
  const pending = await bPage.evaluate(() => JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0])
  expect(pending.receipts['call:51'].sounds).toBe(0)
  expect(pending.lease).not.toBeNull()
  await page.close()
  await bPage.clock.install(); await bPage.clock.runFor(8500)
  await refresh(bPage)
  await expect.poll(() => tones(bPage)).toBe(2)
  const delivered = await bPage.evaluate(() => JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0])
  expect(delivered.receipts['call:51'].sounds).toBe(1)
  expect(delivered.lease).toBeNull()
})

test('stalled Web Lock has a bounded wait and recovers after its tab closes', async ({ page, context }) => {
  const api = await fixture(page)
  await taskAction(page, 'Включить звук')
  const holder = await context.newPage()
  await fixture(holder)
  await holder.evaluate(() => {
    void navigator.locks.request('hookah:venue-events:v2:coordination', async () => {
      Object.assign(window, { holding: true })
      await new Promise(() => undefined)
    })
  })
  await expect.poll(() => holder.evaluate(() => (window as unknown as { holding: boolean }).holding)).toBe(true)
  api.set({ ...empty(), attention: [event('call:52', 'STAFF_CALL_CREATED')] })
  await refresh(page)
  await expect(page.locator('.venue-alert-status')).toContainText('Координация вкладок занята')
  await openTasks(page)
  await expect(page.getByRole('button', { includeHidden: true, name: 'Выключить звук', exact: true })).toBeVisible()
  expect(await tones(page)).toBe(1)
  await holder.close()
  await refresh(page)
  await expect.poll(() => tones(page)).toBe(2)
})

test('failed automatic playback is not a receipt and manual enable retries pending audio', async ({ page }) => {
  const api = await fixture(page)
  await taskAction(page, 'Включить звук')
  await page.evaluate(() => { (window as unknown as { blocked: boolean }).blocked = true })
  api.set({ ...empty(), attention: [event('call:61', 'STAFF_CALL_CREATED')] })
  await refresh(page)
  const failed = await page.evaluate(() => JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0])
  expect(failed.receipts['call:61'].sounds).toBe(0)
  expect(failed.lease).toBeNull()
  await page.evaluate(() => { (window as unknown as { blocked: boolean }).blocked = false })
  await taskAction(page, 'Включить звук')
  await expect.poll(() => tones(page)).toBe(3)
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0].receipts['call:61'].sounds)).toBe(1)
  await openTasks(page)
  await page.getByText('Настройки оповещений', { exact: true }).click()
  await page.evaluate(() => { (window as unknown as { holdDelivery: boolean }).holdDelivery = true })
  api.set({ ...empty(), attention: [event('call:61', 'STAFF_CALL_CREATED'), event('call:62', 'STAFF_CALL_CREATED')] })
  await page.getByRole('button', { name: 'Обновить события', exact: true }).click()
  await expect.poll(() => page.evaluate(() => Boolean(JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0].lease))).toBe(true)
  await page.getByLabel('Громкость сигнала').fill('0')
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0].lease)).toBeNull()
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0].receipts['call:62'].sounds)).toBe(0)
  await expect(page.locator('.venue-alerts')).toContainText('выключен — громкость сигнала 0')
  await expect(page.locator('.venue-alerts')).not.toContainText('нужно разрешить звук')
  await page.evaluate(() => { (window as unknown as { holdDelivery: boolean }).holdDelivery = false })
  await page.getByLabel('Громкость сигнала').fill('50')
  await page.keyboard.press('Escape')
  await refresh(page)
  await expect.poll(() => page.evaluate(() => JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0].receipts['call:62'].sounds)).toBe(1)
  expect(api.mutations()).toBe(0)
})

test('capacity sentinel and oversized responses explicitly limit monitoring without empty success', async ({ page }) => {
  const api = await fixture(page)
  await taskAction(page, 'Включить звук')
  api.set({ ...empty(), attention: Array.from({ length: 256 }, (_, i) => event(`batch:${i + 1}`)), attentionComplete: false, attentionTotalAtLeast: 257 })
  await refresh(page)
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: ≥257', exact: true })).toBeVisible()
  await expect(page.locator('.venue-alert-status')).toContainText('Мониторинг ограничен')
  expect(await tones(page)).toBe(1)
  await page.route('**/api/venue/*/dashboard*', route => route.fulfill({ json: { ...empty(), padding: 'x'.repeat(140000) } }))
  await taskAction(page, 'Обновить события')
  await expect(page.getByRole('button', { includeHidden: true, name: 'Требуют внимания: неизвестно', exact: true })).toBeVisible()
  await expect(page.locator('.venue-alerts')).not.toContainText('Нет ожидающих событий.')
  const accessChecks = api.meRequests()
  await page.route('**/api/venue/*/dashboard*', route => route.fulfill({ json: { items: [] } }))
  await taskAction(page, 'Обновить события')
  await expect(page.locator('.venue-alert-status')).toContainText('Несовместимый')
  await expect(page.locator('.venue-header')).toContainText('владелец')
  expect(api.meRequests()).toBe(accessChecks)
})

test('rapid receipt capacity preserves enabled preference and resumes pending delivery after grace', async ({ page }) => {
  const api = await fixture(page)
  await page.clock.install()
  await taskAction(page, 'Включить звук')
  for (let group = 0; group < 2; group++) {
    api.set({ ...empty(), attention: Array.from({ length: 256 }, (_, i) => event(`batch:${group * 256 + i + 1}`)) })
    await refresh(page)
    await expect.poll(() => tones(page)).toBe(group + 2)
  }
  api.set({ ...empty(), attention: Array.from({ length: 256 }, (_, i) => event(`batch:${i + 513}`)) })
  await refresh(page)
  await expect(page.locator('.venue-alert-status')).toContainText('Слишком много событий за короткое время')
  await openTasks(page)
  await expect(page.getByRole('button', { includeHidden: true, name: 'Выключить звук', exact: true })).toBeVisible()
  expect(await tones(page)).toBe(3)
  await page.clock.runFor(31000)
  await refresh(page)
  await expect.poll(() => tones(page)).toBe(4)
  await expect(page.locator('.venue-alert-status')).not.toContainText('Мониторинг ограничен')
})

test('200000 and 400000 legacy IDs never accumulate in bounded ledger and new batches still sound', async ({ page }, testInfo) => {
  const api = await fixture(page)
  await taskAction(page, 'Включить звук')
  const results: unknown[] = []
  for (const history of [200000, 400000]) {
    const legacy = await page.evaluate(count => {
      const raw = JSON.stringify({ seen: Array.from({ length: count }, (_, i) => `batch:${i + 1}`), signals: {}, muted: [] })
      let accepted = true
      try { localStorage.setItem('hookah:venue-events:v1:legacy', raw) } catch { accepted = false }
      return { history: count, legacyBytes: new TextEncoder().encode(raw).byteLength, legacyAccepted: accepted }
    }, history)
    api.set({ ...empty(), attention: [event(`batch:${history + 1}`, 'BATCH_ADDED')] })
    const started = Date.now()
    await refresh(page)
    const storage = await page.evaluate(() => {
      const raw = localStorage.getItem('hookah:venue-events:v2')!
      const value = JSON.parse(raw)
      return { utf8Bytes: new TextEncoder().encode(raw).byteLength, utf16Bytes: raw.length * 2, scopes: value.scopes.length, receipts: value.scopes.map((scope: { receipts: object }) => Object.keys(scope.receipts).length), legacyPresent: localStorage.getItem('hookah:venue-events:v1:legacy') !== null }
    })
    await expect.poll(() => tones(page)).toBe(history === 200000 ? 2 : 3)
    expect(storage.utf8Bytes).toBeLessThanOrEqual(262144)
    expect(storage.utf16Bytes).toBeLessThanOrEqual(524288)
    expect(storage.legacyPresent).toBe(false)
    results.push({ ...legacy, ...storage, refreshMs: Date.now() - started, arrivalSound: true })
  }
  writeFileSync(testInfo.outputPath('capacity-browser.json'), JSON.stringify(results, null, 2))
})

test('origin ledger enforces byte context and receipt limits without silent rapid-history eviction', async ({ page }, testInfo) => {
  await fixture(page)
  const measurements = await page.evaluate(async () => {
    const { VenueEventLedger } = await import('/miniapp/src/shared/venueEventLedger.ts')
    localStorage.removeItem('hookah:venue-events:v2')
    const rows: unknown[] = []
    for (let context = 0; context < 5; context++) {
      const ledger = new VenueEventLedger(`capacity:${context}`)
      for (let group = 0; group < 3; group++) {
        const ids = Array.from({ length: 256 }, (_, i) => `batch:${8000000000000000000n + BigInt(context * 10000 + group * 256 + i)}`)
        let limited = ''
        try { await ledger.observe(ids, false, false) } catch (error) { limited = String(error) }
        const raw = localStorage.getItem('hookah:venue-events:v2')!
        const value = JSON.parse(raw)
        rows.push({ context, group, limited, utf8Bytes: new TextEncoder().encode(raw).byteLength, utf16Bytes: raw.length * 2,
          scopes: value.scopes.length, receipts: value.scopes.map((scope: { receipts: object }) => Object.keys(scope.receipts).length) })
      }
    }
    return rows as Array<{ context: number; group: number; limited: string; utf8Bytes: number; utf16Bytes: number; scopes: number; receipts: number[] }>
  })
  for (const row of measurements) {
    expect(row.utf8Bytes).toBeLessThanOrEqual(262144)
    expect(row.utf16Bytes).toBeLessThanOrEqual(524288)
    expect(row.scopes).toBeLessThanOrEqual(4)
    expect(row.receipts.every(count => count <= 512)).toBe(true)
  }
  expect(measurements.find(row => row.context === 0 && row.group === 2)!.limited).toContain('короткое время')
  expect(measurements.find(row => row.context === 4 && row.group === 0)!.limited).not.toBe('')
  writeFileSync(testInfo.outputPath('capacity-ledger.json'), JSON.stringify(measurements, null, 2))
})

for (const width of [360, 390, 1440]) test(`operations compact alerts and single task surface ${width}`, async ({ page }, testInfo) => {
  await page.setViewportSize({ width, height: 570 })
  await page.emulateMedia({ colorScheme: width === 390 ? 'light' : 'dark' })
  const snapshot = empty(); snapshot.monitoringVersion = 3
  snapshot.attention = [{ id: 'booking:12', kind: 'BOOKING_PENDING', orderId: null, callId: null, bookingId: 12,
    tableNumber: 0, createdAt: '2026-11-09T08:00:00Z', scheduledAt: '2026-11-10T10:00:00Z' }]
  if (width === 390) snapshot.attention.push(...Array.from({ length: 40 }, (_, i) => ({ ...event(`batch:${100 + i}`, 'BATCH_ADDED'), batchId: 100 + i })))
  const api = await fixture(page, snapshot)
  await expect(page.locator('.venue-alert-dialog')).not.toBeVisible()
  await expect(page.locator('.venue-alerts')).toContainText('Звук на этом устройстве: выключен')
  expect((await page.locator('.venue-alerts').boundingBox())!.height).toBeLessThan(170)
  const cardBoxes = await page.locator('.venue-dashboard-cards > .venue-dashboard-card').evaluateAll(nodes => nodes.map(node => {
    const box = node.getBoundingClientRect(); return { x: box.x, y: box.y, right: box.right, bottom: box.bottom }
  }))
  for (let i = 0; i < cardBoxes.length; i++) for (let j = i + 1; j < cardBoxes.length; j++) {
    const a = cardBoxes[i], b = cardBoxes[j]
    expect(a.right <= b.x || b.right <= a.x || a.bottom <= b.y || b.bottom <= a.y).toBe(true)
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath(`compact-${width}.png`), fullPage: true })
  await openTasks(page)
  await expect(page.getByRole('link', { name: /Новая бронь/ })).toHaveAttribute('href', '#/bookings?bookingId=12')
  await expect(page.getByRole('link', { name: /Новая бронь/ })).toContainText('время устройства')
  const scroll = await page.locator('.venue-alert-list').evaluate(node => getComputedStyle(node).overflowY)
  expect(scroll).toBe('visible')
  await openTasks(page); await page.getByText('Настройки оповещений', { exact: true }).click()
  await page.getByLabel('Громкость сигнала').fill('0')
  await page.getByRole('button', { name: 'Проверить звук' }).click()
  expect(await tones(page)).toBe(0)
  await page.getByLabel('Громкость сигнала').fill('50')
  await page.getByRole('button', { name: 'Проверить звук' }).click()
  expect(await tones(page)).toBe(1)
  await expect(page.locator('.venue-alerts')).toContainText('Звук на этом устройстве: выключен')
  await page.screenshot({ path: testInfo.outputPath(`task-panel-${width}.png`), fullPage: true })
  await page.keyboard.press('Escape')
  await expect(page.locator('.venue-alert-dialog')).not.toBeVisible()
  const extras = page.locator('.venue-dashboard-extras')
  await extras.locator('summary').click()
  const activeOrders = extras.locator('.venue-dashboard-card').filter({ hasText: 'Активные заказы' })
  await activeOrders.focus()
  api.set({ ...snapshot, activeOrders: 9 })
  await page.getByRole('button', { name: 'Обновить обзор', exact: true }).evaluate(node => (node as HTMLButtonElement).click())
  await expect(activeOrders).toContainText('9')
  await expect(activeOrders).toBeFocused()
  expect(api.mutations()).toBe(0)
  if (width === 360) {
    const compatibility = await page.evaluate(async () => {
      const { VenueEventLedger } = await import('/miniapp/src/shared/venueEventLedger.ts')
      const ledger = new VenueEventLedger('booking-compatibility')
      await ledger.observe([], false, false)
      const result = await ledger.observe(['booking:900', 'bookingMessage:901'], true, false)
      const stored = JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes.find((scope: { key: string }) => scope.key === 'booking-compatibility')
      const pending = await ledger.observe(['booking:900', 'bookingMessage:901'], true, false)
      await ledger.finish(result.lease!, true, false)
      const complete = JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes.find((scope: { key: string }) => scope.key === 'booking-compatibility')
      return { stored, pending: pending.lease, complete }
    })
    expect(Object.keys(compatibility.stored.receipts)).toEqual([])
    expect(Object.keys(compatibility.stored.bookingReceipts)).toEqual(['booking:900', 'bookingMessage:901'])
    expect(compatibility.stored.lease.audio).toEqual([])
    expect(compatibility.stored.bookingLease.audio).toEqual(['booking:900', 'bookingMessage:901'])
    expect(compatibility.pending).toBeNull()
    expect(compatibility.complete.bookingReceipts['booking:900'].sounds).toBe(1)
  }
})
