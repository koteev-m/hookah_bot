import type { AccountingLineInput, AccountingSale } from '../shared/api/accounting'
import type { VenueMenuCategoryDto, VenueTableDto } from '../shared/api/venueDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { append, el } from '../shared/ui/dom'
import { exactMoney } from '../shared/ui/venueStatsFormat'

export type LineDraft = { id?: string; menuItemId?: number; name: string; qty: string; price: string; discount: string; discountMode?: 'percent' | 'amount'; gift: boolean; giftReason: string; categoryKey?: string; search?: string }
export type SaleDraft = { tableId: string; lines: LineDraft[]; reason: string }
export const accountingDecimal = (value: number) => `${Math.floor(value / 100)}.${String(value % 100).padStart(2, '0')}`
export function accountingMinor(text: string): number {
  const match = /^(\d{1,10})(?:[.,](\d{1,2}))?$/.exec(text.trim())
  if (!match) throw new Error('Введите сумму в рублях, не более двух знаков после запятой.')
  return Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'))
}
export function accountingPercent(text: string): number {
  if (!text.trim()) return 0
  const match = /^(\d{1,3})(?:[.,](\d{1,2}))?$/.exec(text.trim())
  const basis = match ? Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0')) : -1
  if (basis < 0 || basis > 10000) throw new Error('Введите процент от 0 до 100, не более двух знаков после запятой.')
  return basis
}
const money = (minor: bigint | number) => exactMoney(String(minor), 'RUB').replace(/RUB$/, '₽')
const mode = (line: LineDraft) => line.discountMode ?? 'amount'
function amounts(line: LineDraft, original?: AccountingSale['lines'][number]) {
  if (!/^\d+$/.test(line.qty) || Number(line.qty) < 1 || Number(line.qty) > 1000) throw new Error('Количество должно быть целым от 1 до 1000.')
  const price = accountingMinor(line.price)
  if (price > 1_000_000_000) throw new Error('Цена не должна превышать 10 000 000 ₽.')
  const gross = BigInt(price) * BigInt(line.qty)
  const basis = mode(line) === 'percent' ? accountingPercent(line.discount) : null
  const discount = basis != null ? (gross * BigInt(basis) + 5000n) / 10000n : BigInt(line.discount.trim() ? accountingMinor(line.discount) : 0)
  const baseDiscount = BigInt(original?.baseDiscountMinor ?? 0), baseGift = BigInt(original?.baseGiftMinor ?? 0)
  if (basis != null && (baseDiscount > 0n || baseGift > 0n)) throw new Error('Процент недоступен: в исходном заказе уже есть скидка или подарок.')
  if (discount > gross - baseDiscount - baseGift) throw new Error('Скидка превышает доступную сумму позиции.')
  if ((baseDiscount > 0n && discount > 0n) || (line.gift && (discount > 0n || (basis ?? 0) > 0 || baseDiscount > 0n))) throw new Error('Скидки и подарок одной позиции не суммируются.')
  return { price, gross, discount, basis, total: line.gift ? 0n : gross - baseDiscount - baseGift - discount }
}
type Options = { draft: SaleDraft; sale?: AccountingSale; tables: VenueTableDto[]; categories: VenueMenuCategoryDto[]; digitalSales: AccountingSale[]; canOpenOrders: boolean; save: () => void; submit: (lines: AccountingLineInput[]) => void; back: () => void }
type Field = { node: HTMLInputElement | HTMLSelectElement; message: HTMLElement; row?: HTMLDetailsElement }
export function accountingSaleEditor(options: Options) {
  const { draft, sale } = options
  const form = el('form', { className: 'card accounting-editor' }); form.noValidate = true
  const fields = new Map<string, Field>();
  const footer = el('p', { className: 'error-card accounting-submit-error' }); footer.hidden = true; footer.setAttribute('role', 'alert')
  const progress = el('p', { className: 'accounting-save-status' }); progress.setAttribute('role', 'status')
  const total = el('p', { className: 'accounting-sale-total' }); total.setAttribute('aria-live', 'polite')
  const rows = el('div'); const reasonBox = el('div'); const reason = el('input'); reason.maxLength = 500
  let reasonLabel: HTMLLabelElement
  const addField = (parent: HTMLElement, key: string, label: string, node: HTMLInputElement | HTMLSelectElement, row?: HTMLDetailsElement) => {
    const wrap = el('div', { className: 'accounting-field' }); node.id ||= `accounting-${crypto.randomUUID()}`
    const title = el('label', { text: label }); title.htmlFor = node.id
    const message = el('p', { className: 'accounting-field-error' }); message.id = `${node.id}-error`; message.hidden = true
    node.setAttribute('aria-describedby', message.id); node.setAttribute('aria-invalid', 'false')
    append(wrap, title, node, message); parent.append(wrap); fields.set(key, { node, message, row }); return title
  }
  const clearErrors = () => { fields.forEach(f => { f.message.hidden = true; f.node.setAttribute('aria-invalid', 'false') }); footer.hidden = true }
  const edited = (key: string) => {
    const field = fields.get(key)
    if (field) { field.message.hidden = true; field.node.setAttribute('aria-invalid', 'false') }
    if ([...fields.values()].every(f => f.message.hidden)) footer.hidden = true
    else footer.textContent = 'Проверьте выделенные поля.'
  }
  const invalid = (key: string, message: string) => { const f = fields.get(key); if (f) { f.message.textContent = message; f.message.hidden = false; f.node.setAttribute('aria-invalid', 'true') }; return f }
  const focusError = (f?: Field) => { if (f) { if (f.row) f.row.open = true; const focus = f.node.disabled ? f.message : f.node; if (f.node.disabled) f.message.tabIndex = -1; focus.focus({ preventScroll: true }); focus.scrollIntoView({ block: 'center' }) } else { footer.tabIndex = -1; footer.focus(); footer.scrollIntoView({ block: 'center' }) } }
  const makeButton = (text: string, action: () => void) => { const b = el('button', { text, className: 'button-secondary' }); b.type = 'button'; b.onclick = action; return b }
  const table = el('select'); table.append(new Option('Выберите стол', ''))
  options.tables.filter(t => t.isActive || t.tableId === sale?.tableId).forEach(t => table.append(new Option(t.tableLabel, String(t.tableId))))
  table.value = draft.tableId; table.disabled = !!sale
  append(form, el('h3', { text: sale ? 'Исправление учётной записи' : 'Добавить продажу по столу' }))
  if (!sale) append(form, el('p', { className: 'accounting-manual-guidance', text: 'Для продаж вне приложения. Заказы из бота и Mini App уже учитываются автоматически. Не добавляйте их повторно.' }))
  addField(form, 'table', 'Стол', table)
  const orderLinks = el('div', { className: 'accounting-digital-links' }); form.append(orderLinks)
  const updateLinks = () => { orderLinks.replaceChildren(); if (!options.canOpenOrders || sale) return; options.digitalSales.filter(s => s.tableId === Number(draft.tableId) && s.orderId).forEach(s => { const a = el('a', { text: `Открыть заказ №${s.displayNumber ?? s.orderId}` }); a.href = `#/order/${s.orderId}`; orderLinks.append(a) }) }
  table.onchange = () => { draft.tableId = table.value; edited('table'); options.save(); updateLinks() }; updateLinks()
  if (sale?.orderId) form.append(el('p', { text: 'Исходный заказ сохраняется. Здесь меняется только учётная скидка или подарок. Цена, количество и счета гостей остаются в исходном заказе.' }))
  if (!sale) form.append(el('p', { className: 'muted', text: 'Для полноценной товарной статистики выбирайте существующую позицию меню. «Позиция не из меню» сохраняется отдельно.' }))
  form.append(rows)
  const original = (line: LineDraft) => sale?.lines.find(l => l.id === line.id)
  const requiresReason = () => !!sale || draft.lines.some(l => { try { return mode(l) === 'percent' ? accountingPercent(l.discount) > 0 : (l.discount.trim() ? accountingMinor(l.discount) : 0) > 0 } catch { return l.discount.trim() !== '' } })
  const updateReason = () => { reasonBox.hidden = !requiresReason(); if (reasonLabel) reasonLabel.textContent = sale ? 'Причина исправления · обязательно' : 'Причина скидки · обязательно' }
  const updateTotal = () => { try { total.textContent = `Итого продажи: ${money(draft.lines.reduce((sum, l) => sum + amounts(l, original(l)).total, 0n))}` } catch { total.textContent = 'Итого продажи: проверьте поля позиций.' }; updateReason() }
  const draw = (openIndex: number) => {
    for (const key of fields.keys()) if (key.startsWith('line:')) fields.delete(key)
    rows.replaceChildren();
    draft.lines.forEach((line, index) => {
      const row = el('details', { className: 'accounting-line' }); row.open = index === openIndex;
      const summary = el('summary'); const preview = el('p', { className: 'accounting-line-preview' }); preview.setAttribute('aria-live', 'polite')
      summary.setAttribute('aria-expanded', String(row.open))
      row.ontoggle = () => { summary.setAttribute('aria-expanded', String(row.open)); if (row.open) Array.from(rows.children).forEach(other => { if (other !== row) (other as HTMLDetailsElement).open = false }) }
      row.append(summary); const body = el('div', { className: 'accounting-line-fields' }); row.append(body)
      const update = () => {
        try { const a = amounts(line, original(line)); preview.textContent = `${line.qty} × ${money(a.price)} = ${money(a.gross)} · Скидка${a.basis != null ? ` ${line.discount.trim() || '0'}%` : ''}: ${money(a.discount)} · Итог: ${money(a.total)}`; summary.textContent = `${line.name || `Позиция ${index + 1}`} × ${line.qty} · ${money(a.total)}` }
        catch { preview.textContent = 'Проверьте количество, цену и скидку.'; summary.textContent = line.name || `Позиция ${index + 1}` }
        updateTotal()
      }
      let refreshSelection = () => {}
      let custom: HTMLButtonElement | undefined
      let selected: HTMLParagraphElement | undefined
      let updateSelected = () => {}
      if (!line.id) {
        const category = el('select'); category.append(new Option('Выберите категорию', ''))
        options.categories.filter(c => c.isActive !== false).forEach(c => category.append(new Option(c.name, String(c.id))))
        category.value = line.categoryKey ?? ''
        const search = el('input'); search.type = 'search'; search.placeholder = 'Поиск по всему меню'; search.value = line.search ?? ''
        const items = el('select'); selected = el('p', { className: 'accounting-selected-item' })
        const available = options.categories.filter(c => c.isActive !== false).flatMap(c => c.items.filter(i => i.isAvailable && i.currency === 'RUB').map(i => ({ ...i, categoryId: c.id, categoryName: c.name })))
        updateSelected = () => { selected!.textContent = line.menuItemId ? `Выбрано: ${line.name}. ${available.some(i => i.id === line.menuItemId) ? '' : 'Позиция больше недоступна. Выберите другую.'}` : 'Позиция не из меню' }
        const choices = () => {
          items.replaceChildren(new Option(line.menuItemId ? 'Выберите другой товар' : 'Позиция не из меню', ''))
          const query = search.value.trim().toLocaleLowerCase('ru')
          available.filter(i => query ? i.name.toLocaleLowerCase('ru').includes(query) : category.value === String(i.categoryId)).forEach(i => items.append(new Option(`${i.name} · ${i.categoryName} · ${money(i.priceMinor)}`, String(i.id))))
          items.value = line.menuItemId && Array.from(items.options).some(o => o.value === String(line.menuItemId)) ? String(line.menuItemId) : ''
          updateSelected()
        }
        category.onchange = () => { line.categoryKey = category.value; line.search = ''; search.value = ''; options.save(); choices() }
        search.oninput = () => { line.search = search.value; options.save(); choices() }
        items.onchange = () => { const item = available.find(i => i.id === Number(items.value)); line.menuItemId = item?.id; line.name = item?.name ?? ''; line.price = item ? accountingDecimal(item.priceMinor) : ''; edited(`line:${index}:item`); edited(`line:${index}:name`); edited(`line:${index}:price`); options.save(); refreshSelection(); update() }
        addField(body, `line:${index}:category`, 'Категория меню', category, row)
        addField(body, `line:${index}:search`, 'Поиск товара по всему меню', search, row)
        addField(body, `line:${index}:item`, 'Товар', items, row); body.append(selected); choices()
        custom = makeButton('Позиция не из меню', () => { line.menuItemId = undefined; line.name = ''; line.price = ''; items.value = ''; options.save(); refreshSelection(); update(); name.focus() }); body.append(custom)
      }
      const textInput = (key: string, value: string, label: string, change: (value: string) => void, inputMode?: 'decimal' | 'numeric') => {
        const node = el('input'); node.value = value; if (inputMode) node.inputMode = inputMode
        node.oninput = () => { change(node.value); edited(`line:${index}:${key}`); options.save(); update() }; addField(body, `line:${index}:${key}`, label, node, row); return node
      }
      const name = textInput('name', line.name, 'Название', v => { line.name = v }); name.maxLength = 200
      const qty = textInput('qty', line.qty, 'Количество', v => { line.qty = v }, 'numeric')
      const price = textInput('price', line.price, 'Цена за единицу, ₽', v => { line.price = v }, 'decimal')
      refreshSelection = () => {
        name.value = line.name; price.value = line.price
        name.disabled = !!line.menuItemId || !!sale?.orderId; price.disabled = name.disabled; qty.disabled = !!sale?.orderId
        if (custom) custom.hidden = !line.menuItemId
        updateSelected()
      }; refreshSelection()
      const restriction = (original(line)?.baseDiscountMinor ?? 0) > 0 || (original(line)?.baseGiftMinor ?? 0) > 0
      const discountMode = el('select'); discountMode.append(new Option('Скидка, %', 'percent'), new Option('Скидка, ₽', 'amount')); discountMode.value = mode(line)
      if (restriction) { discountMode.options[0].disabled = true; body.append(el('p', { className: 'muted', text: 'Процент недоступен: в исходном заказе уже есть скидка или подарок. Условия исходного счёта сохраняются.' })) }
      addField(body, `line:${index}:discountMode`, 'Режим скидки', discountMode, row)
      const modeNotice = el('p', { className: 'accounting-mode-notice' }); modeNotice.hidden = true
      const discount = textInput('discount', line.discount, mode(line) === 'percent' ? 'Скидка, %' : 'Скидка на всю позицию, ₽', v => { line.discount = v }, 'decimal'); discount.placeholder = '0'; discount.onfocus = () => discount.select()
      discountMode.onchange = () => { line.discountMode = discountMode.value as 'percent' | 'amount'; line.discount = ''; discount.value = ''; discount.labels![0].textContent = mode(line) === 'percent' ? 'Скидка, %' : 'Скидка на всю позицию, ₽'; edited(`line:${index}:discount`); options.save(); update(); modeNotice.hidden = false; modeNotice.textContent = 'Режим изменён. Введите скидку в выбранных единицах заново.' }
      body.append(modeNotice)
      const gift = el('input'); gift.type = 'checkbox'; gift.checked = line.gift
      addField(body, `line:${index}:gift`, 'Подарок / бесплатно', gift, row)
      const giftReason = textInput('giftReason', line.giftReason, 'Причина подарка', v => { line.giftReason = v }); giftReason.maxLength = 500; giftReason.parentElement!.hidden = !line.gift
      gift.onchange = () => { line.gift = gift.checked; giftReason.parentElement!.hidden = !line.gift; edited(`line:${index}:giftReason`); options.save(); update() }
      body.append(preview)
      if (!sale?.orderId && draft.lines.length > 1) body.append(makeButton('Убрать позицию', () => { draft.lines.splice(index, 1); options.save(); draw(Math.max(0, index - 1)); rows.querySelectorAll<HTMLElement>('summary')[Math.max(0, index - 1)]?.focus() }))
      rows.append(row); update()
    })
  }
  if (!sale?.orderId) form.append(makeButton('Ещё позиция', () => { draft.lines.push({ name: '', qty: '1', price: '', discount: '', discountMode: 'percent', gift: false, giftReason: '' }); options.save(); draw(draft.lines.length - 1); rows.lastElementChild?.querySelector<HTMLElement>('summary')?.focus() }))
  reason.value = draft.reason; reason.oninput = () => { draft.reason = reason.value; edited('reason'); options.save() }
  reasonLabel = addField(reasonBox, 'reason', sale ? 'Причина исправления · обязательно' : 'Причина скидки · обязательно', reason)
  const send = el('button', { text: sale ? 'Сохранить исправление' : 'Сохранить продажу' }); send.type = 'submit'
  append(form, reasonBox, total, footer, progress, send, makeButton('К отчёту', options.back))
  draw(Math.max(0, draft.lines.length - 1)); updateTotal()
  const showError = (info: ApiErrorInfo | Error) => {
    clearErrors(); const message = info instanceof Error ? info.message : info.message ?? 'Проверьте данные и повторите сохранение.'
    footer.textContent = message; footer.hidden = false
    const details = !(info instanceof Error) && info.details && typeof info.details === 'object' ? info.details as Record<string, unknown> : {}
    const index = Number.isInteger(details.lineIndex) ? Number(details.lineIndex) : -1
    const key = typeof details.field === 'string' ? index >= 0 ? `line:${index}:${details.field}` : details.field : ''
    const target = invalid(key, message)
    if (details.field === 'price' && index >= 0 && Number.isSafeInteger(details.currentUnitPriceMinor) && draft.lines[index]?.menuItemId) {
      const current = Number(details.currentUnitPriceMinor)
      footer.append(makeButton(`Применить цену ${money(current)} и проверить итог`, () => { draft.lines[index].price = accountingDecimal(current); options.save(); clearErrors(); draw(index) }))
    }
    focusError(target)
  }
  form.onsubmit = event => {
    event.preventDefault(); clearErrors(); const problems: { key: string; message: string }[] = []
    if (!draft.tableId) problems.push({ key: 'table', message: 'Выберите стол.' })
    const lines: AccountingLineInput[] = []
    draft.lines.forEach((line, index) => {
      const problem = (field: string, message: string) => problems.push({ key: `line:${index}:${field}`, message })
      if (!line.name.trim() || line.name.trim().length > 200) problem('name', 'Введите название позиции (до 200 символов).')
      if (line.gift && !line.giftReason.trim()) problem('giftReason', 'Укажите причину подарка.')
      if (!/^\d+$/.test(line.qty) || Number(line.qty) < 1 || Number(line.qty) > 1000) problem('qty', 'Количество должно быть целым от 1 до 1000.')
      try { if (accountingMinor(line.price) > 1_000_000_000) throw new Error('Цена не должна превышать 10 000 000 ₽.') } catch (e) { problem('price', (e as Error).message) }
      try {
        const a = amounts(line, original(line))
        if (a.price === 0 && !line.gift && !(original(line)?.baseGiftMinor)) problem('price', 'Для бесплатной позиции обязательна причина подарка.')
        lines.push({ id: line.id, menuItemId: line.menuItemId, name: line.name, qty: Number(line.qty), unitPriceMinor: a.price,
          ...(a.basis != null ? { discountPercent: accountingDecimal(a.basis) } : { discountMinor: Number(a.discount) }), giftReason: line.gift ? line.giftReason : null })
      } catch (e) { if (!problems.some(p => p.key === `line:${index}:qty` || p.key === `line:${index}:price`)) problem('discount', (e as Error).message) }
    })
    if (requiresReason() && !draft.reason.trim()) problems.push({ key: 'reason', message: sale ? 'Укажите причину исправления.' : 'Укажите причину скидки.' })
    if (problems.length) { const first = problems[0]; problems.forEach(p => invalid(p.key, p.message)); footer.textContent = `Проверьте поля. ${first.message}`; footer.hidden = false; focusError(fields.get(first.key)); return }
    options.submit(lines)
  }
  return { form, showError, setSaving(value: boolean) { send.disabled = value; progress.textContent = value ? 'Сохраняем продажу…' : ''; form.setAttribute('aria-busy', String(value)) } }
}
