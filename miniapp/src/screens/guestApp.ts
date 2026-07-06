import { getBackendBaseUrl } from '../shared/api/backend'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { guestEndTableSession, guestGetActiveOrder, guestGetStaffCallStatus, guestGetTabs } from '../shared/api/guestApi'
import { isDebugEnabled } from '../shared/debug'
import {
  clearCurrentTableContext,
  formatTableStatus,
  getTableContext,
  initTableContext,
  refresh as refreshTableContext,
  subscribe as subscribeTable
} from '../shared/state/tableContext'
import { getCartSnapshot, setCartTableToken, subscribeCart } from '../shared/state/cartStore'
import { parsePositiveInt } from '../shared/parse'
import { bindTelegramBackButton } from '../shared/telegramBackButton'
import { getTelegramContext, getTelegramQrScanner } from '../shared/telegram'
import { openBotChat } from '../shared/telegramActions'
import { normalizeTableToken } from '../shared/validation/tableToken'
import { append, el, on } from '../shared/ui/dom'
import { renderCatalogScreen } from './catalog'
import { renderCartScreen } from './cart'
import { renderGuestAccountScreen } from './guestAccount'
import { renderGuestBookingsScreen } from './guestBookings'
import { renderGuestSupportThreadsScreen } from './guestSupportThreads'
import { renderGuestVenueScreen } from './guestVenue'
import { renderOrderScreen } from './order'

type GuestAppOptions = {
  root: HTMLDivElement | null
}

type RouteName = 'catalog' | 'venue' | 'cart' | 'order' | 'bookings' | 'account' | 'messages' | 'support'
type GuestActionId =
  | 'catalog'
  | 'qr'
  | 'menu'
  | 'cart'
  | 'order'
  | 'staff'
  | 'leave'
  | 'account'
  | 'messages'
  | 'support'
  | 'refresh'
type GuestTableMode = 'no-table' | 'active-table' | 'ended-table'

type Route = {
  name: RouteName
  venueId: number | null
  threadId: number | null
  openStaffCall: boolean
}

type GuestRefs = {
  app: HTMLDivElement
  statusTitle: HTMLParagraphElement
  statusDetails: HTMLParagraphElement
  primaryActions: HTMLDivElement
  nav: HTMLElement
  content: HTMLDivElement
}

const scannedTokenQueryParamKeys = ['table_token', 'tableToken', 'tgWebAppStartParam', 'startapp', 'start_param'] as const
const tableContextQueryParamKeys = [...scannedTokenQueryParamKeys, 'tableSessionId', 'table_session_id'] as const
const primaryActionsByMode: Record<GuestTableMode, GuestActionId[]> = {
  'no-table': ['qr'],
  'ended-table': ['qr', 'catalog', 'refresh'],
  'active-table': ['staff', 'leave']
}
const navActionsByMode: Record<GuestTableMode, GuestActionId[]> = {
  'no-table': ['catalog', 'qr', 'messages', 'account', 'support'],
  'ended-table': ['catalog', 'qr', 'messages', 'account', 'support'],
  'active-table': ['menu', 'cart', 'order', 'messages', 'support', 'account']
}

function ensureDefaultHash() {
  const hash = window.location.hash
  if (!hash || hash === '#' || hash === '#/') {
    window.location.hash = '#/catalog'
  }
}

