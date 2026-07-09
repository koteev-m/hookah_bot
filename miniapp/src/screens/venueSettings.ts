import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueDeleteScheduleOverrideRange,
  venueClearPublicReviewUrl,
  venueGetBookingSettings,
  venueGetPublicCardSettings,
  venueGetPublicReviewUrl,
  venueGetScheduleSettings,
  venueGetShiftExtensionSettings,
  venueReplaceScheduleOverrideRange,
  venueUpdateScheduleDay,
  venueUpdateScheduleOverrideRange,
  venueUpdateBookingSettings,
  venueUpdatePublicCardSettings,
  venueUpdatePublicReviewUrl,
  venueUpdateShiftExtensionSettings
} from '../shared/api/venueApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type {
  ShiftExtensionSettingsDto,
  VenueAccessDto,
  VenueBookingSettingsResponse,
  VenuePublicCardSettingsResponse,
  VenuePublicReviewUrlResponse,
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
  reviewLinkCard: HTMLElement
  reviewLinkCurrent: HTMLParagraphElement
  reviewLinkInput: HTMLInputElement
  reviewLinkSaveButton: HTMLButtonElement
  reviewLinkClearButton: HTMLButtonElement
  reviewLinkForm: HTMLDivElement
  scheduleCard: HTMLElement
  scheduleSummary: HTMLParagraphElement
  weeklyScheduleList: HTMLDivElement
  overrideActions: HTMLDivElement
  closePeriodButton: HTMLButtonElement
  changeHoursPeriodButton: HTMLButtonElement
  showOverridesButton: HTMLButtonElement
  overrideForm: HTMLDivElement
  overrideFormTitle: HTMLParagraphElement
  overrideFromDateInput: HTMLInputElement
  overrideToDateInput: HTMLInputElement
  overrideNoteLabel: HTMLParagraphElement
  overrideNoteInput: HTMLTextAreaElement
  overrideHelper: HTMLParagraphElement
  overrideOpensLabel: HTMLParagraphElement
  overrideOpensInput: HTMLInputElement
  overrideClosesLabel: HTMLParagraphElement
  overrideClosesInput: HTMLInputElement
  overrideSaveButton: HTMLButtonElement
  overrideCancelButton: HTMLButtonElement
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
const PUBLIC_REVIEW_URL_MAX_LENGTH = 2048
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

  const reviewLinkCard = el('section', { className: 'card' })
  const reviewLinkTitle = el('h3', { text: 'Ссылка для отзывов' })
  const reviewLinkDescription = el('p', {
    text: 'Покажем эту кнопку гостю только после оценки 5/5. Гость сам решает, переходить ли по ссылке.'
  })
  const reviewLinkCurrent = el('p', { className: 'venue-order-sub', text: '' })
  const reviewLinkForm = el('div', { className: 'venue-form-grid' }) as HTMLDivElement
  const reviewLinkLabel = el('p', { className: 'field-label', text: 'Ссылка на Яндекс.Карты' })
  const reviewLinkInput = document.createElement('input')
  reviewLinkInput.className = 'venue-input'
  reviewLinkInput.type = 'url'
  reviewLinkInput.maxLength = PUBLIC_REVIEW_URL_MAX_LENGTH
  reviewLinkInput.placeholder = 'https://yandex.ru/maps/.../reviews'
  const reviewLinkActions = el('div', { className: 'venue-inline-actions' }) as HTMLDivElement
  const reviewLinkSaveButton = el('button', { text: 'Сохранить' }) as HTMLButtonElement
  const reviewLinkClearButton = el('button', { className: 'button-secondary', text: 'Очистить' }) as HTMLButtonElement
  reviewLinkSaveButton.disabled = true
  reviewLinkClearButton.disabled = true
  append(reviewLinkActions, reviewLinkSaveButton, reviewLinkClearButton)
  append(reviewLinkForm, reviewLinkLabel, reviewLinkInput, reviewLinkActions)
  append(reviewLinkCard, reviewLinkTitle, reviewLinkDescription, reviewLinkCurrent, reviewLinkForm)

  const scheduleCard = el('section', { className: 'card' })
  const scheduleTitle = el('h3', { text: 'Часы работы' })
  const scheduleDescription = el('p', {
    text: 'Настройте базовый недельный график и исключения для конкретных дат.'
  })
  const scheduleSummary = el('p', { className: 'venue-order-sub', text: '' })
  const weeklyScheduleList = el('div', { className: 'venue-form-grid' }) as HTMLDivElement
  const overridesTitle = el('h4', { text: 'Исключения по датам' })
  const overridesDescription = el('p', {
    className: 'venue-order-sub',
    text: 'Закрывайте отдельные даты или задавайте особые часы, например на праздники.'
  })
  const overrideActions = el('div', { className: 'button-row' }) as HTMLDivElement
  const closePeriodButton = el('button', {
    className: 'button-secondary button-small',
    text: 'Закрыть период'
  }) as HTMLButtonElement
  const changeHoursPeriodButton = el('button', {
    className: 'button-secondary button-small',
    text: 'Изменить часы на период'
  }) as HTMLButtonElement
  const showOverridesButton = el('button', {
    className: 'button-secondary button-small',
    text: 'Показать исключения'
  }) as HTMLButtonElement
  append(overrideActions, closePeriodButton, changeHoursPeriodButton, showOverridesButton)
  const overrideForm = el('div', { className: 'venue-form-grid' }) as HTMLDivElement
  overrideForm.dataset.testid = 'schedule-exception-form'
  overrideForm.hidden = true
  const overrideFormTitle = el('p', { className: 'field-label', text: 'Закрыть период' })
  const overrideFromDateLabel = el('p', { className: 'field-label', text: 'С даты' })
  const overrideFromDateInput = document.createElement('input')
  overrideFromDateInput.className = 'venue-input'
  overrideFromDateInput.type = 'date'
  const overrideToDateLabel = el('p', { className: 'field-label', text: 'По дату' })
  const overrideToDateInput = document.createElement('input')
  overrideToDateInput.className = 'venue-input'
  overrideToDateInput.type = 'date'
  const overrideOpensLabel = el('p', { className: 'field-label', text: 'Открываемся' })
  const overrideOpensInput = document.createElement('input')
  overrideOpensInput.className = 'venue-input'
  overrideOpensInput.type = 'time'
  overrideOpensInput.value = '18:00'
  const overrideClosesLabel = el('p', { className: 'field-label', text: 'Закрываемся' })
  const overrideClosesInput = document.createElement('input')
  overrideClosesInput.className = 'venue-input'
  overrideClosesInput.type = 'time'
  overrideClosesInput.value = '00:00'
  const overrideNoteLabel = el('p', { className: 'field-label', text: 'Причина' })
  const overrideNoteInput = document.createElement('textarea')
  overrideNoteInput.className = 'venue-input'
  overrideNoteInput.rows = 2
  const overrideHelper = el('p', {
    className: 'venue-order-sub',
    text: 'Если выбрать одну и ту же дату, закроется только этот день.'
  })
  const overrideSaveButton = el('button', { text: 'Сохранить' }) as HTMLButtonElement
  overrideSaveButton.disabled = true
  const overrideCancelButton = el('button', {
    className: 'button-secondary',
    text: 'Отмена'
  }) as HTMLButtonElement
  append(
    overrideForm,
    overrideFormTitle,
    overrideFromDateLabel,
    overrideFromDateInput,
    overrideToDateLabel,
    overrideToDateInput,
    overrideOpensLabel,
    overrideOpensInput,
    overrideClosesLabel,
    overrideClosesInput,
    overrideNoteLabel,
    overrideNoteInput,
    overrideHelper,
    overrideSaveButton,
    overrideCancelButton
  )
  const overrideList = el('div', { className: 'venue-form-grid' }) as HTMLDivElement
  overrideList.dataset.testid = 'schedule-exception-list'
  overrideList.hidden = true
  append(
    scheduleCard,
    scheduleTitle,
    scheduleDescription,
    scheduleSummary,
    weeklyScheduleList,
    overridesTitle,
    overridesDescription,
    overrideActions,
    overrideForm,
    overrideList
  )

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
  append(wrapper, header, publicCard, reviewLinkCard, scheduleCard, bookingCard, extensionCard, backButton)
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
    reviewLinkCard,
    reviewLinkCurrent,
    reviewLinkInput,
    reviewLinkSaveButton,
    reviewLinkClearButton,
    reviewLinkForm,
    scheduleCard,
    scheduleSummary,
    weeklyScheduleList,
    overrideActions,
    closePeriodButton,
    changeHoursPeriodButton,
    showOverridesButton,
    overrideForm,
    overrideFormTitle,
    overrideFromDateInput,
    overrideToDateInput,
    overrideNoteLabel,
    overrideNoteInput,
    overrideHelper,
    overrideOpensLabel,
    overrideOpensInput,
    overrideClosesLabel,
    overrideClosesInput,
    overrideSaveButton,
    overrideCancelButton,
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
  onEditOverride: (group: ScheduleOverrideGroup) => void
  onDeleteOverrideRange: (fromDate: string, toDate: string) => void
}

