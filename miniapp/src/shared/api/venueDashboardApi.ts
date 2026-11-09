import { requestApi, type RequestDependencies } from './request'

export type AttentionEvent = {
  id: string
  kind: 'ORDER_CREATED' | 'BATCH_ADDED' | 'STAFF_CALL_CREATED'
  createdAt: string
  orderId: number | null
  callId: number | null
  tableNumber: number
}

export type DashboardSnapshot = {
  venueId: number
  userId: number
  role: string
  permissions: string[]
  monitoringVersion: 2
  eventCapacity: number
  attentionComplete: boolean
  attentionTotalAtLeast: number
  attention: AttentionEvent[]
  activeOrders: number
  callsInWork: number
  bookingsPending: number | null
  occupiedTables: number | null
  activeTables: number | null
  unavailableItems: number | null
  unavailableOptions: number | null
  accountingOpenedAt: string | null
  staffChatLinked: boolean | null
  venueStatus: string
  subscriptionStatus: string | null
  refreshedAt: string
}

export function venueGetDashboard(backendUrl: string, venueId: number, deps: RequestDependencies, signal: AbortSignal) {
  return requestApi<DashboardSnapshot>(backendUrl, `/api/venue/${venueId}/dashboard`, { signal, cache: 'no-store' }, deps, 131072)
}
