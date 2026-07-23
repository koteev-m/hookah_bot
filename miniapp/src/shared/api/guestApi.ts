import { requestApi, type RequestDependencies } from './request'
import type {
  ActiveOrderResponse,
  AddBatchRequest,
  AddBatchResponse,
  BillPaymentMethod,
  CartPreviewRequest,
  CartPreviewResponse,
  CatalogResponse,
  CreateSharedTabRequest,
  CreateTabInviteRequest,
  CreateTabInviteResponse,
  GuestBookingCancelRequest,
  GuestBookingConfirmRequest,
  GuestBookingCreateRequest,
  GuestBookingListResponse,
  GuestBookingResponse,
  GuestBookingUpdateRequest,
  GuestBillRequestRequest,
  GuestBillRequestResponse,
  GuestFavoriteItemsResponse,
  GuestFavoriteMutationResponse,
  GuestFavoriteVenuesResponse,
  GuestShiftExtensionOptionsResponse,
  GuestTodayStaffResponse,
  GuestShiftExtensionRequest,
  GuestVisitFeedbackSubmitRequest,
  GuestVisitFeedbackSubmitResponse,
  GuestVisitRepeatPlanRequest,
  GuestVisitRepeatPlanResponse,
  GuestTabResponse,
  GuestTabsResponse,
  GuestVisitDetailResponse,
  GuestVisitListResponse,
  JoinTabRequest,
  MenuResponse,
  ShiftExtensionRequestResponse,
  StaffCallRequest,
  StaffCallResponse,
  StaffCallStatusResponse,
  TableResolveResponse,
  TableRestoreResponse,
  TableSessionEndRequest,
  TableSessionEndResponse,
  VenueInfoSectionsResponse,
  VenueResponse
} from './guestDtos'
import type {
  SupportMessageCreateRequest,
  SupportMessageCreateResponse,
  SupportThreadDetailResponse,
  SupportThreadCreateRequest,
  SupportThreadCreateResponse,
  SupportThreadFilter,
  SupportThreadType,
  SupportThreadListResponse,
  VenueChatCreateRequest
} from './supportDtos'
import { ApiErrorCodes, type ApiResult } from './types'
import { normalizeTableToken } from '../validation/tableToken'

function invalidInputResult<T>(message: string): ApiResult<T> {
  return {
    ok: false,
    error: {
      status: 400,
      code: ApiErrorCodes.INVALID_INPUT,
      message
    }
  }
}

function invalidTableTokenResult<T>(): ApiResult<T> {
  return invalidInputResult('Некорректный токен стола')
}

function invalidPositiveIdResult<T>(name: string): ApiResult<T> {
  return invalidInputResult(`${name} должен быть положительным числом`)
}

function invalidPaymentMethodResult<T>(): ApiResult<T> {
  return invalidInputResult('Некорректный способ оплаты')
}

function isBillPaymentMethod(value: string): value is BillPaymentMethod {
  return value === 'CARD' || value === 'CASH' || value === 'UNKNOWN'
}

