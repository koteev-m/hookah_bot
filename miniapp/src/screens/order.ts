import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestGetActiveOrder, guestGetTabs } from '../shared/api/guestApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { ActiveOrderDto, GuestTabDto, OrderBatchDto } from '../shared/api/guestDtos'
import { getItemMeta } from '../shared/state/itemCache'
import { getSelectedGuestTabId, setSelectedGuestTabId } from '../shared/state/guestTabSelection'
import { getTableContext, subscribe as subscribeTable } from '../shared/state/tableContext'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { formatPrice } from '../shared/ui/price'

const POLL_INTERVAL_MS = 12000

type OrderScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onNavigateMenu: (venueId: number | null) => void
}

type OrderRefs = {
  header: HTMLDivElement
  title: HTMLHeadingElement
  statusValue: HTMLParagraphElement
  hint: HTMLParagraphElement
  refreshPanel: HTMLDivElement
  updatedAt: HTMLParagraphElement
  message: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  refreshButton: HTMLButtonElement
  addButton: HTMLButtonElement
  content: HTMLDivElement
}

const STATUS_LABELS: Record<string, string> = {
  active: 'Активный',
  new: 'Новый',
  accepted: 'Принят',
  cooking: 'Готовится',
  delivering: 'Доставляется',
  delivered: 'Доставлен',
  closed: 'Закрыт',
  rejected: 'Отклонён'
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function resolveTableHint(snapshot: ReturnType<typeof getTableContext>): string | null {
  switch (snapshot.status) {
    case 'missing':
    case 'invalid':
    case 'notFound':
      return 'Сначала отсканируйте QR'
    case 'resolving':
      return 'Загрузка стола…'
    case 'error':
      return 'Не удалось загрузить стол. Попробуйте позже.'
    case 'resolved':
      if (!snapshot.tableToken) {
        return 'Сначала отсканируйте QR'
      }
      if (!snapshot.orderAllowed) {
        return snapshot.blockReasonText ?? 'Заказы временно недоступны.'
      }
      return null
    default:
      return null
  }
}

function formatItemTitle(itemId: number): string {
  const meta = getItemMeta(itemId)
  if (!meta) {
    return String(itemId)
  }
  return meta.name
}

function orderStatusLabel(status: string): string {
  return STATUS_LABELS[status.toLowerCase()] ?? status
}

function formatMoney(amountMinor: number, currency: string | null | undefined): string {
  return formatPrice(amountMinor, currency || 'RUB')
}

function formatDiscount(amountMinor: number, currency: string | null | undefined): string {
  return `−${formatMoney(amountMinor, currency)}`
}

function isLoyaltyDiscount(ruleType: string | null | undefined, label: string): boolean {
  return ruleType?.toUpperCase() === 'LOYALTY_NTH_HOOKAH' || label.toLowerCase().includes('лояльность')
}

function appendBillRow(container: HTMLElement, label: string, value: string, isTotal = false) {
  const row = el('div', { className: isTotal ? 'order-bill-row order-bill-total' : 'order-bill-row' })
  append(row, el('span', { text: label }), el('strong', { text: value }))
  container.appendChild(row)
}

function formatRefreshTime(date: Date): string {
  return date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function chooseOrderTab(tabs: GuestTabDto[], tableSessionId: number): GuestTabDto | null {
  const activeTabs = tabs.filter((tab) => tab.status === 'ACTIVE' && tab.tableSessionId === tableSessionId)
  const storedTabId = getSelectedGuestTabId(tableSessionId)
  const storedTab = storedTabId != null ? activeTabs.find((tab) => tab.id === storedTabId) ?? null : null
  if (storedTab) {
    return storedTab
  }
  return activeTabs.find((tab) => tab.type === 'PERSONAL') ?? activeTabs[0] ?? null
}

function buildOrderDom(root: HTMLDivElement): OrderRefs {
  const wrapper = el('div', { className: 'order-screen' })
  const header = el('div', { className: 'card' }) as HTMLDivElement
  header.hidden = true
  const title = el('h3', { text: 'Мой заказ' })
  const statusValue = el('p', { className: 'order-status', text: '' })
  const hint = el('p', { className: 'order-hint', text: '' })
  hint.hidden = true

  const buttonRow = el('div', { className: 'button-row order-actions' })
  const addButton = el('button', { className: 'button-small', text: 'Добавить к заказу' }) as HTMLButtonElement
  append(buttonRow, addButton)
  append(header, title, statusValue, hint, buttonRow)

  const refreshPanel = el('div', { className: 'button-row order-refresh-panel' }) as HTMLDivElement
  const refreshButton = el('button', { className: 'button-small', text: '🔄 Обновить' }) as HTMLButtonElement
  const updatedAt = el('p', { className: 'order-updated-at', text: '' })
  updatedAt.hidden = true
  append(refreshPanel, refreshButton, updatedAt)

  const message = el('p', { className: 'order-message', text: '' })
  message.hidden = true

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const content = el('div', { className: 'order-content' })

  append(wrapper, header, refreshPanel, message, error, content)
  root.replaceChildren(wrapper)

  return {
    header,
    title,
    statusValue,
    hint,
    refreshPanel,
    updatedAt,
    message,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    refreshButton,
    addButton,
    content
  }
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

function renderDiscountBreakdown(container: HTMLElement, order: ActiveOrderDto) {
  const promoDiscounts = order.discounts.filter((discount) => !isLoyaltyDiscount(discount.ruleType, discount.label))
  const loyaltyDiscounts = order.discounts.filter((discount) => isLoyaltyDiscount(discount.ruleType, discount.label))
  if (!promoDiscounts.length && !loyaltyDiscounts.length) return
  const block = el('div', { className: 'order-discount-list' })
  block.appendChild(el('p', { className: 'order-batch-comment', text: 'Скидки и бонусы' }))
  promoDiscounts.forEach((discount) => {
    appendBillRow(block, discount.label || 'Акция', formatDiscount(discount.discountMinor, discount.currency))
  })
  loyaltyDiscounts.forEach((discount) => {
    appendBillRow(block, discount.label || 'Лояльность', formatDiscount(discount.discountMinor, discount.currency))
  })
  container.appendChild(block)
}

function renderOrderBill(container: HTMLElement, order: ActiveOrderDto) {
  const bill = el('div', { className: 'card venue-order-bill' })
  bill.appendChild(el('h3', { text: 'Итого по заказу' }))
  appendBillRow(bill, 'Сумма до скидок', formatMoney(order.grossTotalMinor, order.currency))
  if (order.manualDiscountTotalMinor > 0) {
    appendBillRow(bill, 'Ручные скидки', formatDiscount(order.manualDiscountTotalMinor, order.currency))
  }
  if (order.promoDiscountTotalMinor > 0) {
    appendBillRow(bill, 'Акции', formatDiscount(order.promoDiscountTotalMinor, order.currency))
  }
  if (order.loyaltyDiscountTotalMinor > 0) {
    appendBillRow(bill, 'Лояльность', formatDiscount(order.loyaltyDiscountTotalMinor, order.currency))
  }
  renderDiscountBreakdown(bill, order)
  const serviceCharges = order.serviceCharges ?? []
  serviceCharges.forEach((charge) => {
    appendBillRow(bill, charge.label, formatMoney(charge.totalMinor, charge.currency))
  })
  appendBillRow(bill, 'К оплате', formatMoney(order.finalPayableTotalMinor, order.currency), true)
  container.appendChild(bill)
}

function renderItemPriceMeta(item: OrderBatchDto['items'][number]) {
  const parts: string[] = []
  if (item.lineGrossMinor > 0) {
    parts.push(formatMoney(item.lineGrossMinor, item.currency))
  } else if (item.priceMinor != null && item.currency) {
    parts.push(formatMoney(item.priceMinor * item.qty, item.currency))
  }
  if (item.manualDiscountMinor > 0) {
    parts.push(`ручная скидка ${formatDiscount(item.manualDiscountMinor, item.currency)}`)
  }
  if (item.promoDiscountMinor > 0) {
    parts.push(`акции/бонусы ${formatDiscount(item.promoDiscountMinor, item.currency)}`)
  }
  if (item.linePayableMinor >= 0 && (item.lineGrossMinor > 0 || item.promoDiscountMinor > 0 || item.manualDiscountMinor > 0)) {
    parts.push(`к оплате ${formatMoney(item.linePayableMinor, item.currency)}`)
  }
  return parts.join(' · ')
}

function renderBatches(container: HTMLElement, batches: OrderBatchDto[]) {
  container.replaceChildren()
  if (batches.length === 0) {
    container.appendChild(el('p', { className: 'order-empty', text: 'Пока нет добавленных заявок.' }))
    return
  }
  const list = el('div', { className: 'order-batches' })
  batches.forEach((batch, index) => {
    const card = el('div', { className: 'order-batch' })
    const header = el('div', { className: 'order-batch-header' })
    const batchTitle = el('strong', { text: index === 0 ? 'Состав заказа' : `Дозаказ ${index}` })
    const batchMeta = el('span', { text: `Заявка №${batch.batchId}` })
    append(header, batchTitle, batchMeta)
    if (batch.comment) {
      const comment = el('p', { className: 'order-batch-comment', text: batch.comment })
      append(card, header, comment)
    } else {
      append(card, header)
    }

    const items = el('div', { className: 'order-items' })
    batch.items.forEach((item) => {
      const row = el('div', { className: 'order-item' })
      const info = el('div', { className: 'order-item-main' })
      const name = el('span', { className: 'order-item-name', text: item.name || formatItemTitle(item.itemId) })
      info.appendChild(name)
      if (item.selectedOption?.name) {
        info.appendChild(el('span', { className: 'order-item-details', text: `Вкус: ${item.selectedOption.name}` }))
      }
      if (item.preferenceNote) {
        info.appendChild(el('span', { className: 'order-item-details', text: `Пожелание: ${item.preferenceNote}` }))
      }
      const priceMeta = renderItemPriceMeta(item)
      if (priceMeta) {
        info.appendChild(el('span', { className: 'order-item-details', text: priceMeta }))
      }
      const qty = el('span', { className: 'order-item-qty', text: `×${item.qty}` })
      append(row, info, qty)
      items.appendChild(row)
    })
    append(card, items)
    list.appendChild(card)
  })
  container.appendChild(list)
}

export function renderOrderScreen(options: OrderScreenOptions) {
  const { root, backendUrl, isDebug, onNavigateMenu } = options
  if (!root) return () => undefined

  const refs = buildOrderDom(root)
  let disposed = false
  let tableSnapshot = getTableContext()
  let orderAbort: AbortController | null = null
  let pollTimer: number | null = null
  let inFlight = false
  let currentOrder: ActiveOrderDto | null = null
  let lastError: ApiErrorInfo | null = null
  let hadLoadedOrder = false
  let emptyStateReason: 'none' | 'closed' = 'none'
  let lastSuccessfulRefreshAt: Date | null = null

  const setMessage = (text: string | null) => {
    refs.message.textContent = text ?? ''
    refs.message.hidden = !text
  }

  const hideError = () => {
    refs.error.hidden = true
    refs.errorActions.replaceChildren()
    refs.errorDetails.replaceChildren()
  }

  const showError = (error: ApiErrorInfo) => {
    const normalizedCode = normalizeErrorCode(error)
    if (normalizedCode === ApiErrorCodes.UNAUTHORIZED || normalizedCode === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'table' })
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    refs.error.dataset.severity = presentation.severity
    const actions = presentation.actions.map((action) => {
      if (action.label === 'Повторить') {
        return { ...action, onClick: () => void loadOrder() }
      }
      return action
    })
    if (!actions.length) {
      actions.push({ label: 'Повторить', onClick: () => void loadOrder() })
    }
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, {
      isDebug,
      extraNotes: presentation.debugLine ? [presentation.debugLine] : undefined
    })
    refs.error.hidden = false
  }

  const getTableScope = () => {
    if (
      tableSnapshot.status !== 'resolved' ||
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

  const updateHint = () => {
    const hintText = resolveTableHint(tableSnapshot)
    refs.hint.textContent = hintText ?? ''
    refs.hint.hidden = !hintText
    const tableScope = getTableScope()
    const blocked = Boolean(hintText) || !tableScope
    refs.refreshButton.disabled = blocked || inFlight
    refs.addButton.disabled = blocked
    refs.updatedAt.textContent = lastSuccessfulRefreshAt ? `Обновлено ${formatRefreshTime(lastSuccessfulRefreshAt)}` : ''
    refs.updatedAt.hidden = !lastSuccessfulRefreshAt
  }

  const renderState = () => {
    refs.content.replaceChildren()
    updateHint()
    hideError()
    refs.header.hidden = !currentOrder
    if (inFlight) {
      refs.content.appendChild(el('p', { className: 'order-empty', text: 'Загрузка заказа…' }))
      return
    }
    if (lastError) {
      showError(lastError)
      refs.content.appendChild(el('p', { className: 'order-empty', text: 'Не удалось загрузить заказ.' }))
      return
    }
    if (!currentOrder) {
      refs.content.appendChild(
        el('p', {
          className: 'order-empty',
          text:
            emptyStateReason === 'closed'
              ? 'Счёт закрыт. Активного заказа по этому счёту больше нет.'
              : 'Активного заказа нет.'
        })
      )
      return
    }
    refs.title.textContent = currentOrder.displayNumber ? `Заказ №${currentOrder.displayNumber}` : 'Мой заказ'
    refs.statusValue.textContent = `Статус: ${orderStatusLabel(currentOrder.status)}`
    if (currentOrder.status.toLowerCase() === 'closed') {
      refs.content.appendChild(el('p', { className: 'order-empty', text: 'Счёт закрыт.' }))
    }
    renderBatches(refs.content, currentOrder.batches ?? [])
    renderOrderBill(refs.content, currentOrder)
  }

  const loadOrder = async () => {
    if (inFlight) {
      return
    }
    const tableScope = getTableScope()
    if (!tableScope) {
      if (orderAbort) {
        orderAbort.abort()
        orderAbort = null
      }
      currentOrder = null
      lastError = null
      inFlight = false
      emptyStateReason = 'none'
      lastSuccessfulRefreshAt = null
      refs.statusValue.textContent = 'Статус: —'
      refs.title.textContent = 'Мой заказ'
      renderState()
      return
    }
    if (orderAbort) {
      orderAbort.abort()
    }
    const controller = new AbortController()
    orderAbort = controller
    inFlight = true
    lastError = null
    setMessage(null)
    renderState()

    const deps = buildApiDeps(isDebug)
    const tabsResult = await guestGetTabs(backendUrl, tableScope.tableSessionId, deps, controller.signal)
    if (disposed || orderAbort !== controller) {
      return
    }
    if (controller.signal.aborted) {
      inFlight = false
      orderAbort = null
      renderState()
      return
    }
    const currentScope = getTableScope()
    if (
      !currentScope ||
      currentScope.tableToken !== tableScope.tableToken ||
      currentScope.tableSessionId !== tableScope.tableSessionId
    ) {
      inFlight = false
      orderAbort = null
      currentOrder = null
      lastError = null
      emptyStateReason = 'none'
      refs.statusValue.textContent = 'Статус: —'
      refs.title.textContent = 'Мой заказ'
      renderState()
      return
    }
    if (!tabsResult.ok) {
      lastError = tabsResult.error
      currentOrder = null
      inFlight = false
      orderAbort = null
      emptyStateReason = 'none'
      refs.statusValue.textContent = 'Статус: —'
      refs.title.textContent = 'Мой заказ'
      renderState()
      return
    }
    const selectedTab = chooseOrderTab(tabsResult.data.tabs, tableScope.tableSessionId)
    if (!selectedTab) {
      inFlight = false
      orderAbort = null
      currentOrder = null
      lastError = null
      emptyStateReason = 'none'
      refs.statusValue.textContent = 'Статус: —'
      refs.title.textContent = 'Мой заказ'
      setMessage('Не удалось определить счёт для заказа. Откройте корзину и выберите счёт.')
      renderState()
      return
    }
    setSelectedGuestTabId(tableScope.tableSessionId, selectedTab.id)

    const result = await guestGetActiveOrder(
      backendUrl,
      tableScope.tableToken,
      tableScope.tableSessionId,
      selectedTab.id,
      deps,
      controller.signal
    )
    if (disposed || orderAbort !== controller) {
      return
    }
    if (controller.signal.aborted) {
      inFlight = false
      orderAbort = null
      renderState()
      return
    }
    const afterLoadScope = getTableScope()
    if (
      !afterLoadScope ||
      afterLoadScope.tableToken !== tableScope.tableToken ||
      afterLoadScope.tableSessionId !== tableScope.tableSessionId
    ) {
      inFlight = false
      orderAbort = null
      currentOrder = null
      lastError = null
      emptyStateReason = 'none'
      refs.statusValue.textContent = 'Статус: —'
      refs.title.textContent = 'Мой заказ'
      renderState()
      return
    }
    inFlight = false
    orderAbort = null
    if (!result.ok) {
      lastError = result.error
      currentOrder = null
      emptyStateReason = 'none'
      refs.statusValue.textContent = 'Статус: —'
      renderState()
      return
    }

    const activeOrder = result.data.order
    if (
      activeOrder &&
      (activeOrder.tableSessionId !== tableScope.tableSessionId || activeOrder.tabId !== selectedTab.id)
    ) {
      currentOrder = null
      lastError = null
      emptyStateReason = 'none'
      refs.statusValue.textContent = 'Статус: —'
      setMessage('Заказ относится к другому счёту. Обновите Mini App и попробуйте снова.')
      renderState()
      return
    }

    currentOrder = activeOrder
    if (activeOrder) {
      hadLoadedOrder = true
      emptyStateReason = activeOrder.status.toLowerCase() === 'closed' ? 'closed' : 'none'
    } else {
      emptyStateReason = hadLoadedOrder ? 'closed' : 'none'
    }
    lastSuccessfulRefreshAt = new Date()
    lastError = null
    refs.statusValue.textContent = currentOrder ? `Статус: ${orderStatusLabel(currentOrder.status)}` : 'Статус: —'
    renderState()
  }

  const startPolling = () => {
    if (!getTableScope()) {
      return
    }
    if (pollTimer) {
      return
    }
    pollTimer = window.setInterval(() => {
      if (!getTableScope() || inFlight || orderAbort) {
        return
      }
      void loadOrder()
    }, POLL_INTERVAL_MS)
  }

  const stopPolling = () => {
    if (pollTimer) {
      window.clearInterval(pollTimer)
      pollTimer = null
    }
  }

  const syncPolling = () => {
    if (getTableScope()) {
      startPolling()
    } else {
      stopPolling()
    }
  }

  const updateTableSnapshot = (snapshot: typeof tableSnapshot) => {
    const prevScope = getTableScope()
    tableSnapshot = snapshot
    updateHint()
    const nextScope = getTableScope()
    if (
      nextScope &&
      (!prevScope ||
        nextScope.tableToken !== prevScope.tableToken ||
        nextScope.tableSessionId !== prevScope.tableSessionId)
    ) {
      void loadOrder()
    }
    if (!nextScope) {
      stopPolling()
      if (orderAbort) {
        orderAbort.abort()
        orderAbort = null
      }
      inFlight = false
      currentOrder = null
      lastError = null
      emptyStateReason = 'none'
      lastSuccessfulRefreshAt = null
      refs.statusValue.textContent = 'Статус: —'
      refs.title.textContent = 'Мой заказ'
      renderState()
    } else {
      syncPolling()
    }
  }

  const disposables: Array<() => void> = []
  disposables.push(
    on(refs.refreshButton, 'click', () => {
      void loadOrder()
    }),
    on(refs.addButton, 'click', () => {
      onNavigateMenu(tableSnapshot.status === 'resolved' ? tableSnapshot.venueId : null)
    })
  )

  const tableSubscription = subscribeTable((snapshot) => {
    updateTableSnapshot(snapshot)
  })

  void loadOrder()
  syncPolling()

  return () => {
    disposed = true
    orderAbort?.abort()
    if (pollTimer) {
      window.clearInterval(pollTimer)
    }
    tableSubscription()
    disposables.forEach((dispose) => dispose())
  }
}
