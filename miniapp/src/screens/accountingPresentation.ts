import type { AccountingPayment, AccountingSale } from '../shared/api/accounting'

const compareId = (a: string, b: string) => a < b ? -1 : a > b ? 1 : 0
function instant(value: string): bigint | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value) || !Number.isFinite(Date.parse(value))) return null
  const fraction = /\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/.exec(value)?.[1] ?? ''
  return BigInt(Math.floor(Date.parse(value) / 1000)) * 1_000_000_000n + BigInt(fraction.padEnd(9, '0'))
}

export function compareAccountingTime(a: string, b: string, aId: string, bId: string) {
  const left = instant(a), right = instant(b)
  if (left === right) return compareId(aId, bId)
  return left === null ? 1 : right === null ? -1 : left > right ? -1 : 1
}

export function orderedSales(sales: readonly AccountingSale[]): AccountingSale[] {
  return [...sales].sort((a, b) => compareAccountingTime(a.createdAt, b.createdAt, a.id, b.id))
}

export function venueDay(value: string, timezone: string): string | null {
  if (!timezone || instant(value) === null) return null
  try {
    const parts = new Intl.DateTimeFormat('en', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(value))
    const part = (type: string) => parts.find(p => p.type === type)!.value
    return `${part('year')}-${part('month')}-${part('day')}`
  } catch { return null }
}

export function accountingStamp(value: string, timezone: string, kind: 'date' | 'time' | 'full' = 'full'): string {
  if (!venueDay(value, timezone)) return 'Дата не определена'
  return new Intl.DateTimeFormat('ru-RU', { timeZone: timezone,
    ...(kind !== 'time' ? { day: '2-digit', month: '2-digit', year: 'numeric' } as const : {}),
    ...(kind !== 'date' ? { hour: '2-digit', minute: '2-digit', second: '2-digit' } as const : {})
  }).format(new Date(value))
}

export type PaymentOperation = { key: string; requestId: string | null; payments: AccountingPayment[]; recordedAt: string }

export function paymentOperations(sale: AccountingSale): PaymentOperation[] {
  // PAY requestIds cannot contain ':'. Only PAY_SPLIT creates requestId:METHOD receipts.
  const splitKey = (p: AccountingPayment) => {
    const match = /^([A-Za-z0-9-]{16,80}):(CASH|CARD|TRANSFER)$/.exec(p.id)
    return match && match[2] === p.method ? match[1] : null
  }
  const candidates = new Map<string, AccountingPayment[]>()
  sale.payments.forEach(payment => {
    const key = splitKey(payment)
    if (key) candidates.set(key, [...(candidates.get(key) ?? []), payment])
  })
  const consumed = new Set<AccountingPayment>()
  const operations: PaymentOperation[] = []
  sale.payments.forEach(payment => {
    if (consumed.has(payment)) return
    const key = splitKey(payment), parts = key ? candidates.get(key)! : []
    const confirmed = parts.length >= 2 && parts.length <= 3 && new Set(parts.map(p => p.method)).size === parts.length
      && parts.every(p => Number.isSafeInteger(p.amountMinor) && p.amountMinor > 0)
      && instant(payment.recordedAt) !== null && parts.every(p => instant(p.recordedAt) === instant(payment.recordedAt))
    const payments = confirmed ? [...parts].sort((a, b) => ['CASH', 'CARD', 'TRANSFER'].indexOf(a.method) - ['CASH', 'CARD', 'TRANSFER'].indexOf(b.method)) : [payment]
    payments.forEach(p => consumed.add(p))
    operations.push({ key: confirmed ? key! : payment.id, requestId: confirmed ? key : null, payments, recordedAt: payment.recordedAt })
  })
  return operations.sort((a, b) => compareAccountingTime(a.recordedAt, b.recordedAt, a.key, b.key))
}
