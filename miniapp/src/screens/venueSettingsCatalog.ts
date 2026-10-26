import type { VenueAccessDto } from '../shared/api/venueDtos'
import { append, el } from '../shared/ui/dom'

export const SETTINGS_SECTIONS = ['card', 'hours', 'team', 'booking', 'extension'] as const
export type VenueSettingsSection = typeof SETTINGS_SECTIONS[number]

export function renderVenueSettingsCatalog(root: HTMLElement, access: VenueAccessDto) {
  const card = el('section', { className: 'card venue-settings-catalog' })
  append(card, el('h2', { text: 'Настройки' }))
  const links = el('div', { className: 'venue-settings-links' })
  const can = (permission: string) => access.permissions.includes(permission)
  const items: Array<[string, string, boolean]> = [
    ['Карточка заведения', 'settings/card', true],
    ['Часы работы', 'settings/hours', true],
    ['Меню', 'menu', can('MENU_VIEW')],
    ['Столы и QR', 'tables', can('TABLE_VIEW')],
    ['Команда и доступ', 'settings/team', true],
    ['Бронирование', 'settings/booking', can('BOOKING_MANAGE')],
    ['Продление времени', 'settings/extension', can('SHIFT_EXTENSION_SETTINGS')],
    ['Акции', 'promotions', true]
  ]
  for (const [title, route, allowed] of items) {
    if (!allowed) continue
    const link = el('a', { className: 'venue-owner-action', text: title })
    link.href = `#/${route}`
    links.appendChild(link)
  }
  append(card, links)
  root.replaceChildren(card)
  return () => undefined
}
