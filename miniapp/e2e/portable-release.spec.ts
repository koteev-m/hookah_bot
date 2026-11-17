import { expect, test as base, type BrowserContext, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { createServer as createHttpServer } from 'node:http'
import { createServer as createHttpsServer } from 'node:https'
import { connect } from 'node:net'
import type { Duplex } from 'node:stream'
import { execFileSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { createServer } from 'vite'
import { infoMediaPdf } from './fixtures/info-media'

const origins = { staging: 'https://staging.hookah.test', production: 'https://production.hookah.test' }
type RedirectTransport = { proxy: string; received: number }
// WebKit cannot fulfill intercepted 3xx responses. Send only those requests through local TLS.
const test = base.extend<{}, { redirectTransport: RedirectTransport }>({
  redirectTransport: [async ({}, use) => {
    const directory = mkdtempSync(join(tmpdir(), 'hookah-portable-tls-'))
    const key = join(directory, 'key.pem'), cert = join(directory, 'cert.pem')
    execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key,
      '-out', cert, '-days', '1', '-subj', '/CN=hookah.test'], { stdio: 'ignore' })
    const transport = { proxy: '', received: 0 }
    const tls = createHttpsServer({ key: readFileSync(key), cert: readFileSync(cert) }, (request, response) => {
      transport.received++
      const host = request.headers.host?.split(':')[0]
      const target = Object.values(origins).find(origin => new URL(origin).hostname !== host)!
      response.writeHead(307, { Location: `${target}${request.url}` })
      response.end()
    })
    const sockets = new Set<Duplex>()
    const proxy = createHttpServer((_request, response) => { response.writeHead(403); response.end() })
    try {
      await new Promise<void>(resolve => tls.listen(0, '127.0.0.1', resolve))
      const address = tls.address()
      if (!address || typeof address === 'string') throw new Error('Missing local TLS address')
      proxy.on('connect', (request, socket, head) => {
        if (!Object.values(origins).some(origin => request.url === `${new URL(origin).hostname}:443`)) { socket.destroy(); return }
        const upstream = connect(address.port, '127.0.0.1', () => {
          socket.write('HTTP/1.1 200 Connection Established\r\n\r\n')
          if (head.length) upstream.write(head)
          socket.pipe(upstream); upstream.pipe(socket)
        })
        sockets.add(upstream); sockets.add(socket)
        upstream.on('error', () => socket.destroy())
        socket.on('error', () => upstream.destroy())
        upstream.on('close', () => sockets.delete(upstream))
        socket.on('close', () => { sockets.delete(socket); upstream.destroy() })
      })
      await new Promise<void>(resolve => proxy.listen(0, '127.0.0.1', resolve))
      const proxyAddress = proxy.address()
      if (!proxyAddress || typeof proxyAddress === 'string') throw new Error('Missing local proxy address')
      transport.proxy = `http://127.0.0.1:${proxyAddress.port}`
      await use(transport)
    } finally {
      for (const socket of sockets) socket.destroy()
      tls.closeAllConnections(); proxy.closeAllConnections()
      await Promise.all([new Promise<void>(resolve => tls.close(() => resolve())), new Promise<void>(resolve => proxy.close(() => resolve()))])
      rmSync(directory, { recursive: true, force: true })
    }
  }, { scope: 'worker' }],
  proxy: async ({ redirectTransport }, use) => {
    await use({ server: redirectTransport.proxy, bypass: '127.0.0.1,localhost,[::1]' })
  }
})
const dist = resolve('dist')
const assets = new Map<string, Buffer>()
function readAssets(directory = dist) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name)
    if (entry.isDirectory()) readAssets(path)
    else assets.set(`/miniapp/${path.slice(dist.length + 1)}`, readFileSync(path))
  }
}
readAssets()
const inventory = Object.fromEntries([...assets].map(([path, bytes]) => [path, createHash('sha256').update(bytes).digest('hex')]))
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/luzRygAAAABJRU5ErkJggg==', 'base64')
type Seen = { origin: string; path: string; authorization: string | undefined }

