import { expect, test, type Page } from '@playwright/test'

async function identity(page: Page) {
  await page.addInitScript(() => {
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 7 } }, onEvent() {}, offEvent() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:7`, JSON.stringify({ token: 'synthetic', expiresAtEpochSeconds: 4102444800 }))
  })
}

test('review F4 real AudioContext status recovers only after successful Web Lock coordination', async ({ page }) => {
  await identity(page)
  await page.context().addInitScript(() => {
    const w = window as any
    w.starts = 0
    const Base = window.AudioContext
    w.AudioContext = class extends Base {
      constructor() { super(); w.actualAudio = this }
      createOscillator() {
        const oscillator = super.createOscillator(), start = oscillator.start.bind(oscillator)
        oscillator.start = (...args: Parameters<typeof start>) => { w.starts++; start(...args) }
        return oscillator
      }
    }
  })
  let events: unknown[] = []
  await page.context().route('**/api/venue/1/dashboard*', route => route.fulfill({ json: {
    venueId: 1, userId: 7, role: 'OWNER', permissions: ['BOOKING_MANAGE'], monitoringVersion: 3,
    eventCapacity: 256, attentionComplete: true, attentionTotalAtLeast: events.length, attention: events,
    activeOrders: 0, callsInWork: 0, bookingsPending: 0, venueStatus: 'PUBLISHED', refreshedAt: new Date().toISOString()
  } }))
  await page.context().route('**/miniapp/review-audio*', route => route.fulfill({ contentType: 'text/html', body: `
    <link rel="stylesheet" href="/miniapp/src/style.css"><div id="root"></div><script type="module">
    import {mountVenueEventMonitor} from '/miniapp/src/shared/venueEventMonitor.ts';
    window.monitor=mountVenueEventMonitor({root:document.querySelector('#root'),backendUrl:location.origin,isDebug:false,
      access:{venueId:1,role:'OWNER',permissions:['BOOKING_MANAGE']},userId:7,onAccessChanged(){}});</script>` }))
  await page.goto('review-audio')
  const label = page.locator('.venue-alerts > p').first()
  await page.getByRole('button', { name: 'Требуют внимания: 0', exact: true }).click()
  await page.getByRole('button', { name: 'Включить звук', exact: true }).click()
  await expect(label).toHaveText('Звук на этом устройстве: включён.')
  const lock = async () => {
    await page.evaluate(() => {
      const w = window as any; w.locked = false
      void navigator.locks.request('hookah:venue-events:v2:coordination', async () => {
        w.locked = true; await new Promise<void>(resolve => { w.unlock = resolve })
      })
    })
    await expect.poll(() => page.evaluate(() => (window as any).locked)).toBe(true)
  }
  const refresh = () => page.getByRole('button', { name: 'Обновить события', exact: true }).click()
  const receipt = (id: string) => page.evaluate(id => JSON.parse(localStorage.getItem('hookah:venue-events:v2')!).scopes[0].receipts[id], id)
  const event = (id: number) => ({ id: `call:${id}`, kind: 'STAFF_CALL_CREATED', callId: id, orderId: null, tableNumber: 1, createdAt: new Date().toISOString() })
  await lock(); await refresh()
  await expect(label).toContainText('координация')
  await page.evaluate(() => (window as any).unlock())
  events = [event(91)]; await refresh()
  await expect.poll(async () => (await receipt('call:91'))?.sounds).toBe(1)
  await expect(label).toHaveText('Звук на этом устройстве: включён.')
  expect(await page.evaluate(() => (window as any).starts)).toBe(2)
  await lock(); await refresh(); await expect(label).toContainText('координация')
  await page.evaluate(async () => { await (window as any).actualAudio.suspend(); (window as any).unlock() })
  events.push(event(92)); await refresh()
  await expect(label).toContainText('приостановлен')
  expect((await receipt('call:92')).sounds).toBe(0)
  await page.getByText('Настройки оповещений', { exact: true }).click()
  await page.getByLabel('Громкость сигнала').fill('0')
  await expect(label).toContainText('громкость сигнала 0')
  await page.getByRole('button', { name: 'Проверить звук', exact: true }).click()
  expect((await receipt('call:92')).sounds).toBe(0)
  expect(await page.evaluate(() => (window as any).starts)).toBe(2)
  await page.reload()
  await expect(label).toContainText('нужно разрешить звук')
  expect((await receipt('call:91')).sounds).toBe(1)
  const other = await page.context().newPage(); await identity(other); await other.goto('review-audio')
  await other.getByRole('button', { name: 'Требуют внимания: 2', exact: true }).click()
  await other.getByText('Настройки оповещений', { exact: true }).click()
  await other.getByLabel('Громкость сигнала').fill('50')
  await other.getByRole('button', { name: 'Включить звук', exact: true }).click()
  await expect.poll(async () => (await receipt('call:92'))?.sounds).toBe(1)
  await other.getByRole('button', { name: 'Обновить события', exact: true }).click()
  await expect(other.locator('.venue-alerts > p').first()).toHaveText('Звук на этом устройстве: включён.')
  expect((await receipt('call:91')).sounds).toBe(1)
  expect((await receipt('call:92')).sounds).toBe(1)
  await other.close()
})

test('review F5 booking target stays highlighted after reconciliation without repeated scroll or focus', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 680 })
  await identity(page)
  await page.addInitScript(() => {
    const w = window as any; w.targetScrolls = []
    const original = HTMLElement.prototype.scrollIntoView
    HTMLElement.prototype.scrollIntoView = function (...args) { if (this.dataset.bookingId) w.targetScrolls.push(this.dataset.bookingId); original.apply(this, args) }
  })
  let release!: () => void
  let blocked: Promise<void> | null = new Promise(resolve => { release = resolve })
  let denied = false
  let threadDenied = false
  await page.route('**/api/venue/bookings?*', route => route.fulfill(denied ? { status: 403, json: { error: { code: 'FORBIDDEN' } } } : { json: {
    items: [12, 13].map(bookingId => ({ bookingId, venueId: 1, status: 'pending', source: 'GUEST', scheduledAt: '2026-11-13T17:00:00Z', partySize: 2, guestDisplayName: 'Synthetic' }))
  } }))
  await page.route('**/api/venue/1/support/booking-threads?*', async route => {
    if (blocked) await blocked
    if (threadDenied) return route.fulfill({ status: 403, json: { error: { code: 'FORBIDDEN' } } })
    await route.fulfill({ json: { items: [12, 13].map(bookingId => ({ bookingId, status: 'NO_THREAD', thread: null })) } })
  })
  await page.route('**/miniapp/review-booking*', route => route.fulfill({ contentType: 'text/html', body: `
    <meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css">
    <button id="sentinel">Контроль фокуса</button><div id="root"></div><script type="module">
    import {renderVenueBookingsScreen} from '/miniapp/src/screens/venueBookings.ts';let dispose;
    window.mount=(venueId=1)=>{dispose?.();document.querySelector('#root').replaceChildren();dispose=renderVenueBookingsScreen({root:document.querySelector('#root'),backendUrl:location.origin,isDebug:false,venueId,access:{venueId,role:'OWNER',permissions:['BOOKING_MANAGE']}})};window.mount();</script>` }))
  await page.goto('review-booking#/bookings?bookingId=12')
  const target = page.locator('[data-booking-id="12"]')
  await expect(target).toHaveClass(/order-send-highlight/)
  await page.locator('#sentinel').focus()
  release(); blocked = null
  await expect(page.getByText('Сверяем переписку…')).toHaveCount(0)
  await expect(target).toHaveClass(/order-send-highlight/)
  await expect(page.locator('#sentinel')).toBeFocused()
  expect(await page.evaluate(() => (window as any).targetScrolls)).toEqual(['12'])
  await page.getByRole('button', { name: '🔄 Обновить', exact: true }).click()
  await expect(target).toHaveClass(/order-send-highlight/)
  await expect(page.getByRole('button', { name: '🔄 Обновить', exact: true })).toBeEnabled()
  expect(await page.evaluate(() => (window as any).targetScrolls)).toEqual(['12'])
  await page.evaluate(() => { location.hash = '#/bookings?bookingId=13' })
  await page.getByRole('button', { name: '🔄 Обновить', exact: true }).click()
  await expect(page.locator('[data-booking-id="13"]')).toHaveClass(/order-send-highlight/)
  await expect(target).not.toHaveClass(/order-send-highlight/)
  await page.goBack(); await page.getByRole('button', { name: '🔄 Обновить', exact: true }).click()
  await expect(target).toHaveClass(/order-send-highlight/)
  threadDenied = true; await page.getByRole('button', { name: '🔄 Обновить', exact: true }).click()
  await expect(page.locator('.order-send-highlight')).toHaveCount(0)
  threadDenied = false
  denied = true; await page.getByRole('button', { name: '🔄 Обновить', exact: true }).click()
  await expect(page.locator('.order-send-highlight')).toHaveCount(0)
  await page.evaluate(() => (window as any).mount(2))
  await expect(page.locator('.order-send-highlight')).toHaveCount(0)
})
