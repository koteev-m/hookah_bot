import type { KpiValue, ProductCategoryItem, VenueKpiSnapshot } from '../shared/api/venueStatsApi'
import { append, el } from '../shared/ui/dom'
import { exactCount, exactMoney } from '../shared/ui/venueStatsFormat'

export type ProductsView = {
  category: string | null
  item: string | null
  filtered: boolean
  onCategory: (key: string) => void
  onItem: (key: string) => void
  onPage: (page: number) => void
}
const partial = (value: KpiValue) => value.availability === 'PARTIAL' || value.coverage === 'PARTIAL'
function valueText(value: KpiValue, currency?: string): string {
  const known = value.value ?? value.observedValue
  if (known == null || value.availability === 'UNAVAILABLE' || (partial(value) && /^0+$/.test(known))) return '—'
  return currency ? exactMoney(known, currency) : exactCount(known)
}
function figure(value: KpiValue, currency?: string): HTMLElement {
  const text = valueText(value, currency)
  const span = el('span', { className: 'product-number' })
  if (currency && text.endsWith(currency)) {
    span.append(document.createTextNode(text.slice(0, -currency.length).trimEnd().replace(/[\u00a0\u202f]/g, ' ') + ' '),
      el('span', { className: 'stats-currency-unit', text: currency }))
  } else span.textContent = text
  if (partial(value)) {
    span.dataset.partial = 'true'
    span.append(el('small', { className: 'product-incomplete', text: ' · неполно' }))
  }
  return span
}
function productDetails(item: ProductCategoryItem): HTMLElement {
  const detail = el('div', { className: 'product-detail' })
  detail.append(el('strong', { text: item.name }))
  if (partial(item.units) || partial(item.gross)) detail.append(el('p', { className: 'stats-note', text: 'Показана подтверждённая часть. Не все старые операции или цены известны.' }))
  for (const source of item.sources) {
    const block = el('div', { className: 'product-source' })
    append(block, el('strong', { text: source.source === 'MANUAL_SALE' ? 'Ручные продажи' : 'Выданные заказы' }),
      el('p', { text: `Единиц: ${exactCount(source.units)} · подарочных: ${source.giftUnits == null ? 'недостаточно данных' : exactCount(source.giftUnits)} · без подарков: ${source.nonGiftUnits == null ? 'недостаточно данных' : exactCount(source.nonGiftUnits)}` }),
      el('p', { text: `Стоимость до скидок и подарков: ${source.snapshotGrossMinor == null ? 'недостаточно данных' : exactMoney(source.snapshotGrossMinor, source.currency)}` }))
    if (source.giftUnits == null) block.append(el('p', { className: 'stats-note', text: 'Денежная корректировка не позволяет точно определить количество подарочных единиц.' }))
    if (source.options.length) block.append(el('p', { text: `Варианты и опции: ${source.options.join(', ')}` }))
    detail.append(block)
  }
  const catalog = item.sources[0]
  detail.append(el('p', { className: 'stats-note', text: catalog.catalogCreatedAt == null ? 'Нет подтверждённой связи с текущей карточкой меню.'
    : `${catalog.currentlyAvailable ? 'В меню включён сейчас' : 'Сейчас недоступен'}${catalog.categoryActive === false ? ' · категория скрыта' : ''}. Доступность весь период не подтверждается.` }))
  return detail
}