async function serveRelease(context: BrowserContext, options: { auth?: 'denied' | 'offline' | 'redirect'; launch?: boolean; redirectPaths?: string[] } = {}) {
  const seen: Seen[] = [], escaped: string[] = []
  const generations = new Map<string, number>()
  await context.addInitScript(({ launch }) => {
    const id = Number(sessionStorage.getItem('synthetic-user') ?? 7001)
    Object.assign(window, { Telegram: { WebApp: {
      initData: launch ? new URLSearchParams({ user: JSON.stringify({ id }), hash: 'synthetic-not-server-evidence' }).toString() : '',
      ready() {}, expand() {}, BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} }
    } } })
  }, { launch: options.launch !== false })
  await context.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url())
    if (url.href === 'https://telegram.org/js/telegram-web-app.js') {
      return route.fulfill({ contentType: 'text/javascript', body: '' })
    }
    if (!Object.values(origins).includes(url.origin)) {
      escaped.push(url.href)
      return route.abort()
    }
    if (url.pathname.startsWith('/api/')) {
      seen.push({ origin: url.origin, path: url.pathname, authorization: request.headers().authorization })
      const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
      if (options.redirectPaths?.includes(url.pathname)) return route.continue()
      if (url.pathname === '/api/auth/telegram') {
        if (options.auth === 'redirect') return route.continue()
        if (options.auth === 'offline') return route.abort()
        if (options.auth === 'denied') return json({ error: { code: 'INITDATA_INVALID' } }, 401)
        const id = JSON.parse(new URLSearchParams(request.postDataJSON().initData).get('user')!).id
        const key = `${url.origin}:${id}`, generation = (generations.get(key) ?? 0) + 1
        generations.set(key, generation)
        return json({ token: `${new URL(url.origin).hostname}:${id}:${generation}`, expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 })
      }
      if (url.pathname.endsWith('/media/1') || url.pathname.includes('/staff/4/photos/')) return route.fulfill({ contentType: 'image/png', body: png })
      if (url.pathname.endsWith('/media/2')) return route.fulfill({ contentType: 'application/pdf', body: infoMediaPdf(1) })
      if (url.pathname === '/api/venue/me') return json({ userId: 7001, venues: [{ venueId: 1, venueName: 'Пилот', venueStatus: 'PUBLISHED', role: 'OWNER', permissions: ['ORDER_QUEUE_VIEW'] }] })
      if (url.pathname === '/api/platform/me') return json({ ok: true, ownerUserId: 7001 })
      if (url.pathname === '/api/platform/venues') return json({ venues: [] })
      if (url.pathname === '/api/venue/orders/queue') return json({ items: [], nextCursor: null })
      if (url.pathname === '/api/guest/catalog') return json({ venues: [] })
      if (url.pathname === '/api/guest/table/restore') return json({ context: null })
      if (url.pathname === '/api/guest/venue/1') return json({ venue: { id: 1, name: 'Пилот', city: 'Тест', status: 'PUBLISHED', isFavorite: false, todayStaff: [{ id: 4, displayName: 'Сотрудник', subtype: 'waiter', shiftDate: '2026-11-17', photoUrl: '/api/guest/venue/1/staff/4/photos/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' }], promotions: [] } })
      if (url.pathname === '/api/guest/venue/1/info-sections') {
        const foreign = Object.values(origins).find(origin => origin !== url.origin)
        return json({ venueId: 1, sections: [{ id: 1, type: 'MENU', title: 'Материалы', displayTitle: 'Материалы', media: [
          { id: 1, mediaType: 'image', sortOrder: 0, url: `${url.origin}/api/guest/venue/1/info-sections/1/media/1` },
          { id: 2, mediaType: 'pdf', sortOrder: 1, url: '/api/guest/venue/1/info-sections/1/media/2' },
          { id: 3, mediaType: 'image', sortOrder: 2, url: `${foreign}/api/guest/venue/1/info-sections/1/media/3` }
        ] }] })
      }
      return json({ error: { code: 'FORBIDDEN' } }, 403)
    }
    const path = url.pathname === '/miniapp/' ? '/miniapp/index.html' : url.pathname
    const body = assets.get(path)
    if (!body) return route.fulfill({ status: 404, body: 'Missing release asset' })
    const contentType = /\.m?js$/.test(path) ? 'text/javascript' : path.endsWith('.css') ? 'text/css' : path.endsWith('.html') ? 'text/html' : 'application/octet-stream'
    return route.fulfill({ contentType, body })
  })
  return { seen, escaped, generations }
}

