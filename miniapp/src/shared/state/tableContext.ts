import { getBackendBaseUrl } from '../api/backend'
import { REQUEST_ABORTED_CODE } from '../api/abort'
import { clearSession, getAccessToken } from '../api/auth'
import { guestResolveTable, guestRestoreTable } from '../api/guestApi'
import type { TableResolveResponse } from '../api/guestDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../api/types'
import { isDebugEnabled } from '../debug'
import { forgetTableToken, getTelegramContext, rememberTableToken } from '../telegram'
import { setSelectedGuestTabId } from './guestTabSelection'

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
  tableSessionId: number | null
  tableSessionStatus: string | null
  tableSessionActive: boolean
  tableSessionInactiveReason: string | null
  tableNumber: string | null
  venueName: string | null
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

const isDebug = isDebugEnabled()
const tableSessionStorageKeyPrefix = 'hookah_guest_table_session_context'
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

function resolveTableSessionInactiveText(reason: string | null): string {
  switch (reason) {
    case 'TABLE_SESSION_EXPIRED':
    case 'TABLE_SESSION_ENDED':
    default:
      return 'Контекст стола устарел'
  }
}

type StoredTableSessionContext = {
  tableToken: string
  tableSessionId: number
}

function resolveTableSessionStorageKey(): string {
  const userId = getTelegramContext().telegramUserId
  const userPart = userId ? `user:${userId}` : 'user:unknown'
  return `${tableSessionStorageKeyPrefix}:${userPart}`
}

function loadStoredTableSessionContext(tableToken: string): number | null {
  if (typeof window === 'undefined') {
    return null
  }
  try {
    const raw = window.sessionStorage.getItem(resolveTableSessionStorageKey())
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as Partial<StoredTableSessionContext>
    if (
      parsed.tableToken === tableToken &&
      typeof parsed.tableSessionId === 'number' &&
      Number.isFinite(parsed.tableSessionId) &&
      parsed.tableSessionId > 0
    ) {
      return parsed.tableSessionId
    }
  } catch {
    // ignore stale storage
  }
  return null
}

function rememberTableSessionContext(tableToken: string, tableSessionId: number) {
  if (typeof window === 'undefined' || tableSessionId <= 0) {
    return
  }
  try {
    window.sessionStorage.setItem(resolveTableSessionStorageKey(), JSON.stringify({ tableToken, tableSessionId }))
  } catch {
    // ignore session storage errors
  }
}

function forgetTableSessionContext(tableToken?: string | null) {
  if (typeof window === 'undefined') {
    return
  }
  try {
    const key = resolveTableSessionStorageKey()
    const raw = window.sessionStorage.getItem(key)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<StoredTableSessionContext>
      if (!tableToken || parsed.tableToken === tableToken) {
        if (
          typeof parsed.tableSessionId === 'number' &&
          Number.isFinite(parsed.tableSessionId) &&
          parsed.tableSessionId > 0
        ) {
          setSelectedGuestTabId(parsed.tableSessionId, null)
        }
        window.sessionStorage.removeItem(key)
      }
    }
  } catch {
    // ignore stale storage
  }
}

export function clearCurrentTableContext(): void {
  const tableToken = currentSnapshot.tableToken
  if (currentSnapshot.tableSessionId) {
    setSelectedGuestTabId(currentSnapshot.tableSessionId, null)
  }
  forgetTableSessionContext(tableToken)
  forgetTableToken(tableToken)
  updateSnapshot(buildEmptySnapshot('missing'))
}

