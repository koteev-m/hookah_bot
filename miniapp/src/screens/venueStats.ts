import './venueStats.css'
import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { venueGetTables } from '../shared/api/venueApi'
import { venueGetStatsV1, type StatsSection, type VenueKpiSnapshot } from '../shared/api/venueStatsApi'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { getTelegramContext } from '../shared/telegram'
import { append, el, on } from '../shared/ui/dom'
import { calendarDateAt, formatCalendarDate, periodValidation, presetDates, shiftCalendarDate, type StatsPreset } from '../shared/ui/venueStatsFormat'
import { renderStatsContent } from './venueStatsContent'

type VenueStatsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
  onAccessLost?: (reason: 'denied' | 'changed') => void
}
const periods: [StatsPreset, string][] = [['today', 'Сегодня'], ['7d', 'Последние 7 дней'],
  ['30d', 'Последние 30 дней'], ['custom', 'Произвольный период']]
const sections: [StatsSection, string][] = [['all', 'Все разделы'], ['orders', 'Заказы'], ['finance', 'Финансы'],
  ['bookings', 'Бронирования'], ['seatings', 'Посадки'], ['calls', 'Вызовы персонала']]
const operational = (section: StatsSection) => ['orders', 'bookings', 'seatings', 'calls'].includes(section)