export function renderVenueProducts(snapshot: VenueKpiSnapshot, view?: ProductsView): HTMLElement {
  const root = el('section', { className: 'stats-sections stats-section stats-products' })
  root.append(el('h2', { text: 'Товары' }))
  const report = snapshot.products
  if (!report?.summary || !report.categoryTotals || !report.categoryOptions) {
    root.append(el('p', { text: 'Товарный отчёт недоступен. Обновите приложение и сервер.' })); return root
  }
  const totals = report.summary
  const top = el('div', { className: 'product-summary' })
  const quantity = el('div'); append(quantity, el('span', { className: 'stats-note', text: 'Учтено единиц товаров и услуг' }), figure(totals.units)); top.append(quantity)
  const cost = el('div'); cost.append(el('span', { className: 'stats-note', text: 'Стоимость до скидок и подарков' }))
  totals.grossAmounts.forEach(amount => cost.append(figure(amount.gross, amount.currency)))
  if (!totals.grossAmounts.length) cost.append(el('span', { className: 'product-number', text: '—' }))
  top.append(cost)
  if ((totals.giftUnits.value ?? totals.giftUnits.observedValue) !== '0' || partial(totals.giftUnits)) {
    const gifts = el('div'); append(gifts, el('span', { className: 'stats-note', text: 'Подарочных единиц' }), figure(totals.giftUnits)); top.append(gifts)
  }
  root.append(top)
  if (view?.filtered) root.append(el('p', { className: 'stats-note', text: 'Итоги и категории относятся к найденным товарам по применённым фильтрам.' }))
  if (partial(totals.units) || partial(totals.giftUnits) || totals.grossAmounts.some(x => partial(x.gross))) root.append(el('p', { className: 'product-report-warning', text: 'Данные неполные: показана подтверждённая часть. Подробности — в «Как считается» и у товара.' }))
  if ([totals.units, ...totals.grossAmounts.map(x => x.gross)].some(x => x.reason === 'PRODUCT_CURRENCY_UNKNOWN')) root.append(el('p', { className: 'product-currency-warning', text: 'Есть операции с неизвестной валютой. Полнота суммы не подтверждена.' }))
  if (report.unassignedManualUnits !== '0') root.append(el('p', { className: 'product-report-warning', text: `Не распределено по товарам: ${exactCount(report.unassignedManualUnits)} ед. ручных записей — в них нет ссылки на товар.` }))
  const explanation = el('details', { className: 'stats-explanation product-calculation' })
  append(explanation, el('summary', { text: 'Как считается' }), el('p', { text: 'Заказы учитываются после выдачи, ручные продажи — в день внесения учётной записи продажи. Итоги смен могут отличаться: они относятся к дате закрытия смены.' }),
    el('p', { text: 'Стоимость — по сохранённой цене и выбранным опциям, до скидок и подарков. Общие скидки и сборы по товарам не распределяются. Разные валюты не складываются. Подарочные единицы не означают оплату.' }),
    el('p', { text: 'Используется текущая классификация меню, а не категории на дату продажи. Одинаковые названия разных карточек не объединяются. Недостающие сведения отмечены: прочерк не означает ноль.' }),
    el('p', { text: 'При открытии категории и смене страницы сервер обновляет весь отчёт. Время актуальности меняется вместе с итогами; при новых операциях состав страниц может измениться.' }))
  root.append(explanation, el('p', { className: 'product-table-caption', text: 'Категория / товар · Единицы · Стоимость' }))
  for (const group of report.categoryTotals) {
    const category = el('section', { className: 'product-category' }); category.dataset.categoryKey = group.category.key
    const open = view?.category === group.category.key
    const button = el('button', { className: 'product-category-toggle' }); button.type = 'button'; button.dataset.productFocus = `category:${group.category.key}`
    button.setAttribute('aria-expanded', String(open)); button.setAttribute('aria-label', `Категория: ${group.category.name}`)
    const money = el('span', { className: 'product-money' }); group.totals.grossAmounts.forEach(x => money.append(figure(x.gross, x.currency)))
    const name = el('span', { className: 'product-category-label' }); name.append(el('span', { className: 'product-name', text: group.category.name }))
    if (partial(group.totals.giftUnits)) name.append(el('small', { className: 'product-incomplete', text: 'Подарки: неполно' }))
    append(button, name, figure(group.totals.units), money, el('span', { className: 'product-chevron', text: open ? '⌃' : '⌄' }))
    button.onclick = () => view?.onCategory(group.category.key); category.append(button)
    if (open) {
      const page = report.categoryPage
      if (!page || page.key !== group.category.key) category.append(el('p', { className: 'stats-note', text: 'Загружаем товары…' }))
      else {
        for (const item of page.rows) {
          const key = `${item.itemId}:${item.currency}`; const expanded = view?.item === key
          const row = el('div', { className: 'product-item' }); row.dataset.itemId = String(item.itemId)
          const toggle = el('button', { className: 'product-item-toggle' }); toggle.type = 'button'; toggle.dataset.productFocus = `item:${key}`
          toggle.setAttribute('aria-expanded', String(expanded)); toggle.setAttribute('aria-label', `Товар: ${item.name} · ${item.currency}`)
          append(toggle, el('span', { className: 'product-name', text: item.name }), figure(item.units), figure(item.gross, item.currency))
          toggle.onclick = () => view?.onItem(key); row.append(toggle)
          if (expanded) row.append(productDetails(item))
          category.append(row)
        }
        if (!page.rows.length) category.append(el('p', { className: 'stats-note', text: 'На этой странице товаров нет.' }))
        if (page.totalRows > page.pageSize || page.page > 1) {
          const nav = el('div', { className: 'product-pages' }); const previous = el('button', { text: 'Предыдущие товары' }); const next = el('button', { text: 'Следующие товары' })
          previous.type = next.type = 'button'; previous.disabled = page.page <= 1; next.disabled = page.page * page.pageSize >= page.totalRows
          previous.dataset.productFocus = 'previous'; next.dataset.productFocus = 'next'
          previous.onclick = () => view?.onPage(page.page - 1); next.onclick = () => view?.onPage(page.page + 1)
          append(nav, previous, el('span', { text: `Страница ${page.page} · позиций ${page.totalRows}` }), next); category.append(nav)
        }
      }
    }
    root.append(category)
  }
  if (!report.categoryTotals.length) root.append(el('p', { className: 'stats-note', text: totals.units.availability === 'AVAILABLE' ? 'По выбранным условиям товаров нет.' : 'Нет подтверждённых товаров в доступных данных.' }))
  return root
}
