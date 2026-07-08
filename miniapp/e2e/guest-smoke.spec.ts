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

type ApiErrorFixture = {
  status: number
  code?: string
  message?: string
}

type TableSessionEndResponseFixture = {
  ended: boolean
  tableSessionId: number
  blockedReason: 'ACTIVE_ORDER' | 'ACTIVE_STAFF_CALL' | null
  message: string | null
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

type ActiveOrderFixtureOptions = {
  status?: string
  batchStatus?: string
  itemManualDiscountMinor?: number
  itemPromoDiscountMinor?: number
}

type GuestVisitHistoryFixture = {
  items: Array<Record<string, unknown>>
  details: Record<number, Record<string, unknown>>
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

type BookingSettings = {
  venueId: number
  holdMinutes: number
  defaultHoldMinutes: number
  minHoldMinutes: number
  maxHoldMinutes: number
  quickHoldMinutes: number[]
}

type VenueScheduleDay = {
  weekday: number
  opensAt: string
  closesAt: string
  isClosed: boolean
  configured: boolean
}

type VenueScheduleOverride = {
  serviceDate: string
  opensAt: string
  closesAt: string
  isClosed: boolean
  guestNote?: string | null
}

type VenueScheduleSettings = {
  venueId: number
  weeklyHours: VenueScheduleDay[]
  dateOverrides: VenueScheduleOverride[]
}

type PublicCardSettings = {
  venueId: number
  name: string
  city: string | null
  address: string | null
  countryCode?: string | null
  formattedAddress?: string | null
  displayAddress?: string | null
  latitude?: number | null
  longitude?: number | null
  routeUrl?: string | null
  guestContact: string | null
  cardDescription: string | null
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

type VenueStatsResponse = {
  venueId: number
  period: 'today' | '7d' | '30d'
  periodTitle: string
  periodStart: string
  ordersCount: number
  revenueMinor: number
  averageCheckMinor: number
  discountMinor: number
  cancelledItemsCount: number
  currency: string
  topItems: Array<{ itemName: string; qty: number }>
}

type BillingInvoiceFixture = {
  id: number
  periodStart: string
  periodEnd: string
  dueAt: string
  amountMinor: number
  currency: string
  status: string
  checkoutUrl?: string | null
  paidAt?: string | null
}

type BillingOverviewFixture = {
  venueId: number
  subscriptionStatus: string
  trialEndAt?: string | null
  paidStartAt?: string | null
  lifecycleUpdatedAt?: string | null
  settingsTrialEndDate?: string | null
  settingsPaidStartDate?: string | null
  priceMinor?: number | null
  currency?: string | null
  basePaidThrough?: string | null
  paidThrough?: string | null
  nextPaymentDate?: string | null
  nextInvoicePeriodStart?: string | null
  nextInvoicePeriodEnd?: string | null
  courtesyDays?: number | null
  lastCourtesyDays?: number | null
  lastCourtesyReason?: string | null
  lastCourtesyCreatedAt?: string | null
  paymentAvailable: boolean
  platformCheckoutEnsureAvailable?: boolean
  checkoutEnsureAvailable: boolean
  unavailableReason?: string | null
  checkoutUrl?: string | null
  payableInvoice?: BillingInvoiceFixture | null
  invoices: BillingInvoiceFixture[]
}

type VenueBookingFixture = {
  bookingId: number
  displayNumber?: number | null
  status: string
  scheduledAt: string
  scheduledAtDisplay?: string | null
  scheduledLocalDate?: string | null
  scheduledLocalTime?: string | null
  serviceDate?: string | null
  arrivalDeadlineAt?: string | null
  arrivalDeadlineAtDisplay?: string | null
  partySize?: number | null
  comment?: string | null
  guestDisplayName?: string | null
  lastGuestConfirmationAt?: string | null
}

type GuestBookingFixture = VenueBookingFixture & {
  venueId: number
  venueName?: string | null
  displayLabel?: string | null
  statusLabel?: string | null
  attendanceScheduleVersion?: number | null
  arrivalDeadlineTimeDisplay?: string | null
  canChange?: boolean | null
  canCancel?: boolean | null
}

type SupportThreadFixture = {
  threadId: number
  venueId?: number | null
  venueName?: string | null
  guestDisplayName?: string | null
  threadType?: string
  assigneeScope?: string
  category: string
  contextLabel?: string | null
  status: string
  statusLabel?: string | null
  bookingId?: number | null
  orderId?: number | null
  orderDisplayLabel?: string | null
  tableId?: number | null
  tableSessionId?: number | null
  tableLabel?: string | null
  title: string
  lastMessagePreview?: string | null
  lastMessageAt?: string | null
  unreadCount?: number | null
  createdAt: string
  updatedAt: string
  booking?: {
    bookingId: number
    displayNumber?: number | null
    scheduledAt?: string | null
    partySize?: number | null
    status?: string | null
  } | null
}

type SupportMessageFixture = {
  messageId: number
  threadId: number
  authorRole: string
  source: string
  text: string
  createdAt: string
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

type BillRequestPayload = {
  tableToken: string
  tableSessionId: number
  tabId: number
  paymentMethod: string
}

type BillRequestCapture = {
  url: string
  method: string
  contentType: string | undefined
  authorization: string | undefined
  body: BillRequestPayload
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
      sendData?: (data: string) => void
      openTelegramLink?: (url: string) => void
      BackButton?: {
        show?: () => void
        hide?: () => void
        onClick?: (cb: () => void) => void
        offClick?: (cb: () => void) => void
      }
    }
  }
  __e2eTelegramBackButtonVisible?: boolean
  __e2eTelegramSendDataPayloads?: string[]
  __e2eTelegramOpenedLinks?: string[]
}

function jsonResponse(data: unknown, status = 200) {
  return {
    status,
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

function buildBookingSettings(overrides: Partial<BookingSettings> = {}): BookingSettings {
  return {
    venueId: 1,
    holdMinutes: 30,
    defaultHoldMinutes: 30,
    minHoldMinutes: 10,
    maxHoldMinutes: 240,
    quickHoldMinutes: [30, 60],
    ...overrides
  }
}

function buildVenueScheduleSettings(overrides: Partial<VenueScheduleSettings> = {}): VenueScheduleSettings {
  return {
    venueId: 1,
    weeklyHours: [1, 2, 3, 4, 5, 6, 7].map((weekday) => ({
      weekday,
      opensAt: '18:00',
      closesAt: '00:00',
      isClosed: false,
      configured: true
    })),
    dateOverrides: [],
    ...overrides
  }
}

function addIsoDays(value: string, days: number): string {
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

function eachIsoDate(fromDate: string, toDate: string): string[] {
  const dates: string[] = []
  let date = fromDate
  while (date <= toDate) {
    dates.push(date)
    date = addIsoDays(date, 1)
  }
  return dates
}

function countryNameForRoute(countryCode?: string | null): string | null {
  switch (countryCode?.trim().toUpperCase()) {
    case 'RU':
      return 'Россия'
    case 'KZ':
      return 'Казахстан'
    case 'BY':
      return 'Беларусь'
    default:
      return countryCode?.trim().toUpperCase() || null
  }
}

function buildTextRouteUrl(name: string, countryCode: string | null | undefined, city: string | null, address: string | null): string {
  const routeAddress = [countryNameForRoute(countryCode), city, address].filter(Boolean).join(', ')
  return `https://yandex.ru/maps/?text=${encodeURIComponent(`${name}, ${routeAddress || 'Адрес уточняется'}`)}`
}

function buildPublicCardSettings(overrides: Partial<PublicCardSettings> = {}): PublicCardSettings {
  return {
    venueId: 1,
    name: 'Микс',
    city: 'Москва',
    address: 'Пилотная, 1',
    countryCode: 'RU',
    formattedAddress: null,
    displayAddress: 'Москва, Пилотная, 1',
    latitude: null,
    longitude: null,
    routeUrl: buildTextRouteUrl('Микс', 'RU', 'Москва', 'Пилотная, 1'),
    guestContact: null,
    cardDescription: null,
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

function buildGuestBooking(overrides: Partial<GuestBookingFixture> = {}): GuestBookingFixture {
  return {
    bookingId: 501,
    venueId: 1,
    venueName: 'Микс',
    displayNumber: 1,
    displayLabel: 'Бронь №1',
    status: 'confirmed',
    statusLabel: 'Подтверждена',
    scheduledAt: '2030-01-10T18:00:00Z',
    scheduledAtDisplay: '10.01.2030, 21:00',
    scheduledLocalDate: '2030-01-10',
    scheduledLocalTime: '21:00',
    arrivalDeadlineAt: '2030-01-10T18:15:00Z',
    arrivalDeadlineAtDisplay: '10.01.2030, 21:15',
    arrivalDeadlineTimeDisplay: '21:15',
    partySize: 3,
    comment: 'у окна',
    lastGuestConfirmationAt: null,
    attendanceScheduleVersion: 1894312800,
    canChange: true,
    canCancel: true,
    ...overrides
  }
}

async function installTelegramWebApp(page: Page, userId: number) {
  await page.route('https://telegram.org/js/telegram-web-app.js', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/javascript',
      body: 'window.Telegram = window.Telegram || { WebApp: {} };'
    })
  })
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
        window.__e2eTelegramSendDataPayloads = [];
        window.__e2eTelegramOpenedLinks = [];
        const telegramApi = {
          WebApp: {
            initData: nextInitData,
            initDataUnsafe: { user: { id: nextUserId } },
            ready: () => undefined,
            expand: () => undefined,
            close: () => undefined,
            sendData: (data) => {
              window.__e2eTelegramSendDataPayloads.push(String(data));
            },
            openTelegramLink: (url) => {
              window.__e2eTelegramOpenedLinks.push(String(url));
            },
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
    extensionOptions?: ShiftExtensionOptions
    extensionOptionsError?: ApiErrorFixture | null
    menuCategories?: GuestMenuCategory[]
    bookings?: GuestBookingFixture[]
    bookingCreateError?: { code: string; message: string }
    tableSessionEndResponse?: TableSessionEndResponseFixture
    activeOrder?: ActiveOrderFixtureOptions | null
    todayStaff?: Array<Record<string, unknown>>
    visitHistory?: GuestVisitHistoryFixture
  } = {}
) {
  let structuredMenuCalls = 0
  let restoreContext = options.restoreContext ?? null
  let extensionOptions = options.extensionOptions ?? buildShiftExtensionOptions()
  let extensionOptionsError = options.extensionOptionsError ?? null
  const menuCategories = options.menuCategories ?? buildDefaultGuestMenu()
  let bookings = options.bookings ?? []
  let bookingCreateError = options.bookingCreateError ?? null
  let tableSessionEndResponse =
    options.tableSessionEndResponse ??
    {
      ended: true,
      tableSessionId: 77,
      blockedReason: null,
      message: 'Визит завершён. Чтобы снова заказать за столом, отсканируйте QR.'
    }
  let activeOrderOptions: ActiveOrderFixtureOptions | null = options.activeOrder === undefined ? {} : options.activeOrder
  const todayStaff = options.todayStaff ?? []
  const visitHistory = options.visitHistory ?? { items: [], details: {} }
  let createExtensionRequestCalls = 0
  let nextBookingId = 9000
  let activeOrderServiceCharges: ServiceCharge[] = []
  const previewRequests: Array<{ items: AddBatchItemPayload[] }> = []
  const addBatchRequests: AddBatchPayload[] = []
  const billRequestRequests: BillRequestCapture[] = []
  const tableSessionEndRequests: Array<{
    url: string
    method: string
    contentType: string | undefined
    body: { tableToken: string; tableSessionId: number }
  }> = []
  let submittedOrderItems: AddBatchItemPayload[] = []
  let activeBillRequestId: number | null = null
  const bookingUpdateRequests: Array<{ venueId: number; bookingId: number; scheduledAt: string; partySize?: number | null; comment?: string | null }> = []
  const bookingCancelRequests: Array<{ venueId: number; bookingId: number }> = []
  const staffCallRequests: Array<{ tableToken: string; tableSessionId: number; reason: string; comment?: string | null }> = []
  let staffCallStatuses: Array<{
    staffCallId: number
    status: string
    statusLabel: string
    createdAtEpochSeconds: number
    reason: string
    reasonLabel: string
    comment?: string | null
  }> = []

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
            countryCode: 'RU',
            displayAddress: 'Москва, Пилотная, 1',
            routeUrl: buildTextRouteUrl('Микс', 'RU', 'Москва', 'Пилотная, 1'),
            cardDescription: 'Тестовая карточка',
            todaySchedule: {
              date: '2030-01-10',
              isConfigured: false,
              isClosed: false,
              isOpenNow: false,
              statusLabel: 'График не указан',
              timeLabel: null
            }
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
          countryCode: 'RU',
          displayAddress: 'Москва, Пилотная, 1',
          routeUrl: buildTextRouteUrl('Микс', 'RU', 'Москва', 'Пилотная, 1'),
          guestContact: '+7 000 000-00-00',
          cardDescription: 'Текстовая информация о заведении',
          todaySchedule: {
            date: '2030-01-10',
            isConfigured: false,
            isClosed: false,
            isOpenNow: false,
            statusLabel: 'График не указан',
            timeLabel: null
          },
          todayStaff,
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

  await page.route('**/api/guest/booking**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const activeBookings = () =>
      bookings
        .filter((booking) => ['pending', 'confirmed', 'changed'].includes(booking.status))
        .sort((left, right) => left.scheduledAt.localeCompare(right.scheduledAt) || right.bookingId - left.bookingId)

    if (path === '/api/guest/bookings' && request.method() === 'GET') {
      await route.fulfill(jsonResponse({ items: activeBookings() }))
      return
    }

    if (path === '/api/guest/booking' && request.method() === 'GET') {
      const venueId = Number(url.searchParams.get('venueId'))
      await route.fulfill(jsonResponse({ items: bookings.filter((booking) => booking.venueId === venueId) }))
      return
    }

    if (path === '/api/guest/booking/create' && request.method() === 'POST') {
      if (bookingCreateError) {
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ error: bookingCreateError })
        })
        return
      }
      const body = (await request.postDataJSON()) as {
        venueId: number
        scheduledAt: string
        partySize?: number | null
        comment?: string | null
      }
      const booking = buildGuestBooking({
        bookingId: nextBookingId++,
        venueId: body.venueId,
        scheduledAt: body.scheduledAt,
        scheduledAtDisplay: '11.01.2030, 19:00',
        scheduledLocalDate: '2030-01-11',
        scheduledLocalTime: '19:00',
        status: 'pending',
        statusLabel: 'Ожидает подтверждения',
        partySize: body.partySize ?? null,
        comment: body.comment ?? null,
        canChange: true,
        canCancel: true
      })
      bookings = [...bookings, booking]
      await route.fulfill(jsonResponse(booking))
      return
    }

    const venueId = Number(url.searchParams.get('venueId'))
    const body = request.method() === 'POST' ? (await request.postDataJSON()) as {
      bookingId: number
      scheduledAt?: string
      partySize?: number | null
      comment?: string | null
      attendanceScheduleVersion?: number | null
    } : null
    const booking = body ? bookings.find((item) => item.bookingId === body.bookingId && item.venueId === venueId) : null
    if (!booking) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }

    if (path === '/api/guest/booking/update' && request.method() === 'POST') {
      bookingUpdateRequests.push({
        venueId,
        bookingId: booking.bookingId,
        scheduledAt: body?.scheduledAt ?? booking.scheduledAt,
        partySize: body?.partySize ?? null,
        comment: body?.comment ?? null
      })
      booking.status = 'pending'
      booking.statusLabel = 'Ожидает подтверждения'
      booking.scheduledAt = body?.scheduledAt ?? booking.scheduledAt
      booking.scheduledAtDisplay = '11.01.2030, 20:30'
      booking.scheduledLocalDate = '2030-01-11'
      booking.scheduledLocalTime = '20:30'
      booking.partySize = body?.partySize ?? booking.partySize
      booking.comment = body?.comment ?? booking.comment
      await route.fulfill(jsonResponse(booking))
      return
    }

    if (path === '/api/guest/booking/cancel' && request.method() === 'POST') {
      bookingCancelRequests.push({ venueId, bookingId: booking.bookingId })
      booking.status = 'canceled'
      booking.statusLabel = 'Отменена'
      booking.canChange = false
      booking.canCancel = false
      await route.fulfill(jsonResponse(booking))
      return
    }

    if (path === '/api/guest/booking/confirm' && request.method() === 'POST') {
      if (body?.attendanceScheduleVersion !== booking.attendanceScheduleVersion) {
        await route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({ error: 'stale booking schedule' })
        })
        return
      }
      booking.lastGuestConfirmationAt = '10.01.2030, 21:05'
      await route.fulfill(jsonResponse(booking))
      return
    }

    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
  })

  await page.route('**/api/guest/visits**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/guest/visits' && request.method() === 'GET') {
      await route.fulfill(jsonResponse({ items: visitHistory.items }))
      return
    }

    const detailMatch = path.match(/^\/api\/guest\/visits\/(\d+)$/)
    if (detailMatch && request.method() === 'GET') {
      const visitId = Number(detailMatch[1])
      const detail = visitHistory.details[visitId]
      if (detail) {
        await route.fulfill(jsonResponse({ visit: detail }))
      } else {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'NOT_FOUND', message: 'Visit not found' } })
        })
      }
      return
    }

    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
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

  await page.route('**/api/guest/table/session/end', async (route) => {
    const request = route.request()
    const body = (await request.postDataJSON()) as { tableToken: string; tableSessionId: number }
    tableSessionEndRequests.push({
      url: request.url(),
      method: request.method(),
      contentType: request.headers()['content-type'],
      body
    })
    if (tableSessionEndResponse.ended) {
      restoreContext = null
    }
    await route.fulfill(jsonResponse(tableSessionEndResponse))
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
    if (activeOrderOptions === null) {
      await route.fulfill(jsonResponse({ order: null }))
      return
    }
    const orderItems = buildActiveOrderItems()
    const firstItem = orderItems[0]
    if (firstItem) {
      const manualDiscountMinor = Math.min(
        activeOrderOptions.itemManualDiscountMinor ?? 0,
        firstItem.lineGrossMinor
      )
      const promoDiscountMinor = Math.min(
        activeOrderOptions.itemPromoDiscountMinor ?? 0,
        firstItem.lineGrossMinor - manualDiscountMinor
      )
      firstItem.manualDiscountMinor = manualDiscountMinor
      firstItem.promoDiscountMinor = promoDiscountMinor
      firstItem.discountMinor = manualDiscountMinor + promoDiscountMinor
      firstItem.linePayableMinor = firstItem.lineGrossMinor - manualDiscountMinor - promoDiscountMinor
    }
    const orderItemsTotal = orderItems.reduce((sum, item) => sum + item.lineGrossMinor, 0)
    const manualDiscountTotal = orderItems.reduce((sum, item) => sum + item.manualDiscountMinor, 0)
    const promoDiscountTotal = orderItems.reduce((sum, item) => sum + item.promoDiscountMinor, 0)
    const payableItemsTotal = orderItems.reduce((sum, item) => sum + item.linePayableMinor, 0)
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
          status: activeOrderOptions.status ?? 'ACTIVE',
          grossTotalMinor: orderItemsTotal + serviceChargeTotal,
          manualDiscountTotalMinor: manualDiscountTotal,
          promoDiscountTotalMinor: promoDiscountTotal,
          loyaltyDiscountTotalMinor: 0,
          finalPayableTotalMinor: payableItemsTotal + serviceChargeTotal,
          currency: 'RUB',
          discounts: promoDiscountTotal > 0
            ? [
                {
                  label: 'Скидка',
                  discountMinor: promoDiscountTotal,
                  currency: 'RUB',
                  ruleType: 'PROMO'
                }
              ]
            : [],
          serviceCharges: activeOrderServiceCharges,
          batches: [
            {
              batchId: 333,
              status: activeOrderOptions.batchStatus ?? 'NEW',
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

  await page.route('**/api/guest/order/bill-request', async (route) => {
    const request = route.request()
    const body = (await request.postDataJSON()) as BillRequestPayload
    billRequestRequests.push({
      url: request.url(),
      method: request.method(),
      contentType: request.headers()['content-type'],
      authorization: request.headers()['authorization'],
      body
    })
    if (activeBillRequestId != null) {
      await route.fulfill(
        jsonResponse({
          staffCallId: activeBillRequestId,
          createdAtEpochSeconds: 1894302000,
          status: 'NEW',
          statusLabel: 'Запрос на счёт отправлен',
          paymentMethod: 'CARD',
          paymentMethodLabel: 'Картой на месте',
          alreadyActive: true,
          message: 'Запрос на счёт уже отправлен. Персонал скоро подойдёт.'
        })
      )
      return
    }
    activeBillRequestId = 902
    await route.fulfill(
      jsonResponse({
        staffCallId: activeBillRequestId,
        createdAtEpochSeconds: 1894302000,
        status: 'NEW',
        statusLabel: 'Запрос на счёт отправлен',
        paymentMethod: body.paymentMethod,
        paymentMethodLabel:
          body.paymentMethod === 'CASH'
            ? 'Наличными'
            : body.paymentMethod === 'UNKNOWN'
              ? 'Пока не знаю'
              : 'Картой на месте',
        alreadyActive: false,
        message: 'Персонал получил запрос на счёт.'
      })
    )
  })

  await page.route('**/api/guest/staff-call/status?**', async (route) => {
    await route.fulfill(jsonResponse({ items: staffCallStatuses }))
  })

  await page.route('**/api/guest/staff-call', async (route) => {
    const body = (await route.request().postDataJSON()) as {
      tableToken: string
      tableSessionId: number
      reason: string
      comment?: string | null
    }
    staffCallRequests.push(body)
    staffCallStatuses = [
      {
        staffCallId: 901,
        status: 'NEW',
        statusLabel: 'Вызов отправлен',
        createdAtEpochSeconds: 1894302000,
        reason: body.reason,
        reasonLabel: body.reason === 'COALS' ? 'Заменить угли' : 'Вызов персонала',
        comment: body.comment ?? null
      }
    ]
    await route.fulfill(
      jsonResponse({
        staffCallId: 901,
        createdAtEpochSeconds: 1894302000,
        status: 'NEW',
        statusLabel: 'Вызов отправлен'
      })
    )
  })

  await page.route('**/api/guest/table/extension-options?**', async (route) => {
    if (extensionOptionsError) {
      await route.fulfill({
        status: extensionOptionsError.status,
        contentType: 'application/json',
        body: JSON.stringify({
          error: {
            code: extensionOptionsError.code,
            message: extensionOptionsError.message
          }
        })
      })
      return
    }
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
    getBookingUpdateRequests: () => bookingUpdateRequests,
    getBookingCancelRequests: () => bookingCancelRequests,
    getGuestBookings: () => bookings,
    getPreviewRequests: () => previewRequests,
    getAddBatchRequests: () => addBatchRequests,
    getBillRequestRequests: () => billRequestRequests,
    getTableSessionEndRequests: () => tableSessionEndRequests,
    getStaffCallRequests: () => staffCallRequests,
    setStaffCallStatuses: (items: typeof staffCallStatuses) => {
      staffCallStatuses = items
    },
    setRestoreContext: (context: RestoreContext | null) => {
      restoreContext = context
    },
    setBookingCreateError: (error: { code: string; message: string } | null) => {
      bookingCreateError = error
    },
    setTableSessionEndResponse: (response: TableSessionEndResponseFixture) => {
      tableSessionEndResponse = response
    },
    setExtensionOptions: (options: ShiftExtensionOptions) => {
      extensionOptionsError = null
      extensionOptions = options
    },
    setExtensionOptionsError: (error: ApiErrorFixture | null) => {
      extensionOptionsError = error
    },
    setActiveOrderServiceCharges: (charges: ServiceCharge[]) => {
      activeOrderServiceCharges = charges
    },
    setActiveOrder: (order: ActiveOrderFixtureOptions | null) => {
      activeOrderOptions = order
    }
  }
}

async function mockVenueShiftExtensionApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    permissions?: string[]
    settings?: ShiftExtensionSettings
    bookingSettings?: BookingSettings
    scheduleSettings?: VenueScheduleSettings
    publicCardSettings?: PublicCardSettings
    failPublicCardUpdateOnce?: boolean
  } = {}
) {
  const role = options.role ?? 'STAFF'
  const permissions = options.permissions ?? ['ORDER_QUEUE_VIEW', 'SHIFT_EXTENSION_VIEW', 'SHIFT_EXTENSION_CONFIRM']
  let requests = [buildShiftExtensionRequest()]
  let settings = options.settings ?? buildShiftExtensionSettings()
  let bookingSettings = options.bookingSettings ?? buildBookingSettings()
  let scheduleSettings = options.scheduleSettings ?? buildVenueScheduleSettings()
  let publicCardSettings = options.publicCardSettings ?? buildPublicCardSettings()
  let approveCalls = 0
  let rejectCalls = 0
  let updateSettingsCalls = 0
  let updateBookingSettingsCalls = 0
  let updatePublicCardSettingsCalls = 0
  let locationProviderCalls = 0
  let failPublicCardUpdateOnce = options.failPublicCardUpdateOnce === true
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
          name: publicCardSettings.name,
          city: publicCardSettings.city,
          address: publicCardSettings.address,
          countryCode: publicCardSettings.countryCode,
          formattedAddress: publicCardSettings.formattedAddress,
          displayAddress: publicCardSettings.displayAddress,
          latitude: publicCardSettings.latitude,
          longitude: publicCardSettings.longitude,
          routeUrl: publicCardSettings.routeUrl,
          guestContact: publicCardSettings.guestContact,
          cardDescription: publicCardSettings.cardDescription,
          status: 'PUBLISHED'
        }
      })
    )
  })

  await page.route('**/api/guest/venue/1/info-sections', async (route) => {
    await route.fulfill(jsonResponse({ venueId: 1, sections: [] }))
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
      manualDiscountTotalMinor: 12000,
      promoDiscountTotalMinor: 0,
      loyaltyDiscountTotalMinor: 0,
      excludedTotalMinor: 30000,
      canceledTotalMinor: 0,
      rejectedTotalMinor: 0,
      finalPayableTotalMinor: 108000 + serviceChargeTotal,
      currency: 'RUB',
      promoDiscounts: [],
      loyaltyDiscounts: [],
      excludedItems: [
        {
          batchId: 300,
          batchLabel: 'Основной заказ',
          tabId: 88,
          tabType: 'PERSONAL',
          tabDisplayLabel: 'Личный счёт гостя',
          batchItemId: 701,
          itemId: 101,
          name: 'Чай',
          qty: 1,
          lineGrossMinor: 30000,
          currency: 'RUB',
          status: 'excluded',
          reason: 'Не учитывать'
        }
      ],
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
              tabId: 88,
              tabType: 'PERSONAL',
              tabDisplayLabel: 'Личный счёт гостя',
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
                  manualDiscountMinor: 12000,
                  promoDiscountMinor: 0,
                  linePayableMinor: 108000,
                  isExcluded: false,
                  discountPercent: 10,
                  itemStatus: 'active'
                },
                {
                  batchItemId: 701,
                  itemId: 101,
                  name: 'Чай',
                  qty: 1,
                  priceMinor: 30000,
                  currency: 'RUB',
                  lineGrossMinor: 30000,
                  manualDiscountMinor: 0,
                  promoDiscountMinor: 0,
                  linePayableMinor: 0,
                  isExcluded: true,
                  excludedReasonText: 'Не учитывать',
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

  await page.route('**/api/venue/1/booking-settings', async (route) => {
    if (route.request().method() === 'PUT') {
      updateBookingSettingsCalls += 1
      const body = (await route.request().postDataJSON()) as { holdMinutes: number }
      bookingSettings = {
        ...bookingSettings,
        holdMinutes: body.holdMinutes
      }
    }
    await route.fulfill(jsonResponse(bookingSettings))
  })

  await page.route('**/api/venue/1/schedule/weekly/*', async (route) => {
    if (route.request().method() !== 'PUT') {
      await route.fallback()
      return
    }
    const weekday = Number(new URL(route.request().url()).pathname.split('/').pop())
    const body = (await route.request().postDataJSON()) as {
      opensAt?: string | null
      closesAt?: string | null
      isClosed?: boolean
    }
    scheduleSettings = {
      ...scheduleSettings,
      weeklyHours: scheduleSettings.weeklyHours.map((day) =>
        day.weekday === weekday
          ? {
              ...day,
              opensAt: body.isClosed ? '00:00' : body.opensAt ?? day.opensAt,
              closesAt: body.isClosed ? '00:00' : body.closesAt ?? day.closesAt,
              isClosed: body.isClosed === true,
              configured: true
            }
          : day
      )
    }
    await route.fulfill(jsonResponse(scheduleSettings))
  })

  await page.route('**/api/venue/1/schedule/override-ranges**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const rangeMatch = path.match(/\/api\/venue\/1\/schedule\/override-ranges\/([^/]+)\/([^/]+)$/)
    if (rangeMatch && request.method() === 'PUT') {
      const originalFromDate = decodeURIComponent(rangeMatch[1])
      const originalToDate = decodeURIComponent(rangeMatch[2])
      const body = (await request.postDataJSON()) as {
        fromDate: string
        toDate: string
        opensAt?: string | null
        closesAt?: string | null
        isClosed?: boolean
        guestNote?: string | null
      }
      const originalDates = eachIsoDate(originalFromDate, originalToDate)
      const dates = eachIsoDate(body.fromDate, body.toDate)
      scheduleSettings = {
        ...scheduleSettings,
        dateOverrides: [
          ...scheduleSettings.dateOverrides.filter(
            (override) => !originalDates.includes(override.serviceDate) && !dates.includes(override.serviceDate)
          ),
          ...dates.map((serviceDate) => ({
            serviceDate,
            opensAt: body.isClosed ? '00:00' : body.opensAt ?? '18:00',
            closesAt: body.isClosed ? '00:00' : body.closesAt ?? '00:00',
            isClosed: body.isClosed === true,
            guestNote: body.guestNote?.trim() || null
          }))
        ].sort((left, right) => left.serviceDate.localeCompare(right.serviceDate))
      }
      await route.fulfill(jsonResponse(scheduleSettings))
      return
    }
    if (rangeMatch && request.method() === 'DELETE') {
      const fromDate = decodeURIComponent(rangeMatch[1])
      const toDate = decodeURIComponent(rangeMatch[2])
      scheduleSettings = {
        ...scheduleSettings,
        dateOverrides: scheduleSettings.dateOverrides.filter(
          (override) => override.serviceDate < fromDate || override.serviceDate > toDate
        )
      }
      await route.fulfill(jsonResponse(scheduleSettings))
      return
    }
    if (path.endsWith('/schedule/override-ranges') && request.method() === 'POST') {
      const body = (await request.postDataJSON()) as {
        fromDate: string
        toDate: string
        opensAt?: string | null
        closesAt?: string | null
        isClosed?: boolean
        guestNote?: string | null
      }
      const dates = eachIsoDate(body.fromDate, body.toDate)
      scheduleSettings = {
        ...scheduleSettings,
        dateOverrides: [
          ...scheduleSettings.dateOverrides.filter((override) => !dates.includes(override.serviceDate)),
          ...dates.map((serviceDate) => ({
            serviceDate,
            opensAt: body.isClosed ? '00:00' : body.opensAt ?? '18:00',
            closesAt: body.isClosed ? '00:00' : body.closesAt ?? '00:00',
            isClosed: body.isClosed === true,
            guestNote: body.guestNote?.trim() || null
          }))
        ].sort((left, right) => left.serviceDate.localeCompare(right.serviceDate))
      }
      await route.fulfill(jsonResponse(scheduleSettings))
      return
    }
    await route.fallback()
  })

  await page.route('**/api/venue/1/schedule/overrides/*', async (route) => {
    const serviceDate = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop() ?? '')
    if (route.request().method() === 'DELETE') {
      scheduleSettings = {
        ...scheduleSettings,
        dateOverrides: scheduleSettings.dateOverrides.filter((override) => override.serviceDate !== serviceDate)
      }
      await route.fulfill(jsonResponse(scheduleSettings))
      return
    }
    if (route.request().method() !== 'PUT') {
      await route.fallback()
      return
    }
    const body = (await route.request().postDataJSON()) as {
      opensAt?: string | null
      closesAt?: string | null
      isClosed?: boolean
      guestNote?: string | null
    }
    const nextOverride = {
      serviceDate,
      opensAt: body.isClosed ? '00:00' : body.opensAt ?? '18:00',
      closesAt: body.isClosed ? '00:00' : body.closesAt ?? '00:00',
      isClosed: body.isClosed === true,
      guestNote: body.guestNote?.trim() || null
    }
    scheduleSettings = {
      ...scheduleSettings,
      dateOverrides: [
        ...scheduleSettings.dateOverrides.filter((override) => override.serviceDate !== serviceDate),
        nextOverride
      ].sort((left, right) => left.serviceDate.localeCompare(right.serviceDate))
    }
    await route.fulfill(jsonResponse(scheduleSettings))
  })

  await page.route('**/api/venue/1/schedule', async (route) => {
    await route.fulfill(jsonResponse(scheduleSettings))
  })

  await page.route('**/api/venue/1/public-card', async (route) => {
    if (route.request().method() === 'PUT') {
      updatePublicCardSettingsCalls += 1
      if (failPublicCardUpdateOnce) {
        failPublicCardUpdateOnce = false
        await route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'INVALID_INPUT', message: 'Не удалось сохранить публичную карточку.' } })
        })
        return
      }
      const body = (await route.request().postDataJSON()) as {
        city?: string | null
        address?: string | null
        countryCode?: string | null
        formattedAddress?: string | null
        latitude?: number | null
        longitude?: number | null
        guestContact?: string | null
        cardDescription?: string | null
      }
      const city = body.city?.trim() || null
      const address = body.address?.trim() || null
      const formattedAddress = body.formattedAddress?.trim() || null
      const latitude = typeof body.latitude === 'number' ? body.latitude : null
      const longitude = typeof body.longitude === 'number' ? body.longitude : null
      const displayAddress =
        formattedAddress?.replace(/^Россия,\s*/, '') || [city, address].filter(Boolean).join(', ') || null
      publicCardSettings = {
        ...publicCardSettings,
        city,
        address,
        countryCode: body.countryCode?.trim().toUpperCase() || null,
        formattedAddress,
        displayAddress,
        latitude,
        longitude,
        routeUrl:
          latitude != null && longitude != null
            ? `https://yandex.ru/maps/?rtext=~${latitude},${longitude}&rtt=auto`
            : buildTextRouteUrl(publicCardSettings.name, body.countryCode, city, address),
        guestContact: body.guestContact?.trim() || null,
        cardDescription: body.cardDescription?.trim() || null
      }
    }
    await route.fulfill(jsonResponse(publicCardSettings))
  })

  await page.route('**/api/venue/1/location/suggestions?**', async (route) => {
    locationProviderCalls += 1
    const url = new URL(route.request().url())
    const kind = url.searchParams.get('kind')
    if (kind === 'city') {
      await route.fulfill(
        jsonResponse({
          items: [
            {
              id: 'city-spb',
              title: 'Санкт-Петербург',
              subtitle: 'Россия',
              countryCode: 'RU',
              city: 'Санкт-Петербург',
              address: null,
              formattedAddress: 'Россия, Санкт-Петербург',
              providerUri: 'ymapsbm1://geo?data=city-spb'
            }
          ],
          unavailable: false
        })
      )
      return
    }
    await route.fulfill(
      jsonResponse({
        items: [
          {
            id: 'address-liteinyi-7',
            title: 'Литейный проспект, 7',
            subtitle: 'Санкт-Петербург',
            countryCode: 'RU',
            city: 'Санкт-Петербург',
            address: 'Литейный проспект, 7',
            formattedAddress: 'Россия, Санкт-Петербург, Литейный проспект, 7',
            providerUri: 'ymapsbm1://geo?data=liteinyi-7'
          }
        ],
        unavailable: false
      })
    )
  })

  await page.route('**/api/venue/1/location/resolve', async (route) => {
    locationProviderCalls += 1
    await route.fulfill(
      jsonResponse({
        location: {
          countryCode: 'RU',
          city: 'Санкт-Петербург',
          address: 'Литейный проспект, 7',
          formattedAddress: 'Россия, Санкт-Петербург, Литейный проспект, 7',
          latitude: 59.9386,
          longitude: 30.3451
        },
        unavailable: false
      })
    )
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
    getUpdateBookingSettingsCalls: () => updateBookingSettingsCalls,
    getUpdatePublicCardSettingsCalls: () => updatePublicCardSettingsCalls,
    getLocationProviderCalls: () => locationProviderCalls,
    getSettings: () => settings,
    getBookingSettings: () => bookingSettings,
    getScheduleSettings: () => scheduleSettings,
    getPublicCardSettings: () => publicCardSettings,
    getRejectedReasons: () => rejectedReasons,
    setRequests: (nextRequests: ShiftExtensionRequest[]) => {
      requests = nextRequests
    }
  }
}

