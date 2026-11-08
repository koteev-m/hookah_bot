import { clearSession, getAccessToken } from './auth'
import { requestApi, requestBinary } from './request'

export type SeatingRules = { revision: number; usePlan: boolean; guestTableChoice: boolean; limitDuration: boolean; durationMinutes: number; bufferMinutes: number }
export type Hall = { id: number; name: string; active: boolean; revision: number; planId: string | null; planWidth: number | null; planHeight: number | null }
export type Layout = { x: number; y: number; width: number; height: number; rotation: number; shape: 'RECTANGLE' | 'ELLIPSE' }
export type Seating = { id: number; tableId: number; visitId: number; tableSessionId: number; bookingId: number | null; partySize: number; startedAt: string; agreedEndAt: string | null; revision: number }
export type TableBooking = { id: number; tableId: number | null; scheduledAt: string; agreedEndAt: string | null; partySize: number | null; status: string; source: string; guestName: string | null; phone: string | null; comment: string | null; createdBy: number | null; revision: number }
export type HallTable = { id: number; number: number; hallId: number | null; capacity: number | null; active: boolean; revision: number; layout: Layout | null; state: string; reason: string | null; seating: Seating | null; nextBooking: TableBooking | null }
export type HallState = { venueId: number; timezone: string; rules: SeatingRules; halls: Hall[]; tables: HallTable[]; bookings: TableBooking[]; unresolvedBookings: number; imageUploadAvailable: boolean }
export type TableAvailability = { venueId: number; timezone: string; enabled: boolean; scheduledAt: string | null; agreedEndAt: string | null; reason: string | null; halls: Hall[]; tables: HallTable[] }
export type SeatingCommand = { requestId: string; action: string; id?: number; revision?: number; tableId?: number | null; hallId?: number | null; name?: string; phone?: string | null; comment?: string | null; active?: boolean; number?: number; capacity?: number | null; layout?: Layout | null; rules?: SeatingRules; localStart?: string | null; localEnd?: string | null; partySize?: number; bookingId?: number; shorterVisitAcknowledged?: boolean; reconcileLegacySession?: boolean; planId?: string; confirmPlanReplacement?: boolean }
export const seatingDeps = { isDebug: false, clearSession, getAccessToken }
export const hallRead = (base: string, venue: number, signal?: AbortSignal) => requestApi<HallState>(base, `/api/venue/${venue}/hall`, { signal }, seatingDeps)
export const hallCommand = (base: string, venue: number, command: SeatingCommand) => requestApi<{ id: number; revision: number }>(base, `/api/venue/${venue}/hall/commands`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(command) }, seatingDeps)
export const hallPlan = (base: string, venue: number, hall: Hall, guest: boolean, signal?: AbortSignal) => requestBinary(base, `/api/${guest ? 'guest/venues' : 'venue'}/${venue}/hall/${hall.id}/plans/${hall.planId}`, { signal }, seatingDeps)
export const tableAvailability = (base: string, venue: number, localStart: string, partySize: number, signal?: AbortSignal) => requestApi<TableAvailability>(base, `/api/guest/venues/${venue}/table-availability?${new URLSearchParams({ localStart, partySize: String(partySize) })}`, { signal }, seatingDeps)
export const tableBooking = (base: string, venue: number, command: SeatingCommand) => requestApi<{ id: number; revision: number }>(base, `/api/guest/venues/${venue}/table-bookings`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(command) }, seatingDeps)

export const tableStateLabel = (state: string) => ({ FREE: 'Свободен сейчас', PREPARING: 'Подготовка стола', OCCUPIED: 'Занят', OVERDUE: 'Время превышено', RECONCILE: 'Требует сверки', DISABLED: 'Отключён', AVAILABLE: 'Доступен', UNAVAILABLE: 'Недоступен' }[state] ?? state)
export function venueDate(value: string | null, timezone: string) { return value ? new Intl.DateTimeFormat('ru-RU', { timeZone: timezone, dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : 'Без согласованного окончания' }
