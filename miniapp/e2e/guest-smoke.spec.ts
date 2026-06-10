import { expect, type Page, test } from '@playwright/test'

const sessionExpiresAt = Math.floor(Date.now() / 1000) + 3600
const tableToken = 'TABLE-SMOKE-1'
const mockInitData = 'query_id=e2e-smoke&user=%7B%22id%22%3A123456789%7D&hash=test'
const otherMockInitData = 'query_id=e2e-smoke-other&user=%7B%22id%22%3A987654321%7D&hash=test'
const transparentPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/luzRygAAAABJRU5ErkJggg==',
  'base64'
)

type RestoreContext = {
  tableToken: string
  tabId: number
  venueId: number
  venueName: string
  tableId: number
  tableSessionId: number
  tableSessionStatus: string
  tableSessionActive: boolean
  tableNumber: string
  venueStatus: string
  subscriptionStatus: string
  available: boolean
  unavailableReason: string | null
}

type ShiftExtensionRequest = {
  id: number
  venueId: number
  tableSessionId: number
  tableId: number
  tableNumber: string | null
  tabId: number
  orderId: number
  requestedByUserId: number
  status: string
  durationMinutes: number
  priceMinor: number
  currency: string
  currentOrderableUntil: string
  requestedUntil: string
  comment: string | null
  decidedByUserId: number | null
  decidedAt: string | null
  rejectReason: string | null
  createdAt: string
  updatedAt: string
}

type ShiftExtensionOptions = {
  available: boolean
  unavailableReason?: string | null
  durationMinutes?: number | null
  priceMinor?: number | null
  currency?: string | null
  tableSessionId?: number | null
  tabId?: number | null
  orderId?: number | null
  currentOrderableUntil?: string | null
  proposedOrderableUntil?: string | null
  pendingRequest?: ShiftExtensionRequest | null
}

type ServiceCharge = {
  id: number
  source: string
  sourceRequestId: number | null
  label: string
  qty: number
  unitPriceMinor: number
  totalMinor: number
  currency: string
}

type ShiftExtensionSettings = {
  venueId: number
  enabled: boolean
  durationMinutes: number
  priceMinor: number | null
  priceRub: string | null
  currency: string
  maxExtensionsPerSession: number | null
  configured: boolean
}

type TestTelegramWindow = Window & {
  Telegram?: {
    WebApp?: {
      initData?: string
      initDataUnsafe?: {
        user?: {
          id?: number
        }
      }
      ready?: () => void
      expand?: () => void
      close?: () => void
      BackButton?: {
        show?: () => void
        hide?: () => void
        onClick?: (cb: () => void) => void
        offClick?: (cb: () => void) => void
      }
    }
  }
  __e2eTelegramBackButtonVisible?: boolean
}

function jsonResponse(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(data)
  }
}

function buildRestoreContext(overrides: Partial<RestoreContext> = {}): RestoreContext {
  return {
    tableToken,
    tabId: 88,
    venueId: 1,
    venueName: 'Микс',
    tableId: 7,
    tableSessionId: 77,
    tableSessionStatus: 'ACTIVE',
    tableSessionActive: true,
    tableNumber: '4',
    venueStatus: 'PUBLISHED',
    subscriptionStatus: 'ACTIVE',
    available: true,
    unavailableReason: null,
    ...overrides
  }
}

function buildShiftExtensionRequest(overrides: Partial<ShiftExtensionRequest> = {}): ShiftExtensionRequest {
  return {
    id: 501,
    venueId: 1,
    tableSessionId: 77,
    tableId: 7,
    tableNumber: '4',
    tabId: 88,
    orderId: 900,
    requestedByUserId: 123456789,
    status: 'pending',
    durationMinutes: 60,
    priceMinor: 300000,
    currency: 'RUB',
    currentOrderableUntil: '2026-06-09T22:00:00+03:00',
    requestedUntil: '2026-06-09T23:00:00+03:00',
    comment: null,
    decidedByUserId: null,
    decidedAt: null,
    rejectReason: null,
    createdAt: '2026-06-09T21:45:00+03:00',
    updatedAt: '2026-06-09T21:45:00+03:00',
    ...overrides
  }
}

