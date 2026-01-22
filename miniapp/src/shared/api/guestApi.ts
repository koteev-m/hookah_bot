import { requestApi, type RequestDependencies } from './request'
import type {
  ActiveOrderResponse,
  AddBatchRequest,
  AddBatchResponse,
  CatalogResponse,
  MenuResponse,
  StaffCallRequest,
  StaffCallResponse,
  TableResolveResponse,
  VenueResponse
} from './guestDtos'
import { ApiErrorCodes, type ApiResult } from './types'
import { normalizeTableToken } from '../tableToken'

function invalidTableTokenResult<T>(): ApiResult<T> {
  return {
    ok: false,
    error: {
      status: 400,
      code: ApiErrorCodes.INVALID_INPUT,
      message: 'Некорректный токен стола'
    }
  }
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

export async function guestResolveTable(
  backendUrl: string,
  tableToken: string,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<TableResolveResponse>> {
  const normalizedToken = normalizeTableToken(tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  const encodedToken = encodeURIComponent(normalizedToken)
  return requestApi<TableResolveResponse>(
    backendUrl,
    `/api/guest/table/resolve?tableToken=${encodedToken}`,
    { signal },
    deps
  )
}

export async function guestGetActiveOrder(
  backendUrl: string,
  tableToken: string,
  deps: RequestDependencies,
  signal?: AbortSignal
): Promise<ApiResult<ActiveOrderResponse>> {
  const normalizedToken = normalizeTableToken(tableToken)
  if (!normalizedToken) {
    return invalidTableTokenResult()
  }
  const encodedToken = encodeURIComponent(normalizedToken)
  return requestApi<ActiveOrderResponse>(
    backendUrl,
    `/api/guest/order/active?tableToken=${encodedToken}`,
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
