import type { FinancialKpis, KpiBucket, KpiValue, VenueKpiSnapshot } from '../shared/api/venueStatsApi'
import { append, el } from '../shared/ui/dom'
import { exactCount, exactMoney, formatDuration, formatRatio } from '../shared/ui/venueStatsFormat'

type Definition = [key: string, label: string, explanation: string]
const createdOrders: Definition[] = [
  ['orders.created', 'Созданные заказы', 'Заказы, созданные в выбранные даты до времени обновления. Каждый заказ считается один раз независимо от его текущего состояния.'],
  ['batches.created', 'Созданные части заказов', 'Части заказов, созданные в выбранные даты. Первый заказ и каждый дозаказ могут содержать отдельную часть; это не количество заказов.']
]
const transitions: Definition[] = [
  ['orders.firstAccepted', 'Впервые принятые заказы', 'Заказ учитывается в момент первого фактического принятия любой его части. Дозаказы не добавляют ещё один принятый заказ. Принятие сохраняется после отказа; неизвестная старая история отмечается отдельно.'],
  ['batches.accepted', 'Принятые части заказов', 'Различные части заказов с подтверждённым переходом в принятое состояние в выбранные даты. Повторный переход той же части в периоде не увеличивает число.'],
  ['batches.delivered', 'Выданные части заказов', 'Различные части с подтверждённой выдачей в выбранные даты. Закрытие заказа само по себе не является выдачей или оплатой.'],
  ['batches.rejected', 'Отклонённые части заказов', 'Различные части с зафиксированным отказом в выбранные даты. Ранее принятое состояние не стирается; это не возврат денег.']
]
const cohort: Definition[] = [
  ['batches.cohortAcceptedRate', 'Доля принятых частей', 'Среди частей, созданных в выбранные даты: число с известным первым принятием до времени обновления, делённое на число всех созданных частей. Тот же знаменатель используется для доли выданных.'],
  ['batches.cohortDeliveredRate', 'Доля выданных частей', 'Среди той же группы созданных частей: число с известной первой выдачей до времени обновления, делённое на число всех созданных частей. Выдача может быть позже выбранных дат.'],
  ['batches.acceptedDelay', 'Среднее время принятия', 'Среднее время от создания части до её первого принятия. Учитываются части, созданные в выбранные даты, с достоверной историей и измеримым принятием до времени обновления.'],
  ['batches.deliveredDelay', 'Среднее время выдачи', 'Среднее время от создания части до её первой выдачи. Учитываются части, созданные в выбранные даты, с достоверной историей и измеримой выдачей до времени обновления.'],
  ['orders.cancelledSnapshot', 'Отменённые заказы из созданных', 'Текущее число отменённых заказов среди созданных в выбранные даты. Это состояние на момент обновления, а не число отмен по дате отмены.'],
  ['items.excludedSnapshot', 'Исключённые строки позиций', 'Текущее число исключённых или отменённых строк в частях, созданных в выбранные даты. Считаются строки, а не количество единиц товара. История исключений не восстанавливается.']
]
const bookingScheduled: Definition[] = [
  ['bookings.scheduled', 'Запланировано посещений', 'Бронирования с текущей запланированной датой посещения внутри выбранного периода. При переносе используется новая дата. Это отдельная группа от созданных заявок.'],
  ['bookings.seated', 'Пришли', 'Бронирования этой группы с подтверждённой посадкой. Изменение или подтверждение заявки само по себе не означает визит.'],
  ['bookings.no_show', 'Не пришли', 'Бронирования этой группы, отмеченные как неявка.'],
  ['bookings.unresolved', 'Исход ещё не отмечен', 'Запланированное время уже прошло, но нет отметки о посадке, неявке, отмене или истечении. Изменённые заявки могут оставаться здесь.'],
  ['bookings.future', 'Предстоящие', 'Запланированное время ещё не наступило, итог не отмечен. Не включаются посадки, неявки, отменённые и истёкшие заявки.'],
  ['bookings.canceled', 'Отменены', 'Отменённые бронирования с запланированным посещением в выбранные даты.'],
  ['bookings.expired', 'Истекли', 'Истёкшие бронирования с запланированным посещением в выбранные даты.'],
  ['bookings.attendance', 'Доля пришедших', 'Пришли ÷ (пришли + не пришли) для запланированных посещений. Заявки без исхода, предстоящие, отменённые и истёкшие не входят в знаменатель. При пустом знаменателе доля недоступна.']
]
const calls: Definition[] = [
  ['calls.created', 'Созданные вызовы', 'Вызовы, созданные в выбранные даты до времени обновления.'],
  ['calls.acknowledged', 'Принятые сотрудником', 'Вызовы с достоверным временем первого принятия сотрудником внутри выбранного периода. Время создания может относиться к другим датам.'],
  ['calls.operatorCompleted', 'Завершённые сотрудником', 'Вызовы, завершение которых сотрудником подтверждено внутри выбранного периода. Автоматическое завершение сюда не входит.'],
  ['calls.autoCompleted', 'Автоматические завершения', 'Вызовы счёта, автоматически завершённые при закрытии заказа внутри выбранного периода. Это не подтверждение обслуживания сотрудником.'],
  ['calls.cancelled', 'Отменённые вызовы', 'Вызовы с подтверждённой отменой внутри выбранного периода, включая автоматическую отмену при закрытии заказа. Это не выполнение сотрудником.'],
  ['calls.firstResponse', 'Среднее время ответа', 'Среднее время от создания до первого принятия сотрудником для вызовов, созданных в выбранные даты. Только измеримые случаи с достоверной историей до времени обновления; автоматическое завершение не подменяет ответ.'],
  ['calls.legacyUnknown', 'Без достоверной истории времени', 'Вызовы, созданные в выбранные даты, для которых старая история времени неполна. Это ограничение данных, а не оценка работы сотрудников.']
]
const money: Definition[] = [
  ['accrued', 'Начислено', 'Начисленная сумма продаж, включая ручные продажи, после скидок и подарков по правилам учёта.'],
  ['operatorConfirmed', 'Подтверждено оператором', 'Оплаты наличными, картой и переводом, внесённые сотрудником. Аннулированные записи оплат не учитываются. Это не банковская сверка и не реальные возвраты.'],
  ['discounts', 'Скидки', 'Зафиксированная в учёте сумма скидок.'],
  ['gifts', 'Подарки', 'Зафиксированная в учёте стоимость подарков. Это не денежное поступление.'],
  ['unpaid', 'Зафиксированный неоплаченный остаток', 'Начисленная, но не отмеченная оплаченной сумма по отчёту. Остаток закрытой смены не является текущим долгом клиента.'],
  ['averageAccruedSale', 'Средняя начисленная продажа', 'Начислено ÷ количество продаж, включая ручные продажи. При отсутствии продаж среднее недоступно.'],
  ['sales', 'Количество продаж', 'Количество продаж по правилам учёта, включая ручные продажи. Это не количество заказов или гостей.']
]
const statuses: Record<string, string> = {
  NEW: 'Новые', PENDING: 'Ожидают решения', CONFIRMED: 'Подтверждены', CHANGED: 'Изменены',
  SEATED: 'Пришли', NO_SHOW: 'Не пришли', CANCELED: 'Отменены', CANCELLED: 'Отменены', EXPIRED: 'Истекли',
  ACK: 'Приняты сотрудником', DONE: 'Завершены', DECLINED: 'Отклонены', REJECTED: 'Отклонены'
}

