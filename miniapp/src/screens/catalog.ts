import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestAddFavoriteVenue, guestGetCatalog, guestRemoveFavoriteVenue } from '../shared/api/guestApi'
import type { CatalogVenueDto, VenueTodayScheduleDto } from '../shared/api/guestDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'

type CatalogScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onOpenVenue: (venueId: number) => void
  onBookVenue?: (venueId: number) => void
  onAskVenue?: (venueId: number) => void
}

type CatalogRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorDetails: HTMLDivElement
  errorActions: HTMLDivElement
  list: HTMLUListElement
  searchInput: HTMLInputElement
  citySelect: HTMLSelectElement
  resetButton: HTMLButtonElement
  retryButton: HTMLButtonElement
}

type CatalogFilters = {
  q: string
  city: string
}

const CATALOG_SEARCH_DEBOUNCE_MS = 300

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

function buildCatalogDom(root: HTMLDivElement): CatalogRefs {
  const wrapper = el('div', { className: 'catalog-screen' })
  const controls = el('div', { className: 'catalog-controls' })
  const searchLabel = el('label', { text: 'Поиск по названию, городу или адресу', className: 'field-label' })
  searchLabel.htmlFor = 'catalog-search'
  const searchInput = el('input', { id: 'catalog-search' }) as HTMLInputElement
  searchInput.type = 'search'
  searchInput.maxLength = 100
  searchInput.placeholder = 'Название, город или адрес'
  searchInput.disabled = true

  const cityLabel = el('label', { text: 'Город', className: 'field-label' })
  cityLabel.htmlFor = 'catalog-city'
  const citySelect = el('select', { id: 'catalog-city' }) as HTMLSelectElement
  citySelect.appendChild(new Option('Все города', ''))
  citySelect.disabled = true

  const controlActions = el('div', { className: 'catalog-control-actions' })
  const resetButton = el('button', {
    className: 'button-small button-secondary',
    text: 'Сбросить поиск и фильтр'
  }) as HTMLButtonElement
  resetButton.disabled = true
  const retryButton = el('button', { className: 'button-small', text: 'Обновить' }) as HTMLButtonElement
  append(controlActions, resetButton, retryButton)
  append(controls, searchLabel, searchInput, cityLabel, citySelect, controlActions)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const list = el('ul', { className: 'catalog-list' })

  append(wrapper, controls, status, error, list)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorDetails,
    errorActions,
    list,
    searchInput,
    citySelect,
    resetButton,
    retryButton
  }
}

function buildCityOptions(venues: CatalogVenueDto[]): string[] {
  const cities = new Map<string, string>()
  venues.forEach((venue) => {
    const city = venue.city?.trim()
    if (!city) return
    const key = city.toLocaleLowerCase('ru-RU')
    if (!cities.has(key)) {
      cities.set(key, city)
    }
  })
  const collator = new Intl.Collator('ru-RU', { sensitivity: 'base' })
  return Array.from(cities.values()).sort((left, right) => collator.compare(left, right))
}

function formatTodaySchedule(schedule: VenueTodayScheduleDto | null | undefined): string {
  if (!schedule) return ''
  if (schedule.isConfigured === false) return schedule.statusLabel || 'График не указан'
  const timeLabel = schedule.timeLabel?.trim()
  return timeLabel ? `${schedule.statusLabel} · ${timeLabel}` : schedule.statusLabel
}

