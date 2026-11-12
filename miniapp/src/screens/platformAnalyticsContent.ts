import type { PlatformAnalyticsSnapshot } from '../shared/api/platformAnalyticsApi'
import type { KpiBucket, KpiValue } from '../shared/api/venueStatsApi'
import { append, el } from '../shared/ui/dom'
import { exactCount, exactMoney, formatCalendarDate, formatDuration, validCalendarDate } from '../shared/ui/venueStatsFormat'
import { calculation } from './venueStatsContent'

type Definition = { title: string; explanation: string; basis: string; unit?: string; sampleSize?: true }
const snapshot = 'Состояние на время обновления. Выбранный период его не ограничивает.'
const period = 'По выбранным датам UTC, от начала первой даты до начала дня после последней. Факты после времени обновления не входят.'
const definitions: Record<string, Definition> = {
  'venues.total': { title: 'Всего заведений', basis: 'SNAPSHOT_AS_OF', explanation: `Все сохранённые заведения, включая архивные и удалённые. ${snapshot}` },
  'venues.created': { title: 'Новые заведения', basis: 'VENUE_CREATED_AT', explanation: `Число заведений по дате регистрации. Публикация и оплата не требуются. ${period}` },
  'venues.guestAvailable': { title: 'Доступны гостям', basis: 'SNAPSHOT_AVAILABILITY_RESOLVER', explanation: `Сервер проверяет публикацию и состояние подписки. Публикация сама по себе не гарантирует доступ. Отсутствующие подписки указаны как неизвестные. ${snapshot}` },
  'venues.waav': { sampleSize: true, title: 'Активные заведения за 7 дней', basis: 'ROLLING_168_HOURS_BATCH_TRANSITIONS', explanation: 'Заведения, в которых за последние 168 часов был подтверждён переход хотя бы одной части заказа в принятие или выдачу. Каждое заведение считается один раз. От начала окна включительно до времени обновления исключительно. Выбранный период не меняет это окно; время создания заказа не используется.' },
  'venues.staffChatLinked': { title: 'Привязан чат сотрудников', basis: 'SNAPSHOT_CONFIRMED_LINK_NOT_DELIVERY', explanation: `Число заведений с сохранённой привязкой чата. Наличие привязки не подтверждает доставку сообщений. ${snapshot}` },
  'subscriptions.paidThroughCurrent': { title: 'Оплаченный срок действует', basis: 'SNAPSHOT_INVOICE_PERIOD_END_INCLUSIVE_DATE', explanation: 'Число заведений, у которых максимальная дата окончания счёта, отмеченного оплаченным, не раньше текущей даты UTC. Дата окончания включается. Отсутствие оплаченного периода остаётся неизвестным. Это не банковская сверка.' },
  'subscriptions.effectivePaidThroughCurrent': { title: 'Срок с учётом продления действует', basis: 'SNAPSHOT_INVOICE_PERIOD_END_INCLUSIVE_DATE', explanation: 'Число заведений с действующим сроком по серверным правилам учёта подписки: оплаченный срок с учётом предоставленного продления. Дата окончания включается. Продления не суммируются на экране и не считаются оплатой.' },
  'subscriptions.withCourtesy': { title: 'Есть предоставленные продления', basis: 'SNAPSHOT_COURTESY_NOT_PAYMENT', explanation: `Заведения с сохранёнными предоставленными продлениями, в том числе уже истёкшими. Предоставленные дни продления, а не полученные деньги. ${snapshot}` },
  'invoices.open': { title: 'Открытые счета', basis: 'SNAPSHOT_INVOICE_STATE', explanation: `Счета в состояниях «Открыт» и «Просрочен». Просроченные включены в это число. ${snapshot}` },
  'invoices.overdue': { title: 'Просроченные счета', basis: 'SNAPSHOT_INVOICE_STATE', explanation: `Открытые или просроченные счета, срок оплаты которых уже прошёл. Это часть открытых счетов; числа не складываются. ${snapshot}` },
  'invoices.paid': { title: 'Счета, отмеченные оплаченными', basis: 'SNAPSHOT_INVOICE_STATE', explanation: `Счета с текущим статусом «Оплачен». Статус не доказывает банковское поступление. ${snapshot}` },
  'invoices.manualConfirmed': { title: 'Ручные подтверждения за период', basis: 'INVOICE_PAID_AT_MATCHED_MANUAL_CONFIRMATION', explanation: `Счета, отмеченные оплаченными в выбранный период, с подтверждением сотрудника, совпадающим по счёту, провайдеру, сумме и валюте. Повторные записи не умножают число счетов. ${period}` },
  'invoices.paidWithoutMatchingEvidence': { title: 'Оплаченные без совпадающего подтверждения', basis: 'SNAPSHOT_RECONCILIATION_WARNING', explanation: `Число отмеченных оплаченными счетов без успешного подтверждения с совпадающими счётом, провайдером, суммой и валютой. Требует проверки, не является суммой поступлений. ${snapshot}` },
  'invoices.inconsistentSuccessfulEvents': { title: 'Несогласованные подтверждения', basis: 'SNAPSHOT_RECONCILIATION_WARNING', explanation: `Число успешных записей, где счёт не отмечен оплаченным либо не совпадают провайдер, сумма или валюта. Это записи подтверждений, не количество оплат. ${snapshot}` },
  'invoices.multipleMatchingSuccesses': { title: 'Счета с повторными подтверждениями', basis: 'SNAPSHOT_RECONCILIATION_WARNING', explanation: `Число счетов с несколькими совпадающими успешными подтверждениями. Повторы не складываются как поступления. Предупреждения могут относиться к одним и тем же счетам; их нельзя суммировать. ${snapshot}` },
  'onboarding.total': { title: 'Всего заявок', basis: 'SNAPSHOT_AS_OF', explanation: `Все сохранённые заявки на подключение. ${snapshot}` },
  'onboarding.created': { title: 'Новые заявки', basis: 'REQUEST_CREATED_AT', explanation: `Заявки на подключение по времени создания. Заявка не означает подписку или оплату. ${period}` },
  'onboarding.linked': { title: 'Заявки с привязанным заведением', basis: 'SNAPSHOT_CONFIRMED_VENUE_LINK', explanation: `Заявки, ссылка из которых ведёт к существующему заведению. Считаются заявки, не уникальные платящие клиенты. ${snapshot}` },
  'support.total': { title: 'Всего обращений поддержки', basis: 'SNAPSHOT_SUPPORT_TICKET', explanation: `Только обращения в поддержку. Переписки по бронированию и обычные чаты с заведением исключены. ${snapshot}` },
  'support.active': { title: 'Активные обращения', basis: 'SNAPSHOT_SUPPORT_TICKET', explanation: `Обращения в состояниях «Открыто», «Новое», «В работе» и «Ожидает пользователя». ${snapshot}` },
  'support.created': { title: 'Новые обращения', basis: 'TICKET_CREATED_AT', explanation: `Только обращения поддержки, созданные в выбранные даты. ${period}` },
  'support.oldestActiveAge': { sampleSize: true, title: 'Возраст самого старого активного обращения', basis: 'SNAPSHOT_SUPPORT_TICKET', unit: 'MILLISECONDS', explanation: `Время от создания самого старого активного обращения до обновления данных. Это не время решения. При пустой очереди недоступно. ${snapshot}` },
  'support.ttfr': { sampleSize: true, title: 'Среднее время первого ответа', basis: 'TICKET_CREATED_AT_FIRST_RECORDED_HUMAN_REPLY', unit: 'MILLISECONDS', explanation: `Среднее время от создания обращения до первого сохранённого человеческого ответа платформы или заведения с указанным автором. Системные и гостевые сообщения не входят. Выборка — обращения, созданные в выбранный период; ответ учитывается до времени обновления. Неподтверждённые ответы отмечены отдельно.` },
  'support.ttfrUnknown': { title: 'Без подтверждённого первого ответа', basis: 'TICKET_CREATED_AT_NO_HUMAN_REPLY', explanation: `Созданные в выбранный период обращения без сохранённого подходящего человеческого ответа. Это ограничение данных, а не оценка работы поддержки. ${period}` }
}
const periodKeys = new Set(['venues.created', 'invoices.manualConfirmed', 'onboarding.created', 'support.created', 'support.ttfr', 'support.ttfrUnknown'])
const labels: Record<string, string> = { DRAFT: 'Черновики', PUBLISHED: 'Опубликованы', HIDDEN: 'Скрыты', PAUSED: 'На паузе',
  SUSPENDED: 'Приостановлены', SUSPENDED_BY_PLATFORM: 'Приостановлены платформой', ARCHIVED: 'Архивные', DELETED: 'Удалены',
  ACTIVE: 'Активные', TRIAL: 'Пробные', PAST_DUE: 'Просроченные', CANCELED: 'Отменены', UNKNOWN: 'Неизвестно', MISSING: 'Подписка отсутствует',
  PENDING: 'Ожидают решения', CANCELLED: 'Отменены', OPEN: 'Открытые', PAID: 'Отмечены оплаченными', VOID: 'Аннулированы', NEW: 'Новые', IN_PROGRESS: 'В работе', APPROVED: 'Одобрены', REJECTED: 'Отклонены', LINKED: 'Привязаны', CLOSED: 'Закрыты' }