function resolveRoute(): Route {
  const rawHash = window.location.hash.replace(/^#/, '')
  const cleaned = rawHash.startsWith('/') ? rawHash.slice(1) : rawHash
  if (!cleaned) {
    return { name: 'catalog', venueId: null, threadId: null, openStaffCall: false }
  }
  const [pathPart, queryPart] = cleaned.split('?')
  const segments = pathPart.split('/').filter(Boolean)
  const route = segments[0] as RouteName | undefined
  if (!route || !['catalog', 'venue', 'cart', 'order', 'bookings', 'account', 'messages', 'support'].includes(route)) {
    return { name: 'catalog', venueId: null, threadId: null, openStaffCall: false }
  }
  if (route === 'venue') {
    const venueIdFromPath = parsePositiveInt(segments[1])
    const params = new URLSearchParams(queryPart ?? '')
    const venueIdFromQuery = parsePositiveInt(params.get('id'))
    return {
      name: 'venue',
      venueId: venueIdFromPath ?? venueIdFromQuery ?? null,
      threadId: null,
      openStaffCall: params.get('staff') === '1'
    }
  }
  if (route === 'bookings' || route === 'messages' || route === 'support') {
    const params = new URLSearchParams(queryPart ?? '')
    return {
      name: route,
      venueId: parsePositiveInt(params.get('venueId')) ?? parsePositiveInt(params.get('venue_id')) ?? null,
      threadId: parsePositiveInt(params.get('threadId')) ?? parsePositiveInt(params.get('thread_id')) ?? null,
      openStaffCall: false
    }
  }
  return { name: route, venueId: null, threadId: null, openStaffCall: false }
}

function resolveRouteNameFromHash(hash: string | null | undefined): RouteName | null {
  if (!hash) {
    return null
  }
  const raw = hash.replace(/^#/, '')
  const cleaned = raw.startsWith('/') ? raw.slice(1) : raw
  if (!cleaned) {
    return 'catalog'
  }
  const [pathPart] = cleaned.split('?')
  const segments = pathPart.split('/').filter(Boolean)
  const route = segments[0] as RouteName | undefined
  if (!route || !['catalog', 'venue', 'cart', 'order', 'bookings', 'account', 'messages', 'support'].includes(route)) {
    return null
  }
  return route
}

function isTabFlowRoute(route: RouteName | null): boolean {
  return route === 'venue' || route === 'cart' || route === 'order'
}

function extractTokenFromSearchParams(params: URLSearchParams): string | null {
  for (const key of scannedTokenQueryParamKeys) {
    const token = normalizeTableToken(params.get(key))
    if (token) {
      return token
    }
  }
  return null
}

function extractTableTokenFromScannedText(scannedText: string): string | null {
  try {
    const url = new URL(scannedText)
    return extractTokenFromSearchParams(url.searchParams)
  } catch {
    return normalizeTableToken(scannedText)
  }
}

function clearTableContextSearchParams() {
  const nextUrl = new URL(window.location.href)
  tableContextQueryParamKeys.forEach((key) => nextUrl.searchParams.delete(key))
  window.history.replaceState(null, '', `${nextUrl.pathname}${nextUrl.search}${nextUrl.hash}`)
}

function showTelegramAlert(message: string) {
  try {
    getTelegramContext().webApp?.showAlert?.(message)
  } catch {
    // Telegram alert availability differs between browser shims and real clients.
  }
}

function buildGuestShell(root: HTMLDivElement): GuestRefs {
  const app = el('div', { className: 'app-shell' })
  const header = el('header', { className: 'app-header' })
  const brand = el('div', { className: 'app-brand' })
  const title = el('h1', { text: 'Hookah Mini App' })
  const subtitle = el('p', { className: 'app-subtitle', text: 'Гостевой режим' })
  append(brand, title, subtitle)

  const statusCard = el('div', { className: 'table-status' })
  const statusTitle = el('p', { className: 'table-status-title', text: '' })
  const statusDetails = el('p', { className: 'table-status-details', text: '' })
  const primaryActions = el('div', { className: 'table-actions' }) as HTMLDivElement
  append(statusCard, statusTitle, statusDetails, primaryActions)

  const nav = el('nav', { className: 'app-nav' })
  append(header, brand, statusCard, nav)

  const content = el('div', { className: 'container app-content' })
  append(app, header, content)
  root.replaceChildren(app)

  return {
    app,
    statusTitle,
    statusDetails,
    primaryActions,
    nav,
    content
  }
}

function renderPlaceholder(container: HTMLElement, title: string, body: string) {
  const section = el('section', { className: 'card' })
  const heading = el('h2', { text: title })
  const hint = el('p', { text: body })
  append(section, heading, hint)
  container.replaceChildren(section)
  return () => undefined
}

function renderRouteContent(
  route: Route,
  container: HTMLElement,
  backendUrl: string,
  isDebug: boolean,
  onOpenVenue: (venueId: number) => void,
  onNavigateOrder: () => void,
  onNavigateMenu: (venueId: number | null) => void,
  onNavigateCatalog: () => void,
  onNavigateSupportBack: () => void,
  onOpenVenueStaffCall: () => void,
  onOpenSupportBot: () => { ok: true } | { ok: false; message: string },
  tableSnapshot: ReturnType<typeof getTableContext>,
  hasTableContext: boolean
): () => void {
  const screenRoot = document.createElement('div')
  screenRoot.className = 'screen-root'
  container.replaceChildren(screenRoot)

  switch (route.name) {
    case 'venue':
      return renderGuestVenueScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        venueId: route.venueId,
        openStaffCall: route.openStaffCall,
        onBookVenue: (venueId) => {
          window.location.hash = `#/bookings?venueId=${venueId}`
        },
        onAskVenue: (venueId) => {
          window.location.hash = `#/messages?venueId=${venueId}`
        }
      })
    case 'cart':
      return renderCartScreen({ root: screenRoot, backendUrl, isDebug, onNavigateOrder })
    case 'order':
      return renderOrderScreen({ root: screenRoot, backendUrl, isDebug, onNavigateMenu })
    case 'bookings':
      return renderGuestBookingsScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        venueId: route.venueId,
        onBack: route.venueId ? onNavigateCatalog : () => {
          window.location.hash = '#/account'
        }
      })
    case 'account':
      return renderGuestAccountScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        currentVenueId: tableSnapshot.status === 'resolved' ? tableSnapshot.venueId : route.venueId,
        hasTableContext,
        onBack: onNavigateCatalog,
        onOpenBookings: () => {
          window.location.hash = '#/bookings'
        },
        onOpenVenue,
        onOpenBot: onOpenSupportBot
      })
    case 'messages':
      return renderGuestSupportThreadsScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        screenMode: 'messages',
        hasTableContext,
        tableSnapshot,
        onBack: onNavigateSupportBack,
        onOpenVenueStaffCall,
        onOpenBot: onOpenSupportBot,
        initialThreadId: route.threadId,
        createVenueChatVenueId: route.venueId
      })
    case 'support':
      return renderGuestSupportThreadsScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        screenMode: 'tickets',
        hasTableContext,
        tableSnapshot,
        onBack: onNavigateSupportBack,
        onOpenVenueStaffCall,
        onOpenBot: onOpenSupportBot,
        initialThreadId: route.threadId,
        prefillSupportVenueId: route.venueId
      })
    case 'catalog':
    default:
      return renderCatalogScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        onOpenVenue,
        onBookVenue: (venueId) => {
          window.location.hash = `#/bookings?venueId=${venueId}`
        },
        onAskVenue: (venueId) => {
          window.location.hash = `#/messages?venueId=${venueId}`
        }
      })
  }
}