function buildVenueStats(period: 'today' | '7d' | '30d', overrides: Partial<VenueStatsResponse> = {}): VenueStatsResponse {
  const titles = {
    today: 'Сегодня',
    '7d': '7 дней',
    '30d': '30 дней'
  } as const
  const ordersCount = period === 'today' ? 4 : period === '7d' ? 8 : 15
  return {
    venueId: 1,
    period,
    periodTitle: titles[period],
    periodStart: '2026-06-16T00:00:00+03:00',
    ordersCount,
    revenueMinor: ordersCount * 125000,
    averageCheckMinor: 125000,
    discountMinor: period === 'today' ? 15000 : 30000,
    cancelledItemsCount: period === 'today' ? 1 : 2,
    currency: 'RUB',
    topItems: [
      { itemName: 'Кальян', qty: period === 'today' ? 5 : 11 },
      { itemName: 'Чай', qty: 3 }
    ],
    ...overrides
  }
}

async function mockVenueStatsApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    permissions?: string[]
    statsByPeriod?: Partial<Record<'today' | '7d' | '30d', VenueStatsResponse>>
  } = {}
) {
  const role = options.role ?? 'MANAGER'
  const permissions = options.permissions ?? []
  const periods: string[] = []

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

  await page.route('**/api/venue/1/stats**', async (route) => {
    const url = new URL(route.request().url())
    const period = (url.searchParams.get('period') || 'today') as 'today' | '7d' | '30d'
    periods.push(period)
    const stats = options.statsByPeriod?.[period] ?? buildVenueStats(period)
    await route.fulfill(jsonResponse(stats))
  })

  return {
    getPeriods: () => periods
  }
}

function buildBillingOverview(overrides: Partial<BillingOverviewFixture> = {}): BillingOverviewFixture {
  const invoice: BillingInvoiceFixture = {
    id: 77,
    periodStart: '2026-07-01',
    periodEnd: '2026-07-31',
    dueAt: '2026-07-01T00:00:00Z',
    amountMinor: 150000,
    currency: 'RUB',
    status: 'OPEN',
    checkoutUrl: null
  }
  return {
    venueId: 1,
    subscriptionStatus: 'past_due',
    trialEndAt: '2026-06-30T00:00:00Z',
    paidStartAt: '2026-07-01T00:00:00Z',
    lifecycleUpdatedAt: '2026-07-01T00:00:00Z',
    settingsTrialEndDate: '2026-06-30',
    settingsPaidStartDate: '2026-07-01',
    priceMinor: 150000,
    currency: 'RUB',
    basePaidThrough: null,
    paidThrough: null,
    nextPaymentDate: null,
    nextInvoicePeriodStart: '2026-07-01',
    nextInvoicePeriodEnd: '2026-07-31',
    courtesyDays: 0,
    paymentAvailable: false,
    platformCheckoutEnsureAvailable: true,
    checkoutEnsureAvailable: true,
    unavailableReason: 'external_checkout_unavailable',
    checkoutUrl: null,
    payableInvoice: invoice,
    invoices: [invoice],
    ...overrides
  }
}

function withCheckout(overview: BillingOverviewFixture): BillingOverviewFixture {
  const payableInvoice =
    overview.invoices.find((invoice) => invoice.status === 'OPEN' || invoice.status === 'PAST_DUE') ??
    overview.invoices[0]
  const invoice = {
    ...payableInvoice,
    checkoutUrl: `https://pay.example.test/checkout?invoice_id=${payableInvoice.id}`
  }
  return {
    ...overview,
    paymentAvailable: true,
    unavailableReason: null,
    checkoutUrl: invoice.checkoutUrl,
    payableInvoice: invoice,
    invoices: overview.invoices.map((item) => (item.id === invoice.id ? invoice : item))
  }
}

function addDaysIso(dateOnly: string, days: number): string {
  const [year, month, day] = dateOnly.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  date.setUTCDate(date.getUTCDate() + days)
  return [
    date.getUTCFullYear(),
    String(date.getUTCMonth() + 1).padStart(2, '0'),
    String(date.getUTCDate()).padStart(2, '0')
  ].join('-')
}

function nextPeriodEndIso(periodStart: string): string {
  const [year, month, day] = periodStart.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  date.setUTCMonth(date.getUTCMonth() + 1)
  date.setUTCDate(date.getUTCDate() - 1)
  return [
    date.getUTCFullYear(),
    String(date.getUTCMonth() + 1).padStart(2, '0'),
    String(date.getUTCDate()).padStart(2, '0')
  ].join('-')
}

