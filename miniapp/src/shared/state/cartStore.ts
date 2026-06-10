import { getItemMeta, type ItemMeta, updateItemCache } from './itemCache'
import { getTelegramContext } from '../telegram'

export type CartLineOptionInput = {
  selectedOptionId?: number | null
  selectedOptionName?: string | null
  priceDeltaMinor?: number | null
}

export type CartLine = {
  key: string
  itemId: number
  qty: number
  selectedOptionId?: number | null
  selectedOptionName?: string | null
  priceDeltaMinor?: number | null
}

export type CartSnapshot = {
  items: Map<string, CartLine>
  totalQty: number
  distinctCount: number
  commentDraft: string
}

type CartListener = (snapshot: CartSnapshot) => void

type SetQtyResult = {
  ok: boolean
  reason?: 'limit' | 'invalid'
}

type PersistedCartLine = {
  itemId: number
  qty: number
  selectedOptionId?: number | null
  selectedOptionName?: string | null
  priceDeltaMinor?: number | null
}

type LegacyPersistedCartEntry = [number, number]

type PersistedDraft = {
  items: Array<LegacyPersistedCartEntry | PersistedCartLine>
  itemMeta?: ItemMeta[]
  commentDraft?: string
}

const MAX_QTY = 50
const MAX_DISTINCT = 50
const cartDraftLocalStoragePrefix = 'hookah_guest_cart_draft:'
const listeners = new Set<CartListener>()
let items = new Map<string, CartLine>()
let totalQty = 0
let commentDraft = ''
let activeTableToken: string | null = null
let activeDraftStorageKey: string | null = null

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

function normalizeSelectedOptionId(selectedOptionId: number | null | undefined): number | null {
  if (selectedOptionId == null) {
    return null
  }
  const normalized = Number(selectedOptionId)
  return Number.isInteger(normalized) && normalized > 0 ? normalized : null
}

export function buildCartLineKey(itemId: number, selectedOptionId?: number | null): string {
  return `${itemId}:${normalizeSelectedOptionId(selectedOptionId) ?? 'base'}`
}

function resolveLineOption(
  itemId: number,
  input?: CartLineOptionInput | null
): { selectedOptionId: number | null; selectedOptionName: string | null; priceDeltaMinor: number | null } {
  const selectedOptionId = normalizeSelectedOptionId(input?.selectedOptionId)
  if (selectedOptionId == null) {
    return {
      selectedOptionId: null,
      selectedOptionName: null,
      priceDeltaMinor: null
    }
  }
  const metaOption = getItemMeta(itemId)?.options?.find((option) => option.id === selectedOptionId)
  const selectedOptionName = input?.selectedOptionName?.trim() || metaOption?.name || null
  const rawDelta = input?.priceDeltaMinor
  const priceDeltaMinor =
    typeof rawDelta === 'number' && Number.isFinite(rawDelta) ? Math.trunc(rawDelta) : metaOption?.priceDeltaMinor ?? null
  return {
    selectedOptionId,
    selectedOptionName,
    priceDeltaMinor
  }
}

function buildLine(itemId: number, qty: number, input?: CartLineOptionInput | null, existing?: CartLine): CartLine {
  const option = resolveLineOption(itemId, input)
  const selectedOptionId = option.selectedOptionId ?? existing?.selectedOptionId ?? null
  const key = buildCartLineKey(itemId, selectedOptionId)
  return {
    key,
    itemId,
    qty,
    selectedOptionId,
    selectedOptionName: option.selectedOptionName ?? existing?.selectedOptionName ?? null,
    priceDeltaMinor: option.priceDeltaMinor ?? existing?.priceDeltaMinor ?? null
  }
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
  const seenItemIds = new Set<number>()
  for (const line of items.values()) {
    if (seenItemIds.has(line.itemId)) {
      continue
    }
    seenItemIds.add(line.itemId)
    const meta = getItemMeta(line.itemId)
    if (meta) {
      itemMeta.push(meta)
    }
  }
  const payload: PersistedDraft = {
    items: Array.from(items.values()).map((line) => ({
      itemId: line.itemId,
      qty: line.qty,
      selectedOptionId: line.selectedOptionId ?? null,
      selectedOptionName: line.selectedOptionName ?? null,
      priceDeltaMinor: line.priceDeltaMinor ?? null
    }))
  }
  if (itemMeta.length > 0) {
    payload.itemMeta = itemMeta
  }
  if (commentDraft) {
    payload.commentDraft = commentDraft
  }
  const serialized = JSON.stringify(payload)
  setLocalStorageItem(activeDraftStorageKey, serialized)
}

function normalizePersistedLine(entry: LegacyPersistedCartEntry | PersistedCartLine): PersistedCartLine | null {
  if (Array.isArray(entry)) {
    if (entry.length !== 2) {
      return null
    }
    return {
      itemId: Number(entry[0]),
      qty: Number(entry[1])
    }
  }
  if (!entry || typeof entry !== 'object') {
    return null
  }
  return {
    itemId: Number(entry.itemId),
    qty: Number(entry.qty),
    selectedOptionId: normalizeSelectedOptionId(entry.selectedOptionId),
    selectedOptionName: typeof entry.selectedOptionName === 'string' ? entry.selectedOptionName : null,
    priceDeltaMinor:
      typeof entry.priceDeltaMinor === 'number' && Number.isFinite(entry.priceDeltaMinor)
        ? Math.trunc(entry.priceDeltaMinor)
        : null
  }
}

