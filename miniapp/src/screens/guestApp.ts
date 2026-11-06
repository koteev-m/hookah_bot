import './guestExperience.css'
import { clearVenueBrowse, getVenueBrowseId, getVenueBrowsePayload, rememberVenueBrowse } from '../shared/state/venueBrowseContext'
import { requestApi } from '../shared/api/request'
import { getBackendBaseUrl } from '../shared/api/backend'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { guestEndTableSession, guestGetActiveOrder, guestGetStaffCallStatus, guestGetTabs } from '../shared/api/guestApi'
import { isDebugEnabled } from '../shared/debug'
import {
  cancelPendingTableRefresh,
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
import { extractTableTokenFromScannedText, tableTokenQueryParamKeys } from '../shared/validation/tableToken'
import { append, el, on } from '../shared/ui/dom'
import { renderCatalogScreen } from './catalog'
import { renderCartScreen } from './cart'
import type { GuestProfileDraft } from './guestProfile'
import { renderGuestAccountScreen } from './guestAccount'
import { renderGuestBookingsScreen } from './guestBookings'
import { renderGuestSupportThreadsScreen, type GuestConversationState } from './guestSupportThreads'
import { renderGuestVenueScreen } from './guestVenue'
import { renderOrderScreen } from './order'

type GuestAppOptions = {
  root: HTMLDivElement | null
}

type RouteName = 'catalog' | 'venue' | 'cart' | 'order' | 'bookings' | 'account' | 'messages' | 'support'
type GuestActionId =
  | 'catalog'
  | 'qr'
  | 'cart'
  | 'order'
  | 'staff'
  | 'leave'
  | 'account'
  | 'messages'
type GuestTableMode = 'no-table' | 'active-table' | 'ended-table'

type Route = {
  name: RouteName
  venueId: number | null
  threadId: number | null
  openStaffCall: boolean
  cartLineRef: string | null
}

type GuestRefs = {
  app: HTMLDivElement
  statusCard: HTMLDivElement
  statusTitle: HTMLParagraphElement
  visitState: HTMLParagraphElement
  statusDetails: HTMLParagraphElement
  primaryActions: HTMLDivElement
  nav: HTMLElement
  content: HTMLDivElement
}

const tableContextQueryParamKeys = [...tableTokenQueryParamKeys, 'tableSessionId', 'table_session_id'] as const
const navigationActions: GuestActionId[] = ['catalog', 'qr', 'messages', 'account']

function ensureDefaultHash() {
  const hash = window.location.hash
  if (!hash || hash === '#' || hash === '#/') {
    window.history.replaceState(window.history.state, '', '#/catalog')
  }
}

function resolveRoute(): Route {
  const rawHash = window.location.hash.replace(/^#/, '')
  const cleaned = rawHash.startsWith('/') ? rawHash.slice(1) : rawHash
  if (!cleaned) {
    return { name: 'catalog', venueId: null, threadId: null, openStaffCall: false, cartLineRef: null }
  }
  const [pathPart, queryPart] = cleaned.split('?')
  const segments = pathPart.split('/').filter(Boolean)
  const route = segments[0] as RouteName | undefined
  if (!route || !['catalog', 'venue', 'cart', 'order', 'bookings', 'account', 'messages', 'support'].includes(route)) {
    return { name: 'catalog', venueId: null, threadId: null, openStaffCall: false, cartLineRef: null }
  }
  if (route === 'venue') {
    const venueIdFromPath = parsePositiveInt(segments[1])
    const params = new URLSearchParams(queryPart ?? '')
    const venueIdFromQuery = parsePositiveInt(params.get('id'))
    return {
      name: 'venue',
      venueId: venueIdFromPath ?? venueIdFromQuery ?? null,
      threadId: null,
      openStaffCall: params.get('staff') === '1',
      cartLineRef: params.get('cartLineRef')?.trim() || null
    }
  }
  if (route === 'bookings' || route === 'messages' || route === 'support') {
    const params = new URLSearchParams(queryPart ?? '')
    return {
      name: route,
      venueId: parsePositiveInt(params.get('venueId')) ?? parsePositiveInt(params.get('venue_id')) ?? null,
      threadId: parsePositiveInt(params.get('threadId')) ?? parsePositiveInt(params.get('thread_id')) ?? null,
      openStaffCall: false,
      cartLineRef: null
    }
  }
  if (route === 'cart') {
    const params = new URLSearchParams(queryPart ?? '')
    return {
      name: route,
      venueId: null,
      threadId: null,
      openStaffCall: false,
      cartLineRef: params.get('focusLineRef')?.trim() || null
    }
  }
  return { name: route, venueId: null, threadId: null, openStaffCall: false, cartLineRef: null }
}

function clearTableContextSearchParams() {
  const nextUrl = new URL(window.location.href)
  tableContextQueryParamKeys.forEach((key) => nextUrl.searchParams.delete(key))
  window.history.replaceState(window.history.state, '', `${nextUrl.pathname}${nextUrl.search}${nextUrl.hash}`)
}

function showTelegramAlert(message: string) {
  try {
    getTelegramContext().webApp?.showAlert?.(message)
  } catch {
    // Telegram alert availability differs between browser shims and real clients.
  }
}

function buildGuestShell(root: HTMLDivElement): GuestRefs {
  const app = el('div', { className: 'app-shell guest-shell' })
  const header = el('header', { className: 'guest-visit-header' })
  const statusCard = el('div', { className: 'table-status' })
  const statusTitle = el('p', { className: 'table-status-title', text: '' })
  const visitState = el('p', { className: 'guest-visit-state', text: 'Активное посещение' })
  visitState.hidden = true
  const statusDetails = el('p', { className: 'table-status-details', text: '' })
  const primaryActions = el('div', { className: 'table-actions' }) as HTMLDivElement
  append(statusCard, visitState, statusTitle, statusDetails, primaryActions)

  const nav = el('nav', { className: 'guest-nav' })
  nav.setAttribute('aria-label', 'Гостевая навигация')
  statusDetails.setAttribute('role', 'status')
  header.appendChild(statusCard)

  const content = el('div', { className: 'container app-content' })
  append(app, header, content, nav)
  root.replaceChildren(app)

  return {
    app,
    statusCard,
    statusTitle,
    visitState,
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
  onNavigateMenu: (venueId: number | null, focusHeading?: boolean) => void,
  onNavigateCatalog: () => void,
  onBack: () => void,
  onOpenVenueStaffCall: () => void,
  onOpenSupportBot: () => { ok: true } | { ok: false; message: string },
  tableSnapshot: ReturnType<typeof getTableContext>,
  hasTableContext: boolean,
  profileDraft: GuestProfileDraft,
  selectedVenueId: number | null,
  conversations: Record<'messages' | 'support', GuestConversationState>,
  onAskVenue: (venueId: number) => void,
  navigate: (hash: string, options?: { replace?: boolean; focusVenue?: number }) => void,
  startVenueChat: boolean
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
        focusHeading: window.history.state?.hookahFocusVenue === route.venueId,
        replaceCartLineRef: route.cartLineRef,
        onOpenCatalog: onNavigateCatalog,
        onOpenHelp: (venueId) => { window.location.hash = `#/support?venueId=${venueId}` },
        onBack,
        onBookVenue: (venueId) => {
          window.location.hash = `#/bookings?venueId=${venueId}`
        },
        onAskVenue
      })
    case 'cart':
      return renderCartScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        onNavigateOrder,
        initialFocusLineRef: route.cartLineRef,
        onNavigateMenu: (focusHeading) => onNavigateMenu(tableSnapshot.venueId, focusHeading),
        onNavigateOptionReplacement: (cartLineRef) => {
          if (!tableSnapshot.venueId) return
          window.location.hash = `#/venue/${tableSnapshot.venueId}?cartLineRef=${encodeURIComponent(cartLineRef)}`
        }
      })
    case 'order':
      return renderOrderScreen({ root: screenRoot, backendUrl, isDebug, onNavigateMenu })
    case 'bookings':
      return renderGuestBookingsScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        venueId: route.venueId,
        onBack
      })
    case 'account':
      return renderGuestAccountScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        profileDraft,
        tableContext: tableSnapshot,
        onBack,
        onOpenHelp: () => { window.location.hash = '#/support' },
        onOpenBookings: () => {
          window.location.hash = '#/bookings'
        },
        onOpenVenue,
        onBookVenue: (venueId) => {
          window.location.hash = `#/bookings?venueId=${venueId}`
        },
        onAskVenue,
        selectedVenueId,
        onNavigateCart: () => {
          window.location.hash = '#/cart'
        }
      })
    case 'messages':
      return renderGuestSupportThreadsScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        screenMode: 'messages',
        hasTableContext,
        tableSnapshot,
        onBack,
        onOpenVenueStaffCall,
        onOpenBot: onOpenSupportBot,
        initialThreadId: route.threadId,
        createVenueChatVenueId: route.venueId,
        startVenueChat,
        conversationState: conversations.messages,
        onOpenThread: (threadId, replace) => navigate(`#/messages?threadId=${threadId}`, { replace }),
        onOpenList: () => window.history.state?.hookahGuestPrevious === '#/messages'
          ? onBack() : navigate('#/messages', { replace: true })
      })
    case 'support':
      return renderGuestSupportThreadsScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        screenMode: 'tickets',
        hasTableContext,
        tableSnapshot,
        onBack,
        onOpenVenueStaffCall,
        onOpenBot: onOpenSupportBot,
        initialThreadId: route.threadId,
        prefillSupportVenueId: route.venueId,
        conversationState: conversations.support,
        onOpenThread: (threadId) => navigate(`#/support?threadId=${threadId}`),
        onOpenList: () => window.history.state?.hookahGuestPrevious?.startsWith('#/support')
          ? onBack() : navigate('#/support', { replace: true })
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
        onAskVenue
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

