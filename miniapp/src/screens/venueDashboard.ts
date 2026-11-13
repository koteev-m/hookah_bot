import type { VenueAccessDto } from '../shared/api/venueDtos'
import { venueAttentionHref, type VenueMonitor } from '../shared/venueEventMonitor'
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
  root.replaceChildren(...(access.venueStatus === 'DRAFT' ? [buildVenueHome(options), summary] : [summary, buildVenueHome(options)]))
  if (!['DRAFT', 'PUBLISHED'].includes(access.venueStatus?.toUpperCase() ?? '')) return () => undefined
  const refresh = el('button', { className: 'button-secondary', text: 'Обновить обзор' })
  refresh.onclick = () => monitor?.refresh()
  root.append(refresh)
  if (!monitor) { summary.append(el('p', { text: 'Оперативные данные пока недоступны.' })); return () => undefined }
  let extrasOpen = false
  let renderedSnapshotKey: string | undefined
  return monitor.subscribe(snapshot => {
    const key = JSON.stringify(snapshot ? { ...snapshot, refreshedAt: '' } : null)
    if (key === renderedSnapshotKey) return
    renderedSnapshotKey = key
    const focused = document.activeElement instanceof HTMLElement && summary.contains(document.activeElement)
      ? document.activeElement : null
    const focusedKey = focused?.closest('a')?.dataset.dashboardKey
    const focusedHref = focused?.closest('a')?.getAttribute('href')
    const focusedExtras = focused?.tagName === 'SUMMARY'
    summary.replaceChildren()
    let cardCount = 0
    const extras = el('details', { className: 'venue-dashboard-extras' }); extras.open = extrasOpen
    extras.append(el('summary', { text: 'Остальные показатели заведения' }))
    extras.ontoggle = () => { extrasOpen = extras.open }
    const card = (route: Parameters<typeof canAccessRoute>[0], title: string, value: string) => {
      if (!canAccessRoute(route)) return
      const link = el('a', { className: 'card venue-dashboard-card' }); link.href = `#/${route}`; link.dataset.dashboardKey = title
      append(link, el('strong', { text: title }), el('span', { text: value })); (cardCount++ < 3 ? summary : extras).append(link)
    }
    const count = (value: number | null | undefined) => value == null ? 'Нет подтверждённых данных' : String(value)
    card('orders', 'Новые заказы и дозаказы', snapshot ? `${snapshot.attention.filter(e => e.orderId != null).length}${snapshot.attentionComplete ? '' : ' в неполном списке — мониторинг ограничен'}` : '—')
    card('calls', 'Новые вызовы:', snapshot ? `${snapshot.attention.filter(e => e.callId != null).length}${snapshot.attentionComplete ? '' : ' в неполном списке — мониторинг ограничен'}` : '—')
    card('bookings', 'Бронирования, ожидающие ответа', count(snapshot?.bookingsPending))
    card('orders', 'Активные заказы', count(snapshot?.activeOrders))
    card('calls', 'Вызовы в работе', count(snapshot?.callsInWork))
    if (snapshot?.bookingsUpcoming != null) card('bookings', 'Подтверждённые будущие брони · отдельно от срочных задач', String(snapshot.bookingsUpcoming))
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
    if (snapshot?.attention.length) {
      const preview = el('section', { className: 'card venue-task-preview' }); preview.append(el('h3', { text: 'Ближайшие задачи' }))
      snapshot.attention.slice(0, 3).forEach(task => {
        const link = el('a', { text: task.kind === 'BOOKING_PENDING' ? 'Бронь ожидает решения' : task.kind === 'BOOKING_MESSAGE' ? 'Сообщение по брони' : task.kind === 'ORDER_SEND_UNKNOWN' ? 'Новые позиции · счёт не определён' : task.kind === 'BATCH_ADDED' ? 'Дозаказ · новые позиции' : task.callId ? 'Вызов гостя' : 'Первый заказ' })
        link.dataset.dashboardKey = task.id
        const href = venueAttentionHref(task)
        if (href) link.href = href; else link.setAttribute('aria-disabled', 'true')
        preview.append(link)
      }); summary.prepend(preview)
    }
    if (extras.children.length > 1) summary.append(extras)
    if (focusedHref) Array.from(summary.querySelectorAll<HTMLAnchorElement>('a')).find(link => focusedKey ? link.dataset.dashboardKey === focusedKey : link.getAttribute('href') === focusedHref)?.focus({ preventScroll: true })
    else if (focusedExtras) extras.querySelector('summary')?.focus({ preventScroll: true })
    extras.append(el('p', { className: 'venue-alert-help' , text: 'Учётная смена, стоимость выполненных заказов и внешние оплаты имеют разные основания. Денежные показатели открываются в соответствующих разделах.' }))
  })
}
