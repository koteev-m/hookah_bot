import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestGetVenue, guestGetVenueMenu } from '../shared/api/guestApi'
import type { MenuCategoryDto, MenuItemDto, VenueDto } from '../shared/api/guestDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { updateItemCache } from '../shared/state/itemCache'
import { addToCart, getCartSnapshot, removeFromCart, subscribeCart } from '../shared/state/cartStore'
import { append, el, on } from '../shared/ui/dom'
import { renderErrorDetails } from '../shared/ui/errorDetails'

const MAX_ITEM_QTY = 50

type VenueScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number | null
}

type ErrorAction = {
  label: string
  onClick: () => void
}

type MenuItemRefs = {
  qtyLabel: HTMLSpanElement
  addButton: HTMLButtonElement
  minusButton: HTMLButtonElement
  plusButton: HTMLButtonElement
  qtyControls: HTMLDivElement
  isAvailable: boolean
}

type VenueRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  venueTitle: HTMLHeadingElement
  venueLocation: HTMLParagraphElement
  menuBody: HTMLDivElement
  message: HTMLParagraphElement
  retryButton: HTMLButtonElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
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

function formatPrice(priceMinor: number, currency: string): string {
  const value = Number.isFinite(priceMinor) ? priceMinor / 100 : 0
  try {
    return new Intl.NumberFormat('ru-RU', {
      style: 'currency',
      currency
    }).format(value)
  } catch {
    return `${value.toFixed(2)} ${currency}`
  }
}

