export type CartSnapshot = {
  items: Map<number, number>
  totalQty: number
  distinctCount: number
}

type CartListener = (snapshot: CartSnapshot) => void

type SetQtyResult = {
  ok: boolean
  reason?: 'limit' | 'invalid'
}

const MAX_QTY = 50
const MAX_DISTINCT = 50
const listeners = new Set<CartListener>()
let items = new Map<number, number>()
let totalQty = 0

function clampQty(qty: number): number {
  return Math.max(0, Math.min(MAX_QTY, qty))
}

function normalizeQty(qty: number): number {
  if (!Number.isFinite(qty)) {
    return 0
  }
  return clampQty(Math.trunc(qty))
}

function isValidItemId(itemId: number): boolean {
  return Number.isInteger(itemId) && itemId > 0
}

function buildSnapshot(): CartSnapshot {
  return {
    items: new Map(items),
    totalQty,
    distinctCount: items.size
  }
}

function notify() {
  const snapshot = buildSnapshot()
  listeners.forEach((listener) => listener(snapshot))
}

function updateTotals() {
  totalQty = 0
  for (const [itemId, qty] of items) {
    const nextQty = normalizeQty(qty)
    if (nextQty === 0) {
      items.delete(itemId)
      continue
    }
    items.set(itemId, nextQty)
    totalQty += nextQty
  }
}

export function getCartSnapshot(): CartSnapshot {
  return buildSnapshot()
}

export function subscribeCart(listener: CartListener): () => void {
  listeners.add(listener)
  listener(buildSnapshot())
  return () => {
    listeners.delete(listener)
  }
}

export function addToCart(itemId: number): SetQtyResult {
  if (!isValidItemId(itemId)) {
    return { ok: false, reason: 'invalid' }
  }
  const current = normalizeQty(items.get(itemId) ?? 0)
  if (current === 0 && items.size >= MAX_DISTINCT) {
    return { ok: false, reason: 'limit' }
  }
  const nextQty = normalizeQty(current + 1)
  if (nextQty === 0) {
    items.delete(itemId)
  } else {
    items.set(itemId, nextQty)
  }
  updateTotals()
  notify()
  return { ok: true }
}

export function removeFromCart(itemId: number): void {
  if (!isValidItemId(itemId)) {
    return
  }
  const current = items.get(itemId)
  if (!current) {
    return
  }
  const nextQty = normalizeQty(current - 1)
  if (nextQty === 0) {
    items.delete(itemId)
  } else {
    items.set(itemId, nextQty)
  }
  updateTotals()
  notify()
}

export function setCartQty(itemId: number, qty: number): SetQtyResult {
  if (!isValidItemId(itemId)) {
    return { ok: false, reason: 'invalid' }
  }
  const nextQty = normalizeQty(qty)
  const hasItem = items.has(itemId)
  if (!hasItem && nextQty > 0 && items.size >= MAX_DISTINCT) {
    return { ok: false, reason: 'limit' }
  }
  if (nextQty === 0) {
    items.delete(itemId)
  } else {
    items.set(itemId, nextQty)
  }
  updateTotals()
  notify()
  return { ok: true }
}

export function clearCart(): void {
  items = new Map()
  totalQty = 0
  notify()
}