function renderCatalogList(
  venues: CatalogVenueDto[],
  onOpenVenue: (venueId: number) => void,
  onBookVenue: ((venueId: number) => void) | undefined,
  onAskVenue: ((venueId: number) => void) | undefined,
  pendingFavoriteVenueIds: ReadonlySet<number>,
  onToggleFavorite: (venue: CatalogVenueDto) => void,
  refs: CatalogRefs,
  emptyMessage: string
) {
  refs.list.replaceChildren()
  if (!venues.length) {
    const item = document.createElement('li')
    item.textContent = emptyMessage
    refs.list.appendChild(item)
    return
  }
  venues.forEach((venue) => {
    const item = document.createElement('li')
    item.className = 'catalog-item'

    const info = document.createElement('div')
    const name = document.createElement('strong')
    name.textContent = venue.name
    const city = document.createElement('div')
    city.className = 'catalog-meta'
    city.textContent = venue.city ?? '—'
    const address = document.createElement('div')
    address.className = 'catalog-meta'
    address.textContent = venue.address ?? ''

    info.appendChild(name)
    info.appendChild(city)
    if (venue.address) {
      info.appendChild(address)
    }
    const scheduleText = formatTodaySchedule(venue.todaySchedule)
    if (scheduleText) {
      info.appendChild(el('div', { className: 'catalog-meta', text: scheduleText }))
    }

    const actions = document.createElement('div')
    actions.className = 'order-actions'
    const favoriteButton = document.createElement('button')
    favoriteButton.className = 'button-small favorite-toggle'
    favoriteButton.dataset.favorite = String(venue.isFavorite)
    favoriteButton.setAttribute('aria-pressed', String(venue.isFavorite))
    favoriteButton.textContent = venue.isFavorite ? 'В избранном' : 'В избранное'
    favoriteButton.disabled = pendingFavoriteVenueIds.has(venue.id)
    favoriteButton.addEventListener('click', () => onToggleFavorite(venue))
    actions.appendChild(favoriteButton)
    const button = document.createElement('button')
    button.className = 'button-small'
    button.textContent = 'Открыть карточку'
    button.addEventListener('click', () => onOpenVenue(venue.id))
    actions.appendChild(button)
    if (onAskVenue) {
      const askButton = document.createElement('button')
      askButton.className = 'button-small button-secondary'
      askButton.textContent = 'Задать вопрос'
      askButton.addEventListener('click', () => onAskVenue(venue.id))
      actions.appendChild(askButton)
    }
    if (onBookVenue) {
      const bookingButton = document.createElement('button')
      bookingButton.className = 'button-small button-secondary'
      bookingButton.textContent = 'Забронировать'
      bookingButton.addEventListener('click', () => onBookVenue(venue.id))
      actions.appendChild(bookingButton)
    }

    item.appendChild(info)
    item.appendChild(actions)
    refs.list.appendChild(item)
  })
}

