import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueGetMe } from '../shared/api/venueApi'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { isDebugEnabled } from '../shared/debug'
import { parsePositiveInt } from '../shared/parse'
import { getTelegramContext } from '../shared/telegram'
import { openBotChat } from '../shared/telegramActions'
import { bindTelegramBackButton } from '../shared/telegramBackButton'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { renderVenueChatLinkScreen } from './venueChatLink'
import { renderVenueCallsScreen } from './venueCalls'
import { renderVenueBookingsScreen } from './venueBookings'
import { renderVenueDashboardScreen } from './venueDashboard'
import { renderVenueMenuScreen } from './venueMenu'
import { renderVenueOrderDetailScreen } from './venueOrderDetail'
import { renderVenueOrdersScreen } from './venueOrders'
import { renderVenueSettingsScreen } from './venueSettings'
import { renderVenueShiftExtensionsScreen } from './venueShiftExtensions'
import { renderVenueStaffScreen } from './venueStaff'
import { renderVenueStatsScreen } from './venueStats'
import { renderVenueSupportScreen } from './supportScreens'
import { renderVenueTablesScreen } from './venueTables'

export type VenueAppOptions = {
  root: HTMLDivElement | null
  backendUrl: string
}

type RouteName =
  | 'dashboard'
  | 'orders'
  | 'order'
  | 'calls'
  | 'extensions'
  | 'menu'
  | 'tables'
  | 'staff'
  | 'stats'
  | 'settings'
  | 'bookings'
  | 'chat'
  | 'support'

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
  navSections: Array<{ element: HTMLDivElement; buttons: HTMLButtonElement[] }>
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
    ![
      'dashboard',
      'orders',
      'order',
      'calls',
      'extensions',
      'menu',
      'tables',
      'staff',
      'stats',
      'settings',
      'bookings',
      'chat',
      'support'
    ].includes(route)
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
  const title = el('h1', { text: 'Панель заведения' })
  const subtitle = el('p', { className: 'app-subtitle', text: 'Операционный режим' })
  append(brand, title, subtitle)

  const controls = el('div', { className: 'venue-controls' })
  const accessState = el('p', { className: 'venue-access-state', text: 'Загрузка доступа...' })
  const venueSelect = document.createElement('select')
  venueSelect.className = 'venue-select'
  venueSelect.disabled = true
  append(controls, accessState, venueSelect)

  const nav = el('nav', { className: 'venue-nav' })
  const navButtons = {
    dashboard: el('button', { className: 'nav-button', text: 'Обзор' }) as HTMLButtonElement,
    orders: el('button', { className: 'nav-button', text: 'Заказы' }) as HTMLButtonElement,
    bookings: el('button', { className: 'nav-button', text: 'Брони' }) as HTMLButtonElement,
    calls: el('button', { className: 'nav-button', text: 'Вызовы' }) as HTMLButtonElement,
    extensions: el('button', { className: 'nav-button', text: 'Запросы продления' }) as HTMLButtonElement,
    menu: el('button', { className: 'nav-button', text: 'Заказное меню' }) as HTMLButtonElement,
    tables: el('button', { className: 'nav-button', text: 'Столы и QR' }) as HTMLButtonElement,
    staff: el('button', { className: 'nav-button', text: 'Персонал' }) as HTMLButtonElement,
    stats: el('button', { className: 'nav-button', text: 'Статистика' }) as HTMLButtonElement,
    settings: el('button', { className: 'nav-button', text: 'Настройки продления' }) as HTMLButtonElement,
    chat: el('button', { className: 'nav-button', text: 'Чат персонала' }) as HTMLButtonElement,
    support: el('button', { className: 'nav-button', text: 'Поддержка' }) as HTMLButtonElement
  }

  const navSections = [
    {
      title: 'Работа смены',
      buttons: [navButtons.dashboard, navButtons.orders, navButtons.bookings, navButtons.calls, navButtons.extensions]
    },
    {
      title: 'Настройки',
      buttons: [navButtons.menu, navButtons.tables, navButtons.staff, navButtons.chat, navButtons.settings]
    },
    {
      title: 'Статистика',
      buttons: [navButtons.stats]
    },
    {
      title: 'Помощь',
      buttons: [navButtons.support]
    }
  ].map((section) => {
    const group = el('div', { className: 'venue-nav-section' }) as HTMLDivElement
    const label = el('span', { className: 'venue-nav-section-title', text: section.title })
    const actions = el('div', { className: 'venue-nav-section-actions' })
    append(actions, ...section.buttons)
    append(group, label, actions)
    nav.appendChild(group)
    return { element: group, buttons: section.buttons }
  })

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
    navSections,
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

function venueRoleLabel(role: VenueAccessDto['role']) {
  switch (role) {
    case 'OWNER':
      return 'владелец'
    case 'MANAGER':
      return 'менеджер'
    case 'STAFF':
      return 'персонал'
    default:
      return role
  }
}

function venueSelectorLabel(venue: VenueAccessDto): string {
  const base = venue.venueName?.trim() || `Заведение #${venue.venueId}`
  const meta = [venue.venueCity?.trim(), venueStatusLabel(venue.venueStatus)].filter(Boolean)
  return meta.length ? `${base} · ${meta.join(' · ')}` : base
}

