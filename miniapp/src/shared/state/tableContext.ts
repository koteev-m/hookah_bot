import { getBackendBaseUrl } from '../api/backend'
import { REQUEST_ABORTED_CODE } from '../api/abort'
import { clearSession, getAccessToken } from '../api/auth'
import { guestResolveTable } from '../api/guestApi'
import type { TableResolveResponse } from '../api/guestDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../api/types'
import { getTelegramContext } from '../telegram'

export type TableContextStatus =
  | 'missing'
  | 'invalid'
  | 'resolving'
  | 'resolved'
  | 'notFound'
  | 'error'

export type TableContextSnapshot = {
  status: TableContextStatus
  tableToken: string | null
  tableId: number | null
  tableNumber: string | null
  venueId: number | null
  available: boolean | null
  unavailableReason: string | null
  tableLabel: string | null
  orderAllowed: boolean
  blockReasonText: string | null
  error: ApiErrorInfo | null
}

export type TableStatusPresentation = {
  title: string
  details?: string
  severity: 'ok' | 'warn' | 'error'
}

const isDebug = import.meta.env.DEV
const listeners = new Set<(snapshot: TableContextSnapshot) => void>()
let currentSnapshot = buildEmptySnapshot('missing')
let activeController: AbortController | null = null
let inFlight: Promise<void> | null = null
let requestCounter = 0
let initialized = false

function buildApiDeps() {
  return { isDebug, getAccessToken, clearSession }
}

function resolveTokenStatus(status: ReturnType<typeof getTelegramContext>['tableTokenStatus']): TableContextStatus {
  if (status === 'invalid') {
    return 'invalid'
  }
  if (status === 'missing') {
    return 'missing'
  }
  return 'missing'
}

function resolveBlockReasonText(reason: string | null): string {
  switch (reason) {
    case 'SERVICE_SUSPENDED':
      return 'Заведение временно недоступно'
    case 'SUBSCRIPTION_BLOCKED':
      return 'Заказы временно недоступны'
    default:
      return 'Заведение недоступно'
  }
}

function buildEmptySnapshot(
  status: TableContextStatus,
  tableToken: string | null = null
): TableContextSnapshot {
  return {
    status,
    tableToken,
    tableId: null,
    tableNumber: null,
    venueId: null,
    available: null,
    unavailableReason: null,
    tableLabel: null,
    orderAllowed: false,
    blockReasonText: null,
    error: null
  }
}

function buildResolvedSnapshot(
  payload: TableResolveResponse,
  tableToken: string
): TableContextSnapshot {
  const blockReasonText = payload.available ? null : resolveBlockReasonText(payload.unavailableReason)
  return {
    status: 'resolved',
    tableToken,
    tableId: payload.tableId,
    tableNumber: payload.tableNumber,
    venueId: payload.venueId,
    available: payload.available,
    unavailableReason: payload.unavailableReason,
    tableLabel: `Стол №${payload.tableNumber}`,
    orderAllowed: payload.available,
    blockReasonText,
    error: null
  }
}

function updateSnapshot(snapshot: TableContextSnapshot) {
  currentSnapshot = snapshot
  listeners.forEach((listener) => listener(currentSnapshot))
}

export function getTableContext(): TableContextSnapshot {
  return currentSnapshot
}

export function subscribe(listener: (snapshot: TableContextSnapshot) => void): () => void {
  listeners.add(listener)
  listener(currentSnapshot)
  return () => {
    listeners.delete(listener)
  }
}

export async function refresh(): Promise<void> {
  const { tableTokenStatus, tableToken } = getTelegramContext()
  if (tableTokenStatus !== 'valid' || !tableToken) {
    if (activeController) {
      activeController.abort()
      activeController = null
    }
    updateSnapshot(buildEmptySnapshot(resolveTokenStatus(tableTokenStatus)))
    return
  }

  const nextToken = tableToken
  if (activeController) {
    activeController.abort()
  }
  const controller = new AbortController()
  activeController = controller
  updateSnapshot(buildEmptySnapshot('resolving', nextToken))
  const requestId = (requestCounter += 1)

  const backendUrl = getBackendBaseUrl()
  const deps = buildApiDeps()
  const request = guestResolveTable(backendUrl, nextToken, deps, controller.signal)
  inFlight = request.then((result) => {
    if (controller.signal.aborted || requestId !== requestCounter) {
      return
    }

    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) {
        return
      }
      if (result.error.status === 404) {
        updateSnapshot(buildEmptySnapshot('notFound'))
        return
      }
      updateSnapshot({ ...buildEmptySnapshot('error'), error: result.error })
      return
    }

    updateSnapshot(buildResolvedSnapshot(result.data, nextToken))
  })

  await inFlight
}

export async function ensureResolved(): Promise<TableContextSnapshot> {
  if (currentSnapshot.status === 'resolved') {
    return currentSnapshot
  }
  if (currentSnapshot.status === 'resolving' && inFlight) {
    await inFlight
    return currentSnapshot
  }
  await refresh()
  return currentSnapshot
}

export function formatTableStatus(snapshot: TableContextSnapshot): TableStatusPresentation {
  switch (snapshot.status) {
    case 'missing':
      return { title: 'Сначала отсканируйте QR', severity: 'warn' }
    case 'invalid':
      return {
        title: 'Некорректный QR. Обновите и попробуйте снова.',
        severity: 'error'
      }
    case 'notFound':
      return { title: 'Стол не найден. Обновите QR.', severity: 'error' }
    case 'resolving':
      return { title: 'Загрузка стола…', severity: 'warn' }
    case 'error':
      if (snapshot.error?.code === ApiErrorCodes.INITDATA_INVALID) {
        return { title: 'Откройте Mini App внутри Telegram', severity: 'error' }
      }
      if (snapshot.error?.code === ApiErrorCodes.UNAUTHORIZED) {
        return { title: 'Сессия устарела — откройте заново в Telegram', severity: 'error' }
      }
      return { title: 'Не удалось загрузить стол. Попробуйте позже.', severity: 'error' }
    case 'resolved': {
      if (snapshot.available) {
        return { title: snapshot.tableLabel ?? 'Стол', severity: 'ok' }
      }
      return { title: snapshot.blockReasonText ?? 'Заведение недоступно', severity: 'error' }
    }
    default:
      return { title: 'Не удалось загрузить стол. Попробуйте позже.', severity: 'error' }
  }
}

export function initTableContext(): void {
  if (initialized) {
    return
  }
  initialized = true
  const { tableTokenStatus, tableToken } = getTelegramContext()
  if (tableTokenStatus === 'valid' && tableToken) {
    updateSnapshot(buildEmptySnapshot('resolving', tableToken))
    void refresh()
    return
  }
  updateSnapshot(buildEmptySnapshot(resolveTokenStatus(tableTokenStatus)))
}
