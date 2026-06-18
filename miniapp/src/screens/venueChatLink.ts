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
  codeTitle: HTMLHeadingElement
  codeValue: HTMLSpanElement
  codeExpires: HTMLSpanElement
  commandValue: HTMLInputElement
  copyButton: HTMLButtonElement
  regenerateButton: HTMLButtonElement
  generateButton: HTMLButtonElement
  refreshButton: HTMLButtonElement
  testButton: HTMLButtonElement
  unlinkButton: HTMLButtonElement
  status: HTMLParagraphElement
  confirmOverlay: HTMLDivElement
  confirmCancelButton: HTMLButtonElement
  confirmUnlinkButton: HTMLButtonElement
  regenerateOverlay: HTMLDivElement
  regenerateCancelButton: HTMLButtonElement
  regenerateConfirmButton: HTMLButtonElement
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
  const codeTitle = el('h3', { text: 'Код привязки готов' })
  const codeValue = el('span', { className: 'venue-link-code-value', text: '—' })
  const codeExpires = el('span', { className: 'venue-link-code-expiry', text: '—' })
  const commandValue = document.createElement('input')
  commandValue.type = 'text'
  commandValue.readOnly = true
  commandValue.value = ''
  commandValue.setAttribute('aria-label', 'Команда для привязки чата')
  const copyButton = el('button', { text: 'Скопировать команду' }) as HTMLButtonElement
  const regenerateButton = el('button', {
    className: 'button-secondary button-tertiary',
    text: 'Создать новый код'
  }) as HTMLButtonElement
  const codeRow = el('p', { className: 'venue-link-code-raw' })
  append(codeRow, document.createTextNode('Код: '), codeValue)
  append(
    codeSection,
    codeTitle,
    codeExpires,
    el('p', { className: 'venue-link-code-label', text: 'Команда для группы:' }),
    commandValue,
    copyButton,
    codeRow,
    regenerateButton
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

  const regenerateOverlay = el('div', { className: 'venue-modal-overlay' }) as HTMLDivElement
  regenerateOverlay.hidden = true
  const regenerateDialog = el('section', { className: 'venue-modal card' })
  regenerateDialog.setAttribute('role', 'dialog')
  regenerateDialog.setAttribute('aria-modal', 'true')
  regenerateDialog.setAttribute('aria-labelledby', 'venue-chat-regenerate-title')
  const regenerateTitle = el('h3', { id: 'venue-chat-regenerate-title', text: 'Создать новый код?' })
  const regenerateBody = el('p', {
    text: 'Текущий код перестанет работать. Используйте новый код для привязки Telegram-группы.'
  })
  const regenerateActions = el('div', { className: 'button-row' })
  const regenerateConfirmButton = el('button', { text: 'Создать новый код' }) as HTMLButtonElement
  const regenerateCancelButton = el('button', { className: 'button-secondary', text: 'Отмена' }) as HTMLButtonElement
  append(regenerateActions, regenerateConfirmButton, regenerateCancelButton)
  append(regenerateDialog, regenerateTitle, regenerateBody, regenerateActions)
  regenerateOverlay.appendChild(regenerateDialog)

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, summaryCard, instructionsCard, codeSection, actions, status, error, confirmOverlay, regenerateOverlay)
  root.replaceChildren(wrapper)

  return {
    statusTitle,
    statusDescription,
    chatMeta,
    instructionsCard,
    codeSection,
    codeTitle,
    codeValue,
    codeExpires,
    commandValue,
    copyButton,
    regenerateButton,
    generateButton,
    refreshButton,
    testButton,
    unlinkButton,
    status,
    confirmOverlay,
    confirmCancelButton,
    confirmUnlinkButton,
    regenerateOverlay,
    regenerateCancelButton,
    regenerateConfirmButton,
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

function isExpired(value?: string | null) {
  if (!value) return false
  return new Date(value).getTime() <= Date.now()
}

function formatExpiry(value?: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  const now = new Date()
  const time = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  if (
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate()
  ) {
    return `Действует до ${time}`
  }
  const day = date.toLocaleDateString([], { day: '2-digit', month: '2-digit', year: 'numeric' })
  return `Действует до ${day}, ${time}`
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
  let isRegenerating = false

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
    const hasGeneratedCode = Boolean(currentFullCode && currentLinkCommand)
    const generatedCodeExpired = hasGeneratedCode && isExpired(currentExpiresAt)
    const hasActiveCodeHint = Boolean(status?.activeCodeHint) && !isExpired(status?.activeCodeExpiresAt)
    const hasUsableCode = hasGeneratedCode || hasActiveCodeHint

    refs.instructionsCard.hidden = linked
    refs.testButton.hidden = !linked
    refs.unlinkButton.hidden = !linked || !canUnlink
    refs.generateButton.hidden = linked || !canLink || hasUsableCode || generatedCodeExpired
    refs.refreshButton.hidden = false
    refs.generateButton.disabled = isGenerating || !canLink
    refs.testButton.disabled = isTesting
    refs.unlinkButton.disabled = isUnlinking
    refs.confirmUnlinkButton.disabled = isUnlinking
    refs.regenerateConfirmButton.disabled = isGenerating || isRegenerating
    refs.regenerateButton.disabled = isGenerating || isRegenerating

    if (!canLink) {
      refs.statusTitle.textContent = 'Нет доступа к управлению чатом персонала'
      refs.statusDescription.textContent = 'Для подключения и проверки чата нужны права владельца или менеджера.'
      refs.chatMeta.textContent = ''
      refs.codeSection.hidden = true
      refs.refreshButton.hidden = true
      refs.regenerateOverlay.hidden = true
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
      refs.refreshButton.hidden = true
      return
    }

    refs.statusTitle.textContent = 'Чат персонала не подключён'
    refs.statusDescription.textContent =
      'Подключите Telegram-группу, чтобы получать уведомления о заказах, бронях, вызовах и продлениях.'
    refs.chatMeta.textContent = ''
    refs.refreshButton.hidden = !hasUsableCode && !generatedCodeExpired

    refs.codeSection.hidden = !hasUsableCode && !generatedCodeExpired
    refs.regenerateButton.hidden = !hasUsableCode && !generatedCodeExpired
    refs.copyButton.className = ''
    refs.regenerateButton.className = 'button-secondary button-tertiary'
    refs.regenerateButton.textContent = 'Создать новый код'

    if (generatedCodeExpired) {
      refs.codeTitle.textContent = 'Срок действия кода истёк'
      refs.codeExpires.textContent = 'Сгенерируйте новый код, чтобы привязать Telegram-группу.'
      refs.codeValue.textContent = currentFullCode ?? '—'
      refs.commandValue.value = 'Код больше не действует.'
      refs.commandValue.disabled = true
      refs.copyButton.hidden = true
      refs.copyButton.disabled = true
      refs.regenerateButton.className = ''
      refs.regenerateButton.textContent = 'Сгенерировать новый код'
      return
    }

    if (hasGeneratedCode) {
      refs.codeTitle.textContent = 'Код привязки готов'
      refs.codeValue.textContent = currentFullCode ?? '—'
      refs.codeExpires.textContent = formatExpiry(currentExpiresAt)
      refs.commandValue.value = currentLinkCommand ?? ''
      refs.commandValue.disabled = false
      refs.copyButton.hidden = false
      refs.copyButton.disabled = !currentLinkCommand
      return
    }
    if (hasActiveCodeHint) {
      refs.codeTitle.textContent = 'Код привязки уже создан'
      refs.codeValue.textContent = `начинается на ${status?.activeCodeHint ?? '—'}`
      refs.codeExpires.textContent = formatExpiry(status?.activeCodeExpiresAt)
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
    refs.status.textContent = ''
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

  const openRegenerateConfirm = () => {
    if (isExpired(currentExpiresAt)) {
      void generateCode()
      return
    }
    refs.regenerateOverlay.hidden = false
  }

  const closeRegenerateConfirm = () => {
    if (!isGenerating && !isRegenerating) {
      refs.regenerateOverlay.hidden = true
    }
  }

  const regenerateCode = async () => {
    isRegenerating = true
    renderStatus()
    refs.regenerateOverlay.hidden = true
    await generateCode()
    isRegenerating = false
    renderStatus()
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
  disposables.push(on(refs.regenerateButton, 'click', openRegenerateConfirm))
  disposables.push(on(refs.regenerateCancelButton, 'click', closeRegenerateConfirm))
  disposables.push(on(refs.regenerateConfirmButton, 'click', () => void regenerateCode()))

  renderStatus()
  void loadStatus()

  return () => {
    disposed = true
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
