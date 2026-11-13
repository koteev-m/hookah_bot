import { requestApi, type RequestDependencies } from './request'
import type { ApiResult } from './types'

// KpiModels.kt / KpiRoutes.kt. The old VenueStatsResponse and /stats client remain separate.
export type StatsSection = 'all' | 'orders' | 'finance' | 'bookings' | 'seatings' | 'calls' | 'products' | 'additions'
export type KpiValue = {
  value: string | null
  observedValue: string | null
  unit: string
  basis: string
  availability: string
  coverage: string
  samples: number
  unknown: number
  reason: string | null
}
export type KpiBucket = { key: string; count: number }
export type FinancialKpis = { currency: string; metrics: Record<string, KpiValue> }
export type VenueKpiSnapshot = {
  definitionVersion: string
  asOf: string
  period: { from: string; to: string; timezone: string; interval: string }
  venueId: number | null
  section: StatsSection
  tableId: number | null
  metrics: Record<string, KpiValue>
  breakdowns: Record<string, KpiBucket[]>
  closedShiftFinances: FinancialKpis[]
  openShiftFinances: FinancialKpis[]
  invoiceFinances: Record<string, FinancialKpis[]>
  products?: {
    rows: { itemId: number; name: string; category: string; currentlyAvailable: boolean; source: string; currency: string;
      units: string; giftUnits: string | null; nonGiftUnits: string | null; snapshotGrossMinor: string | null; options: string[]; catalogCreatedAt?: string | null; categoryActive?: boolean | null }[]
    totalRows: number; page: number; pageSize: number; categories: string[]; currencies: string[]; coverage: KpiValue;
    unassignedManualUnits: string; basis: string; netAmount: KpiValue
  } | null
  limitations: string[]
}
export type VenueStatsQuery = { venueId: number; from: string; to: string; section: StatsSection; tableId?: number; search?: string; category?: string; currency?: string; sort?: string; page?: number }

export async function venueGetStatsV1(
  backendUrl: string, query: VenueStatsQuery, deps: RequestDependencies, signal: AbortSignal
): Promise<ApiResult<VenueKpiSnapshot>> {
  const params = new URLSearchParams({ from: query.from, to: query.to, section: query.section })
  for (const key of ['search', 'category', 'currency', 'sort', 'page'] as const) {
    if (query[key] != null && query[key] !== '') params.set(key, String(query[key]))
  }
  if (query.tableId != null) params.set('tableId', String(query.tableId))
  const result = await requestApi<VenueKpiSnapshot>(backendUrl, `/api/venue/${query.venueId}/stats/v1?${params}`,
    { signal, cache: 'no-store' }, deps)
  if (!result.ok) return result
  const data = result.data
  // Fail closed on foreign context or an incompatible envelope; missing individual KPIs remain unavailable.
  try {
    if (data.definitionVersion !== '2026-11-analytics-v1' || data.venueId !== query.venueId || data.section !== query.section ||
      data.tableId !== (query.tableId ?? null) || data.period.interval !== '[from,to)' ||
      !Number.isFinite(Date.parse(data.asOf)) || !Number.isFinite(Date.parse(data.period.from)) ||
      !Number.isFinite(Date.parse(data.period.to)) || !data.metrics || !data.breakdowns ||
      !Array.isArray(data.closedShiftFinances) || !Array.isArray(data.openShiftFinances) || !Array.isArray(data.limitations)) {
      throw new Error('incompatible')
    }
    const calendar = new Intl.DateTimeFormat('en-CA', { timeZone: data.period.timezone, year: 'numeric', month: '2-digit', day: '2-digit' })
    const date = (instant: string) => {
      const parts = calendar.formatToParts(new Date(instant))
      return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)!.value).join('-')
    }
    if (date(data.period.from) !== query.from || date(data.period.to) !== query.to ||
      Date.parse(data.period.to) <= Date.parse(data.period.from)) throw new Error('incompatible period')
    return result
  } catch {
    return { ok: false, error: { status: 0, code: 'STATS_CONTRACT_UNAVAILABLE' } }
  }
}