async function mockPlatformBillingApi(
  page: Page,
  options: {
    overview?: Partial<BillingOverviewFixture>
    manualOnly?: boolean
  } = {}
) {
  let billingGetCalls = 0
  let checkoutPostCalls = 0
  let courtesyPostCalls = 0
  let markPaidCalls = 0
  let lastSubscriptionUpdate: Record<string, unknown> | null = null
  let overview = buildBillingOverview(options.overview)
  let subscriptionSettings = {
    trialEndDate: '2026-06-30',
    paidStartDate: '2026-07-01',
    basePriceMinor: 150000,
    priceOverrideMinor: null as number | null,
    currency: 'RUB'
  }

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-platform-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })

  await page.route('**/api/platform/me', async (route) => {
    await route.fulfill(jsonResponse({ ok: true, ownerUserId: 123456789 }))
  })

  await page.route('**/api/platform/venues?**', async (route) => {
    await route.fulfill(
      jsonResponse({
        venues: [
          {
            id: 1,
            name: 'Микс',
            status: 'PUBLISHED',
            createdAt: '2026-07-01T00:00:00Z',
            ownersCount: 1,
            subscriptionSummary: { trialEndDate: null, paidStartDate: '2026-07-01', isPaid: false }
          }
        ]
      })
    )
  })

  await page.route('**/api/platform/venues/1', async (route) => {
    await route.fulfill(
      jsonResponse({
        venue: {
          id: 1,
          name: 'Микс',
          city: 'Москва',
          address: 'Пилотная, 1',
          status: 'PUBLISHED',
          createdAt: '2026-07-01T00:00:00Z',
          deletedAt: null
        },
        owners: [{ userId: 123456789, role: 'OWNER', username: 'owner', firstName: 'Owner', lastName: null }],
        subscriptionSummary: { trialEndDate: null, paidStartDate: '2026-07-01', isPaid: false }
      })
    )
  })

  await page.route('**/api/platform/venues/1/subscription', async (route) => {
    if (route.request().method() === 'PUT') {
      lastSubscriptionUpdate = route.request().postDataJSON() as Record<string, unknown>
      subscriptionSettings = {
        ...subscriptionSettings,
        trialEndDate: (lastSubscriptionUpdate.trialEndDate as string | null | undefined) ?? null,
        paidStartDate: (lastSubscriptionUpdate.paidStartDate as string | null | undefined) ?? null,
        basePriceMinor: (lastSubscriptionUpdate.basePriceMinor as number | null | undefined) ?? null,
        priceOverrideMinor: (lastSubscriptionUpdate.priceOverrideMinor as number | null | undefined) ?? null,
        currency: (lastSubscriptionUpdate.currency as string | null | undefined) ?? 'RUB'
      }
      const effectivePrice = subscriptionSettings.priceOverrideMinor ?? subscriptionSettings.basePriceMinor
      overview = {
        ...overview,
        trialEndAt: subscriptionSettings.trialEndDate ? `${subscriptionSettings.trialEndDate}T00:00:00Z` : null,
        paidStartAt: subscriptionSettings.paidStartDate ? `${subscriptionSettings.paidStartDate}T00:00:00Z` : null,
        settingsTrialEndDate: subscriptionSettings.trialEndDate,
        settingsPaidStartDate: subscriptionSettings.paidStartDate,
        priceMinor: effectivePrice,
        currency: subscriptionSettings.currency
      }
    }
    await route.fulfill(
      jsonResponse({
        settings: subscriptionSettings,
        schedule: [],
        effectivePriceToday: {
          priceMinor: subscriptionSettings.priceOverrideMinor ?? subscriptionSettings.basePriceMinor,
          currency: subscriptionSettings.currency
        }
      })
    )
  })

  await page.route('**/api/platform/venues/1/billing', async (route) => {
    billingGetCalls += 1
    await route.fulfill(jsonResponse(overview))
  })

  await page.route('**/api/platform/venues/1/billing/checkout', async (route) => {
    checkoutPostCalls += 1
    const hasPayable = overview.invoices.some((invoice) => invoice.status === 'OPEN' || invoice.status === 'PAST_DUE')
    if (!hasPayable && overview.nextInvoicePeriodStart && overview.nextInvoicePeriodEnd) {
      const nextInvoice: BillingInvoiceFixture = {
        id: 78,
        periodStart: overview.nextInvoicePeriodStart,
        periodEnd: overview.nextInvoicePeriodEnd,
        dueAt: `${overview.nextInvoicePeriodStart}T00:00:00Z`,
        amountMinor: overview.priceMinor ?? 150000,
        currency: overview.currency ?? 'RUB',
        status: 'OPEN',
        checkoutUrl: null
      }
      overview = {
        ...overview,
        unavailableReason: 'external_checkout_unavailable',
        payableInvoice: nextInvoice,
        invoices: [nextInvoice, ...overview.invoices]
      }
    }
    overview = options.manualOnly ? overview : withCheckout(overview)
    await route.fulfill(jsonResponse(overview))
  })

  await page.route('**/api/platform/venues/1/billing/courtesy-days', async (route) => {
    courtesyPostCalls += 1
    const body = route.request().postDataJSON() as { days?: number; reason?: string }
    if (!body.reason?.trim()) {
      await route.fulfill(jsonResponse({ error: { code: 'INVALID_INPUT', message: 'reason must not be blank' } }, 400))
      return
    }
    const previousPaidThrough = overview.paidThrough ?? overview.basePaidThrough
    if (!previousPaidThrough) {
      await route.fulfill(
        jsonResponse({ error: { code: 'INVALID_INPUT', message: 'NO_PAID_PERIOD_TO_EXTEND' } }, 400)
      )
      return
    }
    const days = body.days ?? 0
    const newPaidThrough = addDaysIso(previousPaidThrough, days)
    const nextStart = addDaysIso(newPaidThrough, 1)
    overview = {
      ...overview,
      paidThrough: newPaidThrough,
      nextPaymentDate: nextStart,
      nextInvoicePeriodStart: nextStart,
      nextInvoicePeriodEnd: nextPeriodEndIso(nextStart),
      courtesyDays: (overview.courtesyDays ?? 0) + days,
      lastCourtesyDays: days,
      lastCourtesyReason: body.reason
    }
    await route.fulfill(jsonResponse(overview))
  })

  await page.route('**/api/platform/invoices/*/mark-paid', async (route) => {
    markPaidCalls += 1
    const paidInvoice = { ...overview.invoices[0], status: 'PAID', paidAt: '2026-07-02T10:00:00Z' }
    overview = {
      ...overview,
      subscriptionStatus: 'active',
      basePaidThrough: paidInvoice.periodEnd,
      paidThrough: paidInvoice.periodEnd,
      nextPaymentDate: '2026-08-01',
      nextInvoicePeriodStart: '2026-08-01',
      nextInvoicePeriodEnd: '2026-08-31',
      paymentAvailable: false,
      platformCheckoutEnsureAvailable: true,
      unavailableReason: 'already_paid',
      checkoutUrl: null,
      payableInvoice: null,
      invoices: [paidInvoice]
    }
    await route.fulfill(jsonResponse({ ok: true, alreadyPaid: false }))
  })

  return {
    getBillingGetCalls: () => billingGetCalls,
    getCheckoutPostCalls: () => checkoutPostCalls,
    getCourtesyPostCalls: () => courtesyPostCalls,
    getMarkPaidCalls: () => markPaidCalls,
    getLastSubscriptionUpdate: () => lastSubscriptionUpdate
  }
}

async function mockVenueBillingApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    permissions?: string[]
    overview?: Partial<BillingOverviewFixture>
  } = {}
) {
  const role = options.role ?? 'OWNER'
  const permissions = options.permissions ?? ['ORDER_QUEUE_VIEW']
  let subscriptionGetCalls = 0
  let checkoutPostCalls = 0
  let overview = buildBillingOverview({
    subscriptionStatus: 'active',
    unavailableReason: 'external_checkout_unavailable',
    ...options.overview
  })

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-venue-token', expiresAtEpochSeconds: sessionExpiresAt }))
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
        venue: { id: 1, name: 'Микс', city: 'Москва', address: 'Пилотная, 1', status: 'PUBLISHED' }
      })
    )
  })

  await page.route('**/api/venue/1/staff-calls**', async (route) => {
    await route.fulfill(jsonResponse({ items: [] }))
  })

  await page.route('**/api/venue/1/subscription', async (route) => {
    subscriptionGetCalls += 1
    await route.fulfill(jsonResponse(overview))
  })

  await page.route('**/api/venue/1/subscription/checkout', async (route) => {
    checkoutPostCalls += 1
    overview = withCheckout(overview)
    await route.fulfill(jsonResponse(overview))
  })

  return {
    getSubscriptionGetCalls: () => subscriptionGetCalls,
    getCheckoutPostCalls: () => checkoutPostCalls
  }
}

async function mockVenueStaffCallsApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    permissions?: string[]
    includeBillRequest?: boolean
  } = {}
) {
  const role = options.role ?? 'STAFF'
  const permissions = options.permissions ?? ['ORDER_QUEUE_VIEW', 'ORDER_STATUS_UPDATE']
  let ackCalls = 0
  let doneCalls = 0
  const calls: Array<{
    id: number
    tableId: number
    tableNumber: number
    reason: string
    reasonLabel: string
    comment: string | null
    status: string
    statusLabel: string
    createdAt: string
    guestDisplayName: string | null
    orderId?: number | null
    tabId?: number | null
    paymentMethod?: string | null
    paymentMethodLabel?: string | null
    orderDisplayLabel?: string | null
    tabDisplayLabel?: string | null
  }> = [
    {
      id: 901,
      tableId: 7,
      tableNumber: 4,
      reason: 'COALS',
      reasonLabel: 'Заменить угли',
      comment: 'Нужны угли',
      status: 'NEW',
      statusLabel: 'Новый',
      createdAt: '2030-01-10T18:30:00Z',
      guestDisplayName: 'Алексей'
    }
  ]
  if (options.includeBillRequest) {
    calls.push({
      id: 902,
      tableId: 7,
      tableNumber: 4,
      reason: 'BILL',
      reasonLabel: 'Запрос счёта',
      comment: null,
      status: 'NEW',
      statusLabel: 'Новый',
      createdAt: '2030-01-10T18:31:00Z',
      guestDisplayName: 'Мария',
      orderId: 900,
      tabId: 88,
      paymentMethod: 'CARD',
      paymentMethodLabel: 'Картой на месте',
      orderDisplayLabel: 'Заказ №123',
      tabDisplayLabel: 'Личный счёт'
    })
  }

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

  await page.route('**/api/venue/1/staff-chat', async (route) => {
    await route.fulfill(jsonResponse({ venueId: 1, isLinked: true, chatId: -100 }))
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
    const request = route.request()
    const url = new URL(request.url())
    const actionMatch = url.pathname.match(/\/api\/venue\/1\/staff-calls\/(\d+)\/(ack|done)$/)
    if (request.method() === 'GET') {
      await route.fulfill(jsonResponse({ items: calls.filter((call) => call.status === 'NEW' || call.status === 'ACK') }))
      return
    }
    if (!actionMatch || request.method() !== 'POST') {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }

    const staffCallId = Number(actionMatch[1])
    const action = actionMatch[2]
    const call = calls.find((item) => item.id === staffCallId)
    if (!call) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }

    let applied = false
    if (action === 'ack' && call.status === 'NEW') {
      ackCalls += 1
      call.status = 'ACK'
      call.statusLabel = 'В работе'
      applied = true
    } else if (action === 'done' && call.status === 'ACK') {
      doneCalls += 1
      call.status = 'DONE'
      call.statusLabel = 'Выполнен'
      applied = true
    }
    await route.fulfill(jsonResponse({ call, applied }))
  })

  return {
    getAckCalls: () => ackCalls,
    getDoneCalls: () => doneCalls
  }
}

async function mockVenueStaffChatApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    permissions?: string[]
    linked?: boolean
    generatedExpiresAt?: string
  } = {}
) {
  const role = options.role ?? 'OWNER'
  const permissions = options.permissions ?? (role === 'STAFF' ? [] : ['STAFF_CHAT_LINK'])
  let linked = options.linked ?? true
  let generated = 0
  let staffInvites = 0
  let testMessages = 0
  let unlinks = 0
  let activeCodeHint: string | null = null
  let activeCodeExpiresAt: string | null = null
  const generatedCodes = ['ABC123', 'DEF456', 'GHI789']
  type StaffProfileFixture = {
    id: number
    linkedUserId?: number | null
    displayName: string
    roleLabel?: string | null
    subtype: string
    photoRef?: string | null
    bio?: string | null
    tags: string[]
    isGuestVisible: boolean
    publishedAt?: string | null
    disabledAt?: string | null
    createdAt: string
    updatedAt: string
    todayShift?: Record<string, unknown> | null
  }
  let nextProfileId = 700
  const profileCreateRequests: Array<Record<string, unknown>> = []
  const profileUpdateRequests: Array<Record<string, unknown>> = []
  const shiftRequests: Array<Record<string, unknown>> = []
  const staffProfiles: StaffProfileFixture[] = [
    {
      id: 501,
      linkedUserId: 123456789,
      displayName: 'Алексей',
      roleLabel: null,
      subtype: 'hookah_master',
      photoRef: 'internal/photo/ref',
      bio: 'Любит крепкие миксы.',
      tags: ['крепкие миксы'],
      isGuestVisible: false,
      publishedAt: null,
      disabledAt: null,
      createdAt: '2030-01-10T18:00:00Z',
      updatedAt: '2030-01-10T18:00:00Z',
      todayShift: null
    }
  ]

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

  await page.route('**/api/venue/1/staff/profiles**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    const profileMatch = path.match(/^\/api\/venue\/1\/staff\/profiles\/(\d+)(?:\/(publish|hide|today-shift))?$/)

    if (path === '/api/venue/1/staff/profiles' && method === 'GET') {
      await route.fulfill(jsonResponse({ profiles: staffProfiles }))
      return
    }

    if (path === '/api/venue/1/staff/profiles' && method === 'POST') {
      const body = (await request.postDataJSON()) as Record<string, unknown>
      profileCreateRequests.push(body)
      const profile: StaffProfileFixture = {
        id: nextProfileId++,
        linkedUserId: typeof body.linkedUserId === 'number' ? body.linkedUserId : null,
        displayName: String(body.displayName ?? ''),
        roleLabel: typeof body.roleLabel === 'string' ? body.roleLabel : null,
        subtype: String(body.subtype ?? 'other'),
        photoRef: typeof body.photoRef === 'string' ? body.photoRef : null,
        bio: typeof body.bio === 'string' ? body.bio : null,
        tags: Array.isArray(body.tags) ? body.tags.map(String) : [],
        isGuestVisible: body.isGuestVisible === true,
        publishedAt: null,
        disabledAt: null,
        createdAt: '2030-01-10T18:05:00Z',
        updatedAt: '2030-01-10T18:05:00Z',
        todayShift: null
      }
      staffProfiles.push(profile)
      await route.fulfill(jsonResponse(profile))
      return
    }

    if (!profileMatch) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }

    const profileId = Number(profileMatch[1])
    const action = profileMatch[2] ?? null
    const profile = staffProfiles.find((item) => item.id === profileId)
    if (!profile) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }

    if (method === 'PATCH' && !action) {
      const body = (await request.postDataJSON()) as Record<string, unknown>
      profileUpdateRequests.push(body)
      if (typeof body.displayName === 'string') profile.displayName = body.displayName
      if (typeof body.roleLabel === 'string' || body.roleLabel === null) profile.roleLabel = body.roleLabel
      if (typeof body.subtype === 'string') profile.subtype = body.subtype
      if (typeof body.linkedUserId === 'number') profile.linkedUserId = body.linkedUserId
      if (body.unlinkUser === true) profile.linkedUserId = null
      if (typeof body.photoRef === 'string' || body.photoRef === null) profile.photoRef = body.photoRef
      if (typeof body.bio === 'string' || body.bio === null) profile.bio = body.bio
      if (Array.isArray(body.tags)) profile.tags = body.tags.map(String)
      profile.updatedAt = '2030-01-10T18:06:00Z'
      await route.fulfill(jsonResponse(profile))
      return
    }

    if (method === 'POST' && action === 'publish') {
      profile.isGuestVisible = true
      profile.publishedAt = '2030-01-10T18:07:00Z'
      profile.disabledAt = null
      await route.fulfill(jsonResponse(profile))
      return
    }

    if (method === 'POST' && action === 'hide') {
      profile.isGuestVisible = false
      profile.disabledAt = '2030-01-10T18:08:00Z'
      await route.fulfill(jsonResponse(profile))
      return
    }

    if (method === 'POST' && action === 'today-shift') {
      const body = (await request.postDataJSON()) as Record<string, unknown>
      shiftRequests.push(body)
      const shift = {
        id: 900 + shiftRequests.length,
        staffProfileId: profile.id,
        shiftDate: '2030-01-10',
        startsAt: null,
        endsAt: null,
        status: String(body.status ?? 'active'),
        isGuestVisible: body.isGuestVisible !== false,
        manuallyMarkedActive: body.status === 'active',
        createdAt: '2030-01-10T18:09:00Z',
        updatedAt: '2030-01-10T18:09:00Z'
      }
      profile.todayShift = shift
      await route.fulfill(jsonResponse({ shift }))
      return
    }

    await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
  })

  await page.route('**/api/venue/1/staff', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    await route.fulfill(
      jsonResponse({
        members: [
          {
            userId: 123456789,
            role,
            createdAt: '2030-01-10T18:00:00Z',
            invitedByUserId: null
          }
        ]
      })
    )
  })

  await page.route('**/api/venue/1/staff/invites', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    staffInvites += 1
    const body = (await route.request().postDataJSON()) as { role?: string }
    const inviteCode = 'ABC234'
    const startPayload = `staff_invite_${inviteCode}`
    const deepLink = `https://t.me/TestHookahBot?start=${startPayload}`
    const fallbackCommand = `/start ${startPayload}`
    await route.fulfill(
      jsonResponse({
        inviteCode,
        expiresAt: '2030-01-17T18:00:00Z',
        ttlSeconds: 604800,
        role: body.role ?? 'STAFF',
        venueName: 'Микс',
        startPayload,
        deepLink,
        fallbackCommand,
        copyText: deepLink,
        instructions: `Передайте сотруднику приглашение.\nЗаведение: Микс\nРоль: ${
          body.role ?? 'STAFF'
        }\nСсылка: ${deepLink}\nЗапасная команда: ${fallbackCommand}`
      })
    )
  })

  await page.route('**/api/venue/1/staff-chat**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    if (request.method() === 'GET' && path === '/api/venue/1/staff-chat') {
      await route.fulfill(
        jsonResponse({
          venueId: 1,
          isLinked: linked,
          chatId: null,
          maskedChatId: linked ? '-100...7890' : null,
          activeCodeHint,
          activeCodeExpiresAt,
          testCommand: '/link_test@TestHookahBot'
        })
      )
      return
    }
    if (request.method() === 'POST' && path === '/api/venue/1/staff-chat/link-code') {
      generated += 1
      const code = generatedCodes[generated - 1] ?? `CODE${generated}`
      activeCodeHint = code.slice(0, 3)
      activeCodeExpiresAt = options.generatedExpiresAt ?? '2030-01-10T19:00:00Z'
      await route.fulfill(
        jsonResponse({
          code,
          expiresAt: activeCodeExpiresAt,
          ttlSeconds: 600,
          linkCommand: `/link@TestHookahBot ${code}`,
          testCommand: '/link_test@TestHookahBot'
        })
      )
      return
    }
    if (request.method() === 'POST' && path === '/api/venue/1/staff-chat/test') {
      testMessages += 1
      await route.fulfill(
        jsonResponse(
          linked
            ? { result: 'QUEUED', queued: true, message: 'Тестовое сообщение поставлено в отправку.' }
            : { result: 'NO_STAFF_CHAT', queued: false, message: 'Чат не подключён.' }
        )
      )
      return
    }
    if (request.method() === 'POST' && path === '/api/venue/1/staff-chat/unlink') {
      unlinks += 1
      linked = false
      activeCodeHint = null
      activeCodeExpiresAt = null
      await route.fulfill(jsonResponse({ ok: true }))
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
  })

  return {
    getGeneratedCalls: () => generated,
    getStaffInvites: () => staffInvites,
    getProfileCreateRequests: () => profileCreateRequests,
    getProfileUpdateRequests: () => profileUpdateRequests,
    getShiftRequests: () => shiftRequests,
    getTestMessages: () => testMessages,
    getUnlinks: () => unlinks,
    setLinked: (next: boolean) => {
      linked = next
    }
  }
}

function buildVenueBooking(overrides: Partial<VenueBookingFixture> = {}): VenueBookingFixture {
  return {
    bookingId: 701,
    displayNumber: 12,
    status: 'pending',
    scheduledAt: '2030-01-10T18:30:00Z',
    scheduledAtDisplay: '10.01.2030, 21:30',
    scheduledLocalDate: '2030-01-10',
    scheduledLocalTime: '21:30',
    serviceDate: '2030-01-10',
    arrivalDeadlineAt: '2030-01-10T19:00:00Z',
    arrivalDeadlineAtDisplay: '10.01.2030, 22:00',
    partySize: 4,
    comment: 'у окна',
    guestDisplayName: 'Алексей',
    lastGuestConfirmationAt: null,
    ...overrides
  }
}

