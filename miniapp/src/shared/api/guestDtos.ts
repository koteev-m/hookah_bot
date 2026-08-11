// Backend Json uses explicitNulls=true and encodeDefaults=false, so nullable defaults can be omitted, while nullable fields without defaults are always present.
export type CatalogResponse = {
  venues: CatalogVenueDto[]
}

export type CatalogVenueDto = {
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
  todaySchedule?: VenueTodayScheduleDto | null
  isFavorite: boolean
}

export type VenueResponse = {
  venue: VenueDto
}

export type VenueDto = {
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
  todaySchedule?: VenueTodayScheduleDto | null
  weeklyHours?: GuestVenueScheduleDayDto[]
  dateExceptions?: GuestVenueDateExceptionDto[]
  todayStaff?: GuestTodayStaffDto[]
  timezone?: string | null
  promotions?: GuestVenuePromotionDto[]
  status: string
  isFavorite: boolean
}

export type GuestVenuePromotionDto = {
  id: number
  title: string
  description: string
  terms?: string | null
  startsAt?: string | null
  endsAt?: string | null
  templateType?: 'TEXT_ONLY' | 'HAPPY_HOURS_PERCENT' | string
}

export type GuestTodayStaffResponse = {
  venueId: number
  staff: GuestTodayStaffDto[]
}

export type GuestTodayStaffDto = {
  id: number
  displayName: string
  roleLabel?: string | null
  subtype: 'hookah_master' | 'waiter' | 'admin' | 'other' | string
  photoRef?: string | null
  bio?: string | null
  tags?: string[]
  shiftDate: string
  startsAt?: string | null
  endsAt?: string | null
  shiftStatus: string
}

export type VenueTodayScheduleDto = {
  date: string
  opensAt?: string | null
  closesAt?: string | null
  isConfigured?: boolean
  isClosed: boolean
  isOpenNow: boolean
  statusLabel: string
  timeLabel?: string | null
}

export type GuestVenueScheduleDayDto = {
  weekday: number
  opensAt: string
  closesAt: string
  isClosed: boolean
}

export type GuestVenueDateExceptionDto = {
  serviceDate: string
  opensAt: string
  closesAt: string
  isClosed: boolean
  guestNote?: string | null
}

export type VenueInfoSectionsResponse = {
  venueId: number
  sections: VenueInfoSectionDto[]
}

export type VenueInfoSectionDto = {
  id: number
  type: string
  title: string
  displayTitle: string
  text?: string | null
  mediaCount?: number | null
  media?: VenueInfoSectionMediaDto[] | null
}

export type VenueInfoSectionMediaDto = {
  id: number
  mediaType: string
  sortOrder: number
  url?: string | null
}

export type MenuResponse = {
  venueId: number
  categories: MenuCategoryDto[]
}

export type MenuCategoryDto = {
  id: number
  name: string
  categoryType?: MenuSemanticType | null
  items: MenuItemDto[]
}

export type MenuItemDto = {
  id: number
  name: string
  priceMinor: number
  currency: string
  isAvailable: boolean
  itemType?: MenuSemanticType | null
  effectiveItemType?: MenuSemanticType | null
  options?: MenuItemOptionDto[]
}

export type MenuItemOptionDto = {
  id: number
  name: string
  priceDeltaMinor: number
  isAvailable?: boolean
}

export type MenuSemanticType = 'HOOKAH' | 'TEA' | 'DRINK' | 'FOOD' | 'OTHER' | string

export type TableResolveResponse = {
  venueId: number
  venueName: string
  tableId: number
  tableSessionId: number
  tableSessionStatus: string
  tableSessionActive: boolean
  tableSessionInactiveReason?: string | null
  tableNumber: string
  venueStatus: string
  subscriptionStatus: string
  available: boolean
  unavailableReason: string | null
}

export type TableRestoreContextResponse = TableResolveResponse & {
  tableToken: string
  tabId: number
}

export type TableRestoreResponse = {
  context: TableRestoreContextResponse | null
}

export type TableSessionEndBlockedReason = 'ACTIVE_ORDER' | 'ACTIVE_STAFF_CALL'

export type TableSessionEndRequest = {
  tableToken: string
  tableSessionId: number
}

export type TableSessionEndResponse = {
  ended: boolean
  tableSessionId: number
  blockedReason: TableSessionEndBlockedReason | null
  message: string | null
}

export type ActiveOrderResponse = {
  order: ActiveOrderDto | null
}

