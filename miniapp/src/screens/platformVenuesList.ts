import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { platformListVenues } from '../shared/api/platformApi'
import type { PlatformVenueSummaryDto } from '../shared/api/platformDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'

export type PlatformVenuesListOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onNavigate: (hash: string) => void
}

type ListRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  statusFilter: HTMLSelectElement
  subscriptionFilter: HTMLSelectElement
  searchInput: HTMLInputElement
  createButton: HTMLButtonElement
  list: HTMLDivElement
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

function buildListDom(root: HTMLDivElement): ListRefs {
  const wrapper = el('div', { className: 'venue-orders' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Заведения' })
  const controls = el('div', { className: 'venue-form-grid' })
  const statusFilter = document.createElement('select')
  statusFilter.className = 'venue-select'
  ;['any', 'DRAFT', 'PUBLISHED', 'HIDDEN', 'PAUSED', 'SUSPENDED', 'ARCHIVED', 'DELETED'].forEach((status) => {
    const label = status === 'any' ? 'Все статусы' : status
    statusFilter.appendChild(new Option(label, status))
  })
  const subscriptionFilter = document.createElement('select')
  subscriptionFilter.className = 'venue-select'
  const subscriptionOptions: Array<[string, string]> = [
    ['any', 'Все подписки'],
    ['trial_active', 'Trial активен'],
    ['paid', 'Paid'],
    ['none', 'Нет подписки']
  ]
  subscriptionOptions.forEach(([value, label]) => {
    subscriptionFilter.appendChild(new Option(label, value))
  })

  const searchInput = document.createElement('input')
  searchInput.className = 'venue-input'
  searchInput.placeholder = 'Поиск по названию'

  const createButton = el('button', { text: 'Создать заведение' }) as HTMLButtonElement

  append(controls, statusFilter, subscriptionFilter, searchInput, createButton)
  append(header, title, controls)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const list = el('div', { className: 'venue-orders-list' })

  append(wrapper, header, status, error, list)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    statusFilter,
    subscriptionFilter,
    searchInput,
    createButton,
    list
  }
}

function formatSubscriptionSummary(summary: PlatformVenueSummaryDto['subscriptionSummary']) {
  if (!summary) return 'Подписка: неизвестно'
  if (summary.isPaid) return 'Подписка: оплачена'
  if (summary.trialEndDate) return `Trial до ${summary.trialEndDate}`
  if (summary.paidStartDate) return `Paid c ${summary.paidStartDate}`
  return 'Подписка: нет'
}

function renderVenueRow(venue: PlatformVenueSummaryDto, onOpen: (id: number) => void) {
  const row = el('div', { className: 'venue-order-row' })
  const meta = el('div', { className: 'venue-order-meta' })
  append(
    meta,
    el('strong', { text: venue.name }),
    el('p', { className: 'venue-order-sub', text: `#${venue.id} · ${venue.status}` }),
    el('p', { className: 'venue-order-sub', text: `Владельцев: ${venue.ownersCount}` }),
    el('p', { className: 'venue-order-sub', text: formatSubscriptionSummary(venue.subscriptionSummary) })
  )

  const actions = el('div', { className: 'venue-order-actions' })
  const openButton = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
  openButton.addEventListener('click', () => onOpen(venue.id))
  append(actions, openButton)

  append(row, meta, actions)
  return row
}

export function renderPlatformVenuesListScreen(options: PlatformVenuesListOptions) {
  const { root, backendUrl, isDebug, onNavigate } = options
  if (!root) return () => undefined
  const refs = buildListDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let loadSeq = 0
  let searchDebounce: ReturnType<typeof setTimeout> | null = null

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
          action.label === 'Повторить' ? { ...action, onClick: () => void loadVenues() } : action
        )
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void loadVenues() }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const renderList = (venues: PlatformVenueSummaryDto[]) => {
    refs.list.replaceChildren()
    if (!venues.length) {
      refs.list.appendChild(el('p', { className: 'venue-empty', text: 'Заведения не найдены.' }))
      return
    }
    venues.forEach((venue) => {
      refs.list.appendChild(renderVenueRow(venue, (id) => onNavigate(`#/venue/${id}`)))
    })
  }

  const loadVenues = async () => {
    loadAbort?.abort()
    loadAbort = new AbortController()
    const seq = ++loadSeq
    hideError()
    setStatus('Загрузка...')
    const result = await platformListVenues(
      backendUrl,
      {
        status: refs.statusFilter.value === 'any' ? null : refs.statusFilter.value,
        subscription: refs.subscriptionFilter.value === 'any' ? null : refs.subscriptionFilter.value,
        q: refs.searchInput.value.trim() || null,
        limit: 50,
        offset: 0
      },
      deps,
      loadAbort.signal
    )
    if (disposed || seq !== loadSeq) return
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      setStatus('')
      showError(result.error)
      return
    }
    setStatus(`Найдено: ${result.data.venues.length}`)
    renderList(result.data.venues)
  }

  const clearSearchDebounce = () => {
    if (searchDebounce) {
      clearTimeout(searchDebounce)
      searchDebounce = null
    }
  }

  const filtersHandler = () => {
    clearSearchDebounce()
    void loadVenues()
  }

  const searchHandler = () => {
    clearSearchDebounce()
    searchDebounce = setTimeout(() => {
      if (disposed) return
      void loadVenues()
    }, 300)
  }

  const disposables = [
    on(refs.statusFilter, 'change', filtersHandler),
    on(refs.subscriptionFilter, 'change', filtersHandler),
    on(refs.searchInput, 'input', searchHandler),
    on(refs.createButton, 'click', () => onNavigate('#/create'))
  ]

  void loadVenues()

  return () => {
    disposed = true
    loadAbort?.abort()
    loadAbort = null
    clearSearchDebounce()
    disposables.forEach((dispose) => dispose())
  }
}
