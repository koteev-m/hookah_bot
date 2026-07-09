import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueGetFeedback, venueOpenFeedbackFollowUp } from '../shared/api/venueApi'
import type { VenueAccessDto, VenueFeedbackFilter, VenueFeedbackItemDto, VenueFeedbackResponse } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'

type VenueFeedbackOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type FeedbackRefs = {
  status: HTMLParagraphElement
  filterButtons: Record<VenueFeedbackFilter, HTMLButtonElement>
  metrics: HTMLDivElement
  list: HTMLDivElement
  empty: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
}

const TAG_LABELS: Record<string, string> = {
  service: 'Сервис',
  hookah_quality: 'Кальян',
  taste: 'Вкус',
  speed: 'Скорость',
  atmosphere: 'Атмосфера',
  cleanliness: 'Чистота',
  booking: 'Бронь',
  price: 'Цена'
}

type FeedbackCallbacks = {
  onFollowUp: (item: VenueFeedbackItemDto, button: HTMLButtonElement) => void
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('ru-RU')
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

function buildFeedbackDom(root: HTMLDivElement): FeedbackRefs {
  const wrapper = el('div', { className: 'venue-stats' })
  const header = el('section', { className: 'card venue-stats-header' })
  const title = el('h2', { text: 'Отзывы' })
  const subtitle = el('p', {
    className: 'venue-dashboard-subtitle',
    text: 'Внутренние оценки гостей после завершённых визитов.'
  })

  const filters = el('div', { className: 'button-row venue-stats-periods' })
  const filterButtons = {
    all: el('button', { className: 'button-secondary', text: 'Все' }) as HTMLButtonElement,
    low: el('button', { className: 'button-secondary', text: 'Низкие' }) as HTMLButtonElement
  }
  append(filters, filterButtons.all, filterButtons.low)
  append(header, title, subtitle, filters)

  const status = el('p', { className: 'status', text: '' })
  const metrics = el('div', { className: 'venue-stats-grid' }) as HTMLDivElement
  const listCard = el('section', { className: 'card venue-stats-top' })
  const listTitle = el('h3', { text: 'Последние отзывы' })
  const list = el('div', { className: 'venue-stats-top-list' }) as HTMLDivElement
  const empty = el('p', { className: 'venue-empty', text: 'Отзывов пока нет.' })
  append(listCard, listTitle, list, empty)

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, header, status, error, metrics, listCard)
  root.replaceChildren(wrapper)

  return { status, filterButtons, metrics, list, empty, error, errorTitle, errorMessage, errorActions, errorDetails }
}

function renderMetric(label: string, value: string) {
  const card = el('div', { className: 'venue-stats-metric' })
  append(card, el('span', { text: label }), el('strong', { text: value }))
  return card
}

function setActiveFilter(refs: FeedbackRefs, filter: VenueFeedbackFilter) {
  refs.filterButtons.all.dataset.active = String(filter === 'all')
  refs.filterButtons.low.dataset.active = String(filter === 'low')
}

function renderFeedbackItem(item: VenueFeedbackItemDto, callbacks: FeedbackCallbacks) {
  const row = el('article', { className: 'card' })
  const title = el('h3', { text: `${item.rating ?? '—'}/5 · ${item.guestLabel}` })
  const meta = el('p', {
    className: 'venue-order-sub',
    text: [formatDateTime(item.occurredAt), item.serviceDate ? `Дата: ${item.serviceDate}` : null]
      .filter((value): value is string => Boolean(value))
      .join(' · ')
  })
  const tags = (item.tags ?? []).map((tag) => TAG_LABELS[tag] ?? tag).join(' · ')
  const tagText = tags ? el('p', { className: 'venue-order-sub', text: tags }) : null
  const comment = item.comment?.trim() ? el('p', { text: item.comment.trim() }) : null
  append(row, title, meta)
  if (tagText) append(row, tagText)
  if (comment) append(row, comment)
  if (item.rating != null && item.rating >= 1 && item.rating <= 3) {
    const actions = el('div', { className: 'venue-inline-actions' })
    const followUpButton = el('button', {
      className: 'button-secondary button-small',
      text: 'Связаться с гостем'
    }) as HTMLButtonElement
    followUpButton.addEventListener('click', () => callbacks.onFollowUp(item, followUpButton))
    append(actions, followUpButton)
    append(row, actions)
  }
  return row
}

