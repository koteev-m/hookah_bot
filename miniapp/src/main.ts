import './style.css'
import { renderCatalogScreen } from './screens/catalog'
import { renderVenueMode } from './screens/venue'

const root = document.querySelector<HTMLDivElement>('#app')
const backendUrl = import.meta.env.VITE_BACKEND_PUBLIC_URL ?? 'http://localhost:8080'
const isDebug = Boolean(import.meta.env.DEV)
const searchParams = new URLSearchParams(window.location.search)
const screen = searchParams.get('screen')
const mode = searchParams.get('mode')

function render() {
  if (screen === 'catalog') {
    renderCatalogScreen({ root, backendUrl, isDebug })
    return
  }
  if (mode === 'venue') {
    renderVenueMode({ root, backendUrl })
    return
  }
  renderVenueMode({ root, backendUrl })
}

render()