export async function guestGetCatalog(
  backendUrl: string,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<CatalogResponse>> {
  return requestApi<CatalogResponse>(backendUrl, '/api/guest/catalog', { signal }, deps)
}

export async function guestGetVenue(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<VenueResponse>> {
  return requestApi<VenueResponse>(backendUrl, `/api/guest/venue/${venueId}`, { signal }, deps)
}

export async function guestGetVenueMenu(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<MenuResponse>> {
  return requestApi<MenuResponse>(backendUrl, `/api/guest/venue/${venueId}/menu`, { signal }, deps)
}

export async function guestGetVenueInfoSections(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<VenueInfoSectionsResponse>> {
  return requestApi<VenueInfoSectionsResponse>(
    backendUrl,
    `/api/guest/venue/${venueId}/info-sections`,
    { signal },
    deps
  )
}

export async function guestGetTodayStaff(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestTodayStaffResponse>> {
  return requestApi<GuestTodayStaffResponse>(backendUrl, `/api/guest/venue/${venueId}/today-staff`, { signal }, deps)
}

export async function guestCreateBooking(
  backendUrl: string,
  payload: GuestBookingCreateRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestBookingResponse>> {
  if (!Number.isFinite(payload.venueId) || !Number.isInteger(payload.venueId) || payload.venueId <= 0) {
    return invalidPositiveIdResult('venueId')
  }
  return requestApi<GuestBookingResponse>(
    backendUrl,
    '/api/guest/booking/create',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestGetBookings(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestBookingListResponse>> {
  if (!Number.isFinite(venueId) || !Number.isInteger(venueId) || venueId <= 0) {
    return invalidPositiveIdResult('venueId')
  }
  const search = new URLSearchParams({ venueId: String(venueId) })
  return requestApi<GuestBookingListResponse>(
    backendUrl,
    `/api/guest/booking?${search.toString()}`,
    { signal },
    deps
  )
}

export async function guestGetActiveBookings(
  backendUrl: string,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestBookingListResponse>> {
  return requestApi<GuestBookingListResponse>(
    backendUrl,
    '/api/guest/bookings',
    { signal },
    deps
  )
}

export async function guestUpdateBooking(
  backendUrl: string,
  venueId: number,
  payload: GuestBookingUpdateRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestBookingResponse>> {
  if (!Number.isFinite(venueId) || !Number.isInteger(venueId) || venueId <= 0) {
    return invalidPositiveIdResult('venueId')
  }
  if (!Number.isFinite(payload.bookingId) || !Number.isInteger(payload.bookingId) || payload.bookingId <= 0) {
    return invalidPositiveIdResult('bookingId')
  }
  const search = new URLSearchParams({ venueId: String(venueId) })
  return requestApi<GuestBookingResponse>(
    backendUrl,
    `/api/guest/booking/update?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestCancelBooking(
  backendUrl: string,
  venueId: number,
  payload: GuestBookingCancelRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestBookingResponse>> {
  if (!Number.isFinite(venueId) || !Number.isInteger(venueId) || venueId <= 0) {
    return invalidPositiveIdResult('venueId')
  }
  if (!Number.isFinite(payload.bookingId) || !Number.isInteger(payload.bookingId) || payload.bookingId <= 0) {
    return invalidPositiveIdResult('bookingId')
  }
  const search = new URLSearchParams({ venueId: String(venueId) })
  return requestApi<GuestBookingResponse>(
    backendUrl,
    `/api/guest/booking/cancel?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestConfirmBooking(
  backendUrl: string,
  venueId: number,
  payload: GuestBookingConfirmRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestBookingResponse>> {
  if (!Number.isFinite(venueId) || !Number.isInteger(venueId) || venueId <= 0) {
    return invalidPositiveIdResult('venueId')
  }
  if (!Number.isFinite(payload.bookingId) || !Number.isInteger(payload.bookingId) || payload.bookingId <= 0) {
    return invalidPositiveIdResult('bookingId')
  }
  if (
    payload.attendanceScheduleVersion != null &&
    (!Number.isFinite(payload.attendanceScheduleVersion) ||
      !Number.isInteger(payload.attendanceScheduleVersion) ||
      payload.attendanceScheduleVersion <= 0)
  ) {
    return invalidPositiveIdResult('attendanceScheduleVersion')
  }
  const search = new URLSearchParams({ venueId: String(venueId) })
  return requestApi<GuestBookingResponse>(
    backendUrl,
    `/api/guest/booking/confirm?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestGetSupportThreads(
  backendUrl: string,
  deps: RequestDependencies,
  signal?: AbortSignal,
  options?: { filter?: SupportThreadFilter; threadType?: SupportThreadType; threadTypes?: SupportThreadType[] }
): Promise<ApiResult<SupportThreadListResponse>> {
  const search = new URLSearchParams()
  if (options?.filter) {
    search.set('filter', options.filter)
  }
  if (options?.threadType) {
    search.set('threadType', options.threadType)
  }
  if (options?.threadTypes?.length) {
    search.set('threadTypes', options.threadTypes.join(','))
  }
  const suffix = search.toString() ? `?${search.toString()}` : ''
  return requestApi<SupportThreadListResponse>(backendUrl, `/api/guest/support/threads${suffix}`, { signal }, deps)
}

export async function guestCreateVenueChat(
  backendUrl: string,
  payload: VenueChatCreateRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<SupportThreadDetailResponse>> {
  if (!Number.isFinite(payload.venueId) || !Number.isInteger(payload.venueId) || payload.venueId <= 0) {
    return invalidPositiveIdResult('venueId')
  }
  return requestApi<SupportThreadDetailResponse>(
    backendUrl,
    '/api/guest/support/venue-chats',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestGetSupportThread(
  backendUrl: string,
  threadId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<SupportThreadDetailResponse>> {
  if (!Number.isFinite(threadId) || !Number.isInteger(threadId) || threadId <= 0) {
    return invalidPositiveIdResult('threadId')
  }
  return requestApi<SupportThreadDetailResponse>(
    backendUrl,
    `/api/guest/support/threads/${threadId}`,
    { signal },
    deps
  )
}

export async function guestCreateSupportThread(
  backendUrl: string,
  payload: SupportThreadCreateRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<SupportThreadCreateResponse>> {
  return requestApi<SupportThreadCreateResponse>(
    backendUrl,
    '/api/guest/support/threads',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestSendSupportThreadMessage(
  backendUrl: string,
  threadId: number,
  payload: SupportMessageCreateRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<SupportMessageCreateResponse>> {
  if (!Number.isFinite(threadId) || !Number.isInteger(threadId) || threadId <= 0) {
    return invalidPositiveIdResult('threadId')
  }
  return requestApi<SupportMessageCreateResponse>(
    backendUrl,
    `/api/guest/support/threads/${threadId}/messages`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestResolveSupportThread(
  backendUrl: string,
  threadId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<SupportThreadDetailResponse>> {
  if (!Number.isFinite(threadId) || !Number.isInteger(threadId) || threadId <= 0) {
    return invalidPositiveIdResult('threadId')
  }
  return requestApi<SupportThreadDetailResponse>(
    backendUrl,
    `/api/guest/support/threads/${threadId}/resolve`,
    { method: 'POST', signal },
    deps
  )
}

export async function guestReopenSupportThread(
  backendUrl: string,
  threadId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<SupportThreadDetailResponse>> {
  if (!Number.isFinite(threadId) || !Number.isInteger(threadId) || threadId <= 0) {
    return invalidPositiveIdResult('threadId')
  }
  return requestApi<SupportThreadDetailResponse>(
    backendUrl,
    `/api/guest/support/threads/${threadId}/reopen`,
    { method: 'POST', signal },
    deps
  )
}

export async function guestResolveTable(
  backendUrl: string,
  tableToken: string,
  deps: RequestDependencies,
  signal?: AbortSignal,
  options?: { tableSessionId?: number | null; allowCreateSession?: boolean }
): Promise<ApiResult<TableResolveResponse>> {
  const normalizedToken = normalizeTableToken(tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  const search = new URLSearchParams({ tableToken: normalizedToken })
  if (options?.tableSessionId !== undefined && options.tableSessionId !== null) {
    if (!Number.isFinite(options.tableSessionId) || !Number.isInteger(options.tableSessionId) || options.tableSessionId <= 0) {
      return invalidPositiveIdResult('tableSessionId')
    }
    search.set('tableSessionId', String(options.tableSessionId))
  }
  if (options?.allowCreateSession === true) {
    search.set('resolveMode', 'create')
  }
  return requestApi<TableResolveResponse>(
    backendUrl,
    `/api/guest/table/resolve?${search.toString()}`,
    { signal },
    deps
  )
}

export async function guestRestoreTable(
  backendUrl: string,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<TableRestoreResponse>> {
  return requestApi<TableRestoreResponse>(
    backendUrl,
    '/api/guest/table/restore',
    { signal },
    deps
  )
}

export async function guestEndTableSession(
  backendUrl: string,
  request: TableSessionEndRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<TableSessionEndResponse>> {
  const normalizedToken = normalizeTableToken(request.tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  if (!Number.isFinite(request.tableSessionId) || !Number.isInteger(request.tableSessionId) || request.tableSessionId <= 0) {
    return invalidPositiveIdResult('tableSessionId')
  }
  return requestApi<TableSessionEndResponse>(
    backendUrl,
    '/api/guest/table/session/end',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        tableToken: normalizedToken,
        tableSessionId: request.tableSessionId
      }),
      signal
    },
    deps
  )
}

export async function guestGetActiveOrder(
  backendUrl: string,
  tableToken: string,
  tableSessionId: number,
  tabId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<ActiveOrderResponse>> {
  const normalizedToken = normalizeTableToken(tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  if (!Number.isFinite(tableSessionId) || !Number.isInteger(tableSessionId) || tableSessionId <= 0) {
    return invalidPositiveIdResult('tableSessionId')
  }
  if (!Number.isFinite(tabId) || !Number.isInteger(tabId) || tabId <= 0) {
    return invalidPositiveIdResult('tabId')
  }
  const search = new URLSearchParams({
    tableToken: normalizedToken,
    tableSessionId: String(tableSessionId),
    tabId: String(tabId)
  })
  return requestApi<ActiveOrderResponse>(
    backendUrl,
    `/api/guest/order/active?${search.toString()}`,
    { signal },
    deps
  )
}

export async function guestAddBatch(
  backendUrl: string,
  payload: AddBatchRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<AddBatchResponse>> {
  const normalizedToken = normalizeTableToken(payload.tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  const requestPayload: AddBatchRequest = { ...payload, tableToken: normalizedToken }
  return requestApi<AddBatchResponse>(
    backendUrl,
    '/api/guest/order/add-batch',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestPayload),
      signal
    },
    deps
  )
}

export async function guestPreviewCart(
  backendUrl: string,
  payload: CartPreviewRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<CartPreviewResponse>> {
  const normalizedToken = normalizeTableToken(payload.tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  if (!Number.isFinite(payload.tableSessionId) || !Number.isInteger(payload.tableSessionId) || payload.tableSessionId <= 0) {
    return invalidPositiveIdResult('tableSessionId')
  }
  if (!Number.isFinite(payload.tabId) || !Number.isInteger(payload.tabId) || payload.tabId <= 0) {
    return invalidPositiveIdResult('tabId')
  }
  const requestPayload: CartPreviewRequest = { ...payload, tableToken: normalizedToken }
  return requestApi<CartPreviewResponse>(
    backendUrl,
    '/api/guest/order/preview',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestPayload),
      signal
    },
    deps
  )
}

export async function guestRequestBill(
  backendUrl: string,
  payload: GuestBillRequestRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestBillRequestResponse>> {
  const normalizedToken = normalizeTableToken(payload.tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  if (!Number.isFinite(payload.tableSessionId) || !Number.isInteger(payload.tableSessionId) || payload.tableSessionId <= 0) {
    return invalidPositiveIdResult('tableSessionId')
  }
  if (!Number.isFinite(payload.tabId) || !Number.isInteger(payload.tabId) || payload.tabId <= 0) {
    return invalidPositiveIdResult('tabId')
  }
  if (!isBillPaymentMethod(payload.paymentMethod)) {
    return invalidPaymentMethodResult()
  }
  const requestPayload: GuestBillRequestRequest = { ...payload, tableToken: normalizedToken }
  return requestApi<GuestBillRequestResponse>(
    backendUrl,
    '/api/guest/order/bill-request',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestPayload),
      signal
    },
    deps
  )
}

export async function guestStaffCall(
  backendUrl: string,
  payload: StaffCallRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<StaffCallResponse>> {
  const normalizedToken = normalizeTableToken(payload.tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  const requestPayload: StaffCallRequest = { ...payload, tableToken: normalizedToken }
  return requestApi<StaffCallResponse>(
    backendUrl,
    '/api/guest/staff-call',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestPayload),
      signal
    },
    deps
  )
}

export async function guestGetStaffCallStatus(
  backendUrl: string,
  params: { tableToken: string; tableSessionId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<StaffCallStatusResponse>> {
  const normalizedToken = normalizeTableToken(params.tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  if (
    !Number.isFinite(params.tableSessionId) ||
    !Number.isInteger(params.tableSessionId) ||
    params.tableSessionId <= 0
  ) {
    return invalidPositiveIdResult('tableSessionId')
  }
  const search = new URLSearchParams({
    tableToken: normalizedToken,
    tableSessionId: String(params.tableSessionId)
  })
  return requestApi<StaffCallStatusResponse>(
    backendUrl,
    `/api/guest/staff-call/status?${search.toString()}`,
    { signal },
    deps
  )
}

export async function guestGetShiftExtensionOptions(
  backendUrl: string,
  params: { tableToken: string; tableSessionId: number; tabId: number },
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestShiftExtensionOptionsResponse>> {
  const normalizedToken = normalizeTableToken(params.tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  if (!Number.isFinite(params.tableSessionId) || !Number.isInteger(params.tableSessionId) || params.tableSessionId <= 0) {
    return invalidPositiveIdResult('tableSessionId')
  }
  if (!Number.isFinite(params.tabId) || !Number.isInteger(params.tabId) || params.tabId <= 0) {
    return invalidPositiveIdResult('tabId')
  }
  const search = new URLSearchParams({
    tableToken: normalizedToken,
    tableSessionId: String(params.tableSessionId),
    tabId: String(params.tabId)
  })
  return requestApi<GuestShiftExtensionOptionsResponse>(
    backendUrl,
    `/api/guest/table/extension-options?${search.toString()}`,
    { signal },
    deps
  )
}

export async function guestCreateShiftExtensionRequest(
  backendUrl: string,
  payload: GuestShiftExtensionRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<ShiftExtensionRequestResponse>> {
  const normalizedToken = normalizeTableToken(payload.tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  if (!Number.isFinite(payload.tableSessionId) || !Number.isInteger(payload.tableSessionId) || payload.tableSessionId <= 0) {
    return invalidPositiveIdResult('tableSessionId')
  }
  if (!Number.isFinite(payload.tabId) || !Number.isInteger(payload.tabId) || payload.tabId <= 0) {
    return invalidPositiveIdResult('tabId')
  }
  const requestPayload: GuestShiftExtensionRequest = { ...payload, tableToken: normalizedToken }
  return requestApi<ShiftExtensionRequestResponse>(
    backendUrl,
    '/api/guest/table/extension-requests',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestPayload),
      signal
    },
    deps
  )
}

export async function guestGetTabs(
  backendUrl: string,
  tableSessionId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestTabsResponse>> {
  return requestApi<GuestTabsResponse>(
    backendUrl,
    `/api/guest/tabs?table_session_id=${encodeURIComponent(String(tableSessionId))}`,
    { signal },
    deps
  )
}

export async function guestCreateSharedTab(
  backendUrl: string,
  payload: CreateSharedTabRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestTabResponse>> {
  return requestApi<GuestTabResponse>(
    backendUrl,
    '/api/guest/tabs/shared',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestJoinTab(
  backendUrl: string,
  payload: JoinTabRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestTabResponse>> {
  return requestApi<GuestTabResponse>(
    backendUrl,
    '/api/guest/tabs/join',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestCreateTabInvite(
  backendUrl: string,
  tabId: number,
  payload: CreateTabInviteRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<CreateTabInviteResponse>> {
  return requestApi<CreateTabInviteResponse>(
    backendUrl,
    `/api/guest/tabs/${encodeURIComponent(String(tabId))}/invite`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestGetVisits(
  backendUrl: string,
  params: { limit?: number; cursor?: number | null },
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestVisitListResponse>> {
  const search = new URLSearchParams()
  if (params.limit !== undefined) {
    search.set('limit', String(params.limit))
  }
  if (params.cursor !== undefined && params.cursor !== null) {
    search.set('cursor', String(params.cursor))
  }
  const suffix = search.toString()
  return requestApi<GuestVisitListResponse>(backendUrl, `/api/guest/visits${suffix ? `?${suffix}` : ''}`, { signal }, deps)
}

export async function guestGetVisitDetail(
  backendUrl: string,
  visitId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestVisitDetailResponse>> {
  if (!Number.isFinite(visitId) || !Number.isInteger(visitId) || visitId <= 0) {
    return invalidPositiveIdResult('visitId')
  }
  return requestApi<GuestVisitDetailResponse>(
    backendUrl,
    `/api/guest/visits/${encodeURIComponent(String(visitId))}`,
    { signal },
    deps
  )
}

export async function guestBuildVisitRepeatPlan(
  backendUrl: string,
  visitId: number,
  payload: GuestVisitRepeatPlanRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestVisitRepeatPlanResponse>> {
  if (!Number.isFinite(visitId) || !Number.isInteger(visitId) || visitId <= 0) {
    return invalidPositiveIdResult('visitId')
  }
  if (
    !Number.isFinite(payload.tableSessionId) ||
    !Number.isInteger(payload.tableSessionId) ||
    payload.tableSessionId <= 0
  ) {
    return invalidPositiveIdResult('tableSessionId')
  }
  if (!Number.isFinite(payload.tabId) || !Number.isInteger(payload.tabId) || payload.tabId <= 0) {
    return invalidPositiveIdResult('tabId')
  }
  if (
    payload.orderId != null &&
    (!Number.isFinite(payload.orderId) || !Number.isInteger(payload.orderId) || payload.orderId <= 0)
  ) {
    return invalidPositiveIdResult('orderId')
  }
  return requestApi<GuestVisitRepeatPlanResponse>(
    backendUrl,
    `/api/guest/visits/${encodeURIComponent(String(visitId))}/repeat-plan`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestSubmitVisitFeedback(
  backendUrl: string,
  visitId: number,
  payload: GuestVisitFeedbackSubmitRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestVisitFeedbackSubmitResponse>> {
  if (!Number.isFinite(visitId) || !Number.isInteger(visitId) || visitId <= 0) {
    return invalidPositiveIdResult('visitId')
  }
  return requestApi<GuestVisitFeedbackSubmitResponse>(
    backendUrl,
    `/api/guest/visits/${encodeURIComponent(String(visitId))}/feedback`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function guestGetFavoriteVenues(
  backendUrl: string,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestFavoriteVenuesResponse>> {
  return requestApi<GuestFavoriteVenuesResponse>(backendUrl, '/api/guest/favorites/venues', { signal }, deps)
}

export async function guestAddFavoriteVenue(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestFavoriteMutationResponse>> {
  if (!Number.isFinite(venueId) || !Number.isInteger(venueId) || venueId <= 0) {
    return invalidPositiveIdResult('venueId')
  }
  return requestApi<GuestFavoriteMutationResponse>(
    backendUrl,
    `/api/guest/favorites/venues/${encodeURIComponent(String(venueId))}`,
    { method: 'POST', signal },
    deps
  )
}

export async function guestRemoveFavoriteVenue(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestFavoriteMutationResponse>> {
  if (!Number.isFinite(venueId) || !Number.isInteger(venueId) || venueId <= 0) {
    return invalidPositiveIdResult('venueId')
  }
  return requestApi<GuestFavoriteMutationResponse>(
    backendUrl,
    `/api/guest/favorites/venues/${encodeURIComponent(String(venueId))}`,
    { method: 'DELETE', signal },
    deps
  )
}

export async function guestGetFavoriteItems(
  backendUrl: string,
  venueId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<GuestFavoriteItemsResponse>> {
  if (!Number.isFinite(venueId) || !Number.isInteger(venueId) || venueId <= 0) {
    return invalidPositiveIdResult('venueId')
  }
  return requestApi<GuestFavoriteItemsResponse>(
    backendUrl,
    `/api/guest/favorites/items?venueId=${encodeURIComponent(String(venueId))}`,
    { signal },
    deps
  )
}
