import './style.css'
import { mountGuestApp } from './screens/guestApp'
import { renderVenueMode } from './screens/venue'
import { getBackendBaseUrl } from './shared/api/backend'
import { mountAuthGate } from './shared/authGate'
import { getTelegramContext } from './shared/telegram'

const root = document.querySelector<HTMLDivElement>('#app')
const backendUrl = getBackendBaseUrl()
const searchParams = new URLSearchParams(window.location.search)
const mode = searchParams.get('mode')
const screen = searchParams.get('screen')
let dispose: (() => void) | null = null

const telegramContext = getTelegramContext()
try {
  telegramContext.webApp?.ready?.()
} catch {
  // ignore WebApp readiness errors
}
try {
  telegramContext.webApp?.expand?.()
} catch {
  // ignore WebApp expand errors
}

if (mode === 'venue' && screen !== 'catalog') {
  dispose = renderVenueMode({ root, backendUrl })
} else {
  dispose = mountAuthGate({
    root,
    onReady: () => {
      dispose?.()
      dispose = mountGuestApp({ root })
    }
  })
}
