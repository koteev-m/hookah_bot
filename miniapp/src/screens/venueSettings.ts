import type { VenueAccessDto } from '../shared/api/venueDtos'
import { append, el, on } from '../shared/ui/dom'

export type VenueSettingsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

export function renderVenueSettingsScreen(options: VenueSettingsOptions) {
  const { root, access } = options
  if (!root) return () => undefined

  const wrapper = el('div', { className: 'venue-settings' })
  const card = el('section', { className: 'card' })
  const title = el('h2', { text: 'Настройки' })
  const text = access.permissions.includes('VENUE_SETTINGS')
    ? 'Настройки заведения пока доступны в Telegram-боте. Чтобы не показывать неработающий экран перед запуском, Mini App временно скрывает этот раздел.'
    : 'У вас нет доступа к настройкам заведения.'
  const description = el('p', { text })
  const hint = access.permissions.includes('VENUE_SETTINGS')
    ? el('p', {
        className: 'venue-order-sub',
        text: 'Откройте раздел заведения в боте, чтобы изменить профиль, описание, график, отзывы и другие параметры.'
      })
    : el('p', { className: 'venue-order-sub', text: 'Обратитесь к владельцу заведения, если нужен доступ.' })
  const backButton = el('button', { className: 'button-secondary', text: 'Вернуться в обзор' }) as HTMLButtonElement

  append(card, title, description, hint, backButton)
  append(wrapper, card)
  root.replaceChildren(wrapper)

  const dispose = on(backButton, 'click', () => {
    window.location.hash = '#/dashboard'
  })

  return () => {
    dispose()
  }
}
