import './guestExperience.css'
import { clearVenueBrowse, getVenueBrowseId, getVenueBrowsePayload, isPersonalVenueBrowse, rememberVenueBrowse } from '../shared/state/venueBrowseContext'
import { requestApi } from '../shared/api/request'
import { getBackendBaseUrl } from '../shared/api/backend'
import { clearSession, getAccessToken, getSessionBotUsername } from '../shared/api/auth'
import { guestEndTableSession, guestGetActiveOrder, guestGetStaffCallStatus, guestGetTabs } from '../shared/api/guestApi'
import { isDebugEnabled } from '../shared/debug'
import {
  cancelPendingTableRefresh,
  canRetryTableLoad,
  clearCurrentTableContext,
  formatTableStatus,
  getTableContext,
  initTableContext,
  isTemporaryError,
  pendingSdkRejoin,
  prepareVisitExit,
  startTableSelection,
  rememberSdkRejoin,
  sdkTokenDigest,
  refresh as refreshTableContext,
  retryTableLoad,
  subscribe as subscribeTable
} from '../shared/state/tableContext'
import { finishExit } from '../shared/state/visitSelection'
import { getCartSnapshot, resolveCartConflict, retryCartStorage, setCartTableToken, subscribeCart } from '../shared/state/cartStore'
import { parsePositiveInt } from '../shared/parse'
import { bindTelegramBackButton } from '../shared/telegramBackButton'
import { applicationHash, guestApplicationUrl, createGuestNavigation, guestRouteHash } from '../shared/guestNavigation'
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

