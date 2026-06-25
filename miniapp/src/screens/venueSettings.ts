import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueGetBookingSettings,
  venueGetPublicCardSettings,
  venueGetShiftExtensionSettings,
  venueUpdateBookingSettings,
  venueUpdatePublicCardSettings,
  venueUpdateShiftExtensionSettings
} from '../shared/api/venueApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type {
  ShiftExtensionSettingsDto,
  VenueAccessDto,
  VenueBookingSettingsResponse,
  VenuePublicCardSettingsResponse
} from '../shared/api/venueDtos'
import { append, el, on } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'
import { showToast } from '../shared/ui/toast'

export type VenueSettingsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type VenueSettingsRefs = {
  status: HTMLParagraphElement
  publicCard: HTMLElement
  publicName: HTMLParagraphElement
  cityInput: HTMLInputElement
  addressInput: HTMLInputElement
  guestContactInput: HTMLInputElement
  cardDescriptionInput: HTMLTextAreaElement
  publicCardSaveButton: HTMLButtonElement
  publicCardForm: HTMLDivElement
  bookingCard: HTMLElement
  bookingSummary: HTMLParagraphElement
  bookingExample: HTMLParagraphElement
  bookingPresetActions: HTMLDivElement
  holdInput: HTMLInputElement
  bookingSaveButton: HTMLButtonElement
  bookingForm: HTMLDivElement
  extensionCard: HTMLElement
  extensionSummary: HTMLParagraphElement
  extensionHint: HTMLParagraphElement
  enabledInput: HTMLInputElement
  durationSelect: HTMLSelectElement
  priceInput: HTMLInputElement
  extensionSaveButton: HTMLButtonElement
  extensionForm: HTMLDivElement
  backButton: HTMLButtonElement
}

