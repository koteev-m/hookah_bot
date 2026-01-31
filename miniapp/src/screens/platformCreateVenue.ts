import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { platformCreateVenue } from '../shared/api/platformApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

export type PlatformCreateVenueOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onNavigate: (hash: string) => void
}

type CreateRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  nameInput: HTMLInputElement
  cityInput: HTMLInputElement
  addressInput: HTMLInputElement
  submitButton: HTMLButtonElement
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

function buildCreateDom(root: HTMLDivElement): CreateRefs {
  const wrapper = el('div', { className: 'venue-settings' })
  const card = el('div', { className: 'card' })
  const title = el('h2', { text: 'Создать заведение' })

  const nameInput = document.createElement('input')
  nameInput.className = 'venue-input'
  nameInput.placeholder = 'Название (обязательно)'

  const cityInput = document.createElement('input')
  cityInput.className = 'venue-input'
  cityInput.placeholder = 'Город'

  const addressInput = document.createElement('input')
  addressInput.className = 'venue-input'
  addressInput.placeholder = 'Адрес'

  const submitButton = el('button', { text: 'Создать' }) as HTMLButtonElement

  const form = el('div', { className: 'venue-form-grid' })
  append(form, nameInput, cityInput, addressInput, submitButton)
  append(card, title, form)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, card, status, error)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    nameInput,
    cityInput,
    addressInput,
    submitButton
  }
}

export function renderPlatformCreateVenueScreen(options: PlatformCreateVenueOptions) {
  const { root, backendUrl, isDebug, onNavigate } = options
  if (!root) return () => undefined
  const refs = buildCreateDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let submitting = false

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
  }

  const showError = (error: ApiErrorInfo, retry: () => void) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.error.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    const actions: ApiErrorAction[] = presentation.actions.length
      ? presentation.actions.map((action) => (action.label === 'Повторить' ? { ...action, onClick: retry } : action))
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: retry }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const setSubmitting = (value: boolean) => {
    submitting = value
    refs.submitButton.disabled = value
  }

  const handleSubmit = async () => {
    if (submitting) return
    const name = refs.nameInput.value.trim()
    if (!name) {
      setStatus('Введите название заведения.')
      return
    }
    hideError()
    setStatus('Создаём...')
    setSubmitting(true)
    const result = await platformCreateVenue(
      backendUrl,
      {
        name,
        city: refs.cityInput.value.trim() || null,
        address: refs.addressInput.value.trim() || null
      },
      deps
    )
    if (disposed) return
    setSubmitting(false)
    if (!result.ok) {
      setStatus('')
      showError(result.error, handleSubmit)
      return
    }
    showToast('Заведение создано')
    onNavigate(`#/venue/${result.data.venue.id}`)
  }

  const dispose = on(refs.submitButton, 'click', (event) => {
    event.preventDefault()
    void handleSubmit()
  })

  return () => {
    disposed = true
    dispose()
  }
}
