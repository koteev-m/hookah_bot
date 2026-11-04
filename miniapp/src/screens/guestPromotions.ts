import type { GuestVenuePromotionDto } from '../shared/api/guestDtos'
import { el } from '../shared/ui/dom'

function formatPromotionDateTime(value: string, timezone?: string | null): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  try {
    return date.toLocaleString('ru-RU', {
      ...(timezone ? { timeZone: timezone } : {}),
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    })
  } catch {
    return date.toLocaleString('ru-RU')
  }
}

function promotionPeriodLabel(promotion: GuestVenuePromotionDto, timezone?: string | null): string | null {
  if (promotion.startsAt && promotion.endsAt) {
    return `с ${formatPromotionDateTime(promotion.startsAt, timezone)} по ${formatPromotionDateTime(promotion.endsAt, timezone)}`
  }
  if (promotion.endsAt) {
    return `Действует до ${formatPromotionDateTime(promotion.endsAt, timezone)}`
  }
  if (promotion.startsAt) {
    return `Действует с ${formatPromotionDateTime(promotion.startsAt, timezone)}`
  }
  return null
}

export function renderGuestPromotion(promotion: GuestVenuePromotionDto, timezone?: string | null) {
  const card = el('article', { className: 'guest-venue-promotion' })
  card.appendChild(el('h5', { text: promotion.title }))
  card.appendChild(el('p', { text: promotion.description }))
  if (promotion.templateType === 'HAPPY_HOURS_PERCENT') {
    card.appendChild(
      el('p', {
        className: 'guest-venue-promotion-terms',
        text: 'Скидка рассчитывается автоматически по актуальным ценам при оформлении заказа.'
      })
    )
  }
  const period = promotionPeriodLabel(promotion, timezone)
  if (period) {
    card.appendChild(el('p', { className: 'guest-venue-promotion-period', text: period }))
  }
  if (promotion.terms?.trim()) {
    card.appendChild(el('p', { className: 'guest-venue-promotion-terms', text: `Условия: ${promotion.terms.trim()}` }))
  }
  return card
}
