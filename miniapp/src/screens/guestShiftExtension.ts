import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  guestCreateShiftExtensionRequest,
  guestGetActiveOrder,
  guestGetShiftExtensionOptions,
  guestGetTabs
} from '../shared/api/guestApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type {
  ActiveOrderDto,
  GuestShiftExtensionOptionsResponse,
  GuestTabDto,
  ShiftExtensionRequestDto
} from '../shared/api/guestDtos'
import { getSelectedGuestTabId, setSelectedGuestTabId } from '../shared/state/guestTabSelection'
import { getTableContext, subscribe as subscribeTable } from '../shared/state/tableContext'
import { getTelegramContext } from '../shared/telegram'
import { append, el } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'
import { showToast } from '../shared/ui/toast'

const POLL_INTERVAL_MS = 15000
const LAST_REQUEST_STORAGE_KEY = 'hookahGuestShiftExtensionLastRequest'

export type GuestShiftExtensionAvailability = {
  visible: boolean
  enabled: boolean
  pending: boolean
  copy?: string
  unavailableText?: string
  unavailableReason?: string | null
}

type GuestShiftExtensionRenderMode = 'standalone' | 'menuDetail'

type GuestShiftExtensionOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number | null
  mode?: GuestShiftExtensionRenderMode
  onAvailabilityChange?: (availability: GuestShiftExtensionAvailability) => void
}

type ExtensionRefs = {
  title: HTMLHeadingElement
  body: HTMLParagraphElement
  meta: HTMLParagraphElement
  message: HTMLParagraphElement
  button: HTMLButtonElement
}

type ExtensionScope = {
  tableToken: string
  tableSessionId: number
  tabId: number
}

type LastExtensionRequest = {
  requestId: number
  tableSessionId: number
  tabId: number
  requestedUntil: string
  status: 'pending' | 'confirmed' | 'missing'
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function normalizePositiveId(value: number | null | undefined): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || !Number.isInteger(value) || value <= 0) {
    return null
  }
  return value
}

function resolveStorageKey(tableSessionId: number, tabId: number): string {
  const userId = getTelegramContext().telegramUserId
  const userPart = userId ? `user:${userId}` : 'user:unknown'
  return `${LAST_REQUEST_STORAGE_KEY}:${userPart}:session:${tableSessionId}:tab:${tabId}`
}

function readLastRequest(tableSessionId: number, tabId: number): LastExtensionRequest | null {
  if (typeof window === 'undefined') {
    return null
  }
  try {
    const raw = window.sessionStorage.getItem(resolveStorageKey(tableSessionId, tabId))
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as Partial<LastExtensionRequest>
    const requestId = normalizePositiveId(parsed.requestId)
    const storedSessionId = normalizePositiveId(parsed.tableSessionId)
    const storedTabId = normalizePositiveId(parsed.tabId)
    if (!requestId || storedSessionId !== tableSessionId || storedTabId !== tabId || !parsed.requestedUntil) {
      return null
    }
    return {
      requestId,
      tableSessionId,
      tabId,
      requestedUntil: parsed.requestedUntil,
      status: parsed.status === 'confirmed' || parsed.status === 'missing' ? parsed.status : 'pending'
    }
  } catch {
    return null
  }
}

function rememberLastRequest(scope: ExtensionScope, request: ShiftExtensionRequestDto, status: LastExtensionRequest['status']) {
  if (typeof window === 'undefined') {
    return
  }
  try {
    const value: LastExtensionRequest = {
      requestId: request.id,
      tableSessionId: scope.tableSessionId,
      tabId: scope.tabId,
      requestedUntil: request.requestedUntil,
      status
    }
    window.sessionStorage.setItem(resolveStorageKey(scope.tableSessionId, scope.tabId), JSON.stringify(value))
  } catch {
    // Extension state is recoverable from the backend; storage only improves refresh copy.
  }
}

function updateLastRequest(scope: ExtensionScope, request: LastExtensionRequest, status: LastExtensionRequest['status']) {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.sessionStorage.setItem(
      resolveStorageKey(scope.tableSessionId, scope.tabId),
      JSON.stringify({ ...request, status })
    )
  } catch {
    // ignore session storage errors
  }
}

function chooseTab(tabs: GuestTabDto[], tableSessionId: number): GuestTabDto | null {
  const activeTabs = tabs.filter((tab) => tab.status === 'ACTIVE' && tab.tableSessionId === tableSessionId)
  const storedTabId = getSelectedGuestTabId(tableSessionId)
  const storedTab = storedTabId != null ? activeTabs.find((tab) => tab.id === storedTabId) ?? null : null
  if (storedTab) {
    return storedTab
  }
  return activeTabs.find((tab) => tab.type === 'PERSONAL') ?? activeTabs[0] ?? null
}

