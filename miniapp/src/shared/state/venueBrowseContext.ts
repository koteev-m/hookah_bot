import { getTelegramContext } from '../telegram'

let dismissed = false
let selectedVenueId: number | null = null
let selectedPayload: string | null = null
const key = () => `hookah_browse_venue:user:${getTelegramContext().telegramUserId ?? 'unknown'}`

export function getExplicitVenueBrowsePayload(): string | null {
  if (dismissed || new URLSearchParams(window.location.search).get('venue_browse') === 'off') return null
  const ctx = getTelegramContext()
  if (ctx.tableTokenAutoResolve) return null
  return new URLSearchParams(window.location.search).get('venue_entry')
    ?? (ctx.startParam?.startsWith('venue_') && ctx.startParam.length <= 19 ? ctx.startParam : null)
}

export function getVenueBrowsePayload(): string | null {
  if (dismissed) return null
  if (new URLSearchParams(window.location.search).get('venue_browse') === 'off') return null
  const ctx = getTelegramContext()
  if (ctx.tableTokenAutoResolve) {
    selectedVenueId = null
    selectedPayload = null
    try { sessionStorage.removeItem(key()) } catch { /* explicit QR supersedes a browsing choice */ }
    return null
  }
  const explicit = getExplicitVenueBrowsePayload()
  if (explicit !== null) return explicit
  const saved = savedBrowsePayload()
  return saved?.startsWith('venue_') ? saved : null
}

function savedBrowsePayload(): string | null {
  if (dismissed) return null
  if (selectedPayload !== null) return selectedPayload
  if (new URLSearchParams(window.location.search).get('venue_browse') === 'off') return null
  try { return sessionStorage.getItem(key()) } catch { return null }
}

export function isPersonalVenueBrowse(venueId: number | null): boolean {
  return venueId !== null && savedBrowsePayload()?.startsWith('venue_') === true && getVenueBrowseId() === venueId
}

export function getVenueBrowseId(): number | null {
  if (dismissed) return null
  if (selectedVenueId !== null) return selectedVenueId
  const payload = savedBrowsePayload()
  if (!payload) return null
  const catalogId = /^catalog:([1-9][0-9]*)$/.exec(payload)?.[1]
  const personalId = /^venue_([1-9a-z][0-9a-z]{0,12})$/.exec(payload)?.[1]
  const id = catalogId ? Number(catalogId) : personalId ? Number.parseInt(personalId, 36) : null
  return id !== null && Number.isSafeInteger(id) && id > 0 ? id : null
}

export function rememberVenueBrowse(payload: string, venueId: number): void {
  dismissed = false
  selectedVenueId = venueId
  selectedPayload = payload
  try { sessionStorage.setItem(key(), payload) } catch { /* browsing still works without storage */ }
}

export function clearVenueBrowse(): void {
  dismissed = true
  selectedPayload = null
  selectedVenueId = null
  try { sessionStorage.removeItem(key()) } catch { /* optional persistence */ }
  const url = new URL(window.location.href)
  url.searchParams.set('venue_browse', 'off')
  url.searchParams.delete('venue_entry')
  for (const name of ['startapp', 'start_param', 'tgWebAppStartParam']) {
    if (url.searchParams.get(name)?.startsWith('venue_')) url.searchParams.delete(name)
  }
  window.history.replaceState(window.history.state, '', url)
}
