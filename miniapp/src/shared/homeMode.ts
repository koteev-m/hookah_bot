import { clearVenueBrowse } from './state/venueBrowseContext'
import { tableTokenQueryParamKeys } from './validation/tableToken'

export function openHomeMode(mode: 'guest' | 'venue' | 'platform', ownerVenueId?: number) {
  const url = new URL(window.location.href)
  url.searchParams.set('mode', 'home')
  url.searchParams.set('entry', mode)
  // Back from a linked cabinet resumes the Guest's own browsing entry.
  if (ownerVenueId === undefined) clearVenueBrowse()
  url.searchParams.set('venue_browse', 'off')
  url.searchParams.delete('venue_entry')
  url.searchParams.set('visit', 'restore')
  url.searchParams.delete('from')
  url.searchParams.delete('venueId')
  if (mode === 'venue' && ownerVenueId !== undefined) {
    url.searchParams.set('venueId', String(ownerVenueId))
    url.searchParams.set('from', 'venue-application')
  }
  url.searchParams.delete('screen')
  for (const key of [...tableTokenQueryParamKeys, 'tableSessionId', 'table_session_id']) url.searchParams.delete(key)
  url.hash = mode === 'venue' && ownerVenueId !== undefined ? '#/dashboard' : ''
  // A fresh bootstrap re-reads roles; no cached/client role grants access.
  window.location.assign(url.href)
}

export function returnToGuestApplication() {
  const url = new URL(window.location.pathname, window.location.origin)
  url.search = 'mode=home&entry=guest&visit=restore'
  url.hash = '#/account/venue-application'
  window.location.replace(url.href)
}