const PUBLIC_CARD_CITY_MAX_LENGTH = 120
const PUBLIC_CARD_ADDRESS_MAX_LENGTH = 300
const PUBLIC_CARD_GUEST_CONTACT_MAX_LENGTH = 300
const PUBLIC_CARD_DESCRIPTION_MAX_LENGTH = 500

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function buildDom(root: HTMLDivElement): VenueSettingsRefs {
  const wrapper = el('div', { className: 'venue-settings' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: 'Настройки' })
  const status = el('p', { className: 'status', text: '' })
  append(header, title, status)

  const publicCard = el('section', { className: 'card' })
  const publicTitle = el('h3', { text: 'Публичная карточка' })
  const publicDescription = el('p', {
    text: 'Эти данные видят гости в каталоге и карточке заведения.'
  })
  const publicNameLabel = el('p', { className: 'field-label', text: 'Название' })
  const publicName = el('p', { className: 'venue-order-sub', text: '' })
  const publicCardForm = el('div', { className: 'venue-form-grid' }) as HTMLDivElement

  const cityLabel = el('p', { className: 'field-label', text: 'Город' })
  const cityInput = document.createElement('input')
  cityInput.className = 'venue-input'
  cityInput.type = 'text'
  cityInput.maxLength = PUBLIC_CARD_CITY_MAX_LENGTH
  cityInput.placeholder = 'Москва'

  const addressLabel = el('p', { className: 'field-label', text: 'Адрес' })
  const addressInput = document.createElement('input')
  addressInput.className = 'venue-input'
  addressInput.type = 'text'
  addressInput.maxLength = PUBLIC_CARD_ADDRESS_MAX_LENGTH
  addressInput.placeholder = 'Новый Арбат, 24'

  const guestContactLabel = el('p', { className: 'field-label', text: 'Контакт для гостей' })
  const guestContactInput = document.createElement('input')
  guestContactInput.className = 'venue-input'
  guestContactInput.type = 'text'
  guestContactInput.maxLength = PUBLIC_CARD_GUEST_CONTACT_MAX_LENGTH
  guestContactInput.placeholder = '+7 999 000-00-00'

  const cardDescriptionLabel = el('p', { className: 'field-label', text: 'Краткое описание' })
  const cardDescriptionInput = document.createElement('textarea')
  cardDescriptionInput.className = 'venue-textarea'
  cardDescriptionInput.maxLength = PUBLIC_CARD_DESCRIPTION_MAX_LENGTH
  cardDescriptionInput.rows = 4
  cardDescriptionInput.placeholder = 'Например: авторские чаши, спокойная посадка, чайная карта.'

  const publicCardSaveButton = el('button', { text: 'Сохранить' }) as HTMLButtonElement
  append(
    publicCardForm,
    cityLabel,
    cityInput,
    addressLabel,
    addressInput,
    guestContactLabel,
    guestContactInput,
    cardDescriptionLabel,
    cardDescriptionInput,
    publicCardSaveButton
  )
  append(publicCard, publicTitle, publicDescription, publicNameLabel, publicName, publicCardForm)

  const bookingCard = el('section', { className: 'card' })
  const bookingTitle = el('h3', { text: 'Настройки брони' })
  const bookingDescription = el('p', {
    text: 'Укажите, сколько времени после начала бронирования стол остаётся закреплён за гостем.'
  })
  const bookingSummary = el('p', { className: 'venue-order-sub', text: '' })
  const bookingExample = el('p', { className: 'venue-order-sub', text: '' })
  const bookingForm = el('div', { className: 'venue-form-grid' }) as HTMLDivElement
  const holdLabel = el('p', { className: 'field-label', text: 'Сколько минут держим бронь' })
  const bookingPresetActions = el('div', { className: 'venue-inline-actions' }) as HTMLDivElement
  const holdInput = document.createElement('input')
  holdInput.className = 'venue-input'
  holdInput.type = 'number'
  holdInput.inputMode = 'numeric'
  holdInput.placeholder = '15'
  const bookingSaveButton = el('button', { text: 'Сохранить' }) as HTMLButtonElement
  append(bookingForm, holdLabel, bookingPresetActions, holdInput, bookingSaveButton)
  append(bookingCard, bookingTitle, bookingDescription, bookingSummary, bookingExample, bookingForm)

  const extensionCard = el('section', { className: 'card' })
  const extensionTitle = el('h3', { text: 'Продление времени' })
  const description = el('p', {
    text: 'Гости смогут запросить платное продление. Персонал подтвердит возможность продления перед добавлением суммы в счёт.'
  })
  const extensionSummary = el('p', { className: 'venue-order-sub', text: '' })
  const extensionHint = el('p', { className: 'venue-order-sub', text: '' })

  const extensionForm = el('div', { className: 'venue-form-grid' }) as HTMLDivElement
  const enabledLabel = document.createElement('label')
  enabledLabel.className = 'venue-settings-toggle'
  const enabledInput = document.createElement('input')
  enabledInput.type = 'checkbox'
  const enabledText = el('span', { text: 'Показывать гостям возможность продления' })
  const enabledHelp = el('span', {
    className: 'venue-settings-toggle-help',
    text: 'Если выключено, гости не увидят продление, но цена и длительность сохранятся.'
  })
  append(enabledLabel, enabledInput, enabledText, enabledHelp)

  const durationLabel = el('p', { className: 'field-label', text: 'Длительность' })
  const durationSelect = document.createElement('select')
  durationSelect.className = 'venue-select'
  const durationPresets = [30, 60, 90, 120]
  durationPresets.forEach((minutes) => {
    durationSelect.appendChild(new Option(`${minutes} минут`, String(minutes)))
  })

  const priceLabel = el('p', { className: 'field-label', text: 'Цена, ₽' })
  const priceInput = document.createElement('input')
  priceInput.className = 'venue-input'
  priceInput.type = 'text'
  priceInput.inputMode = 'decimal'
  priceInput.placeholder = '3000'

  const extensionSaveButton = el('button', { text: 'Сохранить' }) as HTMLButtonElement
  append(extensionForm, enabledLabel, durationLabel, durationSelect, priceLabel, priceInput, extensionSaveButton)
  append(extensionCard, extensionTitle, description, extensionSummary, extensionHint, extensionForm)

  const backButton = el('button', { className: 'button-secondary', text: 'Вернуться в обзор' }) as HTMLButtonElement
  append(wrapper, header, publicCard, bookingCard, extensionCard, backButton)
  root.replaceChildren(wrapper)

  return {
    status,
    publicCard,
    publicName,
    cityInput,
    addressInput,
    guestContactInput,
    cardDescriptionInput,
    publicCardSaveButton,
    publicCardForm,
    bookingCard,
    bookingSummary,
    bookingExample,
    bookingPresetActions,
    holdInput,
    bookingSaveButton,
    bookingForm,
    extensionCard,
    extensionSummary,
    extensionHint,
    enabledInput,
    durationSelect,
    priceInput,
    extensionSaveButton,
    extensionForm,
    backButton
  }
}

