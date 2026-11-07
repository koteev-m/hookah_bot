import { requestApi, type RequestDependencies } from './request'

export type AccountingLine = {
  id: string; name: string; qty: number; unitPriceMinor: number; menuItemId: number | null
  source: string; tabId: number | null; baseDiscountMinor: number; baseGiftMinor: number
  discountMinor: number; giftReason: string | null
}
export type AccountingPayment = { id: string; method: string; amountMinor: number; recordedAt: string; voidReason: string | null }
export type AccountingSale = {
  id: string; tableId: number; tableLabel: string; source: string; orderId: number | null; displayNumber: number | null
  orderClosed: boolean; createdAt: string; currency: string; lines: AccountingLine[]; payments: AccountingPayment[]
  version: number; cancelReason: string | null; disputeReason: string | null; originalPayableMinor: number | null; issue: string | null
}
export type AccountingTotals = {
  salesCount: number; ordersCount: number; grossMinor: number; discountMinor: number; giftMinor: number; accruedMinor: number
  paidMinor: number; unpaidMinor: number; cashMinor: number; cardMinor: number; transferMinor: number
}
export type AccountingBreakdown = { label: string; qty: number; accruedMinor: number }
export type AccountingReport = {
  shiftId: number; venueId: number; status: string; timezone: string; openedAt: string; closedAt: string | null; currency: string
  sales: AccountingSale[]; totals: AccountingTotals; byTable: AccountingBreakdown[]; byItem: AccountingBreakdown[]
  bySource: AccountingBreakdown[]; unpaidSaleIds: string[]; disputedSaleIds: string[]; reportHash: string
}
export type AccountingShiftHeader = { id: number; status: string; timezone: string; openedAt: string; closedAt: string | null }
export type AccountingState = { current: AccountingReport | null; history: AccountingShiftHeader[]; nextHistoryBeforeId?: number | null }
export type AccountingLineInput = { id?: string; menuItemId?: number; name?: string; qty: number; unitPriceMinor?: number; discountMinor?: number; giftReason?: string | null }
export type AccountingCommand = {
  requestId: string; action: string; shiftId?: number; saleId?: string; expectedVersion?: number; tableId?: number
  lines?: AccountingLineInput[]; reason?: string; paymentMethod?: string; amountMinor?: number; paymentId?: string
  expectedReportHash?: string; acknowledgeUnpaid?: boolean
}
export function accountingState(url: string, venueId: number, deps: RequestDependencies, signal: AbortSignal, beforeShiftId?: number) {
  return requestApi<AccountingState>(url, `/api/venue/${venueId}/accounting${beforeShiftId ? `?beforeShiftId=${beforeShiftId}` : ''}`, { signal }, deps)
}
export function accountingReport(url: string, venueId: number, shiftId: number, deps: RequestDependencies, signal: AbortSignal) {
  return requestApi<AccountingReport>(url, `/api/venue/${venueId}/accounting/${shiftId}`, { signal }, deps)
}
export function accountingCommand(url: string, venueId: number, body: AccountingCommand, deps: RequestDependencies) {
  return requestApi<AccountingReport>(url, `/api/venue/${venueId}/accounting/commands`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
  }, deps)
}
export function linePayable(line: AccountingLine) {
  return line.giftReason !== null ? 0 : line.unitPriceMinor * line.qty - line.baseDiscountMinor - line.baseGiftMinor - line.discountMinor
}
export function salePayable(sale: AccountingSale) { return sale.cancelReason ? 0 : sale.lines.reduce((sum, line) => sum + linePayable(line), 0) }
export function salePaid(sale: AccountingSale) { return sale.payments.filter((p) => !p.voidReason).reduce((sum, p) => sum + p.amountMinor, 0) }