export function calculation(text: string) {
  const details = el('details', { className: 'stats-explanation' })
  append(details, el('summary', { text: 'Как считается' }), el('p', { text }))
  return details
}

export function coverageText(kpi?: KpiValue): string {
  if (!kpi) return 'Недостаточно данных для расчёта.'
  if (kpi.reason === 'REPORT_MISSING_OR_INCONSISTENT') return 'Не все отчёты смен доступны или полны.'
  if (kpi.reason === 'ITEM_OR_HISTORY_COVERAGE_INCOMPLETE') return 'Не для всех выданных частей сохранены состав или точное время.'
  if (kpi.reason === 'NUMERIC_OVERFLOW') return 'Сумма превышает поддерживаемый диапазон расчёта.'
  if (kpi.reason === 'LEGACY_HISTORY_UNKNOWN') return 'Часть старых операций не содержит точного времени.'
  if (kpi.availability === 'PARTIAL' || kpi.coverage === 'PARTIAL') return 'Данные неполны. Показана только подтверждённая часть.'
  return 'Недостаточно данных для расчёта.'
}

function formatted(kpi: KpiValue, value: string, currency?: string): string {
  if (kpi.unit === 'MINOR_UNITS') return currency ? exactMoney(value, currency) : 'Недостаточно данных'
  if (kpi.unit === 'RATIO') return formatRatio(value)
  if (kpi.unit === 'MILLISECONDS') return formatDuration(value)
  if (kpi.unit === 'COUNT') return exactCount(value)
  return 'Недостаточно данных'
}

