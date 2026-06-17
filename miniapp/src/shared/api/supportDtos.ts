export type SupportBookingContextDto = {
  bookingId: number
  displayNumber?: number | null
  scheduledAt?: string | null
  partySize?: number | null
  status?: string | null
}

export type SupportThreadDto = {
  threadId: number
  venueId: number
  venueName?: string | null
  guestDisplayName?: string | null
  category: 'BOOKING' | 'GENERAL' | 'ORDER' | 'TABLE' | 'PLATFORM' | string
  contextLabel?: string | null
  status: 'OPEN' | 'RESOLVED' | 'CLOSED' | string
  bookingId?: number | null
  orderId?: number | null
  tableSessionId?: number | null
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
  source: 'GUEST_BOT' | 'GUEST_MINIAPP' | 'VENUE_MINIAPP' | 'STAFF_CHAT' | 'SYSTEM' | string
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

export type SupportMessageCreateResponse = {
  thread: SupportThreadDto
  message: SupportMessageDto
  queued: boolean
}
