import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueGetShiftExtensionSettings,
  venueUpdateShiftExtensionSettings
} from '../shared/api/venueApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { ShiftExtensionSettingsDto, VenueAccessDto } from '../shared/api/venueDtos'
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
  summary: HTMLParagraphElement
  hint: HTMLParagraphElement
  enabledInput: HTMLInputElement
  durationSelect: HTMLSelectElement
  priceInput: HTMLInputElement
  saveButton: HTMLButtonElement
  form: HTMLDivElement
  backButton: HTMLButtonElement
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

  const extensionCard = el('section', { className: 'card' })
  const extensionTitle = el('h3', { text: 'Продление времени' })
  const description = el('p', {
    text: 'Гости смогут запросить платное продление. Персонал подтвердит возможность продления перед добавлением суммы в счёт.'
  })
  const summary = el('p', { className: 'venue-order-sub', text: '' })
  const hint = el('p', { className: 'venue-order-sub', text: '' })

  const form = el('div', { className: 'venue-form-grid' }) as HTMLDivElement
  const enabledLabel = document.createElement('label')
  enabledLabel.className = 'venue-settings-toggle'
  const enabledInput = document.createElement('input')
  enabledInput.type = 'checkbox'
  const enabledText = el('span', { text: 'Включить запросы на продление' })
  append(enabledLabel, enabledInput, enabledText)

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

  const saveButton = el('button', { text: 'Сохранить' }) as HTMLButtonElement
  append(form, enabledLabel, durationLabel, durationSelect, priceLabel, priceInput, saveButton)

  append(extensionCard, extensionTitle, description, summary, hint, form)

  const backButton = el('button', { className: 'button-secondary', text: 'Вернуться в обзор' }) as HTMLButtonElement
  append(wrapper, header, extensionCard, backButton)
  root.replaceChildren(wrapper)

  return {
    status,
    summary,
    hint,
    enabledInput,
    durationSelect,
    priceInput,
    saveButton,
    form,
    backButton
  }
}

function renderApiError(status: HTMLParagraphElement, error: ApiErrorInfo, isDebug: boolean) {
  const code = normalizeErrorCode(error)
  if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
    clearSession()
  }
  status.textContent = isDebug ? `${error.message || 'Ошибка'} (${error.code ?? error.status})` : error.message || 'Не удалось выполнить действие.'
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

function renderSettings(refs: VenueSettingsRefs, settings: ShiftExtensionSettingsDto) {
  refs.enabledInput.checked = settings.enabled
  refs.durationSelect.value = String(settings.durationMinutes)
  if (refs.durationSelect.value !== String(settings.durationMinutes)) {
    refs.durationSelect.appendChild(new Option(`${settings.durationMinutes} минут`, String(settings.durationMinutes)))
    refs.durationSelect.value = String(settings.durationMinutes)
  }
  refs.priceInput.value = rubInputValue(settings.priceMinor)

  const price = settings.priceMinor == null ? 'цена не задана' : formatPrice(settings.priceMinor, settings.currency || 'RUB')
  const state = settings.enabled ? 'Включено' : 'Выключено'
  refs.summary.textContent = `${state} · ${settings.durationMinutes} мин · ${price}`
  refs.hint.textContent = settings.configured
    ? 'Гости увидят кнопку продления в активном счёте.'
    : 'Настройте цену и длительность, чтобы гости могли запросить продление.'
}

export function renderVenueSettingsScreen(options: VenueSettingsOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined

  const refs = buildDom(root)
  const deps = buildApiDeps(isDebug)
  const canManageShiftExtension = access.permissions.includes('SHIFT_EXTENSION_SETTINGS')
  const disposables: Array<() => void> = []

  let disposed = false
  let loadAbort: AbortController | null = null
  let saveAbort: AbortController | null = null
  let currentSettings: ShiftExtensionSettingsDto | null = null

  if (!canManageShiftExtension) {
    refs.form.remove()
    refs.status.textContent = 'У вас нет доступа к настройкам продления.'
  }

  const setBusy = (busy: boolean) => {
    refs.saveButton.disabled = busy
    refs.saveButton.textContent = busy ? 'Сохраняем…' : 'Сохранить'
  }

  const load = async () => {
    if (!canManageShiftExtension) return
    refs.status.textContent = 'Загрузка…'
    loadAbort?.abort()
    const controller = new AbortController()
    loadAbort = controller
    const result = await venueGetShiftExtensionSettings(backendUrl, { venueId }, deps, controller.signal)
    if (disposed || loadAbort !== controller) return
    loadAbort = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentSettings = result.data.settings
    renderSettings(refs, currentSettings)
    refs.status.textContent = 'Настройки загружены.'
  }

  const save = async () => {
    if (!canManageShiftExtension || !currentSettings) return
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

    setBusy(true)
    saveAbort?.abort()
    const controller = new AbortController()
    saveAbort = controller
    const result = await venueUpdateShiftExtensionSettings(
      backendUrl,
      {
        venueId,
        body: {
          enabled: refs.enabledInput.checked,
          durationMinutes,
          priceMinor,
          currency: 'RUB',
          maxExtensionsPerSession: currentSettings.maxExtensionsPerSession ?? null
        }
      },
      deps,
      controller.signal
    )
    if (disposed || saveAbort !== controller) return
    saveAbort = null
    setBusy(false)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentSettings = result.data.settings
    renderSettings(refs, currentSettings)
    refs.status.textContent = 'Настройки сохранены.'
    showToast('Настройки сохранены')
  }

  disposables.push(on(refs.saveButton, 'click', () => void save()))
  disposables.push(on(refs.backButton, 'click', () => {
    window.location.hash = '#/dashboard'
  }))

  void load()

  return () => {
    disposed = true
    loadAbort?.abort()
    saveAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