function visitUrl(hash: string, token?: string | null, endedSessionId?: number | null) {
  const url = new URL(window.location.href)
  tableContextQueryParamKeys.forEach((key) => url.searchParams.delete(key))
  if (token) {
    url.searchParams.set('screen', 'menu')
    url.searchParams.set('table_token', token)
    if (endedSessionId && endedSessionId > 0) url.searchParams.set('tableSessionId', String(endedSessionId))
  }
  url.hash = hash
  return url
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
  navigate: (hash: string, options?: { replace?: boolean; focusVenue?: number; parent?: string }) => void,
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
        onOpenHelp: (venueId) => navigate(`#/support?venueId=${venueId}`),
        onBack,
        onBookVenue: (venueId) => {
          navigate(`#/bookings?venueId=${venueId}`)
        },
        onAskVenue,
        navigate
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
          navigate(`#/venue/${tableSnapshot.venueId}?cartLineRef=${encodeURIComponent(cartLineRef)}`)
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
        onBack,
        navigate
      })
    case 'account':
      return renderGuestAccountScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        profileDraft,
        tableContext: tableSnapshot,
        onBack,
        onOpenHelp: () => navigate('#/support'),
        onOpenBookings: () => {
          navigate('#/bookings')
        },
        onOpenVenue,
        onBookVenue: (venueId) => {
          navigate(`#/bookings?venueId=${venueId}`)
        },
        onAskVenue,
        selectedVenueId,
        navigate,
        onNavigateCart: () => {
          navigate('#/cart')
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
          navigate(`#/bookings?venueId=${venueId}`)
        },
        onAskVenue,
        navigate
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
  if (guestRouteHash() === applicationHash) {
    // Older entries may still carry the QR launch query. Restore this route by session.
    const clean = guestApplicationUrl(new URL(window.location.href))
    window.history.replaceState(window.history.state, '', clean)
    return mountGuestScreens(options)
  }
  if (/^#\/(account|order|cart|bookings|messages|support)(?:[/?]|$)/.test(guestRouteHash())) return mountGuestScreens(options)
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
  let mounted = true
  let selectedVenue = currentRoute.venueId ?? getVenueBrowseId()
  const profileDraft: GuestProfileDraft = {}
  let draftOwner = getTelegramContext().telegramUserId
  const conversations: Record<'messages' | 'support', GuestConversationState> = {
    messages: { filter: 'active', listScroll: 0, drafts: new Map() },
    support: { filter: 'active', listScroll: 0, drafts: new Map() }
  }
  let pendingVenueChat: number | null = null
  let resolvingQr = false
  let visitExitPending = false
  const visitReturn = el('button', { className: 'button-secondary guest-return-visit' })
  visitReturn.hidden = true
  refs.statusCard.after(visitReturn)
  const resumeLaunch = el('button', { className: 'button-secondary', text: 'Подключиться по QR из Telegram' })
  const retryRestore = el('button', { className: 'button-secondary', text: 'Повторить загрузку стола' })
  retryRestore.addEventListener('click', retryTableLoad)
  const draftMessage = el('p')
  draftMessage.setAttribute('role', 'status')
  const retryDraft = el('button', { className: 'button-secondary', text: 'Повторить загрузку корзины' })
  const keepSaved = el('button', { className: 'button-secondary', text: 'Принять сохранённую корзину' })
  const keepMine = el('button', { className: 'button-secondary', text: 'Применить мои правки' })
  keepSaved.addEventListener('click', () => resolveCartConflict(false))
  keepMine.addEventListener('click', () => resolveCartConflict(true))
  retryDraft.addEventListener('click', retryCartStorage)
  refs.statusCard.append(resumeLaunch, retryRestore, draftMessage, retryDraft, keepSaved, keepMine)
  let sdkQrAttempted = false
  let sdkQrRetry = false
  let sdkRetryIntentId: string | undefined
  const launchToken = getTelegramContext().tableToken
  let sdkRetryToken = launchToken
  try {
    const attempt = pendingSdkRejoin()
    if (attempt && launchToken) {
      sdkQrAttempted = true
      void sdkTokenDigest(launchToken).then(digest => {
        if (!mounted) return
        sdkQrAttempted = digest === attempt.tokenDigest
        sdkQrRetry = sdkQrAttempted && !attempt.requiresScan && !!attempt.intentId
        sdkRetryIntentId = sdkQrRetry ? attempt.intentId : undefined
        renderShellActions()
      }).catch(() => { /* A new scan is required. */ })
    }
  } catch { sdkQrAttempted = true }
  let staffCallActive = false
  let canLeaveTable = false
  let handleGuestAction: (action: GuestActionId) => void = () => undefined
  const renderShellActions = () => {
    const activeVisit = resolveTableMode(tableSnapshot) === 'active-table'
    const differentVenue = currentRoute.venueId !== null && currentRoute.venueId !== tableSnapshot.venueId
    const showVisit = activeVisit && !differentVenue && ['venue', 'cart', 'order'].includes(currentRoute.name)
    const presentation = formatTableStatus(tableSnapshot)
    const launch = getTelegramContext()
    resumeLaunch.textContent = sdkQrRetry ? 'Повторить подключение по QR' : 'Подключиться по QR из Telegram'
    resumeLaunch.disabled = resolvingQr || visitExitPending
    resumeLaunch.hidden = (sdkQrAttempted && !sdkQrRetry) || (!sdkQrRetry && tableSnapshot.status !== 'rescan') || !launch.tableTokenAutoResolve ||
      launch.tableTokenFromUrl || !launch.tableToken || guestRouteHash() === applicationHash
    retryRestore.hidden = !canRetryTableLoad(tableSnapshot)
    const storage = getCartSnapshot().draftStorage
    keepSaved.hidden = storage !== 'conflict' && storage !== 'capacity'
    keepMine.hidden = storage !== 'conflict'
    draftMessage.hidden = storage === 'ready' || storage === 'saving'
    retryDraft.hidden = draftMessage.hidden || !keepSaved.hidden
    retryDraft.textContent = storage === 'unsaved' ? 'Повторить сохранение корзины' : 'Повторить загрузку корзины'
    draftMessage.textContent = storage === 'capacity' ? 'Эти правки уже нельзя применить вместе. Примите сохранённую корзину, проверьте позиции и внесите нужные изменения.' : storage === 'conflict' ? 'Та же позиция или комментарий изменены в другом окне. Выберите, какие правки сохранить.' : storage === 'unsaved' ? 'Изменения корзины пока не сохранены. Редактирование приостановлено. Оставьте окно открытым и повторите сохранение.' : 'Не удалось прочитать корзину. Повторите загрузку перед изменением заказа.'
    refs.statusCard.hidden = draftMessage.hidden && !showVisit && !refs.statusDetails.textContent && resumeLaunch.hidden && retryRestore.hidden
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
  setCartTableToken(resolveTableMode(tableSnapshot) === 'active-table' ? tableSnapshot.tableToken : null, tableSnapshot.tableSessionId, tableSnapshot.visitGeneration)
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

  const contentRoot = (): string => selectedVenue ? `#/venue/${selectedVenue}`
    : !navigation.isCatalogChosen() && resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId
      ? `#/venue/${tableSnapshot.venueId}` : '#/catalog'
  const navigation = createGuestNavigation(onNavigationChange, () => fallbackBackHash(), contentRoot)
  let lastResolvedTable = tableSnapshot.status === 'resolved' ? tableSnapshot : null
  const tableSubscription = subscribeTable((snapshot) => {
    const previousCanOpenOrder = canOpenActiveOrder(tableSnapshot)
    const previousMode = resolveTableMode(tableSnapshot)
    const nextCanOpenOrder = canOpenActiveOrder(snapshot)
    tableSnapshot = snapshot
    const nextMode = resolveTableMode(snapshot)
    setCartTableToken(nextMode === 'active-table' ? snapshot.tableToken : null, snapshot.tableSessionId, snapshot.visitGeneration)
    const presentation = formatTableStatus(snapshot)
    if (!resolvingQr) {
      const showTableMessage = snapshot.status === 'rescan' || nextMode === 'ended-table' || snapshot.status === 'resolving' || snapshot.status === 'error' || presentation.severity === 'error'
      refs.statusDetails.textContent = showTableMessage
        ? [presentation.title, presentation.details].filter(Boolean).join(' ')
        : ''
    }
    refs.app.dataset.tableSeverity = presentation.severity
    if (nextMode !== 'active-table') {
      staffCallActive = false
    }
    if (snapshot.status === 'resolved') {
      const previous = lastResolvedTable
      lastResolvedTable = snapshot
      const changed = previous && (previous.tableToken !== snapshot.tableToken ||
        previous.tableSessionId !== snapshot.tableSessionId || previous.tableSessionActive !== snapshot.tableSessionActive)
      const firstEndedSession = !previous && nextMode === 'ended-table' && (snapshot.tableSessionId ?? 0) > 0
      const exitedWhileApplying = snapshot.tableSessionInactiveReason === 'TABLE_SESSION_EXITED' && guestRouteHash() === applicationHash
      if (!resolvingQr && !exitedWhileApplying && (changed || firstEndedSession)) {
        const venueId = snapshot.tableSessionActive ? snapshot.venueId : null
        const sessionId = snapshot.tableSessionId || (previous?.tableToken === snapshot.tableToken ? previous?.tableSessionId : null)
        const url = visitUrl(venueId ? `#/venue/${venueId}` : '#/catalog', snapshot.tableToken,
          snapshot.tableSessionActive ? null : sessionId)
        if (changed || !navigation.hasEndedVisitRoot(url)) {
          selectedVenue = venueId
          navigation.resetVisit(url, { ended: !snapshot.tableSessionActive })
          return
        }
      }
    } else if (snapshot.status === 'missing') {
      lastResolvedTable = null
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

  const { navigate } = navigation
  const askVenue = (venueId: number) => {
    pendingVenueChat = venueId
    navigate(`#/messages?venueId=${venueId}`)
  }
  const openVenueCard = (venueId: number) => {
    rememberVenueBrowse(`catalog:${venueId}`, venueId)
    navigate(`#/venue/${venueId}`)
  }
  const openCatalog = () => {
    if (getVenueBrowseId()) clearVenueBrowse()
    navigate('#/catalog', { root: true, catalogChosen: true })
  }
  const openVisit = () => {
    if (!tableSnapshot.venueId) return
    selectedVenue = tableSnapshot.venueId
    navigate(`#/venue/${tableSnapshot.venueId}`, { root: true })
  }
  visitReturn.addEventListener('click', openVisit)

  const routeListeners = new Set<() => void>()
  const notifyRouteChange = () => routeListeners.forEach((listener) => listener())
  const fallbackBackHash = (): string | null => {
    const hash = guestRouteHash()
    if (hash === '#/catalog') return null
    if (currentRoute.name === 'catalog') return '#/catalog'
    if (hash === '#/account/venue-application') return navigation.previousHash() ?? '#/account'
    if (currentRoute.name === 'account' && hash !== '#/account') {
      const params = new URLSearchParams(hash.split('?')[1] ?? '')
      if (params.has('visit')) {
        params.delete('visit')
        return `#/account/history${params.size ? `?${params}` : ''}`
      }
      return '#/account'
    }
    if (currentRoute.name === 'messages' && (currentRoute.threadId || currentRoute.venueId)) return '#/messages'
    if (currentRoute.name === 'support' && currentRoute.threadId) {
      const previous = navigation.previousHash()
      if (previous?.startsWith('#/support') && !/[?&](threadId|thread_id)=/.test(previous)) return previous
      return currentRoute.venueId ? `#/support?venueId=${currentRoute.venueId}` : '#/support'
    }
    const previous = navigation.previousHash()
    if (previous) return previous
    if (currentRoute.name === 'support' || (currentRoute.name === 'bookings' && !currentRoute.venueId)) return '#/account'
    if (currentRoute.name === 'venue') {
      if (/[?&]cartLineRef=/.test(hash)) return '#/cart'
      const cardAlias = new URLSearchParams(hash.split('?')[1] ?? '').get('browseMenu') === '0'
      const activeMenu = resolveTableMode(tableSnapshot) === 'active-table' &&
        tableSnapshot.venueId === currentRoute.venueId && !isPersonalVenueBrowse(currentRoute.venueId)
      if (hash.includes('?') && (!cardAlias || activeMenu)) return `#/venue/${currentRoute.venueId}`
      return isPersonalVenueBrowse(currentRoute.venueId) || currentRoute.venueId === tableSnapshot.venueId ? null : '#/catalog'
    }
    return contentRoot()
  }

  let cancelQrScan: (() => void) | null = null
  disposables.push(() => cancelQrScan?.())
  const connectToQr = async (token: string, sdkLaunch = false) => {
    if (resolvingQr || visitExitPending) return
    const actor = getTelegramContext().telegramUserId
    const retry = sdkLaunch && sdkQrRetry && token === sdkRetryToken
    const selection = startTableSelection(retry ? sdkRetryIntentId ?? '' : undefined)
    resolvingQr = true
    if (sdkLaunch) {
      sdkQrAttempted = true
      sdkQrRetry = false
      renderShellActions()
    }
    try {
      const tokenDigest = sdkLaunch ? await sdkTokenDigest(token) : ''
      if (!mounted || visitExitPending || actor !== getTelegramContext().telegramUserId) return
      const pending = sdkLaunch ? pendingSdkRejoin() : null
      if (sdkLaunch) {
        sdkRetryToken = token
        sdkRetryIntentId = selection.intent.id
      }
      const snapshot = await refreshTableContext({
        scannedToken: token, selection, retryableScan: sdkLaunch,
        retrySessionId: pending?.tokenDigest === tokenDigest ? pending.sessionId ?? undefined : undefined,
        confirmRejoin: table => {
          const confirmed = window.confirm(`Визит завершён. Снова подключиться к столу №${table.tableNumber} в заведении «${table.venueName}»?`)
          if (sdkLaunch && confirmed) rememberSdkRejoin({ sessionId: table.tableSessionId, tokenDigest, intentId: selection.intent.id })
          return confirmed
        }
      })
      if (!mounted || !snapshot || actor !== getTelegramContext().telegramUserId) return
      if (!snapshot.orderAllowed) {
        if (snapshot.status === 'rescan' && pending?.tokenDigest === tokenDigest) {
          rememberSdkRejoin({ ...pending, requiresScan: true })
        }
        if (sdkLaunch) sdkQrRetry = snapshot.status === 'error' &&
          !!snapshot.error && isTemporaryError(snapshot.error)
        if (sdkLaunch && sdkQrRetry) {
          const attempt = pendingSdkRejoin()
          if (attempt?.intentId !== selection.intent.id || attempt.tokenDigest !== tokenDigest) {
            rememberSdkRejoin({ tokenDigest, sessionId: null, intentId: selection.intent.id })
          }
        }
        const failure = formatTableStatus(snapshot)
        const message = [failure.title, failure.details].filter(Boolean).join(' ')
        refs.statusDetails.textContent = message
        renderShellActions()
        showTelegramAlert(message)
        return
      }
      sdkQrRetry = false
      if (getTelegramContext().telegramUserId) rememberSdkRejoin(null)
      if (getVenueBrowseId() !== null) clearVenueBrowse()
      if (snapshot.status === 'resolved' && snapshot.venueId && snapshot.tableSessionActive) {
        selectedVenue = snapshot.venueId
        navigation.resetVisit(visitUrl(`#/venue/${snapshot.venueId}`, token))
      }
    } catch {
      if (sdkLaunch) sdkQrRetry = true
      showTelegramAlert('Не удалось загрузить стол. Попробуйте ещё раз.')
    } finally {
      resolvingQr = false
      if (mounted) renderShellActions()
    }
  }
  resumeLaunch.addEventListener('click', () => {
    const launch = getTelegramContext()
    if ((!sdkQrRetry && tableSnapshot.status !== 'rescan') || launch.tableTokenFromUrl || !launch.tableTokenAutoResolve || !launch.tableToken) return
    void connectToQr(launch.tableToken, true)
  })
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
          botUsername: getSessionBotUsername()
        })
        if (!token) {
          webApp?.showAlert?.('QR-код не содержит токен стола.')
          return false
        }
        finishScan()
        try {
          webApp?.closeScanQrPopup?.()
        } catch {
          // ignore scanner close errors
        }
        connectToQr(token)
        return true
      })
    } catch {
      finishScan()
      refs.statusDetails.textContent = fallbackMessage
      renderShellActions()
      webApp?.showAlert?.('Не удалось открыть QR-сканер.')
    }
  }
  const leaveVisit = async (endedVisit?: { tableToken: string; tableSessionId: number }, exit?: ReturnType<typeof prepareVisitExit>) => {
    if (!await clearCurrentTableContext(endedVisit, exit) || !mounted) return
    setCartTableToken(null, null)
    staffCallActive = false
    selectedVenue = null
    const url = visitUrl('#/catalog')
    url.searchParams.set('visit', 'restore')
    if (url.searchParams.get('mode') === 'home' || !url.searchParams.has('mode')) url.searchParams.set('entry', 'guest')
    navigation.resetVisit(url, { ended: true })
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
      leaveVisit()
      return
    }
    if (snapshot.visitGeneration === undefined) {
      refs.statusDetails.textContent = 'Обновите стол перед завершением визита.'
      return
    }
    if (!window.confirm(`Завершить визит: ${snapshot.venueName ?? 'заведение'}, стол №${snapshot.tableNumber ?? '—'}? Вы выйдете из текущей сессии стола и корзины. Для нового заказа понадобится снова отсканировать QR.`)) return
    const visit = { tableToken: snapshot.tableToken, tableSessionId: snapshot.tableSessionId, visitGeneration: snapshot.visitGeneration }
    const actor = getTelegramContext().telegramUserId
    const exit = prepareVisitExit(visit)
    visitExitPending = true
    cancelQrScan?.()
    cancelPendingTableRefresh()
    refs.statusDetails.textContent = 'Завершаем визит…'
    renderShellActions()
    // Capture the admission before the server can issue a rejoin challenge for this exit.
    void exit.registered.then(() => {
      if (!mounted || getTelegramContext().telegramUserId !== actor) return null
      return guestEndTableSession(backendUrl, { ...visit, expectedVisitGeneration: visit.visitGeneration }, { isDebug, getAccessToken, clearSession })
    })
      .then((result) => {
        if (!result) return
        const sameScreen = mounted && getTelegramContext().telegramUserId === actor &&
          tableSnapshot.tableToken === visit.tableToken && tableSnapshot.tableSessionId === visit.tableSessionId &&
          tableSnapshot.visitGeneration === visit.visitGeneration
        if (!sameScreen) {
          if (result.ok && result.data.ended && result.data.tableSessionId === visit.tableSessionId) {
            return finishExit(exit, visit)
          }
          return
        }
        if (!result.ok) {
          if (result.error.status === 403 || result.error.status === 404) {
            const message = 'Сервер не подтвердил выход. Проверьте номер стола и заведение в меню бота. Завершайте визит только если они совпадают с нужным столом.'
            refs.statusDetails.textContent = message
            showTelegramAlert(message)
            return
          }
          const message = result.error.message || 'Не удалось завершить визит. Попробуйте позже.'
          refs.statusDetails.textContent = message
          showTelegramAlert(message)
          return
        }
        if (!result.data.ended || result.data.tableSessionId !== visit.tableSessionId) {
          const message = result.data.message || 'Сейчас визит нельзя завершить.'
          refs.statusDetails.textContent = message
          showTelegramAlert(message)
          return
        }
        const message = result.data.message || 'Визит завершён. Чтобы снова заказать за столом, отсканируйте QR.'
        refs.statusDetails.textContent = message
        showTelegramAlert(message)
        return leaveVisit(visit, exit)
      })
      .catch(() => {
        const message = 'Не удалось завершить визит. Попробуйте позже.'
        refs.statusDetails.textContent = message
        showTelegramAlert(message)
      })
      .finally(() => {
        void finishExit(exit)
        visitExitPending = false
        renderShellActions()
      })
  }
  handleGuestAction = (action) => {
    switch (action) {
      case 'catalog': {
        const venue = selectedVenue ?? (resolveTableMode(tableSnapshot) === 'active-table' ? tableSnapshot.venueId : null)
        if (venue) {
          const showingActiveMenu = tableSnapshot.venueId === venue && tableSnapshot.orderAllowed && !isPersonalVenueBrowse(venue)
          navigate(`#/venue/${venue}${showingActiveMenu ? '?browseMenu=0' : ''}`, { root: true })
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
        navigate('#/account', { root: true })
        return
      case 'messages':
        navigate('#/messages', { root: true })
        return
      default:
        return
    }
  }
  renderShellActions()
  const refreshResolvedTableContext = () => {
    if (!resolvingQr && !visitExitPending && tableSnapshot.status === 'resolved' && tableSnapshot.tableSessionActive) {
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
    back: navigation.back,
    canGoBack: navigation.canGoBack,
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
    if (route.name === 'catalog') selectedVenue = null
    renderShellActions()
    refreshVisitExitAvailability()
    currentDispose?.()
    if (route.name === 'catalog' && window.location.hash !== '#/catalog/promotions' && !navigation.isCatalogChosen() && !getVenueBrowseId() && resolveTableMode(tableSnapshot) === 'active-table' && tableSnapshot.venueId) {
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
        onOpenVenue: openVenueCard,
        onBookVenue: (venueId) => navigate(`#/bookings?venueId=${venueId}`),
        navigate
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
      openVenueCard,
      () => {
        navigate('#/order')
      },
      (venueId, focusHeading) => {
        if (venueId) {
          navigate(`#/venue/${venueId}${isPersonalVenueBrowse(venueId) ? '?browseMenu=1' : ''}`, {
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
        const result = openBotChat(telegramContext)
        if (result.ok) {
          return { ok: true }
        }
        const username = getSessionBotUsername()
        return {
          ok: false,
          message: username
            ? `Не удалось открыть чат автоматически. Откройте @${username} вручную.`
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

  function onNavigationChange() {
    if (resolveTableMode(tableSnapshot) !== 'ended-table' && tableSnapshot.status !== 'rescan' && tableSnapshot.status !== 'error') refs.statusDetails.textContent = ''
    render()
    notifyRouteChange()
  }

  render()
  notifyRouteChange()

  return () => {
    mounted = false
    if (resolvingQr) cancelPendingTableRefresh()
    navigation.dispose()
    exitCheckAbort?.abort()
    currentDispose?.()
    unbindBackButton()
    disposables.forEach((dispose) => dispose())
    cartSubscription()
    tableSubscription()
  }
}
