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
import type { ApiResult } from './types'

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
  const encodedToken = encodeURIComponent(tableToken)
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
  const encodedToken = encodeURIComponent(tableToken)
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
  return requestApi<AddBatchResponse>(
    backendUrl,
    '/api/guest/order/add-batch',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
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
  return requestApi<StaffCallResponse>(
    backendUrl,
    '/api/guest/staff-call',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}
