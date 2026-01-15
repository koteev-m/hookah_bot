import { normalizeErrorCode } from '../shared/api/errorMapping'
import { requestApi } from '../shared/api/request'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { clearSession, ensureGuestSession } from '../shared/session/guestSession'
import { getTelegramContext } from '../shared/telegram'
import { renderErrorDetails } from '../shared/ui/errorDetails'

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

let visibilitySuspendedObserved = false
let venueNotFoundObserved = false

function buildApiDeps(isDebug: boolean) {
  return { isDebug, ensureGuestSession, clearSession }
}

function logApiError(context: string, error: ApiErrorInfo) {
  console.warn(`[${context}] API error`, {
    status: error.status,
    code: error.code,
    requestId: error.requestId,
    message: error.message
  })
}

function setCatalogStatus(text: string) {
  const status = document.querySelector<HTMLParagraphElement>('#catalog-status')
  if (status) {
    status.textContent = text
  }
}

function setVenueStatus(text: string) {
  const status = document.querySelector<HTMLParagraphElement>('#venue-status')
  if (status) {
    status.textContent = text
  }
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

function getVisibilityNotes(isDebug: boolean) {
  if (!isDebug || !visibilitySuspendedObserved) {
    return []
  }
  return ['Visibility mode: explain (423 observed)']
}

function getVenueVisibilityNotes(isDebug: boolean) {
  const notes = getVisibilityNotes(isDebug)
  if (isDebug && venueNotFoundObserved) {
    notes.push('404 может означать hide mode или реальный NOT_FOUND')
  }
  return notes
}

function showCatalogError(error: ApiErrorInfo, isDebug: boolean, backendUrl: string) {
  const container = document.querySelector<HTMLDivElement>('#catalog-error')
  const title = document.querySelector<HTMLHeadingElement>('#catalog-error-title')
  const message = document.querySelector<HTMLParagraphElement>('#catalog-error-message')
  const actions = document.querySelector<HTMLDivElement>('#catalog-error-actions')
  const details = document.querySelector<HTMLDivElement>('#catalog-error-details')
  if (!container || !title || !message || !actions || !details) return

  const code = normalizeErrorCode(error)
  const actionsToRender: ErrorAction[] = []

  if (code === ApiErrorCodes.DATABASE_UNAVAILABLE) {
    title.textContent = 'Каталог временно недоступен'
    message.textContent = 'Сервис перегружен или недоступен. Попробуйте ещё раз чуть позже.'
    actionsToRender.push({
      label: 'Повторить',
      onClick: () => void loadCatalog(backendUrl, isDebug)
    })
  } else if (code === ApiErrorCodes.NETWORK_ERROR) {
    title.textContent = 'Нет соединения'
    message.textContent = 'Нет соединения / ошибка сети. Проверьте подключение и повторите.'
    actionsToRender.push({
      label: 'Повторить',
      onClick: () => void loadCatalog(backendUrl, isDebug)
    })
  } else if (code === ApiErrorCodes.INITDATA_INVALID) {
    title.textContent = 'Нужен запуск из Telegram'
    message.textContent = 'Откройте мини-приложение из Telegram, чтобы продолжить.'
    actionsToRender.push({ label: 'Перезапустить', onClick: () => window.location.reload() })
  } else if (code === ApiErrorCodes.UNAUTHORIZED) {
    title.textContent = 'Сессия истекла'
    message.textContent = 'Перезапустите мини-приложение или повторите авторизацию.'
    actionsToRender.push({ label: 'Перезапустить', onClick: () => window.location.reload() })
    actionsToRender.push({
      label: 'Повторить (переавторизоваться)',
      onClick: () => {
        clearSession(backendUrl, isDebug)
        void loadCatalog(backendUrl, isDebug)
      }
    })
  } else {
    title.textContent = 'Не удалось загрузить каталог'
    message.textContent = 'Попробуйте обновить страницу или повторить запрос позже.'
    actionsToRender.push({
      label: 'Повторить',
      onClick: () => void loadCatalog(backendUrl, isDebug)
    })
  }

  renderErrorActions(actions, actionsToRender)

  renderErrorDetails(details, error, { isDebug, extraNotes: getVisibilityNotes(isDebug) })
  container.hidden = false
  logApiError('catalog', error)
}

function hideCatalogError() {
  const container = document.querySelector<HTMLDivElement>('#catalog-error')
  if (container) {
    container.hidden = true
  }
}

function renderCatalogList(venues: CatalogVenue[]) {
  const list = document.querySelector<HTMLUListElement>('#catalog-list')
  if (!list) return
  list.replaceChildren()
  if (!venues.length) {
    const item = document.createElement('li')
    item.textContent = 'Пока нет доступных заведений.'
    list.appendChild(item)
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
    button.addEventListener('click', () => void loadVenue(venue.id))
    item.appendChild(info)
    item.appendChild(button)
    list.appendChild(item)
  })
}