function loadDraftFromStorage(storageKey: string): { items: Map<string, CartLine>; itemMeta: ItemMeta[]; commentDraft: string } {
  const raw = getLocalStorageItem(storageKey)
  if (!raw) {
    return { items: new Map(), itemMeta: [], commentDraft: '' }
  }
  try {
    const parsed = JSON.parse(raw) as PersistedDraft
    if (!Array.isArray(parsed.items)) {
      return { items: new Map(), itemMeta: [], commentDraft: '' }
    }
    const restored = new Map<string, CartLine>()
    for (const entry of parsed.items) {
      const line = normalizePersistedLine(entry)
      if (!line) {
        continue
      }
      const itemId = Number(line.itemId)
      const qty = normalizeQty(Number(line.qty))
      if (!isValidItemId(itemId) || qty <= 0) {
        continue
      }
      const key = buildCartLineKey(itemId, line.selectedOptionId)
      const existing = restored.get(key)
      if (!existing && restored.size >= MAX_DISTINCT) {
        continue
      }
      const nextQty = normalizeQty((existing?.qty ?? 0) + qty)
      restored.set(
        key,
        buildLine(
          itemId,
          nextQty,
          {
            selectedOptionId: line.selectedOptionId,
            selectedOptionName: line.selectedOptionName,
            priceDeltaMinor: line.priceDeltaMinor
          },
          existing
        )
      )
    }
    const restoredMeta = Array.isArray(parsed.itemMeta) ? parsed.itemMeta : []
    const restoredComment = typeof parsed.commentDraft === 'string' ? parsed.commentDraft : ''
    return { items: restored, itemMeta: restoredMeta, commentDraft: restoredComment }
  } catch {
    return { items: new Map(), itemMeta: [], commentDraft: '' }
  }
}

function buildSnapshot(): CartSnapshot {
  const snapshotItems = Array.from(items.entries()).map(([key, line]): [string, CartLine] => [key, { ...line }])
  return {
    items: new Map(snapshotItems),
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
  for (const [key, line] of items) {
    const nextQty = normalizeQty(line.qty)
    if (nextQty === 0) {
      items.delete(key)
      continue
    }
    items.set(key, { ...line, qty: nextQty, key })
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

export function addToCart(itemId: number, option?: CartLineOptionInput | null): SetQtyResult {
  if (!isValidItemId(itemId)) {
    return { ok: false, reason: 'invalid' }
  }
  const key = buildCartLineKey(itemId, option?.selectedOptionId)
  const currentLine = items.get(key)
  const current = normalizeQty(currentLine?.qty ?? 0)
  if (current === 0 && items.size >= MAX_DISTINCT) {
    return { ok: false, reason: 'limit' }
  }
  const nextQty = normalizeQty(current + 1)
  if (nextQty === 0) {
    items.delete(key)
  } else {
    items.set(key, buildLine(itemId, nextQty, option, currentLine))
  }
  updateTotals()
  persistActiveDraft()
  notify()
  return { ok: true }
}

export function removeFromCart(itemId: number, selectedOptionId?: number | null): void {
  if (!isValidItemId(itemId)) {
    return
  }
  removeCartLine(buildCartLineKey(itemId, selectedOptionId))
}

export function removeCartLine(lineKey: string): void {
  const currentLine = items.get(lineKey)
  if (!currentLine) {
    return
  }
  const nextQty = normalizeQty(currentLine.qty - 1)
  if (nextQty === 0) {
    items.delete(lineKey)
  } else {
    items.set(lineKey, { ...currentLine, qty: nextQty })
  }
  updateTotals()
  persistActiveDraft()
  notify()
}

export function setCartQty(itemId: number, qty: number, selectedOptionId?: number | null): SetQtyResult {
  if (!isValidItemId(itemId)) {
    return { ok: false, reason: 'invalid' }
  }
  return setCartLineQty(buildCartLineKey(itemId, selectedOptionId), qty, itemId, selectedOptionId)
}

export function setCartLineQty(
  lineKey: string,
  qty: number,
  fallbackItemId?: number,
  fallbackSelectedOptionId?: number | null
): SetQtyResult {
  const currentLine = items.get(lineKey)
  const itemId = currentLine?.itemId ?? fallbackItemId
  if (!itemId || !isValidItemId(itemId)) {
    return { ok: false, reason: 'invalid' }
  }
  const nextQty = normalizeQty(qty)
  const hasItem = items.has(lineKey)
  if (!hasItem && nextQty > 0 && items.size >= MAX_DISTINCT) {
    return { ok: false, reason: 'limit' }
  }
  if (nextQty === 0) {
    items.delete(lineKey)
  } else {
    items.set(
      lineKey,
      buildLine(
        itemId,
        nextQty,
        {
          selectedOptionId: currentLine?.selectedOptionId ?? fallbackSelectedOptionId ?? null,
          selectedOptionName: currentLine?.selectedOptionName ?? null,
          priceDeltaMinor: currentLine?.priceDeltaMinor ?? null
        },
        currentLine
      )
    )
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
