import type { VenueAccessDto } from '../shared/api/venueDtos'
import { append, el, on } from '../shared/ui/dom'
import { renderGuestVenueScreen } from './guestVenue'

type VenueGuestPreviewScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: Pick<VenueAccessDto, 'role' | 'venueStatus'>
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

  const venueStatus = access.venueStatus?.trim().toUpperCase()
  const isDraftPreview = venueStatus === 'DRAFT'
  const screen = el('section', { className: 'venue-guest-preview' })
  const heading = el('h2', {
    text: isDraftPreview ? 'Предпросмотр карточки' : 'Предпросмотр для гостя'
  })
  const helper = el('p', {
    className: 'app-subtitle',
    text: isDraftPreview
      ? 'Только сохранённые данные. Предпросмотр доступен без действий гостя.'
      : 'Так опубликованная карточка выглядит для гостя.'
  })
  const returnButton = isDraftPreview
    ? null
    : (el('button', {
        className: 'button-secondary venue-preview-return',
        text: 'Вернуться в кабинет'
      }) as HTMLButtonElement)
  const disposeReturn = returnButton
    ? on(returnButton, 'click', () => options.onBack())
    : () => undefined
  if (venueStatus !== 'PUBLISHED' && venueStatus !== 'DRAFT') {
    const unavailable = el('section', { className: 'card venue-preview-unavailable' })
    append(
      unavailable,
      el('h3', { text: 'Предпросмотр недоступен' }),
      el('p', { text: 'Заведение сейчас недоступно для гостевого просмотра.' })
    )
    append(screen, heading, helper, unavailable)
    if (returnButton) {
      screen.appendChild(returnButton)
    }
    root.replaceChildren(screen)
    return disposeReturn
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
  if (returnButton) {
    screen.appendChild(returnButton)
  }
  root.replaceChildren(screen)

  const disposePreview = renderGuestVenueScreen({
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
  return () => {
    disposePreview()
    disposeReturn()
  }
}
