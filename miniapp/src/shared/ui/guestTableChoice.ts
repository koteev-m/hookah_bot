import { tableAvailability, tableBooking, tableStateLabel, venueDate, type TableAvailability, type SeatingCommand } from '../api/seating'
import { getTelegramContext } from '../telegram'
import { append, el } from './dom'
import { renderHallPlan } from './hallPlan'

type Fields = { formCard: HTMLElement; dateInput: HTMLInputElement; timeInput: HTMLInputElement; partySizeInput: HTMLInputElement; commentInput: HTMLTextAreaElement; status: HTMLParagraphElement }
export function guestTableChoice(base: string, venue: number, fields: Fields) {
  const actor = getTelegramContext().telegramUserId
  const key = actor ? `hookah:guest-booking:${actor}:${venue}` : null
  let disposed = false
  let revoked = false
  let version = 0
  let selected: number | null = null
  let availability: TableAvailability | null = null
  let attempt: SeatingCommand | null = null
  let planDisposers: Array<() => void> = []
  let loadError = false
  const controller = new AbortController()
  const panel = el('section', { className: 'guest-table-choice' })
  const hint = el('p', { text: 'Выберите дату, время и количество гостей для проверки столов.' }); hint.setAttribute('role', 'status')
  const choices = el('div')
  const update = el('button', { text: 'Проверить доступные столы', className: 'button-secondary' }) as HTMLButtonElement; update.type = 'button'
  const conditionLabel = el('label', { className: 'hall-field' }); const conditions = document.createElement('input'); conditions.type = 'checkbox'
  append(conditionLabel, conditions, el('span', { text: 'Условия времени и вместимости понятны' })); conditionLabel.hidden = true
  append(panel, hint, update, choices, conditionLabel); fields.formCard.append(panel)
  try {
    const stored = key ? JSON.parse(sessionStorage.getItem(key) ?? 'null') : null
    if (stored) { for (const name of ['dateInput', 'timeInput', 'partySizeInput', 'commentInput'] as const) if (typeof stored[name] === 'string') fields[name].value = stored[name]; selected = stored.selected ?? null; attempt = stored.attempt ?? null }
  } catch { /* Current form remains valid. */ }
  function persist() {
    if (!key || revoked) return
    const data = { dateInput: fields.dateInput.value, timeInput: fields.timeInput.value, partySizeInput: fields.partySizeInput.value, commentInput: fields.commentInput.value, selected, attempt }
    try { sessionStorage.setItem(key, JSON.stringify(data)) } catch { /* Memory still retains the operation. */ }
  }
  function revokeAccess(message: string) {
    revoked = true; ++version; attempt = null; selected = null; availability = null
    clearPlans(); choices.replaceChildren(); conditionLabel.hidden = true
    for (const name of ['dateInput', 'timeInput', 'partySizeInput', 'commentInput'] as const) { fields[name].value = ''; fields[name].disabled = true }
    if (key) sessionStorage.removeItem(key)
    hint.textContent = message; fields.status.textContent = message
  }
  function clearPlans() { planDisposers.forEach(d => d()); planDisposers = [] }
  function lockFields() {
    for (const name of ['dateInput', 'timeInput', 'partySizeInput', 'commentInput'] as const) fields[name].disabled = Boolean(attempt)
    if (attempt) hint.textContent = 'Результат отправки ещё не подтверждён. Повторите отправку с прежними данными; новый резерв не создастся.'
  }
  function render() {
    lockFields()
    clearPlans(); choices.replaceChildren()
    if (!availability?.enabled) { conditionLabel.hidden = true; hint.textContent = availability?.reason ?? 'Заявка без гарантии конкретного стола. Заведение подтвердит условия.'; return }
    hint.textContent = availability.reason ?? `Время заведения: ${availability.timezone}. Визит до ${venueDate(availability.agreedEndAt, availability.timezone)}. Заявка ожидает подтверждения заведения.`
    conditionLabel.hidden = Boolean(availability.reason)
    if (availability.reason) return
    const choose = (id: number) => { if (attempt) return; selected = id; conditions.checked = false; persist(); render() }
    for (const hall of availability.halls.filter(h => h.planId)) {
      const canvas = el('section', { className: 'card' }); canvas.append(el('h3', { text: hall.name })); choices.append(canvas)
      planDisposers.push(renderHallPlan(canvas, { base, venue, hall, tables: availability.tables, guest: true, selected: selected ?? undefined, onSelect: table => choose(table.id) }))
    }
    for (const table of availability.tables) {
      const row = el('div', { className: 'card' })
      const button = el('button', { text: `${selected === table.id ? 'Выбран · ' : ''}Стол №${table.number}, ${table.capacity ?? 'неизвестно'} мест · ${tableStateLabel(table.state)}` }) as HTMLButtonElement
      button.type = 'button'; button.disabled = table.state !== 'AVAILABLE' || Boolean(attempt); button.setAttribute('aria-pressed', String(selected === table.id)); button.addEventListener('click', () => choose(table.id)); row.append(button)
      if (table.reason) row.append(el('p', { text: table.reason })); choices.append(row)
    }
  }
  async function refresh() {
    if (revoked || !fields.dateInput.value || !fields.timeInput.value) return
    const token = ++version
    const result = await tableAvailability(base, venue, `${fields.dateInput.value}T${fields.timeInput.value}`, Number(fields.partySizeInput.value), controller.signal)
    if (disposed || revoked || token !== version) return
    loadError = !result.ok
    if (!result.ok && (result.error.status === 401 || result.error.status === 403)) { revokeAccess(result.error.message ?? 'Доступ недоступен. Откройте заведение заново.'); return }
    if (!result.ok) { hint.textContent = result.error.message ?? 'Не удалось проверить доступность. Повторите проверку; введённые данные сохранены.'; return }
    availability = result.data
    if (selected != null && !availability.tables?.some(t => t.id === selected && t.state === 'AVAILABLE') && !attempt) selected = null
    render()
  }
  const listeners: Array<() => void> = []
  for (const name of ['dateInput', 'timeInput', 'partySizeInput', 'commentInput'] as const) {
    const field = fields[name]
    const change = () => { if (name !== 'commentInput') { selected = null; conditions.checked = false; availability = null; void refresh() }; persist() }
    field.addEventListener('change', change); field.addEventListener('input', persist)
    listeners.push(() => { field.removeEventListener('change', change); field.removeEventListener('input', persist) })
  }
  update.addEventListener('click', () => void refresh())
  lockFields(); void refresh()
  return {
    async submit(): Promise<'ordinary' | 'success' | 'blocked'> {
      if (attempt == null) await refresh()
      if (disposed || revoked) return 'blocked'
      // A missing legacy endpoint leaves the ordinary server-validated request available; it cannot reserve a table.
      if (!attempt && !availability?.enabled) return 'ordinary'
      if (!attempt && (loadError || availability?.reason || selected == null || !conditions.checked)) {
        fields.status.textContent = availability?.reason ?? 'Выберите доступный стол и подтвердите условия визита.'; return 'blocked'
      }
      attempt ??= { requestId: crypto.randomUUID(), action: 'GUEST_BOOKING', tableId: selected, localStart: `${fields.dateInput.value}T${fields.timeInput.value}`, partySize: Number(fields.partySizeInput.value), comment: fields.commentInput.value.trim() || null }
      persist(); lockFields()
      const result = await tableBooking(base, venue, attempt)
      if (disposed || revoked) return 'blocked'
      if (!result.ok && (result.error.status === 401 || result.error.status === 403)) { revokeAccess(result.error.message ?? 'Доступ недоступен. Откройте заведение заново.'); return 'blocked' }
      if (!result.ok) {
        fields.status.textContent = result.error.message ?? 'Результат неизвестен. Повтор отправки использует прежний ключ.'
        if (result.error.status > 0 && result.error.status < 500) { attempt = null; conditions.checked = false; void refresh() }
        persist(); lockFields(); return 'blocked'
      }
      attempt = null; selected = null; lockFields(); if (key) sessionStorage.removeItem(key)
      fields.status.textContent = 'Заявка на выбранный стол отправлена. Ожидайте подтверждения заведения.'
      return 'success'
    },
    clear() { attempt = null; selected = null; if (key) sessionStorage.removeItem(key) },
    dispose() { disposed = true; controller.abort(); clearPlans(); listeners.forEach(d => d()) }
  }
}
