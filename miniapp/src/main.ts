import './style.css'

const root = document.querySelector<HTMLDivElement>('#app')
const backendUrl = import.meta.env.VITE_BACKEND_PUBLIC_URL ?? 'http://localhost:8080'
const isDebug = Boolean(import.meta.env.DEV)
const searchParams = new URLSearchParams(window.location.search)
const screen = searchParams.get('screen')
const mode = searchParams.get('mode')

const ApiErrorCodes = {
  UNAUTHORIZED: 'UNAUTHORIZED',
  INVALID_INPUT: 'INVALID_INPUT',
  NOT_FOUND: 'NOT_FOUND',
  SERVICE_SUSPENDED: 'SERVICE_SUSPENDED',
  DATABASE_UNAVAILABLE: 'DATABASE_UNAVAILABLE',
  CONFIG_ERROR: 'CONFIG_ERROR',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
  INITDATA_INVALID: 'INITDATA_INVALID',
  NETWORK_ERROR: 'NETWORK_ERROR'
} as const

type ApiErrorCode = (typeof ApiErrorCodes)[keyof typeof ApiErrorCodes]

type TelegramContext = {
  initDataLength: number
  startParam: string
  userId?: number
}

type ApiErrorEnvelope = {
  error?: {
    code?: string
    message?: string
    details?: unknown
  }
  requestId?: string
}

type ApiErrorInfo = {
  status: number
  code?: string
  message?: string
  requestId?: string
}

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

type GuestSession = {
  token: string
  expiresAtEpochSeconds: number
  telegramUserId?: number
}

type TelegramAuthResponse = {
  token: string
  expiresAtEpochSeconds: number
  user: {
    telegramUserId: number
  }
}

const guestSessionStorageKey = 'hookah_guest_session'
const sessionSafetySkewSeconds = 15
let inMemorySession: GuestSession | null = null
let pendingAuth: Promise<{ session?: GuestSession; error?: ApiErrorInfo }> | null = null

function nowEpochSeconds() {
  return Math.floor(Date.now() / 1000)
}

function isSessionValid(session: GuestSession) {
  return nowEpochSeconds() < session.expiresAtEpochSeconds - sessionSafetySkewSeconds
}

function loadStoredSession(): GuestSession | null {
  const raw = localStorage.getItem(guestSessionStorageKey)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as GuestSession
    if (!parsed.token || !parsed.expiresAtEpochSeconds) {
      return null
    }
    return parsed
  } catch (error) {
    return null
  }
}

function persistSession(session: GuestSession) {
  inMemorySession = session
  localStorage.setItem(guestSessionStorageKey, JSON.stringify(session))
}

function clearSession() {
  inMemorySession = null
  localStorage.removeItem(guestSessionStorageKey)
}

function getTelegramContext(): TelegramContext {
  const tg = (window as any).Telegram?.WebApp
  const initData = tg?.initData || ''
  const startParam = tg?.initDataUnsafe?.start_param || tg?.initDataUnsafe?.startParam || ''
  const userId = tg?.initDataUnsafe?.user?.id
  return { initDataLength: initData.length, startParam, userId }
}

function getTelegramInitData(): string {
  const tg = (window as any).Telegram?.WebApp
  return tg?.initData || ''
}

function resolveRequestId(headerValue: string | null, bodyValue?: string | null): string | undefined {
  if (headerValue && bodyValue && headerValue !== bodyValue) {
    console.warn('RequestId mismatch between header and body', { headerValue, bodyValue })
  }
  return headerValue ?? bodyValue ?? undefined
}

function normalizeErrorCode(error: ApiErrorInfo): ApiErrorCode | undefined {
  if (error.code && (Object.values(ApiErrorCodes) as string[]).includes(error.code)) {
    return error.code as ApiErrorCode
  }
  switch (error.status) {
    case 400:
      return ApiErrorCodes.INVALID_INPUT
    case 401:
      return ApiErrorCodes.UNAUTHORIZED
    case 404:
      return ApiErrorCodes.NOT_FOUND
    case 423:
      return ApiErrorCodes.SERVICE_SUSPENDED
    case 503:
      return ApiErrorCodes.DATABASE_UNAVAILABLE
    default:
      return undefined
  }
}

function logApiError(context: string, error: ApiErrorInfo) {
  console.warn(`[${context}] API error`, {
    status: error.status,
    code: error.code,
    requestId: error.requestId,
    message: error.message
  })
}

function isGuestApi(path: string) {
  return path.startsWith('/api/guest/')
}

