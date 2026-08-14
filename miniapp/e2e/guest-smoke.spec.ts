import { expect, type Locator, type Page, type Route, test } from '@playwright/test'

const sessionExpiresAt = Math.floor(Date.now() / 1000) + 3600
const giftDecisionExpiresAtEpochSeconds = 4_102_444_800
const tableToken = 'TABLE-SMOKE-1'
const mockInitData = 'query_id=e2e-smoke&user=%7B%22id%22%3A123456789%7D&hash=test'
const otherMockInitData = 'query_id=e2e-smoke-other&user=%7B%22id%22%3A987654321%7D&hash=test'
const promotionVenueTimezone = 'Europe/Moscow'
const promotionVenueUtcOffset = '+03:00'
const unsavedPreviewMessage =
  'Есть несохранённые изменения. Сначала сохраните их, затем откройте предпросмотр.'
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
  details?: unknown
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
  giftReward?: {
    itemId: number
    name: string
    priceMinor: number
    currency: string
  }
}

type GuestVisitHistoryFixture = {
  items: Array<Record<string, unknown>>
  details: Record<number, Record<string, unknown>>
  repeatPlans?: Record<number, Record<number, Record<string, unknown>>>
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

type VenueStaffModuleSettingsFixture = {
  teamScheduleModuleEnabled: boolean
  guestTeamVisible: boolean
  todayStaffSource: 'MANUAL' | 'SCHEDULE'
  updatedAt: string
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

type GuestCatalogVenueFixture = {
  id: number
  name: string
  city?: string | null
  address?: string | null
  countryCode?: string | null
  formattedAddress?: string | null
  displayAddress?: string | null
  routeUrl?: string | null
  cardDescription?: string | null
  todaySchedule?: Record<string, unknown> | null
}

type CatalogRequestCapture = {
  url: string
  q: string | null
  city: string | null
  authorization: string | undefined
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

type VenueMenuShiftCheckRequestFixture = {
  items: Array<{
    itemId: number
    expectedIsAvailable: boolean
    desiredIsAvailable: boolean
  }>
  options: Array<{
    optionId: number
    itemId: number
    expectedIsAvailable: boolean
    desiredIsAvailable: boolean
  }>
}

type VenueMenuShiftCheckAccessFixture = {
  venueId: number
  venueName: string
  venueCity: string
  venueStatus: string
  role: 'OWNER' | 'MANAGER' | 'STAFF'
  permissions: string[]
  categories: VenueMenuCategoryFixture[]
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

type VenueFeedbackResponse = {
  venueId: number
  filter: 'all' | 'low'
  summary: {
    count: number
    averageRating: number | null
    lowCount: number
  }
  items: Array<Record<string, unknown>>
}

type VenuePromotionFixture = {
  id: number
  title: string
  description: string
  terms?: string | null
  startsAt: string
  endsAt: string
  status: 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'ARCHIVED'
  templateType?: 'TEXT_ONLY' | 'HAPPY_HOURS_PERCENT' | 'GIFT_WITH_ITEM'
  rule?: VenuePromotionRuleFixture | null
}

type GuestVenuePromotionFixture = Omit<VenuePromotionFixture, 'status'>

type VenueGuestPreviewScheduleDayFixture = {
  weekday: number
  opensAt: string
  closesAt: string
  isClosed: boolean
}

type VenueGuestPreviewDateExceptionFixture = {
  serviceDate: string
  opensAt: string
  closesAt: string
  isClosed: boolean
  guestNote?: string | null
}

type VenueGuestPreviewStaffFixture = {
  id: number
  displayName: string
  roleLabel?: string | null
  subtype: string
  photoRef?: string | null
  bio?: string | null
  tags?: string[]
  shiftDate: string
  startsAt?: string | null
  endsAt?: string | null
  shiftStatus: string
}

type VenueGuestPreviewVenueFixture = {
  id: number
  name: string
  city?: string | null
  address?: string | null
  countryCode?: string | null
  formattedAddress?: string | null
  displayAddress?: string | null
  latitude?: number | null
  longitude?: number | null
  routeUrl?: string | null
  guestContact?: string | null
  cardDescription?: string | null
  todaySchedule?: {
    date: string
    opensAt?: string | null
    closesAt?: string | null
    isConfigured?: boolean
    isClosed: boolean
    isOpenNow: boolean
    statusLabel: string
    timeLabel?: string | null
  } | null
  weeklyHours: VenueGuestPreviewScheduleDayFixture[]
  dateExceptions: VenueGuestPreviewDateExceptionFixture[]
  todayStaff: VenueGuestPreviewStaffFixture[]
  timezone?: string | null
  promotions: GuestVenuePromotionFixture[]
  status: string
  isFavorite: boolean
}

type VenueGuestPreviewInfoSectionFixture = {
  id: number
  type: string
  title: string
  displayTitle: string
  text?: string | null
  mediaCount?: number | null
  media?: Array<{
    id: number
    mediaType: string
    sortOrder: number
    url?: string | null
  }> | null
}

type VenueGuestPreviewFixture = {
  mode: 'PUBLISHED_PUBLIC' | 'PRIVATE_DRAFT'
  venueAvailabilityLabel: string | null
  venue: VenueGuestPreviewVenueFixture
  infoSections: VenueGuestPreviewInfoSectionFixture[]
  source: 'SAVED_STATE'
  previewError?: ApiErrorFixture | null
}

type VenueGuestPreviewAccessFixture = {
  venueId: number
  venueName: string
  venueCity: string | null
  venueStatus: string
  role: 'OWNER' | 'MANAGER' | 'STAFF'
  permissions: string[]
}

type VenuePromotionRuleFixture = {
  id: number
  version: number
  windows: Array<{ weekday: number; startLocal: string; endLocal: string }>
  target?: {
    type: 'MENU_CATEGORY' | 'MENU_ITEM'
    menuCategoryId?: number | null
    menuItemId?: number | null
    label?: string | null
  } | null
  discountPercent?: number | null
  reward?: {
    mode: 'FIXED_ITEM' | 'CHOICE_ITEMS'
    fixedItem?: VenuePromotionRewardItemFixture | null
    allowlist: VenuePromotionRewardItemFixture[]
  } | null
  readyForActivation: boolean
  validationIssues: string[]
}

type VenuePromotionMutationFixture = {
  title: string
  description: string
  terms?: string | null
  startsAt: string
  endsAt: string
  templateType: 'TEXT_ONLY' | 'HAPPY_HOURS_PERCENT' | 'GIFT_WITH_ITEM'
  rule?: {
    windows: Array<{ weekday: number; startLocal: string; endLocal: string }>
    target: {
      type: 'MENU_CATEGORY' | 'MENU_ITEM'
      menuCategoryId?: number | null
      menuItemId?: number | null
    }
    discountPercent?: number | null
    reward?: {
      mode: 'FIXED_ITEM' | 'CHOICE_ITEMS'
      fixedMenuItemId?: number | null
      allowlistMenuItemIds: number[]
    } | null
  } | null
}

type VenuePromotionLifecycleRequestFixture = {
  method: 'POST' | 'DELETE'
  path: string
  promotionId: number
  body?: { status: 'ACTIVE' | 'PAUSED' }
}

type VenuePromotionStaleFixture = {
  promotionId: number
  authoritativeStatus: VenuePromotionFixture['status']
}

type VenuePromotionRewardItemFixture = {
  menuItemId: number
  name: string
  priceMinor?: number | null
  currency?: string | null
  isAvailable?: boolean
  requiresOptionSelection?: boolean
}

type GiftDecisionFixture = {
  action: 'ACCEPT_FIXED' | 'SELECT_ITEM' | 'SKIP'
  selectedMenuItemId?: number | null
  decisionScopeToken: string
}

type GiftRewardItemFixture = {
  menuItemId: number
  name: string
  originalUnitPriceMinor: number
  currency: string
}

type GiftOfferFixture = {
  status:
    | 'NO_GIFT'
    | 'FIXED_GIFT_AVAILABLE'
    | 'GIFT_CHOICE_REQUIRED'
    | 'GIFT_UNAVAILABLE'
    | 'GIFT_SKIPPED'
    | 'GIFT_SELECTED'
  promotionId?: number | null
  promotionTitle?: string | null
  ruleId?: number | null
  ruleVersion?: number | null
  triggerLineId?: number | null
  triggerMenuItemId?: number | null
  triggerItemName?: string | null
  fixedRewardItem?: GiftRewardItemFixture | null
  selectableRewardItems?: GiftRewardItemFixture[]
  selectedRewardItem?: GiftRewardItemFixture | null
  unavailableReason?: string | null
}

type CartPreviewFixture = {
  grossTotalMinor: number
  promoDiscountTotalMinor: number
  loyaltyDiscountTotalMinor: number
  finalPayableTotalMinor: number
  currency: string
  discounts: Array<Record<string, unknown>>
  items: Array<Record<string, unknown>>
  pricingFingerprint: string
  cartFingerprint: string
  decisionScopeToken?: string | null
  decisionScopeExpiresAtEpochSeconds?: number | null
  giftDecisionStale?: boolean
  giftDecisionMessage?: string | null
  giftOffer?: GiftOfferFixture | null
}

type AddBatchResponseFixture = {
  submitted?: boolean
  orderId?: number | null
  batchId?: number | null
  pricing: CartPreviewFixture
  recalculated: boolean
}

type AddBatchRouteResponseFixture = AddBatchResponseFixture | { error: ApiErrorFixture }

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
  cartLineRef?: string
  itemId: number
  qty: number
  selectedOptionId?: number | null
  preferenceNote?: string | null
}

type CartPreviewRequestFixture = {
  tableToken: string
  tableSessionId: number
  tabId: number
  giftDecision?: GiftDecisionFixture | null
  items: AddBatchItemPayload[]
  comment?: string | null
}

type CartPreviewResponseFixture =
  | { preview: CartPreviewFixture }
  | { error: ApiErrorFixture }

type GuestTableScopeFixture = {
  venueId: number
  tableSessionId: number
  tabId: number
  ownerUserId: number
}

type GuestTabFixture = {
  id: number
  tableSessionId: number
  type: 'PERSONAL' | 'SHARED'
  ownerUserId: number
  status: 'ACTIVE' | 'CLOSED'
}

function buildScopedFixedGiftPreview(request: CartPreviewRequestFixture): CartPreviewFixture {
  const triggerQty = request.items.find((item) => item.itemId === 200)?.qty ?? 0
  const triggerGross = triggerQty * 150000
  const normalizedComment = request.comment?.trim() ?? ''
  const cartFingerprint = [
    'scoped-fixed-cart',
    request.tableSessionId,
    request.tabId,
    triggerQty,
    normalizedComment
  ].join(':')
  const decisionScopeToken = `scoped-fixed-token:${cartFingerprint}`
  const selected = request.giftDecision?.action === 'ACCEPT_FIXED'
  const skipped = request.giftDecision?.action === 'SKIP'
  const reward: GiftRewardItemFixture = {
    menuItemId: 210,
    name: 'Чай',
    originalUnitPriceMinor: 45000,
    currency: 'RUB'
  }
  return {
    grossTotalMinor: triggerGross + (selected ? reward.originalUnitPriceMinor : 0),
    promoDiscountTotalMinor: selected ? reward.originalUnitPriceMinor : 0,
    loyaltyDiscountTotalMinor: 0,
    finalPayableTotalMinor: triggerGross,
    currency: 'RUB',
    discounts: [],
    items: [
      {
        itemId: 200,
        name: 'Double Apple',
        qty: triggerQty,
        priceMinor: 150000,
        currency: 'RUB',
        lineGrossMinor: triggerGross,
        discountMinor: 0,
        linePayableMinor: triggerGross,
        isPromotionReward: false
      }
    ],
    pricingFingerprint: `scoped-fixed-pricing:${selected ? 'selected' : skipped ? 'skipped' : 'offer'}`,
    cartFingerprint,
    decisionScopeToken,
    decisionScopeExpiresAtEpochSeconds: giftDecisionExpiresAtEpochSeconds,
    giftOffer: {
      status: selected ? 'GIFT_SELECTED' : skipped ? 'GIFT_SKIPPED' : 'FIXED_GIFT_AVAILABLE',
      promotionId: 705,
      promotionTitle: 'Чай к кальяну',
      ruleId: 805,
      ruleVersion: 1,
      triggerMenuItemId: 200,
      triggerItemName: 'Double Apple',
      fixedRewardItem: reward,
      selectableRewardItems: [],
      selectedRewardItem: selected ? reward : null
    }
  }
}

type AddBatchPayload = {
  tableToken: string
  tableSessionId: number
  tabId: number
  idempotencyKey?: string
  previewFingerprint?: string | null
  giftDecision?: GiftDecisionFixture | null
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

function buildGuestCatalogVenue(overrides: Partial<GuestCatalogVenueFixture> = {}): GuestCatalogVenueFixture {
  return {
    id: 1,
    name: 'Микс',
    city: 'Москва',
    address: 'Пилотная, 1',
    countryCode: 'RU',
    formattedAddress: null,
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
    },
    ...overrides
  }
}

function buildVenueGuestPreviewFixture(
  venueId = 1,
  overrides: Omit<Partial<VenueGuestPreviewFixture>, 'venue'> & {
    venue?: Partial<VenueGuestPreviewVenueFixture>
  } = {}
): VenueGuestPreviewFixture {
  const name = venueId === 1 ? 'Микс' : `Микс ${venueId}`
  const venueOverrides = overrides.venue ?? {}
  const { venue: _venue, ...fixtureOverrides } = overrides
  return {
    mode: 'PUBLISHED_PUBLIC',
    venueAvailabilityLabel: null,
    venue: {
      id: venueId,
      name,
      city: 'Москва',
      address: `Пилотная, ${venueId}`,
      countryCode: 'RU',
      formattedAddress: null,
      displayAddress: `Москва, Пилотная, ${venueId}`,
      latitude: null,
      longitude: null,
      routeUrl: buildTextRouteUrl(name, 'RU', 'Москва', `Пилотная, ${venueId}`),
      guestContact: '+7 900 100-20-30',
      cardDescription: 'Опубликованное описание лаунжа.',
      todaySchedule: {
        date: '2030-01-10',
        opensAt: '18:00',
        closesAt: '02:00',
        isConfigured: true,
        isClosed: false,
        isOpenNow: true,
        statusLabel: 'Открыто',
        timeLabel: '18:00–02:00'
      },
      weeklyHours: [
        { weekday: 1, opensAt: '18:00', closesAt: '02:00', isClosed: false },
        { weekday: 2, opensAt: '18:00', closesAt: '02:00', isClosed: false },
        { weekday: 3, opensAt: '18:00', closesAt: '02:00', isClosed: false },
        { weekday: 4, opensAt: '18:00', closesAt: '02:00', isClosed: false },
        { weekday: 5, opensAt: '18:00', closesAt: '03:00', isClosed: false },
        { weekday: 6, opensAt: '16:00', closesAt: '03:00', isClosed: false },
        { weekday: 7, opensAt: '00:00', closesAt: '00:00', isClosed: true }
      ],
      dateExceptions: [
        {
          serviceDate: '2030-01-12',
          opensAt: '00:00',
          closesAt: '00:00',
          isClosed: true,
          guestNote: 'Санитарный день'
        },
        {
          serviceDate: '2030-01-13',
          opensAt: '20:00',
          closesAt: '01:00',
          isClosed: false,
          guestNote: 'Специальный график'
        }
      ],
      todayStaff: [
        {
          id: 501,
          displayName: 'Анна',
          roleLabel: 'Мастер авторских миксов',
          subtype: 'hookah_master',
          photoRef: null,
          bio: 'Поможет подобрать крепость.',
          tags: ['цитрусовые', 'крепкие'],
          shiftDate: '2030-01-10',
          startsAt: '2030-01-10T15:00:00Z',
          endsAt: null,
          shiftStatus: 'active'
        }
      ],
      timezone: 'Europe/Moscow',
      promotions: [
        {
          id: 701,
          title: 'Чай в подарок',
          description: 'К каждому авторскому миксу.',
          terms: 'До закрытия заведения.',
          startsAt: '2030-01-01T00:00:00Z',
          endsAt: '2030-01-31T20:59:59Z',
          templateType: 'TEXT_ONLY'
        }
      ],
      status: 'PUBLISHED',
      isFavorite: false,
      ...venueOverrides
    },
    infoSections: [
      {
        id: 10,
        type: 'menu',
        title: 'Меню',
        displayTitle: '📖 Фото-меню',
        text: 'Ознакомительное меню в опубликованной карточке.',
        mediaCount: 1,
        media: [
          {
            id: 100,
            mediaType: 'image',
            sortOrder: 0,
            url: `/api/guest/venue/${venueId}/info-sections/10/media/100`
          }
        ]
      }
    ],
    source: 'SAVED_STATE',
    previewError: null,
    ...fixtureOverrides
  }
}

function buildVenuePrivatePreviewFixture(
  venueId = 1,
  overrides: Omit<Partial<VenueGuestPreviewFixture>, 'venue'> & {
    venue?: Partial<VenueGuestPreviewVenueFixture>
  } = {}
): VenueGuestPreviewFixture {
  const published = buildVenueGuestPreviewFixture(venueId)
  const venueOverrides = overrides.venue ?? {}
  const { venue: _venue, ...fixtureOverrides } = overrides
  return {
    mode: 'PRIVATE_DRAFT',
    venueAvailabilityLabel: 'Заведение ещё не опубликовано.',
    venue: {
      ...published.venue,
      cardDescription: 'Сохранённое описание черновика.',
      status: 'DRAFT',
      isFavorite: false,
      ...venueOverrides
    },
    infoSections: [
      {
        id: 10,
        type: 'menu',
        title: 'Меню',
        displayTitle: '📖 Фото-меню',
        text: 'Сохранённый публичный текст черновика.',
        mediaCount: 1,
        media: [
          {
            id: 100,
            mediaType: 'image',
            sortOrder: 0,
            url: `/api/venue/${venueId}/guest-preview/info-sections/10/media/100`
          }
        ]
      }
    ],
    source: 'SAVED_STATE',
    previewError: null,
    ...fixtureOverrides
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

function buildStaleRecoveryPreview(request: CartPreviewRequestFixture): CartPreviewFixture {
  const items = request.items.map((line) => {
    const unitPriceMinor = line.itemId === 200 ? 150000 : line.itemId === 211 ? 20000 : 30000
    return {
      itemId: line.itemId,
      name: line.itemId === 200 ? 'Кальян' : line.itemId === 211 ? 'Вода' : 'Чай',
      qty: line.qty,
      selectedOption:
        line.selectedOptionId == null
          ? null
          : {
              optionId: line.selectedOptionId,
              name: line.selectedOptionId === 302 ? 'Цитрус' : 'Ягодный',
              priceDeltaMinor: 0
            },
      preferenceNote: line.preferenceNote ?? null,
      priceMinor: unitPriceMinor,
      currency: 'RUB',
      lineGrossMinor: unitPriceMinor * line.qty,
      discountMinor: 0,
      linePayableMinor: unitPriceMinor * line.qty,
      isPromotionReward: false
    }
  })
  const grossTotalMinor = items.reduce((sum, item) => sum + item.lineGrossMinor, 0)
  return {
    grossTotalMinor,
    promoDiscountTotalMinor: 0,
    loyaltyDiscountTotalMinor: 0,
    finalPayableTotalMinor: grossTotalMinor,
    currency: 'RUB',
    discounts: [],
    items,
    pricingFingerprint: `stale-recovery:${JSON.stringify(request.items)}`,
    cartFingerprint: `stale-recovery:${request.tableSessionId}:${request.tabId}:${JSON.stringify(request.items)}`,
    giftOffer: { status: 'NO_GIFT' }
  }
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
    promotions?: Array<Omit<VenuePromotionFixture, 'status'>>
    visitHistory?: GuestVisitHistoryFixture
    initialFavoriteVenueIds?: number[]
    favoriteMutationDelayMs?: number
    favoriteMutationFailureOnce?: boolean
    isolateFavoriteUsers?: boolean
    venueAvailable?: boolean
    catalogVenues?: GuestCatalogVenueFixture[]
    cartPreview?: CartPreviewFixture
    cartPreviewResolver?: (request: CartPreviewRequestFixture) => CartPreviewFixture
    cartPreviewResponseResolver?: (
      request: CartPreviewRequestFixture
    ) => CartPreviewResponseFixture | Promise<CartPreviewResponseFixture>
    addBatchResponse?: AddBatchResponseFixture
    addBatchResponseResolver?: (
      request: AddBatchPayload
    ) => AddBatchRouteResponseFixture | Promise<AddBatchRouteResponseFixture>
    tableScope?: GuestTableScopeFixture
    tabs?: GuestTabFixture[]
  } = {}
) {
  let structuredMenuCalls = 0
  let restoreContext = options.restoreContext ?? null
  let extensionOptions = options.extensionOptions ?? buildShiftExtensionOptions()
  let extensionOptionsError = options.extensionOptionsError ?? null
  let menuCategories = options.menuCategories ?? buildDefaultGuestMenu()
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
  let promotions = options.promotions ?? []
  let cartPreview = options.cartPreview ?? null
  const visitHistory = options.visitHistory ?? { items: [], details: {} }
  const defaultFavoriteToken = options.isolateFavoriteUsers ? 'favorite-user-123456789' : 'e2e-session-token'
  const favoriteVenueIdsByToken = new Map<string, Set<number>>([
    [defaultFavoriteToken, new Set(options.initialFavoriteVenueIds ?? [])]
  ])
  let favoriteMutationFailureOnce = options.favoriteMutationFailureOnce === true
  let venueAvailable = options.venueAvailable !== false
  let catalogVenues = [...(options.catalogVenues ?? [buildGuestCatalogVenue()])]
  const catalogRequests: CatalogRequestCapture[] = []
  const catalogResponseAttempts: CatalogRequestCapture[] = []
  const catalogErrors: ApiErrorFixture[] = []
  const deferredCatalogResponses: Array<{
    filters: { q?: string | null; city?: string | null }
    promise: Promise<void>
    release: () => void
  }> = []
  const favoriteMutationRequests: Array<{ venueId: number; method: string }> = []
  const deferredFavoriteMutations: Array<{
    venueId: number
    method?: string
    promise: Promise<void>
    release: () => void
  }> = []
  let tableScope: GuestTableScopeFixture =
    options.tableScope ?? {
      venueId: 1,
      tableSessionId: 77,
      tabId: 88,
      ownerUserId: 123456789
    }
  let guestTabs = options.tabs ?? null
  let createExtensionRequestCalls = 0
  let nextBookingId = 9000
  let activeOrderServiceCharges: ServiceCharge[] = []
  const previewRequests: CartPreviewRequestFixture[] = []
  const addBatchRequests: AddBatchPayload[] = []
  const billRequestRequests: BillRequestCapture[] = []
  const tableSessionEndRequests: Array<{
    url: string
    method: string
    contentType: string | undefined
    body: { tableToken: string; tableSessionId: number }
  }> = []
  const repeatPlanRequests: Array<{
    visitId: number
    body: { tableSessionId: number; tabId: number; orderId?: number | null }
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
    const orderItems = lines.map(buildOrderItem)
    if (activeOrderOptions?.giftReward) {
      const reward = activeOrderOptions.giftReward
      orderItems.push({
        itemId: reward.itemId,
        qty: 1,
        name: reward.name,
        selectedOption: null,
        preferenceNote: null,
        priceMinor: reward.priceMinor,
        currency: reward.currency,
        lineGrossMinor: reward.priceMinor,
        manualDiscountMinor: 0,
        promoDiscountMinor: reward.priceMinor,
        discountMinor: reward.priceMinor,
        linePayableMinor: 0,
        isPromotionReward: true
      })
    }
    return orderItems
  }

  await page.route('**/api/auth/telegram', async (route) => {
    if (!options.isolateFavoriteUsers) {
      await route.fulfill(jsonResponse({ token: 'e2e-session-token', expiresAtEpochSeconds: sessionExpiresAt }))
      return
    }
    const body = (await route.request().postDataJSON()) as { initData?: string }
    const rawUser = new URLSearchParams(body.initData ?? '').get('user')
    const userId = rawUser ? Number((JSON.parse(rawUser) as { id?: number }).id) : 0
    const token = `favorite-user-${userId}`
    if (!favoriteVenueIdsByToken.has(token)) {
      favoriteVenueIdsByToken.set(token, new Set())
    }
    await route.fulfill(jsonResponse({ token, expiresAtEpochSeconds: sessionExpiresAt }))
  })

  const favoriteIdsForRequest = (request: { headers(): Record<string, string> }) => {
    const token = request.headers().authorization?.replace(/^Bearer\s+/i, '') ?? defaultFavoriteToken
    let ids = favoriteVenueIdsByToken.get(token)
    if (!ids) {
      ids = new Set()
      favoriteVenueIdsByToken.set(token, ids)
    }
    return ids
  }

  const deferNextCatalogResponse = (filters: { q?: string | null; city?: string | null } = {}) => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    deferredCatalogResponses.push({ filters, promise, release })
    return release
  }

  const deferNextFavoriteMutation = (venueId: number, method?: 'POST' | 'DELETE') => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    deferredFavoriteMutations.push({ venueId, method, promise, release })
    return release
  }

  const normalizedCatalogText = (value: string | null | undefined) => value?.trim().toLocaleLowerCase('ru-RU') ?? ''

  const filterCatalogVenues = (q: string | null, city: string | null) => {
    const normalizedQuery = normalizedCatalogText(q)
    const normalizedCity = normalizedCatalogText(city)
    return catalogVenues.filter((venue) => {
      const matchesQuery =
        !normalizedQuery ||
        [venue.name, venue.city, venue.address, venue.formattedAddress].some((value) =>
          normalizedCatalogText(value).includes(normalizedQuery)
        )
      const matchesCity = !normalizedCity || normalizedCatalogText(venue.city) === normalizedCity
      return matchesQuery && matchesCity
    })
  }

  await page.route('**/api/guest/catalog**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const capture: CatalogRequestCapture = {
      url: request.url(),
      q: url.searchParams.get('q'),
      city: url.searchParams.get('city'),
      authorization: request.headers().authorization
    }
    catalogRequests.push(capture)
    const favoriteIds = favoriteIdsForRequest(request)
    const response = catalogErrors.length
      ? (() => {
          const error = catalogErrors.shift()!
          return jsonResponse(
            {
              error: {
                code: error.code ?? 'INTERNAL_ERROR',
                message: error.message ?? 'Не удалось загрузить каталог.'
              }
            },
            error.status
          )
        })()
      : jsonResponse({
          venues: venueAvailable
            ? filterCatalogVenues(capture.q, capture.city).map((venue) => ({
                ...venue,
                isFavorite: favoriteIds.has(venue.id)
              }))
            : []
        })
    const deferredIndex = deferredCatalogResponses.findIndex(({ filters }) => {
      const qMatches = !Object.prototype.hasOwnProperty.call(filters, 'q') || (filters.q ?? null) === capture.q
      const cityMatches =
        !Object.prototype.hasOwnProperty.call(filters, 'city') || (filters.city ?? null) === capture.city
      return qMatches && cityMatches
    })
    const gate = deferredIndex >= 0 ? deferredCatalogResponses.splice(deferredIndex, 1)[0] : null
    await gate?.promise
    try {
      await route.fulfill(response)
    } catch {
      // A newer filter or disposed catalog screen intentionally aborts this request.
    } finally {
      catalogResponseAttempts.push(capture)
    }
  })

  await page.route('**/api/guest/venue/1', async (route) => {
    if (!venueAvailable) {
      await route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ error: { code: 'NOT_FOUND', message: 'Not found' } })
      })
      return
    }
    const favoriteIds = favoriteIdsForRequest(route.request())
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
          timezone: 'Europe/Moscow',
          promotions,
          status: 'PUBLISHED',
          isFavorite: favoriteIds.has(1)
        }
      })
    )
  })

  await page.route('**/api/guest/favorites/venues**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const favoriteIds = favoriteIdsForRequest(request)
    if (path === '/api/guest/favorites/venues' && request.method() === 'GET') {
      await route.fulfill(
        jsonResponse({
          venues: venueAvailable && favoriteIds.has(1)
            ? [{ venueId: 1, name: 'Микс', city: 'Москва', address: 'Пилотная, 1' }]
            : []
        })
      )
      return
    }
    const venueMatch = path.match(/^\/api\/guest\/favorites\/venues\/(\d+)$/)
    if (venueMatch && (request.method() === 'POST' || request.method() === 'DELETE')) {
      const venueId = Number(venueMatch[1])
      const method = request.method()
      favoriteMutationRequests.push({ venueId, method })
      const deferredIndex = deferredFavoriteMutations.findIndex(
        (deferred) => deferred.venueId === venueId && (!deferred.method || deferred.method === method)
      )
      const gate = deferredIndex >= 0 ? deferredFavoriteMutations.splice(deferredIndex, 1)[0] : null
      await gate?.promise
      if (options.favoriteMutationDelayMs) {
        await new Promise((resolve) => setTimeout(resolve, options.favoriteMutationDelayMs))
      }
      if (favoriteMutationFailureOnce) {
        favoriteMutationFailureOnce = false
        await route.fulfill(
          jsonResponse({ error: { code: 'INTERNAL_ERROR', message: 'Failed' } }, 500)
        )
        return
      }
      if (request.method() === 'POST' && venueAvailable) {
        favoriteIds.add(venueId)
      }
      if (request.method() === 'DELETE') {
        favoriteIds.delete(venueId)
      }
      await route.fulfill(jsonResponse({ ok: true }))
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
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

    const repeatPlanMatch = path.match(/^\/api\/guest\/visits\/(\d+)\/repeat-plan$/)
    if (repeatPlanMatch && request.method() === 'POST') {
      const visitId = Number(repeatPlanMatch[1])
      const body = (await request.postDataJSON()) as {
        tableSessionId: number
        tabId: number
        orderId?: number | null
      }
      repeatPlanRequests.push({ visitId, body })
      const visitPlans = visitHistory.repeatPlans?.[visitId]
      const plan =
        body.orderId != null
          ? visitPlans?.[body.orderId]
          : Object.values(visitPlans ?? {}).length === 1
            ? Object.values(visitPlans ?? {})[0]
            : undefined
      if (plan) {
        await route.fulfill(jsonResponse(plan))
      } else {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'NOT_FOUND', message: 'Repeat plan not found' } })
        })
      }
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

    const feedbackMatch = path.match(/^\/api\/guest\/visits\/(\d+)\/feedback$/)
    if (feedbackMatch && request.method() === 'POST') {
      const visitId = Number(feedbackMatch[1])
      const detail = visitHistory.details[visitId]
      if (!detail) {
        await route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({ error: { code: 'NOT_FOUND', message: 'Visit not found' } })
        })
        return
      }
      const currentFeedback = (detail.feedback ?? {}) as Record<string, unknown>
      if (currentFeedback.submitted === true) {
        await route.fulfill(jsonResponse({ feedback: currentFeedback }))
        return
      }
      const body = JSON.parse(request.postData() || '{}') as {
        rating?: number
        tags?: string[]
        comment?: string | null
      }
      const submittedRating = body.rating ?? 5
      const feedback = {
        eligible: true,
        submitted: true,
        rating: submittedRating,
        tags: body.tags ?? [],
        comment: body.comment ?? null,
        publicReviewUrl:
          submittedRating === 5 && typeof detail.publicReviewUrl === 'string' ? detail.publicReviewUrl : null
      }
      detail.feedback = feedback
      await route.fulfill(jsonResponse({ feedback }))
      return
    }

    await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
  })

  await page.route('**/api/guest/table/resolve?**', async (route) => {
    await route.fulfill(
      jsonResponse({
        venueId: tableScope.venueId,
        venueName: 'Микс',
        tableId: 7,
        tableSessionId: tableScope.tableSessionId,
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
        tabs:
          guestTabs ??
          [
            {
              id: tableScope.tabId,
              tableSessionId: tableScope.tableSessionId,
              type: 'PERSONAL',
              ownerUserId: tableScope.ownerUserId,
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
    const body = (await route.request().postDataJSON()) as CartPreviewRequestFixture
    previewRequests.push(body)
    if (options.cartPreviewResponseResolver) {
      const response = await options.cartPreviewResponseResolver(body)
      if ('error' in response) {
        await route.fulfill({
          status: response.error.status,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: response.error.code,
              message: response.error.message,
              details: response.error.details
            }
          })
        })
      } else {
        await route.fulfill(jsonResponse(response))
      }
      return
    }
    if (options.cartPreviewResolver) {
      await route.fulfill(jsonResponse({ preview: options.cartPreviewResolver(body) }))
      return
    }
    if (cartPreview) {
      await route.fulfill(jsonResponse({ preview: cartPreview }))
      return
    }
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
          items: previewItems,
          pricingFingerprint: 'e2e-preview-default',
          cartFingerprint: `e2e-cart-${body.tableSessionId}-${body.tabId}-${JSON.stringify(body.items)}-${body.comment ?? ''}`,
          giftOffer: { status: 'NO_GIFT' }
        }
      })
    )
  })

  await page.route('**/api/guest/order/add-batch', async (route) => {
    const body = (await route.request().postDataJSON()) as AddBatchPayload
    addBatchRequests.push(body)
    submittedOrderItems = body.items
    if (options.addBatchResponseResolver) {
      const response = await options.addBatchResponseResolver(body)
      if ('error' in response) {
        await route.fulfill({
          status: response.error.status,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: response.error.code,
              message: response.error.message,
              details: response.error.details
            },
            requestId: 'e2e-order-idempotency-request'
          })
        })
      } else {
        await route.fulfill(jsonResponse(response))
      }
      return
    }
    if (options.addBatchResponse) {
      await route.fulfill(jsonResponse(options.addBatchResponse))
      return
    }
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
    getRepeatPlanRequests: () => repeatPlanRequests,
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
    },
    setVenueAvailable: (available: boolean) => {
      venueAvailable = available
    },
    setCatalogVenues: (venues: GuestCatalogVenueFixture[]) => {
      catalogVenues = [...venues]
    },
    getCatalogRequests: () => [...catalogRequests],
    getCatalogResponseAttempts: () => [...catalogResponseAttempts],
    queueCatalogError: (error: ApiErrorFixture) => {
      catalogErrors.push(error)
    },
    deferNextCatalogResponse,
    getFavoriteMutationRequests: () => [...favoriteMutationRequests],
    deferNextFavoriteMutation,
    setPromotions: (items: Array<Omit<VenuePromotionFixture, 'status'>>) => {
      promotions = items
    },
    setCartPreview: (preview: CartPreviewFixture | null) => {
      cartPreview = preview
    },
    setMenuCategories: (categories: GuestMenuCategory[]) => {
      menuCategories = categories
    },
    setTableScope: (scope: GuestTableScopeFixture) => {
      tableScope = scope
    },
    setTabs: (tabs: GuestTabFixture[] | null) => {
      guestTabs = tabs
    },
    setFavoriteVenueIds: (userId: number, venueIds: number[]) => {
      const token = options.isolateFavoriteUsers ? `favorite-user-${userId}` : defaultFavoriteToken
      favoriteVenueIdsByToken.set(token, new Set(venueIds))
    },
    getFavoriteVenueIds: (userId: number) => {
      const token = options.isolateFavoriteUsers ? `favorite-user-${userId}` : defaultFavoriteToken
      return Array.from(favoriteVenueIdsByToken.get(token) ?? [])
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
    publicReviewUrl?: string | null
    failPublicCardUpdateOnce?: boolean
    promotionFacts?: boolean
    activePromotionReward?: boolean
    staffModuleSettings?: VenueStaffModuleSettingsFixture
  } = {}
) {
  const role = options.role ?? 'STAFF'
  const permissions = options.permissions ?? ['ORDER_QUEUE_VIEW', 'SHIFT_EXTENSION_VIEW', 'SHIFT_EXTENSION_CONFIRM']
  let requests = [buildShiftExtensionRequest()]
  let settings = options.settings ?? buildShiftExtensionSettings()
  let bookingSettings = options.bookingSettings ?? buildBookingSettings()
  let scheduleSettings = options.scheduleSettings ?? buildVenueScheduleSettings()
  let publicCardSettings = options.publicCardSettings ?? buildPublicCardSettings()
  let publicReviewUrl = options.publicReviewUrl ?? null
  let staffModuleSettings = options.staffModuleSettings ?? {
    teamScheduleModuleEnabled: true,
    guestTeamVisible: true,
    todayStaffSource: 'MANUAL' as const,
    updatedAt: '2030-01-10T18:00:00.000001Z'
  }
  let approveCalls = 0
  let rejectCalls = 0
  let updateSettingsCalls = 0
  let updateBookingSettingsCalls = 0
  let updatePublicCardSettingsCalls = 0
  let updatePublicReviewUrlCalls = 0
  let updateStaffModuleSettingsCalls = 0
  let locationProviderCalls = 0
  let failPublicCardUpdateOnce = options.failPublicCardUpdateOnce === true
  const promotionFacts = options.promotionFacts === true
  const activePromotionReward = options.activePromotionReward === true
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

  await page.route('**/api/venue/1/staff-module-settings', async (route) => {
    if (route.request().method() === 'PUT') {
      updateStaffModuleSettingsCalls += 1
      const body = (await route.request().postDataJSON()) as {
        teamScheduleModuleEnabled: boolean
        guestTeamVisible: boolean
        todayStaffSource: 'MANUAL' | 'SCHEDULE'
      }
      staffModuleSettings = {
        teamScheduleModuleEnabled: body.teamScheduleModuleEnabled,
        guestTeamVisible: body.guestTeamVisible,
        todayStaffSource: body.todayStaffSource,
        updatedAt: `2030-01-10T18:00:00.${String(updateStaffModuleSettingsCalls + 1).padStart(6, '0')}Z`
      }
    }
    await route.fulfill(jsonResponse(staffModuleSettings))
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
    const manualDiscountMinor = promotionFacts || activePromotionReward ? 0 : 12000
    const promoDiscountMinor = promotionFacts ? 60000 : 0
    return {
      grossTotalMinor: 120000,
      manualDiscountTotalMinor: manualDiscountMinor,
      promoDiscountTotalMinor: promoDiscountMinor,
      loyaltyDiscountTotalMinor: 0,
      excludedTotalMinor: 30000,
      canceledTotalMinor: 0,
      rejectedTotalMinor: 0,
      finalPayableTotalMinor: 120000 - manualDiscountMinor - promoDiscountMinor + serviceChargeTotal,
      currency: 'RUB',
      promoDiscounts: promotionFacts
        ? [
            {
              label: 'Счастливые часы',
              discountMinor: promoDiscountMinor,
              currency: 'RUB',
              ruleType: 'HAPPY_HOURS_PERCENT'
            }
          ]
        : [],
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
              promotionDiscounts: promotionFacts
                ? [
                    {
                      label: 'Счастливые часы',
                      discountMinor: 60000,
                      currency: 'RUB',
                      ruleType: 'HAPPY_HOURS_PERCENT'
                    }
                  ]
                : [],
              items: [
                {
                  batchItemId: 700,
                  itemId: 100,
                  name: 'Double Apple',
                  qty: 1,
                  priceMinor: 120000,
                  currency: 'RUB',
                  lineGrossMinor: 120000,
                  manualDiscountMinor: promotionFacts || activePromotionReward ? 0 : 12000,
                  promoDiscountMinor: promotionFacts ? 60000 : 0,
                  linePayableMinor: promotionFacts ? 60000 : activePromotionReward ? 120000 : 108000,
                  hasActivePromotionReward: activePromotionReward,
                  isExcluded: false,
                  discountPercent: promotionFacts || activePromotionReward ? null : 10,
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

  await page.route('**/api/venue/1/public-review-url', async (route) => {
    const request = route.request()
    if (request.method() === 'PUT') {
      const body = (await request.postDataJSON()) as { publicReviewUrl?: string | null }
      const value = body.publicReviewUrl?.trim() ?? ''
      if (!value.startsWith('https://')) {
        await route.fulfill(
          jsonResponse({ error: { code: 'INVALID_INPUT', message: 'Ссылка должна начинаться с https://' } }, 400)
        )
        return
      }
      publicReviewUrl = value
      updatePublicReviewUrlCalls += 1
    } else if (request.method() === 'DELETE') {
      publicReviewUrl = null
      updatePublicReviewUrlCalls += 1
    }
    await route.fulfill(jsonResponse({ venueId: 1, publicReviewUrl }))
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
    getUpdatePublicReviewUrlCalls: () => updatePublicReviewUrlCalls,
    getUpdateStaffModuleSettingsCalls: () => updateStaffModuleSettingsCalls,
    getLocationProviderCalls: () => locationProviderCalls,
    getSettings: () => settings,
    getBookingSettings: () => bookingSettings,
    getScheduleSettings: () => scheduleSettings,
    getPublicCardSettings: () => publicCardSettings,
    getPublicReviewUrl: () => publicReviewUrl,
    getStaffModuleSettings: () => ({ ...staffModuleSettings }),
    getRejectedReasons: () => rejectedReasons,
    setRequests: (nextRequests: ShiftExtensionRequest[]) => {
      requests = nextRequests
    }
  }
}

async function mockVenueGuestPreviewApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    permissions?: string[]
    venues?: VenueGuestPreviewAccessFixture[]
    previews?: Record<number, VenueGuestPreviewFixture>
    deferredVenueIds?: number[]
  } = {}
) {
  const role = options.role ?? 'OWNER'
  const accesses =
    options.venues ??
    [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role,
        permissions: options.permissions ?? []
      }
    ]
  const previews = new Map(
    accesses.map((access) => [
      access.venueId,
      options.previews?.[access.venueId] ??
        (access.venueStatus === 'PUBLISHED'
          ? buildVenueGuestPreviewFixture(access.venueId)
          : buildVenuePrivatePreviewFixture(access.venueId, {
              venueAvailabilityLabel:
                access.venueStatus === 'DRAFT'
                  ? 'Заведение ещё не опубликовано.'
                  : access.venueStatus === 'HIDDEN'
                    ? 'Заведение временно скрыто.'
                    : 'Заведение приостановлено.',
              venue: { status: access.venueStatus }
            }))
    ])
  )
  const traffic: Array<{ method: string; path: string }> = []
  const previewRequests: Array<{ venueId: number; method: string }> = []
  const previewMediaRequests: Array<{
    venueId: number
    path: string
    method: string
    authorization: string | undefined
  }> = []
  const deferred = new Map<number, { promise: Promise<void>; release: () => void }>()
  ;(options.deferredVenueIds ?? []).forEach((venueId) => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    deferred.set(venueId, { promise, release })
  })

  page.on('request', (request) => {
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/')) {
      traffic.push({ method: request.method(), path: url.pathname })
    }
  })

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-session-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })

  await page.route('**/api/venue/me', async (route) => {
    await route.fulfill(jsonResponse({ userId: 123456789, venues: accesses }))
  })

  await page.route('**/api/guest/venue/*', async (route) => {
    const path = new URL(route.request().url()).pathname
    const venueId = Number(path.match(/^\/api\/guest\/venue\/(\d+)$/)?.[1])
    if (!Number.isSafeInteger(venueId)) {
      await route.fallback()
      return
    }
    const preview = previews.get(venueId)
    if (!preview) {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }
    await route.fulfill(
      jsonResponse({
        venue: {
          id: venueId,
          name: preview.venue.name,
          city: preview.venue.city,
          address: preview.venue.address,
          status: 'PUBLISHED',
          isFavorite: false
        }
      })
    )
  })

  await page.route('**/api/guest/venue/*/info-sections/*/media/*', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const mediaMatch = path.match(
      /^\/api\/guest\/venue\/(\d+)\/info-sections\/\d+\/media\/\d+$/
    )
    const venueId = Number(mediaMatch?.[1])
    if (!mediaMatch || !previews.has(venueId) || request.method() !== 'GET') {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }
    previewMediaRequests.push({
      venueId,
      path,
      method: request.method(),
      authorization: request.headers().authorization
    })
    await route.fulfill({
      status: 200,
      contentType: 'image/png',
      body: transparentPng
    })
  })

  await page.route('**/api/venue/*/staff-calls**', async (route) => {
    await route.fulfill(jsonResponse({ items: [] }))
  })

  await page.route('**/api/venue/*/guest-preview**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const mediaMatch = path.match(
      /^\/api\/venue\/(\d+)\/guest-preview\/info-sections\/\d+\/media\/\d+$/
    )
    const previewMatch = path.match(/^\/api\/venue\/(\d+)\/guest-preview$/)
    const venueId = Number(mediaMatch?.[1] ?? previewMatch?.[1])
    const fixture = previews.get(venueId)

    if (!fixture || (!mediaMatch && !previewMatch)) {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }
    if (request.method() !== 'GET') {
      await route.fulfill(jsonResponse({ error: { code: 'METHOD_NOT_ALLOWED', message: 'GET only' } }, 405))
      return
    }
    if (mediaMatch) {
      previewMediaRequests.push({
        venueId,
        path,
        method: request.method(),
        authorization: request.headers().authorization
      })
      await route.fulfill({
        status: 200,
        contentType: 'image/png',
        body: transparentPng
      })
      return
    }
    previewRequests.push({ venueId, method: request.method() })
    await deferred.get(venueId)?.promise
    const error = fixture.previewError
    if (error) {
      await route.fulfill(
        jsonResponse(
          {
            error: {
              code: error.code ?? (error.status === 404 ? 'NOT_FOUND' : 'INTERNAL_ERROR'),
              message: error.message ?? 'Preview failed'
            }
          },
          error.status
        )
      )
      return
    }
    await route.fulfill(jsonResponse(fixture))
  })

  await page.route('**/preview-media-*.png', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/png',
      body: transparentPng
    })
  })

  await page.route('**/api/venue/*/public-card', async (route) => {
    const request = route.request()
    const venueId = Number(new URL(request.url()).pathname.match(/^\/api\/venue\/(\d+)\/public-card$/)?.[1])
    const card = previews.get(venueId)?.venue
    if (!card || (request.method() !== 'GET' && request.method() !== 'PUT')) {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }
    if (request.method() === 'PUT') {
      const body = (await request.postDataJSON()) as {
        city?: string | null
        address?: string | null
        countryCode?: string | null
        formattedAddress?: string | null
        latitude?: number | null
        longitude?: number | null
        guestContact?: string | null
        cardDescription?: string | null
      }
      card.city = body.city
      card.address = body.address
      card.countryCode = body.countryCode
      card.formattedAddress = body.formattedAddress
      card.displayAddress =
        body.formattedAddress?.trim() ||
        [body.city?.trim(), body.address?.trim()].filter(Boolean).join(', ') ||
        null
      card.latitude = body.latitude
      card.longitude = body.longitude
      card.guestContact = body.guestContact
      card.cardDescription = body.cardDescription
    }
    await route.fulfill(
      jsonResponse({
        venueId,
        name: card.name,
        city: card.city,
        address: card.address,
        countryCode: card.countryCode,
        formattedAddress: card.formattedAddress,
        displayAddress: card.displayAddress,
        latitude: card.latitude,
        longitude: card.longitude,
        routeUrl: card.routeUrl,
        guestContact: card.guestContact,
        cardDescription: card.cardDescription
      })
    )
  })

  const scheduleResponse = (venueId: number) => {
    const venue = previews.get(venueId)?.venue
    return {
      venueId,
      weeklyHours: (venue?.weeklyHours ?? []).map((day) => ({ ...day, configured: true })),
      dateOverrides: venue?.dateExceptions ?? []
    }
  }

  await page.route('**/api/venue/*/schedule/weekly/*', async (route) => {
    const request = route.request()
    const match = new URL(request.url()).pathname.match(/^\/api\/venue\/(\d+)\/schedule\/weekly\/(\d+)$/)
    const venueId = Number(match?.[1])
    const weekday = Number(match?.[2])
    const venue = previews.get(venueId)?.venue
    if (!venue || request.method() !== 'PUT') {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }
    const body = (await request.postDataJSON()) as {
      opensAt?: string | null
      closesAt?: string | null
      isClosed?: boolean
    }
    venue.weeklyHours = venue.weeklyHours.map((day) =>
      day.weekday === weekday
        ? {
            ...day,
            opensAt: body.isClosed ? '00:00' : body.opensAt ?? day.opensAt,
            closesAt: body.isClosed ? '00:00' : body.closesAt ?? day.closesAt,
            isClosed: body.isClosed === true
          }
        : day
    )
    await route.fulfill(jsonResponse(scheduleResponse(venueId)))
  })

  await page.route('**/api/venue/*/schedule/override-ranges', async (route) => {
    const request = route.request()
    const venueId = Number(
      new URL(request.url()).pathname.match(/^\/api\/venue\/(\d+)\/schedule\/override-ranges$/)?.[1]
    )
    const venue = previews.get(venueId)?.venue
    if (!venue || request.method() !== 'POST') {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }
    const body = (await request.postDataJSON()) as {
      fromDate: string
      toDate: string
      opensAt?: string | null
      closesAt?: string | null
      isClosed?: boolean
      guestNote?: string | null
    }
    const dates = eachIsoDate(body.fromDate, body.toDate)
    venue.dateExceptions = [
      ...venue.dateExceptions.filter((exception) => !dates.includes(exception.serviceDate)),
      ...dates.map((serviceDate) => ({
        serviceDate,
        opensAt: body.isClosed ? '00:00' : body.opensAt ?? '18:00',
        closesAt: body.isClosed ? '00:00' : body.closesAt ?? '00:00',
        isClosed: body.isClosed === true,
        guestNote: body.guestNote?.trim() || null
      }))
    ].sort((left, right) => left.serviceDate.localeCompare(right.serviceDate))
    await route.fulfill(jsonResponse(scheduleResponse(venueId)))
  })

  await page.route('**/api/venue/*/schedule', async (route) => {
    const venueId = Number(new URL(route.request().url()).pathname.match(/^\/api\/venue\/(\d+)\/schedule$/)?.[1])
    await route.fulfill(jsonResponse(scheduleResponse(venueId)))
  })

  return {
    getPreviewRequests: () => [...previewRequests],
    getPreviewMediaRequests: () => [...previewMediaRequests],
    getTraffic: () => [...traffic],
    releaseVenue: (venueId: number) => deferred.get(venueId)?.release(),
    clearTraffic: () => {
      traffic.splice(0, traffic.length)
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

function buildVenueFeedback(filter: 'all' | 'low' = 'all'): VenueFeedbackResponse {
  const allItems = [
    {
      feedbackId: 91,
      visitId: 11,
      occurredAt: '2030-01-11T18:30:00Z',
      serviceDate: '2030-01-11',
      rating: 5,
      tags: ['service', 'taste'],
      comment: 'Все отлично',
      guestLabel: 'Гость 9010',
      createdAt: '2030-01-11T19:00:00Z'
    },
    {
      feedbackId: 92,
      visitId: 12,
      occurredAt: '2030-01-12T18:30:00Z',
      serviceDate: '2030-01-12',
      rating: 2,
      tags: ['speed'],
      comment: 'Долго ждали',
      guestLabel: 'Гость 9011',
      createdAt: '2030-01-12T19:00:00Z'
    }
  ]
  return {
    venueId: 1,
    filter,
    summary: {
      count: 2,
      averageRating: 3.5,
      lowCount: 1
    },
    items: filter === 'low' ? allItems.filter((item) => item.rating <= 3) : allItems
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
  let followUpCalls = 0
  const followUpThread: SupportThreadFixture = {
    threadId: 501,
    venueId: 1,
    venueName: 'Микс',
    guestDisplayName: 'Гость 9011',
    threadType: 'VENUE_CHAT',
    assigneeScope: 'VENUE',
    category: 'OTHER',
    contextLabel: 'Отзыв после визита',
    status: 'IN_PROGRESS',
    statusLabel: 'В работе',
    title: 'Отзыв после визита',
    lastMessagePreview: null,
    lastMessageAt: null,
    unreadCount: 0,
    createdAt: '2030-01-12T19:05:00Z',
    updatedAt: '2030-01-12T19:05:00Z',
    booking: null
  }
  const followUpContextMessage: SupportMessageFixture = {
    messageId: 701,
    threadId: followUpThread.threadId,
    authorRole: 'SYSTEM',
    source: 'SYSTEM',
    text: 'Отзыв после визита\nОценка: 2/5\nТеги: скорость\nКомментарий: Долго ждали\nДата визита: 2030-01-12',
    createdAt: '2030-01-12T19:05:00Z'
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

  await page.route('**/api/venue/1/stats**', async (route) => {
    const url = new URL(route.request().url())
    const period = (url.searchParams.get('period') || 'today') as 'today' | '7d' | '30d'
    periods.push(period)
    const stats = options.statsByPeriod?.[period] ?? buildVenueStats(period)
    await route.fulfill(jsonResponse(stats))
  })

  await page.route('**/api/venue/1/feedback**', async (route) => {
    const request = route.request()
    const url = new URL(route.request().url())
    const followUpMatch = url.pathname.match(/\/api\/venue\/1\/feedback\/(\d+)\/follow-up$/)
    if (followUpMatch && request.method() === 'POST') {
      followUpCalls += 1
      await route.fulfill(jsonResponse({ threadId: followUpThread.threadId, threadType: 'VENUE_CHAT', message: 'Чат с гостем открыт.' }))
      return
    }
    const filter = (url.searchParams.get('filter') || 'all') as 'all' | 'low'
    await route.fulfill(jsonResponse(buildVenueFeedback(filter)))
  })

  await page.route('**/api/venue/1/support/threads**', async (route) => {
    const url = new URL(route.request().url())
    const threadMatch = url.pathname.match(/\/api\/venue\/1\/support\/threads\/(\d+)$/)
    if (threadMatch) {
      await route.fulfill(jsonResponse({ thread: followUpThread, messages: followUpCalls > 0 ? [followUpContextMessage] : [] }))
      return
    }
    await route.fulfill(jsonResponse({ items: followUpCalls > 0 ? [followUpThread] : [] }))
  })

  return {
    getPeriods: () => periods,
    getFollowUpCalls: () => followUpCalls
  }
}

async function mockVenuePromotionsApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    promotions?: VenuePromotionFixture[]
    nowEpochMs?: number
    menuCategories?: Array<{ id: number; name: string }>
    menuItems?: Array<{ id: number; name: string; categoryId: number }>
    statusStale?: VenuePromotionStaleFixture
    archiveStale?: VenuePromotionStaleFixture
    venueId?: number
    accessVenueIds?: number[]
    promotionsByVenue?: Record<number, VenuePromotionFixture[]>
    promotionListFailureRequests?: number[]
  } = {}
) {
  const role = options.role ?? 'OWNER'
  const venueId = options.venueId ?? 1
  const accessVenueIds = Array.from(new Set([...(options.accessVenueIds ?? []), venueId]))
  const promotionListFailureRequests = new Set(options.promotionListFailureRequests ?? [])
  let promotions = [...(options.promotionsByVenue?.[venueId] ?? options.promotions ?? [])]
  const secondaryPromotions = new Map(
    accessVenueIds
      .filter((accessVenueId) => accessVenueId !== venueId)
      .map((accessVenueId) => [accessVenueId, [...(options.promotionsByVenue?.[accessVenueId] ?? [])]])
  )
  let nextId = Math.max(0, ...promotions.map((item) => item.id)) + 1
  let nextRuleId = 1001
  const mutations: string[] = []
  const lifecycleRequests: VenuePromotionLifecycleRequestFixture[] = []
  let promotionListRequests = 0
  let statusStaleConsumed = false
  let archiveStaleConsumed = false
  const promotionListVenueRequests: number[] = []
  const settledPromotionListVenues: number[] = []
  const deferredPromotionLists = new Map<number, Array<{ promise: Promise<void>; release: () => void }>>()
  const menuCategories = options.menuCategories ?? [{ id: 20, name: 'Кальяны' }]
  const menuItems = options.menuItems ?? [{ id: 200, name: 'Double Apple', categoryId: 20 }]

  const deferNextPromotionList = (deferredVenueId: number) => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    const queue = deferredPromotionLists.get(deferredVenueId) ?? []
    queue.push({ promise, release })
    deferredPromotionLists.set(deferredVenueId, queue)
    return release
  }

  const fulfillPromotionList = async (
    route: Route,
    responseVenueId: number,
    responseItems: VenuePromotionFixture[]
  ) => {
    promotionListRequests += 1
    promotionListVenueRequests.push(responseVenueId)
    if (promotionListFailureRequests.has(promotionListRequests)) {
      await route.fulfill(
        jsonResponse(
          {
            error: {
              code: 'DATABASE_UNAVAILABLE',
              message: 'Сервис временно недоступен.'
            }
          },
          503
        )
      )
      return
    }
    const snapshot = JSON.parse(JSON.stringify(responseItems)) as VenuePromotionFixture[]
    const gate = deferredPromotionLists.get(responseVenueId)?.shift()
    await gate?.promise
    try {
      await route.fulfill(
        jsonResponse({
          venueId: responseVenueId,
          timezone: promotionVenueTimezone,
          items: snapshot,
          menuCategories,
          menuItems
        })
      )
    } catch {
      // A venue switch aborts the disposed screen request; the late fixture response is intentionally ignored.
    } finally {
      settledPromotionListVenues.push(responseVenueId)
    }
  }

  const toRuleFixture = (
    mutation: VenuePromotionMutationFixture,
    previousRule?: VenuePromotionRuleFixture | null
  ): VenuePromotionRuleFixture | null => {
    if (mutation.templateType === 'TEXT_ONLY' || !mutation.rule) return null
    const target = mutation.rule.target
    const label =
      target.type === 'MENU_ITEM'
        ? menuItems.find((item) => item.id === target.menuItemId)?.name
        : menuCategories.find((category) => category.id === target.menuCategoryId)?.name
    return {
      id: previousRule?.id ?? nextRuleId++,
      version: previousRule ? previousRule.version + 1 : 1,
      windows: mutation.rule.windows,
      target: { ...target, label: label ?? null },
      discountPercent: mutation.templateType === 'HAPPY_HOURS_PERCENT' ? mutation.rule.discountPercent : null,
      reward:
        mutation.templateType === 'GIFT_WITH_ITEM' && mutation.rule.reward
          ? {
              mode: mutation.rule.reward.mode,
              fixedItem:
                mutation.rule.reward.fixedMenuItemId == null
                  ? null
                  : (() => {
                      const item = menuItems.find(
                        (candidate) => candidate.id === mutation.rule?.reward?.fixedMenuItemId
                      )
                      return item ? { menuItemId: item.id, name: item.name } : null
                    })(),
              allowlist: mutation.rule.reward.allowlistMenuItemIds.flatMap((itemId) => {
                const item = menuItems.find((candidate) => candidate.id === itemId)
                return item ? [{ menuItemId: item.id, name: item.name }] : []
              })
            }
          : null,
      readyForActivation: true,
      validationIssues: []
    }
  }

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-session-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })

  await page.route('**/api/guest/catalog', async (route) => {
    await route.fulfill(
      jsonResponse({
        venues: [
          {
            id: venueId,
            name: 'Микс',
            city: 'Москва',
            address: 'Пилотная, 1',
            isFavorite: false
          }
        ]
      })
    )
  })

  await page.route('**/api/venue/me', async (route) => {
    await route.fulfill(
      jsonResponse({
        userId: 123456789,
        venues: accessVenueIds.map((accessVenueId) => ({
          venueId: accessVenueId,
          venueName: accessVenueId === venueId ? 'Микс' : `Заведение ${accessVenueId}`,
          venueCity: 'Москва',
          venueStatus: 'PUBLISHED',
          role,
          permissions: role === 'STAFF' ? ['ORDER_QUEUE_VIEW'] : []
        }))
      })
    )
  })

  await page.route(`**/api/guest/venue/${venueId}`, async (route) => {
    const now = options.nowEpochMs ?? Date.now()
    const visiblePromotions = promotions
      .filter(
        (item) =>
          item.status === 'ACTIVE' &&
          new Date(item.startsAt).getTime() <= now &&
          new Date(item.endsAt).getTime() >= now
      )
      .map(({ status: _status, ...item }) => item)
    await route.fulfill(
      jsonResponse({
        venue: {
          id: venueId,
          name: 'Микс',
          city: 'Москва',
          address: 'Пилотная, 1',
          timezone: promotionVenueTimezone,
          promotions: visiblePromotions,
          status: 'PUBLISHED',
          isFavorite: false
        }
      })
    )
  })

  await page.route(`**/api/guest/venue/${venueId}/info-sections`, async (route) => {
    await route.fulfill(jsonResponse({ venueId, sections: [] }))
  })

  await page.route(`**/api/venue/${venueId}/staff-calls**`, async (route) => {
    await route.fulfill(jsonResponse({ items: [] }))
  })

  for (const [secondaryVenueId, secondaryItems] of secondaryPromotions) {
    await page.route(`**/api/venue/${secondaryVenueId}/promotions**`, async (route) => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      if (path === `/api/venue/${secondaryVenueId}/promotions` && request.method() === 'GET') {
        await fulfillPromotionList(route, secondaryVenueId, secondaryItems)
        return
      }
      await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
    })
  }

  await page.route(`**/api/venue/${venueId}/promotions**`, async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const statusMatch = path.match(new RegExp(`^/api/venue/${venueId}/promotions/(\\d+)/status$`))
    const itemMatch = path.match(new RegExp(`^/api/venue/${venueId}/promotions/(\\d+)$`))
    const promotionsPath = `/api/venue/${venueId}/promotions`
    if (path === promotionsPath && request.method() === 'GET') {
      await fulfillPromotionList(route, venueId, promotions)
      return
    }
    if (path === promotionsPath && request.method() === 'POST') {
      const body = (await request.postDataJSON()) as VenuePromotionMutationFixture
      const normalizedBody = normalizePromotionMutation(body)
      const promotion: VenuePromotionFixture = {
        id: nextId++,
        status: 'DRAFT',
        ...normalizedBody,
        rule: toRuleFixture(normalizedBody)
      }
      promotions = [promotion, ...promotions]
      mutations.push('create')
      await route.fulfill(jsonResponse({ promotion }))
      return
    }
    if (statusMatch && request.method() === 'POST') {
      const promotionId = Number(statusMatch[1])
      const body = (await request.postDataJSON()) as { status: 'ACTIVE' | 'PAUSED' }
      lifecycleRequests.push({ method: 'POST', path, promotionId, body })
      if (
        options.statusStale?.promotionId === promotionId &&
        !statusStaleConsumed
      ) {
        statusStaleConsumed = true
        promotions = promotions.map((item) =>
          item.id === promotionId ? { ...item, status: options.statusStale!.authoritativeStatus } : item
        )
        await route.fulfill(
          jsonResponse(
            {
              error: {
                code: 'PROMOTION_LIFECYCLE_STALE',
                message: 'Статус акции уже изменился. Обновите список и повторите действие.'
              }
            },
            409
          )
        )
        return
      }
      const current = promotions.find((item) => item.id === promotionId)
      if (
        body.status === 'ACTIVE' &&
        (current?.templateType === 'HAPPY_HOURS_PERCENT' || current?.templateType === 'GIFT_WITH_ITEM') &&
        current.rule?.readyForActivation !== true
      ) {
        await route.fulfill(
          jsonResponse(
            {
              error: {
                code: 'INVALID_INPUT',
                message: 'Сначала заполните расписание, категорию или позицию и процент скидки.'
              }
            },
            400
          )
        )
        return
      }
      promotions = promotions.map((item) => (item.id === promotionId ? { ...item, status: body.status } : item))
      mutations.push(body.status.toLowerCase())
      await route.fulfill(jsonResponse({ promotion: promotions.find((item) => item.id === promotionId) }))
      return
    }
    if (itemMatch && request.method() === 'PUT') {
      const promotionId = Number(itemMatch[1])
      const body = (await request.postDataJSON()) as VenuePromotionMutationFixture
      const normalizedBody = normalizePromotionMutation(body)
      promotions = promotions.map((item) =>
        item.id === promotionId
          ? {
              ...item,
              ...normalizedBody,
              rule: toRuleFixture(normalizedBody, item.rule)
            }
          : item
      )
      mutations.push('update')
      await route.fulfill(jsonResponse({ promotion: promotions.find((item) => item.id === promotionId) }))
      return
    }
    if (itemMatch && request.method() === 'DELETE') {
      const promotionId = Number(itemMatch[1])
      lifecycleRequests.push({ method: 'DELETE', path, promotionId })
      if (
        options.archiveStale?.promotionId === promotionId &&
        !archiveStaleConsumed
      ) {
        archiveStaleConsumed = true
        promotions = promotions.map((item) =>
          item.id === promotionId ? { ...item, status: options.archiveStale!.authoritativeStatus } : item
        )
        await route.fulfill(
          jsonResponse(
            {
              error: {
                code: 'PROMOTION_LIFECYCLE_STALE',
                message: 'Статус акции уже изменился. Обновите список и повторите действие.'
              }
            },
            409
          )
        )
        return
      }
      promotions = promotions.map((item) => (item.id === promotionId ? { ...item, status: 'ARCHIVED' } : item))
      mutations.push('archive')
      await route.fulfill(jsonResponse({ promotion: promotions.find((item) => item.id === promotionId) }))
      return
    }
    await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
  })

  return {
    getPromotions: () => promotions,
    getMutations: () => mutations,
    getLifecycleRequests: () => lifecycleRequests,
    getPromotionListRequests: () => promotionListRequests,
    getPromotionListVenueRequests: () => promotionListVenueRequests,
    getSettledPromotionListVenues: () => settledPromotionListVenues,
    deferNextPromotionList
  }
}

function normalizePromotionMutation(
  body: VenuePromotionMutationFixture
): VenuePromotionMutationFixture {
  return {
    ...body,
    startsAt: normalizePromotionDateTime(body.startsAt),
    endsAt: normalizePromotionDateTime(body.endsAt)
  }
}

function normalizePromotionDateTime(value: string): string {
  const hasOffset = /(?:Z|[+-]\d{2}:\d{2})$/i.test(value)
  const instant = new Date(hasOffset ? value : `${value}${promotionVenueUtcOffset}`)
  if (Number.isNaN(instant.getTime())) {
    throw new Error(`Invalid promotion datetime: ${value}`)
  }
  return instant.toISOString()
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

type VenueStaffProfileLinkStateFixture =
  | 'NOT_LINKED'
  | 'LINKED'
  | 'DUPLICATE_LINK_DETECTED'
  | 'PROTECTED'

type VenueStaffMemberFixture = {
  userId: number
  displayName: string
  username?: string | null
  role: 'OWNER' | 'MANAGER' | 'STAFF'
  active: boolean
  linkedStaffProfileId?: number | null
  linkedStaffProfileDisplayName?: string | null
  profileLinkState: VenueStaffProfileLinkStateFixture
}

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

type StaffProfileLinkageClassFixture =
  | 'DISPLAY_ONLY'
  | 'STAFF_LINKED'
  | 'PROTECTED'
  | 'DUPLICATE_LINK_DETECTED'

type StaffProfileLinkConflictFixture = {
  profileLinkState: Exclude<VenueStaffProfileLinkStateFixture, 'NOT_LINKED'>
  linkedStaffProfileId?: number | null
  winningProfile?: StaffProfileFixture
}

type ProjectedStaffProfileFixture = StaffProfileFixture & {
  linkedUserId: number | null
  linkageClass: StaffProfileLinkageClassFixture
  canManage: boolean
  isSelf: boolean
}

type VenueStaffGetResponsesFixture = {
  directory: { members: VenueStaffMemberFixture[] }
  profiles: { profiles: ProjectedStaffProfileFixture[] }
}

async function waitForVenueStaffGetResponses(
  page: Page,
  venueId: number
): Promise<VenueStaffGetResponsesFixture> {
  const directoryPath = `/api/venue/${venueId}/staff`
  const profilesPath = `/api/venue/${venueId}/staff/profiles`
  const [directoryResponse, profilesResponse] = await Promise.all([
    page.waitForResponse((response) => {
      const request = response.request()
      return request.method() === 'GET' && new URL(response.url()).pathname === directoryPath
    }),
    page.waitForResponse((response) => {
      const request = response.request()
      return request.method() === 'GET' && new URL(response.url()).pathname === profilesPath
    })
  ])
  return {
    directory: (await directoryResponse.json()) as VenueStaffGetResponsesFixture['directory'],
    profiles: (await profilesResponse.json()) as VenueStaffGetResponsesFixture['profiles']
  }
}

function recordVenueStaffProfileMutations(page: Page, venueId: number) {
  const profilePathPrefix = `/api/venue/${venueId}/staff/profiles`
  const mutations: Array<{ method: string; path: string; body: unknown }> = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    const method = request.method()
    if (method === 'GET' || !path.startsWith(profilePathPrefix)) return
    let body: unknown = null
    try {
      body = request.postDataJSON()
    } catch {
      body = request.postData()
    }
    mutations.push({ method, path, body })
  })
  return mutations
}

async function mockVenueStaffChatApi(
  page: Page,
  options: {
    role?: 'OWNER' | 'MANAGER' | 'STAFF'
    permissions?: string[]
    linked?: boolean
    generatedExpiresAt?: string
    pendingInvites?: Array<{
      handle: string
      role: 'MANAGER' | 'STAFF'
      status: 'PENDING'
      createdAt: string
      expiresAt: string
    }>
    members?: VenueStaffMemberFixture[]
    staffProfiles?: StaffProfileFixture[]
    holdFirstStaffDirectory?: boolean
    staffDirectoryFailuresBeforeSuccess?: number
    staffModuleSettings?: VenueStaffModuleSettingsFixture
  } = {}
) {
  let accessUserId = 123456789
  const role = options.role ?? 'OWNER'
  const permissions =
    options.permissions ??
    (role === 'OWNER'
      ? [
          'STAFF_CHAT_LINK',
          'STAFF_INVITE_CREATE_STAFF',
          'STAFF_INVITE_CREATE_MANAGER',
          'STAFF_INVITE_REVOKE_STAFF',
          'STAFF_INVITE_REVOKE_MANAGER',
          'STAFF_PROFILE_MANAGE_STAFF',
          'STAFF_PROFILE_MANAGE_PROTECTED',
          'STAFF_PROFILE_PUBLISH_STAFF',
          'STAFF_PROFILE_PUBLISH_PROTECTED',
          'STAFF_PROFILE_EDIT_OWN',
          'STAFF_SCHEDULE_MANAGE',
          'STAFF_MODULE_SETTINGS_MANAGE'
        ]
      : role === 'MANAGER'
        ? [
            'STAFF_CHAT_LINK',
            'STAFF_INVITE_CREATE_STAFF',
            'STAFF_INVITE_REVOKE_STAFF',
            'STAFF_PROFILE_MANAGE_STAFF',
            'STAFF_PROFILE_PUBLISH_STAFF',
            'STAFF_PROFILE_EDIT_OWN',
            'STAFF_SCHEDULE_MANAGE',
            'STAFF_MODULE_SETTINGS_MANAGE'
          ]
        : ['STAFF_PROFILE_EDIT_OWN'])
  let linked = options.linked ?? true
  let generated = 0
  const staffInviteRequests: Array<{ role?: string }> = []
  let pendingInvites = (options.pendingInvites ?? []).map((invite) => ({ ...invite }))
  const revokedInviteHandles: string[] = []
  let testMessages = 0
  let unlinks = 0
  let activeCodeHint: string | null = null
  let activeCodeExpiresAt: string | null = null
  const generatedCodes = ['ABC123', 'DEF456', 'GHI789']
  let nextProfileId = 700
  const profileCreateRequests: Array<Record<string, unknown>> = []
  const profileCreateFromMemberRequests: Array<Record<string, unknown>> = []
  const profileUpdateRequests: Array<Record<string, unknown>> = []
  const shiftRequests: Array<Record<string, unknown>> = []
  let profileGetCalls = 0
  let staffModuleSettings = options.staffModuleSettings ?? {
    teamScheduleModuleEnabled: true,
    guestTeamVisible: true,
    todayStaffSource: 'MANUAL' as const,
    updatedAt: '2030-01-10T18:00:00.000001Z'
  }
  const defaultStaffProfiles: StaffProfileFixture[] = [
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
    },
    {
      id: 502,
      linkedUserId: 222222222,
      displayName: 'Светлана',
      roleLabel: null,
      subtype: 'waiter',
      photoRef: null,
      bio: 'Работает в зале.',
      tags: ['сервис'],
      isGuestVisible: false,
      publishedAt: null,
      disabledAt: null,
      createdAt: '2030-01-10T18:00:00Z',
      updatedAt: '2030-01-10T18:00:00Z',
      todayShift: null
    },
    {
      id: 503,
      linkedUserId: null,
      displayName: 'Карточка без доступа',
      roleLabel: 'Бармен',
      subtype: 'other',
      photoRef: null,
      bio: null,
      tags: [],
      isGuestVisible: false,
      publishedAt: null,
      disabledAt: null,
      createdAt: '2030-01-10T18:00:00Z',
      updatedAt: '2030-01-10T18:00:00Z',
      todayShift: null
    },
    {
      id: 504,
      linkedUserId: 333333333,
      displayName: 'Другой менеджер',
      roleLabel: 'Менеджер зала',
      subtype: 'admin',
      photoRef: null,
      bio: null,
      tags: [],
      isGuestVisible: true,
      publishedAt: '2030-01-10T18:00:00Z',
      disabledAt: null,
      createdAt: '2030-01-10T18:00:00Z',
      updatedAt: '2030-01-10T18:00:00Z',
      todayShift: null
    },
    {
      id: 505,
      linkedUserId: 111111111,
      displayName: 'Владелец заведения',
      roleLabel: 'Владелец',
      subtype: 'admin',
      photoRef: null,
      bio: null,
      tags: [],
      isGuestVisible: true,
      publishedAt: '2030-01-10T18:00:00Z',
      disabledAt: null,
      createdAt: '2030-01-10T18:00:00Z',
      updatedAt: '2030-01-10T18:00:00Z',
      todayShift: null
    }
  ]
  const staffProfiles = (options.staffProfiles ?? defaultStaffProfiles).map((profile) => ({
    ...profile,
    tags: [...profile.tags],
    todayShift: profile.todayShift ? { ...profile.todayShift } : null
  }))
  const defaultStaffMembers: VenueStaffMemberFixture[] = [
    {
      userId: 123456789,
      displayName: 'Алексей Морозов',
      username: 'alexey_owner',
      role,
      active: true,
      linkedStaffProfileId: 501,
      linkedStaffProfileDisplayName: 'Алексей',
      profileLinkState: 'LINKED'
    },
    {
      userId: 222222222,
      displayName: 'Светлана Орлова',
      username: 'sveta_staff',
      role: 'STAFF',
      active: true,
      linkedStaffProfileId: 502,
      linkedStaffProfileDisplayName: 'Светлана',
      profileLinkState: 'LINKED'
    },
    {
      userId: 444444444,
      displayName: 'Максим Катаев',
      username: 'max_kataev',
      role: 'STAFF',
      active: true,
      linkedStaffProfileId: null,
      linkedStaffProfileDisplayName: null,
      profileLinkState: 'NOT_LINKED'
    },
    {
      userId: 555555555,
      displayName: 'Анна Петрова',
      username: null,
      role: 'STAFF',
      active: true,
      linkedStaffProfileId: null,
      linkedStaffProfileDisplayName: null,
      profileLinkState: 'NOT_LINKED'
    },
    {
      userId: 666666666,
      displayName: 'Ирина Безопасная',
      username: 'safe_<script>&"',
      role: 'STAFF',
      active: true,
      linkedStaffProfileId: null,
      linkedStaffProfileDisplayName: null,
      profileLinkState: 'NOT_LINKED'
    },
    {
      userId: 333333333,
      displayName: 'Другой Менеджер',
      username: 'other_manager',
      role: 'MANAGER',
      active: true,
      linkedStaffProfileId: 504,
      linkedStaffProfileDisplayName: 'Другой менеджер',
      profileLinkState: 'PROTECTED'
    },
    {
      userId: 111111111,
      displayName: 'Владелец Заведения',
      username: null,
      role: 'OWNER',
      active: true,
      linkedStaffProfileId: 505,
      linkedStaffProfileDisplayName: 'Владелец заведения',
      profileLinkState: 'PROTECTED'
    }
  ]
  const staffMembers = (options.members ?? defaultStaffMembers).map((member) => ({ ...member }))
  let nextProfileLinkConflict: StaffProfileLinkConflictFixture | null = null
  let shouldHoldStaffDirectory = options.holdFirstStaffDirectory === true
  let staffDirectoryFailuresRemaining = options.staffDirectoryFailuresBeforeSuccess ?? 0
  const deferredProfileLoads: Array<{ promise: Promise<void>; release: () => void }> = []
  let releaseStaffDirectoryGate: (() => void) | null = null
  const staffDirectoryGate = new Promise<void>((resolve) => {
    releaseStaffDirectoryGate = resolve
  })

  const projectStaffProfile = (profile: StaffProfileFixture) => {
    const linkedMember = profile.linkedUserId == null
      ? null
      : staffMembers.find((member) => member.userId === profile.linkedUserId) ?? null
    const activeLinkCount = profile.linkedUserId == null
      ? 0
      : staffProfiles.filter(
        (candidate) =>
          candidate.linkedUserId === profile.linkedUserId && candidate.disabledAt == null
      ).length
    let linkageClass: StaffProfileLinkageClassFixture
    if (profile.linkedUserId == null) {
      linkageClass = 'DISPLAY_ONLY'
    } else if (profile.disabledAt == null && activeLinkCount > 1) {
      linkageClass = 'DUPLICATE_LINK_DETECTED'
    } else if (linkedMember?.active && linkedMember.role === 'STAFF') {
      linkageClass = 'STAFF_LINKED'
    } else {
      linkageClass = 'PROTECTED'
    }
    const canManage =
      role === 'OWNER' ||
      (role === 'MANAGER' &&
        (linkageClass === 'DISPLAY_ONLY' ||
          linkageClass === 'STAFF_LINKED'))
    const isSelf = profile.linkedUserId != null && profile.linkedUserId === accessUserId
    const linkedUserId =
      role === 'STAFF' ||
      (role === 'MANAGER' &&
        (linkageClass === 'PROTECTED' || linkageClass === 'DUPLICATE_LINK_DETECTED'))
        ? null
        : profile.linkedUserId ?? null
    return {
      ...profile,
      linkedUserId,
      linkageClass,
      canManage,
      isSelf,
      tags: [...profile.tags],
      todayShift: profile.todayShift ? { ...profile.todayShift } : null
    }
  }

  const syncStaffMemberLinkState = (userId: number | null | undefined) => {
    if (userId == null) return
    const member = staffMembers.find((candidate) => candidate.userId === userId)
    if (!member || member.role !== 'STAFF') return
    const linkedProfiles = staffProfiles.filter(
      (profile) => profile.linkedUserId === userId && profile.disabledAt == null
    )
    if (!linkedProfiles.length) {
      member.profileLinkState = 'NOT_LINKED'
      member.linkedStaffProfileId = null
      member.linkedStaffProfileDisplayName = null
    } else if (linkedProfiles.length === 1) {
      member.profileLinkState = 'LINKED'
      member.linkedStaffProfileId = linkedProfiles[0].id
      member.linkedStaffProfileDisplayName = linkedProfiles[0].displayName
    } else {
      member.profileLinkState = 'DUPLICATE_LINK_DETECTED'
      member.linkedStaffProfileId = null
      member.linkedStaffProfileDisplayName = null
    }
  }

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-session-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })

  await page.route('**/api/venue/me', async (route) => {
    await route.fulfill(
      jsonResponse({
        userId: accessUserId,
        venues: [
          {
            venueId: 1,
            venueName: 'Микс',
            venueCity: 'Москва',
            venueStatus: 'PUBLISHED',
            role,
            permissions,
            teamScheduleModuleEnabled: staffModuleSettings.teamScheduleModuleEnabled
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

  await page.route('**/api/venue/1/staff-module-settings', async (route) => {
    await route.fulfill(jsonResponse(staffModuleSettings))
  })

  await page.route('**/api/venue/1/staff/profiles**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    const profileMatch = path.match(/^\/api\/venue\/1\/staff\/profiles\/(\d+)(?:\/(publish|hide|today-shift))?$/)

    if (path === '/api/venue/1/staff/profiles' && method === 'GET') {
      profileGetCalls += 1
      const gate = deferredProfileLoads.shift()
      await gate?.promise
      await route.fulfill(jsonResponse({ profiles: staffProfiles.map(projectStaffProfile) }))
      return
    }

    if (path === '/api/venue/1/staff/profiles/from-member' && method === 'POST') {
      const body = (await request.postDataJSON()) as Record<string, unknown>
      profileCreateFromMemberRequests.push(body)
      const linkedUserId = typeof body.userId === 'number' ? body.userId : null
      if (nextProfileLinkConflict) {
        const conflict = nextProfileLinkConflict
        nextProfileLinkConflict = null
        if (conflict.winningProfile) {
          staffProfiles.push({
            ...conflict.winningProfile,
            tags: [...conflict.winningProfile.tags],
            todayShift: conflict.winningProfile.todayShift
              ? { ...conflict.winningProfile.todayShift }
              : null
          })
          syncStaffMemberLinkState(conflict.winningProfile.linkedUserId)
        }
        await route.fulfill(
          jsonResponse(
            {
              error: {
                code: 'STAFF_PROFILE_LINK_CONFLICT',
                message: 'Staff member already has an active linked profile',
                details: {
                  profileLinkState: conflict.profileLinkState,
                  linkedStaffProfileId:
                    conflict.profileLinkState === 'LINKED'
                      ? conflict.linkedStaffProfileId ?? null
                      : null
                }
              }
            },
            409
          )
        )
        return
      }
      const linkedMember = linkedUserId == null
        ? null
        : staffMembers.find((member) => member.userId === linkedUserId) ?? null
      if (!linkedMember || linkedMember.profileLinkState !== 'NOT_LINKED') {
        await route.fulfill(
          jsonResponse(
            {
              error: {
                code: 'STAFF_PROFILE_LINK_CONFLICT',
                message: 'Staff member already has an active linked profile',
                details: {
                  profileLinkState: linkedMember?.profileLinkState ?? 'PROTECTED',
                  linkedStaffProfileId:
                    linkedMember?.profileLinkState === 'LINKED'
                      ? linkedMember.linkedStaffProfileId ?? null
                      : null
                }
              }
            },
            409
          )
        )
        return
      }
      const profile: StaffProfileFixture = {
        id: nextProfileId++,
        linkedUserId,
        displayName: linkedMember.displayName,
        roleLabel: typeof body.roleLabel === 'string' ? body.roleLabel : null,
        subtype: String(body.subtype ?? ''),
        photoRef: null,
        bio: null,
        tags: [],
        isGuestVisible: false,
        publishedAt: null,
        disabledAt: null,
        createdAt: '2030-01-10T18:05:00Z',
        updatedAt: '2030-01-10T18:05:00Z',
        todayShift: null
      }
      staffProfiles.push(profile)
      syncStaffMemberLinkState(linkedUserId)
      await route.fulfill(jsonResponse(projectStaffProfile(profile)))
      return
    }

    if (path === '/api/venue/1/staff/profiles' && method === 'POST') {
      const body = (await request.postDataJSON()) as Record<string, unknown>
      profileCreateRequests.push(body)
      const profile: StaffProfileFixture = {
        id: nextProfileId++,
        linkedUserId: null,
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
      await route.fulfill(jsonResponse(projectStaffProfile(profile)))
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
      const previousLinkedUserId = profile.linkedUserId
      if (typeof body.displayName === 'string') profile.displayName = body.displayName
      if (typeof body.roleLabel === 'string' || body.roleLabel === null) profile.roleLabel = body.roleLabel
      if (typeof body.subtype === 'string') profile.subtype = body.subtype
      if (typeof body.linkedUserId === 'number') profile.linkedUserId = body.linkedUserId
      if (body.unlinkUser === true) profile.linkedUserId = null
      if (typeof body.photoRef === 'string' || body.photoRef === null) profile.photoRef = body.photoRef
      if (typeof body.bio === 'string' || body.bio === null) profile.bio = body.bio
      if (Array.isArray(body.tags)) profile.tags = body.tags.map(String)
      profile.updatedAt = '2030-01-10T18:06:00Z'
      syncStaffMemberLinkState(previousLinkedUserId)
      syncStaffMemberLinkState(profile.linkedUserId)
      await route.fulfill(jsonResponse(projectStaffProfile(profile)))
      return
    }

    if (method === 'POST' && action === 'publish') {
      profile.isGuestVisible = true
      profile.publishedAt = '2030-01-10T18:07:00Z'
      profile.disabledAt = null
      syncStaffMemberLinkState(profile.linkedUserId)
      await route.fulfill(jsonResponse(projectStaffProfile(profile)))
      return
    }

    if (method === 'POST' && action === 'hide') {
      profile.isGuestVisible = false
      profile.disabledAt = '2030-01-10T18:08:00Z'
      syncStaffMemberLinkState(profile.linkedUserId)
      await route.fulfill(jsonResponse(projectStaffProfile(profile)))
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
    if (shouldHoldStaffDirectory) {
      shouldHoldStaffDirectory = false
      await staffDirectoryGate
    }
    if (staffDirectoryFailuresRemaining > 0) {
      staffDirectoryFailuresRemaining -= 1
      await route.fulfill(
        jsonResponse(
          { error: { code: 'DATABASE_UNAVAILABLE', message: 'Staff directory unavailable' } },
          503
        )
      )
      return
    }
    staffMembers.forEach((member) => syncStaffMemberLinkState(member.userId))
    const visibleMembers = role === 'MANAGER'
      ? staffMembers.filter((member) => member.active && member.role === 'STAFF')
      : staffMembers.filter((member) => member.active)
    await route.fulfill(jsonResponse({ members: visibleMembers }))
  })

  await page.route('**/api/venue/1/staff/invites**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET' && path === '/api/venue/1/staff/invites') {
      await route.fulfill(jsonResponse({ invites: pendingInvites }))
      return
    }
    const revokeMatch = path.match(/^\/api\/venue\/1\/staff\/invites\/([^/]+)\/revoke$/)
    if (request.method() === 'POST' && revokeMatch) {
      const handle = decodeURIComponent(revokeMatch[1])
      const existing = pendingInvites.find((invite) => invite.handle === handle)
      if (!existing) {
        await route.fulfill(jsonResponse({ error: { code: 'INVITE_NOT_PENDING', message: 'Invite is not pending' } }, 409))
        return
      }
      revokedInviteHandles.push(handle)
      pendingInvites = pendingInvites.filter((invite) => invite.handle !== handle)
      await route.fulfill(jsonResponse({ ok: true }))
      return
    }
    if (request.method() !== 'POST' || path !== '/api/venue/1/staff/invites') {
      await route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ error: 'not found' }) })
      return
    }
    const body = (await request.postDataJSON()) as { role?: string }
    staffInviteRequests.push(body)
    const inviteCode = 'ABC234'
    const startPayload = `staff_invite_${inviteCode}`
    const deepLink = `https://t.me/TestHookahBot?start=${startPayload}`
    const fallbackCommand = `/start ${startPayload}`
    const pendingRole = body.role === 'MANAGER' ? 'MANAGER' : 'STAFF'
    pendingInvites.push({
      handle: `pending-${staffInviteRequests.length}`,
      role: pendingRole,
      status: 'PENDING',
      createdAt: '2030-01-10T18:00:00Z',
      expiresAt: '2030-01-17T18:00:00Z'
    })
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
    getStaffInvites: () => staffInviteRequests.length,
    getStaffInviteRequests: () => [...staffInviteRequests],
    getPendingInvites: () => pendingInvites.map((invite) => ({ ...invite })),
    getRevokedInviteHandles: () => [...revokedInviteHandles],
    getProfileCreateRequests: () => profileCreateRequests,
    getProfileCreateFromMemberRequests: () => profileCreateFromMemberRequests,
    getProfileUpdateRequests: () => profileUpdateRequests,
    getProfileGetCalls: () => profileGetCalls,
    getStaffMembers: () => staffMembers.map((member) => ({ ...member })),
    addStaffProfile: (profile: StaffProfileFixture) => {
      staffProfiles.push({
        ...profile,
        tags: [...profile.tags],
        todayShift: profile.todayShift ? { ...profile.todayShift } : null
      })
      syncStaffMemberLinkState(profile.linkedUserId)
    },
    getShiftRequests: () => shiftRequests,
    setStaffModuleSettings: (settings: VenueStaffModuleSettingsFixture) => {
      staffModuleSettings = { ...settings }
    },
    deferNextProfileLoad: () => {
      let release = () => undefined
      const promise = new Promise<void>((resolve) => {
        release = resolve
      })
      deferredProfileLoads.push({ promise, release })
      return release
    },
    getTestMessages: () => testMessages,
    getUnlinks: () => unlinks,
    setLinked: (next: boolean) => {
      linked = next
    },
    releaseStaffDirectory: () => {
      releaseStaffDirectoryGate?.()
      releaseStaffDirectoryGate = null
    },
    queueProfileLinkConflict: (conflict: StaffProfileLinkConflictFixture) => {
      nextProfileLinkConflict = conflict
    },
    setAccountStaffState: (
      userId: number,
      members: VenueStaffMemberFixture[],
      profiles: StaffProfileFixture[] = []
    ) => {
      accessUserId = userId
      staffMembers.splice(0, staffMembers.length, ...members.map((member) => ({ ...member })))
      staffProfiles.splice(
        0,
        staffProfiles.length,
        ...profiles.map((profile) => ({
          ...profile,
          tags: [...profile.tags],
          todayShift: profile.todayShift ? { ...profile.todayShift } : null
        }))
      )
    }
  }
}

type VenueStaffScheduleAccessFixture = {
  venueId: number
  venueName: string
  venueCity: string
  venueStatus: string
  role: 'OWNER' | 'MANAGER' | 'STAFF'
  permissions: string[]
  teamScheduleModuleEnabled?: boolean
}

type VenueStaffScheduleAdminShiftFixture = {
  id: number
  staffProfileId: number
  displayName: string
  roleLabel?: string | null
  subtype: string
  shiftDate: string
  startsAt: string
  endsAt: string
  endsNextDay: boolean
  computedStatus: string | null
  cancelConfirmationState: string | null
  updatedAt: string
  storedStatus: string
  isGuestVisible: boolean
  manuallyMarkedActive: boolean
  restoreAllowed: boolean
}

type VenueStaffScheduleEffectiveHoursFixture = {
  serviceDate: string
  state: 'OPEN' | 'CLOSED' | 'NOT_CONFIGURED'
  opensAt?: string | null
  closesAt?: string | null
  endsNextDay: boolean
}

async function mockVenueStaffScheduleApi(
  page: Page,
  options: {
    accesses?: VenueStaffScheduleAccessFixture[]
    adminShifts?: Record<number, VenueStaffScheduleAdminShiftFixture[]>
    ownShifts?: Record<number, Array<Record<string, unknown>>>
    effectiveHours?: Record<number, VenueStaffScheduleEffectiveHoursFixture[]>
    timezones?: Record<number, string>
  } = {}
) {
  const accesses =
    options.accesses ??
    [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'OWNER' as const,
        permissions: ['STAFF_SCHEDULE_VIEW', 'STAFF_SCHEDULE_MANAGE']
      }
    ]
  const defaultAdminShift = (venueId: number): VenueStaffScheduleAdminShiftFixture => ({
    id: venueId * 1000 + 1,
    staffProfileId: venueId * 100 + 1,
    displayName: venueId === 1 ? 'Алексей' : 'Мария Второй',
    roleLabel: null,
    subtype: 'hookah_master',
    shiftDate: '2030-01-08',
    startsAt: '22:00',
    endsAt: '06:00',
    endsNextDay: true,
    computedStatus: 'scheduled',
    cancelConfirmationState: 'SCHEDULED',
    updatedAt: `2030-01-07T10:00:0${venueId}Z`,
    storedStatus: 'scheduled',
    isGuestVisible: false,
    manuallyMarkedActive: false,
    restoreAllowed: false
  })
  const adminShifts = new Map<number, VenueStaffScheduleAdminShiftFixture[]>(
    accesses.map((access) => [
      access.venueId,
      (
        options.adminShifts?.[access.venueId] ??
        [
          defaultAdminShift(access.venueId),
          {
            id: access.venueId * 1000 + 2,
            staffProfileId: access.venueId * 100 + 4,
            displayName: access.venueId === 1 ? 'Анна' : 'Анна Второй',
            roleLabel: 'Администратор зала',
            subtype: 'admin',
            shiftDate: '2030-01-07',
            startsAt: '19:00',
            endsAt: '01:00',
            endsNextDay: true,
            computedStatus: 'canceled',
            cancelConfirmationState: null,
            updatedAt: `2030-01-07T09:00:0${access.venueId}Z`,
            storedStatus: 'canceled',
            isGuestVisible: false,
            manuallyMarkedActive: false,
            restoreAllowed: true
          }
        ]
      ).map((shift) => ({ ...shift }))
    ])
  )
  const profiles = new Map(
    accesses.map((access) => [
      access.venueId,
      [
        {
          id: access.venueId * 100 + 1,
          linkedUserId: 123456789,
          linkageClass: 'STAFF_LINKED',
          canManage: true,
          isSelf: true,
          displayName: access.venueId === 1 ? 'Алексей' : 'Мария Второй',
          roleLabel: null,
          subtype: 'hookah_master',
          photoRef: null,
          bio: null,
          tags: [],
          isGuestVisible: false,
          publishedAt: null,
          disabledAt: null,
          createdAt: '2030-01-01T10:00:00Z',
          updatedAt: '2030-01-01T10:00:00Z',
          todayShift: null
        },
        {
          id: access.venueId * 100 + 2,
          linkedUserId: null,
          linkageClass: 'DISPLAY_ONLY',
          canManage: true,
          isSelf: false,
          displayName: access.venueId === 1 ? 'Светлана' : 'Илья Второй',
          roleLabel: 'Бармен',
          subtype: 'other',
          photoRef: null,
          bio: null,
          tags: [],
          isGuestVisible: false,
          publishedAt: null,
          disabledAt: null,
          createdAt: '2030-01-01T10:00:00Z',
          updatedAt: '2030-01-01T10:00:00Z',
          todayShift: null
        },
        {
          id: access.venueId * 100 + 3,
          linkedUserId: 987654321,
          linkageClass: 'STAFF_LINKED',
          canManage: true,
          isSelf: false,
          displayName: access.venueId === 1 ? 'Максим' : 'Максим Второй',
          roleLabel: null,
          subtype: 'waiter',
          photoRef: null,
          bio: null,
          tags: [],
          isGuestVisible: false,
          publishedAt: null,
          disabledAt: null,
          createdAt: '2030-01-01T10:00:00Z',
          updatedAt: '2030-01-01T10:00:00Z',
          todayShift: null
        },
        {
          id: access.venueId * 100 + 4,
          linkedUserId: null,
          linkageClass: 'DISPLAY_ONLY',
          canManage: true,
          isSelf: false,
          displayName: access.venueId === 1 ? 'Анна' : 'Анна Второй',
          roleLabel: 'Администратор зала',
          subtype: 'admin',
          photoRef: null,
          bio: null,
          tags: [],
          isGuestVisible: false,
          publishedAt: null,
          disabledAt: null,
          createdAt: '2030-01-01T10:00:00Z',
          updatedAt: '2030-01-01T10:00:00Z',
          todayShift: null
        }
      ]
    ])
  )
  const defaultOwnShifts: Array<Record<string, unknown>> = [
    {
      id: 1001,
      staffProfileId: 101,
      shiftDate: '2030-01-08',
      startsAt: '22:00',
      endsAt: '06:00',
      endsNextDay: true,
      computedStatus: 'scheduled',
      colleagues: [
        {
          staffProfileId: 102,
          displayName: 'Светлана',
          roleLabel: 'Бармен',
          subtype: 'other',
          shiftDate: '2030-01-09',
          startsAt: '01:00',
          endsAt: '05:00',
          endsNextDay: false,
          computedStatus: 'scheduled'
        }
      ]
    }
  ]
  const ownShifts = new Map(
    accesses.map((access) => [
      access.venueId,
      (options.ownShifts?.[access.venueId] ?? defaultOwnShifts).map((shift) => ({ ...shift }))
    ])
  )
  const defaultEffectiveHours: VenueStaffScheduleEffectiveHoursFixture[] = [
    {
      serviceDate: '2030-01-07',
      state: 'OPEN',
      opensAt: '18:00',
      closesAt: '02:00',
      endsNextDay: true
    },
    {
      serviceDate: '2030-01-08',
      state: 'OPEN',
      opensAt: '20:00',
      closesAt: '04:00',
      endsNextDay: true
    },
    {
      serviceDate: '2030-01-09',
      state: 'CLOSED',
      opensAt: null,
      closesAt: null,
      endsNextDay: false
    },
    {
      serviceDate: '2030-01-10',
      state: 'NOT_CONFIGURED',
      opensAt: null,
      closesAt: null,
      endsNextDay: false
    }
  ]
  const effectiveHours = new Map(
    accesses.map((access) => [
      access.venueId,
      new Map(
        (options.effectiveHours?.[access.venueId] ?? defaultEffectiveHours).map((hours) => [
          hours.serviceDate,
          { ...hours }
        ])
      )
    ])
  )
  const mutations: Array<{ venueId: number; method: string; path: string; body: Record<string, unknown> }> = []
  const listRequests: Array<{ venueId: number; path: string; from: string | null; to: string | null }> = []
  const mutationErrors: Array<{ status: number; code: string; message: string }> = []
  const deferredLoads = new Map<number, Array<{ promise: Promise<void>; release: () => void }>>()

  const deferNextList = (venueId: number) => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    const queue = deferredLoads.get(venueId) ?? []
    queue.push({ promise, release })
    deferredLoads.set(venueId, queue)
    return release
  }

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-session-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })

  await page.route('**/api/venue/me', async (route) => {
    await route.fulfill(jsonResponse({ userId: 123456789, venues: accesses }))
  })

  await page.route('**/api/venue/*/staff/profiles**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const venueId = Number(path.match(/^\/api\/venue\/(\d+)\/staff\/profiles$/)?.[1])
    if (route.request().method() !== 'GET' || !profiles.has(venueId)) {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }
    await route.fulfill(jsonResponse({ profiles: profiles.get(venueId) }))
  })

  await page.route('**/api/venue/*/staff/shifts**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const match = path.match(
      /^\/api\/venue\/(\d+)\/staff\/shifts(?:\/(me|batch|\d+))?(?:\/(cancel|restore))?$/
    )
    const venueId = Number(match?.[1])
    const resource = match?.[2] ?? null
    const action = match?.[3] ?? null
    const access = accesses.find((candidate) => candidate.venueId === venueId)
    if (!match || !access) {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }

    const canView = access.permissions.includes('STAFF_SCHEDULE_VIEW')
    const canViewOwn = access.permissions.includes('STAFF_SCHEDULE_VIEW_OWN')
    const canManage = access.permissions.includes('STAFF_SCHEDULE_MANAGE')

    if (request.method() === 'GET') {
      listRequests.push({
        venueId,
        path,
        from: url.searchParams.get('from'),
        to: url.searchParams.get('to')
      })
      const gate = deferredLoads.get(venueId)?.shift()
      await gate?.promise
      if (resource === 'me') {
        if (!canViewOwn) {
          await route.fulfill(jsonResponse({ error: { code: 'FORBIDDEN', message: 'Недостаточно прав.' } }, 403))
          return
        }
        await route.fulfill(
          jsonResponse({
            venueId,
            venueName: access.venueName,
            timezone: options.timezones?.[venueId] ?? 'Europe/Moscow',
            venueToday: '2030-01-07',
            from: url.searchParams.get('from'),
            to: url.searchParams.get('to'),
            shifts: (ownShifts.get(venueId) ?? []).filter((shift) => {
              const date = String(shift.shiftDate ?? '')
              const from = url.searchParams.get('from') ?? ''
              const to = url.searchParams.get('to') ?? ''
              return date >= from && date <= to
            })
          })
        )
        return
      }
      if (!canView) {
        await route.fulfill(jsonResponse({ error: { code: 'FORBIDDEN', message: 'Недостаточно прав.' } }, 403))
        return
      }
      await route.fulfill(
        jsonResponse({
          venueId,
          venueName: access.venueName,
          timezone: options.timezones?.[venueId] ?? 'Europe/Moscow',
          venueToday: '2030-01-07',
          from: url.searchParams.get('from'),
          to: url.searchParams.get('to'),
          shifts: (adminShifts.get(venueId) ?? []).filter((shift) => {
            const from = url.searchParams.get('from') ?? ''
            const to = url.searchParams.get('to') ?? ''
            return shift.shiftDate >= from && shift.shiftDate <= to
          }),
          effectiveHours: eachIsoDate(
            url.searchParams.get('from') ?? '2030-01-07',
            url.searchParams.get('to') ?? '2030-01-07'
          ).map(
            (serviceDate) =>
              effectiveHours.get(venueId)?.get(serviceDate) ?? {
                serviceDate,
                state: 'NOT_CONFIGURED',
                opensAt: null,
                closesAt: null,
                endsNextDay: false
              }
          )
        })
      )
      return
    }

    if (!canManage) {
      await route.fulfill(jsonResponse({ error: { code: 'FORBIDDEN', message: 'Недостаточно прав.' } }, 403))
      return
    }
    const body = ((await request.postDataJSON()) ?? {}) as Record<string, unknown>
    mutations.push({ venueId, method: request.method(), path, body })
    const queuedError = mutationErrors.shift()
    if (queuedError) {
      await route.fulfill(
        jsonResponse({ error: { code: queuedError.code, message: queuedError.message } }, queuedError.status)
      )
      return
    }
    const shifts = adminShifts.get(venueId) ?? []
    const venueProfiles = profiles.get(venueId) ?? []
    if (request.method() === 'POST' && resource === 'batch') {
      const rawAssignments = Array.isArray(body.assignments)
        ? (body.assignments as Array<Record<string, unknown>>)
        : []
      const working = shifts.map((shift) => ({ ...shift }))
      const responseShifts: VenueStaffScheduleAdminShiftFixture[] = []
      const keys = new Set<string>()
      let nextId = Math.max(venueId * 1000, ...working.map((shift) => shift.id)) + 1

      for (const [index, assignment] of rawAssignments.entries()) {
        const profileId = Number(assignment.staffProfileId)
        const shiftDate = String(assignment.shiftDate ?? '')
        const startsAt = String(assignment.startsAt ?? '')
        const endsAt = String(assignment.endsAt ?? '')
        const operation = String(assignment.operation ?? '').toUpperCase()
        const key = `${profileId}:${shiftDate}`
        const profile = venueProfiles.find((candidate) => candidate.id === profileId)
        if (!profile || keys.has(key)) {
          await route.fulfill(
            jsonResponse(
              { error: { code: 'INVALID_INPUT', message: 'Некорректное назначение смены.' } },
              400
            )
          )
          return
        }
        keys.add(key)
        const existing = working.find(
          (candidate) =>
            candidate.staffProfileId === profileId && candidate.shiftDate === shiftDate
        )
        if (operation === 'CREATE') {
          if (existing) {
            await route.fulfill(
              jsonResponse(
                {
                  error: {
                    code:
                      existing.storedStatus === 'canceled'
                        ? 'STAFF_SHIFT_CANCELED_CONFLICT'
                        : 'STAFF_SHIFT_DATE_CONFLICT',
                    message:
                      existing.storedStatus === 'canceled'
                        ? 'Смена на эту дату была отменена.'
                        : 'Смена уже запланирована на эту дату.'
                  }
                },
                409
              )
            )
            return
          }
          const created: VenueStaffScheduleAdminShiftFixture = {
            id: nextId++,
            staffProfileId: profileId,
            displayName: profile.displayName,
            roleLabel: profile.roleLabel,
            subtype: profile.subtype,
            shiftDate,
            startsAt,
            endsAt,
            endsNextDay: endsAt <= startsAt,
            computedStatus: 'scheduled',
            cancelConfirmationState: 'SCHEDULED',
            updatedAt: `2030-01-07T11:00:${String(index).padStart(2, '0')}Z`,
            storedStatus: 'scheduled',
            isGuestVisible: false,
            manuallyMarkedActive: false,
            restoreAllowed: false
          }
          working.push(created)
          responseShifts.push(created)
          continue
        }
        if (
          operation !== 'RESTORE' ||
          !existing ||
          existing.storedStatus !== 'canceled' ||
          !existing.restoreAllowed ||
          assignment.expectedUpdatedAt !== existing.updatedAt
        ) {
          await route.fulfill(
            jsonResponse(
              { error: { code: 'STAFF_SHIFT_STALE', message: 'График изменился.' } },
              409
            )
          )
          return
        }
        existing.startsAt = startsAt
        existing.endsAt = endsAt
        existing.endsNextDay = endsAt <= startsAt
        existing.computedStatus = 'scheduled'
        existing.storedStatus = 'scheduled'
        existing.cancelConfirmationState = 'SCHEDULED'
        existing.updatedAt = `2030-01-07T14:00:${String(index).padStart(2, '0')}Z`
        existing.restoreAllowed = false
        responseShifts.push(existing)
      }

      adminShifts.set(venueId, working)
      await route.fulfill(jsonResponse({ shifts: responseShifts }))
      return
    }

    let shift: VenueStaffScheduleAdminShiftFixture | undefined
    if (request.method() === 'POST' && resource == null) {
      const profileId = Number(body.staffProfileId)
      const profile = venueProfiles.find((candidate) => candidate.id === profileId)
      shift = {
        id: venueId * 1000 + shifts.length + 10,
        staffProfileId: profileId,
        displayName: profile?.displayName ?? 'Сотрудник',
        roleLabel: profile?.roleLabel,
        subtype: profile?.subtype ?? 'other',
        shiftDate: String(body.shiftDate),
        startsAt: String(body.startsAt),
        endsAt: String(body.endsAt),
        endsNextDay: String(body.endsAt) <= String(body.startsAt),
        computedStatus: 'scheduled',
        cancelConfirmationState: 'SCHEDULED',
        updatedAt: '2030-01-07T11:00:00Z',
        storedStatus: 'scheduled',
        isGuestVisible: false,
        manuallyMarkedActive: false,
        restoreAllowed: false
      }
      shifts.push(shift)
    } else {
      shift = shifts.find((candidate) => candidate.id === Number(resource))
      if (!shift) {
        await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
        return
      }
      if (request.method() === 'PUT') {
        shift.shiftDate = String(body.shiftDate)
        shift.startsAt = String(body.startsAt)
        shift.endsAt = String(body.endsAt)
        shift.endsNextDay = shift.endsAt <= shift.startsAt
        shift.updatedAt = '2030-01-07T12:00:00Z'
        shift.restoreAllowed = false
      } else if (request.method() === 'POST' && action === 'cancel') {
        shift.computedStatus = 'canceled'
        shift.storedStatus = 'canceled'
        shift.cancelConfirmationState = null
        shift.updatedAt = '2030-01-07T13:00:00Z'
        shift.restoreAllowed = true
      } else if (request.method() === 'POST' && action === 'restore') {
        if (
          shift.storedStatus !== 'canceled' ||
          !shift.restoreAllowed ||
          body.expectedUpdatedAt !== shift.updatedAt
        ) {
          await route.fulfill(
            jsonResponse(
              { error: { code: 'STAFF_SHIFT_STALE', message: 'График изменился.' } },
              409
            )
          )
          return
        }
        if (typeof body.startsAt === 'string') shift.startsAt = body.startsAt
        if (typeof body.endsAt === 'string') shift.endsAt = body.endsAt
        shift.endsNextDay = shift.endsAt <= shift.startsAt
        shift.computedStatus = 'scheduled'
        shift.storedStatus = 'scheduled'
        shift.cancelConfirmationState = 'SCHEDULED'
        shift.updatedAt = '2030-01-07T14:00:00Z'
        shift.restoreAllowed = false
      }
    }
    adminShifts.set(venueId, shifts)
    await route.fulfill(jsonResponse({ shift }))
  })

  return {
    getMutations: () => [...mutations],
    getBatchRequests: () => mutations.filter((mutation) => mutation.path.endsWith('/batch')),
    getRestoreRequests: () => mutations.filter((mutation) => mutation.path.endsWith('/restore')),
    getAdminShifts: (venueId: number) =>
      (adminShifts.get(venueId) ?? []).map((shift) => ({ ...shift })),
    getListRequests: () => [...listRequests],
    setEffectiveHours: (
      venueId: number,
      hours: VenueStaffScheduleEffectiveHoursFixture
    ) => {
      effectiveHours.get(venueId)?.set(hours.serviceDate, { ...hours })
    },
    queueMutationError: (error: { status: number; code: string; message: string }) => mutationErrors.push(error),
    deferNextList
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

const initialVenueMenuCategorySeeds = ['Кальянное меню', 'Напитки', 'Кухня'] as const

function appendMissingInitialVenueMenuCategories(
  categories: VenueMenuCategoryFixture[],
  allocateId: () => number
) {
  const existingNames = new Set(
    categories.map((category) => category.name.trim().toLocaleLowerCase('ru-RU'))
  )
  let nextSortOrder = Math.max(-1, ...categories.map((category) => category.sortOrder)) + 1
  initialVenueMenuCategorySeeds.forEach((name) => {
    const normalizedName = name.trim().toLocaleLowerCase('ru-RU')
    if (existingNames.has(normalizedName)) return
    existingNames.add(normalizedName)
    categories.push({
      id: allocateId(),
      name,
      sortOrder: nextSortOrder++,
      categoryType: 'OTHER',
      items: []
    })
  })
}

function buildMenuShiftCheckFixture(
  idOffset = 0,
  nameSuffix = ''
): VenueMenuCategoryFixture[] {
  return [
    {
      id: 30 + idOffset,
      name: `Кальянное меню${nameSuffix}`,
      sortOrder: 0,
      categoryType: 'HOOKAH',
      items: [
        {
          id: 310 + idOffset,
          categoryId: 30 + idOffset,
          name: `Кальян Ягодный${nameSuffix}`,
          priceMinor: 180000,
          currency: 'RUB',
          isAvailable: true,
          sortOrder: 0,
          effectiveItemType: 'HOOKAH',
          supportsBaseFlavorProfiles: true,
          options: [
            {
              id: 401 + idOffset,
              itemId: 310 + idOffset,
              name: `Яблоко${nameSuffix}`,
              priceDeltaMinor: 0,
              isAvailable: true,
              sortOrder: 0
            },
            {
              id: 402 + idOffset,
              itemId: 310 + idOffset,
              name: `Мята${nameSuffix}`,
              priceDeltaMinor: 10000,
              isAvailable: false,
              sortOrder: 1
            }
          ]
        },
        {
          id: 311 + idOffset,
          categoryId: 30 + idOffset,
          name: `Кальян Классический${nameSuffix}`,
          priceMinor: 170000,
          currency: 'RUB',
          isAvailable: false,
          sortOrder: 1,
          effectiveItemType: 'HOOKAH',
          supportsBaseFlavorProfiles: true,
          options: [
            {
              id: 403 + idOffset,
              itemId: 311 + idOffset,
              name: `Лёд${nameSuffix}`,
              priceDeltaMinor: 0,
              isAvailable: true,
              sortOrder: 0
            }
          ]
        }
      ]
    },
    {
      id: 31 + idOffset,
      name: `Напитки${nameSuffix}`,
      sortOrder: 1,
      categoryType: 'DRINK',
      items: [
        {
          id: 320 + idOffset,
          categoryId: 31 + idOffset,
          name: `Чай${nameSuffix}`,
          priceMinor: 30000,
          currency: 'RUB',
          isAvailable: true,
          sortOrder: 0,
          effectiveItemType: 'DRINK',
          supportsBaseFlavorProfiles: false,
          options: [
            {
              id: 410 + idOffset,
              itemId: 320 + idOffset,
              name: `Большой чайник${nameSuffix}`,
              priceDeltaMinor: 15000,
              isAvailable: false,
              sortOrder: 0
            }
          ]
        },
        {
          id: 321 + idOffset,
          categoryId: 31 + idOffset,
          name: `Лимонад${nameSuffix}`,
          priceMinor: 45000,
          currency: 'RUB',
          isAvailable: true,
          sortOrder: 1,
          effectiveItemType: 'DRINK',
          supportsBaseFlavorProfiles: false,
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
    otherAccountCategories?: VenueMenuCategoryFixture[]
    bootstrapSeedsDefaults?: boolean
    bootstrapErrors?: ApiErrorFixture[]
    menuErrors?: ApiErrorFixture[]
    deleteItemErrors?: Record<number, ApiErrorFixture>
    updateItemErrors?: Record<number, ApiErrorFixture>
  } = {}
) {
  const role = options.role ?? 'MANAGER'
  const permissions = options.permissions ?? ['MENU_VIEW', 'MENU_MANAGE', 'MENU_AVAILABILITY_MANAGE']
  const categories = options.categories ?? buildDefaultVenueMenu()
  const otherAccountCategories = options.otherAccountCategories ?? categories
  const deleteItemErrors = options.deleteItemErrors ?? {}
  const updateItemErrors = options.updateItemErrors ?? {}
  const bootstrapErrors = [...(options.bootstrapErrors ?? [])]
  const menuErrors = [...(options.menuErrors ?? [])]
  const createCategoryRequests: Array<{ name: string }> = []
  const updateCategoryRequests: Array<{ categoryId: number; name: string }> = []
  const reorderCategoryRequests: number[][] = []
  const createdCategoryIds: number[] = []
  const deleteItemRequests: number[] = []
  const createItemRequests: Array<{ categoryId: number; name: string; priceMinor: number; currency: string }> = []
  const updateItemRequests: Array<{ itemId: number; name?: string | null; priceMinor?: number | null }> = []
  const createOptionRequests: Array<{ itemId: number; name: string; priceDeltaMinor: number }> = []
  const updateOptionRequests: Array<{ optionId: number; name?: string | null; priceDeltaMinor?: number | null }> = []
  const deferredMenuLoads: Array<{ promise: Promise<void>; release: () => void }> = []
  const deferredBootstrapLoads: Array<{ promise: Promise<void>; release: () => void }> = []
  const venueMeUserIds: number[] = []
  const bootstrapRequests: Array<{ venueId: number; otherAccount: boolean }> = []
  let menuCalls = 0
  let settledMenuCalls = 0
  let bootstrapCalls = 0
  let settledBootstrapCalls = 0
  let createOptionCalls = 0
  let updateOptionCalls = 0
  let deleteOptionCalls = 0
  let availabilityCalls = 0
  let applyBaseFlavorProfileCalls = 0
  let createItemCalls = 0
  let updateItemCalls = 0
  let itemAvailabilityCalls = 0
  let nextCategoryId = 980
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
  const cloneCategories = (value: VenueMenuCategoryFixture[]) =>
    JSON.parse(JSON.stringify(value)) as VenueMenuCategoryFixture[]
  const deferNextMenuLoad = () => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    deferredMenuLoads.push({ promise, release })
    return release
  }
  const deferNextMenuBootstrap = () => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    deferredBootstrapLoads.push({ promise, release })
    return release
  }

  updateAllMissingBaseFlavorProfilesCount()

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-session-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })

  await page.route('**/api/venue/me', async (route) => {
    const userId = new URL(page.url()).searchParams.get('smokeUser') === 'other' ? 987654321 : 123456789
    venueMeUserIds.push(userId)
    await route.fulfill(
      jsonResponse({
        userId,
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

  await page.route('**/api/venue/menu/bootstrap?**', async (route) => {
    const request = route.request()
    const venueId = Number(new URL(request.url()).searchParams.get('venueId'))
    const otherAccount = new URL(page.url()).searchParams.get('smokeUser') === 'other'
    bootstrapCalls += 1
    bootstrapRequests.push({ venueId, otherAccount })
    const gate = deferredBootstrapLoads.shift()
    await gate?.promise
    try {
      if (request.method() !== 'POST' || venueId !== 1) {
        await route.fulfill(
          jsonResponse({ error: { code: 'NOT_FOUND', message: 'Меню не найдено.' } }, 404)
        )
        return
      }
      const queuedError = bootstrapErrors.shift()
      if (queuedError) {
        await route.fulfill(
          jsonResponse(
            {
              error: {
                code: queuedError.code ?? 'DATABASE_UNAVAILABLE',
                message: queuedError.message ?? 'Меню временно недоступно.'
              }
            },
            queuedError.status
          )
        )
        return
      }
      if (options.bootstrapSeedsDefaults) {
        appendMissingInitialVenueMenuCategories(
          otherAccount ? otherAccountCategories : categories,
          () => nextCategoryId++
        )
      }
      await route.fulfill(jsonResponse({ venueId }))
    } catch {
      // A disposed menu screen aborts its bootstrap during venue or account switching.
    } finally {
      settledBootstrapCalls += 1
    }
  })

  await page.route('**/api/venue/menu?**', async (route) => {
    menuCalls += 1
    const responseCategories =
      new URL(page.url()).searchParams.get('smokeUser') === 'other'
        ? otherAccountCategories
        : categories
    const snapshot = cloneCategories(responseCategories)
    const gate = deferredMenuLoads.shift()
    await gate?.promise
    try {
      const queuedError = menuErrors.shift()
      if (queuedError) {
        await route.fulfill(
          jsonResponse(
            {
              error: {
                code: queuedError.code ?? 'DATABASE_UNAVAILABLE',
                message: queuedError.message ?? 'Меню временно недоступно.'
              }
            },
            queuedError.status
          )
        )
        return
      }
      await route.fulfill(jsonResponse({ venueId: 1, categories: snapshot }))
    } catch {
      // A disposed menu screen aborts its own request during venue or account switching.
    } finally {
      settledMenuCalls += 1
    }
  })

  await page.route('**/api/venue/menu/categories**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()

    if (url.pathname === '/api/venue/menu/categories' && method === 'POST') {
      const body = (await request.postDataJSON()) as { name: string }
      createCategoryRequests.push({ name: body.name })
      const category: VenueMenuCategoryFixture = {
        id: nextCategoryId++,
        name: body.name,
        sortOrder: categories.length,
        categoryType: 'OTHER',
        items: []
      }
      categories.push(category)
      createdCategoryIds.push(category.id)
      await route.fulfill(jsonResponse(category))
      return
    }

    const categoryMatch = url.pathname.match(/\/api\/venue\/menu\/categories\/(\d+)$/)
    if (categoryMatch && method === 'PATCH') {
      const categoryId = Number(categoryMatch[1])
      const category = findCategory(categoryId)
      const body = (await request.postDataJSON()) as { name: string }
      if (!category) {
        await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Категория не найдена.' } }, 404))
        return
      }
      updateCategoryRequests.push({ categoryId, name: body.name })
      category.name = body.name
      await route.fulfill(jsonResponse(category))
      return
    }

    await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
  })

  await page.route('**/api/venue/menu/reorder/categories**', async (route) => {
    const request = route.request()
    if (request.method() !== 'POST') {
      await route.fulfill({ status: 405, contentType: 'application/json', body: JSON.stringify({ error: 'unsupported' }) })
      return
    }
    const body = (await request.postDataJSON()) as { categoryIds: number[] }
    reorderCategoryRequests.push([...body.categoryIds])
    const categoriesById = new Map(categories.map((category) => [category.id, category]))
    const reordered = body.categoryIds
      .map((categoryId) => categoriesById.get(categoryId) ?? null)
      .filter((category): category is VenueMenuCategoryFixture => category !== null)
    if (reordered.length !== categories.length) {
      await route.fulfill(jsonResponse({ error: { code: 'INVALID_REQUEST', message: 'Неверный порядок.' } }, 400))
      return
    }
    reordered.forEach((category, index) => {
      category.sortOrder = index
    })
    categories.splice(0, categories.length, ...reordered)
    await route.fulfill(jsonResponse({ ok: true }))
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
      createItemRequests.push({
        categoryId: body.categoryId,
        name: body.name,
        priceMinor: body.priceMinor,
        currency: body.currency
      })
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
    if (itemMatch && method === 'DELETE') {
      const itemId = Number(itemMatch[1])
      deleteItemRequests.push(itemId)
      const item = findItem(itemId)
      if (!permissions.includes('MENU_MANAGE')) {
        await route.fulfill(
          jsonResponse({ error: { code: 'FORBIDDEN', message: 'Недостаточно прав.' } }, 403)
        )
        return
      }
      if (!item) {
        await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Позиция не найдена.' } }, 404))
        return
      }
      const error = deleteItemErrors[itemId]
      if (error) {
        await route.fulfill(
          jsonResponse(
            {
              error: {
                code: error.code ?? 'INTERNAL_ERROR',
                message: error.message ?? 'Не удалось удалить позицию.'
              }
            },
            error.status
          )
        )
        return
      }
      categories.forEach((category) => {
        category.items = category.items.filter((candidate) => candidate.id !== itemId)
      })
      await route.fulfill(jsonResponse({ ok: true }))
      return
    }
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
      updateItemRequests.push({ itemId: item.id, name: body.name, priceMinor: body.priceMinor })
      const error = updateItemErrors[item.id]
      if (error) {
        await route.fulfill(
          jsonResponse(
            { error: { code: error.code ?? 'INTERNAL_ERROR', message: error.message ?? 'Не удалось обновить позицию.' } },
            error.status
          )
        )
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
      createOptionRequests.push({
        itemId: body.itemId,
        name: body.name,
        priceDeltaMinor: body.priceDeltaMinor
      })
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
      updateOptionRequests.push({
        optionId: option.id,
        name: body.name,
        priceDeltaMinor: body.priceDeltaMinor
      })
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
    getOtherAccountCategories: () => otherAccountCategories,
    getCreateCategoryRequests: () => [...createCategoryRequests],
    getUpdateCategoryRequests: () => [...updateCategoryRequests],
    getReorderCategoryRequests: () => reorderCategoryRequests.map((request) => [...request]),
    getCreatedCategoryIds: () => [...createdCategoryIds],
    getCreateOptionCalls: () => createOptionCalls,
    getUpdateOptionCalls: () => updateOptionCalls,
    getDeleteOptionCalls: () => deleteOptionCalls,
    getAvailabilityCalls: () => availabilityCalls,
    getApplyBaseFlavorProfileCalls: () => applyBaseFlavorProfileCalls,
    getCreateItemCalls: () => createItemCalls,
    getUpdateItemCalls: () => updateItemCalls,
    getCreateItemRequests: () => [...createItemRequests],
    getUpdateItemRequests: () => [...updateItemRequests],
    getCreateOptionRequests: () => [...createOptionRequests],
    getUpdateOptionRequests: () => [...updateOptionRequests],
    getItemAvailabilityCalls: () => itemAvailabilityCalls,
    getDeleteItemRequests: () => [...deleteItemRequests],
    getMenuCalls: () => menuCalls,
    getSettledMenuCalls: () => settledMenuCalls,
    getBootstrapCalls: () => bootstrapCalls,
    getSettledBootstrapCalls: () => settledBootstrapCalls,
    getBootstrapRequests: () => [...bootstrapRequests],
    getVenueMeUserIds: () => [...venueMeUserIds],
    deferNextMenuLoad,
    deferNextMenuBootstrap
  }
}

async function mockVenueMenuShiftCheckApi(
  page: Page,
  options: {
    accesses?: VenueMenuShiftCheckAccessFixture[]
    shiftCheckErrors?: ApiErrorFixture[]
    bootstrapSeedsDefaults?: boolean
  } = {}
) {
  const accesses =
    options.accesses ??
    [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER' as const,
        permissions: ['MENU_VIEW', 'MENU_MANAGE', 'MENU_AVAILABILITY_MANAGE', 'MENU_SHIFT_CHECK'],
        categories: buildDefaultVenueMenu()
      }
    ]
  const categoriesByVenue = new Map(accesses.map((access) => [access.venueId, access.categories]))
  const shiftCheckErrors = [...(options.shiftCheckErrors ?? [])]
  const shiftCheckRequests: Array<{ venueId: number; body: VenueMenuShiftCheckRequestFixture }> = []
  const bootstrapRequests: number[] = []
  const settledBootstrapRequests: number[] = []
  const menuRequests: number[] = []
  const settledMenuRequests: number[] = []
  let itemAvailabilityCalls = 0
  let optionAvailabilityCalls = 0

  type DeferredMenuResponse = {
    promise: Promise<void>
    release: () => void
  }
  const deferredMenuResponses = new Map<number, DeferredMenuResponse[]>()
  const deferredBootstrapResponses = new Map<number, DeferredMenuResponse[]>()
  let nextBootstrapCategoryId = 8000
  const cloneCategories = (categories: VenueMenuCategoryFixture[]) =>
    JSON.parse(JSON.stringify(categories)) as VenueMenuCategoryFixture[]
  const allItems = (venueId: number) =>
    (categoriesByVenue.get(venueId) ?? []).flatMap((category) => category.items)
  const allOptions = (venueId: number) => allItems(venueId).flatMap((item) => item.options)
  const findItem = (venueId: number, itemId: number) =>
    allItems(venueId).find((item) => item.id === itemId) ?? null
  const findOption = (venueId: number, optionId: number) =>
    allOptions(venueId).find((option) => option.id === optionId) ?? null
  const menuResponse = (
    venueId: number,
    changedItemCount = 0,
    changedOptionCount = 0
  ) => {
    const categories = categoriesByVenue.get(venueId) ?? []
    const items = allItems(venueId)
    const menuOptions = allOptions(venueId)
    return {
      venueId,
      categories,
      changedItemCount,
      changedOptionCount,
      reviewedItemCount: items.length,
      reviewedOptionCount: menuOptions.length,
      availableItemCount: items.filter((item) => item.isAvailable).length,
      availableOptionCount: menuOptions.filter((option) => option.isAvailable).length
    }
  }
  const errorEnvelope = (error: ApiErrorFixture) =>
    jsonResponse(
      {
        error: {
          code:
            error.code ??
            (error.status === 403
              ? 'FORBIDDEN'
              : error.status === 409
                ? 'MENU_SHIFT_CHECK_STALE'
                : 'INTERNAL_ERROR'),
          message:
            error.message ??
            (error.status === 409
              ? 'Меню изменилось. Обновите проверку и повторите подтверждение.'
              : 'Не удалось завершить проверку меню.')
        }
      },
      error.status
    )
  const deferNextMenuLoad = (venueId: number) => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    const queue = deferredMenuResponses.get(venueId) ?? []
    queue.push({ promise, release })
    deferredMenuResponses.set(venueId, queue)
    return release
  }
  const deferNextMenuBootstrap = (venueId: number) => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    const queue = deferredBootstrapResponses.get(venueId) ?? []
    queue.push({ promise, release })
    deferredBootstrapResponses.set(venueId, queue)
    return release
  }

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-session-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })

  await page.route('**/api/venue/me', async (route) => {
    await route.fulfill(
      jsonResponse({
        userId: 123456789,
        venues: accesses.map(({ categories: _categories, ...access }) => access)
      })
    )
  })

  await page.route('**/api/venue/menu/bootstrap?**', async (route) => {
    const request = route.request()
    const venueId = Number(new URL(request.url()).searchParams.get('venueId'))
    const access = accesses.find((candidate) => candidate.venueId === venueId)
    if (
      request.method() !== 'POST' ||
      !access ||
      (access.role !== 'OWNER' && access.role !== 'MANAGER') ||
      !access.permissions.includes('MENU_MANAGE')
    ) {
      await route.fulfill(
        errorEnvelope({ status: 403, code: 'FORBIDDEN', message: 'Недостаточно прав.' })
      )
      return
    }
    bootstrapRequests.push(venueId)
    const gate = deferredBootstrapResponses.get(venueId)?.shift()
    await gate?.promise
    try {
      if (options.bootstrapSeedsDefaults) {
        appendMissingInitialVenueMenuCategories(access.categories, () => nextBootstrapCategoryId++)
      }
      await route.fulfill(jsonResponse({ venueId }))
    } catch {
      // A venue switch aborts the disposed screen bootstrap; the late response is intentionally ignored.
    } finally {
      settledBootstrapRequests.push(venueId)
    }
  })

  await page.route('**/api/venue/menu?**', async (route) => {
    const request = route.request()
    const venueId = Number(new URL(request.url()).searchParams.get('venueId'))
    const categories = categoriesByVenue.get(venueId)
    if (request.method() !== 'GET' || !categories) {
      await route.fulfill(errorEnvelope({ status: 404, code: 'NOT_FOUND', message: 'Меню не найдено.' }))
      return
    }
    menuRequests.push(venueId)
    const snapshot = cloneCategories(categories)
    const gate = deferredMenuResponses.get(venueId)?.shift()
    await gate?.promise
    try {
      await route.fulfill(jsonResponse({ venueId, categories: snapshot }))
    } catch {
      // A venue switch aborts the disposed screen request; the late fixture response is intentionally ignored.
    } finally {
      settledMenuRequests.push(venueId)
    }
  })

  await page.route('**/api/venue/menu/items/*/availability**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const venueId = Number(url.searchParams.get('venueId'))
    const itemId = Number(url.pathname.match(/\/items\/(\d+)\/availability$/)?.[1])
    const access = accesses.find((candidate) => candidate.venueId === venueId)
    const item = findItem(venueId, itemId)
    if (
      request.method() !== 'PATCH' ||
      !access?.permissions.includes('MENU_AVAILABILITY_MANAGE') ||
      !item
    ) {
      await route.fulfill(errorEnvelope({ status: item ? 403 : 404 }))
      return
    }
    const body = (await request.postDataJSON()) as { isAvailable: boolean }
    itemAvailabilityCalls += 1
    item.isAvailable = body.isAvailable
    await route.fulfill(jsonResponse(item))
  })

  await page.route('**/api/venue/menu/options/*/availability**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const venueId = Number(url.searchParams.get('venueId'))
    const optionId = Number(url.pathname.match(/\/options\/(\d+)\/availability$/)?.[1])
    const access = accesses.find((candidate) => candidate.venueId === venueId)
    const menuOption = findOption(venueId, optionId)
    if (
      request.method() !== 'PATCH' ||
      !access?.permissions.includes('MENU_AVAILABILITY_MANAGE') ||
      !menuOption
    ) {
      await route.fulfill(errorEnvelope({ status: menuOption ? 403 : 404 }))
      return
    }
    const body = (await request.postDataJSON()) as { isAvailable: boolean }
    optionAvailabilityCalls += 1
    menuOption.isAvailable = body.isAvailable
    await route.fulfill(jsonResponse(menuOption))
  })

  await page.route('**/api/venue/menu/shift-check?**', async (route) => {
    const request = route.request()
    const venueId = Number(new URL(request.url()).searchParams.get('venueId'))
    const access = accesses.find((candidate) => candidate.venueId === venueId)
    if (
      request.method() !== 'POST' ||
      !access ||
      (access.role !== 'OWNER' && access.role !== 'MANAGER') ||
      !access.permissions.includes('MENU_SHIFT_CHECK')
    ) {
      await route.fulfill(errorEnvelope({ status: 403, code: 'FORBIDDEN', message: 'Недостаточно прав.' }))
      return
    }

    const body = (await request.postDataJSON()) as VenueMenuShiftCheckRequestFixture
    shiftCheckRequests.push({
      venueId,
      body: JSON.parse(JSON.stringify(body)) as VenueMenuShiftCheckRequestFixture
    })
    const queuedError = shiftCheckErrors.shift()
    if (queuedError) {
      await route.fulfill(errorEnvelope(queuedError))
      return
    }

    const duplicateItemIds = new Set<number>()
    const duplicateOptionIds = new Set<number>()
    const invalidItemChange = body.items.some((change) => {
      if (duplicateItemIds.has(change.itemId)) return true
      duplicateItemIds.add(change.itemId)
      const item = findItem(venueId, change.itemId)
      return (
        !item ||
        item.isAvailable !== change.expectedIsAvailable ||
        change.expectedIsAvailable === change.desiredIsAvailable
      )
    })
    const invalidOptionChange = body.options.some((change) => {
      if (duplicateOptionIds.has(change.optionId)) return true
      duplicateOptionIds.add(change.optionId)
      const menuOption = findOption(venueId, change.optionId)
      return (
        !menuOption ||
        menuOption.itemId !== change.itemId ||
        menuOption.isAvailable !== change.expectedIsAvailable ||
        change.expectedIsAvailable === change.desiredIsAvailable
      )
    })
    if (invalidItemChange || invalidOptionChange) {
      await route.fulfill(
        errorEnvelope({
          status: 409,
          code: 'MENU_SHIFT_CHECK_STALE',
          message: 'Меню изменилось. Обновите проверку и повторите подтверждение.'
        })
      )
      return
    }

    body.items.forEach((change) => {
      const item = findItem(venueId, change.itemId)
      if (item) item.isAvailable = change.desiredIsAvailable
    })
    body.options.forEach((change) => {
      const menuOption = findOption(venueId, change.optionId)
      if (menuOption) menuOption.isAvailable = change.desiredIsAvailable
    })
    await route.fulfill(jsonResponse(menuResponse(venueId, body.items.length, body.options.length)))
  })

  return {
    getCategories: (venueId = 1) => categoriesByVenue.get(venueId) ?? [],
    getBootstrapRequests: () => [...bootstrapRequests],
    getSettledBootstrapRequests: () => [...settledBootstrapRequests],
    getMenuRequests: () => [...menuRequests],
    getSettledMenuRequests: () => [...settledMenuRequests],
    getShiftCheckRequests: () => [...shiftCheckRequests],
    getItemAvailabilityCalls: () => itemAvailabilityCalls,
    getOptionAvailabilityCalls: () => optionAvailabilityCalls,
    queueShiftCheckError: (error: ApiErrorFixture) => {
      shiftCheckErrors.push(error)
    },
    deferNextMenuLoad,
    deferNextMenuBootstrap,
    setItemAvailability: (venueId: number, itemId: number, isAvailable: boolean) => {
      const item = findItem(venueId, itemId)
      if (item) item.isAvailable = isAvailable
    },
    setOptionAvailability: (venueId: number, optionId: number, isAvailable: boolean) => {
      const menuOption = findOption(venueId, optionId)
      if (menuOption) menuOption.isAvailable = isAvailable
    }
  }
}

type VenueOwnershipAccessFixture = {
  venueId: number
  venueName: string
  venueCity: string
  venueStatus: string
  role: 'OWNER' | 'MANAGER' | 'STAFF'
  permissions: string[]
}

type VenueOwnershipApplicationFixture = {
  id: number
  venueName: string
  city: string
  contact: string
  comment: string | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
  createdAt: string
  linkedVenueId: number | null
}

type OwnershipMutationFixture = {
  method: string
  path: string
  body?: Record<string, unknown>
}

async function mockVenueOwnershipApi(
  page: Page,
  options: {
    accesses: VenueOwnershipAccessFixture[]
    applications?: VenueOwnershipApplicationFixture[]
    otherAccount?: {
      accesses: VenueOwnershipAccessFixture[]
      applications?: VenueOwnershipApplicationFixture[]
    }
  }
) {
  type AccountState = {
    accesses: VenueOwnershipAccessFixture[]
    venues: Array<Omit<VenueOwnershipAccessFixture, 'role' | 'permissions'>>
    applications: VenueOwnershipApplicationFixture[]
  }
  const primaryUserId = 123456789
  const otherUserId = 987654321
  const buildAccountState = (
    accesses: VenueOwnershipAccessFixture[],
    applications: VenueOwnershipApplicationFixture[] = []
  ): AccountState => {
    const clonedAccesses = accesses.map((access) => ({ ...access, permissions: [...access.permissions] }))
    return {
      accesses: clonedAccesses,
      venues: clonedAccesses
        .filter((access) => access.role === 'OWNER')
        .map(({ venueId, venueName, venueCity, venueStatus }) => ({ venueId, venueName, venueCity, venueStatus })),
      applications: applications.map((application) => ({ ...application }))
    }
  }
  const accounts = new Map<number, AccountState>([
    [primaryUserId, buildAccountState(options.accesses, options.applications)],
    [
      otherUserId,
      buildAccountState(options.otherAccount?.accesses ?? [], options.otherAccount?.applications)
    ]
  ])
  const allInitialApplications = [...accounts.values()].flatMap((account) => account.applications)
  let nextApplicationId = Math.max(0, ...allInitialApplications.map((application) => application.id)) + 1
  let ownershipGetCalls = 0
  let applicationSubmitCalls = 0
  let settledApplicationSubmitCalls = 0
  let failNextApplicationSubmit = false
  let loseNextApplicationSubmitResponse = false
  const deferredApplicationSubmitResponses: Array<{ promise: Promise<void>; release: () => void }> = []
  const mutations: OwnershipMutationFixture[] = []

  const requestUserId = () =>
    new URL(page.url()).searchParams.get('smokeUser') === 'other' ? otherUserId : primaryUserId
  const accountFor = (userId: number) => {
    const account = accounts.get(userId)
    if (!account) throw new Error(`Missing ownership fixture account ${userId}`)
    return account
  }
  const normalizeTupleField = (value: unknown) =>
    String(value ?? '').normalize('NFKC').trim().replace(/\s+/gu, ' ').toLowerCase()
  const canonicalTuple = (value: {
    venueName: unknown
    city: unknown
    contact: unknown
    comment: unknown
  }) =>
    [value.venueName, value.city, value.contact, value.comment]
      .map(normalizeTupleField)
      .join('\u001f')
  const canParticipateInRetry = (application: VenueOwnershipApplicationFixture) =>
    application.status === 'PENDING' ||
    (application.status === 'APPROVED' && application.linkedVenueId == null)
  const deferNextSubmitResponse = () => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    deferredApplicationSubmitResponses.push({ promise, release })
    return release
  }

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-ownership-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })
  await page.route('**/api/venue/me', async (route) => {
    const userId = requestUserId()
    await route.fulfill(jsonResponse({ userId, venues: accountFor(userId).accesses }))
  })
  await page.route('**/api/venue/ownership**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()
    const userId = requestUserId()
    const account = accountFor(userId)
    if (path === '/api/venue/ownership' && method === 'GET') {
      ownershipGetCalls += 1
      await route.fulfill(
        jsonResponse({ userId, venues: account.venues, applications: account.applications })
      )
      return
    }

    const applicationMatch = path.match(/^\/api\/venue\/ownership\/applications(?:\/(\d+))?(?:\/(cancel))?$/)
    if (!applicationMatch) {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }

    const applicationId = applicationMatch[1] ? Number(applicationMatch[1]) : null
    const action = applicationMatch[2]
    if (applicationId == null && method === 'POST') {
      applicationSubmitCalls += 1
      if (failNextApplicationSubmit) {
        failNextApplicationSubmit = false
        await route.abort('failed')
        return
      }
      const body = (await request.postDataJSON()) as Record<string, unknown>
      const existing = account.applications.find(
        (application) =>
          canParticipateInRetry(application) && canonicalTuple(application) === canonicalTuple({
            venueName: body.venueName,
            city: body.city,
            contact: body.contact,
            comment: body.comment
          })
      )
      let application = existing
      let created = false
      if (!application) {
        application = {
          id: nextApplicationId++,
          venueName: String(body.venueName),
          city: String(body.city),
          contact: String(body.contact),
          comment: body.comment == null ? null : String(body.comment),
          status: 'PENDING',
          createdAt: '2030-01-10T18:00:00Z',
          linkedVenueId: null
        }
        account.applications = [...account.applications, application]
        mutations.push({ method, path, body })
        created = true
      }
      const shouldLoseResponse = loseNextApplicationSubmitResponse
      loseNextApplicationSubmitResponse = false
      const gate = deferredApplicationSubmitResponses.shift()
      await gate?.promise
      try {
        if (shouldLoseResponse) {
          await route.abort('failed')
        } else {
          await route.fulfill(jsonResponse({ application, created }))
        }
      } catch {
        // Account replacement or navigation may dispose the screen before the authoritative response arrives.
      } finally {
        settledApplicationSubmitCalls += 1
      }
      return
    }

    const application = account.applications.find((candidate) => candidate.id === applicationId)
    if (!application) {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }
    if (method === 'PUT' && !action) {
      const body = (await request.postDataJSON()) as Record<string, unknown>
      Object.assign(application, {
        venueName: String(body.venueName),
        city: String(body.city),
        contact: String(body.contact),
        comment: body.comment == null ? null : String(body.comment)
      })
      mutations.push({ method, path, body })
      await route.fulfill(jsonResponse({ application }))
      return
    }
    if (method === 'POST' && action === 'cancel') {
      application.status = 'CANCELLED'
      mutations.push({ method, path })
      await route.fulfill(jsonResponse({ application }))
      return
    }
    await route.fulfill(jsonResponse({ error: { code: 'METHOD_NOT_ALLOWED', message: 'Unsupported' } }, 405))
  })

  return {
    getApplications: (userId = primaryUserId) =>
      accountFor(userId).applications.map((application) => ({ ...application })),
    getMutations: () => mutations.map((mutation) => ({ ...mutation })),
    getOwnershipGetCalls: () => ownershipGetCalls,
    getApplicationSubmitCalls: () => applicationSubmitCalls,
    getSettledApplicationSubmitCalls: () => settledApplicationSubmitCalls,
    failNextSubmit: () => {
      failNextApplicationSubmit = true
    },
    loseNextSubmitResult: () => {
      loseNextApplicationSubmitResponse = true
    },
    deferNextSubmitResponse,
    setAccesses: (nextAccesses: VenueOwnershipAccessFixture[]) => {
      accountFor(primaryUserId).accesses = nextAccesses.map((access) => ({
        ...access,
        permissions: [...access.permissions]
      }))
    },
    setVenues: (nextVenues: Array<Omit<VenueOwnershipAccessFixture, 'role' | 'permissions'>>) => {
      accountFor(primaryUserId).venues = nextVenues.map((venue) => ({ ...venue }))
    }
  }
}

async function fillVenueOwnershipApplication(
  page: Page,
  values: { venueName: string; city: string; contact: string; comment?: string }
) {
  await page.getByLabel('Название заведения').fill(values.venueName)
  await page.getByLabel('Город', { exact: true }).fill(values.city)
  await page.getByLabel('Контакт для связи').fill(values.contact)
  await page.getByLabel('Комментарий (необязательно)').fill(values.comment ?? '')
}

type PlatformOnboardingRequestFixture = {
  id: number
  applicant: {
    userId: number
    username: string | null
    firstName: string | null
    lastName: string | null
  }
  venueName: string
  city: string
  contact: string
  comment: string | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
  createdAt: string
  linkedVenueId: number | null
  trialConfigured: boolean
  trialEndsOn: string | null
  currentPriceRub: number | null
  futurePriceRub: number | null
  futurePriceEffectiveOn: string | null
  commercialNote: string | null
}

async function mockPlatformOwnershipApi(
  page: Page,
  options: { platformAllowed?: boolean; ownerVenueCounts?: number[] } = {}
) {
  let request: PlatformOnboardingRequestFixture = {
    id: 701,
    applicant: {
      userId: 501,
      username: 'anna_owner',
      firstName: 'Анна',
      lastName: 'Иванова'
    },
    venueName: 'Дымный берег',
    city: 'Казань',
    contact: '@anna_owner',
    comment: 'Нужна миграция меню',
    status: 'PENDING',
    createdAt: '2030-01-10T18:00:00Z',
    linkedVenueId: null,
    trialConfigured: false,
    trialEndsOn: null,
    currentPriceRub: null,
    futurePriceRub: null,
    futurePriceEffectiveOn: null,
    commercialNote: null
  }
  const owner = {
    userId: 501,
    username: 'anna_owner',
    firstName: 'Анна',
    lastName: 'Иванова',
    venueCount: 2,
    venueStatusCounts: { PUBLISHED: 1, DRAFT: 1 }
  }
  const owners = options.ownerVenueCounts?.map((venueCount, index) => ({
    userId: 600 + index,
    username: `owner_${venueCount}`,
    firstName: 'Владелец',
    lastName: String(venueCount),
    venueCount,
    venueStatusCounts: { PUBLISHED: venueCount }
  })) ?? [owner]
  const ownerVenues = [
    { id: 1, name: 'Микс', city: 'Москва', status: 'PUBLISHED', createdAt: '2029-01-01T00:00:00Z' },
    { id: 2, name: 'Дымный берег', city: 'Казань', status: 'DRAFT', createdAt: '2030-01-10T00:00:00Z' }
  ]
  const mutations: OwnershipMutationFixture[] = []
  const deferredApprovals: Array<{ promise: Promise<void>; release: () => void }> = []
  const createLinkCreatedResults: boolean[] = []
  let approvalCalls = 0
  let createLinkCalls = 0
  let onboardingCalls = 0
  let loseNextCreateLinkResponse = false

  const deferNextApproval = () => {
    let release = () => undefined
    const promise = new Promise<void>((resolve) => {
      release = resolve
    })
    deferredApprovals.push({ promise, release })
    return { release }
  }

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-platform-ownership-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })
  await page.route('**/api/platform/me', async (route) => {
    if (options.platformAllowed === false) {
      await route.fulfill(
        jsonResponse({ error: { code: 'FORBIDDEN', message: 'Недостаточно прав.' } }, 403)
      )
      return
    }
    await route.fulfill(jsonResponse({ ok: true, ownerUserId: 123456789 }))
  })
  await page.route('**/api/platform/venues?**', async (route) => {
    await route.fulfill(
      jsonResponse({
        venues: [
          {
            id: 1,
            name: 'Микс',
            city: 'Москва',
            status: 'PUBLISHED',
            createdAt: '2029-01-01T00:00:00Z',
            ownersCount: 3,
            owners: [
              { userId: 501, role: 'OWNER', username: 'anna_owner', firstName: 'Анна', lastName: 'Иванова' },
              { userId: 502, role: 'OWNER', username: 'co_owner', firstName: null, lastName: null },
              { userId: 503, role: 'OWNER', username: null, firstName: null, lastName: null }
            ],
            subscriptionSummary: null
          }
        ]
      })
    )
  })
  await page.route('**/api/platform/venues/2', async (route) => {
    await route.fulfill(
      jsonResponse({
        venue: {
          id: 2,
          name: 'Дымный берег',
          city: 'Казань',
          address: null,
          status: 'DRAFT',
          createdAt: '2030-01-10T00:00:00Z',
          deletedAt: null
        },
        owners: [
          { userId: 501, role: 'OWNER', username: 'anna_owner', firstName: 'Анна', lastName: 'Иванова' }
        ],
        subscriptionSummary: null
      })
    )
  })
  await page.route('**/api/platform/venues/2/subscription', async (route) => {
    await route.fulfill(
      jsonResponse({
        settings: {
          trialEndDate: '2030-02-01',
          paidStartDate: null,
          basePriceMinor: 220000,
          priceOverrideMinor: null,
          currency: 'RUB'
        },
        schedule: [],
        effectivePriceToday: { priceMinor: 220000, currency: 'RUB' }
      })
    )
  })
  await page.route('**/api/platform/venues/2/billing', async (route) => {
    await route.fulfill(jsonResponse(buildBillingOverview({ venueId: 2 })))
  })
  await page.route('**/api/platform/onboarding/requests**', async (route) => {
    onboardingCalls += 1
    const apiRequest = route.request()
    const path = new URL(apiRequest.url()).pathname
    const method = apiRequest.method()
    const match = path.match(/^\/api\/platform\/onboarding\/requests(?:\/(\d+))?(?:\/(approve|reject|close|commercial-terms|create-and-link))?$/)
    if (!match) {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }
    if (!match[1] && method === 'GET') {
      await route.fulfill(jsonResponse({ requests: [request] }))
      return
    }
    if (Number(match[1]) !== request.id) {
      await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
      return
    }
    const action = match[2]
    if (!action && method === 'GET') {
      await route.fulfill(jsonResponse({ request }))
      return
    }
    if (method === 'POST' && action === 'approve') {
      approvalCalls += 1
      const gate = deferredApprovals.shift()
      if (gate) await gate.promise
      request = { ...request, status: 'APPROVED' }
      mutations.push({ method, path })
      await route.fulfill(jsonResponse({ request }))
      return
    }
    if (method === 'POST' && action === 'reject') {
      if (request.status !== 'PENDING') {
        await route.fulfill(
          jsonResponse({ error: { code: 'INVALID_TRANSITION', message: 'Недопустимый переход.' } }, 409)
        )
        return
      }
      request = { ...request, status: 'REJECTED' }
      mutations.push({ method, path })
      await route.fulfill(jsonResponse({ request }))
      return
    }
    if (method === 'POST' && action === 'close') {
      if (request.status !== 'APPROVED' || request.linkedVenueId != null) {
        await route.fulfill(
          jsonResponse({ error: { code: 'INVALID_TRANSITION', message: 'Недопустимый переход.' } }, 409)
        )
        return
      }
      request = { ...request, status: 'CANCELLED' }
      mutations.push({ method, path })
      await route.fulfill(jsonResponse({ request }))
      return
    }
    if (method === 'PUT' && action === 'commercial-terms') {
      const body = (await apiRequest.postDataJSON()) as Record<string, unknown>
      request = {
        ...request,
        trialConfigured: Boolean(body.trialConfigured),
        trialEndsOn: body.trialEndsOn == null ? null : String(body.trialEndsOn),
        currentPriceRub: Number(body.currentPriceRub),
        futurePriceRub: body.futurePriceRub == null ? null : Number(body.futurePriceRub),
        futurePriceEffectiveOn: body.futurePriceEffectiveOn == null ? null : String(body.futurePriceEffectiveOn),
        commercialNote: body.commercialNote == null ? null : String(body.commercialNote)
      }
      mutations.push({ method, path, body })
      await route.fulfill(jsonResponse({ request }))
      return
    }
    if (method === 'POST' && action === 'create-and-link') {
      createLinkCalls += 1
      const created = request.linkedVenueId == null
      request = { ...request, linkedVenueId: 2 }
      createLinkCreatedResults.push(created)
      mutations.push({ method, path })
      if (loseNextCreateLinkResponse) {
        loseNextCreateLinkResponse = false
        await route.abort('failed')
        return
      }
      await route.fulfill(jsonResponse({ request, venueId: 2, created }))
      return
    }
    await route.fulfill(jsonResponse({ error: { code: 'METHOD_NOT_ALLOWED', message: 'Unsupported' } }, 405))
  })
  await page.route('**/api/platform/owners**', async (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/platform/owners') {
      await route.fulfill(jsonResponse({ owners }))
      return
    }
    const requestedOwner = owners.find((candidate) => path === `/api/platform/owners/${candidate.userId}`)
    if (requestedOwner) {
      await route.fulfill(
        jsonResponse({ owner: requestedOwner, venues: requestedOwner.userId === owner.userId ? ownerVenues : [] })
      )
      return
    }
    await route.fulfill(jsonResponse({ error: { code: 'NOT_FOUND', message: 'Not found' } }, 404))
  })

  return {
    getMutations: () => mutations.map((mutation) => ({ ...mutation })),
    getRequest: () => ({ ...request }),
    getApprovalCalls: () => approvalCalls,
    getCreateLinkCalls: () => createLinkCalls,
    getOnboardingCalls: () => onboardingCalls,
    getCreateLinkCreatedResults: () => [...createLinkCreatedResults],
    deferNextApproval,
    loseNextCreateLinkResult: () => {
      loseNextCreateLinkResponse = true
    },
    setRequest: (patch: Partial<PlatformOnboardingRequestFixture>) => {
      request = { ...request, ...patch }
    }
  }
}

test('ownership onboarding: owner explicitly selects and opens one or multiple venue cards', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const firstAccess: VenueOwnershipAccessFixture = {
    venueId: 1,
    venueName: 'Микс',
    venueCity: 'Москва',
    venueStatus: 'PUBLISHED',
    role: 'OWNER',
    permissions: []
  }
  const secondAccess: VenueOwnershipAccessFixture = {
    venueId: 2,
    venueName: 'Дымный берег',
    venueCity: 'Казань',
    venueStatus: 'DRAFT',
    role: 'OWNER',
    permissions: []
  }
  const api = await mockVenueOwnershipApi(page, {
    accesses: [firstAccess],
    applications: [
      {
        id: 77,
        venueName: 'Дымный берег',
        city: 'Казань',
        contact: '@anna_owner',
        comment: null,
        status: 'APPROVED',
        createdAt: '2030-01-10T18:00:00Z',
        linkedVenueId: 2
      }
    ]
  })

  await page.goto(`?mode=venue&venueId=1#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Мои заведения', exact: true }).click()

  await expect(page.getByRole('heading', { name: 'Мои заведения' })).toBeVisible()
  await clickTelegramBackButton(page)
  await expect(page.getByRole('heading', { name: 'Обзор', exact: true })).toBeFocused()
  await page.getByRole('button', { name: 'Мои заведения', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Мои заведения' })).toBeVisible()
  const venueCard = page.locator('section.card').filter({
    has: page.getByRole('heading', { name: 'Заведения', exact: true })
  })
  await expect(venueCard.locator('.venue-order-row').filter({ hasText: 'Микс' })).toBeVisible()
  await expect(venueCard.locator('.venue-order-row').filter({ hasText: 'Дымный берег' })).toHaveCount(0)
  await expect(page.locator('.venue-controls .venue-select')).toBeHidden()

  api.setAccesses([firstAccess, secondAccess])
  api.setVenues([firstAccess, secondAccess])
  const linkedApplication = page.locator('.venue-order-row').filter({ hasText: 'Дымный берег' })
  await linkedApplication.getByRole('button', { name: 'Обновить список' }).click()

  await expect(page.getByRole('heading', { name: 'Мои заведения' })).toBeVisible()
  await expect(venueCard.locator('.venue-order-row').filter({ hasText: 'Дымный берег' })).toBeVisible()
  const selector = page.locator('.venue-controls .venue-select')
  await expect(selector).toBeVisible()
  await expect(selector).toHaveValue('1')
  await expect(page).toHaveURL(/venueId=1/)

  await selector.selectOption('2')
  await expect(page).toHaveURL(/venueId=2/)
  await expect(selector).toHaveValue('2')

  await page.locator('.venue-order-row').filter({ hasText: 'Микс' }).getByRole('button', { name: 'Открыть' }).click()
  await expect(page).toHaveURL(/venueId=1.*#\/dashboard/)
})

test('ownership onboarding: owner submits edits and cancels an application with approved-unlinked guidance', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueOwnershipApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'OWNER',
        permissions: []
      }
    ],
    applications: [
      {
        id: 77,
        venueName: 'Одобренная кальянная',
        city: 'Тула',
        contact: '@approved_owner',
        comment: null,
        status: 'APPROVED',
        createdAt: '2030-01-09T18:00:00Z',
        linkedVenueId: null
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Мои заведения', exact: true }).click()

  await expect(
    page.getByText('Заявка одобрена. Заведение ещё подготавливается и скоро появится в списке.')
  ).toBeVisible()
  await page.getByRole('button', { name: 'Добавить заведение' }).click()
  await expect(page.getByLabel('Название заведения')).toBeFocused()
  await page.getByRole('button', { name: 'Отмена', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Добавить заведение' })).toBeFocused()
  await page.getByRole('button', { name: 'Добавить заведение' }).click()
  await page.getByLabel('Название заведения').fill('Новая кальянная')
  await page.getByLabel('Город', { exact: true }).fill('Самара')
  await page.getByLabel('Контакт для связи').fill('@new_owner')
  await page.getByLabel('Комментарий (необязательно)').fill('Открытие в марте')
  await page.getByRole('button', { name: 'Отправить заявку' }).click()

  let applicationRow = page.locator('.venue-order-row').filter({ hasText: 'Новая кальянная' })
  await expect(applicationRow.getByText('На рассмотрении', { exact: true })).toBeVisible()
  await expect(applicationRow).toBeFocused()
  await applicationRow.getByRole('button', { name: 'Изменить' }).click()
  await page.getByLabel('Название заведения').fill('Новая кальянная 2')
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click()

  applicationRow = page.locator('.venue-order-row').filter({ hasText: 'Новая кальянная 2' })
  await expect(applicationRow).toBeVisible()
  let cancelConfirmation: string | null = null
  page.once('dialog', async (dialog) => {
    cancelConfirmation = dialog.message()
    await dialog.accept()
  })
  await applicationRow.getByRole('button', { name: 'Отменить' }).click()

  await expect.poll(() => cancelConfirmation).toBe(
    'Отменить заявку «Новая кальянная 2»? После отмены платформа больше не будет её обрабатывать.'
  )
  await expect(applicationRow.getByText('Отменено', { exact: true })).toBeVisible()
  await expect(applicationRow).toBeFocused()
  expect(api.getApplications().find((application) => application.id === 78)?.status).toBe('CANCELLED')
  expect(api.getMutations()).toEqual([
    {
      method: 'POST',
      path: '/api/venue/ownership/applications',
      body: {
        venueName: 'Новая кальянная',
        city: 'Самара',
        contact: '@new_owner',
        comment: 'Открытие в марте'
      }
    },
    {
      method: 'PUT',
      path: '/api/venue/ownership/applications/78',
      body: {
        venueName: 'Новая кальянная 2',
        city: 'Самара',
        contact: '@new_owner',
        comment: 'Открытие в марте'
      }
    },
    { method: 'POST', path: '/api/venue/ownership/applications/78/cancel' }
  ])
})

test('ownership onboarding: exact double-submit keeps one authoritative application', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueOwnershipApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'OWNER',
        permissions: []
      }
    ]
  })
  const releaseFirstResponse = api.deferNextSubmitResponse()

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Мои заведения', exact: true }).click()
  const liveStatus = page.locator('.ownership-workspace > .status')
  await expect(liveStatus).toHaveAttribute('role', 'status')
  await expect(liveStatus).toHaveAttribute('aria-live', 'polite')
  await page.getByRole('button', { name: 'Добавить заведение' }).click()
  await fillVenueOwnershipApplication(page, {
    venueName: 'Северный дым',
    city: 'Пермь',
    contact: '@north_owner',
    comment: 'Открытие осенью'
  })
  const submit = page.getByRole('button', { name: 'Отправить заявку' })
  await submit.evaluate((button: HTMLButtonElement) => {
    button.click()
    button.click()
  })

  await expect.poll(() => api.getApplicationSubmitCalls()).toBe(1)
  await expect(submit).toBeDisabled()
  releaseFirstResponse()
  const firstRow = page.locator('[data-application-id="1"]')
  await expect(firstRow).toContainText('Северный дым')
  await expect(firstRow).toContainText('На рассмотрении')
  await expect(firstRow).toBeFocused()

  await page.getByRole('button', { name: 'Добавить заведение' }).click()
  await fillVenueOwnershipApplication(page, {
    venueName: 'Северный дым',
    city: 'Пермь',
    contact: '@north_owner',
    comment: 'Открытие осенью'
  })
  await page.getByRole('button', { name: 'Отправить заявку' }).click()

  await expect(liveStatus).toHaveText(
    'Заявка #1 уже была отправлена. Показан актуальный статус.'
  )
  await expect(firstRow).toBeFocused()
  expect(api.getApplicationSubmitCalls()).toBe(2)
  expect(api.getApplications()).toHaveLength(1)
  expect(api.getMutations()).toHaveLength(1)
})

test('ownership onboarding: a distinct second venue creates and shows a second application', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueOwnershipApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'OWNER',
        permissions: []
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Мои заведения', exact: true }).click()
  await page.getByRole('button', { name: 'Добавить заведение' }).click()
  await fillVenueOwnershipApplication(page, {
    venueName: 'Северный дым',
    city: 'Пермь',
    contact: '@north_owner',
    comment: 'Первая площадка'
  })
  await page.getByRole('button', { name: 'Отправить заявку' }).click()
  await page.getByRole('button', { name: 'Добавить заведение' }).click()
  await fillVenueOwnershipApplication(page, {
    venueName: 'Южный дым',
    city: 'Самара',
    contact: '@south_owner',
    comment: 'Вторая площадка'
  })
  await page.getByRole('button', { name: 'Отправить заявку' }).click()

  const firstRow = page.locator('[data-application-id="1"]')
  const secondRow = page.locator('[data-application-id="2"]')
  await expect(firstRow).toContainText('Северный дым')
  await expect(firstRow).toContainText('Пермь')
  await expect(secondRow).toContainText('Южный дым')
  await expect(secondRow).toContainText('Самара')
  await expect(secondRow).toBeFocused()
  expect(api.getApplications().map((application) => application.venueName)).toEqual([
    'Северный дым',
    'Южный дым'
  ])
  expect(api.getMutations()).toHaveLength(2)
})

test('ownership onboarding: approved-unlinked canonical retry returns the existing request', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueOwnershipApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'OWNER',
        permissions: []
      }
    ],
    applications: [
      {
        id: 77,
        venueName: 'Дымный берег',
        city: 'Казань',
        contact: '@owner',
        comment: 'Пилот',
        status: 'APPROVED',
        createdAt: '2030-01-09T18:00:00Z',
        linkedVenueId: null
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Мои заведения', exact: true }).click()
  await page.getByRole('button', { name: 'Добавить заведение' }).click()
  await fillVenueOwnershipApplication(page, {
    venueName: '  ДЫМНЫЙ   БЕРЕГ  ',
    city: 'казань',
    contact: '＠OWNER',
    comment: 'ПИЛОТ'
  })
  await page.getByRole('button', { name: 'Отправить заявку' }).click()

  const approvedRow = page.locator('[data-application-id="77"]')
  await expect(approvedRow).toContainText(
    'Заявка одобрена. Заведение ещё подготавливается и скоро появится в списке.'
  )
  await expect(approvedRow).toBeFocused()
  await expect(page.locator('.ownership-workspace > .status')).toHaveText(
    'Заявка #77 уже была отправлена. Показан актуальный статус.'
  )
  expect(api.getApplications()).toHaveLength(1)
  expect(api.getApplications()[0].status).toBe('APPROVED')
  expect(api.getMutations()).toHaveLength(0)
})

test('ownership onboarding: account replacement ignores a late prior-account submit response', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const ownerAccess: VenueOwnershipAccessFixture = {
    venueId: 1,
    venueName: 'Микс',
    venueCity: 'Москва',
    venueStatus: 'PUBLISHED',
    role: 'OWNER',
    permissions: []
  }
  const api = await mockVenueOwnershipApi(page, {
    accesses: [ownerAccess],
    otherAccount: {
      accesses: [{ ...ownerAccess, venueId: 2, venueName: 'Другой аккаунт' }],
      applications: [
        {
          id: 900,
          venueName: 'Заявка другого владельца',
          city: 'Тула',
          contact: '@other_owner',
          comment: null,
          status: 'PENDING',
          createdAt: '2030-01-09T18:00:00Z',
          linkedVenueId: null
        }
      ]
    }
  })
  const releaseOldResponse = api.deferNextSubmitResponse()

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Мои заведения', exact: true }).click()
  await page.getByRole('button', { name: 'Добавить заведение' }).click()
  await fillVenueOwnershipApplication(page, {
    venueName: 'Поздняя заявка старого аккаунта',
    city: 'Москва',
    contact: '@old_owner'
  })
  await page.getByRole('button', { name: 'Отправить заявку' }).click()
  await expect.poll(() => api.getApplications()).toHaveLength(1)

  await page.evaluate(({ userId, initData }) => {
    window.localStorage.setItem('__e2e_telegram_user_id', String(userId))
    window.localStorage.setItem('__e2e_telegram_init_data', initData)
  }, { userId: 987654321, initData: otherMockInitData })
  await page.goto(`?mode=venue&smokeUser=other#tgWebAppData=${encodeURIComponent(otherMockInitData)}`)
  await page.getByRole('button', { name: 'Мои заведения', exact: true }).click()

  await expect(page.getByText('Заявка другого владельца', { exact: true })).toBeVisible()
  await expect(page.getByText('Поздняя заявка старого аккаунта', { exact: true })).toHaveCount(0)
  releaseOldResponse()
  await expect.poll(() => api.getSettledApplicationSubmitCalls()).toBe(1)
  await expect(page.getByText('Заявка другого владельца', { exact: true })).toBeVisible()
  await expect(page.getByText('Поздняя заявка старого аккаунта', { exact: true })).toHaveCount(0)
  expect(api.getApplications(123456789).map((application) => application.venueName)).toEqual([
    'Поздняя заявка старого аккаунта'
  ])
  expect(api.getApplications(987654321).map((application) => application.venueName)).toEqual([
    'Заявка другого владельца'
  ])
})

test('ownership onboarding: lost response keeps the form and exact retry reuses the authoritative request', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueOwnershipApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'OWNER',
        permissions: []
      }
    ]
  })
  api.loseNextSubmitResult()

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Мои заведения', exact: true }).click()
  await page.getByRole('button', { name: 'Добавить заведение' }).click()
  await page.getByLabel('Название заведения').fill('Северный дым')
  await page.getByLabel('Город', { exact: true }).fill('Пермь')
  await page.getByLabel('Контакт для связи').fill('@north_owner')
  await page.getByLabel('Комментарий (необязательно)').fill('Сохранить при ошибке')
  await page.getByRole('button', { name: 'Отправить заявку' }).click()

  await expect(page.getByRole('heading', { name: 'Нет соединения' })).toBeVisible()
  await expect(page.locator('.ownership-workspace > .error-card')).toHaveAttribute('role', 'alert')
  await expect(page.locator('.ownership-workspace > .error-card')).toBeFocused()
  await expect(page.getByLabel('Название заведения')).toHaveValue('Северный дым')
  await expect(page.getByLabel('Город', { exact: true })).toHaveValue('Пермь')
  await expect(page.getByLabel('Контакт для связи')).toHaveValue('@north_owner')
  await expect(page.getByLabel('Комментарий (необязательно)')).toHaveValue('Сохранить при ошибке')
  await expect(page.getByRole('button', { name: 'Отправить заявку' })).toBeEnabled()
  expect(api.getApplicationSubmitCalls()).toBe(1)
  expect(api.getApplications()).toHaveLength(1)

  await page.getByRole('button', { name: 'Повторить' }).click()
  await expect(page.locator('.venue-order-row').filter({ hasText: 'Северный дым' })).toContainText('На рассмотрении')
  await expect(page.locator('.ownership-workspace > .status')).toHaveText(
    'Заявка #1 уже была отправлена. Показан актуальный статус.'
  )
  expect(api.getApplicationSubmitCalls()).toBe(2)
  expect(api.getApplications()).toHaveLength(1)
  expect(api.getMutations()).toEqual([
    {
      method: 'POST',
      path: '/api/venue/ownership/applications',
      body: {
        venueName: 'Северный дым',
        city: 'Пермь',
        contact: '@north_owner',
        comment: 'Сохранить при ошибке'
      }
    }
  ])
})

test('ownership onboarding: manager and staff cannot open the owner workspace', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueOwnershipApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER',
        permissions: []
      },
      {
        venueId: 2,
        venueName: 'Дым',
        venueCity: 'Казань',
        venueStatus: 'PUBLISHED',
        role: 'STAFF',
        permissions: []
      }
    ]
  })

  await page.goto(`?mode=venue&venueId=1#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  const ownershipNav = page.locator('.nav-button').filter({ hasText: 'Мои заведения' })
  await expect(ownershipNav).toBeHidden()
  await page.evaluate(() => {
    window.location.hash = '#/ownership'
  })

  await expect(page.getByRole('heading', { name: 'Недостаточно прав' })).toBeVisible()
  await expect(page.getByText('Раздел доступен только пользователю с действующей ролью OWNER.')).toBeVisible()
  const directRouteDenial = page.getByRole('alert')
  await expect(directRouteDenial).toHaveAttribute('aria-live', 'assertive')
  await expect(directRouteDenial).toBeFocused()
  expect(api.getOwnershipGetCalls()).toBe(0)

  await page.locator('.venue-controls .venue-select').selectOption('2')
  await expect(page.getByText('Роль: персонал')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Недостаточно прав' })).toBeVisible()
  await expect(page.getByRole('alert')).toBeFocused()
  expect(api.getOwnershipGetCalls()).toBe(0)
})

test('ownership onboarding: platform shows requests owners and venue co-owners', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockPlatformOwnershipApi(page)
  const approval = api.deferNextApproval()

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  const venueRow = page.locator('.venue-order-row').filter({ hasText: 'Микс' })
  await expect(venueRow).toContainText('#1 · Москва · PUBLISHED')
  await expect(venueRow).toContainText('Владельцы: Анна Иванова, @co_owner, User #503')

  await page.getByRole('button', { name: 'Заявки' }).click()
  await expect(page.getByRole('heading', { name: 'Подключение и ownership' })).toBeVisible()
  await expect(page.getByLabel('Статус заявки')).toBeVisible()
  await expect(page.getByLabel('Поиск заявок')).toBeVisible()
  const applicationsStatus = page.locator('.ownership-workspace > .status')
  await expect(applicationsStatus).toHaveAttribute('role', 'status')
  await expect(applicationsStatus).toHaveAttribute('aria-live', 'polite')
  const requestRow = page.locator('.venue-order-row').filter({ hasText: 'Дымный берег' })
  await expect(requestRow).toContainText('#701 · Казань · Анна Иванова')
  await requestRow.getByRole('button', { name: 'Открыть' }).click()

  await expect(page.getByRole('heading', { name: 'Заявка #701' })).toBeVisible()
  await expect(page.getByText('Контакт: @anna_owner')).toBeVisible()
  await expect(page.getByText('Комментарий: Нужна миграция меню')).toBeVisible()
  const approve = page.getByRole('button', { name: 'Одобрить' })
  await approve.click()
  await expect.poll(() => api.getApprovalCalls()).toBe(1)
  await expect(applicationsStatus).toHaveText('Сохраняем...')
  await expect(approve).toBeDisabled()
  await approve.dispatchEvent('click')
  expect(api.getApprovalCalls()).toBe(1)
  approval.release()
  await expect(page.getByText('Одобрена · ожидает создания')).toBeVisible()
  await expect(applicationsStatus).toHaveText('Заявка #701 одобрена.')
  await expect(applicationsStatus).toBeFocused()
  const createBeforeTerms = page.getByRole('button', { name: 'Создать DRAFT и связать' })
  await expect(createBeforeTerms).toBeDisabled()
  await expect(page.getByText(/Сначала сохраните корректные коммерческие условия/)).toBeVisible()

  await page.getByLabel('Текущая цена, ₽').fill('2200')
  await page.getByLabel('Пробный период до (необязательно)').fill('2030-02-01')
  await page.getByLabel('Коммерческая заметка (необязательно)').fill('Пилотный тариф')
  await page.getByRole('button', { name: 'Сохранить условия' }).click()
  await expect.poll(() => api.getRequest().currentPriceRub).toBe(2200)
  await expect(applicationsStatus).toHaveText('Коммерческие условия заявки #701 сохранены.')
  await expect(applicationsStatus).toBeFocused()
  await expect(page.getByRole('button', { name: 'Создать DRAFT и связать' })).toBeEnabled()
  expect(api.getMutations()).toEqual([
    { method: 'POST', path: '/api/platform/onboarding/requests/701/approve' },
    {
      method: 'PUT',
      path: '/api/platform/onboarding/requests/701/commercial-terms',
      body: {
        trialConfigured: true,
        trialEndsOn: '2030-02-01',
        currentPriceRub: 2200,
        futurePriceRub: null,
        futurePriceEffectiveOn: null,
        commercialNote: 'Пилотный тариф'
      }
    }
  ])

  await page.getByRole('button', { name: '← К заявкам' }).click()
  await expect(page.getByRole('heading', { name: 'Подключение и ownership' })).toBeFocused()
  await page.getByRole('button', { name: 'Кальянные' }).click()
  await expect(page.getByRole('heading', { name: 'Заведения' })).toBeVisible()
  await page.getByRole('button', { name: 'Владельцы' }).click()
  await expect(page.getByRole('heading', { name: 'Операционные владельцы' })).toBeVisible()
  await expect(page.getByLabel('Поиск владельцев')).toBeVisible()
  await expect(page.getByLabel('Статус заведения')).toBeVisible()
  const ownerRow = page.locator('.venue-order-row').filter({ hasText: 'Анна Иванова' })
  await expect(ownerRow).toContainText('User #501 · 2 заведения')
  await expect(ownerRow).toContainText('PUBLISHED: 1 · DRAFT: 1')
  await ownerRow.getByRole('button', { name: 'Открыть' }).click()

  await expect(page.getByRole('heading', { name: 'Владелец #501' })).toBeVisible()
  await expect(page.getByText('В управлении: 2 заведения', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Связанные заведения' })).toBeVisible()
  await expect(page.locator('.venue-order-row').filter({ hasText: 'Микс' })).toContainText('#1 · Москва · PUBLISHED')
  await expect(page.locator('.venue-order-row').filter({ hasText: 'Дымный берег' })).toContainText('#2 · Казань · DRAFT')
  await page.getByRole('button', { name: '← К владельцам' }).click()
  await expect(page.getByRole('heading', { name: 'Операционные владельцы' })).toBeFocused()
})

test('ownership onboarding: owner venue counts use Russian plural forms', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const cases = [
    [1, '1 заведение'],
    [2, '2 заведения'],
    [5, '5 заведений'],
    [11, '11 заведений'],
    [21, '21 заведение'],
    [22, '22 заведения'],
    [25, '25 заведений']
  ] as const
  await mockPlatformOwnershipApi(page, { ownerVenueCounts: cases.map(([count]) => count) })

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Владельцы' }).click()

  for (const [count, expected] of cases) {
    const row = page.locator('.venue-order-row').filter({ hasText: `Владелец ${count}` })
    await expect(row.locator('.venue-order-sub').first()).toHaveText(
      new RegExp(`^User #\\d+ · ${expected}$`)
    )
  }
})

test('ownership onboarding: first create-link focuses the rendered venue detail heading', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockPlatformOwnershipApi(page)
  api.setRequest({
    status: 'APPROVED',
    trialConfigured: true,
    trialEndsOn: '2030-02-01',
    currentPriceRub: 2200
  })

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заявки' }).click()
  await page.locator('.venue-order-row').filter({ hasText: 'Дымный берег' }).getByRole('button', { name: 'Открыть' }).click()
  page.once('dialog', async (dialog) => {
    await dialog.accept()
  })
  await page.getByRole('button', { name: 'Создать DRAFT и связать' }).click()

  await expect(page).toHaveURL(/#\/venue\/2$/)
  const heading = page.locator('[data-platform-venue-detail-heading="true"]')
  await expect(heading).toHaveRole('heading', { level: 2 })
  await expect(heading).toHaveText('Дымный берег')
  await expect(heading).toHaveAccessibleName('Дымный берег')
  await expect(heading).toHaveAttribute('id', 'platform-venue-detail-heading')
  await expect(heading).toBeFocused()
  expect(api.getCreateLinkCreatedResults()).toEqual([true])
})

test('ownership onboarding: platform retries a lost create-link result as an idempotent replay', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockPlatformOwnershipApi(page)
  api.setRequest({
    status: 'APPROVED',
    trialConfigured: true,
    trialEndsOn: '2030-02-01',
    currentPriceRub: 2200
  })
  api.loseNextCreateLinkResult()

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заявки' }).click()
  const requestRow = page.locator('.venue-order-row').filter({ hasText: 'Дымный берег' })
  await requestRow.getByRole('button', { name: 'Открыть' }).click()

  let confirmation: string | null = null
  page.once('dialog', async (dialog) => {
    confirmation = dialog.message()
    await dialog.accept()
  })
  const create = page.getByRole('button', { name: 'Создать DRAFT и связать' })
  await create.click()

  await expect.poll(() => confirmation).toBe(
    'Создать DRAFT «Дымный берег», назначить действующего OWNER и связать заявку? Новое заведение не будет выбрано автоматически.'
  )
  await expect(page.getByRole('heading', { name: 'Нет соединения' })).toBeVisible()
  await expect(create).toBeEnabled()
  expect(api.getCreateLinkCalls()).toBe(1)
  expect(api.getCreateLinkCreatedResults()).toEqual([true])
  expect(api.getRequest().linkedVenueId).toBe(2)

  page.once('dialog', async (dialog) => {
    await dialog.accept()
  })
  await page.getByRole('button', { name: 'Повторить' }).click()

  await expect(page).toHaveURL(/#\/venue\/2$/)
  const heading = page.locator('[data-platform-venue-detail-heading="true"]')
  await expect(heading).toHaveText('Дымный берег')
  await expect(heading).toHaveAccessibleName('Дымный берег')
  await expect(heading).toBeFocused()
  await expect(page.getByText('Заявка уже связана с существующим результатом.')).toBeVisible()
  expect(api.getCreateLinkCalls()).toBe(2)
  expect(api.getCreateLinkCreatedResults()).toEqual([true, false])
  expect(api.getMutations()).toEqual([
    { method: 'POST', path: '/api/platform/onboarding/requests/701/create-and-link' },
    { method: 'POST', path: '/api/platform/onboarding/requests/701/create-and-link' }
  ])
})

test('ownership onboarding: non-platform account is denied before request facts', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockPlatformOwnershipApi(page, { platformAllowed: false })

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  const accessState = page.locator('.venue-access-state')
  await expect(accessState).toHaveAttribute('role', 'status')
  await expect(accessState).toHaveAttribute('aria-live', 'polite')
  const platformDenial = page.getByRole('alert')
  await expect(platformDenial).toHaveText('Нет доступа.')
  await expect(platformDenial).toHaveAttribute('aria-live', 'assertive')
  await expect(platformDenial).toBeFocused()
  await expect(page.getByText('Дымный берег', { exact: true })).toHaveCount(0)
  await expect(page.getByText('@anna_owner', { exact: false })).toHaveCount(0)
  expect(api.getOnboardingCalls()).toBe(0)
})

test('ownership onboarding: platform rejects a pending request through the reject endpoint', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockPlatformOwnershipApi(page)

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заявки' }).click()
  await page.locator('.venue-order-row').filter({ hasText: 'Дымный берег' }).getByRole('button', { name: 'Открыть' }).click()
  page.once('dialog', async (dialog) => {
    await dialog.accept()
  })
  await page.getByRole('button', { name: 'Отклонить' }).click()

  const status = page.locator('.ownership-workspace > .status')
  await expect(page.getByText('Отклонена', { exact: true })).toBeVisible()
  await expect(status).toHaveText('Заявка #701 отклонена.')
  await expect(status).toBeFocused()
  expect(api.getRequest().status).toBe('REJECTED')
  expect(api.getMutations()).toEqual([
    { method: 'POST', path: '/api/platform/onboarding/requests/701/reject' }
  ])
})

test('ownership onboarding: platform closes approved-unlinked request through the close endpoint', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockPlatformOwnershipApi(page)
  api.setRequest({ status: 'APPROVED', linkedVenueId: null })

  await page.goto(`?mode=platform#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заявки' }).click()
  await page.locator('.venue-order-row').filter({ hasText: 'Дымный берег' }).getByRole('button', { name: 'Открыть' }).click()
  page.once('dialog', async (dialog) => {
    await dialog.accept()
  })
  await page.getByRole('button', { name: 'Закрыть без создания' }).click()

  const status = page.locator('.ownership-workspace > .status')
  await expect(page.getByText('Отменена', { exact: true })).toBeVisible()
  await expect(status).toHaveText('Заявка #701 закрыта.')
  await expect(status).toBeFocused()
  expect(api.getRequest().status).toBe('CANCELLED')
  expect(api.getMutations()).toEqual([
    { method: 'POST', path: '/api/platform/onboarding/requests/701/close' }
  ])
})

test('guest catalog sends debounced backend search and city filters then resets', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    catalogVenues: [
      buildGuestCatalogVenue({ id: 1, name: 'Микс & чай', city: 'Москва', address: 'Пилотная, 1' }),
      buildGuestCatalogVenue({ id: 2, name: 'Вторая Москва', city: 'москва', address: 'Тверская, 2' }),
      buildGuestCatalogVenue({ id: 3, name: 'Казанский зал', city: 'Казань', address: 'Баумана, 3' }),
      buildGuestCatalogVenue({ id: 4, name: 'Северный зал', city: 'Санкт-Петербург', address: 'Невский, 4' }),
      buildGuestCatalogVenue({ id: 5, name: 'Без города', city: '  ', address: 'Адрес, 5' })
    ]
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  const search = page.getByRole('searchbox', { name: 'Поиск по названию, городу или адресу' })
  const city = page.getByRole('combobox', { name: 'Город' })
  const reset = page.getByRole('button', { name: 'Сбросить поиск и фильтр' })
  await expect(search).toBeEnabled()
  await expect(city).toBeEnabled()
  await expect(reset).toBeDisabled()
  expect(await city.locator('option').allTextContents()).toEqual([
    'Все города',
    'Казань',
    'Москва',
    'Санкт-Петербург'
  ])

  const initialRequestCount = api.getCatalogRequests().length
  await page.clock.install()
  await search.fill('М')
  await search.fill('Микс')
  await search.fill('Микс & чай')
  await page.clock.fastForward(299)
  expect(api.getCatalogRequests()).toHaveLength(initialRequestCount)
  await page.clock.fastForward(1)
  await expect.poll(() => api.getCatalogRequests()).toHaveLength(initialRequestCount + 1)
  const searchRequest = api.getCatalogRequests().at(-1)!
  expect(searchRequest.q).toBe('Микс & чай')
  expect(searchRequest.city).toBeNull()
  expect(searchRequest.url).toContain('%26')
  await expect(page.getByText('Микс & чай', { exact: true })).toBeVisible()

  await city.selectOption('Москва')
  await expect.poll(() => api.getCatalogRequests()).toHaveLength(initialRequestCount + 2)
  const combinedRequest = api.getCatalogRequests().at(-1)!
  expect(combinedRequest).toMatchObject({ q: 'Микс & чай', city: 'Москва' })
  expect(combinedRequest.url).toContain(encodeURIComponent('Москва'))
  const filteredCard = page.locator('.catalog-item').filter({ hasText: 'Микс & чай' })
  await expect(filteredCard.getByRole('button', { name: 'Открыть карточку' })).toBeVisible()
  await expect(filteredCard.getByRole('button', { name: 'Задать вопрос' })).toBeVisible()
  await expect(filteredCard.getByRole('button', { name: 'Забронировать' })).toBeVisible()

  await reset.click()
  await expect.poll(() => api.getCatalogRequests()).toHaveLength(initialRequestCount + 3)
  expect(api.getCatalogRequests().at(-1)).toMatchObject({ q: null, city: null })
  await expect(search).toHaveValue('')
  await expect(city).toHaveValue('')
  await expect(reset).toBeDisabled()
  await expect(page.locator('.catalog-item')).toHaveCount(5)

  const resetMixCard = page.locator('.catalog-item').filter({ hasText: 'Микс & чай' })
  await resetMixCard.getByRole('button', { name: 'Забронировать' }).click()
  await expect.poll(() => page.evaluate(() => window.location.hash)).toBe('#/bookings?venueId=1')
  await page.evaluate(() => { window.location.hash = '#/catalog' })
  await expect(search).toBeEnabled()
  await page.locator('.catalog-item').filter({ hasText: 'Микс & чай' }).getByRole('button', { name: 'Задать вопрос' }).click()
  await expect.poll(() => page.evaluate(() => window.location.hash)).toBe('#/messages?venueId=1')
  await page.evaluate(() => { window.location.hash = '#/catalog' })
  await expect(search).toBeEnabled()
  await page.locator('.catalog-item').filter({ hasText: 'Микс & чай' }).getByRole('button', { name: 'Открыть карточку' }).click()
  await expect.poll(() => page.evaluate(() => window.location.hash)).toBe('#/venue/1')
})

test('guest catalog has deterministic loading retry general-empty and no-match states', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, { catalogVenues: [] })
  api.queueCatalogError({ status: 500, code: 'INTERNAL_ERROR', message: 'Failed' })
  const releaseInitial = api.deferNextCatalogResponse({ q: null, city: null })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  const search = page.getByRole('searchbox', { name: 'Поиск по названию, городу или адресу' })
  const city = page.getByRole('combobox', { name: 'Город' })
  await expect(page.getByText('Загрузка каталога...', { exact: true })).toBeVisible()
  await expect(search).toBeDisabled()
  await expect(city).toBeDisabled()
  releaseInitial()

  const errorCard = page.locator('.catalog-screen .error-card')
  await expect(errorCard).toBeVisible()
  await errorCard.getByRole('button', { name: 'Повторить' }).click()
  await expect(page.getByText('Пока нет доступных заведений.', { exact: true })).toBeVisible()
  await expect(search).toBeEnabled()
  await expect(city).toBeEnabled()

  api.setCatalogVenues([buildGuestCatalogVenue()])
  await page.getByRole('button', { name: 'Обновить', exact: true }).click()
  await expect(page.getByText('Микс', { exact: true })).toBeVisible()
  const beforeNoMatch = api.getCatalogRequests().length
  await search.fill('совпадений нет')
  await expect.poll(() => api.getCatalogRequests().length).toBe(beforeNoMatch + 1)
  await expect(page.getByText('По вашему запросу ничего не найдено', { exact: true })).toBeVisible()

  api.queueCatalogError({ status: 500, code: 'INTERNAL_ERROR', message: 'Failed again' })
  await page.getByRole('button', { name: 'Обновить', exact: true }).click()
  await expect(errorCard).toBeVisible()
  await errorCard.getByRole('button', { name: 'Повторить' }).click()
  await expect(page.getByText('По вашему запросу ничего не найдено', { exact: true })).toBeVisible()
})

test('guest catalog latest response wins and disposed requests cannot restore old results', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    catalogVenues: [
      buildGuestCatalogVenue({ id: 1, name: 'Старый зал' }),
      buildGuestCatalogVenue({ id: 2, name: 'Новый зал' })
    ]
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  const search = page.getByRole('searchbox', { name: 'Поиск по названию, городу или адресу' })
  await expect(search).toBeEnabled()
  await page.clock.install()

  const releaseOld = api.deferNextCatalogResponse({ q: 'Старый', city: null })
  await search.fill('Старый')
  await page.clock.fastForward(300)
  await expect.poll(() => api.getCatalogRequests().some((request) => request.q === 'Старый')).toBe(true)

  await search.fill('Новый')
  await page.clock.fastForward(300)
  await expect(page.getByText('Новый зал', { exact: true })).toBeVisible()
  await expect(page.getByText('Старый зал', { exact: true })).toHaveCount(0)
  releaseOld()
  await expect.poll(() => api.getCatalogResponseAttempts().some((request) => request.q === 'Старый')).toBe(true)
  await expect(page.getByText('Новый зал', { exact: true })).toBeVisible()
  await expect(page.getByText('Старый зал', { exact: true })).toHaveCount(0)

  const releaseDisposed = api.deferNextCatalogResponse({ q: 'После ухода', city: null })
  await search.fill('После ухода')
  await page.clock.fastForward(300)
  await expect.poll(() => api.getCatalogRequests().some((request) => request.q === 'После ухода')).toBe(true)
  await page.getByRole('button', { name: 'Профиль', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Профиль', exact: true })).toBeVisible()
  releaseDisposed()
  await expect.poll(() => api.getCatalogResponseAttempts().some((request) => request.q === 'После ухода')).toBe(true)
  await expect(page.getByRole('heading', { name: 'Профиль', exact: true })).toBeVisible()
})

test('guest catalog favorite override survives stale reload and an off-result mutation', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    catalogVenues: [
      buildGuestCatalogVenue({ id: 1, name: 'Микс', city: 'Москва' }),
      buildGuestCatalogVenue({ id: 2, name: 'Казанский зал', city: 'Казань' })
    ]
  })
  const releaseFavorite = api.deferNextFavoriteMutation(1, 'POST')

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  const mixCard = page.locator('.catalog-item').filter({ hasText: 'Микс' })
  await mixCard.getByRole('button', { name: 'В избранное' }).click()
  await expect.poll(() => api.getFavoriteMutationRequests()).toEqual([{ venueId: 1, method: 'POST' }])
  await expect(mixCard.getByRole('button', { name: 'В избранном' })).toBeDisabled()

  const requestsBeforeReload = api.getCatalogRequests().length
  await page.getByRole('button', { name: 'Обновить', exact: true }).click()
  await expect.poll(() => api.getCatalogRequests()).toHaveLength(requestsBeforeReload + 1)
  await expect(mixCard.getByRole('button', { name: 'В избранном' })).toBeDisabled()

  await page.getByRole('combobox', { name: 'Город' }).selectOption('Казань')
  await expect(page.getByText('Казанский зал', { exact: true })).toBeVisible()
  await expect(page.getByText('Микс', { exact: true })).toHaveCount(0)
  releaseFavorite()
  await expect.poll(() => api.getFavoriteVenueIds(123456789)).toEqual([1])

  await page.getByRole('button', { name: 'Сбросить поиск и фильтр' }).click()
  const restoredMixCard = page.locator('.catalog-item').filter({ hasText: 'Микс' })
  await expect(restoredMixCard.getByRole('button', { name: 'В избранном' })).toBeEnabled()
  await expect(restoredMixCard.getByRole('button', { name: 'В избранном' })).toHaveAttribute('aria-pressed', 'true')
})

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

test('guest sees active happy hours in venue detail and no cart discount outside its window', async ({ page }) => {
  const referenceInstant = '2030-01-14T09:00:00.000Z'
  await page.clock.setFixedTime(referenceInstant)
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    promotions: [
      {
        id: 41,
        title: 'Счастливые часы',
        description: 'Скидка на кальяны днём.',
        terms: 'По понедельникам.',
        startsAt: '2030-01-13T21:00:00.000Z',
        endsAt: '2030-01-20T21:00:00.000Z',
        templateType: 'HAPPY_HOURS_PERCENT',
        rule: {
          id: 601,
          version: 2,
          windows: [{ weekday: 1, startLocal: '14:00', endLocal: '17:00' }],
          target: { type: 'MENU_CATEGORY', menuCategoryId: 20, label: 'Кальянное меню' },
          discountPercent: 50,
          readyForActivation: true,
          validationIssues: []
        }
      }
    ]
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Открыть карточку' }).click()

  const promotions = page.locator('.guest-venue-promotions')
  await expect(promotions.getByRole('heading', { name: 'Акции', exact: true })).toBeVisible()
  await expect(promotions).toContainText('Счастливые часы')
  await expect(promotions).toContainText('Скидка на кальяны днём.')
  await expect(promotions).toContainText('По понедельникам.')
  await expect(promotions).toContainText(/с .* по /)

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()

  const previewCard = page.locator('.cart-preview-card')
  await expect.poll(() => api.getPreviewRequests()).toHaveLength(1)
  await expect(previewCard.getByRole('heading', { name: 'Скидка по позициям' })).toHaveCount(0)
  await expect(previewCard).not.toContainText('Акция «Счастливые часы»')
  await expect(previewCard).toContainText(/К оплате.*1[\s\u00a0]500,00\s*₽/)
})

test('guest favorite venues stay consistent across catalog venue detail and account', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, { favoriteMutationDelayMs: 250 })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  const catalogFavorite = page.getByRole('button', { name: 'В избранное' })
  await expect(catalogFavorite).toHaveAttribute('aria-pressed', 'false')
  await catalogFavorite.click()
  await expect(page.getByRole('button', { name: 'В избранном' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'В избранном' })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('button', { name: 'В избранном' })).toBeEnabled()
  expect(
    await page.getByRole('button', { name: 'В избранном' }).evaluate((button) => getComputedStyle(button).backgroundColor)
  ).not.toBe('rgb(255, 255, 255)')

  await page.getByRole('button', { name: 'Открыть карточку' }).click()
  const detailFavorite = page.getByRole('button', { name: 'В избранном' })
  await expect(detailFavorite).toHaveAttribute('aria-pressed', 'true')
  await detailFavorite.click()
  await expect(page.getByRole('button', { name: 'В избранное' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'В избранное' })).toBeEnabled()
  await page.getByRole('button', { name: 'В избранное' }).click()
  await expect(page.getByRole('button', { name: 'В избранном' })).toBeEnabled()

  await page.getByRole('button', { name: 'Профиль' }).click()
  await page.getByRole('button', { name: '⭐ Избранные заведения' }).click()
  await expect(page.getByRole('heading', { name: 'Избранные заведения' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Микс' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Открыть заведение' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Забронировать' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Задать вопрос' })).toBeVisible()

  await page.getByRole('button', { name: 'Открыть заведение' }).click()
  await expect.poll(() => page.evaluate(() => window.location.hash)).toBe('#/venue/1')
  await page.evaluate(() => { window.location.hash = '#/account' })
  await page.getByRole('button', { name: '⭐ Избранные заведения' }).click()
  await page.getByRole('button', { name: 'Забронировать' }).click()
  await expect.poll(() => page.evaluate(() => window.location.hash)).toBe('#/bookings?venueId=1')
  await page.evaluate(() => { window.location.hash = '#/account' })
  await page.getByRole('button', { name: '⭐ Избранные заведения' }).click()
  await page.getByRole('button', { name: 'Задать вопрос' }).click()
  await expect.poll(() => page.evaluate(() => window.location.hash)).toBe('#/messages?venueId=1')

  await page.evaluate(() => { window.location.hash = '#/account' })
  await page.getByRole('button', { name: '⭐ Избранные заведения' }).click()
  await page.getByRole('button', { name: 'Удалить из избранного' }).click()
  await expect(page.locator('.favorite-venue-list .button-danger')).toBeDisabled()
  await expect(page.getByText('Пока нет избранных заведений. Добавляйте их из каталога или карточки заведения.')).toBeVisible()

  api.setFavoriteVenueIds(123456789, [1])
  api.setVenueAvailable(false)
  await page.reload()
  await page.getByRole('button', { name: '⭐ Избранные заведения' }).click()
  await expect(page.getByText('Пока нет избранных заведений. Добавляйте их из каталога или карточки заведения.')).toBeVisible()
  api.setVenueAvailable(true)
  await page.reload()
  await page.getByRole('button', { name: '⭐ Избранные заведения' }).click()
  await expect(page.getByRole('heading', { name: 'Микс' })).toBeVisible()
})

test('guest favorite mutation rolls back and shows safe error', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, { favoriteMutationDelayMs: 150, favoriteMutationFailureOnce: true })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'В избранное' }).click()
  await expect(page.getByRole('button', { name: 'В избранном' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'В избранное' })).toBeEnabled()
  await expect(page.getByRole('button', { name: 'В избранное' })).toHaveAttribute('aria-pressed', 'false')
  await expect(page.getByText('Не удалось изменить избранное.')).toBeVisible()
})

test('guest favorite venues are isolated between accounts', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, { isolateFavoriteUsers: true })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'В избранное' }).click()
  await expect(page.getByRole('button', { name: 'В избранном' })).toBeEnabled()
  expect(api.getFavoriteVenueIds(123456789)).toEqual([1])
  const firstUserRequestCount = api.getCatalogRequests().length
  await page.getByRole('searchbox', { name: 'Поиск по названию, городу или адресу' }).fill('Мик')
  await page.getByRole('combobox', { name: 'Город' }).selectOption('Москва')
  await expect.poll(() => api.getCatalogRequests()).toHaveLength(firstUserRequestCount + 1)
  expect(api.getCatalogRequests().at(-1)).toMatchObject({ q: 'Мик', city: 'Москва' })

  await page.evaluate(({ userId, initData }) => {
    window.localStorage.setItem('__e2e_telegram_user_id', String(userId))
    window.localStorage.setItem('__e2e_telegram_init_data', initData)
    window.location.hash = '#/catalog'
  }, { userId: 987654321, initData: otherMockInitData })
  await page.reload()
  await expect(page.getByRole('searchbox', { name: 'Поиск по названию, городу или адресу' })).toHaveValue('')
  await expect(page.getByRole('combobox', { name: 'Город' })).toHaveValue('')
  await expect(page.getByRole('button', { name: 'В избранное' })).toHaveAttribute('aria-pressed', 'false')
  expect(api.getFavoriteVenueIds(987654321)).toEqual([])

  await page.evaluate(({ userId, initData }) => {
    window.localStorage.setItem('__e2e_telegram_user_id', String(userId))
    window.localStorage.setItem('__e2e_telegram_init_data', initData)
  }, { userId: 123456789, initData: mockInitData })
  await page.reload()
  await expect(page.getByRole('searchbox', { name: 'Поиск по названию, городу или адресу' })).toHaveValue('')
  await expect(page.getByRole('combobox', { name: 'Город' })).toHaveValue('')
  await expect(page.getByRole('button', { name: 'В избранном' })).toHaveAttribute('aria-pressed', 'true')
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
        },
        {
          visitId: 13,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-09T18:30:00Z',
          serviceDate: '2030-01-09',
          source: 'order_closed',
          totalMinor: 50000,
          currency: 'RUB',
          hasBooking: false,
          orderLabels: ['№7']
        },
        {
          visitId: 14,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-08T18:30:00Z',
          serviceDate: '2030-01-08',
          source: 'order_closed',
          totalMinor: 70000,
          currency: 'RUB',
          hasBooking: false,
          orderLabels: ['№8']
        },
        {
          visitId: 15,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-07T18:30:00Z',
          serviceDate: '2030-01-07',
          source: 'order_closed',
          totalMinor: 80000,
          currency: 'RUB',
          hasBooking: false,
          orderLabels: ['№9']
        },
        {
          visitId: 12,
          venueId: 1,
          venueName: 'Недоступный визит',
          venueCity: 'Москва',
          occurredAt: '2030-01-12T18:30:00Z',
          serviceDate: '2030-01-12',
          source: 'order_closed',
          totalMinor: null,
          currency: null,
          hasBooking: false,
          orderLabels: ['№404']
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
          currency: null,
          feedback: {
            eligible: true,
            submitted: false,
            rating: null,
            tags: [],
            comment: null
          },
          publicReviewUrl: 'https://yandex.ru/maps/org/mix/reviews'
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
              promotionDiscounts: [
                {
                  label: 'Счастливые часы',
                  discountMinor: 125000,
                  currency: 'RUB',
                  ruleType: 'HAPPY_HOURS_PERCENT',
                  originalAmountMinor: 250000,
                  finalAmountMinor: 125000
                }
              ],
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
          currency: 'RUB',
          feedback: {
            eligible: true,
            submitted: false,
            rating: null,
            tags: [],
            comment: null
          }
        },
        13: {
          visitId: 13,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-09T18:30:00Z',
          serviceDate: '2030-01-09',
          source: 'order_closed',
          booking: null,
          orders: [
            {
              orderId: 901,
              displayNumber: 7,
              displayDate: '2030-01-09',
              totalMinor: 50000,
              currency: 'RUB',
              items: [
                {
                  itemId: 201,
                  itemName: 'Classic Hookah',
                  qty: 1,
                  priceMinor: 50000,
                  currency: 'RUB',
                  totalMinor: 50000
                }
              ]
            }
          ],
          totalMinor: 50000,
          currency: 'RUB',
          feedback: {
            eligible: false,
            submitted: false,
            rating: null,
            tags: [],
            comment: null
          }
        },
        14: {
          visitId: 14,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-08T18:30:00Z',
          serviceDate: '2030-01-08',
          source: 'order_closed',
          booking: null,
          orders: [
            {
              orderId: 902,
              displayNumber: 8,
              displayDate: '2030-01-08',
              totalMinor: 70000,
              currency: 'RUB',
              promotionDiscounts: [],
              items: [
                {
                  itemId: 202,
                  itemName: 'Mint Hookah',
                  qty: 1,
                  priceMinor: 70000,
                  currency: 'RUB',
                  totalMinor: 70000
                }
              ]
            }
          ],
          totalMinor: 70000,
          currency: 'RUB',
          feedback: {
            eligible: true,
            submitted: false,
            rating: null,
            tags: [],
            comment: null
          }
        },
        15: {
          visitId: 15,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-07T18:30:00Z',
          serviceDate: '2030-01-07',
          source: 'order_closed',
          booking: null,
          orders: [
            {
              orderId: 903,
              displayNumber: 9,
              displayDate: '2030-01-07',
              totalMinor: 80000,
              currency: 'RUB',
              promotionDiscounts: [],
              items: [
                {
                  itemId: 203,
                  itemName: 'Berry Hookah',
                  qty: 1,
                  priceMinor: 80000,
                  currency: 'RUB',
                  totalMinor: 80000
                }
              ]
            }
          ],
          totalMinor: 80000,
          currency: 'RUB',
          feedback: {
            eligible: true,
            submitted: false,
            rating: null,
            tags: [],
            comment: null
          }
        }
      }
    }
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Профиль' }).click()
  await page.getByRole('button', { name: '🕘 История' }).click()

  const bookingOnlyVisit = page.locator('article.card').filter({ hasText: 'Было бронирование' })
  const closedOrderVisit = page.locator('article.card').filter({ hasText: 'Заказы: №42' })
  const legacyClosedOrderVisit = page.locator('article.card').filter({ hasText: 'Заказы: №7' })
  const noReviewLinkVisit = page.locator('article.card').filter({ hasText: 'Заказы: №8' })
  const lowRatingVisit = page.locator('article.card').filter({ hasText: 'Заказы: №9' })
  const missingDetailVisit = page.locator('article.card').filter({ hasText: 'Заказы: №404' })
  await expect(bookingOnlyVisit).toContainText('Микс')
  await expect(closedOrderVisit).toContainText(/Итого: 1[\s\u00a0]250/)
  await expect(legacyClosedOrderVisit).toContainText(/Итого: 500/)
  await expect(noReviewLinkVisit).toContainText(/Итого: 700/)
  await expect(lowRatingVisit).toContainText(/Итого: 800/)
  await expect(page.getByText('Отменённая бронь')).toHaveCount(0)

  await bookingOnlyVisit.getByRole('button', { name: 'Подробнее' }).click()
  await expect(page.getByText('Посещение по брони. Заказов в этом визите нет.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Повторить заказ' })).toHaveCount(0)
  await expect(page.getByText('Можно оценить бронь, встречу и обслуживание.')).toBeVisible()
  await page.getByRole('button', { name: 'Оценить визит' }).click()
  await expect(page.getByText('Можно оценить бронь, встречу и обслуживание.')).toBeVisible()
  const bookingFeedbackSubmit = page.getByRole('button', { name: 'Сохранить отзыв' })
  await expect(bookingFeedbackSubmit).toBeDisabled()
  const ratingFive = page.getByRole('button', { name: '5', exact: true })
  await ratingFive.click()
  await expect(ratingFive).toHaveAttribute('data-active', 'true')
  await expect(ratingFive).toHaveAttribute('aria-pressed', 'true')
  await expect(bookingFeedbackSubmit).toBeEnabled()
  const bookingTag = page.getByRole('button', { name: 'Бронь' })
  await bookingTag.click()
  await expect(bookingTag).toHaveAttribute('data-active', 'true')
  await expect(bookingTag).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'Сервис' }).click()
  await page.getByRole('button', { name: 'Вкус' }).click()
  await page.getByRole('button', { name: 'Скорость' }).click()
  await page.getByRole('button', { name: 'Атмосфера' }).click()
  await page.getByRole('button', { name: 'Чистота' }).click()
  await expect(page.getByText('Можно выбрать до 5 тегов.')).toBeVisible()
  await page.getByPlaceholder('Комментарий').fill('Все было вовремя')
  await bookingFeedbackSubmit.click()
  await expect(page.getByText('Спасибо за высокую оценку!')).toBeVisible()
  await expect(page.getByText('Вы оценили визит: 5/5.')).toBeVisible()
  const publicReviewLink = page.getByRole('link', { name: 'Оставить отзыв на Яндекс.Картах' })
  await expect(publicReviewLink).toBeVisible()
  await expect(publicReviewLink).toHaveAttribute('href', 'https://yandex.ru/maps/org/mix/reviews')
  await expect
    .poll(async () => page.evaluate(() => Boolean((window as TestTelegramWindow).__e2eTelegramBackButtonVisible)))
    .toBe(true)
  await clickTelegramBackButton(page)
  await expect(closedOrderVisit).toBeVisible()

  await closedOrderVisit.getByRole('button', { name: 'Подробнее' }).click()
  await expect(page.getByRole('button', { name: 'Повторить заказ' })).toBeVisible()
  await page.getByRole('button', { name: 'Повторить заказ' }).click()
  await expect(
    page.getByText('Чтобы повторить заказ, отсканируйте QR на столе в этом заведении.')
  ).toBeVisible()
  await page.getByRole('button', { name: 'Оценить визит' }).click()
  const closedFeedbackSubmit = page.getByRole('button', { name: 'Сохранить отзыв' })
  await expect(closedFeedbackSubmit).toBeDisabled()
  await page.getByRole('button', { name: '2', exact: true }).click()
  await expect(
    page.getByText('Жаль, что визит прошёл не идеально. Расскажите, что было не так — заведение сможет разобраться.')
  ).toBeVisible()
  await page.getByRole('button', { name: '4', exact: true }).click()
  await expect(closedFeedbackSubmit).toBeEnabled()
  await page.getByPlaceholder('Комментарий').fill('Хорошо')
  await closedFeedbackSubmit.click()
  await expect(page.getByText('Спасибо, отзыв сохранён.')).toBeVisible()
  await expect(page.getByText('Вы оценили визит: 4/5.')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Оставить отзыв на Яндекс.Картах' })).toHaveCount(0)

  await expect(page.getByRole('heading', { name: 'Заказ №42' })).toBeVisible()
  await expect(page.getByText('Загружаем данные...')).toHaveCount(0)
  await expect(page.getByText('Double Apple · Ягодный микс · Пожелание: покрепче ×1')).toBeVisible()
  await expect(page.getByText(/Счастливые часы:/)).toContainText(
    /2[\s\u00a0]500,00\s*₽.*−\s*1[\s\u00a0]250,00\s*₽.*=\s*1[\s\u00a0]250,00\s*₽/
  )
  await expect(page.getByRole('button', { name: '← Назад к истории' })).toBeVisible()
  await expect(page.getByText('Foreign Hookah')).toHaveCount(0)
  await page.getByRole('button', { name: '← Назад к истории' }).click()
  await expect(closedOrderVisit).toBeVisible()

  await noReviewLinkVisit.getByRole('button', { name: 'Подробнее' }).click()
  await page.getByRole('button', { name: 'Оценить визит' }).click()
  await page.getByRole('button', { name: '5', exact: true }).click()
  await page.getByRole('button', { name: 'Сохранить отзыв' }).click()
  await expect(page.getByText('Спасибо за высокую оценку!')).toBeVisible()
  await expect(page.getByText('Вы оценили визит: 5/5.')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Оставить отзыв на Яндекс.Картах' })).toHaveCount(0)
  await page.getByRole('button', { name: '← Назад к истории' }).click()
  await expect(noReviewLinkVisit).toBeVisible()

  await lowRatingVisit.getByRole('button', { name: 'Подробнее' }).click()
  await page.getByRole('button', { name: 'Оценить визит' }).click()
  await page.getByRole('button', { name: '2', exact: true }).click()
  await page.getByPlaceholder('Что было не так?').fill('Долго ждали')
  await page.getByRole('button', { name: 'Сохранить отзыв' }).click()
  await expect(page.getByText('Спасибо, отзыв сохранён. Мы передали его заведению.')).toBeVisible()
  await expect(page.getByText('Вы оценили визит: 2/5.')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Оставить отзыв на Яндекс.Картах' })).toHaveCount(0)
  await page.getByRole('button', { name: '← Назад к истории' }).click()
  await expect(lowRatingVisit).toBeVisible()

  await legacyClosedOrderVisit.getByRole('button', { name: 'Подробнее' }).click()
  await expect(page.getByRole('heading', { name: 'Заказ №7' })).toBeVisible()
  await expect(page.getByText('Classic Hookah ×1')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Оценить визит' })).toHaveCount(0)
  await expect(page.getByText('Не удалось загрузить детали истории.')).toHaveCount(0)
  await page.getByRole('button', { name: '← Назад к истории' }).click()
  await expect(legacyClosedOrderVisit).toBeVisible()

  await missingDetailVisit.getByRole('button', { name: 'Подробнее' }).click()
  await expect(page.getByText('Не удалось загрузить детали истории.')).toBeVisible()
  await expect(page.getByText('Загружаем данные...')).toHaveCount(0)
  await page.getByRole('button', { name: '← Назад к истории' }).click()
  await expect(missingDetailVisit).toBeVisible()
})

test('guest repeats eligible history lines into the current cart only after confirmation', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    menuCategories: [
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
            effectiveItemType: 'HOOKAH',
            options: [
              {
                id: 301,
                name: 'Ягодный',
                priceDeltaMinor: 25000,
                isAvailable: true
              }
            ]
          }
        ]
      }
    ],
    visitHistory: {
      items: [
        {
          visitId: 21,
          venueId: 1,
          venueName: 'Микс',
          occurredAt: '2030-02-01T18:30:00Z',
          source: 'order_closed',
          totalMinor: 260000,
          currency: 'RUB',
          hasBooking: false,
          orderLabels: ['№55']
        }
      ],
      details: {
        21: {
          visitId: 21,
          venueId: 1,
          venueName: 'Микс',
          occurredAt: '2030-02-01T18:30:00Z',
          source: 'order_closed',
          booking: null,
          orders: [
            {
              orderId: 904,
              displayNumber: 55,
              items: [
                {
                  itemId: 200,
                  itemName: 'Double Apple',
                  qty: 2,
                  selectedOption: { name: 'Старый ягодный', priceDeltaMinor: 10000 },
                  preferenceNote: 'покрепче',
                  priceMinor: 130000,
                  currency: 'RUB',
                  totalMinor: 260000
                }
              ],
              totalMinor: 260000,
              currency: 'RUB',
              promotionDiscounts: []
            }
          ],
          totalMinor: 260000,
          currency: 'RUB',
          feedback: { eligible: false, submitted: false }
        }
      },
      repeatPlans: {
        21: {
          904: {
            eligibleLines: [
              {
                itemId: 200,
                itemName: 'Double Apple',
                quantity: 2,
                selectedOption: {
                  optionId: 301,
                  name: 'Ягодный',
                  currentPriceDelta: { amountMinor: 25000, currency: 'RUB' }
                },
                preferenceNote: 'покрепче',
                currentItemPrice: { amountMinor: 150000, currency: 'RUB' },
                currentUnitPrice: { amountMinor: 175000, currency: 'RUB' },
                currentLineTotal: { amountMinor: 350000, currency: 'RUB' }
              }
            ],
            skippedLines: [
              {
                itemName: 'Вода',
                quantity: 1,
                selectedOptionName: null,
                reason: 'ITEM_UNAVAILABLE',
                message: 'Позиция больше недоступна.'
              }
            ],
            currentTotal: { amountMinor: 350000, currency: 'RUB' },
            sourceOrderId: 904,
            venueId: 1
          }
        }
      }
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: 'Профиль' }).click()
  await page.getByRole('button', { name: '🕘 История' }).click()
  await page.getByRole('button', { name: 'Подробнее' }).click()

  const repeatButton = page.getByRole('button', { name: 'Повторить заказ' })
  await expect(repeatButton).toBeVisible()
  await repeatButton.evaluate((button: HTMLButtonElement) => {
    button.click()
    button.click()
  })

  await expect(page.getByText('Добавим доступные позиции в корзину по текущим ценам.')).toBeVisible()
  await expect(page.getByText(/Double Apple · Ягодный · Пожелание: покрепче ×2/)).toBeVisible()
  await expect(page.getByText(/Вода ×1 — Позиция больше недоступна\./)).toBeVisible()
  await expect(page.getByText(/Итого по текущим ценам:/)).toContainText(/3[\s\u00a0]500/)
  expect(api.getRepeatPlanRequests()).toEqual([
    {
      visitId: 21,
      body: { tableSessionId: 77, tabId: 88 }
    }
  ])
  expect(api.getAddBatchRequests()).toHaveLength(0)

  const addButton = page.getByRole('button', { name: 'Добавить в корзину' })
  await addButton.click()
  await addButton.click({ force: true })
  await expect(page.getByText('Доступные позиции добавлены в корзину.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Корзина (2)' })).toBeVisible()
  expect(api.getAddBatchRequests()).toHaveLength(0)

  await page.getByRole('button', { name: 'Перейти в корзину' }).click()
  const repeatedLine = page.locator('.cart-item').filter({ hasText: 'Вариант: Ягодный' })
  await expect(repeatedLine).toContainText('Пожелание: покрепче')
  await expect(repeatedLine.locator('input')).toHaveValue('2')
  expect(api.getAddBatchRequests()).toHaveLength(0)
})

test('guest repeat blocks a current table context from another venue', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    visitHistory: {
      items: [
        {
          visitId: 22,
          venueId: 2,
          venueName: 'Другое место',
          occurredAt: '2030-02-02T18:30:00Z',
          source: 'order_closed',
          totalMinor: 100000,
          currency: 'RUB',
          hasBooking: false,
          orderLabels: ['№56']
        }
      ],
      details: {
        22: {
          visitId: 22,
          venueId: 2,
          venueName: 'Другое место',
          occurredAt: '2030-02-02T18:30:00Z',
          source: 'order_closed',
          orders: [
            {
              orderId: 905,
              displayNumber: 56,
              items: [{ itemId: 200, itemName: 'Кальян', qty: 1 }],
              promotionDiscounts: []
            }
          ],
          feedback: { eligible: false, submitted: false }
        }
      }
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: 'Профиль' }).click()
  await page.getByRole('button', { name: '🕘 История' }).click()
  await page.getByRole('button', { name: 'Подробнее' }).click()
  await page.getByRole('button', { name: 'Повторить заказ' }).click()

  await expect(page.getByText('Этот заказ можно повторить только в том же заведении.')).toBeVisible()
  expect(api.getRepeatPlanRequests()).toHaveLength(0)
  expect(api.getAddBatchRequests()).toHaveLength(0)
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

test('guest cart uses current item and option prices and drops a paused promotion on submit', async ({ page }) => {
  const referenceInstant = '2030-01-14T12:00:00.000Z'
  await page.clock.setFixedTime(referenceInstant)
  await installTelegramWebApp(page, 123456789)
  const preview: CartPreviewFixture = {
    grossTotalMinor: 160000,
    promoDiscountTotalMinor: 80000,
    loyaltyDiscountTotalMinor: 0,
    finalPayableTotalMinor: 80000,
    currency: 'RUB',
    pricingFingerprint: 'happy-hours-preview-v3',
    cartFingerprint: 'happy-hours-cart-v3',
    discounts: [
      {
        label: 'Счастливые часы',
        discountMinor: 80000,
        currency: 'RUB',
        ruleType: 'HAPPY_HOURS_PERCENT',
        promotionId: 501,
        ruleId: 601,
        ruleVersion: 3,
        originalAmountMinor: 160000,
        finalAmountMinor: 80000,
        eligibleLineIds: [1]
      }
    ],
    items: [
      {
        itemId: 200,
        name: 'Кальян',
        qty: 1,
        selectedOption: {
          optionId: 301,
          name: 'Ягодный',
          priceDeltaMinor: 30000
        },
        preferenceNote: null,
        priceMinor: 160000,
        currency: 'RUB',
        lineGrossMinor: 160000,
        discountMinor: 80000,
        linePayableMinor: 80000,
        isPromotionReward: false,
        promotionAdjustment: {
          promotionId: 501,
          promotionTitle: 'Счастливые часы',
          ruleId: 601,
          ruleVersion: 3,
          ruleType: 'HAPPY_HOURS_PERCENT',
          originalAmountMinor: 160000,
          discountMinor: 80000,
          finalAmountMinor: 80000
        }
      }
    ]
  }
  const recalculatedPricing: CartPreviewFixture = {
    ...preview,
    promoDiscountTotalMinor: 0,
    finalPayableTotalMinor: 160000,
    pricingFingerprint: 'happy-hours-submit-paused',
    cartFingerprint: 'happy-hours-cart-v3',
    discounts: [],
    items: [
      {
        itemId: 200,
        name: 'Кальян',
        qty: 1,
        selectedOption: {
          optionId: 301,
          name: 'Ягодный',
          priceDeltaMinor: 30000
        },
        preferenceNote: null,
        priceMinor: 160000,
        currency: 'RUB',
        lineGrossMinor: 160000,
        discountMinor: 0,
        linePayableMinor: 160000,
        isPromotionReward: false,
        promotionAdjustment: null
      }
    ]
  }
  const api = await mockGuestApi(page, {
    menuCategories: [
      {
        id: 20,
        name: 'Кальянное меню',
        categoryType: 'HOOKAH',
        items: [
          {
            id: 200,
            name: 'Кальян',
            priceMinor: 100000,
            currency: 'RUB',
            isAvailable: true,
            effectiveItemType: 'HOOKAH',
            options: [{ id: 301, name: 'Ягодный', priceDeltaMinor: 10000, isAvailable: true }]
          }
        ]
      }
    ],
    promotions: [
      {
        id: 501,
        title: 'Счастливые часы',
        description: 'Скидка 50% на кальяны.',
        startsAt: '2030-01-13T21:00:00.000Z',
        endsAt: '2030-01-20T21:00:00.000Z',
        templateType: 'HAPPY_HOURS_PERCENT',
        rule: {
          id: 601,
          version: 3,
          windows: [{ weekday: 1, startLocal: '12:00', endLocal: '18:00' }],
          target: { type: 'MENU_ITEM', menuItemId: 200, label: 'Кальян' },
          discountPercent: 50,
          readyForActivation: true,
          validationIssues: []
        }
      }
    ],
    cartPreview: preview,
    addBatchResponse: {
      orderId: 900,
      batchId: 444,
      pricing: recalculatedPricing,
      recalculated: true
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Выбрать' }).click()
  await page.getByRole('button', { name: /Ягодный/ }).click()
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()

  const cartLine = page.locator('.cart-item').filter({ hasText: 'Вариант: Ягодный' })
  await expect(cartLine).toContainText(/1[\s\u00a0]100,00\s*₽/)
  const previewCard = page.locator('.cart-preview-card')
  await expect(previewCard).toContainText('Сумма до скидок')
  await expect(previewCard).toContainText('Акция «Счастливые часы»')
  await expect(previewCard.getByRole('heading', { name: 'Скидка по позициям' })).toBeVisible()
  const promotedLine = previewCard.locator('.cart-preview-line').filter({ hasText: 'Кальян · Ягодный × 1' })
  await expect(promotedLine).toContainText(/Обычная стоимость.*1[\s\u00a0]600,00\s*₽/)
  await expect(promotedLine).toContainText(/Акция «Счастливые часы».*−800,00\s*₽/)
  await expect(promotedLine).toContainText(/К оплате.*800,00\s*₽/)
  await expect.poll(() => api.getPreviewRequests()).toHaveLength(1)
  expect(api.getPreviewRequests()[0].items).toEqual([
    expect.objectContaining({ itemId: 200, qty: 1, selectedOptionId: 301, cartLineRef: expect.any(String) })
  ])
  expect(api.getAddBatchRequests()).toHaveLength(0)

  api.setPromotions([])
  await page.getByRole('button', { name: 'Отправить' }).click()
  await expect(page.locator('.toast')).toHaveText('Условия акции изменились. Итог корзины пересчитан.')
  expect(api.getAddBatchRequests()).toHaveLength(1)
  expect(api.getAddBatchRequests()[0].previewFingerprint).toBe('happy-hours-preview-v3')
})

function submittedBatchResponse(request: AddBatchPayload): AddBatchResponseFixture {
  return {
    submitted: true,
    orderId: 900,
    batchId: 444,
    pricing: buildStaleRecoveryPreview(request),
    recalculated: false
  }
}

async function openDefaultGuestCart(page: Page) {
  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()
  await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeEnabled()
}

test('guest cart idempotency preserves the key for an exact network retry', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  let attempts = 0
  const api = await mockGuestApi(page, {
    addBatchResponseResolver: (request) => {
      attempts += 1
      return attempts === 1
        ? {
            error: {
              status: 503,
              code: 'DATABASE_UNAVAILABLE',
              message: 'Временная ошибка базы данных.'
            }
          }
        : submittedBatchResponse(request)
    }
  })
  await openDefaultGuestCart(page)

  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  const submitError = page.locator('.error-card')
  await expect(submitError).toContainText('Сервис временно недоступен')
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(1)
  const firstKey = api.getAddBatchRequests()[0].idempotencyKey
  expect(firstKey).toBeTruthy()

  await submitError.getByRole('button', { name: 'Повторить' }).click()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(2)
  expect(api.getAddBatchRequests()[1].idempotencyKey).toBe(firstKey)
})

test('guest cart preserves a business mutation made while submit response is in flight', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  let releaseFirstResponse!: () => void
  let markFirstRequestStarted!: () => void
  const firstResponseGate = new Promise<void>((resolve) => {
    releaseFirstResponse = resolve
  })
  const firstRequestStarted = new Promise<void>((resolve) => {
    markFirstRequestStarted = resolve
  })
  let attempts = 0
  const api = await mockGuestApi(page, {
    addBatchResponseResolver: async (request) => {
      attempts += 1
      if (attempts === 1) {
        markFirstRequestStarted()
        await firstResponseGate
      }
      return submittedBatchResponse(request)
    }
  })
  await openDefaultGuestCart(page)
  const send = page.getByRole('button', { name: 'Отправить', exact: true })

  await send.click()
  await firstRequestStarted
  const firstKey = api.getAddBatchRequests()[0].idempotencyKey
  const quantityInput = page.locator('.cart-item .qty-input')
  await quantityInput.fill('2')
  await quantityInput.press('Enter')
  await expect.poll(() => api.getPreviewRequests().at(-1)?.items[0]?.qty).toBe(2)

  releaseFirstResponse()
  await expect(
    page.getByText(
      'Заказ отправлен. Изменения, внесённые во время отправки, сохранены в корзине. Проверьте их и нажмите «Отправить» для нового заказа.'
    )
  ).toBeVisible()
  await expect(quantityInput).toHaveValue('2')
  await expect(page.locator('.cart-item')).toHaveCount(1)
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(1)
  await expect(send).toBeEnabled()

  await send.click()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(2)
  expect(api.getAddBatchRequests()[1].idempotencyKey).not.toBe(firstKey)
  await expect(page).toHaveURL(/#\/order$/)
})

test('guest cart idempotency rotates the key after quantity and normalized comment mutations', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  let attempts = 0
  const api = await mockGuestApi(page, {
    addBatchResponseResolver: (request) => {
      attempts += 1
      return attempts < 3
        ? {
            error: {
              status: 503,
              code: 'DATABASE_UNAVAILABLE',
              message: 'Временная ошибка базы данных.'
            }
          }
        : submittedBatchResponse(request)
    }
  })
  await openDefaultGuestCart(page)
  const send = page.getByRole('button', { name: 'Отправить', exact: true })

  await send.click()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(1)
  const quantityInput = page.locator('.cart-item .qty-input')
  await quantityInput.fill('2')
  await quantityInput.press('Enter')
  await expect.poll(() => api.getPreviewRequests().at(-1)?.items[0]?.qty).toBe(2)
  await expect(send).toBeEnabled()

  await send.click()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(2)
  const comment = page.getByPlaceholder('Комментарий к заказу')
  await comment.fill('  у окна  ')
  await expect.poll(() => api.getPreviewRequests().at(-1)?.comment).toBe('у окна')
  await expect(send).toBeEnabled()

  await send.click()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(3)
  const keys = api.getAddBatchRequests().map((request) => request.idempotencyKey)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).not.toBe(keys[0])
  expect(keys[2]).not.toBe(keys[1])
})

test('guest cart idempotency mismatch keeps the cart and uses a new key only after explicit retry', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  let attempts = 0
  const api = await mockGuestApi(page, {
    addBatchResponseResolver: (request) => {
      attempts += 1
      return attempts === 1
        ? {
            error: {
              status: 409,
              code: 'ORDER_IDEMPOTENCY_PAYLOAD_MISMATCH',
              message:
                'Этот ключ отправки уже использован для другого состава заказа. Обновите корзину и отправьте заказ ещё раз.'
            }
          }
        : submittedBatchResponse(request)
    }
  })
  await openDefaultGuestCart(page)

  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  const submitError = page.locator('.error-card')
  await expect(submitError).toContainText('Этот ключ отправки уже использован для другого состава заказа.')
  await expect(page.locator('.cart-item')).toHaveCount(1)
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(1)
  const conflictingKey = api.getAddBatchRequests()[0].idempotencyKey

  await submitError.getByRole('button', { name: 'Повторить' }).click()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(2)
  expect(api.getAddBatchRequests()[1].idempotencyKey).not.toBe(conflictingKey)
})

test('guest cart unverifiable replay waits for an explicit safe recovery action', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  let attempts = 0
  const api = await mockGuestApi(page, {
    addBatchResponseResolver: (request) => {
      attempts += 1
      return attempts === 1
        ? {
            error: {
              status: 409,
              code: 'ORDER_IDEMPOTENCY_REPLAY_UNVERIFIABLE',
              message:
                'Не удалось безопасно повторить старую отправку. Проверьте активный заказ и отправьте корзину заново только при необходимости.'
            }
          }
        : submittedBatchResponse(request)
    }
  })
  await openDefaultGuestCart(page)

  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  const submitError = page.locator('.error-card')
  await expect(submitError).toContainText('Не удалось безопасно повторить старую отправку.')
  await expect(submitError.getByRole('button', { name: 'Проверить активный заказ' })).toBeVisible()
  await expect(submitError.getByRole('button', { name: 'Отправить как новый заказ' })).toBeVisible()
  await expect(page.locator('.cart-item')).toHaveCount(1)
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(1)
  const unverifiableKey = api.getAddBatchRequests()[0].idempotencyKey

  await submitError.getByRole('button', { name: 'Отправить как новый заказ' }).click()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(2)
  expect(api.getAddBatchRequests()[1].idempotencyKey).not.toBe(unverifiableKey)
})

for (const scenario of [
  {
    code: 'CART_MENU_SELECTION_UNAVAILABLE',
    label: 'stale menu selection'
  },
  {
    code: 'ORDER_IDEMPOTENCY_PAYLOAD_MISMATCH',
    label: 'idempotency payload mismatch'
  },
  {
    code: 'ORDER_IDEMPOTENCY_REPLAY_UNVERIFIABLE',
    label: 'unverifiable idempotency replay'
  }
]) {
  test(`guest cart keeps ${scenario.label} generic for non-conflict HTTP statuses`, async ({ page }) => {
    await installTelegramWebApp(page, 123456789)
    let attempts = 0
    const servedStatuses: number[] = []
    const api = await mockGuestApi(page, {
      addBatchResponseResolver: (request) => {
        const status = attempts === 0 ? 400 : attempts === 1 ? 422 : 500
        attempts += 1
        servedStatuses.push(status)
        const line = request.items[0]
        return {
          error: {
            status,
            code: scenario.code,
            message: 'Конфликтный код не соответствует HTTP-статусу.',
            details:
              scenario.code === 'CART_MENU_SELECTION_UNAVAILABLE'
                ? {
                    issues: [
                      {
                        cartLineRef: line.cartLineRef,
                        itemId: line.itemId,
                        optionId: null,
                        selectionKind: 'ITEM',
                        reason: 'UNAVAILABLE'
                      }
                    ]
                  }
                : undefined
          }
        }
      }
    })
    await openDefaultGuestCart(page)

    const submitError = page.locator('.error-card')
    await page.getByRole('button', { name: 'Отправить', exact: true }).click()
    for (let requestCount = 1; requestCount <= 3; requestCount += 1) {
      await expect.poll(() => api.getAddBatchRequests()).toHaveLength(requestCount)
      await expect(submitError).toBeVisible()
      await expect(submitError.getByRole('heading', { name: 'Ошибка', exact: true })).toBeVisible()
      await expect(submitError).toContainText('Попробуйте ещё раз.')
      await expect(submitError).not.toContainText('Конфликтный код не соответствует HTTP-статусу.')
      await expect(submitError.getByRole('button')).toHaveCount(1)
      await expect(submitError.getByRole('button', { name: 'Повторить', exact: true })).toBeVisible()
      await expect(submitError.getByRole('button', { name: 'Проверить активный заказ' })).toHaveCount(0)
      await expect(submitError.getByRole('button', { name: 'Отправить как новый заказ' })).toHaveCount(0)
      await expect(page.locator('.cart-line-warning')).toHaveCount(0)
      await expect(page.getByRole('button', { name: /Выбрать другой вариант/ })).toHaveCount(0)
      await expect(page.getByRole('button', { name: 'Вернуться в меню', exact: true })).toHaveCount(0)
      await expect(page.getByText('Удалить и выбрать другую', { exact: true })).toHaveCount(0)
      await expect(page.locator('.cart-item')).toHaveCount(1)
      if (requestCount < 3) {
        await submitError.getByRole('button', { name: 'Повторить', exact: true }).click()
      }
    }

    expect(servedStatuses).toEqual([400, 422, 500])
    const keys = api.getAddBatchRequests().map((request) => request.idempotencyKey)
    expect(keys[0]).toBeTruthy()
    expect(keys.every((key) => key === keys[0])).toBe(true)
  })
}

test('guest cart stale option correction rotates the prior submit key', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const menu = (includeStaleOption: boolean): GuestMenuCategory[] => [
    {
      id: 20,
      name: 'Кальянное меню',
      categoryType: 'HOOKAH',
      items: [
        {
          id: 200,
          name: 'Кальян',
          priceMinor: 150000,
          currency: 'RUB',
          isAvailable: true,
          effectiveItemType: 'HOOKAH',
          options: [
            ...(includeStaleOption
              ? [{ id: 301, name: 'Ягодный', priceDeltaMinor: 0, isAvailable: true }]
              : []),
            { id: 302, name: 'Цитрус', priceDeltaMinor: 0, isAvailable: true }
          ]
        }
      ]
    }
  ]
  let attempts = 0
  const api = await mockGuestApi(page, {
    menuCategories: menu(true),
    cartPreviewResolver: buildStaleRecoveryPreview,
    addBatchResponseResolver: (request) => {
      attempts += 1
      const line = request.items[0]
      return attempts === 1
        ? {
            error: {
              status: 409,
              code: 'CART_MENU_SELECTION_UNAVAILABLE',
              message: 'Корзину нужно обновить.',
              details: {
                issues: [
                  {
                    cartLineRef: line.cartLineRef,
                    itemId: line.itemId,
                    optionId: line.selectedOptionId,
                    selectionKind: 'OPTION',
                    reason: 'UNAVAILABLE'
                  }
                ]
              }
            }
          }
        : submittedBatchResponse(request)
    }
  })
  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Выбрать' }).click()
  await page.getByRole('button', { name: /Ягодный/ }).click()
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(1)
  const staleKey = api.getAddBatchRequests()[0].idempotencyKey

  api.setMenuCategories(menu(false))
  await page.getByRole('button', { name: 'Выбрать другой вариант для позиции Кальян' }).click()
  await page.getByRole('button', { name: /Цитрус/ }).click()
  await page.getByRole('button', { name: 'Сохранить новый вариант' }).click()
  await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()

  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(2)
  expect(api.getAddBatchRequests()[1].idempotencyKey).not.toBe(staleKey)
})

for (const scenario of [
  {
    reason: 'UNAVAILABLE' as const,
    label: 'unavailable',
    copy:
      'Позиция «Вода» временно недоступна. Чтобы продолжить заказ, удалите её из корзины и выберите другую позицию.'
  },
  {
    reason: 'REMOVED' as const,
    label: 'removed',
    copy: 'Позиции «Вода» больше нет в меню. Удалите её из корзины, чтобы продолжить заказ.'
  }
]) {
  test(`guest cart removes an ${scenario.label} item before opening the menu`, async ({ page }) => {
    await installTelegramWebApp(page, 123456789)
    let releaseRemainingCartPreview: () => void = () => undefined
    const remainingCartPreviewGate = new Promise<void>((resolve) => {
      releaseRemainingCartPreview = resolve
    })
    let holdRemainingCartPreview = true
    const api = await mockGuestApi(page, {
      menuCategories: [
        {
          id: 21,
          name: 'Напитки',
          categoryType: 'DRINK',
          items: [
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
              effectiveItemType: 'DRINK'
            }
          ]
        }
      ],
      cartPreviewResponseResolver: async (request) => {
        const isRemainingCartPreview =
          request.items.length === 1 && request.items[0].itemId === 212 && request.items[0].qty === 1
        if (isRemainingCartPreview && holdRemainingCartPreview) {
          await remainingCartPreviewGate
          holdRemainingCartPreview = false
        }
        return { preview: buildStaleRecoveryPreview(request) }
      },
      addBatchResponseResolver: (request) => {
        const affectedLine = request.items.find((line) => line.itemId === 211)
        if (!affectedLine) {
          return submittedBatchResponse(request)
        }
        return {
          error: {
            status: 409,
            code: 'CART_MENU_SELECTION_UNAVAILABLE',
            message: 'Корзину нужно обновить.',
            details: {
              issues: [
                {
                  cartLineRef: affectedLine.cartLineRef,
                  itemId: 211,
                  optionId: null,
                  selectionKind: 'ITEM',
                  reason: scenario.reason
                }
              ]
            }
          }
        }
      }
    })

    await page.goto(
      `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
    )
    await page.getByRole('button', { name: /Напитки/ }).click()
    await page.locator('.menu-item').filter({ hasText: 'Вода' }).getByRole('button', { name: 'Добавить' }).click()
    await page.locator('.menu-item').filter({ hasText: 'Чай' }).getByRole('button', { name: 'Добавить' }).click()
    await page.getByRole('button', { name: 'Корзина (2)' }).click()
    await page.getByRole('button', { name: 'Отправить', exact: true }).click()
    await expect.poll(() => api.getAddBatchRequests()).toHaveLength(1)
    const staleKey = api.getAddBatchRequests()[0].idempotencyKey

    const waterLine = page.locator('.cart-item').filter({ hasText: 'Вода' })
    const teaLine = page.locator('.cart-item').filter({ hasText: 'Чай' })
    const warning = waterLine.locator('.cart-line-warning')
    await expect(warning.locator('p')).toHaveText(scenario.copy)
    await expect(warning.getByRole('button', { name: 'Вернуться в меню', exact: true })).toHaveCount(0)
    await expect(warning.getByRole('button', { name: /Выбрать другой вариант/ })).toHaveCount(0)
    await expect(page.locator('.cart-item')).toHaveCount(2)
    await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeDisabled()
    const retry = page.getByRole('button', { name: 'Повторить расчёт' })
    await expect(retry).toHaveClass(/button-secondary/)
    const primaryAction = warning.getByRole('button', {
      name: 'Удалить «Вода» и выбрать другую позицию',
      exact: true
    })
    await expect(primaryAction).toHaveText('Удалить и выбрать другую')
    await expect(
      warning.getByRole('button', { name: 'Удалить «Вода» из корзины', exact: true })
    ).toHaveText('Удалить из корзины')

    const previewsBeforeRecovery = api.getPreviewRequests().length
    await primaryAction.click()
    await expect.poll(() => api.getPreviewRequests().length).toBeGreaterThan(previewsBeforeRecovery)
    expect(
      api.getPreviewRequests().slice(previewsBeforeRecovery).some(
        (request) => request.items.length === 1 && request.items[0].itemId === 212 && request.items[0].qty === 1
      )
    ).toBe(true)
    await expect(page).toHaveURL(/#\/cart$/)
    await expect(teaLine).toBeFocused()
    releaseRemainingCartPreview()
    await expect(page).toHaveURL(/#\/venue\/1$/)
    const guestMenuHeading = page.locator('.venue-header h3')
    await expect(guestMenuHeading).toBeFocused()
    await expect(page.locator('.menu-category-button').filter({ hasText: 'Напитки' })).toBeVisible()
    await expect(page.locator('.menu-item')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Корзина (1)' })).toBeVisible()

    await page.getByRole('button', { name: 'Корзина (1)' }).click()
    await expect(waterLine).toHaveCount(0)
    await expect(teaLine).toBeVisible()
    await expect(
      page.getByRole('button', { name: 'Удалить «Вода» и выбрать другую позицию', exact: true })
    ).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Удалить «Вода» из корзины', exact: true })).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeEnabled()
    expect(api.getAddBatchRequests()).toHaveLength(1)
    await page.getByRole('button', { name: 'Отправить', exact: true }).click()
    await expect.poll(() => api.getAddBatchRequests()).toHaveLength(2)
    expect(api.getAddBatchRequests()[1].idempotencyKey).not.toBe(staleKey)
    expect(api.getAddBatchRequests()[1].items).toEqual([
      expect.objectContaining({ itemId: 212, qty: 1 })
    ])
  })
}

test('guest cart removes a removed item in place and recalculates the remaining line', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const menuCategories: GuestMenuCategory[] = [
    {
      id: 21,
      name: 'Напитки',
      categoryType: 'DRINK',
      items: [
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
          effectiveItemType: 'DRINK'
        }
      ]
    }
  ]
  const api = await mockGuestApi(page, {
    menuCategories,
    cartPreviewResponseResolver: (request) => {
      const removedLine = request.items.find((line) => line.itemId === 211)
      if (!removedLine) {
        return { preview: buildStaleRecoveryPreview(request) }
      }
      return {
        error: {
          status: 409,
          code: 'CART_MENU_SELECTION_UNAVAILABLE',
          message: 'Корзину нужно обновить.',
          details: {
            issues: [
              {
                cartLineRef: removedLine.cartLineRef,
                itemId: 211,
                optionId: null,
                selectionKind: 'ITEM',
                reason: 'REMOVED'
              }
            ]
          }
        }
      }
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Напитки/ }).click()
  await page.locator('.menu-item').filter({ hasText: 'Вода' }).getByRole('button', { name: 'Добавить' }).click()
  await page.locator('.menu-item').filter({ hasText: 'Чай' }).getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (2)' }).click()

  const waterLine = page.locator('.cart-item').filter({ hasText: 'Вода' })
  const teaLine = page.locator('.cart-item').filter({ hasText: 'Чай' })
  const warning = waterLine.locator('.cart-line-warning')
  await expect(warning).toHaveAttribute('role', 'alert')
  await expect(warning.locator('p')).toHaveText(
    'Позиции «Вода» больше нет в меню. Удалите её из корзины, чтобы продолжить заказ.'
  )
  await expect(waterLine).toHaveAttribute('aria-describedby', /cart-line-warning-/)
  await expect(teaLine).toBeVisible()
  await expect(warning.getByRole('button', { name: 'Вернуться в меню', exact: true })).toHaveCount(0)
  await expect(
    warning.getByRole('button', { name: 'Удалить «Вода» и выбрать другую позицию', exact: true })
  ).toHaveText('Удалить и выбрать другую')
  await expect(page.getByText('Не удалось рассчитать корзину. Повторите попытку.')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Отправить' })).toBeDisabled()
  expect(api.getAddBatchRequests()).toHaveLength(0)

  const previewsBeforeRemoval = api.getPreviewRequests().length
  await warning.getByRole('button', { name: 'Удалить «Вода» из корзины', exact: true }).click()
  await expect(page).toHaveURL(/#\/cart$/)
  await expect(waterLine).toHaveCount(0)
  await expect(teaLine).toBeVisible()
  await expect(teaLine).toBeFocused()
  await expect(
    page.getByRole('button', { name: 'Удалить «Вода» и выбрать другую позицию', exact: true })
  ).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Удалить «Вода» из корзины', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Отправить' })).toBeEnabled()
  await expect.poll(() => api.getPreviewRequests().length).toBeGreaterThan(previewsBeforeRemoval)
  expect(
    api.getPreviewRequests().slice(previewsBeforeRemoval).some(
      (request) => request.items.length === 1 && request.items[0].itemId === 212 && request.items[0].qty === 1
    )
  ).toBe(true)
  const totalRow = page.locator('.cart-preview-card .order-bill-total')
  await expect(totalRow).toContainText('К оплате')
  await expect(totalRow).toContainText('300')
})

test('guest cart keeps an unavailable-item warning across retry and recovers after re-enable', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  let responseMode: 'unavailable' | 'generic' | 'available' = 'unavailable'
  const api = await mockGuestApi(page, {
    menuCategories: [
      {
        id: 21,
        name: 'Напитки',
        categoryType: 'DRINK',
        items: [
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
    ],
    cartPreviewResponseResolver: (request) => {
      if (responseMode === 'available') {
        return { preview: buildStaleRecoveryPreview(request) }
      }
      if (responseMode === 'generic') {
        return {
          error: { status: 503, code: 'DATABASE_UNAVAILABLE', message: 'Временная ошибка базы данных.' }
        }
      }
      const line = request.items[0]
      return {
        error: {
          status: 409,
          code: 'CART_MENU_SELECTION_UNAVAILABLE',
          message: 'Корзину нужно обновить.',
          details: {
            issues: [
              {
                cartLineRef: line.cartLineRef,
                itemId: 211,
                optionId: null,
                selectionKind: 'ITEM',
                reason: 'UNAVAILABLE'
              }
            ]
          }
        }
      }
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Напитки/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()

  const warning = page.locator('.cart-line-warning')
  const unavailableCopy =
    'Позиция «Вода» временно недоступна. Чтобы продолжить заказ, удалите её из корзины и выберите другую позицию.'
  await expect(warning.locator('p')).toHaveText(unavailableCopy)
  await expect(warning.getByRole('button', { name: 'Вернуться в меню', exact: true })).toHaveCount(0)
  await expect(
    warning.getByRole('button', { name: 'Удалить «Вода» и выбрать другую позицию', exact: true })
  ).toHaveText('Удалить и выбрать другую')
  await expect(
    warning.getByRole('button', { name: 'Удалить «Вода» из корзины', exact: true })
  ).toHaveText('Удалить из корзины')
  const retry = page.getByRole('button', { name: 'Повторить расчёт' })
  await expect(retry).toHaveClass(/button-secondary/)
  await retry.click()
  await expect(warning.locator('p')).toHaveText(unavailableCopy)

  responseMode = 'generic'
  const requestsBeforeGenericFailure = api.getPreviewRequests().length
  await retry.click()
  await expect.poll(() => api.getPreviewRequests().length).toBeGreaterThan(requestsBeforeGenericFailure)
  await expect(page.getByText('Не удалось обновить расчёт. Исправьте отмеченные позиции или повторите проверку.')).toBeVisible()
  await expect(warning.locator('p')).toHaveText(unavailableCopy)
  await expect(page.getByText('Не удалось рассчитать корзину. Повторите попытку.')).toHaveCount(0)

  responseMode = 'available'
  await retry.click()
  await expect(warning).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Отправить' })).toBeEnabled()
  await expect(page.locator('.cart-preview-card')).toBeVisible()
  expect(api.getAddBatchRequests()).toHaveLength(0)
})

for (const scenario of [
  {
    reason: 'REMOVED' as const,
    label: 'removed',
    copy: 'Выбранного варианта «Ягодный» для позиции «Кальян» больше нет в меню.'
  },
  {
    reason: 'UNAVAILABLE' as const,
    label: 'unavailable',
    copy: 'Выбранный вариант «Ягодный» для позиции «Кальян» временно недоступен.'
  }
]) {
  test(`guest cart replaces an ${scenario.label} option through the current option picker`, async ({ page }) => {
    await installTelegramWebApp(page, 123456789)
    const currentMenu = (includeStaleOption: boolean): GuestMenuCategory[] => [
      {
        id: 20,
        name: 'Кальянное меню',
        categoryType: 'HOOKAH',
        items: [
          {
            id: 200,
            name: 'Кальян',
            priceMinor: 150000,
            currency: 'RUB',
            isAvailable: true,
            effectiveItemType: 'HOOKAH',
            options: [
              ...(includeStaleOption
                ? [{ id: 301, name: 'Ягодный', priceDeltaMinor: 0, isAvailable: true }]
                : []),
              { id: 302, name: 'Цитрус', priceDeltaMinor: 0, isAvailable: true }
            ]
          }
        ]
      }
    ]
    const api = await mockGuestApi(page, {
      menuCategories: currentMenu(true),
      cartPreviewResponseResolver: (request) => {
        const staleLine = request.items.find((line) => line.selectedOptionId === 301)
        if (!staleLine) {
          return { preview: buildStaleRecoveryPreview(request) }
        }
        return {
          error: {
            status: 409,
            code: 'CART_MENU_SELECTION_UNAVAILABLE',
            message: 'Корзину нужно обновить.',
            details: {
              issues: [
                {
                  cartLineRef: staleLine.cartLineRef,
                  itemId: 200,
                  optionId: 301,
                  selectionKind: 'OPTION',
                  reason: scenario.reason
                }
              ]
            }
          }
        }
      }
    })

    await page.goto(
      `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
    )
    await page.getByRole('button', { name: /Кальянное меню/ }).click()
    await page.getByRole('button', { name: 'Выбрать' }).click()
    await page.getByRole('button', { name: /Ягодный/ }).click()
    await page.getByLabel('Пожелания к приготовлению').fill('покрепче')
    await page.getByRole('button', { name: 'Добавить в корзину' }).click()
    await page.getByRole('button', { name: 'Корзина (1)' }).click()

    const staleLine = page.locator('.cart-item').filter({ hasText: 'Вариант: Ягодный' })
    const warning = staleLine.locator('.cart-line-warning')
    await expect(warning).toContainText(scenario.copy)
    await expect(warning).toContainText('Выберите другой вариант или удалите позицию из корзины.')
    await expect(warning.getByText('Удалить и выбрать другую', { exact: true })).toHaveCount(0)
    await expect(warning.getByText(/Чтобы продолжить заказ, удалите её из корзины/)).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Отправить' })).toBeDisabled()

    await staleLine.locator('.qty-input').fill('2')
    await staleLine.locator('.qty-input').press('Enter')
    api.setMenuCategories(currentMenu(false))
    const replaceButton = warning.getByRole('button', { name: 'Выбрать другой вариант для позиции Кальян' })
    await replaceButton.click()

    await expect(page.getByRole('heading', { name: 'Выберите вкус' })).toBeVisible()
    await expect(page.getByRole('button', { name: /Ягодный/ })).toHaveCount(0)
    const replacement = page.getByRole('button', { name: /Цитрус/ })
    await expect(replacement).toBeVisible()
    await expect(replacement).toBeFocused()
    await replacement.click()
    const note = page.getByLabel('Пожелания к приготовлению')
    await expect(note).toHaveValue('покрепче')
    await expect(note).toBeFocused()
    await page.getByRole('button', { name: 'Сохранить новый вариант' }).click()

    const correctedLine = page.locator('.cart-item').filter({ hasText: 'Вариант: Цитрус' })
    await expect(correctedLine).toBeVisible()
    await expect(correctedLine).toContainText('Пожелание: покрепче')
    await expect(correctedLine.locator('.qty-input')).toHaveValue('2')
    await expect(correctedLine.locator('.cart-line-warning')).toHaveCount(0)
    await expect(correctedLine).toBeFocused()
    await expect(page.getByRole('button', { name: 'Отправить' })).toBeEnabled()
    await expect.poll(() => api.getPreviewRequests().at(-1)?.items[0]).toEqual(
      expect.objectContaining({ itemId: 200, qty: 2, selectedOptionId: 302, preferenceNote: 'покрепче' })
    )
  })
}

test('guest cart renders all deterministic line issues and keeps the remaining issue after one fix', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    menuCategories: [
      {
        id: 20,
        name: 'Меню',
        categoryType: 'OTHER',
        items: [
          {
            id: 200,
            name: 'Кальян',
            priceMinor: 150000,
            currency: 'RUB',
            isAvailable: true,
            effectiveItemType: 'HOOKAH',
            options: [{ id: 301, name: 'Ягодный', priceDeltaMinor: 0, isAvailable: true }]
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
            effectiveItemType: 'DRINK'
          }
        ]
      }
    ],
    cartPreviewResponseResolver: (request) => {
      const issues: Array<Record<string, unknown>> = []
      const unavailableItem = request.items.find((line) => line.itemId === 211)
      const removedOption = request.items.find((line) => line.itemId === 200 && line.selectedOptionId === 301)
      if (unavailableItem) {
        issues.push({
          cartLineRef: unavailableItem.cartLineRef,
          itemId: 211,
          optionId: null,
          selectionKind: 'ITEM',
          reason: 'UNAVAILABLE'
        })
      }
      if (removedOption) {
        issues.push({
          cartLineRef: removedOption.cartLineRef,
          itemId: 200,
          optionId: 301,
          selectionKind: 'OPTION',
          reason: 'REMOVED'
        })
      }
      return issues.length
        ? {
            error: {
              status: 409,
              code: 'CART_MENU_SELECTION_UNAVAILABLE',
              message: 'Корзину нужно обновить.',
              details: { issues }
            }
          }
        : { preview: buildStaleRecoveryPreview(request) }
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.locator('.menu-category-button').filter({ hasText: 'Меню' }).click()
  await page.locator('.menu-item').filter({ hasText: 'Вода' }).getByRole('button', { name: 'Добавить' }).click()
  await page.locator('.menu-item').filter({ hasText: 'Чай' }).getByRole('button', { name: 'Добавить' }).click()
  await page.locator('.menu-item').filter({ hasText: 'Кальян' }).getByRole('button', { name: 'Выбрать' }).click()
  await page.getByRole('button', { name: /Ягодный/ }).click()
  await page.getByRole('button', { name: 'Добавить в корзину' }).click()
  await page.getByRole('button', { name: 'Корзина (3)' }).click()

  await expect(
    page.getByRole('alert').filter({ hasText: 'Некоторые позиции в корзине нужно обновить.' })
  ).toBeVisible()
  const waterLine = page.locator('.cart-item').filter({ hasText: 'Вода' })
  const hookahLine = page.locator('.cart-item').filter({ hasText: 'Кальян' })
  const teaLine = page.locator('.cart-item').filter({ hasText: 'Чай' })
  await expect(waterLine.locator('.cart-line-warning p')).toHaveText(
    'Позиция «Вода» временно недоступна. Чтобы продолжить заказ, удалите её из корзины и выберите другую позицию.'
  )
  await expect(hookahLine.locator('.cart-line-warning')).toContainText('больше нет в меню')
  await expect(teaLine.locator('.cart-line-warning')).toHaveCount(0)
  await expect(page.locator('.cart-item')).toHaveCount(3)
  await expect(page.getByRole('button', { name: 'Отправить' })).toBeDisabled()
  expect(api.getAddBatchRequests()).toHaveLength(0)

  await waterLine
    .locator('.cart-line-warning')
    .getByRole('button', { name: 'Удалить «Вода» из корзины', exact: true })
    .click()
  await expect(waterLine).toHaveCount(0)
  await expect(teaLine).toBeVisible()
  await expect(hookahLine.locator('.cart-line-warning')).toContainText('больше нет в меню')
  await expect(page.locator('.cart-line-warning[role="alert"]')).toHaveCount(1)
  await expect(page.getByRole('button', { name: 'Отправить' })).toBeDisabled()
  expect(api.getAddBatchRequests()).toHaveLength(0)

  await hookahLine
    .locator('.cart-line-warning')
    .getByRole('button', { name: 'Удалить из корзины: Кальян', exact: true })
    .click()
  await expect(hookahLine).toHaveCount(0)
  await expect(teaLine).toBeFocused()
  await expect(page.locator('.cart-line-warning')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Отправить' })).toBeEnabled()
  expect(api.getAddBatchRequests()).toHaveLength(0)
})

test('guest cart keeps unknown preview failures generic', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, {
    cartPreviewResponseResolver: () => ({
      error: { status: 503, code: 'DATABASE_UNAVAILABLE', message: 'Временная ошибка базы данных.' }
    })
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()

  await expect(page.getByText('Не удалось рассчитать корзину. Повторите попытку.')).toBeVisible()
  await expect(page.locator('.cart-line-warning')).toHaveCount(0)
  await expect(page.getByText(/больше нет в меню|временно недоступ/)).toHaveCount(0)
})

test('guest cart requires explicit fixed gift accept and clears the decision after a cart mutation', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const rewardItem: GiftRewardItemFixture = {
    menuItemId: 210,
    name: 'Чай',
    originalUnitPriceMinor: 45000,
    currency: 'RUB'
  }
  const giftOfferBase = {
    promotionId: 701,
    promotionTitle: 'Чай к кальяну',
    ruleId: 801,
    ruleVersion: 3,
    triggerLineId: 1,
    triggerMenuItemId: 200,
    triggerItemName: 'Double Apple',
    fixedRewardItem: rewardItem,
    selectableRewardItems: []
  }
  const api = await mockGuestApi(page, {
    cartPreviewResolver: (request) => {
      const triggerQty = request.items.find((item) => item.itemId === 200)?.qty ?? 0
      const triggerGross = triggerQty * 150000
      const accepted = request.giftDecision?.action === 'ACCEPT_FIXED'
      const skipped = request.giftDecision?.action === 'SKIP'
      const cartFingerprint = `fixed-gift-cart-${request.tableSessionId}-${request.tabId}-${triggerQty}-${request.comment ?? ''}`
      const decisionScopeToken = `fixed-gift-scope-${cartFingerprint}`
      return {
        grossTotalMinor: triggerGross + (accepted ? rewardItem.originalUnitPriceMinor : 0),
        promoDiscountTotalMinor: accepted ? rewardItem.originalUnitPriceMinor : 0,
        loyaltyDiscountTotalMinor: 0,
        finalPayableTotalMinor: triggerGross,
        currency: 'RUB',
        discounts: accepted
          ? [
              {
                label: 'Чай к кальяну',
                discountMinor: rewardItem.originalUnitPriceMinor,
                currency: 'RUB',
                ruleType: 'GIFT_WITH_ITEM',
                promotionId: 701,
                ruleId: 801,
                ruleVersion: 3,
                originalAmountMinor: rewardItem.originalUnitPriceMinor,
                finalAmountMinor: 0,
                eligibleLineIds: [1]
              }
            ]
          : [],
        items: [
          {
            itemId: 200,
            name: 'Double Apple',
            qty: triggerQty,
            priceMinor: 150000,
            currency: 'RUB',
            lineGrossMinor: triggerGross,
            discountMinor: 0,
            linePayableMinor: triggerGross,
            isPromotionReward: false,
            promotionAdjustment: null
          },
          ...(accepted
            ? [
                {
                  itemId: rewardItem.menuItemId,
                  name: rewardItem.name,
                  qty: 1,
                  priceMinor: rewardItem.originalUnitPriceMinor,
                  currency: rewardItem.currency,
                  lineGrossMinor: rewardItem.originalUnitPriceMinor,
                  discountMinor: rewardItem.originalUnitPriceMinor,
                  linePayableMinor: 0,
                  isPromotionReward: true,
                  promotionAdjustment: {
                    promotionId: 701,
                    promotionTitle: 'Чай к кальяну',
                    ruleId: 801,
                    ruleVersion: 3,
                    ruleType: 'GIFT_WITH_ITEM',
                    originalAmountMinor: rewardItem.originalUnitPriceMinor,
                    discountMinor: rewardItem.originalUnitPriceMinor,
                    finalAmountMinor: 0
                  }
                }
              ]
            : [])
        ],
        pricingFingerprint: `fixed-gift-${triggerQty}-${request.giftDecision?.action ?? 'offer'}`,
        cartFingerprint,
        decisionScopeToken,
        decisionScopeExpiresAtEpochSeconds: giftDecisionExpiresAtEpochSeconds,
        giftOffer: {
          ...giftOfferBase,
          status: accepted ? 'GIFT_SELECTED' : skipped ? 'GIFT_SKIPPED' : 'FIXED_GIFT_AVAILABLE',
          selectedRewardItem: accepted ? rewardItem : null
        }
      }
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()

  const giftCard = page.locator('.cart-gift-offer')
  await expect(giftCard).toContainText('Вам доступен подарок: Чай')
  await expect(page.locator('.cart-preview-gift-line')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeDisabled()
  await expect(page.getByText('Добавьте подарок или выберите «Пропустить подарок».')).toBeVisible()
  await giftCard.getByRole('button', { name: 'Добавить подарок' }).click()
  await expect(giftCard).toContainText('Подарок добавлен: Чай')
  await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeEnabled()
  const giftLine = page.locator('.cart-preview-gift-line')
  await expect(giftLine).toContainText(/Обычная стоимость.*450,00\s*₽/)
  await expect(giftLine).toContainText('скидка 100%')
  await expect(giftLine).toContainText(/К оплате.*0,00\s*₽/)
  expect(api.getPreviewRequests().at(-1)?.giftDecision).toMatchObject({
    action: 'ACCEPT_FIXED',
    decisionScopeToken: 'fixed-gift-scope-fixed-gift-cart-77-88-1-'
  })

  await page.locator('.cart-item').getByRole('button', { name: '+' }).click()
  await expect(giftCard).toContainText('Вам доступен подарок: Чай')
  await expect(page.locator('.cart-preview-gift-line')).toHaveCount(0)
  expect(api.getPreviewRequests().at(-1)?.giftDecision ?? null).toBeNull()

  await giftCard.getByRole('button', { name: 'Пропустить подарок' }).click()
  await expect(giftCard).toContainText('Вы пропустили подарок.')
  await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeEnabled()
  expect(api.getPreviewRequests().at(-1)?.giftDecision).toMatchObject({
    action: 'SKIP',
    decisionScopeToken: 'fixed-gift-scope-fixed-gift-cart-77-88-2-'
  })

  await page.getByPlaceholder('Комментарий к заказу').fill('без сахара')
  await expect.poll(() => api.getPreviewRequests().at(-1)?.comment).toBe('без сахара')
  expect(api.getPreviewRequests().at(-1)?.giftDecision ?? null).toBeNull()

  const minusButton = page.locator('.cart-item').getByRole('button', { name: '−' })
  await minusButton.click()
  await minusButton.click()
  await expect(page.locator('.cart-item')).toHaveCount(0)
  const persistedAfterTriggerRemoval = await page.evaluate((key) => {
    const raw = window.localStorage.getItem(key)
    return raw ? JSON.parse(raw) as Record<string, unknown> : null
  }, `hookah_guest_cart_draft:user:123456789:${tableToken}`)
  expect(persistedAfterTriggerRemoval?.giftDecisionDraft).toBeUndefined()
})

test('initial tab restore rejects a gift draft from a replaced table session on the same QR', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    cartPreviewResolver: buildScopedFixedGiftPreview
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()
  await page.getByRole('button', { name: 'Добавить подарок' }).click()
  await expect(page.getByText('Подарок добавлен: Чай')).toBeVisible()
  expect(api.getPreviewRequests().at(-1)?.giftDecision?.decisionScopeToken).toContain(
    'scoped-fixed-cart:77:88'
  )

  api.setTableScope({
    venueId: 1,
    tableSessionId: 177,
    tabId: 188,
    ownerUserId: 123456789
  })
  const requestsBeforeReload = api.getPreviewRequests().length
  await page.reload()

  await expect
    .poll(() =>
      api
        .getPreviewRequests()
        .slice(requestsBeforeReload)
        .filter((request) => request.tableSessionId === 177 && request.tabId === 188).length
    )
    .toBeGreaterThan(0)
  const replacementRequests = api
    .getPreviewRequests()
    .slice(requestsBeforeReload)
    .filter((request) => request.tableSessionId === 177 && request.tabId === 188)
  expect(replacementRequests.every((request) => request.giftDecision == null)).toBe(true)
  await expect(page.locator('.cart-gift-offer')).toContainText('Вам доступен подарок: Чай')
  await expect(page.getByText('Подарок добавлен: Чай')).toHaveCount(0)
  const persistedDraft = await page.evaluate((key) => {
    const raw = window.localStorage.getItem(key)
    return raw ? JSON.parse(raw) as Record<string, unknown> : null
  }, `hookah_guest_cart_draft:user:123456789:${tableToken}`)
  expect(persistedDraft?.giftDecisionDraft).toBeUndefined()
})

test('switching tab in the same table session clears the gift decision', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    cartPreviewResolver: buildScopedFixedGiftPreview,
    tabs: [
      {
        id: 88,
        tableSessionId: 77,
        type: 'PERSONAL',
        ownerUserId: 123456789,
        status: 'ACTIVE'
      },
      {
        id: 89,
        tableSessionId: 77,
        type: 'SHARED',
        ownerUserId: 987654321,
        status: 'ACTIVE'
      }
    ]
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()
  await page.getByRole('button', { name: 'Добавить подарок' }).click()
  await expect(page.getByText('Подарок добавлен: Чай')).toBeVisible()
  expect(api.getPreviewRequests().at(-1)?.tabId).toBe(88)
  expect(api.getPreviewRequests().at(-1)?.giftDecision?.action).toBe('ACCEPT_FIXED')

  await page.getByRole('button', { name: 'Переключить на Общий счёт' }).click()

  await expect.poll(() => api.getPreviewRequests().at(-1)?.tabId).toBe(89)
  expect(api.getPreviewRequests().at(-1)?.tableSessionId).toBe(77)
  expect(api.getPreviewRequests().at(-1)?.giftDecision ?? null).toBeNull()
  await expect(page.locator('.cart-gift-offer')).toContainText('Вам доступен подарок: Чай')
})

test('expired gift scope is not restored from LocalStorage', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, {
    cartPreviewResolver: buildScopedFixedGiftPreview
  })
  const draftKey = `hookah_guest_cart_draft:user:123456789:${tableToken}`

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()
  await page.getByRole('button', { name: 'Добавить подарок' }).click()
  await expect(page.getByText('Подарок добавлен: Чай')).toBeVisible()

  await page.evaluate((key) => {
    const raw = window.localStorage.getItem(key)
    if (!raw) throw new Error('Expected persisted cart draft')
    const draft = JSON.parse(raw) as {
      giftDecisionDraft?: { expiresAtEpochSeconds?: number }
    }
    if (!draft.giftDecisionDraft) throw new Error('Expected persisted gift decision')
    draft.giftDecisionDraft.expiresAtEpochSeconds = 1
    window.localStorage.setItem(key, JSON.stringify(draft))
  }, draftKey)
  const requestsBeforeReload = api.getPreviewRequests().length
  await page.reload()

  await expect.poll(() => api.getPreviewRequests().length).toBeGreaterThan(requestsBeforeReload)
  expect(api.getPreviewRequests().slice(requestsBeforeReload).every((request) => request.giftDecision == null)).toBe(true)
  await expect(page.locator('.cart-gift-offer')).toContainText('Вам доступен подарок: Чай')
  const persistedDraft = await page.evaluate((key) => {
    const raw = window.localStorage.getItem(key)
    return raw ? JSON.parse(raw) as Record<string, unknown> : null
  }, draftKey)
  expect(persistedDraft?.giftDecisionDraft).toBeUndefined()
})

test('account switch clears the prior user gift decision cache', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, {
    isolateFavoriteUsers: true,
    cartPreviewResolver: buildScopedFixedGiftPreview
  })
  const firstUserDraftKey = `hookah_guest_cart_draft:user:123456789:${tableToken}`

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()
  await page.getByRole('button', { name: 'Добавить подарок' }).click()
  await expect(page.getByText('Подарок добавлен: Чай')).toBeVisible()

  await page.evaluate(
    ({ userId, initData }) => {
      window.localStorage.setItem('__e2e_telegram_user_id', String(userId))
      window.localStorage.setItem('__e2e_telegram_init_data', initData)
    },
    { userId: 987654321, initData: otherMockInitData }
  )
  await page.reload()

  await expect(page.locator('.cart-item')).toHaveCount(0)
  const priorUserDraft = await page.evaluate((key) => {
    const raw = window.localStorage.getItem(key)
    return raw ? JSON.parse(raw) as Record<string, unknown> : null
  }, firstUserDraftKey)
  expect(priorUserDraft?.giftDecisionDraft).toBeUndefined()
})

test('guest selects one allowlisted gift and duplicate submit sends one authoritative decision', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const rewardItems: GiftRewardItemFixture[] = [
    { menuItemId: 210, name: 'Чай', originalUnitPriceMinor: 45000, currency: 'RUB' },
    { menuItemId: 211, name: 'Лимонад', originalUnitPriceMinor: 55000, currency: 'RUB' }
  ]
  let lastPricing: CartPreviewFixture | null = null
  const api = await mockGuestApi(page, {
    cartPreviewResolver: (request) => {
      const selectedItem =
        request.giftDecision?.action === 'SELECT_ITEM'
          ? rewardItems.find((item) => item.menuItemId === request.giftDecision?.selectedMenuItemId) ?? null
          : null
      lastPricing = {
        grossTotalMinor: 150000 + (selectedItem?.originalUnitPriceMinor ?? 0),
        promoDiscountTotalMinor: selectedItem?.originalUnitPriceMinor ?? 0,
        loyaltyDiscountTotalMinor: 0,
        finalPayableTotalMinor: 150000,
        currency: 'RUB',
        discounts: [],
        items: [
          {
            itemId: 200,
            name: 'Double Apple',
            qty: 1,
            priceMinor: 150000,
            currency: 'RUB',
            lineGrossMinor: 150000,
            discountMinor: 0,
            linePayableMinor: 150000,
            isPromotionReward: false
          },
          ...(selectedItem
            ? [
                {
                  itemId: selectedItem.menuItemId,
                  name: selectedItem.name,
                  qty: 1,
                  priceMinor: selectedItem.originalUnitPriceMinor,
                  currency: selectedItem.currency,
                  lineGrossMinor: selectedItem.originalUnitPriceMinor,
                  discountMinor: selectedItem.originalUnitPriceMinor,
                  linePayableMinor: 0,
                  isPromotionReward: true,
                  promotionAdjustment: {
                    promotionId: 702,
                    promotionTitle: 'Напиток в подарок',
                    ruleId: 802,
                    ruleVersion: 4,
                    ruleType: 'GIFT_WITH_ITEM',
                    originalAmountMinor: selectedItem.originalUnitPriceMinor,
                    discountMinor: selectedItem.originalUnitPriceMinor,
                    finalAmountMinor: 0
                  }
                }
              ]
            : [])
        ],
        pricingFingerprint: `choice-gift-${selectedItem?.menuItemId ?? 'offer'}`,
        cartFingerprint: 'choice-gift-cart-77-88-1',
        decisionScopeToken: 'choice-gift-scope-77-88-1',
        decisionScopeExpiresAtEpochSeconds: giftDecisionExpiresAtEpochSeconds,
        giftOffer: {
          status: selectedItem ? 'GIFT_SELECTED' : 'GIFT_CHOICE_REQUIRED',
          promotionId: 702,
          promotionTitle: 'Напиток в подарок',
          ruleId: 802,
          ruleVersion: 4,
          triggerLineId: 1,
          triggerMenuItemId: 200,
          triggerItemName: 'Double Apple',
          selectableRewardItems: rewardItems,
          selectedRewardItem: selectedItem
        }
      }
      return lastPricing
    },
    addBatchResponseResolver: () => {
      if (!lastPricing) {
        throw new Error('Expected a cart preview before submit')
      }
      return {
        submitted: true,
        orderId: 900,
        batchId: 444,
        pricing: lastPricing,
        recalculated: false
      }
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()

  const giftCard = page.locator('.cart-gift-offer')
  const lemonadeChoice = giftCard.getByRole('button', { name: /Лимонад/ })
  await lemonadeChoice.click()
  await expect(lemonadeChoice).toHaveAttribute('aria-pressed', 'true')
  await giftCard.getByRole('button', { name: 'Добавить выбранный подарок' }).click()
  await expect(giftCard).toContainText('Подарок добавлен: Лимонад')
  await page.getByRole('button', { name: 'Отправить', exact: true }).dblclick()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(1)
  expect(api.getAddBatchRequests()[0].giftDecision).toEqual({
    action: 'SELECT_ITEM',
    selectedMenuItemId: 211,
    decisionScopeToken: 'choice-gift-scope-77-88-1'
  })
})

test('guest can submit an ordinary order while the gift is unavailable', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const unavailablePreview: CartPreviewFixture = {
    grossTotalMinor: 150000,
    promoDiscountTotalMinor: 0,
    loyaltyDiscountTotalMinor: 0,
    finalPayableTotalMinor: 150000,
    currency: 'RUB',
    discounts: [],
    items: [
      {
        itemId: 200,
        name: 'Double Apple',
        qty: 1,
        priceMinor: 150000,
        currency: 'RUB',
        lineGrossMinor: 150000,
        discountMinor: 0,
        linePayableMinor: 150000,
        isPromotionReward: false
      }
    ],
    pricingFingerprint: 'gift-unavailable',
    cartFingerprint: 'gift-unavailable-cart',
    giftOffer: {
      status: 'GIFT_UNAVAILABLE',
      promotionId: 703,
      promotionTitle: 'Подарок к кальяну',
      ruleId: 803,
      ruleVersion: 1,
      triggerMenuItemId: 200,
      triggerItemName: 'Double Apple',
      selectableRewardItems: [],
      unavailableReason: 'NO_AVAILABLE_REWARD_ITEMS'
    }
  }
  const api = await mockGuestApi(page, {
    cartPreview: unavailablePreview,
    addBatchResponse: {
      submitted: true,
      orderId: 900,
      batchId: 444,
      pricing: unavailablePreview,
      recalculated: false
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()

  await expect(page.getByText('Подарок по акции сейчас недоступен.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(1)
  expect(api.getAddBatchRequests()[0].giftDecision ?? null).toBeNull()
})

test('submitted false keeps the cart, clears a stale gift decision and allows an ordinary retry', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const rewardItem: GiftRewardItemFixture = {
    menuItemId: 210,
    name: 'Чай',
    originalUnitPriceMinor: 45000,
    currency: 'RUB'
  }
  let rewardUnavailable = false
  let submitCount = 0
  const buildPricing = (decision?: GiftDecisionFixture | null): CartPreviewFixture => {
    const selected = !rewardUnavailable && decision?.action === 'ACCEPT_FIXED'
    return {
      grossTotalMinor: 150000 + (selected ? rewardItem.originalUnitPriceMinor : 0),
      promoDiscountTotalMinor: selected ? rewardItem.originalUnitPriceMinor : 0,
      loyaltyDiscountTotalMinor: 0,
      finalPayableTotalMinor: 150000,
      currency: 'RUB',
      discounts: [],
      items: [
        {
          itemId: 200,
          name: 'Double Apple',
          qty: 1,
          priceMinor: 150000,
          currency: 'RUB',
          lineGrossMinor: 150000,
          discountMinor: 0,
          linePayableMinor: 150000,
          isPromotionReward: false
        }
      ],
      pricingFingerprint: rewardUnavailable ? 'fixed-stale-unavailable' : `fixed-stale-${selected ? 'selected' : 'offer'}`,
      cartFingerprint: 'fixed-stale-cart-77-88-1',
      decisionScopeToken: 'fixed-stale-scope-77-88-1',
      decisionScopeExpiresAtEpochSeconds: giftDecisionExpiresAtEpochSeconds,
      giftDecisionStale: rewardUnavailable && decision != null,
      giftDecisionMessage:
        rewardUnavailable && decision != null
          ? 'Корзина изменилась. Проверьте подарок ещё раз.'
          : null,
      giftOffer: {
        status: rewardUnavailable ? 'GIFT_UNAVAILABLE' : selected ? 'GIFT_SELECTED' : 'FIXED_GIFT_AVAILABLE',
        promotionId: 704,
        promotionTitle: 'Чай к кальяну',
        ruleId: 804,
        ruleVersion: 2,
        triggerMenuItemId: 200,
        triggerItemName: 'Double Apple',
        fixedRewardItem: rewardItem,
        selectableRewardItems: [],
        selectedRewardItem: selected ? rewardItem : null,
        unavailableReason: rewardUnavailable ? 'REWARD_UNAVAILABLE' : null
      }
    }
  }
  const api = await mockGuestApi(page, {
    cartPreviewResolver: (request) => buildPricing(request.giftDecision),
    addBatchResponseResolver: () => {
      submitCount += 1
      if (submitCount === 1) {
        rewardUnavailable = true
        return {
          submitted: false,
          pricing: buildPricing(null),
          recalculated: true
        }
      }
      return {
        submitted: true,
        orderId: 900,
        batchId: 444,
        pricing: buildPricing(null),
        recalculated: false
      }
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await page.getByRole('button', { name: 'Корзина (1)' }).click()
  await page.getByRole('button', { name: 'Добавить подарок' }).click()
  await expect(page.getByText('Подарок добавлен: Чай')).toBeVisible()

  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect(page.getByText('Корзина изменилась. Проверьте подарок ещё раз.')).toBeVisible()
  await expect(page.locator('.cart-item')).toContainText('Double Apple')
  await expect(page.getByText('Подарок по акции сейчас недоступен.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeEnabled()
  expect(api.getAddBatchRequests()[0].giftDecision?.action).toBe('ACCEPT_FIXED')

  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect.poll(() => api.getAddBatchRequests()).toHaveLength(2)
  expect(api.getAddBatchRequests()[1].giftDecision ?? null).toBeNull()
})

test('persisted gift facts are visible in the active order and guest history', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, {
    activeOrder: {
      batchStatus: 'ACCEPTED',
      giftReward: {
        itemId: 210,
        name: 'Чай',
        priceMinor: 45000,
        currency: 'RUB'
      }
    },
    visitHistory: {
      items: [
        {
          visitId: 31,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-11T18:30:00Z',
          serviceDate: '2030-01-11',
          source: 'order_closed',
          totalMinor: 150000,
          currency: 'RUB',
          hasBooking: false,
          orderLabels: ['№77']
        }
      ],
      details: {
        31: {
          visitId: 31,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-11T18:30:00Z',
          serviceDate: '2030-01-11',
          source: 'order_closed',
          booking: null,
          orders: [
            {
              orderId: 931,
              displayNumber: 77,
              displayDate: '2030-01-11',
              totalMinor: 150000,
              currency: 'RUB',
              promotionDiscounts: [
                {
                  label: 'Чай к кальяну',
                  discountMinor: 45000,
                  currency: 'RUB',
                  originalAmountMinor: 45000,
                  finalAmountMinor: 0
                }
              ],
              items: [
                {
                  itemId: 200,
                  itemName: 'Double Apple',
                  qty: 1,
                  priceMinor: 150000,
                  currency: 'RUB',
                  totalMinor: 150000,
                  promoDiscountMinor: 0,
                  isPromotionReward: false
                },
                {
                  itemId: 210,
                  itemName: 'Чай',
                  qty: 1,
                  priceMinor: 45000,
                  currency: 'RUB',
                  totalMinor: 0,
                  promoDiscountMinor: 45000,
                  isPromotionReward: true
                }
              ]
            }
          ],
          totalMinor: 150000,
          currency: 'RUB',
          feedback: {
            eligible: false,
            submitted: false,
            rating: null,
            tags: [],
            comment: null
          }
        }
      }
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: 'Мой заказ', exact: true }).click()
  const activeGiftLine = page.locator('.order-item-promotion-reward').filter({ hasText: 'Чай' })
  await expect(activeGiftLine).toContainText('Подарок по акции')
  await expect(activeGiftLine).toContainText(/450,00\s*₽/)
  await expect(activeGiftLine).toContainText(/акция 100% −450,00\s*₽/)
  await expect(activeGiftLine).toContainText(/к оплате 0,00\s*₽/)

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Профиль' }).click()
  await page.getByRole('button', { name: '🕘 История' }).click()
  await page.getByRole('button', { name: 'Подробнее' }).click()
  const historyGiftLine = page.getByText(/Подарок по акции · Чай/)
  await expect(historyGiftLine).toContainText(/обычная стоимость 450,00\s*₽/)
  await expect(historyGiftLine).toContainText(/акция −450,00\s*₽/)
  await expect(historyGiftLine).toContainText(/итого 0,00\s*₽/)
})

test('coupled gift trigger cancel and exclude stay out of the active bill and remain explained in history', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, {
    activeOrder: {
      batchStatus: 'ACCEPTED'
    },
    visitHistory: {
      items: [
        {
          visitId: 32,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-12T18:30:00Z',
          serviceDate: '2030-01-12',
          source: 'order_closed',
          totalMinor: 0,
          currency: 'RUB',
          hasBooking: false,
          orderLabels: ['№71', '№72']
        }
      ],
      details: {
        32: {
          visitId: 32,
          venueId: 1,
          venueName: 'Микс',
          venueCity: 'Москва',
          occurredAt: '2030-01-12T18:30:00Z',
          serviceDate: '2030-01-12',
          source: 'order_closed',
          booking: null,
          orders: [
            {
              orderId: 932,
              displayNumber: 71,
              displayDate: '2030-01-12',
              totalMinor: 0,
              currency: 'RUB',
              promotionDiscounts: [
                {
                  label: 'Чай к кальяну — отмена',
                  discountMinor: 45000,
                  currency: 'RUB',
                  originalAmountMinor: 45000,
                  finalAmountMinor: 0,
                  isActive: false
                }
              ],
              items: [
                {
                  itemId: 220,
                  itemName: 'Кальян отменённый',
                  qty: 1,
                  priceMinor: 150000,
                  currency: 'RUB',
                  totalMinor: 0,
                  promoDiscountMinor: 0,
                  isPromotionReward: false,
                  isExcluded: false,
                  itemStatus: 'CANCELED',
                  canceledReasonText: 'Позиция недоступна.',
                  promotionLinkRole: 'TRIGGER',
                  promotionLabel: 'Чай к кальяну — отмена'
                },
                {
                  itemId: 221,
                  itemName: 'Чай отменённый',
                  qty: 1,
                  priceMinor: 45000,
                  currency: 'RUB',
                  totalMinor: 0,
                  promoDiscountMinor: 45000,
                  isPromotionReward: true,
                  isExcluded: false,
                  itemStatus: 'CANCELED',
                  canceledReasonText: 'Связанный подарок отменён вместе с условием акции.',
                  promotionLinkRole: 'REWARD',
                  promotionLabel: 'Чай к кальяну — отмена'
                }
              ]
            },
            {
              orderId: 933,
              displayNumber: 72,
              displayDate: '2030-01-12',
              totalMinor: 0,
              currency: 'RUB',
              promotionDiscounts: [
                {
                  label: 'Лимонад к кальяну — исключение',
                  discountMinor: 30000,
                  currency: 'RUB',
                  originalAmountMinor: 30000,
                  finalAmountMinor: 0,
                  isActive: false
                }
              ],
              items: [
                {
                  itemId: 222,
                  itemName: 'Кальян исключённый',
                  qty: 1,
                  priceMinor: 140000,
                  currency: 'RUB',
                  totalMinor: 0,
                  promoDiscountMinor: 0,
                  isPromotionReward: false,
                  isExcluded: true,
                  excludedReasonText: 'Исключено заведением.',
                  itemStatus: 'ACTIVE',
                  promotionLinkRole: 'TRIGGER',
                  promotionLabel: 'Лимонад к кальяну — исключение'
                },
                {
                  itemId: 223,
                  itemName: 'Лимонад исключённый',
                  qty: 1,
                  priceMinor: 30000,
                  currency: 'RUB',
                  totalMinor: 0,
                  promoDiscountMinor: 30000,
                  isPromotionReward: true,
                  isExcluded: true,
                  excludedReasonText: 'Связанный подарок исключён вместе с условием акции.',
                  itemStatus: 'ACTIVE',
                  promotionLinkRole: 'REWARD',
                  promotionLabel: 'Лимонад к кальяну — исключение'
                }
              ]
            }
          ],
          totalMinor: 0,
          currency: 'RUB',
          feedback: {
            eligible: false,
            submitted: false,
            rating: null,
            tags: [],
            comment: null
          }
        }
      }
    }
  })

  await page.goto(
    `?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )
  await page.getByRole('button', { name: 'Мой заказ', exact: true }).click()
  const activeBill = page.locator('.order-screen')
  await expect(activeBill.getByText('Double Apple', { exact: true })).toBeVisible()
  await expect(activeBill.locator('.order-item-promotion-reward')).toHaveCount(0)
  await expect(activeBill.getByText('Кальян отменённый', { exact: true })).toHaveCount(0)
  await expect(activeBill.getByText('Чай отменённый', { exact: true })).toHaveCount(0)
  await expect(activeBill.getByText('Кальян исключённый', { exact: true })).toHaveCount(0)
  await expect(activeBill.getByText('Лимонад исключённый', { exact: true })).toHaveCount(0)
  await expect(activeBill.locator('.venue-order-bill')).toContainText(/К оплате\s*1\s*500,00\s*₽/)

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Профиль' }).click()
  await page.getByRole('button', { name: '🕘 История' }).click()
  await page.getByRole('button', { name: 'Подробнее' }).click()

  const canceledOrder = page.locator('.card').filter({ hasText: 'Заказ №71' })
  await expect(canceledOrder).toContainText('Кальян отменённый')
  await expect(canceledOrder).toContainText('Условие акции «Чай к кальяну — отмена»')
  await expect(canceledOrder).toContainText('Отменено: Позиция недоступна.')
  await expect(canceledOrder).toContainText('Подарок по акции · Чай отменённый')
  await expect(canceledOrder).toContainText('Отменено: Связанный подарок отменён вместе с условием акции.')
  await expect(canceledOrder).toContainText(/Чай к кальяну — отмена: 450,00\s*₽ − 450,00\s*₽ = 0,00\s*₽ · больше не действует/)

  const excludedOrder = page.locator('.card').filter({ hasText: 'Заказ №72' })
  await expect(excludedOrder).toContainText('Кальян исключённый')
  await expect(excludedOrder).toContainText('Условие акции «Лимонад к кальяну — исключение»')
  await expect(excludedOrder).toContainText('Исключено из счёта: Исключено заведением.')
  await expect(excludedOrder).toContainText('Подарок по акции · Лимонад исключённый')
  await expect(excludedOrder).toContainText('Исключено из счёта: Связанный подарок исключён вместе с условием акции.')
  await expect(excludedOrder).toContainText(/Лимонад к кальяну — исключение: 300,00\s*₽ − 300,00\s*₽ = 0,00\s*₽ · больше не действует/)
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

  const appleLines = page.locator('.cart-item').filter({ hasText: 'Вариант: Яблоко' })
  const appleLine = page.locator('.cart-item').filter({ hasText: 'Пожелание: поменьше холодка' })
  const appleNoMintLine = page.locator('.cart-item').filter({ hasText: 'Пожелание: без мяты' })
  const mintLine = page.locator('.cart-item').filter({ hasText: 'Вариант: Мята' })
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
      expect.objectContaining({ itemId: 210, qty: 1, selectedOptionId: 301 }),
      expect.objectContaining({
        itemId: 210,
        qty: 2,
        selectedOptionId: 301,
        preferenceNote: 'поменьше холодка'
      }),
      expect.objectContaining({ itemId: 210, qty: 1, selectedOptionId: 301, preferenceNote: 'без мяты' }),
      expect.objectContaining({ itemId: 210, qty: 1, selectedOptionId: 302 })
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

test('venue bill renders persisted happy hours amounts without recalculating them', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueShiftExtensionApi(page, { promotionFacts: true })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказы' }).click()
  await page.getByRole('button', { name: 'Открыть' }).click()

  const bill = page.locator('.venue-order-bill')
  await expect(bill.getByRole('heading', { name: 'Счёт' })).toBeVisible()
  await expect(bill).toContainText(/Сумма до скидок.*1[\s\u00a0]200,00\s*₽/)
  await expect(bill).toContainText(/Счастливые часы.*−600,00\s*₽/)
  await expect(bill).toContainText(/К оплате.*600,00\s*₽/)

  const batch = page.locator('.order-batch').filter({ hasText: 'Основная заявка' })
  await expect(batch).toContainText('Скидки по заявке')
  await expect(batch).toContainText(/Счастливые часы.*−600,00\s*₽/)
  await expect(batch).toContainText(/Акции.*−600,00\s*₽.*к оплате.*600,00\s*₽/)
})

test('venue bill hides manual discount action for a trigger with an active linked gift', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueShiftExtensionApi(page, {
    role: 'OWNER',
    activePromotionReward: true
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказы' }).click()
  await page.getByRole('button', { name: 'Открыть' }).click()

  const triggerRow = page.locator('.order-item').filter({ hasText: 'Double Apple' })
  await expect(triggerRow).toContainText(
    'На эту позицию уже действует акция. Ручную скидку применить нельзя.'
  )
  await expect(triggerRow.getByRole('button', { name: /^Скидка/ })).toHaveCount(0)
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
    'Приглашение в Микс. Роль: Менеджер. Откройте ссылку, чтобы принять доступ.'
  )}`

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Персонал' })).toBeVisible()
  await page.locator('.venue-staff select.venue-select').first().selectOption('MANAGER')
  await page.getByRole('button', { name: 'Добавить сотрудника' }).click()

  await expect(page.locator('.venue-invite-result')).toContainText('Менеджер')
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
    'Отправьте эту ссылку сотруднику. Он откроет её в Telegram и получит роль Менеджер в заведении «Микс».'
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

test('venue manager creates and revokes only staff invites', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, {
    role: 'MANAGER',
    linked: true,
    pendingInvites: [
      {
        handle: 'existing-staff',
        role: 'STAFF',
        status: 'PENDING',
        createdAt: '2030-01-10T18:00:00Z',
        expiresAt: '2030-01-17T18:00:00Z'
      },
      {
        handle: 'hidden-manager',
        role: 'MANAGER',
        status: 'PENDING',
        createdAt: '2030-01-10T18:00:00Z',
        expiresAt: '2030-01-17T18:00:00Z'
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()

  const accessCard = page.locator('.venue-staff > .card').first()
  const roleSelect = accessCard.getByLabel('Роль приглашения')
  await expect(accessCard.getByRole('heading', { name: 'Доступ сотрудников' })).toBeVisible()
  await expect(accessCard.getByRole('button', { name: 'Добавить сотрудника' })).toBeVisible()
  await expect(roleSelect.getByRole('option')).toHaveCount(1)
  await expect(roleSelect.getByRole('option', { name: 'Сотрудник' })).toHaveText('Сотрудник')
  await expect(roleSelect.getByRole('option', { name: 'Менеджер' })).toHaveCount(0)
  await expect(roleSelect.getByRole('option', { name: 'Владелец' })).toHaveCount(0)

  const pending = accessCard.locator('.venue-pending-invite-list')
  await expect(pending.locator('.venue-pending-invite-row')).toHaveCount(1)
  await expect(pending).toContainText('Сотрудник')
  await expect(pending).toContainText('Статус: ожидает принятия')
  await expect(pending).toContainText('Создано:')
  await expect(pending).toContainText('Действует до:')
  await expect(pending).not.toContainText('Менеджер')
  await expect(pending).not.toContainText('username')
  await expect(pending).not.toContainText('User ')

  await accessCard.getByRole('button', { name: 'Добавить сотрудника' }).click()
  await expect.poll(() => api.getStaffInviteRequests()).toEqual([{ role: 'STAFF' }])
  await expect(accessCard.locator('.venue-invite-result')).toContainText('Сотрудник')
  await expect(pending.locator('.venue-pending-invite-row')).toHaveCount(2)

  await pending.getByRole('button', { name: 'Отозвать приглашение' }).last().click()
  await expect.poll(() => api.getRevokedInviteHandles()).toEqual(['pending-1'])
  await expect(pending.locator('.venue-pending-invite-row')).toHaveCount(1)
  expect(api.getPendingInvites().some((invite) => invite.handle === 'hidden-manager')).toBe(true)
})

test('venue manager manages staff and display cards while protected cards stay read only', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, { role: 'MANAGER', linked: true })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()

  const accessCard = page.locator('.venue-staff > .card').first()
  const svetlanaMember = accessCard.locator('.venue-staff-row').filter({ hasText: 'Светлана Орлова' })
  const maximMember = accessCard.locator('.venue-staff-row').filter({ hasText: 'Максим Катаев' })
  const annaMember = accessCard.locator('.venue-staff-row').filter({ hasText: 'Анна Петрова' })
  const safeUsernameMember = accessCard.locator('.venue-staff-row').filter({ hasText: 'Ирина Безопасная' })
  await expect(svetlanaMember).toContainText('@sveta_staff')
  await expect(svetlanaMember.locator('.venue-staff-role-badge')).toHaveText('Сотрудник')
  await expect(svetlanaMember).toContainText('Привязан к карточке «Светлана»')
  await expect(svetlanaMember.getByRole('button', { name: 'Открыть карточку' })).toBeVisible()
  await expect(maximMember).toContainText('@max_kataev')
  await expect(maximMember).toContainText('Карточка не создана')
  await expect(maximMember.getByRole('button', { name: 'Создать карточку' })).toBeVisible()
  await expect(annaMember).toContainText('Без username · ID …5555')
  await expect(safeUsernameMember).toContainText('@safe_<script>&"')
  await expect(safeUsernameMember.locator('script')).toHaveCount(0)
  await expect(accessCard).not.toContainText('Алексей Морозов')
  await expect(accessCard).not.toContainText('Другой Менеджер')
  await expect(accessCard).not.toContainText('Владелец Заведения')
  await expect(accessCard).not.toContainText('222222222')
  await expect(accessCard).not.toContainText('444444444')
  await expect(accessCard).not.toContainText('555555555')

  const cards = page.locator('.venue-public-staff')
  await expect(cards.getByRole('heading', { name: 'Карточки команды' })).toBeVisible()
  await expect(cards).toContainText('Карточками владельцев и других менеджеров управляет владелец.')
  await expect(cards.getByRole('button', { name: 'Добавить карточку сотрудника' })).toBeVisible()

  const otherManager = cards.locator('.venue-profile-row').filter({ hasText: 'Другой менеджер' })
  const owner = cards.locator('.venue-profile-row').filter({ hasText: 'Владелец заведения' })
  for (const protectedRow of [otherManager, owner]) {
    await expect(protectedRow).toContainText('Только просмотр — карточкой управляет владелец.')
    await expect(protectedRow.getByRole('button', { name: 'Редактировать' })).toHaveCount(0)
    await expect(protectedRow.getByRole('button', { name: /Опубликовать|Скрыть/ })).toHaveCount(0)
    await expect(protectedRow.getByRole('button', { name: 'Сегодня на смене' })).toHaveCount(0)
  }

  const ownManagerCard = cards.locator('.venue-profile-row').filter({ hasText: 'Алексей' })
  await ownManagerCard.getByRole('button', { name: 'Редактировать' }).click()
  await expect(ownManagerCard.getByLabel('Имя на карточке')).toHaveCount(0)
  await expect(ownManagerCard.getByLabel('Тип сотрудника')).toHaveCount(0)
  await expect(ownManagerCard.getByLabel('Привязать к сотруднику')).toHaveCount(0)
  await expect(ownManagerCard.getByLabel('Коротко о сотруднике')).toBeVisible()
  await ownManagerCard.getByRole('button', { name: 'Отмена' }).click()

  await cards.getByRole('button', { name: 'Добавить карточку сотрудника' }).click()
  let createForm = cards.locator('.venue-profile-form')
  await expect(createForm.getByLabel('Привязать к сотруднику')).toBeHidden()
  await createForm.getByLabel('Имя на карточке').fill('Карточка менеджера')
  await createForm.getByLabel('Тип сотрудника').selectOption('waiter')
  await createForm.getByRole('button', { name: 'Создать профиль' }).click()
  await expect.poll(() => api.getProfileCreateRequests()).toHaveLength(1)
  expect(api.getProfileCreateRequests()[0]).toMatchObject({
    displayName: 'Карточка менеджера'
  })
  expect(api.getProfileCreateRequests()[0]).not.toHaveProperty('linkedUserId')
  expect(api.getProfileCreateFromMemberRequests()).toHaveLength(0)

  await maximMember.getByRole('button', { name: 'Создать карточку' }).click()
  createForm = cards.locator('.venue-profile-form')
  await expect(createForm.getByLabel('Имя на карточке')).toHaveValue('Максим Катаев')
  await expect(createForm.getByLabel('Имя на карточке')).not.toBeEditable()
  await expect(createForm.getByLabel('Привязать к сотруднику')).toHaveValue('444444444')
  await expect(createForm.getByLabel('Привязать к сотруднику')).toBeDisabled()
  await expect(createForm.getByLabel('Коротко о сотруднике')).toBeHidden()
  await expect(createForm.getByLabel('Специализация')).toBeHidden()
  await expect(createForm.getByLabel('Тип сотрудника')).toHaveValue('')
  await createForm.getByRole('button', { name: 'Создать карточку' }).click()
  await expect.poll(() => api.getProfileCreateFromMemberRequests()).toHaveLength(0)
  await createForm.getByLabel('Тип сотрудника').selectOption('waiter')
  await createForm.getByRole('button', { name: 'Создать карточку' }).click()
  await expect.poll(() => api.getProfileCreateFromMemberRequests()).toHaveLength(1)
  expect(api.getProfileCreateFromMemberRequests()[0]).toEqual({
    userId: 444444444,
    subtype: 'waiter'
  })
  expect(api.getProfileCreateFromMemberRequests()[0]).not.toHaveProperty('displayName')
  expect(api.getProfileCreateFromMemberRequests()[0]).not.toHaveProperty('isGuestVisible')
  expect(api.getProfileCreateRequests()).toHaveLength(1)
  const createdDraft = cards.locator('.venue-profile-row').filter({ hasText: 'Максим Катаев' })
  await expect(createdDraft).toBeFocused()
  await expect(createdDraft).toContainText('Скрыт — виден только в кабинете')
  await expect(createdDraft.getByLabel('Имя на карточке')).toHaveValue('Максим Катаев')

  await svetlanaMember.getByRole('button', { name: 'Открыть карточку' }).click()
  let staffCard = cards.locator('.venue-profile-row').filter({ hasText: 'Светлана' })
  await expect(staffCard).toBeFocused()
  await expect(staffCard.getByLabel('Имя на карточке')).toBeVisible()
  await staffCard.getByRole('button', { name: 'Отмена' }).click()
  await expect(staffCard.getByRole('button', { name: 'Редактировать' })).toBeVisible()
  await expect(staffCard.getByRole('button', { name: 'Опубликовать' })).toBeVisible()
  await staffCard.getByRole('button', { name: 'Редактировать' }).click()
  await staffCard.getByLabel('Имя на карточке').fill('Светлана Новая')
  await staffCard.getByRole('button', { name: 'Сохранить' }).click()
  await expect.poll(() => api.getProfileUpdateRequests()).toHaveLength(1)
  staffCard = cards.locator('.venue-profile-row').filter({ hasText: 'Светлана Новая' })
  await staffCard.getByRole('button', { name: 'Опубликовать' }).click()
  await expect(staffCard).toContainText('Опубликован — виден гостям')
  await staffCard.getByRole('button', { name: 'Скрыть' }).click()
  await expect(staffCard).toContainText('Скрыт — виден только в кабинете')
})

test('staff profile controls fail closed while the current directory loads or errors', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, {
    role: 'MANAGER',
    linked: true,
    holdFirstStaffDirectory: true,
    staffDirectoryFailuresBeforeSuccess: 1
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()

  const staff = page.locator('.venue-staff')
  const cards = staff.locator('.venue-public-staff')
  const displayOnly = cards.locator('.venue-profile-row').filter({ hasText: 'Карточка без доступа' })
  await expect(displayOnly).toBeVisible()
  await expect(displayOnly.getByRole('button', { name: 'Редактировать' })).toHaveCount(0)
  await expect(displayOnly.getByRole('button', { name: 'Опубликовать' })).toHaveCount(0)
  await expect(cards.getByRole('button', { name: 'Добавить карточку сотрудника' })).toBeDisabled()

  api.releaseStaffDirectory()
  await expect(staff.getByRole('heading', { name: 'Сервис временно недоступен' })).toBeVisible()
  await expect(displayOnly.getByRole('button', { name: 'Редактировать' })).toHaveCount(0)
  await expect(cards.getByRole('button', { name: 'Добавить карточку сотрудника' })).toBeDisabled()

  await staff.getByRole('button', { name: 'Повторить' }).click()
  await expect(displayOnly.getByRole('button', { name: 'Редактировать' })).toBeVisible()
  await expect(displayOnly.getByRole('button', { name: 'Опубликовать' })).toBeVisible()
  await expect(cards.getByRole('button', { name: 'Добавить карточку сотрудника' })).toBeEnabled()
})

test('manager duplicate staff links stay read-only across reload refresh and venue switch', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, { role: 'MANAGER', linked: true })
  api.addStaffProfile({
    id: 506,
    linkedUserId: 222222222,
    displayName: 'Светлана дубль',
    roleLabel: null,
    subtype: 'waiter',
    photoRef: null,
    bio: null,
    tags: [],
    isGuestVisible: false,
    publishedAt: null,
    disabledAt: null,
    createdAt: '2030-01-10T18:00:00Z',
    updatedAt: '2030-01-10T18:00:00Z',
    todayShift: null
  })

  const permissions = [
    'STAFF_CHAT_LINK',
    'STAFF_INVITE_CREATE_STAFF',
    'STAFF_INVITE_REVOKE_STAFF',
    'STAFF_PROFILE_MANAGE_STAFF',
    'STAFF_PROFILE_PUBLISH_STAFF',
    'STAFF_PROFILE_EDIT_OWN',
    'STAFF_SCHEDULE_MANAGE'
  ]
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
            permissions
          },
          {
            venueId: 2,
            venueName: 'Дым',
            venueCity: 'Казань',
            venueStatus: 'PUBLISHED',
            role: 'MANAGER',
            permissions
          }
        ]
      })
    )
  })
  await page.route('**/api/venue/2/staff', async (route) => {
    await route.fulfill(jsonResponse({ members: [] }))
  })
  await page.route('**/api/venue/2/staff/profiles**', async (route) => {
    await route.fulfill(jsonResponse({ profiles: [] }))
  })
  await page.route('**/api/venue/2/staff/invites**', async (route) => {
    await route.fulfill(jsonResponse({ invites: [] }))
  })

  const profileMutations = recordVenueStaffProfileMutations(page, 1)
  const assertRawDuplicateContract = (responses: VenueStaffGetResponsesFixture) => {
    const duplicateMember = responses.directory.members.find(
      (member) => member.userId === 222222222
    )
    expect(duplicateMember).toMatchObject({
      profileLinkState: 'DUPLICATE_LINK_DETECTED',
      linkedStaffProfileId: null,
      linkedStaffProfileDisplayName: null
    })
    expect(duplicateMember).not.toHaveProperty('linkedUserId')

    const duplicateProfiles = responses.profiles.profiles.filter(
      (profile) => profile.linkageClass === 'DUPLICATE_LINK_DETECTED'
    )
    expect(duplicateProfiles.map((profile) => profile.id).sort((left, right) => left - right)).toEqual([
      502,
      506
    ])
    duplicateProfiles.forEach((profile) => {
      expect(profile).toMatchObject({
        linkageClass: 'DUPLICATE_LINK_DETECTED',
        canManage: false,
        linkedUserId: null
      })
    })
    responses.profiles.profiles
      .filter(
        (profile) =>
          profile.linkageClass === 'PROTECTED' ||
          profile.linkageClass === 'DUPLICATE_LINK_DETECTED'
      )
      .forEach((profile) => expect(profile.linkedUserId).toBeNull())

    const rawJson = JSON.stringify(responses)
    expect(rawJson).not.toContain('123456789')
    expect(rawJson).not.toContain('333333333')
    expect(rawJson).not.toContain('111111111')
  }
  const assertDuplicateUiIsReadOnly = async () => {
    const staff = page.locator('.venue-staff')
    const accessCard = staff.locator(':scope > .card').filter({
      has: page.getByRole('heading', { name: 'Доступ сотрудников', exact: true })
    })
    const memberRow = accessCard.locator('.venue-staff-row').filter({
      has: page.getByText('Светлана Орлова', { exact: true })
    })
    await expect(memberRow).toHaveCount(1)
    await expect(memberRow.locator('.venue-staff-link-state')).toHaveText(
      'Обнаружено несколько активных карточек'
    )
    await expect(memberRow.locator('.venue-staff-link-warning')).toHaveText(
      'К этому сотруднику привязано несколько карточек. Выберите основную и отвяжите остальные.'
    )
    for (const action of [
      'Открыть карточку',
      'Редактировать',
      'Отвязать',
      'Связать',
      'Создать карточку'
    ]) {
      await expect(memberRow.getByRole('button', { name: action, exact: true })).toHaveCount(0)
    }
    await expect(memberRow.getByRole('button')).toHaveCount(0)
    await expect(memberRow.locator('input, select, textarea')).toHaveCount(0)

    for (const profileId of [502, 506]) {
      const profile = staff.locator(`.venue-profile-row[data-staff-profile-id="${profileId}"]`)
      await expect(profile).toBeVisible()
      await expect(profile.getByRole('button')).toHaveCount(0)
      await expect(profile.locator('input, select, textarea')).toHaveCount(0)
      await expect(profile.locator('option[value="222222222"]')).toHaveCount(0)
    }
    await expect(staff).not.toContainText('222222222')
    expect(profileMutations).toHaveLength(0)
    expect(api.getProfileUpdateRequests()).toHaveLength(0)
  }

  const initialResponsesPromise = waitForVenueStaffGetResponses(page, 1)
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  assertRawDuplicateContract(await initialResponsesPromise)
  await assertDuplicateUiIsReadOnly()

  await page.getByRole('button', { name: 'Обзор', exact: true }).click()
  const refreshedResponsesPromise = waitForVenueStaffGetResponses(page, 1)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  assertRawDuplicateContract(await refreshedResponsesPromise)
  await assertDuplicateUiIsReadOnly()

  const reloadedResponsesPromise = waitForVenueStaffGetResponses(page, 1)
  await page.reload()
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  assertRawDuplicateContract(await reloadedResponsesPromise)
  await assertDuplicateUiIsReadOnly()

  const venueSelect = page.locator('.venue-controls select.venue-select')
  const venueTwoResponsesPromise = waitForVenueStaffGetResponses(page, 2)
  await venueSelect.selectOption('2')
  const venueTwoResponses = await venueTwoResponsesPromise
  expect(venueTwoResponses.directory.members).toEqual([])
  expect(venueTwoResponses.profiles.profiles).toEqual([])

  const switchedBackResponsesPromise = waitForVenueStaffGetResponses(page, 1)
  await venueSelect.selectOption('1')
  assertRawDuplicateContract(await switchedBackResponsesPromise)
  await assertDuplicateUiIsReadOnly()
})

test('owner repairs one concrete duplicate staff card with safe unlink only', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const primaryShift = {
    id: 1502,
    staffProfileId: 502,
    shiftDate: '2030-01-10',
    startsAt: '18:00',
    endsAt: '23:00',
    status: 'active',
    isGuestVisible: false,
    manuallyMarkedActive: true,
    createdAt: '2030-01-10T18:00:00Z',
    updatedAt: '2030-01-10T18:00:00Z'
  }
  const wrongCardShift = {
    id: 1506,
    staffProfileId: 506,
    shiftDate: '2030-01-09',
    startsAt: '19:00',
    endsAt: '01:00',
    status: 'completed',
    isGuestVisible: false,
    manuallyMarkedActive: false,
    createdAt: '2030-01-09T19:00:00Z',
    updatedAt: '2030-01-10T01:00:00Z'
  }
  const api = await mockVenueStaffChatApi(page, {
    role: 'OWNER',
    linked: true,
    members: [
      {
        userId: 222222222,
        displayName: 'Светлана Орлова',
        username: 'sveta_staff',
        role: 'STAFF',
        active: true,
        linkedStaffProfileId: null,
        linkedStaffProfileDisplayName: null,
        profileLinkState: 'DUPLICATE_LINK_DETECTED'
      }
    ],
    staffProfiles: [
      {
        id: 502,
        linkedUserId: 222222222,
        displayName: 'Светлана',
        roleLabel: null,
        subtype: 'waiter',
        photoRef: null,
        bio: 'Основная карточка',
        tags: ['сервис'],
        isGuestVisible: false,
        publishedAt: null,
        disabledAt: null,
        createdAt: '2030-01-01T18:00:00Z',
        updatedAt: '2030-01-10T18:00:00Z',
        todayShift: primaryShift
      },
      {
        id: 506,
        linkedUserId: 222222222,
        displayName: 'Светлана дубль',
        roleLabel: null,
        subtype: 'waiter',
        photoRef: null,
        bio: 'Неправильная карточка',
        tags: ['зал'],
        isGuestVisible: false,
        publishedAt: null,
        disabledAt: null,
        createdAt: '2030-01-02T18:00:00Z',
        updatedAt: '2030-01-10T18:00:00Z',
        todayShift: wrongCardShift
      }
    ]
  })
  const profileMutations = recordVenueStaffProfileMutations(page, 1)

  const initialResponsesPromise = waitForVenueStaffGetResponses(page, 1)
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  const initialResponses = await initialResponsesPromise
  expect(initialResponses.directory.members.find((member) => member.userId === 222222222)).toMatchObject({
    profileLinkState: 'DUPLICATE_LINK_DETECTED',
    linkedStaffProfileId: null,
    linkedStaffProfileDisplayName: null
  })
  const initialDuplicateProfiles = initialResponses.profiles.profiles.filter(
    (profile) => profile.id === 502 || profile.id === 506
  )
  expect(initialDuplicateProfiles.map((profile) => profile.id).sort((left, right) => left - right)).toEqual([
    502,
    506
  ])
  initialDuplicateProfiles.forEach((profile) => {
    expect(profile).toMatchObject({
      linkedUserId: 222222222,
      linkageClass: 'DUPLICATE_LINK_DETECTED',
      canManage: true,
      disabledAt: null
    })
  })

  const accessCard = page.locator('.venue-staff > .card').filter({
    has: page.getByRole('heading', { name: 'Доступ сотрудников', exact: true })
  })
  const memberRow = accessCard.locator('.venue-staff-row').filter({
    has: page.getByText('Светлана Орлова', { exact: true })
  })
  await expect(memberRow.locator('.venue-staff-link-warning')).toHaveText(
    'К этому сотруднику привязано несколько карточек. Выберите основную и отвяжите остальные.'
  )

  const cards = page.locator('.venue-public-staff')
  const primaryProfile = cards.locator('.venue-profile-row[data-staff-profile-id="502"]')
  const wrongProfile = cards.locator('.venue-profile-row[data-staff-profile-id="506"]')
  await expect(primaryProfile).toHaveAttribute('data-staff-profile-id', '502')
  await expect(wrongProfile).toHaveAttribute('data-staff-profile-id', '506')
  await expect(primaryProfile).toContainText('Светлана')
  await expect(wrongProfile).toContainText('Светлана дубль')
  await expect(primaryProfile).toContainText('По графику: запланирован сегодня · 18:00-23:00 · Сегодня на смене')
  await expect(primaryProfile).toContainText('Для гостей: выключено вручную')
  await expect(wrongProfile).toContainText('По графику: запланирован сегодня · 19:00-01:00 · Не на смене сегодня')
  await expect(wrongProfile).toContainText('Для гостей: выключено вручную')

  await primaryProfile.getByRole('button', { name: 'Редактировать', exact: true }).click()
  await expect(primaryProfile.getByLabel('Привязать к сотруднику')).toHaveValue('222222222')
  await primaryProfile.getByRole('button', { name: 'Отмена', exact: true }).click()

  await wrongProfile.getByRole('button', { name: 'Редактировать', exact: true }).click()
  const wrongProfileLink = wrongProfile.getByLabel('Привязать к сотруднику')
  await expect(wrongProfileLink).toHaveValue('222222222')
  await wrongProfileLink.selectOption('')
  const repairRefreshPromise = waitForVenueStaffGetResponses(page, 1)
  await wrongProfile.getByRole('button', { name: 'Сохранить', exact: true }).click()
  const repairedResponses = await repairRefreshPromise

  await expect.poll(() => profileMutations).toHaveLength(1)
  expect(profileMutations[0]).toMatchObject({
    method: 'PATCH',
    path: '/api/venue/1/staff/profiles/506',
    body: { unlinkUser: true }
  })
  expect(profileMutations[0].body).not.toHaveProperty('linkedUserId')
  await expect.poll(() => api.getProfileUpdateRequests()).toHaveLength(1)
  expect(api.getProfileUpdateRequests()[0]).toMatchObject({ unlinkUser: true })
  expect(api.getProfileUpdateRequests()[0]).not.toHaveProperty('linkedUserId')

  expect(repairedResponses.directory.members.find((member) => member.userId === 222222222)).toMatchObject({
    profileLinkState: 'LINKED',
    linkedStaffProfileId: 502,
    linkedStaffProfileDisplayName: 'Светлана'
  })
  const repairedPrimary = repairedResponses.profiles.profiles.find((profile) => profile.id === 502)
  const repairedWrongCard = repairedResponses.profiles.profiles.find((profile) => profile.id === 506)
  expect(repairedPrimary).toMatchObject({
    linkedUserId: 222222222,
    linkageClass: 'STAFF_LINKED',
    disabledAt: null,
    createdAt: '2030-01-01T18:00:00Z'
  })
  expect(repairedWrongCard).toMatchObject({
    linkedUserId: null,
    linkageClass: 'DISPLAY_ONLY',
    disabledAt: null,
    createdAt: '2030-01-02T18:00:00Z'
  })
  expect(repairedPrimary?.todayShift).toEqual(primaryShift)
  expect(repairedWrongCard?.todayShift).toEqual(wrongCardShift)
  expect(
    repairedResponses.profiles.profiles
      .filter((profile) => profile.id === 502 || profile.id === 506)
      .map((profile) => profile.id)
      .sort((left, right) => left - right)
  ).toEqual([502, 506])

  const reloadedResponsesPromise = waitForVenueStaffGetResponses(page, 1)
  await page.reload()
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  const reloadedResponses = await reloadedResponsesPromise
  expect(reloadedResponses.directory.members.find((member) => member.userId === 222222222)).toMatchObject({
    profileLinkState: 'LINKED',
    linkedStaffProfileId: 502,
    linkedStaffProfileDisplayName: 'Светлана'
  })
  const reloadedPrimary = reloadedResponses.profiles.profiles.find((profile) => profile.id === 502)
  const reloadedWrongCard = reloadedResponses.profiles.profiles.find((profile) => profile.id === 506)
  expect(reloadedPrimary?.todayShift).toEqual(primaryShift)
  expect(reloadedWrongCard?.todayShift).toEqual(wrongCardShift)
  expect(reloadedPrimary).toMatchObject({ linkageClass: 'STAFF_LINKED', disabledAt: null })
  expect(reloadedWrongCard).toMatchObject({ linkageClass: 'DISPLAY_ONLY', disabledAt: null })
  await expect(primaryProfile).toBeVisible()
  await expect(wrongProfile).toBeVisible()
  await expect(primaryProfile).toContainText('По графику: запланирован сегодня · 18:00-23:00 · Сегодня на смене')
  await expect(primaryProfile).toContainText('Для гостей: выключено вручную')
  await expect(wrongProfile).toContainText('По графику: запланирован сегодня · 19:00-01:00 · Не на смене сегодня')
  await expect(wrongProfile).toContainText('Для гостей: выключено вручную')
  expect(profileMutations).toHaveLength(1)
})

test('concurrent profile link conflict opens the winning safe profile without a duplicate', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, { role: 'MANAGER', linked: true })
  api.queueProfileLinkConflict({
    profileLinkState: 'LINKED',
    linkedStaffProfileId: 706,
    winningProfile: {
      id: 706,
      linkedUserId: 444444444,
      displayName: 'Максим Катаев',
      roleLabel: null,
      subtype: 'waiter',
      photoRef: null,
      bio: null,
      tags: [],
      isGuestVisible: false,
      publishedAt: null,
      disabledAt: null,
      createdAt: '2030-01-10T18:05:00Z',
      updatedAt: '2030-01-10T18:05:00Z',
      todayShift: null
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()

  const accessCard = page.locator('.venue-staff > .card').first()
  let maximMember = accessCard.locator('.venue-staff-row').filter({ hasText: 'Максим Катаев' })
  await maximMember.getByRole('button', { name: 'Создать карточку' }).click()
  const createForm = page.locator('.venue-public-staff .venue-profile-form')
  await createForm.getByLabel('Тип сотрудника').selectOption('waiter')
  await createForm.getByRole('button', { name: 'Создать карточку' }).click()

  await expect.poll(() => api.getProfileCreateFromMemberRequests()).toHaveLength(1)
  expect(api.getProfileCreateRequests()).toHaveLength(0)
  await expect(createForm).toBeHidden()
  maximMember = accessCard.locator('.venue-staff-row').filter({ hasText: 'Максим Катаев' })
  await expect(maximMember).toContainText('Привязан к карточке «Максим Катаев»')
  await expect(maximMember.getByRole('button', { name: 'Открыть карточку' })).toBeVisible()
  const winningProfile = page.locator('.venue-profile-row[data-staff-profile-id="706"]')
  await expect(winningProfile).toBeFocused()
  await expect(winningProfile.getByLabel('Имя на карточке')).toHaveValue('Максим Катаев')
  await expect(page.locator('.venue-profile-row').filter({ hasText: 'Максим Катаев' })).toHaveCount(1)
})

test('venue switch clears staff invite and profile drafts', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueStaffChatApi(page, { role: 'MANAGER', linked: true })
  const permissions = [
    'STAFF_INVITE_CREATE_STAFF',
    'STAFF_INVITE_REVOKE_STAFF',
    'STAFF_PROFILE_MANAGE_STAFF',
    'STAFF_PROFILE_PUBLISH_STAFF',
    'STAFF_PROFILE_EDIT_OWN',
    'STAFF_SCHEDULE_MANAGE'
  ]
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
            permissions
          },
          {
            venueId: 2,
            venueName: 'Дым',
            venueCity: 'Казань',
            venueStatus: 'PUBLISHED',
            role: 'MANAGER',
            permissions
          }
        ]
      })
    )
  })
  await page.route('**/api/venue/2/staff', async (route) => {
    await route.fulfill(
      jsonResponse({
        members: [
          {
            userId: 266666666,
            displayName: 'Мария Вторая',
            username: null,
            role: 'STAFF',
            active: true,
            linkedStaffProfileId: null,
            linkedStaffProfileDisplayName: null,
            profileLinkState: 'NOT_LINKED'
          }
        ]
      })
    )
  })
  await page.route('**/api/venue/2/staff/profiles**', async (route) => {
    await route.fulfill(jsonResponse({ profiles: [] }))
  })
  await page.route('**/api/venue/2/staff/invites**', async (route) => {
    await route.fulfill(jsonResponse({ invites: [] }))
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  let staff = page.locator('.venue-staff')
  await staff.getByRole('button', { name: 'Добавить сотрудника' }).click()
  await expect(staff.locator('.venue-invite-result')).toBeVisible()
  await staff.getByRole('button', { name: 'Добавить карточку сотрудника' }).click()
  await expect(staff.locator('.venue-profile-form')).toBeVisible()
  await expect(staff).toContainText('Максим Катаев')

  await page.locator('.venue-controls select.venue-select').selectOption('2')
  staff = page.locator('.venue-staff')
  await expect(staff.getByRole('heading', { name: 'Доступ сотрудников' })).toBeVisible()
  await expect(staff.locator('.venue-invite-result')).toBeHidden()
  await expect(staff.locator('.venue-profile-form')).toBeHidden()
  await expect(staff.locator('.venue-pending-invite-list')).toContainText('Ожидающих приглашений нет.')
  await expect(staff).toContainText('Мария Вторая')
  await expect(staff).toContainText('Без username · ID …6666')
  await expect(staff).not.toContainText('Максим Катаев')
  await expect(staff).not.toContainText('266666666')
})

test('account switch does not reuse prior venue member identities', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, { role: 'MANAGER', linked: true })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  let accessCard = page.locator('.venue-staff > .card').first()
  await expect(accessCard).toContainText('Максим Катаев')

  api.setAccountStaffState(
    987654321,
    [
      {
        userId: 888888888,
        displayName: 'Екатерина Новая',
        username: 'new_account_staff',
        role: 'STAFF',
        active: true,
        linkedStaffProfileId: null,
        linkedStaffProfileDisplayName: null,
        profileLinkState: 'NOT_LINKED'
      }
    ]
  )
  await page.evaluate(({ userId, initData }) => {
    window.localStorage.setItem('__e2e_telegram_user_id', String(userId))
    window.localStorage.setItem('__e2e_telegram_init_data', initData)
  }, { userId: 987654321, initData: otherMockInitData })
  await page.goto(`?mode=venue&smokeUser=other#tgWebAppData=${encodeURIComponent(otherMockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()

  accessCard = page.locator('.venue-staff > .card').first()
  await expect(accessCard).toContainText('Екатерина Новая')
  await expect(accessCard).toContainText('@new_account_staff')
  await expect(accessCard).not.toContainText('Максим Катаев')
  await expect(accessCard).not.toContainText('Светлана Орлова')
  await expect(accessCard).not.toContainText('888888888')
})

test('staff module off keeps access and invites available and explains profile and schedule sections', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, {
    role: 'OWNER',
    permissions: [
      'STAFF_INVITE_CREATE_STAFF',
      'STAFF_INVITE_CREATE_MANAGER',
      'STAFF_INVITE_REVOKE_STAFF',
      'STAFF_INVITE_REVOKE_MANAGER',
      'STAFF_SCHEDULE_VIEW',
      'STAFF_MODULE_SETTINGS_MANAGE'
    ],
    pendingInvites: [
      {
        handle: 'saved-invite',
        role: 'STAFF',
        status: 'PENDING',
        createdAt: '2030-01-10T18:00:00Z',
        expiresAt: '2030-01-17T18:00:00Z'
      }
    ],
    staffModuleSettings: {
      teamScheduleModuleEnabled: false,
      guestTeamVisible: false,
      todayStaffSource: 'SCHEDULE',
      updatedAt: '2030-01-10T18:00:00.000001Z'
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()

  const accessCard = page.locator('.venue-staff > .card').first()
  await expect(accessCard.getByRole('heading', { name: 'Доступ сотрудников' })).toBeVisible()
  await expect(accessCard).toContainText('Светлана Орлова')
  await expect(accessCard).toContainText('Статус: ожидает принятия')
  await accessCard.getByRole('button', { name: 'Добавить сотрудника' }).click()
  await expect.poll(() => api.getStaffInvites()).toBe(1)

  const profileCard = page.locator('.venue-public-staff')
  await expect(profileCard).toContainText('Карточки команды и график смен отключены.')
  await expect(profileCard).toContainText('Сохранённые данные не удалены')
  await expect(profileCard.getByRole('button', { name: 'Включить в настройках' })).toBeVisible()
  await expect.poll(() => api.getProfileGetCalls()).toBe(0)

  await page.getByRole('button', { name: 'График смен', exact: true }).click()
  const schedule = page.locator('.screen-root .venue-module-disabled')
  await expect(schedule).toContainText('Карточки команды и график смен отключены.')
  await expect(schedule).toContainText('Сохранённые смены не удалены')
  await expect(schedule.getByRole('button', { name: 'Включить в настройках' })).toBeVisible()
})

test('staff module off hides My shifts navigation and rejects a direct view locally', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffScheduleApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'STAFF',
        permissions: ['STAFF_SCHEDULE_VIEW_OWN'],
        teamScheduleModuleEnabled: false
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByRole('button', { name: 'Мои смены', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    window.location.hash = '#/shifts'
  })
  await expect(page.getByRole('heading', { name: 'Мои смены недоступны' })).toBeVisible()
  await expect(page.getByText('Карточки команды и график смен отключены в настройках заведения.')).toBeVisible()
  expect(api.getListRequests()).toHaveLength(0)
})

test('manual Today publication is persisted and schedule source removes the manual control', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, {
    role: 'OWNER',
    linked: true,
    staffModuleSettings: {
      teamScheduleModuleEnabled: true,
      guestTeamVisible: true,
      todayStaffSource: 'MANUAL',
      updatedAt: '2030-01-10T18:00:00.000001Z'
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  let profileRow = page.locator('.venue-profile-row').filter({ hasText: 'Алексей' })
  await expect(profileRow).toContainText('По графику: не запланирован сегодня')
  await expect(profileRow).toContainText('Для гостей: выключено вручную')
  await profileRow.getByRole('button', { name: 'Опубликовать' }).click()
  await expect(profileRow).toContainText('Опубликован — виден гостям')
  const manualSwitch = profileRow.getByRole('switch', { name: 'Выключено' })
  await expect(manualSwitch).toHaveAttribute('aria-checked', 'false')
  const releaseProfileRefresh = api.deferNextProfileLoad()
  const todayMutationResponse = page.waitForResponse((response) => {
    const request = response.request()
    return (
      request.method() === 'POST' &&
      new URL(response.url()).pathname === '/api/venue/1/staff/profiles/501/today-shift'
    )
  })
  await manualSwitch.click()
  await todayMutationResponse
  try {
    await expect(profileRow.getByRole('switch', { name: 'Включено' })).toHaveAttribute(
      'aria-checked',
      'true'
    )
    await expect(profileRow.getByRole('switch', { name: 'Включено' })).toBeEnabled()
  } finally {
    releaseProfileRefresh()
  }
  await expect.poll(() => api.getShiftRequests()).toEqual([{ status: 'active', isGuestVisible: true }])
  await expect(page.getByText('Сотрудник отображается в блоке «Сегодня работают».')).toBeVisible()
  await expect(profileRow.getByRole('switch', { name: 'Включено' })).toHaveAttribute('aria-checked', 'true')

  api.setStaffModuleSettings({
    teamScheduleModuleEnabled: true,
    guestTeamVisible: true,
    todayStaffSource: 'SCHEDULE',
    updatedAt: '2030-01-10T18:00:00.000002Z'
  })
  await page.reload()
  profileRow = page.locator('.venue-profile-row').filter({ hasText: 'Алексей' })
  await expect(profileRow).toContainText('Состав для гостей определяется активными сменами в графике.')
  await expect(profileRow.getByRole('switch')).toHaveCount(0)
  expect(api.getShiftRequests()).toEqual([{ status: 'active', isGuestVisible: true }])
})

test('manual Today success copy does not claim public visibility when guest team is hidden', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, {
    role: 'OWNER',
    staffModuleSettings: {
      teamScheduleModuleEnabled: true,
      guestTeamVisible: false,
      todayStaffSource: 'MANUAL',
      updatedAt: '2030-01-10T18:00:00.000001Z'
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()
  const profileRow = page.locator('.venue-profile-row').filter({ hasText: 'Алексей' })
  await profileRow.getByRole('switch', { name: 'Выключено' }).click()

  await expect.poll(() => api.getShiftRequests()).toEqual([{ status: 'active', isGuestVisible: true }])
  await expect(page.getByRole('status')).toHaveText(
    'Ручная отметка сохранена. Показ команды гостям отключён в настройках.'
  )
  await expect(page.getByRole('status')).not.toContainText(
    'Сотрудник отображается в блоке «Сегодня работают».'
  )
  await expect(profileRow).toContainText(
    'Для гостей: включено вручную, но показ команды отключён в настройках'
  )
})

test('venue owner staff cards use human profile labels and hide raw technical fields', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffChatApi(page, { role: 'OWNER', linked: true })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Персонал', exact: true }).click()

  const staffCards = page.locator('.venue-public-staff')
  const createForm = staffCards.locator('.venue-profile-form')
  await expect(staffCards.getByRole('heading', { name: 'Карточки команды' })).toBeVisible()
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
  await expect(createForm.getByLabel('Привязать к сотруднику')).toBeHidden()
  await expect(createForm.getByLabel('Коротко о сотруднике')).toBeVisible()
  await expect(createForm.getByLabel('Специализация')).toBeVisible()
  await expect(createForm).not.toContainText('123456789')
  await expect(createForm).toContainText('Так это имя увидят гости.')
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
    bio: 'Люблю крепкие миксы.',
    tags: ['крепкие миксы', 'авторские вкусы']
  })
  expect(createRequest).not.toHaveProperty('linkedUserId')
  expect(createRequest).not.toHaveProperty('isGuestVisible')
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
    bio: 'Люблю крепкие миксы.',
    tags: ['крепкие миксы', 'авторские вкусы']
  })
  expect(updateRequest).not.toHaveProperty('linkedUserId')
  expect(updateRequest).not.toHaveProperty('unlinkUser')
  expect(updateRequest).not.toHaveProperty('photoRef')

  await profileRow.getByRole('button', { name: 'Опубликовать' }).click()
  await expect(profileRow).toContainText('Опубликован — виден гостям')
  await profileRow.getByRole('switch', { name: 'Выключено' }).click()
  await expect.poll(() => api.getShiftRequests().length).toBe(1)
  await profileRow.getByRole('switch', { name: 'Включено' }).click()
  await expect.poll(() => api.getShiftRequests().length).toBe(2)
  expect(api.getShiftRequests()).toEqual([
    { status: 'active', isGuestVisible: true },
    { status: 'canceled', isGuestVisible: false }
  ])
})

test('staff shift form uses effective venue hours without overwriting manual or stored times', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueStaffScheduleApi(page, {
    timezones: { 1: 'Asia/Yekaterinburg' }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()

  const schedule = page.locator('.venue-staff-schedule')
  await expect(schedule).toContainText('Asia/Yekaterinburg')
  await schedule.getByRole('button', { name: 'Добавить смену' }).click()
  const form = schedule.locator('.venue-schedule-form')
  const date = form.getByLabel('Дата начала')
  const start = form.getByLabel('Начало')
  const end = form.getByLabel('Окончание')

  await expect(date).toHaveValue('2030-01-07')
  await expect(start).toHaveValue('18:00')
  await expect(end).toHaveValue('02:00')
  await expect(form).toContainText('По графику заведения: 18:00–02:00, следующий день.')

  await date.fill('2030-01-08')
  await expect(start).toHaveValue('20:00')
  await expect(end).toHaveValue('04:00')
  await expect(form).toContainText('По графику заведения: 20:00–04:00, следующий день.')

  await start.fill('21:00')
  await end.fill('05:00')
  await date.fill('2030-01-07')
  await expect(start).toHaveValue('21:00')
  await expect(end).toHaveValue('05:00')
  await expect(form.getByRole('button', { name: 'Заполнить по часам заведения' })).toBeVisible()
  await form.getByRole('button', { name: 'Заполнить по часам заведения' }).click()
  await expect(start).toHaveValue('18:00')
  await expect(end).toHaveValue('02:00')

  await date.fill('2030-01-09')
  await expect(start).toHaveValue('')
  await expect(end).toHaveValue('')
  await expect(form).toContainText('По графику заведение закрыто в этот день.')
  await expect(form.getByRole('button', { name: 'Указать время вручную' })).toBeVisible()
  await form.getByRole('button', { name: 'Указать время вручную' }).click()
  await start.fill('19:00')
  await end.fill('01:00')
  await date.fill('2030-01-08')
  await expect(start).toHaveValue('19:00')
  await expect(end).toHaveValue('01:00')
  await form.getByRole('button', { name: 'Заполнить по часам заведения' }).click()

  await date.fill('2030-01-10')
  await expect(start).toHaveValue('')
  await expect(end).toHaveValue('')
  await expect(form).toContainText('Часы работы заведения на этот день не настроены.')
  await form.getByRole('button', { name: 'Закрыть' }).click()

  const storedShift = schedule.locator('.venue-schedule-shift').filter({ hasText: 'Алексей' })
  await storedShift.getByRole('button', { name: 'Редактировать' }).click()
  await expect(date).toHaveValue('2030-01-08')
  await expect(start).toHaveValue('22:00')
  await expect(end).toHaveValue('06:00')
  await expect(form.getByRole('button', { name: 'Заполнить по часам заведения' })).toBeVisible()
  await date.fill('2030-01-07')
  await expect(start).toHaveValue('22:00')
  await expect(end).toHaveValue('06:00')
  await form.getByRole('button', { name: 'Заполнить по часам заведения' }).click()
  await expect(start).toHaveValue('18:00')
  await expect(end).toHaveValue('02:00')
})

test('staff shift AUTO clears stale hours on load error and retries an aborted date', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffScheduleApi(page)
  await page.route('**/api/venue/1/staff/shifts**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    if (
      request.method() === 'GET' &&
      url.searchParams.get('from') === '2030-01-14' &&
      url.searchParams.get('to') === '2030-01-14'
    ) {
      await route.fulfill(
        jsonResponse(
          { error: { code: 'DATABASE_UNAVAILABLE', message: 'Временно недоступно.' } },
          503
        )
      )
      return
    }
    await route.fallback()
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()
  const schedule = page.locator('.venue-staff-schedule')
  await schedule.getByRole('button', { name: 'Добавить смену' }).click()
  const form = schedule.locator('.venue-schedule-form')
  const date = form.getByLabel('Дата начала')
  const start = form.getByLabel('Начало')
  const end = form.getByLabel('Окончание')

  await expect(start).toHaveValue('18:00')
  await expect(end).toHaveValue('02:00')
  await date.fill('2030-01-14')
  await expect(start).toHaveValue('')
  await expect(end).toHaveValue('')
  await expect(form).toContainText('Не удалось загрузить часы заведения.')

  const releaseFirstDate = api.deferNextList(1)
  await date.fill('2030-01-15')
  await expect(form).toContainText('Загружаем часы заведения')
  await date.fill('2030-01-16')
  await expect(form).toContainText('Часы работы заведения на этот день не настроены.')
  releaseFirstDate()

  await date.fill('2030-01-15')
  await expect(form).toContainText('Часы работы заведения на этот день не настроены.')
  await expect
    .poll(
      () =>
        api
          .getListRequests()
          .filter((request) => request.from === '2030-01-15' && request.to === '2030-01-15')
          .length
    )
    .toBe(2)
})

test('staff bulk draft refreshes AUTO hours and does not leak canceled saved time to another date', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffScheduleApi(page)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()
  const schedule = page.locator('.venue-staff-schedule')
  await schedule.getByRole('button', { name: 'Добавить смену' }).click()
  const form = schedule.locator('.venue-schedule-form')
  const date = form.getByLabel('Дата начала')

  await form.getByLabel('Выбрать Анна').check()
  await expect(form.getByLabel('Начало — Анна')).toHaveValue('19:00')
  await expect(form.getByLabel('Окончание — Анна')).toHaveValue('01:00')

  await date.fill('2030-01-08')
  await expect(form.getByLabel('Начало — Анна')).toHaveValue('20:00')
  await expect(form.getByLabel('Окончание — Анна')).toHaveValue('04:00')
  await form.getByLabel('Убрать Анна').click()

  await date.fill('2030-01-07')
  await form.getByLabel('Выбрать Светлана').check()
  await expect(form.getByLabel('Начало — Светлана')).toHaveValue('18:00')
  await expect(form.getByLabel('Окончание — Светлана')).toHaveValue('02:00')

  api.setEffectiveHours(1, {
    serviceDate: '2030-01-07',
    state: 'OPEN',
    opensAt: '17:00',
    closesAt: '01:00',
    endsNextDay: true
  })
  await form.getByRole('button', { name: 'Проверить смены' }).click()

  const confirmation = schedule.locator('.venue-schedule-confirmation')
  await expect(confirmation).toContainText('Будет создано: 1')
  await expect(confirmation).toContainText(
    'Светлана · создание · 17:00–01:00, следующий день'
  )
  expect(api.getBatchRequests()).toHaveLength(0)
})

test('venue owner assigns multiple staff with common and individual hours and explicitly restores canceled', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffScheduleApi(page)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()
  const schedule = page.locator('.venue-staff-schedule')
  const canceledRow = schedule.locator('.venue-schedule-shift').filter({ hasText: 'Анна' })
  await expect(canceledRow).toContainText('Отменена')
  await expect(canceledRow).toContainText('19:00–01:00, следующий день')
  await expect(canceledRow.getByRole('button', { name: 'Восстановить' })).toBeVisible()

  await schedule.getByRole('button', { name: 'Добавить смену' }).click()
  const form = schedule.locator('.venue-schedule-form')
  await expect(form.getByLabel('Начало')).toHaveValue('18:00')
  await expect(form.getByLabel('Окончание')).toHaveValue('02:00')
  await expect(form).toContainText('По графику заведения: 18:00–02:00, следующий день.')

  await form.getByLabel('Выбрать Светлана').check()
  await form.getByLabel('Выбрать Максим').check()
  await form.getByLabel('Выбрать Анна').check()
  await form.getByLabel('Выбрать Алексей').check()

  await form.getByLabel('Начало — Максим').fill('20:00')
  await form.getByLabel('Окончание — Максим').fill('00:00')
  await form.getByRole('button', { name: 'Применить общее время всем' }).click()
  await expect(form.getByLabel('Начало — Максим')).toHaveValue('18:00')
  await expect(form.getByLabel('Окончание — Максим')).toHaveValue('02:00')
  await form.getByLabel('Начало — Максим').fill('20:00')
  await form.getByLabel('Окончание — Максим').fill('00:00')

  await form.getByLabel('Убрать Алексей').click()
  await expect(form.getByLabel('Выбрать Алексей')).not.toBeChecked()
  await expect(form).toContainText('Смена на эту дату была отменена.')

  const checkButton = form.getByRole('button', { name: 'Проверить смены' })
  await checkButton.click()
  await expect
    .poll(
      () =>
        api
          .getListRequests()
          .filter((request) => request.from === '2030-01-07' && request.to === '2030-01-07')
          .length
    )
    .toBe(1)
  await expect(schedule.locator('.venue-schedule-confirmation')).toBeHidden()
  await form.getByLabel('Восстановить Анна').click()
  await checkButton.click()

  const confirmation = schedule.locator('.venue-schedule-confirmation')
  await expect(confirmation).toContainText('Будет создано: 2')
  await expect(confirmation).toContainText('Будет восстановлено: 1')
  await expect(confirmation).toContainText('Максим · создание · 20:00–00:00, следующий день')
  await expect(confirmation).toContainText('Анна · восстановление · 18:00–02:00, следующий день')
  await expect.poll(() => api.getBatchRequests()).toHaveLength(0)
  await confirmation.getByRole('button', { name: 'Создать смены' }).click()
  await expect.poll(() => api.getBatchRequests()).toHaveLength(1)

  expect(api.getBatchRequests()[0].body).toEqual({
    assignments: [
      {
        staffProfileId: 102,
        shiftDate: '2030-01-07',
        startsAt: '18:00',
        endsAt: '02:00',
        operation: 'CREATE'
      },
      {
        staffProfileId: 103,
        shiftDate: '2030-01-07',
        startsAt: '20:00',
        endsAt: '00:00',
        operation: 'CREATE'
      },
      {
        staffProfileId: 104,
        shiftDate: '2030-01-07',
        startsAt: '18:00',
        endsAt: '02:00',
        operation: 'RESTORE',
        expectedUpdatedAt: '2030-01-07T09:00:01Z'
      }
    ]
  })
  await expect(schedule.locator('.venue-schedule-form')).toBeHidden()
  await expect(schedule.locator('.venue-schedule-shift').filter({ hasText: 'Светлана' })).toContainText(
    '18:00–02:00, следующий день'
  )
  await expect(schedule.locator('.venue-schedule-shift').filter({ hasText: 'Максим' })).toContainText(
    '20:00–00:00, следующий день'
  )
  const restored = api.getAdminShifts(1).find((shift) => shift.staffProfileId === 104)
  expect(restored).toMatchObject({ id: 1002, storedStatus: 'scheduled', restoreAllowed: false })
})

test('future canceled schedule row restores through explicit row action with new time', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffScheduleApi(page)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()
  const schedule = page.locator('.venue-staff-schedule')
  const canceledRow = schedule.locator('.venue-schedule-shift').filter({ hasText: 'Анна' })
  await canceledRow.getByRole('button', { name: 'Восстановить' }).click()

  const form = schedule.locator('.venue-schedule-form')
  await expect(form.getByLabel('Дата начала')).toBeDisabled()
  await expect(form.getByLabel('Дата начала')).toHaveValue('2030-01-07')
  await expect(form.getByLabel('Начало')).toHaveValue('19:00')
  await expect(form.getByLabel('Окончание')).toHaveValue('01:00')
  await form.getByLabel('Начало').fill('20:00')
  await form.getByLabel('Окончание').fill('02:00')
  await form.getByRole('button', { name: 'Проверить восстановление' }).click()

  const confirmation = schedule.locator('.venue-schedule-confirmation')
  await expect(confirmation).toContainText('Отменённая смена: 07.01.2030 · 19:00–01:00')
  await expect(confirmation).toContainText('После восстановления: 07.01.2030 · 20:00–02:00')
  await confirmation.getByRole('button', { name: 'Восстановить смену' }).click()
  await expect.poll(() => api.getRestoreRequests()).toHaveLength(1)
  expect(api.getRestoreRequests()[0].body).toEqual({
    expectedUpdatedAt: '2030-01-07T09:00:01Z',
    startsAt: '20:00',
    endsAt: '02:00'
  })
  const restored = api.getAdminShifts(1).filter((shift) => shift.staffProfileId === 104)
  expect(restored).toHaveLength(1)
  expect(restored[0]).toMatchObject({
    id: 1002,
    startsAt: '20:00',
    endsAt: '02:00',
    storedStatus: 'scheduled',
    restoreAllowed: false
  })
  await expect(
    schedule.locator('.venue-schedule-shift').filter({ hasText: 'Анна' }).getByRole('button', {
      name: 'Восстановить'
    })
  ).toHaveCount(0)
})

test('scheduled staff conflict blocks the whole bulk confirmation', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffScheduleApi(page)
  const before = api.getAdminShifts(1)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()
  const schedule = page.locator('.venue-staff-schedule')
  await schedule.getByRole('button', { name: 'Добавить смену' }).click()
  const form = schedule.locator('.venue-schedule-form')
  await form.getByLabel('Дата начала').fill('2030-01-08')
  await form.getByLabel('Выбрать Алексей').check()
  await form.getByLabel('Выбрать Светлана').check()
  await expect(form).toContainText('Смена уже запланирована на эту дату.')
  await form.getByRole('button', { name: 'Проверить смены' }).click()
  await expect
    .poll(
      () =>
        api
          .getListRequests()
          .filter((request) => request.from === '2030-01-08' && request.to === '2030-01-08')
          .length
    )
    .toBe(1)
  await expect(schedule.locator('.venue-schedule-confirmation')).toBeHidden()
  expect(api.getBatchRequests()).toHaveLength(0)
  expect(api.getAdminShifts(1)).toEqual(before)
})

test('atomic staff batch error keeps every schedule row unchanged and draft retryable', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffScheduleApi(page)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()
  const schedule = page.locator('.venue-staff-schedule')
  await schedule.getByRole('button', { name: 'Добавить смену' }).click()
  const form = schedule.locator('.venue-schedule-form')
  await form.getByLabel('Выбрать Светлана').check()
  await form.getByLabel('Выбрать Максим').check()
  await form.getByRole('button', { name: 'Проверить смены' }).click()
  const confirmation = schedule.locator('.venue-schedule-confirmation')
  await expect(confirmation).toContainText('Будет создано: 2')
  const before = api.getAdminShifts(1)
  api.queueMutationError({
    status: 409,
    code: 'STAFF_SHIFT_INVALID_INTERVAL',
    message: 'Один из интервалов недоступен.'
  })
  await confirmation.getByRole('button', { name: 'Создать смены' }).click()
  await expect.poll(() => api.getBatchRequests()).toHaveLength(1)
  await expect(schedule).toContainText('Один из интервалов недоступен.')
  await expect(form).toBeVisible()
  await expect(confirmation).toBeHidden()
  await expect(form.getByLabel('Выбрать Светлана')).toBeChecked()
  await expect(form.getByLabel('Выбрать Максим')).toBeChecked()
  expect(api.getAdminShifts(1)).toEqual(before)
})

test('venue owner manages a weekly staff schedule with local overnight copy', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffScheduleApi(page)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()

  const schedule = page.locator('.venue-staff-schedule')
  await expect(schedule.getByRole('heading', { name: 'График смен', exact: true })).toBeVisible()
  await expect(schedule).toContainText('Europe/Moscow')
  await expect(schedule).toContainText('22:00–06:00, следующий день')
  await expect(schedule).toContainText('Запланирована')

  await schedule.getByRole('button', { name: 'Добавить смену' }).click()
  const form = schedule.locator('.venue-schedule-form')
  await form.getByLabel('Дата начала').fill('2030-01-10')
  await expect(form).toContainText('Часы работы заведения на этот день не настроены.')
  await form.getByLabel('Начало').fill('20:00')
  await form.getByLabel('Окончание').fill('04:00')
  await form.getByLabel('Выбрать Светлана').check()
  await expect(form).toContainText('20:00–04:00, следующий день')
  await form.getByRole('button', { name: 'Проверить смены' }).click()

  const confirmation = schedule.locator('.venue-schedule-confirmation')
  await expect(confirmation).toContainText('Будет создано: 1')
  await expect(confirmation).toContainText('Будет восстановлено: 0')
  await expect(confirmation).toContainText('Светлана · создание')
  await expect(confirmation).toContainText('20:00–04:00, следующий день')
  await expect(confirmation).toContainText('Часовой пояс: Europe/Moscow')
  await confirmation.getByRole('button', { name: 'Создать смены' }).click()
  await expect.poll(() => api.getMutations()).toHaveLength(1)
  expect(api.getMutations()[0]).toMatchObject({
    venueId: 1,
    method: 'POST',
    path: '/api/venue/1/staff/shifts/batch',
    body: {
      assignments: [
        {
          staffProfileId: 102,
          shiftDate: '2030-01-10',
          startsAt: '20:00',
          endsAt: '04:00',
          operation: 'CREATE'
        }
      ]
    }
  })

  let displayOnlyRow = schedule.locator('.venue-schedule-shift').filter({ hasText: 'Светлана' })
  await expect(displayOnlyRow).toContainText('Бармен')
  await displayOnlyRow.getByRole('button', { name: 'Редактировать' }).click()
  await form.getByLabel('Начало').fill('21:00')
  await form.getByLabel('Окончание').fill('05:00')
  await form.getByRole('button', { name: 'Проверить изменения' }).click()
  await expect(confirmation).toContainText('Было: 10.01.2030 · 20:00–04:00, следующий день')
  await expect(confirmation).toContainText('Станет: 10.01.2030 · 21:00–05:00, следующий день')
  await confirmation.getByRole('button', { name: 'Сохранить изменения' }).click()
  await expect.poll(() => api.getMutations()).toHaveLength(2)
  expect(api.getMutations()[1].body).toEqual({
    shiftDate: '2030-01-10',
    startsAt: '21:00',
    endsAt: '05:00',
    expectedUpdatedAt: '2030-01-07T11:00:00Z'
  })

  displayOnlyRow = schedule.locator('.venue-schedule-shift').filter({ hasText: 'Светлана' })
  await displayOnlyRow.getByRole('button', { name: 'Отменить' }).click()
  await expect(confirmation).toContainText('Подтвердите отмену смены')
  await confirmation.getByRole('button', { name: 'Отменить смену' }).click()
  await expect.poll(() => api.getMutations()).toHaveLength(3)
  expect(api.getMutations()[2].body).toEqual({
    expectedUpdatedAt: '2030-01-07T12:00:00Z',
    expectedConfirmationState: 'SCHEDULED'
  })
  await expect(displayOnlyRow.getByRole('button', { name: 'Редактировать' })).toHaveCount(0)
  await expect(displayOnlyRow.getByRole('button', { name: 'Отменить' })).toHaveCount(0)
  await expect(displayOnlyRow.getByRole('button', { name: 'Восстановить' })).toBeVisible()

  await schedule.getByRole('button', { name: 'Следующая неделя' }).click()
  await expect.poll(() => api.getListRequests().at(-1)).toMatchObject({
    venueId: 1,
    from: '2030-01-14',
    to: '2030-01-20'
  })
  await expect(schedule).toContainText('На этой неделе смен нет.')
})

test('venue manager has the same staff schedule editor controls', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueStaffScheduleApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER',
        permissions: ['STAFF_SCHEDULE_VIEW', 'STAFF_SCHEDULE_MANAGE']
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()
  const schedule = page.locator('.venue-staff-schedule')
  await expect(schedule.getByRole('button', { name: 'Добавить смену' })).toBeVisible()
  const row = schedule.locator('.venue-schedule-shift').filter({ hasText: 'Алексей' })
  await expect(row.getByRole('button', { name: 'Редактировать' })).toBeVisible()
  await expect(row.getByRole('button', { name: 'Отменить' })).toBeVisible()
  const canceledRow = schedule.locator('.venue-schedule-shift').filter({ hasText: 'Анна' })
  await expect(canceledRow.getByRole('button', { name: 'Восстановить' })).toBeVisible()
  await schedule.getByRole('button', { name: 'Добавить смену' }).click()
  await expect(schedule.getByLabel('Выбрать Светлана')).toBeVisible()
  await expect(schedule.getByRole('button', { name: 'Проверить смены' })).toBeVisible()
})

test('venue staff sees only own shifts and safe overlapping colleagues', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffScheduleApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'STAFF',
        permissions: ['STAFF_SCHEDULE_VIEW_OWN']
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByRole('button', { name: 'Персонал', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Мои смены', exact: true }).click()

  const schedule = page.locator('.venue-staff-schedule')
  await expect(schedule.getByRole('heading', { name: 'Мои смены', exact: true })).toBeVisible()
  await expect(schedule).toContainText('Микс')
  await expect(schedule).toContainText('22:00–06:00, следующий день')
  await expect(schedule).toContainText('Коллеги в этой смене')
  await expect(schedule).toContainText('Светлана · Бармен · 01:00–05:00 · Запланирована')
  await expect(schedule).not.toContainText('linkedUserId')
  await expect(schedule.getByRole('button', { name: 'Добавить смену' })).toHaveCount(0)
  await expect(schedule.getByRole('button', { name: 'Редактировать' })).toHaveCount(0)
  await expect(schedule.getByRole('button', { name: 'Отменить' })).toHaveCount(0)
  await expect(schedule.getByRole('button', { name: 'Восстановить' })).toHaveCount(0)
  await expect(schedule.getByRole('button', { name: 'Проверить смены' })).toHaveCount(0)
  expect(api.getListRequests().every((request) => request.path.endsWith('/me'))).toBe(true)

  const directMutation = await page.evaluate(async () => {
    const response = await fetch('/api/venue/1/staff/shifts/batch', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer e2e-session-token',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        assignments: [
          {
            staffProfileId: 101,
            shiftDate: '2030-01-10',
            startsAt: '18:00',
            endsAt: '02:00',
            operation: 'CREATE'
          }
        ]
      })
    })
    return { status: response.status, body: await response.json() }
  })
  expect(directMutation).toMatchObject({
    status: 403,
    body: { error: { code: 'FORBIDDEN' } }
  })
})

test('staff schedule stale edit requires refresh and never overwrites current row', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStaffScheduleApi(page)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()
  const schedule = page.locator('.venue-staff-schedule')
  const row = schedule.locator('.venue-schedule-shift').filter({ hasText: 'Алексей' })
  await row.getByRole('button', { name: 'Редактировать' }).click()
  const form = schedule.locator('.venue-schedule-form')
  await form.getByLabel('Начало').fill('20:00')
  await form.getByRole('button', { name: 'Проверить изменения' }).click()
  api.queueMutationError({
    status: 409,
    code: 'STAFF_SHIFT_STALE',
    message: 'График изменился. Обновите данные и повторите действие.'
  })
  await schedule.getByRole('button', { name: 'Сохранить изменения' }).click()

  await expect(schedule).toContainText('График изменился. Обновите данные и повторите действие.')
  await expect(schedule.locator('.venue-schedule-form')).toBeHidden()
  await expect(schedule.locator('.venue-schedule-confirmation')).toBeHidden()
  await expect(row).toContainText('22:00–06:00, следующий день')
  await schedule.getByRole('button', { name: 'Обновить график' }).click()
  await expect(schedule).not.toContainText('График изменился. Обновите данные и повторите действие.')
  await expect(row).toContainText('22:00–06:00, следующий день')
})

test('staff schedule venue switch clears draft ignores late response and restores allowed venue', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const accesses: VenueStaffScheduleAccessFixture[] = [
    {
      venueId: 1,
      venueName: 'Микс',
      venueCity: 'Москва',
      venueStatus: 'PUBLISHED',
      role: 'OWNER',
      permissions: ['STAFF_SCHEDULE_VIEW', 'STAFF_SCHEDULE_MANAGE']
    },
    {
      venueId: 2,
      venueName: 'Дым',
      venueCity: 'Казань',
      venueStatus: 'PUBLISHED',
      role: 'OWNER',
      permissions: ['STAFF_SCHEDULE_VIEW', 'STAFF_SCHEDULE_MANAGE']
    }
  ]
  const api = await mockVenueStaffScheduleApi(page, { accesses })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'График смен', exact: true }).click()
  let schedule = page.locator('.venue-staff-schedule')
  await expect(schedule).toContainText('Алексей')
  await schedule.getByRole('button', { name: 'Добавить смену' }).click()
  let form = schedule.locator('.venue-schedule-form')
  await form.getByLabel('Выбрать Светлана').check()
  await expect(form.getByLabel('Выбрать Светлана')).toBeChecked()
  const releaseVenueOne = api.deferNextList(1)
  await form.getByLabel('Дата начала').fill('2030-01-15')
  await expect(form).toContainText('Загружаем часы заведения')

  const venueSelect = page.locator('.venue-controls select.venue-select')
  await venueSelect.selectOption('2')
  schedule = page.locator('.venue-staff-schedule')
  await expect(schedule.locator('.venue-schedule-form')).toBeHidden()
  await expect(schedule).toContainText('Мария Второй')
  await expect(schedule).not.toContainText('Алексей')
  await expect.poll(() => new URL(page.url()).searchParams.get('venueId')).toBe('2')

  await schedule.getByRole('button', { name: 'Добавить смену' }).click()
  form = schedule.locator('.venue-schedule-form')
  await expect(form.locator('.venue-schedule-profile-option input:checked')).toHaveCount(0)
  await expect(form).not.toContainText('Светлана')

  releaseVenueOne()
  await expect(schedule).toContainText('Мария Второй')
  await expect(schedule).not.toContainText('Алексей')
  await page.reload()
  await expect(venueSelect).toHaveValue('2')
  await expect(page.locator('.venue-staff-schedule')).toContainText('Мария Второй')
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

test('venue owner opens the published guest preview with public read-only content', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueGuestPreviewApi(page, { role: 'OWNER' })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  const previewNav = page.getByRole('button', { name: 'Предпросмотр для гостя', exact: true })
  await expect(previewNav).toBeVisible()
  await previewNav.click()

  const preview = page.locator('.screen-root')
  await expect(preview.getByRole('heading', { name: 'Предпросмотр для гостя', exact: true })).toBeVisible()
  await expect(preview.getByText('Так карточку сейчас видит гость.', { exact: true })).toBeVisible()
  await expect(preview.getByText('Опубликовано', { exact: true })).toBeVisible()
  await expect(preview.getByRole('heading', { name: 'Микс', exact: true })).toBeVisible()
  await expect(preview).toContainText('Москва, Пилотная, 1')
  await expect(preview).toContainText('Опубликованное описание лаунжа.')
  await expect(preview).toContainText('Контакт: +7 900 100-20-30')
  await expect(preview).toContainText('Открыто · 18:00–02:00')

  await expect(preview).toContainText('График работы')
  await expect(preview).toContainText('Пн')
  await expect(preview).toContainText('18:00')
  await expect(preview).toContainText('Вс')
  await expect(preview).toContainText('Закрыто')
  await expect(preview).toContainText('Исключения по датам')
  await expect(preview).toContainText('12.01.2030')
  await expect(preview).toContainText('Санитарный день')
  await expect(preview).toContainText('13.01.2030')
  await expect(preview).toContainText('Специальный график')

  await expect(preview.getByRole('heading', { name: '📖 Фото-меню', exact: true })).toBeVisible()
  await expect(preview).toContainText('Ознакомительное меню в опубликованной карточке.')
  await expect(preview.getByRole('img', { name: '📖 Фото-меню 1' })).toBeVisible()

  await expect(preview.getByRole('heading', { name: 'Сегодня работают', exact: true })).toBeVisible()
  await expect(preview).toContainText('Анна')
  await expect(preview).toContainText('Мастер авторских миксов')
  await expect(preview).toContainText('цитрусовые, крепкие')
  await expect(preview).toContainText('Поможет подобрать крепость.')

  await expect(preview.getByRole('heading', { name: 'Акции', exact: true })).toBeVisible()
  await expect(preview).toContainText('Чай в подарок')
  await expect(preview).toContainText('К каждому авторскому миксу.')
  await expect(preview).toContainText('Условия: До закрытия заведения.')

  await expect(preview.getByRole('button', { name: 'В избранное', exact: true })).toHaveCount(0)
  await expect(preview.getByRole('button', { name: 'Забронировать', exact: true })).toHaveCount(0)
  await expect(preview.getByRole('button', { name: /Задать вопрос/ })).toHaveCount(0)
  await expect(preview.getByRole('button', { name: 'Вызвать персонал', exact: true })).toHaveCount(0)
  await expect(preview.getByRole('button', { name: /Чат|Поддержка|Обращение/ })).toHaveCount(0)
  await expect(preview.getByRole('button', { name: /Корзина|Оформить заказ|Мой заказ|Продлить/ })).toHaveCount(0)
  await expect(preview.getByRole('button', { name: /Настройки/ })).toHaveCount(0)
  await expect(preview.getByText(/продлить смену/i)).toHaveCount(0)
  await expect(preview.getByText(/заказное меню и корзина доступны/i)).toHaveCount(0)
  await expect(preview.getByText(/вы за столом/i)).toHaveCount(0)
  await expect(preview.locator('input:visible, textarea:visible, select:visible')).toHaveCount(0)
  await expect(preview.getByRole('button', { name: /Сохранить|Опубликовать|Удалить|Архивировать/ })).toHaveCount(0)
  const returnButton = preview.getByRole('button', { name: 'Вернуться в кабинет', exact: true })
  await expect(returnButton).toBeVisible()

  expect(api.getPreviewRequests()).toEqual([{ venueId: 1, method: 'GET' }])
  expect(api.getPreviewMediaRequests()).toEqual([
    {
      venueId: 1,
      path: '/api/guest/venue/1/info-sections/10/media/100',
      method: 'GET',
      authorization: 'Bearer e2e-session-token'
    }
  ])

  await returnButton.click()
  await expect(page).toHaveURL(/#\/dashboard$/)
})

test('venue manager can open the published guest preview', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueGuestPreviewApi(page, {
    role: 'MANAGER',
    previews: {
      1: buildVenueGuestPreviewFixture(1, {
        venue: {
          name: 'Опубликованный лаунж менеджера',
          displayAddress: 'Казань, Кремлёвская, 10'
        }
      })
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await page.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()
  const preview = page.locator('.screen-root')
  await expect(preview.getByRole('heading', { name: 'Предпросмотр для гостя', exact: true })).toBeVisible()
  await expect(preview.getByRole('heading', { name: 'Опубликованный лаунж менеджера', exact: true })).toBeVisible()
  await expect(preview).toContainText('Казань, Кремлёвская, 10')
})

test('guest preview uses the server mode when a published venue is temporarily unavailable', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueGuestPreviewApi(page, {
    venues: [
      {
        venueId: 1,
        venueName: 'Временно недоступный лаунж',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'OWNER',
        permissions: []
      }
    ],
    previews: {
      1: buildVenuePrivatePreviewFixture(1, {
        venueAvailabilityLabel: 'Заведение приостановлено.',
        venue: {
          name: 'Сохранённая карточка приостановленного лаунжа',
          status: 'PUBLISHED'
        }
      })
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()

  const preview = page.locator('.screen-root')
  await expect(preview.getByText('Черновик', { exact: true })).toBeVisible()
  await expect(preview.getByText('Заведение приостановлено.', { exact: true })).toBeVisible()
  await expect(
    preview.getByRole('heading', {
      name: 'Сохранённая карточка приостановленного лаунжа',
      exact: true
    })
  ).toBeVisible()
  await expect(preview.getByText('Опубликовано', { exact: true })).toHaveCount(0)
})

test('venue owner and manager open saved draft previews through the shared read-only card', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const venues: VenueGuestPreviewAccessFixture[] = [
    {
      venueId: 1,
      venueName: 'Черновик владельца',
      venueCity: 'Москва',
      venueStatus: 'DRAFT',
      role: 'OWNER',
      permissions: []
    },
    {
      venueId: 2,
      venueName: 'Черновик менеджера',
      venueCity: 'Казань',
      venueStatus: 'DRAFT',
      role: 'MANAGER',
      permissions: []
    }
  ]
  const api = await mockVenueGuestPreviewApi(page, {
    venues,
    previews: {
      1: buildVenuePrivatePreviewFixture(1, {
        venue: {
          name: 'Карточка до первой публикации',
          displayAddress: 'Москва, Черновая, 1',
          cardDescription: 'Сохранённое публичное описание.',
          guestContact: '+7 900 555-10-20'
        },
        infoSections: [
          {
            id: 10,
            type: 'about',
            title: 'О заведении',
            displayTitle: 'О заведении',
            text: 'Только видимый сохранённый раздел.',
            mediaCount: 2,
            media: [
              {
                id: 100,
                mediaType: 'image',
                sortOrder: 0,
                url: '/api/venue/1/guest-preview/info-sections/10/media/100'
              },
              {
                id: 101,
                mediaType: 'image',
                sortOrder: 1,
                url: '/api/venue/2/guest-preview/info-sections/10/media/101'
              }
            ]
          }
        ]
      }),
      2: buildVenuePrivatePreviewFixture(2, {
        venue: {
          name: 'Черновая карточка менеджера',
          displayAddress: 'Казань, Черновая, 2'
        }
      })
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()

  const preview = page.locator('.screen-root')
  await expect(preview.getByText('Черновик', { exact: true })).toBeVisible()
  await expect(
    preview.getByText(
      'Гости пока не видят эту карточку. Это закрытый предпросмотр сохранённой версии.',
      { exact: true }
    )
  ).toBeVisible()
  await expect(preview.getByText('Заведение ещё не опубликовано.', { exact: true })).toBeVisible()
  await expect(preview.getByRole('heading', { name: 'Карточка до первой публикации', exact: true })).toBeVisible()
  await expect(preview).toContainText('Москва, Черновая, 1')
  await expect(preview).toContainText('Сохранённое публичное описание.')
  await expect(preview).toContainText('Контакт: +7 900 555-10-20')
  await expect(preview).toContainText('Только видимый сохранённый раздел.')
  await expect(preview.getByRole('heading', { name: 'Сегодня работают', exact: true })).toBeVisible()
  await expect(preview).toContainText('Анна')
  await expect(preview.getByRole('heading', { name: 'Акции', exact: true })).toBeVisible()
  await expect(preview).toContainText('Чай в подарок')
  await expect(preview.getByRole('img', { name: 'О заведении 1' })).toBeVisible()
  await expect(preview.getByRole('button', { name: /Забронировать|В избранное|Задать вопрос|Вызвать персонал/ })).toHaveCount(0)
  await expect(preview.getByRole('button', { name: /Сохранить|Опубликовать|Удалить/ })).toHaveCount(0)
  expect(api.getPreviewRequests()).toEqual([{ venueId: 1, method: 'GET' }])
  expect(api.getPreviewMediaRequests()).toEqual([
    {
      venueId: 1,
      path: '/api/venue/1/guest-preview/info-sections/10/media/100',
      method: 'GET',
      authorization: 'Bearer e2e-session-token'
    }
  ])

  await page.locator('select.venue-select').selectOption('2')
  await expect(preview.getByRole('heading', { name: 'Черновая карточка менеджера', exact: true })).toBeVisible()
  await expect(preview).toContainText('Казань, Черновая, 2')
  await expect(preview.getByText('Черновик', { exact: true })).toBeVisible()
  expect(api.getPreviewRequests()).toEqual([
    { venueId: 1, method: 'GET' },
    { venueId: 2, method: 'GET' }
  ])
})

test('settings preview blocks unsaved public card and schedule changes, then shows saved updates', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueGuestPreviewApi(page, {
    venues: [
      {
        venueId: 1,
        venueName: 'Черновик',
        venueCity: 'Москва',
        venueStatus: 'DRAFT',
        role: 'OWNER',
        permissions: []
      }
    ],
    previews: {
      1: buildVenuePrivatePreviewFixture(1, {
        venue: {
          name: 'Сохранённая карточка',
          cardDescription: 'Описание уже сохранено на сервере.'
        }
      })
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()
  const publicCard = page.locator('.card').filter({
    has: page.getByRole('heading', { name: 'Публичная карточка', exact: true })
  })
  await expect(publicCard.getByText('В предпросмотре отображаются только сохранённые данные.')).toBeVisible()
  await publicCard.getByPlaceholder(/авторские чаши/).fill('Несохранённое описание формы.')
  await publicCard.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()

  await expect(page).toHaveURL(/#\/settings$/)
  await expect(
    page.getByText(
      'Есть несохранённые изменения. Сначала сохраните их, затем откройте предпросмотр.',
      { exact: true }
    ).first()
  ).toBeVisible()
  expect(api.getPreviewRequests()).toEqual([])

  await publicCard.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(publicCard.getByRole('button', { name: '✓ Сохранено', exact: true })).toBeVisible()
  await publicCard.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()

  await expect(page).toHaveURL(/#\/guest-preview\?from=settings$/)
  const preview = page.locator('.screen-root')
  await expect(preview.getByRole('heading', { name: 'Сохранённая карточка', exact: true })).toBeVisible()
  await expect(preview).toContainText('Несохранённое описание формы.')
  expect(api.getPreviewRequests()).toEqual([{ venueId: 1, method: 'GET' }])

  await preview.getByRole('button', { name: 'Вернуться к настройкам', exact: true }).click()
  await expect(page).toHaveURL(/#\/settings$/)

  const scheduleCard = page.locator('.card').filter({
    has: page.getByRole('heading', { name: 'Часы работы', exact: true })
  })
  let mondayRow = scheduleCard.getByText('Пн · 18:00-02:00', { exact: true }).locator('xpath=..')
  await mondayRow.locator('input[type="time"]').nth(1).fill('03:30')
  await publicCard.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()

  await expect(page).toHaveURL(/#\/settings$/)
  await expect(page.getByText(unsavedPreviewMessage, { exact: true }).first()).toBeVisible()
  expect(api.getPreviewRequests()).toHaveLength(1)

  await mondayRow.getByRole('button', { name: 'Сохранить день', exact: true }).click()
  await expect(page.locator('p.status')).toHaveText('Часы работы сохранены.')
  mondayRow = scheduleCard.getByText('Пн · 18:00-03:30', { exact: true }).locator('xpath=..')
  await expect(mondayRow).toBeVisible()
  await publicCard.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()
  await expect(page).toHaveURL(/#\/guest-preview\?from=settings$/)
  await expect(page.locator('.screen-root').getByText('Пн · 18:00–03:30', { exact: true })).toBeVisible()
  expect(api.getPreviewRequests()).toHaveLength(2)

  await page
    .locator('.screen-root')
    .getByRole('button', { name: 'Вернуться к настройкам', exact: true })
    .click()
  await expect(page).toHaveURL(/#\/settings$/)

  await scheduleCard.getByRole('button', { name: 'Закрыть период', exact: true }).click()
  const exceptionForm = scheduleCard.getByTestId('schedule-exception-form')
  await exceptionForm.locator('input[type="date"]').nth(0).fill('2030-02-10')
  await exceptionForm.locator('input[type="date"]').nth(1).fill('2030-02-10')
  await exceptionForm.locator('textarea').fill('Закрыто для уборки')
  await publicCard.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()

  await expect(page).toHaveURL(/#\/settings$/)
  await expect(page.getByText(unsavedPreviewMessage, { exact: true }).first()).toBeVisible()
  expect(api.getPreviewRequests()).toHaveLength(2)

  await exceptionForm.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(page.locator('p.status')).toHaveText('Исключение сохранено.')
  await publicCard.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()
  await expect(page).toHaveURL(/#\/guest-preview\?from=settings$/)
  await expect(page.locator('.screen-root').getByText('10.02.2030 · Закрыто', { exact: true })).toBeVisible()
  await expect(page.locator('.screen-root').getByText('Закрыто для уборки', { exact: true })).toBeVisible()
  expect(api.getPreviewRequests()).toHaveLength(3)

  await clickTelegramBackButton(page)
  await expect(page).toHaveURL(/#\/settings$/)
})

test('temporarily unavailable venues use safe private-preview reasons while deleted stays denied', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const statuses = ['HIDDEN', 'PAUSED', 'SUSPENDED', 'DELETED'] as const
  const api = await mockVenueGuestPreviewApi(page, {
    venues: statuses.map((status, index) => ({
      venueId: index + 1,
      venueName: `Статус ${status}`,
      venueCity: 'Москва',
      venueStatus: status,
      role: 'OWNER',
      permissions: []
    })),
    previews: {
      4: buildVenuePrivatePreviewFixture(4, {
        previewError: {
          status: 404,
          code: 'NOT_FOUND',
          message: 'Not found'
        }
      })
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()
  const preview = page.locator('.screen-root')

  const safeReasons = [
    'Заведение временно скрыто.',
    'Заведение приостановлено.',
    'Заведение приостановлено.'
  ]
  for (let index = 0; index < safeReasons.length; index += 1) {
    if (index > 0) {
      await page.locator('select.venue-select').selectOption(String(index + 1))
    }
    await expect(preview.getByText('Черновик', { exact: true })).toBeVisible()
    await expect(preview.getByText(safeReasons[index], { exact: true })).toBeVisible()
    await expect(preview.getByRole('button', { name: 'Вернуться в кабинет', exact: true })).toBeVisible()
  }

  await page.locator('select.venue-select').selectOption('4')
  await expect(
    preview.getByText('Заведение сейчас недоступно для гостевого просмотра.', { exact: true })
  ).toBeVisible()
  await expect(preview.getByText('Черновик', { exact: true })).toHaveCount(0)
  expect(api.getPreviewRequests().map((request) => request.venueId)).toEqual([1, 2, 3, 4])
})

test('venue staff cannot see or open the guest preview', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueGuestPreviewApi(page, {
    venues: [
      {
        venueId: 1,
        venueName: 'Черновик без доступа',
        venueCity: 'Москва',
        venueStatus: 'DRAFT',
        role: 'STAFF',
        permissions: ['ORDER_QUEUE_VIEW']
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Предпросмотр для гостя', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    window.location.hash = '#/guest-preview'
  })
  await expect(page.getByRole('heading', { name: 'Недостаточно прав', exact: true })).toBeVisible()
  await expect(page.getByText('У вас нет доступа к этому разделу.')).toBeVisible()
  expect(api.getPreviewRequests()).toEqual([])
  expect(api.getPreviewMediaRequests()).toEqual([])
})

test('venue guest preview clears stale data while switching venues', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const venues: VenueGuestPreviewAccessFixture[] = [
    {
      venueId: 1,
      venueName: 'Медленный лаунж',
      venueCity: 'Москва',
      venueStatus: 'PUBLISHED',
      role: 'OWNER',
      permissions: []
    },
    {
      venueId: 2,
      venueName: 'Черновой лаунж',
      venueCity: 'Казань',
      venueStatus: 'DRAFT',
      role: 'MANAGER',
      permissions: []
    }
  ]
  const api = await mockVenueGuestPreviewApi(page, {
    venues,
    deferredVenueIds: [1],
    previews: {
      1: buildVenueGuestPreviewFixture(1, {
        venue: {
          name: 'Медленный опубликованный лаунж',
          displayAddress: 'Москва, Медленная, 1'
        }
      }),
      2: buildVenuePrivatePreviewFixture(2, {
        venue: {
          name: 'Быстрый черновой лаунж',
          displayAddress: 'Казань, Быстрая, 2'
        }
      })
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()

  const preview = page.locator('.screen-root')
  await expect(preview.getByText('Загрузка предпросмотра...', { exact: true })).toBeVisible()
  await expect.poll(() => api.getPreviewRequests().map((request) => request.venueId)).toContain(1)

  await page.locator('select.venue-select').selectOption('2')
  await expect(preview.getByRole('heading', { name: 'Быстрый черновой лаунж', exact: true })).toBeVisible()
  await expect(preview.getByText('Черновик', { exact: true })).toBeVisible()
  await expect(preview).toContainText('Казань, Быстрая, 2')
  await expect(preview.getByText('Медленный опубликованный лаунж', { exact: true })).toHaveCount(0)

  api.releaseVenue(1)
  await expect.poll(async () =>
    preview.getByText('Медленный опубликованный лаунж', { exact: true }).count()
  ).toBe(0)
  await expect(preview.getByRole('heading', { name: 'Быстрый черновой лаунж', exact: true })).toBeVisible()
  expect(api.getPreviewRequests().map((request) => request.venueId)).toEqual([1, 2])
})

test('published guest preview clears a slow published venue before loading the next one', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const venues: VenueGuestPreviewAccessFixture[] = [
    {
      venueId: 1,
      venueName: 'Медленное опубликованное заведение',
      venueCity: 'Москва',
      venueStatus: 'PUBLISHED',
      role: 'OWNER',
      permissions: []
    },
    {
      venueId: 2,
      venueName: 'Быстрое опубликованное заведение',
      venueCity: 'Казань',
      venueStatus: 'PUBLISHED',
      role: 'MANAGER',
      permissions: []
    }
  ]
  const api = await mockVenueGuestPreviewApi(page, {
    venues,
    deferredVenueIds: [1, 2],
    previews: {
      1: buildVenueGuestPreviewFixture(1, {
        venue: {
          name: 'Старая опубликованная карточка',
          displayAddress: 'Москва, Медленная, 1'
        }
      }),
      2: buildVenueGuestPreviewFixture(2, {
        venue: {
          name: 'Новая опубликованная карточка',
          displayAddress: 'Казань, Быстрая, 2'
        }
      })
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()

  const preview = page.locator('.screen-root')
  await expect(preview.getByText('Загрузка предпросмотра...', { exact: true })).toBeVisible()
  await expect.poll(() => api.getPreviewRequests().map((request) => request.venueId)).toContain(1)

  await page.locator('select.venue-select').selectOption('2')
  await expect(preview.getByText('Загрузка предпросмотра...', { exact: true })).toBeVisible()
  api.releaseVenue(2)
  await expect(preview.getByRole('heading', { name: 'Новая опубликованная карточка', exact: true })).toBeVisible()
  await expect(preview).toContainText('Казань, Быстрая, 2')
  await expect(preview.getByText('Старая опубликованная карточка', { exact: true })).toHaveCount(0)

  api.releaseVenue(1)
  await expect.poll(async () =>
    preview.getByText('Старая опубликованная карточка', { exact: true }).count()
  ).toBe(0)
  await expect(preview.getByRole('heading', { name: 'Новая опубликованная карточка', exact: true })).toBeVisible()
  expect(api.getPreviewRequests().map((request) => request.venueId)).toEqual([1, 2])
})

test('venue guest preview ignores table cart and order context and uses GET-only preview traffic', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await page.addInitScript((token) => {
    window.localStorage.setItem('hookah_guest_table_token', token)
    window.sessionStorage.setItem('hookah_guest_table_token', token)
    window.sessionStorage.setItem(
      'hookah_guest_table_session_context:user:123456789',
      JSON.stringify({ tableToken: token, tableSessionId: 77 })
    )
    window.sessionStorage.setItem(
      'hookahMiniAppGuestTabSelection',
      JSON.stringify({ 'user:123456789:session:77': 88 })
    )
    window.localStorage.setItem(
      `hookah_guest_cart_draft:user:123456789:${token}`,
      JSON.stringify({
        items: [{ itemId: 200, qty: 3 }],
        commentDraft: 'Этот гостевой заказ не относится к preview.'
      })
    )
  }, tableToken)
  const api = await mockVenueGuestPreviewApi(page, { role: 'OWNER' })

  await page.goto(
    `?mode=venue&table_token=${encodeURIComponent(tableToken)}#tgWebAppData=${encodeURIComponent(mockInitData)}`
  )

  const previewNav = page.getByRole('button', { name: 'Предпросмотр для гостя', exact: true })
  await expect(previewNav).toBeVisible()
  api.clearTraffic()
  await previewNav.click()

  const preview = page.locator('.screen-root')
  await expect(preview.getByRole('heading', { name: 'Микс', exact: true })).toBeVisible()
  await expect(preview).toContainText('Москва, Пилотная, 1')
  await expect(preview.getByText(/вы за столом/i)).toHaveCount(0)
  await expect(preview.getByText(/корзин/i)).toHaveCount(0)
  await expect(preview.getByText(/этот гостевой заказ/i)).toHaveCount(0)

  await expect.poll(() => api.getPreviewRequests().length).toBe(1)
  const previewTraffic = api
    .getTraffic()
    .filter((request) => request.path.includes('/guest-preview'))
    .sort((left, right) => left.path.localeCompare(right.path))
  expect(previewTraffic).toEqual([
    { method: 'GET', path: '/api/venue/1/guest-preview' }
  ])
  expect(
    api.getTraffic().filter((request) =>
      /\/api\/guest\/(?:table|cart|order|tabs|staff-call|shift-extension)/.test(request.path)
    )
  ).toEqual([])
})

test('venue guest preview has safe loading empty error and unavailable states', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const venues: VenueGuestPreviewAccessFixture[] = [
    {
      venueId: 1,
      venueName: 'Пустая карточка',
      venueCity: 'Москва',
      venueStatus: 'PUBLISHED',
      role: 'OWNER',
      permissions: []
    },
    {
      venueId: 2,
      venueName: 'Ошибка карточки',
      venueCity: 'Москва',
      venueStatus: 'PUBLISHED',
      role: 'OWNER',
      permissions: []
    },
    {
      venueId: 3,
      venueName: 'Удалённое заведение',
      venueCity: 'Москва',
      venueStatus: 'DELETED',
      role: 'OWNER',
      permissions: []
    }
  ]
  const api = await mockVenueGuestPreviewApi(page, {
    venues,
    deferredVenueIds: [1],
    previews: {
      1: buildVenueGuestPreviewFixture(1, {
        venue: {
          guestContact: null,
          cardDescription: null,
          todaySchedule: null,
          weeklyHours: [],
          dateExceptions: [],
          todayStaff: [],
          promotions: []
        },
        infoSections: []
      }),
      2: buildVenueGuestPreviewFixture(2, {
        previewError: {
          status: 500,
          code: 'INTERNAL_ERROR',
          message: 'Preview failed'
        }
      }),
      3: buildVenuePrivatePreviewFixture(3, {
        previewError: {
          status: 404,
          code: 'NOT_FOUND',
          message: 'Not found'
        }
      })
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Предпросмотр для гостя', exact: true }).click()

  const preview = page.locator('.screen-root')
  await expect(preview.getByText('Загрузка предпросмотра...', { exact: true })).toBeVisible()
  api.releaseVenue(1)
  await expect(preview.getByText('Информация пока не заполнена.', { exact: true })).toBeVisible()

  await page.locator('select.venue-select').selectOption('2')
  await expect(preview.getByRole('button', { name: 'Повторить', exact: true })).toBeVisible()
  await expect(preview.getByText('Информация пока не заполнена.', { exact: true })).toHaveCount(0)

  await page.locator('select.venue-select').selectOption('3')
  await expect(
    preview.getByText('Заведение сейчас недоступно для гостевого просмотра.', { exact: true })
  ).toBeVisible()
  expect(api.getPreviewRequests().map((request) => request.venueId)).toEqual([1, 2, 3])
})

test('guest direct venue route cannot read an unavailable draft card', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, { venueAvailable: false })
  const apiPaths: string[] = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path.startsWith('/api/')) apiPaths.push(path)
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.evaluate(() => {
    window.location.hash = '#/venue/1'
  })

  await expect(page.locator('.screen-root .error-card:visible')).toBeVisible()
  await expect(page.getByText('Сохранённое описание черновика.', { exact: true })).toHaveCount(0)
  await expect.poll(() => apiPaths.includes('/api/guest/venue/1')).toBe(true)
  expect(apiPaths).not.toContain('/api/venue/1/guest-preview')
})

test('venue owner disables and re-enables staff module without resetting nested settings', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueShiftExtensionApi(page, {
    role: 'OWNER',
    permissions: ['STAFF_MODULE_SETTINGS_MANAGE'],
    staffModuleSettings: {
      teamScheduleModuleEnabled: true,
      guestTeamVisible: false,
      todayStaffSource: 'SCHEDULE',
      updatedAt: '2030-01-10T18:00:00.000001Z'
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()

  const card = page.getByTestId('staff-module-settings')
  const master = card.getByTestId('staff-module-master')
  const guestVisible = card.getByTestId('staff-module-guest-visible')
  const scheduleSource = card.getByTestId('staff-module-source-schedule')
  await expect(card.getByRole('heading', { name: 'Команда и график смен' })).toBeVisible()
  await expect(master).toBeChecked()
  await expect(guestVisible).not.toBeChecked()
  await expect(scheduleSource).toBeChecked()

  await master.uncheck()
  await expect(guestVisible).toBeDisabled()
  await expect(scheduleSource).toBeDisabled()
  await card.getByTestId('staff-module-save').click()
  const confirmation = card.getByTestId('staff-module-disable-confirmation')
  await expect(confirmation).toBeVisible()
  await expect(confirmation).toContainText('Профили, смены, приглашения, роли и доступ сотрудников')
  await confirmation.getByRole('button', { name: 'Отключить модуль' }).click()

  await expect.poll(() => api.getStaffModuleSettings()).toMatchObject({
    teamScheduleModuleEnabled: false,
    guestTeamVisible: false,
    todayStaffSource: 'SCHEDULE'
  })
  await expect(card).toContainText('Сохранено: модуль выключен')
  await expect(guestVisible).not.toBeChecked()
  await expect(scheduleSource).toBeChecked()

  await master.check()
  await expect(guestVisible).toBeEnabled()
  await expect(scheduleSource).toBeEnabled()
  await expect(guestVisible).not.toBeChecked()
  await expect(scheduleSource).toBeChecked()
  await card.getByTestId('staff-module-save').click()
  await expect.poll(() => api.getStaffModuleSettings()).toMatchObject({
    teamScheduleModuleEnabled: true,
    guestTeamVisible: false,
    todayStaffSource: 'SCHEDULE'
  })
  expect(api.getUpdateStaffModuleSettingsCalls()).toBe(2)
})

test('venue manager sees staff module settings through the narrow permission', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueShiftExtensionApi(page, {
    role: 'MANAGER',
    permissions: ['STAFF_MODULE_SETTINGS_MANAGE']
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()
  await expect(page.getByTestId('staff-module-settings')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Команда и график смен' })).toBeVisible()
})

test('staff module settings venue switch ignores a late response from the previous venue', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const accesses: VenueStaffScheduleAccessFixture[] = [
    {
      venueId: 1,
      venueName: 'Микс',
      venueCity: 'Москва',
      venueStatus: 'PUBLISHED',
      role: 'OWNER',
      permissions: ['STAFF_MODULE_SETTINGS_MANAGE']
    },
    {
      venueId: 2,
      venueName: 'Дым',
      venueCity: 'Казань',
      venueStatus: 'PUBLISHED',
      role: 'OWNER',
      permissions: ['STAFF_MODULE_SETTINGS_MANAGE']
    }
  ]
  await mockVenueStaffScheduleApi(page, { accesses })
  let releaseVenueOne = () => undefined
  let venueOneRequestStarted = false
  let venueOneRequestReleased = false
  const venueOneGate = new Promise<void>((resolve) => {
    releaseVenueOne = resolve
  })
  const settingsByVenue: Record<number, VenueStaffModuleSettingsFixture> = {
    1: {
      teamScheduleModuleEnabled: false,
      guestTeamVisible: true,
      todayStaffSource: 'MANUAL',
      updatedAt: '2030-01-10T18:00:00.000001Z'
    },
    2: {
      teamScheduleModuleEnabled: true,
      guestTeamVisible: false,
      todayStaffSource: 'SCHEDULE',
      updatedAt: '2030-01-10T18:00:00.000002Z'
    }
  }
  await page.route('**/api/venue/*/staff-module-settings', async (route) => {
    const venueId = Number(new URL(route.request().url()).pathname.split('/')[3])
    if (venueId === 1) {
      venueOneRequestStarted = true
      await venueOneGate
      venueOneRequestReleased = true
    }
    await route.fulfill(jsonResponse(settingsByVenue[venueId]))
  })
  await page.route('**/api/venue/*/public-card', async (route) => {
    const venueId = Number(new URL(route.request().url()).pathname.split('/')[3])
    await route.fulfill(jsonResponse(buildPublicCardSettings({ venueId, name: accesses[venueId - 1].venueName })))
  })
  await page.route('**/api/venue/*/schedule', async (route) => {
    const venueId = Number(new URL(route.request().url()).pathname.split('/')[3])
    await route.fulfill(jsonResponse({ ...buildVenueScheduleSettings(), venueId }))
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()
  await expect.poll(() => venueOneRequestStarted).toBe(true)

  const venueSelect = page.locator('.venue-controls select.venue-select')
  await venueSelect.selectOption('2')
  const card = page.getByTestId('staff-module-settings')
  await expect(card.getByTestId('staff-module-master')).toBeChecked()
  await expect(card.getByTestId('staff-module-guest-visible')).not.toBeChecked()
  await expect(card.getByTestId('staff-module-source-schedule')).toBeChecked()
  releaseVenueOne()
  await expect.poll(() => venueOneRequestReleased).toBe(true)

  await expect(card.getByTestId('staff-module-master')).toBeChecked()
  await expect(card.getByTestId('staff-module-guest-visible')).not.toBeChecked()
  await expect(card.getByTestId('staff-module-source-schedule')).toBeChecked()
  await expect.poll(() => new URL(page.url()).searchParams.get('venueId')).toBe('2')
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

test('venue owner configures public review link settings', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueShiftExtensionApi(page, {
    role: 'OWNER',
    permissions: ['VENUE_SETTINGS'],
    publicReviewUrl: null
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Настройки', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Настройки', exact: true }).click()
  const reviewCard = page.locator('.card').filter({ has: page.getByRole('heading', { name: 'Ссылка для отзывов' }) })
  await expect(reviewCard).toContainText('Эту кнопку покажем гостю только после оценки 5/5')
  await expect(reviewCard).toContainText('Где взять ссылку: откройте карточку заведения в Яндекс.Картах')
  await expect(reviewCard).toContainText('Не обещайте скидки или бонусы за отзыв')
  await expect(reviewCard.getByText('Ссылка пока не задана.')).toBeVisible()
  await expect(reviewCard.getByRole('button', { name: 'Сохранить' })).toBeDisabled()

  await reviewCard.getByPlaceholder('https://yandex.ru/maps/.../reviews').fill('http://unsafe.example/reviews')
  await reviewCard.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.locator('p.status')).toContainText('Ссылка должна начинаться с https://')
  expect(api.getUpdatePublicReviewUrlCalls()).toBe(0)

  await reviewCard.getByPlaceholder('https://yandex.ru/maps/.../reviews').fill('https://yandex.ru/maps/org/mix/reviews')
  await reviewCard.getByRole('button', { name: 'Сохранить' }).click()
  await expect(page.locator('p.status')).toHaveText('Ссылка для отзывов сохранена.')
  await expect(reviewCard).toContainText('Текущая ссылка: https://yandex.ru/maps/org/mix/reviews')
  expect(api.getPublicReviewUrl()).toBe('https://yandex.ru/maps/org/mix/reviews')

  await reviewCard.getByRole('button', { name: 'Очистить' }).click()
  await expect(page.locator('p.status')).toHaveText('Ссылка для отзывов очищена.')
  await expect(reviewCard.getByText('Ссылка пока не задана.')).toBeVisible()
  expect(api.getPublicReviewUrl()).toBeNull()
})

test('venue staff does not see public card or staff module settings', async ({ page }) => {
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
  await expect(page.getByRole('heading', { name: 'Команда и график смен' })).toHaveCount(0)
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

test('venue owner creates edits activates and pauses informational promotion', async ({ page }) => {
  const referenceInstant = '2020-06-15T12:00:00.000Z'
  const referenceEpochMs = Date.parse(referenceInstant)
  const localPeriod = {
    startsAt: '2020-06-14T15:00',
    endsAt: '2020-06-16T15:00'
  }
  const expectedPeriod = {
    startsAt: '2020-06-14T12:00:00.000Z',
    endsAt: '2020-06-16T12:00:00.000Z'
  }

  await page.clock.setFixedTime(referenceInstant)
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenuePromotionsApi(page, { role: 'OWNER', nowEpochMs: referenceEpochMs })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Акции', exact: true })).toBeVisible()
  await expect(
    page.getByText('Акция носит информационный характер. Скидки и промокоды автоматически к заказу не применяются.')
  ).toBeVisible()

  await page.getByRole('button', { name: 'Создать акцию' }).click()
  const form = page.locator('.venue-promotion-form')
  await form.getByLabel('Название акции', { exact: true }).fill('Вечер для друзей')
  await form.getByLabel('Описание', { exact: true }).fill('Специальное предложение для компаний.')
  await form.getByLabel(/^Условия/).fill('Подробности уточняйте у персонала.')
  await form.getByLabel('Начало', { exact: true }).fill(localPeriod.endsAt)
  await form.getByLabel('Окончание', { exact: true }).fill(localPeriod.endsAt)
  await form.getByRole('button', { name: 'Сохранить черновик' }).click()
  await expect(form.getByText('Начало акции должно быть раньше окончания.')).toBeVisible()

  await form.getByLabel('Начало', { exact: true }).fill(localPeriod.startsAt)
  await form.getByRole('button', { name: 'Сохранить черновик' }).click()
  await expect(page.getByText('Черновик акции создан.')).toBeVisible()
  await expect(form).toBeHidden()
  const informationalCard = page.locator('.venue-promotion-card').filter({ hasText: 'Вечер для друзей' })
  await expect(informationalCard).toContainText('Черновик')
  await expect(informationalCard).toContainText('Информационная акция')
  expect(api.getMutations()).toEqual(['create'])
  expect(api.getPromotions()[0]).toMatchObject({
    status: 'DRAFT',
    templateType: 'TEXT_ONLY',
    rule: null,
    ...expectedPeriod
  })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Открыть карточку' }).click()
  await expect(page.locator('.guest-venue-promotions')).toHaveCount(0)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()
  const draftCard = page.locator('.venue-promotion-card').filter({ hasText: 'Вечер для друзей' })
  await draftCard.getByRole('button', { name: 'Опубликовать' }).click()
  await expect(page.getByText('Акция опубликована.')).toBeVisible()
  expect(api.getPromotions()[0].status).toBe('ACTIVE')

  const activeCard = page.locator('.venue-promotion-card').filter({ hasText: 'Вечер для друзей' })
  await activeCard.getByRole('button', { name: 'Редактировать' }).click()
  await form.getByLabel('Название акции', { exact: true }).fill('Обновлённый вечер')
  await form.getByRole('button', { name: 'Сохранить изменения' }).click()
  await expect(page.getByText('Изменения сохранены.')).toBeVisible()
  await expect(page.locator('.venue-promotion-card').filter({ hasText: 'Обновлённый вечер' })).toBeVisible()
  expect(api.getMutations()).toEqual(['create', 'active', 'update'])
  const updatedPromotion = api.getPromotions()[0]
  expect(updatedPromotion).toMatchObject({
    title: 'Обновлённый вечер',
    status: 'ACTIVE',
    ...expectedPeriod
  })
  expect(Date.parse(updatedPromotion.startsAt)).toBeLessThan(referenceEpochMs)
  expect(Date.parse(updatedPromotion.endsAt)).toBeGreaterThan(referenceEpochMs)

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  const guestVenueResponsePromise = page.waitForResponse((response) => {
    return response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/guest/venue/1'
  })
  await page.getByRole('button', { name: 'Открыть карточку' }).click()
  const guestVenueResponse = await guestVenueResponsePromise
  expect(guestVenueResponse.ok()).toBe(true)
  const guestVenueBody = (await guestVenueResponse.json()) as {
    venue: { promotions: GuestVenuePromotionFixture[] }
  }
  expect(guestVenueBody.venue.promotions).toEqual([
    expect.objectContaining({
      title: 'Обновлённый вечер',
      ...expectedPeriod
    })
  ])
  await expect(page.locator('.guest-venue-promotions')).toContainText('Обновлённый вечер')

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()
  await page
    .locator('.venue-promotion-card')
    .filter({ hasText: 'Обновлённый вечер' })
    .getByRole('button', { name: 'Приостановить' })
    .click()
  await expect(page.getByText('Акция приостановлена.')).toBeVisible()
  expect(api.getPromotions()[0].status).toBe('PAUSED')

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Открыть карточку' }).click()
  await expect(page.locator('.guest-venue-promotions')).toHaveCount(0)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()
  const pausedCard = page.locator('.venue-promotion-card').filter({ hasText: 'Обновлённый вечер' })
  await pausedCard.getByRole('button', { name: 'Редактировать' }).click()
  await form.getByLabel('Описание', { exact: true }).fill('Обновлённое предложение для компаний.')
  await form.getByRole('button', { name: 'Сохранить изменения' }).click()
  await pausedCard.getByRole('button', { name: 'Опубликовать' }).click()
  await expect(page.getByText('Акция опубликована.')).toBeVisible()

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Открыть карточку' }).click()
  await expect(page.locator('.guest-venue-promotions')).toContainText('Обновлённое предложение для компаний.')
})

test('venue promotions present derived effective states without lifecycle mutation and extend expired periods', async ({ page }) => {
  const referenceInstant = '2030-01-10T12:00:00.000Z'
  await page.clock.setFixedTime(referenceInstant)
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenuePromotionsApi(page, {
    role: 'OWNER',
    nowEpochMs: Date.parse(referenceInstant),
    promotions: [
      {
        id: 950,
        title: 'Действует в текущем периоде',
        description: 'Показывается гостям сейчас.',
        startsAt: '2030-01-09T12:00:00.000Z',
        endsAt: '2030-01-11T12:00:00.000Z',
        status: 'ACTIVE'
      },
      {
        id: 951,
        title: 'Будущая акция',
        description: 'Начнётся позже.',
        startsAt: '2030-01-11T12:00:00.000Z',
        endsAt: '2030-01-12T12:00:00.000Z',
        status: 'ACTIVE'
      },
      {
        id: 952,
        title: 'Завершённая акция',
        description: 'Её период уже закончился.',
        startsAt: '2030-01-01T12:00:00.000Z',
        endsAt: '2030-01-09T12:00:00.000Z',
        status: 'ACTIVE'
      },
      {
        id: 953,
        title: 'Приостановленная завершённая акция',
        description: 'Manual pause имеет приоритет.',
        startsAt: '2030-01-01T12:00:00.000Z',
        endsAt: '2030-01-09T12:00:00.000Z',
        status: 'PAUSED'
      },
      {
        id: 954,
        title: 'Черновик с прошедшим периодом',
        description: 'Draft имеет приоритет.',
        startsAt: '2030-01-01T12:00:00.000Z',
        endsAt: '2030-01-09T12:00:00.000Z',
        status: 'DRAFT'
      },
      {
        id: 955,
        title: 'Архив с прошедшим периодом',
        description: 'Archive имеет приоритет.',
        startsAt: '2030-01-01T12:00:00.000Z',
        endsAt: '2030-01-09T12:00:00.000Z',
        status: 'ARCHIVED'
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()

  const currentPanel = page.locator('#venue-promotions-current-panel')
  const archivedPanel = page.locator('#venue-promotions-archived-panel')
  const activeCard = currentPanel.locator('[data-promotion-id="950"]')
  await expect(activeCard).toHaveAttribute('data-effective-state', 'ACTIVE_NOW')
  await expect(activeCard.getByText('Действует сейчас', { exact: true })).toBeVisible()
  await expect(activeCard.getByText('Период завершён', { exact: true })).toHaveCount(0)

  const scheduledCard = currentPanel.locator('[data-promotion-id="951"]')
  await expect(scheduledCard).toHaveAttribute('data-effective-state', 'SCHEDULED')
  await expect(scheduledCard.getByText('Запланирована', { exact: true })).toBeVisible()
  await expect(scheduledCard).toContainText('Акция начнёт показываться гостям в указанный период.')

  const expiredGroup = currentPanel.locator('[data-group="expired"]')
  const expiredCard = currentPanel.locator('[data-promotion-id="952"]')
  await expect(expiredGroup).toBeVisible()
  await expect(expiredCard).toHaveAttribute('data-effective-state', 'EXPIRED')
  await expect(expiredCard.getByText('Период завершён', { exact: true })).toBeVisible()
  await expect(expiredCard.getByText('Активна', { exact: true })).toHaveCount(0)
  await expect(expiredCard).toContainText('Акция сейчас не показывается гостям. Измените даты, чтобы она снова начала действовать.')
  await expect(expiredCard.getByRole('button', { name: 'Продлить период', exact: true })).toBeVisible()
  await expect(expiredCard.getByRole('button', { name: 'Редактировать', exact: true })).toBeVisible()
  await expect(expiredCard.getByRole('button', { name: 'Архивировать', exact: true })).toBeVisible()
  await expect(expiredCard.getByRole('button', { name: 'Приостановить', exact: true })).toHaveCount(0)

  const pausedCard = currentPanel.locator('[data-promotion-id="953"]')
  await expect(pausedCard).toHaveAttribute('data-effective-state', 'PAUSED')
  await expect(pausedCard.getByText('Приостановлена', { exact: true })).toBeVisible()
  await expect(pausedCard.getByText('Период завершён', { exact: true })).toHaveCount(0)
  await expect(currentPanel.locator('[data-group="paused"]')).toContainText('Приостановленная завершённая акция')

  const draftCard = currentPanel.locator('[data-promotion-id="954"]')
  await expect(draftCard).toHaveAttribute('data-effective-state', 'DRAFT')
  await expect(draftCard.getByText('Черновик', { exact: true })).toBeVisible()
  await expect(draftCard.getByText('Период завершён', { exact: true })).toHaveCount(0)

  await page.getByRole('tab', { name: 'Архив', exact: true }).click()
  const archivedCard = archivedPanel.locator('[data-promotion-id="955"]')
  await expect(archivedCard).toHaveAttribute('data-effective-state', 'ARCHIVED')
  await expect(archivedCard.getByText('Архив', { exact: true })).toBeVisible()
  await expect(archivedCard.getByText('Период завершён', { exact: true })).toHaveCount(0)
  await expect(archivedPanel.locator('[data-promotion-id="952"]')).toHaveCount(0)

  await page.getByRole('tab', { name: 'Текущие', exact: true }).click()
  await expiredCard.getByRole('button', { name: 'Продлить период', exact: true }).click()
  const form = page.locator('.venue-promotion-form')
  await expect(form.getByRole('heading', { name: 'Редактировать акцию', exact: true })).toBeVisible()
  await form.getByLabel('Начало', { exact: true }).fill('2030-01-10T14:00')
  await form.getByLabel('Окончание', { exact: true }).fill('2030-01-11T15:00')
  await form.getByRole('button', { name: 'Сохранить изменения', exact: true }).click()
  await expect(page.getByText('Изменения сохранены.', { exact: true })).toBeVisible()
  const extendedCard = currentPanel.locator('[data-promotion-id="952"]')
  await expect(extendedCard).toHaveAttribute('data-effective-state', 'ACTIVE_NOW')
  await expect(extendedCard.getByText('Действует сейчас', { exact: true })).toBeVisible()
  expect(api.getMutations()).toEqual(['update'])
  expect(api.getLifecycleRequests()).toEqual([])

  await page.clock.runFor(60_000)
  expect(api.getLifecycleRequests()).toEqual([])
})

test('venue promotion tabs separate current and archived cards with accessible keyboard navigation', async ({ page }) => {
  const referenceInstant = '2030-01-10T12:00:00.000Z'
  await page.clock.setFixedTime(referenceInstant)
  await installTelegramWebApp(page, 123456789)
  await mockVenuePromotionsApi(page, {
    role: 'OWNER',
    promotions: [
      {
        id: 920,
        title: 'Черновик во вкладке текущих',
        description: 'Черновик остаётся текущей акцией.',
        startsAt: '2030-01-01T00:00:00.000Z',
        endsAt: '2030-02-01T00:00:00.000Z',
        status: 'DRAFT'
      },
      {
        id: 921,
        title: 'Активная акция во вкладке текущих',
        description: 'Активная акция остаётся текущей.',
        startsAt: '2030-01-01T00:00:00.000Z',
        endsAt: '2030-02-01T00:00:00.000Z',
        status: 'ACTIVE'
      },
      {
        id: 922,
        title: 'Пауза во вкладке текущих',
        description: 'Приостановленная акция остаётся текущей.',
        startsAt: '2030-01-01T00:00:00.000Z',
        endsAt: '2030-02-01T00:00:00.000Z',
        status: 'PAUSED'
      },
      {
        id: 923,
        title: 'Только архивная акция',
        description: 'Архивная акция показывается отдельно.',
        startsAt: '2030-01-01T00:00:00.000Z',
        endsAt: '2030-02-01T00:00:00.000Z',
        status: 'ARCHIVED'
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()

  const tablist = page.getByRole('tablist', { name: 'Списки акций', exact: true })
  const currentTab = tablist.getByRole('tab', { name: 'Текущие', exact: true })
  const archivedTab = tablist.getByRole('tab', { name: 'Архив', exact: true })
  const currentPanel = page.locator('#venue-promotions-current-panel')
  const archivedPanel = page.locator('#venue-promotions-archived-panel')

  await expect(currentTab).toHaveAttribute('aria-selected', 'true')
  await expect(currentTab).toHaveAttribute('aria-controls', 'venue-promotions-current-panel')
  await expect(currentTab).toHaveText('Текущие')
  await expect(archivedTab).toHaveAttribute('aria-selected', 'false')
  await expect(archivedTab).toHaveAttribute('aria-controls', 'venue-promotions-archived-panel')
  await expect(archivedTab).toHaveText('Архив')
  await expect(currentPanel).toHaveAttribute('aria-labelledby', 'venue-promotions-current-tab')
  await expect(archivedPanel).toHaveAttribute('aria-labelledby', 'venue-promotions-archived-tab')
  await expect(currentPanel).toBeVisible()
  await expect(archivedPanel).toBeHidden()
  await expect(page.locator('.venue-promotion-panel:visible')).toHaveCount(1)
  await expect(page.locator('.venue-promotion-groups:visible')).toHaveCount(1)
  await expect(currentPanel.locator('[data-promotion-id="920"]')).toBeVisible()
  await expect(currentPanel.locator('[data-promotion-id="921"]')).toBeVisible()
  await expect(currentPanel.locator('[data-promotion-id="922"]')).toBeVisible()
  await expect(currentPanel.locator('[data-promotion-id="923"]')).toHaveCount(0)

  await currentTab.focus()
  await currentTab.press('ArrowRight')
  await expect(archivedTab).toBeFocused()
  await expect(archivedTab).toHaveAttribute('aria-selected', 'true')
  await expect(currentPanel).toBeHidden()
  await expect(archivedPanel).toBeVisible()
  await expect(page.locator('.venue-promotion-panel:visible')).toHaveCount(1)
  await expect(page.locator('.venue-promotion-groups:visible')).toHaveCount(1)
  await expect(archivedPanel.locator('[data-promotion-id="923"]')).toBeVisible()
  await expect(archivedPanel.locator('[data-promotion-id="920"]')).toHaveCount(0)
  await expect(archivedPanel.locator('[data-promotion-id="921"]')).toHaveCount(0)
  await expect(archivedPanel.locator('[data-promotion-id="922"]')).toHaveCount(0)

  await archivedTab.press('Home')
  await expect(currentTab).toBeFocused()
  await expect(currentTab).toHaveAttribute('aria-selected', 'true')
  await archivedTab.click()
  await expect(archivedTab).toBeFocused()
  await expect(archivedTab).toHaveAttribute('aria-selected', 'true')
  await currentTab.click()
  await expect(currentTab).toBeFocused()
  await expect(currentPanel.locator('[data-promotion-id="920"]')).toBeVisible()
})

test('venue promotion pause and archive use separate confirmed lifecycle requests', async ({ page }) => {
  const referenceInstant = '2030-01-10T12:00:00.000Z'
  await page.clock.setFixedTime(referenceInstant)
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenuePromotionsApi(page, {
    role: 'OWNER',
    nowEpochMs: Date.parse(referenceInstant),
    promotions: [
      {
        id: 910,
        title: 'Активная акция для паузы',
        description: 'Проверка отдельного status request.',
        startsAt: '2030-01-01T00:00:00.000Z',
        endsAt: '2030-02-01T00:00:00.000Z',
        status: 'ACTIVE',
        templateType: 'TEXT_ONLY',
        rule: null
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()
  await expect.poll(() => api.getPromotionListRequests()).toBe(1)

  const currentTab = page.getByRole('tab', { name: 'Текущие', exact: true })
  const archivedTab = page.getByRole('tab', { name: 'Архив', exact: true })
  const currentPanel = page.locator('#venue-promotions-current-panel')
  const archivedPanel = page.locator('#venue-promotions-archived-panel')
  await archivedTab.click()
  await expect(archivedPanel.getByText('Архивных акций пока нет.', { exact: true })).toBeVisible()
  await expect(currentPanel).toBeHidden()
  await currentTab.click()

  await page
    .locator('#venue-promotions-current-panel [data-promotion-id="910"]')
    .getByRole('button', { name: 'Приостановить', exact: true })
    .click()
  await expect(currentPanel.locator('[data-group="paused"] [data-promotion-id="910"]')).toBeVisible()
  expect(api.getLifecycleRequests()).toEqual([
    {
      method: 'POST',
      path: '/api/venue/1/promotions/910/status',
      promotionId: 910,
      body: { status: 'PAUSED' }
    }
  ])
  expect(api.getLifecycleRequests().filter((request) => request.method === 'DELETE')).toHaveLength(0)

  let dismissedArchive = false
  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toBe('Архивировать акцию «Активная акция для паузы»?')
    dismissedArchive = true
    await dialog.dismiss()
  })
  await page
    .locator('#venue-promotions-current-panel [data-promotion-id="910"]')
    .getByRole('button', { name: 'Архивировать', exact: true })
    .click()
  expect(dismissedArchive).toBe(true)
  expect(api.getLifecycleRequests()).toHaveLength(1)

  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toBe('Архивировать акцию «Активная акция для паузы»?')
    await dialog.accept()
  })
  await page
    .locator('#venue-promotions-current-panel [data-promotion-id="910"]')
    .getByRole('button', { name: 'Архивировать', exact: true })
    .click()
  await expect(currentPanel.locator('[data-promotion-id="910"]')).toHaveCount(0)
  await expect(currentPanel.getByText('Текущих акций пока нет.', { exact: true })).toBeVisible()
  await expect(
    currentPanel.getByText(
      'Создайте акцию, чтобы подготовить или опубликовать предложение для гостей.',
      { exact: true }
    )
  ).toBeVisible()
  await archivedTab.click()
  await expect(archivedPanel.locator('[data-group="archived"] [data-promotion-id="910"]')).toBeVisible()
  expect(api.getLifecycleRequests()).toEqual([
    {
      method: 'POST',
      path: '/api/venue/1/promotions/910/status',
      promotionId: 910,
      body: { status: 'PAUSED' }
    },
    {
      method: 'DELETE',
      path: '/api/venue/1/promotions/910',
      promotionId: 910
    }
  ])
})

test('venue promotion stale status and archive refresh authoritative state without false success', async ({ page }) => {
  const referenceInstant = '2030-01-10T12:00:00.000Z'
  await page.clock.setFixedTime(referenceInstant)
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenuePromotionsApi(page, {
    role: 'OWNER',
    venueId: 2,
    accessVenueIds: [1, 2],
    promotionListFailureRequests: [2],
    nowEpochMs: Date.parse(referenceInstant),
    promotions: [
      {
        id: 912,
        title: 'Конфликт статуса',
        description: 'Сервер уже архивировал акцию.',
        startsAt: '2030-01-01T00:00:00.000Z',
        endsAt: '2030-02-01T00:00:00.000Z',
        status: 'ACTIVE',
        templateType: 'TEXT_ONLY',
        rule: null
      },
      {
        id: 913,
        title: 'Конфликт архива',
        description: 'Сервер уже активировал акцию.',
        startsAt: '2030-01-01T00:00:00.000Z',
        endsAt: '2030-02-01T00:00:00.000Z',
        status: 'DRAFT',
        templateType: 'TEXT_ONLY',
        rule: null
      }
    ],
    statusStale: { promotionId: 912, authoritativeStatus: 'ARCHIVED' },
    archiveStale: { promotionId: 913, authoritativeStatus: 'ACTIVE' }
  })

  await page.goto(`?mode=venue&venueId=2#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.locator('.venue-select')).toHaveValue('2')
  await page.getByRole('button', { name: 'Акции', exact: true }).click()
  await expect.poll(() => api.getPromotionListRequests()).toBe(1)

  const statusConflictCard = page.locator('[data-promotion-id="912"]')
  await statusConflictCard.getByRole('button', { name: 'Редактировать', exact: true }).click()
  await expect(page.locator('.venue-promotion-form')).toBeVisible()
  await statusConflictCard.getByRole('button', { name: 'Приостановить', exact: true }).click()
  await expect(
    page.getByText('Статус акции уже изменился. Обновите список и повторите действие.', { exact: true })
  ).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Сервис временно недоступен', exact: true })).toBeVisible()
  await expect.poll(() => api.getPromotionListRequests()).toBe(2)
  expect(api.getLifecycleRequests()).toEqual([
    {
      method: 'POST',
      path: '/api/venue/2/promotions/912/status',
      promotionId: 912,
      body: { status: 'PAUSED' }
    }
  ])
  await expect(page.locator('.venue-promotion-form')).toBeVisible()
  await expect(page.getByText('Акция приостановлена.', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Акция опубликована.', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Акция архивирована.', { exact: true })).toHaveCount(0)
  await expect(page.locator('.venue-select')).toHaveValue('2')
  expect(new URL(page.url()).searchParams.get('venueId')).toBe('2')

  const currentTab = page.getByRole('tab', { name: 'Текущие', exact: true })
  const archivedTab = page.getByRole('tab', { name: 'Архив', exact: true })
  const currentPanel = page.locator('#venue-promotions-current-panel')
  const archivedPanel = page.locator('#venue-promotions-archived-panel')
  await archivedTab.click()
  await expect(archivedTab).toHaveAttribute('aria-selected', 'true')
  await expect(archivedPanel.getByText('Архивных акций пока нет.', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Повторить', exact: true }).click()
  await expect.poll(() => api.getPromotionListRequests()).toBe(3)
  await expect(archivedTab).toHaveAttribute('aria-selected', 'true')
  await expect(archivedPanel).toBeVisible()
  await expect(archivedPanel.locator('[data-group="archived"] [data-promotion-id="912"]')).toBeVisible()
  await expect(currentPanel).toBeHidden()
  await expect(page.locator('.venue-promotion-form')).toBeHidden()
  await expect(
    page.getByText('Статус акции уже изменился. Обновите список и повторите действие.', { exact: true })
  ).toBeVisible()
  expect(api.getLifecycleRequests()).toHaveLength(1)

  await currentTab.click()
  await expect(currentTab).toHaveAttribute('aria-selected', 'true')
  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toBe('Архивировать акцию «Конфликт архива»?')
    await dialog.accept()
  })
  await page
    .locator('[data-promotion-id="913"]')
    .getByRole('button', { name: 'Архивировать', exact: true })
    .click()
  await expect(
    page.getByText('Статус акции уже изменился. Обновите список и повторите действие.', { exact: true })
  ).toBeVisible()
  await expect(currentTab).toHaveAttribute('aria-selected', 'true')
  await expect(currentPanel.locator('[data-group="active"] [data-promotion-id="913"]')).toBeVisible()
  await expect.poll(() => api.getPromotionListRequests()).toBe(4)
  expect(api.getLifecycleRequests()).toEqual([
    {
      method: 'POST',
      path: '/api/venue/2/promotions/912/status',
      promotionId: 912,
      body: { status: 'PAUSED' }
    },
    {
      method: 'DELETE',
      path: '/api/venue/2/promotions/913',
      promotionId: 913
    }
  ])
  await expect(page.getByText('Акция приостановлена.', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Акция опубликована.', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Акция архивирована.', { exact: true })).toHaveCount(0)
  await expect(page.locator('.venue-select')).toHaveValue('2')
  expect(new URL(page.url()).searchParams.get('venueId')).toBe('2')
})

test('archived gift promotion is read only while active and paused readiness stays visible', async ({ page }) => {
  const referenceInstant = '2030-01-10T12:00:00.000Z'
  await page.clock.setFixedTime(referenceInstant)
  await installTelegramWebApp(page, 123456789)
  await mockVenuePromotionsApi(page, {
    role: 'OWNER',
    nowEpochMs: Date.parse(referenceInstant),
    promotions: [
      {
        id: 914,
        title: 'Архивный подарок',
        description: 'Историческое описание подарочной акции.',
        terms: 'Исторические условия.',
        startsAt: '2030-01-01T00:00:00.000Z',
        endsAt: '2030-02-01T00:00:00.000Z',
        status: 'ARCHIVED',
        templateType: 'GIFT_WITH_ITEM',
        rule: null
      },
      {
        id: 915,
        title: 'Активный подарок с проверкой',
        description: 'Требует заполнения правила.',
        startsAt: '2030-01-01T00:00:00.000Z',
        endsAt: '2030-02-01T00:00:00.000Z',
        status: 'ACTIVE',
        templateType: 'GIFT_WITH_ITEM',
        rule: {
          id: 1915,
          version: 1,
          windows: [],
          target: null,
          reward: null,
          readyForActivation: false,
          validationIssues: ['Добавьте расписание, условие покупки и подарок.']
        }
      },
      {
        id: 916,
        title: 'Приостановленный подарок с проверкой',
        description: 'Требует заполнения правила.',
        startsAt: '2030-01-01T00:00:00.000Z',
        endsAt: '2030-02-01T00:00:00.000Z',
        status: 'PAUSED',
        templateType: 'GIFT_WITH_ITEM',
        rule: {
          id: 1916,
          version: 1,
          windows: [],
          target: null,
          reward: null,
          readyForActivation: false,
          validationIssues: ['Добавьте расписание, условие покупки и подарок.']
        }
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()

  const currentTab = page.getByRole('tab', { name: 'Текущие', exact: true })
  const archivedTab = page.getByRole('tab', { name: 'Архив', exact: true })
  const currentPanel = page.locator('#venue-promotions-current-panel')
  const archivedPanel = page.locator('#venue-promotions-archived-panel')
  await expect(currentPanel.locator('[data-promotion-id="914"]')).toHaveCount(0)
  await expect(currentPanel.locator('[data-promotion-id="915"]')).toBeVisible()
  await expect(currentPanel.locator('[data-promotion-id="916"]')).toBeVisible()

  await archivedTab.click()
  const archivedCard = archivedPanel.locator('[data-promotion-id="914"]')
  await expect(archivedCard).toContainText('Архивный подарок')
  await expect(archivedCard).toContainText('Историческое описание подарочной акции.')
  await expect(archivedCard).toContainText('Исторические условия.')
  await expect(archivedCard).toContainText('2030')
  await expect(archivedCard).toContainText('Подарок при покупке')
  await expect(archivedCard).toContainText('Архив')
  await expect(archivedCard).toContainText(
    'Акция находится в архиве. Изменения и повторная публикация недоступны.'
  )
  await expect(archivedCard).not.toContainText('Нужно исправить перед публикацией')
  await expect(archivedCard).not.toContainText('перед публикацией')
  await expect(archivedCard).not.toContainText('не настроен')
  await expect(archivedCard.getByRole('button')).toHaveCount(0)
  await expect(archivedPanel.locator('[data-promotion-id="915"]')).toHaveCount(0)
  await expect(archivedPanel.locator('[data-promotion-id="916"]')).toHaveCount(0)

  await currentTab.click()
  const activeCard = currentPanel.locator('[data-promotion-id="915"]')
  await expect(activeCard).toContainText('Нужно исправить перед публикацией')
  await expect(activeCard).toContainText('Добавьте расписание, условие покупки и подарок.')
  await expect(activeCard.getByRole('button', { name: 'Приостановить', exact: true })).toBeVisible()

  const pausedCard = currentPanel.locator('[data-promotion-id="916"]')
  await expect(pausedCard).toContainText('Нужно исправить перед публикацией')
  await expect(pausedCard).toContainText('Добавьте расписание, условие покупки и подарок.')
  await expect(pausedCard.getByRole('button', { name: 'Опубликовать', exact: true })).toBeVisible()
})

test('venue promotion tabs reset and ignore a disposed late response on venue switch', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenuePromotionsApi(page, {
    role: 'OWNER',
    accessVenueIds: [1, 2],
    promotionsByVenue: {
      1: [
        {
          id: 930,
          title: 'Текущая акция первого заведения',
          description: 'Карточка первого заведения.',
          startsAt: '2030-01-01T00:00:00.000Z',
          endsAt: '2030-02-01T00:00:00.000Z',
          status: 'DRAFT'
        },
        {
          id: 931,
          title: 'Архив первого заведения',
          description: 'Архивная карточка первого заведения.',
          startsAt: '2030-01-01T00:00:00.000Z',
          endsAt: '2030-02-01T00:00:00.000Z',
          status: 'ARCHIVED'
        }
      ],
      2: [
        {
          id: 940,
          title: 'Текущая акция второго заведения',
          description: 'Карточка второго заведения.',
          startsAt: '2030-01-01T00:00:00.000Z',
          endsAt: '2030-02-01T00:00:00.000Z',
          status: 'ACTIVE'
        },
        {
          id: 941,
          title: 'Архив второго заведения',
          description: 'Архивная карточка второго заведения.',
          startsAt: '2030-01-01T00:00:00.000Z',
          endsAt: '2030-02-01T00:00:00.000Z',
          status: 'ARCHIVED'
        }
      ]
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()
  const venueSelect = page.locator('.venue-controls select.venue-select')
  const currentTab = page.getByRole('tab', { name: 'Текущие', exact: true })
  const archivedTab = page.getByRole('tab', { name: 'Архив', exact: true })
  const currentPanel = page.locator('#venue-promotions-current-panel')
  const archivedPanel = page.locator('#venue-promotions-archived-panel')

  await expect(currentPanel.locator('[data-promotion-id="930"]')).toBeVisible()
  await archivedTab.click()
  await expect(archivedPanel.locator('[data-promotion-id="931"]')).toBeVisible()

  await venueSelect.selectOption('2')
  await expect(venueSelect).toHaveValue('2')
  await expect(currentTab).toHaveAttribute('aria-selected', 'true')
  await expect(currentPanel.locator('[data-promotion-id="940"]')).toBeVisible()
  await expect(page.locator('[data-promotion-id="930"]')).toHaveCount(0)
  await expect(page.locator('[data-promotion-id="931"]')).toHaveCount(0)
  await archivedTab.click()
  await expect(archivedPanel.locator('[data-promotion-id="941"]')).toBeVisible()

  const releaseVenueOne = api.deferNextPromotionList(1)
  const venueOneRequestsBefore = api.getPromotionListVenueRequests().filter((id) => id === 1).length
  const venueOneSettledBefore = api.getSettledPromotionListVenues().filter((id) => id === 1).length
  await venueSelect.selectOption('1')
  await expect
    .poll(() => api.getPromotionListVenueRequests().filter((id) => id === 1).length)
    .toBe(venueOneRequestsBefore + 1)
  await expect(currentTab).toHaveAttribute('aria-selected', 'true')
  await expect(currentPanel.getByText('Текущих акций пока нет.', { exact: true })).toBeVisible()
  await expect(page.locator('[data-promotion-id="930"]')).toHaveCount(0)
  await expect(page.locator('[data-promotion-id="931"]')).toHaveCount(0)
  await expect(page.locator('[data-promotion-id="940"]')).toHaveCount(0)
  await expect(page.locator('[data-promotion-id="941"]')).toHaveCount(0)

  await venueSelect.selectOption('2')
  await expect(currentTab).toHaveAttribute('aria-selected', 'true')
  await expect(currentPanel.locator('[data-promotion-id="940"]')).toBeVisible()
  releaseVenueOne()
  await expect
    .poll(() => api.getSettledPromotionListVenues().filter((id) => id === 1).length)
    .toBe(venueOneSettledBefore + 1)
  await expect(venueSelect).toHaveValue('2')
  await expect.poll(() => new URL(page.url()).searchParams.get('venueId')).toBe('2')
  await expect(currentTab).toHaveAttribute('aria-selected', 'true')
  await expect(currentPanel.locator('[data-promotion-id="940"]')).toBeVisible()
  await expect(page.locator('[data-promotion-id="930"]')).toHaveCount(0)
  await expect(page.locator('[data-promotion-id="931"]')).toHaveCount(0)
})

test('venue owner configures happy hours windows and targets while invalid activation is rejected', async ({ page }) => {
  const referenceInstant = '2030-01-10T12:00:00.000Z'
  await page.clock.setFixedTime(referenceInstant)
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenuePromotionsApi(page, {
    role: 'OWNER',
    nowEpochMs: Date.parse(referenceInstant),
    promotions: [
      {
        id: 901,
        title: 'Ненастроенные часы',
        description: 'Черновик без исполняемого правила.',
        terms: null,
        startsAt: '2030-01-10T09:00:00.000Z',
        endsAt: '2030-01-20T09:00:00.000Z',
        status: 'DRAFT',
        templateType: 'HAPPY_HOURS_PERCENT',
        rule: {
          id: 1901,
          version: 1,
          windows: [],
          target: null,
          discountPercent: null,
          readyForActivation: false,
          validationIssues: ['Добавьте временное окно.', 'Выберите категорию или позицию.']
        }
      }
    ],
    menuCategories: [{ id: 20, name: 'Кальяны' }],
    menuItems: [{ id: 200, name: 'Double Apple', categoryId: 20 }]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()

  const incompleteCard = page.locator('.venue-promotion-card').filter({ hasText: 'Ненастроенные часы' })
  await expect(incompleteCard).toContainText('Нужно исправить перед публикацией')
  await incompleteCard.getByRole('button', { name: 'Опубликовать' }).click()
  await expect(page.getByText('Сначала заполните расписание, категорию или позицию и процент скидки.')).toBeVisible()
  expect(api.getPromotions().find((promotion) => promotion.id === 901)?.status).toBe('DRAFT')
  expect(api.getMutations()).toEqual([])

  await page.getByRole('button', { name: 'Создать акцию' }).click()
  const form = page.locator('.venue-promotion-form')
  await form.getByLabel('Тип акции').selectOption('HAPPY_HOURS_PERCENT')
  await expect(form.getByText('Часовой пояс заведения: Europe/Moscow')).toBeVisible()
  await form.getByLabel('Название акции', { exact: true }).fill('Счастливые часы')
  await form.getByLabel('Описание', { exact: true }).fill('Скидка на кальяны днём.')
  await form.getByLabel(/^Условия/).fill('По будням.')
  await form.getByLabel('Начало', { exact: true }).fill('2030-01-10T12:00')
  await form.getByLabel('Окончание', { exact: true }).fill('2030-01-20T12:00')
  await form.getByLabel('Категория или позиция').selectOption('20')
  await form.getByLabel('Скидка, %').fill('50')
  await form.getByLabel('Окончание окна 1').fill('24:00')
  await expect(form.getByLabel('Окончание окна 1')).toHaveValue('24:00')
  await form.getByLabel('Окончание окна 1').fill('18:00')

  await form.getByRole('button', { name: 'Добавить окно' }).click()
  await form.getByLabel('День недели, окно 2').selectOption('1')
  await form.getByLabel('Начало окна 2').fill('17:00')
  await form.getByLabel('Окончание окна 2').fill('19:00')
  await form.getByRole('button', { name: 'Сохранить черновик' }).click()
  await expect(form.getByText('Окна в один день не должны пересекаться: Понедельник.')).toBeVisible()
  expect(api.getMutations()).toEqual([])

  await form.getByLabel('День недели, окно 2').selectOption('2')
  await form.getByLabel('Начало окна 2').fill('12:00')
  await form.getByLabel('Окончание окна 2').fill('18:00')
  const summary = form.locator('.venue-promotion-rule-summary')
  await expect(summary).toContainText('Понедельник–вторник, 12:00–18:00')
  await expect(summary).toContainText('Категория: Кальяны')
  await expect(summary).toContainText('Скидка: 50%')
  await expect(form).toContainText('Скидка рассчитывается автоматически по актуальным ценам при оформлении заказа.')

  await form.getByRole('button', { name: 'Сохранить черновик' }).click()
  await expect(page.getByText('Черновик акции создан.')).toBeVisible()
  const created = api.getPromotions().find((promotion) => promotion.title === 'Счастливые часы')
  expect(created).toMatchObject({
    status: 'DRAFT',
    templateType: 'HAPPY_HOURS_PERCENT',
    rule: {
      version: 1,
      windows: [
        { weekday: 1, startLocal: '12:00', endLocal: '18:00' },
        { weekday: 2, startLocal: '12:00', endLocal: '18:00' }
      ],
      target: { type: 'MENU_CATEGORY', menuCategoryId: 20, label: 'Кальяны' },
      discountPercent: 50,
      readyForActivation: true
    }
  })

  const happyHoursCard = page.locator('.venue-promotion-card', {
    has: page.getByRole('heading', { name: 'Счастливые часы', exact: true })
  })
  await expect(happyHoursCard).toContainText('Понедельник–вторник, 12:00–18:00')
  await expect(happyHoursCard).toContainText('Категория: Кальяны')
  await happyHoursCard.getByRole('button', { name: 'Редактировать' }).click()
  await form.getByLabel('Скидка действует на').selectOption('MENU_ITEM')
  await form.getByLabel('Категория или позиция').selectOption('200')
  await expect(form.locator('.venue-promotion-rule-summary')).toContainText('Позиция: Double Apple')
  await form.getByRole('button', { name: 'Сохранить изменения' }).click()
  await expect(page.getByText('Изменения сохранены.')).toBeVisible()

  const updated = api.getPromotions().find((promotion) => promotion.title === 'Счастливые часы')
  expect(updated).toMatchObject({
    rule: {
      version: 2,
      target: { type: 'MENU_ITEM', menuItemId: 200, label: 'Double Apple' },
      readyForActivation: true
    }
  })
  await page
    .locator('.venue-promotion-card', {
      has: page.getByRole('heading', { name: 'Счастливые часы', exact: true })
    })
    .getByRole('button', { name: 'Опубликовать' })
    .click()
  await expect(page.getByText('Акция опубликована.')).toBeVisible()
  expect(api.getPromotions().find((promotion) => promotion.title === 'Счастливые часы')?.status).toBe('ACTIVE')
  expect(api.getMutations()).toEqual(['create', 'update', 'active'])

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Открыть карточку' }).click()
  await expect(page.locator('.guest-venue-promotions')).toContainText('Счастливые часы')

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()
  await page
    .locator('.venue-promotion-card', {
      has: page.getByRole('heading', { name: 'Счастливые часы', exact: true })
    })
    .getByRole('button', { name: 'Приостановить' })
    .click()
  await expect(page.getByText('Акция приостановлена.')).toBeVisible()
  expect(api.getPromotions().find((promotion) => promotion.title === 'Счастливые часы')?.status).toBe('PAUSED')

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Открыть карточку' }).click()
  await expect(page.locator('.guest-venue-promotions')).toHaveCount(0)
})

test('venue owner creates a fixed gift promotion and edits it to an allowlisted choice', async ({ page }) => {
  const referenceInstant = '2030-01-10T12:00:00.000Z'
  await page.clock.setFixedTime(referenceInstant)
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenuePromotionsApi(page, {
    role: 'OWNER',
    nowEpochMs: Date.parse(referenceInstant),
    menuCategories: [
      { id: 20, name: 'Кальяны' },
      { id: 21, name: 'Напитки' }
    ],
    menuItems: [
      { id: 200, name: 'Double Apple', categoryId: 20 },
      { id: 210, name: 'Чай', categoryId: 21 },
      { id: 211, name: 'Лимонад', categoryId: 21 }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Акции', exact: true }).click()
  await page.getByRole('button', { name: 'Создать акцию' }).click()
  const form = page.locator('.venue-promotion-form')
  await form.getByLabel('Тип акции').selectOption('GIFT_WITH_ITEM')
  await form.getByLabel('Название акции', { exact: true }).fill('Подарок к кальяну')
  await form.getByLabel('Описание', { exact: true }).fill('Чай в подарок при заказе кальяна.')
  await form.getByLabel(/^Условия/).fill('Один подарок на заказ.')
  await form.getByLabel('Начало', { exact: true }).fill('2030-01-10T12:00')
  await form.getByLabel('Окончание', { exact: true }).fill('2030-01-20T12:00')
  await form.getByLabel('Категория или позиция').selectOption('20')
  await form.getByLabel('Подарок', { exact: true }).selectOption('210')

  const summary = form.locator('.venue-promotion-rule-summary')
  await expect(summary).toContainText('При заказе: Категория «Кальяны»')
  await expect(summary).toContainText('Подарок: Чай')
  await expect(summary).toContainText('Максимум: 1 подарок на заказ')
  await expect(form).toContainText(
    'Гость сам выбирает или подтверждает подарок. Подарок автоматически в заказ не добавляется.'
  )
  await form.getByRole('button', { name: 'Сохранить черновик' }).click()
  await expect(page.getByText('Черновик акции создан.')).toBeVisible()

  const fixedPromotion = api.getPromotions().find((promotion) => promotion.title === 'Подарок к кальяну')
  expect(fixedPromotion).toMatchObject({
    status: 'DRAFT',
    templateType: 'GIFT_WITH_ITEM',
    rule: {
      target: { type: 'MENU_CATEGORY', menuCategoryId: 20, label: 'Кальяны' },
      discountPercent: null,
      reward: {
        mode: 'FIXED_ITEM',
        fixedItem: { menuItemId: 210, name: 'Чай' },
        allowlist: []
      },
      readyForActivation: true
    }
  })

  const promotionCard = page.locator('.venue-promotion-card').filter({ hasText: 'Подарок к кальяну' })
  await expect(promotionCard).toContainText('Подарок при покупке')
  await expect(promotionCard).toContainText('Подарок: Чай')
  await promotionCard.getByRole('button', { name: 'Редактировать' }).click()
  await form.getByLabel('Тип подарка').selectOption('CHOICE_ITEMS')
  await form.locator('.venue-promotion-reward-option').filter({ hasText: 'Чай' }).locator('input').check()
  await form.locator('.venue-promotion-reward-option').filter({ hasText: 'Лимонад' }).locator('input').check()
  await expect(form.locator('.venue-promotion-rule-summary')).toContainText('Подарок: на выбор — Чай, Лимонад')
  await form.getByRole('button', { name: 'Сохранить изменения' }).click()
  await expect(page.getByText('Изменения сохранены.')).toBeVisible()

  const selectablePromotion = api.getPromotions().find((promotion) => promotion.title === 'Подарок к кальяну')
  expect(selectablePromotion).toMatchObject({
    rule: {
      version: 2,
      reward: {
        mode: 'CHOICE_ITEMS',
        fixedItem: null,
        allowlist: [
          { menuItemId: 210, name: 'Чай' },
          { menuItemId: 211, name: 'Лимонад' }
        ]
      }
    }
  })
  await expect(page.locator('.venue-promotion-card').filter({ hasText: 'Подарок к кальяну' })).toContainText(
    'Подарок: на выбор — Чай, Лимонад'
  )
})

test('venue owner sees feedback section and staff does not', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueStatsApi(page, { role: 'OWNER', permissions: ['FEEDBACK_VIEW', 'SUPPORT_MANAGE'] })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Отзывы', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Отзывы', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Отзывы', exact: true })).toBeVisible()
  await expect(page.locator('.venue-stats-metric').filter({ hasText: 'Отзывы' })).toContainText('2')
  await expect(page.getByText('Гость 9010')).toBeVisible()
  await expect(page.getByText('Все отлично')).toBeVisible()
  await page.getByRole('button', { name: 'Низкие' }).click()
  await expect(page.getByText('Долго ждали')).toBeVisible()
  await page.getByRole('button', { name: 'Связаться с гостем' }).click()
  await expect.poll(() => api.getFollowUpCalls()).toBe(1)
  await expect(page.getByRole('heading', { name: 'Сообщения' })).toBeVisible()
  const messageDetail = page.locator('.venue-messages-detail')
  await expect(messageDetail).toContainText('Отзыв после визита')
  await expect(messageDetail).toContainText('Оценка: 2/5')
  await expect(messageDetail).toContainText('Комментарий: Долго ждали')
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
  await expect(page.getByRole('button', { name: 'Отзывы', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    window.location.hash = '#/feedback'
  })
  await expect(page.getByRole('heading', { name: 'Недостаточно прав' })).toBeVisible()
})

test('venue staff does not see or open promotions section', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenuePromotionsApi(page, { role: 'STAFF' })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Акции', exact: true })).toHaveCount(0)
  await page.evaluate(() => {
    window.location.hash = '#/promotions'
  })
  await expect(page.getByRole('heading', { name: 'Недостаточно прав' })).toBeVisible()
  await expect(page.getByRole('tablist', { name: 'Списки акций', exact: true })).toHaveCount(0)
})

test('venue manager can create promotions from the management section', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenuePromotionsApi(page, { role: 'MANAGER' })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByRole('button', { name: 'Акции', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Акции', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Акции', exact: true })).toBeVisible()
  const currentTab = page.getByRole('tab', { name: 'Текущие', exact: true })
  const archivedTab = page.getByRole('tab', { name: 'Архив', exact: true })
  await archivedTab.click()
  await expect(archivedTab).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('button', { name: 'Создать акцию' }).click()
  const form = page.locator('.venue-promotion-form')
  await form.getByLabel('Название акции', { exact: true }).fill('Акция менеджера')
  await form.getByLabel('Описание', { exact: true }).fill('Информационное предложение.')
  await form.getByLabel('Начало', { exact: true }).fill('2030-05-10T18:00')
  await form.getByLabel('Окончание', { exact: true }).fill('2030-05-10T22:00')
  await form.getByRole('button', { name: 'Сохранить черновик' }).click()
  await expect(page.getByText('Черновик акции создан.')).toBeVisible()
  await expect(archivedTab).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByText('Архивных акций пока нет.', { exact: true })).toBeVisible()
  await currentTab.click()
  await expect(page.locator('[data-promotion-id="1"]')).toContainText('Акция менеджера')
  expect(api.getMutations()).toEqual(['create'])
})

test('venue menu bootstraps an empty venue before the first authoritative display', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuApi(page, {
    role: 'MANAGER',
    categories: [],
    bootstrapSeedsDefaults: true
  })
  const releaseBootstrap = api.deferNextMenuBootstrap()

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.evaluate(() => {
    document.body.dataset.menuEmptySeen = 'false'
    const observer = new MutationObserver((records) => {
      const emptyWasAdded = records.some((record) =>
        Array.from(record.addedNodes).some((node) => {
          if (!(node instanceof Element)) return false
          const candidates = [
            ...(node.matches('.venue-empty') ? [node] : []),
            ...Array.from(node.querySelectorAll('.venue-empty'))
          ]
          return candidates.some((candidate) => candidate.textContent?.trim() === 'Категории не найдены.')
        })
      )
      if (emptyWasAdded) document.body.dataset.menuEmptySeen = 'true'
    })
    observer.observe(document.body, { childList: true, subtree: true })
  })

  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  await expect.poll(() => api.getBootstrapCalls()).toBe(1)
  expect(api.getMenuCalls()).toBe(0)
  await expect(page.locator('.venue-menu-category-title')).toHaveCount(0)
  await expect(page.getByText('Категории не найдены.', { exact: true })).toHaveCount(0)

  releaseBootstrap()

  await expect(page.locator('.venue-menu-category-title')).toHaveText([
    'Кальянное меню',
    'Напитки',
    'Кухня'
  ])
  expect(api.getMenuCalls()).toBe(1)
  expect(
    api.getCategories().map(({ name, sortOrder, categoryType, items }) => ({
      name,
      sortOrder,
      categoryType,
      itemCount: items.length
    }))
  ).toEqual([
    { name: 'Кальянное меню', sortOrder: 0, categoryType: 'OTHER', itemCount: 0 },
    { name: 'Напитки', sortOrder: 1, categoryType: 'OTHER', itemCount: 0 },
    { name: 'Кухня', sortOrder: 2, categoryType: 'OTHER', itemCount: 0 }
  ])
  await expect(page.locator('body')).toHaveAttribute('data-menu-empty-seen', 'false')
})

test('venue menu repeats bootstrap on a new screen mount without duplicate categories', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuApi(page, { categories: [], bootstrapSeedsDefaults: true })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  await expect(page.locator('.venue-menu-category-title')).toHaveText([
    'Кальянное меню',
    'Напитки',
    'Кухня'
  ])

  await page.getByRole('button', { name: 'Обзор', exact: true }).click()
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  await expect.poll(() => api.getBootstrapCalls()).toBe(2)
  await expect.poll(() => api.getMenuCalls()).toBe(2)
  await expect(page.locator('.venue-menu-category-title')).toHaveText([
    'Кальянное меню',
    'Напитки',
    'Кухня'
  ])
  expect(api.getCategories().map((category) => category.name)).toEqual([
    'Кальянное меню',
    'Напитки',
    'Кухня'
  ])
  expect(new Set(api.getCategories().map((category) => category.name)).size).toBe(3)
})

test('venue menu bootstrap preserves a partial custom menu and appends only missing defaults', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const categories: VenueMenuCategoryFixture[] = [
    {
      id: 71,
      name: 'Авторское меню',
      sortOrder: 4,
      categoryType: 'FOOD',
      items: []
    },
    {
      id: 72,
      name: 'Напитки',
      sortOrder: 9,
      categoryType: 'DRINK',
      items: []
    }
  ]
  const existingSnapshot = JSON.parse(JSON.stringify(categories)) as VenueMenuCategoryFixture[]
  const api = await mockVenueMenuApi(page, { categories, bootstrapSeedsDefaults: true })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  await expect(page.locator('.venue-menu-category-title')).toHaveText([
    'Авторское меню',
    'Напитки',
    'Кальянное меню',
    'Кухня'
  ])
  expect(api.getCategories().slice(0, 2)).toEqual(existingSnapshot)
  expect(
    api.getCategories().slice(2).map(({ name, sortOrder, categoryType, items }) => ({
      name,
      sortOrder,
      categoryType,
      itemCount: items.length
    }))
  ).toEqual([
    { name: 'Кальянное меню', sortOrder: 10, categoryType: 'OTHER', itemCount: 0 },
    { name: 'Кухня', sortOrder: 11, categoryType: 'OTHER', itemCount: 0 }
  ])
})

test('venue menu keeps bootstrap failure retryable without a false empty-menu success', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuApi(page, {
    categories: [],
    bootstrapSeedsDefaults: true,
    bootstrapErrors: [
      { status: 503, code: 'DATABASE_UNAVAILABLE', message: 'private database detail' }
    ],
    menuErrors: [
      { status: 503, code: 'DATABASE_UNAVAILABLE', message: 'private menu read detail' }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  const errorCard = page.locator('.venue-menu-builder > .error-card')
  await expect(errorCard.getByRole('heading', { name: 'Сервис временно недоступен' })).toBeVisible()
  await expect(errorCard.getByText('Попробуйте чуть позже.', { exact: true })).toBeVisible()
  await expect(errorCard).not.toContainText('private database detail')
  await expect(page.getByText('Категории не найдены.', { exact: true })).toHaveCount(0)
  expect(api.getBootstrapCalls()).toBe(1)
  expect(api.getMenuCalls()).toBe(0)

  await errorCard.getByRole('button', { name: 'Повторить', exact: true }).click()

  await expect.poll(() => api.getBootstrapCalls()).toBe(2)
  await expect.poll(() => api.getMenuCalls()).toBe(1)
  await expect(errorCard.getByRole('heading', { name: 'Сервис временно недоступен' })).toBeVisible()
  await expect(errorCard).not.toContainText('private menu read detail')
  await expect(page.getByText('Категории не найдены.', { exact: true })).toHaveCount(0)

  await errorCard.getByRole('button', { name: 'Повторить', exact: true }).click()

  expect(api.getBootstrapCalls()).toBe(2)
  await expect.poll(() => api.getMenuCalls()).toBe(2)
  await expect(page.locator('.venue-menu-category-title')).toHaveText([
    'Кальянное меню',
    'Напитки',
    'Кухня'
  ])
  await expect(errorCard).toBeHidden()
})

test('venue menu ignores a delayed bootstrap response after a venue switch', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const permissions = ['MENU_VIEW', 'MENU_MANAGE', 'MENU_AVAILABILITY_MANAGE']
  const api = await mockVenueMenuShiftCheckApi(page, {
    bootstrapSeedsDefaults: true,
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER',
        permissions,
        categories: [
          { id: 70, name: 'Только старое заведение', sortOrder: 0, categoryType: 'OTHER', items: [] }
        ]
      },
      {
        venueId: 2,
        venueName: 'Дым',
        venueCity: 'Казань',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER',
        permissions,
        categories: [
          { id: 80, name: 'Только новое заведение', sortOrder: 0, categoryType: 'OTHER', items: [] }
        ]
      }
    ]
  })
  const releaseOldBootstrap = api.deferNextMenuBootstrap(1)

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  await expect.poll(() => api.getBootstrapRequests()).toEqual([1])
  const venueSelect = page.locator('.venue-controls select.venue-select')

  await venueSelect.selectOption('2')

  await expect.poll(() => api.getBootstrapRequests()).toEqual([1, 2])
  await expect(page.locator('.venue-menu-category-title')).toHaveText([
    'Только новое заведение',
    'Кальянное меню',
    'Напитки',
    'Кухня'
  ])
  await expect(page.getByText('Только старое заведение', { exact: true })).toHaveCount(0)
  expect(api.getMenuRequests()).toEqual([2])

  releaseOldBootstrap()
  await expect
    .poll(() => api.getSettledBootstrapRequests().filter((venueId) => venueId === 1).length)
    .toBe(1)
  await expect(venueSelect).toHaveValue('2')
  await expect(page.locator('.venue-menu-category-title')).toHaveText([
    'Только новое заведение',
    'Кальянное меню',
    'Напитки',
    'Кухня'
  ])
  await expect(page.getByText('Только старое заведение', { exact: true })).toHaveCount(0)
  expect(api.getMenuRequests()).toEqual([2])
})

test('venue menu ignores a delayed bootstrap response after an account replacement', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuApi(page, {
    categories: [
      { id: 70, name: 'Только старый аккаунт', sortOrder: 0, categoryType: 'OTHER', items: [] }
    ],
    otherAccountCategories: [
      { id: 80, name: 'Только новый аккаунт', sortOrder: 0, categoryType: 'OTHER', items: [] }
    ],
    bootstrapSeedsDefaults: true
  })
  const releaseOldBootstrap = api.deferNextMenuBootstrap()

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  await expect.poll(() => api.getBootstrapCalls()).toBe(1)

  await page.evaluate(
    ({ userId, initData }) => {
      window.localStorage.setItem('__e2e_telegram_user_id', String(userId))
      window.localStorage.setItem('__e2e_telegram_init_data', initData)
    },
    { userId: 987654321, initData: otherMockInitData }
  )
  await page.goto(`?mode=venue&smokeUser=other#tgWebAppData=${encodeURIComponent(otherMockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  await expect.poll(() => api.getBootstrapCalls()).toBe(2)
  await expect(page.locator('.venue-menu-category-title')).toHaveText([
    'Только новый аккаунт',
    'Кальянное меню',
    'Напитки',
    'Кухня'
  ])
  await expect(page.getByText('Только старый аккаунт', { exact: true })).toHaveCount(0)
  expect(api.getMenuCalls()).toBe(1)

  const settledBeforeRelease = api.getSettledBootstrapCalls()
  releaseOldBootstrap()
  await expect.poll(() => api.getSettledBootstrapCalls()).toBe(settledBeforeRelease + 1)
  await expect(page.locator('.venue-menu-category-title')).toHaveText([
    'Только новый аккаунт',
    'Кальянное меню',
    'Напитки',
    'Кухня'
  ])
  await expect(page.getByText('Только старый аккаунт', { exact: true })).toHaveCount(0)
  expect(api.getMenuCalls()).toBe(1)
})

test('venue staff reads the menu without sending the bootstrap mutation', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuApi(page, {
    role: 'STAFF',
    permissions: ['MENU_VIEW', 'MENU_AVAILABILITY_MANAGE'],
    categories: buildDefaultVenueMenu(),
    bootstrapSeedsDefaults: true
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  await expect(page.locator('.venue-menu-category-title')).toHaveText(['Кальянное меню'])
  expect(api.getBootstrapCalls()).toBe(0)
  expect(api.getMenuCalls()).toBe(1)
  await expect(page.getByRole('button', { name: 'Добавить категорию' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Добавить позицию' })).toHaveCount(0)
})

test('venue manager drafts cancels and atomically confirms one menu shift check batch', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuShiftCheckApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER',
        permissions: ['MENU_VIEW', 'MENU_MANAGE', 'MENU_AVAILABILITY_MANAGE', 'MENU_SHIFT_CHECK'],
        categories: buildMenuShiftCheckFixture()
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  const editing = page.locator('details.venue-menu-editing')
  const shiftCheck = page.locator('details.venue-menu-shift-check')
  const editingCategory = (categoryId: number) =>
    editing.locator(`details.venue-menu-category[data-category-id="${categoryId}"]`)
  const search = shiftCheck.getByRole('searchbox', { name: 'Поиск по позициям и опциям' })
  const itemRow = (itemId: number) =>
    shiftCheck.locator(`.venue-shift-check-item[data-item-id="${itemId}"]`)
  const optionRow = (optionId: number) =>
    shiftCheck.locator(`.venue-shift-check-option[data-option-id="${optionId}"]`)
  const category = (categoryId: number) =>
    shiftCheck.locator(`.venue-shift-check-category[data-category-id="${categoryId}"]`)
  const itemGroup = (itemId: number) => itemRow(itemId).locator('..')
  const itemAvailability = (itemId: number, name: string) =>
    itemRow(itemId).getByRole('switch', { name: new RegExp(`Позиция ${name}:`) })
  const optionAvailability = (optionId: number, name: string) =>
    optionRow(optionId).getByRole('switch', { name: new RegExp(`Опция ${name}:`) })
  const confirmationGroups = shiftCheck.locator('.venue-shift-check-confirmation-grid > div')
  const prepareDraft = async () => {
    await itemAvailability(310, 'Кальян Ягодный').click()
    await optionAvailability(401, 'Яблоко').click()
    await category(31).getByRole('button', { name: 'Все позиции недоступны' }).click()
    await itemGroup(320).getByRole('button', { name: 'Все опции позиции в наличии' }).click()
    await shiftCheck.getByRole('button', { name: 'Массовое изменение' }).click()
    await search.fill('Кальян Классический')
    await shiftCheck.getByRole('button', { name: 'Выбрать все отфильтрованные' }).click()
    await expect(itemRow(311).getByRole('checkbox', { name: 'Выбрать Кальян Классический' })).toBeChecked()
    await expect(shiftCheck.getByText('Выбрано: 2', { exact: true })).toBeVisible()
    await shiftCheck.getByRole('button', { name: 'Сделать доступными' }).click()
    await search.fill('')
  }

  await expect(editing).toBeVisible()
  await expect(shiftCheck).toBeVisible()
  await expect(editing).not.toHaveAttribute('open', '')
  await expect(shiftCheck).not.toHaveAttribute('open', '')
  await expect(editing.locator(':scope > summary')).toContainText('Редактирование меню')
  await expect(shiftCheck.locator(':scope > summary')).toContainText('Проверка меню перед сменой')
  await expect(editing.locator(':scope > summary')).toContainText('Категории: 2')
  await expect(editing.locator(':scope > summary')).toContainText('Позиции: 4')
  await expect(shiftCheck.locator(':scope > summary')).toContainText('Позиции: 3/4')
  await expect(shiftCheck.locator(':scope > summary')).toContainText('Опции: 2/4')
  await expect(
    editing.getByText('Категории, позиции, цены, опции и топ-лист.', { exact: true })
  ).toBeVisible()
  await expect(
    shiftCheck.getByText(
      'Проверьте наличие позиций и вариантов. Изменения применятся только после подтверждения.',
      { exact: true }
    )
  ).toBeVisible()

  await editing.locator(':scope > summary').click()
  await expect(editing).toHaveAttribute('open', '')
  await expect(shiftCheck).not.toHaveAttribute('open', '')
  await expect(editing.locator('.venue-menu-create-category')).toBeHidden()
  await expect(editingCategory(30)).toBeVisible()
  await expect(editingCategory(30)).not.toHaveAttribute('open', '')
  await editingCategory(30).locator(':scope > summary').click()
  await expect(editingCategory(30)).toHaveAttribute('open', '')
  await expect(editingCategory(30).locator('.venue-menu-item')).toHaveCount(2)
  await editing.getByRole('button', { name: 'Добавить категорию' }).click()
  await expect(editing.locator('.venue-menu-create-category')).toBeVisible()
  await editing.getByRole('button', { name: 'Добавить категорию' }).click()
  await expect(editing.locator('.venue-menu-create-category')).toBeHidden()

  await shiftCheck.locator(':scope > summary').click()
  await expect(shiftCheck).toHaveAttribute('open', '')
  await expect(editing).not.toHaveAttribute('open', '')
  await expect(category(30)).toContainText('Позиции: 1/2 · Опции: 2/3')
  await expect(category(31)).toContainText('Позиции: 2/2 · Опции: 0/1')
  await expect(category(30).getByRole('button', { name: 'Все позиции в наличии' })).toBeVisible()
  await expect(category(30).getByRole('button', { name: 'Все позиции недоступны' })).toBeVisible()
  await expect(category(30).getByRole('button', { name: 'Все опции в наличии' })).toBeVisible()
  await expect(category(30).getByRole('button', { name: 'Все опции недоступны' })).toBeVisible()
  await expect(shiftCheck.getByRole('checkbox', { name: /^Выбрать / })).toHaveCount(0)
  await expect(
    shiftCheck.getByRole('switch', { name: 'Позиция Кальян Ягодный: В наличии' })
  ).toBeVisible()
  await expect(
    shiftCheck.getByRole('switch', { name: 'Опция Яблоко: В наличии' })
  ).toBeVisible()
  await expect(itemAvailability(310, 'Кальян Ягодный')).toHaveText('В наличии')
  await expect(optionAvailability(401, 'Яблоко')).toHaveText('В наличии')
  await expect(itemGroup(310).locator('.venue-shift-check-options')).toContainText('Яблоко')
  const itemBox = await itemRow(310).boundingBox()
  const optionBox = await optionRow(401).boundingBox()
  expect(itemBox).not.toBeNull()
  expect(optionBox).not.toBeNull()
  expect(optionBox!.x).toBeGreaterThan(itemBox!.x)

  await category(30).getByRole('button', { name: 'Все опции недоступны' }).click()
  await expect(
    shiftCheck.getByRole('switch', { name: 'Опция Яблоко: Нет в наличии' })
  ).toBeVisible()
  await expect(optionRow(401).getByText('Изменено', { exact: true })).toBeVisible()
  await expect(category(30).getByRole('button', { name: 'Все опции недоступны' })).toBeFocused()
  expect(api.getShiftCheckRequests()).toHaveLength(0)
  expect(api.getOptionAvailabilityCalls()).toBe(0)
  await shiftCheck.getByRole('button', { name: 'Отменить изменения' }).click()
  await expect(
    shiftCheck.getByRole('switch', { name: 'Опция Яблоко: В наличии' })
  ).toBeVisible()

  await itemAvailability(310, 'Кальян Ягодный').click()
  expect(api.getShiftCheckRequests()).toHaveLength(0)
  expect(api.getItemAvailabilityCalls()).toBe(0)
  await expect(itemRow(310).getByText('Изменено', { exact: true })).toBeVisible()
  await expect(
    shiftCheck.getByRole('switch', { name: 'Позиция Кальян Ягодный: Нет в наличии' })
  ).toBeFocused()
  await expect(shiftCheck.getByText('Несохранённые изменения: 1', { exact: true })).toBeVisible()
  await editing.locator(':scope > summary').click()
  await expect(editing).toHaveAttribute('open', '')
  await expect(shiftCheck).not.toHaveAttribute('open', '')
  await expect(shiftCheck.getByText('Несохранённые изменения: 1', { exact: true })).toBeVisible()
  await shiftCheck.locator(':scope > summary').click()
  await expect(editing).not.toHaveAttribute('open', '')
  await expect(itemAvailability(310, 'Кальян Ягодный')).toHaveAttribute('aria-checked', 'false')
  await expect(itemRow(310).getByText('Изменено', { exact: true })).toBeVisible()
  await itemAvailability(310, 'Кальян Ягодный').click()
  await expect(itemRow(310).getByText('Изменено', { exact: true })).toHaveCount(0)

  await search.fill('Мята')
  await expect(optionRow(402)).toBeVisible()
  await expect(optionRow(401)).toHaveCount(0)
  await expect(itemRow(310)).toHaveCount(0)
  await search.fill('')
  await shiftCheck.getByRole('button', { name: 'Нет в наличии', exact: true }).click()
  await expect(itemRow(311)).toBeVisible()
  await expect(optionRow(402)).toBeVisible()
  await expect(optionRow(410)).toBeVisible()
  await expect(optionRow(401)).toHaveCount(0)
  await shiftCheck.getByRole('button', { name: 'Все', exact: true }).click()

  await prepareDraft()
  await expect(itemRow(311).getByRole('checkbox', { name: 'Выбрать Кальян Классический' })).toBeVisible()
  await expect(itemAvailability(311, 'Кальян Классический')).toBeVisible()
  await expect(itemRow(311)).toHaveAttribute('data-selected', 'true')
  await expect
    .poll(() => itemRow(311).evaluate((node) => getComputedStyle(node).outlineStyle))
    .toBe('solid')
  await expect(shiftCheck.getByRole('button', { name: 'Снять выбор' })).toBeVisible()
  await expect(shiftCheck.getByRole('button', { name: 'Выйти из массового режима' })).toBeVisible()
  expect(api.getShiftCheckRequests()).toHaveLength(0)
  expect(api.getItemAvailabilityCalls()).toBe(0)
  expect(api.getOptionAvailabilityCalls()).toBe(0)
  await shiftCheck.getByRole('button', { name: 'Снять выбор' }).click()
  await expect(shiftCheck.getByText('Выбрано: 0', { exact: true })).toBeVisible()
  await shiftCheck.getByRole('button', { name: 'Выйти из массового режима' }).click()
  await expect(shiftCheck.getByRole('checkbox', { name: /^Выбрать / })).toHaveCount(0)
  await expect(shiftCheck.getByRole('button', { name: 'Массовое изменение' })).toBeVisible()
  await expect(category(30)).toContainText('Позиции: 1/2 · Опции: 1/3')
  await expect(category(31)).toContainText('Позиции: 0/2 · Опции: 1/1')
  await expect(confirmationGroups.nth(0)).toContainText('Доступны: 1')
  await expect(confirmationGroups.nth(0)).toContainText('Недоступны: 3')
  await expect(confirmationGroups.nth(1)).toContainText('Доступны: 1')
  await expect(confirmationGroups.nth(1)).toContainText('Недоступны: 1')

  await shiftCheck.getByRole('button', { name: 'Есть несохранённые изменения', exact: true }).click()
  await expect(itemRow(310)).toBeVisible()
  await expect(itemRow(311)).toBeVisible()
  await expect(itemRow(320)).toBeVisible()
  await expect(itemRow(321)).toBeVisible()
  await expect(optionRow(401)).toBeVisible()
  await expect(optionRow(410)).toBeVisible()
  await expect(optionRow(402)).toHaveCount(0)
  await shiftCheck.getByRole('button', { name: 'Отменить изменения' }).click()

  expect(api.getShiftCheckRequests()).toHaveLength(0)
  await expect(shiftCheck.getByText('Несохранённые изменения отменены.', { exact: true })).toBeVisible()
  await shiftCheck.getByRole('button', { name: 'Все', exact: true }).click()
  await expect(shiftCheck.getByRole('checkbox', { name: /^Выбрать / })).toHaveCount(0)
  await expect(itemAvailability(310, 'Кальян Ягодный')).toHaveAttribute('aria-checked', 'true')
  await expect(itemAvailability(311, 'Кальян Классический')).toHaveAttribute('aria-checked', 'false')
  await expect(optionAvailability(401, 'Яблоко')).toHaveAttribute('aria-checked', 'true')
  await expect(optionAvailability(410, 'Большой чайник')).toHaveAttribute('aria-checked', 'false')
  await expect(confirmationGroups.nth(0)).toContainText('Доступны: 0')
  await expect(confirmationGroups.nth(0)).toContainText('Недоступны: 0')
  await expect(confirmationGroups.nth(1)).toContainText('Доступны: 0')
  await expect(confirmationGroups.nth(1)).toContainText('Недоступны: 0')

  await prepareDraft()
  await shiftCheck.getByRole('button', { name: 'Подтвердить проверку' }).click()
  await expect.poll(() => api.getShiftCheckRequests()).toHaveLength(1)
  expect(api.getShiftCheckRequests()).toEqual([
    {
      venueId: 1,
      body: {
        items: [
          { itemId: 310, expectedIsAvailable: true, desiredIsAvailable: false },
          { itemId: 311, expectedIsAvailable: false, desiredIsAvailable: true },
          { itemId: 320, expectedIsAvailable: true, desiredIsAvailable: false },
          { itemId: 321, expectedIsAvailable: true, desiredIsAvailable: false }
        ],
        options: [
          { optionId: 401, itemId: 310, expectedIsAvailable: true, desiredIsAvailable: false },
          { optionId: 410, itemId: 320, expectedIsAvailable: false, desiredIsAvailable: true }
        ]
      }
    }
  ])
  await expect(
    shiftCheck.getByText('Проверка меню завершена. Изменено позиций: 4, опций: 2.', { exact: true })
  ).toBeVisible()
  await expect(itemAvailability(310, 'Кальян Ягодный')).toHaveAttribute('aria-checked', 'false')
  await expect(itemAvailability(311, 'Кальян Классический')).toHaveAttribute('aria-checked', 'true')
  await expect(optionAvailability(401, 'Яблоко')).toHaveAttribute('aria-checked', 'false')
  await expect(optionAvailability(410, 'Большой чайник')).toHaveAttribute('aria-checked', 'true')
  expect(api.getShiftCheckRequests()).toHaveLength(1)
  expect(api.getItemAvailabilityCalls()).toBe(0)
  expect(api.getOptionAvailabilityCalls()).toBe(0)
})

test('venue owner can complete a no-op menu shift check with one empty batch', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuShiftCheckApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'OWNER',
        permissions: ['MENU_VIEW', 'MENU_MANAGE', 'MENU_AVAILABILITY_MANAGE', 'MENU_SHIFT_CHECK'],
        categories: buildMenuShiftCheckFixture()
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const shiftCheck = page.locator('details.venue-menu-shift-check')
  await shiftCheck.locator(':scope > summary').click()
  await shiftCheck.getByRole('button', { name: 'Подтвердить проверку' }).click()

  await expect.poll(() => api.getShiftCheckRequests()).toHaveLength(1)
  expect(api.getShiftCheckRequests()).toEqual([
    {
      venueId: 1,
      body: {
        items: [],
        options: []
      }
    }
  ])
  await expect(
    shiftCheck.getByText('Проверка меню завершена. Изменено позиций: 0, опций: 0.', { exact: true })
  ).toBeVisible()
})

test('menu shift check rebases a stale draft and keeps a failed confirmation retryable', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuShiftCheckApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER',
        permissions: ['MENU_VIEW', 'MENU_MANAGE', 'MENU_AVAILABILITY_MANAGE', 'MENU_SHIFT_CHECK'],
        categories: buildMenuShiftCheckFixture()
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const shiftCheck = page.locator('details.venue-menu-shift-check')
  const errorCard = page.locator('.venue-menu-builder > .error-card')
  const berrySwitch = () =>
    shiftCheck.getByRole('switch', { name: /Позиция Кальян Ягодный:/ })
  await shiftCheck.locator(':scope > summary').click()
  await berrySwitch().click()

  api.queueShiftCheckError({
    status: 409,
    code: 'MENU_SHIFT_CHECK_STALE',
    message: 'Меню изменилось. Обновите проверку и повторите подтверждение.'
  })
  await shiftCheck.getByRole('button', { name: 'Подтвердить проверку' }).click()
  await expect.poll(() => api.getShiftCheckRequests()).toHaveLength(1)
  await expect(
    errorCard.getByText('Меню изменилось. Обновите проверку и повторите подтверждение.', { exact: true })
  ).toBeVisible()
  await expect(berrySwitch()).toHaveAttribute('aria-checked', 'false')
  await expect(berrySwitch()).toBeDisabled()

  api.setOptionAvailability(1, 402, true)
  await errorCard.getByRole('button', { name: 'Обновить проверку' }).click()
  await expect(
    shiftCheck.getByText('Проверка обновлена. Проверьте изменения и подтвердите ещё раз.', { exact: true })
  ).toBeVisible()
  await expect(berrySwitch()).toHaveAttribute('aria-checked', 'false')
  await expect(berrySwitch()).toBeEnabled()
  await expect(shiftCheck.getByRole('switch', { name: /Опция Мята:/ })).toHaveAttribute(
    'aria-checked',
    'true'
  )

  api.queueShiftCheckError({
    status: 500,
    code: 'INTERNAL_ERROR',
    message: 'database details must stay private'
  })
  await shiftCheck.getByRole('button', { name: 'Подтвердить проверку' }).click()
  await expect.poll(() => api.getShiftCheckRequests()).toHaveLength(2)
  await expect(errorCard.getByText('Не удалось завершить проверку меню.', { exact: true })).toBeVisible()
  await expect(errorCard).not.toContainText('database details must stay private')
  await expect(berrySwitch()).toHaveAttribute('aria-checked', 'false')
  await expect(shiftCheck.getByRole('button', { name: 'Подтвердить проверку' })).toBeEnabled()

  await errorCard.getByRole('button', { name: 'Повторить' }).click()
  await expect.poll(() => api.getShiftCheckRequests()).toHaveLength(3)
  await expect(
    shiftCheck.getByText('Проверка меню завершена. Изменено позиций: 1, опций: 0.', { exact: true })
  ).toBeVisible()
  expect(api.getShiftCheckRequests().map((request) => request.body)).toEqual([
    {
      items: [{ itemId: 310, expectedIsAvailable: true, desiredIsAvailable: false }],
      options: []
    },
    {
      items: [{ itemId: 310, expectedIsAvailable: true, desiredIsAvailable: false }],
      options: []
    },
    {
      items: [{ itemId: 310, expectedIsAvailable: true, desiredIsAvailable: false }],
      options: []
    }
  ])
  expect(api.getCategories()[0].items[0].isAvailable).toBe(false)
})

test('menu shift check clears venue drafts and ignores a disposed late menu response', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const permissions = ['MENU_VIEW', 'MENU_MANAGE', 'MENU_AVAILABILITY_MANAGE', 'MENU_SHIFT_CHECK']
  const api = await mockVenueMenuShiftCheckApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER',
        permissions,
        categories: buildMenuShiftCheckFixture()
      },
      {
        venueId: 2,
        venueName: 'Дым',
        venueCity: 'Казань',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER',
        permissions,
        categories: buildMenuShiftCheckFixture(1000, ' Второй')
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  let editing = page.locator('details.venue-menu-editing')
  let shiftCheck = page.locator('details.venue-menu-shift-check')
  const venueSelect = page.locator('.venue-controls select.venue-select')
  await shiftCheck.locator(':scope > summary').click()
  await shiftCheck.getByRole('switch', { name: /Позиция Кальян Ягодный:/ }).click()
  await shiftCheck.getByRole('button', { name: 'Массовое изменение' }).click()
  await shiftCheck.getByRole('checkbox', { name: 'Выбрать Кальян Ягодный' }).check()
  await expect(shiftCheck.locator('.menu-item-badge').filter({ hasText: 'Изменено' })).toHaveCount(1)
  await expect(shiftCheck.getByRole('checkbox', { name: 'Выбрать Кальян Ягодный' })).toBeChecked()

  await venueSelect.selectOption('2')
  editing = page.locator('details.venue-menu-editing')
  shiftCheck = page.locator('details.venue-menu-shift-check')
  await expect(editing).not.toHaveAttribute('open', '')
  await expect(shiftCheck).not.toHaveAttribute('open', '')
  await shiftCheck.locator(':scope > summary').click()
  await expect(shiftCheck.getByRole('switch', { name: /Позиция Кальян Ягодный Второй:/ })).toHaveAttribute(
    'aria-checked',
    'true'
  )
  await expect(shiftCheck.locator('.menu-item-badge').filter({ hasText: 'Изменено' })).toHaveCount(0)
  await shiftCheck.getByRole('button', { name: 'Массовое изменение' }).click()
  await expect(
    shiftCheck.getByRole('checkbox', { name: 'Выбрать Кальян Ягодный Второй' })
  ).not.toBeChecked()
  await shiftCheck.getByRole('button', { name: 'Подтвердить проверку' }).click()
  await expect.poll(() => api.getShiftCheckRequests()).toHaveLength(1)
  expect(api.getShiftCheckRequests()[0]).toEqual({
    venueId: 2,
    body: { items: [], options: [] }
  })

  const releaseVenueOne = api.deferNextMenuLoad(1)
  const venueOneRequestsBefore = api.getMenuRequests().filter((venueId) => venueId === 1).length
  await venueSelect.selectOption('1')
  await expect
    .poll(() => api.getMenuRequests().filter((venueId) => venueId === 1).length)
    .toBe(venueOneRequestsBefore + 1)
  await venueSelect.selectOption('2')
  editing = page.locator('details.venue-menu-editing')
  shiftCheck = page.locator('details.venue-menu-shift-check')
  await expect(editing).not.toHaveAttribute('open', '')
  await expect(shiftCheck).not.toHaveAttribute('open', '')
  await editing.locator(':scope > summary').click()
  const secondCategory = editing.locator('details.venue-menu-category[data-category-id="1030"]')
  await secondCategory.locator(':scope > summary').click()
  const secondVenueItem = page.locator('.venue-menu-item').filter({ hasText: 'Кальян Ягодный Второй' })
  await expect(secondVenueItem).toBeVisible()
  releaseVenueOne()

  await expect(venueSelect).toHaveValue('2')
  await expect(secondVenueItem).toBeVisible()
  await expect(page.locator('.venue-menu-item').filter({ hasText: /^Кальян Ягодный$/ })).toHaveCount(0)
  expect(api.getShiftCheckRequests()).toHaveLength(1)
})

test('venue menu isolates form scroll focus and success across a venue switch', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const permissions = ['MENU_VIEW', 'MENU_MANAGE', 'MENU_AVAILABILITY_MANAGE']
  const api = await mockVenueMenuShiftCheckApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER',
        permissions,
        categories: buildMenuShiftCheckFixture()
      },
      {
        venueId: 2,
        venueName: 'Дым',
        venueCity: 'Казань',
        venueStatus: 'PUBLISHED',
        role: 'MANAGER',
        permissions,
        categories: buildMenuShiftCheckFixture(1000, ' Второй')
      }
    ]
  })

  await page.setViewportSize({ width: 390, height: 600 })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const venueSelect = page.locator('.venue-controls select.venue-select')
  const editing = page.locator('details.venue-menu-editing')
  await editing.locator(':scope > summary').click()
  const category = editing.locator('details.venue-menu-category[data-category-id="30"]')
  await category.locator(':scope > summary[data-menu-category-summary="30"]').click()
  const item = category.locator('.venue-menu-item[data-item-id="310"]')
  const venueOneRequestsBefore = api.getMenuRequests().filter((venueId) => venueId === 1).length
  const releaseVenueOneReload = api.deferNextMenuLoad(1)
  await item
    .getByRole('checkbox', { name: 'Доступно гостям: Кальян Ягодный', exact: true })
    .uncheck()

  const success = page.locator('.venue-menu-builder .venue-menu-success')
  await expect(success).toHaveAttribute('role', 'status')
  await expect(success).toHaveText('Позиция в стоп-листе')
  await expect
    .poll(() => api.getMenuRequests().filter((venueId) => venueId === 1).length)
    .toBe(venueOneRequestsBefore + 1)

  const oldDraftItem = category.locator('.venue-menu-item[data-item-id="311"]')
  await oldDraftItem.locator('[data-menu-control="item-edit"]').click()
  const oldDraftForm = oldDraftItem.locator('.venue-menu-item-edit-form')
  const oldDraftName = oldDraftForm.getByLabel('Название позиции', { exact: true })
  const oldDraftPrice = oldDraftForm.getByLabel('Цена, ₽', { exact: true })
  await oldDraftName.fill('Черновик старого заведения')
  await oldDraftPrice.fill('777')
  await oldDraftItem.scrollIntoViewIfNeeded()
  await oldDraftPrice.focus()
  await expect(oldDraftPrice).toBeFocused()
  const oldVenueScroll = await page.evaluate(() => window.scrollY)
  expect(oldVenueScroll).toBeGreaterThan(8)

  await venueSelect.selectOption('2')
  const secondBuilder = page.locator('.venue-menu-builder')
  const secondEditing = page.locator('details.venue-menu-editing')
  await secondEditing.locator(':scope > summary').click()
  const secondCategory = secondEditing.locator('details.venue-menu-category[data-category-id="1030"]')
  const secondSummary = secondCategory.locator(
    ':scope > summary[data-menu-category-summary="1030"]'
  )
  await secondSummary.click()
  await expect(secondCategory.getByText('Кальян Ягодный Второй', { exact: true })).toBeVisible()
  await expect(secondBuilder.locator('.venue-menu-item-edit-form')).toHaveCount(0)
  expect(
    await secondBuilder.locator('input').evaluateAll((inputs) =>
      inputs.map((input) => (input as HTMLInputElement).value)
    )
  ).not.toContain('Черновик старого заведения')
  await expect(secondBuilder.locator('[data-category-id="30"], [data-item-id="311"]')).toHaveCount(0)
  await expect(secondBuilder.locator('.venue-menu-success')).toBeHidden()
  await expect(secondBuilder.locator('.venue-menu-success')).toHaveText('')
  await expect(secondBuilder.locator(':scope > .error-card')).toBeHidden()
  await expect(secondBuilder.locator('.venue-menu-mutation-feedback:visible')).toHaveCount(0)
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'auto' }))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeLessThanOrEqual(8)
  await secondSummary.focus()
  await expect(secondSummary).toBeFocused()
  const secondVenueScroll = await page.evaluate(() => window.scrollY)
  expect(Math.abs(secondVenueScroll - oldVenueScroll)).toBeGreaterThan(8)

  const venueOneSettledBeforeRelease = api
    .getSettledMenuRequests()
    .filter((venueId) => venueId === 1).length
  releaseVenueOneReload()
  await expect
    .poll(() => api.getSettledMenuRequests().filter((venueId) => venueId === 1).length)
    .toBe(venueOneSettledBeforeRelease + 1)
  await expect(venueSelect).toHaveValue('2')
  await expect(secondCategory.getByText('Кальян Ягодный Второй', { exact: true })).toBeVisible()
  await expect(secondBuilder.locator('.venue-menu-item-edit-form')).toHaveCount(0)
  expect(
    await secondBuilder.locator('input').evaluateAll((inputs) =>
      inputs.map((input) => (input as HTMLInputElement).value)
    )
  ).not.toContain('Черновик старого заведения')
  await expect(secondBuilder.locator('.venue-menu-success')).toBeHidden()
  await expect(secondBuilder.locator(':scope > .error-card')).toBeHidden()
  await expect(secondSummary).toBeFocused()
  expect(Math.abs((await page.evaluate(() => window.scrollY)) - secondVenueScroll)).toBeLessThanOrEqual(8)
})

test('venue menu isolates form scroll focus and success across an account replacement', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuApi(page, {
    categories: buildMenuShiftCheckFixture(),
    otherAccountCategories: buildMenuShiftCheckFixture(2000, ' Новый аккаунт')
  })
  await page.setViewportSize({ width: 390, height: 600 })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const editing = page.locator('details.venue-menu-editing')
  await editing.locator(':scope > summary').click()
  const category = editing.locator('details.venue-menu-category[data-category-id="30"]')
  await category.locator(':scope > summary[data-menu-category-summary="30"]').click()
  const releaseOldAccountReload = api.deferNextMenuLoad()
  await category
    .locator('.venue-menu-item[data-item-id="310"]')
    .getByRole('checkbox', { name: 'Доступно гостям: Кальян Ягодный', exact: true })
    .uncheck()
  await expect(page.locator('.venue-menu-builder .venue-menu-success')).toHaveText(
    'Позиция в стоп-листе'
  )
  await expect.poll(() => api.getMenuCalls()).toBe(2)

  const oldDraftItem = category.locator('.venue-menu-item[data-item-id="311"]')
  await oldDraftItem.locator('[data-menu-control="item-edit"]').click()
  const oldDraftForm = oldDraftItem.locator('.venue-menu-item-edit-form')
  const oldDraftName = oldDraftForm.getByLabel('Название позиции', { exact: true })
  const oldDraftPrice = oldDraftForm.getByLabel('Цена, ₽', { exact: true })
  await oldDraftName.fill('Черновик старого аккаунта')
  await oldDraftPrice.fill('888')
  await oldDraftItem.scrollIntoViewIfNeeded()
  await oldDraftPrice.focus()
  await expect(oldDraftPrice).toBeFocused()
  const oldAccountScroll = await page.evaluate(() => window.scrollY)
  expect(oldAccountScroll).toBeGreaterThan(8)

  await page.evaluate(({ userId, initData }) => {
    window.localStorage.setItem('__e2e_telegram_user_id', String(userId))
    window.localStorage.setItem('__e2e_telegram_init_data', initData)
  }, { userId: 987654321, initData: otherMockInitData })
  await page.goto(`?mode=venue&smokeUser=other#tgWebAppData=${encodeURIComponent(otherMockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const otherBuilder = page.locator('.venue-menu-builder')
  await expect(otherBuilder).toBeVisible()
  await expect.poll(() => api.getVenueMeUserIds().at(-1)).toBe(987654321)
  const otherEditing = otherBuilder.locator('details.venue-menu-editing')
  await otherEditing.locator(':scope > summary').click()
  const otherCategory = otherEditing.locator('details.venue-menu-category[data-category-id="2030"]')
  const otherSummary = otherCategory.locator(
    ':scope > summary[data-menu-category-summary="2030"]'
  )
  await otherSummary.click()
  await expect(otherCategory.getByText('Кальян Ягодный Новый аккаунт', { exact: true })).toBeVisible()
  await expect(otherBuilder.locator('.venue-menu-item-edit-form')).toHaveCount(0)
  expect(
    await otherBuilder.locator('input').evaluateAll((inputs) =>
      inputs.map((input) => (input as HTMLInputElement).value)
    )
  ).not.toContain('Черновик старого аккаунта')
  await expect(otherBuilder.locator('[data-category-id="30"], [data-item-id="311"]')).toHaveCount(0)
  await expect(otherBuilder.locator('.venue-menu-success')).toBeHidden()
  await expect(otherBuilder.locator('.venue-menu-success')).toHaveText('')
  await expect(otherBuilder.locator(':scope > .error-card')).toBeHidden()
  await expect(otherBuilder.locator('.venue-menu-mutation-feedback:visible')).toHaveCount(0)
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'auto' }))
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeLessThanOrEqual(8)
  await otherSummary.focus()
  await expect(otherSummary).toBeFocused()
  const otherAccountScroll = await page.evaluate(() => window.scrollY)
  expect(Math.abs(otherAccountScroll - oldAccountScroll)).toBeGreaterThan(8)

  const settledBeforeRelease = api.getSettledMenuCalls()
  releaseOldAccountReload()
  await expect.poll(() => api.getSettledMenuCalls()).toBe(settledBeforeRelease + 1)
  await expect(otherBuilder).toBeVisible()
  await expect(otherCategory.getByText('Кальян Ягодный Новый аккаунт', { exact: true })).toBeVisible()
  await expect(otherBuilder.locator('.venue-menu-item-edit-form')).toHaveCount(0)
  expect(
    await otherBuilder.locator('input').evaluateAll((inputs) =>
      inputs.map((input) => (input as HTMLInputElement).value)
    )
  ).not.toContain('Черновик старого аккаунта')
  await expect(otherBuilder.locator('.venue-menu-success')).toBeHidden()
  await expect(otherBuilder.locator(':scope > .error-card')).toBeHidden()
  await expect(otherSummary).toBeFocused()
  expect(Math.abs((await page.evaluate(() => window.scrollY)) - otherAccountScroll)).toBeLessThanOrEqual(8)
})

test('venue staff has no menu shift check but keeps individual stop-list access', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuShiftCheckApi(page, {
    accesses: [
      {
        venueId: 1,
        venueName: 'Микс',
        venueCity: 'Москва',
        venueStatus: 'PUBLISHED',
        role: 'STAFF',
        permissions: ['MENU_VIEW', 'MENU_AVAILABILITY_MANAGE'],
        categories: buildMenuShiftCheckFixture()
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  await expect(page.locator('.venue-menu-shift-check')).toHaveCount(0)
  await expect(page.getByText('Проверка меню перед сменой', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('searchbox', { name: 'Поиск по позициям и опциям' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Массовое изменение' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Подтвердить проверку' })).toHaveCount(0)
  await expect(page.locator('.venue-shift-check-item')).toHaveCount(0)
  await expect(page.locator('.venue-shift-check-option')).toHaveCount(0)

  const editing = page.locator('details.venue-menu-editing')
  await expect(editing).not.toHaveAttribute('open', '')
  await expect(editing.getByRole('button', { name: 'Добавить категорию' })).toHaveCount(0)
  await editing.locator(':scope > summary').click()
  await editing
    .locator('details.venue-menu-category[data-category-id="30"] > summary')
    .click()
  const item = editing.locator('.venue-menu-item[data-item-id="310"]')
  const mintOption = item.locator('.venue-menu-option[data-option-id="402"]')
  await expect(item).toHaveCount(1)
  await expect(mintOption).toHaveCount(1)
  await expect(
    item.getByRole('checkbox', { name: 'Доступно гостям: Кальян Ягодный', exact: true })
  ).toBeChecked()
  await expect(
    mintOption.getByRole('checkbox', {
      name: 'В стоп-листе: вариант Мята для Кальян Ягодный',
      exact: true
    })
  ).not.toBeChecked()
  await item
    .getByRole('checkbox', { name: 'Доступно гостям: Кальян Ягодный', exact: true })
    .uncheck()
  await expect.poll(() => api.getItemAvailabilityCalls()).toBe(1)
  await expect(
    item.getByRole('checkbox', { name: 'В стоп-листе: Кальян Ягодный', exact: true })
  ).not.toBeChecked()
  await expect(
    mintOption.getByRole('checkbox', {
      name: 'В стоп-листе: вариант Мята для Кальян Ягодный',
      exact: true
    })
  ).not.toBeChecked()
  expect(api.getOptionAvailabilityCalls()).toBe(0)

  const directResult = await page.evaluate(async () => {
    const response = await fetch('/api/venue/menu/shift-check?venueId=1', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer e2e-session-token',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ items: [], options: [] })
    })
    return {
      status: response.status,
      body: (await response.json()) as { error?: { code?: string } }
    }
  })
  expect(directResult).toEqual({
    status: 403,
    body: { error: { code: 'FORBIDDEN', message: 'Недостаточно прав.' } }
  })
  await expect(page.locator('.venue-menu-shift-check')).toHaveCount(0)
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
  page.on('dialog', async (dialog) => {
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
  const editing = page.locator('details.venue-menu-editing')
  await editing.locator(':scope > summary').click()
  for (const categoryId of [30, 31, 32]) {
    await editing
      .locator(`details.venue-menu-category[data-category-id="${categoryId}"] > summary`)
      .click()
  }
  await expect(
    hookahItem().getByRole('checkbox', { name: 'Доступно гостям: Кальян', exact: true })
  ).toBeChecked()
  await hookahItem()
    .getByRole('checkbox', { name: 'Доступно гостям: Кальян', exact: true })
    .uncheck()
  await expect(
    hookahItem().getByRole('checkbox', { name: 'В стоп-листе: Кальян', exact: true })
  ).not.toBeChecked()
  await expect(hookahItem().locator('.menu-item-badge').filter({ hasText: 'Стоп-лист' })).toBeVisible()
  await hookahItem()
    .getByRole('checkbox', { name: 'В стоп-листе: Кальян', exact: true })
    .check()
  await expect(
    hookahItem().getByRole('checkbox', { name: 'Доступно гостям: Кальян', exact: true })
  ).toBeChecked()
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

  await hookahItem().getByRole('button', { name: 'Добавить вкус' }).click()
  const createFlavorForm = hookahItem().locator('.venue-menu-option-create-form')
  await createFlavorForm.getByLabel('Название вкуса', { exact: true }).fill('Яблоко')
  await createFlavorForm.getByLabel('Доплата к вкусу, ₽', { exact: true }).fill('250')
  await createFlavorForm.getByRole('button', { name: 'Добавить вкус', exact: true }).click()
  await expect(hookahItem().getByText('Яблоко')).toBeVisible()
  await expect(hookahItem().getByText(/\+250/)).toBeVisible()
  await expect(waterItem().getByText('Яблоко')).toHaveCount(0)
  await expect(kitchenItem().getByText('Яблоко')).toHaveCount(0)
  expect(api.getCreateOptionCalls()).toBe(1)

  await hookahItem()
    .locator('.venue-menu-option')
    .filter({ hasText: 'Яблоко' })
    .getByRole('button', { name: 'Править вкус' })
    .click()
  const editFlavorForm = hookahItem().locator('.venue-menu-option-edit-form')
  await editFlavorForm.getByLabel('Название вкуса', { exact: true }).fill('Яблоко без мяты')
  await editFlavorForm.getByLabel('Доплата к вкусу, ₽', { exact: true }).fill('0')
  await editFlavorForm.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(hookahItem().getByText('Яблоко без мяты')).toBeVisible()
  expect(api.getUpdateOptionCalls()).toBe(1)

  const editedOption = () => page.locator('.venue-menu-option').filter({ hasText: 'Яблоко без мяты' })
  await editedOption()
    .getByRole('checkbox', {
      name: 'Доступен гостям: вариант Яблоко без мяты для Кальян',
      exact: true
    })
    .uncheck()
  await expect(
    editedOption().getByRole('checkbox', {
      name: 'В стоп-листе: вариант Яблоко без мяты для Кальян',
      exact: true
    })
  ).not.toBeChecked()
  await expect(editedOption().locator('.menu-item-badge').filter({ hasText: 'Стоп-лист' })).toBeVisible()
  expect(api.getAvailabilityCalls()).toBe(1)
  await editedOption()
    .getByRole('checkbox', {
      name: 'В стоп-листе: вариант Яблоко без мяты для Кальян',
      exact: true
    })
    .check()
  await expect(
    editedOption().getByRole('checkbox', {
      name: 'Доступен гостям: вариант Яблоко без мяты для Кальян',
      exact: true
    })
  ).toBeChecked()
  expect(api.getAvailabilityCalls()).toBe(2)

  await editedOption().getByRole('button', { name: 'Удалить вкус' }).click()
  await expect(page.getByText('Яблоко без мяты')).toHaveCount(0)
  await expect(hookahItem().getByText('Ягодный')).toBeVisible()
  expect(api.getDeleteOptionCalls()).toBe(1)
  expect(api.getCategories()[0].items[0].options).toHaveLength(8)
  expect(api.getCategories()[1].items[0].options.map((option) => option.name)).toEqual(['Газированная'])

  await hookahCategory().getByLabel('Название позиции', { exact: true }).fill('Кальян дорогой')
  await hookahCategory().getByLabel('Цена, ₽', { exact: true }).fill('2500')
  await hookahCategory().getByRole('button', { name: 'Добавить позицию' }).click()
  const expensiveHookahItem = page.locator('.venue-menu-item[data-item-id="950"]')
  await expect(expensiveHookahItem.getByText('Вкусы / опции')).toBeVisible()
  await expect(expensiveHookahItem.getByText('Добавьте вкусы, чтобы гости выбирали их при заказе.')).toBeVisible()
  await expect(expensiveHookahItem.getByRole('button', { name: 'Добавить базовые вкусы' })).toBeVisible()
  await expect(expensiveHookahItem.getByRole('button', { name: 'Добавить вкус' })).toBeVisible()
  await expensiveHookahItem.getByRole('button', { name: 'Добавить базовые вкусы' }).click()
  await expect(expensiveHookahItem.getByText('Ягодный')).toBeVisible()
  await expect(expensiveHookahItem.getByRole('button', { name: 'Добавить базовые вкусы' })).toHaveCount(0)
  await expensiveHookahItem.getByRole('button', { name: 'Править позицию' }).click()
  const editItemForm = expensiveHookahItem.locator('.venue-menu-item-edit-form')
  await editItemForm.getByLabel('Название позиции', { exact: true }).fill('Кальян дорогой')
  await editItemForm.getByLabel('Цена, ₽', { exact: true }).fill('2700')
  await editItemForm.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(expensiveHookahItem.getByText(/2\s*700/)).toBeVisible()
  expect(api.getCreateItemCalls()).toBe(1)
  expect(api.getUpdateItemCalls()).toBe(1)
  expect(api.getApplyBaseFlavorProfileCalls()).toBe(2)
})

test('venue menu focuses a renamed non-empty category summary after authoritative reload', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuApi(page, { categories: buildMenuShiftCheckFixture() })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  const editing = page.locator('details.venue-menu-editing')
  const category = editing.locator('details.venue-menu-category[data-category-id="30"]')
  const summary = category.locator(':scope > summary[data-menu-category-summary="30"]')
  await editing.locator(':scope > summary').click()
  await summary.click()

  await category.locator('[data-menu-control="category-rename"]').click()
  const renameForm = category.locator('.venue-menu-category-rename-form')
  await renameForm.getByLabel('Название категории', { exact: true }).fill('Кальянная карта')
  const releaseReload = api.deferNextMenuLoad()
  await renameForm.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect.poll(() => api.getUpdateCategoryRequests()).toEqual([
    { categoryId: 30, name: 'Кальянная карта' }
  ])
  await expect.poll(() => api.getMenuCalls()).toBe(2)

  releaseReload()
  await expect(summary).toContainText('Кальянная карта')
  await expect(summary).toHaveAccessibleName(/Кальянная карта/)
  await expect(category).toHaveAttribute('open', '')
  await expect(summary).toBeFocused()
  await expect(
    category
      .locator('.venue-menu-item[data-item-id="310"]')
      .getByRole('heading', { name: 'Кальян Ягодный', exact: true })
  ).not.toBeFocused()
})

test('venue menu focuses a renamed empty category summary after authoritative reload', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const categories = buildMenuShiftCheckFixture()
  categories.push({
    id: 32,
    name: 'Десерты',
    sortOrder: 2,
    categoryType: 'FOOD',
    items: []
  })
  const api = await mockVenueMenuApi(page, { categories })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  const editing = page.locator('details.venue-menu-editing')
  const category = editing.locator('details.venue-menu-category[data-category-id="32"]')
  const summary = category.locator(':scope > summary[data-menu-category-summary="32"]')
  await editing.locator(':scope > summary').click()
  await summary.click()
  await category.locator('[data-menu-control="category-rename"]').click()
  const renameForm = category.locator('.venue-menu-category-rename-form')
  await renameForm.getByLabel('Название категории', { exact: true }).fill('Десертная карта')
  const releaseReload = api.deferNextMenuLoad()
  await renameForm.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect.poll(() => api.getUpdateCategoryRequests()).toEqual([
    { categoryId: 32, name: 'Десертная карта' }
  ])
  await expect.poll(() => api.getMenuCalls()).toBe(2)

  releaseReload()
  await expect(summary).toContainText('Десертная карта')
  await expect(summary).toHaveAccessibleName(/Десертная карта/)
  await expect(category.locator('.venue-menu-item')).toHaveCount(0)
  await expect(category).toHaveAttribute('open', '')
  await expect(summary).toBeFocused()
  await expect.poll(() => page.evaluate(() => document.activeElement === document.body)).toBe(false)
})

test('venue menu creates and focuses the server-id category summary after authoritative reload', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const categories = buildMenuShiftCheckFixture()
  categories.push({
    id: 32,
    name: 'Новая категория',
    sortOrder: 2,
    categoryType: 'OTHER',
    items: []
  })
  const api = await mockVenueMenuApi(page, { categories })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  const editing = page.locator('details.venue-menu-editing')
  await editing.locator(':scope > summary').click()
  await editing.getByRole('button', { name: 'Добавить категорию', exact: true }).click()
  const createForm = editing.locator('.venue-menu-create-category')
  await createForm.getByLabel('Название новой категории', { exact: true }).fill('Новая категория')
  const releaseReload = api.deferNextMenuLoad()
  await createForm.getByRole('button', { name: 'Добавить', exact: true }).click()
  await expect.poll(() => api.getCreateCategoryRequests()).toEqual([{ name: 'Новая категория' }])
  await expect.poll(() => api.getCreatedCategoryIds()).toEqual([980])
  await expect.poll(() => api.getMenuCalls()).toBe(2)

  releaseReload()
  const createdCategory = editing.locator('details.venue-menu-category[data-category-id="980"]')
  const createdSummary = createdCategory.locator(
    ':scope > summary[data-menu-category-summary="980"]'
  )
  const sameNameSummary = editing.locator(
    'details.venue-menu-category[data-category-id="32"] > summary[data-menu-category-summary="32"]'
  )
  await expect(createdSummary).toContainText('Новая категория')
  await expect(createdSummary).toHaveAccessibleName(/Новая категория/)
  await expect(createdCategory).toHaveAttribute('open', '')
  await expect(createdCategory.locator('.venue-menu-item')).toHaveCount(0)
  await expect(createdSummary).toBeFocused()
  await expect(sameNameSummary).not.toBeFocused()
})

test('venue menu restores the moved category summary and authoritative order', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const categories = buildMenuShiftCheckFixture()
  for (let index = 0; index < 12; index += 1) {
    categories[0].items.push({
      id: 7200 + index,
      categoryId: 30,
      name: `Позиция перед перемещаемой категорией ${index + 1}`,
      priceMinor: 30000,
      currency: 'RUB',
      isAvailable: true,
      sortOrder: index + 2,
      effectiveItemType: 'HOOKAH',
      supportsBaseFlavorProfiles: true,
      missingBaseFlavorProfilesCount: 0,
      options: []
    })
  }
  const api = await mockVenueMenuApi(page, { categories })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  const editing = page.locator('details.venue-menu-editing')
  const firstCategory = editing.locator('details.venue-menu-category[data-category-id="30"]')
  const movedCategory = editing.locator('details.venue-menu-category[data-category-id="31"]')
  const movedSummary = movedCategory.locator(
    ':scope > summary[data-menu-category-summary="31"]'
  )
  await editing.locator(':scope > summary').click()
  await firstCategory.locator(':scope > summary[data-menu-category-summary="30"]').click()
  await movedSummary.click()
  await movedSummary.evaluate((node) => {
    window.scrollTo({
      top: window.scrollY + node.getBoundingClientRect().top - 120,
      behavior: 'auto'
    })
  })
  await expect
    .poll(() => movedSummary.evaluate((node) => Math.abs(node.getBoundingClientRect().top - 120)))
    .toBeLessThanOrEqual(1)
  const anchorTop = await movedSummary.evaluate((node) => node.getBoundingClientRect().top)

  const releaseReload = api.deferNextMenuLoad()
  await movedCategory.locator('[data-menu-control="category-move-up"]').click()
  await expect.poll(() => api.getReorderCategoryRequests()).toEqual([[31, 30]])
  await expect.poll(() => api.getMenuCalls()).toBe(2)
  releaseReload()

  await expect
    .poll(() =>
      editing
        .locator('.venue-menu-categories > details.venue-menu-category')
        .evaluateAll((nodes) => nodes.map((node) => Number((node as HTMLElement).dataset.categoryId)))
    )
    .toEqual([31, 30])
  expect(api.getCategories().map((category) => category.id)).toEqual([31, 30])
  await expect(movedCategory).toHaveAttribute('open', '')
  await expect(movedSummary).toBeFocused()
  await expect(
    movedCategory
      .locator('.venue-menu-item[data-item-id="320"]')
      .getByRole('heading', { name: 'Чай', exact: true })
  ).not.toBeFocused()
  await expect(
    firstCategory
      .locator('.venue-menu-item[data-item-id="310"]')
      .getByRole('heading', { name: 'Кальян Ягодный', exact: true })
  ).not.toBeFocused()
  await expect
    .poll(() =>
      movedSummary.evaluate((node, expectedTop) =>
        Math.abs(node.getBoundingClientRect().top - expectedTop), anchorTop
      )
    )
    .toBeLessThanOrEqual(1)
})

test('venue menu skips category focus restoration after manual scroll and focus', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const categories = buildMenuShiftCheckFixture()
  for (let index = 0; index < 16; index += 1) {
    categories[0].items.push({
      id: 7400 + index,
      categoryId: 30,
      name: `Позиция для ручной прокрутки ${index + 1}`,
      priceMinor: 30000,
      currency: 'RUB',
      isAvailable: true,
      sortOrder: index + 2,
      effectiveItemType: 'HOOKAH',
      supportsBaseFlavorProfiles: true,
      missingBaseFlavorProfilesCount: 0,
      options: []
    })
  }
  const api = await mockVenueMenuApi(page, { categories })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  const editing = page.locator('details.venue-menu-editing')
  const renamedCategory = editing.locator('details.venue-menu-category[data-category-id="30"]')
  const renamedSummary = renamedCategory.locator(
    ':scope > summary[data-menu-category-summary="30"]'
  )
  const manualCategory = editing.locator('details.venue-menu-category[data-category-id="31"]')
  const manualItem = manualCategory.locator('.venue-menu-item[data-item-id="320"]')
  await editing.locator(':scope > summary').click()
  await renamedSummary.click()
  await renamedCategory.locator('[data-menu-control="category-rename"]').click()
  const renameForm = renamedCategory.locator('.venue-menu-category-rename-form')
  await renameForm.getByLabel('Название категории', { exact: true }).fill('Кальянная карта сервера')
  const releaseReload = api.deferNextMenuLoad()
  await renameForm.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect.poll(() => api.getMenuCalls()).toBe(2)
  const mutationScroll = await page.evaluate(() => window.scrollY)

  await manualCategory.locator(':scope > summary[data-menu-category-summary="31"]').click()
  await manualItem.locator('[data-menu-control="item-edit"]').click()
  const manualForm = manualItem.locator('.venue-menu-item-edit-form')
  const manualName = manualForm.getByLabel('Название позиции', { exact: true })
  await manualName.fill('Черновик после снимка')
  await manualForm.getByLabel('Цена, ₽', { exact: true }).fill('777')
  await manualName.focus()
  const scrollBeforeWheel = await page.evaluate(() => window.scrollY)
  await page.mouse.wheel(0, -320)
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeLessThan(scrollBeforeWheel)
  const userScroll = await page.evaluate(() => window.scrollY)
  expect(Math.abs(userScroll - mutationScroll)).toBeGreaterThan(8)
  await expect(manualName).toBeFocused()

  releaseReload()
  await expect(renamedSummary).toContainText('Кальянная карта сервера')
  const restoredManualForm = manualItem.locator('.venue-menu-item-edit-form')
  const restoredManualName = restoredManualForm.getByLabel('Название позиции', { exact: true })
  await expect(restoredManualName).toHaveValue('Черновик после снимка')
  await expect(restoredManualForm.getByLabel('Цена, ₽', { exact: true })).toHaveValue('777')
  await expect(restoredManualName).toBeFocused()
  const finalScroll = await page.evaluate(() => window.scrollY)
  expect(Math.abs(finalScroll - userScroll)).toBeLessThanOrEqual(8)
  await expect(renamedSummary).not.toBeFocused()
  expect(api.getUpdateItemRequests()).toEqual([])
})

test('venue menu cancel restores focus to stable inline-form triggers without network mutations', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockVenueMenuApi(page, { categories: buildMenuShiftCheckFixture() })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  const editing = page.locator('details.venue-menu-editing')
  const category = editing.locator('details.venue-menu-category[data-category-id="30"]')
  const item = category.locator('.venue-menu-item[data-item-id="310"]')
  const option = item.locator('.venue-menu-option[data-option-id="401"]')
  await editing.locator(':scope > summary').click()
  await category.locator(':scope > summary[data-menu-category-summary="30"]').click()

  const renameTrigger = category.locator('[data-menu-control="category-rename"]')
  await renameTrigger.click()
  const renameForm = category.locator('.venue-menu-category-rename-form')
  await renameForm.getByLabel('Название категории', { exact: true }).fill('Несохранённая категория')
  await renameForm.getByRole('button', { name: 'Отменить', exact: true }).click()
  await expect(renameForm).toBeHidden()
  await expect(renameTrigger).toBeFocused()

  const itemEditTrigger = item.locator('[data-menu-control="item-edit"]')
  await itemEditTrigger.click()
  const itemForm = item.locator('.venue-menu-item-edit-form')
  await itemForm.getByLabel('Название позиции', { exact: true }).fill('Несохранённая позиция')
  await itemForm.getByRole('button', { name: 'Отменить', exact: true }).click()
  await expect(itemForm).toHaveCount(0)
  await expect(itemEditTrigger).toBeFocused()

  const addOptionTrigger = item.locator('[data-menu-control="item-create-option"]')
  await addOptionTrigger.click()
  const addOptionForm = item.locator('.venue-menu-option-create-form')
  await addOptionForm.getByLabel('Название вкуса', { exact: true }).fill('Несохранённый вкус')
  await addOptionForm.getByLabel('Доплата к вкусу, ₽', { exact: true }).fill('99')
  await addOptionForm.getByRole('button', { name: 'Отменить', exact: true }).click()
  await expect(addOptionForm).toBeHidden()
  await expect(addOptionTrigger).toBeFocused()

  const optionEditTrigger = option.locator('[data-menu-control="option-edit"]')
  await optionEditTrigger.click()
  const optionForm = option.locator('.venue-menu-option-edit-form')
  await optionForm.getByLabel('Название вкуса', { exact: true }).fill('Несохранённое яблоко')
  await optionForm.getByRole('button', { name: 'Отменить', exact: true }).click()
  await expect(optionForm).toHaveCount(0)
  await expect(optionEditTrigger).toBeFocused()

  expect(api.getCreateCategoryRequests()).toEqual([])
  expect(api.getUpdateCategoryRequests()).toEqual([])
  expect(api.getReorderCategoryRequests()).toEqual([])
  expect(api.getUpdateItemRequests()).toEqual([])
  expect(api.getCreateOptionRequests()).toEqual([])
  expect(api.getUpdateOptionRequests()).toEqual([])
  expect(api.getMenuCalls()).toBe(1)
})

test('venue menu management keeps cards and actions inside narrow viewports', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const categories = buildMenuShiftCheckFixture()
  const item = categories[0].items[0]
  item.name = 'Очень длинное русское название позиции для проверки переноса текста на узком экране'
  item.options[0].name = 'Очень длинное название вкуса или дополнительной опции без обрезания справа'
  await mockVenueMenuApi(page, { categories })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const editing = page.locator('details.venue-menu-editing')
  const category = editing.locator('details.venue-menu-category[data-category-id="30"]')
  const itemCard = category.locator('.venue-menu-item[data-item-id="310"]')
  await editing.locator(':scope > summary').click()
  await category.locator(':scope > summary').click()

  const optionRow = itemCard.locator('.venue-menu-option[data-option-id="401"]')
  const createItemForm = category.locator('.venue-menu-item-create-form')
  const addOptionTrigger = itemCard
    .locator('.venue-menu-option-header')
    .getByRole('button', { name: 'Добавить вкус', exact: true })
  const expectNoHorizontalOverflow = async () => {
    const geometry = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth
    }))
    expect(geometry.scrollWidth).toBeLessThanOrEqual(geometry.clientWidth)
  }
  const expectHorizontallyInsideViewport = async (locator: Locator) => {
    await expect(locator).toBeVisible()
    const [box, clientWidth] = await Promise.all([
      locator.boundingBox(),
      page.evaluate(() => document.documentElement.clientWidth)
    ])
    expect(box).not.toBeNull()
    if (!box) return
    expect(box.x).toBeGreaterThanOrEqual(-0.5)
    expect(box.x + box.width).toBeLessThanOrEqual(clientWidth + 0.5)
  }
  const expectControlInsideViewport = async (locator: Locator) => {
    await expect(locator).toBeVisible()
    await locator.scrollIntoViewIfNeeded()
    const [box, viewport] = await Promise.all([
      locator.boundingBox(),
      page.evaluate(() => ({
        width: document.documentElement.clientWidth,
        height: document.documentElement.clientHeight
      }))
    ])
    expect(box).not.toBeNull()
    if (!box) return
    expect(box.x).toBeGreaterThanOrEqual(-0.5)
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width + 0.5)
    expect(box.y).toBeGreaterThanOrEqual(-0.5)
    expect(box.y + box.height).toBeLessThanOrEqual(viewport.height + 0.5)
  }

  for (const viewport of [
    { width: 320, height: 700 },
    { width: 360, height: 800 },
    { width: 390, height: 844 },
    { width: 430, height: 932 }
  ]) {
    await page.setViewportSize(viewport)
    await expect(itemCard).toBeVisible()
    const layout = await page.evaluate(() => {
      const menu = document.querySelector<HTMLElement>('.venue-menu-builder')
      const item = document.querySelector<HTMLElement>('.venue-menu-item[data-item-id="310"]')
      const option = document.querySelector<HTMLElement>('.venue-menu-option[data-option-id="401"]')
      const input = document.querySelector<HTMLElement>(
        '.venue-menu-item-create-form [data-menu-price-input="true"]'
      )
      const rect = (node: HTMLElement | null) => {
        const value = node?.getBoundingClientRect()
        return value ? { left: value.left, right: value.right, width: value.width } : null
      }
      return {
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
        menu: rect(menu),
        item: rect(item),
        option: rect(option),
        input: rect(input),
        longTextOverflows:
          (item?.querySelector<HTMLElement>('.venue-menu-item-info strong')?.scrollWidth ?? 0) >
            (item?.querySelector<HTMLElement>('.venue-menu-item-info strong')?.clientWidth ?? 0) ||
          (option?.querySelector<HTMLElement>('.venue-menu-option-info > span')?.scrollWidth ?? 0) >
            (option?.querySelector<HTMLElement>('.venue-menu-option-info > span')?.clientWidth ?? 0)
      }
    })
    expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth)
    expect(layout.menu?.right).toBeLessThanOrEqual(viewport.width)
    expect(layout.item?.right).toBeLessThanOrEqual(viewport.width)
    expect(layout.option?.right).toBeLessThanOrEqual(viewport.width)
    expect(layout.input?.right).toBeLessThanOrEqual(viewport.width)
    expect(layout.longTextOverflows).toBe(false)
    await expectHorizontallyInsideViewport(page.locator('.venue-menu-builder'))
    await expectHorizontallyInsideViewport(itemCard)
    await expectHorizontallyInsideViewport(optionRow)
    await expectHorizontallyInsideViewport(createItemForm)

    for (const control of [
      itemCard.getByRole('button', { name: 'Править позицию', exact: true }),
      itemCard
        .locator('.venue-menu-item-secondary-actions')
        .getByRole('button', { name: 'Удалить', exact: true }),
      itemCard.getByRole('checkbox', {
        name: /^Доступно гостям: Очень длинное русское название позиции/
      }),
      itemCard.getByRole('button', { name: 'Добавить базовые вкусы', exact: true }),
      addOptionTrigger,
      optionRow.getByRole('checkbox', { name: /^Доступен гостям: вариант Очень длинное название вкуса/ }),
      optionRow.getByRole('button', { name: 'Править вкус', exact: true }),
      optionRow.getByRole('button', { name: 'Удалить вкус', exact: true }),
      createItemForm.getByLabel('Название позиции', { exact: true }),
      createItemForm.getByLabel('Цена, ₽', { exact: true }),
      createItemForm.getByRole('combobox', { name: 'Валюта новой позиции', exact: true }),
      createItemForm.getByRole('button', { name: 'Добавить позицию', exact: true })
    ]) {
      await expectControlInsideViewport(control)
    }
    await expectNoHorizontalOverflow()

    await itemCard.getByRole('button', { name: 'Править позицию', exact: true }).click()
    const editItemForm = itemCard.locator('.venue-menu-item-edit-form')
    await expectNoHorizontalOverflow()
    await expectHorizontallyInsideViewport(editItemForm)
    for (const control of [
      editItemForm.getByLabel('Название позиции', { exact: true }),
      editItemForm.getByLabel('Цена, ₽', { exact: true }),
      editItemForm.getByRole('button', { name: 'Сохранить', exact: true }),
      editItemForm.getByRole('button', { name: 'Отменить', exact: true })
    ]) {
      await expectControlInsideViewport(control)
    }
    await editItemForm.getByRole('button', { name: 'Отменить', exact: true }).click()

    await addOptionTrigger.click()
    const createOptionForm = itemCard.locator('.venue-menu-option-create-form')
    await expectNoHorizontalOverflow()
    await expectHorizontallyInsideViewport(createOptionForm)
    for (const control of [
      createOptionForm.getByLabel('Название вкуса', { exact: true }),
      createOptionForm.getByLabel('Доплата к вкусу, ₽', { exact: true }),
      createOptionForm.getByRole('button', { name: 'Добавить вкус', exact: true }),
      createOptionForm.getByRole('button', { name: 'Отменить', exact: true })
    ]) {
      await expectControlInsideViewport(control)
    }
    await createOptionForm.getByRole('button', { name: 'Отменить', exact: true }).click()

    await optionRow.getByRole('button', { name: 'Править вкус', exact: true }).click()
    const editOptionForm = optionRow.locator('.venue-menu-option-edit-form')
    await expectNoHorizontalOverflow()
    await expectHorizontallyInsideViewport(editOptionForm)
    for (const control of [
      editOptionForm.getByLabel('Название вкуса', { exact: true }),
      editOptionForm.getByLabel('Доплата к вкусу, ₽', { exact: true }),
      editOptionForm.getByRole('button', { name: 'Сохранить', exact: true }),
      editOptionForm.getByRole('button', { name: 'Отменить', exact: true })
    ]) {
      await expectControlInsideViewport(control)
    }
    await editOptionForm.getByRole('button', { name: 'Отменить', exact: true }).click()
    await expectNoHorizontalOverflow()
  }
})

test('venue menu price fields use empty new values and replace an existing zero', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const categories = buildMenuShiftCheckFixture()
  categories[0].items[1].priceMinor = 0
  const api = await mockVenueMenuApi(page, { categories })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const editing = page.locator('details.venue-menu-editing')
  const category = editing.locator('details.venue-menu-category[data-category-id="30"]')
  const hookahItem = category.locator('.venue-menu-item[data-item-id="310"]')
  await editing.locator(':scope > summary').click()
  await category.locator(':scope > summary').click()

  const createItemForm = category.locator('.venue-menu-item-create-form')
  const newItemPrice = createItemForm.getByLabel('Цена, ₽', { exact: true })
  await expect(newItemPrice).toHaveValue('')
  await createItemForm.getByLabel('Название позиции', { exact: true }).fill('Новая позиция')
  await newItemPrice.pressSequentially('150')
  await expect(newItemPrice).toHaveValue('150')
  await createItemForm.getByRole('button', { name: 'Добавить позицию', exact: true }).click()
  await expect.poll(() => api.getCreateItemRequests()).toEqual([
    { categoryId: 30, name: 'Новая позиция', priceMinor: 15000, currency: 'RUB' }
  ])

  await hookahItem.getByRole('button', { name: 'Добавить вкус', exact: true }).click()
  const createOptionForm = hookahItem.locator('.venue-menu-option-create-form')
  const newOptionPrice = createOptionForm.getByLabel('Доплата к вкусу, ₽', { exact: true })
  await expect(newOptionPrice).toHaveValue('')
  await createOptionForm.getByLabel('Название вкуса', { exact: true }).fill('Новый вкус')
  await newOptionPrice.pressSequentially('150')
  await expect(newOptionPrice).toHaveValue('150')
  await createOptionForm.getByRole('button', { name: 'Добавить вкус', exact: true }).click()
  await expect.poll(() => api.getCreateOptionRequests()).toEqual([
    { itemId: 310, name: 'Новый вкус', priceDeltaMinor: 15000 }
  ])

  const zeroOption = hookahItem.locator('.venue-menu-option[data-option-id="401"]')
  await zeroOption.getByRole('button', { name: 'Править вкус', exact: true }).click()
  const zeroPrice = zeroOption.locator('.venue-menu-option-edit-form').getByLabel('Доплата к вкусу, ₽', { exact: true })
  await expect(zeroPrice).toHaveValue('0')
  await zeroPrice.focus()
  await zeroPrice.blur()
  await expect(zeroPrice).toHaveValue('0')
  await zeroPrice.focus()
  await zeroPrice.pressSequentially('150')
  await expect(zeroPrice).toHaveValue('150')
  await zeroPrice.blur()
  await zeroPrice.focus()
  await zeroPrice.pressSequentially('0')
  await expect(zeroPrice).toHaveValue('1500')
  await zeroOption.getByRole('button', { name: 'Сохранить', exact: true }).click()

  const secondZeroOption = category.locator('.venue-menu-option[data-option-id="403"]')
  await secondZeroOption.getByRole('button', { name: 'Править вкус', exact: true }).click()
  const pastedPrice = secondZeroOption
    .locator('.venue-menu-option-edit-form')
    .getByLabel('Доплата к вкусу, ₽', { exact: true })
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], {
    origin: new URL(page.url()).origin
  })
  await page.evaluate(async () => {
    await navigator.clipboard.writeText('175')
  })
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe('175')
  await pastedPrice.focus()
  await pastedPrice.press('ControlOrMeta+V')
  await expect(pastedPrice).toHaveValue('175')
  await secondZeroOption.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect.poll(() => api.getUpdateOptionRequests()).toEqual([
    { optionId: 401, name: 'Яблоко', priceDeltaMinor: 150000 },
    { optionId: 403, name: 'Лёд', priceDeltaMinor: 17500 }
  ])

  const zeroItem = category.locator('.venue-menu-item[data-item-id="311"]')
  await zeroItem.getByRole('button', { name: 'Править позицию', exact: true }).click()
  const zeroItemPrice = zeroItem.locator('.venue-menu-item-edit-form').getByLabel('Цена, ₽', { exact: true })
  await expect(zeroItemPrice).toHaveValue('0')
  await zeroItemPrice.focus()
  await zeroItemPrice.blur()
  await expect(zeroItemPrice).toHaveValue('0')
  await zeroItemPrice.focus()
  await zeroItemPrice.pressSequentially('150')
  await expect(zeroItemPrice).toHaveValue('150')
  await zeroItemPrice.blur()
  await zeroItemPrice.focus()
  await zeroItemPrice.pressSequentially('0')
  await expect(zeroItemPrice).toHaveValue('1500')
  await zeroItem.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect.poll(() => api.getUpdateItemRequests()).toEqual([
    { itemId: 311, name: 'Кальян Классический', priceMinor: 150000 }
  ])
  await expect.poll(() => api.getMenuCalls()).toBe(6)

  const invalidCreateItemForm = category.locator('.venue-menu-item-create-form')
  await invalidCreateItemForm.getByLabel('Название позиции', { exact: true }).fill('Без цены')
  await invalidCreateItemForm.getByRole('button', { name: 'Добавить позицию', exact: true }).click()
  await expect(invalidCreateItemForm.getByText('Заполните название и цену.', { exact: true })).toBeVisible()
  await expect(invalidCreateItemForm.getByLabel('Название позиции', { exact: true })).toHaveValue('Без цены')
  expect(api.getCreateItemRequests()).toHaveLength(1)
})

test('venue menu restores an edited item and option context after authoritative reload', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const categories = buildMenuShiftCheckFixture()
  const targetItem = {
    id: 9000,
    categoryId: 30,
    name: 'Целевая позиция',
    priceMinor: 42000,
    currency: 'RUB',
    isAvailable: true,
    sortOrder: 30,
    effectiveItemType: 'HOOKAH' as const,
    supportsBaseFlavorProfiles: true,
    missingBaseFlavorProfilesCount: 0,
    options: [
      {
        id: 9001,
        itemId: 9000,
        name: 'Целевой вкус',
        priceDeltaMinor: 0,
        isAvailable: true,
        sortOrder: 0
      }
    ]
  }
  for (let index = 0; index < 16; index += 1) {
    categories[0].items.push({
      id: 7000 + index,
      categoryId: 30,
      name: `Дополнительная позиция ${index + 1}`,
      priceMinor: 30000,
      currency: 'RUB',
      isAvailable: true,
      sortOrder: index + 2,
      effectiveItemType: 'HOOKAH',
      supportsBaseFlavorProfiles: true,
      missingBaseFlavorProfilesCount: 0,
      options: []
    })
  }
  categories[0].items.push(targetItem)
  const api = await mockVenueMenuApi(page, { categories })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const editing = page.locator('details.venue-menu-editing')
  const category = editing.locator('details.venue-menu-category[data-category-id="30"]')
  const item = category.locator('.venue-menu-item[data-item-id="9000"]')
  await editing.locator(':scope > summary').click()
  await category.locator(':scope > summary').click()
  await item.scrollIntoViewIfNeeded()
  const scrollBefore = await page.evaluate(() => window.scrollY)
  expect(scrollBefore).toBeGreaterThan(0)

  await item.getByRole('button', { name: 'Править позицию', exact: true }).click()
  const editItemForm = item.locator('.venue-menu-item-edit-form')
  await editItemForm.getByLabel('Название позиции', { exact: true }).fill('Изменённая целевая позиция')
  await editItemForm.getByLabel('Цена, ₽', { exact: true }).fill('500')
  const itemAnchorTop = await item.evaluate((node) => node.getBoundingClientRect().top)
  const releaseItemReload = api.deferNextMenuLoad()
  await editItemForm.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect.poll(() => api.getMenuCalls()).toBe(2)
  releaseItemReload()
  const updatedItem = category.locator('.venue-menu-item[data-item-id="9000"]')
  await expect(updatedItem.getByText('Изменённая целевая позиция', { exact: true })).toBeVisible()
  await expect(category).toHaveAttribute('open', '')
  await expect(updatedItem).toBeVisible()
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0)
  await expect
    .poll(() =>
      updatedItem.evaluate(
        (node, anchorTop) => Math.abs(node.getBoundingClientRect().top - anchorTop),
        itemAnchorTop
      )
    )
    .toBeLessThanOrEqual(1)
  await expect
    .poll(() => page.evaluate(() => document.activeElement?.textContent))
    .toBe('Изменённая целевая позиция')

  const option = updatedItem.locator('.venue-menu-option[data-option-id="9001"]')
  await option.getByRole('button', { name: 'Править вкус', exact: true }).click()
  const editOptionForm = option.locator('.venue-menu-option-edit-form')
  await editOptionForm.getByLabel('Название вкуса', { exact: true }).fill('Переименованный вкус')
  await editOptionForm.getByLabel('Доплата к вкусу, ₽', { exact: true }).fill('50')
  const releaseOptionReload = api.deferNextMenuLoad()
  await editOptionForm.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect.poll(() => api.getMenuCalls()).toBe(3)
  releaseOptionReload()
  const updatedOption = updatedItem.locator('.venue-menu-option[data-option-id="9001"]')
  await expect(updatedOption.getByText('Переименованный вкус', { exact: true })).toBeVisible()
  await expect(category).toHaveAttribute('open', '')
  await expect(updatedOption).toBeVisible()
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0)
  await expect.poll(() => page.evaluate(() => document.activeElement?.textContent)).toBe('Переименованный вкус')
})

test('venue menu does not steal user scroll or focus after a delayed authoritative reload', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const categories = buildMenuShiftCheckFixture()
  for (let index = 0; index < 18; index += 1) {
    categories[0].items.push({
      id: 8000 + index,
      categoryId: 30,
      name: `Позиция для прокрутки ${index + 1}`,
      priceMinor: 30000,
      currency: 'RUB',
      isAvailable: true,
      sortOrder: index + 2,
      effectiveItemType: 'HOOKAH',
      supportsBaseFlavorProfiles: true,
      missingBaseFlavorProfilesCount: 0,
      options: []
    })
  }
  categories[0].items.push({
    id: 9100,
    categoryId: 30,
    name: 'Позиция с задержанным обновлением',
    priceMinor: 42000,
    currency: 'RUB',
    isAvailable: true,
    sortOrder: 30,
    effectiveItemType: 'HOOKAH',
    supportsBaseFlavorProfiles: true,
    missingBaseFlavorProfilesCount: 0,
    options: []
  })
  const api = await mockVenueMenuApi(page, { categories })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()

  const editing = page.locator('details.venue-menu-editing')
  const category = editing.locator('details.venue-menu-category[data-category-id="30"]')
  const item = category.locator('.venue-menu-item[data-item-id="9100"]')
  const currentWorkItem = category.locator('.venue-menu-item[data-item-id="310"]')
  await editing.locator(':scope > summary').click()
  await category.locator(':scope > summary').click()
  await item.scrollIntoViewIfNeeded()
  expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0)

  await item.getByRole('button', { name: 'Править позицию', exact: true }).click()
  const form = item.locator('.venue-menu-item-edit-form')
  await form.getByLabel('Название позиции', { exact: true }).fill('Авторитетно обновлённая позиция')
  const releaseReload = api.deferNextMenuLoad()
  await form.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect.poll(() => api.getMenuCalls()).toBe(2)
  const capturedScroll = await page.evaluate(() => window.scrollY)

  await page.mouse.move(120, 120)
  await page.mouse.wheel(0, -10_000)
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeLessThan(capturedScroll)
  await currentWorkItem.getByRole('button', { name: 'Править позицию', exact: true }).click()
  const currentWorkForm = currentWorkItem.locator('.venue-menu-item-edit-form')
  const currentWorkName = currentWorkForm.getByLabel('Название позиции', { exact: true })
  const currentWorkPrice = currentWorkForm.getByLabel('Цена, ₽', { exact: true })
  await currentWorkName.press('ControlOrMeta+A')
  await currentWorkName.pressSequentially('Черновик пользователя')
  await currentWorkPrice.press('ControlOrMeta+A')
  await currentWorkPrice.pressSequentially('999')
  await expect(currentWorkPrice).toBeFocused()
  const userScroll = await page.evaluate(() => window.scrollY)
  expect(Math.abs(userScroll - capturedScroll)).toBeGreaterThan(8)

  releaseReload()
  const updatedItem = category.locator('.venue-menu-item[data-item-id="9100"]')
  const updatedHeading = updatedItem.getByRole('heading', {
    name: 'Авторитетно обновлённая позиция',
    exact: true
  })
  await expect(updatedHeading).toHaveCount(1)
  const restoredWorkForm = currentWorkItem.locator('.venue-menu-item-edit-form')
  await expect(restoredWorkForm.getByLabel('Название позиции', { exact: true })).toHaveValue(
    'Черновик пользователя'
  )
  const restoredWorkPrice = restoredWorkForm.getByLabel('Цена, ₽', { exact: true })
  await expect(restoredWorkPrice).toHaveValue('999')
  await expect(restoredWorkPrice).toBeFocused()
  await expect(restoredWorkPrice).toBeInViewport()
  await expect
    .poll(() => page.evaluate((expected) => Math.abs(window.scrollY - expected), userScroll))
    .toBeLessThanOrEqual(8)
  const finalScroll = await page.evaluate(() => window.scrollY)
  expect(Math.abs(finalScroll - userScroll)).toBeLessThanOrEqual(8)
  expect(Math.abs(finalScroll - capturedScroll)).toBeGreaterThan(8)
  await expect
    .poll(() => updatedHeading.evaluate((node) => document.activeElement === node))
    .toBe(false)
  expect(api.getCategories()[0].items.find((candidate) => candidate.id === 9100)?.name).toBe(
    'Авторитетно обновлённая позиция'
  )
})

test('venue menu keeps failed inline edit values at the current card', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockVenueMenuApi(page, {
    categories: buildMenuShiftCheckFixture(),
    updateItemErrors: {
      310: { status: 500, code: 'INTERNAL_ERROR', message: 'write failed' }
    }
  })
  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const editing = page.locator('details.venue-menu-editing')
  const category = editing.locator('details.venue-menu-category[data-category-id="30"]')
  const item = category.locator('.venue-menu-item[data-item-id="310"]')
  await editing.locator(':scope > summary').click()
  await category.locator(':scope > summary').click()
  await item.getByRole('button', { name: 'Править позицию', exact: true }).click()
  const form = item.locator('.venue-menu-item-edit-form')
  await form.getByLabel('Название позиции', { exact: true }).fill('Не потерять при ошибке')
  await form.getByLabel('Цена, ₽', { exact: true }).fill('777')
  await form.getByRole('button', { name: 'Сохранить', exact: true }).click()
  await expect(form.getByLabel('Название позиции', { exact: true })).toHaveValue('Не потерять при ошибке')
  await expect(form.getByLabel('Цена, ₽', { exact: true })).toHaveValue('777')
  await expect(item.locator('.venue-menu-mutation-feedback')).toBeVisible()
  await expect(item).toBeVisible()
})

test('venue menu item delete explains dependencies and handles fixed and choice rewards', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const blockedMessage =
    'Позицию нельзя удалить: она используется как фиксированный подарок в акции. ' +
    'Сначала замените подарок или измените акцию, затем повторите удаление.'
  const confirmation =
    'Позиция будет удалена из меню.\n\n' +
    'Ссылки на неё в условиях акций и списках подарков на выбор будут удалены автоматически.\n\n' +
    'Если позиция используется как фиксированный подарок, удалить её нельзя, ' +
    'пока подарок не будет заменён в акции.'
  const api = await mockVenueMenuApi(page, {
    categories: [
      {
        id: 30,
        name: 'Напитки',
        sortOrder: 0,
        categoryType: 'DRINK',
        items: [
          {
            id: 310,
            categoryId: 30,
            name: 'Фиксированный подарок',
            priceMinor: 30000,
            currency: 'RUB',
            isAvailable: true,
            sortOrder: 0,
            effectiveItemType: 'DRINK',
            options: []
          },
          {
            id: 311,
            categoryId: 30,
            name: 'Подарок на выбор',
            priceMinor: 35000,
            currency: 'RUB',
            isAvailable: true,
            sortOrder: 1,
            effectiveItemType: 'DRINK',
            options: []
          }
        ]
      }
    ],
    deleteItemErrors: {
      310: {
        status: 409,
        code: 'MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD',
        message: blockedMessage
      }
    }
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const editing = page.locator('details.venue-menu-editing')
  await editing.locator(':scope > summary').click()
  await editing
    .locator('details.venue-menu-category[data-category-id="30"] > summary')
    .click()
  const fixedItem = page.locator('.venue-menu-item[data-item-id="310"]')
  const choiceItem = page.locator('.venue-menu-item[data-item-id="311"]')
  const initialMenuCalls = api.getMenuCalls()

  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toBe(confirmation)
    await dialog.accept()
  })
  await fixedItem.getByRole('button', { name: 'Удалить', exact: true }).click()
  await expect(fixedItem).toBeVisible()
  await expect(fixedItem.locator('.venue-menu-mutation-feedback')).toHaveText(blockedMessage)
  await expect(page.getByText('Позиция удалена', { exact: true })).toHaveCount(0)
  expect(api.getDeleteItemRequests()).toEqual([310])

  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toBe(confirmation)
    await dialog.accept()
  })
  await choiceItem.getByRole('button', { name: 'Удалить', exact: true }).click()
  await expect(choiceItem).toHaveCount(0)
  await expect(page.getByText('Позиция удалена', { exact: true })).toBeVisible()
  await expect.poll(() => api.getMenuCalls()).toBe(initialMenuCalls + 1)
  expect(api.getDeleteItemRequests()).toEqual([310, 311])
})

test('venue menu item delete cancellation sends no request', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const confirmation =
    'Позиция будет удалена из меню.\n\n' +
    'Ссылки на неё в условиях акций и списках подарков на выбор будут удалены автоматически.\n\n' +
    'Если позиция используется как фиксированный подарок, удалить её нельзя, ' +
    'пока подарок не будет заменён в акции.'
  const api = await mockVenueMenuApi(page, {
    categories: [
      {
        id: 30,
        name: 'Напитки',
        sortOrder: 0,
        categoryType: 'DRINK',
        items: [
          {
            id: 310,
            categoryId: 30,
            name: 'Чай',
            priceMinor: 30000,
            currency: 'RUB',
            isAvailable: true,
            sortOrder: 0,
            effectiveItemType: 'DRINK',
            options: []
          }
        ]
      }
    ]
  })

  await page.goto(`?mode=venue#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await page.getByRole('button', { name: 'Заказное меню', exact: true }).click()
  const editing = page.locator('details.venue-menu-editing')
  await editing.locator(':scope > summary').click()
  await editing
    .locator('details.venue-menu-category[data-category-id="30"] > summary')
    .click()
  const item = page.locator('.venue-menu-item[data-item-id="310"]')
  const initialMenuCalls = api.getMenuCalls()

  page.once('dialog', async (dialog) => {
    expect(dialog.message()).toBe(confirmation)
    await dialog.dismiss()
  })
  await item.getByRole('button', { name: 'Удалить', exact: true }).click()

  await expect(item).toBeVisible()
  expect(api.getDeleteItemRequests()).toEqual([])
  expect(api.getMenuCalls()).toBe(initialMenuCalls)
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
  const editing = page.locator('details.venue-menu-editing')
  await editing.locator(':scope > summary').click()
  await editing
    .locator('details.venue-menu-category[data-category-id="30"] > summary')
    .click()
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
  await expect(
    hookahItem.getByRole('checkbox', { name: 'Доступно гостям: Кальян', exact: true })
  ).toBeChecked()
  await expect(
    appleOption.getByRole('checkbox', {
      name: 'Доступен гостям: вариант Яблоко для Кальян',
      exact: true
    })
  ).toBeChecked()
  expect(api.getDeleteItemRequests()).toEqual([])

  await appleOption
    .getByRole('checkbox', {
      name: 'Доступен гостям: вариант Яблоко для Кальян',
      exact: true
    })
    .uncheck()
  await expect(
    appleOption.getByRole('checkbox', {
      name: 'В стоп-листе: вариант Яблоко для Кальян',
      exact: true
    })
  ).not.toBeChecked()
  await expect(appleOption.locator('.menu-item-badge').filter({ hasText: 'Стоп-лист' })).toBeVisible()
  await hookahItem
    .getByRole('checkbox', { name: 'Доступно гостям: Кальян', exact: true })
    .uncheck()
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