export function metric(definition: Definition, kpi?: KpiValue, currency?: string, periodBasis = '') {
  const [key, label, explanation] = definition
  const card = el('article', { className: 'venue-stats-metric' })
  card.dataset.kpi = key
  const available = kpi?.availability === 'AVAILABLE' && kpi.coverage === 'COMPLETE_RECORDED_SOURCE' && kpi.value != null
  const partial = kpi?.availability === 'PARTIAL' || kpi?.coverage === 'PARTIAL'
  const value = el('strong', {
    className: 'stats-value', text: available ? formatted(kpi, kpi.value!, currency) : partial ? 'Частичные данные' : 'Недостаточно данных'
  })
  if (available && currency && value.textContent!.length > 20) value.dataset.long = 'true'
  if (available && currency && kpi.unit === 'MINOR_UNITS' && value.textContent!.endsWith(currency)) {
    const amount = value.textContent!.slice(0, -currency.length).trimEnd()
    value.replaceChildren(document.createTextNode(`${amount} `), el('span', { className: 'stats-currency-unit', text: currency }))
  }
  append(card, el('h4', { text: label }), value)
  if (!available) {
    card.dataset.coverage = partial ? 'partial' : 'unavailable'
    card.append(el('p', { className: 'stats-note', text: coverageText(kpi) }))
    if (partial && kpi?.observedValue != null) {
      card.append(el('p', { className: 'stats-observed', text: `По известным данным: ${formatted(kpi, kpi.observedValue, currency)}` }))
    }
  } else if (kpi.value === '0' && kpi.unit === 'COUNT' && !currency) {
    card.append(el('p', { className: 'stats-note', text: 'За выбранный период событий нет.' }))
  }
  if (kpi && (kpi.unit === 'MILLISECONDS' || kpi.unit === 'RATIO' || partial)) {
    card.append(el('p', { className: 'stats-note', text:
      `Случаев в расчёте: ${exactCount(kpi.samples)}.${kpi.unknown > 0 ? ` Без полной истории: ${exactCount(kpi.unknown)}.` : ''}` }))
  }
  card.append(calculation(`${explanation}${periodBasis ? ` ${periodBasis}` : ''}`))
  return card
}

function group(title: string, note: string, definitions: Definition[], metrics: Record<string, KpiValue>) {
  const block = el('div', { className: 'stats-group' })
  append(block, el('h3', { text: title }), el('p', { className: 'stats-note', text: note }))
  const grid = el('div', { className: 'venue-stats-grid' })
  grid.append(...definitions.map(definition => metric(definition, metrics[definition[0]])))
  block.append(grid)
  return block
}

function distribution(title: string, rows: KpiBucket[] | undefined, top = false) {
  const block = el('div', { className: 'stats-distribution' })
  block.append(el('h3', { text: title }))
  if (!rows?.length) { block.append(el('p', { className: 'stats-note', text: rows ? 'В этой выборке записей нет.' : 'Недостаточно данных для распределения.' })); return block }
  const max = Math.max(...rows.filter(row => Number.isSafeInteger(row.count)).map(row => row.count), 1)
  const list = el('ul')
  rows.forEach(row => {
    const li = el('li')
    const line = el('div', { className: 'stats-distribution-label' })
    append(line, el('span', { text: top ? row.key : statuses[row.key] ?? 'Другой статус' }), el('strong', { text: exactCount(row.count) }))
    const track = el('div', { className: 'stats-bar-track' }); track.setAttribute('aria-hidden', 'true')
    const bar = el('div', { className: 'stats-bar' })
    bar.style.width = `${Number.isSafeInteger(row.count) ? Math.max(0, row.count / max * 100) : 0}%`
    track.append(bar); append(li, line, track); list.append(li)
  })
  block.append(list)
  return block
}