async function mockVenueBookingsApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    permissions?: string[]
    bookings?: VenueBookingFixture[]
  } = {}
) {
  const role = options.role ?? 'MANAGER'
  const permissions = options.permissions ?? [
    'BOOKING_VIEW',
    'BOOKING_MANAGE',
    'BOOKING_ARRIVAL_UPDATE',
    'SUPPORT_VIEW',
    'SUPPORT_MANAGE',
  ]
  let bookings = options.bookings ?? [buildVenueBooking()]
  let confirmCalls = 0
  let cancelCalls = 0
  let changeCalls = 0
  let seatCalls = 0
  let noShowCalls = 0
  let messageCalls = 0
  const changeRequests: unknown[] = []
  const cancelReasons: Array<string | null> = []
  const bookingMessages: string[] = []
  let nextThreadId = 4000
  let nextMessageId = 5000
  let supportThreads: SupportThreadFixture[] = []
  let supportMessages: SupportMessageFixture[] = []

  const activeBookings = () => bookings.filter((booking) => ['pending', 'confirmed', 'changed'].includes(booking.status))
  const findBooking = (bookingId: number) => bookings.find((booking) => booking.bookingId === bookingId) ?? null
  const findOrCreateBookingThread = (booking: VenueBookingFixture) => {
    let thread = supportThreads.find((item) => item.bookingId === booking.bookingId)
    if (!thread) {
      thread = {
        threadId: nextThreadId++,
        venueId: 1,
        venueName: 'Микс',
        guestDisplayName: booking.guestDisplayName ?? 'Алексей',
        threadType: 'BOOKING_THREAD',
        assigneeScope: 'VENUE',
        category: 'BOOKING',
        contextLabel: booking.displayNumber ? `Бронь №${booking.displayNumber}` : `Бронь #${booking.bookingId}`,
        status: 'OPEN',
        statusLabel: 'В работе',
        bookingId: booking.bookingId,
        title: booking.displayNumber ? `Бронь №${booking.displayNumber}` : `Бронь #${booking.bookingId}`,
        lastMessagePreview: null,
        lastMessageAt: null,
        unreadCount: 0,
        createdAt: '2030-01-10T18:00:00Z',
        updatedAt: '2030-01-10T18:00:00Z',
        booking: {
          bookingId: booking.bookingId,
          displayNumber: booking.displayNumber,
          scheduledAt: booking.scheduledAt,
          partySize: booking.partySize,
          status: booking.status
        }
      }
      supportThreads = [...supportThreads, thread]
    }
    return thread
  }
  const addSupportMessage = (
    thread: SupportThreadFixture,
    authorRole: 'GUEST' | 'VENUE',
    source: string,
    text: string
  ): SupportMessageFixture => {
    const message = {
      messageId: nextMessageId++,
      threadId: thread.threadId,
      authorRole,
      source,
      text,
      createdAt: `2030-01-10T18:${String(nextMessageId % 60).padStart(2, '0')}:00Z`
    }
    supportMessages = [...supportMessages, message]
    thread.lastMessagePreview = text
    thread.lastMessageAt = message.createdAt
    thread.updatedAt = message.createdAt
    thread.status = 'OPEN'
    thread.unreadCount = authorRole === 'GUEST' ? 1 : 0
    return message
  }

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

  await page.route('**/api/venue/1/support/threads**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const threadMatch = url.pathname.match(/\/api\/venue\/1\/support\/threads\/(\d+)(?:\/(messages|resolve|reopen))?$/)
    if (!threadMatch && request.method() === 'GET') {
      const bookingIdParam = url.searchParams.get('bookingId')
      const filter = url.searchParams.get('filter')
      const bookingId = bookingIdParam == null ? null : Number(bookingIdParam)
      let items = bookingId != null && Number.isFinite(bookingId)
        ? supportThreads.filter((thread) => thread.bookingId === bookingId)
        : supportThreads
      if (filter === 'active') {
        items = items.filter((thread) => thread.status === 'OPEN')
      } else if (filter === 'resolved') {
        items = items.filter((thread) => thread.status === 'RESOLVED' || thread.status === 'CLOSED')
      }
      await route.fulfill(jsonResponse({ items }))
      return
    }
    if (!threadMatch) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    const threadId = Number(threadMatch[1])
    const thread = supportThreads.find((item) => item.threadId === threadId)
    if (!thread) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    const threadAction = threadMatch[2]
    if (threadAction === 'resolve' && request.method() === 'POST') {
      thread.status = 'RESOLVED'
      thread.updatedAt = '2030-01-10T18:50:00Z'
      thread.unreadCount = 0
      await route.fulfill(
        jsonResponse({
          thread,
          messages: supportMessages.filter((message) => message.threadId === thread.threadId)
        })
      )
      return
    }
    if (threadAction === 'reopen' && request.method() === 'POST') {
      thread.status = 'OPEN'
      thread.updatedAt = '2030-01-10T18:51:00Z'
      thread.unreadCount = 0
      await route.fulfill(
        jsonResponse({
          thread,
          messages: supportMessages.filter((message) => message.threadId === thread.threadId)
        })
      )
      return
    }
    if (threadAction === 'messages' && request.method() === 'POST') {
      const body = (await request.postDataJSON()) as { message?: string | null }
      const message = addSupportMessage(thread, 'VENUE', 'VENUE_MINIAPP', body.message ?? '')
      await route.fulfill(jsonResponse({ thread, message, queued: true }))
      return
    }
    if (request.method() === 'GET') {
      thread.unreadCount = 0
      await route.fulfill(
        jsonResponse({
          thread,
          messages: supportMessages.filter((message) => message.threadId === thread.threadId)
        })
      )
      return
    }
    await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
  })

  await page.route('**/api/venue/bookings**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname === '/api/venue/bookings' && request.method() === 'GET') {
      await route.fulfill(jsonResponse({ items: activeBookings() }))
      return
    }

    const actionMatch = url.pathname.match(/\/api\/venue\/bookings\/(\d+)\/([^/]+)$/)
    if (!actionMatch || request.method() !== 'POST') {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }

    const bookingId = Number(actionMatch[1])
    const action = actionMatch[2]
    const booking = findBooking(bookingId)
    if (!booking) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }

    if (action === 'confirm') {
      confirmCalls += 1
      booking.status = 'confirmed'
    } else if (action === 'cancel') {
      cancelCalls += 1
      const body = (await request.postDataJSON()) as { reasonText?: string | null }
      cancelReasons.push(body.reasonText ?? null)
      booking.status = 'canceled'
    } else if (action === 'change') {
      changeCalls += 1
      const body = (await request.postDataJSON()) as {
        scheduledLocalDate?: string | null
        scheduledLocalTime?: string | null
      }
      changeRequests.push(body)
      booking.status = 'changed'
      booking.scheduledLocalDate = body.scheduledLocalDate ?? booking.scheduledLocalDate
      booking.scheduledLocalTime = body.scheduledLocalTime ?? booking.scheduledLocalTime
      booking.scheduledAtDisplay = `${body.scheduledLocalDate ?? booking.scheduledLocalDate}, ${body.scheduledLocalTime ?? booking.scheduledLocalTime}`
    } else if (action === 'seat') {
      seatCalls += 1
      booking.status = 'seated'
    } else if (action === 'no-show') {
      noShowCalls += 1
      booking.status = 'no_show'
    } else if (action === 'message') {
      messageCalls += 1
      const body = (await request.postDataJSON()) as { message?: string | null }
      bookingMessages.push(body.message ?? '')
      const thread = findOrCreateBookingThread(booking)
      const message = addSupportMessage(thread, 'VENUE', 'VENUE_MINIAPP', body.message ?? '')
      await route.fulfill(jsonResponse({ bookingId: booking.bookingId, queued: true, thread, message }))
      return
    }

    await route.fulfill(jsonResponse({ bookingId: booking.bookingId, status: booking.status, scheduledAt: booking.scheduledAt }))
  })

  return {
    getConfirmCalls: () => confirmCalls,
    getCancelCalls: () => cancelCalls,
    getChangeCalls: () => changeCalls,
    getSeatCalls: () => seatCalls,
    getNoShowCalls: () => noShowCalls,
    getMessageCalls: () => messageCalls,
    getSupportMessages: () => supportMessages,
    getChangeRequests: () => changeRequests,
    getCancelReasons: () => cancelReasons,
    getBookingMessages: () => bookingMessages,
    setBookings: (nextBookings: VenueBookingFixture[]) => {
      bookings = nextBookings
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
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page)

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByRole('heading', { name: 'Hookah Mini App' })).toBeVisible()
  await expect(page.getByText('График не указан')).toBeVisible()
  await page.getByRole('button', { name: 'Открыть карточку' }).click()

  await expect(page.getByRole('heading', { name: 'Микс' })).toBeVisible()
  await expect(page.getByText('График не указан')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'ℹ️ Информация' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '📖 Фото-меню' })).toBeVisible()
  await expect(page.getByText('Заказное меню и корзина доступны после сканирования QR-кода на столе.')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Построить маршрут' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Скопировать адрес' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Забронировать' })).toBeVisible()
  await expect(page.getByAltText('📖 Фото-меню 1')).toBeVisible()
  await expect(page.getByText('Кальянное меню')).toHaveCount(0)
  expect(api.getStructuredMenuCalls()).toBe(0)
})

test('guest venue card shows today staff without private linkage fields', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, {
    todayStaff: [
      {
        id: 501,
        displayName: 'Максим',
        roleLabel: 'Мастер миксов',
        subtype: 'other',
        photoRef: null,
        bio: 'Люблю крепкие миксы и помогаю подобрать вкус под настроение.',
        tags: ['крепкие миксы', 'авторские вкусы'],
        shiftId: 901,
        shiftDate: '2030-01-10',
        startsAt: null,
        endsAt: null,
        shiftStatus: 'active',
        manuallyMarkedActive: true,
        linkedUserId: 123456789,
        telegramUserId: 123456789
      }
    ]
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Открыть карточку' }).click()

  const todayStaff = page.locator('.guest-today-staff')
  await expect(todayStaff).toContainText('Сегодня работают')
  await expect(todayStaff).toContainText('Максим')
  await expect(todayStaff).toContainText('Мастер миксов')
  await expect(todayStaff).not.toContainText('Другое')
  await expect(todayStaff).toContainText('крепкие миксы')
  await expect(todayStaff).not.toContainText('Алексей')
  await expect(todayStaff).not.toContainText('linkedUserId')
  await expect(todayStaff).not.toContainText('telegramUserId')
  await expect(todayStaff).not.toContainText('123456789')
  await expect(page.locator('.venue-info-section').first()).toBeVisible()
  const todayStaffAfterInfo = await page.evaluate(() => {
    const info = document.querySelector('.venue-info-section')
    const today = document.querySelector('.guest-today-staff')
    return Boolean(info && today && (info.compareDocumentPosition(today) & Node.DOCUMENT_POSITION_FOLLOWING))
  })
  expect(todayStaffAfterInfo).toBe(true)
})

test('guest booking closed date shows human message and keeps selected date', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    bookingCreateError: {
      code: 'VENUE_CLOSED_ON_SELECTED_DATE',
      message: 'На выбранную дату заведение не работает: ремонт. Выберите другую дату.'
    }
  })

  await page.goto('?mode=guest#/bookings?venueId=1')
  const screen = page.locator('.guest-bookings-screen')
  await expect(page.getByRole('heading', { name: 'Бронирование' })).toBeVisible()
  await screen.locator('input[type="date"]').fill('2030-01-10')
  await screen.locator('input[type="time"]').fill('19:00')
  await screen.getByRole('button', { name: 'Отправить заявку' }).click()

  await expect(screen.locator('p.status')).toHaveText(
    'На выбранную дату заведение не работает: ремонт. Выберите другую дату.'
  )
  await expect(screen.locator('p.status')).not.toContainText('schedule')
  await expect(screen.locator('input[type="date"]')).toHaveValue('2030-01-10')

  api.setBookingCreateError(null)
  await screen.getByRole('button', { name: 'Отправить заявку' }).click()
  await expect(page.getByRole('heading', { name: 'Заявка на бронь отправлена' })).toBeVisible()
})

test('guest opens my bookings from profile and manages booking actions', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    bookings: [
      buildGuestBooking({
        bookingId: 501,
        venueId: 1,
        venueName: 'Микс',
        displayNumber: 1,
        displayLabel: 'Бронь №1',
        status: 'confirmed',
        statusLabel: 'Подтверждена',
        scheduledAt: '2030-01-10T18:00:00Z',
        scheduledAtDisplay: '10.01.2030, 21:00',
        scheduledLocalDate: '2030-01-10',
        scheduledLocalTime: '21:00',
        arrivalDeadlineTimeDisplay: '21:15',
        partySize: 3,
        comment: 'у окна',
        lastGuestConfirmationAt: '10.01.2030, 21:05'
      }),
      buildGuestBooking({
        bookingId: 502,
        venueId: 2,
        venueName: 'Дым',
        displayNumber: 1,
        displayLabel: 'Бронь №1',
        status: 'pending',
        statusLabel: 'Ожидает подтверждения',
        scheduledAt: '2030-01-09T17:00:00Z',
        scheduledAtDisplay: '09.01.2030, 22:00',
        scheduledLocalDate: '2030-01-09',
        scheduledLocalTime: '22:00',
        arrivalDeadlineTimeDisplay: '22:30',
        partySize: 2,
        comment: null
      }),
      buildGuestBooking({
        bookingId: 503,
        venueId: 3,
        venueName: 'Облако',
        displayNumber: 2,
        displayLabel: 'Бронь №2',
        status: 'confirmed',
        statusLabel: 'Подтверждена',
        scheduledAt: '2030-01-12T17:00:00Z',
        scheduledAtDisplay: '12.01.2030, 20:00',
        scheduledLocalDate: '2030-01-12',
        scheduledLocalTime: '20:00',
        arrivalDeadlineTimeDisplay: '20:30',
        partySize: 2,
        comment: null,
        lastGuestConfirmationAt: null
      })
    ]
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Профиль' }).click()
  await expect(page.getByRole('heading', { name: 'Профиль' })).toBeVisible()
  await page.getByRole('button', { name: '📅 Мои брони' }).click()

  await expect(page.getByRole('heading', { name: 'Мои брони' })).toBeVisible()
  const rows = page.locator('.venue-order-row')
  await expect(rows.nth(0)).toContainText('Дым')
  await expect(rows.nth(1)).toContainText('Микс')
  const mixCard = rows.filter({ hasText: 'Микс' })
  await expect(mixCard).toContainText('Бронь №1')
  await expect(mixCard).toContainText('10.01.2030, 21:00')
  await expect(mixCard).toContainText('3 гостей')
  await expect(mixCard).toContainText('Бронь подтверждена заведением')
  await expect(mixCard).toContainText('Комментарий: у окна')
  await expect(mixCard).toContainText('Держим стол до 21:15.')
  await expect(mixCard).toContainText('Ваш ответ: придёте')
  await expect(mixCard.getByRole('button', { name: '✅ Я приду' })).toHaveCount(0)

  const cloudCard = rows.filter({ hasText: 'Облако' })
  await expect(cloudCard).toContainText('Бронь подтверждена заведением')
  await cloudCard.getByRole('button', { name: '✅ Я приду' }).click()
  await expect(cloudCard).toContainText('Ваш ответ: придёте')
  await expect(cloudCard.getByRole('button', { name: '✅ Я приду' })).toHaveCount(0)

  await mixCard.getByRole('button', { name: 'Перенести' }).click()
  await mixCard.locator('input[type="date"]').fill('2030-01-11')
  await mixCard.locator('input[type="time"]').fill('20:30')
  await mixCard.locator('input[type="number"]').fill('4')
  await mixCard.locator('textarea').fill('другой стол')
  await mixCard.getByRole('button', { name: 'Сохранить перенос' }).click()

  expect(api.getBookingUpdateRequests()).toHaveLength(1)
  expect(api.getBookingUpdateRequests()[0]).toMatchObject({ venueId: 1, bookingId: 501, partySize: 4, comment: 'другой стол' })
  await expect(rows.filter({ hasText: 'Микс' })).toContainText('11.01.2030, 20:30')

  page.once('dialog', (dialog) => void dialog.accept())
  await rows.filter({ hasText: 'Микс' }).getByRole('button', { name: 'Отменить бронь' }).click()
  expect(api.getBookingCancelRequests()).toEqual([{ venueId: 1, bookingId: 501 }])
  await expect(rows.filter({ hasText: 'Микс' })).toHaveCount(0)
})

test('guest history empty state is shown from profile', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, {
    visitHistory: {
      items: [],
      details: {}
    }
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Профиль' }).click()
  await page.getByRole('button', { name: '🕘 История' }).click()

  await expect(page.getByRole('heading', { name: 'История' })).toBeVisible()
  await expect(page.getByText('История пока пустая.')).toBeVisible()
})

test('guest history shows completed visits and safe closed order detail', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, {
    visitHistory: {
      items: [
        {
          visitId: 10,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-10T18:00:00Z',
          serviceDate: '2030-01-10',
          source: 'booking_seated',
          totalMinor: null,
          currency: null,
          hasBooking: true,
          orderLabels: []
        },
        {
          visitId: 11,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-11T18:30:00Z',
          serviceDate: '2030-01-11',
          source: 'order_closed',
          totalMinor: 125000,
          currency: 'RUB',
          hasBooking: false,
          orderLabels: ['№42']
        }
      ],
      details: {
        10: {
          visitId: 10,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-10T18:00:00Z',
          serviceDate: '2030-01-10',
          source: 'booking_seated',
          booking: {
            bookingId: 501,
            displayNumber: 1,
            partySize: 2,
            status: 'seated'
          },
          orders: [],
          totalMinor: null,
          currency: null
        },
        11: {
          visitId: 11,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-11T18:30:00Z',
          serviceDate: '2030-01-11',
          source: 'order_closed',
          booking: null,
          orders: [
            {
              orderId: 900,
              displayNumber: 42,
              displayDate: '2030-01-11',
              totalMinor: 125000,
              currency: 'RUB',
              promotionDiscounts: [],
              items: [
                {
                  itemId: 200,
                  itemName: 'Double Apple',
                  qty: 1,
                  selectedOption: {
                    name: 'Ягодный микс',
                    priceDeltaMinor: 25000
                  },
                  preferenceNote: 'покрепче',
                  priceMinor: 125000,
                  currency: 'RUB',
                  totalMinor: 125000
                }
              ]
            }
          ],
          totalMinor: 125000,
          currency: 'RUB'
        }
      }
    }
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Профиль' }).click()
  await page.getByRole('button', { name: '🕘 История' }).click()

  const bookingOnlyVisit = page.locator('article.card').filter({ hasText: 'Было бронирование' })
  const closedOrderVisit = page.locator('article.card').filter({ hasText: 'Заказы: №42' })
  await expect(bookingOnlyVisit).toContainText('Микс')
  await expect(closedOrderVisit).toContainText(/Итого: 1[\s\u00a0]250/)

  await closedOrderVisit.getByRole('button', { name: 'Подробнее' }).click()

  await expect(page.getByRole('heading', { name: 'Заказ №42' })).toBeVisible()
  await expect(page.getByText('Double Apple · Ягодный микс · Пожелание: покрепче ×1')).toBeVisible()
  await expect(page.getByText('Foreign Hookah')).toHaveCount(0)
})

