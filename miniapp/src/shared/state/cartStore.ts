import { getItemMeta, type ItemMeta, updateItemCache } from './itemCache'
import { getTelegramContext } from '../telegram'

export type CartSnapshot = {
  items: Map<number, number>
  totalQty: number
  distinctCount: number
  commentDraft: string
}

type CartListener = (snapshot: CartSnapshot) => void

type SetQtyResult = {
  ok: boolean
  reason?: 'limit' | 'invalid'
}

const MAX_QTY = 50
const MAX_DISTINCT = 50
const cartDraftLocalStoragePrefix = 'hookah_guest_cart_draft:'
const listeners = new Set<CartListener>()
let items = new Map<number, number>()
let totalQty = 0
let commentDraft = ''
let activeTableToken: string | null = null
let activeDraftStorageKey: string | null = null

type PersistedDraft = {
  items: Array<[number, number]>
  itemMeta?: ItemMeta[]
  commentDraft?: string
}

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

function getDraftStorageKey(tableToken: string): string {
  const userId = getTelegramContext().telegramUserId
  const userPart = userId ? `user:${userId}` : 'user:unknown'
  return `${cartDraftLocalStoragePrefix}${userPart}:${tableToken}`
}

function setLocalStorageItem(key: string, value: string): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.localStorage.setItem(key, value)
  } catch {
    // ignore local storage errors
  }
}

function removeLocalStorageItem(key: string): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.localStorage.removeItem(key)
  } catch {
    // ignore local storage errors
  }
}

function getLocalStorageItem(key: string): string | null {
  if (typeof window === 'undefined') {
    return null
  }
  try {
    const value = window.localStorage.getItem(key)
    return value ? value : null
  } catch {
    return null
  }
}

function persistActiveDraft(): void {
  if (!activeDraftStorageKey) {
    return
  }
  if (items.size === 0 && commentDraft.trim() === '') {
    removeLocalStorageItem(activeDraftStorageKey)
    return
  }
  const itemMeta: ItemMeta[] = []
  for (const [itemId] of items) {
    const meta = getItemMeta(itemId)
    if (meta) {
      itemMeta.push(meta)
    }
  }
  const payload: PersistedDraft = { items: Array.from(items.entries()) }
  if (itemMeta.length > 0) {
    payload.itemMeta = itemMeta
  }
  if (commentDraft) {
    payload.commentDraft = commentDraft
  }
  const serialized = JSON.stringify(payload)
  setLocalStorageItem(activeDraftStorageKey, serialized)
}

function loadDraftFromStorage(storageKey: string): { items: Map<number, number>; itemMeta: ItemMeta[]; commentDraft: string } {
  const raw = getLocalStorageItem(storageKey)
  if (!raw) {
    return { items: new Map(), itemMeta: [], commentDraft: '' }
  }
  try {
    const parsed = JSON.parse(raw) as PersistedDraft
    if (!Array.isArray(parsed.items)) {
      return { items: new Map(), itemMeta: [], commentDraft: '' }
    }
    const restored = new Map<number, number>()
    for (const entry of parsed.items) {
      if (!Array.isArray(entry) || entry.length !== 2) {
        continue
      }
      const itemId = Number(entry[0])
      const qty = normalizeQty(Number(entry[1]))
      if (!isValidItemId(itemId) || qty <= 0) {
        continue
      }
      restored.set(itemId, qty)
    }
    const restoredMeta = Array.isArray(parsed.itemMeta) ? parsed.itemMeta : []
    const restoredComment = typeof parsed.commentDraft === 'string' ? parsed.commentDraft : ''
    return { items: restored, itemMeta: restoredMeta, commentDraft: restoredComment }
  } catch {
    return { items: new Map(), itemMeta: [], commentDraft: '' }
  }
}

function buildSnapshot(): CartSnapshot {
  return {
    items: new Map(items),
    totalQty,
    distinctCount: items.size,
    commentDraft
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

export function setCartTableToken(tableToken: string | null): void {
  const normalizedToken = tableToken?.trim() || null
  if (normalizedToken === activeTableToken) {
    return
  }
  activeTableToken = normalizedToken
  activeDraftStorageKey = normalizedToken ? getDraftStorageKey(normalizedToken) : null
  if (!activeDraftStorageKey) {
    items = new Map()
    totalQty = 0
    commentDraft = ''
    notify()
    return
  }
  const restoredDraft = loadDraftFromStorage(activeDraftStorageKey)
  items = restoredDraft.items
  commentDraft = restoredDraft.commentDraft
  if (restoredDraft.itemMeta.length > 0) {
    updateItemCache(restoredDraft.itemMeta)
  }
  updateTotals()
  notify()
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
  persistActiveDraft()
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
  persistActiveDraft()
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
  persistActiveDraft()
  notify()
  return { ok: true }
}

export function setCartCommentDraft(value: string): void {
  commentDraft = value
  persistActiveDraft()
  notify()
}

export function clearCart(): void {
  items = new Map()
  totalQty = 0
  commentDraft = ''
  persistActiveDraft()
  notify()
}