function section(title: string, id: string, intro: string) {
  const block = el('section', { className: 'card stats-section', id: `stats-${id}` })
  const heading = el('h2', { text: title, id: `stats-${id}-heading` })
  block.setAttribute('aria-labelledby', heading.id)
  append(block, heading, el('p', { className: 'stats-intro', text: intro }))
  return block
}

function financeGroups(groups: FinancialKpis[], provisional: boolean) {
  const root = el('div')
  const basis = provisional ? 'Текущее состояние открытой смены на время обновления; выбранный период её не ограничивает.'
    : 'Сумма замороженных отчётов смен, закрытых в выбранные даты. Валюты не складываются.'
  groups.forEach(group => {
    const block = el('section', { className: 'stats-currency' })
    block.dataset.currency = group.currency
    block.append(el('h3', { text: `Валюта: ${/^[A-Z]{3}$/.test(group.currency) ? group.currency : 'не определена'}` }))
    const grid = el('div', { className: 'venue-stats-grid' })
    grid.append(...money.map(definition => metric(definition, group.metrics[definition[0]], group.currency, basis)))
    block.append(grid)
    const payments: Definition[] = [['cash', 'Наличными', 'Оплаты, внесённые сотрудником наличными.'],
      ['card', 'Картой', 'Оплаты, внесённые сотрудником как оплата картой.'], ['transfer', 'Переводом', 'Оплаты, внесённые сотрудником как перевод.']]
    const paymentDetails = el('details', { className: 'stats-payment-details' })
    paymentDetails.append(el('summary', { text: 'Способы подтверждённой оплаты' }))
    const paymentGrid = el('div', { className: 'venue-stats-grid' })
    paymentGrid.append(...payments.map(definition => metric(definition, group.metrics[definition[0]], group.currency, basis)))
    paymentDetails.append(paymentGrid); block.append(paymentDetails); root.append(block)
  })
  return root
}

