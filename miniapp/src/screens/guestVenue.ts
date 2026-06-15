import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestGetVenue, guestGetVenueInfoSections, guestGetVenueMenu, guestStaffCall } from '../shared/api/guestApi'
import type {
  MenuCategoryDto,
  MenuItemDto,
  MenuItemOptionDto,
  MenuResponse,
  VenueDto,
  VenueInfoSectionDto,
  VenueInfoSectionsResponse
} from '../shared/api/guestDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { updateItemCache } from '../shared/state/itemCache'
import { addToCart, getCartSnapshot, removeFromCart, subscribeCart } from '../shared/state/cartStore'
import { getTableContext, subscribe as subscribeTable } from '../shared/state/tableContext'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { formatPrice } from '../shared/ui/price'
import { showToast } from '../shared/ui/toast'
import { renderGuestShiftExtensionCard, type GuestShiftExtensionAvailability } from './guestShiftExtension'

const MAX_ITEM_QTY = 50
const MAX_STAFF_COMMENT_LENGTH = 500
const MAX_ITEM_PREFERENCE_NOTE_LENGTH = 200

type VenueScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number | null
  openStaffCall?: boolean
  onBookVenue?: (venueId: number) => void
}

type MenuItemRefs = {
  item: MenuItemDto
  qtyLabel: HTMLSpanElement
  addButton: HTMLButtonElement
  minusButton: HTMLButtonElement
  plusButton: HTMLButtonElement
  qtyControls: HTMLDivElement
  isAvailable: boolean
  hasOptions: boolean
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
  bookingButton: HTMLButtonElement
  extensionSlot: HTMLDivElement
  menuBody: HTMLDivElement
  message: HTMLParagraphElement
  retryButton: HTMLButtonElement
  staffSlot: HTMLDivElement
  staffCard: HTMLDivElement
  staffReason: HTMLSelectElement
  staffComment: HTMLTextAreaElement
  staffCounter: HTMLParagraphElement
  staffButton: HTMLButtonElement
  staffCloseButton: HTMLButtonElement
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
  const bookingButton = el('button', { className: 'button-secondary button-small', text: 'Забронировать' }) as HTMLButtonElement
  append(header, venueTitle, venueLocation, bookingButton)

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

  const staffSlot = el('div', { className: 'staff-call-slot' }) as HTMLDivElement
  const staffCard = el('div', { className: 'card staff-call' }) as HTMLDivElement
  const staffTitle = el('p', { className: 'field-label', text: 'Вызвать персонал' })
  const staffCloseButton = el('button', { className: 'button-secondary button-small', text: '← К меню' }) as HTMLButtonElement
  const staffReasonLabel = el('p', { className: 'field-label', text: 'Причина' })
  const staffReason = document.createElement('select')
  staffReason.className = 'staff-select'
  staffReason.appendChild(new Option('Замена углей', 'COALS'))
  staffReason.appendChild(new Option('Счёт', 'BILL'))
  staffReason.appendChild(new Option('Консультация', 'COME'))
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
    staffCloseButton,
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
  const extensionSlot = el('div', { className: 'shift-extension-slot' }) as HTMLDivElement

  append(wrapper, header, staffSlot, status, message, retryButton, error, menuBody)
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
    bookingButton,
    extensionSlot,
    menuBody,
    message,
    retryButton,
    staffSlot,
    staffCard,
    staffReason,
    staffComment,
    staffCounter,
    staffButton,
    staffCloseButton,
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
    const itemOptions = getAvailableItemOptions(item)
    const row = el('div', { className: 'menu-item' })
    const info = el('div', { className: 'menu-item-info' })
    const name = el('strong', { text: item.name })
    const price = el('span', { className: 'menu-item-price', text: formatPrice(item.priceMinor, item.currency) })
    append(info, name, price)
    if (itemOptions.length > 0) {
      info.appendChild(el('span', { className: 'menu-item-option-hint', text: 'Выберите вкус' }))
    }

    const controls = el('div', { className: 'menu-item-controls' })
    const addButton = el('button', {
      className: 'button-small',
      text: itemOptions.length > 0 ? 'Выбрать' : 'Добавить'
    }) as HTMLButtonElement
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
      item,
      qtyLabel,
      addButton,
      minusButton,
      plusButton,
      qtyControls,
      isAvailable: item.isAvailable,
      hasOptions: itemOptions.length > 0
    })

    row.appendChild(info)
    row.appendChild(controls)
    list.appendChild(row)
  })

  append(categorySection, title, list)
  return categorySection
}

