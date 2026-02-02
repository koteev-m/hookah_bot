import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueGetMe } from '../shared/api/venueApi'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { isDebugEnabled } from '../shared/debug'
import { parsePositiveInt } from '../shared/parse'
import { getTelegramContext } from '../shared/telegram'
import { bindTelegramBackButton } from '../shared/telegramBackButton'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { renderVenueChatLinkScreen } from './venueChatLink'
import { renderVenueDashboardScreen } from './venueDashboard'
import { renderVenueMenuScreen } from './venueMenu'
import { renderVenueOrderDetailScreen } from './venueOrderDetail'
import { renderVenueOrdersScreen } from './venueOrders'
import { renderVenueSettingsScreen } from './venueSettings'
import { renderVenueStaffScreen } from './venueStaff'
import { renderVenueManagerTablesScreen } from './venueManagerTables'
import { renderVenueTablesScreen } from './venueTables'

export type VenueAppOptions = {
  root: HTMLDivElement | null
  backendUrl: string
}

type RouteName =
  | 'dashboard'
  | 'orders'
  | 'order'
  | 'menu'
  | 'tables'
  | 'manager-tables'
  | 'staff'
  | 'settings'
  | 'chat'

type Route = {
  name: RouteName
  orderId: number | null
}

type NavRouteName = Exclude<RouteName, 'order'>

