import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueCreateStaffChatLinkCode,
  venueGetStaffChatStatus,
  venueSendStaffChatTestMessage,
  venueUnlinkStaffChat
} from '../shared/api/venueApi'
import type { StaffChatLinkCodeResponse, VenueAccessDto, VenueStaffChatStatusResponse } from '../shared/api/venueDtos'
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
  statusTitle: HTMLParagraphElement
  statusDescription: HTMLParagraphElement
  chatMeta: HTMLParagraphElement
  instructionsCard: HTMLElement
  codeSection: HTMLElement
  codeValue: HTMLSpanElement
  codeExpires: HTMLSpanElement
  commandValue: HTMLInputElement
  copyButton: HTMLButtonElement
  generateButton: HTMLButtonElement
  refreshButton: HTMLButtonElement
  testButton: HTMLButtonElement
  unlinkButton: HTMLButtonElement
  status: HTMLParagraphElement
  confirmOverlay: HTMLDivElement
  confirmCancelButton: HTMLButtonElement
  confirmUnlinkButton: HTMLButtonElement
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

  const summaryCard = el('section', { className: 'card' })
  const title = el('h2', { text: 'Чат персонала' })
  const statusTitle = el('p', { className: 'venue-chat-status-title', text: '' })
  const statusDescription = el('p', { className: 'venue-chat-status-description', text: '' })
  const chatMeta = el('p', { className: 'status', text: '' })
  append(summaryCard, title, statusTitle, statusDescription, chatMeta)

  const instructionsCard = el('section', { className: 'card' })
  const instructionsTitle = el('h3', { text: 'Как подключить' })
  const instructions = el('ol', { className: 'venue-instructions' })
  instructions.appendChild(el('li', { text: 'Создайте или откройте Telegram-группу персонала.' }))
  instructions.appendChild(el('li', { text: 'Добавьте бота в группу.' }))
  instructions.appendChild(el('li', { text: 'Разрешите боту отправлять сообщения.' }))
  instructions.appendChild(el('li', { text: 'Сгенерируйте код привязки.' }))
  instructions.appendChild(el('li', { text: 'Отправьте команду из блока ниже в этой группе.' }))
  append(instructionsCard, instructionsTitle, instructions)

  const codeSection = el('section', { className: 'card venue-link-code' })
  const codeTitle = el('h3', { text: 'Код привязки' })
  const codeValue = el('span', { className: 'venue-link-code-value', text: '—' })
  const codeExpires = el('span', { text: '—' })
  const commandValue = document.createElement('input')
  commandValue.type = 'text'
  commandValue.readOnly = true
  commandValue.value = ''
  commandValue.setAttribute('aria-label', 'Команда для привязки чата')
  const copyButton = el('button', { className: 'button-secondary', text: 'Скопировать команду' }) as HTMLButtonElement
  append(
    codeSection,
    codeTitle,
    el('p', { text: 'Код:' }),
    codeValue,
    el('p', { text: 'Истекает:' }),
    codeExpires,
    el('p', { text: 'Команда для группы:' }),
    commandValue,
    copyButton
  )

  const actions = el('div', { className: 'button-row venue-chat-actions' })
  const generateButton = el('button', { text: 'Сгенерировать код привязки' }) as HTMLButtonElement
  const refreshButton = el('button', { className: 'button-secondary', text: 'Проверить подключение' }) as HTMLButtonElement
  const testButton = el('button', { className: 'button-secondary', text: 'Отправить тестовое сообщение' }) as HTMLButtonElement
  const unlinkButton = el('button', { className: 'button-danger', text: 'Отвязать чат' }) as HTMLButtonElement
  append(actions, generateButton, refreshButton, testButton, unlinkButton)

  const status = el('p', { className: 'status', text: '' })

  const confirmOverlay = el('div', { className: 'venue-modal-overlay' }) as HTMLDivElement
  confirmOverlay.hidden = true
  const confirmDialog = el('section', { className: 'venue-modal card' })
  confirmDialog.setAttribute('role', 'dialog')
  confirmDialog.setAttribute('aria-modal', 'true')
  confirmDialog.setAttribute('aria-labelledby', 'venue-chat-unlink-title')
  const confirmTitle = el('h3', { id: 'venue-chat-unlink-title', text: 'Отвязать чат персонала?' })
  const confirmBody = el('p', {
    text:
      'Уведомления о заказах, бронях, вызовах и продлениях перестанут приходить в эту группу. ' +
      'Подключить чат снова можно будет новым кодом.'
  })
  const confirmActions = el('div', { className: 'button-row' })
  const confirmUnlinkButton = el('button', { className: 'button-danger', text: 'Отвязать' }) as HTMLButtonElement
  const confirmCancelButton = el('button', { className: 'button-secondary', text: 'Отмена' }) as HTMLButtonElement
  append(confirmActions, confirmUnlinkButton, confirmCancelButton)
  append(confirmDialog, confirmTitle, confirmBody, confirmActions)
  confirmOverlay.appendChild(confirmDialog)

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, summaryCard, instructionsCard, codeSection, actions, status, error, confirmOverlay)
  root.replaceChildren(wrapper)

  return {
    statusTitle,
    statusDescription,
    chatMeta,
    instructionsCard,
    codeSection,
    codeValue,
    codeExpires,
    commandValue,
    copyButton,
    generateButton,
    refreshButton,
    testButton,
    unlinkButton,
    status,
    confirmOverlay,
    confirmCancelButton,
    confirmUnlinkButton,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
}