function buildShiftExtensionOptions(overrides: Partial<ShiftExtensionOptions> = {}): ShiftExtensionOptions {
  return {
    available: false,
    unavailableReason: 'EXTENSION_NOT_CONFIGURED',
    tableSessionId: 77,
    tabId: 88,
    orderId: 900,
    pendingRequest: null,
    ...overrides
  }
}

function buildShiftExtensionSettings(overrides: Partial<ShiftExtensionSettings> = {}): ShiftExtensionSettings {
  return {
    venueId: 1,
    enabled: false,
    durationMinutes: 60,
    priceMinor: null,
    priceRub: null,
    currency: 'RUB',
    maxExtensionsPerSession: null,
    configured: false,
    ...overrides
  }
}

async function installTelegramWebApp(page: Page, userId: number) {
  await page.addInitScript({
    content: `
      (() => {
        const defaultInitData = ${JSON.stringify(mockInitData)};
        const defaultUserId = ${JSON.stringify(userId)};
        const storedUserId = Number(window.localStorage.getItem('__e2e_telegram_user_id'));
        const nextUserId = Number.isFinite(storedUserId) && storedUserId > 0 ? storedUserId : defaultUserId;
        const nextInitData = window.localStorage.getItem('__e2e_telegram_init_data') || defaultInitData;
        const backCallbacks = [];
        window.__e2eTelegramBackButtonVisible = false;
        const telegramApi = {
          WebApp: {
            initData: nextInitData,
            initDataUnsafe: { user: { id: nextUserId } },
            ready: () => undefined,
            expand: () => undefined,
            close: () => undefined,
            BackButton: {
              show: () => {
                window.__e2eTelegramBackButtonVisible = true;
              },
              hide: () => {
                window.__e2eTelegramBackButtonVisible = false;
              },
              onClick: (callback) => {
                backCallbacks.push(callback);
              },
              offClick: (callback) => {
                const index = backCallbacks.indexOf(callback);
                if (index >= 0) {
                  backCallbacks.splice(index, 1);
                }
              }
            }
          }
        };
        window.Telegram = telegramApi;
        try {
          Object.defineProperty(window, 'Telegram', {
            value: telegramApi,
            configurable: true,
            writable: true
          });
        } catch {
          window.Telegram = telegramApi;
        }
      })();
    `
  })
}

async function clickTelegramBackButton(page: Page) {
  await page.evaluate(() => {
    window.dispatchEvent(new Event('hookah:e2e-telegram-back'))
  })
}

async function expectTelegramBackButtonHidden(page: Page) {
  await expect
    .poll(async () => page.evaluate(() => Boolean((window as TestTelegramWindow).__e2eTelegramBackButtonVisible)))
    .toBe(false)
}

