import captured from './venue-stats-http.json' with { type: 'json' }
import moneyCaptured from './venue-stats-money-http.json' with { type: 'json' }
import type { StatsSection, VenueKpiSnapshot } from '../../src/shared/api/venueStatsApi'

const snapshots = { ...captured, money: moneyCaptured }
export type StatsVariant = keyof typeof snapshots
export const statsHttp = snapshots as Record<StatsVariant, VenueKpiSnapshot>

// Test-only calendar boundary adaptation; metrics/coverage/defaults originate in VenueStatsUiContractTest.
export function midnight(date: string, timezone: string): string {
  const target = Date.parse(`${date}T00:00:00Z`)
  let instant = target
  const formatter = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
  for (let i = 0; i < 4; i++) {
    const parts = formatter.formatToParts(new Date(instant))
    const part = (type: string) => parts.find(value => value.type === type)!.value
    const local = Date.parse(`${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}:${part('second')}Z`)
    const next = target - (local - instant)
    if (next === instant) break
    instant = next
  }
  return new Date(instant).toISOString().replace('.000Z', 'Z')
}

export function statsResponse(url: URL, options: { variant?: StatsVariant; timezone?: string; asOf?: string } = {}): VenueKpiSnapshot {
  const response = structuredClone(statsHttp[options.variant ?? 'complete'])
  const timezone = options.timezone ?? response.period.timezone
  response.venueId = Number(url.pathname.split('/')[3])
  response.section = (url.searchParams.get('section') ?? 'all') as StatsSection
  response.tableId = url.searchParams.has('tableId') ? Number(url.searchParams.get('tableId')) : null
  response.asOf = options.asOf ?? response.asOf
  response.period = { from: midnight(url.searchParams.get('from')!, timezone), to: midnight(url.searchParams.get('to')!, timezone),
    timezone, interval: '[from,to)' }
  const prefixes: Record<StatsSection, string[]> = { all: [], orders: ['orders.', 'batches.', 'items.'], finance: ['finance.'],
    bookings: ['bookings.'], seatings: ['seatings.'], calls: ['calls.'] }
  if (response.section !== 'all') {
    const filter = (key: string) => prefixes[response.section].some(prefix => key.startsWith(prefix))
    response.metrics = Object.fromEntries(Object.entries(response.metrics).filter(([key]) => filter(key)))
    response.breakdowns = Object.fromEntries(Object.entries(response.breakdowns).filter(([key]) => filter(key)))
    if (response.section !== 'finance') { response.closedShiftFinances = []; response.openShiftFinances = [] }
  }
  return response
}
