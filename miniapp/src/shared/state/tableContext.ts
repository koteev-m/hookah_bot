import { getBackendBaseUrl } from '../api/backend'
import { REQUEST_ABORTED_CODE } from '../api/abort'
import { clearSession, getAccessToken } from '../api/auth'
import { guestRejoinTable, guestResolveTable, guestRestoreTable } from '../api/guestApi'
import type { TableResolveResponse } from '../api/guestDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../api/types'
import { normalizeTableToken } from '../validation/tableToken'
import { cartRequiresTabChoice } from './cartStore'
import {
  beginSelection, cancelSelection, checkSelection, confirmObservation, confirmSelection, forgetRejectedVisit, finishExit, knownEnded, prepareExit, retainLegacyPointer,
  readSelection, type SelectionResult, type VisitIntent
} from './visitSelection'
import { parseVisit, validGeneration, visitPointer, type VisitPointer, type Visit as StoredTableSessionContext } from './visitStorage'
import { isDebugEnabled } from '../debug'
import { forgetTableToken, getTelegramContext, rememberTableToken } from '../telegram'
import { bindGuestTabParticipation, restoreGuestTabSelection, setSelectedGuestTabId } from './guestTabSelection'

export type TableContextStatus =
  | 'rescan'
  | 'missing'
  | 'invalid'
  | 'resolving'
  | 'resolved'
  | 'notFound'
  | 'rejoinUnavailable'
  | 'selectionUnavailable'
  | 'error'

