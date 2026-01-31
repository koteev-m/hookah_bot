import { requestApi, type RequestDependencies } from './request'
import type {
  PlatformMeResponse,
  PlatformOwnerAssignRequest,
  PlatformOwnerAssignResponse,
  PlatformOwnerInviteRequest,
  PlatformOwnerInviteResponse,
  PlatformPriceScheduleResponse,
  PlatformPriceScheduleUpdateRequest,
  PlatformSubscriptionSettingsResponse,
  PlatformSubscriptionSettingsUpdateRequest,
  PlatformUserListResponse,
  PlatformVenueCreateRequest,
  PlatformVenueDetailResponse,
  PlatformVenueListResponse,
  PlatformVenueResponse,
  PlatformVenueStatusChangeRequest
} from './platformDtos'

export async function platformGetMe(
  backendUrl: string,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<PlatformMeResponse>(backendUrl, '/api/platform/me', { signal }, deps)
}

export async function platformListVenues(
  backendUrl: string,
  params: {
    status?: string | null
    subscription?: string | null
    q?: string | null
    limit?: number | null
    offset?: number | null
  },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams()
  if (params.status) search.set('status', params.status)
  if (params.subscription) search.set('subscription', params.subscription)
  if (params.q) search.set('q', params.q)
  if (params.limit) search.set('limit', String(params.limit))
  if (params.offset) search.set('offset', String(params.offset))
  const suffix = search.toString()
  return requestApi<PlatformVenueListResponse>(
    backendUrl,
    `/api/platform/venues${suffix ? `?${suffix}` : ''}`,
    { signal },
    deps
  )
}

export async function platformCreateVenue(
  backendUrl: string,
  body: PlatformVenueCreateRequest,
  deps: RequestDependencies
) {
  return requestApi<PlatformVenueResponse>(
    backendUrl,
    '/api/platform/venues',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    deps
  )
}

export async function platformGetVenue(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<PlatformVenueDetailResponse>(
    backendUrl,
    `/api/platform/venues/${venueId}`,
    { signal },
    deps
  )
}

export async function platformChangeVenueStatus(
  backendUrl: string,
  venueId: number,
  action: string,
  deps: RequestDependencies
) {
  const body: PlatformVenueStatusChangeRequest = { action }
  return requestApi<PlatformVenueResponse>(
    backendUrl,
    `/api/platform/venues/${venueId}/status`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    deps
  )
}

export async function platformSearchUsers(
  backendUrl: string,
  q: string,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ q })
  return requestApi<PlatformUserListResponse>(
    backendUrl,
    `/api/platform/users?${search.toString()}`,
    { signal },
    deps
  )
}

export async function platformAssignOwner(
  backendUrl: string,
  venueId: number,
  params: PlatformOwnerAssignRequest,
  deps: RequestDependencies
) {
  return requestApi<PlatformOwnerAssignResponse>(
    backendUrl,
    `/api/platform/venues/${venueId}/owners`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params)
    },
    deps
  )
}

export async function platformCreateOwnerInvite(
  backendUrl: string,
  venueId: number,
  params: PlatformOwnerInviteRequest,
  deps: RequestDependencies
) {
  return requestApi<PlatformOwnerInviteResponse>(
    backendUrl,
    `/api/platform/venues/${venueId}/owner-invite`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params)
    },
    deps
  )
}

export async function platformGetSubscription(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<PlatformSubscriptionSettingsResponse>(
    backendUrl,
    `/api/platform/venues/${venueId}/subscription`,
    { signal },
    deps
  )
}

export async function platformUpdateSubscription(
  backendUrl: string,
  venueId: number,
  body: PlatformSubscriptionSettingsUpdateRequest,
  deps: RequestDependencies
) {
  return requestApi<PlatformSubscriptionSettingsResponse>(
    backendUrl,
    `/api/platform/venues/${venueId}/subscription`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    deps
  )
}

export async function platformUpdatePriceSchedule(
  backendUrl: string,
  venueId: number,
  body: PlatformPriceScheduleUpdateRequest,
  deps: RequestDependencies
) {
  return requestApi<PlatformPriceScheduleResponse>(
    backendUrl,
    `/api/platform/venues/${venueId}/price-schedule`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    },
    deps
  )
}
