import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueApproveShiftExtensionRequest,
  venueGetShiftExtensionRequests,
  venueRejectShiftExtensionRequest
} from '../shared/api/venueApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { ShiftExtensionRequestDto, VenueAccessDto } from '../shared/api/venueDtos'
import { append, el, on } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'
import { showToast } from '../shared/ui/toast'

const POLL_INTERVAL_MS = 12000

type VenueShiftExtensionsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
  onOpenOrder: (orderId: number) => void
}

type ShiftExtensionRefs = {
  status: HTMLParagraphElement
  refreshButton: HTMLButtonElement
  list: HTMLDivElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function buildDom(root: HTMLDivElement): ShiftExtensionRefs {
  const wrapper = el('div', { className: 'venue-shift-extensions-screen' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: 'Продления' })
  const intro = el('p', {
    className: 'venue-order-sub',
    text: 'Запросы гостей на платное продление текущего счёта.'
  })
  const status = el('p', { className: 'status', text: '' })
  const refreshButton = el('button', { className: 'button-secondary', text: '🔄 Обновить' }) as HTMLButtonElement
  append(header, title, intro, status, refreshButton)
  const list = el('div', { className: 'venue-extension-list' })
  append(wrapper, header, list)
  root.replaceChildren(wrapper)
  return { status, refreshButton, list }
}

function formatDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function formatTime(value: string): string {
  const match = value.match(/T(\d{2}:\d{2})/)
  if (match?.[1]) {
    return match[1]
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
}

function statusLabel(status: string): string {
  switch (status.toLowerCase()) {
    case 'pending':
      return 'ожидает подтверждения'
    case 'approved':
      return 'подтверждено'
    case 'rejected':
      return 'отказано'
    case 'cancelled':
    case 'canceled':
      return 'отменено'
    default:
      return status
  }
}

function tableLabel(request: ShiftExtensionRequestDto): string {
  return request.tableNumber ? `Стол №${request.tableNumber}` : `Стол #${request.tableId}`
}

function renderApiError(status: HTMLParagraphElement, error: ApiErrorInfo, isDebug: boolean) {
  const code = normalizeErrorCode(error)
  if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
    clearSession()
  }
  status.textContent = isDebug ? `${error.message ?? 'Ошибка'} (${error.code ?? error.status})` : error.message || 'Не удалось выполнить действие.'
}

function renderRequests(
  list: HTMLDivElement,
  requests: ShiftExtensionRequestDto[],
  canConfirm: boolean,
  disabledRequestIds: Set<number>,
  onApprove: (request: ShiftExtensionRequestDto) => void,
  onReject: (request: ShiftExtensionRequestDto) => void,
  onOpenOrder: (orderId: number) => void
) {
  list.replaceChildren()
  if (!requests.length) {
    const empty = el('section', { className: 'card' })
    empty.appendChild(el('p', { className: 'venue-empty', text: 'Запросов на продление нет.' }))
    list.appendChild(empty)
    return
  }

  requests.forEach((request) => {
    const row = el('section', { className: 'card venue-extension-card' })
    row.appendChild(el('h3', { text: 'Запрос на продление' }))
    row.appendChild(
      el('p', {
        className: 'venue-order-sub',
        text: `${tableLabel(request)} · Заказ #${request.orderId} · ${statusLabel(request.status)}`
      })
    )
    row.appendChild(
      el('p', {
        className: 'venue-order-sub',
        text: `Продление на ${request.durationMinutes} мин · ${formatPrice(request.priceMinor, request.currency)}`
      })
    )
    row.appendChild(
      el('p', {
        className: 'venue-order-sub',
        text: `Было до ${formatTime(request.currentOrderableUntil)} · будет до ${formatTime(request.requestedUntil)}`
      })
    )
    row.appendChild(el('p', { className: 'venue-order-sub', text: `Создан: ${formatDateTime(request.createdAt)}` }))
    if (request.comment) {
      row.appendChild(el('p', { className: 'venue-order-sub', text: `Комментарий: ${request.comment}` }))
    }

    const actions = el('div', { className: 'order-actions' })
    const isDisabled = disabledRequestIds.has(request.id)
    if (canConfirm) {
      const approveButton = el('button', { className: 'button-small', text: '✅ Продлить на 1 час' }) as HTMLButtonElement
      approveButton.disabled = isDisabled
      approveButton.addEventListener('click', () => onApprove(request))
      const rejectButton = el('button', { className: 'button-small button-secondary', text: '❌ Отказать' }) as HTMLButtonElement
      rejectButton.disabled = isDisabled
      rejectButton.addEventListener('click', () => onReject(request))
      append(actions, approveButton, rejectButton)
    }
    const openOrderButton = el('button', { className: 'button-small button-secondary', text: 'Открыть заказ' }) as HTMLButtonElement
    openOrderButton.addEventListener('click', () => onOpenOrder(request.orderId))
    actions.appendChild(openOrderButton)
    row.appendChild(actions)
    list.appendChild(row)
  })
}

export function renderVenueShiftExtensionsScreen(options: VenueShiftExtensionsOptions) {
  const { root, backendUrl, isDebug, venueId, access, onOpenOrder } = options
  if (!root) return () => undefined

  const refs = buildDom(root)
  const deps = buildApiDeps(isDebug)
  const canView = access.permissions.includes('SHIFT_EXTENSION_VIEW')
  const canConfirm = access.permissions.includes('SHIFT_EXTENSION_CONFIRM')
  const disabledRequestIds = new Set<number>()
  const disposables: Array<() => void> = []

  let disposed = false
  let isLoading = false
  let abortController: AbortController | null = null
  let pollTimer: number | null = null
  let loadSeq = 0
  let currentRequests: ShiftExtensionRequestDto[] = []

  const setLoading = (loading: boolean) => {
    isLoading = loading
    refs.refreshButton.disabled = loading
    refs.refreshButton.textContent = loading ? 'Обновляем…' : '🔄 Обновить'
  }

  const loadRequests = async () => {
    if (!canView) {
      refs.status.textContent = 'Недостаточно прав для просмотра запросов.'
      currentRequests = []
      renderRequests(refs.list, currentRequests, false, disabledRequestIds, approveRequest, rejectRequest, onOpenOrder)
      return
    }
    if (isLoading) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    const seq = ++loadSeq
    setLoading(true)
    const result = await venueGetShiftExtensionRequests(backendUrl, { venueId, status: 'pending' }, deps, controller.signal)
    if (disposed || abortController !== controller || loadSeq !== seq) return
    abortController = null
    setLoading(false)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) {
        return
      }
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    refs.status.textContent = `Обновлено: ${new Date().toLocaleTimeString('ru-RU')}`
    currentRequests = result.data.items
    renderRequests(refs.list, currentRequests, canConfirm, disabledRequestIds, approveRequest, rejectRequest, onOpenOrder)
  }

  const markRequestBusy = (requestId: number, busy: boolean) => {
    if (busy) {
      disabledRequestIds.add(requestId)
    } else {
      disabledRequestIds.delete(requestId)
    }
    renderRequests(refs.list, currentRequests, canConfirm, disabledRequestIds, approveRequest, rejectRequest, onOpenOrder)
  }

  async function approveRequest(request: ShiftExtensionRequestDto) {
    if (!canConfirm || disabledRequestIds.has(request.id)) return
    markRequestBusy(request.id, true)
    const result = await venueApproveShiftExtensionRequest(backendUrl, { venueId, requestId: request.id }, deps)
    if (disposed) return
    markRequestBusy(request.id, false)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      await loadRequests()
      return
    }
    showToast('Продление подтверждено')
    window.dispatchEvent(new CustomEvent('hookah:shift-extension-approved', { detail: { requestId: request.id, orderId: request.orderId } }))
    await loadRequests()
  }

  async function rejectRequest(request: ShiftExtensionRequestDto) {
    if (!canConfirm || disabledRequestIds.has(request.id)) return
    const reasonInput = window.prompt('Причина отказа для гостя. Можно оставить пустым.', '')
    if (reasonInput === null) return
    const reasonText = reasonInput.trim() || 'Причина не указана'
    markRequestBusy(request.id, true)
    const result = await venueRejectShiftExtensionRequest(
      backendUrl,
      { venueId, requestId: request.id, body: { reasonText } },
      deps
    )
    if (disposed) return
    markRequestBusy(request.id, false)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      await loadRequests()
      return
    }
    showToast('В продлении отказано')
    await loadRequests()
  }

  const startPolling = () => {
    if (pollTimer) {
      window.clearInterval(pollTimer)
    }
    pollTimer = window.setInterval(() => {
      void loadRequests()
    }, POLL_INTERVAL_MS)
  }

  disposables.push(on(refs.refreshButton, 'click', () => void loadRequests()))
  void loadRequests()
  startPolling()

  return () => {
    disposed = true
    abortController?.abort()
    if (pollTimer) {
      window.clearInterval(pollTimer)
    }
    disposables.forEach((dispose) => dispose())
  }
}