function buildVenueDom(root: HTMLDivElement): VenueRefs {
  const wrapper = el('div', { className: 'venue-screen' })
  const header = el('div', { className: 'venue-header' })
  const venueTitle = el('h3', { text: 'Загрузка...' })
  const venueLocation = el('p', { className: 'venue-location', text: '' })
  append(header, venueTitle, venueLocation)

  const status = el('p', { className: 'status', text: '' })
  const message = el('p', { className: 'status menu-message', text: '' })

  const retryButton = el('button', { className: 'button-small', text: 'Обновить' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const menuBody = el('div', { className: 'menu-body' })

  append(wrapper, header, status, message, retryButton, error, menuBody)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    venueTitle,
    venueLocation,
    menuBody,
    message,
    retryButton
  }
}

function renderMenuCategory(category: MenuCategoryDto, itemRefs: Map<number, MenuItemRefs>) {
  const categorySection = el('section', { className: 'menu-category' })
  const title = el('h4', { text: category.name })
  const list = el('div', { className: 'menu-items' })

  category.items.forEach((item) => {
    const row = el('div', { className: 'menu-item' })
    const info = el('div', { className: 'menu-item-info' })
    const name = el('strong', { text: item.name })
    const price = el('span', { className: 'menu-item-price', text: formatPrice(item.priceMinor, item.currency) })
    append(info, name, price)

    const controls = el('div', { className: 'menu-item-controls' })
    const addButton = el('button', { className: 'button-small', text: 'Добавить' }) as HTMLButtonElement
    const qtyControls = el('div', { className: 'qty-controls' }) as HTMLDivElement
    const minusButton = el('button', { className: 'button-small', text: '−' }) as HTMLButtonElement
    const qtyLabel = el('span', { className: 'qty-label', text: '0' }) as HTMLSpanElement
    const plusButton = el('button', { className: 'button-small', text: '+' }) as HTMLButtonElement
    append(qtyControls, minusButton, qtyLabel, plusButton)
    append(controls, addButton, qtyControls)

    if (!item.isAvailable) {
      const badge = el('span', { className: 'menu-item-badge', text: 'Нет в наличии' })
      info.appendChild(badge)
      addButton.disabled = true
      plusButton.disabled = true
    }

    itemRefs.set(item.id, {
      qtyLabel,
      addButton,
      minusButton,
      plusButton,
      qtyControls,
      isAvailable: item.isAvailable
    })

    row.appendChild(info)
    row.appendChild(controls)
    list.appendChild(row)
  })

  append(categorySection, title, list)
  return categorySection
}

function updateItemControls(refs: MenuItemRefs, qty: number) {
  refs.qtyLabel.textContent = String(qty)
  refs.addButton.hidden = qty > 0
  refs.qtyControls.hidden = qty === 0
  refs.minusButton.disabled = qty <= 0
  refs.plusButton.disabled = qty >= MAX_ITEM_QTY || !refs.isAvailable
  refs.addButton.disabled = !refs.isAvailable
}

export function renderGuestVenueScreen(options: VenueScreenOptions) {
  const { root, backendUrl, isDebug, venueId } = options
  if (!root) return () => undefined

  const refs = buildVenueDom(root)
  let disposed = false
  let menuAbort: AbortController | null = null
  let messageTimer: number | null = null
  const itemRefs = new Map<number, MenuItemRefs>()
  let itemDisposables: Array<() => void> = []
  const disposables: Array<() => void> = []

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
  }

  const setMessage = (text: string) => {
    if (messageTimer) {
      window.clearTimeout(messageTimer)
      messageTimer = null
    }
    refs.message.textContent = text
    if (text) {
      messageTimer = window.setTimeout(() => {
        refs.message.textContent = ''
        messageTimer = null
      }, 2500)
    }
  }

  const showError = (error: ApiErrorInfo) => {
    const code = normalizeErrorCode(error)
    const actions: ErrorAction[] = []
    let extraNotes: string[] | undefined

    if (code === ApiErrorCodes.NOT_FOUND) {
      refs.errorTitle.textContent = 'Заведение не найдено'
      refs.errorMessage.textContent = 'Проверьте ссылку или выберите другое заведение в каталоге.'
    } else if (code === ApiErrorCodes.SERVICE_SUSPENDED || code === ApiErrorCodes.SUBSCRIPTION_BLOCKED) {
      refs.errorTitle.textContent = 'Доступ к заведению ограничен'
      refs.errorMessage.textContent =
        code === ApiErrorCodes.SERVICE_SUSPENDED
          ? 'Заведение временно недоступно. Попробуйте позже.'
          : 'Заказы временно недоступны. Попробуйте позже.'
      extraNotes = ['В зависимости от режима backend страница может быть скрыта (423/404).']
    } else if (code === ApiErrorCodes.NETWORK_ERROR) {
      refs.errorTitle.textContent = 'Нет соединения'
      refs.errorMessage.textContent = 'Проверьте подключение к интернету и повторите попытку.'
    } else if (code === ApiErrorCodes.DATABASE_UNAVAILABLE) {
      refs.errorTitle.textContent = 'Меню временно недоступно'
      refs.errorMessage.textContent = 'Попробуйте ещё раз чуть позже.'
    } else if (code === ApiErrorCodes.INITDATA_INVALID || code === ApiErrorCodes.UNAUTHORIZED) {
      refs.errorTitle.textContent = 'Перезапустите Mini App'
      refs.errorMessage.textContent = 'Сессия недействительна. Перезапустите Mini App в Telegram.'
      actions.push({ label: 'Перезапустить', onClick: () => window.location.reload() })
      clearSession()
    } else {
      refs.errorTitle.textContent = 'Не удалось загрузить меню'
      refs.errorMessage.textContent = 'Попробуйте обновить страницу или повторить запрос позже.'
    }

    if (!actions.length) {
      actions.push({ label: 'Обновить', onClick: () => void loadVenue() })
    }

    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug, extraNotes })
    refs.error.hidden = false
  }

  const renderVenueInfo = (venue: VenueDto) => {
    refs.venueTitle.textContent = venue.name
    const locationParts = [venue.city, venue.address].filter(Boolean)
    refs.venueLocation.textContent = locationParts.length ? locationParts.join(', ') : 'Адрес не указан'
  }

  const renderMenu = (categories: MenuCategoryDto[]) => {
    refs.menuBody.replaceChildren()
    itemRefs.clear()
    itemDisposables.forEach((dispose) => dispose())
    itemDisposables = []
    if (!categories.length) {
      refs.menuBody.appendChild(el('p', { text: 'Меню пока пустое.' }))
      return
    }
    categories.forEach((category) => {
      refs.menuBody.appendChild(renderMenuCategory(category, itemRefs))
    })

    const snapshot = getCartSnapshot()
    itemRefs.forEach((refs, itemId) => {
      updateItemControls(refs, snapshot.items.get(itemId) ?? 0)
    })
  }

  const bindItemActions = () => {
    itemRefs.forEach((refs, itemId) => {
      const addHandler = () => {
        const result = addToCart(itemId)
        if (!result.ok) {
          if (result.reason === 'limit') {
            setMessage('Лимит: не более 50 позиций в корзине.')
          } else {
            setMessage('Не удалось добавить позицию.')
          }
          return
        }
        setMessage('')
      }
      const removeHandler = () => {
        removeFromCart(itemId)
        setMessage('')
      }
      refs.addButton.addEventListener('click', addHandler)
      refs.plusButton.addEventListener('click', addHandler)
      refs.minusButton.addEventListener('click', removeHandler)
      itemDisposables.push(() => {
        refs.addButton.removeEventListener('click', addHandler)
        refs.plusButton.removeEventListener('click', addHandler)
        refs.minusButton.removeEventListener('click', removeHandler)
      })
    })
  }

  async function loadVenue() {
    if (disposed) return
    if (!venueId) {
      setStatus('')
      refs.menuBody.replaceChildren()
      showError({ status: 404, code: ApiErrorCodes.NOT_FOUND, message: 'Venue not found' })
      return
    }

    setStatus('Загрузка меню...')
    hideError()
    setMessage('')
    refs.menuBody.replaceChildren()

    if (menuAbort) {
      menuAbort.abort()
    }
    const controller = new AbortController()
    menuAbort = controller

    const deps = buildApiDeps(isDebug)
    const [venueResult, menuResult] = await Promise.all([
      guestGetVenue(backendUrl, venueId, deps, controller.signal),
      guestGetVenueMenu(backendUrl, venueId, deps, controller.signal)
    ])

    if (disposed || menuAbort !== controller) {
      return
    }
    if (
      (!venueResult.ok && venueResult.error.code === REQUEST_ABORTED_CODE) ||
      (!menuResult.ok && menuResult.error.code === REQUEST_ABORTED_CODE)
    ) {
      return
    }
    if (!venueResult.ok) {
      setStatus('')
      showError(venueResult.error)
      return
    }
    if (!menuResult.ok) {
      setStatus('')
      showError(menuResult.error)
      return
    }

    renderVenueInfo(venueResult.data.venue)
    renderMenu(menuResult.data.categories ?? [])
    bindItemActions()

    const itemsToCache: MenuItemDto[] = menuResult.data.categories.flatMap((category) => category.items)
    updateItemCache(
      itemsToCache.map((item) => ({
        itemId: item.id,
        name: item.name,
        priceMinor: item.priceMinor,
        currency: item.currency
      }))
    )

    setStatus('')
  }

  const cartSubscription = subscribeCart((snapshot) => {
    itemRefs.forEach((refs, itemId) => {
      updateItemControls(refs, snapshot.items.get(itemId) ?? 0)
    })
  })

  disposables.push(on(refs.retryButton, 'click', () => void loadVenue()))

  void getAccessToken()
  void loadVenue()

  return () => {
    disposed = true
    menuAbort?.abort()
    if (messageTimer) {
      window.clearTimeout(messageTimer)
    }
    itemDisposables.forEach((dispose) => dispose())
    cartSubscription()
    disposables.forEach((dispose) => dispose())
  }
}
