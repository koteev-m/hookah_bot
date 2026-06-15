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

export type StaffChatLinkCodeResponse = {
  code: string
  expiresAt: string
  ttlSeconds: number
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

export type VenueStaffInviteRequest = {
  role: 'OWNER' | 'MANAGER' | 'STAFF'
  expiresIn?: number
}

export type VenueStaffInviteResponse = {
  inviteCode: string
  expiresAt: string
  ttlSeconds: number
  instructions: string
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
  linkedAt?: string | null
  linkedByUserId?: number | null
  activeCodeHint?: string | null
  activeCodeExpiresAt?: string | null
}

export type VenueStaffCallDto = {
  id: number
  tableId?: number | null
  tableNumber: number
  reason: string
  reasonLabel: string
  comment?: string | null
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
  partySize?: number | null
  comment?: string | null
  guestDisplayName?: string | null
  lastGuestConfirmationAt?: string | null
}

export type VenueBookingListResponse = {
  items: VenueBookingDto[]
}

export type VenueBookingChangeRequest = {
  scheduledAt: string
}

export type VenueBookingCancelRequest = {
  reasonText?: string | null
}

export type VenueBookingStatusResponse = {
  bookingId: number
  status: string
  scheduledAt?: string | null
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

export type VenueCreateCategoryRequest = {
  name: string
}

export type VenueUpdateCategoryRequest = {
  name?: string | null
}

export type VenueCreateItemRequest = {
  categoryId: number
  name: string
  priceMinor: number
  currency: string
  isAvailable: boolean
}

export type VenueUpdateItemRequest = {
  categoryId?: number | null
  name?: string | null
  priceMinor?: number | null
  currency?: string | null
  isAvailable?: boolean | null
}

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
