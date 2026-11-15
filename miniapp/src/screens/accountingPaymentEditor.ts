import { accountingDecimal, accountingMinor } from './accountingSaleEditor'
import type { AccountingCommand } from '../shared/api/accounting'
import type { ApiErrorInfo } from '../shared/api/types'
import { append, el } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'

export const paymentNames: Record<string, string> = { CASH: 'Наличные', CARD: 'Карта', TRANSFER: 'Перевод' }
const actions: Record<string, string> = { CASH: 'наличными', CARD: 'картой', TRANSFER: 'переводом' }
const money = (v: number) => formatPrice(v, 'RUB')
export type PaymentDraft = { version: number; method: string; partial: boolean; amount: string; components: Record<string, string>; acknowledge: boolean }
export function accountingPaymentEditor(options: { remaining: number; draft: PaymentDraft; save: () => void; submit: (body: Partial<AccountingCommand>) => void }) {
  const { remaining, draft } = options
  const form = el('form', { className: 'card accounting-payment-form' }); form.noValidate = true
  const errors = new Map<string, string>()
  let attempted = false
  const messages = new Map<string, HTMLParagraphElement>()
  const fields = new Map<string, HTMLInputElement | HTMLSelectElement>()
  const add = (label: string, node: HTMLInputElement | HTMLSelectElement, key: string) => {
    node.id = `payment-${crypto.randomUUID()}`; const title = el('label', { text: label }); title.htmlFor = node.id
    const error = el('p', { className: 'accounting-field-error' }); error.id = `${node.id}-error`; error.hidden = true
    node.setAttribute('aria-describedby', error.id); node.setAttribute('aria-invalid', 'false'); fields.set(key, node); messages.set(key, error)
    return append(el('div', { className: 'accounting-field' }), title, node, error)
  }
  const error = el('p', { className: 'error-card accounting-submit-error' }); error.hidden = true; error.setAttribute('role', 'alert')
  const progress = el('p'); progress.setAttribute('role', 'status')
  const method = el('select'); Object.entries({ ...paymentNames, SPLIT: 'Комбинированная' }).forEach(([v, n]) => method.add(new Option(n, v))); method.value = draft.method
  const partial = el('input'); partial.type = 'checkbox'; partial.checked = draft.partial
  const amount = el('input'); amount.inputMode = 'decimal'; amount.value = draft.amount
  const amountRow = add('Получено, ₽', amount, 'amount')
  const components = el('div', { className: 'accounting-payment-components' })
  const componentInputs = Object.keys(paymentNames).map(key => {
    const node = el('input'); node.inputMode = 'decimal'; node.value = draft.components[key] ?? ''; node.placeholder = '0'
    components.append(add(`${paymentNames[key]}, ₽`, node, key))
    node.oninput = () => { draft.components[key] = node.value; changed() }; return node
  })
  const summary = el('p', { className: 'accounting-payment-preview' }); summary.setAttribute('aria-live', 'polite')
  const totalError = el('p', { className: 'accounting-field-error' }); totalError.id = `payment-total-${crypto.randomUUID()}`; totalError.hidden = true
  const total = append(el('div', { className: 'accounting-field accounting-payment-total' }), summary, totalError)
  total.setAttribute('role', 'group'); total.setAttribute('aria-label', 'Итог оплаты'); total.setAttribute('aria-describedby', totalError.id); total.tabIndex = -1
  const ack = el('input'); ack.type = 'checkbox'; ack.checked = draft.acknowledge
  const ackRow = add('Подтверждаю частичную оплату: неоплаченный остаток останется', ack, 'ack')
  const send = el('button'); send.type = 'submit'
  let busy = false
  const split = () => method.value === 'SPLIT'
  const validation = () => {
    const parts: { method: string; amountMinor: number }[] = []
    const problems = new Map<string, string>()
    for (const key of split() ? Object.keys(paymentNames) : ['amount']) {
      const text = key === 'amount' ? draft.amount : draft.components[key] ?? ''
      if (split() && !text.trim()) continue
      try { parts.push({ method: key === 'amount' ? method.value : key, amountMinor: key === 'amount' && !draft.partial ? remaining : accountingMinor(text) }) }
      catch { problems.set(key, 'Введите допустимую сумму: рубли и не более двух знаков после запятой.') }
    }
    // An incomplete/invalid component makes the total unknown, never a valid zero.
    const sum = problems.size ? null : parts.reduce((value, part) => value + part.amountMinor, 0)
    const totals: string[] = []
    if (sum == null) totals.push('Итог пока нельзя проверить: исправьте суммы оплаты.')
    else {
      if (split() && (parts.length < 2 || parts.some(part => part.amountMinor <= 0))) totals.push('Укажите минимум два способа с положительными суммами. Неиспользуемые поля оставьте пустыми.')
      if (sum <= 0 || sum > remaining) totals.push('Сумма должна быть больше нуля и не превышать остаток.')
    }
    if (totals.length) problems.set('total', totals.join(' '))
    const partialNeeded = sum != null && sum > 0 && sum < remaining
    if (!draft.partial && (partialNeeded || (sum == null && errors.has('partial')))) problems.set('partial', 'Включите частичную оплату и подтвердите оставшийся неоплаченный остаток.')
    if (draft.partial && !draft.acknowledge && (partialNeeded || (sum == null && errors.has('ack')))) problems.set('ack', 'Для частичной оплаты подтвердите оставшийся неоплаченный остаток.')
    return { parts, sum, problems }
  }
  const syncErrors = () => {
    const aggregate = errors.get('total')
    total.setAttribute('aria-invalid', String(Boolean(aggregate))); totalError.textContent = aggregate ?? ''; totalError.hidden = !aggregate
    fields.forEach((node, key) => {
      const message = errors.get(key)
      const participates = split() ? key in paymentNames : key === 'amount'
      node.setAttribute('aria-invalid', String(Boolean(message || (!split() && key === 'amount' && aggregate))))
      node.setAttribute('aria-describedby', [messages.get(key)!.id, ...(participates ? [totalError.id] : [])].join(' '))
      const p = messages.get(key)!
      p.textContent = message ?? ''; p.hidden = !message
    })
    error.textContent = [...errors.values()].join(' '); error.hidden = errors.size === 0
  }
  const focusError = (key: string) => {
    const node = key === 'total' ? total : fields.get(key) ?? error
    if (node === error) node.tabIndex = -1
    node.focus(); node.scrollIntoView({ block: 'nearest' })
  }
  const showError = (info: ApiErrorInfo | Error, key = 'server') => {
    errors.set(key, info instanceof Error ? info.message : info.message ?? 'Оплата не записана. Проверьте актуальную продажу.'); syncErrors(); focusError(key)
  }
  const update = () => {
    components.hidden = !split(); amountRow.hidden = split(); amount.readOnly = !draft.partial
    if (!draft.partial) amount.value = accountingDecimal(remaining)
    ackRow.hidden = !draft.partial
    const state = validation(), { sum } = state
    if (attempted) {
      // Reconcile dependent local constraints; keep independent server errors intact.
      for (const key of ['amount', 'CASH', 'CARD', 'TRANSFER', 'total', 'partial', 'ack']) {
        const message = state.problems.get(key)
        if (message) errors.set(key, message); else errors.delete(key)
      }
    }
    syncErrors()
    summary.textContent = sum == null ? 'Проверьте суммы оплаты.' : `Будет записано: ${money(sum)} · Остаток после записи: ${money(Math.max(0, remaining - sum))}`
    send.textContent = `Записать ${sum == null ? 'оплату' : money(sum)} ${split() ? 'комбинированно' : actions[method.value]}`
    send.disabled = busy || sum === 0 || (attempted && state.problems.has('total'))
    return state
  }
  const changed = () => {
    draft.acknowledge = false; ack.checked = false
    options.save(); update()
  }
  method.onchange = () => { draft.method = method.value; changed() }
  partial.onchange = () => { draft.partial = partial.checked; draft.amount = accountingDecimal(remaining); amount.value = draft.amount; changed() }
  amount.oninput = () => { draft.amount = amount.value; changed() }
  ack.onchange = () => { draft.acknowledge = ack.checked; options.save(); update() }
  append(form, el('h3', { text: 'Записать оплату' }), el('p', { text: 'Запишите уже полученную оплату. Приложение не списывает деньги.' }), add('Способ оплаты', method, 'method'), add('Частичная оплата', partial, 'partial'), amountRow, components, total, ackRow, error, progress, send)
  form.onsubmit = event => {
    event.preventDefault(); if (busy) return
    attempted = true
    const state = update()
    if (state.problems.size) { focusError(state.problems.keys().next().value!); return }
    const { parts, sum } = state
    options.submit(split() ? { action: 'PAY_SPLIT', paymentComponents: parts, ...(sum! < remaining ? { acknowledgePartial: true } : {}) } : { action: 'PAY', amountMinor: sum!, paymentMethod: method.value })
  }
  update()
  return { form, showError, setSaving(value: boolean) { busy = value; progress.textContent = value ? 'Записываем оплату…' : ''; form.setAttribute('aria-busy', String(value)); update() } }
}
