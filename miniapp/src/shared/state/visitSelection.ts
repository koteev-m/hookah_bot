import { visitPointer, visitStorageReady, withVisitRecord, type Visit, type VisitRecord } from './visitStorage'
import { discardTableCart } from './cartStore'

export type VisitIntent = { key: string; id: string }
export type SelectionResult = 'current' | 'superseded' | 'unavailable'

function blockClosedSession(pending: VisitRecord['pending'], sessionId: number) {
  if (pending && !pending.blocked.includes(sessionId)) pending.blocked.push(sessionId)
}

function uniqueId() {
  // getRandomValues also works on WebKit versions that predate randomUUID and Web Locks.
  return Array.from(crypto.getRandomValues(new Uint32Array(4)), word => word.toString(16).padStart(8, '0')).join('')
}

function matches(a: VisitRecord['pointer'], b: Visit) {
  return a !== null && typeof a === 'object' && a.tableSessionId === b.tableSessionId && a.tableToken === b.tableToken
}

function generation(visit: Visit) {
  return visit.visitGeneration ?? '0'
}

function remember(record: VisitRecord, visit: Visit, revision: string, ended: boolean) {
  record.history ??= {}
  record.history[visit.tableSessionId] = { revision, generation: generation(visit), ended }
}

function clearDraft(key: string, record: VisitRecord, visit: Visit, store: IDBObjectStore) {
  const owner = /:(user:[0-9]+)$/.exec(key)?.[1]
  if (!owner) throw new Error('Missing cart owner for visit cleanup')
  discardTableCart(store, owner, visit.tableToken, visit.tableSessionId, visit.visitGeneration)
  record.cleanup = record.cleanup.filter(item => !(matches(item, visit) && generation(item) === generation(visit)))
}

const closed = new Set<string>()
const exitChannel = typeof BroadcastChannel === 'function' ? new BroadcastChannel('hookah-visit-ended') : null
const closedKey = (key: string, visit: Visit) => key + ':ended:' + visit.tableSessionId + ':' + generation(visit)

function observeEnd(key: string) {
  closed.add(key)
  window.dispatchEvent(new CustomEvent('hookah-visit-ended', { detail: key }))
}
if (exitChannel) exitChannel.onmessage = event => { if (typeof event.data === 'string') observeEnd(event.data) }
if (typeof window !== 'undefined') window.addEventListener('storage', event => {
  if (event.key?.includes(':ended:') && event.newValue === '1') observeEnd(event.key)
})

export function knownEnded(key: string, visit: Visit) {
  const marker = closedKey(key, visit)
  if (closed.has(marker)) return true
  try { return window.localStorage.getItem(marker) === '1' } catch { return false }
}
export function readSelection(key: string, live: () => boolean) {
  return withVisitRecord(key, (record, store) => {
    if (live()) for (const visit of record.cleanup) clearDraft(key, record, visit, store)
    return { pointer: record.pointer, revision: record.revision, history: record.history ?? {} }
  })
}

export function beginSelection(key: string): { intent: VisitIntent; ready: Promise<SelectionResult> } {
  const intent = { key, id: uniqueId() }
  // Only cold registrations can be overtaken before a transaction is enqueued.
  // Equal/coarsened times are ambiguous: keep the registered choice and offer a rescan.
  const cold = !visitStorageReady()
  const started = performance.timeOrigin + performance.now()
  const ready = withVisitRecord(key, record => {
    if (cold && record.lastIntentAt !== undefined &&
        (!Number.isFinite(started) || started <= record.lastIntentAt)) return 'superseded' as const
    // A warm registration already owns its place in the database queue. If the clock
    // cannot be compared across windows, require the next cold launch to rescan.
    record.lastIntentAt = Number.isFinite(started) ? started : Number.POSITIVE_INFINITY
    record.pending = { id: intent.id, blocked: [] }
    return 'current' as const
  }).then(result => result ?? 'unavailable' as const)
  return { intent, ready }
}

async function current(
  intent: VisitIntent, live: () => boolean,
  transition: (record: VisitRecord, pending: NonNullable<VisitRecord['pending']>, store: IDBObjectStore) => SelectionResult
): Promise<SelectionResult> {
  return await withVisitRecord(intent.key, (record, store) => {
    if (!live() || record.pending?.id !== intent.id) return 'superseded'
    return transition(record, record.pending, store)
  }) ?? 'unavailable'
}

export function checkSelection(intent: VisitIntent, live: () => boolean) {
  return current(intent, live, () => 'current')
}

