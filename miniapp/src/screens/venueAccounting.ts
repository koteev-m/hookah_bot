import { accountingCommand, accountingReport, accountingState, linePayable, salePaid, salePayable, type AccountingCommand, type AccountingLineInput, type AccountingReport, type AccountingSale, type AccountingShiftHeader } from '../shared/api/accounting'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { venueGetMenu, venueGetTables } from '../shared/api/venueApi'
import type { VenueAccessDto, VenueMenuItemDto, VenueTableDto } from '../shared/api/venueDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { getTelegramContext } from '../shared/telegram'
import { append, el } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'

type Options = { root: HTMLDivElement | null; backendUrl: string; isDebug: boolean; venueId: number; access: VenueAccessDto; userId?: number | null }
type LineDraft = { id?: string; menuItemId?: number; name: string; qty: string; price: string; discount: string; gift: boolean; giftReason: string }
type Draft = { version?: number; tableId: string; lines: LineDraft[]; reason: string; updatedAt?: number }
const sourceNames: Record<string, string> = { MANUAL: 'Вручную', MINIAPP: 'Mini App', CHAT: 'Telegram', STAFF: 'Заказ персонала', MIXED: 'Telegram / Mini App', SERVICE_CHARGE: 'Услуга в счёте' }
const paymentNames: Record<string, string> = { CASH: 'Наличные', CARD: 'Карта', TRANSFER: 'Перевод' }
const money = (value: number) => formatPrice(value, 'RUB')
const decimal = (value: number) => `${Math.floor(value / 100)}.${String(value % 100).padStart(2, '0')}`
function minor(text: string): number {
  const match = /^(\d{1,10})(?:[.,](\d{1,2}))?$/.exec(text.trim())
  if (!match) throw new Error('Введите сумму в рублях, не более двух знаков после запятой.')
  return Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'))
}
function storageRead<T>(key: string): T | null { try { return JSON.parse(sessionStorage.getItem(key) ?? 'null') as T | null } catch { return null } }
function storageWrite(key: string, value: unknown): boolean { try { sessionStorage.setItem(key, JSON.stringify(value)); return true } catch { return false } }
function storageRemove(key: string) { try { sessionStorage.removeItem(key) } catch { /* No financial state is stored only in the browser. */ } }
function button(text: string, action: () => void, secondary = true) {
  const node = el('button', { text, className: secondary ? 'button-secondary' : '' })
  node.type = 'button'; node.addEventListener('click', action); return node
}
function field(text: string, node: HTMLElement) {
  node.id ||= `accounting-${crypto.randomUUID()}`
  const label = el('label', { text }); label.htmlFor = node.id
  return append(el('div', { className: 'accounting-field' }), label, node)
}
function input(value: string, update: (value: string) => void, decimalInput = false) {
  const node = el('input'); node.value = value
  if (decimalInput) node.inputMode = 'decimal'
  node.addEventListener('input', () => update(node.value)); return node
}

