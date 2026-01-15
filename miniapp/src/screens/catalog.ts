import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { requestApi } from '../shared/api/request'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { clearSession, ensureGuestSession } from '../shared/session/guestSession'
import { getTelegramContext } from '../shared/telegram'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { append, el, on } from '../shared/ui/dom'

type CatalogVenue = {
  id: number
  name: string
  city?: string | null
  address?: string | null
}

type CatalogResponse = {
  venues: CatalogVenue[]
}

type VenueResponse = {
  venue: {
    id: number
    name: string
    city?: string | null
    address?: string | null
    status: string
  }
}

type CatalogScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
}

type ErrorAction = {
  label: string
  onClick: () => void
}

type CatalogRuntimeState = {
  backendUrl: string
  isDebug: boolean
  visibilitySuspendedObserved: boolean
  venueNotFoundObserved: boolean
}

type CatalogRefs = {
  catalogStatus: HTMLParagraphElement
  catalogError: HTMLDivElement
  catalogErrorTitle: HTMLHeadingElement
  catalogErrorMessage: HTMLParagraphElement
  catalogErrorActions: HTMLDivElement
  catalogErrorDetails: HTMLDivElement
  catalogList: HTMLUListElement
  venueStatus: HTMLParagraphElement
  venueError: HTMLDivElement
  venueErrorTitle: HTMLHeadingElement
  venueErrorMessage: HTMLParagraphElement
  venueErrorActions: HTMLDivElement
  venueErrorDetails: HTMLDivElement
  venueDetails: HTMLDivElement
  venueInput: HTMLInputElement
  startParam: HTMLSpanElement
  catalogRetryButton: HTMLButtonElement
  venueLoadButton: HTMLButtonElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, ensureGuestSession, clearSession }
}