export type TableContextSnapshot = {
  status: TableContextStatus
  tableToken: string | null
  tableId: number | null
  tableSessionId: number | null
  visitGeneration?: string
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

type LaunchEntry = { tableToken: string; tableSessionId: number | null; forceResolveSession: boolean; intent: VisitIntent }
type Selection = ReturnType<typeof beginSelection>
let pendingIntent: VisitIntent | null = null

let failedLoad: { userId: number | null; entry?: LaunchEntry } | null = null

function resolveTableSessionStorageKey(): string {
  const userId = getTelegramContext().telegramUserId
  const userPart = userId ? `user:${userId}` : 'user:unknown'
  return `${tableSessionStorageKeyPrefix}:${userPart}`
}

export function startTableSelection(retryId?: string): Selection {
  const key = resolveTableSessionStorageKey()
  if (retryId === undefined) clearPendingLaunch()
  const selection = retryId === undefined ? beginSelection(key) : continuedSelection({ key, id: retryId })
  pendingIntent = selection.intent
  return selection
}

function continuedSelection(intent: VisitIntent): Selection {
  return { intent, ready: Promise.resolve('current') }
}

function clearPendingLaunch(key = resolveTableSessionStorageKey()) {
  try { window.sessionStorage.removeItem(key + ':pending-launch') } catch { /* optional */ }
}

function pendingLaunch(): LaunchEntry | undefined {
  try {
    const key = resolveTableSessionStorageKey()
    const raw = window.sessionStorage.getItem(key + ':pending-launch')
    const entry: LaunchEntry | null = raw ? JSON.parse(raw) : null
    if (entry && entry.intent?.key === key && typeof entry.intent.id === 'string' &&
        normalizeTableToken(entry.tableToken) === entry.tableToken &&
        (entry.tableSessionId === null || Number.isSafeInteger(entry.tableSessionId) && entry.tableSessionId > 0)) return entry
  } catch { /* No usable retry in this WebView. */ }
}

// An unresolved SDK command can only be checked against its original session.
type SdkRejoinAttempt = { sessionId: number | null; tokenDigest: string; requiresScan?: boolean; intentId?: string }

function sdkRejoinKey(): string | null {
  return getTelegramContext().telegramUserId ? resolveTableSessionStorageKey() + ':sdk-rejoin' : null
}

export function pendingSdkRejoin(): SdkRejoinAttempt | null {
  const key = sdkRejoinKey()
  if (!key) return null
  const raw = window.sessionStorage.getItem(key)
  if (!raw) return null
  const value = JSON.parse(raw)
  if ((value.sessionId !== null && (!Number.isSafeInteger(value.sessionId) || value.sessionId <= 0)) || !/^[a-f0-9]{64}$/.test(value.tokenDigest)) {
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

function sameVisit(a: VisitPointer, b: VisitPointer): boolean {
  if (a === b) return true
  return !!a && typeof a === 'object' && !!b && typeof b === 'object' &&
    a.tableToken === b.tableToken && a.tableSessionId === b.tableSessionId && (a.visitGeneration ?? '0') === (b.visitGeneration ?? '0')
}

function storedVisit(): VisitPointer {
  if (!getTelegramContext().telegramUserId) return null
  try { return parseVisit(window.sessionStorage.getItem(resolveTableSessionStorageKey())) } catch { return null }
}

function confirmedVisit() {
  if ((currentSnapshot.tableSessionActive || currentSnapshot.status === 'error') && currentSnapshot.tableToken && currentSnapshot.tableSessionId) {
    return visitPointer(currentSnapshot.tableToken, currentSnapshot.tableSessionId, currentSnapshot.visitGeneration)
  }
  return storedVisit()
}

export function hasGuestVisitToResume() {
  return Boolean(confirmedVisit()) || currentSnapshot.status === 'resolving' && Boolean(currentSnapshot.tableToken)
}

function resumePointer(visit: VisitPointer, shared: VisitPointer): VisitPointer {
  if (visit) return visit
  const navigation = window.history.state?.hookahGuestNavigation
  if (navigation?.resumeTable) return 'lost'
  if (navigation?.visitEnded) return null
  return shared
}

function requestNewScan(reason: 'lost' | 'unavailable') {
  if (getTelegramContext().telegramUserId) {
    try { window.sessionStorage.setItem(resolveTableSessionStorageKey(), JSON.stringify(reason)) } catch { /* optional */ }
  }
  updateSnapshot(buildEmptySnapshot('rescan'))
}
function rememberTableSessionContext(tableToken: string, tableSessionId: number, generation?: string | null) {
  if (typeof window === 'undefined' || !getTelegramContext().telegramUserId || tableSessionId <= 0) {
    return
  }
  const key = resolveTableSessionStorageKey()
  const pointer = JSON.stringify(visitPointer(tableToken, tableSessionId, generation))
  try { window.sessionStorage.setItem(key, pointer) } catch { /* optional */ }
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
  failedLoad = null
  clearPendingLaunch(pendingIntent?.key)
  if (pendingIntent) void cancelSelection(pendingIntent)
  pendingIntent = null
  activeController?.abort()
  activeController = null
  requestCounter += 1
  inFlight = null
  if (currentSnapshot.status === 'resolving') updateSnapshot(stableSnapshot)
}

export function prepareVisitExit(visit: StoredTableSessionContext) {
  return prepareExit(resolveTableSessionStorageKey(), visit)
}

export async function clearCurrentTableContext(endedVisit?: StoredTableSessionContext, exit?: ReturnType<typeof prepareVisitExit>) {
  const key = resolveTableSessionStorageKey()
  if (exit && exit.key !== key) return false
  cancelPendingTableRefresh()
  const version = requestCounter
  const live = () => version === requestCounter && key === resolveTableSessionStorageKey()
  const tableToken = currentSnapshot.tableToken
  if (endedVisit) {
    await finishExit(exit ?? prepareVisitExit(endedVisit), endedVisit)
    if (!live()) return false
    if (sameVisit(storedVisit(), endedVisit)) forgetTableSessionContext(endedVisit.tableToken)
  } else {
    forgetTableSessionContext(tableToken)
  }
  if (currentSnapshot.tableSessionId) setSelectedGuestTabId(currentSnapshot.tableSessionId, null)
  forgetTableToken(tableToken)
  updateSnapshot(buildEmptySnapshot('missing'))
  return true
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
  const tableSessionActive = payload.tableSessionActive === true
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
    visitGeneration: payload.visitGeneration ?? undefined,
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
  retryableScan?: boolean
  retrySessionId?: number
  verifyOnly?: boolean
  entry?: LaunchEntry
  selection?: Selection
  confirmRejoin?: (table: TableResolveResponse) => boolean
}): Promise<TableContextSnapshot | null> {
  failedLoad = null
  const entry = options?.entry
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
  const tableTokenStatus = entry ? 'valid' : launch.unboundBotTableButton ? 'missing' : launch.tableTokenStatus
  const tableToken = entry?.tableToken ?? launch.tableToken
  const tableSessionId = entry ? entry.tableSessionId : launch.tableSessionId
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
  const actor = getTelegramContext().telegramUserId

  const backendUrl = getBackendBaseUrl()
  const deps = buildApiDeps()
  const sessionId = forceResolveSession ? null : contextTableSessionId ?? currentSessionId ?? storedSessionId
  const retrySession = isScan ? options?.retrySessionId : undefined
  const selection = options?.selection ?? (entry ? continuedSelection(entry.intent) : isScan ? startTableSelection() : null)
  const live = () => !controller.signal.aborted && requestId === requestCounter && actor === getTelegramContext().telegramUserId
  const selectionFailure = (result: SelectionResult) => {
    const rejected = buildEmptySnapshot(result === 'unavailable' ? 'selectionUnavailable' : 'rescan')
    if (live() && entry && result === 'superseded') clearPendingLaunch()
    if (live() && !isScan) updateSnapshot(rejected)
    return rejected
  }
  const resolveRequest = async () => {
    if (selection) {
      pendingIntent = selection.intent
      const ready = await selection.ready
      if (!live()) return null
      if (!actor || selection.intent.key !== resolveTableSessionStorageKey()) return selectionFailure('superseded')
      const admission = ready === 'current' ? await checkSelection(selection.intent, live) : ready
      if (!live()) return null
      if (admission !== 'current') return selectionFailure(admission)
      if (entry) {
        try { window.sessionStorage.setItem(entry.intent.key + ':pending-launch', JSON.stringify(entry)) } catch { /* in-memory retry remains */ }
      }
    }
    // A passive exact read can prove that the saved visit ended. Capture the
    // confirmed revision before HTTP so a late denial cannot erase a later admission.
    const exact = !selection && sessionId && actor ? visitPointer(nextToken, sessionId, stored && typeof stored === 'object' && stored.tableSessionId === sessionId ? stored.visitGeneration : undefined) : null
    const saved = exact ? await readSelection(resolveTableSessionStorageKey(), live) : null
    if (!live()) return null
    if (exact && !saved) return selectionFailure('unavailable')
    const response = await guestResolveTable(backendUrl, nextToken, deps, controller.signal, {
      tableSessionId: retrySession ?? sessionId,
      verifyOnly: options?.verifyOnly === true || retrySession !== undefined || (sessionId !== null && (sessionId === storedSessionId || sessionId === currentSessionId)),
      allowCreateSession: forceResolveSession && retrySession === undefined
    })
    if (!live()) return null
    if (response.ok && response.data.visitProtocol !== 1) {
      const failure = { ...buildEmptySnapshot('error', nextToken), error: { code: 'VISIT_PROTOCOL_UNAVAILABLE', status: 503 } }
      failedLoad = { userId: actor, entry }
      if (!isScan) updateSnapshot(failure)
      return failure
    }
    if (response.ok && response.data.available && response.data.tableSessionActive && !validGeneration(response.data.visitGeneration)) {
      return selectionFailure('unavailable')
    }
    if (exact && saved) {
      if (response.ok ? response.data.available && !response.data.tableSessionActive : finalTableDenial(response.error)) {
        await forgetRejectedVisit(resolveTableSessionStorageKey(), exact, saved, live)
        if (!live()) return null
      } else if (response.ok && response.data.tableSessionActive && response.data.available) {
        const verified = visitPointer(nextToken, sessionId!, response.data.visitGeneration)
        const observed = await confirmObservation(resolveTableSessionStorageKey(), saved, verified, false, live)
        if (!live()) return null
        if (observed !== 'current') return selectionFailure(observed)
      }
    }
    let result = response
    if (retrySession !== undefined && result.ok && (result.data.tableSessionId !== retrySession ||
        (!result.data.tableSessionActive && result.data.tableSessionInactiveReason !== 'TABLE_SESSION_EXITED'))) {
      return buildEmptySnapshot('rescan')
    }
    if (isScan && result.ok && result.data.tableSessionInactiveReason === 'TABLE_SESSION_EXITED') {
      const table = result.data
      if (selection) {
        const eligibility = await checkSelection(selection.intent, live)
        if (!live()) return null
        if (eligibility !== 'current') return selectionFailure(eligibility)
      }
      if (currentSnapshot.tableToken === nextToken && currentSnapshot.tableSessionId === table.tableSessionId) {
        updateSnapshot(buildResolvedSnapshot(table, nextToken))
      }
      if (!table.exitEventId) return buildEmptySnapshot('rejoinUnavailable')
      if (table.available && !table.tableSessionActive && options.confirmRejoin?.(table)) {
        // Another window can choose a table while the confirmation dialog is open.
        if (selection) {
          const eligibility = await checkSelection(selection.intent, live)
          if (!live()) return null
          if (eligibility !== 'current') return selectionFailure(eligibility)
        }
        result = await guestRejoinTable(backendUrl, nextToken, table.tableSessionId, table.exitEventId, deps, controller.signal)
        if (!result.ok && !controller.signal.aborted && requestId === requestCounter) {
          if (result.error.status === 409) return buildEmptySnapshot('rescan')
          if (result.error.status === 403) return buildEmptySnapshot('rejoinUnavailable')
          return { ...buildEmptySnapshot('error'), error: result.error }
        }
        if (result.ok && (result.data.visitProtocol !== 1 || result.data.tableSessionId !== table.tableSessionId ||
            result.data.visitGeneration !== table.exitEventId)) return buildEmptySnapshot('rescan')
      }
    }
    if (!live()) return null

    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return null
      const failure = result.error.status === 404
        ? buildEmptySnapshot('notFound')
        : { ...buildEmptySnapshot('error', nextToken), tableSessionId: sessionId, visitGeneration: exact?.visitGeneration, error: result.error }
      if (!isScan) {
        if (isTemporaryError(result.error) || serviceSuspended(result.error.code)) {
          failedLoad = { userId: actor, entry }
          if (!entry && actor && sessionId && (sessionId === storedSessionId || sessionId === currentSessionId)) {
            await retainLegacyPointer(resolveTableSessionStorageKey(), { tableToken: nextToken, tableSessionId: sessionId }, live)
            if (!live()) return null
          }
        } else if (entry) {
          clearPendingLaunch()
        }
        updateSnapshot(failure)
      }
      return failure
    }

    if (entry && entry.tableSessionId !== null && result.data.tableSessionId !== entry.tableSessionId) {
      return selectionFailure('superseded')
    }
    const resolved = buildResolvedSnapshot(result.data, nextToken)
    if (isScan && !resolved.orderAllowed) return resolved

    if (selection && resolved.orderAllowed) {
      if (!Number.isSafeInteger(result.data.tableSessionId) || result.data.tableSessionId <= 0) return selectionFailure('superseded')
      const session = result.data.tableSessionId
      const committed = await confirmSelection(selection.intent, live, visitPointer(nextToken, session, result.data.visitGeneration))
      if (!live()) return null
      if (committed !== 'current') return selectionFailure(committed)
      pendingIntent = null
    }
    if (entry) clearPendingLaunch()
    if (result.data.tableSessionActive && result.data.available) {
      const needsChoice = await cartRequiresTabChoice(nextToken, result.data.tableSessionId, result.data.visitGeneration ?? undefined)
      if (!live() || knownEnded(resolveTableSessionStorageKey(), visitPointer(nextToken, result.data.tableSessionId, result.data.visitGeneration))) return selectionFailure('superseded')
      bindGuestTabParticipation(result.data.tableSessionId, result.data.visitGeneration!)
      if (needsChoice) {
        restoreGuestTabSelection(result.data.tableSessionId, true)
      }
      rememberTableToken(nextToken)
      rememberTableSessionContext(nextToken, result.data.tableSessionId, result.data.visitGeneration)
    } else if (!selection && result.data.available) {
      forgetTableSessionContext(nextToken)
    }
    updateSnapshot(resolved)
    return resolved
  }

  const resolution = resolveRequest().then(async result => {
    if (selection && live() && result && !result.orderAllowed) {
      const mayRetry = (entry || options?.retryableScan) && result.error && isTemporaryError(result.error)
      if (!mayRetry) {
        await cancelSelection(selection.intent)
        if (live()) pendingIntent = null
      }
    }
    return result
  })
  inFlight = resolution
  return resolution
}

async function restoreActiveContext(): Promise<void> {
  failedLoad = null
  if (activeController) {
    activeController.abort()
  }
  const controller = new AbortController()
  activeController = controller
  const key = resolveTableSessionStorageKey()
  const requestId = ++requestCounter
  const actor = getTelegramContext().telegramUserId
  const live = () => !controller.signal.aborted && requestId === requestCounter && actor === getTelegramContext().telegramUserId
  const backendUrl = getBackendBaseUrl()
  const deps = buildApiDeps()
  const windowVisit = confirmedVisit()
  updateSnapshot(buildEmptySnapshot('resolving'))
  inFlight = (async () => {
    const shared = actor ? await readSelection(key, live) : { pointer: null, revision: null, history: {} }
    if (!live()) return
    if (!shared) {
      failedLoad = { userId: actor }
      updateSnapshot({ ...buildEmptySnapshot('error'), error: { status: 0, code: 'VISIT_STORAGE_UNAVAILABLE' } })
      return
    }
    const visit = resumePointer(windowVisit, shared.pointer)
    if (typeof visit === 'string') { requestNewScan(visit); return }
    if (visit) rememberTableSessionContext(visit.tableToken, visit.tableSessionId, visit.visitGeneration)
    updateSnapshot(buildEmptySnapshot('resolving', visit?.tableToken))
    const result = await guestRestoreTable(backendUrl, deps, controller.signal, visit)
    if (!live()) return
    const rejectExactVisit = async (target: StoredTableSessionContext) => {
      await forgetRejectedVisit(key, target, shared, live)
      if (!live()) return
      forgetTableSessionContext(target.tableToken)
      forgetTableToken(target.tableToken)
      requestNewScan('unavailable')
    }
    if (!result.ok) {
      if (visit && finalTableDenial(result.error)) {
        await rejectExactVisit(visit)
        return
      }
      if (isTemporaryError(result.error) || serviceSuspended(result.error.code)) {
        failedLoad = { userId: actor }
        if (actor && visit) await retainLegacyPointer(key, visit, live)
        if (!live()) return
      }
      updateSnapshot({ ...buildEmptySnapshot('error', visit?.tableToken), tableSessionId: visit?.tableSessionId ?? null, visitGeneration: visit?.visitGeneration, error: result.error })
      return
    }

    if (result.data.visitProtocol !== 1) {
      failedLoad = { userId: actor }
      updateSnapshot({ ...buildEmptySnapshot('error', visit?.tableToken), error: { code: 'VISIT_PROTOCOL_UNAVAILABLE', status: 503 } })
      return
    }
    if (visit && result.data.unavailableReason) {
      failedLoad = { userId: actor }
      updateSnapshot({ ...buildEmptySnapshot('error', visit.tableToken), tableSessionId: visit.tableSessionId,
        visitGeneration: visit.visitGeneration, error: { code: result.data.unavailableReason, status: 403 } })
      return
    }
    const restored = result.data.context
    if (visit && (!restored?.tableSessionActive || !restored.available || restored.tableSessionId !== visit.tableSessionId ||
        restored.tableToken !== visit.tableToken)) {
      await rejectExactVisit(visit)
      return
    }
    if (!restored?.tableSessionActive || !restored.available) {
      updateSnapshot(buildEmptySnapshot('missing'))
      return
    }

    if (!validGeneration(restored.visitGeneration)) {
      updateSnapshot(buildEmptySnapshot('selectionUnavailable'))
      return
    }
    const verified = visitPointer(restored.tableToken, restored.tableSessionId, restored.visitGeneration)
    const observed = actor ? await confirmObservation(key, shared, verified, !visit, live) : 'current'
    if (!live()) return
    if (observed === 'superseded') {
      requestNewScan('unavailable')
      return
    }
    if (observed === 'unavailable') {
      failedLoad = { userId: actor }
      updateSnapshot({ ...buildEmptySnapshot('error', visit?.tableToken), tableSessionId: visit?.tableSessionId ?? null,
        visitGeneration: visit?.visitGeneration, error: { status: 0, code: 'VISIT_STORAGE_UNAVAILABLE' } })
      return
    }
    const needsChoice = await cartRequiresTabChoice(restored.tableToken, restored.tableSessionId, restored.visitGeneration)
    if (!live()) return
    if (knownEnded(key, verified)) { requestNewScan('unavailable'); return }
    bindGuestTabParticipation(restored.tableSessionId, restored.visitGeneration)
    rememberTableToken(restored.tableToken)
    rememberTableSessionContext(restored.tableToken, restored.tableSessionId, restored.visitGeneration)
    restoreGuestTabSelection(restored.tableSessionId, needsChoice)
    updateSnapshot(buildResolvedSnapshot(restored, restored.tableToken))
  })()

  await inFlight
}

function serviceSuspended(code?: string) {
  return code === 'SERVICE_SUSPENDED' || code === 'SUBSCRIPTION_BLOCKED'
}

function finalTableDenial({ status, code }: ApiErrorInfo) {
  return !serviceSuspended(code) && (status === 403 || status === 404 || status === 410)
}

export function isTemporaryError({ status }: ApiErrorInfo): boolean {
  return status === 0 || status === 408 || status === 429 || status >= 500
}

export function canRetryTableLoad(snapshot: TableContextSnapshot): boolean {
  return failedLoad !== null && snapshot.status === 'error' && !!snapshot.error && (isTemporaryError(snapshot.error) || serviceSuspended(snapshot.error.code))
}

export function retryTableLoad(): void {
  if (!failedLoad || !canRetryTableLoad(currentSnapshot)) return
  const { userId, entry } = failedLoad
  const launch = getTelegramContext()
  if (launch.telegramUserId !== userId || (entry && launch.tableTokenStatus !== 'missing' &&
      (launch.tableToken !== entry.tableToken || launch.tableSessionId !== entry.tableSessionId))) {
    failedLoad = null
    clearPendingLaunch(entry?.intent.key)
    updateSnapshot(buildEmptySnapshot('rescan'))
    return
  }
  if (entry) void refresh({ entry, forceResolveSession: entry.forceResolveSession })
  else if (launch.tableTokenAutoResolve && launch.tableToken) void refresh()
  else void restoreActiveContext()
}

// Both work-mode entry points consult the same authenticated view of the visit.
export const botVisitExitHint = 'Если кнопки завершения здесь нет, откройте чат с ботом и нажмите «🚪 Завершить визит».'

export async function checkGuestVisit(backendUrl: string, signal: AbortSignal): Promise<'active' | 'absent' | 'unknown'> {
  if (inFlight) await inFlight
  const version = requestCounter
  const result = await guestRestoreTable(backendUrl, buildApiDeps(), signal, 'active-visits')
  if (!result.ok || version !== requestCounter || signal.aborted) return 'unknown'
  if (result.data.visitProtocol !== 1) return 'unknown'
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
    case 'selectionUnavailable':
      return { title: 'Не удалось сохранить выбор стола.', details: 'Проверьте разрешение на хранение данных и повторите вход по QR.', severity: 'error' }
    case 'rejoinUnavailable':
      return { title: 'Прежний визит недоступен.', details: 'Откройте меню текущего стола в боте или обратитесь к персоналу.', severity: 'warn' }
    case 'notFound':
      return { title: 'Стол не найден. Обновите QR.', severity: 'error' }
    case 'resolving':
      return { title: 'Загрузка стола…', severity: 'warn' }
    case 'error':
      if (serviceSuspended(snapshot.error?.code)) {
        return { title: 'Обслуживание временно приостановлено.', details: 'Корзина сохранена. Проверьте доступность стола позже.', severity: 'warn' }
      }
      if (snapshot.error?.code === 'VISIT_PROTOCOL_UNAVAILABLE') {
        return { title: 'Сервер обновляется.', details: 'Повторите загрузку стола после обновления. Корзина сохранена.', severity: 'warn' }
      }
      if (snapshot.error?.code === 'VISIT_STORAGE_UNAVAILABLE') {
        return { title: 'Хранилище посещения временно недоступно.', details: 'Проверьте разрешение на хранение данных и повторите загрузку стола.', severity: 'error' }
      }
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
      return { title: 'Не удалось загрузить стол. Проверьте соединение и повторите попытку.', severity: 'error' }
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
  const { tableTokenStatus, tableToken, tableTokenAutoResolve, tableTokenFromUrl, tableSessionId, telegramUserId } = getTelegramContext()
  const pointer = storedVisit()
  // A reload of the previous Guest entry is passive. Only a new launch URL or the
  // scanner can identify a replacement for a lost pointer.
  const navigation = window.performance?.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined
  const freshQr = tableTokenAutoResolve && tableTokenFromUrl && !window.history.state?.hookahGuestNavigation &&
    navigation?.type !== 'reload' && navigation?.type !== 'back_forward'
  const previous = pendingLaunch()
  if (navigation?.type === 'reload' && previous && !window.history.state?.hookahGuestNavigation?.visitEnded &&
      (tableTokenStatus === 'missing' || (tableToken === previous.tableToken && tableSessionId === previous.tableSessionId))) {
    if (previous.tableSessionId !== null) {
      void refresh({ entry: previous, verifyOnly: true })
    } else {
      // The session is unknown. Only the retry button may repeat a QR admission.
      failedLoad = { userId: telegramUserId, entry: previous }
      updateSnapshot({ ...buildEmptySnapshot('error', previous.tableToken), error: { status: 0 } })
    }
    return
  }
  clearPendingLaunch()
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
    // Capture the explicit choice before Guest navigation rewrites the launch history.
    const forceResolveSession = typeof pointer === 'string' && freshQr && tableSessionId === null
    const selection = freshQr && telegramUserId && !getTelegramContext().unboundBotTableButton ? startTableSelection() : undefined
    const entry = selection ? { tableToken, tableSessionId, forceResolveSession, intent: selection.intent } : undefined
    void refresh({ selection, entry, forceResolveSession })
    return
  }
  if (tableTokenStatus === 'missing') {
    updateSnapshot(buildEmptySnapshot('resolving'))
    void restoreActiveContext()
    return
  }
  updateSnapshot(buildEmptySnapshot(resolveTokenStatus(tableTokenStatus)))
}


if (typeof window !== 'undefined') window.addEventListener('hookah-visit-ended', () => {
  const snapshot = currentSnapshot
  if (snapshot.tableToken && snapshot.tableSessionId && snapshot.visitGeneration !== undefined &&
      knownEnded(resolveTableSessionStorageKey(), visitPointer(snapshot.tableToken, snapshot.tableSessionId, snapshot.visitGeneration))) {
    updateSnapshot(buildEmptySnapshot('rescan'))
  }
})
