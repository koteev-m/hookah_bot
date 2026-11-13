import { getTelegramContext } from '../shared/telegram'
import { requestApi } from '../shared/api/request'
import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueApproveShiftExtensionRequest,
  venueCloseOrder,
  venueExcludeOrderItem,
  venueGetOrderDetail,
  venueRejectOrder,
  venueRejectShiftExtensionRequest,
  venueRestoreOrderItem,
  venueSetOrderItemDiscount,
  venueUpdateOrderStatus
} from '../shared/api/venueApi'
import type {
  OrderBatchItemDto,
  OrderBillDiscountDto,
  OrderDetailDto,
  OrderPendingShiftExtensionDto,
  VenueAccessDto
} from '../shared/api/venueDtos'
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
  shiftExtensionCard: HTMLDivElement
  bill: HTMLDivElement
  batches: HTMLDivElement
  loyalty: HTMLDivElement
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

  const shiftExtensionCard = el('div', { className: 'card venue-order-extension-card' }) as HTMLDivElement
  shiftExtensionCard.hidden = true

  const bill = el('div', { className: 'card venue-order-bill' })

  const loyalty = el('div', { className: 'card' }) as HTMLDivElement
  loyalty.hidden = true
  bill.after(loyalty)
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

  append(wrapper, header, shiftExtensionCard, batches, bill, managementNotice, actions, rejectCard, backButton, error)
  root.replaceChildren(wrapper)

  bill.after(loyalty)
  return {
    loyalty,
    title,
    meta,
    status,
    refreshButton,
    updatedAt,
    shiftExtensionCard,
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

function formatDuration(minutes: number): string {
  if (minutes === 60) return '1 час'
  if (minutes % 60 === 0) return `${minutes / 60} ч`
  return `${minutes} мин`
}

type ShiftExtensionActionHandlers = {
  onApprove: (request: OrderPendingShiftExtensionDto) => void
  onReject: (request: OrderPendingShiftExtensionDto) => void
}

function renderShiftExtensionCard(
  container: HTMLElement,
  request: OrderPendingShiftExtensionDto | null | undefined,
  canConfirm: boolean,
  isUpdating: boolean,
  handlers: ShiftExtensionActionHandlers
) {
  container.replaceChildren()
  if (!request) {
    container.hidden = true
    return
  }
  container.hidden = false
  const title = el('h3', { text: 'Запрос на продление работы заведения' })
  const price = el('p', {
    className: 'venue-order-sub',
    text: `На ${formatDuration(request.durationMinutes)} — ${formatMoney(request.priceMinor, request.currency)}`
  })
  const state = el('p', { className: 'venue-order-sub', text: 'Гость ожидает подтверждения' })
  append(container, title, price, state)
  const actions = el('div', { className: 'order-actions venue-order-extension-actions' })
  const approve = el('button', { className: 'button-small', text: '✅ Подтвердить продление' }) as HTMLButtonElement
  const reject = el('button', { className: 'button-small button-secondary', text: '❌ Отказать' }) as HTMLButtonElement
  approve.disabled = !canConfirm || isUpdating
  reject.disabled = !canConfirm || isUpdating
  approve.title = canConfirm ? '' : 'Недостаточно прав'
  reject.title = canConfirm ? '' : 'Недостаточно прав'
  approve.addEventListener('click', () => handlers.onApprove(request))
  reject.addEventListener('click', () => handlers.onReject(request))
  append(actions, approve, reject)
  container.appendChild(actions)
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
  appendBillRow(container, 'Скидка заведения', formatDiscount(bill.manualDiscountTotalMinor, bill.currency))
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
  const serviceCharges = bill.serviceCharges ?? []
  serviceCharges.forEach((charge) => {
    appendBillRow(container, charge.label, formatMoney(charge.totalMinor, charge.currency))
  })
  appendBillRow(container, 'К оплате', formatMoney(bill.finalPayableTotalMinor, bill.currency), true)
  if (order.status === 'closed') {
    container.appendChild(
      el('p', {
        className: 'venue-order-sub',
        text: 'Счёт закрыт. Состав и итог доступны только для просмотра.'
      })
    )
  }

  if (bill.excludedItems.length) {
    const excluded = el('div', { className: 'order-excluded-list' })
    excluded.appendChild(el('p', { className: 'order-batch-comment', text: 'Не входят в оплату' }))
    bill.excludedItems.forEach((item) => {
      const reason = item.reason ? ` · ${item.reason}` : ''
      const tabLabel = item.tabDisplayLabel ? `${item.tabDisplayLabel} · ` : ''
      excluded.appendChild(
        el('p', {
          className: 'venue-order-sub',
          text: `${tabLabel}${item.batchLabel}: ${item.name} ×${item.qty} — ${formatMoney(item.lineGrossMinor, item.currency)} · ${nonPayableStatusLabel(item.status)}${reason}`
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
    parts.push(`скидка заведения ${formatDiscount(item.manualDiscountMinor, item.currency)}`)
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
    parts.push(`Скидка заведения ${item.discountPercent}%`)
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
    exclude.addEventListener('click', () => handlers.onExclude(item))
    controls.appendChild(exclude)
    if (item.isPromotionReward || item.hasActivePromotionReward) {
      controls.appendChild(
        el('span', {
          className: 'promotion-reward-manual-discount-note',
          text: 'На эту позицию уже действует акция. Ручную скидку применить нельзя.'
        })
      )
    } else {
      const discount = el('button', {
        className: 'button-small button-secondary',
        text: item.discountPercent ? `Скидка ${item.discountPercent}%` : 'Скидка'
      }) as HTMLButtonElement
      discount.addEventListener('click', () => handlers.onDiscount(item))
      controls.appendChild(discount)
    }
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
  const fresh = el('section', { className: 'order-send-group' })
  const handled = el('section', { className: 'order-send-group' })
  fresh.append(el('h3', { text: 'Новые позиции — ожидают принятия' }))
  handled.append(el('h3', { text: 'Обработанные отправки' }))
  const sendsByTab = new Map<string, number>()
  order.batches.forEach((batch) => {
    const key = String(batch.tabId ?? 'unknown')
    const index = sendsByTab.get(key) ?? 0
    sendsByTab.set(key, index + 1)
    const card = el('div', { className: 'order-batch' })
    card.dataset.batchId = String(batch.batchId)
    card.id = `order-send-${batch.batchId}`
    const header = el('div', { className: 'order-batch-header' })
    append(
      header,
      el('strong', { text: batch.tabId == null ? 'Новые позиции · счёт не определён' : index === 0 ? 'Первый заказ' : `Дозаказ ${index}` }),
      el('span', { text: orderStatusLabel(batch.status) })
    )
    const tabContext = batch.tabDisplayLabel
      ? el('p', { className: 'venue-order-sub', text: batch.tabDisplayLabel })
      : null
    const comment = el('p', { className: 'order-batch-comment', text: batch.comment ?? 'Комментарий: —' })
    const list = el('div', { className: 'order-items' })
    batch.items.forEach((item) => {
      const row = el('div', {
        className: item.isPromotionReward ? 'order-item order-item-promotion-reward' : 'order-item'
      })
      const info = el('div', { className: 'order-item-main' })
      info.appendChild(el('span', { className: 'order-item-name', text: item.name }))
      if (item.isPromotionReward) {
        info.appendChild(el('span', { className: 'promotion-reward-badge', text: 'Подарок по акции' }))
      }
      if (item.selectedOption?.name) {
        info.appendChild(el('span', { className: 'order-item-details', text: `Вкус: ${item.selectedOption.name}` }))
      }
      if (item.preferenceNote) {
        info.appendChild(el('span', { className: 'order-item-details', text: `Пожелание: ${item.preferenceNote}` }))
      }
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
    append(card, header, tabContext, comment, list)
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
    ;(batch.status === 'new' ? fresh : handled).appendChild(card)
  })
  if (fresh.children.length > 1) container.append(fresh)
  if (handled.children.length > 1) container.append(handled)
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
  let highlightedTarget: string | null = null
  let isUpdating = false
  let lastSuccessfulRefreshAt: Date | null = null

  const canUpdate = access.permissions.includes('ORDER_STATUS_UPDATE')
  const canReject = access.role !== 'STAFF'
  const canEditBill = access.role !== 'STAFF'
  const canConfirmShiftExtension = access.permissions.includes('SHIFT_EXTENSION_CONFIRM')

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
          : 'Счёт доступен только для просмотра. Скидки заведения и исключение позиций доступны менеджеру или владельцу.'
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

  const approveShiftExtension = async (request: OrderPendingShiftExtensionDto) => {
    if (isUpdating || !canConfirmShiftExtension) return
    isUpdating = true
    const result = await venueApproveShiftExtensionRequest(backendUrl, { venueId, requestId: request.requestId }, deps)
    if (disposed) return
    isUpdating = false
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Продление подтверждено')
    void load()
  }

  const rejectShiftExtension = async (request: OrderPendingShiftExtensionDto) => {
    if (isUpdating || !canConfirmShiftExtension) return
    const reasonText = window.prompt('Причина отказа (необязательно)', '')?.trim()
    if (reasonText === undefined) return
    isUpdating = true
    const result = await venueRejectShiftExtensionRequest(
      backendUrl,
      { venueId, requestId: request.requestId, body: { reasonText: reasonText || undefined } },
      deps
    )
    if (disposed) return
    isUpdating = false
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Продление отклонено')
    void load()
  }

  const updateStatus = async (nextStatus: OrderDetailDto['status']) => {
    if (!orderId || isUpdating) return
    if (!canUpdate) return
    if (nextStatus === 'delivered' && hasReservedReward && !window.confirm('Подтвердить фактическую доставку всех действующих позиций? Зарезервированные награды станут использованными. Обычная отмена после выдачи их не вернёт; бонусы за счёт заведения будут учтены без изменения счёта гостя.')) return
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

  const correctionDrafts = new Map<number, { reason: string; requestId: string }>()
  const unitDrafts = new Map<number, { reason: string; requestId: string }>()
  const issueRequests = new Map<number, string>()
  const actorScope = getTelegramContext().telegramUserId
  const isCurrentLoyaltyContext = () => !disposed && getTelegramContext().telegramUserId === actorScope
  const unitDraftKey = actorScope && canEditBill
    ? `hookah:loyalty-unit:${new URL(backendUrl || window.location.origin, window.location.origin).origin}:${actorScope}:${venueId}:${orderId}`
    : null
  try {
    const saved = unitDraftKey && JSON.parse(window.sessionStorage.getItem(unitDraftKey) ?? 'null')
    if (saved && typeof saved.at === 'number' && Date.now() - saved.at < 86_400_000 && Array.isArray(saved.items)) {
      for (const [id, draft] of saved.items.slice(0, 100)) if (Number.isSafeInteger(id) && typeof draft?.reason === 'string' && typeof draft?.requestId === 'string') unitDrafts.set(id, draft)
    }
  } catch { /* Storage is optional; server idempotency remains authoritative. */ }
  const saveUnitDrafts = () => {
    if (!unitDraftKey || !isCurrentLoyaltyContext()) return
    try {
      if (unitDrafts.size) window.sessionStorage.setItem(unitDraftKey, JSON.stringify({ at: Date.now(), items: [...unitDrafts].slice(0, 100) }))
      else window.sessionStorage.removeItem(unitDraftKey)
    } catch { /* Keep the current input when storage is unavailable. */ }
  }
  let hasReservedReward = false
  const loyaltyError = (error: ApiErrorInfo) => {
    if (error.status === 403) return 'Изменение недоступно: проверьте роль и выбранное заведение. Финансовую корректировку выполняет только управляющий или владелец.'
    if ((error.status === 400 || error.status === 409) && error.message) return error.message
    return 'Результат операции не подтверждён. Введённая причина сохранена; повторите тот же запрос или обновите заказ.'
  }
  const renderLoyalty = (order: OrderDetailDto) => {
    refs.loyalty.replaceChildren()
    refs.loyalty.style.overflowWrap = 'anywhere'
    const rewards = order.loyaltyRewards ?? []
    const expenses = access.role === 'STAFF' ? [] : order.loyaltyExpenses ?? []
    const units = access.role === 'STAFF' ? [] : order.loyaltyUnits ?? []
    const history = access.role === 'STAFF' ? [] : order.loyaltyHistory ?? []
    hasReservedReward = rewards.some(reward => reward.status === 'RESERVED' && reward.origin === 'PAYMENT_POLICY')
    refs.loyalty.hidden = rewards.length + expenses.length + units.length + history.length === 0
    if (refs.loyalty.hidden) return
    append(refs.loyalty, el('h3', { text: 'Награды лояльности' }), el('p', { className: 'status', text: 'Доступная награда может быть выбрана в заказе. Резерв сохраняет скидку до выдачи; использованной награда становится после подтверждения фактической выдачи. Бесплатная единица не участвует в новом накоплении.' }))
    if (access.role === 'STAFF') refs.loyalty.appendChild(el('p', { className: 'status', text: 'Обнаружена ошибка в начислении? Передайте заказ управляющему. Изменить бонус самостоятельно нельзя.' }))
    for (const reward of rewards) {
      const state = reward.origin === 'LEGACY' ? 'Историческое состояние: выдача новой политикой не подтверждалась.'
        : reward.status === 'RESERVED' ? 'Награда зарезервирована. Бесплатный кальян ещё не выдан.'
          : reward.status === 'APPLIED' ? 'Награда использована. Выдача подтверждена.'
            : reward.venueFunded ? 'Награда отменена до выдачи. Повторно использовать её нельзя.' : 'Резерв этой позиции отменён. Доступная награда возвращена гостю для нового заказа.'
      refs.loyalty.appendChild(el('p', { text: `Позиция ${reward.batchItemId}: ${state}` }))
      if (reward.venueFunded && reward.status !== 'CANCELED') refs.loyalty.appendChild(el('p', { className: 'status', text: 'Бонус предоставляется за счёт заведения. Сумма счёта гостя не меняется.' }))
      if (reward.origin === 'PAYMENT_POLICY' && reward.status === 'RESERVED' && canUpdate) {
        refs.loyalty.appendChild(el('p', { className: 'status', text: 'Подтверждайте только фактическую выдачу. «Доставлено» для заявки подтверждает все её действующие позиции. После выдачи автоматического возврата награды нет.' }))
        const issue = el('button', { className: 'button-secondary', text: 'Подтвердить выдачу наградного кальяна' }) as HTMLButtonElement
        const resultText = el('p', { className: 'status' }); resultText.setAttribute('role', 'status')
        issue.disabled = isUpdating
        issue.addEventListener('click', async () => {
          if (!isCurrentLoyaltyContext() || isUpdating) return
          if (!window.confirm(`Подтвердить, что наградной кальян по позиции ${reward.batchItemId} фактически выдан? Награда станет использованной. Обычная отмена её не вернёт.${reward.venueFunded ? ' Будет учтён бонус за счёт заведения; счёт гостя не изменится.' : ''}`)) return
          const requestId = issueRequests.get(reward.id) ?? crypto.randomUUID(); issueRequests.set(reward.id, requestId)
          isUpdating = true; issue.disabled = true
          const result = await requestApi(backendUrl, `/api/venue/orders/${order.orderId}/loyalty/${reward.id}/confirm-delivery?venueId=${venueId}`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId, reason: '' })
          }, deps)
          if (!isCurrentLoyaltyContext()) return
          isUpdating = false; issue.disabled = false
          if (!result.ok) { resultText.textContent = loyaltyError(result.error); return }
          issueRequests.delete(reward.id); void load()
        })
        append(refs.loyalty, issue, resultText)
      }
      if (reward.status === 'CANCELED') { unitDrafts.delete(-reward.id); saveUnitDrafts() }
      if (reward.origin === 'PAYMENT_POLICY' && reward.status === 'RESERVED' && reward.venueFunded && canEditBill) {
        refs.loyalty.appendChild(el('p', { className: 'status', text: 'Если этот бонус не будет выдан, управляющий может завершить только его компенсационный резерв. Количество и цены соседних единиц, закрытый счёт и оплаты останутся прежними. Недействительное право не вернётся в доступные награды; расход выдачи не возникнет.' }))
        const key = -reward.id
        const input = el('textarea', { className: 'venue-input' }) as HTMLTextAreaElement
        input.setAttribute('aria-label', 'Причина отмены невыданного бонуса'); input.value = unitDrafts.get(key)?.reason ?? ''
        const button = el('button', { className: 'button-secondary', text: 'Отменить невыданный бонус' }) as HTMLButtonElement
        const message = el('p', { className: 'status' }); message.setAttribute('role', 'status')
        input.addEventListener('input', () => { const old = unitDrafts.get(key); unitDrafts.set(key, { reason: input.value, requestId: old?.reason.trim() === input.value.trim() ? old.requestId : '' }); saveUnitDrafts() })
        button.disabled = isUpdating
        button.addEventListener('click', async () => {
          if (!isCurrentLoyaltyContext() || isUpdating) return
          const reason = input.value.trim()
          if (!reason || Array.from(reason).length > 500) { message.textContent = 'Укажите причину отмены до 500 символов.'; return }
          if (!window.confirm('Отменить только невыданный бонус за счёт заведения? Скидка и исторический счёт сохранятся. Недействительная награда не станет доступной снова; расход выдачи не создаётся.')) return
          const old = unitDrafts.get(key)
          const requestId = old?.requestId && old.reason.trim() === reason ? old.requestId : crypto.randomUUID()
          unitDrafts.set(key, { reason: input.value, requestId }); saveUnitDrafts()
          isUpdating = true; button.disabled = true; input.disabled = true
          const result = await requestApi<{ replayed: boolean }>(backendUrl, `/api/venue/orders/${order.orderId}/loyalty/${reward.id}/cancel-compensation?venueId=${venueId}`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId, reason })
          }, deps)
          if (!isCurrentLoyaltyContext()) return
          isUpdating = false; button.disabled = false; input.disabled = false
          if (!result.ok) { message.textContent = `${loyaltyError(result.error)} Ввод сохранён.`; return }
          unitDrafts.delete(key); saveUnitDrafts(); showToast(result.data.replayed ? 'Отмена уже была выполнена. Обновляем состояние.' : 'Невыданный бонус отменён; счёт сохранён'); void load()
        })
        append(refs.loyalty, input, button, message)
      }
      if (reward.origin === 'PAYMENT_POLICY' && reward.status === 'APPLIED' && canEditBill && reward.correctionAllowed) {
        refs.loyalty.appendChild(el('p', { className: 'status', text: 'Ошибочную отметку выдачи можно исправить с причиной: действующая позиция вернётся в резерв. Для отменённой позиции заработанное право возвращается только если оно действительно; неиспользованная компенсация заведения завершается. Денежного возврата нет.' }))
        const input = el('input', { className: 'venue-input' }) as HTMLInputElement
        input.setAttribute('aria-label', 'Причина исправления выдачи'); input.placeholder = 'Причина ошибочной отметки о выдаче'
        input.value = correctionDrafts.get(reward.id)?.reason ?? ''
        const button = el('button', { className: 'button-secondary', text: 'Исправить ошибочную выдачу' }) as HTMLButtonElement
        const message = el('p', { className: 'status' }); message.setAttribute('role', 'status')
        button.disabled = isUpdating
        button.addEventListener('click', async () => {
          if (!isCurrentLoyaltyContext() || isUpdating) return
          const reason = input.value.trim()
          if (!reason || Array.from(reason).length > 500) { message.textContent = 'Укажите причину до 500 символов.'; return }
          if (!window.confirm('Исправить ошибочную отметку о выдаче? Счёт и подтверждённые оплаты не меняются. История и причина сохранятся; действующая позиция снова будет ожидать подтверждения выдачи.')) return
          let draft = correctionDrafts.get(reward.id)
          if (!draft || draft.reason !== reason) { draft = { reason, requestId: crypto.randomUUID() }; correctionDrafts.set(reward.id, draft) }
          isUpdating = true; button.disabled = true
          const result = await requestApi(backendUrl, `/api/venue/orders/${order.orderId}/loyalty/${reward.id}/correct-delivery?venueId=${venueId}`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(draft)
          }, deps)
          if (!isCurrentLoyaltyContext()) return
          isUpdating = false; button.disabled = false
          if (!result.ok) { message.textContent = loyaltyError(result.error); return }
          correctionDrafts.delete(reward.id); showToast('Ошибочная выдача исправлена; история сохранена'); void load()
        })
        append(refs.loyalty, input, button, message)
      }
    }
    if (units.length) {
      append(refs.loyalty, el('h4', { text: 'Корректировка начисления' }), el('p', { className: 'status', text: 'Начисление основано на полной подтверждённой оплате конкретной закрытой цифровой продажи и фактической выдаче подходящей платной единицы. Исправляется только выбранная единица: количество, цены, закрытый счёт и подтверждённые оплаты не меняются. Уже обещанная скидка сохраняется за счёт заведения; расход фиксируется после фактической выдачи, без долга гостя и удержания будущих наград.' }))
      const eligible = units.filter(unit => unit.canCorrect)
      for (const unit of units.filter(unit => unit.correctedAt)) {
        unitDrafts.delete(unit.id)
        refs.loyalty.appendChild(el('p', { className: 'status', text: `${unit.name} · единица ${unit.unitIndex} из ${unit.qty}: начисление исправлено. ${unit.correctedByLabel || 'Управляющий'} · ${new Date(unit.correctedAt!).toLocaleString()} · Причина: ${unit.correctionReason}. Повторная коррекция не требуется.` }))
      }
      saveUnitDrafts()
      if (!eligible.length) refs.loyalty.appendChild(el('p', { className: 'status', text: 'Нет единиц для корректировки. Уже исправленные начисления повторно не изменяются. Если данные неполные, передайте заказ управляющему для сверки.' }))
      else {
        const select = el('select', { className: 'venue-input' }) as HTMLSelectElement
        select.setAttribute('aria-label', 'Единица для корректировки')
        const placeholder = el('option', { text: 'Выберите одну единицу кальяна' }); placeholder.value = ''; select.appendChild(placeholder)
        for (const unit of eligible) { const option = el('option', { text: `${unit.name} · заявка ${unit.batchId} · единица ${unit.unitIndex} из ${unit.qty}` }) as HTMLOptionElement; option.value = String(unit.id); select.appendChild(option) }
        const pending = eligible.find(unit => unitDrafts.has(unit.id)); if (pending) select.value = String(pending.id)
        const basis = el('p', { className: 'status' })
        const reason = el('textarea', { className: 'venue-input' }) as HTMLTextAreaElement
        reason.setAttribute('aria-label', 'Причина корректировки начисления'); reason.placeholder = 'Почему эта единица была учтена ошибочно'
        const count = el('p', { className: 'status' })
        const button = el('button', { className: 'button-secondary', text: 'Исправить начисление выбранной единицы' }) as HTMLButtonElement
        const message = el('p', { className: 'status' }); message.setAttribute('role', 'status')
        const remember = () => {
          const id = Number(select.value); if (!id) return
          const old = unitDrafts.get(id)
          unitDrafts.set(id, { reason: reason.value, requestId: old?.reason.trim() === reason.value.trim() ? old.requestId : '' }); saveUnitDrafts()
        }
        const update = (restore = false) => {
          const unit = eligible.find(item => item.id === Number(select.value))
          if (restore) reason.value = unit ? unitDrafts.get(unit.id)?.reason ?? '' : ''
          basis.textContent = unit ? `Выбрана единица ${unit.unitIndex} из ${unit.qty} · ${formatPrice(unit.priceMinor ?? 0, order.bill.currency)}. Начисление подтверждено ${unit.earnedAt ? new Date(unit.earnedAt).toLocaleString() : 'в истории оплаты'}.` : 'Выберите конкретную единицу. Соседние оплаченные единицы останутся без изменений.'
          count.textContent = `${Array.from(reason.value).length}/500 символов`
          button.disabled = !unit || isUpdating; reason.disabled = !unit || isUpdating; select.disabled = isUpdating
        }
        select.addEventListener('change', () => { message.textContent = ''; update(true) })
        reason.addEventListener('input', () => { remember(); update() })
        button.addEventListener('click', async () => {
          if (!isCurrentLoyaltyContext() || isUpdating) return
          const unit = eligible.find(item => item.id === Number(select.value)); if (!unit) return
          const text = reason.value.trim()
          if (!text || Array.from(text).length > 500) { message.textContent = 'Укажите конкретную причину до 500 символов. Ввод сохранён.'; return }
          if (!window.confirm(`Исправить начисление за «${Array.from(unit.name).slice(0, 80).join('')}», единица ${unit.unitIndex} из ${unit.qty}? Закрытый счёт и оплаты не меняются. Обещанная скидка сохраняется за счёт заведения. Причина: ${Array.from(text).slice(0, 160).join('')}`)) return
          const draft = unitDrafts.get(unit.id)
          const requestId = draft?.requestId && draft.reason.trim() === text ? draft.requestId : crypto.randomUUID()
          unitDrafts.set(unit.id, { reason: reason.value, requestId }); saveUnitDrafts()
          isUpdating = true; update(); message.textContent = 'Сохраняем корректировку…'
          const result = await requestApi<{ replayed: boolean }>(backendUrl, `/api/venue/orders/${order.orderId}/loyalty/units/${unit.id}/correct?venueId=${venueId}`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ requestId, reason: text })
          }, deps)
          if (!isCurrentLoyaltyContext()) return
          isUpdating = false; update()
          if (!result.ok) { message.textContent = `${loyaltyError(result.error)} Введённая причина сохранена.`; return }
          unitDrafts.delete(unit.id); saveUnitDrafts()
          showToast(result.data.replayed ? 'Корректировка уже была выполнена. Показаны актуальные данные.' : 'Начисление одной единицы исправлено; счёт гостя сохранён')
          void load()
        })
        update(true); append(refs.loyalty, select, basis, reason, count, button, message)
      }
    }
    if (history.length) {
      refs.loyalty.appendChild(el('h4', { text: 'История награды' }))
      for (const entry of history) {
        const confirmed = entry.action === 'DELIVERY_CONFIRMED'
        const title = confirmed ? 'Выдача награды подтверждена' : entry.action === 'DELIVERY_CORRECTED' ? 'Исправлена отметка о выдаче' : 'Отменена компенсация'
        const record = el('section', { className: 'loyalty-history-entry' })
        const result = confirmed ? 'Награда использована: фактическая выдача подтверждена.' : entry.action === 'DELIVERY_CORRECTED'
          ? 'Исправление сохранено. Текущее состояние награды показано выше.'
          : 'Невыданный бонус отменён. Недействительное право не возвращается в доступные награды.'
        append(record,
          el('p', { text: title }),
          el('p', { text: `${entry.itemName} · одна наградная единица · позиция №${entry.batchItemId} · награда №${entry.redemptionId}` }),
          el('p', { className: 'status', text: `${new Date(entry.occurredAt).toLocaleString()} · сотрудник №${entry.actorUserId}` }),
        )
        if (entry.reason != null) record.appendChild(el('p', { text: `Причина: ${entry.reason}` }))
        record.appendChild(el('p', { className: 'status', text: result }))
        if (entry.action === 'DELIVERY_CORRECTED' && entry.compensationCancellation) record.appendChild(el('p', { className: 'status', text: 'Для этой же награды сохранена связанная отмена компенсации.' }))
        refs.loyalty.appendChild(record)
      }
    }
    if (expenses.length) {
      refs.loyalty.appendChild(el('p', { className: 'status', text: 'Расход отражает фактически выданный бонус по исторической цене скидки. Это неденежный факт лояльности: поступления наличными, по карте и переводом не увеличиваются. Отдельной строки этого расхода в кассовом отчёте пока нет.' }))
      for (const expense of expenses) refs.loyalty.appendChild(el('p', { className: 'status', text: `${expense.resolved ? 'История исправления расхода' : 'Бонус за счёт заведения'} · ${formatPrice(expense.amountMinor, order.bill.currency)} · ${expense.recordedAt ? new Date(expense.recordedAt).toLocaleString() : ''}${expense.actorUserId ? ` · сотрудник №${expense.actorUserId}` : ''} · ${expense.reason}` }))
    }
  }

  const load = async () => {
    if (!orderId) {
      refs.title.textContent = 'Заказ не найден'
      refs.meta.textContent = ''
      refs.status.textContent = ''
      refs.actions.replaceChildren()
      refs.shiftExtensionCard.hidden = true
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
    if (disposed || loadSeq !== seq || getTelegramContext().telegramUserId !== actorScope) return
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
    renderShiftExtensionCard(
      refs.shiftExtensionCard,
      order.pendingShiftExtension,
      canConfirmShiftExtension,
      isUpdating,
      {
        onApprove: approveShiftExtension,
        onReject: rejectShiftExtension
      }
    )
    renderBill(refs.bill, order)
    renderLoyalty(order)
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
    const targetBatch = new URL(window.location.href).hash.split('?')[1]
    const targetId = new URLSearchParams(targetBatch ?? '').get('batchId')
    if (targetId && /^\d+$/.test(targetId)) {
      const target = refs.batches.querySelector<HTMLElement>(`[data-batch-id="${targetId}"]`)
      if (target && highlightedTarget !== targetId) { target.classList.add('order-send-highlight'); highlightedTarget = targetId; target.dataset.highlighted = 'true'; target.tabIndex = -1; target.focus({ preventScroll: true }); target.scrollIntoView({ block: 'start' }) }
    }
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