test('table context with active order opens category-first order menu and hides pre-visit actions', async ({ page }) => {
  await mockGuestApi(page)

  await page.goto(`?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await page.getByRole('button', { name: 'Меню', exact: true }).click()

  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await expect(page.getByText('Вы за столом №4').last()).toBeVisible()
  await expect(page.getByRole('link', { name: 'Построить маршрут' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Скопировать адрес' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Забронировать' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Продление работы заведения/ })).toHaveCount(0)
  await expect(page.getByText('Double Apple')).toHaveCount(0)
  await page.getByRole('button', { name: /Кальянное меню/ }).click()

  await expect(page.getByRole('heading', { name: 'Кальянное меню' })).toBeVisible()
  await expect(page.getByText('Double Apple')).toBeVisible()
  await page.getByRole('button', { name: 'Добавить' }).click()

  await expect(page.getByRole('button', { name: 'Корзина (1)' })).toBeVisible()
})

test('table context without active order hides pre-visit actions and extension entry', async ({ page }) => {
  await mockGuestApi(page, {
    extensionOptionsError: {
      status: 404,
      code: 'NOT_FOUND',
      message: 'Active order not found'
    }
  })

  await page.goto(`?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await expect(page.getByText('Вы за столом №4').last()).toBeVisible()
  await expect(page.getByRole('link', { name: 'Построить маршрут' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Скопировать адрес' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Забронировать' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Продление работы заведения/ })).toHaveCount(0)
  await expect(page.getByText('Активного счёта нет. Продление недоступно.')).toHaveCount(0)
})

test('table context leave session clears current guest restore state', async ({ page }) => {
  const api = await mockGuestApi(page, { restoreContext: buildRestoreContext(), activeOrder: null })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await expect(page.getByRole('button', { name: '🚪 Завершить визит' })).toBeVisible()
  await page.getByRole('button', { name: '🚪 Завершить визит' }).click()

  expect(api.getTableSessionEndRequests()).toHaveLength(1)
  const endRequest = api.getTableSessionEndRequests()[0]
  expect(new URL(endRequest.url).pathname).toBe('/api/guest/table/session/end')
  expect(endRequest.method).toBe('POST')
  expect(endRequest.contentType).toContain('application/json')
  expect(endRequest.body).toEqual({ tableToken, tableSessionId: 77 })
  await expect(page.getByText('Чтобы заказать к столику или вызвать персонал, отсканируйте QR-код на столе.')).toBeVisible()
  await expect(page.getByText('Вы за столом №4 · Микс')).toHaveCount(0)

  await page.reload()
  await expect(page.getByText('Чтобы заказать к столику или вызвать персонал, отсканируйте QR-код на столе.')).toBeVisible()
  await expect(page.getByText('Вы за столом №4 · Микс')).toHaveCount(0)
})

test('table context with active order hides leave session action', async ({ page }) => {
  const api = await mockGuestApi(page, {
    restoreContext: buildRestoreContext(),
    tableSessionEndResponse: {
      ended: false,
      tableSessionId: 77,
      blockedReason: 'ACTIVE_ORDER',
      message: 'Сначала закройте счёт. После этого визит можно завершить.'
    }
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await expect(page.getByRole('button', { name: '🚪 Завершить визит' })).toHaveCount(0)
  expect(api.getTableSessionEndRequests()).toHaveLength(0)
  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Мой заказ' })).toBeVisible()
})

test('guest fallback chat order sends supported quick order payload through Telegram sendData', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page)

  await page.goto(`?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()

  await expect(page.getByRole('button', { name: 'Корзина (1)' })).toBeVisible()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()
  await expect(page.getByText('Выберите счёт (tab) для заказа.')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Оформить в чате' })).toBeEnabled()
  await page.getByRole('button', { name: 'Оформить в чате' }).click()

  await expect
    .poll(async () =>
      page.evaluate(() => ((window as TestTelegramWindow).__e2eTelegramSendDataPayloads ?? []).length)
    )
    .toBe(1)
  const payloads = await page.evaluate(() => (window as TestTelegramWindow).__e2eTelegramSendDataPayloads ?? [])
  expect(JSON.parse(payloads[0])).toEqual({
    cmd: 'start_quick_order',
    table_token: tableToken
  })
  await expect(page.getByText('Откройте чат с ботом вручную.')).toHaveCount(0)
  await expect(page.getByText('Откройте чат с ботом и отправьте заказ там.')).toHaveCount(0)
})

test('guest creates staff call from active table and sees lifecycle status', async ({ page }) => {
  const api = await mockGuestApi(page)

  await page.goto(`?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: '🛎 Вызвать персонал' })).toBeVisible()
  await expect(page.locator('.staff-call-overlay')).toBeHidden()
  await expect(page.locator('.staff-call-status')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()

  await page.getByRole('button', { name: '🛎 Вызвать персонал' }).first().click()
  await expect(page.getByText('Причина')).toBeVisible()
  await expect(page.locator('select.staff-select option').filter({ hasText: 'Счёт' })).toHaveCount(0)
  const staffReasonValues = await page.locator('select.staff-select').evaluate((node) =>
    Array.from((node as HTMLSelectElement).options).map((option) => option.value)
  )
  expect(staffReasonValues).not.toContain('BILL')
  await page.locator('select.staff-select').selectOption('COALS')
  await page.locator('textarea.staff-comment').fill('Нужны угли')
  await page.getByRole('button', { name: 'Вызвать персонал к столу №4' }).click()

  await expect(page.locator('.staff-call-overlay')).toBeHidden()
  await expect(page.getByRole('button', { name: 'Вызвать персонал к столу №4' })).toHaveCount(0)
  await expect(page.locator('.staff-call-status[data-tone="pending"]')).toContainText('Вызов отправлен')
  await expect(page.locator('.staff-call-status')).toContainText('Заменить угли')
  await expect(page.locator('.staff-call-status')).toContainText('Ожидаем подтверждения персонала.')
  await expect(page.locator('.staff-call-status').getByRole('button', { name: 'Обновить' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Вызов активен' })).toBeVisible()
  expect(api.getStaffCallRequests()).toEqual([
    {
      tableToken,
      tableSessionId: 77,
      reason: 'COALS',
      comment: 'Нужны угли'
    }
  ])

  await page.getByRole('button', { name: 'Вызов активен' }).click()
  await expect(page.locator('.staff-call-status')).toContainText('Вызов отправлен')
  await expect(page.getByText('Причина')).toBeHidden()

  api.setStaffCallStatuses([
    {
      staffCallId: 901,
      status: 'ACK',
      statusLabel: 'Персонал принял вызов',
      createdAtEpochSeconds: 1894302000,
      reason: 'COALS',
      reasonLabel: 'Заменить угли',
      comment: 'Нужны угли'
    }
  ])
  await page.goto(`?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.locator('.staff-call-status[data-tone="success"]')).toContainText('Персонал принял вызов')
  await expect(page.locator('.staff-call-status')).toContainText('Сотрудник скоро подойдёт к столу №4.')
  await expect(page.getByText('Персонал принял вызов')).not.toHaveCSS('color', 'rgb(220, 38, 38)')

  api.setStaffCallStatuses([
    {
      staffCallId: 901,
      status: 'DONE',
      statusLabel: 'Вызов закрыт',
      createdAtEpochSeconds: 1894302000,
      reason: 'COALS',
      reasonLabel: 'Заменить угли',
      comment: 'Нужны угли'
    }
  ])
  await page.goto(`?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.locator('.staff-call-status[data-tone="done"]')).toContainText('Вызов выполнен')
  await expect(page.getByRole('button', { name: '🛎 Вызвать персонал' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Вызов активен' })).toHaveCount(0)
})

test('guest mini app uses context-aware placeholder and submits structured selected option', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    restoreContext: buildRestoreContext(),
    menuCategories: [
      {
        id: 20,
        name: 'Кальянное меню',
        categoryType: 'OTHER',
        items: [
          {
            id: 210,
            name: 'Кальян',
            priceMinor: 180000,
            currency: 'RUB',
            isAvailable: true,
            effectiveItemType: 'OTHER',
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
          },
          {
            id: 212,
            name: 'Чай',
            priceMinor: 30000,
            currency: 'RUB',
            isAvailable: true,
            effectiveItemType: 'DRINK',
            options: [{ id: 305, name: 'Горячий', priceDeltaMinor: 0, isAvailable: true }]
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
  const hookahItem = page.locator('.menu-item').filter({ hasText: 'Кальян' })
  const waterItem = page.locator('.menu-item').filter({ hasText: 'Вода' })
  await expect(waterItem.getByText('Выберите вкус')).toHaveCount(0)
  await expect(waterItem.getByText('Выберите опцию')).toHaveCount(0)
  const teaItem = page.locator('.menu-item').filter({ hasText: 'Чай' })
  await expect(teaItem.getByText('Выберите опцию')).toBeVisible()
  await teaItem.getByRole('button', { name: 'Выбрать' }).click()

  await expect(page.getByRole('heading', { name: 'Выберите опцию' })).toBeVisible()
  await page.getByRole('button', { name: /Горячий/ }).click()
  const drinkNoteInput = page.getByLabel('Пожелания к приготовлению')
  await expect(drinkNoteInput).toHaveAttribute('placeholder', 'Например: без сахара, без льда, потеплее')
  await page.getByRole('button', { name: /К выбору опции/ }).click()
  await page.getByRole('button', { name: '← Назад' }).click()

  await hookahItem.getByRole('button', { name: 'Выбрать' }).click()

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
  const hookahNoteInput = page.getByLabel('Пожелания к приготовлению')
  await expect(hookahNoteInput).toHaveAttribute('placeholder', 'Например: покрепче, полегче, больше мяты, без ментола')
  await expect(hookahNoteInput).not.toHaveAttribute('placeholder', /без сахара|без льда/)
  await expect(page.getByPlaceholder('Например: без сахара, без льда, потеплее')).toHaveCount(0)
  await page.getByRole('button', { name: /К выбору вкуса/ }).click()
  await expect(page.getByRole('heading', { name: 'Выберите вкус' })).toBeVisible()
  await expect(page.getByLabel('Пожелания к приготовлению')).toHaveCount(0)

  await page.getByRole('button', { name: /Яблоко/ }).click()
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await hookahItem.getByRole('button', { name: 'Выбрать' }).click()
  await page.getByRole('button', { name: /Яблоко/ }).click()
  await page.getByLabel('Пожелания к приготовлению').fill('поменьше холодка')
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await hookahItem.getByRole('button', { name: 'Выбрать' }).click()
  await page.getByRole('button', { name: /Яблоко/ }).click()
  await page.getByLabel('Пожелания к приготовлению').fill(' поменьше холодка ')
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await hookahItem.getByRole('button', { name: 'Выбрать' }).click()
  await page.getByRole('button', { name: /Яблоко/ }).click()
  await page.getByLabel('Пожелания к приготовлению').fill('без мяты')
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await hookahItem.getByRole('button', { name: 'Выбрать' }).click()
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
  await expect(page.getByText('Счёт: Личный счёт')).toBeVisible()
  await expect(page.getByText('Вкус: Яблоко')).toHaveCount(3)
  await expect(page.getByText('Вкус: Мята')).toBeVisible()
  await expect(page.getByText('Пожелание: поменьше холодка')).toBeVisible()
  await expect(page.getByText('Пожелание: без мяты')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Заказ №900' })).toHaveCount(0)
  await expect(page.getByText('Заявка №333')).toHaveCount(0)
  await expect(page.getByText('Общий счёт #88')).toHaveCount(0)
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
  await expect(page.getByRole('link', { name: 'Построить маршрут' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Скопировать адрес' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Забронировать' })).toHaveCount(0)
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
  await expect(page.getByText('Заказ №42')).toBeVisible()
  await page.getByRole('button', { name: 'Открыть' }).click()
  await expect(page.getByRole('heading', { name: 'Заказ №42' })).toBeVisible()
  await expect(page.getByText('Личный счёт гостя', { exact: true })).toBeVisible()
  await expect(page.getByText('Скидка заведения 10%')).toBeVisible()
  await expect(page.getByText('Исключено из счёта')).toBeVisible()
  await expect(page.getByText(/Личный счёт гостя · Основной заказ: Чай ×1/)).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Заказ №900' })).toHaveCount(0)
  await expect(page.getByText('Общий счёт #88')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Запрос на продление работы заведения' })).toBeVisible()
  await expect(page.getByText(/На 1 час/)).toBeVisible()
  await expect(page.getByText('Гость ожидает подтверждения')).toBeVisible()
  await expect(page.getByRole('button', { name: '✅ Подтвердить продление' })).toBeVisible()
  await expect(page.getByRole('button', { name: '❌ Отказать' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Настройки', exact: true })).toHaveCount(0)

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

test('venue staff accepts and closes staff calls queue', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffCallsApi(page)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Вызовы', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Вызовы', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Вызовы персонала' })).toBeVisible()

  const callCard = page.locator('.venue-call-card').filter({ hasText: 'Стол №4' })
  await expect(callCard).toBeVisible()
  await expect(callCard).toContainText('Причина: Заменить угли')
  await expect(callCard).toContainText('Комментарий: Нужны угли')
  await expect(callCard).toContainText('Гость: Алексей')

  await callCard.getByRole('button', { name: 'Принять' }).click()
  await expect(page.locator('.venue-call-card').filter({ hasText: 'В работе' })).toBeVisible()
  expect(api.getAckCalls()).toBe(1)

  await page.locator('.venue-call-card').filter({ hasText: 'Стол №4' }).getByRole('button', { name: 'Закрыть' }).click()
  await expect(page.getByText('Активных вызовов пока нет.')).toBeVisible()
  expect(api.getDoneCalls()).toBe(1)
})

test('venue staff sees bill request context in calls queue', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffCallsApi(page, { includeBillRequest: true })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Вызовы', exact: true }).click()
  const billCard = page.locator('.venue-call-card').filter({ hasText: 'Причина: Запрос счёта' })
  await expect(billCard).toBeVisible()
  await expect(billCard).toContainText('Гость: Мария')
  await expect(billCard).toContainText('Заказ: Заказ №123')
  await expect(billCard).toContainText('Счёт: Личный счёт')
  await expect(billCard).toContainText('Оплата: Картой на месте')

  await billCard.getByRole('button', { name: 'Принять' }).click()
  await expect(billCard).toContainText('В работе')
  await billCard.getByRole('button', { name: 'Закрыть' }).click()

  expect(api.getAckCalls()).toBe(1)
  expect(api.getDoneCalls()).toBe(1)
})

test('venue owner links tests and unlinks staff chat from mini app', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: async (text: string) => {
          ;(window as Window & { __copiedText?: string }).__copiedText = text
        }
      }
    })
  })
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, { role: 'OWNER', linked: false })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Чат персонала', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Чат персонала', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Чат персонала' })).toBeVisible()
  await expect(page.getByText('Чат персонала не подключён')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Скопировать команду' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Проверить подключение' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Сгенерировать код привязки' }).click()

  await expect(page.getByRole('heading', { name: 'Код привязки готов' })).toBeVisible()
  await expect(page.getByText('ABC123')).toBeVisible()
  await expect(page.getByLabel('Команда для привязки чата')).toHaveValue('/link@TestHookahBot ABC123')
  await expect(page.getByRole('button', { name: 'Сгенерировать код привязки' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Скопировать команду' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Проверить подключение' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Создать новый код' })).toBeVisible()
  expect(api.getGeneratedCalls()).toBe(1)

  await page.getByRole('button', { name: 'Скопировать команду' }).click()
  await expect(page.getByRole('status')).toHaveText('Команда скопирована')
  await expect.poll(() => page.evaluate(() => (window as Window & { __copiedText?: string }).__copiedText)).toBe(
    '/link@TestHookahBot ABC123'
  )

  await page.getByRole('button', { name: 'Создать новый код' }).click()
  await expect(page.getByRole('heading', { name: 'Создать новый код?' })).toBeVisible()
  await page.getByRole('button', { name: 'Отмена' }).click()
  await expect(page.getByLabel('Команда для привязки чата')).toHaveValue('/link@TestHookahBot ABC123')
  expect(api.getGeneratedCalls()).toBe(1)
  await page.getByRole('button', { name: 'Создать новый код' }).click()
  await page.locator('.venue-modal').filter({ hasText: 'Текущий код перестанет работать' }).getByRole('button', {
    name: 'Создать новый код'
  }).click()
  await expect(page.getByLabel('Команда для привязки чата')).toHaveValue('/link@TestHookahBot DEF456')
  expect(api.getGeneratedCalls()).toBe(2)

  api.setLinked(true)
  await page.getByRole('button', { name: 'Проверить подключение' }).click()
  await expect(page.getByText('Чат персонала подключён')).toBeVisible()
  await expect(page.getByText('Чат: -100...7890')).toBeVisible()
  await page.getByRole('button', { name: 'Отправить тестовое сообщение' }).click()
  await expect(
    page.locator('.venue-chat-link > .status').filter({ hasText: 'Тестовое сообщение поставлено в отправку.' })
  ).toBeVisible()
  expect(api.getTestMessages()).toBe(1)

  await page.getByRole('button', { name: 'Отвязать чат' }).click()
  await expect(page.getByRole('heading', { name: 'Отвязать чат персонала?' })).toBeVisible()
  await page.getByRole('button', { name: 'Отмена' }).click()
  await expect(page.getByText('Чат персонала подключён')).toBeVisible()
  expect(api.getUnlinks()).toBe(0)

  await page.getByRole('button', { name: 'Отвязать чат' }).click()
  await page.getByRole('button', { name: 'Отвязать', exact: true }).click()
  await expect(page.getByText('Чат персонала не подключён')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Отправить тестовое сообщение' })).toHaveCount(0)
  expect(api.getUnlinks()).toBe(1)
})

test('venue manager can test staff chat but cannot unlink', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueStaffChatApi(page, { role: 'MANAGER', linked: true })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Чат персонала', exact: true }).click()
  await expect(page.getByText('Чат персонала подключён')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Отправить тестовое сообщение' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Отвязать чат' })).toHaveCount(0)
})

test('venue owner creates manager staff invite with copyable deep link', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: async (text: string) => {
          ;(window as Window & { __copiedText?: string }).__copiedText = text
        }
      }
    })
  })
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, { role: 'OWNER', linked: true })
  const deepLink = 'https://t.me/TestHookahBot?start=staff_invite_ABC234'
  const fallbackCommand = '/start staff_invite_ABC234'
  const shareUrl = `https://t.me/share/url?url=${encodeURIComponent(deepLink)}&text=${encodeURIComponent(
    'Приглашение в Микс. Роль: MANAGER. Откройте ссылку, чтобы принять доступ.'
  )}`

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Персонал' })).toBeVisible()
  await page.locator('.venue-staff select.venue-select').first().selectOption('MANAGER')
  await page.getByRole('button', { name: 'Создать инвайт' }).click()

  await expect(page.locator('.venue-invite-result')).toContainText('MANAGER')
  await expect(page.locator('.venue-invite-result')).toContainText('Микс')
  await expect(page.locator('.venue-invite-result')).toContainText('Приглашение создано')
  await expect(page.getByText('Ссылка для сотрудника')).toBeVisible()
  await expect(page.getByLabel('Ссылка для сотрудника')).toHaveValue(deepLink)
  await expect(page.getByLabel('Ссылка для сотрудника')).toBeVisible()
  await expect(page.locator('.venue-invite-result textarea#venue-staff-invite-link')).toHaveCount(1)
  await expect(page.locator('.venue-invite-result a')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Скопировать ссылку/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /Поделиться в Telegram/ })).toHaveAttribute('data-share-url', shareUrl)
  await expect(page.getByRole('button', { name: 'Открыть ссылку' })).toHaveCount(0)
  await expect(page.getByText('Код:')).toHaveCount(0)
  await expect(page.getByText('Передайте сотруднику приглашение.')).toHaveCount(0)
  await expect(page.locator('.venue-invite-result')).toContainText(
    'Отправьте эту ссылку сотруднику. Он откроет её в Telegram и получит роль MANAGER в заведении «Микс».'
  )
  await expect(page.getByText('Если ссылка не открылась')).toBeVisible()
  await expect(page.getByText('Скопируйте команду и отправьте её сотруднику вручную.')).not.toBeVisible()
  await page.getByText('Если ссылка не открылась').click()
  await expect(page.getByText('Скопируйте команду и отправьте её сотруднику вручную.')).toBeVisible()
  await expect(page.getByLabel('Команда, если ссылка не открылась')).toHaveValue(fallbackCommand)
  await expect(page.getByRole('button', { name: /Скопировать команду/ })).toBeVisible()
  const fallbackCommandFields = await page.locator('.venue-invite-result').evaluate((node, command) => {
    const fields = Array.from(node.querySelectorAll('textarea')) as HTMLTextAreaElement[]
    return fields.filter((field) => field.value === command).length
  }, fallbackCommand)
  expect(fallbackCommandFields).toBe(1)
  expect(api.getStaffInvites()).toBe(1)

  await page.getByRole('button', { name: /Скопировать ссылку/ }).click()
  await expect.poll(() => page.evaluate(() => (window as Window & { __copiedText?: string }).__copiedText)).toBe(deepLink)
  await expect(page.locator('.venue-invite-copy-status')).toHaveText('Ссылка скопирована')
  await page.getByRole('button', { name: /Скопировать команду/ }).click()
  await expect.poll(() => page.evaluate(() => (window as Window & { __copiedText?: string }).__copiedText)).toBe(
    fallbackCommand
  )
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: async () => {
          throw new Error('clipboard denied')
        }
      }
    })
  })
  await page.getByRole('button', { name: /Скопировать ссылку/ }).click()
  await expect(page.locator('.venue-invite-copy-status')).toHaveText(
    'Не удалось скопировать автоматически. Ссылка выделена ниже.'
  )
  await expect
    .poll(() =>
      page
        .getByLabel('Ссылка для сотрудника')
        .evaluate(
          (node) =>
            document.activeElement === node &&
            (node as HTMLTextAreaElement).selectionStart === 0 &&
            (node as HTMLTextAreaElement).selectionEnd === (node as HTMLTextAreaElement).value.length
        )
    )
    .toBe(true)
})

