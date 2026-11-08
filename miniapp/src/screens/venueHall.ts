import { hallRead, hallCommand, seatingDeps, tableStateLabel, venueDate, type HallState, type HallTable, type SeatingCommand, type TableBooking, type Layout } from '../shared/api/seating'
import { requestApi } from '../shared/api/request'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { append, el } from '../shared/ui/dom'
import { renderHallPlan } from '../shared/ui/hallPlan'

type Options = { root: HTMLDivElement | null; backendUrl: string; venueId: number; access: VenueAccessDto; userId: number | null; mode?: 'work' | 'settings' | 'rules' | 'bookings' }
type Draft = { values: Record<string, string>; attempt?: SeatingCommand; selected?: number; form?: string; revision?: string; conflict?: string }

export function renderVenueHall(options: Options) {
  const { root, backendUrl, venueId, access, userId } = options
  if (!root) return () => undefined
  root.replaceChildren()
  const mode = options.mode ?? 'work'
  const manages = access.role === 'OWNER' || access.role === 'MANAGER'
  const key = userId ? `hookah:hall:${userId}:${venueId}:${mode}` : null
  let draft: Draft = { values: {} }
  try { if (key) draft = JSON.parse(sessionStorage.getItem(key) ?? '{"values":{}}') } catch { /* No persisted draft. */ }
  if (window.location.hash.includes('addBooking=1') && !draft.form) draft.form = 'booking'
  let state: HallState | null = null
  let disposed = false
  let revoked = false
  let filePreviewUrl: string | null = null
  let busy = false
  let loadVersion = 0
  let planDispose = () => undefined as void
  let view: 'list' | 'plan' = 'list'
  const controller = new AbortController()
  const status = el('p', { className: 'status' }); status.setAttribute('role', 'status')
  const conflictNotice = el('section', { className: 'hall-conflict' })
  conflictNotice.setAttribute('aria-live', 'polite')
  const content = el('div')
  const form = el('section', { className: 'card hall-editor' })
  const header = el('section', { className: 'card' })
  const title = mode === 'settings' ? 'Залы и столы' : mode === 'rules' ? 'Бронирование / Правила посадки' : mode === 'bookings' ? 'Посадка и ручные брони' : 'Зал'
  append(header, el('h2', { text: title }), status)
  const refresh = button('Обновить доступность', () => void load())
  append(header, refresh, conflictNotice)
  if (mode === 'settings') { const qr = el('a', { text: 'Создать столы и действия с QR' }); qr.href = '#/tables'; header.append(qr) }
  append(root, header, content, form)

  function revokeAccess() {
    revoked = true; ++loadVersion; state = null; draft = { values: {} }
    if (key) sessionStorage.removeItem(key)
    planDispose(); content.replaceChildren(); form.replaceChildren(); conflictNotice.replaceChildren()
  }
  function persist() { if (key && !revoked) { try { sessionStorage.setItem(key, JSON.stringify(draft)) } catch { /* Keep memory draft. */ } } }
  function button(text: string, click: () => void) { const b = el('button', { text, className: 'button-secondary' }) as HTMLButtonElement; b.type = 'button'; b.addEventListener('click', click); return b }
  function input(label: string, name: string, value = '', type = 'text') {
    const wrap = el('label', { className: 'hall-field', text: label }); const control = document.createElement('input')
    control.type = type; control.className = 'venue-input'; control.name = name; control.setAttribute('aria-label', label)
    control.value = draft.values[name] ?? value; control.disabled = busy || Boolean(draft.attempt)
    control.addEventListener('input', () => { draft.values[name] = control.value; persist() })
    wrap.append(control); form.append(wrap); return control
  }
  function check(label: string, name: string, value: boolean) {
    const wrap = el('label', { className: 'hall-field' }); const control = document.createElement('input'); control.type = 'checkbox'; control.name = name; control.checked = (draft.values[name] ?? String(value)) === 'true'; control.disabled = busy || Boolean(draft.attempt)
    control.addEventListener('change', () => { draft.values[name] = String(control.checked); persist() }); append(wrap, control, el('span', { text: label })); form.append(wrap); return control
  }
  function select(label: string, name: string, items: Array<[string, string]>, value: string) {
    const wrap = el('label', { className: 'hall-field', text: label }); const control = document.createElement('select'); control.className = 'venue-input'; control.name = name; control.setAttribute('aria-label', label)
    for (const [id, text] of items) { const option = document.createElement('option'); option.value = id; option.textContent = text; control.append(option) }
    control.value = draft.values[name] ?? value; control.disabled = busy || Boolean(draft.attempt)
    control.addEventListener('change', () => { draft.values[name] = control.value; persist() }); wrap.append(control); form.append(wrap); return control
  }
  function begin(name: string, id?: number, revision?: string) {
    if (draft.attempt || busy) { status.textContent = 'Сначала проверьте результат предыдущего действия.'; return false }
    if (draft.form !== name || draft.selected !== id) { draft = { values: {}, form: name, selected: id }; persist() }
    if (draft.revision !== undefined && revision !== draft.revision) {
      draft.conflict ??= 'Данные на сервере изменились. Ваш ввод сохранён.'
    }
    draft.revision = revision; persist()
    if (filePreviewUrl) { URL.revokeObjectURL(filePreviewUrl); filePreviewUrl = null }
    form.replaceChildren(); return true
  }
  function saveButton(text: string, get: () => Omit<SeatingCommand, 'requestId'>) {
    const save = button(text, () => void send({ ...get(), requestId: crypto.randomUUID() }))
    save.disabled = Boolean(draft.conflict)
    form.append(save)
  }
  function fieldLock(locked: boolean) { form.querySelectorAll<HTMLInputElement | HTMLButtonElement>('input,select,textarea,button').forEach(x => { x.disabled = locked }) }
  async function send(command: SeatingCommand) {
    if (busy || disposed || revoked || draft.conflict) return
    ++loadVersion
    busy = true; refresh.disabled = true; draft.attempt ??= command; persist(); fieldLock(true); status.textContent = 'Сохраняем…'
    const result = await hallCommand(backendUrl, venueId, draft.attempt)
    if (disposed || revoked) return
    busy = false; refresh.disabled = false
    if (!result.ok) {
      status.textContent = result.error.message ?? 'Не удалось сохранить.'
      if (result.error.status === 401 || result.error.status === 403) { revokeAccess(); return }
      if (result.error.status === 0 || result.error.status >= 500) {
        status.append(button('Проверить результат повтором', () => { if (draft.attempt) void send(draft.attempt) }))
      } else {
        delete draft.attempt
        if (result.error.status === 409) {
          draft.conflict = result.error.message ?? 'Данные на сервере изменились.'
          persist(); await load(); return
        }
        fieldLock(false)
      }
      persist(); return
    }
    draft = { values: {} }; persist(); form.replaceChildren(); status.textContent = 'Сохранено.'
    await load()
  }
  async function load() {
    if (busy || disposed || revoked) return
    const version = ++loadVersion
    const result = await hallRead(backendUrl, venueId, controller.signal)
    if (disposed || revoked || version !== loadVersion) return
    if (!result.ok) {
      status.textContent = result.error.message ?? 'Не удалось обновить. Черновик сохранён.'
      if (result.error.status === 401 || result.error.status === 403) { revokeAccess() }
      else if (draft.conflict) { fieldLock(true); showConflict(false) }
      return
    }
    state = result.data
    if (draft.form === 'plan') {
      const revision = state.halls.find(h => h.id === draft.selected)?.revision
      if (draft.revision !== undefined && String(revision) !== draft.revision) {
        draft.conflict ??= 'План зала на сервере изменился. Проверьте его перед заменой.'
        draft.revision = String(revision); persist()
      }
    }
    if (draft.form && !draft.attempt && draft.form !== 'plan') form.replaceChildren()
    render(); showConflict(true)
    if (draft.attempt) { status.textContent = 'Результат предыдущего действия неизвестен. Повтор использует тот же ключ.'; status.append(button('Проверить результат повтором', () => { if (draft.attempt) void send(draft.attempt) })) }
  }
  function showConflict(loaded: boolean) {
    conflictNotice.replaceChildren()
    if (!draft.conflict) return
    status.textContent = draft.conflict
    fieldLock(true)
    conflictNotice.append(el('p', { text: 'Данные на сервере могли измениться. Ваш ввод сохранён; повторная отправка требует проверки.' }))
    if (!loaded || !state) {
      conflictNotice.append(el('p', { text: 'Не удалось получить актуальную версию. Обновите доступность перед повтором.' })); return
    }
    const booking = state.bookings.find(b => b.id === draft.selected)
    const table = state.tables.find(t => t.id === draft.selected)
    if (draft.form === 'assign' || draft.form === 'seat-booking') {
      if (!booking) { conflictNotice.append(el('p', { text: 'Бронь завершена или больше недоступна. Черновик сохранён, отправка запрещена.' })); return }
      const number = state.tables.find(t => t.id === booking.tableId)?.number
      conflictNotice.append(el('p', { text: `Сейчас на сервере: ${venueDate(booking.scheduledAt, state.timezone)} — ${venueDate(booking.agreedEndAt, state.timezone)}, стол ${number ?? 'не назначен'}, гостей: ${booking.partySize ?? 'неизвестно'}.` }))
    } else if (draft.form === 'table' && table) {
      conflictNotice.append(el('p', { text: `Сейчас на сервере: стол №${table.number}, мест: ${table.capacity ?? 'неизвестно'}.` }))
    }
    conflictNotice.append(button('Проверил изменения, продолжить', () => {
      delete draft.conflict; persist(); conflictNotice.replaceChildren(); fieldLock(false)
      status.textContent = 'Проверьте сохранённый ввод и отправьте новую попытку. Автоматического сохранения не было.'
    }))
  }
  function render() {
    if (!state) return
    planDispose(); content.replaceChildren()
    content.append(el('p', { text: `Время заведения: ${state.timezone}. Будущая бронь не означает занятость сейчас.` }))
    if (mode === 'rules') { if (manages && !form.childElementCount) rulesForm(); return }
    if (mode === 'settings') {
      if (!manages) { status.textContent = 'Настройка доступна владельцу и управляющему.'; return }
      content.append(button('Добавить зал', () => hallForm()))
      for (const hall of state.halls) {
        const card = el('section', { className: 'card' }); append(card, el('h3', { text: `${hall.name}${hall.active ? '' : ' · отключён'}` }), button('Настроить зал', () => hallForm(hall.id)), button('Загрузить или заменить план', () => planForm(hall.id))); content.append(card)
      }
    } else {
      content.append(button('Добавить бронь', () => bookingForm()))
      if (state.unresolvedBookings) content.append(el('p', { text: `Без назначенного стола: ${state.unresolvedBookings}. Перед включением выбора стола назначьте места действующим броням.` }))
    }
    const switches = el('div', { className: 'button-row' }); append(switches, button('Список', () => { view = 'list'; render() }))
    if (state.rules.usePlan || mode === 'settings') switches.append(button('Схема', () => { view = 'plan'; render() }))
    content.append(switches)
    if (view === 'plan' && (state.rules.usePlan || mode === 'settings')) {
      const disposers: Array<() => void> = []
      for (const hall of state.halls.filter(h => h.active && h.planId)) {
        const panel = el('section', { className: 'card' }); panel.append(el('h3', { text: hall.name })); content.append(panel)
        disposers.push(renderHallPlan(panel, { base: backendUrl, venue: venueId, hall, tables: state.tables, edit: mode === 'settings', onSelect: table => mode === 'settings' ? tableForm(table) : tableCard(table), onMove: (table, layout) => {
          if (draft.form !== 'table' || draft.selected !== table.id) return
          for (const [k, v] of Object.entries(layout)) { draft.values[k] = String(v); const field = form.querySelector<HTMLInputElement>(`[name="${k}"]`); if (field) field.value = String(v) } persist()
        } }))
      }
      planDispose = () => disposers.forEach(d => d())
    }
    // Always retain a reachable list for unplaced, inactive and nonvisual obligations.
    for (const table of state.tables) {
      const hall = state.halls.find(h => h.id === table.hallId)?.name ?? 'Без зала'
      const row = el('section', { className: 'card hall-table' }); append(row, el('h3', { text: `Стол №${table.number} · ${hall}` }), el('p', { text: `Мест: ${table.capacity ?? 'неизвестно'} · ${tableStateLabel(table.state)}` }))
      if (table.reason) row.append(el('p', { text: table.reason }))
      if (table.seating) row.append(el('p', { text: `Посадка: ${venueDate(table.seating.startedAt, state.timezone)}. До: ${venueDate(table.seating.agreedEndAt, state.timezone)}` }))
      if (table.nextBooking) row.append(el('p', { text: `Ближайшая бронь: ${venueDate(table.nextBooking.scheduledAt, state.timezone)} — ${venueDate(table.nextBooking.agreedEndAt, state.timezone)}` }))
      row.append(button(mode === 'settings' ? 'Редактировать стол' : 'Действия со столом', () => mode === 'settings' ? tableForm(table) : tableCard(table))); content.append(row)
    }
    if (mode !== 'settings') for (const booking of state.bookings.filter(b => b.status !== 'SEATED')) {
      const row = el('section', { className: 'card' }); append(row, el('h3', { text: `Бронь #${booking.id} · ${booking.source === 'STAFF' ? 'Создана сотрудником' : 'Гостевая заявка'}` }), el('p', { text: `${booking.guestName ?? 'Гость'} · ${booking.partySize ?? '—'} гостей · ${booking.status}` }), el('p', { text: `${venueDate(booking.scheduledAt, state.timezone)} — ${venueDate(booking.agreedEndAt, state.timezone)}` }))
      if (booking.phone) row.append(el('p', { text: booking.phone }))
      if (booking.comment) row.append(el('p', { text: booking.comment }))
      if (booking.status === 'CONFIRMED') row.append(button('Посадить по брони', () => bookingForm(undefined, booking, true)))
      if (manages) row.append(button('Назначить или изменить стол', () => bookingForm(undefined, booking)))
      content.append(row)
    }
    if (draft.form && !form.childElementCount && !draft.attempt) restoreForm()
  }
  function hallForm(id?: number) {
    if (!state) return
    const hall = state.halls.find(h => h.id === id)
    if (!begin('hall', id, hall ? String(hall.revision) : undefined)) return
    const name = input('Название зала', 'name', hall?.name)
    const active = check('Зал активен', 'active', hall?.active ?? true)
    saveButton('Сохранить зал', () => ({ action: 'HALL', id, revision: hall?.revision, name: name.value, active: active.checked }))
  }
  function tableForm(table: HallTable) {
    if (!state || !begin('table', table.id, String(table.revision))) return
    form.append(el('h3', { text: `Стол №${table.number}: настройки` }))
    const number = input('Номер стола', 'number', String(table.number), 'number')
    const capacity = input('Вместимость (пусто — неизвестна)', 'capacity', table.capacity == null ? '' : String(table.capacity), 'number')
    const hall = select('Зал', 'hall', [['', 'Без зала'], ...state.halls.filter(h => h.active).map(h => [String(h.id), h.name] as [string, string])], String(table.hallId ?? ''))
    const active = check('Стол активен', 'active', table.active)
    const shape = select('Форма', 'shape', [['RECTANGLE', 'Прямоугольник'], ['ELLIPSE', 'Круг / овал']], table.layout?.shape ?? 'RECTANGLE')
    const layout = table.layout ?? { x: .5, y: .5, width: .12, height: .12, rotation: 0 }
    const fields = Object.fromEntries((['x', 'y', 'width', 'height', 'rotation'] as const).map(k => [k, input(({ x: 'Положение X (0–1)', y: 'Положение Y (0–1)', width: 'Ширина (доля изображения)', height: 'Высота (доля изображения)', rotation: 'Поворот, градусы' })[k], k, String(layout[k]), 'number')]))
    Object.values(fields).forEach(f => { f.step = '.01' })
    saveButton('Сохранить стол', () => ({ action: 'TABLE', id: table.id, revision: table.revision, number: Number(number.value), capacity: capacity.value ? Number(capacity.value) : null, hallId: hall.value ? Number(hall.value) : null, active: active.checked,
      layout: hall.value ? { x: Number(fields.x.value), y: Number(fields.y.value), width: Number(fields.width.value), height: Number(fields.height.value), rotation: Number(fields.rotation.value), shape: shape.value as Layout['shape'] } : null }))
  }
  function rulesForm() {
    if (!state || !begin('rules', undefined, String(state.rules.revision))) return
    const old = state.rules
    const plan = check('Использовать схему зала', 'usePlan', old.usePlan)
    form.append(el('p', { text: 'Разместите столы на изображении и управляйте посадкой. Без схемы работа со столами доступна списком' }))
    const guest = check('Разрешить гостям выбирать стол', 'guestTableChoice', old.guestTableChoice)
    form.append(el('p', { text: 'Гость выбирает доступный стол на указанное время. Для корректной доступности отмечайте посадку и освобождение столов' }))
    const limit = check('Ограничивать длительность визита', 'limitDuration', old.limitDuration)
    form.append(el('p', { text: 'Задаёт стандартное время брони и посадки. Продление возможно, если не мешает следующей брони. Стол не освобождается автоматически по таймеру' }))
    const duration = input('Стандартная длительность, минут', 'durationMinutes', String(old.durationMinutes), 'number')
    const buffer = input('Перерыв между посадками, минут', 'bufferMinutes', String(old.bufferMinutes), 'number')
    form.append(el('p', { text: 'Перерыв может быть 0. Это не время удержания брони при опоздании. Новые правила не меняют уже согласованные визиты.' }))
    saveButton('Сохранить правила', () => ({ action: 'RULES', revision: old.revision, rules: { ...old, usePlan: plan.checked, guestTableChoice: guest.checked, limitDuration: limit.checked, durationMinutes: Number(duration.value), bufferMinutes: Number(buffer.value) } }))
  }
  function bookingForm(table?: HallTable, booking?: TableBooking, seating = false) {
    if (!state || !begin(seating ? (booking ? 'seat-booking' : 'seat') : booking ? 'assign' : 'booking', booking?.id ?? table?.id, booking ? String(booking.revision) : undefined)) return
    form.append(el('h3', { text: seating ? 'Посадка гостей' : booking ? 'Назначение стола' : 'Добавить согласованную бронь' }))
    const tableSelect = select('Стол для гостей', 'tableId', [['', 'Без гарантии конкретного стола'], ...state.tables.filter(t => t.active).map(t => [String(t.id), `№${t.number}, мест: ${t.capacity ?? 'неизвестно'}`] as [string, string])], String(booking?.tableId ?? table?.id ?? ''))
    const local = (v: string | null) => v ? new Intl.DateTimeFormat('sv-SE', { timeZone: state!.timezone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(v)).replace(' ', 'T') : ''
    const start = seating ? null : input('Начало (время заведения)', 'localStart', local(booking?.scheduledAt ?? null), 'datetime-local')
    const end = input('Согласованное окончание (необязательно)', 'localEnd', local(booking?.agreedEndAt ?? null), 'datetime-local')
    const party = input('Количество гостей', 'partySize', booking ? (booking.partySize == null ? '' : String(booking.partySize)) : '2', 'number')
    const name = !booking && !seating ? input('Имя или обозначение гостя', 'name') : null
    const phone = !booking && !seating ? input('Телефон (необязательно)', 'phone', '', 'tel') : null
    const comment = !booking && !seating ? input('Комментарий (необязательно)', 'comment') : null
    const bookingSeat = seating && Boolean(booking)
    if (bookingSeat && booking) {
      party.readOnly = true; party.value = booking.partySize == null ? '' : String(booking.partySize)
      end.readOnly = true; end.value = local(booking.agreedEndAt)
      form.append(el('p', { text: `Посадка сохраняет условия брони: гостей ${booking.partySize ?? 'неизвестно'}, окончание ${venueDate(booking.agreedEndAt, state.timezone)}.` }))
      form.append(el('p', { text: 'Изменить число гостей или окончание до посадки может владелец или управляющий через изменение брони. Доступность проверяется заново.' }))
      if (manages) form.append(button('Изменить условия брони', () => bookingForm(table, booking)))
    }
    const shorter = bookingSeat ? null : check('Гости предупреждены о сокращённом времени', 'shorter', false)
    const reconcile = seating ? check('Лично сверил компанию: связать единственный прежний сеанс с этой посадкой', 'reconcile', false) : null
    form.append(el('p', { text: `Часовой пояс: ${state.timezone}. Ручная бронь сразу подтверждена. Регистрация гостя в Telegram не нужна.` }))
    saveButton(seating ? 'Подтвердить посадку' : booking ? 'Сохранить назначение' : 'Создать подтверждённую бронь', () => ({ action: seating ? 'SEAT' : booking ? 'ASSIGN_BOOKING' : 'MANUAL_BOOKING', id: booking?.id, revision: booking?.revision, bookingId: seating ? booking?.id : undefined, tableId: tableSelect.value ? Number(tableSelect.value) : null, localStart: start?.value || undefined, localEnd: bookingSeat ? undefined : end.value || null, partySize: bookingSeat ? undefined : Number(party.value), name: name?.value, phone: phone?.value || null, comment: comment?.value || null, shorterVisitAcknowledged: shorter?.checked ?? false, reconcileLegacySession: reconcile?.checked ?? false }))
  }
  function tableCard(table: HallTable) {
    if (!state || !begin('operation', table.id, table.seating ? `${table.seating.id}:${table.seating.revision}` : 'empty')) return
    form.append(el('h3', { text: `Стол №${table.number} · ${tableStateLabel(table.state)}` }))
    if (table.reason) form.append(el('p', { text: table.reason }))
    form.append(button('Добавить бронь этого стола', () => bookingForm(table)))
    if (!table.seating) { form.append(button('Посадить без брони', () => bookingForm(table, undefined, true))); return }
    const seat = table.seating
    const end = input('Новое окончание (время заведения)', 'extendEnd', '', 'datetime-local')
    saveButton('Продлить посадку', () => ({ action: 'EXTEND', id: seat.id, revision: seat.revision, localEnd: end.value }))
    const target = select('Новый стол', 'target', state.tables.filter(t => t.active && t.id !== table.id).map(t => [String(t.id), `Стол №${t.number}`]), '')
    saveButton('Пересадить гостей', () => ({ action: 'MOVE', id: seat.id, revision: seat.revision, tableId: Number(target.value) }))
    form.append(el('p', { text: 'Освобождение не является оплатой. Активный заказ сначала завершается штатным действием; неоплаченный долг сохраняется.' }))
    form.append(button('Подтвердить освобождение', () => { if (window.confirm('Гости ушли, стол освобождён? Долг останется в учёте.')) void send({ requestId: crypto.randomUUID(), action: 'RELEASE', id: seat.id, revision: seat.revision }) }))
  }
  function planForm(id: number) {
    if (!state) return
    const hall = state.halls.find(h => h.id === id)
    if (!hall || !begin('plan', id, String(hall.revision))) return
    form.append(el('h3', { text: `План: ${hall.name}` }))
    if (!state.imageUploadAvailable) { form.append(el('p', { text: 'Хранилище изображений недоступно. Работа со столами остаётся доступна списком.' })); return }
    const file = document.createElement('input'); file.type = 'file'; file.accept = 'image/jpeg,image/png'; file.setAttribute('aria-label', 'Изображение плана'); form.append(file)
    const preview = document.createElement('img'); preview.className = 'hall-plan-preview'; preview.alt = 'Предпросмотр новой схемы'; form.append(preview)
    let requestId = draft.values.uploadRequestId ?? crypto.randomUUID()
    let uncertain = Boolean(draft.values.uploadRequestId)
    file.addEventListener('change', () => { if (!uncertain) requestId = crypto.randomUUID(); if (filePreviewUrl) URL.revokeObjectURL(filePreviewUrl); if (file.files?.[0]) { filePreviewUrl = URL.createObjectURL(file.files[0]); preview.src = filePreviewUrl } })
    form.append(el('p', { text: 'Замена сбросит расположение маркеров и выключит гостевой выбор до проверки новой схемы. Брони, посадки и QR сохранятся.' }))
    const upload = button('Загрузить выбранный план', async () => {
      const selected = file.files?.[0]; if (!selected || busy) return
      busy = true; draft.values.uploadRequestId = requestId; persist(); fieldLock(true)
      const result = await requestApi<{ id: string; width: number; height: number }>(backendUrl, `/api/venue/${venueId}/hall/${hall.id}/plans`, { method: 'POST', headers: { 'Content-Type': selected.type, 'Idempotency-Key': requestId }, body: selected }, seatingDeps)
      if (disposed) return
      busy = false; fieldLock(false)
      if (!result.ok && (result.error.status === 401 || result.error.status === 403)) { status.textContent = result.error.message ?? 'Доступ отозван.'; revokeAccess(); return }
      if (!result.ok) { uncertain = result.error.status === 0 || result.error.status >= 500; if (!uncertain) { delete draft.values.uploadRequestId; persist() }; file.disabled = uncertain; status.textContent = (result.error.message ?? 'Не удалось загрузить. Последняя схема сохранена.') + (uncertain ? ' Повторите загрузку этого же файла с прежним ключом.' : ''); return }
      upload.disabled = true; file.disabled = true
      form.append(button('Подтвердить новую схему и разместить столы заново', () => void send({ requestId: crypto.randomUUID(), action: 'PLAN', id: hall.id, revision: state?.halls.find(h => h.id === hall.id)?.revision, planId: result.data.id, confirmPlanReplacement: true })))
    }); form.append(upload)
  }
  function restoreForm() {
    if (!state) return
    const table = state.tables.find(t => t.id === draft.selected)
    const booking = state.bookings.find(b => b.id === draft.selected)
    if (draft.form === 'hall') hallForm(draft.selected)
    else if (draft.form === 'table' && table) tableForm(table)
    else if (draft.form === 'operation' && table) tableCard(table)
    else if (draft.form === 'booking') bookingForm(table)
    else if (draft.form === 'seat' && table) bookingForm(table, undefined, true)
    else if (draft.form === 'seat-booking' && booking) bookingForm(undefined, booking, true)
    else if (draft.form === 'assign' && booking) bookingForm(undefined, booking)
    else if (draft.form === 'rules') rulesForm()
    else if (draft.form === 'plan' && draft.selected) planForm(draft.selected)
  }
  void load()
  return () => { disposed = true; controller.abort(); planDispose(); if (filePreviewUrl) URL.revokeObjectURL(filePreviewUrl); persist() }
}
