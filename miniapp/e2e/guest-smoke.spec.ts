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

type GuestMenuOption = {
  id: number
  name: string
  priceDeltaMinor: number
  isAvailable?: boolean
}

type GuestMenuItem = {
  id: number
  name: string
  priceMinor: number
  currency: string
  isAvailable: boolean
  itemType?: string | null
  effectiveItemType: string
  options?: GuestMenuOption[]
}

type GuestMenuCategory = {
  id: number
  name: string
  categoryType: string
  items: GuestMenuItem[]
}

type VenueMenuOptionFixture = {
  id: number
  itemId: number
  name: string
  priceDeltaMinor: number
  isAvailable: boolean
  sortOrder: number
}

type VenueMenuItemFixture = {
  id: number
  categoryId: number
  name: string
  priceMinor: number
  currency: string
  isAvailable: boolean
  sortOrder: number
  itemType?: string | null
  effectiveItemType: string
  supportsBaseFlavorProfiles?: boolean
  missingBaseFlavorProfilesCount?: number
  options: VenueMenuOptionFixture[]
}

type VenueMenuCategoryFixture = {
  id: number
  name: string
  sortOrder: number
  categoryType: string
  items: VenueMenuItemFixture[]
}

type AddBatchItemPayload = {
  itemId: number
  qty: number
  selectedOptionId?: number | null
  preferenceNote?: string | null
}

