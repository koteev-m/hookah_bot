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
  GuestShiftExtensionRequest,
  ShiftExtensionRequestDto,
  ShiftExtensionTerms
} from '../shared/api/guestDtos'
import { chooseGuestTab } from '../shared/state/guestTabSelection'
import { getTableContext, subscribe as subscribeTable } from '../shared/state/tableContext'
import { transactRecord } from '../shared/state/visitStorage'
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
  userId: number
  expectedVisitGeneration: string
  tableToken: string
  tableSessionId: number
  tabId: number
}

type LastExtensionRequest = { requestId: number; requestedUntil: string }
type ExtensionRecord = { prepared?: GuestShiftExtensionRequest; last?: LastExtensionRequest }

function extensionRecord<T>(scope: ExtensionScope, orderId: number, change: (record: ExtensionRecord) => T) {
  const key = `${LAST_REQUEST_STORAGE_KEY}:user:${scope.userId}:session:${scope.tableSessionId}:` +
    `generation:${scope.expectedVisitGeneration}:tab:${scope.tabId}:order:${orderId}`
  return transactRecord<ExtensionRecord, T>(key, () => ({}), change)
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

function formatDuration(minutes: number | null | undefined): string {
  if (minutes === 60) return '1 час'
  if (minutes === 120 || minutes === 180 || minutes === 240) return `${minutes / 60} часа`
  return `${minutes ?? '—'} мин`
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
  const button = el('button', { className: 'button-secondary', text: 'Продлить' }) as HTMLButtonElement
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
  let displayedAction: {
    scope: ExtensionScope
    orderId: number
    terms?: ShiftExtensionTerms
    retryKey?: string | null
  } | null = null
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
    displayedAction = null
    root.replaceChildren()
  }

  const hideCard = (unavailableReason: string | null = null) => {
    clearCard()
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
    const userId = getTelegramContext().telegramUserId
    if (
      !userId || !venueId ||
      tableSnapshot.status !== 'resolved' ||
      tableSnapshot.venueId !== venueId ||
      !tableSnapshot.tableToken ||
      !tableSnapshot.tableSessionId ||
      tableSnapshot.visitGeneration === undefined ||
      !tableSnapshot.orderAllowed
    ) {
      return null
    }
    return {
      userId,
      tableToken: tableSnapshot.tableToken,
      expectedVisitGeneration: tableSnapshot.visitGeneration,
      tableSessionId: tableSnapshot.tableSessionId
    }
  }

  const renderLoading = () => {
    displayedAction = null
    const currentRefs = ensureRefs()
    currentRefs.body.textContent = 'Проверяем возможность продления…'
    currentRefs.meta.textContent = ''
    currentRefs.button.disabled = true
    currentRefs.button.textContent = 'Загрузка…'
    currentRefs.message.hidden = true
  }

  const displayQuoteAction = (currentRefs: ExtensionRefs, option: GuestShiftExtensionOptionsResponse | null) => {
    const terms = option?.confirmedTerms
    displayedAction = currentScope && option?.available && option.orderId && terms
      ? { scope: currentScope, orderId: option.orderId, terms }
      : null
    const label = terms ? `Продлить на ${formatDuration(terms.durationMinutes)}` : 'Продлить'
    currentRefs.button.textContent = isSubmitting ? 'Отправляем…' : label
    currentRefs.button.disabled = isSubmitting || displayedAction === null
    return displayedAction !== null && !isSubmitting
  }

  const renderAvailable = (option: GuestShiftExtensionOptionsResponse) => {
    const currentRefs = ensureRefs()
    const copy = optionCopy(option.confirmedTerms ? { available: true, ...option.confirmedTerms } : option)
    currentRefs.body.textContent = copy
    const proposedTime = extractTimeLabel(option.proposedOrderableUntil)
    currentRefs.meta.textContent = proposedTime ? `После подтверждения можно заказывать до ${proposedTime}.` : ''
    const terms = option.confirmedTerms
    if (option.orderId && option.tabId) {
      currentRefs.meta.textContent += ` Заказ №${option.orderId}, счёт №${option.tabId}.`
    }
    const enabled = displayQuoteAction(currentRefs, option)
    if (!terms) currentRefs.meta.textContent = 'Обновите Mini App после обновления сервера.'
    setMessage('', 'info')
    emitAvailability({
      visible: true,
      enabled,
      pending: false,
      copy,
      unavailableReason: null
    })
  }

  const renderPending = (request: ShiftExtensionRequestDto) => {
    displayedAction = null
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
    setMessage('Ожидает подтверждения', 'info')
    emitAvailability({
      visible: true,
      enabled: false,
      pending: true,
      copy,
      unavailableReason: null
    })
  }

  const renderConfirmed = () => {
    const currentRefs = ensureRefs()
    const timeLabel = extractTimeLabel(currentOption?.currentOrderableUntil)
    currentRefs.body.textContent = timeLabel
      ? `Продление подтверждено до ${timeLabel}. Сумма добавлена в счёт.`
      : 'Продление подтверждено. Сумма добавлена в счёт.'
    currentRefs.meta.textContent = ''
    const terms = currentOption?.confirmedTerms
    if (terms && currentOption?.available) {
      currentRefs.meta.textContent = optionCopy({ available: true, ...terms }) +
        ` Заказ №${currentOption.orderId}, счёт №${currentOption.tabId}.`
    }
    const enabled = displayQuoteAction(currentRefs, currentOption)
    setMessage('', 'success')
    emitAvailability({
      visible: true,
      enabled,
      pending: false,
      copy: currentRefs.body.textContent ?? undefined,
      unavailableReason: null
    })
  }

  const renderMissingAfterPending = () => {
    const currentRefs = ensureRefs()
    currentRefs.body.textContent = 'Запрос на продление отклонён или больше не активен. Счёт не изменён.'
    currentRefs.meta.textContent = ''
    const terms = currentOption?.confirmedTerms
    if (terms && currentOption?.available) {
      currentRefs.meta.textContent = optionCopy({ available: true, ...terms }) +
        ` Заказ №${currentOption.orderId}, счёт №${currentOption.tabId}.`
    }
    const enabled = displayQuoteAction(currentRefs, currentOption)
    setMessage('', 'info')
    emitAvailability({
      visible: true,
      enabled,
      pending: false,
      unavailableText: currentRefs.body.textContent ?? undefined,
      unavailableReason: null
    })
  }

  let prepared: GuestShiftExtensionRequest | undefined

  const isCurrent = (scope: ExtensionScope, orderId: number) => {
    const base = resolveBaseScope()
    return !disposed && base?.userId === scope.userId && base.tableSessionId === scope.tableSessionId &&
      base.tableToken === scope.tableToken && base.expectedVisitGeneration === scope.expectedVisitGeneration &&
      currentScope?.tabId === scope.tabId && currentOption?.orderId === orderId
  }

  const renderRetry = () => {
    const currentRefs = ensureRefs()
    currentRefs.body.textContent = 'Результат запроса на продление пока не подтверждён.'
    const orderId = currentOption?.orderId
    displayedAction = prepared && currentScope && orderId
      ? { scope: currentScope, orderId, retryKey: prepared.idempotencyKey ?? null }
      : null
    currentRefs.meta.textContent = prepared?.confirmedTerms
      ? optionCopy({ available: true, ...prepared.confirmedTerms })
      : ''
    currentRefs.button.textContent = isSubmitting ? 'Проверяем…' : 'Повторить исходный запрос'
    currentRefs.button.disabled = isSubmitting || displayedAction === null
    emitAvailability({
      visible: true,
      enabled: !currentRefs.button.disabled,
      pending: false,
      copy: currentRefs.body.textContent
    })
  }

  const evaluateLastRequest = async (scope: ExtensionScope, orderId: number, last: LastExtensionRequest) => {
    const activeOrder = await guestGetActiveOrder(backendUrl, scope.tableToken, scope.tableSessionId, scope.tabId, deps)
    if (!isCurrent(scope, orderId)) return true
    if (activeOrder.ok && activeOrder.data.order?.orderId === orderId) {
      if (hasConfirmedCharge(activeOrder.data.order, last.requestId)) renderConfirmed()
      else renderMissingAfterPending()
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
    const selectedTab = chooseGuestTab(tabsResult.data.tabs, baseScope.tableSessionId)
    if (!selectedTab) {
      hideCard('ACTIVE_TAB_NOT_FOUND')
      return null
    }
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
      prepared = undefined
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
    const orderId = optionResult.data.orderId
    const option = optionResult.data
    prepared = undefined
    if (option.unavailableReason === 'STAFF_MANAGED_SEATING') {
      hideCard(option.unavailableReason)
      return
    }
    if (orderId) {
      const pending = option.pendingRequest?.orderId === orderId ? option.pendingRequest : null
      const saved = await extensionRecord(scope, orderId, record => {
        if (pending) record.last = { requestId: pending.id, requestedUntil: pending.requestedUntil }
        return record
      }).catch(() => null)
      if (!isCurrent(scope, orderId) || loadSeq !== seq || controller.signal.aborted) return
      if (saved?.prepared) {
        prepared = saved.prepared
        renderRetry()
        return
      }
      if (pending) {
        renderPending(pending)
        return
      }
      if (saved?.last && await evaluateLastRequest(scope, orderId, saved.last)) return
      if (!isCurrent(scope, orderId) || loadSeq !== seq) return
    }
    if (!optionResult.data.available || optionResult.data.priceMinor == null) {
      hideCard(optionResult.data.unavailableReason ?? null)
      return
    }
    renderAvailable(optionResult.data)
  }

  const submitRequest = async () => {
    const selection = displayedAction
    if (isSubmitting || !selection) return
    const { scope, orderId, terms, retryKey } = selection
    if (!isCurrent(scope, orderId)) return
    isSubmitting = true
    const currentRefs = ensureRefs()
    currentRefs.button.disabled = true
    currentRefs.button.textContent = retryKey === undefined ? 'Отправляем…' : 'Проверяем…'
    actionAbort?.abort()
    const controller = new AbortController()
    actionAbort = controller
    let request: GuestShiftExtensionRequest | undefined
    try {
      request = await extensionRecord(scope, orderId, record => {
        const existing = record.prepared
        if (retryKey !== undefined) return (existing?.idempotencyKey ?? null) === retryKey ? existing : undefined
        if (!terms) return undefined
        if (existing) {
          const saved = existing.confirmedTerms
          return saved?.revision === terms.revision && saved.durationMinutes === terms.durationMinutes &&
            saved.priceMinor === terms.priceMinor && saved.currency === terms.currency ? existing : undefined
        }
        return record.prepared = {
          tableToken: scope.tableToken,
          tableSessionId: scope.tableSessionId,
          tabId: scope.tabId,
          expectedVisitGeneration: scope.expectedVisitGeneration,
          expectedOrderId: orderId,
          confirmedTerms: terms,
          idempotencyKey: buildIdempotencyKey(scope),
          comment: null
        }
      })
    } catch {
      isSubmitting = false
      if (isCurrent(scope, orderId)) {
        currentRefs.button.disabled = false
        currentRefs.button.textContent = retryKey === undefined
          ? `Продлить на ${formatDuration(terms?.durationMinutes)}`
          : 'Повторить исходный запрос'
        setMessage('Не удалось сохранить запрос на этом устройстве. Повторите загрузку.', 'error')
      }
      return
    }
    if (!request || !isCurrent(scope, orderId) || controller.signal.aborted) {
      isSubmitting = false
      if (!request && isCurrent(scope, orderId)) await load()
      return
    }
    prepared = request
    const result = await guestCreateShiftExtensionRequest(backendUrl, request, deps, controller.signal)
    if (actionAbort === controller) {
      actionAbort = null
      isSubmitting = false
    }
    if (!result.ok) {
      if (!isCurrent(scope, orderId) || controller.signal.aborted) return
      if (isKnownAuthError(result.error)) clearSession()
      const code = normalizeErrorCode(result.error)
      if (code === ApiErrorCodes.SHIFT_EXTENSION_TERMS_CHANGED ||
          (!request.confirmedTerms && code === ApiErrorCodes.VISIT_VERSION_REQUIRED)) {
        try {
          await extensionRecord(scope, orderId, record => {
            if (record.prepared?.idempotencyKey === request.idempotencyKey) delete record.prepared
          })
        } catch {
          renderRetry()
          setMessage('Не удалось сохранить результат. Повторите исходный запрос.', 'error')
          return
        }
        if (!isCurrent(scope, orderId)) return
        prepared = undefined
        await load()
        if (isCurrent(scope, orderId)) setMessage('Условия изменились. Проверьте их и подтвердите заново.', 'error')
        return
      }
      renderRetry()
      setMessage(result.error.message || 'Не удалось подтвердить результат. Повторите исходный запрос.', 'error')
      return
    }
    const response = result.data.request
    if (response.orderId !== orderId || response.tableSessionId !== scope.tableSessionId ||
        response.tabId !== scope.tabId) {
      if (isCurrent(scope, orderId)) renderRetry()
      return
    }
    try {
      await extensionRecord(scope, orderId, record => {
        if (record.prepared?.idempotencyKey !== request.idempotencyKey) return
        delete record.prepared
        record.last = { requestId: response.id, requestedUntil: response.requestedUntil }
      })
    } catch {
      if (isCurrent(scope, orderId)) renderRetry()
      return
    }
    if (!isCurrent(scope, orderId) || controller.signal.aborted) return
    prepared = undefined
    if (response.status === 'pending') {
      renderPending(response)
      showToast('Запрос на продление отправлен')
      window.dispatchEvent(new CustomEvent('hookah:shift-extension-requested', { detail: { requestId: response.id } }))
    } else {
      await load()
    }
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