export type ActiveOrderDto = {
  orderId: number
  displayNumber?: number | null
  displayDate?: string | null
  venueId: number
  tableId: number
  tableSessionId?: number | null
  tabId?: number | null
  tableNumber: string
  status: string
  grossTotalMinor: number
  manualDiscountTotalMinor: number
  promoDiscountTotalMinor: number
  loyaltyDiscountTotalMinor: number
  finalPayableTotalMinor: number
  currency: string
  discounts: ActiveOrderDiscountDto[]
  serviceCharges?: ActiveOrderServiceChargeDto[]
  batches: OrderBatchDto[]
}

export type ActiveOrderDiscountDto = {
  label: string
  discountMinor: number
  currency: string
  ruleType?: string | null
}

export type ActiveOrderServiceChargeDto = {
  id: number
  source: string
  sourceRequestId?: number | null
  label: string
  qty: number
  unitPriceMinor: number
  totalMinor: number
  currency: string
}

export type OrderBatchDto = {
  batchId: number
  status: string
  comment: string | null
  items: OrderBatchItemDto[]
}

export type OrderBatchItemDto = {
  itemId: number
  qty: number
  name?: string | null
  selectedOption?: SelectedOrderItemOptionDto | null
  preferenceNote?: string | null
  priceMinor?: number | null
  currency?: string | null
  lineGrossMinor: number
  manualDiscountMinor: number
  promoDiscountMinor: number
  linePayableMinor: number
  isPromotionReward: boolean
}

export type GiftOfferStatus =
  | 'NO_GIFT'
  | 'FIXED_GIFT_AVAILABLE'
  | 'GIFT_CHOICE_REQUIRED'
  | 'GIFT_UNAVAILABLE'
  | 'GIFT_SKIPPED'
  | 'GIFT_SELECTED'

export type GiftDecisionAction = 'ACCEPT_FIXED' | 'SELECT_ITEM' | 'SKIP'

export type GiftRewardItemDto = {
  menuItemId: number
  name: string
  originalUnitPriceMinor: number
  currency: string
}

export type GiftOfferDto = {
  status: GiftOfferStatus
  promotionId?: number | null
  promotionTitle?: string | null
  ruleId?: number | null
  ruleVersion?: number | null
  triggerLineId?: number | null
  triggerMenuItemId?: number | null
  triggerItemName?: string | null
  fixedRewardItem?: GiftRewardItemDto | null
  selectableRewardItems?: GiftRewardItemDto[]
  selectedRewardItem?: GiftRewardItemDto | null
  unavailableReason?: string | null
}

export type GiftDecisionDto = {
  action: GiftDecisionAction
  selectedMenuItemId?: number | null
  decisionScopeToken: string
}

export type AddBatchRequest = {
  tableToken: string
  tableSessionId: number
  tabId: number
  idempotencyKey: string
  previewFingerprint?: string | null
  giftDecision?: GiftDecisionDto | null
  items: AddBatchItemDto[]
  comment?: string | null
}

export type GuestTabDto = {
  id: number
  tableSessionId: number
  type: string
  ownerUserId?: number | null
  status: string
}

export type GuestTabsResponse = {
  tabs: GuestTabDto[]
}

export type GuestTabResponse = {
  tab: GuestTabDto
}

export type CreateSharedTabRequest = {
  tableSessionId: number
}

export type CreateTabInviteRequest = {
  tableSessionId: number
  ttlSeconds?: number | null
}

export type CreateTabInviteResponse = {
  tabId: number
  token: string
  expiresAtEpochSeconds: number
}

export type JoinTabRequest = {
  tableSessionId: number
  token: string
  consent: boolean
}

export type AddBatchItemDto = {
  cartLineRef?: string | null
  itemId: number
  qty: number
  selectedOptionId?: number | null
  preferenceNote?: string | null
}

export type CartMenuSelectionKind = 'ITEM' | 'OPTION'

export type CartMenuSelectionReason = 'REMOVED' | 'UNAVAILABLE'

export type CartMenuSelectionIssue = {
  cartLineRef: string
  itemId: number
  optionId: number | null
  selectionKind: CartMenuSelectionKind
  reason: CartMenuSelectionReason
}

export type AddBatchResponse = {
  submitted?: boolean
  orderId?: number | null
  batchId?: number | null
  pricing: CartPreviewDto
  recalculated: boolean
}

export type CartPreviewRequest = {
  tableToken: string
  tableSessionId: number
  tabId: number
  giftDecision?: GiftDecisionDto | null
  items: AddBatchItemDto[]
  comment?: string | null
}

export type CartPreviewResponse = {
  preview: CartPreviewDto
}

