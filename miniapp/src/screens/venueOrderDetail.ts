import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueGetOrderDetail, venueRejectOrder, venueUpdateOrderStatus } from '../shared/api/venueApi'
import type { OrderDetailDto, VenueAccessDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

const STATUS_FLOW: Record<OrderDetailDto['status'], OrderDetailDto['status'][]> = {
  new: ['accepted'],
  accepted: ['cooking'],
  cooking: ['delivering'],
  delivering: ['delivered'],
  delivered: ['closed'],
  closed: []
}

const REJECT_REASONS = [
  { value: 'NO_STOCK', label: 'Нет в наличии' },
  { value: 'CUSTOMER_REQUEST', label: 'Запрос клиента' },
  { value: 'CLOSED', label: 'Заказ закрыт' },
  { value: 'OTHER', label: 'Другое' }
]

type OrderDetailOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  orderId: number | null
  access: VenueAccessDto
  onBack: () => void
}

type OrderDetailRefs = {
  title: HTMLHeadingElement
  meta: HTMLParagraphElement
  status: HTMLParagraphElement
  batches: HTMLDivElement
  actions: HTMLDivElement
  rejectReason: HTMLSelectElement
  rejectText: HTMLInputElement
  rejectButton: HTMLButtonElement
  backButton: HTMLButtonElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function renderErrorActions(container: HTMLElement, actions: ApiErrorAction[]) {
  container.replaceChildren()
  actions.forEach((action) => {
    const button = document.createElement('button')
    button.textContent = action.label
    if (action.kind === 'secondary') {
      button.classList.add('button-secondary')
    }
    button.addEventListener('click', action.onClick)
    container.appendChild(button)
  })
}

function buildOrderDetailDom(root: HTMLDivElement): OrderDetailRefs {
  const wrapper = el('div', { className: 'venue-order-detail' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Заказ' })
  const meta = el('p', { className: 'venue-order-sub', text: '' })
  const status = el('p', { className: 'venue-order-sub', text: '' })
  append(header, title, meta, status)

  const actions = el('div', { className: 'card venue-order-actions-card' })
  const actionsTitle = el('h3', { text: 'Смена статуса' })
  const buttons = el('div', { className: 'order-actions' })
  append(actions, actionsTitle, buttons)

  const rejectCard = el('div', { className: 'card' })
  const rejectTitle = el('h3', { text: 'Отклонить заказ' })
  const rejectReasonLabel = el('p', { className: 'field-label', text: 'Причина' })
  const rejectReason = document.createElement('select')
  rejectReason.className = 'venue-select'
  REJECT_REASONS.forEach((reason) => rejectReason.appendChild(new Option(reason.label, reason.value)))
  const rejectTextLabel = el('p', { className: 'field-label', text: 'Комментарий (необязательно)' })
  const rejectText = document.createElement('input')
  rejectText.type = 'text'
  rejectText.className = 'venue-input'
  rejectText.placeholder = 'Комментарий'
  const rejectButton = el('button', { text: 'Отклонить' }) as HTMLButtonElement
  append(rejectCard, rejectTitle, rejectReasonLabel, rejectReason, rejectTextLabel, rejectText, rejectButton)

  const backButton = el('button', { className: 'button-secondary', text: 'Назад к очереди' }) as HTMLButtonElement
  const batches = el('div', { className: 'order-batches' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, header, actions, rejectCard, backButton, error, batches)
  root.replaceChildren(wrapper)

  return {
    title,
    meta,
    status,
    batches,
    actions: buttons,
    rejectReason,
    rejectText,
    rejectButton,
    backButton,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
}

function renderBatches(container: HTMLElement, order: OrderDetailDto) {
  container.replaceChildren()
  if (!order.batches.length) {
    container.appendChild(el('p', { className: 'venue-empty', text: 'Пакеты отсутствуют.' }))
    return
  }
  order.batches.forEach((batch) => {
    const card = el('div', { className: 'order-batch' })
    const header = el('div', { className: 'order-batch-header' })
    append(
      header,
      el('strong', { text: `Batch ${batch.batchId}` }),
      el('span', { text: batch.status })
    )
    const comment = el('p', { className: 'order-batch-comment', text: batch.comment ?? 'Комментарий: —' })
    const list = el('div', { className: 'order-items' })
    batch.items.forEach((item) => {
      const row = el('div', { className: 'order-item' })
      append(row, el('span', { className: 'order-item-name', text: item.name }), el('span', { className: 'order-item-qty', text: `×${item.qty}` }))
      list.appendChild(row)
    })
    append(card, header, comment, list)
    if (batch.rejectedReasonCode) {
      const rejectMeta = el('p', { className: 'venue-order-sub', text: `Отказ: ${batch.rejectedReasonCode}${batch.rejectedReasonText ? ` · ${batch.rejectedReasonText}` : ''}` })
      card.appendChild(rejectMeta)
    }
    container.appendChild(card)
  })
}

export function renderVenueOrderDetailScreen(options: OrderDetailOptions) {
  const { root, backendUrl, isDebug, venueId, orderId, access, onBack } = options
  if (!root) return () => undefined
  const refs = buildOrderDetailDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let loadSeq = 0
  let isUpdating = false

  const canUpdate = access.permissions.includes('ORDER_STATUS_UPDATE')
  const canReject = access.role !== 'STAFF'

  const hideError = () => {
    refs.error.hidden = true
  }

  const showError = (error: ApiErrorInfo) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.error.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    const actions: ApiErrorAction[] = presentation.actions.length
      ? presentation.actions.map((action) =>
          action.label === 'Повторить' ? { ...action, onClick: () => void load() } : action
        )
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void load() }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const renderActions = (order: OrderDetailDto) => {
    refs.actions.replaceChildren()
    const nextStatuses = STATUS_FLOW[order.status] ?? []
    if (!nextStatuses.length) {
      refs.actions.appendChild(el('p', { className: 'venue-empty', text: 'Нет доступных переходов.' }))
      return
    }
    nextStatuses.forEach((status) => {
      const button = el('button', { className: 'button-small', text: `→ ${status}` }) as HTMLButtonElement
      button.disabled = !canUpdate || isUpdating
      button.title = canUpdate ? '' : 'Недостаточно прав'
      button.addEventListener('click', () => void updateStatus(status))
      refs.actions.appendChild(button)
    })
  }

  const updateStatus = async (nextStatus: OrderDetailDto['status']) => {
    if (!orderId || isUpdating) return
    if (!canUpdate) return
    isUpdating = true
    refs.rejectButton.disabled = true
    const result = await venueUpdateOrderStatus(
      backendUrl,
      { venueId, orderId, body: { nextStatus } },
      deps
    )
    if (disposed) return
    isUpdating = false
    refs.rejectButton.disabled = !canReject
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Статус обновлён')
    void load()
  }

  const rejectOrder = async () => {
    if (!orderId || isUpdating) return
    if (!canReject) return
    const reasonCode = refs.rejectReason.value
    if (!reasonCode) {
      showToast('Укажите причину')
      return
    }
    isUpdating = true
    refs.rejectButton.disabled = true
    const result = await venueRejectOrder(
      backendUrl,
      { venueId, orderId, body: { reasonCode, reasonText: refs.rejectText.value.trim() || undefined } },
      deps
    )
    if (disposed) return
    isUpdating = false
    refs.rejectButton.disabled = !canReject
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Заказ отклонён')
    void load()
  }

  const load = async () => {
    if (!orderId) {
      refs.title.textContent = 'Заказ не найден'
      refs.meta.textContent = ''
      refs.status.textContent = ''
      refs.actions.replaceChildren()
      refs.batches.replaceChildren()
      return
    }
    hideError()
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    const result = await venueGetOrderDetail(backendUrl, { venueId, orderId }, deps, controller.signal)
    if (disposed || loadSeq !== seq) return
    loadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    const order = result.data.order
    refs.title.textContent = `Заказ #${order.orderId}`
    refs.meta.textContent = `${order.tableLabel || order.tableNumber} · Создан: ${new Date(order.createdAt).toLocaleString()}`
    refs.status.textContent = `Статус: ${order.status}`
    renderActions(order)
    renderBatches(refs.batches, order)
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.rejectButton, 'click', () => void rejectOrder()))
  disposables.push(on(refs.backButton, 'click', onBack))

  refs.rejectButton.disabled = !canReject
  refs.rejectButton.title = canReject ? '' : 'Недостаточно прав'

  void load()

  return () => {
    disposed = true
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
