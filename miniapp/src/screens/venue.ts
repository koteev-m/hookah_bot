import { getTelegramContext } from '../shared/telegram'

type LinkCodePayload = {
  code: string
  expiresAt: string
  ttlSeconds: number
}

type VenueScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
}

let countdownTimer: number | null = null

function startCountdown(expiresAt: string) {
  const countdownElement = document.querySelector<HTMLSpanElement>('#link-countdown')
  if (!countdownElement) return
  if (countdownTimer) {
    window.clearInterval(countdownTimer)
  }
  const target = new Date(expiresAt).getTime()
  const update = () => {
    const diff = Math.max(0, target - Date.now())
    const seconds = Math.floor(diff / 1000)
    const minutes = Math.floor(seconds / 60)
    const remainSeconds = seconds % 60
    countdownElement.textContent = `${minutes}м ${remainSeconds}с`
  }
  update()
  countdownTimer = window.setInterval(update, 1000)
}

async function pingBackend(backendUrl: string) {
  const statusElement = document.querySelector<HTMLParagraphElement>('#backend-status')
  if (!statusElement) return
  statusElement.textContent = 'Pinging backend...'
  try {
    const response = await fetch(`${backendUrl}/health`)
    if (!response.ok) {
      throw new Error(`Unexpected status ${response.status}`)
    }
    const data = await response.json()
    statusElement.textContent = `Backend OK: ${JSON.stringify(data)}`
  } catch (error) {
    statusElement.textContent = `Backend error: ${(error as Error).message}`
  }
}

async function generateLinkCode(backendUrl: string) {
  const venueInput = document.querySelector<HTMLInputElement>('#venue-id')
  const status = document.querySelector<HTMLParagraphElement>('#link-status')
  const codeElement = document.querySelector<HTMLSpanElement>('#link-code')
  const container = document.querySelector<HTMLDivElement>('#link-result')
  if (!venueInput || !status || !codeElement || !container) return
  const venueId = Number(venueInput.value)
  const ctx = getTelegramContext()
  if (!venueId || Number.isNaN(venueId)) {
    status.textContent = 'Укажите корректный ID заведения.'
    container.hidden = true
    return
  }
  if (!ctx.userId) {
    status.textContent = 'Не удалось определить Telegram ID. Откройте мини-приложение из Telegram.'
    container.hidden = true
    return
  }
  status.textContent = 'Генерация кода...'
  container.hidden = true
  try {
    const response = await fetch(`${backendUrl}/api/venue/${venueId}/staff-chat/link-code`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Telegram-User-Id': ctx.userId.toString()
      },
      body: JSON.stringify({ userId: ctx.userId })
    })
    if (!response.ok) {
      const errorText = await response.text()
      throw new Error(`Ошибка ${response.status}: ${errorText || 'неизвестно'}`)
    }
    const payload = (await response.json()) as LinkCodePayload
    codeElement.textContent = payload.code
    status.textContent = 'Код сгенерирован. Отправьте /link <код> в чате персонала.'
    container.hidden = false
    startCountdown(payload.expiresAt)
  } catch (error) {
    status.textContent = `Не удалось сгенерировать код: ${(error as Error).message}`
    container.hidden = true
  }
}

function setupCopyButton() {
  const copyButton = document.querySelector<HTMLButtonElement>('#copy-link-btn')
  const codeElement = document.querySelector<HTMLSpanElement>('#link-code')
  const status = document.querySelector<HTMLParagraphElement>('#link-status')
  if (!copyButton || !codeElement || !status) return
  copyButton.addEventListener('click', async () => {
    const code = codeElement.textContent?.trim()
    if (!code) return
    try {
      await navigator.clipboard.writeText(`/link ${code}`)
      status.textContent = 'Скопировано: /link <код>'
    } catch (error) {
      status.textContent = 'Не удалось скопировать. Скопируйте вручную.'
    }
  })
}

function startClock() {
  const nowElement = document.querySelector<HTMLSpanElement>('#now')
  if (!nowElement) return
  const update = () => {
    nowElement.textContent = new Date().toLocaleString()
  }
  update()
  setInterval(update, 1000)
}

export function renderVenueMode(options: VenueScreenOptions) {
  const { root, backendUrl } = options
  if (!root) return
  const { initDataLength, startParam, userId } = getTelegramContext()
  const defaultVenueId = Number(startParam)
  root.innerHTML = `
    <main class="container">
      <header>
        <h1>Hookah Mini App</h1>
        <p>Режим заведения: привязка чата персонала.</p>
      </header>
      <section class="info">
        <p><strong>initData length:</strong> ${initDataLength}</p>
        <p><strong>tgWebAppStartParam:</strong> <span id="venue-start-param"></span></p>
        <p><strong>Telegram user id:</strong> ${userId ?? '—'}</p>
        <p><strong>Текущее время:</strong> <span id="now"></span></p>
      </section>
      <section>
        <button id="ping-btn">Ping backend /health</button>
        <p id="backend-status" class="status">Idle</p>
      </section>
      <section class="link-card">
        <h2>Привязать чат персонала</h2>
        <label for="venue-id">ID заведения</label>
        <input id="venue-id" type="number" placeholder="Введите ID" />
        <p id="link-status" class="status">Сгенерируйте код, чтобы связать чат.</p>
        <div id="link-result" class="link-result" hidden>
          <p>Код: <strong id="link-code"></strong></p>
          <p>Истекает через: <span id="link-countdown"></span></p>
          <button id="copy-link-btn">Скопировать /link &lt;код&gt;</button>
        </div>
        <button id="generate-link-btn">Сгенерировать новый код</button>
      </section>
      <section>
        <p>Backend URL: ${backendUrl}</p>
      </section>
    </main>
  `

  const startParamElement = document.querySelector<HTMLSpanElement>('#venue-start-param')
  if (startParamElement) {
    startParamElement.textContent = startParam || '—'
  }
  const venueInput = document.querySelector<HTMLInputElement>('#venue-id')
  if (venueInput) {
    venueInput.value = Number.isFinite(defaultVenueId) ? String(defaultVenueId) : ''
  }

  document
    .querySelector<HTMLButtonElement>('#ping-btn')
    ?.addEventListener('click', () => void pingBackend(backendUrl))
  document
    .querySelector<HTMLButtonElement>('#generate-link-btn')
    ?.addEventListener('click', () => void generateLinkCode(backendUrl))
  setupCopyButton()
  startClock()
}