function venueStatusLabel(status?: string | null): string | null {
  if (!status) return null
  switch (status.trim().toUpperCase()) {
    case 'PUBLISHED':
      return 'Опубликовано'
    case 'HIDDEN':
      return 'Скрыто'
    case 'DRAFT':
      return 'Черновик'
    case 'PAUSED':
      return 'На паузе'
    case 'SUSPENDED':
      return 'Заблокировано'
    case 'ARCHIVED':
      return 'Архив'
    case 'DELETED':
      return 'Удалено'
    default:
      return status
  }
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
      refs.accessState.textContent = `Роль: ${venueRoleLabel(currentRole)}`
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
    const params = new URLSearchParams(window.location.search)
    const requestedVenueId = parsePositiveInt(params.get('venueId')) ?? parsePositiveInt(telegramContext.startParam)
    selectedVenueId = null
    if (requestedVenueId && accessList.some((venue) => venue.venueId === requestedVenueId)) {
      selectedVenueId = requestedVenueId
    } else if (accessList.length === 1) {
      selectedVenueId = accessList[0].venueId
    } else if (accessList.length > 0) {
      selectedVenueId = accessList[0].venueId
    }
    refs.venueSelect.replaceChildren()
    accessList.forEach((venue) => {
      const option = new Option(venueSelectorLabel(venue), String(venue.venueId))
      refs.venueSelect.appendChild(option)
    })
    if (selectedVenueId) {
      refs.venueSelect.value = String(selectedVenueId)
    }
    refs.venueSelect.hidden = accessList.length <= 1
    refs.venueSelect.disabled = accessList.length <= 1
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
    updateNavVisibility()
  }

  const hasPermission = (permission: string) => currentPermissions.includes(permission)

  const updateNavVisibility = () => {
    refs.navButtons.orders.hidden = !hasPermission('ORDER_QUEUE_VIEW')
    refs.navButtons.bookings.hidden = !hasPermission('BOOKING_VIEW')
    refs.navButtons.calls.hidden = !hasPermission('ORDER_QUEUE_VIEW')
    refs.navButtons.extensions.hidden = !hasPermission('SHIFT_EXTENSION_VIEW')
    refs.navButtons.menu.hidden = !hasPermission('MENU_VIEW')
    refs.navButtons.tables.hidden = !hasPermission('TABLE_VIEW')
    refs.navButtons.staff.hidden = currentRole === 'STAFF'
    refs.navButtons.stats.hidden = currentRole !== 'OWNER' && currentRole !== 'MANAGER'
    refs.navButtons.chat.hidden = !hasPermission('STAFF_CHAT_LINK')
    refs.navButtons.settings.hidden = !hasPermission('SHIFT_EXTENSION_SETTINGS')
    refs.navSections.forEach((section) => {
      section.element.hidden = section.buttons.every((button) => button.hidden)
    })
  }

  const canAccessRoute = (route: RouteName) => {
    switch (route) {
      case 'orders':
      case 'order':
      case 'calls':
        return hasPermission('ORDER_QUEUE_VIEW')
      case 'extensions':
        return hasPermission('SHIFT_EXTENSION_VIEW')
      case 'bookings':
        return hasPermission('BOOKING_VIEW')
      case 'menu':
        return hasPermission('MENU_VIEW')
      case 'tables':
        return hasPermission('TABLE_VIEW')
      case 'settings':
        return hasPermission('SHIFT_EXTENSION_SETTINGS')
      case 'staff':
        return currentRole !== 'STAFF'
      case 'stats':
        return currentRole === 'OWNER' || currentRole === 'MANAGER'
      case 'chat':
        return hasPermission('STAFF_CHAT_LINK')
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
      case 'calls':
        return renderVenueCallsScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'extensions':
        return renderVenueShiftExtensionsScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          onOpenOrder: (orderId) => navigate(`#/order/${orderId}`)
        })
      case 'bookings':
        return renderVenueBookingsScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'menu':
        return renderVenueMenuScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'tables':
        return renderVenueTablesScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'staff':
        return renderVenueStaffScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          currentUserId: currentUserId ?? 0
        })
      case 'stats':
        return renderVenueStatsScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'settings':
        return renderVenueSettingsScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'chat':
        return renderVenueChatLinkScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'support':
        return renderVenueSupportScreen({
          root: screenRoot,
          onBack: () => navigate('#/dashboard'),
          onOpenBot: () => {
            const telegramContext = getTelegramContext()
            const result = openBotChat(telegramContext)
            if (result.ok) {
              return { ok: true }
            }
            return {
              ok: false,
              message: telegramContext.botUsername
                ? `Не удалось открыть чат автоматически. Откройте @${telegramContext.botUsername} вручную.`
                : 'Не удалось открыть чат автоматически. Откройте чат с ботом вручную.'
            }
          }
        })
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
  disposables.push(on(refs.navButtons.bookings, 'click', () => navigate('#/bookings')))
  disposables.push(on(refs.navButtons.calls, 'click', () => navigate('#/calls')))
  disposables.push(on(refs.navButtons.extensions, 'click', () => navigate('#/extensions')))
  disposables.push(on(refs.navButtons.menu, 'click', () => navigate('#/menu')))
  disposables.push(on(refs.navButtons.tables, 'click', () => navigate('#/tables')))
  disposables.push(on(refs.navButtons.staff, 'click', () => navigate('#/staff')))
  disposables.push(on(refs.navButtons.stats, 'click', () => navigate('#/stats')))
  disposables.push(on(refs.navButtons.settings, 'click', () => navigate('#/settings')))
  disposables.push(on(refs.navButtons.chat, 'click', () => navigate('#/chat')))
  disposables.push(on(refs.navButtons.support, 'click', () => navigate('#/support')))
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
