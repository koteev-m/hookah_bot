import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestGetVenue } from '../shared/api/guestApi'
import { venueGetOrdersQueue, venueGetStaffChatStatus } from '../shared/api/venueApi'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'

const POLL_INTERVAL_MS = 15000

export type VenueDashboardOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type DashboardRefs = {
  title: HTMLHeadingElement
  subtitle: HTMLParagraphElement
  summaryNew: HTMLSpanElement
  summaryActive: HTMLSpanElement
  summaryChat: HTMLSpanElement
  refreshButton: HTMLButtonElement
  status: HTMLParagraphElement
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

function buildDashboardDom(root: HTMLDivElement): DashboardRefs {
  const wrapper = el('div', { className: 'venue-dashboard' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Dashboard' })
  const subtitle = el('p', { className: 'venue-dashboard-subtitle', text: '' })
  append(header, title, subtitle)

  const summary = el('div', { className: 'venue-summary' })
  const summaryNew = el('span', { text: '—' })
  const summaryActive = el('span', { text: '—' })
  const summaryChat = el('span', { text: '—' })
  const summaryNewRow = el('div', { className: 'venue-summary-row' })
  append(summaryNewRow, el('strong', { text: 'Новые batches:' }), summaryNew)
  const summaryActiveRow = el('div', { className: 'venue-summary-row' })
  append(summaryActiveRow, el('strong', { text: 'Активные заказы:' }), summaryActive)
  const summaryChatRow = el('div', { className: 'venue-summary-row' })
  append(summaryChatRow, el('strong', { text: 'Чат персонала:' }), summaryChat)
  append(summary, summaryNewRow, summaryActiveRow, summaryChatRow)

  const actions = el('div', { className: 'button-row' })
  const refreshButton = el('button', { className: 'button-secondary', text: 'Обновить' }) as HTMLButtonElement
  append(actions, refreshButton)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, header, summary, actions, status, error)
  root.replaceChildren(wrapper)

  return {
    title,
    subtitle,
    summaryNew,
    summaryActive,
    summaryChat,
    refreshButton,
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
}

function toStatusLabel(status: string) {
  return status === 'linked' ? 'привязан' : status === 'unlinked' ? 'не привязан' : status
}

export function renderVenueDashboardScreen(options: VenueDashboardOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined

  const refs = buildDashboardDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let pollTimer: number | null = null
  let inFlight = false
  let loadSeq = 0

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

  const load = async () => {
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
    const baseVenuePromise = guestGetVenue(backendUrl, venueId, deps, controller.signal)
    const newQueuePromise = venueGetOrdersQueue(
      backendUrl,
      { venueId, status: 'new', limit: 50 },
      deps,
      controller.signal
    )
    const activeStatuses = ['accepted', 'cooking', 'delivering']
    const activePromises = activeStatuses.map((status) =>
      venueGetOrdersQueue(backendUrl, { venueId, status, limit: 50 }, deps, controller.signal)
    )
    const chatPromise = venueGetStaffChatStatus(backendUrl, venueId, deps, controller.signal)

    const responses = await Promise.all([baseVenuePromise, newQueuePromise, ...activePromises, chatPromise])

    if (disposed || loadSeq !== seq) return
    inFlight = false
    loadAbort = null

    const venueResult = responses[0] as Awaited<typeof baseVenuePromise>
    const newQueueResult = responses[1] as Awaited<typeof newQueuePromise>
    const activeResults = responses.slice(2, 2 + activeStatuses.length) as Awaited<typeof newQueuePromise>[]
    const chatResult = responses[2 + activeStatuses.length] as Awaited<typeof chatPromise>

    if (!venueResult.ok && venueResult.error.code === REQUEST_ABORTED_CODE) {
      return
    }
    if (!newQueueResult.ok && newQueueResult.error.code === REQUEST_ABORTED_CODE) {
      return
    }
    const abortedActive = activeResults.find((result) => !result.ok && result.error.code === REQUEST_ABORTED_CODE)
    if (abortedActive) {
      return
    }
    if (!chatResult.ok && chatResult.error.code === REQUEST_ABORTED_CODE) {
      return
    }

    if (!venueResult.ok) {
      showError(venueResult.error)
      setStatus('')
      return
    }
    if (!newQueueResult.ok) {
      showError(newQueueResult.error)
      setStatus('')
      return
    }
    const badActive = activeResults.find((result) => !result.ok)
    if (badActive && !badActive.ok) {
      showError(badActive.error)
      setStatus('')
      return
    }
    if (!chatResult.ok) {
      showError(chatResult.error)
      setStatus('')
      return
    }

    const venue = venueResult.data.venue
    refs.title.textContent = venue.name
    refs.subtitle.textContent = `Venue #${venue.id} · Роль ${access.role}`

    refs.summaryNew.textContent = String(newQueueResult.data.items.length)
    const activeOrders = new Set<number>()
    activeResults.forEach((result) => {
      if (result.ok) {
        result.data.items.forEach((item) => activeOrders.add(item.orderId))
      }
    })
    refs.summaryActive.textContent = String(activeOrders.size)
    refs.summaryChat.textContent = chatResult.data.isLinked ? toStatusLabel('linked') : toStatusLabel('unlinked')

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
