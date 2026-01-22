import { formatTableStatus, initTableContext, subscribe as subscribeTable } from '../shared/state/tableContext'
import { addToCart, clearCart, getCartSnapshot, removeFromCart, subscribeCart } from '../shared/state/cartStore'
import { parsePositiveInt } from '../shared/parse'
import { append, el, on } from '../shared/ui/dom'

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

type PageResult = {
  node: HTMLElement
  cartCount: HTMLSpanElement
}

function buildPageLayout(title: string): { section: HTMLElement; body: HTMLDivElement; cartCount: HTMLSpanElement } {
  const section = el('section', { className: 'card' })
  const heading = el('h2', { text: title })
  const cartLine = el('p', { className: 'page-meta' })
  const cartCount = el('span', { text: '0' })
  append(cartLine, document.createTextNode('Товаров в корзине: '), cartCount)
  const body = el('div', { className: 'page-body' })
  append(section, heading, cartLine, body)
  return { section, body, cartCount }
}

function buildCatalogPage(): PageResult {
  const { section, body, cartCount } = buildPageLayout('Каталог')
  const hint = el('p', { text: 'Каталог появится здесь после подключения данных.' })
  const actionRow = el('div', { className: 'button-row' })
  const addButton = el('button', { className: 'button-small', text: 'Добавить тестовый товар' })
  const removeButton = el('button', { className: 'button-small', text: 'Уменьшить количество' })
  const clearButton = el('button', { className: 'button-small', text: 'Очистить корзину' })
  const message = el('p', { className: 'status', text: '' })
  append(actionRow, addButton, removeButton, clearButton)
  append(body, hint, actionRow, message)

  const updateMessage = (text: string) => {
    message.textContent = text
  }

  addButton.addEventListener('click', () => {
    const result = addToCart(101)
    if (!result.ok) {
      updateMessage('Достигнут лимит по количеству позиций в корзине.')
      return
    }
    updateMessage('Товар добавлен в корзину.')
  })
  removeButton.addEventListener('click', () => {
    removeFromCart(101)
    updateMessage('Количество уменьшено.')
  })
  clearButton.addEventListener('click', () => {
    clearCart()
    updateMessage('Корзина очищена.')
  })

  return { node: section, cartCount }
}

function buildVenuePage(venueId: number | null): PageResult {
  const { section, body, cartCount } = buildPageLayout('Заведение')
  const venueLine = el('p', { text: `Venue ID: ${venueId ?? '—'}` })
  const hint = el('p', { text: 'Страница заведения будет здесь.' })
  append(body, venueLine, hint)
  return { node: section, cartCount }
}

function buildCartPage(): PageResult {
  const { section, body, cartCount } = buildPageLayout('Корзина')
  const hint = el('p', { text: 'Здесь появятся выбранные позиции.' })
  append(body, hint)
  return { node: section, cartCount }
}

function buildOrderPage(): PageResult {
  const { section, body, cartCount } = buildPageLayout('Заказ')
  const hint = el('p', { text: 'Экран активного заказа появится позже.' })
  append(body, hint)
  return { node: section, cartCount }
}

function renderRouteContent(route: Route, container: HTMLElement): HTMLSpanElement {
  let content: PageResult
  switch (route.name) {
    case 'venue':
      content = buildVenuePage(route.venueId)
      break
    case 'cart':
      content = buildCartPage()
      break
    case 'order':
      content = buildOrderPage()
      break
    case 'catalog':
    default:
      content = buildCatalogPage()
      break
  }
  container.replaceChildren(content.node)
  return content.cartCount
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

  let currentCartCount: HTMLSpanElement | null = null
  const cartSubscription = subscribeCart((snapshot) => {
    updateCartNav(snapshot.totalQty)
    if (currentCartCount) {
      currentCartCount.textContent = String(snapshot.totalQty)
    }
  })
  updateCartNav(getCartSnapshot().totalQty)

  const navigate = (hash: string) => {
    if (window.location.hash !== hash) {
      window.location.hash = hash
    }
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.navButtons.catalog, 'click', () => navigate('#/catalog')))
  disposables.push(on(refs.navButtons.cart, 'click', () => navigate('#/cart')))
  disposables.push(on(refs.navButtons.order, 'click', () => navigate('#/order')))

  const render = () => {
    const route = resolveRoute()
    updateNav(refs.navButtons, route.name)
    currentCartCount = renderRouteContent(route, refs.content)
    currentCartCount.textContent = String(getCartSnapshot().totalQty)
  }

  const onHashChange = () => {
    render()
  }

  window.addEventListener('hashchange', onHashChange)
  render()

  return () => {
    window.removeEventListener('hashchange', onHashChange)
    disposables.forEach((dispose) => dispose())
    cartSubscription()
    tableSubscription()
  }
}
