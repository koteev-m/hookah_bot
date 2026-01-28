export type VenueAccessDto = {
  venueId: number
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
  tableNumber: string
  tableLabel: string
  createdAt: string
  comment?: string | null
  itemsCount: number
  status: 'new' | 'accepted' | 'cooking' | 'delivering' | 'delivered' | 'closed'
}

export type OrdersQueueResponse = {
  items: OrderQueueItemDto[]
  nextCursor?: string | null
}

export type OrderBatchItemDto = {
  itemId: number
  name: string
  qty: number
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
  items: OrderBatchItemDto[]
}

export type OrderDetailDto = {
  orderId: number
  venueId: number
  tableId: number
  tableNumber: string
  tableLabel: string
  status: 'new' | 'accepted' | 'cooking' | 'delivering' | 'delivered' | 'closed'
  createdAt: string
  updatedAt: string
  batches: OrderBatchDto[]
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
