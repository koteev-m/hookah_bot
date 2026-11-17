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
type StatsView = 'overview' | 'products' | 'additions' | 'finance' | 'service'
const sections: [StatsView, string][] = [['overview', 'Обзор'], ['finance', 'Финансы'], ['products', 'Товары'], ['additions', 'Дозаказы'], ['service', 'Обслуживание']]
const serverSection = (view: string): StatsSection => view === 'overview' || view === 'service' ? 'all' : view as StatsSection

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
  const title = el('div', { className: 'stats-title-row' })
  append(title, el('h2', { text: 'Статистика' }),
    el('p', { className: 'stats-venue-name', text: access.venueName?.trim() || 'Текущее заведение' }))
  header.append(title)
  const controls = el('div', { className: 'stats-controls' })
  const modeLabel = el('fieldset', { className: 'stats-finance-mode' })
  modeLabel.append(el('legend', { text: 'Финансовый период' }))
  const financeModes = (['current', 'closed'] as const).map(value => {
    const label = el('label'); const input = el('input'); input.type = 'radio'; input.name = 'stats-finance-mode'; input.value = value; input.checked = value === 'closed'
    append(label, input, el('span', { text: value === 'current' ? 'Текущая смена' : 'Закрытые смены' })); modeLabel.append(label); return input
  })
  const financeMode = () => financeModes[0].checked ? 'current' : 'closed'
  const currentFinance = () => ['overview', 'finance'].includes(section.value) && financeMode() === 'current'
  const periodRow = el('div', { className: 'venue-stats-periods' }); periodRow.setAttribute('aria-label', 'Период статистики')
  const buttons = periods.map(([key, title]) => {
    const button = el('button', { className: 'button-secondary', text: title }); button.type = 'button'
    button.setAttribute('aria-label', title)
    button.textContent = key === '7d' ? '7 дней' : key === '30d' ? '30 дней' : key === 'custom' ? 'Свой период' : title
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
  const draftNotice = el('p', { className: 'stats-note' }); draftNotice.hidden = true
  const filterRow = el('div', { className: 'stats-filter-row' })
  const section = el('select'); section.id = 'stats-section'
  sections.forEach(([value, label]) => section.add(new Option(label, value)))
  const sectionLabel = el('label'); sectionLabel.htmlFor = section.id
  append(sectionLabel, el('span', { className: 'stats-section-label', text: 'Показатели' }), section)
  const serviceKind = el('select'); serviceKind.id = 'stats-service-kind'
  for (const [value, label] of [['all', 'Все подробности'], ['orders', 'Заказы'], ['bookings', 'Бронирования'], ['seatings', 'Посадки'], ['calls', 'Вызовы']]) serviceKind.add(new Option(label, value))
  const serviceLabel = el('label', { text: 'Показатели обслуживания' }); serviceLabel.htmlFor = serviceKind.id; serviceLabel.append(serviceKind); serviceLabel.hidden = true
  const selectedSection = () => section.value === 'service' ? serviceKind.value as StatsSection : serverSection(section.value)
  const table = el('select'); table.id = 'stats-table'; table.add(new Option('Все столы', ''))
  const tableLabel = el('label', { text: 'Текущий стол' }); tableLabel.htmlFor = table.id; tableLabel.append(table); tableLabel.hidden = true
  const productControls = el('details', { className: 'stats-product-controls' }); productControls.hidden = true
  const productFilters = el('form', { className: 'stats-product-filters' })
  append(productControls, el('summary', { text: 'Поиск и фильтры товаров' }), productFilters)
  const search = el('input'); search.type = 'search'; search.maxLength = 100; search.placeholder = 'Поиск товара'
  search.setAttribute('aria-label', 'Поиск товара')
  const category = el('select'); category.setAttribute('aria-label', 'Категория товаров'); category.add(new Option('Все категории', ''))
  const currency = el('select'); currency.setAttribute('aria-label', 'Валюта товаров'); currency.add(new Option('Все валюты', ''))
  const sort = el('select'); sort.setAttribute('aria-label', 'Сортировка товаров')
  for (const [value, label] of [['popular', 'Популярные'], ['rare', 'Редко заказываемые и без выдач'], ['amount', 'По стоимости до скидок']]) sort.add(new Option(label, value))
  const find = el('button', { text: 'Найти товары' }); find.type = 'submit'
  append(productFilters, search, category, currency, sort, find)
  let page = 1
  let expandedCategory: string | null = null
  let expandedItem: string | null = null
  let lastSnapshot: VenueKpiSnapshot | null = null
  let appliedProducts = { search: '', categoryKey: '', currency: '', sort: 'popular' }
  const resetProducts = () => { page = 1; expandedCategory = null; expandedItem = null }
  const capturePosition = () => {
    const node = document.activeElement instanceof HTMLElement ? document.activeElement : null
    return { key: node?.dataset.productFocus, top: node?.getBoundingClientRect().top ?? 0, y: window.scrollY }
  }
  const restorePosition = (position: ReturnType<typeof capturePosition>) => {
    const node = Array.from(content.querySelectorAll<HTMLElement>('[data-product-focus]')).find(n => n.dataset.productFocus === position.key)
    if (node && position.key) { node.focus({ preventScroll: true }); window.scrollTo(0, position.y + node.getBoundingClientRect().top - position.top) }
    else window.scrollTo(0, position.y)
  }
  const refresh = el('button', { className: 'button-secondary', text: 'Обновить' }); refresh.type = 'button'; refresh.setAttribute('aria-label', 'Обновить статистику')
  append(filterRow, sectionLabel, serviceLabel, tableLabel, refresh)
  const tableNotice = el('p', { className: 'stats-note' }); tableNotice.hidden = true
  append(controls, filterRow, modeLabel, periodRow, form, productControls, tableNotice, validation, draftNotice)
  const periodCaption = el('p', { className: 'stats-period-caption' }); periodCaption.hidden = true
  const metadata = el('div', { className: 'stats-metadata' })
  const metadataDetails = el('details', { className: 'stats-period-details' }); metadataDetails.hidden = true
  metadataDetails.append(el('summary', { text: 'Период и актуальность данных' }))
  append(header, controls, periodCaption, metadata)
  const status = el('p', { className: 'status stats-load-status' }); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite')
  const skeleton = el('div', { className: 'stats-skeleton' }); skeleton.setAttribute('aria-hidden', 'true'); skeleton.hidden = true
  for (let i = 0; i < 6; i++) skeleton.append(el('div'))
  const error = el('section', { className: 'error-card' }); error.setAttribute('role', 'alert'); error.hidden = true
  const summary = el('div', { className: 'stats-coverage' }); summary.hidden = true
  const content = el('div', { className: 'stats-content' })
  append(wrapper, header, status, skeleton, error, summary, content, metadataDetails); root.replaceChildren(wrapper)

  let disposed = false
  let denied = false
  let active = true
  let sequence = 0
  let controller: AbortController | null = null
  let tableController: AbortController | null = null
  let tablesLoaded = false
  let preset: StatsPreset = '7d'
  let appliedPeriod: { from: string; to: string } | null = null
  let timezone: string | null = null
  let serverAnchor: { ms: number; received: number } | null = null
  const currentActor = () => getTelegramContext().telegramUserId === actor
  const current = (seq: number) => !disposed && !denied && sequence === seq && currentActor()
  const clearView = () => { content.replaceChildren(); lastSnapshot = null; metadata.replaceChildren(); metadataDetails.hidden = true; periodCaption.hidden = true; summary.replaceChildren(); summary.hidden = true }
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
  const fail = (info: ApiErrorInfo, timedOut = false, preserveProducts = false, retryRequest = () => void load()) => {
    if (!preserveProducts) clearView(); stopLoading(); status.textContent = ''
    if (info.status === 401 || info.code === 'UNAUTHORIZED' || info.code === 'INITDATA_INVALID') { clearSession(); loseAccess('changed'); return }
    if (info.status === 403 || info.code === 'FORBIDDEN') { loseAccess('denied'); return }
    const message = timedOut ? 'Сервер не успел ответить. Можно повторить запрос или выбрать другой период.'
      : info.code === 'STATS_CONTRACT_UNAVAILABLE' ? 'Получены неподдерживаемые данные статистики. Обновите приложение и повторите запрос.'
        : info.status === 400 ? 'Не удалось применить даты или фильтры. Проверьте период: от 1 до 370 календарных дней.'
          : 'Не удалось загрузить статистику. Проверьте соединение и попробуйте ещё раз.'
    const retry = el('button', { text: 'Повторить' }); retry.onclick = retryRequest
    error.replaceChildren(el('h3', { text: 'Статистика не загружена' }), el('p', { text: message }), retry)
    if (preserveProducts) error.append(el('p', { text: 'Сохранены последние загруженные итоги. Новые фильтры не применены.' }))
    error.hidden = false
  }
  const activePeriod = () => buttons.forEach((button, index) => {
    const selected = periods[index][0] === preset
    button.dataset.active = String(selected); button.setAttribute('aria-pressed', String(selected))
  })
  const renderContent = (data: VenueKpiSnapshot) => {
    content.replaceChildren(renderStatsContent(data, section.value as StatsView, {
      category: expandedCategory, item: expandedItem,
      filtered: Boolean(appliedProducts.search || appliedProducts.categoryKey || appliedProducts.currency),
      onCategory: key => {
        if (!currentActor() || denied || disposed) return
        const position = capturePosition()
        expandedCategory = expandedCategory === key ? null : key; expandedItem = null; page = 1
        renderContent(data); restorePosition(position)
        if (expandedCategory) void load(0, true)
        else { abort(); status.textContent = '' }
      },
      onItem: key => {
        if (!currentActor() || denied || disposed) return
        const position = capturePosition(); expandedItem = expandedItem === key ? null : key
        renderContent(data); restorePosition(position)
      },
      onPage: selected => { page = selected; expandedItem = null; void load(0, true) }
    }, financeMode(), () => {
      if (!currentActor() || denied || disposed) return
      financeModes[0].checked = true; syncPeriods(); void load()
    }, view => {
      if (!currentActor() || denied || disposed) return
      section.value = view; section.focus({ preventScroll: true }); section.dispatchEvent(new Event('change'))
    }))
  }
  const syncPeriods = () => {
    const financial = ['overview', 'finance'].includes(section.value)
    wrapper.classList.toggle('stats-financial-view', financial)
    modeLabel.hidden = !financial
    const metadataParent = financial ? metadataDetails : header
    metadataParent.append(metadata)
    metadataDetails.hidden = !financial || !metadata.childElementCount
    periodCaption.hidden = !financial || currentFinance() || !metadata.childElementCount
    periodRow.hidden = currentFinance(); buttons.forEach(b => { b.disabled = currentFinance() })
    form.hidden = currentFinance() || preset !== 'custom'
    validation.hidden = currentFinance()
    draftNotice.hidden = currentFinance() || preset !== 'custom' || Boolean(appliedPeriod && appliedPeriod.from === from.value && appliedPeriod.to === to.value)
    draftNotice.textContent = appliedPeriod ? `Даты не применены. Последний применённый период: ${formatCalendarDate(appliedPeriod.from)} — ${formatCalendarDate(appliedPeriod.to)}.` : 'Даты не применены. Выберите корректный период и нажмите «Показать период».'
  }
  const show = (data: VenueKpiSnapshot) => {
    syncPeriods()
    wrapper.classList.toggle('stats-products-view', section.value === 'products')
    const start = calendarDateAt(data.period.from, data.period.timezone)
    const end = shiftCalendarDate(calendarDateAt(data.period.to, data.period.timezone), -1)
    const refreshed = new Intl.DateTimeFormat('ru-RU', { timeZone: data.period.timezone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(data.asOf))
    const periodText = currentFinance() ? 'Вся текущая смена с момента открытия' : `${['overview', 'finance'].includes(section.value) ? 'Закрытые смены по дате закрытия' : 'Период'}: ${formatCalendarDate(start)} — ${formatCalendarDate(end)} включительно`
    metadata.replaceChildren(el('p', { text: periodText }),
      el('p', { text: `Данные на ${refreshed} · Часовой пояс: ${data.period.timezone}` }))
    periodCaption.textContent = `${start.split('-').reverse().join('.')} — ${end.split('-').reverse().join('.')}`
    metadataDetails.hidden = !['overview', 'finance'].includes(section.value)
    periodCaption.hidden = metadataDetails.hidden || currentFinance()
    const financial = ['overview', 'finance'].includes(section.value)
    const metrics = financial ? [data.metrics[currentFinance() ? 'finance.openReportCoverage' : 'finance.closedReports'],
      ...(currentFinance() ? data.openShiftFinances : data.closedShiftFinances).flatMap(group => Object.values(group.metrics))].filter(value => value != null)
      : [...Object.values(data.metrics), ...(data.products ? [data.products.coverage] : [])]
    const partial = metrics.some(value => value.availability === 'PARTIAL' || value.coverage === 'PARTIAL')
    summary.dataset.coverage = partial ? 'partial' : 'complete'
    summary.replaceChildren(...(partial ? [el('strong', { text: 'Частичные данные' }), el('p', { text: 'Не все операции можно учесть полностью. Ограничения указаны у показателей.' })]
      : [el('p', { text: 'По сохранённым операциям. Показатели без достаточных данных отмечены отдельно.' })]))
    if (metrics.some(value => value !== data.products?.coverage && value.reason === 'LEGACY_HISTORY_UNKNOWN')) summary.append(el('p', { text: 'Часть старых операций не содержит точного времени.' }))
    if (data.limitations.includes('VENUE_TIMEZONE_FALLBACK')) summary.append(el('p', { text: 'Часовой пояс заведения не настроен или недоступен. Используется указанный сервером резервный часовой пояс.' }))
    const known = ['RECORDED_DOMAIN_FACTS_ONLY', 'STATE_BREAKDOWNS_ARE_SNAPSHOT_NOT_TRANSITIONS', 'CLOSED_SHIFTS_NOT_CALENDAR_CASH_FLOW',
      'VOID_PAYMENT_NOT_REFUND', 'SEATINGS_NOT_UNIQUE_PEOPLE', 'VENUE_TIMEZONE_FALLBACK']
    if (data.limitations.some(value => !known.includes(value))) summary.append(el('p', { text: 'Сервер сообщил дополнительные ограничения расчёта. Полноту данных пока нельзя подтвердить.' }))
    summary.hidden = (financial && !partial || section.value === 'products') && !data.limitations.some(v => !known.includes(v) || v === 'VENUE_TIMEZONE_FALLBACK')
    lastSnapshot = data
    if (expandedCategory && !data.products?.categoryTotals?.some(g => g.category.key === expandedCategory)) { expandedCategory = null; expandedItem = null; page = 1 }
    renderContent(data)
    if (data.products) {
      const categoryValue = category.value
      while (category.options.length > 1) category.remove(1)
      data.products.categoryOptions?.forEach(option => category.add(new Option(option.name, option.key)))
      category.value = categoryValue
      const currencyValue = currency.value
      while (currency.options.length > 1) currency.remove(1)
      data.products.currencies.forEach(value => currency.add(new Option(value, value))); currency.value = currencyValue
    }; stopLoading(); status.textContent = ''
  }
  const load = async (correction = 0, preserveProducts = false, productsFilter?: typeof appliedProducts): Promise<void> => {
    if (disposed || denied || !active || document.hidden) return
    if (!currentActor()) { loseAccess('changed'); return }
    const position = capturePosition()
    const preserve = preserveProducts && section.value === 'products' && lastSnapshot != null
    abort(); if (!preserve) clearView(); error.hidden = true; validation.textContent = ''
    const seq = sequence
    const request = new AbortController(); controller = request
    let timedOut = false
    skeleton.hidden = preserve; content.setAttribute('aria-busy', 'true'); status.textContent = preserve ? 'Обновляем товары и итоги…' : 'Загружаем статистику…'
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
      const currentMode = currentFinance()
      const queryPreset = currentMode ? (appliedPeriod ? null : 'today') : preset === 'custom' ? null : preset
      let dates = currentMode && appliedPeriod ? appliedPeriod : { from: from.value, to: to.value }
      if (queryPreset) {
        const now = new Date(serverAnchor ? serverAnchor.ms + Date.now() - serverAnchor.received : Date.now()).toISOString()
        dates = presetDates(queryPreset, now, timezone)
        if (!currentMode) { from.value = dates.from; to.value = dates.to }
      }
      const invalid = periodValidation(dates.from, dates.to)
      if (!currentMode) { from.setAttribute('aria-invalid', String(Boolean(invalid))); to.setAttribute('aria-invalid', String(Boolean(invalid))) }
      if (invalid) { validation.textContent = invalid; syncPeriods(); stopLoading(); status.textContent = ''; return }
      const query = { venueId, from: dates.from, to: shiftCalendarDate(dates.to, 1), section: selectedSection(),
        ...(section.value === 'products' ? { ...(productsFilter ?? appliedProducts), page: productsFilter ? 1 : page, ...(expandedCategory ? { expandedCategoryKey: expandedCategory } : {}) } : {}),
        ...(operational(selectedSection()) && table.value ? { tableId: Number(table.value) } : {}) }
      const result = await venueGetStatsV1(backendUrl, query, deps, request.signal)
      if (!current(seq)) { if (!currentActor()) loseAccess('changed'); return }
      if (!result.ok) { if (result.error.code !== REQUEST_ABORTED_CODE || timedOut) fail(result.error, timedOut, preserve && productsFilter != null, () => void load(0, preserveProducts, productsFilter)); return }
      timezone = result.data.period.timezone; serverAnchor = { ms: Date.parse(result.data.asOf), received: Date.now() }
      // Correct a midnight crossing or a venue timezone change without treating a day as 24 hours.
      if (queryPreset && correction === 0) {
        const expected = presetDates(queryPreset, result.data.asOf, timezone)
        if (expected.from !== query.from || shiftCalendarDate(expected.to, 1) !== query.to) { await load(1, preserveProducts, productsFilter); return }
      }
      if (productsFilter) { appliedProducts = productsFilter; page = 1 }
      if (!currentMode) appliedPeriod = { ...dates }
      show(result.data)
      if (preserve) restorePosition(position)
    } catch {
      if (current(seq)) fail({ status: 0, code: 'STATS_CONTRACT_UNAVAILABLE' }, false, preserve && productsFilter != null, () => void load(0, preserveProducts, productsFilter))
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
    preset = periods[index][0]; resetProducts(); activePeriod(); form.hidden = preset !== 'custom'; validation.textContent = ''
    if (preset === 'custom') { abort(); clearView(); error.hidden = true; status.textContent = 'Выберите даты и нажмите «Показать период».'; from.focus() }
    else void load()
  }))
  disposables.push(on(modeLabel, 'change', () => { syncPeriods(); void load() }), on(productFilters, 'submit', event => { event.preventDefault(); if (sort.value === 'amount' && !currency.value) { validation.textContent = 'Выберите одну валюту для сортировки по стоимости.'; return }; void load(0, true, { search: search.value.trim(), categoryKey: category.value, currency: currency.value, sort: sort.value }) }), on(form, 'submit', event => { event.preventDefault(); resetProducts(); void load() }), on(refresh, 'click', () => void load(0, section.value === 'products')),
    on(section, 'change', () => {
      wrapper.classList.toggle('stats-products-view', section.value === 'products')
      syncPeriods(); resetProducts(); productControls.hidden = section.value !== 'products'; serviceLabel.hidden = section.value !== 'service'
      table.value = ''; tableLabel.hidden = !operational(selectedSection()); tableNotice.hidden = tableLabel.hidden
      tableNotice.textContent = 'Фильтр относится только к выбранным показателям и текущему столу. Прошлые перемещения не восстанавливаются.'
      if (!tableLabel.hidden) void loadTables()
      void load()
    }), on(serviceKind, 'change', () => { table.value = ''; tableLabel.hidden = !operational(selectedSection()); tableNotice.hidden = tableLabel.hidden; if (!tableLabel.hidden) void loadTables(); void load() }), on(table, 'change', () => void load()), ...[from, to].map(input => on(input, 'input', () => {
      abort(); clearView(); error.hidden = true; validation.textContent = ''; status.textContent = 'Нажмите «Показать период», чтобы применить даты.'; syncPeriods()
    })))
  const resume = () => {
    if (!currentActor()) { loseAccess('changed'); return }
    if (document.hidden || !active) { abort(); clearView(); status.textContent = 'Данные скрыты до возвращения в раздел.'; return }
    if (!controller) { timezone = null; void load(0, section.value === 'products') }
  }
  const activated = () => { active = true; resume() }
  const deactivated = () => { active = false; resume() }
  document.addEventListener('visibilitychange', resume); window.addEventListener('focus', resume)
  webApp?.onEvent?.('activated', activated); webApp?.onEvent?.('deactivated', deactivated)
  const identityWatch = window.setInterval(() => { if (!currentActor()) loseAccess('changed') }, 1000)
  syncPeriods(); activePeriod(); void load()
  return () => {
    disposed = true; abort(); tableController?.abort(); window.clearInterval(identityWatch)
    document.removeEventListener('visibilitychange', resume); window.removeEventListener('focus', resume)
    webApp?.offEvent?.('activated', activated); webApp?.offEvent?.('deactivated', deactivated)
    disposables.forEach(dispose => dispose()); clearView(); root.replaceChildren()
  }
}
