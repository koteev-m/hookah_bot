import type { SupportMessageDto, SupportThreadDto } from './supportDtos'

export type VenueAccessDto = {
  venueId: number
  venueName?: string | null
  venueCity?: string | null
  venueStatus?: string | null
  role: 'OWNER' | 'MANAGER' | 'STAFF'
  permissions: string[]
}

export type VenueMeResponse = {
  userId: number
  venues: VenueAccessDto[]
}

export type VenueStatsPeriod = 'today' | '7d' | '30d'

export type VenueStatsTopItemDto = {
  itemName: string
  qty: number
}

export type VenueStatsResponse = {
  venueId: number
  period: VenueStatsPeriod
  periodTitle: string
  periodStart: string
  ordersCount: number
  revenueMinor: number
  averageCheckMinor: number
  discountMinor: number
  cancelledItemsCount: number
  currency: string
  topItems: VenueStatsTopItemDto[]
}

export type VenueFeedbackFilter = 'all' | 'low'

export type VenueFeedbackResponse = {
  venueId: number
  filter: VenueFeedbackFilter
  summary: VenueFeedbackSummaryDto
  items: VenueFeedbackItemDto[]
}

export type VenueFeedbackSummaryDto = {
  count: number
  averageRating?: number | null
  lowCount: number
}

export type VenueFeedbackItemDto = {
  feedbackId: number
  visitId: number
  occurredAt: string
  serviceDate?: string | null
  rating?: number | null
  tags?: string[]
  comment?: string | null
  guestLabel: string
  createdAt?: string | null
}

export type StaffChatLinkCodeResponse = {
  code: string
  expiresAt: string
  ttlSeconds: number
  linkCommand?: string | null
  testCommand?: string | null
}

export type VenueStaffMemberDto = {
  userId: number
  role: 'OWNER' | 'MANAGER' | 'STAFF'
  createdAt: string
  invitedByUserId?: number | null
}

export type VenueStaffListResponse = {
  members: VenueStaffMemberDto[]
}

export type VenueStaffProfileSubtype = 'hookah_master' | 'waiter' | 'admin' | 'other' | string

export type VenueStaffShiftStatus = 'scheduled' | 'active' | 'completed' | 'canceled' | string

export type VenueStaffShiftDto = {
  id: number
  staffProfileId: number
  shiftDate: string
  startsAt?: string | null
  endsAt?: string | null
  status: VenueStaffShiftStatus
  isGuestVisible: boolean
  manuallyMarkedActive: boolean
  createdAt: string
  updatedAt: string
}

export type VenueStaffProfileDto = {
  id: number
  linkedUserId?: number | null
  displayName: string
  roleLabel?: string | null
  subtype: VenueStaffProfileSubtype
  photoRef?: string | null
  bio?: string | null
  tags?: string[]
  isGuestVisible: boolean
  publishedAt?: string | null
  disabledAt?: string | null
  createdAt: string
  updatedAt: string
  todayShift?: VenueStaffShiftDto | null
}

export type VenueStaffProfilesResponse = {
  profiles: VenueStaffProfileDto[]
}

export type VenueStaffProfileCreateRequest = {
  displayName: string
  roleLabel?: string | null
  subtype?: VenueStaffProfileSubtype
  linkedUserId?: number | null
  photoRef?: string | null
  bio?: string | null
  tags?: string[]
  isGuestVisible?: boolean
}

export type VenueStaffProfileUpdateRequest = {
  displayName?: string | null
  roleLabel?: string | null
  subtype?: VenueStaffProfileSubtype | null
  linkedUserId?: number | null
  unlinkUser?: boolean
  photoRef?: string | null
  bio?: string | null
  tags?: string[] | null
  isGuestVisible?: boolean | null
}

export type VenueStaffShiftUpsertRequest = {
  status?: VenueStaffShiftStatus
  startsAt?: string | null
  endsAt?: string | null
  isGuestVisible?: boolean | null
}