test('venue owner staff cards use human profile labels and hide raw technical fields', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, { role: 'OWNER', linked: true })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()

  const staffCards = page.locator('.venue-public-staff')
  const createForm = staffCards.locator('.venue-profile-form')
  await expect(staffCards.getByRole('heading', { name: 'Карточки сотрудников' })).toBeVisible()
  await expect(staffCards).toContainText(
    'Создайте карточки сотрудников, которых гости увидят в карточке заведения.'
  )
  await expect(staffCards).toContainText('Гости видят только опубликованные карточки.')
  await expect(staffCards).toContainText(
    'Отметьте сотрудника «Сегодня на смене», чтобы он появился у гостей в блоке «Сегодня работают».'
  )
  await expect(staffCards).not.toContainText('Публичные профили')
  await expect(createForm).toBeHidden()
  await expect(staffCards.getByRole('button', { name: 'Добавить карточку сотрудника' })).toBeVisible()

  const existingRow = staffCards.locator('.venue-profile-row').filter({ hasText: 'Алексей' })
  await expect(existingRow).toContainText('Кальянный мастер')
  await expect(existingRow).toContainText('Скрыт — виден только в кабинете')
  await expect(existingRow.getByLabel('Имя на карточке')).toHaveCount(0)
  await expect(existingRow.getByLabel('Тип сотрудника')).toHaveCount(0)

  await staffCards.getByRole('button', { name: 'Добавить карточку сотрудника' }).click()
  await expect(createForm.getByLabel('Имя на карточке')).toBeVisible()
  await expect(createForm.getByLabel('Тип сотрудника')).toBeVisible()
  await expect(createForm.getByLabel('Привязать к сотруднику')).toBeVisible()
  await expect(createForm.getByLabel('Коротко о сотруднике')).toBeVisible()
  await expect(createForm.getByLabel('Специализация')).toBeVisible()
  await expect(createForm.getByRole('option', { name: /#123456789/ })).toHaveCount(1)
  await expect(createForm).toContainText('Так это имя увидят гости.')
  await expect(createForm).toContainText('Гостям эта связь не показывается.')
  await expect(createForm).toContainText('Можно указать через запятую.')
  await expect(createForm).toContainText('Фото сотрудника — позже')
  await expect(createForm.getByPlaceholder('User ID')).toHaveCount(0)
  await expect(createForm.getByPlaceholder('Фото ref')).toHaveCount(0)
  await expect(createForm.getByPlaceholder('Photo ref')).toHaveCount(0)

  await createForm.getByLabel('Имя на карточке').fill('Максим')
  await createForm.getByLabel('Тип сотрудника').selectOption('other')
  await expect(createForm.getByLabel('Название роли')).toBeVisible()
  await expect(createForm.getByLabel('Название роли')).toHaveAttribute(
    'placeholder',
    'Например: Бармен, Старший смены, Мастер миксов'
  )
  await expect(createForm).toContainText('Так роль будет показана гостям.')
  await createForm.getByLabel('Привязать к сотруднику').selectOption('123456789')
  await createForm.getByLabel('Коротко о сотруднике').fill('Люблю крепкие миксы.')
  await createForm.getByLabel('Специализация').fill('крепкие миксы, авторские вкусы')
  await createForm.getByRole('button', { name: 'Создать профиль' }).click()
  await expect.poll(() => api.getProfileCreateRequests().length).toBe(0)
  await createForm.getByLabel('Название роли').fill('Мастер миксов')
  await createForm.getByRole('button', { name: 'Создать профиль' }).click()
  await expect(createForm).toBeHidden()
  await expect(staffCards.getByRole('button', { name: 'Добавить карточку сотрудника' })).toBeVisible()

  await expect(staffCards).toContainText('Максим')
  await expect(staffCards).toContainText('Мастер миксов')
  await expect(staffCards).toContainText('Скрыт — виден только в кабинете')
  await expect.poll(() => api.getProfileCreateRequests().length).toBe(1)
  const createRequest = api.getProfileCreateRequests()[0]
  expect(createRequest).toMatchObject({
    displayName: 'Максим',
    roleLabel: 'Мастер миксов',
    subtype: 'other',
    linkedUserId: 123456789,
    bio: 'Люблю крепкие миксы.',
    tags: ['крепкие миксы', 'авторские вкусы']
  })
  expect(createRequest).not.toHaveProperty('photoRef')

  const profileRow = staffCards.locator('.venue-profile-row').filter({ hasText: 'Максим' })
  await expect(profileRow).not.toContainText('Другое')
  await expect(profileRow.getByPlaceholder('User ID')).toHaveCount(0)
  await expect(profileRow.getByPlaceholder('Фото ref')).toHaveCount(0)
  await expect(profileRow.getByLabel('Имя на карточке')).toHaveCount(0)
  await expect(profileRow.getByLabel('Тип сотрудника')).toHaveCount(0)
  await profileRow.getByRole('button', { name: 'Редактировать' }).click()
  await expect(profileRow.getByLabel('Имя на карточке')).toBeVisible()
  await expect(profileRow.getByLabel('Тип сотрудника')).toBeVisible()
  await expect(profileRow.getByLabel('Название роли')).toBeVisible()
  await profileRow.getByRole('button', { name: 'Сохранить' }).click()
  await expect.poll(() => api.getProfileUpdateRequests().length).toBe(1)
  const updateRequest = api.getProfileUpdateRequests()[0]
  expect(updateRequest).toMatchObject({
    displayName: 'Максим',
    roleLabel: 'Мастер миксов',
    subtype: 'other',
    linkedUserId: 123456789,
    bio: 'Люблю крепкие миксы.',
    tags: ['крепкие миксы', 'авторские вкусы']
  })
  expect(updateRequest).not.toHaveProperty('photoRef')

  await profileRow.getByRole('button', { name: 'Опубликовать' }).click()
  await expect(profileRow).toContainText('Опубликован — виден гостям')
  await profileRow.getByRole('button', { name: 'Сегодня на смене' }).click()
  await expect.poll(() => api.getShiftRequests().length).toBe(1)
  await profileRow.getByRole('button', { name: 'Не на смене сегодня' }).click()
  await expect.poll(() => api.getShiftRequests().length).toBe(2)
  expect(api.getShiftRequests()).toEqual([
    { status: 'active', isGuestVisible: true },
    { status: 'canceled', isGuestVisible: false }
  ])
})

test('expired staff chat link code is not presented as usable', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueStaffChatApi(page, {
    role: 'OWNER',
    linked: false,
    generatedExpiresAt: '2000-01-10T19:00:00Z'
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Чат персонала', exact: true }).click()
  await page.getByRole('button', { name: 'Сгенерировать код привязки' }).click()

  await expect(page.getByRole('heading', { name: 'Срок действия кода истёк' })).toBeVisible()
  await expect(page.getByLabel('Команда для привязки чата')).toHaveValue('Код больше не действует.')
  await expect(page.getByRole('button', { name: 'Скопировать команду' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Сгенерировать новый код' })).toBeVisible()
})

test('venue staff does not see staff chat management', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueStaffChatApi(page, { role: 'STAFF', permissions: [], linked: true })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Чат персонала', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    window.location.hash = '#/chat'
  })
  await expect(page.getByRole('heading', { name: 'Недостаточно прав' })).toBeVisible()
})

test('venue manager configures public profile card settings', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueShiftExtensionApi(page, {
    role: 'MANAGER',
    permissions: ['BOOKING_MANAGE'],
    publicCardSettings: buildPublicCardSettings({
      city: 'Москва',
      address: 'Пилотная, 1',
      guestContact: null,
      cardDescription: null
    })
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Настройки', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()
  const publicCard = page.locator('.card').filter({ has: page.getByRole('heading', { name: 'Публичная карточка' }) })
  await expect(publicCard).toContainText('Эти данные видят гости в каталоге')
  await expect(publicCard).toContainText('Микс')
  await expect(publicCard.getByRole('button', { name: 'Сохранить' })).toBeDisabled()

  const cityInput = publicCard.getByPlaceholder('Начните вводить город')
  await cityInput.focus()
  const focusStyle = await cityInput.evaluate((input) => {
    const styles = window.getComputedStyle(input)
    return { caretColor: styles.caretColor, boxShadow: styles.boxShadow }
  })
  expect(focusStyle.caretColor).not.toBe('rgba(0, 0, 0, 0)')
  expect(focusStyle.boxShadow).not.toBe('none')

  await publicCard.getByPlaceholder('Россия').fill('Р')
  await expect(publicCard.getByRole('button', { name: 'Россия · RU' })).toHaveCount(0)
  await publicCard.getByPlaceholder('Россия').fill('Ка')
  await publicCard.getByRole('button', { name: 'Казахстан · KZ' }).click()
  await cityInput.fill('Са')
  await expect(publicCard.getByText('Санкт-Петербург')).toHaveCount(0)
  await expect(publicCard.getByText('Ничего не найдено. Можно ввести город вручную.')).toBeVisible()
  await publicCard.getByPlaceholder('Россия').fill('Ро')
  await publicCard.getByRole('button', { name: 'Россия · RU' }).click()
  await cityInput.fill('Са')
  await publicCard.getByRole('button', { name: /Санкт-Петербург/ }).click()
  await publicCard.getByPlaceholder('Улица, дом').fill('Литейный проспект, 7')
  await expect(publicCard.getByText('Маршрут будет построен по указанному адресу.')).toBeVisible()
  await publicCard.getByPlaceholder('+7 999 000-00-00').fill('+7 900 111-22-33')
  await publicCard.getByPlaceholder(/авторские чаши/).fill('Лаунж с чайной картой и спокойной посадкой.')
  await expect(publicCard.getByRole('button', { name: 'Сохранить' })).toBeEnabled()
  await publicCard.getByRole('button', { name: 'Сохранить' }).click()

  await expect(page.locator('p.status')).toHaveText('Публичная карточка сохранена.')
  await expect(publicCard.getByRole('button', { name: '✓ Сохранено' })).toBeDisabled()
  expect(api.getUpdatePublicCardSettingsCalls()).toBe(1)
  expect(api.getPublicCardSettings()).toMatchObject({
    city: 'Санкт-Петербург',
    address: 'Литейный проспект, 7',
    countryCode: 'RU',
    formattedAddress: null,
    latitude: null,
    longitude: null,
    guestContact: '+7 900 111-22-33',
    cardDescription: 'Лаунж с чайной картой и спокойной посадкой.'
  })
  expect(api.getLocationProviderCalls()).toBe(0)

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.evaluate(() => {
    window.location.hash = '#/venue/1'
  })
  await expect(page.getByText('Санкт-Петербург, Литейный проспект, 7')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Скопировать адрес' })).toBeVisible()
  const routeLink = page.getByRole('link', { name: 'Построить маршрут' })
  await expect(routeLink).toHaveAttribute(
    'href',
    buildTextRouteUrl('Микс', 'RU', 'Санкт-Петербург', 'Литейный проспект, 7')
  )
})

test('venue staff does not see public profile card settings', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueShiftExtensionApi(page, {
    role: 'STAFF',
    permissions: ['ORDER_QUEUE_VIEW']
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Настройки', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    window.location.hash = '#/settings'
  })
  await expect(page.getByRole('heading', { name: 'Недостаточно прав' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Публичная карточка' })).toHaveCount(0)
})

test('venue public card failed save preserves manual location draft', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueShiftExtensionApi(page, {
    role: 'MANAGER',
    permissions: ['BOOKING_MANAGE'],
    failPublicCardUpdateOnce: true
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()

  const publicCard = page.locator('.card').filter({ has: page.getByRole('heading', { name: 'Публичная карточка' }) })
  await publicCard.getByPlaceholder('Начните вводить город').fill('Иннополис')
  await expect(publicCard.getByText('Ничего не найдено. Можно ввести город вручную.')).toBeVisible()
  await publicCard.getByPlaceholder('Улица, дом').fill('Баумана, 7')
  await publicCard.getByRole('button', { name: 'Ввести адрес вручную' }).click()
  await expect(publicCard.getByText('Маршрут будет построен по указанному адресу.')).toBeVisible()
  await publicCard.getByRole('button', { name: 'Сохранить' }).click()

  await expect(page.locator('p.status')).toContainText('Не удалось сохранить публичную карточку.')
  await expect(publicCard.getByPlaceholder('Начните вводить город')).toHaveValue('Иннополис')
  await expect(publicCard.getByPlaceholder('Улица, дом')).toHaveValue('Баумана, 7')
  await expect(publicCard.getByRole('button', { name: 'Сохранить' })).toBeEnabled()
})

test('venue manager configures working hours and date exceptions', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueShiftExtensionApi(page, {
    role: 'MANAGER',
    permissions: ['BOOKING_MANAGE']
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Настройки', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()
  let scheduleCard = page.locator('.card').filter({ has: page.getByRole('heading', { name: 'Часы работы' }) })
  await expect(scheduleCard).toContainText('7 дней · исключений: 0')

  const mondayRow = scheduleCard.getByText('Пн · 18:00-00:00').locator('xpath=..')
  await mondayRow.getByLabel('Закрыто').check()
  await mondayRow.getByRole('button', { name: 'Сохранить день' }).click()
  await expect(page.locator('p.status')).toHaveText('Часы работы сохранены.')
  expect(api.getScheduleSettings().weeklyHours.find((day) => day.weekday === 1)).toMatchObject({
    isClosed: true,
    opensAt: '00:00',
    closesAt: '00:00'
  })

  await page.getByRole('button', { name: 'Вернуться в обзор' }).click()
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()
  scheduleCard = page.locator('.card').filter({ has: page.getByRole('heading', { name: 'Часы работы' }) })
  await expect(scheduleCard).toContainText('Пн · Закрыто')

  await expect(scheduleCard.getByTestId('schedule-exception-form')).toBeHidden()
  await scheduleCard.getByRole('button', { name: 'Закрыть период' }).click()
  let exceptionForm = scheduleCard.getByTestId('schedule-exception-form')
  await expect(exceptionForm.getByRole('button', { name: 'Сохранить' })).toBeDisabled()
  await exceptionForm.locator('input[type="date"]').nth(0).fill('2030-01-10')
  await exceptionForm.locator('input[type="date"]').nth(1).fill('2030-01-12')
  await exceptionForm.locator('textarea').fill('Санитарный день')
  await exceptionForm.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.locator('p.status')).toHaveText('Исключение сохранено.')
  await expect(exceptionForm).toBeHidden()
  await expect(scheduleCard.getByRole('button', { name: 'Скрыть исключения' })).toBeVisible()
  await expect(scheduleCard.getByTestId('schedule-exception-list')).toBeVisible()
  await expect(scheduleCard.getByTestId('schedule-exception-list')).toContainText('10.01.2030–12.01.2030 · Закрыто')
  await expect(scheduleCard.getByTestId('schedule-exception-list')).toContainText('Причина: Санитарный день')
  expect(api.getScheduleSettings().dateOverrides).toHaveLength(3)

  await scheduleCard.getByRole('button', { name: 'Изменить часы на период' }).click()
  exceptionForm = scheduleCard.getByTestId('schedule-exception-form')
  await expect(exceptionForm.locator('input[type="date"]').nth(0)).toHaveValue('')
  await expect(exceptionForm.locator('input[type="date"]').nth(1)).toHaveValue('')
  await expect(exceptionForm.locator('input[type="time"]').nth(0)).toHaveValue('18:00')
  await expect(exceptionForm.locator('input[type="time"]').nth(1)).toHaveValue('00:00')
  await expect(exceptionForm.locator('textarea')).toHaveValue('')
  await expect(exceptionForm.getByRole('button', { name: 'Сохранить' })).toBeDisabled()
  await exceptionForm.locator('input[type="date"]').nth(0).fill('2030-01-20')
  await exceptionForm.locator('input[type="date"]').nth(1).fill('2030-01-21')
  await exceptionForm.locator('input[type="time"]').nth(0).fill('12:00')
  await exceptionForm.locator('input[type="time"]').nth(1).fill('23:00')
  await exceptionForm.locator('textarea').fill('Праздничный график')
  await exceptionForm.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.locator('p.status')).toHaveText('Особые часы сохранены.')
  await expect(exceptionForm).toBeHidden()
  await expect(scheduleCard.getByTestId('schedule-exception-list')).toContainText('20.01.2030–21.01.2030 · 12:00–23:00')
  await expect(scheduleCard.getByTestId('schedule-exception-list')).toContainText('Комментарий: Праздничный график')
  expect(api.getScheduleSettings().dateOverrides).toHaveLength(5)

  await scheduleCard.getByRole('button', { name: 'Изменить часы на период' }).click()
  exceptionForm = scheduleCard.getByTestId('schedule-exception-form')
  await expect(exceptionForm.locator('input[type="date"]').nth(0)).toHaveValue('')
  await expect(exceptionForm.locator('input[type="date"]').nth(1)).toHaveValue('')
  await expect(exceptionForm.locator('textarea')).toHaveValue('')
  await exceptionForm.getByRole('button', { name: 'Отмена' }).click()

  await scheduleCard
    .getByTestId('schedule-exception-list')
    .getByText('10.01.2030–12.01.2030 · Закрыто')
    .locator('xpath=..')
    .getByRole('button', { name: 'Изменить' })
    .click()
  exceptionForm = scheduleCard.getByTestId('schedule-exception-form')
  await expect(exceptionForm.locator('input[type="date"]').nth(0)).toBeEnabled()
  await exceptionForm.locator('input[type="date"]').nth(0).fill('2030-01-13')
  await exceptionForm.locator('input[type="date"]').nth(1).fill('2030-01-14')
  await exceptionForm.locator('textarea').fill('Плановый выходной')
  await exceptionForm.getByRole('button', { name: 'Сохранить' }).click()
  await expect(exceptionForm).toBeHidden()
  await expect(scheduleCard.getByTestId('schedule-exception-list')).not.toContainText('10.01.2030–12.01.2030 · Закрыто')
  await expect(scheduleCard.getByTestId('schedule-exception-list')).toContainText('13.01.2030–14.01.2030 · Закрыто')
  await expect(scheduleCard.getByTestId('schedule-exception-list')).toContainText('Причина: Плановый выходной')

  await scheduleCard
    .getByTestId('schedule-exception-list')
    .getByText('20.01.2030–21.01.2030 · 12:00–23:00')
    .locator('xpath=..')
    .getByRole('button', { name: 'Изменить' })
    .click()
  exceptionForm = scheduleCard.getByTestId('schedule-exception-form')
  await expect(exceptionForm.locator('input[type="date"]').nth(0)).toBeEnabled()
  await exceptionForm.locator('input[type="date"]').nth(0).fill('2030-01-22')
  await exceptionForm.locator('input[type="date"]').nth(1).fill('2030-01-22')
  await exceptionForm.locator('input[type="time"]').nth(0).fill('13:00')
  await exceptionForm.locator('input[type="time"]').nth(1).fill('01:00')
  await exceptionForm.locator('textarea').fill('Новогодний график')
  await exceptionForm.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.locator('p.status')).toHaveText('Особые часы сохранены.')
  await expect(exceptionForm).toBeHidden()
  await expect(scheduleCard.getByTestId('schedule-exception-list')).not.toContainText('20.01.2030–21.01.2030 · 12:00–23:00')
  await expect(scheduleCard.getByTestId('schedule-exception-list')).toContainText('22.01.2030 · 13:00–01:00')
  await expect(scheduleCard.getByTestId('schedule-exception-list')).toContainText('Комментарий: Новогодний график')
  expect(api.getScheduleSettings().dateOverrides).toHaveLength(3)

  await scheduleCard.getByRole('button', { name: 'Изменить часы на период' }).click()
  exceptionForm = scheduleCard.getByTestId('schedule-exception-form')
  await expect(exceptionForm.locator('input[type="date"]').nth(0)).toHaveValue('')
  await expect(exceptionForm.locator('input[type="date"]').nth(1)).toHaveValue('')
  await expect(exceptionForm.locator('textarea')).toHaveValue('')
  await exceptionForm.getByRole('button', { name: 'Отмена' }).click()

  await scheduleCard
    .getByTestId('schedule-exception-list')
    .getByText('22.01.2030 · 13:00–01:00')
    .locator('xpath=..')
    .getByRole('button', { name: 'Удалить' })
    .click()
  await expect(page.locator('p.status')).toHaveText('Исключение удалено.')
  expect(api.getScheduleSettings().dateOverrides).toHaveLength(2)
})

test('venue manager configures paid shift extension settings', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueShiftExtensionApi(page, {
    role: 'MANAGER',
    permissions: ['ORDER_QUEUE_VIEW', 'BOOKING_MANAGE', 'SHIFT_EXTENSION_VIEW', 'SHIFT_EXTENSION_CONFIRM', 'SHIFT_EXTENSION_SETTINGS'],
    settings: buildShiftExtensionSettings()
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Настройки', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Настройки брони' })).toBeVisible()
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

test('venue manager configures booking hold settings', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueShiftExtensionApi(page, {
    role: 'MANAGER',
    permissions: ['BOOKING_MANAGE'],
    bookingSettings: buildBookingSettings({ holdMinutes: 30 })
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Настройки', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()
  const bookingCard = page.locator('.card').filter({ has: page.getByRole('heading', { name: 'Настройки брони' }) })
  await expect(bookingCard).toContainText('Держим бронь: 30 минут')
  await expect(bookingCard).toContainText('если бронь на 19:00')
  await expect(page.getByRole('heading', { name: 'Продление времени' })).toHaveCount(0)

  await bookingCard.getByPlaceholder('15').fill('45')
  await bookingCard.getByRole('button', { name: 'Сохранить' }).click()

  await expect(page.locator('p.status')).toHaveText('Настройки брони сохранены.')
  await expect(bookingCard).toContainText('Держим бронь: 45 минут')
  await expect(bookingCard).toContainText('стол держим до 19:45')
  expect(api.getUpdateBookingSettingsCalls()).toBe(1)
  expect(api.getBookingSettings()).toMatchObject({ holdMinutes: 45 })
})

test('venue booking queue shows guest attendance confirmation only when present', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueBookingsApi(page, {
    role: 'MANAGER',
    bookings: [
      buildVenueBooking({
        bookingId: 701,
        displayNumber: 12,
        status: 'confirmed',
        lastGuestConfirmationAt: '10.01.2030, 21:05'
      }),
      buildVenueBooking({
        bookingId: 702,
        displayNumber: 13,
        status: 'changed',
        scheduledAt: '2030-01-11T17:15:00Z',
        scheduledAtDisplay: '11.01.2030, 20:15',
        scheduledLocalDate: '2030-01-11',
        scheduledLocalTime: '20:15',
        serviceDate: '2030-01-11',
        arrivalDeadlineAt: '2030-01-11T17:45:00Z',
        arrivalDeadlineAtDisplay: '11.01.2030, 20:45',
        comment: 'без отметки',
        lastGuestConfirmationAt: null
      }),
      buildVenueBooking({
        bookingId: 703,
        displayNumber: 14,
        status: 'pending',
        scheduledAt: '2030-01-12T17:15:00Z',
        scheduledAtDisplay: '12.01.2030, 20:15',
        scheduledLocalDate: '2030-01-12',
        scheduledLocalTime: '20:15',
        serviceDate: '2030-01-12',
        arrivalDeadlineAt: '2030-01-12T17:45:00Z',
        arrivalDeadlineAtDisplay: '12.01.2030, 20:45',
        comment: 'ожидает',
        lastGuestConfirmationAt: null
      })
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Брони', exact: true }).click()

  const confirmedCard = page.locator('.venue-booking-card').filter({ hasText: 'Бронь №12' })
  await expect(confirmedCard).toContainText('подтверждена')
  await expect(confirmedCard).toContainText('Гость подтвердил визит: 10.01.2030, 21:05')
  await expect(confirmedCard.getByRole('button', { name: 'Гость пришёл' })).toBeVisible()
  await expect(confirmedCard.getByRole('button', { name: 'Не пришёл' })).toBeVisible()

  const changedCard = page.locator('.venue-booking-card').filter({ hasText: 'Бронь №13' })
  await expect(changedCard).toContainText('перенесена')
  await expect(changedCard).not.toContainText('Гость подтвердил визит')
  await expect(changedCard.getByRole('button', { name: 'Гость пришёл' })).toHaveCount(0)
  await expect(changedCard.getByRole('button', { name: 'Не пришёл' })).toHaveCount(0)

  const pendingCard = page.locator('.venue-booking-card').filter({ hasText: 'Бронь №14' })
  await expect(pendingCard).toContainText('ожидает')
  await expect(pendingCard.getByRole('button', { name: 'Гость пришёл' })).toHaveCount(0)
  await expect(pendingCard.getByRole('button', { name: 'Не пришёл' })).toHaveCount(0)
})

test('venue manager manages bookings queue lifecycle', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueBookingsApi(page, {
    role: 'MANAGER',
    bookings: [buildVenueBooking({ lastGuestConfirmationAt: '10.01.2030, 21:05' })]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Брони', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Брони', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Брони' })).toBeVisible()
  await expect(page.getByText('Бронь №12')).toBeVisible()
  await expect(page.getByText(/10\.01\.2030, 21:30/)).toBeVisible()
  await expect(page.getByText('Гость: Алексей')).toBeVisible()
  await expect(page.getByText('Держим до: 10.01.2030, 22:00')).toBeVisible()
  await expect(page.getByText('Гость подтвердил визит: 10.01.2030, 21:05')).toBeVisible()
  await expect(page.getByText('у окна')).toBeVisible()

  await page.getByRole('button', { name: 'Написать гостю' }).click()
  await expect(page.getByRole('heading', { name: 'Сообщение гостю' })).toBeVisible()
  await expect(page.getByText('Сообщение придёт гостю в Telegram и появится в переписке.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'На это время все столы заняты. Можем предложить другое время?' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Уточните, пожалуйста, детали брони.' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Подтверждаем бронь вручную, ждём вас' })).toHaveCount(0)
  await page.getByPlaceholder('Например: На 19:00 все столы заняты. Можем предложить 20:30?').fill(
    'На 19:00 все столы заняты. Можем предложить 20:30?'
  )
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect(page.getByRole('heading', { name: 'Сообщение гостю' })).toHaveCount(0)
  await expect(page.locator('.venue-bookings-screen .status').filter({ hasText: 'Сообщение отправлено гостю.' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Открыть переписку' })).toBeVisible()
  expect(api.getMessageCalls()).toBe(1)
  expect(api.getBookingMessages()).toEqual(['На 19:00 все столы заняты. Можем предложить 20:30?'])

  await page.getByRole('button', { name: 'Открыть переписку' }).click()
  await expect(page.getByRole('heading', { name: 'Сообщения' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Активные' })).toBeVisible()
  const venueThreadCard = page.locator('.venue-message-thread-card').filter({ hasText: 'Бронь №12' })
  await expect(venueThreadCard).toBeVisible()
  await expect(venueThreadCard).toContainText('Гость: Алексей')
  await expect(venueThreadCard).toContainText('В работе')
  await expect(venueThreadCard).toContainText('На 19:00 все столы заняты')
  await expect(page.locator('.venue-messages-detail').getByText(/На 19:00 все столы заняты/)).toBeVisible()
  await page.getByPlaceholder('Напишите ответ гостю.').fill('Можем забронировать на 20:30.')
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect(page.locator('.venue-messages-detail .status').filter({ hasText: 'Сообщение отправлено гостю.' })).toBeVisible()
  expect(api.getSupportMessages().map((message) => message.text)).toContain('Можем забронировать на 20:30.')
  await page.getByRole('button', { name: 'Завершить переписку' }).click()
  await expect(page.locator('.venue-messages-detail').getByText('Переписка завершена.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Возобновить переписку' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Завершённые' })).toHaveAttribute('data-active', 'true')
  await page.getByRole('button', { name: 'Активные' }).click()
  await expect(page.getByText('Сообщений пока нет.')).toBeVisible()
  await page.getByRole('button', { name: 'Завершённые' }).click()
  await expect(venueThreadCard).toBeVisible()
  await page.getByRole('button', { name: 'Возобновить переписку' }).click()
  await expect(page.getByRole('button', { name: 'Завершить переписку' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Активные' })).toHaveAttribute('data-active', 'true')
  await page.getByRole('button', { name: 'Брони', exact: true }).click()

  await page.getByRole('button', { name: 'Подтвердить' }).click()
  await expect(page.locator('.venue-booking-card .venue-order-meta').filter({ hasText: 'подтверждена' })).toBeVisible()
  expect(api.getConfirmCalls()).toBe(1)

  await page.getByText('Перенести бронь').click()
  await page.locator('.venue-booking-change input[type="date"]').fill('2030-01-11')
  await page.locator('.venue-booking-change input[type="time"]').fill('20:15')
  await page.locator('.venue-booking-change').getByRole('button', { name: 'Перенести' }).click()
  await expect(page.locator('.venue-booking-card .venue-order-meta').filter({ hasText: 'перенесена' })).toBeVisible()
  expect(api.getChangeCalls()).toBe(1)
  expect(api.getChangeRequests()).toEqual([{ scheduledLocalDate: '2030-01-11', scheduledLocalTime: '20:15' }])

  page.once('dialog', async (dialog) => {
    await dialog.accept('Гость попросил отменить')
  })
  await page.getByRole('button', { name: 'Отменить' }).click()
  await expect(page.getByText('Активных броней пока нет.')).toBeVisible()
  expect(api.getCancelCalls()).toBe(1)
  expect(api.getCancelReasons()).toEqual(['Гость попросил отменить'])
})

test('venue staff sees booking arrival controls only', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueBookingsApi(page, {
    role: 'STAFF',
    permissions: ['BOOKING_VIEW', 'BOOKING_ARRIVAL_UPDATE'],
    bookings: [buildVenueBooking({ status: 'confirmed' })]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Брони', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Обращения', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    window.location.hash = '#/support'
  })
  await expect(page.getByRole('heading', { name: 'Недостаточно прав' })).toBeVisible()
  await page.getByRole('button', { name: 'Брони', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Брони' })).toBeVisible()
  await expect(page.getByText('Бронь №12')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Подтвердить' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Отменить' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Написать гостю' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Сообщения', exact: true })).toHaveCount(0)
  await expect(page.getByText('Перенести бронь')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Гость пришёл' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Не пришёл' })).toBeVisible()

  page.once('dialog', async (dialog) => {
    await dialog.accept()
  })
  await page.getByRole('button', { name: 'Гость пришёл' }).click()
  await expect(page.getByText('Активных броней пока нет.')).toBeVisible()
  expect(api.getSeatCalls()).toBe(1)
  expect(api.getNoShowCalls()).toBe(0)
})

test('guest replies to booking thread from Mini App messages', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, { restoreContext: null })

  const thread: SupportThreadFixture = {
    threadId: 4100,
    venueId: 1,
    venueName: 'Микс',
    threadType: 'BOOKING_THREAD',
    assigneeScope: 'VENUE',
    category: 'BOOKING',
    contextLabel: 'Бронь №12',
    status: 'OPEN',
    statusLabel: 'В работе',
    bookingId: 701,
    title: 'Бронь №12',
    lastMessagePreview: 'На 19:00 все столы заняты. Можем предложить 20:30?',
    lastMessageAt: '2030-01-10T18:01:00Z',
    unreadCount: 1,
    createdAt: '2030-01-10T18:00:00Z',
    updatedAt: '2030-01-10T18:01:00Z',
    booking: {
      bookingId: 701,
      displayNumber: 12,
      scheduledAt: '2030-01-10T18:30:00Z',
      partySize: 4,
      status: 'confirmed'
    }
  }
  let nextMessageId = 6100
  let messages: SupportMessageFixture[] = [
    {
      messageId: nextMessageId++,
      threadId: thread.threadId,
      authorRole: 'VENUE',
      source: 'VENUE_MINIAPP',
      text: 'На 19:00 все столы заняты. Можем предложить 20:30?',
      createdAt: '2030-01-10T18:01:00Z'
    }
  ]

  await page.route('**/api/guest/support/threads**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const threadMatch = url.pathname.match(/\/api\/guest\/support\/threads\/(\d+)(?:\/(messages|resolve|reopen))?$/)
    if (!threadMatch && request.method() === 'GET') {
      const filter = url.searchParams.get('filter')
      const items =
        filter === 'resolved'
          ? thread.status === 'RESOLVED' || thread.status === 'CLOSED'
            ? [thread]
            : []
          : thread.status === 'OPEN'
            ? [thread]
            : []
      await route.fulfill(jsonResponse({ items }))
      return
    }
    if (!threadMatch) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    const threadId = Number(threadMatch[1])
    if (threadId !== thread.threadId) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    const threadAction = threadMatch[2]
    if (threadAction === 'resolve' && request.method() === 'POST') {
      thread.status = 'RESOLVED'
      thread.updatedAt = '2030-01-10T18:06:00Z'
      thread.unreadCount = 0
      await route.fulfill(jsonResponse({ thread, messages }))
      return
    }
    if (threadAction === 'reopen' && request.method() === 'POST') {
      thread.status = 'OPEN'
      thread.updatedAt = '2030-01-10T18:07:00Z'
      thread.unreadCount = 0
      await route.fulfill(jsonResponse({ thread, messages }))
      return
    }
    if (threadAction === 'messages' && request.method() === 'POST') {
      const body = (await request.postDataJSON()) as { message?: string | null }
      const message: SupportMessageFixture = {
        messageId: nextMessageId++,
        threadId,
        authorRole: 'GUEST',
        source: 'GUEST_MINIAPP',
        text: body.message ?? '',
        createdAt: '2030-01-10T18:05:00Z'
      }
      messages = [...messages, message]
      thread.lastMessagePreview = message.text
      thread.lastMessageAt = message.createdAt
      thread.updatedAt = message.createdAt
      thread.status = 'OPEN'
      thread.unreadCount = 0
      await route.fulfill(jsonResponse({ thread, message, queued: true }))
      return
    }
    if (request.method() === 'GET') {
      thread.unreadCount = 0
      await route.fulfill(jsonResponse({ thread, messages }))
      return
    }
    await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Чаты' }).click()
  await expect(page.getByRole('heading', { name: 'Чаты' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Активные' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Завершённые' })).toBeVisible()
  const guestThreadCard = page.locator('.venue-message-thread-card').filter({ hasText: 'Бронь №12' })
  await expect(guestThreadCard).toBeVisible()
  await expect(guestThreadCard).toContainText('Микс')
  await expect(guestThreadCard).toContainText('В работе')
  await expect(guestThreadCard).toContainText('На 19:00 все столы заняты')
  await expect(guestThreadCard.locator('.menu-item-badge')).toHaveCount(0)
  await expect(page.locator('.venue-messages-detail').getByText(/На 19:00 все столы заняты/)).toBeVisible()
  await page.getByRole('button', { name: 'Завершённые' }).click()
  await expect(page.getByText('Пока нет чатов. Вы можете задать вопрос заведению из каталога или карточки заведения.')).toBeVisible()
  await page.getByRole('button', { name: 'Активные' }).click()
  await expect(guestThreadCard).toBeVisible()
  await page.getByRole('button', { name: 'Завершить переписку' }).click()
  await expect(page.locator('.venue-messages-detail').getByText('Переписка завершена.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Возобновить переписку' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Завершённые' })).toHaveAttribute('data-active', 'true')
  await page.getByRole('button', { name: 'Активные' }).click()
  await expect(page.getByText('Пока нет чатов. Вы можете задать вопрос заведению из каталога или карточки заведения.')).toBeVisible()
  await page.getByRole('button', { name: 'Завершённые' }).click()
  await expect(guestThreadCard).toBeVisible()
  await page.getByRole('button', { name: 'Возобновить переписку' }).click()
  await expect(page.getByRole('button', { name: 'Завершить переписку' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Активные' })).toHaveAttribute('data-active', 'true')
  await page.getByPlaceholder('Напишите ответ заведению.').fill('Да, 20:30 подходит.')
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect(
    page.locator('.venue-messages-detail .status').filter({ hasText: 'Сообщение отправлено заведению.' })
  ).toBeVisible()
  expect(messages.map((message) => message.text)).toContain('Да, 20:30 подходит.')
})

test('guest opens venue chat from catalog and venue card question actions', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, { restoreContext: null })

  const thread: SupportThreadFixture = {
    threadId: 4150,
    venueId: 1,
    venueName: 'Микс',
    threadType: 'VENUE_CHAT',
    assigneeScope: 'VENUE',
    category: 'OTHER',
    contextLabel: 'Чат с Микс',
    status: 'IN_PROGRESS',
    statusLabel: 'В работе',
    title: 'Чат с Микс',
    lastMessagePreview: null,
    lastMessageAt: null,
    unreadCount: 0,
    createdAt: '2030-01-10T18:00:00Z',
    updatedAt: '2030-01-10T18:00:00Z'
  }
  let createCalls = 0

  await page.route('**/api/guest/support/venue-chats', async (route) => {
    createCalls += 1
    await route.fulfill(jsonResponse({ thread, messages: [] }))
  })
  await page.route('**/api/guest/support/threads**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const threadMatch = url.pathname.match(/\/api\/guest\/support\/threads\/(\d+)$/)
    if (!threadMatch && request.method() === 'GET') {
      const threadTypes = url.searchParams.get('threadTypes') ?? url.searchParams.get('threadType') ?? ''
      await route.fulfill(jsonResponse({ items: threadTypes.includes('VENUE_CHAT') ? [thread] : [] }))
      return
    }
    if (threadMatch && request.method() === 'GET' && Number(threadMatch[1]) === thread.threadId) {
      await route.fulfill(jsonResponse({ thread, messages: [] }))
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Чаты' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Помощь' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Задать вопрос' })).toBeVisible()

  await page.getByRole('button', { name: 'Задать вопрос' }).click()
  await expect(page.getByRole('heading', { name: 'Чаты' })).toBeVisible()
  await expect(page.locator('.venue-messages-detail').getByRole('heading', { name: 'Чат с Микс' })).toBeVisible()
  expect(createCalls).toBe(1)

  await page.evaluate(() => {
    window.location.hash = '#/catalog'
  })
  await page.getByRole('button', { name: 'Открыть карточку' }).click()
  await expect(page.getByRole('button', { name: '💬 Задать вопрос' })).toBeVisible()
  await page.getByRole('button', { name: '💬 Задать вопрос' }).click()
  await expect(page.locator('.venue-messages-detail').getByRole('heading', { name: 'Чат с Микс' })).toBeVisible()
  expect(createCalls).toBe(2)
})

test('guest support tickets stay list-first and open detail only by choice or creation', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, { restoreContext: buildRestoreContext() })

  let nextThreadId = 4300
  let nextMessageId = 6300
  let threads: SupportThreadFixture[] = [
    {
      threadId: 4200,
      venueId: 1,
      venueName: 'Микс',
      threadType: 'SUPPORT_TICKET',
      assigneeScope: 'VENUE',
      category: 'ORDER_SERVICE',
      contextLabel: 'Стол №4',
      status: 'NEW',
      statusLabel: 'Новый',
      tableId: 10,
      tableSessionId: 77,
      tableLabel: 'Стол №4',
      title: 'Обслуживание',
      lastMessagePreview: 'Нужна помощь по заказу',
      lastMessageAt: '2030-01-10T18:01:00Z',
      unreadCount: 0,
      createdAt: '2030-01-10T18:00:00Z',
      updatedAt: '2030-01-10T18:01:00Z'
    }
  ]
  let messages: SupportMessageFixture[] = [
    {
      messageId: nextMessageId++,
      threadId: 4200,
      authorRole: 'GUEST',
      source: 'GUEST_MINIAPP',
      text: 'Нужна помощь по заказу',
      createdAt: '2030-01-10T18:01:00Z'
    }
  ]

  await page.route('**/api/guest/support/threads**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const threadMatch = url.pathname.match(/\/api\/guest\/support\/threads\/(\d+)(?:\/messages)?$/)
    if (!threadMatch && request.method() === 'GET') {
      const threadType = url.searchParams.get('threadType')
      const threadTypes = url.searchParams.get('threadTypes') ?? threadType ?? ''
      const items =
        threadTypes.includes('BOOKING_THREAD') || threadTypes.includes('VENUE_CHAT')
          ? []
          : threads.filter((thread) => ['OPEN', 'NEW', 'IN_PROGRESS', 'WAITING_USER'].includes(thread.status))
      await route.fulfill(jsonResponse({ items }))
      return
    }
    if (!threadMatch && request.method() === 'POST') {
      const body = (await request.postDataJSON()) as { category?: string; message?: string; tableToken?: string | null; tableSessionId?: number | null }
      const thread: SupportThreadFixture = {
        threadId: nextThreadId++,
        venueId: 1,
        venueName: 'Микс',
        threadType: 'SUPPORT_TICKET',
        assigneeScope: 'VENUE',
        category: body.category ?? 'ORDER_SERVICE',
        contextLabel: 'Стол №4',
        status: 'NEW',
        statusLabel: 'Новый',
        tableId: 10,
        tableSessionId: body.tableSessionId ?? 77,
        tableLabel: 'Стол №4',
        title: 'Обслуживание',
        lastMessagePreview: body.message ?? '',
        lastMessageAt: '2030-01-10T18:05:00Z',
        unreadCount: 0,
        createdAt: '2030-01-10T18:05:00Z',
        updatedAt: '2030-01-10T18:05:00Z'
      }
      const message: SupportMessageFixture = {
        messageId: nextMessageId++,
        threadId: thread.threadId,
        authorRole: 'GUEST',
        source: 'GUEST_MINIAPP',
        text: body.message ?? '',
        createdAt: thread.createdAt
      }
      threads = [thread, ...threads]
      messages = [...messages, message]
      await route.fulfill(jsonResponse({ thread, message, queued: false }))
      return
    }
    if (!threadMatch) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    const threadId = Number(threadMatch[1])
    const thread = threads.find((item) => item.threadId === threadId)
    if (!thread) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    if (request.method() === 'GET') {
      await route.fulfill(jsonResponse({ thread, messages: messages.filter((message) => message.threadId === threadId) }))
      return
    }
    await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: '💬 Связаться с заведением' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Чаты' }).click()
  await expect(page.getByRole('heading', { name: 'Чаты' })).toBeVisible()
  await expect(page.getByText('Здесь все ваши чаты с заведениями: вопросы, брони и другие переписки. Проблемы и жалобы находятся в разделе Помощь.')).toBeVisible()

  await page.getByRole('button', { name: 'Помощь' }).click()
  await expect(page.getByRole('heading', { name: 'Мои обращения' })).toBeVisible()
  const existingTicket = page.locator('.venue-message-thread-card').filter({ hasText: 'Нужна помощь по заказу' })
  await expect(existingTicket).toBeVisible()
  await expect(page.locator('.venue-messages-detail')).toHaveText('')

  await existingTicket.getByRole('button', { name: 'Открыть' }).click()
  await expect(page.locator('.venue-messages-detail').getByText('Нужна помощь по заказу')).toBeVisible()

  await page.getByPlaceholder('Опишите проблему. Для срочного вопроса по столу используйте вызов персонала.').fill('Не открывается заказ')
  await page.getByRole('button', { name: 'Создать обращение' }).click()
  await expect(page.locator('.venue-messages-detail').getByText('Не открывается заказ')).toBeVisible()
  expect(threads[0].tableSessionId).toBe(77)
})

test('venue manager support queue is list-first and transfers ticket with clear copy', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  let thread: SupportThreadFixture = {
    threadId: 5200,
    venueId: 1,
    venueName: 'Микс',
    guestDisplayName: 'Алексей',
    threadType: 'SUPPORT_TICKET',
    assigneeScope: 'VENUE',
    category: 'ORDER_SERVICE',
    contextLabel: 'Стол №4',
    status: 'NEW',
    statusLabel: 'Новый',
    tableId: 10,
    tableSessionId: 77,
    tableLabel: 'Стол №4',
    title: 'Обслуживание',
    lastMessagePreview: 'Нужна помощь по заказу',
    lastMessageAt: '2030-01-10T18:01:00Z',
    unreadCount: 1,
    createdAt: '2030-01-10T18:00:00Z',
    updatedAt: '2030-01-10T18:01:00Z'
  }
  const messages: SupportMessageFixture[] = [
    {
      messageId: 7200,
      threadId: thread.threadId,
      authorRole: 'GUEST',
      source: 'GUEST_MINIAPP',
      text: 'Нужна помощь по заказу',
      createdAt: '2030-01-10T18:01:00Z'
    }
  ]

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
            role: 'MANAGER',
            permissions: ['SUPPORT_VIEW', 'SUPPORT_MANAGE']
          }
        ]
      })
    )
  })
  await page.route('**/api/venue/1/support/threads**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const threadMatch = url.pathname.match(/\/api\/venue\/1\/support\/threads\/(\d+)(?:\/(escalate|messages))?$/)
    if (!threadMatch && request.method() === 'GET') {
      const threadTypes = url.searchParams.get('threadTypes') ?? url.searchParams.get('threadType') ?? ''
      const items = threadTypes.includes('SUPPORT_TICKET') ? [thread] : []
      await route.fulfill(jsonResponse({ items }))
      return
    }
    if (!threadMatch) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    const action = threadMatch[2]
    if (action === 'escalate' && request.method() === 'POST') {
      thread = { ...thread, assigneeScope: 'PLATFORM', status: 'IN_PROGRESS', statusLabel: 'В работе' }
      await route.fulfill(jsonResponse({ thread, messages }))
      return
    }
    if (request.method() === 'GET') {
      await route.fulfill(jsonResponse({ thread, messages }))
      return
    }
    await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Обращения', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Обращения' })).toBeVisible()
  await expect(page.locator('.venue-message-thread-card').filter({ hasText: 'Нужна помощь по заказу' })).toBeVisible()
  await expect(page.locator('.venue-messages-detail')).toHaveText('')

  await page.getByRole('button', { name: 'Открыть' }).click()
  await expect(page.locator('.venue-messages-detail').getByText('Нужна помощь по заказу')).toBeVisible()
  await page.getByRole('button', { name: 'Передать платформе' }).click()
  await expect(page.getByRole('heading', { name: 'Передать обращение платформе?' })).toBeVisible()
  await expect(
    page.getByText('Используйте это, если проблема связана с Mini App, ботом, QR, оплатой, правами доступа или технической ошибкой. Владелец платформы увидит обращение и сможет ответить гостю.')
  ).toBeVisible()
  await page.locator('.error-card').getByRole('button', { name: 'Передать платформе' }).click()
  await expect(page.locator('.venue-messages-detail').getByText('Обращение передано платформе. Ответы от заведения отключены.')).toBeVisible()
})

test('platform owner finds transferred support tickets and can reply and close', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  let transferred: SupportThreadFixture = {
    threadId: 6200,
    venueId: 1,
    venueName: 'Микс',
    guestDisplayName: 'Алексей',
    threadType: 'SUPPORT_TICKET',
    assigneeScope: 'PLATFORM',
    category: 'MINIAPP_TECHNICAL',
    contextLabel: 'Техническая проблема',
    status: 'IN_PROGRESS',
    statusLabel: 'В работе',
    tableLabel: 'Стол №4',
    title: 'Техническая проблема',
    lastMessagePreview: 'QR не открывается',
    lastMessageAt: '2030-01-10T18:01:00Z',
    unreadCount: 0,
    createdAt: '2030-01-10T18:00:00Z',
    updatedAt: '2030-01-10T18:01:00Z'
  }
  const venueOwned: SupportThreadFixture = {
    ...transferred,
    threadId: 6201,
    assigneeScope: 'VENUE',
    title: 'Вопрос по сервису',
    contextLabel: 'Стол №5',
    lastMessagePreview: 'Нужна помощь'
  }
  let messages: SupportMessageFixture[] = [
    {
      messageId: 8200,
      threadId: transferred.threadId,
      authorRole: 'GUEST',
      source: 'GUEST_MINIAPP',
      text: 'QR не открывается',
      createdAt: '2030-01-10T18:01:00Z'
    }
  ]

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-platform-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })
  await page.route('**/api/platform/me', async (route) => {
    await route.fulfill(jsonResponse({ ownerUserId: 123456789 }))
  })
  await page.route('**/api/platform/venues?**', async (route) => {
    await route.fulfill(jsonResponse({ venues: [] }))
  })
  await page.route('**/api/platform/support/threads**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const threadMatch = url.pathname.match(/\/api\/platform\/support\/threads\/(\d+)(?:\/(messages|status))?$/)
    if (!threadMatch && request.method() === 'GET') {
      let items = [transferred, venueOwned]
      if (url.searchParams.get('assigneeScope') === 'PLATFORM') {
        items = items.filter((thread) => thread.assigneeScope === 'PLATFORM')
      }
      await route.fulfill(jsonResponse({ items }))
      return
    }
    if (!threadMatch) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    const threadId = Number(threadMatch[1])
    const action = threadMatch[2]
    if (threadId !== transferred.threadId) {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    if (action === 'messages' && request.method() === 'POST') {
      const body = (await request.postDataJSON()) as { message?: string | null }
      const message: SupportMessageFixture = {
        messageId: 8201,
        threadId,
        authorRole: 'PLATFORM',
        source: 'PLATFORM_MINIAPP',
        text: body.message ?? '',
        createdAt: '2030-01-10T18:05:00Z'
      }
      messages = [...messages, message]
      transferred = { ...transferred, lastMessagePreview: message.text, updatedAt: message.createdAt, lastMessageAt: message.createdAt }
      await route.fulfill(jsonResponse({ thread: transferred, message, queued: true }))
      return
    }
    if (action === 'status' && request.method() === 'POST') {
      transferred = { ...transferred, status: 'CLOSED', statusLabel: 'Закрыто' }
      await route.fulfill(jsonResponse({ thread: transferred, messages }))
      return
    }
    if (request.method() === 'GET') {
      await route.fulfill(jsonResponse({ thread: transferred, messages }))
      return
    }
    await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
  })

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Обращения' }).click()
  await expect(page.getByRole('heading', { name: 'Обращения' })).toBeVisible()
  await page.getByRole('button', { name: 'Переданные платформе' }).click()
  await expect(page.locator('.venue-message-thread-card').filter({ hasText: 'QR не открывается' })).toBeVisible()
  await expect(page.locator('.venue-message-thread-card').filter({ hasText: 'Нужна помощь' })).toHaveCount(0)

  await page.getByRole('button', { name: 'Открыть' }).click()
  await page.getByPlaceholder('Ответ гостю').fill('Проверяем QR.')
  await page.getByRole('button', { name: 'Ответить' }).click()
  await expect(page.locator('.venue-messages-detail').getByText('Проверяем QR.')).toBeVisible()
  await page.getByRole('button', { name: 'Закрыть' }).click()
  await expect(page.locator('.venue-messages-detail').getByText(/Закрыто/)).toBeVisible()
})

test('venue manager sees read-only statistics and switches period', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStatsApi(page, { role: 'MANAGER' })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Статистика', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Статистика', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Статистика' })).toBeVisible()
  await expect(page.locator('.venue-stats-metric').filter({ hasText: 'Заказы' }).getByText('4')).toBeVisible()
  await expect(page.locator('.venue-stats-metric').filter({ hasText: 'Выручка' })).toContainText(/5\s*000/)
  await expect(page.getByText('Кальян')).toBeVisible()

  await page.getByRole('button', { name: '7 дней' }).click()

  await expect(page.locator('.venue-stats-metric').filter({ hasText: 'Заказы' }).getByText('8')).toBeVisible()
  expect(api.getPeriods()).toEqual(['today', '7d'])
})

test('platform billing cockpit shows invoices and uses explicit checkout and mark paid actions', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockPlatformBillingApi(page)

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('heading', { name: 'Заведения' })).toBeVisible()
  await page.getByRole('button', { name: 'Открыть' }).click()
  await expect(page.getByRole('heading', { name: 'Счета и оплата' })).toBeVisible()
  await expect(page.getByLabel('Пробный период до')).toHaveValue('2026-06-30')
  await expect(page.getByLabel('Платный период с')).toHaveValue('2026-07-01')
  await expect(page.getByText('После даты начала платного периода можно создать счёт за текущий период.')).toBeVisible()
  await expect(page.getByLabel('Базовая цена, ₽/мес')).toHaveValue('1500')
  await expect(page.getByLabel('Индивидуальная цена для этой кальянной, ₽/мес')).toHaveValue('')
  await expect(page.getByText('Расширенные настройки: будущие изменения цены')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Расписание цен' })).toBeHidden()
  await expect(page.getByText(/копейки/)).toHaveCount(0)
  await expect(page.getByText('Статус подписки: Просрочена')).toBeVisible()
  await expect(page.getByText(/Цена:.*₽/)).toBeVisible()
  await expect(page.getByText('Пробный период до: 30.06.2026')).toBeVisible()
  await expect(page.getByText('Платный период с: 01.07.2026')).toBeVisible()
  await expect(page.getByText('Счёт: #77 · Открыт')).toBeVisible()
  await expect(page.getByText('Счёт #77 · Открыт')).toBeVisible()
  await expect(page.getByText(/Период: 01\.07\.2026 — 31\.07\.2026 включительно/)).toBeVisible()
  expect(api.getBillingGetCalls()).toBe(1)
  expect(api.getCheckoutPostCalls()).toBe(0)

  await page.getByLabel('Базовая цена, ₽/мес').fill('3000')
  await page.getByLabel('Индивидуальная цена для этой кальянной, ₽/мес').fill('3500')
  await page.getByRole('button', { name: 'Сохранить настройки' }).click()
  await expect.poll(() => api.getLastSubscriptionUpdate()).toMatchObject({
    basePriceMinor: 300000,
    priceOverrideMinor: 350000
  })
  await expect(page.getByText(/Цена:.*3\s*500/)).toBeVisible()
  expect(api.getBillingGetCalls()).toBe(2)

  await page.getByRole('button', { name: 'Создать счёт/ссылку' }).click()
  await expect(page.getByText('Что сделать дальше: Счёт создан. Можно открыть внешнюю ссылку оплаты.')).toBeVisible()
  expect(api.getCheckoutPostCalls()).toBe(1)

  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: 'Отметить оплачено' }).click()
  await expect(page.getByText('Счёт #77 · Оплачен')).toBeVisible()
  await expect(page.getByText('Оплачено до 31.07.2026 включительно', { exact: true })).toBeVisible()
  await expect(page.getByText('Следующая оплата с 01.08.2026', { exact: true })).toBeVisible()
  await expect(page.getByText('Следующий период: 01.08.2026 — 31.08.2026')).toBeVisible()
  await expect(page.getByText('Что сделать дальше: Оплата учтена. Следующая оплата с 01.08.2026.')).toBeVisible()
  expect(api.getMarkPaidCalls()).toBe(1)

  await page.getByLabel('Бесплатные дни').fill('3')
  await page.getByLabel('Причина').fill('Сбой сервиса')
  page.once('dialog', (dialog) => dialog.accept())
  await page.getByRole('button', { name: 'Добавить бесплатные дни' }).click()
  await expect(page.getByText('Оплачено до 03.08.2026 включительно', { exact: true })).toBeVisible()
  await expect(page.getByText('Следующая оплата с 04.08.2026', { exact: true })).toBeVisible()
  await expect(page.getByText('Следующий период: 04.08.2026 — 03.09.2026')).toBeVisible()
  await expect(page.getByText('Бесплатные дни: 3')).toBeVisible()
  expect(api.getCourtesyPostCalls()).toBe(1)

  await page.getByRole('button', { name: 'Создать счёт за следующий период' }).click()
  await expect(page.getByText('Счёт: #78 · Открыт')).toBeVisible()
  await expect(page.getByText(/Период: 04\.08\.2026 — 03\.09\.2026 включительно/)).toBeVisible()
  expect(api.getCheckoutPostCalls()).toBe(2)
})

test('platform billing cockpit explains missing setup before checkout', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockPlatformBillingApi(page, {
    overview: {
      priceMinor: null,
      paymentAvailable: false,
      checkoutEnsureAvailable: false,
      unavailableReason: 'missing_price',
      checkoutUrl: 'fake://invoice/77',
      invoices: [{ ...buildBillingOverview().invoices[0], checkoutUrl: 'fake://invoice/77' }]
    }
  })

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Открыть' }).click()
  await expect(
    page.getByText('Что сделать дальше: Цена не задана. Укажите цену в блоке «Подписка и цены» и сохраните настройки.')
  ).toBeVisible()
  await expect(page.getByRole('button', { name: 'Создать счёт/ссылку' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Открыть оплату' })).toBeDisabled()
  await expect(page.getByText('fake://')).toHaveCount(0)
})

test('platform billing cockpit explains missing paid period and manual provider state', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockPlatformBillingApi(page, {
    overview: {
      paidStartAt: null,
      settingsPaidStartDate: null,
      paymentAvailable: false,
      checkoutEnsureAvailable: false,
      unavailableReason: 'missing_billing_period',
      checkoutUrl: null
    }
  })

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Открыть' }).click()
  await expect(
    page.getByText('Что сделать дальше: Платный период не задан. Укажите дату начала платного периода.')
  ).toBeVisible()
  await expect(page.getByRole('button', { name: 'Создать счёт/ссылку' })).toBeDisabled()
})

test('platform billing cockpit supports manual invoice without exposing fake links', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockPlatformBillingApi(page, {
    manualOnly: true,
    overview: {
      paymentAvailable: false,
      checkoutEnsureAvailable: true,
      unavailableReason: 'fake_provider_manual_only',
      checkoutUrl: 'fake://invoice/77',
      invoices: [{ ...buildBillingOverview().invoices[0], checkoutUrl: 'fake://invoice/77' }]
    }
  })

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Открыть' }).click()
  await expect(page.getByText('Счёт: #77 · Открыт')).toBeVisible()
  await expect(
    page.getByText('Онлайн-оплата: недоступна. Онлайн-оплата не подключена. Можно вести оплату вручную.')
  ).toBeVisible()
  await expect(page.getByText('fake://')).toHaveCount(0)
  await page.getByRole('button', { name: 'Создать счёт/ссылку' }).click()
  await expect(page.getByText('Что сделать дальше: Счёт создан. Онлайн-оплата не подключена. Можно вести оплату вручную.')).toBeVisible()
  expect(api.getCheckoutPostCalls()).toBe(1)
})

test('platform billing cockpit avoids stale trial contradiction when paid period is configured', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockPlatformBillingApi(page, {
    overview: {
      subscriptionStatus: 'trial',
      trialEndAt: '2026-06-30T00:00:00Z',
      settingsTrialEndDate: '2026-06-30',
      paymentAvailable: false,
      unavailableReason: 'external_checkout_unavailable'
    }
  })

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Открыть' }).click()
  await expect(page.getByText('Статус подписки: Пробный период')).toBeVisible()
  await expect(page.getByText('Состояние: Платный период настроен, счёт открыт')).toBeVisible()
  await expect(page.getByText('Пробный период закончился. Настройте платный период или оплату.')).toHaveCount(0)
})

test('venue owner subscription screen separates refresh from checkout ensure', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueBillingApi(page, { role: 'OWNER', permissions: ['ORDER_QUEUE_VIEW'] })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Подписка', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Подписка', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Подписка' })).toBeVisible()
  await expect(page.getByText('Статус: Активна')).toBeVisible()
  await expect(page.getByText(/Цена:.*₽/)).toBeVisible()
  await expect(page.getByText('Пробный период до: 30.06.2026')).toBeVisible()
  await expect(page.getByText('Платный период с: 01.07.2026')).toBeVisible()
  await expect(page.getByText('Счёт #77 · Открыт')).toBeVisible()
  await expect(page.getByText(/Период: 01\.07\.2026 — 31\.07\.2026 включительно/)).toBeVisible()
  expect(api.getSubscriptionGetCalls()).toBe(1)
  expect(api.getCheckoutPostCalls()).toBe(0)

  await page.getByRole('button', { name: 'Проверить оплату' }).click()
  expect(api.getSubscriptionGetCalls()).toBe(2)
  expect(api.getCheckoutPostCalls()).toBe(0)

  await page.getByRole('button', { name: 'Подготовить оплату' }).click()
  await expect(page.getByText('Можно перейти к оплате картой.')).toBeVisible()
  expect(api.getCheckoutPostCalls()).toBe(1)
})

test('venue owner subscription screen uses human manual payment copy and hides fake links', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueBillingApi(page, {
    role: 'OWNER',
    permissions: ['ORDER_QUEUE_VIEW'],
    overview: {
      paymentAvailable: false,
      checkoutEnsureAvailable: true,
      unavailableReason: 'fake_provider_manual_only',
      checkoutUrl: 'fake://invoice/77',
      invoices: [{ ...buildBillingOverview().invoices[0], checkoutUrl: 'fake://invoice/77' }]
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Подписка', exact: true }).click()
  await expect(page.getByText('Онлайн-оплата не подключена. Оплату ведёт платформа вручную.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Оплатить картой' })).toBeDisabled()
  await expect(page.getByText('fake://')).toHaveCount(0)
})

test('venue staff does not see subscription payment controls', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueBillingApi(page, { role: 'STAFF', permissions: ['ORDER_QUEUE_VIEW'] })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Подписка', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    window.location.hash = '#/subscription'
  })
  await expect(page.getByText('У вас нет доступа к этому разделу.')).toBeVisible()
})

test('venue owner sees statistics section', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueStatsApi(page, { role: 'OWNER' })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Статистика', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Статистика', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Статистика' })).toBeVisible()
  await expect(page.locator('.venue-stats-metric').filter({ hasText: 'Средний чек' })).toContainText(/1\s*250/)
})