function expectScoped(seen: Seen[], origin: string) {
  expect(seen.length).toBeGreaterThan(0)
  expect([...new Set(seen.map(request => request.origin))]).toEqual([origin])
  for (const request of seen.filter(request => request.authorization)) {
    expect(request.authorization).toMatch(new RegExp(`^Bearer ${new URL(origin).hostname}:`))
  }
}

for (const [environment, origin] of Object.entries(origins)) {
  for (const mode of ['guest', 'venue', 'platform']) {
    test(`release ${environment} ${mode} uses its own API origin`, async ({ context, page }, info) => {
      const api = await serveRelease(context)
      const errors: string[] = []
      page.on('pageerror', error => errors.push(error.message))
      const path = mode === 'guest' ? '/api/guest/catalog' : mode === 'venue' ? '/api/venue/orders/queue' : '/api/platform/venues'
      await page.goto(`${origin}/miniapp/?mode=${mode}&venueId=1&backendUrl=https://attacker.invalid&VITE_BACKEND_PUBLIC_URL=http://localhost:8080#/${mode === 'guest' ? 'catalog' : mode === 'venue' ? 'orders' : 'venues'}`)
      await expect.poll(() => api.seen.some(request => request.path === path)).toBe(true)
      expectScoped(api.seen, origin)
      expect(api.escaped).toEqual([])
      expect(errors).toEqual([])
      await info.attach('release-assets.json', { body: JSON.stringify({ origin, inventory }), contentType: 'application/json' })
    })
  }

  test(`release ${environment} media and PDF stay scoped to their origin`, async ({ context, page }) => {
    const api = await serveRelease(context)
    await page.goto(`${origin}/miniapp/?mode=guest#/venue/1?media=0.0`)
    await page.locator('.venue-info-media-pdf').scrollIntoViewIfNeeded()
    await expect(page.locator('.info-media-preview canvas').first()).toHaveAttribute('aria-label', /страница 1 из 1/)
    await expect.poll(() => api.seen.some(request => request.path.endsWith('/media/1'))).toBe(true)
    await expect.poll(() => api.seen.some(request => request.path.endsWith('/media/2'))).toBe(true)
    await expect.poll(() => api.seen.some(request => request.path.includes('/staff/4/photos/') && request.authorization)).toBeTruthy()
    expect(api.seen.some(request => request.path.endsWith('/media/3'))).toBe(false)
    expectScoped(api.seen, origin)
    expect(api.escaped).toEqual([])
  })

  for (const auth of ['denied', 'offline', 'redirect'] as const) {
    test(`release ${environment} ${auth} authentication never falls back`, async ({ context, page, redirectTransport }) => {
      const before = redirectTransport.received
      const api = await serveRelease(context, { auth })
      await page.goto(`${origin}/miniapp/?mode=guest`)
      await expect(page.locator('.error-actions button').first()).toBeVisible()
      expect(api.seen.map(request => request.path)).toEqual(['/api/auth/telegram'])
      expectScoped(api.seen, origin)
      expect(api.escaped).toEqual([])
      expect(redirectTransport.received - before).toBe(auth === 'redirect' ? 1 : 0)
    })
  }

  test(`release ${environment} missing initData sends no authentication`, async ({ context, page }) => {
    const api = await serveRelease(context, { launch: false })
    await page.goto(`${origin}/miniapp/?mode=guest&backendUrl=${origins.staging}`)
    await expect(page.getByText('Нет Telegram-авторизации', { exact: true })).toBeVisible()
    expect(api.seen).toEqual([])
    expect(api.escaped).toEqual([])
  })

  test(`release ${environment} API and binary redirects never cross origins`, async ({ context, page, redirectTransport }) => {
    const before = redirectTransport.received
    const photo = '/api/guest/venue/1/staff/4/photos/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
    const api = await serveRelease(context, { redirectPaths: ['/api/guest/catalog', photo] })
    const failedCatalog = page.waitForEvent('requestfailed', request => new URL(request.url()).pathname === '/api/guest/catalog')
    await page.goto(`${origin}/miniapp/?mode=guest#/catalog`)
    await failedCatalog
    await expect(page.locator('.error-actions button').first()).toBeVisible()
    const failedPhoto = page.waitForEvent('requestfailed', request => new URL(request.url()).pathname === photo)
    await page.goto(`${origin}/miniapp/?mode=guest#/venue/1?media=0.0`)
    await failedPhoto
    expectScoped(api.seen, origin)
    expect(api.escaped).toEqual([])
    expect(redirectTransport.received - before).toBe(2)
  })
}

