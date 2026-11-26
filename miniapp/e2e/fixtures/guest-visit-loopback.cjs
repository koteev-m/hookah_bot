// Invoked by GuestTableResolveAvailabilityPostgresTest against its disposable fixture.
const { chromium, webkit, expect } = require('@playwright/test')
const { storedPointer, seedCart, storedCart } = require('./visit-record.cjs')
const { createHmac, randomUUID } = require('node:crypto')

const origin = new URL('http://127.0.0.1')
origin.port = process.argv[2]
const withBill = process.argv[3] === 'true'

function signedLaunch() {
  const fields = {
    auth_date: String(Math.floor(Date.now() / 1000)),
    query_id: randomUUID(),
    user: JSON.stringify({ id: 501, first_name: 'Test' })
  }
  const key = createHmac('sha256', 'WebAppData').update('123456:local-visit-test').digest()
  const check = Object.entries(fields).map(([key, value]) => key + '=' + value).join('\n')
  const hash = createHmac('sha256', key).update(check).digest('hex')
  return new URLSearchParams({ ...fields, hash }).toString()
}

async function newWebView(browser, storageState) {
  const context = await browser.newContext({ storageState })
  await context.route(url => url.origin !== origin.origin, route => route.abort())
  await context.addInitScript(initData => {
    window.Telegram = { WebApp: {
      initData, initDataUnsafe: { user: { id: 501 } }, ready() {}, expand() {},
      showScanQrPopup(_options, scan) { scan('availability-token') }
    } }
  }, signedLaunch())
  return context
}

async function checkBotLinkRetry(browser) {
  const key = 'hookah_guest_table_session_context:user:501'
  const previous = JSON.stringify({ tableToken: 'availability-token', tableSessionId: 301 })
  const next = JSON.stringify({ tableToken: 'confirmed-b', tableSessionId: 302 })
  const drafts = [
    { name: 'hookah_guest_cart_draft:user:501:availability-token:session:301:visit:0', value: '{"items":[],"commentDraft":"Table A"}' },
    { name: 'hookah_guest_cart_draft:user:502:confirmed-b:session:302:visit:0', value: '{"items":[],"commentDraft":"Other guest"}' }
  ]
  const context = await newWebView(browser, { cookies: [], origins: [
    { origin: origin.origin, localStorage: [{ name: key, value: previous }, ...drafts] }
  ] })
  let retained
  try {
    const page = await context.newPage()
    await page.addInitScript(([key, value]) => sessionStorage.setItem(key, value), [key, previous])
    const path = '/api/guest/table/resolve'
    const exactB = response => {
      const url = new URL(response.url())
      return url.pathname === path && url.searchParams.get('tableToken') === 'confirmed-b' &&
        url.searchParams.get('tableSessionId') === '302' && !url.searchParams.has('resolveMode')
    }
    // Inject only the first failure. The retry and the next WebView use Ktor and PostgreSQL.
    await page.route('**/api/guest/table/resolve?**', route => route.fulfill({ status: 503 }), { times: 1 })
    const failed = page.waitForResponse(response => exactB(response) && response.status() === 503)
    await page.goto(new URL('/miniapp/?mode=guest&screen=menu&table_token=confirmed-b&tableSessionId=302', origin).href)
    await failed
    const retry = page.getByRole('button', { name: 'Повторить загрузку стола' })
    await expect(retry).toBeVisible()
    const pointers = () => Promise.all([page.evaluate(k => sessionStorage.getItem(k), key), storedPointer(page, key)])
    expect(await pointers()).toEqual([previous, previous])
    const verified = page.waitForResponse(response => exactB(response) && response.ok())
    await retry.click()
    await verified
    await expect(page.getByText('Вы за столом №2 · Other', { exact: true })).toBeVisible()
    expect(await pointers()).toEqual([next, next])
    retained = await context.storageState({ indexedDB: true })
  } finally {
    await context.close()
  }
  const reopened = await newWebView(browser, retained)
  try {
    const page = await reopened.newPage()
    const restore = page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname === '/api/guest/table/restore' && url.searchParams.get('tableToken') === 'confirmed-b' &&
        url.searchParams.get('tableSessionId') === '302' && response.ok()
    })
    await page.goto(new URL('/miniapp/?mode=home', origin).href)
    await restore
    await expect(page.getByText('Вы за столом №2 · Other', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Завершить визит' })).toBeEnabled()
    expect(await page.evaluate(keys => keys.map(k => localStorage.getItem(k)), drafts.map(d => d.name)))
      .toEqual(drafts.map(d => d.value))
  } finally {
    await reopened.close()
  }
}

