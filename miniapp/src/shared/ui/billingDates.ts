import type { OwnerBillingOverviewResponse } from '../api/billingDtos'

export function isoDateOnly(value?: string | null): string | null {
  if (!value) return null
  const match = value.trim().match(/^(\d{4})-(\d{2})-(\d{2})/)
  return match ? `${match[1]}-${match[2]}-${match[3]}` : null
}

export function formatBillingDate(value?: string | null): string {
  const dateOnly = isoDateOnly(value)
  if (!dateOnly) return '—'
  const [year, month, day] = dateOnly.split('-')
  return `${day}.${month}.${year}`
}

export function visibleBillingInvoices(overview: OwnerBillingOverviewResponse) {
  const due = overview.payableInvoice
  return due && !overview.invoices.some(invoice => invoice.id === due.id)
    ? [due, ...overview.invoices]
    : overview.invoices
}

export function billingCoverage(overview: OwnerBillingOverviewResponse): string[] {
  const paid = overview.basePaidThrough
  const courtesy = overview.courtesyUntil
  const rows = [paid ? `Оплачено до ${formatBillingDate(paid)} включительно` : 'Оплачено до: —']
  if (courtesy) rows.push(`Льгота до ${formatBillingDate(courtesy)} включительно`)
  if (overview.nextPaymentDate) rows.push(`Следующая оплата с ${formatBillingDate(overview.nextPaymentDate)}`)
  return rows
}

export function freePeriodGuidance(overview: OwnerBillingOverviewResponse): string | null {
  if (overview.priceMinor !== 0) return null
  const debt = overview.payableInvoice ?? overview.invoices.find(i => i.status === 'OPEN' || i.status === 'PAST_DUE')
  if (debt) {
    const manual = overview.unavailableReason === 'fake_provider_manual_only' ? ' Оплату ведёт платформа вручную.' : ''
    const status = debt.status === 'PAST_DUE' ? 'Срок оплаты прошёл.' : 'Счёт открыт.'
    return `Неоплаченный счёт #${debt.id}. ${status} Бесплатный период сохраняет ранее выставленный счёт.${manual}`
  }
  return 'Бесплатный период. Счёт за него не выставляется.'
}
