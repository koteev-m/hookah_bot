import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueCreateStaffChatLinkCode, venueGetStaffChatStatus } from '../shared/api/venueApi'
import type { VenueAccessDto, VenueStaffChatStatusResponse } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

export type VenueChatLinkOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type ChatLinkRefs = {
  status: HTMLParagraphElement
  codeSection: HTMLDivElement
  codeValue: HTMLSpanElement
  codeExpires: HTMLSpanElement
  generateButton: HTMLButtonElement
  refreshButton: HTMLButtonElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
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

function buildChatLinkDom(root: HTMLDivElement): ChatLinkRefs {
  const wrapper = el('div', { className: 'venue-chat-link' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Привязка чата персонала' })
  const status = el('p', { className: 'status', text: '' })
  const instructions = el('ol', { className: 'venue-instructions' })
  instructions.appendChild(el('li', { text: 'Добавьте бота в чат персонала.' }))
  instructions.appendChild(el('li', { text: 'Отправьте команду /link <код>.' }))
  instructions.appendChild(el('li', { text: 'Нажмите «Проверить статус».' }))
  append(header, title, status, instructions)

  const codeSection = el('div', { className: 'card venue-link-code' })
  const codeTitle = el('h3', { text: 'Код привязки' })
  const codeValue = el('span', { text: '—' })
  const codeExpires = el('span', { text: '—' })
  append(codeSection, codeTitle, el('p', { text: 'Код:' }), codeValue, el('p', { text: 'Истекает:' }), codeExpires)

  const actions = el('div', { className: 'button-row' })
  const generateButton = el('button', { text: 'Сгенерировать код' }) as HTMLButtonElement
  const refreshButton = el('button', { className: 'button-secondary', text: 'Проверить статус' }) as HTMLButtonElement
  append(actions, generateButton, refreshButton)

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, header, codeSection, actions, error)
  root.replaceChildren(wrapper)

  return {
    status,
    codeSection,
    codeValue,
    codeExpires,
    generateButton,
    refreshButton,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
}

function maskChatId(chatId?: number | null) {
  if (!chatId) return '—'
  const tail = String(chatId).slice(-4)
  return `****${tail}`
}

export function renderVenueChatLinkScreen(options: VenueChatLinkOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  const refs = buildChatLinkDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let loadSeq = 0

  const canLink = access.permissions.includes('STAFF_CHAT_LINK')

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
          action.label === 'Повторить' ? { ...action, onClick: () => void loadStatus() } : action
        )
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void loadStatus() }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const renderStatus = (status: VenueStaffChatStatusResponse) => {
    const state = status.isLinked ? 'Привязан' : 'Не привязан'
    refs.status.textContent = `${state} · ChatId: ${maskChatId(status.chatId)}`
    if (status.activeCodeHint) {
      refs.codeValue.textContent = status.activeCodeHint
      refs.codeExpires.textContent = status.activeCodeExpiresAt
        ? new Date(status.activeCodeExpiresAt).toLocaleString()
        : '—'
    }
  }

  const loadStatus = async () => {
    hideError()
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    const result = await venueGetStaffChatStatus(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadSeq !== seq) return
    loadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    renderStatus(result.data)
  }

  const generateCode = async () => {
    if (!canLink) {
      showToast('Недостаточно прав')
      return
    }
    const result = await venueCreateStaffChatLinkCode(backendUrl, venueId, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    refs.codeValue.textContent = result.data.code
    refs.codeExpires.textContent = new Date(result.data.expiresAt).toLocaleString()
    showToast('Код создан')
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.generateButton, 'click', () => void generateCode()))
  disposables.push(on(refs.refreshButton, 'click', () => void loadStatus()))

  refs.generateButton.disabled = !canLink
  refs.generateButton.title = canLink ? '' : 'Недостаточно прав'

  void loadStatus()

  return () => {
    disposed = true
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
