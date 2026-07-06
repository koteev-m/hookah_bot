import { requestApi, requestBinary, type RequestDependencies } from './request'
import type { OwnerBillingOverviewResponse } from './billingDtos'
import type {
  VenueApplyBaseFlavorProfilesResponse,
  VenueAvailabilityRequest,
  VenueBookingChangeRequest,
  VenueBookingCancelRequest,
  VenueBookingListResponse,
  VenueBookingMessageRequest,
  VenueBookingMessageResponse,
  VenueBookingSettingsResponse,
  VenueBookingSettingsUpdateRequest,
  VenueBookingStatusResponse,
  VenueCreateCategoryRequest,
  VenueCreateItemRequest,
  VenueCreateOptionRequest,
  VenueLocationResolveRequest,
  VenueLocationResolveResponse,
  VenueLocationSuggestionKind,
  VenueLocationSuggestionsResponse,
  VenueTableBatchCreateRequest,
  VenueTableBatchCreateResponse,
  VenueTableRotateTokensRequest,
  VenueTableRotateTokensResponse,
  VenueTableTokenRotateResponse,
  VenueTablesResponse,
  VenueMenuCategoryDto,
  VenueMenuItemDto,
  VenueMenuOptionDto,
  VenueMenuResponse,
  VenuePublicCardSettingsResponse,
  VenuePublicCardSettingsUpdateRequest,
  VenueReorderCategoriesRequest,
  VenueReorderItemsRequest,
  VenueScheduleDayUpdateRequest,
  VenueScheduleOverrideRangeUpdateRequest,
  VenueScheduleOverrideUpdateRequest,
  VenueScheduleSettingsResponse,
  VenueStatsPeriod,
  VenueStatsResponse,
  VenueUpdateCategoryRequest,
  VenueUpdateItemRequest,
  VenueUpdateOptionRequest,
  OrderAuditResponse,
  OrderBillItemAdjustmentResponse,
  OrderBillItemDiscountRequest,
  OrderBillItemExcludeRequest,
  OrderDetailResponse,
  OrdersQueueResponse,
  OrderRejectRequest,
  OrderStatusRequest,
  OrderStatusResponse,
  ShiftExtensionDecisionRequest,
  ShiftExtensionDecisionResponse,
  ShiftExtensionRequestsResponse,
  ShiftExtensionSettingsResponse,
  ShiftExtensionSettingsUpdateRequest,
  StaffChatLinkCodeResponse,
  StaffChatTestResponse,
  VenueStaffCallActionResponse,
  VenueStaffCallsResponse,
  VenueMeResponse,
  VenueStaffChatStatusResponse,
  VenueStaffInviteAcceptRequest,
  VenueStaffInviteAcceptResponse,
  VenueStaffInviteRequest,
  VenueStaffInviteResponse,
  VenueStaffListResponse,
  VenueStaffUpdateRoleRequest
} from './venueDtos'
import type {
  SupportMessageCreateRequest,
  SupportMessageCreateResponse,
  SupportThreadDetailResponse,
  SupportThreadFilter,
  SupportThreadListResponse
} from './supportDtos'

export async function venueGetMe(
  backendUrl: string,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueMeResponse>(backendUrl, '/api/venue/me', { signal }, deps)
}

