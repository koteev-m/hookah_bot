import type { AccountingSale } from '../shared/api/accounting'
import type { ApiErrorInfo } from '../shared/api/types'
import { append, el } from '../shared/ui/dom'

export type TextReconciliationDraft = { amount: string; confirmationSource: string; reason: string }
export function textReconciliationMinor(text: string): number {
  const m = /^(\d{1,12})(?:[.,](\d{1,2}))?$/.exec(text.trim())
  const amount = m ? Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0')) : 0
  if (!m || amount < 1 || amount > 10_000_000_000_000) throw new Error('Укажите подтверждённую сумму больше нуля, не более двух знаков после запятой и в пределах лимита учёта.')
  return amount
}
export function accountingTextReconciliation(options: { sale: AccountingSale; draft: TextReconciliationDraft; save: () => void; submit: (amountMinor: number) => void; back: () => void }) {
  const { sale, draft } = options
  const form = el('form', { className: 'card accounting-editor accounting-reconciliation' }); form.noValidate = true
  append(form, el('h3', { text: sale.textReconciliation ? 'Исправить сверку заказа' : 'Сверить текстовый заказ' }),
    el('p', { text: `${sale.tableLabel} · Заказ №${sale.displayNumber ?? sale.orderId}` }))
  const order = el('a', { text: 'Открыть исходный заказ' }); order.href = `#/order/${sale.orderId}`; form.append(order)
  form.append(el('p', { text: 'Укажите подтверждённый итог в рублях и проверяемое основание. Сверка не меняет счёт гостя и не означает оплату. Состав, полные скидки и подарки останутся неизвестными.' }))
  const fields = new Map<string, { node: HTMLInputElement; error: HTMLElement }>()
  const footer = el('p', { className: 'error-card accounting-submit-error' }); footer.hidden = true; footer.setAttribute('role', 'alert')
  const progress = el('p'); progress.setAttribute('role', 'status')
  const field = (key: keyof TextReconciliationDraft, label: string) => {
    const wrap = el('div', { className: 'accounting-field' }), node = el('input'), error = el('p', { className: 'accounting-field-error' })
    node.id = `reconciliation-${crypto.randomUUID()}`; node.value = draft[key]; node.maxLength = key === 'amount' ? 16 : 500
    error.id = `${node.id}-error`; error.hidden = true
    const title = el('label', { text: label }); title.htmlFor = node.id
    node.setAttribute('aria-invalid', 'false'); node.setAttribute('aria-describedby', error.id)
    node.oninput = () => { draft[key] = node.value; error.hidden = true; node.setAttribute('aria-invalid', 'false'); footer.hidden = true; options.save() }
    append(wrap, title, node, error); form.append(wrap); fields.set(key, { node, error }); return node
  }
  const amount = field('amount', 'Подтверждённый итог, ₽'); amount.inputMode = 'decimal'; amount.placeholder = 'Например, 750,00'; amount.onfocus = () => amount.select()
  field('confirmationSource', 'Источник подтверждения · обязательно').placeholder = 'Чек, кассовая запись или другое проверяемое основание'
  field('reason', 'Причина сверки · обязательно')
  const send = el('button', { text: sale.textReconciliation ? 'Сохранить исправление сверки' : 'Записать сверку' }); send.type = 'submit'
  const back = el('button', { text: 'К записи', className: 'button-secondary' }); back.type = 'button'; back.onclick = options.back
  append(form, footer, progress, send, back)
  const clear = () => { fields.forEach(f => { f.error.hidden = true; f.node.setAttribute('aria-invalid', 'false') }); footer.hidden = true }
  const invalid = (key: string, message: string) => { const f = fields.get(key); if (f) { f.error.textContent = message; f.error.hidden = false; f.node.setAttribute('aria-invalid', 'true') }; return f }
  const focus = (key?: string) => { const node = fields.get(key ?? '')?.node ?? footer; if (node === footer) node.tabIndex = -1; node.focus({ preventScroll: true }); node.scrollIntoView({ block: 'center' }) }
  const showError = (info: ApiErrorInfo | Error) => {
    clear(); const message = info.message ?? 'Не удалось подтвердить сверку. Проверьте данные.'
    const details = !(info instanceof Error) && info.details && typeof info.details === 'object' ? info.details as Record<string, unknown> : {}
    const key = typeof details.field === 'string' ? details.field : undefined
    if (key) invalid(key, message)
    footer.textContent = message; footer.hidden = false; focus(key)
  }
  form.onsubmit = event => {
    event.preventDefault(); clear(); let minor = 0
    const problems: { key: string; message: string }[] = []
    try { minor = textReconciliationMinor(draft.amount) } catch (e) { problems.push({ key: 'amount', message: (e as Error).message }) }
    if (!draft.confirmationSource.trim() || draft.confirmationSource.trim().length > 500) problems.push({ key: 'confirmationSource', message: 'Укажите источник подтверждения суммы (до 500 символов).' })
    if (!draft.reason.trim() || draft.reason.trim().length > 500) problems.push({ key: 'reason', message: 'Укажите причину сверки (до 500 символов).' })
    if (problems.length) { problems.forEach(p => invalid(p.key, p.message)); footer.textContent = `Сверка не отправлена. ${problems[0].message}`; footer.hidden = false; focus(problems[0].key); return }
    options.submit(minor)
  }
  return { form, showError, setSaving(value: boolean) { send.disabled = value; form.setAttribute('aria-busy', String(value)); progress.textContent = value ? 'Записываем сверку…' : '' } }
}