async function ensureGuestSession(): Promise<{ token?: string; error?: ApiErrorInfo }> {
  if (inMemorySession && isSessionValid(inMemorySession)) {
    return { token: inMemorySession.token }
  }

  const storedSession = loadStoredSession()
  if (storedSession && isSessionValid(storedSession)) {
    inMemorySession = storedSession
    return { token: storedSession.token }
  }

  clearSession()

  if (pendingAuth) {
    const result = await pendingAuth
    return result.session ? { token: result.session.token } : { error: result.error }
  }

  pendingAuth = (async () => {
    const initData = getTelegramInitData()
    if (!initData) {
      return {
        error: {
          status: 401,
          code: ApiErrorCodes.INITDATA_INVALID,
          message: 'Откройте мини-приложение из Telegram.'
        }
      }
    }
    const { data, error } = await requestApi<TelegramAuthResponse>('/api/auth/telegram', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ initData })
    })
    if (error || !data) {
      return {
        error: error ?? {
          status: 401,
          code: ApiErrorCodes.UNAUTHORIZED,
          message: 'Не удалось авторизоваться.'
        }
      }
    }
    const session: GuestSession = {
      token: data.token,
      expiresAtEpochSeconds: data.expiresAtEpochSeconds,
      telegramUserId: data.user?.telegramUserId
    }
    persistSession(session)
    return { session }
  })()

  const result = await pendingAuth
  pendingAuth = null
  return result.session ? { token: result.session.token } : { error: result.error }
}

async function requestApi<T>(path: string, init?: RequestInit): Promise<{ data?: T; error?: ApiErrorInfo }> {
  if (isGuestApi(path)) {
    const { token, error } = await ensureGuestSession()
    if (!token || error) {
      return { error: error ?? { status: 401, code: ApiErrorCodes.UNAUTHORIZED } }
    }
    const headers = new Headers(init?.headers ?? {})
    headers.set('Authorization', `Bearer ${token}`)
    init = { ...init, headers }
  }

  try {
    const response = await fetch(`${backendUrl}${path}`, init)
    if (response.ok) {
      return { data: (await response.json()) as T }
    }

    const headerRequestId = response.headers.get('X-Request-Id')
    let envelope: ApiErrorEnvelope | null = null
    try {
      envelope = (await response.json()) as ApiErrorEnvelope
    } catch (error) {
      envelope = null
    }
    const requestId = resolveRequestId(headerRequestId, envelope?.requestId)
    const errorInfo: ApiErrorInfo = {
      status: response.status,
      code: envelope?.error?.code,
      message: envelope?.error?.message,
      requestId
    }
    if (isGuestApi(path) && response.status === 401) {
      clearSession()
    }
    return { error: errorInfo }
  } catch (error) {
    return {
      error: {
        status: 0,
        code: ApiErrorCodes.NETWORK_ERROR,
        message: 'Нет соединения / ошибка сети'
      }
    }
  }
}

function renderErrorDetails(container: HTMLElement, error: ApiErrorInfo) {
  container.textContent = ''
  if (!isDebug) {
    return
  }
  if (!error.requestId && !error.code && !error.message) {
    return
  }
  const details = document.createElement('details')
  details.className = 'error-details'
  details.open = false
  const summary = document.createElement('summary')
  summary.textContent = 'Подробнее'
  details.appendChild(summary)
  const list = document.createElement('ul')
  if (error.requestId) {
    const item = document.createElement('li')
    item.textContent = `Request ID: ${error.requestId}`
    list.appendChild(item)
  }
  if (error.code) {
    const item = document.createElement('li')
    item.textContent = `Код: ${error.code}`
    list.appendChild(item)
  }
  if (error.message) {
    const item = document.createElement('li')
    item.textContent = `Сообщение: ${error.message}`
    list.appendChild(item)
  }
  details.appendChild(list)
  if (error.requestId) {
    const actions = document.createElement('div')
    actions.className = 'error-details-actions'
    const copyButton = document.createElement('button')
    copyButton.className = 'button-small'
    copyButton.textContent = 'Скопировать requestId'
    copyButton.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(error.requestId ?? '')
      } catch (copyError) {
        console.warn('Failed to copy requestId', copyError)
      }
    })
    actions.appendChild(copyButton)
    details.appendChild(actions)
  }
  container.appendChild(details)
}

async function pingBackend() {
  const statusElement = document.querySelector<HTMLParagraphElement>('#backend-status')
  if (!statusElement) return
  statusElement.textContent = 'Pinging backend...'
  try {
    const response = await fetch(`${backendUrl}/health`)
    if (!response.ok) {
      throw new Error(`Unexpected status ${response.status}`)
    }
    const data = await response.json()
    statusElement.textContent = `Backend OK: ${JSON.stringify(data)}`
  } catch (error) {
    statusElement.textContent = `Backend error: ${(error as Error).message}`
  }
}

