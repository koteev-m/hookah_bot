import { getBackendBaseUrl } from '../shared/api/backend'
import { isDebugEnabled } from '../shared/debug'
import { formatTableStatus, initTableContext, subscribe as subscribeTable } from '../shared/state/tableContext'
import { getCartSnapshot, subscribeCart } from '../shared/state/cartStore'
import { parsePositiveInt } from '../shared/parse'
import { bindTelegramBackButton } from '../shared/telegramBackButton'
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
  navButtons: NavButtonRefs
  content: HTMLDivElement
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
  append(statusCard, statusTitle, statusDetails)

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

export function mountGuestApp(options: GuestAppOptions) {
  const { root } = options
  if (!root) return () => undefined
  initTableContext()
  ensureDefaultHash()
  const backendUrl = getBackendBaseUrl()
  const isDebug = isDebugEnabled()

  const refs = buildGuestShell(root)
  const updateCartNav = (totalQty: number) => {
    refs.navButtons.cart.textContent = `Корзина (${totalQty})`
  }

  const tableSubscription = subscribeTable((snapshot) => {
    const presentation = formatTableStatus(snapshot)
    refs.statusTitle.textContent = presentation.title
    refs.statusDetails.textContent = presentation.details ?? ''
    refs.app.dataset.tableSeverity = presentation.severity
  })

  const cartSubscription = subscribeCart((snapshot) => {
    updateCartNav(snapshot.totalQty)
  })
  updateCartNav(getCartSnapshot().totalQty)

  const navigate = (hash: string) => {
    if (window.location.hash !== hash) {
      window.location.hash = hash
    }
  }

  const routeListeners = new Set<() => void>()
  const notifyRouteChange = () => {
    routeListeners.forEach((listener) => listener())
  }

  const historyStack: string[] = []
  const updateHistory = (hash: string) => {
    if (!hash || hash === '#') {
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
  disposables.push(on(refs.navButtons.catalog, 'click', () => navigate('#/catalog')))
  disposables.push(on(refs.navButtons.cart, 'click', () => navigate('#/cart')))
  disposables.push(on(refs.navButtons.order, 'click', () => navigate('#/order')))

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
