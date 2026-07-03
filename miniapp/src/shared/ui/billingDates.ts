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

export function nextBillingDate(value?: string | null): string | null {
  const dateOnly = isoDateOnly(value)
  if (!dateOnly) return null
  const [year, month, day] = dateOnly.split('-').map(Number)
  const date = new Date(Date.UTC(year, month - 1, day))
  date.setUTCDate(date.getUTCDate() + 1)
  return [
    date.getUTCFullYear(),
    String(date.getUTCMonth() + 1).padStart(2, '0'),
    String(date.getUTCDate()).padStart(2, '0')
  ].join('-')
}

export function formatNextBillingDate(value?: string | null): string {
  return formatBillingDate(nextBillingDate(value))
}
