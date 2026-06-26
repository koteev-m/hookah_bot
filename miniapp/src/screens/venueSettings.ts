import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueDeleteScheduleOverride,
  venueGetBookingSettings,
  venueGetPublicCardSettings,
  venueGetScheduleSettings,
  venueGetShiftExtensionSettings,
  venueUpdateScheduleDay,
  venueUpdateScheduleOverride,
  venueUpdateBookingSettings,
  venueUpdatePublicCardSettings,
  venueUpdateShiftExtensionSettings
} from '../shared/api/venueApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type {
  ShiftExtensionSettingsDto,
  VenueAccessDto,
  VenueBookingSettingsResponse,
  VenuePublicCardSettingsResponse,
  VenueScheduleDayDto,
  VenueScheduleOverrideDto,
  VenueScheduleSettingsResponse
} from '../shared/api/venueDtos'
import { countryName, filterCities, filterCountries, type LocalCityOption } from '../shared/location/localLocationData'
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
  countryInput: HTMLInputElement
  countryOptions: HTMLDivElement
  cityInput: HTMLInputElement
  citySuggestions: HTMLDivElement
  addressInput: HTMLInputElement
  addressSuggestions: HTMLDivElement
  manualAddressButton: HTMLButtonElement
  locationHint: HTMLParagraphElement
  guestContactInput: HTMLInputElement
  cardDescriptionInput: HTMLTextAreaElement
  publicCardSaveButton: HTMLButtonElement
  publicCardForm: HTMLDivElement
  scheduleCard: HTMLElement
  scheduleSummary: HTMLParagraphElement
  weeklyScheduleList: HTMLDivElement
  overrideDateInput: HTMLInputElement
  overrideClosedInput: HTMLInputElement
  overrideOpensInput: HTMLInputElement
  overrideClosesInput: HTMLInputElement
  overrideSaveButton: HTMLButtonElement
  overrideList: HTMLDivElement
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
const DEFAULT_COUNTRY_CODE = 'RU'
const WEEKDAY_LABELS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс']

