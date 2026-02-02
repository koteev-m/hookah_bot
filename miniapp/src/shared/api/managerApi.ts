import { requestApi, type RequestDependencies } from './request'
import type {
  NightTablesResponse,
  SeatTableRequest,
  UpdateDepositRequest
} from './managerDtos'

export async function getTablesForNight(
  backendUrl: string,
  clubId: number,
  nightKey: string,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ clubId: String(clubId), nightKey })
  return requestApi<NightTablesResponse>(
    backendUrl,
    `/api/manager/tables?${search.toString()}`,
    { signal },
    deps
  )
}

export async function seatTable(
  backendUrl: string,
  clubId: number,
  nightKey: string,
  tableId: number,
  payload: SeatTableRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ clubId: String(clubId), nightKey })
  return requestApi<{ ok: boolean }>(
    backendUrl,
    `/api/manager/tables/${tableId}/seat?${search.toString()}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}

export async function freeTable(
  backendUrl: string,
  clubId: number,
  nightKey: string,
  tableId: number,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ clubId: String(clubId), nightKey })
  return requestApi<{ ok: boolean }>(
    backendUrl,
    `/api/manager/tables/${tableId}/free?${search.toString()}`,
    { method: 'POST', signal },
    deps
  )
}

export async function updateDeposit(
  backendUrl: string,
  clubId: number,
  nightKey: string,
  depositId: number,
  payload: UpdateDepositRequest,
  deps: RequestDependencies,
  signal?: AbortSignal
) {
  const search = new URLSearchParams({ clubId: String(clubId), nightKey })
  return requestApi<{ ok: boolean }>(
    backendUrl,
    `/api/manager/deposits/${depositId}?${search.toString()}`,
    {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal
    },
    deps
  )
}
