import { getBackendBaseUrl } from '../api/backend'
import { REQUEST_ABORTED_CODE } from '../api/abort'
import { clearSession, getAccessToken } from '../api/auth'
import { guestRejoinTable, guestResolveTable, guestRestoreTable } from '../api/guestApi'
import type { TableResolveResponse } from '../api/guestDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../api/types'
import { normalizeTableToken } from '../validation/tableToken'
import { discardTableCart, hasTableCartDraft } from './cartStore'
import { isDebugEnabled } from '../debug'
import { forgetTableToken, getTelegramContext, rememberTableToken } from '../telegram'
import { restoreGuestTabSelection, setSelectedGuestTabId } from './guestTabSelection'

export type TableContextStatus =
  | 'rescan'
  | 'missing'
  | 'invalid'
  | 'resolving'
  | 'resolved'
  | 'notFound'
  | 'rejoinUnavailable'
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
let stableSnapshot = currentSnapshot
let activeController: AbortController | null = null
let inFlight: Promise<void | TableContextSnapshot | null> | null = null
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
    case 'TABLE_SESSION_EXITED':
      return 'Визит завершён. Для нового входа отсканируйте QR стола в обновлённом приложении.'
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

// An unresolved SDK command can only be checked against its original session.
type SdkRejoinAttempt = { sessionId: number; tokenDigest: string; requiresScan?: boolean }

function sdkRejoinKey(): string | null {
  return getTelegramContext().telegramUserId ? resolveTableSessionStorageKey() + ':sdk-rejoin' : null
}

export function pendingSdkRejoin(): SdkRejoinAttempt | null {
  const key = sdkRejoinKey()
  if (!key) return null
  const raw = window.sessionStorage.getItem(key)
  if (!raw) return null
  const value = JSON.parse(raw)
  if (!Number.isSafeInteger(value.sessionId) || value.sessionId <= 0 || !/^[a-f0-9]{64}$/.test(value.tokenDigest)) {
    throw new Error('Invalid pending QR session')
  }
  return value
}

export async function sdkTokenDigest(token: string): Promise<string> {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token))
  return Array.from(new Uint8Array(bytes), byte => byte.toString(16).padStart(2, '0')).join('')
}

export function rememberSdkRejoin(attempt: SdkRejoinAttempt | null) {
  const key = sdkRejoinKey()
  if (!key) throw new Error('Missing guest identity')
  if (attempt) window.sessionStorage.setItem(key, JSON.stringify(attempt))
  else window.sessionStorage.removeItem(key)
}

type VisitPointer = StoredTableSessionContext | 'unavailable' | 'lost' | null

function storedVisit(): VisitPointer {
  if (!getTelegramContext().telegramUserId) return null
  try {
    const raw = window.sessionStorage.getItem(resolveTableSessionStorageKey())
    if (!raw) return null
    const value = JSON.parse(raw)
    if (value === 'unavailable' || value === 'lost') return value
    if (normalizeTableToken(value?.tableToken) === value?.tableToken &&
        typeof value?.tableToken === 'string' && Number.isSafeInteger(value.tableSessionId) && value.tableSessionId > 0) {
      return { tableToken: value.tableToken, tableSessionId: value.tableSessionId }
    }
  } catch { /* A missing or corrupt pointer cannot identify a visit. */ }
  return 'lost'
}

function confirmedVisit() {
  if (currentSnapshot.tableSessionActive && currentSnapshot.tableToken && currentSnapshot.tableSessionId) {
    return { tableToken: currentSnapshot.tableToken, tableSessionId: currentSnapshot.tableSessionId }
  }
  return storedVisit()
}

export function hasGuestVisitToResume() {
  return Boolean(confirmedVisit()) || currentSnapshot.status === 'resolving' && Boolean(currentSnapshot.tableToken)
}

function resumePointer(): VisitPointer {
  return confirmedVisit() ?? (window.history.state?.hookahGuestNavigation?.resumeTable === true ? 'lost' : null)
}

