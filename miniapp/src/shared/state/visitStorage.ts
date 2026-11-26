import { normalizeTableToken } from '../validation/tableToken'

export type Visit = { tableToken: string; tableSessionId: number; visitGeneration?: string }
export type VisitPointer = Visit | 'lost' | 'unavailable' | null
export type VisitRecord = {
  pointer: VisitPointer
  history?: Record<string, { revision: string; generation: string; ended: boolean }>
  lastIntentAt?: number
  revision: string | null
  pending: { id: string; blocked: number[] } | null
  exits: { id: string; sessionId: number; generation?: string }[]
  cleanup: Visit[]
}

export function validGeneration(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value)
}

export function visitPointer(tableToken: string, tableSessionId: number, generation?: string | null): Visit {
  return generation && generation !== '0' ? { tableToken, tableSessionId, visitGeneration: generation } : { tableToken, tableSessionId }
}

export function visitStorageReady(): boolean {
  return connection !== undefined
}

export function parseVisit(raw: string | null): VisitPointer {
  if (!raw) return null
  try {
    const value = JSON.parse(raw)
    if (value === 'unavailable') return value
    if (typeof value?.tableToken === 'string' && normalizeTableToken(value.tableToken) === value.tableToken &&
        Number.isSafeInteger(value.tableSessionId) && value.tableSessionId > 0) {
      if (value.visitGeneration !== undefined && !validGeneration(value.visitGeneration)) return 'lost'
      return visitPointer(value.tableToken, value.tableSessionId, value.visitGeneration)
    }
  } catch { /* An unrecognizable pointer requires a new scan. */ }
  return 'lost'
}

function migrate(key: string): VisitRecord {
  const pointer = parseVisit(window.localStorage.getItem(key))
  const raw = window.localStorage.getItem(key + ':selection')
  let previous
  try { previous = raw && JSON.parse(raw) } catch { /* Keep the confirmed visit. */ }
  const ended: number[] = Array.isArray(previous?.ended) ? previous.ended.filter(Number.isSafeInteger) : []
  const pending = typeof previous?.latest === 'string' && previous.latest !== previous.confirmed
    ? { id: previous.latest, blocked: ended }
    : null
  // A legacy exit also prevents a stale window from republishing its private pointer.
  const revision = typeof previous?.confirmed === 'string' ? previous.confirmed : ended.length ? 'legacy-exit' : null
  return { pointer, revision, pending, exits: [], cleanup: [] }
}

const storeName = 'visits'
const migrated = new Set<string>()
let connection: IDBDatabase | undefined
let opening: Promise<IDBDatabase> | undefined

function database(): Promise<IDBDatabase> {
  return opening ??= new Promise<IDBDatabase>((resolve, reject) => {
    const request = window.indexedDB.open('hookah-guest-visits', 1)
    let blocked = false
    request.onblocked = () => {
      blocked = true
      reject(new Error('Visit storage upgrade blocked'))
    }
    request.onupgradeneeded = () => request.result.createObjectStore(storeName)
    request.onerror = () => reject(request.error)
    request.onsuccess = () => {
      const db = request.result
      if (blocked) { db.close(); return }
      const close = () => {
        if (connection === db) connection = undefined
        db.close()
      }
      db.onversionchange = close
      db.onclose = close
      connection = db
      resolve(db)
    }
  }).finally(() => { opening = undefined })
}

// A short read/write transaction orders transitions across all same-origin WebViews.
// The callback must be synchronous. Only a committed transaction can publish its result.
export async function updateRecordsWithPrefix<T>(prefix: string, update: (record: T) => boolean) {
  const db = connection ?? await database()
  const tx = db.transaction(storeName, 'readwrite')
  await new Promise<void>((resolve, reject) => {
    const cursor = tx.objectStore(storeName).openCursor(IDBKeyRange.bound(prefix, prefix + '\uffff'))
    tx.oncomplete = () => resolve()
    tx.onabort = () => reject(tx.error)
    cursor.onsuccess = () => {
      const row = cursor.result
      if (!row) return
      const record = row.value
      if (update(record)) row.update(record)
      row.continue()
    }
  })
}

export async function transactRecord<R, T>(
  key: string, initial: () => R, change: (record: R, store: IDBObjectStore) => T,
  committed?: () => void
): Promise<T> {
  const db = connection ?? await database()
  try {
    const tx = db.transaction(storeName, 'readwrite')
    return await new Promise<T>((resolve, reject) => {
      let result: T
      const store = tx.objectStore(storeName)
      const get = store.get(key)
      tx.onabort = () => reject(tx.error ?? new Error('Transaction aborted'))
      tx.oncomplete = () => { committed?.(); resolve(result) }
      get.onsuccess = () => {
        try {
          const record = get.result ?? initial()
          result = change(record, store)
          store.put(record, key)
        } catch (error) { tx.abort(); reject(error) }
      }
    })
  } catch (error) {
    if (connection === db) { connection = undefined; db.close() }
    throw error
  }
}

export async function withVisitRecord<T>(key: string, change: (record: VisitRecord, store: IDBObjectStore) => T): Promise<T | null> {
  if (key.endsWith(':unknown')) return null
  try {
    return await transactRecord(key, () => migrate(key), change, () => {
      if (migrated.has(key)) return
      try {
        window.localStorage.removeItem(key)
        window.localStorage.removeItem(key + ':selection')
        migrated.add(key)
      } catch { /* A committed IndexedDB record takes precedence. */ }
    })
  } catch { return null }
}