// Accept only an authenticated response. A changed server generation makes the old
// cart unreachable even when deleting its LocalStorage key must be retried.
function accept(key: string, record: VisitRecord, visit: Visit, revision: string, store: IDBObjectStore) {
  const prior = record.history?.[visit.tableSessionId]
  const before = prior?.generation ?? '0'
  const next = generation(visit)
  if (next.length < before.length || (next.length === before.length && next < before)) return false
  if (next !== before) {
    clearDraft(key, record, visitPointer(visit.tableToken, visit.tableSessionId, before), store)
    record.exits = record.exits.filter(exit => exit.sessionId !== visit.tableSessionId || exit.generation === generation(visit))
  }
  remember(record, visit, revision, false)
  return true
}

export function confirmSelection(intent: VisitIntent, live: () => boolean, visit: Visit) {
  return current(intent, live, (record, pending, store) => {
    const previous = record.history?.[visit.tableSessionId]?.generation ?? '0'
    if (pending.blocked.includes(visit.tableSessionId) && previous === generation(visit)) return 'superseded'
    if (knownEnded(intent.key, visit) || !accept(intent.key, record, visit, intent.id, store)) return 'superseded'
    record.revision = intent.id
    record.pointer = visit
    record.pending = null
    return 'current'
  })
}

export type Observation = NonNullable<Awaited<ReturnType<typeof readSelection>>>

export async function confirmObservation(
  key: string, seen: Observation, visit: Visit, discovery: boolean, live: () => boolean
): Promise<SelectionResult> {
  return await withVisitRecord(key, (record, store) => {
    if (knownEnded(key, visit) || !live() || (discovery && record.revision !== seen.revision)) return 'superseded'
    const prior = record.history?.[visit.tableSessionId]
    if (prior?.revision !== seen.history[visit.tableSessionId]?.revision) return 'superseded'
    const changed = !prior || prior.ended || prior.generation !== generation(visit)
    if ((prior?.ended && prior.generation === generation(visit)) || (changed && !accept(key, record, visit, uniqueId(), store))) return 'superseded'
    if (matches(record.pointer, visit)) record.pointer = visit
    return 'current'
  }) ?? 'unavailable'
}

export function cancelSelection(intent: VisitIntent) {
  return withVisitRecord(intent.key, record => {
    if (record.pending?.id === intent.id) record.pending = null
  })
}

export function retainLegacyPointer(key: string, visit: Visit, live: () => boolean) {
  return withVisitRecord(key, record => {
    if (live() && !record.pointer && !record.pending && !record.revision) record.pointer = visit
  })
}

export function prepareExit(key: string, visit: Visit) {
  const id = uniqueId()
  const registered = withVisitRecord(key, record => {
    record.exits.push({ id, sessionId: visit.tableSessionId, generation: generation(visit) })
    return true
  })
  return { key, id, registered }
}

function endVisit(key: string, record: VisitRecord, visit: Visit, revision: string, store: IDBObjectStore) {
  const existing = record.history?.[visit.tableSessionId]
  if (existing && existing.generation !== generation(visit)) {
    clearDraft(key, record, visit, store)
    return
  }
  remember(record, visit, revision, true)
  blockClosedSession(record.pending, visit.tableSessionId)
  if (!record.pointer || matches(record.pointer, visit)) {
    record.pointer = null
    record.revision = revision
  }
  clearDraft(key, record, visit, store)
}

export async function finishExit(exit: ReturnType<typeof prepareExit>, visit?: Visit) {
  const registered = await exit.registered
  if (visit) {
    const marker = closedKey(exit.key, visit)
    closed.add(marker)
    exitChannel?.postMessage(marker)
  }
  const committed = await withVisitRecord(exit.key, (record, store) => {
    record.exits = record.exits.filter(item => item.id !== exit.id)
    if (visit) endVisit(exit.key, record, visit, exit.id, store)
    return true
  })
  if (visit && (!registered || !committed || !exitChannel)) {
    // Denial only. This fallback can never admit a guest or supply a cart.
    try { window.localStorage.setItem(closedKey(exit.key, visit), '1') } catch { /* Live peers may still receive the notification. */ }
  }
}

export function forgetRejectedVisit(key: string, visit: Visit, seen: Observation, live: () => boolean) {
  return withVisitRecord(key, (record, store) => {
    if (live() && record.history?.[visit.tableSessionId]?.revision === seen.history[visit.tableSessionId]?.revision) {
      endVisit(key, record, visit, uniqueId(), store)
    }
  })
}