function requestNewScan(reason: 'lost' | 'unavailable') {
  if (getTelegramContext().telegramUserId) {
    try { window.sessionStorage.setItem(resolveTableSessionStorageKey(), JSON.stringify(reason)) } catch { /* optional */ }
  }
  updateSnapshot(buildEmptySnapshot('rescan'))
}
function rememberTableSessionContext(tableToken: string, tableSessionId: number) {
  if (typeof window === 'undefined' || !getTelegramContext().telegramUserId || tableSessionId <= 0) {
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

// Cancel an in-flight QR/refresh while retaining the confirmed visit if ending it fails.
export function cancelPendingTableRefresh(): void {
  activeController?.abort()
  activeController = null
  requestCounter += 1
  inFlight = null
  if (currentSnapshot.status === 'resolving') updateSnapshot(stableSnapshot)
}

export function clearCurrentTableContext(): void {
  cancelPendingTableRefresh()
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
  if (snapshot.status !== 'resolving') stableSnapshot = snapshot
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

export async function refresh(options?: {
  forceResolveSession?: boolean
  scannedToken?: string
  retrySessionId?: number
  confirmRejoin?: (table: TableResolveResponse) => boolean
}): Promise<TableContextSnapshot | null> {
  const isScan = options?.scannedToken !== undefined
  const forceResolveSession = isScan || options?.forceResolveSession === true
  const launch = getTelegramContext()
  // Old Bot menu buttons have no visit ID. Only an actual scan or the live
  // session already confirmed in this WebView can supply a visit for refresh.
  if (launch.unboundBotTableButton && !isScan && currentSnapshot.status !== 'resolved') {
    const rescan = buildEmptySnapshot('rescan')
    updateSnapshot(rescan)
    return rescan
  }
  const tableTokenStatus = launch.unboundBotTableButton ? 'missing' : launch.tableTokenStatus
  const { tableToken, tableSessionId } = launch
  const hasResolvedSnapshotToken =
    !forceResolveSession &&
    tableTokenStatus === 'missing' &&
    currentSnapshot.status === 'resolved' &&
    Boolean(currentSnapshot.tableToken)
  const nextToken = options?.scannedToken ?? (tableTokenStatus === 'valid' && tableToken ? tableToken : hasResolvedSnapshotToken ? currentSnapshot.tableToken : null)
  const contextTableSessionId =
    tableTokenStatus === 'valid' && tableToken ? tableSessionId : hasResolvedSnapshotToken ? currentSnapshot.tableSessionId : null
  if (!nextToken) {
    if (activeController) {
      activeController.abort()
      activeController = null
    }
    updateSnapshot(buildEmptySnapshot(resolveTokenStatus(tableTokenStatus)))
    return null
  }

  const currentSessionId =
    !forceResolveSession &&
    contextTableSessionId === null &&
    currentSnapshot.status === 'resolved' &&
    currentSnapshot.tableToken === nextToken
      ? currentSnapshot.tableSessionId
      : null
  const stored = storedVisit()
  const storedSessionId = stored && typeof stored === 'object' && stored.tableToken === nextToken ? stored.tableSessionId : null
  if (activeController) {
    activeController.abort()
  }
  const controller = new AbortController()
  activeController = controller
  if (!isScan) {
    updateSnapshot(buildEmptySnapshot('resolving', nextToken))
  } else if (currentSnapshot.status === 'resolving') {
    updateSnapshot(stableSnapshot)
  }
  const requestId = (requestCounter += 1)

  const backendUrl = getBackendBaseUrl()
  const deps = buildApiDeps()
  const sessionId = forceResolveSession ? null : contextTableSessionId ?? currentSessionId ?? storedSessionId
  const retrySession = isScan ? options?.retrySessionId : undefined
  const request = guestResolveTable(backendUrl, nextToken, deps, controller.signal, {
    tableSessionId: retrySession ?? sessionId,
    verifyOnly: retrySession !== undefined || (sessionId !== null && (sessionId === storedSessionId || sessionId === currentSessionId)),
    allowCreateSession: forceResolveSession && retrySession === undefined
  })
  const resolution = request.then(async (response) => {
    if (controller.signal.aborted || requestId !== requestCounter) return null
    let result = response
    if (retrySession !== undefined && result.ok && (result.data.tableSessionId !== retrySession ||
        (!result.data.tableSessionActive && result.data.tableSessionInactiveReason !== 'TABLE_SESSION_EXITED'))) {
      return buildEmptySnapshot('rescan')
    }
    if (isScan && result.ok && result.data.tableSessionInactiveReason === 'TABLE_SESSION_EXITED') {
      const table = result.data
      if (currentSnapshot.tableToken === nextToken && currentSnapshot.tableSessionId === table.tableSessionId) {
        updateSnapshot(buildResolvedSnapshot(table, nextToken))
      }
      if (!table.exitEventId) return buildEmptySnapshot('rejoinUnavailable')
      if (table.available && !table.tableSessionActive && options.confirmRejoin?.(table)) {
        result = await guestRejoinTable(backendUrl, nextToken, table.tableSessionId, table.exitEventId, deps, controller.signal)
        if (!result.ok && !controller.signal.aborted && requestId === requestCounter) {
          if (result.error.status === 409) return buildEmptySnapshot('rescan')
          if (result.error.status === 403) return buildEmptySnapshot('rejoinUnavailable')
          return { ...buildEmptySnapshot('error'), error: result.error }
        }
        if (result.ok && result.data.tableSessionId !== table.tableSessionId) return buildEmptySnapshot('rescan')
      }
    }
    if (controller.signal.aborted || requestId !== requestCounter) {
      return null
    }

    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return null
      const failure = result.error.status === 404
        ? buildEmptySnapshot('notFound')
        : { ...buildEmptySnapshot('error'), error: result.error }
      if (!isScan) updateSnapshot(failure)
      return failure
    }

    const resolved = buildResolvedSnapshot(result.data, nextToken)
    if (isScan && !resolved.orderAllowed) return resolved

    if (result.data.tableSessionActive) {
      if (hasTableCartDraft(nextToken, result.data.tableSessionId)) {
        restoreGuestTabSelection(result.data.tableSessionId, null, true)
      }
      rememberTableToken(nextToken)
      rememberTableSessionContext(nextToken, result.data.tableSessionId)
    } else {
      forgetTableSessionContext(nextToken)
    }
    updateSnapshot(resolved)
    return resolved
  })

  inFlight = resolution
  return resolution
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
  const visit = resumePointer()
  if (typeof visit === 'string') {
    requestNewScan(visit)
    return
  }
  const request = guestRestoreTable(backendUrl, deps, controller.signal, visit)
  inFlight = request.then((result) => {
    if (controller.signal.aborted || requestId !== requestCounter) {
      return
    }

    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) {
        return
      }
      updateSnapshot({ ...buildEmptySnapshot('error'), error: result.error })
      return
    }

    const restored = result.data.context
    if (visit && (!restored?.tableSessionActive || restored.tableSessionId !== visit.tableSessionId ||
        restored.tableToken !== visit.tableToken)) {
      forgetTableSessionContext(visit.tableToken)
      forgetTableToken(visit.tableToken)
      discardTableCart(visit.tableToken, visit.tableSessionId)
      requestNewScan('unavailable')
      return
    }
    if (!restored) {
      updateSnapshot(buildEmptySnapshot('missing'))
      return
    }

    rememberTableToken(restored.tableToken)
    rememberTableSessionContext(restored.tableToken, restored.tableSessionId)
    restoreGuestTabSelection(restored.tableSessionId, restored.tabId, hasTableCartDraft(restored.tableToken, restored.tableSessionId))
    updateSnapshot(buildResolvedSnapshot(restored, restored.tableToken))
  })

  await inFlight
}

