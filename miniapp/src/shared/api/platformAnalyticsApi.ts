import { requestApi, type RequestDependencies } from './request'
import type { ApiResult } from './types'
import type { VenueKpiSnapshot } from './venueStatsApi'
import { periodValidation, shiftCalendarDate } from '../ui/venueStatsFormat'

// The shared KpiSnapshot wire contract is defined in KpiModels.kt; no second KPI model.
export type PlatformAnalyticsSection = 'all' | 'venues' | 'subscriptions' | 'invoices' | 'onboarding' | 'support'
export type PlatformAnalyticsSnapshot = Omit<VenueKpiSnapshot, 'section' | 'venueId' | 'tableId'> & {
  section: PlatformAnalyticsSection; venueId: null; tableId: null
}
export type PlatformAnalyticsQuery = { from: string; to: string; section: PlatformAnalyticsSection }
const sections = ['all', 'venues', 'subscriptions', 'invoices', 'onboarding', 'support']
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const invalid = (): ApiResult<PlatformAnalyticsSnapshot> => ({ ok: false, error: { status: 0, code: 'ANALYTICS_CONTRACT_UNAVAILABLE' } })

export async function platformGetAnalyticsV1(
  backendUrl: string, query: PlatformAnalyticsQuery, deps: RequestDependencies, signal: AbortSignal
): Promise<ApiResult<PlatformAnalyticsSnapshot>> {
  try {
    if (Object.keys(query).some(key => !['from', 'to', 'section'].includes(key)) || !sections.includes(query.section) ||
      periodValidation(query.from, shiftCalendarDate(query.to, -1))) return invalid()
  } catch { return invalid() }
  const params = new URLSearchParams(query)
  const result = await requestApi<PlatformAnalyticsSnapshot>(backendUrl, `/api/platform/analytics/v1?${params}`, { signal, cache: 'no-store' }, deps)
  if (!result.ok) return result
  try {
    const data = result.data
    if (data.definitionVersion !== '2026-11-analytics-v1' || data.venueId !== null || data.tableId !== null ||
      data.section !== query.section || data.period.timezone !== 'UTC' || data.period.interval !== '[from,to)' ||
      !/^\d{4}-\d{2}-\d{2}T.*Z$/.test(data.asOf) || !Number.isFinite(Date.parse(data.asOf)) ||
      Date.parse(data.period.from) !== Date.parse(`${query.from}T00:00:00Z`) ||
      Date.parse(data.period.to) !== Date.parse(`${query.to}T00:00:00Z`) ||
      !object(data.metrics) || !object(data.breakdowns) || !object(data.invoiceFinances) ||
      !Array.isArray(data.closedShiftFinances) || data.closedShiftFinances.length !== 0 ||
      !Array.isArray(data.openShiftFinances) || data.openShiftFinances.length !== 0 ||
      !Array.isArray(data.limitations) || !data.limitations.every(value => typeof value === 'string')) return invalid()
    // Missing individual capabilities are rendered unavailable; malformed payloads never become zero.
    const metrics: unknown[] = [...Object.values(data.metrics)]
    for (const groups of Object.values(data.invoiceFinances)) {
      if (!Array.isArray(groups)) return invalid()
      for (const group of groups) {
        if (!object(group) || typeof group.currency !== 'string' || !object(group.metrics)) return invalid()
        metrics.push(...Object.values(group.metrics))
      }
    }
    for (const value of metrics) {
      if (!object(value) || !['value', 'observedValue'].every(key => value[key] === null ||
        typeof value[key] === 'string' && /^-?\d+(\.\d+)?$/.test(value[key] as string)) ||
        !['unit', 'basis', 'availability', 'coverage'].every(key => typeof value[key] === 'string') ||
        !['samples', 'unknown'].every(key => typeof value[key] === 'number' && Number.isInteger(value[key]) && (value[key] as number) >= 0) ||
        !(value.reason === null || typeof value.reason === 'string')) return invalid()
    }
    if (Object.values(data.breakdowns).some(rows => !Array.isArray(rows) || rows.some(row =>
      !object(row) || typeof row.key !== 'string' || typeof row.count !== 'number' || !Number.isInteger(row.count) || row.count < 0))) return invalid()
    return result
  } catch { return invalid() }
}
