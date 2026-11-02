import { getTelegramContext } from '../telegram'

let dismissed = false
let selectedVenueId: number | null = null
const key = () => `hookah_browse_venue:user:${getTelegramContext().telegramUserId ?? 'unknown'}`

export function getVenueBrowsePayload(): string | null {
  if (dismissed) return null
  if (new URLSearchParams(window.location.search).get('venue_browse') === 'off') return null
  const ctx = getTelegramContext()
  if (ctx.tableTokenAutoResolve) {
    selectedVenueId = null
    try { localStorage.removeItem(key()) } catch { /* explicit QR supersedes a browsing choice */ }
    return null
  }
  const explicit = new URLSearchParams(window.location.search).get('venue_entry')
    ?? (ctx.startParam?.startsWith('venue_') && ctx.startParam.length <= 19 ? ctx.startParam : null)
  if (explicit !== null) return explicit
  try { return localStorage.getItem(key()) } catch { return null }
}

export function getVenueBrowseId(): number | null { return dismissed ? null : selectedVenueId }

export function rememberVenueBrowse(payload: string, venueId: number): void {
  dismissed = false
  selectedVenueId = venueId
  try { localStorage.setItem(key(), payload) } catch { /* browsing still works without storage */ }
}

export function clearVenueBrowse(): void {
  dismissed = true
  selectedVenueId = null
  try { localStorage.removeItem(key()) } catch { /* optional persistence */ }
  const url = new URL(window.location.href)
  url.searchParams.set('venue_browse', 'off')
  url.searchParams.delete('venue_entry')
  for (const name of ['startapp', 'start_param', 'tgWebAppStartParam']) {
    if (url.searchParams.get(name)?.startsWith('venue_')) url.searchParams.delete(name)
  }
  window.history.replaceState(window.history.state, '', url)
}
