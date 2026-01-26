import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { venueCreateStaffChatLinkCode, venueGetMe } from '../shared/api/venueApi'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { isDebugEnabled } from '../shared/debug'
import { parsePositiveInt } from '../shared/parse'
import { getTelegramContext } from '../shared/telegram'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { append, el, on } from '../shared/ui/dom'

type VenueScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
}

type VenueRefs = {
  accessError: HTMLDivElement
  accessErrorTitle: HTMLHeadingElement
  accessErrorMessage: HTMLParagraphElement
  accessErrorActions: HTMLDivElement
  accessErrorDebug: HTMLParagraphElement
  startParam: HTMLSpanElement
  now: HTMLSpanElement
  pingButton: HTMLButtonElement
  backendStatus: HTMLParagraphElement
  linkSection: HTMLElement
  venueInput: HTMLInputElement
  linkStatus: HTMLParagraphElement
  linkResult: HTMLDivElement
  linkCode: HTMLSpanElement
  linkCountdown: HTMLSpanElement
  copyButton: HTMLButtonElement
  generateButton: HTMLButtonElement
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

function buildVenueDom(
  root: HTMLDivElement,
  initDataLength: number,
  startParam: string,
  userId: number | null,
  backendUrl: string
): VenueRefs {
  const main = el('main', { className: 'container' })
  const header = el('header')
  append(
    header,
    el('h1', { text: 'Hookah Mini App' }),
    el('p', { text: 'Режим заведения: привязка чата персонала.' })
  )

  const info = el('section', { className: 'info' })
  const initDataLine = el('p')
  append(
    initDataLine,
    el('strong', { text: 'initData length:' }),
    document.createTextNode(` ${initDataLength}`)
  )
  const startParamLine = el('p')
  const startParamSpan = el('span', { id: 'venue-start-param' })
  append(
    startParamLine,
    el('strong', { text: 'tgWebAppStartParam:' }),
    document.createTextNode(' '),
    startParamSpan
  )
  const userIdLine = el('p')
  append(
    userIdLine,
    el('strong', { text: 'Telegram user id:' }),
    document.createTextNode(` ${userId ?? '—'}`)
  )
  const nowLine = el('p')
  const nowSpan = el('span', { id: 'now' })
  append(nowLine, el('strong', { text: 'Текущее время:' }), document.createTextNode(' '), nowSpan)
  append(info, initDataLine, startParamLine, userIdLine, nowLine)

  const accessError = el('div', { className: 'error-card' }) as HTMLDivElement
  const accessErrorTitle = el('h3')
  const accessErrorMessage = el('p')
  const accessErrorActions = el('div', { className: 'error-actions' })
  const accessErrorDebug = el('p', { className: 'auth-debug' })
  append(accessError, accessErrorTitle, accessErrorMessage, accessErrorActions, accessErrorDebug)
  accessError.hidden = true

  const pingSection = el('section')
  const pingButton = el('button', { id: 'ping-btn', text: 'Ping backend /health' })
  const backendStatus = el('p', { id: 'backend-status', className: 'status', text: 'Idle' })
  append(pingSection, pingButton, backendStatus)

  const linkSection = el('section', { className: 'link-card' }) as HTMLElement
  const linkTitle = el('h2', { text: 'Привязать чат персонала' })
  const venueLabel = el('label')
  venueLabel.htmlFor = 'venue-id'
  venueLabel.textContent = 'ID заведения'
  const venueInput = el('input', { id: 'venue-id' }) as HTMLInputElement
  venueInput.type = 'number'
  venueInput.min = '1'
  venueInput.step = '1'
  venueInput.placeholder = 'Введите ID'
  const linkStatus = el('p', { id: 'link-status', className: 'status', text: 'Сгенерируйте код, чтобы связать чат.' })
  const linkResult = el('div', { id: 'link-result', className: 'link-result' })
  linkResult.hidden = true
  const linkCode = el('strong', { id: 'link-code' })
  const linkCountdown = el('span', { id: 'link-countdown' })
  const linkCodeLine = el('p')
  append(linkCodeLine, document.createTextNode('Код: '), linkCode)
  const linkCountdownLine = el('p')
  append(linkCountdownLine, document.createTextNode('Истекает через: '), linkCountdown)
  const copyButton = el('button', { id: 'copy-link-btn', text: 'Скопировать /link <код>' })
  append(linkResult, linkCodeLine, linkCountdownLine, copyButton)
  const generateButton = el('button', { id: 'generate-link-btn', text: 'Сгенерировать новый код' })
  append(linkSection, linkTitle, venueLabel, venueInput, linkStatus, linkResult, generateButton)

  const backendInfo = el('section')
  append(backendInfo, el('p', { text: `Backend URL: ${backendUrl}` }))

  append(main, header, info, accessError, pingSection, linkSection, backendInfo)
  root.replaceChildren(main)

  startParamSpan.textContent = startParam || '—'

  return {
    accessError,
    accessErrorTitle,
    accessErrorMessage,
    accessErrorActions,
    accessErrorDebug,
    startParam: startParamSpan,
    now: nowSpan,
    pingButton,
    backendStatus,
    linkSection,
    venueInput,
    linkStatus,
    linkResult,
    linkCode,
    linkCountdown,
    copyButton,
    generateButton
  }
}

export function renderVenueMode(options: VenueScreenOptions) {
  const { root, backendUrl } = options
  if (!root) return () => undefined
  const telegramContext = getTelegramContext()
  const isDebug = isDebugEnabled()
  const deps = buildApiDeps(isDebug)
  const initDataLength = telegramContext.initData?.length ?? 0
  const startParam = telegramContext.startParam ?? ''
  const userId = telegramContext.telegramUserId
  const defaultVenueId = parsePositiveInt(startParam)
  const refs = buildVenueDom(root, initDataLength, startParam, userId, backendUrl)
  refs.venueInput.value = defaultVenueId ? String(defaultVenueId) : ''

  let disposed = false
  let accessAbort: AbortController | null = null
  let accessByVenueId = new Map<number, VenueAccessDto>()
  let countdownInterval: number | null = null
  let clockInterval: number | null = null
  let generating = false
  let linkAbort: AbortController | null = null
  const disposables: Array<() => void> = []

  const renderAccessError = (error: ApiErrorInfo) => {
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.accessErrorTitle.textContent = presentation.title
    refs.accessErrorMessage.textContent = presentation.message
    refs.accessErrorDebug.textContent = presentation.debugLine ?? ''
    const actions: ApiErrorAction[] = presentation.actions.length
      ? presentation.actions
      : [{ label: 'Перезагрузить', kind: 'primary', onClick: () => window.location.reload() }]
    renderErrorActions(refs.accessErrorActions, actions)
    refs.accessError.hidden = false
    refs.linkSection.hidden = true
  }

  const clearAccessError = () => {
    refs.accessError.hidden = true
    refs.linkSection.hidden = false
    refs.accessErrorActions.replaceChildren()
    refs.accessErrorDebug.textContent = ''
  }

  const stopCountdown = () => {
    if (countdownInterval) {
      window.clearInterval(countdownInterval)
      countdownInterval = null
    }
  }

  const resetLinkUiForVenueChange = () => {
    stopCountdown()
    refs.linkResult.hidden = true
    refs.linkCode.textContent = ''
    refs.linkCountdown.textContent = ''
    refs.linkStatus.textContent = 'Сгенерируйте код, чтобы связать чат.'
  }

  const startCountdown = (expiresAt: string) => {
    stopCountdown()
    const target = new Date(expiresAt).getTime()
    const update = () => {
      const diff = Math.max(0, target - Date.now())
      const seconds = Math.floor(diff / 1000)
      const minutes = Math.floor(seconds / 60)
      const remainSeconds = seconds % 60
      refs.linkCountdown.textContent = `${minutes}м ${remainSeconds}с`
    }
    update()
    countdownInterval = window.setInterval(update, 1000)
  }

  const startClock = () => {
    const update = () => {
      refs.now.textContent = new Date().toLocaleString()
    }
    update()
    clockInterval = window.setInterval(update, 1000)
  }

  const updateAccessUi = () => {
    const venueId = parsePositiveInt(refs.venueInput.value)
    const access = venueId ? accessByVenueId.get(venueId) ?? null : null
    const canLink = access?.permissions.includes('STAFF_CHAT_LINK') ?? false
    if (!access) {
      refs.linkStatus.textContent = venueId
        ? 'Нет доступа к выбранному заведению.'
        : 'Укажите ID заведения.'
      refs.generateButton.disabled = true
      refs.linkResult.hidden = true
      return
    }
    if (!canLink) {
      refs.linkStatus.textContent = 'Недостаточно прав для привязки чата.'
      refs.generateButton.disabled = true
      refs.linkResult.hidden = true
      return
    }
    refs.linkStatus.textContent = 'Сгенерируйте код, чтобы связать чат.'
    refs.generateButton.disabled = generating
  }

  const loadVenueAccess = async () => {
    accessAbort?.abort()
    const controller = new AbortController()
    accessAbort = controller
    const result = await venueGetMe(backendUrl, deps, controller.signal)
    if (disposed || controller.signal.aborted || accessAbort !== controller) {
      if (accessAbort === controller) {
        accessAbort = null
      }
      return
    }
    accessAbort = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) {
        return
      }
      renderAccessError(result.error)
      return
    }
    accessByVenueId = new Map(result.data.venues.map((venue) => [venue.venueId, venue]))
    if (!accessByVenueId.size) {
      renderAccessError({
        status: 403,
        code: ApiErrorCodes.FORBIDDEN,
        message: 'Нет доступа'
      })
      return
    }
    if (!defaultVenueId && accessByVenueId.size === 1) {
      const onlyVenueId = accessByVenueId.keys().next().value as number
      refs.venueInput.value = String(onlyVenueId)
    }
    clearAccessError()
    updateAccessUi()
  }

  const pingBackend = async () => {
    refs.backendStatus.textContent = 'Pinging backend...'
    try {
      const response = await fetch(`${backendUrl}/health`)
      if (!response.ok) {
        throw new Error(`Unexpected status ${response.status}`)
      }
      const data = await response.json()
      if (disposed) return // Skip UI updates after screen dispose.
      refs.backendStatus.textContent = `Backend OK: ${JSON.stringify(data)}`
    } catch (error) {
      if (disposed) return // Skip UI updates after screen dispose.
      refs.backendStatus.textContent = `Backend error: ${(error as Error).message}`
    }
  }

  const generateLinkCode = async () => {
    if (generating) {
      return
    }
    const venueId = parsePositiveInt(refs.venueInput.value)
    if (!venueId) {
      refs.linkStatus.textContent = 'Укажите корректный ID заведения.'
      refs.linkResult.hidden = true
      refs.venueInput.focus()
      refs.venueInput.select()
      return
    }
    const access = accessByVenueId.get(venueId)
    if (!access) {
      refs.linkStatus.textContent = 'Нет доступа к выбранному заведению.'
      refs.linkResult.hidden = true
      return
    }
    if (!access.permissions.includes('STAFF_CHAT_LINK')) {
      refs.linkStatus.textContent = 'Недостаточно прав для привязки чата.'
      refs.linkResult.hidden = true
      return
    }
    resetLinkUiForVenueChange()
    generating = true
    refs.generateButton.disabled = true
    refs.venueInput.disabled = true
    refs.linkStatus.textContent = 'Генерация кода...'
    try {
      if (linkAbort) {
        linkAbort.abort()
      }
      const controller = new AbortController()
      linkAbort = controller
      const result = await venueCreateStaffChatLinkCode(backendUrl, venueId, deps, controller.signal)
      if (disposed || controller.signal.aborted || linkAbort !== controller) {
        if (linkAbort === controller) {
          linkAbort = null
        }
        return
      }
      linkAbort = null
      if (!result.ok) {
        if (result.error.code === REQUEST_ABORTED_CODE) {
          return
        }
        const presentation = presentApiError(result.error, { isDebug, scope: 'venue' })
        refs.linkStatus.textContent = `${presentation.title}. ${presentation.message}`
        refs.linkResult.hidden = true
        return
      }
      refs.linkCode.textContent = result.data.code
      refs.linkStatus.textContent = 'Код сгенерирован. Отправьте /link <код> в чате персонала.'
      refs.linkResult.hidden = false
      startCountdown(result.data.expiresAt)
    } finally {
      if (disposed) return
      generating = false
      refs.generateButton.disabled = false
      refs.venueInput.disabled = false
      updateAccessUi()
    }
  }

  const setupCopyButton = () => {
    disposables.push(
      on(refs.copyButton, 'click', async () => {
        const code = refs.linkCode.textContent?.trim()
        if (!code) return
        try {
          await navigator.clipboard.writeText(`/link ${code}`)
          if (disposed) return
          refs.linkStatus.textContent = 'Скопировано: /link <код>'
        } catch (error) {
          if (disposed) return
          refs.linkStatus.textContent = 'Не удалось скопировать. Скопируйте вручную.'
        }
      })
    )
  }

  disposables.push(
    on(refs.pingButton, 'click', () => void pingBackend()),
    on(refs.generateButton, 'click', () => void generateLinkCode()),
    on(refs.venueInput, 'input', () => {
      if (disposed || generating) {
        return
      }
      resetLinkUiForVenueChange()
      updateAccessUi()
    }),
    on(refs.venueInput, 'keydown', (event) => {
      if (event.key !== 'Enter') {
        return
      }
      event.preventDefault()
      void generateLinkCode()
    })
  )

  setupCopyButton()
  startClock()
  void loadVenueAccess()

  return () => {
    disposed = true
    accessAbort?.abort()
    linkAbort?.abort()
    if (clockInterval) {
      window.clearInterval(clockInterval)
    }
    if (countdownInterval) {
      window.clearInterval(countdownInterval)
    }
    disposables.forEach((dispose) => dispose())
  }
}
