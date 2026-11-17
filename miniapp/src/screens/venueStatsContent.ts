import { renderVenueProducts, type ProductsView } from './venueProducts'
import type { FinancialKpis, KpiBucket, KpiValue, VenueKpiSnapshot } from '../shared/api/venueStatsApi'
import { append, el } from '../shared/ui/dom'
import { calendarDateAt, exactCount, exactMoney, formatCalendarDate, formatDuration, formatRatio, shiftCalendarDate } from '../shared/ui/venueStatsFormat'

type Definition = [key: string, label: string, explanation: string]
const createdOrders: Definition[] = [
  ['orders.created', 'Созданные заказы', 'Заказы, созданные в выбранные даты до времени обновления. Каждый заказ считается один раз независимо от его текущего состояния.'],
  ['batches.created', 'Отправки заказа', 'Части заказов, созданные в выбранные даты. Первый заказ и каждый дозаказ могут содержать отдельную часть; это не количество заказов.']
]
const transitions: Definition[] = [
  ['orders.firstAccepted', 'Впервые принятые заказы', 'Заказ учитывается в момент первого фактического принятия любой его части. Дозаказы не добавляют ещё один принятый заказ. Принятие сохраняется после отказа; неизвестная старая история отмечается отдельно.'],
  ['batches.accepted', 'Принятые отправки заказов', 'Различные части заказов с подтверждённым переходом в принятое состояние в выбранные даты. Повторный переход той же части в периоде не увеличивает число.'],
  ['batches.delivered', 'Выданные отправки заказов', 'Различные части с подтверждённой выдачей в выбранные даты. Закрытие заказа само по себе не является выдачей или оплатой.'],
  ['batches.rejected', 'Отклонённые отправки заказов', 'Различные части с зафиксированным отказом в выбранные даты. Ранее принятое состояние не стирается; это не возврат денег.']
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
  ['accrued', 'Сумма продаж', 'Сумма ручных и цифровых продаж после действующих скидок и подарков.'],
  ['operatorConfirmed', 'Получено по учёту', 'Оплаты наличными, картой и переводом, внесённые сотрудником. Аннулированные записи оплат не учитываются. Это не банковская сверка и не реальные возвраты.'],
  ['discounts', 'Скидки', 'Зафиксированная в учёте сумма скидок.'],
  ['gifts', 'Подарки', 'Зафиксированная в учёте стоимость подарков. Это не денежное поступление.'],
  ['unpaid', 'Зафиксированный неоплаченный остаток', 'Начисленная, но не отмеченная оплаченной сумма по отчёту. Остаток закрытой смены не является текущим долгом клиента.'],
  ['averageAccruedSale', 'Средняя сумма продажи', 'Сумма продаж ÷ количество учётных продаж. Несколько оплат одной продажи не увеличивают количество. При отсутствии продаж среднее недоступно.'],
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
  if (kpi.reason === 'TEXT_RECONCILIATION_DETAIL_UNKNOWN') return 'Суммы текстовых заказов сверены. Полные скидки и подарки не установлены.'
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

export function metric(definition: Definition, kpi?: KpiValue, currency?: string, periodBasis = '', explain = true, showCoverageNote = true) {
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
    if (showCoverageNote) card.append(el('p', { className: 'stats-note', text: coverageText(kpi) }))
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
  if (explain) card.append(calculation(`${explanation}${periodBasis ? ` ${periodBasis}` : ''}`))
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

function financeGroups(groups: FinancialKpis[], provisional: boolean, compact = false) {
  const root = el('div', { className: 'stats-finance-currencies' })
  if (compact) { root.setAttribute('role', 'group'); root.setAttribute('aria-label', provisional ? 'Оплаты за всю текущую смену — предварительно' : 'Оплаты закрытых смен по дате закрытия') }
  const basis = provisional ? 'Текущее состояние открытой смены на время обновления; выбранный период её не ограничивает.'
    : 'Сумма замороженных отчётов смен, закрытых в выбранные даты. Валюты не складываются.'
  groups.forEach(group => {
    const block = el('section', { className: 'stats-currency' })
    block.dataset.currency = group.currency
    block.append(el('h3', { className: compact && /^[A-Z]{3}$/.test(group.currency) ? 'stats-overview-currency' : '', text: `Валюта: ${/^[A-Z]{3}$/.test(group.currency) ? group.currency : 'не определена'}` }))
    const financialMetric = (definition: Definition) => metric(definition, group.metrics[definition[0]], group.currency, basis, false)
    const received = el('div', { className: 'stats-received' })
    received.append(financialMetric(money.find(d => d[0] === 'operatorConfirmed')!))
    const payments: Definition[] = [['cash', 'Наличными', 'Оплаты, внесённые сотрудником наличными.'],
      ['card', 'Картой', 'Оплаты, внесённые сотрудником как оплата картой.'], ['transfer', 'Переводом', 'Оплаты, внесённые сотрудником как перевод.']]
    const paymentGrid = el('div', { className: 'stats-receipt-methods' })
    paymentGrid.setAttribute('role', 'group'); paymentGrid.setAttribute('aria-label', `Способы оплаты · ${group.currency}`)
    paymentGrid.append(...payments.map(financialMetric)); received.append(paymentGrid); block.append(received)
    const grid = el('div', { className: 'stats-finance-kpis' })
    for (const key of compact ? ['sales'] : ['accrued', 'unpaid', 'sales', 'averageAccruedSale']) {
      const definition = money.find(d => d[0] === key)!
      grid.append(financialMetric(key === 'unpaid' ? [key, 'Неоплаченный остаток', definition[2]] : definition))
    }
    block.append(grid)
    if (group.metrics.unallocatedAccrued) {
      if (!compact) block.append(financialMetric(['unallocatedAccrued', 'Сверено без товарной расшифровки', 'Подтверждённый итог текстовых заказов; состав, скидки и подарки не восстановлены. Это не полученная оплата.']))
      else if (group.metrics.unallocatedAccrued.value !== '0' || group.metrics.unallocatedAccrued.availability !== 'AVAILABLE' || group.metrics.unallocatedAccrued.coverage !== 'COMPLETE_RECORDED_SOURCE') {
        block.append(el('p', { className: 'stats-warning', text: 'Есть сверенные суммы без полной товарной расшифровки. Подробности — в Финансах.' }))
      }
    }
    const additional: Definition[] = money.filter(d => ['discounts', 'gifts'].includes(d[0]))
    // Detail coverage stays visible even while the secondary amounts are collapsed.
    for (const [key, label] of additional) {
      const value = group.metrics[key]
      if (!value || value.availability !== 'AVAILABLE' || value.coverage !== 'COMPLETE_RECORDED_SOURCE' || value.value == null) {
        block.append(el('p', { className: 'stats-warning', text: `${label}: ${coverageText(value)}` }))
      }
    }
    if (!compact) {
      const details = el('details', { className: 'stats-finance-details' })
      const amounts = el('div', { className: 'stats-finance-kpis' })
      amounts.append(...additional.map(definition => metric(definition, group.metrics[definition[0]], group.currency, basis, false, false)))
      append(details, el('summary', { text: 'Скидки, подарки и дополнительные суммы' }), amounts); block.append(details)
    }
    root.append(block)
  })
  return root
}

function financialContent(snapshot: VenueKpiSnapshot, mode: 'current' | 'closed', compact = false, onCurrentShift?: () => void) {
  const current = mode === 'current'
  const root = section(compact ? 'Обзор работы заведения' : current ? 'Текущая смена' : 'Закрытые смены', 'finance', current
    ? 'Предварительные суммы всей открытой смены. Календарный период их не ограничивает.'
    : 'По датам закрытия смен за выбранный период. Текущая смена сюда не входит.')
  root.classList.add('stats-financial-content')
  const intro = root.querySelector('.stats-intro')!
  intro.remove()
  if (current && !compact) root.append(el('p', { className: 'stats-shift-context', text: 'Предварительный итог · вся открытая смена' }))
  const stamp = (v: string) => new Intl.DateTimeFormat('ru-RU', { timeZone: snapshot.period.timezone, dateStyle: 'short', timeStyle: 'short' }).format(new Date(v))
  const coverage = snapshot.metrics[current ? 'finance.openReportCoverage' : 'finance.closedReports']
  const count = snapshot.metrics[current ? 'finance.openShifts' : 'finance.closedReports']
  const empty = count?.value === '0' && count.availability === 'AVAILABLE' && count.coverage === 'COMPLETE_RECORDED_SOURCE'
  if (current && !empty) root.append(el('p', { className: 'stats-shift-context', text: snapshot.openShiftOpenedAt
    ? compact ? `Вся смена с ${stamp(snapshot.openShiftOpenedAt)} · предварительно` : `Открыта ${stamp(snapshot.openShiftOpenedAt)}`
    : 'Время открытия не передано. Обновите приложение и сервер для точной границы смены.' }))
  if (empty) {
    root.append(el('p', { text: current ? 'Сейчас открытой смены нет. Она появится после первой сохранённой продажи или заказа.' : 'Нет смен, закрытых в выбранный период. Учитывается дата закрытия по времени заведения, а не дата отдельных продаж.' }))
    if (!current && (snapshot.openShiftOpenedAt || snapshot.metrics['finance.openShifts']?.value === '1')) {
      root.append(el('p', { text: `Текущая смена открыта${snapshot.openShiftOpenedAt ? ` с ${stamp(snapshot.openShiftOpenedAt)}` : ''} и ещё продолжается. Она не входит в отчёт закрытых смен, даже если в ней уже есть продажи и оплаты.` }))
      if (onCurrentShift) { const open = el('button', { text: 'Показать текущую смену' }); open.type = 'button'; open.addEventListener('click', onCurrentShift); root.append(open) }
    }
  }
  else {
    if (!coverage || coverage.availability !== 'AVAILABLE') root.append(el('p', { className: 'stats-warning', text: coverageText(coverage) }))
    if (!current && (!compact || !coverage || coverage.unknown > 0)) root.append(el('p', { className: 'stats-shift-context', text: coverage ? `Смен в расчёте: ${exactCount(coverage.samples)}.${coverage.unknown ? ` Без полного отчёта: ${exactCount(coverage.unknown)}.` : ''}` : 'Число смен недоступно.' }))
    const groups = current ? snapshot.openShiftFinances : snapshot.closedShiftFinances
    if (!groups.length) root.append(el('p', { className: 'stats-warning', text: 'Недостаточно финансовых данных. Итоговые суммы недоступны.' }))
    root.append(financeGroups(groups, current, compact))
  }
  root.append(intro, el('p', { className: 'stats-note stats-receipt-note', text: 'Получено по учёту — записи сотрудника, не банковская сверка.' }))
  if (!current && !compact) root.append(el('p', { className: 'stats-note', text: 'Аннулирование записи оплаты не является реальным возвратом. Остаток закрытой смены не означает текущий долг клиента.' }))
  const definitions = compact ? money.filter(d => ['operatorConfirmed', 'sales'].includes(d[0])) : money
  root.append(calculation(`${definitions.map(d => `${d[1]}: ${d[2]}`).join(' ')} ${current ? 'Учитывается вся открытая смена на время обновления.' : 'Учитываются сохранённые отчёты по дате закрытия смены в часовом поясе заведения.'} CASH/CARD/TRANSFER уже включают компоненты комбинированных оплат. Валюты не складываются.`))
  return root
}

export function renderStatsContent(snapshot: VenueKpiSnapshot, view?: 'overview' | 'products' | 'additions' | 'finance' | 'service', productsView?: ProductsView, financeMode: 'current' | 'closed' = 'closed', onCurrentShift?: () => void, onSection?: (view: 'finance' | 'service') => void): HTMLElement {
  const root = el('div', { className: 'stats-sections' })
  const m = snapshot.metrics
  if (view === 'overview') {
    root.classList.add('stats-overview')
    const overview = financialContent(snapshot, financeMode, true, onCurrentShift)
    overview.classList.add('stats-overview-summary')
    const start = calendarDateAt(snapshot.period.from, snapshot.period.timezone)
    const end = shiftCalendarDate(calendarDateAt(snapshot.period.to, snapshot.period.timezone), -1)
    const operations = el('div', { className: 'stats-overview-work' })
    const period = el('p', { className: 'stats-overview-period', text: `Обслуживание: ${start.split('-').reverse().join('.')} — ${end.split('-').reverse().join('.')}` })
    const grid = el('div', { className: 'stats-overview-operations' })
    grid.setAttribute('role', 'group'); grid.setAttribute('aria-label', 'Работа заведения за календарный период')
    grid.append(...([
      ['orders.created', 'Заказы', 'Созданные заказы.'],
      ['seatings.registeredSessions', 'Посадки', 'Зарегистрированные физические сессии.'],
      ['calls.created', 'Вызовы', 'Созданные вызовы персонала.']
    ] as Definition[]).map(d => metric(d, m[d[0]], undefined, '', false)))
    append(operations, period, grid)
    if (Object.entries(m).some(([key, value]) => /^(orders|batches|items|bookings|seatings|calls)\./.test(key) && (value.availability === 'PARTIAL' || value.coverage === 'PARTIAL'))) {
      operations.append(el('p', { className: 'stats-warning stats-service-warning', text: 'Часть данных обслуживания неполна. Ограничения указаны у показателей; финансовые итоги указаны отдельно.' }))
    }
    overview.insertBefore(operations, overview.children[1] ?? null)
    overview.append(el('p', { className: 'stats-note', text: `Обслуживание — календарный период по ${snapshot.period.timezone}. Ручные продажи не создают заказы или посадки.` }))
    overview.querySelector('.stats-explanation p')?.append(document.createTextNode(' Заказы и вызовы считаются по созданию, посадки — по регистрации физических сессий, не по числу гостей.'))
    if (onSection) {
      const links = el('div', { className: 'stats-overview-links' })
      for (const [view, title] of [['finance', 'Подробные финансы'], ['service', 'Подробное обслуживание']] as const) {
        const button = el('button', { className: 'button-secondary', text: title }); button.type = 'button'; button.onclick = () => onSection(view); links.append(button)
      }
      overview.append(links)
    }
    root.append(overview)
    return root
  }
  if (view === 'products') return renderVenueProducts(snapshot, productsView)
  if (view === 'additions') {
    root.append(group('Дозаказы', 'Только завершённые счета в заказах, закрытых в период. Даты относятся к закрытию заказа, а не счёта. Личные и общие счета считаются отдельно. Закрытие не означает оплату.', [
      ['additions.completedTabs', 'Счета с выполненными позициями', 'Счета со статусом «Закрыт» в закрытых заказах, хотя бы с одной подтверждённой выдачей.'],
      ['additions.none', 'Без выполненных дозаказов', 'Одна выполненная отправка в счёте.'],
      ['additions.one', 'С одним дозаказом', 'Две выполненные отправки в счёте.'],
      ['additions.twoOrMore', 'С двумя и более дозаказами', 'Три или более выполненных отправок в счёте.'],
      ['additions.share', 'Доля счетов с дозаказом', 'Счета с двумя и более выполненными отправками / счета с выполненными позициями. Нулевой знаменатель — нет данных.'],
      ['additions.count', 'Выполненные дозаказы', 'Сумма выполненных отправок после первой выполненной отправки каждого счёта. Повтор того же запроса не добавляет дозаказ.'],
      ['additions.openTabs', 'Открытые счета — предварительно', 'Текущее число ACTIVE счетов с выполненными позициями, в том числе при уже закрытом заказе. Не включается в итоговую долю.']
    ], m))
    const top = el('section'); top.append(el('h3', { text: 'Чаще в дозаказах' }))
    for (const item of snapshot.breakdowns['additions.items'] ?? []) top.append(el('p', { text: `${item.key.slice(item.key.indexOf(':') + 1)} · ${exactCount(item.count)} единиц` }))
    root.append(top); return root
  }
  if (view === 'service' && snapshot.section === 'all') {
    for (const [kind, title] of [['orders', 'Заказы'], ['bookings', 'Бронирования'], ['seatings', 'Посадки'], ['calls', 'Вызовы персонала']] as const) {
      const details = el('details', { className: 'stats-service-details' })
      append(details, el('summary', { text: title }), renderStatsContent({ ...snapshot, section: kind })); root.append(details)
    }
    return root
  }
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
    root.append(financialContent(snapshot, financeMode, false, onCurrentShift))
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
  if (view === 'service') {
    const blocks = Array.from(root.children)
    root.replaceChildren()
    blocks.forEach(block => {
      const details = el('details', { className: 'stats-service-details' }); details.open = snapshot.section !== 'all'
      append(details, el('summary', { text: block.querySelector('h2')?.textContent ?? 'Подробности обслуживания' }), block); root.append(details)
    })
  }
  return root
}
