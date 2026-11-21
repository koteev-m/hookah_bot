import type { OwnerBillingInvoiceDto, OwnerBillingOverviewResponse, OutstandingInvoicePage } from '../api/billingDtos'
import type { ApiResult } from '../api/types'
import { visibleBillingInvoices } from './billingDates'
import { append, el } from './dom'
import { formatPrice } from './price'

export function renderBillingInvoices(
  container: HTMLElement,
  overview: OwnerBillingOverviewResponse,
  renderRow: (invoice: OwnerBillingInvoiceDto) => HTMLElement,
  loadPage: (afterId?: number) => Promise<ApiResult<OwnerBillingOverviewResponse>>
) {
  const first = overview.outstandingInvoices
  if (!first) {
    append(container, el('p', { text: 'Полный список неоплаченных счетов недоступен. Обновите данные.' }))
    for (const invoice of visibleBillingInvoices(overview)) append(container, renderRow(invoice))
    return
  }

  const list = el('div')
  append(container, el('h4', { text: 'Неоплаченные счета' }), list)
  function showPage(page: OutstandingInvoicePage, isFirst: boolean) {
    list.replaceChildren()
    for (const invoice of page.items) {
      const status = invoice.status === 'PAST_DUE' ? 'Просрочен' : 'Открыт'
      const details = el('details')
      append(details, el('summary', {
        text: `Счёт #${invoice.id} · ${status} · ${formatPrice(invoice.amountMinor, invoice.currency)}`
      }), renderRow(invoice))
      append(list, details)
    }
    if (!page.items.length) {
      append(list, el('p', { text: isFirst ? 'Неоплаченных счетов нет.' : 'На этой странице неоплаченных счетов больше нет.' }))
    }
    const controls = el('div', { className: 'venue-inline-actions' })
    const message = el('p', { text: page.hasMore ? 'Показана часть неоплаченных счетов.' : '' })
    message.setAttribute('role', 'status')
    function navigation(label: string, afterId?: number) {
      const button = el('button', { className: 'button-small button-secondary', text: label })
      button.addEventListener('click', async () => {
        const buttons = controls.querySelectorAll('button')
        buttons.forEach(b => { b.disabled = true })
        const result = await loadPage(afterId)
        if (!controls.isConnected) return
        if (!result.ok || !result.data.outstandingInvoices) {
          message.textContent = 'Не удалось загрузить неоплаченные счета. Повторите запрос.'
          buttons.forEach(b => { b.disabled = false })
          return
        }
        showPage(result.data.outstandingInvoices, afterId === undefined)
      })
      append(controls, button)
    }
    if (!isFirst) navigation('К первым неоплаченным счетам')
    if (page.hasMore && page.nextAfterId != null) navigation('Следующие неоплаченные счета', page.nextAfterId)
    append(list, message, controls)
  }
  showPage(first, true)
  if (overview.invoices.length) {
    const history = el('details')
    append(history, el('summary', { text: 'Последние счета' }))
    for (const invoice of overview.invoices) append(history, renderRow(invoice))
    append(container, history)
  }
}