async function mockGuestApi(
  page: Page,
  options: { restoreContext?: RestoreContext | null; extensionOptions?: ShiftExtensionOptions | null } = {}
) {
  let structuredMenuCalls = 0
  let restoreContext = options.restoreContext ?? null
  let extensionOptions = options.extensionOptions ?? buildShiftExtensionOptions()
  let createExtensionRequestCalls = 0
  let activeOrderServiceCharges: ServiceCharge[] = []

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-session-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })

  await page.route('**/api/guest/catalog', async (route) => {
    await route.fulfill(
      jsonResponse({
        venues: [
          {
            id: 1,
            name: 'Микс',
            city: 'Москва',
            address: 'Пилотная, 1',
            cardDescription: 'Тестовая карточка'
          }
        ]
      })
    )
  })

  await page.route('**/api/guest/venue/1', async (route) => {
    await route.fulfill(
      jsonResponse({
        venue: {
          id: 1,
          name: 'Микс',
          city: 'Москва',
          address: 'Пилотная, 1',
          guestContact: '+7 000 000-00-00',
          cardDescription: 'Текстовая информация о заведении',
          status: 'PUBLISHED'
        }
      })
    )
  })

  await page.route('**/api/guest/venue/1/info-sections', async (route) => {
    await route.fulfill(
      jsonResponse({
        venueId: 1,
        sections: [
          {
            id: 10,
            type: 'menu',
            title: 'Меню',
            displayTitle: '📖 Фото-меню',
            text: 'Ознакомительное меню в карточке',
            mediaCount: 1,
            media: [
              {
                id: 100,
                mediaType: 'image',
                sortOrder: 0,
                url: '/api/guest/venue/1/info-sections/10/media/100'
              }
            ]
          }
        ]
      })
    )
  })

  await page.route('**/api/guest/venue/1/info-sections/10/media/100', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/png',
      body: transparentPng
    })
  })

  await page.route('**/api/guest/table/resolve?**', async (route) => {
    await route.fulfill(
      jsonResponse({
        venueId: 1,
        venueName: 'Микс',
        tableId: 7,
        tableSessionId: 77,
        tableSessionStatus: 'ACTIVE',
        tableSessionActive: true,
        tableNumber: '4',
        venueStatus: 'PUBLISHED',
        subscriptionStatus: 'ACTIVE',
        available: true,
        unavailableReason: null
      })
    )
  })

  await page.route('**/api/guest/table/restore', async (route) => {
    await route.fulfill(jsonResponse({ context: restoreContext }))
  })

  await page.route('**/api/guest/venue/1/menu', async (route) => {
    structuredMenuCalls += 1
    await route.fulfill(
      jsonResponse({
        venueId: 1,
        categories: [
          {
            id: 20,
            name: 'Кальянное меню',
            items: [
              {
                id: 200,
                name: 'Double Apple',
                priceMinor: 150000,
                currency: 'RUB',
                isAvailable: true
              }
            ]
          }
        ]
      })
    )
  })

  await page.route('**/api/guest/tabs?**', async (route) => {
    await route.fulfill(
      jsonResponse({
        tabs: [
          {
            id: 88,
            tableSessionId: 77,
            type: 'PERSONAL',
            ownerUserId: 123456789,
            status: 'ACTIVE'
          }
        ]
      })
    )
  })

  await page.route('**/api/guest/order/active?**', async (route) => {
    const serviceChargeTotal = activeOrderServiceCharges.reduce((sum, charge) => sum + charge.totalMinor, 0)
    await route.fulfill(
      jsonResponse({
        order: {
          orderId: 900,
          displayNumber: 123,
          venueId: 1,
          tableId: 7,
          tableSessionId: 77,
          tabId: 88,
          tableNumber: '4',
          status: 'ACTIVE',
          grossTotalMinor: 150000 + serviceChargeTotal,
          manualDiscountTotalMinor: 0,
          promoDiscountTotalMinor: 0,
          loyaltyDiscountTotalMinor: 0,
          finalPayableTotalMinor: 150000 + serviceChargeTotal,
          currency: 'RUB',
          discounts: [],
          serviceCharges: activeOrderServiceCharges,
          batches: [
            {
              batchId: 333,
              comment: null,
              items: [
                {
                  itemId: 200,
                  qty: 1,
                  name: 'Double Apple',
                  priceMinor: 150000,
                  currency: 'RUB',
                  lineGrossMinor: 150000,
                  manualDiscountMinor: 0,
                  promoDiscountMinor: 0,
                  linePayableMinor: 150000,
                  isPromotionReward: false
                }
              ]
            }
          ]
        }
      })
    )
  })

  await page.route('**/api/guest/table/extension-options?**', async (route) => {
    await route.fulfill(jsonResponse(extensionOptions))
  })

  await page.route('**/api/guest/table/extension-requests', async (route) => {
    createExtensionRequestCalls += 1
    const request = buildShiftExtensionRequest()
    extensionOptions = {
      ...buildShiftExtensionOptions({ available: true }),
      durationMinutes: request.durationMinutes,
      priceMinor: request.priceMinor,
      currency: request.currency,
      currentOrderableUntil: request.currentOrderableUntil,
      proposedOrderableUntil: request.requestedUntil,
      pendingRequest: request
    }
    await route.fulfill(jsonResponse({ request }))
  })

  return {
    getStructuredMenuCalls: () => structuredMenuCalls,
    getCreateExtensionRequestCalls: () => createExtensionRequestCalls,
    setRestoreContext: (context: RestoreContext | null) => {
      restoreContext = context
    },
    setExtensionOptions: (options: ShiftExtensionOptions) => {
      extensionOptions = options
    },
    setActiveOrderServiceCharges: (charges: ServiceCharge[]) => {
      activeOrderServiceCharges = charges
    }
  }
}

async function mockVenueShiftExtensionApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    permissions?: string[]
    settings?: ShiftExtensionSettings
  } = {}
) {
  const role = options.role ?? 'STAFF'
  const permissions = options.permissions ?? ['ORDER_QUEUE_VIEW', 'SHIFT_EXTENSION_VIEW', 'SHIFT_EXTENSION_CONFIRM']
  let requests = [buildShiftExtensionRequest()]
  let settings = options.settings ?? buildShiftExtensionSettings()
  let approveCalls = 0
  let rejectCalls = 0
  let updateSettingsCalls = 0
  const rejectedReasons: string[] = []

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-session-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })

  await page.route('**/api/venue/me', async (route) => {
    await route.fulfill(
      jsonResponse({
        userId: 123456789,
        venues: [
          {
            venueId: 1,
            venueName: 'Микс',
            venueCity: 'Москва',
            venueStatus: 'PUBLISHED',
            role,
            permissions
          }
        ]
      })
    )
  })

  await page.route('**/api/guest/venue/1', async (route) => {
    await route.fulfill(
      jsonResponse({
        venue: {
          id: 1,
          name: 'Микс',
          city: 'Москва',
          address: 'Пилотная, 1',
          status: 'PUBLISHED'
        }
      })
    )
  })

  await page.route('**/api/venue/1/staff-calls**', async (route) => {
    await route.fulfill(jsonResponse({ items: [] }))
  })

  await page.route('**/api/venue/1/shift-extension-settings', async (route) => {
    if (route.request().method() === 'PUT') {
      updateSettingsCalls += 1
      const body = (await route.request().postDataJSON()) as {
        enabled: boolean
        durationMinutes: number
        priceMinor?: number | null
        currency?: string | null
        maxExtensionsPerSession?: number | null
      }
      settings = {
        venueId: 1,
        enabled: body.enabled,
        durationMinutes: body.durationMinutes,
        priceMinor: body.priceMinor ?? null,
        priceRub: body.priceMinor == null ? null : String(body.priceMinor / 100),
        currency: body.currency ?? 'RUB',
        maxExtensionsPerSession: body.maxExtensionsPerSession ?? null,
        configured: body.enabled && body.priceMinor != null
      }
    }
    await route.fulfill(jsonResponse({ settings }))
  })

  await page.route('**/api/venue/1/shift-extension-requests**', async (route) => {
    const url = route.request().url()
    const approveMatch = url.match(/shift-extension-requests\/(\d+)\/approve/)
    if (approveMatch) {
      approveCalls += 1
      const requestId = Number(approveMatch[1])
      const request = requests.find((item) => item.id === requestId) ?? buildShiftExtensionRequest({ id: requestId })
      requests = requests.filter((item) => item.id !== requestId)
      await route.fulfill(jsonResponse({ request: { ...request, status: 'approved' }, applied: true }))
      return
    }
    const rejectMatch = url.match(/shift-extension-requests\/(\d+)\/reject/)
    if (rejectMatch) {
      rejectCalls += 1
      const requestId = Number(rejectMatch[1])
      const body = (await route.request().postDataJSON()) as { reasonText?: string | null }
      rejectedReasons.push(body.reasonText ?? '')
      const request = requests.find((item) => item.id === requestId) ?? buildShiftExtensionRequest({ id: requestId })
      requests = requests.filter((item) => item.id !== requestId)
      await route.fulfill(jsonResponse({ request: { ...request, status: 'rejected', rejectReason: body.reasonText }, applied: true }))
      return
    }
    await route.fulfill(jsonResponse({ items: requests }))
  })

  return {
    getApproveCalls: () => approveCalls,
    getRejectCalls: () => rejectCalls,
    getUpdateSettingsCalls: () => updateSettingsCalls,
    getSettings: () => settings,
    getRejectedReasons: () => rejectedReasons,
    setRequests: (nextRequests: ShiftExtensionRequest[]) => {
      requests = nextRequests
    }
  }
}