export type CartPreviewDto = {
  grossTotalMinor: number
  promoDiscountTotalMinor: number
  loyaltyDiscountTotalMinor: number
  finalPayableTotalMinor: number
  currency: string
  discounts: CartPreviewDiscountDto[]
  items: CartPreviewItemDto[]
  pricingFingerprint: string
  cartFingerprint: string
  decisionScopeToken?: string | null
  decisionScopeExpiresAtEpochSeconds?: number | null
  giftDecisionStale?: boolean
  giftDecisionMessage?: string | null
  giftOffer?: GiftOfferDto | null
}

export type CartPreviewDiscountDto = {
  label: string
  discountMinor: number
  currency: string
  ruleType?: string | null
  promotionId: number | null
  ruleId: number | null
  ruleVersion: number | null
  originalAmountMinor: number | null
  finalAmountMinor: number | null
  eligibleLineIds: number[]
}

export type CartPreviewPromotionAdjustmentDto = {
  promotionId: number | null
  promotionTitle: string
  ruleId: number
  ruleVersion: number
  ruleType: string
  originalAmountMinor: number
  discountMinor: number
  finalAmountMinor: number
}

export type CartPreviewItemDto = {
  itemId: number
  name: string
  qty: number
  selectedOption?: SelectedOrderItemOptionDto | null
  preferenceNote?: string | null
  priceMinor: number
  currency: string
  lineGrossMinor: number
  discountMinor: number
  linePayableMinor: number
  isPromotionReward: boolean
  promotionAdjustment?: CartPreviewPromotionAdjustmentDto | null
}

export type SelectedOrderItemOptionDto = {
  optionId: number
  name: string
  priceDeltaMinor: number
}

export type StaffCallRequest = {
  tableToken: string
  tableSessionId: number
  reason: string
  comment?: string | null
}

export type StaffCallResponse = {
  staffCallId: number
  createdAtEpochSeconds: number
  status: string
  statusLabel: string
}

export type BillPaymentMethod = 'CARD' | 'CASH' | 'UNKNOWN'

export type GuestBillRequestRequest = {
  tableToken: string
  tableSessionId: number
  tabId: number
  paymentMethod: BillPaymentMethod
}

export type GuestBillRequestResponse = {
  staffCallId: number
  createdAtEpochSeconds: number
  status: string
  statusLabel: string
  paymentMethod: BillPaymentMethod | string
  paymentMethodLabel: string
  alreadyActive: boolean
  message: string
}

export type StaffCallStatusResponse = {
  items: StaffCallStatusDto[]
}

export type StaffCallStatusDto = {
  staffCallId: number
  status: 'NEW' | 'ACK' | 'DONE' | 'CANCELLED' | string
  statusLabel: string
  createdAtEpochSeconds: number
  reason: string
  reasonLabel: string
  comment?: string | null
}

export type GuestShiftExtensionOptionsResponse = {
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
  pendingRequest?: ShiftExtensionRequestDto | null
}

export type GuestShiftExtensionRequest = {
  tableToken: string
  tableSessionId: number
  tabId: number
  idempotencyKey?: string | null
  comment?: string | null
}

