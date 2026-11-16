import { accountingStamp, compareAccountingTime, orderedSales, paymentOperations, venueDay, type PaymentOperation } from './accountingPresentation'
import { salePaid, salePayable, type AccountingReport, type AccountingSale } from '../shared/api/accounting'
import { append, el } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'

export type ShiftView = {
  mode: 'sales' | 'payments'; filter: string; search: string
  salesLimit: number; paymentsLimit: number; days: Record<string, boolean>
  totalsOpen: boolean; archiveOpen: boolean; scroll?: number; returnSale?: string; returnOperation?: string
  route?: string
}
const names: Record<string, string> = { MANUAL: 'Вручную', MINIAPP: 'Mini App', CHAT: 'Telegram', STAFF: 'Заказ персонала', MIXED: 'Telegram / Mini App', SERVICE_CHARGE: 'Услуга в счёте' }
const money = (value: number) => formatPrice(value, 'RUB')
const needsReview = (sale: AccountingSale) => Boolean(sale.issue || sale.sourceWarning || sale.disputeReason)
function button(text: string, action: () => void) {
  const node = el('button', { text, className: 'button-secondary' }); node.type = 'button'; node.onclick = action; return node
}

// Only this list region is redrawn. Editors, search input and server report retain their identity.
export function accountingShiftEntries(options: {
  report: AccountingReport; state: ShiftView; save: () => void; openSale: (id: string, operation?: string) => void
  tableNumber: (sale: AccountingSale) => number | undefined
  reference: (sale: AccountingSale) => string; paymentBlock: (sale: AccountingSale, operation: PaymentOperation, corrections: boolean) => HTMLElement
}) {
  const { report, state, save, reference, openSale, paymentBlock } = options
  const days = new Map(report.sales.map(sale => [sale.id, venueDay(sale.createdAt, report.timezone) ?? 'unknown']))
  const section = el('section', { className: 'accounting-entries' }); section.dataset.accountingSection = 'entries'
  const heading = el('h3', { text: report.status === 'OPEN' ? 'Продажи и оплаты текущей смены' : 'Продажи и оплаты закрытой смены' })
  const modes = el('div', { className: 'accounting-list-modes' }); modes.setAttribute('aria-label', 'Список учёта')
  modes.setAttribute('role', 'group')
  const input = el('input', { id: 'accounting-list-search' }); input.type = 'search'; input.value = state.search; input.maxLength = 120
  const label = el('label', { text: 'Поиск по столу или номеру записи' }); label.htmlFor = input.id
  const searchField = append(el('div', { className: 'accounting-field' }), label, input)
  const filters = el('div', { className: 'accounting-sales-filters' }); filters.setAttribute('aria-label', 'Состояние оплаты')
  filters.setAttribute('role', 'group')
  const hint = el('p', { className: 'muted', text: 'Требующие сверки записи видны при любом фильтре. Поиск действует на весь полученный отчёт.' })
  const count = el('p', { className: 'accounting-list-count' }); count.setAttribute('role', 'status')
  const body = el('div'), more = button('Показать ещё', () => {
    const limit = state.mode === 'sales' ? 'salesLimit' : 'paymentsLimit'
    state[limit] += 20; save(); draw()
    // The same button survives; loading another portion keeps keyboard focus and scroll.
  })
  const allSales = orderedSales(report.sales)
  const operations = report.sales.flatMap(sale => paymentOperations(sale).map(operation => ({ sale, operation })))
  operations.sort((a, b) => compareAccountingTime(a.operation.recordedAt, b.operation.recordedAt, JSON.stringify([a.sale.id, a.operation.key]), JSON.stringify([b.sale.id, b.operation.key])))
  const matches = (sale: AccountingSale) => {
    const query = state.search.trim().toLocaleLowerCase('ru-RU')
    return !query || `${sale.tableLabel} ${options.tableNumber(sale) ?? ''} ${reference(sale)} ${sale.displayNumber ?? ''}`.toLocaleLowerCase('ru-RU').includes(query)
  }
  const filtered = (sale: AccountingSale) => {
    if (needsReview(sale) || state.filter === 'all') return true
    if (sale.cancelReason) return false
    const total = salePayable(sale), paid = salePaid(sale)
    return state.filter === 'paid' ? total > 0 && paid >= total : state.filter === 'partial' ? paid > 0 && paid < total : paid === 0 && total > 0
  }
  const today = () => venueDay(new Date().toISOString(), report.timezone)
  const draw = () => {
    const x = scrollX, y = scrollY
    body.replaceChildren()
    modes.querySelectorAll<HTMLButtonElement>('button').forEach(n => n.setAttribute('aria-pressed', String(n.dataset.mode === state.mode)))
    filters.hidden = hint.hidden = state.mode !== 'sales'
    filters.querySelectorAll<HTMLButtonElement>('button').forEach(n => n.setAttribute('aria-pressed', String(n.dataset.filter === state.filter)))
    const payments = state.mode === 'payments'
    body.className = payments ? 'accounting-shift-payments' : 'accounting-sales'
    body.id = payments ? 'accounting-shift-payments' : 'accounting-sales-list'
    const found = payments ? operations.filter(({ sale }) => matches(sale)) : allSales.filter(s => matches(s) && filtered(s))
    const limit = payments ? state.paymentsLimit : state.salesLimit
    const shown = found.slice(0, limit)
    count.textContent = `Найдено: ${found.length} · В списке: ${shown.length}`
    more.hidden = shown.length >= found.length
    if (payments) {
      body.append(el('p', { className: 'muted', text: `Оплаты всей смены · Операций: ${operations.length} · Компонентов: ${report.sales.reduce((n, s) => n + s.payments.length, 0)}. Исправленные записи сохранены; каждая операция относится к одной продаже.` }))
      if (!found.length) body.append(el('p', { text: state.search.trim() ? 'Нет оплат по этому запросу.' : 'В этой смене оплата ещё не записана.' }))
      for (const { sale, operation } of shown as typeof operations) {
        const block = paymentBlock(sale, operation, false)
        if (sale.cancelReason || needsReview(sale)) block.prepend(el('p', { className: 'accounting-row-warning', text: sale.cancelReason ? 'Продажа отменена' : 'Продажа требует сверки' }))
        block.append(button(`Открыть продажу · ${sale.tableLabel} · ${reference(sale)}`, () => openSale(sale.id, operation.key))); body.append(block)
      }
    } else {
      if (!found.length) body.append(el('p', { text: !report.sales.length ? 'В этой смене пока нет продаж.' : 'Нет продаж с выбранным состоянием или запросом.' }))
      const groups = new Map<string, { box: HTMLDetailsElement; rows: HTMLDivElement }>()
      const sales = found as AccountingSale[]
      for (const sale of shown as AccountingSale[]) {
        const day = days.get(sale.id)!
        let group = groups.get(day)
        if (!group) {
          const box = el('details', { className: 'accounting-day-group' })
          const summary = el('summary'), dateLabel = day === 'unknown' ? 'Дата не определена' : accountingStamp(sale.createdAt, report.timezone, 'date')
          const label = el('span', { id: `accounting-day-${day}`, text: `${day === today() ? 'Сегодня · ' : ''}${dateLabel}` }); label.dataset.saleDate = day; label.dataset.dateLabel = dateLabel
          const daily = sales.filter(s => days.get(s.id) === day)
          const reviews = daily.filter(needsReview).length
          append(summary, label, el('span', { className: reviews ? 'accounting-row-warning' : 'muted', text: `${daily.length} записей${reviews ? ` · Сверка: ${reviews}` : ''}` }))
          const rows = el('div', { className: 'accounting-day-sales' }); append(box, summary, rows)
          box.open = state.search.trim() !== '' || state.filter !== 'all' || (state.days[day] ?? day === today())
          box.ontoggle = () => { state.days[day] = box.open; save() }
          body.append(box); group = { box, rows }; groups.set(day, group)
        }
        const total = salePayable(sale), paid = salePaid(sale), unknown = sale.issue || sale.sourceWarning
        const status = sale.cancelReason ? 'Отменено' : unknown ? 'Сумма не подтверждена · Требует сверки' : sale.disputeReason ? 'Спорная · Требует сверки' : total === 0 && paid === 0 ? 'Оплата не требуется' : paid >= total ? 'Оплачено' : paid > 0 ? 'Частично' : 'Не оплачено'
        const row = button('', () => openSale(sale.id)); row.dataset.saleId = sale.id; row.setAttribute('aria-describedby', `accounting-day-${day}`)
        const title = el('span', { className: 'accounting-row-top' }); append(title, el('span', { className: 'accounting-row-title', text: `${sale.tableLabel} · ${names[sale.source] ?? sale.source}` }), el('span', { className: 'accounting-row-amount', text: unknown ? 'Сумма неизвестна' : money(total) }))
        append(row, title, el('span', { className: 'accounting-row-meta', text: `${reference(sale)} · ${accountingStamp(sale.createdAt, report.timezone, 'time')}` }), el('span', { className: sale.cancelReason || unknown || sale.disputeReason ? 'accounting-row-warning' : 'accounting-row-state', text: status }))
        if (!unknown && !sale.cancelReason && paid > 0 && paid < total) row.append(el('span', { className: 'accounting-row-remainder', text: `Осталось ${money(total - paid)}` }))
        group.rows.append(row)
      }
    }
    window.scrollTo(x, y)
  }
  for (const [mode, text] of [['sales', 'Продажи'], ['payments', 'Оплаты смены']] as const) {
    const node = button(text, () => { state.mode = mode; save(); draw() }); node.dataset.mode = mode; modes.append(node)
  }
  for (const [value, text] of [['unpaid', 'Не оплачены'], ['partial', 'Частично'], ['paid', 'Оплачены'], ['all', 'Все']]) {
    const node = button(text, () => { state.filter = value; state.salesLimit = 20; save(); draw() }); node.dataset.filter = value; filters.append(node)
  }
  input.oninput = () => { state.search = input.value; state.salesLimit = state.paymentsLimit = 20; save(); draw() }
  append(section, heading, modes, searchField, filters, hint, count, body, more); draw()
  return section
}