function updateGuestShellActions(
  refs: GuestRefs,
  route: Route,
  selectedVenue: number | null,
  activeVisit: boolean,
  cartQty: number,
  staffCallActive: boolean,
  canLeaveTable: boolean,
  onAction: (action: GuestActionId) => void
) {
  const labels: Record<GuestActionId, string> = {
    catalog: selectedVenue ? 'Заведение' : 'Заведения',
    qr: 'QR',
    messages: 'Чаты',
    account: 'Профиль',
    cart: cartQty > 0 ? `Корзина (${cartQty})` : 'Корзина',
    order: 'Мои заказы',
    staff: staffCallActive ? 'Вызов активен' : 'Персонал',
    leave: 'Завершить визит'
  }
  const selected: Partial<Record<RouteName, GuestActionId>> = {
    catalog: 'catalog', venue: 'catalog', cart: 'cart', order: 'order',
    messages: 'messages', account: 'account', bookings: 'account', support: 'account'
  }
  const makeButton = (action: GuestActionId) => {
    const button = el('button', { className: 'button-secondary', text: labels[action] })
    button.type = 'button'
    button.dataset.action = action
    if (selected[route.name] === action) button.setAttribute('aria-current', 'page')
    button.addEventListener('click', () => onAction(action))
    return button
  }
  const visitActions: GuestActionId[] = activeVisit ? ['cart', 'order', 'staff'] : []
  if (activeVisit && canLeaveTable) visitActions.push('leave')
  refs.primaryActions.replaceChildren(...visitActions.map(makeButton))
  refs.nav.replaceChildren(...navigationActions.map(makeButton))
}

