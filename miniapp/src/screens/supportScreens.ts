import { append, el, on } from '../shared/ui/dom'

type SupportOpenBotResult = { ok: true } | { ok: false; message: string }

export type GuestSupportScreenOptions = {
  root: HTMLDivElement | null
  hasTableContext: boolean
  onBack: () => void
  onOpenBot: () => SupportOpenBotResult
  onOpenVenueStaffCall: () => void
}

export type VenueSupportScreenOptions = {
  root: HTMLDivElement | null
  onBack: () => void
  onOpenBot: () => SupportOpenBotResult
}

function renderSupportMessage(message: HTMLParagraphElement, result: SupportOpenBotResult) {
  message.hidden = false
  message.textContent = result.ok ? 'Открываем чат с ботом.' : result.message
}

export function renderGuestSupportScreen(options: GuestSupportScreenOptions) {
  const { root, hasTableContext, onBack, onOpenBot, onOpenVenueStaffCall } = options
  if (!root) return () => undefined

  const wrapper = el('div', { className: 'venue-settings' })
  const card = el('section', { className: 'card' })
  const title = el('h2', { text: 'Поддержка' })
  const body = el('p', {
    text:
      'Если возникла проблема с заказом, меню, оплатой или QR — обратитесь к персоналу заведения или напишите в поддержку платформы.'
  })
  const tableHint = el('p', {
    className: 'venue-order-sub',
    text: hasTableContext
      ? 'Срочный вопрос по текущему столу быстрее решить через кнопку «Вызвать персонал» на экране заведения.'
      : 'Если вы уже за столом, откройте заведение по QR и используйте кнопку «Вызвать персонал» для срочных вопросов.'
  })
  const platformHint = el('p', {
    className: 'venue-order-sub',
    text: 'Технические вопросы по Mini App и доступу можно описать в Telegram-боте.'
  })
  const message = el('p', { className: 'staff-message', text: '' })
  message.hidden = true

  const actions = el('div', { className: 'venue-inline-actions' })
  const staffButton = hasTableContext
    ? (el('button', { className: 'button-secondary', text: 'К вызову персонала' }) as HTMLButtonElement)
    : null
  const botButton = el('button', { className: 'button-secondary', text: 'Открыть чат с ботом' }) as HTMLButtonElement
  const backButton = el('button', { text: hasTableContext ? 'К заведению' : 'К каталогу' }) as HTMLButtonElement
  append(actions, staffButton, botButton, backButton)
  append(card, title, body, tableHint, platformHint, message, actions)
  append(wrapper, card)
  root.replaceChildren(wrapper)

  const disposables = [
    staffButton ? on(staffButton, 'click', onOpenVenueStaffCall) : null,
    on(botButton, 'click', () => renderSupportMessage(message, onOpenBot())),
    on(backButton, 'click', onBack)
  ].filter((dispose): dispose is () => void => Boolean(dispose))

  return () => {
    disposables.forEach((dispose) => dispose())
  }
}

export function renderVenueSupportScreen(options: VenueSupportScreenOptions) {
  const { root, onBack, onOpenBot } = options
  if (!root) return () => undefined

  const wrapper = el('div', { className: 'venue-settings' })
  const card = el('section', { className: 'card' })
  const title = el('h2', { text: 'Поддержка' })
  const body = el('p', {
    text: 'По настройке заведения, меню, QR, заказам и подписке обратитесь в поддержку платформы.'
  })
  const operationsHint = el('p', {
    className: 'venue-order-sub',
    text:
      'Критичные операционные вопросы решайте через Telegram-бот или владельца платформы. Mini App не создаёт тикеты автоматически.'
  })
  const noTicketHint = el('p', {
    className: 'venue-order-sub',
    text: 'В этом разделе нет номеров тикетов, статусов или обещаний автоматической обработки обращений.'
  })
  const message = el('p', { className: 'staff-message', text: '' })
  message.hidden = true

  const actions = el('div', { className: 'venue-inline-actions' })
  const botButton = el('button', { className: 'button-secondary', text: 'Открыть чат с ботом' }) as HTMLButtonElement
  const backButton = el('button', { text: 'К обзору' }) as HTMLButtonElement
  append(actions, botButton, backButton)
  append(card, title, body, operationsHint, noTicketHint, message, actions)
  append(wrapper, card)
  root.replaceChildren(wrapper)

  const disposables = [
    on(botButton, 'click', () => renderSupportMessage(message, onOpenBot())),
    on(backButton, 'click', onBack)
  ]

  return () => {
    disposables.forEach((dispose) => dispose())
  }
}
