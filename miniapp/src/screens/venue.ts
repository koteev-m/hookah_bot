import { getTelegramContext } from '../shared/telegram'
import { append, el, on } from '../shared/ui/dom'
import { parsePositiveInt } from '../shared/parse'

type LinkCodePayload = {
  code: string
  expiresAt: string
  ttlSeconds: number
}

type VenueScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
}

type VenueRefs = {
  startParam: HTMLSpanElement
  now: HTMLSpanElement
  pingButton: HTMLButtonElement
  backendStatus: HTMLParagraphElement
  venueInput: HTMLInputElement
  linkStatus: HTMLParagraphElement
  linkResult: HTMLDivElement
  linkCode: HTMLSpanElement
  linkCountdown: HTMLSpanElement
  copyButton: HTMLButtonElement
  generateButton: HTMLButtonElement
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

  const pingSection = el('section')
  const pingButton = el('button', { id: 'ping-btn', text: 'Ping backend /health' })
  const backendStatus = el('p', { id: 'backend-status', className: 'status', text: 'Idle' })
  append(pingSection, pingButton, backendStatus)

  const linkSection = el('section', { className: 'link-card' })
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

  append(main, header, info, pingSection, linkSection, backendInfo)
  root.replaceChildren(main)

  startParamSpan.textContent = startParam || '—'

  return {
    startParam: startParamSpan,
    now: nowSpan,
    pingButton,
    backendStatus,
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
  const initDataLength = telegramContext.initData?.length ?? 0
  const startParam = telegramContext.startParam ?? ''
  const userId = telegramContext.telegramUserId
  const defaultVenueId = parsePositiveInt(startParam)
  const refs = buildVenueDom(root, initDataLength, startParam, userId, backendUrl)
  refs.venueInput.value = defaultVenueId ? String(defaultVenueId) : ''

  let disposed = false
  let countdownInterval: number | null = null
  let clockInterval: number | null = null
  let generating = false
  const disposables: Array<() => void> = []

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
    const ctx = getTelegramContext()
    const telegramUserId = ctx.telegramUserId
    if (!venueId) {
      refs.linkStatus.textContent = 'Укажите корректный ID заведения.'
      refs.linkResult.hidden = true
      refs.venueInput.focus()
      refs.venueInput.select()
      return
    }
    if (!telegramUserId) {
      refs.linkStatus.textContent =
        'Не удалось определить Telegram ID. Откройте мини-приложение из Telegram.'
      refs.linkResult.hidden = true
      return
    }
    resetLinkUiForVenueChange()
    generating = true
    refs.generateButton.disabled = true
    refs.venueInput.disabled = true
    refs.linkStatus.textContent = 'Генерация кода...'
    try {
      const response = await fetch(`${backendUrl}/api/venue/${venueId}/staff-chat/link-code`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Telegram-User-Id': telegramUserId.toString()
        },
        body: JSON.stringify({ userId: telegramUserId })
      })
      if (!response.ok) {
        const errorText = await response.text()
        throw new Error(`Ошибка ${response.status}: ${errorText || 'неизвестно'}`)
      }
      const payload = (await response.json()) as LinkCodePayload
      if (disposed) return // Skip UI updates after screen dispose.
      refs.linkCode.textContent = payload.code
      refs.linkStatus.textContent = 'Код сгенерирован. Отправьте /link <код> в чате персонала.'
      refs.linkResult.hidden = false
      startCountdown(payload.expiresAt)
    } catch (error) {
      if (disposed) return // Skip UI updates after screen dispose.
      refs.linkStatus.textContent = `Не удалось сгенерировать код: ${(error as Error).message}`
      refs.linkResult.hidden = true
    } finally {
      if (disposed) return
      generating = false
      refs.generateButton.disabled = false
      refs.venueInput.disabled = false
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

  return () => {
    disposed = true
    if (clockInterval) {
      window.clearInterval(clockInterval)
    }
    if (countdownInterval) {
      window.clearInterval(countdownInterval)
    }
    disposables.forEach((dispose) => dispose())
  }
}
