import type { BookingThreadReconciliationItemDto, SupportThreadDto } from './api/supportDtos'

export const BOOKING_THREAD_RECONCILIATION_BATCH_SIZE = 100

export type BookingThreadReconciliationState =
  | { status: 'LOADING'; thread: null }
  | { status: 'READY_NO_THREAD'; thread: null }
  | { status: 'READY_WITH_THREAD'; thread: SupportThreadDto }
  | { status: 'ERROR'; thread: null }

export const bookingThreadLoading = (): BookingThreadReconciliationState => ({
  status: 'LOADING',
  thread: null
})

export const bookingThreadError = (): BookingThreadReconciliationState => ({
  status: 'ERROR',
  thread: null
})

function validateBookingIds(bookingIds: number[]): void {
  const seen = new Set<number>()
  bookingIds.forEach((bookingId) => {
    if (!Number.isSafeInteger(bookingId) || bookingId <= 0) {
      throw new Error(`Invalid booking ID ${bookingId}`)
    }
    if (seen.has(bookingId)) {
      throw new Error(`Duplicate booking ID ${bookingId}`)
    }
    seen.add(bookingId)
  })
}

export function bookingThreadReconciliationChunks(bookingIds: number[]): number[][] {
  validateBookingIds(bookingIds)
  const chunks: number[][] = []
  for (let index = 0; index < bookingIds.length; index += BOOKING_THREAD_RECONCILIATION_BATCH_SIZE) {
    chunks.push(bookingIds.slice(index, index + BOOKING_THREAD_RECONCILIATION_BATCH_SIZE))
  }
  return chunks
}

export function reconcileBookingThreadItems(
  bookingIds: number[],
  items: BookingThreadReconciliationItemDto[]
): Map<number, BookingThreadReconciliationState> {
  validateBookingIds(bookingIds)
  const requestedIds = new Set(bookingIds)
  const reconciliation = new Map<number, BookingThreadReconciliationState>()
  const reconciledThreadIds = new Set<number>()
  items.forEach((item) => {
    if (!Number.isSafeInteger(item.bookingId) || !requestedIds.has(item.bookingId)) {
      throw new Error(`Unexpected booking reconciliation item ${item.bookingId}`)
    }
    if (reconciliation.has(item.bookingId)) {
      throw new Error(`Duplicate booking reconciliation item ${item.bookingId}`)
    }
    if (item.status === 'NO_THREAD') {
      if (item.thread != null) {
        throw new Error(`Contradictory booking reconciliation item ${item.bookingId}`)
      }
      reconciliation.set(item.bookingId, { status: 'READY_NO_THREAD', thread: null })
      return
    }
    if (item.status !== 'WITH_THREAD' || item.thread == null) {
      throw new Error(`Invalid booking reconciliation status for booking ${item.bookingId}`)
    }
    if (
      !Number.isSafeInteger(item.thread.threadId) ||
      item.thread.threadId <= 0 ||
      reconciledThreadIds.has(item.thread.threadId) ||
      item.thread.threadType !== 'BOOKING_THREAD' ||
      item.thread.bookingId !== item.bookingId
    ) {
      throw new Error(`Contradictory booking thread identity for booking ${item.bookingId}`)
    }
    reconciledThreadIds.add(item.thread.threadId)
    reconciliation.set(item.bookingId, { status: 'READY_WITH_THREAD', thread: item.thread })
  })
  if (reconciliation.size !== bookingIds.length) {
    throw new Error('Incomplete booking reconciliation response')
  }
  return new Map(bookingIds.map((bookingId) => [bookingId, reconciliation.get(bookingId)!]))
}

export function bookingThreadStates(
  bookingIds: number[],
  state: 'LOADING' | 'ERROR'
): Map<number, BookingThreadReconciliationState> {
  return new Map(
    bookingIds.map((bookingId) => [
      bookingId,
      state === 'LOADING' ? bookingThreadLoading() : bookingThreadError()
    ])
  )
}