function logApiError(context: string, error: ApiErrorInfo, isDebug: boolean) {
  if (!isDebug) {
    return
  }
  console.warn(`[${context}] API error`, {
    status: error.status,
    code: error.code,
    requestId: error.requestId,
    message: error.message
  })
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

function getVisibilityNotes(state: CatalogRuntimeState) {
  if (!state.isDebug || !state.visibilitySuspendedObserved) {
    return []
  }
  return ['Visibility mode: explain (423 observed)']
}

function getVenueVisibilityNotes(state: CatalogRuntimeState) {
  const notes = getVisibilityNotes(state)
  if (state.isDebug && state.venueNotFoundObserved) {
    notes.push('404 может означать hide mode или реальный NOT_FOUND')
  }
  return notes
}

function renderCatalogList(
  venues: CatalogVenue[],
  onOpenVenue: (venueId: number) => void,
  refs: CatalogRefs
) {
  refs.catalogList.replaceChildren()
  if (!venues.length) {
    const item = document.createElement('li')
    item.textContent = 'Пока нет доступных заведений.'
    refs.catalogList.appendChild(item)
    return
  }
  venues.forEach((venue) => {
    const item = document.createElement('li')
    item.className = 'catalog-item'
    const info = document.createElement('div')
    const name = document.createElement('strong')
    name.textContent = venue.name
    const lineBreak = document.createElement('br')
    const location = document.createElement('span')
    location.textContent = `${venue.city ?? '—'}${venue.address ? `, ${venue.address}` : ''}`
    info.appendChild(name)
    info.appendChild(lineBreak)
    info.appendChild(location)
    const button = document.createElement('button')
    button.textContent = 'Открыть'
    button.addEventListener('click', () => void onOpenVenue(venue.id))
    item.appendChild(info)
    item.appendChild(button)
    refs.catalogList.appendChild(item)
  })
}

function buildCatalogDom(
  root: HTMLDivElement,
  initDataLength: number,
  startParam: string,
  userId: number | null
): CatalogRefs {
  const main = el('main', { className: 'container' })
  const header = el('header')
  append(header, el('h1', { text: 'Hookah Mini App' }), el('p', { text: 'Каталог кальянных' }))

  const info = el('section', { className: 'info' })
  const initDataLine = el('p')
  append(
    initDataLine,
    el('strong', { text: 'initData length:' }),
    document.createTextNode(` ${initDataLength}`)
  )
  const startParamLine = el('p')
  const startParamSpan = el('span', { id: 'catalog-start-param' })
  append(
    startParamLine,
    el('strong', { text: 'tgWebAppStartParam:' }),
    document.createTextNode(' '),
    startParamSpan
  )
  const userIdLine = el('p')
  append(
    userIdLine,
    el('strong', { text: 'Telegram user id:' }),
    document.createTextNode(` ${userId ?? '—'}`)
  )
  append(info, initDataLine, startParamLine, userIdLine)

  const catalogCard = el('section', { className: 'card' })
  const catalogHeader = el('div', { className: 'card-header' })
  const catalogTitle = el('h2', { text: 'Каталог' })
  const catalogRetryButton = el('button', { id: 'catalog-retry-btn', text: 'Обновить' })
  append(catalogHeader, catalogTitle, catalogRetryButton)
  const catalogStatus = el('p', { id: 'catalog-status', className: 'status', text: 'Загрузка каталога...' })
  const catalogError = el('div', { id: 'catalog-error', className: 'error-card' })
  catalogError.hidden = true
  const catalogErrorTitle = el('h3', { id: 'catalog-error-title' })
  const catalogErrorMessage = el('p', { id: 'catalog-error-message' })
  const catalogErrorActions = el('div', { id: 'catalog-error-actions', className: 'error-actions' })
  const catalogErrorDetails = el('div', { id: 'catalog-error-details' })
  append(
    catalogError,
    catalogErrorTitle,
    catalogErrorMessage,
    catalogErrorActions,
    catalogErrorDetails
  )
  const catalogList = el('ul', { id: 'catalog-list', className: 'catalog-list' })
  append(catalogCard, catalogHeader, catalogStatus, catalogError, catalogList)

  const venueCard = el('section', { className: 'card' })
  const venueHeader = el('div', { className: 'card-header' })
  append(venueHeader, el('h2', { text: 'Заведение по ID' }))
  const venueLabel = el('label')
  venueLabel.htmlFor = 'guest-venue-id'
  venueLabel.textContent = 'ID заведения'
  const venueInput = el('input', { id: 'guest-venue-id' }) as HTMLInputElement
  venueInput.type = 'number'
  venueInput.placeholder = 'Введите ID'
  const venueButtonRow = el('div', { className: 'button-row' })
  const venueLoadButton = el('button', { id: 'venue-load-btn', text: 'Открыть заведение' })
  append(venueButtonRow, venueLoadButton)
  const venueStatus = el('p', { id: 'venue-status', className: 'status', text: 'Введите ID и загрузите карточку.' })
  const venueError = el('div', { id: 'venue-error', className: 'error-card' })
  venueError.hidden = true
  const venueErrorTitle = el('h3', { id: 'venue-error-title' })
  const venueErrorMessage = el('p', { id: 'venue-error-message' })
  const venueErrorActions = el('div', { id: 'venue-error-actions', className: 'error-actions' })
  const venueErrorDetails = el('div', { id: 'venue-error-details' })
  append(venueError, venueErrorTitle, venueErrorMessage, venueErrorActions, venueErrorDetails)
  const venueDetails = el('div', { id: 'venue-details', className: 'venue-details' })
  venueDetails.hidden = true
  append(venueCard, venueHeader, venueLabel, venueInput, venueButtonRow, venueStatus, venueError, venueDetails)

  append(main, header, info, catalogCard, venueCard)
  root.replaceChildren(main)

  startParamSpan.textContent = startParam || '—'

  return {
    catalogStatus,
    catalogError,
    catalogErrorTitle,
    catalogErrorMessage,
    catalogErrorActions,
    catalogErrorDetails,
    catalogList,
    venueStatus,
    venueError,
    venueErrorTitle,
    venueErrorMessage,
    venueErrorActions,
    venueErrorDetails,
    venueDetails,
    venueInput,
    startParam: startParamSpan,
    catalogRetryButton,
    venueLoadButton
  }
}

export function renderCatalogScreen(options: CatalogScreenOptions) {
  const { root, backendUrl, isDebug } = options
  if (!root) return () => undefined
  const state: CatalogRuntimeState = {
    backendUrl,
    isDebug,
    visibilitySuspendedObserved: false,
    venueNotFoundObserved: false
  }
  const { initDataLength, startParam, userId } = getTelegramContext()
  const defaultVenueId = Number(startParam)
  const refs = buildCatalogDom(root, initDataLength, startParam ?? '', userId ?? null)
  refs.venueInput.value = Number.isFinite(defaultVenueId) ? String(defaultVenueId) : ''

  let catalogAbort: AbortController | null = null
  let venueAbort: AbortController | null = null
  let disposed = false
  const disposables: Array<() => void> = []

  const setCatalogStatus = (text: string) => {
    refs.catalogStatus.textContent = text
  }

  const setVenueStatus = (text: string) => {
    refs.venueStatus.textContent = text
  }

  const hideCatalogError = () => {
    refs.catalogError.hidden = true
  }

  const hideVenueError = () => {
    refs.venueError.hidden = true
  }

  const showCatalogError = (error: ApiErrorInfo) => {
    const code = normalizeErrorCode(error)
    const actions: ErrorAction[] = []

    if (code === ApiErrorCodes.DATABASE_UNAVAILABLE) {
      refs.catalogErrorTitle.textContent = 'Каталог временно недоступен'
      refs.catalogErrorMessage.textContent = 'Сервис перегружен или недоступен. Попробуйте ещё раз чуть позже.'
      actions.push({
        label: 'Повторить',
        onClick: () => void loadCatalog()
      })
    } else if (code === ApiErrorCodes.NETWORK_ERROR) {
      refs.catalogErrorTitle.textContent = 'Нет соединения'
      refs.catalogErrorMessage.textContent = 'Нет соединения / ошибка сети. Проверьте подключение и повторите.'
      actions.push({
        label: 'Повторить',
        onClick: () => void loadCatalog()
      })
    } else if (code === ApiErrorCodes.INITDATA_INVALID) {
      refs.catalogErrorTitle.textContent = 'Нужен запуск из Telegram'
      refs.catalogErrorMessage.textContent = 'Откройте мини-приложение из Telegram, чтобы продолжить.'
      actions.push({ label: 'Перезапустить', onClick: () => window.location.reload() })
    } else if (code === ApiErrorCodes.UNAUTHORIZED) {
      refs.catalogErrorTitle.textContent = 'Сессия истекла'
      refs.catalogErrorMessage.textContent = 'Перезапустите мини-приложение или повторите авторизацию.'
      actions.push({ label: 'Перезапустить', onClick: () => window.location.reload() })
      actions.push({
        label: 'Повторить (переавторизоваться)',
        onClick: () => {
          clearSession(state.backendUrl, state.isDebug)
          void loadCatalog()
        }
      })
    } else {
      refs.catalogErrorTitle.textContent = 'Не удалось загрузить каталог'
      refs.catalogErrorMessage.textContent = 'Попробуйте обновить страницу или повторить запрос позже.'
      actions.push({
        label: 'Повторить',
        onClick: () => void loadCatalog()
      })
    }

    renderErrorActions(refs.catalogErrorActions, actions)

    renderErrorDetails(refs.catalogErrorDetails, error, {
      isDebug: state.isDebug,
      extraNotes: getVisibilityNotes(state)
    })
    refs.catalogError.hidden = false
    logApiError('catalog', error, state.isDebug)
  }

  const showVenueError = (error: ApiErrorInfo) => {
    const code = normalizeErrorCode(error)
    const actions: ErrorAction[] = []

    if (code === ApiErrorCodes.SERVICE_SUSPENDED) {
      refs.venueErrorTitle.textContent = 'Заведение временно недоступно'
      refs.venueErrorMessage.textContent = 'Попробуйте вернуться позже или выбрать другое заведение.'
      actions.push({
        label: 'Вернуться в каталог',
        onClick: () => void loadCatalog()
      })
    } else if (code === ApiErrorCodes.NOT_FOUND) {
      refs.venueErrorTitle.textContent = 'Заведение не найдено'
      refs.venueErrorMessage.textContent = 'Проверьте ссылку или выберите другое заведение в каталоге.'
      actions.push({
        label: 'Вернуться в каталог',
        onClick: () => void loadCatalog()
      })
    } else if (code === ApiErrorCodes.INVALID_INPUT) {
      refs.venueErrorTitle.textContent = 'Некорректная ссылка'
      refs.venueErrorMessage.textContent = 'Похоже, ID заведения указан неверно.'
      actions.push({
        label: 'Вернуться в каталог',
        onClick: () => void loadCatalog()
      })
    } else if (code === ApiErrorCodes.DATABASE_UNAVAILABLE) {
      refs.venueErrorTitle.textContent = 'Сервис временно недоступен'
      refs.venueErrorMessage.textContent = 'Попробуйте повторить запрос чуть позже.'
      actions.push({
        label: 'Повторить',
        onClick: () => {
          const venueId = Number(refs.venueInput.value)
          if (venueId && !Number.isNaN(venueId)) {
            void loadVenue(venueId)
          }
        }
      })
    } else if (code === ApiErrorCodes.NETWORK_ERROR) {
      refs.venueErrorTitle.textContent = 'Нет соединения'
      refs.venueErrorMessage.textContent = 'Нет соединения / ошибка сети. Проверьте подключение и повторите.'
      actions.push({
        label: 'Повторить',
        onClick: () => {
          const venueId = Number(refs.venueInput.value)
          if (venueId && !Number.isNaN(venueId)) {
            void loadVenue(venueId)
          }
        }
      })
    } else if (code === ApiErrorCodes.INITDATA_INVALID) {
      refs.venueErrorTitle.textContent = 'Нужен запуск из Telegram'
      refs.venueErrorMessage.textContent = 'Откройте мини-приложение из Telegram, чтобы продолжить.'
      actions.push({ label: 'Перезапустить', onClick: () => window.location.reload() })
    } else if (code === ApiErrorCodes.UNAUTHORIZED) {
      refs.venueErrorTitle.textContent = 'Сессия истекла'
      refs.venueErrorMessage.textContent = 'Перезапустите мини-приложение или повторите авторизацию.'
      actions.push({ label: 'Перезапустить', onClick: () => window.location.reload() })
      actions.push({
        label: 'Повторить (переавторизоваться)',
        onClick: () => {
          clearSession(state.backendUrl, state.isDebug)
          const venueId = Number(refs.venueInput.value)
          if (venueId && !Number.isNaN(venueId)) {
            void loadVenue(venueId)
          }
        }
      })
    } else {
      refs.venueErrorTitle.textContent = 'Не удалось загрузить заведение'
      refs.venueErrorMessage.textContent = 'Попробуйте повторить запрос чуть позже.'
      actions.push({
        label: 'Повторить',
        onClick: () => {
          const venueId = Number(refs.venueInput.value)
          if (venueId && !Number.isNaN(venueId)) {
            void loadVenue(venueId)
          }
        }
      })
    }

    renderErrorActions(refs.venueErrorActions, actions)

    renderErrorDetails(refs.venueErrorDetails, error, {
      isDebug: state.isDebug,
      extraNotes: getVenueVisibilityNotes(state)
    })
    refs.venueError.hidden = false
    refs.venueDetails.hidden = true
    logApiError('venue', error, state.isDebug)
  }

  const renderVenueDetails = (venue: VenueResponse['venue']) => {
    refs.venueDetails.replaceChildren()
    const title = document.createElement('h3')
    title.textContent = venue.name
    const location = document.createElement('p')
    location.textContent = `${venue.city ?? '—'}${venue.address ? `, ${venue.address}` : ''}`
    const status = document.createElement('p')
    status.className = 'status'
    status.textContent = `Статус: ${venue.status}`
    refs.venueDetails.appendChild(title)
    refs.venueDetails.appendChild(location)
    refs.venueDetails.appendChild(status)
    refs.venueDetails.hidden = false
  }

  async function loadCatalog() {
    if (disposed) return
    setCatalogStatus('Загрузка каталога...')
    hideCatalogError()
    refs.catalogList.replaceChildren()
    if (catalogAbort) {
      catalogAbort.abort()
    }
    const controller = new AbortController()
    catalogAbort = controller
    const result = await requestApi<CatalogResponse>(
      state.backendUrl,
      '/api/guest/catalog',
      { signal: controller.signal },
      buildApiDeps(state.isDebug)
    )
    if (disposed) {
      // Screen was disposed while awaiting, skip UI updates.
      return
    }
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) {
      return
    }
    if (!result.ok) {
      if (result.error.status === 423) {
        state.visibilitySuspendedObserved = true
      }
      showCatalogError(result.error)
      setCatalogStatus('')
      return
    }
    setCatalogStatus('')
    renderCatalogList(result.data.venues ?? [], (venueId) => loadVenue(venueId), refs)
  }

  async function loadVenue(venueId: number) {
    if (disposed) return
    setVenueStatus('Загрузка заведения...')
    hideVenueError()
    refs.venueDetails.hidden = true
    if (venueAbort) {
      venueAbort.abort()
    }
    const controller = new AbortController()
    venueAbort = controller
    const result = await requestApi<VenueResponse>(
      state.backendUrl,
      `/api/guest/venue/${venueId}`,
      { signal: controller.signal },
      buildApiDeps(state.isDebug)
    )
    if (disposed) {
      // Screen was disposed while awaiting, skip UI updates.
      return
    }
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) {
      return
    }
    if (!result.ok) {
      if (result.error.status === 423) {
        state.visibilitySuspendedObserved = true
      }
      if (result.error.status === 404) {
        state.venueNotFoundObserved = true
      }
      showVenueError(result.error)
      setVenueStatus('')
      return
    }
    setVenueStatus('')
    renderVenueDetails(result.data.venue)
  }

  disposables.push(
    on(refs.catalogRetryButton, 'click', () => void loadCatalog()),
    on(refs.venueLoadButton, 'click', () => {
      const venueId = Number(refs.venueInput.value)
      if (!venueId || Number.isNaN(venueId)) {
        showVenueError({
          status: 400,
          code: ApiErrorCodes.INVALID_INPUT,
          message: 'Некорректный ID'
        })
        return
      }
      void loadVenue(venueId)
    })
  )

  void ensureGuestSession(state.backendUrl, state.isDebug)
  void loadCatalog()

  return () => {
    disposed = true
    catalogAbort?.abort()
    venueAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
