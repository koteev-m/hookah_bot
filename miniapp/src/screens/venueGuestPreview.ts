import type { VenueAccessDto } from '../shared/api/venueDtos'
import { append, el } from '../shared/ui/dom'
import { renderGuestVenueScreen } from './guestVenue'

type VenueGuestPreviewScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: Pick<VenueAccessDto, 'role'>
}

export function renderVenueGuestPreviewScreen(options: VenueGuestPreviewScreenOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined

  if (access.role !== 'OWNER' && access.role !== 'MANAGER') {
    const denied = el('section', { className: 'card' })
    append(
      denied,
      el('h2', { text: 'Недостаточно прав' }),
      el('p', { text: 'У вас нет доступа к этому разделу.' })
    )
    root.replaceChildren(denied)
    return () => undefined
  }

  const screen = el('section', { className: 'venue-guest-preview' })
  const heading = el('h2', { text: 'Предпросмотр для гостя' })
  const helper = el('p', {
    className: 'app-subtitle',
    text: 'Только текущее опубликованное состояние. Предпросмотр доступен без действий гостя.'
  })
  const previewRoot = el('div', { className: 'venue-guest-preview-content' }) as HTMLDivElement
  append(screen, heading, helper, previewRoot)
  root.replaceChildren(screen)

  return renderGuestVenueScreen({
    root: previewRoot,
    backendUrl,
    isDebug,
    venueId,
    readOnlyPreview: true
  })
}