export const platformSections = [['venues', 'Заведения'], ['subscriptions', 'Подписки'], ['invoices', 'Счета и оплаты'],
  ['onboarding', 'Подключение заведений'], ['support', 'Поддержка']] as const

function warning(kpi?: KpiValue): string {
  if (kpi?.reason === 'LEGACY_HISTORY_UNKNOWN') return 'Часть старых операций не содержит точного времени. Полная историческая активность неизвестна.'
  if (kpi?.reason === 'SUBSCRIPTION_MISSING') return 'Для части заведений нет записи подписки; доступность нельзя подтвердить полностью.'
  if (kpi?.reason === 'NO_RECORDED_PAID_PERIOD') return 'Для части заведений не сохранён подтверждённый срок. Отсутствие данных не означает нулевой срок.'
  if (kpi?.reason === 'NO_RECORDED_HUMAN_REPLY') return 'Часть обращений не содержит подтверждённого первого ответа.'
  if (kpi?.reason === 'INVALID_CURRENCY') return 'Часть счетов содержит неизвестную валюту и не входит в суммы.'
  return 'Недостаточно данных для полного расчёта.'
}
function card(key: string, kpi?: KpiValue, custom?: Definition, currency?: string): HTMLElement {
  const def = custom ?? definitions[key]
  const root = el('article', { className: 'venue-stats-metric' }); root.dataset.kpi = key
  const compatible = kpi?.basis === def.basis && kpi?.unit === (def.unit ?? 'COUNT')
  const available = compatible && kpi?.availability === 'AVAILABLE' && kpi.coverage === 'COMPLETE_RECORDED_SOURCE' && kpi.value !== null
  const partial = compatible && kpi?.availability === 'PARTIAL' && kpi.coverage === 'PARTIAL'
  const format = (value: string) => currency ? exactMoney(value, currency) : kpi?.unit === 'MILLISECONDS' ? formatDuration(value) : exactCount(value)
  const text = available ? format(kpi.value!) : partial ? 'Частичные данные' : 'Недостаточно данных'
  const value = el('strong', { className: 'stats-value', text }); if (text.length > 20) value.dataset.long = 'true'
  if (available && currency && /^[A-Z]{3}$/.test(currency) && text.endsWith(currency)) {
    value.replaceChildren(document.createTextNode(`${text.slice(0, -currency.length).trimEnd()} `), el('span', { className: 'stats-currency-unit', text: currency }))
  }
  append(root, el('h3', { text: def.title }), el('p', { className: 'stats-basis', text: key === 'venues.waav' ? 'Последние 168 часов' : periodKeys.has(key) ? 'За выбранный период · UTC' : 'На время обновления' }), value)
  if (!available) { root.dataset.coverage = partial ? 'partial' : 'unavailable'; root.append(el('p', { className: 'stats-note', text: warning(kpi) })) }
  if (partial && kpi.observedValue !== null) root.append(el('p', { className: 'stats-observed', text: `По известным данным: ${format(kpi.observedValue)}` }))
  if (available && kpi.value === '0' && kpi.unit === 'COUNT') root.append(el('p', { className: 'stats-note', text: periodKeys.has(key) ? 'За выбранный период событий нет.' : 'Подходящих записей на время обновления нет.' }))
  // KpiModels.kpi defaults samples to 0; only these known definitions use it as sample metadata.
  const showSamples = compatible && def.sampleSize === true
  if (kpi && (showSamples || partial || kpi.unknown > 0)) {
    const samples = showSamples ? `Случаев в расчёте: ${exactCount(kpi.samples)}. ` : ''
    root.append(el('p', { className: 'stats-note', text: `${samples}Без подтверждённых данных: ${exactCount(kpi.unknown)}.` }))
  }
  root.append(calculation(def.explanation)); return root
}
function grid(data: PlatformAnalyticsSnapshot, keys: string[]) {
  const root = el('div', { className: 'venue-stats-grid' }); keys.forEach(key => root.append(card(key, data.metrics[key]))); return root
}
function distribution(title: string, rows: KpiBucket[] | undefined, dates = false) {
  const block = el('div', { className: 'stats-distribution' }); block.append(el('h3', { text: title }))
  if (!rows) { block.append(el('p', { text: 'Недостаточно данных для распределения.' })); return block }
  if (!rows.length) { block.append(el('p', { text: 'Подходящих записей нет.' })); return block }
  const max = rows.reduce((max, row) => Number.isSafeInteger(row.count) ? Math.max(max, row.count) : max, 1)
  const list = el('ul'); list.setAttribute('aria-label', `${title}: количество`)
  for (const row of rows) {
    const item = el('li'); const line = el('div', { className: 'stats-distribution-label' })
    append(line, el('span', { text: dates && validCalendarDate(row.key) ? formatCalendarDate(row.key) : labels[row.key.toUpperCase()] ?? 'Другой или неизвестный статус' }), el('strong', { text: exactCount(row.count) }))
    const track = el('div', { className: 'stats-bar-track' }); track.setAttribute('aria-hidden', 'true')
    const bar = el('div', { className: 'stats-bar' }); bar.style.width = `${Number.isSafeInteger(row.count) ? row.count / max * 100 : 0}%`
    track.append(bar); append(item, line, track); list.append(item)
  }
  append(block, el('p', { className: 'stats-note', text: 'Количество на время обновления; это распределение, а не изменение за период.' }), list)
  block.append(calculation(dates ? 'Сервер группирует заведения по подтверждённой дате окончания срока, включая последний день. Неизвестные сроки показаны отдельно. Длина полосы сравнивает число заведений с самой большой группой; выбранный период не применяется.' : 'Сервер группирует сохранённые записи по текущему состоянию на время обновления. Это не переходы за выбранный период. Подписи показывают количество записей; длина полосы сравнивает группу с самой большой.'))
  return block
}
function note(text: string, warning = false) { return el('p', { className: warning ? 'stats-warning' : 'stats-note', text }) }

