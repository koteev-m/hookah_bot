import type { VenueAccessDto } from '../shared/api/venueDtos'
import { append, el } from '../shared/ui/dom'
import { renderGuestVenueScreen } from './guestVenue'

type VenueGuestPreviewScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: Pick<VenueAccessDto, 'role' | 'venueStatus'>
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

  const venueStatus = access.venueStatus?.trim().toUpperCase()
  const screen = el('section', { className: 'venue-guest-preview' })
  const heading = el('h2', { text: 'Предпросмотр карточки' })
  const helper = el('p', {
    className: 'app-subtitle',
    text: 'Только сохранённые данные. Предпросмотр доступен без действий гостя.'
  })
  if (venueStatus !== 'PUBLISHED' && venueStatus !== 'DRAFT') {
    const unavailable = el('section', { className: 'card venue-preview-unavailable' })
    append(
      unavailable,
      el('h3', { text: 'Предпросмотр недоступен' }),
      el('p', {
        text:
          venueStatus === 'DELETED'
            ? 'Заведение не найдено.'
            : 'Предпросмотр карточки для этого статуса пока недоступен.'
      })
    )
    append(screen, heading, helper, unavailable)
    root.replaceChildren(screen)
    return () => undefined
  }

  const previewMode = venueStatus
  const banner = el('p', {
    className: 'venue-preview-banner',
    text:
      previewMode === 'PUBLISHED'
        ? 'Опубликовано — так карточку видит гость сейчас'
        : 'Черновик. Гости пока не видят эту карточку'
  })
  banner.dataset.mode = previewMode.toLowerCase()
  banner.hidden = true
  const previewRoot = el('div', { className: 'venue-guest-preview-content' }) as HTMLDivElement
  append(screen, heading, helper, banner, previewRoot)
  root.replaceChildren(screen)

  return renderGuestVenueScreen({
    root: previewRoot,
    backendUrl,
    isDebug,
    venueId,
    previewMode,
    onPreviewLoaded: (loadedMode) => {
      if (loadedMode === previewMode) {
        banner.hidden = false
      }
    }
  })
}