function formatDuration(minutes: number | null | undefined): string {
  if (minutes === 60 || minutes == null) {
    return '1 час'
  }
  return `${minutes} минут`
}

function extractTimeLabel(value: string | null | undefined): string | null {
  if (!value) {
    return null
  }
  const match = value.match(/T(\d{2}:\d{2})/)
  if (match?.[1]) {
    return match[1]
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return null
  }
  return parsed.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
}

function optionCopy(option: GuestShiftExtensionOptionsResponse): string {
  const duration = formatDuration(option.durationMinutes)
  const price = formatPrice(option.priceMinor ?? 0, option.currency || 'RUB')
  return `Продление на ${duration} — ${price}. Персонал подтвердит возможность продления.`
}

function isKnownAuthError(error: ApiErrorInfo): boolean {
  const code = normalizeErrorCode(error)
  return code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID
}

function hasConfirmedCharge(order: ActiveOrderDto, requestId: number): boolean {
  return (order.serviceCharges ?? []).some((charge) => charge.sourceRequestId === requestId)
}

function buildDom(root: HTMLDivElement, mode: GuestShiftExtensionRenderMode): ExtensionRefs {
  const card = el('section', { className: 'card shift-extension-card' })
  const title = el('h3', { text: mode === 'menuDetail' ? 'Продление работы заведения' : 'Продление времени' })
  const body = el('p', { className: 'shift-extension-copy', text: '' })
  const meta = el('p', { className: 'venue-order-sub', text: '' })
  const message = el('p', { className: 'shift-extension-message', text: '' })
  message.hidden = true
  const button = el('button', { className: 'button-secondary', text: 'Продлить на 1 час' }) as HTMLButtonElement
  append(card, title, body, meta, message, button)
  root.replaceChildren(card)
  return { title, body, meta, message, button }
}