export function renderStatsContent(snapshot: VenueKpiSnapshot): HTMLElement {
  const root = el('div', { className: 'stats-sections' })
  const m = snapshot.metrics
  const chosen = snapshot.section
  if (chosen === 'all' || chosen === 'orders') {
    const orders = section('Заказы', 'orders', 'Заказ и его части считаются отдельно. Дозаказ — новая часть существующего заказа.')
    append(orders, group('Созданы в период', 'По датам создания заказов и частей.', createdOrders, m),
      group('Принятие и выдача в период', 'По времени подтверждённого действия — заказ мог быть создан раньше.', transitions, m),
      group('Что произошло с созданными частями', 'Среди частей, созданных в выбранные даты, на время обновления данных.', cohort, m))
    const coverage = m['items.deliveredSnapshotCoverage']
    const top = distribution('Популярные выданные позиции', snapshot.breakdowns['items.deliveredTop'], true)
    if (!coverage || coverage.availability !== 'AVAILABLE') {
      top.prepend(el('p', { className: 'stats-warning', text: coverageText(coverage) }))
      if (!snapshot.breakdowns['items.deliveredTop']?.length) top.querySelector('.stats-note')!.textContent = 'Недостаточно данных для списка позиций.'
    }
    top.append(calculation('До 20 позиций по количеству единиц в частях с первой известной выдачей в выбранные даты. Названия сохранены при заказе; текущие названия и цены меню не используются. Исключённые и отменённые строки не входят. Повторная выдача не умножает количество.'))
    orders.append(top); root.append(orders)
  }
  if (chosen === 'all' || chosen === 'finance') {
    const finances = section('Финансовые итоги закрытых смен', 'finance',
      'Показатели закрытых смен относятся к датам их закрытия. Подтверждённые оплаты внесены сотрудником и не означают банковскую сверку.')
    const coverage = m['finance.closedReports']
    if (!coverage || coverage.availability !== 'AVAILABLE') finances.append(el('p', { className: 'stats-warning', text: coverageText(coverage) }))
    if (coverage?.value === '0' && coverage.availability === 'AVAILABLE') finances.append(el('p', { text: 'За выбранный период закрытых смен нет.' }))
    else finances.append(el('p', { className: 'stats-note', text: coverage ? `Смен в расчёте: ${exactCount(coverage.samples)}.` : 'Число смен недоступно.' }))
    finances.append(financeGroups(snapshot.closedShiftFinances, false))
    finances.append(el('p', { className: 'stats-note', text: 'Аннулирование записи оплаты не является реальным возвратом. Остаток закрытой смены не означает текущий долг клиента.' }))
    const open = el('section', { className: 'stats-provisional' })
    append(open, el('h3', { text: 'Текущая смена — предварительные данные' }),
      el('p', { className: 'stats-note', text: 'Отдельное текущее состояние на время обновления. Эти суммы не добавлены к итогам закрытых смен и не ограничены выбранными датами.' }))
    if (m['finance.openShifts']?.value === '0') open.append(el('p', { text: 'Сейчас открытой смены нет.' }))
    else {
      if (m['finance.openReportCoverage']?.availability !== 'AVAILABLE') open.append(el('p', { className: 'stats-warning', text: coverageText(m['finance.openReportCoverage']) }))
      open.append(financeGroups(snapshot.openShiftFinances, true))
    }
    finances.append(open); root.append(finances)
  }
  if (['all', 'bookings', 'seatings'].includes(chosen)) {
    const bookings = section('Бронирования и посадки', 'bookings', 'Создание заявки, запланированное посещение и физическая посадка — разные показатели.')
    if (chosen !== 'seatings') {
      bookings.append(group('Бронирования, созданные в период', 'Текущие статусы заявок, созданных в выбранные даты.',
        [['bookings.created', 'Созданные бронирования', 'Заявки, созданные в выбранные даты до времени обновления, независимо от даты запланированного посещения.']], m))
      bookings.append(distribution('Статусы созданных заявок', snapshot.breakdowns['bookings.createdStatuses']))
      bookings.append(group('Запланированные посещения в период', 'По текущей запланированной дате, включая перенесённые заявки. Это не конверсия всех заявок.', bookingScheduled, m))
      bookings.append(el('p', { className: 'stats-notice', text: 'Доля пришедших = пришли ÷ (пришли + не пришли). Заявки без исхода, предстоящие, отменённые и истёкшие показываются отдельно. Если исходов нет — недостаточно данных, а не 0 %.' }))
      bookings.append(distribution('Статусы запланированных посещений', snapshot.breakdowns['bookings.scheduledStatuses']))
    }
    if (chosen !== 'bookings') {
      bookings.append(metric(['seatings.registeredSessions', 'Посадки', 'Число различных зарегистрированных физических сессий посадки, начавшихся в выбранные даты. Посадки без бронирования и офлайн учитываются. Перемещение за другой стол внутри одной сессии не создаёт новую посадку.'], m['seatings.registeredSessions']))
      bookings.append(el('p', { className: 'stats-notice', text: 'Посадки — зарегистрированные физические сессии, а не уникальные люди. Учитываются гости без бронирования; перемещение в той же сессии не добавляет посадку.' }))
    }
    root.append(bookings)
  }
  if (chosen === 'all' || chosen === 'calls') {
    const callSection = section('Вызовы персонала', 'calls', 'Автоматическое завершение вызова при закрытии заказа не считается подтверждённым обслуживанием сотрудником.')
    callSection.append(group('Обработка вызовов', 'Создание — по дате создания; принятие, завершение и отмена — по подтверждённому времени действия.', calls, m))
    callSection.append(distribution('Текущее состояние созданных вызовов', snapshot.breakdowns['calls.createdStatuses']))
    callSection.append(el('p', { className: 'stats-note', text: 'Распределение показывает состояние на время обновления. В завершённых могут быть как ручные, так и автоматические случаи; они разделены в карточках выше.' }))
    root.append(callSection)
  }
  return root
}
