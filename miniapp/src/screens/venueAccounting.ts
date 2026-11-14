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
const paymentNames: Record<string, string> = { CASH: 'Наличные', CARD: 'Карта', TRANSFER: 'Перевод' }
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
  let editor: ReturnType<typeof accountingSaleEditor> | null = null
  let denied = false
  let saving = false
  const identity = options.userId ?? getTelegramContext().telegramUserId ?? 'unknown'
  const scope = `hookah:accounting:${location.host}:${identity}:${venueId}`
  const pendingKey = `${scope}:pending`
  const noticeKey = `${scope}:notice`
  let successNotice = storageRead<string>(noticeKey); storageRemove(noticeKey)
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
  const currentActor = () => (getTelegramContext().telegramUserId ?? identity) === identity
  const loseAccess = () => { denied = true; editor = null; report = null; categories = []; tables = []; content.replaceChildren(el('p', { text: 'Доступ изменился. Прежние данные закрыты. Вернитесь к выбору заведения.' })) }
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
      else if (body.action === 'CREATE') { storageWrite(noticeKey, 'Продажа сохранена. Полученные деньги запишите отдельной оплатой.'); navigate() }
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
      el('p', { text: sale.issue ? 'Расчётная сумма недоступна и не включена в итог.' : `Начислено ${money(salePayable(sale))} · Оплачено ${money(salePaid(sale))} · Остаток ${money(Math.max(0, salePayable(sale) - salePaid(sale)))}` }))
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
    if (successNotice) { const notice = el('p', { className: 'accounting-success', text: successNotice }); notice.setAttribute('role', 'status'); content.append(notice) }
    const pending = storageRead<PendingCommand>(pendingKey)
    if (pending) {
      const box = el('section', { className: 'card accounting-pending' }); box.setAttribute('role', 'alert')
      append(box, el('p', { text: 'Результат предыдущей операции ещё не подтверждён. Повтор безопасен: сохранён тот же ключ и те же данные.' }), el('p', { text: 'Продажа могла сохраниться. Черновик сохранён; не создавайте новую операцию до проверки результата.' }), button('Проверить и повторить операцию', () => void run(pending)))
      content.append(box)
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
      if (report.status === 'OPEN' && view === 'reconcile' && sale && access.role !== 'STAFF' && !sale.cancelReason && (sale.textReconciliationAvailable || sale.textReconciliation)) { reconciliationEditor(sale); return }
      if (report.status === 'OPEN' && (view === 'new' || (view === 'edit' && sale && sale.orderClosed && !sale.issue && !sale.sourceWarning && !sale.cancelReason && !sale.textReconciliation))) { lineEditor(sale); return }
      if (sale) { detail(sale); return }
      const t = report.totals
      const unknown = report.sales.filter((s) => s.issue).length
      if (unknown) append(content, el('p', { text: `${unknown} записей без достоверного снимка: суммы не включены в итог. Нужна сверка перед закрытием.` }))
      if (report.sales.some(s => s.sourceWarning)) append(content, el('p', { className: 'error-card', text: 'Есть текстовые части заказов без состава и цен. Итоги содержат известную часть; полнота суммы не подтверждена. Сверьте исходные заказы перед оплатой и закрытием.' }))
      if (report.status === 'OPEN') append(content, button('Добавить продажу', () => navigate({ view: 'new' }), false), button('Обновить отчёт', () => void load()))
      append(content, el('p', { text: `Продаж: ${t.salesCount} · Цифровых заказов: ${t.ordersCount}` }), el('h3', { text: 'Расчётные суммы' }))
      const metrics = el('dl', { className: 'accounting-totals' })
      for (const [label, value] of [['Стоимость позиций', t.grossMinor], ['Скидки', t.discountMinor], ['Подарки / бесплатно', t.giftMinor], ['Начислено к оплате', t.accruedMinor], ['Подтверждено оператором', t.paidMinor], ['Неоплаченный остаток', t.unpaidMinor], ['Наличные', t.cashMinor], ['Карты', t.cardMinor], ['Переводы', t.transferMinor]] as const) append(metrics, el('dt', { text: label }), el('dd', { text: money(value) }))
      if (t.financialDetailIncomplete) { append(metrics, el('dt', { text: 'Сверено без товарной расшифровки' }), el('dd', { text: money(t.unallocatedAccruedMinor ?? 0) })); content.append(el('p', { text: 'Начисления включают подтверждённые неразнесённые итоги. Стоимость позиций, скидки и подарки показаны только по известным данным; полнота детализации не подтверждена.' })) }
      append(content, metrics, el('p', { text: 'Начислено — сумма учётных записей. Подтверждено оператором — записанные внешние поступления; это не банковская сверка.' }))

      for (const [label, rows] of [['По столам', report.byTable], ['По позициям', report.byItem], ['По источникам', report.bySource]] as const) {
        const section = el('details', { className: 'card' }); section.appendChild(el('summary', { text: label }))
        rows.forEach((row) => append(section, el('p', { text: row.label === 'TEXT_CONFIRMED_UNALLOCATED' ? `Сверено без товарной расшифровки · ${money(row.accruedMinor)}` : `${sourceNames[row.label] ?? row.label} · ${row.qty} · ${money(row.accruedMinor)}` })))
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
    if (!state.ok) { if ([401, 403].includes(state.error.status)) loseAccess(); showError(state.error); return }
    history = state.data.history
    nextHistoryBeforeId = state.data.nextHistoryBeforeId ?? null
    report = state.data.current
    if (report && !storageRead(pendingKey)) adoptFirstDraft(report.shiftId)
    if (requestedShift && requestedShift !== report?.shiftId) {
      const result = await accountingReport(backendUrl, venueId, requestedShift, deps, abort.signal)
      if (disposed) return
      if (!result.ok) { if ([401, 403].includes(result.error.status)) loseAccess(); showError(result.error); return }
      report = result.data
    }
    render()
  }
  void Promise.all([venueGetTables(backendUrl, venueId, deps, abort.signal), venueGetMenu(backendUrl, venueId, deps, abort.signal)]).then(([t, m]) => {
    if (disposed) return
    if (t.ok) tables = t.data.tables
    if (m.ok) categories = m.data.categories
    if (!t.ok || !m.ok) showError(new Error('Не удалось загрузить столы или меню. Обновите раздел перед добавлением продажи.'))
    void load()
  })
  const identityWatch = window.setInterval(() => { if (!currentActor()) loseAccess() }, 1000)
  return () => { disposed = true; abort.abort(); window.clearInterval(identityWatch) }
}