test('pre-QR guest card shows info/photo menu and hides structured order menu', async ({ page }) => {
  const api = await mockGuestApi(page)

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByRole('heading', { name: 'Hookah Mini App' })).toBeVisible()
  await page.getByRole('button', { name: 'Открыть карточку' }).click()

  await expect(page.getByRole('heading', { name: 'Микс' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'ℹ️ Информация' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '📖 Фото-меню' })).toBeVisible()
  await expect(page.getByText('Заказное меню и корзина доступны после сканирования QR-кода на столе.')).toBeVisible()
  await expect(page.getByAltText('📖 Фото-меню 1')).toBeVisible()
  await expect(page.getByText('Кальянное меню')).toHaveCount(0)
  expect(api.getStructuredMenuCalls()).toBe(0)
})

test('table context opens category-first order menu and cart action', async ({ page }) => {
  await mockGuestApi(page)

  await page.goto(`?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await page.getByRole('button', { name: 'Меню', exact: true }).click()

  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await expect(page.getByText('Double Apple')).toHaveCount(0)
  await page.getByRole('button', { name: /Кальянное меню/ }).click()

  await expect(page.getByRole('heading', { name: 'Кальянное меню' })).toBeVisible()
  await expect(page.getByText('Double Apple')).toBeVisible()
  await page.getByRole('button', { name: 'Добавить' }).click()

  await expect(page.getByRole('button', { name: 'Корзина (1)' })).toBeVisible()
})

test('guest creates shift extension request and sees pending then confirmed state', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const extensionRequest = buildShiftExtensionRequest()
  const api = await mockGuestApi(page, {
    restoreContext: buildRestoreContext(),
    extensionOptions: buildShiftExtensionOptions({
      available: true,
      durationMinutes: 60,
      priceMinor: 300000,
      currency: 'RUB',
      currentOrderableUntil: extensionRequest.currentOrderableUntil,
      proposedOrderableUntil: extensionRequest.requestedUntil,
      pendingRequest: null
    })
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByText('Продление на 1 час')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Продлить на 1 час' })).toBeEnabled()
  await page.getByRole('button', { name: 'Продлить на 1 час' }).click()

  await expect(page.getByRole('button', { name: 'Ожидает подтверждения' })).toBeVisible()
  expect(api.getCreateExtensionRequestCalls()).toBe(1)
  await page.evaluate(() => {
    const button = [...document.querySelectorAll('button')].find((node) => node.textContent === 'Ожидает подтверждения')
    const action = button as HTMLButtonElement | undefined
    action?.click()
  })
  expect(api.getCreateExtensionRequestCalls()).toBe(1)

  api.setExtensionOptions(
    buildShiftExtensionOptions({
      available: true,
      durationMinutes: 60,
      priceMinor: 300000,
      currency: 'RUB',
      currentOrderableUntil: extensionRequest.currentOrderableUntil,
      proposedOrderableUntil: extensionRequest.requestedUntil,
      pendingRequest: null
    })
  )
  api.setActiveOrderServiceCharges([
    {
      id: 700,
      source: 'SHIFT_EXTENSION',
      sourceRequestId: extensionRequest.id,
      label: 'Продление работы на 1 час',
      qty: 1,
      unitPriceMinor: 300000,
      totalMinor: 300000,
      currency: 'RUB'
    }
  ])

  await page.getByRole('button', { name: 'Обновить' }).click()

  await expect(page.getByText('Продление подтверждено до 23:00. Сумма добавлена в счёт.')).toBeVisible()
  await page.getByRole('button', { name: 'Мой заказ', exact: true }).click()
  await expect(page.getByText('Продление работы на 1 час')).toBeVisible()
  await expect(page.getByText('Сначала отсканируйте QR')).toHaveCount(0)
})

test('venue staff sees pending shift extension requests and can approve or reject', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueShiftExtensionApi(page)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Продления' })).toBeVisible()
  await page.getByRole('button', { name: 'Продления' }).click()
  await expect(page.getByRole('heading', { name: 'Запрос на продление' })).toBeVisible()
  await expect(page.getByText('Стол №4')).toBeVisible()
  await expect(page.getByRole('button', { name: '✅ Продлить на 1 час' })).toBeVisible()
  await expect(page.getByRole('button', { name: '❌ Отказать' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Настройки' })).toHaveCount(0)

  await page.getByRole('button', { name: '✅ Продлить на 1 час' }).click()

  await expect(page.getByText('Запросов на продление нет.')).toBeVisible()
  expect(api.getApproveCalls()).toBe(1)

  api.setRequests([buildShiftExtensionRequest({ id: 502, requestedUntil: '2026-06-10T00:00:00+03:00' })])
  await page.getByRole('button', { name: '🔄 Обновить' }).click()
  await expect(page.getByRole('heading', { name: 'Запрос на продление' })).toBeVisible()

  page.once('dialog', async (dialog) => {
    await dialog.accept('Нет свободного времени')
  })
  await page.getByRole('button', { name: '❌ Отказать' }).click()

  await expect(page.getByText('Запросов на продление нет.')).toBeVisible()
  expect(api.getRejectCalls()).toBe(1)
  expect(api.getRejectedReasons()).toEqual(['Нет свободного времени'])
})

test('venue manager configures paid shift extension settings', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueShiftExtensionApi(page, {
    role: 'MANAGER',
    permissions: ['ORDER_QUEUE_VIEW', 'SHIFT_EXTENSION_VIEW', 'SHIFT_EXTENSION_CONFIRM', 'SHIFT_EXTENSION_SETTINGS'],
    settings: buildShiftExtensionSettings()
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Настройки' })).toBeVisible()
  await page.getByRole('button', { name: 'Настройки' }).click()
  await expect(page.getByRole('heading', { name: 'Продление времени' })).toBeVisible()
  await expect(page.getByText('Настройте цену и длительность, чтобы гости могли запросить продление.')).toBeVisible()

  const settingsCard = page.locator('.card').filter({ has: page.getByRole('heading', { name: 'Продление времени' }) })
  await settingsCard.getByLabel('Включить запросы на продление').check()
  await settingsCard.locator('select').selectOption('60')
  await settingsCard.getByPlaceholder('3000').fill('3000')
  await settingsCard.getByRole('button', { name: 'Сохранить' }).click()

  await expect(page.getByText('Настройки сохранены.')).toBeVisible()
  await expect(settingsCard).toContainText('Включено · 60 мин')
  await expect(settingsCard).toContainText(/3\s*000/)
  expect(api.getUpdateSettingsCalls()).toBe(1)
  expect(api.getSettings()).toMatchObject({
    enabled: true,
    durationMinutes: 60,
    priceMinor: 300000,
    configured: true
  })
})

test('startup without URL table token restores active table context', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, { restoreContext: buildRestoreContext() })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Микс' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Корзина' })).toBeVisible()
})

test('my order after restored table context keeps table scope', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, { restoreContext: buildRestoreContext() })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await page.getByRole('button', { name: 'Мой заказ', exact: true }).click()

  await expect(page.getByRole('heading', { name: 'Заказ №123' })).toBeVisible()
  await expect(page.getByText('Double Apple')).toBeVisible()
  await expect(page.getByText('Сначала отсканируйте QR')).toHaveCount(0)
  await expect(page.getByText('Корзина и заказ доступны после сканирования QR-кода стола.')).toHaveCount(0)

  await clickTelegramBackButton(page)

  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await expect(page.getByText('Сначала отсканируйте QR')).toHaveCount(0)
  await expectTelegramBackButtonHidden(page)

  await clickTelegramBackButton(page)
  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Заказ №123' })).toHaveCount(0)
  await expect(page.getByText('Сначала отсканируйте QR')).toHaveCount(0)
})

test('explicit QR table token wins over restore context', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, {
    restoreContext: buildRestoreContext({
      tableToken: 'RESTORE-OLD',
      tableSessionId: 901,
      tabId: 902,
      tableNumber: '99'
    })
  })

  await page.goto(`?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await expect(page.getByText('столом №99')).toHaveCount(0)
})

test('account switch does not reuse previous user cart or table restore state', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, { restoreContext: buildRestoreContext() })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await expect(page.getByRole('button', { name: 'Корзина (1)' })).toBeVisible()

  api.setRestoreContext(null)
  await page.evaluate((initData) => {
    window.localStorage.setItem('__e2e_telegram_user_id', '987654321')
    window.localStorage.setItem('__e2e_telegram_init_data', initData)
  }, otherMockInitData)
  await page.goto(`?mode=guest&smokeUser=other#tgWebAppData=${encodeURIComponent(otherMockInitData)}`)

  await expect(page.getByText('Чтобы заказать к столику или вызвать персонал, отсканируйте QR-код на столе.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Корзина (1)' })).toHaveCount(0)
})