export type ShiftExtensionRequestResponse = {
  request: ShiftExtensionRequestDto
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

export type GuestVisitListResponse = {
  items: GuestVisitListItemDto[]
}

export type GuestVisitListItemDto = {
  visitId: number
  venueId: number
  venueName: string
  venueCity?: string | null
  occurredAt: string
  serviceDate?: string | null
  source: string
  totalMinor?: number | null
  currency?: string | null
  hasBooking: boolean
  orderLabels: string[]
}

export type GuestVisitDetailResponse = {
  visit: GuestVisitDetailDto
}

export type GuestVisitDetailDto = {
  visitId: number
  venueId: number
  venueName: string
  venueCity?: string | null
  occurredAt: string
  serviceDate?: string | null
  source: string
  totalMinor?: number | null
  currency?: string | null
  booking?: GuestVisitBookingDto | null
  orders: GuestVisitOrderDto[]
  feedback?: GuestVisitFeedbackDto | null
}

export type GuestVisitFeedbackDto = {
  eligible: boolean
  submitted: boolean
  rating?: number | null
  tags?: string[]
  comment?: string | null
  publicReviewUrl?: string | null
}

export type GuestVisitFeedbackSubmitRequest = {
  rating: number
  tags?: string[]
  comment?: string | null
}

export type GuestVisitFeedbackSubmitResponse = {
  feedback: GuestVisitFeedbackDto
}

export type GuestVisitBookingDto = {
  bookingId: number
  displayNumber?: number | null
  partySize?: number | null
  status: string
}

export type GuestBookingCreateRequest = {
  venueId: number
  scheduledAt: string
  partySize?: number | null
  comment?: string | null
}

export type GuestBookingCancelRequest = {
  bookingId: number
}

export type GuestBookingUpdateRequest = {
  bookingId: number
  scheduledAt: string
  partySize?: number | null
  comment?: string | null
}

export type GuestBookingConfirmRequest = {
  bookingId: number
  attendanceScheduleVersion?: number | null
}

export type GuestBookingResponse = {
  bookingId: number
  venueId: number
  status: string
  scheduledAt: string
  partySize?: number | null
  comment?: string | null
  lastGuestConfirmationAt?: string | null
  attendanceScheduleVersion?: number | null
  displayNumber?: number | null
  displayLabel?: string | null
  venueName?: string | null
  statusLabel?: string | null
  scheduledAtDisplay?: string | null
  scheduledLocalDate?: string | null
  scheduledLocalTime?: string | null
  arrivalDeadlineAt?: string | null
  arrivalDeadlineAtDisplay?: string | null
  arrivalDeadlineTimeDisplay?: string | null
  canChange?: boolean | null
  canCancel?: boolean | null
}

export type GuestBookingListResponse = {
  items: GuestBookingResponse[]
}

export type GuestVisitOrderDto = {
  orderId: number
  displayNumber?: number | null
  displayDate?: string | null
  totalMinor?: number | null
  currency?: string | null
  items?: GuestVisitOrderItemDto[] | null
  promotionDiscounts?: GuestVisitPromotionDiscountDto[] | null
}

export type GuestVisitOrderItemDto = {
  itemId?: number | null
  itemName?: string | null
  qty?: number | null
  selectedOption?: GuestVisitOrderItemOptionDto | null
  preferenceNote?: string | null
  priceMinor?: number | null
  currency?: string | null
  discountPercent?: number | null
  totalMinor?: number | null
  promoDiscountMinor?: number | null
  isPromotionReward?: boolean | null
  isExcluded?: boolean | null
  excludedReasonText?: string | null
  itemStatus?: string | null
  canceledReasonText?: string | null
  promotionLinkRole?: 'TRIGGER' | 'REWARD' | string | null
  promotionLabel?: string | null
}

export type GuestVisitOrderItemOptionDto = {
  name: string
  priceDeltaMinor: number
}

export type GuestVisitRepeatPlanRequest = {
  tableSessionId: number
  tabId: number
  orderId?: number | null
}

export type GuestVisitRepeatPlanResponse = {
  eligibleLines: GuestVisitRepeatEligibleLineDto[]
  skippedLines: GuestVisitRepeatSkippedLineDto[]
  currentTotal: GuestVisitRepeatMoneyDto
  sourceOrderId: number
  venueId: number
}

export type GuestVisitRepeatEligibleLineDto = {
  itemId: number
  itemName: string
  quantity: number
  selectedOption?: GuestVisitRepeatOptionDto | null
  preferenceNote?: string | null
  currentItemPrice: GuestVisitRepeatMoneyDto
  currentUnitPrice: GuestVisitRepeatMoneyDto
  currentLineTotal: GuestVisitRepeatMoneyDto
}

export type GuestVisitRepeatOptionDto = {
  optionId: number
  name: string
  currentPriceDelta: GuestVisitRepeatMoneyDto
}

export type GuestVisitRepeatSkippedLineDto = {
  itemName: string
  quantity: number
  selectedOptionName?: string | null
  reason: string
  message: string
}

export type GuestVisitRepeatMoneyDto = {
  amountMinor: number
  currency: string
}

export type GuestVisitPromotionDiscountDto = {
  label: string
  discountMinor: number
  currency: string
  originalAmountMinor?: number | null
  finalAmountMinor?: number | null
  isActive?: boolean | null
}

export type GuestFavoriteVenuesResponse = {
  venues: GuestFavoriteVenueDto[]
}

export type GuestFavoriteMutationResponse = {
  ok: boolean
}

export type GuestFavoriteVenueDto = {
  venueId: number
  name: string
  city?: string | null
  address?: string | null
}

export type GuestFavoriteItemsResponse = {
  items: GuestFavoriteItemDto[]
}

export type GuestFavoriteItemDto = {
  itemId: number
  venueId: number
  categoryId: number
  name: string
  priceMinor: number
  currency: string
}
