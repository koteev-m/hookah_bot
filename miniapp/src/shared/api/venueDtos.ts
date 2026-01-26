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
