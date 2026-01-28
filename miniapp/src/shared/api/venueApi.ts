import { requestApi, requestBinary, type RequestDependencies } from './request'
import type {
  VenueAvailabilityRequest,
  VenueCreateCategoryRequest,
  VenueCreateItemRequest,
  VenueCreateOptionRequest,
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
  VenueReorderCategoriesRequest,
  VenueReorderItemsRequest,
  VenueUpdateCategoryRequest,
  VenueUpdateItemRequest,
  VenueUpdateOptionRequest,
  OrderAuditResponse,
  OrderDetailResponse,
  OrdersQueueResponse,
  OrderRejectRequest,
  OrderStatusRequest,
  OrderStatusResponse,
  StaffChatLinkCodeResponse,
  VenueMeResponse
} from './venueDtos'

export async function venueGetMe(
  backendUrl: string,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  return requestApi<VenueMeResponse>(backendUrl, '/api/venue/me', { signal }, deps)
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