export type VenueStaffShiftResponse = {
  shift: VenueStaffShiftDto
}

export type VenueStaffTodayShiftsResponse = {
  shifts: VenueStaffShiftDto[]
}

export type VenueStaffInviteRequest = {
  role: 'OWNER' | 'MANAGER' | 'STAFF'
  expiresIn?: number
}

export type VenueStaffInviteResponse = {
  inviteCode: string
  expiresAt: string
  ttlSeconds: number
  instructions: string
  role?: 'OWNER' | 'MANAGER' | 'STAFF' | string
  venueName?: string
  startPayload?: string
  deepLink?: string | null
  fallbackCommand?: string
  copyText?: string
}

export type VenueStaffInviteAcceptRequest = {
  inviteCode: string
}

export type VenueStaffInviteAcceptResponse = {
  venueId: number
  member: VenueStaffMemberDto
  alreadyMember: boolean
}

export type VenueStaffUpdateRoleRequest = {
  role: 'OWNER' | 'MANAGER' | 'STAFF'
}

export type VenueStaffChatStatusResponse = {
  venueId: number
  isLinked: boolean
  chatId?: number | null
  maskedChatId?: string | null
  linkedAt?: string | null
  linkedByUserId?: number | null
  activeCodeHint?: string | null
  activeCodeExpiresAt?: string | null
  testCommand?: string | null
}

export type StaffChatTestResponse = {
  result: 'QUEUED' | 'NO_STAFF_CHAT' | 'TELEGRAM_INACTIVE' | 'FAILED' | string
  queued: boolean
  message: string
}

export type VenueStaffCallDto = {
  id: number
  tableId?: number | null
  tableNumber: number
  reason: string
  reasonLabel: string
  comment?: string | null
  orderId?: number | null
  tabId?: number | null
  paymentMethod?: string | null
  paymentMethodLabel?: string | null
  orderDisplayLabel?: string | null
  tabDisplayLabel?: string | null
  status: 'NEW' | 'ACK' | 'DONE' | 'CANCELLED' | string
  statusLabel: string
  createdAt?: string | null
  guestDisplayName?: string | null
}

export type VenueStaffCallsResponse = {
  items: VenueStaffCallDto[]
}

export type VenueStaffCallActionResponse = {
  call: VenueStaffCallDto
  applied: boolean
}