function fallbackMaskChatId(chatId?: number | null) {
  if (!chatId) return '—'
  const text = String(chatId)
  return `...${text.slice(-4)}`
}

function formatDateTime(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

async function copyText(value: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.setAttribute('readonly', 'true')
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  document.execCommand('copy')
  document.body.removeChild(textarea)
}

function commandFromGeneratedCode(payload: StaffChatLinkCodeResponse) {
  return payload.linkCommand ?? `/link ${payload.code}`
}

export function renderVenueChatLinkScreen(options: VenueChatLinkOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  const refs = buildChatLinkDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let loadSeq = 0
  let currentStatus: VenueStaffChatStatusResponse | null = null
  let currentFullCode: string | null = null
  let currentLinkCommand: string | null = null
  let currentExpiresAt: string | null = null
  let isGenerating = false
  let isTesting = false
  let isUnlinking = false

  const canLink = access.permissions.includes('STAFF_CHAT_LINK')
  const canUnlink = access.role === 'OWNER'

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

  const renderStatus = () => {
    const status = currentStatus
    const linked = Boolean(status?.isLinked)

    refs.instructionsCard.hidden = linked
    refs.testButton.hidden = !linked
    refs.unlinkButton.hidden = !linked || !canUnlink
    refs.generateButton.hidden = linked || !canLink
    refs.refreshButton.hidden = false
    refs.generateButton.disabled = isGenerating || !canLink
    refs.testButton.disabled = isTesting
    refs.unlinkButton.disabled = isUnlinking
    refs.confirmUnlinkButton.disabled = isUnlinking

    if (!canLink) {
      refs.statusTitle.textContent = 'Нет доступа к управлению чатом персонала'
      refs.statusDescription.textContent = 'Для подключения и проверки чата нужны права владельца или менеджера.'
      refs.chatMeta.textContent = ''
      refs.codeSection.hidden = true
      refs.refreshButton.hidden = true
      return
    }

    if (linked) {
      refs.statusTitle.textContent = 'Чат персонала подключён'
      refs.statusDescription.textContent = 'Сюда будут приходить операционные уведомления заведения.'
      refs.chatMeta.textContent = `Чат: ${status?.maskedChatId ?? fallbackMaskChatId(status?.chatId)}`
      refs.codeSection.hidden = true
      currentFullCode = null
      currentLinkCommand = null
      currentExpiresAt = null
      return
    }

    refs.statusTitle.textContent = 'Чат персонала не подключён'
    refs.statusDescription.textContent =
      'Подключите Telegram-группу, чтобы получать уведомления о заказах, бронях, вызовах и продлениях.'
    refs.chatMeta.textContent = ''

    const hasGeneratedCode = Boolean(currentFullCode && currentLinkCommand)
    const hasActiveCodeHint = Boolean(status?.activeCodeHint)
    refs.codeSection.hidden = !hasGeneratedCode && !hasActiveCodeHint
    if (hasGeneratedCode) {
      refs.codeValue.textContent = currentFullCode ?? '—'
      refs.codeExpires.textContent = formatDateTime(currentExpiresAt)
      refs.commandValue.value = currentLinkCommand ?? ''
      refs.commandValue.disabled = false
      refs.copyButton.hidden = false
      refs.copyButton.disabled = !currentLinkCommand
      return
    }
    if (hasActiveCodeHint) {
      refs.codeValue.textContent = `начинается на ${status?.activeCodeHint ?? '—'}`
      refs.codeExpires.textContent = formatDateTime(status?.activeCodeExpiresAt)
      refs.commandValue.value = 'Полный код показывается только при создании. Сгенерируйте новый код, если он потерян.'
      refs.commandValue.disabled = true
      refs.copyButton.hidden = true
      refs.copyButton.disabled = true
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
    refs.status.textContent = 'Проверяем подключение…'
    const result = await venueGetStaffChatStatus(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadSeq !== seq) return
    loadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      refs.status.textContent = ''
      showError(result.error)
      return
    }
    currentStatus = result.data
    refs.status.textContent = ''
    renderStatus()
  }

  const generateCode = async () => {
    if (!canLink) {
      showToast('Недостаточно прав')
      return
    }
    hideError()
    isGenerating = true
    refs.status.textContent = 'Создаём код…'
    renderStatus()
    const result = await venueCreateStaffChatLinkCode(backendUrl, venueId, deps)
    if (disposed) return
    isGenerating = false
    if (!result.ok) {
      refs.status.textContent = ''
      showError(result.error)
      renderStatus()
      return
    }
    currentFullCode = result.data.code
    currentLinkCommand = commandFromGeneratedCode(result.data)
    currentExpiresAt = result.data.expiresAt
    currentStatus = {
      ...(currentStatus ?? { venueId, isLinked: false }),
      isLinked: false,
      activeCodeHint: result.data.code.slice(0, 3),
      activeCodeExpiresAt: result.data.expiresAt
    }
    refs.status.textContent = 'Код создан. Отправьте команду в Telegram-группе персонала.'
    showToast('Код создан')
    renderStatus()
  }

  const sendTestMessage = async () => {
    hideError()
    isTesting = true
    refs.status.textContent = 'Отправляем тестовое сообщение…'
    renderStatus()
    const result = await venueSendStaffChatTestMessage(backendUrl, venueId, deps)
    if (disposed) return
    isTesting = false
    if (!result.ok) {
      refs.status.textContent = ''
      showError(result.error)
      renderStatus()
      return
    }
    refs.status.textContent = result.data.message
    showToast(result.data.message)
    renderStatus()
  }

  const openUnlinkConfirm = () => {
    refs.confirmOverlay.hidden = false
  }

  const closeUnlinkConfirm = () => {
    if (!isUnlinking) {
      refs.confirmOverlay.hidden = true
    }
  }

  const unlinkChat = async () => {
    if (!canUnlink) {
      showToast('Недостаточно прав')
      return
    }
    hideError()
    isUnlinking = true
    refs.status.textContent = 'Отвязываем чат…'
    renderStatus()
    const result = await venueUnlinkStaffChat(backendUrl, venueId, deps)
    if (disposed) return
    isUnlinking = false
    if (!result.ok) {
      showError(result.error)
      refs.status.textContent = ''
      renderStatus()
      return
    }
    refs.confirmOverlay.hidden = true
    currentFullCode = null
    currentLinkCommand = null
    currentExpiresAt = null
    currentStatus = {
      ...(currentStatus ?? { venueId, isLinked: false }),
      isLinked: false,
      chatId: null,
      maskedChatId: null,
      linkedAt: null,
      linkedByUserId: null
    }
    refs.status.textContent = 'Чат персонала отвязан.'
    showToast('Чат персонала отвязан.')
    renderStatus()
    void loadStatus()
  }

  const copyCommand = async () => {
    if (!currentLinkCommand) {
      showToast('Сначала сгенерируйте код')
      return
    }
    try {
      await copyText(currentLinkCommand)
      showToast('Команда скопирована')
    } catch (_error) {
      showToast('Не удалось скопировать команду')
    }
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.generateButton, 'click', () => void generateCode()))
  disposables.push(on(refs.refreshButton, 'click', () => void loadStatus()))
  disposables.push(on(refs.testButton, 'click', () => void sendTestMessage()))
  disposables.push(on(refs.unlinkButton, 'click', openUnlinkConfirm))
  disposables.push(on(refs.confirmCancelButton, 'click', closeUnlinkConfirm))
  disposables.push(on(refs.confirmUnlinkButton, 'click', () => void unlinkChat()))
  disposables.push(on(refs.copyButton, 'click', () => void copyCommand()))

  renderStatus()
  void loadStatus()

  return () => {
    disposed = true
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
