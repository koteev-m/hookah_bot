import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestGetVenue } from '../shared/api/guestApi'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

export type VenueSettingsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type SettingsRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  nameInput: HTMLInputElement
  phoneInput: HTMLInputElement
  addressInput: HTMLInputElement
  scheduleInput: HTMLTextAreaElement
  rulesInput: HTMLTextAreaElement
  flagsContainer: HTMLDivElement
  saveButton: HTMLButtonElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
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

function buildSettingsDom(root: HTMLDivElement): SettingsRefs {
  const wrapper = el('div', { className: 'venue-settings' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Настройки' })
  append(header, title)

  const profileCard = el('div', { className: 'card' })
  const profileTitle = el('h3', { text: 'Профиль' })
  const nameInput = document.createElement('input')
  nameInput.className = 'venue-input'
  nameInput.placeholder = 'Название'
  const phoneInput = document.createElement('input')
  phoneInput.className = 'venue-input'
  phoneInput.placeholder = 'Телефон'
  const addressInput = document.createElement('input')
  addressInput.className = 'venue-input'
  addressInput.placeholder = 'Адрес'
  append(profileCard, profileTitle, nameInput, phoneInput, addressInput)

  const scheduleCard = el('div', { className: 'card' })
  const scheduleTitle = el('h3', { text: 'График' })
  const scheduleInput = document.createElement('textarea')
  scheduleInput.className = 'venue-textarea'
  scheduleInput.rows = 3
  scheduleInput.placeholder = 'Например: Пн-Вс 12:00-02:00'
  append(scheduleCard, scheduleTitle, scheduleInput)

  const rulesCard = el('div', { className: 'card' })
  const rulesTitle = el('h3', { text: 'Правила' })
  const rulesInput = document.createElement('textarea')
  rulesInput.className = 'venue-textarea'
  rulesInput.rows = 3
  rulesInput.placeholder = 'Короткие правила заведения'
  append(rulesCard, rulesTitle, rulesInput)

  const flagsCard = el('div', { className: 'card' })
  const flagsTitle = el('h3', { text: 'Модули и фичи' })
  const flagsContainer = el('div', { className: 'venue-flags' })
  append(flagsCard, flagsTitle, flagsContainer)

  const saveButton = el('button', { text: 'Сохранить' }) as HTMLButtonElement

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, header, profileCard, scheduleCard, rulesCard, flagsCard, saveButton, status, error)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    nameInput,
    phoneInput,
    addressInput,
    scheduleInput,
    rulesInput,
    flagsContainer,
    saveButton
  }
}

export function renderVenueSettingsScreen(options: VenueSettingsOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  const refs = buildSettingsDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let loadSeq = 0

  const canEdit = access.permissions.includes('VENUE_SETTINGS')

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
  }

  const showError = (error: ApiErrorInfo) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.error.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    const actions: ApiErrorAction[] = presentation.actions.length
      ? presentation.actions.map((action) =>
          action.label === 'Повторить' ? { ...action, onClick: () => void loadSettings() } : action
        )
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void loadSettings() }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const renderFlags = () => {
    refs.flagsContainer.replaceChildren()
    if (!access.permissions.length) {
      refs.flagsContainer.appendChild(el('p', { className: 'venue-empty', text: 'Модули не заданы.' }))
      return
    }
    access.permissions.forEach((perm) => {
      const label = document.createElement('label')
      label.className = 'venue-flag'
      const checkbox = document.createElement('input')
      checkbox.type = 'checkbox'
      checkbox.checked = true
      checkbox.disabled = !canEdit
      const span = document.createElement('span')
      span.textContent = perm
      append(label, checkbox, span)
      refs.flagsContainer.appendChild(label)
    })
  }

  const loadSettings = async () => {
    hideError()
    setStatus('Загрузка...')
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    const result = await guestGetVenue(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadSeq !== seq) return
    loadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      showError(result.error)
      setStatus('')
      return
    }
    const venue = result.data.venue
    refs.nameInput.value = venue.name
    refs.addressInput.value = venue.address ?? ''
    renderFlags()
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const saveSettings = () => {
    if (!canEdit) {
      showToast('Недостаточно прав')
      return
    }
    showToast('Настройки сохранены')
    setStatus(`Сохранено: ${new Date().toLocaleTimeString()}`)
  }

  const disableInputs = () => {
    const fields = [
      refs.nameInput,
      refs.phoneInput,
      refs.addressInput,
      refs.scheduleInput,
      refs.rulesInput
    ]
    fields.forEach((field) => {
      field.disabled = !canEdit
    })
    refs.saveButton.disabled = !canEdit
    refs.saveButton.title = canEdit ? '' : 'Недостаточно прав'
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.saveButton, 'click', saveSettings))

  disableInputs()
  void loadSettings()

  return () => {
    disposed = true
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