async function checkLaunch(engine) {
  const browser = await engine.launch()
  try {
    const context = await newWebView(browser)
    const page = await context.newPage()
    const reads = new Set()
    page.on('response', response => {
      const url = new URL(response.url())
      if (response.status() !== 200) return
      if (url.pathname === '/api/guest/staff-call/status' && url.searchParams.get('tableSessionId') === '302') reads.add('status')
      if (url.pathname === '/api/guest/tabs' && url.searchParams.get('table_session_id') === '302') reads.add('tabs')
      if (url.pathname === '/api/guest/order/active' && url.searchParams.get('tableSessionId') === '302' &&
          url.searchParams.get('tabId') === '403') reads.add('order')
    })
    await page.goto(new URL('/miniapp/?mode=home', origin).href)
    await expect(page.getByText('Вы за столом №2 · Other', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Завершить визит' })).toBeEnabled()
    expect([...reads].sort()).toEqual(withBill ? ['order', 'status', 'tabs'] : ['status', 'tabs'])
    await context.close()
    if (!withBill) await checkBotLinkRetry(browser)
  } finally {
    await browser.close()
  }
}

// route.fetch reaches the real loopback route. Drop only its response after Ktor
// reports success; the parent fixture independently checks PostgreSQL after each phase.
async function checkCommittedResponseLoss(phase, engine, stateFile) {
  const fs = require('node:fs/promises')
  const browser = await engine.launch()
  const saved = phase === 'lose-end' ? null : JSON.parse(await fs.readFile(stateFile, 'utf8'))
  const context = await newWebView(browser, saved?.browser)
  try {
    const page = await context.newPage()
    page.on('dialog', dialog => dialog.accept())
    const link = '/miniapp/?mode=guest&screen=menu&table_token=availability-token&tableSessionId=301'
    const key = 'hookah_guest_table_session_context:user:501'
    const baseline = 'hookah_guest_cart_draft:user:501:availability-token:session:301'
    const other = baseline.replace(':501:', ':502:') + ':visit:0'
    const rich = JSON.stringify({ items: [{ itemId: 1, qty: 2 }], commentDraft: 'prior participation' })
    let cartKey = saved?.cartKey
    if (phase === 'reopen') {
      const restored = page.waitForResponse(r => new URL(r.url()).pathname === '/api/guest/table/restore')
      await page.goto(new URL('/miniapp/?mode=guest', origin).href)
      const body = await (await restored).json()
      expect(body.context.tableSessionId).toBe(301)
      expect(BigInt(body.context.visitGeneration) > BigInt(saved.prior)).toBe(true)
      await expect(page.getByRole('button', { name: 'Завершить визит' })).toBeEnabled()
      await expect(page.getByRole('button', { name: 'Корзина', exact: true })).toBeVisible()
      expect(await Promise.all([storedCart(page, cartKey), storedCart(page, other)])).toEqual([null, rich])
      return
    }
    const resolved = page.waitForResponse(r => new URL(r.url()).pathname === '/api/guest/table/resolve')
    await page.goto(new URL(link, origin).href)
    const entry = await (await resolved).json()
    if (phase === 'lose-end') {
      expect(entry.tableSessionActive).toBe(true)
      await expect(page.getByRole('button', { name: 'Завершить визит' })).toBeEnabled()
      cartKey = baseline + ':visit:' + entry.visitGeneration
      await seedCart(page, cartKey, rich)
      await page.evaluate(([k,v]) => localStorage.setItem(k,v), [other,rich])
      await page.reload()
      await expect(page.getByRole('button', { name: 'Корзина (2)'})).toBeVisible()
    } else {
      expect(entry.tableSessionInactiveReason).toBe('TABLE_SESSION_EXITED')
      expect(JSON.parse(await storedCart(page, cartKey)).commentDraft).toBe('prior participation')
    }
    const path = '/api/guest/table/session/' + (phase === 'lose-end' ? 'end' : 'rejoin')
    let committed
    await page.route('**' + path, async route => {
      const command = route.request().postDataJSON()
      if (phase === 'lose-end') expect(command.expectedVisitGeneration).toBe(entry.visitGeneration)
      const response = await route.fetch()
      committed = { status: response.status(), body: await response.json() }
      if (phase === 'lose-rejoin') expect(committed.body.visitGeneration).toBe(command.exitEventId)
      await route.abort('failed')
    })
    const lost = page.waitForEvent('requestfailed', request => new URL(request.url()).pathname === path)
    await page.getByRole('button', { name: phase === 'lose-end' ? 'Завершить визит' : 'QR', exact: true }).click()
    await lost
    expect(committed.status).toBe(200)
    expect(phase === 'lose-end' ? committed.body.ended : committed.body.tableSessionActive).toBe(true)
    expect(await storedPointer(page, key)).toBeTruthy()
    await fs.writeFile(stateFile, JSON.stringify({ browser: await context.storageState({ indexedDB: true }), cartKey, prior: saved?.prior ?? entry.visitGeneration }))
  } finally {
    await browser.close()
  }
}

async function checkCheckout(engine) {
  const browser = await engine.launch()
  const requestPath = '**/api/guest/order/add-batch'
  const isSubmit = response => new URL(response.url()).pathname === '/api/guest/order/add-batch'
  const addItem = async page => {
    await page.goto(new URL('/miniapp/?mode=guest&screen=menu&table_token=availability-token&tableSessionId=301', origin).href)
    await page.getByRole('button', { name: /Кальянное меню/ }).first().click()
    await page.getByRole('button', { name: 'Добавить', exact: true }).first().click()
    await page.getByRole('button', { name: 'Корзина (1)', exact: true }).first().click()
  }
  const selectBill = async page => {
    const bill = page.getByRole('combobox', { name: 'Выберите счёт' })
    const send = page.getByRole('button', { name: 'Отправить', exact: true })
    await expect.poll(async () => await send.isEnabled() || (await bill.isVisible() && await bill.locator('option[value="402"]').count() > 0)).toBe(true)
    if (await bill.isVisible()) await bill.selectOption('402')
    await expect(send).toBeEnabled()
  }
  try {
    const firstView = await newWebView(browser)
    const page = await firstView.newPage()
    await addItem(page)
    await selectBill(page)
    let original, firstBatch, signalCommit, releaseReply
    const committed = new Promise(resolve => { signalCommit = resolve })
    const pendingReply = new Promise(resolve => { releaseReply = resolve })
    await page.route(requestPath, async route => {
      original = route.request().postDataJSON()
      const reply = await route.fetch()
      expect(reply.status()).toBe(200)
      firstBatch = (await reply.json()).batchId
      expect(firstBatch).toBeGreaterThan(0)
      signalCommit()
      await pendingReply
      await route.abort()
    })
    const lost = page.waitForEvent('requestfailed', isSubmit)
    await page.getByRole('button', { name: 'Отправить', exact: true }).click()
    await committed
    // A second live window can act before the authoring window knows the result.
    const peer = await firstView.newPage()
    await peer.goto(new URL('/miniapp/?mode=guest', origin).href)
    await peer.getByRole('button', { name: 'Корзина (1)', exact: true }).first().click()
    let peerBatch, peerPayload
    await peer.route(requestPath, async route => {
      peerPayload = route.request().postDataJSON()
      const response = await route.fetch()
      peerBatch = (await response.json()).batchId
      await route.abort()
    })
    const lostPeer = peer.waitForEvent('requestfailed', isSubmit)
    await peer.getByRole('button', { name: 'Повторить исходную отправку' }).click()
    await lostPeer
    expect(peerPayload).toEqual(original)
    expect(peerBatch).toBe(firstBatch)
    releaseReply()
    await lost
    const persisted = await firstView.storageState({ indexedDB: true })
    await firstView.close()
    const fresh = await newWebView(browser, persisted)
    const next = await fresh.newPage()
    const requests = []
    next.on('request', request => { if (isSubmit(request)) requests.push(request.postDataJSON()) })
    await next.goto(new URL('/miniapp/?mode=guest', origin).href)
    await next.getByRole('button', { name: 'Корзина (1)', exact: true }).first().click()
    const retry = next.getByRole('button', { name: 'Повторить исходную отправку' })
    await expect(retry).toBeEnabled()
    expect(requests).toHaveLength(0)
    const input = next.locator('.cart-item .qty-input')
    await input.fill('2')
    await input.press('Enter')
    await expect(input).toHaveValue('2')
    await expect(retry).toBeEnabled()
    const replay = next.waitForResponse(isSubmit)
    await retry.click()
    expect((await (await replay).json()).batchId).toBe(firstBatch)
    expect(requests).toEqual([original])
    await expect(retry).toBeHidden()
    // Acknowledging the first order must leave the extra item available as a new order.
    await next.goto(new URL('/miniapp/?mode=guest', origin).href)
    await next.getByRole('button', { name: 'Корзина (1)', exact: true }).first().click()
    await selectBill(next)
    const other = await fresh.newPage()
    await other.goto(new URL('/miniapp/?mode=guest', origin).href)
    await other.getByRole('button', { name: 'Корзина (1)', exact: true }).first().click()
    await selectBill(other)
    const deliveries = []
    let release, started
    const held = new Promise(resolve => { release = resolve })
    const submitted = new Promise(resolve => { started = resolve })
    await fresh.route(requestPath, async route => {
      const body = route.request().postDataJSON()
      const response = await route.fetch()
      deliveries.push({ body, batchId: (await response.json()).batchId })
      started()
      await held
      await route.fulfill({ response })
    })
    // Both tabs act on the same displayed preview before either can see an acknowledgment.
    await Promise.all([next, other].map(view =>
      view.getByRole('button', { name: 'Отправить', exact: true }).evaluate(button => button.click())))
    await submitted
    release()
    await expect.poll(async () => (await next.getByRole('button', { name: 'Корзина', exact: true }).first().isVisible()) ||
      (await other.getByRole('button', { name: 'Корзина', exact: true }).first().isVisible())).toBe(true)
    const [submittedOrder] = deliveries
    expect(submittedOrder.batchId).toBeGreaterThan(firstBatch)
    expect(submittedOrder.body.idempotencyKey).not.toBe(original.idempotencyKey)
    expect(submittedOrder.body.items).toEqual(original.items)
    for (const delivery of deliveries) expect(delivery).toEqual(submittedOrder)
    await next.goto(new URL('/miniapp/?mode=guest', origin).href)
    await expect(next.getByRole('button', { name: 'Корзина', exact: true }).first()).toBeVisible()
  } finally { await browser.close() }
}

async function checkExtension(engine, staffToken) {
  const browser = await engine.launch()
  const endpoint = '**/api/guest/table/extension-requests'
  const openExtension = async (page, first) => {
    const url = first ? '/miniapp/?mode=guest&screen=menu&table_token=availability-token&tableSessionId=301' :
      '/miniapp/?mode=guest'
    await page.goto(new URL(url, origin).href)
    await page.getByRole('button', { name: /Продление работы заведения/ }).first().click()
  }
  try {
    const attempts = await Promise.all([0, 1].map(async () => {
      const view = await newWebView(browser)
      const page = await view.newPage()
      const attempt = { view, page }
      await page.route(endpoint, async route => {
        attempt.payload = route.request().postDataJSON()
        const committed = await route.fetch()
        expect(committed.status()).toBe(200)
        attempt.requestId = (await committed.json()).request.id
        expect(attempt.requestId).toBeGreaterThan(0)
        await route.abort()
      })
      await openExtension(page, true)
      await expect(page.getByRole('button', { name: 'Продлить на 1 час' })).toBeEnabled()
      return attempt
    }))
    await Promise.all(attempts.map(async attempt => {
      await attempt.page.getByRole('button', { name: 'Продлить на 1 час' }).click()
      await expect(attempt.page.getByRole('button', { name: 'Повторить исходный запрос' })).toBeEnabled()
      attempt.state = await attempt.view.storageState({ indexedDB: true })
      await attempt.view.close()
    }))
    const [first, second] = attempts
    expect(first.requestId).toBe(second.requestId)
    expect(first.payload.idempotencyKey).not.toBe(second.payload.idempotencyKey)

    const staff = await newWebView(browser)
    const approvalPath = `/api/venue/101/shift-extension-requests/${first.requestId}/approve`
    const approved = await staff.request.post(new URL(approvalPath, origin).href,
      { headers: { authorization: 'Bearer ' + staffToken } })
    expect(approved.status()).toBe(200)
    expect((await approved.json()).applied).toBe(true)
    await staff.close()
    await Promise.all(attempts.map(async attempt => {
      const reopened = await newWebView(browser, attempt.state)
      const next = await reopened.newPage()
      let calls = 0
      await next.route(endpoint, async route => {
        calls++
        expect(route.request().postDataJSON()).toEqual(attempt.payload)
        const response = await route.fetch()
        expect(response.status()).toBe(200)
        const { request } = await response.json()
        expect(request.id).toBe(attempt.requestId)
        expect(request.status).toBe('approved')
        await route.fulfill({ response })
      })
      await openExtension(next, false)
      const retry = next.getByRole('button', { name: 'Повторить исходный запрос' })
      await expect(retry).toBeEnabled()
      expect(calls).toBe(0)
      await retry.click()
      await expect(next.getByText(/Сумма добавлена в счёт/)).toBeVisible()
      expect(calls).toBe(1)
      await reopened.close()
    }))
  } finally { await browser.close() }
}

;(async () => {
  if (process.argv[3] === 'extension') return checkExtension(process.argv[4] === 'webkit' ? webkit : chromium, process.argv[5])
  if (process.argv[3] === 'checkout') return checkCheckout(process.argv[4] === 'webkit' ? webkit : chromium)
  if (process.argv[4]) {
    await checkCommittedResponseLoss(process.argv[3], process.argv[4] === 'chromium' ? chromium : webkit, process.argv[5])
    return
  }
  await checkLaunch(chromium)
  await checkLaunch(webkit)
})().catch(error => {
  console.error(error)
  process.exitCode = 1
})
