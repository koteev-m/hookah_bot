import iso4217 from './iso4217MinorUnits.json' with { type: 'json' }

export type StatsPreset = 'today' | '7d' | '30d' | 'custom'

export function validCalendarDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

// UTC here is only a calendar arithmetic carrier. The server resolves venue midnight and DST.
export function shiftCalendarDate(value: string, days: number): string {
  if (!validCalendarDate(value)) throw new Error('Invalid calendar date')
  const date = new Date(`${value}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

export function calendarDateAt(instant: string, timezone: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' })
    .formatToParts(new Date(instant))
  const value = (type: string) => parts.find(part => part.type === type)!.value
  return `${value('year')}-${value('month')}-${value('day')}`
}

export function presetDates(preset: Exclude<StatsPreset, 'custom'>, asOf: string, timezone: string) {
  const today = calendarDateAt(asOf, timezone)
  return { from: shiftCalendarDate(today, preset === 'today' ? 0 : preset === '7d' ? -6 : -29), to: today }
}

export function periodValidation(from: string, inclusiveTo: string): string | null {
  if (!validCalendarDate(from) || !validCalendarDate(inclusiveTo)) return 'Укажите корректные даты начала и окончания.'
  const days = (Date.parse(`${inclusiveTo}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000 + 1
  if (days < 1) return 'Дата окончания должна быть не раньше даты начала.'
  if (days > 370) return 'Выберите период не длиннее 370 календарных дней.'
  if (!validCalendarDate(shiftCalendarDate(inclusiveTo, 1))) return 'Выберите более раннюю дату окончания.'
  return null
}

export function formatCalendarDate(value: string): string {
  return new Intl.DateTimeFormat('ru-RU', { timeZone: 'UTC', day: 'numeric', month: 'long', year: 'numeric' })
    .format(new Date(`${value}T00:00:00Z`))
}

export function exactCount(value: string | number): string {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return 'Недостаточно данных'
  try { return BigInt(value).toLocaleString('ru-RU') } catch { return 'Недостаточно данных' }
}

export function exactMoney(minor: string, currency: string): string {
  try {
    if (!/^-?\d+$/.test(minor) || !/^[A-Z]{3}$/.test(currency)) return 'Недостаточно данных'
    // Pinned SIX ISO 4217 minor units; Intl/CLDR display defaults can differ (notably IQD and MGA).
    const digits = (iso4217.minorUnits as Readonly<Record<string, number | null>>)[currency]
    if (digits == null) return 'Недостаточно данных'
    const formatter = new Intl.NumberFormat('ru-RU', {
      style: 'currency', currency, currencyDisplay: 'code', minimumFractionDigits: digits, maximumFractionDigits: digits
    })
    const scale = 10n ** BigInt(digits)
    const amount = BigInt(minor)
    const abs = amount < 0n ? -amount : amount
    const fraction = (abs % scale).toString().padStart(digits, '0')
    const parts = formatter.formatToParts(abs / scale)
    if (digits > 0 && !parts.some(part => part.type === 'fraction')) return 'Недостаточно данных'
    return (amount < 0n ? '−' : '') + parts
      .map(part => part.type === 'fraction' ? fraction : part.value).join('')
  } catch { return 'Недостаточно данных' }
}

export function formatRatio(value: string): string {
  if (!/^\d+(\.\d+)?$/.test(value)) return 'Недостаточно данных'
  const [whole, fraction = ''] = value.split('.')
  const scale = 10n ** BigInt(fraction.length)
  const integer = BigInt(whole + fraction)
  const hundredths = (integer * 10000n + scale / 2n) / scale
  return `${exactCount(hundredths / 100n + '')},${(hundredths % 100n).toString().padStart(2, '0')} %`
}

export function formatDuration(value: string): string {
  const ms = Number(value)
  if (!Number.isFinite(ms) || ms < 0) return 'Недостаточно данных'
  if (ms < 1000) return `${ms.toLocaleString('ru-RU', { maximumFractionDigits: 3 })} мс`
  if (ms < 60000) return `${(ms / 1000).toLocaleString('ru-RU', { maximumFractionDigits: 1 })} сек`
  if (ms < 3600000) return `${(ms / 60000).toLocaleString('ru-RU', { maximumFractionDigits: 1 })} мин`
  return `${(ms / 3600000).toLocaleString('ru-RU', { maximumFractionDigits: 1 })} ч`
}