function canOpenActiveOrder(snapshot: ReturnType<typeof getTableContext>): boolean {
  return snapshot.status === 'resolved' && Boolean(snapshot.tableToken) && snapshot.orderAllowed
}

function isOpenGuestOrderStatus(status: string | null | undefined): boolean {
  const normalized = status?.toLowerCase()
  return Boolean(
    normalized &&
      normalized !== 'closed' &&
      normalized !== 'rejected' &&
      normalized !== 'canceled' &&
      normalized !== 'cancelled'
  )
}

function isActiveGuestStaffCallStatus(status: string | null | undefined): boolean {
  return status === 'NEW' || status === 'ACK'
}

function resolveTableMode(snapshot: ReturnType<typeof getTableContext>): GuestTableMode {
  const hasResolvedTable = snapshot.status === 'resolved' && Boolean(snapshot.tableToken) && Boolean(snapshot.venueId)
  if (hasResolvedTable && !snapshot.tableSessionActive) {
    return 'ended-table'
  }
  if (hasResolvedTable && snapshot.tableSessionActive && snapshot.orderAllowed) {
    return 'active-table'
  }
  return 'no-table'
}

function dedupeActions(actions: GuestActionId[]): GuestActionId[] {
  return actions.filter((action, index) => actions.indexOf(action) === index)
}