type VenueShellRefs = {
  app: HTMLDivElement
  accessState: HTMLParagraphElement
  venueSelect: HTMLSelectElement
  navButtons: Record<NavRouteName, HTMLButtonElement>
  content: HTMLDivElement
  errorCard: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function ensureDefaultHash() {
  const hash = window.location.hash
  if (!hash || hash === '#' || hash === '#/') {
    window.location.hash = '#/dashboard'
  }
}

function resolveRoute(): Route {
  const rawHash = window.location.hash.replace(/^#/, '')
  const cleaned = rawHash.startsWith('/') ? rawHash.slice(1) : rawHash
  if (!cleaned) {
    return { name: 'dashboard', orderId: null }
  }
  const [pathPart] = cleaned.split('?')
  const segments = pathPart.split('/').filter(Boolean)
  const route = segments[0] as RouteName | undefined
  if (
    !route ||
    !['dashboard', 'orders', 'order', 'menu', 'tables', 'manager-tables', 'staff', 'settings', 'chat'].includes(route)
  ) {
    return { name: 'dashboard', orderId: null }
  }
  if (route === 'order') {
    const orderId = parsePositiveInt(segments[1])
    return { name: 'order', orderId: orderId ?? null }
  }
  return { name: route, orderId: null }
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

function buildVenueShell(root: HTMLDivElement): VenueShellRefs {
  const app = el('div', { className: 'venue-shell' })
  const header = el('header', { className: 'venue-header' })
  const brand = el('div', { className: 'app-brand' })
  const title = el('h1', { text: 'Hookah Mini App' })
  const subtitle = el('p', { className: 'app-subtitle', text: 'Режим заведения' })
  append(brand, title, subtitle)

  const controls = el('div', { className: 'venue-controls' })
  const accessState = el('p', { className: 'venue-access-state', text: 'Загрузка доступа...' })
  const venueSelect = document.createElement('select')
  venueSelect.className = 'venue-select'
  venueSelect.disabled = true
  append(controls, accessState, venueSelect)

  const nav = el('nav', { className: 'venue-nav' })
  const navButtons = {
    dashboard: el('button', { className: 'nav-button', text: 'Dashboard' }) as HTMLButtonElement,
    orders: el('button', { className: 'nav-button', text: 'Очередь' }) as HTMLButtonElement,
    menu: el('button', { className: 'nav-button', text: 'Меню' }) as HTMLButtonElement,
    tables: el('button', { className: 'nav-button', text: 'Столы & QR' }) as HTMLButtonElement,
    'manager-tables': el('button', { className: 'nav-button', text: 'Столы' }) as HTMLButtonElement,
    staff: el('button', { className: 'nav-button', text: 'Персонал' }) as HTMLButtonElement,
    settings: el('button', { className: 'nav-button', text: 'Настройки' }) as HTMLButtonElement,
    chat: el('button', { className: 'nav-button', text: 'Чат персонала' }) as HTMLButtonElement
  }
  append(
    nav,
    navButtons.dashboard,
    navButtons.orders,
    navButtons.menu,
    navButtons.tables,
    navButtons['manager-tables'],
    navButtons.staff,
    navButtons.settings,
    navButtons.chat
  )

  const errorCard = el('div', { className: 'error-card venue-error' }) as HTMLDivElement
  errorCard.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(errorCard, errorTitle, errorMessage, errorActions, errorDetails)

  const content = el('div', { className: 'container venue-content' })
  append(header, brand, controls, nav)
  append(app, header, errorCard, content)
  root.replaceChildren(app)

  return {
    app,
    accessState,
    venueSelect,
    navButtons,
    content,
    errorCard,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
}

function updateNav(navButtons: Record<NavRouteName, HTMLButtonElement>, active: RouteName) {
  Object.entries(navButtons).forEach(([key, button]) => {
    button.dataset.active = String(key === active)
  })
}

function maskErrorActions(presentation: ReturnType<typeof presentApiError>, retry: () => void): ApiErrorAction[] {
  if (presentation.actions.length) {
    return presentation.actions.map((action) => {
      if (action.label === 'Повторить') {
        return { ...action, onClick: retry }
      }
      return action
    })
  }
  return [{ label: 'Повторить', kind: 'primary' as const, onClick: retry }]
}

export function mountVenueApp(options: VenueAppOptions) {
  const { root, backendUrl } = options
  if (!root) return () => undefined
  ensureDefaultHash()
  const isDebug = isDebugEnabled()
  const deps = buildApiDeps(isDebug)
  const refs = buildVenueShell(root)

  let disposed = false
  let accessAbort: AbortController | null = null
  let accessList: VenueAccessDto[] = []
  let selectedVenueId: number | null = null
  let currentRole: VenueAccessDto['role'] | null = null
  let currentPermissions: string[] = []
  let currentUserId: number | null = null

  const updateAccessState = () => {
    if (selectedVenueId && currentRole) {
      refs.accessState.textContent = `Роль: ${currentRole} · Venue #${selectedVenueId}`
    } else {
      refs.accessState.textContent = 'Выберите заведение'
    }
  }

  const clearAccessError = () => {
    refs.errorCard.hidden = true
    refs.errorActions.replaceChildren()
    refs.errorDetails.replaceChildren()
  }

  const showAccessError = (error: ApiErrorInfo) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.errorCard.hidden = false
    refs.errorCard.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    renderErrorActions(refs.errorActions, maskErrorActions(presentation, () => void loadAccess()))
    renderErrorDetails(refs.errorDetails, error, { isDebug })
  }

  const loadAccess = async () => {
    if (accessAbort) {
      accessAbort.abort()
    }
    const controller = new AbortController()
    accessAbort = controller
    refs.accessState.textContent = 'Загрузка доступа...'
    refs.venueSelect.disabled = true
    refs.venueSelect.replaceChildren()
    const result = await venueGetMe(backendUrl, deps, controller.signal)
    if (disposed || accessAbort !== controller) return
    accessAbort = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) {
        return
      }
      showAccessError(result.error)
      return
    }
    clearAccessError()
    accessList = result.data.venues
    currentUserId = result.data.userId
    const telegramContext = getTelegramContext()
    const startParamId = parsePositiveInt(telegramContext.startParam)
    selectedVenueId = null
    if (startParamId && accessList.some((venue) => venue.venueId === startParamId)) {
      selectedVenueId = startParamId
    } else if (accessList.length === 1) {
      selectedVenueId = accessList[0].venueId
    } else if (accessList.length > 0) {
      selectedVenueId = accessList[0].venueId
    }
    refs.venueSelect.replaceChildren()
    accessList.forEach((venue) => {
      const option = new Option(`Venue #${venue.venueId}`, String(venue.venueId))
      refs.venueSelect.appendChild(option)
    })
    if (selectedVenueId) {
      refs.venueSelect.value = String(selectedVenueId)
    }
    refs.venueSelect.disabled = accessList.length === 0
    updateAccessFromSelection()
    render()
  }

  const updateAccessFromSelection = () => {
    const selection = parsePositiveInt(refs.venueSelect.value)
    selectedVenueId = selection
    const access = selection ? accessList.find((venue) => venue.venueId === selection) ?? null : null
    currentRole = access?.role ?? null
    currentPermissions = access?.permissions ?? []
    updateAccessState()
  }

  const hasPermission = (permission: string) => currentPermissions.includes(permission)

  const canAccessRoute = (route: RouteName) => {
    switch (route) {
      case 'orders':
      case 'order':
        return hasPermission('ORDER_QUEUE_VIEW')
      case 'menu':
        return hasPermission('MENU_VIEW')
      case 'tables':
        return hasPermission('TABLE_VIEW')
      case 'manager-tables':
        return currentRole === 'OWNER' || currentRole === 'MANAGER'
      case 'settings':
        return hasPermission('VENUE_SETTINGS')
      default:
        return true
    }
  }

  const renderRouteContent = (route: Route) => {
    const screenRoot = document.createElement('div')
    screenRoot.className = 'screen-root'
    refs.content.replaceChildren(screenRoot)
    const venueId = selectedVenueId
    const access = currentRole
      ? { venueId: selectedVenueId ?? 0, role: currentRole, permissions: currentPermissions }
      : null
    if (!venueId || !access) {
      const placeholder = el('section', { className: 'card' })
      const text = venueId ? 'Недостаточно прав.' : 'Выберите заведение.'
      append(placeholder, el('h2', { text: 'Доступ' }), el('p', { text }))
      refs.content.replaceChildren(placeholder)
      return () => undefined
    }
    if (!canAccessRoute(route.name)) {
      const denied = el('section', { className: 'card' })
      append(denied, el('h2', { text: 'Недостаточно прав' }), el('p', { text: 'У вас нет доступа к этому разделу.' }))
      refs.content.replaceChildren(denied)
      return () => undefined
    }
    switch (route.name) {
      case 'dashboard':
        return renderVenueDashboardScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'orders':
        return renderVenueOrdersScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          onOpenOrder: (orderId) => navigate(`#/order/${orderId}`)
        })
      case 'order':
        return renderVenueOrderDetailScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          orderId: route.orderId,
          access,
          onBack: () => navigate('#/orders')
        })
      case 'menu':
        return renderVenueMenuScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'tables':
        return renderVenueTablesScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'manager-tables':
        return renderVenueManagerTablesScreen({ root: screenRoot, backendUrl, isDebug, venueId })
      case 'staff':
        return renderVenueStaffScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          currentUserId: currentUserId ?? 0
        })
      case 'settings':
        return renderVenueSettingsScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'chat':
        return renderVenueChatLinkScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      default:
        return () => undefined
    }
  }

  const navigate = (hash: string) => {
    if (window.location.hash !== hash) {
      window.location.hash = hash
    }
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.navButtons.dashboard, 'click', () => navigate('#/dashboard')))
  disposables.push(on(refs.navButtons.orders, 'click', () => navigate('#/orders')))
  disposables.push(on(refs.navButtons.menu, 'click', () => navigate('#/menu')))
  disposables.push(on(refs.navButtons.tables, 'click', () => navigate('#/tables')))
  disposables.push(on(refs.navButtons['manager-tables'], 'click', () => navigate('#/manager-tables')))
  disposables.push(on(refs.navButtons.staff, 'click', () => navigate('#/staff')))
  disposables.push(on(refs.navButtons.settings, 'click', () => navigate('#/settings')))
  disposables.push(on(refs.navButtons.chat, 'click', () => navigate('#/chat')))
  disposables.push(
    on(refs.venueSelect, 'change', () => {
      updateAccessFromSelection()
      render()
    })
  )

  let currentDispose: (() => void) | null = null
  let currentRoute = resolveRoute()

  const routeListeners = new Set<() => void>()
  const notifyRouteChange = () => {
    routeListeners.forEach((listener) => listener())
  }

  const router = {
    getRouteName: () => (currentRoute.name === 'dashboard' ? 'catalog' : currentRoute.name),
    navigate,
    back: () => {
      if (currentRoute.name === 'order') {
        navigate('#/orders')
        return
      }
      navigate('#/dashboard')
    },
    canGoBack: () => currentRoute.name !== 'dashboard',
    subscribe: (handler: () => void) => {
      routeListeners.add(handler)
      return () => routeListeners.delete(handler)
    }
  }

  const unbindBackButton = bindTelegramBackButton(router)

  const render = () => {
    const route = resolveRoute()
    currentRoute = route
    updateNav(refs.navButtons, route.name === 'order' ? 'orders' : route.name)
    currentDispose?.()
    currentDispose = renderRouteContent(route)
  }

  const onHashChange = () => {
    render()
    notifyRouteChange()
  }

  window.addEventListener('hashchange', onHashChange)
  void loadAccess()
  render()
  notifyRouteChange()

  return () => {
    disposed = true
    accessAbort?.abort()
    accessAbort = null
    window.removeEventListener('hashchange', onHashChange)
    currentDispose?.()
    unbindBackButton()
    disposables.forEach((dispose) => dispose())
  }
}
