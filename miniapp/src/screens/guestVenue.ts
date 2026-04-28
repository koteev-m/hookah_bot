import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestGetVenue, guestGetVenueMenu, guestStaffCall } from '../shared/api/guestApi'
import type { MenuCategoryDto, MenuItemDto, VenueDto } from '../shared/api/guestDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { updateItemCache } from '../shared/state/itemCache'
import { addToCart, getCartSnapshot, removeFromCart, subscribeCart } from '../shared/state/cartStore'
import { getTableContext, subscribe as subscribeTable } from '../shared/state/tableContext'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { formatPrice } from '../shared/ui/price'
import { showToast } from '../shared/ui/toast'

const MAX_ITEM_QTY = 50
const MAX_STAFF_COMMENT_LENGTH = 500

type VenueScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number | null
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
  staffReason: HTMLSelectElement
  staffComment: HTMLTextAreaElement
  staffCounter: HTMLParagraphElement
  staffButton: HTMLButtonElement
  staffMessage: HTMLParagraphElement
  staffError: HTMLDivElement
  staffErrorTitle: HTMLHeadingElement
  staffErrorMessage: HTMLParagraphElement
  staffErrorActions: HTMLDivElement
  staffErrorDetails: HTMLDivElement
  staffDisabledReason: HTMLParagraphElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function resolveTableHint(snapshot: ReturnType<typeof getTableContext>): string | null {
  switch (snapshot.status) {
    case 'missing':
      return 'Сначала отсканируйте QR'
    case 'invalid':
      return 'Некорректный QR. Обновите и попробуйте снова.'
    case 'notFound':
      return 'Стол не найден / обновите QR'
    case 'resolving':
      return 'Загрузка стола…'
    case 'error':
      return 'Не удалось загрузить стол. Попробуйте позже.'
    case 'resolved':
      if (!snapshot.tableToken) {
        return 'Сначала отсканируйте QR'
      }
      if (!snapshot.orderAllowed) {
        return snapshot.blockReasonText ?? 'Заказы временно недоступны.'
      }
      return null
    default:
      return null
  }
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

  const staffCard = el('div', { className: 'card staff-call' })
  const staffTitle = el('p', { className: 'field-label', text: 'Вызвать персонал' })
  const staffReasonLabel = el('p', { className: 'field-label', text: 'Причина' })
  const staffReason = document.createElement('select')
  staffReason.className = 'staff-select'
  staffReason.appendChild(new Option('Замена углей', 'COALS'))
  staffReason.appendChild(new Option('Счёт', 'BILL'))
  staffReason.appendChild(new Option('Подойти к столу', 'COME'))
  staffReason.appendChild(new Option('Другое', 'OTHER'))
  const staffCommentLabel = el('p', { className: 'field-label', text: 'Комментарий (необязательно)' })
  const staffComment = document.createElement('textarea')
  staffComment.className = 'staff-comment'
  staffComment.maxLength = MAX_STAFF_COMMENT_LENGTH
  staffComment.rows = 2
  staffComment.placeholder = 'Комментарий для персонала'
  const staffCounter = el('p', { className: 'field-counter', text: `0/${MAX_STAFF_COMMENT_LENGTH}` })
  const staffMessage = el('p', { className: 'staff-message', text: '' })
  staffMessage.hidden = true
  const staffButton = el('button', { text: 'Вызвать персонал' }) as HTMLButtonElement
  const staffError = el('div', { className: 'error-card' })
  staffError.hidden = true
  const staffErrorTitle = el('h3')
  const staffErrorMessage = el('p')
  const staffErrorActions = el('div', { className: 'error-actions' })
  const staffErrorDetails = el('div')
  append(staffError, staffErrorTitle, staffErrorMessage, staffErrorActions, staffErrorDetails)
  const staffDisabledReason = el('p', { className: 'disabled-reason', text: '' })
  staffDisabledReason.hidden = true
  append(
    staffCard,
    staffTitle,
    staffReasonLabel,
    staffReason,
    staffCommentLabel,
    staffComment,
    staffCounter,
    staffMessage,
    staffError,
    staffButton,
    staffDisabledReason
  )

  const menuBody = el('div', { className: 'menu-body' })

  append(wrapper, header, staffCard, status, message, retryButton, error, menuBody)
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
    retryButton,
    staffReason,
    staffComment,
    staffCounter,
    staffButton,
    staffMessage,
    staffError,
    staffErrorTitle,
    staffErrorMessage,
    staffErrorActions,
    staffErrorDetails,
    staffDisabledReason
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

function updateItemControls(refs: MenuItemRefs, qty: number, canOrder: boolean) {
  refs.qtyLabel.textContent = String(qty)
  if (!canOrder) {
    refs.addButton.hidden = true
    refs.qtyControls.style.display = 'none'
    refs.addButton.disabled = true
    refs.minusButton.disabled = true
    refs.plusButton.disabled = true
    return
  }
  refs.addButton.hidden = qty > 0
  refs.qtyControls.style.display = qty === 0 ? 'none' : 'inline-flex'
  refs.minusButton.disabled = qty <= 0
  refs.plusButton.disabled = qty >= MAX_ITEM_QTY || !refs.isAvailable
  refs.addButton.disabled = !refs.isAvailable
}

export function renderGuestVenueScreen(options: VenueScreenOptions) {
  const { root, backendUrl, isDebug, venueId } = options
  if (!root) return () => undefined

  const refs = buildVenueDom(root)
  refs.staffCounter.textContent = `${refs.staffComment.value.length}/${MAX_STAFF_COMMENT_LENGTH}`
  let disposed = false
  let menuAbort: AbortController | null = null
  let staffAbort: AbortController | null = null
  let isStaffCalling = false
  let messageTimer: number | null = null
  const itemRefs = new Map<number, MenuItemRefs>()
  let itemDisposables: Array<() => void> = []
  let tableSnapshot = getTableContext()
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

  const setStaffMessage = (text: string, tone: 'default' | 'success' = 'default') => {
    refs.staffMessage.textContent = text
    refs.staffMessage.hidden = !text
    refs.staffMessage.dataset.tone = tone
  }

  const hideStaffError = () => {
    refs.staffError.hidden = true
    refs.staffErrorActions.replaceChildren()
    refs.staffErrorDetails.replaceChildren()
  }

  const showError = (error: ApiErrorInfo) => {
    const normalizedCode = normalizeErrorCode(error)
    if (normalizedCode === ApiErrorCodes.UNAUTHORIZED || normalizedCode === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    refs.error.dataset.severity = presentation.severity
    const actions = presentation.actions.map((action) => {
      if (action.label === 'Повторить') {
        return { ...action, onClick: () => void loadVenue() }
      }
      return action
    })
    if (!actions.length) {
      actions.push({ label: 'Обновить', onClick: () => void loadVenue() })
    }

    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, {
      isDebug,
      extraNotes: presentation.debugLine ? [presentation.debugLine] : undefined
    })
    refs.error.hidden = false
  }

  const canCallStaff = () => tableSnapshot.status === 'resolved' && Boolean(tableSnapshot.tableToken)
  const canPlaceOrders = () =>
    tableSnapshot.status === 'resolved' && Boolean(tableSnapshot.tableToken) && tableSnapshot.orderAllowed

  const updateStaffState = () => {
    const canStaff = canCallStaff()
    const staffDisabledReason = canStaff ? null : resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR'
    refs.staffDisabledReason.textContent = staffDisabledReason ?? ''
    refs.staffDisabledReason.hidden = !staffDisabledReason
    refs.staffButton.disabled = isStaffCalling || !canStaff
  }

  const updateMenuOrderState = (snapshot: ReturnType<typeof getCartSnapshot> = getCartSnapshot()) => {
    const canOrder = canPlaceOrders()
    itemRefs.forEach((itemRefs, itemId) => {
      updateItemControls(itemRefs, snapshot.items.get(itemId) ?? 0, canOrder)
    })
  }

  const showStaffError = (error: ApiErrorInfo) => {
    const presentation = presentApiError(error, { isDebug, scope: 'table' })
    refs.staffErrorTitle.textContent = presentation.title
    refs.staffErrorMessage.textContent = presentation.message
    refs.staffError.dataset.severity = presentation.severity
    const actions = presentation.actions.map((action) => {
      if (action.label === 'Повторить') {
        return { ...action, onClick: () => void handleStaffCall() }
      }
      return action
    })
    if (!actions.length) {
      actions.push({ label: 'Повторить', onClick: () => void handleStaffCall() })
    }
    renderErrorActions(refs.staffErrorActions, actions)
    renderErrorDetails(refs.staffErrorDetails, error, {
      isDebug,
      extraNotes: presentation.debugLine ? [presentation.debugLine] : undefined
    })
    refs.staffError.hidden = false
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

    updateMenuOrderState(getCartSnapshot())
  }

  const bindItemActions = () => {
    itemRefs.forEach((refs, itemId) => {
      const addHandler = () => {
        if (!canPlaceOrders()) {
          setMessage(resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR')
          return
        }
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
        if (!canPlaceOrders()) {
          setMessage(resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR')
          return
        }
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

  const handleStaffCall = async () => {
    if (isStaffCalling) return
    setStaffMessage('', 'default')
    hideStaffError()
    if (!canCallStaff()) {
      setStaffMessage(resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR')
      return
    }
    const tableToken = tableSnapshot.tableToken
    if (!tableToken) {
      setStaffMessage(resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR')
      return
    }
    const commentValue = refs.staffComment.value.trim()
    if (commentValue.length > MAX_STAFF_COMMENT_LENGTH) {
      setStaffMessage('Комментарий должен быть не длиннее 500 символов.')
      return
    }
    const payload = {
      tableToken,
      reason: refs.staffReason.value,
      comment: commentValue ? commentValue : null
    }
    isStaffCalling = true
    updateStaffState()
    if (staffAbort) {
      staffAbort.abort()
    }
    const controller = new AbortController()
    staffAbort = controller
    const deps = buildApiDeps(isDebug)
    const result = await guestStaffCall(backendUrl, payload, deps, controller.signal)
    if (disposed) {
      return
    }
    if (controller.signal.aborted || staffAbort !== controller) {
      if (staffAbort === controller) {
        staffAbort = null
        isStaffCalling = false
        updateStaffState()
      }
      return
    }
    isStaffCalling = false
    staffAbort = null
    if (!result.ok) {
      const code = normalizeErrorCode(result.error)
      if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
        clearSession()
      }
      if (code === ApiErrorCodes.REQUEST_ABORTED) {
        updateStaffState()
        return
      }
      showStaffError(result.error)
      updateStaffState()
      return
    }
    const timeLabel = new Date(result.data.createdAtEpochSeconds * 1000).toLocaleTimeString('ru-RU', {
      hour: '2-digit',
      minute: '2-digit'
    })
    setStaffMessage(`Персонал вызван (${timeLabel})`, 'success')
    refs.staffComment.value = ''
    refs.staffCounter.textContent = `0/${MAX_STAFF_COMMENT_LENGTH}`
    updateStaffState()
    showToast('Вызов персонала отправлен')
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
    updateMenuOrderState(snapshot)
  })

  const tableSubscription = subscribeTable((snapshot) => {
    tableSnapshot = snapshot
    updateStaffState()
    updateMenuOrderState()
  })

  disposables.push(
    on(refs.retryButton, 'click', () => void loadVenue()),
    on(refs.staffButton, 'click', () => void handleStaffCall()),
    on(refs.staffComment, 'input', () => {
      if (refs.staffComment.value.length > MAX_STAFF_COMMENT_LENGTH) {
        refs.staffComment.value = refs.staffComment.value.slice(0, MAX_STAFF_COMMENT_LENGTH)
      }
      refs.staffCounter.textContent = `${refs.staffComment.value.length}/${MAX_STAFF_COMMENT_LENGTH}`
    })
  )

  void getAccessToken()
  void loadVenue()

  return () => {
    disposed = true
    menuAbort?.abort()
    staffAbort?.abort()
    if (messageTimer) {
      window.clearTimeout(messageTimer)
    }
    itemDisposables.forEach((dispose) => dispose())
    cartSubscription()
    tableSubscription()
    disposables.forEach((dispose) => dispose())
  }
}