function formatActionLabel(
  action: GuestActionId,
  cartQty: number,
  mode: GuestTableMode,
  variant: 'primary' | 'nav',
  staffCallActive: boolean
): string {
  switch (action) {
    case 'catalog':
      if (variant === 'nav') {
        return 'Каталог'
      }
      return mode === 'ended-table' ? '🏠 В каталог' : '🏠 Каталог кальянных'
    case 'qr':
      return variant === 'nav' ? 'QR' : '🪑 Сканировать QR'
    case 'menu':
      return variant === 'nav' ? 'Меню' : '🍽 Меню'
    case 'cart':
      return cartQty > 0 ? `${variant === 'nav' ? 'Корзина' : '🧺 Корзина'} (${cartQty})` : variant === 'nav' ? 'Корзина' : '🧺 Корзина'
    case 'order':
      return variant === 'nav' ? 'Мой заказ' : '📄 Мой заказ'
    case 'staff':
      if (mode === 'active-table' && staffCallActive) {
        return 'Вызов активен'
      }
      return '🛎 Вызвать персонал'
    case 'leave':
      return '🚪 Завершить визит'
    case 'account':
      return variant === 'nav' ? 'Профиль' : '👤 Профиль'
    case 'messages':
      return 'Чаты'
    case 'support':
      return 'Помощь'
    case 'refresh':
      return '🔄 Обновить'
    default:
      return action
  }
}

function isActionActive(action: GuestActionId, route: Route, mode: GuestTableMode): boolean {
  switch (action) {
    case 'catalog':
      return mode !== 'active-table' && route.name === 'catalog'
    case 'menu':
      return mode === 'active-table' && (route.name === 'venue' || route.name === 'catalog')
    case 'cart':
      return route.name === 'cart'
    case 'order':
      return route.name === 'order'
    case 'account':
      return route.name === 'account'
    case 'messages':
      return route.name === 'messages'
    case 'support':
      return route.name === 'support'
    default:
      return false
  }
}

function renderActionSet(
  container: HTMLElement,
  actions: GuestActionId[],
  buttonClassName: string,
  cartQty: number,
  route: Route,
  mode: GuestTableMode,
  variant: 'primary' | 'nav',
  staffCallActive: boolean,
  onAction: (action: GuestActionId) => void
) {
  const buttons = dedupeActions(actions).map((action) => {
    const button = el('button', {
      className: buttonClassName,
      text: formatActionLabel(action, cartQty, mode, variant, staffCallActive)
    }) as HTMLButtonElement
    button.dataset.action = action
    button.dataset.active = String(isActionActive(action, route, mode))
    button.addEventListener('click', () => onAction(action))
    return button
  })
  container.replaceChildren(...buttons)
}

function updateGuestShellActions(
  refs: GuestRefs,
  mode: GuestTableMode,
  route: Route,
  cartQty: number,
  staffCallActive: boolean,
  canLeaveTable: boolean,
  onAction: (action: GuestActionId) => void
) {
  const primaryActions =
    mode === 'active-table' && !canLeaveTable
      ? primaryActionsByMode[mode].filter((action) => action !== 'leave')
      : primaryActionsByMode[mode]
  renderActionSet(
    refs.primaryActions,
    primaryActions,
    'button-secondary',
    cartQty,
    route,
    mode,
    'primary',
    staffCallActive,
    onAction
  )
  renderActionSet(refs.nav, navActionsByMode[mode], 'nav-button', cartQty, route, mode, 'nav', staffCallActive, onAction)
}