type LinkCodePayload = {
  code: string
  expiresAt: string
  ttlSeconds: number
}

let countdownTimer: number | null = null

function startCountdown(expiresAt: string) {
  const countdownElement = document.querySelector<HTMLSpanElement>('#link-countdown')
  if (!countdownElement) return
  if (countdownTimer) {
    window.clearInterval(countdownTimer)
  }
  const target = new Date(expiresAt).getTime()
  const update = () => {
    const diff = Math.max(0, target - Date.now())
    const seconds = Math.floor(diff / 1000)
    const minutes = Math.floor(seconds / 60)
    const remainSeconds = seconds % 60
    countdownElement.textContent = `${minutes}м ${remainSeconds}с`
  }
  update()
  countdownTimer = window.setInterval(update, 1000)
}

async function generateLinkCode() {
  const venueInput = document.querySelector<HTMLInputElement>('#venue-id')
  const status = document.querySelector<HTMLParagraphElement>('#link-status')
  const codeElement = document.querySelector<HTMLSpanElement>('#link-code')
  const container = document.querySelector<HTMLDivElement>('#link-result')
  if (!venueInput || !status || !codeElement || !container) return
  const venueId = Number(venueInput.value)
  const ctx = getTelegramContext()
  if (!venueId || Number.isNaN(venueId)) {
    status.textContent = 'Укажите корректный ID заведения.'
    container.hidden = true
    return
  }
  if (!ctx.userId) {
    status.textContent = 'Не удалось определить Telegram ID. Откройте мини-приложение из Telegram.'
    container.hidden = true
    return
  }
  status.textContent = 'Генерация кода...'
  container.hidden = true
  try {
    const response = await fetch(`${backendUrl}/api/venue/${venueId}/staff-chat/link-code`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-User-Id': ctx.userId.toString()
      },
      body: JSON.stringify({ userId: ctx.userId })
    })
    if (!response.ok) {
      const errorText = await response.text()
      throw new Error(`Ошибка ${response.status}: ${errorText || 'неизвестно'}`)
    }
    const payload = (await response.json()) as LinkCodePayload
    codeElement.textContent = payload.code
    status.textContent = 'Код сгенерирован. Отправьте /link <код> в чате персонала.'
    container.hidden = false
    startCountdown(payload.expiresAt)
  } catch (error) {
    status.textContent = `Не удалось сгенерировать код: ${(error as Error).message}`
    container.hidden = true
  }
}

function setupCopyButton() {
  const copyButton = document.querySelector<HTMLButtonElement>('#copy-link-btn')
  const codeElement = document.querySelector<HTMLSpanElement>('#link-code')
  const status = document.querySelector<HTMLParagraphElement>('#link-status')
  if (!copyButton || !codeElement || !status) return
  copyButton.addEventListener('click', async () => {
    const code = codeElement.textContent?.trim()
    if (!code) return
    try {
      await navigator.clipboard.writeText(`/link ${code}`)
      status.textContent = 'Скопировано: /link <код>'
    } catch (error) {
      status.textContent = 'Не удалось скопировать. Скопируйте вручную.'
    }
  })
}

function renderVenueMode() {
  if (!root) return
  const { initDataLength, startParam, userId } = getTelegramContext()
  const defaultVenueId = Number(startParam)
  root.innerHTML = `
    <main class="container">
      <header>
        <h1>Hookah Mini App</h1>
        <p>Режим заведения: привязка чата персонала.</p>
      </header>
      <section class="info">
        <p><strong>initData length:</strong> ${initDataLength}</p>
        <p><strong>tgWebAppStartParam:</strong> ${startParam || '—'}</p>
        <p><strong>Telegram user id:</strong> ${userId ?? '—'}</p>
        <p><strong>Текущее время:</strong> <span id="now"></span></p>
      </section>
      <section>
        <button id="ping-btn">Ping backend /health</button>
        <p id="backend-status" class="status">Idle</p>
      </section>
      <section class="link-card">
        <h2>Привязать чат персонала</h2>
        <label for="venue-id">ID заведения</label>
        <input id="venue-id" type="number" value="${Number.isFinite(defaultVenueId) ? defaultVenueId : ''}" placeholder="Введите ID" />
        <p id="link-status" class="status">Сгенерируйте код, чтобы связать чат.</p>
        <div id="link-result" class="link-result" hidden>
          <p>Код: <strong id="link-code"></strong></p>
          <p>Истекает через: <span id="link-countdown"></span></p>
          <button id="copy-link-btn">Скопировать /link &lt;код&gt;</button>
        </div>
        <button id="generate-link-btn">Сгенерировать новый код</button>
      </section>
      <section>
        <p>Backend URL: ${backendUrl}</p>
      </section>
    </main>
  `

  document.querySelector<HTMLButtonElement>('#ping-btn')?.addEventListener('click', pingBackend)
  document.querySelector<HTMLButtonElement>('#generate-link-btn')?.addEventListener('click', generateLinkCode)
  setupCopyButton()
}