test('venue staff does not see statistics section', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueStatsApi(page, { role: 'STAFF', permissions: ['ORDER_QUEUE_VIEW'] })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Статистика', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    window.location.hash = '#/stats'
  })
  await expect(page.getByRole('heading', { name: 'Недостаточно прав' })).toBeVisible()
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
  await expect(page.getByText('Счёт: Личный счёт')).toBeVisible()
  await expect(page.getByText('Статус: Отправлен')).toBeVisible()
  await expect(page.getByText('Показаны только позиции этого счёта. Исключённые и чужие позиции не входят в сумму.')).toHaveCount(0)
  await expect(page.getByText('Показан только этот счёт.')).toHaveCount(0)
  await expect(page.getByText('Double Apple')).toBeVisible()
  const plainItemRow = page.locator('.order-item').filter({ hasText: 'Double Apple' })
  await expect(plainItemRow).toContainText(/1\s*500,00\s*₽/)
  await expect(plainItemRow).not.toContainText('к оплате')
  await expect(page.getByText('Сумма до скидок')).toHaveCount(0)
  await expect(page.getByText('К оплате', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '🚪 Завершить визит' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Заказ №900' })).toHaveCount(0)
  await expect(page.getByText('Заявка №333')).toHaveCount(0)
  await expect(page.getByText('Общий счёт #88')).toHaveCount(0)
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

test('guest bill request payment method posts json from order screen and shows duplicate copy', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, { restoreContext: buildRestoreContext() })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Мой заказ', exact: true }).click()

  await expect(page.getByRole('heading', { name: 'Заказ №123' })).toBeVisible()
  await page.getByRole('button', { name: 'Попросить счёт' }).click()
  await expect(page.getByRole('heading', { name: 'Как будете оплачивать?' })).toBeVisible()
  await expect
    .poll(async () =>
      page.evaluate(() => {
        const chooser = document.querySelector('.order-bill-request')
        const composition = document.querySelector('.order-batches')
        if (!chooser || !composition) return false
        return chooser.getBoundingClientRect().bottom <= composition.getBoundingClientRect().top
      })
    )
    .toBe(true)
  await page.getByRole('button', { name: 'Картой на месте' }).click()

  await expect(page.getByText('Персонал получил запрос на счёт.')).toBeVisible()
  await expect.poll(() => api.getBillRequestRequests()).toHaveLength(1)
  const firstRequest = api.getBillRequestRequests()[0]
  expect(firstRequest.url).toContain('/api/guest/order/bill-request')
  expect(firstRequest.method).toBe('POST')
  expect(firstRequest.contentType).toContain('application/json')
  expect(firstRequest.authorization).toBe('Bearer e2e-session-token')
  expect(firstRequest.body).toEqual({
    tableToken,
    tableSessionId: 77,
    tabId: 88,
    paymentMethod: 'CARD'
  })

  await page.getByRole('button', { name: 'Попросить счёт' }).click()
  await page.getByRole('button', { name: 'Наличными' }).click()

  await expect(page.getByText('Запрос на счёт уже отправлен. Персонал скоро подойдёт.')).toBeVisible()
  await expect.poll(() => api.getBillRequestRequests()).toHaveLength(2)
  expect(api.getBillRequestRequests()[1].body.paymentMethod).toBe('CASH')
})

