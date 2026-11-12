import './venueStats.css'
import './platformAnalytics.css'
import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken, getStoredSession } from '../shared/api/auth'
import { platformGetAnalyticsV1, type PlatformAnalyticsSection, type PlatformAnalyticsSnapshot } from '../shared/api/platformAnalyticsApi'
import type { PlatformMeResponse } from '../shared/api/platformDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { getTelegramContext } from '../shared/telegram'
import { append, el, on } from '../shared/ui/dom'
import { formatCalendarDate, periodValidation, presetDates, shiftCalendarDate, type StatsPreset } from '../shared/ui/venueStatsFormat'
import { platformSections, renderPlatformAnalyticsContent } from './platformAnalyticsContent'

type Options = { root: HTMLDivElement | null; backendUrl: string; isDebug: boolean; access: PlatformMeResponse | null;
  onNavigate: (hash: string) => void; onAccessLost: () => void }
const periods: [StatsPreset, string][] = [['today', 'Сегодня'], ['7d', 'Последние 7 дней'], ['30d', 'Последние 30 дней'], ['custom', 'Произвольный период']]
const dateTime = (value: string) => new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))

export function renderPlatformAnalyticsScreen(options: Options) {
  const { root, backendUrl, isDebug, onNavigate, onAccessLost } = options
  if (!root) return () => undefined
  if (!options.access?.ok) {
    root.replaceChildren(el('h2', { text: 'Аналитика платформы' }), el('p', { text: 'Раздел доступен только владельцу платформы.' }))
    return () => root.replaceChildren()
  }
  const actor = getTelegramContext().telegramUserId
  const webApp = getTelegramContext().webApp
  let sessionToken = getStoredSession()?.token ?? null
  const deps = { isDebug, clearSession, getAccessToken: async () => {
    const result = await getAccessToken()
    if (result.ok && getTelegramContext().telegramUserId === actor) sessionToken = result.data.token
    return result
  } }
  const wrapper = el('div', { className: 'venue-stats platform-analytics' }); wrapper.lang = 'ru'
  const header = el('section', { className: 'card stats-header' })
  const heading = el('h2', { text: 'Аналитика платформы' }); heading.tabIndex = -1
  append(header, el('p', { className: 'stats-eyebrow', text: 'Платформа · аналитика' }), heading,
    el('p', { className: 'stats-intro', text: 'Состояние и подтверждённая активность. Время — UTC.' }))
  const back = el('button', { className: 'button-secondary', text: 'К заведениям' }); back.onclick = () => onNavigate('#/venues')
  const controls = el('div', { className: 'stats-controls' })
  const periodRow = el('div', { className: 'venue-stats-periods' }); periodRow.setAttribute('aria-label', 'Период аналитики')
  const buttons = periods.map(([key, title]) => {
    const button = el('button', { className: 'button-secondary', text: title }); button.type = 'button'; button.dataset.period = key; periodRow.append(button); return button
  })
  const form = el('form', { className: 'stats-custom-period' }); form.hidden = true; form.noValidate = true
  const from = el('input'); from.type = 'date'; from.id = 'analytics-date-from'; from.required = true
  const to = el('input'); to.type = 'date'; to.id = 'analytics-date-to'; to.required = true
  const fromLabel = el('label', { text: 'Начало периода' }); fromLabel.htmlFor = from.id; fromLabel.append(from)
  const toLabel = el('label', { text: 'Окончание периода (включительно)' }); toLabel.htmlFor = to.id; toLabel.append(to)
  const apply = el('button', { text: 'Показать период' }); apply.type = 'submit'
  const validation = el('p', { className: 'stats-validation', id: 'analytics-validation' }); validation.setAttribute('role', 'alert')
  for (const input of [from, to]) input.setAttribute('aria-describedby', validation.id)
  append(form, fromLabel, toLabel, apply, validation, el('p', { className: 'stats-note', text: 'Обе даты включаются. От 1 до 370 календарных дней UTC.' }))
  const filter = el('div', { className: 'stats-filter-row' })
  const section = el('select'); section.id = 'analytics-section'; section.add(new Option('Все разделы', 'all'))
  platformSections.forEach(([key, title]) => section.add(new Option(title, key)))
  const sectionLabel = el('label', { text: 'Показатели' }); sectionLabel.htmlFor = section.id; sectionLabel.append(section)
  const refresh = el('button', { className: 'button-secondary', text: 'Обновить аналитику' }); refresh.type = 'button'
  append(filter, sectionLabel, refresh); append(controls, periodRow, form, filter)
  const metadata = el('div', { className: 'stats-metadata' }); append(header, controls, metadata, back)
  const status = el('p', { className: 'stats-load-status' }); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite')
  const skeleton = el('div', { className: 'stats-skeleton' }); skeleton.setAttribute('aria-hidden', 'true'); skeleton.hidden = true
  for (let i = 0; i < 4; i++) skeleton.append(el('div'))
  const error = el('section', { className: 'error-card' }); error.setAttribute('role', 'alert'); error.hidden = true
  const coverage = el('div', { className: 'stats-coverage' }); coverage.hidden = true
  const content = el('div', { className: 'stats-content' })
  append(wrapper, header, status, skeleton, error, coverage, content); root.replaceChildren(wrapper); heading.focus({ preventScroll: true })

  let disposed = false, denied = false, active = true, sequence = 0
  let controller: AbortController | null = null
  let preset: StatsPreset = 'today'
  let anchor: { ms: number; received: number } | null = null
  const sameActor = () => getTelegramContext().telegramUserId === actor
  const current = (seq: number) => !disposed && !denied && active && !document.hidden && sequence === seq && sameActor()
  const clear = () => { content.replaceChildren(); metadata.replaceChildren(); coverage.replaceChildren(); coverage.hidden = true }
  const stop = () => { skeleton.hidden = true; content.removeAttribute('aria-busy') }
  const abort = () => { sequence++; controller?.abort(); controller = null; stop() }
  const loseAccess = () => {
    if (disposed || denied) return
    denied = true; abort(); clear(); status.textContent = ''
    error.replaceChildren(el('h3', { text: 'Доступ к аналитике закрыт' }), el('p', { text: 'Аккаунт или права доступа изменились. Прежние данные удалены с экрана. Откройте приложение заново для проверки доступа.' }))
    error.hidden = false
    controls.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLButtonElement>('input,select,button').forEach(node => { node.disabled = true })
    onAccessLost()
  }
  const fail = (info: ApiErrorInfo, timeout = false) => {
    clear(); stop(); status.textContent = ''
    if ([401, 403].includes(info.status) || ['FORBIDDEN', 'UNAUTHORIZED', 'INITDATA_INVALID'].includes(info.code ?? '')) { loseAccess(); return }
    const retry = el('button', { text: 'Повторить' }); retry.onclick = () => void load()
    const message = timeout ? 'Сервер не успел ответить. Повторите запрос или выберите другой период.' : info.code === 'ANALYTICS_CONTRACT_UNAVAILABLE'
      ? 'Получены неподдерживаемые данные аналитики. Обновите приложение и повторите запрос.' : info.status === 400
        ? 'Проверьте даты и раздел: допустимо от 1 до 370 календарных дней UTC.' : 'Не удалось загрузить данные. Проверьте соединение и попробуйте ещё раз.'
    error.replaceChildren(el('h3', { text: 'Аналитика не загружена' }), el('p', { text: message }), retry); error.hidden = false
  }
  const show = (data: PlatformAnalyticsSnapshot) => {
    metadata.replaceChildren(el('p', { text: `Период: ${formatCalendarDate(data.period.from.slice(0, 10))} — ${formatCalendarDate(shiftCalendarDate(data.period.to.slice(0, 10), -1))} включительно · UTC` }),
      el('p', { text: `Данные на ${dateTime(data.asOf)} UTC` }))
    const waavFrom = new Date(Date.parse(data.asOf) - 168 * 3600000).toISOString()
    if (data.section === 'all' || data.section === 'venues') {
      const window = el('details', { className: 'analytics-window' })
      append(window, el('summary', { text: 'Окно активности: последние 168 часов' }),
        el('p', { text: `С ${dateTime(waavFrom)} до ${dateTime(data.asOf)} UTC, конец не включён. Выбранный период не влияет на это окно.` }))
      metadata.append(window)
    }
    const metrics = [...Object.values(data.metrics), ...Object.values(data.invoiceFinances).flatMap(groups => groups.flatMap(group => Object.values(group.metrics)))]
    const partial = metrics.some(value => value.availability === 'PARTIAL' || value.coverage === 'PARTIAL')
    coverage.dataset.coverage = partial ? 'partial' : 'complete'
    coverage.replaceChildren(el('strong', { text: partial ? 'Есть частичные данные' : 'Показатели по сохранённым данным' }),
      el('p', { text: 'Текущее состояние, выбранный период и последние 168 часов показаны отдельно. История до начала регистрации фактов может быть неполной.' }))
    if (metrics.some(value => value.reason === 'LEGACY_HISTORY_UNKNOWN')) coverage.append(el('p', { text: 'Часть старых операций не содержит точного времени. Это не означает отсутствие активности.' }))
    const known = ['RECORDED_DOMAIN_FACTS_ONLY', 'STATE_BREAKDOWNS_ARE_SNAPSHOT_NOT_TRANSITIONS', 'PAID_INVOICE_NOT_BANK_SETTLEMENT', 'COURTESY_NOT_PAYMENT', 'CHAT_LINK_NOT_DELIVERY', 'WAAV_WINDOW_INDEPENDENT_OF_SELECTED_PERIOD']
    if (data.limitations.some(value => !known.includes(value))) coverage.append(el('p', { text: 'Сервер сообщил дополнительные ограничения. Полноту расчёта пока нельзя подтвердить.' }))
    coverage.hidden = false; content.replaceChildren(renderPlatformAnalyticsContent(data, onNavigate)); stop(); status.textContent = ''
  }
  const load = async (correction = 0): Promise<void> => {
    if (disposed || denied || !active || document.hidden) return
    if (!sameActor()) { loseAccess(); return }
    abort(); clear(); error.hidden = true; validation.textContent = ''
    if (preset !== 'custom') {
      const now = new Date(anchor ? anchor.ms + Date.now() - anchor.received : Date.now()).toISOString()
      const dates = presetDates(preset, now, 'UTC'); from.value = dates.from; to.value = dates.to
    }
    const invalid = periodValidation(from.value, to.value)
    for (const input of [from, to]) input.setAttribute('aria-invalid', String(!!invalid))
    if (invalid) { validation.textContent = invalid; status.textContent = ''; return }
    const seq = sequence, request = new AbortController(); controller = request
    skeleton.hidden = false; content.setAttribute('aria-busy', 'true'); status.textContent = 'Загружаем аналитику…'
    const slow = window.setTimeout(() => { if (current(seq)) status.textContent = 'Загрузка занимает больше обычного. Можно выбрать другой период или повторить запрос.' }, 8000)
    const timeout = window.setTimeout(() => { if (current(seq)) { abort(); fail({ status: 0 }, true) } }, 30000)
    try {
      const query = { from: from.value, to: shiftCalendarDate(to.value, 1), section: section.value as PlatformAnalyticsSection }
      const result = await platformGetAnalyticsV1(backendUrl, query, deps, request.signal)
      if (!current(seq)) { if (!sameActor()) loseAccess(); return }
      if (!result.ok) { if (result.error.code !== REQUEST_ABORTED_CODE) fail(result.error); return }
      anchor = { ms: Date.parse(result.data.asOf), received: Date.now() }
      if (preset !== 'custom' && correction === 0) {
        const expected = presetDates(preset, result.data.asOf, 'UTC')
        if (expected.from !== query.from || shiftCalendarDate(expected.to, 1) !== query.to) { await load(1); return }
      }
      show(result.data)
    } catch { if (current(seq)) fail({ status: 0, code: 'ANALYTICS_CONTRACT_UNAVAILABLE' }) }
    finally { window.clearTimeout(slow); window.clearTimeout(timeout); if (controller === request) controller = null }
  }
  const selected = () => buttons.forEach((button, index) => { const active = periods[index][0] === preset; button.dataset.active = String(active); button.setAttribute('aria-pressed', String(active)) })
  const disposables = buttons.map((button, index) => on(button, 'click', () => {
    preset = periods[index][0]; selected(); form.hidden = preset !== 'custom'; validation.textContent = ''
    if (preset === 'custom') { abort(); clear(); error.hidden = true; status.textContent = 'Выберите даты и нажмите «Показать период».'; from.focus() }
    else void load()
  }))
  disposables.push(on(form, 'submit', event => { event.preventDefault(); void load() }), on(refresh, 'click', () => void load()), on(section, 'change', () => void load()),
    ...[from, to].map(input => on(input, 'input', () => { abort(); clear(); error.hidden = true; validation.textContent = ''; status.textContent = 'Нажмите «Показать период», чтобы применить даты.' })))
  const resume = () => {
    if (!sameActor()) { loseAccess(); return }
    if (document.hidden || !active) { abort(); clear(); status.textContent = 'Данные скрыты до возвращения в раздел.' }
    else if (!controller) void load()
  }
  const activated = () => { active = true; resume() }, deactivated = () => { active = false; resume() }
  document.addEventListener('visibilitychange', resume); window.addEventListener('focus', resume)
  webApp?.onEvent?.('activated', activated); webApp?.onEvent?.('deactivated', deactivated)
  // Check identity/logout before the next paint, including an ignored-abort late response.
  let frame = 0
  const watchIdentity = () => {
    if (disposed || denied) return
    if (!sameActor() || sessionToken !== null && getStoredSession()?.token !== sessionToken) { loseAccess(); return }
    frame = requestAnimationFrame(watchIdentity)
  }
  frame = requestAnimationFrame(watchIdentity); selected(); void load()
  return () => {
    disposed = true; abort(); cancelAnimationFrame(frame); document.removeEventListener('visibilitychange', resume); window.removeEventListener('focus', resume)
    webApp?.offEvent?.('activated', activated); webApp?.offEvent?.('deactivated', deactivated); disposables.forEach(dispose => dispose()); clear(); root.replaceChildren()
  }
}