export type VenueBookingDto = {
  bookingId: number
  displayNumber?: number | null
  status: 'pending' | 'confirmed' | 'changed' | 'canceled' | 'expired' | 'no_show' | 'seated' | string
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

export type VenueBookingListResponse = {
  items: VenueBookingDto[]
}

export type VenueBookingChangeRequest = {
  scheduledAt?: string | null
  scheduledLocalDate?: string | null
  scheduledLocalTime?: string | null
}

export type VenueBookingCancelRequest = {
  reasonText?: string | null
}

export type VenueBookingMessageRequest = {
  message: string
}

export type VenueBookingStatusResponse = {
  bookingId: number
  status: string
  scheduledAt?: string | null
}

export type VenueBookingMessageResponse = {
  bookingId: number
  queued: boolean
  thread: SupportThreadDto
  message: SupportMessageDto
}

export type VenueBookingSettingsResponse = {
  venueId: number
  holdMinutes: number
  defaultHoldMinutes: number
  minHoldMinutes: number
  maxHoldMinutes: number
  quickHoldMinutes: number[]
}

export type VenueBookingSettingsUpdateRequest = {
  holdMinutes: number
}

export type VenueScheduleDayDto = {
  weekday: number
  opensAt: string
  closesAt: string
  isClosed: boolean
  configured: boolean
}

export type VenueScheduleOverrideDto = {
  serviceDate: string
  opensAt: string
  closesAt: string
  isClosed: boolean
  guestNote?: string | null
}

export type VenueScheduleSettingsResponse = {
  venueId: number
  weeklyHours: VenueScheduleDayDto[]
  dateOverrides: VenueScheduleOverrideDto[]
}

export type VenueScheduleDayUpdateRequest = {
  opensAt?: string | null
  closesAt?: string | null
  isClosed?: boolean
}

export type VenueScheduleOverrideUpdateRequest = {
  opensAt?: string | null
  closesAt?: string | null
  isClosed?: boolean
  guestNote?: string | null
}

export type VenueScheduleOverrideRangeUpdateRequest = {
  fromDate: string
  toDate: string
  opensAt?: string | null
  closesAt?: string | null
  isClosed?: boolean
  guestNote?: string | null
}

export type VenuePublicCardSettingsResponse = {
  venueId: number
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
}

export type VenuePublicCardSettingsUpdateRequest = {
  city?: string | null
  address?: string | null
  countryCode?: string | null
  formattedAddress?: string | null
  latitude?: number | null
  longitude?: number | null
  guestContact?: string | null
  cardDescription?: string | null
}

export type VenueLocationSuggestionKind = 'city' | 'address'

export type VenueLocationSuggestionItem = {
  id: string
  title: string
  subtitle?: string | null
  countryCode?: string | null
  city?: string | null
  address?: string | null
  formattedAddress?: string | null
  providerUri?: string | null
}

export type VenueLocationSuggestionsResponse = {
  items: VenueLocationSuggestionItem[]
  unavailable?: boolean
  message?: string | null
}

export type VenueLocationResolveRequest = {
  providerUri?: string | null
  query?: string | null
  countryCode?: string | null
  city?: string | null
}

export type VenueLocationResolvedItem = {
  countryCode?: string | null
  city?: string | null
  address?: string | null
  formattedAddress?: string | null
  latitude?: number | null
  longitude?: number | null
}

export type VenueLocationResolveResponse = {
  location?: VenueLocationResolvedItem | null
  unavailable?: boolean
  message?: string | null
}

export type VenueTableDto = {
  tableId: number
  tableNumber: number
  tableLabel: string
  isActive: boolean
  activeTokenIssuedAt?: string | null
}

export type VenueTablesResponse = {
  tables: VenueTableDto[]
}

export type VenueTableBatchCreateRequest = {
  count: number
  startNumber?: number
  prefix?: string
}

export type VenueTableCreatedDto = {
  tableId: number
  tableNumber: number
  tableLabel: string
  activeTokenIssuedAt: string
}

export type VenueTableBatchCreateResponse = {
  count: number
  tables: VenueTableCreatedDto[]
}

export type VenueTableTokenRotateResponse = {
  tableId: number
  tableNumber: number
  tableLabel: string
  activeTokenIssuedAt: string
}

export type VenueTableRotateTokensRequest = {
  tableIds?: number[]
}

export type VenueTableRotateTokensResponse = {
  rotatedCount: number
  tableIds: number[]
}

export type VenueMenuResponse = {
  venueId: number
  categories: VenueMenuCategoryDto[]
}

export type VenueMenuCategoryDto = {
  id: number
  name: string
  sortOrder: number
  categoryType: MenuSemanticType
  items: VenueMenuItemDto[]
}

export type VenueMenuItemDto = {
  id: number
  categoryId: number
  name: string
  priceMinor: number
  currency: string
  isAvailable: boolean
  sortOrder: number
  itemType?: MenuSemanticType | null
  effectiveItemType: MenuSemanticType
  supportsBaseFlavorProfiles?: boolean
  missingBaseFlavorProfilesCount?: number
  options: VenueMenuOptionDto[]
}

export type VenueMenuOptionDto = {
  id: number
  itemId: number
  name: string
  priceDeltaMinor: number
  isAvailable: boolean
  sortOrder: number
}

export type VenueApplyBaseFlavorProfilesResponse = {
  itemId: number
  addedCount: number
  existingCount: number
  options: VenueMenuOptionDto[]
}

export type VenueCreateCategoryRequest = {
  name: string
  categoryType?: MenuSemanticType | null
}

export type VenueUpdateCategoryRequest = {
  name?: string | null
  categoryType?: MenuSemanticType | null
}

export type VenueCreateItemRequest = {
  categoryId: number
  name: string
  priceMinor: number
  currency: string
  isAvailable: boolean
  itemType?: MenuSemanticType | null
}

export type VenueUpdateItemRequest = {
  categoryId?: number | null
  name?: string | null
  priceMinor?: number | null
  currency?: string | null
  isAvailable?: boolean | null
  itemType?: MenuSemanticType | null
}

export type MenuSemanticType = 'HOOKAH' | 'TEA' | 'DRINK' | 'FOOD' | 'OTHER' | string

export type VenueAvailabilityRequest = {
  isAvailable: boolean
}

export type VenueReorderCategoriesRequest = {
  categoryIds: number[]
}

export type VenueReorderItemsRequest = {
  categoryId: number
  itemIds: number[]
}

export type VenueCreateOptionRequest = {
  itemId: number
  name: string
  priceDeltaMinor: number
  isAvailable: boolean
}

export type VenueUpdateOptionRequest = {
  name?: string | null
  priceDeltaMinor?: number | null
  isAvailable?: boolean | null
}

export type OrderQueueItemDto = {
  orderId: number
  batchId: number
  displayNumber?: number | null
  activeBatchesCount?: number | null
  tableNumber: string
  tableLabel: string
  createdAt: string
  comment?: string | null
  itemsCount: number
  status: 'new' | 'accepted' | 'cooking' | 'delivering' | 'delivered' | 'closed'
  pendingShiftExtension?: OrderPendingShiftExtensionDto | null
}

export type OrdersQueueResponse = {
  items: OrderQueueItemDto[]
  nextCursor?: string | null
}

export type OrderBatchItemDto = {
  batchItemId: number
  itemId: number
  name: string
  qty: number
  selectedOption?: OrderItemSelectedOptionDto | null
  preferenceNote?: string | null
  priceMinor?: number | null
  currency?: string | null
  lineGrossMinor: number
  manualDiscountMinor: number
  promoDiscountMinor: number
  linePayableMinor: number
  isExcluded: boolean
  excludedReasonText?: string | null
  discountPercent?: number | null
  itemStatus: 'active' | 'canceled'
  canceledReasonCode?: string | null
  canceledReasonText?: string | null
  canceledAt?: string | null
  canceledByUserId?: number | null
}

export type OrderBillDiscountDto = {
  label: string
  discountMinor: number
  currency: string
  ruleType?: string | null
}

export type OrderBillServiceChargeDto = {
  id: number
  source: string
  sourceRequestId?: number | null
  label: string
  qty: number
  unitPriceMinor: number
  totalMinor: number
  currency: string
}

export type OrderBillExcludedItemDto = {
  batchId: number
  batchLabel: string
  tabId?: number | null
  tabType?: 'PERSONAL' | 'SHARED' | string | null
  tabDisplayLabel?: string | null
  batchItemId: number
  itemId: number
  name: string
  qty: number
  selectedOption?: OrderItemSelectedOptionDto | null
  preferenceNote?: string | null
  lineGrossMinor: number
  currency: string
  status: 'excluded' | 'canceled' | 'rejected_batch'
  reason?: string | null
}

export type OrderItemSelectedOptionDto = {
  optionId?: number | null
  name: string
  priceDeltaMinor: number
}

export type OrderBillDto = {
  grossTotalMinor: number
  manualDiscountTotalMinor: number
  promoDiscountTotalMinor: number
  loyaltyDiscountTotalMinor: number
  excludedTotalMinor: number
  canceledTotalMinor: number
  rejectedTotalMinor: number
  finalPayableTotalMinor: number
  currency: string
  promoDiscounts: OrderBillDiscountDto[]
  loyaltyDiscounts: OrderBillDiscountDto[]
  excludedItems: OrderBillExcludedItemDto[]
  serviceCharges?: OrderBillServiceChargeDto[]
}

export type OrderBatchDto = {
  batchId: number
  tabId?: number | null
  tabType?: 'PERSONAL' | 'SHARED' | string | null
  tabDisplayLabel?: string | null
  status: 'new' | 'accepted' | 'cooking' | 'delivering' | 'delivered' | 'closed'
  source: string
  comment?: string | null
  createdAt: string
  updatedAt: string
  rejectedReasonCode?: string | null
  rejectedReasonText?: string | null
  promotionDiscounts: OrderBillDiscountDto[]
  items: OrderBatchItemDto[]
}

export type OrderDetailDto = {
  orderId: number
  displayNumber?: number | null
  displayDate?: string | null
  venueId: number
  tableId: number
  tableNumber: string
  tableLabel: string
  status: 'new' | 'accepted' | 'cooking' | 'delivering' | 'delivered' | 'closed'
  createdAt: string
  updatedAt: string
  bill: OrderBillDto
  batches: OrderBatchDto[]
  pendingShiftExtension?: OrderPendingShiftExtensionDto | null
}

export type OrderPendingShiftExtensionDto = {
  requestId: number
  orderId: number
  tableSessionId: number
  tabId: number
  tableId: number
  tableNumber: string
  tableLabel: string
  durationMinutes: number
  priceMinor: number
  currency: string
  requestedAt: string
  status: string
}

export type OrderDetailResponse = {
  order: OrderDetailDto
}

export type OrderStatusRequest = {
  nextStatus: 'new' | 'accepted' | 'cooking' | 'delivering' | 'delivered' | 'closed'
}

export type OrderStatusResponse = {
  orderId: number
  status: 'new' | 'accepted' | 'cooking' | 'delivering' | 'delivered' | 'closed'
  updatedAt: string
}

export type OrderRejectRequest = {
  reasonCode: string
  reasonText?: string
}

export type OrderBillItemExcludeRequest = {
  reasonText: string
}

export type OrderBillItemDiscountRequest = {
  discountPercent: number
}

export type OrderBillItemAdjustmentResponse = {
  order: OrderDetailDto
}

export type OrderAuditEntryDto = {
  orderId: number
  actorUserId: number
  actorRole: string
  action: string
  fromStatus: string
  toStatus: string
  reasonCode?: string | null
  reasonText?: string | null
  createdAt: string
}

export type OrderAuditResponse = {
  items: OrderAuditEntryDto[]
}

export type ShiftExtensionRequestDto = {
  id: number
  venueId: number
  tableSessionId: number
  tableId: number
  tableNumber?: string | null
  tabId: number
  orderId: number
  requestedByUserId: number
  status: string
  durationMinutes: number
  priceMinor: number
  currency: string
  currentOrderableUntil: string
  requestedUntil: string
  comment?: string | null
  decidedByUserId?: number | null
  decidedAt?: string | null
  rejectReason?: string | null
  createdAt: string
  updatedAt: string
}

export type ShiftExtensionRequestsResponse = {
  items: ShiftExtensionRequestDto[]
}

export type ShiftExtensionDecisionRequest = {
  reasonText?: string | null
}

export type ShiftExtensionDecisionResponse = {
  request: ShiftExtensionRequestDto
  applied: boolean
}

export type ShiftExtensionSettingsDto = {
  venueId: number
  enabled: boolean
  durationMinutes: number
  priceMinor?: number | null
  priceRub?: string | null
  currency: string
  maxExtensionsPerSession?: number | null
  configured: boolean
}

export type ShiftExtensionSettingsResponse = {
  settings: ShiftExtensionSettingsDto
}

export type ShiftExtensionSettingsUpdateRequest = {
  enabled: boolean
  durationMinutes: number
  priceMinor?: number | null
  priceRub?: string | null
  currency?: string | null
  maxExtensionsPerSession?: number | null
}