export function renderPlatformAnalyticsContent(data: PlatformAnalyticsSnapshot, navigate: (hash: string) => void) {
  const root = el('div', { className: 'stats-sections' })
  if (data.section === 'all') {
    const overview = el('section', { className: 'card platform-analytics-overview' }); overview.setAttribute('aria-label', 'Обзор платформы')
    append(overview, el('h2', { text: 'Главное' }), grid(data, ['venues.total', 'venues.waav', 'invoices.overdue', 'support.active']))
    root.append(overview)
  }
  for (const [key, title] of platformSections) {
    if (data.section !== 'all' && data.section !== key) continue
    const block = el('details', { className: 'card stats-section platform-analytics-section', id: `analytics-${key}` }); block.open = data.section !== 'all'
    const summary = el('summary'); summary.append(el('h2', { text: title })); block.append(summary)
    if (key === 'venues') {
      append(block, note('Регистрация, публикация, доступ гостям и подтверждённая активность — разные факты.'),
        grid(data, ['venues.total', 'venues.created', 'venues.guestAvailable', 'venues.waav']),
        distribution('Состояния заведений', data.breakdowns['venues.lifecycle']),
        note('Распределение причин недоступности гостям пока недоступно. Оно не вычисляется из статуса публикации.'))
    } else if (key === 'subscriptions') {
      append(block, note('Наличие подписки не доказывает оплату. Предоставленные дни продления не являются полученными деньгами.', true),
        distribution('Состояния подписок', data.breakdowns['subscriptions.states']),
        calculation('Количество заведений по текущему состоянию подписки. Отсутствующие записи и неизвестные состояния показаны отдельно. Это текущее состояние, а не переходы за выбранные даты.'),
        grid(data, ['subscriptions.paidThroughCurrent', 'subscriptions.effectivePaidThroughCurrent', 'subscriptions.withCourtesy']))
      const dates = el('details', { className: 'stats-payment-details' }); dates.append(el('summary', { text: 'Распределение дат окончания срока' }),
        note('Оплаченный срок подтверждён действующими правилами учёта подписки. Срок с учётом продления рассчитан сервером. Даты окончания включаются; это не календарь поступлений.'),
        distribution('Оплаченный срок', data.breakdowns['subscriptions.paidThrough'], true),
        distribution('Срок с учётом продления', data.breakdowns['subscriptions.effectivePaidThrough'], true)); block.append(dates)
    } else if (key === 'invoices') {
      append(block, note('Счёт, отмеченный оплаченным, и ручное подтверждение не означают банковскую сверку. Это счета платформы, отдельно от финансовых итогов смен заведений.', true),
        grid(data, ['invoices.open', 'invoices.overdue', 'invoices.paid', 'invoices.manualConfirmed']),
        distribution('Состояния счетов', data.breakdowns['invoices.states']))
      for (const [name, label] of [['open', 'Суммы открытых счетов'], ['overdue', 'Суммы просроченных счетов'], ['paid', 'Суммы счетов, отмеченных оплаченными']]) {
        const currency = el('section', { className: 'stats-currency' }); currency.append(el('h3', { text: label }))
        const coverage = data.metrics[`invoices.${name}.currencyCoverage`]
        if (!coverage || coverage.availability !== 'AVAILABLE') currency.append(note(warning(coverage), true))
        const groups = data.invoiceFinances[name]
        if (!groups?.length) currency.append(note(groups && coverage?.availability === 'AVAILABLE' ? 'В этой группе счетов нет.' : 'Недостаточно данных для сумм.'))
        for (const group of groups ?? []) {
          const money = card('amount', group.metrics.amount, { title: /^[A-Z]{3}$/.test(group.currency) ? group.currency : 'Валюта не определена',
            basis: 'SNAPSHOT_INVOICE_STATE', unit: 'MINOR_UNITS', explanation: `${label} по сохранённой сумме каждого счёта. Валюты не складываются. Просроченные счета входят в открытые. ${snapshot}` }, group.currency)
          money.dataset.currency = /^[A-Z]{3}$/.test(group.currency) ? group.currency : 'unknown'; currency.append(money)
        }
        block.append(currency)
      }
      const warnings = el('details', { className: 'stats-payment-details' }); warnings.open = true
      append(warnings, el('summary', { text: 'Согласованность подтверждений оплаты' }),
        note('Предупреждения требуют проверки записей. Их нельзя складывать как число оплат или сумму поступлений.'),
        grid(data, ['invoices.paidWithoutMatchingEvidence', 'invoices.inconsistentSuccessfulEvents', 'invoices.multipleMatchingSuccesses']))
      append(block, warnings, note('Банковские поступления, прибыль, регулярная месячная выручка и реальные возвраты недоступны: подтверждённого источника нет.'))
    } else if (key === 'onboarding') {
      append(block, grid(data, ['onboarding.total', 'onboarding.created', 'onboarding.linked']),
        distribution('Состояния заявок', data.breakdowns['onboarding.states']), note('Привязка заведения не означает оплату. Воронка «заявка → платящий клиент» не рассчитывается.'))
      // staffChatLinked belongs to the venues section of the backend, never infer it from a list.
      if (data.section === 'all') block.append(grid(data, ['venues.staffChatLinked']))
      else block.append(note('Привязки чатов сотрудников доступны в обзоре всех разделов.'))
      block.append(note('Привязка чата сотрудников подтверждает настройку, а не доставку сообщений.'))
    } else {
      append(block, note('Только обращения поддержки. Переписки по бронированиям и обычные чаты с заведениями исключены.'),
        grid(data, ['support.total', 'support.active', 'support.created', 'support.oldestActiveAge', 'support.ttfr', 'support.ttfrUnknown']),
        note('Системные и гостевые сообщения не считаются ответом поддержки.', true),
        note('Распределение статусов очереди, время решения, повторные открытия, эскалации и оценки поддержки пока недоступны. Возраст обращения не является временем решения.'))
    }
    const target = key === 'support' ? '#/support' : key === 'onboarding' ? '#/applications' : '#/venues'
    const action = el('button', { className: 'button-secondary', text: key === 'support' ? 'Открыть центр поддержки' : key === 'onboarding' ? 'Открыть заявки' : key === 'invoices' || key === 'subscriptions' ? 'К заведениям и их счетам' : 'Открыть список заведений' })
    action.onclick = () => navigate(target)
    append(block, action, note('Откроется существующий раздел без переноса периода и фильтров.')); root.append(block)
  }
  return root
}
