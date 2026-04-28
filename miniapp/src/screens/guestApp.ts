import { getBackendBaseUrl } from '../shared/api/backend'
import { isDebugEnabled } from '../shared/debug'
import {
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
import { renderGuestVenueScreen } from './guestVenue'
import { renderOrderScreen } from './order'

type GuestAppOptions = {
  root: HTMLDivElement | null
}

type RouteName = 'catalog' | 'venue' | 'cart' | 'order'
type NavRouteName = Exclude<RouteName, 'venue'>

type Route = {
  name: RouteName
  venueId: number | null
}

type NavButtonRefs = Record<NavRouteName, HTMLButtonElement>

type GuestRefs = {
  app: HTMLDivElement
  statusTitle: HTMLParagraphElement
  statusDetails: HTMLParagraphElement
  scanQrButton: HTMLButtonElement
  fallbackChatButton: HTMLButtonElement
  navButtons: NavButtonRefs
  content: HTMLDivElement
}

const scannedTokenQueryParamKeys = ['table_token', 'tableToken', 'tgWebAppStartParam', 'startapp', 'start_param'] as const

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
    return { name: 'catalog', venueId: null }
  }
  const [pathPart, queryPart] = cleaned.split('?')
  const segments = pathPart.split('/').filter(Boolean)
  const route = segments[0] as RouteName | undefined
  if (!route || !['catalog', 'venue', 'cart', 'order'].includes(route)) {
    return { name: 'catalog', venueId: null }
  }
  if (route === 'venue') {
    const venueIdFromPath = parsePositiveInt(segments[1])
    const params = new URLSearchParams(queryPart ?? '')
    const venueIdFromQuery = parsePositiveInt(params.get('id'))
    return { name: 'venue', venueId: venueIdFromPath ?? venueIdFromQuery ?? null }
  }
  return { name: route, venueId: null }
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
  if (!route || !['catalog', 'venue', 'cart', 'order'].includes(route)) {
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
  const scanQrButton = el('button', {
    className: 'button-secondary table-scan-button',
    text: 'Сканировать QR'
  }) as HTMLButtonElement
  const fallbackChatButton = el('button', {
    className: 'button-secondary table-fallback-button',
    text: 'Не грузится? → Оформить в чате'
  }) as HTMLButtonElement
  append(statusCard, statusTitle, statusDetails, scanQrButton, fallbackChatButton)

  const nav = el('nav', { className: 'app-nav' })
  const catalogButton = el('button', { className: 'nav-button', text: 'Каталог' })
  const cartButton = el('button', { className: 'nav-button', text: 'Корзина (0)' })
  const orderButton = el('button', { className: 'nav-button', text: 'Заказ' })
  append(nav, catalogButton, cartButton, orderButton)
  append(header, brand, statusCard, nav)

  const content = el('div', { className: 'container app-content' })
  append(app, header, content)
  root.replaceChildren(app)

  return {
    app,
    statusTitle,
    statusDetails,
    scanQrButton,
    fallbackChatButton,
    navButtons: {
      catalog: catalogButton,
      cart: cartButton,
      order: orderButton
    },
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
  onNavigateMenu: (venueId: number | null) => void
): () => void {
  const screenRoot = document.createElement('div')
  screenRoot.className = 'screen-root'
  container.replaceChildren(screenRoot)

  switch (route.name) {
    case 'venue':
      return renderGuestVenueScreen({ root: screenRoot, backendUrl, isDebug, venueId: route.venueId })
    case 'cart':
      return renderCartScreen({ root: screenRoot, backendUrl, isDebug, onNavigateOrder })
    case 'order':
      return renderOrderScreen({ root: screenRoot, backendUrl, isDebug, onNavigateMenu })
    case 'catalog':
    default:
      return renderCatalogScreen({ root: screenRoot, backendUrl, isDebug, onOpenVenue })
  }
}

function updateNav(navButtons: NavButtonRefs, active: RouteName) {
  const entries: Array<[NavRouteName, HTMLButtonElement]> = Object.entries(navButtons) as Array<[
    NavRouteName,
    HTMLButtonElement
  ]>
  entries.forEach(([key, button]) => {
    const isActive = key === active
    button.dataset.active = String(isActive)
  })
}

function canOpenActiveOrder(snapshot: ReturnType<typeof getTableContext>): boolean {
  return snapshot.status === 'resolved' && Boolean(snapshot.tableToken) && snapshot.orderAllowed
}

function updateOrderNavAccess(navButtons: NavButtonRefs, canOpenOrder: boolean) {
  navButtons.cart.disabled = !canOpenOrder
  navButtons.order.disabled = !canOpenOrder
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
  setCartTableToken(tableSnapshot.tableToken)
  let rerenderForTableChange: (() => void) | null = null
  const updateCartNav = (totalQty: number) => {
    refs.navButtons.cart.textContent = `Корзина (${totalQty})`
  }

  const tableSubscription = subscribeTable((snapshot) => {
    const previousCanOpenOrder = canOpenActiveOrder(tableSnapshot)
    const nextCanOpenOrder = canOpenActiveOrder(snapshot)
    tableSnapshot = snapshot
    setCartTableToken(snapshot.tableToken)
    const presentation = formatTableStatus(snapshot)
    refs.statusTitle.textContent = presentation.title
    refs.statusDetails.textContent = presentation.details ?? ''
    refs.app.dataset.tableSeverity = presentation.severity
    updateOrderNavAccess(refs.navButtons, nextCanOpenOrder)
    const routeName = resolveRoute().name
    if (
      rerenderForTableChange &&
      (routeName === 'cart' || routeName === 'order') &&
      previousCanOpenOrder !== nextCanOpenOrder
    ) {
      rerenderForTableChange()
    }
  })

  const cartSubscription = subscribeCart((snapshot) => {
    updateCartNav(snapshot.totalQty)
  })
  updateCartNav(getCartSnapshot().totalQty)

  const historyStack: string[] = []
  let replaceNextHistoryEntry = false

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
    const routeName = resolveRoute().name
    const hasActiveVenueContext = tableSnapshot.status === 'resolved' && Boolean(tableSnapshot.tableToken) && Boolean(tableSnapshot.venueId)
    if ((routeName === 'cart' || routeName === 'order') && hasActiveVenueContext && tableSnapshot.venueId) {
      navigate(`#/venue/${tableSnapshot.venueId}`, { replace: true })
      return
    }
    navigate('#/catalog', { replace: true })
  }

  const routeListeners = new Set<() => void>()
  const notifyRouteChange = () => {
    routeListeners.forEach((listener) => listener())
  }

  const updateHistory = (hash: string) => {
    if (!hash || hash === '#') {
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

  const disposables: Array<() => void> = []
  disposables.push(
    on(refs.scanQrButton, 'click', () => {
      const telegramContext = getTelegramContext()
      const webApp = telegramContext.webApp
      const scanQr = getTelegramQrScanner(webApp)
      if (!scanQr) {
        webApp?.showAlert?.('Сканер QR недоступен в текущем Telegram.')
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
          nextUrl.searchParams.delete('tableToken')
          nextUrl.searchParams.delete('tgWebAppStartParam')
          nextUrl.searchParams.delete('startapp')
          nextUrl.searchParams.delete('start_param')
          nextUrl.searchParams.set('table_token', token)
          window.history.replaceState(null, '', `${nextUrl.pathname}${nextUrl.search}${nextUrl.hash}`)
          void refreshTableContext()
            .then(() => {
              const snapshot = getTableContext()
              if (resolveRoute().name !== 'catalog') {
                return
              }
              if (snapshot.status === 'resolved' && snapshot.venueId) {
                navigate(`#/venue/${snapshot.venueId}`)
              }
            })
            .catch(() => undefined)
          return true
        })
      } catch {
        webApp?.showAlert?.('Не удалось открыть QR-сканер.')
      }
    })
  )
  disposables.push(
    on(refs.fallbackChatButton, 'click', () => {
      const telegramContext = getTelegramContext()
      const tableSnapshot = getTableContext()
      const result = openBotChat(telegramContext, {
        tableToken: tableSnapshot.tableToken,
        tableSessionId: tableSnapshot.tableSessionId
      })
      if (!result.ok) {
        refs.statusDetails.textContent = telegramContext.botUsername
          ? `Не удалось открыть чат автоматически. Откройте @${telegramContext.botUsername} вручную.`
          : 'Не удалось открыть чат автоматически. Откройте чат с ботом вручную.'
      }
    })
  )
  disposables.push(on(refs.navButtons.catalog, 'click', navigateCatalogButton))
  disposables.push(on(refs.navButtons.cart, 'click', () => navigate('#/cart', { replace: true })))
  disposables.push(on(refs.navButtons.order, 'click', () => navigate('#/order', { replace: true })))

  let currentDispose: (() => void) | null = null
  let currentRoute: Route = resolveRoute()

  const router = {
    getRouteName: () => currentRoute.name,
    navigate,
    back: () => {
      if (historyStack.length > 1) {
        historyStack.pop()
        navigate(historyStack[historyStack.length - 1])
        return
      }
      navigate('#/catalog')
    },
    canGoBack: () => historyStack.length > 1,
    subscribe: (handler: () => void) => {
      routeListeners.add(handler)
      return () => routeListeners.delete(handler)
    }
  }

  const unbindBackButton = bindTelegramBackButton(router)

  const render = () => {
    const route = resolveRoute()
    currentRoute = route
    updateNav(refs.navButtons, route.name)
    currentDispose?.()
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
      }
    )
  }
  rerenderForTableChange = render

  const onHashChange = () => {
    updateHistory(window.location.hash || '#/catalog')
    render()
    notifyRouteChange()
  }

  window.addEventListener('hashchange', onHashChange)
  updateHistory(window.location.hash || '#/catalog')
  render()
  notifyRouteChange()

  return () => {
    window.removeEventListener('hashchange', onHashChange)
    currentDispose?.()
    unbindBackButton()
    disposables.forEach((dispose) => dispose())
    cartSubscription()
    tableSubscription()
  }
}