export function renderCatalogScreen(options: CatalogScreenOptions) {
  const { root, backendUrl, isDebug, onOpenVenue, onBookVenue, onAskVenue } = options
  if (!root) return () => undefined

  const refs = buildCatalogDom(root)
  let disposed = false
  let catalogAbort: AbortController | null = null
  let loadSeq = 0
  let searchDebounce: ReturnType<typeof setTimeout> | null = null
  let cityOptionsReady = false
  let venues: CatalogVenueDto[] = []
  const pendingFavoriteVenueIds = new Set<number>()
  const favoriteOverrides = new Map<number, boolean>()
  const favoriteMutationControllers = new Set<AbortController>()
  const disposables: Array<() => void> = []

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
  }

  const currentFilters = (): CatalogFilters => ({
    q: refs.searchInput.value.trim(),
    city: refs.citySelect.value.trim()
  })

  const hasActiveFilters = (filters: CatalogFilters = currentFilters()) => Boolean(filters.q || filters.city)

  const updateControlState = () => {
    refs.searchInput.disabled = !cityOptionsReady
    refs.citySelect.disabled = !cityOptionsReady
    refs.resetButton.disabled = !cityOptionsReady || !hasActiveFilters()
  }

  const clearSearchDebounce = () => {
    if (searchDebounce !== null) {
      clearTimeout(searchDebounce)
      searchDebounce = null
    }
  }

  const invalidateCatalogRequest = () => {
    loadSeq += 1
    catalogAbort?.abort()
    catalogAbort = null
  }

  const renderCityOptions = (cities: string[]) => {
    refs.citySelect.replaceChildren(new Option('Все города', ''))
    cities.forEach((city) => refs.citySelect.appendChild(new Option(city, city)))
    refs.citySelect.value = ''
  }

  const withFavoriteOverrides = (items: CatalogVenueDto[]): CatalogVenueDto[] =>
    items.map((venue) => {
      const override = favoriteOverrides.get(venue.id)
      return override === undefined ? venue : { ...venue, isFavorite: override }
    })

  const renderCurrentResults = () => {
    const emptyMessage = hasActiveFilters()
      ? 'По вашему запросу ничего не найдено'
      : 'Пока нет доступных заведений.'
    renderCatalogList(
      withFavoriteOverrides(venues),
      onOpenVenue,
      onBookVenue,
      onAskVenue,
      pendingFavoriteVenueIds,
      (venue) => void toggleFavorite(venue),
      refs,
      emptyMessage
    )
    updateControlState()
  }

  const showError = (error: ApiErrorInfo) => {
    const normalizedCode = normalizeErrorCode(error)
    if (normalizedCode === 'UNAUTHORIZED' || normalizedCode === 'INITDATA_INVALID') {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug })
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    refs.error.dataset.severity = presentation.severity
    const actions = presentation.actions.map((action) => {
      if (action.label === 'Повторить') {
        return { ...action, onClick: () => void loadCatalog({ refreshCityOptions: !hasActiveFilters() }) }
      }
      return action
    })
    if (!actions.length) {
      actions.push({ label: 'Повторить', onClick: () => void loadCatalog({ refreshCityOptions: !hasActiveFilters() }) })
    }
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, {
      isDebug,
      extraNotes: presentation.debugLine ? [presentation.debugLine] : undefined
    })
    refs.error.hidden = false
  }

  async function toggleFavorite(venue: CatalogVenueDto) {
    if (disposed || pendingFavoriteVenueIds.has(venue.id)) return
    const hadOverride = favoriteOverrides.has(venue.id)
    const previousOverride = favoriteOverrides.get(venue.id)
    const previousState = previousOverride ?? venue.isFavorite
    const desiredState = !previousState
    favoriteOverrides.set(venue.id, desiredState)
    pendingFavoriteVenueIds.add(venue.id)
    setStatus('')
    renderCurrentResults()

    const controller = new AbortController()
    favoriteMutationControllers.add(controller)
    const result = desiredState
      ? await guestAddFavoriteVenue(backendUrl, venue.id, buildApiDeps(isDebug), controller.signal)
      : await guestRemoveFavoriteVenue(backendUrl, venue.id, buildApiDeps(isDebug), controller.signal)
    favoriteMutationControllers.delete(controller)
    if (disposed || (!result.ok && result.error.code === REQUEST_ABORTED_CODE)) {
      return
    }
    pendingFavoriteVenueIds.delete(venue.id)
    if (!result.ok) {
      if (hadOverride && previousOverride !== undefined) {
        favoriteOverrides.set(venue.id, previousOverride)
      } else {
        favoriteOverrides.delete(venue.id)
      }
      setStatus('Не удалось изменить избранное.')
    }
    renderCurrentResults()
  }

  async function loadCatalog(options: { refreshCityOptions?: boolean } = {}) {
    if (disposed) return
    clearSearchDebounce()
    const filters = currentFilters()
    const refreshCityOptions = options.refreshCityOptions === true && !hasActiveFilters(filters)
    catalogAbort?.abort()
    const controller = new AbortController()
    catalogAbort = controller
    const seq = ++loadSeq
    setStatus('Загрузка каталога...')
    hideError()
    refs.list.replaceChildren()

    const result = await guestGetCatalog(backendUrl, buildApiDeps(isDebug), controller.signal, {
      q: filters.q || null,
      city: filters.city || null
    })
    if (disposed || loadSeq !== seq || catalogAbort !== controller) {
      return
    }
    catalogAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) {
      return
    }
    if (!result.ok) {
      setStatus('')
      showError(result.error)
      return
    }

    venues = result.data.venues ?? []
    if (refreshCityOptions) {
      renderCityOptions(buildCityOptions(venues))
      cityOptionsReady = true
    }
    setStatus('')
    renderCurrentResults()
  }

  const handleSearchInput = () => {
    clearSearchDebounce()
    invalidateCatalogRequest()
    setStatus('')
    hideError()
    updateControlState()
    searchDebounce = setTimeout(() => {
      searchDebounce = null
      if (disposed) return
      void loadCatalog()
    }, CATALOG_SEARCH_DEBOUNCE_MS)
  }

  const handleCityChange = () => {
    clearSearchDebounce()
    invalidateCatalogRequest()
    updateControlState()
    void loadCatalog()
  }

  const resetFilters = () => {
    if (!hasActiveFilters()) return
    refs.searchInput.value = ''
    refs.citySelect.value = ''
    clearSearchDebounce()
    invalidateCatalogRequest()
    updateControlState()
    void loadCatalog({ refreshCityOptions: true })
  }

  disposables.push(
    on(refs.retryButton, 'click', () => void loadCatalog({ refreshCityOptions: !hasActiveFilters() })),
    on(refs.resetButton, 'click', resetFilters),
    on(refs.searchInput, 'input', handleSearchInput),
    on(refs.citySelect, 'change', handleCityChange)
  )

  void getAccessToken()
  void loadCatalog({ refreshCityOptions: true })

  return () => {
    disposed = true
    loadSeq += 1
    clearSearchDebounce()
    catalogAbort?.abort()
    catalogAbort = null
    favoriteMutationControllers.forEach((controller) => controller.abort())
    disposables.forEach((dispose) => dispose())
  }
}
