import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueGetOrdersQueue } from '../shared/api/venueApi'
import type { OrderQueueItemDto, VenueAccessDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'

const POLL_INTERVAL_MS = 12000
const ALL_STATUSES = ['new', 'accepted', 'cooking', 'delivering', 'delivered'] as const

type OrdersScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
  onOpenOrder: (orderId: number) => void
}

type OrdersRefs = {
  status: HTMLParagraphElement
  filter: HTMLSelectElement
  refreshButton: HTMLButtonElement
  list: HTMLDivElement
  empty: HTMLParagraphElement
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

function buildOrdersDom(root: HTMLDivElement): OrdersRefs {
  const wrapper = el('div', { className: 'venue-orders' })
  const header = el('div', { className: 'card venue-orders-header' })
  const title = el('h2', { text: 'Очередь заказов' })
  const controls = el('div', { className: 'venue-orders-controls' })
  const filterLabel = el('label', { className: 'field-label', text: 'Статус' })
  const filter = document.createElement('select')
  filter.className = 'venue-select'
  filter.appendChild(new Option('Все', 'all'))
  filter.appendChild(new Option('Новые', 'new'))
  filter.appendChild(new Option('Принятые', 'accepted'))
  filter.appendChild(new Option('Готовятся', 'cooking'))
  filter.appendChild(new Option('Доставляются', 'delivering'))
  filter.appendChild(new Option('Доставлены', 'delivered'))
  const refreshButton = el('button', { className: 'button-secondary', text: 'Обновить' }) as HTMLButtonElement
  append(controls, filterLabel, filter, refreshButton)
  append(header, title, controls)

  const status = el('p', { className: 'status', text: '' })
  const list = el('div', { className: 'venue-orders-list' })
  const empty = el('p', { className: 'venue-empty', text: 'Очередь пуста.' })
  empty.hidden = true

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, header, status, error, list, empty)
  root.replaceChildren(wrapper)

  return {
    status,
    filter,
    refreshButton,
    list,
    empty,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
}

function sortByCreatedAt(items: OrderQueueItemDto[]) {
  return [...items].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
}

function renderOrderRow(item: OrderQueueItemDto, onOpenOrder: (orderId: number) => void) {
  const row = el('div', { className: 'venue-order-row' })
  const meta = el('div', { className: 'venue-order-meta' })
  const title = el('strong', { text: `Order #${item.orderId} · Batch ${item.batchId}` })
  const table = el('p', { className: 'venue-order-sub', text: `${item.tableLabel || item.tableNumber}` })
  const details = el('p', { className: 'venue-order-sub', text: `Позиций: ${item.itemsCount} · ${item.status}` })
  const comment = el('p', { className: 'venue-order-sub', text: item.comment ? `Комментарий: ${item.comment}` : 'Комментарий: —' })
  append(meta, title, table, details, comment)

  const actions = el('div', { className: 'venue-order-actions' })
  const openButton = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
  openButton.addEventListener('click', () => onOpenOrder(item.orderId))
  append(actions, openButton)

  append(row, meta, actions)
  return row
}

export function renderVenueOrdersScreen(options: OrdersScreenOptions) {
  const { root, backendUrl, isDebug, venueId, access, onOpenOrder } = options
  if (!root) return () => undefined
  const refs = buildOrdersDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let inFlight = false
  let pollTimer: number | null = null
  let loadSeq = 0

  const canView = access.permissions.includes('ORDER_QUEUE_VIEW')

  const setStatus = (text: string) => {
    refs.status.textContent = text
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

  const renderOrders = (items: OrderQueueItemDto[]) => {
    refs.list.replaceChildren()
    if (!items.length) {
      refs.empty.hidden = false
      return
    }
    refs.empty.hidden = true
    items.forEach((item) => {
      refs.list.appendChild(renderOrderRow(item, onOpenOrder))
    })
  }

  const load = async () => {
    if (!canView) {
      setStatus('Недостаточно прав для просмотра очереди.')
      refs.list.replaceChildren()
      refs.empty.hidden = false
      return
    }
    if (inFlight) return
    inFlight = true
    hideError()
    setStatus('Загрузка...')
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    const selectedStatus = refs.filter.value
    let results: OrderQueueItemDto[] = []

    if (selectedStatus === 'all') {
      const promises = ALL_STATUSES.map((status) =>
        venueGetOrdersQueue(backendUrl, { venueId, status, limit: 50 }, deps, controller.signal)
      )
      const responses = await Promise.all(promises)
      if (disposed || loadSeq !== seq) return
      inFlight = false
      loadAbort = null
      const aborted = responses.find((response) => !response.ok && response.error.code === REQUEST_ABORTED_CODE)
      if (aborted) return
      const failed = responses.find((response) => !response.ok)
      if (failed && !failed.ok) {
        showError(failed.error)
        setStatus('')
        return
      }
      responses.forEach((response) => {
        if (response.ok) {
          results = results.concat(response.data.items)
        }
      })
      results = sortByCreatedAt(results)
    } else {
      const response = await venueGetOrdersQueue(
        backendUrl,
        { venueId, status: selectedStatus, limit: 50 },
        deps,
        controller.signal
      )
      if (disposed || loadSeq !== seq) return
      inFlight = false
      loadAbort = null
      if (!response.ok && response.error.code === REQUEST_ABORTED_CODE) return
      if (!response.ok) {
        showError(response.error)
        setStatus('')
        return
      }
      results = sortByCreatedAt(response.data.items)
    }

    renderOrders(results)
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const startPolling = () => {
    if (pollTimer) {
      window.clearInterval(pollTimer)
    }
    pollTimer = window.setInterval(() => {
      void load()
    }, POLL_INTERVAL_MS)
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.refreshButton, 'click', () => void load()))
  disposables.push(on(refs.filter, 'change', () => void load()))

  void load()
  startPolling()

  return () => {
    disposed = true
    if (pollTimer) {
      window.clearInterval(pollTimer)
    }
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