async function loadCatalog(backendUrl: string, isDebug: boolean) {
  setCatalogStatus('Загрузка каталога...')
  hideCatalogError()
  const list = document.querySelector<HTMLUListElement>('#catalog-list')
  if (list) {
    list.replaceChildren()
  }
  const result = await requestApi<CatalogResponse>(
    backendUrl,
    '/api/guest/catalog',
    undefined,
    buildApiDeps(isDebug)
  )
  if (!result.ok) {
    if (result.error.status === 423) {
      visibilitySuspendedObserved = true
    }
    showCatalogError(result.error, isDebug, backendUrl)
    setCatalogStatus('')
    return
  }
  setCatalogStatus('')
  renderCatalogList(result.data.venues ?? [])
}

function showVenueError(error: ApiErrorInfo, isDebug: boolean, backendUrl: string) {
  const container = document.querySelector<HTMLDivElement>('#venue-error')
  const title = document.querySelector<HTMLHeadingElement>('#venue-error-title')
  const message = document.querySelector<HTMLParagraphElement>('#venue-error-message')
  const actions = document.querySelector<HTMLDivElement>('#venue-error-actions')
  const details = document.querySelector<HTMLDivElement>('#venue-error-details')
  const detailsContainer = document.querySelector<HTMLDivElement>('#venue-details')
  if (!container || !title || !message || !actions || !details || !detailsContainer) return

  const code = normalizeErrorCode(error)
  const actionsToRender: ErrorAction[] = []

  if (code === ApiErrorCodes.SERVICE_SUSPENDED) {
    title.textContent = 'Заведение временно недоступно'
    message.textContent = 'Попробуйте вернуться позже или выбрать другое заведение.'
    actionsToRender.push({
      label: 'Вернуться в каталог',
      onClick: () => void loadCatalog(backendUrl, isDebug)
    })
  } else if (code === ApiErrorCodes.NOT_FOUND) {
    title.textContent = 'Заведение не найдено'
    message.textContent = 'Проверьте ссылку или выберите другое заведение в каталоге.'
    actionsToRender.push({
      label: 'Вернуться в каталог',
      onClick: () => void loadCatalog(backendUrl, isDebug)
    })
  } else if (code === ApiErrorCodes.INVALID_INPUT) {
    title.textContent = 'Некорректная ссылка'
    message.textContent = 'Похоже, ID заведения указан неверно.'
    actionsToRender.push({
      label: 'Вернуться в каталог',
      onClick: () => void loadCatalog(backendUrl, isDebug)
    })
  } else if (code === ApiErrorCodes.DATABASE_UNAVAILABLE) {
    title.textContent = 'Сервис временно недоступен'
    message.textContent = 'Попробуйте повторить запрос чуть позже.'
    actionsToRender.push({
      label: 'Повторить',
      onClick: () => {
        const input = document.querySelector<HTMLInputElement>('#guest-venue-id')
        const venueId = input ? Number(input.value) : NaN
        if (venueId && !Number.isNaN(venueId)) {
          void loadVenue(venueId)
        }
      }
    })
  } else if (code === ApiErrorCodes.NETWORK_ERROR) {
    title.textContent = 'Нет соединения'
    message.textContent = 'Нет соединения / ошибка сети. Проверьте подключение и повторите.'
    actionsToRender.push({
      label: 'Повторить',
      onClick: () => {
        const input = document.querySelector<HTMLInputElement>('#guest-venue-id')
        const venueId = input ? Number(input.value) : NaN
        if (venueId && !Number.isNaN(venueId)) {
          void loadVenue(venueId)
        }
      }
    })
  } else if (code === ApiErrorCodes.INITDATA_INVALID) {
    title.textContent = 'Нужен запуск из Telegram'
    message.textContent = 'Откройте мини-приложение из Telegram, чтобы продолжить.'
    actionsToRender.push({ label: 'Перезапустить', onClick: () => window.location.reload() })
  } else if (code === ApiErrorCodes.UNAUTHORIZED) {
    title.textContent = 'Сессия истекла'
    message.textContent = 'Перезапустите мини-приложение или повторите авторизацию.'
    actionsToRender.push({ label: 'Перезапустить', onClick: () => window.location.reload() })
    actionsToRender.push({
      label: 'Повторить (переавторизоваться)',
      onClick: () => {
        clearSession(backendUrl, isDebug)
        const input = document.querySelector<HTMLInputElement>('#guest-venue-id')
        const venueId = input ? Number(input.value) : NaN
        if (venueId && !Number.isNaN(venueId)) {
          void loadVenue(venueId)
        }
      }
    })
  } else {
    title.textContent = 'Не удалось загрузить заведение'
    message.textContent = 'Попробуйте повторить запрос чуть позже.'
    actionsToRender.push({
      label: 'Повторить',
      onClick: () => {
        const input = document.querySelector<HTMLInputElement>('#guest-venue-id')
        const venueId = input ? Number(input.value) : NaN
        if (venueId && !Number.isNaN(venueId)) {
          void loadVenue(venueId)
        }
      }
    })
  }

  renderErrorActions(actions, actionsToRender)

  renderErrorDetails(details, error, { isDebug, extraNotes: getVenueVisibilityNotes(isDebug) })
  container.hidden = false
  detailsContainer.hidden = true
  logApiError('venue', error)
}

