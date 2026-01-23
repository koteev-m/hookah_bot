import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestGetCatalog } from '../shared/api/guestApi'
import type { CatalogVenueDto } from '../shared/api/guestDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { renderErrorDetails } from '../shared/ui/errorDetails'

type CatalogScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onOpenVenue: (venueId: number) => void
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
  retryButton: HTMLButtonElement
}

type ErrorAction = {
  label: string
  onClick: () => void
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function renderErrorActions(container: HTMLElement, actions: ErrorAction[]) {
  container.replaceChildren()
  actions.forEach((action) => {
    const button = document.createElement('button')
    button.textContent = action.label
    button.addEventListener('click', action.onClick)
    container.appendChild(button)
  })
}

function buildCatalogDom(root: HTMLDivElement): CatalogRefs {
  const wrapper = el('div', { className: 'catalog-screen' })
  const controls = el('div', { className: 'catalog-controls' })
  const searchLabel = el('label', { text: 'Поиск по названию или городу', className: 'field-label' })
  searchLabel.htmlFor = 'catalog-search'
  const searchInput = el('input', { id: 'catalog-search' }) as HTMLInputElement
  searchInput.type = 'search'
  searchInput.placeholder = 'Начните вводить название или город'
  const retryButton = el('button', { className: 'button-small', text: 'Обновить' })
  append(controls, searchLabel, searchInput, retryButton)

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
    retryButton
  }
}

function renderCatalogList(
  venues: CatalogVenueDto[],
  onOpenVenue: (venueId: number) => void,
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

    const button = document.createElement('button')
    button.textContent = 'Открыть меню'
    button.addEventListener('click', () => onOpenVenue(venue.id))

    item.appendChild(info)
    item.appendChild(button)
    refs.list.appendChild(item)
  })
}

export function renderCatalogScreen(options: CatalogScreenOptions) {
  const { root, backendUrl, isDebug, onOpenVenue } = options
  if (!root) return () => undefined

  const refs = buildCatalogDom(root)
  let disposed = false
  let catalogAbort: AbortController | null = null
  let venues: CatalogVenueDto[] = []
  const disposables: Array<() => void> = []

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
  }

  const showError = (error: ApiErrorInfo) => {
    const code = normalizeErrorCode(error)
    const actions: ErrorAction[] = []

    if (code === ApiErrorCodes.INITDATA_INVALID || code === ApiErrorCodes.UNAUTHORIZED) {
      refs.errorTitle.textContent = 'Перезапустите Mini App'
      refs.errorMessage.textContent = 'Сессия недействительна. Перезапустите Mini App в Telegram.'
      actions.push({ label: 'Перезапустить', onClick: () => window.location.reload() })
      clearSession()
    } else if (code === ApiErrorCodes.NETWORK_ERROR) {
      refs.errorTitle.textContent = 'Нет соединения'
      refs.errorMessage.textContent = 'Проверьте подключение к интернету и повторите попытку.'
      actions.push({ label: 'Повторить', onClick: () => void loadCatalog() })
    } else if (code === ApiErrorCodes.DATABASE_UNAVAILABLE) {
      refs.errorTitle.textContent = 'Каталог временно недоступен'
      refs.errorMessage.textContent = 'Попробуйте ещё раз чуть позже.'
      actions.push({ label: 'Повторить', onClick: () => void loadCatalog() })
    } else {
      refs.errorTitle.textContent = 'Не удалось загрузить каталог'
      refs.errorMessage.textContent = 'Попробуйте обновить страницу или повторить запрос позже.'
      actions.push({ label: 'Повторить', onClick: () => void loadCatalog() })
    }

    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const applyFilter = () => {
    const query = refs.searchInput.value.trim().toLowerCase()
    const filtered = query
      ? venues.filter((venue) => {
          const haystack = `${venue.name} ${venue.city ?? ''}`.toLowerCase()
          return haystack.includes(query)
        })
      : venues
    const emptyMessage = venues.length
      ? 'Ничего не найдено по заданному фильтру.'
      : 'Пока нет доступных заведений.'
    renderCatalogList(filtered, onOpenVenue, refs, emptyMessage)
  }

  async function loadCatalog() {
    if (disposed) return
    setStatus('Загрузка каталога...')
    hideError()
    refs.list.replaceChildren()
    if (catalogAbort) {
      catalogAbort.abort()
    }
    const controller = new AbortController()
    catalogAbort = controller

    const result = await guestGetCatalog(backendUrl, buildApiDeps(isDebug), controller.signal)
    if (disposed || catalogAbort !== controller) {
      return
    }
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) {
      return
    }
    if (!result.ok) {
      setStatus('')
      showError(result.error)
      return
    }

    venues = result.data.venues ?? []
    setStatus('')
    applyFilter()
  }

  disposables.push(
    on(refs.retryButton, 'click', () => void loadCatalog()),
    on(refs.searchInput, 'input', () => applyFilter())
  )

  void getAccessToken()
  void loadCatalog()

  return () => {
    disposed = true
    catalogAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
