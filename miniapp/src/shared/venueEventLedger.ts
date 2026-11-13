export const VENUE_LEDGER_KEY = 'hookah:venue-events:v2'
export const VENUE_LEDGER_LOCK = 'hookah:venue-events:v2:coordination'
export const EVENT_CAPACITY = 256
export const LEDGER_MAX_BYTES = 262144
export const DELIVERY_LEASE_MS = 8000
const MAX_SCOPES = 4
const MAX_RETIRED = 256
const RETIRED_GRACE_MS = 30000
const RETIRED_TTL_MS = 86400000
const REMINDER_MS = 60000

type Receipt = { baseline: boolean; sounds: number; soundedAt: number; muted: boolean; desktop: boolean; seenAt: number }
export type DeliveryLease = { owner: string; expires: number; audio: string[]; desktop: string[] }
type Scope = { key: string; usedAt: number; receipts: Record<string, Receipt>; lease: DeliveryLease | null }
type DeviceLedger = { version: 2; scopes: Scope[] }
export class VenueLedgerCapacityError extends Error {}
export class VenueLedgerBusyError extends VenueLedgerCapacityError {}

function validId(id: unknown): id is string { return typeof id === 'string' && /^(batch|call|booking|bookingMessage):[1-9]\d{0,18}$/.test(id) }

/** Bounded current receipts, not a creation history or a server ACK. Only successful channel
 * attempts advance channel receipts. A silent observer leaves audio pending for another tab.
 */
export class VenueEventLedger {
  constructor(private readonly scopeKey: string) {
    if (scopeKey.length > 512) throw new VenueLedgerCapacityError('Контекст мониторинга слишком велик.')
  }

  private read(): DeviceLedger {
    // V1 is intentionally not parsed: an old history ledger can itself exceed the origin quota.
    const legacy: string[] = []
    for (let i = 0; i < Math.min(localStorage.length, 512); i++) {
      const key = localStorage.key(i)
      if (key?.startsWith('hookah:venue-events:v1:')) legacy.push(key)
    }
    legacy.forEach(key => localStorage.removeItem(key))
    const raw = localStorage.getItem(VENUE_LEDGER_KEY)
    if (!raw) return { version: 2, scopes: [] }
    if (raw.length + VENUE_LEDGER_KEY.length > LEDGER_MAX_BYTES) throw new VenueLedgerCapacityError('Локальный журнал превышает допустимый размер.')
    const value = JSON.parse(raw) as DeviceLedger
    if (value.version !== 2 || !Array.isArray(value.scopes) || value.scopes.length > MAX_SCOPES) throw new Error('invalid ledger')
    for (const scope of value.scopes) {
      // Protocol-2 readers keep accepting their batch/call ledger. Booking-only extension
      // fields are preserved by their object round-trips and share the same lease lock.
      const extended = scope as Scope & { bookingReceipts?: Record<string, Receipt>; bookingLease?: DeliveryLease | null }
      if (extended.bookingReceipts) {
        if (!Object.keys(extended.bookingReceipts).every(id => /^booking(?:Message)?:[1-9]\d{0,18}$/.test(id))) throw new Error('invalid booking receipts')
        scope.receipts = { ...scope.receipts, ...extended.bookingReceipts }
      }
      if (scope.lease && extended.bookingLease?.owner === scope.lease.owner && extended.bookingLease.expires === scope.lease.expires) {
        scope.lease.audio.push(...extended.bookingLease.audio); scope.lease.desktop.push(...extended.bookingLease.desktop)
      }
      delete extended.bookingReceipts; delete extended.bookingLease
      if (typeof scope.key !== 'string' || scope.key.length > 512 || !Number.isFinite(scope.usedAt) || !scope.receipts || typeof scope.receipts !== 'object') throw new Error('invalid scope')
      const entries = Object.entries(scope.receipts)
      if (entries.length > EVENT_CAPACITY + MAX_RETIRED) throw new VenueLedgerCapacityError('Слишком много локальных квитанций.')
      for (const [id, receipt] of entries) {
        if (!validId(id) || typeof receipt.baseline !== 'boolean' || typeof receipt.muted !== 'boolean' || typeof receipt.desktop !== 'boolean' || !Number.isInteger(receipt.sounds) || receipt.sounds < 0 || receipt.sounds > 3 || !Number.isFinite(receipt.soundedAt) || !Number.isFinite(receipt.seenAt)) throw new Error('invalid receipt')
      }
      if (scope.lease && (typeof scope.lease.owner !== 'string' || scope.lease.owner.length > 64 || !Number.isFinite(scope.lease.expires) || !Array.isArray(scope.lease.audio) || !Array.isArray(scope.lease.desktop) || scope.lease.audio.length > EVENT_CAPACITY || scope.lease.desktop.length > EVENT_CAPACITY || ![...scope.lease.audio, ...scope.lease.desktop].every(validId))) throw new Error('invalid lease')
    }
    return value
  }

