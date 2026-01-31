export type PlatformMeResponse = {
  ok: boolean
  ownerUserId: number
}

export type PlatformUserDto = {
  userId: number
  username?: string | null
  displayName: string
  lastSeenAt: string
}

export type PlatformUserListResponse = {
  users: PlatformUserDto[]
}

export type PlatformVenueSummaryDto = {
  id: number
  name: string
  status: string
  createdAt: string
  ownersCount: number
  subscriptionSummary: PlatformSubscriptionSummaryDto | null
}

export type PlatformVenueListResponse = {
  venues: PlatformVenueSummaryDto[]
}

export type PlatformVenueCreateRequest = {
  name: string
  city?: string | null
  address?: string | null
}

export type PlatformVenueDetailDto = {
  id: number
  name: string
  city?: string | null
  address?: string | null
  status: string
  createdAt: string
  deletedAt?: string | null
}

export type PlatformVenueOwnerDto = {
  userId: number
  role: string
  username?: string | null
  firstName?: string | null
  lastName?: string | null
}

export type PlatformSubscriptionSummaryDto = {
  trialEndDate?: string | null
  paidStartDate?: string | null
  isPaid?: boolean | null
}

export type PlatformVenueResponse = {
  venue: PlatformVenueDetailDto
}

export type PlatformVenueDetailResponse = {
  venue: PlatformVenueDetailDto
  owners: PlatformVenueOwnerDto[]
  subscriptionSummary: PlatformSubscriptionSummaryDto | null
}

export type PlatformSubscriptionSettingsDto = {
  trialEndDate?: string | null
  paidStartDate?: string | null
  basePriceMinor?: number | null
  priceOverrideMinor?: number | null
  currency: string
}

export type PlatformPriceScheduleItemDto = {
  effectiveFrom: string
  priceMinor: number
  currency: string
}

export type PlatformEffectivePriceDto = {
  priceMinor: number
  currency: string
}

export type PlatformSubscriptionSettingsResponse = {
  settings: PlatformSubscriptionSettingsDto
  schedule: PlatformPriceScheduleItemDto[]
  effectivePriceToday?: PlatformEffectivePriceDto | null
}

export type PlatformSubscriptionSettingsUpdateRequest = {
  trialEndDate?: string | null
  paidStartDate?: string | null
  basePriceMinor?: number | null
  priceOverrideMinor?: number | null
  currency?: string | null
}

export type PlatformPriceScheduleUpdateRequest = {
  items: PlatformPriceScheduleItemInput[]
}

export type PlatformPriceScheduleItemInput = {
  effectiveFrom: string
  priceMinor: number
  currency: string
}

export type PlatformPriceScheduleResponse = {
  items: PlatformPriceScheduleItemDto[]
}

export type PlatformVenueStatusChangeRequest = {
  action: string
}

export type PlatformOwnerAssignRequest = {
  userId: number
  role?: string | null
}

export type PlatformOwnerAssignResponse = {
  ok: boolean
  alreadyMember: boolean
  role: string
}

export type PlatformOwnerInviteRequest = {
  ttlSeconds?: number | null
}

export type PlatformOwnerInviteResponse = {
  code: string
  expiresAt: string
  instructions: string
  deepLink?: string | null
}