test('guest bill with discount shows useful breakdown and human discount copy', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, {
    restoreContext: buildRestoreContext(),
    activeOrder: {
      batchStatus: 'ACCEPTED',
      itemManualDiscountMinor: 25000
    }
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Мой заказ', exact: true }).click()

  await expect(page.getByRole('heading', { name: 'Заказ №123' })).toBeVisible()
  await expect(page.getByText('Статус: Принят')).toBeVisible()
  const discountedItemRow = page.locator('.order-item').filter({ hasText: 'Double Apple' })
  await expect(discountedItemRow).toContainText(/1\s*500,00\s*₽/)
  await expect(discountedItemRow).toContainText(/скидка заведения −250,00\s*₽/)
  await expect(discountedItemRow).toContainText(/к оплате 1\s*250,00\s*₽/)
  await expect(page.getByText('Сумма до скидок')).toBeVisible()
  await expect(page.getByText('Скидка заведения', { exact: true })).toBeVisible()
  await expect(page.getByText('К оплате', { exact: true })).toBeVisible()
  await expect(page.getByText(/Ручн/)).toHaveCount(0)
})

test('guest order status updates to delivered and closed copy stays self-service', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    restoreContext: buildRestoreContext(),
    activeOrder: {
      batchStatus: 'ACCEPTED'
    }
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Мой заказ', exact: true }).click()

  await expect(page.getByText('Статус: Принят')).toBeVisible()

  api.setActiveOrder({ batchStatus: 'DELIVERED' })
  await page.getByRole('button', { name: '🔄 Обновить' }).click()
  await expect(page.getByText('Статус: Доставлен')).toBeVisible()

  api.setActiveOrder({ status: 'CLOSED', batchStatus: 'DELIVERED' })
  await page.getByRole('button', { name: '🔄 Обновить' }).click()
  await expect(page.getByText('Статус: Счёт закрыт')).toBeVisible()
  await expect(page.getByText('Счёт закрыт. Состав и итог доступны только для просмотра.')).toBeVisible()
  await expect(page.getByText(/обратитесь к персоналу/)).toHaveCount(0)
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