  private write(value: DeviceLedger) {
    const stored = { ...value, scopes: value.scopes.map(scope => ({ ...scope,
      receipts: Object.fromEntries(Object.entries(scope.receipts).filter(([id]) => !id.startsWith('booking'))),
      bookingReceipts: Object.fromEntries(Object.entries(scope.receipts).filter(([id]) => id.startsWith('booking'))),
      lease: scope.lease && { ...scope.lease, audio: scope.lease.audio.filter(id => !id.startsWith('booking')), desktop: scope.lease.desktop.filter(id => !id.startsWith('booking')) },
      bookingLease: scope.lease && { ...scope.lease, audio: scope.lease.audio.filter(id => id.startsWith('booking')), desktop: scope.lease.desktop.filter(id => id.startsWith('booking')) }
    })) }
    const raw = JSON.stringify(stored)
    if (raw.length + VENUE_LEDGER_KEY.length > LEDGER_MAX_BYTES || new TextEncoder().encode(raw + VENUE_LEDGER_KEY).byteLength > LEDGER_MAX_BYTES) throw new VenueLedgerCapacityError('Локальный журнал достиг лимита размера.')
    localStorage.setItem(VENUE_LEDGER_KEY, raw)
  }

  private async transact<T>(operation: (value: DeviceLedger, now: number) => T): Promise<T> {
    if (!navigator.locks?.request) throw new Error('coordination unavailable')
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 2000)
    try {
      return await navigator.locks.request(VENUE_LEDGER_LOCK, { signal: controller.signal }, () => {
        const value = this.read()
        const result = operation(value, Date.now())
        this.write(value)
        return result
      })
    } catch (error) {
      if (controller.signal.aborted) throw new VenueLedgerBusyError('Координация вкладок занята. Повторим обновление автоматически.')
      throw error
    } finally { window.clearTimeout(timeout) }
  }

  async observe(ids: string[], audio: boolean, desktop: boolean) {
    if (ids.length > EVENT_CAPACITY || !ids.every(validId) || new Set(ids).size !== ids.length) throw new VenueLedgerCapacityError('Неполный или слишком большой набор событий.')
    return this.transact((value, now) => {
      let scope = value.scopes.find(item => item.key === this.scopeKey)
      const initial = !scope
      if (!scope) {
        if (value.scopes.length === MAX_SCOPES) {
          const removable = value.scopes.filter(item => now - item.usedAt >= RETIRED_GRACE_MS && (!item.lease || item.lease.expires <= now)).sort((a, b) => a.usedAt - b.usedAt)[0]
          if (!removable) throw new VenueLedgerCapacityError('Достигнут лимит активных контекстов мониторинга на устройстве.')
          value.scopes = value.scopes.filter(item => item !== removable)
        }
        scope = { key: this.scopeKey, usedAt: now, receipts: {}, lease: null }
        value.scopes.push(scope)
      }
      scope.usedAt = now
      if (scope.lease && (scope.lease.expires <= now || scope.lease.expires > now + DELIVERY_LEASE_MS)) scope.lease = null
      const present = new Set(ids)
      const retired = Object.entries(scope.receipts).filter(([id, receipt]) => !present.has(id) && now - receipt.seenAt <= RETIRED_TTL_MS).sort((a, b) => b[1].seenAt - a[1].seenAt)
      if (retired.slice(MAX_RETIRED).some(([, receipt]) => now - receipt.seenAt < RETIRED_GRACE_MS)) throw new VenueLedgerCapacityError('Слишком много событий за короткое время. Звуковой мониторинг временно ограничен.')
      scope.receipts = Object.fromEntries([...Object.entries(scope.receipts).filter(([id]) => present.has(id)), ...retired.slice(0, MAX_RETIRED)])
      let created = 0
      for (const id of ids) {
        if (!scope.receipts[id]) {
          scope.receipts[id] = { baseline: initial, sounds: 0, soundedAt: 0, muted: false, desktop: false, seenAt: now }
          if (!initial) created++
        }
        scope.receipts[id].seenAt = now
      }
      let lease: DeliveryLease | null = null
      if (!scope.lease) {
        const eligible = ids.filter(id => !scope!.receipts[id].baseline && !scope!.receipts[id].muted)
        const audioIds = audio ? eligible.filter(id => {
          const receipt = scope!.receipts[id]
          return receipt.sounds === 0 || (receipt.sounds < 3 && now - receipt.soundedAt >= REMINDER_MS)
        }) : []
        const desktopIds = desktop ? eligible.filter(id => !scope!.receipts[id].desktop) : []
        if (audioIds.length || desktopIds.length) {
          lease = { owner: crypto.randomUUID(), expires: now + DELIVERY_LEASE_MS, audio: audioIds, desktop: desktopIds }
          scope.lease = lease
        }
      }
      return { created, muted: ids.filter(id => scope!.receipts[id].muted), lease }
    })
  }

  async finish(lease: DeliveryLease, audioSucceeded: boolean, desktopSucceeded: boolean) {
    await this.transact((value, now) => {
      const scope = value.scopes.find(item => item.key === this.scopeKey)
      if (!scope || scope.lease?.owner !== lease.owner) return
      if (audioSucceeded) for (const id of lease.audio) {
        const receipt = scope.receipts[id]
        if (receipt) { receipt.sounds = Math.min(3, receipt.sounds + 1); receipt.soundedAt = now }
      }
      if (desktopSucceeded) for (const id of lease.desktop) if (scope.receipts[id]) scope.receipts[id].desktop = true
      scope.lease = null
    })
  }

  async release(lease: DeliveryLease) { await this.finish(lease, false, false) }

  async mute(id: string) {
    await this.transact(value => {
      const receipt = value.scopes.find(item => item.key === this.scopeKey)?.receipts[id]
      if (receipt) receipt.muted = true
    })
  }
}