function buildEmptySnapshot(
  status: TableContextStatus,
  tableToken: string | null = null
): TableContextSnapshot {
  return {
    status,
    tableToken,
    tableId: null,
    tableSessionId: null,
    tableSessionStatus: null,
    tableSessionActive: false,
    tableSessionInactiveReason: null,
    tableNumber: null,
    venueName: null,
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
  const tableSessionActive = payload.tableSessionActive !== false
  const tableSessionInactiveReason = payload.tableSessionInactiveReason ?? null
  const blockReasonText = payload.available
    ? tableSessionActive
      ? null
      : resolveTableSessionInactiveText(tableSessionInactiveReason)
    : resolveBlockReasonText(payload.unavailableReason)
  return {
    status: 'resolved',
    tableToken,
    tableId: payload.tableId,
    tableSessionId: payload.tableSessionId,
    tableSessionStatus: payload.tableSessionStatus,
    tableSessionActive,
    tableSessionInactiveReason,
    tableNumber: payload.tableNumber,
    venueName: payload.venueName,
    venueId: payload.venueId,
    available: payload.available,
    unavailableReason: payload.unavailableReason,
    tableLabel: `Стол №${payload.tableNumber}`,
    orderAllowed: payload.available && tableSessionActive,
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

export async function refresh(options?: { forceResolveSession?: boolean }): Promise<void> {
  const { tableTokenStatus, tableToken, tableSessionId } = getTelegramContext()
  const hasResolvedSnapshotToken =
    !options?.forceResolveSession &&
    tableTokenStatus === 'missing' &&
    currentSnapshot.status === 'resolved' &&
    Boolean(currentSnapshot.tableToken)
  const nextToken = tableTokenStatus === 'valid' && tableToken ? tableToken : hasResolvedSnapshotToken ? currentSnapshot.tableToken : null
  const contextTableSessionId =
    tableTokenStatus === 'valid' && tableToken ? tableSessionId : hasResolvedSnapshotToken ? currentSnapshot.tableSessionId : null
  if (!nextToken) {
    if (activeController) {
      activeController.abort()
      activeController = null
    }
    updateSnapshot(buildEmptySnapshot(resolveTokenStatus(tableTokenStatus)))
    return
  }

  const currentSessionId =
    !options?.forceResolveSession &&
    contextTableSessionId === null &&
    currentSnapshot.status === 'resolved' &&
    currentSnapshot.tableToken === nextToken
      ? currentSnapshot.tableSessionId
      : null
  const storedSessionId =
    !options?.forceResolveSession && contextTableSessionId === null && currentSessionId === null
      ? loadStoredTableSessionContext(nextToken)
      : null
  if (activeController) {
    activeController.abort()
  }
  const controller = new AbortController()
  activeController = controller
  updateSnapshot(buildEmptySnapshot('resolving', nextToken))
  const requestId = (requestCounter += 1)

  const backendUrl = getBackendBaseUrl()
  const deps = buildApiDeps()
  const request = guestResolveTable(backendUrl, nextToken, deps, controller.signal, {
    tableSessionId: options?.forceResolveSession === true ? null : contextTableSessionId ?? currentSessionId ?? storedSessionId,
    allowCreateSession: options?.forceResolveSession === true
  })
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

    if (result.data.tableSessionActive) {
      rememberTableToken(nextToken)
      rememberTableSessionContext(nextToken, result.data.tableSessionId)
    } else {
      forgetTableSessionContext(nextToken)
    }
    updateSnapshot(buildResolvedSnapshot(result.data, nextToken))
  })

  await inFlight
}

async function restoreActiveContext(): Promise<void> {
  if (activeController) {
    activeController.abort()
  }
  const controller = new AbortController()
  activeController = controller
  updateSnapshot(buildEmptySnapshot('resolving'))
  const requestId = (requestCounter += 1)

  const backendUrl = getBackendBaseUrl()
  const deps = buildApiDeps()
  const request = guestRestoreTable(backendUrl, deps, controller.signal)
  inFlight = request.then((result) => {
    if (controller.signal.aborted || requestId !== requestCounter) {
      return
    }

    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) {
        return
      }
      updateSnapshot(buildEmptySnapshot('missing'))
      return
    }

    const restored = result.data.context
    if (!restored) {
      forgetTableSessionContext()
      updateSnapshot(buildEmptySnapshot('missing'))
      return
    }

    rememberTableToken(restored.tableToken)
    rememberTableSessionContext(restored.tableToken, restored.tableSessionId)
    setSelectedGuestTabId(restored.tableSessionId, restored.tabId)
    updateSnapshot(buildResolvedSnapshot(restored, restored.tableToken))
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
      return {
        title: 'Заказ за столом',
        details: 'Чтобы заказать к столику или вызвать персонал, отсканируйте QR-код на столе.',
        severity: 'warn'
      }
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
      if (!snapshot.tableSessionActive) {
        return {
          title: 'Контекст стола устарел',
          details: 'Чтобы заказать или вызвать персонал, отсканируйте QR на столе заново.',
          severity: 'warn'
        }
      }
      if (snapshot.available) {
        const tableTitle = snapshot.tableNumber ? `столом №${snapshot.tableNumber}` : 'столом'
        return {
          title: snapshot.venueName ? `Вы за ${tableTitle} · ${snapshot.venueName}` : `Вы за ${tableTitle}`,
          severity: 'ok'
        }
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
  const { tableTokenStatus, tableToken, tableTokenAutoResolve } = getTelegramContext()
  if (tableTokenStatus === 'valid' && tableToken && tableTokenAutoResolve) {
    updateSnapshot(buildEmptySnapshot('resolving', tableToken))
    void refresh()
    return
  }
  if (tableTokenStatus === 'missing') {
    updateSnapshot(buildEmptySnapshot('resolving'))
    void restoreActiveContext()
    return
  }
  updateSnapshot(buildEmptySnapshot(resolveTokenStatus(tableTokenStatus)))
}
