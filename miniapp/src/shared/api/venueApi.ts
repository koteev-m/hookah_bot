import { requestApi, type RequestDependencies } from './request'
import type {
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
