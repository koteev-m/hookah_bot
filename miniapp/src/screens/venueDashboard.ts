import type { VenueAccessDto } from '../shared/api/venueDtos'
import type { VenueMonitor } from '../shared/venueEventMonitor'
import { append, el } from '../shared/ui/dom'

export type VenueDashboardOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
  monitor?: VenueMonitor | null
  canAccessRoute: (route: 'hall' | 'settings' | 'menu' | 'tables' | 'staff' | 'guest-preview' | 'orders' | 'bookings' | 'calls' | 'messages' | 'extensions' | 'shifts' | 'chat' | 'accounting' | 'subscription') => boolean
}

function buildVenueHome(options: VenueDashboardOptions): HTMLElement {
  const { access, canAccessRoute } = options
  const status = access.venueStatus?.trim().toUpperCase()
  const operational = status === 'DRAFT' || status === 'PUBLISHED'
  const card = el('section', { className: 'card venue-owner-home' })
  const messages: Record<string, string> = {
    DRAFT: 'Карточка не опубликована и не видна гостям.',
    HIDDEN: 'Заведение скрыто от гостей. Сохранённую карточку можно проверить в настройках.',
    PAUSED: 'Заведение на паузе. Доступность операций определяется текущими ограничениями заведения.',
    SUSPENDED: 'Работа заведения приостановлена. Уточните причину и дальнейшие действия в поддержке.',
    ARCHIVED: 'Заведение в архиве. Доступность восстановления определяется действующими правилами.',
    DELETED: 'Заведение удалено. Доступные действия определяются текущими ограничениями.'
  }
  append(card, el('h2', { text: access.role === 'STAFF' ? 'Моя работа' : 'Работа заведения' }))
  if (status !== 'PUBLISHED') {
    append(card, el('p', { className: 'venue-owner-context', text: messages[status ?? ''] ?? 'Статус публикации не подтверждён. Доступность операций уточните в поддержке.' }))
  }
  if (status === 'DRAFT' && canAccessRoute('settings')) {
    const setup = el('a', { className: 'venue-owner-setup', text: 'Настроить заведение' })
    setup.href = '#/settings'
    card.appendChild(setup)
  }
  const actions = el('div', { className: 'venue-owner-actions' })
  const items: Array<{ route: Parameters<typeof canAccessRoute>[0]; title: string; detail?: string }> = operational
    ? [
        { route: 'orders', title: 'Заказы', detail: 'Очередь заказов' },
        { route: 'accounting', title: 'Учёт смены', detail: 'Продажи и внешние оплаты' },
        { route: 'bookings', title: 'Бронирования' },
        { route: 'calls', title: 'Вызовы гостей' },
        { route: 'messages', title: 'Переписки' },
        { route: 'extensions', title: 'Запросы продления' },
        { route: 'shifts', title: access.role === 'STAFF' ? 'Мои смены' : 'График смен' },
        { route: 'menu', title: 'Стоп-лист и меню' }
      ]
    : []
  items.filter((item) => canAccessRoute(item.route)).forEach((item) => {
    const link = el('a', { className: 'venue-owner-action' })
    link.href = `#/${item.route}`
    if (item.route === 'messages') link.dataset.ownerMessages = 'true'
    append(link, el('strong', { text: item.title }))
    if (item.detail) append(link, el('span', { text: item.detail }))
    actions.appendChild(link)
  })
  card.appendChild(actions)
  return card
}

export function renderVenueDashboardScreen(options: VenueDashboardOptions) {
  const { root, access, monitor, canAccessRoute } = options
  if (!root) return () => undefined
  const summary = el('div', { className: 'venue-dashboard-cards' })
  root.replaceChildren(buildVenueHome(options), summary)
  if (!['DRAFT', 'PUBLISHED'].includes(access.venueStatus?.toUpperCase() ?? '')) return () => undefined
  const refresh = el('button', { className: 'button-secondary', text: 'Обновить обзор' })
  refresh.onclick = () => monitor?.refresh()
  root.append(refresh)
  if (!monitor) { summary.append(el('p', { text: 'Оперативные данные пока недоступны.' })); return () => undefined }
  return monitor.subscribe(snapshot => {
    summary.replaceChildren()
    const card = (route: Parameters<typeof canAccessRoute>[0], title: string, value: string) => {
      if (!canAccessRoute(route)) return
      const link = el('a', { className: 'card venue-dashboard-card' }); link.href = `#/${route}`
      append(link, el('strong', { text: title }), el('span', { text: value })); summary.append(link)
    }
    const count = (value: number | null | undefined) => value == null ? 'Нет подтверждённых данных' : String(value)
    card('orders', 'Новые партии, включая дозаказы', snapshot ? `${snapshot.attention.filter(e => e.orderId != null).length}${snapshot.attentionComplete ? '' : ' в неполном списке — мониторинг ограничен'}` : '—')
    card('orders', 'Активные заказы', count(snapshot?.activeOrders))
    card('calls', 'Новые вызовы:', snapshot ? `${snapshot.attention.filter(e => e.callId != null).length}${snapshot.attentionComplete ? '' : ' в неполном списке — мониторинг ограничен'}` : '—')
    card('calls', 'Вызовы в работе', count(snapshot?.callsInWork))
    card('bookings', 'Бронирования, ожидающие ответа', count(snapshot?.bookingsPending))
    card('hall', 'Посадка за столами', snapshot && snapshot.occupiedTables != null && snapshot.activeTables != null
      ? `Занято: ${snapshot.occupiedTables}. Активных столов: ${snapshot.activeTables}. Физическая посадка, без предположений по заказам.` : 'Нет подтверждённых данных')
    card('accounting', 'Учётная смена', !snapshot ? '—' : snapshot.accountingOpenedAt
      ? `Открыта ${new Date(snapshot.accountingOpenedAt).toLocaleString()} (время устройства)` : 'Нет открытой учётной смены. Первая реальная операция откроет её автоматически.')
    card('menu', 'Стоп-лист', snapshot && snapshot.unavailableItems != null && snapshot.unavailableOptions != null
      ? `Недоступно позиций: ${snapshot.unavailableItems}; вариантов: ${snapshot.unavailableOptions}` : 'Нет подтверждённых данных')
    if (access.permissions.includes('STAFF_CHAT_LINK')) card('chat', 'Чат персонала:', !snapshot || snapshot.staffChatLinked == null ? 'Нет подтверждённых данных' : snapshot.staffChatLinked ? 'привязан' : 'не привязан — откройте настройки чата')
    const venueLabels: Record<string, string> = { DRAFT: 'Черновик', PUBLISHED: 'Опубликовано', HIDDEN: 'Скрыто', PAUSED: 'На паузе', SUSPENDED: 'Приостановлено', ARCHIVED: 'В архиве', DELETED: 'Удалено' }
    const subscriptionLabels: Record<string, string> = { TRIAL: 'Пробный период', ACTIVE: 'Подписка активна', PAST_DUE: 'Оплата просрочена — проверьте подписку', SUSPENDED: 'Подписка приостановлена — проверьте ограничения' }
    if (access.role === 'OWNER') card('subscription', 'Состояние заведения и подписки', snapshot
      ? `${venueLabels[snapshot.venueStatus] ?? snapshot.venueStatus} · ${subscriptionLabels[snapshot.subscriptionStatus ?? ''] ?? 'Подписка не подтверждена'}` : '—')
    summary.append(el('p', { className: 'venue-alert-help', text: 'Учётная смена, стоимость выполненных заказов и внешние оплаты имеют разные основания. Денежные показатели открываются в соответствующих разделах.' }))
  })
}