function hideVenueError() {
  const container = document.querySelector<HTMLDivElement>('#venue-error')
  if (container) {
    container.hidden = true
  }
}

function renderVenueDetails(venue: VenueResponse['venue']) {
  const details = document.querySelector<HTMLDivElement>('#venue-details')
  if (!details) return
  details.replaceChildren()
  const title = document.createElement('h3')
  title.textContent = venue.name
  const location = document.createElement('p')
  location.textContent = `${venue.city ?? '—'}${venue.address ? `, ${venue.address}` : ''}`
  const status = document.createElement('p')
  status.className = 'status'
  status.textContent = `Статус: ${venue.status}`
  details.appendChild(title)
  details.appendChild(location)
  details.appendChild(status)
  details.hidden = false
}

async function loadVenue(venueId: number) {
  const { backendUrl, isDebug } = catalogRuntime
  setVenueStatus('Загрузка заведения...')
  hideVenueError()
  const details = document.querySelector<HTMLDivElement>('#venue-details')
  if (details) {
    details.hidden = true
  }
  const result = await requestApi<VenueResponse>(
    backendUrl,
    `/api/guest/venue/${venueId}`,
    undefined,
    buildApiDeps(isDebug)
  )
  if (!result.ok) {
    if (result.error.status === 423) {
      visibilitySuspendedObserved = true
    }
    if (result.error.status === 404) {
      venueNotFoundObserved = true
    }
    showVenueError(result.error, isDebug, backendUrl)
    setVenueStatus('')
    return
  }
  setVenueStatus('')
  renderVenueDetails(result.data.venue)
}

const catalogRuntime: { backendUrl: string; isDebug: boolean } = {
  backendUrl: '',
  isDebug: false
}

export function renderCatalogScreen(options: CatalogScreenOptions) {
  const { root, backendUrl, isDebug } = options
  if (!root) return
  const { initDataLength, startParam, userId } = getTelegramContext()
  const defaultVenueId = Number(startParam)
  catalogRuntime.backendUrl = backendUrl
  catalogRuntime.isDebug = isDebug
  root.innerHTML = `
    <main class="container">
      <header>
        <h1>Hookah Mini App</h1>
        <p>Каталог кальянных</p>
      </header>
      <section class="info">
        <p><strong>initData length:</strong> ${initDataLength}</p>
        <p><strong>tgWebAppStartParam:</strong> ${startParam || '—'}</p>
        <p><strong>Telegram user id:</strong> ${userId ?? '—'}</p>
      </section>
      <section class="card">
        <div class="card-header">
          <h2>Каталог</h2>
          <button id="catalog-retry-btn">Обновить</button>
        </div>
        <p id="catalog-status" class="status">Загрузка каталога...</p>
        <div id="catalog-error" class="error-card" hidden>
          <h3 id="catalog-error-title"></h3>
          <p id="catalog-error-message"></p>
          <div class="error-actions" id="catalog-error-actions"></div>
          <div id="catalog-error-details"></div>
        </div>
        <ul id="catalog-list" class="catalog-list"></ul>
      </section>
      <section class="card">
        <div class="card-header">
          <h2>Заведение по ID</h2>
        </div>
        <label for="guest-venue-id">ID заведения</label>
        <input id="guest-venue-id" type="number" value="${
          Number.isFinite(defaultVenueId) ? defaultVenueId : ''
        }" placeholder="Введите ID" />
        <div class="button-row">
          <button id="venue-load-btn">Открыть заведение</button>
        </div>
        <p id="venue-status" class="status">Введите ID и загрузите карточку.</p>
        <div id="venue-error" class="error-card" hidden>
          <h3 id="venue-error-title"></h3>
          <p id="venue-error-message"></p>
          <div class="error-actions" id="venue-error-actions"></div>
          <div id="venue-error-details"></div>
        </div>
        <div id="venue-details" class="venue-details" hidden></div>
      </section>
    </main>
  `

  document
    .querySelector<HTMLButtonElement>('#catalog-retry-btn')
    ?.addEventListener('click', () => void loadCatalog(backendUrl, isDebug))
  document.querySelector<HTMLButtonElement>('#venue-load-btn')?.addEventListener('click', () => {
    const input = document.querySelector<HTMLInputElement>('#guest-venue-id')
    if (!input) return
    const venueId = Number(input.value)
    if (!venueId || Number.isNaN(venueId)) {
      showVenueError(
        {
          status: 400,
          code: ApiErrorCodes.INVALID_INPUT,
          message: 'Некорректный ID'
        },
        isDebug,
        backendUrl
      )
      return
    }
    void loadVenue(venueId)
  })

  void ensureGuestSession(backendUrl, isDebug)
  void loadCatalog(backendUrl, isDebug)
}