function getAvailableItemOptions(item: MenuItemDto): MenuItemOptionDto[] {
  return (item.options ?? []).filter((option) => option.isAvailable !== false)
}

function formatOptionButtonText(option: MenuItemOptionDto, currency: string): string {
  if (option.priceDeltaMinor > 0) {
    return `${option.name} +${formatPrice(option.priceDeltaMinor, currency)}`
  }
  if (option.priceDeltaMinor < 0) {
    return `${option.name} ${formatPrice(option.priceDeltaMinor, currency)}`
  }
  return `${option.name} · Без доплаты`
}

function resolveInfoMediaUrl(backendUrl: string, mediaUrl: string) {
  try {
    return new URL(mediaUrl, backendUrl).toString()
  } catch {
    return mediaUrl
  }
}

function normalizeInfoMedia(section: VenueInfoSectionDto) {
  return Array.isArray(section.media)
    ? section.media.filter((media) => typeof media.url === 'string' && media.url.trim().length > 0)
    : []
}

function resolveInfoMediaCount(section: VenueInfoSectionDto, mediaItems: ReturnType<typeof normalizeInfoMedia>) {
  return typeof section.mediaCount === 'number' && Number.isFinite(section.mediaCount)
    ? section.mediaCount
    : mediaItems.length
}

function isImageInfoMedia(mediaType: string) {
  const normalized = mediaType.toLowerCase()
  return normalized === 'image' || normalized === 'photo'
}

function isPdfInfoMedia(mediaType: string) {
  const normalized = mediaType.toLowerCase()
  return normalized === 'pdf' || normalized === 'application/pdf'
}