export function mountGuestApp(options: GuestAppOptions): () => void {
  const payload = getVenueBrowsePayload()
  if (payload === null || !options.root) return mountGuestScreens(options)
  const controller = new AbortController()
  let dispose: (() => void) | undefined
  const message = el('p', { text: 'Открываем заведение…' })
  options.root.replaceChildren(message)
  const open = async () => {
    const result = /^venue_[1-9a-z][0-9a-z]{0,12}$/.test(payload)
      ? await requestApi<{ venueId: number }>(getBackendBaseUrl(), `/api/guest/venue-entry/${encodeURIComponent(payload)}`,
        { signal: controller.signal }, { isDebug: isDebugEnabled(), getAccessToken, clearSession })
      : null
    if (controller.signal.aborted) return
    if (!result?.ok) {
      message.textContent = 'Заведение недоступно. Проверьте ссылку или попробуйте позже.'
      const catalog = el('button', { text: 'Все заведения Hookah Tootah' })
      catalog.addEventListener('click', () => {
        clearVenueBrowse()
        window.history.replaceState(window.history.state, '', `${window.location.pathname}${window.location.search}#/catalog`)
        dispose = mountGuestScreens(options)
      })
      options.root?.appendChild(catalog)
      return
    }
    const hash = window.location.hash
    rememberVenueBrowse(payload, result.data.venueId)
    if (!/^#\/(venue|cart|order|bookings|account|messages|support)(?:[/?]|$)/.test(hash)) {
      const url = new URL(window.location.href)
      url.hash = `#/venue/${result.data.venueId}`
      window.history.replaceState(window.history.state, '', url)
    }
    dispose = mountGuestScreens(options)
  }
  void open()
  return () => { controller.abort(); dispose?.() }
}