// Both work-mode entry points consult the same authenticated view of the visit.
export const botVisitExitHint = 'Если кнопки завершения здесь нет, откройте чат с ботом и нажмите «🚪 Завершить визит».'

export async function checkGuestVisit(backendUrl: string, signal: AbortSignal): Promise<'active' | 'absent' | 'unknown'> {
  if (inFlight) await inFlight
  const version = requestCounter
  const result = await guestRestoreTable(backendUrl, buildApiDeps(), signal, 'active-visits')
  if (!result.ok || version !== requestCounter || signal.aborted) return 'unknown'
  if (result.data?.context === null) return 'absent'
  return result.data?.context?.tableSessionActive === true ? 'active' : 'unknown'
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
    case 'rescan':
      return { title: 'Не удалось восстановить выбранный стол. Отсканируйте QR на столе заново.', severity: 'warn' }
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
    case 'rejoinUnavailable':
      return { title: 'Прежний визит недоступен.', details: 'Откройте меню текущего стола в боте или обратитесь к персоналу.', severity: 'warn' }
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
      if (snapshot.error?.code === ApiErrorCodes.FORBIDDEN) {
        return {
          title: 'Требуется новое подтверждение входа',
          details: 'Откройте QR-код ещё раз и подтвердите вход в Telegram.',
          severity: 'error'
        }
      }
      return { title: 'Не удалось загрузить стол. Попробуйте позже.', severity: 'error' }
    case 'resolved': {
      if (!snapshot.tableSessionActive) {
        return {
          title: resolveTableSessionInactiveText(snapshot.tableSessionInactiveReason),
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
  const { tableTokenStatus, tableToken, tableTokenAutoResolve, tableTokenFromUrl, tableSessionId } = getTelegramContext()
  const pointer = storedVisit()
  // A reload of the previous Guest entry is passive. Only a new launch URL or the
  // scanner can identify a replacement for a lost pointer.
  const freshQr = tableTokenAutoResolve && tableTokenFromUrl && !window.history.state?.hookahGuestNavigation
  if (!freshQr && tableTokenAutoResolve && !tableTokenFromUrl) {
    try {
      if (pendingSdkRejoin()) { requestNewScan('lost'); return }
    } catch { requestNewScan('lost'); return }
  }
  if (typeof pointer === 'string' && !freshQr) {
    requestNewScan(pointer)
    return
  }
  if (tableTokenStatus === 'valid' && tableToken && tableTokenAutoResolve) {
    updateSnapshot(buildEmptySnapshot('resolving', tableToken))
    void refresh({ forceResolveSession: typeof pointer === 'string' && freshQr && tableSessionId === null })
    return
  }
  if (tableTokenStatus === 'missing') {
    updateSnapshot(buildEmptySnapshot('resolving'))
    void restoreActiveContext()
    return
  }
  updateSnapshot(buildEmptySnapshot(resolveTokenStatus(tableTokenStatus)))
}