function renderCatalogScreen() {
  if (!root) return
  const { initDataLength, startParam, userId } = getTelegramContext()
  const defaultVenueId = Number(startParam)
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
        <input id="guest-venue-id" type="number" value="${Number.isFinite(defaultVenueId) ? defaultVenueId : ''}" placeholder="Введите ID" />
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

  document.querySelector<HTMLButtonElement>('#catalog-retry-btn')?.addEventListener('click', () => void loadCatalog())
  document.querySelector<HTMLButtonElement>('#venue-load-btn')?.addEventListener('click', () => {
    const input = document.querySelector<HTMLInputElement>('#guest-venue-id')
    if (!input) return
    const venueId = Number(input.value)
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

  void ensureGuestSession()
  void loadCatalog()
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

type ErrorAction = {
  label: string
  onClick: () => void
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

function showCatalogError(error: ApiErrorInfo) {
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
    actionsToRender.push({ label: 'Повторить', onClick: () => void loadCatalog() })
  } else if (code === ApiErrorCodes.NETWORK_ERROR) {
    title.textContent = 'Нет соединения'
    message.textContent = 'Нет соединения / ошибка сети. Проверьте подключение и повторите.'
    actionsToRender.push({ label: 'Повторить', onClick: () => void loadCatalog() })
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
        clearSession()
        void loadCatalog()
      }
    })
  } else {
    title.textContent = 'Не удалось загрузить каталог'
    message.textContent = 'Попробуйте обновить страницу или повторить запрос позже.'
    actionsToRender.push({ label: 'Повторить', onClick: () => void loadCatalog() })
  }

  renderErrorActions(actions, actionsToRender)

  renderErrorDetails(details, error)
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

async function loadCatalog() {
  setCatalogStatus('Загрузка каталога...')
  hideCatalogError()
  const list = document.querySelector<HTMLUListElement>('#catalog-list')
  if (list) {
    list.replaceChildren()
  }
  const { data, error } = await requestApi<CatalogResponse>('/api/guest/catalog')
  if (error) {
    showCatalogError(error)
    setCatalogStatus('')
    return
  }
  setCatalogStatus('')
  renderCatalogList(data?.venues ?? [])
}

function showVenueError(error: ApiErrorInfo) {
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
    actionsToRender.push({ label: 'Вернуться в каталог', onClick: () => void loadCatalog() })
  } else if (code === ApiErrorCodes.NOT_FOUND) {
    title.textContent = 'Заведение не найдено'
    message.textContent = 'Проверьте ссылку или выберите другое заведение в каталоге.'
    actionsToRender.push({ label: 'Вернуться в каталог', onClick: () => void loadCatalog() })
  } else if (code === ApiErrorCodes.INVALID_INPUT) {
    title.textContent = 'Некорректная ссылка'
    message.textContent = 'Похоже, ID заведения указан неверно.'
    actionsToRender.push({ label: 'Вернуться в каталог', onClick: () => void loadCatalog() })
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
        clearSession()
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

  renderErrorDetails(details, error)
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
  setVenueStatus('Загрузка заведения...')
  hideVenueError()
  const details = document.querySelector<HTMLDivElement>('#venue-details')
  if (details) {
    details.hidden = true
  }
  const { data, error } = await requestApi<VenueResponse>(`/api/guest/venue/${venueId}`)
  if (error) {
    showVenueError(error)
    setVenueStatus('')
    return
  }
  setVenueStatus('')
  if (data) {
    renderVenueDetails(data.venue)
  }
}

function startClock() {
  const nowElement = document.querySelector<HTMLSpanElement>('#now')
  if (!nowElement) return
  const update = () => {
    nowElement.textContent = new Date().toLocaleString()
  }
  update()
  setInterval(update, 1000)
}

function render() {
  if (screen === 'catalog') {
    renderCatalogScreen()
    return
  }
  if (mode === 'venue') {
    renderVenueMode()
    return
  }
  renderVenueMode()
}

render()
startClock()
