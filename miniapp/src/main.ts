import './style.css'

const root = document.querySelector<HTMLDivElement>('#app')
const backendUrl = import.meta.env.VITE_BACKEND_PUBLIC_URL ?? 'http://localhost:8080'

function getTelegramContext() {
  const tg = (window as any).Telegram?.WebApp
  const initData = tg?.initData || ''
  const startParam = tg?.initDataUnsafe?.start_param || tg?.initDataUnsafe?.startParam || ''
  return { initDataLength: initData.length, startParam }
}

async function pingBackend() {
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

function render() {
  if (!root) return
  const { initDataLength, startParam } = getTelegramContext()
  root.innerHTML = `
    <main class="container">
      <header>
        <h1>Mini App stub</h1>
        <p>Dev preview for Hookah platform.</p>
      </header>
      <section class="info">
        <p><strong>initData length:</strong> ${initDataLength}</p>
        <p><strong>tgWebAppStartParam:</strong> ${startParam || '—'}</p>
        <p><strong>Current time:</strong> <span id="now"></span></p>
      </section>
      <section>
        <button id="ping-btn">Ping backend /health</button>
        <p id="backend-status" class="status">Idle</p>
      </section>
      <section>
        <p>Backend URL: ${backendUrl}</p>
      </section>
    </main>
  `

  const pingButton = document.querySelector<HTMLButtonElement>('#ping-btn')
  pingButton?.addEventListener('click', pingBackend)
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

render()
startClock()
