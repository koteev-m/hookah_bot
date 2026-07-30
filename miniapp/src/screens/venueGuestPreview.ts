import type { VenueAccessDto } from '../shared/api/venueDtos'
import { append, el, on } from '../shared/ui/dom'
import { renderGuestVenueScreen } from './guestVenue'

type VenueGuestPreviewScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: Pick<VenueAccessDto, 'role'>
  origin: 'settings' | 'venue'
  onBack: () => void
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
    text: ''
  })
  helper.hidden = true
  const banner = el('p', {
    className: 'venue-preview-banner',
    text: ''
  })
  banner.hidden = true
  const availability = el('p', {
    className: 'status venue-preview-availability',
    text: ''
  })
  availability.hidden = true
  const previewRoot = el('div', { className: 'venue-guest-preview-content' }) as HTMLDivElement
  const returnButton = el('button', {
    className: 'button-secondary venue-preview-return',
    text: options.origin === 'settings' ? 'Вернуться к настройкам' : 'Вернуться в кабинет'
  }) as HTMLButtonElement
  const disposeReturn = on(returnButton, 'click', () => options.onBack())
  append(screen, heading, helper, banner, availability, previewRoot, returnButton)
  root.replaceChildren(screen)

  const disposePreview = renderGuestVenueScreen({
    root: previewRoot,
    backendUrl,
    isDebug,
    venueId,
    readOnlyPreview: true,
    onPreviewLoaded: (preview) => {
      const isPublished = preview.mode === 'PUBLISHED_PUBLIC'
      banner.textContent = isPublished ? 'Опубликовано' : 'Черновик'
      banner.dataset.mode = isPublished ? 'published' : 'draft'
      banner.hidden = false
      helper.textContent = isPublished
        ? 'Так карточку сейчас видит гость.'
        : 'Гости пока не видят эту карточку. Это закрытый предпросмотр сохранённой версии.'
      helper.hidden = false
      const safeAvailabilityLabel = preview.venueAvailabilityLabel?.trim()
      if (!isPublished && safeAvailabilityLabel) {
        availability.textContent = safeAvailabilityLabel
        availability.hidden = false
      } else {
        availability.textContent = ''
        availability.hidden = true
      }
    }
  })
  return () => {
    disposePreview()
    disposeReturn()
  }
}