type OverrideFormMode = 'closed' | 'hours'

type ScheduleOverrideGroup = {
  fromDate: string
  toDate: string
  opensAt: string
  closesAt: string
  isClosed: boolean
  guestNote?: string | null
}

type EditingOverrideRange = {
  fromDate: string
  toDate: string
}

function isValidScheduleTime(value: string): boolean {
  return /^([01]\d|2[0-3]):[0-5]\d$/.test(value.trim())
}

function formatScheduleLine(opensAt: string, closesAt: string, isClosed: boolean): string {
  if (isClosed) return 'Закрыто'
  if (opensAt === closesAt) return 'Круглосуточно'
  return `${opensAt}-${closesAt}`
}

function formatOverrideScheduleLine(opensAt: string, closesAt: string, isClosed: boolean): string {
  if (isClosed) return 'Закрыто'
  if (opensAt === closesAt) return 'Круглосуточно'
  return `${opensAt}–${closesAt}`
}

function formatScheduleDate(value: string): string {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  if (!match) return value
  return `${match[3]}.${match[2]}.${match[1]}`
}

function addIsoDays(value: string, days: number): string {
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

function sameOverrideGroup(left: ScheduleOverrideGroup, right: VenueScheduleOverrideDto): boolean {
  return (
    left.isClosed === right.isClosed &&
    left.opensAt === right.opensAt &&
    left.closesAt === right.closesAt &&
    (left.guestNote ?? '') === (right.guestNote ?? '')
  )
}

function groupScheduleOverrides(overrides: VenueScheduleOverrideDto[]): ScheduleOverrideGroup[] {
  const groups: ScheduleOverrideGroup[] = []
  overrides
    .slice()
    .sort((left, right) => left.serviceDate.localeCompare(right.serviceDate))
    .forEach((override) => {
      const previous = groups[groups.length - 1]
      if (previous && addIsoDays(previous.toDate, 1) === override.serviceDate && sameOverrideGroup(previous, override)) {
        previous.toDate = override.serviceDate
        return
      }
      groups.push({
        fromDate: override.serviceDate,
        toDate: override.serviceDate,
        opensAt: override.opensAt,
        closesAt: override.closesAt,
        isClosed: override.isClosed,
        guestNote: override.guestNote
      })
    })
  return groups
}

function formatDateRange(fromDate: string, toDate: string): string {
  return fromDate === toDate ? formatScheduleDate(fromDate) : `${formatScheduleDate(fromDate)}–${formatScheduleDate(toDate)}`
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

function renderScheduleOverrideGroup(
  group: ScheduleOverrideGroup,
  callbacks: ScheduleCallbacks
): HTMLElement {
  const row = el('div', { className: 'venue-form-grid' })
  const summary = el('p', {
    className: 'venue-order-sub',
    text:
      `${formatDateRange(group.fromDate, group.toDate)} · ` +
      formatOverrideScheduleLine(group.opensAt, group.closesAt, group.isClosed)
  })
  row.appendChild(summary)
  if (group.guestNote) {
    row.appendChild(
      el('p', {
        className: 'venue-order-sub',
        text: `${group.isClosed ? 'Причина' : 'Комментарий'}: ${group.guestNote}`
      })
    )
  }
  const editButton = el('button', {
    className: 'button-secondary button-small',
    text: 'Изменить'
  }) as HTMLButtonElement
  const deleteButton = el('button', {
    className: 'button-secondary button-small',
    text: 'Удалить'
  }) as HTMLButtonElement
  editButton.addEventListener('click', () => callbacks.onEditOverride(group))
  deleteButton.addEventListener('click', () => callbacks.onDeleteOverrideRange(group.fromDate, group.toDate))
  append(row, editButton, deleteButton)
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
      ? groupScheduleOverrides(settings.dateOverrides).map((group) => renderScheduleOverrideGroup(group, callbacks))
      : [el('p', { className: 'venue-order-sub', text: 'Исключения не настроены.' })])
  )
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

function renderPublicReviewUrlSettings(refs: VenueSettingsRefs, settings: VenuePublicReviewUrlResponse) {
  const url = normalizeNullableText(settings.publicReviewUrl)
  refs.reviewLinkInput.value = url ?? ''
  refs.reviewLinkCurrent.textContent = url ? `Текущая ссылка: ${url}` : 'Ссылка пока не задана.'
  refs.reviewLinkClearButton.disabled = !url
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
  const canManageReviewLink = access.permissions.includes('VENUE_SETTINGS')
  const canManageSchedule = access.role === 'OWNER' || access.role === 'MANAGER'
  const canManageBookingSettings = access.permissions.includes('BOOKING_MANAGE')
  const canManageShiftExtension = access.permissions.includes('SHIFT_EXTENSION_SETTINGS')
  const disposables: Array<() => void> = []

  let disposed = false
  let loadAbort: AbortController | null = null
  let publicCardSaveAbort: AbortController | null = null
  let reviewLinkSaveAbort: AbortController | null = null
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
  let currentReviewLinkSettings: VenuePublicReviewUrlResponse | null = null
  let reviewLinkSaving = false
  let currentScheduleSettings: VenueScheduleSettingsResponse | null = null
  let overrideFormMode: OverrideFormMode | null = null
  let editingOverrideRange: EditingOverrideRange | null = null
  let scheduleSaving = false
  let currentBookingSettings: VenueBookingSettingsResponse | null = null
  let currentShiftExtensionSettings: ShiftExtensionSettingsDto | null = null

  const resetOverrideFormValues = () => {
    refs.overrideFromDateInput.value = ''
    refs.overrideToDateInput.value = ''
    refs.overrideOpensInput.value = '18:00'
    refs.overrideClosesInput.value = '00:00'
    refs.overrideNoteInput.value = ''
  }

  const updateOverrideSaveButton = () => {
    if (scheduleSaving) {
      refs.overrideSaveButton.disabled = true
      refs.overrideSaveButton.textContent = 'Сохраняем…'
      return
    }
    refs.overrideSaveButton.textContent = 'Сохранить'
    const mode = overrideFormMode
    const fromDate = refs.overrideFromDateInput.value.trim()
    const toDate = refs.overrideToDateInput.value.trim()
    const datesValid =
      /^\d{4}-\d{2}-\d{2}$/.test(fromDate) &&
      /^\d{4}-\d{2}-\d{2}$/.test(toDate) &&
      toDate >= fromDate
    const timesValid =
      mode === 'closed' ||
      (mode === 'hours' && isValidScheduleTime(refs.overrideOpensInput.value) && isValidScheduleTime(refs.overrideClosesInput.value))
    refs.overrideSaveButton.disabled = !mode || !datesValid || !timesValid
  }

  const syncOverrideFormMode = () => {
    const isHoursMode = overrideFormMode === 'hours'
    refs.overrideOpensLabel.hidden = !isHoursMode
    refs.overrideOpensInput.hidden = !isHoursMode
    refs.overrideClosesLabel.hidden = !isHoursMode
    refs.overrideClosesInput.hidden = !isHoursMode
    refs.overrideNoteLabel.textContent = isHoursMode ? 'Комментарий для гостей' : 'Причина'
    refs.overrideHelper.textContent = isHoursMode
      ? 'Эти часы заменят обычный график только на выбранные даты.'
      : 'Если выбрать одну и ту же дату, закроется только этот день.'
    updateOverrideSaveButton()
  }

  const openOverrideForm = (mode: OverrideFormMode, group: ScheduleOverrideGroup | null = null) => {
    overrideFormMode = mode
    editingOverrideRange = group ? { fromDate: group.fromDate, toDate: group.toDate } : null
    refs.overrideForm.hidden = false
    refs.overrideFormTitle.textContent = mode === 'closed' ? 'Закрыть период' : 'Изменить часы на период'
    refs.overrideFromDateInput.disabled = false
    refs.overrideToDateInput.disabled = false
    if (group) {
      refs.overrideFromDateInput.value = group.fromDate
      refs.overrideToDateInput.value = group.toDate
      refs.overrideOpensInput.value = group.opensAt
      refs.overrideClosesInput.value = group.closesAt
      refs.overrideNoteInput.value = group.guestNote ?? ''
    } else {
      resetOverrideFormValues()
    }
    syncOverrideFormMode()
  }

  const closeOverrideForm = () => {
    overrideFormMode = null
    editingOverrideRange = null
    refs.overrideForm.hidden = true
    refs.overrideFromDateInput.disabled = false
    refs.overrideToDateInput.disabled = false
    resetOverrideFormValues()
    updateOverrideSaveButton()
  }

  const toggleOverrideList = () => {
    refs.overrideList.hidden = !refs.overrideList.hidden
    refs.showOverridesButton.textContent = refs.overrideList.hidden ? 'Показать исключения' : 'Скрыть исключения'
  }

  if (!canManagePublicCard) {
    refs.publicCard.remove()
  }
  if (!canManageReviewLink) {
    refs.reviewLinkCard.remove()
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
  if (
    !canManagePublicCard &&
    !canManageReviewLink &&
    !canManageSchedule &&
    !canManageBookingSettings &&
    !canManageShiftExtension
  ) {
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

  const currentReviewLinkValue = () => normalizeNullableText(refs.reviewLinkInput.value)

  const reviewLinkIsDirty = () => {
    if (!currentReviewLinkSettings) return false
    return currentReviewLinkValue() !== normalizeNullableText(currentReviewLinkSettings.publicReviewUrl)
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

  const updateReviewLinkButtons = () => {
    if (reviewLinkSaving) {
      refs.reviewLinkSaveButton.disabled = true
      refs.reviewLinkClearButton.disabled = true
      refs.reviewLinkSaveButton.textContent = 'Сохраняем…'
      return
    }
    refs.reviewLinkSaveButton.textContent = 'Сохранить'
    refs.reviewLinkSaveButton.disabled = !reviewLinkIsDirty()
    refs.reviewLinkClearButton.disabled = !normalizeNullableText(currentReviewLinkSettings?.publicReviewUrl)
  }

  const setPublicCardBusy = (busy: boolean) => {
    publicCardSaving = busy
    updatePublicCardSaveButton()
  }

  const setReviewLinkBusy = (busy: boolean) => {
    reviewLinkSaving = busy
    updateReviewLinkButtons()
  }

  const setBookingBusy = (busy: boolean) => {
    refs.bookingSaveButton.disabled = busy
    refs.bookingSaveButton.textContent = busy ? 'Сохраняем…' : 'Сохранить'
  }

  const setScheduleBusy = (busy: boolean) => {
    scheduleSaving = busy
    updateOverrideSaveButton()
    refs.overrideCancelButton.disabled = busy
    refs.closePeriodButton.disabled = busy
    refs.changeHoursPeriodButton.disabled = busy
    refs.showOverridesButton.disabled = busy
    refs.weeklyScheduleList.querySelectorAll<HTMLButtonElement>('button').forEach((button) => {
      button.disabled = busy
    })
    refs.overrideList.querySelectorAll<HTMLButtonElement>('button').forEach((button) => {
      button.disabled = busy
    })
  }

  const setExtensionBusy = (busy: boolean) => {
    refs.extensionSaveButton.disabled = busy
    refs.extensionSaveButton.textContent = busy ? 'Сохраняем…' : 'Сохранить'
  }

  const load = async () => {
    if (
      !canManagePublicCard &&
      !canManageReviewLink &&
      !canManageSchedule &&
      !canManageBookingSettings &&
      !canManageShiftExtension
    ) return
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
    if (canManageReviewLink) {
      const result = await venueGetPublicReviewUrl(backendUrl, { venueId }, deps, controller.signal)
      if (disposed || loadAbort !== controller) return
      if (!result.ok) {
        if (result.error.code === REQUEST_ABORTED_CODE) return
        loadAbort = null
        renderApiError(refs.status, result.error, isDebug)
        return
      }
      currentReviewLinkSettings = result.data
      renderPublicReviewUrlSettings(refs, currentReviewLinkSettings)
      updateReviewLinkButtons()
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

  const saveReviewLinkSettings = async () => {
    if (!canManageReviewLink || reviewLinkSaving || !reviewLinkIsDirty()) return
    const value = currentReviewLinkValue()
    if (!value) {
      refs.status.textContent = 'Введите ссылку или очистите текущую настройку.'
      return
    }
    if (value.length > PUBLIC_REVIEW_URL_MAX_LENGTH || !value.startsWith('https://')) {
      refs.status.textContent = 'Ссылка должна начинаться с https:// и быть короче 2048 символов.'
      return
    }

    setReviewLinkBusy(true)
    reviewLinkSaveAbort?.abort()
    const controller = new AbortController()
    reviewLinkSaveAbort = controller
    const result = await venueUpdatePublicReviewUrl(
      backendUrl,
      { venueId, body: { publicReviewUrl: value } },
      deps,
      controller.signal
    )
    if (disposed || reviewLinkSaveAbort !== controller) return
    reviewLinkSaveAbort = null
    setReviewLinkBusy(false)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentReviewLinkSettings = result.data
    renderPublicReviewUrlSettings(refs, currentReviewLinkSettings)
    updateReviewLinkButtons()
    refs.status.textContent = 'Ссылка для отзывов сохранена.'
    showToast('Ссылка для отзывов сохранена.')
  }

  const clearReviewLinkSettings = async () => {
    if (!canManageReviewLink || reviewLinkSaving || !normalizeNullableText(currentReviewLinkSettings?.publicReviewUrl)) return
    setReviewLinkBusy(true)
    reviewLinkSaveAbort?.abort()
    const controller = new AbortController()
    reviewLinkSaveAbort = controller
    const result = await venueClearPublicReviewUrl(backendUrl, { venueId }, deps, controller.signal)
    if (disposed || reviewLinkSaveAbort !== controller) return
    reviewLinkSaveAbort = null
    setReviewLinkBusy(false)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentReviewLinkSettings = result.data
    renderPublicReviewUrlSettings(refs, currentReviewLinkSettings)
    updateReviewLinkButtons()
    refs.status.textContent = 'Ссылка для отзывов очищена.'
    showToast('Ссылка для отзывов очищена.')
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
    const mode = overrideFormMode
    const fromDate = refs.overrideFromDateInput.value.trim()
    const toDate = refs.overrideToDateInput.value.trim()
    const isClosed = mode === 'closed'
    const opensAt = refs.overrideOpensInput.value
    const closesAt = refs.overrideClosesInput.value
    const guestNote = refs.overrideNoteInput.value.trim() || null
    const originalRange = editingOverrideRange
    if (!mode) {
      refs.status.textContent = 'Выберите действие для исключения.'
      return
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(fromDate) || !/^\d{4}-\d{2}-\d{2}$/.test(toDate)) {
      refs.status.textContent = 'Выберите даты периода.'
      return
    }
    if (toDate < fromDate) {
      refs.status.textContent = 'Дата окончания должна быть не раньше даты начала.'
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
    const body = {
      fromDate,
      toDate,
      isClosed,
      opensAt: isClosed ? null : opensAt,
      closesAt: isClosed ? null : closesAt,
      guestNote
    }
    const result = originalRange
      ? await venueReplaceScheduleOverrideRange(
          backendUrl,
          {
            venueId,
            fromDate: originalRange.fromDate,
            toDate: originalRange.toDate,
            body
          },
          deps,
          controller.signal
        )
      : await venueUpdateScheduleOverrideRange(
          backendUrl,
          {
            venueId,
            body
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
    refs.overrideList.hidden = false
    refs.showOverridesButton.textContent = 'Скрыть исключения'
    closeOverrideForm()
    const successMessage = isClosed ? 'Исключение сохранено.' : 'Особые часы сохранены.'
    refs.status.textContent = successMessage
    showToast(successMessage)
  }

  const deleteScheduleOverrideRange = async (fromDate: string, toDate: string, announce = true) => {
    if (!canManageSchedule || !currentScheduleSettings) return
    setScheduleBusy(true)
    scheduleSaveAbort?.abort()
    const controller = new AbortController()
    scheduleSaveAbort = controller
    const result = await venueDeleteScheduleOverrideRange(
      backendUrl,
      { venueId, fromDate, toDate },
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
    if (announce) {
      refs.status.textContent = 'Исключение удалено.'
      showToast('Исключение удалено.')
    }
  }

  const scheduleCallbacks: ScheduleCallbacks = {
    onSaveDay: (weekday, isClosed, opensAt, closesAt) =>
      void saveScheduleDay(weekday, isClosed, opensAt, closesAt),
    onEditOverride: (group) => {
      openOverrideForm(group.isClosed ? 'closed' : 'hours', group)
      refs.overrideList.hidden = false
      refs.showOverridesButton.textContent = 'Скрыть исключения'
    },
    onDeleteOverrideRange: (fromDate, toDate) => void deleteScheduleOverrideRange(fromDate, toDate)
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
  disposables.push(on(refs.reviewLinkInput, 'input', updateReviewLinkButtons))
  disposables.push(on(refs.reviewLinkSaveButton, 'click', () => void saveReviewLinkSettings()))
  disposables.push(on(refs.reviewLinkClearButton, 'click', () => void clearReviewLinkSettings()))
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
  disposables.push(on(refs.closePeriodButton, 'click', () => openOverrideForm('closed')))
  disposables.push(on(refs.changeHoursPeriodButton, 'click', () => openOverrideForm('hours')))
  disposables.push(on(refs.showOverridesButton, 'click', toggleOverrideList))
  ;[
    refs.overrideFromDateInput,
    refs.overrideToDateInput,
    refs.overrideOpensInput,
    refs.overrideClosesInput,
    refs.overrideNoteInput
  ].forEach((input) => {
    disposables.push(on(input, 'input', updateOverrideSaveButton))
  })
  disposables.push(on(refs.overrideSaveButton, 'click', () => void saveScheduleOverride()))
  disposables.push(on(refs.overrideCancelButton, 'click', closeOverrideForm))
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
    reviewLinkSaveAbort?.abort()
    scheduleSaveAbort?.abort()
    if (publicCardSavedTimer) window.clearTimeout(publicCardSavedTimer)
    bookingSaveAbort?.abort()
    extensionSaveAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