function mountGuestScreens(options: GuestAppOptions) {
  const { root } = options
  if (!root) return () => undefined
  initTableContext()
  ensureDefaultHash()
  const backendUrl = getBackendBaseUrl()
  const isDebug = isDebugEnabled()

  const refs = buildGuestShell(root)
  let tableSnapshot = getTableContext()
  let currentRoute: Route = resolveRoute()
  let catalogChosen = window.history.state?.hookahGuestCatalog === true && window.location.hash.startsWith('#/catalog')
  let mounted = true
  let selectedVenue = currentRoute.venueId ?? getVenueBrowseId()
  const profileDraft: GuestProfileDraft = {}
  let draftOwner = getTelegramContext().telegramUserId
  const conversations: Record<'messages' | 'support', GuestConversationState> = {
    messages: { filter: 'active', listScroll: 0, drafts: new Map() },
    support: { filter: 'active', listScroll: 0, drafts: new Map() }
  }
  let pendingVenueChat: number | null = null
  let visitExitPending = false
  const visitReturn = el('button', { className: 'button-secondary guest-return-visit' })
  visitReturn.hidden = true
  refs.statusCard.after(visitReturn)
  let staffCallActive = false
  let canLeaveTable = false
  let handleGuestAction: (action: GuestActionId) => void = () => undefined
  const renderShellActions = () => {
    const activeVisit = resolveTableMode(tableSnapshot) === 'active-table'
    const differentVenue = currentRoute.venueId !== null && currentRoute.venueId !== tableSnapshot.venueId
    const showVisit = activeVisit && !differentVenue && ['venue', 'cart', 'order'].includes(currentRoute.name)
    const presentation = formatTableStatus(tableSnapshot)
    refs.statusCard.hidden = !showVisit && !refs.statusDetails.textContent
    refs.statusTitle.textContent = showVisit ? presentation.title : ''
    refs.statusCard.dataset.activeVisit = String(showVisit && tableSnapshot.tableSessionActive)
    refs.visitState.hidden = !showVisit
    refs.statusDetails.hidden = showVisit && !refs.statusDetails.textContent
    visitReturn.hidden = !activeVisit || showVisit
    visitReturn.textContent = `К столу · ${tableSnapshot.venueName ?? 'Текущее заведение'}`
    updateGuestShellActions(
      refs,
      currentRoute,
      selectedVenue ?? (activeVisit ? tableSnapshot.venueId : null),
      showVisit,
      getCartSnapshot().totalQty,
      staffCallActive,
      canLeaveTable && !visitExitPending,
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

  let resolvingQr = false
  const tableSubscription = subscribeTable((snapshot) => {
    const previousCanOpenOrder = canOpenActiveOrder(tableSnapshot)
    const previousMode = resolveTableMode(tableSnapshot)
    const nextCanOpenOrder = canOpenActiveOrder(snapshot)
    tableSnapshot = snapshot
    const nextMode = resolveTableMode(snapshot)
    setCartTableToken(nextMode === 'active-table' ? snapshot.tableToken : null)
    const presentation = formatTableStatus(snapshot)
    if (!resolvingQr) {
      const showTableMessage = nextMode === 'ended-table' || snapshot.status === 'resolving' || presentation.severity === 'error'
      refs.statusDetails.textContent = showTableMessage
        ? [presentation.title, presentation.details].filter(Boolean).join(' ')
        : ''
    }
    refs.app.dataset.tableSeverity = presentation.severity
    if (nextMode !== 'active-table') {
      staffCallActive = false
    }
    renderShellActions()
    refreshVisitExitAvailability()
    const routeName = resolveRoute().name
    if (
      rerenderForTableChange &&
      (previousMode !== nextMode && routeName !== 'messages' && routeName !== 'account' ||
        ((routeName === 'cart' || routeName === 'order') && previousCanOpenOrder !== nextCanOpenOrder))
    ) {
      rerenderForTableChange()
    }
  })

  const disposables: Array<() => void> = []
  const viewport = window.visualViewport
  if (viewport) {
    const updateKeyboard = () => {
      const active = document.activeElement
      const editing = active instanceof HTMLElement && refs.app.contains(active) &&
        active.matches('textarea, input:not([type=button]):not([type=checkbox])')
      const keyboardOpen = editing && viewport.scale === 1 && window.innerHeight - viewport.height > 120
      refs.app.toggleAttribute('data-keyboard-open', keyboardOpen)
      if (keyboardOpen) active.scrollIntoView({ block: 'nearest' })
    }
    viewport.addEventListener('resize', updateKeyboard)
    disposables.push(() => viewport.removeEventListener('resize', updateKeyboard))
  }
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

  // Browser and Telegram use the same history entries, including old hash links.
  const historyKey = 'hookahGuestDepth'
  const historyDepth = () => Number(window.history.state?.[historyKey] ?? 0)
  if (!Number.isInteger(window.history.state?.[historyKey])) {
    window.history.replaceState({ ...window.history.state, [historyKey]: 0 }, '')
  }
  let currentDepth = historyDepth()
  let currentHash = window.location.hash
  const navigate = (hash: string, options?: { replace?: boolean; focusVenue?: number }) => {
    if (window.location.hash === hash) return
    const url = new URL(window.location.href)
    url.hash = hash
    const state = {
      ...window.history.state,
      [historyKey]: currentDepth + (options?.replace ? 0 : 1),
      hookahFocusVenue: options?.focusVenue ?? null,
      hookahGuestCatalog: hash.startsWith('#/catalog') && catalogChosen,
      hookahGuestPrevious: options?.replace ? window.history.state?.hookahGuestPrevious : currentHash
    }
    if (options?.replace) window.history.replaceState(state, '', url)
    else window.history.pushState(state, '', url)
    window.dispatchEvent(new Event('hashchange'))
  }
  const askVenue = (venueId: number) => {
    pendingVenueChat = venueId
    navigate(`#/messages?venueId=${venueId}`)
  }
  const openCatalog = () => {
    if (getVenueBrowseId()) clearVenueBrowse()
    catalogChosen = true
    navigate('#/catalog')
  }
  const openVisit = () => {
    if (!tableSnapshot.venueId) return
    selectedVenue = tableSnapshot.venueId
    navigate(`#/venue/${tableSnapshot.venueId}`)
  }
  visitReturn.addEventListener('click', openVisit)

  const routeListeners = new Set<() => void>()
  const notifyRouteChange = () => routeListeners.forEach((listener) => listener())
  const fallbackBackHash = () => {
    if (currentRoute.name === 'venue' && /[?&](browseMenu|media)=/.test(window.location.hash)) {
      return `#/venue/${currentRoute.venueId}`
    }
    if (currentRoute.name === 'catalog') return '#/catalog'
    if (currentRoute.name === 'account' && window.location.hash !== '#/account') {
      const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
      if (params.has('visit')) {
        params.delete('visit')
        return `#/account/history${params.size ? `?${params}` : ''}`
      }
      return '#/account'
    }
    if (currentRoute.name === 'support' || (currentRoute.name === 'bookings' && !currentRoute.venueId)) return '#/account'
    if (currentRoute.name === 'messages' && (currentRoute.threadId || currentRoute.venueId)) return '#/messages'
    return selectedVenue && currentRoute.name !== 'venue' ? `#/venue/${selectedVenue}` : '#/catalog'
  }
  const canFallBack = () => window.location.hash !== '#/catalog' &&
    !(currentRoute.name === 'venue' && currentRoute.venueId === (getVenueBrowseId() ?? tableSnapshot.venueId) &&
      !window.location.hash.includes('?'))

  let cancelQrScan: (() => void) | null = null
  disposables.push(() => cancelQrScan?.())
  const openQrScanner = () => {
    if (resolvingQr || visitExitPending) return
    if (cancelQrScan) return
    refs.statusDetails.textContent = ''
    renderShellActions()
    const telegramContext = getTelegramContext()
    const webApp = telegramContext.webApp
    const scanQr = getTelegramQrScanner(webApp)
    const fallbackMessage =
      'Откройте камеру телефона и отсканируйте QR на столе или нажмите «Я за столом / У меня QR» в боте.'
    if (!scanQr) {
      refs.statusDetails.textContent = fallbackMessage
      renderShellActions()
      webApp?.showAlert?.(fallbackMessage)
      return
    }
    let consumed = false
    const finishScan = () => {
      consumed = true
      webApp?.offEvent?.('scanQrPopupClosed', finishScan)
      cancelQrScan = null
    }
    cancelQrScan = finishScan
    try {
      webApp?.onEvent?.('scanQrPopupClosed', finishScan)
      scanQr({ text: 'Наведите камеру на QR стола' }, (scannedText) => {
        if (consumed) return true
        const token = extractTableTokenFromScannedText(scannedText, {
          miniAppUrl: window.location.href,
          botUsername: telegramContext.botUsername
        })
        if (!token) {
          webApp?.showAlert?.('QR-код не содержит токен стола.')
          return false
        }
        finishScan()
        resolvingQr = true
        try {
          webApp?.closeScanQrPopup?.()
        } catch {
          // ignore scanner close errors
        }
        void refreshTableContext({ scannedToken: token })
          .then((snapshot) => {
            if (!mounted || !snapshot) return
            if (!snapshot.orderAllowed) {
              const failure = formatTableStatus(snapshot)
              const message = [failure.title, failure.details].filter(Boolean).join(' ')
              refs.statusDetails.textContent = message
              renderShellActions()
              showTelegramAlert(message)
              return
            }
            if (getVenueBrowseId() !== null) clearVenueBrowse()
            catalogChosen = false
            const nextUrl = new URL(window.location.href)
            tableContextQueryParamKeys.forEach((key) => nextUrl.searchParams.delete(key))
            nextUrl.searchParams.set('table_token', token)
            window.history.replaceState(window.history.state, '', `${nextUrl.pathname}${nextUrl.search}${nextUrl.hash}`)
            if (snapshot.status === 'resolved' && snapshot.venueId && snapshot.tableSessionActive) {
              selectedVenue = snapshot.venueId
              navigate(`#/venue/${snapshot.venueId}`, { replace: true })
            }
          })
          .catch(() => showTelegramAlert('Не удалось загрузить стол. Попробуйте позже.'))
          .finally(() => { resolvingQr = false })
        return true
      })
    } catch {
      finishScan()
      refs.statusDetails.textContent = fallbackMessage
      renderShellActions()
      webApp?.showAlert?.('Не удалось открыть QR-сканер.')
    }
  }
  const endTableVisit = () => {
    if (visitExitPending) return
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
    if (!window.confirm(`Завершить визит: ${snapshot.venueName ?? 'заведение'}, стол №${snapshot.tableNumber ?? '—'}? Вы выйдете из текущей сессии стола и корзины. Для нового заказа понадобится снова отсканировать QR.`)) return
    visitExitPending = true
    cancelQrScan?.()
    cancelPendingTableRefresh()
    refs.statusDetails.textContent = 'Завершаем визит…'
    renderShellActions()
    void guestEndTableSession(
      backendUrl,
      {
        tableToken: snapshot.tableToken,
        tableSessionId: snapshot.tableSessionId
      },
      { isDebug, getAccessToken, clearSession }
    )
      .then((result) => {
        if (!mounted || tableSnapshot.tableToken !== snapshot.tableToken ||
          tableSnapshot.tableSessionId !== snapshot.tableSessionId) return
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
      .finally(() => { visitExitPending = false; renderShellActions() })
  }
  handleGuestAction = (action) => {
    switch (action) {
      case 'catalog': {
        const venue = selectedVenue ?? (resolveTableMode(tableSnapshot) === 'active-table' ? tableSnapshot.venueId : null)
        if (venue) {
          const showingActiveMenu = tableSnapshot.venueId === venue && tableSnapshot.orderAllowed && getVenueBrowseId() !== venue
          navigate(`#/venue/${venue}${showingActiveMenu ? '?browseMenu=0' : ''}`)
        } else {
          openCatalog()
        }
        return
      }
      case 'qr':
        openQrScanner()
        return
      case 'cart':
        navigate('#/cart')
        return
      case 'order':
        navigate('#/order')
        return
      case 'staff':
        if (resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId) {
          navigate(`#/venue/${tableSnapshot.venueId}?staff=1`)
          return
        }
        navigate('#/catalog', { replace: true })
        return
      case 'leave':
        endTableVisit()
        return
      case 'account':
        navigate('#/account')
        return
      case 'messages':
        navigate('#/messages')
        return
      default:
        return
    }
  }
  renderShellActions()
  const refreshResolvedTableContext = () => {
    if (!resolvingQr && !visitExitPending && tableSnapshot.status === 'resolved' && tableSnapshot.tableToken) {
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
      if (currentDepth > 0) window.history.back()
      else if (canFallBack()) navigate(fallbackBackHash(), { replace: true })
    },
    canGoBack: () => currentDepth > 0 || canFallBack(),
    subscribe: (handler: () => void) => {
      routeListeners.add(handler)
      return () => routeListeners.delete(handler)
    }
  }

  const unbindBackButton = bindTelegramBackButton(router)

  const render = () => {
    const route = resolveRoute()
    currentRoute = route
    const guestId = getTelegramContext().telegramUserId
    if (guestId !== draftOwner) {
      draftOwner = guestId
      delete profileDraft.name
      delete profileDraft.birthday
      Object.values(conversations).forEach((state) => {
        state.drafts.clear()
        state.filter = 'active'
        state.listScroll = 0
      })
    }
    if (route.name === 'venue' && route.venueId) selectedVenue = route.venueId
    if (route.name === 'catalog' && (catalogChosen || window.location.hash === '#/catalog/promotions')) selectedVenue = null
    renderShellActions()
    refreshVisitExitAvailability()
    currentDispose?.()
    if (route.name === 'catalog' && window.location.hash !== '#/catalog/promotions' && !catalogChosen && !getVenueBrowseId() && resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId) {
      navigate(`#/venue/${tableSnapshot.venueId}`, { replace: true })
      return
    }
    const endedTableSession =
      tableSnapshot.status === 'resolved' && Boolean(tableSnapshot.tableToken) && !tableSnapshot.tableSessionActive
    if ((route.name === 'cart' || route.name === 'order') && endedTableSession) {
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
    const startVenueChat = route.name === 'messages' && route.venueId !== null && pendingVenueChat === route.venueId
    pendingVenueChat = null
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
      (venueId, focusHeading) => {
        if (venueId) {
          navigate(`#/venue/${venueId}${getVenueBrowseId() === venueId ? '?browseMenu=1' : ''}`, {
            focusVenue: focusHeading ? venueId : undefined
          })
          return
        }
        openCatalog()
      },
      openCatalog,
      router.back,
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
      resolveTableMode(tableSnapshot) === 'active-table',
      profileDraft,
      selectedVenue ?? tableSnapshot.venueId,
      conversations,
      askVenue,
      navigate,
      startVenueChat
    )
  }
  rerenderForTableChange = render

  const onHashChange = () => {
    if (!Number.isInteger(window.history.state?.[historyKey])) {
      window.history.replaceState({
        ...window.history.state, [historyKey]: currentDepth + 1, hookahGuestPrevious: currentHash
      }, '')
    }
    currentDepth = historyDepth()
    currentHash = window.location.hash
    refs.statusDetails.textContent = ''
    render()
    notifyRouteChange()
  }

  window.addEventListener('hashchange', onHashChange)
  render()
  notifyRouteChange()

  return () => {
    mounted = false
    window.removeEventListener('hashchange', onHashChange)
    exitCheckAbort?.abort()
    currentDispose?.()
    unbindBackButton()
    disposables.forEach((dispose) => dispose())
    cartSubscription()
    tableSubscription()
  }
}