type PublicCardDraft = {
  city: string | null
  address: string | null
  countryCode: string | null
  formattedAddress: string | null
  latitude: number | null
  longitude: number | null
  guestContact: string | null
  cardDescription: string | null
}

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

  const countryLabel = el('p', { className: 'field-label', text: 'Страна' })
  const countryField = el('div', { className: 'venue-combobox-field' }) as HTMLDivElement
  const countryInput = document.createElement('input')
  countryInput.className = 'venue-input'
  countryInput.type = 'text'
  countryInput.autocomplete = 'off'
  countryInput.placeholder = 'Россия'
  countryInput.setAttribute('role', 'combobox')
  countryInput.setAttribute('aria-expanded', 'false')
  const countryOptions = el('div', { className: 'venue-suggestion-list' }) as HTMLDivElement
  countryOptions.hidden = true
  countryField.appendChild(countryInput)
  countryField.appendChild(countryOptions)

  const cityLabel = el('p', { className: 'field-label', text: 'Город' })
  const cityField = el('div', { className: 'venue-combobox-field' }) as HTMLDivElement
  const cityInput = document.createElement('input')
  cityInput.className = 'venue-input'
  cityInput.type = 'text'
  cityInput.maxLength = PUBLIC_CARD_CITY_MAX_LENGTH
  cityInput.placeholder = 'Начните вводить город'
  cityInput.autocomplete = 'off'
  const citySuggestions = el('div', { className: 'venue-suggestion-list' }) as HTMLDivElement
  citySuggestions.hidden = true
  cityField.appendChild(cityInput)
  cityField.appendChild(citySuggestions)

  const addressLabel = el('p', { className: 'field-label', text: 'Адрес' })
  const addressField = el('div', { className: 'venue-combobox-field' }) as HTMLDivElement
  const addressInput = document.createElement('input')
  addressInput.className = 'venue-input'
  addressInput.type = 'text'
  addressInput.maxLength = PUBLIC_CARD_ADDRESS_MAX_LENGTH
  addressInput.placeholder = 'Улица, дом'
  addressInput.autocomplete = 'off'
  const addressSuggestions = el('div', { className: 'venue-suggestion-list' }) as HTMLDivElement
  addressSuggestions.hidden = true
  addressField.appendChild(addressInput)
  addressField.appendChild(addressSuggestions)
  const manualAddressButton = el('button', {
    className: 'button-secondary button-small venue-manual-address',
    text: 'Ввести адрес вручную'
  }) as HTMLButtonElement
  const locationHint = el('p', { className: 'venue-order-sub venue-location-hint', text: '' })

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
  publicCardSaveButton.disabled = true
  append(
    publicCardForm,
    countryLabel,
    countryField,
    cityLabel,
    cityField,
    addressLabel,
    addressField,
    manualAddressButton,
    locationHint,
    guestContactLabel,
    guestContactInput,
    cardDescriptionLabel,
    cardDescriptionInput,
    publicCardSaveButton
  )
  append(publicCard, publicTitle, publicDescription, publicNameLabel, publicName, publicCardForm)

  const scheduleCard = el('section', { className: 'card' })
  const scheduleTitle = el('h3', { text: 'Часы работы' })
  const scheduleDescription = el('p', {
    text: 'Настройте базовый недельный график и исключения для конкретных дат.'
  })
  const scheduleSummary = el('p', { className: 'venue-order-sub', text: '' })
  const weeklyScheduleList = el('div', { className: 'venue-form-grid' }) as HTMLDivElement
  const overridesTitle = el('p', { className: 'field-label', text: 'Исключения по датам' })
  const overrideForm = el('div', { className: 'venue-form-grid' }) as HTMLDivElement
  const overrideDateLabel = el('p', { className: 'field-label', text: 'Дата' })
  const overrideDateInput = document.createElement('input')
  overrideDateInput.className = 'venue-input'
  overrideDateInput.type = 'date'
  const overrideClosedLabel = document.createElement('label')
  overrideClosedLabel.className = 'venue-settings-toggle'
  const overrideClosedInput = document.createElement('input')
  overrideClosedInput.type = 'checkbox'
  const overrideClosedText = el('span', { text: 'Закрыто в эту дату' })
  append(overrideClosedLabel, overrideClosedInput, overrideClosedText)
  const overrideOpensLabel = el('p', { className: 'field-label', text: 'Открытие' })
  const overrideOpensInput = document.createElement('input')
  overrideOpensInput.className = 'venue-input'
  overrideOpensInput.type = 'time'
  overrideOpensInput.value = '18:00'
  const overrideClosesLabel = el('p', { className: 'field-label', text: 'Закрытие' })
  const overrideClosesInput = document.createElement('input')
  overrideClosesInput.className = 'venue-input'
  overrideClosesInput.type = 'time'
  overrideClosesInput.value = '00:00'
  const overrideSaveButton = el('button', { text: 'Сохранить исключение' }) as HTMLButtonElement
  append(
    overrideForm,
    overrideDateLabel,
    overrideDateInput,
    overrideClosedLabel,
    overrideOpensLabel,
    overrideOpensInput,
    overrideClosesLabel,
    overrideClosesInput,
    overrideSaveButton
  )
  const overrideList = el('div', { className: 'venue-form-grid' }) as HTMLDivElement
  append(scheduleCard, scheduleTitle, scheduleDescription, scheduleSummary, weeklyScheduleList, overridesTitle, overrideForm, overrideList)

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
  append(wrapper, header, publicCard, scheduleCard, bookingCard, extensionCard, backButton)
  root.replaceChildren(wrapper)

  return {
    status,
    publicCard,
    publicName,
    countryInput,
    countryOptions,
    cityInput,
    citySuggestions,
    addressInput,
    addressSuggestions,
    manualAddressButton,
    locationHint,
    guestContactInput,
    cardDescriptionInput,
    publicCardSaveButton,
    publicCardForm,
    scheduleCard,
    scheduleSummary,
    weeklyScheduleList,
    overrideDateInput,
    overrideClosedInput,
    overrideOpensInput,
    overrideClosesInput,
    overrideSaveButton,
    overrideList,
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

function normalizeNullableText(raw: string | null | undefined): string | null {
  const normalized = raw?.trim() ?? ''
  return normalized.length ? normalized : null
}

function normalizeNumber(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function isEmptyLocation(settings: VenuePublicCardSettingsResponse): boolean {
  return !normalizeNullableText(settings.countryCode) &&
    !normalizeNullableText(settings.city) &&
    !normalizeNullableText(settings.address) &&
    !normalizeNullableText(settings.formattedAddress) &&
    normalizeNumber(settings.latitude) == null &&
    normalizeNumber(settings.longitude) == null
}

function settingsToDraft(settings: VenuePublicCardSettingsResponse): PublicCardDraft {
  const defaultCountryCode = isEmptyLocation(settings) ? DEFAULT_COUNTRY_CODE : null
  return {
    city: normalizeNullableText(settings.city),
    address: normalizeNullableText(settings.address),
    countryCode: normalizeNullableText(settings.countryCode)?.toUpperCase() ?? defaultCountryCode,
    formattedAddress: normalizeNullableText(settings.formattedAddress),
    latitude: normalizeNumber(settings.latitude),
    longitude: normalizeNumber(settings.longitude),
    guestContact: normalizeNullableText(settings.guestContact),
    cardDescription: normalizeNullableText(settings.cardDescription)
  }
}

function sameDraft(a: PublicCardDraft, b: PublicCardDraft): boolean {
  return (
    a.city === b.city &&
    a.address === b.address &&
    a.countryCode === b.countryCode &&
    a.formattedAddress === b.formattedAddress &&
    a.latitude === b.latitude &&
    a.longitude === b.longitude &&
    a.guestContact === b.guestContact &&
    a.cardDescription === b.cardDescription
  )
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

type ScheduleCallbacks = {
  onSaveDay: (weekday: number, isClosed: boolean, opensAt: string, closesAt: string) => void
  onDeleteOverride: (serviceDate: string) => void
}

function isValidScheduleTime(value: string): boolean {
  return /^([01]\d|2[0-3]):[0-5]\d$/.test(value.trim())
}

function formatScheduleLine(opensAt: string, closesAt: string, isClosed: boolean): string {
  if (isClosed) return 'Закрыто'
  if (opensAt === closesAt) return 'Круглосуточно'
  return `${opensAt}-${closesAt}`
}

function renderScheduleDay(
  day: VenueScheduleDayDto,
  callbacks: ScheduleCallbacks
): HTMLElement {
  const row = el('div', { className: 'venue-form-grid' })
  const title = el('p', {
    className: 'field-label',
    text: `${WEEKDAY_LABELS[day.weekday - 1] ?? day.weekday} · ${formatScheduleLine(day.opensAt, day.closesAt, day.isClosed)}`
  })
  const closedLabel = document.createElement('label')
  closedLabel.className = 'venue-settings-toggle'
  const closedInput = document.createElement('input')
  closedInput.type = 'checkbox'
  closedInput.checked = day.isClosed
  const closedText = el('span', { text: 'Закрыто' })
  append(closedLabel, closedInput, closedText)

  const opensInput = document.createElement('input')
  opensInput.className = 'venue-input'
  opensInput.type = 'time'
  opensInput.value = day.opensAt
  const closesInput = document.createElement('input')
  closesInput.className = 'venue-input'
  closesInput.type = 'time'
  closesInput.value = day.closesAt
  const saveButton = el('button', {
    className: 'button-small',
    text: day.configured ? 'Сохранить день' : 'Добавить день'
  }) as HTMLButtonElement

  const syncClosedState = () => {
    opensInput.disabled = closedInput.checked
    closesInput.disabled = closedInput.checked
  }
  closedInput.addEventListener('change', syncClosedState)
  saveButton.addEventListener('click', () => {
    callbacks.onSaveDay(day.weekday, closedInput.checked, opensInput.value, closesInput.value)
  })
  syncClosedState()
  append(row, title, closedLabel, opensInput, closesInput, saveButton)
  return row
}

function renderScheduleOverride(
  override: VenueScheduleOverrideDto,
  callbacks: ScheduleCallbacks
): HTMLElement {
  const row = el('div', { className: 'venue-form-grid' })
  const summary = el('p', {
    className: 'venue-order-sub',
    text: `${override.serviceDate} · ${formatScheduleLine(override.opensAt, override.closesAt, override.isClosed)}`
  })
  const deleteButton = el('button', {
    className: 'button-secondary button-small',
    text: 'Удалить'
  }) as HTMLButtonElement
  deleteButton.addEventListener('click', () => callbacks.onDeleteOverride(override.serviceDate))
  append(row, summary, deleteButton)
  return row
}

function renderScheduleSettings(
  refs: VenueSettingsRefs,
  settings: VenueScheduleSettingsResponse,
  callbacks: ScheduleCallbacks
) {
  refs.scheduleSummary.textContent = `${settings.weeklyHours.length} дней · исключений: ${settings.dateOverrides.length}`
  refs.weeklyScheduleList.replaceChildren(
    ...settings.weeklyHours
      .slice()
      .sort((a, b) => a.weekday - b.weekday)
      .map((day) => renderScheduleDay(day, callbacks))
  )
  refs.overrideList.replaceChildren(
    ...(settings.dateOverrides.length
      ? settings.dateOverrides.map((override) => renderScheduleOverride(override, callbacks))
      : [el('p', { className: 'venue-order-sub', text: 'Исключения не настроены.' })])
  )
  refs.overrideOpensInput.disabled = refs.overrideClosedInput.checked
  refs.overrideClosesInput.disabled = refs.overrideClosedInput.checked
}

function renderPublicCardSettings(refs: VenueSettingsRefs, settings: VenuePublicCardSettingsResponse) {
  const draft = settingsToDraft(settings)
  refs.publicName.textContent = settings.name || 'Название не задано'
  refs.countryInput.value = countryName(draft.countryCode)
  refs.cityInput.value = draft.city ?? ''
  refs.addressInput.value = draft.address ?? ''
  refs.guestContactInput.value = draft.guestContact ?? ''
  refs.cardDescriptionInput.value = draft.cardDescription ?? ''
  refs.cityInput.disabled = !draft.countryCode && !draft.city
  refs.addressInput.disabled = !draft.city
  refs.manualAddressButton.disabled = !draft.city
  refs.locationHint.textContent = draft.latitude != null && draft.longitude != null
    ? 'Сохранены координаты. Если изменить город или адрес, маршрут будет построен по тексту.'
    : 'Укажите улицу и номер дома. Маршрут будет построен по указанному адресу.'
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
  const canManageSchedule = access.role === 'OWNER' || access.role === 'MANAGER'
  const canManageBookingSettings = access.permissions.includes('BOOKING_MANAGE')
  const canManageShiftExtension = access.permissions.includes('SHIFT_EXTENSION_SETTINGS')
  const disposables: Array<() => void> = []

  let disposed = false
  let loadAbort: AbortController | null = null
  let publicCardSaveAbort: AbortController | null = null
  let scheduleSaveAbort: AbortController | null = null
  let bookingSaveAbort: AbortController | null = null
  let extensionSaveAbort: AbortController | null = null
  let currentPublicCardSettings: VenuePublicCardSettingsResponse | null = null
  let publicCardSnapshot: PublicCardDraft | null = null
  let selectedCountryCode: string | null = 'RU'
  let selectedFormattedAddress: string | null = null
  let selectedLatitude: number | null = null
  let selectedLongitude: number | null = null
  let publicCardSaving = false
  let publicCardSavedTimer: number | null = null
  let currentScheduleSettings: VenueScheduleSettingsResponse | null = null
  let currentBookingSettings: VenueBookingSettingsResponse | null = null
  let currentShiftExtensionSettings: ShiftExtensionSettingsDto | null = null

  if (!canManagePublicCard) {
    refs.publicCard.remove()
  }
  if (!canManageSchedule) {
    refs.scheduleCard.remove()
  }
  if (!canManageBookingSettings) {
    refs.bookingCard.remove()
  }
  if (!canManageShiftExtension) {
    refs.extensionCard.remove()
  }
  if (!canManagePublicCard && !canManageSchedule && !canManageBookingSettings && !canManageShiftExtension) {
    refs.status.textContent = 'У вас нет доступа к настройкам заведения.'
  }

  const currentPublicCardDraft = (): PublicCardDraft => ({
    city: normalizeNullableText(refs.cityInput.value),
    address: normalizeNullableText(refs.addressInput.value),
    countryCode: normalizeNullableText(selectedCountryCode)?.toUpperCase() ?? null,
    formattedAddress: normalizeNullableText(selectedFormattedAddress),
    latitude: selectedLatitude,
    longitude: selectedLongitude,
    guestContact: normalizeNullableText(refs.guestContactInput.value),
    cardDescription: normalizeNullableText(refs.cardDescriptionInput.value)
  })

  const publicCardIsDirty = () => {
    if (!publicCardSnapshot) return false
    return !sameDraft(currentPublicCardDraft(), publicCardSnapshot)
  }

  const setPublicCardSavedState = () => {
    if (publicCardSavedTimer) {
      window.clearTimeout(publicCardSavedTimer)
      publicCardSavedTimer = null
    }
    refs.publicCardSaveButton.textContent = '✓ Сохранено'
    refs.publicCardSaveButton.disabled = true
    publicCardSavedTimer = window.setTimeout(() => {
      publicCardSavedTimer = null
      updatePublicCardSaveButton()
    }, 1800)
  }

  const updatePublicCardSaveButton = () => {
    if (publicCardSaving) {
      refs.publicCardSaveButton.disabled = true
      refs.publicCardSaveButton.textContent = 'Сохраняем…'
      return
    }
    if (publicCardSavedTimer) {
      refs.publicCardSaveButton.disabled = true
      return
    }
    const dirty = publicCardIsDirty()
    refs.publicCardSaveButton.disabled = !dirty
    refs.publicCardSaveButton.textContent = 'Сохранить'
  }

  const setPublicCardBusy = (busy: boolean) => {
    publicCardSaving = busy
    updatePublicCardSaveButton()
  }

  const setBookingBusy = (busy: boolean) => {
    refs.bookingSaveButton.disabled = busy
    refs.bookingSaveButton.textContent = busy ? 'Сохраняем…' : 'Сохранить'
  }

  const setScheduleBusy = (busy: boolean) => {
    refs.overrideSaveButton.disabled = busy
    refs.weeklyScheduleList.querySelectorAll<HTMLButtonElement>('button').forEach((button) => {
      button.disabled = busy
    })
  }

  const setExtensionBusy = (busy: boolean) => {
    refs.extensionSaveButton.disabled = busy
    refs.extensionSaveButton.textContent = busy ? 'Сохраняем…' : 'Сохранить'
  }

  const load = async () => {
    if (!canManagePublicCard && !canManageSchedule && !canManageBookingSettings && !canManageShiftExtension) return
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
      publicCardSnapshot = settingsToDraft(currentPublicCardSettings)
      selectedCountryCode = publicCardSnapshot.countryCode
      selectedFormattedAddress = publicCardSnapshot.formattedAddress
      selectedLatitude = publicCardSnapshot.latitude
      selectedLongitude = publicCardSnapshot.longitude
      renderPublicCardSettings(refs, currentPublicCardSettings)
      updatePublicCardSaveButton()
    }
    if (canManageSchedule) {
      const result = await venueGetScheduleSettings(backendUrl, { venueId }, deps, controller.signal)
      if (disposed || loadAbort !== controller) return
      if (!result.ok) {
        if (result.error.code === REQUEST_ABORTED_CODE) return
        loadAbort = null
        renderApiError(refs.status, result.error, isDebug)
        return
      }
      currentScheduleSettings = result.data
      renderScheduleSettings(refs, currentScheduleSettings, scheduleCallbacks)
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
    if (!canManagePublicCard || !currentPublicCardSettings || !publicCardSnapshot || publicCardSaving) return
    if (!publicCardIsDirty()) return
    const draft = currentPublicCardDraft()
    const city = draft.city ?? ''
    const address = draft.address ?? ''
    const guestContact = draft.guestContact ?? ''
    const cardDescription = draft.cardDescription ?? ''
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
    if ((draft.latitude == null) !== (draft.longitude == null)) {
      refs.status.textContent = 'Координаты адреса неполные. Выберите адрес заново или сохраните вручную.'
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
          city: draft.city,
          address: draft.address,
          countryCode: draft.countryCode,
          formattedAddress: draft.formattedAddress,
          latitude: draft.latitude,
          longitude: draft.longitude,
          guestContact: draft.guestContact,
          cardDescription: draft.cardDescription
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
    publicCardSnapshot = settingsToDraft(currentPublicCardSettings)
    selectedCountryCode = publicCardSnapshot.countryCode
    selectedFormattedAddress = publicCardSnapshot.formattedAddress
    selectedLatitude = publicCardSnapshot.latitude
    selectedLongitude = publicCardSnapshot.longitude
    renderPublicCardSettings(refs, currentPublicCardSettings)
    refs.status.textContent = 'Публичная карточка сохранена.'
    showToast('Публичная карточка сохранена.')
    setPublicCardSavedState()
  }

  const saveScheduleDay = async (weekday: number, isClosed: boolean, opensAt: string, closesAt: string) => {
    if (!canManageSchedule || !currentScheduleSettings) return
    if (!Number.isInteger(weekday) || weekday < 1 || weekday > 7) {
      refs.status.textContent = 'Некорректный день недели.'
      return
    }
    if (!isClosed && (!isValidScheduleTime(opensAt) || !isValidScheduleTime(closesAt))) {
      refs.status.textContent = 'Введите время в формате HH:mm.'
      return
    }

    setScheduleBusy(true)
    scheduleSaveAbort?.abort()
    const controller = new AbortController()
    scheduleSaveAbort = controller
    const result = await venueUpdateScheduleDay(
      backendUrl,
      {
        venueId,
        weekday,
        body: {
          isClosed,
          opensAt: isClosed ? null : opensAt,
          closesAt: isClosed ? null : closesAt
        }
      },
      deps,
      controller.signal
    )
    if (disposed || scheduleSaveAbort !== controller) return
    scheduleSaveAbort = null
    setScheduleBusy(false)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentScheduleSettings = result.data
    renderScheduleSettings(refs, currentScheduleSettings, scheduleCallbacks)
    refs.status.textContent = 'Часы работы сохранены.'
    showToast('Часы работы сохранены.')
  }

  const saveScheduleOverride = async () => {
    if (!canManageSchedule || !currentScheduleSettings) return
    const serviceDate = refs.overrideDateInput.value.trim()
    const isClosed = refs.overrideClosedInput.checked
    const opensAt = refs.overrideOpensInput.value
    const closesAt = refs.overrideClosesInput.value
    if (!/^\d{4}-\d{2}-\d{2}$/.test(serviceDate)) {
      refs.status.textContent = 'Выберите дату исключения.'
      return
    }
    if (!isClosed && (!isValidScheduleTime(opensAt) || !isValidScheduleTime(closesAt))) {
      refs.status.textContent = 'Введите время в формате HH:mm.'
      return
    }

    setScheduleBusy(true)
    scheduleSaveAbort?.abort()
    const controller = new AbortController()
    scheduleSaveAbort = controller
    const result = await venueUpdateScheduleOverride(
      backendUrl,
      {
        venueId,
        serviceDate,
        body: {
          isClosed,
          opensAt: isClosed ? null : opensAt,
          closesAt: isClosed ? null : closesAt
        }
      },
      deps,
      controller.signal
    )
    if (disposed || scheduleSaveAbort !== controller) return
    scheduleSaveAbort = null
    setScheduleBusy(false)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentScheduleSettings = result.data
    renderScheduleSettings(refs, currentScheduleSettings, scheduleCallbacks)
    refs.status.textContent = 'Исключение сохранено.'
    showToast('Исключение сохранено.')
  }

  const deleteScheduleOverride = async (serviceDate: string) => {
    if (!canManageSchedule || !currentScheduleSettings) return
    setScheduleBusy(true)
    scheduleSaveAbort?.abort()
    const controller = new AbortController()
    scheduleSaveAbort = controller
    const result = await venueDeleteScheduleOverride(
      backendUrl,
      { venueId, serviceDate },
      deps,
      controller.signal
    )
    if (disposed || scheduleSaveAbort !== controller) return
    scheduleSaveAbort = null
    setScheduleBusy(false)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentScheduleSettings = result.data
    renderScheduleSettings(refs, currentScheduleSettings, scheduleCallbacks)
    refs.status.textContent = 'Исключение удалено.'
    showToast('Исключение удалено.')
  }

  const scheduleCallbacks: ScheduleCallbacks = {
    onSaveDay: (weekday, isClosed, opensAt, closesAt) =>
      void saveScheduleDay(weekday, isClosed, opensAt, closesAt),
    onDeleteOverride: (serviceDate) => void deleteScheduleOverride(serviceDate)
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

  const clearPublicCardSavedTimer = () => {
    if (publicCardSavedTimer) {
      window.clearTimeout(publicCardSavedTimer)
      publicCardSavedTimer = null
    }
  }

  const markPublicCardChanged = () => {
    clearPublicCardSavedTimer()
    updatePublicCardSaveButton()
  }

  const closeSuggestions = (container: HTMLDivElement) => {
    container.hidden = true
    container.replaceChildren()
  }

  const renderSuggestionMessage = (container: HTMLDivElement, message: string) => {
    container.replaceChildren(el('div', { className: 'venue-suggestion-message', text: message }))
    container.hidden = false
  }

  const renderCountryOptions = () => {
    const matches = filterCountries(refs.countryInput.value)
    refs.countryInput.setAttribute('aria-expanded', matches.length ? 'true' : 'false')
    if (!matches.length) {
      closeSuggestions(refs.countryOptions)
      return
    }
    refs.countryOptions.replaceChildren()
    matches.forEach((country) => {
      const button = el('button', {
        className: 'venue-suggestion-option',
        text: `${country.name} · ${country.code}`
      }) as HTMLButtonElement
      button.type = 'button'
      button.addEventListener('click', () => {
        const changed = selectedCountryCode !== country.code
        selectedCountryCode = country.code
        refs.countryInput.value = country.name
        refs.cityInput.disabled = false
        refs.addressInput.disabled = true
        refs.manualAddressButton.disabled = true
        if (changed) {
          refs.cityInput.value = ''
          refs.addressInput.value = ''
        selectedFormattedAddress = null
        selectedLatitude = null
        selectedLongitude = null
          refs.locationHint.textContent = 'Страна изменена: выберите город и укажите адрес заново.'
        }
        closeSuggestions(refs.countryOptions)
        refs.countryInput.setAttribute('aria-expanded', 'false')
        markPublicCardChanged()
      })
      refs.countryOptions.appendChild(button)
    })
    refs.countryOptions.hidden = false
  }

  const renderCityItems = (
    container: HTMLDivElement,
    items: LocalCityOption[],
    onSelect: (item: LocalCityOption) => void
  ) => {
    if (!items.length) {
      renderSuggestionMessage(container, 'Ничего не найдено. Можно ввести город вручную.')
      return
    }
    container.replaceChildren()
    items.slice(0, 7).forEach((item) => {
      const button = el('button', { className: 'venue-suggestion-option' }) as HTMLButtonElement
      button.type = 'button'
      button.appendChild(el('span', { text: item.name }))
      if (item.region) {
        button.appendChild(el('small', { text: item.region }))
      }
      button.addEventListener('click', () => onSelect(item))
      container.appendChild(button)
    })
    container.hidden = false
  }

  const renderCitySuggestions = () => {
    const countryCode = selectedCountryCode
    const query = refs.cityInput.value.trim()
    if (!countryCode || query.length < 2) {
      closeSuggestions(refs.citySuggestions)
      return
    }
    renderCityItems(refs.citySuggestions, filterCities(countryCode, query), (item) => {
      refs.cityInput.value = item.name
      refs.addressInput.disabled = false
      refs.manualAddressButton.disabled = false
      refs.addressInput.value = ''
      selectedFormattedAddress = null
      selectedLatitude = null
      selectedLongitude = null
      refs.locationHint.textContent = 'Укажите улицу и номер дома. Маршрут будет построен по указанному адресу.'
      closeSuggestions(refs.citySuggestions)
      markPublicCardChanged()
    })
  }

  const clearTrustedAddress = (message?: string) => {
    selectedFormattedAddress = null
    selectedLatitude = null
    selectedLongitude = null
    if (message) refs.locationHint.textContent = message
  }

  disposables.push(on(refs.publicCardSaveButton, 'click', () => void savePublicCardSettings()))
  disposables.push(on(refs.countryInput, 'input', () => {
    renderCountryOptions()
  }))
  disposables.push(on(refs.countryInput, 'keydown', (event) => {
    const keyboardEvent = event as KeyboardEvent
    const options = Array.from(refs.countryOptions.querySelectorAll<HTMLButtonElement>('button'))
    const active = document.activeElement as HTMLButtonElement | null
    const index = active ? options.indexOf(active) : -1
    if (keyboardEvent.key === 'Escape') {
      closeSuggestions(refs.countryOptions)
      refs.countryInput.setAttribute('aria-expanded', 'false')
    } else if (keyboardEvent.key === 'ArrowDown' && options.length) {
      keyboardEvent.preventDefault()
      options[Math.min(index + 1, options.length - 1)].focus()
    } else if (keyboardEvent.key === 'ArrowUp' && options.length) {
      keyboardEvent.preventDefault()
      options[Math.max(index - 1, 0)].focus()
    }
  }))
  disposables.push(on(refs.cityInput, 'input', () => {
    refs.addressInput.disabled = !refs.cityInput.value.trim()
    refs.manualAddressButton.disabled = !refs.cityInput.value.trim()
    refs.addressInput.value = ''
    clearTrustedAddress('Город изменён: укажите улицу и номер дома заново.')
    markPublicCardChanged()
    renderCitySuggestions()
  }))
  disposables.push(on(refs.cityInput, 'keydown', (event) => {
    if ((event as KeyboardEvent).key === 'Escape') closeSuggestions(refs.citySuggestions)
  }))
  disposables.push(on(refs.addressInput, 'input', () => {
    closeSuggestions(refs.addressSuggestions)
    clearTrustedAddress('Маршрут будет построен по указанному адресу.')
    markPublicCardChanged()
  }))
  disposables.push(on(refs.addressInput, 'keydown', (event) => {
    if ((event as KeyboardEvent).key === 'Escape') closeSuggestions(refs.addressSuggestions)
  }))
  disposables.push(on(refs.manualAddressButton, 'click', () => {
    clearTrustedAddress('Маршрут будет построен по указанному адресу.')
    closeSuggestions(refs.addressSuggestions)
    markPublicCardChanged()
  }))
  ;[refs.guestContactInput, refs.cardDescriptionInput].forEach((input) => {
    disposables.push(on(input, 'input', markPublicCardChanged))
  })
  const handleDocumentClick = (event: MouseEvent) => {
    const target = event.target as Node | null
    if (!target) return
    if (!refs.countryOptions.contains(target) && target !== refs.countryInput) closeSuggestions(refs.countryOptions)
    if (!refs.citySuggestions.contains(target) && target !== refs.cityInput) closeSuggestions(refs.citySuggestions)
    if (!refs.addressSuggestions.contains(target) && target !== refs.addressInput) closeSuggestions(refs.addressSuggestions)
  }
  document.addEventListener('click', handleDocumentClick)
  disposables.push(() => document.removeEventListener('click', handleDocumentClick))
  disposables.push(on(refs.overrideClosedInput, 'change', () => {
    refs.overrideOpensInput.disabled = refs.overrideClosedInput.checked
    refs.overrideClosesInput.disabled = refs.overrideClosedInput.checked
  }))
  disposables.push(on(refs.overrideSaveButton, 'click', () => void saveScheduleOverride()))
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
    scheduleSaveAbort?.abort()
    if (publicCardSavedTimer) window.clearTimeout(publicCardSavedTimer)
    bookingSaveAbort?.abort()
    extensionSaveAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
