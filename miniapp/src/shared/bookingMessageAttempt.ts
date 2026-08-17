import { getTelegramContext } from './telegram'

export type BookingMessageAttemptScope = {
  venueId: number | null
  threadId?: number | null
  bookingId?: number | null
}

type PendingBookingMessageAttempt = {
  clientMessageId: string
  normalizedDraft: string
  scopeKey: string
}

function generateOpaqueUuid(): string {
  const cryptoApi = globalThis.crypto
  if (typeof cryptoApi?.randomUUID === 'function') {
    return cryptoApi.randomUUID()
  }

  const bytes = new Uint8Array(16)
  if (typeof cryptoApi?.getRandomValues === 'function') {
    cryptoApi.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function buildScopeKey(scope: BookingMessageAttemptScope): string {
  return JSON.stringify([
    getTelegramContext().telegramUserId,
    scope.venueId,
    scope.threadId ?? null,
    scope.bookingId ?? null
  ])
}

export function createBookingMessageAttempt() {
  let pending: PendingBookingMessageAttempt | null = null

  return {
    clientMessageIdFor(draft: string, scope: BookingMessageAttemptScope): string {
      const normalizedDraft = draft.trim()
      const scopeKey = buildScopeKey(scope)
      if (pending && pending.normalizedDraft === normalizedDraft && pending.scopeKey === scopeKey) {
        return pending.clientMessageId
      }
      const clientMessageId = generateOpaqueUuid()
      pending = { clientMessageId, normalizedDraft, scopeKey }
      return clientMessageId
    },
    invalidate(): void {
      pending = null
    }
  }
}