test('release sessions refresh and remain isolated by origin and account', async ({ context, page }) => {
  const api = await serveRelease(context)
  for (const origin of [origins.staging, origins.production, origins.staging]) {
    await page.goto(`${origin}/miniapp/?mode=guest#/catalog`)
    await expect(page.locator('.guest-shell')).toBeVisible()
    await expect.poll(() => api.seen.filter(request => request.origin === origin && request.path === '/api/guest/catalog').length).toBeGreaterThan(0)
    const keys = await page.evaluate(() => Object.keys(sessionStorage).filter(key => key.startsWith('hookah_session_token:')))
    expect(keys).toEqual([`hookah_session_token:${new URL(origin).host}:user:7001`])
  }
  expect(api.generations.get(`${origins.staging}:7001`)).toBe(1)
  expect(api.generations.get(`${origins.production}:7001`)).toBe(1)
  await page.evaluate(() => {
    for (const key of Object.keys(sessionStorage).filter(key => key.startsWith('hookah_session_token:'))) {
      const session = JSON.parse(sessionStorage.getItem(key)!)
      session.expiresAtEpochSeconds = 1
      sessionStorage.setItem(key, JSON.stringify(session))
    }
  })
  await page.reload()
  await expect.poll(() => api.generations.get(`${origins.staging}:7001`)).toBe(2)
  await page.evaluate(() => sessionStorage.setItem('synthetic-user', '7002'))
  await page.reload()
  await expect.poll(() => api.generations.get(`${origins.staging}:7002`)).toBe(1)
  await expect.poll(() => api.seen.some(request => request.authorization === 'Bearer staging.hookah.test:7002:1')).toBe(true)
  const refreshed = api.seen.filter(request => request.origin === origins.staging && request.authorization)
  expect(refreshed.some(request => request.authorization === 'Bearer staging.hookah.test:7001:2')).toBe(true)
  expect(refreshed.some(request => request.authorization === 'Bearer staging.hookah.test:7002:1')).toBe(true)
  for (const origin of Object.values(origins)) expectScoped(api.seen.filter(request => request.origin === origin), origin)
  expect(api.escaped).toEqual([])
})