function renderApiError(status: HTMLParagraphElement, error: ApiErrorInfo, isDebug: boolean) {
  const code = normalizeErrorCode(error)
  if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
    clearSession()
  }
  status.textContent = isDebug
    ? `${error.message || 'Ошибка'} (${error.code ?? error.status})`
    : error.message || 'Не удалось выполнить действие.'
}

function rubInputValue(priceMinor: number | null | undefined): string {
  if (priceMinor == null) return ''
  const rub = priceMinor / 100
  return Number.isInteger(rub) ? String(rub) : rub.toFixed(2)
}

function parsePriceMinor(raw: string): number | null {
  const normalized = raw.trim().replace(',', '.')
  if (!normalized) return null
  const match = normalized.match(/^(\d+)(?:\.(\d{1,2}))?$/)
  if (!match) return null
  const rub = Number(match[1])
  const kop = Number((match[2] ?? '').padEnd(2, '0'))
  const minor = rub * 100 + kop
  return Number.isSafeInteger(minor) && minor > 0 ? minor : null
}

function deadlineExample(minutes: number): string {
  const total = 19 * 60 + minutes
  const hours = String(Math.floor(total / 60) % 24).padStart(2, '0')
  const mins = String(total % 60).padStart(2, '0')
  return `Например: если бронь на 19:00 и выбран срок ${minutes} минут, стол держим до ${hours}:${mins}.`
}

function renderBookingSettings(refs: VenueSettingsRefs, settings: VenueBookingSettingsResponse) {
  refs.holdInput.value = String(settings.holdMinutes)
  refs.holdInput.min = String(settings.minHoldMinutes)
  refs.holdInput.max = String(settings.maxHoldMinutes)
  refs.bookingSummary.textContent = `Держим бронь: ${settings.holdMinutes} минут`
  refs.bookingExample.textContent = deadlineExample(settings.holdMinutes)
  refs.bookingPresetActions.replaceChildren()
  settings.quickHoldMinutes
    .filter((minutes) => minutes >= settings.minHoldMinutes && minutes <= settings.maxHoldMinutes)
    .forEach((minutes) => {
      const button = el('button', {
        className: 'button-secondary button-small',
        text: `${minutes} минут`
      }) as HTMLButtonElement
      button.disabled = minutes === settings.holdMinutes
      button.addEventListener('click', () => {
        refs.holdInput.value = String(minutes)
      })
      refs.bookingPresetActions.appendChild(button)
    })
}

function renderPublicCardSettings(refs: VenueSettingsRefs, settings: VenuePublicCardSettingsResponse) {
  refs.publicName.textContent = settings.name || 'Название не задано'
  refs.cityInput.value = settings.city ?? ''
  refs.addressInput.value = settings.address ?? ''
  refs.guestContactInput.value = settings.guestContact ?? ''
  refs.cardDescriptionInput.value = settings.cardDescription ?? ''
}

function renderShiftExtensionSettings(refs: VenueSettingsRefs, settings: ShiftExtensionSettingsDto) {
  refs.enabledInput.checked = settings.enabled
  refs.durationSelect.value = String(settings.durationMinutes)
  if (refs.durationSelect.value !== String(settings.durationMinutes)) {
    refs.durationSelect.appendChild(new Option(`${settings.durationMinutes} минут`, String(settings.durationMinutes)))
    refs.durationSelect.value = String(settings.durationMinutes)
  }
  refs.priceInput.value = rubInputValue(settings.priceMinor)

  const price = settings.priceMinor == null ? 'цена не задана' : formatPrice(settings.priceMinor, settings.currency || 'RUB')
  const state = settings.enabled ? 'Включено' : 'Выключено'
  refs.extensionSummary.textContent = `${state} · ${settings.durationMinutes} мин · ${price}`
  refs.extensionHint.textContent = settings.configured
    ? 'Гости увидят продление в списке разделов активного счёта.'
    : 'Настройте цену и длительность, чтобы гости могли запросить продление.'
}

