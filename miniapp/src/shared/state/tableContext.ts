import { getBackendBaseUrl } from '../api/backend'
import { REQUEST_ABORTED_CODE } from '../api/abort'
import { clearSession, getAccessToken } from '../api/auth'
import { guestResolveTable } from '../api/guestApi'
import type { TableResolveResponse } from '../api/guestDtos'
import type { ApiErrorInfo } from '../api/types'
import { getTelegramContext } from '../telegram'
import { validateTableToken } from '../validation/tableToken'

export type TableContextStatus =
  | 'missing'
  | 'invalid'
  | 'resolving'
  | 'resolved'
  | 'notFound'
  | 'error'

export type TableContextSnapshot = {
  status: TableContextStatus
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

type TableTokenResolution =
  | { status: 'missing' }
  | { status: 'invalid' }
  | { status: 'valid'; token: string }

const isDebug = import.meta.env.DEV
const listeners = new Set<(snapshot: TableContextSnapshot) => void>()
let currentSnapshot = buildEmptySnapshot('missing')
let activeController: AbortController | null = null
let inFlight: Promise<void> | null = null
let requestCounter = 0

function buildApiDeps() {
  return { isDebug, getAccessToken, clearSession }
}

function normalizeNonEmpty(value: string | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null
  }
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

function getQueryParam(key: string): string | null {
  if (typeof window === 'undefined') {
    return null
  }
  const params = new URLSearchParams(window.location.search)
  const value = params.get(key)
  return value ? value : null
}

function resolveTableToken(): TableTokenResolution {
  const startParam = normalizeNonEmpty(getTelegramContext().startParam)
  const queryToken = normalizeNonEmpty(getQueryParam('tableToken'))
  const candidate = startParam ?? queryToken
  if (!candidate) {
    return { status: 'missing' }
  }
  const validation = validateTableToken(candidate)
  if (!validation.ok) {
    return { status: 'invalid' }
  }
  return { status: 'valid', token: validation.value }
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

function buildEmptySnapshot(status: TableContextStatus): TableContextSnapshot {
  return {
    status,
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

function buildResolvedSnapshot(payload: TableResolveResponse): TableContextSnapshot {
  const blockReasonText = payload.available ? null : resolveBlockReasonText(payload.unavailableReason)
  return {
    status: 'resolved',
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
  const tokenState = resolveTableToken()
  if (tokenState.status !== 'valid') {
    if (activeController) {
      activeController.abort()
      activeController = null
    }
    updateSnapshot(buildEmptySnapshot(tokenState.status))
    return
  }

  const nextToken = tokenState.token
  if (activeController) {
    activeController.abort()
  }
  const controller = new AbortController()
  activeController = controller
  updateSnapshot(buildEmptySnapshot('resolving'))
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

    updateSnapshot(buildResolvedSnapshot(result.data))
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

const initialTokenState = resolveTableToken()
if (initialTokenState.status === 'valid') {
  updateSnapshot(buildEmptySnapshot('resolving'))
  void refresh()
} else {
  updateSnapshot(buildEmptySnapshot(initialTokenState.status))
}