export function mountGuestApp(options: GuestAppOptions) {
  const { root } = options
  if (!root) return () => undefined
  initTableContext()
  ensureDefaultHash()
  const backendUrl = getBackendBaseUrl()
  const isDebug = isDebugEnabled()

  const refs = buildGuestShell(root)
  let tableSnapshot = getTableContext()
  let currentRoute: Route = resolveRoute()
  let staffCallActive = false
  let canLeaveTable = false
  let handleGuestAction: (action: GuestActionId) => void = () => undefined
  const renderShellActions = () => {
    updateGuestShellActions(
      refs,
      resolveTableMode(tableSnapshot),
      currentRoute,
      getCartSnapshot().totalQty,
      staffCallActive,
      canLeaveTable,
      (action) => handleGuestAction(action)
    )
  }
  setCartTableToken(resolveTableMode(tableSnapshot) === 'active-table' ? tableSnapshot.tableToken : null)
  let rerenderForTableChange: (() => void) | null = null
  let exitCheckAbort: AbortController | null = null

  const setCanLeaveTable = (nextValue: boolean) => {
    if (canLeaveTable === nextValue) return
    canLeaveTable = nextValue
    renderShellActions()
  }

  const getExitCheckScope = () => {
    if (
      resolveTableMode(tableSnapshot) !== 'active-table' ||
      tableSnapshot.status !== 'resolved' ||
      !tableSnapshot.tableToken ||
      !tableSnapshot.tableSessionId
    ) {
      return null
    }
    return {
      tableToken: tableSnapshot.tableToken,
      tableSessionId: tableSnapshot.tableSessionId
    }
  }

  const refreshVisitExitAvailability = () => {
    const scope = getExitCheckScope()
    if (!scope) {
      exitCheckAbort?.abort()
      exitCheckAbort = null
      setCanLeaveTable(false)
      return
    }
    exitCheckAbort?.abort()
    const controller = new AbortController()
    exitCheckAbort = controller
    setCanLeaveTable(false)
    const deps = { isDebug, getAccessToken, clearSession }
    void (async () => {
      const staffStatus = await guestGetStaffCallStatus(backendUrl, scope, deps, controller.signal)
      if (controller.signal.aborted || exitCheckAbort !== controller) return
      if (!staffStatus.ok || staffStatus.data.items.some((item) => isActiveGuestStaffCallStatus(item.status))) {
        exitCheckAbort = null
        setCanLeaveTable(false)
        return
      }

      const tabsResult = await guestGetTabs(backendUrl, scope.tableSessionId, deps, controller.signal)
      if (controller.signal.aborted || exitCheckAbort !== controller) return
      if (!tabsResult.ok) {
        exitCheckAbort = null
        setCanLeaveTable(false)
        return
      }

      const activeTabs = tabsResult.data.tabs.filter(
        (tab) => tab.status === 'ACTIVE' && tab.tableSessionId === scope.tableSessionId
      )
      for (const tab of activeTabs) {
        const orderResult = await guestGetActiveOrder(
          backendUrl,
          scope.tableToken,
          scope.tableSessionId,
          tab.id,
          deps,
          controller.signal
        )
        if (controller.signal.aborted || exitCheckAbort !== controller) return
        if (!orderResult.ok || (orderResult.data.order && isOpenGuestOrderStatus(orderResult.data.order.status))) {
          exitCheckAbort = null
          setCanLeaveTable(false)
          return
        }
      }

      exitCheckAbort = null
      setCanLeaveTable(true)
    })()
  }

  const tableSubscription = subscribeTable((snapshot) => {
    const previousCanOpenOrder = canOpenActiveOrder(tableSnapshot)
    const previousMode = resolveTableMode(tableSnapshot)
    const nextCanOpenOrder = canOpenActiveOrder(snapshot)
    tableSnapshot = snapshot
    const nextMode = resolveTableMode(snapshot)
    setCartTableToken(nextMode === 'active-table' ? snapshot.tableToken : null)
    const presentation = formatTableStatus(snapshot)
    refs.statusTitle.textContent = presentation.title
    refs.statusDetails.textContent = presentation.details ?? ''
    refs.app.dataset.tableSeverity = presentation.severity
    if (nextMode !== 'active-table') {
      staffCallActive = false
    }
    renderShellActions()
    refreshVisitExitAvailability()
    const routeName = resolveRoute().name
    if (
      rerenderForTableChange &&
      (previousMode !== nextMode ||
        ((routeName === 'cart' || routeName === 'order') && previousCanOpenOrder !== nextCanOpenOrder))
    ) {
      rerenderForTableChange()
    }
  })

  const disposables: Array<() => void> = []
  const cartSubscription = subscribeCart(() => {
    renderShellActions()
  })
  renderShellActions()
  refreshVisitExitAvailability()

  const handleStaffCallState = (event: Event) => {
    const detail = (event as CustomEvent<{ active?: boolean; venueId?: number | null }>).detail
    const nextActive =
      detail?.active === true &&
      resolveTableMode(tableSnapshot) === 'active-table' &&
      (!detail.venueId || detail.venueId === tableSnapshot.venueId)
    if (staffCallActive === nextActive) return
    staffCallActive = nextActive
    renderShellActions()
    refreshVisitExitAvailability()
  }
  window.addEventListener('hookah:guest-staff-call-state', handleStaffCallState)
  disposables.push(() => window.removeEventListener('hookah:guest-staff-call-state', handleStaffCallState))

  const historyStack: string[] = []
  let replaceNextHistoryEntry = false
  let suppressNextHistoryUpdate = false

  const navigate = (hash: string, options?: { replace?: boolean }) => {
    if (window.location.hash === hash) {
      return
    }
    if (options?.replace) {
      replaceNextHistoryEntry = true
      const nextUrl = new URL(window.location.href)
      nextUrl.hash = hash
      window.history.replaceState(null, '', `${nextUrl.pathname}${nextUrl.search}${nextUrl.hash}`)
      window.dispatchEvent(new Event('hashchange'))
      return
    }
    window.location.hash = hash
  }

  const navigateCatalogButton = () => {
    if (resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId) {
      navigate(`#/venue/${tableSnapshot.venueId}`, { replace: true })
      return
    }
    navigate('#/catalog', { replace: true })
  }
  const navigateSecondaryGuestScreen = (hash: string) => {
    const route = resolveRoute()
    const shouldPushFromTableRoot = resolveTableMode(tableSnapshot) === 'active-table' && route.name === 'venue'
    navigate(hash, shouldPushFromTableRoot ? undefined : { replace: true })
  }
  const resolveGuestRootHash = () =>
    resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId ? `#/venue/${tableSnapshot.venueId}` : '#/catalog'
  const isCurrentGuestRoot = () => {
    const route = resolveRoute()
    if (resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId) {
      return route.name === 'venue' && route.venueId === tableSnapshot.venueId && !route.openStaffCall
    }
    return route.name === 'catalog'
  }
  const replaceHashWithoutHistoryUpdate = (targetHash: string) => {
    if (window.location.hash === targetHash) {
      return
    }
    suppressNextHistoryUpdate = true
    const nextUrl = new URL(window.location.href)
    nextUrl.hash = targetHash
    window.history.replaceState(null, '', `${nextUrl.pathname}${nextUrl.search}${nextUrl.hash}`)
    window.dispatchEvent(new Event('hashchange'))
  }

  const routeListeners = new Set<() => void>()
  const notifyRouteChange = () => {
    routeListeners.forEach((listener) => listener())
  }

  const updateHistory = (hash: string) => {
    if (!hash || hash === '#') {
      return
    }
    if (suppressNextHistoryUpdate) {
      suppressNextHistoryUpdate = false
      return
    }
    if (replaceNextHistoryEntry) {
      replaceNextHistoryEntry = false
      if (historyStack.length === 0) {
        historyStack.push(hash)
        return
      }
      const nextRoute = resolveRouteNameFromHash(hash)
      const lastRoute = resolveRouteNameFromHash(historyStack[historyStack.length - 1])
      const prevRoute = resolveRouteNameFromHash(historyStack[historyStack.length - 2])
      if (isTabFlowRoute(nextRoute) && isTabFlowRoute(lastRoute) && !isTabFlowRoute(prevRoute)) {
        historyStack.push(hash)
        return
      }
      historyStack[historyStack.length - 1] = hash
      const prev = historyStack[historyStack.length - 2]
      if (prev === hash) {
        historyStack.pop()
      }
      return
    }
    if (historyStack.length === 0) {
      historyStack.push(hash)
      return
    }
    const last = historyStack[historyStack.length - 1]
    if (hash === last) {
      return
    }
    const prev = historyStack[historyStack.length - 2]
    if (prev === hash) {
      historyStack.pop()
      return
    }
    historyStack.push(hash)
  }

  const openQrScanner = () => {
    const telegramContext = getTelegramContext()
    const webApp = telegramContext.webApp
    const scanQr = getTelegramQrScanner(webApp)
    const fallbackMessage =
      'Откройте камеру телефона и отсканируйте QR на столе или нажмите «Я за столом / У меня QR» в боте.'
    if (!scanQr) {
      refs.statusDetails.textContent = fallbackMessage
      webApp?.showAlert?.(fallbackMessage)
      return
    }
    try {
      scanQr({ text: 'Наведите камеру на QR стола' }, (scannedText) => {
        const token = extractTableTokenFromScannedText(scannedText)
        if (!token) {
          webApp?.showAlert?.('QR не содержит token стола.')
          return false
        }
        try {
          webApp?.closeScanQrPopup?.()
        } catch {
          // ignore scanner close errors
        }
        const nextUrl = new URL(window.location.href)
        tableContextQueryParamKeys.forEach((key) => nextUrl.searchParams.delete(key))
        nextUrl.searchParams.set('table_token', token)
        window.history.replaceState(null, '', `${nextUrl.pathname}${nextUrl.search}${nextUrl.hash}`)
        void refreshTableContext({ forceResolveSession: true })
          .then(() => {
            const snapshot = getTableContext()
            if (resolveRoute().name !== 'catalog') {
              return
            }
            if (snapshot.status === 'resolved' && snapshot.venueId && snapshot.tableSessionActive) {
              navigate(`#/venue/${snapshot.venueId}`)
            }
          })
          .catch(() => undefined)
        return true
      })
    } catch {
      refs.statusDetails.textContent = fallbackMessage
      webApp?.showAlert?.('Не удалось открыть QR-сканер.')
    }
  }
  const endTableVisit = () => {
    const snapshot = tableSnapshot
    if (
      resolveTableMode(snapshot) !== 'active-table' ||
      !snapshot.tableToken ||
      !snapshot.tableSessionId ||
      snapshot.tableSessionId <= 0
    ) {
      clearTableContextSearchParams()
      clearCurrentTableContext()
      setCartTableToken(null)
      staffCallActive = false
      navigate('#/catalog', { replace: true })
      return
    }
    refs.statusDetails.textContent = 'Завершаем визит…'
    void guestEndTableSession(
      backendUrl,
      {
        tableToken: snapshot.tableToken,
        tableSessionId: snapshot.tableSessionId
      },
      { isDebug, getAccessToken, clearSession }
    )
      .then((result) => {
        if (!result.ok) {
          if (result.error.status === 403 || result.error.status === 404) {
            const message = 'Контекст стола уже недоступен. Отсканируйте QR заново.'
            refs.statusDetails.textContent = message
            showTelegramAlert(message)
            clearTableContextSearchParams()
            clearCurrentTableContext()
            setCartTableToken(null)
            staffCallActive = false
            navigate('#/catalog', { replace: true })
            return
          }
          const message = result.error.message || 'Не удалось завершить визит. Попробуйте позже.'
          refs.statusDetails.textContent = message
          showTelegramAlert(message)
          return
        }
        if (!result.data.ended) {
          const message = result.data.message || 'Сейчас визит нельзя завершить.'
          refs.statusDetails.textContent = message
          showTelegramAlert(message)
          return
        }
        const message = result.data.message || 'Визит завершён. Чтобы снова заказать за столом, отсканируйте QR.'
        refs.statusDetails.textContent = message
        showTelegramAlert(message)
        clearTableContextSearchParams()
        clearCurrentTableContext()
        setCartTableToken(null)
        staffCallActive = false
        navigate('#/catalog', { replace: true })
      })
      .catch(() => {
        const message = 'Не удалось завершить визит. Попробуйте позже.'
        refs.statusDetails.textContent = message
        showTelegramAlert(message)
      })
  }
  handleGuestAction = (action) => {
    switch (action) {
      case 'catalog':
      case 'menu':
        navigateCatalogButton()
        return
      case 'qr':
        openQrScanner()
        return
      case 'cart':
        navigateSecondaryGuestScreen('#/cart')
        return
      case 'order':
        navigateSecondaryGuestScreen('#/order')
        return
      case 'staff':
        if (resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId) {
          navigateSecondaryGuestScreen(`#/venue/${tableSnapshot.venueId}?staff=1`)
          return
        }
        navigate('#/catalog', { replace: true })
        return
      case 'leave':
        endTableVisit()
        return
      case 'account':
        navigateSecondaryGuestScreen('#/account')
        return
      case 'messages':
        navigateSecondaryGuestScreen('#/messages')
        return
      case 'support':
        if (currentRoute.name === 'venue' && currentRoute.venueId && resolveTableMode(tableSnapshot) !== 'active-table') {
          navigateSecondaryGuestScreen(`#/support?venueId=${currentRoute.venueId}`)
          return
        }
        navigateSecondaryGuestScreen('#/support')
        return
      case 'refresh':
        void refreshTableContext()
        return
      default:
        return
    }
  }
  renderShellActions()
  const refreshResolvedTableContext = () => {
    if (tableSnapshot.status === 'resolved' && tableSnapshot.tableToken) {
      void refreshTableContext()
    }
  }
  const handleVisibilityChange = () => {
    if (document.visibilityState === 'visible') {
      refreshResolvedTableContext()
    }
  }
  window.addEventListener('focus', refreshResolvedTableContext)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  disposables.push(() => {
    window.removeEventListener('focus', refreshResolvedTableContext)
    document.removeEventListener('visibilitychange', handleVisibilityChange)
  })
  let currentDispose: (() => void) | null = null

  const router = {
    getRouteName: () => currentRoute.name,
    navigate,
    back: () => {
      if (historyStack.length > 1) {
        historyStack.pop()
        const targetHash = historyStack[historyStack.length - 1]
        replaceHashWithoutHistoryUpdate(targetHash)
        return
      }
      if (!isCurrentGuestRoot()) {
        const rootHash = resolveGuestRootHash()
        historyStack.splice(0, historyStack.length, rootHash)
        replaceHashWithoutHistoryUpdate(rootHash)
        return
      }
      getTelegramContext().webApp?.close?.()
    },
    canGoBack: () => historyStack.length > 1 || !isCurrentGuestRoot(),
    subscribe: (handler: () => void) => {
      routeListeners.add(handler)
      return () => routeListeners.delete(handler)
    }
  }

  const unbindBackButton = bindTelegramBackButton(router)

  const render = () => {
    const route = resolveRoute()
    currentRoute = route
    renderShellActions()
    refreshVisitExitAvailability()
    currentDispose?.()
    if (route.name === 'catalog' && resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId) {
      navigate(`#/venue/${tableSnapshot.venueId}`, { replace: true })
      return
    }
    const endedTableSession =
      tableSnapshot.status === 'resolved' && Boolean(tableSnapshot.tableToken) && !tableSnapshot.tableSessionActive
    if ((route.name === 'venue' || route.name === 'cart' || route.name === 'order') && endedTableSession) {
      currentDispose = renderCatalogScreen({
        root: refs.content,
        backendUrl,
        isDebug,
        onOpenVenue: (venueId) => navigate(`#/venue/${venueId}`),
        onBookVenue: (venueId) => navigate(`#/bookings?venueId=${venueId}`)
      })
      return
    }
    if ((route.name === 'cart' || route.name === 'order') && !canOpenActiveOrder(tableSnapshot)) {
      currentDispose = renderPlaceholder(
        refs.content,
        'Нужен QR стола',
        'Корзина и заказ доступны после сканирования QR-кода стола.'
      )
      return
    }
    currentDispose = renderRouteContent(
      route,
      refs.content,
      backendUrl,
      isDebug,
      (venueId) => {
        navigate(`#/venue/${venueId}`)
      },
      () => {
        navigate('#/order')
      },
      (venueId) => {
        if (venueId) {
          navigate(`#/venue/${venueId}`)
          return
        }
        navigate('#/catalog')
      },
      () => navigate('#/catalog'),
      () => {
        if (resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId) {
          navigate(`#/venue/${tableSnapshot.venueId}`)
          return
        }
        navigate('#/catalog')
      },
      () => {
        if (resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId) {
          navigate(`#/venue/${tableSnapshot.venueId}?staff=1`)
          return
        }
        navigate('#/catalog')
      },
      () => {
        const telegramContext = getTelegramContext()
        const result = openBotChat(telegramContext, {
          tableToken: tableSnapshot.tableToken,
          tableSessionId: tableSnapshot.tableSessionId
        })
        if (result.ok) {
          return { ok: true }
        }
        return {
          ok: false,
          message: telegramContext.botUsername
            ? `Не удалось открыть чат автоматически. Откройте @${telegramContext.botUsername} вручную.`
            : 'Не удалось открыть чат автоматически. Откройте чат с ботом вручную.'
        }
      },
      tableSnapshot,
      resolveTableMode(tableSnapshot) === 'active-table'
    )
  }
  rerenderForTableChange = render

  const onHashChange = () => {
    updateHistory(window.location.hash || '#/catalog')
    render()
    if (isCurrentGuestRoot()) {
      const rootHash = resolveGuestRootHash()
      historyStack.splice(0, historyStack.length, rootHash)
    }
    notifyRouteChange()
  }

  window.addEventListener('hashchange', onHashChange)
  updateHistory(window.location.hash || '#/catalog')
  render()
  if (isCurrentGuestRoot()) {
    const rootHash = resolveGuestRootHash()
    historyStack.splice(0, historyStack.length, rootHash)
  }
  notifyRouteChange()

  return () => {
    window.removeEventListener('hashchange', onHashChange)
    exitCheckAbort?.abort()
    currentDispose?.()
    unbindBackButton()
    disposables.forEach((dispose) => dispose())
    cartSubscription()
    tableSubscription()
  }
}