export function renderVenueStatsScreen(options: VenueStatsOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  if (access.venueId !== venueId || !['OWNER', 'MANAGER'].includes(access.role)) {
    root.replaceChildren(el('h2', { text: 'Статистика' }), el('p', { text: 'Раздел доступен владельцу или менеджеру этого заведения.' }))
    return () => root.replaceChildren()
  }
  const actor = getTelegramContext().telegramUserId
  const webApp = getTelegramContext().webApp
  const deps = { isDebug, getAccessToken, clearSession }
  const wrapper = el('div', { className: 'venue-stats' })
  const header = el('section', { className: 'card stats-header' })
  append(header, el('p', { className: 'stats-eyebrow', text: 'Заведение · аналитика' }), el('h2', { text: 'Статистика' }),
    el('p', { className: 'stats-venue-name', text: access.venueName?.trim() || 'Текущее заведение' }))
  const controls = el('div', { className: 'stats-controls' })
  const periodRow = el('div', { className: 'venue-stats-periods' }); periodRow.setAttribute('aria-label', 'Период статистики')
  const buttons = periods.map(([key, title]) => {
    const button = el('button', { className: 'button-secondary', text: title }); button.type = 'button'
    button.dataset.period = key; periodRow.append(button); return button
  })
  const form = el('form', { className: 'stats-custom-period' }); form.noValidate = true; form.hidden = true
  const from = el('input'); from.type = 'date'; from.id = 'stats-date-from'; from.required = true
  const to = el('input'); to.type = 'date'; to.id = 'stats-date-to'; to.required = true
  const fromLabel = el('label', { text: 'Начало периода' }); fromLabel.htmlFor = from.id; fromLabel.append(from)
  const toLabel = el('label', { text: 'Окончание периода (включительно)' }); toLabel.htmlFor = to.id; toLabel.append(to)
  const apply = el('button', { text: 'Показать период' }); apply.type = 'submit'
  const validation = el('p', { className: 'stats-validation', id: 'stats-period-validation' }); validation.setAttribute('role', 'alert')
  from.setAttribute('aria-describedby', validation.id); to.setAttribute('aria-describedby', validation.id)
  append(form, fromLabel, toLabel, apply, validation,
    el('p', { className: 'stats-note', text: 'Обе выбранные даты включаются. До 370 календарных дней по часовому поясу заведения.' }))
  const filterRow = el('div', { className: 'stats-filter-row' })
  const section = el('select'); section.id = 'stats-section'
  sections.forEach(([value, label]) => section.add(new Option(label, value)))
  const sectionLabel = el('label', { text: 'Показатели' }); sectionLabel.htmlFor = section.id; sectionLabel.append(section)
  const table = el('select'); table.id = 'stats-table'; table.add(new Option('Все столы', ''))
  const tableLabel = el('label', { text: 'Текущий стол' }); tableLabel.htmlFor = table.id; tableLabel.append(table); tableLabel.hidden = true
  const refresh = el('button', { className: 'button-secondary', text: 'Обновить статистику' }); refresh.type = 'button'
  append(filterRow, sectionLabel, tableLabel, refresh)
  const tableNotice = el('p', { className: 'stats-note' }); tableNotice.hidden = true
  append(controls, periodRow, form, filterRow, tableNotice)
  const metadata = el('div', { className: 'stats-metadata' }); append(header, controls, metadata)
  const status = el('p', { className: 'status stats-load-status' }); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite')
  const skeleton = el('div', { className: 'stats-skeleton' }); skeleton.setAttribute('aria-hidden', 'true'); skeleton.hidden = true
  for (let i = 0; i < 6; i++) skeleton.append(el('div'))
  const error = el('section', { className: 'error-card' }); error.setAttribute('role', 'alert'); error.hidden = true
  const summary = el('div', { className: 'stats-coverage' }); summary.hidden = true
  const content = el('div', { className: 'stats-content' })
  append(wrapper, header, status, skeleton, error, summary, content); root.replaceChildren(wrapper)

  let disposed = false
  let denied = false
  let active = true
  let sequence = 0
  let controller: AbortController | null = null
  let tableController: AbortController | null = null
  let tablesLoaded = false
  let preset: StatsPreset = 'today'
  let timezone: string | null = null
  let serverAnchor: { ms: number; received: number } | null = null
  const currentActor = () => getTelegramContext().telegramUserId === actor
  const current = (seq: number) => !disposed && !denied && sequence === seq && currentActor()
  const clearView = () => { content.replaceChildren(); metadata.replaceChildren(); summary.replaceChildren(); summary.hidden = true }
  const stopLoading = () => { skeleton.hidden = true; content.removeAttribute('aria-busy') }
  const abort = () => { sequence++; controller?.abort(); controller = null; stopLoading() }
  const loseAccess = (reason: 'denied' | 'changed') => {
    if (denied || disposed) return
    denied = true; abort(); tableController?.abort(); table.replaceChildren(); clearView(); status.textContent = ''
    error.replaceChildren(el('h3', { text: reason === 'changed' ? 'Аккаунт изменился' : 'Доступ к статистике потерян' }),
      el('p', { text: 'Прежние данные закрыты. Обновите доступ к заведению или откройте приложение заново.' }))
    error.hidden = false
    controls.querySelectorAll<HTMLInputElement | HTMLButtonElement | HTMLSelectElement>('input, button, select').forEach(node => { node.disabled = true })
    options.onAccessLost?.(reason)
  }
  const fail = (info: ApiErrorInfo, timedOut = false) => {
    clearView(); stopLoading(); status.textContent = ''
    if (info.status === 401 || info.code === 'UNAUTHORIZED' || info.code === 'INITDATA_INVALID') { clearSession(); loseAccess('changed'); return }
    if (info.status === 403 || info.code === 'FORBIDDEN') { loseAccess('denied'); return }
    const message = timedOut ? 'Сервер не успел ответить. Можно повторить запрос или выбрать другой период.'
      : info.code === 'STATS_CONTRACT_UNAVAILABLE' ? 'Получены неподдерживаемые данные статистики. Обновите приложение и повторите запрос.'
        : info.status === 400 ? 'Не удалось применить даты или фильтры. Проверьте период: от 1 до 370 календарных дней.'
          : 'Не удалось загрузить статистику. Проверьте соединение и попробуйте ещё раз.'
    const retry = el('button', { text: 'Повторить' }); retry.onclick = () => void load()
    error.replaceChildren(el('h3', { text: 'Статистика не загружена' }), el('p', { text: message }), retry); error.hidden = false
  }
  const activePeriod = () => buttons.forEach((button, index) => {
    const selected = periods[index][0] === preset
    button.dataset.active = String(selected); button.setAttribute('aria-pressed', String(selected))
  })
  const show = (data: VenueKpiSnapshot) => {
    const start = calendarDateAt(data.period.from, data.period.timezone)
    const end = shiftCalendarDate(calendarDateAt(data.period.to, data.period.timezone), -1)
    const refreshed = new Intl.DateTimeFormat('ru-RU', { timeZone: data.period.timezone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(data.asOf))
    metadata.replaceChildren(el('p', { text: `Период: ${formatCalendarDate(start)} — ${formatCalendarDate(end)} включительно` }),
      el('p', { text: `Часовой пояс: ${data.period.timezone}` }), el('p', { text: `Данные на ${refreshed}` }))
    const metrics = [...Object.values(data.metrics), ...data.closedShiftFinances.flatMap(group => Object.values(group.metrics)),
      ...data.openShiftFinances.flatMap(group => Object.values(group.metrics))]
    const partial = metrics.some(value => value.availability === 'PARTIAL' || value.coverage === 'PARTIAL')
    summary.dataset.coverage = partial ? 'partial' : 'complete'
    summary.replaceChildren(el('strong', { text: partial ? 'Частичные данные' : 'Статистика по сохранённым операциям' }),
      el('p', { text: partial ? 'Не все операции можно учесть полностью. Подтверждённые значения и ограничения указаны у каждого показателя.'
        : 'Пустые выборки и показатели без достаточных данных отмечены отдельно. Отсутствие старой истории не означает нулевую активность.' }))
    if (metrics.some(value => value.reason === 'LEGACY_HISTORY_UNKNOWN')) summary.append(el('p', { text: 'Часть старых операций не содержит точного времени.' }))
    if (data.limitations.includes('VENUE_TIMEZONE_FALLBACK')) summary.append(el('p', { text: 'Часовой пояс заведения не настроен или недоступен. Используется указанный сервером резервный часовой пояс.' }))
    const known = ['RECORDED_DOMAIN_FACTS_ONLY', 'STATE_BREAKDOWNS_ARE_SNAPSHOT_NOT_TRANSITIONS', 'CLOSED_SHIFTS_NOT_CALENDAR_CASH_FLOW',
      'VOID_PAYMENT_NOT_REFUND', 'SEATINGS_NOT_UNIQUE_PEOPLE', 'VENUE_TIMEZONE_FALLBACK']
    if (data.limitations.some(value => !known.includes(value))) summary.append(el('p', { text: 'Сервер сообщил дополнительные ограничения расчёта. Полноту данных пока нельзя подтвердить.' }))
    summary.hidden = false; content.replaceChildren(renderStatsContent(data)); stopLoading(); status.textContent = ''
  }
  const load = async (correction = 0): Promise<void> => {
    if (disposed || denied || !active || document.hidden) return
    if (!currentActor()) { loseAccess('changed'); return }
    abort(); clearView(); error.hidden = true; validation.textContent = ''
    const seq = sequence
    const request = new AbortController(); controller = request
    let timedOut = false
    skeleton.hidden = false; content.setAttribute('aria-busy', 'true'); status.textContent = 'Загружаем статистику…'
    const slow = window.setTimeout(() => {
      if (current(seq)) status.textContent = 'Загрузка занимает больше обычного. Можно выбрать другой период или повторить запрос.'
    }, 8000)
    const timeout = window.setTimeout(() => { timedOut = true; request.abort() }, 30000)
    try {
      if (!timezone) {
        // Obtain server timezone/asOf through the smallest existing read-only section before selecting venue dates.
        const day = new Date().toISOString().slice(0, 10)
        const initial = await venueGetStatsV1(backendUrl, { venueId, from: day, to: shiftCalendarDate(day, 1), section: 'seatings' }, deps, request.signal)
        if (!current(seq)) { if (!currentActor()) loseAccess('changed'); return }
        if (!initial.ok) { if (initial.error.code !== REQUEST_ABORTED_CODE || timedOut) fail(initial.error, timedOut); return }
        timezone = initial.data.period.timezone; serverAnchor = { ms: Date.parse(initial.data.asOf), received: Date.now() }
      }
      if (preset !== 'custom') {
        const now = new Date(serverAnchor ? serverAnchor.ms + Date.now() - serverAnchor.received : Date.now()).toISOString()
        const dates = presetDates(preset, now, timezone); from.value = dates.from; to.value = dates.to
      }
      const invalid = periodValidation(from.value, to.value)
      from.setAttribute('aria-invalid', String(Boolean(invalid))); to.setAttribute('aria-invalid', String(Boolean(invalid)))
      if (invalid) { validation.textContent = invalid; stopLoading(); status.textContent = ''; return }
      const query = { venueId, from: from.value, to: shiftCalendarDate(to.value, 1), section: section.value as StatsSection,
        ...(operational(section.value as StatsSection) && table.value ? { tableId: Number(table.value) } : {}) }
      const result = await venueGetStatsV1(backendUrl, query, deps, request.signal)
      if (!current(seq)) { if (!currentActor()) loseAccess('changed'); return }
      if (!result.ok) { if (result.error.code !== REQUEST_ABORTED_CODE || timedOut) fail(result.error, timedOut); return }
      timezone = result.data.period.timezone; serverAnchor = { ms: Date.parse(result.data.asOf), received: Date.now() }
      // Correct a midnight crossing or a venue timezone change without treating a day as 24 hours.
      if (preset !== 'custom' && correction === 0) {
        const expected = presetDates(preset, result.data.asOf, timezone)
        if (expected.from !== query.from || shiftCalendarDate(expected.to, 1) !== query.to) { await load(1); return }
      }
      show(result.data)
    } catch {
      if (current(seq)) fail({ status: 0, code: 'STATS_CONTRACT_UNAVAILABLE' })
    } finally {
      window.clearTimeout(slow); window.clearTimeout(timeout)
      if (controller === request) controller = null
    }
  }
  const loadTables = async () => {
    if (tablesLoaded || tableController || !currentActor() || denied || disposed) return
    table.disabled = true
    const request = new AbortController(); tableController = request
    const result = await venueGetTables(backendUrl, venueId, deps, request.signal)
    if (disposed || denied || tableController !== request) return
    tableController = null
    if (!currentActor()) { loseAccess('changed'); return }
    if (!result.ok || !Array.isArray(result.data.tables)) {
      tableNotice.textContent = 'Фильтр по столу сейчас недоступен. Показаны данные всех столов.'; return
    }
    result.data.tables.forEach(item => table.add(new Option(`Стол ${item.tableNumber}${item.isActive ? '' : ' · неактивен'}`, String(item.tableId))))
    tablesLoaded = true; table.disabled = false
  }
  const disposables = buttons.map((button, index) => on(button, 'click', () => {
    preset = periods[index][0]; activePeriod(); form.hidden = preset !== 'custom'; validation.textContent = ''
    if (preset === 'custom') { abort(); clearView(); error.hidden = true; status.textContent = 'Выберите даты и нажмите «Показать период».'; from.focus() }
    else void load()
  }))
  disposables.push(on(form, 'submit', event => { event.preventDefault(); void load() }), on(refresh, 'click', () => void load()),
    on(section, 'change', () => {
      table.value = ''; tableLabel.hidden = !operational(section.value as StatsSection); tableNotice.hidden = tableLabel.hidden
      tableNotice.textContent = 'Фильтр относится только к выбранным показателям и текущему столу. Прошлые перемещения не восстанавливаются.'
      if (!tableLabel.hidden) void loadTables()
      void load()
    }), on(table, 'change', () => void load()), ...[from, to].map(input => on(input, 'input', () => {
      abort(); clearView(); error.hidden = true; validation.textContent = ''; status.textContent = 'Нажмите «Показать период», чтобы применить даты.'
    })))
  const resume = () => {
    if (!currentActor()) { loseAccess('changed'); return }
    if (document.hidden || !active) { abort(); clearView(); status.textContent = 'Данные скрыты до возвращения в раздел.'; return }
    if (!controller) { timezone = null; void load() }
  }
  const activated = () => { active = true; resume() }
  const deactivated = () => { active = false; resume() }
  document.addEventListener('visibilitychange', resume); window.addEventListener('focus', resume)
  webApp?.onEvent?.('activated', activated); webApp?.onEvent?.('deactivated', deactivated)
  const identityWatch = window.setInterval(() => { if (!currentActor()) loseAccess('changed') }, 1000)
  activePeriod(); void load()
  return () => {
    disposed = true; abort(); tableController?.abort(); window.clearInterval(identityWatch)
    document.removeEventListener('visibilitychange', resume); window.removeEventListener('focus', resume)
    webApp?.offEvent?.('activated', activated); webApp?.offEvent?.('deactivated', deactivated)
    disposables.forEach(dispose => dispose()); clearView(); root.replaceChildren()
  }
}
