import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestGetActiveOrder } from '../shared/api/guestApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { ActiveOrderDto, OrderBatchDto } from '../shared/api/guestDtos'
import { getItemMeta } from '../shared/state/itemCache'
import { getTableContext, subscribe as subscribeTable } from '../shared/state/tableContext'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'

const POLL_INTERVAL_MS = 12000

type OrderScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onNavigateMenu: (venueId: number | null) => void
}

type OrderRefs = {
  statusValue: HTMLParagraphElement
  hint: HTMLParagraphElement
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

function buildOrderDom(root: HTMLDivElement): OrderRefs {
  const wrapper = el('div', { className: 'order-screen' })
  const header = el('div', { className: 'card' })
  const title = el('h3', { text: 'Активный заказ' })
  const statusValue = el('p', { className: 'order-status', text: '' })
  const hint = el('p', { className: 'order-hint', text: '' })
  hint.hidden = true

  const buttonRow = el('div', { className: 'button-row order-actions' })
  const refreshButton = el('button', { className: 'button-small', text: 'Обновить' }) as HTMLButtonElement
  const addButton = el('button', { className: 'button-small', text: 'Добавить к заказу' }) as HTMLButtonElement
  append(buttonRow, refreshButton, addButton)
  append(header, title, statusValue, hint, buttonRow)

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

  append(wrapper, header, message, error, content)
  root.replaceChildren(wrapper)

  return {
    statusValue,
    hint,
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

function renderBatches(container: HTMLElement, batches: OrderBatchDto[]) {
  container.replaceChildren()
  if (batches.length === 0) {
    container.appendChild(el('p', { className: 'order-empty', text: 'Пока нет добавленных партий.' }))
    return
  }
  const list = el('div', { className: 'order-batches' })
  batches.forEach((batch) => {
    const card = el('div', { className: 'order-batch' })
    const header = el('div', { className: 'order-batch-header' })
    const batchTitle = el('strong', { text: `Партия #${batch.batchId}` })
    append(header, batchTitle)
    if (batch.comment) {
      const comment = el('p', { className: 'order-batch-comment', text: batch.comment })
      append(card, header, comment)
    } else {
      append(card, header)
    }

    const items = el('div', { className: 'order-items' })
    batch.items.forEach((item) => {
      const row = el('div', { className: 'order-item' })
      const name = el('span', { className: 'order-item-name', text: formatItemTitle(item.itemId) })
      const qty = el('span', { className: 'order-item-qty', text: `×${item.qty}` })
      append(row, name, qty)
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
    const presentation = presentApiError(error, { isDebug })
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

  const getTableToken = () => (tableSnapshot.status === 'resolved' ? tableSnapshot.tableToken : null)

  const updateHint = () => {
    const hintText = resolveTableHint(tableSnapshot)
    refs.hint.textContent = hintText ?? ''
    refs.hint.hidden = !hintText
    const blocked = Boolean(hintText)
    refs.refreshButton.disabled = blocked || inFlight
    refs.addButton.disabled = blocked
  }

  const renderState = () => {
    refs.content.replaceChildren()
    updateHint()
    hideError()
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
      refs.content.appendChild(el('p', { className: 'order-empty', text: 'Активного заказа нет.' }))
      return
    }
    refs.statusValue.textContent = `Статус: ${currentOrder.status}`
    renderBatches(refs.content, currentOrder.batches ?? [])
  }

  const loadOrder = async () => {
    const tableToken = getTableToken()
    if (!tableToken) {
      if (orderAbort) {
      orderAbort.abort()
      orderAbort = null
    }
    currentOrder = null
    lastError = null
    inFlight = false
    refs.statusValue.textContent = 'Статус: —'
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
    const result = await guestGetActiveOrder(backendUrl, tableToken, deps, controller.signal)
    if (disposed || orderAbort !== controller) {
      return
    }
    if (controller.signal.aborted) {
      inFlight = false
      orderAbort = null
      renderState()
      return
    }
    const currentToken = getTableToken()
    if (currentToken !== tableToken) {
      inFlight = false
      orderAbort = null
      currentOrder = null
      lastError = null
      refs.statusValue.textContent = 'Статус: —'
      renderState()
      return
    }
    inFlight = false
    orderAbort = null
    if (!result.ok) {
      lastError = result.error
      currentOrder = null
      refs.statusValue.textContent = 'Статус: —'
      renderState()
      return
    }

    currentOrder = result.data.order
    lastError = null
    refs.statusValue.textContent = currentOrder ? `Статус: ${currentOrder.status}` : 'Статус: —'
    renderState()
  }

  const startPolling = () => {
    if (pollTimer) {
      window.clearInterval(pollTimer)
    }
    pollTimer = window.setInterval(() => {
      if (inFlight || orderAbort) {
        return
      }
      void loadOrder()
    }, POLL_INTERVAL_MS)
  }

  const updateTableSnapshot = (snapshot: typeof tableSnapshot) => {
    const prevToken = getTableToken()
    tableSnapshot = snapshot
    updateHint()
    const nextToken = getTableToken()
    if (nextToken && nextToken !== prevToken) {
      void loadOrder()
    }
    if (!nextToken) {
      if (orderAbort) {
        orderAbort.abort()
      orderAbort = null
    }
    inFlight = false
    currentOrder = null
    lastError = null
    refs.statusValue.textContent = 'Статус: —'
    renderState()
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
  startPolling()

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