test('release assets contain no configured backend fallback', () => {
  const code = [...assets].filter(([path]) => /\.m?js$/.test(path)).map(([, bytes]) => bytes.toString()).join('\n')
  expect(code).not.toContain('VITE_BACKEND_PUBLIC_URL')
  expect(code).not.toContain('https://staging.hookahtootah.club')
  expect(code).not.toContain('http://localhost:8080')
  expect(code).not.toContain('https://unexpected-api.invalid')
})

async function devOrigin(page: Page, configured: string | undefined, authenticate = false) {
  const server = await createServer({
    configFile: false, root: process.cwd(), base: '/miniapp/',
    define: { 'import.meta.env.VITE_BACKEND_PUBLIC_URL': configured === undefined ? 'undefined' : JSON.stringify(configured) },
    server: { middlewareMode: true, hmr: false }
  })
  const frontend = createHttpServer((request, response) => {
    if (request.url === '/miniapp/origin-test') {
      response.setHeader('Content-Type', 'text/html')
      response.end('<div id="result"></div>')
    } else server.middlewares(request, response)
  })
  try {
    await new Promise<void>(resolve => frontend.listen(0, '127.0.0.1', resolve))
    const address = frontend.address()
    if (!address || typeof address === 'string') throw new Error('Missing local Vite address')
    await page.goto(`http://127.0.0.1:${address.port}/miniapp/origin-test`)
    return await page.evaluate(async authenticate => {
      const path = '/miniapp/src/shared/api/backend.ts'
      try {
        const origin = (await import(path)).getBackendBaseUrl()
        if (authenticate) {
          const authPath = '/miniapp/src/shared/api/auth.ts'
          await (await import(authPath)).authenticate('synthetic-dev-initdata')
        }
        return { origin, error: false }
      }
      catch (error) { if (authenticate) throw error; return { origin: null, error: true } }
    }, authenticate)
  } finally {
    frontend.closeAllConnections()
    await new Promise<void>(resolve => frontend.close(() => resolve()))
    await server.close()
  }
}

test('dev supports distinct frontend and backend localhost ports', async ({ page }) => {
  const seen: string[] = []
  const backend = createHttpServer((request, response) => {
    response.setHeader('Access-Control-Allow-Origin', request.headers.origin!)
    response.setHeader('Access-Control-Allow-Headers', 'Content-Type')
    if (request.method === 'OPTIONS') { response.end(); return }
    seen.push(`${request.method} ${request.url}`)
    response.setHeader('Content-Type', 'application/json')
    response.end(JSON.stringify({ token: 'synthetic-dev-session', expiresAtEpochSeconds: 4102444800 }))
  })
  await new Promise<void>(resolve => backend.listen(0, '127.0.0.1', resolve))
  try {
    const address = backend.address()
    if (!address || typeof address === 'string') throw new Error('Missing local API address')
    const origin = `http://127.0.0.1:${address.port}`
    expect(await devOrigin(page, `${origin}/`, true)).toEqual({ origin, error: false })
    expect(seen).toEqual(['POST /api/auth/telegram'])
  } finally { backend.closeAllConnections(); await new Promise<void>(resolve => backend.close(() => resolve())) }
})

test('dev without override keeps localhost backend', async ({ page }) => {
  expect(await devOrigin(page, undefined)).toEqual({ origin: 'http://localhost:8080', error: false })
})

test('dev accepts explicit HTTPS tunnel origin', async ({ page }) => {
  expect(await devOrigin(page, 'https://dev.hookah.test')).toEqual({ origin: 'https://dev.hookah.test', error: false })
})

for (const [label, configured] of Object.entries({ remoteHttp: 'http://attacker.invalid', credentials: 'https://user:pass@attacker.invalid', path: 'https://attacker.invalid/api', malformed: 'not-an-origin' })) {
  test(`dev rejects invalid backend origin ${label}`, async ({ page }) => {
    const api: string[] = []
    page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/')) api.push(request.url()) })
    expect(await devOrigin(page, configured)).toEqual({ origin: null, error: true })
    expect(api).toEqual([])
  })
}