function renderInfoSection(section: VenueInfoSectionDto, backendUrl: string) {
  const sectionCard = el('section', { className: 'card venue-info-section' })
  const title = el('h4', { text: section.displayTitle || section.title })
  sectionCard.appendChild(title)

  const text = section.text?.trim()
  if (text) {
    sectionCard.appendChild(el('p', { text }))
  }
  const mediaItems = normalizeInfoMedia(section)
  const mediaCount = resolveInfoMediaCount(section, mediaItems)
  if (mediaItems.length > 0) {
    const mediaList = el('div', { className: 'venue-info-media-list' })
    mediaItems.forEach((media, index) => {
      const mediaUrl = resolveInfoMediaUrl(backendUrl, media.url ?? '')
      const mediaType = media.mediaType ?? ''
      if (isImageInfoMedia(mediaType)) {
        const frame = el('div', { className: 'venue-info-media-image' })
        const loading = el('p', { className: 'status', text: 'Загрузка изображения…' })
        const image = el('img')
        const displayIndex = typeof media.sortOrder === 'number' ? media.sortOrder + 1 : index + 1
        image.alt = `${section.displayTitle || section.title} ${displayIndex}`
        image.loading = 'lazy'
        let settled = false
        const failImage = () => {
          if (settled) return
          settled = true
          frame.replaceChildren(
            el('p', {
              className: 'status',
              text: 'Не удалось загрузить изображение. Файл можно открыть в Telegram-боте.'
            })
          )
        }
        const timeoutId = window.setTimeout(failImage, 12000)
        image.addEventListener('load', () => {
          if (settled) return
          settled = true
          window.clearTimeout(timeoutId)
          loading.remove()
        })
        image.addEventListener('error', () => {
          window.clearTimeout(timeoutId)
          failImage()
        })
        frame.appendChild(loading)
        frame.appendChild(image)
        image.src = mediaUrl
        mediaList.appendChild(frame)
        return
      }

      if (isPdfInfoMedia(mediaType)) {
        const link = el('a', { className: 'button-small button-secondary', text: 'Открыть PDF' })
        link.href = mediaUrl
        link.target = '_blank'
        link.rel = 'noopener noreferrer'
        mediaList.appendChild(link)
        return
      }

      const link = el('a', { className: 'button-small button-secondary', text: 'Открыть файл' })
      link.href = mediaUrl
      link.target = '_blank'
      link.rel = 'noopener noreferrer'
      mediaList.appendChild(link)
    })
    sectionCard.appendChild(mediaList)
  } else if (mediaCount > 0) {
    sectionCard.appendChild(el('p', { className: 'status', text: 'Медиа пока не удалось показать.' }))
  }

  return sectionCard
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
  if (refs.hasOptions) {
    refs.addButton.hidden = false
    refs.addButton.textContent = 'Выбрать'
    refs.qtyControls.style.display = 'none'
    refs.addButton.disabled = !refs.isAvailable
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
  const { root, backendUrl, isDebug, venueId, onBookVenue } = options
  if (!root) return () => undefined

  const refs = buildVenueDom(root)
  refs.bookingButton.disabled = !venueId
  refs.staffCounter.textContent = `${refs.staffComment.value.length}/${MAX_STAFF_COMMENT_LENGTH}`
  let disposed = false
  let menuAbort: AbortController | null = null
  let staffAbort: AbortController | null = null
  let isStaffCalling = false
  let staffFormOpen = options.openStaffCall === true
  let messageTimer: number | null = null
  const itemRefs = new Map<number, MenuItemRefs>()
  let itemDisposables: Array<() => void> = []
  let tableSnapshot = getTableContext()
  let renderedOrderMenuMode: boolean | null = null
  let selectedCategoryId: number | null = null
  let selectedService: 'shift-extension' | null = null
  let selectedOptionItemId: number | null = null
  let latestCategories: MenuCategoryDto[] = []
  let shiftExtensionAvailability: GuestShiftExtensionAvailability = {
    visible: false,
    enabled: false,
    pending: false,
    unavailableReason: null
  }
  let menuRendererReady = false
  const disposables: Array<() => void> = []
  const extensionDispose = renderGuestShiftExtensionCard({
    root: refs.extensionSlot,
    backendUrl,
    isDebug,
    venueId,
    mode: 'menuDetail',
    onAvailabilityChange: (availability) => {
      shiftExtensionAvailability = availability
      if (!availability.visible && selectedService === 'shift-extension') {
        selectedService = null
      }
      if (menuRendererReady && renderedOrderMenuMode === true) {
        renderMenu(latestCategories)
        bindItemActions()
      }
    }
  })

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

  const canCallStaff = () =>
    tableSnapshot.status === 'resolved' &&
    Boolean(tableSnapshot.tableToken) &&
    tableSnapshot.venueId === venueId &&
    tableSnapshot.tableSessionActive &&
    tableSnapshot.available === true
  const canPlaceOrders = () =>
    tableSnapshot.status === 'resolved' &&
    Boolean(tableSnapshot.tableToken) &&
    tableSnapshot.venueId === venueId &&
    tableSnapshot.orderAllowed
  const shouldShowOrderMenu = () => canPlaceOrders()

  const updateBookingButtonVisibility = () => {
    const inTableContext = tableSnapshot.status === 'resolved' && tableSnapshot.venueId === venueId
    refs.bookingButton.hidden = inTableContext || !onBookVenue
    refs.bookingButton.disabled = !venueId || inTableContext
  }

  const updateStaffState = () => {
    updateBookingButtonVisibility()
    const canStaff = canCallStaff()
    if (!canStaff) {
      staffFormOpen = false
    }
    if (canStaff && staffFormOpen) {
      if (!refs.staffCard.isConnected) {
        refs.staffSlot.replaceChildren(refs.staffCard)
      }
    } else {
      refs.staffCard.remove()
    }
    const staffDisabledReason = canStaff ? null : resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR'
    refs.staffButton.textContent = tableSnapshot.tableNumber
      ? `Вызвать персонал к столу №${tableSnapshot.tableNumber}`
      : 'Вызвать персонал'
    refs.staffDisabledReason.textContent = staffDisabledReason ?? ''
    refs.staffDisabledReason.hidden = !staffDisabledReason
    refs.staffButton.disabled = isStaffCalling || !canStaff
  }

  const updateMenuOrderState = (snapshot: ReturnType<typeof getCartSnapshot> = getCartSnapshot()) => {
    const canOrder = canPlaceOrders()
    itemRefs.forEach((itemRefs, itemId) => {
      const qty = Array.from(snapshot.items.values()).reduce(
        (sum, line) => (line.itemId === itemId ? sum + line.qty : sum),
        0
      )
      updateItemControls(itemRefs, qty, canOrder)
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

  const renderPreQrInfo = (venue: VenueDto, sections: VenueInfoSectionDto[]) => {
    refs.menuBody.replaceChildren()
    itemRefs.clear()
    itemDisposables.forEach((dispose) => dispose())
    itemDisposables = []

    const intro = el('section', { className: 'card venue-info-section' })
    intro.appendChild(el('h4', { text: 'ℹ️ Информация' }))
    const description = venue.cardDescription?.trim()
    if (description) {
      intro.appendChild(el('p', { text: description }))
    }
    const contact = venue.guestContact?.trim()
    if (contact) {
      intro.appendChild(el('p', { className: 'status', text: `Контакт: ${contact}` }))
    }
    intro.appendChild(
      el('p', {
        className: 'status',
        text: 'Заказное меню и корзина доступны после сканирования QR-кода на столе.'
      })
    )
    refs.menuBody.appendChild(intro)

    if (!sections.length) {
      refs.menuBody.appendChild(el('p', { text: 'Информация пока не заполнена.' }))
      return
    }

    sections.forEach((section) => {
      refs.menuBody.appendChild(renderInfoSection(section, backendUrl))
    })
  }

  const renderMenuCategoryList = (categories: MenuCategoryDto[]) => {
    const list = el('section', { className: 'card menu-category-list' })
    list.appendChild(el('h4', { text: 'Выберите раздел меню' }))
    categories.forEach((category) => {
      const availableCount = category.items.filter((item) => item.isAvailable).length
      const button = el('button', {
        className: 'menu-category-button',
        text: `${category.name} · ${category.items.length} позиций`
      }) as HTMLButtonElement
      if (availableCount === 0) {
        button.appendChild(el('span', { className: 'menu-category-note', text: 'Нет доступных позиций' }))
      }
      const handler = () => {
        selectedCategoryId = category.id
        selectedService = null
        selectedOptionItemId = null
        renderMenu(categories)
        bindItemActions()
      }
      button.addEventListener('click', handler)
      itemDisposables.push(() => button.removeEventListener('click', handler))
      list.appendChild(button)
    })
    if (shiftExtensionAvailability.visible) {
      const button = el('button', {
        className: 'menu-category-button',
        text: 'Продление работы заведения'
      }) as HTMLButtonElement
      const noteText = shiftExtensionAvailability.pending
        ? 'Ожидает подтверждения'
        : shiftExtensionAvailability.copy ?? shiftExtensionAvailability.unavailableText ?? ''
      if (noteText) {
        button.appendChild(el('span', { className: 'menu-category-note', text: noteText }))
      }
      const canOpenExtension = shiftExtensionAvailability.enabled || shiftExtensionAvailability.pending
      button.disabled = !canOpenExtension
      if (canOpenExtension) {
        const handler = () => {
          selectedCategoryId = null
          selectedService = 'shift-extension'
          selectedOptionItemId = null
          renderMenu(categories)
          refs.extensionSlot.dispatchEvent(new Event('hookah:guest-venue-refresh'))
        }
        button.addEventListener('click', handler)
        itemDisposables.push(() => button.removeEventListener('click', handler))
      }
      list.appendChild(button)
    }
    return list
  }

  const findMenuItem = (itemId: number) =>
    latestCategories.flatMap((category) => category.items).find((item) => item.id === itemId) ?? null

  const renderOptionPicker = (item: MenuItemDto, categories: MenuCategoryDto[]) => {
    const section = el('section', { className: 'card menu-option-picker' })
    section.appendChild(el('h4', { text: 'Выберите вкус' }))
    section.appendChild(el('p', { className: 'menu-option-item-name', text: item.name }))
    const noteField = el('label', { className: 'menu-option-note-field' })
    const noteText = el('span', { text: 'Пожелание к вкусу' })
    const noteInput = document.createElement('input')
    noteInput.className = 'menu-option-note-input'
    noteInput.type = 'text'
    noteInput.maxLength = MAX_ITEM_PREFERENCE_NOTE_LENGTH
    noteInput.placeholder = 'Например: поменьше холодка, без мяты, покрепче'
    append(noteField, noteText, noteInput)
    section.appendChild(noteField)
    const optionList = el('div', { className: 'menu-option-list' })
    const options = getAvailableItemOptions(item)
    options.forEach((option) => {
      const button = el('button', {
        className: 'menu-option-button',
        text: formatOptionButtonText(option, item.currency)
      }) as HTMLButtonElement
      const handler = () => {
        if (!canPlaceOrders()) {
          setMessage(resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR')
          return
        }
        const result = addToCart(item.id, {
          selectedOptionId: option.id,
          selectedOptionName: option.name,
          priceDeltaMinor: option.priceDeltaMinor,
          preferenceNote: noteInput.value
        })
        if (!result.ok) {
          setMessage(result.reason === 'limit' ? 'Лимит: не более 50 позиций в корзине.' : 'Не удалось добавить позицию.')
          return
        }
        selectedOptionItemId = null
        setMessage('')
        renderMenu(categories)
        bindItemActions()
      }
      button.addEventListener('click', handler)
      itemDisposables.push(() => button.removeEventListener('click', handler))
      optionList.appendChild(button)
    })
    if (!options.length) {
      section.appendChild(el('p', { className: 'status', text: 'Для этой позиции сейчас нет доступных вариантов.' }))
    } else {
      section.appendChild(optionList)
    }
    return section
  }

  const renderMenu = (categories: MenuCategoryDto[]) => {
    refs.menuBody.replaceChildren()
    itemRefs.clear()
    itemDisposables.forEach((dispose) => dispose())
    itemDisposables = []
    if (!categories.length && !shiftExtensionAvailability.visible) {
      refs.menuBody.appendChild(el('p', { text: 'Меню заведения пока не загружено.' }))
      return
    }
    if (selectedService === 'shift-extension') {
      if (!shiftExtensionAvailability.visible) {
        selectedService = null
        renderMenu(categories)
        return
      }
      const backButton = el('button', { className: 'button-small button-secondary', text: '← К разделам меню' }) as HTMLButtonElement
      const backHandler = () => {
        selectedService = null
        selectedOptionItemId = null
        renderMenu(categories)
      }
      backButton.addEventListener('click', backHandler)
      itemDisposables.push(() => backButton.removeEventListener('click', backHandler))
      refs.menuBody.appendChild(backButton)
      refs.menuBody.appendChild(refs.extensionSlot)
      return
    }
    if (selectedOptionItemId != null) {
      const item = findMenuItem(selectedOptionItemId)
      if (!item) {
        selectedOptionItemId = null
        renderMenu(categories)
        return
      }
      const backButton = el('button', { className: 'button-small button-secondary', text: '← Назад' }) as HTMLButtonElement
      const backHandler = () => {
        selectedOptionItemId = null
        renderMenu(categories)
        bindItemActions()
      }
      backButton.addEventListener('click', backHandler)
      itemDisposables.push(() => backButton.removeEventListener('click', backHandler))
      refs.menuBody.appendChild(backButton)
      refs.menuBody.appendChild(renderOptionPicker(item, categories))
      return
    }
    const selectedCategory = selectedCategoryId
      ? categories.find((category) => category.id === selectedCategoryId) ?? null
      : null
    if (!selectedCategory) {
      selectedCategoryId = null
      refs.menuBody.appendChild(renderMenuCategoryList(categories))
      return
    }

    const backButton = el('button', { className: 'button-small button-secondary', text: '← К разделам меню' }) as HTMLButtonElement
    const backHandler = () => {
      selectedCategoryId = null
      selectedService = null
      selectedOptionItemId = null
      renderMenu(categories)
    }
    backButton.addEventListener('click', backHandler)
    itemDisposables.push(() => backButton.removeEventListener('click', backHandler))
    refs.menuBody.appendChild(backButton)
    refs.menuBody.appendChild(renderMenuCategory(selectedCategory, itemRefs))

    updateMenuOrderState(getCartSnapshot())
  }

  menuRendererReady = true

  const bindItemActions = () => {
    itemRefs.forEach((refs, itemId) => {
      const addHandler = () => {
        if (!canPlaceOrders()) {
          setMessage(resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR')
          return
        }
        if (refs.hasOptions) {
          selectedOptionItemId = itemId
          selectedService = null
          renderMenu(latestCategories)
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
    const tableSessionId = tableSnapshot.tableSessionId
    if (typeof tableSessionId !== 'number' || !Number.isFinite(tableSessionId)) {
      setStaffMessage('Не удалось определить сессию стола. Обновите QR и попробуйте снова.')
      return
    }
    const commentValue = refs.staffComment.value.trim()
    if (commentValue.length > MAX_STAFF_COMMENT_LENGTH) {
      setStaffMessage('Комментарий должен быть не длиннее 500 символов.')
      return
    }
    const payload = {
      tableToken,
      tableSessionId,
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

    const orderMenuMode = shouldShowOrderMenu()
    updateBookingButtonVisibility()
    setStatus(orderMenuMode ? 'Загрузка меню...' : 'Загрузка информации...')
    hideError()
    setMessage('')
    refs.menuBody.replaceChildren()

    if (menuAbort) {
      menuAbort.abort()
    }
    const controller = new AbortController()
    menuAbort = controller

    const deps = buildApiDeps(isDebug)
    let didTimeout = false
    const timeoutId = window.setTimeout(() => {
      didTimeout = true
      controller.abort()
    }, 15000)
    const [venueResult, detailsResult] = await Promise.all([
      guestGetVenue(backendUrl, venueId, deps, controller.signal),
      orderMenuMode
        ? guestGetVenueMenu(backendUrl, venueId, deps, controller.signal)
        : guestGetVenueInfoSections(backendUrl, venueId, deps, controller.signal)
    ])
    window.clearTimeout(timeoutId)

    if (disposed || menuAbort !== controller) {
      return
    }
    if (
      (!venueResult.ok && venueResult.error.code === REQUEST_ABORTED_CODE) ||
      (!detailsResult.ok && detailsResult.error.code === REQUEST_ABORTED_CODE)
    ) {
      if (didTimeout && menuAbort === controller) {
        setStatus('')
        refs.menuBody.replaceChildren(
          el('p', {
            className: 'status',
            text: orderMenuMode
              ? 'Не удалось загрузить меню. Нажмите «Обновить».'
              : 'Не удалось загрузить информацию. Нажмите «Обновить».'
          })
        )
      }
      return
    }
    if (!venueResult.ok) {
      setStatus('')
      showError(venueResult.error)
      return
    }
    if (!detailsResult.ok) {
      setStatus('')
      showError(detailsResult.error)
      return
    }

    setStatus('')
    try {
      renderVenueInfo(venueResult.data.venue)
      updateBookingButtonVisibility()
      renderedOrderMenuMode = orderMenuMode
      if (orderMenuMode) {
        const menuData = detailsResult.data as MenuResponse
        const categories = menuData.categories ?? []
        latestCategories = categories
        if (!categories.some((category) => category.id === selectedCategoryId)) {
          selectedCategoryId = null
          selectedOptionItemId = null
        }
        const selectedOptionItemExists =
          selectedOptionItemId == null ||
          categories.some((category) => category.items.some((item) => item.id === selectedOptionItemId))
        if (!selectedOptionItemExists) {
          selectedOptionItemId = null
        }
        renderMenu(categories)
        bindItemActions()

        const itemsToCache: MenuItemDto[] = categories.flatMap((category) => category.items)
        updateItemCache(
          itemsToCache.map((item) => ({
            itemId: item.id,
            name: item.name,
            priceMinor: item.priceMinor,
            currency: item.currency,
            options: getAvailableItemOptions(item).map((option) => ({
              id: option.id,
              name: option.name,
              priceDeltaMinor: option.priceDeltaMinor
            }))
          }))
        )
      } else {
        const infoData = detailsResult.data as VenueInfoSectionsResponse
        renderPreQrInfo(venueResult.data.venue, Array.isArray(infoData.sections) ? infoData.sections : [])
      }
    } catch {
      refs.menuBody.appendChild(
        el('p', {
          className: 'status',
          text: orderMenuMode
            ? 'Не удалось показать меню. Нажмите «Обновить».'
            : 'Не удалось показать часть информации. Нажмите «Обновить».'
        })
      )
    }
  }

  const cartSubscription = subscribeCart((snapshot) => {
    updateMenuOrderState(snapshot)
  })

  const tableSubscription = subscribeTable((snapshot) => {
    tableSnapshot = snapshot
    updateStaffState()
    updateMenuOrderState()
    const nextOrderMenuMode = shouldShowOrderMenu()
    if (renderedOrderMenuMode !== null && nextOrderMenuMode !== renderedOrderMenuMode) {
      void loadVenue()
    }
  })

  disposables.push(
    on(refs.bookingButton, 'click', () => {
      if (venueId) {
        onBookVenue?.(venueId)
      }
    }),
    on(refs.retryButton, 'click', () => {
      refs.extensionSlot.dispatchEvent(new Event('hookah:guest-venue-refresh'))
      void loadVenue()
    }),
    on(refs.staffButton, 'click', () => void handleStaffCall()),
    on(refs.staffCloseButton, 'click', () => {
      staffFormOpen = false
      hideStaffError()
      setStaffMessage('', 'default')
      updateStaffState()
      if (typeof window !== 'undefined' && venueId) {
        const nextUrl = new URL(window.location.href)
        nextUrl.hash = `#/venue/${venueId}`
        window.history.replaceState(null, '', `${nextUrl.pathname}${nextUrl.search}${nextUrl.hash}`)
      }
    }),
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
    extensionDispose()
    if (messageTimer) {
      window.clearTimeout(messageTimer)
    }
    itemDisposables.forEach((dispose) => dispose())
    cartSubscription()
    tableSubscription()
    disposables.forEach((dispose) => dispose())
  }
}