export function renderVenueAccountingScreen(options: Options) {
  const { root, venueId, backendUrl, isDebug, access } = options
  if (!root) return () => undefined
  const deps = { isDebug, getAccessToken, clearSession }
  const abort = new AbortController()
  let disposed = false
  let report: AccountingReport | null = null
  let history: AccountingShiftHeader[] = []
  let nextHistoryBeforeId: number | null = null
  let tables: VenueTableDto[] = []
  let menu: VenueMenuItemDto[] = []
  let saving = false
  const identity = options.userId ?? getTelegramContext().telegramUserId ?? 'unknown'
  const scope = `hookah:accounting:${location.host}:${identity}:${venueId}`
  const pendingKey = `${scope}:pending`
  const firstDraftKey = `${scope}:draft:first:new`
  const adoptFirstDraft = (shiftId: number): Draft | null => {
    const key = `${scope}:draft:${shiftId}:new`
    const first = storageRead<Draft>(firstDraftKey)
    const current = storageRead<Draft>(key)
    if (!first) return current
    // Existing legacy drafts win when their relative age is unknown.
    const chosen = current && !(first.updatedAt !== undefined && current.updatedAt !== undefined && first.updatedAt > current.updatedAt) ? current : first
    if (chosen === current || storageWrite(key, chosen)) storageRemove(firstDraftKey)
    return chosen
  }
  const query = new URLSearchParams(location.hash.split('?')[1] ?? '')
  const sameVenue = query.get('av') === String(venueId)
  const requestedShift = sameVenue ? Number(query.get('shift')) || null : null
  const selectedSale = sameVenue ? query.get('sale') : null
  const view = sameVenue ? query.get('view') : null
  const wrapper = el('section', { className: 'venue-accounting' })
  const error = el('p', { className: 'error-card' }); error.setAttribute('role', 'alert'); error.hidden = true
  const content = el('div')
  append(wrapper, el('h2', { text: 'Учёт смены' }), el('p', { className: 'muted', text: 'Внутренний учёт продаж и внешних оплат. Не касса и не эквайринг. Учётная смена не связана с графиком сотрудников.' }), error, content)
  root.replaceChildren(wrapper)
  const date = (value: string, zone: string) => new Intl.DateTimeFormat('ru-RU', { timeZone: zone, dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
  const navigate = (params: Record<string, string> = {}) => {
    const q = new URLSearchParams({ av: String(venueId), ...(report ? { shift: String(report.shiftId) } : {}), ...params })
    location.hash = `#/accounting?${q}`
  }
  const showError = (value: ApiErrorInfo | Error) => {
    if (disposed) return
    error.hidden = false
    error.textContent = value instanceof Error ? value.message
      : value.status === 403 ? 'Доступ отозван или действие недоступно вашей роли. Вернитесь к выбору заведения.'
        : ['ACCOUNTING_CONFLICT', 'INVALID_INPUT'].includes(value.code ?? '') ? value.message ?? 'Проверьте данные.'
          : 'Не удалось подтвердить операцию. Обновите отчёт; повтор с теми же данными не создаёт вторую запись.'
  }
  const run = async (body: AccountingCommand, draftKey?: string) => {
    if (saving) return
    if (!storageWrite(pendingKey, { body, draftKey })) {
      showError(new Error('Операция не отправлена: не удалось сохранить данные для безопасного повтора. Оставьте форму открытой, освободите место в хранилище браузера и повторите сохранение.'))
      return
    }
    saving = true; error.hidden = true
    content.querySelectorAll<HTMLButtonElement | HTMLInputElement | HTMLSelectElement>('button,input,select').forEach((node) => { node.disabled = true })
    const result = await accountingCommand(backendUrl, venueId, body, deps)
    saving = false
    if (result.ok) {
      storageRemove(pendingKey)
      if (draftKey) storageRemove(draftKey)
      if (disposed) return
      report = result.data
      if (body.action === 'CREATE') navigate()
      else render()
    } else {
      if ([400, 401, 403, 404, 409, 422].includes(result.error.status)) storageRemove(pendingKey)
      if (disposed) return
      render(); showError(result.error)
    }
  }
  const submit = (body: Omit<AccountingCommand, 'requestId'>, draftKey?: string) => void run({ ...body, requestId: crypto.randomUUID() }, draftKey)
  const saleCommand = (sale: AccountingSale, action: string, extra: Partial<AccountingCommand> = {}) => submit({ action, shiftId: report!.shiftId, saleId: sale.id, expectedVersion: sale.version, ...extra })
  const reasonAction = (sale: AccountingSale, action: string, title: string, explanation: string, extra: Partial<AccountingCommand> = {}) => {
    const box = el('div', { className: 'card' })
    const reason = el('textarea'); reason.maxLength = 500
    append(box, el('h3', { text: title }), el('p', { text: explanation }), field('Причина', reason), button('Подтвердить', () => {
      if (!reason.value.trim()) { showError(new Error('Укажите причину.')); return }
      saleCommand(sale, action, { reason: reason.value, ...extra })
    }, false), button('Отмена', () => box.remove()))
    content.appendChild(box); reason.focus()
  }
  const lineEditor = (sale?: AccountingSale) => {
    const draftKey = `${scope}:draft:${report?.shiftId ?? 'first'}:${sale?.id ?? 'new'}`
    const blank = (): LineDraft => ({ name: '', qty: '1', price: '', discount: '0', gift: false, giftReason: '' })
    const saved = !sale && report ? adoptFirstDraft(report.shiftId) : storageRead<Draft>(draftKey)
    const draft: Draft = saved ?? {
      version: sale?.version, tableId: sale ? String(sale.tableId) : '', reason: '',
      lines: sale ? sale.lines.map((line) => ({ id: line.id, name: line.name, qty: String(line.qty), price: decimal(line.unitPriceMinor), discount: decimal(line.discountMinor), gift: line.giftReason !== null, giftReason: line.giftReason ?? '' })) : [blank()]
    }
    if (sale && draft.version !== sale.version) {
      append(content, el('p', { text: 'Запись изменилась после начала исправления. Черновик сохранён; проверьте актуальные суммы перед новым исправлением.' }), button('Загрузить актуальную запись вместо черновика', () => { storageRemove(draftKey); render() }), button('К отчёту', () => navigate()))
      return
    }
    const save = () => { draft.updatedAt = Date.now(); storageWrite(draftKey, draft) }
    const form = el('form', { className: 'card accounting-editor' })
    const select = el('select'); append(select, new Option('Выберите стол', ''))
    tables.filter((t) => t.isActive || t.tableId === sale?.tableId).forEach((t) => select.appendChild(new Option(t.tableLabel, String(t.tableId))))
    select.value = draft.tableId; select.disabled = !!sale
    select.addEventListener('change', () => { draft.tableId = select.value; save() })
    append(form, el('h3', { text: sale ? 'Исправление учётной записи' : 'Добавить продажу по столу' }), field('Стол', select))
    if (sale?.orderId) append(form, el('p', { text: 'Исходный заказ сохраняется. Здесь меняется только учётная скидка или подарок. Цена, количество и счета гостей остаются в исходном заказе.' }))
    const rows = el('div'); form.appendChild(rows)
    const drawLines = () => {
      rows.replaceChildren()
      draft.lines.forEach((line, index) => {
        const row = el('fieldset', { className: 'accounting-line' })
        append(row, el('legend', { text: `Позиция ${index + 1}` }))
        if (!line.id) {
          const menuSelect = el('select'); menuSelect.appendChild(new Option('Ручная позиция', ''))
          menu.filter((item) => item.isAvailable && item.currency === 'RUB').forEach((item) => menuSelect.appendChild(new Option(`${item.name} · ${money(item.priceMinor)}`, String(item.id))))
          menuSelect.value = line.menuItemId ? String(line.menuItemId) : ''
          menuSelect.addEventListener('change', () => {
            const item = menu.find((item) => item.id === Number(menuSelect.value))
            line.menuItemId = item?.id; line.name = item?.name ?? ''; line.price = item ? decimal(item.priceMinor) : ''
            save(); drawLines()
          })
          append(row, field('Из меню или вручную', menuSelect))
        }
        const name = input(line.name, (v) => { line.name = v; save() }); name.maxLength = 200
        const qty = input(line.qty, (v) => { line.qty = v; save() }, true)
        const price = input(line.price, (v) => { line.price = v; save() }, true)
        name.disabled = !!line.menuItemId || !!sale?.orderId; price.disabled = name.disabled; qty.disabled = !!sale?.orderId
        const discount = input(line.discount, (v) => { line.discount = v; save() }, true)
        const gift = el('input'); gift.type = 'checkbox'; gift.checked = line.gift
        gift.addEventListener('change', () => { line.gift = gift.checked; save(); drawLines() })
        append(row, field('Название', name), field('Количество', qty), field('Цена за единицу, ₽', price), field('Скидка на всю позицию, ₽', discount), field('Подарок / бесплатно', gift))
        if (line.gift) append(row, field('Причина подарка', input(line.giftReason, (v) => { line.giftReason = v; save() })))
        if (!sale?.orderId && draft.lines.length > 1) row.appendChild(button('Убрать позицию', () => { draft.lines.splice(index, 1); save(); drawLines() }))
        rows.appendChild(row)
      })
    }
    drawLines()
    if (!sale?.orderId) form.appendChild(button('Ещё позиция', () => { draft.lines.push(blank()); save(); drawLines() }))
    append(form, field('Причина исправления или скидки', input(draft.reason, (v) => { draft.reason = v; save() })))
    const send = el('button', { text: sale ? 'Сохранить исправление' : 'Сохранить продажу' }); send.type = 'submit'
    append(form, send, button('К отчёту', () => navigate()))
    form.addEventListener('submit', (event) => {
      event.preventDefault()
      try {
        if (!draft.tableId) throw new Error('Выберите стол.')
        const lines: AccountingLineInput[] = draft.lines.map((line) => {
          if (!/^\d+$/.test(line.qty) || Number(line.qty) < 1) throw new Error('Количество должно быть целым и больше нуля.')
          if (line.gift && !line.giftReason.trim()) throw new Error('Укажите причину подарка.')
          return { id: line.id, menuItemId: line.menuItemId, name: line.name, qty: Number(line.qty), unitPriceMinor: minor(line.price), discountMinor: minor(line.discount), giftReason: line.gift ? line.giftReason : null }
        })
        if ((sale || lines.some((line) => (line.discountMinor ?? 0) > 0)) && !draft.reason.trim()) throw new Error('Укажите причину исправления или скидки.')
        submit({ action: sale ? 'EDIT' : 'CREATE', shiftId: report?.shiftId, saleId: sale?.id, expectedVersion: sale?.version, tableId: Number(draft.tableId), lines, reason: draft.reason }, draftKey)
      } catch (e) { showError(e as Error) }
    })
    content.appendChild(form)
  }
  const detail = (sale: AccountingSale) => {
    const box = el('section', { className: 'card' })
    append(box, el('h3', { text: `${sale.tableLabel} · ${sourceNames[sale.source] ?? sale.source}` }),
      el('p', { text: sale.issue ? 'Расчётная сумма недоступна и не включена в итог.' : `Начислено ${money(salePayable(sale))} · Оплачено ${money(salePaid(sale))} · Остаток ${money(Math.max(0, salePayable(sale) - salePaid(sale)))}` }))
    if (sale.orderId) {
      const link = el('a', { text: `Открыть заказ №${sale.displayNumber ?? sale.orderId}` }); link.href = `#/order/${sale.orderId}`; box.appendChild(link)
      append(box, el('p', { text: `Исходный счёт: ${money(sale.originalPayableMinor ?? 0)}. Изменения журнала не меняют счёт гостя.` }))
    }
    sale.lines.forEach((line) => append(box, el('p', { text: `${line.name} × ${line.qty} · ${money(line.unitPriceMinor)} = ${money(linePayable(line))}${line.giftReason ? ` · Подарок: ${line.giftReason}` : ''}${line.baseGiftMinor ? ' · Подарок по акции исходного заказа' : ''}${line.baseDiscountMinor + line.discountMinor ? ` · Скидка ${money(line.baseDiscountMinor + line.discountMinor)}` : ''}` })))
    for (const text of [sale.cancelReason && `Отменено: ${sale.cancelReason}`, sale.disputeReason && `Спорная запись: ${sale.disputeReason}`, sale.issue]) if (text) append(box, el('p', { text }))
    content.appendChild(box)
    const editable = report!.status === 'OPEN' && sale.orderClosed && !sale.issue
    if (!sale.orderClosed) append(box, el('p', { text: 'Сначала завершите заказ в разделе «Заказы», затем запишите оплату. Завершение заказа само по себе не подтверждает поступление денег.' }))
    sale.payments.forEach((payment) => {
      const row = el('div', { className: 'accounting-payment' })
      append(row, el('p', { text: `${paymentNames[payment.method]} · ${money(payment.amountMinor)} · ${date(payment.recordedAt, report!.timezone)}${payment.voidReason ? ` · Исправлено: ${payment.voidReason}` : ''}` }))
      if (editable && !payment.voidReason) row.appendChild(button('Исправить запись оплаты', () => reasonAction(sale, 'VOID_PAYMENT', 'Отменить запись оплаты', 'Это отменит только учётную запись и увеличит остаток. Реальные деньги не возвращаются. Для верной суммы затем запишите новую оплату.', { paymentId: payment.id })))
      content.appendChild(row)
    })
    if (editable && !sale.cancelReason) {
      const form = el('form', { className: 'card accounting-payment-form' })
      const amount = el('input'); amount.inputMode = 'decimal'; amount.value = decimal(Math.max(0, salePayable(sale) - salePaid(sale)))
      const method = el('select'); Object.entries(paymentNames).forEach(([value, label]) => method.appendChild(new Option(label, value)))
      const submitButton = el('button', { text: 'Подтвердить внешнюю оплату' }); submitButton.type = 'submit'
      append(form, el('h3', { text: 'Записать оплату' }), el('p', { text: 'Укажите только уже полученные деньги. Для комбинированной оплаты сохраните каждую часть отдельно.' }), field('Способ оплаты', method), field('Получено, ₽', amount), submitButton)
      form.addEventListener('submit', (event) => { event.preventDefault(); try { saleCommand(sale, 'PAY', { amountMinor: minor(amount.value), paymentMethod: method.value }) } catch (e) { showError(e as Error) } })
      content.appendChild(form)
      append(content, button('Исправить продажу', () => navigate({ sale: sale.id, view: 'edit' })), button('Отменить продажу', () => reasonAction(sale, 'CANCEL', 'Отменить продажу', 'Сумма записи станет нулевой; история сохранится. Сначала необходимо исправить её активные оплаты.')))
    }
    if (editable) content.appendChild(button(sale.disputeReason ? 'Разрешить спор' : 'Отметить спорной', () => reasonAction(sale, sale.disputeReason ? 'RESOLVE' : 'DISPUTE', 'Сверка записи', 'Укажите причину и результат сверки. Это не изменяет сумму или реальные деньги.')))
    append(content, button('Обновить запись', () => void load()), button('К отчёту', () => navigate()))
  }
  const render = () => {
    if (disposed) return
    content.replaceChildren()
    const pending = storageRead<{ body: AccountingCommand; draftKey?: string }>(pendingKey)
    if (pending) {
      append(content, el('p', { text: 'Результат предыдущей операции ещё не подтверждён. Повтор безопасен: сохранён тот же ключ и те же данные.' }), button('Проверить и повторить операцию', () => void run(pending.body, pending.draftKey)))
      return
    }
    if (!report) {
      append(content, el('p', { text: 'Добавьте первую продажу. Учётная смена откроется автоматически при сохранении продажи или первом заказе гостя. Просмотр экрана смену не открывает.' }))
      if (view === 'new') { lineEditor(); return }
      append(content, button('Добавить продажу', () => navigate({ view: 'new' }), false))
    } else {
      append(content, el('p', { text: `${report.status === 'OPEN' ? 'Открыта' : 'Закрыта'} · ${date(report.openedAt, report.timezone)}${report.closedAt ? ` — ${date(report.closedAt, report.timezone)}` : ''} · ${report.timezone}` }))
      if (report.status === 'CLOSED') append(content, el('p', { text: 'Сохранённый итог. Редактирование финансовых записей закрытой смены запрещено.' }))
      if (report.status === 'OPEN') {
        const day = new Intl.DateTimeFormat('sv-SE', { timeZone: report.timezone, year: 'numeric', month: '2-digit', day: '2-digit' })
        if (day.format(new Date(report.openedAt)) < day.format(new Date())) append(content, el('p', { className: 'error-card', text: `Смена открыта с ${date(report.openedAt, report.timezone)}. Новые продажи войдут в эту же смену. Полночь её не закрывает. Если прошлый рабочий период завершён, попросите владельца или менеджера сверить и закрыть смену до новой продажи.` }))
      }
      const sale = report.sales.find((item) => item.id === selectedSale)
      if (report.status === 'OPEN' && (view === 'new' || (view === 'edit' && sale && sale.orderClosed && !sale.issue && !sale.cancelReason))) { lineEditor(sale); return }
      if (sale) { detail(sale); return }
      const t = report.totals
      const unknown = report.sales.filter((s) => s.issue).length
      if (unknown) append(content, el('p', { text: `${unknown} записей без достоверного снимка: суммы не включены в итог. Нужна сверка перед закрытием.` }))
      if (report.status === 'OPEN') append(content, button('Добавить продажу', () => navigate({ view: 'new' }), false), button('Обновить отчёт', () => void load()))
      append(content, el('p', { text: `Продаж: ${t.salesCount} · Цифровых заказов: ${t.ordersCount}` }), el('h3', { text: 'Расчётные суммы' }))
      const metrics = el('dl', { className: 'accounting-totals' })
      for (const [label, value] of [['Стоимость позиций', t.grossMinor], ['Скидки', t.discountMinor], ['Подарки / бесплатно', t.giftMinor], ['Начислено к оплате', t.accruedMinor], ['Подтверждено оператором', t.paidMinor], ['Неоплаченный остаток', t.unpaidMinor], ['Наличные', t.cashMinor], ['Карты', t.cardMinor], ['Переводы', t.transferMinor]] as const) append(metrics, el('dt', { text: label }), el('dd', { text: money(value) }))
      append(content, metrics, el('p', { text: 'Начислено — сумма учётных записей. Подтверждено оператором — записанные внешние поступления; это не банковская сверка.' }))

      for (const [label, rows] of [['По столам', report.byTable], ['По позициям', report.byItem], ['По источникам', report.bySource]] as const) {
        const section = el('details', { className: 'card' }); section.appendChild(el('summary', { text: label }))
        rows.forEach((row) => append(section, el('p', { text: `${sourceNames[row.label] ?? row.label} · ${row.qty} · ${money(row.accruedMinor)}` })))
        content.appendChild(section)
      }
      const list = el('section', { className: 'accounting-sales' }); list.appendChild(el('h3', { text: 'Продажи и сверка' }))
      if (!report.sales.length) list.appendChild(el('p', { text: 'В этой смене пока нет продаж.' }))
      for (const item of report.sales) {
        const row = button(`${item.tableLabel} · ${sourceNames[item.source] ?? item.source} · ${money(salePayable(item))}${report.unpaidSaleIds.includes(item.id) ? ' · Не оплачено полностью' : ''}${report.disputedSaleIds.includes(item.id) ? ' · Требует сверки' : ''}${item.cancelReason ? ' · Отменено' : ''}`, () => navigate({ sale: item.id }))
        list.appendChild(row)
      }
      content.appendChild(list)
      if (report.status === 'OPEN' && access.role !== 'STAFF') content.appendChild(button('Предварительный итог и закрытие', () => {
        const box = el('section', { className: 'card' })
        append(box, el('h3', { text: 'Подтверждение закрытия смены' }), el('p', { text: `Получено ${money(t.paidMinor)}. Не оплачено: ${money(t.unpaidMinor)} (${report!.unpaidSaleIds.length} записей). Спорных: ${report!.disputedSaleIds.length}. Итог будет сохранён, дальнейшее исправление и запись оплат в этой смене станут недоступны.` }))
        const ack = el('input'); ack.type = 'checkbox'
        append(box, field('Проверил неоплаченные и спорные записи; принимаю закрытие с этим остатком', ack), button('Подтвердить закрытие смены', () => submit({ action: 'CLOSE', shiftId: report!.shiftId, expectedReportHash: report!.reportHash, acknowledgeUnpaid: ack.checked }), false), button('Продолжить смену', () => box.remove()))
        content.appendChild(box); box.scrollIntoView({ block: 'nearest' })
      }))
    }
    if (access.role !== 'STAFF' && history.length) {
      append(content, el('h3', { text: 'История закрытых смен' }))
      history.forEach((shift) => content.appendChild(button(`${date(shift.openedAt, shift.timezone)} — ${shift.closedAt ? date(shift.closedAt, shift.timezone) : ''}`, () => navigate({ shift: String(shift.id) }))))
      if (nextHistoryBeforeId) content.appendChild(button('Ещё закрытые смены', () => {
        void accountingState(backendUrl, venueId, deps, abort.signal, nextHistoryBeforeId!).then((result) => {
          if (disposed) return
          if (!result.ok) { showError(result.error); return }
          history = [...history, ...result.data.history.filter((shift) => !history.some((old) => old.id === shift.id))]
          nextHistoryBeforeId = result.data.nextHistoryBeforeId ?? null
          render()
        })
      }))
    }
    if (report?.status === 'CLOSED') content.appendChild(button('К текущей смене', () => {
      const target = `#/accounting?av=${venueId}`
      if (location.hash === target) void load()
      else location.hash = target
    }))
  }
  const load = async () => {
    const state = await accountingState(backendUrl, venueId, deps, abort.signal)
    if (disposed) return
    if (!state.ok) { showError(state.error); return }
    history = state.data.history
    nextHistoryBeforeId = state.data.nextHistoryBeforeId ?? null
    report = state.data.current
    if (report && !storageRead(pendingKey)) adoptFirstDraft(report.shiftId)
    if (requestedShift && requestedShift !== report?.shiftId) {
      const result = await accountingReport(backendUrl, venueId, requestedShift, deps, abort.signal)
      if (disposed) return
      if (!result.ok) { showError(result.error); return }
      report = result.data
    }
    render()
  }
  void Promise.all([venueGetTables(backendUrl, venueId, deps, abort.signal), venueGetMenu(backendUrl, venueId, deps, abort.signal)]).then(([t, m]) => {
    if (disposed) return
    if (t.ok) tables = t.data.tables
    if (m.ok) menu = m.data.categories.flatMap((category) => category.items)
    if (!t.ok || !m.ok) showError(new Error('Не удалось загрузить столы или меню. Обновите раздел перед добавлением продажи.'))
    void load()
  })
  return () => { disposed = true; abort.abort() }
}
