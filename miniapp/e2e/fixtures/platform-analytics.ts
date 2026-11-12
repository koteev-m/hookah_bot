import captured from './platform-analytics-http.json' with { type: 'json' }
import type { PlatformAnalyticsSection, PlatformAnalyticsSnapshot } from '../../src/shared/api/platformAnalyticsApi'

export type PlatformVariant = keyof typeof captured
export const platformHttp = captured as Record<PlatformVariant, PlatformAnalyticsSnapshot>
// Only request context is adapted. KPI values and field shapes come from the real Ktor/H2 GET.
export function platformResponse(url: URL, variant: PlatformVariant = 'complete') {
  const body = structuredClone(platformHttp[variant])
  body.period.from = `${url.searchParams.get('from')}T00:00:00Z`
  body.period.to = `${url.searchParams.get('to')}T00:00:00Z`
  body.section = (url.searchParams.get('section') ?? 'all') as PlatformAnalyticsSection
  if (body.section !== 'all') {
    const prefix = body.section === 'venues' ? 'venues.' : `${body.section}.`
    body.metrics = Object.fromEntries(Object.entries(body.metrics).filter(([key]) => key.startsWith(prefix)))
    body.breakdowns = Object.fromEntries(Object.entries(body.breakdowns).filter(([key]) => key.startsWith(prefix)))
    if (body.section !== 'invoices') body.invoiceFinances = {}
  }
  return body
}