export function renderGuestShiftExtensionCard(options: GuestShiftExtensionOptions) {
  const { root, backendUrl, isDebug, venueId, mode = 'standalone', onAvailabilityChange } = options
  if (!root) return () => undefined

  const deps = buildApiDeps(isDebug)
  let disposed = false
  let tableSnapshot = getTableContext()
  let refs: ExtensionRefs | null = null
  let loadAbort: AbortController | null = null
  let actionAbort: AbortController | null = null
  let pollTimer: number | null = null
  let isLoading = false
  let isSubmitting = false
  let currentScope: ExtensionScope | null = null
  let currentOption: GuestShiftExtensionOptionsResponse | null = null
  let currentPendingRequest: ShiftExtensionRequestDto | null = null
  let loadSeq = 0
  let availabilityKey = ''

  const emitAvailability = (availability: GuestShiftExtensionAvailability) => {
    if (!onAvailabilityChange) return
    const key = [
      availability.visible,
      availability.enabled,
      availability.pending,
      availability.copy ?? '',
      availability.unavailableText ?? '',
      availability.unavailableReason ?? ''
    ].join('|')
    if (key === availabilityKey) return
    availabilityKey = key
    onAvailabilityChange(availability)
  }

  const clearCard = () => {
    refs = null
    root.replaceChildren()
  }

  const hideCard = (unavailableReason: string | null = null) => {
    clearCard()
    currentPendingRequest = null
    emitAvailability({ visible: false, enabled: false, pending: false, unavailableReason })
  }

  const ensureRefs = () => {
    if (!refs) {
      refs = buildDom(root, mode)
      refs.button.addEventListener('click', () => void submitRequest())
    }
    return refs
  }

  const setMessage = (text: string, tone: 'info' | 'success' | 'error' = 'info') => {
    const currentRefs = ensureRefs()
    currentRefs.message.textContent = text
    currentRefs.message.hidden = !text
    currentRefs.message.dataset.tone = tone
  }

  const resolveBaseScope = () => {
    if (
      !venueId ||
      tableSnapshot.status !== 'resolved' ||
      tableSnapshot.venueId !== venueId ||
      !tableSnapshot.tableToken ||
      !tableSnapshot.tableSessionId ||
      !tableSnapshot.orderAllowed
    ) {
      return null
    }
    return {
      tableToken: tableSnapshot.tableToken,
      tableSessionId: tableSnapshot.tableSessionId
    }
  }

  const renderLoading = () => {
    const currentRefs = ensureRefs()
    currentRefs.body.textContent = 'Проверяем возможность продления…'
    currentRefs.meta.textContent = ''
    currentRefs.button.disabled = true
    currentRefs.button.textContent = 'Загрузка…'
    currentRefs.message.hidden = true
  }

  const renderAvailable = (option: GuestShiftExtensionOptionsResponse) => {
    const currentRefs = ensureRefs()
    const copy = optionCopy(option)
    currentRefs.body.textContent = copy
    const proposedTime = extractTimeLabel(option.proposedOrderableUntil)
    currentRefs.meta.textContent = proposedTime ? `После подтверждения можно заказывать до ${proposedTime}.` : ''
    currentRefs.button.textContent = isSubmitting ? 'Отправляем…' : 'Продлить на 1 час'
    currentRefs.button.disabled = isSubmitting
    currentPendingRequest = null
    setMessage('', 'info')
    emitAvailability({
      visible: true,
      enabled: !isSubmitting,
      pending: false,
      copy,
      unavailableReason: null
    })
  }

  const renderPending = (request: ShiftExtensionRequestDto) => {
    const currentRefs = ensureRefs()
    const copy = optionCopy({
      available: true,
      durationMinutes: request.durationMinutes,
      priceMinor: request.priceMinor,
      currency: request.currency
    })
    currentRefs.body.textContent = copy
    const requestedUntil = extractTimeLabel(request.requestedUntil)
    currentRefs.meta.textContent = requestedUntil ? `Запрошено до ${requestedUntil}.` : ''
    currentRefs.button.textContent = 'Ожидает подтверждения'
    currentRefs.button.disabled = true
    currentPendingRequest = request
    setMessage('Ожидает подтверждения', 'info')
    emitAvailability({
      visible: true,
      enabled: false,
      pending: true,
      copy,
      unavailableReason: null
    })
  }

  const renderConfirmed = (request: LastExtensionRequest) => {
    const currentRefs = ensureRefs()
    const timeLabel = extractTimeLabel(request.requestedUntil)
    currentRefs.body.textContent = timeLabel
      ? `Продление подтверждено до ${timeLabel}. Сумма добавлена в счёт.`
      : 'Продление подтверждено. Сумма добавлена в счёт.'
    currentRefs.meta.textContent = ''
    currentRefs.button.textContent = 'Продлить на 1 час'
    currentRefs.button.disabled = isSubmitting || !currentOption?.available
    currentPendingRequest = null
    setMessage('', 'success')
    emitAvailability({
      visible: true,
      enabled: Boolean(currentOption?.available) && !isSubmitting,
      pending: false,
      copy: currentRefs.body.textContent ?? undefined,
      unavailableReason: null
    })
  }

  const renderMissingAfterPending = () => {
    const currentRefs = ensureRefs()
    currentRefs.body.textContent = 'Запрос на продление отклонён или больше не активен. Счёт не изменён.'
    currentRefs.meta.textContent = ''
    currentRefs.button.textContent = 'Продлить на 1 час'
    currentRefs.button.disabled = isSubmitting || !currentOption?.available
    currentPendingRequest = null
    setMessage('', 'info')
    emitAvailability({
      visible: true,
      enabled: Boolean(currentOption?.available) && !isSubmitting,
      pending: false,
      unavailableText: currentRefs.body.textContent ?? undefined,
      unavailableReason: null
    })
  }

  const evaluateLastRequest = async (scope: ExtensionScope, option: GuestShiftExtensionOptionsResponse) => {
    const lastRequest = readLastRequest(scope.tableSessionId, scope.tabId)
    if (!lastRequest || option.pendingRequest) {
      return false
    }
    const activeOrder = await guestGetActiveOrder(
      backendUrl,
      scope.tableToken,
      scope.tableSessionId,
      scope.tabId,
      deps
    )
    if (disposed) {
      return true
    }
    if (activeOrder.ok && activeOrder.data.order && hasConfirmedCharge(activeOrder.data.order, lastRequest.requestId)) {
      updateLastRequest(scope, lastRequest, 'confirmed')
      renderConfirmed({ ...lastRequest, status: 'confirmed' })
      return true
    }
    if (!activeOrder.ok && activeOrder.error.code === REQUEST_ABORTED_CODE) {
      return true
    }
    if (lastRequest.status === 'pending') {
      updateLastRequest(scope, lastRequest, 'missing')
      renderMissingAfterPending()
      return true
    }
    if (lastRequest.status === 'confirmed') {
      renderConfirmed(lastRequest)
      return true
    }
    if (lastRequest.status === 'missing') {
      renderMissingAfterPending()
      return true
    }
    return false
  }

  const resolveScope = async (controller: AbortController): Promise<ExtensionScope | null> => {
    const baseScope = resolveBaseScope()
    if (!baseScope) {
      return null
    }
    const tabsResult = await guestGetTabs(backendUrl, baseScope.tableSessionId, deps, controller.signal)
    if (controller.signal.aborted || disposed) {
      return null
    }
    if (!tabsResult.ok) {
      if (tabsResult.error.code === REQUEST_ABORTED_CODE) {
        return null
      }
      if (isKnownAuthError(tabsResult.error)) {
        clearSession()
      }
      hideCard('TAB_LOOKUP_UNAVAILABLE')
      return null
    }
    const selectedTab = chooseTab(tabsResult.data.tabs, baseScope.tableSessionId)
    if (!selectedTab) {
      hideCard('ACTIVE_TAB_NOT_FOUND')
      return null
    }
    setSelectedGuestTabId(baseScope.tableSessionId, selectedTab.id)
    return {
      ...baseScope,
      tabId: selectedTab.id
    }
  }

  const load = async (options?: { silent?: boolean }) => {
    const baseScope = resolveBaseScope()
    if (!baseScope) {
      clearCard()
      emitAvailability({ visible: false, enabled: false, pending: false, unavailableReason: null })
      currentScope = null
      currentOption = null
      currentPendingRequest = null
      return
    }
    if (isLoading) {
      return
    }
    isLoading = true
    if (!options?.silent) {
      renderLoading()
    }
    loadAbort?.abort()
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    const scope = await resolveScope(controller)
    if (disposed || loadSeq !== seq || controller.signal.aborted) {
      isLoading = false
      return
    }
    if (!scope) {
      isLoading = false
      loadAbort = null
      return
    }
    currentScope = scope
    const optionResult = await guestGetShiftExtensionOptions(backendUrl, scope, deps, controller.signal)
    if (disposed || loadSeq !== seq || controller.signal.aborted) {
      isLoading = false
      return
    }
    isLoading = false
    loadAbort = null
    if (!optionResult.ok) {
      if (optionResult.error.code === REQUEST_ABORTED_CODE) {
        return
      }
      if (isKnownAuthError(optionResult.error)) {
        clearSession()
      }
      if (normalizeErrorCode(optionResult.error) === ApiErrorCodes.NOT_FOUND) {
        hideCard('ACTIVE_ORDER_NOT_FOUND')
        return
      }
      hideCard('EXTENSION_OPTIONS_UNAVAILABLE')
      return
    }
    currentOption = optionResult.data
    const pending = optionResult.data.pendingRequest ?? null
    if (pending) {
      rememberLastRequest(scope, pending, 'pending')
      renderPending(pending)
      return
    }
    const renderedFromLastRequest = await evaluateLastRequest(scope, optionResult.data)
    if (renderedFromLastRequest) {
      return
    }
    if (!optionResult.data.available || optionResult.data.priceMinor == null) {
      hideCard(optionResult.data.unavailableReason ?? null)
      return
    }
    renderAvailable(optionResult.data)
  }

  const submitRequest = async () => {
    if (isSubmitting || currentPendingRequest) {
      return
    }
    const scope = currentScope
    const option = currentOption
    if (!scope || !option?.available || option.priceMinor == null) {
      setMessage('Продление сейчас недоступно.', 'error')
      return
    }
    isSubmitting = true
    renderAvailable(option)
    actionAbort?.abort()
    const controller = new AbortController()
    actionAbort = controller
    const idempotencyKey = buildIdempotencyKey(scope)
    const result = await guestCreateShiftExtensionRequest(
      backendUrl,
      {
        tableToken: scope.tableToken,
        tableSessionId: scope.tableSessionId,
        tabId: scope.tabId,
        idempotencyKey,
        comment: null
      },
      deps,
      controller.signal
    )
    if (disposed || controller.signal.aborted) {
      return
    }
    actionAbort = null
    isSubmitting = false
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) {
        return
      }
      if (isKnownAuthError(result.error)) {
        clearSession()
      }
      if (option) {
        renderAvailable(option)
      }
      setMessage(result.error.message || 'Не удалось отправить запрос на продление.', 'error')
      return
    }
    rememberLastRequest(scope, result.data.request, 'pending')
    renderPending(result.data.request)
    showToast('Запрос на продление отправлен')
    window.dispatchEvent(new CustomEvent('hookah:shift-extension-requested', { detail: { requestId: result.data.request.id } }))
  }

  const restartPolling = () => {
    if (pollTimer) {
      window.clearInterval(pollTimer)
    }
    pollTimer = window.setInterval(() => {
      void load({ silent: true })
    }, POLL_INTERVAL_MS)
  }

  const tableDispose = subscribeTable((snapshot) => {
    tableSnapshot = snapshot
    void load()
  })
  const refreshHandler = () => void load()
  root.addEventListener('hookah:guest-venue-refresh', refreshHandler)
  const disposables: Array<() => void> = [
    tableDispose,
    () => root.removeEventListener('hookah:guest-venue-refresh', refreshHandler)
  ]

  void load()
  restartPolling()

  return () => {
    disposed = true
    loadAbort?.abort()
    actionAbort?.abort()
    if (pollTimer) {
      window.clearInterval(pollTimer)
    }
    disposables.forEach((dispose) => dispose())
  }
}

function buildIdempotencyKey(scope: ExtensionScope): string {
  const randomPart =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `shift-extension-${scope.tableSessionId}-${scope.tabId}-${randomPart}`
}
