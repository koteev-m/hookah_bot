import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueCloseOrder,
  venueExcludeOrderItem,
  venueGetOrderDetail,
  venueRejectOrder,
  venueRestoreOrderItem,
  venueSetOrderItemDiscount,
  venueUpdateOrderStatus
} from '../shared/api/venueApi'
import type { OrderBatchItemDto, OrderBillDiscountDto, OrderDetailDto, VenueAccessDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { formatPrice } from '../shared/ui/price'
import { showToast } from '../shared/ui/toast'

const REJECT_REASONS = [
  { value: 'NO_STOCK', label: 'Нет в наличии' },
  { value: 'CUSTOMER_REQUEST', label: 'Запрос клиента' },
  { value: 'CLOSED', label: 'Заказ закрыт' },
  { value: 'OTHER', label: 'Другое' }
]

const STATUS_LABELS: Record<OrderDetailDto['status'], string> = {
  new: 'новый',
  accepted: 'принят',
  cooking: 'принят',
  delivering: 'принят',
  delivered: 'доставлен',
  closed: 'закрыт'
}

const STATUS_ACTION_LABELS: Partial<Record<OrderDetailDto['status'], string>> = {
  accepted: 'Принять заказ',
  delivered: 'Доставлено'
}

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
  refreshButton: HTMLButtonElement
  updatedAt: HTMLParagraphElement
  bill: HTMLDivElement
  batches: HTMLDivElement
  managementNotice: HTMLDivElement
  actionsCard: HTMLDivElement
  actions: HTMLDivElement
  rejectCard: HTMLDivElement
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
  const refreshPanel = el('div', { className: 'button-row order-refresh-panel' })
  const refreshButton = el('button', { className: 'button-small', text: '🔄 Обновить' }) as HTMLButtonElement
  const updatedAt = el('p', { className: 'order-updated-at', text: '' })
  updatedAt.hidden = true
  append(refreshPanel, refreshButton, updatedAt)
  append(header, title, meta, status, refreshPanel)

  const bill = el('div', { className: 'card venue-order-bill' })

  const managementNotice = el('div', { className: 'card' })
  append(
    managementNotice,
    el('h3', { text: 'Управление счётом' }),
    el('p', {
      className: 'venue-order-sub',
      text: 'Загружаем доступные действия по счёту.'
    })
  )

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
  const rejectButton = el('button', { text: 'Отклонить заказ' }) as HTMLButtonElement
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

  append(wrapper, header, batches, bill, managementNotice, actions, rejectCard, backButton, error)
  root.replaceChildren(wrapper)

  return {
    title,
    meta,
    status,
    refreshButton,
    updatedAt,
    bill,
    batches,
    managementNotice,
    actionsCard: actions,
    actions: buttons,
    rejectCard,
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

function formatMoney(amountMinor: number, currency: string) {
  return formatPrice(amountMinor, currency || 'RUB')
}

function formatDiscount(amountMinor: number, currency: string) {
  return `−${formatMoney(amountMinor, currency)}`
}

function appendBillRow(container: HTMLElement, label: string, value: string, isTotal = false) {
  const row = el('div', { className: isTotal ? 'order-bill-row order-bill-total' : 'order-bill-row' })
  append(row, el('span', { text: label }), el('strong', { text: value }))
  container.appendChild(row)
}

function formatRefreshTime(date: Date): string {
  return date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function renderDiscountRows(container: HTMLElement, title: string, discounts: OrderBillDiscountDto[]) {
  if (!discounts.length) return
  const block = el('div', { className: 'order-discount-list' })
  block.appendChild(el('p', { className: 'order-batch-comment', text: title }))
  discounts.forEach((discount) => {
    appendBillRow(block, discount.label || 'Акция', formatDiscount(discount.discountMinor, discount.currency))
  })
  container.appendChild(block)
}

function renderBill(container: HTMLElement, order: OrderDetailDto) {
  const bill = order.bill
  container.replaceChildren()
  container.appendChild(el('h3', { text: 'Счёт' }))
  appendBillRow(container, 'Сумма до скидок', formatMoney(bill.grossTotalMinor, bill.currency))
  appendBillRow(container, 'Ручные скидки', formatDiscount(bill.manualDiscountTotalMinor, bill.currency))
  renderDiscountRows(container, 'Акции', bill.promoDiscounts)
  if (!bill.promoDiscounts.length) {
    appendBillRow(container, 'Акции', formatDiscount(bill.promoDiscountTotalMinor, bill.currency))
  }
  renderDiscountRows(container, 'Лояльность', bill.loyaltyDiscounts)
  if (!bill.loyaltyDiscounts.length && bill.loyaltyDiscountTotalMinor > 0) {
    appendBillRow(container, 'Лояльность', formatDiscount(bill.loyaltyDiscountTotalMinor, bill.currency))
  }
  if (bill.excludedTotalMinor > 0) {
    appendBillRow(container, 'Исключено из счёта', formatMoney(bill.excludedTotalMinor, bill.currency))
  }
  if (bill.canceledTotalMinor > 0) {
    appendBillRow(container, 'Отменено', formatMoney(bill.canceledTotalMinor, bill.currency))
  }
  if (bill.rejectedTotalMinor > 0) {
    appendBillRow(container, 'Отклонённые заявки', formatMoney(bill.rejectedTotalMinor, bill.currency))
  }
  appendBillRow(container, 'К оплате', formatMoney(bill.finalPayableTotalMinor, bill.currency), true)

  if (bill.excludedItems.length) {
    const excluded = el('div', { className: 'order-excluded-list' })
    excluded.appendChild(el('p', { className: 'order-batch-comment', text: 'Не входят в оплату' }))
    bill.excludedItems.forEach((item) => {
      const reason = item.reason ? ` · ${item.reason}` : ''
      excluded.appendChild(
        el('p', {
          className: 'venue-order-sub',
          text: `${item.batchLabel}: ${item.name} ×${item.qty} — ${formatMoney(item.lineGrossMinor, item.currency)} · ${nonPayableStatusLabel(item.status)}${reason}`
        })
      )
    })
    container.appendChild(excluded)
  }
}

function renderItemPriceMeta(item: OrderBatchItemDto, discountLabel: string) {
  const parts: string[] = []
  if (item.lineGrossMinor > 0 && item.currency) {
    parts.push(`сумма ${formatMoney(item.lineGrossMinor, item.currency)}`)
  }
  if (item.manualDiscountMinor > 0 && item.currency) {
    parts.push(`ручная скидка ${formatDiscount(item.manualDiscountMinor, item.currency)}`)
  }
  if (item.promoDiscountMinor > 0 && item.currency) {
    parts.push(`${discountLabel} ${formatDiscount(item.promoDiscountMinor, item.currency)}`)
  }
  if (item.linePayableMinor > 0 && item.currency) {
    parts.push(`к оплате ${formatMoney(item.linePayableMinor, item.currency)}`)
  } else if (item.currency && (item.lineGrossMinor > 0 || item.promoDiscountMinor > 0 || item.manualDiscountMinor > 0)) {
    parts.push(`к оплате ${formatMoney(0, item.currency)}`)
  }
  return parts.join(' · ')
}

function renderItemStatusMeta(item: OrderBatchItemDto) {
  const parts: string[] = []
  if (item.itemStatus === 'canceled') {
    parts.push(`Отменено${item.canceledReasonText ? `: ${item.canceledReasonText}` : ''}`)
  }
  if (item.isExcluded) {
    parts.push(`Исключено${item.excludedReasonText ? `: ${item.excludedReasonText}` : ''}`)
  }
  if (item.discountPercent) {
    parts.push(`Ручная скидка ${item.discountPercent}%`)
  }
  return parts.join(' · ')
}

function nonPayableStatusLabel(status: string) {
  if (status === 'excluded') return 'исключено'
  if (status === 'canceled') return 'отменено'
  if (status === 'rejected_batch') return 'отклонённая заявка'
  return status
}

function orderStatusLabel(status: OrderDetailDto['status']) {
  return STATUS_LABELS[status] ?? status
}

function directBotParityTransitions(status: OrderDetailDto['status']): OrderDetailDto['status'][] {
  switch (status) {
    case 'new':
      return ['accepted']
    case 'accepted':
    case 'cooking':
    case 'delivering':
      return ['delivered']
    case 'delivered':
    default:
      return []
  }
}

function canCloseBillFromMiniApp(order: OrderDetailDto, access: VenueAccessDto) {
  return access.permissions.includes('ORDER_STATUS_UPDATE') && order.status === 'delivered'
}

type BillItemActionHandlers = {
  onExclude: (item: OrderBatchItemDto) => void
  onRestore: (item: OrderBatchItemDto) => void
  onDiscount: (item: OrderBatchItemDto) => void
}

function renderBillItemControls(
  container: HTMLElement,
  item: OrderBatchItemDto,
  order: OrderDetailDto,
  handlers: BillItemActionHandlers
) {
  if (order.status === 'closed' || item.itemStatus !== 'active') {
    return
  }
  const controls = el('div', { className: 'order-actions bill-item-actions' })
  if (item.isExcluded) {
    const restore = el('button', { className: 'button-small button-secondary', text: 'Вернуть в счёт' }) as HTMLButtonElement
    restore.addEventListener('click', () => handlers.onRestore(item))
    controls.appendChild(restore)
  } else {
    const exclude = el('button', { className: 'button-small button-secondary', text: 'Исключить' }) as HTMLButtonElement
    const discount = el('button', {
      className: 'button-small button-secondary',
      text: item.discountPercent ? `Скидка ${item.discountPercent}%` : 'Скидка'
    }) as HTMLButtonElement
    exclude.addEventListener('click', () => handlers.onExclude(item))
    discount.addEventListener('click', () => handlers.onDiscount(item))
    append(controls, exclude, discount)
  }
  container.appendChild(controls)
}

function renderBatches(
  container: HTMLElement,
  order: OrderDetailDto,
  canEditBill: boolean,
  handlers: BillItemActionHandlers
) {
  container.replaceChildren()
  if (!order.batches.length) {
    container.appendChild(el('p', { className: 'venue-empty', text: 'Заявок нет.' }))
    return
  }
  const discountLabel =
    order.bill.promoDiscountTotalMinor > 0 && order.bill.loyaltyDiscountTotalMinor > 0
      ? 'Акции и лояльность'
      : order.bill.loyaltyDiscountTotalMinor > 0
        ? 'Лояльность'
        : 'Акции'
  order.batches.forEach((batch, index) => {
    const card = el('div', { className: 'order-batch' })
    const header = el('div', { className: 'order-batch-header' })
    append(
      header,
      el('strong', { text: index === 0 ? 'Основная заявка' : `Дозаказ ${index}` }),
      el('span', { text: orderStatusLabel(batch.status) })
    )
    const comment = el('p', { className: 'order-batch-comment', text: batch.comment ?? 'Комментарий: —' })
    const list = el('div', { className: 'order-items' })
    batch.items.forEach((item) => {
      const row = el('div', { className: 'order-item' })
      const info = el('div', { className: 'order-item-main' })
      info.appendChild(el('span', { className: 'order-item-name', text: item.name }))
      const statusMeta = renderItemStatusMeta(item)
      const priceMeta = renderItemPriceMeta(item, discountLabel)
      if (statusMeta) {
        info.appendChild(el('span', { className: 'order-item-details', text: statusMeta }))
      }
      if (priceMeta) {
        info.appendChild(el('span', { className: 'order-item-details', text: priceMeta }))
      }
      append(row, info, el('span', { className: 'order-item-qty', text: `×${item.qty}` }))
      if (canEditBill) {
        renderBillItemControls(row, item, order, handlers)
      }
      list.appendChild(row)
    })
    append(card, header, comment, list)
    if (batch.rejectedReasonCode) {
      const rejectMeta = el('p', { className: 'venue-order-sub', text: `Отказ: ${batch.rejectedReasonCode}${batch.rejectedReasonText ? ` · ${batch.rejectedReasonText}` : ''}` })
      card.appendChild(rejectMeta)
    }
    if (batch.promotionDiscounts.length) {
      const promoList = el('div', { className: 'order-discount-list' })
      promoList.appendChild(el('p', { className: 'order-batch-comment', text: 'Скидки по заявке' }))
      batch.promotionDiscounts.forEach((discount) => {
        appendBillRow(promoList, discount.label || 'Акция', formatDiscount(discount.discountMinor, discount.currency))
      })
      card.appendChild(promoList)
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
  let isLoading = false
  let isUpdating = false
  let lastSuccessfulRefreshAt: Date | null = null

  const canUpdate = access.permissions.includes('ORDER_STATUS_UPDATE')
  const canReject = access.role !== 'STAFF'
  const canEditBill = access.role !== 'STAFF'

  const updateRefreshState = () => {
    refs.refreshButton.disabled = isLoading || !orderId
    refs.refreshButton.textContent = isLoading ? 'Обновляем…' : '🔄 Обновить'
    refs.updatedAt.textContent = lastSuccessfulRefreshAt ? `Обновлено ${formatRefreshTime(lastSuccessfulRefreshAt)}` : ''
    refs.updatedAt.hidden = !lastSuccessfulRefreshAt
  }

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
    const nextStatuses = directBotParityTransitions(order.status)
    const canCloseBill = canCloseBillFromMiniApp(order, access)
    refs.actionsCard.hidden = !nextStatuses.length && !canCloseBill
    if (!nextStatuses.length && !canCloseBill) {
      return
    }
    nextStatuses.forEach((status) => {
      const button = el('button', { className: 'button-small', text: STATUS_ACTION_LABELS[status] ?? orderStatusLabel(status) }) as HTMLButtonElement
      button.disabled = !canUpdate || isUpdating
      button.title = canUpdate ? '' : 'Недостаточно прав'
      button.addEventListener('click', () => void updateStatus(status))
      refs.actions.appendChild(button)
    })
    if (canCloseBill) {
      const closeButton = el('button', { className: 'button-small', text: 'Закрыть счёт' }) as HTMLButtonElement
      closeButton.disabled = isUpdating
      closeButton.addEventListener('click', () => void closeBill())
      refs.actions.appendChild(closeButton)
    }
  }

  const renderManagementNotice = () => {
    refs.managementNotice.replaceChildren()
    refs.managementNotice.appendChild(el('h3', { text: 'Управление счётом' }))
    refs.managementNotice.appendChild(
      el('p', {
        className: 'venue-order-sub',
        text: canEditBill
          ? 'Скидки и исключение позиций доступны у строк состава заказа. Итог обновится после сохранения.'
          : 'Счёт доступен только для просмотра. Ручные скидки и исключение позиций доступны менеджеру или владельцу.'
      })
    )
  }

  const applyBillMutationResult = (message: string) => {
    showToast(message)
    void load()
  }

  const excludeItem = async (item: OrderBatchItemDto) => {
    if (!orderId || isUpdating || !canEditBill) return
    const reasonText = window.prompt('Причина исключения из счёта', item.excludedReasonText ?? '')?.trim()
    if (!reasonText) return
    isUpdating = true
    const result = await venueExcludeOrderItem(
      backendUrl,
      { venueId, orderId, batchItemId: item.batchItemId, body: { reasonText } },
      deps
    )
    if (disposed) return
    isUpdating = false
    if (!result.ok) {
      showError(result.error)
      return
    }
    applyBillMutationResult('Позиция исключена из счёта')
  }

  const restoreItem = async (item: OrderBatchItemDto) => {
    if (!orderId || isUpdating || !canEditBill) return
    isUpdating = true
    const result = await venueRestoreOrderItem(
      backendUrl,
      { venueId, orderId, batchItemId: item.batchItemId },
      deps
    )
    if (disposed) return
    isUpdating = false
    if (!result.ok) {
      showError(result.error)
      return
    }
    applyBillMutationResult('Позиция возвращена в счёт')
  }

  const setItemDiscount = async (item: OrderBatchItemDto) => {
    if (!orderId || isUpdating || !canEditBill) return
    const current = item.discountPercent ?? 0
    const raw = window.prompt('Скидка на позицию, % (0-100). 0 убирает скидку.', String(current))?.trim()
    if (raw === undefined) return
    const discountPercent = Number(raw)
    if (!Number.isInteger(discountPercent) || discountPercent < 0 || discountPercent > 100) {
      showToast('Введите целое число от 0 до 100')
      return
    }
    isUpdating = true
    const result = await venueSetOrderItemDiscount(
      backendUrl,
      { venueId, orderId, batchItemId: item.batchItemId, body: { discountPercent } },
      deps
    )
    if (disposed) return
    isUpdating = false
    if (!result.ok) {
      showError(result.error)
      return
    }
    applyBillMutationResult(discountPercent === 0 ? 'Скидка убрана' : 'Скидка сохранена')
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

  const closeBill = async () => {
    if (!orderId || isUpdating || !access.permissions.includes('ORDER_STATUS_UPDATE')) return
    const confirmed = window.confirm('Закрыть счёт? После закрытия заказ уйдёт из активных.')
    if (!confirmed) return
    isUpdating = true
    refs.rejectButton.disabled = true
    const result = await venueCloseOrder(backendUrl, { venueId, orderId }, deps)
    if (disposed) return
    isUpdating = false
    refs.rejectButton.disabled = !canReject
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Счёт закрыт')
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
      updateRefreshState()
      return
    }
    hideError()
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    isLoading = true
    updateRefreshState()
    const result = await venueGetOrderDetail(backendUrl, { venueId, orderId }, deps, controller.signal)
    if (disposed || loadSeq !== seq) return
    loadAbort = null
    isLoading = false
    updateRefreshState()
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    lastSuccessfulRefreshAt = new Date()
    updateRefreshState()
    const order = result.data.order
    refs.title.textContent = order.displayNumber ? `Заказ №${order.displayNumber}` : `Заказ №${order.orderId}`
    refs.meta.textContent = `${order.tableLabel || order.tableNumber} · Создан: ${new Date(order.createdAt).toLocaleString()}`
    refs.status.textContent = `Статус: ${orderStatusLabel(order.status)}`
    renderActions(order)
    renderBill(refs.bill, order)
    renderManagementNotice()
    renderBatches(
      refs.batches,
      order,
      canEditBill,
      {
        onExclude: excludeItem,
        onRestore: restoreItem,
        onDiscount: setItemDiscount
      }
    )
    refs.rejectCard.hidden = !canReject || order.status === 'closed'
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.refreshButton, 'click', () => void load()))
  disposables.push(on(refs.rejectButton, 'click', () => void rejectOrder()))
  disposables.push(on(refs.backButton, 'click', onBack))

  refs.rejectCard.hidden = !canReject
  refs.rejectButton.disabled = !canReject
  refs.rejectButton.title = canReject ? '' : 'Недостаточно прав'
  updateRefreshState()

  void load()

  return () => {
    disposed = true
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