export function renderVenueSettingsScreen(options: VenueSettingsOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined

  const refs = buildDom(root)
  const deps = buildApiDeps(isDebug)
  const canManagePublicCard = access.role === 'OWNER' || access.role === 'MANAGER'
  const canManageBookingSettings = access.permissions.includes('BOOKING_MANAGE')
  const canManageShiftExtension = access.permissions.includes('SHIFT_EXTENSION_SETTINGS')
  const disposables: Array<() => void> = []

  let disposed = false
  let loadAbort: AbortController | null = null
  let publicCardSaveAbort: AbortController | null = null
  let bookingSaveAbort: AbortController | null = null
  let extensionSaveAbort: AbortController | null = null
  let currentPublicCardSettings: VenuePublicCardSettingsResponse | null = null
  let currentBookingSettings: VenueBookingSettingsResponse | null = null
  let currentShiftExtensionSettings: ShiftExtensionSettingsDto | null = null

  if (!canManagePublicCard) {
    refs.publicCard.remove()
  }
  if (!canManageBookingSettings) {
    refs.bookingCard.remove()
  }
  if (!canManageShiftExtension) {
    refs.extensionCard.remove()
  }
  if (!canManagePublicCard && !canManageBookingSettings && !canManageShiftExtension) {
    refs.status.textContent = 'У вас нет доступа к настройкам заведения.'
  }

  const setPublicCardBusy = (busy: boolean) => {
    refs.publicCardSaveButton.disabled = busy
    refs.publicCardSaveButton.textContent = busy ? 'Сохраняем…' : 'Сохранить'
  }

  const setBookingBusy = (busy: boolean) => {
    refs.bookingSaveButton.disabled = busy
    refs.bookingSaveButton.textContent = busy ? 'Сохраняем…' : 'Сохранить'
  }

  const setExtensionBusy = (busy: boolean) => {
    refs.extensionSaveButton.disabled = busy
    refs.extensionSaveButton.textContent = busy ? 'Сохраняем…' : 'Сохранить'
  }

  const load = async () => {
    if (!canManagePublicCard && !canManageBookingSettings && !canManageShiftExtension) return
    refs.status.textContent = 'Загрузка…'
    loadAbort?.abort()
    const controller = new AbortController()
    loadAbort = controller
    if (canManagePublicCard) {
      const result = await venueGetPublicCardSettings(backendUrl, { venueId }, deps, controller.signal)
      if (disposed || loadAbort !== controller) return
      if (!result.ok) {
        if (result.error.code === REQUEST_ABORTED_CODE) return
        loadAbort = null
        renderApiError(refs.status, result.error, isDebug)
        return
      }
      currentPublicCardSettings = result.data
      renderPublicCardSettings(refs, currentPublicCardSettings)
    }
    if (canManageBookingSettings) {
      const result = await venueGetBookingSettings(backendUrl, { venueId }, deps, controller.signal)
      if (disposed || loadAbort !== controller) return
      if (!result.ok) {
        if (result.error.code === REQUEST_ABORTED_CODE) return
        loadAbort = null
        renderApiError(refs.status, result.error, isDebug)
        return
      }
      currentBookingSettings = result.data
      renderBookingSettings(refs, currentBookingSettings)
    }
    if (canManageShiftExtension) {
      const result = await venueGetShiftExtensionSettings(backendUrl, { venueId }, deps, controller.signal)
      if (disposed || loadAbort !== controller) return
      if (!result.ok) {
        if (result.error.code === REQUEST_ABORTED_CODE) return
        loadAbort = null
        renderApiError(refs.status, result.error, isDebug)
        return
      }
      currentShiftExtensionSettings = result.data.settings
      renderShiftExtensionSettings(refs, currentShiftExtensionSettings)
    }
    loadAbort = null
    refs.status.textContent = 'Настройки загружены.'
  }

  const savePublicCardSettings = async () => {
    if (!canManagePublicCard || !currentPublicCardSettings) return
    const city = refs.cityInput.value.trim()
    const address = refs.addressInput.value.trim()
    const guestContact = refs.guestContactInput.value.trim()
    const cardDescription = refs.cardDescriptionInput.value.trim()
    if (city.length > PUBLIC_CARD_CITY_MAX_LENGTH) {
      refs.status.textContent = `Город должен быть не длиннее ${PUBLIC_CARD_CITY_MAX_LENGTH} символов.`
      return
    }
    if (address.length > PUBLIC_CARD_ADDRESS_MAX_LENGTH) {
      refs.status.textContent = `Адрес должен быть не длиннее ${PUBLIC_CARD_ADDRESS_MAX_LENGTH} символов.`
      return
    }
    if (guestContact.length > PUBLIC_CARD_GUEST_CONTACT_MAX_LENGTH) {
      refs.status.textContent =
        `Контакт для гостей должен быть не длиннее ${PUBLIC_CARD_GUEST_CONTACT_MAX_LENGTH} символов.`
      return
    }
    if (cardDescription.length > PUBLIC_CARD_DESCRIPTION_MAX_LENGTH) {
      refs.status.textContent =
        `Описание должно быть не длиннее ${PUBLIC_CARD_DESCRIPTION_MAX_LENGTH} символов.`
      return
    }

    setPublicCardBusy(true)
    publicCardSaveAbort?.abort()
    const controller = new AbortController()
    publicCardSaveAbort = controller
    const result = await venueUpdatePublicCardSettings(
      backendUrl,
      {
        venueId,
        body: {
          city: city || null,
          address: address || null,
          guestContact: guestContact || null,
          cardDescription: cardDescription || null
        }
      },
      deps,
      controller.signal
    )
    if (disposed || publicCardSaveAbort !== controller) return
    publicCardSaveAbort = null
    setPublicCardBusy(false)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentPublicCardSettings = result.data
    renderPublicCardSettings(refs, currentPublicCardSettings)
    refs.status.textContent = 'Публичная карточка сохранена.'
    showToast('Публичная карточка сохранена.')
  }

  const saveBookingSettings = async () => {
    if (!canManageBookingSettings || !currentBookingSettings) return
    const raw = refs.holdInput.value.trim()
    const holdMinutes = Number(raw)
    if (
      !/^\d+$/.test(raw) ||
      !Number.isInteger(holdMinutes) ||
      holdMinutes < currentBookingSettings.minHoldMinutes ||
      holdMinutes > currentBookingSettings.maxHoldMinutes
    ) {
      refs.status.textContent =
        `Введите число от ${currentBookingSettings.minHoldMinutes} до ` +
        `${currentBookingSettings.maxHoldMinutes} минут.`
      return
    }

    setBookingBusy(true)
    bookingSaveAbort?.abort()
    const controller = new AbortController()
    bookingSaveAbort = controller
    const result = await venueUpdateBookingSettings(
      backendUrl,
      { venueId, body: { holdMinutes } },
      deps,
      controller.signal
    )
    if (disposed || bookingSaveAbort !== controller) return
    bookingSaveAbort = null
    setBookingBusy(false)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentBookingSettings = result.data
    renderBookingSettings(refs, currentBookingSettings)
    refs.status.textContent = 'Настройки брони сохранены.'
    showToast('Настройки брони сохранены.')
  }

  const saveShiftExtensionSettings = async () => {
    if (!canManageShiftExtension || !currentShiftExtensionSettings) return
    const durationMinutes = Number(refs.durationSelect.value)
    const priceMinor = parsePriceMinor(refs.priceInput.value)
    if (!Number.isInteger(durationMinutes) || durationMinutes <= 0 || durationMinutes > 240) {
      refs.status.textContent = 'Выберите длительность от 1 до 240 минут.'
      return
    }
    if (refs.enabledInput.checked && priceMinor == null) {
      refs.status.textContent = 'Укажите цену, чтобы включить продление.'
      return
    }
    if (refs.priceInput.value.trim() && priceMinor == null) {
      refs.status.textContent = 'Цена должна быть положительным числом в рублях.'
      return
    }

    setExtensionBusy(true)
    extensionSaveAbort?.abort()
    const controller = new AbortController()
    extensionSaveAbort = controller
    const result = await venueUpdateShiftExtensionSettings(
      backendUrl,
      {
        venueId,
        body: {
          enabled: refs.enabledInput.checked,
          durationMinutes,
          priceMinor,
          currency: 'RUB',
          maxExtensionsPerSession: currentShiftExtensionSettings.maxExtensionsPerSession ?? null
        }
      },
      deps,
      controller.signal
    )
    if (disposed || extensionSaveAbort !== controller) return
    extensionSaveAbort = null
    setExtensionBusy(false)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentShiftExtensionSettings = result.data.settings
    renderShiftExtensionSettings(refs, currentShiftExtensionSettings)
    refs.status.textContent = 'Настройки сохранены.'
    showToast('Настройки сохранены')
  }

  disposables.push(on(refs.publicCardSaveButton, 'click', () => void savePublicCardSettings()))
  disposables.push(on(refs.bookingSaveButton, 'click', () => void saveBookingSettings()))
  disposables.push(on(refs.extensionSaveButton, 'click', () => void saveShiftExtensionSettings()))
  disposables.push(on(refs.backButton, 'click', () => {
    window.location.hash = '#/dashboard'
  }))

  void load()

  return () => {
    disposed = true
    loadAbort?.abort()
    publicCardSaveAbort?.abort()
    bookingSaveAbort?.abort()
    extensionSaveAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
