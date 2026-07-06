export type SupportBookingContextDto = {
  bookingId: number
  displayNumber?: number | null
  scheduledAt?: string | null
  partySize?: number | null
  status?: string | null
}

export type SupportThreadDto = {
  threadId: number
  venueId?: number | null
  venueName?: string | null
  guestDisplayName?: string | null
  threadType: 'BOOKING_THREAD' | 'SUPPORT_TICKET' | string
  assigneeScope: 'VENUE' | 'PLATFORM' | string
  category: 'BOOKING' | 'ORDER_SERVICE' | 'MINIAPP_TECHNICAL' | 'BILLING' | 'OTHER' | string
  contextLabel?: string | null
  status: 'OPEN' | 'NEW' | 'IN_PROGRESS' | 'WAITING_USER' | 'RESOLVED' | 'CLOSED' | string
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
  booking?: SupportBookingContextDto | null
}

export type SupportThreadFilter = 'active' | 'resolved'

export type SupportMessageDto = {
  messageId: number
  threadId: number
  authorRole: 'GUEST' | 'VENUE' | 'PLATFORM' | 'SYSTEM' | string
  source: 'GUEST_BOT' | 'GUEST_MINIAPP' | 'VENUE_MINIAPP' | 'PLATFORM_MINIAPP' | 'STAFF_CHAT' | 'SYSTEM' | string
  text: string
  createdAt: string
}

export type SupportThreadListResponse = {
  items: SupportThreadDto[]
}

export type SupportThreadDetailResponse = {
  thread: SupportThreadDto
  messages: SupportMessageDto[]
}

export type SupportMessageCreateRequest = {
  message: string
}

export type SupportThreadCreateRequest = {
  category: 'ORDER_SERVICE' | 'MINIAPP_TECHNICAL' | 'BOOKING' | 'BILLING' | 'OTHER'
  title?: string | null
  message: string
  venueId?: number | null
  tableToken?: string | null
  tableSessionId?: number | null
  orderId?: number | null
  bookingId?: number | null
  appVersion?: string | null
  correlationId?: string | null
}

export type SupportMessageCreateResponse = {
  thread: SupportThreadDto
  message: SupportMessageDto
  queued: boolean
}

export type SupportThreadCreateResponse = {
  thread: SupportThreadDto
  message: SupportMessageDto
  queued: boolean
}

export type SupportAssigneeScopeRequest = {
  assigneeScope: 'VENUE' | 'PLATFORM'
}

export type SupportStatusChangeRequest = {
  status: 'NEW' | 'IN_PROGRESS' | 'WAITING_USER' | 'RESOLVED' | 'CLOSED'
}
