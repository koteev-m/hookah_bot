import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueGetStats } from '../shared/api/venueApi'
import type { VenueAccessDto, VenueStatsPeriod, VenueStatsResponse } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'

type VenueStatsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type PeriodConfig = {
  code: VenueStatsPeriod
  label: string
}

type StatsRefs = {
  status: HTMLParagraphElement
  refreshButton: HTMLButtonElement
  periodButtons: Record<VenueStatsPeriod, HTMLButtonElement>
  metrics: HTMLDivElement
  topList: HTMLDivElement
  topEmpty: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
}

const PERIODS: PeriodConfig[] = [
  { code: 'today', label: 'Сегодня' },
  { code: '7d', label: '7 дней' },
  { code: '30d', label: '30 дней' }
]

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

function buildStatsDom(root: HTMLDivElement): StatsRefs {
  const wrapper = el('div', { className: 'venue-stats' })
  const header = el('section', { className: 'card venue-stats-header' })
  const title = el('h2', { text: 'Статистика' })
  const subtitle = el('p', {
    className: 'venue-dashboard-subtitle',
    text: 'Сводка по заказам заведения. Данные доступны только владельцу и менеджеру.'
  })

  const periodActions = el('div', { className: 'button-row venue-stats-periods' })
  const periodButtons = {} as Record<VenueStatsPeriod, HTMLButtonElement>
  PERIODS.forEach((period) => {
    const button = el('button', { className: 'button-secondary', text: period.label }) as HTMLButtonElement
    periodButtons[period.code] = button
    periodActions.appendChild(button)
  })

  const refreshButton = el('button', { className: 'button-secondary', text: 'Обновить' }) as HTMLButtonElement
  append(header, title, subtitle, periodActions, refreshButton)

  const status = el('p', { className: 'status', text: '' })
  const metrics = el('div', { className: 'venue-stats-grid' }) as HTMLDivElement
  const topCard = el('section', { className: 'card venue-stats-top' })
  const topTitle = el('h3', { text: 'Топ позиций' })
  const topList = el('div', { className: 'venue-stats-top-list' }) as HTMLDivElement
  const topEmpty = el('p', { className: 'venue-empty', text: 'За выбранный период позиций нет.' })
  append(topCard, topTitle, topList, topEmpty)

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, header, status, error, metrics, topCard)
  root.replaceChildren(wrapper)

  return {
    status,
    refreshButton,
    periodButtons,
    metrics,
    topList,
    topEmpty,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
}

function setActivePeriod(refs: StatsRefs, period: VenueStatsPeriod) {
  PERIODS.forEach((item) => {
    refs.periodButtons[item.code].dataset.active = String(item.code === period)
  })
}

function renderMetric(label: string, value: string) {
  const card = el('div', { className: 'venue-stats-metric' })
  append(card, el('span', { text: label }), el('strong', { text: value }))
  return card
}

function renderStats(refs: StatsRefs, stats: VenueStatsResponse) {
  refs.metrics.replaceChildren(
    renderMetric('Заказы', String(stats.ordersCount)),
    renderMetric('Выручка', formatPrice(stats.revenueMinor, stats.currency)),
    renderMetric('Средний чек', formatPrice(stats.averageCheckMinor, stats.currency)),
    renderMetric('Скидки', formatPrice(stats.discountMinor, stats.currency)),
    renderMetric('Отмены/исключения', String(stats.cancelledItemsCount))
  )

  refs.topList.replaceChildren()
  refs.topEmpty.hidden = stats.topItems.length > 0
  stats.topItems.forEach((item, index) => {
    const row = el('div', { className: 'venue-stats-top-row' })
    append(row, el('strong', { text: `${index + 1}. ${item.itemName}` }), el('span', { text: `×${item.qty}` }))
    refs.topList.appendChild(row)
  })
}

export function renderVenueStatsScreen(options: VenueStatsOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined

  const refs = buildStatsDom(root)
  const deps = buildApiDeps(isDebug)
  const canView = access.role === 'OWNER' || access.role === 'MANAGER'

  let disposed = false
  let loadAbort: AbortController | null = null
  let inFlight = false
  let currentPeriod: VenueStatsPeriod = 'today'

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
    if (!canView) {
      setStatus('Раздел статистики доступен владельцу или менеджеру.')
      refs.metrics.replaceChildren()
      refs.topList.replaceChildren()
      refs.topEmpty.hidden = false
      return
    }
    inFlight = true
    hideError()
    setStatus('Загрузка...')
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const result = await venueGetStats(backendUrl, { venueId, period: currentPeriod }, deps, controller.signal)
    if (disposed || loadAbort !== controller) return
    inFlight = false
    loadAbort = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) {
        return
      }
      showError(result.error)
      setStatus('')
      return
    }
    setActivePeriod(refs, result.data.period)
    renderStats(refs, result.data)
    setStatus(`Период: ${result.data.periodTitle}`)
  }

  const disposables = PERIODS.map((period) =>
    on(refs.periodButtons[period.code], 'click', () => {
      if (currentPeriod === period.code) return
      currentPeriod = period.code
      setActivePeriod(refs, currentPeriod)
      void load()
    })
  )
  disposables.push(on(refs.refreshButton, 'click', () => void load()))

  setActivePeriod(refs, currentPeriod)
  void load()

  return () => {
    disposed = true
    loadAbort?.abort()
    loadAbort = null
    disposables.forEach((dispose) => dispose())
  }
}
