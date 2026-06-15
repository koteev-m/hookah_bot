// Backend Json uses explicitNulls=true and encodeDefaults=false, so nullable defaults can be omitted, while nullable fields without defaults are always present.
export type CatalogResponse = {
  venues: CatalogVenueDto[]
}

export type CatalogVenueDto = {
  id: number
  name: string
  city?: string | null
  address?: string | null
  guestContact?: string | null
  cardDescription?: string | null
}

export type VenueResponse = {
  venue: VenueDto
}

export type VenueDto = {
  id: number
  name: string
  city?: string | null
  address?: string | null
  guestContact?: string | null
  cardDescription?: string | null
  status: string
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

export type AddBatchRequest = {
  tableToken: string
  tableSessionId: number
  tabId: number
  idempotencyKey: string
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
  itemId: number
  qty: number
  selectedOptionId?: number | null
  preferenceNote?: string | null
}

export type AddBatchResponse = {
  orderId: number
  batchId: number
}

export type CartPreviewRequest = {
  tableToken: string
  tableSessionId: number
  tabId: number
  items: AddBatchItemDto[]
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
}

export type CartPreviewDiscountDto = {
  label: string
  discountMinor: number
  currency: string
  ruleType?: string | null
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

export type GuestBookingConfirmRequest = {
  bookingId: number
}

export type GuestBookingResponse = {
  bookingId: number
  venueId: number
  status: string
  scheduledAt: string
  partySize?: number | null
  comment?: string | null
  lastGuestConfirmationAt?: string | null
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
  items: GuestVisitOrderItemDto[]
  promotionDiscounts: GuestVisitPromotionDiscountDto[]
}

export type GuestVisitOrderItemDto = {
  itemId: number
  itemName: string
  qty: number
  priceMinor?: number | null
  currency?: string | null
  discountPercent?: number | null
  totalMinor?: number | null
}

export type GuestVisitPromotionDiscountDto = {
  label: string
  discountMinor: number
  currency: string
}

export type GuestFavoriteVenuesResponse = {
  venues: GuestFavoriteVenueDto[]
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