type AddBatchPayload = {
  tableToken: string
  tableSessionId: number
  tabId: number
  idempotencyKey?: string
  items: AddBatchItemPayload[]
  comment?: string | null
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
    unavailableReason: 'EXTENSION_DISABLED',
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

function buildDefaultGuestMenu(): GuestMenuCategory[] {
  return [
    {
      id: 20,
      name: 'Кальянное меню',
      categoryType: 'HOOKAH',
      items: [
        {
          id: 200,
          name: 'Double Apple',
          priceMinor: 150000,
          currency: 'RUB',
          isAvailable: true,
          effectiveItemType: 'HOOKAH'
        }
      ]
    }
  ]
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
  options: {
    restoreContext?: RestoreContext | null
    extensionOptions?: ShiftExtensionOptions | null
    menuCategories?: GuestMenuCategory[]
  } = {}
) {
  let structuredMenuCalls = 0
  let restoreContext = options.restoreContext ?? null
  let extensionOptions = options.extensionOptions ?? buildShiftExtensionOptions()
  const menuCategories = options.menuCategories ?? buildDefaultGuestMenu()
  let createExtensionRequestCalls = 0
  let activeOrderServiceCharges: ServiceCharge[] = []
  const previewRequests: Array<{ items: AddBatchItemPayload[] }> = []
  const addBatchRequests: AddBatchPayload[] = []
  let submittedOrderItems: AddBatchItemPayload[] = []

  const findMenuItem = (itemId: number) =>
    menuCategories.flatMap((category) => category.items).find((item) => item.id === itemId) ?? null

  const findOption = (item: GuestMenuItem | null, optionId: number | null | undefined) =>
    optionId == null ? null : item?.options?.find((option) => option.id === optionId) ?? null

  const buildOrderItem = (line: AddBatchItemPayload) => {
    const item = findMenuItem(line.itemId)
    const option = findOption(item, line.selectedOptionId)
    const unitPriceMinor = (item?.priceMinor ?? 0) + (option?.priceDeltaMinor ?? 0)
    const lineGrossMinor = unitPriceMinor * line.qty
    return {
      itemId: line.itemId,
      qty: line.qty,
      name: item?.name ?? `Item ${line.itemId}`,
      selectedOption: option
        ? {
            optionId: option.id,
            name: option.name,
            priceDeltaMinor: option.priceDeltaMinor
          }
        : null,
      preferenceNote: line.preferenceNote ?? null,
      priceMinor: unitPriceMinor,
      currency: item?.currency ?? 'RUB',
      lineGrossMinor,
      manualDiscountMinor: 0,
      promoDiscountMinor: 0,
      discountMinor: 0,
      linePayableMinor: lineGrossMinor,
      isPromotionReward: false
    }
  }

  const buildActiveOrderItems = () => {
    const lines = submittedOrderItems.length > 0 ? submittedOrderItems : [{ itemId: 200, qty: 1 }]
    return lines.map(buildOrderItem)
  }

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
        categories: menuCategories
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
    const orderItems = buildActiveOrderItems()
    const orderItemsTotal = orderItems.reduce((sum, item) => sum + item.lineGrossMinor, 0)
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
          grossTotalMinor: orderItemsTotal + serviceChargeTotal,
          manualDiscountTotalMinor: 0,
          promoDiscountTotalMinor: 0,
          loyaltyDiscountTotalMinor: 0,
          finalPayableTotalMinor: orderItemsTotal + serviceChargeTotal,
          currency: 'RUB',
          discounts: [],
          serviceCharges: activeOrderServiceCharges,
          batches: [
            {
              batchId: 333,
              comment: null,
              items: orderItems
            }
          ]
        }
      })
    )
  })

  await page.route('**/api/guest/order/preview', async (route) => {
    const body = (await route.request().postDataJSON()) as { items: AddBatchItemPayload[] }
    previewRequests.push({ items: body.items })
    const previewItems = body.items.map(buildOrderItem)
    const grossTotalMinor = previewItems.reduce((sum, item) => sum + item.lineGrossMinor, 0)
    await route.fulfill(
      jsonResponse({
        preview: {
          grossTotalMinor,
          promoDiscountTotalMinor: 0,
          loyaltyDiscountTotalMinor: 0,
          finalPayableTotalMinor: grossTotalMinor,
          currency: 'RUB',
          discounts: [],
          items: previewItems
        }
      })
    )
  })

  await page.route('**/api/guest/order/add-batch', async (route) => {
    const body = (await route.request().postDataJSON()) as AddBatchPayload
    addBatchRequests.push(body)
    submittedOrderItems = body.items
    await route.fulfill(jsonResponse({ orderId: 900, batchId: 444 }))
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
    getPreviewRequests: () => previewRequests,
    getAddBatchRequests: () => addBatchRequests,
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
  let orderServiceCharges: ServiceCharge[] = []
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

  const orderPendingShiftExtension = () => {
    const request = requests[0]
    if (!request) return null
    return {
      requestId: request.id,
      orderId: request.orderId,
      tableSessionId: request.tableSessionId,
      tabId: request.tabId,
      tableId: request.tableId,
      tableNumber: request.tableNumber ?? '4',
      tableLabel: request.tableNumber ?? '4',
      durationMinutes: request.durationMinutes,
      priceMinor: request.priceMinor,
      currency: request.currency,
      requestedAt: request.createdAt,
      status: request.status
    }
  }

  const orderBill = () => {
    const serviceChargeTotal = orderServiceCharges.reduce((sum, charge) => sum + charge.totalMinor, 0)
    return {
      grossTotalMinor: 120000,
      manualDiscountTotalMinor: 0,
      promoDiscountTotalMinor: 0,
      loyaltyDiscountTotalMinor: 0,
      excludedTotalMinor: 0,
      canceledTotalMinor: 0,
      rejectedTotalMinor: 0,
      finalPayableTotalMinor: 120000 + serviceChargeTotal,
      currency: 'RUB',
      promoDiscounts: [],
      loyaltyDiscounts: [],
      excludedItems: [],
      serviceCharges: orderServiceCharges
    }
  }

  await page.route('**/api/venue/orders/queue?**', async (route) => {
    await route.fulfill(
      jsonResponse({
        items: [
          {
            orderId: 900,
            batchId: 300,
            displayNumber: 42,
            activeBatchesCount: 1,
            tableNumber: '4',
            tableLabel: '4',
            createdAt: '2026-06-09T21:30:00+03:00',
            comment: null,
            itemsCount: 1,
            status: 'accepted',
            pendingShiftExtension: orderPendingShiftExtension()
          }
        ],
        nextCursor: null
      })
    )
  })

  await page.route('**/api/venue/orders/900?**', async (route) => {
    await route.fulfill(
      jsonResponse({
        order: {
          orderId: 900,
          displayNumber: 42,
          displayDate: '2026-06-09',
          venueId: 1,
          tableId: 7,
          tableNumber: '4',
          tableLabel: '4',
          status: 'accepted',
          createdAt: '2026-06-09T21:30:00+03:00',
          updatedAt: '2026-06-09T21:45:00+03:00',
          bill: orderBill(),
          batches: [
            {
              batchId: 300,
              status: 'accepted',
              source: 'MINIAPP',
              comment: null,
              createdAt: '2026-06-09T21:30:00+03:00',
              updatedAt: '2026-06-09T21:30:00+03:00',
              promotionDiscounts: [],
              items: [
                {
                  batchItemId: 700,
                  itemId: 100,
                  name: 'Double Apple',
                  qty: 1,
                  priceMinor: 120000,
                  currency: 'RUB',
                  lineGrossMinor: 120000,
                  manualDiscountMinor: 0,
                  promoDiscountMinor: 0,
                  linePayableMinor: 120000,
                  isExcluded: false,
                  itemStatus: 'active'
                }
              ]
            }
          ],
          pendingShiftExtension: orderPendingShiftExtension()
        }
      })
    )
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
      orderServiceCharges = [
        ...orderServiceCharges,
        {
          id: 9000 + requestId,
          source: 'SHIFT_EXTENSION',
          sourceRequestId: request.id,
          label: 'Продление работы на 1 час',
          qty: 1,
          unitPriceMinor: request.priceMinor,
          totalMinor: request.priceMinor,
          currency: request.currency
        }
      ]
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

function buildDefaultVenueMenu(): VenueMenuCategoryFixture[] {
  return [
    {
      id: 30,
      name: 'Кальянное меню',
      sortOrder: 0,
      categoryType: 'HOOKAH',
      items: [
        {
          id: 310,
          categoryId: 30,
          name: 'Кальян',
          priceMinor: 180000,
          currency: 'RUB',
          isAvailable: true,
          sortOrder: 0,
          effectiveItemType: 'HOOKAH',
          supportsBaseFlavorProfiles: true,
          missingBaseFlavorProfilesCount: 8,
          options: []
        }
      ]
    }
  ]
}

async function mockVenueMenuApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    permissions?: string[]
    categories?: VenueMenuCategoryFixture[]
  } = {}
) {
  const role = options.role ?? 'MANAGER'
  const permissions = options.permissions ?? ['MENU_VIEW', 'MENU_MANAGE', 'MENU_AVAILABILITY_MANAGE']
  const categories = options.categories ?? buildDefaultVenueMenu()
  let createOptionCalls = 0
  let updateOptionCalls = 0
  let deleteOptionCalls = 0
  let availabilityCalls = 0
  let applyBaseFlavorProfileCalls = 0
  let createItemCalls = 0
  let updateItemCalls = 0
  let itemAvailabilityCalls = 0
  let nextItemId = 950
  let nextOptionId = 900
  const baseFlavorProfiles = [
    'Ягодный',
    'Фруктовый',
    'Цитрусовый',
    'Десертный',
    'Освежающий / мятный',
    'Напиточный',
    'Пряный',
    'Цветочный'
  ]

  const allItems = () => categories.flatMap((category) => category.items)
  const allOptions = () => allItems().flatMap((item) => item.options)
  const findCategory = (categoryId: number) => categories.find((category) => category.id === categoryId) ?? null
  const findItem = (itemId: number) => allItems().find((item) => item.id === itemId) ?? null
  const findOption = (optionId: number) => allOptions().find((option) => option.id === optionId) ?? null
  const normalizeFlavorNameKey = (name: string) => name.trim().replace(/\s+/g, ' ').toLocaleLowerCase('ru-RU')
  const isLegacyHookahCategory = (category: VenueMenuCategoryFixture) =>
    category.name.trim().toLocaleLowerCase('ru-RU') === 'кальянное меню'
  const supportsBaseFlavorProfiles = (category: VenueMenuCategoryFixture, itemType?: string | null) =>
    itemType === 'HOOKAH' || (!itemType && (category.categoryType === 'HOOKAH' || isLegacyHookahCategory(category)))
  const updateMissingBaseFlavorProfilesCount = (item: VenueMenuItemFixture) => {
    if (item.effectiveItemType !== 'HOOKAH' && item.supportsBaseFlavorProfiles !== true) {
      item.missingBaseFlavorProfilesCount = 0
      return
    }
    const existingKeys = new Set(item.options.map((option) => normalizeFlavorNameKey(option.name)))
    item.missingBaseFlavorProfilesCount = baseFlavorProfiles.filter(
      (profile) => !existingKeys.has(normalizeFlavorNameKey(profile))
    ).length
  }
  const updateAllMissingBaseFlavorProfilesCount = () => allItems().forEach(updateMissingBaseFlavorProfilesCount)

  updateAllMissingBaseFlavorProfilesCount()

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

  await page.route('**/api/venue/menu?**', async (route) => {
    await route.fulfill(jsonResponse({ venueId: 1, categories }))
  })

  await page.route('**/api/venue/menu/items**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    const baseFlavorMatch = url.pathname.match(/\/api\/venue\/menu\/items\/(\d+)\/base-flavor-profiles$/)
    if (baseFlavorMatch && method === 'POST') {
      applyBaseFlavorProfileCalls += 1
      const item = findItem(Number(baseFlavorMatch[1]))
      if (!item) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
        return
      }
      if (item.effectiveItemType !== 'HOOKAH' && item.supportsBaseFlavorProfiles !== true) {
        await route.fulfill({ status: 400, contentType: 'application/json', body: JSON.stringify({ error: 'invalid' }) })
        return
      }
      const existingKeys = new Set(item.options.map((option) => normalizeFlavorNameKey(option.name)))
      let existingCount = 0
      const createdOptions: VenueMenuOptionFixture[] = []
      baseFlavorProfiles.forEach((profileName) => {
        const key = normalizeFlavorNameKey(profileName)
        if (existingKeys.has(key)) {
          existingCount += 1
          return
        }
        const option = {
          id: nextOptionId++,
          itemId: item.id,
          name: profileName,
          priceDeltaMinor: 0,
          isAvailable: true,
          sortOrder: item.options.length
        }
        item.options.push(option)
        createdOptions.push(option)
        existingKeys.add(key)
      })
      updateMissingBaseFlavorProfilesCount(item)
      await route.fulfill(
        jsonResponse({
          itemId: item.id,
          addedCount: createdOptions.length,
          existingCount,
          options: item.options
        })
      )
      return
    }

    if (method === 'POST') {
      createItemCalls += 1
      const body = (await request.postDataJSON()) as {
        categoryId: number
        name: string
        priceMinor: number
        currency: string
        isAvailable: boolean
        itemType?: string | null
      }
      const category = findCategory(body.categoryId)
      if (!category) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
        return
      }
      const itemSupportsBaseFlavorProfiles = supportsBaseFlavorProfiles(category, body.itemType)
      const item = {
        id: nextItemId++,
        categoryId: category.id,
        name: body.name,
        priceMinor: body.priceMinor,
        currency: body.currency,
        isAvailable: body.isAvailable,
        sortOrder: category.items.length,
        itemType: body.itemType ?? null,
        effectiveItemType: body.itemType ?? category.categoryType,
        supportsBaseFlavorProfiles: itemSupportsBaseFlavorProfiles,
        missingBaseFlavorProfilesCount: itemSupportsBaseFlavorProfiles ? 8 : 0,
        options: []
      }
      category.items.push(item)
      await route.fulfill(jsonResponse(item))
      return
    }

    const itemMatch = url.pathname.match(/\/api\/venue\/menu\/items\/(\d+)$/)
    if (itemMatch && method === 'PATCH') {
      updateItemCalls += 1
      const item = findItem(Number(itemMatch[1]))
      const body = (await request.postDataJSON()) as {
        name?: string | null
        priceMinor?: number | null
        currency?: string | null
        isAvailable?: boolean | null
        itemType?: string | null
      }
      if (!item) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
        return
      }
      if (body.name != null) item.name = body.name
      if (body.priceMinor != null) item.priceMinor = body.priceMinor
      if (body.currency != null) item.currency = body.currency
      if (body.isAvailable != null) item.isAvailable = body.isAvailable
      if (body.itemType !== undefined) {
        item.itemType = body.itemType
        const category = findCategory(item.categoryId)
        item.effectiveItemType = body.itemType ?? category?.categoryType ?? 'OTHER'
        item.supportsBaseFlavorProfiles = category ? supportsBaseFlavorProfiles(category, body.itemType) : false
        updateMissingBaseFlavorProfilesCount(item)
      }
      await route.fulfill(jsonResponse(item))
      return
    }

    const availabilityMatch = url.pathname.match(/\/api\/venue\/menu\/items\/(\d+)\/availability$/)
    if (availabilityMatch && method === 'PATCH') {
      itemAvailabilityCalls += 1
      const item = findItem(Number(availabilityMatch[1]))
      const body = (await request.postDataJSON()) as { isAvailable: boolean }
      if (!item) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
        return
      }
      item.isAvailable = body.isAvailable
      await route.fulfill(jsonResponse(item))
      return
    }

    await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
  })

  await page.route('**/api/venue/menu/options**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (method === 'POST') {
      createOptionCalls += 1
      const body = (await request.postDataJSON()) as {
        itemId: number
        name: string
        priceDeltaMinor: number
        isAvailable: boolean
      }
      const item = findItem(body.itemId)
      if (!item) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
        return
      }
      const option = {
        id: nextOptionId++,
        itemId: item.id,
        name: body.name,
        priceDeltaMinor: body.priceDeltaMinor,
        isAvailable: body.isAvailable,
        sortOrder: item.options.length
      }
      item.options.push(option)
      updateMissingBaseFlavorProfilesCount(item)
      await route.fulfill(jsonResponse(option))
      return
    }

    const availabilityMatch = url.pathname.match(/\/api\/venue\/menu\/options\/(\d+)\/availability$/)
    if (availabilityMatch && method === 'PATCH') {
      availabilityCalls += 1
      const option = findOption(Number(availabilityMatch[1]))
      const body = (await request.postDataJSON()) as { isAvailable: boolean }
      if (!option) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
        return
      }
      option.isAvailable = body.isAvailable
      await route.fulfill(jsonResponse(option))
      return
    }

    const optionMatch = url.pathname.match(/\/api\/venue\/menu\/options\/(\d+)$/)
    if (optionMatch && method === 'PATCH') {
      updateOptionCalls += 1
      const option = findOption(Number(optionMatch[1]))
      const body = (await request.postDataJSON()) as {
        name?: string | null
        priceDeltaMinor?: number | null
        isAvailable?: boolean | null
      }
      if (!option) {
        await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
        return
      }
      if (body.name != null) option.name = body.name
      if (body.priceDeltaMinor != null) option.priceDeltaMinor = body.priceDeltaMinor
      if (body.isAvailable != null) option.isAvailable = body.isAvailable
      updateAllMissingBaseFlavorProfilesCount()
      await route.fulfill(jsonResponse(option))
      return
    }

    if (optionMatch && method === 'DELETE') {
      deleteOptionCalls += 1
      const optionId = Number(optionMatch[1])
      categories.forEach((category) => {
        category.items.forEach((item) => {
          item.options = item.options.filter((option) => option.id !== optionId)
        })
      })
      updateAllMissingBaseFlavorProfilesCount()
      await route.fulfill(jsonResponse({ ok: true }))
      return
    }

    await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
  })

  return {
    getCategories: () => categories,
    getCreateOptionCalls: () => createOptionCalls,
    getUpdateOptionCalls: () => updateOptionCalls,
    getDeleteOptionCalls: () => deleteOptionCalls,
    getAvailabilityCalls: () => availabilityCalls,
    getApplyBaseFlavorProfileCalls: () => applyBaseFlavorProfileCalls,
    getCreateItemCalls: () => createItemCalls,
    getUpdateItemCalls: () => updateItemCalls,
    getItemAvailabilityCalls: () => itemAvailabilityCalls
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

test('guest mini app selects item flavor and submits structured selected option', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    restoreContext: buildRestoreContext(),
    menuCategories: [
      {
        id: 20,
        name: 'Кальянное меню',
        categoryType: 'HOOKAH',
        items: [
          {
            id: 210,
            name: 'Кальян',
            priceMinor: 180000,
            currency: 'RUB',
            isAvailable: true,
            effectiveItemType: 'HOOKAH',
            options: [
              { id: 304, name: 'Ягодный', priceDeltaMinor: 0, isAvailable: true },
              { id: 301, name: 'Яблоко', priceDeltaMinor: 0, isAvailable: true },
              { id: 302, name: 'Мята', priceDeltaMinor: 25000, isAvailable: true },
              { id: 303, name: 'Недоступный вкус', priceDeltaMinor: 50000, isAvailable: false }
            ]
          },
          {
            id: 211,
            name: 'Вода',
            priceMinor: 20000,
            currency: 'RUB',
            isAvailable: true,
            effectiveItemType: 'DRINK'
          }
        ]
      }
    ]
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await page.getByRole('button', { name: /Кальянное меню/ }).click()

  await expect(page.getByText('Кальян', { exact: true })).toBeVisible()
  await expect(page.getByText('Выберите вкус')).toBeVisible()
  const waterItem = page.locator('.menu-item').filter({ hasText: 'Вода' })
  await expect(waterItem.getByText('Выберите вкус')).toHaveCount(0)
  await expect(waterItem.getByText('Выберите опцию')).toHaveCount(0)
  await page.getByRole('button', { name: 'Выбрать' }).click()

  await expect(page.getByRole('heading', { name: 'Выберите вкус' })).toBeVisible()
  await expect(page.getByText('Кальян', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: /Яблоко/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /Ягодный/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /Мята/ })).toBeVisible()
  await expect(page.getByText('Недоступный вкус')).toHaveCount(0)
  await expect(page.getByLabel('Пожелания к приготовлению')).toHaveCount(0)

  await page.getByRole('button', { name: /Яблоко/ }).click()
  await expect(page.getByRole('heading', { name: 'Пожелания к приготовлению' })).toBeVisible()
  await expect(page.getByText('Вкус: Яблоко')).toBeVisible()
  await expect(page.getByText('Если пожеланий нет, просто добавьте в корзину.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Добавить в корзину' })).toBeVisible()
  await page.getByRole('button', { name: /К выбору вкуса/ }).click()
  await expect(page.getByRole('heading', { name: 'Выберите вкус' })).toBeVisible()
  await expect(page.getByLabel('Пожелания к приготовлению')).toHaveCount(0)

  await page.getByRole('button', { name: /Яблоко/ }).click()
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await page.getByRole('button', { name: 'Выбрать' }).click()
  await page.getByRole('button', { name: /Яблоко/ }).click()
  await page.getByLabel('Пожелания к приготовлению').fill('поменьше холодка')
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await page.getByRole('button', { name: 'Выбрать' }).click()
  await page.getByRole('button', { name: /Яблоко/ }).click()
  await page.getByLabel('Пожелания к приготовлению').fill(' поменьше холодка ')
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await page.getByRole('button', { name: 'Выбрать' }).click()
  await page.getByRole('button', { name: /Яблоко/ }).click()
  await page.getByLabel('Пожелания к приготовлению').fill('без мяты')
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await page.getByRole('button', { name: 'Выбрать' }).click()
  await page.getByRole('button', { name: /Мята/ }).click()
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()

  await expect(page.getByRole('button', { name: 'Корзина (5)' })).toBeVisible()
  await page.getByRole('button', { name: 'Корзина (5)' }).click()

  const appleLines = page.locator('.cart-item').filter({ hasText: 'Вкус: Яблоко' })
  const appleLine = page.locator('.cart-item').filter({ hasText: 'Пожелание: поменьше холодка' })
  const appleNoMintLine = page.locator('.cart-item').filter({ hasText: 'Пожелание: без мяты' })
  const mintLine = page.locator('.cart-item').filter({ hasText: 'Вкус: Мята' })
  await expect(appleLines).toHaveCount(3)
  await expect(appleLine).toHaveCount(1)
  await expect(appleNoMintLine).toHaveCount(1)
  await expect(mintLine).toHaveCount(1)
  await expect(appleLine.locator('input')).toHaveValue('2')
  await expect(appleNoMintLine.locator('input')).toHaveValue('1')
  await expect(mintLine.locator('input')).toHaveValue('1')
  await expect(page.getByText('Пожелание: поменьше холодка')).toBeVisible()
  await expect(page.getByText('Пожелание: без мяты')).toBeVisible()

  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect(page.getByRole('heading', { name: 'Заказ №123' })).toBeVisible()
  await expect(page.getByText('Вкус: Яблоко')).toHaveCount(3)
  await expect(page.getByText('Вкус: Мята')).toBeVisible()
  await expect(page.getByText('Пожелание: поменьше холодка')).toBeVisible()
  await expect(page.getByText('Пожелание: без мяты')).toBeVisible()
  await expect(page.getByText('Сначала отсканируйте QR')).toHaveCount(0)

  expect(api.getAddBatchRequests()).toHaveLength(1)
  const submittedItems = api.getAddBatchRequests()[0].items
  expect(submittedItems).toHaveLength(4)
  expect(submittedItems).toEqual(
    expect.arrayContaining([
      { itemId: 210, qty: 1, selectedOptionId: 301 },
      { itemId: 210, qty: 2, selectedOptionId: 301, preferenceNote: 'поменьше холодка' },
      { itemId: 210, qty: 1, selectedOptionId: 301, preferenceNote: 'без мяты' },
      { itemId: 210, qty: 1, selectedOptionId: 302 }
    ])
  )
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

  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await expect(page.getByRole('button', { name: /Продление работы заведения/ })).toBeVisible()
  await page.getByRole('button', { name: /Продление работы заведения/ }).click()

  await expect(page.getByRole('heading', { name: 'Продление работы заведения' })).toBeVisible()
  await expect(page.getByText('Продление на 1 час')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Продлить на 1 час' })).toBeEnabled()
  await page.getByRole('button', { name: 'Продлить на 1 час' }).click()

  await expect(page.getByRole('button', { name: 'Ожидает подтверждения' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Корзина (1)' })).toHaveCount(0)
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

  const extensionRequestsButton = page.getByRole('button', { name: 'Запросы продления', exact: true })
  await expect(extensionRequestsButton).toBeVisible()
  await extensionRequestsButton.click()
  await expect(page.getByRole('heading', { name: 'Продления' })).toBeVisible()
  await expect(page.getByText('Запрос на продление')).toBeVisible()
  await page.getByRole('button', { name: 'Заказы' }).click()
  await expect(page.getByText('Запрос на продление')).toBeVisible()
  await page.getByRole('button', { name: 'Открыть' }).click()
  await expect(page.getByRole('heading', { name: 'Запрос на продление работы заведения' })).toBeVisible()
  await expect(page.getByText(/На 1 час/)).toBeVisible()
  await expect(page.getByText('Гость ожидает подтверждения')).toBeVisible()
  await expect(page.getByRole('button', { name: '✅ Подтвердить продление' })).toBeVisible()
  await expect(page.getByRole('button', { name: '❌ Отказать' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Настройки продления', exact: true })).toHaveCount(0)

  await page.getByRole('button', { name: '✅ Подтвердить продление' }).click()

  await expect(page.getByRole('heading', { name: 'Запрос на продление работы заведения' })).toHaveCount(0)
  await expect(page.getByText('Продление работы на 1 час')).toBeVisible()
  expect(api.getApproveCalls()).toBe(1)

  api.setRequests([buildShiftExtensionRequest({ id: 502, requestedUntil: '2026-06-10T00:00:00+03:00' })])
  await page.getByRole('button', { name: '🔄 Обновить' }).click()
  await expect(page.getByRole('heading', { name: 'Запрос на продление работы заведения' })).toBeVisible()

  page.once('dialog', async (dialog) => {
    await dialog.accept('Нет свободного времени')
  })
  await page.getByRole('button', { name: '❌ Отказать' }).click()

  await expect(page.getByRole('heading', { name: 'Запрос на продление работы заведения' })).toHaveCount(0)
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

  await expect(page.getByRole('button', { name: 'Настройки продления', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Настройки продления', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Продление времени' })).toBeVisible()
  await expect(page.getByText('Настройте цену и длительность, чтобы гости могли запросить продление.')).toBeVisible()

  const settingsCard = page.locator('.card').filter({ has: page.getByRole('heading', { name: 'Продление времени' }) })
  await expect(settingsCard.getByText('Если выключено, гости не увидят продление, но цена и длительность сохранятся.')).toBeVisible()
  await settingsCard.getByLabel('Показывать гостям возможность продления').check()
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

test('venue manager manages menu item flavors from mini app', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuApi(page, {
    categories: [
      {
        id: 30,
        name: 'Кальянное меню',
        sortOrder: 0,
        categoryType: 'OTHER',
        items: [
          {
            id: 310,
            categoryId: 30,
            name: 'Кальян',
            priceMinor: 180000,
            currency: 'RUB',
            isAvailable: true,
            sortOrder: 0,
            effectiveItemType: 'OTHER',
            supportsBaseFlavorProfiles: true,
            missingBaseFlavorProfilesCount: 8,
            options: []
          }
        ]
      },
      {
        id: 31,
        name: 'Напитки',
        sortOrder: 1,
        categoryType: 'DRINK',
        items: [
          {
            id: 320,
            categoryId: 31,
            name: 'Вода',
            priceMinor: 20000,
            currency: 'RUB',
            isAvailable: true,
            sortOrder: 0,
            effectiveItemType: 'DRINK',
            supportsBaseFlavorProfiles: false,
            missingBaseFlavorProfilesCount: 0,
            options: [
              {
                id: 410,
                itemId: 320,
                name: 'Газированная',
                priceDeltaMinor: 0,
                isAvailable: true,
                sortOrder: 0
              }
            ]
          }
        ]
      },
      {
        id: 32,
        name: 'Кухня',
        sortOrder: 2,
        categoryType: 'FOOD',
        items: [
          {
            id: 330,
            categoryId: 32,
            name: 'Сэндвич',
            priceMinor: 45000,
            currency: 'RUB',
            isAvailable: true,
            sortOrder: 0,
            effectiveItemType: 'FOOD',
            supportsBaseFlavorProfiles: false,
            missingBaseFlavorProfilesCount: 0,
            options: []
          }
        ]
      }
    ]
  })
  const dialogReplies: string[] = []
  page.on('dialog', async (dialog) => {
    if (dialog.type() === 'prompt') {
      await dialog.accept(dialogReplies.shift() ?? '')
      return
    }
    await dialog.accept()
  })
  const hookahItem = () => page.locator('.venue-menu-item').filter({ hasText: 'Кальян' })
  const waterItem = () => page.locator('.venue-menu-item').filter({ hasText: 'Вода' })
  const kitchenItem = () => page.locator('.venue-menu-item').filter({ hasText: 'Сэндвич' })
  const hookahCategory = () =>
    page.locator('.venue-menu-category').filter({ has: page.getByRole('heading', { name: 'Кальянное меню' }) })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  await expect(page.getByRole('heading', { level: 2, name: 'Меню', exact: true })).toBeVisible()
  await expect(hookahItem().getByLabel('Доступно гостям')).toBeChecked()
  await hookahItem().getByLabel('Доступно гостям').uncheck()
  await expect(hookahItem().getByLabel('В стоп-листе')).not.toBeChecked()
  await expect(hookahItem().locator('.menu-item-badge').filter({ hasText: 'Стоп-лист' })).toBeVisible()
  await hookahItem().getByLabel('В стоп-листе').check()
  await expect(hookahItem().getByLabel('Доступно гостям')).toBeChecked()
  expect(api.getItemAvailabilityCalls()).toBe(2)
  await expect(hookahItem().getByText('Вкусы / опции')).toBeVisible()
  await expect(hookahItem().getByText('Добавьте вкусы, чтобы гости выбирали их при заказе.')).toBeVisible()
  await expect(hookahItem().getByRole('button', { name: 'Добавить базовые вкусы' })).toBeVisible()
  await expect(hookahItem().getByRole('button', { name: 'Добавить вкус' })).toBeVisible()
  await expect(hookahItem().getByRole('button', { name: 'Добавить опцию' })).toHaveCount(0)
  await expect(waterItem().getByText('Опции')).toBeVisible()
  await expect(waterItem().getByText('Газированная')).toBeVisible()
  await expect(waterItem().getByRole('button', { name: 'Добавить опцию' })).toBeVisible()
  await expect(waterItem().getByRole('button', { name: 'Добавить базовые вкусы' })).toHaveCount(0)
  await expect(waterItem().getByText('Вкусы / опции')).toHaveCount(0)
  await expect(waterItem().getByText('Добавьте вкусы, чтобы гости выбирали их при заказе.')).toHaveCount(0)
  await expect(kitchenItem().getByRole('button', { name: 'Добавить базовые вкусы' })).toHaveCount(0)
  await expect(kitchenItem().getByText('Вкусы / опции')).toHaveCount(0)
  await expect(kitchenItem().getByText('Добавьте вкусы, чтобы гости выбирали их при заказе.')).toHaveCount(0)

  await hookahItem().getByRole('button', { name: 'Добавить базовые вкусы' }).click()
  await expect(page.getByText('Добавлено вкусов: 8. Уже были: 0.')).toBeVisible()
  await expect(hookahItem().getByText('Ягодный')).toBeVisible()
  await expect(hookahItem().getByText('Фруктовый')).toBeVisible()
  await expect(hookahItem().getByRole('button', { name: 'Добавить базовые вкусы' })).toHaveCount(0)
  await expect(waterItem().getByText('Ягодный')).toHaveCount(0)
  await expect(kitchenItem().getByText('Ягодный')).toHaveCount(0)
  expect(api.getApplyBaseFlavorProfileCalls()).toBe(1)

  dialogReplies.push('Яблоко', '250')
  await hookahItem().getByRole('button', { name: 'Добавить вкус' }).click()
  await expect(hookahItem().getByText('Яблоко')).toBeVisible()
  await expect(hookahItem().getByText(/\+250/)).toBeVisible()
  await expect(waterItem().getByText('Яблоко')).toHaveCount(0)
  await expect(kitchenItem().getByText('Яблоко')).toHaveCount(0)
  expect(api.getCreateOptionCalls()).toBe(1)

  dialogReplies.push('Яблоко без мяты', '0')
  await hookahItem()
    .locator('.venue-menu-option')
    .filter({ hasText: 'Яблоко' })
    .getByRole('button', { name: 'Править вкус' })
    .click()
  await expect(hookahItem().getByText('Яблоко без мяты')).toBeVisible()
  expect(api.getUpdateOptionCalls()).toBe(1)

  const editedOption = () => page.locator('.venue-menu-option').filter({ hasText: 'Яблоко без мяты' })
  await editedOption().getByLabel('Доступен гостям').uncheck()
  await expect(editedOption().getByLabel('В стоп-листе')).not.toBeChecked()
  await expect(editedOption().locator('.menu-item-badge').filter({ hasText: 'Стоп-лист' })).toBeVisible()
  expect(api.getAvailabilityCalls()).toBe(1)
  await editedOption().getByLabel('В стоп-листе').check()
  await expect(editedOption().getByLabel('Доступен гостям')).toBeChecked()
  expect(api.getAvailabilityCalls()).toBe(2)

  await editedOption().getByRole('button', { name: 'Удалить вкус' }).click()
  await expect(page.getByText('Яблоко без мяты')).toHaveCount(0)
  await expect(hookahItem().getByText('Ягодный')).toBeVisible()
  expect(api.getDeleteOptionCalls()).toBe(1)
  expect(api.getCategories()[0].items[0].options).toHaveLength(8)
  expect(api.getCategories()[1].items[0].options.map((option) => option.name)).toEqual(['Газированная'])

  await hookahCategory().getByPlaceholder('Название позиции').fill('Кальян дорогой')
  await hookahCategory().getByPlaceholder('Цена (например 350)').fill('2500')
  await hookahCategory().getByRole('button', { name: 'Добавить позицию' }).click()
  const expensiveHookahItem = page.locator('.venue-menu-item').filter({ hasText: 'Кальян дорогой' })
  await expect(expensiveHookahItem.getByText('Вкусы / опции')).toBeVisible()
  await expect(expensiveHookahItem.getByText('Добавьте вкусы, чтобы гости выбирали их при заказе.')).toBeVisible()
  await expect(expensiveHookahItem.getByRole('button', { name: 'Добавить базовые вкусы' })).toBeVisible()
  await expect(expensiveHookahItem.getByRole('button', { name: 'Добавить вкус' })).toBeVisible()
  await expensiveHookahItem.getByRole('button', { name: 'Добавить базовые вкусы' }).click()
  await expect(expensiveHookahItem.getByText('Ягодный')).toBeVisible()
  await expect(expensiveHookahItem.getByRole('button', { name: 'Добавить базовые вкусы' })).toHaveCount(0)
  dialogReplies.push('Кальян дорогой', '2700')
  await expensiveHookahItem.getByRole('button', { name: 'Править позицию' }).click()
  await expect(expensiveHookahItem.getByText(/2\s*700/)).toBeVisible()
  expect(api.getCreateItemCalls()).toBe(1)
  expect(api.getUpdateItemCalls()).toBe(1)
  expect(api.getApplyBaseFlavorProfileCalls()).toBe(2)
})

test('venue staff sees menu flavors without edit controls', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuApi(page, {
    role: 'STAFF',
    permissions: ['MENU_VIEW', 'MENU_AVAILABILITY_MANAGE'],
    categories: [
      {
        id: 30,
        name: 'Кальянное меню',
        sortOrder: 0,
        categoryType: 'HOOKAH',
        items: [
          {
            id: 310,
            categoryId: 30,
            name: 'Кальян',
            priceMinor: 180000,
            currency: 'RUB',
            isAvailable: true,
            sortOrder: 0,
            effectiveItemType: 'HOOKAH',
            options: [
              {
                id: 401,
                itemId: 310,
                name: 'Яблоко',
                priceDeltaMinor: 0,
                isAvailable: true,
                sortOrder: 0
              }
            ]
          }
        ]
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const hookahItem = page.locator('.venue-menu-item').filter({ hasText: 'Кальян' })
  const appleOption = hookahItem.locator('.venue-menu-option').filter({ hasText: 'Яблоко' })
  await expect(hookahItem.getByText('Вкусы / опции')).toBeVisible()
  await expect(hookahItem.getByText('Яблоко')).toBeVisible()
  await expect(page.getByPlaceholder('Новая категория')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Добавить позицию' })).toHaveCount(0)
  await expect(hookahItem.getByRole('button', { name: 'Добавить вкус' })).toHaveCount(0)
  await expect(hookahItem.getByRole('button', { name: 'Добавить базовые вкусы' })).toHaveCount(0)
  await expect(hookahItem.getByRole('button', { name: 'Править позицию' })).toHaveCount(0)
  await expect(hookahItem.getByRole('button', { name: 'Править вкус' })).toHaveCount(0)
  await expect(hookahItem.getByRole('button', { name: 'Удалить вкус' })).toHaveCount(0)
  await expect(hookahItem.getByRole('button', { name: 'Удалить' })).toHaveCount(0)
  await expect(hookahItem.getByRole('button', { name: '↑' })).toHaveCount(0)
  await expect(hookahItem.getByRole('button', { name: '↓' })).toHaveCount(0)
  await expect(hookahItem.getByLabel('Доступно гостям')).toBeChecked()
  await expect(hookahItem.getByLabel('Доступен гостям')).toBeChecked()

  await appleOption.getByLabel('Доступен гостям').uncheck()
  await expect(appleOption.getByLabel('В стоп-листе')).not.toBeChecked()
  await expect(appleOption.locator('.menu-item-badge').filter({ hasText: 'Стоп-лист' })).toBeVisible()
  await hookahItem.getByLabel('Доступно гостям').uncheck()
  await expect(hookahItem.locator('.menu-item-badge').filter({ hasText: 'Стоп-лист' })).toHaveCount(2)
  expect(api.getAvailabilityCalls()).toBe(1)
  expect(api.getItemAvailabilityCalls()).toBe(1)
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
