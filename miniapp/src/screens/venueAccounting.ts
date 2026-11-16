import { accountingShiftEntries, type ShiftView } from './accountingShiftEntries'
import { accountingStamp, orderedSales, paymentOperations, venueDay, type PaymentOperation } from './accountingPresentation'
import { accountingPaymentEditor, paymentNames, type PaymentDraft } from './accountingPaymentEditor'
import { accountingTextReconciliation } from './accountingTextReconciliation'
import { accountingSaleEditor, accountingDecimal as decimal, accountingMinor as minor, type LineDraft } from './accountingSaleEditor'
import { accountingCommand, accountingReport, accountingState, linePayable, salePaid, salePayable, type AccountingCommand, type AccountingReport, type AccountingSale, type AccountingShiftHeader } from '../shared/api/accounting'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { venueGetMenu, venueGetTables } from '../shared/api/venueApi'
import type { VenueAccessDto, VenueMenuCategoryDto, VenueTableDto } from '../shared/api/venueDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { getTelegramContext } from '../shared/telegram'
import { append, el } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'

type Options = { root: HTMLDivElement | null; backendUrl: string; isDebug: boolean; venueId: number; access: VenueAccessDto; userId?: number | null }
type Draft = { version?: number; tableId: string; lines: LineDraft[]; reason: string; updatedAt?: number; revision?: string; amount?: string; confirmationSource?: string }
type StoredCopy = { key: string; value: string }
type StoredDraft = { draft: Draft; key: string; copies: StoredCopy[] }
type PendingCommand = { body: AccountingCommand; draftCopies?: StoredCopy[]; draftKey?: string }
const sourceNames: Record<string, string> = { MANUAL: 'Вручную', MINIAPP: 'Mini App', CHAT: 'Telegram', STAFF: 'Заказ персонала', MIXED: 'Telegram / Mini App', SERVICE_CHARGE: 'Услуга в счёте' }
const money = (value: number) => formatPrice(value, 'RUB')
function storageRead<T>(key: string): T | null { try { return JSON.parse(sessionStorage.getItem(key) ?? 'null') as T | null } catch { return null } }
function storageWrite(key: string, value: unknown): boolean { try { sessionStorage.setItem(key, JSON.stringify(value)); return true } catch { return false } }
function storageRemove(key: string) { try { sessionStorage.removeItem(key) } catch { /* No financial state is stored only in the browser. */ } }
function removeUnchanged(copy: StoredCopy) {
  try { if (sessionStorage.getItem(copy.key) === copy.value) sessionStorage.removeItem(copy.key) } catch { /* Keep data if storage is unavailable. */ }
}
function readDraft(key: string): StoredDraft | null {
  try {
    const value = sessionStorage.getItem(key)
    return value ? { draft: JSON.parse(value) as Draft, key, copies: [{ key, value }] } : null
  } catch { return null }
}
function matchesLegacyCommand(draft: Draft, body: AccountingCommand): boolean {
  if (draft.revision || draft.lines.some(l => l.discountMode === 'percent') || !['CREATE', 'EDIT'].includes(body.action)) return false
  try {
    return draft.version === body.expectedVersion && Number(draft.tableId) === body.tableId && draft.reason === body.reason &&
      JSON.stringify(draft.lines.map(line => ({ id: line.id, menuItemId: line.menuItemId, name: line.name, qty: Number(line.qty), unitPriceMinor: minor(line.price), discountMinor: line.discount.trim() ? minor(line.discount) : 0, giftReason: line.gift ? line.giftReason : null }))) === JSON.stringify(body.lines)
  } catch { return false }
}
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
  let categories: VenueMenuCategoryDto[] = []
  let catalogUnavailable = false
  let editor: ReturnType<typeof accountingSaleEditor> | ReturnType<typeof accountingPaymentEditor> | null = null
  let denied = false
  let saving = false
  let calendarDay: string | null = null
  let calendarTimer: number | undefined
  let loadVersion = 0
  const identity = options.userId ?? getTelegramContext().telegramUserId ?? 'unknown'
  const scope = `hookah:accounting:${location.host}:${identity}:${venueId}`
  const pendingKey = `${scope}:pending`
  const noticeKey = `${scope}:notice`
  let successNotice = storageRead<string>(noticeKey); storageRemove(noticeKey)
  let savedSaleId = storageRead<string>(`${scope}:saved-sale`); storageRemove(`${scope}:saved-sale`)
  const firstDraftKey = `${scope}:draft:first:new`
  const adoptFirstDraft = (shiftId: number): StoredDraft | null => {
    const key = `${scope}:draft:${shiftId}:new`
    const first = readDraft(firstDraftKey)
    const current = readDraft(key)
    if (!first) return current
    // Existing legacy drafts win when their relative age is unknown.
    if (current && !(first.draft.updatedAt !== undefined && current.draft.updatedAt !== undefined && first.draft.updatedAt > current.draft.updatedAt)) {
      removeUnchanged(first.copies[0])
      return current
    }
    if (!storageWrite(key, first.draft)) return first
    removeUnchanged(first.copies[0])
    return { draft: first.draft, key, copies: [...first.copies, { key, value: JSON.stringify(first.draft) }] }
  }
  const query = new URLSearchParams(location.hash.split('?')[1] ?? '')
  const sameVenue = query.get('av') === String(venueId)
  const requestedShift = sameVenue ? Number(query.get('shift')) || null : null
  const selectedSale = sameVenue ? query.get('sale') : null
  const view = sameVenue ? query.get('view') : null
  let salesFilter = sameVenue ? query.get('filter') ?? 'all' : 'all'
  let listMode: 'sales' | 'payments' = sameVenue && query.get('list') === 'payments' ? 'payments' : 'sales'
  let viewState: ShiftView | null = null
  let viewKey = ''
  const saveView = () => { if (viewState && !disposed && !denied && currentActor()) storageWrite(viewKey, viewState) }
  const restoreView = () => {
    const key = `${scope}:view:${report?.shiftId ?? 'first'}`
    if (viewKey === key && viewState) return viewState
    viewKey = key
    viewState = { mode: listMode, filter: salesFilter, search: '', salesLimit: 20, paymentsLimit: 20, days: {}, totalsOpen: false, archiveOpen: false, ...storageRead<ShiftView>(key) }
    const route = JSON.stringify(sameVenue ? [query.get('list'), query.get('filter')] : [null, null])
    // Local tab changes keep the URL. A different route (including history traversal) is explicit.
    if (viewState.route !== undefined && viewState.route !== route) { viewState.mode = listMode; viewState.filter = salesFilter }
    viewState.route = route
    salesFilter = viewState.filter; listMode = viewState.mode
    return viewState
  }
  let saleNumbers = new Map<string, number>()
  const reference = (sale: AccountingSale) => `Запись ${saleNumbers.get(sale.id) ?? '—'}`
  const wrapper = el('section', { className: 'venue-accounting' })
  const error = el('p', { className: 'error-card' }); error.setAttribute('role', 'alert'); error.hidden = true
  const content = el('div')
  append(wrapper, el('h2', { text: 'Учёт смены' }), el('p', { className: 'muted', text: 'Учётная смена открывается автоматически при первой сохранённой продаже или заказе. Закрывается вручную владельцем или управляющим после сверки.' }), error, content)
  root.replaceChildren(wrapper)
  const date = (value: string, zone: string) => accountingStamp(value, zone)
  const updateDailyCount = (node: HTMLElement, today: string | null) => {
    const count = report!.sales.filter(s => s.cancelReason === null && today !== null && venueDay(s.createdAt, report!.timezone) === today).length
    node.textContent = today ? `Продаж сегодня в этой смене: ${count}` : 'Продаж сегодня в этой смене: дата недоступна'
    node.dataset.todayCount = today ? String(count) : 'unknown'
  }
  const oldShift = (today: string | null) => {
    const opened = report && venueDay(report.openedAt, report.timezone)
    return Boolean(report?.status === 'OPEN' && today && opened && opened < today)
  }
  const refreshCalendar = () => {
    if (!report) return
    const today = venueDay(new Date().toISOString(), report.timezone)
    if (today === calendarDay) return
    calendarDay = today
    const x = window.scrollX, y = window.scrollY
    const daily = content.querySelector<HTMLElement>('[data-today-count]')
    if (daily) updateDailyCount(daily, today)
    content.querySelectorAll<HTMLElement>('[data-sale-date]').forEach(heading => {
      heading.textContent = `${heading.dataset.saleDate === today ? 'Сегодня · ' : ''}${heading.dataset.dateLabel}`
    })
    const warning = content.querySelector<HTMLElement>('.accounting-old-shift')
    if (warning) warning.hidden = !oldShift(today)
    window.scrollTo(x, y)
  }
  const pauseCalendar = () => { window.clearTimeout(calendarTimer); calendarTimer = undefined }
  const checkCalendar = () => {
    pauseCalendar()
    if (disposed || denied || document.visibilityState === 'hidden') return
    if (!currentActor()) { loseAccess(); return }
    refreshCalendar()
    // Align low-frequency checks to minute boundaries; each check uses the actual venue day, including DST.
    if (report) calendarTimer = window.setTimeout(checkCalendar, 60_000 - Date.now() % 60_000)
  }
  const navigate = (params: Record<string, string> = {}) => {
    if (viewState && content.querySelector('[data-accounting-section=entries]')) { viewState.scroll = window.scrollY; saveView() }
    if (params.list === 'sales' || params.list === 'payments') {
      const state = restoreView(); state.mode = listMode = params.list
      if (params.filter) state.filter = salesFilter = params.filter
      state.scroll = 0; saveView()
    }
    const q = new URLSearchParams({ av: String(venueId), ...(report ? { shift: String(report.shiftId) } : {}), filter: salesFilter, list: listMode, ...params })
    const target = `#/accounting?${q}`
    if (location.hash === target) render()
    else location.hash = target
  }
  const currentActor = () => (getTelegramContext().telegramUserId ?? identity) === identity
  const loseAccess = () => { pauseCalendar(); storageRemove(viewKey); viewState = null; denied = true; editor = null; report = null; categories = []; tables = []; content.replaceChildren(el('p', { text: 'Доступ изменился. Прежние данные закрыты. Вернитесь к выбору заведения.' })) }
  const showError = (value: ApiErrorInfo | Error) => {
    if (disposed) return
    error.hidden = false
    error.textContent = value instanceof Error ? value.message
      : value.status === 403 ? 'Доступ отозван или действие недоступно вашей роли. Вернитесь к выбору заведения.'
        : ['ACCOUNTING_CONFLICT', 'INVALID_INPUT'].includes(value.code ?? '') ? value.message ?? 'Проверьте данные.'
          : 'Не удалось подтвердить операцию. Обновите отчёт; повтор с теми же данными не создаёт вторую запись.'
  }
  const run = async (operation: PendingCommand) => {
    if (saving || disposed || denied) return
    if (!currentActor()) { loseAccess(); return }
    const pending = storageRead<PendingCommand>(pendingKey)
    if (pending && pending.body.requestId !== operation.body.requestId) { render(); return }
    const { body } = operation
    // Upgrade old pending records only when the legacy draft still matches their submitted input.
    if (!operation.draftCopies && operation.draftKey) {
      const keys = [operation.draftKey, ...(body.action === 'CREATE' ? [firstDraftKey] : [])]
      operation = { ...operation, draftCopies: [...new Set(keys)].filter(key => key.startsWith(`${scope}:draft:`))
        .flatMap(key => { const saved = readDraft(key); return saved && matchesLegacyCommand(saved.draft, body) ? saved.copies : [] }) }
    }
    const pendingCopy = { key: pendingKey, value: JSON.stringify(operation) }
    if (!storageWrite(pendingKey, operation)) {
      (editor?.showError ?? showError)(new Error('Операция не отправлена: не удалось сохранить данные для безопасного повтора. Оставьте форму открытой, освободите место в хранилище браузера и повторите сохранение.'))
      return
    }
    saving = true; error.hidden = true
    const disabled = Array.from(content.querySelectorAll<HTMLButtonElement | HTMLInputElement | HTMLSelectElement>('button,input,select')).map(node => ({ node, before: node.disabled }))
    disabled.forEach(({ node }) => { node.disabled = true }); editor?.setSaving(true)
    const result = await accountingCommand(backendUrl, venueId, body, deps)
    saving = false
    disabled.forEach(({ node, before }) => { if (node.isConnected) node.disabled = before }); editor?.setSaving(false)
    if (!currentActor()) { loseAccess(); return }
    if (result.ok) {
      removeUnchanged(pendingCopy)
      operation.draftCopies?.filter(copy => copy.key.startsWith(`${scope}:draft:`)).forEach(removeUnchanged)
      if (disposed) return
      report = result.data
      if (body.action === 'RECONCILE_TEXT') { storageWrite(noticeKey, 'Сверка записана. Полученные деньги подтверждаются отдельной оплатой.'); navigate({ sale: body.saleId! }) }
      else if (body.action === 'CREATE') {
        storageWrite(noticeKey, 'Продажа сохранена. Полученные деньги запишите отдельной оплатой.'); storageWrite(`${scope}:saved-sale`, result.data.createdSaleId ?? 'choose'); navigate()
      }
      else if (body.action === 'PAY' || body.action === 'PAY_SPLIT') {
        const sale = result.data.sales.find(s => s.id === body.saleId)
        const added = sale?.payments.filter(p => p.id === body.requestId || p.id.startsWith(`${body.requestId}:`)) ?? []
        successNotice = `Оплата записана: ${added.map(p => `${money(p.amountMinor)} · ${paymentNames[p.method] ?? p.method}`).join(' + ')}. ${sale ? `Осталось оплатить ${money(Math.max(0, salePayable(sale) - salePaid(sale)))}.` : 'Обновите запись для проверки остатка.'}`; render()
      }
      else { successNotice = 'Изменения сохранены.'; render() }
    } else {
      if ([400, 401, 403, 404, 409, 422].includes(result.error.status)) removeUnchanged(pendingCopy)
      if (disposed) return
      if ([401, 403].includes(result.error.status)) { loseAccess(); showError(result.error); return }
      if ([400, 404, 409, 422].includes(result.error.status) && editor) editor.showError(result.error)
      else { render(); if (!storageRead(pendingKey)) showError(result.error) }
    }
  }
  const submit = (body: Omit<AccountingCommand, 'requestId'>, draftCopies?: StoredCopy[]) => {
    if (saving) return
    if (storageRead(pendingKey)) { render(); return }
    void run({ body: { ...body, requestId: crypto.randomUUID() }, draftCopies })
  }
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
  const paymentBlock = (sale: AccountingSale, operation: PaymentOperation, corrections: boolean) => {
    const block = el('section', { className: 'card accounting-payment-operation' })
    block.dataset.paymentOperation = operation.key; block.dataset.saleId = sale.id
    if (operation.requestId) block.dataset.paymentRequestId = operation.requestId
    const original = operation.payments.reduce((sum, p) => sum + p.amountMinor, 0)
    const active = operation.payments.filter(p => !p.voidReason).reduce((sum, p) => sum + p.amountMinor, 0)
    append(block, el('h4', { text: operation.requestId ? `Комбинированная оплата · ${money(original)}` : 'Запись оплаты' }),
      el('p', { className: 'muted', text: date(operation.recordedAt, report!.timezone) }))
    if (operation.payments.some(p => p.voidReason)) block.append(el('p', { className: 'accounting-payment-corrected', text: `Есть исправленные записи · Сейчас учтено ${money(active)}` }))
    operation.payments.forEach(payment => {
      const row = el('div', { className: 'accounting-payment' }); row.dataset.paymentComponent = payment.id
      const label = el('p', { id: `accounting-component-${crypto.randomUUID()}`, text: `${paymentNames[payment.method] ?? payment.method} · ${money(payment.amountMinor)}` })
      row.append(label)
      if (payment.voidReason) row.append(el('p', { className: 'accounting-payment-corrected', text: `Исправлено: ${payment.voidReason}` }))
      if (corrections && !payment.voidReason) {
        const correct = button('Исправить запись оплаты', () => reasonAction(sale, 'VOID_PAYMENT', 'Отменить запись оплаты', 'Это отменит только учётную запись и увеличит остаток. Реальные деньги не возвращаются. Для верной суммы затем запишите новую оплату.', { paymentId: payment.id }))
        correct.setAttribute('aria-describedby', label.id); row.append(correct)
      }
      block.append(row)
    })
    return block
  }
  const closePreview = (opener: HTMLButtonElement) => {
    const current = report
    if (!current || current.status !== 'OPEN' || access.role === 'STAFF' || saving) return
    content.querySelector('.accounting-close-preview')?.remove()
    const box = el('section', { className: 'card accounting-close-preview' })
    const heading = el('h3', { id: 'accounting-close-heading', text: 'Подтверждение закрытия смены' }); heading.tabIndex = -1
    box.setAttribute('aria-labelledby', heading.id)
    append(box, heading, el('p', { text: `Получено ${money(current.totals.paidMinor)}. Не оплачено: ${money(current.totals.unpaidMinor)} (${current.unpaidSaleIds.length} записей). Спорных: ${current.disputedSaleIds.length}. Итог будет сохранён, дальнейшее исправление и запись оплат в этой смене станут недоступны.` }))
    const ack = el('input'); ack.type = 'checkbox'
    const cancel = () => { box.remove(); opener.focus() }
    append(box, field('Проверил неоплаченные и спорные записи; принимаю закрытие с этим остатком', ack), button('Подтвердить закрытие смены', () => submit({ action: 'CLOSE', shiftId: current.shiftId, expectedReportHash: current.reportHash, acknowledgeUnpaid: ack.checked }), false), button('Продолжить смену', cancel))
    box.addEventListener('keydown', event => { if (event.key === 'Escape') { event.preventDefault(); cancel() } })
    content.append(box); heading.focus(); box.scrollIntoView({ block: 'nearest' })
  }
  const lineEditor = (sale?: AccountingSale) => {
    const targetKey = `${scope}:draft:${report?.shiftId ?? 'first'}:${sale?.id ?? 'new'}`
    const blank = (): LineDraft => ({ name: '', qty: '1', price: '', discount: '', discountMode: 'percent', gift: false, giftReason: '' })
    const saved = !sale && report ? adoptFirstDraft(report.shiftId) : readDraft(targetKey)
    const draftKey = saved?.key ?? targetKey
    let draftCopies = saved?.copies ?? []
    const draft: Draft = saved?.draft ?? {
      version: sale?.version, tableId: sale ? String(sale.tableId) : '', reason: '',
      lines: sale ? sale.lines.map((line) => ({ id: line.id, name: line.name, qty: String(line.qty), price: decimal(line.unitPriceMinor), discount: line.discountPercent ?? decimal(line.discountMinor), discountMode: line.discountPercent != null ? 'percent' : 'amount', gift: line.giftReason !== null, giftReason: line.giftReason ?? '' })) : [blank()]
    }
    if (sale && draft.version !== sale.version) {
      append(content, el('p', { text: 'Запись изменилась после начала исправления. Черновик сохранён; проверьте актуальные суммы перед новым исправлением.' }), button('Загрузить актуальную запись вместо черновика', () => { storageRemove(draftKey); render() }), button('К отчёту', () => navigate()))
      return
    }
    const save = () => {
      draft.updatedAt = Date.now(); draft.revision = crypto.randomUUID()
      if (storageWrite(draftKey, draft)) {
        draftCopies = [...draftCopies.filter(copy => copy.key !== draftKey), { key: draftKey, value: JSON.stringify(draft) }]
      }
    }
    editor = accountingSaleEditor({
      draft, sale, tables, categories, digitalSales: report?.sales ?? [], canOpenOrders: access.permissions.includes('ORDER_QUEUE_VIEW'), save,
      submit: lines => submit({ action: sale ? 'EDIT' : 'CREATE', shiftId: report?.shiftId, saleId: sale?.id, expectedVersion: sale?.version, tableId: Number(draft.tableId), lines, reason: draft.reason }, draftCopies),
      back: () => navigate()
    })
    content.append(editor.form)
  }
  const reconciliationEditor = (sale: AccountingSale) => {
    const key = `${scope}:draft:${report!.shiftId}:reconcile:${sale.id}`
    const saved = readDraft(key)
    if (saved?.draft.version !== undefined && saved.draft.version !== sale.version) {
      append(content, el('p', { text: 'Запись изменилась. Черновик сверки сохранён; проверьте актуальное состояние перед повтором.' }), button('Открыть актуальную запись', () => navigate({ sale: sale.id })), button('Начать сверку актуальной записи', () => { storageRemove(key); render() }))
      return
    }
    const draft = { version: sale.version, tableId: String(sale.tableId), lines: [], reason: '', amount: sale.textReconciliation ? decimal(sale.textReconciliation.confirmedAccruedMinor) : '', confirmationSource: sale.textReconciliation?.confirmationSource ?? '', ...saved?.draft }
    let copies = saved?.copies ?? []
    const save = () => { draft.updatedAt = Date.now(); draft.revision = crypto.randomUUID(); if (storageWrite(key, draft)) copies = [{ key, value: JSON.stringify(draft) }] }
    editor = accountingTextReconciliation({ sale, draft, save, back: () => navigate({ sale: sale.id }),
      submit: amountMinor => submit({ action: 'RECONCILE_TEXT', shiftId: report!.shiftId, saleId: sale.id, expectedVersion: sale.version, amountMinor, confirmationSource: draft.confirmationSource, reason: draft.reason }, copies) })
    content.append(editor.form)
  }
  const detail = (sale: AccountingSale) => {
    const box = el('section', { className: 'card' })
    append(box, el('h3', { text: `${sale.tableLabel} · ${sourceNames[sale.source] ?? sale.source}` }),
      el('p', { text: `${reference(sale)} · ${date(sale.createdAt, report!.timezone)}` }))
    const technical = el('details', { className: 'accounting-sale-technical' }); technical.append(el('summary', { text: 'Технические сведения' }), el('p', { text: `ID продажи: ${sale.id}` }))
    sale.payments.forEach(p => technical.append(el('p', { text: `ID записи оплаты: ${p.id}` }))); box.append(technical)
    const remaining = Math.max(0, salePayable(sale) - salePaid(sale))
    if (sale.issue || sale.sourceWarning) box.append(el('p', { text: 'Расчётная сумма недоступна или неполна. Требуется сверка.' }))
    else {
      const totals = el('dl', { className: 'accounting-totals accounting-sale-balances' })
      for (const [label, value] of [['Сумма продажи', salePayable(sale)], ['Уже оплачено', salePaid(sale)], ['Осталось оплатить', remaining]] as const) append(totals, el('dt', { text: label }), el('dd', { text: money(value) }))
      box.append(totals)
      if (!sale.cancelReason && remaining === 0) box.append(el('p', { className: 'accounting-settled', text: salePayable(sale) === 0 && salePaid(sale) === 0 ? 'Оплата не требуется' : 'Оплачено полностью' }))
    }
    if (sale.orderId) {
      const link = el('a', { text: `Открыть заказ №${sale.displayNumber ?? sale.orderId}` }); link.href = `#/order/${sale.orderId}`; box.appendChild(link)
      append(box, el('p', { text: `${sale.originalPayableMinor == null ? 'Исходная расчётная сумма не подтверждена.' : `${sale.sourceWarning || sale.textReconciliation?.originalSourceWarning ? 'Известная часть исходного счёта' : 'Исходный счёт'}: ${money(sale.originalPayableMinor)}.`} Изменения журнала не меняют счёт гостя.` }))
    }
    if (sale.textReconciliation) append(box, el('p', { text: `Сверка записана: подтверждённый неразнесённый итог ${money(sale.textReconciliation.confirmedAccruedMinor)}. Состав, полные скидки и подарки не установлены.` }), el('p', { text: `Основание: ${sale.textReconciliation.confirmationSource}. Причина: ${sale.textReconciliation.reason}. ${date(sale.textReconciliation.recordedAt, report!.timezone)}` }))
    if (sale.textReconciliation && sale.lines.length) box.append(el('p', { text: 'Известные строки исходного счёта. Подтверждённый сверкой итог не разнесён по позициям.' }))
    sale.lines.forEach((line) => append(box, el('p', { text: `${line.name} × ${line.qty} · ${money(line.unitPriceMinor)} = ${money(linePayable(line))}${line.giftReason ? ` · Подарок: ${line.giftReason}` : ''}${line.baseGiftMinor ? ' · Подарок по акции исходного заказа' : ''}${line.baseDiscountMinor + line.discountMinor ? ` · Скидка ${money(line.baseDiscountMinor + line.discountMinor)}` : ''}` })))
    for (const text of [sale.cancelReason && `Отменено: ${sale.cancelReason}`, sale.disputeReason && `Спорная запись: ${sale.disputeReason}`, sale.issue, sale.sourceWarning]) if (text) append(box, el('p', { text }))
    if (sale.textReconciliationAvailable) box.append(el('p', { text: 'Текстовый заказ требует сверки. Состав или стоимость нельзя подтвердить автоматически.' }))
    if (report!.status === 'OPEN' && access.role !== 'STAFF' && !sale.cancelReason && (sale.textReconciliationAvailable || sale.textReconciliation)) box.append(button(sale.textReconciliation ? 'Исправить сверку' : 'Сверить заказ', () => navigate({ sale: sale.id, view: 'reconcile' })))
    content.appendChild(box)
    const editable = report!.status === 'OPEN' && sale.orderClosed && !sale.issue && !sale.sourceWarning
    if (!sale.orderClosed) append(box, el('p', { text: 'Сначала завершите заказ в разделе «Заказы», затем запишите оплату. Завершение заказа само по себе не подтверждает поступление денег.' }))
    const operations = paymentOperations(sale)
    const paymentHistory = el('details', { className: 'card accounting-payment-history' }); paymentHistory.append(el('summary', { text: `Оплаты по этой продаже · операций: ${operations.length}` }))
    operations.forEach(operation => paymentHistory.append(paymentBlock(sale, operation, editable)))
    if (!operations.length) paymentHistory.append(el('p', { text: 'По этой продаже оплата ещё не записана.' }))
    content.append(paymentHistory)
    if (editable && !sale.cancelReason) {
      if (remaining > 0) {
        const key = `${scope}:draft:${report!.shiftId}:payment:${sale.id}`
        const saved = storageRead<PaymentDraft>(key)
        if (saved && saved.version !== sale.version) {
          content.append(el('p', { text: 'Продажа изменилась. Черновик оплаты сохранён. Проверьте актуальный остаток перед новой записью.' }), button('Начать оплату актуального остатка', () => { storageRemove(key); render() }))
        } else {
          const draft: PaymentDraft = saved ?? { version: sale.version, method: 'CASH', partial: false, amount: decimal(remaining), components: {}, acknowledge: false }
          let copies: StoredCopy[] = saved ? [{ key, value: JSON.stringify(saved) }] : []
          const save = () => { if (storageWrite(key, draft)) copies = [{ key, value: JSON.stringify(draft) }] }
          editor = accountingPaymentEditor({ remaining, draft, save, submit: extra => submit({ action: 'PAY', shiftId: report!.shiftId, saleId: sale.id, expectedVersion: sale.version, ...extra }, copies) })
          content.append(editor.form)
        }
      }
      if (!sale.textReconciliation) append(content, button('Исправить продажу', () => navigate({ sale: sale.id, view: 'edit' })), button('Отменить продажу', () => reasonAction(sale, 'CANCEL', 'Отменить продажу', 'Сумма записи станет нулевой; история сохранится. Сначала необходимо исправить её активные оплаты.')))
    }
    if (editable && sale.textReconciliation && !sale.cancelReason) content.append(button('Отменить продажу', () => reasonAction(sale, 'CANCEL', 'Отменить продажу', 'Отменяется только учётная запись; исходный заказ и реальные деньги не меняются.')))
    if (editable && !sale.textReconciliation) content.appendChild(button(sale.disputeReason ? 'Разрешить спор' : 'Отметить спорной', () => reasonAction(sale, sale.disputeReason ? 'RESOLVE' : 'DISPUTE', 'Сверка записи', 'Укажите причину и результат сверки. Это не изменяет сумму или реальные деньги.')))
    append(content, button('Обновить запись', () => void load()), button('К отчёту', () => navigate()))
  }
  const render = () => {
    if (disposed || denied) return
    if (!currentActor()) { loseAccess(); return }
    editor = null; content.replaceChildren()
    calendarDay = report ? venueDay(new Date().toISOString(), report.timezone) : null
    saleNumbers = new Map(orderedSales(report?.sales ?? []).reverse().map((sale, index) => [sale.id, index + 1]))
    if (successNotice) { const notice = el('p', { className: 'accounting-success', text: successNotice }); notice.setAttribute('role', 'status'); content.append(notice) }
    if (savedSaleId && report && !selectedSale) content.append(savedSaleId !== 'choose'
      ? button('Перейти к записи оплаты', () => navigate({ sale: savedSaleId! }), false)
      : button('Выбрать продажу для оплаты', () => {
        const state = restoreView()
        state.search = ''; state.salesLimit = 20; state.returnSale = undefined; state.returnOperation = undefined
        savedSaleId = null
        navigate({ list: 'sales', filter: 'all' })
      }, false))
    const pending = storageRead<PendingCommand>(pendingKey)
    if (pending) {
      const box = el('section', { className: 'card accounting-pending' }); box.setAttribute('role', 'alert')
      append(box, el('p', { text: 'Результат предыдущей операции ещё не подтверждён. Повтор безопасен: сохранён тот же ключ и те же данные.' }), el('p', { text: ['PAY', 'PAY_SPLIT'].includes(pending.body.action) ? 'Оплата могла быть записана. Результат не подтверждён; сохранены прежний запрос и черновик. Не записывайте новую оплату до проверки.' : 'Продажа могла сохраниться. Черновик сохранён; не создавайте новую операцию до проверки результата.' }), button('Проверить и повторить операцию', () => void run(pending)))
      content.append(box)
      return
    }
    if (!report) {
      append(content, el('p', { text: 'Добавьте первую продажу. Учётная смена откроется автоматически при сохранении продажи или первом заказе гостя. Просмотр экрана смену не открывает.' }))
      if (view === 'new') { lineEditor(); return }
      append(content, button('Добавить продажу', () => navigate({ view: 'new' }), false))
      const totals = el('details', { className: 'card accounting-shift-breakdown' }); totals.dataset.accountingSection = 'totals'
      append(totals, el('summary', { text: 'Подробный итог текущей смены' }), el('p', { text: 'Итог появится после первой сохранённой продажи или заказа.' }))
      const entries = el('section'); entries.dataset.accountingSection = 'entries'
      append(entries, el('h3', { text: 'Продажи и оплаты текущей смены' }), el('p', { text: 'Открытой смены пока нет. Продажи и оплаты ещё не записаны.' }))
      append(content, totals, entries)
    } else {
      const shift = el('section', { className: 'card accounting-shift-summary' })
      append(shift, el('h3', { text: report.status === 'OPEN' ? 'Текущая смена · OPEN' : 'Закрытая смена · CLOSED' }), el('p', { text: `Открыта ${date(report.openedAt, report.timezone)}` }))
      if (report.closedAt) shift.append(el('p', { text: `Закрыта ${date(report.closedAt, report.timezone)}` }))
      shift.append(el('p', { className: 'muted', text: `Время заведения: ${report.timezone || 'часовой пояс не передан'}` }))
      const today = calendarDay
      const shiftCount = el('p', { text: `Продаж за всю смену: ${report.totals.salesCount}` }); shiftCount.dataset.shiftCount = String(report.totals.salesCount); shift.append(shiftCount)
      if (report.status === 'OPEN') {
        const daily = el('p'); updateDailyCount(daily, today); shift.append(daily)
        shift.append(el('p', { className: 'muted', text: 'Счётчики не включают отменённые продажи. Смена охватывает всё время с открытия, даже несколько дат.' }))
        if (report.sales.some(s => !venueDay(s.createdAt, report!.timezone))) shift.append(el('p', { className: 'muted', text: 'Записи без достоверной даты видны отдельно и не включены в счётчик за сегодня.' }))
      }
      const primary = el('dl', { className: 'accounting-totals accounting-primary-totals' })
      for (const [label, value] of [['Сумма продаж', report.totals.accruedMinor], ['Получено по учёту', report.totals.paidMinor], ['Осталось оплатить', report.totals.unpaidMinor]] as const) append(primary, el('dt', { text: label }), el('dd', { text: money(value) }))
      shift.append(primary)
      shift.append(el('p', { className: 'muted', text: 'Сумма — после скидок и подарков. Получено — записи сотрудника, не банковская сверка.' }))
      content.append(shift)
      if (report.unpaidSaleIds.length || report.disputedSaleIds.length || report.sales.some(s => s.issue || s.sourceWarning)) content.append(el('p', { className: 'accounting-attention', text: `Не оплачены полностью: ${report.unpaidSaleIds.length} · Спорных: ${report.disputedSaleIds.length} · Требуют сверки суммы: ${report.sales.filter(s => s.issue || s.sourceWarning).length}. Эти записи сохранены в списке.` }))
      if (report.status === 'CLOSED') content.append(el('p', { text: 'Сохранённый итог. Редактирование финансовых записей закрытой смены запрещено.' }))
      if (report.status === 'OPEN') {
        const warning = el('p', { className: 'error-card accounting-old-shift', text: `Смена открыта с ${date(report.openedAt, report.timezone)}. Она началась не сегодня. Новые продажи продолжают попадать в неё; полночь смену не закрывает. Если прошлый рабочий период завершён, владелец или менеджер должен сверить и закрыть смену.` }); warning.hidden = !oldShift(today); content.append(warning)
      }
      const sale = report.sales.find((item) => item.id === selectedSale)
      if (report.status === 'OPEN' && view === 'reconcile' && sale && access.role !== 'STAFF' && !sale.cancelReason && (sale.textReconciliationAvailable || sale.textReconciliation)) { reconciliationEditor(sale); return }
      if (report.status === 'OPEN' && (view === 'new' || (view === 'edit' && sale && sale.orderClosed && !sale.issue && !sale.sourceWarning && !sale.cancelReason && !sale.textReconciliation))) { lineEditor(sale); return }
      if (sale) { detail(sale); return }
      if (selectedSale) { append(content, el('p', { text: 'Продажа недоступна в этом отчёте. Обновите данные или выберите запись в списке.' }), button('Обновить отчёт', () => void load()), button('К списку продаж', () => navigate())); return }
      const state = restoreView()
      const t = report.totals
      const unknown = report.sales.filter(s => s.issue).length
      if (unknown) content.append(el('p', { text: `${unknown} записей без достоверного снимка: суммы не включены в итог. Нужна сверка перед закрытием.` }))
      if (report.sales.some(s => s.sourceWarning)) content.append(el('p', { className: 'error-card', text: 'Есть текстовые части заказов без состава и цен. Итоги содержат известную часть; полнота суммы не подтверждена. Сверьте исходные заказы перед оплатой и закрытием.' }))
      if (report.status === 'OPEN') {
        const actions = el('div', { className: 'accounting-shift-actions' })
        actions.append(button('Добавить продажу', () => navigate({ view: 'new' }), false))
        if (access.role !== 'STAFF') { const close = button('Сверить и закрыть смену', () => closePreview(close)); actions.append(close) }
        actions.append(button('Обновить отчёт', () => void load())); content.append(actions)
      }
      const summary = el('section', { className: 'accounting-shift-totals' })
      summary.dataset.accountingSection = 'totals'
      const more = el('details', { className: 'card accounting-shift-breakdown' }); more.open = state.totalsOpen; more.ontoggle = () => { state.totalsOpen = more.open; saveView() }; more.append(el('summary', { text: report.status === 'OPEN' ? 'Подробный итог текущей смены' : 'Итог закрытой смены' }), el('p', { text: `Цифровых заказов: ${t.ordersCount}` }))
      const detailMetrics = el('dl', { className: 'accounting-totals' })
      for (const [label, value] of [['Стоимость позиций', t.grossMinor], ['Скидки', t.discountMinor], ['Подарки / бесплатно', t.giftMinor], ['Наличные', t.cashMinor], ['Карты', t.cardMinor], ['Переводы', t.transferMinor]] as const) append(detailMetrics, el('dt', { text: label }), el('dd', { text: money(value) }))
      if (t.financialDetailIncomplete) { append(detailMetrics, el('dt', { text: 'Сверено без товарной расшифровки' }), el('dd', { text: money(t.unallocatedAccruedMinor ?? 0) })); summary.append(el('p', { text: 'Начисления включают подтверждённые неразнесённые итоги. Полнота товарной детализации не подтверждена.' })) }
      more.append(detailMetrics)
      for (const [label, rows] of [['По столам', report.byTable], ['По позициям', report.byItem], ['По источникам', report.bySource]] as const) {
        const section = el('details'); section.append(el('summary', { text: label }))
        rows.forEach(row => section.append(el('p', { text: row.label === 'TEXT_CONFIRMED_UNALLOCATED' ? `Сверено без товарной расшифровки · ${money(row.accruedMinor)}` : `${sourceNames[row.label] ?? row.label} · ${row.qty} · ${money(row.accruedMinor)}` }))); more.append(section)
      }
      summary.append(more); content.append(summary)
      const entries = accountingShiftEntries({ report, state, reference, paymentBlock, tableNumber: sale => tables.find(t => t.tableId === sale.tableId)?.tableNumber,
        save: () => {
          salesFilter = state.filter; listMode = state.mode; saveView()
        },
        openSale: (id, operation) => { state.returnSale = id; state.returnOperation = operation; navigate({ sale: id }) }
      })
      entries.dataset.accountingSection = 'entries'; content.append(entries)
    }
    if (access.role !== 'STAFF') {
      const state = restoreView()
      const archive = el('details', { className: 'card accounting-archive' }); archive.dataset.accountingSection = 'archive'; archive.open = state.archiveOpen
      archive.ontoggle = () => { state.archiveOpen = archive.open; saveView() }
      const heading = el('summary', { text: 'Архив закрытых смен' }), body = el('div', { className: 'accounting-archive-list' })
      const drawHistory = () => {
        body.replaceChildren()
        if (!history.length) body.append(el('p', { text: report?.status === 'OPEN' ? 'Закрытых смен пока нет. Текущая смена открыта и появится здесь только после сверки и закрытия.' : 'Закрытых смен пока нет.' }))
        history.forEach(shift => body.append(button(`${date(shift.openedAt, shift.timezone)} — ${shift.closedAt ? date(shift.closedAt, shift.timezone) : ''} · ${shift.status}`, () => navigate({ shift: String(shift.id), sale: '', view: '' }))))
        if (nextHistoryBeforeId) {
          const more = button('Ещё закрытые смены', () => {
            more.disabled = true
            void accountingState(backendUrl, venueId, deps, abort.signal, nextHistoryBeforeId!).then(result => {
              if (disposed || denied) return
              if (!currentActor()) { loseAccess(); return }
              more.disabled = false
              if (!result.ok) { if ([401, 403].includes(result.error.status)) loseAccess(); showError(result.error); return }
              history = [...history, ...result.data.history.filter(shift => !history.some(old => old.id === shift.id))]
              nextHistoryBeforeId = result.data.nextHistoryBeforeId ?? null
              const scroll = window.scrollY; drawHistory(); window.scrollTo(window.scrollX, scroll)
              body.querySelector<HTMLButtonElement>('button:last-child')?.focus({ preventScroll: true })
            })
          }); body.append(more)
        }
      }
      drawHistory(); append(archive, heading, body); content.append(archive)
    }
    if (report?.status === 'CLOSED') content.appendChild(button('К текущей смене', () => {
      const target = `#/accounting?av=${venueId}`
      if (location.hash === target) void load()
      else location.hash = target
    }))
    if (viewState?.returnSale) {
      const operation = Array.from(content.querySelectorAll<HTMLElement>('.accounting-shift-payments [data-payment-operation]')).find(n => n.dataset.saleId === viewState!.returnSale && n.dataset.paymentOperation === viewState!.returnOperation)
      const target = operation?.querySelector('button') ?? Array.from(content.querySelectorAll<HTMLButtonElement>('.accounting-sales [data-sale-id]')).find(n => n.dataset.saleId === viewState!.returnSale)
      target?.focus({ preventScroll: true })
    }
    if (viewState?.scroll !== undefined) window.scrollTo(window.scrollX, viewState.scroll)
  }
  const load = async () => {
    const version = ++loadVersion
    if (viewState && content.querySelector('[data-accounting-section=entries]')) { viewState.scroll = window.scrollY; saveView() }
    const state = await accountingState(backendUrl, venueId, deps, abort.signal)
    if (disposed || version !== loadVersion) return
    if (!currentActor()) { loseAccess(); return }
    if (!state.ok) {
      if ([401, 403].includes(state.error.status)) { loseAccess(); showError(state.error) }
      else showError(new Error(report ? 'Не удалось обновить отчёт. Показаны прежние данные; их актуальность не подтверждена. Повторите обновление.' : 'Не удалось загрузить отчёт. Повторно откройте раздел.'))
      return
    }
    history = state.data.history
    nextHistoryBeforeId = state.data.nextHistoryBeforeId ?? null
    report = state.data.current
    if (report && !storageRead(pendingKey)) adoptFirstDraft(report.shiftId)
    if (requestedShift && requestedShift !== report?.shiftId) {
      const result = await accountingReport(backendUrl, venueId, requestedShift, deps, abort.signal)
      if (disposed || version !== loadVersion) return
      if (!result.ok) { if ([401, 403].includes(result.error.status)) loseAccess(); showError(result.error); return }
      report = result.data
    }
    if (!catalogUnavailable) error.hidden = true
    render(); checkCalendar()
  }
  void Promise.all([venueGetTables(backendUrl, venueId, deps, abort.signal), venueGetMenu(backendUrl, venueId, deps, abort.signal)]).then(([t, m]) => {
    if (disposed) return
    if (t.ok) tables = t.data.tables
    if (m.ok) categories = m.data.categories
    catalogUnavailable = !t.ok || !m.ok
    if (catalogUnavailable) showError(new Error('Не удалось загрузить столы или меню. Обновите раздел перед добавлением продажи.'))
    void load()
  })
  const identityWatch = window.setInterval(() => { if (!currentActor()) loseAccess() }, 1000)
  const telegramApp = getTelegramContext().webApp
  const webApp = telegramApp?.onEvent && telegramApp.offEvent ? telegramApp : null
  document.addEventListener('visibilitychange', checkCalendar)
  window.addEventListener('pageshow', checkCalendar); window.addEventListener('pagehide', pauseCalendar)
  webApp?.onEvent?.('activated', checkCalendar); webApp?.onEvent?.('deactivated', pauseCalendar)
  return () => {
    disposed = true; abort.abort(); window.clearInterval(identityWatch); pauseCalendar()
    document.removeEventListener('visibilitychange', checkCalendar)
    window.removeEventListener('pageshow', checkCalendar); window.removeEventListener('pagehide', pauseCalendar)
    webApp?.offEvent?.('activated', checkCalendar); webApp?.offEvent?.('deactivated', pauseCalendar)
  }
}