export async function venueGetStats(
  backendUrl: string,
  params: { venueId: number; period: VenueStatsPeriod },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ period: params.period })
  return requestApi<VenueStatsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/stats?${search.toString()}`,
    { signal },
    deps
  )
}

export async function venueGetSubscription(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<OwnerBillingOverviewResponse>(
    backendUrl,
    `/api/venue/${venueId}/subscription`,
    { signal },
    deps
  )
}

export async function venueEnsureSubscriptionCheckout(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies
) {
  return requestApi<OwnerBillingOverviewResponse>(
    backendUrl,
    `/api/venue/${venueId}/subscription/checkout`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    },
    deps
  )
}

export async function venueGetMenu(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(venueId) })
  return requestApi<VenueMenuResponse>(
    backendUrl,
    `/api/venue/menu?${search.toString()}`,
    { signal },
    deps
  )
}

export async function venueGetBookings(
  backendUrl: string,
  params: { venueId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueBookingListResponse>(
    backendUrl,
    `/api/venue/bookings?${search.toString()}`,
    { signal },
    deps
  )
}

export async function venueConfirmBooking(
  backendUrl: string,
  params: { venueId: number; bookingId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueBookingStatusResponse>(
    backendUrl,
    `/api/venue/bookings/${params.bookingId}/confirm?${search.toString()}`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueCancelBooking(
  backendUrl: string,
  params: { venueId: number; bookingId: number; body?: VenueBookingCancelRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueBookingStatusResponse>(
    backendUrl,
    `/api/venue/bookings/${params.bookingId}/cancel?${search.toString()}`,
    {
      method: 'POST',
      headers: params.body ? { 'Content-Type': 'application/json' } : undefined,
      body: params.body ? JSON.stringify(params.body) : undefined,
      signal
    },
    deps
  )
}

export async function venueSeatBooking(
  backendUrl: string,
  params: { venueId: number; bookingId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueBookingStatusResponse>(
    backendUrl,
    `/api/venue/bookings/${params.bookingId}/seat?${search.toString()}`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueNoShowBooking(
  backendUrl: string,
  params: { venueId: number; bookingId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueBookingStatusResponse>(
    backendUrl,
    `/api/venue/bookings/${params.bookingId}/no-show?${search.toString()}`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueChangeBooking(
  backendUrl: string,
  params: { venueId: number; bookingId: number; body: VenueBookingChangeRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueBookingStatusResponse>(
    backendUrl,
    `/api/venue/bookings/${params.bookingId}/change?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueMessageBookingGuest(
  backendUrl: string,
  params: { venueId: number; bookingId: number; body: VenueBookingMessageRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueBookingMessageResponse>(
    backendUrl,
    `/api/venue/bookings/${params.bookingId}/message?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueGetBookingSettings(
  backendUrl: string,
  params: { venueId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueBookingSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/booking-settings`,
    { signal },
    deps
  )
}

export async function venueUpdateBookingSettings(
  backendUrl: string,
  params: { venueId: number; body: VenueBookingSettingsUpdateRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueBookingSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/booking-settings`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueGetScheduleSettings(
  backendUrl: string,
  params: { venueId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueScheduleSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/schedule`,
    { signal },
    deps
  )
}

export async function venueUpdateScheduleDay(
  backendUrl: string,
  params: { venueId: number; weekday: number; body: VenueScheduleDayUpdateRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueScheduleSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/schedule/weekly/${params.weekday}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueUpdateScheduleOverride(
  backendUrl: string,
  params: { venueId: number; serviceDate: string; body: VenueScheduleOverrideUpdateRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueScheduleSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/schedule/overrides/${encodeURIComponent(params.serviceDate)}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueUpdateScheduleOverrideRange(
  backendUrl: string,
  params: { venueId: number; body: VenueScheduleOverrideRangeUpdateRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueScheduleSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/schedule/override-ranges`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueReplaceScheduleOverrideRange(
  backendUrl: string,
  params: { venueId: number; fromDate: string; toDate: string; body: VenueScheduleOverrideRangeUpdateRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueScheduleSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/schedule/override-ranges/${encodeURIComponent(
      params.fromDate
    )}/${encodeURIComponent(params.toDate)}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueDeleteScheduleOverride(
  backendUrl: string,
  params: { venueId: number; serviceDate: string },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueScheduleSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/schedule/overrides/${encodeURIComponent(params.serviceDate)}`,
    { method: 'DELETE', signal },
    deps
  )
}

export async function venueDeleteScheduleOverrideRange(
  backendUrl: string,
  params: { venueId: number; fromDate: string; toDate: string },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueScheduleSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/schedule/override-ranges/${encodeURIComponent(
      params.fromDate
    )}/${encodeURIComponent(params.toDate)}`,
    { method: 'DELETE', signal },
    deps
  )
}

export async function venueGetPublicCardSettings(
  backendUrl: string,
  params: { venueId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenuePublicCardSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/public-card`,
    { signal },
    deps
  )
}

export async function venueUpdatePublicCardSettings(
  backendUrl: string,
  params: { venueId: number; body: VenuePublicCardSettingsUpdateRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenuePublicCardSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/public-card`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueGetLocationSuggestions(
  backendUrl: string,
  params: {
    venueId: number
    kind: VenueLocationSuggestionKind
    query: string
    countryCode: string
    city?: string | null
    sessionToken?: string | null
  },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({
    kind: params.kind,
    query: params.query,
    countryCode: params.countryCode
  })
  if (params.city) {
    search.set('city', params.city)
  }
  if (params.sessionToken) {
    search.set('sessionToken', params.sessionToken)
  }
  return requestApi<VenueLocationSuggestionsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/location/suggestions?${search.toString()}`,
    { signal },
    deps
  )
}

export async function venueResolveLocation(
  backendUrl: string,
  params: { venueId: number; body: VenueLocationResolveRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueLocationResolveResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/location/resolve`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueGetSupportThreads(
  backendUrl: string,
  params: { venueId: number; bookingId?: number | null; filter?: SupportThreadFilter },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams()
  if (params.bookingId != null) {
    search.set('bookingId', String(params.bookingId))
  }
  if (params.filter) {
    search.set('filter', params.filter)
  }
  const suffix = search.toString() ? `?${search.toString()}` : ''
  return requestApi<SupportThreadListResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/support/threads${suffix}`,
    { signal },
    deps
  )
}

export async function venueGetSupportThread(
  backendUrl: string,
  params: { venueId: number; threadId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<SupportThreadDetailResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/support/threads/${params.threadId}`,
    { signal },
    deps
  )
}

export async function venueSendSupportThreadMessage(
  backendUrl: string,
  params: { venueId: number; threadId: number; body: SupportMessageCreateRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<SupportMessageCreateResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/support/threads/${params.threadId}/messages`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueResolveSupportThread(
  backendUrl: string,
  params: { venueId: number; threadId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<SupportThreadDetailResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/support/threads/${params.threadId}/resolve`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueReopenSupportThread(
  backendUrl: string,
  params: { venueId: number; threadId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<SupportThreadDetailResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/support/threads/${params.threadId}/reopen`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueEscalateSupportThread(
  backendUrl: string,
  params: { venueId: number; threadId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<SupportThreadDetailResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/support/threads/${params.threadId}/escalate`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueCreateCategory(
  backendUrl: string,
  params: { venueId: number; body: VenueCreateCategoryRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueMenuCategoryDto>(
    backendUrl,
    `/api/venue/menu/categories?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueUpdateCategory(
  backendUrl: string,
  params: { venueId: number; categoryId: number; body: VenueUpdateCategoryRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueMenuCategoryDto>(
    backendUrl,
    `/api/venue/menu/categories/${params.categoryId}?${search.toString()}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueDeleteCategory(
  backendUrl: string,
  params: { venueId: number; categoryId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<{ ok: boolean }>(
    backendUrl,
    `/api/venue/menu/categories/${params.categoryId}?${search.toString()}`,
    { method: 'DELETE', signal },
    deps
  )
}

export async function venueCreateItem(
  backendUrl: string,
  params: { venueId: number; body: VenueCreateItemRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueMenuItemDto>(
    backendUrl,
    `/api/venue/menu/items?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueUpdateItem(
  backendUrl: string,
  params: { venueId: number; itemId: number; body: VenueUpdateItemRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueMenuItemDto>(
    backendUrl,
    `/api/venue/menu/items/${params.itemId}?${search.toString()}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueDeleteItem(
  backendUrl: string,
  params: { venueId: number; itemId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<{ ok: boolean }>(
    backendUrl,
    `/api/venue/menu/items/${params.itemId}?${search.toString()}`,
    { method: 'DELETE', signal },
    deps
  )
}

export async function venueReorderCategories(
  backendUrl: string,
  params: { venueId: number; body: VenueReorderCategoriesRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<{ ok: boolean }>(
    backendUrl,
    `/api/venue/menu/reorder/categories?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueReorderItems(
  backendUrl: string,
  params: { venueId: number; body: VenueReorderItemsRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<{ ok: boolean }>(
    backendUrl,
    `/api/venue/menu/reorder/items?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueSetItemAvailability(
  backendUrl: string,
  params: { venueId: number; itemId: number; body: VenueAvailabilityRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueMenuItemDto>(
    backendUrl,
    `/api/venue/menu/items/${params.itemId}/availability?${search.toString()}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueCreateOption(
  backendUrl: string,
  params: { venueId: number; body: VenueCreateOptionRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueMenuOptionDto>(
    backendUrl,
    `/api/venue/menu/options?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueApplyBaseFlavorProfiles(
  backendUrl: string,
  params: { venueId: number; itemId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueApplyBaseFlavorProfilesResponse>(
    backendUrl,
    `/api/venue/menu/items/${params.itemId}/base-flavor-profiles?${search.toString()}`,
    {
      method: 'POST',
      signal
    },
    deps
  )
}

export async function venueUpdateOption(
  backendUrl: string,
  params: { venueId: number; optionId: number; body: VenueUpdateOptionRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueMenuOptionDto>(
    backendUrl,
    `/api/venue/menu/options/${params.optionId}?${search.toString()}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueSetOptionAvailability(
  backendUrl: string,
  params: { venueId: number; optionId: number; body: VenueAvailabilityRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueMenuOptionDto>(
    backendUrl,
    `/api/venue/menu/options/${params.optionId}/availability?${search.toString()}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueDeleteOption(
  backendUrl: string,
  params: { venueId: number; optionId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<{ ok: boolean }>(
    backendUrl,
    `/api/venue/menu/options/${params.optionId}?${search.toString()}`,
    { method: 'DELETE', signal },
    deps
  )
}

export async function venueCreateStaffChatLinkCode(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<StaffChatLinkCodeResponse>(
    backendUrl,
    `/api/venue/${venueId}/staff-chat/link-code`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueCreateChatLinkCode(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return venueCreateStaffChatLinkCode(backendUrl, venueId, deps, signal)
}

export async function venueGetStaffChatStatus(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueStaffChatStatusResponse>(
    backendUrl,
    `/api/venue/${venueId}/staff-chat`,
    { signal },
    deps
  )
}

export async function venueGetStaffCalls(
  backendUrl: string,
  params: { venueId: number; limit?: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams()
  if (params.limit) {
    search.set('limit', String(params.limit))
  }
  const suffix = search.toString() ? `?${search.toString()}` : ''
  return requestApi<VenueStaffCallsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/staff-calls${suffix}`,
    { signal },
    deps
  )
}

export async function venueAckStaffCall(
  backendUrl: string,
  params: { venueId: number; staffCallId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueStaffCallActionResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/staff-calls/${params.staffCallId}/ack`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueDoneStaffCall(
  backendUrl: string,
  params: { venueId: number; staffCallId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueStaffCallActionResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/staff-calls/${params.staffCallId}/done`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueGetChatLinkStatus(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return venueGetStaffChatStatus(backendUrl, venueId, deps, signal)
}

export async function venueUnlinkStaffChat(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<{ ok: boolean }>(
    backendUrl,
    `/api/venue/${venueId}/staff-chat/unlink`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueSendStaffChatTestMessage(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<StaffChatTestResponse>(
    backendUrl,
    `/api/venue/${venueId}/staff-chat/test`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueUnlinkChat(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return venueUnlinkStaffChat(backendUrl, venueId, deps, signal)
}

export async function venueGetStaff(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueStaffListResponse>(
    backendUrl,
    `/api/venue/${venueId}/staff`,
    { signal },
    deps
  )
}

export async function venueCreateInvite(
  backendUrl: string,
  params: { venueId: number; body: VenueStaffInviteRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueStaffInviteResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/staff/invites`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueAcceptInvite(
  backendUrl: string,
  body: VenueStaffInviteAcceptRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueStaffInviteAcceptResponse>(
    backendUrl,
    '/api/venue/staff/invites/accept',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal
    },
    deps
  )
}

export async function venueUpdateRole(
  backendUrl: string,
  params: { venueId: number; userId: number; body: VenueStaffUpdateRoleRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueStaffListResponse['members'][number]>(
    backendUrl,
    `/api/venue/${params.venueId}/staff/${params.userId}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueRemoveStaff(
  backendUrl: string,
  params: { venueId: number; userId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<{ ok: boolean }>(
    backendUrl,
    `/api/venue/${params.venueId}/staff/${params.userId}`,
    { method: 'DELETE', signal },
    deps
  )
}

export async function venueGetTables(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(venueId) })
  return requestApi<VenueTablesResponse>(
    backendUrl,
    `/api/venue/tables?${search.toString()}`,
    { signal },
    deps
  )
}

export async function venueCreateTablesBatch(
  backendUrl: string,
  params: { venueId: number; body: VenueTableBatchCreateRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueTableBatchCreateResponse>(
    backendUrl,
    `/api/venue/tables/batch-create?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueRotateTableToken(
  backendUrl: string,
  params: { venueId: number; tableId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueTableTokenRotateResponse>(
    backendUrl,
    `/api/venue/tables/${params.tableId}/rotate-token?${search.toString()}`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueRotateTableTokens(
  backendUrl: string,
  params: { venueId: number; body: VenueTableRotateTokensRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<VenueTableRotateTokensResponse>(
    backendUrl,
    `/api/venue/tables/rotate-tokens?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueExportQrPackage(
  backendUrl: string,
  params: { venueId: number; format: 'zip' | 'pdf' },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({
    venueId: String(params.venueId),
    format: params.format
  })
  return requestBinary(
    backendUrl,
    `/api/venue/tables/qr-package?${search.toString()}`,
    { signal },
    deps
  )
}

export async function venueGetOrdersQueue(
  backendUrl: string,
  params: { venueId: number; status?: string; limit?: number; cursor?: string },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  if (params.status) {
    search.set('status', params.status)
  }
  if (params.limit) {
    search.set('limit', String(params.limit))
  }
  if (params.cursor) {
    search.set('cursor', params.cursor)
  }
  return requestApi<OrdersQueueResponse>(
    backendUrl,
    `/api/venue/orders/queue?${search.toString()}`,
    { signal },
    deps
  )
}

export async function venueGetOrderDetail(
  backendUrl: string,
  params: { venueId: number; orderId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<OrderDetailResponse>(
    backendUrl,
    `/api/venue/orders/${params.orderId}?${search.toString()}`,
    { signal },
    deps
  )
}

export async function venueGetOrderAudit(
  backendUrl: string,
  params: { venueId: number; orderId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<OrderAuditResponse>(
    backendUrl,
    `/api/venue/orders/${params.orderId}/audit?${search.toString()}`,
    { signal },
    deps
  )
}

export async function venueUpdateOrderStatus(
  backendUrl: string,
  params: { venueId: number; orderId: number; body: OrderStatusRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<OrderStatusResponse>(
    backendUrl,
    `/api/venue/orders/${params.orderId}/status?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueRejectOrder(
  backendUrl: string,
  params: { venueId: number; orderId: number; body: OrderRejectRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<OrderStatusResponse>(
    backendUrl,
    `/api/venue/orders/${params.orderId}/reject?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueCloseOrder(
  backendUrl: string,
  params: { venueId: number; orderId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<OrderStatusResponse>(
    backendUrl,
    `/api/venue/orders/${params.orderId}/close?${search.toString()}`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueExcludeOrderItem(
  backendUrl: string,
  params: { venueId: number; orderId: number; batchItemId: number; body: OrderBillItemExcludeRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<OrderBillItemAdjustmentResponse>(
    backendUrl,
    `/api/venue/orders/${params.orderId}/items/${params.batchItemId}/exclude?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueRestoreOrderItem(
  backendUrl: string,
  params: { venueId: number; orderId: number; batchItemId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<OrderBillItemAdjustmentResponse>(
    backendUrl,
    `/api/venue/orders/${params.orderId}/items/${params.batchItemId}/restore?${search.toString()}`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueSetOrderItemDiscount(
  backendUrl: string,
  params: { venueId: number; orderId: number; batchItemId: number; body: OrderBillItemDiscountRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ venueId: String(params.venueId) })
  return requestApi<OrderBillItemAdjustmentResponse>(
    backendUrl,
    `/api/venue/orders/${params.orderId}/items/${params.batchItemId}/discount?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueGetShiftExtensionRequests(
  backendUrl: string,
  params: { venueId: number; status?: string },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams()
  if (params.status) {
    search.set('status', params.status)
  }
  const suffix = search.toString() ? `?${search.toString()}` : ''
  return requestApi<ShiftExtensionRequestsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/shift-extension-requests${suffix}`,
    { signal },
    deps
  )
}

export async function venueGetShiftExtensionSettings(
  backendUrl: string,
  params: { venueId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<ShiftExtensionSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/shift-extension-settings`,
    { signal },
    deps
  )
}

export async function venueUpdateShiftExtensionSettings(
  backendUrl: string,
  params: { venueId: number; body: ShiftExtensionSettingsUpdateRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<ShiftExtensionSettingsResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/shift-extension-settings`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}

export async function venueApproveShiftExtensionRequest(
  backendUrl: string,
  params: { venueId: number; requestId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<ShiftExtensionDecisionResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/shift-extension-requests/${params.requestId}/approve`,
    { method: 'POST', signal },
    deps
  )
}

export async function venueRejectShiftExtensionRequest(
  backendUrl: string,
  params: { venueId: number; requestId: number; body: ShiftExtensionDecisionRequest },
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<ShiftExtensionDecisionResponse>(
    backendUrl,
    `/api/venue/${params.venueId}/shift-extension-requests/${params.requestId}/reject`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(params.body),
      signal
    },
    deps
  )
}
