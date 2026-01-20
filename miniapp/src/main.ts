import './style.css'
import { renderCatalogScreen } from './screens/catalog'
import { renderVenueMode } from './screens/venue'
import { getTelegramContext } from './shared/telegram'

const root = document.querySelector<HTMLDivElement>('#app')
const backendUrl = import.meta.env.VITE_BACKEND_PUBLIC_URL ?? 'http://localhost:8080'
const isDebug = Boolean(import.meta.env.DEV)
const searchParams = new URLSearchParams(window.location.search)
const screen = searchParams.get('screen')
const mode = searchParams.get('mode')
let dispose: (() => void) | null = null

const telegramContext = getTelegramContext()
telegramContext.webApp?.ready?.()
telegramContext.webApp?.expand?.()

function render() {
  dispose?.()
  if (screen === 'catalog') {
    dispose = renderCatalogScreen({ root, backendUrl, isDebug })
    return
  }
  if (mode === 'venue') {
    dispose = renderVenueMode({ root, backendUrl })
    return
  }
  dispose = renderVenueMode({ root, backendUrl })
}

render()