function renderFeedback(refs: FeedbackRefs, data: VenueFeedbackResponse, callbacks: FeedbackCallbacks) {
  refs.metrics.replaceChildren(
    renderMetric('Отзывы', String(data.summary.count)),
    renderMetric('Средняя оценка', data.summary.averageRating == null ? '—' : data.summary.averageRating.toFixed(1)),
    renderMetric('Низкие оценки', String(data.summary.lowCount))
  )
  refs.list.replaceChildren()
  refs.empty.hidden = data.items.length > 0
  data.items.forEach((item) => refs.list.appendChild(renderFeedbackItem(item, callbacks)))
}

export function renderVenueFeedbackScreen(options: VenueFeedbackOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined

  const refs = buildFeedbackDom(root)
  const deps = buildApiDeps(isDebug)
  const canView = access.permissions.includes('FEEDBACK_VIEW')
  let disposed = false
  let loadAbort: AbortController | null = null
  let followUpAbort: AbortController | null = null
  let inFlight = false
  let currentFilter: VenueFeedbackFilter = 'all'

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
      ? presentation.actions.map((action) => (action.label === 'Повторить' ? { ...action, onClick: () => void load() } : action))
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void load() }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const openFollowUp = async (item: VenueFeedbackItemDto, button: HTMLButtonElement) => {
    button.disabled = true
    button.textContent = 'Открываем...'
    refs.status.textContent = ''
    followUpAbort?.abort()
    const controller = new AbortController()
    followUpAbort = controller
    const result = await venueOpenFeedbackFollowUp(
      backendUrl,
      { venueId, feedbackId: item.feedbackId },
      deps,
      controller.signal
    )
    if (disposed || followUpAbort !== controller) return
    followUpAbort = null
    if (!result.ok) {
      button.disabled = false
      button.textContent = 'Связаться с гостем'
      if (result.error.code === REQUEST_ABORTED_CODE) return
      refs.status.textContent = 'Не удалось открыть чат с гостем.'
      showError(result.error)
      return
    }
    refs.status.textContent = result.data.message || 'Чат с гостем открыт.'
    window.location.hash = `#/messages?threadId=${encodeURIComponent(String(result.data.threadId))}`
  }

  const callbacks: FeedbackCallbacks = {
    onFollowUp: (item, button) => void openFollowUp(item, button)
  }

  const load = async () => {
    if (inFlight) return
    if (!canView) {
      refs.status.textContent = 'Раздел отзывов доступен владельцу или менеджеру.'
      refs.metrics.replaceChildren()
      refs.list.replaceChildren()
      refs.empty.hidden = false
      return
    }
    inFlight = true
    refs.error.hidden = true
    refs.status.textContent = 'Загрузка...'
    loadAbort?.abort()
    const controller = new AbortController()
    loadAbort = controller
    const result = await venueGetFeedback(backendUrl, { venueId, filter: currentFilter }, deps, controller.signal)
    if (disposed || loadAbort !== controller) return
    inFlight = false
    loadAbort = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      refs.status.textContent = ''
      showError(result.error)
      return
    }
    setActiveFilter(refs, result.data.filter)
    renderFeedback(refs, result.data, callbacks)
    refs.status.textContent = result.data.filter === 'low' ? 'Фильтр: низкие оценки.' : 'Фильтр: все отзывы.'
  }

  const disposables = [
    on(refs.filterButtons.all, 'click', () => {
      currentFilter = 'all'
      setActiveFilter(refs, currentFilter)
      void load()
    }),
    on(refs.filterButtons.low, 'click', () => {
      currentFilter = 'low'
      setActiveFilter(refs, currentFilter)
      void load()
    })
  ]

  setActiveFilter(refs, currentFilter)
  void load()

  return () => {
    disposed = true
    loadAbort?.abort()
    loadAbort = null
    followUpAbort?.abort()
    followUpAbort = null
    disposables.forEach((dispose) => dispose())
  }
}
